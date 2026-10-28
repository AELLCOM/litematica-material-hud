package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerBedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SegmentableBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class BlockFamilies
{
    private BlockFamilies() {}

    public static @Nullable IntegerProperty countProperty(BlockState state)
    {
        if (state.getBlock() instanceof CandleBlock)
        {
            return CandleBlock.CANDLES;
        }

        if (state.getBlock() instanceof SeaPickleBlock)
        {
            return SeaPickleBlock.PICKLES;
        }

        if (state.getBlock() instanceof TurtleEggBlock)
        {
            return TurtleEggBlock.EGGS;
        }

        if (state.getBlock() instanceof SegmentableBlock segmentable)
        {
            return segmentable.getSegmentAmountProperty();
        }

        if (state.getBlock() instanceof SnowLayerBlock)
        {
            return SnowLayerBlock.LAYERS;
        }

        return null;
    }

    public static boolean isMultiface(BlockState state)
    {
        return state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof VineBlock;
    }

    public static boolean solverHandles(BlockState state)
    {
        return ! isMultiface(state) && ! (state.getBlock() instanceof SlabBlock);
    }

    public static boolean isSegmentable(BlockState state)
    {
        return state.getBlock() instanceof SegmentableBlock;
    }

    public static @Nullable Direction segmentFacing(BlockState state)
    {
        if (state.getBlock() instanceof FlowerBedBlock)
        {
            return state.getValue(FlowerBedBlock.FACING);
        }

        if (state.getBlock() instanceof LeafLitterBlock)
        {
            return state.getValue(LeafLitterBlock.FACING);
        }

        return null;
    }

    public static boolean isFaceAttached(BlockState state)
    {
        return state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock
                && state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.hasProperty(HorizontalDirectionalBlock.FACING);
    }

    public static AttachFace attachFace(BlockState state)
    {
        return state.getValue(BlockStateProperties.ATTACH_FACE);
    }

    public static Direction horizontalFacing(BlockState state)
    {
        return state.getValue(HorizontalDirectionalBlock.FACING);
    }

    public static int count(BlockState state, IntegerProperty property)
    {
        return state.hasProperty(property) ? state.getValue(property) : 0;
    }
}
