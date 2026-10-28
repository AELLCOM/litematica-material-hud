package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Вид сверху: облака (опция {@code schematicsTopHideClouds}) выключаются в снимке настроек кадра,
 * а не в самих настройках игрока — ничего не нужно возвращать и ничего не попадёт в
 * {@code options.txt}. Косметика — {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(Options.class)
public abstract class MixinTopViewClouds
{
    // 1.21.11: облака LevelRenderer спрашивает у Options.getCloudsType каждый кадр (в 26.x — снимок
    // настроек в GameRenderState). Сама настройка игрока не меняется — подменяется только ответ.
    @Inject(method = "getCloudsType", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$hideClouds(CallbackInfoReturnable<CloudStatus> cir)
    {
        if (TopViewCamera.hideClouds())
        {
            cir.setReturnValue(CloudStatus.OFF);
        }
    }
}
