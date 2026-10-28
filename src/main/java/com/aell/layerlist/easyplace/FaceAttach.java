package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class FaceAttach
{
    private FaceAttach() {}

    public static boolean is(BlockState state)
    {
        return state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
    }

    public static Direction support(BlockState state)
    {
        return switch (state.getValue(BlockStateProperties.ATTACH_FACE))
        {
            case CEILING -> Direction.UP;
            case FLOOR -> Direction.DOWN;
            case WALL -> state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        };
    }

    public static @Nullable LookSpoof.Pose pose(BlockState state, boolean sneak)
    {
        Direction support = support(state);
        AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);

        if (face == AttachFace.WALL)
        {
            return new LookSpoof.Pose(support.toYRot(), 0.0F, sneak);
        }

        float yaw = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();

        return new LookSpoof.Pose(yaw, support == Direction.DOWN ? 90.0F : -90.0F, sneak);
    }

    public static Vec3 spot(BlockPos target, Direction support)
    {
        Direction face = support.getOpposite();

        return new Vec3(target.getX() + 0.5D + face.getStepX() * 0.5D,
                target.getY() + 0.5D + face.getStepY() * 0.5D,
                target.getZ() + 0.5D + face.getStepZ() * 0.5D);
    }
}
