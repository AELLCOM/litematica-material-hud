package com.aell.layerlist.zonefocus;

import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Num;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.SharedOptions;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Values;
import com.aell.layerlist.ui.Theme;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Вкладка «Zone Focus» экрана настроек Material HUD — пульт к отдельному моду
 * Litematica Zone Focus. Собственного движка зон у Material HUD больше нет (вырезан в
 * 0.0.18, см. {@code docs/zonefocus/EXCISION.md}): вкладка появляется только при
 * установленном отдельном моде и правит его живые объекты через рефлексивный мост
 * {@code com.aell.zonefocus.compat.ZoneFocusBridge} ({@link SharedOptions}) — значения
 * у обоих модов одни, значит настраиваться можно из любого экрана, как и раньше.
 *
 * <p>Обратный контракт (сосед → Material HUD) — {@code api.LayerListApi}: v1 держит
 * {@code setCountScope}, {@code createSettings(..., TAB_ZONE_FOCUS)}, {@code markDirty},
 * счётчики и поиск по сундукам. Его менять нельзя: на нём стоят уже выпущенные сборки
 * Zone Focus.
 *
 * <p>Опции ниже — «спецификации» строк: имя, тип и границы соседских опций (скопированы
 * из отдельного мода). Пока сосед стоит, {@link SharedOptions#bool}/{@link SharedOptions#integer}
 * отдают его живой объект, а спецификация даёт только имя; без соседа вкладки нет вовсе,
 * так что сами по себе эти объекты никогда ничего не хранят — в {@code layerlist.json}
 * они не пишутся.
 */
@Environment(EnvType.CLIENT)
public final class ZoneFocusTab
{
    public static final String STANDALONE_MOD_ID = "zonefocus";

    private static final boolean STANDALONE_PRESENT =
            FabricLoader.getInstance().isModLoaded(STANDALONE_MOD_ID);

    /** Мост отдельного мода: те же опции зон под теми же именами. */
    public static final SharedOptions SHARED =
            SharedOptions.of("com.aell.zonefocus.compat.ZoneFocusBridge");

    // Спецификации соседских опций: имена, типы и границы — как в Litematica Zone Focus
    private static final ConfigBoolean ZONE_FOCUS           = new ConfigBoolean("zoneFocus", false);
    private static final ConfigBoolean COUNT_BY_ZONE        = new ConfigBoolean("countByZone", true);
    private static final ConfigBoolean SEARCH_WHOLE_ZONE    = new ConfigBoolean("searchWholeZone", false);
    private static final ConfigBoolean IGNORE_LAYERS_IN_ZONE = new ConfigBoolean("ignoreLayersInZone", false);
    private static final ConfigBoolean CLIP_SCHEMATIC_RENDER = new ConfigBoolean("clipSchematicRender", true);
    private static final ConfigBoolean AUTO_ZONE_BY_POSITION = new ConfigBoolean("autoZoneByPosition", false);
    private static final ConfigBoolean AUTO_NEXT_WHEN_DONE  = new ConfigBoolean("autoNextWhenDone", false);
    private static final ConfigBoolean SHOW_ACTIVE_ZONE     = new ConfigBoolean("showActiveZone", true);
    private static final ConfigBoolean SHOW_ALL_ZONES       = new ConfigBoolean("showAllZones", false);
    private static final ConfigBoolean SHOW_ZONE_LABELS     = new ConfigBoolean("showZoneLabels", true);
    private static final ConfigBoolean ZONE_SEE_THROUGH     = new ConfigBoolean("zoneSeeThrough", true);
    private static final ConfigInteger ZONE_DRAW_RANGE      = new ConfigInteger("zoneDrawRange", 96, 16, 512);
    private static final ConfigBoolean TOOL_ENABLED         = new ConfigBoolean("toolEnabled", true);
    private static final ConfigBoolean TOOL_INVENTORY_BUTTON = new ConfigBoolean("toolInventoryButton", true);
    private static final ConfigBoolean TOOL_KEY_HINTS       = new ConfigBoolean("toolKeyHints", true);
    private static final ConfigBoolean TOOL_CLAMP_TO_SCHEMATIC = new ConfigBoolean("toolClampToSchematic", true);
    private static final ConfigInteger TOOL_REACH           = new ConfigInteger("toolReach", 128, 8, 512);
    private static final ConfigInteger SCAN_BLOCKS_PER_TICK = new ConfigInteger("scanBlocksPerTick", 250000, 10000, 4000000);

    // Цвета рамок зон отдельного мода — для свотчей строк (были в ZoneRenderer движка)
    private static final int COLOR_ACTIVE = 0xFF50D0FF;
    private static final int COLOR_DONE   = 0xFF70E070;

    private ZoneFocusTab() {}

    public static boolean standalonePresent()
    {
        return STANDALONE_PRESENT;
    }

    /** Вкладка-пульт; без отдельного мода Zone Focus вкладки нет — {@code null}. */
    public static @Nullable Model.Tab build(net.minecraft.resources.Identifier icon)
    {
        if (! STANDALONE_PRESENT)
        {
            return null;
        }

        // Сосед прошлой сборки тумблера и действий через мост ещё не отдаёт: показывать строки,
        // которые ничего не сделают, хуже, чем прятать их
        boolean sharedToggle = SHARED.option(ZONE_FOCUS.getName()) != null;
        boolean sharedActions = SHARED.has("openSplit");

        Model.Tab tab = new Model.Tab("layerlist.gui.tab_zone_focus").icon(icon);

        // Раздела «Модуль» больше нет (09.10.2026): тумблер модуля в Zone Focus 0.0.8+ не нужен — модуль
        // включён, пока стоит мод (выключить можно в его собственных настройках); «Настройки Zone Focus…»
        // переехали в «Зоны». Старый Zone Focus без карточки — раздел как раньше
        Model.Section module = new Model.Section("layerlist.zf.section.module")
                .note("layerlist.zf.section.module.note_shared");
        boolean modern = sharedActions && SHARED.has("openManager") && com.aell.layerlist.compat.ZoneFocusFiles.available();

        if (sharedToggle && ! modern)
        {
            module.add(Model.Option.toggle("layerlist.zf.config.zoneFocus",
                    Values.of(SHARED.bool(ZONE_FOCUS))));
        }

        if (sharedActions && ! modern)
        {
            // Проверка зоны и клавиши у отдельного мода свои — они остались в его экране
            module.add(Model.Option.action("layerlist.zf.config.standaloneSettings",
                    () -> SHARED.call("openOwnSettings")));
        }

        // Зоны — первым разделом: за ними сюда и приходят. Главное действие — «Управление зонами…»
        // (разбиение, карта, список); старый Zone Focus без него — прежние «Разбить» и «Список»
        if (sharedActions)
        {
            boolean manager = SHARED.has("openManager");
            Model.Section zones = new Model.Section("layerlist.zf.section.zones").note("layerlist.zf.section.zones.note")
                    .accent(COLOR_ACTIVE);

            // Zone Focus 0.0.8+ — большая живая карточка «Управление зонами» со сводкой зон; в ней же яркая
            // кнопка «Настройки Zone Focus» (фиолетовый — «про другой мод»). Качество 3D-превью — в самом
            // «Управлении зонами», рядом с 2D / 3D
            if (modern)
            {
                zones.add(Model.Option.card("layerlist.zf.config.zoneManager",
                        new ZoneManagerCard(() -> SHARED.call("openManager"), () -> SHARED.call("openOwnSettings")),
                        () -> SHARED.call("openManager")));
            }
            else if (manager)
            {
                zones.add(Model.Option.action("layerlist.zf.config.zoneManager", () -> SHARED.call("openManager"))
                        .primary().buttonLabel("layerlist.zf.ui.open_manager").gate(ZoneFocusTab::placementGate));
            }
            else
            {
                zones.add(Model.Option.action("layerlist.zf.config.splitZones", () -> SHARED.call("openSplit"))
                        .gate(ZoneFocusTab::placementGate));
            }

            zones.add(Model.Option.action("layerlist.zf.config.subRegionZones", () -> SHARED.call("splitBySubRegions"))
                            .gate(ZoneFocusTab::placementGate),
                    Model.Option.action("layerlist.zf.config.zoneFromSelection", () -> SHARED.call("zoneFromSelection"))
                            .gate(ZoneFocusTab::selectionGate));

            if (! manager)
            {
                zones.add(Model.Option.action("layerlist.zf.config.zoneList", () -> SHARED.call("openList"))
                        .gate(ZoneFocusTab::zonesGate));
            }

            zones.add(Model.Option.action(manager ? "layerlist.zf.config.undoSplit" : "layerlist.zf.config.clearZones",
                            () -> SHARED.call("clearZones"))
                    .danger().buttonLabel(manager ? "layerlist.zf.ui.undo_split" : null).gate(ZoneFocusTab::zonesGate));

            tab.add(zones);
        }

        if (! module.options.isEmpty())
        {
            tab.add(module);
        }

        // Цвета заголовков — по смыслу: зоны — циан (как активная в мире), подсчёт — золото, переключение и
        // схема — синий, рамки — зелёный (как зоны в мире), инструмент — зелёный кнопки инструмента
        tab.add(new Model.Section("layerlist.zf.section.what_to_count")
                        .note("layerlist.zf.section.what_to_count.note").accent(Ui.C_GOLD).add(
                zfToggle("countByZone", COUNT_BY_ZONE),
                zfToggle("ignoreLayersInZone", IGNORE_LAYERS_IN_ZONE),
                zfToggle("searchWholeZone", SEARCH_WHOLE_ZONE)));

        tab.add(new Model.Section("layerlist.zf.section.auto_switch")
                        .note("layerlist.zf.section.auto_switch.note").accent(Ui.C_ACCENT).add(
                zfToggle("autoZoneByPosition", AUTO_ZONE_BY_POSITION),
                zfToggle("autoNextWhenDone", AUTO_NEXT_WHEN_DONE)));

        tab.add(new Model.Section("layerlist.zf.section.schematic")
                        .note("layerlist.zf.section.schematic.note").accent(Ui.C_ACCENT).add(
                zfToggle("clipSchematicRender", CLIP_SCHEMATIC_RENDER)),
                new Model.Section("layerlist.zf.section.zone_boxes")
                        .note("layerlist.zf.section.zone_boxes.note").accent(COLOR_DONE).add(
                zfToggle("showActiveZone", SHOW_ACTIVE_ZONE)
                        .swatch(() -> COLOR_ACTIVE),
                zfToggle("showAllZones", SHOW_ALL_ZONES)
                        .swatch(() -> COLOR_DONE),
                zfToggle("showZoneLabels", SHOW_ZONE_LABELS)
                        .child().gate(ZoneFocusTab::boxesGate),
                zfToggle("zoneSeeThrough", ZONE_SEE_THROUGH)
                        .child().gate(ZoneFocusTab::boxesGate),
                Model.Option.integer("layerlist.zf.config.zoneDrawRange",
                                Values.of(SHARED.integer(ZONE_DRAW_RANGE)),
                                Num.Scale.LINEAR)
                        .child().gate(ZoneFocusTab::boxesGate)
                        .preview(new RangePreview(ZONE_DRAW_RANGE.getMaxIntegerValue()))));

        // Инструмент и его кнопки на экране — под одним заголовком
        Model.Section tool = new Model.Section("layerlist.zf.section.tool")
                .note("layerlist.zf.section.tool.note").accent(Theme.GREEN).add(
                zfToggle("toolEnabled", TOOL_ENABLED)
                        .swatch(() -> Theme.GREEN));

        if (sharedActions)
        {
            tool.add(Model.Option.action("layerlist.zf.config.startMarking", ZoneFocusTab::startMarking)
                    .child().gate(ZoneFocusTab::markingGate));
        }

        tool.add(
                Model.Option.integer("layerlist.zf.config.toolReach",
                                Values.of(SHARED.integer(TOOL_REACH)), Num.Scale.LINEAR)
                        .child().gate(ZoneFocusTab::toolGate),
                zfToggle("toolClampToSchematic", TOOL_CLAMP_TO_SCHEMATIC)
                        .child().gate(ZoneFocusTab::toolGate));

        tool.add(
                zfToggle("toolInventoryButton", TOOL_INVENTORY_BUTTON)
                        .child().gate(ZoneFocusTab::toolGate),
                zfToggle("toolKeyHints", TOOL_KEY_HINTS)
                        .child().gate(ZoneFocusTab::toolGate));
        tab.add(tool);

        // Техническая настройка — в самом низу, чтобы не путалась под ногами
        tab.add(new Model.Section("layerlist.zf.section.performance")
                        .note("layerlist.zf.section.performance.note").accent(Ui.C_WARN).add(
                Model.Option.integer("layerlist.zf.config.scanBlocksPerTick",
                                Values.of(SHARED.integer(SCAN_BLOCKS_PER_TICK)),
                                Num.Scale.LOG)
                        .gate(ZoneFocusTab::zfGate)
                        .foreignTip(ZoneFocusTab::budgetTip)));

        return tab;
    }

    private static Model.Option zfToggle(String name, ConfigBoolean spec)
    {
        return Model.Option.toggle("layerlist.zf.config." + name, Values.of(SHARED.bool(spec)))
                .gate(ZoneFocusTab::zfGate);
    }

    private static void startMarking()
    {
        if (SHARED.flag("startMarking"))
        {
            Minecraft.getInstance().setScreen(null);
        }
    }

    private static boolean engineOn()
    {
        return SHARED.bool(ZONE_FOCUS).getBooleanValue();
    }

    private static @Nullable Model.Reason zfGate()
    {
        return engineOn() ? null : new Model.Reason(text("gate.zone_focus_off"), false);
    }

    private static @Nullable Model.Reason placementGate()
    {
        Model.Reason off = zfGate();

        if (off != null)
        {
            return off;
        }

        return SHARED.flag("hasPlacement") ? null
                : new Model.Reason(text("gate.no_placement"), false);
    }

    private static @Nullable Model.Reason selectionGate()
    {
        Model.Reason off = zfGate();

        if (off != null)
        {
            return off;
        }

        return SHARED.flag("hasSelection") ? null
                : new Model.Reason(text("gate.no_selection"), false);
    }

    private static @Nullable Model.Reason zonesGate()
    {
        Model.Reason off = zfGate();

        if (off != null)
        {
            return off;
        }

        return SHARED.flag("hasZones") ? null
                : new Model.Reason(text("gate.no_zones"), false);
    }

    private static @Nullable Model.Reason toolGate()
    {
        Model.Reason off = zfGate();

        if (off != null)
        {
            return off;
        }

        return SHARED.bool(TOOL_ENABLED).getBooleanValue() ? null
                : new Model.Reason(text("gate.tool_off"), false);
    }

    private static @Nullable Model.Reason markingGate()
    {
        Model.Reason tool = toolGate();

        if (tool != null)
        {
            return tool;
        }

        return SHARED.flag("hasPlacement") ? null
                : new Model.Reason(text("gate.no_placement"), false);
    }

    private static @Nullable Model.Reason boxesGate()
    {
        Model.Reason off = zfGate();

        if (off != null)
        {
            return off;
        }

        return SHARED.bool(SHOW_ACTIVE_ZONE).getBooleanValue()
                || SHARED.bool(SHOW_ALL_ZONES).getBooleanValue()
                ? null : new Model.Reason(text("gate.no_boxes"), false);
    }

    private static List<String> budgetTip()
    {
        return engineOn() ? List.of(Strings.t("tip.budgets_add_up")) : List.of();
    }

    private static String text(String suffix)
    {
        return Strings.plain("layerlist.zf.ui." + suffix);
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
}
