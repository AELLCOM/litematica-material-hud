package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.shaded.ui.Strings;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Одна загрузка строк таблицы: файл — «загрузка схемы» (Litematica, клиентский поток) и «подсчёт»
 * (M25: фон + клиент); размещение — сразу подсчёт его схемы; список HUD — сразу готово. Всё, что
 * трогает поля, выполняется на клиентском потоке (обратные вызовы — через {@code Minecraft} как
 * {@code Executor}), поэтому панель читает состояние без блокировок.
 *
 * <p>Отменить сам счёт M25 нельзя: отменённая работа просто не отдаёт результат, а панель уже
 * смотрит на новую.
 */
@Environment(EnvType.CLIENT)
public final class MaterialsJob
{
    public enum Stage
    {
        /** Litematica читает файл схемы. */
        LOAD,
        /** Счёт материалов (M25). */
        COUNT,
        DONE,
        FAILED
    }

    private static final String KEY_NO_SCHEMATIC = "layerlist.schematics.materials.error.no_schematic";
    private static final String KEY_NO_RESULT = "layerlist.schematics.materials.error.no_result";

    private TableSource source;
    private final boolean recount;
    private final long started = Util.getMillis();

    private Stage stage = Stage.COUNT;
    private List<MaterialRow> rows = List.of();
    /** Схема Litematica, по которой считаются строки (у списка HUD — нет): её блоки меняет замена (0.0.27). */
    private @Nullable LitematicaSchematic schematic;
    private String error = "";
    private boolean cancelled;

    private MaterialsJob(TableSource source, boolean recount)
    {
        this.source = source;
        this.recount = recount;
    }

    /**
     * Начать загрузку. Только клиентский поток.
     *
     * @param recount забыть прежний счёт схемы и перечитать запись каталога (кнопка «Обновить»)
     */
    public static MaterialsJob start(TableSource source, boolean recount)
    {
        MaterialsJob job = new MaterialsJob(source, recount);

        switch (source)
        {
            case TableSource.File file -> job.loadFile(file.entry());
            case TableSource.Placed placed -> job.countPlacement(placed.placement());
            case TableSource.Hud hud -> job.readHud();
            case TableSource.Area area -> job.finish(area.rows());
        }

        return job;
    }

    /** Источник; у файла после «Обновить» — свежая запись каталога (размер и время могли смениться). */
    public TableSource source()
    {
        return this.source;
    }

    public Stage stage()
    {
        return this.stage;
    }

    public boolean running()
    {
        return this.stage == Stage.LOAD || this.stage == Stage.COUNT;
    }

    /** Шаг из скольких: у файла два (загрузка, подсчёт), у размещения один. */
    public int step()
    {
        return this.stage == Stage.LOAD ? 1 : this.source instanceof TableSource.File ? 2 : 1;
    }

    public int steps()
    {
        return this.source instanceof TableSource.File ? 2 : 1;
    }

    /** Миллисекунды с начала загрузки ({@link Util#getMillis()}). */
    public long elapsed()
    {
        return Math.max(0L, Util.getMillis() - this.started);
    }

    /** Строки после {@link Stage#DONE}; до того — пусто. */
    public List<MaterialRow> rows()
    {
        return this.rows;
    }

    /** Схема строк — файл или схема размещения; {@code null} — список HUD или ещё не загружена. */
    public @Nullable LitematicaSchematic schematic()
    {
        return this.schematic;
    }

    /** Текст ошибки для игрока после {@link Stage#FAILED}. */
    public String error()
    {
        return this.error;
    }

    /** Результат больше не нужен (сменили источник, ушли с экрана). */
    public void cancel()
    {
        this.cancelled = true;
    }

    // ===== Шаги =====

    private void loadFile(SchematicEntry entry)
    {
        SchematicEntry target = entry;

        if (this.recount)
        {
            // Файл могли пересохранить: запись каталога с новым размером/временем — ключ свежей загрузки
            SchematicEntry fresh = SchematicLibrary.get().entry(entry.path());

            if (fresh != null && ! fresh.directory())
            {
                target = fresh;
                this.source = new TableSource.File(fresh);
            }
        }

        this.stage = Stage.LOAD;
        CompletableFuture<LoadedSchematic> loading;

        try
        {
            loading = SchematicLibrary.get().load(target);
        }
        catch (RuntimeException e)
        {
            loading = CompletableFuture.failedFuture(e);
        }

        loading.whenCompleteAsync((loaded, error) ->
        {
            if (this.cancelled)
            {
                return;
            }

            if (error != null || loaded == null)
            {
                this.fail(SchematicLibrary.errorText(orEmpty(error)));
                return;
            }

            this.count(loaded);
        }, Minecraft.getInstance());
    }

    private void countPlacement(SchematicPlacement placement)
    {
        LitematicaSchematic schematic;

        try
        {
            schematic = placement.getSchematic();
        }
        catch (RuntimeException e)
        {
            this.fail(SchematicMaterials.describe(e));
            return;
        }

        if (schematic == null)
        {
            this.fail(SchematicMaterials.describe(new IllegalStateException(Strings.plain(KEY_NO_SCHEMATIC))));
            return;
        }

        this.count(new LoadedSchematic(placementEntry(placement), schematic));
    }

    /**
     * Запись каталога для схемы размещения: {@link LoadedSchematic} без неё не создать, а счёт M25
     * смотрит только на объект Litematica. Размещение без файла (схема из памяти) получает запись-имя.
     */
    private static SchematicEntry placementEntry(SchematicPlacement placement)
    {
        Path file = placement.getSchematicFile();
        SchematicEntry entry = file == null ? null : SchematicLibrary.get().entry(file);

        if (entry != null)
        {
            return entry;
        }

        Path path;

        try
        {
            path = file != null ? file : Path.of(placement.getName());
        }
        catch (InvalidPathException | NullPointerException e)
        {
            path = Path.of("schematic");
        }

        return new SchematicEntry(path, false, SchematicFormat.of(path), 0L, 0L);
    }

    private void count(LoadedSchematic loaded)
    {
        this.stage = Stage.COUNT;
        this.schematic = loaded.litematica();
        // Замены файла — до счёта: строки сразу про то, что стоит в голограммах (обычно уже наложены при чтении)
        BlockReplacements.ensure(loaded.litematica());

        if (this.recount)
        {
            SchematicMaterials.forget(loaded);
        }

        CompletableFuture<List<MaterialRow>> counting;

        try
        {
            counting = SchematicMaterials.ofSchematic(loaded);
        }
        catch (RuntimeException e)
        {
            counting = CompletableFuture.failedFuture(e);
        }

        counting.whenCompleteAsync((rows, error) ->
        {
            if (this.cancelled)
            {
                return;
            }

            if (error != null || rows == null)
            {
                this.fail(SchematicMaterials.describe(orEmpty(error)));
                return;
            }

            this.finish(rows);
        }, Minecraft.getInstance());
    }

    private void readHud()
    {
        try
        {
            this.finish(SchematicMaterials.ofHud());
        }
        catch (RuntimeException | LinkageError e)
        {
            // Тик HUD с той же ошибкой упал бы громко; здесь — текстом в панели
            this.fail(SchematicMaterials.describe(e));
        }
    }

    /** Будущее завершилось без результата и без ошибки — тоже ошибка, но со словами для игрока. */
    private static Throwable orEmpty(@Nullable Throwable error)
    {
        return error != null ? error : new IllegalStateException(Strings.plain(KEY_NO_RESULT));
    }

    private void finish(List<MaterialRow> rows)
    {
        this.rows = List.copyOf(rows);
        this.stage = Stage.DONE;
    }

    private void fail(@Nullable String message)
    {
        this.error = message == null ? "" : message;
        this.stage = Stage.FAILED;
    }
}
