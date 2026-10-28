package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Строка формы как строка экрана настроек: название слева ({@link Ui#C_TEXT}, подсказка — при
 * наведении на название), контрол справа шириной {@code controlW}. В режиме {@link #stacked} контрол
 * уходит под название на всю ширину — для длинных значений (папка, адрес).
 */
@Environment(EnvType.CLIENT)
public final class FormRow extends Widget
{
    /** Высота обычной строки. */
    public static final int HEIGHT = Ui.ROW_H;
    /** Высота строки «название над полем». */
    public static final int STACKED_HEIGHT = Ui.TIP_LINE + Ui.PAD_MICRO + Ui.CTRL_H;

    private final String label;
    private final @Nullable String tooltip;
    private final Widget control;
    private final int controlW;
    private final boolean stacked;

    private FormRow(String label, @Nullable String tooltip, Widget control, int controlW, boolean stacked)
    {
        this.label = label;
        this.tooltip = tooltip;
        this.control = control;
        this.controlW = controlW;
        this.stacked = stacked;
    }

    public static FormRow inline(String label, @Nullable String tooltip, Widget control, int controlW)
    {
        return new FormRow(label, tooltip, control, controlW, false);
    }

    public static FormRow stacked(String label, @Nullable String tooltip, Widget control)
    {
        return new FormRow(label, tooltip, control, 0, true);
    }

    public int height()
    {
        return this.stacked ? STACKED_HEIGHT : HEIGHT;
    }

    public Widget control()
    {
        return this.control;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        if (this.stacked)
        {
            this.control.setBounds(x, y + Ui.TIP_LINE + Ui.PAD_MICRO, w, Ui.CTRL_H);
        }
        else
        {
            int cw = Math.min(this.controlW, w / 2);
            this.control.setBounds(x + w - cw, y + (h - Ui.CTRL_H) / 2, cw, Ui.CTRL_H);
        }
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        int labelW = this.stacked ? this.w : this.control.x() - Ui.PAD_REL - this.x;
        int labelH = this.stacked ? Ui.TIP_LINE : this.h;
        String shown = Paint.fit(font, this.label, labelW);

        Paint.midText(ctx, font, shown, this.x, this.y, labelH, Ui.C_TEXT, false);

        if (Paint.hit(mouseX, mouseY, this.x, this.y, Math.max(0, labelW), labelH))
        {
            Tooltip.offerLines(this, this.tooltip == null ? List.of(this.label)
                    : List.of(this.label, this.tooltip), Ui.C_TEXT);
        }

        this.control.render(ctx, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return this.control.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.control.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        return this.control.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.control.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        return this.control.keyPressed(key);
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        return this.control.charTyped(event);
    }

    @Override
    public @Nullable TextInput textInput()
    {
        return this.control.textInput();
    }
}
