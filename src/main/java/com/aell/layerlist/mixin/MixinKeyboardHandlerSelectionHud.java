package com.aell.layerlist.mixin;

import com.aell.layerlist.schematics.gui.PlacementHud;
import com.aell.layerlist.schematics.gui.SelectionHud;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Esc в игре при «Смотреть на мир» ({@link SelectionHud}, {@link PlacementHud}): вместо меню паузы — назад к вкладке
 * «Выделение» или в редактор размещения. Только клавиша: пауза при сворачивании окна идёт мимо {@code keyPress} и остаётся как была.
 */
@Environment(EnvType.CLIENT)
@Mixin(KeyboardHandler.class)
public abstract class MixinKeyboardHandlerSelectionHud
{
    @WrapWithCondition(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"),
            require = 0)
    private boolean layerlist$escBackToSelection(Minecraft minecraft, boolean pauseOnly)
    {
        return ! (SelectionHud.escape() || PlacementHud.escape());
    }
}
