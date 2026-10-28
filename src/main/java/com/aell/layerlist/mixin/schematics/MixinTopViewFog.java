package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Вид сверху: без тумана дальности прорисовки. Камера висит высоко над схемой, и земля под ней
 * лежит почти на границе дальности — ванильный туман залил бы её цветом неба. Туман среды
 * (дождь, вода, слепота) не трогаем: он про погоду, а не про расстояние. В облёте ({@link OrbitCamera})
 * камера отходит от схемы до дальности прорисовки — туман снят и там. Косметика —
 * {@code require = 0}.
 *
 * <p>1.21.11: {@code setupFog} возвращает цвет тумана (Vector4f), а не FogData — поэтому правим не результат,
 * а границы дальности, которые уходят в буфер ({@code updateBuffer}, аргументы 5 и 6).
 */
@Environment(EnvType.CLIENT)
@Mixin(FogRenderer.class)
public abstract class MixinTopViewFog
{
    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
            index = 5, require = 0)
    private float layerlist$noDistanceFogStart(float start)
    {
        return active() ? Float.MAX_VALUE : start;
    }

    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"),
            index = 6, require = 0)
    private float layerlist$noDistanceFogEnd(float end)
    {
        return active() ? Float.MAX_VALUE : end;
    }

    private static boolean active()
    {
        return TopViewCamera.active() || OrbitCamera.active();
    }
}
