package com.aell.layerlist.mixin.voxy;

import com.aell.layerlist.compat.VoxyPreview;
import me.cortex.voxy.client.VoxyClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * В кадре превью Voxy рисует и ближние секции (обычно их рисует Sodium, а Voxy пропускает): в окне превью мира игры
 * нет, без этого посередине была бы дыра.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = VoxyClient.class, remap = false, priority = 2000)
public abstract class MixinVoxyClient
{
    @Inject(method = "disableSodiumChunkRender", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$previewDrawsNearSections(CallbackInfoReturnable<Boolean> cir)
    {
        if (VoxyPreview.rendering())
        {
            cir.setReturnValue(true);
        }
    }
}
