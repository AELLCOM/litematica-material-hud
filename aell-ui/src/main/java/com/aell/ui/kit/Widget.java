package com.aell.ui.kit;

import com.aell.ui.Paint;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Основа виджетов набора: прямоугольник, который раскладывает владелец, отрисовка и ввод. Каждый
 * обработчик ввода возвращает «съедено ли событие». Кнопки мыши — числа SDL 26.3, сравнивать только с
 * {@code InputConstants.MOUSE_BUTTON_*} (см. {@link Kit#left}).
 *
 * <p>Виджет ничего не знает об экране: владелец (экран или панель) сам решает, кому отдать событие,
 * и передаёт координаты мыши {@code -1}, если над виджетом открыт попап — тогда наведения нет.
 */
@Environment(EnvType.CLIENT)
public abstract class Widget
{
    protected int x;
    protected int y;
    protected int w;
    protected int h;
    protected boolean visible = true;
    protected boolean enabled = true;

    public void setBounds(int x, int y, int w, int h)
    {
        this.x = x;
        this.y = y;
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    public int x()
    {
        return this.x;
    }

    public int y()
    {
        return this.y;
    }

    public int w()
    {
        return this.w;
    }

    public int h()
    {
        return this.h;
    }

    public boolean visible()
    {
        return this.visible;
    }

    public void setVisible(boolean visible)
    {
        this.visible = visible;
    }

    public boolean enabled()
    {
        return this.enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public boolean contains(double mouseX, double mouseY)
    {
        return this.visible && this.w > 0 && this.h > 0
                && Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), this.x, this.y, this.w, this.h);
    }

    public abstract void render(GuiContext ctx, int mouseX, int mouseY);

    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent click)
    {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return false;
    }

    public boolean keyPressed(KeyEvent key)
    {
        return false;
    }

    public boolean charTyped(CharacterEvent event)
    {
        return false;
    }

    /** Поле ввода внутри виджета (само поле или поле строки формы) — для фокуса и Tab в окнах. */
    public @Nullable TextInput textInput()
    {
        return null;
    }
}
