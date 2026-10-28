package com.aell.layerlist.schematics.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Корзина «Библиотеки» (M22): удалённые схемы и пустые папки не стираются, а переезжают в
 * {@code <папка игры>/material-hud-trash/<гггг-ММ-дд_ЧЧ-мм-сс>/<имя>} — по папке на удаление
 * (совпала секунда — {@code …-2}, {@code …-3}). Откуда файл и когда удалён — в {@code index.json}
 * там же; через {@link #KEEP_DAYS} дней запись удаляется насовсем ({@link #purgeExpired()} зовут при
 * открытии экрана «Схемы» и раз при старте клиента). Своя папка, а не корзина системы: игра идёт
 * без AWT ({@code java.awt.Desktop} нет), а Litematica папку вне своей не показывает.
 *
 * <p>Срок считается по записанной дате удаления (стенные часы, переживают перезапуск), не по времени
 * изменения файла: перенос его не меняет, и файл, сохранённый год назад, удалился бы сразу. Индекс
 * потерян — дата берётся из имени папки, исходное место — корень папки схем.
 *
 * <p>Все методы синхронизированы: чистка при старте идёт в фоновом пуле, остальное — с клиентского потока.
 */
@Environment(EnvType.CLIENT)
public final class SchematicTrash
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    /** Папка корзины в папке игры. */
    public static final String FOLDER = "material-hud-trash";
    /** Сколько дней удалённое хранится. */
    public static final int KEEP_DAYS = 30;
    static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final String INDEX = "index.json";
    private static final int INDEX_VERSION = 1;
    private static final int MAX_SUFFIX = 999;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Удалённое: {@code id} — имя папки внутри корзины, {@code name} — имя файла (папки) в ней,
     * {@code original} — откуда удалено ({@code null} — неизвестно, вернётся в корень папки схем),
     * {@code deletedAt} — эпоха, мс.
     */
    public record Item(String id, String name, @Nullable Path original, long deletedAt, long size, boolean directory)
    {
        public long expiresAt()
        {
            return this.deletedAt + KEEP_DAYS * DAY_MS;
        }

        /** Полных и неполных дней до удаления: только что удалённое — {@link #KEEP_DAYS}, просроченное — 0. */
        public int daysLeft(long now)
        {
            long left = this.expiresAt() - now;
            return left <= 0L ? 0 : (int) Math.min(KEEP_DAYS, (left + DAY_MS - 1L) / DAY_MS);
        }
    }

    private static final SchematicTrash INSTANCE = new SchematicTrash();

    private final List<Item> items = new ArrayList<>();
    private @Nullable Path loadedFrom;
    private @Nullable Path rootOverride;

    private SchematicTrash() {}

    public static SchematicTrash get()
    {
        return INSTANCE;
    }

    /** Дата удаления и сроки — по стенным часам: монотонные {@code Util.getMillis} не переживают перезапуск. */
    public static long now()
    {
        return System.currentTimeMillis();
    }

    public synchronized Path root()
    {
        Path override = this.rootOverride;

        return override != null ? override
                : FabricLoader.getInstance().getGameDir().resolve(FOLDER).toAbsolutePath().normalize();
    }

    /** Удалённое, свежее сверху. Записи, чьих папок больше нет (стёрли руками), выпадают. */
    public synchronized List<Item> items()
    {
        this.ensureLoaded();

        if (this.items.removeIf(item -> ! Files.exists(this.stored(item))))
        {
            this.writeIndexQuietly();
        }

        return List.copyOf(this.items);
    }

    public synchronized int count()
    {
        return this.items().size();
    }

    /** Где лежит удалённое сейчас. */
    public synchronized Path stored(Item item)
    {
        return this.root().resolve(item.id()).resolve(item.name());
    }

    /**
     * Переместить файл схемы или пустую папку в корзину. Непустая папка — {@link DirectoryNotEmptyException}:
     * её содержимое игрок не видел целиком (там бывают и не схемы).
     */
    public synchronized Item trash(Path path) throws IOException
    {
        this.ensureLoaded();

        Path source = path.toAbsolutePath().normalize();
        Path fileName = source.getFileName();

        if (fileName == null || ! Files.exists(source))
        {
            throw new NoSuchFileException(source.toString());
        }

        boolean directory = Files.isDirectory(source);

        if (directory && ! isEmptyDirectory(source))
        {
            throw new DirectoryNotEmptyException(source.toString());
        }

        long size = directory ? 0L : Files.size(source);
        long deletedAt = now();
        Path root = this.root();
        Files.createDirectories(root);

        String id = this.freeId(root, deletedAt);
        Path holder = root.resolve(id);
        Files.createDirectory(holder);

        try
        {
            // Без ATOMIC_MOVE: папка игры и папка схем могут быть на разных дисках — тогда копия и удаление
            Files.move(source, holder.resolve(fileName.toString()));
        }
        catch (IOException | RuntimeException e)
        {
            deleteQuietly(holder);
            throw e;
        }

        Item item = new Item(id, fileName.toString(), source, deletedAt, size, directory);
        this.items.addFirst(item);
        this.writeIndexQuietly();

        return item;
    }

    /**
     * Вернуть на прежнее место. Имя занято — «Имя (2).litematic», «Имя (3)…». Папки, где файл лежал,
     * больше нет — создаётся заново. Возвращает, куда легло.
     */
    public synchronized Path restore(Item item, Path fallbackFolder) throws IOException
    {
        this.ensureLoaded();

        Path stored = this.stored(item);

        if (! Files.exists(stored))
        {
            this.forgetItem(item);
            throw new NoSuchFileException(stored.toString());
        }

        Path wanted = item.original() != null ? item.original() : fallbackFolder.resolve(item.name());
        Path target = freeName(wanted, item.directory());
        Path parent = target.getParent();

        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        Files.move(stored, target);
        deleteQuietly(this.root().resolve(item.id()));
        this.forgetItem(item);

        return target;
    }

    /** Удалить насовсем одну запись. */
    public synchronized void deleteForever(Item item) throws IOException
    {
        this.ensureLoaded();
        deleteRecursively(this.root().resolve(item.id()));
        this.forgetItem(item);
    }

    /** Очистить корзину; возвращает удалённые записи (для чистки миниатюр). */
    public synchronized List<Item> empty() throws IOException
    {
        List<Item> all = this.items();
        IOException first = null;
        List<Item> done = new ArrayList<>();

        for (Item item : all)
        {
            try
            {
                deleteRecursively(this.root().resolve(item.id()));
                done.add(item);
            }
            catch (IOException e)
            {
                first = first == null ? e : first;
            }
        }

        this.items.removeAll(done);
        this.writeIndexQuietly();

        if (first != null)
        {
            throw first;
        }

        return done;
    }

    /** Удалить записи старше {@link #KEEP_DAYS} дней; ошибки — в лог. Возвращает удалённые. */
    public synchronized List<Item> purgeExpired()
    {
        long now = now();
        List<Item> purged = new ArrayList<>();

        for (Item item : this.items())
        {
            if (now < item.expiresAt())
            {
                continue;
            }

            try
            {
                deleteRecursively(this.root().resolve(item.id()));
                purged.add(item);
            }
            catch (IOException | RuntimeException e)
            {
                LOGGER.warn("Could not purge {} from the trash", item.id(), e);
            }
        }

        if (! purged.isEmpty())
        {
            this.items.removeAll(purged);
            this.writeIndexQuietly();
            LOGGER.info("Trash: deleted {} item(s) older than {} days", purged.size(), KEEP_DAYS);
        }

        return purged;
    }

    private void forgetItem(Item item)
    {
        this.items.removeIf(known -> known.id().equals(item.id()));
        this.writeIndexQuietly();
    }

    // ===== Имена =====

    /** Папка на удаление — по секунде удаления; занята (две схемы за секунду) — с номером. */
    private String freeId(Path root, long deletedAt)
    {
        String base = STAMP.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(deletedAt), ZoneId.systemDefault()));
        String id = base;

        for (int n = 2; Files.exists(root.resolve(id)) || this.known(id); ++n)
        {
            id = base + "-" + n;
        }

        return id;
    }

    private boolean known(String id)
    {
        for (Item item : this.items)
        {
            if (item.id().equals(id))
            {
                return true;
            }
        }

        return false;
    }

    /** «Имя.ext» свободно — оно; иначе «Имя (2).ext», «Имя (3).ext»… (у папки — без расширения). */
    static Path freeName(Path wanted, boolean directory) throws IOException
    {
        if (! Files.exists(wanted))
        {
            return wanted;
        }

        Path name = wanted.getFileName();
        String full = name == null ? "" : name.toString();
        int dot = directory ? -1 : full.lastIndexOf('.');
        String base = dot > 0 ? full.substring(0, dot) : full;
        String ext = dot > 0 ? full.substring(dot) : "";

        for (int n = 2; n <= MAX_SUFFIX; ++n)
        {
            Path candidate = wanted.resolveSibling(base + " (" + n + ")" + ext);

            if (! Files.exists(candidate))
            {
                return candidate;
            }
        }

        throw new FileAlreadyExistsException(wanted.toString());
    }

    // ===== Индекс =====

    private void ensureLoaded()
    {
        Path root = this.root();

        if (root.equals(this.loadedFrom))
        {
            return;
        }

        this.loadedFrom = root;
        this.items.clear();
        this.readIndex(root);

        // Папки без записи (индекс потерян или стёрт руками) — подобрать: дата из имени папки
        boolean adopted = false;
        Set<String> ids = new HashSet<>();

        for (Item item : this.items)
        {
            ids.add(item.id());
        }

        if (Files.isDirectory(root))
        {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, Files::isDirectory))
            {
                for (Path dir : stream)
                {
                    String id = dir.getFileName().toString();

                    if (! ids.contains(id))
                    {
                        Item orphan = adopt(dir, id);

                        if (orphan != null)
                        {
                            this.items.add(orphan);
                            adopted = true;
                        }
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                LOGGER.warn("Could not scan the trash {}", root, e);
            }
        }

        this.items.sort(Comparator.comparingLong(Item::deletedAt).reversed());

        if (adopted)
        {
            this.writeIndexQuietly();
        }
    }

    private void readIndex(Path root)
    {
        Path index = root.resolve(INDEX);

        if (! Files.isRegularFile(index))
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8))
        {
            JsonElement parsed = JsonParser.parseReader(reader);
            JsonArray array = parsed.isJsonObject() && parsed.getAsJsonObject().has("items")
                    ? parsed.getAsJsonObject().getAsJsonArray("items") : new JsonArray();

            for (JsonElement element : array)
            {
                Item item = fromJson(element);

                if (item != null && Files.exists(root.resolve(item.id()).resolve(item.name())))
                {
                    this.items.add(item);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Битый индекс — не повод терять удалённое: папки подберутся по именам
            LOGGER.warn("Trash index {} is unreadable", index, e);
        }
    }

    private static @Nullable Item fromJson(JsonElement element)
    {
        if (! element.isJsonObject())
        {
            return null;
        }

        JsonObject o = element.getAsJsonObject();

        if (! o.has("id") || ! o.has("name") || ! o.has("deleted"))
        {
            return null;
        }

        String id = o.get("id").getAsString();
        String name = o.get("name").getAsString();

        // Чужая правка индекса не должна вывести за пределы корзины
        if (id.isEmpty() || name.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")
                || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals("."))
        {
            return null;
        }

        Path original = null;

        if (o.has("original") && ! o.get("original").isJsonNull())
        {
            try
            {
                original = Path.of(o.get("original").getAsString()).toAbsolutePath().normalize();
            }
            catch (InvalidPathException e)
            {
                original = null;
            }
        }

        long size = o.has("size") ? o.get("size").getAsLong() : 0L;
        boolean directory = o.has("directory") && o.get("directory").getAsBoolean();

        return new Item(id, name, original, o.get("deleted").getAsLong(), size, directory);
    }

    private static @Nullable Item adopt(Path dir, String id)
    {
        Path only = null;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path child : stream)
            {
                if (only != null)
                {
                    return null;
                }

                only = child;
            }
        }
        catch (IOException e)
        {
            return null;
        }

        if (only == null)
        {
            return null;
        }

        long deletedAt = parseStamp(id);
        boolean directory = Files.isDirectory(only);
        long size = 0L;

        try
        {
            size = directory ? 0L : Files.size(only);
        }
        catch (IOException ignored)
        {
        }

        return new Item(id, only.getFileName().toString(), null, deletedAt, size, directory);
    }

    /** Дата из имени папки ({@code 2026-10-07_12-30-15}, {@code …-2}); не разобралась — «сейчас»: срок с находки. */
    static long parseStamp(String id)
    {
        String stamp = id.length() >= 19 ? id.substring(0, 19) : id;

        try
        {
            return LocalDateTime.parse(stamp, STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        catch (DateTimeParseException e)
        {
            return now();
        }
    }

    private void writeIndexQuietly()
    {
        Path root = this.root();

        try
        {
            if (this.items.isEmpty() && ! Files.isDirectory(root))
            {
                return;
            }

            Files.createDirectories(root);

            JsonObject top = new JsonObject();
            top.addProperty("version", INDEX_VERSION);
            top.addProperty("keepDays", KEEP_DAYS);
            JsonArray array = new JsonArray();

            for (Item item : this.items)
            {
                JsonObject o = new JsonObject();
                o.addProperty("id", item.id());
                o.addProperty("name", item.name());
                o.addProperty("original", item.original() == null ? null : item.original().toString());
                o.addProperty("deleted", item.deletedAt());
                o.addProperty("size", item.size());
                o.addProperty("directory", item.directory());
                array.add(o);
            }

            top.add("items", array);

            Path index = root.resolve(INDEX);
            Path tmp = root.resolve(INDEX + ".tmp");

            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))
            {
                GSON.toJson(top, writer);
            }

            try
            {
                Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            // Индекс — подсказка, а не источник истины: без него папки подберутся по именам
            LOGGER.warn("Could not write the trash index in {}", root, e);
        }
    }

    // ===== Диск =====

    static boolean isEmptyDirectory(Path dir) throws IOException
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            return ! stream.iterator().hasNext();
        }
    }

    private static void deleteRecursively(Path path) throws IOException
    {
        if (! Files.exists(path))
        {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException error) throws IOException
            {
                if (error != null)
                {
                    throw error;
                }

                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            deleteRecursively(path);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.debug("Could not clean {}", path, e);
        }
    }

    /** Только для харнесса (режим k): своя папка корзины и «состаренная» запись. В игре не зовётся. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Корзина в другой папке ({@code null} — обычная); список перечитывается. */
        public static void root(@Nullable Path root)
        {
            synchronized (INSTANCE)
            {
                INSTANCE.rootOverride = root == null ? null : root.toAbsolutePath().normalize();
                INSTANCE.loadedFrom = null;
            }
        }

        /** Сдвинуть дату удаления записи на {@code days} дней назад (индекс переписывается). */
        public static boolean backdate(String id, double days)
        {
            synchronized (INSTANCE)
            {
                INSTANCE.ensureLoaded();

                for (int i = 0; i < INSTANCE.items.size(); ++i)
                {
                    Item item = INSTANCE.items.get(i);

                    if (item.id().equals(id))
                    {
                        long shift = (long) (days * DAY_MS);
                        INSTANCE.items.set(i, new Item(item.id(), item.name(), item.original(), item.deletedAt() - shift,
                                item.size(), item.directory()));
                        INSTANCE.writeIndexQuietly();
                        return true;
                    }
                }

                return false;
            }
        }

        /** Перечитать индекс с диска (проверка, что дата удаления пережила перезапуск). */
        public static void reload()
        {
            synchronized (INSTANCE)
            {
                INSTANCE.loadedFrom = null;
            }
        }
    }
}
