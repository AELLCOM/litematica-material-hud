package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.export.PreviewPoster;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Dropdown;
import com.aell.layerlist.ui.kit.FormRow;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Toggle;
import fi.dy.masa.malilib.render.GuiContext;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

/**
 * Окно выгрузки (SPEC §3.4, §8) — модальное над экраном «Схемы»: название таблицы, источник и
 * фильтр строк, «Кто собирает», тумблеры «Иконки предметов», «Крупное сырьё», «Диаграмма», кнопки
 * форматов, листание частей для Discord, раздел «Таблица команды» и строка итога.
 *
 * <p>Выгрузка в файл не замораживает игру ({@link ExportJob}): иконки — порциями на клиентском
 * потоке, запись — в фоне. Буфер обмена (Копировать, Discord, скрипт) — сразу. Итог — в строке
 * итога внизу тела окна ({@link StatusLine}) с кнопками «Открыть файл», «Показать в папке» (для Google —
 * ещё «Открыть Google Таблицы», для таблицы команды — «Открыть лист»); ошибки — там же красным, в чат
 * ничего. Тело прокручивается к этой строке само.
 *
 * <p>Пока окно открыто, тостов нет (0.0.27): стопка экрана в правом нижнем углу ложилась на подвал
 * окна — на «Закрыть» и «Папку с выгрузками»; окно прячет её ({@link Toasts#claim}). Окно закрыли, пока
 * писался файл, — итог тостом экрана, как прежде (а если открыто уже другое окно выгрузки — в его строке).
 *
 * <p>Файл называется по схеме источника ({@link ExportSource#fileName()}), а не по названию таблицы
 * (0.0.27). В подвале рядом с «Закрыть» — «Папка с выгрузками» ({@link ExportsFolder}): после записи
 * файла она загорается золотом и гаснет за 30 с.
 */
@Environment(EnvType.CLIENT)
public final class ExportWindow extends Modal
{
    private static final int WIDTH = 460;
    private static final int NAME_MAX = 100;
    private static final String KEY_UNTITLED = "layerlist.schematics.export.untitled";
    private static final String KEY_GOOGLE_HINT = "layerlist.schematics.export.google.hint";

    /**
     * Экран-хозяин окна.
     *
     * @param layers стопка попапов экрана
     * @param toasts тосты экрана: прячутся, пока окно открыто; итог, пришедший после закрытия окна, — тостом
     * @param alive  экран ещё открыт — есть где показать тост об итоге, пришедшем после закрытия окна
     */
    public record Host(Layers layers, Toasts toasts, BooleanSupplier alive) {}

    /** Тот же ли запрос, что у скопированных частей Discord (иначе «Следующая часть» не к месту). */
    private record DiscordKey(String title, ExportSource source, boolean onlyMissing) {}

    /** Открытое сейчас окно выгрузки (модальное — одно); ему же — итог выгрузки, начатой в окне, которое уже закрыли. */
    private static @Nullable ExportWindow current;

    private final Host host;
    private final String defaultName;
    private final TextInput name;
    private final RosterField roster;
    private final FormatGrid formats;
    private final DiscordRow discord;
    private final TeamPanel team;
    private final StatusLine status;

    private ExportSource source;
    private @Nullable ExportJob job;
    private @Nullable StatusLine.Message result;
    private @Nullable DiscordKey discordKey;

    // Итоги строк: пересчёт только при смене строк или фильтра
    private @Nullable List<MaterialRow> statsRows;
    private boolean statsMissing;
    private int statsPositions;
    private long statsBlocks;

    private ExportWindow(Host host, String title, String defaultName, List<ExportSource> sources, int selected,
                         @Nullable ExportFormat preset)
    {
        super(host.layers(), title, Strings.t("close"));

        this.host = host;
        this.defaultName = defaultName;
        this.source = sources.get(Math.max(0, Math.min(selected, sources.size() - 1)));
        this.width(WIDTH);

        Font font = Kit.font();

        // Название таблицы: пустое поле — имя по умолчанию (оно же подсказка-заглушка)
        this.name = new TextInput().maxLength(NAME_MAX)
                .hint(defaultName.isBlank() ? Strings.plain(KEY_UNTITLED) : defaultName);
        this.name.setText(defaultName);
        this.name.onSubmit(value -> this.name.setFocused(false));
        FormRow nameRow = FormRow.stacked(DialogText.t("name"), DialogText.t("name.tip"), this.name);
        this.row(nameRow, nameRow.height());

        Dropdown<ExportSource> sourceBox = new Dropdown<>(host.layers(), sources, this.source, ExportSource::label,
                this::pickSource).tooltip(DialogText.t("source.tip"));
        FormRow sourceRow = FormRow.inline(DialogText.t("source"), DialogText.t("source.tip"), sourceBox,
                Math.max(Ui.CTRL_W, sourceBox.preferredWidth(font)));
        this.row(sourceRow, sourceRow.height());

        Segmented<Boolean> filter = new Segmented<>(List.of(false, true),
                missing -> missing ? DialogText.t("filter.missing") : DialogText.t("filter.all"),
                () -> options().onlyMissing(), this::setOnlyMissing).tooltip(DialogText.t("filter.tip"));
        FormRow filterRow = FormRow.inline(DialogText.t("filter"), DialogText.t("filter.tip"), filter,
                filter.preferredWidth(font));
        this.row(filterRow, filterRow.height());

        StatusLine rowsLine = new StatusLine(this::rowsMessage);
        this.row(rowsLine, rowsLine::height);

        // Что именно попадёт в список: «не хватает» у схемы и у списка HUD значит разное
        StatusLine aboutLine = new StatusLine(this::filterAbout);
        this.row(aboutLine, aboutLine::height);

        this.roster = new RosterField(host.layers());
        this.row(this.roster, this.roster::height);

        ToggleFlow checks = new ToggleFlow(
                new Toggle(DialogText.t("icons"), () -> options().icons(),
                        value -> setOptions(o -> new ExportOptions(o.onlyMissing(), value, o.rawMaterials(), o.chart())))
                        .tooltip(DialogText.t("icons.tip")),
                new Toggle(DialogText.t("raw"), () -> options().rawMaterials(),
                        value -> setOptions(o -> new ExportOptions(o.onlyMissing(), o.icons(), value, o.chart())))
                        .tooltip(DialogText.t("raw.tip")),
                new Toggle(DialogText.t("chart"), () -> options().chart(),
                        value -> setOptions(o -> new ExportOptions(o.onlyMissing(), o.icons(), o.rawMaterials(), value)))
                        .tooltip(DialogText.t("chart.tip")));
        this.row(checks, checks::height);

        SectionTitle formatsTitle = new SectionTitle(() -> DialogText.t("formats"));
        this.row(formatsTitle, SectionTitle.HEIGHT);
        this.formats = new FormatGrid(this::run, preset);
        this.row(this.formats, this.formats::height);

        this.discord = new DiscordRow(this::nextDiscordPart);
        this.row(this.discord, width -> this.discord.shown() ? Ui.BUTTON_H : 0);

        this.team = new TeamPanel(() -> this.run(ExportFormat.TEAM), this::copyScript, preset == ExportFormat.TEAM);
        this.row(this.team, this.team::height);

        this.status = new StatusLine(this::statusMessage);
        this.row(this.status, this.status::height);

        this.button(ExportsFolder.button());
        this.button(new Button(Strings.t("close"), Button.Style.SECONDARY, this::cancel));
        // Enter жмёт формат, выбранный в меню «Выгрузить ▾»; без выбора Enter ничего не выгружает
        this.defaultButton(preset == ExportFormat.TEAM ? this.team.sendButton() : this.formats.button(preset));
    }

    /**
     * Открыть окно.
     *
     * @param title       заголовок окна («Выгрузка: <схема>»)
     * @param defaultName название таблицы по умолчанию (имя схемы); пусто — «Материалы»
     * @param sources     источники строк для выпадающего списка (хотя бы один)
     * @param selected    какой источник выбран сначала
     * @param preset      формат, выбранный в меню «Выгрузить ▾» (нажат, срабатывает по Enter); {@code null} — без выбора
     */
    public static ExportWindow open(Host host, String title, String defaultName, List<ExportSource> sources, int selected,
                                    @Nullable ExportFormat preset)
    {
        Objects.requireNonNull(host, "host");

        if (sources.isEmpty())
        {
            throw new IllegalArgumentException("no export sources");
        }

        ExportWindow window = new ExportWindow(host, title, defaultName, List.copyOf(sources), selected, preset);
        window.open();

        return window;
    }

    @Override
    public void open()
    {
        // Рецепты «Крупного сырья» читаются в фоне, пока игрок заполняет окно
        Exporters.preload();
        this.source.load();
        super.open();
        this.host.toasts().claim(this);
        current = this;
    }

    // ===== Состояние =====

    private static ExportOptions options()
    {
        return SchematicsState.get().exportOptions();
    }

    private static void setOptions(UnaryOperator<ExportOptions> change)
    {
        SchematicsState.get().setExportOptions(change.apply(options()));
    }

    private void setOnlyMissing(boolean value)
    {
        setOptions(o -> new ExportOptions(value, o.icons(), o.rawMaterials(), o.chart()));
    }

    private void pickSource(ExportSource next)
    {
        this.source = next;
        next.load();
        this.result = null;
    }

    private String title()
    {
        String typed = this.name.text().strip();

        return typed.isEmpty() ? this.defaultName : typed;
    }

    /** Запрос на сейчас; {@code null} — строки ещё не посчитаны. */
    private @Nullable ExportRequest request()
    {
        List<MaterialRow> rows = this.source.ready();

        if (rows == null)
        {
            return null;
        }

        SchematicsState state = SchematicsState.get();

        return new ExportRequest(this.title(), this.source.label(), rows, state.roster(), state.exportOptions(),
                this.source.fileName());
    }

    private boolean busy()
    {
        return this.job != null && this.job.running();
    }

    /** Позиций и блоков после фильтра — так же, как их отберёт выгрузка (количество больше нуля). */
    private void updateStats(List<MaterialRow> rows)
    {
        boolean missing = options().onlyMissing();

        if (rows == this.statsRows && missing == this.statsMissing)
        {
            return;
        }

        int positions = 0;
        long blocks = 0L;

        for (MaterialRow row : rows)
        {
            long quantity = missing ? row.missing() : row.total();

            if (quantity > 0L)
            {
                ++positions;
                blocks += quantity;
            }
        }

        this.statsRows = rows;
        this.statsMissing = missing;
        this.statsPositions = positions;
        this.statsBlocks = blocks;
    }

    /** Почему выгружать нельзя прямо сейчас (подсказка выключенных кнопок); {@code null} — можно. */
    private @Nullable String blocked()
    {
        if (this.busy())
        {
            return DialogText.t("busy");
        }

        List<MaterialRow> rows = this.source.ready();

        if (rows == null)
        {
            String error = this.source.error();

            return error != null ? error : DialogText.t("rows.loading");
        }

        this.updateStats(rows);

        return this.statsPositions == 0 ? DialogText.t("rows.empty") : null;
    }

    private @Nullable StatusLine.Message rowsMessage()
    {
        List<MaterialRow> rows = this.source.ready();

        if (rows == null)
        {
            String error = this.source.error();

            return error != null ? new StatusLine.Message(error, Ui.C_DANGER)
                    : new StatusLine.Message(DialogText.t("rows.loading"), Theme.BLUE);
        }

        this.updateStats(rows);

        if (this.statsPositions == 0)
        {
            return new StatusLine.Message(DialogText.t("rows.empty"), Ui.C_WARN);
        }

        return new StatusLine.Message(DialogText.f("rows.summary", DialogText.num(this.statsPositions),
                DialogText.num(this.statsBlocks)), Ui.C_TEXT_3);
    }

    /**
     * Пояснение под «Всё / Только недостающее» — по виду источника; сверено с кодом счёта:
     * схема с миром не сверяется ({@code missing == total}), у списка HUD «не хватает» — пустые места, а не
     * тот блок или не то состояние — только при выключенной «Только пустые места».
     */
    private @Nullable StatusLine.Message filterAbout()
    {
        String text = filterAbout(this.source.kind(), options().onlyMissing(),
                LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue());

        if (text != null && this.source.narrowed())
        {
            text = text + " " + DialogText.t("filter.about.narrowed");
        }

        // Отключённые блоки в таблицу не идут — та же строка пояснения, StatusLine сама переносит и меряет высоту
        long disabled = this.source.disabledBlocks();

        if (disabled > 0L)
        {
            String note = DialogText.f("disabled.note", DialogText.num(disabled));
            text = text == null ? note : text + " " + note;
        }

        return text == null ? null : new StatusLine.Message(text, Ui.C_TEXT_3);
    }

    public static @Nullable String filterAbout(@Nullable ExportSource.Kind kind, boolean onlyMissing, boolean onlyEmpty)
    {
        if (kind == null)
        {
            return null;
        }

        return switch (kind)
        {
            case SCHEMATIC -> DialogText.t(onlyMissing ? "filter.about.schematic.missing" : "filter.about.schematic.all");
            case PLACED -> DialogText.t(onlyMissing ? "filter.about.placed.missing" : "filter.about.placed.all");
            case HUD -> DialogText.t(! onlyMissing ? "filter.about.hud.all"
                    : onlyEmpty ? "filter.about.hud.missing" : "filter.about.hud.missing_wrong");
        };
    }

    private @Nullable StatusLine.Message statusMessage()
    {
        if (this.busy())
        {
            return new StatusLine.Message(this.job.progress(), Theme.BLUE);
        }

        return this.result;
    }

    /** Кадр: порция иконок, доступность кнопок, сброс листания Discord при смене запроса. */
    private void tick()
    {
        if (this.job != null)
        {
            this.job.tick();
        }

        String why = this.blocked();
        this.formats.update(why);
        this.team.update(why);
        this.discord.update(! this.busy());

        if (this.discord.shown() && ! this.discordKey().equals(this.discordKey))
        {
            this.discord.reset();
        }
    }

    private DiscordKey discordKey()
    {
        return new DiscordKey(this.title(), this.source, options().onlyMissing());
    }

    // ===== Выгрузка =====

    private void run(ExportFormat format)
    {
        if (this.blocked() != null)
        {
            return;
        }

        // Таблицам — сначала оформление; выгрузка — по «Выгрузить» в том окне
        if (format.styled())
        {
            PaletteChooser.open(this.host.layers(), format, () -> this.start(format));
            return;
        }

        this.start(format);
    }

    private void start(ExportFormat format)
    {
        if (this.blocked() != null)
        {
            return;
        }

        ExportRequest request = this.request();

        if (request == null)
        {
            return;
        }

        this.result = null;

        switch (format)
        {
            case COPY -> this.copyTable(request);
            case DISCORD -> this.copyDiscord(request, 0);
            default ->
            {
                this.job = ExportJob.start(format, request, this.source.entry(), this::finished);
                // Прогресс — в той же строке внизу тела: при прокрутке (GUI 3) её бы не было видно
                this.scrollTo(this.status);
            }
        }
    }

    private void copyTable(ExportRequest request)
    {
        ExportResult copy = Exporters.tsv(request);

        if (! copy.ok() || copy.clipboard() == null)
        {
            this.fail(copy);
            return;
        }

        Minecraft.getInstance().keyboardHandler.setClipboard(copy.clipboard());
        this.succeed(DialogText.f("copied", DialogText.num(this.statsPositions)));
    }

    private void copyDiscord(ExportRequest request, int index)
    {
        int count = Exporters.discordParts(request);

        if (count == 0)
        {
            this.discord.reset();
            this.show(new StatusLine.Message(DialogText.t("rows.empty"), Ui.C_WARN));
            return;
        }

        ExportResult part = Exporters.discord(request, index);

        if (! part.ok() || part.clipboard() == null)
        {
            this.fail(part);
            return;
        }

        Minecraft.getInstance().keyboardHandler.setClipboard(part.clipboard());
        this.discord.copied(index, count);
        this.discordKey = this.discordKey();

        if (count == 1)
        {
            this.succeed(DialogText.t("discord.single"));
            return;
        }

        // Частей несколько: «скопирована часть N из M» стоит в строке Discord рядом с кнопкой
        // «Скопировать следующую часть» — общая строка итога её не повторяет
        this.result = null;
    }

    private void nextDiscordPart()
    {
        if (this.busy() || ! this.discordKey().equals(this.discordKey))
        {
            this.discord.reset();
            return;
        }

        ExportRequest request = this.request();

        if (request != null)
        {
            this.copyDiscord(request, this.discord.part() + 1);
        }
    }

    private void copyScript()
    {
        String script = Exporters.teamSheetScript();

        if (script == null)
        {
            this.show(new StatusLine.Message(DialogText.t("team.script_missing"), Ui.C_DANGER));
            return;
        }

        Minecraft.getInstance().keyboardHandler.setClipboard(script);
        this.succeed(DialogText.t("team.script_copied"));
    }

    /** Готово сразу (буфер обмена, скрипт) — окно открыто: только строка итога. */
    private void succeed(String text)
    {
        this.show(new StatusLine.Message(text, Theme.GREEN));
    }

    private void fail(ExportResult failed)
    {
        String text = failed.error() != null ? failed.error() : DialogText.f("failed", "?");

        this.deliver(new StatusLine.Message(text, Ui.C_DANGER));
    }

    /** Итог фоновой выгрузки — клиентский поток (окно может быть уже закрыто). */
    private void finished(ExportFormat format, ExportResult done)
    {
        if (! done.ok())
        {
            this.fail(done);
            return;
        }

        // Кнопка «Папка с выгрузками» горит, даже если окно уже закрыли: файл есть — путь к нему тоже
        if (! done.files().isEmpty())
        {
            ExportsFolder.exported(revealTarget(format, done.files()));
        }

        if (! this.layers.isOpen(this) && openNow() == null && ! this.host.alive().getAsBoolean())
        {
            return;
        }

        if (format == ExportFormat.TEAM)
        {
            URI link = done.open();

            if (link != null)
            {
                SystemOpen.browse(link);
                this.report(DialogText.t("team.done"), new Toasts.Action(DialogText.t("team.open"), () -> SystemOpen.browse(link)));
            }
            else
            {
                this.report(DialogText.t("team.done"));
            }

            return;
        }

        if (done.files().isEmpty())
        {
            this.report(DialogText.t("done"));
            return;
        }

        Path file = done.files().getFirst();
        Path shown = revealTarget(format, done.files());
        List<Toasts.Action> actions = new ArrayList<>(3);
        actions.add(new Toasts.Action(DialogText.t("open_file"), () -> SystemOpen.browse(file.toAbsolutePath().normalize().toUri())));
        actions.add(new Toasts.Action(DialogText.t("reveal"), () -> SystemOpen.reveal(shown)));

        String text = saved(format, done.files());

        if (format == ExportFormat.GOOGLE && done.open() != null)
        {
            URI sheets = done.open();
            // SPEC §8.2: новая таблица в браузере и файл в проводнике — сразу, импорт — руками
            SystemOpen.browse(sheets);
            SystemOpen.reveal(file);
            actions.add(new Toasts.Action(DialogText.t("open_sheets"), () -> SystemOpen.browse(sheets)));
            text = text + "\n" + Strings.plain(KEY_GOOGLE_HINT);
        }

        this.report(text, actions.toArray(new Toasts.Action[0]));
    }

    private void report(String text, Toasts.Action... actions)
    {
        this.deliver(new StatusLine.Message(text, Theme.GREEN, List.of(actions)));
    }

    /**
     * Итог — в строку этого окна; его уже закрыли — в строку открытого сейчас окна выгрузки (выгрузку
     * начали в прошлом); нет и такого — тостом экрана с теми же кнопками, если экран ещё открыт.
     */
    private void deliver(StatusLine.Message message)
    {
        ExportWindow window = this.layers.isOpen(this) ? this : openNow();

        if (window != null)
        {
            window.show(message);
        }
        else if (this.host.alive().getAsBoolean())
        {
            this.host.toasts().show(message.text(), message.color(), message.actions().toArray(new Toasts.Action[0]));
        }
    }

    /** В строку итога — и прокрутить тело к ней. */
    private void show(StatusLine.Message message)
    {
        this.result = message;
        this.scrollTo(this.status);
    }

    private static @Nullable ExportWindow openNow()
    {
        ExportWindow window = current;

        return window != null && window.layers.isOpen(window) ? window : null;
    }

    /**
     * Что выделить в проводнике: у картинки — её папку {@code <схема>/} (в ней {@code 1.png}, {@code 2.png}…,
     * 0.0.27), у остальных — первый файл.
     */
    public static Path revealTarget(ExportFormat format, List<Path> files)
    {
        Path first = files.getFirst();
        Path parent = first.getParent();

        return format == ExportFormat.PNG && parent != null ? parent : first;
    }

    private static String saved(ExportFormat format, List<Path> files)
    {
        if (format == ExportFormat.PNG)
        {
            // Картинки лежат своей папкой — её и называем, со счётом страниц; постер-превью — не страница
            boolean poster = files.size() > 1
                    && files.getLast().getFileName().toString().equals(PreviewPoster.FILE_NAME);
            return DialogText.f(poster ? "saved.folder.poster" : "saved.folder", format.label(),
                    DialogText.shortPath(revealTarget(format, files)), poster ? files.size() - 1 : files.size());
        }

        String path = DialogText.shortPath(files.getFirst());
        int more = files.size() - 1;

        if (more <= 0)
        {
            return DialogText.f("saved", format.label(), path);
        }

        return DialogText.f("saved.more", format.label(), path, more);
    }

    // ===== Отрисовка и ввод =====

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.tick();
        super.render(ctx, mouseX, mouseY);
    }

    @Override
    public void onClose()
    {
        super.onClose();
        this.host.toasts().unclaim(this);

        if (current == this)
        {
            current = null;
        }

        if (this.job != null)
        {
            this.job.cancel();
        }

        SchematicsState.get().save();
    }

    /** Только для харнесса (режим k): что показывает строка итога окна и «Кто собирает», где их кнопки. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Выбранный источник; строки посчитаны — {@code sourceReady}. */
        public static ExportSource source(ExportWindow window)
        {
            return window.source;
        }

        public static boolean sourceReady(ExportWindow window)
        {
            return window.source.ready() != null;
        }

        /** Идёт выгрузка (иконки, постер или запись). */
        public static boolean busy(ExportWindow window)
        {
            return window.busy();
        }

        /** Текст строки итога; {@code null} — строка скрыта. */
        public static @Nullable String statusText(ExportWindow window)
        {
            StatusLine.Message message = window.status.message();

            return message == null ? null : message.text();
        }

        public static int statusColor(ExportWindow window)
        {
            StatusLine.Message message = window.status.message();

            return message == null ? 0 : message.color();
        }

        /** Подписи кнопок строки итога по порядку. */
        public static List<String> statusActions(ExportWindow window)
        {
            StatusLine.Message message = window.status.message();

            return message == null ? List.of() : message.actions().stream().map(Toasts.Action::label).toList();
        }

        /** Строка итога на экране: {@code {x, y, w, h}}. */
        public static int[] statusBox(ExportWindow window)
        {
            return new int[] { window.status.x(), window.status.y(), window.status.w(), window.status.h() };
        }

        /** Кнопка строки итога с подписью {@code label}: {@code {x, y, w, h}}; {@code null} — нет. */
        public static int @Nullable [] statusAction(ExportWindow window, String label)
        {
            return window.status.actionBox(label);
        }

        /** Сообщение под полем «Кто собирает»; {@code null} — нет. */
        public static @Nullable String rosterNote(ExportWindow window)
        {
            return window.roster.noteText();
        }

        public static List<String> rosterNoteActions(ExportWindow window)
        {
            return window.roster.noteActions();
        }

        public static int @Nullable [] rosterNoteAction(ExportWindow window, String label)
        {
            return window.roster.noteActionBox(label);
        }
    }
}
