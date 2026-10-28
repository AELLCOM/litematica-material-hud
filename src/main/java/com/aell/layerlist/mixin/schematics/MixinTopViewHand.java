package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Вид сверху: без руки с предметом. Ваниль решает про руку по режиму камеры из настроек (он
 * остаётся «от первого лица»), а не по тому, где камера на самом деле, — рука висела бы поверх
 * вида с высоты. То же в облёте ({@link OrbitCamera}). Косметика — {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class MixinTopViewHand
{
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$hideHand(CameraRenderState cameraState, float partialTick, Matrix4fc projection,
            CallbackInfo ci)
    {
        if (TopViewCamera.active() || OrbitCamera.active())
        {
            ci.cancel();
        }
    }
}
