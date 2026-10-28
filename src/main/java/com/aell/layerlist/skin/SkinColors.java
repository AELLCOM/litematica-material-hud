package com.aell.layerlist.skin;

import com.aell.layerlist.shaded.ui.Ui;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Палитра malilib и Litematica → токены aell-ui. Экраны и виджеты malilib рисуют заливки и рамки небольшим
 * набором цветов (выписаны из исходников malilib 0.30 и Litematica 0.29); в перекрашенном экране каждый из них
 * заменяется своим токеном. Незнакомый цвет (материалы, проверка схемы, цвета из настроек) остаётся как есть.
 */
@Environment(EnvType.CLIENT)
public final class SkinColors
{
    /** Плашка окна и панели. */
    public static final int PANEL = Ui.C_PANEL;
    /** Полупрозрачная панель браузеров файлов и списков. */
    public static final int PANEL_SOFT = 0xD8121215;
    private static final int ROW_ODD = 0xD0141418;
    private static final int ROW_EVEN = 0xD01B1B21;
    private static final int ROW_HOT = 0xD02A2A34;
    private static final int ZEBRA = 0x08FFFFFF;
    private static final int ZEBRA_2 = 0x10FFFFFF;

    private SkinColors() {}

    public static int map(int color)
    {
        return switch (color)
        {
            // рамки, разделители (GuiBase.COLOR_HORIZONTAL_BAR), рамка выпадающего списка
            case 0xFF999999, 0xFFC0C0C0 -> Ui.C_PANEL_EDGE;
            // фон окон malilib
            case 0xFF000000, 0xF0000000, 0xE0000000, 0xFF101010 -> PANEL;
            case 0xFF202020 -> Ui.C_CHROME;
            // фон браузеров файлов, затемнение экрана malilib
            case 0xA0000000, 0xB0000000 -> PANEL_SOFT;
            // строки malilib: «зебра», наведение, выбор
            case 0x20FFFFFF -> ZEBRA;
            case 0x30FFFFFF -> ZEBRA_2;
            case 0x38FFFFFF -> Ui.C_ROW_HOVER;
            case 0x50FFFFFF, 0x50C0C0C0 -> Ui.C_ROW_FOCUS;
            case 0x70FFFFFF -> Ui.C_SELECT_FILL;
            // строки Litematica: нечётная, чётная, наведение/выбор
            case 0xA0101010 -> ROW_ODD;
            case 0xA0303030 -> ROW_EVEN;
            case 0xA0707070 -> ROW_HOT;
            // обводка выбранной строки
            case 0xFFE0E0E0, 0xEEEEEEEE -> Ui.C_SELECT_EDGE;
            case 0xFF90D0F0 -> Ui.C_ACCENT;
            // полоса прокрутки malilib: дорожка
            case 0x44FFFFFF -> Ui.C_TRACK;
            default -> color;
        };
    }

    /** Фон окна или панели malilib — такую плашку рисуем скруглённой карточкой. */
    public static boolean isPanel(int background)
    {
        return switch (background)
        {
            case 0xFF000000, 0xF0000000, 0xE0000000, 0xFF101010, 0xA0000000, 0xB0000000 -> true;
            default -> false;
        };
    }
}
