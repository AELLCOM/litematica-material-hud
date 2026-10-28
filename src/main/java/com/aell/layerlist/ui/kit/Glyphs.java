package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Пиксельные значки набора. Маски, а не символы шрифта: глифы вроде «▾» или «⟳» в игровом шрифте
 * сидят не по центру строки и есть не во всех наборах шрифтов (как и лупа {@code Paint.SEARCH_ICON}).
 * {@code '#'} — пиксель цветом значка; у куба грани помечены буквами T/L/R.
 */
@Environment(EnvType.CLIENT)
public final class Glyphs
{
    private Glyphs() {}

    /** Папка 9×7. */
    public static final String[] FOLDER = {
            "###      ",
            "#########",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            "#########"};

    /** Стрелка «вверх по папке» 7×7. */
    public static final String[] UP = {
            "   #   ",
            "  ###  ",
            " # # # ",
            "#  #  #",
            "   #   ",
            "   #   ",
            "   #   "};

    /** Обновить: дуга со стрелкой 7×7. */
    public static final String[] REFRESH = {
            "  ### #",
            " #   ##",
            "#   ###",
            "#      ",
            "#     #",
            " #   # ",
            "  ###  "};

    /** Шестерёнка 7×7. */
    public static final String[] GEAR = {
            "   #   ",
            " # # # ",
            "  ###  ",
            "### ###",
            "  ###  ",
            " # # # ",
            "   #   "};

    /** Открыть папку в системе: папка со стрелкой наружу 9×7. */
    public static final String[] OPEN_FOLDER = {
            "###   ###",
            "#####  ##",
            "#    # # ",
            "#     #  ",
            "#       #",
            "#       #",
            "#########"};

    /** Корзина 7×8: ручка, крышка, ведро с рёбрами (строка «Корзина» в списке схем, её окно). */
    public static final String[] TRASH = {
            "  ###  ",
            "#######",
            " #   # ",
            " # # # ",
            " # # # ",
            " # # # ",
            " #   # ",
            "  ###  "};

    /** Раскрыть список 7×4. */
    public static final String[] CHEVRON_DOWN = {
            "##   ##",
            " ## ## ",
            "  ###  ",
            "   #   "};

    /** Сортировка по возрастанию 5×3. */
    public static final String[] SORT_ASC = {
            "  #  ",
            " ### ",
            "#####"};

    /** Сортировка по убыванию 5×3. */
    public static final String[] SORT_DESC = {
            "#####",
            " ### ",
            "  #  "};

    /** Крестик 7×7: удалить, закрыть. */
    public static final String[] CROSS = {
            "#     #",
            " #   # ",
            "  # #  ",
            "   #   ",
            "  # #  ",
            " #   # ",
            "#     #"};

    /** Плюс 7×7: добавить (чипы выбора игроков). Того же размера, что {@link #CROSS}, — чипы не прыгают. */
    public static final String[] PLUS = {
            "   #   ",
            "   #   ",
            "   #   ",
            "#######",
            "   #   ",
            "   #   ",
            "   #   "};

    /** Минус 7×7 — пара к {@link #PLUS}: шаг координаты вниз (вкладка «Выделение»). */
    public static final String[] MINUS = {
            "       ",
            "       ",
            "       ",
            "#######",
            "       ",
            "       ",
            "       "};

    /** Человечек 7×7: «К игроку» (вкладка «Выделение»). */
    public static final String[] PLAYER = {
            "  ###  ",
            "  ###  ",
            "   #   ",
            " ##### ",
            "   #   ",
            "  # #  ",
            " #   # "};

    /** Прицел 7×7: «Двигать в мире» — этот угол двигают клавиши и инструмент Litematica. */
    public static final String[] TARGET = {
            "   #   ",
            " ##### ",
            " #   # ",
            "### ###",
            " #   # ",
            " ##### ",
            "   #   "};

    /** Стрелки вверх-вниз 7×9: шаг координаты (ЛКМ — плюс, ПКМ — минус), как «+/−» у полей Litematica. */
    public static final String[] NUDGE = {
            "   #   ",
            "  ###  ",
            " ##### ",
            "#######",
            "       ",
            "#######",
            " ##### ",
            "  ###  ",
            "   #   "};

    /** Плюс 5×5 — верхняя половина кнопки шага координаты ({@code AxisNudge}). */
    public static final String[] PLUS_SMALL = {
            "  #  ",
            "  #  ",
            "#####",
            "  #  ",
            "  #  "};

    /** Минус 5×5 — нижняя половина кнопки шага координаты. */
    public static final String[] MINUS_SMALL = {
            "     ",
            "     ",
            "#####",
            "     ",
            "     "};

    /** Глаз 9×7: «Смотреть на мир» — всё, кроме координат, уезжает, экран светлеет (вкладка «Выделение»). */
    public static final String[] EYE = {
            "   ###   ",
            " ##   ## ",
            "#  ###  #",
            "# ##### #",
            "#  ###  #",
            " ##   ## ",
            "   ###   "};

    /** Замок закрытый 7×7: размещение закреплено, схему не сдвинуть (редактор размещения, строка таблицы). */
    public static final String[] LOCK = {
            "  ###  ",
            " #   # ",
            " #   # ",
            "#######",
            "### ###",
            "### ###",
            "#######"};

    /** Замок открытый 7×8: дужка поднята и открыта с правой стороны — размещение можно двигать. */
    public static final String[] UNLOCK = {
            "  ###  ",
            " #   # ",
            " #     ",
            "       ",
            "#######",
            "### ###",
            "### ###",
            "#######"};

    /** Поворот против часовой 7×7: «↺» в редакторе размещения. */
    public static final String[] ROTATE_CCW = {
            "# ###  ",
            "##   # ",
            "###   #",
            "      #",
            "#     #",
            " #   # ",
            "  ###  "};

    /** Поворот по часовой 7×7: «↻» в редакторе размещения. */
    public static final String[] ROTATE_CW = {
            "  ### #",
            " #   ##",
            "#   ###",
            "#      ",
            "#     #",
            " #   # ",
            "  ###  "};

    /** Отражение 7×7: ромб с прорезью посередине. */
    public static final String[] MIRROR = {
            "   #   ",
            "  # #  ",
            " ##### ",
            "### ###",
            " ##### ",
            "  # #  ",
            "   #   "};

    /** Галочка 7×5 — во флажке. */
    public static final String[] CHECK = {
            "      #",
            "     ##",
            "#   ## ",
            "## ##  ",
            " ###   "};

    /** Изометрический кубик 12×11 — нейтральная заглушка миниатюры. */
    public static final String[] CUBE = {
            "    TTTT    ",
            "  TTTTTTTT  ",
            "TTTTTTTTTTTT",
            "LLTTTTTTTTRR",
            "LLLLTTTTRRRR",
            "LLLLLLRRRRRR",
            "LLLLLLRRRRRR",
            "LLLLLLRRRRRR",
            "LLLLLLRRRRRR",
            "  LLLLRRRR  ",
            "    LLRR    "};

    public static int width(String[] mask)
    {
        return mask.length == 0 ? 0 : mask[0].length();
    }

    public static int height(String[] mask)
    {
        return mask.length;
    }

    public static void draw(GuiContext ctx, String[] mask, int x, int y, int color)
    {
        Paint.icon(ctx, mask, x, y, color);
    }

    /** Значок по центру прямоугольника. */
    public static void center(GuiContext ctx, String[] mask, int x, int y, int w, int h, int color)
    {
        draw(ctx, mask, x + (w - width(mask)) / 2, y + (h - height(mask)) / 2, color);
    }

    /**
     * Кубик с гранями по свету канона: верх 1.0, левая 0.8, правая 0.6 от цвета {@code color}
     * (освещение слева, как у иконок семейства).
     */
    public static void cube(GuiContext ctx, int x, int y, int color)
    {
        int top = color;
        int left = Ui.darken(color, 0.2F);
        int right = Ui.darken(color, 0.4F);

        for (int row = 0; row < CUBE.length; ++row)
        {
            String line = CUBE[row];

            for (int column = 0; column < line.length(); ++column)
            {
                char c = line.charAt(column);
                int tone = c == 'T' ? top : c == 'L' ? left : c == 'R' ? right : 0;

                if (tone != 0)
                {
                    Paint.rect(ctx, x + column, y + row, 1, 1, tone);
                }
            }
        }
    }
}
