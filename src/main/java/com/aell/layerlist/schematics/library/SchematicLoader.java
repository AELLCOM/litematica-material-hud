package com.aell.layerlist.schematics.library;

import com.aell.layerlist.schematics.materials.BlockReplacements;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.util.WorldUtils;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Загрузка схемы в Litematica — её же методами и на клиентском потоке, как это делает её браузер
 * схем. Схема, которую Litematica уже знает (загружена или стоит в размещении), берётся как есть.
 *
 * <p>В {@code SchematicHolder} («Загруженные схемы» Litematica) загрузчик сам ничего не добавляет:
 * просмотр материалов не должен засорять её список и держать большие схемы в памяти. Если нужно
 * (размещение), это делает вызывающий.
 */
@Environment(EnvType.CLIENT)
final class SchematicLoader
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final long DEFER_MAX_MS = 500L;
    private static boolean tickRegistered;

    private final List<Deferred> deferred = new ArrayList<>();

    /** Свои загрузки — на слабых ссылках: держит схему только тот, кто ею пользуется. */
    private final Map<SchematicEntry, WeakReference<LitematicaSchematic>> loaded = new HashMap<>();

    private @Nullable Constructor<LitematicaSchematic> fileConstructor;
    private boolean fileConstructorMissing;

    /**
     * Будущее завершается на клиентском потоке; провал — {@link SchematicReadException} внутри.
     *
     * <p>Чтение Litematica идёт на клиентском потоке и у больших схем держит кадр секундами (замер
     * 07.10: «База VS» 11 МБ — 3,8 с, 23 тыс. блочных сущностей — 5,3 с). Поэтому новая загрузка
     * не начинается в том же кадре, где её попросили: сначала экран успевает нарисовать «Загрузка…»
     * ({@link #frameDrawn}), потом тик клиента читает файл. Уже знакомая Litematica схема — сразу.
     */
    CompletableFuture<LoadedSchematic> load(SchematicEntry entry)
    {
        Minecraft mc = Minecraft.getInstance();

        if (! mc.isSameThread())
        {
            return mc.submit(() -> this.loadOrThrow(entry));
        }

        if (mc.level != null && this.known(entry) == null)
        {
            Deferred later = new Deferred(entry, new CompletableFuture<>(), Util.getMillis());
            this.deferred.add(later);
            registerTick();
            return later.future;
        }

        try
        {
            return CompletableFuture.completedFuture(this.loadNow(entry));
        }
        catch (SchematicReadException error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }

    /** Экран нарисовал кадр: отложенные загрузки можно начинать (игрок уже видит «Загрузка…»). */
    void frameDrawn()
    {
        for (Deferred later : this.deferred)
        {
            later.framed = true;
        }
    }

    private void runDeferred()
    {
        if (this.deferred.isEmpty())
        {
            return;
        }

        long now = Util.getMillis();

        // Без экрана, который рисует кадры (харнесс, закрытый экран), — не дольше полсекунды
        for (Deferred later : List.copyOf(this.deferred))
        {
            if (! later.framed && now - later.at < DEFER_MAX_MS)
            {
                continue;
            }

            this.deferred.remove(later);

            try
            {
                later.future.complete(this.loadNow(later.entry));
            }
            catch (SchematicReadException | RuntimeException | LinkageError error)
            {
                later.future.completeExceptionally(error);
            }
        }
    }

    private static void registerTick()
    {
        if (! tickRegistered)
        {
            tickRegistered = true;
            ClientTickEvents.END_CLIENT_TICK.register(client -> SchematicLibrary.get().loader().runDeferred());
        }
    }

    /** Загрузка, отложенная до следующего кадра (см. {@link #load}). */
    private static final class Deferred
    {
        private final SchematicEntry entry;
        private final CompletableFuture<LoadedSchematic> future;
        private final long at;
        private boolean framed;

        private Deferred(SchematicEntry entry, CompletableFuture<LoadedSchematic> future, long at)
        {
            this.entry = entry;
            this.future = future;
            this.at = at;
        }
    }

    private LoadedSchematic loadOrThrow(SchematicEntry entry)
    {
        try
        {
            return this.loadNow(entry);
        }
        catch (SchematicReadException error)
        {
            throw new CompletionException(error);
        }
    }

    private LoadedSchematic loadNow(SchematicEntry entry) throws SchematicReadException
    {
        SchematicFormat format = entry.format();

        if (entry.directory() || format == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.not_schematic");
        }

        // Без мира у Litematica нет реестров мира и временного мира для конвертации
        if (Minecraft.getInstance().level == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.no_world");
        }

        LitematicaSchematic known = this.known(entry);

        if (known != null)
        {
            return new LoadedSchematic(entry, known);
        }

        LitematicaSchematic schematic = this.read(entry, format);
        // Замены блоков файла (0.0.27): у .litematic/.schem/.nbt их уже наложил миксин чтения, у .schematic
        // (конвертация Schematica идёт мимо чтения файла Litematica) — здесь
        BlockReplacements.ensure(schematic);
        this.forgetCollected();
        this.loaded.put(entry, new WeakReference<>(schematic));

        return new LoadedSchematic(entry, schematic);
    }

    /** Уже загруженная схема этого файла: своя, из «Загруженных схем» или из размещений. */
    private @Nullable LitematicaSchematic known(SchematicEntry entry)
    {
        WeakReference<LitematicaSchematic> ref = this.loaded.get(entry);
        LitematicaSchematic own = ref == null ? null : ref.get();

        if (own != null)
        {
            return own;
        }

        for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics())
        {
            if (sameFile(schematic.getFile(), entry.path()))
            {
                return schematic;
            }
        }

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            LitematicaSchematic schematic = placement.getSchematic();

            if (schematic != null && sameFile(schematic.getFile(), entry.path()))
            {
                return schematic;
            }
        }

        return null;
    }

    private LitematicaSchematic read(SchematicEntry entry, SchematicFormat format) throws SchematicReadException
    {
        Path dir = entry.path().getParent();
        String fileName = entry.fileName();

        if (dir == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.not_schematic");
        }

        LitematicaSchematic schematic;
        StringBuilder feedback = new StringBuilder();

        try
        {
            if (format == SchematicFormat.SCHEMATICA)
            {
                // Конвертация Schematica идёт по пути как есть (без «чистки» имени); entities не
                // пропускаем — с пропуском Litematica пишет ложную ошибку
                schematic = WorldUtils.convertSchematicaSchematicToLitematicaSchematic(dir, fileName, false,
                        message ->
                        {
                            feedback.setLength(0);
                            feedback.append(message);
                        });
            }
            else
            {
                FileType type = fileType(format);

                // Litematica «чистит» имя файла (COM/PRN, управляющие и комбинирующие знаки, регистр
                // расширения) и тогда открывает другой путь. Совпадает — зовём её обычный метод
                if (sameFile(LitematicaSchematic.fileFromDirAndName(dir, fileName, type), entry.path()))
                {
                    schematic = switch (format)
                    {
                        case LITEMATIC -> LitematicaSchematic.createFromFile(dir, fileName);
                        case SPONGE -> WorldUtils.convertSpongeSchematicToLitematicaSchematic(dir, fileName);
                        default -> WorldUtils.convertStructureToLitematicaSchematic(dir, fileName);
                    };
                }
                else
                {
                    schematic = this.readExactFile(entry.path(), type);
                }
            }
        }
        catch (RuntimeException error)
        {
            // Конвертация Schematica при неудаче сначала пишет причину, а потом падает на null —
            // причина тогда полезнее текста исключения
            LOGGER.warn("Litematica failed to load {}", entry.path(), error);
            throw new SchematicReadException(error, "layerlist.schematics.error.load_failed_reason",
                    feedback.isEmpty() ? SchematicReadException.reason(error) : I18n.get(feedback.toString()));
        }

        if (schematic == null)
        {
            // Litematica присылает в обратную связь ключ своего перевода; без неё причина — в её журнале
            throw feedback.isEmpty()
                    ? new SchematicReadException("layerlist.schematics.error.load_failed")
                    : new SchematicReadException("layerlist.schematics.error.load_failed_reason", I18n.get(feedback.toString()));
        }

        return schematic;
    }

    /**
     * Файл с «неудобным» именем: приватный конструктор (путь, тип) и публичный {@code readFromFile()}
     * читают ровно этот путь. Публичный конструктор (путь, данные, тип) не годится — на старых
     * версиях данных он падает.
     */
    private LitematicaSchematic readExactFile(Path file, FileType type) throws SchematicReadException
    {
        Constructor<LitematicaSchematic> constructor = this.fileConstructor();

        if (constructor == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.bad_name");
        }

        LitematicaSchematic schematic;

        try
        {
            schematic = constructor.newInstance(file, type);
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException error)
        {
            Throwable cause = error instanceof InvocationTargetException target && target.getCause() != null
                    ? target.getCause() : error;
            throw new SchematicReadException(cause, "layerlist.schematics.error.load_failed_reason",
                    SchematicReadException.reason(cause));
        }

        if (! schematic.readFromFile())
        {
            throw new SchematicReadException("layerlist.schematics.error.load_failed");
        }

        return schematic;
    }

    private @Nullable Constructor<LitematicaSchematic> fileConstructor()
    {
        if (this.fileConstructor == null && ! this.fileConstructorMissing)
        {
            try
            {
                Constructor<LitematicaSchematic> constructor =
                        LitematicaSchematic.class.getDeclaredConstructor(Path.class, FileType.class);
                constructor.setAccessible(true);
                this.fileConstructor = constructor;
            }
            catch (NoSuchMethodException | RuntimeException error)
            {
                // Litematica сменила конструктор — такие имена честно не открываем, остальное работает
                this.fileConstructorMissing = true;
                LOGGER.warn("LitematicaSchematic(Path, FileType) is not accessible; files with sanitized names will not load", error);
            }
        }

        return this.fileConstructor;
    }

    private static FileType fileType(SchematicFormat format)
    {
        return switch (format)
        {
            case LITEMATIC -> FileType.LITEMATICA_SCHEMATIC;
            case SPONGE -> FileType.SPONGE_SCHEMATIC;
            case VANILLA -> FileType.VANILLA_STRUCTURE;
            case SCHEMATICA -> FileType.SCHEMATICA_SCHEMATIC;
        };
    }

    static boolean sameFile(@Nullable Path a, @Nullable Path b)
    {
        return a != null && b != null && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private void forgetCollected()
    {
        Iterator<WeakReference<LitematicaSchematic>> it = this.loaded.values().iterator();

        while (it.hasNext())
        {
            if (it.next().get() == null)
            {
                it.remove();
            }
        }
    }
}
