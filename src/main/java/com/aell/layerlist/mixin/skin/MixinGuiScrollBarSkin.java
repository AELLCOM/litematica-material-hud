package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.skin.LitematicaSkin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Полоса прокрутки malilib в перекрашенном экране: дорожка {@link Ui#C_TRACK}, ползунок {@link Ui#C_THUMB} (при
 * перетаскивании и наведении — {@link Ui#C_THUMB_DRAG}), скруглённые, как в aell-ui. Ползунок malilib белый —
 * общей заменой цветов его не взять, поэтому меняем только вызовы внутри {@code render}; счёт положения и
 * перетаскивание остаются malilib. {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = GuiScrollBar.class, remap = false)
public abstract class MixinGuiScrollBarSkin
{
    @Shadow protected boolean mouseOver;
    @Shadow protected boolean dragging;
    @Shadow protected int backgroundColor;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/render/RenderUtils;drawRect(Lfi/dy/masa/malilib/render/GuiContext;IIIII)V"), require = 0)
    private void layerlist$skinBar(GuiContext ctx, int x, int y, int width, int height, int color, Operation<Void> original)
    {
        if (! LitematicaSkin.skinning())
        {
            original.call(ctx, x, y, width, height, color);
        }
        else if (color == this.backgroundColor)
        {
            Paint.round(ctx, x, y, width, height, Ui.R_BAR, Ui.C_TRACK);
        }
        else
        {
            Paint.round(ctx, x, y, width, height, Ui.R_BAR, this.dragging || this.mouseOver ? Ui.C_THUMB_DRAG : Ui.C_THUMB);
        }
    }

    /** Ползунок из текстуры (так рисуют списки Litematica) — та же плашка, без текстуры. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lfi/dy/masa/malilib/render/RenderUtils;drawTexturedRect(Lfi/dy/masa/malilib/render/GuiContext;Lnet/minecraft/resources/Identifier;IIIIII)V",
            remap = true), require = 0)
    private void layerlist$skinThumb(GuiContext ctx, Identifier texture, int x, int y, int u, int v, int width, int height,
            Operation<Void> original)
    {
        if (LitematicaSkin.skinning())
        {
            Paint.rect(ctx, x, y, width, height, this.dragging || this.mouseOver ? Ui.C_THUMB_DRAG : Ui.C_THUMB);
            return;
        }

        original.call(ctx, texture, x, y, u, v, width, height);
    }
}
