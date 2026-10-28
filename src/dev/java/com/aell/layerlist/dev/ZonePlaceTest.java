package com.aell.layerlist.dev;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.GuiUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Режим {@code zoneplace}: схема, разбитая на зоны, в библиотеке (запрос 17.10.2026). Нужен Zone Focus 0.0.8+ в
 * {@code run/mods} и «Портал.litematic» в {@code run/schematics}.
 * <ol>
 *   <li>у Zone Focus уже есть зоны чужого размещения (склад 2×1×1) — сам он новой схеме зоны не раздаст;</li>
 *   <li>«Портал» разбит 2×1×2: «Разместить здесь» и «Разместить…» — цветом зон и пунктиром (снимок);</li>
 *   <li>«Разместить здесь» — новое размещение сразу в 4 зонах, даже при чужих зонах (снимок редактора).</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class ZonePlaceTest implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private Path file;
    private Path other;

    @Override
    public String name()
    {
        return "ZONEPLACE (зоны схемы при размещении)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (! ZoneFocusFiles.available())
        {
            CovOut.log("[zoneplace] нет Zone Focus 0.0.8+ в run/mods");
            return false;
        }

        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        this.file = dir.resolve("Портал.litematic").toAbsolutePath().normalize();
        this.other = dir.resolve("_k").resolve("склад.litematic").toAbsolutePath().normalize();
        clearPlacements();
        ZoneFocusFiles.clear(this.file);
        ZoneFocusFiles.clear(this.other);
        this.nextAt = Util.getMillis() + 2000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.nextAt)
        {
            return false;
        }

        long wait = 2500L;

        try
        {
            switch (this.stage)
            {
                case 0 ->
                {
                    // Чужое размещение с зонами: Zone Focus занят им
                    LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(this.other);
                    SchematicPlacement p = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(-40, 0, -40),
                            "zoneplace-other", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(p, false);
                    ZoneFocusFiles.split(this.other, 2, 1, 1);
                    ZoneFocusFiles.split(this.file, 2, 1, 2);
                }
                case 1 ->
                {
                    this.check("у Zone Focus зоны чужого размещения", "zoneplace-other".equals(ZoneFocusFiles.placementName()),
                            String.valueOf(ZoneFocusFiles.placementName()));
                    GuiSchematics.open(null);
                    wait = 1500L;
                }
                case 2 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    SchematicEntry entry = SchematicLibrary.get().entry(this.file);
                    this.check("библиотека открыта, файл найден", screen instanceof GuiSchematics && entry != null,
                            screen + " / " + entry);
                    Method select = GuiSchematics.class.getDeclaredMethod("select", SchematicEntry.class);
                    select.setAccessible(true);
                    select.invoke(screen, entry);
                    wait = 3000L;
                }
                case 3 ->
                {
                    Object info = field(GuiUtils.getCurrentScreen(), "info");
                    Object here = field(info, "placeHere");
                    Object at = field(info, "placeAt");
                    this.check("разбита — «Разместить здесь» пунктиром", (boolean) field(here, "dashed"), "");
                    this.check("разбита — «Разместить…» пунктиром", (boolean) field(at, "dashed"), "");
                    int accent = (int) field(here, "accent");
                    this.check("разбита — цвет зон", accent == com.aell.layerlist.schematics.preview.ZoneOverlay.ACTIVE,
                            Integer.toHexString(accent));
                    shot(mc, "библиотека — схема с зонами, кнопки пунктиром");
                    Method place = GuiSchematics.class.getDeclaredMethod("place", SchematicEntry.class, boolean.class);
                    place.setAccessible(true);
                    place.invoke(GuiUtils.getCurrentScreen(), SchematicLibrary.get().entry(this.file), true);
                    wait = 3500L;
                }
                case 4 ->
                {
                    int[] summary = ZoneFocusFiles.summary();
                    String name = ZoneFocusFiles.placementName();
                    this.check("после размещения зоны на «Портале»", name != null && ! name.equals("zoneplace-other"),
                            String.valueOf(name));
                    this.check("после размещения 4 зоны", summary != null && summary[0] == 4, Arrays.toString(summary));
                    shot(mc, "редактор — размещение сразу в зонах");
                    mc.gui.setScreen(null);
                    wait = 1000L;
                }
                default ->
                {
                    CovOut.log("[zoneplace] " + (this.failed ? "ЕСТЬ ОШИБКИ" : "всё прошло"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            return true;
        }

        ++this.stage;
        this.nextAt = Util.getMillis() + wait;
        return false;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    private static void clearPlacements()
    {
        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Class<?> c = owner.getClass();

        while (c != null)
        {
            try
            {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
        }

        throw new NoSuchFieldException(name);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[zoneplace] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[zoneplace] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
        clearPlacements();
        ZoneFocusFiles.clear(this.file);
        ZoneFocusFiles.clear(this.other);
    }
}
