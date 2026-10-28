package com.aell.layerlist.mixin.voxy;

import com.aell.layerlist.compat.VoxyPreview;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Кадр превью не уводит центр подгрузки LOD Voxy к своему далёкому глазу — он остаётся на игроке, как в игре, — но
 * шаг подгрузки делает: Voxy грузит LOD по шагу за кадр, а VoxyMap Bobby вне своей карты пропускает большую часть
 * кадров игры, и без этого область вокруг игрока дочитывалась бы минутами.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class MixinVoxyRenderSystem
{
    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"), require = 0)
    private boolean layerlist$keepLoadCentre(RenderDistanceTracker tracker, double x, double z, Operation<Boolean> original)
    {
        if (! VoxyPreview.rendering())
        {
            return original.call(tracker, x, z);
        }

        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && original.call(tracker, player.getX(), player.getZ());
    }
}
