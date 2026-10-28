package com.aell.layerlist.schematics.place;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.TextFocus;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Элементы панели редактора размещения в виде aell-ui: кнопка, кнопка с автоповтором, переключатель,
 * поле целого числа, ряд выбора, выбор по кругу, приглушённая пометка. Рисуются примитивами
 * {@link Paint} и токенами {@link Ui}, цвет смысла (зелёное «Готово») приходит снаружи. Свои, а не общий набор {@code ui.kit}: панель маленькая и
 * не должна ждать общий набор, который пишется параллельно.
 */
@Environment(EnvType.CLIENT)
final class EditorControls
{
    private static final float A_ACCENT_FILL = 0.16F;
    private static final float A_ACCENT_FILL_HOVER = 0.28F;
    private static final float A_ACCENT_EDGE = 0.7F;
    private static final float A_SELECTED_FILL = 0.22F;
    private static final float A_EDGE_HOVER = 0.14F;

    private EditorControls() {}

    /** Прямоугольник с подсказкой; клик и колесо — по желанию наследника. */
    abstract static class Control
    {
        int x;
        int y;
        int w;
        int h;
        boolean visible = true;
        @Nullable Supplier<String> tip;

        final Control at(int x, int y, int w, int h)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.visible = w > 0 && h > 0;

            return this;
        }

        final Control tip(Supplier<String> text)
        {
            this.tip = text;

            return this;
        }

        boolean over(double mouseX, double mouseY)
        {
            return this.visible && Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY),
                    this.x, this.y, this.w, this.h);
        }

        abstract void draw(GuiContext ctx, Font font, int mouseX, int mouseY);

        /** Клик левой кнопкой по элементу; {@code shift} — с зажатым Shift. */
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            return false;
        }

        boolean scroll(double amount, boolean shift)
        {
            return false;
        }
    }

    /**
     * Кнопка с подписью и/или пиксельной иконкой. Цвет смысла — тремя способами, по нарастающей:
     * {@link #edge} — рамка цветом (сплошная или {@link #dashed} пунктиром, пунктир — «прыжок в расчётное место»),
     * {@link #accent} — ещё и заливка. Все три — поставщики: цвет и пунктир следят за состоянием (замок, вид камеры).
     * Выключенная кнопка — всегда серая, без цвета и пунктира.
     */
    static final class Button extends Control
    {
        private static final float A_EDGE_HOVER_FILL = 0.14F;

        private final Supplier<String> label;
        private final Consumer<Boolean> action;
        private Supplier<String @Nullable []> icon = () -> null;
        private IntSupplier accent = () -> 0;
        private IntSupplier edge = () -> 0;
        private boolean tintText;
        private BooleanSupplier dashed = () -> false;
        private BooleanSupplier enabled = () -> true;
        private BooleanSupplier selected = () -> false;

        Button(Supplier<String> label, Consumer<Boolean> action)
        {
            this.label = label;
            this.action = action;
        }

        Button icon(String[] mask)
        {
            this.icon = () -> mask;

            return this;
        }

        /** Значок, зависящий от состояния (замок закрыт/открыт). */
        Button icon(Supplier<String @Nullable []> mask)
        {
            this.icon = mask;

            return this;
        }

        /** Сплошной цвет смысла: заливка и рамка. */
        Button accent(int color)
        {
            this.accent = () -> color;

            return this;
        }

        /** Сплошной цвет смысла, пока поставщик отдаёт не ноль. */
        Button accent(IntSupplier color)
        {
            this.accent = color;

            return this;
        }

        /** Рамка цветом без заливки; {@code tintText} — подпись и значок тем же цветом. */
        Button edge(int color, boolean tintText)
        {
            return this.edge(() -> color, tintText);
        }

        Button edge(IntSupplier color, boolean tintText)
        {
            this.edge = color;
            this.tintText = tintText;

            return this;
        }

        /** Рамка пунктиром (у кнопки с {@link #edge}), пока поставщик отдаёт истину. */
        Button dashed(BooleanSupplier when)
        {
            this.dashed = when;

            return this;
        }

        Button dashed()
        {
            return this.dashed(() -> true);
        }

        Button enabled(BooleanSupplier when)
        {
            this.enabled = when;

            return this;
        }

        Button selected(BooleanSupplier when)
        {
            this.selected = when;

            return this;
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            boolean on = this.enabled.getAsBoolean();
            boolean over = on && this.over(mouseX, mouseY);
            boolean chosen = this.selected.getAsBoolean();
            int accentColor = this.accent.getAsInt();
            int edgeColor = this.edge.getAsInt();
            int text;

            if (Style.vanilla())
            {
                // Ванильный стиль: кнопка Minecraft; выбранная — подсвечена с жёлтой надписью, цвет смысла — цвет надписи
                Vanilla.button(ctx, this.x, this.y, this.w, this.h, on, over || (on && chosen));
                text = ! on ? Style.V_GRAY : accentColor != 0 ? Style.color(accentColor)
                        : edgeColor != 0 && this.tintText ? Style.color(edgeColor)
                        : chosen ? Style.V_HOVER : Style.V_WHITE;
            }
            else if (accentColor != 0 && on)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        Ui.alpha(accentColor, over ? A_ACCENT_FILL_HOVER : A_ACCENT_FILL));
                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        over ? accentColor : Ui.alpha(accentColor, A_ACCENT_EDGE));
                text = accentColor;
            }
            else if (edgeColor != 0 && on)
            {
                int line = over ? edgeColor : Ui.alpha(edgeColor, A_ACCENT_EDGE);

                if (over)
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(edgeColor, A_EDGE_HOVER_FILL));
                }

                if (this.dashed.getAsBoolean())
                {
                    com.aell.layerlist.ui.kit.Button.dashedBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, line);
                }
                else
                {
                    Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, line);
                }

                text = this.tintText ? edgeColor : over ? Ui.C_TEXT : Ui.C_TEXT_2;
            }
            else if (chosen)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_ACCENT, A_SELECTED_FILL));
                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_ACCENT);
                text = Ui.C_TEXT;
            }
            else
            {
                if (over)
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_ROW_HOVER);
                }

                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        on ? Ui.C_DIVIDER : Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF));
                text = ! on ? Ui.C_TEXT_OFF : over ? Ui.C_TEXT : Ui.C_TEXT_2;
            }

            String caption = this.label.get();
            String[] glyph = this.icon.get();
            int iconW = glyph == null ? 0 : glyph[0].length();
            int gap = iconW > 0 && ! caption.isEmpty() ? Ui.PAD_IN : 0;
            String shown = Paint.fit(font, caption, this.w - Ui.BUTTON_PAD_X * 2 - iconW - gap);
            int contentW = iconW + gap + font.width(shown);
            int cx = this.x + Math.max(Ui.BUTTON_PAD_X, (this.w - contentW) / 2);

            if (glyph != null)
            {
                Paint.icon(ctx, glyph, cx, this.y + (this.h - glyph.length) / 2, text);
            }

            if (! shown.isEmpty())
            {
                Paint.midText(ctx, font, shown, cx + iconW + gap, this.y, this.h, text, false);
            }
        }

        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            if (! this.enabled.getAsBoolean())
            {
                return true;
            }

            Vanilla.click();
            this.action.accept(shift);

            return true;
        }
    }

    /**
     * Тумблер как в экране настроек: дорожка с бегунком и подпись справа; клик по всей строке.
     * Выключенный ({@link #enabled}) рисуется приглушённо и в положении «выкл.» — так, как он сейчас
     * действует, — клик не переключает, подсказка остаётся (почему выключен — в ней и в пометке рядом).
     */
    static final class Switch extends Control
    {
        private final Supplier<String> label;
        private final BooleanSupplier value;
        private final Runnable toggle;
        private BooleanSupplier enabled = () -> true;
        private boolean keepValue;

        Switch(Supplier<String> label, BooleanSupplier value, Runnable toggle)
        {
            this.label = label;
            this.value = value;
            this.toggle = toggle;
        }

        Switch enabled(BooleanSupplier when)
        {
            this.enabled = when;

            return this;
        }

        /** Выключенный тумблер остаётся в своём положении, только серый (закреплённое размещение: сетка чанков). */
        Switch keepValue()
        {
            this.keepValue = true;

            return this;
        }

        boolean isEnabled()
        {
            return this.enabled.getAsBoolean();
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            boolean active = this.enabled.getAsBoolean();
            boolean on = (active || this.keepValue) && this.value.getAsBoolean();
            boolean over = active && this.over(mouseX, mouseY);
            int base = ! active ? Ui.C_TEXT_OFF : on ? Ui.C_ON : Ui.C_OFF;
            int sy = this.y + (this.h - Ui.SWITCH_H) / 2;

            if (Style.vanilla())
            {
                // Ванильный стиль: галочка Minecraft вместо тумблера, подпись справа белая
                Vanilla.checkbox(ctx, this.x, this.y, Ui.SWITCH_W, this.h, on, over);

                int vx = this.x + Ui.SWITCH_W + Ui.PAD_REL;

                Paint.midText(ctx, font, Paint.fit(font, this.label.get(), this.x + this.w - vx), vx, this.y,
                        this.h, active ? Style.V_WHITE : Style.V_GRAY, false);

                return;
            }

            if (over)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_ROW, Ui.C_ROW_HOVER);
            }

            Paint.round(ctx, this.x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, Ui.alpha(base, Ui.A_TRACK_OFF));
            Paint.roundBorder(ctx, this.x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, base);
            Paint.round(ctx, on ? this.x + Ui.SWITCH_W - Ui.SWITCH_KNOB : this.x, sy, Ui.SWITCH_KNOB, Ui.SWITCH_H,
                    Ui.R_PILL, base);

            int labelX = this.x + Ui.SWITCH_W + Ui.PAD_REL;

            Paint.midText(ctx, font, Paint.fit(font, this.label.get(), this.x + this.w - labelX), labelX, this.y,
                    this.h, ! active ? Ui.C_TEXT_OFF : over ? Ui.C_TEXT : Ui.C_TEXT_2, false);
        }

        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            if (this.enabled.getAsBoolean())
            {
                Vanilla.click();
                this.toggle.run();
            }

            return true;
        }
    }

    /**
     * Приглушённая пометка под элементом (что делает тумблер, почему он выключен): текст с переносом,
     * не больше {@link #MAX_LINES} строк. Не нажимается.
     */
    static final class Note extends Control
    {
        static final int MAX_LINES = 3;

        private final Supplier<String> text;
        private final IntSupplier color;

        Note(Supplier<String> text, IntSupplier color)
        {
            this.text = text;
            this.color = color;
        }

        /** Высота под текст при ширине {@code width}: строк по переносу, не больше {@link #MAX_LINES}. */
        int height(Font font, int width)
        {
            String value = this.text.get();

            return value.isEmpty() ? 0 : Math.min(MAX_LINES, font.split(FormattedText.of(value), width).size()) * Ui.TIP_LINE;
        }

        String text()
        {
            return this.text.get();
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            List<FormattedCharSequence> lines = font.split(FormattedText.of(this.text.get()), this.w);
            int room = Math.min(MAX_LINES, this.h / Ui.TIP_LINE);

            for (int i = 0; i < lines.size() && i < room; ++i)
            {
                Paint.text(ctx, font, lines.get(i), this.x, this.y + i * Ui.TIP_LINE + 1, this.color.getAsInt(), false);
            }
        }

        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            return true;
        }
    }

    /**
     * Поле целого числа с меткой слева («X»). Клик — ввод с клавиатуры (цифры и минус), Enter —
     * применить, Esc — вернуть как было, клик мимо — применить; колесо — ±1, с Shift — ±10.
     *
     * <p>Пока идёт ввод, поле держит ввод текста SDL ({@link TextFocus}): в 26.3 без него цифры не
     * приходят вовсе. Черновик IME (цифры с полноширинной раскладки) — плашкой под полем.
     */
    static final class NumberField extends Control implements TextFocus.Target
    {
        private static final int MAX_DIGITS = 9;
        private static final int FULLWIDTH_MINUS = 0xFF0D;

        private String label;
        private final IntSupplier value;
        private final IntConsumer commit;
        private int labelColor = Ui.C_TEXT_3;
        private BooleanSupplier enabled = () -> true;
        private boolean editing;
        private String text = "";
        private boolean fresh;
        private long errorUntil;
        private @Nullable IMEPreeditOverlay preedit;

        NumberField(String label, IntSupplier value, IntConsumer commit)
        {
            this.label = label;
            this.value = value;
            this.commit = commit;
        }

        /** Сменить подпись (режим слоёв: «Слой», «Ниже», «Выше», «От»). */
        void label(String text)
        {
            this.label = text;
        }

        /** Цвет подписи (оси X/Y/Z — красный, зелёный, синий). */
        NumberField labelColor(int color)
        {
            this.labelColor = color;
            return this;
        }

        /** Поле не правится (размещение или ось закреплены): серое, клик ввода не начинает. */
        NumberField enabled(BooleanSupplier when)
        {
            this.enabled = when;
            return this;
        }

        boolean isEnabled()
        {
            return this.enabled.getAsBoolean();
        }

        boolean editing()
        {
            return this.editing;
        }

        void begin()
        {
            if (! this.isEnabled())
            {
                return;
            }

            this.editing = true;
            this.text = Integer.toString(this.value.getAsInt());
            // Первый набранный символ заменяет число целиком — как выделенный текст
            this.fresh = true;
            TextFocus.take(this);
        }

        void cancel()
        {
            this.stopEditing();
        }

        private void stopEditing()
        {
            this.editing = false;
            this.preedit = null;
            TextFocus.release(this);
        }

        @Override
        public void preedit(@Nullable PreeditEvent event)
        {
            this.preedit = this.editing ? TextFocus.overlay(event, this.h) : null;
        }

        /** Применить набранное; неверное число — красная вспышка рамки, поле остаётся в вводе. */
        boolean finish()
        {
            if (! this.editing)
            {
                return true;
            }

            try
            {
                int parsed = Integer.parseInt(this.text.trim());
                this.stopEditing();

                if (parsed != this.value.getAsInt())
                {
                    this.commit.accept(parsed);
                }

                return true;
            }
            catch (NumberFormatException e)
            {
                this.errorUntil = Util.getMillis() + Ui.BLINK_MS * 2;

                return false;
            }
        }

        /** Текст поля: в вводе — набранное, иначе — текущее число. */
        String text()
        {
            return this.editing ? this.text : Integer.toString(this.value.getAsInt());
        }

        /** Вставка из буфера: число целиком заменяет набранное и сразу применяется. */
        void paste(String number)
        {
            if (! this.editing)
            {
                this.begin();
            }

            this.text = number;
            this.finish();
        }

        void type(int codepoint)
        {
            // Любая десятичная цифра Unicode: IME в полноширинном режиме подтверждает «１２», а не «12»
            int digit = Character.digit(codepoint, 10);
            boolean minus = codepoint == '-' || codepoint == FULLWIDTH_MINUS;

            if (digit < 0 && ! minus)
            {
                return;
            }

            if (this.fresh)
            {
                this.text = "";
                this.fresh = false;
            }

            if (minus)
            {
                this.text = this.text.startsWith("-") ? this.text.substring(1) : "-" + this.text;
            }
            else if (this.text.replace("-", "").length() < MAX_DIGITS)
            {
                this.text += (char) ('0' + digit);
            }
        }

        void backspace()
        {
            this.fresh = false;

            if (! this.text.isEmpty())
            {
                this.text = this.text.substring(0, this.text.length() - 1);
            }
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            boolean on = this.isEnabled();
            boolean over = on && this.over(mouseX, mouseY);
            boolean error = Util.getMillis() < this.errorUntil;
            int edge = ! on ? Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF)
                    : error ? Ui.C_DANGER : this.editing ? Ui.C_ACCENT : over ? Ui.C_PANEL_EDGE : Ui.C_DIVIDER;

            if (Style.vanilla())
            {
                // Ванильный стиль: поле ввода Minecraft; неверное число — красная рамка поверх
                Vanilla.field(ctx, this.x, this.y, this.w, this.h, on && (this.editing || over));

                if (error)
                {
                    Vanilla.outline(ctx, this.x, this.y, this.w, this.h, Style.V_RED);
                }
            }
            else
            {
                if (over && ! this.editing)
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_ROW_HOVER);
                }

                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, edge);
            }

            Paint.midText(ctx, font, this.label, this.x + Ui.PAD_IN, this.y, this.h,
                    on ? this.labelColor : Ui.alpha(this.labelColor, Ui.A_DISABLED), true);

            boolean caret = this.editing && (Util.getMillis() / Ui.CARET_MS) % 2 == 0;
            String shown = this.editing ? this.text + (caret ? "_" : " ") : Integer.toString(this.value.getAsInt());
            int room = this.w - Ui.PAD_IN * 3 - font.width(this.label);

            Paint.midTextRight(ctx, font, Paint.fit(font, shown, room), this.x + this.w - Ui.PAD_IN, this.y, this.h,
                    ! on ? (Style.vanilla() ? Style.V_GRAY : Ui.C_TEXT_OFF)
                            : Style.vanilla() ? Style.V_WHITE : this.editing ? Ui.C_TEXT : Ui.C_TEXT_2, false);

            if (this.editing)
            {
                // Курсор «_» — последний знак у правого края; ввод текста берём каждый кадр, потому что
                // ваниль снимает его при любой смене экрана
                int caretX = this.x + this.w - Ui.PAD_IN - font.width("_");
                TextFocus.take(this);

                if (this.preedit != null)
                {
                    TextFocus.showPreedit(ctx, this.preedit, caretX, this.y);
                }
                else
                {
                    TextFocus.caret(this, caretX, this.y, this.y + this.h);
                }
            }
        }

        @Override
        boolean scroll(double amount, boolean shift)
        {
            if (this.editing || amount == 0.0)
            {
                return true;
            }

            int step = shift ? Ui.STEP_FAST : 1;
            this.commit.accept(this.value.getAsInt() + (amount > 0.0 ? step : -step));

            return true;
        }
    }

    /**
     * Кнопка с автоповтором («−»/«+» у полей X/Y/Z): срабатывает при нажатии, а пока её держат —
     * после {@link #DELAY_MS} снова и снова, всё чаще: от {@link #START_PER_S} до {@link #END_PER_S}
     * раз в секунду за {@link #RAMP_MS}. Сама ничего не двигает — экран спрашивает {@link #due}, сколько
     * срабатываний набежало с прошлого раза, и сам решает, что с ними делать (серия — один шаг истории).
     */
    static final class HoldButton extends Control
    {
        static final long DELAY_MS = 350L;
        static final double START_PER_S = 8.0;
        static final double END_PER_S = 25.0;
        static final long RAMP_MS = 1500L;

        private final String[] icon;
        private BooleanSupplier enabled = () -> true;
        private int edge;
        private boolean held;
        private long heldSince;
        private long fired;

        HoldButton(String[] icon)
        {
            this.icon = icon;
        }

        HoldButton enabled(BooleanSupplier when)
        {
            this.enabled = when;

            return this;
        }

        /** Тонкая рамка цветом смысла (цвет оси у «−»/«+» под полем); выключенная кнопка остаётся серой. */
        HoldButton edge(int color)
        {
            this.edge = color;

            return this;
        }

        boolean isEnabled()
        {
            return this.enabled.getAsBoolean();
        }

        boolean held()
        {
            return this.held;
        }

        /** Нажали: первое срабатывание — сразу. */
        void press(long now)
        {
            this.held = true;
            this.heldSince = now;
            this.fired = 1;
        }

        void release()
        {
            this.held = false;
        }

        /** Сколько новых срабатываний набежало к {@code now} (с последнего вызова). */
        int due(long now)
        {
            if (! this.held)
            {
                return 0;
            }

            long total = 1 + repeats(now - this.heldSince);
            long fresh = total - this.fired;
            this.fired = total;

            return (int) Math.max(0L, fresh);
        }

        /**
         * Повторов за {@code ms} мс удержания: первый — ровно через {@link #DELAY_MS}, дальше частота
         * растёт линейно от {@link #START_PER_S} до {@link #END_PER_S} за {@link #RAMP_MS} и держится.
         */
        static long repeats(long ms)
        {
            if (ms < DELAY_MS)
            {
                return 0;
            }

            double t = (ms - DELAY_MS) / 1000.0;
            double ramp = RAMP_MS / 1000.0;
            double grow = (END_PER_S - START_PER_S) / ramp;
            double n = t <= ramp
                    ? START_PER_S * t + grow * t * t * 0.5
                    : START_PER_S * ramp + grow * ramp * ramp * 0.5 + END_PER_S * (t - ramp);

            return 1 + (long) Math.floor(n);
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            boolean on = this.enabled.getAsBoolean();
            boolean over = on && this.over(mouseX, mouseY);

            if (Style.vanilla())
            {
                // Ванильный стиль: кнопка Minecraft, значок поверх
                Vanilla.button(ctx, this.x, this.y, this.w, this.h, on, over || (this.held && on));
                Paint.icon(ctx, this.icon, this.x + (this.w - this.icon[0].length()) / 2,
                        this.y + (this.h - this.icon.length) / 2, on ? Style.V_WHITE : Style.V_GRAY);

                return;
            }

            if (this.held && on)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_ACCENT, A_SELECTED_FILL));
                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_ACCENT);
            }
            else
            {
                boolean tinted = on && this.edge != 0;

                if (over)
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                            tinted ? Ui.alpha(this.edge, A_EDGE_HOVER) : Ui.C_ROW_HOVER);
                }

                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        tinted ? Ui.alpha(this.edge, over ? 1.0F : A_ACCENT_EDGE)
                                : on ? Ui.C_DIVIDER : Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF));
            }

            int color = ! on ? Ui.C_TEXT_OFF : this.held || over ? Ui.C_TEXT : Ui.C_TEXT_2;
            int iconW = this.icon[0].length();

            Paint.icon(ctx, this.icon, this.x + (this.w - iconW) / 2, this.y + (this.h - this.icon.length) / 2, color);
        }

        /** Нажатие ловит экран (ему нужно и отпускание); сюда клик доходит, только если не поймал. */
        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            return true;
        }
    }

    /**
     * Выбор одного из нескольких значений по кругу: «‹ значение ›». Клик по левому шеврону —
     * предыдущее, по остальной кнопке — следующее; колесо — туда-сюда. Так помещается в половину
     * ряда, где ряд сегментов с подписями не влез бы.
     */
    static final class Cycle extends Control
    {
        private final Supplier<String> label;
        private final IntConsumer step;
        private BooleanSupplier solid = () -> false;
        private int solidColor;

        Cycle(Supplier<String> label, IntConsumer step)
        {
            this.label = label;
            this.step = step;
        }

        /** Сплошной цвет смысла, пока {@code when} истинно (вид камеры не обычный): заливка, рамка и надпись. */
        Cycle solid(BooleanSupplier when, int color)
        {
            this.solid = when;
            this.solidColor = color;

            return this;
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            boolean over = this.over(mouseX, mouseY);
            boolean back = over && this.backZone(mouseX);
            boolean lit = this.solid.getAsBoolean();

            if (Style.vanilla())
            {
                // Ванильный стиль: закрытый выбор — кнопка Minecraft с шевронами и белой надписью
                Vanilla.button(ctx, this.x, this.y, this.w, this.h, true, over);
            }
            else if (lit)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        Ui.alpha(this.solidColor, over ? A_ACCENT_FILL_HOVER : A_ACCENT_FILL));
                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                        over ? this.solidColor : Ui.alpha(this.solidColor, A_ACCENT_EDGE));
            }
            else
            {
                if (over)
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_ROW_HOVER);
                }

                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_DIVIDER);
            }

            int chevronW = Paint.CHEVRON_LEFT[0].length();
            int chevronY = this.y + (this.h - Paint.CHEVRON_LEFT.length) / 2;

            boolean vanilla = Style.vanilla();

            Paint.icon(ctx, Paint.CHEVRON_LEFT, this.x + Ui.PAD_IN, chevronY,
                    vanilla ? (back ? Style.V_HOVER : Style.V_WHITE) : back ? Ui.C_TEXT : Ui.C_TEXT_3);
            Paint.icon(ctx, Paint.CHEVRON_RIGHT, this.x + this.w - Ui.PAD_IN - chevronW, chevronY,
                    vanilla ? (over && ! back ? Style.V_HOVER : Style.V_WHITE) : over && ! back ? Ui.C_TEXT : Ui.C_TEXT_3);

            int room = this.w - (Ui.PAD_IN + chevronW + Ui.PAD_IN) * 2;
            String shown = Paint.fit(font, this.label.get(), room);

            Paint.midText(ctx, font, shown, this.x + (this.w - font.width(shown)) / 2, this.y, this.h,
                    vanilla ? (lit ? Style.color(this.solidColor) : Style.V_WHITE)
                            : lit ? this.solidColor : over ? Ui.C_TEXT : Ui.C_TEXT_2, false);
        }

        /** Левый шеврон с полем вокруг: шаг назад. */
        private boolean backZone(double mouseX)
        {
            return mouseX < this.x + Ui.PAD_IN * 2 + Paint.CHEVRON_LEFT[0].length();
        }

        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            Vanilla.click();
            this.step.accept(this.backZone(mouseX) || shift ? -1 : 1);

            return true;
        }

        @Override
        boolean scroll(double amount, boolean shift)
        {
            if (amount != 0.0)
            {
                this.step.accept(amount > 0.0 ? -1 : 1);
            }

            return true;
        }
    }

    /** Ряд равных ячеек, одна выбрана (шаг 1/5/16/64). */
    static final class Segmented extends Control
    {
        private final String[] labels;
        private final IntSupplier selected;
        private final IntConsumer select;

        Segmented(String[] labels, IntSupplier selected, IntConsumer select)
        {
            this.labels = labels;
            this.selected = selected;
            this.select = select;
        }

        @Override
        void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
        {
            if (! this.visible)
            {
                return;
            }

            int chosen = this.selected.getAsInt();

            for (int i = 0; i < this.labels.length; ++i)
            {
                int cx = this.cellX(i);
                int cw = this.cellX(i + 1) - cx - (i < this.labels.length - 1 ? Ui.PAD_MICRO : 0);
                boolean over = Paint.hit(mouseX, mouseY, cx, this.y, cw, this.h);

                if (Style.vanilla())
                {
                    // Ванильный стиль: ряд кнопок Minecraft; выбранная подсвечена, надпись жёлтая
                    Vanilla.button(ctx, cx, this.y, cw, this.h, true, i == chosen || over);

                    String label = Paint.fit(font, this.labels[i], cw - Ui.PAD_MICRO * 2);

                    Paint.midText(ctx, font, label, cx + (cw - font.width(label)) / 2, this.y, this.h,
                            i == chosen ? Style.V_HOVER : Style.V_WHITE, false);
                    continue;
                }

                if (i == chosen)
                {
                    Paint.round(ctx, cx, this.y, cw, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_ACCENT, A_SELECTED_FILL));
                    Paint.roundBorder(ctx, cx, this.y, cw, this.h, Ui.R_CTRL, Ui.C_ACCENT);
                }
                else
                {
                    if (over)
                    {
                        Paint.round(ctx, cx, this.y, cw, this.h, Ui.R_CTRL, Ui.C_ROW_HOVER);
                    }

                    Paint.roundBorder(ctx, cx, this.y, cw, this.h, Ui.R_CTRL, Ui.C_DIVIDER);
                }

                String text = Paint.fit(font, this.labels[i], cw - Ui.PAD_MICRO * 2);

                Paint.midText(ctx, font, text, cx + (cw - font.width(text)) / 2, this.y, this.h,
                        i == chosen || over ? Ui.C_TEXT : Ui.C_TEXT_2, false);
            }
        }

        @Override
        boolean click(double mouseX, double mouseY, boolean shift)
        {
            int px = (int) Math.floor(mouseX);

            for (int i = 0; i < this.labels.length; ++i)
            {
                if (px >= this.cellX(i) && px < this.cellX(i + 1))
                {
                    Vanilla.click();
                    this.select.accept(i);

                    return true;
                }
            }

            return true;
        }

        private int cellX(int index)
        {
            return this.x + this.w * index / this.labels.length;
        }
    }
}
