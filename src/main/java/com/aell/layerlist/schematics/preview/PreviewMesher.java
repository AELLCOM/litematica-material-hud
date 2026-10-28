package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.PreviewLimits;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сборка модели превью из {@link VoxelSnapshot} в фоне (M23).
 *
 * <p>Полный вид: секции 16³ по рядам снизу вверх, каждый блок — ванильным
 * {@code ModelBlockRenderer.tesselateBlock} в свой приёмник квадов, жидкости — {@code FluidRenderer}
 * (он пишет координаты внутри секции, поэтому секции выровнены по 16 от угла схемы). Блок, со всех
 * шести сторон закрытый полными непрозрачными соседями, даже не тесселируется. Готовые секции
 * кешируются в {@link Context}: смена среза перестраивает только ряд секций с верхним видимым слоем.
 *
 * <p>Бюджет квадов зависит от качества превью. Как только сумма квадов его превышает, полный вид
 * бросается и строится упрощённый: кубы по 2, 4, 8… блоков, цвет куба — средний цвет текстуры
 * верхнего блока в ячейке. Сетку упрощённого вида тоже кешируем, при срезе пересчитывается один её ряд.
 *
 * <p>Все сборки идут в одном фоновом потоке по очереди: кеши контекста трогает только он. Отмена —
 * флажок {@link Job#cancel()}, проверяется на каждой секции и каждом ряду ячеек.
 */
@Environment(EnvType.CLIENT)
final class PreviewMesher
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics-preview");

    static final int BUDGET_HIGH = 250_000;
    static final int BUDGET_FAST = 80_000;
    private static final int BUDGET_AUTO_MID = 150_000;
    private static final long AUTO_SMALL_VOLUME = 2_000_000L;
    private static final long AUTO_LARGE_VOLUME = 32_000_000L;

    /** Особых блоков (сундуков, флагов…) в одной модели не больше этого; каждый — отдельный вызов рендера. */
    static final int SPECIAL_CAP = 2048;
    /** Во сколько квадов бюджета обходится один особый блок (сундук — около двух десятков граней). */
    private static final int SPECIAL_QUAD_COST = 24;

    /** Секций в кеше держим не больше стольких бюджетов квадов. */
    private static final int CACHE_BUDGETS = 3;

    /** Ячеек базовой сетки упрощённого вида не больше: по 4 байта на ячейку это 32 МБ. */
    private static final long LOD_MAX_CELLS = 8_000_000L;
    private static final int LOD_MAX_FACTOR = 32;

    private static final int PROGRESS_FULL = 1000;
    private static final int SECTION = 16;

    private static final byte F_AIR = 1;
    private static final byte F_OPAQUE = 2;
    private static final byte F_FLUID = 4;
    private static final byte F_MODEL = 8;
    private static final byte F_SPECIAL = 16;
    private static final byte F_FORCE_SOLID = 32;

    private static final long COLOR_WAIT_MS = 50L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "Material HUD schematic preview");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);

        return thread;
    });

    private PreviewMesher() {}

    /**
     * Бюджет квадов по «Отображению схемы»: «Оптимизировано» — по объёму габарита схемы, «Качественно» — сколько
     * позволяет память игры ({@link PreviewLimits#quads}), но не меньше высокого.
     */
    static int budget(SchematicsConfig.PreviewQuality quality, VoxelSnapshot snapshot)
    {
        return switch (quality)
        {
            case QUALITY -> PreviewLimits.quads(BUDGET_HIGH);
            case OPTIMIZED -> snapshot.volume() <= AUTO_SMALL_VOLUME ? BUDGET_HIGH
                    : snapshot.volume() <= AUTO_LARGE_VOLUME ? BUDGET_AUTO_MID : BUDGET_FAST;
        };
    }

    /** Поставить сборку в очередь; {@code cut} — верхний видимый слой, {@link Integer#MAX_VALUE} — без среза. */
    static Job start(Context context, int cut, int budget)
    {
        Job job = new Job(cut, budget);
        job.future = CompletableFuture.supplyAsync(() ->
        {
            PreviewModel model = new Builder(context, job).build();
            model.statusSig = context.statusSig;
            // Вершины для видеопамяти пакуем здесь же, в фоне: основному потоку останется скопировать их на GPU
            model.pack();
            return model;
        }, EXECUTOR);

        return job;
    }

    /** Сборка в очереди или в работе: будущее, отмена, прогресс. */
    static final class Job
    {
        final int cut;
        final int budget;
        @Nullable CompletableFuture<PreviewModel> future;
        private volatile boolean cancelled;
        private volatile int progress;

        private Job(int cut, int budget)
        {
            this.cut = cut;
            this.budget = budget;
        }

        void cancel()
        {
            this.cancelled = true;
        }

        boolean cancelled()
        {
            return this.cancelled;
        }

        /** 0…1. */
        float progress()
        {
            return this.progress / (float) PROGRESS_FULL;
        }

        private void checkCancelled()
        {
            if (this.cancelled)
            {
                throw new Cancelled();
            }
        }
    }

    /** Отмена сборки: не ошибка, виджет её молча пропускает. */
    static final class Cancelled extends RuntimeException
    {
        private Cancelled()
        {
            super("preview build cancelled", null, false, false);
        }
    }

    /**
     * Всё, что сборка берёт из клиента, снятое на клиентском потоке, плюс кеши сборок одного снимка.
     * Наборы моделей держим ссылками: при перезагрузке ресурсов клиент заводит новые, а сравнение
     * ссылок в {@link #stale()} говорит виджету, что модель устарела.
     */
    static final class Context
    {
        final VoxelSnapshot snapshot;
        /** 1.21.11: модели блоков — {@link BlockModelShaper} (при перезагрузке ресурсов тот же объект; устаревание — по поколению). */
        final BlockModelShaper models;
        /** Жидкости тесселирует сам диспетчер блоков ({@code renderLiquid}) — наборов моделей жидкостей в 1.21.11 нет. */
        final BlockRenderDispatcher dispatcher;
        final BlockColors colors;
        final boolean ambientOcclusion;
        final boolean cutoutLeaves;
        final PreviewBlockView.Tint tint;
        final long generation;
        final int windingSign;
        /** Готовность по блокам: непостроенные — «призраком», чужие — красным; {@code null} — без неё. */
        final @Nullable BuildStatus status;
        final long statusSig;

        // Дальше — только поток сборки
        private byte @Nullable [] flags;
        private final Map<Integer, PreviewModel.Section> fullSections = new HashMap<>();
        private long cachedQuads;
        private final Map<Integer, PreviewModel.Section> cutSections = new HashMap<>();
        private int cutSectionsTop = Integer.MIN_VALUE;
        private final Map<Integer, LodGrid> lodGrids = new HashMap<>();
        private int @Nullable [] lodColors;

        private Context(VoxelSnapshot snapshot, @Nullable BuildStatus status)
        {
            this.status = status;
            this.statusSig = status != null ? status.signature : 0L;
            Minecraft mc = Minecraft.getInstance();
            ModelManager manager = mc.getModelManager();

            this.snapshot = snapshot;
            this.models = manager.getBlockModelShaper();
            this.dispatcher = mc.getBlockRenderer();
            this.colors = mc.getBlockColors();
            this.ambientOcclusion = mc.options.ambientOcclusion().get();
            this.cutoutLeaves = mc.options.cutoutLeaves().get();
            this.tint = PreviewBlockView.Tint.capture();
            this.generation = PreviewModule.generation();
            this.windingSign = windingSign(this.models);
        }

        /** Только клиентский поток. */
        static Context capture(VoxelSnapshot snapshot)
        {
            return new Context(snapshot, null);
        }

        /** С готовностью по блокам — её цвета войдут в модель. */
        static Context capture(VoxelSnapshot snapshot, @Nullable BuildStatus status)
        {
            return new Context(snapshot, status);
        }

        /** Ресурсы или настройки графики сменились — модель надо собрать заново. Только клиентский поток. */
        boolean stale()
        {
            Minecraft mc = Minecraft.getInstance();

            return mc.getModelManager().getBlockModelShaper() != this.models
                    || PreviewModule.generation() != this.generation
                    || mc.options.ambientOcclusion().get() != this.ambientOcclusion
                    || mc.options.cutoutLeaves().get() != this.cutoutLeaves;
        }

        /**
         * В какую сторону закручены ванильные грани: верхняя грань камня, нормаль по правилу правой
         * руки. Нужна, чтобы кубы упрощённого вида закручивались так же (иначе отсечение задних граней
         * спрятало бы лицевые) и чтобы отбрасывать повёрнутые от зрителя грани до записи вершин.
         */
        private static int windingSign(BlockModelShaper models)
        {
            try
            {
                List<BlockModelPart> parts = new ArrayList<>();
                models.getBlockModel(Blocks.STONE.defaultBlockState()).collectParts(RandomSource.create(42L), parts);

                for (BlockModelPart part : parts)
                {
                    for (BakedQuad quad : part.getQuads(Direction.UP))
                    {
                        Vector3fc p0 = quad.position(0);
                        Vector3fc p1 = quad.position(1);
                        Vector3fc p2 = quad.position(2);
                        Vector3fc p3 = quad.position(3);
                        float ax = p2.x() - p0.x();
                        float az = p2.z() - p0.z();
                        float bx = p3.x() - p1.x();
                        float bz = p3.z() - p1.z();
                        float upward = az * bx - ax * bz;

                        if (Math.abs(upward) > 1.0E-6F)
                        {
                            return upward > 0.0F ? 1 : -1;
                        }
                    }
                }
            }
            catch (RuntimeException e)
            {
                LOGGER.warn("Preview: could not probe face winding, assuming the vanilla default", e);
            }

            return 1;
        }

        private void cacheFull(int index, PreviewModel.Section section, int budget)
        {
            // «Качественно» (бюджет больше высокого): кеш — не больше одной модели, иначе память на огромной схеме
            // ушла бы на кеш среза, а не на честные блоки
            long cap = (budget > BUDGET_HIGH ? 1L : CACHE_BUDGETS) * budget;

            if (this.cachedQuads + section.quads <= cap)
            {
                this.fullSections.put(index, section);
                this.cachedQuads += section.quads;
            }
        }
    }

    /** Сетка упрощённого вида: номер палитры верхнего непустого блока в каждой ячейке, 0 — пусто. */
    private record LodGrid(int factor, int nx, int ny, int nz, int[] ids)
    {
        int id(int x, int y, int z)
        {
            return this.ids[(y * this.nz + z) * this.nx + x];
        }
    }

    /** Одна сборка: полный вид или, сверх бюджета, упрощённый. */
    private static final class Builder
    {
        private final Context context;
        private final Job job;
        private final VoxelSnapshot snapshot;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final RandomSource random = RandomSource.create(42L);
        private final List<BlockModelPart> parts = new ArrayList<>();
        private final PoseStack poseStack = new PoseStack();
        private @Nullable ModelBlockRenderer blockRenderer;
        private int failures;

        private Builder(Context context, Job job)
        {
            this.context = context;
            this.job = job;
            this.snapshot = context.snapshot;
        }

        PreviewModel build()
        {
            byte[] flags = this.flags();
            int top = Math.min(this.job.cut, this.snapshot.sizeY() - 1);
            boolean cutActive = this.job.cut < this.snapshot.sizeY() - 1;

            if (this.snapshot.nonAir() == 0L)
            {
                return this.empty(PreviewModel.Empty.NO_BLOCKS);
            }

            if (top < 0)
            {
                return this.empty(PreviewModel.Empty.BELOW_CUT);
            }

            PreviewModel full = this.full(flags, top, cutActive);

            if (this.failures > 0)
            {
                LOGGER.warn("Preview: {} block(s) failed to tessellate and were skipped", this.failures);
            }

            return full != null ? full : this.simplified(flags, top, cutActive);
        }

        private PreviewModel empty(PreviewModel.Empty reason)
        {
            return new PreviewModel(this.snapshot, List.of(), null, 1, 0, this.job.cut, this.job.budget, reason);
        }

        // ---- признаки палитры ----

        private byte[] flags()
        {
            byte[] known = this.context.flags;

            if (known != null)
            {
                return known;
            }

            byte[] flags = new byte[this.snapshot.paletteSize()];

            for (int id = 0; id < flags.length; ++id)
            {
                flags[id] = this.flagsOf(this.snapshot.palette(id));
            }

            this.context.flags = flags;

            return flags;
        }

        private byte flagsOf(BlockState state)
        {
            if (state.isAir())
            {
                return F_AIR;
            }

            byte flags = 0;

            try
            {
                if (state.isSolidRender())
                {
                    flags |= F_OPAQUE;
                }

                if (! state.getFluidState().isEmpty())
                {
                    flags |= F_FLUID;
                }

                if (state.getRenderShape() == RenderShape.MODEL)
                {
                    flags |= F_MODEL;
                }

                // 1.21.11: «листва без прозрачности» решает сам ItemBlockRenderTypes (слой блока), отдельного признака нет

                if (this.isSpecial(state))
                {
                    flags |= F_SPECIAL;
                }
            }
            catch (RuntimeException e)
            {
                flags |= F_MODEL;
            }

            return flags;
        }

        /**
         * Особый блок: в JSON-модели одна частица (сундук, шалкер, флаг, голова, ваза…), а вид даёт
         * «особая» модель набора блоков. Такие рисуем при показе через {@code BlockModelRenderState}.
         * Блоки, у которых есть и обычная геометрия (стол зачарований, колокол), тесселируются как
         * обычно, их движущаяся часть в превью не попадает.
         */
        private boolean isSpecial(BlockState state)
        {
            // 1.21.11: особый вид есть только у блоков с блок-сущностью (сундук, шалкер, флаг, голова, ваза)
            if (! state.hasBlockEntity())
            {
                return false;
            }

            this.parts.clear();
            this.random.setSeed(42L);
            this.context.models.getBlockModel(state).collectParts(this.random, this.parts);

            try
            {
                for (BlockModelPart part : this.parts)
                {
                    if (! part.getQuads(null).isEmpty())
                    {
                        return false;
                    }

                    for (Direction direction : Direction.values())
                    {
                        if (! part.getQuads(direction).isEmpty())
                        {
                            return false;
                        }
                    }
                }

                return true;
            }
            finally
            {
                this.parts.clear();
            }
        }

        // ---- полный вид ----

        private @Nullable PreviewModel full(byte[] flags, int top, boolean cutActive)
        {
            int sectionsX = ceilDiv(this.snapshot.sizeX(), SECTION);
            int sectionsZ = ceilDiv(this.snapshot.sizeZ(), SECTION);
            int rows = (top >> 4) + 1;
            long total = (long) rows * sectionsX * sectionsZ;
            long done = 0L;
            long quads = 0L;
            int specials = 0;
            List<PreviewModel.Section> visible = new ArrayList<>();

            // Срез сдвинулся — секции прошлого среза больше не годятся
            if (this.context.cutSectionsTop != top)
            {
                this.context.cutSections.clear();
                this.context.cutSectionsTop = top;
            }

            for (int ry = 0; ry < rows; ++ry)
            {
                boolean cutRow = cutActive && ry == rows - 1;

                for (int rz = 0; rz < sectionsZ; ++rz)
                {
                    for (int rx = 0; rx < sectionsX; ++rx)
                    {
                        this.job.checkCancelled();

                        int index = (ry * sectionsZ + rz) * sectionsX + rx;
                        PreviewModel.Section section = cutRow
                                ? this.context.cutSections.get(index)
                                : this.context.fullSections.get(index);

                        if (section == null)
                        {
                            section = this.tessellate(flags, rx, ry, rz, cutRow ? top : Integer.MAX_VALUE);

                            if (cutRow)
                            {
                                this.context.cutSections.put(index, section);
                            }
                            else
                            {
                                this.context.cacheFull(index, section, this.job.budget);
                            }
                        }

                        if (! section.isEmpty())
                        {
                            visible.add(section);
                            quads += section.quads;
                            specials += section.specials.length / 4;
                        }

                        if (quads + (long) Math.min(specials, SPECIAL_CAP) * SPECIAL_QUAD_COST > this.job.budget)
                        {
                            return null;
                        }

                        ++done;
                        this.job.progress = (int) (done * PROGRESS_FULL / Math.max(1L, total));
                    }
                }
            }

            PreviewModel.Empty empty = visible.isEmpty()
                    ? cutActive ? PreviewModel.Empty.BELOW_CUT : PreviewModel.Empty.NO_BLOCKS
                    : PreviewModel.Empty.NONE;

            return new PreviewModel(this.snapshot, visible, null, 1, Math.min(specials, SPECIAL_CAP),
                    this.job.cut, this.job.budget, empty);
        }

        private PreviewModel.Section tessellate(byte[] flags, int rx, int ry, int rz, int top)
        {
            int ox = rx * SECTION;
            int oy = ry * SECTION;
            int oz = rz * SECTION;
            int maxX = Math.min(ox + SECTION, this.snapshot.sizeX());
            int maxY = Math.min(Math.min(oy + SECTION, this.snapshot.sizeY()), top == Integer.MAX_VALUE ? Integer.MAX_VALUE : top + 1);
            int maxZ = Math.min(oz + SECTION, this.snapshot.sizeZ());
            PreviewBlockView view = new PreviewBlockView(this.snapshot, top, this.context.tint);
            Sink sink = new Sink(this.context.windingSign, ox, oy, oz);
            IntArrayList specials = new IntArrayList();
            ModelBlockRenderer blocks = this.blockRenderer();

            ModelBlockRenderer.enableCaching();

            try
            {
                for (int y = oy; y < maxY; ++y)
                {
                    for (int z = oz; z < maxZ; ++z)
                    {
                        for (int x = ox; x < maxX; ++x)
                        {
                            int id = this.snapshot.idAt(x, y, z);
                            byte f = flags[id];

                            if ((f & F_AIR) != 0 || this.enclosed(flags, x, y, z, top))
                            {
                                continue;
                            }

                            BlockState state = this.snapshot.palette(id);
                            this.pos.set(x, y, z);
                            // Недописанный квад жидкости — ещё прошлого блока, его цвет по прошлому статусу
                            sink.vertices.commit();
                            sink.blocks.commit();
                            sink.status = BuildStatus.statusAt(this.context.status, x, y, z);

                            try
                            {
                                if ((f & F_FLUID) != 0)
                                {
                                    sink.vertices.target(Sink.layerOf(ItemBlockRenderTypes.getRenderLayer(state.getFluidState())));
                                    this.context.dispatcher.renderLiquid(this.pos, view, sink.vertices, state, state.getFluidState());
                                }

                                if ((f & F_SPECIAL) != 0)
                                {
                                    specials.add(x);
                                    specials.add(y);
                                    specials.add(z);
                                    specials.add(id);
                                }
                                else if ((f & F_MODEL) != 0)
                                {
                                    BlockStateModel model = this.context.models.getBlockModel(state);
                                    this.parts.clear();
                                    this.random.setSeed(state.getSeed(this.pos));
                                    model.collectParts(this.random, this.parts);
                                    // 1.21.11: слой — у блока целиком; вершины уже в координатах схемы (сдвиг PoseStack)
                                    sink.blocks.target(Sink.layerOf(ItemBlockRenderTypes.getChunkRenderType(state)));
                                    this.poseStack.pushPose();
                                    this.poseStack.translate(x, y, z);
                                    blocks.tesselateBlock(view, this.parts, state, this.pos, this.poseStack, sink.blocks,
                                            true, OverlayTexture.NO_OVERLAY);
                                    this.poseStack.popPose();
                                    sink.blocks.commit();
                                    this.parts.clear();
                                }
                            }
                            catch (RuntimeException e)
                            {
                                // Один кривой блок (модель мода, странное состояние) не должен ронять всё превью
                                if (this.failures++ == 0)
                                {
                                    LOGGER.warn("Preview: failed to tessellate {} at {} {} {}", state, x, y, z, e);
                                }
                            }
                        }
                    }
                }
            }
            finally
            {
                ModelBlockRenderer.clearCache();
                sink.flush();
            }

            return new PreviewModel.Section(sink.finish(), specials.toIntArray());
        }

        /** Все шесть соседей — полные непрозрачные блоки внутри схемы и ниже среза: блок не виден. */
        private boolean enclosed(byte[] flags, int x, int y, int z, int top)
        {
            return y + 1 <= top
                    && opaque(flags, this.snapshot.idAt(x, y + 1, z))
                    && opaque(flags, this.snapshot.idAt(x, y - 1, z))
                    && opaque(flags, this.snapshot.idAt(x + 1, y, z))
                    && opaque(flags, this.snapshot.idAt(x - 1, y, z))
                    && opaque(flags, this.snapshot.idAt(x, y, z + 1))
                    && opaque(flags, this.snapshot.idAt(x, y, z - 1));
        }

        private static boolean opaque(byte[] flags, int id)
        {
            return (flags[id] & F_OPAQUE) != 0;
        }

        private ModelBlockRenderer blockRenderer()
        {
            if (this.blockRenderer == null)
            {
                // Свой экземпляр на поток сборки: внутри у тесселятора изменяемые буферы
                this.blockRenderer = new ModelBlockRenderer(this.context.colors);
            }

            return this.blockRenderer;
        }

        // ---- упрощённый вид ----

        private PreviewModel simplified(byte[] flags, int top, boolean cutActive)
        {
            LodGrid grid = this.baseGrid(flags);
            int[] cutRow = cutActive ? this.cutRow(flags, grid, top) : null;
            long faces = this.countFaces(grid, top, cutRow);

            while (faces > this.job.budget && grid.factor() < LOD_MAX_FACTOR)
            {
                grid = this.coarser(grid);
                cutRow = cutActive ? this.cutRow(flags, grid, top) : null;
                faces = this.countFaces(grid, top, cutRow);
            }

            int[] colors = this.colors(grid, cutRow);
            PreviewModel.Quads flat = this.emit(grid, top, cutRow, colors);
            PreviewModel.Empty empty = flat.count == 0
                    ? cutActive ? PreviewModel.Empty.BELOW_CUT : PreviewModel.Empty.NO_BLOCKS
                    : PreviewModel.Empty.NONE;

            return new PreviewModel(this.snapshot, List.of(), flat, grid.factor(), 0, this.job.cut, this.job.budget, empty);
        }

        private LodGrid baseGrid(byte[] flags)
        {
            int factor = 2;

            // «Качественно»: упрощённый вид мельче (кубы 2 вместо 4 на огромной схеме) — сетка до 4× больше
            long maxCells = this.job.budget > BUDGET_HIGH ? LOD_MAX_CELLS * 4 : LOD_MAX_CELLS;

            while (factor < LOD_MAX_FACTOR && cells(this.snapshot, factor) > maxCells)
            {
                factor *= 2;
            }

            LodGrid known = this.context.lodGrids.get(factor);

            if (known != null)
            {
                return known;
            }

            int nx = ceilDiv(this.snapshot.sizeX(), factor);
            int ny = ceilDiv(this.snapshot.sizeY(), factor);
            int nz = ceilDiv(this.snapshot.sizeZ(), factor);
            int[] ids = new int[nx * ny * nz];

            for (int cy = 0; cy < ny; ++cy)
            {
                this.job.checkCancelled();

                int yTop = Math.min((cy + 1) * factor, this.snapshot.sizeY()) - 1;

                for (int cz = 0; cz < nz; ++cz)
                {
                    for (int cx = 0; cx < nx; ++cx)
                    {
                        ids[(cy * nz + cz) * nx + cx] = this.scanCell(flags, factor, cx, cy, cz, yTop);
                    }
                }

                this.job.progress = (cy + 1) * PROGRESS_FULL / ny;
            }

            LodGrid grid = new LodGrid(factor, nx, ny, nz, ids);
            this.context.lodGrids.put(factor, grid);

            return grid;
        }

        /** Верхний непустой блок ячейки, сверху вниз; {@code yTop} — верхний учитываемый слой. */
        private int scanCell(byte[] flags, int factor, int cx, int cy, int cz, int yTop)
        {
            int x0 = cx * factor;
            int z0 = cz * factor;
            int x1 = Math.min(x0 + factor, this.snapshot.sizeX());
            int z1 = Math.min(z0 + factor, this.snapshot.sizeZ());
            int y0 = cy * factor;

            for (int y = yTop; y >= y0; --y)
            {
                for (int z = z0; z < z1; ++z)
                {
                    for (int x = x0; x < x1; ++x)
                    {
                        int id = this.snapshot.idAt(x, y, z);

                        if ((flags[id] & F_AIR) == 0)
                        {
                            return id;
                        }
                    }
                }
            }

            return 0;
        }

        /** Вдвое крупнее: из восьми дочерних ячеек берём верхние, сверху виднее. */
        private LodGrid coarser(LodGrid fine)
        {
            int factor = fine.factor() * 2;
            LodGrid known = this.context.lodGrids.get(factor);

            if (known != null)
            {
                return known;
            }

            int nx = ceilDiv(fine.nx(), 2);
            int ny = ceilDiv(fine.ny(), 2);
            int nz = ceilDiv(fine.nz(), 2);
            int[] ids = new int[nx * ny * nz];

            for (int cy = 0; cy < ny; ++cy)
            {
                this.job.checkCancelled();

                for (int cz = 0; cz < nz; ++cz)
                {
                    for (int cx = 0; cx < nx; ++cx)
                    {
                        ids[(cy * nz + cz) * nx + cx] = pickChild(fine, cx, cy, cz);
                    }
                }
            }

            LodGrid grid = new LodGrid(factor, nx, ny, nz, ids);
            this.context.lodGrids.put(factor, grid);

            return grid;
        }

        private static int pickChild(LodGrid fine, int cx, int cy, int cz)
        {
            for (int dy = 1; dy >= 0; --dy)
            {
                int y = cy * 2 + dy;

                if (y >= fine.ny())
                {
                    continue;
                }

                for (int dz = 0; dz < 2; ++dz)
                {
                    int z = cz * 2 + dz;

                    for (int dx = 0; dx < 2 && z < fine.nz(); ++dx)
                    {
                        int x = cx * 2 + dx;

                        if (x < fine.nx())
                        {
                            int id = fine.id(x, y, z);

                            if (id != 0)
                            {
                                return id;
                            }
                        }
                    }
                }
            }

            return 0;
        }

        /** Ряд ячеек, через который проходит срез: пересканировать только слои не выше среза. */
        private int[] cutRow(byte[] flags, LodGrid grid, int top)
        {
            int cy = top / grid.factor();
            int[] row = new int[grid.nx() * grid.nz()];

            for (int cz = 0; cz < grid.nz(); ++cz)
            {
                this.job.checkCancelled();

                for (int cx = 0; cx < grid.nx(); ++cx)
                {
                    row[cz * grid.nx() + cx] = this.scanCell(flags, grid.factor(), cx, cy, cz, top);
                }
            }

            return row;
        }

        private static int cellAt(LodGrid grid, int @Nullable [] cutRow, int cutCy, int x, int y, int z)
        {
            if (x < 0 || z < 0 || y < 0 || x >= grid.nx() || z >= grid.nz() || y > cutCy)
            {
                return 0;
            }

            return cutRow != null && y == cutCy ? cutRow[z * grid.nx() + x] : grid.id(x, y, z);
        }

        private long countFaces(LodGrid grid, int top, int @Nullable [] cutRow)
        {
            int cutCy = Math.min(top / grid.factor(), grid.ny() - 1);
            long faces = 0L;

            for (int cy = 0; cy <= cutCy; ++cy)
            {
                this.job.checkCancelled();

                for (int cz = 0; cz < grid.nz(); ++cz)
                {
                    for (int cx = 0; cx < grid.nx(); ++cx)
                    {
                        if (cellAt(grid, cutRow, cutCy, cx, cy, cz) == 0)
                        {
                            continue;
                        }

                        for (Direction direction : Direction.values())
                        {
                            if (cellAt(grid, cutRow, cutCy, cx + direction.getStepX(), cy + direction.getStepY(),
                                    cz + direction.getStepZ()) == 0)
                            {
                                ++faces;
                            }
                        }
                    }
                }
            }

            return faces;
        }

        /**
         * Средние цвета блоков читаются с клиентского потока (пиксели спрайтов); ждём ответа здесь,
         * проверяя отмену. Клиентский поток сам никогда не ждёт сборку — взаимной блокировки нет.
         */
        private int[] colors(LodGrid grid, int @Nullable [] cutRow)
        {
            int[] known = this.context.lodColors;
            int paletteSize = this.snapshot.paletteSize();
            boolean[] needed = new boolean[paletteSize];
            IntArrayList missing = new IntArrayList();

            for (int id : grid.ids())
            {
                needed[id] = true;
            }

            if (cutRow != null)
            {
                for (int id : cutRow)
                {
                    needed[id] = true;
                }
            }

            for (int id = 1; id < paletteSize; ++id)
            {
                if (needed[id] && (known == null || known[id] == 0))
                {
                    missing.add(id);
                }
            }

            int[] colors = known != null ? known : new int[paletteSize];

            if (! missing.isEmpty())
            {
                int[] ids = missing.toIntArray();
                CompletableFuture<int[]> fresh = Minecraft.getInstance().submit(() -> PreviewColors.compute(this.context, ids));
                int[] computed = this.await(fresh);

                for (int id : ids)
                {
                    colors[id] = computed[id];
                }
            }

            this.context.lodColors = colors;

            return colors;
        }

        private int[] await(CompletableFuture<int[]> future)
        {
            while (true)
            {
                this.job.checkCancelled();

                try
                {
                    return future.get(COLOR_WAIT_MS, TimeUnit.MILLISECONDS);
                }
                catch (TimeoutException e)
                {
                    // Клиентский поток занят кадром — ждём дальше
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new Cancelled();
                }
                catch (ExecutionException e)
                {
                    throw new IllegalStateException("block colours", e.getCause());
                }
            }
        }

        private PreviewModel.Quads emit(LodGrid grid, int top, int @Nullable [] cutRow, int[] colors)
        {
            int factor = grid.factor();
            int cutCy = Math.min(top / factor, grid.ny() - 1);
            PreviewModel.Quads flat = new PreviewModel.Quads(this.context.windingSign, 1024);
            float[] p = new float[12];
            float[] t = new float[8];
            int[] c = new int[4];

            // Одноцветный куб: все вершины смотрят в середину белой текстуры, цвет — в вершинах
            Arrays.fill(t, 0.5F);

            for (int cy = 0; cy <= cutCy; ++cy)
            {
                this.job.checkCancelled();

                for (int cz = 0; cz < grid.nz(); ++cz)
                {
                    for (int cx = 0; cx < grid.nx(); ++cx)
                    {
                        int id = cellAt(grid, cutRow, cutCy, cx, cy, cz);

                        if (id == 0)
                        {
                            continue;
                        }

                        float x0 = cx * factor;
                        float y0 = cy * factor;
                        float z0 = cz * factor;
                        float x1 = Math.min(x0 + factor, this.snapshot.sizeX());
                        float y1 = Math.min(Math.min(y0 + factor, this.snapshot.sizeY()), top + 1.0F);
                        float z1 = Math.min(z0 + factor, this.snapshot.sizeZ());

                        for (Direction direction : Direction.values())
                        {
                            if (cellAt(grid, cutRow, cutCy, cx + direction.getStepX(), cy + direction.getStepY(),
                                    cz + direction.getStepZ()) != 0)
                            {
                                continue;
                            }

                            if (flat.count >= this.job.budget)
                            {
                                // Даже кубы по 32 блока не влезли — показываем, сколько влезло, снизу вверх
                                flat.trim();
                                return flat;
                            }

                            Arrays.fill(c, shade(BuildStatus.tint(this.cellStatus(cx, cy, cz, factor), colors[id]), direction));
                            face(direction, x0, y0, z0, x1, y1, z1, this.context.windingSign, p);
                            flat.put(p, t, c);
                        }
                    }
                }
            }

            flat.trim();

            return flat;
        }

        /** Статус куба упрощённого вида: большинство его блоков (построенных или нет); без готовности — неизвестно. */
        private byte cellStatus(int cx, int cy, int cz, int factor)
        {
            BuildStatus status = this.context.status;

            if (status == null)
            {
                return BuildStatus.UNKNOWN;
            }

            int built = 0;
            int missing = 0;
            int wrong = 0;
            int x1 = Math.min((cx + 1) * factor, this.snapshot.sizeX());
            int y1 = Math.min((cy + 1) * factor, this.snapshot.sizeY());
            int z1 = Math.min((cz + 1) * factor, this.snapshot.sizeZ());

            for (int y = cy * factor; y < y1; ++y)
            {
                for (int z = cz * factor; z < z1; ++z)
                {
                    for (int x = cx * factor; x < x1; ++x)
                    {
                        switch (status.at(x, y, z))
                        {
                            case BuildStatus.BUILT -> ++built;
                            case BuildStatus.MISSING -> ++missing;
                            case BuildStatus.WRONG -> ++wrong;
                            default -> { }
                        }
                    }
                }
            }

            return wrong > built && wrong >= missing ? BuildStatus.WRONG
                    : missing > built ? BuildStatus.MISSING : built > 0 ? BuildStatus.BUILT : BuildStatus.UNKNOWN;
        }

        private static int shade(int argb, Direction direction)
        {
            float k = PreviewBlockView.faceShade(direction);
            int r = Math.round(((argb >> 16) & 0xFF) * k);
            int g = Math.round(((argb >> 8) & 0xFF) * k);
            int b = Math.round((argb & 0xFF) * k);

            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        /**
         * Грань короба: углы против часовой стрелки, если смотреть снаружи (нормаль по правой руке
         * наружу). Если ванильные грани закручены наоборот, переворачиваем порядок так же.
         */
        private static void face(Direction direction, float x0, float y0, float z0, float x1, float y1, float z1,
                int windingSign, float[] p)
        {
            switch (direction)
            {
                case UP -> corners(p, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                case DOWN -> corners(p, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
                case NORTH -> corners(p, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                case SOUTH -> corners(p, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                case WEST -> corners(p, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                default -> corners(p, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            }

            if (windingSign < 0)
            {
                // a b c d → a d c b: та же грань, обход в другую сторону
                swap(p, 1, 3);
            }
        }

        private static void corners(float[] p, float ax, float ay, float az, float bx, float by, float bz,
                float cx, float cy, float cz, float dx, float dy, float dz)
        {
            p[0] = ax;
            p[1] = ay;
            p[2] = az;
            p[3] = bx;
            p[4] = by;
            p[5] = bz;
            p[6] = cx;
            p[7] = cy;
            p[8] = cz;
            p[9] = dx;
            p[10] = dy;
            p[11] = dz;
        }

        private static void swap(float[] p, int a, int b)
        {
            for (int i = 0; i < 3; ++i)
            {
                float keep = p[a * 3 + i];
                p[a * 3 + i] = p[b * 3 + i];
                p[b * 3 + i] = keep;
            }
        }
    }

    private static long cells(VoxelSnapshot snapshot, int factor)
    {
        return (long) ceilDiv(snapshot.sizeX(), factor) * ceilDiv(snapshot.sizeY(), factor) * ceilDiv(snapshot.sizeZ(), factor);
    }

    private static int ceilDiv(int value, int divisor)
    {
        return (value + divisor - 1) / divisor;
    }

    /**
     * Приёмник одной секции. 1.21.11: и блоки ({@code ModelBlockRenderer}), и жидкости ({@code renderLiquid}) пишут
     * вершины по одной в {@link VertexConsumer}; квад собирается из четырёх. У блоков координаты уже в схеме
     * (сдвиг {@code PoseStack}), у жидкостей — внутри секции, к ним прибавляется угол секции.
     */
    private static final class Sink
    {
        private final @Nullable PreviewModel.Quads[] layers = new PreviewModel.Quads[PreviewModel.LAYERS];
        private final int windingSign;
        /** Статус блока, который сейчас тесселируется: его цвета — с поправкой {@link BuildStatus#tint}. */
        byte status;

        final FluidVertices blocks;
        final FluidVertices vertices;

        Sink(int windingSign, int ox, int oy, int oz)
        {
            this.windingSign = windingSign;
            this.blocks = new FluidVertices(0, 0, 0);
            this.vertices = new FluidVertices(ox, oy, oz);
        }

        private PreviewModel.Quads quads(int layer)
        {
            PreviewModel.Quads quads = this.layers[layer];

            if (quads == null)
            {
                quads = new PreviewModel.Quads(this.windingSign, 256);
                this.layers[layer] = quads;
            }

            return quads;
        }

        void flush()
        {
            this.blocks.commit();
            this.vertices.commit();
        }

        @Nullable PreviewModel.Quads[] finish()
        {
            for (PreviewModel.Quads quads : this.layers)
            {
                if (quads != null)
                {
                    quads.trim();
                }
            }

            return this.layers;
        }

        static int layerOf(ChunkSectionLayer layer)
        {
            return switch (layer)
            {
                case SOLID -> PreviewModel.SOLID;
                case TRANSLUCENT -> PreviewModel.TRANSLUCENT;
                default -> PreviewModel.CUTOUT;
            };
        }

        /**
         * Вершины жидкости: {@code FluidRenderer} пишет их по одной через 11-аргументный
         * {@code addVertex} (позиция, цвет, UV, …). Квад собирается из четырёх и уходит в слой при
         * следующей вершине или по {@link #commit()}.
         */
        private final class FluidVertices implements VertexConsumer
        {
            private final float ox;
            private final float oy;
            private final float oz;
            private final float[] vp = new float[12];
            private final float[] vt = new float[8];
            private final int[] vc = new int[4];
            private int layer = PreviewModel.TRANSLUCENT;
            private int n;

            FluidVertices(int ox, int oy, int oz)
            {
                this.ox = ox;
                this.oy = oy;
                this.oz = oz;
            }

            void target(int layer)
            {
                if (layer != this.layer)
                {
                    this.commit();
                    this.layer = layer;
                }
            }

            void commit()
            {
                if (this.n == 4)
                {
                    for (int i = 0; i < 4; ++i)
                    {
                        this.vc[i] = BuildStatus.tint(Sink.this.status, this.vc[i]);
                    }

                    Sink.this.quads(this.layer).put(this.vp, this.vt, this.vc);
                }

                this.n = 0;
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z)
            {
                if (this.n == 4)
                {
                    this.commit();
                }

                int i = this.n++;
                this.vp[i * 3] = x + this.ox;
                this.vp[i * 3 + 1] = y + this.oy;
                this.vp[i * 3 + 2] = z + this.oz;
                this.vt[i * 2] = 0.0F;
                this.vt[i * 2 + 1] = 0.0F;
                this.vc[i] = -1;

                return this;
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a)
            {
                return this.setColor((a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF));
            }

            @Override
            public VertexConsumer setColor(int argb)
            {
                if (this.n > 0)
                {
                    this.vc[this.n - 1] = argb;
                }

                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v)
            {
                if (this.n > 0)
                {
                    this.vt[(this.n - 1) * 2] = u;
                    this.vt[(this.n - 1) * 2 + 1] = v;
                }

                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v)
            {
                return this;
            }

            @Override
            public VertexConsumer setUv2(int u, int v)
            {
                return this;
            }

            @Override
            public VertexConsumer setNormal(float x, float y, float z)
            {
                return this;
            }

            @Override
            public VertexConsumer setLineWidth(float width)
            {
                return this;
            }
        }
    }
}
