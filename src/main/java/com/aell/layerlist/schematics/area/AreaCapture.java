package com.aell.layerlist.schematics.area;

import com.aell.layerlist.schematics.library.VoxelSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Снимок прогруженной области мира для превью вкладки «Выделение». Квадрат чанков вокруг игрока радиусом
 * {@code min(32, дальность прорисовки)}; по высоте — от нижней поверхности с запасом под воду до верхней, плюс
 * высоты выделения и игрока. Клеток не больше бюджета: иначе снимок прореживается ({@link VoxelSnapshot#scale()}).
 *
 * <p>Как у Sodium и Voxy: на клиентском потоке ({@link #tick}) секции чанков только копируются (копия палитры и
 * упакованного массива — микросекунды), разбор копий в клетки снимка — в своём фоновом потоке. Чужой поток читать сам
 * чанк не может: игра его как раз меняет. Раньше разбор шёл на клиентском потоке по 4 мс за кадр — блок за блоком,
 * около сотни миллионов чтений при 32 чанках: десятки секунд подгрузки и просадка каждого кадра. Незагруженные чанки
 * остаются воздухом.
 *
 * <p>Клетка крупнее блока получает верхний блок своего объёма (вид сверху: трава, а не камень под ней); растения и
 * прочее несплошное уступают сплошному блоку или жидкости ниже — иначе клетка рисовалась бы кустом во весь размер. Столбец чанка берётся только видимой сверху
 * частью — от самой низкой поверхности его и соседей с запасом (как в Bobby Map), а где стоит выделение — до его
 * низа: подземный камень превью не нужен, а модель строится в разы быстрее.
 */
@Environment(EnvType.CLIENT)
public final class AreaCapture
{
    public static final int MAX_RADIUS = 32;
    /** Запас вниз от самой низкой поверхности: дно рек и морей под водой. */
    private static final int BELOW_SURFACE = 32;
    private static final int ABOVE_SURFACE = 4;
    private static final int SURFACE_SAMPLE = 4;
    /** Запас под самой низкой поверхностью чанка и его соседей (приём «честной» 3D-карты Bobby Map). */
    private static final int DEPTH_MARGIN = 8;

    /** Разбор копий секций: один поток по очереди — снимок пишет только он. */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "Material HUD area capture");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** Чанков в очереди разбора не больше: дальше копии ждут следующего кадра (память — несколько мегабайт). */
    private static final int MAX_PENDING = 256;

    /** Снимок и где он лежит в мире: клетка {@code c} — блок {@code origin + c · scale}. */
    public record Result(VoxelSnapshot snapshot, BlockPos origin, int scale, int worldBottom, int worldTop)
    {
        public BlockPos toWorld(int[] cell)
        {
            return this.origin.offset(cell[0] * this.scale, cell[1] * this.scale, cell[2] * this.scale);
        }

        public float[] toCell(double x, double y, double z)
        {
            return new float[] { (float) ((x - this.origin.getX()) / this.scale), (float) ((y - this.origin.getY()) / this.scale),
                    (float) ((z - this.origin.getZ()) / this.scale) };
        }
    }

    private final ClientLevel level;
    private final int minCX;
    private final int minCZ;
    private final int chunksX;
    private final int chunksZ;
    private final int minY;
    private final int maxY;
    private final int scale;
    /** Угол снимка в мире по X/Z: у области — угол первого чанка, у коробки выделения — её угол. */
    private final int originX;
    private final int originZ;
    /** Размер снимка в блоках по X/Z: блоки чанков за ним (у коробки — за её краем) не пишутся. */
    private final int limitX;
    private final int limitZ;
    private final VoxelSnapshot.Builder builder;
    /** Самая низкая поверхность каждого чанка ({@code Integer.MAX_VALUE} — не загружен). */
    private final int[] lowest;
    private final List<BoundingBox> keep;
    private int next;
    private boolean submittedAll;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean cancelled;
    private volatile @Nullable Result result;
    /** Только поток разбора: «вес» состояния по номеру палитры (0 — ещё не считан, 1 — несплошное, 2 — сплошное или жидкость). */
    private byte[] weights = new byte[64];

    /** Копии непустых секций чанка от его нижней видимой высоты; {@code null} — секция пуста. */
    private record ChunkCopy(int ix, int iz, int fromY, int firstSection, @Nullable PalettedContainer<BlockState>[] sections) {}

    private AreaCapture(ClientLevel level, int minCX, int minCZ, int chunksX, int chunksZ, int minY, int maxY, int scale,
            int[] lowest, List<BoundingBox> keep)
    {
        this(level, minCX, minCZ, chunksX, chunksZ, minY, maxY, scale, lowest, keep, minCX * 16, minCZ * 16, chunksX * 16, chunksZ * 16);
    }

    private AreaCapture(ClientLevel level, int minCX, int minCZ, int chunksX, int chunksZ, int minY, int maxY, int scale,
            int[] lowest, List<BoundingBox> keep, int originX, int originZ, int limitX, int limitZ)
    {
        this.originX = originX;
        this.originZ = originZ;
        this.limitX = limitX;
        this.limitZ = limitZ;
        this.lowest = lowest;
        this.keep = List.copyOf(keep);
        this.level = level;
        this.minCX = minCX;
        this.minCZ = minCZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.minY = minY;
        this.maxY = maxY;
        this.scale = scale;
        this.builder = new VoxelSnapshot.Builder(ceilDiv(limitX, scale), ceilDiv(maxY - minY + 1, scale),
                ceilDiv(limitZ, scale), scale);
    }

    /**
     * Снимок ровно коробки выделения (срез по слоям): те же копии секций и разбор в фоне, что у области, но клетки —
     * только внутри коробки, по высоте — от её низа до верха. Незагруженные чанки — воздух.
     *
     * @param maxCells бюджет клеток; коробка больше — клетка крупнее (2, 4… блока)
     */
    public static AreaCapture startBox(ClientLevel level, BoundingBox box, long maxCells)
    {
        int minY = Math.max(level.getMinY(), box.minY());
        int maxY = Math.max(minY, Math.min(level.getMaxY(), box.maxY()));
        int minCX = box.minX() >> 4;
        int minCZ = box.minZ() >> 4;
        int chunksX = (box.maxX() >> 4) - minCX + 1;
        int chunksZ = (box.maxZ() >> 4) - minCZ + 1;
        int[] lowest = new int[chunksX * chunksZ];
        java.util.Arrays.fill(lowest, minY);
        int scale = 1;

        while (scale < 16 && (long) ceilDiv(box.getXSpan(), scale) * ceilDiv(maxY - minY + 1, scale) * ceilDiv(box.getZSpan(), scale) > maxCells)
        {
            scale *= 2;
        }

        return new AreaCapture(level, minCX, minCZ, chunksX, chunksZ, minY, maxY, scale, lowest, List.of(), box.minX(), box.minZ(),
                box.getXSpan(), box.getZSpan());
    }

    /**
     * @param center    вокруг чего (позиция игрока)
     * @param radius    радиус в чанках, не больше {@link #MAX_RADIUS}
     * @param maxCells  бюджет клеток снимка (от режима качества)
     * @param keep      что обязательно попасть по высоте (компоненты выделения)
     */
    public static AreaCapture start(ClientLevel level, BlockPos center, int radius, long maxCells, List<BoundingBox> keep)
    {
        int r = Math.max(1, Math.min(MAX_RADIUS, radius));
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;
        int minCX = ccx - r;
        int minCZ = ccz - r;
        int chunks = r * 2 + 1;

        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int[] lowest = new int[chunks * chunks];
        java.util.Arrays.fill(lowest, Integer.MAX_VALUE);

        for (int cx = minCX; cx < minCX + chunks; ++cx)
        {
            for (int cz = minCZ; cz < minCZ + chunks; ++cz)
            {
                LevelChunk chunk = loaded(level, cx, cz);

                if (chunk == null)
                {
                    continue;
                }

                int chunkLow = Integer.MAX_VALUE;

                for (int x = 0; x < 16; x += SURFACE_SAMPLE)
                {
                    for (int z = 0; z < 16; z += SURFACE_SAMPLE)
                    {
                        int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        chunkLow = Math.min(chunkLow, h);
                        low = Math.min(low, h);
                        high = Math.max(high, h);
                    }
                }

                lowest[(cz - minCZ) * chunks + (cx - minCX)] = chunkLow;
            }
        }

        if (low == Integer.MAX_VALUE)
        {
            low = center.getY();
            high = center.getY();
        }

        int minY = Math.min(low - BELOW_SURFACE, center.getY() - 8);
        int maxY = Math.max(high + ABOVE_SURFACE, center.getY() + 8);

        for (BoundingBox box : keep)
        {
            minY = Math.min(minY, box.minY() - 2);
            maxY = Math.max(maxY, box.maxY() + 2);
        }

        minY = Math.max(level.getMinY(), minY);
        maxY = Math.min(level.getMaxY(), maxY);

        int scale = 1;
        long side = (long) chunks * 16;

        while (scale < 16 && (side / scale) * (side / scale) * ((maxY - minY + 1L) / scale) > maxCells)
        {
            scale *= 2;
        }

        return new AreaCapture(level, minCX, minCZ, chunks, chunks, minY, maxY, scale, lowest, keep);
    }

    private static @Nullable LevelChunk loaded(ClientLevel level, int cx, int cz)
    {
        return level.hasChunk(cx, cz) ? level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) : null;
    }

    /**
     * Копировать секции чанков, пока не выйдет {@code budgetNanos} (разбор — в фоне); {@code true} — снимок готов.
     * Только клиентский поток.
     */
    public boolean tick(long budgetNanos)
    {
        if (this.result != null)
        {
            return true;
        }

        long until = System.nanoTime() + budgetNanos;
        int total = this.chunksX * this.chunksZ;

        while (this.next < total && this.pending.get() < MAX_PENDING && System.nanoTime() < until)
        {
            ChunkCopy copy = this.copyChunk(this.next % this.chunksX, this.next / this.chunksX);
            ++this.next;

            if (copy != null)
            {
                this.pending.incrementAndGet();
                DECODER.execute(() ->
                {
                    try
                    {
                        if (! this.cancelled)
                        {
                            this.decode(copy);
                        }
                    }
                    finally
                    {
                        this.pending.decrementAndGet();
                    }
                });
            }
        }

        // Все копии в очереди — сборка снимка встаёт за ними (поток один, порядок сохраняется)
        if (this.next >= total && ! this.submittedAll)
        {
            this.submittedAll = true;
            DECODER.execute(() ->
            {
                if (! this.cancelled)
                {
                    this.result = new Result(this.builder.build(), new BlockPos(this.originX, this.minY, this.originZ), this.scale,
                            Math.floorDiv(this.level.getMinY() - this.minY, this.scale),
                            Math.floorDiv(this.level.getMaxY() + 1 - this.minY, this.scale));
                }
            });
        }

        return this.result != null;
    }

    /** Снимок больше не нужен (новый, экран закрыт): фон бросает оставшийся разбор. */
    public void cancel()
    {
        this.cancelled = true;
    }

    public float progress()
    {
        // Копирование и разбор вместе: скопировано, но ещё не разобрано — половина дела
        int total = Math.max(1, this.chunksX * this.chunksZ);
        return this.result != null ? 1.0F : Math.min(0.99F, (this.next - this.pending.get() * 0.5F) / total);
    }

    public @Nullable Result result()
    {
        return this.result;
    }

    /** Копии секций чанка от его нижней видимой высоты до верха снимка; чанка нет — {@code null}. Клиентский поток. */
    @SuppressWarnings("unchecked")
    private @Nullable ChunkCopy copyChunk(int ix, int iz)
    {
        LevelChunk chunk = loaded(this.level, this.minCX + ix, this.minCZ + iz);

        if (chunk == null)
        {
            return null;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int fromY = Math.max(this.minY, this.columnBottom(ix, iz));
        int first = Math.max(0, this.level.getSectionIndex(fromY));
        int last = Math.min(sections.length - 1, this.level.getSectionIndex(this.maxY));

        if (last < first)
        {
            return null;
        }

        PalettedContainer<BlockState>[] copies = new PalettedContainer[last - first + 1];
        boolean any = false;

        for (int i = first; i <= last; ++i)
        {
            LevelChunkSection section = sections[i];

            if (section != null && ! section.hasOnlyAir())
            {
                copies[i - first] = section.getStates().copy();
                any = true;
            }
        }

        return any ? new ChunkCopy(ix, iz, fromY, first, copies) : null;
    }

    /** Копии секций — в клетки снимка, сверху вниз (в клетке остаётся верхний сплошной блок). Поток разбора. */
    private void decode(ChunkCopy copy)
    {
        int s = this.scale;
        // Блок чанка относительно угла снимка; у коробки выделения чанки шире неё — края отбрасываются
        int baseX = (this.minCX + copy.ix()) * 16 - this.originX;
        int baseZ = (this.minCZ + copy.iz()) * 16 - this.originZ;
        int minSectionY = this.level.getMinSectionY();

        for (int i = copy.sections().length - 1; i >= 0; --i)
        {
            PalettedContainer<BlockState> states = copy.sections()[i];

            if (states == null || this.cancelled)
            {
                continue;
            }

            int sectionBottom = (minSectionY + copy.firstSection() + i) << 4;
            int top = Math.min(this.maxY, sectionBottom + 15);
            int bottom = Math.max(copy.fromY(), sectionBottom);

            for (int y = top; y >= bottom; --y)
            {
                int ly = y & 15;
                int cy = (y - this.minY) / s;

                for (int lz = 0; lz < 16; ++lz)
                {
                    if (baseZ + lz < 0 || baseZ + lz >= this.limitZ)
                    {
                        continue;
                    }

                    int cz = (baseZ + lz) / s;

                    for (int lx = 0; lx < 16; ++lx)
                    {
                        if (baseX + lx < 0 || baseX + lx >= this.limitX)
                        {
                            continue;
                        }

                        BlockState state = states.get(lx, ly, lz);

                        if (state.isAir())
                        {
                            continue;
                        }

                        int cx = (baseX + lx) / s;

                        if (s == 1)
                        {
                            this.builder.set(cx, cy, cz, state);
                            continue;
                        }

                        int current = this.builder.idAt(cx, cy, cz);

                        // Пусто — ставим; несплошное сверху уступает сплошному или жидкости ниже
                        if (current == 0 || (this.weight(current, null) == 1 && this.weight(-1, state) == 2))
                        {
                            this.builder.set(cx, cy, cz, state);
                        }
                    }
                }
            }
        }
    }

    /** «Вес» состояния: 2 — сплошной блок или жидкость, 1 — остальное (растения, факелы, снег слоем…). */
    private int weight(int id, @Nullable BlockState state)
    {
        if (state != null)
        {
            id = this.builder.id(state);
        }

        if (id >= this.weights.length)
        {
            this.weights = Arrays.copyOf(this.weights, Math.max(id + 1, this.weights.length * 2));
        }

        byte known = this.weights[id];

        if (known != 0)
        {
            return known;
        }

        BlockState of = state != null ? state : this.builder.palette(id);
        byte weight = (byte) (of.isSolidRender() || ! of.getFluidState().isEmpty() ? 2 : 1);
        this.weights[id] = weight;
        return weight;
    }

    /** Низ видимой части столбца: самая низкая поверхность чанка и соседей с запасом, а под выделением — его низ. */
    private int columnBottom(int ix, int iz)
    {
        int low = Integer.MAX_VALUE;

        for (int dz = -1; dz <= 1; ++dz)
        {
            for (int dx = -1; dx <= 1; ++dx)
            {
                int nx = ix + dx;
                int nz = iz + dz;

                if (nx >= 0 && nz >= 0 && nx < this.chunksX && nz < this.chunksZ)
                {
                    low = Math.min(low, this.lowest[nz * this.chunksX + nx]);
                }
            }
        }

        int bottom = low == Integer.MAX_VALUE ? this.minY : low - DEPTH_MARGIN;
        int x0 = (this.minCX + ix) * 16;
        int z0 = (this.minCZ + iz) * 16;

        for (BoundingBox box : this.keep)
        {
            if (box.maxX() >= x0 && box.minX() <= x0 + 15 && box.maxZ() >= z0 && box.minZ() <= z0 + 15)
            {
                bottom = Math.min(bottom, box.minY());
            }
        }

        return bottom;
    }

    private static int ceilDiv(int value, int divisor)
    {
        return (value + divisor - 1) / divisor;
    }
}
