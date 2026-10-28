package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.SimPlaceContext;
import com.google.gson.JsonObject;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Дамп T («legacy») — наблюдаемое поведение шести фрагментов, переписанных в 0.0.26 своими словами
 * (аудит прав, разделы D, D-E, M1). Снимается на старом коде и на новом; файлы обязаны совпасть
 * байт в байт. Всё, что пишется, — детерминировано: сетки и перечисления, случайные миры — с
 * фиксированным зерном, порядок обхода — по спискам.
 *
 * <ul>
 *   <li>{@code legacy-m1b.csv} — порядок {@code SimPlaceContext.getNearestLookingDirections} (сырой,
 *       клик по заменяемой клетке) на плотной сетке поворота головы × наклона, плюс точные оси,
 *       ±0, соседние float, NaN/∞ и углы вне диапазона.</li>
 *   <li>{@code legacy-m1a.csv} — форма {@code Rails.settle} (шов с сигналом) на мире-словаре: полное
 *       перечисление «вид соседа» по четырём сторонам × столбец над/под целью × тип/форма цели × сигнал,
 *       плюс 30000 случайных миров 5×5×3.</li>
 *   <li>{@code legacy-d1.csv} — {@code LayerCountManager.layerClamp} (шов) на сетке боксов × режимов
 *       {@code LayerRange} × осей × границ (в том числе вывернутый диапазон) × «весь диапазон».</li>
 *   <li>{@code legacy-d2.csv} — {@code LayerHudRenderer.hudOrigin} (шов): все выравнивания InfoHud ×
 *       масштабы × смещения × размеры панели × ширины экрана; высота экрана — масштабы GUI 1–3;
 *       эффекты зелий (смещение malilib) — нет / полезный / полезный+вредный / игрок null.</li>
 *   <li>{@code legacy-d3.csv} — решение Litematica {@code ChunkRendererSchematicVbo.shouldCullOverlayPos}
 *       (вместе с нашим миксином) на отдельном экземпляре с подставленным видом схемы: опции × диапазон
 *       слоёв × клиентский блок × блок схемы × соседи по шести сторонам.</li>
 *   <li>{@code legacy-e1.csv} — {@code ToolPick.pick}: блоки × наборы инвентаря (хотбар, инвентарь,
 *       чары, почти сломанные, ничьи, пустая рука) в выживании; плюс креатив.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DumpLegacy implements CovJob
{
    private static final String[] FILES = { "legacy-m1b.csv", "legacy-m1a.csv", "legacy-d1.csv", "legacy-d2.csv",
            "legacy-d3.csv", "legacy-e1.csv" };

    private interface Phase
    {
        String name();

        /** Одна порция работы; true — фаза закончена. */
        boolean step(Minecraft mc) throws Exception;

        default void finish(Minecraft mc)
        {
        }
    }

    private final List<Phase> phases = new ArrayList<>();
    private int phaseIndex;
    private CovOut out;
    private long until;

    @Override
    public String name()
    {
        return "T (legacy: шесть переписанных фрагментов)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.getSingleplayerServer() == null || mc.player == null || mc.level == null)
        {
            return false;
        }

        try
        {
            for (String file : FILES)
            {
                Files.deleteIfExists(CovOut.dir().resolve(file));
            }
        }
        catch (IOException e)
        {
            CovOut.log("[legacy] не удалить старые файлы: " + e);
            return false;
        }

        String only = System.getenv("LL_LEGACY_ONLY");

        addIf(only, new OrderPhase());
        addIf(only, new RailPhase());
        addIf(only, new ClampPhase());
        addIf(only, new HudPhase());
        addIf(only, new CullPhase());
        addIf(only, new ToolPhase());

        return true;
    }

    private void addIf(@Nullable String only, Phase phase)
    {
        if (only == null || only.isBlank() || only.toLowerCase(Locale.ROOT).contains(phase.name()))
        {
            this.phases.add(phase);
        }
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.until || mc.level == null || mc.player == null)
        {
            return false;
        }

        if (this.phaseIndex >= this.phases.size())
        {
            return true;
        }

        Phase phase = this.phases.get(this.phaseIndex);

        try
        {
            if (this.out == null)
            {
                this.out = CovOut.open("legacy-" + phase.name() + ".csv", header(phase.name()));
                CovOut.log("[legacy] фаза " + phase.name());
            }

            if (! phase.step(mc))
            {
                return false;
            }
        }
        catch (Exception | LinkageError e)
        {
            CovOut.log("[legacy] фаза " + phase.name() + " упала: " + e);
            e.printStackTrace(System.out);
        }

        phase.finish(mc);
        this.out.close();
        CovOut.log("[legacy] " + phase.name() + ": " + summary(this.out.path()));
        this.out = null;
        ++this.phaseIndex;

        return this.phaseIndex >= this.phases.size();
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            this.out.close();
        }

        CovOut.log("[legacy] итог: фаз " + this.phaseIndex + "/" + this.phases.size());
    }

    private static String header(String phase)
    {
        return switch (phase)
        {
            case "m1b" -> "key,codes";
            case "m1a" -> "key,shapes";
            case "d1" -> "key,boxes";
            case "d2" -> "key,origins";
            case "d3" -> "key,cull";
            default -> "key,result,selected,hand,slots";
        };
    }

    private static String summary(Path path)
    {
        try
        {
            byte[] data = Files.readAllBytes(path);
            long lines = 0;

            for (byte b : data)
            {
                lines += b == '\n' ? 1 : 0;
            }

            return "строк " + lines + ", sha256 " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (Exception e)
        {
            return "не прочитать: " + e;
        }
    }

    private void row(String... fields)
    {
        this.out.row(List.of(fields));
    }

    private static Method declared(String owner, String name, Class<?>... args) throws ReflectiveOperationException
    {
        Method method = Class.forName(owner).getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static Object call(Method method, Object self, Object... args) throws Exception
    {
        try
        {
            return method.invoke(self, args);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
        }
    }

    private static String num(float value)
    {
        return Float.toString(value) + "#" + Integer.toHexString(Float.floatToRawIntBits(value));
    }

    // ===================================================================================
    // M1b: порядок направлений взгляда
    // ===================================================================================

    private final class OrderPhase implements Phase
    {
        private final List<Float> yaws = new ArrayList<>();
        private final List<Float> pitches = new ArrayList<>();
        private final ItemStack stack = new ItemStack(Items.STONE);
        private int index;
        private int stage;
        private BlockHitResult hit;

        OrderPhase()
        {
            for (int i = -720; i <= 720; ++i)
            {
                this.yaws.add(i * 0.5F);
            }

            for (int k = -16; k <= 16; ++k)
            {
                float exact = k * 22.5F;
                this.yaws.add(Math.nextDown(exact));
                this.yaws.add(Math.nextUp(exact));
                this.yaws.add(exact + 0.001F);
                this.yaws.add(exact - 0.001F);
            }

            for (float extra : new float[] { -0.0F, 1.0E-7F, -1.0E-7F, 720.25F, -1080.5F, 3600.0F, -3599.75F, 1.0E6F,
                    Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 44.99F, 45.01F, 134.999F, 315.0001F })
            {
                this.yaws.add(extra);
            }

            for (int i = -180; i <= 180; ++i)
            {
                this.pitches.add(i * 0.5F);
            }

            for (float extra : new float[] { -0.0F, Math.nextUp(90.0F), Math.nextDown(90.0F), Math.nextUp(-90.0F),
                    Math.nextDown(-90.0F), Math.nextUp(45.0F), Math.nextDown(45.0F), Math.nextUp(-45.0F),
                    Math.nextDown(-45.0F), 1.0E-6F, -1.0E-6F, 35.264F, -35.264F, 35.2644F, -35.2644F, 90.5F, -90.5F,
                    180.0F, -180.0F, 270.0F, -270.0F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
            {
                this.pitches.add(extra);
            }
        }

        @Override
        public String name()
        {
            return "m1b";
        }

        @Override
        public boolean step(Minecraft mc)
        {
            if (this.hit == null)
            {
                BlockPos air = new BlockPos(mc.player.getBlockX(), 250, mc.player.getBlockZ());
                CovOut.log("[legacy] m1b: клик по " + air + " = " + mc.level.getBlockState(air));
                this.hit = new BlockHitResult(Vec3.atCenterOf(air), Direction.UP, air, false);
            }

            // Стадия 0: голова отдельно от тела (8-арг. of); стадия 1: голова = тело (7-арг. of)
            if (this.index >= this.yaws.size())
            {
                ++this.stage;
                this.index = 0;

                if (this.stage > 1)
                {
                    return true;
                }
            }

            float yaw = this.yaws.get(this.index++);
            StringBuilder codes = new StringBuilder(this.pitches.size() * 4);

            for (float pitch : this.pitches)
            {
                SimPlaceContext ctx = this.stage == 0
                        ? SimPlaceContext.of(mc.level, mc.player, InteractionHand.MAIN_HAND, this.stack, this.hit,
                                new LookSpoof.Pose(yaw + 123.0F, pitch, false), yaw)
                        : SimPlaceContext.of(mc.level, mc.player, InteractionHand.MAIN_HAND, this.stack, this.hit,
                                new LookSpoof.Pose(yaw, pitch, false));
                Direction[] order = ctx.getNearestLookingDirections();

                for (Direction d : order)
                {
                    codes.append(d.getSerializedName().charAt(0));
                }

                codes.append(ctx.getNearestLookingDirection().getSerializedName().charAt(0)).append(' ');
            }

            row("m1b|" + (this.stage == 0 ? "head" : "pose") + "|" + num(yaw), codes.toString().trim());
            return false;
        }
    }

    // ===================================================================================
    // M1a: форма рельса после ванильной доводки соседями
    // ===================================================================================

    /** Мир-словарь: всё, чего нет в словаре, — воздух. */
    private static final class MapLevel implements BlockGetter
    {
        final Map<BlockPos, BlockState> cells = new HashMap<>();

        void put(BlockPos pos, BlockState state)
        {
            this.cells.put(pos.immutable(), state);
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos)
        {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            BlockState state = this.cells.get(pos);
            return state != null ? state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos)
        {
            return this.getBlockState(pos).getFluidState();
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

    private final class RailPhase implements Phase
    {
        private static final BlockPos P = new BlockPos(0, 64, 0);
        private static final Direction[] SIDES = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
        private static final int KINDS = 10;
        private static final int RANDOM_WORLDS = 30000;

        private final List<BlockState> targets = new ArrayList<>();
        private Method settle;
        private int target;
        private int signal;
        private int column;
        private int north;
        private int south;
        private int randomIndex = -1;
        private Random random;

        RailPhase()
        {
            for (RailShape shape : RailShape.values())
            {
                this.targets.add(rail(Blocks.RAIL, shape));
            }

            for (RailShape shape : RailShape.values())
            {
                if (BlockStateProperties.RAIL_SHAPE_STRAIGHT.getPossibleValues().contains(shape))
                {
                    this.targets.add(rail(Blocks.POWERED_RAIL, shape));
                }
            }
        }

        @Override
        public String name()
        {
            return "m1a";
        }

        @Override
        public boolean step(Minecraft mc) throws Exception
        {
            if (this.settle == null)
            {
                this.settle = declared("com.aell.layerlist.easyplace.Rails", "settle", BlockGetter.class, BlockPos.class,
                        BlockState.class, boolean.class);
                MapLevel empty = new MapLevel();
                row("m1a|not-rail", String.valueOf(code((BlockState) call(this.settle, null, empty, P, Blocks.STONE.defaultBlockState(), true))));
            }

            if (this.randomIndex < 0)
            {
                this.structured();
                return false;
            }

            return this.randomWorlds();
        }

        /** Одна строка: цель × сигнал × столбец × север × юг, внутри — 100 сочетаний запад × восток. */
        private void structured() throws Exception
        {
            BlockState placed = this.targets.get(this.target);
            StringBuilder shapes = new StringBuilder(KINDS * KINDS);

            for (int west = 0; west < KINDS; ++west)
            {
                for (int east = 0; east < KINDS; ++east)
                {
                    MapLevel level = new MapLevel();
                    neighbour(level, Direction.NORTH, this.north);
                    neighbour(level, Direction.SOUTH, this.south);
                    neighbour(level, Direction.WEST, west);
                    neighbour(level, Direction.EAST, east);

                    if (this.column == 1)
                    {
                        level.put(P.above(), rail(Blocks.RAIL, RailShape.NORTH_SOUTH));
                    }
                    else if (this.column == 2)
                    {
                        level.put(P.below(), rail(Blocks.RAIL, RailShape.EAST_WEST));
                    }

                    shapes.append(code((BlockState) call(this.settle, null, level, P, placed, this.signal == 1)));
                }
            }

            row("m1a|" + label(placed) + "|sig" + this.signal + "|col" + this.column + "|n" + this.north + "|s" + this.south,
                    shapes.toString());

            if (++this.south < KINDS)
            {
                return;
            }

            this.south = 0;

            if (++this.north < KINDS)
            {
                return;
            }

            this.north = 0;

            if (++this.column < 3)
            {
                return;
            }

            this.column = 0;

            if (++this.signal < 2)
            {
                return;
            }

            this.signal = 0;

            if (++this.target < this.targets.size())
            {
                return;
            }

            this.randomIndex = 0;
            this.random = new Random(20261006L);
        }

        /** Случайные миры 5×5×3 вокруг цели — сто миров на строку. */
        private boolean randomWorlds() throws Exception
        {
            if (this.randomIndex >= RANDOM_WORLDS)
            {
                return true;
            }

            StringBuilder shapes = new StringBuilder(100);
            int first = this.randomIndex;
            RailShape[] all = RailShape.values();

            for (int n = 0; n < 100; ++n, ++this.randomIndex)
            {
                MapLevel level = new MapLevel();

                for (int dx = -2; dx <= 2; ++dx)
                {
                    for (int dz = -2; dz <= 2; ++dz)
                    {
                        for (int dy = -1; dy <= 1; ++dy)
                        {
                            if (dx == 0 && dz == 0 && dy == 0)
                            {
                                continue;
                            }

                            int ring = Math.max(Math.abs(dx), Math.abs(dz));
                            int chance = ring <= 1 ? 45 : 30;

                            if (this.random.nextInt(100) < chance)
                            {
                                level.put(P.offset(dx, dy, dz), rail(Blocks.RAIL, all[this.random.nextInt(all.length)]));
                            }
                        }
                    }
                }

                BlockState placed = this.targets.get(this.random.nextInt(this.targets.size()));
                boolean powered = this.random.nextBoolean();
                shapes.append(code((BlockState) call(this.settle, null, level, P, placed, powered)));
            }

            row("m1a|random|" + first, shapes.toString());
            return false;
        }

        /** Вид соседа со стороны {@code side}; 0 — нет никого. Числа — см. разметку в журнале модуля. */
        private static void neighbour(MapLevel level, Direction side, int kind)
        {
            BlockPos at = P.relative(side);
            Direction turn = side.getClockWise();
            RailShape along = side.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
            RailShape across = side.getAxis() == Direction.Axis.Z ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
            RailShape alongTurn = turn.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;

            switch (kind)
            {
                case 1 -> level.put(at, rail(Blocks.RAIL, along));
                case 2 -> level.put(at.below(), rail(Blocks.RAIL, along));
                case 3 -> level.put(at.above(), rail(Blocks.RAIL, along));
                case 4, 6 ->
                {
                    // Сосед поворотом наружу, оба его конца живые и ни один не смотрит на цель
                    level.put(at, rail(Blocks.RAIL, corner(side, turn)));
                    level.put(at.relative(side), rail(Blocks.RAIL, along));
                    level.put(at.relative(turn), rail(Blocks.RAIL, alongTurn));

                    if (kind == 6)
                    {
                        level.put(at.above(), rail(Blocks.RAIL, along));
                    }
                }
                case 5 ->
                {
                    level.put(at, rail(Blocks.RAIL, along));
                    level.put(at.above(), rail(Blocks.RAIL, along));
                }
                case 7 -> level.put(at, rail(Blocks.RAIL, ascending(side)));
                case 8 ->
                {
                    level.put(at, rail(Blocks.RAIL, ascending(side.getOpposite())));
                    level.put(at.relative(side), rail(Blocks.RAIL, along));
                }
                case 9 ->
                {
                    // Поперёк, с живыми концами по обе стороны
                    level.put(at, rail(Blocks.RAIL, across));
                    level.put(at.relative(turn), rail(Blocks.RAIL, across));
                    level.put(at.relative(turn.getOpposite()), rail(Blocks.RAIL, across));
                }
                default ->
                {
                }
            }
        }

        private static RailShape corner(Direction a, Direction b)
        {
            boolean north = a == Direction.NORTH || b == Direction.NORTH;
            boolean south = a == Direction.SOUTH || b == Direction.SOUTH;
            boolean east = a == Direction.EAST || b == Direction.EAST;

            if (north)
            {
                return east ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
            }

            if (south)
            {
                return east ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST;
            }

            return RailShape.NORTH_SOUTH;
        }

        private static RailShape ascending(Direction up)
        {
            return switch (up)
            {
                case NORTH -> RailShape.ASCENDING_NORTH;
                case SOUTH -> RailShape.ASCENDING_SOUTH;
                case WEST -> RailShape.ASCENDING_WEST;
                default -> RailShape.ASCENDING_EAST;
            };
        }

        private static BlockState rail(Block block, RailShape shape)
        {
            Property<RailShape> property = ((BaseRailBlock) block).getShapeProperty();
            return block.defaultBlockState().setValue(property, shape);
        }

        private static String label(BlockState state)
        {
            Property<RailShape> property = ((BaseRailBlock) state.getBlock()).getShapeProperty();
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + ":" + state.getValue(property).getSerializedName();
        }

        private static char code(BlockState state)
        {
            if (! (state.getBlock() instanceof BaseRailBlock rail))
            {
                return '-';
            }

            return (char) ('A' + state.getValue(rail.getShapeProperty()).ordinal());
        }
    }

    // ===================================================================================
    // D1: часть бокса внутри диапазона слоёв
    // ===================================================================================

    private static final IRangeChangeListener QUIET = new IRangeChangeListener()
    {
        @Override
        public void updateAll()
        {
        }

        @Override
        public void updateBetweenX(int a, int b)
        {
        }

        @Override
        public void updateBetweenY(int a, int b)
        {
        }

        @Override
        public void updateBetweenZ(int a, int b)
        {
        }
    };

    private final class ClampPhase implements Phase
    {
        private static final int[][] SPANS = { { 0, 3 }, { 3, 0 }, { 1, 1 }, { -2, 5 }, { 5, -2 }, { 0, 0 }, { 2, 4 }, { 6, 9 } };
        private static final int[] LAYERS = { -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 9, 10 };

        private final List<IntBoundingBox> boxes = new ArrayList<>();
        private final List<String[]> ranges = new ArrayList<>();
        private Method clamp;
        private Method forceMin;
        private int index;

        ClampPhase()
        {
            for (int[] x : SPANS)
            {
                for (int[] y : SPANS)
                {
                    for (int[] z : SPANS)
                    {
                        this.boxes.add(new IntBoundingBox(x[0], y[0], z[0], x[1], y[1], z[1]));
                    }
                }
            }

            for (Direction.Axis axis : Direction.Axis.values())
            {
                this.ranges.add(new String[] { axis.getName(), "all", "0", "0" });

                for (int layer : LAYERS)
                {
                    this.ranges.add(new String[] { axis.getName(), "single", "" + layer, "0" });
                    this.ranges.add(new String[] { axis.getName(), "above", "" + layer, "0" });
                    this.ranges.add(new String[] { axis.getName(), "below", "" + layer, "0" });
                }

                for (int min : LAYERS)
                {
                    for (int max : LAYERS)
                    {
                        if (min <= max || (min - max <= 3 && min % 2 == 0))
                        {
                            this.ranges.add(new String[] { axis.getName(), "range", "" + min, "" + max });
                        }
                    }
                }
            }
        }

        @Override
        public String name()
        {
            return "d1";
        }

        @Override
        public boolean step(Minecraft mc) throws Exception
        {
            if (this.clamp == null)
            {
                this.clamp = declared("com.aell.layerlist.core.LayerCountManager", "layerClamp", IntBoundingBox.class,
                        LayerRange.class, boolean.class);
                this.forceMin = LayerRange.class.getDeclaredMethod("setLayerRangeMin", int.class, boolean.class);
                this.forceMin.setAccessible(true);
            }

            if (this.index >= this.ranges.size() * 2)
            {
                return true;
            }

            String[] spec = this.ranges.get(this.index / 2);
            boolean everything = this.index % 2 == 1;
            ++this.index;

            LayerRange range = this.build(spec);
            StringBuilder boxes = new StringBuilder(this.boxes.size() * 14);

            for (IntBoundingBox box : this.boxes)
            {
                IntBoundingBox span = (IntBoundingBox) call(this.clamp, null, box, range, everything);

                if (span == null)
                {
                    boxes.append('-');
                }
                else
                {
                    boxes.append(span.minX()).append('.').append(span.minY()).append('.').append(span.minZ()).append('.')
                            .append(span.maxX()).append('.').append(span.maxY()).append('.').append(span.maxZ());
                }

                boxes.append(' ');
            }

            row("d1|" + String.join(":", spec) + "|all=" + everything + "|lo" + range.getLayerMin() + "|hi"
                    + range.getLayerMax(), boxes.toString().trim());
            return false;
        }

        private LayerRange build(String[] spec) throws Exception
        {
            LayerRange range = new LayerRange(QUIET);
            range.setAxis(Direction.Axis.byName(spec[0]));
            int a = Integer.parseInt(spec[2]);
            int b = Integer.parseInt(spec[3]);

            switch (spec[1])
            {
                case "single" ->
                {
                    range.setLayerMode(LayerMode.SINGLE_LAYER, false);
                    range.setLayerSingle(a);
                }
                case "above" ->
                {
                    range.setLayerMode(LayerMode.ALL_ABOVE, false);
                    range.setLayerAbove(a);
                }
                case "below" ->
                {
                    range.setLayerMode(LayerMode.ALL_BELOW, false);
                    range.setLayerBelow(a);
                }
                case "range" ->
                {
                    range.setLayerMode(LayerMode.LAYER_RANGE, false);

                    if (a <= b)
                    {
                        range.setLayerRangeMax(b);
                        range.setLayerRangeMin(a);
                        range.setLayerRangeMax(b);
                    }
                    else
                    {
                        // Вывернутый диапазон: так он бывает только из файла настроек или принудительно
                        range.setLayerRangeMin(-100);
                        range.setLayerRangeMax(b);
                        call(this.forceMin, range, a, true);
                    }
                }
                default -> range.setLayerMode(LayerMode.ALL, false);
            }

            return range;
        }
    }

    // ===================================================================================
    // D2: место панели HUD в раскладке InfoHud
    // ===================================================================================

    private final class HudPhase implements Phase
    {
        private static final double[] SCALES = { 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0, 0.1, 0.3,
                1.0 / 3.0, 0.7, 1.1, 4.0 / 3.0, 2.2, 0.05 };
        private static final int[] PANEL_W = { 40, 121, 163, 250, 777, 3000 };
        private static final int[] PANEL_H = { 30, 77, 201, 650 };
        private static final int[] X_OFF = { 0, 2, 7 };
        private static final int[] Y_OFF = { 0, 4, 13, 61 };
        private static final int[] WIDTHS = { 0, 50, 320, 427, 641, 1366, 1921 };
        private static final String[] EFFECTS = { "none", "speed", "speed+poison", "null-player" };

        private Method origin;
        private int savedGui = -1;
        private int stage;
        private int gui = 1;
        private int effect;
        private int alignment;
        private int width;
        private int scale;

        @Override
        public String name()
        {
            return "d2";
        }

        @Override
        public boolean step(Minecraft mc) throws Exception
        {
            if (this.origin == null)
            {
                this.origin = declared("com.aell.layerlist.hud.LayerHudRenderer", "hudOrigin", int.class, double.class,
                        int.class, int.class, int.class, int.class, HudAlignment.class, Player.class);
                this.savedGui = mc.options.guiScale().get();
                mc.player.removeAllEffects();
            }

            // Стадия 0: перебор по ширине экрана (x) при масштабе GUI 2; стадия 1: по высоте (y) — масштабы GUI 1–3 × эффекты
            if (this.stage == 0)
            {
                if (mc.options.guiScale().get() != 2)
                {
                    setGui(mc, 2);
                    DumpLegacy.this.until = Util.getMillis() + 300L;
                    return false;
                }

                this.sweepX(mc);
                return false;
            }

            if (this.stage == 1)
            {
                if (mc.options.guiScale().get() != this.gui)
                {
                    setGui(mc, this.gui);
                    DumpLegacy.this.until = Util.getMillis() + 300L;
                    return false;
                }

                applyEffects(mc, this.effect);
                this.sweepY(mc);
                return false;
            }

            return true;
        }

        @Override
        public void finish(Minecraft mc)
        {
            if (mc.player != null)
            {
                mc.player.removeAllEffects();
            }

            if (this.savedGui >= 0)
            {
                setGui(mc, this.savedGui);
            }
        }

        private void sweepX(Minecraft mc) throws Exception
        {
            HudAlignment align = HudAlignment.values()[this.alignment];
            double s = SCALES[this.scale];
            int screen = WIDTHS[this.width] == 0 ? mc.getWindow().getGuiScaledWidth() : WIDTHS[this.width];
            StringBuilder origins = new StringBuilder();

            for (int w : PANEL_W)
            {
                for (int x : X_OFF)
                {
                    int[] at = (int[]) call(this.origin, null, screen, s, w, 77, x, 0, align, mc.player);
                    origins.append(at[0]).append(':').append(at[1]).append(' ');
                }
            }

            row("d2|x|" + align.name() + "|w" + (WIDTHS[this.width] == 0 ? "real" : "" + screen) + "|s" + s,
                    origins.toString().trim());

            if (++this.scale < SCALES.length)
            {
                return;
            }

            this.scale = 0;

            if (++this.width < WIDTHS.length)
            {
                return;
            }

            this.width = 0;

            if (++this.alignment < HudAlignment.values().length)
            {
                return;
            }

            this.alignment = 0;
            this.stage = 1;
        }

        private void sweepY(Minecraft mc) throws Exception
        {
            HudAlignment align = HudAlignment.values()[this.alignment];
            double s = SCALES[this.scale];
            Player player = this.effect == 3 ? null : mc.player;
            int screen = mc.getWindow().getGuiScaledWidth();
            StringBuilder origins = new StringBuilder();

            for (int h : PANEL_H)
            {
                for (int y : Y_OFF)
                {
                    String cell;

                    try
                    {
                        int[] at = (int[]) call(this.origin, null, screen, s, 121, h, 2, y, align, player);
                        cell = at[0] + ":" + at[1];
                    }
                    catch (Exception e)
                    {
                        cell = "EXC:" + e.getClass().getSimpleName();
                    }

                    origins.append(cell).append(' ');
                }
            }

            row("d2|y|gui" + this.gui + "|" + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight()
                    + "|" + EFFECTS[this.effect] + "|" + align.name() + "|s" + s, origins.toString().trim());

            if (++this.scale < SCALES.length)
            {
                return;
            }

            this.scale = 0;

            if (++this.alignment < HudAlignment.values().length)
            {
                return;
            }

            this.alignment = 0;

            if (++this.effect < EFFECTS.length)
            {
                return;
            }

            this.effect = 0;

            if (++this.gui <= 3)
            {
                return;
            }

            this.stage = 2;
        }

        private static void applyEffects(Minecraft mc, int effect)
        {
            boolean speed = effect == 1 || effect == 2;
            boolean poison = effect == 2;

            if (speed != mc.player.hasEffect(MobEffects.SPEED))
            {
                if (speed)
                {
                    mc.player.forceAddEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 3600), null);
                }
                else
                {
                    mc.player.removeEffect(MobEffects.SPEED);
                }
            }

            if (poison != mc.player.hasEffect(MobEffects.POISON))
            {
                if (poison)
                {
                    mc.player.forceAddEffect(new MobEffectInstance(MobEffects.POISON, 20 * 3600), null);
                }
                else
                {
                    mc.player.removeEffect(MobEffects.POISON);
                }
            }
        }

        private static void setGui(Minecraft mc, int value)
        {
            mc.options.guiScale().set(value);
            mc.resizeDisplay();
        }
    }

    // ===================================================================================
    // D3: отсечение маркеров оверлея внутри схемы
    // ===================================================================================

    /** Вид схемы, у которого есть только центр и шесть соседей; остальное — воздух. */
    public static class ScriptedView extends ChunkCacheSchematic
    {
        BlockPos center;
        BlockState middle;
        BlockState[] around;

        @SuppressWarnings("unused")
        private ScriptedView()
        {
            super(null, null, BlockPos.ZERO, 0);
        }

        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            if (pos.equals(this.center))
            {
                return this.middle;
            }

            for (Direction side : Direction.values())
            {
                if (pos.equals(this.center.relative(side)))
                {
                    return this.around[side.ordinal()];
                }
            }

            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos)
        {
            return this.getBlockState(pos).getFluidState();
        }
    }

    private static Object allocate(Class<?> type) throws Exception
    {
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private final class CullPhase implements Phase
    {
        private static final BlockPos P = new BlockPos(0, 64, 0);

        private final List<BlockState[]> neighbours = new ArrayList<>();
        private final List<BlockState> clients = new ArrayList<>();
        private final List<BlockState> schematics = new ArrayList<>();
        private Method cull;
        private Object renderer;
        private ScriptedView view;
        private boolean[] saved;
        private JsonObject savedRange;
        private int options;
        private int range;
        private int client;
        private int schematic;
        private boolean configured;

        CullPhase()
        {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState glass = Blocks.GLASS.defaultBlockState();
            BlockState[] base = { air, stone, glass };

            for (int code = 0; code < 729; ++code)
            {
                BlockState[] set = new BlockState[6];
                int rest = code;

                for (int i = 0; i < 6; ++i)
                {
                    set[i] = base[rest % 3];
                    rest /= 3;
                }

                this.neighbours.add(set);
            }

            BlockState[] specials = { Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                    Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP),
                    Blocks.OAK_LEAVES.defaultBlockState(), Blocks.WHITE_STAINED_GLASS.defaultBlockState(),
                    Blocks.ICE.defaultBlockState(), Blocks.SNOW.defaultBlockState() };

            for (BlockState background : new BlockState[] { air, stone })
            {
                for (int side = 0; side < 6; ++side)
                {
                    for (BlockState special : specials)
                    {
                        BlockState[] set = new BlockState[6];
                        java.util.Arrays.fill(set, background);
                        set[side] = special;
                        this.neighbours.add(set);
                    }
                }
            }

            this.clients.add(air);
            this.clients.add(Blocks.CAVE_AIR.defaultBlockState());
            this.clients.add(stone);
            this.clients.add(Blocks.WATER.defaultBlockState());
            this.clients.add(glass);

            this.schematics.add(stone);
            this.schematics.add(glass);
            this.schematics.add(Blocks.WHITE_STAINED_GLASS.defaultBlockState());
            this.schematics.add(Blocks.OAK_SLAB.defaultBlockState());
            this.schematics.add(Blocks.WATER.defaultBlockState());
            this.schematics.add(Blocks.LAVA.defaultBlockState());
            this.schematics.add(Blocks.OAK_LEAVES.defaultBlockState());
            this.schematics.add(air);
            this.schematics.add(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
        }

        @Override
        public String name()
        {
            return "d3";
        }

        @Override
        public boolean step(Minecraft mc) throws Exception
        {
            if (this.cull == null)
            {
                this.cull = ChunkRendererSchematicVbo.class.getDeclaredMethod("shouldCullOverlayPos", BlockPos.class,
                        BlockState.class, BlockState.class);
                this.cull.setAccessible(true);
                this.renderer = allocate(ChunkRendererSchematicVbo.class);
                this.view = (ScriptedView) allocate(ScriptedView.class);
                this.view.center = P;
                Field field = ChunkRendererSchematicVbo.class.getDeclaredField("schematicWorldView");
                field.setAccessible(true);
                field.set(this.renderer, this.view);
                this.saved = new boolean[] { LayerListConfigs.GHOST_LEAN_INNER_SIDES.getBooleanValue(),
                        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue(),
                        Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue(),
                        Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY_CULLING.getBooleanValue(),
                        Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.getBooleanValue() };
                this.savedRange = DataManager.getRenderLayerRange().toJson();
            }

            if (this.options >= 32)
            {
                return true;
            }

            if (! this.configured)
            {
                setOptions(this.options);
                setRange(this.range);
                this.configured = true;
            }

            BlockState stateClient = this.clients.get(this.client);
            BlockState stateSchematic = this.schematics.get(this.schematic);
            this.view.middle = stateSchematic;
            StringBuilder cull = new StringBuilder(this.neighbours.size());

            for (BlockState[] around : this.neighbours)
            {
                this.view.around = around;

                try
                {
                    cull.append(((Boolean) call(this.cull, this.renderer, P, stateSchematic, stateClient)) ? '1' : '0');
                }
                catch (Exception e)
                {
                    cull.append('E');
                }
            }

            row("d3|opt" + Integer.toBinaryString(32 | this.options).substring(1) + "|r" + this.range + "|c"
                    + this.client + "|s" + this.schematic, cull.toString());

            if (++this.schematic < this.schematics.size())
            {
                return false;
            }

            this.schematic = 0;

            if (++this.client < this.clients.size())
            {
                return false;
            }

            this.client = 0;
            this.configured = false;

            if (++this.range < 4)
            {
                return false;
            }

            this.range = 0;
            ++this.options;
            return false;
        }

        @Override
        public void finish(Minecraft mc)
        {
            if (this.saved != null)
            {
                LayerListConfigs.GHOST_LEAN_INNER_SIDES.setBooleanValue(this.saved[0]);
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(this.saved[1]);
                Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.setBooleanValue(this.saved[2]);
                Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY_CULLING.setBooleanValue(this.saved[3]);
                Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.setBooleanValue(this.saved[4]);
            }

            if (this.savedRange != null)
            {
                DataManager.getRenderLayerRange().fromJson(this.savedRange);
            }
        }

        /** Пять тумблеров битами: наш «экономный режим», translucent, inner sides, culling, fluids. */
        private static void setOptions(int bits)
        {
            LayerListConfigs.GHOST_LEAN_INNER_SIDES.setBooleanValue((bits & 16) != 0);
            Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue((bits & 8) != 0);
            Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.setBooleanValue((bits & 4) != 0);
            Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY_CULLING.setBooleanValue((bits & 2) != 0);
            Configs.Visuals.ENABLE_SCHEMATIC_FLUIDS.setBooleanValue((bits & 1) != 0);
        }

        private static void setRange(int which)
        {
            LayerRange range = DataManager.getRenderLayerRange();

            switch (which)
            {
                case 1 ->
                {
                    range.setLayerMode(LayerMode.SINGLE_LAYER, false);
                    range.setAxis(Direction.Axis.Y);
                    range.setLayerSingle(P.getY());
                }
                case 2 ->
                {
                    range.setLayerMode(LayerMode.SINGLE_LAYER, false);
                    range.setAxis(Direction.Axis.X);
                    range.setLayerSingle(P.getX());
                }
                case 3 ->
                {
                    range.setLayerMode(LayerMode.LAYER_RANGE, false);
                    range.setAxis(Direction.Axis.Z);
                    range.setLayerRangeMax(P.getZ() + 5);
                    range.setLayerRangeMin(P.getZ());
                    range.setLayerRangeMax(P.getZ() + 5);
                }
                default -> range.setLayerMode(LayerMode.ALL, false);
            }
        }
    }

    // ===================================================================================
    // E1: инструмент для копания
    // ===================================================================================

    private record Layout(String name, int selected, Map<Integer, ItemStack> slots) {}

    private final class ToolPhase implements Phase
    {
        private static final int X0 = 600;
        private static final int Y = 101;

        private final List<Block> blocks = List.of(Blocks.AIR, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
                Blocks.OBSIDIAN, Blocks.IRON_ORE, Blocks.DIAMOND_ORE, Blocks.ANCIENT_DEBRIS, Blocks.NETHERRACK,
                Blocks.END_STONE, Blocks.IRON_BLOCK, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.GRAVEL,
                Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.OAK_LOG, Blocks.OAK_PLANKS, Blocks.CRAFTING_TABLE, Blocks.GLASS,
                Blocks.OAK_LEAVES, Blocks.WHITE_WOOL, Blocks.COBWEB, Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK,
                Blocks.MOSS_BLOCK, Blocks.SCULK, Blocks.PACKED_ICE, Blocks.GLOWSTONE, Blocks.BAMBOO_BLOCK);
        private final List<Layout> layouts = new ArrayList<>();
        private Method pick;
        private int stage;
        private int layout;
        private int block;

        @Override
        public String name()
        {
            return "e1";
        }

        @Override
        public boolean step(Minecraft mc) throws Exception
        {
            MinecraftServer server = mc.getSingleplayerServer();

            switch (this.stage)
            {
                case 0 ->
                {
                    this.pick = declared("com.aell.layerlist.easyplace.ToolPick", "pick", BlockPos.class);
                    this.buildLayouts(mc);
                    server.execute(() -> {
                        command(server, "forceload add " + (X0 - 16) + " -16 " + (X0 + 80) + " 16");
                        command(server, "gamemode creative @a");
                        command(server, "clear @a");
                        // Клетки не должны меняться за время фазы: листва не опадает, лёд не тает
                        command(server, "gamerule random_tick_speed 0");
                        command(server, "gamerule randomTickSpeed 0");
                        command(server, "fill " + (X0 - 4) + " " + (Y - 1) + " -6 " + (X0 + 70) + " " + (Y - 1) + " 1 minecraft:stone");
                        ServerLevel level = server.overworld();

                        for (int i = 0; i < this.blocks.size(); ++i)
                        {
                            level.setBlock(cell(i), cellState(this.blocks.get(i)), Block.UPDATE_CLIENTS);
                        }

                        command(server, "tp @a " + (X0 + 0.5D) + " " + Y + " -3.5 0 30");
                    });
                    this.stage = 1;
                    DumpLegacy.this.until = Util.getMillis() + 2000L;
                }
                case 1 ->
                {
                    int seen = 0;

                    for (int i = 0; i < this.blocks.size(); ++i)
                    {
                        seen += mc.level.getBlockState(cell(i)).is(this.blocks.get(i)) ? 1 : 0;
                    }

                    if (seen < this.blocks.size())
                    {
                        CovOut.log("[legacy] e1: клетки на клиенте " + seen + "/" + this.blocks.size());
                        DumpLegacy.this.until = Util.getMillis() + 500L;
                        return false;
                    }

                    // Креатив: ToolPick ничего не делает — по строке на блок
                    for (int i = 0; i < this.blocks.size(); ++i)
                    {
                        this.runCase(mc, "creative", this.layouts.get(2), i);
                    }

                    server.execute(() -> command(server, "gamemode survival @a"));
                    this.stage = 2;
                    DumpLegacy.this.until = Util.getMillis() + 1000L;
                }
                case 2 ->
                {
                    if (mc.player.isCreative())
                    {
                        DumpLegacy.this.until = Util.getMillis() + 250L;
                        return false;
                    }

                    this.stage = 3;
                }
                case 3 ->
                {
                    if (this.layout >= this.layouts.size())
                    {
                        this.stage = 4;
                        return false;
                    }

                    boolean moved = this.runCase(mc, "survival", this.layouts.get(this.layout), this.block);

                    if (++this.block >= this.blocks.size())
                    {
                        this.block = 0;
                        ++this.layout;
                    }

                    // Обмен со слотом инвентаря ушёл пакетом на сервер; ждём его ответа, чтобы
                    // поправка с сервера не легла на следующий набор
                    if (moved)
                    {
                        DumpLegacy.this.until = Util.getMillis() + 500L;
                    }
                }
                default ->
                {
                    return true;
                }
            }

            return false;
        }

        @Override
        public void finish(Minecraft mc)
        {
            MinecraftServer server = mc.getSingleplayerServer();

            if (server != null)
            {
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "gamemode creative @a");
                    command(server, "gamerule random_tick_speed 3");
                    command(server, "gamerule randomTickSpeed 3");
                });
            }

            if (mc.player != null)
            {
                mc.player.getInventory().clearContent();
            }
        }

        /** Один случай; true — содержимое слотов поменялось (был обмен через сервер). */
        private boolean runCase(Minecraft mc, String mode, Layout layout, int blockIndex) throws Exception
        {
            Inventory inventory = mc.player.getInventory();
            inventory.clearContent();

            for (Map.Entry<Integer, ItemStack> entry : layout.slots().entrySet())
            {
                inventory.setItem(entry.getKey(), entry.getValue().copy());
            }

            inventory.setSelectedSlot(layout.selected());
            String before = slots(inventory);
            String result;

            try
            {
                result = String.valueOf(call(this.pick, null, cell(blockIndex)));
            }
            catch (Exception e)
            {
                result = "EXC:" + e.getClass().getSimpleName();
            }

            String after = slots(inventory);
            row("e1|" + mode + "|" + layout.name() + "|" + BuiltInRegistries.BLOCK.getKey(this.blocks.get(blockIndex)).getPath(),
                    result, "" + inventory.getSelectedSlot(), stackLabel(mc.player.getMainHandItem()), after);

            return ! before.equals(after);
        }

        private static BlockState cellState(Block block)
        {
            BlockState state = block.defaultBlockState();
            return state.hasProperty(BlockStateProperties.PERSISTENT) ? state.setValue(BlockStateProperties.PERSISTENT, true) : state;
        }

        private BlockPos cell(int index)
        {
            return new BlockPos(X0 + 2 * index, Y, 0);
        }

        private static String slots(Inventory inventory)
        {
            StringBuilder sb = new StringBuilder();

            for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
            {
                ItemStack stack = inventory.getItem(slot);

                if (! stack.isEmpty())
                {
                    sb.append(slot).append('=').append(stackLabel(stack)).append(' ');
                }
            }

            return sb.toString().trim();
        }

        private static String stackLabel(ItemStack stack)
        {
            if (stack.isEmpty())
            {
                return "empty";
            }

            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + (stack.getDamageValue() > 0 ? "@" + stack.getDamageValue() : "")
                    + (stack.isEnchanted() ? "+E" : "") + (stack.getCount() > 1 ? "x" + stack.getCount() : "");
        }

        private void buildLayouts(Minecraft mc)
        {
            Holder<Enchantment> efficiency = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.EFFICIENCY);
            ItemStack effIron = new ItemStack(Items.IRON_PICKAXE);
            effIron.enchant(efficiency, 5);
            ItemStack effDiamondShovel = new ItemStack(Items.DIAMOND_SHOVEL);
            effDiamondShovel.enchant(efficiency, 5);
            ItemStack brokenDiamond = new ItemStack(Items.DIAMOND_PICKAXE);
            brokenDiamond.setDamageValue(brokenDiamond.getMaxDamage() - 1);
            ItemStack brokenNetherite = new ItemStack(Items.NETHERITE_PICKAXE);
            brokenNetherite.setDamageValue(brokenNetherite.getMaxDamage() - 1);
            ItemStack effBroken = new ItemStack(Items.NETHERITE_PICKAXE);
            effBroken.enchant(efficiency, 3);
            effBroken.setDamageValue(effBroken.getMaxDamage() - 1);

            add("empty", 0);
            add("hand-dirt", 0, 0, new ItemStack(Items.DIRT, 32));
            add("pickaxes", 0, 1, Items.WOODEN_PICKAXE, 2, Items.STONE_PICKAXE, 3, Items.COPPER_PICKAXE, 4, Items.IRON_PICKAXE,
                    5, Items.GOLDEN_PICKAXE, 6, Items.DIAMOND_PICKAXE, 7, Items.NETHERITE_PICKAXE);
            add("pickaxes-hold-iron", 4, 1, Items.WOODEN_PICKAXE, 2, Items.STONE_PICKAXE, 3, Items.COPPER_PICKAXE, 4,
                    Items.IRON_PICKAXE, 5, Items.GOLDEN_PICKAXE, 6, Items.DIAMOND_PICKAXE);
            add("shovels", 0, 1, Items.WOODEN_SHOVEL, 2, Items.STONE_SHOVEL, 3, Items.COPPER_SHOVEL, 4, Items.IRON_SHOVEL,
                    5, Items.GOLDEN_SHOVEL, 6, Items.DIAMOND_SHOVEL, 7, Items.NETHERITE_SHOVEL);
            add("axes", 0, 1, Items.WOODEN_AXE, 2, Items.STONE_AXE, 4, Items.IRON_AXE, 5, Items.GOLDEN_AXE, 6, Items.DIAMOND_AXE,
                    7, Items.NETHERITE_AXE);
            add("hoes", 0, 1, Items.WOODEN_HOE, 2, Items.STONE_HOE, 4, Items.IRON_HOE, 5, Items.GOLDEN_HOE, 6, Items.DIAMOND_HOE,
                    7, Items.NETHERITE_HOE);
            add("swords-shears", 0, 1, Items.WOODEN_SWORD, 2, Items.IRON_SWORD, 3, Items.DIAMOND_SWORD, 5, Items.SHEARS);
            add("one-of-each", 8, 0, Items.DIAMOND_PICKAXE, 1, Items.DIAMOND_SHOVEL, 2, Items.DIAMOND_AXE, 3, Items.DIAMOND_HOE,
                    4, Items.DIAMOND_SWORD, 5, Items.SHEARS);
            add("one-of-each-hold-shovel", 1, 0, Items.DIAMOND_PICKAXE, 1, Items.DIAMOND_SHOVEL, 2, Items.DIAMOND_AXE, 3,
                    Items.DIAMOND_HOE, 4, Items.DIAMOND_SWORD, 5, Items.SHEARS);
            add("eff5-iron-vs-diamond", 0, 1, effIron, 2, Items.DIAMOND_PICKAXE);
            add("diamond-vs-eff5-iron", 0, 1, Items.DIAMOND_PICKAXE, 2, effIron);
            add("broken-diamond-vs-iron", 0, 1, brokenDiamond, 2, Items.IRON_PICKAXE);
            add("broken-netherite-only", 0, 0, new ItemStack(Items.DIRT, 5), 2, brokenNetherite);
            add("tie-two-diamond", 0, 2, Items.DIAMOND_PICKAXE, 5, Items.DIAMOND_PICKAXE);
            add("tie-with-hand", 0, 0, Items.DIAMOND_PICKAXE, 3, Items.DIAMOND_PICKAXE);
            add("golden-vs-stone", 0, 1, Items.GOLDEN_PICKAXE, 2, Items.STONE_PICKAXE);
            add("inventory-only", 0, 9, Items.DIAMOND_PICKAXE, 20, Items.DIAMOND_SHOVEL, 35, Items.DIAMOND_AXE);
            add("inventory-full-hotbar", 3, 0, new ItemStack(Items.DIRT), 1, new ItemStack(Items.DIRT), 2,
                    new ItemStack(Items.DIRT), 3, new ItemStack(Items.DIRT), 4, new ItemStack(Items.DIRT), 5,
                    new ItemStack(Items.DIRT), 6, new ItemStack(Items.DIRT), 7, new ItemStack(Items.DIRT), 8,
                    new ItemStack(Items.DIRT), 9, Items.DIAMOND_PICKAXE, 12, Items.DIAMOND_SHOVEL, 30, Items.SHEARS);
            add("inventory-beats-hotbar", 0, 1, Items.STONE_PICKAXE, 15, Items.DIAMOND_PICKAXE, 16, Items.WOODEN_SHOVEL);
            add("hotbar-beats-inventory", 0, 2, Items.NETHERITE_PICKAXE, 12, Items.DIAMOND_PICKAXE, 13, Items.IRON_SHOVEL);
            add("wrong-tools", 0, 1, Items.DIAMOND_SHOVEL, 2, Items.DIAMOND_AXE, 3, Items.DIAMOND_HOE, 4, Items.DIAMOND_SWORD);
            add("shears-sword", 0, 1, Items.SHEARS, 2, Items.DIAMOND_SWORD);
            add("eff-shovel-in-inventory", 0, 1, Items.IRON_SHOVEL, 22, effDiamondShovel, 23, effBroken);
            add("hand-wooden-pick", 0, 0, Items.WOODEN_PICKAXE, 4, Items.DIAMOND_SHOVEL, 5, Items.DIAMOND_AXE);
            add("offhand-only", 0, 40, Items.DIAMOND_PICKAXE, 36, Items.DIAMOND_BOOTS);
            add("hand-wooden-obsidian", 0, 0, Items.WOODEN_PICKAXE, 10, Items.DIAMOND_PICKAXE, 11, Items.IRON_PICKAXE);
            add("stack-of-tools-mixed", 6, 0, Items.STICK, 1, Items.IRON_SWORD, 2, Items.WOODEN_AXE, 3, Items.STONE_SHOVEL, 6,
                    Items.SHEARS, 7, Items.GOLDEN_HOE, 8, Items.COPPER_PICKAXE);
        }

        /** Пары «слот, предмет или стопка». */
        private void add(String name, int selected, Object... pairs)
        {
            Map<Integer, ItemStack> slots = new java.util.TreeMap<>();

            for (int i = 0; i + 1 < pairs.length; i += 2)
            {
                Object what = pairs[i + 1];
                ItemStack stack = what instanceof ItemStack s ? s : new ItemStack((Item) what);
                slots.put((Integer) pairs[i], stack);
            }

            this.layouts.add(new Layout(name, selected, slots));
        }
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("команда не прошла (" + command + "): " + e);
        }
    }
}
