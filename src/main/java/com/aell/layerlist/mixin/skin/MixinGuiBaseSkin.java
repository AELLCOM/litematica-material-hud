package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.LitematicaSkin;
import com.aell.layerlist.skin.SkinPaint;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кадр экрана malilib: в начале решаем, красим ли этот экран ({@link LitematicaSkin#beginFrame}), в конце — снимаем
 * кадр со стека; фон и заголовок перекрашенного экрана Litematica — наши ({@link SkinPaint}). {@code require = 0}: переименуют методы — экран останется
 * в виде malilib.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = GuiBase.class, remap = false)
public abstract class MixinGuiBaseSkin
{
    @Inject(method = "render", at = @At("HEAD"), require = 0, remap = true)
    private void layerlist$skinBegin(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
    {
        LitematicaSkin.beginFrame((GuiBase) (Object) this);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0, remap = true)
    private void layerlist$skinEnd(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
    {
        LitematicaSkin.endFrame();
    }

    @Inject(method = "drawScreenBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skinBackground(GuiContext ctx, int mouseX, int mouseY, CallbackInfo ci)
    {
        if (LitematicaSkin.skinning() && LitematicaSkin.ownsBackground((GuiBase) (Object) this))
        {
            SkinPaint.background(ctx, (GuiBase) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = "drawTitle", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skinTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
    {
        if (LitematicaSkin.skinning() && LitematicaSkin.ownsBackground((GuiBase) (Object) this))
        {
            SkinPaint.title(ctx, (GuiBase) (Object) this);
            ci.cancel();
        }
    }
}
