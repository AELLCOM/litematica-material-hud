package com.aell.layerlist.config;

import com.aell.layerlist.api.CountScope;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.ghost.GhostAlpha;
import com.aell.layerlist.ghost.GhostAlphaModule;
import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.gui.GuiHudPosition;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.storage.StorageSearch;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.shelf.ShelfRenderer;
import com.aell.layerlist.verify.LayerVerifier;
import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Num;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.SettingsModel;
import com.aell.layerlist.shaded.ui.SharedOptions;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Values;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class LayerListSettings implements SettingsModel
{
    /**
     * Отдельный мод EasyPlaceFixPlus: те же опции Easy Place под теми же именами. Пока он стоит,
     * движок ведёт он, а эта вкладка правит его же объекты — значения у обоих модов одни.
     */
    private static final SharedOptions EP_SHARED =
            SharedOptions.of("com.aell.easyplacefixplus.compat.EasyPlaceBridge");

    /**
     * Клавиша-переключатель Easy Place: пока стоит Easy Place Fix+, который её знает, — его объект
     * (его же обработчик и сработает), иначе своя. Две живые клавиши на одну опцию переключали бы её дважды.
     */
    public static fi.dy.masa.malilib.config.options.ConfigHotkey epHotkey(fi.dy.masa.malilib.config.options.ConfigHotkey own)
    {
        return EP_SHARED.option(own.getName()) instanceof fi.dy.masa.malilib.config.options.ConfigHotkey shared ? shared : own;
    }

    /** Опция Easy Place — общая с Easy Place Fix+, если он стоит. */
    public static fi.dy.masa.malilib.config.options.ConfigBoolean epOption(fi.dy.masa.malilib.config.options.ConfigBoolean own)
    {
        return EP_SHARED.bool(own);
    }

    /** Записать опции Easy Place: свои и, если стоит Easy Place Fix+, его файл. */
    public static void saveEasyPlace()
    {
        EP_SHARED.pull(LayerListConfigs.EP_OPTIONS);
        new LayerListConfigs().save();
        EP_SHARED.saveSibling();
    }

    public static final String MOD_ID = "layerlist";
    public static final String MOD_NAME = "Litematica Material HUD";
    public static final String MALI_MOD_NAME = "Litematica Material HUD";

    public static final net.minecraft.resources.Identifier ICON =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/mod_icon.png");

    public static final net.minecraft.resources.Identifier TAB_GHOST_ICON =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tab_ghost.png");

    public static final net.minecraft.resources.Identifier TAB_EASY_PLACE_ICON =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tab_easy_place.png");

    public static final net.minecraft.resources.Identifier TAB_ZONE_FOCUS_ICON =
            net.minecraft.resources.Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/tab_zone_focus.png");

    private static final String[] BUDGET_NEIGHBOURS = { "zonefocus" };

    private static final int SLOW_REFRESH_TICKS = 100;

    /** Полноэкранный режим общего экрана настроек — читается библиотекой при каждой раскладке. */
    private static final Values.Bool FULLSCREEN = Values.of(LayerListConfigs.SETTINGS_FULLSCREEN);

    private static final LayerListSettings INSTANCE = new LayerListSettings();

    private @Nullable List<Model.Tab> tabs;

    private int addonTabCount;

    private LayerListSettings() {}

    public static LayerListSettings getInstance()
    {
        return INSTANCE;
    }

    @Override
    public String modName()
    {
        return MOD_NAME;
    }

    @Override
    public String modId()
    {
        return MOD_ID;
    }

    @Override
    public String nameKey(String optionKey)
    {
        return optionKey.indexOf('.') >= 0
                ? optionKey + ".name"
                : MOD_ID + ".config." + optionKey + ".name";
    }

    @Override
    public String tooltipKey(String optionKey)
    {
        return optionKey.indexOf('.') >= 0
                ? optionKey + ".tooltip"
                : MOD_ID + ".config." + optionKey + ".tooltip";
    }

    @Override
    public String maliModName()
    {
        return MALI_MOD_NAME;
    }

    @Override
    public net.minecraft.resources.Identifier icon()
    {
        return ICON;
    }

    @Override
    public Values.Bool fullscreen()
    {
        return FULLSCREEN;
    }

    @Override
    public List<Model.Tab> tabs()
    {
        int addons = LayerListApi.getConfigTabs().size();

        if (this.tabs == null || this.addonTabCount != addons)
        {
            this.tabs = buildTabs();
            this.addonTabCount = addons;
        }

        return this.tabs;
    }

    @Override
    public void save()
    {
        // Вкладки Easy Place и Zone Focus правят объекты соседей, если те стоят: забираем
        // значения к себе, чтобы свой файл не отставал, и просим соседей записать их собственные
        EP_SHARED.pull(LayerListConfigs.EP_OPTIONS);

        new LayerListConfigs().save();
        EP_SHARED.saveSibling();
        com.aell.layerlist.zonefocus.ZoneFocusTab.SHARED.saveSibling();
        GhostAlpha.save();

        for (LayerListApi.AddonTab addon : LayerListApi.getConfigTabs())
        {
            if (addon.onSave() != null)
            {
                addon.onSave().run();
            }
        }
    }

    @Override
    public void resetAll()
    {
        LayerListDefaults.resetEverything();

        for (LayerListApi.AddonTab addon : LayerListApi.getConfigTabs())
        {
            for (LayerListApi.AddonSection section : addon.sections())
            {
                for (LayerListApi.AddonOption option : section.options())
                {
                    resetAddonOption(option);
                }
            }

            if (addon.onSave() != null)
            {
                addon.onSave().run();
            }
        }
    }

    private static void resetAddonOption(LayerListApi.AddonOption option)
    {
        switch (option)
        {
            case LayerListApi.AddonToggle toggle -> toggle.setter().accept(toggle.defaultValue());
            case LayerListApi.AddonSlider slider -> slider.setter().accept(slider.defaultValue());
        }
    }

    @Override
    public List<Model.HeaderButton> headerButtons()
    {
        List<Model.HeaderButton> buttons = new ArrayList<>();

        // «Схемы» — главный вход в модуль схем (вкладки-ссылки больше нет), выделен приглушённым акцентом
        buttons.add(new Model.HeaderButton("layerlist.gui.schematics", false,
                () -> GuiSchematics.open(fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen()),
                com.aell.layerlist.shaded.ui.Ui.C_ACCENT));

        for (LayerListApi.AddonScreen entry : LayerListApi.getConfigButtons())
        {
            buttons.add(new Model.HeaderButton(entry.labelKey(), true,
                    () -> com.aell.layerlist.shaded.ui.Nav.open(entry.factory().get(),
                            fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen())));
        }

        return buttons;
    }

    @Override
    public @Nullable Model.Status status()
    {
        if (! hasEnabledPlacement())
        {
            return new Model.Status(Strings.t("status.no_schematic"), Ui.C_WARN, null, null);
        }

        Warning warning = firstWarning();

        if (warning != null)
        {
            return new Model.Status(warning.text, Ui.C_WARN, warning.tabKey, warning.optionKey);
        }

        if (LayerListConfigs.VERIFY_ENABLED.getBooleanValue() && noVerifyCategory())
        {
            return new Model.Status(text("warn.no_verify_category"), Ui.C_WARN,
                    "layerlist.gui.tab_verification", "verifyMissing");
        }

        if (foreignScopeActive())
        {
            CountScope scope = LayerListApi.getCountScope();
            String label = scope == null ? "" : scope.getLabel();

            return new Model.Status(label.isEmpty()
                    ? text("status.foreign_scope")
                    : text("status.foreign_scope_named") + " " + label,
                    Ui.C_FOREIGN, "layerlist.gui.tab_search", "searchFollowsPage");
        }

        return null;
    }

    /** Акцент раздела стиля — тёплый золотой, отдельно от синего Aell UI (22.10.2026). */
    private static final int UI_STYLE_ACCENT = 0xFFE0A030;

    private static List<Model.Tab> buildTabs()
    {
        List<Model.Tab> tabs = new ArrayList<>();

        tabs.add(new Model.Tab("layerlist.gui.tab_hud").add(
                // Общий переключатель стиля: отдельный акцент, чтобы не путать с обычными разделами
                new Model.Section("layerlist.section.ui_style")
                        .note("layerlist.section.ui_style.note")
                        .accent(UI_STYLE_ACCENT)
                        .add(
                        Model.Option.choice("uiStyle", Values.choice(LayerListConfigs.UI_STYLE,
                                com.aell.layerlist.shaded.ui.Style.LEVEL_KEYS))),
                new Model.Section("layerlist.section.panel_content")
                        .warning(() -> warningText("layerlist.section.panel_content"))
                        .add(
                        Model.Option.toggle("onlyMissing", Values.of(LayerListConfigs.ONLY_MISSING)),
                        Model.Option.toggle("useStock", Values.of(LayerListConfigs.USE_STOCK)),
                        Model.Option.toggle("pinAcrossDimensions",
                                Values.of(LayerListConfigs.PIN_ACROSS_DIMENSIONS)),
                        Model.Option.toggle("countEntities", Values.of(LayerListConfigs.COUNT_ENTITIES)),
                        Model.Option.toggle("shortfallOnlyMissing",
                                Values.of(LayerListConfigs.SHORTFALL_ONLY_MISSING)),
                        Model.Option.toggle("showShortfall", Values.of(LayerListConfigs.SHOW_SHORTFALL))
                                .swatch(LayerHudRenderer::badColor),
                        Model.Option.toggle("showProgressBar", Values.of(LayerListConfigs.SHOW_PROGRESS_BAR))
                                .swatch(LayerHudRenderer::okColor),
                        Model.Option.toggle("hudCountInBoxes", Values.of(LayerListConfigs.HUD_COUNT_IN_BOXES)),
                        Model.Option.toggle("sortByName", Values.of(LayerListConfigs.SORT_BY_NAME)),
                        Model.Option.integer("itemsPerPage", Values.of(LayerListConfigs.ITEMS_PER_PAGE),
                                Num.Scale.LINEAR)),
                new Model.Section("layerlist.section.material_cutoff")
                        .note("layerlist.section.material_cutoff.note")
                        .accent(Ui.C_ACCENT)
                        .add(
                        Model.Option.toggle("materialCutoff", Values.of(LayerListConfigs.MATERIAL_CUTOFF)),
                        Model.Option.integer("materialCutoffY", Values.of(LayerListConfigs.MATERIAL_CUTOFF_Y),
                                        Num.Scale.LINEAR)
                                .child().gate(LayerListSettings::cutoffGate)),
                new Model.Section("layerlist.section.placement").add(
                        Model.Option.toggle("replaceLitematicaHud",
                                Values.of(LayerListConfigs.REPLACE_LITEMATICA_HUD)),
                        Model.Option.toggle("hudCustomPosition",
                                Values.of(LayerListConfigs.HUD_CUSTOM_POSITION)),
                        Model.Option.action("hudPositionScreen", GuiHudPosition::openFromSettings),
                        Model.Option.toggle("hudBesideContainer",
                                Values.of(LayerListConfigs.HUD_BESIDE_CONTAINER)),
                        Model.Option.toggle("hudDockRight", Values.of(LayerListConfigs.HUD_DOCK_RIGHT))
                                .child().gate(LayerListSettings::dockGate),
                        Model.Option.toggle("hudTransferButton", Values.of(LayerListConfigs.HUD_TRANSFER_BUTTON))
                                .child().gate(LayerListSettings::dockGate),
                        Model.Option.toggle("hudTransferAuto", Values.of(LayerListConfigs.HUD_TRANSFER_AUTO))
                                .child().gate(LayerListSettings::transferGate),
                        Model.Option.toggle("hudTransferInstant", Values.of(LayerListConfigs.HUD_TRANSFER_INSTANT))
                                .child().gate(LayerListSettings::transferGate),
                        Model.Option.toggle("hudTransferAutoClose", Values.of(LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE))
                                .child().gate(LayerListSettings::transferGate),
                        Model.Option.toggle("hudTransferShulkers", Values.of(LayerListConfigs.HUD_TRANSFER_SHULKERS))
                                .child().gate(LayerListSettings::transferGate),
                        Model.Option.decimal("hudScale",
                                        Values.of(LayerListConfigs.HUD_SCALE, LayerListConfigs.HUD_SCALE_STEP),
                                        "%.2f×")
                                .preview(new ScalePreview())),
                new Model.Section("layerlist.section.settings_screen").add(
                        Model.Option.toggle("settingsFullscreen", Values.of(LayerListConfigs.SETTINGS_FULLSCREEN))),
                new Model.Section("layerlist.section.performance")
                        .warning(() -> warningText("layerlist.section.performance"))
                        .add(
                        Model.Option.integer("refreshIntervalTicks",
                                Values.of(LayerListConfigs.REFRESH_INTERVAL), Num.Scale.LINEAR),
                        Model.Option.integer("blocksPerTick", Values.of(LayerListConfigs.BLOCKS_PER_TICK),
                                        Num.Scale.LOG)
                                .foreignTip(LayerListSettings::budgetTip))));

        tabs.add(new Model.Tab("layerlist.gui.tab_search").add(
                new Model.Section("layerlist.section.search_area")
                        .warning(() -> warningText("layerlist.section.search_area"))
                        .add(
                        Model.Option.integer("searchRadius", Values.of(LayerListConfigs.SEARCH_RADIUS),
                                Num.Scale.LINEAR),
                        Model.Option.toggle("searchItemFrames",
                                Values.of(LayerListConfigs.SEARCH_ITEM_FRAMES))),
                new Model.Section("layerlist.section.search_behaviour").add(
                        Model.Option.toggle("searchFollowsPage",
                                        Values.of(LayerListConfigs.SEARCH_FOLLOWS_PAGE))
                                .gate(LayerListSettings::followsPageGate),
                        Model.Option.toggle("skipItemsInInventory",
                                        Values.of(LayerListConfigs.SKIP_ITEMS_IN_INVENTORY))
                                .swatch(LayerHudRenderer::okColor),
                        Model.Option.toggle("showSearchMarkers",
                                        Values.of(LayerListConfigs.SHOW_SEARCH_MARKERS))
                                .swatch(() -> StorageSearch.paletteColor(0)),
                        // Тяжёлая опция: жёлтый «!» и вопрос перед включением
                        Model.Option.toggle("searchAllChests", Values.of(LayerListConfigs.SEARCH_ALL_CHESTS))
                                .warning()
                                .confirmEnable(com.aell.layerlist.storage.AllChestsSearch::askToEnable))));

        tabs.add(new Model.Tab("layerlist.gui.tab_highlight").add(
                new Model.Section("layerlist.section.storage_marks")
                        .warning(() -> warningText("layerlist.section.storage_marks"))
                        .add(
                        Model.Option.integer("highlightGlow", Values.of(LayerListConfigs.HIGHLIGHT_GLOW),
                                        Num.Scale.LINEAR)
                                .swatch(com.aell.layerlist.storage.StorageHighlightRenderer::sampleColor),
                        Model.Option.toggle("colorsFromBlocks", Values.of(LayerListConfigs.COLORS_FROM_BLOCKS))
                                .swatch(() -> StorageSearch.paletteColor(0))
                                .gate(LayerListSettings::rowColorGate),
                        Model.Option.toggle("highlightOwnColor", Values.of(LayerListConfigs.HIGHLIGHT_OWN_COLOR))
                                .swatch(com.aell.layerlist.storage.StorageHighlightRenderer::ownColor),
                        Model.Option.integer("highlightHue", Values.of(LayerListConfigs.HIGHLIGHT_HUE), Num.Scale.LINEAR)
                                .child().gate(LayerListSettings::ownColorGate)
                                .swatch(com.aell.layerlist.storage.StorageHighlightRenderer::ownColor),
                        Model.Option.toggle("highlightLabels", Values.of(LayerListConfigs.HIGHLIGHT_LABELS))),
                new Model.Section("layerlist.section.ghost_inventory").note("layerlist.section.ghost_inventory.note").add(
                        Model.Option.toggle("ghostInventoryOnly", Values.of(LayerListConfigs.GHOST_INVENTORY_ONLY))
                                .swatch(() -> Theme.GREEN)),
                new Model.Section("layerlist.section.ghost_support").note("layerlist.section.ghost_support.note").add(
                        Model.Option.toggle("ghostHideUnsupported", Values.of(LayerListConfigs.GHOST_HIDE_UNSUPPORTED)),
                        Model.Option.toggle("ghostSupportFromLayers",
                                Values.of(LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS))),
                new Model.Section("layerlist.section.inside_container").add(
                        Model.Option.toggle("highlightInContainer",
                                        Values.of(LayerListConfigs.HIGHLIGHT_IN_CONTAINER))
                                .swatch(() -> StorageSearch.paletteColor(1)))));

        tabs.add(new Model.Tab("layerlist.gui.tab_verification").add(
                new Model.Section("layerlist.section.verify_mode").add(
                        Model.Option.toggle("verifyBlocks", Values.of(LayerListConfigs.VERIFY_ENABLED))),
                new Model.Section("layerlist.section.verify_what")
                        .warning(() -> LayerListConfigs.VERIFY_ENABLED.getBooleanValue() && noVerifyCategory()
                                ? text("warn.no_verify_category") : null)
                        .add(
                        Model.Option.toggle("verifyMissing", Values.of(LayerListConfigs.VERIFY_MISSING))
                                .child().gate(LayerListSettings::verifyGate)
                                .swatch(LayerVerifier.Category.MISSING::color),
                        Model.Option.toggle("verifyWrongBlocks", Values.of(LayerListConfigs.VERIFY_WRONG_BLOCK))
                                .child().gate(LayerListSettings::verifyGate)
                                .swatch(LayerVerifier.Category.WRONG_BLOCK::color),
                        Model.Option.toggle("verifyWrongStates", Values.of(LayerListConfigs.VERIFY_WRONG_STATE))
                                .child().gate(LayerListSettings::verifyGate)
                                .swatch(LayerVerifier.Category.WRONG_STATE::color),
                        Model.Option.toggle("verifyExtraBlocks", Values.of(LayerListConfigs.VERIFY_EXTRA))
                                .child().gate(LayerListSettings::verifyGate)
                                .swatch(LayerVerifier.Category.EXTRA::color),
                        Model.Option.toggle("verifyShelves", Values.of(LayerListConfigs.VERIFY_SHELVES))
                                .child().gate(LayerListSettings::verifyGate)
                                .swatch(() -> ShelfRenderer.C_STOCKED)),
                new Model.Section("layerlist.section.verify_look").add(
                        Model.Option.toggle("verifySeeThrough", Values.of(LayerListConfigs.VERIFY_SEE_THROUGH))
                                .child().gate(LayerListSettings::verifyGate),
                        Model.Option.toggle("verifyFillBoxes", Values.of(LayerListConfigs.VERIFY_FILL))
                                .child().gate(LayerListSettings::verifyGate),
                        Model.Option.integer("verifyDrawRange", Values.of(LayerListConfigs.VERIFY_DRAW_RANGE),
                                        Num.Scale.LINEAR)
                                .child().gate(LayerListSettings::verifyGate)
                                .preview(new RangePreview(LayerListConfigs.VERIFY_DRAW_RANGE.getMaxIntegerValue())),
                        Model.Option.integer("verifyMaxMarkers", Values.of(LayerListConfigs.VERIFY_MAX_MARKERS),
                                        Num.Scale.LOG)
                                .child().gate(LayerListSettings::verifyGate),
                        Model.Option.toggle("shelfShowEmpty", Values.of(LayerListConfigs.SHELF_SHOW_EMPTY))
                                .child().gate(LayerListSettings::shelfGate)
                                .swatch(() -> ShelfRenderer.C_EMPTY))));

        tabs.add(new Model.Tab("layerlist.gui.tab_hotkeys").add(
                new Model.Section("layerlist.section.panel_keys").note("layerlist.section.keys.note").add(
                        key("toggleLayerHud", LayerListConfigs.TOGGLE_HUD)
                                .swatch(LayerHudRenderer::okColor),
                        key("scrollUp", LayerListConfigs.SCROLL_UP)
                                .swatch(() -> Theme.BLUE),
                        key("scrollDown", LayerListConfigs.SCROLL_DOWN)
                                .swatch(() -> Theme.BLUE)),
                new Model.Section("layerlist.section.search_keys").add(
                        key("searchStorage", LayerListConfigs.SEARCH_STORAGE)
                                .swatch(() -> StorageSearch.paletteColor(0)),
                        key("clearSearch", LayerListConfigs.CLEAR_SEARCH)
                                .swatch(() -> Theme.RED),
                        key("toggleSearchAllChests", LayerListConfigs.TOGGLE_SEARCH_ALL_CHESTS)
                                .warning(),
                        key("verifyBlocksKey", LayerListConfigs.VERIFY_KEY)
                                .swatch(() -> Theme.YELLOW)
                                .gate(LayerListSettings::verifyGate)),
                // Переключатели опций, которые то нужны, то мешают: по умолчанию клавиш нет
                new Model.Section("layerlist.section.toggle_keys").note("layerlist.section.toggle_keys.note").add(
                        key("toggleStrictSchematic", epHotkey(LayerListConfigs.TOGGLE_STRICT_SCHEMATIC))
                                .swatch(() -> 0xFFE0902E),
                        key("toggleDigGuard", epHotkey(LayerListConfigs.TOGGLE_DIG_GUARD)),
                        key("ghostInventoryKey", LayerListConfigs.GHOST_INVENTORY_KEY)
                                .swatch(() -> Theme.GREEN)),
                // «Схемы» — первой в «Экранах»: по умолчанию клавиши нет, и последней строкой её не замечали
                new Model.Section("layerlist.section.screen_keys").add(
                        key("openSchematics", SchematicsConfig.OPEN_SCHEMATICS)
                                .swatch(() -> Theme.BLUE),
                        key("selectionWorldCursor", SchematicsConfig.SELECTION_CURSOR)
                                .swatch(() -> Theme.BLUE),
                        key("openConfig", LayerListConfigs.OPEN_CONFIG)
                                .swatch(() -> Theme.BLUE),
                        key("openHudPosition", LayerListConfigs.OPEN_HUD_POSITION)
                                .swatch(() -> Theme.BLUE)),
                new Model.Section("layerlist.section.messages").add(
                        Model.Option.toggle("chatMessages", Values.of(LayerListConfigs.CHAT_MESSAGES)))));

        if (! GhostAlphaModule.standalonePresent())
        {
            tabs.add(ghostTab());
        }

        // Вкладка есть всегда, даже когда движок ведёт отдельный мод: настройки общие, и
        // человеку, пришедшему сюда, незачем знать, какой из двух модов сейчас за рулём
        tabs.add(easyPlaceTab());

        // Своего движка зон больше нет: вкладка — пульт к отдельному Zone Focus,
        // и без него её не существует
        Model.Tab zoneFocus = com.aell.layerlist.zonefocus.ZoneFocusTab.build(TAB_ZONE_FOCUS_ICON);

        if (zoneFocus != null)
        {
            tabs.add(zoneFocus);
        }

        for (LayerListApi.AddonTab addon : LayerListApi.getConfigTabs())
        {
            tabs.add(addonTab(addon));
        }

        return tabs;
    }

    private static Model.Tab ghostTab()
    {
        return new Model.Tab("layerlist.gui.tab_ghost").icon(TAB_GHOST_ICON).add(
                new Model.Section("layerlist.section.ghost_alpha")
                        .note("layerlist.section.ghost_alpha.note").add(
                        Model.Option.decimal("ghostTransparency", ghostTransparency(), "%.0f%%"),
                        Model.Option.toggle("ghostFlatNoFill", Values.of(LayerListConfigs.GHOST_FLAT_NO_FILL))),
                new Model.Section("layerlist.section.ghost_translucency").add(
                        Model.Option.toggle("translucentInnerSides",
                                        Values.of(fi.dy.masa.litematica.config.Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES))
                                .child().gate(LayerListSettings::opaqueGate),
                        Model.Option.toggle("ghostLeanInnerSides",
                                        Values.of(LayerListConfigs.GHOST_LEAN_INNER_SIDES))
                                .child().gate(LayerListSettings::leanGate),
                        Model.Option.toggle("sortTranslucent",
                                        Values.of(fi.dy.masa.litematica.config.Configs.Visuals.RENDER_ENABLE_TRANSLUCENT_RESORTING))
                                .child().gate(LayerListSettings::opaqueGate)));
    }

    private static Model.Tab easyPlaceTab()
    {
        return new Model.Tab("layerlist.gui.tab_easy_place").icon(TAB_EASY_PLACE_ICON).add(
                new Model.Section("layerlist.section.easy_place_fixes")
                        .note("layerlist.section.easy_place_fixes"
                                + (EasyPlaceModule.standalonePresent() ? ".note_shared" : ".note"))
                        .add(
                        Model.Option.toggle("easyPlaceFix", Values.of(EP_SHARED.bool(LayerListConfigs.EP_ENABLED))),
                        Model.Option.toggle("easyPlaceSkipWrongState",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_SKIP_WRONG)))
                                .child().gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceOnlySchematic",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_ONLY_SCHEMATIC)))
                                .child().gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceGiveUpRefused",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_GIVE_UP_REFUSED)))
                                .child().gate(LayerListSettings::easyPlaceGate),
                        // Железные защиты — свои, от тумблера исправлений не зависят
                        Model.Option.toggle("easyPlaceStrictSchematic",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_STRICT_SCHEMATIC)))
                                .swatch(() -> 0xFFE0902E),
                        Model.Option.toggle("easyPlaceStrictBlock",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_STRICT_BLOCK)))
                                .swatch(() -> 0xFFE0902E)),
                new Model.Section("layerlist.section.easy_place_tools")
                        .note("layerlist.section.easy_place_tools.note").add(
                        Model.Option.toggle("easyPlaceFixLit", Values.of(EP_SHARED.bool(LayerListConfigs.EP_FIX_LIT)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceFixWaterlogged",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_FIX_WATERLOGGED)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceStripLogs",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_STRIP_LOGS)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceFinishTools",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_FINISH_TOOLS)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceFixFilling",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_FIX_FILLING)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceReplaceSoil",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_REPLACE_SOIL)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceDirtForGrass",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_DIRT_FOR_GRASS)))
                                .gate(LayerListSettings::easyPlaceGate),
                        // Только у Material HUD: в общий с Easy Place Fix+ список не входит
                        Model.Option.toggle("easyPlaceEntities", Values.of(LayerListConfigs.EP_ENTITIES))
                                .gate(LayerListSettings::easyPlaceGate)),
                new Model.Section("layerlist.section.easy_place_digging")
                        .note("layerlist.section.easy_place_digging.note").add(
                        Model.Option.toggle("easyPlaceDigGuard", Values.of(EP_SHARED.bool(LayerListConfigs.EP_DIG_GUARD)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceDigGuardOutside",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_DIG_GUARD_OUTSIDE)))
                                .child().gate(LayerListSettings::digGuardGate),
                        Model.Option.toggle("easyPlaceDigGuardCorrect",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_DIG_GUARD_CORRECT)))
                                .child().gate(LayerListSettings::digGuardGate),
                        Model.Option.toggle("easyPlaceDigGuardEasyPlaceOnly",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_DIG_GUARD_EP_ONLY)))
                                .child().gate(LayerListSettings::digGuardGate),
                        Model.Option.toggle("easyPlaceAutoTool", Values.of(EP_SHARED.bool(LayerListConfigs.EP_AUTO_TOOL)))
                                .gate(LayerListSettings::easyPlaceGate)),
                new Model.Section("layerlist.section.easy_place_server")
                        .note("layerlist.section.easy_place_server.note").add(
                        Model.Option.integer("easyPlaceBlocksPerSecond",
                                        Values.of(EP_SHARED.integer(LayerListConfigs.EP_PLACE_RATE)), Num.Scale.LINEAR)
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceJitter", Values.of(EP_SHARED.bool(LayerListConfigs.EP_JITTER)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceNoTurn", Values.of(EP_SHARED.bool(LayerListConfigs.EP_NO_TURN)))
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceVanillaServer",
                                        Values.of(EP_SHARED.bool(LayerListConfigs.EP_PLAIN_HITS)))
                                .gate(LayerListSettings::easyPlaceGate)),
                new Model.Section("layerlist.section.easy_place_timings").add(
                        Model.Option.integer("easyPlaceConfirmWindow",
                                        Values.of(EP_SHARED.integer(LayerListConfigs.EP_CONFIRM_WINDOW)), Num.Scale.LINEAR)
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.integer("easyPlaceAutomationWindow",
                                        Values.of(EP_SHARED.integer(LayerListConfigs.EP_AUTOMATION_WINDOW)), Num.Scale.LINEAR)
                                .gate(LayerListSettings::easyPlaceGate),
                        Model.Option.toggle("easyPlaceDebug", Values.of(EP_SHARED.bool(LayerListConfigs.EP_DEBUG)))
                                .gate(LayerListSettings::easyPlaceGate)));
    }

    private static Values.Dbl ghostTransparency()
    {
        return new Values.Dbl()
        {
            @Override public double get() { return GhostAlpha.transparency(); }
            @Override public void set(double value) { GhostAlpha.setTransparency((int) Math.round(value)); }
            @Override public double min() { return 0.0D; }
            @Override public double max() { return GhostAlpha.FULL; }
            @Override public double step() { return 1.0D; }
            @Override public double defaultValue() { return 0.0D; }
            @Override public boolean isDefault() { return ! GhostAlpha.isTranslucent(); }

            @Override
            public void reset()
            {
                GhostAlpha.setTransparency(0);
                GhostAlpha.save();
            }
        };
    }

    private static @Nullable Model.Reason opaqueGate()
    {
        return GhostAlpha.isTranslucent() ? null : new Model.Reason(text("gate.opaque"), false);
    }

    private static @Nullable Model.Reason transferGate()
    {
        Model.Reason dock = dockGate();

        if (dock != null)
        {
            return dock;
        }

        return LayerListConfigs.HUD_TRANSFER_BUTTON.getBooleanValue()
                ? null
                : new Model.Reason(text("gate.transfer_off"), false);
    }

    /** Цвета из блоков действуют, только пока подсветка цветом строки (без своего цвета). */
    private static @Nullable Model.Reason rowColorGate()
    {
        return LayerListConfigs.HIGHLIGHT_OWN_COLOR.getBooleanValue()
                ? new Model.Reason(text("gate.own_color"), false)
                : null;
    }

    private static @Nullable Model.Reason ownColorGate()
    {
        return LayerListConfigs.HIGHLIGHT_OWN_COLOR.getBooleanValue()
                ? null
                : new Model.Reason(text("gate.own_color_off"), false);
    }

    private static @Nullable Model.Reason cutoffGate()
    {
        return LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue()
                ? null
                : new Model.Reason(text("gate.cutoff_off"), false);
    }

    private static @Nullable Model.Reason dockGate()
    {
        return LayerListConfigs.HUD_BESIDE_CONTAINER.getBooleanValue()
                ? null
                : new Model.Reason(text("gate.dock_off"), false);
    }

    private static @Nullable Model.Reason leanGate()
    {
        Model.Reason opaque = opaqueGate();

        if (opaque != null)
        {
            return opaque;
        }

        return fi.dy.masa.litematica.config.Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES
                .getBooleanValue()
                ? null
                : new Model.Reason(text("gate.inner_sides_off"), false);
    }

    private static @Nullable Model.Reason easyPlaceGate()
    {
        return EP_SHARED.bool(LayerListConfigs.EP_ENABLED).getBooleanValue() ? null
                : new Model.Reason(text("gate.easy_place_off"), false);
    }

    private static @Nullable Model.Reason digGuardGate()
    {
        Model.Reason off = easyPlaceGate();

        return off != null || EP_SHARED.bool(LayerListConfigs.EP_DIG_GUARD).getBooleanValue() ? off
                : new Model.Reason(text("gate.dig_guard_off"), false);
    }

    private static Model.Tab addonTab(LayerListApi.AddonTab addon)
    {
        Model.Tab tab = new Model.Tab(addon.labelKey()).icon(addonIcon(addon));

        for (LayerListApi.AddonSection section : addon.sections())
        {
            Model.Section mapped = new Model.Section(section.titleKey());

            for (LayerListApi.AddonOption option : section.options())
            {
                mapped.add(addonOption(option));
            }

            tab.add(mapped);
        }

        return tab;
    }

    private static net.minecraft.resources.@Nullable Identifier addonIcon(LayerListApi.AddonTab addon)
    {
        if (addon.iconNamespace() == null || addon.iconPath() == null)
        {
            return null;
        }

        try
        {
            return net.minecraft.resources.Identifier.fromNamespaceAndPath(addon.iconNamespace(),
                    addon.iconPath());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Model.Option addonOption(LayerListApi.AddonOption option)
    {
        Model.Option mapped = switch (option)
        {
            case LayerListApi.AddonToggle toggle -> Model.Option.toggle(toggle.configKey(),
                    new Values.Bool()
                    {
                        @Override public boolean get() { return toggle.getter().getAsBoolean(); }
                        @Override public void set(boolean value) { toggle.setter().accept(value); }
                        @Override public boolean defaultValue() { return toggle.defaultValue(); }
                        @Override public boolean isDefault() { return get() == toggle.defaultValue(); }
                        @Override public void reset() { set(toggle.defaultValue()); }
                    });
            case LayerListApi.AddonSlider slider -> Model.Option.decimal(slider.configKey(),
                    new Values.Dbl()
                    {
                        @Override public double get() { return slider.getter().getAsDouble(); }
                        @Override public void set(double value) { slider.setter().accept(value); }
                        @Override public double min() { return slider.min(); }
                        @Override public double max() { return slider.max(); }
                        @Override public double step() { return slider.step(); }
                        @Override public double defaultValue() { return slider.defaultValue(); }

                        @Override
                        public boolean isDefault()
                        {
                            return Math.abs(get() - slider.defaultValue()) < slider.step() / 2.0D;
                        }

                        @Override public void reset() { set(slider.defaultValue()); }
                    }, slider.format());
        };

        if (option.child())
        {
            mapped.child();
        }

        Supplier<String> gate = option.gate();

        if (gate != null)
        {
            mapped.gate(() ->
            {
                String reason = gate.get();

                return reason == null ? null : new Model.Reason(reason, true);
            });
        }

        return mapped;
    }

    private static Model.Option key(String name, fi.dy.masa.malilib.config.options.ConfigHotkey config)
    {
        return Model.Option.keybind(name, Values.of(config), Values.keybind(config));
    }

    private static @Nullable Model.Reason shelfGate()
    {
        Model.Reason verify = verifyGate();

        if (verify != null)
        {
            return verify;
        }

        return LayerListConfigs.VERIFY_SHELVES.getBooleanValue() ? null
                : new Model.Reason(text("gate.shelves_off"), false);
    }

    private static @Nullable Model.Reason verifyGate()
    {
        return LayerListConfigs.VERIFY_ENABLED.getBooleanValue() ? null
                : new Model.Reason(text("gate.verify_off"), false);
    }

    private static boolean noVerifyCategory()
    {
        return ! LayerListConfigs.VERIFY_MISSING.getBooleanValue()
                && ! LayerListConfigs.VERIFY_WRONG_BLOCK.getBooleanValue()
                && ! LayerListConfigs.VERIFY_WRONG_STATE.getBooleanValue()
                && ! LayerListConfigs.VERIFY_EXTRA.getBooleanValue()
                && ! LayerListConfigs.VERIFY_SHELVES.getBooleanValue();
    }

    private static @Nullable Model.Reason followsPageGate()
    {
        return foreignScopeActive() && scopeWantsWholeSearch()
                ? new Model.Reason(text("gate.whole_scope_search"), true)
                : null;
    }

    private static boolean foreignScopeActive()
    {
        return LayerListApi.isScopeActive();
    }

    private static boolean scopeWantsWholeSearch()
    {
        CountScope scope = LayerListApi.getCountScope();

        return scope != null && scope.searchWholeScope();
    }

    private static List<String> budgetTip()
    {
        for (String modId : BUDGET_NEIGHBOURS)
        {
            if (FabricLoader.getInstance().isModLoaded(modId))
            {
                return List.of(Strings.t("tip.budgets_add_up"));
            }
        }

        return List.of();
    }

    private record Warning(String text, String tabKey, String optionKey, String sectionKey) {}

    private static @Nullable Warning firstWarning()
    {
        if (LayerListConfigs.ITEMS_PER_PAGE.getIntegerValue() == LayerListConfigs.ITEMS_PER_PAGE
                .getMinIntegerValue() && LayerListConfigs.SEARCH_FOLLOWS_PAGE.getBooleanValue())
        {
            return new Warning(text("warn.one_per_page"), "layerlist.gui.tab_hud", "itemsPerPage",
                    "layerlist.section.panel_content");
        }

        if (LayerListConfigs.REFRESH_INTERVAL.getIntegerValue() > SLOW_REFRESH_TICKS)
        {
            return new Warning(text("warn.slow_refresh"), "layerlist.gui.tab_hud", "refreshIntervalTicks",
                    "layerlist.section.performance");
        }

        if (LayerListConfigs.SEARCH_RADIUS.getIntegerValue() == LayerListConfigs.SEARCH_RADIUS
                .getMinIntegerValue())
        {
            return new Warning(text("warn.small_radius"), "layerlist.gui.tab_search", "searchRadius",
                    "layerlist.section.search_area");
        }

        if (! LayerListConfigs.SHOW_SEARCH_MARKERS.getBooleanValue()
                && ! LayerListConfigs.HIGHLIGHT_LABELS.getBooleanValue())
        {
            return new Warning(text("warn.unreadable_marks"), "layerlist.gui.tab_highlight",
                    "highlightLabels", "layerlist.section.storage_marks");
        }

        return null;
    }

    private static @Nullable String warningText(String sectionKey)
    {
        Warning warning = firstWarning();

        return warning != null && warning.sectionKey.equals(sectionKey) ? warning.text : null;
    }

    private static boolean hasEnabledPlacement()
    {
        try
        {
            for (SchematicPlacement placement :
                    DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                if (placement.isEnabled())
                {
                    return true;
                }
            }

            return false;
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
        }
    }

    private static String text(String suffix)
    {
        return Strings.plain(MOD_ID + ".ui." + suffix);
    }

    private record RangePreview(int maxRange) implements Model.Preview
    {
        private static final int WIDTH = 24;
        private static final int HEIGHT = 4;

        @Override
        public int width()
        {
            return WIDTH;
        }

        @Override
        public void draw(GuiContext ctx, int x, int rowTop, Model.Option option)
        {
            if (option.number == null)
            {
                return;
            }

            int y = rowTop + (Ui.ROW_H - HEIGHT) / 2;
            int fill = Math.round(WIDTH * option.number.get() / (float) this.maxRange);

            Paint.rect(ctx, x, y, WIDTH, HEIGHT, Ui.C_TRACK);
            Paint.rect(ctx, x, y, Math.max(1, fill), HEIGHT, Ui.alpha(Ui.C_ACCENT, Ui.A_PREVIEW));
        }
    }

    private static final class DotPreview implements Model.Preview
    {
        private static final int MAX = 12;

        @Override
        public int width()
        {
            return MAX;
        }

        @Override
        public void draw(GuiContext ctx, int x, int rowTop, Model.Option option)
        {
            if (option.number == null)
            {
                return;
            }

            int size = Math.max(2, Math.min(MAX,
                    MAX * option.number.get() / option.number.max()));
            int color = StorageSearch.paletteColor(0);
            int cx = x + MAX / 2;
            int cy = rowTop + Ui.ROW_H / 2;
            int half = size / 2;

            for (int row = -half; row <= half; ++row)
            {
                int span = half - Math.abs(row);
                Paint.rect(ctx, cx - span, cy + row, span * 2 + 1, 1, color);
            }
        }
    }

    private static final class ScalePreview implements Model.Preview
    {
        private static final int WIDTH = 16;
        private static final int HEIGHT = 12;

        @Override
        public int width()
        {
            return WIDTH;
        }

        @Override
        public void draw(GuiContext ctx, int x, int rowTop, Model.Option option)
        {
            if (option.decimal == null)
            {
                return;
            }

            double scale = option.decimal.get() / option.decimal.max();
            int w = Math.max(2, (int) Math.round(WIDTH * scale));
            int h = Math.max(2, (int) Math.round(HEIGHT * scale));
            int y = rowTop + (Ui.ROW_H - h) / 2;

            Paint.rect(ctx, x, y, w, h, Ui.C_TRACK);
            Paint.border(ctx, x, y, w, h, Ui.C_TEXT_3);
        }
    }
}
