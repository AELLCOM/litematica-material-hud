package com.aell.layerlist.schematics.place;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Вид сверху для редактора размещения: своя поза камеры, которую миксины
 * {@code mixin.schematics.MixinTopView*} подставляют вместо позы игрока, пока режим включён.
 * Взгляд строго вниз, север вверху экрана; игрока видно (камера «отвязана»), рука, прицел,
 * туман дальности и (по опции) облака на это время убраны.
 *
 * <p>Режим привязан к экрану-владельцу: {@link #active()} сам гасит его, как только владелец
 * перестал быть текущим экраном. Так вид сверху не переживёт редактор, даже если тот закрылся
 * мимо своего {@code removed()} — выход из мира, падение в обработчике, чужой {@code setScreen}.
 */
@Environment(EnvType.CLIENT)
public final class TopViewCamera
{
    /** Рыскание 180° (на север) и тангаж 90° (вниз): верх экрана — север, право — восток. */
    public static final float YAW = 180.0F;
    public static final float PITCH = 90.0F;

    /** Доля свободной части окна, которую занимает габарит после подбора высоты. */
    private static final double FILL = 0.7;

    /** Ниже этой высоты над верхом схемы камера не опускается. */
    private static final double MIN_HEIGHT = 6.0;

    /** Щелчок колеса меняет высоту во столько раз. */
    private static final double ZOOM_STEP = 0.85;

    /** Поле вокруг габарита, когда камера догоняет схему, в блоках. */
    private static final double VIEW_MARGIN = 1.0;

    private static @Nullable Screen owner;
    private static double x;
    private static double y;
    private static double z;

    /** Верх схемы (плоскость над габаритом): от него отсчитываются пределы высоты. */
    private static double floor;

    private TopViewCamera() {}

    /**
     * Включить для экрана {@code screen} и встать над габаритом так, чтобы он занял ~70 %
     * свободной части окна ({@code left..right × top..bottom} в GUI-координатах экрана
     * {@code screenW × screenH}); высота — не больше дальности прорисовки.
     */
    public static void enable(Screen screen, PlacementOps.Bounds b, double left, double top, double right,
            double bottom, int screenW, int screenH)
    {
        owner = screen;
        frame(b, left, top, right, bottom, screenW, screenH);
    }

    public static void disable(Screen screen)
    {
        if (owner == screen)
        {
            owner = null;
        }
    }

    /** Режим включён, и экран-владелец всё ещё открыт; иначе режим гаснет здесь же. */
    public static boolean active()
    {
        if (owner == null)
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.screen != owner || mc.level == null || mc.player == null)
        {
            owner = null;

            return false;
        }

        return true;
    }

    public static boolean hideClouds()
    {
        return active() && SchematicsConfig.TOP_HIDE_CLOUDS.getBooleanValue();
    }

    public static double x()
    {
        return x;
    }

    public static double y()
    {
        return y;
    }

    public static double z()
    {
        return z;
    }

    public static Vec3 position()
    {
        return new Vec3(x, y, z);
    }

    /** Подбор высоты и сдвига под габарит (см. {@link #enable}). */
    public static void frame(PlacementOps.Bounds b, double left, double top, double right, double bottom,
            int screenW, int screenH)
    {
        double tan = tanHalfFov();
        double aspect = aspect();

        // Свободная часть окна в долях экрана от −1 до 1 (верх экрана — +1)
        double nl = left * 2.0 / screenW - 1.0;
        double nr = right * 2.0 / screenW - 1.0;
        double nt = 1.0 - top * 2.0 / screenH;
        double nb = 1.0 - bottom * 2.0 / screenH;

        double spanX = Math.max(0.05, nr - nl);
        double spanY = Math.max(0.05, nt - nb);

        // На глубине d экран видит по горизонтали 2·d·tan·aspect блоков, по вертикали 2·d·tan
        double byWidth = b.sizeX() / (FILL * spanX * tan * aspect);
        double byHeight = b.sizeZ() / (FILL * spanY * tan);
        double h = clampHeight(Math.max(byWidth, byHeight));

        floor = b.maxY() + 1.0;
        y = floor + h;

        // Центр габарита — в центр свободной части, а не всего окна (справа панель)
        double cx = (nl + nr) * 0.5;
        double cy = (nt + nb) * 0.5;
        double centerX = (b.minX() + b.maxX() + 1) * 0.5;
        double centerZ = (b.minZ() + b.maxZ() + 1) * 0.5;

        x = centerX - cx * h * tan * aspect;
        z = centerZ + cy * h * tan;
        clampToPlayer();
    }

    /** Верх схемы сдвинулся (PgUp/PgDn, «к земле»): камера едет следом по высоте. */
    public static void follow(PlacementOps.Bounds b)
    {
        double newFloor = b.maxY() + 1.0;

        if (newFloor != floor)
        {
            y += newFloor - floor;
            floor = newFloor;
        }
    }

    /**
     * Габарит ушёл из свободной части окна (стрелки, поворот, сетка чанков, отмена шага) — сдвинуть камеру
     * ровно настолько, чтобы его верх снова был виден с полем в блок.
     * Высота не меняется: масштаб, выбранный колесом, остаётся.
     */
    public static void keepInView(PlacementOps.Bounds b, double left, double top, double right, double bottom,
            int screenW, int screenH)
    {
        double depth = y - (b.maxY() + 1.0);

        if (depth <= 1.0E-3 || screenW <= 0 || screenH <= 0)
        {
            return;
        }

        double tan = tanHalfFov();
        double perX = depth * tan * aspect();
        double perZ = depth * tan;

        // Свободная часть окна на высоте верха схемы, в блоках (верх экрана — север, меньший Z)
        double viewMinX = x + (left * 2.0 / screenW - 1.0) * perX;
        double viewMaxX = x + (right * 2.0 / screenW - 1.0) * perX;
        double viewMinZ = z - (1.0 - top * 2.0 / screenH) * perZ;
        double viewMaxZ = z - (1.0 - bottom * 2.0 / screenH) * perZ;

        x += inside(b.minX(), b.maxX() + 1.0, viewMinX + VIEW_MARGIN, viewMaxX - VIEW_MARGIN);
        z += inside(b.minZ(), b.maxZ() + 1.0, viewMinZ + VIEW_MARGIN, viewMaxZ - VIEW_MARGIN);
        clampToPlayer();
    }

    /**
     * Сдвиг окна {@code viewLo..viewHi}, после которого отрезок {@code lo..hi} в нём; отрезок длиннее окна —
     * окно только не вылезает за его края (приближенный колесом кусок большой схемы не уезжает в центр).
     */
    private static double inside(double lo, double hi, double viewLo, double viewHi)
    {
        if (hi - lo > viewHi - viewLo)
        {
            return lo > viewLo ? lo - viewLo : hi < viewHi ? hi - viewHi : 0.0;
        }

        if (lo < viewLo)
        {
            return lo - viewLo;
        }

        return hi > viewHi ? hi - viewHi : 0.0;
    }

    /** Панорама: сдвинуть камеру по горизонтали. */
    public static void pan(double dx, double dz)
    {
        x += dx;
        z += dz;
        clampToPlayer();
    }

    /**
     * Колесо: выше/ниже в {@link #ZOOM_STEP} раз за щелчок, к курсору — точка {@code focus} на
     * плоскости {@code planeY} остаётся под курсором (в перспективе вниз её смещение от камеры
     * пропорционально высоте над плоскостью).
     */
    public static void zoom(double notches, @Nullable Vec3 focus, double planeY)
    {
        double target = floor + clampHeight((y - floor) * Math.pow(ZOOM_STEP, notches));
        double before = y - planeY;

        if (focus != null && before > 1.0E-3)
        {
            double k = (target - planeY) / before;

            x = focus.x - (focus.x - x) * k;
            z = focus.z - (focus.z - z) * k;
        }

        y = target;
        clampToPlayer();
    }

    private static double clampHeight(double h)
    {
        return Math.max(MIN_HEIGHT, Math.min(maxHeight(), h));
    }

    /** Дальность прорисовки в блоках: выше смысла нет — земля под камерой уже не строится. */
    private static double maxHeight()
    {
        return Math.max(MIN_HEIGHT * 2.0, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
    }

    /** Чанки грузятся вокруг игрока, не камеры: дальше дальности прорисовки от него — пустота. */
    private static void clampToPlayer()
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            return;
        }

        double limit = maxHeight();
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > limit)
        {
            x = player.getX() + dx * limit / distance;
            z = player.getZ() + dz * limit / distance;
        }
    }

    private static double tanHalfFov()
    {
        return Math.tan(Math.toRadians(CursorRay.fovDegrees() * 0.5));
    }

    private static double aspect()
    {
        Window window = Minecraft.getInstance().getWindow();

        return window.getHeight() > 0 ? (double) window.getWidth() / window.getHeight() : 1.0;
    }
}
