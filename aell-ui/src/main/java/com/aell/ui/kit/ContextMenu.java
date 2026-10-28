package com.aell.ui.kit;

import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Меню-попап: контекстное меню по ПКМ и раскрытый список {@link Dropdown}. Вид — как выпадашка «⋯»
 * шапки настроек: карточка {@link Ui#C_CHROME}, строки {@link Ui#HEAD_BTN_H}. Прижимается к краям
 * экрана; если снизу не влезает — открывается вверх от точки.
 *
 * <p>Клавиатура: ↑/↓ — пункт, Enter/пробел — выбрать, Esc — закрыть (через {@link Layers}).
 */
@Environment(EnvType.CLIENT)
public final class ContextMenu implements Popup
{
    /** Пункт меню. {@code checked} рисует галочку (выбранное значение выпадашки). */
    public record Item(String label, boolean enabled, boolean danger, boolean checked, @Nullable String tooltip,
                       @Nullable Runnable action)
    {
        public static Item of(String label, Runnable action)
        {
            return new Item(label, true, false, false, null, action);
        }

        public static Item danger(String label, Runnable action)
        {
            return new Item(label, true, true, false, null, action);
        }

        public static Item disabled(String label, @Nullable String why)
        {
            return new Item(label, false, false, false, why, null);
        }

        public static Item separator()
        {
            return new Item("", false, false, false, null, null);
        }

        public boolean isSeparator()
        {
            return this.label.isEmpty() && this.action == null;
        }

        public Item withTooltip(@Nullable String text)
        {
            return new Item(this.label, this.enabled, this.danger, this.checked, text, this.action);
        }
    }

    private static final int SEPARATOR_H = Ui.PAD_REL + 1;

    private final Layers layers;
    private final List<Item> items;
    private int x;
    private int y;
    private int w;
    private int h;
    private int focus = -1;
    /** Строка, которая сейчас бежит (под курсором или в фокусе), и с какого момента — круг с начала. */
    private int marqueeRow = -1;
    private long marqueeSince;

    private ContextMenu(Layers layers, List<Item> items)
    {
        this.layers = layers;
        this.items = List.copyOf(items);
    }

    /** Открыть у точки ({@code anchorX}, {@code anchorY}); {@code minWidth} — не уже этого. */
    public static ContextMenu open(Layers layers, int anchorX, int anchorY, int minWidth, List<Item> items)
    {
        ContextMenu menu = new ContextMenu(layers, items);
        menu.place(anchorX, anchorY, minWidth, 0);
        layers.push(menu);

        return menu;
    }

    /**
     * Открыть под прямоугольником {@code (x, y, w, h)} (кнопка выпадашки); не влезает снизу —
     * над ним.
     */
    public static ContextMenu below(Layers layers, int x, int y, int w, int h, List<Item> items)
    {
        ContextMenu menu = new ContextMenu(layers, items);
        menu.place(x, y + h, w, h);
        layers.push(menu);

        return menu;
    }

    private void place(int anchorX, int anchorY, int minWidth, int anchorH)
    {
        Font font = Kit.font();
        int width = minWidth;
        int height = Ui.PAD_MICRO * 2;

        for (Item item : this.items)
        {
            if (item.isSeparator())
            {
                height += SEPARATOR_H;
                continue;
            }

            // Очень длинное имя меню не раздувает: шире Ui.LONG_TEXT_W — многоточие, под курсором бежит
            width = Math.max(width, Math.min(font.width(item.label), Ui.LONG_TEXT_W) + Ui.HEAD_BTN_PAD_X * 2
                    + this.checkRoom());
            height += Ui.HEAD_BTN_H;
        }

        int screenW = this.layers.screenW();
        int screenH = this.layers.screenH();

        this.w = Math.min(width, Math.max(0, screenW - Ui.PAD_IN * 2));
        this.h = Math.min(height, Math.max(0, screenH - Ui.PAD_IN * 2));
        this.x = Ui.clamp(anchorX, Ui.PAD_IN, Math.max(Ui.PAD_IN, screenW - this.w - Ui.PAD_IN));

        int y = anchorY;

        if (y + this.h > screenH - Ui.PAD_IN)
        {
            y = anchorY - anchorH - this.h;
        }

        this.y = Ui.clamp(y, Ui.PAD_IN, Math.max(Ui.PAD_IN, screenH - this.h - Ui.PAD_IN));
    }

    private int checkRoom()
    {
        for (Item item : this.items)
        {
            if (item.checked)
            {
                return Glyphs.width(Glyphs.CHECK) + Ui.PAD_IN;
            }
        }

        return 0;
    }

    @Override
    public boolean contains(double mouseX, double mouseY)
    {
        return Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), this.x, this.y, this.w, this.h);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();

        boolean vanilla = Style.vanilla();

        if (vanilla)
        {
            Vanilla.tooltip(ctx, this.x, this.y, this.w, this.h);
        }
        else
        {
            Paint.card(ctx, this.x, this.y, this.w, this.h, Ui.R_PLATE, Ui.C_CHROME, Ui.C_DIVIDER);
        }

        ctx.enableScissor(this.x, this.y, this.x + this.w, this.y + this.h);

        int rowY = this.y + Ui.PAD_MICRO;
        int check = this.checkRoom();
        boolean mouseIn = this.contains(mouseX, mouseY);
        int active = -1;

        for (int i = 0; i < this.items.size(); ++i)
        {
            Item item = this.items.get(i);

            if (item.isSeparator())
            {
                Paint.rect(ctx, this.x + Ui.PAD_IN, rowY + SEPARATOR_H / 2, this.w - Ui.PAD_IN * 2, Ui.BORDER,
                        vanilla ? 0x40FFFFFF : Ui.C_DIVIDER);
                rowY += SEPARATOR_H;
                continue;
            }

            boolean over = Paint.hit(mouseX, mouseY, this.x, rowY, this.w, Ui.HEAD_BTN_H);

            if ((over || i == this.focus) && item.enabled)
            {
                Paint.round(ctx, this.x + Ui.PAD_MICRO, rowY, this.w - Ui.PAD_MICRO * 2, Ui.HEAD_BTN_H, Ui.R_ROW,
                        vanilla ? 0x40FFFFFF : i == this.focus && ! over ? Ui.C_ROW_FOCUS : Ui.C_ROW_HOVER);
            }

            int color = ! item.enabled ? Ui.C_TEXT_OFF
                    : item.danger ? Ui.C_DANGER
                    : item.checked ? (vanilla ? Style.V_HOVER : Ui.C_ACCENT)
                    : vanilla ? Style.V_WHITE
                    : over ? Ui.C_TEXT : Ui.C_TEXT_2;

            int textX = this.x + Ui.HEAD_BTN_PAD_X;

            if (item.checked)
            {
                Glyphs.draw(ctx, Glyphs.CHECK, textX, rowY + (Ui.HEAD_BTN_H - Glyphs.height(Glyphs.CHECK)) / 2,
                        vanilla ? Style.V_HOVER : Ui.C_ACCENT);
            }

            if (over || (i == this.focus && ! mouseIn))
            {
                active = i;

                if (this.marqueeRow != i)
                {
                    this.marqueeRow = i;
                    this.marqueeSince = net.minecraft.util.Util.getMillis();
                }
            }

            Paint.midMarquee(ctx, font, item.label, textX + check, rowY, Ui.HEAD_BTN_H,
                    this.w - Ui.HEAD_BTN_PAD_X * 2 - check, color, false, active == i ? this.marqueeSince : -1L);

            if (over)
            {
                Tooltip.offer(item, item.tooltip);
            }

            rowY += Ui.HEAD_BTN_H;
        }

        if (active < 0)
        {
            this.marqueeRow = -1;
        }

        ctx.disableScissor();
    }

    private int indexAt(double mouseY)
    {
        int rowY = this.y + Ui.PAD_MICRO;

        for (int i = 0; i < this.items.size(); ++i)
        {
            int height = this.items.get(i).isSeparator() ? SEPARATOR_H : Ui.HEAD_BTN_H;

            if (mouseY >= rowY && mouseY < rowY + height)
            {
                return i;
            }

            rowY += height;
        }

        return -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        int index = this.indexAt(click.y());

        if (index >= 0 && (Kit.left(click) || Kit.right(click)))
        {
            this.choose(index);
        }

        return true;
    }

    private void choose(int index)
    {
        Item item = this.items.get(index);

        if (! item.enabled || item.isSeparator())
        {
            return;
        }

        // Сначала закрыть: действие может открыть новый попап (подтверждение), и он должен лечь сверху
        this.layers.close(this);
        Vanilla.click();

        if (item.action != null)
        {
            item.action.run();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        int code = key.key();

        if (code == InputConstants.KEY_DOWN || code == InputConstants.KEY_UP)
        {
            this.moveFocus(code == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }

        if ((code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER
                || code == InputConstants.KEY_SPACE) && this.focus >= 0)
        {
            this.choose(this.focus);
            return true;
        }

        return false;
    }

    private void moveFocus(int step)
    {
        List<Integer> usable = new ArrayList<>();

        for (int i = 0; i < this.items.size(); ++i)
        {
            if (this.items.get(i).enabled && ! this.items.get(i).isSeparator())
            {
                usable.add(i);
            }
        }

        if (usable.isEmpty())
        {
            return;
        }

        int at = usable.indexOf(this.focus);
        int next = at < 0 ? (step > 0 ? 0 : usable.size() - 1) : Math.floorMod(at + step, usable.size());
        this.focus = usable.get(next);
    }
}
