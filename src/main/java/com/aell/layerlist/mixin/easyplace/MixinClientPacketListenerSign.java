package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.SignEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerSign
{
    /**
     * Первый заход — с сетевого потока: {@code ensureRunningOnSameThread} перекинет
     * обработку на основной и бросит своё исключение, решение принимает основной заход.
     */
    @Inject(method = "handleOpenSignEditor", at = @At("HEAD"), cancellable = true)
    private void layerlist$fillSchematicSign(ClientboundOpenSignEditorPacket packet, CallbackInfo ci)
    {
        if (! Minecraft.getInstance().isSameThread())
        {
            return;
        }

        boolean handled;

        try
        {
            handled = SignEditor.handleOpen(packet.pos(), packet.slot());
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        if (handled)
        {
            ci.cancel();
        }
    }
}
