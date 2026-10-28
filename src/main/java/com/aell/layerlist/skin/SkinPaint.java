package com.aell.layerlist.skin;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;

/**
 * Кнопка malilib в виде кнопок aell-ui: тёмная плашка со скруглением {@link Ui#R_CTRL}, рамка
 * {@link Ui#C_DIVIDER}, наведение светлит плашку и рамку за {@link Ui#ANIM_HOVER_MS}. Кнопки других модов —
 * с рамкой цвета {@link Ui#C_FOREIGN}: видно, что это не Litematica.
 */
@Environment(EnvType.CLIENT)
public final class SkinPaint
{
    private static final int FILL = 0xE81A1A20;
    private static final int FILL_HOVER = 0xF02A2A34;
    private static final float ADDON_EDGE_IDLE = 0.55F;
    private static final int HEADER_H = 24;
    private static final int TITLE_X = 12;
    private static final int FIELD_FILL = 0xF00E0E11;
    private static final int TOOLTIP_FILL = 0xF2121215;
    private static final int TOOLTIP_LINE_H = 10;
    private static final int TEXT_PAD = 6;
    private static final int TIGHT_PAD = 2;

    private SkinPaint() {}

    /** Фон экрана Litematica: затемнение мира и шапка {@link Ui#C_CHROME} с разделителем. */
    public static void background(GuiContext ctx, GuiBase screen)
    {
        Paint.rect(ctx, 0, 0, screen.width, screen.height, Ui.C_DIM);
        Paint.rect(ctx, 0, 0, screen.width, HEADER_H, Ui.C_CHROME);
        Paint.rect(ctx, 0, HEADER_H, screen.width, 1, Ui.C_DIVIDER);
    }

    public static void title(GuiContext ctx, GuiBase screen)
    {
        Paint.midText(ctx, Minecraft.getInstance().font, screen.getTitleString(), TITLE_X, 0, HEADER_H, Ui.C_TEXT, false);
    }

    /** Окно или панель malilib ({@code drawOutlinedBox}: рамка снаружи на 1 px) — скруглённая карточка. */
    public static void panel(GuiContext ctx, int x, int y, int w, int h, int background)
    {
        Paint.card(ctx, x - 1, y - 1, w + 2, h + 2, Ui.R_PLATE, SkinColors.map(background), Ui.C_PANEL_EDGE);
    }

    /** Поле ввода malilib: тёмная плашка, рамка светлеет в фокусе и становится цвета акцента. */
    public static void field(GuiContext ctx, int x, int y, int w, int h, boolean focused, boolean active)
    {
        int edge = ! active ? Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF) : focused ? Ui.C_ACCENT : Ui.C_PANEL_EDGE;
        Paint.card(ctx, x, y, w, h, Ui.R_CTRL, FIELD_FILL, edge);
    }

    /** Подсказка malilib в виде подсказок aell-ui; место считается как у {@code RenderUtils.drawHoverText}. */
    public static void tooltip(GuiContext ctx, int x, int y, List<String> lines)
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (lines.isEmpty() || screen == null)
        {
            return;
        }

        Font font = Minecraft.getInstance().font;
        List<String> split = new ArrayList<>();
        int maxW = 0;

        for (String line : lines)
        {
            for (String part : line.split("\\n"))
            {
                split.add(part);
                maxW = Math.max(maxW, font.width(part));
            }
        }

        int textH = split.size() * TOOLTIP_LINE_H - 2;
        int textX = x + 4;
        int textY = Math.max(8, y - textH - 6);

        if (textX + maxW + 6 > screen.width)
        {
            textX = Math.max(2, screen.width - maxW - 8);
        }

        // Свой слой: текст кнопок под подсказкой не проступает сквозь плашку
        ctx.nextStratum();
        Paint.card(ctx, textX - 4, textY - 4, maxW + 8, textH + 8, Ui.R_CTRL, TOOLTIP_FILL, Ui.C_PANEL_EDGE);

        for (String part : split)
        {
            ctx.drawString(font, part, textX, textY, Ui.C_TEXT, false);
            textY += TOOLTIP_LINE_H;
        }
    }

    /** Шаг наведения к 0 или 1 за время с прошлого кадра ({@code lastNanos == 0} — первый кадр). */
    public static float approach(float current, boolean target, long lastNanos)
    {
        float step = lastNanos == 0L ? 0.0F : (System.nanoTime() - lastNanos) / 1_000_000.0F / Ui.ANIM_HOVER_MS;
        return target ? Math.min(1.0F, current + step) : Math.max(0.0F, current - step);
    }

    public static void button(GuiContext ctx, int x, int y, int w, int h, float hover, boolean enabled, boolean addon)
    {
        int fill = enabled ? Paint.mix(FILL, FILL_HOVER, hover) : FILL;
        int edge;

        if (! enabled)
        {
            edge = Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF);
        }
        else if (addon)
        {
            edge = Paint.mix(Ui.alpha(Ui.C_FOREIGN, ADDON_EDGE_IDLE), Ui.C_FOREIGN, hover);
        }
        else
        {
            edge = Paint.mix(Ui.C_DIVIDER, Ui.C_PANEL_EDGE, hover);
        }

        Paint.round(ctx, x, y, w, h, Ui.R_CTRL, fill);
        Paint.roundBorder(ctx, x, y, w, h, Ui.R_CTRL, edge);
    }

    /** Подпись: по центру или слева после значка ({@code iconW} — его ширина с зазором), обрезанная по ширине. */
    public static void label(GuiContext ctx, String text, int x, int y, int w, int h, boolean centered, int iconW,
            float hover, boolean enabled)
    {
        if (text == null || text.isBlank())
        {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int color = enabled ? Paint.mix(Ui.C_TEXT_2, Ui.C_TEXT, hover) : Ui.C_TEXT_OFF;

        if (centered)
        {
            // Litematica делает кнопку под подпись впритык (+10): поля сужаются, прежде чем резать текст
            String shown = Paint.fit(font, text, Math.max(0, w - TIGHT_PAD * 2));
            Paint.midText(ctx, font, shown, x + (w - font.width(shown)) / 2, y, h, color, false);
        }
        else
        {
            int left = x + TEXT_PAD + iconW;
            String shown = Paint.fit(font, text, Math.max(0, x + w - TIGHT_PAD - left));
            Paint.midText(ctx, font, shown, left, y, h, color, false);
        }
    }
}
