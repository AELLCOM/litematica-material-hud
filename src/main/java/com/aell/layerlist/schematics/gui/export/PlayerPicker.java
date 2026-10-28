package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Выбор игроков онлайн для «Кто собирает» — окно над окном выгрузки. Те же чипы ников, что в
 * ростере, только с «+» вместо «×»: клик по чипу добавляет игрока, после этого чип приглушён и с
 * галочкой («уже в списке»). Поле-фильтр сужает список, «Добавить всех» берёт всех видимых (после
 * фильтра), «Готово» и Esc возвращают к ростеру.
 *
 * <p>Раньше «+ игроки онлайн» сразу вписывал весь сервер — на большом сервере это 50+ ников, из
 * которых собирают трое. Список игроков перечитывается раз в секунду: кто-то зашёл или вышел, пока
 * окно открыто. Своя прокрутка у чипов, а не у окна: поле фильтра остаётся на месте.
 */
@Environment(EnvType.CLIENT)
public final class PlayerPicker extends Modal
{
    private static final int WIDTH = 360;
    private static final int CHIP_H = Ui.HEAD_BTN_H;
    /** Шаг строки чипов: чип и зазор под ним. */
    private static final int LINE = CHIP_H + Ui.PAD_IN;
    private static final long REFRESH_MS = 1000L;
    private static final long MESSAGE_MS = 4000L;

    private final TextInput filter;
    private final Chips chips;
    private final Button addAll;
    private final Button done;

    private List<String> online = List.of();
    private long refreshedAt;
    private @Nullable StatusLine.Message message;
    private long messageAt;

    private PlayerPicker(Layers layers)
    {
        super(layers, DialogText.t("picker.title"), Strings.t("close"));

        this.width(WIDTH);

        this.filter = new TextInput().hint(DialogText.t("picker.filter")).searchIcon(true).maxLength(RosterField.NICK_MAX)
                .tooltip(DialogText.t("picker.filter.tip"));
        this.filter.onSubmit(value -> this.submit());
        this.row(this.filter, Ui.CTRL_H);

        StatusLine info = new StatusLine(this::info);
        this.row(info, info::height);

        this.chips = new Chips();
        this.row(this.chips, this.chips::height);

        this.addAll = new Button(DialogText.f("picker.add_all", 0), Button.Style.SECONDARY, this::addAllShown)
                .tooltip(DialogText.t("picker.add_all.tip"));
        this.done = new Button(DialogText.t("picker.done"), Button.Style.PRIMARY, this::dismiss);
        this.button(this.addAll);
        this.button(this.done);
        this.defaultButton(this.done);
    }

    /** Открыть поверх окна выгрузки; без подключения не открывается ({@code null}). */
    static @Nullable PlayerPicker open(Layers layers)
    {
        if (OnlinePlayers.names() == null)
        {
            return null;
        }

        PlayerPicker picker = new PlayerPicker(layers);
        picker.refresh(true);
        picker.open();
        // Сразу можно печатать ник: на большом сервере фильтр нужнее прокрутки
        picker.filter.setFocused(true);

        return picker;
    }

    // ===== Данные =====

    private void refresh(boolean now)
    {
        long time = Util.getMillis();

        if (! now && time - this.refreshedAt < REFRESH_MS)
        {
            return;
        }

        this.refreshedAt = time;
        List<String> names = OnlinePlayers.names();
        this.online = names == null ? List.of() : names;
    }

    /** Видимые ники: фильтр — вхождение без учёта регистра. */
    private List<String> shown()
    {
        String query = this.filter.text().strip().toLowerCase(Locale.ROOT);

        if (query.isEmpty())
        {
            return this.online;
        }

        List<String> out = new ArrayList<>();

        for (String nick : this.online)
        {
            if (nick.toLowerCase(Locale.ROOT).contains(query))
            {
                out.add(nick);
            }
        }

        return out;
    }

    private static Set<String> added()
    {
        Set<String> folded = new HashSet<>();

        for (String nick : SchematicsState.get().roster())
        {
            folded.add(nick.toLowerCase(Locale.ROOT));
        }

        return folded;
    }

    private static boolean full()
    {
        return SchematicsState.get().roster().size() >= SchematicsState.ROSTER_MAX;
    }

    /** Видимые и ещё не добавленные — их возьмёт «Добавить всех». */
    private List<String> addable()
    {
        Set<String> added = added();
        List<String> out = new ArrayList<>();

        for (String nick : this.shown())
        {
            if (! added.contains(nick.toLowerCase(Locale.ROOT)))
            {
                out.add(nick);
            }
        }

        return out;
    }

    // ===== Действия =====

    private void add(String nick)
    {
        RosterField.Added result = RosterField.addAll(List.of(nick));

        if (result.full())
        {
            this.say(DialogText.f("roster.full", SchematicsState.ROSTER_MAX), Ui.C_WARN);
        }
    }

    private void addAllShown()
    {
        List<String> nicks = this.addable();

        if (nicks.isEmpty())
        {
            return;
        }

        RosterField.Added result = RosterField.addAll(nicks);

        if (result.full())
        {
            this.say(DialogText.f("roster.full", SchematicsState.ROSTER_MAX), Ui.C_WARN);
        }
        else
        {
            this.say(DialogText.f("roster.online_added", result.added()), Theme.GREEN);
        }
    }

    /** Enter в фильтре: найден ровно один, кого ещё нет в списке, — добавить его. */
    private void submit()
    {
        List<String> nicks = this.addable();

        if (nicks.size() == 1)
        {
            this.add(nicks.getFirst());
            this.filter.setText("");
        }
        else
        {
            this.filter.setFocused(false);
        }
    }

    private void say(String text, int color)
    {
        this.message = new StatusLine.Message(text, color);
        this.messageAt = Util.getMillis();
    }

    /** Строка под фильтром: ответ на последнее действие, иначе — сколько видно и сколько уже в списке. */
    private StatusLine.Message info()
    {
        if (this.message != null && Util.getMillis() - this.messageAt < MESSAGE_MS)
        {
            return this.message;
        }

        if (full())
        {
            return new StatusLine.Message(DialogText.f("roster.full", SchematicsState.ROSTER_MAX), Ui.C_WARN);
        }

        List<String> shown = this.shown();

        if (shown.isEmpty())
        {
            return new StatusLine.Message(this.online.isEmpty() ? DialogText.t("picker.nobody")
                    : DialogText.f("picker.none", this.filter.text().strip()), Ui.C_TEXT_3);
        }

        int already = shown.size() - this.addable().size();

        return this.filter.text().isBlank()
                ? new StatusLine.Message(DialogText.f("picker.summary", shown.size(), already), Ui.C_TEXT_3)
                : new StatusLine.Message(DialogText.f("picker.found", shown.size(), this.online.size(), already), Ui.C_TEXT_3);
    }

    // ===== Отрисовка =====

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.refresh(false);

        int count = full() ? 0 : this.addable().size();
        this.addAll.label(DialogText.f("picker.add_all", count));
        this.addAll.setEnabled(count > 0);

        // Стопка слоёв затемняет экран один раз — под нижним окном; окно выгрузки под выбором
        // осталось бы ярким и пестрило по краям узкого окна выбора. Своё затемнение — поверх него
        Paint.rect(ctx, 0, 0, this.layers.screenW(), this.layers.screenH(), Ui.C_DIM);
        super.render(ctx, mouseX, mouseY);
    }

    // ===== Чипы =====

    /** Чип на экране (координаты без прокрутки) и его состояние. */
    private record Chip(String nick, int x, int y, int w, boolean added) {}

    /** Чипы ников с переносом по строкам; не влезли по высоте — своя прокрутка колесом. */
    private final class Chips extends Widget
    {
        private final List<Chip> placed = new ArrayList<>();
        private int contentH;
        private int scroll;
        private boolean scrollbar;

        /**
         * Высота строки окна: под весь список онлайн (не только найденное — окно не прыгает, пока
         * печатают фильтр), но не выше места на экране; место — целым числом строк чипов.
         */
        int height(int width)
        {
            List<String> all = PlayerPicker.this.online;

            if (all.isEmpty())
            {
                return 0;
            }

            int room = this.room(width);
            int full = linesHeight(lines(all, width));

            if (full <= room)
            {
                return full;
            }

            // С полосой прокрутки чипам уже: строки пересчитываются под меньшую ширину
            return Math.min(linesHeight(lines(all, width - scrollReserve())), room);
        }

        /**
         * Сколько высоты остаётся чипам, чтобы само окно не прокручивалось (фильтр всегда виден):
         * целое число строк — нижняя строка не режется пополам.
         */
        private int room(int width)
        {
            StatusLine.Message info = PlayerPicker.this.info();
            int infoH = Kit.wrap(Kit.font(), info.text(), width).size() * Ui.TIP_LINE;
            int chrome = Ui.OUTER * 2 + Ui.ROW_H + Ui.FOOTER_H + Ui.PAD_GROUP * 2;
            int above = Ui.CTRL_H + Ui.PAD_IN + infoH + Ui.PAD_IN;
            int space = PlayerPicker.this.layers.screenH() - chrome - above;

            return linesHeight(Math.max(2, (space + Ui.PAD_IN) / LINE));
        }

        private static int scrollReserve()
        {
            return Ui.SCROLL_W + Ui.PAD_IN;
        }

        private static int linesHeight(int lines)
        {
            return lines <= 0 ? 0 : lines * CHIP_H + (lines - 1) * Ui.PAD_IN;
        }

        private static int chipWidth(Font font, String nick, int width)
        {
            int full = Ui.PAD_REL + font.width(nick) + Ui.PAD_IN + Glyphs.width(Glyphs.PLUS) + Ui.PAD_REL;

            return Math.min(full, Math.max(0, width));
        }

        /** Сколько строк займут чипы при ширине {@code width}. */
        private static int lines(List<String> nicks, int width)
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

                cx += cw + Ui.PAD_IN;
            }

            return nicks.isEmpty() ? 0 : line + 1;
        }

        @Override
        public void setBounds(int x, int y, int w, int h)
        {
            super.setBounds(x, y, w, h);

            List<String> nicks = PlayerPicker.this.shown();
            this.scrollbar = linesHeight(lines(nicks, w)) > h;
            int width = this.scrollbar ? w - scrollReserve() : w;
            Set<String> added = added();
            Font font = Kit.font();

            this.placed.clear();
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

                this.placed.add(new Chip(nick, x + cx, y + line * (CHIP_H + Ui.PAD_IN), cw,
                        added.contains(nick.toLowerCase(Locale.ROOT))));
                cx += cw + Ui.PAD_IN;
            }

            this.contentH = linesHeight(nicks.isEmpty() ? 0 : line + 1);
            this.scroll = Ui.clamp(this.scroll, 0, Math.max(0, this.contentH - h));
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            if (this.h <= 0)
            {
                return;
            }

            Font font = Kit.font();
            boolean inside = this.contains(mouseX, mouseY);
            boolean full = full();

            ctx.enableScissor(this.x, this.y, this.x + this.w, this.y + this.h);

            for (Chip chip : this.placed)
            {
                int cy = chip.y - this.scroll;

                if (cy + CHIP_H > this.y && cy < this.y + this.h)
                {
                    this.drawChip(ctx, font, chip, cy, inside ? mouseX : -1, inside ? mouseY : -1, full);
                }
            }

            ctx.disableScissor();

            if (this.scrollbar)
            {
                int sx = this.x + this.w - Ui.SCROLL_W;
                int thumb = Math.max(Ui.SCROLL_THUMB_MIN, this.h * this.h / Math.max(1, this.contentH));
                int max = Math.max(1, this.contentH - this.h);

                if (Style.vanilla())
                {
                    // Ванильный стиль: дорожка и бегунок Minecraft
                    Vanilla.scrollerTrack(ctx, sx, this.y, Ui.SCROLL_W, this.h);
                    Vanilla.scrollerThumb(ctx, sx, this.y + (this.h - thumb) * this.scroll / max, Ui.SCROLL_W, thumb);
                }
                else
                {
                    Paint.round(ctx, sx, this.y, Ui.SCROLL_W, this.h, Ui.R_BAR, Ui.C_TRACK);
                    Paint.round(ctx, sx, this.y + (this.h - thumb) * this.scroll / max, Ui.SCROLL_W, thumb, Ui.R_BAR, Ui.C_THUMB);
                }
            }
        }

        private void drawChip(GuiContext ctx, Font font, Chip chip, int cy, int mouseX, int mouseY, boolean full)
        {
            boolean over = Paint.hit(mouseX, mouseY, chip.x, cy, chip.w, CHIP_H);
            boolean live = ! chip.added && ! full;
            String[] glyph = chip.added ? Glyphs.CHECK : Glyphs.PLUS;
            int glyphX = chip.x + chip.w - Ui.PAD_REL - Glyphs.width(glyph);
            int textRoom = Math.max(0, glyphX - Ui.PAD_IN - chip.x - Ui.PAD_REL);

            int fill;
            int edge;
            int text;
            int mark;

            if (chip.added)
            {
                // Уже в списке: без заливки, приглушённо, с галочкой — видно и не нажимается
                fill = 0;
                edge = Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF);
                text = Ui.C_TEXT_3;
                mark = Ui.C_ON;
            }
            else if (! live)
            {
                fill = 0;
                edge = Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF);
                text = Ui.C_TEXT_OFF;
                mark = Ui.C_TEXT_OFF;
            }
            else
            {
                fill = over ? Ui.C_ROW_FOCUS : Ui.C_ROW_HOVER;
                edge = over ? Ui.C_ON : Ui.C_DIVIDER;
                text = Ui.C_TEXT;
                mark = over ? Ui.C_ON : Ui.C_TEXT_3;
            }

            if (Style.vanilla())
            {
                // Ванильный стиль: чип — кнопка Minecraft (под курсором подсвечена), недоступный — серая выключенная
                Vanilla.button(ctx, chip.x, cy, chip.w, CHIP_H, live, live && over);
            }
            else
            {
                if (fill != 0)
                {
                    Paint.round(ctx, chip.x, cy, chip.w, CHIP_H, Ui.R_PILL, fill);
                }

                Paint.roundBorder(ctx, chip.x, cy, chip.w, CHIP_H, Ui.R_PILL, edge);
            }

            Paint.midText(ctx, font, Paint.fit(font, chip.nick, textRoom), chip.x + Ui.PAD_REL, cy, CHIP_H, text, false);
            Glyphs.draw(ctx, glyph, glyphX, cy + (CHIP_H - Glyphs.height(glyph)) / 2, mark);

            if (over)
            {
                String tip = chip.added ? DialogText.f("roster.duplicate", chip.nick)
                        : ! live ? DialogText.f("roster.full", SchematicsState.ROSTER_MAX)
                        : DialogText.f("picker.add", chip.nick);
                Tooltip.offer("export#picker#" + chip.nick, tip);
            }
        }

        private @Nullable Chip at(double mouseX, double mouseY)
        {
            if (! this.contains(mouseX, mouseY))
            {
                return null;
            }

            int mx = (int) Math.floor(mouseX);
            int my = (int) Math.floor(mouseY);

            for (Chip chip : this.placed)
            {
                if (Paint.hit(mx, my, chip.x, chip.y - this.scroll, chip.w, CHIP_H))
                {
                    return chip;
                }
            }

            return null;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            if (! Kit.left(click))
            {
                return false;
            }

            Chip chip = this.at(click.x(), click.y());

            if (chip == null)
            {
                return false;
            }

            if (! chip.added && ! full())
            {
                Tooltip.reset();
                Vanilla.click();
                PlayerPicker.this.add(chip.nick);
            }

            return true;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount)
        {
            if (! this.scrollbar || ! this.contains(mouseX, mouseY) || amount == 0.0D)
            {
                return false;
            }

            // Шаг — строка чипов: верхняя строка всегда видна целиком
            int max = Math.max(0, this.contentH - this.h);
            int lines = Math.max(0, (this.scroll - (int) Math.signum(amount) * LINE + LINE / 2) / LINE);
            this.scroll = Math.min(max, lines * LINE);

            return true;
        }
    }

    /**
     * Узкий вход для харнесса покрытия (режим {@code k}): найти открытый выбор, его поле, кнопки и
     * центр чипа — дальше харнесс жмёт обычным вводом экрана. В игре его никто не зовёт.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static @Nullable PlayerPicker current(Layers layers)
        {
            return layers.top() instanceof PlayerPicker picker ? picker : null;
        }

        public static TextInput filter(PlayerPicker picker)
        {
            return picker.filter;
        }

        public static Button addAll(PlayerPicker picker)
        {
            return picker.addAll;
        }

        public static Button done(PlayerPicker picker)
        {
            return picker.done;
        }

        /** Видимые ники по порядку чипов. */
        public static List<String> shown(PlayerPicker picker)
        {
            return List.copyOf(picker.shown());
        }

        /** Центр чипа {@code nick} на экране (с прокруткой) или {@code null}, если чип не виден. */
        public static double @Nullable [] chip(PlayerPicker picker, String nick)
        {
            Chips chips = picker.chips;

            for (Chip chip : chips.placed)
            {
                int cy = chip.y - chips.scroll;

                if (chip.nick.equals(nick) && cy >= chips.y() && cy + CHIP_H <= chips.y() + chips.h())
                {
                    return new double[] { chip.x + chip.w / 2.0, cy + CHIP_H / 2.0 };
                }
            }

            return null;
        }
    }
}
