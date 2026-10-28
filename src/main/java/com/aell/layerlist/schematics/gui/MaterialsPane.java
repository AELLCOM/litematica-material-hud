package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.gui.export.ExportNames;
import com.aell.layerlist.schematics.gui.export.ExportsFolder;
import com.aell.layerlist.schematics.gui.materials.BlockPicker;
import com.aell.layerlist.schematics.gui.materials.ExportFormat;
import com.aell.layerlist.schematics.gui.materials.MaterialFilter;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.materials.MaterialsExport;
import com.aell.layerlist.schematics.gui.materials.MaterialsJob;
import com.aell.layerlist.schematics.gui.materials.ReplaceCell;
import com.aell.layerlist.schematics.gui.materials.SchematicChoice;
import com.aell.layerlist.schematics.gui.materials.StorageFind;
import com.aell.layerlist.schematics.gui.materials.TableSource;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.materials.Availability;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.ItemKeys;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.ReplacementStates;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.SettingsScreen;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toggle;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Подвкладка «Материалы» (M25, интерфейс; SPEC §3.3). Сверху — источник («Схема: &lt;имя&gt;» /
 * «Список HUD»), поиск, тумблер «Только недостающее», «Пересчитать»; ниже — таблица во всю ширину:
 * иконка (настоящий предмет), «Предмет», «Всего», «Разбивка», «В инвентаре», «В сундуках»,
 * «Не хватает» (цвет — как у счётчиков HUD). Заголовок — сортировка, повторный клик — обратный
 * порядок; числа — по правому краю. В подвале — итоги видимых строк, кнопки выгрузки (не влезают —
 * лишние уходят в «Ещё ▾») и левее них «Папка с выгрузками» (0.0.27, после выгрузки горит золотом; в
 * тесном подвале — один значок). ПКМ по строке — «Заменить блок…», «Найти в сундуках», «Скопировать
 * название / id».
 *
 * <p>Замена блоков (0.0.27): клик по иконке или названию строки-блока (или Enter) открывает выбор блока
 * ({@link BlockPicker}), выбор меняет блок в самой схеме ({@link BlockReplacements}): голограммы её
 * размещений перестраиваются, строки пересчитываются. Заменённая строка пишет «Новый ← Исходный» и
 * несёт кнопку отката, как сброс у строк настроек ({@link ReplaceCell}). Сущности, вёдра, растения в
 * горшках и строки «Списка HUD» не заменяются — клик объясняет почему.
 *
 * <p>Строки собираются один раз на загрузку ({@link MaterialLine}: числа и подписи готовы), таблица
 * рисует только видимые — тысячи позиций не тормозят. Загрузка — {@link MaterialsJob}: у файла
 * «загрузка схемы» и «подсчёт», пока нет строк — по центру шаг и полоса, при пересчёте старые строки
 * остаются, над таблицей бежит тонкая полоса.
 *
 * <p>Сверка с миром (1.0.18): схема, размещённая в мире (файл — любое включённое размещение этого файла,
 * размещение — оно само), считается своим счётчиком {@link LayerCountManager#forPlacements} по всей постройке,
 * слои Litematica не важны. «Всего» — вся схема, «Не хватает» и «Только недостающее» — от ещё не поставленного.
 * Не размещена — сверять не с чем: таблица — весь файл, а «Только недостающее» недоступно.
 */
@Environment(EnvType.CLIENT)
final class MaterialsPane extends Pane
{
    private static final int ROW_H = Ui.ROW_H;
    private static final int ICON = 16;
    private static final int ICON_COL = ICON + Ui.PAD_IN * 2;
    /** Насколько притушена иконка отключённого блока (доля непрозрачности фона панели поверх). */
    private static final float DISABLED_ICON_SHADE = 0.6F;
    /** Ключ строки отключённого блока, когда тот же предмет есть и среди включённых. */
    private static final String DISABLED_KEY = "disabled:";
    private static final int NUMBER_COL_MIN = 56;
    private static final int ITEM_COL_MIN = 80;
    private static final int BREAKDOWN_COL_MIN = 90;
    private static final int SEARCH_MIN = 80;
    private static final int SOURCE_MIN = 48;
    private static final int TOGGLE_MIN = Toggle.TRACK + Ui.PAD_IN + 24;
    private static final int BAR_W = 120;
    private static final long BAR_PERIOD_MS = 1200L;
    private static final long HUD_POLL_MS = 1000L;
    private static final long SHOW_SECONDS_AFTER_MS = 1000L;
    /** Дольше этого свежий счёт после правки не ждём: показываем, что есть. */
    private static final long COUNTER_AWAIT_MS = 15_000L;
    /** Тиков без данных и без прохода по чанкам, после которых счётчик считается пустым (≈ 1 с). */
    private static final int GIVE_UP_TICKS = 20;
    private static final long GIVE_UP_RETRY_MS = 10_000L;
    /** Запас на расхождение своей оценки места в подвале с раскладкой экрана. */
    private static final int FOOTER_SLACK = Ui.PAD_GROUP;

    // Сортировка и фильтр — на время игры, как сортировка «Размещений»
    private static int sortColumn = -1;
    private static boolean sortDescending;
    private static boolean onlyMissing;
    private static boolean onlyChanged;

    private final Table<MaterialLine> table;
    private final TextInput search;
    private final Toggle missingOnly;
    /** «Изменённые»: только заменённые и отключённые блоки; виден вместе со «Сбросом». */
    private final Toggle changedOnly;
    private final Toggle stockAware;
    private final Toggle cutoff;
    private final Button schematicButton;
    private final Button hudButton;
    private final IconButton refreshButton;
    private final Button retry;
    private final Map<ExportFormat, Button> exportButtons = new EnumMap<>(ExportFormat.class);
    private final Button more;
    private final Button folder = ExportsFolder.button();
    private final ReplaceCell replaceCell = new ReplaceCell();
    /** Свой счётчик размещений схемы (сверка с миром); {@code null} — схема не размещена или источник не схема. */
    private @Nullable LayerCountManager counter;
    /** Источник, для которого создан счётчик. */
    private @Nullable TableSource counterSource;
    /** Список счётчика, из которого собраны строки (тот же объект — счёт не обновлялся). */
    private @Nullable List<MaterialListEntry> counterEntries;
    /** Ждём свежий счёт (после создания и правок): строки не пересобираются, пока список счётчика не сменится. */
    private boolean counterAwait;
    /** Список счётчика на момент правки: пока он тот же, счёт ещё без правки. */
    private @Nullable List<MaterialListEntry> counterStale;
    private long counterAwaitSince;
    private int counterTicks;
    private long counterCheckedAt;
    private long placementsCheckedAt;
    /** Счётчик ничего не насчитал и не считает (схема пуста или мир не тот): показан файл целиком, как у неразмещённой. */
    private boolean counterGaveUp;
    private long counterGaveUpAt;
    /** Схема-источник не размещена в мире: «Только недостающее» недоступно, таблица — весь файл. */
    private boolean unplaced;
    /** Строки файла из {@link MaterialsJob}: нужны, пока счётчик не дал своих, и если не даст вовсе. */
    private List<MaterialRow> fileRows = List.of();
    /** Строки таблицы собраны счётчиком размещений (сверены с миром). */
    private boolean verifiedRows;
    /** «Сброс»: все замены и отключённые блоки схемы — разом к исходному файлу. */
    private final Button resetAllButton;
    /** Строка отключённого блока → исходные блоки и отключённые сущности за ней (для «включить»). */
    private Map<String, SchematicMaterials.DisabledRow> disabledBlocks = Map.of();
    /** «Список HUD»: схемы включённых размещений, из которых собраны строки (отключение идёт во все). */
    private List<LitematicaSchematic> hudSchematics = List.of();
    /** «Список HUD»: строка отключённого блока → по схемам, какие исходные блоки за ней (для «включить»). */
    private Map<String, Map<LitematicaSchematic, SchematicMaterials.DisabledRow>> hudDisabled = Map.of();

    private @Nullable TableSource source;
    /** Схема для кнопки «Схема: имя», пока показан список HUD: последняя открытая. */
    private @Nullable TableSource lastSchematic;
    /** Выбор «Библиотеки», который таблица уже видела: сменился — таблица идёт за ним. */
    private @Nullable SchematicEntry seenSelection;
    private @Nullable MaterialsJob job;
    private boolean jobApplied;
    private List<MaterialLine> lines = List.of();
    private List<MaterialLine> shown = List.of();
    private MaterialFilter filter = MaterialFilter.of("", onlyMissing, false);
    private MaterialFilter.Totals totals = MaterialFilter.Totals.EMPTY;
    /** Список HUD, из которого собраны строки (тот же объект — HUD не пересчитывал). */
    private @Nullable List<MaterialListEntry> hudEntries;
    private long hudCheckedAt;
    private List<ExportFormat> overflow = List.of();
    /** Схемы мира для «Схемы» без выбора в «Библиотеке»: перечитываются раз в секунду, не каждый кадр. */
    private List<SchematicChoice.Option> choices = List.of();
    private boolean choicesRead;
    private long choicesAt;
    /** Схема Litematica строк (файл или размещение): в ней меняет блоки замена. У списка HUD — нет. */
    private @Nullable LitematicaSchematic schematic;
    /** Ключ строки → можно ли заменить и что в неё заменено; собирается вместе со строками. */
    private Map<String, BlockReplacements.RowState> rowStates = Map.of();
    /** После замены выбрать строку нового блока, когда придут пересчитанные строки. */
    private @Nullable String selectAfter;

    MaterialsPane(GuiSchematics screen)
    {
        super(screen);

        Font font = Kit.font();
        String totalTitle = Tr.t("materials.col.total");
        String inventoryTitle = Tr.t("materials.col.inventory");
        String chestsTitle = Tr.t("materials.col.chests");
        String missingTitle = Tr.t("materials.col.missing");
        int totalW = numberColumn(font, totalTitle);
        int inventoryW = numberColumn(font, inventoryTitle);
        int chestsW = numberColumn(font, chestsTitle);
        int missingW = numberColumn(font, missingTitle);

        // Узкое окно теряет сначала «В сундуках», потом «Разбивку», потом «В инвентаре»
        int full = ICON_COL + ITEM_COL_MIN + totalW + BREAKDOWN_COL_MIN + inventoryW + chestsW + missingW
                + Ui.SCROLL_W + Ui.SCROLL_GAP;
        int withoutChests = full - chestsW;
        int withoutBreakdown = withoutChests - BREAKDOWN_COL_MIN;

        this.table = new Table<MaterialLine>(ROW_H).columns(
                Table.Column.<MaterialLine>fixed("", ICON_COL)
                        .cell(MaterialsPane::renderIconCell)
                        .onClick(this::itemClick),
                Table.Column.<MaterialLine>flex(Tr.t("materials.col.item"), 3)
                        .minWidth(ITEM_COL_MIN)
                        .cell(this::renderItemCell)
                        .onClick(this::itemClick)
                        .sort(MaterialLine.BY_NAME),
                // Сверенная схема при «Только недостающем» — как «Список HUD»: «Осталось» вместо «Всего», разбивка остатка
                Table.Column.<MaterialLine>fixed(totalTitle, totalW)
                        .align(Table.Align.RIGHT)
                        .text(line -> this.showLeft() ? line.lackText() : line.needText())
                        .color(line -> line.disabled() ? Ui.C_TEXT_OFF : Ui.C_TEXT)
                        .tooltip(MaterialsPane::totalTip)
                        .headerTooltip(Tr.t("materials.col.total.tip"))
                        .liveTitle(() -> Tr.t(this.showLeft() ? "materials.col.left" : "materials.col.total"),
                                () -> Tr.t(this.showLeft() ? "materials.col.left.tip" : "materials.col.total.tip"))
                        .sort(MaterialLine.byCount(line -> this.showLeft() ? line.lack() : line.need()))
                        .descendingFirst(),
                Table.Column.<MaterialLine>flex(Tr.t("materials.col.breakdown"), 2)
                        .minWidth(BREAKDOWN_COL_MIN)
                        .hideBelow(withoutChests)
                        .text(line -> this.showLeft() ? line.lackBreakdownText() : line.breakdownText())
                        .color(line -> line.disabled() ? Ui.C_CHANGED : Ui.C_TEXT_2)
                        .headerTooltip(Tr.t("materials.col.breakdown.tip"))
                        .sort(MaterialLine.byCount(line -> this.showLeft() ? line.lack() : line.need()))
                        .descendingFirst(),
                Table.Column.<MaterialLine>fixed(inventoryTitle, inventoryW)
                        .align(Table.Align.RIGHT)
                        .hideBelow(withoutBreakdown)
                        .text(MaterialLine::inventoryText)
                        .color(line -> line.disabled() ? Ui.C_TEXT_OFF : line.inventory() > 0L ? Ui.C_TEXT_2 : Ui.C_TEXT_3)
                        .tooltip(line -> countTip(line.inventory(), line.row().maxStack()))
                        .headerTooltip(Availability.inventoryHint())
                        .sort(MaterialLine.byCount(MaterialLine::inventory))
                        .descendingFirst(),
                Table.Column.<MaterialLine>fixed(chestsTitle, chestsW)
                        .align(Table.Align.RIGHT)
                        .hideBelow(full)
                        .text(MaterialLine::chestsText)
                        .color(line -> line.disabled() ? Ui.C_TEXT_OFF : line.chests() > 0L ? Ui.C_TEXT_2 : Ui.C_TEXT_3)
                        .tooltip(MaterialsPane::chestsTip)
                        .headerTooltip(Availability.chestsHint())
                        .sort(MaterialLine.byCount(MaterialLine::chests))
                        .descendingFirst(),
                Table.Column.<MaterialLine>fixed(missingTitle, missingW)
                        .align(Table.Align.RIGHT)
                        .text(MaterialLine::shortageText)
                        .color(line -> line.disabled() ? Ui.C_TEXT_OFF : line.coverage().color())
                        .tooltip(MaterialsPane::missingTip)
                        .headerTooltip(Tr.t("materials.col.missing.tip"))
                        .sort(MaterialLine.byCount(MaterialLine::shortage))
                        .descendingFirst())
                .onSort((column, descending) ->
                {
                    sortColumn = column;
                    sortDescending = descending;
                })
                .listener(new ScrollList.Listener<>()
                {
                    @Override
                    public void context(MaterialLine item, int index, int mouseX, int mouseY)
                    {
                        MaterialsPane.this.openMenu(item, mouseX, mouseY);
                    }

                    @Override
                    public void activate(MaterialLine item, int index)
                    {
                        MaterialsPane.this.openPicker(item);
                    }
                });
        this.table.setSort(sortColumn, sortDescending);

        this.search = new TextInput().hint(Tr.t("materials.search_hint")).searchIcon(true).maxLength(64)
                .onChange(text -> this.applyFilter(true)).onSubmit(text -> this.focusTable());
        this.missingOnly = new Toggle(Tr.t("materials.only_missing"), this::missingActive, value ->
        {
            onlyMissing = value;
            this.applyFilter(true);
        }).tooltip(Tr.t("materials.only_missing.tip"));
        // «С учётом склада» — общая настройка с HUD (как «Считать до Y»): прячет то, что уже есть в инвентаре и сундуках
        this.stockAware = new Toggle(Tr.t("materials.use_stock"), LayerListConfigs.USE_STOCK::getBooleanValue, value ->
        {
            LayerListConfigs.USE_STOCK.setBooleanValue(value);
            this.applyFilter(true);
        }).tooltip(Tr.t("materials.use_stock.tip"));
        this.cutoff = new Toggle(Tr.t("materials.cutoff"), LayerListConfigs.MATERIAL_CUTOFF::getBooleanValue, value ->
        {
            LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(value);
            this.refresh();
        }).tooltip(Tr.t("materials.cutoff.tip"));
        this.changedOnly = new Toggle(Tr.t("materials.only_changed"), () -> onlyChanged, value ->
        {
            onlyChanged = value;
            this.applyFilter(true);
        }).tooltip(Tr.t("materials.only_changed.tip"));
        this.schematicButton = new Button("", Button.Style.SECONDARY, this::showSchematic);
        this.hudButton = new Button(Tr.t("materials.source.hud"), Button.Style.SECONDARY,
                () -> this.show(new TableSource.Hud(), false)).tooltip(Tr.t("materials.source.hud.tip"));
        this.refreshButton = new IconButton(Glyphs.REFRESH, Tr.t("materials.refresh.tip"), Button.Style.GHOST,
                this::refresh);
        this.retry = new Button(Tr.t("materials.retry"), Button.Style.SECONDARY, this::refresh);
        this.resetAllButton = new Button(Tr.t("materials.reset_all"), Button.Style.PRIMARY, this::revertAll)
                .accent(Ui.C_CHANGED).glyph(Paint.RESET_ICON).tooltip(Tr.t("materials.reset_all.tip"));

        for (ExportFormat format : ExportFormat.values())
        {
            Button button = new Button(format.label(), format.primary() ? Button.Style.PRIMARY : Button.Style.SECONDARY,
                    () -> this.export(format));

            if (format == ExportFormat.XLSX)
            {
                button.accent(Theme.GREEN);
            }

            this.exportButtons.put(format, button);
        }

        this.more = new Button(Tr.t("materials.export.more"), Button.Style.SECONDARY, this::openMore)
                .trailing(Glyphs.CHEVRON_DOWN).tooltip(Tr.t("materials.export.more.tip"));
    }

    /** Ширина колонки-числа: заголовок со стрелкой сортировки или семизначное число — что шире. */
    private static int numberColumn(Font font, String title)
    {
        int header = font.width(title) + Glyphs.width(Glyphs.SORT_DESC) + Ui.PAD_MICRO + Ui.PAD_IN * 2;
        int digits = font.width(Tr.num(9_999_999L)) + Ui.PAD_IN * 2;

        return Math.max(NUMBER_COL_MIN, Math.max(header, digits));
    }

    // ===== Источник =====

    /** Источник для таблицы: файл схемы или размещение (одно из двух; оба {@code null} — список HUD). */
    void open(@Nullable SchematicEntry entry, @Nullable SchematicPlacement placement)
    {
        TableSource next = placement != null ? new TableSource.Placed(placement)
                : entry != null ? new TableSource.File(entry)
                : new TableSource.Hud();

        this.seenSelection = this.screen.selected();
        this.show(next, false);
    }

    @Nullable SchematicEntry entry()
    {
        return this.source instanceof TableSource.File file ? file.entry() : null;
    }

    @Nullable SchematicPlacement placement()
    {
        return this.source instanceof TableSource.Placed placed ? placed.placement() : null;
    }

    /** «Обновить»: перечитать схему, инвентарь и сундуки (экран может звать по F5 / кнопке шапки). */
    /** Анализ выделенной области — строками таблицы (вкладка «Выделение»). */
    void openArea(TableSource.Area area)
    {
        this.show(area, false);
    }

    void refresh()
    {
        if (this.source != null)
        {
            this.show(this.source, true);
        }
    }

    private void show(TableSource next, boolean recount)
    {
        if (this.job != null)
        {
            this.job.cancel();
        }

        boolean same = sameSource(next, this.source);
        this.source = next;
        this.syncCounter(next, same, recount);

        // Другая схема — старые строки чужие; тот же источник (пересчёт) — пусть стоят до новых
        if (! same)
        {
            this.lines = List.of();
            this.table.body().setScroll(0);
            this.applyFilter(false);
        }

        if (next.schematic())
        {
            this.lastSchematic = next;
        }

        this.hudEntries = next instanceof TableSource.Hud ? LayerCountManager.getInstance().getAllEntries() : null;
        this.hudCheckedAt = Util.getMillis();
        this.jobApplied = false;
        this.job = MaterialsJob.start(next, recount);
        this.pollJob();
    }

    /** Тот же источник: файл — по пути (после пересохранения запись другая), размещение — тот же объект. */
    private static boolean sameSource(TableSource next, @Nullable TableSource current)
    {
        if (current == null)
        {
            return false;
        }

        return switch (next)
        {
            case TableSource.File file -> current instanceof TableSource.File other
                    && file.entry().path().equals(other.entry().path());
            case TableSource.Placed placed -> current instanceof TableSource.Placed other
                    && placed.placement() == other.placement();
            case TableSource.Hud hud -> current instanceof TableSource.Hud;
            case TableSource.Area area -> current == area;
        };
    }

    private void showSchematic()
    {
        if (this.source != null && this.source.schematic())
        {
            return;
        }

        TableSource target = this.schematicTarget();

        if (target != null)
        {
            this.show(target, false);
            return;
        }

        // Несколько схем в мире и ни одна не выбрана — спросить, какую
        List<ContextMenu.Item> items = new ArrayList<>();

        for (SchematicChoice.Option option : this.choices())
        {
            items.add(ContextMenu.Item.of(option.label(), () -> this.show(option.source(), false)));
        }

        if (items.size() > 1)
        {
            ContextMenu.below(this.screen.layers, this.schematicButton.x(), this.schematicButton.y(),
                    this.schematicButton.w(), this.schematicButton.h(), items);
        }
    }

    /**
     * Какую схему покажет кнопка «Схема»: открытую, последнюю открытую, выбранную в «Библиотеке», иначе
     * определимую по миру (выбранное в Litematica размещение, единственное размещение или загруженная
     * схема, 0.0.27); {@code null} — схем несколько (кнопка предложит выбрать) или нет ни одной.
     */
    private @Nullable TableSource schematicTarget()
    {
        if (this.source != null && this.source.schematic())
        {
            return this.source;
        }

        if (this.lastSchematic != null)
        {
            return this.lastSchematic;
        }

        SchematicEntry selected = this.screen.selected();

        if (selected != null)
        {
            return new TableSource.File(selected);
        }

        return SchematicChoice.single(this.choices());
    }

    private List<SchematicChoice.Option> choices()
    {
        long now = Util.getMillis();

        if (! this.choicesRead || now - this.choicesAt >= HUD_POLL_MS || now < this.choicesAt)
        {
            this.choicesRead = true;
            this.choicesAt = now;
            this.choices = SchematicChoice.options();
        }

        return this.choices;
    }

    private String sourceLabel()
    {
        if (this.source instanceof TableSource.Area area)
        {
            return Tr.f("materials.source.area", area.name());
        }

        if (this.source == null || ! this.source.schematic())
        {
            return Tr.t("materials.source.hud");
        }

        return Tr.f("materials.source.schematic", this.source.name());
    }

    @Override
    void tick()
    {
        this.pollJob();

        if (this.screen.tab() != GuiSchematics.Tab.MATERIALS)
        {
            return;
        }

        SchematicEntry selected = this.screen.selected();

        // Вкладку открыли щелчком по строке подвкладок, а не кнопкой «Материалы»
        if (this.source == null)
        {
            this.seenSelection = selected;
            this.show(selected != null ? new TableSource.File(selected) : new TableSource.Hud(), false);
            return;
        }

        // В «Библиотеке» выбрали другую схему — показываем её; явный выбор «Списка HUD» не трогаем
        if (selected != null && ! Objects.equals(selected, this.seenSelection))
        {
            this.seenSelection = selected;
            TableSource next = new TableSource.File(selected);

            if (this.source instanceof TableSource.Hud)
            {
                this.lastSchematic = next;
            }
            else if (! sameSource(next, this.source))
            {
                this.show(next, false);
                return;
            }
        }

        this.pollHud();
        this.pollCounter();
    }

    private void pollJob()
    {
        MaterialsJob current = this.job;

        if (current == null || current.running() || this.jobApplied)
        {
            return;
        }

        this.jobApplied = true;
        this.source = current.source();

        if (this.source.schematic())
        {
            this.lastSchematic = this.source;
        }

        if (current.stage() == MaterialsJob.Stage.DONE)
        {
            this.schematic = current.schematic();
            this.fileRows = current.rows();

            // Сверка с миром: строки соберёт тик, когда у счётчика будут данные; без счётчика — файл целиком
            if (this.counter == null)
            {
                this.setRows(this.fileRows, false);
            }
        }
        else
        {
            this.schematic = null;
            this.rowStates = Map.of();
            this.lines = List.of();
            this.applyFilter(false);
        }
    }

    /** Список HUD живёт своей жизнью: пересчитался — перечитать (не чаще раза в секунду). */
    private void pollHud()
    {
        if (! (this.source instanceof TableSource.Hud) || this.job == null || this.job.running())
        {
            return;
        }

        long now = Util.getMillis();

        if (now - this.hudCheckedAt < HUD_POLL_MS)
        {
            return;
        }

        this.hudCheckedAt = now;

        if (LayerCountManager.getInstance().getAllEntries() != this.hudEntries)
        {
            this.show(this.source, false);
        }
    }

    // ===== Сверка с миром =====

    /** Какие размещения считать для источника; {@code null} — источник не схема (список HUD, область). */
    private static @Nullable Predicate<SchematicPlacement> matcher(TableSource source)
    {
        return switch (source)
        {
            case TableSource.Placed placed -> placement -> placement == placed.placement();
            case TableSource.File file ->
            {
                Path want = normalized(file.entry().path());
                yield placement -> want != null && want.equals(normalized(placement.getSchematicFile()));
            }
            case TableSource.Hud hud -> null;
            case TableSource.Area area -> null;
        };
    }

    private static @Nullable Path normalized(@Nullable Path path)
    {
        try
        {
            return path == null ? null : path.toAbsolutePath().normalize();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * В мире есть размещение, подходящее под {@code matcher}, и в нём есть что считать (включённые подобласти).
     * Выключенное в Litematica (голограмма скрыта) — тоже: сверка идёт по файлу схемы, а не по голограмме.
     */
    private static boolean anyPlaced(Predicate<SchematicPlacement> matcher)
    {
        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                if (matcher.test(placement) && ! placement.getEnabledRelativeSubRegionPlacements().isEmpty())
                {
                    return true;
                }
            }
        }
        catch (RuntimeException e)
        {
            return false;
        }

        return false;
    }

    /**
     * Показан источник: завести или оставить счётчик размещений, либо отметить схему неразмещённой. Тот же источник
     * (пересчёт, правка) счётчик не пересоздаёт — только просит пересчитать и ждёт свежий список.
     */
    private void syncCounter(TableSource next, boolean same, boolean recount)
    {
        Predicate<SchematicPlacement> matcher = matcher(next);

        if (! same || recount)
        {
            this.counterGaveUp = false;
        }

        boolean placed = matcher != null && anyPlaced(matcher) && ! this.counterGaveUp;
        this.unplaced = matcher != null && ! placed;

        if (! placed)
        {
            this.dropCounter();
            return;
        }

        this.counterTicks = 0;

        if (this.counter != null && this.counterSource != null && sameSource(next, this.counterSource))
        {
            // Правка или «Обновить»: счёт пересчитать, прежние строки стоят до нового списка
            this.counter.markDirty();
            this.counterStale = this.counter.getEntries();
            this.counterAwait = true;
            this.counterAwaitSince = Util.getMillis();
            return;
        }

        this.dropCounter();
        this.counter = LayerCountManager.forPlacements(matcher);
        this.counterSource = next;
        this.counterAwait = true;
        this.counterAwaitSince = Util.getMillis();

        // Замены файла — и в схеме размещения, по которой считает счётчик (она может быть другим объектом, чем файл)
        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                if (matcher.test(placement) && placement.getSchematic() != null)
                {
                    BlockReplacements.ensure(placement.getSchematic());
                }
            }
        }
        catch (RuntimeException e)
        {
            // Схема размещения не читается — счётчик сам покажет, что считать нечего
        }
    }

    private void dropCounter()
    {
        if (this.counter != null)
        {
            // Дальний кэш проверки — на диск, счёт — в сторону
            this.counter.reset();
        }

        this.counter = null;
        this.counterSource = null;
        this.counterEntries = null;
        this.counterStale = null;
        this.counterAwait = false;
    }

    /**
     * Каждый тик: тикает счётчик размещений; раз в секунду проверяет, что размещения те же (схему поставили или
     * убрали — таблица переключается между сверкой и файлом целиком); пересобирает строки по свежему списку.
     */
    private void pollCounter()
    {
        long now = Util.getMillis();

        if ((now - this.placementsCheckedAt >= HUD_POLL_MS || now < this.placementsCheckedAt) && this.source != null)
        {
            this.placementsCheckedAt = now;
            Predicate<SchematicPlacement> matcher = matcher(this.source);

            // Сдались (считать было нечего) — раз в GIVE_UP_RETRY_MS пробуем снова: размещение могли поправить
            if (this.counterGaveUp && now - this.counterGaveUpAt >= GIVE_UP_RETRY_MS)
            {
                this.counterGaveUp = false;
            }

            if (matcher != null && ! this.counterGaveUp && (anyPlaced(matcher) != (this.counter != null)))
            {
                this.show(this.source, false);
                return;
            }
        }

        LayerCountManager current = this.counter;
        MaterialsJob job = this.job;

        if (current == null)
        {
            return;
        }

        current.onClientTick(Minecraft.getInstance());
        ++this.counterTicks;

        // Строкам нужна схема из загрузки (замены, голограммы): ждём, пока загрузка файла дойдёт до конца
        if (job == null || ! this.jobApplied || job.stage() != MaterialsJob.Stage.DONE)
        {
            return;
        }

        if (current.hasData())
        {
            List<MaterialListEntry> entries = current.getEntries();
            boolean fresh = this.counterAwait
                    ? entries != this.counterStale || now - this.counterAwaitSince > COUNTER_AWAIT_MS
                    : entries != this.counterEntries && (this.counterEntries == null || now - this.counterCheckedAt >= HUD_POLL_MS);

            if (fresh)
            {
                this.counterEntries = entries;
                this.counterStale = null;
                this.counterAwait = false;
                this.counterCheckedAt = now;
                this.setRows(SchematicMaterials.ofCounter(current), true);
            }
        }
        else if (this.counterTicks > GIVE_UP_TICKS && ! current.isCounting())
        {
            // Размещение есть, а считать нечего (схема пуста, мир не тот): как у неразмещённой — весь файл
            this.counterGaveUp = true;
            this.counterGaveUpAt = now;
            this.dropCounter();
            this.unplaced = true;
            this.setRows(this.fileRows, false);
        }
    }

    private void setRows(List<MaterialRow> rows, boolean verified)
    {
        this.verifiedRows = verified;
        MaterialLine selected = this.table.selectedItem();
        String selectedKey = this.selectAfter != null ? this.selectAfter : selected == null ? null : selected.key();
        this.selectAfter = null;
        boolean hud = this.source instanceof TableSource.Hud;

        if (! hud && this.schematic != null)
        {
            rows = withLayerOnlyRows(this.schematic, rows);
        }

        List<MaterialLine> built = new ArrayList<>(rows.size());
        List<LitematicaSchematic> hudSchematics = hud ? BlockReplacements.hudSchematics() : List.of();
        this.hudSchematics = hudSchematics;
        this.rowStates = hud ? BlockReplacements.hudRows(rows, hudSchematics)
                : BlockReplacements.rows(this.schematic, rows, false);

        for (MaterialRow row : rows)
        {
            BlockReplacements.RowState state = this.rowStates.get(row.key());
            List<String> from = new ArrayList<>();
            List<Integer> layers = new ArrayList<>();

            if (state != null)
            {
                state.replacedFrom().forEach(block -> from.add(ReplacementStates.name(block)));
                state.layersOff().forEach(layer -> layers.add(layer + 1));
            }

            built.add(MaterialLine.of(row, hud, verified, Tr::num, from, layers));
        }

        // Отключённые блоки схемы: в счёте их больше нет (стали воздухом). Строка остаётся на своём месте —
        // с тем же ключом и счётом, потухшая, с золотой кнопкой «включить»
        Map<String, SchematicMaterials.DisabledRow> disabled = new HashMap<>();

        if (! hud && this.schematic != null)
        {
            List<String> keys = new ArrayList<>(built.size());
            built.forEach(line -> keys.add(line.key()));

            for (SchematicMaterials.DisabledRow off : SchematicMaterials.disabledRows(this.schematic))
            {
                MaterialRow row = off.row();

                // Тот же предмет и среди включённых (в него заменили другой блок) — отдельной строкой рядом
                if (keys.contains(row.key()))
                {
                    row = new MaterialRow(row.stack(), DISABLED_KEY + row.key(), row.name(), row.total(), row.missing(),
                            row.inInventory(), row.inChests(), row.maxStack(), row.entity());
                }

                built.add(MaterialLine.disabled(row, Tr::num, Tr.t("materials.disabled.label")));
                disabled.put(row.key(), off);
            }

            // Порядок счёта схемы («больше всего — выше»): отключённая строка встаёт туда, где стояла
            built.sort(Comparator.comparingLong((MaterialLine line) -> line.row().total()).reversed()
                    .thenComparing(line -> line.row().name()));
        }

        this.disabledBlocks = Map.copyOf(disabled);
        this.hudDisabled = hud ? this.addHudDisabled(built, hudSchematics) : Map.of();

        this.lines = List.copyOf(built);
        this.applyFilter(false);

        // Пересчёт: выбранная строка остаётся выбранной (объекты новые — ищем по ключу)
        if (selectedKey != null)
        {
            for (MaterialLine line : this.table.body().items())
            {
                if (line.key().equals(selectedKey))
                {
                    this.table.setSelected(line);
                    break;
                }
            }
        }
    }

    /**
     * «Список HUD»: отключённые блоки всех схем размещений — потухшими строками в конце списка (порядок самого
     * HUD не трогаем). Один предмет в нескольких схемах — одна строка, счёт складывается. Возвращает: ключ строки
     * → по схемам исходные блоки.
     */
    private Map<String, Map<LitematicaSchematic, SchematicMaterials.DisabledRow>> addHudDisabled(List<MaterialLine> built,
            List<LitematicaSchematic> schematics)
    {
        Map<String, MaterialRow> merged = new LinkedHashMap<>();
        Map<String, Map<LitematicaSchematic, SchematicMaterials.DisabledRow>> originals = new HashMap<>();

        for (LitematicaSchematic schematic : schematics)
        {
            for (SchematicMaterials.DisabledRow off : SchematicMaterials.disabledRows(schematic))
            {
                MaterialRow row = off.row();
                MaterialRow before = merged.get(row.key());

                if (before != null)
                {
                    row = new MaterialRow(before.stack(), before.key(), before.name(), saturatedSum(before.total(), row.total()),
                            saturatedSum(before.missing(), row.missing()), before.inInventory(), before.inChests(),
                            before.maxStack(), before.entity());
                }

                merged.put(row.key(), row);
                originals.computeIfAbsent(row.key(), key -> new IdentityHashMap<>()).put(schematic, off);
            }
        }

        List<String> keys = new ArrayList<>(built.size());
        built.forEach(line -> keys.add(line.key()));
        Map<String, Map<LitematicaSchematic, SchematicMaterials.DisabledRow>> out = new HashMap<>();

        for (MaterialRow row : merged.values())
        {
            Map<LitematicaSchematic, SchematicMaterials.DisabledRow> byKey = originals.get(row.key());

            // Тот же предмет и среди включённых (его отключили только в части схем) — отдельной строкой рядом
            if (keys.contains(row.key()))
            {
                row = new MaterialRow(row.stack(), DISABLED_KEY + row.key(), row.name(), row.total(), row.missing(),
                        row.inInventory(), row.inChests(), row.maxStack(), row.entity());
            }

            built.add(MaterialLine.disabled(row, Tr::num, Tr.t("materials.disabled.label")));
            out.put(row.key(), byKey);
        }

        return out;
    }

    private static long saturatedSum(long a, long b)
    {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    private void applyFilter(boolean toTop)
    {
        this.filter = MaterialFilter.of(this.search.text(), this.missingActive(), onlyChanged && this.resetAllShown(),
                LayerListConfigs.USE_STOCK.getBooleanValue());
        this.shown = this.filter.apply(this.lines);

        // Остаток вместо «Всего» — и порядок как у «Списка HUD»: больше всего осталось — выше (если игрок не выбрал свой)
        if (this.showLeft() && sortColumn < 0)
        {
            List<MaterialLine> byLeft = new ArrayList<>(this.shown);
            byLeft.sort(Comparator.comparing((MaterialLine line) -> line.disabled())
                    .thenComparing(Comparator.comparingLong(MaterialLine::lack).reversed())
                    .thenComparing(MaterialLine.BY_NAME));
            this.shown = byLeft;
        }
        this.totals = MaterialFilter.Totals.of(this.shown, this.verifiedRows && this.filter.onlyMissing());
        this.table.setItems(this.shown);
        this.table.setEmptyText(this.emptyText());

        if (toTop)
        {
            this.table.body().setScroll(0);
        }
    }

    /** «Только недостающее» действует: включено и есть с чем сверять (схема, не размещённая в мире, — нечем). */
    private boolean missingActive()
    {
        return onlyMissing && ! this.unplaced;
    }

    /** Колонки «Всего» и «Разбивка» показывают остаток (сколько ещё поставить): сверенная схема и «Только недостающее». */
    private boolean showLeft()
    {
        return this.verifiedRows && this.missingActive();
    }

    private String emptyText()
    {
        if (this.lines.isEmpty())
        {
            return Tr.t(this.source instanceof TableSource.Hud ? "materials.empty.hud" : "materials.empty.schematic");
        }

        if (! this.filter.words().isEmpty())
        {
            return Tr.f("materials.empty.search", this.search.text().strip());
        }

        if (this.filter.onlyChanged())
        {
            return Tr.t("materials.empty.changed");
        }

        if (this.verifiedRows && this.filter.onlyMissing() && ! this.filter.useStock())
        {
            return Tr.t("materials.empty.placed");
        }

        return Tr.t("materials.empty.covered");
    }

    // ===== Подсказки ячеек =====

    private String itemTip(MaterialLine line)
    {
        String tip = line.name() + "\n" + line.key();

        if (line.row().entity())
        {
            tip += "\n" + Tr.t("materials.cell.entity");
        }

        if (line.disabled())
        {
            return tip + "\n" + Tr.t(this.source instanceof TableSource.Hud ? "materials.disabled.cell.hud"
                    : "materials.disabled.cell");
        }

        if (! line.replacedFrom().isEmpty())
        {
            tip += "\n" + Tr.f("materials.replace.cell.replaced", String.join(", ", line.replacedFrom()), line.name());
        }

        if (! line.layersOff().isEmpty())
        {
            tip += "\n" + Tr.f(line.layersOff().size() == 1 ? "materials.layer.cell.one" : "materials.layer.cell",
                    layersText(line.layersOff()));
        }

        BlockReplacements.RowState state = this.rowState(line);
        return tip + "\n" + (state.replaceable() ? Tr.t("materials.replace.cell.hint") : state.reason(line.name()));
    }

    private BlockReplacements.RowState rowState(MaterialLine line)
    {
        BlockReplacements.RowState state = this.rowStates.get(line.key());
        return state != null ? state : new BlockReplacements.RowState(BlockReplacements.Refusal.NO_SCHEMATIC, List.of());
    }

    // ===== Замена блоков =====

    /** Иконка предмета; у отключённого блока — притушена фоном панели, как выключенный пункт. */
    private static void renderIconCell(GuiContext ctx, MaterialLine line, int x, int y, int w, int h, boolean hovered,
            int mouseX, int mouseY)
    {
        int ix = x + Ui.PAD_IN;
        int iy = y + (h - ICON) / 2;
        ctx.renderItem(line.stack(), ix, iy);

        if (line.disabled())
        {
            ctx.nextStratum();
            Paint.rect(ctx, ix, iy, ICON, ICON, Ui.alpha(Ui.C_PANEL, DISABLED_ICON_SHADE));
        }
    }

    private void renderItemCell(GuiContext ctx, MaterialLine line, int x, int y, int w, int h, boolean hovered,
            int mouseX, int mouseY)
    {
        boolean replaceable = this.rowState(line).replaceable();
        String layers = line.layersOff().isEmpty() ? null : layersText(line.layersOff());
        String revertTip = line.disabled() ? Tr.f("materials.disabled.enable.tip", line.name())
                : ! line.replacedFrom().isEmpty() ? Tr.f("materials.replace.revert.tip", String.join(", ", line.replacedFrom()))
                : layers != null ? Tr.f(line.layersOff().size() == 1 ? "materials.layer.revert.tip.one"
                        : "materials.layer.revert.tip", line.name(), layers)
                : null;

        this.replaceCell.render(ctx, line, replaceable, x, y, w, h, hovered, mouseX, mouseY,
                hovered ? this.itemTip(line) : null, revertTip,
                layers == null ? null : Tr.f(line.layersOff().size() == 1 ? "materials.layer.note.one" : "materials.layer.note",
                        layers));
    }

    /**
     * Блок, отключённый на всех слоях, где он был, в счёте пропадает целиком — а с ним пропала бы и строка с
     * золотой кнопкой. Такой блок остаётся строкой со счётом 0: пометка слоёв и кнопка на месте.
     */
    private static List<MaterialRow> withLayerOnlyRows(LitematicaSchematic schematic, List<MaterialRow> rows)
    {
        Map<Block, java.util.Set<Integer>> layers = BlockReplacements.layersOff(schematic);

        if (layers.isEmpty())
        {
            return rows;
        }

        Map<Block, Block> active = BlockReplacements.active(schematic);
        java.util.Set<net.minecraft.world.item.Item> shown = new java.util.HashSet<>();
        rows.forEach(row -> shown.add(row.stack().getItem()));
        List<MaterialRow> out = new ArrayList<>(rows);

        for (Block original : layers.keySet())
        {
            net.minecraft.world.item.Item item = BlockReplacements.mainRowItem(active.getOrDefault(original, original));

            if (item == net.minecraft.world.item.Items.AIR || ! shown.add(item))
            {
                continue;
            }

            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            out.add(new MaterialRow(stack, com.aell.layerlist.schematics.materials.ItemKeys.of(stack),
                    stack.getHoverName().getString(), 0L, 0L, 0L, 0L, stack.getMaxStackSize(), false));
        }

        return out;
    }

    /** Слои для подписи: 1 — нижний, подряд идущие — диапазоном («3–5, 8»). */
    static String layersText(List<Integer> layers)
    {
        List<String> parts = new ArrayList<>();
        int i = 0;

        while (i < layers.size())
        {
            int start = layers.get(i);
            int end = start;

            while (i + 1 < layers.size() && layers.get(i + 1) == end + 1)
            {
                end = layers.get(++i);
            }

            parts.add(start == end ? String.valueOf(start) : start + "–" + end);
            ++i;
        }

        return String.join(", ", parts);
    }

    /** ЛКМ по иконке или названию: кнопка отката — откат, иначе выбор блока. Строка при этом выбирается. */
    private boolean itemClick(MaterialLine line, MouseButtonEvent click)
    {
        if (! Kit.left(click))
        {
            return false;
        }

        this.search.setFocused(false);
        this.table.setFocused(true);
        this.table.setSelected(line);

        if (this.replaceCell.overRevert(line.key(), click.x(), click.y()))
        {
            // Кнопка отката — действие, со щелчком; выбор строки сам по себе без звука
            Vanilla.click();

            if (line.disabled())
            {
                this.enable(line);
            }
            else if (line.replacedFrom().isEmpty() && ! line.layersOff().isEmpty())
            {
                this.enableAllLayers(line);
            }
            else
            {
                this.revertRow(line);
            }
        }
        else if (! line.disabled())
        {
            // Ячейка блока — как плитка: щелчок и выбор блока на замену
            Vanilla.click();
            this.openPicker(line);
        }

        return true;
    }

    private boolean busy()
    {
        MaterialsJob current = this.job;
        return current != null && current.running() || this.counterPending();
    }

    /**
     * Строк сверки ещё нет: счётчик только создан. После правки — не «занято»: прежние строки стоят до свежего
     * списка, а следующую правку можно делать сразу (иначе отключение нескольких строк подряд упиралось бы в
     * «Дождитесь окончания подсчёта» на каждом пересчёте).
     */
    private boolean counterPending()
    {
        MaterialsJob current = this.job;

        return this.counter != null && this.counterEntries == null
                && (current == null || current.stage() != MaterialsJob.Stage.FAILED);
    }

    /** Выбор блока на замену строки; нельзя — тост с причиной. */
    private void openPicker(MaterialLine line)
    {
        if (line.disabled())
        {
            this.enable(line);
            return;
        }

        if (this.busy())
        {
            this.screen.toast(Tr.t("materials.replace.why.busy"), Ui.C_TEXT_2);
            return;
        }

        BlockReplacements.RowState state = this.rowState(line);

        if (! state.replaceable())
        {
            this.screen.toast(state.reason(line.name()), Ui.C_WARN);
            return;
        }

        Block current = line.stack().getItem() instanceof BlockItem item ? item.getBlock() : null;
        BlockPicker.open(this.screen.layers, new BlockPicker.Request(line.stack(), line.name(), line.breakdownText(), current,
                block -> this.replaceRow(line, block)));
    }

    private void replaceRow(MaterialLine line, Block target)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null)
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.replace(current, line.stack().getItem(), target);

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.replace.toast.done", line.name(), ReplacementStates.name(target)), Theme.GREEN);
        this.selectAfter = ItemKeys.of(new ItemStack(target.asItem()));
        this.refreshAfterEdit();
    }

    /** Кнопка отката: все исходные блоки, что сейчас стоят этой строкой. */
    private void revertRow(MaterialLine line)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null)
        {
            return;
        }

        this.revertDone(BlockReplacements.revert(current, line.stack().getItem()));
    }

    private void revertOne(Block original)
    {
        LitematicaSchematic current = this.schematic;

        if (current != null)
        {
            this.revertDone(BlockReplacements.revertOne(current, original));
        }
    }

    private void revertDone(BlockReplacements.Outcome outcome)
    {
        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        List<String> names = new ArrayList<>();
        outcome.changed().forEach(block -> names.add(ReplacementStates.name(block)));
        this.screen.toast(Tr.f("materials.replace.toast.reverted", String.join(", ", names)), Theme.GREEN);

        Block first = outcome.changed().getFirst();
        this.selectAfter = ItemKeys.of(new ItemStack(first.asItem()));
        this.refreshAfterEdit();
    }

    /** Отключить блок строки: в схеме он станет воздухом (голограммы, список, Easy Place). */
    private void disableRow(MaterialLine line)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null || this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disable(current, line.stack().getItem());

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.disabled.toast.off", line.name()), Ui.C_CHANGED);
        this.selectAfter = line.key();
        this.refreshAfterEdit();
    }

    /** «Список HUD»: отключить блок строки во всех схемах включённых размещений, где он есть. */
    private void disableHud(MaterialLine line)
    {
        if (this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disableAll(this.hudSchematics, line.row());

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.disabled.toast.off.hud", line.name()), Ui.C_CHANGED);
        this.selectAfter = line.key();
        this.refreshAfterEdit();
    }

    /** «Отключить на слое…»: диалог с номером слоя, затем блок строки — воздух на этом слое. */
    private void disableLayer(MaterialLine line)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null || this.busy())
        {
            return;
        }

        LayerDisableDialog.open(this.screen.layers, current, line, layer -> this.applyDisableLayer(current, line, layer));
    }

    /** Отключить блок строки на слое (0 — нижний): из окна «Отключить на слое…» и пунктом «на текущем слое». */
    private void applyDisableLayer(LitematicaSchematic current, MaterialLine line, int layer)
    {
        if (this.schematic != current || this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disableLayer(current, line.stack().getItem(), layer);

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.layer.toast.off", line.name(), layer + 1), Ui.C_CHANGED);
        this.selectAfter = line.key();
        this.refreshAfterEdit();
    }

    /** Вернуть блок строки на один слой (0 — нижний). */
    private void enableLayer(MaterialLine line, int layer)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null || this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.enableLayer(current, this.rowState(line).layerOriginals(), layer);

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.layer.toast.on", line.name(), layer + 1), Theme.GREEN);
        this.selectAfter = line.key();
        this.refreshAfterEdit();
    }

    /** Вернуть блок строки на все слои, где он отключён (золотая кнопка такой строки). */
    private void enableAllLayers(MaterialLine line)
    {
        LitematicaSchematic current = this.schematic;

        if (current == null || this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.enableLayers(current, this.rowState(line).layerOriginals());

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.layer.toast.on_all", line.name()), Theme.GREEN);
        this.selectAfter = line.key();
        this.refreshAfterEdit();
    }

    /** Золотая кнопка отключённой строки: блок обратно в схему. */
    private void enable(MaterialLine line)
    {
        boolean hud = this.source instanceof TableSource.Hud;
        LitematicaSchematic current = this.schematic;
        SchematicMaterials.DisabledRow originals = this.disabledBlocks.get(line.key());
        Map<LitematicaSchematic, SchematicMaterials.DisabledRow> hudOriginals = this.hudDisabled.get(line.key());

        if (this.busy() || (hud ? hudOriginals == null : current == null || originals == null))
        {
            return;
        }

        // Блоки и сущности строки — одной правкой (камень в стене и в рамках — одна строка)
        BlockReplacements.Outcome outcome = hud ? BlockReplacements.enableRows(hudOriginals)
                : BlockReplacements.enable(current, originals.originals(), originals.entities());

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.disabled.toast.on", line.name()), Theme.GREEN);
        this.selectAfter = line.key().startsWith(DISABLED_KEY) ? line.key().substring(DISABLED_KEY.length()) : line.key();
        this.refreshAfterEdit();
    }

    /** «Сброс»: все замены и отключения — к исходному файлу. */
    private void revertAll()
    {
        LitematicaSchematic current = this.schematic;

        if (current == null || this.busy())
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.revertAll(current);

        if (! outcome.ok())
        {
            this.screen.toast(Tr.f("materials.replace.toast.failed", outcome.error()), Ui.C_WARN);
            return;
        }

        this.screen.toast(Tr.f("materials.reset_all.toast", Tr.num(outcome.changed().size())), Theme.GREEN);
        this.refreshAfterEdit();
    }

    /** Кнопка «Сброс» (и тумблер «Изменённые») видна: таблица схемы, и в ней есть замены или отключённые блоки. */
    private boolean resetAllShown()
    {
        LitematicaSchematic current = this.schematic;

        // Есть что вернуть: замены, отключения на слоях, отключённые сущности
        return current != null && ! (this.source instanceof TableSource.Hud)
                && (! BlockReplacements.active(current).isEmpty() || ! BlockReplacements.layersOff(current).isEmpty()
                        || ! BlockReplacements.disabledEntityItems(current).isEmpty());
    }

    /** Палитра схемы изменилась: строки заново (счёт схемы уже забыт заменой), старые стоят до новых. */
    private void refreshAfterEdit()
    {
        if (this.counter != null)
        {
            this.counter.markDirty();
        }

        if (this.source != null)
        {
            this.show(this.source, false);
        }
    }

    /** «1 675 шт = 26 ст. + 11 шт»; у нуля подсказки нет. */
    private static @Nullable String countTip(long count, int maxStack)
    {
        return count <= 0L ? null : Tr.f("materials.cell.count", Tr.num(count), MaterialRow.breakdown(count, maxStack));
    }

    private static @Nullable String totalTip(MaterialLine line)
    {
        String count = countTip(line.need(), line.row().maxStack());

        if (line.disabled())
        {
            return count == null ? Tr.t("materials.disabled.total") : count + "\n" + Tr.t("materials.disabled.total");
        }

        if (line.verified())
        {
            long all = line.row().total();
            String placed = Tr.f("materials.cell.placed_total", Tr.num(all), Tr.num(Math.max(0L, all - line.lack())));

            return count == null ? placed : count + "\n" + placed;
        }

        if (! line.hud())
        {
            return count;
        }

        long total = line.row().total();
        long placed = Math.max(0L, total - line.need());
        String hud = Tr.f("materials.cell.hud_total", Tr.num(total), Tr.num(placed));

        return count == null ? hud : count + "\n" + hud;
    }

    private static String chestsTip(MaterialLine line)
    {
        String count = countTip(line.chests(), line.row().maxStack());
        return count == null ? Availability.chestsHint() : count + "\n" + Availability.chestsHint();
    }

    private static String missingTip(MaterialLine line)
    {
        return Tr.f("materials.cell.missing", Tr.num(line.lack()), Tr.num(line.inventory()), Tr.num(line.chests()),
                line.shortageText());
    }

    // ===== Раскладка и отрисовка =====

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);
        this.layoutToolbar();

        int top = this.toolbarBottom() + Ui.PAD_IN;
        this.table.setBounds(x + Ui.OUTER, top, Math.max(0, w - Ui.OUTER * 2), Math.max(0, y + h - top - Ui.PAD_IN));
    }

    private int toolbarY()
    {
        return this.y + Ui.PAD_IN;
    }

    private int toolbarBottom()
    {
        return this.toolbarY() + Ui.CTRL_H + Ui.PAD_IN;
    }

    /** Полоса инструментов: источник слева, поиск посередине, тумблер и «Пересчитать» справа. */
    private void layoutToolbar()
    {
        Font font = Kit.font();
        int rowY = this.toolbarY();
        int left = this.x + Ui.OUTER;
        int right = this.x + this.w - Ui.OUTER;

        TableSource target = this.schematicTarget();
        boolean onSchematic = this.source != null && this.source.schematic();
        // Без схемы, но с несколькими в мире — «Схема ▾» со списком; серая — только когда схемы нет вовсе
        boolean pick = target == null && this.choices().size() > 1;
        this.schematicButton.label(target == null ? Tr.t("materials.source.pick")
                : Tr.f("materials.source.schematic", target.name()));
        this.schematicButton.trailing(pick ? Glyphs.CHEVRON_DOWN : null);
        this.schematicButton.setEnabled(target != null || pick);
        this.schematicButton.tooltip(target != null ? Tr.t("materials.source.schematic.tip")
                : pick ? Tr.t("materials.source.pick.choose.tip") : Tr.t("materials.source.pick.tip"));
        this.schematicButton.selected(onSchematic);

        // Анализ области «Выделения» — кнопка источника показывает область
        if (this.source instanceof TableSource.Area area)
        {
            this.schematicButton.label(Tr.f("materials.source.area", area.name()));
            this.schematicButton.trailing(null);
            this.schematicButton.tooltip(Tr.t("materials.source.area.tip"));
            this.schematicButton.selected(true);
        }
        this.hudButton.selected(this.source instanceof TableSource.Hud);

        // Схема не размещена: сверять не с чем — тумблер выключен, рамка и подсказка объясняют почему
        this.missingOnly.setEnabled(! this.unplaced);
        this.missingOnly.highlight(this.unplaced);
        this.missingOnly.tooltip(Tr.t(this.unplaced ? "materials.only_missing.unplaced"
                : this.verifiedRows ? "materials.only_missing.tip.placed" : "materials.only_missing.tip"));

        int iconSize = IconButton.size();
        this.refreshButton.setBounds(right - iconSize, rowY + (Ui.CTRL_H - iconSize) / 2, iconSize, iconSize);
        right -= iconSize + Ui.PAD_REL;

        int hudW = this.hudButton.preferredWidth(font);
        int schematicW = Math.min(this.schematicButton.preferredWidth(font), Math.max(SOURCE_MIN, this.w * 3 / 10));
        int toggleW = this.missingOnly.preferredWidth(font);
        boolean reset = this.resetAllShown();
        int resetW = reset ? this.resetAllButton.preferredWidth(font) : 0;
        int changedW = reset ? this.changedOnly.preferredWidth(font) : 0;
        // «Сброс» и «Изменённые» тоже просят места у подписи схемы — но не у тумблера «Только недостающее»
        int extra = reset ? resetW + changedW + Ui.PAD_GROUP * 2 : 0;
        int spare = right - left - schematicW - hudW - toggleW - Ui.PAD_GROUP * 2 - SEARCH_MIN;

        if (spare - extra < 0)
        {
            int cut = Math.min(extra - spare, Math.max(0, schematicW - SOURCE_MIN));
            schematicW -= cut;
            spare += cut;
        }

        // Тесно: сначала ужимается подпись схемы, потом подпись тумблера (полная — в подсказке)
        if (spare < 0)
        {
            int cut = Math.min(-spare, Math.max(0, schematicW - SOURCE_MIN));
            schematicW -= cut;
            spare += cut;
        }

        if (spare < 0)
        {
            toggleW = Math.max(TOGGLE_MIN, toggleW + spare);
        }

        this.schematicButton.setBounds(left, rowY, schematicW, Ui.CTRL_H);
        this.hudButton.setBounds(left + schematicW, rowY, hudW, Ui.CTRL_H);
        left += schematicW + hudW + Ui.PAD_GROUP;

        this.missingOnly.setBounds(right - toggleW, rowY, toggleW, Ui.CTRL_H);
        right -= toggleW + Ui.PAD_GROUP;

        // Тесно: сначала встаёт «Сброс», «Изменённые» — если влезает целиком (обрезанный тумблер ничего не
        // говорит); скрытые не должны ловить клики старыми границами. Порядок слева направо: «Сброс»,
        // «Изменённые», «Только недостающее» — фильтры рядом
        boolean showReset = reset && right - left - resetW - Ui.PAD_GROUP >= SEARCH_MIN;
        boolean showChanged = showReset && right - left - resetW - changedW - Ui.PAD_GROUP * 2 >= SEARCH_MIN;
        this.resetAllButton.setVisible(showReset);
        this.resetAllButton.setBounds(0, 0, 0, 0);
        this.changedOnly.setVisible(showChanged);
        this.changedOnly.setBounds(0, 0, 0, 0);

        // «С учётом склада» — рядом с «Только недостающее», если есть место; место — после «Сброса» и «Изменённых»,
        // но раньше «Считать до Y»: склад — то, что спрашивают у таблицы, высота есть ещё и в настройках HUD
        int stockW = this.stockAware.preferredWidth(font);
        int taken = (showReset ? resetW + Ui.PAD_GROUP : 0) + (showChanged ? changedW + Ui.PAD_GROUP : 0);
        boolean showStock = right - left - taken - stockW - Ui.PAD_GROUP >= SEARCH_MIN;
        this.stockAware.setVisible(showStock);
        this.stockAware.setBounds(0, 0, 0, 0);
        taken += showStock ? stockW + Ui.PAD_GROUP : 0;
        // «Считать до Y» — если осталось место; общая настройка с HUD
        int cutoffW = this.cutoff.preferredWidth(font);
        boolean showCutoff = right - left - taken - cutoffW - Ui.PAD_GROUP >= SEARCH_MIN;
        this.cutoff.setVisible(showCutoff);
        this.cutoff.setBounds(0, 0, 0, 0);

        if (showStock)
        {
            this.stockAware.setBounds(right - stockW, rowY, stockW, Ui.CTRL_H);
            right -= stockW + Ui.PAD_GROUP;
        }

        if (showCutoff)
        {
            this.cutoff.setBounds(right - cutoffW, rowY, cutoffW, Ui.CTRL_H);
            right -= cutoffW + Ui.PAD_GROUP;
        }

        if (showChanged)
        {
            this.changedOnly.setBounds(right - changedW, rowY, changedW, Ui.CTRL_H);
            right -= changedW + Ui.PAD_GROUP;
        }

        if (showReset)
        {
            this.resetAllButton.setBounds(right - resetW, rowY, resetW, Ui.CTRL_H);
            right -= resetW + Ui.PAD_GROUP;
        }

        this.search.setBounds(left, rowY, Math.max(0, right - left), Ui.CTRL_H);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        this.layoutToolbar();

        this.schematicButton.render(ctx, mouseX, mouseY);
        this.hudButton.render(ctx, mouseX, mouseY);
        this.search.render(ctx, mouseX, mouseY);
        this.missingOnly.render(ctx, mouseX, mouseY);

        if (this.stockAware.visible())
        {
            this.stockAware.render(ctx, mouseX, mouseY);
        }

        if (this.cutoff.visible())
        {
            this.cutoff.render(ctx, mouseX, mouseY);
        }

        this.changedOnly.render(ctx, mouseX, mouseY);
        this.resetAllButton.render(ctx, mouseX, mouseY);
        this.refreshButton.setEnabled(this.source != null);
        this.refreshButton.render(ctx, mouseX, mouseY);

        Paint.rect(ctx, this.x, this.toolbarBottom(), this.w, Ui.BORDER, Ui.C_DIVIDER);

        MaterialsJob current = this.job;
        boolean busy = this.busy();

        if (current != null && current.stage() == MaterialsJob.Stage.FAILED)
        {
            this.renderError(ctx, font, current.error(), mouseX, mouseY);
            return;
        }

        if (busy && this.lines.isEmpty() && current != null)
        {
            this.renderLoading(ctx, font, current);
            return;
        }

        this.replaceCell.beginFrame();
        this.table.render(ctx, mouseX, mouseY);

        if (busy)
        {
            // Пересчёт поверх старых строк: тонкая полоса по нижней кромке полосы инструментов
            progress(ctx, this.x, this.toolbarBottom() - Ui.PAD_MICRO + 1, this.w, Ui.PAD_MICRO);
        }
    }

    private void renderLoading(GuiContext ctx, Font font, MaterialsJob current)
    {
        String name = current.source().name();
        String stage = current.stage() == MaterialsJob.Stage.LOAD ? Tr.f("materials.stage.load", name)
                : Tr.f("materials.stage.count", name.isEmpty() ? this.sourceLabel() : name);
        long elapsed = current.elapsed();
        String step = elapsed >= SHOW_SECONDS_AFTER_MS
                ? Tr.f("materials.stage.step_time", current.step(), current.steps(), elapsed / 1000L)
                : Tr.f("materials.stage.step", current.step(), current.steps());

        int areaY = this.table.y();
        int areaH = this.table.h();
        int blockH = Ui.TIP_LINE + Ui.PAD_REL + Ui.TRACK_H + Ui.PAD_REL + Ui.TIP_LINE;
        int cy = areaY + Math.max(0, Math.min(areaH / 2, Ui.ROW_H * 3) - blockH / 2);
        int room = Math.max(0, this.w - Ui.PAD_GROUP * 2);
        String shown = Paint.fit(font, stage, room);

        Paint.midText(ctx, font, shown, this.x + (this.w - font.width(shown)) / 2, cy, Ui.TIP_LINE, Ui.C_TEXT_2, false);
        cy += Ui.TIP_LINE + Ui.PAD_REL;

        int barW = Math.min(BAR_W, room);
        progress(ctx, this.x + (this.w - barW) / 2, cy, barW, Ui.TRACK_H);
        cy += Ui.TRACK_H + Ui.PAD_REL;

        Paint.midText(ctx, font, step, this.x + (this.w - font.width(step)) / 2, cy, Ui.TIP_LINE, Ui.C_TEXT_3, false);
    }

    private void renderError(GuiContext ctx, Font font, String message, int mouseX, int mouseY)
    {
        int room = Math.max(0, Math.min(this.w - Ui.PAD_GROUP * 2, Ui.TIP_MAX_W * 2));
        List<String> wrapped = Kit.wrap(font, message.isEmpty() ? "?" : message, room);
        int cy = this.table.y() + Math.min(this.table.h() / 2, Ui.ROW_H * 2);

        for (String line : wrapped)
        {
            Paint.midText(ctx, font, line, this.x + (this.w - font.width(line)) / 2, cy, Ui.TIP_LINE, Ui.C_DANGER, false);
            cy += Ui.TIP_LINE;
        }

        int retryW = this.retry.preferredWidth(font);
        this.retry.setBounds(this.x + (this.w - retryW) / 2, cy + Ui.PAD_REL, retryW, Ui.BUTTON_H);
        this.retry.setEnabled(this.source != null);
        this.retry.render(ctx, mouseX, mouseY);
    }

    /** Полоса без процента (счёт M25 его не сообщает): бегущий отрезок синего — «идёт работа». */
    private static void progress(GuiContext ctx, int x, int y, int w, int h)
    {
        if (w <= 0 || h <= 0)
        {
            return;
        }

        Paint.round(ctx, x, y, w, h, Math.min(Ui.R_BAR, h / 2), Ui.C_TRACK);

        int segment = Math.max(h, w / 3);
        long phase = Util.getMillis() % BAR_PERIOD_MS;
        int start = x - segment + (int) ((long) (w + segment) * phase / BAR_PERIOD_MS);
        int from = Math.max(x, start);
        int to = Math.min(x + w, start + segment);

        if (to > from)
        {
            Paint.round(ctx, from, y, to - from, h, Math.min(Ui.R_BAR, h / 2), Theme.BLUE);
        }
    }

    // ===== Подвал =====

    @Override
    String footerStatus()
    {
        boolean busy = this.busy();

        if (busy && this.lines.isEmpty())
        {
            return Tr.t("materials.status.counting");
        }

        if (this.lines.isEmpty())
        {
            return "";
        }

        MaterialFilter.Totals sum = this.totals;
        String shulkers = this.decimal(sum.shulkers());
        String text = this.filter.active()
                ? Tr.f("materials.totals_filtered", Tr.num(sum.positions()), Tr.num(this.positions()),
                        Tr.num(sum.blocks()), shulkers)
                : Tr.f("materials.totals", Tr.num(sum.positions()), Tr.num(sum.blocks()), shulkers);

        text += sum.shortage() > 0L ? Tr.f("materials.totals_missing", Tr.num(sum.shortage()))
                : Tr.t("materials.totals_covered");

        // Сверено с миром; дальние чанки без посещения считаются недостающими целиком — предупредить
        if (this.verifiedRows && this.counter != null)
        {
            text += Tr.t("materials.totals_verified");
            int far = this.counter.getUnverifiedChunks();

            if (far > 0)
            {
                text += Tr.f("materials.totals_unverified", Tr.num(far));
            }
        }

        return busy ? Tr.t("materials.status.recounting") + Ui.TITLE_SEPARATOR + text : text;
    }

    @Override
    @Nullable String footerStatusTip()
    {
        return this.verifiedRows && this.counter != null && ! this.lines.isEmpty() ? Tr.t("materials.totals_unverified.tip") : null;
    }

    /** Позиций стройки во всей таблице: отключённые блоки — не позиции. */
    private int positions()
    {
        return (int) this.lines.stream().filter(line -> ! line.disabled()).count();
    }

    /** Одна цифра после запятой; ненулевое меньше 0,1 не показывается нулём. */
    private String decimal(double value)
    {
        long tenths = Math.round(value * 10.0D);

        if (value > 0.0D && tenths == 0L)
        {
            tenths = 1L;
        }

        return Tr.num(tenths / 10L) + Tr.t("export.decimal") + tenths % 10L;
    }

    @Override
    List<Button> footerActions()
    {
        boolean busy = this.busy();
        // Отключённые строки в выгрузку не идут: из одних них выгружать нечего
        boolean ready = ! busy && this.shown.stream().anyMatch(line -> ! line.disabled());
        String why = busy ? Tr.t("materials.export.wait") : Tr.t("materials.export.empty");

        for (ExportFormat format : ExportFormat.values())
        {
            Button button = this.exportButtons.get(format);
            button.setEnabled(ready);
            button.tooltip(ready ? Tr.f("materials.export.button.tip", format.description()) : why);
        }

        this.more.setEnabled(ready);

        return this.fitFooter(Kit.font());
    }

    /**
     * Сколько кнопок форматов влезает в подвал рядом с «Назад» и итогами. Экран прячет то, что не
     * влезло, начиная с последней в списке (самой левой), — поэтому решаем сами и прячем хвост в
     * «Ещё ▾», чтобы ни один формат не пропал из виду. «Папка с выгрузками» — последней (левее всех
     * форматов) и всегда: место под неё вычитается сразу; если с подписью не остаётся места даже под
     * Excel, Google и «Ещё ▾», она сворачивается до значка (подпись — в подсказке).
     */
    private List<Button> fitFooter(Font font)
    {
        int least = footerWidth(font, this.exportButtons.get(ExportFormat.XLSX))
                + footerWidth(font, this.exportButtons.get(ExportFormat.GOOGLE)) + footerWidth(font, this.more);
        ExportsFolder.compact(this.folder, false);

        if (this.footerRoom(font) - footerWidth(font, this.folder) < least)
        {
            ExportsFolder.compact(this.folder, true);
        }

        int room = this.footerRoom(font) - footerWidth(font, this.folder);
        List<Button> all = new ArrayList<>();
        int need = 0;

        for (ExportFormat format : ExportFormat.values())
        {
            Button button = this.exportButtons.get(format);
            all.add(button);
            need += footerWidth(font, button);
        }

        if (need <= room)
        {
            this.overflow = List.of();
            all.add(this.folder);
            return all;
        }

        List<Button> fitted = new ArrayList<>();
        List<ExportFormat> hidden = new ArrayList<>();
        int used = footerWidth(font, this.more);

        for (ExportFormat format : ExportFormat.values())
        {
            Button button = this.exportButtons.get(format);
            int width = footerWidth(font, button);

            if (hidden.isEmpty() && used + width <= room)
            {
                fitted.add(button);
                used += width;
            }
            else
            {
                hidden.add(format);
            }
        }

        fitted.add(this.more);
        fitted.add(this.folder);
        this.overflow = List.copyOf(hidden);

        return fitted;
    }

    private static int footerWidth(Font font, Button button)
    {
        return GuiSchematics.footerButtonWidth(font, button) + Ui.PAD_REL;
    }

    /** Место под кнопки: ширина без полей, кнопки «Назад» (как её подписывает экран) и итогов. */
    private int footerRoom(Font font)
    {
        Screen parent = Nav.peek();
        String back = parent == null ? Strings.t("close") : Strings.t("back",
                parent instanceof SettingsScreen ? Strings.t("settings") : parent.getTitle().getString());
        int backW = Math.min(font.width(back) + Ui.BUTTON_PAD_X * 2, this.w / 3);
        int statusW = Math.min(font.width(this.footerStatus()), this.w * 3 / 10);

        return this.w - Ui.OUTER * 2 - backW - Ui.PAD_REL * 2 - statusW - FOOTER_SLACK;
    }

    private void openMore()
    {
        List<ContextMenu.Item> items = new ArrayList<>();

        for (ExportFormat format : this.overflow)
        {
            items.add(ContextMenu.Item.of(format.label(), () -> this.export(format)).withTooltip(format.description()));
        }

        if (! items.isEmpty())
        {
            ContextMenu.below(this.screen.layers, this.more.x(), this.more.y(), this.more.w(), this.more.h(), items);
        }
    }

    /**
     * Кнопка формата: окно выгрузки с этим форматом и строками, как они видны в таблице.
     * Контракт с окном выгрузки — {@code docs/integration/materials_ui-w2/wiring.md} §2.
     */
    private void export(ExportFormat format)
    {
        TableSource current = this.source;

        if (current == null || this.shown.stream().allMatch(MaterialLine::disabled))
        {
            return;
        }

        List<MaterialRow> rows = new ArrayList<>(this.shown.size());

        for (MaterialLine line : this.table.body().items())
        {
            // Отключённый блок схема не строит — в таблицу сбора он не идёт
            if (! line.disabled())
            {
                rows.add(line.row());
            }
        }

        boolean hud = current instanceof TableSource.Hud;
        String label = this.sourceLabel();
        // Файл — по схеме: у размещения это файл его схемы, а не имя размещения
        String fileName = switch (current)
        {
            case TableSource.File file -> ExportNames.of(file.entry());
            case TableSource.Placed placed -> ExportNames.of(placed.placement());
            case TableSource.Hud ignored -> ExportNames.hud();
            case TableSource.Area area -> area.name();
        };
        long disabledBlocks = 0L;

        for (MaterialLine line : this.lines)
        {
            if (line.disabled())
            {
                disabledBlocks = saturatedSum(disabledBlocks, line.row().total());
            }
        }

        // Сверенная схема: «только недостающее» — как в таблице, не принудительно; окно знает, что строки сверены с миром
        MaterialsExport request = new MaterialsExport(hud ? label : current.name(), label, rows, this.positions(),
                hud || this.missingActive(), format, current.entry(), fileName, hud, disabledBlocks, this.verifiedRows);

        this.openExportDialog(request);
    }

    /** Единственная точка вызова окна выгрузки: строки без пересчёта, формат уже выбран. */
    private void openExportDialog(MaterialsExport request)
    {
        ExportDialog.open(this.screen, request);
    }

    // ===== Меню строки =====

    private void openMenu(MaterialLine line, int mouseX, int mouseY)
    {
        if (line.disabled())
        {
            // В «Списке HUD» блок возвращается во все схемы, где был отключён
            ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, List.of(
                    ContextMenu.Item.of(Tr.t("materials.disabled.menu.on"), () -> this.enable(line))));
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        List<ContextMenu.Item> items = new ArrayList<>();
        BlockReplacements.RowState state = this.rowState(line);
        String replace = Tr.t("materials.replace.menu");

        if (state.replaceable() && ! this.busy())
        {
            items.add(ContextMenu.Item.of(replace, () -> this.openPicker(line)).withTooltip(Tr.t("materials.replace.cell.hint")));
        }
        else
        {
            items.add(ContextMenu.Item.disabled(replace,
                    this.busy() ? Tr.t("materials.replace.why.busy") : state.reason(line.name())));
        }

        for (Block original : state.replacedFrom())
        {
            items.add(ContextMenu.Item.of(Tr.f("materials.replace.menu.revert", ReplacementStates.name(original)),
                    () -> this.revertOne(original)));
        }

        // Отключить блок: у воды, лавы и сущностей оно есть, хоть замены нет. В «Списке HUD» — во все схемы, без слоёв
        String off = Tr.t("materials.disabled.menu.off");
        boolean hud = this.source instanceof TableSource.Hud;
        boolean water = line.stack().is(Items.WATER_BUCKET);
        // Строка целиком из сущностей: слоёв у сущностей нет, отключаются только целиком
        boolean entity = line.row().entity();

        if (state.disableable() && ! this.busy())
        {
            String tip = Tr.t(hud ? "materials.disabled.menu.off.tip.hud" : "materials.disabled.menu.off.tip");

            if (water)
            {
                tip += "\n" + Tr.t("materials.disabled.menu.off.tip.water");
            }

            if (entity || state.refusal() == BlockReplacements.Refusal.ENTITY)
            {
                tip += "\n" + Tr.t("materials.disabled.menu.off.tip.entity");
            }

            // Растение в горшке, вода в котле: клетка одна на два предмета — уйдёт целиком
            if (! hud && ! water && BlockReplacements.sharesCells(this.schematic, line.stack().getItem()))
            {
                tip += "\n" + Tr.t("materials.disabled.menu.off.tip.shared");
            }

            Runnable action = hud ? () -> this.disableHud(line) : () -> this.disableRow(line);
            items.add(ContextMenu.Item.of(off, action).withTooltip(tip));

            if (! hud && ! entity)
            {
                items.add(this.disableHereItem(line));
                items.add(ContextMenu.Item.of(Tr.t("materials.layer.menu"), () -> this.disableLayer(line))
                        .withTooltip(Tr.t("materials.layer.menu.tip")));
            }
        }
        else if (hud && ! state.disableable())
        {
            items.add(ContextMenu.Item.disabled(off, state.offReason(line.name())));
        }

        // Отключён на слоях: вернуть на каждом (до шести пунктов) и на всех разом
        List<Integer> layersOff = state.layersOff();

        if (! layersOff.isEmpty() && ! this.busy())
        {
            if (layersOff.size() <= 6)
            {
                for (int layer : layersOff)
                {
                    items.add(ContextMenu.Item.of(Tr.f("materials.layer.menu.on", layer + 1), () -> this.enableLayer(line, layer)));
                }
            }

            if (layersOff.size() > 1)
            {
                items.add(ContextMenu.Item.of(Tr.f("materials.layer.menu.on_all", layersText(line.layersOff())),
                        () -> this.enableAllLayers(line)));
            }
        }

        items.add(ContextMenu.Item.separator());
        boolean marking = StorageFind.marking(line.stack());
        String find = Tr.t(marking ? "materials.menu.find_off" : "materials.menu.find");

        if (mc.level == null || mc.player == null)
        {
            items.add(ContextMenu.Item.disabled(find, Tr.t("materials.find.no_world")));
        }
        else
        {
            String tip = Tr.t(StorageFind.followsHud() ? "materials.menu.find.tip_follow" : "materials.menu.find.tip");
            items.add(ContextMenu.Item.of(find, () -> this.find(line)).withTooltip(marking ? null : tip));
        }

        items.add(ContextMenu.Item.separator());
        items.add(ContextMenu.Item.of(Tr.t("materials.menu.copy_name"),
                () -> this.copy(line.name(), "materials.toast.name_copied")));
        items.add(ContextMenu.Item.of(Tr.t("materials.menu.copy_id"),
                () -> this.copy(line.key(), "materials.toast.id_copied")));

        ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, items);
    }

    /**
     * «Отключить на текущем слое (N)» — в один щелчок, без окна: слой тот, что показывает список HUD (Litematica в
     * режиме «один слой»). Слоя нет или блока на нём нет — пункт серый с причиной.
     */
    private ContextMenu.Item disableHereItem(MaterialLine line)
    {
        LitematicaSchematic current = this.schematic;
        int here = current == null ? 0 : LayerDisableDialog.currentLayer(current);

        if (here <= 0)
        {
            return ContextMenu.Item.disabled(Tr.t("materials.layer.menu.here.none"), Tr.t("materials.layer.menu.here.none.tip"));
        }

        String label = Tr.f("materials.layer.menu.here", here);

        if (BlockReplacements.countOnLayer(current, line.stack().getItem(), here - 1) <= 0L)
        {
            return ContextMenu.Item.disabled(label, Tr.f("materials.layer.none", here));
        }

        return ContextMenu.Item.of(label, () -> this.applyDisableLayer(current, line, here - 1))
                .withTooltip(Tr.t("materials.layer.menu.here.tip"));
    }

    private void find(MaterialLine line)
    {
        StorageFind.Result result = StorageFind.toggle(line.row());

        switch (result.outcome())
        {
            case MARKED -> this.screen.toast(Tr.f("materials.toast.find_marked", line.name(), Tr.num(result.storages())),
                    Theme.GREEN);
            case NOT_NEAR -> this.screen.toast(Tr.f("materials.toast.find_far", line.name(), Tr.num(StorageFind.radius())),
                    Ui.C_WARN);
            case NOT_KNOWN -> this.screen.toast(Tr.f("materials.toast.find_unknown", line.name()), Ui.C_WARN);
            case CLEARED -> this.screen.toast(Tr.t("materials.toast.find_off"), Ui.C_TEXT_3);
            case NO_WORLD -> this.screen.toast(Tr.t("materials.find.no_world"), Ui.C_WARN);
        }
    }

    private void copy(String text, String toastKey)
    {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        this.screen.toast(Tr.t(toastKey), Theme.GREEN);
    }

    // ===== Ввод =====

    @Override
    @Nullable TextInput focusedInput()
    {
        return this.search.focused() ? this.search : null;
    }

    private void focusTable()
    {
        this.search.setFocused(false);
        this.table.setFocused(true);

        if (this.table.selectedItem() == null && ! this.table.body().items().isEmpty())
        {
            this.table.body().setSelectedIndex(0);
        }
    }

    private void focusSearch()
    {
        this.table.setFocused(false);
        this.search.setFocused(true);
        this.search.selectAll();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.search.mouseClicked(click, doubleClick))
        {
            this.table.setFocused(false);
            return true;
        }

        for (Button button : List.of(this.schematicButton, this.hudButton, this.refreshButton, this.resetAllButton))
        {
            if (button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        if (this.missingOnly.mouseClicked(click, doubleClick) || this.changedOnly.mouseClicked(click, doubleClick)
                || this.stockAware.visible() && this.stockAware.mouseClicked(click, doubleClick)
                || this.cutoff.visible() && this.cutoff.mouseClicked(click, doubleClick))
        {
            return true;
        }

        MaterialsJob current = this.job;

        if (current != null && current.stage() == MaterialsJob.Stage.FAILED)
        {
            this.retry.mouseClicked(click, doubleClick);
            return true;
        }

        // Пока первая загрузка без строк — на месте таблицы надпись и полоса, кликать там не во что
        boolean tableShown = ! this.busy() || ! this.lines.isEmpty();

        if (tableShown && this.table.contains(click.x(), click.y()))
        {
            this.search.setFocused(false);
            this.table.setFocused(true);
            return this.table.mouseClicked(click, doubleClick);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.search.mouseDragged(click, dx, dy) || this.table.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : List.of(this.schematicButton, this.hudButton, this.refreshButton, this.retry, this.more,
                this.folder))
        {
            button.mouseReleased(click);
        }

        for (Button button : this.exportButtons.values())
        {
            button.mouseReleased(click);
        }

        return this.search.mouseReleased(click) | this.table.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.table.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        int code = key.key();

        if (code == InputConstants.KEY_F && key.hasControlDownWithQuirk())
        {
            this.focusSearch();
            return true;
        }

        if (this.search.focused())
        {
            // Из поиска стрелкой вниз или Tab — сразу к строкам
            if (code == InputConstants.KEY_DOWN || code == InputConstants.KEY_PAGEDOWN || code == InputConstants.KEY_TAB)
            {
                this.focusTable();
                return true;
            }

            return this.search.keyPressed(key);
        }

        MaterialLine selected = this.table.selectedItem();

        if (key.isCopy() && selected != null)
        {
            this.copy(selected.name(), "materials.toast.name_copied");
            return true;
        }

        if (code == InputConstants.KEY_TAB)
        {
            this.focusSearch();
            return true;
        }

        boolean navigation = code == InputConstants.KEY_UP || code == InputConstants.KEY_DOWN
                || code == InputConstants.KEY_PAGEUP || code == InputConstants.KEY_PAGEDOWN
                || code == InputConstants.KEY_HOME || code == InputConstants.KEY_END;

        if (navigation)
        {
            this.table.setFocused(true);
        }

        return this.table.keyPressed(key);
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        if (this.search.focused())
        {
            return this.search.charTyped(event);
        }

        // Буква в таблице — начало поиска, как в списке файлов
        if (event.isAllowedChatCharacter() && Character.isLetterOrDigit(event.codepoint()))
        {
            this.table.setFocused(false);
            this.search.setFocused(true);
            this.search.insert(event.codepointAsString());
            return true;
        }

        return false;
    }

    @Override
    void release()
    {
        // Экран уходит (закрыт или под редактором размещения): счёт схем держится слабо, но явная
        // очистка дешевле. Свои строки панель не бросает — при возвращении они на месте
        SchematicMaterials.clearCache();
        this.dropCounter();
    }
}
