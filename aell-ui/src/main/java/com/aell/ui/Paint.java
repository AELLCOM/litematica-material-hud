package com.aell.ui;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;

@Environment(EnvType.CLIENT)
public final class Paint
{
    private Paint() {}

    public static final String[] RESET_ICON = {
            " ####  ",
            "##  ## ",
            "#    # ",
            "#     #",
            "#    ##",
            "##  ###",
            " ####  "};

    public static final String[] CHEVRON_LEFT = {
            "   #",
            "  ##",
            " ## ",
            "##  ",
            " ## ",
            "  ##",
            "   #"};

    public static final String[] CHEVRON_RIGHT = {
            "#   ",
            "##  ",
            " ## ",
            "  ##",
            " ## ",
            "##  ",
            "#   "};

    /** Лупа 7×7: кольцо слева сверху, ручка вправо вниз. Рисуется в {@link Ui#C_GOLD}. */
    public static final String[] SEARCH_ICON = {
            " ###   ",
            "#   #  ",
            "#   #  ",
            "#   #  ",
            " ### # ",
            "     ##",
            "      #"};

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

    public static void resetGlyph(GuiContext ctx, int x, int y, int color)
    {
        icon(ctx, RESET_ICON, x, y, color);
    }

    public static void rect(GuiContext ctx, int x, int y, int w, int h, int color)
    {
        if (w > 0 && h > 0 && (color >>> 24) != 0)
        {
            ctx.fill(x, y, x + w, y + h, Style.color(color));
        }
    }

    /** {@code ctx.fill} по углам с цветом текущего стиля ({@link Style#color}). */
    public static void fill(GuiContext ctx, int x1, int y1, int x2, int y2, int color)
    {
        ctx.fill(x1, y1, x2, y2, Style.color(color));
    }

    /** {@code ctx.drawString} с цветом и тенью текущего стиля. */
    public static void text(GuiContext ctx, Font font, String text, int x, int y, int color, boolean shadow)
    {
        ctx.drawString(font, text, x, y, Style.color(color), Style.shadow(shadow));
    }

    public static void text(GuiContext ctx, Font font, String text, int x, int y, int color)
    {
        text(ctx, font, text, x, y, color, true);
    }

    public static void text(GuiContext ctx, Font font, net.minecraft.network.chat.Component text, int x, int y, int color,
            boolean shadow)
    {
        ctx.drawString(font, text, x, y, Style.color(color), Style.shadow(shadow));
    }

    public static void text(GuiContext ctx, Font font, net.minecraft.network.chat.Component text, int x, int y, int color)
    {
        text(ctx, font, text, x, y, color, true);
    }

    public static void text(GuiContext ctx, Font font, net.minecraft.util.FormattedCharSequence text, int x, int y,
            int color, boolean shadow)
    {
        ctx.drawString(font, text, x, y, Style.color(color), Style.shadow(shadow));
    }

    public static void text(GuiContext ctx, Font font, net.minecraft.util.FormattedCharSequence text, int x, int y,
            int color)
    {
        text(ctx, font, text, x, y, color, true);
    }

    /** {@code ctx.drawCenteredString} с цветом текущего стиля (тень у него всегда есть). */
    public static void centered(GuiContext ctx, Font font, String text, int centerX, int y, int color)
    {
        ctx.drawCenteredString(font, text, centerX, y, Style.color(color));
    }

    public static void centered(GuiContext ctx, Font font, net.minecraft.network.chat.Component text, int centerX, int y,
            int color)
    {
        ctx.drawCenteredString(font, text, centerX, y, Style.color(color));
    }

    private static final int[][] ARC = {
            {},
            {0},
            {1, 0},
            {2, 1, 0},
            {2, 1, 0, 0},
            {3, 2, 1, 0, 0},
            {3, 2, 1, 1, 0, 0},
            {4, 2, 1, 1, 0, 0, 0},
            {4, 3, 2, 1, 1, 0, 0, 0}};

    public static void round(GuiContext ctx, int x, int y, int w, int h, int radius, int color)
    {
        int r = clampRadius(Style.radius(radius), w, h);

        if (r == 0)
        {
            rect(ctx, x, y, w, h, color);

            return;
        }

        int[] insets = ARC[r];

        for (int i = 0; i < r; ++i)
        {
            int inset = insets[i];
            rect(ctx, x + inset, y + i, w - inset * 2, 1, color);
            rect(ctx, x + inset, y + h - 1 - i, w - inset * 2, 1, color);
        }

        rect(ctx, x, y + r, w, h - r * 2, color);
    }

    public static void roundBorder(GuiContext ctx, int x, int y, int w, int h, int radius, int color)
    {
        int r = clampRadius(Style.radius(radius), w, h);

        if (r == 0)
        {
            border(ctx, x, y, w, h, color);

            return;
        }

        int[] insets = ARC[r];
        int straight = insets[0] == 0 ? 0 : firstZero(insets);

        rect(ctx, x + r, y, w - r * 2, 1, color);
        rect(ctx, x + r, y + h - 1, w - r * 2, 1, color);
        rect(ctx, x, y + straight, 1, h - straight * 2, color);
        rect(ctx, x + w - 1, y + straight, 1, h - straight * 2, color);

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
            rect(ctx, x + w - previous, y + i, span, 1, color);
            rect(ctx, x + inset, y + h - 1 - i, span, 1, color);
            rect(ctx, x + w - previous, y + h - 1 - i, span, 1, color);
        }
    }

    public static void card(GuiContext ctx, int x, int y, int w, int h, int radius, int fill, int edge)
    {
        // Ванильный стиль: панель окна — фон списка Minecraft с тёмной рамкой вместо карточки Aell
        if (Style.vanilla() && Style.isPanel(fill))
        {
            Vanilla.panel(ctx, x, y, w, h);
            return;
        }

        round(ctx, x, y, w, h, radius, fill);

        if (edge != 0)
        {
            roundBorder(ctx, x, y, w, h, radius, edge);
        }
    }

    private static int clampRadius(int radius, int w, int h)
    {
        int limit = Math.min(w, h) / 2;

        return Math.max(0, Math.min(Math.min(radius, ARC.length - 1), limit));
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

    public static void border(GuiContext ctx, int x, int y, int w, int h, int color)
    {
        if (w <= 0 || h <= 0)
        {
            return;
        }

        int t = Ui.BORDER;

        rect(ctx, x, y, w, t, color);
        rect(ctx, x, y + h - t, w, t, color);
        rect(ctx, x, y + t, t, h - t * 2, color);
        rect(ctx, x + w - t, y + t, t, h - t * 2, color);
    }

    public static void focus(GuiContext ctx, int x, int y, int w, int h)
    {
        // Ванильный фокус — белая рамка, как у полей и списков Minecraft
        if (Style.vanilla())
        {
            Vanilla.outline(ctx, x, y, w, h, Style.V_WHITE);
            return;
        }

        roundBorder(ctx, x, y, w, h, Ui.R_CTRL, Ui.C_ACCENT);
    }

    public static int mix(int from, int to, float t)
    {
        // Ванильный стиль: смешиваются уже цвета Minecraft — иначе смесь двух цветов Aell не узнать по таблице
        from = Style.color(from);
        to = Style.color(to);
        float k = Ui.clamp01(t);
        int result = 0;

        for (int shift = 0; shift <= 24; shift += 8)
        {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            result |= (int) (a + (b - a) * k) << shift;
        }

        return result;
    }

    public static void rowText(GuiContext ctx, Font font, String text, int x, int rowTop, int color, boolean shadow)
    {
        ctx.drawString(font, text, x, rowTop + Ui.TEXT_DY, Style.color(color), Style.shadow(shadow));
    }

    public static void texture(GuiContext ctx, Identifier texture, int x, int y, int size)
    {
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, size, size, size, size);
    }

    public static void midText(GuiContext ctx, Font font, String text, int x, int y, int h, int color, boolean shadow)
    {
        ctx.drawString(font, text, x, y + (h - Ui.GLYPH_H) / 2, Style.color(color), Style.shadow(shadow));
    }

    public static void midTextRight(GuiContext ctx, Font font, String text, int right, int y, int h,
            int color, boolean shadow)
    {
        ctx.drawString(font, text, right - font.width(text), y + (h - Ui.GLYPH_H) / 2, Style.color(color),
                Style.shadow(shadow));
    }

    /**
     * Бегущая строка — правило модов семейства (24.10.2026): необязательная строка (подвал, статус, подсказка, имя)
     * ширину плитки не задаёт. Влезает в {@code room} — рисуется как есть; длиннее — стоит {@link Ui#MARQUEE_PAUSE_MS},
     * потом бежит влево по кругу, обрезанная по {@code room}. Ширину плитки считать без таких строк (или не шире
     * {@link Ui#LONG_TEXT_W}). Круг — по общим часам: так бегут строки, на которые не наводят (HUD).
     */
    public static void marquee(GuiContext ctx, Font font, String text, int x, int y, int room, int color,
            boolean shadow)
    {
        marquee(ctx, font, text, x, y, room, color, shadow, 0L);
    }

    /**
     * То же, но круг отсчитывается от {@code since} ({@code Util.getMillis()} момента наведения): строка под курсором
     * всегда начинается с начала и паузы. В меню и полях длинная строка без наведения — {@link #fit} с многоточием.
     */
    public static void marquee(GuiContext ctx, Font font, String text, int x, int y, int room, int color,
            boolean shadow, long since)
    {
        int textWidth = font.width(text);

        if (textWidth <= room)
        {
            text(ctx, font, text, x, y, color, shadow);
            return;
        }

        if (room <= 0)
        {
            return;
        }

        int span = textWidth + Ui.MARQUEE_GAP;
        long runMs = span * 1000L / Ui.MARQUEE_SPEED;
        long phase = Math.max(0L, net.minecraft.util.Util.getMillis() - since) % (Ui.MARQUEE_PAUSE_MS + runMs);
        int offset = phase < Ui.MARQUEE_PAUSE_MS ? 0
                : (int) ((phase - Ui.MARQUEE_PAUSE_MS) * Ui.MARQUEE_SPEED / 1000L);

        ctx.enableScissor(x, y - 1, x + room, y + font.lineHeight);
        text(ctx, font, text, x - offset, y, color, shadow);
        text(ctx, font, text, x - offset + span, y, color, shadow);
        ctx.disableScissor();
    }

    /** {@link #marquee(GuiContext, Font, String, int, int, int, int, boolean)} по центру строки высоты {@code h}. */
    public static void midMarquee(GuiContext ctx, Font font, String text, int x, int y, int h, int room, int color,
            boolean shadow)
    {
        marquee(ctx, font, text, x, y + (h - Ui.GLYPH_H) / 2, room, color, shadow, 0L);
    }

    /**
     * Строка меню или поля высоты {@code h}: длиннее {@code room} — под курсором ({@code since >= 0}, момент наведения)
     * бежит, без наведения ({@code since < 0}) — обрезана многоточием.
     */
    public static void midMarquee(GuiContext ctx, Font font, String text, int x, int y, int h, int room, int color,
            boolean shadow, long since)
    {
        if (since < 0L)
        {
            midText(ctx, font, fit(font, text, room), x, y, h, color, shadow);
            return;
        }

        marquee(ctx, font, text, x, y + (h - Ui.GLYPH_H) / 2, room, color, shadow, since);
    }

    public static String fit(Font font, String text, int maxWidth)
    {
        if (maxWidth <= 0)
        {
            return "";
        }

        if (font.width(text) <= maxWidth)
        {
            return text;
        }

        String tail = "…";
        int limit = maxWidth - font.width(tail);

        if (limit <= 0)
        {
            return "";
        }

        StringBuilder out = new StringBuilder();
        int used = 0;

        for (int i = 0; i < text.length(); ++i)
        {
            int w = font.width(String.valueOf(text.charAt(i)));

            if (used + w > limit)
            {
                break;
            }

            out.append(text.charAt(i));
            used += w;
        }

        return out + tail;
    }

    public static void highlighted(GuiContext ctx, Font font, String text, int from, int len,
            int x, int rowTop, int color, int hit, int maxWidth)
    {
        String shown = fit(font, text, maxWidth);

        if (from < 0 || from >= shown.length())
        {
            rowText(ctx, font, shown, x, rowTop, color, true);
            return;
        }

        int end = Math.min(shown.length(), from + len);
        String head = shown.substring(0, from);
        String mid = shown.substring(from, end);
        String tail = shown.substring(end);

        int cx = x;
        rowText(ctx, font, head, cx, rowTop, color, true);
        cx += font.width(head);
        rowText(ctx, font, mid, cx, rowTop, hit, true);
        cx += font.width(mid);
        rowText(ctx, font, tail, cx, rowTop, color, true);
    }

    public static boolean hit(int mx, int my, int x, int y, int w, int h)
    {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
