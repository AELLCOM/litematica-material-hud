package com.aell.layerlist.config;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import java.io.File;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public class LayerListConfigs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = "layerlist.json";

    /** 2 — «Показывать нехватку» к умолчанию; 3 (1.0.9) — «Сообщения в чат» выключены у всех один раз. */
    private static final int SETTINGS_REVISION = 5;

    public static final ConfigBoolean REPLACE_LITEMATICA_HUD = new ConfigBoolean("replaceLitematicaHud", true);
    public static final ConfigBoolean ONLY_MISSING           = new ConfigBoolean("onlyMissing", true);
    public static final ConfigBoolean PIN_ACROSS_DIMENSIONS  = new ConfigBoolean("pinAcrossDimensions", true);
    /** С 1.0.20: «С учётом склада» — HUD и таблица «Материалы» прячут то, что уже есть в инвентаре и известных сундуках. */
    public static final ConfigBoolean USE_STOCK              = new ConfigBoolean("useStock", false);
    public static final ConfigBoolean SHORTFALL_ONLY_MISSING = new ConfigBoolean("shortfallOnlyMissing", true);
    public static final ConfigBoolean SHOW_SHORTFALL         = new ConfigBoolean("showShortfall", false);
    public static final ConfigBoolean SHOW_PROGRESS_BAR      = new ConfigBoolean("showProgressBar", true);
    /** Большие количества в шалкерах («1,2 SB»); по умолчанию — стопками, как в остальных строках («32x64+17»). */
    public static final ConfigBoolean HUD_COUNT_IN_BOXES     = new ConfigBoolean("hudCountInBoxes", false);
    public static final ConfigBoolean SORT_BY_NAME           = new ConfigBoolean("sortByName", false);
    public static final ConfigBoolean SHOW_SEARCH_MARKERS    = new ConfigBoolean("showSearchMarkers", true);
    public static final ConfigBoolean COLORS_FROM_BLOCKS     = new ConfigBoolean("colorsFromBlocks", false);
    public static final ConfigBoolean SEARCH_ITEM_FRAMES     = new ConfigBoolean("searchItemFrames", true);
    public static final ConfigBoolean SEARCH_FOLLOWS_PAGE     = new ConfigBoolean("searchFollowsPage", true);
    public static final ConfigBoolean SKIP_ITEMS_IN_INVENTORY = new ConfigBoolean("skipItemsInInventory", true);
    public static final ConfigBoolean HIGHLIGHT_BOXES        = new ConfigBoolean("highlightBoxes", false);
    public static final ConfigBoolean HIGHLIGHT_IN_CONTAINER = new ConfigBoolean("highlightInContainer", true);
    public static final ConfigBoolean HIGHLIGHT_LABELS       = new ConfigBoolean("highlightLabels", true);
    public static final ConfigBoolean HUD_CUSTOM_POSITION    = new ConfigBoolean("hudCustomPosition", false);
    public static final ConfigBoolean HUD_BESIDE_CONTAINER   = new ConfigBoolean("hudBesideContainer", true);
    /** С 1.0.1: панель у контейнера — справа от окна (выкл — слева, как раньше). */
    public static final ConfigBoolean HUD_DOCK_RIGHT         = new ConfigBoolean("hudDockRight", true);
    /** С 1.0.4: кнопка «Перенести всё необходимое» над панелью у контейнера. */
    public static final ConfigBoolean HUD_TRANSFER_BUTTON    = new ConfigBoolean("hudTransferButton", true);
    /** С 1.0.5: перенос сам при открытии хранилища (кнопка «Auto» рядом с переносом). */
    public static final ConfigBoolean HUD_TRANSFER_AUTO      = new ConfigBoolean("hudTransferAuto", false);
    /** С 1.0.11: перенос (кнопкой и «Auto») добирает шалкерами с нужным внутри — целиком. */
    public static final ConfigBoolean HUD_TRANSFER_SHULKERS  = new ConfigBoolean("hudTransferShulkers", false);
    /** Перенос за один тик всеми щелчками сразу; выключено — 4 щелчка за тик, как у быстрого игрока (если сервер кикает). */
    public static final ConfigBoolean HUD_TRANSFER_INSTANT   = new ConfigBoolean("hudTransferInstant", true);
    /** Автоперенос забрал своё и нужного в хранилище больше нет — выйти из него (закрыть окно). */
    public static final ConfigBoolean HUD_TRANSFER_AUTO_CLOSE = new ConfigBoolean("hudTransferAutoClose", true);
    public static final ConfigBoolean SETTINGS_FULLSCREEN    = new ConfigBoolean("settingsFullscreen", true);
    public static final ConfigBoolean VERIFY_ENABLED         = new ConfigBoolean("verifyBlocks", true);
    public static final ConfigBoolean VERIFY_MISSING         = new ConfigBoolean("verifyMissing", true);
    public static final ConfigBoolean VERIFY_WRONG_BLOCK     = new ConfigBoolean("verifyWrongBlocks", true);
    public static final ConfigBoolean VERIFY_WRONG_STATE     = new ConfigBoolean("verifyWrongStates", true);
    public static final ConfigBoolean VERIFY_EXTRA           = new ConfigBoolean("verifyExtraBlocks", true);
    public static final ConfigBoolean VERIFY_SEE_THROUGH     = new ConfigBoolean("verifySeeThrough", true);
    public static final ConfigBoolean VERIFY_FILL            = new ConfigBoolean("verifyFillBoxes", false);
    public static final ConfigBoolean CHAT_MESSAGES          = new ConfigBoolean("chatMessages", false);
    public static final ConfigBoolean VERIFY_SHELVES         = new ConfigBoolean("verifyShelves", false);
    public static final ConfigBoolean SHELF_SHOW_EMPTY       = new ConfigBoolean("shelfShowEmpty", false);

    public static final ConfigBoolean GHOST_LEAN_INNER_SIDES = new ConfigBoolean("ghostLeanInnerSides", true);
    public static final ConfigBoolean GHOST_INVENTORY_ONLY   = new ConfigBoolean("ghostInventoryOnly", false);
    /** С 1.0.4: не рисовать голограммы блоков, которым в мире не на чем держаться (табличка без потолка, лиана в воздухе). */
    public static final ConfigBoolean GHOST_HIDE_UNSUPPORTED = new ConfigBoolean("ghostHideUnsupported", false);
    /** С 1.0.4: показывать и давать ставить опору с соседнего слоя для блока видимого слоя. */
    public static final ConfigBoolean GHOST_SUPPORT_FROM_LAYERS = new ConfigBoolean("ghostSupportFromLayers", false);
    /** С 1.0.22: у редстоуна, рельсов и нити — только контур оверлея, без заливки (иначе узор не виден). */
    public static final ConfigBoolean GHOST_FLAT_NO_FILL     = new ConfigBoolean("ghostFlatNoFill", true);
    public static final ConfigBoolean COUNT_ENTITIES         = new ConfigBoolean("countEntities", true);

    public static final ConfigBoolean EP_ENABLED             = new ConfigBoolean("easyPlaceFix", true);
    public static final ConfigBoolean EP_FIX_LIT              = new ConfigBoolean("easyPlaceFixLit", true);
    public static final ConfigBoolean EP_FIX_WATERLOGGED      = new ConfigBoolean("easyPlaceFixWaterlogged", true);
    public static final ConfigBoolean EP_FIX_FILLING          = new ConfigBoolean("easyPlaceFixFilling", false);
    public static final ConfigBoolean EP_SKIP_WRONG           = new ConfigBoolean("easyPlaceSkipWrongState", true);
    public static final ConfigBoolean EP_ONLY_SCHEMATIC       = new ConfigBoolean("easyPlaceOnlySchematic", true);
    /** С 1.0.6: после шести отказов сервера по клетке перестать в неё кликать и сказать об этом в чат. */
    public static final ConfigBoolean EP_GIVE_UP_REFUSED      = new ConfigBoolean("easyPlaceGiveUpRefused", true);
    /** С 1.0.5: железная защита — никакой блок мимо схемы, ручной тоже (по умолчанию выкл). */
    public static final ConfigBoolean EP_STRICT_SCHEMATIC     = new ConfigBoolean("easyPlaceStrictSchematic", false);
    public static final ConfigBoolean EP_STRICT_BLOCK         = new ConfigBoolean("easyPlaceStrictBlock", false);
    public static final ConfigBoolean EP_OPEN_CONTAINERS      = new ConfigBoolean("easyPlaceOpenContainers", true);
    public static final ConfigBoolean EP_STRIP_LOGS           = new ConfigBoolean("easyPlaceStripLogs", true);
    public static final ConfigBoolean EP_FINISH_TOOLS         = new ConfigBoolean("easyPlaceFinishTools", true);
    public static final ConfigBoolean EP_PLAIN_HITS           = new ConfigBoolean("easyPlaceVanillaServer", false);
    public static final ConfigBoolean EP_NO_TURN              = new ConfigBoolean("easyPlaceNoTurn", false);
    public static final ConfigBoolean EP_POSE_AHEAD           = new ConfigBoolean("easyPlacePoseAhead", false);
    public static final ConfigBoolean EP_DEBUG                = new ConfigBoolean("easyPlaceDebug", false);
    public static final ConfigBoolean EP_JITTER              = new ConfigBoolean("easyPlaceJitter", false);
    public static final ConfigBoolean EP_DIG_GUARD           = new ConfigBoolean("easyPlaceDigGuard", false);
    /** Части «Не копать лишнего» (13.10.2026): вне схемы, правильно стоящие, только в режиме Easy Place. */
    public static final ConfigBoolean EP_DIG_GUARD_OUTSIDE   = new ConfigBoolean("easyPlaceDigGuardOutside", true);
    public static final ConfigBoolean EP_DIG_GUARD_CORRECT   = new ConfigBoolean("easyPlaceDigGuardCorrect", true);
    public static final ConfigBoolean EP_DIG_GUARD_EP_ONLY   = new ConfigBoolean("easyPlaceDigGuardEasyPlaceOnly", true);
    public static final ConfigBoolean EP_AUTO_TOOL           = new ConfigBoolean("easyPlaceAutoTool", false);
    public static final ConfigBoolean EP_REPLACE_SOIL        = new ConfigBoolean("easyPlaceReplaceSoil", false);
    public static final ConfigBoolean EP_DIRT_FOR_GRASS      = new ConfigBoolean("easyPlaceDirtForGrass", false);
    public static final ConfigBoolean EP_ENTITIES            = new ConfigBoolean("easyPlaceEntities", true);
    public static final ConfigInteger EP_PLACE_RATE          = new ConfigInteger("easyPlaceBlocksPerSecond", 10, 1, 20);
    public static final ConfigInteger EP_CONFIRM_WINDOW      = new ConfigInteger("easyPlaceConfirmWindow", 300, 0, 2000);
    public static final ConfigInteger EP_AUTOMATION_WINDOW   = new ConfigInteger("easyPlaceAutomationWindow", 1000, 0, 5000);
    public static final ConfigInteger HIGHLIGHT_DOT_SIZE     = new ConfigInteger("highlightDotSize", 12, 3, 30);
    /** Подсветка контейнеров (13.10.2026): яркость заливки и ореола, %; свой цвет вместо цвета строки — оттенок 0…359. */
    public static final ConfigInteger HIGHLIGHT_GLOW         = new ConfigInteger("highlightGlow", 60, 10, 100);
    public static final ConfigBoolean HIGHLIGHT_OWN_COLOR    = new ConfigBoolean("highlightOwnColor", false);
    public static final ConfigInteger HIGHLIGHT_HUE          = new ConfigInteger("highlightHue", 190, 0, 359);
    public static final ConfigInteger SEARCH_RADIUS          = new ConfigInteger("searchRadius", 96, 16, 256);
    /** «Подсветить все сундуки» (17.10.2026): все материалы схемы, не слой и не страница; по умолчанию выкл. */
    public static final ConfigBoolean SEARCH_ALL_CHESTS      = new ConfigBoolean("searchAllChests", false);
    public static final ConfigInteger ITEMS_PER_PAGE         = new ConfigInteger("itemsPerPage", 8, 1, 30);
    public static final ConfigInteger REFRESH_INTERVAL       = new ConfigInteger("refreshIntervalTicks", 20, 1, 200);
    public static final ConfigInteger BLOCKS_PER_TICK        = new ConfigInteger("blocksPerTick", 250000, 10000, 4000000);
    /** Оформление (22.10.2026): 0 — Aell UI (по умолчанию), 1 — Vanilla (настройки плитками), 2 — Vanilla+ (строками, как Litematica). */
    public static final ConfigInteger UI_STYLE = new ConfigInteger("uiStyle", 0, 0, 2);
    /** Список считает всё на высоте Y и ниже, рендер слоёв не меняется (22.10.2026). Выключено — список идёт за слоями. */
    public static final ConfigBoolean MATERIAL_CUTOFF        = new ConfigBoolean("materialCutoff", false);
    public static final ConfigInteger MATERIAL_CUTOFF_Y      = new ConfigInteger("materialCutoffY", 64, -60, 321);
    public static final ConfigInteger VERIFY_DRAW_RANGE      = new ConfigInteger("verifyDrawRange", 64, 8, 256);
    public static final ConfigInteger VERIFY_MAX_MARKERS     = new ConfigInteger("verifyMaxMarkers", 2000, 50, 20000);

    public static final double HUD_SCALE_STEP = 0.25;
    public static final ConfigDoubleStepped HUD_SCALE        = new ConfigDoubleStepped("hudScale", 1.0, HUD_SCALE_STEP, 3.0, HUD_SCALE_STEP);

    private static final KeybindSettings SCROLL_SETTINGS =
            KeybindSettings.create(KeybindSettings.Context.ANY, KeyAction.PRESS, false, true, false, true);

    /** Same as the scroll keys: the search toggles must stay reachable with a container screen open. */
    private static final KeybindSettings SEARCH_SETTINGS =
            KeybindSettings.create(KeybindSettings.Context.ANY, KeyAction.PRESS, false, true, false, true);

    public static final ConfigHotkey TOGGLE_HUD  = new ConfigHotkey("toggleLayerHud", "Y");
    public static final ConfigHotkey SCROLL_UP   = new ConfigHotkey("scrollUp", "PAGE_UP", SCROLL_SETTINGS);
    public static final ConfigHotkey SCROLL_DOWN = new ConfigHotkey("scrollDown", "PAGE_DOWN", SCROLL_SETTINGS);
    public static final ConfigHotkey OPEN_CONFIG = new ConfigHotkey("openConfig", "LEFT_CONTROL,Y");

    public static final ConfigHotkey OPEN_HUD_POSITION = new ConfigHotkey("openHudPosition", "LEFT_ALT,Y");
    public static final ConfigHotkey SEARCH_STORAGE = new ConfigHotkey("searchStorage", "U", SEARCH_SETTINGS);
    public static final ConfigHotkey CLEAR_SEARCH = new ConfigHotkey("clearSearch", "LEFT_CONTROL,U", SEARCH_SETTINGS);
    public static final ConfigHotkey TOGGLE_SEARCH_ALL_CHESTS = new ConfigHotkey("toggleSearchAllChests", "", SEARCH_SETTINGS);
    public static final ConfigHotkey VERIFY_KEY = new ConfigHotkey("verifyBlocksKey", "LEFT_CONTROL,B");
    public static final ConfigHotkey GHOST_INVENTORY_KEY = new ConfigHotkey("ghostInventoryKey", "");
    /** Переключатели защит Easy Place; с Easy Place Fix+ 1.0.18+ — его же клавиши (LayerListSettings.epHotkey). */
    public static final ConfigHotkey TOGGLE_STRICT_SCHEMATIC = new ConfigHotkey("toggleStrictSchematic", "");
    public static final ConfigHotkey TOGGLE_DIG_GUARD = new ConfigHotkey("toggleDigGuard", "");

    /**
     * Опции вкладки «Схемы» живут в {@link SchematicsConfig}, но пишутся в этот же файл и этим же
     * списком — malilib сохраняет их вместе с остальными, «Сбросить всё» сбрасывает и их.
     */
    public static final List<IConfigBase> OPTIONS = ImmutableList.<IConfigBase>builder().add(
            REPLACE_LITEMATICA_HUD, ONLY_MISSING, USE_STOCK, PIN_ACROSS_DIMENSIONS, COUNT_ENTITIES, SHORTFALL_ONLY_MISSING, SHOW_SHORTFALL,
            SHOW_PROGRESS_BAR, HUD_COUNT_IN_BOXES, SORT_BY_NAME,
            SHOW_SEARCH_MARKERS, SEARCH_ITEM_FRAMES, SEARCH_FOLLOWS_PAGE,
            SKIP_ITEMS_IN_INVENTORY, SEARCH_RADIUS, SEARCH_ALL_CHESTS,
            HIGHLIGHT_BOXES, HIGHLIGHT_LABELS, HIGHLIGHT_DOT_SIZE, HIGHLIGHT_GLOW, HIGHLIGHT_OWN_COLOR, HIGHLIGHT_HUE,
            COLORS_FROM_BLOCKS, HIGHLIGHT_IN_CONTAINER,
            ITEMS_PER_PAGE, REFRESH_INTERVAL, BLOCKS_PER_TICK, MATERIAL_CUTOFF, MATERIAL_CUTOFF_Y, UI_STYLE,
            HUD_SCALE, HUD_CUSTOM_POSITION, HUD_BESIDE_CONTAINER, HUD_DOCK_RIGHT, HUD_TRANSFER_BUTTON, HUD_TRANSFER_AUTO, HUD_TRANSFER_INSTANT, HUD_TRANSFER_AUTO_CLOSE, HUD_TRANSFER_SHULKERS, SETTINGS_FULLSCREEN, CHAT_MESSAGES,
            VERIFY_ENABLED, VERIFY_MISSING, VERIFY_WRONG_BLOCK, VERIFY_WRONG_STATE, VERIFY_EXTRA,
            VERIFY_SEE_THROUGH, VERIFY_FILL, VERIFY_DRAW_RANGE, VERIFY_MAX_MARKERS,
            EP_ENABLED, EP_FIX_LIT, EP_FIX_WATERLOGGED, EP_FIX_FILLING,
            EP_SKIP_WRONG, EP_ONLY_SCHEMATIC, EP_GIVE_UP_REFUSED, EP_STRICT_SCHEMATIC, EP_STRICT_BLOCK, EP_OPEN_CONTAINERS,
            EP_STRIP_LOGS, EP_FINISH_TOOLS,
            EP_PLAIN_HITS, EP_NO_TURN, EP_POSE_AHEAD,
            EP_JITTER, EP_DIG_GUARD, EP_DIG_GUARD_OUTSIDE, EP_DIG_GUARD_CORRECT, EP_DIG_GUARD_EP_ONLY, EP_AUTO_TOOL, EP_REPLACE_SOIL, EP_DIRT_FOR_GRASS, EP_ENTITIES,
            VERIFY_SHELVES, SHELF_SHOW_EMPTY, GHOST_LEAN_INNER_SIDES, GHOST_INVENTORY_ONLY,
            GHOST_HIDE_UNSUPPORTED, GHOST_SUPPORT_FROM_LAYERS, GHOST_FLAT_NO_FILL,
            EP_DEBUG, EP_PLACE_RATE, EP_CONFIRM_WINDOW, EP_AUTOMATION_WINDOW)
            .addAll(SchematicsConfig.OPTIONS)
            .build();

    /**
     * Общие с отдельным модом EasyPlaceFixPlus опции: имена совпадают дословно, и через
     * {@code compat.EasyPlaceBridge} этот список — единственное, что сосед видит и правит.
     * «Сообщения в чат» здесь не значатся намеренно: у нас они ещё и про поиск по хранилищам.
     */
    public static final List<IConfigBase> EP_OPTIONS = ImmutableList.of(
            EP_ENABLED, EP_SKIP_WRONG, EP_ONLY_SCHEMATIC, EP_GIVE_UP_REFUSED, EP_STRICT_SCHEMATIC, EP_STRICT_BLOCK,
            EP_FIX_LIT, EP_FIX_WATERLOGGED, EP_FIX_FILLING,
            EP_STRIP_LOGS, EP_FINISH_TOOLS, EP_OPEN_CONTAINERS,
            EP_PLAIN_HITS, EP_NO_TURN, EP_POSE_AHEAD,
            EP_JITTER, EP_DIG_GUARD, EP_DIG_GUARD_OUTSIDE, EP_DIG_GUARD_CORRECT, EP_DIG_GUARD_EP_ONLY, EP_AUTO_TOOL, EP_REPLACE_SOIL, EP_DIRT_FOR_GRASS, EP_PLACE_RATE,
            EP_CONFIRM_WINDOW, EP_AUTOMATION_WINDOW, EP_DEBUG);

    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.<ConfigHotkey>builder().add(
            TOGGLE_HUD, SCROLL_UP, SCROLL_DOWN, OPEN_CONFIG, OPEN_HUD_POSITION,
            SEARCH_STORAGE, CLEAR_SEARCH, TOGGLE_SEARCH_ALL_CHESTS, VERIFY_KEY, GHOST_INVENTORY_KEY,
            TOGGLE_STRICT_SCHEMATIC, TOGGLE_DIG_GUARD)
            .addAll(SchematicsConfig.HOTKEYS)
            .build();

    public static boolean HUD_ENABLED = LayerListDefaults.HUD_ENABLED;

    public static int HUD_POS_X = LayerListDefaults.HUD_POS_X;
    public static int HUD_POS_Y = LayerListDefaults.HUD_POS_Y;

    @Override
    public void load()
    {
        File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), CONFIG_FILE_NAME);

        if (configFile.exists() && configFile.isFile() && configFile.canRead())
        {
            JsonElement element = JsonUtils.parseJsonFile(configFile.toPath());

            if (element != null && element.isJsonObject())
            {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Options", OPTIONS);
                migrateUiStyle(root);
                ConfigUtils.readConfigBase(root, "Hotkeys", HOTKEYS);
                HUD_ENABLED = JsonUtils.getBooleanOrDefault(root, "hud_enabled", LayerListDefaults.HUD_ENABLED);
                HUD_POS_X = JsonUtils.getIntegerOrDefault(root, "hud_pos_x", LayerListDefaults.HUD_POS_X);
                HUD_POS_Y = JsonUtils.getIntegerOrDefault(root, "hud_pos_y", LayerListDefaults.HUD_POS_Y);

                int revision = JsonUtils.getIntegerOrDefault(root, "settings_revision", 1);

                if (revision < 2)
                {
                    SHOW_SHORTFALL.resetToDefault();
                }

                // До 1.0.9 чат был включён по умолчанию и записан в файл у всех: новое умолчание — один раз
                if (revision < 3)
                {
                    CHAT_MESSAGES.resetToDefault();
                }

                // Первая сборка с опцией записала «шалкеры» (тогдашнее умолчание) в файл: новое умолчание — стопки, один раз
                if (revision < 4)
                {
                    HUD_COUNT_IN_BOXES.resetToDefault();
                }

                // Сборка 15.10 ночью записала «выход после набора» выключенным: новое умолчание — вкл., один раз
                if (revision < 5)
                {
                    HUD_TRANSFER_AUTO_CLOSE.resetToDefault();
                }

                if (revision < SETTINGS_REVISION)
                {
                    this.save();
                }
            }
        }
    }

    @Override
    public void save()
    {
        File dir = FabricLoader.getInstance().getConfigDir().toFile();

        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs())
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Options", OPTIONS);
            ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
            root.addProperty("settings_revision", SETTINGS_REVISION);
            root.addProperty("hud_enabled", HUD_ENABLED);
            root.addProperty("hud_pos_x", HUD_POS_X);
            root.addProperty("hud_pos_y", HUD_POS_Y);
            JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME).toPath());
        }
    }

    /** До 22.10.2026 стиль был галочкой {@code uiVanilla}: включённая становится «Vanilla». */
    private static void migrateUiStyle(JsonObject root)
    {
        JsonObject options = root.has("Options") && root.get("Options").isJsonObject() ? root.getAsJsonObject("Options") : null;

        if (options != null && ! options.has("uiStyle") && options.has("uiVanilla")
                && options.get("uiVanilla").isJsonPrimitive() && options.get("uiVanilla").getAsBoolean())
        {
            UI_STYLE.setIntegerValue(1);
        }
    }
}
