package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Тумблеры с подписью в строку с переносом: в широком окне все три рядом, в узком — столбиком. Тумблер
 * шире строки получает всю строку и обрезает подпись (подсказка покажет её целиком). До 0.0.27 — флажки
 * ({@code CheckFlow}).
 */
@Environment(EnvType.CLIENT)
final class ToggleFlow extends Widget
{
    private static final int LINE_H = Ui.HEAD_BTN_H;
    private static final int GAP_X = Ui.PAD_GROUP;

    private final List<Toggle> boxes;

    ToggleFlow(Toggle... boxes)
    {
        this.boxes = List.of(boxes);
    }

    int height(int width)
    {
        int lines = this.place(width, 0, 0, false);

        return lines * LINE_H + Math.max(0, lines - 1) * Ui.PAD_IN;
    }

    /** Раскладка по строкам; {@code apply} — выставить границы. Возвращает число строк. */
    private int place(int width, int x, int y, boolean apply)
    {
        Font font = Kit.font();
        int cx = 0;
        int line = 0;

        for (Toggle box : this.boxes)
        {
            int bw = Math.min(box.preferredWidth(font), Math.max(0, width));

            if (cx > 0 && cx + bw > width)
            {
                ++line;
                cx = 0;
            }

            if (apply)
            {
                box.setBounds(x + cx, y + line * (LINE_H + Ui.PAD_IN), bw, LINE_H);
            }

            cx += bw + GAP_X;
        }

        return this.boxes.isEmpty() ? 0 : line + 1;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);
        this.place(w, x, y, true);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        for (Toggle box : this.boxes)
        {
            box.render(ctx, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        for (Toggle box : this.boxes)
        {
            if (box.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return false;
    }
}
