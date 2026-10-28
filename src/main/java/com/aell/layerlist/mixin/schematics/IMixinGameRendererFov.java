package com.aell.layerlist.mixin.schematics;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.21.11: угол обзора кадра считает закрытый {@code GameRenderer.getFov} (в 26.x его отдаёт камера —
 * {@code Camera.getFov}). Нужен лучу курсора и облёту, чтобы мышь попадала туда же, куда смотрит кадр.
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public interface IMixinGameRendererFov
{
    @Invoker("getFov")
    float layerlist$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
