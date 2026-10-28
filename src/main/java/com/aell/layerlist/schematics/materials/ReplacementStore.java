package com.aell.layerlist.schematics.materials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Замены блоков на диске (0.0.27, M25): {@code config/layerlist_replacements.json}. Ключ — абсолютный
 * путь файла схемы, рядом — отпечаток файла на момент замены (размер, время изменения, CRC32
 * содержимого) и сами замены «исходный блок → новый блок» по id, а с 1.0.12 — отключения на слоях
 * «исходный блок → номера слоёв» ({@code layers}, слой 0 — нижний), а с 1.0.18 — отключённые сущности: id предметов
 * строк, чьи сущности (рамки, стойки…) или содержимое рамок убраны из схемы ({@code entities}). Сам файл схемы не
 * меняется никогда.
 *
 * <p>Отпечаток нужен, чтобы чужой файл с тем же именем не получил чужие замены: другой размер —
 * файл другой; тот же размер, но другое время — сверяется CRC (файл скопировали или «потрогали», а
 * содержимое то же — замены остаются и время обновляется). Отдельный файл, а не опции malilib: malilib
 * перечитывает {@code layerlist.json} при входе в мир.
 *
 * <p>Потоки: всё под замком — замены применяются и при чтении схемы самой Litematica. Запись — сразу и
 * целиком (файл маленький), через временный файл; битый файл не мешает: читается то, что читается.
 */
@Environment(EnvType.CLIENT)
final class ReplacementStore
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "layerlist_replacements.json";
    private static final int VERSION = 1;
    /** CRC ещё не посчитан (считается в фоне после первой замены). */
    static final long NO_CRC = -1L;

    private static final ReplacementStore INSTANCE = new ReplacementStore();

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    private ReplacementStore() {}

    static ReplacementStore get()
    {
        return INSTANCE;
    }

    /**
     * Запись одного файла схемы.
     *
     * @param size     размер файла, Б
     * @param modified время изменения, мс от эпохи
     * @param crc      CRC32 содержимого или {@link #NO_CRC}
     * @param blocks   id исходного блока → id нового, в порядке замен
     * @param layers   id исходного блока → слои схемы (0 — нижний), где он отключён
     * @param entities id предметов строк, чьи сущности или содержимое рамок отключены (1.0.18)
     */
    record Entry(long size, long modified, long crc, Map<String, String> blocks, Map<String, List<Integer>> layers,
                 List<String> entities)
    {
        Entry
        {
            blocks = Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
            Map<String, List<Integer>> copy = new LinkedHashMap<>();
            layers.forEach((id, list) -> copy.put(id, List.copyOf(list)));
            layers = Collections.unmodifiableMap(copy);
            entities = List.copyOf(entities);
        }

        Entry(long size, long modified, long crc, Map<String, String> blocks, Map<String, List<Integer>> layers)
        {
            this(size, modified, crc, blocks, layers, List.of());
        }

        Entry(long size, long modified, long crc, Map<String, String> blocks)
        {
            this(size, modified, crc, blocks, Map.of());
        }

        Entry withBlocks(Map<String, String> next)
        {
            return new Entry(this.size, this.modified, this.crc, next, this.layers, this.entities);
        }

        Entry withChanges(Map<String, String> nextBlocks, Map<String, List<Integer>> nextLayers, List<String> nextEntities)
        {
            return new Entry(this.size, this.modified, this.crc, nextBlocks, nextLayers, nextEntities);
        }

        Entry withFile(Fingerprint print, long crc)
        {
            return new Entry(print.size(), print.modified(), crc, this.blocks, this.layers, this.entities);
        }

        boolean empty()
        {
            return this.blocks.isEmpty() && this.layers.isEmpty() && this.entities.isEmpty();
        }
    }

    /** Размер и время изменения файла — то, что дёшево узнать без чтения. */
    record Fingerprint(long size, long modified) {}

    /** Чем файл стал по сравнению с отпечатком записи. */
    enum Check
    {
        /** Тот же размер и время. */
        SAME,
        /** Время другое, содержимое то же (CRC совпал): запись надо обновить. */
        TOUCHED,
        /** Другой файл: замены сбросить. */
        CHANGED,
        /** Файла нет (ушёл в корзину, переименован): не применять, но и не забывать. */
        MISSING
    }

    // ===== Записи =====

    synchronized @Nullable Entry entry(Path file)
    {
        this.ensureLoaded();
        return this.entries.get(key(file));
    }

    synchronized void put(Path file, Entry entry)
    {
        this.ensureLoaded();
        this.entries.put(key(file), entry);
        this.save();
    }

    synchronized void remove(Path file)
    {
        this.ensureLoaded();

        if (this.entries.remove(key(file)) != null)
        {
            this.save();
        }
    }

    /**
     * Файл схемы переименовали: запись — под новым именем. Отпечаток прежний: переименование не меняет ни
     * размера, ни времени изменения, ни содержимого.
     */
    synchronized void move(Path from, Path to)
    {
        this.ensureLoaded();
        Entry entry = this.entries.remove(key(from));

        if (entry != null)
        {
            this.entries.put(key(to), entry);
            this.save();
        }
    }

    /** CRC посчитан в фоне: дописать, если файл с тех пор не менялся и запись ещё та же. */
    synchronized void setCrc(Path file, Fingerprint print, long crc)
    {
        this.ensureLoaded();
        Entry entry = this.entries.get(key(file));

        if (entry != null && entry.size() == print.size() && entry.modified() == print.modified() && entry.crc() != crc)
        {
            this.entries.put(key(file), new Entry(entry.size(), entry.modified(), crc, entry.blocks(), entry.layers(),
                    entry.entities()));
            this.save();
        }
    }

    /** Харнесс: забыть прочитанное, чтобы следующее обращение читало файл заново (как после перезапуска игры). */
    synchronized void forgetMemory()
    {
        this.entries.clear();
        this.loaded = false;
    }

    static Path path()
    {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    static String key(Path file)
    {
        return file.toAbsolutePath().normalize().toString();
    }

    // ===== Отпечаток =====

    static @Nullable Fingerprint fingerprint(Path file)
    {
        try
        {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return attrs.isRegularFile() ? new Fingerprint(attrs.size(), attrs.lastModifiedTime().toMillis()) : null;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    /** CRC32 всего файла; {@link #NO_CRC} — не прочитался. Читает файл целиком — не на клиентском потоке без нужды. */
    static long crc(Path file)
    {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[1 << 16];

        try (InputStream in = Files.newInputStream(file))
        {
            int read;

            while ((read = in.read(buffer)) > 0)
            {
                crc.update(buffer, 0, read);
            }
        }
        catch (IOException | RuntimeException e)
        {
            return NO_CRC;
        }

        return crc.getValue();
    }

    /** Тот ли это файл, что при замене. Тот же размер и другое время — сверка CRC (если он известен). */
    static Check check(Path file, Entry entry)
    {
        Fingerprint now = fingerprint(file);

        if (now == null)
        {
            return Check.MISSING;
        }

        if (now.size() != entry.size())
        {
            return Check.CHANGED;
        }

        if (now.modified() == entry.modified())
        {
            return Check.SAME;
        }

        return entry.crc() != NO_CRC && crc(file) == entry.crc() ? Check.TOUCHED : Check.CHANGED;
    }

    // ===== Файл =====

    private void ensureLoaded()
    {
        if (this.loaded)
        {
            return;
        }

        this.loaded = true;
        this.entries.clear();
        Path path = path();

        if (! Files.isRegularFile(path))
        {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            JsonElement root = JsonParser.parseReader(reader);
            JsonObject files = root.isJsonObject() && root.getAsJsonObject().get("files") instanceof JsonObject object
                    ? object : new JsonObject();

            for (Map.Entry<String, JsonElement> file : files.entrySet())
            {
                Entry entry = read(file.getValue());

                if (entry != null && ! entry.empty())
                {
                    this.entries.put(file.getKey(), entry);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Could not read block replacements {}", path, e);
        }
    }

    private static @Nullable Entry read(JsonElement element)
    {
        if (! (element instanceof JsonObject object))
        {
            return null;
        }

        try
        {
            long size = object.get("size").getAsLong();
            long modified = object.get("modified").getAsLong();
            long crc = object.has("crc32") ? Long.parseLong(object.get("crc32").getAsString(), 16) : NO_CRC;
            Map<String, String> blocks = new LinkedHashMap<>();

            if (object.get("blocks") instanceof JsonObject list)
            {
                for (Map.Entry<String, JsonElement> pair : list.entrySet())
                {
                    if (pair.getValue().isJsonPrimitive())
                    {
                        blocks.put(pair.getKey(), pair.getValue().getAsString());
                    }
                }
            }

            Map<String, List<Integer>> layers = new LinkedHashMap<>();

            if (object.get("layers") instanceof JsonObject list)
            {
                for (Map.Entry<String, JsonElement> pair : list.entrySet())
                {
                    if (pair.getValue() instanceof com.google.gson.JsonArray array)
                    {
                        List<Integer> numbers = new java.util.ArrayList<>();
                        array.forEach(number -> numbers.add(number.getAsInt()));

                        if (! numbers.isEmpty())
                        {
                            layers.put(pair.getKey(), numbers);
                        }
                    }
                }
            }

            List<String> entities = new java.util.ArrayList<>();

            if (object.get("entities") instanceof com.google.gson.JsonArray array)
            {
                for (JsonElement id : array)
                {
                    if (id.isJsonPrimitive())
                    {
                        entities.add(id.getAsString());
                    }
                }
            }

            return new Entry(size, modified, crc, blocks, layers, entities);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private void save()
    {
        Path path = path();
        Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject files = new JsonObject();

        for (Map.Entry<String, Entry> file : this.entries.entrySet())
        {
            Entry entry = file.getValue();
            JsonObject object = new JsonObject();
            object.addProperty("size", entry.size());
            object.addProperty("modified", entry.modified());

            if (entry.crc() != NO_CRC)
            {
                object.addProperty("crc32", Long.toHexString(entry.crc()));
            }

            JsonObject blocks = new JsonObject();
            entry.blocks().forEach(blocks::addProperty);
            object.add("blocks", blocks);

            if (! entry.layers().isEmpty())
            {
                JsonObject layers = new JsonObject();
                entry.layers().forEach((id, list) ->
                {
                    com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                    list.forEach(array::add);
                    layers.add(id, array);
                });
                object.add("layers", layers);
            }

            if (! entry.entities().isEmpty())
            {
                com.google.gson.JsonArray entities = new com.google.gson.JsonArray();
                entry.entities().forEach(entities::add);
                object.add("entities", entities);
            }

            files.add(file.getKey(), object);
        }

        root.add("files", files);

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(root, writer);
            }

            try
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Could not save block replacements {}", path, e);

            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored)
            {
                // Временный файл останется — перезапишется следующим сохранением
            }
        }
    }
}
