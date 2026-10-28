package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.InventorySync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Обновления слотов инвентаря игрока — для {@link InventorySync}: после подтверждения установки они значат
 * «ответ сервера про руку дошёл». TAIL — уже на клиентском потоке: на сетевом обработчик бросает исключение
 * в {@code ensureRunningOnSameThread} раньше, чем дойдёт сюда.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerInventory
{
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 0)
    private void layerlist$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci)
    {
        layerlist$update(packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"), require = 0)
    private void layerlist$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci)
    {
        layerlist$update(packet.containerId());
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"), require = 0)
    private void layerlist$inventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci)
    {
        InventorySync.onSlotUpdate();
    }

    @Unique
    private static void layerlist$update(int containerId)
    {
        var player = Minecraft.getInstance().player;

        if (player != null && containerId == player.inventoryMenu.containerId)
        {
            InventorySync.onSlotUpdate();
        }
    }
}
