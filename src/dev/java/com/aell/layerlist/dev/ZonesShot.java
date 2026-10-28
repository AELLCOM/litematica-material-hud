package com.aell.layerlist.dev;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;

/**
 * Режим {@code l}: снимки экрана «Управление зонами» Zone Focus в двух вариантах (кит aell-ui и
 * нынешние виджеты ZF). Нужен jar Zone Focus с {@code gui.manage} в {@code run/mods}; всё — рефлексией.
 */
@Environment(EnvType.CLIENT)
public final class ZonesShot implements CovJob
{
    private int stage;
    private long nextAt;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "L (снимки «Управления зонами»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null || mc.player == null)
        {
            CovOut.log("[zonesshot] нет схемы " + path);
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        this.placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(4, 0, 4), "Портал", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        this.nextAt = Util.getMillis() + 4000L;
        return true;
    }

    private static Object zones() throws ReflectiveOperationException
    {
        Class<?> c = Class.forName("com.aell.zonefocus.zone.ZoneManager");
        return c.getMethod("getInstance").invoke(null);
    }

    private Screen open(String cls) throws ReflectiveOperationException
    {
        return (Screen) Class.forName(cls).getConstructor(Screen.class, SchematicPlacement.class).newInstance(null, this.placement);
    }

    private static void progress(boolean on) throws ReflectiveOperationException
    {
        Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("showProgress").setBoolean(null, on);
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        this.nextAt = now + 2500L;

        try
        {
            switch (this.stage++)
            {
                case 0 ->
                {
                    Object m = zones();
                    m.getClass().getMethod("splitGrid", SchematicPlacement.class, int.class, int.class, int.class)
                            .invoke(m, this.placement, 5, 1, 3);
                    m.getClass().getMethod("setActiveIndex", int.class).invoke(m, 7);
                    Class.forName("com.aell.zonefocus.zone.ZoneScanner").getMethod("getInstance").invoke(null).getClass()
                            .getMethod("requestScan").invoke(Class.forName("com.aell.zonefocus.zone.ZoneScanner").getMethod("getInstance").invoke(null));
                    this.nextAt = now + 6000L;
                }
                case 1 ->
                {
                    progress(false);
                    mc.setScreen(this.open("com.aell.zonefocus.gui.manage.GuiZoneManager"));
                }
                case 2 -> shot(mc, "вариант А — кит aell-ui");
                case 3 -> progress(true);
                case 4 -> shot(mc, "вариант А — с прогрессом");
                case 5 -> mc.setScreen(this.open("com.aell.zonefocus.gui.manage.GuiZoneManagerClassic"));
                case 6 -> shot(mc, "вариант Б — виджеты Zone Focus, с прогрессом");
                case 7 ->
                {
                    progress(false);
                    mc.setScreen(this.open("com.aell.zonefocus.gui.manage.GuiZoneManagerClassic"));
                }
                case 8 -> shot(mc, "вариант Б — без прогресса");
                default ->
                {
                    mc.setScreen(null);
                    CovOut.log("[zonesshot] итог: OK");
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log("[zonesshot] сбой: " + e);
            mc.setScreen(null);
            return true;
        }

        return false;
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[zonesshot] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
