package com.aell.ui;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

@Environment(EnvType.CLIENT)
public class SettingsScreen extends GuiBase
{
    private static final Map<String, Integer> TAB_MEMORY = new HashMap<>();
    private static final Map<String, Integer> SCROLL_MEMORY = new HashMap<>();

    private static final int BACK_MIN_W = 34;

    /**
     * Раскладка тела (22.10.2026): своя (Aell UI), плитки «Название: значение» в две колонки, как «Настройки» Minecraft
     * (Vanilla), или строки Litematica — название слева, справа плитка значения и «Сброс» (Vanilla+).
     */
    private enum Layout { AELL, GRID, ROWS }

    /** Плитка сетки: высота и шаг рядов как у списка настроек Minecraft; колонка шире ванильных 150 под длинные названия. */
    private static final int TILE_H = 20;
    private static final int TILE_PITCH = 24;
    private static final int TILE_GAP = 8;
    private static final int TILE_W_MAX = 310;

    private final Anim.Pool hoverAnim = new Anim.Pool(Ui.ANIM_HOVER_MS);
    private final ModSwitcher switcher;
    private final Anim.Pool knobAnim = new Anim.Pool(Ui.ANIM_KNOB_MS);

    protected final SettingsModel model;
    private final List<Model.Tab> tabs;

    private @Nullable String backLabelKey;
    private boolean backHighlighted;

    private Conflicts conflicts;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentW;
    private int ctrlW;
    private boolean showReset;
    private boolean showSections;
    private int tabsH;
    private int tabStripX;
    private int tabStripW;
    private int tabScroll;
    private int tabScrollMax;
    private int statusH;
    private int footerH;
    private int bodyY;
    private int bodyH;
    private int contentH;

    private final List<TabBox> tabBoxes = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    /** Раскладка, по которой построены строки: сменилась (оформление поменяли снаружи) — экран перестраивается. */
    private @Nullable Layout builtLayout;
    private final List<Row> focusRows = new ArrayList<>();

    private @Nullable Model.Status status;

    private String headerTitle = "";
    private int searchX;
    private int searchW;
    private final List<HeadBox> headBoxes = new ArrayList<>();
    private @Nullable HeadBox moreBox;
    private final List<Model.HeaderButton> hidden = new ArrayList<>();

    private int backX;
    private int backW;
    private int tabResetX;
    private int tabResetW;
    private String tabResetLabel = "";
    private int allResetX;
    private int allResetW;

    private int tab;
    private int scroll;
    private int focus = -1;
    private boolean searchFocus;
    private String search = "";

    private @Nullable Row editRow;
    private String editText = "";
    private @Nullable Row captureRow;
    private List<Integer> captureKeys = List.of();
    private @Nullable Row dragRow;
    private boolean dragScroll;
    private int dragScrollGrab;
    private @Nullable Row pressedRow;
    private boolean dropdownOpen;

    private long confirmAllUntil;
    private long blinkUntil;
    private long jumpUntil;
    private @Nullable String jumpKey;

    private long hoverSince;
    private @Nullable Object hoverTarget;

    public SettingsScreen(SettingsModel model)
    {
        this.model = model;
        this.tabs = model.tabs();
        this.title = model.modName();
        this.tab = this.contentTab(TAB_MEMORY.getOrDefault(model.modId(), 0));
        this.conflicts = Conflicts.scan(this.allKeybindOptions(), this::nameOf, model.maliModName());
        this.switcher = new ModSwitcher(model.modId());
    }

    public SettingsScreen backLabel(@Nullable String key)
    {
        this.backLabelKey = key;

        return this;
    }

    public SettingsScreen showTab(int index)
    {
        this.tab = this.contentTab(index);

        return this;
    }

    /**
     * Открыть экран на вкладке с этим ключом заголовка. Ключ, а не номер: вкладки соседних
     * модов встраиваются в общий ряд, и номер зависит от того, какие моды стоят. Ключа нет —
     * остаёмся на запомненной вкладке.
     */
    public SettingsScreen showTab(String key)
    {
        for (int i = 0; i < this.tabs.size(); ++i)
        {
            if (this.tabs.get(i).key.equals(key))
            {
                return this.showTab(i);
            }
        }

        return this;
    }

    public int tabCount()
    {
        return this.tabs.size();
    }

    public SettingsScreen highlightBack(boolean value)
    {
        this.backHighlighted = value;

        return this;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        this.conflicts = Conflicts.scan(this.allKeybindOptions(), this::nameOf, this.model.maliModName());
        this.scroll = SCROLL_MEMORY.getOrDefault(this.scrollKey(), 0);
        this.layout();
        this.showTabInStrip(this.tab);
    }

    private void layout()
    {
        this.panelW = this.width < Ui.WINDOW_NARROW || this.fullscreen()
                ? this.width
                : Math.min(this.panelWant(), this.width);
        this.panelX = 0;
        this.panelY = 0;
        this.panelH = this.height;

        this.contentX = this.panelX + Ui.OUTER;
        this.contentW = this.panelW - Ui.OUTER * 2;

        this.ctrlW = this.panelW < Ui.PANEL_TIGHT ? Ui.CTRL_W_TIGHT : Ui.CTRL_W;
        this.showReset = this.panelW >= Ui.PANEL_VERY_TIGHT;
        this.footerH = this.height < Ui.WINDOW_SHORT ? Ui.FOOTER_H_TIGHT : Ui.FOOTER_H;

        this.layoutHeader();
        this.layoutTabs();

        this.status = this.model.status();
        this.statusH = this.status == null ? 0 : Ui.STATUS_H;

        this.bodyY = this.panelY + Ui.HEADER_H + this.tabsH + this.statusH;
        this.bodyH = Math.max(0, this.panelH - Ui.HEADER_H - this.tabsH - this.statusH - this.footerH);
        this.showSections = this.bodyH >= Ui.BODY_MIN_FOR_SECTIONS;

        this.buildRows();
        this.layoutFooter();
        this.switcher.layout(this.width, this.panelX + this.panelW);
        this.clampScroll();
    }

    private int panelWant()
    {
        // Панель расширяется под ряд вкладок (с соседними модами их больше, а русские названия
        // длиннее), но не дальше двух третей окна; дальше — только полноэкранный режим
        int cap = Math.max(Ui.PANEL_MAX_W, this.width * Ui.PANEL_GROW_NUM / Ui.PANEL_GROW_DEN);

        return Math.min(Math.max(Ui.PANEL_MAX_W, this.tabsWant()), cap);
    }

    private boolean fullscreen()
    {
        Values.Bool value = this.model.fullscreen();

        return value != null && value.get();
    }

    private int tabsWant()
    {
        return this.tabs.isEmpty()
                ? 0
                : this.stripWidth(Ui.TAB_PAD_X_ROOMY, Integer.MAX_VALUE) + Ui.OUTER * 2;
    }

    private void layoutTabs()
    {
        this.tabBoxes.clear();
        this.tabsH = Ui.TABS_H;
        this.tabStripX = this.contentX;
        this.tabStripW = this.contentW;
        this.tabScrollMax = 0;

        if (this.tabs.isEmpty())
        {
            this.tabScroll = 0;

            return;
        }

        int pad = Ui.TAB_PAD_X;

        while (pad > Ui.TAB_PAD_X_MIN && this.stripWidth(pad, Integer.MAX_VALUE) > this.contentW)
        {
            --pad;
        }

        int budget = this.stripWidth(pad, Integer.MAX_VALUE) <= this.contentW
                ? Integer.MAX_VALUE
                : this.labelBudget(pad);

        int y = this.panelY + Ui.HEADER_H;
        int offset = 0;

        for (int i = 0; i < this.tabs.size(); ++i)
        {
            Model.Tab modelTab = this.tabs.get(i);
            String full = Strings.plain(modelTab.key);
            String label = Paint.fit(this.font, full, budget);
            int w = this.font.width(label) + pad * 2 + iconRoom(modelTab.icon) + opensRoom(modelTab);

            this.tabBoxes.add(new TabBox(i, label, full, offset, y, w, pad, modelTab.icon));
            offset += w;
        }

        if (offset > this.contentW)
        {
            this.tabStripX = this.contentX + Ui.TAB_ARROW_W;
            this.tabStripW = Math.max(0, this.contentW - Ui.TAB_ARROW_W * 2);
        }

        this.tabScrollMax = Math.max(0, offset - this.tabStripW);
        this.clampTabScroll();
    }

    private int stripWidth(int pad, int budget)
    {
        int total = 0;

        for (Model.Tab modelTab : this.tabs)
        {
            total += Math.min(this.font.width(Strings.plain(modelTab.key)), budget)
                    + pad * 2 + iconRoom(modelTab.icon) + opensRoom(modelTab);
        }

        return total;
    }

    private int labelBudget(int pad)
    {
        int high = 0;

        for (Model.Tab modelTab : this.tabs)
        {
            high = Math.max(high, this.font.width(Strings.plain(modelTab.key)));
        }

        int low = Ui.TAB_LABEL_MIN;
        int best = low;

        while (low <= high)
        {
            int mid = (low + high) / 2;

            if (this.stripWidth(pad, mid) <= this.contentW)
            {
                best = mid;
                low = mid + 1;
            }
            else
            {
                high = mid - 1;
            }
        }

        return best;
    }

    private int tabDrawX(TabBox box)
    {
        return this.tabStripX + box.x - this.tabScroll;
    }

    private void clampTabScroll()
    {
        this.tabScroll = Ui.clamp(this.tabScroll, 0, this.tabScrollMax);
    }

    private void showTabInStrip(int index)
    {
        if (this.tabScrollMax <= 0 || index < 0 || index >= this.tabBoxes.size())
        {
            return;
        }

        TabBox box = this.tabBoxes.get(index);

        if (box.x < this.tabScroll)
        {
            this.tabScroll = box.x;
        }
        else if (box.x + box.w > this.tabScroll + this.tabStripW)
        {
            this.tabScroll = box.x + box.w - this.tabStripW;
        }

        this.clampTabScroll();
    }

    private int tabArrowY()
    {
        return this.panelY + Ui.HEADER_H + (Ui.TABS_H - Ui.TAB_ARROW_GLYPH_H) / 2;
    }

    private void layoutHeader()
    {
        this.headBoxes.clear();
        this.hidden.clear();
        this.moreBox = null;

        List<Model.HeaderButton> all = this.model.headerButtons();
        String full = this.model.modName() + Ui.TITLE_SEPARATOR + Strings.t("settings");
        String[] variants = { full, this.model.modName(), Strings.t("settings") };

        int shown = Math.min(all.size(), Ui.HEAD_BTN_MAX);
        boolean needMore = all.size() > shown;

        for (;;)
        {
            for (String variant : variants)
            {
                for (int sw : new int[] { Ui.SEARCH_W, Ui.SEARCH_W_TIGHT })
                {
                    int titleW = this.font.width(variant) + this.iconW();
                    int need = this.buttonsWidth(all, shown, needMore);
                    int avail = this.contentW - titleW - Ui.PAD_REL - sw - (need > 0 ? Ui.HEAD_BTN_GAP : 0);

                    if (need <= avail)
                    {
                        this.headerTitle = variant;
                        this.searchW = sw;
                        this.placeHeaderButtons(all, shown, needMore, sw);

                        return;
                    }
                }
            }

            if (shown == 0)
            {
                break;
            }

            --shown;
            needMore = all.size() > shown;
        }

        this.headerTitle = variants[2];
        this.searchW = Ui.SEARCH_W_TIGHT;
        this.placeHeaderButtons(all, 0, ! all.isEmpty(), this.searchW);
    }

    private int buttonsWidth(List<Model.HeaderButton> all, int shown, boolean needMore)
    {
        int total = 0;

        for (int i = 0; i < shown; ++i)
        {
            total += this.headButtonWidth(Strings.plain(all.get(i).labelKey()));
        }

        if (needMore)
        {
            total += this.headButtonWidth(Strings.t("more"));
        }

        int count = shown + (needMore ? 1 : 0);

        return count == 0 ? 0 : total + (count - 1) * Ui.HEAD_BTN_GAP;
    }

    private int iconW()
    {
        return this.model.icon() == null ? 0 : Ui.HEADER_ICON + Ui.HEADER_ICON_GAP;
    }

    private int headButtonWidth(String label)
    {
        return this.font.width(label) + Ui.HEAD_BTN_PAD_X * 2;
    }

    private void placeHeaderButtons(List<Model.HeaderButton> all, int shown, boolean needMore, int sw)
    {
        this.searchX = this.contentX + this.contentW - sw;

        int cursor = this.searchX - Ui.HEAD_BTN_GAP;

        if (needMore)
        {
            int w = this.headButtonWidth(Strings.t("more"));
            cursor -= w;
            this.moreBox = new HeadBox(null, Strings.t("more"), cursor, w, false);
            cursor -= Ui.HEAD_BTN_GAP;

            for (int i = shown; i < all.size(); ++i)
            {
                this.hidden.add(all.get(i));
            }
        }

        for (int i = shown - 1; i >= 0; --i)
        {
            Model.HeaderButton button = all.get(i);
            String label = Strings.plain(button.labelKey());
            int w = this.headButtonWidth(label);
            cursor -= w;
            this.headBoxes.add(new HeadBox(button, label, cursor, w, button.foreign()));
            cursor -= Ui.HEAD_BTN_GAP;
        }
    }

    private void layoutFooter()
    {
        int y = this.footerTop();
        int right = this.contentX + this.contentW;

        String back = this.backLabelKey == null ? Strings.t("close")
                : Strings.t("back", Strings.plain(this.backLabelKey));
        this.backX = this.contentX;
        this.backW = this.font.width(back) + Ui.BUTTON_PAD_X * 2;

        String all = Strings.t("reset.all");
        this.allResetW = this.font.width(all) + Ui.BUTTON_PAD_X * 2;
        this.allResetX = right - this.allResetW;

        int changed = this.changedInTab(this.tab);
        String[] chain = {
                Strings.t("reset.tab", changed),
                Strings.t("reset.tab.short", changed),
                Strings.t("reset.tab.tiny", changed) };

        this.tabResetLabel = chain[0];
        this.tabResetW = this.font.width(this.tabResetLabel) + Ui.BUTTON_PAD_X * 2;

        for (String candidate : chain)
        {
            int w = this.font.width(candidate) + Ui.BUTTON_PAD_X * 2;
            int roomForBack = this.allResetX - Ui.PAD_REL - w - Ui.PAD_REL - this.backX;

            this.tabResetLabel = candidate;
            this.tabResetW = w;

            if (roomForBack >= BACK_MIN_W)
            {
                break;
            }
        }

        this.tabResetX = this.allResetX - Ui.PAD_REL - this.tabResetW;

        int minBack = Math.min(BACK_MIN_W, Math.max(0, this.contentW / 3));

        if (this.backX + minBack + Ui.PAD_REL > this.tabResetX)
        {
            int deficit = this.backX + minBack + Ui.PAD_REL - this.tabResetX;
            this.tabResetW = Math.max(Ui.BUTTON_PAD_X * 2, this.tabResetW - deficit);
            this.tabResetX = this.allResetX - Ui.PAD_REL - this.tabResetW;
        }

        this.tabResetLabel = Paint.fit(this.font, this.tabResetLabel,
                Math.max(0, this.tabResetW - Ui.BUTTON_PAD_X * 2));

        if (this.backX + this.backW > this.tabResetX - Ui.PAD_REL)
        {
            this.backW = Math.max(0, this.tabResetX - Ui.PAD_REL - this.backX);
        }

        this.footerBackLabel = Paint.fit(this.font, back, Math.max(0, this.backW - Ui.BUTTON_PAD_X * 2));
        this.footerY = y;
    }

    private int confirmAskW()
    {
        return this.font.width(Strings.t("confirm.ask")) + Ui.PAD_IN;
    }

    private int confirmYesX()
    {
        return this.confirmX() + Ui.BUTTON_PAD_X + this.confirmAskW();
    }

    private int confirmNoX()
    {
        return this.confirmYesX() + this.font.width(Strings.t("confirm.yes")) + Ui.PAD_IN;
    }

    private int confirmW()
    {
        return this.confirmAskW() + this.font.width(Strings.t("confirm.yes")) + Ui.PAD_IN
                + this.font.width(Strings.t("confirm.no")) + Ui.BUTTON_PAD_X * 2;
    }

    private int confirmX()
    {
        return this.contentX + this.contentW - Math.max(this.allResetW, this.confirmW());
    }

    private boolean confirming()
    {
        return Util.getMillis() < this.confirmAllUntil;
    }

    private String footerBackLabel = "";
    private int footerY;

    private int footerTop()
    {
        return this.panelY + this.panelH - this.footerH + (this.footerH - Ui.BUTTON_H) / 2;
    }

    private void buildRows()
    {
        this.builtLayout = layoutMode();
        this.rows.clear();
        this.focusRows.clear();

        int y = 0;

        if (! this.search.isEmpty())
        {
            String needle = this.search.toLowerCase(Locale.ROOT);

            for (Model.Tab modelTab : this.tabs)
            {
                String prefix = Strings.plain(modelTab.key);

                for (Model.Section section : modelTab.sections)
                {
                    int swatchCol = this.swatchCol(section);

                    for (Model.Option option : section.options)
                    {
                        int[] match = this.match(option, needle);

                        if (match == null)
                        {
                            continue;
                        }

                        Row row = Row.option(option, section, prefix, match[0], match[1]);
                        row.swatchCol = swatchCol;
                        row.indent = 0;
                        row.h = this.rowHeight(option);
                        row.y = y;
                        y += row.h + Ui.ROW_GAP;
                        this.rows.add(row);
                    }
                }
            }
        }
        else if (! this.tabs.isEmpty())
        {
            Model.Tab modelTab = this.tabs.get(this.tab);
            boolean first = true;

            for (Model.Section section : modelTab.sections)
            {
                if (this.showSections)
                {
                    if (! first)
                    {
                        y += Ui.SECTION_PAD_TOP;
                    }

                    Row header = Row.section(section);
                    header.y = y;
                    header.h = Ui.SECTION_H;
                    y += Ui.SECTION_H;
                    this.rows.add(header);

                    y += Ui.SECTION_PAD_BOTTOM;
                }

                int swatchCol = this.swatchCol(section);

                for (Model.Option option : section.options)
                {
                    Row row = Row.option(option, section, null, -1, 0);
                    row.swatchCol = swatchCol;
                    row.indent = option.child ? Ui.INDENT : 0;
                    row.h = this.rowHeight(option);
                    row.y = y;
                    y += row.h + Ui.ROW_GAP;
                    this.rows.add(row);
                }

                first = false;
            }
        }

        this.contentH = Math.max(0, y - Ui.ROW_GAP);

        if (layoutMode() == Layout.GRID)
        {
            this.contentH = this.gridRows();
        }

        for (Row row : this.rows)
        {
            if (row.kind == Row.Kind.OPTION && row.option != null && row.option.gate.get() == null)
            {
                this.focusRows.add(row);
            }
        }

        if (this.focus >= this.focusRows.size())
        {
            this.focus = this.focusRows.size() - 1;
        }

        this.editRow = this.rowFor(this.editRow);
        this.captureRow = this.rowFor(this.captureRow);
        this.dragRow = this.rowFor(this.dragRow);
        this.pressedRow = this.rowFor(this.pressedRow);
    }

    private static Layout layoutMode()
    {
        return ! Style.vanilla() ? Layout.AELL : Style.vanillaPlus() ? Layout.ROWS : Layout.GRID;
    }

    /** Ширина колонки плиток сетки. */
    private int tileW()
    {
        return Math.max(0, Math.min(TILE_W_MAX, (this.contentW - TILE_GAP) / 2));
    }

    /**
     * Сетка «Настроек» Minecraft: опции раздела парами в две колонки по центру, заголовок раздела — на всю ширину,
     * карточки — на всю ширину. Возвращает высоту содержимого.
     */
    private int gridRows()
    {
        int colW = this.tileW();
        int left = this.contentX + (this.contentW - (colW * 2 + TILE_GAP)) / 2;
        int y = 0;
        int col = 0;
        boolean first = true;

        for (Row row : this.rows)
        {
            boolean wide = row.kind == Row.Kind.SECTION || row.option == null || row.option.card != null;

            if (wide && col == 1)
            {
                y += TILE_PITCH;
                col = 0;
            }

            if (row.kind == Row.Kind.SECTION)
            {
                if (! first)
                {
                    y += Ui.SECTION_PAD_TOP;
                }

                row.y = y;
                row.h = Ui.SECTION_H;
                y += Ui.SECTION_H + Ui.SECTION_PAD_BOTTOM;
            }
            else if (wide)
            {
                row.x = this.contentX;
                row.w = this.contentW;
                row.y = y;
                y += row.h + Ui.ROW_GAP;
            }
            else
            {
                row.x = left + col * (colW + TILE_GAP);
                row.w = colW;
                row.y = y;
                row.h = TILE_H;

                if (col == 1)
                {
                    y += TILE_PITCH;
                }

                col = 1 - col;
            }

            first = false;
        }

        if (col == 1)
        {
            y += TILE_PITCH;
        }

        return Math.max(0, y - (TILE_PITCH - TILE_H));
    }

    /** Ширина кнопки «Сброс» в строках Vanilla+ (0 — в узкой панели сброса нет). */
    private int plusResetW()
    {
        return this.showReset ? Math.max(40, this.font.width(Strings.t("reset.short")) + Ui.PAD_REL * 2) : 0;
    }

    private @Nullable Row rowFor(@Nullable Row previous)
    {
        if (previous == null || previous.option == null)
        {
            return null;
        }

        for (Row row : this.rows)
        {
            if (row.option == previous.option)
            {
                return row;
            }
        }

        return null;
    }

    private int rowHeight(Model.Option option)
    {
        if (option.card != null)
        {
            return option.card.height(this.contentW);
        }

        Conflicts.Hit hit = this.conflicts.get(option.key);

        return hit != null && hit.hard() ? Ui.ROW_H_CONFLICT : Ui.ROW_H;
    }

    /** Тумблер: включение с {@code confirmEnable} — только после согласия игрока. */
    private void flip(Model.Option option)
    {
        Values.Bool value = option.bool;

        if (value == null)
        {
            return;
        }

        if (! value.get() && option.confirmEnable != null)
        {
            option.confirmEnable.accept(() ->
            {
                value.set(true);
                this.afterChange();
            });
            return;
        }

        value.set(! value.get());
        this.afterChange();
    }

    /** Жёлтый треугольник с «!» (9×8, тёмный знак внутри) — опция с предупреждением. */
    public static void drawWarning(GuiContext ctx, int x, int y, int color)
    {
        // Ряды треугольника снизу шире: 1, 3, 3, 5, 5, 7, 7, 9 пикселей
        int[] widths = { 1, 3, 3, 5, 5, 7, 7, 9 };

        for (int row = 0; row < widths.length; ++row)
        {
            int w = widths[row];
            Paint.rect(ctx, x + (9 - w) / 2, y + row, w, 1, color);
        }

        int ink = 0xFF1A1A1F;
        Paint.rect(ctx, x + 4, y + 2, 1, 3, ink);
        Paint.rect(ctx, x + 4, y + 6, 1, 1, ink);
    }

    /** Цвет знака предупреждения. */
    public static final int C_WARNING_SIGN = 0xFFF5C542;

    private int swatchCol(Model.Section section)
    {
        for (Model.Option option : section.options)
        {
            if (option.swatch.getAsInt() != 0 || option.warning)
            {
                return Ui.SWATCH_COL;
            }
        }

        return 0;
    }

    private int rowTop(Row row)
    {
        return this.bodyY + row.y - this.scroll;
    }

    private int ctrlX()
    {
        int right = this.contentX + this.contentW;

        if (layoutMode() == Layout.ROWS)
        {
            int reset = this.plusResetW();

            return right - (reset > 0 ? reset + Ui.PAD_IN : 0) - this.ctrlW;
        }

        return right - (this.showReset ? Ui.RESET_W + Ui.PAD_REL : 0) - this.ctrlW;
    }

    private int resetX()
    {
        if (layoutMode() == Layout.ROWS)
        {
            return this.contentX + this.contentW - this.plusResetW();
        }

        return this.contentX + this.contentW - Ui.RESET_W;
    }

    private boolean resetShown(Model.Option option)
    {
        return this.showReset
                && (! option.isDefault() || option.kind == Model.Kind.KEYBIND);
    }

    private int nameW(Row row)
    {
        return Math.max(0, this.ctrlX() - Ui.PAD_REL - (this.contentX + row.indent + row.swatchCol));
    }

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.builtLayout != null && this.builtLayout != layoutMode())
        {
            this.layout();
        }

        Model.Status now = this.model.status();
        int wanted = now == null ? 0 : Ui.STATUS_H;

        if (wanted != this.statusH || this.headerButtonsChanged())
        {
            this.layout();
        }
        else
        {
            this.status = now;
        }

        if (Style.vanilla())
        {
            // Фон меню Minecraft на весь экран и список-панель под окном
            Vanilla.screen(ctx, this.width, this.height);
            Vanilla.panel(ctx, this.panelX, this.panelY, this.panelW, this.panelH);
            return;
        }

        Paint.rect(ctx, 0, 0, this.width, this.height, Ui.C_DIM);
        Paint.rect(ctx, this.panelX, this.panelY, this.panelW, this.panelH, Ui.C_PANEL);
        Paint.rect(ctx, this.panelX + this.panelW - Ui.BORDER, this.panelY, Ui.BORDER, this.panelH,
                Ui.C_PANEL_EDGE);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        this.drawHeader(ctx, mouseX, mouseY);
        this.drawTabs(ctx, mouseX, mouseY);
        this.drawStatus(ctx, mouseX, mouseY);
        this.drawBody(ctx, mouseX, mouseY);
        this.drawFooter(ctx, mouseX, mouseY);

        if (this.dropdownOpen)
        {
            this.drawDropdown(ctx, mouseX, mouseY);
        }

        this.switcher.render(ctx, mouseX, mouseY);
    }

    private void drawHeader(GuiContext ctx, int mouseX, int mouseY)
    {
        if (Style.vanilla())
        {
            // Шапка без заливки: разделитель в 2 px по её нижнему краю
            Vanilla.headerSeparator(ctx, this.panelX, this.panelY + Ui.HEADER_H - 2, this.panelW);
        }
        else
        {
            Paint.rect(ctx, this.panelX, this.panelY, this.panelW, Ui.HEADER_H, Ui.C_CHROME);
            Paint.rect(ctx, this.panelX, this.panelY + Ui.HEADER_H - Ui.BORDER, this.panelW, Ui.BORDER,
                    Ui.C_DIVIDER);
        }

        String title = this.headerTitle;
        int dot = title.indexOf(Ui.TITLE_SEPARATOR);
        int y = this.panelY;
        int titleX = this.contentX + this.iconW();
        Identifier icon = this.model.icon();

        if (icon != null)
        {
            Paint.texture(ctx, icon, this.contentX, y + (Ui.HEADER_H - Ui.HEADER_ICON) / 2, Ui.HEADER_ICON);
        }

        if (dot > 0)
        {
            String name = title.substring(0, dot);
            Paint.midText(ctx, this.font, name, titleX, y, Ui.HEADER_H, Ui.C_TEXT, true);
            int cx = titleX + this.font.width(name);
            Paint.midText(ctx, this.font, Ui.TITLE_SEPARATOR, cx, y, Ui.HEADER_H, Ui.C_TEXT_3, false);
            cx += this.font.width(Ui.TITLE_SEPARATOR);
            Paint.midText(ctx, this.font, title.substring(dot + Ui.TITLE_SEPARATOR.length()), cx, y, Ui.HEADER_H, Ui.C_TEXT_2, false);
        }
        else
        {
            Paint.midText(ctx, this.font, title, titleX, y, Ui.HEADER_H, Ui.C_TEXT, true);
        }

        int btnY = y + (Ui.HEADER_H - Ui.HEAD_BTN_H) / 2;

        for (HeadBox box : this.headBoxes)
        {
            this.drawHeadButton(ctx, box, btnY, mouseX, mouseY);
        }

        if (this.moreBox != null)
        {
            this.drawHeadButton(ctx, this.moreBox, btnY, mouseX, mouseY);
        }

        this.drawSearch(ctx, mouseX, mouseY);
    }

    private void drawHeadButton(GuiContext ctx, HeadBox box, int y, int mouseX, int mouseY)
    {
        boolean over = Paint.hit(mouseX, mouseY, box.x, y, box.w, Ui.HEAD_BTN_H);
        int accent = box.button != null ? box.button.accent() : 0;

        if (Style.vanilla())
        {
            // Кнопка Minecraft; тон акцента и чужой кнопки остаётся только цветом надписи
            Vanilla.button(ctx, box.x, y, box.w, Ui.HEAD_BTN_H, true, over);
            this.vanillaLabel(ctx, box.label, box.x, y, box.w, Ui.HEAD_BTN_H, true,
                    box.foreign ? Style.V_GRAY : Style.V_WHITE);
            return;
        }

        if (accent != 0)
        {
            // Акцентная кнопка: тон приглушён — заливка едва заметна, рамка в полсилы, текст светлее тона
            Paint.round(ctx, box.x, y, box.w, Ui.HEAD_BTN_H, Ui.R_CTRL, withAlpha(accent, over ? 0x40 : 0x24));
            Paint.roundBorder(ctx, box.x, y, box.w, Ui.HEAD_BTN_H, Ui.R_CTRL, withAlpha(accent, over ? 0xB0 : 0x80));
            Paint.midText(ctx, this.font, box.label, box.x + Ui.HEAD_BTN_PAD_X, y, Ui.HEAD_BTN_H,
                    over ? Ui.C_TEXT : lighten(accent), false);
            return;
        }

        int color = box.foreign ? Ui.C_FOREIGN : Ui.C_TEXT_2;

        if (over)
        {
            Paint.round(ctx, box.x, y, box.w, Ui.HEAD_BTN_H, Ui.R_CTRL, Ui.C_ROW_HOVER);
        }

        Paint.roundBorder(ctx, box.x, y, box.w, Ui.HEAD_BTN_H, Ui.R_CTRL,
                box.foreign ? Ui.C_FOREIGN : Ui.C_DIVIDER);
        Paint.midText(ctx, this.font, box.label, box.x + Ui.HEAD_BTN_PAD_X, y, Ui.HEAD_BTN_H,
                over ? Ui.C_TEXT : color, false);
    }

    private static int withAlpha(int color, int alpha)
    {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** Тот же тон, наполовину к белому — текст на приглушённой заливке. */
    private static int lighten(int color)
    {
        int r = ((color >> 16) & 0xFF) + (0xFF - ((color >> 16) & 0xFF)) / 2;
        int g = ((color >> 8) & 0xFF) + (0xFF - ((color >> 8) & 0xFF)) / 2;
        int b = (color & 0xFF) + (0xFF - (color & 0xFF)) / 2;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawSearch(GuiContext ctx, int mouseX, int mouseY)
    {
        int y = this.panelY + (Ui.HEADER_H - Ui.SEARCH_H) / 2;

        if (Style.vanilla())
        {
            Vanilla.field(ctx, this.searchX, y, this.searchW, Ui.SEARCH_H, this.searchFocus);
        }
        else
        {
            Paint.card(ctx, this.searchX, y, this.searchW, Ui.SEARCH_H, Ui.R_CTRL, Ui.C_PANEL,
                    this.searchFocus ? Ui.C_ACCENT : Ui.C_DIVIDER);
        }

        // Лупа — пиксельная, а не глиф «⌕»: глиф в игровом шрифте сидит ниже строки и не
        // центрируется; маска 7×7 встаёт вровень с заглавными буквами подсказки
        int iconX = this.searchX + Ui.PAD_IN;
        Paint.icon(ctx, Paint.SEARCH_ICON, iconX, y + (Ui.SEARCH_H - Ui.SEARCH_ICON) / 2, Ui.C_GOLD);

        int textX = iconX + Ui.SEARCH_ICON + Ui.PAD_IN;
        int room = this.searchX + this.searchW - Ui.PAD_IN - textX;

        if (this.search.isEmpty() && ! this.searchFocus)
        {
            Paint.midText(ctx, this.font, Paint.fit(this.font, Strings.t("search.hint"), room),
                    textX, y, Ui.SEARCH_H, Ui.C_TEXT_3, false);
        }
        else
        {
            // В фокусе подсказки нет: только набранное и мигающий курсор — видно, что клик принят
            boolean caret = this.searchFocus && (Util.getMillis() / Ui.CARET_MS) % 2L == 0L;
            String shown = this.search + (caret ? "_" : "");
            Paint.midText(ctx, this.font, Paint.fit(this.font, shown, room), textX, y, Ui.SEARCH_H,
                    Ui.C_TEXT, false);
        }
    }

    private void drawTabs(GuiContext ctx, int mouseX, int mouseY)
    {
        int stripY = this.panelY + Ui.HEADER_H;

        if (! Style.vanilla())
        {
            Paint.rect(ctx, this.panelX, stripY, this.panelW, this.tabsH, Ui.C_PANEL);
        }

        ctx.enableScissor(this.tabStripX, stripY, this.tabStripX + this.tabStripW, stripY + Ui.TABS_H);

        for (TabBox box : this.tabBoxes)
        {
            int x = this.tabDrawX(box);
            boolean active = box.index == this.tab && this.search.isEmpty();
            boolean over = Paint.hit(mouseX, mouseY, x, box.y, box.w, Ui.TABS_H)
                    && mouseX >= this.tabStripX && mouseX < this.tabStripX + this.tabStripW;

            float hover = this.hoverAnim.update("tab#" + box.index, over && ! active);

            if (Style.vanilla())
            {
                Vanilla.tab(ctx, x, box.y, box.w, Ui.TABS_H, active, over);
            }
            else if (hover > 0.0F)
            {
                Paint.round(ctx, x, box.y + Ui.PAD_MICRO, box.w, Ui.TABS_H - Ui.PAD_IN, Ui.R_ROW,
                        Ui.alpha(Ui.C_ROW_HOVER, hover));
            }

            if (box.icon != null)
            {
                Paint.texture(ctx, box.icon, x + box.pad,
                        box.y + (Ui.TABS_H - Ui.TAB_ICON) / 2, Ui.TAB_ICON);
            }

            Paint.midText(ctx, this.font, box.label, x + box.pad + iconRoom(box.icon), box.y,
                    Ui.TABS_H, Style.vanilla() ? Vanilla.tabText(active, over) : active ? Ui.C_TEXT : Ui.C_TEXT_2, active);

            // Шеврон после подписи: вкладка не листается, а открывает отдельный экран
            if (this.tabs.get(box.index).opens != null)
            {
                Paint.icon(ctx, Paint.CHEVRON_RIGHT,
                        x + box.pad + iconRoom(box.icon) + this.font.width(box.label) + Ui.TAB_ICON_GAP,
                        box.y + (Ui.TABS_H - Ui.TAB_ARROW_GLYPH_H) / 2, over ? Ui.C_TEXT_2 : Ui.C_TEXT_3);
            }

            if (active && ! Style.vanilla())
            {
                Paint.rect(ctx, x, box.y + Ui.TABS_H - Ui.TAB_UNDERLINE, box.w, Ui.TAB_UNDERLINE,
                        Ui.C_ACCENT);
            }

            if (this.changedInTab(box.index) > 0)
            {
                Paint.round(ctx, x + box.w - Ui.TAB_DOT - Ui.PAD_MICRO, box.y + Ui.PAD_MICRO,
                        Ui.TAB_DOT, Ui.TAB_DOT, 1, Ui.C_CHANGED);
            }
        }

        ctx.disableScissor();

        this.drawTabArrows(ctx, mouseX, mouseY);

        if (! Style.vanilla())
        {
            Paint.rect(ctx, this.panelX, stripY + this.tabsH - Ui.BORDER, this.panelW,
                    Ui.BORDER, Ui.C_DIVIDER);
        }
    }

    private void drawTabArrows(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.tabScrollMax <= 0)
        {
            return;
        }

        int y = this.tabArrowY();
        int glyphY = y;
        int leftX = this.contentX;
        int rightX = this.contentX + this.contentW - Ui.TAB_ARROW_W;
        boolean canLeft = this.tabScroll > 0;
        boolean canRight = this.tabScroll < this.tabScrollMax;

        Paint.icon(ctx, Paint.CHEVRON_LEFT, leftX + Ui.PAD_MICRO, glyphY,
                this.arrowColor(canLeft, Paint.hit(mouseX, mouseY, leftX,
                        this.panelY + Ui.HEADER_H, Ui.TAB_ARROW_W, Ui.TABS_H)));

        Paint.icon(ctx, Paint.CHEVRON_RIGHT,
                rightX + Ui.TAB_ARROW_W - Ui.TAB_ARROW_GLYPH_W - Ui.PAD_MICRO, glyphY,
                this.arrowColor(canRight, Paint.hit(mouseX, mouseY, rightX,
                        this.panelY + Ui.HEADER_H, Ui.TAB_ARROW_W, Ui.TABS_H)));
    }

    private int arrowColor(boolean enabled, boolean over)
    {
        if (! enabled)
        {
            return Ui.C_TEXT_OFF;
        }

        return over ? Ui.C_TEXT : Ui.C_TEXT_2;
    }

    private void drawStatus(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.status == null)
        {
            return;
        }

        int y = this.panelY + Ui.HEADER_H + this.tabsH;
        boolean over = Paint.hit(mouseX, mouseY, this.panelX, y, this.panelW, Ui.STATUS_H);

        if (! Style.vanilla())
        {
            Paint.rect(ctx, this.panelX, y, this.panelW, Ui.STATUS_H, Ui.C_CHROME);
        }

        int plateX = this.contentX - Ui.PAD_IN;
        int plateW = this.contentW + Ui.PAD_IN * 2;
        int color = this.status.color();

        Paint.card(ctx, plateX, y + Ui.BORDER, plateW, Ui.STATUS_H - Ui.PAD_MICRO, Ui.R_PLATE,
                Ui.alpha(color, 0.12F), Ui.alpha(color, 0.45F));

        if (over && this.status.tabKey() != null)
        {
            Paint.round(ctx, plateX, y + Ui.BORDER, plateW, Ui.STATUS_H - Ui.PAD_MICRO, Ui.R_PLATE,
                    Ui.C_ROW_HOVER);
        }

        int dotY = y + (Ui.STATUS_H - Ui.STATUS_DOT) / 2;
        Paint.round(ctx, this.contentX, dotY, Ui.STATUS_DOT, Ui.STATUS_DOT, Ui.STATUS_DOT / 2, color);

        int textX = this.contentX + Ui.STATUS_DOT + Ui.PAD_IN;
        Paint.midText(ctx, this.font,
                Paint.fit(this.font, this.status.text(), this.contentX + this.contentW - textX),
                textX, y, Ui.STATUS_H, color, false);
    }

    private void drawBody(GuiContext ctx, int mouseX, int mouseY)
    {
        ctx.enableScissor(this.panelX, this.bodyY, this.panelX + this.panelW, this.bodyY + this.bodyH);

        if (this.rows.isEmpty() && ! this.search.isEmpty())
        {
            String text = Strings.t("search.empty");
            Paint.midText(ctx, this.font, text,
                    this.contentX + (this.contentW - this.font.width(text)) / 2,
                    this.bodyY, this.bodyH, Ui.C_TEXT_2, false);
        }

        for (Row row : this.rows)
        {
            int top = this.rowTop(row);

            if (top + row.h < this.bodyY || top > this.bodyY + this.bodyH)
            {
                continue;
            }

            switch (row.kind)
            {
                case SECTION -> this.drawSection(ctx, row, top);
                case OPTION -> this.drawOption(ctx, row, top, mouseX, mouseY);
            }
        }

        ctx.disableScissor();

        this.drawScrollbar(ctx, mouseX, mouseY);
    }

    private void drawSection(GuiContext ctx, Row row, int top)
    {
        Model.Section section = row.section;

        if (section == null)
        {
            return;
        }

        String warning = section.warning.get();
        String title = Strings.plain(section.key);
        boolean noted = section.noteKey != null;
        // Акцент заголовка: цветная черта слева, текст светлым тоном того же цвета, линия — им же, приглушённо
        int accent = warning != null ? Ui.C_WARN : section.accent != 0 ? section.accent : Ui.C_ACCENT;
        int color = Paint.mix(Ui.C_TEXT, accent, 0.55F);
        int barH = Ui.SECTION_H - 6;

        if (layoutMode() == Layout.GRID)
        {
            this.drawSectionCentered(ctx, section, top, title, warning, noted, accent, color);
            return;
        }

        Paint.rect(ctx, this.contentX, top + (Ui.SECTION_H - barH) / 2, 2, barH, accent);
        Paint.midText(ctx, this.font, title, this.contentX + 2 + Ui.PAD_REL, top, Ui.SECTION_H, color, false);

        int x = this.contentX + 2 + Ui.PAD_REL + this.font.width(title) + Ui.PAD_REL;
        int right = this.contentX + this.contentW;

        if (noted)
        {
            Paint.midText(ctx, this.font, Ui.NOTE_MARK, x - Ui.PAD_MICRO, top, Ui.SECTION_H,
                    this.hoverTarget == section ? Ui.C_TEXT : Ui.C_TEXT_3, false);
            x += this.font.width(Ui.NOTE_MARK) + Ui.PAD_IN - Ui.PAD_MICRO;
        }

        if (warning != null)
        {
            String text = Paint.fit(this.font, warning, right - x);
            Paint.midText(ctx, this.font, text, x, top, Ui.SECTION_H, Ui.C_WARN, false);
            x += this.font.width(text) + Ui.PAD_REL;
        }

        if (x < right)
        {
            Paint.rect(ctx, x, top + Ui.SECTION_H / 2, right - x, Ui.BORDER, Ui.alpha(accent, 0.28F));
        }
    }

    /** Заголовок раздела сетки: по центру, линии акцента по обе стороны, «?» и предупреждение — рядом с названием. */
    private void drawSectionCentered(GuiContext ctx, Model.Section section, int top, String title,
            @Nullable String warning, boolean noted, int accent, int color)
    {
        int left = this.contentX;
        int right = this.contentX + this.contentW;
        String note = noted ? " " + Ui.NOTE_MARK : "";
        String warn = warning != null ? Paint.fit(this.font, "  " + warning, this.contentW / 2) : "";
        int groupW = this.font.width(title) + this.font.width(note) + this.font.width(warn);
        int x = left + (this.contentW - groupW) / 2;
        int lineY = top + Ui.SECTION_H / 2;

        Paint.rect(ctx, left, lineY, Math.max(0, x - Ui.PAD_REL - left), Ui.BORDER, Ui.alpha(accent, 0.28F));
        Paint.midText(ctx, this.font, title, x, top, Ui.SECTION_H, color, false);
        x += this.font.width(title);

        if (noted)
        {
            Paint.midText(ctx, this.font, note, x, top, Ui.SECTION_H,
                    this.hoverTarget == section ? Ui.C_TEXT : Ui.C_TEXT_3, false);
            x += this.font.width(note);
        }

        if (warning != null)
        {
            Paint.midText(ctx, this.font, warn, x, top, Ui.SECTION_H, Ui.C_WARN, false);
            x += this.font.width(warn);
        }

        Paint.rect(ctx, x + Ui.PAD_REL, lineY, Math.max(0, right - x - Ui.PAD_REL), Ui.BORDER, Ui.alpha(accent, 0.28F));
    }

    /** Подпись значения опции для плитки: «Вкл/Выкл», число, клавиши, вариант выбора. */
    private String tileValue(Row row, Model.Option option)
    {
        return switch (option.kind)
        {
            case TOGGLE ->
            {
                boolean on = option.bool != null && option.bool.get();
                yield option.onLabel != null && option.offLabel != null
                        ? Strings.plain(on ? option.onLabel : option.offLabel) : Strings.onOff(on);
            }
            case INT -> this.editRow == row ? this.editText + "_"
                    : option.number == null ? "" : Num.compact(option.number.get());
            case DOUBLE -> option.decimal == null ? "" : Num.format(option.decimal.get(), option.format);
            case KEYBIND ->
            {
                String live = option.hotkey == null ? "" : option.hotkey.display();

                if (this.captureRow == row)
                {
                    yield "> " + (live.isEmpty() ? Strings.t("key.capture") : live) + " <";
                }

                yield live.isEmpty() ? Strings.notSet() : live;
            }
            case ACTION -> option.actionLabel != null ? Strings.plain(option.actionLabel) : Strings.t("open");
            case CHOICE -> option.choice == null ? "" : option.choice.label(option.choice.get());
        };
    }

    /**
     * Плитка Minecraft для опции — общая для сетки (с названием: «Название: значение») и строк Vanilla+ (только
     * значение): кнопка, ползунок со значением внутри, клавиша (ввод — «> … <» жёлтым), выбор.
     */
    private void drawTileControl(GuiContext ctx, Row row, Model.Option option, int x, int y, int w, int h,
            @Nullable String name, boolean off, boolean highlight)
    {
        this.drawTileControl(ctx, row, option, x, y, w, h, name, off, highlight, 0);
    }

    /** {@code leftPad} — место слева под образец цвета или знак предупреждения: надпись центрируется правее. */
    private void drawTileControl(GuiContext ctx, Row row, Model.Option option, int x, int y, int w, int h,
            @Nullable String name, boolean off, boolean highlight, int leftPad)
    {
        String value = this.tileValue(row, option);
        int room = w - leftPad - Ui.PAD_REL * 2;
        String label;

        if (name == null)
        {
            label = Paint.fit(this.font, value, room);
        }
        else if (option.kind == Model.Kind.ACTION)
        {
            label = Paint.fit(this.font, name, room);
        }
        else
        {
            // Значение видно всегда, режется название: «Пропускать то, что вс…: Вкл»
            String tail = ": " + value;
            label = Paint.fit(this.font, name, Math.max(0, room - this.font.width(tail))) + tail;
        }

        int tint = Style.V_WHITE;

        switch (option.kind)
        {
            case INT, DOUBLE ->
            {
                float fraction = option.kind == Model.Kind.INT
                        ? option.number == null ? 0.0F
                                : Num.toFraction(option.number.get(), option.number.min(), option.number.max(), option.scale)
                        : option.decimal == null ? 0.0F
                                : Num.toFraction(option.decimal.get(), option.decimal.min(), option.decimal.max());
                Vanilla.sliderTrack(ctx, x, y, w, h, highlight);
                Vanilla.sliderHandleAt(ctx, x, y, w, h, fraction, highlight);
            }
            case KEYBIND ->
            {
                boolean capturing = this.captureRow == row;
                Conflicts.Hit hit = this.conflicts.get(option.key);
                Vanilla.button(ctx, x, y, w, h, ! off, highlight || capturing);

                if (hit != null)
                {
                    Vanilla.outline(ctx, x, y, w, h, hit.hard() ? Style.V_RED : Style.V_GOLD);
                }

                if (capturing)
                {
                    tint = Style.V_YELLOW;
                }
                else if (hit != null && hit.hard())
                {
                    tint = Style.V_RED;
                }
            }
            default ->
            {
                Vanilla.button(ctx, x, y, w, h, ! off, highlight);

                if (option.kind == Model.Kind.ACTION && option.actionStyle == 2)
                {
                    tint = Style.V_RED;
                }
            }
        }

        this.vanillaLabel(ctx, label, x + leftPad, y, w - leftPad, h, ! off, tint);
    }

    /** Опция в сетке: плитка «Название: значение»; отметка изменённого — слева от плитки, цвет-образец — внутри слева. */
    private void drawTile(GuiContext ctx, Row row, Model.Option option, int top, boolean off, boolean focused,
            int mouseX, int mouseY)
    {
        boolean inBody = mouseY >= this.bodyY && mouseY < this.bodyY + this.bodyH;
        boolean over = ! off && inBody && Paint.hit(mouseX, mouseY, row.x, top, row.w, TILE_H);
        boolean jumped = this.jumpKey != null && this.jumpKey.equals(option.key) && Util.getMillis() < this.jumpUntil;
        boolean highlight = over || focused || jumped || this.pressedRow == row || this.dragRow == row;

        int swatch = option.swatch.getAsInt();
        int leftPad = option.warning || swatch != 0 ? Ui.SWATCH_COL + 4 : 0;
        this.drawTileControl(ctx, row, option, row.x, top, row.w, TILE_H, this.nameOf(option.key), off, highlight, leftPad);

        if (! option.isDefault() && option.kind != Model.Kind.KEYBIND)
        {
            Paint.rect(ctx, row.x - Ui.MARK_W - 1, top, Ui.MARK_W, TILE_H, Ui.C_CHANGED);
        }

        if (option.warning)
        {
            drawWarning(ctx, row.x + 4, top + (TILE_H - 8) / 2, off ? Ui.alpha(C_WARNING_SIGN, Ui.A_SWATCH_OFF) : C_WARNING_SIGN);
        }
        else if (swatch != 0)
        {
            Paint.rect(ctx, row.x + 5, top + (TILE_H - Ui.SWATCH) / 2, Ui.SWATCH, Ui.SWATCH,
                    off ? Ui.alpha(swatch, Ui.A_SWATCH_OFF) : swatch);
        }
    }

    private void drawOption(GuiContext ctx, Row row, int top, int mouseX, int mouseY)
    {
        Model.Option option = row.option;

        if (option == null)
        {
            return;
        }

        Model.Reason reason = option.gate.get();
        boolean off = reason != null;
        boolean over = ! off && Paint.hit(mouseX, mouseY, this.panelX, top, this.panelW, row.h)
                && mouseY >= this.bodyY && mouseY < this.bodyY + this.bodyH;
        boolean focused = this.focusRows.indexOf(row) == this.focus && this.focus >= 0;

        int cardX = this.contentX - Ui.PAD_IN;
        int cardW = this.contentW + Ui.PAD_IN * 2;

        // Карточка рисует себя сама: фон, подсветка, кнопки — её дело
        if (option.card != null)
        {
            option.card.draw(ctx, this.font, this.contentX + row.indent, top, this.contentW - row.indent, row.h,
                    mouseY >= this.bodyY && mouseY < this.bodyY + this.bodyH ? mouseX : Integer.MIN_VALUE, mouseY,
                    focused && ! off);
            return;
        }

        if (layoutMode() == Layout.GRID)
        {
            this.drawTile(ctx, row, option, top, off, focused, mouseX, mouseY);
            return;
        }

        float hover = this.hoverAnim.update(option.key, over);
        boolean jumped = this.jumpKey != null && this.jumpKey.equals(option.key)
                && Util.getMillis() < this.jumpUntil;

        if (Style.vanilla() && (focused || jumped))
        {
            // Строка в фокусе: лёгкая подсветка и белая рамка, как фокус Minecraft
            Paint.rect(ctx, cardX, top, cardW, row.h, Ui.C_ROW_FOCUS);
            Vanilla.outline(ctx, cardX, top, cardW, row.h, Style.V_WHITE);
        }
        else if (focused || jumped)
        {
            Paint.round(ctx, cardX, top, cardW, row.h, Ui.R_ROW, Ui.C_ROW_FOCUS);
        }
        else if (hover > 0.0F)
        {
            Paint.round(ctx, cardX, top, cardW, row.h, Ui.R_ROW,
                    Ui.alpha(Ui.C_ROW_HOVER, hover));
        }

        if (! option.isDefault())
        {
            Paint.rect(ctx, this.panelX, top, Ui.MARK_W, Ui.ROW_H, Ui.C_CHANGED);
        }

        int textX = this.contentX + row.indent;

        if (row.swatchCol > 0)
        {
            int swatch = option.swatch.getAsInt();

            if (option.warning)
            {
                drawWarning(ctx, textX, top + (Ui.ROW_H - 8) / 2, off ? Ui.alpha(C_WARNING_SIGN, Ui.A_SWATCH_OFF) : C_WARNING_SIGN);
            }
            else if (swatch != 0)
            {
                Paint.rect(ctx, textX, top + (Ui.ROW_H - Ui.SWATCH) / 2, Ui.SWATCH, Ui.SWATCH,
                        off ? Ui.alpha(swatch, Ui.A_SWATCH_OFF) : swatch);
            }

            textX += row.swatchCol;
        }

        int color = off ? Ui.C_TEXT_OFF : Ui.C_TEXT;
        int nameW = this.nameW(row);
        String name = this.nameOf(option.key);

        if (row.tabPrefix != null)
        {
            String prefix = row.tabPrefix + Ui.TITLE_SEPARATOR;
            Paint.rowText(ctx, this.font, Paint.fit(this.font, prefix, nameW), textX, top, Ui.C_TEXT_3, false);
            int used = this.font.width(prefix);
            textX += used;
            nameW -= used;
        }

        Paint.highlighted(ctx, this.font, name, row.matchFrom, row.matchLen, textX, top, color,
                Ui.C_ACCENT, nameW);

        this.drawPreview(ctx, row, option, top, textX + this.font.width(Paint.fit(this.font, name, nameW)));

        if (layoutMode() == Layout.ROWS)
        {
            // Vanilla+: плитка значения на всю высоту строки и всегда кнопка «Сброс» (тёмная, если сбрасывать нечего)
            int cx = this.ctrlX();
            boolean overCtrl = ! off && Paint.hit(mouseX, mouseY, cx, top, this.ctrlW, Ui.ROW_H)
                    && mouseY >= this.bodyY && mouseY < this.bodyY + this.bodyH;
            this.drawTileControl(ctx, row, option, cx, top, this.ctrlW, Ui.ROW_H, null, off,
                    overCtrl || focused || this.pressedRow == row || this.dragRow == row);

            int rw = this.plusResetW();

            if (rw > 0 && option.kind != Model.Kind.ACTION)
            {
                int rx = this.resetX();
                boolean can = ! off && (! option.isDefault() || option.kind == Model.Kind.KEYBIND);
                boolean overReset = can && Paint.hit(mouseX, mouseY, rx, top, rw, Ui.ROW_H);
                Vanilla.button(ctx, rx, top, rw, Ui.ROW_H, can, overReset);
                this.vanillaLabel(ctx, Paint.fit(this.font, Strings.t("reset.short"), rw - Ui.PAD_IN * 2), rx, top, rw,
                        Ui.ROW_H, can, Style.V_WHITE);
            }
        }
        else
        {
            this.drawControl(ctx, row, option, top, off, mouseX, mouseY);
        }

        if (focused && layoutMode() == Layout.AELL)
        {
            Paint.focus(ctx, this.ctrlX(), top + (Ui.ROW_H - Ui.CTRL_H) / 2, this.ctrlW, Ui.CTRL_H);
        }

        if (layoutMode() != Layout.ROWS && this.resetShown(option))
        {
            boolean changed = ! option.isDefault();
            int rx = this.resetX();
            int ry = top + (Ui.ROW_H - Ui.RESET_W) / 2;
            boolean overReset = Paint.hit(mouseX, mouseY, rx, ry, Ui.RESET_W, Ui.RESET_W);

            if (Style.vanilla())
            {
                Vanilla.button(ctx, rx, ry, Ui.RESET_W, Ui.RESET_W, true, overReset);
            }
            else
            {
                Paint.roundBorder(ctx, rx, ry, Ui.RESET_W, Ui.RESET_W, Ui.R_CTRL,
                        overReset ? Ui.C_CHANGED : Ui.C_DIVIDER);
            }
            Paint.resetGlyph(ctx, rx + (Ui.RESET_W - Ui.ICON_W) / 2, ry + (Ui.RESET_W - Ui.ICON_W) / 2,
                    changed ? Ui.C_CHANGED : Ui.C_TEXT_3);
        }

        Conflicts.Hit hit = this.conflicts.get(option.key);

        if (hit != null && hit.hard())
        {
            Paint.midText(ctx, this.font,
                    Paint.fit(this.font, hit.label(), this.contentW - row.indent),
                    this.contentX + row.indent, top + Ui.ROW_H, Ui.CONFLICT_LINE_H, Ui.C_DANGER, false);
        }
    }

    private void drawPreview(GuiContext ctx, Row row, Model.Option option, int top, int textEnd)
    {
        if (option.preview == null || ! this.showReset)
        {
            return;
        }

        int need = option.preview.width();
        int right = this.ctrlX() - Ui.PAD_REL;
        int x = right - need;

        if (x - Ui.PAD_REL < textEnd)
        {
            return;
        }

        option.preview.draw(ctx, x, top, option);
    }

    private void drawControl(GuiContext ctx, Row row, Model.Option option, int top, boolean off,
            int mouseX, int mouseY)
    {
        int x = this.ctrlX();
        int y = top + (Ui.ROW_H - Ui.CTRL_H) / 2;
        boolean pressed = this.pressedRow == row;

        switch (option.kind)
        {
            case TOGGLE -> this.drawToggle(ctx, option, x, y, off, pressed);
            case INT -> this.drawInt(ctx, row, option, x, y, off, pressed);
            case DOUBLE -> this.drawDouble(ctx, row, option, x, y, off, pressed);
            case KEYBIND -> this.drawKeybind(ctx, row, option, x, y, off);
            case ACTION -> this.drawAction(ctx, option, x, y, off, pressed, mouseX, mouseY);
            case CHOICE -> this.drawChoice(ctx, row, option, x, y, off, mouseX, mouseY);
        }
    }

    /** Выбор в своём стиле: кнопка с рамкой и текущим вариантом; в ванильном — плитка. */
    private void drawChoice(GuiContext ctx, Row row, Model.Option option, int x, int y, boolean off, int mouseX, int mouseY)
    {
        boolean over = ! off && Paint.hit(mouseX, mouseY, x, y, this.ctrlW, Ui.CTRL_H);

        if (Style.vanilla())
        {
            this.drawTileControl(ctx, row, option, x, y, this.ctrlW, Ui.CTRL_H, null, off, over);
            return;
        }

        if (over)
        {
            Paint.round(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, Ui.C_ROW_HOVER);
        }

        Paint.roundBorder(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, off ? Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF) : Ui.C_DIVIDER);
        String label = Paint.fit(this.font, this.tileValue(row, option), this.ctrlW - Ui.PAD_IN * 2);
        Paint.midText(ctx, this.font, label, x + (this.ctrlW - this.font.width(label)) / 2, y, Ui.CTRL_H,
                off ? Ui.C_TEXT_OFF : Ui.C_TEXT, false);
    }

    /** Следующий (с Shift — предыдущий) вариант выбора. */
    private void cycle(Model.Option option)
    {
        Values.Choice choice = option.choice;

        if (choice == null || choice.count() <= 0)
        {
            return;
        }

        int step = GuiBase.isShiftDown() ? -1 : 1;
        choice.set(Math.floorMod(choice.get() + step, choice.count()));
        this.afterChange();
    }

    private void drawAction(GuiContext ctx, Model.Option option, int x, int y, boolean off, boolean pressed,
            int mouseX, int mouseY)
    {
        boolean over = ! off && Paint.hit(mouseX, mouseY, x, y, this.ctrlW, Ui.CTRL_H);
        int tone = option.actionStyle == 2 ? Ui.C_DANGER : option.actionColor != 0 ? option.actionColor : Ui.C_ACCENT;
        int color = off ? Ui.C_TEXT_OFF : pressed ? Ui.darken(tone, Ui.PRESS_DARKEN) : tone;

        if (Style.vanilla())
        {
            Vanilla.button(ctx, x, y, this.ctrlW, Ui.CTRL_H, ! off, over || pressed);
            String shown = Paint.fit(this.font,
                    option.actionLabel != null ? Strings.plain(option.actionLabel) : Strings.t("open"),
                    this.ctrlW - Ui.PAD_IN * 2);
            this.vanillaLabel(ctx, shown, x, y, this.ctrlW, Ui.CTRL_H, ! off,
                    option.actionStyle == 2 ? Style.V_RED : Style.V_WHITE);
            return;
        }

        if (option.actionStyle == 1 && ! off)
        {
            // Главное действие: приглушённая заливка тоном, как акцентная кнопка шапки
            Paint.round(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, withAlpha(tone, over ? 0x40 : 0x24));
        }
        else if (over)
        {
            Paint.round(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, Ui.C_ROW_HOVER);
        }

        Paint.roundBorder(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL,
                off ? Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF) : option.actionStyle == 1 ? withAlpha(tone, over ? 0xB0 : 0x80) : color);

        String text = option.actionLabel != null ? Strings.plain(option.actionLabel) : Strings.t("open");
        String label = Paint.fit(this.font, text, this.ctrlW - Ui.PAD_IN * 2);
        int textColor = off ? Ui.C_TEXT_OFF : option.actionStyle == 1 ? (over ? Ui.C_TEXT : lighten(tone)) : color;
        Paint.midText(ctx, this.font, label, x + (this.ctrlW - this.font.width(label)) / 2, y, Ui.CTRL_H,
                textColor, false);
    }

    private void drawToggle(GuiContext ctx, Model.Option option, int x, int y, boolean off, boolean pressed)
    {
        boolean on = option.bool != null && option.bool.get();
        float k = this.knobAnim.update(option.key, on);
        int base = Paint.mix(Ui.C_OFF, Ui.C_ON, k);
        int fill = off ? Ui.alpha(base, Ui.A_DISABLED) : pressed ? Ui.darken(base, Ui.PRESS_DARKEN) : base;
        int sy = y + (Ui.CTRL_H - Ui.SWITCH_H) / 2;

        if (Style.vanilla())
        {
            // Галочка Minecraft по центру области рычажка; без наведения в этой точке — подсвечена при нажатии
            Vanilla.checkbox(ctx, x, y, Ui.SWITCH_W, Ui.CTRL_H, on, pressed);
        }
        else
        {
            Paint.round(ctx, x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, Ui.alpha(fill, Ui.A_TRACK_OFF));
            Paint.roundBorder(ctx, x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, fill);

            int knobX = x + Math.round((Ui.SWITCH_W - Ui.SWITCH_KNOB) * k);
            Paint.round(ctx, knobX, sy, Ui.SWITCH_KNOB, Ui.SWITCH_H, Ui.R_PILL, fill);
        }

        // «Вкл» и «Off» коротки, а Desactivado или 비활성화 в узкой панели уезжают на
        // кнопку сброса — режем по остатку колонки, как это делает подпись у кнопки-действия.
        int labelX = x + Ui.SWITCH_W + Ui.PAD_IN;

        Paint.midText(ctx, this.font,
                Paint.fit(this.font, option.onLabel != null && option.offLabel != null
                        ? Strings.plain(on ? option.onLabel : option.offLabel) : Strings.onOff(on), x + this.ctrlW - labelX),
                labelX, y, Ui.CTRL_H, off ? Ui.C_TEXT_OFF : Ui.C_TEXT_2, false);
    }

    private int trackW()
    {
        return this.ctrlW == Ui.CTRL_W ? Ui.TRACK_W : Ui.TRACK_W_TIGHT;
    }

    private void drawInt(GuiContext ctx, Row row, Model.Option option, int x, int y, boolean off, boolean pressed)
    {
        Values.Int value = option.number;

        if (value == null)
        {
            return;
        }

        float fraction = Num.toFraction(value.get(), value.min(), value.max(), option.scale);
        boolean editing = this.editRow == row;
        String text = editing ? this.editText : Num.compact(value.get());

        this.drawSlider(ctx, x, y, fraction, off, pressed);
        this.drawField(ctx, x, y, text, off, editing);
    }

    private void drawDouble(GuiContext ctx, Row row, Model.Option option, int x, int y, boolean off,
            boolean pressed)
    {
        Values.Dbl value = option.decimal;

        if (value == null)
        {
            return;
        }

        float fraction = Num.toFraction(value.get(), value.min(), value.max());

        this.drawSlider(ctx, x, y, fraction, off, pressed);
        this.drawField(ctx, x, y, Num.format(value.get(), option.format), off, false);
    }

    private void drawSlider(GuiContext ctx, int x, int y, float fraction, boolean off, boolean pressed)
    {
        int tw = this.trackW();
        int ty = y + (Ui.CTRL_H - Ui.TRACK_H) / 2;
        int accent = off ? Ui.alpha(Ui.C_ACCENT, Ui.A_DISABLED)
                : pressed ? Ui.darken(Ui.C_ACCENT, Ui.PRESS_DARKEN) : Ui.C_ACCENT;

        if (Style.vanilla())
        {
            // Дорожка Minecraft на всю высоту элемента и ручка по доле значения
            Vanilla.sliderTrack(ctx, x, y, tw, Ui.CTRL_H, pressed);
            Vanilla.sliderHandleAt(ctx, x, y, tw, Ui.CTRL_H, fraction, pressed);
            return;
        }

        Paint.round(ctx, x, ty, tw, Ui.TRACK_H, Ui.R_BAR, Ui.C_TRACK);
        int fill =Math.round(tw * fraction);

        if (fill > 0)
        {
            Paint.round(ctx, x, ty, Math.max(fill, Ui.R_BAR * 2), Ui.TRACK_H, Ui.R_BAR, accent);
        }

        int thumbX = x + Math.round((tw - Ui.THUMB_W) * fraction);
        Paint.round(ctx, thumbX, y + (Ui.CTRL_H - Ui.THUMB_H) / 2, Ui.THUMB_W, Ui.THUMB_H, 1,
                off ? Ui.C_TEXT_OFF : Ui.C_TEXT);
    }

    private void drawField(GuiContext ctx, int x, int y, String text, boolean off, boolean editing)
    {
        int fx = x + this.trackW() + Ui.PAD_IN;
        boolean blink = editing && Util.getMillis() < this.blinkUntil;

        if (Style.vanilla())
        {
            Vanilla.field(ctx, fx, y, Ui.FIELD_W, Ui.CTRL_H, editing);

            if (blink)
            {
                Vanilla.outline(ctx, fx, y, Ui.FIELD_W, Ui.CTRL_H, Style.V_RED);
            }
        }
        else
        {
            Paint.roundBorder(ctx, fx, y, Ui.FIELD_W, Ui.CTRL_H, Ui.R_CTRL,
                    blink ? Ui.C_DANGER : editing ? Ui.C_ACCENT : Ui.C_DIVIDER);
        }
        Paint.midTextRight(ctx, this.font,
                Paint.fit(this.font, editing ? text + "_" : text, Ui.FIELD_W - Ui.PAD_IN * 2),
                fx + Ui.FIELD_W - Ui.PAD_IN, y, Ui.CTRL_H,
                off ? Ui.C_TEXT_OFF : Ui.C_TEXT, false);
    }

    private void drawKeybind(GuiContext ctx, Row row, Model.Option option, int x, int y, boolean off)
    {
        Values.Key value = option.hotkey;

        if (value == null)
        {
            return;
        }

        boolean capturing = this.captureRow == row;
        Conflicts.Hit hit = this.conflicts.get(option.key);
        int accent = option.swatch.getAsInt();
        boolean unset = value.display().isEmpty();
        int plain = accent == 0 ? Ui.C_DIVIDER
                : Ui.alpha(accent, unset ? Ui.A_KEY_EDGE_UNSET : Ui.A_KEY_EDGE);
        int edge = capturing ? Ui.C_ACCENT
                : hit == null ? plain : hit.hard() ? Ui.C_DANGER : Ui.C_WARN;

        if (Style.vanilla())
        {
            // Кнопка Minecraft; захват — подсвеченная, конфликт — рамка красная (жёсткий) или золотая
            Vanilla.button(ctx, x, y, this.ctrlW, Ui.CTRL_H, true, capturing);

            if (hit != null)
            {
                Vanilla.outline(ctx, x, y, this.ctrlW, Ui.CTRL_H, hit.hard() ? Style.V_RED : Style.V_GOLD);
            }
        }
        else
        {
            if (! capturing && ! unset && accent != 0 && ! off)
            {
                Paint.round(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, Ui.alpha(accent, Ui.A_KEY_FILL));
            }

            Paint.roundBorder(ctx, x, y, this.ctrlW, Ui.CTRL_H, Ui.R_CTRL, edge);
        }

        String live = value.display();
        String text = capturing && live.isEmpty() ? Strings.t("key.capture") : live;
        int color = off ? Ui.C_TEXT_OFF : text.isEmpty() ? Ui.C_TEXT_2 : Ui.C_TEXT;

        if (text.isEmpty())
        {
            text = Strings.notSet();
        }

        String shown = Paint.fit(this.font, text, this.ctrlW - Ui.PAD_IN * 2);
        Paint.midText(ctx, this.font, shown, x + (this.ctrlW - this.font.width(shown)) / 2, y, Ui.CTRL_H,
                color, false);

        if (hit != null && ! hit.hard())
        {
            Paint.rect(ctx, x, y + Ui.CTRL_H - Ui.BORDER, this.ctrlW, Ui.BORDER, Ui.C_WARN);
        }
    }

    private void drawScrollbar(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.scrollable())
        {
            return;
        }

        int x = this.contentX + this.contentW + Ui.SCROLL_GAP;
        int thumb = Math.max(Ui.SCROLL_THUMB_MIN, this.bodyH * this.bodyH / this.contentH);
        int span = this.bodyH - thumb;
        int max = this.maxScroll();
        int offset = max == 0 ? 0 : span * this.scroll / max;

        if (Style.vanilla())
        {
            // Ванильная полоса шире на 2 px: сдвиг на 1 влево держит её в зазоре, раскладка та же
            Vanilla.scrollerTrack(ctx, x - 1, this.bodyY, Vanilla.SCROLLER_W, this.bodyH);
            Vanilla.scrollerThumb(ctx, x - 1, this.bodyY + offset, Vanilla.SCROLLER_W, thumb);
            return;
        }

        Paint.round(ctx, x, this.bodyY, Ui.SCROLL_W, this.bodyH, Ui.R_BAR, Ui.C_TRACK);
        Paint.round(ctx, x, this.bodyY + offset, Ui.SCROLL_W, thumb, Ui.R_BAR,
                this.dragScroll ? Ui.C_THUMB_DRAG : Ui.C_THUMB);
    }

    private void drawFooter(GuiContext ctx, int mouseX, int mouseY)
    {
        int y = this.panelY + this.panelH - this.footerH;

        if (Style.vanilla())
        {
            // Подвал без заливки: только разделитель над ним, как в меню Minecraft
            Vanilla.footerSeparator(ctx, this.panelX, y, this.panelW);
        }
        else
        {
            Paint.rect(ctx, this.panelX, y, this.panelW, this.footerH, Ui.C_CHROME);
            Paint.rect(ctx, this.panelX, y, this.panelW, Ui.BORDER, Ui.C_DIVIDER);
        }

        int by = this.footerY;

        boolean accentBack = this.backHighlighted && this.backLabelKey != null;

        this.drawButton(ctx, this.backX, by, this.backW, this.footerBackLabel,
                accentBack ? Ui.C_ON : Ui.C_TEXT_2, true, accentBack, mouseX, mouseY);

        int changed = this.changedInTab(this.tab);
        this.drawButton(ctx, this.tabResetX, by, this.tabResetW, this.tabResetLabel,
                changed > 0 ? Ui.C_TEXT_2 : Ui.C_TEXT_OFF, changed > 0, mouseX, mouseY);

        if (this.confirming())
        {
            int cw = Math.max(this.allResetW, this.confirmW());

            if (Style.vanilla())
            {
                Vanilla.button(ctx, this.confirmX(), by, cw, Ui.BUTTON_H, true, false);
            }
            else
            {
                Paint.round(ctx, this.confirmX(), by, cw, Ui.BUTTON_H, Ui.R_CTRL, Ui.C_PANEL);
                Paint.roundBorder(ctx, this.confirmX(), by, cw, Ui.BUTTON_H, Ui.R_CTRL, Ui.C_DANGER);
            }

            Paint.midText(ctx, this.font, Strings.t("confirm.ask"), this.confirmX() + Ui.BUTTON_PAD_X,
                    by, Ui.BUTTON_H, Ui.C_TEXT_2, false);
            Paint.midText(ctx, this.font, Strings.t("confirm.yes"), this.confirmYesX(), by, Ui.BUTTON_H,
                    Paint.hit(mouseX, mouseY, this.confirmYesX(), by,
                            this.font.width(Strings.t("confirm.yes")), Ui.BUTTON_H)
                            ? Ui.C_TEXT
                            : Ui.C_DANGER, false);
            Paint.midText(ctx, this.font, Strings.t("confirm.no"), this.confirmNoX(), by, Ui.BUTTON_H,
                    Paint.hit(mouseX, mouseY, this.confirmNoX(), by,
                            this.font.width(Strings.t("confirm.no")), Ui.BUTTON_H)
                            ? Ui.C_TEXT
                            : Ui.C_TEXT_2, false);
        }
        else
        {
            this.drawButton(ctx, this.allResetX, by, this.allResetW, Strings.t("reset.all"),
                    Ui.C_DANGER, true, mouseX, mouseY);
        }
    }

    /** Надпись ванильной кнопки по центру: выключенная — серая, иначе заданного цвета, всегда с тенью. */
    private void vanillaLabel(GuiContext ctx, String label, int x, int y, int w, int h, boolean enabled, int tint)
    {
        Paint.midText(ctx, this.font, label, x + (w - this.font.width(label)) / 2, y, h,
                enabled ? tint : Style.V_GRAY, true);
    }

    private void drawButton(GuiContext ctx, int x, int y, int w, String label, int color, boolean enabled,
            int mouseX, int mouseY)
    {
        this.drawButton(ctx, x, y, w, label, color, enabled, false, mouseX, mouseY);
    }

    private void drawButton(GuiContext ctx, int x, int y, int w, String label, int color, boolean enabled,
            boolean accent, int mouseX, int mouseY)
    {
        if (w <= 0)
        {
            return;
        }

        boolean over = enabled && Paint.hit(mouseX, mouseY, x, y, w, Ui.BUTTON_H);

        if (Style.vanilla())
        {
            Vanilla.button(ctx, x, y, w, Ui.BUTTON_H, enabled, over);
            this.vanillaLabel(ctx, Paint.fit(this.font, label, w - Ui.BUTTON_PAD_X * 2), x, y, w, Ui.BUTTON_H,
                    enabled, color == Ui.C_DANGER ? Style.V_RED : accent ? Style.V_HOVER : Style.V_WHITE);
            return;
        }

        if (accent)
        {
            Paint.round(ctx, x, y, w, Ui.BUTTON_H, Ui.R_CTRL, Ui.alpha(color, over ? 0.28F : 0.16F));
        }
        else if (over)
        {
            Paint.round(ctx, x, y, w, Ui.BUTTON_H, Ui.R_CTRL, Ui.C_ROW_HOVER);
        }

        Paint.roundBorder(ctx, x, y, w, Ui.BUTTON_H, Ui.R_CTRL,
                accent ? Ui.alpha(color, over ? 1.0F : 0.7F)
                        : enabled ? Ui.C_DIVIDER : Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF));
        Paint.midText(ctx, this.font, Paint.fit(this.font, label, w - Ui.BUTTON_PAD_X * 2),
                x + Ui.BUTTON_PAD_X, y, Ui.BUTTON_H, enabled ? color : Ui.C_TEXT_OFF, false);
    }

    private void drawDropdown(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.moreBox == null || this.hidden.isEmpty())
        {
            return;
        }

        int w = 0;

        for (Model.HeaderButton button : this.hidden)
        {
            w = Math.max(w, this.font.width(Strings.plain(button.labelKey())) + Ui.HEAD_BTN_PAD_X * 2);
        }

        int h = this.hidden.size() * Ui.HEAD_BTN_H;
        int x = Math.min(this.moreBox.x, this.width - w);
        int y = Math.min(this.panelY + Ui.HEADER_H, this.height - h);

        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, Ui.R_PLATE, Ui.C_CHROME, Ui.C_DIVIDER);
        }

        for (int i = 0; i < this.hidden.size(); ++i)
        {
            Model.HeaderButton button = this.hidden.get(i);
            int by = y + i * Ui.HEAD_BTN_H;
            boolean over = Paint.hit(mouseX, mouseY, x, by, w, Ui.HEAD_BTN_H);

            if (Style.vanilla())
            {
                // Строки меню как у выпадающего списка Minecraft: наведение — белая вуаль, текст белый
                if (over)
                {
                    Paint.rect(ctx, x, by, w, Ui.HEAD_BTN_H, 0x40FFFFFF);
                }

                Paint.midText(ctx, this.font, Strings.plain(button.labelKey()),
                        x + Ui.HEAD_BTN_PAD_X, by, Ui.HEAD_BTN_H, Style.V_WHITE, true);
                continue;
            }

            if (over)
            {
                Paint.round(ctx, x, by, w, Ui.HEAD_BTN_H, Ui.R_ROW, Ui.C_ROW_HOVER);
            }

            Paint.midText(ctx, this.font, Strings.plain(button.labelKey()),
                    x + Ui.HEAD_BTN_PAD_X, by, Ui.HEAD_BTN_H,
                    button.accent() != 0 ? lighten(button.accent()) : button.foreign() ? Ui.C_FOREIGN : Ui.C_TEXT_2, false);
        }
    }

    @Override
    protected void drawButtonHoverTexts(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        Row row = this.rowAt(mouseX, mouseY);
        Object target = this.tipTarget(row);

        if (target == null)
        {
            target = this.clippedTabAt(mouseX, mouseY);
        }

        if (target != this.hoverTarget)
        {
            this.hoverTarget = target;
            this.hoverSince = Util.getMillis();
        }

        if (target == null || Util.getMillis() - this.hoverSince < Ui.TIP_DELAY_MS)
        {
            return;
        }

        List<TipLine> lines = switch (target)
        {
            case Model.Section section -> this.tipFor(section);
            case TabBox box -> List.of(new TipLine(box.full(), Ui.C_TEXT));
            default -> this.tipForSearched(row);
        };

        if (lines.isEmpty())
        {
            return;
        }

        int w = 0;

        for (TipLine line : lines)
        {
            w = Math.max(w, this.font.width(line.text));
        }

        w += Ui.TIP_PAD * 2;
        int h = lines.size() * Ui.TIP_LINE + Ui.TIP_PAD * 2;

        int x = mouseX + Ui.PAD_REL + w <= this.width ? mouseX + Ui.PAD_REL : mouseX - Ui.PAD_REL - w;
        int y = mouseY + h <= this.height ? mouseY : mouseY - h;

        x = Ui.clamp(x, 0, Math.max(0, this.width - w));
        y = Ui.clamp(y, 0, Math.max(0, this.height - h));

        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, Ui.R_PLATE, Ui.C_CHROME, Ui.C_DIVIDER);
        }

        for (int i = 0; i < lines.size(); ++i)
        {
            Paint.text(ctx, this.font, lines.get(i).text, x + Ui.TIP_PAD, y + Ui.TIP_PAD + i * Ui.TIP_LINE,
                    lines.get(i).color, false);
        }
    }

    private @Nullable Object tipTarget(@Nullable Row row)
    {
        if (row == null)
        {
            return null;
        }

        // Карточка подписана сама — всплывающая подсказка закрыла бы её
        if (row.kind == Row.Kind.OPTION)
        {
            return row.option != null && row.option.card != null ? null : row.option;
        }

        if (row.kind == Row.Kind.SECTION && row.section != null
                && (row.section.noteKey != null || row.section.warning.get() != null))
        {
            return row.section;
        }

        return null;
    }

    private @Nullable TabBox clippedTabAt(int mouseX, int mouseY)
    {
        int stripY = this.panelY + Ui.HEADER_H;

        if (mouseY < stripY || mouseY >= stripY + Ui.TABS_H
                || mouseX < this.tabStripX || mouseX >= this.tabStripX + this.tabStripW)
        {
            return null;
        }

        for (TabBox box : this.tabBoxes)
        {
            if (box.clipped() && Paint.hit(mouseX, mouseY, this.tabDrawX(box), box.y, box.w, Ui.TABS_H))
            {
                return box;
            }
        }

        return null;
    }

    private List<TipLine> tipForSearched(@Nullable Row row)
    {
        List<TipLine> lines = this.tipFor(row == null ? null : row.option);

        if (row == null || this.search.isEmpty() || row.section == null || row.section.noteKey == null)
        {
            return lines;
        }

        List<TipLine> out = new ArrayList<>(lines);

        for (String piece : this.wrap(Strings.plain(row.section.noteKey)))
        {
            out.add(new TipLine(piece, Ui.C_TEXT_3));
        }

        return out;
    }

    private List<TipLine> tipFor(Model.Section section)
    {
        List<TipLine> lines = new ArrayList<>();

        if (section.noteKey != null)
        {
            for (String piece : this.wrap(Strings.plain(section.noteKey)))
            {
                lines.add(new TipLine(piece, Ui.C_TEXT));
            }
        }

        String warning = section.warning.get();

        if (warning != null)
        {
            for (String piece : this.wrap(warning))
            {
                lines.add(new TipLine(piece, Ui.C_WARN));
            }
        }

        return lines;
    }

    private List<TipLine> tipFor(@Nullable Model.Option option)
    {
        List<TipLine> lines = new ArrayList<>();

        if (option == null)
        {
            return lines;
        }

        String tip = Strings.raw(this.model.tooltipKey(option.key), "");

        if (! tip.isEmpty())
        {
            for (String piece : this.wrap(tip))
            {
                lines.add(new TipLine(piece, Ui.C_TEXT));
            }
        }

        String defaultText = option.defaultText();

        if (! defaultText.isEmpty())
        {
            lines.add(new TipLine(Strings.t("tip.default", defaultText), Ui.C_TEXT_2));
        }

        if (option.kind == Model.Kind.KEYBIND)
        {
            lines.add(new TipLine(Strings.t("key.hint"), Ui.C_TEXT_2));
        }

        if (option.kind == Model.Kind.INT && option.number != null)
        {
            lines.add(new TipLine(Strings.t("tip.range", option.number.min(), option.number.max()),
                    Ui.C_TEXT_2));

            if (Math.abs(option.number.get()) >= Num.COMPACT_FROM)
            {
                lines.add(new TipLine(Strings.t("tip.exact", option.number.get()), Ui.C_TEXT_2));
            }
        }

        if (option.kind == Model.Kind.DOUBLE && option.decimal != null)
        {
            lines.add(new TipLine(Strings.t("tip.range",
                    Num.format(option.decimal.min(), option.format),
                    Num.format(option.decimal.max(), option.format)), Ui.C_TEXT_2));
        }

        Model.Reason reason = option.gate.get();

        if (reason != null)
        {
            for (String piece : this.wrap(reason.text()))
            {
                lines.add(new TipLine(piece, reason.color()));
            }
        }

        for (String extra : option.foreignTip.get())
        {
            for (String piece : this.wrap(extra))
            {
                lines.add(new TipLine(piece, Ui.C_FOREIGN));
            }
        }

        Conflicts.Hit hit = this.conflicts.get(option.key);

        if (hit != null)
        {
            lines.add(new TipLine(hit.label(), hit.hard() ? Ui.C_DANGER : Ui.C_WARN));
        }

        return lines;
    }

    private List<String> wrap(String text)
    {
        List<String> out = new ArrayList<>();
        int limit = Ui.TIP_MAX_W - Ui.TIP_PAD * 2;
        StringBuilder line = new StringBuilder();

        for (String word : text.split(" "))
        {
            String candidate = line.isEmpty() ? word : line + " " + word;

            if (this.font.width(candidate) > limit && ! line.isEmpty())
            {
                out.add(line.toString());
                line = new StringBuilder(word);
            }
            else
            {
                line = new StringBuilder(candidate);
            }
        }

        if (! line.isEmpty())
        {
            out.add(line.toString());
        }

        return out;
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        int mx = (int) click.x();
        int my = (int) click.y();

        if (this.switcher.click(click, doubleClick))
        {
            return true;
        }

        if (this.captureRow != null && this.captureRow.option != null
                && click.input() != InputConstants.MOUSE_BUTTON_LEFT)
        {
            Model.Option option = this.captureRow.option;

            if (option.hotkey != null)
            {
                option.hotkey.addKey(click.input() - 100);
                this.afterChange();
            }

            return true;
        }

        if (this.dropdownOpen)
        {
            this.clickDropdown(mx, my);

            return true;
        }

        this.commitEdit();

        if (my < this.panelY + Ui.HEADER_H)
        {
            return this.clickHeader(mx, my);
        }

        if (this.clickTabs(mx, my))
        {
            return true;
        }

        if (this.status != null && my >= this.panelY + Ui.HEADER_H + this.tabsH
                && my < this.bodyY && this.status.tabKey() != null)
        {
            Vanilla.click();
            this.jumpTo(this.status.tabKey(), this.status.optionKey());

            return true;
        }

        if (my >= this.footerY && my < this.footerY + Ui.BUTTON_H)
        {
            if (this.clickFooter(mx))
            {
                return true;
            }
        }

        if (this.scrollable())
        {
            int sx = this.contentX + this.contentW + Ui.SCROLL_GAP;

            if (Paint.hit(mx, my, sx, this.bodyY, Ui.SCROLL_W, this.bodyH))
            {
                this.dragScroll = true;
                this.dragScrollGrab = my;

                return true;
            }
        }

        Row row = this.rowAt(mx, my);

        if (row != null && row.kind == Row.Kind.OPTION && row.option != null)
        {
            return this.clickOption(row, row.option, mx, my, click.input());
        }

        this.searchFocus = false;

        return false;
    }

    private boolean clickTabs(int mx, int my)
    {
        int stripY = this.panelY + Ui.HEADER_H;

        if (my < stripY || my >= stripY + Ui.TABS_H)
        {
            return false;
        }

        if (this.tabScrollMax > 0)
        {
            if (Paint.hit(mx, my, this.contentX, stripY, Ui.TAB_ARROW_W, Ui.TABS_H))
            {
                Vanilla.click();
                this.scrollTabs(-Ui.TAB_SCROLL_STEP);

                return true;
            }

            if (Paint.hit(mx, my, this.contentX + this.contentW - Ui.TAB_ARROW_W, stripY,
                    Ui.TAB_ARROW_W, Ui.TABS_H))
            {
                Vanilla.click();
                this.scrollTabs(Ui.TAB_SCROLL_STEP);

                return true;
            }
        }

        if (mx < this.tabStripX || mx >= this.tabStripX + this.tabStripW)
        {
            return false;
        }

        for (TabBox box : this.tabBoxes)
        {
            if (Paint.hit(mx, my, this.tabDrawX(box), box.y, box.w, Ui.TABS_H))
            {
                Vanilla.click();
                this.selectTab(box.index);

                return true;
            }
        }

        return false;
    }

    private void scrollTabs(int delta)
    {
        this.tabScroll += delta;
        this.clampTabScroll();
    }

    private boolean clickHeader(int mx, int my)
    {
        int y = this.panelY + (Ui.HEADER_H - Ui.SEARCH_H) / 2;

        if (Paint.hit(mx, my, this.searchX, y, this.searchW, Ui.SEARCH_H))
        {
            this.searchFocus = true;

            return true;
        }

        int by = this.panelY + (Ui.HEADER_H - Ui.HEAD_BTN_H) / 2;

        for (HeadBox box : this.headBoxes)
        {
            if (Paint.hit(mx, my, box.x, by, box.w, Ui.HEAD_BTN_H) && box.button != null)
            {
                Vanilla.click();
                this.model.save();
                box.button.action().run();

                return true;
            }
        }

        if (this.moreBox != null && Paint.hit(mx, my, this.moreBox.x, by, this.moreBox.w, Ui.HEAD_BTN_H))
        {
            Vanilla.click();
            this.dropdownOpen = true;

            return true;
        }

        this.searchFocus = false;

        return false;
    }

    private void clickDropdown(int mx, int my)
    {
        this.dropdownOpen = false;

        if (this.moreBox == null || this.hidden.isEmpty())
        {
            return;
        }

        int w = 0;

        for (Model.HeaderButton button : this.hidden)
        {
            w = Math.max(w, this.font.width(Strings.plain(button.labelKey())) + Ui.HEAD_BTN_PAD_X * 2);
        }

        int h = this.hidden.size() * Ui.HEAD_BTN_H;
        int x = Math.min(this.moreBox.x, this.width - w);
        int y = Math.min(this.panelY + Ui.HEADER_H, this.height - h);

        for (int i = 0; i < this.hidden.size(); ++i)
        {
            if (Paint.hit(mx, my, x, y + i * Ui.HEAD_BTN_H, w, Ui.HEAD_BTN_H))
            {
                Vanilla.click();
                this.model.save();
                this.hidden.get(i).action().run();

                return;
            }
        }
    }

    private boolean clickFooter(int mx)
    {
        if (this.confirming())
        {
            int yesX = this.confirmYesX();
            int noX = this.confirmNoX();

            if (mx >= yesX && mx < yesX + this.font.width(Strings.t("confirm.yes")))
            {
                Vanilla.click();
                this.confirmAllUntil = 0L;
                this.model.resetAll();
                this.afterChange();
            }
            else
            {
                if (mx >= noX && mx < noX + this.font.width(Strings.t("confirm.no")))
                {
                    Vanilla.click();
                }

                this.confirmAllUntil = 0L;
            }

            return true;
        }

        if (Paint.hit(mx, this.footerY, this.backX, this.footerY, this.backW, Ui.BUTTON_H))
        {
            Vanilla.click();
            this.goBack();

            return true;
        }

        if (Paint.hit(mx, this.footerY, this.tabResetX, this.footerY, this.tabResetW, Ui.BUTTON_H))
        {
            if (this.changedInTab(this.tab) > 0)
            {
                Vanilla.click();

                for (Model.Option option : this.tabs.get(this.tab).allOptions())
                {
                    option.resetToDefault();
                }

                this.afterChange();
            }

            return true;
        }

        if (Paint.hit(mx, this.footerY, this.allResetX, this.footerY, this.allResetW, Ui.BUTTON_H))
        {
            Vanilla.click();
            this.confirmAllUntil = Util.getMillis() + Ui.CONFIRM_MS;

            return true;
        }

        return false;
    }

    private boolean clickOption(Row row, Model.Option option, int mx, int my, int button)
    {
        if (option.gate.get() != null)
        {
            return true;
        }

        this.focus = this.focusRows.indexOf(row);
        this.searchFocus = false;

        int top = this.rowTop(row);

        if (option.card != null)
        {
            return option.card.click(mx, my, this.contentX + row.indent, top, this.contentW - row.indent, row.h);
        }

        Layout layout = layoutMode();

        // Сетка: сброса-кнопки нет — правый клик по плитке возвращает значение по умолчанию
        if (layout == Layout.GRID && button == InputConstants.MOUSE_BUTTON_RIGHT)
        {
            if (option.kind != Model.Kind.ACTION && (! option.isDefault() || option.kind == Model.Kind.KEYBIND))
            {
                Vanilla.click();
                this.captureRow = null;
                option.resetToDefault();
                this.afterChange();
            }

            return true;
        }

        if (layout == Layout.ROWS && this.plusResetW() > 0 && option.kind != Model.Kind.ACTION)
        {
            if (Paint.hit(mx, my, this.resetX(), top, this.plusResetW(), Ui.ROW_H))
            {
                if (! option.isDefault() || option.kind == Model.Kind.KEYBIND)
                {
                    Vanilla.click();
                    this.captureRow = null;
                    option.resetToDefault();
                    this.afterChange();
                }

                return true;
            }
        }

        if (layout == Layout.AELL && this.resetShown(option))
        {
            int rx = this.resetX();
            int ry = top + (Ui.ROW_H - Ui.RESET_W) / 2;

            if (Paint.hit(mx, my, rx, ry, Ui.RESET_W, Ui.RESET_W))
            {
                Vanilla.click();
                this.captureRow = null;
                option.resetToDefault();
                this.afterChange();

                return true;
            }
        }

        int cx = layout == Layout.GRID ? row.x : this.ctrlX();
        int cy = layout == Layout.AELL ? top + (Ui.ROW_H - Ui.CTRL_H) / 2 : top;
        int cw = layout == Layout.GRID ? row.w : this.ctrlW;
        int ch = layout == Layout.AELL ? Ui.CTRL_H : layout == Layout.GRID ? TILE_H : Ui.ROW_H;

        if (! Paint.hit(mx, my, cx, cy, cw, ch))
        {
            return true;
        }

        this.pressedRow = row;

        switch (option.kind)
        {
            case TOGGLE ->
            {
                if (option.bool != null)
                {
                    Vanilla.click();
                    this.flip(option);
                }
            }
            case INT, DOUBLE ->
            {
                int fx = cx + this.trackW() + Ui.PAD_IN;

                if (layout == Layout.AELL && option.kind == Model.Kind.INT && Paint.hit(mx, my, fx, cy, Ui.FIELD_W, Ui.CTRL_H))
                {
                    Vanilla.click();
                    this.editRow = row;
                    this.editText = option.number == null ? "" : String.valueOf(option.number.get());
                }
                else
                {
                    this.dragRow = row;
                    this.applySlider(row, option, mx);
                }
            }
            case KEYBIND ->
            {
                Vanilla.click();

                if (this.captureRow == row)
                {
                    // Повторный клик по полю: ничего не нажато — вернуть прежнее значение
                    this.cancelCapture(option);
                }
                else
                {
                    this.captureRow = row;

                    if (option.hotkey != null)
                    {
                        this.captureKeys = List.copyOf(option.hotkey.keys());
                        option.hotkey.clearKeys();
                    }
                }
            }
            case ACTION ->
            {
                if (option.action != null)
                {
                    Vanilla.click();
                }

                this.runAction(option);
            }
            case CHOICE ->
            {
                Vanilla.click();
                this.cycle(option);
            }
        }

        return true;
    }

    private void cancelCapture(Model.Option option)
    {
        this.captureRow = null;

        if (option.hotkey == null || ! option.hotkey.keys().isEmpty() || this.captureKeys.isEmpty())
        {
            this.captureKeys = List.of();

            return;
        }

        for (int keyCode : this.captureKeys)
        {
            option.hotkey.addKey(keyCode);
        }

        this.captureKeys = List.of();
        this.afterChange();
    }

    /** Ввод закончен: что нажато, то и записано (ничего — клавиша снята); прежнее не возвращаем. */
    private void finishCapture()
    {
        this.captureRow = null;
        this.captureKeys = List.of();
        this.afterChange();
    }

    private void runAction(Model.Option option)
    {
        if (option.action != null)
        {
            this.model.save();
            option.action.run();
        }
    }

    private void applySlider(Row row, Model.Option option, int mx)
    {
        Layout layout = layoutMode();
        int x = layout == Layout.GRID ? row.x : this.ctrlX();
        int tw = layout == Layout.GRID ? row.w : layout == Layout.ROWS ? this.ctrlW : this.trackW();
        // Ванильные плитки: ручка шириной Vanilla.HANDLE_W, доля — по её центру, как рисует sliderHandleAt
        float fraction = layout == Layout.AELL ? Ui.clamp01((float) (mx - x) / tw)
                : Ui.clamp01((float) (mx - x - Vanilla.HANDLE_W / 2) / Math.max(1, tw - Vanilla.HANDLE_W));

        if (option.kind == Model.Kind.INT && option.number != null)
        {
            option.number.set(Num.fromFraction(fraction, option.number.min(), option.number.max(),
                    option.scale));
        }
        else if (option.kind == Model.Kind.DOUBLE && option.decimal != null)
        {
            Values.Dbl value = option.decimal;
            double raw = value.min() + (value.max() - value.min()) * fraction;
            value.set(Num.snap(raw, value.min(), value.max(), value.step()));
        }

        this.afterChange();
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (this.dragRow != null && this.dragRow.option != null)
        {
            this.applySlider(this.dragRow, this.dragRow.option, (int) click.x());

            return true;
        }

        if (this.dragScroll)
        {
            int delta = (int) click.y() - this.dragScrollGrab;
            this.dragScrollGrab = (int) click.y();
            int span = Math.max(1, this.bodyH - Math.max(Ui.SCROLL_THUMB_MIN,
                    this.bodyH * this.bodyH / Math.max(1, this.contentH)));
            this.scroll += delta * this.maxScroll() / span;
            this.clampScroll();

            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        if (this.dragRow != null)
        {
            // Ползунок звучит на отпускание, как AbstractSliderButton
            Vanilla.click();
        }

        this.dragRow = null;
        this.dragScroll = false;
        this.pressedRow = null;

        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical)
    {
        if (this.switcher.scroll(mouseX, mouseY, horizontal, vertical))
        {
            return true;
        }

        int stripY = this.panelY + Ui.HEADER_H;

        if (this.tabScrollMax > 0 && mouseY >= stripY && mouseY < stripY + Ui.TABS_H)
        {
            double wheel = horizontal != 0.0D ? horizontal : -vertical;

            if (wheel != 0.0D)
            {
                this.scrollTabs((int) Math.signum(wheel) * Ui.TAB_SCROLL_STEP);
            }

            return true;
        }

        if (vertical == 0.0D || ! this.scrollable())
        {
            return false;
        }

        if (mouseY < this.bodyY || mouseY > this.bodyY + this.bodyH)
        {
            return false;
        }

        this.scroll -= (int) Math.signum(vertical) * Ui.SCROLL_WHEEL_ROWS * (Ui.ROW_H + Ui.ROW_GAP);
        this.clampScroll();

        return true;
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        int key = input.key();

        if (this.switcher.key(input))
        {
            return true;
        }

        if (this.captureRow != null && this.captureRow.option != null)
        {
            Model.Option option = this.captureRow.option;

            // Esc заканчивает ввод тем, что нажато; ничего не нажато — клавиша остаётся не
            // заданной (как в malilib). Прежнее значение возвращает повторный клик по полю,
            // значение по умолчанию — кнопка сброса в строке
            if (key == InputConstants.KEY_ESCAPE)
            {
                this.finishCapture();
            }
            else if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE)
            {
                if (option.hotkey != null)
                {
                    option.hotkey.clearKeys();
                }

                this.finishCapture();
            }
            else if (option.hotkey != null)
            {
                option.hotkey.addKey(key);
                this.afterChange();
            }

            return true;
        }

        if (this.editRow != null)
        {
            switch (key)
            {
                case InputConstants.KEY_ESCAPE, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER ->
                {
                    this.commitEdit();

                    return true;
                }
                case InputConstants.KEY_BACKSPACE ->
                {
                    if (! this.editText.isEmpty())
                    {
                        this.editText = this.editText.substring(0, this.editText.length() - 1);
                    }

                    return true;
                }
                case InputConstants.KEY_UP, InputConstants.KEY_DOWN ->
                {
                    this.stepEdit(key == InputConstants.KEY_UP);

                    return true;
                }
                default ->
                {
                    return true;
                }
            }
        }

        if (this.searchFocus)
        {
            switch (key)
            {
                case InputConstants.KEY_ESCAPE ->
                {
                    if (this.search.isEmpty())
                    {
                        this.goBack();
                    }
                    else
                    {
                        this.search = "";
                        this.searchFocus = false;
                        this.scroll = SCROLL_MEMORY.getOrDefault(this.scrollKey(), 0);
                        this.layout();
                    }

                    return true;
                }
                case InputConstants.KEY_BACKSPACE ->
                {
                    if (! this.search.isEmpty())
                    {
                        this.search = this.search.substring(0, this.search.length() - 1);
                        this.scroll = 0;
                        this.layout();
                    }

                    return true;
                }
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER ->
                {
                    this.searchFocus = false;

                    return true;
                }
                default ->
                {
                    if (key != InputConstants.KEY_TAB && ! GuiBase.isCtrlDown())
                    {
                        return true;
                    }
                }
            }
        }

        if (key == InputConstants.KEY_F && GuiBase.isCtrlDown())
        {
            this.searchFocus = true;

            return true;
        }

        switch (key)
        {
            case InputConstants.KEY_ESCAPE ->
            {
                if (this.dropdownOpen)
                {
                    this.dropdownOpen = false;
                }
                else if (this.search.isEmpty())
                {
                    this.goBack();
                }
                else
                {
                    this.search = "";
                    this.scroll = SCROLL_MEMORY.getOrDefault(this.scrollKey(), 0);
                    this.layout();
                }

                return true;
            }
            case InputConstants.KEY_TAB ->
            {
                this.moveFocus(GuiBase.isShiftDown() ? -1 : 1);

                return true;
            }
            case InputConstants.KEY_DOWN ->
            {
                this.moveFocus(1);

                return true;
            }
            case InputConstants.KEY_UP ->
            {
                this.moveFocus(-1);

                return true;
            }
            case InputConstants.KEY_LEFT ->
            {
                this.selectTab(this.neighbourTab(-1));

                return true;
            }
            case InputConstants.KEY_RIGHT ->
            {
                this.selectTab(this.neighbourTab(1));

                return true;
            }
            case InputConstants.KEY_PAGEUP ->
            {
                this.scroll -= this.bodyH;
                this.clampScroll();

                return true;
            }
            case InputConstants.KEY_PAGEDOWN ->
            {
                this.scroll += this.bodyH;
                this.clampScroll();

                return true;
            }
            case InputConstants.KEY_HOME ->
            {
                this.scroll = 0;

                return true;
            }
            case InputConstants.KEY_END ->
            {
                this.scroll = this.maxScroll();

                return true;
            }
            case InputConstants.KEY_SPACE, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER ->
            {
                this.activateFocused();

                return true;
            }
            default -> { }
        }

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onCharTyped(CharacterEvent event)
    {
        char c = (char) event.codepoint();

        if (this.editRow != null)
        {
            if (c >= '0' && c <= '9')
            {
                this.editText += c;
            }

            return true;
        }

        if (this.searchFocus && c >= ' ')
        {
            this.search += c;
            this.scroll = 0;
            this.layout();

            return true;
        }

        return super.onCharTyped(event);
    }

    private void activateFocused()
    {
        if (this.focus < 0 || this.focus >= this.focusRows.size())
        {
            return;
        }

        Row row = this.focusRows.get(this.focus);
        Model.Option option = row.option;

        if (option == null)
        {
            return;
        }

        switch (option.kind)
        {
            case TOGGLE ->
            {
                if (option.bool != null)
                {
                    this.flip(option);
                }
            }
            case KEYBIND ->
            {
                this.captureRow = row;

                if (option.hotkey != null)
                {
                    this.captureKeys = List.copyOf(option.hotkey.keys());
                    option.hotkey.clearKeys();
                }
            }
            case INT ->
            {
                this.editRow = row;
                this.editText = option.number == null ? "" : String.valueOf(option.number.get());
            }
            case DOUBLE -> { }
            case ACTION -> this.runAction(option);
            case CHOICE -> this.cycle(option);
        }
    }

    private void stepEdit(boolean up)
    {
        Row row = this.editRow;

        if (row == null || row.option == null || row.option.number == null)
        {
            return;
        }

        int current = this.parseEdit(row.option.number.get());
        int step = GuiBase.isShiftDown() ? Ui.STEP_FAST : 1;
        int next = Ui.clamp(current + (up ? step : -step), row.option.number.min(),
                row.option.number.max());

        this.editText = String.valueOf(next);
    }

    private int parseEdit(int fallback)
    {
        try
        {
            return Integer.parseInt(this.editText);
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private void commitEdit()
    {
        Row row = this.editRow;

        if (row == null || row.option == null || row.option.number == null)
        {
            this.editRow = null;

            return;
        }

        Values.Int value = row.option.number;

        if (this.editText.isEmpty())
        {
            this.editRow = null;

            return;
        }

        int parsed;

        try
        {
            parsed = Integer.parseInt(this.editText);
        }
        catch (NumberFormatException e)
        {
            this.editRow = null;

            return;
        }

        if (parsed < value.min() || parsed > value.max())
        {
            this.blinkUntil = Util.getMillis() + Ui.BLINK_MS;
        }

        value.set(Ui.clamp(parsed, value.min(), value.max()));
        this.editRow = null;
        this.afterChange();
    }

    private void moveFocus(int delta)
    {
        if (this.focusRows.isEmpty())
        {
            return;
        }

        int next = this.focus + delta;

        if (next < 0)
        {
            next = this.focusRows.size() - 1;
        }
        else if (next >= this.focusRows.size())
        {
            next = 0;
        }

        this.focus = next;
        this.searchFocus = false;
        this.scrollIntoView(this.focusRows.get(next));
    }

    private void scrollIntoView(Row row)
    {
        int margin = Ui.PAD_IN;
        int top = row.y - margin;
        int bottom = row.y + row.h + margin;

        if (top < this.scroll)
        {
            this.scroll = top;
        }
        else if (bottom > this.scroll + this.bodyH)
        {
            this.scroll = bottom - this.bodyH;
        }

        this.clampScroll();
    }

    private void selectTab(int index)
    {
        Model.Tab target = this.tabs.isEmpty() ? null
                : this.tabs.get(Ui.clamp(index, 0, this.tabs.size() - 1));

        // Вкладка-ссылка: открыть её экран поверх, а здесь остаться на прежней вкладке —
        // по возврату из стека Nav игрок попадает туда же, где был
        if (target != null && target.opens != null)
        {
            this.model.save();
            Nav.open(target.opens.get(), this);

            return;
        }

        if (index == this.tab && this.search.isEmpty())
        {
            return;
        }

        SCROLL_MEMORY.put(this.scrollKey(), this.scroll);

        this.model.save();

        this.tab = Ui.clamp(index, 0, Math.max(0, this.tabs.size() - 1));
        TAB_MEMORY.put(this.model.modId(), this.tab);
        this.search = "";
        this.searchFocus = false;
        this.focus = -1;
        this.editRow = null;

        if (this.captureRow != null && this.captureRow.option != null)
        {
            this.cancelCapture(this.captureRow.option);
        }
        this.scroll = SCROLL_MEMORY.getOrDefault(this.scrollKey(), 0);
        this.layout();
        this.showTabInStrip(this.tab);
    }

    private void jumpTo(String tabKey, @Nullable String optionKey)
    {
        for (int i = 0; i < this.tabs.size(); ++i)
        {
            if (this.tabs.get(i).key.equals(tabKey))
            {
                this.selectTab(i);
                break;
            }
        }

        this.jumpKey = optionKey;
        this.jumpUntil = Util.getMillis() + Ui.JUMP_HIGHLIGHT_MS;

        if (optionKey == null)
        {
            return;
        }

        for (Row row : this.rows)
        {
            if (row.kind == Row.Kind.OPTION && row.option != null && row.option.key.equals(optionKey))
            {
                this.scrollIntoView(row);
                break;
            }
        }
    }

    private void goBack()
    {
        this.model.save();
        Nav.back();
    }

    private void afterChange()
    {
        InputEventHandler.getKeybindManager().updateUsedKeys();
        this.conflicts = Conflicts.scan(this.allKeybindOptions(), this::nameOf, this.model.maliModName());
        this.layout();
    }

    private boolean headerButtonsChanged()
    {
        return this.model.headerButtons().size() != this.headBoxes.size() + this.hidden.size();
    }

    private boolean scrollable()
    {
        return this.bodyH > 0 && this.contentH > this.bodyH;
    }

    private int maxScroll()
    {
        return Math.max(0, this.contentH - this.bodyH);
    }

    private void clampScroll()
    {
        this.scroll = Ui.clamp(this.scroll, 0, this.maxScroll());
        SCROLL_MEMORY.put(this.scrollKey(), this.scroll);
    }

    private String scrollKey()
    {
        return this.search.isEmpty()
                ? this.model.modId() + "#" + this.tab
                : this.model.modId() + "#search";
    }

    private int changedInTab(int index)
    {
        if (index < 0 || index >= this.tabs.size())
        {
            return 0;
        }

        int count = 0;

        for (Model.Option option : this.tabs.get(index).allOptions())
        {
            if (! option.isDefault())
            {
                ++count;
            }
        }

        return count;
    }

    private @Nullable Row rowAt(int mx, int my)
    {
        if (my < this.bodyY || my >= this.bodyY + this.bodyH || mx < this.panelX
                || mx >= this.panelX + this.panelW)
        {
            return null;
        }

        for (Row row : this.rows)
        {
            int top = this.rowTop(row);

            if (my >= top && my < top + row.h && (row.x < 0 || (mx >= row.x && mx < row.x + row.w)))
            {
                return row;
            }
        }

        return null;
    }

    private String nameOf(String optionKey)
    {
        return Strings.raw(this.model.nameKey(optionKey), optionKey);
    }

    private List<Model.Option> allKeybindOptions()
    {
        List<Model.Option> out = new ArrayList<>();

        for (Model.Tab modelTab : this.tabs)
        {
            for (Model.Option option : modelTab.allOptions())
            {
                if (option.kind == Model.Kind.KEYBIND)
                {
                    out.add(option);
                }
            }
        }

        return out;
    }

    private int @Nullable [] match(Model.Option option, String needle)
    {
        String name = this.nameOf(option.key);
        int at = name.toLowerCase(Locale.ROOT).indexOf(needle);

        if (at >= 0)
        {
            return new int[] { at, needle.length() };
        }

        if (option.key.toLowerCase(Locale.ROOT).contains(needle))
        {
            return new int[] { -1, 0 };
        }

        String tip = Strings.raw(this.model.tooltipKey(option.key), "");

        return tip.toLowerCase(Locale.ROOT).contains(needle) ? new int[] { -1, 0 } : null;
    }

    @Override
    public void resize(int width, int height)
    {
        super.resize(width, height);

    }

    @Override
    public void removed()
    {
        super.removed();

        this.commitEdit();

        if (this.captureRow != null && this.captureRow.option != null)
        {
            this.cancelCapture(this.captureRow.option);
        }

        SCROLL_MEMORY.put(this.scrollKey(), this.scroll);
        this.search = "";
        this.model.save();
    }

    private record TipLine(String text, int color) {}

    private static int iconRoom(@Nullable Identifier icon)
    {
        return icon == null ? 0 : Ui.TAB_ICON + Ui.TAB_ICON_GAP;
    }

    private static int opensRoom(Model.Tab tab)
    {
        return tab.opens == null ? 0 : Ui.TAB_ICON_GAP + Ui.TAB_ARROW_GLYPH_W;
    }

    /** Ближайшая вкладка с настройками в сторону {@code step}; таких нет — остаёмся на текущей. */
    private int neighbourTab(int step)
    {
        for (int i = this.tab + step; i >= 0 && i < this.tabs.size(); i += step)
        {
            if (this.tabs.get(i).opens == null)
            {
                return i;
            }
        }

        return this.tab;
    }

    /** Вкладка-ссылка не может быть текущей: вместо неё — ближайшая обычная слева, потом справа. */
    private int contentTab(int index)
    {
        int clamped = Ui.clamp(index, 0, Math.max(0, this.tabs.size() - 1));

        if (this.tabs.isEmpty() || this.tabs.get(clamped).opens == null)
        {
            return clamped;
        }

        for (int i = clamped - 1; i >= 0; --i)
        {
            if (this.tabs.get(i).opens == null)
            {
                return i;
            }
        }

        for (int i = clamped + 1; i < this.tabs.size(); ++i)
        {
            if (this.tabs.get(i).opens == null)
            {
                return i;
            }
        }

        return clamped;
    }

    private record TabBox(int index, String label, String full, int x, int y, int w, int pad,
            @Nullable Identifier icon)
    {
        boolean clipped()
        {
            return ! this.label.equals(this.full);
        }
    }

    private record HeadBox(@Nullable Model.HeaderButton button, String label, int x, int w, boolean foreign) {}

    private static final class Row
    {
        enum Kind { SECTION, OPTION }

        final Kind kind;
        final @Nullable Model.Section section;
        final @Nullable Model.Option option;
        final @Nullable String tabPrefix;
        final int matchFrom;
        final int matchLen;

        int y;
        int h;
        int indent;
        int swatchCol;
        /** Клетка сетки: левый край и ширина; {@code -1} — строка во всю ширину. */
        int x = -1;
        int w;

        private Row(Kind kind, @Nullable Model.Section section, @Nullable Model.Option option,
                @Nullable String tabPrefix, int matchFrom, int matchLen)
        {
            this.kind = kind;
            this.section = section;
            this.option = option;
            this.tabPrefix = tabPrefix;
            this.matchFrom = matchFrom;
            this.matchLen = matchLen;
        }

        static Row section(Model.Section section)
        {
            return new Row(Kind.SECTION, section, null, null, -1, 0);
        }

        static Row option(Model.Option option, Model.Section section, @Nullable String tabPrefix,
                int matchFrom, int matchLen)
        {
            return new Row(Kind.OPTION, section, option, tabPrefix, matchFrom, matchLen);
        }
    }
}
