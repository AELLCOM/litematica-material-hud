package com.aell.layerlist.mixin.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Environment(EnvType.CLIENT)
@Mixin(BlockItem.class)
public interface IMixinBlockItem
{
    @Invoker("getPlacementState")
    @Nullable BlockState layerlist$getPlacementState(BlockPlaceContext context);
}
