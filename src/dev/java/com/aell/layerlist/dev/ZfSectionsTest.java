package com.aell.layerlist.dev;

import com.aell.layerlist.api.CountScope;
import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.compat.ZoneFocusFiles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code zfsections}: участки Zone Focus (несколько схем, несколько участков) через счёт Material HUD
 * и обрезку рендера. Нужен Zone Focus с участками в {@code run/mods}; схема {@code run/schematics/Портал.litematic}.
 * Всё про Zone Focus — только рефлексией (MHUD собирается без него); {@link LayerListApi} — наш класс, напрямую.
 * <ol>
 *   <li>подготовка, две постановки {@code zfs-a}, {@code zfs-b};</li>
 *   <li>два участка, активный участок и число зон;</li>
 *   <li>счёт через {@code LayerListApi.getCountScope()}: габариты, подпись при сдвиге и включении;</li>
 *   <li>обрезка {@code ZoneRenderClip}: чужой участок, «скрыть остальные», скрытые участки;</li>
 *   <li>режимы слоя и снимки;</li>
 *   <li>ручная зона без схемы;</li>
 *   <li>миграция старой записи {@code zonefocus_zones.json}.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class ZfSectionsTest implements CovJob
{
    private static final String TAG = "[zfsections] ";
    private static final String ZM = "com.aell.zonefocus.zone.ZoneManager";
    private static final String CFG = "com.aell.zonefocus.config.ZoneFocusConfigs";
    private static final String CLIP = "com.aell.zonefocus.zone.ZoneRenderClip";

    @FunctionalInterface
    private interface Step
    {
        /** Возвращает, сколько мс ждать после шага. */
        long run(Minecraft mc) throws Exception;
    }

    private final List<Step> steps = new ArrayList<>();
    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean done;
    private Path file;
    private SchematicPlacement pa;
    private SchematicPlacement pb;
    private SchematicPlacement pc;
    private int[] boxA;
    private int[] boxB;
    private String sigA;
    private String sigB;
    private String sigTmp;
    private boolean origCount;
    private boolean origClip;
    private boolean origHideOutside;
    private boolean origHideOther;
    private boolean cfgSaved;
    private boolean bakExisted;

    @Override
    public String name()
    {
        return "ZFSECTIONS (участки Zone Focus: счёт, обрезка, слои)";
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    // ===== Подготовка =====

    @Override
    public boolean begin(Minecraft mc)
    {
        if (! ZoneFocusFiles.available())
        {
            CovOut.log(TAG + "нет Zone Focus с участками в run/mods");
            return false;
        }

        try
        {
            Class.forName(ZM).getMethod("sections");
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "Zone Focus без участков (ZoneManager.sections нет): " + e);
            return false;
        }

        this.file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic").toAbsolutePath().normalize();
        this.bakExisted = Files.exists(FabricLoader.getInstance().getConfigDir().resolve("zonefocus_zones.json.bak-0.0.9"));
        this.buildSteps();
        this.nextAt = Util.getMillis() + 1500L;
        return true;
    }

    private void buildSteps()
    {
        Step[] all = {
                // 0: подготовка
                mc ->
                {
                    this.removeAllPlacements();
                    callIfPresent(zm(), "clear");
                    ZoneFocusFiles.clear(this.file);
                    this.origCount = this.cfg("COUNT_BY_ZONE").getBooleanValue();
                    this.origClip = this.cfg("CLIP_SCHEMATIC_RENDER").getBooleanValue();
                    this.origHideOutside = this.cfg("HIDE_OUTSIDE_SCHEMATICS").getBooleanValue();
                    this.origHideOther = this.cfg("HIDE_OTHER_SECTIONS").getBooleanValue();
                    this.cfgSaved = true;
                    this.cfg("COUNT_BY_ZONE").setBooleanValue(true);
                    this.cfg("CLIP_SCHEMATIC_RENDER").setBooleanValue(true);
                    this.cfg("HIDE_OUTSIDE_SCHEMATICS").setBooleanValue(false);
                    this.cfg("HIDE_OTHER_SECTIONS").setBooleanValue(true);
                    this.check("0. настройки: счёт по зонам и обрезка включены, чужие схемы и участки — как заданы",
                            this.cfg("COUNT_BY_ZONE").getBooleanValue() && this.cfg("CLIP_SCHEMATIC_RENDER").getBooleanValue()
                                    && ! this.cfg("HIDE_OUTSIDE_SCHEMATICS").getBooleanValue()
                                    && this.cfg("HIDE_OTHER_SECTIONS").getBooleanValue(), "");

                    LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(this.file);
                    this.check("0. схема Портал.litematic загружена", schematic != null, String.valueOf(this.file));
                    this.pa = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(4, 0, 4), "zfs-a", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(this.pa, false);
                    return 1500L;
                },
                // 1: вторая постановка по габариту первой
                mc ->
                {
                    LitematicaSchematic schematic = this.pa.getSchematic();
                    Object bounds = zmStatic("enclosingBox", this.pa);
                    this.check("0. габарит первой постановки найден", bounds != null, String.valueOf(bounds));
                    int sizeX = (int) call(bounds, "sizeX");
                    BlockPos originB = this.pa.getOrigin().offset(sizeX + 6, 0, 0);
                    this.pb = SchematicPlacement.createFor(schematic, originB, "zfs-b", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(this.pb, false);
                    CovOut.log(TAG + "sizeX=" + sizeX + ", origin A=" + this.pa.getOrigin() + ", origin B=" + originB);
                    return 2500L;
                },
                // 2: два участка
                mc ->
                {
                    Object zm = zm();
                    this.boxA = this.enclosing(this.pa);
                    call(zm, "splitGrid", this.pa, 2, 1, 2);
                    this.check("1. splitGrid(A, 2×1×2): один участок", sections(zm).size() == 1, "участков " + sections(zm).size());
                    this.check("1. A: getZoneCount()==4", (int) call(zm, "getZoneCount") == 4, "зон " + call(zm, "getZoneCount"));
                    call(zm, "splitGrid", this.pb, 1, 1, 3);
                    this.check("1. splitGrid(B, 1×1×3): два участка", sections(zm).size() == 2, "участков " + sections(zm).size());
                    this.check("1. активный участок — индекс 1", (int) call(zm, "getActiveSectionIndex") == 1,
                            "индекс " + call(zm, "getActiveSectionIndex"));
                    this.check("1. B: getZoneCount()==3", (int) call(zm, "getZoneCount") == 3, "зон " + call(zm, "getZoneCount"));
                    call(zm, "setActiveSection", 0);
                    this.check("1. setActiveSection(0): getZoneCount()==4", (int) call(zm, "getZoneCount") == 4,
                            "зон " + call(zm, "getZoneCount"));
                    return 800L;
                },
                // 3: счёт, участок A
                mc ->
                {
                    CountScope scope = LayerListApi.getCountScope();
                    this.check("2. LayerListApi.getCountScope() не пуст", scope != null, String.valueOf(scope));
                    this.check("2. скоуп активен", scope.isActive(), "");
                    List<Box> boxes = scope.getBoxes();
                    this.check("2. getBoxes() не пуст", boxes != null && ! boxes.isEmpty(), "коробок " + (boxes == null ? -1 : boxes.size()));
                    this.boxA = this.enclosing(this.pa);
                    this.boxB = this.enclosing(this.pb);
                    CovOut.log(TAG + "габарит A " + java.util.Arrays.toString(this.boxA) + ", B " + java.util.Arrays.toString(this.boxB));
                    this.check("2. все коробки внутри габарита A", this.allInside(boxes, this.boxA) && ! this.anyTouches(boxes, this.boxB),
                            this.describe(boxes));
                    this.sigA = scope.getSignature();
                    CovOut.log(TAG + "подпись A: " + this.sigA);
                    return 300L;
                },
                // 4: сдвиг B
                mc ->
                {
                    this.pb.setOrigin(this.pb.getOrigin().offset(0, 0, 16), s -> {});
                    return 1500L;
                },
                // 5: подпись A не изменилась; переход на B
                mc ->
                {
                    CountScope scope = LayerListApi.getCountScope();
                    this.check("2. сдвиг B: подпись активного участка A не изменилась", this.sigA.equals(scope.getSignature()),
                            this.sigA + " → " + scope.getSignature());
                    this.boxB = this.enclosing(this.pb);
                    call(zm(), "setActiveSection", 1);
                    return 800L;
                },
                mc ->
                {
                    CountScope scope = LayerListApi.getCountScope();
                    this.sigB = scope.getSignature();
                    this.check("2. setActiveSection(1): подпись изменилась", ! this.sigB.equals(this.sigA), this.sigA + " → " + this.sigB);
                    List<Box> boxes = scope.getBoxes();
                    this.check("2. коробки активного B внутри габарита B", boxes != null && ! boxes.isEmpty()
                            && this.allInside(boxes, this.boxB) && ! this.anyTouches(boxes, this.boxA), this.describe(boxes));
                    this.pa.setEnabled(false);
                    return 1000L;
                },
                mc ->
                {
                    this.check("2. выключили A: подпись при активном B не изменилась", this.sigB.equals(LayerListApi.getCountScope().getSignature()),
                            this.sigB + " → " + LayerListApi.getCountScope().getSignature());
                    this.pa.setEnabled(true);
                    return 1000L;
                },
                mc ->
                {
                    this.check("2. вернули A: подпись при активном B не изменилась", this.sigB.equals(LayerListApi.getCountScope().getSignature()),
                            this.sigB + " → " + LayerListApi.getCountScope().getSignature());
                    return 300L;
                },
                // 8: обрезка
                mc ->
                {
                    Class<?> clip = Class.forName(CLIP);
                    this.check("3. ZoneRenderClip.isClipping()==true", (boolean) clip.getMethod("isClipping").invoke(null), "");
                    int[] cb = this.activeZoneCenter(1);
                    int[] ca = this.center(this.boxA);
                    boolean inB = contains(cb);
                    boolean inA = contains(ca);
                    this.check("3. contains(центр активной зоны B)==true", inB, java.util.Arrays.toString(cb));
                    this.check("3. HIDE_OTHER_SECTIONS: contains(центр A)==false", ! inA, java.util.Arrays.toString(ca));
                    this.cfg("HIDE_OTHER_SECTIONS").setBooleanValue(false);
                    return 600L;
                },
                mc ->
                {
                    int[] ca = this.center(this.boxA);
                    this.check("3. HIDE_OTHER_SECTIONS выкл: contains(центр A)==true", contains(ca), java.util.Arrays.toString(ca));
                    this.cfg("HIDE_OTHER_SECTIONS").setBooleanValue(true);
                    Object zm = zm();
                    call(zm, "setSectionHidden", sections(zm).get(1), true);
                    return 800L;
                },
                mc ->
                {
                    Object zm = zm();
                    this.check("3. скрыт B: активность ушла на A (индекс 0)", (int) call(zm, "getActiveSectionIndex") == 0,
                            "индекс " + call(zm, "getActiveSectionIndex"));
                    call(zm, "setSectionHidden", sections(zm).get(0), true);
                    return 800L;
                },
                mc ->
                {
                    Object zm = zm();
                    List<?> cb = (List<?>) call(zm, "getCountBoxes");
                    this.check("3. скрыты оба: getCountBoxes() пуст", cb.isEmpty(), "коробок " + cb.size());
                    this.check("3. скрыты оба: isClipping()==true", (boolean) Class.forName(CLIP).getMethod("isClipping").invoke(null), "");
                    int[] ca = this.center(this.boxA);
                    this.check("3. скрыты оба: contains(центр A)==false", ! contains(ca), java.util.Arrays.toString(ca));
                    call(zm, "setSectionHidden", sections(zm).get(0), false);
                    call(zm, "setSectionHidden", sections(zm).get(1), false);
                    call(zm, "setActiveSection", 1);
                    return 1200L;
                },
                // 13: слои
                mc ->
                {
                    int[] c = this.activeZoneCenter((int) call(zm(), "getActiveSectionIndex"));
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.SINGLE_LAYER);
                    DataManager.getRenderLayerRange().setLayerSingle(c[1]);
                    CovOut.log(TAG + "SINGLE_LAYER, y=" + c[1]);
                    return 2500L;
                },
                mc ->
                {
                    this.check("4. SINGLE_LAYER: isClipping()==true", (boolean) Class.forName(CLIP).getMethod("isClipping").invoke(null), "");
                    this.check("4. SINGLE_LAYER: схема не пропала (есть чанки)", this.schematicChunks() > 0, "чанков " + this.schematicChunks());
                    shot(mc, "zfsections-layers");
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                    return 2500L;
                },
                mc ->
                {
                    this.check("4. ALL: isClipping()==true", (boolean) Class.forName(CLIP).getMethod("isClipping").invoke(null), "");
                    this.check("4. ALL: схема на месте (есть чанки)", this.schematicChunks() > 0, "чанков " + this.schematicChunks());
                    shot(mc, "zfsections-all");
                    return 500L;
                },
                // 16: ручная зона без схемы
                mc ->
                {
                    Object zm = zm();
                    call(zm, "clear");
                    this.pa.setEnabled(false);
                    this.pb.setEnabled(false);
                    BlockPos far = new BlockPos(Math.max(this.boxA[3], this.boxB[3]) + 40, this.boxA[1], this.boxA[2]);
                    call(zm, "setManualBox", far, far.offset(5, 5, 5));
                    return 1000L;
                },
                mc ->
                {
                    Object zm = zm();
                    List<?> s = sections(zm);
                    this.check("5. ручная зона: один участок", s.size() == 1, "участков " + s.size());
                    this.check("5. участок без схем (hasNoPlacements)", ! s.isEmpty() && (boolean) call(s.get(0), "hasNoPlacements"), "");
                    List<?> cb = (List<?>) call(zm, "getCountBoxes");
                    this.check("5. обе схемы выключены: getCountBoxes() пуст", cb.isEmpty(), "коробок " + cb.size());
                    this.sigTmp = LayerListApi.getCountScope().getSignature();
                    this.pa.setEnabled(true);
                    return 1000L;
                },
                mc ->
                {
                    String now = LayerListApi.getCountScope().getSignature();
                    this.check("5. включили схему: подпись ручного участка изменилась", ! now.equals(this.sigTmp), this.sigTmp + " → " + now);
                    this.pb.setEnabled(true);
                    return 500L;
                },
                // 20: миграция
                mc ->
                {
                    Object zm = zm();
                    call(zm, "clear");
                    String key = (String) Class.forName("com.aell.zonefocus.zone.ZoneStore").getMethod("scopeKey", Minecraft.class).invoke(null, mc);
                    this.check("6. scopeKey не пуст", key != null && ! key.isEmpty(), String.valueOf(key));
                    Path cfg = FabricLoader.getInstance().getConfigDir().resolve("zonefocus_zones.json");
                    JsonObject root = new JsonObject();

                    if (Files.isRegularFile(cfg))
                    {
                        try
                        {
                            root = JsonParser.parseString(Files.readString(cfg, StandardCharsets.UTF_8)).getAsJsonObject();
                        }
                        catch (RuntimeException e)
                        {
                            root = new JsonObject();
                        }
                    }

                    JsonObject worlds = root.has("worlds") && root.get("worlds").isJsonObject() ? root.getAsJsonObject("worlds") : new JsonObject();
                    worlds.add(key, JsonParser.parseString("{\"kind\":\"GRID\",\"placement\":\"zfs-a\",\"nx\":2,\"ny\":1,\"nz\":2,\"active\":3}"));
                    root.add("worlds", worlds);
                    Files.createDirectories(cfg.getParent());
                    Files.writeString(cfg, root.toString(), StandardCharsets.UTF_8);
                    Object layout = Class.forName("com.aell.zonefocus.zone.ZoneStore").getMethod("load", String.class).invoke(null, key);
                    List<?> secs = (List<?>) layout.getClass().getField("sections").get(layout);
                    this.check("6. старая запись: Layout с одним участком", secs.size() == 1, "участков " + secs.size());

                    if (! secs.isEmpty())
                    {
                        Object section = secs.get(0);
                        List<?> refs = (List<?>) section.getClass().getField("placements").get(section);
                        String name = refs.isEmpty() ? "<нет>" : String.valueOf(refs.get(0).getClass().getField("name").get(refs.get(0)));
                        this.check("6. placements[0].name==\"zfs-a\"", "zfs-a".equals(name), name);
                    }

                    call(zm, "clear");
                    return 500L;
                },
                // 21: экраны — «Управление зонами» с двумя участками и окно «Новый участок» (снимки для глаза)
                mc ->
                {
                    Object zm = zm();
                    this.pa.setEnabled(true);
                    this.pb.setEnabled(true);
                    call(zm, "splitGrid", this.pa, 2, 1, 2);
                    call(zm, "splitGrid", this.pb, 1, 1, 3);
                    call(zm, "setActiveSection", 0);
                    Class<?> editor = Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor");
                    editor.getField("view3d").setBoolean(null, false);
                    editor.getField("showProgress").setBoolean(null, true);
                    Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                            .getConstructor(Screen.class, SchematicPlacement.class).newInstance(null, this.pa);
                    mc.gui.setScreen(gui);
                    return 3000L;
                },
                mc ->
                {
                    Screen gui = mc.gui.screen();
                    this.check("7. «Управление зонами» открыто", gui != null
                            && gui.getClass().getName().endsWith("GuiZoneManager"), String.valueOf(gui));
                    shot(mc, "zfsections-manager");
                    Class.forName("com.aell.zonefocus.gui.GuiSectionSplit")
                            .getMethod("open", Screen.class, Class.forName("com.aell.zonefocus.zone.model.Section"))
                            .invoke(null, gui, null);
                    return 2500L;
                },
                mc ->
                {
                    Screen gui = mc.gui.screen();
                    this.check("7. окно «Новый участок» открыто", gui != null
                            && gui.getClass().getName().endsWith("GuiSectionSplit"), String.valueOf(gui));
                    shot(mc, "zfsections-split");
                    mc.gui.setScreen(null);
                    call(zm(), "clear");
                    return 500L;
                },
                // 22: группа из двух схем общим габаритом — 3D-превью участка (API 6)
                mc ->
                {
                    Object zm = zm();
                    call(zm, "splitGrid", this.pa, 2, 1, 2);
                    Object section = sections(zm).get(0);
                    Class<?> refClass = Class.forName("com.aell.zonefocus.zone.model.PlacementRef");
                    Object ref = refClass.getConstructor(java.util.UUID.class, String.class, String.class,
                            Class.forName("com.aell.zonefocus.zone.ZoneBox")).newInstance(this.pb.getHashId(), this.pb.getName(),
                            Class.forName("com.aell.zonefocus.zone.FileZones").getMethod("key", SchematicPlacement.class).invoke(null, this.pb), null);
                    @SuppressWarnings("unchecked") List<Object> refs = (List<Object>) section.getClass().getField("placements").get(section);
                    refs.add(ref);
                    Class<?> bounds = Class.forName("com.aell.zonefocus.zone.model.Bounds");
                    section.getClass().getField("bounds").set(section, bounds.getField("COMBINED").get(null));
                    call(call(zm, "getLayout"), "touch");
                    call(zm, "splitGrid", section, 3, 1, 2);
                    List<?> boxes = (List<?>) call(zm, "zoneBoxes", section);
                    this.check("8. группа A+B общим габаритом: 6 зон", boxes.size() == 6, "зон " + boxes.size());
                    int[] a = this.enclosing(this.pa);
                    int[] b = this.enclosing(this.pb);
                    int[] first = zoneBox(boxes.get(0));
                    int[] last = zoneBox(boxes.get(boxes.size() - 1));
                    this.check("8. сетка группы покрывает обе схемы", first[0] == Math.min(a[0], b[0]) && last[3] == Math.max(a[3], b[3]),
                            java.util.Arrays.toString(first) + " … " + java.util.Arrays.toString(last));
                    this.group = section;
                    Class<?> editor = Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor");
                    editor.getField("view3d").setBoolean(null, true);
                    editor.getField("showProgress").setBoolean(null, false);
                    Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                            .getConstructor(Screen.class, section.getClass()).newInstance(null, section);
                    mc.gui.setScreen(gui);
                    return 8000L;
                },
                mc ->
                {
                    Screen gui = mc.gui.screen();
                    Object preview = gui != null ? fieldOf(gui, "preview") : null;
                    this.check("8. 3D для группы — превью создано", preview instanceof com.aell.layerlist.api.ZonePreview, String.valueOf(preview));

                    if (preview instanceof com.aell.layerlist.api.ZonePreview zp)
                    {
                        int[] a = this.enclosing(this.pa);
                        int[] b = this.enclosing(this.pb);
                        BlockPos min = zp.worldMin();
                        int[] size = zp.size();
                        this.check("8. 3D для группы — модель собрана", zp.hasModel(), zp.status());
                        this.check("8. начало модели — угол объединения схем", min != null && min.getX() == Math.min(a[0], b[0])
                                && min.getY() == Math.min(a[1], b[1]) && min.getZ() == Math.min(a[2], b[2]), String.valueOf(min));
                        this.check("8. размер модели — объединение схем", size != null && size[0] == Math.max(a[3], b[3]) - Math.min(a[0], b[0]) + 1,
                                size == null ? "null" : java.util.Arrays.toString(size));
                    }

                    shot(mc, "zfsections-3d-group");
                    return 300L;
                },
                // 23: разрезы с шагом в блок
                mc ->
                {
                    Object zm = zm();
                    Object section = this.group;
                    call(zm, "setCuts", section, new int[] { 10 }, new int[0], new int[] { 20 });
                    List<?> boxes = (List<?>) call(zm, "zoneBoxes", section);
                    Object rule = section.getClass().getField("rule").get(section);
                    this.check("9. разрезы: правило «разрезы», 4 зоны", rule.getClass().getSimpleName().equals("Cuts") && boxes.size() == 4,
                            rule + ", зон " + boxes.size());
                    int[] box = zoneBox(call(zm, "sectionBox", section));
                    int[] z0 = zoneBox(boxes.get(0));
                    this.check("9. первая зона кончается на X = угол + 9", z0[3] == box[0] + 9, java.util.Arrays.toString(z0));
                    call(zm, "moveCut", section, 0, 0, 15);
                    z0 = zoneBox(((List<?>) call(zm, "zoneBoxes", section)).get(0));
                    this.check("9. разрез сдвинут на 15 — граница на угол + 14", z0[3] == box[0] + 14, java.util.Arrays.toString(z0));
                    int active = (int) section.getClass().getField("activeZone").get(section);
                    call(zm, "setActiveZone", section, 3);
                    call(zm, "moveCut", section, 2, 0, 25);
                    active = (int) section.getClass().getField("activeZone").get(section);
                    this.check("9. сдвиг разреза без смены числа зон не сбрасывает активную зону", active == 3, "активная " + active);
                    Screen gui = mc.gui.screen();
                    Object editorObj = gui != null ? fieldOf(gui, "editor") : null;
                    this.check("9. редактор: разрезы двигаются мышью (cuttable)", editorObj != null && (boolean) call(editorObj, "cuttable"), "");
                    return 1500L;
                },
                mc ->
                {
                    shot(mc, "zfsections-3d-cuts");
                    Screen gui = mc.gui.screen();
                    Object tool = gui != null ? fieldOf(gui, "cutTool") : null;
                    float[][] handles = tool != null ? (float[][]) fieldOf(tool, "handles") : new float[0][];
                    int movable = 0;

                    for (float[] h : handles)
                    {
                        movable += h[6] > 0.0F ? 1 : 0;
                    }

                    this.check("10. 3D: у плоскостей разреза есть ручки (два разреза)", handles.length == 2 && movable >= 1,
                            "плоскостей " + handles.length + ", с ручкой " + movable);

                    // Курсор на ручку — экран найдёт наведение в следующем кадре
                    this.handle = null;

                    for (float[] h : handles)
                    {
                        if (h[6] > 0.0F)
                        {
                            this.handle = h;
                            break;
                        }
                    }

                    if (this.handle != null)
                    {
                        SupportTest.moveMouse(mc, this.handle[2], this.handle[3]);
                    }

                    return 400L;
                },
                mc ->
                {
                    // Настоящий щелчок по ручке, протяжка на 8 блоков вдоль оси разреза, отпускание
                    Screen gui = mc.gui.screen();

                    if (this.handle != null && gui != null)
                    {
                        int axis = (int) this.handle[0];
                        int index = (int) this.handle[1];
                        int[][] cuts = (int[][]) call(zm(), "cuts", this.group);
                        this.dragFrom = cuts[axis][index];
                        this.dragAxis = axis;
                        this.dragIndex = index;
                        double tx = this.handle[2] + this.handle[4] * 8.0;
                        double ty = this.handle[3] + this.handle[5] * 8.0;
                        this.press(mc);
                        gui.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(tx, ty,
                                new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)),
                                tx - this.handle[2], ty - this.handle[3]);
                        SupportTest.moveMouse(mc, tx, ty);
                        this.release(mc);
                    }

                    return 1200L;
                },
                mc ->
                {
                    Screen gui = mc.gui.screen();

                    if (this.handle != null)
                    {
                        int[][] cuts = (int[][]) call(zm(), "cuts", this.group);
                        int now = cuts[this.dragAxis][this.dragIndex];
                        this.check("10. 3D: ручку протянули мышью на 8 блоков — разрез сдвинулся на 8", Math.abs(now - (this.dragFrom + 8)) <= 1,
                                "ось " + this.dragAxis + ": " + this.dragFrom + " → " + now);
                    }
                    else
                    {
                        this.check("10. 3D: ручку протянули мышью", false, "ручки нет");
                    }

                    Object slider = gui != null ? fieldOf(gui, "layerCut") : null;
                    int max = gui != null ? (int) fieldOf(gui, "layerCutMax") : -1;
                    this.check("10. ползунок среза по высоте есть", slider != null && max > 0, "max " + max);

                    if (slider != null && max > 0)
                    {
                        call(slider, "setRange", 0, max, max / 2);
                    }

                    return 2500L;
                },
                mc ->
                {
                    shot(mc, "zfsections-3d-layercut");
                    Class<?> editor = Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor");
                    editor.getField("view3d").setBoolean(null, false);
                    editor.getField("mapView").setInt(null, 1);
                    Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                            .getConstructor(Screen.class, this.group.getClass()).newInstance(null, this.group);
                    mc.gui.setScreen(gui);
                    return 4000L;
                },
                mc ->
                {
                    shot(mc, "zfsections-2d-front");
                    Object editorObj = fieldOf(mc.gui.screen(), "editor");
                    int[] map = (int[]) call(editorObj, "mapRect");
                    this.check("11. 2D спереди: карта разложена", map[2] > 0 && map[3] > 0, java.util.Arrays.toString(map));
                    // Курсор на линию разреза X (вертикальная на карте спереди)
                    Object box = call(editorObj, "cutBox");
                    int[][] cuts = (int[][]) call(editorObj, "cuts");
                    this.dragFrom = cuts[0][0];
                    this.lineX = (double) call(editorObj, "cutScreen", box, 0, this.dragFrom);
                    this.lineY = map[1] + map[3] * 0.5;
                    this.lineStep = (double) call(editorObj, "mapScale");
                    SupportTest.moveMouse(mc, this.lineX, this.lineY);
                    return 400L;
                },
                mc ->
                {
                    Screen gui = mc.gui.screen();
                    double tx = this.lineX + this.lineStep * 10.0;
                    this.press(mc);
                    gui.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(tx, this.lineY,
                            new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)),
                            tx - this.lineX, 0.0);
                    SupportTest.moveMouse(mc, tx, this.lineY);
                    this.release(mc);
                    return 1200L;
                },
                mc ->
                {
                    int[][] cuts = (int[][]) call(zm(), "cuts", this.group);
                    this.check("11. 2D спереди: линию X протянули мышью на 10 блоков", Math.abs(cuts[0][0] - (this.dragFrom + 10)) <= 1,
                            this.dragFrom + " → " + cuts[0][0] + " (шаг " + String.format(java.util.Locale.ROOT, "%.2f", this.lineStep) + " пикс./блок)");
                    shot(mc, "zfsections-2d-front-dragged");
                    Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("mapView").setInt(null, 2);
                    Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                            .getConstructor(Screen.class, this.group.getClass()).newInstance(null, this.group);
                    mc.gui.setScreen(gui);
                    return 4000L;
                },
                mc ->
                {
                    shot(mc, "zfsections-2d-side");
                    Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("mapView").setInt(null, 0);
                    // Настройки Zone Focus, вкладка «Зоны» — раздел «Участки» (снимок для описания выпуска)
                    Screen settings = (Screen) Class.forName("com.aell.zonefocus.config.GuiZoneFocusConfigs")
                            .getMethod("create", Screen.class, String.class).invoke(null, null, null);
                    mc.gui.setScreen(settings);
                    java.lang.reflect.Method select = settings.getClass().getSuperclass().getDeclaredMethod("selectTab", int.class);
                    select.setAccessible(true);
                    select.invoke(settings, 1);
                    return 2500L;
                },
                mc ->
                {
                    shot(mc, "zfsections-settings-zones");
                    mc.gui.setScreen(null);
                    call(zm(), "clear");
                    this.group = null;
                    return 500L;
                }
        };

        this.steps.addAll(List.of(all));
    }

    // ===== Ход =====

    @Override
    public boolean step(Minecraft mc)
    {
        if (this.done)
        {
            return true;
        }

        long now = Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        if (this.stage >= this.steps.size())
        {
            CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
            this.done = true;
            return true;
        }

        long wait;

        try
        {
            wait = this.steps.get(this.stage).run(mc);
        }
        catch (Throwable e)
        {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            this.check("сбой на шаге " + this.stage, false, cause.toString());
            CovOut.log(TAG + "итог: FAIL");
            this.done = true;
            return true;
        }

        this.nextAt = Util.getMillis() + wait;
        ++this.stage;
        return false;
    }

    @Override
    public void close()
    {
        try
        {
            Object zm = zm();
            call(zm, "clear");
        }
        catch (Throwable ignored)
        {
        }

        this.removeAllPlacements();

        try
        {
            DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        }
        catch (Throwable ignored)
        {
        }

        if (this.cfgSaved)
        {
            try
            {
                this.cfg("COUNT_BY_ZONE").setBooleanValue(this.origCount);
                this.cfg("CLIP_SCHEMATIC_RENDER").setBooleanValue(this.origClip);
                this.cfg("HIDE_OUTSIDE_SCHEMATICS").setBooleanValue(this.origHideOutside);
                this.cfg("HIDE_OTHER_SECTIONS").setBooleanValue(this.origHideOther);
            }
            catch (Throwable ignored)
            {
            }
        }

        try
        {
            ZoneFocusFiles.clear(this.file);

            // Миграция делает Zone Focus копию «.bak-0.0.9» старого файла — наш мусор, если её раньше не было
            Path bak = FabricLoader.getInstance().getConfigDir().resolve("zonefocus_zones.json.bak-0.0.9");

            if (! this.bakExisted)
            {
                Files.deleteIfExists(bak);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    // ===== Помощники =====

    private void removeAllPlacements()
    {
        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }
    }

    private ConfigBoolean cfg(String name) throws ReflectiveOperationException
    {
        return (ConfigBoolean) Class.forName(CFG).getField(name).get(null);
    }

    /** Группа A+B общим габаритом (шаги 22–23). */
    private Object group;
    /** Ручка разреза в 3D {ось, номер, x, y, dx, dy, двигается}; протяжка — откуда и что. */
    private float[] handle;
    private int dragFrom;
    private int dragAxis;
    private int dragIndex;
    private double lineX;
    private double lineY;
    private double lineStep;

    /** Нажать / отпустить левую кнопку настоящим путём мыши (MouseHandler.onButton), курсор уже на месте. */
    private void press(Minecraft mc)
    {
        mc.mouseHandler.onButton(mc.getWindow().handle(), new net.minecraft.client.input.MouseButtonInfo(
                com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0), com.mojang.blaze3d.platform.InputConstants.PRESS);
    }

    private void release(Minecraft mc)
    {
        mc.mouseHandler.onButton(mc.getWindow().handle(), new net.minecraft.client.input.MouseButtonInfo(
                com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0), com.mojang.blaze3d.platform.InputConstants.RELEASE);
    }

    /** Поле объекта или его предков, в том числе закрытое. */
    private static Object fieldOf(Object target, String name) throws ReflectiveOperationException
    {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }

        throw new NoSuchFieldException(name);
    }

    private static Object zm() throws ReflectiveOperationException
    {
        return Class.forName(ZM).getMethod("getInstance").invoke(null);
    }

    private static List<?> sections(Object zm) throws ReflectiveOperationException
    {
        return (List<?>) call(zm, "sections");
    }

    private static Object zmStatic(String name, Object... args) throws ReflectiveOperationException
    {
        return invoke(Class.forName(ZM), null, name, args);
    }

    private static void callIfPresent(Object target, String name)
    {
        try
        {
            call(target, name);
        }
        catch (ReflectiveOperationException ignored)
        {
        }
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException
    {
        return invoke(target.getClass(), target, name, args);
    }

    /** Публичный метод по имени и фактическим аргументам (перегрузки различаются по типам). */
    private static Object invoke(Class<?> type, Object target, String name, Object[] args) throws ReflectiveOperationException
    {
        for (Method m : type.getMethods())
        {
            if (! m.getName().equals(name) || m.getParameterCount() != args.length
                    || (target == null && ! Modifier.isStatic(m.getModifiers())))
            {
                continue;
            }

            Class<?>[] params = m.getParameterTypes();
            boolean ok = true;

            for (int i = 0; i < params.length && ok; ++i)
            {
                ok = args[i] == null ? ! params[i].isPrimitive() : wrap(params[i]).isInstance(args[i]);
            }

            if (ok)
            {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }

        throw new NoSuchMethodException(type.getSimpleName() + "." + name + "/" + args.length);
    }

    private static Class<?> wrap(Class<?> c)
    {
        if (! c.isPrimitive())
        {
            return c;
        }

        return c == int.class ? Integer.class : c == boolean.class ? Boolean.class : c == long.class ? Long.class
                : c == double.class ? Double.class : c == float.class ? Float.class : c == byte.class ? Byte.class
                : c == short.class ? Short.class : Character.class;
    }

    /** Габарит размещения как {minX, minY, minZ, maxX, maxY, maxZ}. */
    private int[] enclosing(SchematicPlacement placement) throws ReflectiveOperationException
    {
        return zoneBox(zmStatic("enclosingBox", placement));
    }

    private static int[] zoneBox(Object zb) throws ReflectiveOperationException
    {
        return new int[] { (int) call(zb, "minX"), (int) call(zb, "minY"), (int) call(zb, "minZ"),
                (int) call(zb, "maxX"), (int) call(zb, "maxY"), (int) call(zb, "maxZ") };
    }

    private int[] center(int[] b)
    {
        return new int[] { (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2 };
    }

    /** Центр активной зоны участка по индексу (ZoneManager.activeZone(Section)). */
    private int[] activeZoneCenter(int sectionIndex) throws ReflectiveOperationException
    {
        Object zm = zm();
        Object zone = call(zm, "activeZone", sections(zm).get(sectionIndex));

        if (zone == null)
        {
            throw new IllegalStateException("у участка " + sectionIndex + " нет активной зоны");
        }

        Vec3 c = (Vec3) call(zone, "center");
        return new int[] { (int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z) };
    }

    private static boolean contains(int[] p) throws ReflectiveOperationException
    {
        return (boolean) Class.forName(CLIP).getMethod("contains", int.class, int.class, int.class).invoke(null, p[0], p[1], p[2]);
    }

    private static int[] bounds(Box box)
    {
        BlockPos a = box.getPos1();
        BlockPos b = box.getPos2();
        return new int[] { Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()) };
    }

    private boolean allInside(List<Box> boxes, int[] outer)
    {
        if (boxes == null)
        {
            return false;
        }

        for (Box box : boxes)
        {
            int[] b = bounds(box);

            if (b[0] < outer[0] || b[1] < outer[1] || b[2] < outer[2] || b[3] > outer[3] || b[4] > outer[4] || b[5] > outer[5])
            {
                return false;
            }
        }

        return true;
    }

    private boolean anyTouches(List<Box> boxes, int[] other)
    {
        if (boxes == null)
        {
            return false;
        }

        for (Box box : boxes)
        {
            int[] b = bounds(box);

            if (b[0] <= other[3] && b[3] >= other[0] && b[1] <= other[4] && b[4] >= other[1] && b[2] <= other[5] && b[5] >= other[2])
            {
                return true;
            }
        }

        return false;
    }

    private String describe(List<Box> boxes)
    {
        if (boxes == null)
        {
            return "null";
        }

        StringBuilder sb = new StringBuilder("коробок " + boxes.size());

        for (Box box : boxes)
        {
            sb.append(' ').append(java.util.Arrays.toString(bounds(box)));
        }

        return sb.toString();
    }

    private int schematicChunks()
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        return world == null ? 0 : ((fi.dy.masa.litematica.world.ChunkManagerSchematic) world.getChunkSource()).getLoadedChunksCount();
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(TAG + "снимок " + name);
        // Чистый кадр для описания выпуска: снимок под своим именем и без строки «Снимок экрана сохранён» в чате
        // (она приходила в следующий кадр)
        mc.gui.hud.getChat().clearMessages(false);
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }
}
