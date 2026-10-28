package com.aell.layerlist.ui.kit;

import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Всплывающий слой над экраном: выпадающий список, контекстное меню, модальное окно. Живёт в
 * {@link Layers}; рисуется в самом конце кадра, поэтому оказывается над полями ввода и списками.
 */
@Environment(EnvType.CLIENT)
public interface Popup
{
    void render(GuiContext ctx, int mouseX, int mouseY);

    /** Область, клик в которую попап обрабатывает сам. */
    boolean contains(double mouseX, double mouseY);

    /** Модальный попап перехватывает весь ввод экрана и затемняет его. */
    default boolean modal()
    {
        return false;
    }

    default boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return true;
    }

    default boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return false;
    }

    default boolean mouseReleased(MouseButtonEvent click)
    {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return true;
    }

    /** Клавиша для верхнего попапа; Esc закрывает его, если попап сам не решил иначе. */
    default boolean keyPressed(KeyEvent key)
    {
        return false;
    }

    default boolean charTyped(CharacterEvent event)
    {
        return false;
    }

    /** Попап закрыли (Esc, клик мимо, выбор пункта). */
    default void onClose()
    {
    }
}
