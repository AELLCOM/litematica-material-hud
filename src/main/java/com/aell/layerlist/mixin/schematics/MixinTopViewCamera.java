package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Вид сверху редактора размещения: сразу после того, как камера встала по сущности, ставим
 * свою позу ({@link TopViewCamera}). Точка выбрана так, что всё дальнейшее в
 * {@code Camera.update} — пирамида отсечения, проекция, угол обзора — считается уже от новой
 * позы, и мир строится вокруг неё без отдельной правки отсечения. Камера помечается
 * «отвязанной», поэтому ванильная выборка сущностей рисует и самого игрока.
 *
 * <p>Облёт ({@link OrbitCamera}, 0.0.27) — та же точка: сначала поза подтягивается к цели на шаг
 * кадра ({@link OrbitCamera#advance()}), потом ставится так же.
 *
 * <p>{@code require = 1}: без этой точки вида сверху и облёта нет вовсе — лучше узнать об этом при
 * старте.
 */
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class MixinTopViewCamera
{
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity", at = @At("TAIL"), require = 1)
    private void layerlist$topView(float partialTicks, CallbackInfo ci)
    {
        if (TopViewCamera.active())
        {
            this.setRotation(TopViewCamera.YAW, TopViewCamera.PITCH);
            this.setPosition(TopViewCamera.x(), TopViewCamera.y(), TopViewCamera.z());
            this.detached = true;
        }
        else if (OrbitCamera.active())
        {
            OrbitCamera.advance();
            this.setRotation(OrbitCamera.yaw(), OrbitCamera.pitch());
            this.setPosition(OrbitCamera.position());
            this.detached = true;
        }
    }
}
