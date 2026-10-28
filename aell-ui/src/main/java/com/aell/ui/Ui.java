package com.aell.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class Ui
{
    private Ui() {}

    public static final int UNIT = 2;

    public static final int PAD_MICRO = 2;
    public static final int PAD_IN = 4;
    public static final int PAD_REL = 6;
    public static final int PAD_GROUP = 10;
    public static final int PAD_SECTION = 16;

    public static final int OUTER = 8;

    public static final int PANEL_MAX_W = 360;

    /** Панель растёт под ряд вкладок, но не шире этой доли окна: две трети. */
    public static final int PANEL_GROW_NUM = 2;
    public static final int PANEL_GROW_DEN = 3;

    public static final int WINDOW_NARROW = 380;

    public static final int PANEL_TIGHT = 300;

    public static final int PANEL_VERY_TIGHT = 240;

    public static final int BODY_MIN_FOR_SECTIONS = 60;

    public static final int WINDOW_SHORT = 200;

    public static final int ROW_H = 20;
    public static final int ROW_GAP = 2;
    public static final int ROW_H_CONFLICT = 30;
    public static final int CONFLICT_LINE_H = 10;

    public static final int SECTION_H = 14;
    public static final int SECTION_PAD_TOP = PAD_GROUP;
    public static final int SECTION_PAD_BOTTOM = PAD_IN;

    public static final int TABS_H = 20;
    public static final int TAB_PAD_X = OUTER;
    public static final int TAB_PAD_X_ROOMY = PAD_IN;
    public static final int TAB_PAD_X_MIN = PAD_MICRO;
    public static final int TAB_UNDERLINE = 2;
    public static final int TAB_DOT = 3;
    public static final int TAB_ICON = 12;
    public static final int TAB_ICON_GAP = 3;
    public static final int TAB_LABEL_MIN = 26;
    public static final int TAB_ARROW_W = 10;
    public static final int TAB_ARROW_GLYPH_W = 4;
    public static final int TAB_ARROW_GLYPH_H = 7;
    public static final int TAB_SCROLL_STEP = 24;

    public static final int HEADER_H = 26;
    public static final int HEADER_ICON = 16;
    public static final int HEADER_ICON_GAP = PAD_REL;
    public static final int STATUS_H = 14;
    public static final int STATUS_DOT = 4;
    public static final int FOOTER_H = 24;
    public static final int FOOTER_H_TIGHT = 18;
    public static final int BUTTON_H = 16;
    public static final int BUTTON_PAD_X = PAD_REL;

    public static final int CTRL_W = 110;
    public static final int CTRL_W_TIGHT = 84;
    public static final int CTRL_H = 16;

    public static final int RESET_W = 14;
    public static final int ICON_W = 7;

    public static final int SCROLL_W = 4;
    public static final int SCROLL_GAP = 3;
    public static final int SCROLL_THUMB_MIN = 16;
    public static final int SCROLL_WHEEL_ROWS = 3;

    public static final int R_ROW = 3;
    public static final int R_CTRL = 3;
    public static final int R_PILL = 6;
    public static final int R_PLATE = 4;
    public static final int R_BAR = 2;
    public static final int BORDER = 1;

    public static final int MARK_W = 2;

    public static final int SWATCH = 8;
    public static final int SWATCH_GAP = PAD_IN;
    public static final int SWATCH_COL = SWATCH + SWATCH_GAP;

    public static final int INDENT = 8;

    public static final int SWITCH_W = 28;
    public static final int SWITCH_H = 12;
    public static final int SWITCH_KNOB = 12;

    public static final int TRACK_W = 70;
    public static final int TRACK_W_TIGHT = 44;
    public static final int TRACK_H = 4;
    public static final int THUMB_W = 4;
    public static final int THUMB_H = 12;
    public static final int FIELD_W = 36;

    public static final int SEARCH_W = 120;
    public static final int SEARCH_W_TIGHT = 80;
    public static final int SEARCH_H = 14;
    public static final int SEARCH_ICON = 7;

    public static final int HEAD_BTN_H = 14;
    public static final int HEAD_BTN_PAD_X = PAD_REL;
    public static final int HEAD_BTN_GAP = PAD_IN;
    public static final int HEAD_BTN_MAX = 3;

    public static final int HIT_MIN = 14;

    public static final int TEXT_H = 9;
    public static final int GLYPH_H = 8;
    public static final int TEXT_DY = (ROW_H - GLYPH_H) / 2;

    // Бегущая строка (Paint.marquee): пауза в начале, скорость в px GUI/с, зазор между повторами
    public static final long MARQUEE_PAUSE_MS = 1500L;
    public static final int MARQUEE_SPEED = 30;
    public static final int MARQUEE_GAP = 24;
    /** «Очень длинная» строка: имя или подпись шире этого в ширину меню и полей не идёт, а бежит (Paint.marquee). */
    public static final int LONG_TEXT_W = 200;
    public static final String NOTE_MARK = "?";
    public static final int TIP_MAX_W = 220;
    public static final int TIP_PAD = PAD_IN;
    public static final int TIP_LINE = 10;
    public static final long TIP_DELAY_MS = 400L;
    public static final int LABEL_MAX_CHARS = 24;

    public static final long BLINK_MS = 200L;
    public static final long CARET_MS = 500L;
    public static final long JUMP_HIGHLIGHT_MS = 1000L;
    public static final long CONFIRM_MS = 3000L;
    public static final long ANIM_MS = 150L;
    public static final float ANIM_HOVER_MS = 110.0F;
    public static final float ANIM_KNOB_MS = 130.0F;

    public static final int STACK_MAX = 3;
    public static final int STEP_FAST = 10;
    public static final String TITLE_SEPARATOR = " · ";

    public static final int C_DIM         = 0xB0000000;
    public static final int C_PANEL       = 0xF0121215;
    public static final int C_CHROME      = 0xF01A1A20;
    public static final int C_ROW_HOVER   = 0x14FFFFFF;
    public static final int C_ROW_FOCUS   = 0x22FFFFFF;
    public static final int C_DIVIDER     = 0x24FFFFFF;
    public static final int C_PANEL_EDGE  = 0x40FFFFFF;

    public static final int C_TEXT        = 0xFFE8E8EC;
    public static final int C_TEXT_2      = 0xFF9A9AA6;
    public static final int C_TEXT_3      = 0xFF787885;
    public static final int C_TEXT_OFF    = 0xFF55555F;

    public static final int C_ACCENT      = 0xFF4FA3FF;
    public static final int C_ON          = 0xFF57C97A;
    public static final int C_OFF         = 0xFF6E6E7A;
    public static final int C_CHANGED     = 0xFFE0B341;
    public static final int C_DANGER      = 0xFFE05B4A;
    public static final int C_WARN        = 0xFFE0902E;
    public static final int C_FOREIGN     = 0xFF9B7FE0;
    /** Выбранная строка списка (кит, {@code ScrollList}): заливка и рамка в фокусе. */
    public static final int C_SELECT_FILL = 0x387AA8F5;
    public static final int C_SELECT_EDGE = 0x8C7AA8F5;

    /** Золото значка поиска — тот же тон, что у отметки изменённого значения. */
    public static final int C_GOLD        = 0xFFE0B341;

    public static final int C_TRACK       = 0x18FFFFFF;
    public static final int C_THUMB       = 0x50FFFFFF;
    public static final int C_THUMB_DRAG  = 0x80FFFFFF;

    public static int alpha(int color, float k)
    {
        int a = (int) (((color >>> 24) & 0xFF) * clamp01(k));

        return (a << 24) | (color & 0x00FFFFFF);
    }

    public static int darken(int color, float k)
    {
        float f = 1.0F - clamp01(k);
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static final float PRESS_DARKEN = 0.15F;
    public static final float A_DISABLED = 0.35F;
    public static final float A_SWATCH_OFF = 0.4F;
    public static final float A_TRACK_OFF = 0.5F;
    public static final float A_PREVIEW = 0.6F;
    public static final float A_GUIDE = 0.7F;
    public static final float A_KEY_EDGE = 0.62F;
    public static final float A_KEY_EDGE_UNSET = 0.28F;
    public static final float A_KEY_FILL = 0.12F;

    public static float clamp01(float v)
    {
        return v < 0.0F ? 0.0F : v > 1.0F ? 1.0F : v;
    }

    public static int clamp(int v, int min, int max)
    {
        return v < min ? min : v > max ? max : v;
    }
}
