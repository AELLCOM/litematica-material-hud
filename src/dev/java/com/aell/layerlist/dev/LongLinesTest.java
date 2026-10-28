package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
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

/**
 * Режим {@code longlines} (24.10.2026, правило «необязательная длинная строка бежит, а не раздувает плитку»):
 * размещение с длинным именем, HUD в Обычном мире, потом игрок в Незере — HUD показывает закреплённый снимок со
 * строкой «Снимок из …: &lt;имя&gt; — …». Ожидание: ширина панели в Незере не больше, чем в Обычном мире (строка
 * снимка бежит внутри панели), снимки — в начале круга и на ходу. Потом второе размещение, ни одно не выбрано —
 * во вкладке «Материалы» кнопка «Схема» открывает меню выбора: меню не шире {@code Ui.LONG_TEXT_W} + поля, длинное
 * имя без наведения с многоточием, под курсором бежит.
 */
@Environment(EnvType.CLIENT)
public final class LongLinesTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(760, 100, 0);
    private static final BlockPos TARGET = new BlockPos(760, 100, 14);
    /** Запас под галочку выбранного пункта (Glyphs.CHECK + PAD_IN), с избытком. */
    private static final int CHECK_ROOM = 16;
    private static final String NAME = "Тюрьма, почистили тимофею — левое крыло, второй этаж, решётки и капельник";

    private int stage;
    private long nextAt;
    private boolean failed;
    private int liveWidth;
    private SchematicPlacement placement;
    private SchematicPlacement second;

    @Override
    public String name()
    {
        return "longlines (длинная необязательная строка бежит, панель не шире)";
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
        LayerListConfigs.PIN_ACROSS_DIMENSIONS.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 750 -10 780 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 9, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 5, 9, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 0, 2, 0, 0, "minecraft:stone"));
            command(server, fill(REF, 0, 0, 1, 1, 0, 1, "minecraft:andesite"));
            command(server, fill(REF, 0, 0, 2, 0, 0, 2, "minecraft:iron_bars"));
            command(server, "tp @a " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
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
        LayerCountManager hud = LayerCountManager.getInstance();
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-long");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 0, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, NAME, true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " "
                        + (TARGET.getZ() + 6.5D) + " 180 30"));
                wait = 4000L;
            }
            case 1 ->
            {
                this.liveWidth = LayerHudRenderer.getPanelScreenSize()[0];
                CovOut.log("[long] Обычный мир: строк " + hud.getEntries().size() + ", ширина панели " + this.liveWidth);
                this.check("Обычный мир: в HUD есть строки", ! hud.getEntries().isEmpty(), "" + hud.getEntries().size());
                shot(mc, "long-1-overworld");
                server.execute(() -> {
                    command(server, "execute in minecraft:the_nether run forceload add -16 -16 16 16");
                    command(server, "execute in minecraft:the_nether run fill -3 99 -3 3 99 3 minecraft:stone");
                    command(server, "execute in minecraft:the_nether run fill -3 100 -3 3 103 3 minecraft:air");
                    command(server, "execute in minecraft:the_nether run tp @a 0.5 100 0.5 180 30");
                });
                wait = 6000L;
            }
            case 2 ->
            {
                int width = LayerHudRenderer.getPanelScreenSize()[0];
                CovOut.log("[long] Незер: снимок " + hud.isShowingPinned() + " («" + hud.getPinnedPlacements()
                        + "»), ширина панели " + width + " (в Обычном мире " + this.liveWidth + ")");
                this.check("Незер: показан закреплённый снимок", hud.isShowingPinned(), "");
                this.check("Незер: в снимке длинное имя размещения", hud.getPinnedPlacements().contains(NAME),
                        hud.getPinnedPlacements());
                this.check("Незер: панель не шире, чем в Обычном мире", hud.isShowingPinned() && width <= this.liveWidth + 2,
                        width + " при " + this.liveWidth + (hud.isShowingPinned() ? "" : ", снимка нет — не проверено"));
                shot(mc, "long-2-nether-pause");
                wait = 4000L;
            }
            case 3 ->
            {
                shot(mc, "long-3-nether-running");
                wait = 3000L;
            }
            case 4 ->
            {
                shot(mc, "long-4-nether-running-2");
                server.execute(() -> command(server, "execute in minecraft:overworld run tp @a "
                        + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 6.5D) + " 180 30"));
                wait = 4000L;
            }
            case 5 ->
            {
                // Смена измерения перезагрузила размещения Litematica — оба заводим заново
                for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
                {
                    DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
                }

                LitematicaSchematic schematic = this.placement.getSchematic();
                this.placement = SchematicPlacement.createFor(schematic, TARGET, NAME, true, true);
                this.second = SchematicPlacement.createFor(schematic, TARGET.offset(5, 0, 0), "Склад", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.second, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(null);
                lastTab("MATERIALS");
                GuiSchematics.open(null);
                wait = 2500L;
            }
            case 6 ->
            {
                Widget button = (Widget) field(pane(mc), "schematicButton");
                this.check("«Материалы»: кнопка «Схема» есть", button != null, "");

                if (button != null)
                {
                    SupportTest.moveMouse(mc, 4, 4);
                    SupportTest.click(mc, button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
                    // Курсор с кнопки — иначе её подсказка закроет меню на снимке
                    SupportTest.moveMouse(mc, mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() - 60.0);
                }

                wait = 1500L;
            }
            case 7 ->
            {
                ContextMenu menu = this.menu(mc);
                this.check("щелчок по «Схема» открыл меню выбора", menu != null, "");

                if (menu != null)
                {
                    int menuW = (Integer) field(menu, "w");
                    int nameW = mc.font.width(NAME);
                    int limit = Ui.LONG_TEXT_W + Ui.HEAD_BTN_PAD_X * 2 + CHECK_ROOM;
                    CovOut.log("[long] меню: ширина " + menuW + ", имя " + nameW + ", предел " + limit);
                    this.check("меню не шире предела (имя не раздувает)", menuW <= limit && menuW < nameW,
                            menuW + " (имя " + nameW + ")");
                    shot(mc, "long-5-menu-ellipsis");
                    int row = this.longRow(menu);
                    int menuX = (Integer) field(menu, "x");
                    int menuY = (Integer) field(menu, "y");
                    SupportTest.moveMouse(mc, menuX + menuW / 2.0, menuY + Ui.PAD_MICRO + row * Ui.HEAD_BTN_H
                            + Ui.HEAD_BTN_H / 2.0);
                }

                wait = 4500L;
            }
            case 8 ->
            {
                shot(mc, "long-6-menu-hover-running");
                wait = 2500L;
            }
            case 9 ->
            {
                shot(mc, "long-7-menu-hover-running-2");
                mc.setScreen(null);
                wait = 800L;
            }
            default ->
            {
                CovOut.log("[long] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private ContextMenu menu(Minecraft mc)
    {
        Layers layers = mc.screen instanceof GuiSchematics gui ? (Layers) field(gui, "layers") : null;
        return layers != null && layers.top() instanceof ContextMenu menu ? menu : null;
    }

    /** Номер строки меню с длинным именем (разделителей в этом меню нет). */
    @SuppressWarnings("unchecked")
    private int longRow(ContextMenu menu)
    {
        List<ContextMenu.Item> items = (List<ContextMenu.Item>) field(menu, "items");

        for (int i = 0; items != null && i < items.size(); ++i)
        {
            if (items.get(i).label().contains("Тюрьма"))
            {
                return i;
            }
        }

        this.check("в меню есть строка с длинным именем", false, String.valueOf(items));
        return 0;
    }

    private static Object pane(Minecraft mc)
    {
        return mc.screen instanceof GuiSchematics gui ? KProbe.fieldNamedType(gui, "MaterialsPane") : null;
    }

    private static Object field(Object owner, String name)
    {
        if (owner == null)
        {
            return null;
        }

        try
        {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[long] нет поля " + name + ": " + e);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void lastTab(String tab)
    {
        try
        {
            Class<? extends Enum> tabs = (Class<? extends Enum>) Class.forName(GuiSchematics.class.getName() + "$Tab");
            Field field = GuiSchematics.class.getDeclaredField("lastTab");
            field.setAccessible(true);
            field.set(null, Enum.valueOf(tabs, tab));
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[long] вкладку не выбрать: " + e);
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[long] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[long] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[long] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        for (SchematicPlacement p : new SchematicPlacement[]{this.placement, this.second})
        {
            if (p != null)
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
            }
        }
    }
}
