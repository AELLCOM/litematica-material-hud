package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Paint;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/** Мелочи набора: шрифт, перенос строк, кнопки мыши SDL, группы разрядов. */
@Environment(EnvType.CLIENT)
public final class Kit
{
    private Kit() {}

    public static Font font()
    {
        return Minecraft.getInstance().font;
    }

    /** Левая кнопка. В 26.3 ввод SDL: левая = 1, поэтому только через константу. */
    public static boolean left(MouseButtonEvent click)
    {
        return click.button() == InputConstants.MOUSE_BUTTON_LEFT;
    }

    public static boolean right(MouseButtonEvent click)
    {
        return click.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    /** Левый клик внутри прямоугольника. */
    public static boolean leftHit(MouseButtonEvent click, int x, int y, int w, int h)
    {
        return left(click) && Paint.hit((int) Math.floor(click.x()), (int) Math.floor(click.y()), x, y, w, h);
    }

    /**
     * Перенос по словам в ширину {@code maxWidth}. Слово длиннее строки режется по буквам —
     * иначе длинный путь к файлу вылез бы за карточку. Переводы строк в тексте сохраняются.
     */
    public static List<String> wrap(Font font, String text, int maxWidth)
    {
        List<String> out = new ArrayList<>();

        if (maxWidth <= 0)
        {
            return out;
        }

        for (String paragraph : text.split("\n", -1))
        {
            StringBuilder line = new StringBuilder();

            for (String word : paragraph.split(" "))
            {
                String candidate = line.isEmpty() ? word : line + " " + word;

                if (font.width(candidate) <= maxWidth)
                {
                    line = new StringBuilder(candidate);
                    continue;
                }

                if (! line.isEmpty())
                {
                    out.add(line.toString());
                    line = new StringBuilder();
                }

                String rest = word;

                while (font.width(rest) > maxWidth)
                {
                    String head = font.plainSubstrByWidth(rest, maxWidth);

                    if (head.isEmpty())
                    {
                        head = rest.substring(0, 1);
                    }

                    out.add(head);
                    rest = rest.substring(head.length());
                }

                line.append(rest);
            }

            out.add(line.toString());
        }

        return out;
    }

    /** Число с группами разрядов: 12345 → «12 345» (разделитель — из перевода). */
    public static String group(long value, String separator)
    {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        int lead = digits.length() % 3;

        for (int i = 0; i < digits.length(); ++i)
        {
            if (i > 0 && (i - lead) % 3 == 0)
            {
                out.append(separator);
            }

            out.append(digits.charAt(i));
        }

        return value < 0 ? "-" + out : out.toString();
    }
}
