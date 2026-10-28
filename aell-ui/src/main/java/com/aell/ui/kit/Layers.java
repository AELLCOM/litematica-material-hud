package com.aell.ui.kit;

import com.aell.ui.Paint;
import com.aell.ui.Ui;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Стопка попапов экрана. Экран отдаёт сюда ввод <b>первым</b> и рисует стопку <b>последней</b>
 * (в {@code drawButtonHoverTexts}): так попап всегда над панелями и полями ввода.
 *
 * <p>Правила: клик мимо верхнего немодального попапа закрывает его и не уходит дальше (иначе
 * клик мимо меню нажал бы кнопку под ним); модальный попап забирает весь ввод; Esc закрывает
 * верхний попап.
 */
@Environment(EnvType.CLIENT)
public final class Layers
{
    private final List<Popup> stack = new ArrayList<>();
    private int screenW;
    private int screenH;

    public void setScreen(int width, int height)
    {
        this.screenW = width;
        this.screenH = height;
    }

    public int screenW()
    {
        return this.screenW;
    }

    public int screenH()
    {
        return this.screenH;
    }

    public void push(Popup popup)
    {
        this.stack.remove(popup);
        this.stack.add(popup);
    }

    public void close(Popup popup)
    {
        if (this.stack.remove(popup))
        {
            popup.onClose();
        }
    }

    public void closeAll()
    {
        while (! this.stack.isEmpty())
        {
            this.stack.removeLast().onClose();
        }
    }

    public boolean isOpen(Popup popup)
    {
        return this.stack.contains(popup);
    }

    public boolean any()
    {
        return ! this.stack.isEmpty();
    }

    public @Nullable Popup top()
    {
        return this.stack.isEmpty() ? null : this.stack.getLast();
    }

    /** Есть модальный попап: экран под ним не должен реагировать даже на наведение. */
    public boolean blocking()
    {
        for (Popup popup : this.stack)
        {
            if (popup.modal())
            {
                return true;
            }
        }

        return false;
    }

    /** Мышь над каким-то попапом (или экран заблокирован модальным): наведения под ним нет. */
    public boolean covers(double mouseX, double mouseY)
    {
        if (this.blocking())
        {
            return true;
        }

        for (Popup popup : this.stack)
        {
            if (popup.contains(mouseX, mouseY))
            {
                return true;
            }
        }

        return false;
    }

    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Popup top = this.top();

        for (Popup popup : List.copyOf(this.stack))
        {
            // Над модальным окном затемнение кладётся только один раз — под самым нижним модальным
            if (popup.modal() && popup == this.firstModal())
            {
                ctx.elementUp();
                Paint.rect(ctx, 0, 0, this.screenW, this.screenH, Ui.C_DIM);
            }

            ctx.elementUp();
            popup.render(ctx, popup == top ? mouseX : -1, popup == top ? mouseY : -1);
        }
    }

    private @Nullable Popup firstModal()
    {
        for (Popup popup : this.stack)
        {
            if (popup.modal())
            {
                return popup;
            }
        }

        return null;
    }

    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        Popup top = this.top();

        if (top == null)
        {
            return false;
        }

        if (top.contains(click.x(), click.y()))
        {
            top.mouseClicked(click, doubleClick);
            return true;
        }

        if (! top.modal())
        {
            this.close(top);
        }

        return true;
    }

    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        Popup top = this.top();
        return top != null && (top.mouseDragged(click, dx, dy) || top.modal());
    }

    public boolean mouseReleased(MouseButtonEvent click)
    {
        Popup top = this.top();
        return top != null && (top.mouseReleased(click) || top.modal());
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        Popup top = this.top();

        if (top == null)
        {
            return false;
        }

        if (top.contains(mouseX, mouseY))
        {
            return top.mouseScrolled(mouseX, mouseY, amount);
        }

        // Колесо мимо меню прокрутило бы список под ним, а меню осталось бы висеть не там
        if (! top.modal())
        {
            this.close(top);
            return false;
        }

        return true;
    }

    public boolean keyPressed(KeyEvent key)
    {
        Popup top = this.top();

        if (top == null)
        {
            return false;
        }

        if (top.keyPressed(key))
        {
            return true;
        }

        if (key.key() == InputConstants.KEY_ESCAPE)
        {
            this.close(top);
            return true;
        }

        // Модальное окно глотает всё; меню пропускает прочие клавиши экрану
        return top.modal();
    }

    public boolean charTyped(CharacterEvent event)
    {
        Popup top = this.top();
        return top != null && (top.charTyped(event) || top.modal());
    }
}
