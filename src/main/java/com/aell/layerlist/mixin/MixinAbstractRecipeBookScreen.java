package com.aell.layerlist.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Экраны с книгой рецептов (инвентарь игрока, верстак, печи) рисуются своим {@code extractRenderState}, без
 * {@code AbstractContainerScreen.extractRenderState}, — панель материалов ставится здесь в то же место:
 * перед предметом на курсоре и подсказкой предмета, чтобы подсказка ложилась поверх панели.
 */
@Environment(EnvType.CLIENT)
@Mixin(AbstractRecipeBookScreen.class)
public abstract class MixinAbstractRecipeBookScreen
{
    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;extractCarriedItem(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"),
            require = 0)
    private void layerlist_hudUnderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks,
            CallbackInfo ci)
    {
        com.aell.layerlist.hud.LayerHudRenderer.renderUnderTooltip(graphics, mouseX, mouseY);
    }
}
