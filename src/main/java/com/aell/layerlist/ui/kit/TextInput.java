package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Поле ввода в виде поиска экрана настроек: карточка {@link Ui#R_CTRL} на {@link Ui#C_PANEL},
 * рамка {@link Ui#C_ACCENT} в фокусе, по желанию — лупа {@code Paint.SEARCH_ICON} цветом
 * {@link Ui#C_GOLD}. Своё, а не {@code GuiTextFieldGeneric} malilib: поля malilib рисуются после
 * содержимого экрана, и любой попап, нарисованный раньше них, оказался бы под полем.
 *
 * <p>Курсор, выделение (мышью, Shift+стрелки, двойной клик — всё), Ctrl+A/C/V/X, Ctrl+←/→ и
 * Ctrl+Backspace/Delete по словам, Home/End, Enter — {@link #onSubmit}. Esc поле не обрабатывает:
 * снимать фокус — дело экрана. Время мигания — {@link Util#getMillis()}.
 *
 * <p>В фокусе поле держит ввод текста SDL ({@link TextFocus}): без него 26.3 не шлёт букв вовсе.
 * Курсор сообщается системе как место окна кандидатов IME, черновик IME — плашкой под полем.
 */
@Environment(EnvType.CLIENT)
public final class TextInput extends Widget implements TextFocus.Target
{
    private String text = "";
    private int caret;
    private int anchor;
    private int scrollPx;
    private boolean centered;
    private boolean focused;
    private boolean dragging;
    private long blinkFrom;
    private @Nullable IMEPreeditOverlay preedit;

    private String hint = "";
    private int maxLength = 256;
    private boolean searchIcon;
    private boolean masked;
    private IntPredicate filter = c -> true;
    private @Nullable Consumer<String> onChange;
    private @Nullable Consumer<String> onSubmit;
    private @Nullable Runnable onFocus;
    private @Nullable String tooltip;

    public TextInput hint(String hint)
    {
        this.hint = hint;
        return this;
    }

    public TextInput maxLength(int max)
    {
        this.maxLength = Math.max(1, max);
        return this;
    }

    /** Текст по центру поля (короткие числа: «зон по X»); длиннее поля — как обычно, от левого края. */
    public TextInput centered(boolean value)
    {
        this.centered = value;
        return this;
    }

    public TextInput searchIcon(boolean value)
    {
        this.searchIcon = value;
        return this;
    }

    /** Секрет (ключ таблицы): без фокуса — звёздочки, копирование выключено. */
    public TextInput masked(boolean value)
    {
        this.masked = value;
        return this;
    }

    /** Какие символы принимать (кодовая точка); переводы строк не принимаются никогда. */
    public TextInput filter(IntPredicate filter)
    {
        this.filter = filter;
        return this;
    }

    public TextInput onChange(@Nullable Consumer<String> listener)
    {
        this.onChange = listener;
        return this;
    }

    public TextInput onSubmit(@Nullable Consumer<String> listener)
    {
        this.onSubmit = listener;
        return this;
    }

    public TextInput onFocus(@Nullable Runnable listener)
    {
        this.onFocus = listener;
        return this;
    }

    public TextInput tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    public String text()
    {
        return this.text;
    }

    /** Без {@code onChange}: синхронизация с внешним значением. */
    public void setText(String value)
    {
        String clean = this.clean(value == null ? "" : value);
        this.text = clean.length() > this.maxLength ? clean.substring(0, this.maxLength) : clean;
        this.caret = this.text.length();
        this.anchor = this.caret;
        this.scrollPx = 0;
    }

    public boolean focused()
    {
        return this.focused;
    }

    public void setFocused(boolean value)
    {
        if (value && ! this.focused && this.onFocus != null)
        {
            this.onFocus.run();
        }

        this.focused = value;
        this.dragging = false;
        this.blinkFrom = Util.getMillis();

        if (value)
        {
            TextFocus.take(this);
        }
        else
        {
            this.anchor = this.caret;
            this.preedit = null;
            TextFocus.release(this);
        }
    }

    @Override
    public void setVisible(boolean visible)
    {
        super.setVisible(visible);
        this.dropInputIfIdle();
    }

    @Override
    public void setEnabled(boolean enabled)
    {
        super.setEnabled(enabled);
        this.dropInputIfIdle();
    }

    /** Скрытое или выключенное поле букв не примет — держать для него ввод текста незачем. */
    private void dropInputIfIdle()
    {
        if (this.focused && (! this.visible || ! this.enabled))
        {
            this.preedit = null;
            TextFocus.release(this);
        }
    }

    @Override
    public void preedit(@Nullable PreeditEvent event)
    {
        this.preedit = this.focused ? TextFocus.overlay(event, this.h) : null;
    }

    public void selectAll()
    {
        this.anchor = 0;
        this.caret = this.text.length();
    }

    /** Вставить с позиции курсора, заменив выделение (буква, с которой список перевёл фокус в поиск). */
    public void insert(String value)
    {
        String clean = this.clean(value);
        int start = this.selStart();
        int end = this.selEnd();
        // Сколько знаков ещё влезет, если выделенное уйдёт
        int kept = this.text.length() - (end - start);
        int room = Math.max(0, this.maxLength - kept);

        if (room == 0 && ! clean.isEmpty())
        {
            return;
        }

        String piece = clean.length() > room ? clean.substring(0, room) : clean;
        this.text = new StringBuilder(this.text).replace(start, end, piece).toString();
        this.caret = start + piece.length();
        this.anchor = this.caret;
        this.changed();
    }

    /** Левый край выделения: курсор и якорь могут стоять в любом порядке. */
    private int selStart()
    {
        return this.caret < this.anchor ? this.caret : this.anchor;
    }

    /** Правый край выделения. */
    private int selEnd()
    {
        return this.caret < this.anchor ? this.anchor : this.caret;
    }

    private String clean(String value)
    {
        StringBuilder out = new StringBuilder();

        value.codePoints().forEach(c ->
        {
            if (c >= ' ' && c != 0x7F && this.filter.test(c))
            {
                out.appendCodePoint(c);
            }
        });

        return out.toString();
    }

    private void changed()
    {
        this.blinkFrom = Util.getMillis();

        if (this.onChange != null)
        {
            this.onChange.accept(this.text);
        }
    }

    private int textX()
    {
        return this.x + Ui.PAD_IN + (this.searchIcon ? Ui.SEARCH_ICON + Ui.PAD_IN : 0);
    }

    private int textRoom()
    {
        return Math.max(0, this.x + this.w - Ui.PAD_IN - this.textX());
    }

    /** Сдвиг текста при {@link #centered}: пока текст короче поля — половина свободного места. */
    private int centerOffset(Font font, int room)
    {
        if (! this.centered)
        {
            return 0;
        }

        String shown = this.shown();
        return Math.max(0, (room - font.width(shown.isEmpty() && ! this.focused ? this.hint : shown)) / 2);
    }

    private String shown()
    {
        return this.masked && ! this.focused ? "*".repeat(this.text.length()) : this.text;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0)
        {
            return;
        }

        Font font = Kit.font();
        boolean over = this.contains(mouseX, mouseY);

        boolean vanilla = Style.vanilla();
        // Ванильный вид: поле Minecraft, в фокусе с белой рамкой; текст белый, подсказка тёмно-серая
        if (vanilla)
        {
            Vanilla.field(ctx, this.x, this.y, this.w, this.h, this.focused);
        }
        else
        {
            Paint.card(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_PANEL,
                    this.focused ? Ui.C_ACCENT : over && this.enabled ? Ui.C_PANEL_EDGE : Ui.C_DIVIDER);
        }

        if (this.searchIcon)
        {
            Paint.icon(ctx, Paint.SEARCH_ICON, this.x + Ui.PAD_IN, this.y + (this.h - Ui.SEARCH_ICON) / 2, Ui.C_GOLD);
        }

        int room = this.textRoom();
        int textX = this.textX() + this.centerOffset(font, room);
        String shown = this.shown();

        if (shown.isEmpty() && ! this.focused)
        {
            Paint.midText(ctx, font, Paint.fit(font, this.hint, room), textX, this.y, this.h,
                    vanilla ? Style.V_DARK_GRAY : Ui.C_TEXT_3, false);
        }
        else
        {
            this.keepCaretVisible(font, room);

            ctx.enableScissor(this.textX(), this.y, this.textX() + room, this.y + this.h);

            int base = textX - this.scrollPx;
            int from = this.selStart();
            int to = this.selEnd();

            if (from != to && this.focused)
            {
                int sx = base + font.width(shown.substring(0, from));
                int ex = base + font.width(shown.substring(0, to));
                Paint.rect(ctx, sx, this.y + Ui.PAD_MICRO, ex - sx, this.h - Ui.PAD_MICRO * 2,
                        Ui.alpha(Ui.C_ACCENT, Ui.A_PREVIEW));
            }

            Paint.midText(ctx, font, shown, base, this.y, this.h,
                    vanilla ? this.enabled ? Style.V_WHITE : Style.V_GRAY : this.enabled ? Ui.C_TEXT : Ui.C_TEXT_OFF, false);

            boolean blinkOn = ((Util.getMillis() - this.blinkFrom) / Ui.CARET_MS) % 2L == 0L;
            int cx = base + font.width(shown.substring(0, Math.min(this.caret, shown.length())));

            if (this.focused && blinkOn)
            {
                Paint.rect(ctx, cx, this.y + Ui.PAD_MICRO + 1, 1, this.h - Ui.PAD_MICRO * 2 - 2,
                        vanilla ? Style.V_WHITE : Ui.C_TEXT);
            }

            ctx.disableScissor();

            if (this.focused && this.enabled)
            {
                this.reportCaret(ctx, Ui.clamp(cx, this.textX(), this.textX() + room));
            }
        }

        if (over && this.enabled)
        {
            ctx.requestCursor(CursorTypes.IBEAM);
            Tooltip.offer(this, this.tooltip);
        }
    }

    /**
     * Кадр поля в фокусе: снова взять ввод текста (ваниль снимает его при смене экрана, а фокус
     * поля остаётся) и показать системе курсор — или плашку черновика IME, если идёт набор.
     */
    private void reportCaret(GuiContext ctx, int caretX)
    {
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

    private void keepCaretVisible(Font font, int room)
    {
        String shown = this.shown();
        int caretPx = font.width(shown.substring(0, Math.min(this.caret, shown.length())));
        int total = font.width(shown);

        if (caretPx - this.scrollPx > room - 1)
        {
            this.scrollPx = caretPx - room + 1;
        }

        if (caretPx < this.scrollPx)
        {
            this.scrollPx = caretPx;
        }

        this.scrollPx = Math.max(0, Math.min(this.scrollPx, Math.max(0, total - room + 1)));
    }

    private int indexAt(double mouseX)
    {
        Font font = Kit.font();
        String shown = this.shown();
        double local = mouseX - this.textX() - this.centerOffset(font, this.textRoom()) + this.scrollPx;

        if (local <= 0)
        {
            return 0;
        }

        int width = 0;

        for (int i = 0; i < shown.length(); ++i)
        {
            int next = width + font.width(String.valueOf(shown.charAt(i)));

            // Ближе к левой половине буквы — курсор перед ней
            if (local < (width + next) / 2.0)
            {
                return i;
            }

            width = next;
        }

        return shown.length();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()) || ! this.enabled)
        {
            return false;
        }

        if (! Kit.left(click))
        {
            this.setFocused(true);
            return true;
        }

        this.setFocused(true);

        if (doubleClick)
        {
            this.selectAll();
            return true;
        }

        this.caret = this.indexAt(click.x());

        if (! click.hasShiftDown())
        {
            this.anchor = this.caret;
        }

        this.dragging = true;
        this.blinkFrom = Util.getMillis();

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (! this.dragging || ! this.focused)
        {
            return false;
        }

        this.caret = this.indexAt(click.x());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        boolean was = this.dragging;
        this.dragging = false;
        return was;
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        if (! this.focused || ! this.enabled)
        {
            return false;
        }

        int code = key.key();
        boolean shift = key.hasShiftDown();
        boolean word = key.hasControlDownWithQuirk();

        if (key.isSelectAll())
        {
            this.selectAll();
            return true;
        }

        if (key.isCopy() || key.isCut())
        {
            String selected = this.selected();

            if (! selected.isEmpty() && ! this.masked)
            {
                Minecraft.getInstance().keyboardHandler.setClipboard(selected);
            }

            if (key.isCut() && ! selected.isEmpty())
            {
                this.insert("");
            }

            return true;
        }

        if (key.isPaste())
        {
            this.insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace('\n', ' ').replace('\r', ' '));
            return true;
        }

        if (code == InputConstants.KEY_LEFT || code == InputConstants.KEY_RIGHT)
        {
            int dir = code == InputConstants.KEY_LEFT ? -1 : 1;
            int from = this.selStart();
            int to = this.selEnd();

            if (! shift && from != to && ! word)
            {
                this.caret = dir < 0 ? from : to;
            }
            else
            {
                this.caret = word ? this.wordEdge(this.caret, dir) : Ui.clamp(this.caret + dir, 0, this.text.length());
            }

            if (! shift)
            {
                this.anchor = this.caret;
            }

            this.blinkFrom = Util.getMillis();
            return true;
        }

        if (code == InputConstants.KEY_HOME || code == InputConstants.KEY_END)
        {
            this.caret = code == InputConstants.KEY_HOME ? 0 : this.text.length();

            if (! shift)
            {
                this.anchor = this.caret;
            }

            this.blinkFrom = Util.getMillis();
            return true;
        }

        if (code == InputConstants.KEY_BACKSPACE || code == InputConstants.KEY_DELETE)
        {
            if (this.caret == this.anchor)
            {
                int dir = code == InputConstants.KEY_BACKSPACE ? -1 : 1;
                int edge = word ? this.wordEdge(this.caret, dir) : Ui.clamp(this.caret + dir, 0, this.text.length());
                this.anchor = edge;
            }

            if (this.caret != this.anchor)
            {
                this.insert("");
            }

            return true;
        }

        if (code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER)
        {
            if (this.onSubmit != null)
            {
                this.onSubmit.accept(this.text);
            }

            return true;
        }

        return false;
    }

    private String selected()
    {
        return this.text.substring(this.selStart(), this.selEnd());
    }

    /**
     * Граница слова от {@code from} в сторону {@code dir}. Назад — сначала пробелы перед курсором,
     * потом само слово; вперёд — остаток слова, потом пробелы за ним (Ctrl+стрелки, Ctrl+Backspace).
     */
    private int wordEdge(int from, int dir)
    {
        int step = dir < 0 ? -1 : 1;
        boolean spacesFirst = step < 0;
        return this.skip(this.skip(from, step, spacesFirst), step, ! spacesFirst);
    }

    /** Двигает позицию на {@code step}, пока соседний символ в эту сторону — пробел ({@code spaces}) или не пробел. */
    private int skip(int pos, int step, boolean spaces)
    {
        int i = pos;

        for (int at = step < 0 ? i - 1 : i;
             at >= 0 && at < this.text.length() && (this.text.charAt(at) == ' ') == spaces;
             at += step)
        {
            i += step;
        }

        return i;
    }

    @Override
    public @Nullable TextInput textInput()
    {
        return this;
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        if (! this.focused || ! this.enabled)
        {
            return false;
        }

        if (event.isAllowedChatCharacter())
        {
            this.insert(event.codepointAsString());
        }

        return true;
    }
}
