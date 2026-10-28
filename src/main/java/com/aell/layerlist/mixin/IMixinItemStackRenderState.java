package com.aell.layerlist.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(ItemStackRenderState.class)
public interface IMixinItemStackRenderState
{
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] layerlist$layers();

    @Accessor("activeLayerCount")
    int layerlist$activeLayerCount();
}
