package com.aell.layerlist.dev;

import com.aell.layerlist.ghost.GhostSupport;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code supportmatrix} (24.11.2026): все сочетания «блок + опора» ванильной игры и проверка на них правила
 * списка HUD «Скрывать блоки без опоры». Считает по реестру блоков тем же {@link GhostSupport#survives}, каким
 * решает мод; в мире ничего не строит, кроме шага с предметами (ставит каждый предмет-блок в воздухе и убирает).
 *
 * <ul>
 *   <li>{@code support-blocks.csv} — каждый блок: сколько состояний ставится само, сколько упадёт, скольким нужна
 *       опора и с какой стороны; ставится ли его предмет в воздухе настоящим {@code BlockItem.place};</li>
 *   <li>{@code support-pairs.csv} — пары «блок, сторона, опора», где опора сама требует опоры (или упадёт);</li>
 *   <li>{@code support-families.csv} — все пары по классам блоков;</li>
 *   <li>{@code support-mutual.csv} — пары, где блок и опора держат друг друга;</li>
 *   <li>{@code support-check.csv} — расхождения вердикта {@link GhostSupport#stand} с ожидаемым.</li>
 * </ul>
 *
 * <p>Правило владельца (24.11.2026): блок слоя, чья опора лежит на соседнем слое, в списке есть, только если эта
 * опора ставится сама (цепь над фонарём); если опоре самой нужна опора (подвесная табличка над табличкой) — нет.
 */
@Environment(EnvType.CLIENT)
public final class DumpSupportMatrix implements CovJob
{
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockPos P = new BlockPos(0, 64, 0);
    private static final Direction[] SIDES = Direction.values();
    private static final int SELF = 0;
    private static final int FALLS = 1;
    private static final int NEEDS = 2;
    private static final int MUTUAL = 3;
    private static final String[] KIND = {"SELF", "FALLS", "NEEDS", "MUTUAL"};
    private static final float[][] LOOKS = {{0F, 0F}, {90F, 0F}, {180F, 0F}, {270F, 0F}, {0F, 90F}, {0F, -90F}};
    private static final int CLEAR = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int MAX_CHECK_ROWS = 4000;

    private final List<Block> blocks = new ArrayList<>();
    private final List<BlockState> states = new ArrayList<>();
    private final Object2IntOpenHashMap<Block> blockIndex = new Object2IntOpenHashMap<>();
    private int[] stateBlock;
    private byte[] cat;
    private byte[] sideMask;
    private boolean[] held;
    private boolean[] realDiffers;
    /** Для состояния, которому нужна опора: первая найденная опора, которая ставится сама, по каждой стороне. */
    private int[][] selfSupport;
    private final ConcurrentHashMap<Long, Pair> pairs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String[]> checkRows = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String[]> mutualRows = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, AtomicInteger[]> checkStats = new ConcurrentHashMap<>();
    /** Сколько пар показало бы в списке правило до 24.11.2026 (опора ищется от слоя сколько угодно далеко). */
    private final ConcurrentHashMap<String, AtomicInteger[]> oldStats = new ConcurrentHashMap<>();
    private final AtomicLong probes = new AtomicLong();
    private final AtomicBoolean computed = new AtomicBoolean();
    private final AtomicBoolean placed = new AtomicBoolean();
    private final Map<Item, String> airPlaced = new ConcurrentHashMap<>();
    private volatile String failure;
    private boolean written;

    @Override
    public String name()
    {
        return "supportmatrix (все сочетания «блок + опора»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null || mc.level == null)
        {
            return false;
        }

        this.blockIndex.defaultReturnValue(-1);

        for (Block block : BuiltInRegistries.BLOCK)
        {
            boolean any = false;

            for (BlockState state : block.getStateDefinition().getPossibleStates())
            {
                // Вода в клетке самого блока на опору не влияет — половина состояний отпадает
                if (state.isAir() || (state.hasProperty(BlockStateProperties.WATERLOGGED)
                        && state.getValue(BlockStateProperties.WATERLOGGED)))
                {
                    continue;
                }

                if (! any)
                {
                    this.blockIndex.put(block, this.blocks.size());
                    this.blocks.add(block);
                    any = true;
                }

                this.states.add(state);
            }
        }

        int count = this.states.size();
        this.stateBlock = new int[count];
        this.cat = new byte[count];
        this.sideMask = new byte[count];
        this.held = new boolean[count];
        this.realDiffers = new boolean[count];
        this.selfSupport = new int[count][];

        for (int i = 0; i < count; ++i)
        {
            this.stateBlock[i] = this.blockIndex.getInt(this.states.get(i).getBlock());
        }

        CovOut.log("[supportmatrix] блоков " + this.blocks.size() + ", состояний (без воды в клетке) " + count);

        // Сверка с настоящим миром: клетка высоко в воздухе над игроком, вокруг пусто
        BlockPos sky = skyCell(mc);
        Cells empty = new Cells();
        int differ = 0;

        for (int i = 0; i < count; ++i)
        {
            BlockState state = this.states.get(i);
            boolean ours = GhostSupport.survives(state, empty, P);
            boolean real;

            try
            {
                real = state.canSurvive(mc.level, sky);
            }
            catch (RuntimeException | LinkageError e)
            {
                real = ours;
            }

            if (real != ours)
            {
                this.realDiffers[i] = true;
                ++differ;
            }
        }

        CovOut.log("[supportmatrix] ответ мода и canSurvive в настоящем мире расходятся у состояний: " + differ);

        this.placeItems(mc, server, sky);

        Thread worker = new Thread(this::compute, "supportmatrix");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static BlockPos skyCell(Minecraft mc)
    {
        BlockPos base = mc.player.blockPosition();
        return new BlockPos(base.getX(), Math.min(mc.level.getMaxY() - 8, base.getY() + 70), base.getZ());
    }

    // ===== Предметы: ставится ли блок в воздухе =====

    private void placeItems(Minecraft mc, MinecraftServer server, BlockPos cell)
    {
        java.util.UUID id = mc.player.getUUID();

        server.execute(() ->
        {
            try
            {
                ServerPlayer player = server.getPlayerList().getPlayer(id);

                if (player == null)
                {
                    this.failure = "нет игрока на сервере";
                    return;
                }

                ServerLevel level = (ServerLevel) player.level();
                float yaw = player.getYRot();
                float pitch = player.getXRot();
                clear(level, cell);
                int ok = 0;
                int all = 0;

                for (Item item : BuiltInRegistries.ITEM)
                {
                    if (! (item instanceof BlockItem blockItem))
                    {
                        continue;
                    }

                    ++all;
                    String result = "";

                    attempts:
                    for (Direction face : SIDES)
                    {
                        for (float[] look : LOOKS)
                        {
                            player.setYRot(look[0]);
                            player.setXRot(look[1]);
                            Vec3 hit = Vec3.atCenterOf(cell).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D,
                                    face.getStepZ() * 0.5D);

                            try
                            {
                                blockItem.place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(item),
                                        new BlockHitResult(hit, face, cell, false)));
                            }
                            catch (RuntimeException | LinkageError e)
                            {
                                result = "ошибка: " + e.getClass().getSimpleName();
                            }

                            BlockState now = level.getBlockState(cell);

                            if (! now.isAir())
                            {
                                result = now.toString();
                                clear(level, cell);
                                break attempts;
                            }

                            clear(level, cell);
                        }
                    }

                    if (! result.isEmpty() && ! result.startsWith("ошибка"))
                    {
                        ++ok;
                    }

                    this.airPlaced.put(item, result);
                }

                player.setYRot(yaw);
                player.setXRot(pitch);
                CovOut.log("[supportmatrix] предметов-блоков " + all + ", в воздухе поставилось " + ok);
            }
            catch (RuntimeException | LinkageError e)
            {
                this.failure = "шаг с предметами: " + e;
            }
            finally
            {
                this.placed.set(true);
            }
        });
    }

    /** Убрать всё, что поставилось вокруг клетки (двери и кровати занимают две). */
    private static void clear(ServerLevel level, BlockPos cell)
    {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        for (int dx = -2; dx <= 2; ++dx)
        {
            for (int dy = -2; dy <= 2; ++dy)
            {
                for (int dz = -2; dz <= 2; ++dz)
                {
                    at.set(cell.getX() + dx, cell.getY() + dy, cell.getZ() + dz);

                    if (! level.getBlockState(at).isAir())
                    {
                        level.setBlock(at, AIR, CLEAR);
                    }
                }
            }
        }
    }

    // ===== Счёт по реестру =====

    private void compute()
    {
        try
        {
            long started = System.nanoTime();
            int count = this.states.size();
            Cells empty = new Cells();

            // 1. Само, упадёт или нужна опора; каких соседей блок вообще спрашивает
            IntStream.range(0, count).parallel().forEach(i ->
            {
                BlockState state = this.states.get(i);

                if (! GhostSupport.survives(state, empty, P))
                {
                    this.cat[i] = NEEDS;
                    Cells recorder = new Cells();
                    recorder.reads = new LongOpenHashSet();
                    GhostSupport.survives(state, recorder, P);
                    byte mask = 0;

                    for (Direction side : SIDES)
                    {
                        if (recorder.reads.contains(P.relative(side).asLong()))
                        {
                            mask |= (byte) (1 << side.ordinal());
                        }
                    }

                    this.sideMask[i] = mask;
                }
                else if (GhostSupport.falls(state, empty, P))
                {
                    this.cat[i] = FALLS;
                    this.sideMask[i] = (byte) (1 << Direction.DOWN.ordinal());
                }
                else
                {
                    this.cat[i] = SELF;
                }
            });

            int[] byCat = new int[3];

            for (int i = 0; i < count; ++i)
            {
                ++byCat[this.cat[i]];
            }

            CovOut.log("[supportmatrix] состояний: ставится само " + byCat[SELF] + ", упадёт без блока снизу " + byCat[FALLS]
                    + ", нужна опора " + byCat[NEEDS]);

            // 2. Все пары «блок — сторона — опора»
            int blockCount = this.blocks.size();

            IntStream.range(0, count).parallel().forEach(d ->
            {
                if (this.cat[d] == SELF)
                {
                    return;
                }

                BlockState dependent = this.states.get(d);
                boolean falling = this.cat[d] == FALLS;
                Cells world = new Cells();
                Cells back = new Cells();
                int[] firstSelf = new int[6];
                Arrays.fill(firstSelf, -1);
                long done = 0L;

                for (Direction side : SIDES)
                {
                    if ((this.sideMask[d] & (1 << side.ordinal())) == 0)
                    {
                        continue;
                    }

                    BlockPos near = P.relative(side);
                    Int2ObjectOpenHashMap<int[]> local = new Int2ObjectOpenHashMap<>();

                    for (int s = 0; s < count; ++s)
                    {
                        BlockState support = this.states.get(s);
                        ++done;
                        boolean holds = falling ? ! FallingBlock.isFree(support)
                                : GhostSupport.survives(dependent, world.one(near, support), P);

                        if (! holds)
                        {
                            continue;
                        }

                        this.held[d] = true;
                        int kind = this.cat[s];

                        // Опора держится только на самом блоке: стебель и лист большого капельника
                        if (kind == NEEDS && GhostSupport.survives(support, back.one(P, dependent), near))
                        {
                            kind = MUTUAL;
                        }
                        else if (kind == FALLS && side == Direction.UP && ! FallingBlock.isFree(dependent))
                        {
                            kind = MUTUAL;
                        }

                        if (kind == SELF && firstSelf[side.ordinal()] < 0)
                        {
                            firstSelf[side.ordinal()] = s;
                        }

                        int[] row = local.get(this.stateBlock[s]);

                        if (row == null)
                        {
                            row = new int[]{0, 0, 0, 0, -1, -1, -1, -1};
                            local.put(this.stateBlock[s], row);
                        }

                        ++row[kind];

                        if (row[4 + kind] < 0)
                        {
                            row[4 + kind] = s;
                        }
                    }

                    long head = ((long) this.stateBlock[d] * 6L + side.ordinal()) * blockCount;

                    for (Int2ObjectOpenHashMap.Entry<int[]> entry : local.int2ObjectEntrySet())
                    {
                        this.pairs.computeIfAbsent(head + entry.getIntKey(), key -> new Pair()).add(d, entry.getValue());
                    }
                }

                this.selfSupport[d] = firstSelf;
                this.probes.addAndGet(done);
            });

            CovOut.log(String.format(Locale.ROOT, "[supportmatrix] проверено сочетаний %d, пар блоков %d, %.1f с",
                    this.probes.get(), this.pairs.size(), (System.nanoTime() - started) / 1e9D));

            // 3. Правило списка HUD на каждой паре
            this.verify();
            CovOut.log(String.format(Locale.ROOT, "[supportmatrix] счёт и проверка: %.1f с", (System.nanoTime() - started) / 1e9D));
        }
        catch (RuntimeException | LinkageError e)
        {
            this.failure = "счёт: " + e;
            e.printStackTrace();
        }
        finally
        {
            this.computed.set(true);
        }
    }

    // ===== Проверка правила списка =====

    private void verify()
    {
        IRangeChangeListener quiet = new IRangeChangeListener()
        {
            @Override
            public void updateAll() {}

            @Override
            public void updateBetweenX(int from, int to) {}

            @Override
            public void updateBetweenY(int from, int to) {}

            @Override
            public void updateBetweenZ(int from, int to) {}
        };
        GhostSupport.Reach[] on = new GhostSupport.Reach[3];
        GhostSupport.Reach[] off = new GhostSupport.Reach[3];
        GhostSupport.Reach[] whole = new GhostSupport.Reach[3];
        GhostSupport.Reach[] before = new GhostSupport.Reach[3];

        for (Direction.Axis axis : Direction.Axis.values())
        {
            LayerRange range = new LayerRange(quiet);
            range.setLayerMode(LayerMode.SINGLE_LAYER);
            range.setAxis(axis);
            range.setLayerSingle(axis.choose(P.getX(), P.getY(), P.getZ()));
            on[axis.ordinal()] = GhostSupport.layerReach(range, false, axis, null);
            off[axis.ordinal()] = GhostSupport.layerReach(range, false, null, null);
            whole[axis.ordinal()] = GhostSupport.layerReach(range, true, null, null);
            // Как было до 24.11.2026: вне слоя годилась любая клетка, до которой шли вдоль оси слоёв
            before[axis.ordinal()] = (near, side) -> GhostSupport.rawWithinRange(range, near.getX(), near.getY(), near.getZ())
                    || side.getAxis() == axis;
        }

        int blockCount = this.blocks.size();
        List<Map.Entry<Long, Pair>> entries = new ArrayList<>(this.pairs.entrySet());

        entries.parallelStream().forEach(entry ->
        {
            Direction side = SIDES[(int) ((entry.getKey() / blockCount) % 6L)];
            Pair pair = entry.getValue();

            for (int kind = 0; kind < 4; ++kind)
            {
                if (pair.support[kind] >= 0)
                {
                    this.verifyCase(kind, pair.dependent[kind], side, pair.support[kind], on, off, whole, before);
                }
            }
        });
    }

    private void verifyCase(int kind, int d, Direction side, int s, GhostSupport.Reach[] onAll, GhostSupport.Reach[] offAll,
            GhostSupport.Reach[] wholeAll, GhostSupport.Reach[] beforeAll)
    {
        BlockState dependent = this.states.get(d);
        BlockState support = this.states.get(s);

        if (this.cat[d] == FALLS)
        {
            // Сыпучий блок самого слоя мод опорой не проверяет (canSurvive у него пуст) — отдельный, известный случай
            this.stat("сыпучий блок слоя (не проверяется)", true);
            return;
        }

        int axis = side.getAxis().ordinal();
        BlockPos near = P.relative(side);
        Cells empty = new Cells();
        Cells schematic = new Cells().one(P, dependent).two(near, support);
        GhostSupport.Stand on = GhostSupport.stand(dependent, AIR, P, empty, schematic, onAll[axis]);
        GhostSupport.Stand off = GhostSupport.stand(dependent, AIR, P, empty, schematic, offAll[axis]);
        GhostSupport.Stand whole = GhostSupport.stand(dependent, AIR, P, empty, schematic, wholeAll[axis]);

        if (kind == MUTUAL)
        {
            this.mutualRows.add(new String[]{dependent.toString(), side.getName(), support.toString(), on.name(), off.name(),
                    whole.name()});
            this.stat("блок и опора держат друг друга (в отдельном файле)", true);
            return;
        }

        this.expect("опора на соседнем слое, настройка включена: " + KIND[kind], kind == SELF, on, dependent, side, support,
                null);
        this.expect("опора на соседнем слое, настройка выключена: " + KIND[kind], false, off, dependent, side, support, null);
        this.expect("без слоёв: " + KIND[kind], kind != NEEDS, whole, dependent, side, support, null);
        this.old("опора на соседнем слое, настройка включена: " + KIND[kind],
                GhostSupport.stand(dependent, AIR, P, empty, schematic, beforeAll[axis]));

        if (kind != NEEDS || this.selfSupport[s] == null)
        {
            return;
        }

        // Опора опоры: блок T, с которым опора встаёт, — не в клетке самого блока. Отдельно — дальше от слоя по его оси
        // (столбик: табличка под табличкой под потолком) и сбоку от опоры, на её же слое
        boolean any = false;
        boolean aside = false;

        for (Direction next : SIDES)
        {
            int t = this.selfSupport[s][next.ordinal()];

            if (t < 0 || next == side.getOpposite() || (next != side && aside))
            {
                continue;
            }

            any = true;
            aside |= next != side;
            String where = next == side ? "столбиком от слоя" : "сбоку от опоры";
            BlockState third = this.states.get(t);
            BlockPos far = near.relative(next);
            Cells deep = new Cells().one(P, dependent).two(near, support).three(far, third);
            // Только в схеме: опору с этого слоя не поставить — блока в списке быть не должно
            this.expect("опора опоры (" + where + ") только в схеме, настройка включена", false,
                    GhostSupport.stand(dependent, AIR, P, empty, deep, onAll[axis]), dependent, side, support, third);
            this.old("опора опоры (" + where + ") только в схеме, настройка включена",
                    GhostSupport.stand(dependent, AIR, P, empty, deep, beforeAll[axis]));
            // Уже стоит в мире: опора ставится сама — блок в списке
            Cells built = new Cells().one(far, third);
            this.expect("опора опоры (" + where + ") стоит в мире, настройка включена", true,
                    GhostSupport.stand(dependent, AIR, P, built, deep, onAll[axis]), dependent, side, support, third);
            // Без слоёв цепочка строится вся в этой работе — как и раньше
            this.expect("без слоёв: опора опоры (" + where + ") только в схеме", true,
                    GhostSupport.stand(dependent, AIR, P, empty, deep, wholeAll[axis]), dependent, side, support, third);
        }

        if (! any)
        {
            this.stat("опора, которой нужна своя опора: одиночной опоры для неё нет", true);
        }
    }

    private void old(String check, GhostSupport.Stand got)
    {
        AtomicInteger[] row = this.oldStats.computeIfAbsent(check, key -> new AtomicInteger[]{new AtomicInteger(),
                new AtomicInteger()});
        row[0].incrementAndGet();

        if (got != GhostSupport.Stand.NEVER)
        {
            row[1].incrementAndGet();
        }
    }

    /** {@code listed} — блок должен быть в списке (ставится сейчас или после опоры), иначе — скрыт. */
    private void expect(String check, boolean listed, GhostSupport.Stand got, BlockState dependent, Direction side,
            BlockState support, @Nullable BlockState third)
    {
        boolean ok = listed == (got != GhostSupport.Stand.NEVER);
        this.stat(check, ok);

        if (! ok && this.checkRows.size() < MAX_CHECK_ROWS)
        {
            this.checkRows.add(new String[]{check, listed ? "в списке" : "скрыт", got.name(), dependent.toString(),
                    side.getName(), support.toString(), third == null ? "" : third.toString()});
        }
    }

    private void stat(String check, boolean ok)
    {
        AtomicInteger[] row = this.checkStats.computeIfAbsent(check, key -> new AtomicInteger[]{new AtomicInteger(),
                new AtomicInteger()});
        row[0].incrementAndGet();

        if (! ok)
        {
            row[1].incrementAndGet();
        }
    }

    // ===== Ход прогона и файлы =====

    @Override
    public boolean waiting()
    {
        return ! (this.computed.get() && this.placed.get());
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (! this.computed.get() || ! this.placed.get())
        {
            return false;
        }

        // Табличка, поставленная в воздухе, открыла у клиента редактор — закрыть
        if (mc.gui.screen() != null)
        {
            mc.gui.setScreen(null);
        }

        if (this.failure != null)
        {
            CovOut.log("[supportmatrix] FAIL: " + this.failure);
            return true;
        }

        if (! this.written)
        {
            this.written = true;

            try
            {
                this.write();
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log("[supportmatrix] FAIL: файлы не записать: " + e);
            }
        }

        return true;
    }

    private void write() throws IOException
    {
        int blockCount = this.blocks.size();
        int count = this.states.size();
        int[][] byBlock = new int[blockCount][5];
        int[] sides = new int[blockCount];

        for (int i = 0; i < count; ++i)
        {
            int block = this.stateBlock[i];
            ++byBlock[block][this.cat[i]];

            if (this.cat[i] != SELF)
            {
                sides[block] |= this.sideMask[i];

                if (! this.held[i])
                {
                    ++byBlock[block][3];
                }
            }

            if (this.realDiffers[i])
            {
                ++byBlock[block][4];
            }
        }

        // Сколько разных блоков-опор у блока — по видам
        int[][] supportBlocks = new int[blockCount][4];
        List<long[]> ordered = new ArrayList<>();

        for (Map.Entry<Long, Pair> entry : this.pairs.entrySet())
        {
            long key = entry.getKey();
            int d = (int) (key / blockCount / 6L);
            Pair pair = entry.getValue();

            for (int kind = 0; kind < 4; ++kind)
            {
                if (pair.combos[kind] > 0)
                {
                    ++supportBlocks[d][kind];
                }
            }

            ordered.add(new long[]{key});
        }

        ordered.sort(Comparator.comparingLong(row -> row[0]));

        try (CovOut out = fresh("support-blocks.csv", "id,class,states,self,falls,needs,needs_no_single_support,sides,item,"
                + "air_place,air_state,real_differs,support_blocks_self,support_blocks_needs,support_blocks_falls,"
                + "support_blocks_mutual"))
        {
            for (int b = 0; b < blockCount; ++b)
            {
                Block block = this.blocks.get(b);
                Item item = block.asItem();
                String placedAs = item == Items.AIR ? null : this.airPlaced.get(item);
                out.row(List.of(id(block), block.getClass().getSimpleName(),
                        Integer.toString(byBlock[b][SELF] + byBlock[b][FALLS] + byBlock[b][NEEDS]),
                        Integer.toString(byBlock[b][SELF]), Integer.toString(byBlock[b][FALLS]),
                        Integer.toString(byBlock[b][NEEDS]), Integer.toString(byBlock[b][3]), sideNames(sides[b]),
                        item == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString(),
                        item == Items.AIR ? "NO_ITEM" : placedAs == null ? "NOT_BLOCK_ITEM" : placedAs.isEmpty() ? "NO"
                                : placedAs.startsWith("ошибка") ? placedAs : "YES",
                        placedAs == null || placedAs.startsWith("ошибка") ? "" : placedAs,
                        Integer.toString(byBlock[b][4]), Integer.toString(supportBlocks[b][SELF]),
                        Integer.toString(supportBlocks[b][NEEDS]), Integer.toString(supportBlocks[b][FALLS]),
                        Integer.toString(supportBlocks[b][MUTUAL])));
            }
        }

        Map<String, long[]> families = new TreeMap<>();
        Map<String, String> familyExample = new TreeMap<>();

        try (CovOut out = fresh("support-pairs.csv", "block,class,side,support,support_class,combos_self,combos_falls,"
                + "combos_needs,combos_mutual,example_block,example_support"))
        {
            for (long[] row : ordered)
            {
                long key = row[0];
                int s = (int) (key % blockCount);
                Direction side = SIDES[(int) ((key / blockCount) % 6L)];
                int d = (int) (key / blockCount / 6L);
                Pair pair = this.pairs.get(key);
                Block dependent = this.blocks.get(d);
                Block support = this.blocks.get(s);
                String family = dependent.getClass().getSimpleName() + "," + sideKind(side) + ","
                        + support.getClass().getSimpleName();
                long[] sum = families.computeIfAbsent(family, k -> new long[5]);
                ++sum[4];

                for (int kind = 0; kind < 4; ++kind)
                {
                    sum[kind] += pair.combos[kind];
                }

                int shown = pair.support[NEEDS] >= 0 ? NEEDS : pair.support[MUTUAL] >= 0 ? MUTUAL
                        : pair.support[FALLS] >= 0 ? FALLS : SELF;
                familyExample.putIfAbsent(family + "," + KIND[shown], this.states.get(pair.dependent[shown]) + " <- "
                        + this.states.get(pair.support[shown]));

                // В файл пар — только опоры, которые сами требуют опоры, упадут или держатся на самом блоке
                if (pair.combos[NEEDS] + pair.combos[FALLS] + pair.combos[MUTUAL] == 0)
                {
                    continue;
                }

                out.row(List.of(id(dependent), dependent.getClass().getSimpleName(), side.getName(), id(support),
                        support.getClass().getSimpleName(), Integer.toString(pair.combos[SELF]),
                        Integer.toString(pair.combos[FALLS]), Integer.toString(pair.combos[NEEDS]),
                        Integer.toString(pair.combos[MUTUAL]), this.states.get(pair.dependent[shown]).toString(),
                        this.states.get(pair.support[shown]).toString()));
            }
        }

        try (CovOut out = fresh("support-families.csv", "block_class,side,support_class,block_pairs,combos_self,"
                + "combos_falls,combos_needs,combos_mutual,verdict,example"))
        {
            for (Map.Entry<String, long[]> entry : families.entrySet())
            {
                long[] sum = entry.getValue();
                String verdict = sum[NEEDS] + sum[FALLS] + sum[MUTUAL] == 0 ? "SELF"
                        : sum[SELF] == 0 ? (sum[NEEDS] > 0 ? "NEEDS" : sum[MUTUAL] > 0 ? "MUTUAL" : "FALLS") : "MIXED";
                String[] parts = entry.getKey().split(",");
                String example = "";

                for (String kind : new String[]{"NEEDS", "MUTUAL", "FALLS", "SELF"})
                {
                    String found = familyExample.get(entry.getKey() + "," + kind);

                    if (found != null)
                    {
                        example = found;
                        break;
                    }
                }

                out.row(List.of(parts[0], parts[1], parts[2], Long.toString(sum[4]), Long.toString(sum[SELF]),
                        Long.toString(sum[FALLS]), Long.toString(sum[NEEDS]), Long.toString(sum[MUTUAL]), verdict, example));
            }
        }

        try (CovOut out = fresh("support-mutual.csv", "block,side,support,list_adjacent_on,list_adjacent_off,list_no_layers"))
        {
            List<String[]> rows = new ArrayList<>(this.mutualRows);
            rows.sort(Comparator.comparing(row -> row[0] + row[1] + row[2]));

            for (String[] row : rows)
            {
                out.row(Arrays.asList(row));
            }
        }

        try (CovOut out = fresh("support-check.csv", "check,expected,got,block,side,support,support_of_support"))
        {
            List<String[]> rows = new ArrayList<>(this.checkRows);
            rows.sort(Comparator.comparing(row -> row[0] + row[3] + row[4] + row[5]));

            for (String[] row : rows)
            {
                out.row(Arrays.asList(row));
            }
        }

        int failed = 0;

        for (Map.Entry<String, AtomicInteger[]> entry : new TreeMap<>(this.checkStats).entrySet())
        {
            int total = entry.getValue()[0].get();
            int bad = entry.getValue()[1].get();
            failed += bad;
            CovOut.log("[supportmatrix] " + (bad == 0 ? "OK   " : "FAIL ") + entry.getKey() + ": пар " + total
                    + (bad == 0 ? "" : ", расхождений " + bad));
        }

        for (Map.Entry<String, AtomicInteger[]> entry : new TreeMap<>(this.oldStats).entrySet())
        {
            CovOut.log("[supportmatrix] по прежнему правилу в списке было бы — " + entry.getKey() + ": "
                    + entry.getValue()[1].get() + " из " + entry.getValue()[0].get());
        }

        CovOut.log("[supportmatrix] итог: " + (failed == 0 ? "OK" : "FAIL, расхождений " + failed) + "; файлы в " + CovOut.dir());
    }

    private static CovOut fresh(String name, String header) throws IOException
    {
        Files.deleteIfExists(CovOut.dir().resolve(name));
        return CovOut.open(name, header);
    }

    private static String id(Block block)
    {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static String sideKind(Direction side)
    {
        return side == Direction.UP ? "above" : side == Direction.DOWN ? "below" : "beside";
    }

    private static String sideNames(int mask)
    {
        StringBuilder text = new StringBuilder();

        for (Direction side : SIDES)
        {
            if ((mask & (1 << side.ordinal())) != 0)
            {
                text.append(text.length() == 0 ? "" : "|").append(side.getName());
            }
        }

        return text.toString();
    }

    @Override
    public void close()
    {
    }

    /** Сочетания одной пары блоков по видам опоры и по одному примеру на вид. */
    private static final class Pair
    {
        private final int[] combos = new int[4];
        private final int[] dependent = {-1, -1, -1, -1};
        private final int[] support = {-1, -1, -1, -1};

        synchronized void add(int d, int[] row)
        {
            for (int kind = 0; kind < 4; ++kind)
            {
                this.combos[kind] += row[kind];

                // Пример — с наименьшим номером состояния блока: от прогона к прогону один и тот же
                if (row[4 + kind] >= 0 && (this.dependent[kind] < 0 || d < this.dependent[kind]))
                {
                    this.dependent[kind] = d;
                    this.support[kind] = row[4 + kind];
                }
            }
        }
    }

    /** Мир из одной-трёх клеток, остальное — воздух; при {@link #reads} записывает, какие клетки спрашивали. */
    private static final class Cells implements BlockGetter
    {
        private long a = Long.MIN_VALUE;
        private long b = Long.MIN_VALUE;
        private long c = Long.MIN_VALUE;
        private BlockState stateA = AIR;
        private BlockState stateB = AIR;
        private BlockState stateC = AIR;
        private @Nullable LongOpenHashSet reads;

        Cells one(BlockPos pos, BlockState state)
        {
            this.a = pos.asLong();
            this.stateA = state;
            return this;
        }

        Cells two(BlockPos pos, BlockState state)
        {
            this.b = pos.asLong();
            this.stateB = state;
            return this;
        }

        Cells three(BlockPos pos, BlockState state)
        {
            this.c = pos.asLong();
            this.stateC = state;
            return this;
        }

        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            long key = pos.asLong();

            if (this.reads != null)
            {
                this.reads.add(key);
            }

            return key == this.a ? this.stateA : key == this.b ? this.stateB : key == this.c ? this.stateC : AIR;
        }

        @Override
        public FluidState getFluidState(BlockPos pos)
        {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos)
        {
            if (this.reads != null)
            {
                this.reads.add(pos.asLong());
            }

            return null;
        }

        @Override
        public int getHeight()
        {
            return 384;
        }

        @Override
        public int getMinY()
        {
            return -64;
        }
    }
}
