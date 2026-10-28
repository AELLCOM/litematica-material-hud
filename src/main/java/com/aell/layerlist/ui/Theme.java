package com.aell.layerlist.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Цвета смысла и размеры старых экранов мода (HUD, позиция HUD, кнопки {@code ZfButton}); каркас
 * aell-ui берёт свои токены из {@code Ui}. Цвет — тройкой: сам цвет (текст, рамка в фокусе),
 * {@code _FILL} — заливка, {@code _EDGE} — рамка в покое.
 *
 * <ul>
 *   <li>{@code GREEN} — готово, главное действие («Excel» окна выгрузки, «Готово» редактора);</li>
 *   <li>{@code BLUE} — сведения, идёт работа;</li>
 *   <li>{@code DISCORD} — фирменный «блюрпл» Discord {@code #5865F2}: только то, что уходит в Discord
 *       (кнопка формата «Discord» окна выгрузки), — чтобы её узнавали с первого взгляда, как зелёный
 *       Excel;</li>
 *   <li>{@code RED}/{@code ORANGE}/{@code YELLOW}/{@code CYAN} — счётчики HUD.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class Theme
{
    public static final int BG = 0xE0101012;

    public static final int PANEL      = 0xE61B1B1F;
    public static final int PANEL_DEEP = 0xF0121215;
    public static final int EDGE       = 0x29FFFFFF;
    public static final int EDGE_SOFT  = 0x1AFFFFFF;

    public static final int ROW       = 0x12FFFFFF;
    public static final int ROW_HOVER = 0x24FFFFFF;
    public static final int ROW_DEEP  = 0x40000000;

    public static final int TEXT  = 0xFFFFFFFF;
    public static final int MUTED = 0x99FFFFFF;
    public static final int FAINT = 0x57FFFFFF;
    public static final int OFF   = 0x40FFFFFF;

    public static final int GREEN      = 0xFF4ADE80;
    public static final int GREEN_FILL = 0x2B4ADE80;
    public static final int GREEN_EDGE = 0x8C4ADE80;

    /** Золото — включённый режим, который работает сам (кнопка «Auto» у переноса). */
    public static final int GOLD      = 0xFFF5C451;
    public static final int GOLD_FILL = 0x33F5C451;
    public static final int GOLD_EDGE = 0xA6F5C451;
    public static final int GOLD_GLOW = 0x2EF5C451;

    public static final int BLUE      = 0xFF7AA8F5;
    public static final int BLUE_FILL = 0x387AA8F5;
    public static final int BLUE_EDGE = 0x8C7AA8F5;

    public static final int DISCORD      = 0xFF5865F2;
    public static final int DISCORD_FILL = 0x385865F2;
    public static final int DISCORD_EDGE = 0x8C5865F2;

    public static final int RED    = 0xFFF87171;
    public static final int ORANGE = 0xFFFB923C;
    public static final int YELLOW = 0xFFFACC15;
    public static final int CYAN   = 0xFF67E8F9;

    public static final int PAD        = 10;
    public static final int GAP        = 4;
    public static final int ROW_H      = 18;
    public static final int RADIUS     = 5;
    public static final int RADIUS_BIG = 8;

    private Theme() {}

    public static int alpha(int color, float alpha)
    {
        int a = (int) (((color >>> 24) & 0xFF) * clamp(alpha));

        return (a << 24) | (color & 0x00FFFFFF);
    }

    public static int mix(int from, int to, float t)
    {
        // Ванильный стиль: смешиваются уже цвета Minecraft — иначе смесь двух цветов Aell не узнать по таблице
        from = com.aell.layerlist.shaded.ui.Style.color(from);
        to = com.aell.layerlist.shaded.ui.Style.color(to);
        float k = clamp(t);
        int result = 0;

        for (int shift = 0; shift <= 24; shift += 8)
        {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            result |= (int) (a + (b - a) * k) << shift;
        }

        return result;
    }

    private static float clamp(float value)
    {
        return value < 0.0F ? 0.0F : value > 1.0F ? 1.0F : value;
    }
}
