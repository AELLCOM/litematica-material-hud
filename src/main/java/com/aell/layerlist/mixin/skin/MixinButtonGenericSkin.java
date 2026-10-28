package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.LitematicaSkin;
import com.aell.layerlist.skin.MainMenuSkin;
import com.aell.layerlist.skin.SkinAccess;
import com.aell.layerlist.skin.SkinPaint;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопка malilib в перекрашенном экране Litematica ({@link LitematicaSkin#skinning()}): фон и подпись — наши
 * ({@link SkinPaint}), значок malilib рисует сам. Так выглядят и кнопки Litematica, и кнопки других модов —
 * они тоже {@code ButtonGeneric}; место и действие кнопки не трогаются. {@code require = 0}: переименуют
 * закрытые методы — кнопки останутся ванильными, меню цело.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = ButtonGeneric.class, remap = false)
public abstract class MixinButtonGenericSkin
{
    @Shadow @Final protected IGuiIcon icon;
    @Shadow protected LeftRight alignment;
    @Shadow protected boolean textCentered;
    @Shadow protected boolean renderDefaultBackground;

    /** Наведение 0…1 и время прошлого кадра — плавное, как у кнопок aell-ui. */
    @Unique private float layerlist$hover;
    @Unique private long layerlist$hoverAt;

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skinBackground(GuiContext ctx, CallbackInfo ci)
    {
        if (! LitematicaSkin.skinning())
        {
            return;
        }

        ci.cancel();
        ButtonBase self = (ButtonBase) (Object) this;
        boolean enabled = SkinAccess.enabled(self);
        this.layerlist$hover = SkinPaint.approach(this.layerlist$hover, self.isMouseOver() && enabled, this.layerlist$hoverAt);
        this.layerlist$hoverAt = System.nanoTime();

        if (this.renderDefaultBackground)
        {
            SkinPaint.button(ctx, self.getX(), self.getY(), self.getWidth(), self.getHeight(), this.layerlist$hover, enabled,
                    MainMenuSkin.isAddon(self));
        }
    }

    @Inject(method = "drawText", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skinText(GuiContext ctx, CallbackInfo ci)
    {
        if (! LitematicaSkin.skinning())
        {
            return;
        }

        ci.cancel();
        ButtonBase self = (ButtonBase) (Object) this;
        int iconW = this.icon != null && this.alignment == LeftRight.LEFT ? this.icon.getWidth() + 2 : 0;
        SkinPaint.label(ctx, SkinAccess.label(self), self.getX(), self.getY(), self.getWidth(), self.getHeight(),
                this.textCentered, iconW, this.layerlist$hover, SkinAccess.enabled(self));
    }
}
