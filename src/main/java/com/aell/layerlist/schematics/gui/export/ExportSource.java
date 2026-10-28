package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.MaterialSource;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Источник строк окна выгрузки: файл схемы целиком, текущий список HUD или строки, уже
 * посчитанные вкладкой «Материалы». Строки считаются один раз — при первом обращении (выбор в
 * списке источников), дальше окно переключает фильтр без пересчёта.
 *
 * <p>Будущее строк завершается на клиентском потоке (так устроены загрузка M22 и счёт M25); окно
 * его не ждёт, а опрашивает каждый кадр.
 *
 * <p>У источника — и имя файла выгрузки ({@link ExportNames}, 0.0.27): у списка HUD оно берётся в тот
 * же момент, что и строки, — схемы тех размещений, что эти строки посчитали. И вид ({@link Kind}): от него
 * зависит, что значит «Только недостающее» — окно пишет это под переключателем.
 */
@Environment(EnvType.CLIENT)
public final class ExportSource
{
    /** Ключ подписи M25 «Схема: …» — та же подпись, что у переключателя источника вкладки «Материалы». */
    private static final String KEY_SCHEMATIC = "layerlist.schematics.materials.source.schematic";

    /**
     * Откуда строки — от этого зависит «не хватает» ({@code MaterialRow.missing}).
     * <ul>
     *   <li>{@code SCHEMATIC} — файл или размещение целиком: с миром не сверяется, {@code missing == total};</li>
     *   <li>{@code PLACED} — схема, размещённая в мире, сверена с ним по всей постройке (слои не важны): {@code total} —
     *       вся схема, {@code missing} — что ещё не поставлено на месте;</li>
     *   <li>{@code HUD} — счёт HUD: пустые места включённых размещений в его слоях; не тот блок или не то
     *       состояние — только при выключенной «Только пустые места» ({@code shortfallOnlyMissing}).</li>
     * </ul>
     */
    public enum Kind
    {
        SCHEMATIC,
        PLACED,
        HUD
    }

    private final String label;
    private final Supplier<String> namer;
    private final Supplier<CompletableFuture<List<MaterialRow>>> loader;
    private final @Nullable Kind kind;
    private final boolean narrowed;
    private final @Nullable SchematicEntry entry;
    private volatile long disabledBlocks;
    private @Nullable Supplier<List<LitematicaSchematic>> countDisabled;
    private @Nullable CompletableFuture<List<MaterialRow>> rows;
    private @Nullable String fileName;

    private ExportSource(String label, Supplier<String> namer, Supplier<CompletableFuture<List<MaterialRow>>> loader,
                         @Nullable Kind kind, boolean narrowed, @Nullable SchematicEntry entry, long disabledBlocks)
    {
        this.label = label;
        this.namer = namer;
        this.loader = loader;
        this.kind = kind;
        this.narrowed = narrowed;
        this.entry = entry;
        this.disabledBlocks = disabledBlocks;
    }

    /** Считать отключённые блоки схем, что отдаёт {@code schematics}, когда строки загружены. */
    private ExportSource countsDisabled(Supplier<List<LitematicaSchematic>> schematics)
    {
        this.countDisabled = schematics;
        return this;
    }

    /** Файл схемы: Litematica загружает его (клиентский поток), M25 считает материалы. */
    public static ExportSource schematic(SchematicEntry entry)
    {
        Objects.requireNonNull(entry, "entry");

        List<LitematicaSchematic> loadedBox = new CopyOnWriteArrayList<>();

        return new ExportSource(I18n.get(KEY_SCHEMATIC, entry.name()), () -> ExportNames.of(entry), () ->
                SchematicLibrary.get().load(entry).thenCompose(loaded ->
                {
                    loadedBox.add(loaded.litematica());
                    return new MaterialSource.Schematic(loaded).rows();
                }), Kind.SCHEMATIC, false, entry, 0L).countsDisabled(() -> List.copyOf(loadedBox));
    }

    /** Список HUD: недостающее в мире для включённых размещений. */
    public static ExportSource hud()
    {
        MaterialSource.Hud hud = new MaterialSource.Hud();

        return new ExportSource(hud.label(), ExportNames::hud, hud::rows, Kind.HUD, false, null, 0L).countsDisabled(BlockReplacements::hudSchematics);
    }

    /** Готовые строки; файл называется по подписи (так было до 0.0.27). */
    public static ExportSource rows(String label, List<MaterialRow> rows)
    {
        return rows(label, rows, label);
    }

    /** Готовые строки и имя схемы для файла; вид неизвестен — окно не пишет, что значит фильтр. */
    public static ExportSource rows(String label, List<MaterialRow> rows, String fileName)
    {
        return rows(label, rows, fileName, null, false);
    }

    /**
     * Готовые строки (вкладка «Материалы» передаёт то, что уже показывает), имя схемы для файла, вид и
     * {@code narrowed} — строки сужены поиском или фильтром таблицы.
     */
    public static ExportSource rows(String label, List<MaterialRow> rows, String fileName, @Nullable Kind kind,
                                    boolean narrowed)
    {
        return rows(label, rows, fileName, kind, narrowed, null);
    }

    /**
     * То же и файл схемы строк ({@code entry}; у размещения — файл его схемы): по нему рисуется постер-превью
     * выгрузки «Картинка» (0.0.27). {@code null} — постера нет (список HUD, размещение без файла).
     */
    public static ExportSource rows(String label, List<MaterialRow> rows, String fileName, @Nullable Kind kind,
                                    boolean narrowed, @Nullable SchematicEntry entry)
    {
        return rows(label, rows, fileName, kind, narrowed, entry, 0L);
    }

    /**
     * То же и {@code disabledBlocks} — сколько блоков отключено в схеме строк: в таблицу они не идут, окно
     * говорит об этом одной строкой.
     */
    public static ExportSource rows(String label, List<MaterialRow> rows, String fileName, @Nullable Kind kind,
                                    boolean narrowed, @Nullable SchematicEntry entry, long disabledBlocks)
    {
        List<MaterialRow> copy = List.copyOf(rows);

        return new ExportSource(label, () -> fileName, () -> CompletableFuture.completedFuture(copy), kind, narrowed,
                entry, disabledBlocks);
    }

    /** Подпись для списка источников и для шапки выгрузки ({@code ExportRequest.source}). */
    public String label()
    {
        return this.label;
    }

    /** Вид источника; {@code null} — неизвестен (строки переданы без него). */
    public @Nullable Kind kind()
    {
        return this.kind;
    }

    /** Файл схемы строк — для постера выгрузки «Картинка»; {@code null} — у списка HUD и размещения без файла. */
    public @Nullable SchematicEntry entry()
    {
        return this.entry;
    }

    /** Блоков отключено в схеме строк (в таблицу они не идут); 0 — нет или неизвестно. */
    public long disabledBlocks()
    {
        return this.disabledBlocks;
    }

    /** Строки сужены поиском или фильтром таблицы «Материалов». */
    public boolean narrowed()
    {
        return this.narrowed;
    }

    /** Имя файла выгрузки (ещё не очищенное): схема источника. */
    public String fileName()
    {
        if (this.fileName == null)
        {
            try
            {
                this.fileName = this.namer.get();
            }
            catch (RuntimeException e)
            {
                // Litematica не отдала размещения — назовём по подписи источника
                this.fileName = this.label;
            }
        }

        return this.fileName;
    }

    /** Начать счёт, если ещё не начат. */
    void load()
    {
        if (this.rows != null)
        {
            return;
        }

        // Имя — вместе со строками: у списка HUD это схемы размещений на момент счёта
        this.fileName();

        try
        {
            this.rows = this.loader.get();
            this.watchDisabled();
        }
        catch (RuntimeException e)
        {
            // Заглушка или сбой модуля-соседа: окно покажет текст, а не упадёт
            this.rows = CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Отключённые блоки считаются на клиентском потоке, где завершается и будущее строк: в тот момент
     * строки ещё не «готовы» для окна, а число уже лежит в поле.
     */
    private void watchDisabled()
    {
        Supplier<List<LitematicaSchematic>> mode = this.countDisabled;
        CompletableFuture<List<MaterialRow>> future = this.rows;

        if (mode == null || future == null)
        {
            return;
        }

        this.rows = future.thenApply(list ->
        {
            try
            {
                this.disabledBlocks = disabledIn(mode.get());
            }
            catch (RuntimeException e)
            {
                // Счёт отключённых — только пояснение: без него окно просто молчит
                this.disabledBlocks = 0L;
            }

            return list;
        });
    }

    private static long disabledIn(List<LitematicaSchematic> schematics)
    {
        long sum = 0L;

        for (LitematicaSchematic schematic : schematics)
        {
            BlockReplacements.ensure(schematic);

            for (BlockReplacements.Disabled disabled : BlockReplacements.disabled(schematic))
            {
                sum += disabled.count();
            }
        }

        return sum;
    }

    /** Строки, если уже посчитаны; {@code null} — считаются или не удались. */
    @Nullable List<MaterialRow> ready()
    {
        CompletableFuture<List<MaterialRow>> future = this.rows;

        return future != null && future.isDone() && ! future.isCompletedExceptionally() ? future.getNow(null) : null;
    }

    /** Текст ошибки счёта для игрока; {@code null} — ошибки нет (или ещё считается). */
    @Nullable String error()
    {
        CompletableFuture<List<MaterialRow>> future = this.rows;

        if (future == null || ! future.isCompletedExceptionally())
        {
            return null;
        }

        try
        {
            future.join();
            return null;
        }
        catch (RuntimeException e)
        {
            // Текст «Зайдите в мир» и «Не удалось посчитать…» M25 уже переводит сам
            return SchematicMaterials.describe(e);
        }
    }
}
