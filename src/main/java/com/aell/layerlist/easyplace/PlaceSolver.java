package com.aell.layerlist.easyplace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class PlaceSolver
{
    public record Plan(BlockHitResult hit, LookSpoof.Pose pose, BlockState expected, int matched,
            int total, List<Property<?>> missed)
    {
        public boolean perfect()
        {
            return this.missed.isEmpty();
        }

        public boolean misplaced()
        {
            for (Property<?> property : this.missed)
            {
                if (Controllable.orientation(property))
                {
                    return true;
                }
            }

            return false;
        }
    }

    private static final float[] YAW_16 = new float[16];
    private static final float[] YAW_4 = { 0.0F, 90.0F, 180.0F, -90.0F };
    private static final float[] PITCH = { 0.0F, -90.0F, 90.0F, -45.0F, 45.0F };
    private static final double[] SPOT = { 0.25D, 0.75D };

    private static final int BUDGET = 4096;

    static
    {
        for (int i = 0; i < 16; ++i)
        {
            YAW_16[i] = i * 22.5F;
        }
    }

    private PlaceSolver() {}

    public static @Nullable Plan solve(ClientLevel level, LocalPlayer player, InteractionHand hand,
            ItemStack stack, BlockPos target, BlockState wanted, @Nullable BlockHitResult seed)
    {
        if (! (stack.getItem() instanceof BlockItem) || wanted.getBlock().asItem() != stack.getItem())
        {
            return null;
        }

        List<Property<?>> props = Controllable.scored(wanted);

        if (props.isEmpty())
        {
            return null;
        }

        List<Anchor> anchors = anchors(level, target, seed);

        if (anchors.isEmpty())
        {
            return null;
        }

        List<LookSpoof.Pose> poses = poses(player, wanted);
        Plan[] best = new Plan[1];
        int[] spent = new int[1];

        for (LookSpoof.Pose pose : poses)
        {
            Plan perfect = LookSpoof.silent(player, pose, () ->
                    sweep(level, player, hand, stack, target, wanted, props, anchors, pose, best, spent));

            if (perfect != null)
            {
                return perfect;
            }

            if (spent[0] > BUDGET)
            {
                break;
            }
        }

        return best[0];
    }

    public static @Nullable Plan attempt(ClientLevel level, LocalPlayer player, InteractionHand hand,
            ItemStack stack, BlockPos target, BlockState wanted, BlockHitResult hit, LookSpoof.Pose pose)
    {
        if (! (stack.getItem() instanceof BlockItem) || wanted.getBlock().asItem() != stack.getItem())
        {
            return null;
        }

        List<Property<?>> props = Controllable.scored(wanted);

        if (props.isEmpty())
        {
            return null;
        }

        return LookSpoof.silent(player, pose,
                () -> evaluate(level, player, hand, stack, target, wanted, props, hit, pose));
    }

    private static @Nullable Plan sweep(ClientLevel level, LocalPlayer player, InteractionHand hand,
            ItemStack stack, BlockPos target, BlockState wanted, List<Property<?>> props,
            List<Anchor> anchors, LookSpoof.Pose pose, Plan[] best, int[] spent)
    {
        for (Anchor anchor : anchors)
        {
            if (anchor.mustSneak && ! pose.sneak())
            {
                continue;
            }

            for (Vec3 hitVec : anchor.spots(wanted))
            {
                if (++spent[0] > BUDGET)
                {
                    return null;
                }

                BlockHitResult hit = new BlockHitResult(hitVec, anchor.face, anchor.pos, false);
                Plan plan = evaluate(level, player, hand, stack, target, wanted, props, hit, pose);

                if (plan == null)
                {
                    continue;
                }

                if (plan.perfect())
                {
                    return plan;
                }

                if (best[0] == null || plan.matched() > best[0].matched())
                {
                    best[0] = plan;
                }
            }
        }

        return null;
    }

    private static @Nullable Plan evaluate(ClientLevel level, LocalPlayer player, InteractionHand hand,
            ItemStack stack, BlockPos target, BlockState wanted, List<Property<?>> props,
            BlockHitResult hit, LookSpoof.Pose pose)
    {
        SimPlaceContext context = SimPlaceContext.of(level, player, hand, stack, hit, pose);

        if (! context.getClickedPos().equals(target))
        {
            return null;
        }

        BlockState state;

        try
        {
            state = context.placementState();

            // Рельс: ванильная установка отдаёт только ось из угла взгляда, а поворот,
            // подъём и разворот на соседа блок получает уже в мире — на сервере, куда
            // симуляция не заходит. Досчитываем это здесь, иначе решатель сверял бы
            // со схемой заготовку, а не то, что игрок увидит.
            state = state == null ? null : Rails.settle(level, target, state);
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }

        if (state == null || ! state.is(wanted.getBlock()))
        {
            return null;
        }

        List<Property<?>> missed = new ArrayList<>();

        for (Property<?> property : props)
        {
            if (! sameValue(state, wanted, property))
            {
                missed.add(property);
            }
        }

        return new Plan(hit, pose, state, props.size() - missed.size(), props.size(), missed);
    }

    private static <T extends Comparable<T>> boolean sameValue(BlockState a, BlockState b,
            Property<T> property)
    {
        if (! a.hasProperty(property) || ! b.hasProperty(property))
        {
            return true;
        }

        return a.getValue(property).equals(b.getValue(property));
    }

    private record Anchor(BlockPos pos, Direction face, boolean mustSneak, boolean seed)
    {
        List<Vec3> spots(BlockState wanted)
        {
            List<Vec3> out = new ArrayList<>();
            boolean fine = needsFineHit(wanted);
            int count = fine ? SPOT.length : 1;

            for (int a = 0; a < count; ++a)
            {
                for (int b = 0; b < count; ++b)
                {
                    out.add(this.spot(SPOT[a], SPOT[b]));
                }
            }

            return out;
        }

        Vec3 spot(double u, double v)
        {
            double x = this.pos.getX();
            double y = this.pos.getY();
            double z = this.pos.getZ();

            return switch (this.face.getAxis())
            {
                case X -> new Vec3(x + (this.face == Direction.EAST ? 1.0D : 0.0D), y + u, z + v);
                case Y -> new Vec3(x + u, y + (this.face == Direction.UP ? 1.0D : 0.0D), z + v);
                case Z -> new Vec3(x + u, y + v, z + (this.face == Direction.SOUTH ? 1.0D : 0.0D));
            };
        }
    }

    private static boolean needsFineHit(BlockState wanted)
    {
        return wanted.hasProperty(BlockStateProperties.HALF)
                || wanted.hasProperty(BlockStateProperties.SLAB_TYPE)
                || wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || wanted.hasProperty(BlockStateProperties.DOOR_HINGE);
    }

    private static List<Anchor> anchors(ClientLevel level, BlockPos target, @Nullable BlockHitResult seed)
    {
        List<Anchor> out = new ArrayList<>();
        BlockState here = level.getBlockState(target);

        for (Direction side : Direction.values())
        {
            BlockPos neighbour = target.relative(side);
            BlockState state = level.getBlockState(neighbour);

            if (state.isAir())
            {
                continue;
            }

            Direction face = side.getOpposite();
            boolean isSeed = seed != null && seed.getBlockPos().equals(neighbour)
                    && seed.getDirection() == face;

            out.add(new Anchor(neighbour, face, Interactive.eatsRightClick(level, neighbour, state),
                    isSeed));
        }

        for (Direction face : Direction.values())
        {
            boolean isSeed = seed != null && seed.getBlockPos().equals(target)
                    && seed.getDirection() == face;

            out.add(new Anchor(target, face, Interactive.eatsRightClick(level, target, here), isSeed));
        }

        // Якоря без приседа — раньше: клик по интерактивному блоку живёт на присед-спуфе,
        // и там, где есть равноценный чистый якорь (пол, стена), лучше не рисковать им
        out.sort(Comparator.comparing((Anchor a) -> a.seed() ? 0 : 1)
                .thenComparing(a -> a.mustSneak() ? 1 : 0)
                .thenComparing(a -> a.pos().equals(target) ? 1 : 0));

        return out;
    }

    private static List<LookSpoof.Pose> poses(LocalPlayer player, BlockState wanted)
    {
        float yawNow = player.getYRot();
        float pitchNow = player.getXRot();
        boolean sneakNow = player.isShiftKeyDown();

        List<LookSpoof.Pose> out = new ArrayList<>();

        out.add(new LookSpoof.Pose(yawNow, pitchNow, sneakNow));

        // «Не поворачивать игрока»: перебираем только то, что даёт нынешний угол взгляда
        if (! Pacing.mayTurn())
        {
            out.add(new LookSpoof.Pose(yawNow, pitchNow, ! sneakNow));

            return out;
        }

        float[] yaws = wanted.hasProperty(BlockStateProperties.ROTATION_16) ? YAW_16 : YAW_4;
        float[] pitches = needsPitch(wanted) ? PITCH : new float[] { pitchNow };

        for (float yaw : yaws)
        {
            for (float pitch : pitches)
            {
                out.add(new LookSpoof.Pose(yaw, pitch, sneakNow));
                out.add(new LookSpoof.Pose(yaw, pitch, ! sneakNow));
            }
        }

        out.sort(Comparator.comparingDouble((LookSpoof.Pose p) -> p.sneak() == sneakNow ? 0.0D : 1.0D)
                .thenComparingDouble(p -> Math.abs(wrap(p.pitch() - pitchNow)))
                .thenComparingDouble(p -> Math.abs(wrap(p.yaw() - yawNow))));

        return out;
    }

    private static boolean needsPitch(BlockState wanted)
    {
        return wanted.hasProperty(BlockStateProperties.FACING)
                || wanted.hasProperty(BlockStateProperties.FACING_HOPPER)
                || wanted.hasProperty(BlockStateProperties.ATTACH_FACE)
                || wanted.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
                || wanted.hasProperty(BlockStateProperties.ORIENTATION)
                || wanted.hasProperty(BlockStateProperties.AXIS)
                || wanted.hasProperty(BlockStateProperties.BELL_ATTACHMENT)
                || wanted.hasProperty(BlockStateProperties.HANGING);
    }

    private static double wrap(double degrees)
    {
        double value = degrees % 360.0D;

        if (value > 180.0D)
        {
            value -= 360.0D;
        }

        if (value < -180.0D)
        {
            value += 360.0D;
        }

        return value;
    }
}
