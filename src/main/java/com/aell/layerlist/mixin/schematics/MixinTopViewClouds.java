package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Вид сверху: облака (опция {@code schematicsTopHideClouds}) выключаются в снимке настроек кадра,
 * а не в самих настройках игрока — ничего не нужно возвращать и ничего не попадёт в
 * {@code options.txt}. Косметика — {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class MixinTopViewClouds
{
    @Inject(method = "extractOptions", at = @At("TAIL"), require = 0)
    private void layerlist$hideClouds(CallbackInfo ci)
    {
        if (TopViewCamera.hideClouds())
        {
            ((GameRenderer) (Object) this).gameRenderState().optionsRenderState.cloudStatus = CloudStatus.OFF;
        }
    }
}
