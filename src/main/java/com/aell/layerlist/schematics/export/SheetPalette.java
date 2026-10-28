package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Цвета «Таблицы сбора» по ролям: фон листа, строки, панели, шапки, итоги, рамка ввода ников,
 * статусы, диаграмма. Лист строится одной разметкой, палитра решает только цвета — одна на
 * Excel, Google Таблицы и таблицу команды (опция {@link SchematicsConfig#SHEETS_PALETTE});
 * выбирается в окне перед выгрузкой и в настройках вкладки «Схемы».
 *
 * <ul>
 *   <li>{@link #DARK_VIOLET} — тёмная фиолетовая тема канона Aell_ v6, как у PNG-карточки
 *       (значения — из {@link CanonPalette}, docs/integration/M26/theme.md);</li>
 *   <li>{@link #LIGHT_GRAY} — светлая: те же роли, но поверхности и шапки серые, акценты —
 *       фиолетовый канона (полоса заголовка, итоги, подсказки, рамка полей) и золото (подписи
 *       на тёмном, заголовок рамки ввода). Статусы — светлые заливки тех же тонов.</li>
 * </ul>
 *
 * <p>Ники красятся 16 тонами {@link CanonPalette#nick} в обеих палитрах: это светлая ступень
 * тонов, тёмный текст на ней читается и на светлом листе, и на тёмном.
 */
@Environment(EnvType.CLIENT)
public record SheetPalette(
        int canvas, int text, int textMuted,
        int row, int rowAlt, int panel, int total, int border,
        int titleFill, int titleText, int metaText,
        int headFill, int headText, int subText,
        int sumText, int hintText,
        int inputHeadFill, int inputHeadText, int inputHintFill, int inputHintText, int inputFill, int inputEdge,
        int doneFill, int doneText, int takenFill, int takenText,
        int chipText, int tab,
        int chartTotal, int chartDone, int chartTitle)
{
    /** Золото канона (верх янтарной тройки §8) — второй акцент светлой палитры. */
    private static final int GOLD = CanonPalette.AMBER_TOP;
    private static final int GOLD_DEEP = 0xF5B740;
    private static final int GRAPHITE = 0x3A3A43;
    private static final int INK = 0x26262C;

    static final SheetPalette DARK_VIOLET = new SheetPalette(
            CanonPalette.SHEET_CANVAS, CanonPalette.SHEET_TEXT, CanonPalette.SHEET_TEXT_MUTED,
            CanonPalette.SHEET_ROW, CanonPalette.SHEET_ROW_ALT, CanonPalette.SHEET_PANEL, CanonPalette.SHEET_TOTAL,
            CanonPalette.SHEET_BORDER,
            CanonPalette.VIOLET, CanonPalette.WHITE, CanonPalette.SUBTITLE,
            CanonPalette.VIOLET_DEEP, CanonPalette.WHITE, CanonPalette.SUBTITLE,
            CanonPalette.FRAME, CanonPalette.OVERLINE,
            CanonPalette.SHEET_INPUT_HEAD, CanonPalette.WHITE, CanonPalette.VIOLET_DEEP, CanonPalette.OVERLINE,
            CanonPalette.SHEET_INPUT, CanonPalette.SHEET_INPUT_EDGE,
            CanonPalette.DONE_FILL, CanonPalette.DONE_TEXT, CanonPalette.TAKEN_FILL, CanonPalette.TAKEN_TEXT,
            CanonPalette.SHEET_CHIP_TEXT, CanonPalette.VIOLET,
            CanonPalette.CHART_TOTAL, CanonPalette.CHART_DONE, CanonPalette.SUBTITLE);

    /*
     * Светлая серая. Поверхности — нейтральная серая шкала (фон 0.96 → панель 0.98 → строка 1.0,
     * чередование 0.975, итоги 0.94), текст — почти чёрный графит (15:1 на строке), вторичный —
     * 6:1. Шапки — графит с белым (11.3:1) и золотом (7.1:1). Фиолетовый: полоса заголовка (белый
     * на нём 7.1:1, золото 4.5:1), итоги (9.4:1), подсказки на фоне листа (6.4:1), пунктир полей.
     * Заголовок рамки ввода — графит на золоте (8.4:1); статусы — 8.3:1 и 6.6:1.
     */
    static final SheetPalette LIGHT_GRAY = new SheetPalette(
            0xF2F2F4, INK, 0x62626C,
            0xFFFFFF, 0xF7F7F9, 0xFAFAFB, 0xEDEDF1, 0xD8D8DF,
            CanonPalette.VIOLET, CanonPalette.WHITE, GOLD,
            GRAPHITE, CanonPalette.WHITE, GOLD,
            0x4C1D95, CanonPalette.VIOLET,
            GOLD_DEEP, INK, GRAPHITE, GOLD, CanonPalette.WHITE, CanonPalette.GRID,
            0xDCFCE7, 0x14532D, 0xFFF1D6, 0x7C4A03,
            INK, CanonPalette.VIOLET,
            CanonPalette.GRID, CanonPalette.GREEN_RIGHT, INK);

    /** Палитра таблиц из настроек: Excel, Google Таблицы, таблица команды. */
    static SheetPalette forTables()
    {
        return of(SchematicsConfig.sheetsPalette());
    }

    public static SheetPalette of(SchematicsConfig.SheetsPalette choice)
    {
        return switch (choice)
        {
            case LIGHT_GRAY -> LIGHT_GRAY;
            case DARK_VIOLET -> DARK_VIOLET;
        };
    }

    /** Цвет ника по месту в ростере — те же 16 тонов в обеих палитрах. */
    public int nick(int index)
    {
        return CanonPalette.nick(index);
    }

    /**
     * Цвета для скрипта таблицы команды (поле {@code palette} запроса) — его роли {@code C}:
     * скрипт накладывает их поверх своих по умолчанию, а скрипт без поддержки поля его просто
     * не читает.
     */
    JsonObject forScript()
    {
        JsonObject out = new JsonObject();
        out.addProperty("violet", CanonPalette.hex(this.titleFill));
        out.addProperty("white", CanonPalette.hex(this.headText));
        out.addProperty("deep", CanonPalette.hex(this.headFill));
        out.addProperty("sub", CanonPalette.hex(this.total));
        out.addProperty("frame", CanonPalette.hex(this.total));
        out.addProperty("night", CanonPalette.hex(this.sumText));
        out.addProperty("grid", CanonPalette.hex(this.hintText));
        out.addProperty("border", CanonPalette.hex(this.border));
        out.addProperty("done", CanonPalette.hex(this.doneFill));
        out.addProperty("taking", CanonPalette.hex(this.takenFill));
        out.addProperty("barTotal", CanonPalette.hex(this.chartTotal));
        out.addProperty("barDone", CanonPalette.hex(this.chartDone));
        out.addProperty("canvas", CanonPalette.hex(this.canvas));
        out.addProperty("row", CanonPalette.hex(this.row));
        out.addProperty("rowAlt", CanonPalette.hex(this.rowAlt));
        out.addProperty("panel", CanonPalette.hex(this.panel));
        out.addProperty("text", CanonPalette.hex(this.text));
        out.addProperty("chip", CanonPalette.hex(this.chipText));
        return out;
    }
}
