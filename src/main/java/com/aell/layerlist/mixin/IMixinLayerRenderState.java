package com.aell.layerlist.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface IMixinLayerRenderState
{
    @Accessor("tintLayers")
    int[] layerlist$tintLayers();
}
