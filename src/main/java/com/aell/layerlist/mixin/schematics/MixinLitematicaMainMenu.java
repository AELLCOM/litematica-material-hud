package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.gui.LitematicaMenuButton;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопка «Material HUD» (вход в настройки мода) в главном меню Litematica. {@code require = 0}:
 * переименуют {@code initGui} — кнопка пропадёт, а меню и игра останутся целы. Где кнопка встанет и
 * включена ли она — решает {@link LitematicaMenuButton} (опция {@code schematicsMainMenuButton}).
 */
@Environment(EnvType.CLIENT)
@Mixin(GuiMainMenu.class)
public abstract class MixinLitematicaMainMenu
{
    @Inject(method = "initGui", at = @At("TAIL"), require = 0)
    private void layerlist$addMaterialHudButton(CallbackInfo ci)
    {
        LitematicaMenuButton.addTo((GuiBase) (Object) this);
    }
}
