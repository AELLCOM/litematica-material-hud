package com.aell.layerlist.mixin;

import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.PoseAhead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Схема «поза на тик вперёд» ({@link PoseAhead}): нужный угол взгляда подставляется ровно на время
 * сборки ванильного пакета движения и снимается сразу после. Кадры между этими двумя точками не
 * рисуются, поэтому камера игрока не дёргается, а сервер получает угол штатным пакетом — своих
 * пакетов поворота мод при этом не отправляет вовсе.
 *
 * <p>Инжект стоит на всех выходах: {@code sendPosition} выходит рано, когда камера не на игроке, и
 * угол всё равно надо вернуть.
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerPose
{
    @Shadow
    private float yRotLast;

    @Unique
    private @Nullable LookSpoof.Pose layerlist$pose;

    @Unique
    private float layerlist$yaw;

    @Unique
    private float layerlist$pitch;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void layerlist$aimAhead(CallbackInfo ci)
    {
        // Поворот прошлых тиков, до подстановки: его сервер уже вписал в голову игрока
        PoseAhead.noteLastSent(this.yRotLast);

        LookSpoof.Pose pose = PoseAhead.forPacket();

        if (pose == null)
        {
            return;
        }

        LocalPlayer player = (LocalPlayer) (Object) this;

        this.layerlist$pose = pose;
        this.layerlist$yaw = player.getYRot();
        this.layerlist$pitch = player.getXRot();

        player.setYRot(pose.yaw());
        player.setXRot(pose.pitch());
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void layerlist$restoreAim(CallbackInfo ci)
    {
        LookSpoof.Pose pose = this.layerlist$pose;

        if (pose == null)
        {
            return;
        }

        LocalPlayer player = (LocalPlayer) (Object) this;

        player.setYRot(this.layerlist$yaw);
        player.setXRot(this.layerlist$pitch);
        this.layerlist$pose = null;

        // Считаем угол доехавшим только когда пакет действительно строится от игрока: при
        // камере на другой сущности ванильная ветка отправки пропускается целиком, и сервер
        // об угле не узнал бы, а клик ушёл бы со старым.
        if (Minecraft.getInstance().getCameraEntity() == player)
        {
            PoseAhead.packetSent(pose, player.tickCount);
        }
    }
}
