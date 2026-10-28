package com.aell.layerlist.schematics.library;

import com.aell.layerlist.schematics.PreviewLimits;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsState;
import fi.dy.masa.litematica.data.DataManager;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Каталог схем (M22): листинг папок, поиск, сведения, снимки блоков, загрузка в Litematica и
 * миниатюры. Методы зовутся с клиентского потока; тяжёлое уходит в фон и возвращается
 * {@link CompletableFuture}. Провал будущего — {@link CompletionException} с
 * {@link SchematicReadException} внутри (у неё готовый текст для игрока); отменённое —
 * {@link CancellationException}.
 *
 * <p>Потоки: сведения и PNG миниатюр — пул из двух; снимки для превью — один поток; миниатюры —
 * свой поток (чтобы список не стоял в очереди за большой схемой); поиск — свой. Большие файлы
 * разбираются строго по одному: разбор держит в памяти весь массив блоков файла.
 */
@Environment(EnvType.CLIENT)
public final class SchematicLibrary
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    /** Бюджет клеток снимка для превью (~64 млн); больше — грубая сетка 2×, 4×… */
    public static final long PREVIEW_CELLS = 1L << 26;

    /** Бюджет клеток для миниатюры 64×64: больше 128 по оси на ней всё равно не различить. */
    public static final long THUMB_CELLS = 1L << 21;

    /** С такого размера файл считаем большим: одновременно разбирается только один такой. */
    private static final long HEAVY_BYTES = 4L * 1024L * 1024L;

    private static final int INFO_CACHE = 4096;
    private static final int SEARCH_MAX_DEPTH = 32;
    private static final int SEARCH_MAX_RESULTS = 5000;
    private static final long COUNT_TTL_MS = 2000L;

    private static final Comparator<SchematicEntry> BY_NAME = (a, b) ->
    {
        int order = compareNatural(a.name(), b.name());

        return order != 0 ? order : a.fileName().compareTo(b.fileName());
    };

    private static final SchematicLibrary INSTANCE = new SchematicLibrary();

    private final ExecutorService meta = pool("meta", 2);
    private final ExecutorService decode = pool("decode", 1);
    private final ExecutorService thumbs = pool("thumbs", 1);
    private final ExecutorService searches = pool("search", 1);
    private final Semaphore heavy = new Semaphore(1);

    private final Object lock = new Object();
    private final Map<SchematicEntry, CompletableFuture<SchematicInfo>> infos =
            new LinkedHashMap<>(256, 0.75F, true)
            {
                @Override
                protected boolean removeEldestEntry(Map.Entry<SchematicEntry, CompletableFuture<SchematicInfo>> eldest)
                {
                    return this.size() > INFO_CACHE;
                }
            };
    private final Map<SchematicEntry, WeakReference<VoxelSnapshot>> snapshots = new HashMap<>();
    /** С каким пределом клеток снят снимок: «Качественно» берёт больше — грубый снимок тогда снимаем заново. */
    private final Map<SchematicEntry, Long> snapshotCells = new HashMap<>();
    private final Map<SchematicEntry, DecodeJob> jobs = new HashMap<>();

    private final AtomicLong searchTicket = new AtomicLong();
    private @Nullable CompletableFuture<List<SchematicEntry>> lastSearch;

    private final Map<Path, long[]> counts = new HashMap<>();
    private final SchematicLoader loader = new SchematicLoader();
    private final ThumbCache thumbCache;

    private @Nullable Path root;
    // Пишется из потока поиска и клиентского; гонка безобидна, volatile — чтобы не перечитывать зря
    private volatile boolean dosAttributes = true;

    private SchematicLibrary()
    {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("layerlist").resolve("schematic-thumbs");
        this.thumbCache = new ThumbCache(dir, this::thumbnailPixels, this.meta);
    }

    public static SchematicLibrary get()
    {
        return INSTANCE;
    }

    /**
     * Корень каталога — папка схем Litematica. Её метод создаёт папку и удаляет обычный файл на
     * её месте, поэтому зовём его один раз и запоминаем путь; {@link #refreshRoot()} — при
     * открытии экрана, если игрок сменил папку в настройках Litematica.
     */
    public Path root()
    {
        Path cached = this.root;

        return cached != null ? cached : this.refreshRoot();
    }

    public Path refreshRoot()
    {
        Path fresh = DataManager.getSchematicsBaseDirectory().toAbsolutePath().normalize();
        this.root = fresh;

        return fresh;
    }

    // ===== Листинг и поиск =====

    /** Папки, затем схемы, каждые — по имени; быстро, без чтения NBT. Скрытые и чужие файлы пропускаются. */
    public List<SchematicEntry> list(Path dir)
    {
        List<SchematicEntry> folders = new ArrayList<>();
        List<SchematicEntry> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path child : stream)
            {
                SchematicEntry entry = this.entry(child);

                if (entry != null)
                {
                    (entry.directory() ? folders : files).add(entry);
                }
            }
        }
        catch (IOException | RuntimeException error)
        {
            LOGGER.debug("Cannot list {}", dir, error);
        }

        folders.sort(BY_NAME);
        files.sort(BY_NAME);
        folders.addAll(files);

        return folders;
    }

    /**
     * Запись каталога для пути: папка или файл схемы; {@code null} — скрытый, не схема или не
     * читается. Пригодится и для файла, только что перетащенного в окно.
     */
    public @Nullable SchematicEntry entry(Path path)
    {
        Path abs = path.toAbsolutePath().normalize();
        Path name = abs.getFileName();

        if (name == null || name.toString().startsWith("."))
        {
            return null;
        }

        BasicFileAttributes attributes;

        try
        {
            attributes = this.attributes(abs);
        }
        catch (IOException | RuntimeException error)
        {
            return null;
        }

        if (attributes instanceof DosFileAttributes dos && (dos.isHidden() || dos.isSystem()))
        {
            return null;
        }

        long modified = attributes.lastModifiedTime().toMillis();

        if (attributes.isDirectory())
        {
            return new SchematicEntry(abs, true, null, 0L, modified);
        }

        SchematicFormat format = attributes.isRegularFile() ? SchematicFormat.of(abs) : null;

        return format == null ? null : new SchematicEntry(abs, false, format, attributes.size(), modified);
    }

    // На Windows атрибуты DOS дают «скрытый» тем же системным вызовом; на остальных их нет
    private BasicFileAttributes attributes(Path path) throws IOException
    {
        if (this.dosAttributes)
        {
            try
            {
                return Files.readAttributes(path, DosFileAttributes.class);
            }
            catch (UnsupportedOperationException error)
            {
                this.dosAttributes = false;
            }
        }

        return Files.readAttributes(path, BasicFileAttributes.class);
    }

    /** Схем прямо в папке (без подпапок) — для строки папки; кешируется на пару секунд. */
    public int schematicCount(Path dir)
    {
        long now = Util.getMillis();
        long[] known = this.counts.get(dir);

        if (known != null && now - known[1] < COUNT_TTL_MS)
        {
            return (int) known[0];
        }

        int count = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))
        {
            for (Path child : stream)
            {
                Path name = child.getFileName();

                if (name != null && ! name.toString().startsWith(".") && SchematicFormat.of(child) != null)
                {
                    ++count;
                }
            }
        }
        catch (IOException | RuntimeException error)
        {
            count = 0;
        }

        this.counts.put(dir, new long[]{count, now});

        return count;
    }

    /**
     * Рекурсивный поиск схем по имени (без регистра) в папке и подпапках. Новый запрос отменяет
     * прошлый: его будущее завершается {@link CancellationException}. Пустой запрос — содержимое
     * папки, как {@link #list(Path)}.
     */
    public CompletableFuture<List<SchematicEntry>> search(Path dir, String query)
    {
        long ticket = this.searchTicket.incrementAndGet();
        CompletableFuture<List<SchematicEntry>> previous = this.lastSearch;

        if (previous != null)
        {
            previous.cancel(false);
        }

        CompletableFuture<List<SchematicEntry>> future = new CompletableFuture<>();
        this.lastSearch = future;
        String needle = query.trim().toLowerCase(Locale.ROOT);

        if (needle.isEmpty())
        {
            future.complete(this.list(dir));

            return future;
        }

        BooleanSupplier stale = () -> future.isDone() || this.searchTicket.get() != ticket;

        this.searches.execute(() ->
        {
            if (stale.getAsBoolean())
            {
                return;
            }

            try
            {
                List<SchematicEntry> found = this.walk(dir, needle, stale);

                if (! stale.getAsBoolean())
                {
                    found.sort(BY_NAME);
                    future.complete(found);
                }
            }
            catch (IOException | RuntimeException error)
            {
                future.completeExceptionally(new SchematicReadException(error, "layerlist.schematics.error.read",
                        SchematicReadException.reason(error)));
            }
        });

        return future;
    }

    private List<SchematicEntry> walk(Path dir, String needle, BooleanSupplier stale) throws IOException
    {
        List<SchematicEntry> found = new ArrayList<>();
        Path start = dir.toAbsolutePath().normalize();

        // Ссылки не разворачиваем: петля из ссылок на папку-родителя вешала бы поиск
        Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), SEARCH_MAX_DEPTH, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
            {
                if (stale.getAsBoolean())
                {
                    return FileVisitResult.TERMINATE;
                }

                return path.equals(start) || SchematicLibrary.this.entry(path) != null
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
            {
                if (stale.getAsBoolean())
                {
                    return FileVisitResult.TERMINATE;
                }

                if (SchematicFormat.of(path) == null)
                {
                    return FileVisitResult.CONTINUE;
                }

                SchematicEntry entry = SchematicLibrary.this.entry(path);

                if (entry != null && ! entry.directory() && entry.name().toLowerCase(Locale.ROOT).contains(needle))
                {
                    found.add(entry);
                }

                return found.size() >= SEARCH_MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error)
            {
                return FileVisitResult.CONTINUE;
            }
        });

        return found;
    }

    /** Порядок списка: папки всегда сверху, внутри групп — по выбранному ключу; при равенстве — по имени. */
    public static Comparator<SchematicEntry> comparator(SchematicsState.Sort sort, boolean descending)
    {
        Comparator<SchematicEntry> key = switch (sort)
        {
            case NAME -> BY_NAME;
            case DATE -> Comparator.comparingLong(SchematicEntry::modified).thenComparing(BY_NAME);
            case SIZE -> Comparator.comparingLong(SchematicEntry::size).thenComparing(BY_NAME);
        };

        if (descending)
        {
            key = key.reversed();
        }

        return Comparator.comparing((SchematicEntry entry) -> ! entry.directory()).thenComparing(key);
    }

    /** Копия списка в порядке {@link #comparator}. */
    public static List<SchematicEntry> sorted(Collection<SchematicEntry> entries, SchematicsState.Sort sort,
                                              boolean descending)
    {
        List<SchematicEntry> copy = new ArrayList<>(entries);
        copy.sort(comparator(sort, descending));

        return copy;
    }

    /** Сравнение имён «как человек»: числа внутри имени — по значению («база2» раньше «база10»). */
    static int compareNatural(String a, String b)
    {
        int i = 0;
        int j = 0;

        while (i < a.length() && j < b.length())
        {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb))
            {
                int si = i;
                int sj = j;

                while (i < a.length() && Character.isDigit(a.charAt(i)))
                {
                    ++i;
                }

                while (j < b.length() && Character.isDigit(b.charAt(j)))
                {
                    ++j;
                }

                String na = stripZeros(a.substring(si, i));
                String nb = stripZeros(b.substring(sj, j));
                int order = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);

                if (order != 0)
                {
                    return order;
                }

                continue;
            }

            int order = Character.compare(fold(ca), fold(cb));

            if (order != 0)
            {
                return order;
            }

            ++i;
            ++j;
        }

        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String stripZeros(String digits)
    {
        int k = 0;

        while (k < digits.length() - 1 && digits.charAt(k) == '0')
        {
            ++k;
        }

        return digits.substring(k);
    }

    // «ё» — рядом с «е», как в словаре, а не после «я», как в Юникоде
    private static char fold(char c)
    {
        char lower = Character.toLowerCase(c);

        return lower == 'ё' ? 'е' : lower;
    }

    // ===== Сведения =====

    /** Сведения о файле; кеш по (путь, размер, время). Ошибка чтения — в {@link SchematicInfo#error()}. */
    public CompletableFuture<SchematicInfo> info(SchematicEntry e)
    {
        if (e.directory() || e.format() == null)
        {
            return CompletableFuture.completedFuture(SchematicInfo.failed(e.name(),
                    new SchematicReadException("layerlist.schematics.error.not_schematic").getMessage()));
        }

        CompletableFuture<SchematicInfo> shared;

        synchronized (this.lock)
        {
            shared = this.infos.get(e);

            if (shared == null)
            {
                shared = CompletableFuture.supplyAsync(() -> MetadataReader.read(e), this.meta);
                this.infos.put(e, shared);
            }
        }

        // Копия: отмена у одного зрителя не должна отменять общий кеш
        return shared.copy();
    }

    // ===== Снимки =====

    /**
     * Снимок блоков для превью (до {@link #previewCells()} клеток, иначе грубая сетка). Кеш на
     * слабых ссылках: держит снимок тот, кто его показывает. Отмена всех ждущих одного файла
     * останавливает и сам разбор.
     */
    public CompletableFuture<VoxelSnapshot> snapshot(SchematicEntry e)
    {
        if (e.directory() || e.format() == null)
        {
            return CompletableFuture.failedFuture(new SchematicReadException("layerlist.schematics.error.not_schematic"));
        }

        CompletableFuture<VoxelSnapshot> waiter = new CompletableFuture<>();
        DecodeJob fresh = null;

        synchronized (this.lock)
        {
            VoxelSnapshot cached = this.cachedSnapshot(e);

            if (cached != null)
            {
                waiter.complete(cached);

                return waiter;
            }

            DecodeJob job = this.jobs.get(e);

            if (job == null)
            {
                job = new DecodeJob(e);
                this.jobs.put(e, job);
                fresh = job;
            }

            job.waiters.add(waiter);
        }

        if (fresh != null)
        {
            this.run(fresh);
        }

        return waiter;
    }

    private @Nullable VoxelSnapshot cachedSnapshot(SchematicEntry e)
    {
        WeakReference<VoxelSnapshot> ref = this.snapshots.get(e);
        VoxelSnapshot snapshot = ref == null ? null : ref.get();

        // Грубый снимок, снятый с меньшим пределом (до «Качественно»), — снять заново, мельче
        if (snapshot != null && snapshot.scale() > 1 && this.snapshotCells.getOrDefault(e, PREVIEW_CELLS) < previewCells())
        {
            return null;
        }

        return snapshot;
    }

    /**
     * Предел клеток снимка превью: «Оптимизировано» — {@link #PREVIEW_CELLS}, «Качественно» — сколько позволяет память
     * игры ({@link PreviewLimits#cells}), до {@link VoxelSnapshot#MAX_VOLUME}.
     */
    public static long previewCells()
    {
        return SchematicsConfig.previewQuality() == SchematicsConfig.PreviewQuality.QUALITY
                ? PreviewLimits.cells(PREVIEW_CELLS, VoxelSnapshot.MAX_VOLUME)
                : PREVIEW_CELLS;
    }

    private void run(DecodeJob job)
    {
        SchematicEntry e = job.entry;
        CompletableFuture<VoxelSnapshot> work;

        if (e.format() == SchematicFormat.SCHEMATICA)
        {
            // Числовые id переводит только Litematica: загрузка на клиентском потоке, снимок — в фоне
            work = this.loader.load(e).thenApplyAsync(loaded ->
                    unchecked(() -> LitematicaSnapshot.of(loaded.litematica(), job.cells, job::abandoned)), this.decode);
        }
        else
        {
            work = CompletableFuture.supplyAsync(() -> this.decodeFile(e, job.cells, job::abandoned), this.decode);
        }

        work.whenComplete((snapshot, error) -> this.finish(job, snapshot, error));
    }

    private void finish(DecodeJob job, @Nullable VoxelSnapshot snapshot, @Nullable Throwable error)
    {
        synchronized (this.lock)
        {
            this.jobs.remove(job.entry, job);

            if (snapshot != null)
            {
                this.snapshots.values().removeIf(ref -> ref.get() == null);
                this.snapshots.put(job.entry, new WeakReference<>(snapshot));
                this.snapshotCells.keySet().retainAll(this.snapshots.keySet());
                this.snapshotCells.put(job.entry, job.cells);
            }
        }

        Throwable cause = unwrap(error);

        for (CompletableFuture<VoxelSnapshot> waiter : job.waiters)
        {
            if (snapshot != null)
            {
                waiter.complete(snapshot);
            }
            else
            {
                waiter.completeExceptionally(cause);
            }
        }
    }

    /** Разбор файла своим декодером; большой файл ждёт, пока разберут другой большой. */
    private VoxelSnapshot decodeFile(SchematicEntry e, long cells, BooleanSupplier cancelled)
    {
        if (cancelled.getAsBoolean())
        {
            throw new CancellationException();
        }

        boolean big = e.size() >= HEAVY_BYTES;

        if (big)
        {
            this.heavy.acquireUninterruptibly();
        }

        try
        {
            return unchecked(() -> SchematicDecoder.decode(e.path(), e.format(), cells, cancelled));
        }
        finally
        {
            if (big)
            {
                this.heavy.release();
            }
        }
    }

    // ===== Загрузка в Litematica =====

    /**
     * Загрузка в Litematica её же методами; будущее завершается на клиентском потоке. Нужен мир:
     * до входа — ошибка «Зайдите в мир». В «Загруженные схемы» Litematica не добавляется.
     */
    public CompletableFuture<LoadedSchematic> load(SchematicEntry e)
    {
        return this.loader.load(e);
    }

    /**
     * Экран вкладки дорисовал кадр (зовётся в конце его отрисовки): загрузка в Litematica, попрошенная в
     * прошлом кадре, начнётся на ближайшем тике, когда «Загрузка…» уже на экране.
     */
    public void frameDrawn()
    {
        this.loader.frameDrawn();
    }

    SchematicLoader loader()
    {
        return this.loader;
    }

    // ===== Миниатюры =====

    /**
     * Текстура миниатюры 64×64 (линейная фильтрация); {@code null} — ещё нет, запрос поставлен.
     * Звать только для строк, которые сейчас видны: давно не повторённый запрос забывается.
     * Для {@code .schematic} миниатюра появляется, только когда схему уже открывали (снимок в
     * памяти): ради миниатюры её не грузим.
     */
    public @Nullable Identifier thumbnail(SchematicEntry e)
    {
        if (e.directory() || e.format() == null || ! SchematicsConfig.THUMBNAILS.getBooleanValue())
        {
            return null;
        }

        return this.thumbCache.get(e);
    }

    /** Освободить текстуры миниатюр (при закрытии экрана). */
    public void releaseTextures()
    {
        this.thumbCache.release();
        BlockColorTable.clear();
    }

    /**
     * Файл (или папка) ушёл в корзину: забыть его сведения, снимок блоков, текстуру миниатюры и счёт
     * схем в папках. Клиентский поток. PNG миниатюры на диске остаётся — вернут из корзины, и она
     * пригодится; удалённое насовсем чистит {@link #dropThumbnailFiles}.
     */
    public void forget(Path path)
    {
        Path gone = path.toAbsolutePath().normalize();

        synchronized (this.lock)
        {
            this.infos.keySet().removeIf(e -> e.path().toAbsolutePath().normalize().equals(gone));
            this.snapshots.keySet().removeIf(e -> e.path().toAbsolutePath().normalize().equals(gone));
        }

        this.counts.clear();
        this.thumbCache.forget(gone);
    }

    /**
     * Файл переименовали: забыть старое имя, как {@link #forget}, а PNG миниатюр перенести под новое — рисовать
     * её заново незачем. Клиентский поток.
     */
    public void renamed(Path from, Path to)
    {
        Path old = from.toAbsolutePath().normalize();
        this.forget(old);
        this.thumbCache.moveFiles(old, to.toAbsolutePath().normalize());
    }

    /** Удалено насовсем: PNG его миниатюр на диске больше не нужны (в фоне, с любого потока). */
    public void dropThumbnailFiles(Path path)
    {
        this.thumbCache.deleteFiles(path.toAbsolutePath().normalize());
    }

    /** Пиксели миниатюры: встроенное превью Litematica, иначе своя изометрия; {@code null} — не будет. */
    private CompletableFuture<int @Nullable []> thumbnailPixels(SchematicEntry e)
    {
        return this.info(e).thenCompose(info ->
        {
            int[] preview = info.previewArgb();

            if (preview != null)
            {
                return CompletableFuture.supplyAsync(() -> ThumbnailRenderer.fromPreview(preview, info.previewSide()), this.thumbs);
            }

            if (! info.ok())
            {
                return CompletableFuture.completedFuture(null);
            }

            return this.thumbSnapshot(e).thenCompose(snapshot -> snapshot == null
                    ? CompletableFuture.completedFuture(null)
                    : Minecraft.getInstance().submit(() -> BlockColorTable.colors(snapshot))
                            .thenApplyAsync(colors -> ThumbnailRenderer.render(snapshot, colors), this.thumbs));
        });
    }

    /** Снимок для миниатюры: готовый снимок превью, если он есть или уже разбирается, иначе грубый свой. */
    private CompletableFuture<@Nullable VoxelSnapshot> thumbSnapshot(SchematicEntry e)
    {
        CompletableFuture<VoxelSnapshot> waiter = new CompletableFuture<>();

        synchronized (this.lock)
        {
            VoxelSnapshot cached = this.cachedSnapshot(e);

            if (cached != null)
            {
                return CompletableFuture.completedFuture(cached);
            }

            DecodeJob job = this.jobs.get(e);

            if (job != null)
            {
                job.waiters.add(waiter);

                return waiter.exceptionally(error -> null);
            }
        }

        if (e.format() == SchematicFormat.SCHEMATICA)
        {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return this.decodeFile(e, THUMB_CELLS, () -> false);
            }
            catch (CompletionException error)
            {
                LOGGER.debug("No thumbnail snapshot for {}", e.path(), error.getCause());

                return null;
            }
        }, this.thumbs);
    }

    // ===== Общее =====

    /**
     * Текст ошибки для игрока из провала любого будущего библиотеки: разворачивает
     * {@link CompletionException}/{@link ExecutionException}, у
     * {@link SchematicReadException} берёт её перевод, у прочего — «Файл не читается: …».
     * Отмену ({@link CancellationException}) показывать не нужно — проверьте её раньше.
     */
    public static String errorText(Throwable error)
    {
        Throwable cause = error;

        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        if (cause instanceof SchematicReadException read)
        {
            return read.getMessage();
        }

        return new SchematicReadException(cause, "layerlist.schematics.error.read", SchematicReadException.reason(cause)).getMessage();
    }

    /** Разбор одного файла и все, кто ждёт его снимок. */
    private static final class DecodeJob
    {
        private final SchematicEntry entry;
        /** Предел клеток на момент заказа ({@link #previewCells()}). */
        private final long cells = previewCells();
        private final List<CompletableFuture<VoxelSnapshot>> waiters = new CopyOnWriteArrayList<>();

        private DecodeJob(SchematicEntry entry)
        {
            this.entry = entry;
        }

        /** Все ждущие ушли (отменили) — разбор можно бросить. */
        private boolean abandoned()
        {
            for (CompletableFuture<VoxelSnapshot> waiter : this.waiters)
            {
                if (! waiter.isDone())
                {
                    return false;
                }
            }

            return true;
        }
    }

    @FunctionalInterface
    private interface Reader<T>
    {
        T read() throws SchematicReadException;
    }

    private static <T> T unchecked(Reader<T> reader)
    {
        try
        {
            return reader.read();
        }
        catch (SchematicReadException error)
        {
            throw new CompletionException(error);
        }
    }

    private static Throwable unwrap(@Nullable Throwable error)
    {
        Throwable cause = error;

        while (cause instanceof CompletionException && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause == null ? new CancellationException() : cause;
    }

    private static ExecutorService pool(String name, int threads)
    {
        AtomicInteger number = new AtomicInteger();

        return Executors.newFixedThreadPool(threads, task ->
        {
            Thread thread = new Thread(task, "layerlist-schematics-" + name + "-" + number.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);

            return thread;
        });
    }
}
