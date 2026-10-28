package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Вид сверху: без тумана дальности прорисовки. Камера висит высоко над схемой, и земля под ней
 * лежит почти на границе дальности — ванильный туман залил бы её цветом неба. Туман среды
 * (дождь, вода, слепота) не трогаем: он про погоду, а не про расстояние. В облёте ({@link OrbitCamera})
 * камера отходит от схемы до дальности прорисовки — туман снят и там. Косметика —
 * {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(FogRenderer.class)
public abstract class MixinTopViewFog
{
    @Inject(method = "setupFog", at = @At("RETURN"), require = 0)
    private void layerlist$noDistanceFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
            float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir)
    {
        if (! TopViewCamera.active() && ! OrbitCamera.active())
        {
            return;
        }

        FogData fog = cir.getReturnValue();
        fog.renderDistanceStart = Float.MAX_VALUE;
        fog.renderDistanceEnd = Float.MAX_VALUE;
    }
}
