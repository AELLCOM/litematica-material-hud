package com.aell.layerlist.mixin.schematics;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.11: состояние кадра GUI — закрытое поле {@code GameRenderer.guiRenderState} (в 26.x —
 * {@code gameRenderState().guiRenderState}). Через него снимок превью и иконки выгрузок ставят свою
 * невидимую заявку «картинка в картинке» в текущий кадр.
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public interface IMixinGameRendererGui
{
    @Accessor("guiRenderState")
    GuiRenderState layerlist$guiRenderState();
}
