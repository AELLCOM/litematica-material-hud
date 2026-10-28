package com.aell.layerlist.mixin;

import com.aell.layerlist.hud.EscGuard;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Меню паузы не открывается сразу после выхода из хранилища ({@link EscGuard}): лишний Esc при выходе не ставит паузу. */
@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class MixinMinecraftPause
{
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$noPauseAfterContainer(boolean pauseOnly, CallbackInfo ci)
    {
        if (EscGuard.blockPause() && ((Minecraft) (Object) this).screen == null)
        {
            ci.cancel();
        }
    }
}
