package com.aell.layerlist.dev;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import fi.dy.masa.malilib.util.GuiUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Режим {@code nozf}: без Zone Focus библиотека — как раньше (вопрос пользователя 09.10.2026). Zone Focus в
 * {@code run/mods} быть не должно. Настоящее меню файла (то же {@code openMenu}, что по ПКМ) — без пунктов
 * про зоны; панели разбиения и кнопки «Зоны» на превью нет, каркасов нет.
 */
@Environment(EnvType.CLIENT)
public final class ZoneNoZfTest implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private Path file;

    @Override
    public String name()
    {
        return "NOZF (библиотека без Zone Focus)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        this.file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic").toAbsolutePath().normalize();
        this.check("Zone Focus не установлен", ! FabricLoader.getInstance().isModLoaded("zonefocus"), "");
        this.check("мост зон недоступен", ! ZoneFocusFiles.available(), "");
        this.nextAt = Util.getMillis() + 1500L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt)
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
                    GuiSchematics.open(null);
                    wait = 1500L;
                }
                case 1 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    SchematicEntry entry = SchematicLibrary.get().entry(this.file);
                    Method select = GuiSchematics.class.getDeclaredMethod("select", SchematicEntry.class);
                    select.setAccessible(true);
                    select.invoke(screen, entry);
                    wait = 4000L;
                }
                case 2 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    Object files = field(screen, "files");
                    Class<?> itemClass = Class.forName("com.aell.layerlist.schematics.gui.FileListPane$Item");
                    Class<?> kindClass = Class.forName("com.aell.layerlist.schematics.gui.FileListPane$Kind");
                    Object fileKind = null;

                    for (Object k : kindClass.getEnumConstants())
                    {
                        fileKind = ((Enum<?>) k).name().equals("FILE") ? k : fileKind;
                    }

                    var ctor = itemClass.getDeclaredConstructor(kindClass, SchematicEntry.class);
                    ctor.setAccessible(true);
                    Object item = ctor.newInstance(fileKind, SchematicLibrary.get().entry(this.file));
                    Method open = files.getClass().getDeclaredMethod("openMenu", itemClass, int.class, int.class);
                    open.setAccessible(true);
                    open.invoke(files, item, 200, 120);

                    Object layers = field(screen, "layers");
                    Object top = layers.getClass().getMethod("top").invoke(layers);
                    List<String> labels = new ArrayList<>();

                    for (Object it : (List<?>) field(top, "items"))
                    {
                        labels.add((String) it.getClass().getMethod("label").invoke(it));
                    }

                    boolean zones = labels.stream().anyMatch(l -> l.toLowerCase().contains("зон") || l.toLowerCase().contains("zone")
                            || l.toLowerCase().contains("разбиени"));
                    this.check("меню файла без пунктов про зоны", ! labels.isEmpty() && ! zones, labels.toString());
                    shot(mc, "без Zone Focus — меню файла");
                    wait = 1000L;
                }
                case 3 ->
                {
                    Object layers = field(GuiUtils.getCurrentScreen(), "layers");
                    layers.getClass().getMethod("closeAll").invoke(layers);
                    Object pane = field(GuiUtils.getCurrentScreen(), "preview");
                    boolean panel = (boolean) invoke(pane, "zonePanelOpen");
                    Object frames = field(pane, "zoneFrames");
                    boolean framesVisible = (boolean) frames.getClass().getMethod("visible").invoke(frames);
                    Object overlay = field(pane, "zones");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("панели разбиения нет", ! panel, "");
                    this.check("кнопки «Зоны» на превью нет", ! framesVisible, "");
                    this.check("каркасов на превью нет", count == 0, "каркасов " + count);
                    Object zones = field(field(GuiUtils.getCurrentScreen(), "info"), "zones");
                    this.check("в правой панели нет кнопки «Разбить на зоны»",
                            ! (boolean) zones.getClass().getMethod("visible").invoke(zones), "");
                    shot(mc, "без Zone Focus — превью как раньше");
                    wait = 500L;
                }
                default ->
                {
                    mc.gui.setScreen(null);
                    CovOut.log("[nozf] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            CovOut.log("[nozf] итог: FAIL");
            mc.gui.setScreen(null);
            return true;
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private static Object invoke(Object owner, String name) throws ReflectiveOperationException
    {
        Method m = owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(owner);
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
        CovOut.log("[nozf] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[nozf] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
