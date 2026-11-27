package com.aell.layerlist.schematics.gui.party;

import com.aell.layerlist.party.Parties;
import com.aell.layerlist.party.Party;
import com.aell.layerlist.party.PartyChat;
import com.aell.layerlist.party.PartyCode;
import com.aell.layerlist.party.PartySplit;
import com.aell.layerlist.schematics.gui.export.NickList;
import com.aell.layerlist.schematics.gui.export.RosterField;
import com.aell.layerlist.schematics.gui.export.SectionTitle;
import com.aell.layerlist.schematics.gui.export.StatusLine;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.FormRow;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Окно Party схемы (вкладка «Материалы», кнопка Party): название, состав по номерам, доли и рассылка в чат.
 *
 * <p>Party нет — окно собирает её: ники (свой — первым), «+ игроки онлайн», предварительные доли; «Создать Party»
 * записывает. Party есть — «Поделиться в чат» шлёт приглашение, строки долей переключают, чью долю показывают
 * таблица и список HUD (своя, любая другая, весь список). Party, принятую по приглашению, править нельзя —
 * состав задаёт ведущий; из неё можно выйти.
 *
 * <p>Набор кнопок у этих состояний разный, а кнопки окна задаются один раз — при смене состояния окно
 * открывается заново.
 */
@Environment(EnvType.CLIENT)
public final class PartyWindow extends Modal
{
    private static final int WIDTH = 400;
    private static final String KEY = "layerlist.party.";
    private static final long SAID_MS = 6000L;

    /** Что окно показывает; сменилось — окно открывается заново. */
    private enum Mode
    {
        /** Список схемы ещё считается. */
        PENDING,
        /** Party у схемы нет: окно её собирает. */
        DRAFT,
        /** Party есть. */
        PARTY
    }

    /** Party, состав которых меняли после последней рассылки: у остальных он ещё прежний. */
    private static final Set<String> UNSENT = new HashSet<>();
    /** Party, которые уже рассылали: до первой рассылки «поделитесь ещё раз» говорить не о чем. */
    private static final Set<String> SENT = new HashSet<>();
    /** Открытое сейчас окно (для харнесса). */
    private static @Nullable PartyWindow current;

    private final LitematicaSchematic schematic;
    private final String schematicName;
    private final Mode mode;
    private final TextInput name;
    private final RosterField roster;
    private final ShareList shares;
    private final StatusLine noteLine;
    private @Nullable Button create;
    private @Nullable Button share;
    private @Nullable Button copy;
    private @Nullable Button leave;
    /** Состав, пока Party не создана. */
    private List<String> draft;
    private @Nullable StatusLine.Message said;
    private long saidAt;

    private PartyWindow(Layers layers, LitematicaSchematic schematic, String schematicName, Mode mode, List<String> draft,
                        String typedName)
    {
        super(layers, mode == Mode.PARTY ? f("title.party", partyName(schematic, schematicName)) : t("title"),
                Strings.t("close"));

        this.schematic = schematic;
        this.schematicName = schematicName;
        this.mode = mode;
        this.draft = List.copyOf(draft);
        this.width(WIDTH);

        StatusLine about = new StatusLine(() -> new StatusLine.Message(t("about"), Ui.C_TEXT_3));
        this.row(about, about::height);

        this.name = new TextInput().maxLength(PartyCode.NAME_MAX).hint(schematicName).tooltip(t("name.tip"));
        this.name.setText(typedName);
        this.name.onSubmit(value -> this.name.setFocused(false));
        this.name.onChange(this::rename);
        FormRow nameRow = FormRow.stacked(t("name"), t("name.tip"), this.name);
        this.row(nameRow, nameRow.height());

        this.roster = new RosterField(layers, new Roster(), new RosterField.Labels(() -> t("roster"),
                count -> f("roster.count", count, PartySplit.MAX_MEMBERS), () -> t("roster.about"), () -> t("roster.empty"),
                count -> f("roster.cleared", count), () -> t("roster.tip"), true)).editable(this::editable);
        this.row(this.roster, this.roster::height);

        SectionTitle sharesTitle = new SectionTitle(() -> t(this.mode == Mode.PARTY ? "shares.pick" : "shares"))
                .tooltip(t("shares.tip"));
        this.row(sharesTitle, SectionTitle.HEIGHT);
        this.shares = new ShareList();
        this.row(this.shares, width -> this.shares.height());

        if (mode == Mode.PARTY)
        {
            // Приглашение можно не только отправить в общий чат, но и унести в буфер обмена. Кнопка — отдельной строкой:
            // четвёртая кнопка с такой подписью в подвал окна не влезает
            this.copy = new Button(t("copy"), Button.Style.SECONDARY, this::copyNow).tooltip(t("copy.tip"));
            FormRow inviteRow = FormRow.wide(t("invite"), t("invite.tip"), this.copy, this.copy.preferredWidth(Kit.font()));
            this.row(inviteRow, inviteRow.height());
        }

        this.noteLine = new StatusLine(this::note);
        this.row(this.noteLine, this.noteLine::height);
        StatusLine saidLine = new StatusLine(this::said);
        this.row(saidLine, saidLine::height);

        if (mode == Mode.PARTY)
        {
            Party party = this.party();
            boolean own = party != null && party.own();

            this.leave = new Button(t(own ? "disband" : "leave"), Button.Style.GHOST, this::askLeave)
                    .tooltip(t(own ? "disband.tip" : "leave.tip"));
            this.share = new Button(t("share"), Button.Style.PRIMARY, this::shareNow).tooltip(t("share.tip"));
            this.button(this.leave);
            this.button(new Button(Strings.t("close"), Button.Style.SECONDARY, this::cancel));
            this.button(this.share);
            this.defaultButton(this.share);
        }
        else
        {
            this.create = new Button(t("create"), Button.Style.PRIMARY, this::createNow).tooltip(t("create.tip"));
            this.button(new Button(Strings.t("close"), Button.Style.SECONDARY, this::cancel));
            this.button(this.create);
            this.defaultButton(this.create);
        }
    }

    /**
     * Открыть окно Party схемы.
     *
     * @param schematicName имя схемы — название Party по умолчанию
     */
    public static PartyWindow open(Layers layers, LitematicaSchematic schematic, String schematicName)
    {
        return open(layers, schematic, schematicName, lastTeam(), "", null);
    }

    /**
     * С кого начать состав новой Party: команда последней своей Party (обычно строят те же люди — ники заново
     * не вписывать), а если её не было — свой ник: чаще всего Party собирает тот, кто в ней участвует.
     */
    private static List<String> lastTeam()
    {
        for (Party party : Parties.all())
        {
            if (party.own() && ! party.roster().isEmpty())
            {
                return party.roster();
            }
        }

        return List.of(Parties.self());
    }

    private static PartyWindow open(Layers layers, LitematicaSchematic schematic, String schematicName, List<String> draft,
                                    String typedName, @Nullable StatusLine.Message said)
    {
        Parties.retry(schematic);
        Mode mode = mode(schematic);
        Parties.Bound bound = Parties.bound(schematic);
        String shownName = mode == Mode.PARTY && bound != null ? bound.party().name() : typedName;
        PartyWindow window = new PartyWindow(layers, schematic, schematicName, mode, draft, shownName);

        if (said != null)
        {
            window.said = said;
            window.saidAt = Util.getMillis();
        }

        window.open();
        current = window;

        return window;
    }

    private static Mode mode(LitematicaSchematic schematic)
    {
        if (Parties.base(schematic) == null && ! Parties.baseFailed(schematic))
        {
            return Mode.PENDING;
        }

        return Parties.bound(schematic) != null ? Mode.PARTY : Mode.DRAFT;
    }

    private static String partyName(LitematicaSchematic schematic, String fallback)
    {
        Parties.Bound bound = Parties.bound(schematic);

        return bound == null || bound.party().name().isBlank() ? fallback : bound.party().name();
    }

    private static String t(String suffix)
    {
        return Strings.plain(KEY + suffix);
    }

    private static String f(String suffix, Object... args)
    {
        return I18n.get(KEY + suffix, args);
    }

    private static String num(long value)
    {
        return Kit.group(value, Strings.plain("layerlist.schematics.num_group"));
    }

    // ===== Состояние =====

    private @Nullable Party party()
    {
        Parties.Bound bound = Parties.bound(this.schematic);

        return bound == null ? null : bound.party();
    }

    /** Состав можно править: Party ещё собирается или создана здесь. */
    private boolean editable()
    {
        Party party = this.party();

        return this.mode != Mode.PENDING && (party == null || party.own());
    }

    private List<String> members()
    {
        Party party = this.party();

        return party != null ? party.roster() : this.draft;
    }

    /** Состав Party для поля ников: у созданной — сразу в файл, у собираемой — в черновик окна. */
    private final class Roster implements NickList
    {
        @Override
        public List<String> list()
        {
            return PartyWindow.this.members();
        }

        @Override
        public void set(List<String> nicks)
        {
            Party party = PartyWindow.this.party();

            if (party == null)
            {
                PartyWindow.this.draft = List.copyOf(nicks);
            }
            else if (party.own())
            {
                Parties.put(party.withRoster(nicks));
                UNSENT.add(party.id());
            }
        }

        @Override
        public int max()
        {
            return PartySplit.MAX_MEMBERS;
        }
    }

    private void rename(String typed)
    {
        Party party = this.party();

        if (party != null && party.own())
        {
            String next = PartyCode.cleanName(typed);
            Parties.rename(party.id(), next.isEmpty() ? this.schematicName : next);
            UNSENT.add(party.id());
        }
    }

    private void say(String text, int color)
    {
        this.said = new StatusLine.Message(text, color);
        this.saidAt = Util.getMillis();
    }

    private @Nullable StatusLine.Message said()
    {
        return this.said != null && Util.getMillis() - this.saidAt < SAID_MS ? this.said : null;
    }

    /** Что стоит знать о Party сейчас: счёт списка, расхождение со списком ведущего, свой ник, неразосланный состав. */
    private @Nullable StatusLine.Message note()
    {
        if (Parties.baseFailed(this.schematic))
        {
            return new StatusLine.Message(t("note.no_world"), Ui.C_WARN);
        }

        Parties.Bound bound = Parties.bound(this.schematic);

        if (bound == null)
        {
            if (Parties.base(this.schematic) == null)
            {
                return new StatusLine.Message(t("note.counting"), Theme.BLUE);
            }

            // Принятая Party, чья схема у игрока другой версии: можно привязать к этой
            for (Party waiting : Parties.unbound())
            {
                if (! waiting.own())
                {
                    return new StatusLine.Message(f("note.waiting", shownName(waiting)), Ui.C_WARN,
                            List.of(new Toasts.Action(t("note.waiting.bind"), () -> this.bind(waiting))));
                }
            }

            return null;
        }

        Party party = bound.party();

        if (bound.mismatch())
        {
            return party.own()
                    ? new StatusLine.Message(t("note.mismatch.own"), Ui.C_WARN,
                            List.of(new Toasts.Action(t("note.mismatch.update"), () -> this.adopt(party, bound.base()))))
                    : new StatusLine.Message(t("note.mismatch"), Ui.C_WARN);
        }

        if (party.view() == Party.VIEW_MINE && party.indexOf(Parties.self()) < 0)
        {
            return new StatusLine.Message(f("note.not_listed", Parties.self()), Ui.C_WARN);
        }

        if (party.own() && SENT.contains(party.id()) && UNSENT.contains(party.id()))
        {
            return new StatusLine.Message(t("note.unsent"), Ui.C_CHANGED);
        }

        return null;
    }

    private static String shownName(Party party)
    {
        return party.name().isBlank() ? Strings.plain(KEY + "chat.unnamed") : party.name();
    }

    // ===== Действия =====

    private void createNow()
    {
        Parties.Base base = Parties.base(this.schematic);

        if (base == null || this.draft.isEmpty())
        {
            return;
        }

        String typed = PartyCode.cleanName(this.name.text());
        Party party = new Party(Parties.newId(), typed.isEmpty() ? PartyCode.cleanName(this.schematicName) : typed, this.draft,
                base.fingerprint(), true, Party.VIEW_MINE, Parties.fileOf(this.schematic), "", Util.getMillis());
        Parties.put(party);
        UNSENT.add(party.id());
        this.reopen(new StatusLine.Message(t("said.created"), Theme.GREEN));
    }

    private void shareNow()
    {
        Party party = this.party();

        if (party == null)
        {
            return;
        }

        switch (PartyChat.share(party))
        {
            case OK ->
            {
                SENT.add(party.id());
                UNSENT.remove(party.id());
                this.say(t("said.shared"), Theme.GREEN);
            }
            case OFFLINE -> this.say(t("said.offline"), Ui.C_WARN);
            case TOO_LONG -> this.say(t("said.too_long"), Ui.C_WARN);
        }
    }

    private void copyNow()
    {
        Party party = this.party();

        if (party == null)
        {
            return;
        }

        switch (PartyChat.copy(party))
        {
            case OK -> this.say(t("said.copied"), Theme.GREEN);
            case TOO_LONG -> this.say(t("said.too_long"), Ui.C_WARN);
            case OFFLINE -> { }
        }
    }

    private void askLeave()
    {
        Party party = this.party();

        if (party == null)
        {
            return;
        }

        boolean own = party.own();

        Modal.confirm(this.layers, t(own ? "disband.title" : "leave.title"), t(own ? "disband.text" : "leave.text"),
                t(own ? "disband" : "leave"), true, () ->
                {
                    Parties.remove(party.id());
                    UNSENT.remove(party.id());
                    SENT.remove(party.id());
                    this.reopen(new StatusLine.Message(t(own ? "said.disbanded" : "said.left"), Ui.C_TEXT_2));
                }, t("cancel"));
    }

    /** Привязать принятую Party к этой схеме: списки разные, окно дальше предупреждает об этом. */
    private void bind(Party waiting)
    {
        Parties.bind(waiting, this.schematic);
        this.reopen(new StatusLine.Message(t("said.bound"), Theme.GREEN));
    }

    /** Своя Party, чья схема изменилась (замена блоков): взять её нынешний список — и разослать заново. */
    private void adopt(Party party, Parties.Base base)
    {
        Parties.put(party.withFingerprint(base.fingerprint()));
        UNSENT.add(party.id());
        this.say(t("said.adopted"), Theme.GREEN);
    }

    private void view(int view)
    {
        Party party = this.party();

        if (party != null && party.view() != view)
        {
            Parties.put(party.withView(view));
        }
    }

    private void reopen(@Nullable StatusLine.Message message)
    {
        List<String> draft = this.draft;
        String typed = this.name.text();

        this.dismiss();
        open(this.layers, this.schematic, this.schematicName, draft, typed, message);
    }

    // ===== Отрисовка =====

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        // Список досчитался, Party приняли из чата или распустили — у окна другие кнопки
        if (this.layers.top() == this && mode(this.schematic) != this.mode)
        {
            this.reopen(this.said());
            return;
        }

        Party party = this.party();
        this.name.setEnabled(this.editable());

        if (this.create != null)
        {
            boolean ready = Parties.base(this.schematic) != null;
            this.create.setEnabled(ready && ! this.draft.isEmpty());
            this.create.tooltip(! ready ? t("note.counting") : this.draft.isEmpty() ? t("create.empty") : t("create.tip"));
        }

        if (this.share != null)
        {
            this.share.setEnabled(party != null);
        }

        super.render(ctx, mouseX, mouseY);
    }

    @Override
    public void onClose()
    {
        super.onClose();

        if (current == this)
        {
            current = null;
        }
    }

    // ===== Доли =====

    /** Строка списка долей на экране. */
    private record ShareRow(int view, String label, String stats, int y, boolean selected, boolean self) {}

    /**
     * Доли участников: «Ник #1 — ячеек · штук · видов». У созданной Party строка — ещё и выбор, чью долю
     * показывают таблица и список HUD; последняя строка — весь список (делёж на паузе).
     */
    private final class ShareList extends Widget
    {
        private final List<ShareRow> rows = new ArrayList<>();

        int height()
        {
            int count = PartyWindow.this.members().size();

            if (count == 0 || Parties.base(PartyWindow.this.schematic) == null)
            {
                return Ui.TIP_LINE;
            }

            return (count + (PartyWindow.this.mode == Mode.PARTY ? 1 : 0)) * Ui.ROW_H;
        }

        private void layout()
        {
            this.rows.clear();
            List<String> members = PartyWindow.this.members();
            Parties.Base base = Parties.base(PartyWindow.this.schematic);

            if (members.isEmpty() || base == null)
            {
                return;
            }

            Party party = PartyWindow.this.party();
            PartySplit split = base.split(members.size());
            int mine = - 1;

            for (int i = 0; i < members.size(); ++i)
            {
                if (members.get(i).equalsIgnoreCase(Parties.self()))
                {
                    mine = i;
                }
            }

            int shown = party == null ? Integer.MIN_VALUE : party.shownMember(Parties.self());
            int cy = this.y;

            for (int i = 0; i < members.size(); ++i)
            {
                PartySplit.Member member = split.member(i);
                boolean self = i == mine;
                String label = members.get(i) + " #" + (i + 1) + (self ? " " + t("shares.you") : "");
                String stats = f("shares.stats", num(member.slots()), num(member.blocks()), num(member.kinds()));
                this.rows.add(new ShareRow(self ? Party.VIEW_MINE : i, label, stats, cy, party != null && shown == i, self));
                cy += Ui.ROW_H;
            }

            if (party != null && PartyWindow.this.mode == Mode.PARTY)
            {
                this.rows.add(new ShareRow(Party.VIEW_ALL, t("shares.all"), f("shares.all.stats", num(base.positions())), cy,
                        shown < 0, false));
            }
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            Font font = Kit.font();
            this.layout();

            if (this.rows.isEmpty())
            {
                String text = PartyWindow.this.members().isEmpty() ? t("shares.empty") : t("shares.wait");
                Paint.text(ctx, font, Paint.fit(font, text, this.w), this.x, this.y + 1, Ui.C_TEXT_3, false);
                return;
            }

            boolean pick = PartyWindow.this.mode == Mode.PARTY;

            for (ShareRow row : this.rows)
            {
                boolean over = pick && Paint.hit(mouseX, mouseY, this.x, row.y(), this.w, Ui.ROW_H);

                if (Style.vanilla())
                {
                    if (row.selected() || over)
                    {
                        Vanilla.selection(ctx, this.x, row.y(), this.w, Ui.ROW_H, row.selected());
                    }
                }
                else if (row.selected())
                {
                    Paint.round(ctx, this.x, row.y(), this.w, Ui.ROW_H, Ui.R_ROW, Theme.BLUE_FILL);
                    Paint.roundBorder(ctx, this.x, row.y(), this.w, Ui.ROW_H, Ui.R_ROW, Theme.BLUE_EDGE);
                }
                else if (over)
                {
                    Paint.round(ctx, this.x, row.y(), this.w, Ui.ROW_H, Ui.R_ROW, Ui.C_ROW_HOVER);
                }

                int statsW = Math.min(font.width(row.stats()), this.w / 2);
                int labelRoom = Math.max(0, this.w - Ui.PAD_REL * 2 - statsW - Ui.PAD_GROUP);
                Paint.midText(ctx, font, Paint.fit(font, row.label(), labelRoom), this.x + Ui.PAD_REL, row.y(), Ui.ROW_H,
                        row.selected() ? Ui.C_TEXT : row.self() ? Ui.C_TEXT : Ui.C_TEXT_2, false);
                Paint.midTextRight(ctx, font, Paint.fit(font, row.stats(), statsW), this.x + this.w - Ui.PAD_REL, row.y(),
                        Ui.ROW_H, row.selected() ? Ui.C_TEXT_2 : Ui.C_TEXT_3, false);

                if (over)
                {
                    Tooltip.offer("party#share#" + row.view(), row.selected() ? t("shares.row.shown")
                            : row.view() == Party.VIEW_ALL ? t("shares.row.all") : t("shares.row.tip"));
                }
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            if (PartyWindow.this.mode != Mode.PARTY || ! Kit.left(click))
            {
                return false;
            }

            this.layout();

            for (ShareRow row : List.copyOf(this.rows))
            {
                if (Kit.leftHit(click, this.x, row.y(), this.w, Ui.ROW_H))
                {
                    Vanilla.click();
                    PartyWindow.this.view(row.view());
                    return true;
                }
            }

            return false;
        }
    }

    /** Только для харнесса: что показывает окно и где его кнопки. */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static @Nullable PartyWindow current(Layers layers)
        {
            return current != null && layers.isOpen(current) ? current : null;
        }

        /** Party создана (у окна кнопка «Поделиться»), а не собирается. */
        public static boolean exists(PartyWindow window)
        {
            return window.mode == Mode.PARTY;
        }

        public static TextInput name(PartyWindow window)
        {
            return window.name;
        }

        public static RosterField roster(PartyWindow window)
        {
            return window.roster;
        }

        public static @Nullable Button create(PartyWindow window)
        {
            return window.create;
        }

        public static @Nullable Button share(PartyWindow window)
        {
            return window.share;
        }

        public static @Nullable Button leave(PartyWindow window)
        {
            return window.leave;
        }

        public static @Nullable Button copy(PartyWindow window)
        {
            return window.copy;
        }

        /** Подписи строк долей по порядку. */
        public static List<String> shareLabels(PartyWindow window)
        {
            window.shares.layout();

            return window.shares.rows.stream().map(row -> row.label() + " | " + row.stats()).toList();
        }

        /** Строка доли номер {@code index} на экране: {@code {x, y, w, h}}; {@code null} — такой нет. */
        public static int @Nullable [] shareRow(PartyWindow window, int index)
        {
            window.shares.layout();

            if (index < 0 || index >= window.shares.rows.size())
            {
                return null;
            }

            return new int[] { window.shares.x(), window.shares.rows.get(index).y(), window.shares.w(), Ui.ROW_H };
        }

        /** Какая строка долей выбрана; {@code -1} — никакая. */
        public static int shownRow(PartyWindow window)
        {
            window.shares.layout();

            for (int i = 0; i < window.shares.rows.size(); ++i)
            {
                if (window.shares.rows.get(i).selected())
                {
                    return i;
                }
            }

            return - 1;
        }

        /** Текст строки состояния и строки последнего действия. */
        public static @Nullable String note(PartyWindow window)
        {
            StatusLine.Message note = window.note();

            return note == null ? null : note.text();
        }

        /** Кнопка строки состояния с подписью {@code label}: {@code {x, y, w, h}}; {@code null} — нет. */
        public static int @Nullable [] noteAction(PartyWindow window, String label)
        {
            return window.noteLine.actionBox(label);
        }

        public static @Nullable String said(PartyWindow window)
        {
            StatusLine.Message said = window.said();

            return said == null ? null : said.text();
        }
    }
}
