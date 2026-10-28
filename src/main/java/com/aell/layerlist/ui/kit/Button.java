package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Anim;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Кнопка в виде кнопок подвала экрана настроек: рамка {@link Ui#R_CTRL}, наведение
 * {@link Ui#C_ROW_HOVER} за {@link Ui#ANIM_HOVER_MS}, нажатие темнит на {@link Ui#PRESS_DARKEN}.
 *
 * <ul>
 *   <li>{@link Style#PRIMARY} — главное действие: заливка и рамка цветом акцента (по умолчанию
 *       {@link Ui#C_ACCENT}; зелёный «Разместить» передаёт владелец из цветов смысла);</li>
 *   <li>{@link Style#SECONDARY} — обычная, рамка {@link Ui#C_DIVIDER};</li>
 *   <li>{@link Style#DANGER} — удаление/сброс, {@link Ui#C_DANGER};</li>
 *   <li>{@link Style#GHOST} — без рамки, только наведение (ссылки, значки в шапке).</li>
 * </ul>
 * Выключенная кнопка рисуется приглушённо, но подсказку показывает — чтобы было видно почему.
 * Действие срабатывает на нажатие левой кнопки, как у кнопок malilib.
 */
@Environment(EnvType.CLIENT)
public class Button extends Widget
{
    public enum Style
    {
        PRIMARY,
        SECONDARY,
        DANGER,
        GHOST
    }

    private static final float FILL_IDLE = 0.16F;
    private static final float FILL_HOVER = 0.28F;
    private static final float EDGE_IDLE = 0.7F;

    private final Anim hoverAnim = new Anim(Ui.ANIM_HOVER_MS);

    protected String label;
    protected Style style;
    protected int accent = Ui.C_ACCENT;
    protected @Nullable String tooltip;
    protected String @Nullable [] glyph;
    protected String @Nullable [] trailing;
    protected boolean centered = true;
    protected boolean selected;
    /** Пунктирная обводка: у схемы, разбитой на зоны, — как рамки зон в мире. */
    protected boolean dashed;
    /** Цвет рамки обычной кнопки по смыслу (0 — обычная серая); подпись и значок тем же цветом, если {@link #tintText}. */
    protected int edgeTint;
    protected boolean tintText;
    protected @Nullable Runnable action;
    private boolean pressed;

    public Button(String label, Style style, @Nullable Runnable action)
    {
        this.label = label;
        this.style = style;
        this.action = action;
    }

    public Button label(String label)
    {
        this.label = label;
        return this;
    }

    public String label()
    {
        return this.label;
    }

    public Button style(Style style)
    {
        this.style = style;
        return this;
    }

    public Button accent(int color)
    {
        this.accent = color;
        return this;
    }

    public Button tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    /** Значок перед подписью. */
    public Button glyph(String @Nullable [] mask)
    {
        this.glyph = mask;
        return this;
    }

    /** Значок после подписи — например {@link Glyphs#CHEVRON_DOWN} у «Выгрузить ▾». */
    public Button trailing(String @Nullable [] mask)
    {
        this.trailing = mask;
        return this;
    }

    public Button centered(boolean value)
    {
        this.centered = value;
        return this;
    }

    public Button action(@Nullable Runnable action)
    {
        this.action = action;
        return this;
    }

    /** Нажатое состояние сегмента (переключатели вида, вкладки внутри панели). */
    public Button dashed(boolean value)
    {
        this.dashed = value;
        return this;
    }

    /**
     * Рамка обычной ({@link Style#SECONDARY}) кнопки цветом смысла без заливки: «прыжок в расчётное место» —
     * пунктиром ({@link #dashed}) цветом акцента, замок размещения — золотом. {@code tintText} — подпись и значок
     * тем же цветом. {@code 0} — рамка как у всех.
     */
    public Button edge(int color, boolean tintText)
    {
        this.edgeTint = color;
        this.tintText = tintText;
        return this;
    }

    public Button selected(boolean value)
    {
        this.selected = value;
        return this;
    }

    public boolean isSelected()
    {
        return this.selected;
    }

    /** Ширина, при которой подпись и значки помещаются целиком. */
    public int preferredWidth(Font font)
    {
        int width = font.width(this.label) + Ui.BUTTON_PAD_X * 2;

        if (this.glyph != null)
        {
            width += Glyphs.width(this.glyph) + (this.label.isEmpty() ? 0 : Ui.PAD_IN);
        }

        if (this.trailing != null)
        {
            width += Glyphs.width(this.trailing) + Ui.PAD_IN;
        }

        return width;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0 || this.h <= 0)
        {
            return;
        }

        Font font = Kit.font();
        boolean over = this.contains(mouseX, mouseY);
        float hover = this.hoverAnim.update(over && this.enabled);
        boolean down = this.pressed && over;

        int text;
        int fill = 0;
        int edge;

        if (! this.enabled)
        {
            text = Ui.C_TEXT_OFF;
            edge = this.style == Style.GHOST ? 0 : Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF);
        }
        else if (this.selected)
        {
            // Нажатая главная кнопка остаётся своего цвета (зелёный «Excel» в окне выгрузки): синяя
            // отметка выбора сливалась бы с синей соседней главной кнопкой
            int color = this.style == Style.PRIMARY ? this.accent : Ui.C_ACCENT;

            text = Ui.C_TEXT;
            fill = Ui.alpha(color, FILL_IDLE + (FILL_HOVER - FILL_IDLE) * hover);
            edge = color;
        }
        else
        {
            switch (this.style)
            {
                case PRIMARY ->
                {
                    text = this.accent;
                    fill = Ui.alpha(this.accent, FILL_IDLE + (FILL_HOVER - FILL_IDLE) * hover);
                    edge = Ui.alpha(this.accent, EDGE_IDLE + (1.0F - EDGE_IDLE) * hover);
                }
                case DANGER ->
                {
                    text = Ui.C_DANGER;
                    fill = Ui.alpha(Ui.C_DANGER, FILL_IDLE * hover);
                    edge = Ui.alpha(Ui.C_DANGER, EDGE_IDLE + (1.0F - EDGE_IDLE) * hover);
                }
                case GHOST ->
                {
                    text = Paint.mix(Ui.C_TEXT_2, Ui.C_TEXT, hover);
                    fill = Ui.alpha(Ui.C_ROW_HOVER, hover);
                    edge = 0;
                }
                default ->
                {
                    boolean tinted = this.edgeTint != 0;

                    text = tinted && this.tintText ? this.edgeTint : Paint.mix(Ui.C_TEXT_2, Ui.C_TEXT, hover);
                    fill = tinted ? Ui.alpha(this.edgeTint, FILL_IDLE * hover) : Ui.alpha(Ui.C_ROW_HOVER, hover);
                    edge = tinted ? Ui.alpha(this.edgeTint, EDGE_IDLE + (1.0F - EDGE_IDLE) * hover) : Ui.C_DIVIDER;
                }
            }
        }

        // Ванильный стиль: кнопка Minecraft, у выбранной сегмента — подсветка и жёлтая надпись; значки поверх
        // (у Button есть свой вложенный Style, поэтому флаг стиля — по полному имени)
        if (com.aell.layerlist.shaded.ui.Style.vanilla())
        {
            boolean lit = this.enabled && (over || this.selected);
            int vText = ! this.enabled ? com.aell.layerlist.shaded.ui.Style.V_GRAY
                    : this.edgeTint != 0 && this.tintText ? com.aell.layerlist.shaded.ui.Style.color(this.edgeTint)
                    : this.selected ? com.aell.layerlist.shaded.ui.Style.V_HOVER
                    : this.style == Style.DANGER ? com.aell.layerlist.shaded.ui.Style.V_RED : com.aell.layerlist.shaded.ui.Style.V_WHITE;

            Vanilla.button(ctx, this.x, this.y, this.w, this.h, this.enabled, lit);
            this.drawFace(ctx, font, vText);
            this.offerTip(font, over);
            return;
        }

        if (down)
        {
            fill = fill == 0 ? Ui.C_ROW_FOCUS : Ui.darken(fill, Ui.PRESS_DARKEN);
            text = Ui.darken(text, Ui.PRESS_DARKEN);
        }

        if (fill != 0)
        {
            Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, fill);
        }

        if (edge != 0 && this.dashed)
        {
            dashedBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, edge);
        }
        else if (edge != 0)
        {
            Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, edge);
        }

        this.drawFace(ctx, font, text);

        this.offerTip(font, over);
    }

    private void offerTip(Font font, boolean over)
    {
        if (over)
        {
            String tip = this.tooltip;

            // Обрезанная подпись без своей подсказки — подсказкой становится полная подпись
            if (tip == null && font.width(this.label) > this.labelRoom())
            {
                tip = this.label;
            }

            Tooltip.offer(this, tip);
        }
    }

    private int labelRoom()
    {
        int room = this.w - Ui.BUTTON_PAD_X * 2;

        if (this.glyph != null)
        {
            room -= Glyphs.width(this.glyph) + (this.label.isEmpty() ? 0 : Ui.PAD_IN);
        }

        if (this.trailing != null)
        {
            room -= Glyphs.width(this.trailing) + Ui.PAD_IN;
        }

        return room;
    }

    protected void drawFace(GuiContext ctx, Font font, int color)
    {
        String shown = Paint.fit(font, this.label, Math.max(0, this.labelRoom()));
        int content = font.width(shown);

        if (this.glyph != null)
        {
            content += Glyphs.width(this.glyph) + (shown.isEmpty() ? 0 : Ui.PAD_IN);
        }

        if (this.trailing != null)
        {
            content += Glyphs.width(this.trailing) + Ui.PAD_IN;
        }

        int cx = this.centered ? this.x + (this.w - content) / 2 : this.x + Ui.BUTTON_PAD_X;
        cx = Math.max(cx, this.x + Math.min(Ui.BUTTON_PAD_X, Math.max(0, (this.w - content) / 2)));

        if (this.glyph != null)
        {
            Glyphs.draw(ctx, this.glyph, cx, this.y + (this.h - Glyphs.height(this.glyph) + 1) / 2, color);
            cx += Glyphs.width(this.glyph) + (shown.isEmpty() ? 0 : Ui.PAD_IN);
        }

        if (! shown.isEmpty())
        {
            Paint.midText(ctx, font, shown, cx, this.y, this.h, color, false);
            cx += font.width(shown);
        }

        if (this.trailing != null)
        {
            cx += Ui.PAD_IN;
            Glyphs.draw(ctx, this.trailing, cx, this.y + (this.h - Glyphs.height(this.trailing) + 1) / 2, color);
        }
    }

    /** Пунктир по прямым краям (штрих 3, пробел 2), углы скругления пустые — читается как рамка со скруглением. */
    public static void dashedBorder(GuiContext ctx, int x, int y, int w, int h, int radius, int color)
    {
        int r = Math.max(1, radius);

        for (int i = x + r; i < x + w - r; i += 5)
        {
            int e = Math.min(i + 3, x + w - r);
            ctx.fill(i, y, e, y + 1, color);
            ctx.fill(i, y + h - 1, e, y + h, color);
        }

        for (int j = y + r; j < y + h - r; j += 5)
        {
            int e = Math.min(j + 3, y + h - r);
            ctx.fill(x, j, x + 1, e, color);
            ctx.fill(x + w - 1, j, x + w, e, color);
        }

        // Углы — по точке на диагонали скругления
        int d = Math.max(0, r - 1 - r / 3);
        ctx.fill(x + d, y + d, x + d + 1, y + d + 1, color);
        ctx.fill(x + w - d - 1, y + d, x + w - d, y + d + 1, color);
        ctx.fill(x + d, y + h - d - 1, x + d + 1, y + h - d, color);
        ctx.fill(x + w - d - 1, y + h - d - 1, x + w - d, y + h - d, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()) || ! Kit.left(click))
        {
            return false;
        }

        // Выключенная кнопка клик съедает: иначе он провалился бы в строку списка под ней
        if (this.enabled)
        {
            this.pressed = true;
            Tooltip.reset();
            Vanilla.click();

            if (this.action != null)
            {
                this.action.run();
            }
        }

        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        this.pressed = false;
        return false;
    }
}
