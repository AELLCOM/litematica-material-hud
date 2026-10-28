package com.aell.layerlist.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(SpriteContents.class)
public interface IMixinSpriteContents
{
    @Accessor("originalImage")
    NativeImage layerlist$originalImage();
}
