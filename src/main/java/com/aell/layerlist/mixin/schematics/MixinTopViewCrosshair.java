package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.TopViewCamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Вид сверху: без прицела. HUD рисуется и под открытым экраном, а прицел в центре окна при виде
 * с высоты (и в облёте, {@link OrbitCamera}) ни на что не указывает и путается с курсором.
 * Косметика — {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(Gui.class)
public abstract class MixinTopViewCrosshair
{
    // 1.21.11: прицел рисует Gui.renderCrosshair (в 26.x — Hud.extractCrosshair)
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$hideCrosshair(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci)
    {
        if (TopViewCamera.active() || OrbitCamera.active())
        {
            ci.cancel();
        }
    }
}
