package com.aell.ui.kit;

import com.aell.ui.Anim;
import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Прокручиваемый список строк — как тело экрана настроек: scissor по области, скроллбар
 * {@link Ui#SCROLL_W} в правом поле (строки при этом сужаются, а не перекрываются), бегунок не
 * меньше {@link Ui#SCROLL_THUMB_MIN}, колесо — {@link Ui#SCROLL_WHEEL_ROWS} строк.
 *
 * <p>Рисуются только видимые строки (поиск первой — двоичный по смещениям), поэтому тысячи
 * файлов в папке не тормозят. Высота строки — своя у каждого элемента ({@link #rowHeight}).
 * Выбранная строка — синяя ({@link Theme#BLUE_FILL}/{@link Theme#BLUE_EDGE}: «активная строка»),
 * наведение — {@link Ui#C_ROW_HOVER}. Клавиатура (когда список в фокусе): ↑/↓, PgUp/PgDn,
 * Home/End, Enter — {@link Listener#activate}.
 */
@Environment(EnvType.CLIENT)
public class ScrollList<T> extends Widget
{
    /** Отрисовка содержимого строки (фон, выбор и наведение уже нарисованы списком). */
    @FunctionalInterface
    public interface Renderer<T>
    {
        void render(GuiContext ctx, T item, int index, int x, int y, int w, int h, boolean selected,
                    boolean hovered, int mouseX, int mouseY);
    }

    /** Реакции на строки; всё необязательно. */
    public interface Listener<T>
    {
        /** Выбор сменился (клик, клавиатура). */
        default void select(@Nullable T item, int index)
        {
        }

        /** Двойной клик или Enter. */
        default void activate(T item, int index)
        {
        }

        /** ПКМ по строке (строка перед этим уже выбрана). */
        default void context(T item, int index, int mouseX, int mouseY)
        {
        }

        /**
         * Клик по строке до выбора — для своих зон внутри строки (флажок, кнопка); {@code true} —
         * клик съеден, выбор не меняется.
         */
        default boolean click(T item, int index, int rowX, int rowY, int rowW, int rowH, MouseButtonEvent click)
        {
            return false;
        }
    }

    private final Anim.Pool hoverAnim = new Anim.Pool(Ui.ANIM_HOVER_MS);
    private final List<T> items = new ArrayList<>();
    private int[] offsets = new int[1];

    private Renderer<T> renderer;
    private Listener<T> listener = new Listener<>() {};
    private ToIntFunction<T> rowHeight = item -> Ui.ROW_H;
    private int gap;
    private String emptyText = "";
    private boolean showSelection = true;

    private int scroll;
    private int selected = -1;
    private boolean focused;
    private boolean dragThumb;
    private int dragGrab;

    public ScrollList(Renderer<T> renderer)
    {
        this.renderer = renderer;
    }

    public ScrollList<T> renderer(Renderer<T> renderer)
    {
        this.renderer = renderer;
        return this;
    }

    public ScrollList<T> listener(Listener<T> listener)
    {
        this.listener = listener;
        return this;
    }

    public ScrollList<T> rowHeight(ToIntFunction<T> height)
    {
        this.rowHeight = height;
        this.relayout();
        return this;
    }

    public ScrollList<T> gap(int gap)
    {
        this.gap = Math.max(0, gap);
        this.relayout();
        return this;
    }

    public ScrollList<T> showSelection(boolean value)
    {
        this.showSelection = value;
        return this;
    }

    /** Текст по центру пустого списка («Папка пуста», «Ничего не найдено»). */
    public void setEmptyText(String text)
    {
        this.emptyText = text == null ? "" : text;
    }

    public List<T> items()
    {
        return this.items;
    }

    /**
     * Новые строки. Выбранный элемент сохраняется, если он есть и в новом списке (по
     * {@code equals}); прокрутка зажимается.
     */
    public void setItems(List<T> next)
    {
        T previous = this.selectedItem();
        this.items.clear();
        this.items.addAll(next);
        this.relayout();
        this.selected = previous == null ? -1 : this.items.indexOf(previous);
        this.clampScroll();
    }

    private void relayout()
    {
        this.offsets = new int[this.items.size() + 1];

        for (int i = 0; i < this.items.size(); ++i)
        {
            this.offsets[i + 1] = this.offsets[i] + this.rowHeight.applyAsInt(this.items.get(i)) + this.gap;
        }
    }

    public int contentHeight()
    {
        return Math.max(0, this.offsets[this.items.size()] - (this.items.isEmpty() ? 0 : this.gap));
    }

    public boolean focused()
    {
        return this.focused;
    }

    public void setFocused(boolean value)
    {
        this.focused = value;
    }

    public int selectedIndex()
    {
        return this.selected;
    }

    public @Nullable T selectedItem()
    {
        return this.selected >= 0 && this.selected < this.items.size() ? this.items.get(this.selected) : null;
    }

    /** Выбрать без уведомления слушателя (внешняя синхронизация). */
    public void setSelectedIndex(int index)
    {
        this.selected = index >= 0 && index < this.items.size() ? index : -1;

        if (this.selected >= 0)
        {
            this.ensureVisible(this.selected);
        }
    }

    public void setSelected(@Nullable T item)
    {
        this.setSelectedIndex(item == null ? -1 : this.items.indexOf(item));
    }

    public int scroll()
    {
        return this.scroll;
    }

    public void setScroll(int value)
    {
        this.scroll = value;
        this.clampScroll();
    }

    private boolean scrollable()
    {
        return this.contentHeight() > this.h;
    }

    private int maxScroll()
    {
        return Math.max(0, this.contentHeight() - this.h);
    }

    private void clampScroll()
    {
        this.scroll = Ui.clamp(this.scroll, 0, this.maxScroll());
    }

    /** Ширина строки: при скроллбаре строки сужаются, чтобы он не лёг на текст. */
    public int rowWidth()
    {
        return this.scrollable() ? this.w - Ui.SCROLL_W - Ui.SCROLL_GAP : this.w;
    }

    public void ensureVisible(int index)
    {
        if (index < 0 || index >= this.items.size())
        {
            return;
        }

        int top = this.offsets[index];
        int bottom = top + this.rowHeight.applyAsInt(this.items.get(index));

        if (top < this.scroll)
        {
            this.scroll = top;
        }
        else if (bottom > this.scroll + this.h)
        {
            this.scroll = bottom - this.h;
        }

        this.clampScroll();
    }

    /** Номер строки под точкой; -1 — мимо строк. */
    public int indexAt(double mouseX, double mouseY)
    {
        if (! this.contains(mouseX, mouseY) || mouseX >= this.x + this.rowWidth())
        {
            return -1;
        }

        int local = (int) Math.floor(mouseY) - this.y + this.scroll;
        int index = this.firstAtOrAfter(local);

        if (index < 0 || index >= this.items.size())
        {
            return -1;
        }

        int top = this.offsets[index];
        int height = this.rowHeight.applyAsInt(this.items.get(index));

        return local >= top && local < top + height ? index : -1;
    }

    /** Строка, внутри или ниже которой лежит смещение {@code local} (двоичный поиск). */
    private int firstAtOrAfter(int local)
    {
        int lo = 0;
        int hi = this.items.size() - 1;
        int found = this.items.size();

        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;

            if (this.offsets[mid + 1] > local)
            {
                found = mid;
                hi = mid - 1;
            }
            else
            {
                lo = mid + 1;
            }
        }

        return found;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0 || this.h <= 0)
        {
            return;
        }

        this.clampScroll();

        Font font = Kit.font();
        int rowW = this.rowWidth();

        if (this.items.isEmpty() && ! this.emptyText.isEmpty())
        {
            int lineY = this.y + Math.min(this.h / 2, Ui.ROW_H * 2);

            for (String line : Kit.wrap(font, this.emptyText, Math.max(0, this.w - Ui.PAD_GROUP * 2)))
            {
                Paint.midText(ctx, font, line, this.x + (this.w - font.width(line)) / 2, lineY, Ui.TIP_LINE,
                        Ui.C_TEXT_2, false);
                lineY += Ui.TIP_LINE;
            }
        }

        ctx.enableScissor(this.x, this.y, this.x + this.w, this.y + this.h);

        int hovered = this.dragThumb ? -1 : this.indexAt(mouseX, mouseY);

        for (int i = Math.max(0, this.firstAtOrAfter(this.scroll)); i < this.items.size(); ++i)
        {
            int top = this.y + this.offsets[i] - this.scroll;

            if (top >= this.y + this.h)
            {
                break;
            }

            T item = this.items.get(i);
            int height = this.rowHeight.applyAsInt(item);
            boolean isSelected = this.showSelection && i == this.selected;
            boolean isHovered = i == hovered;
            float hover = this.hoverAnim.update(String.valueOf(i), isHovered && ! isSelected);

            if (isSelected && Style.vanilla())
            {
                // Ванильный стиль: выбранная строка как в списках Minecraft
                Vanilla.selection(ctx, this.x, top, rowW, height, this.focused);
            }
            else if (isSelected)
            {
                Paint.card(ctx, this.x, top, rowW, height, Ui.R_ROW, Ui.C_SELECT_FILL,
                        this.focused ? Ui.C_SELECT_EDGE : 0);
            }
            else if (hover > 0.0F)
            {
                Paint.round(ctx, this.x, top, rowW, height, Ui.R_ROW, Ui.alpha(Ui.C_ROW_HOVER, hover));
            }

            this.renderer.render(ctx, item, i, this.x, top, rowW, height, isSelected, isHovered, mouseX, mouseY);
        }

        ctx.disableScissor();

        this.drawScrollbar(ctx, mouseX, mouseY);
    }

    private int thumbHeight()
    {
        return Math.max(Ui.SCROLL_THUMB_MIN, this.h * this.h / Math.max(1, this.contentHeight()));
    }

    private int thumbY()
    {
        int span = this.h - this.thumbHeight();
        int max = this.maxScroll();
        return this.y + (max == 0 ? 0 : span * this.scroll / max);
    }

    private int scrollbarX()
    {
        return this.x + this.w - Ui.SCROLL_W;
    }

    private void drawScrollbar(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.scrollable())
        {
            return;
        }

        int sx = this.scrollbarX();
        boolean over = Paint.hit(mouseX, mouseY, sx, this.thumbY(), Ui.SCROLL_W, this.thumbHeight());

        if (Style.vanilla())
        {
            // Полоса Minecraft шириной 6 px: расширяется влево в зазор перед полосой
            int vw = Vanilla.SCROLLER_W;
            int vx = sx + Ui.SCROLL_W - vw;

            Vanilla.scrollerTrack(ctx, vx, this.y, vw, this.h);
            Vanilla.scrollerThumb(ctx, vx, this.thumbY(), vw, this.thumbHeight());
            return;
        }

        Paint.round(ctx, sx, this.y, Ui.SCROLL_W, this.h, Ui.R_BAR, Ui.C_TRACK);
        Paint.round(ctx, sx, this.thumbY(), Ui.SCROLL_W, this.thumbHeight(), Ui.R_BAR,
                this.dragThumb || over ? Ui.C_THUMB_DRAG : Ui.C_THUMB);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()))
        {
            return false;
        }

        if (this.scrollable() && click.x() >= this.scrollbarX() - Ui.SCROLL_GAP)
        {
            if (Kit.left(click))
            {
                int thumbY = this.thumbY();

                // Клик по дорожке мимо бегунка — страница в ту сторону, как в экране настроек
                if (click.y() < thumbY || click.y() >= thumbY + this.thumbHeight())
                {
                    this.scroll += (click.y() < thumbY ? -1 : 1) * Math.max(Ui.ROW_H, this.h - Ui.ROW_H);
                    this.clampScroll();
                }

                this.dragThumb = true;
                this.dragGrab = (int) click.y();
            }

            return true;
        }

        int index = this.indexAt(click.x(), click.y());

        if (index < 0)
        {
            return true;
        }

        T item = this.items.get(index);
        int top = this.y + this.offsets[index] - this.scroll;

        if (this.listener.click(item, index, this.x, top, this.rowWidth(), this.rowHeight.applyAsInt(item), click))
        {
            // Кнопка внутри строки сработала — щелчок Minecraft
            Vanilla.click();
            return true;
        }

        if (Kit.left(click) || Kit.right(click))
        {
            this.choose(index);
        }

        if (Kit.left(click) && doubleClick)
        {
            Vanilla.click();
            this.listener.activate(item, index);
        }
        else if (Kit.right(click))
        {
            this.listener.context(item, index, (int) click.x(), (int) click.y());
        }

        return true;
    }

    private void choose(int index)
    {
        if (index == this.selected)
        {
            return;
        }

        this.selected = index;
        this.ensureVisible(index);
        this.listener.select(this.selectedItem(), index);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (! this.dragThumb)
        {
            return false;
        }

        int delta = (int) click.y() - this.dragGrab;
        this.dragGrab = (int) click.y();
        int span = Math.max(1, this.h - this.thumbHeight());
        this.scroll += delta * this.maxScroll() / span;
        this.clampScroll();

        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        boolean was = this.dragThumb;
        this.dragThumb = false;
        return was;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        if (! this.contains(mouseX, mouseY) || amount == 0.0D || ! this.scrollable())
        {
            return false;
        }

        this.scroll -= (int) Math.signum(amount) * Ui.SCROLL_WHEEL_ROWS * (Ui.ROW_H + Ui.ROW_GAP);
        this.clampScroll();

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        if (! this.focused || this.items.isEmpty())
        {
            return false;
        }

        int code = key.key();
        int page = Math.max(1, this.h / Math.max(1, this.rowHeight.applyAsInt(this.items.getFirst()) + this.gap));
        int target;

        if (code == InputConstants.KEY_DOWN)
        {
            target = this.selected < 0 ? 0 : this.selected + 1;
        }
        else if (code == InputConstants.KEY_UP)
        {
            target = this.selected < 0 ? this.items.size() - 1 : this.selected - 1;
        }
        else if (code == InputConstants.KEY_PAGEDOWN)
        {
            target = Math.max(0, this.selected) + page;
        }
        else if (code == InputConstants.KEY_PAGEUP)
        {
            target = Math.max(0, this.selected) - page;
        }
        else if (code == InputConstants.KEY_HOME)
        {
            target = 0;
        }
        else if (code == InputConstants.KEY_END)
        {
            target = this.items.size() - 1;
        }
        else if ((code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER) && this.selected >= 0)
        {
            this.listener.activate(this.items.get(this.selected), this.selected);
            return true;
        }
        else
        {
            return false;
        }

        this.choose(Ui.clamp(target, 0, this.items.size() - 1));
        return true;
    }
}
