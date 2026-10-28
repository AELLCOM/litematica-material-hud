package com.aell.layerlist.dev;

import com.aell.layerlist.api.LayerListApi;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Режим {@code zonecard}: карточка «Управление зонами» во вкладке Zone Focus (запрос 09.10.2026). Нужен Zone
 * Focus 0.0.8+ в {@code run/mods}. Портал 5×1×3, зоны 1–2 построены, 3 наполовину, активная 7 — снимок;
 * потом, не трогая экран, активная — 12 и достроена зона 7 — карточка сама показывает новое (живая); щелчок
 * по карточке открывает «Управление зонами»; разбиение отменено — карточка «не разбита».
 */
@Environment(EnvType.CLIENT)
public final class ZoneCardShot implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "ZONECARD (карточка «Управление зонами»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null || mc.player == null)
        {
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
            Object manager = Class.forName("com.aell.zonefocus.zone.ZoneManager").getMethod("getInstance").invoke(null);

            switch (this.stage)
            {
                case 0 ->
                {
                    manager.getClass().getMethod("splitGrid", SchematicPlacement.class, int.class, int.class, int.class)
                            .invoke(manager, this.placement, 5, 1, 3);
                    manager.getClass().getMethod("setActiveIndex", int.class).invoke(manager, 6);
                    this.build(mc, manager, new int[] { 0, 1, 2 }, 2);
                    wait = 5000L;
                }
                case 1 ->
                {
                    this.openTab(mc);
                    wait = 3000L;
                }
                case 2 ->
                {
                    Object card = this.card();
                    int[] summary = (int[]) field(card, "summary");
                    int[] progress = (int[]) field(card, "progress");
                    this.check("карточка: 15 зон, активная 7", summary != null && summary[0] == 15 && summary[1] == 6,
                            java.util.Arrays.toString(summary));
                    this.check("карточка: зоны 1–2 готовы, 3 начата", progress != null && progress[0] == 100 && progress[1] == 100
                            && progress[2] > 0 && progress[2] < 100, java.util.Arrays.toString(progress));
                    this.checkTab();
                    SupportTest.moveMouse(mc, 200, 100);
                    shot(mc, "карточка — 15 зон, прогресс");

                    // Экран не трогаем: активная — 12, зона 7 достроена
                    manager.getClass().getMethod("setActiveIndex", int.class).invoke(manager, 11);
                    this.build(mc, manager, new int[] { 6 }, -1);
                    SupportTest.moveMouse(mc, 4, 4);
                    wait = 3000L;
                }
                case 3 ->
                {
                    Object card = this.card();
                    int[] summary = (int[]) field(card, "summary");
                    int[] progress = (int[]) field(card, "progress");
                    this.check("живая: активная сама сменилась на 12", summary != null && summary[1] == 11,
                            java.util.Arrays.toString(summary));
                    this.check("живая: зона 7 сама стала готовой", progress != null && progress[6] >= 95,
                            java.util.Arrays.toString(progress));
                    shot(mc, "карточка обновилась сама — активная 12, зона 7 готова");
                    // Щелчок по карточке — настоящим путём мыши
                    SupportTest.click(mc, 200, 100);
                    wait = 2000L;
                }
                case 4 ->
                {
                    String screen = mc.screen == null ? "null" : mc.screen.getClass().getSimpleName();
                    this.check("щелчок по карточке открыл «Управление зонами»", "GuiZoneManager".equals(screen), screen);
                    this.openTab(mc);
                    wait = 2500L;
                }
                case 5 ->
                {
                    // Яркая кнопка «Настройки Zone Focus» в карточке — настоящим щелчком
                    int[] b = (int[]) this.card().getClass().getMethod("settingsBounds").invoke(this.card());
                    this.check("кнопка «Настройки Zone Focus» нарисована в карточке", b[2] > 0, java.util.Arrays.toString(b));
                    SupportTest.moveMouse(mc, b[0] + b[2] / 2.0D, b[1] + b[3] / 2.0D);
                    shot(mc, "карточка — наведена «Настройки Zone Focus»");
                    SupportTest.click(mc, b[0] + b[2] / 2.0D, b[1] + b[3] / 2.0D);
                    wait = 2000L;
                }
                case 6 ->
                {
                    String screen = mc.screen == null ? "null" : mc.screen.getClass().getName();
                    this.check("кнопка в карточке открыла настройки Zone Focus", screen.contains("zonefocus"), screen);
                    mc.setScreen(null);
                    manager.getClass().getMethod("clear").invoke(manager);
                    wait = 1500L;
                }
                case 7 ->
                {
                    this.openTab(mc);
                    wait = 2500L;
                }
                case 8 ->
                {
                    Object card = this.card();
                    this.check("разбиение отменено — карточка «не разбита»", field(card, "summary") == null, "");
                    shot(mc, "карточка — не разбита");
                    wait = 500L;
                }
                default ->
                {
                    mc.setScreen(null);
                    CovOut.log("[zonecard] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            CovOut.log("[zonecard] итог: FAIL");
            mc.setScreen(null);
            return true;
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Модель настроек держит вкладки — сбрасываем, чтобы карточка собралась заново, и открываем Zone Focus. */
    private void openTab(Minecraft mc) throws ReflectiveOperationException
    {
        Object settings = settings();
        Field tabs = settings.getClass().getDeclaredField("tabs");
        tabs.setAccessible(true);
        tabs.set(settings, null);
        SupportTest.moveMouse(mc, 4, 4);
        mc.setScreen(LayerListApi.createSettings(null, null, LayerListApi.TAB_ZONE_FOCUS));
    }

    private static Object settings() throws ReflectiveOperationException
    {
        Class<?> c = Class.forName("com.aell.layerlist.config.LayerListSettings");
        Field instance = c.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        return instance.get(null);
    }

    /** Карточка во вкладке Zone Focus — объект строки-карточки модели настроек. */
    private Object card() throws ReflectiveOperationException
    {
        Object settings = settings();

        for (Object tab : (List<?>) settings.getClass().getMethod("tabs").invoke(settings))
        {
            if (! LayerListApi.TAB_ZONE_FOCUS.equals(tab.getClass().getField("key").get(tab)))
            {
                continue;
            }

            for (Object section : (List<?>) tab.getClass().getField("sections").get(tab))
            {
                for (Object option : (List<?>) section.getClass().getField("options").get(section))
                {
                    Object card = option.getClass().getField("card").get(option);

                    if (card != null)
                    {
                        return card;
                    }
                }
            }
        }

        throw new IllegalStateException("карточки во вкладке нет");
    }

    /**
     * Состав вкладки (09.10.2026): тумблера «Модуль Zone Focus» нет, «Настройки Zone Focus…» — в «Зонах»
     * фиолетовой главной кнопкой, там же «3D-превью схем» (Качество / Оптимизированно) — и он правит настройку.
     */
    private void checkTab() throws ReflectiveOperationException
    {
        Object settings = settings();
        java.util.List<String> zones = new java.util.ArrayList<>();
        java.util.List<String> all = new java.util.ArrayList<>();
        Object standalone = null;
        Object quality = null;

        for (Object tab : (List<?>) settings.getClass().getMethod("tabs").invoke(settings))
        {
            if (! LayerListApi.TAB_ZONE_FOCUS.equals(tab.getClass().getField("key").get(tab)))
            {
                continue;
            }

            for (Object section : (List<?>) tab.getClass().getField("sections").get(tab))
            {
                String sectionKey = (String) section.getClass().getField("key").get(section);

                for (Object option : (List<?>) section.getClass().getField("options").get(section))
                {
                    String key = (String) option.getClass().getField("key").get(option);
                    all.add(key);

                    if (sectionKey.endsWith(".zones"))
                    {
                        zones.add(key);
                    }

                    standalone = key.endsWith("standaloneSettings") ? option : standalone;
                    quality = key.endsWith("previewQuality") ? option : quality;
                }
            }
        }

        this.check("тумблера «Модуль Zone Focus» во вкладке нет", all.stream().noneMatch(k -> k.endsWith(".zoneFocus")), all.toString());
        this.check("«Настройки Zone Focus» — в карточке, отдельной строки нет", standalone == null, all.toString());
        this.check("«3D-превью схем» во вкладке нет — он в «Управлении зонами», рядом с 2D / 3D",
                all.stream().noneMatch(k -> k.endsWith("previewQuality")), zones.toString());
        this.check("«Инструмент» — одним разделом (без «Интерфейса инструмента»)", all.contains("layerlist.zf.config.toolKeyHints"), "");

        if (quality != null)
        {
            Object value = quality.getClass().getField("bool").get(quality);
            java.lang.reflect.Method get = value.getClass().getMethod("get");
            java.lang.reflect.Method set = value.getClass().getMethod("set", boolean.class);
            get.setAccessible(true);
            set.setAccessible(true);
            boolean before = (boolean) get.invoke(value);
            set.invoke(value, ! before);
            var config = com.aell.layerlist.schematics.SchematicsConfig.previewQuality();
            boolean switched = (config == com.aell.layerlist.schematics.SchematicsConfig.PreviewQuality.QUALITY) == ! before;
            set.invoke(value, before);
            this.check("переключатель «Качество / Оптимизированно» правит качество превью", switched, "было " + before);
        }
    }

    /** Построить зоны (блоки схемы в мир); {@code halfOf} — эта зона только нижней половиной. */
    private void build(Minecraft mc, Object manager, int[] zones, int halfOf) throws ReflectiveOperationException
    {
        @SuppressWarnings("unchecked")
        List<Object> boxes = (List<Object>) manager.getClass().getMethod("zoneBoxes").invoke(manager);
        var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        var server = mc.getSingleplayerServer();
        var level = server.getLevel(mc.level.dimension());
        Map<BlockPos, BlockState> copy = new HashMap<>();

        for (int i : zones)
        {
            Object b = boxes.get(i);
            Class<?> c = b.getClass();
            int minY = (int) c.getMethod("minY").invoke(b);
            int maxY = (int) c.getMethod("maxY").invoke(b);

            for (BlockPos p : BlockPos.betweenClosed((int) c.getMethod("minX").invoke(b), minY, (int) c.getMethod("minZ").invoke(b),
                    (int) c.getMethod("maxX").invoke(b), i == halfOf ? minY + (maxY - minY) / 2 : maxY, (int) c.getMethod("maxZ").invoke(b)))
            {
                BlockState state = schematicWorld.getBlockState(p);

                if (! state.isAir())
                {
                    copy.put(p.immutable(), state);
                }
            }
        }

        server.execute(() -> copy.forEach((p, state) -> level.setBlock(p, state, 2 | 16)));
        CovOut.log("[zonecard] построено блоков: " + copy.size());
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[zonecard] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[zonecard] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
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
