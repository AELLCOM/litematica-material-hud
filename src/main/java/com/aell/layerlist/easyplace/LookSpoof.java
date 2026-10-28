package com.aell.layerlist.easyplace;

import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

@Environment(EnvType.CLIENT)
public final class LookSpoof
{
    private LookSpoof() {}

    public record Pose(float yaw, float pitch, boolean sneak)
    {
        public static Pose of(LocalPlayer player)
        {
            return new Pose(player.getYRot(), player.getXRot(), player.isShiftKeyDown());
        }

        public boolean sameAs(Pose other)
        {
            return this.yaw == other.yaw && this.pitch == other.pitch && this.sneak == other.sneak;
        }
    }

    public static <T> T silent(LocalPlayer player, Pose pose, Supplier<T> body)
    {
        Pose original = Pose.of(player);
        float yawO = player.yRotO;
        float pitchO = player.xRotO;

        setLocal(player, pose);

        try
        {
            return body.get();
        }
        finally
        {
            setLocal(player, original);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
    }

    public static <T> T announced(LocalPlayer player, Pose pose, Supplier<T> body)
    {
        Pose original = Pose.of(player);

        if (pose.sameAs(original))
        {
            return body.get();
        }

        float yawO = player.yRotO;
        float pitchO = player.xRotO;

        announce(player, pose, original);

        try
        {
            return body.get();
        }
        finally
        {
            announce(player, original, pose);
            player.yRotO = yawO;
            player.xRotO = pitchO;
        }
    }

    /**
     * Угол уже у сервера ({@link PoseAhead}): ставим позу только локально, чтобы клиентское
     * предсказание совпало с серверным, и отправляем разве что присед — он идёт отдельным
     * пакетом ввода, а не движения, и timer-проверки его не считают.
     */
    public static <T> T sneakOnly(LocalPlayer player, Pose pose, Supplier<T> body)
    {
        Pose original = Pose.of(player);
        float yawO = player.yRotO;
        float pitchO = player.xRotO;

        setLocal(player, pose);

        if (player.connection != null && pose.sneak() != original.sneak())
        {
            player.connection.send(new ServerboundPlayerInputPacket(player.input.keyPresses));
        }

        try
        {
            return body.get();
        }
        finally
        {
            setLocal(player, original);
            player.yRotO = yawO;
            player.xRotO = pitchO;

            if (player.connection != null && pose.sneak() != original.sneak())
            {
                player.connection.send(new ServerboundPlayerInputPacket(player.input.keyPresses));
            }
        }
    }

    private static void announce(LocalPlayer player, Pose pose, Pose from)
    {
        setLocal(player, pose);

        if (player.connection == null)
        {
            return;
        }

        if (pose.sneak() != from.sneak())
        {
            player.connection.send(new ServerboundPlayerInputPacket(player.input.keyPresses));
        }

        if (pose.yaw() != from.yaw() || pose.pitch() != from.pitch())
        {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(pose.yaw(), pose.pitch(),
                    player.onGround(), player.horizontalCollision));
        }
    }

    private static void setLocal(LocalPlayer player, Pose pose)
    {
        player.setYRot(pose.yaw());
        player.setXRot(pose.pitch());
        player.yRotO = pose.yaw();
        player.xRotO = pose.pitch();
        player.setShiftKeyDown(pose.sneak());

        Input keys = player.input.keyPresses;

        if (keys.shift() != pose.sneak())
        {
            player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(),
                    keys.right(), keys.jump(), pose.sneak(), keys.sprint());
        }
    }
}
