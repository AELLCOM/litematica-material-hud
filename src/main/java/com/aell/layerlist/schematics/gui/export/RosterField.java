package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * «Кто собирает» — ростер ников для выпадающего списка таблицы: под заголовком — зачем он (ники
 * станут списком столбца «Кто собирает», по ним считается «Кто сколько собрал»), чипы с крестиком,
 * поле «+ ник», «+ игроки онлайн» (выбор из списка игроков сервера — {@link PlayerPicker}),
 * «Очистить» (с «Вернуть» в строке под полем — с 0.0.27 не тостом: пока открыто окно выгрузки, тостов
 * нет). Хранится в {@link SchematicsState}
 * ({@code config/layerlist_schematics.json}) и читается оттуда каждый кадр — окно и файл не расходятся.
 *
 * <p>Ник с клавиатуры — как у Minecraft: латиница, цифры и «_», от 3 до 16 знаков; чужие знаки
 * поле не принимает и коротко подсказывает почему. Ники из списка игроков — {@link OnlinePlayers}.
 */
@Environment(EnvType.CLIENT)
final class RosterField extends Widget
{
    static final int NICK_MIN = 3;
    static final int NICK_MAX = 16;

    private static final Pattern TYPED = Pattern.compile("[A-Za-z0-9_]{" + NICK_MIN + "," + NICK_MAX + "}");

    private static final int CHIP_H = Ui.HEAD_BTN_H;
    private static final long MESSAGE_MS = 5000L;
    private static final long REJECT_MS = 3000L;

    private final Layers layers;
    private final Button online;
    private final Button clear;
    private final SectionTitle title;
    private final TextInput input;
    private final Button add;
    private final List<Chip> chips = new ArrayList<>();
    private final StatusLine noteLine = new StatusLine(this::note);

    private @Nullable StatusLine.Message message;
    private long messageAt;
    private long rejectedAt;
    private boolean rejected;

    /** Чип ника на экране: прямоугольник и зона крестика. */
    private record Chip(String nick, int x, int y, int w, int crossX) {}

    RosterField(Layers layers)
    {
        this.layers = layers;
        this.online = new Button(DialogText.t("roster.online"), Button.Style.GHOST, this::pickOnline)
                .tooltip(DialogText.t("roster.online.tip"));
        this.clear = new Button(DialogText.t("roster.clear"), Button.Style.GHOST, this::clearAll)
                .tooltip(DialogText.t("roster.clear.tip"));
        this.title = new SectionTitle(this::titleText, this.online, this.clear).tooltip(DialogText.t("roster.tip"));
        this.input = new TextInput().hint(DialogText.t("roster.hint")).maxLength(NICK_MAX).filter(this::accept)
                .tooltip(DialogText.f("roster.input.tip", NICK_MIN, NICK_MAX));
        this.input.onSubmit(value -> this.addTyped());
        this.add = new Button(DialogText.t("roster.add"), Button.Style.SECONDARY, this::addTyped);
    }

    private static List<String> roster()
    {
        return SchematicsState.get().roster();
    }

    private String titleText()
    {
        int count = roster().size();

        return count == 0 ? DialogText.t("roster") : DialogText.f("roster.count", count);
    }

    // ===== Раскладка =====

    /** Строки пояснения под заголовком (приглушённые, с переносом). */
    private static List<String> about(int width)
    {
        return Kit.wrap(Kit.font(), DialogText.t("roster.about"), width);
    }

    private static int aboutHeight(int width)
    {
        return about(width).size() * Ui.TIP_LINE;
    }

    int height(int width)
    {
        int h = SectionTitle.HEIGHT + Ui.PAD_MICRO + aboutHeight(width) + Ui.PAD_IN + this.chipsHeight(width) + Ui.PAD_IN
                + Ui.CTRL_H;
        int noteH = this.noteLine.height(width);

        return noteH > 0 ? h + Ui.PAD_MICRO + noteH : h;
    }

    private int chipsHeight(int width)
    {
        List<String> nicks = roster();

        if (nicks.isEmpty())
        {
            return Ui.TIP_LINE;
        }

        int lines = this.placeChips(nicks, width, 0, 0, null);

        return lines * CHIP_H + Math.max(0, lines - 1) * Ui.PAD_IN;
    }

    private static int chipWidth(Font font, String nick, int width)
    {
        int full = Ui.PAD_REL + font.width(nick) + Ui.PAD_IN + Glyphs.width(Glyphs.CROSS) + Ui.PAD_REL;

        return Math.min(full, Math.max(0, width));
    }

    /** Чипы по строкам; {@code out} — куда сложить прямоугольники. Возвращает число строк. */
    private int placeChips(List<String> nicks, int width, int x, int y, @Nullable List<Chip> out)
    {
        Font font = Kit.font();
        int cx = 0;
        int line = 0;

        for (String nick : nicks)
        {
            int cw = chipWidth(font, nick, width);

            if (cx > 0 && cx + cw > width)
            {
                ++line;
                cx = 0;
            }

            if (out != null)
            {
                int chipX = x + cx;
                out.add(new Chip(nick, chipX, y + line * (CHIP_H + Ui.PAD_IN), cw,
                        chipX + cw - Ui.PAD_REL - Glyphs.width(Glyphs.CROSS)));
            }

            cx += cw + Ui.PAD_IN;
        }

        return nicks.isEmpty() ? 0 : line + 1;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        int cy = y;
        this.title.setBounds(x, cy, w, SectionTitle.HEIGHT);
        cy += SectionTitle.HEIGHT + Ui.PAD_MICRO + aboutHeight(w) + Ui.PAD_IN;

        this.chips.clear();
        this.placeChips(roster(), w, x, cy, this.chips);
        cy += this.chipsHeight(w) + Ui.PAD_IN;

        int addW = Math.min(this.add.preferredWidth(Kit.font()), Math.max(0, w / 3));
        this.input.setBounds(x, cy, Math.max(0, w - addW - Ui.PAD_IN), Ui.CTRL_H);
        this.add.setBounds(x + w - addW, cy, addW, Ui.BUTTON_H);
        this.noteLine.setBounds(x, cy + Ui.CTRL_H + Ui.PAD_MICRO, w, this.noteLine.height(w));
    }

    // ===== Отрисовка =====

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        boolean connected = Minecraft.getInstance().getConnection() != null;

        this.online.setEnabled(connected);
        this.online.tooltip(connected ? DialogText.t("roster.online.tip") : DialogText.t("roster.offline"));
        this.clear.setEnabled(! roster().isEmpty());
        this.title.render(ctx, mouseX, mouseY);

        // Зачем вписывать ники — прямо под заголовком, чтобы не искать в подсказке
        int ay = this.title.y() + SectionTitle.HEIGHT + Ui.PAD_MICRO;

        for (String line : about(this.w))
        {
            Paint.text(ctx, font, line, this.x, ay + 1, Ui.C_TEXT_3, false);
            ay += Ui.TIP_LINE;
        }

        if (this.chips.isEmpty())
        {
            int top = ay + Ui.PAD_IN;
            String hint = Paint.fit(font, DialogText.t("roster.empty"), this.w);
            Paint.text(ctx, font, hint, this.x, top + 1, Ui.C_TEXT_3, false);
        }

        for (Chip chip : this.chips)
        {
            this.drawChip(ctx, font, chip, mouseX, mouseY);
        }

        this.input.render(ctx, mouseX, mouseY);
        this.add.render(ctx, mouseX, mouseY);
        this.noteLine.render(ctx, mouseX, mouseY);
    }

    private void drawChip(GuiContext ctx, Font font, Chip chip, int mouseX, int mouseY)
    {
        boolean overChip = Paint.hit(mouseX, mouseY, chip.x, chip.y, chip.w, CHIP_H);
        boolean overCross = overChip && mouseX >= chip.crossX - Ui.PAD_IN;
        int textRoom = Math.max(0, chip.crossX - Ui.PAD_IN - chip.x - Ui.PAD_REL);

        if (Style.vanilla())
        {
            // Ванильный стиль: ник — строка списка Minecraft, под курсором — в фокусе (белая рамка)
            Vanilla.selection(ctx, chip.x, chip.y, chip.w, CHIP_H, overChip);
        }
        else
        {
            Paint.round(ctx, chip.x, chip.y, chip.w, CHIP_H, Ui.R_PILL, overChip ? Ui.C_ROW_FOCUS : Ui.C_ROW_HOVER);
            Paint.roundBorder(ctx, chip.x, chip.y, chip.w, CHIP_H, Ui.R_PILL, overChip ? Ui.C_PANEL_EDGE : Ui.C_DIVIDER);
        }

        Paint.midText(ctx, font, Paint.fit(font, chip.nick, textRoom), chip.x + Ui.PAD_REL, chip.y, CHIP_H, Ui.C_TEXT, false);
        Glyphs.draw(ctx, Glyphs.CROSS, chip.crossX, chip.y + (CHIP_H - Glyphs.height(Glyphs.CROSS)) / 2,
                overCross ? Ui.C_DANGER : Ui.C_TEXT_3);

        if (overCross)
        {
            Tooltip.offer("export#roster#" + chip.nick, DialogText.f("roster.remove", chip.nick));
        }
        else if (overChip && font.width(chip.nick) > textRoom)
        {
            Tooltip.offer("export#roster#" + chip.nick, chip.nick);
        }
    }

    /**
     * Сообщение под полем: ответ на последнее действие, иначе — подсказка про отброшенный знак. «Очищено» с
     * «Вернуть» висит, пока ростер пуст (вернуть есть что), остальные гаснут за {@link #MESSAGE_MS}.
     */
    private @Nullable StatusLine.Message note()
    {
        long now = Util.getMillis();
        StatusLine.Message said = this.message;

        // Ростер снова набрали — прежний «Вернуть» не к месту и после нового «пусто» всплыть не должен
        if (said != null && ! said.actions().isEmpty() && ! roster().isEmpty())
        {
            this.message = null;
            said = null;
        }

        if (said != null && (! said.actions().isEmpty() || now - this.messageAt < MESSAGE_MS))
        {
            return said;
        }

        if (this.rejected && now - this.rejectedAt < REJECT_MS)
        {
            return new StatusLine.Message(DialogText.t("roster.bad_char"), Ui.C_WARN);
        }

        return null;
    }

    private void say(String text, int color)
    {
        this.message = new StatusLine.Message(text, color);
        this.messageAt = Util.getMillis();
    }

    // ===== Действия =====

    /** Фильтр поля: только знаки ника Minecraft; отброшенный — короткая подсказка. */
    private boolean accept(int codePoint)
    {
        boolean ok = codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_');

        if (! ok)
        {
            this.rejected = true;
            this.rejectedAt = Util.getMillis();
        }

        return ok;
    }

    private void addTyped()
    {
        String nick = this.input.text().strip();

        if (nick.isEmpty())
        {
            this.input.setFocused(true);
            return;
        }

        if (! TYPED.matcher(nick).matches())
        {
            this.say(DialogText.f("roster.bad_length", NICK_MIN, NICK_MAX), Ui.C_WARN);
            return;
        }

        Added result = addAll(List.of(nick));

        if (result.duplicate() != null)
        {
            this.say(DialogText.f("roster.duplicate", result.duplicate()), Ui.C_WARN);
        }
        else if (result.full())
        {
            this.say(DialogText.f("roster.full", SchematicsState.ROSTER_MAX), Ui.C_WARN);
        }
        else
        {
            this.message = null;
            this.input.setText("");
        }

        // Фокус остаётся в поле: ники обычно вводят подряд
        this.input.setFocused(true);
    }

    /** «+ игроки онлайн»: окно выбора поверх окна выгрузки (кого добавить — решает игрок). */
    private void pickOnline()
    {
        this.input.setFocused(false);

        if (PlayerPicker.open(this.layers) == null)
        {
            this.say(DialogText.t("roster.offline"), Ui.C_WARN);
        }
        else
        {
            this.message = null;
        }
    }

    private void clearAll()
    {
        List<String> before = List.copyOf(roster());

        if (before.isEmpty())
        {
            return;
        }

        SchematicsState.get().setRoster(List.of());
        // Ростер команды собирают долго — случайное «Очистить» должно отменяться
        this.message = new StatusLine.Message(DialogText.f("roster.cleared", before.size()), Ui.C_WARN,
                List.of(new Toasts.Action(DialogText.t("roster.undo"), () ->
                {
                    SchematicsState.get().setRoster(before);
                    this.message = null;
                })));
        this.messageAt = Util.getMillis();
    }

    /** Итог добавления: сколько добавлено, повтор (если добавляли один ник), упёрлись ли в предел. */
    record Added(int added, @Nullable String duplicate, boolean full) {}

    /**
     * Добавить в конец, без повторов (регистр не важен) и не больше {@link SchematicsState#ROSTER_MAX}.
     * Общее для поля «+ ник» и выбора игроков онлайн.
     */
    static Added addAll(List<String> nicks)
    {
        List<String> next = new ArrayList<>(roster());
        List<String> seen = new ArrayList<>();
        next.forEach(nick -> seen.add(nick.toLowerCase(Locale.ROOT)));

        int added = 0;
        String duplicate = null;
        boolean full = false;

        for (String nick : nicks)
        {
            String folded = nick.toLowerCase(Locale.ROOT);

            if (seen.contains(folded))
            {
                duplicate = nick;
                continue;
            }

            if (next.size() >= SchematicsState.ROSTER_MAX)
            {
                full = true;
                break;
            }

            seen.add(folded);
            next.add(nick);
            ++added;
        }

        if (added > 0)
        {
            SchematicsState.get().setRoster(next);
        }

        return new Added(added, nicks.size() == 1 ? duplicate : null, full);
    }

    // ===== Ввод =====

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.title.mouseClicked(click, doubleClick) || this.noteLine.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (Kit.left(click))
        {
            for (Chip chip : List.copyOf(this.chips))
            {
                if (Kit.leftHit(click, chip.crossX - Ui.PAD_IN, chip.y, chip.x + chip.w - (chip.crossX - Ui.PAD_IN), CHIP_H))
                {
                    Vanilla.click();
                    List<String> next = new ArrayList<>(roster());
                    next.remove(chip.nick);
                    SchematicsState.get().setRoster(next);
                    return true;
                }
            }
        }

        return this.input.mouseClicked(click, doubleClick) || this.add.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.input.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        this.title.mouseReleased(click);
        this.add.mouseReleased(click);

        return this.input.mouseReleased(click);
    }

    @Override
    public @Nullable TextInput textInput()
    {
        return this.input;
    }

    // ===== Харнесс (через ExportWindow.TestHooks) =====

    @Nullable String noteText()
    {
        StatusLine.Message note = this.noteLine.message();

        return note == null ? null : note.text();
    }

    List<String> noteActions()
    {
        StatusLine.Message note = this.noteLine.message();

        return note == null ? List.of() : note.actions().stream().map(Toasts.Action::label).toList();
    }

    int @Nullable [] noteActionBox(String label)
    {
        return this.noteLine.actionBox(label);
    }
}
