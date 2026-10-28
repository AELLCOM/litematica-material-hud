package com.aell.layerlist.mixin.schematics;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Текстуры «картинки в картинке» превью схем. 1.21.11 обфусцирована: имена полей Mojang в собранной игре
 * другие, поэтому не отражением (как в 26.x), а аксессорами — их Mixin переводит в рабочие имена.
 */
@Environment(EnvType.CLIENT)
@Mixin(PictureInPictureRenderer.class)
public interface IMixinPictureInPictureRenderer
{
    @Accessor("texture")
    @Nullable GpuTexture layerlist$texture();

    @Accessor("texture")
    void layerlist$setTexture(@Nullable GpuTexture texture);

    @Accessor("textureView")
    @Nullable GpuTextureView layerlist$textureView();

    @Accessor("textureView")
    void layerlist$setTextureView(@Nullable GpuTextureView view);

    @Accessor("depthTexture")
    @Nullable GpuTexture layerlist$depthTexture();

    @Accessor("depthTexture")
    void layerlist$setDepthTexture(@Nullable GpuTexture texture);

    @Accessor("depthTextureView")
    @Nullable GpuTextureView layerlist$depthTextureView();

    @Accessor("depthTextureView")
    void layerlist$setDepthTextureView(@Nullable GpuTextureView view);
}
