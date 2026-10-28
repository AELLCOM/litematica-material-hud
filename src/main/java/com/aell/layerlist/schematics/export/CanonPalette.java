package com.aell.layerlist.schematics.export;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Цвета выгрузок вне игры (xlsx, Google, PNG) — канон Aell_ v6 ({@code aell-brand-canon},
 * CANON.md §4 фон, §8 тройки, §34 типографика, {@code reference/hue-table.txt}). Игровые экраны
 * этими цветами не пользуются: там токены {@code Ui}/{@code Theme}. Все литералы цвета выгрузок
 * живут только здесь, значения — 0xRRGGBB.
 */
@Environment(EnvType.CLIENT)
final class CanonPalette
{
    /** Фон знака §4: начало и конец градиента под 45°. */
    static final int VIOLET = 0x6D28D9;
    static final int VIOLET_DEEP = 0x3F1178;
    /** Контур полосы §38 — самый тёмный фиолетовый канона: тёмный текст на светлом и тело карточки. */
    static final int VIOLET_NIGHT = 0x2A0B52;
    /** Середина между {@link #VIOLET_NIGHT} и {@link #VIOLET_DEEP}: чередование строк карточки. */
    static final int VIOLET_ZEBRA = 0x350E65;

    static final int WHITE = 0xFFFFFF;
    /** Типографика баннера §34: подзаголовок, основной текст, бейдж, надзаголовок. */
    static final int SUBTITLE = 0xE1C8FF;
    static final int TEXT_ON_DARK = 0xD4ADFF;
    static final int BADGE = 0xEFD8FF;
    static final int OVERLINE = 0xC292FF;

    /** Служебные §8: каркас и сетка. */
    static final int FRAME = 0xE2E8FF;
    static final int GRID = 0x8B5BF7;
    /** Акцентная линия сетки §8/§32 (каждая 4-я): сетка сцены постера-превью. */
    static final int GRID_ACCENT = 0x5384E0;

    /** Тройки §8, верхние грани: смысл «хватает / частично / нет», как у HUD. */
    static final int GREEN_TOP = 0x4ADE80;
    static final int AMBER_TOP = 0xFFC36B;
    static final int RED_TOP = 0xFF8A80;
    /** Правая грань зелёного §8 — ряд «Собрано» диаграммы. */
    static final int GREEN_RIGHT = 0x16A34A;

    /*
     * Тёмная тема листа xlsx/Google (docs/integration/M26/theme.md). Поверхности лежат на той же
     * прямой OKLCh, что и фон знака: тон ≈ 296°, насыщенность ≈ 0.47·L; ступени по светлоте:
     * фон листа 0.21 → строка 0.25 → чередование 0.29 → шапка 0.32 → заголовок 0.49.
     */
    /** Фон всего листа за таблицей и промежутков между блоками (L 0.21). */
    static final int SHEET_CANVAS = 0x1F063F;
    /** Нечётные строки списка — тело PNG-карточки. */
    static final int SHEET_ROW = VIOLET_NIGHT;
    /** Чётные строки списка (условным форматом, чтобы сортировка не путала полосы). */
    static final int SHEET_ROW_ALT = VIOLET_ZEBRA;
    /** Тело боковых блоков: та же светлота, тон сдвинут к индиго на 6° — «другая панель». */
    static final int SHEET_PANEL = 0x2A125C;
    /** Строки итогов боковых блоков (L 0.305, между чередованием и шапкой). */
    static final int SHEET_TOTAL = 0x33176D;
    /** Тонкие рамки ячеек вместо серой сетки: {@link #GRID} 35 % на {@link #VIOLET_NIGHT}. */
    static final int SHEET_BORDER = 0x4C278C;
    /** Основной текст (типографика §34, бейдж) и вторичный (основной текст баннера). */
    static final int SHEET_TEXT = BADGE;
    static final int SHEET_TEXT_MUTED = TEXT_ON_DARK;
    /** Тёмный текст на ярких плашках ников. */
    static final int SHEET_CHIP_TEXT = VIOLET_NIGHT;

    /*
     * Рамка «Кто собирает — впишите ники» (колонка J): заголовок рамки — яркий VIOLET, поля
     * ввода светлее панели (L 0.36, тон 285° — ещё на 6° к индиго: «не панель и не шапка»), в
     * пунктире OVERLINE — пунктир виден и на поле (4.8:1), и на панели рядом (6.6:1).
     */
    /** Заголовок рамки ввода ников — белым на цвете полосы заголовка. */
    static final int SHEET_INPUT_HEAD = VIOLET;
    /** Поле ввода ника (пустой слот); вписанный ник красится своим тоном условным форматом. */
    static final int SHEET_INPUT = 0x3A2984;
    /** Пунктир полей ввода и рамка заголовка рамки. */
    static final int SHEET_INPUT_EDGE = OVERLINE;

    /**
     * Строки «Собрано» / «Собираю»: тёмные варианты канонических тонов (зелёный 150°, янтарный
     * 72°, L 0.36) со светлым текстом — прежние светлые заливки {@code #DCFCE7} / {@code #FFF1D6}
     * стали цветом текста (контраст 9.5:1 и 9.9:1).
     */
    static final int DONE_FILL = 0x134924;
    static final int DONE_TEXT = 0xDCFCE7;
    static final int TAKEN_FILL = 0x53360A;
    static final int TAKEN_TEXT = 0xFFF1D6;

    /**
     * Ряды диаграммы: «Всего блоков» — сетка {@link #GRID}, «Собрано» — {@link #GREEN_RIGHT}. Пара
     * проходит проверку dataviz и на тёмной панели, и на белом (на случай, если Google не перенесёт
     * фон диаграммы): светлота в полосе, ΔE 37 (дейтан 28), контраст ≥ 3:1.
     */
    static final int CHART_TOTAL = GRID;
    static final int CHART_DONE = GREEN_RIGHT;

    /**
     * Цвета ников — 16 тонов {@code hue-table.txt}, колонка «верх» (самая светлая ступень тона,
     * L 0.82). Порядок — шаг 5 по таблице (0, 5, 10, 15, 4, 9…): соседние ники в ростере
     * расходятся по тону не меньше чем на 100°, а не идут оттенками одного цвета.
     */
    private static final int[] NICK_TONES = {
            0xF9AAC1, // 0° алый
            0xA0DA3E, // 128° травяной
            0x8ECCF8, // 240° лазурный
            0xF9A6D5, // 345° малиновый
            0xDBC63D, // 100° лаймовый
            0x5ED6F8, // 220° голубой
            0xE9A8F8, // 320° пурпурный
            0xF9B55B, // 72° янтарный
            0x46DDDD, // 195° бирюзовый
            0xCDB7F8, // 300° фиолетовый
            0xF9B18A, // 50° оранжевый
            0x46E1BF, // 175° изумруд
            0xBEBDF8, // 285° индиго
            0xF9AEA6, // 26° красный
            0x46E77A, // 150° зелёный
            0xA7C5F8, // 261° синий
    };

    private CanonPalette() {}

    static int nickTones()
    {
        return NICK_TONES.length;
    }

    /** Цвет ника по месту в ростере; после 16-го тона повторяются. */
    static int nick(int index)
    {
        return NICK_TONES[Math.floorMod(index, NICK_TONES.length)];
    }

    static String hex(int rgb)
    {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
