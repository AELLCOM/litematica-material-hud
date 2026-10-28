package com.aell.layerlist.easyplace;

import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class RotationSpoof
{
    private RotationSpoof() {}

    public static <T> T around(LocalPlayer player, @Nullable Float yaw, @Nullable Float pitch,
            Supplier<T> body)
    {
        float originalYaw = player.getYRot();
        float originalPitch = player.getXRot();
        float wantedYaw = yaw == null ? originalYaw : yaw;
        float wantedPitch = pitch == null ? originalPitch : pitch;

        if (wantedYaw == originalYaw && wantedPitch == originalPitch)
        {
            return body.get();
        }

        apply(player, wantedYaw, wantedPitch);

        try
        {
            return body.get();
        }
        finally
        {
            apply(player, originalYaw, originalPitch);
        }
    }

    private static void apply(LocalPlayer player, float yaw, float pitch)
    {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;

        if (player.connection != null)
        {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch,
                    player.onGround(), player.horizontalCollision));
        }
    }
}
