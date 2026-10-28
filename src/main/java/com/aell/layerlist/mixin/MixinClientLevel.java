package com.aell.layerlist.mixin;

import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.verify.LayerVerifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel
{
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), require = 0)
    private void layerlist_onBlockChanged(BlockPos pos, BlockState state, int flags, CallbackInfo ci)
    {
        LayerCountManager.getInstance().markDirty();
    }

    /**
     * Подтверждение сервера по номеру пакета. Приходит независимо от того, удалась
     * установка или нет — {@code ackBlockChangesUpTo} стоит в серверном обработчике до
     * всех проверок, кроме {@code hasClientLoaded}. Отсюда и точный признак «сервер
     * отказал»: ack пришёл, а клетка не изменилась.
     */
    @Inject(method = "handleBlockChangedAck", at = @At("HEAD"), require = 0)
    private void layerlist_onBlockChangedAck(int sequence, CallbackInfo ci)
    {
        AutoPlaceState.onAck(sequence);
        com.aell.layerlist.easyplace.InventorySync.onAck(sequence);
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), require = 0)
    private void layerlist_onBlockUpdated(BlockPos pos, BlockState previous, BlockState current,
                                          int flags, CallbackInfo ci)
    {
        if (previous != current)
        {
            LayerVerifier.getInstance().onBlockChanged(pos, current);
            com.aell.layerlist.ghost.GhostSupport.onBlockChanged(pos);
        }
    }
}
