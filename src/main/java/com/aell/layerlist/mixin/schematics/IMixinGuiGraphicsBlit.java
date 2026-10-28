package com.aell.layerlist.mixin.schematics;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Копия GPU-текстуры в GUI (кадр Voxy в превью «Выделения»): в 26.x это открытый {@code blit(GpuTextureView, …)}, в
 * 1.21.11 — только закрытый {@code submitBlit}. Invoker — 1.21.11 обфусцирована.
 */
@Environment(EnvType.CLIENT)
@Mixin(GuiGraphics.class)
public interface IMixinGuiGraphicsBlit
{
    @Invoker("submitBlit")
    void layerlist$submitBlit(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler, int x0, int y0, int x1, int y1,
            float u0, float u1, float v0, float v1, int color);
}
