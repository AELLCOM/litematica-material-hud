package com.aell.layerlist.dev;

import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.gui.GuiHudPosition;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportFormat;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.materials.ReplaceCell;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.SettingsScreen;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.skin.MainMenuSkin;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Dropdown;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.jetbrains.annotations.Nullable;

/**
 * Тур ванильного стиля ({@code LL_COVERAGE=k LL_K_ONLY=vanilla}): каждое окно мода снимается дважды — со стилем Aell UI
 * ({@code vanilla-<окно>-aell}) и с {@code UI_VANILLA = true} ({@code vanilla-<окно>-vanilla}). Окно открывается один
 * раз, стиль переключается на открытом окне, между снимками — пауза на несколько кадров. Не открылось окно — строка
 * INFO и дальше, прогон не падает. К снимкам {@link DumpSchematics} добавляет «k-NN-»: {@code k-07-vanilla-library-aell}.
 */
@Environment(EnvType.CLIENT)
final class KVanillaTour
{
    private final DumpSchematics k;

    // Что вернуть после тура
    private boolean uiBefore;
    private boolean skinBefore;
    private boolean hudBefore;
    private boolean besideBefore;
    private boolean customBefore;
    private int posXBefore;
    private int posYBefore;
    private boolean saved;

    private @Nullable SchematicPlacement placement;
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable Path trashDir;
    private @Nullable Path trashRoot;

    KVanillaTour(DumpSchematics k)
    {
        this.k = k;
    }

    // ===== Сценарий =====

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(this.step(p + "тур: запомнить настройки", 3_000L, mc -> this.remember()));

        this.schematics(s);

        s.add(this.step(p + "тур: «Схемы» закрыть", 3_000L, mc -> this.closeAll()));
        this.world(s);
        this.settings(s);
        this.menus(s);

        s.add(this.step(p + "тур: вернуть настройки", 5_000L, mc -> this.restore()));
        return s;
    }

    // ===== Окно «Схемы» =====

    private void schematics(List<DumpSchematics.Step> s)
    {
        // Библиотека: список, карточка, превью
        s.add(this.step("библиотека: вкладка", 3_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(this.step("библиотека: k-generated", 5_000L, mc -> this.k.selectGenerated()));
        s.add(this.step("библиотека: превью собрано", 70_000L, this.waitFor("превью k-generated", 60_000L, this::previewDone)));
        this.pair(s, "library", 1_500L, true);

        // Меню ПКМ по строке списка
        s.add(this.step("меню ПКМ: по k-generated", 5_000L, this.k.settleThen(300L, mc -> this.rightClickGenerated())));
        this.pair(s, "context-menu", 600L, false);
        s.add(this.step("меню ПКМ: Esc", 3_000L, mc -> this.esc()));

        // «Выгрузить ▾» — меню форматов, потом окно выгрузки
        s.add(this.step("меню выгрузки: кнопка карточки", 5_000L, this.k.settleThen(300L, mc -> this.exportMenu())));
        this.pair(s, "export-menu", 600L, false);
        s.add(this.step("меню выгрузки: Esc", 3_000L, mc -> this.esc()));
        s.add(this.step("окно выгрузки", 5_000L, mc -> this.k.openExportDialog(mc)));
        this.pair(s, "export-window", 1_500L, true);

        // Сетка форматов: мышь на CSV, Excel выбран из меню (наведённая и выбранная плитки рядом)
        s.add(this.step("окно выгрузки: мышь на CSV", 5_000L, this.k.settleThen(300L, mc -> this.hoverFormat(mc, ExportFormat.CSV))));
        this.pair(s, "export-format-hover", 1_200L, false);

        // Выпадающий список «Источник» открыт
        s.add(this.step("окно выгрузки: список «Источник»", 5_000L, this.k.settleThen(300L, mc -> this.openSourceList(mc))));
        this.pair(s, "export-dropdown", 800L, false);
        s.add(this.step("окно выгрузки: список закрыть", 3_000L, mc -> this.esc()));
        s.add(this.step("окно выгрузки: закрыть", 3_000L, mc -> this.k.closeExportDialog(mc)));

        // «Материалы» с тумблером «Считать до Y» в панели инструментов
        s.add(this.step("материалы: вкладка", 3_000L, mc -> this.k.setTab("MATERIALS")));
        s.add(this.step("материалы: строки", 50_000L, this.waitFor("строки «Материалов»", 40_000L, this::materialRows)));
        this.pair(s, "materials", 2_000L, true);

        // Окно «Отключить на слое…», выбор блока для замены, окно настроек схем
        s.add(this.step("отключение на слое: окно", 5_000L, mc -> this.layerDialog()));
        this.pair(s, "layer-disable-dialog", 800L, true);
        s.add(this.step("отключение на слое: закрыть", 3_000L, mc -> this.k.closeExportDialog(mc)));
        s.add(this.step("выбор блока: клик по названию", 5_000L, this.k.settleThen(300L, mc -> this.blockPicker())));
        this.pair(s, "block-picker", 1_000L, true);
        s.add(this.step("выбор блока: закрыть", 3_000L, mc -> this.k.closeExportDialog(mc)));
        s.add(this.step("настройки схем: кнопка в шапке", 5_000L, mc -> this.settingsDialog()));
        this.pair(s, "schematics-settings", 800L, true);
        s.add(this.step("настройки схем: закрыть", 3_000L, mc -> this.k.closeExportDialog(mc)));

        // «Размещения» и редактор размещения
        s.add(this.step("размещение: k-generated", 60_000L, this.waitFor("размещение", 50_000L, this::preparePlacement)));
        s.add(this.step("размещения: вкладка", 3_000L, mc -> this.k.setTab("PLACEMENTS")));
        this.pair(s, "placements", 1_500L, true);
        s.add(this.step("редактор: открыть", 5_000L, mc -> this.openEditor()));
        s.add(this.step("редактор: открыт", 15_000L, this.waitFor("редактор размещения", 10_000L, this::editorShown)));
        this.pair(s, "placement-editor", 1_500L, true);
        s.add(this.step("редактор: Esc", 5_000L, mc -> this.closeEditor()));
        s.add(this.step("редактор: экран «Схемы» вернулся", 15_000L, this.waitFor("возврат из редактора", 10_000L, () -> this.k.gui() != null)));

        // «Выделение» и плитка «Сохранить схему»
        s.add(this.step("выделение: подготовить", 5_000L, mc -> this.prepareSelection()));
        s.add(this.step("выделение: вкладка", 3_000L, mc -> this.k.setTab("SELECTION")));
        this.pair(s, "selection", 3_000L, true);
        s.add(this.step("сохранение: плитка", 5_000L, mc -> this.saveDialog()));
        this.pair(s, "save-dialog", 800L, true);
        s.add(this.step("сохранение: закрыть", 3_000L, mc -> this.k.closeExportDialog(mc)));

        // Удаление в корзину: подтверждение, тост, вкладка «Корзина»; список файлов — во вкладке «Библиотека»
        s.add(this.step("корзина: вкладка «Библиотека»", 3_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(this.step("корзина: свои файлы", 10_000L, mc -> this.trashPrepare()));
        s.add(this.step("корзина: файлы в списке", 20_000L, this.waitFor("файлы корзины", 12_000L, this::trashListed)));
        s.add(this.step("корзина: Delete на первом", 5_000L, this.k.settleThen(300L, mc -> this.trashDelete("kv-a.litematic", false))));
        this.pair(s, "delete-confirm", 800L, true);
        s.add(this.step("корзина: Enter — удалить", 5_000L, this.k.settleThen(200L, mc -> this.enter())));
        s.add(this.step("тост: проверить, что он на экране", 3_000L, mc -> this.ensureToast()));
        this.pair(s, "toast", 400L, true);
        s.add(this.step("корзина: второй файл туда же", 5_000L, this.k.settleThen(1_000L, mc -> this.trashDelete("kv-b.litematic", true))));
        s.add(this.step("корзина: вкладка", 3_000L, mc -> this.k.setTab("TRASH")));
        this.pair(s, "trash", 1_500L, true);
        s.add(this.step("корзина: назад в «Библиотеку»", 3_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(this.step("корзина: убрать за собой", 10_000L, mc -> this.trashTidy()));
    }

    // ===== Мир: панель HUD и сундук =====

    private void world(List<DumpSchematics.Step> s)
    {
        s.add(this.step("HUD: включить, ждать данных панели", 40_000L, this.waitFor("данные панели HUD", 30_000L, this::hudData)));
        this.pair(s, "hud-world", 1_000L, false);

        s.add(this.step("сундук: открыть", 5_000L, mc -> this.openChest(mc)));
        this.pair(s, "hud-chest", 1_500L, true);
        s.add(this.step("сундук: закрыть", 5_000L, mc -> this.closeAll()));
        s.add(this.step("размещение: убрать", 5_000L, mc -> this.dropPlacement()));
    }

    // ===== Настройки =====

    private void settings(List<DumpSchematics.Step> s)
    {
        // Вкладки: список берём у открытого экрана — вкладки аддонов зависят от установленных модов
        s.add(this.step("настройки: открыть", 5_000L, mc -> this.openSettings(0)));
        s.add(this.step("настройки: вкладки", 5_000L, mc -> this.expandTabs()));

        // Поиск с текстом
        s.add(this.step("поиск: открыть вкладку HUD", 5_000L, mc -> this.openSettings(0)));
        s.add(this.step("поиск: набрать текст", 5_000L, this.k.settleThen(500L, mc -> this.searchText("стил"))));
        this.pair(s, "settings-search", 800L, true);

        // Подсказка: мышь на строку настроек
        s.add(this.step("подсказка: открыть вкладку HUD", 5_000L, mc -> this.openSettings(0)));
        s.add(this.step("подсказка: мышь на строку", 5_000L, this.k.settleThen(500L, mc -> this.hoverRow(mc))));
        this.pair(s, "settings-tooltip", 1_800L, false);

        // Ввод клавиши
        s.add(this.step("клавиша: вкладка «Горячие клавиши»", 5_000L, mc -> this.openHotkeys()));
        s.add(this.step("клавиша: Tab, Enter — ввод", 5_000L, this.k.settleThen(500L, mc -> this.startCapture())));
        this.pair(s, "settings-keybind-capture", 800L, true);
        s.add(this.step("клавиша: Esc, сброс клавиши", 5_000L, mc -> this.endCapture()));

        // Положение HUD
        s.add(this.step("положение HUD: открыть", 5_000L, mc -> this.openHudPosition()));
        this.pair(s, "hud-position", 1_500L, true);
        s.add(this.step("положение HUD: закрыть", 5_000L, mc -> this.closeAll()));
    }

    // ===== Меню Litematica =====

    private void menus(List<DumpSchematics.Step> s)
    {
        s.add(this.step("Litematica: перекраска вкл.", 3_000L, mc -> this.skin(true)));
        s.add(this.step("Litematica: главное меню", 5_000L, mc -> this.openMainMenu()));
        this.pair(s, "litematica-menu", 1_500L, true);
        s.add(this.step("Litematica: экран настроек", 5_000L, mc -> this.openLitematicaConfigs()));
        this.pair(s, "litematica-configs", 1_500L, true);
        s.add(this.step("Litematica: закрыть, перекраска как была", 5_000L, mc ->
        {
            this.closeAll();
            return this.skin(this.skinBefore);
        }));

        // Вкладка Zone Focus есть в общем ряду настроек (expandTabs); отдельно — только через API и если мод стоит
        s.add(this.step("Zone Focus: вкладка через API", 5_000L, mc -> this.openZoneFocus()));
        this.pair(s, "zonefocus-tab", 1_500L, true);
        s.add(this.step("Zone Focus: закрыть", 3_000L, mc -> this.closeAll()));
    }

    // ===== Строительные блоки сценария =====

    private DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, this.safe(name, action));
    }

    /** Исключение внутри шага — INFO и дальше: не открылось одно окно, остальные снимаются. */
    private DumpSchematics.Action safe(String what, DumpSchematics.Action action)
    {
        return mc ->
        {
            try
            {
                return action.run(mc);
            }
            catch (RuntimeException | LinkageError e)
            {
                this.k.info("тур: «" + what + "» не вышло — " + e);
                return true;
            }
        };
    }

    /** Ждать условия не дольше {@code ms}: не дождались — INFO и дальше (шаг сам не падает по тайм-ауту). */
    private DumpSchematics.Action waitFor(String what, long ms, BooleanSupplier ready)
    {
        long[] since = {0L};

        return mc ->
        {
            long now = Util.getMillis();

            if (since[0] == 0L)
            {
                since[0] = now;
            }

            if (ready.getAsBoolean())
            {
                return true;
            }

            if (now - since[0] > ms)
            {
                this.k.info("тур: не дождались — " + what);
                return true;
            }

            return false;
        };
    }

    /**
     * Пара снимков одного окна: Aell UI → включить ванильный → пауза на кадры → снимок → Aell UI обратно.
     * {@code aside} — мышь в пустой угол (иначе наведение красит строку), {@code settleMs} — кадры на раскладку и значки.
     */
    private void pair(List<DumpSchematics.Step> s, String window, long settleMs, boolean aside)
    {
        String base = "vanilla-" + window;
        s.add(this.step(base + ": стиль Aell", 3_000L, mc -> this.style(false)));
        s.add(this.step(base + ": снимок Aell", 20_000L, this.shotAction(base + "-aell", settleMs, aside)));
        s.add(this.step(base + ": стиль Minecraft", 3_000L, mc -> this.style(true)));
        s.add(this.step(base + ": снимок Minecraft", 20_000L, this.shotAction(base + "-vanilla", Math.max(settleMs, 900L), aside)));
        // Vanilla+ меняет только экран настроек — третий снимок у сцен настроек
        if (window.startsWith("settings") || window.startsWith("zonefocus"))
        {
            s.add(this.step(base + ": стиль Vanilla+", 3_000L, mc -> this.stylePlus()));
            s.add(this.step(base + ": снимок Vanilla+", 20_000L, this.shotAction(base + "-vanillaplus", Math.max(settleMs, 900L), aside)));
        }

        s.add(this.step(base + ": стиль Aell обратно", 3_000L, mc -> this.style(false)));
    }

    private DumpSchematics.Action shotAction(String name, long settleMs, boolean aside)
    {
        long[] movedAt = {0L};

        return this.k.settleThen(settleMs, mc ->
        {
            if (aside)
            {
                long now = Util.getMillis();

                if (movedAt[0] == 0L)
                {
                    movedAt[0] = now;
                    mc.mouseHandler.onMove(mc.getWindow().handle(), 2.0, 2.0, 0.0, 0.0);
                }

                // После сдвига мыши — пара кадров, чтобы наведение погасло
                if (now - movedAt[0] < 250L)
                {
                    return false;
                }
            }

            this.k.shot(mc, name);
            return true;
        });
    }

    private boolean style(boolean vanilla)
    {
        LayerListConfigs.UI_STYLE.setIntegerValue(vanilla ? 1 : 0);
        return true;
    }

    private boolean stylePlus()
    {
        LayerListConfigs.UI_STYLE.setIntegerValue(2);
        return true;
    }

    private boolean remember()
    {
        this.uiBefore = LayerListConfigs.UI_STYLE.getIntegerValue() != 0;
        this.skinBefore = SchematicsConfig.LITEMATICA_SKIN.getBooleanValue();
        this.hudBefore = LayerListConfigs.HUD_ENABLED;
        this.besideBefore = LayerListConfigs.HUD_BESIDE_CONTAINER.getBooleanValue();
        this.customBefore = LayerListConfigs.HUD_CUSTOM_POSITION.getBooleanValue();
        this.posXBefore = LayerListConfigs.HUD_POS_X;
        this.posYBefore = LayerListConfigs.HUD_POS_Y;
        this.saved = true;
        this.k.info("тур ванильного стиля: стиль был " + (this.uiBefore ? "ванильный" : "Aell"));
        return true;
    }

    private boolean restore()
    {
        LayerListConfigs.UI_STYLE.setIntegerValue(0);

        if (this.saved)
        {
            LayerListConfigs.HUD_ENABLED = this.hudBefore;
            LayerListConfigs.HUD_BESIDE_CONTAINER.setBooleanValue(this.besideBefore);
            LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(this.customBefore);
            LayerListConfigs.HUD_POS_X = this.posXBefore;
            LayerListConfigs.HUD_POS_Y = this.posYBefore;
            this.skin(this.skinBefore);
        }

        this.dropPlacement();
        this.trashTidy();
        this.closeAll();
        return true;
    }

    private boolean closeAll()
    {
        Nav.clear();
        GuiBase.openGui(null);
        return true;
    }

    private boolean skin(boolean on)
    {
        SchematicsConfig.LITEMATICA_SKIN.setBooleanValue(on);
        MainMenuSkin.TestHooks.slow(1.0F);
        return true;
    }

    private boolean esc()
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            KProbe.press(gui, InputConstants.KEY_ESCAPE);
        }

        return true;
    }

    private boolean enter()
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            KProbe.press(gui, InputConstants.KEY_RETURN);
        }

        return true;
    }

    // ===== «Схемы»: открытие окон =====

    private boolean previewDone()
    {
        SchematicEntry entry = this.k.generated(".litematic");
        return entry == null || this.k.previewReadyFor(entry);
    }

    private boolean rightClickGenerated()
    {
        GuiSchematics gui = this.k.gui();
        ScrollList<?> list = KProbe.fieldOfType(this.k.files(), ScrollList.class);
        SchematicEntry sample = this.k.generated(".litematic");

        if (gui == null || list == null || sample == null)
        {
            this.k.info("тур: меню ПКМ — нет экрана или списка");
            return true;
        }

        int index = -1;
        List<?> items = list.items();

        for (int i = 0; i < items.size(); ++i)
        {
            SchematicEntry entry = (SchematicEntry) KProbe.recordComponent(items.get(i), SchematicEntry.class);

            if (entry != null && entry.path().equals(sample.path()))
            {
                index = i;
                break;
            }
        }

        if (index < 0)
        {
            this.k.info("тур: меню ПКМ — строки k-generated в списке нет");
            return true;
        }

        list.ensureVisible(index);
        double x = list.x() + list.w() / 3.0;

        for (int y = list.y(); y < list.y() + list.h(); ++y)
        {
            if (list.indexAt(x, y + 0.5) == index)
            {
                var click = new net.minecraft.client.input.MouseButtonEvent(x, y + 4.5,
                        new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                gui.onMouseClicked(click, false);
                gui.onMouseReleased(click);
                SupportTest.moveMouse(Minecraft.getInstance(), x, y + 4.5);
                return true;
            }
        }

        this.k.info("тур: меню ПКМ — строка не на экране");
        return true;
    }

    private boolean exportMenu()
    {
        GuiSchematics gui = this.k.gui();
        Button button = KProbe.cardExport(this.k.infoPane());

        if (gui == null || button == null)
        {
            this.k.info("тур: меню выгрузки — нет кнопки «Выгрузить»");
            return true;
        }

        this.k.clickButton(gui, button, this.k.infoPane());
        return true;
    }

    private boolean materialRows()
    {
        return KProbe.materialRows(KProbe.fieldNamedType(this.k.gui(), "MaterialsPane")) > 0;
    }

    @SuppressWarnings("unchecked")
    private @Nullable MaterialLine firstLine()
    {
        Object pane = KProbe.fieldNamedType(this.k.gui(), "MaterialsPane");
        Table<MaterialLine> table = KProbe.fieldOfType(pane, Table.class);

        if (table == null || table.body().items().isEmpty())
        {
            return null;
        }

        return table.body().items().getFirst();
    }

    private boolean layerDialog()
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = KProbe.fieldOfType(gui, Layers.class);
        MaterialLine line = this.firstLine();
        LoadedSchematic loaded = this.loadedGenerated();

        if (gui == null || layers == null || line == null || loaded == null)
        {
            this.k.info("тур: окно «Отключить на слое» — нет таблицы или схемы");
            return true;
        }

        try
        {
            Class<?> type = Class.forName("com.aell.layerlist.schematics.gui.LayerDisableDialog");
            Method open = type.getDeclaredMethod("open", Layers.class, LitematicaSchematic.class, MaterialLine.class,
                    IntConsumer.class);
            open.setAccessible(true);
            open.invoke(null, layers, loaded.litematica(), line, (IntConsumer) layer -> {});
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.info("тур: окно «Отключить на слое» не открылось — " + e);
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean blockPicker()
    {
        Object pane = KProbe.fieldNamedType(this.k.gui(), "MaterialsPane");
        Table<MaterialLine> table = KProbe.fieldOfType(pane, Table.class);
        ReplaceCell cell = KProbe.fieldOfType(pane, ReplaceCell.class);
        MaterialLine line = this.firstLine();

        if (table == null || cell == null || line == null)
        {
            this.k.info("тур: выбор блока — нет таблицы");
            return true;
        }

        table.setSelected(line);
        int[] box = cell.cellBox(line.key());

        if (box == null)
        {
            this.k.info("тур: выбор блока — ячейка " + line.key() + " не на экране");
            return true;
        }

        KInline.realClick(box[0] + box[2] / 3.0, box[1] + box[3] / 2.0);
        return true;
    }

    private boolean settingsDialog()
    {
        GuiSchematics gui = this.k.gui();
        Button settings = KTrash.field(gui, "settings");

        if (gui == null || settings == null)
        {
            this.k.info("тур: настройки схем — нет кнопки в шапке");
            return true;
        }

        this.k.clickButton(gui, settings, null);
        return true;
    }

    // ===== «Схемы»: размещение, редактор, выделение =====

    private @Nullable LoadedSchematic loadedGenerated()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        if (generated == null)
        {
            return null;
        }

        LoadedSchematic loaded = this.k.loaded(generated.path());

        if (loaded != null)
        {
            return loaded;
        }

        if (this.loading == null)
        {
            this.loading = SchematicLibrary.get().load(generated);
        }

        return this.loading.isDone() ? this.loading.join() : null;
    }

    /** Размещение k-generated перед игроком: нужно вкладке «Размещения», редактору и данным панели HUD. */
    private boolean preparePlacement()
    {
        if (this.placement != null || this.k.generated(".litematic") == null)
        {
            return true;
        }

        LoadedSchematic loaded = this.loadedGenerated();

        if (loaded == null)
        {
            return false;
        }

        this.placement = PlacementOps.placeInFront(loaded);
        return true;
    }

    private boolean dropPlacement()
    {
        SchematicPlacement p = this.placement;
        this.placement = null;

        if (p != null && PlacementOps.all().contains(p))
        {
            PlacementOps.remove(p);
        }

        return true;
    }

    private boolean openEditor()
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null || this.placement == null)
        {
            this.k.info("тур: редактор — нет экрана или размещения");
            return true;
        }

        PlacementEditorScreen.open(gui, this.placement, false, false);
        return true;
    }

    private boolean editorShown()
    {
        PlacementEditorScreen editor = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());
        return editor == null ? this.placement == null : PlacementEditorScreen.TestHooks.frames(editor) >= 2;
    }

    private boolean closeEditor()
    {
        PlacementEditorScreen editor = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());

        if (editor != null)
        {
            KProbe.press(editor, InputConstants.KEY_ESCAPE);
        }

        return true;
    }

    private boolean prepareSelection()
    {
        SelectionManager manager = DataManager.getSelectionManager();
        var player = Minecraft.getInstance().player;

        if (player == null)
        {
            this.k.info("тур: выделение — нет игрока");
            return true;
        }

        if (! manager.hasNormalSelection())
        {
            manager.setCurrentSelection(manager.createNewSelection(DataManager.getAreaSelectionsBaseDirectory(), "k-vanilla"));
        }

        if (manager.getSelectionMode() == SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();

        if (selection == null)
        {
            this.k.info("тур: выделения нет");
            return true;
        }

        BlockPos at = player.blockPosition();
        this.box(selection, "Основа", at.offset(-6, 0, -4), at.offset(5, 6, 7));
        this.box(selection, "Крыша", at.offset(-6, 7, -4), at.offset(5, 10, 7));
        selection.setSelectedSubRegionBox("Основа");
        selection.setOriginSelected(false);
        selection.setCurrentSelectedCorner(Corner.CORNER_1);
        return true;
    }

    private void box(AreaSelection selection, String name, BlockPos a, BlockPos b)
    {
        if (selection.getSubRegionBox(name) == null)
        {
            selection.createNewSubRegionBox(a, name);
        }

        Box box = selection.getSubRegionBox(name);

        if (box != null)
        {
            selection.setSubRegionCornerPos(box, Corner.CORNER_1, a);
            selection.setSubRegionCornerPos(box, Corner.CORNER_2, b);
        }
    }

    private boolean saveDialog()
    {
        GuiSchematics gui = this.k.gui();
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();

        if (gui == null || selection == null)
        {
            this.k.info("тур: плитка сохранения — нет экрана или выделения");
            return true;
        }

        try
        {
            Class<?> type = Class.forName("com.aell.layerlist.schematics.gui.SaveSchematicDialog");
            Method open = type.getDeclaredMethod("open", GuiSchematics.class, AreaSelection.class);
            open.setAccessible(true);
            open.invoke(null, gui, selection);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.info("тур: плитка сохранения не открылась — " + e);
        }

        return true;
    }

    // ===== «Схемы»: корзина и тост =====

    private boolean trashPrepare()
    {
        SchematicEntry sample = this.k.generated(".litematic");
        Object files = this.k.files();

        if (sample == null || files == null)
        {
            this.k.info("тур: корзина — нет образца или списка файлов");
            return true;
        }

        try
        {
            this.trashRoot = this.k.outDir().resolve("trash-vanilla");
            KData.recreate(this.trashRoot);
            SchematicTrash.TestHooks.root(this.trashRoot);

            this.trashDir = SchematicLibrary.get().root().resolve("_k_vanilla").toAbsolutePath().normalize();
            KData.recreate(this.trashDir);

            for (String name : List.of("kv-a.litematic", "kv-b.litematic"))
            {
                Files.copy(sample.path(), this.trashDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }

            KProbe.call(files, "open", this.trashDir);
        }
        catch (IOException | ReflectiveOperationException | RuntimeException e)
        {
            this.k.info("тур: корзина — подготовка не вышла: " + e);
            this.trashDir = null;
        }

        return true;
    }

    private boolean trashListed()
    {
        Path dir = this.trashDir;
        return dir == null || SchematicLibrary.get().entry(dir.resolve("kv-a.litematic")) != null;
    }

    /** Выбрать файл и нажать Delete; {@code confirm} — ещё и Enter (кнопка «Удалить» по умолчанию). */
    private boolean trashDelete(String name, boolean confirm)
    {
        Path dir = this.trashDir;
        SchematicEntry entry = dir == null ? null : SchematicLibrary.get().entry(dir.resolve(name));

        if (entry == null || this.k.gui() == null)
        {
            this.k.info("тур: корзина — нет файла " + name);
            return true;
        }

        this.k.selectEntry(entry);
        KTrash.realKey(InputConstants.KEY_DELETE);

        if (confirm)
        {
            this.enter();
        }

        return true;
    }

    private boolean trashTidy()
    {
        SchematicTrash.TestHooks.root(null);

        for (Path folder : new Path[] { this.trashDir, this.trashRoot })
        {
            if (folder == null)
            {
                continue;
            }

            try (Stream<Path> walk = Files.walk(folder))
            {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.deleteIfExists(path);
                }
            }
            catch (IOException | RuntimeException e)
            {
                // Уже убрано
            }
        }

        this.trashDir = null;
        this.trashRoot = null;
        return true;
    }

    // ===== Мир =====

    private boolean hudData()
    {
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.HUD_BESIDE_CONTAINER.setBooleanValue(true);
        LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(false);
        return this.placement == null || LayerCountManager.getInstance().hasData();
    }

    private boolean openChest(Minecraft mc)
    {
        if (mc.player == null)
        {
            this.k.info("тур: сундук — нет игрока");
            return true;
        }

        Inventory inventory = mc.player.getInventory();
        mc.gui.setScreen(new ContainerScreen(ChestMenu.sixRows(0, inventory), inventory, Component.literal("Большой сундук")));
        return true;
    }

    // ===== Настройки: открытие =====

    private boolean openSettings(int tab)
    {
        Nav.clear();
        GuiBase.openGui(GuiConfigs.create(null, null).showTab(tab));
        return true;
    }

    private boolean openHotkeys()
    {
        Nav.clear();
        GuiBase.openGui(GuiConfigs.create(null, null).showTab("layerlist.gui.tab_hotkeys"));
        return true;
    }

    /** Вставить в очередь по паре снимков на каждую вкладку открытого экрана настроек. */
    private boolean expandTabs()
    {
        if (! (GuiUtils.getCurrentScreen() instanceof SettingsScreen screen))
        {
            this.k.info("тур: настройки не открылись — вкладки не снять");
            return true;
        }

        List<String> keys = new ArrayList<>();

        try
        {
            Field field = SettingsScreen.class.getDeclaredField("tabs");
            field.setAccessible(true);

            for (Object tab : (List<?>) field.get(screen))
            {
                keys.add(String.valueOf(tab.getClass().getField("key").get(tab)));
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            keys.clear();

            for (int i = 0; i < screen.tabCount(); ++i)
            {
                keys.add("tab-" + i);
            }
        }

        List<DumpSchematics.Step> more = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (int i = 0; i < keys.size(); ++i)
        {
            int index = i;
            String name = tabName(keys.get(i));

            if (seen.contains(name))
            {
                name = name + "-" + i;
            }

            seen.add(name);
            more.add(this.step("настройки: вкладка " + name, 5_000L, mc -> this.openSettings(index)));
            this.pair(more, "settings-" + name, 900L, true);
        }

        this.k.info("тур: вкладок настроек " + keys.size() + " — " + seen);
        this.k.insertNext(more);
        return true;
    }

    /** {@code layerlist.gui.tab_hud} → {@code hud}: латиница, строчные, через дефис. */
    private static String tabName(String key)
    {
        int at = key.lastIndexOf("tab_");
        String rest = at >= 0 ? key.substring(at + 4) : key;
        return rest.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** Строка поиска настроек: поле и раскладка — как у {@link SettingsShot}. */
    private boolean searchText(String text)
    {
        if (! (GuiUtils.getCurrentScreen() instanceof SettingsScreen screen))
        {
            this.k.info("тур: поиск — нет экрана настроек");
            return true;
        }

        try
        {
            Class<?> type = SettingsScreen.class;
            Field search = type.getDeclaredField("search");
            search.setAccessible(true);
            search.set(screen, text);

            try
            {
                Field focus = type.getDeclaredField("searchFocus");
                focus.setAccessible(true);
                focus.setBoolean(screen, true);
            }
            catch (NoSuchFieldException e)
            {
                // Фокуса поиска в этой версии нет — текст и так виден
            }

            Method layout = type.getDeclaredMethod("layout");
            layout.setAccessible(true);
            layout.invoke(screen);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.info("тур: поиск не задан — " + e);
        }

        return true;
    }

    /**
     * Мышь на строку настроек, у которой есть подсказка: подсказка ({@code SettingsScreen.drawButtonHoverTexts}) идёт после
     * {@code Ui.TIP_DELAY_MS} неподвижного наведения на опцию с текстом или на раздел с пометкой «?». Строку ищем теми же
     * {@code rowAt}/{@code tipTarget}, что и экран; не нашли — прежнее приблизительное место.
     */
    private boolean hoverRow(Minecraft mc)
    {
        if (GuiUtils.getCurrentScreen() instanceof SettingsScreen screen)
        {
            try
            {
                int bodyY = intField(screen, "bodyY");
                int bodyH = intField(screen, "bodyH");
                int x = intField(screen, "contentX") + 24;
                Method rowAt = SettingsScreen.class.getDeclaredMethod("rowAt", int.class, int.class);
                rowAt.setAccessible(true);
                Method tipTarget = null;

                for (Method method : SettingsScreen.class.getDeclaredMethods())
                {
                    if (method.getName().equals("tipTarget") && method.getParameterCount() == 1)
                    {
                        tipTarget = method;
                    }
                }

                if (tipTarget != null)
                {
                    tipTarget.setAccessible(true);

                    for (int y = bodyY + 4; y < bodyY + bodyH; y += 3)
                    {
                        Object row = rowAt.invoke(screen, x, y);

                        if (row != null && tipTarget.invoke(screen, row) != null)
                        {
                            this.k.info("тур: подсказка настроек — мышь в (" + x + ", " + y + "), ждать > " + Ui.TIP_DELAY_MS + " мс");
                            SupportTest.moveMouse(mc, x, y);
                            return true;
                        }
                    }
                }
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                this.k.info("тур: строка с подсказкой не найдена — " + e);
            }
        }

        SupportTest.moveMouse(mc, mc.getWindow().getGuiScaledWidth() * 0.06, mc.getWindow().getGuiScaledHeight() * 0.38);
        return true;
    }

    private static int intField(Object owner, String name) throws ReflectiveOperationException
    {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(owner);
    }

    // ===== Окно выгрузки: сетка форматов и список источника =====

    private @Nullable ExportWindow exportWindow()
    {
        Layers layers = KProbe.fieldOfType(this.k.gui(), Layers.class);
        return layers != null && layers.top() instanceof ExportWindow window ? window : null;
    }

    /** Мышь в центр плитки формата — наведение красит её рядом с выбранной. */
    private boolean hoverFormat(Minecraft mc, ExportFormat format)
    {
        ExportWindow window = this.exportWindow();
        Object grid = window == null ? null : KProbe.fieldNamedType(window, "FormatGrid");

        try
        {
            if (grid != null && KProbe.call(grid, "button", format) instanceof Button button)
            {
                SupportTest.moveMouse(mc, button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
                return true;
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("тур: сетка форматов не читается — " + e);
        }

        this.k.info("тур: плитки формата " + format + " нет (окно выгрузки закрыто?)");
        return true;
    }

    /** Открыть выпадающий список «Источник»: мышь в сторону, список — как после клика. */
    private boolean openSourceList(Minecraft mc)
    {
        ExportWindow window = this.exportWindow();
        Dropdown<?> list = window == null ? null : deepFind(window, Dropdown.class, 5, new java.util.IdentityHashMap<>());

        if (list == null)
        {
            this.k.info("тур: списка «Источник» в окне выгрузки нет");
            return true;
        }

        SupportTest.moveMouse(mc, 2.0, 2.0);
        list.expand();
        return true;
    }

    /** Первый экземпляр {@code type} среди полей (и элементов списков) на глубину {@code depth}. */
    private static <T> @Nullable T deepFind(Object owner, Class<T> type, int depth, java.util.Map<Object, Boolean> seen)
    {
        if (depth < 0 || seen.put(owner, Boolean.TRUE) != null)
        {
            return null;
        }

        if (type.isInstance(owner))
        {
            return type.cast(owner);
        }

        List<Object> children = new ArrayList<>();

        if (owner instanceof List<?> list)
        {
            children.addAll(list);
        }
        else if (owner.getClass().getName().startsWith("com.aell."))
        {
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            {
                for (Field field : c.getDeclaredFields())
                {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                    {
                        continue;
                    }

                    try
                    {
                        field.setAccessible(true);
                        Object value = field.get(owner);

                        if (value != null)
                        {
                            children.add(value);
                        }
                    }
                    catch (ReflectiveOperationException | RuntimeException e)
                    {
                        // Поле не читается — пропустить
                    }
                }
            }
        }

        for (Object child : children)
        {
            T found = deepFind(child, type, depth - 1, seen);

            if (found != null)
            {
                return found;
            }
        }

        return null;
    }

    /** Если Enter подтверждения не дал тост (экран был не тот) — показать настоящий тост экрана «Схемы» самим стеком. */
    private boolean ensureToast()
    {
        Toasts toasts = KProbe.fieldOfType(this.k.gui(), Toasts.class);

        if (toasts == null)
        {
            this.k.info("тур: тост — нет стопки на экране «Схемы»");
        }
        else if (Toasts.TestHooks.texts(toasts).isEmpty())
        {
            this.k.info("тур: тост от удаления не пришёл — показан тост стопки напрямую");
            toasts.show("«kv-a.litematic» удалён в корзину", Ui.C_WARN, Toasts.Action.once("Отменить", () -> {}));
        }

        return true;
    }

    private boolean startCapture()
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            // Tab — фокус на первую строку, Enter — начать ввод клавиши (как у TourShots)
            KProbe.press(gui, InputConstants.KEY_TAB);
            KProbe.press(gui, InputConstants.KEY_RETURN);
        }

        return true;
    }

    private boolean endCapture()
    {
        this.esc();
        LayerListConfigs.TOGGLE_HUD.resetToDefault();
        return true;
    }

    private boolean openHudPosition()
    {
        this.openSettings(0);
        GuiHudPosition.openFromSettings();
        return true;
    }

    // ===== Меню Litematica и Zone Focus =====

    private boolean openMainMenu()
    {
        Nav.clear();
        GuiBase.openGui(new GuiMainMenu());
        return true;
    }

    private boolean openLitematicaConfigs()
    {
        Nav.clear();
        DataManager.setConfigGuiTab(fi.dy.masa.litematica.gui.GuiConfigs.ConfigGuiTab.GENERIC);
        GuiBase.openGui(new fi.dy.masa.litematica.gui.GuiConfigs());
        return true;
    }

    private boolean openZoneFocus()
    {
        Screen screen = LayerListApi.createSettings(null, null, LayerListApi.TAB_ZONE_FOCUS);

        if (screen == null)
        {
            this.k.info("тур: вкладки Zone Focus нет (мод не стоит)");
            return true;
        }

        Nav.clear();
        GuiBase.openGui(screen);
        return true;
    }
}
