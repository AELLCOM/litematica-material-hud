package com.aell.layerlist.mixin.voxy;

import com.aell.layerlist.compat.VoxyPreview;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Метка «Voxy действительно рисовал кадр превью»: до вывода геометрии он дошёл. Кадр могут отменить другие моды (или
 * Voxy пуст) — тогда метки нет, и превью рисует себя само ({@link VoxyPreview}), не зная, кто и почему отказал.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class MixinVoxyDrewFrame
{
    @Inject(method = "renderTerrain", at = @At("HEAD"), require = 0)
    private void layerlist$voxyDrewPreview(CallbackInfo ci)
    {
        if (VoxyPreview.rendering())
        {
            VoxyPreview.drew();
        }
    }
}
