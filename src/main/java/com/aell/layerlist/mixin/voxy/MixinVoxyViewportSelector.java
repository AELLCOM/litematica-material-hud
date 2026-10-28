package com.aell.layerlist.mixin.voxy;

import com.aell.layerlist.compat.VoxyPreview;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Кадр превью «Выделения»: Voxy рисует в свой отдельный вид (свои матрицы и размер), а не в вид игры. */
@Environment(EnvType.CLIENT)
@Mixin(value = ViewportSelector.class, remap = false)
public abstract class MixinVoxyViewportSelector
{
    @Inject(method = "getViewport", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$previewViewport(CallbackInfoReturnable<Viewport<?>> cir)
    {
        if (VoxyPreview.rendering() && VoxyPreview.viewport(this) instanceof Viewport<?> viewport)
        {
            cir.setReturnValue(viewport);
        }
    }
}
