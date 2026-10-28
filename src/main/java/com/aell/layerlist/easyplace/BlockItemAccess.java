package com.aell.layerlist.easyplace;

import com.aell.layerlist.mixin.easyplace.IMixinBlockItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class BlockItemAccess
{
    private BlockItemAccess() {}

    public static @Nullable BlockState placementState(ItemStack stack, BlockPlaceContext context)
    {
        if (! (stack.getItem() instanceof BlockItem item))
        {
            return null;
        }

        try
        {
            return ((IMixinBlockItem) item).layerlist$getPlacementState(context);
        }
        catch (RuntimeException | LinkageError e)
        {
            BlockState fallback = item.getBlock().getStateForPlacement(context);

            return fallback != null && fallback.canSurvive(context.getLevel(), context.getClickedPos())
                    ? fallback
                    : null;
        }
    }
}
