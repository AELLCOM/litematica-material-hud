package com.aell.layerlist.schematics;

import com.aell.layerlist.schematics.export.ExportOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Состояние экрана «Схемы», которое не опция: последняя папка, сортировка, ширины панелей,
 * ростер ников, последние флажки выгрузки. Отдельный файл {@code config/layerlist_schematics.json},
 * потому что malilib перечитывает {@code layerlist.json} при входе в мир, и всё, что не опция,
 * там бы терялось.
 *
 * <p>Только клиентский поток. Битый файл не мешает: читается всё, что читается, остальное —
 * по умолчанию, а при следующем сохранении файл перезаписывается целым.
 */
@Environment(EnvType.CLIENT)
public final class SchematicsState
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final String FILE_NAME = "layerlist_schematics.json";
    private static final int VERSION = 1;

    /** Ростер — это люди одной команды; больше этого — почти наверняка мусор в файле. */
    public static final int ROSTER_MAX = 64;
    public static final int NICK_MAX = 32;

    private static final SchematicsState INSTANCE = new SchematicsState();

    /** Сортировка списка файлов. */
    public enum Sort
    {
        NAME,
        DATE,
        SIZE
    }

    private String lastFolder = "";
    private Sort sort = Sort.NAME;
    private boolean sortDescending;
    private boolean searchAllFolders;
    private final Map<String, Integer> splitters = new LinkedHashMap<>();
    private final List<String> roster = new ArrayList<>();
    private ExportOptions exportOptions = ExportOptions.DEFAULT;

    private boolean loaded;
    private boolean dirty;

    private SchematicsState() {}

    /** Состояние; при первом обращении читается с диска. */
    public static SchematicsState get()
    {
        if (! INSTANCE.loaded)
        {
            INSTANCE.load();
        }

        return INSTANCE;
    }

    /**
     * Последняя открытая папка — путь относительно корня каталога схем, через «/»; пусто — корень.
     * Относительный, чтобы смена папки схем в Litematica не уводила в чужое место.
     */
    public String lastFolder()
    {
        return this.lastFolder;
    }

    public void setLastFolder(String folder)
    {
        String value = folder == null ? "" : folder.replace('\\', '/');

        if (! value.equals(this.lastFolder))
        {
            this.lastFolder = value;
            this.dirty = true;
        }
    }

    public Sort sort()
    {
        return this.sort;
    }

    public boolean sortDescending()
    {
        return this.sortDescending;
    }

    public void setSort(Sort sort, boolean descending)
    {
        Objects.requireNonNull(sort, "sort");

        if (sort != this.sort || descending != this.sortDescending)
        {
            this.sort = sort;
            this.sortDescending = descending;
            this.dirty = true;
        }
    }

    /** Флажок «во всех папках» у поиска. */
    public boolean searchAllFolders()
    {
        return this.searchAllFolders;
    }

    public void setSearchAllFolders(boolean value)
    {
        if (value != this.searchAllFolders)
        {
            this.searchAllFolders = value;
            this.dirty = true;
        }
    }

    /**
     * Положение перетаскиваемой границы по имени (например {@code "files"}, {@code "info"}), в
     * пикселях GUI; {@code fallback} — если ещё не двигали.
     */
    public int splitter(String name, int fallback)
    {
        Integer value = this.splitters.get(name);

        return value == null ? fallback : value;
    }

    public void setSplitter(String name, int value)
    {
        Integer old = this.splitters.put(name, value);

        if (old == null || old != value)
        {
            this.dirty = true;
        }
    }

    /** Ники «Кто собирает», по порядку добавления; без пустых и повторов (регистр не важен). */
    public List<String> roster()
    {
        return Collections.unmodifiableList(this.roster);
    }

    public void setRoster(List<String> nicks)
    {
        List<String> clean = cleanRoster(nicks);

        if (! clean.equals(this.roster))
        {
            this.roster.clear();
            this.roster.addAll(clean);
            this.dirty = true;
        }
    }

    public ExportOptions exportOptions()
    {
        return this.exportOptions;
    }

    public void setExportOptions(ExportOptions options)
    {
        if (options != null && ! options.equals(this.exportOptions))
        {
            this.exportOptions = options;
            this.dirty = true;
        }
    }

    public void load()
    {
        this.loaded = true;
        this.reset();

        Path path = path();

        if (! Files.isRegularFile(path))
        {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            JsonElement root = JsonParser.parseReader(reader);

            if (root != null && root.isJsonObject())
            {
                this.read(root.getAsJsonObject());
            }
            else
            {
                LOGGER.warn("Schematics state {} is not a JSON object, using defaults", path);
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not read schematics state {}, using defaults", path, e);
        }

        this.dirty = false;
    }

    /** Записать, если что-то менялось. Через {@code .tmp}: оборванная запись не портит прошлый файл. */
    public void save()
    {
        if (! this.dirty)
        {
            return;
        }

        Path path = path();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(this.write(), writer);
            }

            try
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }

            this.dirty = false;
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not save schematics state {}", path, e);

            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (Exception ignored)
            {
                // Остаток .tmp перезапишется при следующем сохранении
            }
        }
    }

    private static Path path()
    {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private void reset()
    {
        this.lastFolder = "";
        this.sort = Sort.NAME;
        this.sortDescending = false;
        this.searchAllFolders = false;
        this.splitters.clear();
        this.roster.clear();
        this.exportOptions = ExportOptions.DEFAULT;
    }

    // Каждое поле читается само по себе: одно испорченное не должно стирать остальные
    private void read(JsonObject root)
    {
        this.lastFolder = string(root, "lastFolder", "").replace('\\', '/');
        this.sort = parseSort(string(root, "sort", Sort.NAME.name()));
        this.sortDescending = bool(root, "sortDescending", false);
        this.searchAllFolders = bool(root, "searchAllFolders", false);

        if (root.get("splitters") instanceof JsonObject object)
        {
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                if (entry.getValue() instanceof JsonPrimitive primitive && primitive.isNumber())
                {
                    this.splitters.put(entry.getKey(), primitive.getAsInt());
                }
            }
        }

        if (root.get("roster") instanceof JsonArray array)
        {
            List<String> nicks = new ArrayList<>();

            for (JsonElement element : array)
            {
                if (element instanceof JsonPrimitive primitive && primitive.isString())
                {
                    nicks.add(primitive.getAsString());
                }
            }

            this.roster.addAll(cleanRoster(nicks));
        }

        if (root.get("export") instanceof JsonObject export)
        {
            ExportOptions defaults = ExportOptions.DEFAULT;
            this.exportOptions = new ExportOptions(
                    bool(export, "onlyMissing", defaults.onlyMissing()),
                    bool(export, "icons", defaults.icons()),
                    bool(export, "rawMaterials", defaults.rawMaterials()),
                    bool(export, "chart", defaults.chart()));
        }
    }

    private JsonObject write()
    {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("lastFolder", this.lastFolder);
        root.addProperty("sort", this.sort.name());
        root.addProperty("sortDescending", this.sortDescending);
        root.addProperty("searchAllFolders", this.searchAllFolders);

        JsonObject splitters = new JsonObject();
        this.splitters.forEach(splitters::addProperty);
        root.add("splitters", splitters);

        JsonArray roster = new JsonArray();
        this.roster.forEach(roster::add);
        root.add("roster", roster);

        JsonObject export = new JsonObject();
        export.addProperty("onlyMissing", this.exportOptions.onlyMissing());
        export.addProperty("icons", this.exportOptions.icons());
        export.addProperty("rawMaterials", this.exportOptions.rawMaterials());
        export.addProperty("chart", this.exportOptions.chart());
        root.add("export", export);

        return root;
    }

    private static List<String> cleanRoster(List<String> nicks)
    {
        List<String> clean = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (String nick : nicks)
        {
            if (nick == null)
            {
                continue;
            }

            String value = nick.strip();

            if (value.length() > NICK_MAX)
            {
                value = value.substring(0, NICK_MAX);
            }

            String folded = value.toLowerCase(Locale.ROOT);

            if (value.isEmpty() || seen.contains(folded))
            {
                continue;
            }

            seen.add(folded);
            clean.add(value);

            if (clean.size() >= ROSTER_MAX)
            {
                break;
            }
        }

        return clean;
    }

    private static Sort parseSort(String value)
    {
        for (Sort sort : Sort.values())
        {
            if (sort.name().equalsIgnoreCase(value))
            {
                return sort;
            }
        }

        return Sort.NAME;
    }

    private static String string(JsonObject object, String key, String fallback)
    {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback)
    {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isBoolean()
                ? primitive.getAsBoolean()
                : fallback;
    }
}
