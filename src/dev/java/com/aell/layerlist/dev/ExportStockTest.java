package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.ExportFormat;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.FormRow;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code exportstock} (24.11.2026): окно выгрузки и выбор схемы во вкладке «Материалы».
 * <ol>
 *   <li>Переключатель «Всё / Только недостающее / С учётом склада»: третий вариант вычитает инвентарь.</li>
 *   <li>«Считать до Y» в окне: вкладка «Материалы» пересчитывает строки, окно забирает новые; выключили — как было.</li>
 *   <li>То же у источника «Список HUD» (окно, открытое без схемы).</li>
 *   <li>Баг: размещение показанной схемы выключили, включённым осталось другое — «Материалы» идут за включённым;
 *   кнопка «Схема» не подставляет выключенное размещение, выбранное в Litematica; явно открытое выключенное —
 *   остаётся.</li>
 * </ol>
 * Клики по сегментам и тумблеру — настоящие ({@link SupportTest#click}).
 */
@Environment(EnvType.CLIENT)
public final class ExportStockTest implements CovJob
{
    private static final String TAG = "[exportstock] ";
    private static final BlockPos REF = new BlockPos(1700, 100, 0);
    private static final BlockPos GLASS_REF = new BlockPos(1706, 100, 0);
    private static final BlockPos P1 = new BlockPos(1700, 100, 14);
    private static final BlockPos P2 = new BlockPos(1706, 100, 14);
    private static final String STONE = "minecraft:stone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String GLASS = "minecraft:glass";
    private static final String TOWER = "Башня";
    private static final String WINDOW = "Витрина";
    private static final int CUTOFF_Y = 102;

    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean cutoffWas;
    private int cutoffYWas;
    private boolean stockWas;
    private @Nullable SchematicPlacement tower;
    private @Nullable SchematicPlacement glass;

    @Override
    public String name()
    {
        return "exportstock (выгрузка: склад и «Считать до Y»; материалы выключенной схемы)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        forgetChests(mc);
        this.cutoffWas = LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue();
        this.cutoffYWas = LayerListConfigs.MATERIAL_CUTOFF_Y.getIntegerValue();
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(false);
        LayerListConfigs.MATERIAL_CUTOFF_Y.setIntegerValue(CUTOFF_Y);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(true);
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
        LayerListConfigs.STOCK_NEAR_ONLY.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1690 -10 1730 40");
            command(server, fill(REF, -4, -1, -4, 24, -1, 30, "minecraft:smooth_stone"));
            command(server, fill(REF, -4, 0, -4, 24, 8, 30, "minecraft:air"));
            // Башня: три камня и две доски сверху — граница Y = 102 отсекает доски
            command(server, fill(REF, 0, 0, 0, 0, 2, 0, STONE));
            command(server, fill(REF, 0, 3, 0, 0, 4, 0, PLANKS));
            command(server, fill(GLASS_REF, 0, 0, 0, 3, 0, 0, GLASS));
            command(server, "tp @a " + (REF.getX() + 3.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3500L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                LitematicaSchematic towerSchematic = schematic(mc, REF, REF.offset(0, 4, 0), "dev-export-tower");
                LitematicaSchematic glassSchematic = schematic(mc, GLASS_REF, GLASS_REF.offset(3, 0, 0), "dev-export-glass");
                this.tower = place(towerSchematic, P1, TOWER);
                this.glass = place(glassSchematic, P2, WINDOW);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "give @a " + STONE + " 2");
                    command(server, "tp @a " + (P1.getX() + 3.5D) + " " + P1.getY() + " " + (P1.getZ() + 8.5D) + " 180 20");
                });
                GuiSchematics.openPlacements(null);
                wait = 2500L;
            }
            case 1 ->
            {
                this.invoke(this.gui(mc), "showMaterials", null, this.tower);
                wait = 3500L;
            }
            // ===== Окно выгрузки из «Материалов» =====
            case 2 ->
            {
                Object pane = this.materials(mc);
                this.check("таблица: источник — «" + TOWER + "»", this.label(pane).contains(TOWER), this.label(pane));
                this.check("таблица: камень 3, доски 2", this.total(pane, STONE) == 3L && this.total(pane, PLANKS) == 2L,
                        this.total(pane, STONE) + " / " + this.total(pane, PLANKS));
                this.invoke(pane, "export", ExportFormat.COPY);
                wait = 1000L;
            }
            case 3 ->
            {
                Modal window = this.window(mc);
                List<Button> segments = segments(window);
                this.check("окно выгрузки открыто", window != null, "");
                this.check("у переключателя три варианта", segments.size() == 3, "" + segments.size());
                this.check("строка «Считать до Y» есть", this.cutoff(window) != null && this.cutoff(window).visible(), "");
                this.stats(window, "«Всё»: 2 поз., 5 бл.", 2, 5L);

                if (segments.size() == 3)
                {
                    click(mc, segments.get(2));
                }

                wait = 700L;
            }
            case 4 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "«С учётом склада»: камень 3 − 2 в инвентаре, доски 2 → 2 поз., 3 бл.", 2, 3L);
                String table = this.tsv(window);
                CovOut.log(TAG + "таблица «С учётом склада»:\n" + table);
                this.check("в таблице «Копировать» у камня «Не хватает» = 1 (3 − 2 в инвентаре)",
                        table.contains("\t2\t0\t1\t" + STONE), "");
                shot(mc, "exportstock-1-stock");
                click(mc, this.cutoff(window));
                wait = 4000L;
            }
            case 5 ->
            {
                Modal window = this.window(mc);
                this.check("«Считать до Y» включилась", LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue(), "");
                this.stats(window, "до Y = 102 и склад: остался камень 1 → 1 поз., 1 бл.", 1, 1L);
                ExportRequest request = this.request(window);
                this.check("источник в шапке таблицы — с границей высоты", request != null && request.source().contains("Y = " + CUTOFF_Y),
                        request == null ? "строк нет" : request.source());
                shot(mc, "exportstock-2-cutoff");
                click(mc, segments(window).get(0));
                wait = 700L;
            }
            case 6 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "до Y = 102, «Всё»: камень 3 → 1 поз., 3 бл.", 1, 3L);
                click(mc, this.cutoff(window));
                wait = 4000L;
            }
            case 7 ->
            {
                Modal window = this.window(mc);
                this.check("«Считать до Y» выключилась", ! LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue(), "");
                this.stats(window, "без границы, «Всё»: снова 2 поз., 5 бл.", 2, 5L);

                if (window != null)
                {
                    window.cancel();
                }

                wait = 800L;
            }
            // ===== Баг: материалы выключенной схемы =====
            case 8 ->
            {
                Object pane = this.materials(mc);
                this.check("после окна таблица та же: камень 3, доски 2", this.total(pane, STONE) == 3L && this.total(pane, PLANKS) == 2L,
                        this.total(pane, STONE) + " / " + this.total(pane, PLANKS));
                this.tab(mc, "PLACEMENTS");
                this.invoke(this.gui(mc), "toggleEnabled", this.tower);
                wait = 800L;
            }
            case 9 ->
            {
                this.check("размещение «" + TOWER + "» выключено", this.tower != null && ! this.tower.isEnabled(), "");
                this.tab(mc, "MATERIALS");
                wait = 3500L;
            }
            case 10 ->
            {
                Object pane = this.materials(mc);
                String label = this.label(pane);
                this.check("выключили показанную схему — таблица ушла на включённую «" + WINDOW + "»", label.contains(WINDOW), label);
                this.check("в таблице стекло 4 и нет камня", this.total(pane, GLASS) == 4L && this.total(pane, STONE) == 0L,
                        this.total(pane, GLASS) + " / " + this.total(pane, STONE));
                shot(mc, "exportstock-3-enabled-only");
                // Явный выбор выключенного размещения (меню «Материалы» строки) остаётся
                this.invoke(this.gui(mc), "showMaterials", null, this.tower);
                wait = 3000L;
            }
            case 11 ->
            {
                this.check("явно открытая выключенная схема показана", this.label(this.materials(mc)).contains(TOWER),
                        this.label(this.materials(mc)));
                this.tab(mc, "PLACEMENTS");
                wait = 600L;
            }
            case 12 ->
            {
                this.tab(mc, "MATERIALS");
                wait = 1500L;
            }
            case 13 ->
            {
                this.check("…и после возврата на вкладку остаётся", this.label(this.materials(mc)).contains(TOWER),
                        this.label(this.materials(mc)));
                mc.setScreen(null);
                // Выключенное размещение — выбранное в Litematica: раньше кнопка «Схема» брала его
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.tower);
                GuiSchematics.openPlacements(null);
                wait = 1500L;
            }
            case 14 ->
            {
                this.tab(mc, "MATERIALS");
                wait = 3000L;
            }
            case 15 ->
            {
                Object pane = this.materials(mc);
                CovOut.log(TAG + "новый экран, вкладка «Материалы»: «" + this.label(pane) + "»");
                this.check("новый экран: в таблице нет камня выключенной схемы", this.total(pane, STONE) == 0L && this.total(pane, GLASS) == 4L,
                        this.total(pane, STONE) + " / " + this.total(pane, GLASS));
                this.invoke(pane, "showSchematic");
                wait = 3000L;
            }
            case 16 ->
            {
                Object pane = this.materials(mc);
                String label = this.label(pane);
                this.check("кнопка «Схема» взяла включённую «" + WINDOW + "», а не выбранную выключенную", label.contains(WINDOW), label);
                this.check("в таблице стекло 4 и нет камня", this.total(pane, GLASS) == 4L && this.total(pane, STONE) == 0L,
                        this.total(pane, GLASS) + " / " + this.total(pane, STONE));
                shot(mc, "exportstock-4-schematic-button");
                // ===== Окно выгрузки «Список HUD» =====
                this.invoke(this.gui(mc), "toggleEnabled", this.tower);
                wait = 4000L;
            }
            case 17 ->
            {
                this.invoke(this.gui(mc), "openExport", new Object[]{null});
                wait = 2500L;
            }
            case 18 ->
            {
                Modal window = this.window(mc);
                this.check("окно выгрузки «Список HUD» открыто", window != null, "");
                click(mc, segments(window).get(1));
                wait = 700L;
            }
            case 19 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "HUD, «Только недостающее»: камень 3, доски 2, стекло 4 → 3 поз., 9 бл.", 3, 9L);
                click(mc, this.cutoff(window));
                wait = 4500L;
            }
            case 20 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "HUD до Y = 102: камень 3, стекло 4 → 2 поз., 7 бл.", 2, 7L);
                click(mc, segments(window).get(2));
                wait = 700L;
            }
            case 21 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "HUD до Y = 102 и склад: камень 1, стекло 4 → 2 поз., 5 бл.", 2, 5L);
                CovOut.log(TAG + "таблица HUD:\n" + this.tsv(window));
                shot(mc, "exportstock-5-hud");
                click(mc, this.cutoff(window));
                wait = 4500L;
            }
            case 22 ->
            {
                Modal window = this.window(mc);
                this.stats(window, "HUD без границы и склад: камень 1, доски 2, стекло 4 → 3 поз., 7 бл.", 3, 7L);

                if (window != null)
                {
                    window.cancel();
                }

                wait = 600L;
            }
            default ->
            {
                mc.setScreen(null);
                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    // ===== Окно выгрузки =====

    private @Nullable Modal window(Minecraft mc)
    {
        Layers layers = KProbe.fieldOfType(this.gui(mc), Layers.class);

        return layers != null && layers.top() instanceof Modal modal && modal.getClass().getSimpleName().equals("ExportWindow")
                ? modal : null;
    }

    /** Кнопки переключателя «Всё / Только недостающее / С учётом склада». */
    private static List<Button> segments(@Nullable Modal window)
    {
        List<?> rows = KTrash.field(window, "rows");

        if (rows != null)
        {
            for (Object row : rows)
            {
                if (KProbe.recordComponent(row, Widget.class) instanceof FormRow form
                        && form.control().getClass().getSimpleName().equals("Segmented"))
                {
                    List<Button> buttons = KTrash.field(form.control(), "buttons");
                    return buttons == null ? List.of() : buttons;
                }
            }
        }

        return List.of();
    }

    private @Nullable Toggle cutoff(@Nullable Modal window)
    {
        return KTrash.field(window, "cutoff");
    }

    private @Nullable ExportRequest request(@Nullable Modal window)
    {
        try
        {
            return window == null ? null : (ExportRequest) KProbe.call(window, "request");
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "request: " + e);
            return null;
        }
    }

    /** Позиции и блоки — по таблице, которую окно выгрузит прямо сейчас (а не по его подписи). */
    private void stats(@Nullable Modal window, String what, int positions, long blocks)
    {
        ExportRequest request = this.request(window);

        if (request == null)
        {
            this.check(what, false, "строки ещё считаются");
            return;
        }

        int gotPositions = 0;
        long gotBlocks = 0L;

        for (var row : request.rows())
        {
            long quantity = request.options().quantity(row);

            if (quantity > 0L)
            {
                ++gotPositions;
                gotBlocks += quantity;
            }
        }

        Integer shownPositions = KTrash.field(window, "statsPositions");
        Long shownBlocks = KTrash.field(window, "statsBlocks");
        boolean ok = gotPositions == positions && gotBlocks == blocks
                && shownPositions != null && shownPositions == positions && shownBlocks != null && shownBlocks == blocks;
        this.check(what, ok, "выгрузка " + gotPositions + " поз. / " + gotBlocks + " бл., в окне " + shownPositions + " / " + shownBlocks);
    }

    private String tsv(@Nullable Modal window)
    {
        ExportRequest request = this.request(window);

        if (request == null)
        {
            return "(строк нет)";
        }

        ExportResult result = Exporters.tsv(request);

        return result.ok() && result.clipboard() != null ? result.clipboard() : "(не собралась)";
    }

    private static void click(Minecraft mc, @Nullable Widget widget)
    {
        if (widget == null)
        {
            CovOut.log(TAG + "щёлкать не по чему");
            return;
        }

        SupportTest.click(mc, widget.x() + Math.min(8.0D, widget.w() / 2.0D), widget.y() + widget.h() / 2.0D);
        // Курсор — в сторону: подсказка под ним закрывала бы строки окна на снимке
        SupportTest.moveMouse(mc, 3.0D, 3.0D);
    }

    // ===== Экран «Схемы» =====

    private @Nullable GuiSchematics gui(Minecraft mc)
    {
        return mc.screen instanceof GuiSchematics gui ? gui : null;
    }

    private @Nullable Object materials(Minecraft mc)
    {
        return KProbe.fieldNamedType(this.gui(mc), "MaterialsPane");
    }

    private void tab(Minecraft mc, String name)
    {
        try
        {
            this.invoke(this.gui(mc), "setTab", KProbe.enumConstant("com.aell.layerlist.schematics.gui.GuiSchematics$Tab", name));
        }
        catch (ReflectiveOperationException e)
        {
            this.check("вкладка " + name, false, e.toString());
        }
    }

    private void invoke(@Nullable Object target, String method, Object... args)
    {
        if (target == null)
        {
            this.check(method, false, "экран закрыт");
            return;
        }

        try
        {
            KProbe.call(target, method, args);
        }
        catch (ReflectiveOperationException e)
        {
            this.check(method, false, String.valueOf(e.getCause() != null ? e.getCause() : e));
        }
    }

    private String label(@Nullable Object pane)
    {
        try
        {
            return pane == null ? "" : String.valueOf(KProbe.call(pane, "sourceLabel"));
        }
        catch (ReflectiveOperationException e)
        {
            return e.toString();
        }
    }

    /** «Всего» строки предмета в таблице «Материалов»; 0 — строки нет. */
    private long total(@Nullable Object pane, String itemId)
    {
        List<?> lines = KTrash.field(pane, "lines");

        if (lines != null)
        {
            for (Object line : lines)
            {
                MaterialLine l = (MaterialLine) line;

                if (StorageIndex.itemId(l.stack()).equals(itemId))
                {
                    return l.need();
                }
            }
        }

        return 0L;
    }

    // ===== Схемы и мир =====

    private static LitematicaSchematic schematic(Minecraft mc, BlockPos from, BlockPos to, String name)
    {
        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(from, to, "main"), false);
        area.setExplicitOrigin(from);
        return LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                "dev", CovOut::log);
    }

    private static SchematicPlacement place(LitematicaSchematic schematic, BlockPos at, String name)
    {
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, at, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    @SuppressWarnings("unchecked")
    private static void forgetChests(Minecraft mc)
    {
        String scope = StorageIndex.scope(mc);
        java.util.Map<String, StorageIndex.Rec> records = KTrash.field(StorageIndex.getInstance(), "records");

        if (records != null)
        {
            int before = records.size();
            records.values().removeIf(rec -> scope.equals(rec.scope));
            StorageIndex.getInstance().touch();
            CovOut.log(TAG + "индекс: забыто своих записей этого мира " + (before - records.size()));
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(TAG + "снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log(TAG + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(this.cutoffWas);
        LayerListConfigs.MATERIAL_CUTOFF_Y.setIntegerValue(this.cutoffYWas);
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);

        for (SchematicPlacement placement : new SchematicPlacement[]{this.tower, this.glass})
        {
            if (placement != null)
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            }
        }
    }
}
