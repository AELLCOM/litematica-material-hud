package com.aell.layerlist.dev;

import com.aell.layerlist.compat.ZoneFocusFiles;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.lang.reflect.Field;
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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Режим {@code zfviews}: виды «Управления зонами» Zone Focus (запрос 10.10.2026). В 3D — кнопки «Изометрия»,
 * «Сверху», «Спереди», как у превью библиотеки; в 2D под клетками карты — снимок схемы строго сверху: мышью не
 * крутится, при двух срезах Y срезан по текущему, у повёрнутого и отражённого размещения повёрнут и отражён, как
 * постройка в мире. Видимое превью держит модель, второе закрыто. Нужен Zone Focus 0.0.8+ в {@code run/mods};
 * снимки экрана — для глаза.
 */
@Environment(EnvType.CLIENT)
public final class ZfViewsTest implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;
    private Path file;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "ZFVIEWS (виды «Управления зонами»: 2D-снимок сверху, кнопки 3D)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (! ZoneFocusFiles.available())
        {
            CovOut.log("[zfviews] нет Zone Focus 0.0.8+ в run/mods");
            return false;
        }

        this.file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic").toAbsolutePath().normalize();

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        // Сетка 3×2×2: два среза по высоте — снимок сверху срезается по текущему
        ZoneFocusFiles.clear(this.file);
        ZoneFocusFiles.split(this.file, 3, 2, 2);
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(this.file);
        this.placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(6, 0, 6), "zfviews", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        this.nextAt = Util.getMillis() + 3000L;
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
                case 0 -> wait = this.open(mc, false);
                case 1 ->
                {
                    Screen gui = mc.gui.screen();
                    Object top = field(gui, "topView");
                    this.check("2D: снимок сверху создан", top != null, "");
                    Object widget = field(top, "widget");
                    this.check("2D: модель снимка собрана", (boolean) call(widget, "hasModel"), "");
                    this.check("2D: 3D-превью не заводилось", field(gui, "preview") == null, "");
                    this.check("2D: кнопок видов нет", ! visible(((Object[]) field(gui, "views"))[0]), "");
                    shot(mc, "2D — снимок сверху, срез 1 из 2");

                    // Мышь не трогает камеру снимка
                    Object camera = field(widget, "camera");
                    Object before = call(camera, "key");
                    int[] rect = (int[]) call(widget, "bounds");
                    double cx = rect[0] + rect[2] / 2.0D;
                    double cy = rect[1] + rect[3] / 2.0D;
                    boolean clicked = (boolean) top.getClass().getMethod("mouseClicked", double.class, double.class, int.class)
                            .invoke(top, cx, cy, 0);
                    top.getClass().getMethod("mouseDragged", double.class, double.class, int.class, double.class, double.class)
                            .invoke(top, cx + 30.0D, cy + 20.0D, 0, 30.0D, 20.0D);
                    boolean scrolled = (boolean) top.getClass().getMethod("mouseScrolled", double.class, double.class, double.class)
                            .invoke(top, cx, cy, 3.0D);
                    this.check("2D: снимок не вращается и не масштабируется мышью",
                            ! clicked && ! scrolled && before.equals(call(camera, "key")), before + " → " + call(camera, "key"));
                    this.check("2D: при двух срезах — снимок срезан по первому", (int) call(widget, "layerCut") != Integer.MAX_VALUE,
                            "срез " + call(widget, "layerCut"));

                    @SuppressWarnings("unchecked")
                    List<Object> slices = (List<Object>) field(gui, "sliceButtons");
                    this.check("2D: кнопки срезов Y есть", slices.size() == 2, "кнопок " + slices.size());
                    this.cut = (int) call(widget, "layerCut");
                    this.clickWidget(mc, slices.get(1));
                    wait = 3500L;
                }
                case 2 ->
                {
                    Object widget = field(field(mc.gui.screen(), "topView"), "widget");
                    int cut = (int) call(widget, "layerCut");
                    this.check("2D: срез 2 — снимок срезан выше", cut > this.cut, this.cut + " → " + cut);
                    shot(mc, "2D — снимок сверху, срез 2 из 2");

                    // Повёрнутое на 90° и отражённое размещение — снимок поворачивается и отражается, как в мире
                    mc.gui.setScreen(null);
                    this.placement.setMirror(Mirror.FRONT_BACK, (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
                    this.placement.setRotation(Rotation.CLOCKWISE_90, (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
                    wait = 1500L;
                }
                case 3 -> wait = this.open(mc, false);
                case 4 ->
                {
                    Object widget = field(field(mc.gui.screen(), "topView"), "widget");
                    Object key = call(field(widget, "camera"), "key");
                    float yaw = (float) call(key, "yaw");
                    // Поворот по часовой на 90° — верх кадра у той стороны схемы, что в мире смотрит на север (запад схемы)
                    this.check("2D, поворот 90°: камера снимка повёрнута (рыскание 90)", Math.abs(yaw - 90.0F) < 0.01F, "рыскание " + yaw);
                    // Отражение по X схемы до поворота на четверть — после поворота это вертикаль экрана
                    this.check("2D, отражение FRONT_BACK + 90°: картинка отражена по вертикали",
                            (boolean) field(widget, "flipY") && ! (boolean) field(widget, "flipX"),
                            "flipX " + field(widget, "flipX") + ", flipY " + field(widget, "flipY"));
                    shot(mc, "2D — повёрнуто на 90° и отражено");
                    this.combo = 0;
                    this.setCombo();
                    wait = 600L;
                }
                case 41 ->
                {
                    // Все 12 сочетаний поворота и отражения: два угловых блока схемы на снимке — там же, где на карте
                    this.checkOrientation(mc);

                    if (++this.combo < ROTATIONS.length * MIRRORS.length)
                    {
                        this.setCombo();
                        this.nextAt = now + 600L;
                        return false;
                    }

                    this.clickWidget(mc, field(mc.gui.screen(), "view3d"));
                    wait = 6000L;
                }
                case 5 ->
                {
                    Screen gui = mc.gui.screen();
                    Object preview = field(gui, "preview");
                    Object[] views = (Object[]) field(gui, "views");
                    this.check("3D: превью создано", preview != null, "");
                    this.check("3D: кнопки «Изометрия», «Сверху», «Спереди» видны", visible(views[0]) && visible(views[1]) && visible(views[2]), "");
                    this.check("3D: по умолчанию изометрия, кнопка выбрана",
                            "ISOMETRIC".equals(view(preview)) && (boolean) call(views[0], "isSelected"), view(preview));
                    Object top = field(gui, "topView");
                    this.check("3D: снимок 2D закрыт (видеопамять)", ! (boolean) call(field(top, "widget"), "hasModel"), "");
                    shot(mc, "3D — изометрия");
                    this.clickWidget(mc, views[1]);
                    wait = 2500L;
                }
                case 6 ->
                {
                    Object[] views = (Object[]) field(mc.gui.screen(), "views");
                    Object preview = field(mc.gui.screen(), "preview");
                    this.check("3D: «Сверху» — камера сверху, кнопка выбрана",
                            "TOP".equals(view(preview)) && (boolean) call(views[1], "isSelected") && ! (boolean) call(views[0], "isSelected"), view(preview));
                    shot(mc, "3D — сверху");
                    this.clickWidget(mc, views[2]);
                    wait = 2500L;
                }
                case 7 ->
                {
                    Object[] views = (Object[]) field(mc.gui.screen(), "views");
                    Object preview = field(mc.gui.screen(), "preview");
                    this.check("3D: «Спереди» — камера спереди, кнопка выбрана",
                            "FRONT".equals(view(preview)) && (boolean) call(views[2], "isSelected"), view(preview));
                    shot(mc, "3D — спереди");
                    this.clickWidget(mc, views[0]);
                    wait = 1500L;
                }
                case 8 ->
                {
                    Object preview = field(mc.gui.screen(), "preview");
                    this.check("3D: «Изометрия» — обратно", "ISOMETRIC".equals(view(preview)), view(preview));
                    this.clickWidget(mc, field(mc.gui.screen(), "view2d"));
                    wait = 6000L;
                }
                case 9 ->
                {
                    Screen gui = mc.gui.screen();
                    Object top = field(gui, "topView");
                    this.check("снова 2D: снимок собран заново", (boolean) call(field(top, "widget"), "hasModel"), "");
                    this.check("снова 2D: 3D-превью закрыто", ! (boolean) call(field(field(gui, "preview"), "widget"), "hasModel"), "");
                    shot(mc, "2D — снова, после 3D");
                    mc.gui.setScreen(null);
                    wait = 500L;
                }
                default ->
                {
                    CovOut.log("[zfviews] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            CovOut.log("[zfviews] итог: FAIL");
            mc.gui.setScreen(null);
            return true;
        }

        this.nextAt = now + wait;
        this.stage = this.stage == 4 ? 41 : this.stage == 41 ? 5 : this.stage + 1;
        return false;
    }

    private int cut;
    private int combo;
    private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};
    private static final Mirror[] MIRRORS = {Mirror.NONE, Mirror.LEFT_RIGHT, Mirror.FRONT_BACK};

    private void setCombo()
    {
        this.placement.setMirror(MIRRORS[this.combo % MIRRORS.length], (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
        this.placement.setRotation(ROTATIONS[this.combo / MIRRORS.length], (fi.dy.masa.malilib.gui.interfaces.IMessageConsumer) null);
    }

    /**
     * Угловые блоки схемы (северо-запад и северо-восток файла, верхний слой) — точка на снимке против точки
     * на карте: мировая позиция блока по преобразованию Litematica (сначала отражение, потом поворот) в
     * масштабе карты. Снимок отражается при копировании — отражаем и точку.
     */
    private void checkOrientation(Minecraft mc) throws ReflectiveOperationException
    {
        Object gui = mc.gui.screen();
        Object top = field(gui, "topView");
        Object widget = field(top, "widget");
        Object editor = field(gui, "editor");
        Object bounds = call(editor, "bounds");
        int[] rect = (int[]) call(widget, "bounds");
        int[] map = (int[]) call(editor, "mapRect");
        boolean flipX = (boolean) field(widget, "flipX");
        boolean flipY = (boolean) field(widget, "flipY");
        LitematicaSchematic schematic = this.placement.getSchematic();
        net.minecraft.core.BlockPos min = (net.minecraft.core.BlockPos) Class.forName("com.aell.zonefocus.zone.FileZones")
                .getMethod("enclosingMin", LitematicaSchematic.class).invoke(null, schematic);
        net.minecraft.core.Vec3i size = schematic.getTotalSize();
        int sx = Math.abs(size.getX());
        int sz = Math.abs(size.getZ());
        int bminX = (int) call(bounds, "minX");
        int bminZ = (int) call(bounds, "minZ");
        double scale = map[2] / (double) (int) call(bounds, "sizeX");
        String combo = this.placement.getRotation() + "+" + this.placement.getMirror();
        boolean ok = true;
        StringBuilder detail = new StringBuilder();

        for (int[] corner : new int[][] { {0, 0}, {sx - 1, 0}, {0, sz - 1} })
        {
            float[] p = (float[]) widget.getClass().getMethod("project", float.class, float.class, float.class)
                    .invoke(widget, corner[0] + 0.5F, 0.0F, corner[1] + 0.5F);
            double imageX = flipX ? rect[0] + rect[0] + rect[2] - p[0] : p[0];
            double imageY = flipY ? rect[1] + rect[1] + rect[3] - p[1] : p[1];
            net.minecraft.core.BlockPos world = fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos(
                    new net.minecraft.core.BlockPos(corner[0] + min.getX(), min.getY(), corner[1] + min.getZ()),
                    this.placement.getMirror(), this.placement.getRotation()).offset(this.placement.getOrigin());
            double mapX = map[0] + (world.getX() - bminX + 0.5D) * scale;
            double mapY = map[1] + (world.getZ() - bminZ + 0.5D) * scale;
            boolean near = Math.abs(imageX - mapX) <= scale + 1.0D && Math.abs(imageY - mapY) <= scale + 1.0D;
            ok &= near;
            detail.append(String.format(java.util.Locale.ROOT, " угол %d/%d: снимок %.0f×%.0f, карта %.0f×%.0f;",
                    corner[0], corner[1], imageX, imageY, mapX, mapY));
        }

        this.check("2D, " + combo + ": углы схемы на снимке там же, где на карте", ok, detail.toString().trim());

        if (this.combo == 5 || this.combo == 10)
        {
            shot(mc, "2D — " + combo);
        }
    }

    /** «Управление зонами» размещения, 2D или 3D; ждём сборку модели. */
    private long open(Minecraft mc, boolean in3d) throws ReflectiveOperationException
    {
        Class<?> editor = Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor");
        editor.getField("view3d").setBoolean(null, in3d);
        editor.getField("showProgress").setBoolean(null, false);
        Screen gui = (Screen) Class.forName("com.aell.zonefocus.gui.manage.GuiZoneManager")
                .getConstructor(Screen.class, SchematicPlacement.class).newInstance(null, this.placement);
        mc.gui.setScreen(gui);
        return 7000L;
    }

    private static String view(Object preview) throws ReflectiveOperationException
    {
        return String.valueOf(call(preview, "view"));
    }

    private static boolean visible(Object widget) throws ReflectiveOperationException
    {
        return widget != null && (boolean) call(widget, "visible");
    }

    private static Object call(Object owner, String method) throws ReflectiveOperationException
    {
        var m = owner.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(owner);
    }

    private void clickWidget(Minecraft mc, Object widget) throws ReflectiveOperationException
    {
        int x = (int) call(widget, "x");
        int y = (int) call(widget, "y");
        int w = (int) call(widget, "w");
        int h = (int) call(widget, "h");
        CovOut.log("[zfviews] щелчок по " + widget.getClass().getSimpleName() + " " + x + "×" + y + " " + w + "×" + h);
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
        CovOut.log("[zfviews] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[zfviews] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        ZoneFocusFiles.clear(this.file);

        try
        {
            Class.forName("com.aell.zonefocus.gui.manage.ZoneEditor").getField("view3d").setBoolean(null, false);
        }
        catch (ReflectiveOperationException ignored)
        {
        }
    }
}
