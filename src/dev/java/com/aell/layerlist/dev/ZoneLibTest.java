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
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Rotation;

/**
 * Режим {@code zonelib}: зоны Zone Focus в библиотеке и 3D в «Управлении зонами» (запрос 09.10.2026). Нужен
 * Zone Focus 0.0.8+ в {@code run/mods}. Щелчки — через {@code MouseHandler.onButton} с номерами SDL.
 * <ol>
 *   <li>Библиотека, «Портал.litematic» не размещён: панель разбиения, 3×1×2 в полях, щелчок «Разбить» — сетка
 *       запомнена для файла, на модели 6 каркасов с номерами (снимок);</li>
 *   <li>схему размещают — Zone Focus сам раздаёт ей ту же сетку;</li>
 *   <li>перевод координат: у размещения, повёрнутого на 90°, габарит в мире переводится в габарит схемы
 *       от угла 0 (оси X и Z меняются местами);</li>
 *   <li>«Управление зонами», 3D: превью с каркасами (снимок), щелчок по каркасу — активная зона;</li>
 *   <li>«Зоны» в углу превью (3D и библиотека) убирают и возвращают каркасы — постройка без полос;</li>
 *   <li>прогресс постройки: зона 1 построена целиком, зона 2 наполовину — в библиотеке 100 %, начата, 0 %;</li>
 *   <li>«Отменить разбиение» двумя щелчками — сетки нет ни у файла, ни у размещения.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class ZoneLibTest implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private Path file;
    private SchematicPlacement placement;
    private int target = -1;

    @Override
    public String name()
    {
        return "ZONELIB (зоны в библиотеке и 3D)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (! ZoneFocusFiles.available())
        {
            CovOut.log("[zonelib] нет Zone Focus 0.0.8+ в run/mods");
            return false;
        }

        this.file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic").toAbsolutePath().normalize();

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        ZoneFocusFiles.clear(this.file);
        this.nextAt = Util.getMillis() + 2000L;
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
                    this.check("библиотека открыта, файл найден", screen instanceof GuiSchematics && entry != null,
                            screen + " / " + entry);
                    this.check("файл не разбит", ! ZoneFocusFiles.hasZones(this.file), "");
                    Method select = GuiSchematics.class.getDeclaredMethod("select", SchematicEntry.class);
                    select.setAccessible(true);
                    select.invoke(screen, entry);
                    wait = 4000L;
                }
                case 2 ->
                {
                    // Кнопка «Разбить на зоны» в правой панели; в маленьком окне она ниже края — панель
                    // прокручивается, как у игрока
                    Object info = field(GuiUtils.getCurrentScreen(), "info");
                    Object zones = field(info, "zones");
                    String label = (String) zones.getClass().getMethod("label").invoke(zones);
                    boolean visible = (boolean) zones.getClass().getMethod("visible").invoke(zones);
                    String want = net.minecraft.client.resources.language.I18n.get("layerlist.schematics.menu.zones_split");
                    this.check("в правой панели кнопка «Разбить на зоны»", visible && label.equals(want), label);
                    com.aell.layerlist.ui.kit.Widget pane = (com.aell.layerlist.ui.kit.Widget) info;
                    for (int i = 0; i < 30; ++i)
                    {
                        pane.mouseScrolled(pane.x() + 4.0D, pane.y() + 4.0D, -1.0D);
                    }
                    wait = 800L;
                }
                case 3 ->
                {
                    this.clickWidget(mc, field(field(GuiUtils.getCurrentScreen(), "info"), "zones"));
                    wait = 2000L;
                }
                case 4 ->
                {
                    Object pane = this.pane();
                    int[] draft = (int[]) field(pane, "draft");
                    draft[0] = 3;
                    draft[1] = 1;
                    draft[2] = 2;
                    // Поля возьмут значения из черновика; щелчок по «Разбить» — настоящим путём мыши
                    Method sync = pane.getClass().getDeclaredMethod("syncZoneFields", boolean.class);
                    sync.setAccessible(true);
                    sync.invoke(pane, true);
                    shot(mc, "библиотека — черновик 3×1×2 на превью");
                    this.clickWidget(mc, field(pane, "zoneSplit"));
                    wait = 2000L;
                }
                case 5 ->
                {
                    int[] grid = ZoneFocusFiles.grid(this.file);
                    this.check("щелчок «Разбить» запомнил сетку файла 3×1×2", Arrays.equals(grid, new int[] { 3, 1, 2 }),
                            Arrays.toString(grid));
                    Object overlay = field(this.pane(), "zones");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("на превью 6 зон", count == 6, "зон " + count);
                    shot(mc, "библиотека — зоны с номерами на превью");
                    mc.gui.setScreen(null);
                    LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(this.file);
                    this.placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(4, 0, 4), "zonelib", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                    DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                    wait = 2500L;
                }
                case 6 ->
                {
                    Object manager = zfManager();
                    boolean has = (boolean) manager.getClass().getMethod("hasZones").invoke(manager);
                    int zones = (int) manager.getClass().getMethod("getZoneCount").invoke(manager);
                    this.check("размещение получило сетку файла само (6 зон)", has && zones == 6, "зон " + zones);

                    // Поворот 90°: габарит в мире → габарит схемы от угла 0, X и Z меняются местами
                    LitematicaSchematic schematic = this.placement.getSchematic();
                    SchematicPlacement turned = SchematicPlacement.createFor(schematic, this.placement.getOrigin().offset(200, 0, 0),
                            "zonelib-turned", true, true);
                    turned.setRotation(Rotation.CLOCKWISE_90, (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
                    Class<?> fz = Class.forName("com.aell.zonefocus.zone.FileZones");
                    Class<?> zm = Class.forName("com.aell.zonefocus.zone.ZoneManager");
                    Object bounds = zm.getMethod("enclosingBox", SchematicPlacement.class).invoke(null, turned);
                    int[][] local = (int[][]) fz.getMethod("toLocal", SchematicPlacement.class, List.class).invoke(null, turned, List.of(bounds));
                    net.minecraft.core.Vec3i size = schematic.getTotalSize();
                    int[] expected = { 0, 0, 0, Math.abs(size.getX()) - 1, Math.abs(size.getY()) - 1, Math.abs(size.getZ()) - 1 };
                    this.check("поворот 90°: габарит мира переводится в габарит схемы", Arrays.equals(local[0], expected),
                            Arrays.toString(local[0]) + " ждали " + Arrays.toString(expected));

                    // Отражение + поворот: коробка схемы → мир прямым преобразованием Litematica → обратно та же
                    SchematicPlacement mirrored = SchematicPlacement.createFor(schematic, this.placement.getOrigin().offset(400, 0, 0),
                            "zonelib-mirrored", true, true);
                    mirrored.setMirror(net.minecraft.world.level.block.Mirror.FRONT_BACK, (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
                    mirrored.setRotation(Rotation.COUNTERCLOCKWISE_90, (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
                    int[] box = { 2, 1, 3, 11, 5, 6 };
                    net.minecraft.core.BlockPos a = fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos(
                            new net.minecraft.core.BlockPos(box[0], box[1], box[2]), mirrored.getMirror(), mirrored.getRotation()).offset(mirrored.getOrigin());
                    net.minecraft.core.BlockPos c = fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos(
                            new net.minecraft.core.BlockPos(box[3], box[4], box[5]), mirrored.getMirror(), mirrored.getRotation()).offset(mirrored.getOrigin());
                    Object world = Class.forName("com.aell.zonefocus.zone.ZoneBox").getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                            .newInstance(Math.min(a.getX(), c.getX()), Math.min(a.getY(), c.getY()), Math.min(a.getZ(), c.getZ()),
                                    Math.max(a.getX(), c.getX()), Math.max(a.getY(), c.getY()), Math.max(a.getZ(), c.getZ()));
                    int[][] back = (int[][]) fz.getMethod("toLocal", SchematicPlacement.class, List.class).invoke(null, mirrored, List.of(world));
                    // Превью считает от минимального угла регионов, а не от нуля схемы
                    net.minecraft.core.BlockPos min = (net.minecraft.core.BlockPos) fz.getMethod("enclosingMin", LitematicaSchematic.class)
                            .invoke(null, schematic);
                    int[] want = { box[0] - min.getX(), box[1] - min.getY(), box[2] - min.getZ(),
                            box[3] - min.getX(), box[4] - min.getY(), box[5] - min.getZ() };
                    this.check("отражение и поворот: коробка возвращается в координаты схемы", Arrays.equals(back[0], want),
                            Arrays.toString(back[0]) + " ждали " + Arrays.toString(want));

                    // Строим: зона 1 целиком, зона 2 наполовину — блоки схемы в мир (серверный поток)
                    @SuppressWarnings("unchecked")
                    List<Object> boxes = (List<Object>) manager.getClass().getMethod("zoneBoxes").invoke(manager);
                    int[][] build = new int[2][];

                    for (int i = 0; i < 2; ++i)
                    {
                        Object b = boxes.get(i);
                        Class<?> bc = b.getClass();
                        int maxY = (int) bc.getMethod("maxY").invoke(b);
                        int minY = (int) bc.getMethod("minY").invoke(b);
                        build[i] = new int[] { (int) bc.getMethod("minX").invoke(b), minY, (int) bc.getMethod("minZ").invoke(b),
                                (int) bc.getMethod("maxX").invoke(b), i == 0 ? maxY : minY + (maxY - minY) / 2, (int) bc.getMethod("maxZ").invoke(b) };
                    }

                    var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                    var server = mc.getSingleplayerServer();
                    var level = server.getLevel(mc.level.dimension());
                    java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> copy = new java.util.HashMap<>();

                    for (int[] b : build)
                    {
                        for (net.minecraft.core.BlockPos p : net.minecraft.core.BlockPos.betweenClosed(b[0], b[1], b[2], b[3], b[4], b[5]))
                        {
                            var state = schematicWorld.getBlockState(p);

                            if (! state.isAir())
                            {
                                copy.put(p.immutable(), state);
                            }
                        }
                    }

                    server.execute(() -> copy.forEach((p, state) -> level.setBlock(p, state, 2 | 16)));
                    CovOut.log("[zonelib] построено блоков: " + copy.size());

                    // «Управление зонами» в 3D
                    Class<?> editor = Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor");
                    editor.getField("view3d").setBoolean(null, true);
                    editor.getField("showProgress").setBoolean(null, false);
                    Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                            .getConstructor(Screen.class, SchematicPlacement.class).newInstance(null, this.placement);
                    mc.gui.setScreen(gui);
                    wait = 6000L;
                }
                case 7 ->
                {
                    Screen gui = mc.gui.screen();
                    Object preview = field(gui, "preview");
                    this.check("3D-превью создано", preview != null, "");
                    shot(mc, "управление зонами — 3D");

                    // Ищем на карте точку, где под курсором зона, отличная от активной, и щёлкаем в неё
                    Object manager = zfManager();
                    int active = (int) manager.getClass().getMethod("getActiveIndex").invoke(manager);
                    int mapX = (int) field(gui, "mapX");
                    int mapY = (int) field(gui, "mapY");
                    int mapW = (int) field(gui, "mapW");
                    int mapH = (int) field(gui, "mapH");
                    Method pick = preview.getClass().getMethod("pick", double.class, double.class);
                    double[] at = null;

                    for (int y = mapY + 8; y < mapY + mapH - 8 && at == null; y += 4)
                    {
                        for (int x = mapX + 8; x < mapX + mapW - 8; x += 4)
                        {
                            int z = (int) pick.invoke(preview, (double) x, (double) y);

                            if (z >= 0 && z != active)
                            {
                                at = new double[] { x, y };
                                this.target = z;
                                break;
                            }
                        }
                    }

                    this.check("на 3D-превью есть зона под курсором", at != null, "активная " + (active + 1));

                    if (at != null)
                    {
                        SupportTest.click(mc, at[0], at[1]);
                    }

                    wait = 1500L;
                }
                case 8 ->
                {
                    Object manager = zfManager();
                    int active = (int) manager.getClass().getMethod("getActiveIndex").invoke(manager);
                    this.check("щелчок по каркасу в 3D сделал зону активной", active == this.target,
                            "активная " + (active + 1) + ", щёлкнули " + (this.target + 1));
                    Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("showProgress").setBoolean(null, true);
                    wait = 2500L;
                }
                case 9 ->
                {
                    shot(mc, "управление зонами — 3D с прогрессом");
                    Object scanner = Class.forName("com.aell.zonefocus.zone.ZoneScanner").getMethod("getInstance").invoke(null);
                    Method stats = scanner.getClass().getMethod("getStats", int.class);
                    Object s0 = stats.invoke(scanner, 0);
                    this.check("3D с прогрессом: скан запустился сам, зона 1 посчитана", s0 != null, String.valueOf(s0));
                    Object preview = field(mc.gui.screen(), "preview");
                    java.lang.reflect.Method built = preview.getClass().getMethod("builtPercent");
                    int percent = (int) built.invoke(preview);
                    this.check("3D с прогрессом: модель «блок в блок» — построено между 0 и 100 %", percent > 0 && percent < 100,
                            "построено " + percent + "%");
                    // Качество / Оптимизация рядом с 2D / 3D — щелчок переключает настройку Material HUD
                    Object hi = field(mc.gui.screen(), "qualityHi");
                    Object lo = field(mc.gui.screen(), "qualityLo");
                    boolean shown = (boolean) hi.getClass().getMethod("visible").invoke(hi)
                            && (boolean) lo.getClass().getMethod("visible").invoke(lo);
                    boolean before = com.aell.layerlist.api.LayerListApi.isPreviewQuality();
                    this.clickWidget(mc, before ? lo : hi);
                    boolean after = com.aell.layerlist.api.LayerListApi.isPreviewQuality();
                    this.check("3D: «Качество / Оптимизация» в шапке, щелчок переключает", shown && after != before,
                            "было " + before + ", стало " + after);
                    this.clickWidget(mc, before ? hi : lo);
                    // «Зоны» в углу 3D — убрать каркасы
                    this.clickWidget(mc, field(mc.gui.screen(), "frames"));
                    wait = 1500L;
                }
                case 10 ->
                {
                    Object preview = field(mc.gui.screen(), "preview");
                    Object overlay = field(preview, "overlay");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("3D: «Зоны» выкл — каркасов нет", count == 0, "каркасов " + count);
                    shot(mc, "управление зонами — 3D без полос");
                    this.clickWidget(mc, field(mc.gui.screen(), "frames"));
                    Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("showProgress").setBoolean(null, false);
                    wait = 1500L;
                }
                case 11 ->
                {
                    Object preview = field(mc.gui.screen(), "preview");
                    Object overlay = field(preview, "overlay");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("3D: «Зоны» вкл — каркасы вернулись", count == 6, "каркасов " + count);
                    GuiSchematics.open(null);
                    wait = 1500L;
                }
                case 12 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    Method open = GuiSchematics.class.getDeclaredMethod("openZones", SchematicEntry.class);
                    open.setAccessible(true);
                    open.invoke(screen, SchematicLibrary.get().entry(this.file));
                    wait = 4000L;
                }
                case 13 ->
                {
                    int[] p = ZoneFocusFiles.progress(this.file);
                    this.check("библиотека: прогресс зон — 1 готова, 2 начата, 3 не начата",
                            p != null && p.length == 6 && p[0] >= 95 && p[1] > 0 && p[1] < 100 && p[2] == 0, Arrays.toString(p));
                    Object widget = field(this.pane(), "widget");
                    int built = (int) widget.getClass().getMethod("builtPercent").invoke(widget);
                    this.check("библиотека: модель «блок в блок» у размещённой схемы", built > 0 && built < 100, "построено " + built + "%");
                    shot(mc, "библиотека — прогресс постройки зон размещённой схемы");
                    this.clickWidget(mc, field(this.pane(), "zoneFrames"));
                    wait = 1500L;
                }
                case 14 ->
                {
                    Object overlay = field(this.pane(), "zones");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("библиотека: «Зоны» выкл — каркасов нет", count == 0, "каркасов " + count);
                    shot(mc, "библиотека — постройка без полос");
                    this.clickWidget(mc, field(this.pane(), "zoneFrames"));
                    wait = 1500L;
                }
                case 15 ->
                {
                    Object overlay = field(this.pane(), "zones");
                    int count = (int) overlay.getClass().getMethod("count").invoke(overlay);
                    this.check("библиотека: «Зоны» вкл — каркасы вернулись", count == 6, "каркасов " + count);
                    // «Отменить разбиение» — два щелчка (второй подтверждает)
                    this.clickWidget(mc, field(this.pane(), "zoneUndo"));
                    wait = 400L;
                }
                case 16 ->
                {
                    this.clickWidget(mc, field(this.pane(), "zoneUndo"));
                    wait = 1500L;
                }
                case 17 ->
                {
                    Object manager = zfManager();
                    boolean has = (boolean) manager.getClass().getMethod("hasZones").invoke(manager);
                    this.check("«Отменить разбиение»: у файла сетки нет", ZoneFocusFiles.grid(this.file) == null
                            && ! ZoneFocusFiles.hasZones(this.file), "");
                    this.check("«Отменить разбиение»: у размещения зон нет", ! has, "");
                    mc.gui.setScreen(null);
                    wait = 500L;
                }
                default ->
                {
                    CovOut.log("[zonelib] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            CovOut.log("[zonelib] итог: FAIL");
            mc.gui.setScreen(null);
            return true;
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private Object pane() throws ReflectiveOperationException
    {
        return field(GuiUtils.getCurrentScreen(), "preview");
    }

    private static Object zfManager() throws ReflectiveOperationException
    {
        return Class.forName("com.aell.zonefocus.zone.ZoneManager").getMethod("getInstance").invoke(null);
    }

    private void clickWidget(Minecraft mc, Object widget) throws ReflectiveOperationException
    {
        Class<?> c = widget.getClass();
        int x = (int) c.getMethod("x").invoke(widget);
        int y = (int) c.getMethod("y").invoke(widget);
        int w = (int) c.getMethod("w").invoke(widget);
        int h = (int) c.getMethod("h").invoke(widget);
        CovOut.log("[zonelib] щелчок по " + c.getSimpleName() + " " + x + "×" + y + " " + w + "×" + h);
        SupportTest.click(mc, x + w / 2.0D, y + h / 2.0D);
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
        CovOut.log("[zonelib] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[zonelib] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        try
        {
            Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("view3d").setBoolean(null, false);
        }
        catch (ReflectiveOperationException ignored)
        {
        }
    }
}
