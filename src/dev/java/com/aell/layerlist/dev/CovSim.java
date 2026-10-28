package com.aell.layerlist.dev;

import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.SimPlaceContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Полный перебор кликов по той же геометрии, что у {@link com.aell.layerlist.easyplace.PlaceSolver},
 * но без его отбора свойств: нужен, чтобы отличить «решатель не нашёл» от «таким кликом это
 * состояние не получить вовсе». Ничего не ставит — только симуляция установки на клиенте.
 */
@Environment(EnvType.CLIENT)
public final class CovSim
{
    public record Result(@Nullable BlockState expected, @Nullable LookSpoof.Pose pose,
            @Nullable BlockHitResult hit, int matched, int total, int probes, boolean exact) {}

    private static final float[] YAW_4 = { 0.0F, 90.0F, 180.0F, -90.0F };
    private static final float[] YAW_16 = new float[16];
    private static final float[] PITCH = { 0.0F, -90.0F, 90.0F, -45.0F, 45.0F };
    private static final double[] SPOT = { 0.25D, 0.75D };

    static
    {
        for (int i = 0; i < 16; ++i)
        {
            YAW_16[i] = i * 22.5F;
        }
    }

    private CovSim() {}

    public static Result sweep(ClientLevel level, LocalPlayer player, InteractionHand hand,
            ItemStack stack, BlockPos target, BlockState wanted, int budget)
    {
        List<Property<?>> props = new ArrayList<>(wanted.getBlock().getStateDefinition().getProperties());
        List<BlockHitResult> hits = hits(level, target);
        float[] yaws = wanted.hasProperty(BlockStateProperties.ROTATION_16) ? YAW_16 : YAW_4;

        BlockState bestState = null;
        LookSpoof.Pose bestPose = null;
        BlockHitResult bestHit = null;
        int bestMatched = -1;
        int probes = 0;

        for (float yaw : yaws)
        {
            for (float pitch : PITCH)
            {
                for (int s = 0; s < 2; ++s)
                {
                    LookSpoof.Pose pose = new LookSpoof.Pose(yaw, pitch, s == 1);

                    for (BlockHitResult hit : hits)
                    {
                        if (++probes > budget)
                        {
                            return new Result(bestState, bestPose, bestHit, Math.max(bestMatched, 0),
                                    props.size(), probes, false);
                        }

                        BlockState state = LookSpoof.silent(player, pose,
                                () -> place(level, player, hand, stack, target, wanted, hit, pose));

                        if (state == null)
                        {
                            continue;
                        }

                        int matched = matched(state, wanted, props);

                        if (matched > bestMatched)
                        {
                            bestMatched = matched;
                            bestState = state;
                            bestPose = pose;
                            bestHit = hit;
                        }

                        if (matched == props.size())
                        {
                            return new Result(state, pose, hit, matched, props.size(), probes, true);
                        }
                    }
                }
            }
        }

        return new Result(bestState, bestPose, bestHit, Math.max(bestMatched, 0), props.size(),
                probes, false);
    }

    private static @Nullable BlockState place(ClientLevel level, LocalPlayer player,
            InteractionHand hand, ItemStack stack, BlockPos target, BlockState wanted,
            BlockHitResult hit, LookSpoof.Pose pose)
    {
        try
        {
            SimPlaceContext context = SimPlaceContext.of(level, player, hand, stack, hit, pose);

            if (! context.getClickedPos().equals(target))
            {
                return null;
            }

            BlockState state = context.placementState();

            return state != null && state.is(wanted.getBlock()) ? state : null;
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Сколько свойств сошлось: нужно, чтобы сравнивать станции между собой. */
    public static int matched(BlockState state, BlockState wanted)
    {
        return matched(state, wanted,
                new ArrayList<>(wanted.getBlock().getStateDefinition().getProperties()));
    }

    private static int matched(BlockState state, BlockState wanted, List<Property<?>> props)
    {
        int matched = 0;

        for (Property<?> property : props)
        {
            if (same(state, wanted, property))
            {
                ++matched;
            }
        }

        return matched;
    }

    private static <T extends Comparable<T>> boolean same(BlockState a, BlockState b, Property<T> p)
    {
        if (! a.hasProperty(p) || ! b.hasProperty(p))
        {
            return true;
        }

        return a.getValue(p).equals(b.getValue(p));
    }

    /** Свойства, которые не сошлись: их имена идут в дамп. */
    public static List<Property<?>> diff(BlockState a, BlockState b)
    {
        List<Property<?>> out = new ArrayList<>();

        for (Property<?> property : b.getBlock().getStateDefinition().getProperties())
        {
            if (! same(a, b, property))
            {
                out.add(property);
            }
        }

        return out;
    }

    private static List<BlockHitResult> hits(ClientLevel level, BlockPos target)
    {
        List<BlockHitResult> out = new ArrayList<>();

        for (Direction side : Direction.values())
        {
            BlockPos neighbour = target.relative(side);

            if (level.getBlockState(neighbour).isAir())
            {
                continue;
            }

            add(out, neighbour, side.getOpposite());
        }

        for (Direction face : Direction.values())
        {
            add(out, target, face);
        }

        return out;
    }

    private static void add(List<BlockHitResult> out, BlockPos pos, Direction face)
    {
        for (double u : SPOT)
        {
            for (double v : SPOT)
            {
                out.add(new BlockHitResult(spot(pos, face, u, v), face, pos, false));
            }
        }
    }

    private static Vec3 spot(BlockPos pos, Direction face, double u, double v)
    {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        return switch (face.getAxis())
        {
            case X -> new Vec3(x + (face == Direction.EAST ? 1.0D : 0.0D), y + u, z + v);
            case Y -> new Vec3(x + u, y + (face == Direction.UP ? 1.0D : 0.0D), z + v);
            case Z -> new Vec3(x + u, y + v, z + (face == Direction.SOUTH ? 1.0D : 0.0D));
        };
    }
}
