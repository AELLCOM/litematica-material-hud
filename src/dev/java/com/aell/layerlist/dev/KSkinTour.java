package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.skin.MainMenuSkin;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiAreaSelectionEditorNormal;
import fi.dy.masa.litematica.gui.GuiAreaSelectionManager;
import fi.dy.masa.litematica.gui.GuiConfigs;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.GuiPlacementConfiguration;
import fi.dy.masa.litematica.gui.GuiRenderLayer;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.GuiSchematicLoadedList;
import fi.dy.masa.litematica.gui.GuiSchematicManager;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.litematica.gui.GuiSchematicProjectsBrowser;
import fi.dy.masa.litematica.gui.GuiSchematicSave;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.GuiSubRegionConfiguration;
import fi.dy.masa.litematica.gui.GuiTaskManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import net.minecraft.core.BlockPos;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfirmAction;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Обход всех экранов Litematica в цветах Material HUD ({@code LL_K_ONLY=skin}): каждый экран открывается как из
 * игры и снимается ({@code tour-<экран>}). Для экранов размещения — своё размещение k-generated, для редактора
 * региона — выделение; окна malilib (ввод текста, подтверждение) — поверх списка размещений. Проверка — по снимкам.
 */
@Environment(EnvType.CLIENT)
final class KSkinTour
{
    private final DumpSchematics k;
    private @Nullable SchematicPlacement placement;
    private @Nullable CompletableFuture<LoadedSchematic> loading;

    KSkinTour(DumpSchematics k)
    {
        this.k = k;
    }

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(new DumpSchematics.Step(p + "обход: перекраска вкл., размещение", 40_000L, mc -> this.prepare()));
        this.screen(s, p, "main-menu", GuiMainMenu::new);

        for (GuiConfigs.ConfigGuiTab tab : new GuiConfigs.ConfigGuiTab[] { GuiConfigs.ConfigGuiTab.GENERIC,
                GuiConfigs.ConfigGuiTab.VISUALS, GuiConfigs.ConfigGuiTab.COLORS, GuiConfigs.ConfigGuiTab.HOTKEYS })
        {
            this.screen(s, p, "configs-" + tab.name().toLowerCase(), () ->
            {
                DataManager.setConfigGuiTab(tab);
                return new GuiConfigs();
            });
        }

        this.screen(s, p, "render-layers", GuiRenderLayer::new);
        this.screen(s, p, "placements", GuiSchematicPlacementsList::new);
        this.screen(s, p, "placement-config", () -> this.placement != null ? new GuiPlacementConfiguration(this.placement) : null);
        this.screen(s, p, "subregion-config", this::subRegion);
        this.screen(s, p, "loaded", GuiSchematicLoadedList::new);
        this.screen(s, p, "load", GuiSchematicLoad::new);
        this.screen(s, p, "manager", GuiSchematicManager::new);
        this.screen(s, p, "save-selection", GuiSchematicSave::new);
        this.screen(s, p, "area-manager", GuiAreaSelectionManager::new);
        this.screen(s, p, "area-editor", this::areaEditor);
        // Своя вкладка «Выделение»: «Редактор региона» Litematica (её же путь открытия) ведёт туда
        s.add(new DumpSchematics.Step(p + "обход: составное выделение из двух компонентов", 5_000L, mc -> this.prepareSelection()));
        s.add(new DumpSchematics.Step(p + "обход: «Редактор региона» → «Выделение»", 5_000L, mc -> this.openOwnEditor()));
        s.add(new DumpSchematics.Step(p + "обход: снимок selection-tab", 5_000L, this.k.settleShot(p + "tour-selection-tab")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: «+» у X угла 1 двигает угол", 5_000L, mc -> this.nudgeCorner()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: «+ Компонент» добавляет компонент", 5_000L,
                this.k.settleThen(200L, mc -> this.addBox())));
        // Живое превью области: снимок, модель, 3D, щелчок-угол, 2D, анализ, сброс, кнопки меню
        s.add(new DumpSchematics.Step(p + "«Выделение»: превью области построено", 90_000L, mc -> this.previewReady()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: снимок 3D", 5_000L, this.k.settleShot(p + "tour-selection-3d")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: щелчок по превью ставит угол", 5_000L, mc -> this.pickInPreview()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: 2D", 5_000L, mc -> this.pressPaneButton("mode2d")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: снимок 2D", 30_000L, this.k.settleThen(2_500L, mc ->
        {
            this.k.shot(mc, p + "tour-selection-2d");
            return true;
        })));
        s.add(new DumpSchematics.Step(p + "«Выделение»: щелчок по 2D-карте ставит угол", 5_000L, mc -> this.pickInPreview()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: обратно в 3D", 5_000L, mc -> this.pressPaneButton("mode3d")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: «Анализ области»", 5_000L, mc -> this.pressPaneButton("analyze")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: анализ открыл «Материалы»", 30_000L, mc -> this.onMaterials()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: снимок анализа", 5_000L, this.k.settleShot(p + "tour-selection-analysis")));
        s.add(new DumpSchematics.Step(p + "меню: «Размещённые голограммы» → «Размещения»", 5_000L, mc -> this.reroute()));
        // «Сохранить схему»: своя плитка, потом «Библиотека» со свежей схемой сверху
        s.add(new DumpSchematics.Step(p + "«Выделение»: «Сохранить схему» открывает плитку", 5_000L, mc -> this.pressPaneButton("save")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: снимок плитки сохранения", 5_000L, this.k.settleShot(p + "tour-save-dialog")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: Enter — сохраняем", 5_000L, mc -> this.pressEnter()));
        s.add(new DumpSchematics.Step(p + "«Сохранить»: открылась «Библиотека» с новой схемой", 30_000L, mc -> this.savedShown()));
        s.add(new DumpSchematics.Step(p + "«Сохранить»: снимок NEW", 5_000L, this.k.settleShot(p + "tour-saved-new")));
        s.add(new DumpSchematics.Step(p + "«Сохранить»: убрать файл, назад во «Выделение»", 5_000L, mc -> this.dropSaved()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: «Сбросить» спрашивает", 5_000L, mc -> this.askReset()));
        s.add(new DumpSchematics.Step(p + "«Выделение»: снимок подтверждения", 5_000L, this.k.settleShot(p + "tour-selection-reset")));
        s.add(new DumpSchematics.Step(p + "«Выделение»: Enter — сброшено", 5_000L, mc -> this.confirmReset()));
        // Простое выделение: Litematica не даёт удалить его компонент — «Сбросить» стирает углы
        s.add(new DumpSchematics.Step(p + "«Выделение»: простое — углы стоят", 5_000L, this.k.settleThen(300L, mc -> this.simpleWithCorners())));
        s.add(new DumpSchematics.Step(p + "«Выделение»: простое — «Сбросить»", 5_000L, this.k.settleThen(300L, mc ->
        {
            this.k.check("«Выделение»: простое — «Сбросить» доступна", true,
                    paneField("reset") instanceof com.aell.layerlist.ui.kit.Button b && b.enabled());
            return this.askReset();
        })));
        s.add(new DumpSchematics.Step(p + "«Выделение»: простое — Enter", 5_000L, this.k.settleThen(300L, mc -> this.pressEnter())));
        s.add(new DumpSchematics.Step(p + "«Выделение»: простое — выделения нет, поля 0", 5_000L,
                this.k.settleThen(300L, mc -> this.simpleResetDone())));
        this.screen(s, p, "material-list", () -> this.placement != null ? new GuiMaterialList(this.placement.getMaterialList()) : null);
        this.screen(s, p, "verifier", () -> this.placement != null ? new GuiSchematicVerifier(this.placement) : null);
        this.screen(s, p, "tasks", GuiTaskManager::new);
        this.screen(s, p, "vcs", GuiSchematicProjectsBrowser::new);
        this.screen(s, p, "placements-1", GuiSchematicPlacementsList::new);
        // Окна malilib центрируются по родителю — сначала открыть список, окно — поверх него
        this.screen(s, p, "dialog-input", () -> new GuiTextInput(64, "litematica.gui.title.rename_schematic", "Магазин",
                GuiUtils.getCurrentScreen(), value -> {}));
        this.screen(s, p, "placements-2", GuiSchematicPlacementsList::new);
        this.screen(s, p, "dialog-confirm", () -> new GuiConfirmAction(260, "litematica.gui.title.confirm_file_deletion",
                new IConfirmationListener()
                {
                    @Override
                    public boolean onActionConfirmed()
                    {
                        return true;
                    }

                    @Override
                    public boolean onActionCancelled()
                    {
                        return true;
                    }
                }, GuiUtils.getCurrentScreen(), "litematica.gui.message.confirm_file_deletion", "Магазин.litematic"));
        s.add(new DumpSchematics.Step(p + "обход: закрыть, перекраска выкл.", 5_000L, mc -> this.finish()));
        return s;
    }

    private void screen(List<DumpSchematics.Step> s, String p, String name, Supplier<@Nullable Screen> make)
    {
        s.add(new DumpSchematics.Step(p + "обход: " + name, 5_000L, mc ->
        {
            Screen screen = make.get();

            if (screen == null)
            {
                this.k.fail("обход: экран " + name + " не открыть — нет размещения или выделения");
                return true;
            }

            Nav.clear();
            GuiBase.openGui(screen);
            return true;
        }));
        s.add(new DumpSchematics.Step(p + "обход: снимок " + name, 5_000L, this.k.settleShot(p + "tour-" + name)));
    }

    private boolean prepare()
    {
        SchematicsConfig.LITEMATICA_SKIN.setBooleanValue(true);
        MainMenuSkin.TestHooks.slow(1.0F);

        if (this.placement != null)
        {
            return true;
        }

        SchematicEntry generated = this.k.generated(".litematic");

        if (generated == null)
        {
            this.k.fail("обход: нет k-generated.litematic — экраны размещения не открыть");
            return true;
        }

        LoadedSchematic loaded = this.k.loaded(generated.path());

        if (loaded == null)
        {
            if (this.loading == null)
            {
                this.loading = SchematicLibrary.get().load(generated);
            }

            if (! this.loading.isDone())
            {
                return false;
            }

            loaded = this.loading.join();
        }

        this.placement = PlacementOps.placeInFront(loaded);
        this.k.ok("обход: размещение для экранов — " + this.placement.getName());
        return true;
    }

    private @Nullable Screen subRegion()
    {
        SchematicPlacement p = this.placement;

        if (p == null)
        {
            return null;
        }

        for (SubRegionPlacement region : p.getAllSubRegionsPlacements())
        {
            return new GuiSubRegionConfiguration(p, region);
        }

        return null;
    }

    private @Nullable Screen areaEditor()
    {
        SelectionManager manager = DataManager.getSelectionManager();
        AreaSelection selection = manager.getCurrentSelection();

        if (selection == null)
        {
            String id = manager.createNewSelection(DataManager.getAreaSelectionsBaseDirectory(), "k-skin");
            manager.setCurrentSelection(id);
            selection = manager.getCurrentSelection();
        }

        return selection != null ? new GuiAreaSelectionEditorNormal(selection) : null;
    }

    private boolean prepareSelection()
    {
        SelectionManager manager = DataManager.getSelectionManager();
        var player = net.minecraft.client.Minecraft.getInstance().player;

        if (player == null)
        {
            this.k.fail("обход: нет игрока для выделения");
            return true;
        }

        if (! manager.hasNormalSelection())
        {
            manager.setCurrentSelection(manager.createNewSelection(DataManager.getAreaSelectionsBaseDirectory(), "k-skin"));
        }

        if (manager.getSelectionMode() == SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();

        if (selection == null)
        {
            this.k.fail("обход: составного выделения нет");
            return true;
        }

        BlockPos at = player.blockPosition();
        this.box(selection, "Основа", at.offset(-6, 0, -4), at.offset(5, 6, 7));
        this.box(selection, "Крыша", at.offset(-6, 7, -4), at.offset(5, 10, 7));
        selection.setSelectedSubRegionBox("Основа");
        selection.setOriginSelected(false);
        selection.setCurrentSelectedCorner(Corner.CORNER_1);
        this.k.ok("обход: выделение «" + selection.getName() + "», компонентов " + selection.getAllSubRegionNames().size());
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

    private boolean openOwnEditor()
    {
        SchematicsConfig.LITEMATICA_OWN_SCREENS.setBooleanValue(true);
        Nav.clear();
        DataManager.getSelectionManager().openEditGui(null);
        this.k.check("«Редактор региона» открыл вкладку «Выделение»", true, GuiUtils.getCurrentScreen() instanceof GuiSchematics);
        return true;
    }

    /** Кнопка панели «Выделение» по пути полей: блок угла → массив кнопок. */
    private static @Nullable Object paneField(String name)
    {
        Object pane = KProbe.fieldNamedType(GuiUtils.getCurrentScreen(), "SelectionPane");

        try
        {
            if (pane == null)
            {
                return null;
            }

            java.lang.reflect.Field field = pane.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(pane);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private boolean nudgeCorner()
    {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();
        Object block = paneField("corner1");

        if (box == null || block == null || ! (GuiUtils.getCurrentScreen() instanceof GuiBase screen))
        {
            this.k.fail("«Выделение»: нет выделения или панели");
            return true;
        }

        int before = box.getPos1().getX();

        try
        {
            java.lang.reflect.Field plus = block.getClass().getDeclaredField("plus");
            plus.setAccessible(true);
            com.aell.layerlist.ui.kit.Widget button = (com.aell.layerlist.ui.kit.Widget) ((Object[]) plus.get(block))[0];
            KProbe.click(screen, button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("«Выделение»: кнопка «+» не читается: " + e);
            return true;
        }

        this.k.check("«Выделение»: X угла 1 после «+»", before + 1, selection.getSelectedSubRegionBox().getPos1().getX());
        return true;
    }

    private boolean addBox()
    {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Object add = paneField("add");

        if (selection == null || ! (add instanceof com.aell.layerlist.ui.kit.Button button)
                || ! (GuiUtils.getCurrentScreen() instanceof GuiBase screen))
        {
            this.k.fail("«Выделение»: нет кнопки «+ Компонент»");
            return true;
        }

        int before = selection.getAllSubRegionNames().size();
        KProbe.click(screen, button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
        this.k.check("«Выделение»: компонентов после «+ Компонент»", before + 1, selection.getAllSubRegionNames().size());
        return true;
    }

    private static @Nullable Object field(@Nullable Object owner, String name)
    {
        if (owner == null)
        {
            return null;
        }

        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (ReflectiveOperationException e)
            {
                // выше по иерархии
            }
        }

        return null;
    }

    private boolean previewReady()
    {
        Object preview = paneField("preview");
        boolean ready = paneField("area") != null && preview instanceof com.aell.layerlist.schematics.preview.PreviewWidget w && w.hasModel();

        if (ready)
        {
            this.k.ok("«Выделение»: превью области построено");
        }

        return ready;
    }

    private boolean pressPaneButton(String name)
    {
        Object button = paneField(name);

        if (button instanceof com.aell.layerlist.ui.kit.Button b && GuiUtils.getCurrentScreen() instanceof GuiBase screen)
        {
            KProbe.click(screen, b.x() + b.w() / 2.0, b.y() + b.h() / 2.0);
        }
        else
        {
            this.k.fail("«Выделение»: нет кнопки " + name);
        }

        return true;
    }

    private boolean pickInPreview()
    {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();
        Object pane = KProbe.fieldNamedType(GuiUtils.getCurrentScreen(), "SelectionPane");

        if (box == null || ! (GuiUtils.getCurrentScreen() instanceof GuiBase screen) || pane == null)
        {
            this.k.fail("«Выделение»: нет компонента для щелчка");
            return true;
        }

        BlockPos before1 = box.getPos1();
        BlockPos before2 = box.getPos2();
        int vx = (int) field(pane, "viewX");
        int vy = (int) field(pane, "viewY");
        int vw = (int) field(pane, "viewW");
        int vh = (int) field(pane, "viewH");
        KProbe.click(screen, vx + vw / 2.0, vy + vh * 0.6);
        Box after = selection.getSelectedSubRegionBox();
        boolean moved = after != null && (! after.getPos1().equals(before1) || ! after.getPos2().equals(before2));
        this.k.check("«Выделение»: щелчок по превью сдвинул угол", true, moved);
        this.k.info("«Выделение»: углы " + before1.toShortString() + " / " + before2.toShortString() + " → "
                + (after == null ? "-" : after.getPos1().toShortString() + " / " + after.getPos2().toShortString()));
        return true;
    }

    private boolean onMaterials()
    {
        Object tab = field(GuiUtils.getCurrentScreen(), "tab");
        boolean done = tab != null && "MATERIALS".equals(tab.toString());

        if (done)
        {
            this.k.ok("«Выделение»: анализ открыл «Материалы»");
        }

        return done;
    }

    private boolean reroute()
    {
        Nav.clear();
        GuiBase.openGui(new GuiSchematicPlacementsList());
        Object tab = field(GuiUtils.getCurrentScreen(), "tab");
        this.k.check("меню: «Размещённые голограммы» открыли «Размещения»", "PLACEMENTS", String.valueOf(tab));
        GuiSchematics.openSelection(null);
        return true;
    }

    private boolean askReset()
    {
        return this.pressPaneButton("reset");
    }

    private java.nio.file.Path saved;

    private boolean pressEnter()
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase screen)
        {
            KProbe.press(screen, com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
        }

        return true;
    }

    private boolean savedShown()
    {
        Object tab = field(GuiUtils.getCurrentScreen(), "tab");

        if (! "LIBRARY".equals(String.valueOf(tab)))
        {
            return false;
        }

        Object selected = field(GuiUtils.getCurrentScreen(), "selected");
        java.nio.file.Path path = selected instanceof com.aell.layerlist.schematics.library.SchematicEntry e ? e.path() : null;
        this.saved = path;
        this.k.check("«Сохранить»: выбрана новая схема", true, path != null && java.nio.file.Files.exists(path)
                && path.getFileName().toString().endsWith(".litematic"));
        Object files = field(GuiUtils.getCurrentScreen(), "files");
        Object list = field(files, "list");
        Object first = null;

        try
        {
            java.util.List<?> items = (java.util.List<?>) list.getClass().getMethod("items").invoke(list);
            first = items.stream().filter(i -> String.valueOf(i).startsWith("Item[kind=FILE") || String.valueOf(i).startsWith("Item[kind=FOLDER"))
                    .findFirst().orElse(null);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("«Сохранить»: список библиотеки — " + e);
        }

        this.k.check("«Сохранить»: новая схема первой в списке", true, first != null && path != null && String.valueOf(first).contains(path.toString()));
        return true;
    }

    private boolean dropSaved()
    {
        if (this.saved != null)
        {
            try
            {
                java.nio.file.Files.deleteIfExists(this.saved);
            }
            catch (java.io.IOException e)
            {
                this.k.fail("«Сохранить»: не удалить " + this.saved);
            }
        }

        GuiSchematics.openSelection(null);
        return true;
    }

    private boolean simpleWithCorners()
    {
        SelectionManager manager = DataManager.getSelectionManager();

        if (manager.getSelectionMode() != SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();
        var player = net.minecraft.client.Minecraft.getInstance().player;

        if (selection == null || player == null || selection.getSelectedSubRegionBox() == null)
        {
            this.k.fail("«Выделение»: нет простого выделения");
            return true;
        }

        Box box = selection.getSelectedSubRegionBox();
        selection.setSubRegionCornerPos(box, Corner.CORNER_1, player.blockPosition().offset(2, 0, 2));
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, player.blockPosition().offset(5, 3, 5));
        return true;
    }

    private boolean simpleResetDone()
    {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        Box box = selection == null ? null : selection.getSelectedSubRegionBox();
        this.k.check("«Выделение»: простое — углов нет", true, box != null && box.getPos1() == null && box.getPos2() == null);
        Object corner1 = paneField("corner1");
        Object fields = corner1 == null ? null : field(corner1, "fields");
        String x = fields instanceof Object[] a && a.length > 0 && a[0] instanceof com.aell.layerlist.ui.kit.TextInput t ? t.text() : "?";
        this.k.check("«Выделение»: простое — поле X угла 1", "0", x);
        DataManager.getSelectionManager().switchSelectionMode();
        return true;
    }

    private boolean confirmReset()
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase screen)
        {
            KProbe.press(screen, com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
        }

        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        this.k.check("«Выделение»: после «Сбросить» компонентов", 0, selection == null ? -1 : selection.getAllSubRegionNames().size());
        return true;
    }

    private boolean finish()
    {
        GuiBase.openGui(null);

        if (this.placement != null)
        {
            PlacementOps.remove(this.placement);
            this.placement = null;
        }

        SchematicsConfig.LITEMATICA_SKIN.setBooleanValue(false);
        SchematicsConfig.LITEMATICA_OWN_SCREENS.setBooleanValue(false);
        return true;
    }
}
