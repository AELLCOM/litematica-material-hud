package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Голограммы и опора.
 *
 * <ul>
 *   <li><b>«Скрывать блоки без опоры»</b> ({@code ghostHideUnsupported}): клетка, где блок схемы ещё не стоит и
 *       держаться ему в мире не на чем — подвесная табличка без потолка, светящиеся ягоды и бледный мох без
 *       блока сверху, лиана в воздухе, — голограммой не рисуется. Решает ванильный {@code canSurvive} блока по
 *       миру игрока. Цепочка одного рода (лиана под лианой, верх двери над низом) держится, если держится
 *       её начало: остальные звенья ставятся следом за ним.</li>
 *   <li><b>«Опора с соседнего слоя»</b> ({@code ghostSupportFromLayers}): если блоку видимого слоя не на чем
 *       держаться, а опора — блок схемы на соседнем слое (табличка на слое N, потолок над ней на N+1), эта
 *       клетка показывается и ставится Easy Place, хотя лежит вне слоя: для прицела Litematica она считается
 *       «внутри слоя» ({@code MixinLayerRange}), голограмму дорисовывает сборка чанка.</li>
 *   <li><b>Рамка опоры</b> (запрос 09.10.2026): пока «без опоры» прячет блок, клетка его опоры из схемы, которую
 *       можно поставить прямо сейчас, обводится рамкой — видно, что строить первым (потолок над табличкой, пол
 *       под сталагмитом). Считается вокруг игрока, рисуется в {@link #renderFrames}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class GhostSupport
{
    private static final int RECOMPUTE_GAP_TICKS = 10;
    private static final int PERIODIC_TICKS = 40;
    /** Слой меньше этого пересчитывается и сам по себе раз в пару секунд (сдвиг схемы, зона Zone Focus). */
    private static final long PERIODIC_SCAN_LIMIT = 300_000L;
    /** Больше позиций за раз не перебираем — слой с огромной схемы подождёт, клиент не встанет. */
    private static final long MAX_SCAN = 4_000_000L;
    private static final int MAX_CHAIN = 64;
    /** Сколько {@code canSurvive} можно потратить на одну клетку; не хватило — клетку показываем. */
    private static final int CHECK_BUDGET = 256;

    /** Опоры вне слоя: все клетки и по колоннам чанков; после публикации не меняются. */
    private record Snapshot(LongOpenHashSet cells, Long2ObjectOpenHashMap<long[]> byChunk)
    {
        static final Snapshot EMPTY = new Snapshot(new LongOpenHashSet(), new Long2ObjectOpenHashMap<>());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Рамки опор: клетки, которые держат спрятанные «без опоры» блоки и ставятся сейчас. */
    private static volatile LongOpenHashSet frames = new LongOpenHashSet();
    private static volatile boolean framesDirty = true;
    private static long lastFrames = Long.MIN_VALUE / 2;
    /** Рамки ищутся вокруг игрока: по горизонтали и по высоте, блоков. */
    private static final int FRAME_RADIUS = 24;
    private static final int FRAME_HEIGHT = 16;
    private static final int FRAME_COLOR = 0xFFFFC23D;
    /** Для самопроверки: убрано голограмм «без опоры» и дорисовано опор с последнего {@link #takeStats()}. */
    private static final java.util.concurrent.atomic.AtomicInteger CULLED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger DRAWN = new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static volatile boolean dirty = true;
    private static long tick;
    private static long lastCompute = Long.MIN_VALUE / 2;
    private static long lastScanned;
    private static String lastSignature = "";

    private GhostSupport() {}

    public static boolean hideUnsupported()
    {
        return LayerListConfigs.GHOST_HIDE_UNSUPPORTED.getBooleanValue();
    }

    public static boolean supportFromLayers()
    {
        return LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.getBooleanValue();
    }

    // ===== Устоит ли блок =====

    private static final ThreadLocal<BlockGetter> VIEW = new ThreadLocal<>();

    /**
     * Мир игрока для {@code canSurvive} из рабочего потока сборки чанка. Там есть только срез чанков
     * ({@link BlockGetter}), а {@code canSurvive} просит {@link LevelReader}; блокам для ответа почти всегда
     * хватает блоков вокруг. Чего срез не знает (свет, биом), то бросает исключение, — такой блок считаем
     * держащимся и показываем: лучше лишняя голограмма, чем пропавшая.
     */
    private static final LevelReader READER = (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(),
            new Class<?>[]{LevelReader.class}, (proxy, method, args) ->
            {
                BlockGetter view = VIEW.get();
                int arity = args == null ? 0 : args.length;

                switch (method.getName())
                {
                    case "getBlockState" -> { if (arity == 1) return view.getBlockState((BlockPos) args[0]); }
                    case "getFluidState" -> { if (arity == 1) return view.getFluidState((BlockPos) args[0]); }
                    case "getBlockEntity" -> { if (arity == 1) return view.getBlockEntity((BlockPos) args[0]); }
                    case "getHeight" -> { if (arity == 0) return view.getHeight(); }
                    case "getMinY" -> { if (arity == 0) return view.getMinY(); }
                    case "isClientSide" -> { return true; }
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "equals" -> { return proxy == args[0]; }
                    case "toString" -> { return "layerlist support view"; }
                    default -> { }
                }

                if (method.isDefault())
                {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }

                throw new UnsupportedOperationException(method.getName());
            });

    private static boolean canSurvive(BlockState state, BlockGetter world, BlockPos pos)
    {
        if (world instanceof LevelReader level)
        {
            try
            {
                return state.canSurvive(level, pos);
            }
            catch (RuntimeException | LinkageError e)
            {
                return true;
            }
        }

        BlockGetter previous = VIEW.get();
        VIEW.set(world);

        try
        {
            return state.canSurvive(READER, pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
        }
        finally
        {
            VIEW.set(previous);
        }
    }

    /**
     * Устоит ли блок в мире {@code world} — для опор в списке материалов ({@code LayerSupports}). У подвесной
     * таблички на стене {@code canSurvive} пуст — её опору ваниль проверяет только при установке, — поэтому для
     * неё спрашиваем {@code canPlace}.
     */
    public static boolean survives(BlockState state, BlockGetter world, BlockPos pos)
    {
        if (! (state.getBlock() instanceof net.minecraft.world.level.block.WallHangingSignBlock wall))
        {
            return canSurvive(state, world, pos);
        }

        if (world instanceof LevelReader level)
        {
            try
            {
                return wall.canPlace(state, level, pos);
            }
            catch (RuntimeException | LinkageError e)
            {
                return true;
            }
        }

        BlockGetter previous = VIEW.get();
        VIEW.set(world);

        try
        {
            return wall.canPlace(state, READER, pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
        }
        finally
        {
            VIEW.set(previous);
        }
    }

    /** Мир с одной подставленной клеткой — «как будто опору уже поставили (или убрали)». */
    public static BlockGetter with(BlockGetter world, BlockPos at, BlockState state)
    {
        return new Overlay(world).put(at, state);
    }

    /**
     * Голограмма в клетке не нужна: блок схемы ещё не стоит, клетка свободна, а держаться ему не на чем —
     * ни в мире, ни через цепочку того же рода из схемы. Вызывается из рабочего потока сборки чанка.
     */
    public static boolean unsupported(BlockState schematic, BlockState client, BlockPos pos, BlockGetter world,
            BlockGetter schematicWorld)
    {
        if (schematic.isAir() || client == schematic || ! free(client))
        {
            return false;
        }

        return ! supported(schematic, pos.immutable(), world, schematicWorld, null, MAX_CHAIN, new int[]{CHECK_BUDGET});
    }

    private static boolean free(BlockState state)
    {
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * Держится ли блок: в мире как есть — или на соседе того же рода из схемы (лиана на лиане выше,
     * верх двери на низе), который сам держится. {@code from} — звено, из которого пришли: на него не
     * опираемся, иначе два звена держали бы друг друга.
     */
    private static boolean supported(BlockState state, BlockPos pos, BlockGetter world, BlockGetter schematic,
            @Nullable BlockPos from, int depth, int[] budget)
    {
        if (--budget[0] < 0)
        {
            return true;
        }

        if (canSurvive(state, world, pos))
        {
            return true;
        }

        if (depth <= 0)
        {
            return false;
        }

        Overlay all = null;

        for (Direction side : Direction.values())
        {
            BlockPos near = pos.relative(side);

            if (near.equals(from))
            {
                continue;
            }

            BlockState kin = schematic.getBlockState(near);

            if (kin.isAir() || ! sameKind(state, kin))
            {
                continue;
            }

            BlockState have = world.getBlockState(near);

            if (have == kin || ! free(have))
            {
                continue;
            }

            if (canSurvive(state, new Overlay(world).put(near, kin), pos)
                    && supported(kin, near, world, schematic, pos, depth - 1, budget))
            {
                return true;
            }

            all = (all == null ? new Overlay(world) : all).put(near, kin);
        }

        // Блоку нужны сразу несколько звеньев (стебель большого капельника: снизу и сверху)
        if (all == null || all.size() < 2 || ! canSurvive(state, all, pos))
        {
            return false;
        }

        for (Long2ObjectMap.Entry<BlockState> entry : all.put.long2ObjectEntrySet())
        {
            if (! supported(entry.getValue(), BlockPos.of(entry.getLongKey()), world, schematic, pos, depth - 1, budget))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean sameKind(BlockState a, BlockState b)
    {
        Block one = a.getBlock();
        Block two = b.getBlock();

        return one == two
                || (one instanceof GrowingPlantBlock && two instanceof GrowingPlantBlock)
                || ((one instanceof BigDripleafBlock || one instanceof BigDripleafStemBlock)
                        && (two instanceof BigDripleafBlock || two instanceof BigDripleafStemBlock));
    }

    /** Мир с подставленными блоками схемы — «как будто уже поставили». */
    private static final class Overlay implements BlockGetter
    {
        private final BlockGetter base;
        private final Long2ObjectArrayMap<BlockState> put = new Long2ObjectArrayMap<>(4);

        Overlay(BlockGetter base)
        {
            this.base = base;
        }

        Overlay put(BlockPos pos, BlockState state)
        {
            this.put.put(pos.asLong(), state);
            return this;
        }

        int size()
        {
            return this.put.size();
        }

        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            BlockState state = this.put.get(pos.asLong());
            return state != null ? state : this.base.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos)
        {
            BlockState state = this.put.get(pos.asLong());
            return state != null ? state.getFluidState() : this.base.getFluidState(pos);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos)
        {
            return this.put.containsKey(pos.asLong()) ? null : this.base.getBlockEntity(pos);
        }

        @Override
        public int getHeight()
        {
            return this.base.getHeight();
        }

        @Override
        public int getMinY()
        {
            return this.base.getMinY();
        }
    }

    // ===== Опоры с соседнего слоя =====

    /** Для {@code MixinLayerRange}: клетка — опора вне слоя, Easy Place и прицелу её считать «внутри». */
    public static boolean isSupportCell(int x, int y, int z)
    {
        Snapshot current = snapshot;
        return ! current.cells().isEmpty() && supportFromLayers() && ! BYPASS.get()
                && current.cells().contains(BlockPos.asLong(x, y, z));
    }

    /** Опоры в колонне чанка — сборка чанка дорисует их после основного прохода. */
    public static long[] cellsInChunk(int chunkX, int chunkZ)
    {
        long[] cells = supportFromLayers() ? snapshot.byChunk().get(chunkKey(chunkX, chunkZ)) : null;
        return cells == null ? new long[0] : cells;
    }

    /** Проверка «внутри слоя» без опор — чтобы не дорисовать клетку, которую Litematica и так рисует. */
    public static boolean rawWithinRange(LayerRange range, int x, int y, int z)
    {
        BYPASS.set(true);

        try
        {
            return range.isPositionWithinRange(x, y, z);
        }
        finally
        {
            BYPASS.set(false);
        }
    }

    public static void markDirty()
    {
        dirty = true;
    }

    public static void countCulled()
    {
        CULLED.incrementAndGet();
    }

    public static void countDrawn()
    {
        DRAWN.incrementAndGet();
    }

    /** {убрано без опоры, дорисовано опор} с прошлого вызова. */
    public static int[] takeStats()
    {
        return new int[]{CULLED.getAndSet(0), DRAWN.getAndSet(0)};
    }

    /** Опоры вне слоя сейчас — для самопроверки. */
    public static int supportCount()
    {
        return snapshot.cells().size();
    }

    public static void onClientTick(Minecraft mc)
    {
        ++tick;
        tickFrames(mc);
        boolean on = supportFromLayers() && mc.level != null && mc.player != null
                && DataManager.getRenderLayerRange().getLayerMode() != LayerMode.ALL;

        if (! on)
        {
            lastSignature = "";
            publish(Snapshot.EMPTY);
            return;
        }

        String signature = layerSignature();

        if (! signature.equals(lastSignature))
        {
            lastSignature = signature;
            dirty = true;
        }

        if (! dirty && tick - lastCompute >= PERIODIC_TICKS && lastScanned < PERIODIC_SCAN_LIMIT)
        {
            dirty = true;
        }

        if (! dirty || tick - lastCompute < RECOMPUTE_GAP_TICKS)
        {
            return;
        }

        dirty = false;
        lastCompute = tick;
        publish(compute(mc.level));
    }

    private static String layerSignature()
    {
        LayerRange range = DataManager.getRenderLayerRange();
        StringBuilder signature = new StringBuilder().append(range.getLayerMode()).append(':').append(range.getAxis())
                .append(':').append(range.getLayerSingle()).append(':').append(range.getLayerAbove())
                .append(':').append(range.getLayerBelow()).append(':').append(range.getLayerRangeMin())
                .append(':').append(range.getLayerRangeMax());

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            signature.append('|').append(placement.getOrigin()).append(placement.getRotation())
                    .append(placement.getMirror()).append(placement.isEnabled()).append(placement.isRenderingEnabled());
        }

        return signature.toString();
    }

    private static Snapshot compute(ClientLevel level)
    {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        lastScanned = 0L;

        if (schematicWorld == null)
        {
            return Snapshot.EMPTY;
        }

        LayerRange range = DataManager.getRenderLayerRange();
        Direction up = Direction.get(Direction.AxisDirection.POSITIVE, range.getAxis());
        Direction[] sides = {up, up.getOpposite()};
        LongOpenHashSet cells = new LongOpenHashSet();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long budget = MAX_SCAN;

        BYPASS.set(true);

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                if (! placement.isEnabled() || ! placement.isRenderingEnabled())
                {
                    continue;
                }

                for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
                {
                    if (box.getPos1() == null || box.getPos2() == null)
                    {
                        continue;
                    }

                    // Обрезка слоем — та же, что у рендера Litematica (и зона Zone Focus, если обрезает она)
                    IntBoundingBox clamped = range.getClampedRenderBoundingBox(IntBoundingBox.createProper(
                            box.getPos1().getX(), box.getPos1().getY(), box.getPos1().getZ(),
                            box.getPos2().getX(), box.getPos2().getY(), box.getPos2().getZ()));

                    if (clamped == null)
                    {
                        continue;
                    }

                    for (int x = clamped.minX(); x <= clamped.maxX() && budget > 0; ++x)
                    {
                        for (int y = clamped.minY(); y <= clamped.maxY(); ++y)
                        {
                            for (int z = clamped.minZ(); z <= clamped.maxZ(); ++z)
                            {
                                --budget;
                                pos.set(x, y, z);
                                BlockState wanted = schematicWorld.getBlockState(pos);

                                if (wanted.isAir())
                                {
                                    continue;
                                }

                                BlockState have = level.getBlockState(pos);

                                if (! unsupported(wanted, have, pos, level, schematicWorld))
                                {
                                    continue;
                                }

                                BlockPos cell = pos.immutable();

                                for (Direction side : sides)
                                {
                                    BlockPos near = cell.relative(side);

                                    if (range.isPositionWithinRange(near))
                                    {
                                        continue;
                                    }

                                    BlockState support = schematicWorld.getBlockState(near);
                                    BlockState there = level.getBlockState(near);

                                    // Опора — только если с ней блок и правда устоит
                                    if (! support.isAir() && there != support && free(there)
                                            && supported(wanted, cell, new Overlay(level).put(near, support),
                                                    schematicWorld, null, MAX_CHAIN, new int[]{CHECK_BUDGET}))
                                    {
                                        cells.add(near.asLong());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            BYPASS.set(false);
            lastScanned = MAX_SCAN - budget;
        }

        if (cells.isEmpty())
        {
            return Snapshot.EMPTY;
        }

        Long2ObjectOpenHashMap<LongArrayList> grouped = new Long2ObjectOpenHashMap<>();

        for (long cell : cells)
        {
            grouped.computeIfAbsent(chunkKey(BlockPos.getX(cell) >> 4, BlockPos.getZ(cell) >> 4),
                    k -> new LongArrayList()).add(cell);
        }

        Long2ObjectOpenHashMap<long[]> byChunk = new Long2ObjectOpenHashMap<>();

        for (Long2ObjectMap.Entry<LongArrayList> entry : grouped.long2ObjectEntrySet())
        {
            long[] sorted = entry.getValue().toLongArray();
            Arrays.sort(sorted);
            byChunk.put(entry.getLongKey(), sorted);
        }

        return new Snapshot(cells, byChunk);
    }

    /** Опубликовать новый набор опор и пересобрать чанки, где набор изменился. */
    private static void publish(Snapshot next)
    {
        Snapshot previous = snapshot;

        if (previous == next || previous.cells().equals(next.cells()))
        {
            return;
        }

        snapshot = next;
        // Список материалов опоры считает сам, по схеме (LayerSupports) — здесь только голограммы и Easy Place
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (world == null)
        {
            return;
        }

        LongOpenHashSet chunks = new LongOpenHashSet(previous.byChunk().keySet());
        chunks.addAll(next.byChunk().keySet());

        for (long key : chunks)
        {
            if (! Arrays.equals(previous.byChunk().get(key), next.byChunk().get(key)))
            {
                world.scheduleChunkRenders((int) (key >> 32), (int) key);
            }
        }
    }

    // ===== Рамки опор =====

    private static void tickFrames(Minecraft mc)
    {
        if (! hideUnsupported() || mc.level == null || mc.player == null)
        {
            if (! frames.isEmpty())
            {
                frames = new LongOpenHashSet();
            }

            framesDirty = true;
            return;
        }

        // Смена мира, сдвиг игрока и стройка помечают «пересчитать»; сами по себе — раз в пару секунд
        if (! framesDirty && tick - lastFrames < PERIODIC_TICKS || tick - lastFrames < RECOMPUTE_GAP_TICKS)
        {
            return;
        }

        framesDirty = false;
        lastFrames = tick;
        frames = computeFrames(mc.level, mc.player.blockPosition());
    }

    /**
     * Клетки опор вокруг игрока: блок схемы прячется «без опоры», а его опора — блок схемы, которого ещё нет,
     * который сам устоит и с которым спрятанный устоит. Звено той же цепочки (лоза над лозой) — не опора: рамку
     * получает то, на чём держится начало цепочки. При «только блоки из инвентаря» — только для блоков, которые
     * есть у игрока: без предмета строить опору незачем.
     */
    private static LongOpenHashSet computeFrames(ClientLevel level, BlockPos center)
    {
        LongOpenHashSet out = new LongOpenHashSet();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (schematicWorld == null)
        {
            return out;
        }

        LayerRange range = DataManager.getRenderLayerRange();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (! placement.isEnabled() || ! placement.isRenderingEnabled())
            {
                continue;
            }

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                if (box.getPos1() == null || box.getPos2() == null)
                {
                    continue;
                }

                IntBoundingBox clamped = range.getClampedRenderBoundingBox(IntBoundingBox.createProper(
                        box.getPos1().getX(), box.getPos1().getY(), box.getPos1().getZ(),
                        box.getPos2().getX(), box.getPos2().getY(), box.getPos2().getZ()));

                if (clamped == null)
                {
                    continue;
                }

                int minX = Math.max(clamped.minX(), center.getX() - FRAME_RADIUS);
                int maxX = Math.min(clamped.maxX(), center.getX() + FRAME_RADIUS);
                int minY = Math.max(clamped.minY(), center.getY() - FRAME_HEIGHT);
                int maxY = Math.min(clamped.maxY(), center.getY() + FRAME_HEIGHT);
                int minZ = Math.max(clamped.minZ(), center.getZ() - FRAME_RADIUS);
                int maxZ = Math.min(clamped.maxZ(), center.getZ() + FRAME_RADIUS);

                for (int x = minX; x <= maxX; ++x)
                {
                    for (int y = minY; y <= maxY; ++y)
                    {
                        for (int z = minZ; z <= maxZ; ++z)
                        {
                            pos.set(x, y, z);
                            BlockState wanted = schematicWorld.getBlockState(pos);

                            if (wanted.isAir() || ! unsupported(wanted, level.getBlockState(pos), pos, level, schematicWorld)
                                    || (InventoryGhosts.active() && ! InventoryGhosts.available(wanted)))
                            {
                                continue;
                            }

                            addFrames(out, level, schematicWorld, range, pos.immutable(), wanted);
                        }
                    }
                }
            }
        }

        return out;
    }

    private static void addFrames(LongOpenHashSet out, ClientLevel level, WorldSchematic schematicWorld, LayerRange range,
            BlockPos cell, BlockState wanted)
    {
        for (Direction side : Direction.values())
        {
            BlockPos near = cell.relative(side);
            BlockState support = schematicWorld.getBlockState(near);
            BlockState there = level.getBlockState(near);

            if (support.isAir() || there == support || ! free(there) || sameKind(wanted, support)
                    || out.contains(near.asLong()))
            {
                continue;
            }

            // Ставится сейчас: внутри слоя (или опора с соседнего слоя) и сама держится в мире
            if (! range.isPositionWithinRange(near) || unsupported(support, there, near, level, schematicWorld))
            {
                continue;
            }

            if (supported(wanted, cell, new Overlay(level).put(near, support), schematicWorld, null, MAX_CHAIN,
                    new int[]{CHECK_BUDGET}))
            {
                out.add(near.asLong());
            }
        }
    }

    /** Рамки опор — из {@code LevelRenderEvents.BEFORE_GIZMOS}. */
    public static void renderFrames()
    {
        LongOpenHashSet cells = frames;

        if (cells.isEmpty() || ! hideUnsupported())
        {
            return;
        }

        net.minecraft.gizmos.GizmoStyle style = net.minecraft.gizmos.GizmoStyle.strokeAndFill(FRAME_COLOR, 2.5f,
                (0x30 << 24) | (FRAME_COLOR & 0xFFFFFF));

        for (long cell : cells)
        {
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            net.minecraft.gizmos.Gizmos.cuboid(new net.minecraft.world.phys.AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0)
                    .inflate(0.004), style);
        }
    }

    /** Рамки опор сейчас — для самопроверки. */
    public static long[] frameCells()
    {
        return frames.toLongArray();
    }

    private static long chunkKey(int chunkX, int chunkZ)
    {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Блок в мире сменился: опоры пересчитать; при «скрывать без опоры» — пересобрать и соседние чанки. */
    public static void onBlockChanged(BlockPos pos)
    {
        dirty = true;
        framesDirty = true;

        if (! hideUnsupported())
        {
            return;
        }

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (world == null)
        {
            return;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        Minecraft mc = Minecraft.getInstance();

        // Litematica пересобирает только чанк самой клетки; опора на границе держит блок в соседнем.
        // Пересборка гасит чанк на несколько кадров, поэтому соседа трогаем, только если за границей
        // в схеме есть блок, который ещё не стоит, — рост посевов, вода и чужие постройки на сервере
        // иначе мигали бы соседним чанком
        for (Direction side : Direction.Plane.HORIZONTAL)
        {
            BlockPos next = pos.relative(side);
            int nx = next.getX() >> 4;
            int nz = next.getZ() >> 4;

            if ((nx != chunkX || nz != chunkZ) && world.getChunkSource().hasChunk(nx, nz)
                    && ! world.getBlockState(next).isAir()
                    && (mc.level == null || free(mc.level.getBlockState(next))))
            {
                world.scheduleChunkRenders(nx, nz);
            }
        }
    }
}
