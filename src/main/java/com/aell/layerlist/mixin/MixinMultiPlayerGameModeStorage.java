package com.aell.layerlist.mixin;

import com.aell.layerlist.storage.ContainerObserver;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Клик по контейнеру для индекса хранилищ — с выхода {@code useItemOn}, а не из
 * {@code UseBlockCallback}: событие Fabric стреляет в начале метода, и клик, который следом
 * гасит Easy Place (свой конвейер или Litematica), всё равно попал бы в очередь. Отменённый
 * в начале метода клик до выхода не доходит.
 */
@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameModeStorage
{
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void layerlist$armStorageRecord(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        ContainerObserver.onUseItemOnReturned(player, hit.getBlockPos(), cir.getReturnValue());
    }
}
