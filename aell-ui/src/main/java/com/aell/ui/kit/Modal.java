package com.aell.ui.kit;

import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Модальное окно по центру с затемнением экрана ({@link Ui#C_DIM}): шапка {@link Ui#C_CHROME} с
 * заголовком и крестиком, тело (текст с переносом и/или строки-виджеты, прокрутка, если не влезает),
 * подвал с кнопками справа. Esc — закрыть (сначала снимается фокус с поля ввода), Enter — кнопка
 * по умолчанию, Tab — следующее поле ввода. Буквы и черновик IME окно отдаёт полю в фокусе, а ввод
 * текста SDL поле включает само ({@link TextFocus}); закрытое окно снимает фокус — и ввод гаснет.
 *
 * <p>Встроенные подтверждения экрана настроек («Точно? Да · Нет») подходят для одной кнопки; здесь
 * подтверждения с объяснением (удалить размещение, заменить файлы) — поэтому окно.
 *
 * <p>Строки бывают постоянной высоты и «живые» ({@link #row(Widget, IntUnaryOperator)}): высота
 * считается от ширины тела каждый кадр, 0 — строка скрыта (окно выгрузки: чипы ников, статусы).
 */
@Environment(EnvType.CLIENT)
public class Modal implements Popup
{
    private static final int TITLE_H = Ui.ROW_H;
    private static final int BUTTON_MIN_W = 56;

    private record Row(Widget widget, IntUnaryOperator height) {}

    protected final Layers layers;
    private final String title;
    private final List<String> paragraphs = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final IconButton close;
    private int preferredWidth = 280;
    private @Nullable Runnable onCancel;
    private @Nullable Button defaultButton;
    private @Nullable Widget scrollTarget;

    private int x;
    private int y;
    private int w;
    private int h;
    private int bodyY;
    private int bodyH;
    private int contentH;
    private int scroll;
    private List<String> lines = List.of();
    private int[] heights = new int[0];

    public Modal(Layers layers, String title, String closeTooltip)
    {
        this.layers = layers;
        this.title = title;
        this.close = new IconButton(Glyphs.CROSS, closeTooltip, Button.Style.GHOST, this::cancel);
    }

    /** Подтверждение одной кнопкой; {@code danger} — красная кнопка действия. */
    public static Modal confirm(Layers layers, String title, String text, String yes, boolean danger, Runnable onYes,
            String no)
    {
        Modal modal = new Modal(layers, title, no);
        modal.text(text);
        modal.button(new Button(no, Button.Style.SECONDARY, modal::cancel));
        Button confirm = new Button(yes, danger ? Button.Style.DANGER : Button.Style.PRIMARY, () ->
        {
            modal.dismiss();
            onYes.run();
        });
        modal.button(confirm);
        modal.defaultButton(confirm);
        modal.open();

        return modal;
    }

    public Modal width(int width)
    {
        this.preferredWidth = width;
        return this;
    }

    public Modal text(String text)
    {
        this.paragraphs.add(text);
        return this;
    }

    /** Строка-виджет на всю ширину тела, высотой {@code height}. */
    public Modal row(Widget widget, int height)
    {
        return this.row(widget, width -> height);
    }

    /**
     * Строка, высота которой зависит от ширины тела и может меняться, пока окно открыто (перенос
     * текста, чипы, появляющаяся кнопка): считается заново каждый кадр. Высота 0 — строки нет: ни
     * места, ни отступа, ввод ей не достаётся.
     */
    public Modal row(Widget widget, IntUnaryOperator heightForWidth)
    {
        this.rows.add(new Row(widget, heightForWidth));
        return this;
    }

    /** Кнопки подвала, слева направо; самая правая — обычно главная. */
    public Modal button(Button button)
    {
        this.buttons.add(button);
        return this;
    }

    public Modal defaultButton(@Nullable Button button)
    {
        this.defaultButton = button;
        return this;
    }

    public Modal onCancel(@Nullable Runnable action)
    {
        this.onCancel = action;
        return this;
    }

    /**
     * На следующей раскладке прокрутить тело так, чтобы строка {@code widget} была видна целиком
     * (итог выгрузки появляется в самом низу тела — при GUI 3 он ушёл бы под край прокрутки).
     */
    public void scrollTo(Widget widget)
    {
        this.scrollTarget = widget;
    }

    public void open()
    {
        this.layout();
        this.layers.push(this);
    }

    /** Закрыть без «отмены» (после выбора действия). */
    public void dismiss()
    {
        this.layers.close(this);
    }

    /** Отмена: крестик, Esc, кнопка «Отмена». */
    public void cancel()
    {
        this.layers.close(this);

        if (this.onCancel != null)
        {
            this.onCancel.run();
        }
    }

    @Override
    public boolean modal()
    {
        return true;
    }

    private void layout()
    {
        Font font = Kit.font();
        int screenW = this.layers.screenW();
        int screenH = this.layers.screenH();

        this.w = Math.min(this.preferredWidth, Math.max(0, screenW - Ui.OUTER * 2));
        int inner = this.w - Ui.PAD_GROUP * 2;

        List<String> wrapped = new ArrayList<>();

        for (int i = 0; i < this.paragraphs.size(); ++i)
        {
            if (i > 0)
            {
                wrapped.add("");
            }

            wrapped.addAll(Kit.wrap(font, this.paragraphs.get(i), inner));
        }

        this.lines = wrapped;
        this.contentH = this.lines.size() * Ui.TIP_LINE;

        if (this.heights.length != this.rows.size())
        {
            this.heights = new int[this.rows.size()];
        }

        for (int i = 0; i < this.rows.size(); ++i)
        {
            int height = Math.max(0, this.rows.get(i).height.applyAsInt(inner));
            this.heights[i] = height;

            if (height > 0)
            {
                this.contentH += (this.contentH > 0 ? Ui.PAD_IN : 0) + height;
            }
        }

        int chrome = TITLE_H + Ui.FOOTER_H + Ui.PAD_GROUP * 2;
        this.bodyH = Math.max(0, Math.min(this.contentH, screenH - Ui.OUTER * 2 - chrome));
        this.h = this.bodyH + chrome;
        this.x = (screenW - this.w) / 2;
        this.y = Math.max(0, (screenH - this.h) / 2);
        this.bodyY = this.y + TITLE_H + Ui.PAD_GROUP;

        if (this.scrollTarget != null)
        {
            this.revealRow(this.scrollTarget);
            this.scrollTarget = null;
        }

        this.scroll = Ui.clamp(this.scroll, 0, Math.max(0, this.contentH - this.bodyH));

        this.close.setBounds(this.x + this.w - IconButton.size() - Ui.PAD_MICRO,
                this.y + (TITLE_H - IconButton.size()) / 2, IconButton.size(), IconButton.size());

        int cy = this.bodyY + this.lines.size() * Ui.TIP_LINE - this.scroll;
        boolean first = this.lines.isEmpty();

        for (int i = 0; i < this.rows.size(); ++i)
        {
            Widget widget = this.rows.get(i).widget;
            int height = this.heights[i];

            // Скрытая строка: нулевой прямоугольник, чтобы старые координаты не ловили клики
            if (height == 0)
            {
                widget.setBounds(this.x + Ui.PAD_GROUP, cy, inner, 0);
                continue;
            }

            if (! first)
            {
                cy += Ui.PAD_IN;
            }

            first = false;
            widget.setBounds(this.x + Ui.PAD_GROUP, cy, inner, height);
            cy += height;
        }

        int bx = this.x + this.w - Ui.PAD_GROUP;
        int by = this.y + this.h - Ui.FOOTER_H + (Ui.FOOTER_H - Ui.BUTTON_H) / 2;
        int room = this.w - Ui.PAD_GROUP * 2;
        int[] widths = new int[this.buttons.size()];
        int total = 0;

        for (int i = 0; i < this.buttons.size(); ++i)
        {
            widths[i] = Math.max(BUTTON_MIN_W, this.buttons.get(i).preferredWidth(font));
            total += widths[i] + (i > 0 ? Ui.PAD_REL : 0);
        }

        // Не влезли — сжимаем все поровну (подписи обрежутся «…», подсказка покажет целиком)
        if (total > room && ! this.buttons.isEmpty())
        {
            int each = Math.max(Ui.BUTTON_PAD_X * 2, (room - Ui.PAD_REL * (this.buttons.size() - 1)) / this.buttons.size());
            Arrays.fill(widths, each);
        }

        for (int i = this.buttons.size() - 1; i >= 0; --i)
        {
            bx -= widths[i];
            this.buttons.get(i).setBounds(bx, by, widths[i], Ui.BUTTON_H);
            bx -= Ui.PAD_REL;
        }
    }

    /** Прокрутка к строке {@code target}: те же отступы, что у раскладки строк ниже; скрытая — без прокрутки. */
    private void revealRow(Widget target)
    {
        int cy = this.lines.size() * Ui.TIP_LINE;
        boolean first = this.lines.isEmpty();

        for (int i = 0; i < this.rows.size(); ++i)
        {
            int height = this.heights[i];

            if (height == 0)
            {
                continue;
            }

            if (! first)
            {
                cy += Ui.PAD_IN;
            }

            first = false;

            if (this.rows.get(i).widget == target)
            {
                if (cy + height - this.scroll > this.bodyH)
                {
                    this.scroll = cy + height - this.bodyH;
                }

                if (cy < this.scroll)
                {
                    this.scroll = cy;
                }

                return;
            }

            cy += height;
        }
    }

    @Override
    public boolean contains(double mouseX, double mouseY)
    {
        return Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), this.x, this.y, this.w, this.h);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.layout();

        Font font = Kit.font();

        if (Style.vanilla())
        {
            // Ванильное окно: подложка popup, шапка без заливки и разделителя
            Vanilla.popup(ctx, this.x, this.y, this.w, this.h);
        }
        else
        {
            Paint.card(ctx, this.x, this.y, this.w, this.h, Ui.R_PLATE, Ui.C_PANEL, Ui.C_PANEL_EDGE);
            Paint.rect(ctx, this.x + Ui.BORDER, this.y + Ui.BORDER, this.w - Ui.BORDER * 2, TITLE_H - Ui.BORDER, Ui.C_CHROME);
            Paint.rect(ctx, this.x, this.y + TITLE_H, this.w, Ui.BORDER, Ui.C_DIVIDER);
        }

        Paint.midText(ctx, font, Paint.fit(font, this.title, this.w - Ui.PAD_GROUP - IconButton.size() - Ui.PAD_IN),
                this.x + Ui.PAD_GROUP, this.y, TITLE_H, Ui.C_TEXT, true);
        this.close.render(ctx, mouseX, mouseY);

        boolean inBody = Paint.hit(mouseX, mouseY, this.x, this.bodyY, this.w, this.bodyH);
        int bodyMouseX = inBody ? mouseX : -1;
        int bodyMouseY = inBody ? mouseY : -1;

        ctx.enableScissor(this.x, this.bodyY, this.x + this.w, this.bodyY + this.bodyH);

        int ty = this.bodyY - this.scroll;

        for (String line : this.lines)
        {
            Paint.text(ctx, font, line, this.x + Ui.PAD_GROUP, ty + 1, Ui.C_TEXT_2, false);
            ty += Ui.TIP_LINE;
        }

        for (int i = 0; i < this.rows.size(); ++i)
        {
            if (this.shown(i))
            {
                this.rows.get(i).widget.render(ctx, bodyMouseX, bodyMouseY);
            }
        }

        ctx.disableScissor();

        if (this.contentH > this.bodyH)
        {
            int sx = this.x + this.w - Ui.SCROLL_W - Ui.PAD_MICRO;
            int thumb = Math.max(Ui.SCROLL_THUMB_MIN, this.bodyH * this.bodyH / Math.max(1, this.contentH));
            int max = Math.max(1, this.contentH - this.bodyH);

            if (Style.vanilla())
            {
                Vanilla.scrollerTrack(ctx, sx, this.bodyY, Ui.SCROLL_W, this.bodyH);
                Vanilla.scrollerThumb(ctx, sx, this.bodyY + (this.bodyH - thumb) * this.scroll / max, Ui.SCROLL_W, thumb);
            }
            else
            {
                Paint.round(ctx, sx, this.bodyY, Ui.SCROLL_W, this.bodyH, Ui.R_BAR, Ui.C_TRACK);
                Paint.round(ctx, sx, this.bodyY + (this.bodyH - thumb) * this.scroll / max, Ui.SCROLL_W, thumb, Ui.R_BAR,
                        Ui.C_THUMB);
            }
        }

        int footerY = this.y + this.h - Ui.FOOTER_H;

        if (! Style.vanilla())
        {
            Paint.rect(ctx, this.x + Ui.BORDER, footerY, this.w - Ui.BORDER * 2, Ui.FOOTER_H - Ui.BORDER, Ui.C_CHROME);
            Paint.rect(ctx, this.x, footerY, this.w, Ui.BORDER, Ui.C_DIVIDER);
        }

        for (Button button : this.buttons)
        {
            button.render(ctx, mouseX, mouseY);
        }
    }

    private boolean shown(int index)
    {
        return index < this.heights.length && this.heights[index] > 0;
    }

    private @Nullable TextInput focusedInput()
    {
        for (Row row : this.rows)
        {
            TextInput input = row.widget.textInput();

            if (input != null && input.focused())
            {
                return input;
            }
        }

        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.close.mouseClicked(click, doubleClick))
        {
            return true;
        }

        for (Button button : this.buttons)
        {
            if (button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        boolean inBody = click.y() >= this.bodyY && click.y() < this.bodyY + this.bodyH;

        for (Row row : this.rows)
        {
            TextInput input = row.widget.textInput();

            if (input != null && ! (inBody && input.contains(click.x(), click.y())))
            {
                input.setFocused(false);
            }
        }

        if (! inBody)
        {
            return true;
        }

        for (int i = 0; i < this.rows.size(); ++i)
        {
            if (this.shown(i) && this.rows.get(i).widget.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        for (Row row : this.rows)
        {
            if (row.widget.mouseDragged(click, dx, dy))
            {
                return true;
            }
        }

        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : this.buttons)
        {
            button.mouseReleased(click);
        }

        this.close.mouseReleased(click);

        for (Row row : this.rows)
        {
            row.widget.mouseReleased(click);
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        for (int i = 0; i < this.rows.size(); ++i)
        {
            if (this.shown(i) && this.rows.get(i).widget.mouseScrolled(mouseX, mouseY, amount))
            {
                return true;
            }
        }

        if (this.contentH > this.bodyH && amount != 0.0D)
        {
            this.scroll = Ui.clamp(this.scroll - (int) Math.signum(amount) * Ui.ROW_H, 0, this.contentH - this.bodyH);
        }

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        int code = key.key();
        TextInput input = this.focusedInput();

        if (code == InputConstants.KEY_ESCAPE)
        {
            if (input != null)
            {
                input.setFocused(false);
            }
            else
            {
                this.cancel();
            }

            return true;
        }

        if (code == InputConstants.KEY_TAB)
        {
            this.cycleInputs(input, key.hasShiftDown() ? -1 : 1);
            return true;
        }

        if (input != null && input.keyPressed(key))
        {
            return true;
        }

        if ((code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER) && this.defaultButton != null
                && this.defaultButton.enabled())
        {
            if (this.defaultButton.action != null)
            {
                Vanilla.click();
                this.defaultButton.action.run();
            }

            return true;
        }

        return true;
    }

    private void cycleInputs(@Nullable TextInput current, int step)
    {
        List<TextInput> inputs = new ArrayList<>();

        for (Row row : this.rows)
        {
            TextInput input = row.widget.textInput();

            if (input != null && input.visible() && input.enabled())
            {
                inputs.add(input);
            }
        }

        if (inputs.isEmpty())
        {
            return;
        }

        int at = current == null ? (step > 0 ? -1 : 0) : inputs.indexOf(current);
        TextInput next = inputs.get(Math.floorMod(at + step, inputs.size()));

        // Сначала фокус новому полю, потом снять со старого: ввод текста SDL переходит из рук в
        // руки, не выключаясь (иначе IME на миг гаснет и теряет начатый набор)
        if (next != current)
        {
            next.setFocused(true);

            if (current != null)
            {
                current.setFocused(false);
            }
        }

        next.selectAll();
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        TextInput input = this.focusedInput();

        if (input != null)
        {
            input.charTyped(event);
        }

        return true;
    }

    @Override
    public void onClose()
    {
        TextInput input = this.focusedInput();

        if (input != null)
        {
            input.setFocused(false);
        }
    }
}
