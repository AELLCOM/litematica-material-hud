package com.aell.layerlist.ui;

import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;

@Environment(EnvType.CLIENT)
public final class Draw
{
    private static final int[][] INSETS = {
            {},
            {0},
            {1, 0},
            {2, 1, 0},
            {2, 1, 0, 0},
            {3, 2, 1, 0, 0},
            {3, 2, 1, 1, 0, 0},
            {4, 2, 1, 1, 0, 0, 0},
            {4, 3, 2, 1, 1, 0, 0, 0}
    };

    private Draw() {}

    public static void rect(GuiContext ctx, int x, int y, int width, int height, int color)
    {
        if (width > 0 && height > 0)
        {
            ctx.fill(x, y, x + width, y + height, Style.color(color));
        }
    }

    public static void round(GuiContext ctx, int x, int y, int width, int height, int radius, int color)
    {
        int r = radius(Style.radius(radius), width, height);

        if (r == 0)
        {
            rect(ctx, x, y, width, height, color);
            return;
        }

        int[] insets = INSETS[r];

        for (int i = 0; i < r; ++i)
        {
            int inset = insets[i];
            rect(ctx, x + inset, y + i, width - inset * 2, 1, color);
            rect(ctx, x + inset, y + height - 1 - i, width - inset * 2, 1, color);
        }

        rect(ctx, x, y + r, width, height - r * 2, color);
    }

    public static void roundEdge(GuiContext ctx, int x, int y, int width, int height, int radius, int color)
    {
        int r = radius(Style.radius(radius), width, height);

        if (r == 0)
        {
            rect(ctx, x, y, width, 1, color);
            rect(ctx, x, y + height - 1, width, 1, color);
            rect(ctx, x, y + 1, 1, height - 2, color);
            rect(ctx, x + width - 1, y + 1, 1, height - 2, color);
            return;
        }

        int[] insets = INSETS[r];
        int straight = insets[0] == 0 ? 0 : firstZero(insets);

        rect(ctx, x + r, y, width - r * 2, 1, color);
        rect(ctx, x + r, y + height - 1, width - r * 2, 1, color);
        rect(ctx, x, y + straight, 1, height - straight * 2, color);
        rect(ctx, x + width - 1, y + straight, 1, height - straight * 2, color);

        for (int i = 0; i < r; ++i)
        {
            int inset = insets[i];
            int previous = i == 0 ? r : insets[i - 1];

            if (previous <= inset)
            {
                continue;
            }

            int span = previous - inset;
            rect(ctx, x + inset, y + i, span, 1, color);
            rect(ctx, x + width - previous, y + i, span, 1, color);
            rect(ctx, x + inset, y + height - 1 - i, span, 1, color);
            rect(ctx, x + width - previous, y + height - 1 - i, span, 1, color);
        }
    }

    public static void card(GuiContext ctx, int x, int y, int width, int height, int radius, int fill, int edge)
    {
        // Ванильный стиль: панель — фон списка Minecraft с тёмной рамкой
        if (Style.vanilla() && Style.isPanel(fill))
        {
            Vanilla.panel(ctx, x, y, width, height);
            return;
        }

        round(ctx, x, y, width, height, radius, fill);

        if (edge != 0)
        {
            roundEdge(ctx, x, y, width, height, radius, edge);
        }
    }

    public static void screen(GuiContext ctx, int width, int height)
    {
        if (Style.vanilla())
        {
            Vanilla.screen(ctx, width, height);
            return;
        }

        rect(ctx, 0, 0, width, height, Theme.BG);
    }

    public static void panel(GuiContext ctx, int x, int y, int width, int height)
    {
        card(ctx, x, y, width, height, Theme.RADIUS_BIG, Theme.PANEL, Theme.EDGE);
    }

    public static void bar(GuiContext ctx, int x, int y, int width, int height, float progress, int color)
    {
        int radius = Math.min(3, height / 2);
        round(ctx, x, y, width, height, radius, Theme.ROW_DEEP);

        int fill = (int) (width * Math.max(0.0F, Math.min(1.0F, progress)));

        if (fill > 0)
        {
            round(ctx, x, y, Math.max(fill, radius * 2), height, radius, color);
        }
    }

    public static int chip(GuiContext ctx, Font font, String text, int x, int y, int color, int fill, int edge)
    {
        int width = font.width(text) + 12;
        int height = 12;

        card(ctx, x, y, width, height, 6, fill, edge);
        ctx.drawString(font, text, x + 6, y + 2, Style.color(color), Style.shadow(false));

        return width;
    }

    public static final String[] RESET_ICON = {
            " ####  ",
            "##  ## ",
            "#    # ",
            "#     #",
            "#    ##",
            "##  ###",
            " ####  "};

    public static void icon(GuiContext ctx, String[] mask, int x, int y, int color)
    {
        for (int row = 0; row < mask.length; ++row)
        {
            String line = mask[row];

            for (int column = 0; column < line.length(); ++column)
            {
                if (line.charAt(column) == '#')
                {
                    rect(ctx, x + column, y + row, 1, 1, color);
                }
            }
        }
    }

    public static void tracked(GuiContext ctx, Font font, String text, int x, int y, int color, int spacing)
    {
        int cursor = x;

        for (int i = 0; i < text.length(); ++i)
        {
            String glyph = String.valueOf(text.charAt(i));
            ctx.drawString(font, glyph, cursor, y, Style.color(color), Style.shadow(false));
            cursor += font.width(glyph) + spacing;
        }
    }

    public static int trackedWidth(Font font, String text, int spacing)
    {
        return font.width(text) + Math.max(0, text.length() - 1) * spacing;
    }

    public static String fit(Font font, String text, int maxWidth)
    {
        if (font.width(text) <= maxWidth)
        {
            return text;
        }

        String ellipsis = "…";
        int limit = maxWidth - font.width(ellipsis);
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < text.length(); ++i)
        {
            if (font.width(builder.toString() + text.charAt(i)) > limit)
            {
                break;
            }

            builder.append(text.charAt(i));
        }

        return builder + ellipsis;
    }

    public static void textRight(GuiContext ctx, Font font, String text, int right, int y, int color)
    {
        ctx.drawString(font, text, right - font.width(text), y, Style.color(color), Style.shadow(false));
    }

    private static int radius(int radius, int width, int height)
    {
        int limit = Math.min(width, height) / 2;

        return Math.max(0, Math.min(Math.min(radius, INSETS.length - 1), limit));
    }

    private static int firstZero(int[] insets)
    {
        for (int i = 0; i < insets.length; ++i)
        {
            if (insets[i] == 0)
            {
                return i;
            }
        }

        return insets.length;
    }
}
