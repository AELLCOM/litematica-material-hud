package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.LitematicaSkin;
import com.aell.layerlist.skin.SkinPaint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Поле ввода malilib ({@code GuiTextFieldGeneric} — ванильный {@code EditBox}) в перекрашенном экране: вместо
 * спрайта рамки — плашка aell-ui ({@link SkinPaint#field}); текст, курсор и выделение рисует ваниль, отступы не
 * меняются. Ванильные поля других экранов не трогаем. {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(EditBox.class)
public abstract class MixinEditBoxSkin
{
    @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
            require = 0)
    private void layerlist$skinField(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w,
            int h, Operation<Void> original)
    {
        EditBox self = (EditBox) (Object) this;

        if (LitematicaSkin.skinning() && self instanceof GuiTextFieldGeneric)
        {
            SkinPaint.field(GuiContext.fromGuiGraphics(graphics), x, y, w, h, self.isFocused(), self.isActive());
            return;
        }

        original.call(graphics, pipeline, sprite, x, y, w, h);
    }
}
