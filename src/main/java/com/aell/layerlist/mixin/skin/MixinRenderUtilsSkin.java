package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.LitematicaSkin;
import com.aell.layerlist.skin.SkinColors;
import com.aell.layerlist.skin.SkinPaint;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Примитивы malilib в перекрашенном экране: все заливки и рамки GUI сходятся в
 * {@code drawRect(GuiContext, x, y, w, h, color, scale)} — там цвет malilib меняется на токен aell-ui
 * ({@link SkinColors}); окна и панели ({@code drawOutlinedBox} с тёмным фоном) — скруглённой карточкой;
 * подсказки — в виде aell-ui. Так перекрашиваются и строки списков Litematica, и виджеты других модов,
 * нарисованные теми же примитивами. {@code require = 0}: переименуют — останется вид malilib.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = RenderUtils.class, remap = false)
public abstract class MixinRenderUtilsSkin
{
    @ModifyVariable(method = "drawRect(Lfi/dy/masa/malilib/render/GuiContext;IIIIIF)V", at = @At("HEAD"), argsOnly = true,
            ordinal = 4, require = 0)
    private static int layerlist$skinColor(int color)
    {
        return LitematicaSkin.skinning() ? SkinColors.map(color) : color;
    }

    @Inject(method = "drawOutlinedBox(Lfi/dy/masa/malilib/render/GuiContext;IIIIII)V", at = @At("HEAD"), cancellable = true,
            require = 0)
    private static void layerlist$skinPanel(GuiContext ctx, int x, int y, int width, int height, int colorBg, int colorBorder,
            CallbackInfo ci)
    {
        if (LitematicaSkin.skinning() && SkinColors.isPanel(colorBg))
        {
            SkinPaint.panel(ctx, x, y, width, height, colorBg);
            ci.cancel();
        }
    }

    @Inject(method = "drawHoverText(Lfi/dy/masa/malilib/render/GuiContext;IILjava/util/List;)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private static void layerlist$skinTooltip(GuiContext ctx, int x, int y, List<String> lines, CallbackInfo ci)
    {
        if (LitematicaSkin.skinning())
        {
            SkinPaint.tooltip(ctx, x, y, lines);
            ci.cancel();
        }
    }
}
