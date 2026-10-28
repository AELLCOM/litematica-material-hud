package com.aell.layerlist.mixin;

import com.aell.layerlist.hud.TransferButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Окно контейнера закрывают в обход экрана (чужой мод, сама игра) посреди переноса: до пакета закрытия
 * {@link TransferButton} кладёт стопку с курсора на место — иначе сервер отдал бы её игроку целиком.
 * Само закрытие не отменяется: экран всё равно уйдёт, и окно на сервере должно закрыться вместе с ним.
 */
@Environment(EnvType.CLIENT)
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerTransfer
{
    @Inject(method = "closeContainer", at = @At("HEAD"), require = 0)
    private void layerlist$returnCursor(CallbackInfo ci)
    {
        TransferButton.beforeClose((LocalPlayer) (Object) this);
    }
}
