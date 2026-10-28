package com.aell.ui.kit;

import com.aell.ui.Anim;
import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Таблица: строка заголовков (клик — сортировка, повторный клик — обратный порядок) над
 * {@link ScrollList}. Колонки — фиксированной ширины или «резиновые» (делят остаток по весу);
 * колонка с {@link Column#hideBelow} исчезает, когда таблица уже порога — так узкое окно теряет
 * второстепенные колонки, а не обрезает все поровну. Числа — по правому краю.
 *
 * <p>Ячейка — текст ({@link Column#text}) с обрезкой «…» и подсказкой полного текста, по желанию
 * с иконкой предмета ({@link Column#icon}, настоящий {@code renderItem}), или своя отрисовка
 * ({@link Column#cell}).
 */
@Environment(EnvType.CLIENT)
public final class Table<T> extends Widget
{
    public enum Align
    {
        LEFT,
        RIGHT,
        CENTER
    }

    /** Своя отрисовка ячейки. */
    @FunctionalInterface
    public interface Cell<T>
    {
        void render(GuiContext ctx, T item, int x, int y, int w, int h, boolean hovered, int mouseX, int mouseY);
    }

    /** Клик по ячейке; {@code true} — съеден (строка не выбирается). */
    @FunctionalInterface
    public interface CellClick<T>
    {
        boolean click(T item, MouseButtonEvent click);
    }

    public static final class Column<T>
    {
        private final String title;
        private int width;
        private int weight;
        private int minWidth = Ui.FIELD_W;
        private int hideBelow;
        private Align align = Align.LEFT;
        private @Nullable Function<T, String> text;
        private @Nullable ToIntFunction<T> color;
        private @Nullable Function<T, ItemStack> icon;
        private @Nullable Function<T, String> tooltip;
        private @Nullable Comparator<T> comparator;
        private @Nullable Cell<T> cell;
        private @Nullable CellClick<T> onClick;
        private @Nullable String headerTooltip;
        private boolean descendingFirst;

        private int layoutX;
        private int layoutW;
        private boolean shown;

        private Column(String title)
        {
            this.title = title;
        }

        /** Колонка фиксированной ширины в пикселях. */
        public static <T> Column<T> fixed(String title, int width)
        {
            Column<T> column = new Column<>(title);
            column.width = width;
            return column;
        }

        /** Резиновая колонка: делит остаток ширины с другими по весу. */
        public static <T> Column<T> flex(String title, int weight)
        {
            Column<T> column = new Column<>(title);
            column.weight = Math.max(1, weight);
            return column;
        }

        public Column<T> minWidth(int value)
        {
            this.minWidth = value;
            return this;
        }

        /** Прятать колонку, когда ширина таблицы меньше {@code tableWidth}. */
        public Column<T> hideBelow(int tableWidth)
        {
            this.hideBelow = tableWidth;
            return this;
        }

        public Column<T> align(Align value)
        {
            this.align = value;
            return this;
        }

        public Column<T> text(Function<T, String> value)
        {
            this.text = value;
            return this;
        }

        /** Цвет текста ячейки — цвета смысла передаёт владелец. */
        public Column<T> color(ToIntFunction<T> value)
        {
            this.color = value;
            return this;
        }

        /** Иконка предмета 16×16 в начале ячейки. */
        public Column<T> icon(Function<T, ItemStack> value)
        {
            this.icon = value;
            return this;
        }

        public Column<T> tooltip(Function<T, String> value)
        {
            this.tooltip = value;
            return this;
        }

        public Column<T> headerTooltip(String value)
        {
            this.headerTooltip = value;
            return this;
        }

        public Column<T> sort(Comparator<T> value)
        {
            this.comparator = value;
            return this;
        }

        /**
         * Первый клик по заголовку сортирует по убыванию (числа: «больше всего — сверху»), повторный —
         * по возрастанию. По умолчанию первый клик — по возрастанию.
         */
        public Column<T> descendingFirst()
        {
            this.descendingFirst = true;
            return this;
        }

        public Column<T> cell(Cell<T> value)
        {
            this.cell = value;
            return this;
        }

        public Column<T> onClick(CellClick<T> value)
        {
            this.onClick = value;
            return this;
        }

        public String title()
        {
            return this.title;
        }
    }

    /** Сортировка сменилась (чтобы владелец запомнил её). */
    @FunctionalInterface
    public interface SortListener
    {
        void sorted(int column, boolean descending);
    }

    private static final int HEADER_H = Ui.HEAD_BTN_H + Ui.PAD_MICRO;
    private static final int ICON = 16;

    private final List<Column<T>> columns = new ArrayList<>();
    private final ScrollList<T> body;
    private final Anim.Pool hoverAnim = new Anim.Pool(Ui.ANIM_HOVER_MS);
    private final List<T> source = new ArrayList<>();

    private int sortColumn = -1;
    private boolean descending;
    private @Nullable SortListener sortListener;
    private ScrollList.Listener<T> outer = new ScrollList.Listener<>() {};

    public Table(int rowHeight)
    {
        this.body = new ScrollList<T>(this::renderRow).rowHeight(item -> rowHeight);
        this.body.listener(new ScrollList.Listener<>()
        {
            @Override
            public void select(@Nullable T item, int index)
            {
                Table.this.outer.select(item, index);
            }

            @Override
            public void activate(T item, int index)
            {
                Table.this.outer.activate(item, index);
            }

            @Override
            public void context(T item, int index, int mouseX, int mouseY)
            {
                Table.this.outer.context(item, index, mouseX, mouseY);
            }

            @Override
            public boolean click(T item, int index, int rowX, int rowY, int rowW, int rowH, MouseButtonEvent click)
            {
                return Table.this.clickCell(item, click) || Table.this.outer.click(item, index, rowX, rowY, rowW, rowH, click);
            }
        });
    }

    @SafeVarargs
    public final Table<T> columns(Column<T>... list)
    {
        this.columns.clear();
        this.columns.addAll(List.of(list));
        return this;
    }

    public Table<T> listener(ScrollList.Listener<T> listener)
    {
        this.outer = listener;
        return this;
    }

    public Table<T> onSort(SortListener listener)
    {
        this.sortListener = listener;
        return this;
    }

    public void setEmptyText(String text)
    {
        this.body.setEmptyText(text);
    }

    public ScrollList<T> body()
    {
        return this.body;
    }

    public @Nullable T selectedItem()
    {
        return this.body.selectedItem();
    }

    public void setSelected(@Nullable T item)
    {
        this.body.setSelected(item);
    }

    public int sortColumn()
    {
        return this.sortColumn;
    }

    public boolean descending()
    {
        return this.descending;
    }

    /** Без уведомления: восстановить запомненную сортировку. */
    public void setSort(int column, boolean descending)
    {
        this.sortColumn = column;
        this.descending = descending;
        this.applySort();
    }

    public void setItems(List<T> items)
    {
        this.source.clear();
        this.source.addAll(items);
        this.applySort();
    }

    private void applySort()
    {
        List<T> rows = new ArrayList<>(this.source);

        if (this.sortColumn >= 0 && this.sortColumn < this.columns.size())
        {
            Comparator<T> comparator = this.columns.get(this.sortColumn).comparator;

            if (comparator != null)
            {
                rows.sort(this.descending ? comparator.reversed() : comparator);
            }
        }

        this.body.setItems(rows);
    }

    public void setFocused(boolean value)
    {
        this.body.setFocused(value);
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);
        this.body.setBounds(x, y + HEADER_H, w, Math.max(0, h - HEADER_H));
    }

    private void layoutColumns(int width)
    {
        int fixed = 0;
        int weights = 0;

        for (Column<T> column : this.columns)
        {
            column.shown = column.hideBelow <= 0 || this.w >= column.hideBelow;

            if (! column.shown)
            {
                continue;
            }

            if (column.weight > 0)
            {
                weights += column.weight;
            }
            else
            {
                fixed += column.width;
            }
        }

        int rest = Math.max(0, width - fixed);
        int cx = this.x;
        int flexLeft = rest;
        int weightLeft = weights;

        for (Column<T> column : this.columns)
        {
            if (! column.shown)
            {
                continue;
            }

            int cw;

            if (column.weight > 0)
            {
                cw = weightLeft == 0 ? 0 : flexLeft * column.weight / weightLeft;
                cw = Math.max(cw, Math.min(column.minWidth, flexLeft));
                flexLeft -= cw;
                weightLeft -= column.weight;
            }
            else
            {
                cw = column.width;
            }

            column.layoutX = cx;
            column.layoutW = Math.max(0, Math.min(cw, this.x + width - cx));
            cx += cw;
        }
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0)
        {
            return;
        }

        Font font = Kit.font();
        this.layoutColumns(this.body.rowWidth());

        if (Style.vanilla())
        {
            // Шапка таблицы без заливки: разделитель Minecraft под ней
            Vanilla.headerSeparator(ctx, this.x, this.y + HEADER_H - 2, this.w);
        }
        else
        {
            Paint.rect(ctx, this.x, this.y + HEADER_H - Ui.BORDER, this.w, Ui.BORDER, Ui.C_DIVIDER);
        }

        for (int i = 0; i < this.columns.size(); ++i)
        {
            Column<T> column = this.columns.get(i);

            if (! column.shown || column.layoutW <= 0)
            {
                continue;
            }

            boolean sortable = column.comparator != null;
            boolean over = sortable && Paint.hit(mouseX, mouseY, column.layoutX, this.y, column.layoutW, HEADER_H);
            float hover = this.hoverAnim.update("head#" + i, over);

            if (hover > 0.0F)
            {
                Paint.round(ctx, column.layoutX, this.y, column.layoutW, HEADER_H - Ui.BORDER, Ui.R_ROW,
                        Ui.alpha(Ui.C_ROW_HOVER, hover));
            }

            boolean active = i == this.sortColumn;
            String[] arrow = active ? (this.descending ? Glyphs.SORT_DESC : Glyphs.SORT_ASC) : null;
            int arrowW = arrow == null ? 0 : Glyphs.width(arrow) + Ui.PAD_MICRO;
            int room = column.layoutW - Ui.PAD_IN * 2 - arrowW;
            String title = Paint.fit(font, column.title, room);
            int textW = font.width(title);
            int tx = switch (column.align)
            {
                case RIGHT -> column.layoutX + column.layoutW - Ui.PAD_IN - textW - arrowW;
                case CENTER -> column.layoutX + (column.layoutW - textW - arrowW) / 2;
                default -> column.layoutX + Ui.PAD_IN;
            };
            int color = Style.vanilla() ? Style.V_WHITE : active ? Ui.C_TEXT : Ui.C_TEXT_3;

            Paint.midText(ctx, font, title, tx, this.y, HEADER_H, color, Style.vanilla());

            if (arrow != null)
            {
                Glyphs.draw(ctx, arrow, tx + textW + Ui.PAD_MICRO, this.y + (HEADER_H - Glyphs.height(arrow)) / 2,
                        color);
            }

            if (Paint.hit(mouseX, mouseY, column.layoutX, this.y, column.layoutW, HEADER_H))
            {
                Tooltip.offer("head#" + i, column.headerTooltip != null ? column.headerTooltip
                        : ! title.equals(column.title) ? column.title : null);
            }
        }

        this.body.render(ctx, mouseX, mouseY);
    }

    private void renderRow(GuiContext ctx, T item, int index, int x, int y, int w, int h, boolean selected,
            boolean hovered, int mouseX, int mouseY)
    {
        Font font = Kit.font();

        for (Column<T> column : this.columns)
        {
            if (! column.shown || column.layoutW <= 0)
            {
                continue;
            }

            int cx = column.layoutX;
            int cw = column.layoutW;
            boolean overCell = Paint.hit(mouseX, mouseY, cx, y, cw, h);

            if (column.cell != null)
            {
                column.cell.render(ctx, item, cx, y, cw, h, overCell, mouseX, mouseY);
                continue;
            }

            int textX = cx + Ui.PAD_IN;
            int room = cw - Ui.PAD_IN * 2;

            if (column.icon != null)
            {
                ItemStack stack = column.icon.apply(item);

                if (stack != null && ! stack.isEmpty())
                {
                    ctx.renderItem(stack, textX, y + (h - ICON) / 2);
                }

                textX += ICON + Ui.PAD_IN;
                room -= ICON + Ui.PAD_IN;
            }

            if (column.text == null)
            {
                continue;
            }

            String full = column.text.apply(item);
            String shown = Paint.fit(font, full, room);
            int color = column.color != null ? column.color.applyAsInt(item) : selected ? Ui.C_TEXT : Ui.C_TEXT_2;
            int width = font.width(shown);
            int tx = switch (column.align)
            {
                case RIGHT -> cx + cw - Ui.PAD_IN - width;
                case CENTER -> cx + (cw - width) / 2;
                default -> textX;
            };

            Paint.midText(ctx, font, shown, tx, y, h, color, false);

            if (overCell)
            {
                String tip = column.tooltip != null ? column.tooltip.apply(item) : ! shown.equals(full) ? full : null;
                Tooltip.offer(List.of(column.title, index), tip);
            }
        }
    }

    private boolean clickCell(T item, MouseButtonEvent click)
    {
        for (Column<T> column : this.columns)
        {
            if (column.shown && column.onClick != null
                    && click.x() >= column.layoutX && click.x() < column.layoutX + column.layoutW)
            {
                return column.onClick.click(item, click);
            }
        }

        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()))
        {
            return false;
        }

        if (click.y() < this.y + HEADER_H)
        {
            if (Kit.left(click))
            {
                this.layoutColumns(this.body.rowWidth());

                for (int i = 0; i < this.columns.size(); ++i)
                {
                    Column<T> column = this.columns.get(i);

                    if (column.shown && column.comparator != null
                            && click.x() >= column.layoutX && click.x() < column.layoutX + column.layoutW)
                    {
                        this.descending = i == this.sortColumn ? ! this.descending : column.descendingFirst;
                        this.sortColumn = i;
                        this.applySort();
                        Vanilla.click();

                        if (this.sortListener != null)
                        {
                            this.sortListener.sorted(i, this.descending);
                        }

                        break;
                    }
                }
            }

            return true;
        }

        this.layoutColumns(this.body.rowWidth());
        return this.body.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.body.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        return this.body.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.body.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        return this.body.keyPressed(key);
    }
}
