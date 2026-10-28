package com.aell.layerlist.schematics.place;

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Облёт для редактора размещения: камера смотрит на опорную точку (по умолчанию центр схемы) с
 * рысканьем и тангажом и держится от неё на расстоянии. Позу, как и у {@link TopViewCamera},
 * подставляет {@code mixin.schematics.MixinTopViewCamera}; рука, прицел и туман дальности на это
 * время убраны теми же миксинами, обводка блока — {@link PlacementOverlay}.
 *
 * <p>Вращение и панорама мышью меняют позу сразу — движение руки и так плавное; колесо, «схема
 * целиком» и вход в облёт задают цель, к которой поза подтягивается за ~{@link #SMOOTH_MS} мс
 * ({@link #advance()}, раз в кадр из миксина). Опорная точка стоит не в центре окна, а в центре его
 * свободной части (справа панель): камера сдвинута вбок в своей плоскости, поэтому и вращение идёт
 * вокруг точки, которую игрок видит посередине мира, а не под панелью.
 *
 * <p>Камера — тело (1.0.7): глаз не прыгает в расчётную позу ({@link #wanted()}), а едет к ней от
 * своего прошлого места через ванильную проверку столкновений с блоками мира, со скольжением вдоль
 * стен, как у игрока. Сквозь землю и стены облёт не проходит: упёрся — стоит у преграды и только
 * поворачивается, пока расчётная поза снова не станет достижимой. Блоки схемы (голограммы) не
 * мешают, жидкости тоже. {@link #position()} — настоящий глаз, по нему же считает лучи
 * {@link CursorRay}, так что курсор всегда совпадает с картинкой.
 *
 * <p>Режим привязан к экрану-владельцу: {@link #active()} гасит его, как только владелец перестал
 * быть текущим экраном, — облёт не переживёт редактор, как бы тот ни закрылся.
 */
@Environment(EnvType.CLIENT)
public final class OrbitCamera
{
    /** Тангаж: 90° — строго сверху; ниже −80° камера уходит под схему и почти переворачивается. */
    public static final float MIN_PITCH = -80.0F;
    public static final float MAX_PITCH = 90.0F;

    /** Ближе этого к опорной точке камера не подходит. */
    public static final double MIN_DISTANCE = 2.0;

    /** Дальний предел — не меньше стольких блоков, даже у крошечной схемы. */
    private static final double FAR_MIN = 48.0;

    /** Дальний предел — столько диагоналей габарита (но не дальше дальности прорисовки). */
    private static final double FAR_DIAGONALS = 4.0;

    /** Щелчок колеса меняет расстояние во столько раз. */
    private static final double ZOOM_STEP = 0.85;

    /** Доля свободной части окна, которую занимает схема после «схема целиком». */
    private static final double FILL = 0.8;

    /** Постоянная времени подтягивания к цели, мс: за 3·{@link #SMOOTH_MS} — 95 % пути. */
    private static final double SMOOTH_MS = 70.0;

    /** Тангаж при входе — взгляд прежней камеры, но не положе и не круче этого. */
    private static final float ENTER_PITCH_MIN = 25.0F;
    private static final float ENTER_PITCH_MAX = 60.0F;

    /** Ниже такой доли вертикали у взгляда панорама — по плоскости экрана, а не по горизонтали. */
    private static final double PAN_PLANE_MIN_Y = 0.25;

    /** Полуребро коробки вокруг глаза: ближе к стене камера не подходит и в блок не заглядывает. */
    private static final double BODY_HALF = 0.25;

    /** Длинный путь за кадр режется на шаги не длиннее этого — блоки ищутся в маленькой коробке. */
    private static final double BODY_STEP = 4.0;

    /** Больше стольких шагов за кадр не делать: остаток пути — в следующем кадре. */
    private static final int BODY_STEPS_MAX = 32;

    private static @Nullable Screen owner;

    private static double pivotX;
    private static double pivotY;
    private static double pivotZ;
    private static double yaw;
    private static double pitch;
    private static double distance;

    private static double targetX;
    private static double targetY;
    private static double targetZ;
    private static double targetYaw;
    private static double targetPitch;
    private static double targetDistance;

    /** Настоящий глаз: туда, куда пустили блоки по дороге к {@link #wanted()}. */
    private static double eyeX;
    private static double eyeY;
    private static double eyeZ;

    private static double maxDistance = FAR_MIN;
    private static long advancedAt;

    /** Центр свободной части окна в долях экрана (−1…1, верх — +1) и её половины. */
    private static double centerX;
    private static double centerY;
    private static double halfX = 1.0;
    private static double halfY = 1.0;

    private OrbitCamera() {}

    /**
     * Включить для экрана {@code screen}: опорная точка — центр габарита, расстояние — чтобы габарит
     * занял ~80 % свободной части окна ({@code left..right × top..bottom} в GUI-координатах экрана
     * {@code screenW × screenH}). Рысканье — {@code startYaw} (взгляд камеры, из которой пришли),
     * тангаж — {@code startPitch}, но не положе 25° и не круче 60°. Поза плавно переходит в облёт от
     * той камеры, что была на экране.
     */
    public static void enable(Screen screen, PlacementOps.Bounds b, float startYaw, float startPitch, double left,
            double top, double right, double bottom, int screenW, int screenH)
    {
        owner = screen;
        area(left, top, right, bottom, screenW, screenH);

        Minecraft mc = Minecraft.getInstance();

        frame(b);
        targetYaw = startYaw;
        targetPitch = Mth.clamp(startPitch, ENTER_PITCH_MIN, ENTER_PITCH_MAX);

        // Начало перехода — прежняя камера: опорная точка там, куда она смотрит, на будущем расстоянии
        // (с поправкой на сдвиг к центру свободной части — первый кадр облёта совпадает с прежним)
        yaw = startYaw;
        pitch = Mth.clamp(startPitch, MIN_PITCH, MAX_PITCH);
        distance = targetDistance;
        pivotX = 0.0;
        pivotY = 0.0;
        pivotZ = 0.0;

        // При нулевой опоре wanted() — это смещение глаза от опоры
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        Vec3 offset = wanted();

        pivotX = eye.x - offset.x;
        pivotY = eye.y - offset.y;
        pivotZ = eye.z - offset.z;
        // Тело стартует из прежнего глаза — оттуда, где камера уже честно стояла
        eyeX = eye.x;
        eyeY = eye.y;
        eyeZ = eye.z;
        advancedAt = Util.getMillis();
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

    /** Свободная часть окна сменилась (размер окна, масштаб GUI): опорная точка встаёт в её центр. */
    public static void area(double left, double top, double right, double bottom, int screenW, int screenH)
    {
        if (screenW <= 0 || screenH <= 0)
        {
            return;
        }

        double nl = left * 2.0 / screenW - 1.0;
        double nr = right * 2.0 / screenW - 1.0;
        double nt = 1.0 - top * 2.0 / screenH;
        double nb = 1.0 - bottom * 2.0 / screenH;

        centerX = (nl + nr) * 0.5;
        centerY = (nt + nb) * 0.5;
        halfX = Math.max(0.05, (nr - nl) * 0.5);
        halfY = Math.max(0.05, (nt - nb) * 0.5);
    }

    // ---- поза ----

    public static float yaw()
    {
        return (float) yaw;
    }

    public static float pitch()
    {
        return (float) pitch;
    }

    public static double distance()
    {
        return distance;
    }

    /** Расстояние, к которому камера сейчас подтягивается (колесо, «схема целиком»). */
    public static double targetDistance()
    {
        return targetDistance;
    }

    public static double maxDistance()
    {
        return maxDistance;
    }

    public static Vec3 pivot()
    {
        return new Vec3(pivotX, pivotY, pivotZ);
    }

    /** Куда смотрит камера (единичный вектор, как {@code Camera.forwardVector()} при этих углах). */
    public static Vec3 forward()
    {
        return forward(yaw, pitch);
    }

    /** Верх экрана в мире (как {@code Camera.upVector()}). */
    public static Vec3 up()
    {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);

        return new Vec3(-Math.sin(p) * Math.sin(y), Math.cos(p), Math.sin(p) * Math.cos(y));
    }

    /** Левый край экрана в мире (как {@code Camera.leftVector()}). */
    public static Vec3 left()
    {
        double y = Math.toRadians(yaw);

        return new Vec3(Math.cos(y), 0.0, Math.sin(y));
    }

    /** Глаз камеры — где он стоит на самом деле, с учётом блоков на пути (см. {@link #wanted()}). */
    public static Vec3 position()
    {
        return new Vec3(eyeX, eyeY, eyeZ);
    }

    /**
     * Расчётный глаз: на {@link #distance()} позади опорной точки и сдвинут в своей плоскости так,
     * чтобы опорная точка легла в центр свободной части окна. Без преград настоящий глаз — здесь же.
     */
    public static Vec3 wanted()
    {
        double tan = tanHalfFov();
        Vec3 f = forward();
        Vec3 l = left();
        Vec3 u = up();

        // Опора на экране в (centerX, centerY): сдвиг вправо на −centerX·d·tan·aspect, вверх на −centerY·d·tan
        double right = -centerX * distance * tan * aspect();
        double upward = -centerY * distance * tan;

        return new Vec3(
                pivotX - f.x * distance - l.x * right + u.x * upward,
                pivotY - f.y * distance - l.y * right + u.y * upward,
                pivotZ - f.z * distance - l.z * right + u.z * upward);
    }

    /** Шаг подтягивания к цели — раз в кадр, перед тем как миксин поставит позу. */
    public static void advance()
    {
        long now = Util.getMillis();
        long dt = Math.max(0L, Math.min(250L, now - advancedAt));
        advancedAt = now;

        double k = 1.0 - Math.exp(-dt / SMOOTH_MS);

        pivotX = approach(pivotX, targetX, k);
        pivotY = approach(pivotY, targetY, k);
        pivotZ = approach(pivotZ, targetZ, k);
        yaw = approach(yaw, targetYaw, k);
        pitch = approach(pitch, targetPitch, k);
        distance = approach(distance, targetDistance, k);
        moveEye(wanted());
    }

    // ---- управление ----

    /** Повернуть на ({@code dYaw}, {@code dPitch}) градусов сразу; тангаж — в пределах. */
    public static void rotate(double dYaw, double dPitch)
    {
        // Вращение рукой отменяет недоигранный переход: поза — от того, что игрок видит сейчас
        settleAngles();
        yaw += dYaw;
        pitch = Mth.clamp(pitch + dPitch, MIN_PITCH, MAX_PITCH);
        targetYaw = yaw;
        targetPitch = pitch;
    }

    /**
     * Панорама: опорная точка едет по горизонтальной плоскости через неё так, что точка мира под
     * курсором остаётся под курсором ({@code from} → {@code to} — лучи прошлой и новой позиции мыши).
     * Взгляд почти горизонтальный — по плоскости экрана на {@code dx, dy} пикселей GUI окна высотой
     * {@code screenH}.
     */
    public static void pan(CursorRay.Ray from, CursorRay.Ray to, double dx, double dy, int screenH)
    {
        settlePivot();

        Vec3 a = Math.abs(forward().y) >= PAN_PLANE_MIN_Y ? from.atHeight(pivotY) : null;
        Vec3 b = a == null ? null : to.atHeight(pivotY);

        if (a != null && b != null)
        {
            pivotX += a.x - b.x;
            pivotZ += a.z - b.z;
        }
        else if (screenH > 0)
        {
            // Мир «прилипает» к курсору: в плоскости экрана на глубине опоры блоков на пиксель — 2·d·tan/H
            double k = 2.0 * distance * tanHalfFov() / screenH;
            Vec3 l = left();
            Vec3 u = up();

            pivotX += (l.x * dx + u.x * dy) * k;
            pivotY += (l.y * dx + u.y * dy) * k;
            pivotZ += (l.z * dx + u.z * dy) * k;
        }

        clampToPlayer();
        targetX = pivotX;
        targetY = pivotY;
        targetZ = pivotZ;
    }

    /** Колесо: ближе/дальше в {@link #ZOOM_STEP} раз за щелчок (плавно), в пределах. */
    public static void zoom(double notches)
    {
        targetDistance = clampDistance(targetDistance * Math.pow(ZOOM_STEP, notches));
    }

    /**
     * «Схема целиком»: опорная точка — в центр габарита, расстояние — чтобы его описанная сфера
     * заняла ~80 % свободной части окна; углы не меняются. Переход плавный.
     */
    public static void frame(PlacementOps.Bounds b)
    {
        double sx = b.sizeX();
        double sy = b.sizeY();
        double sz = b.sizeZ();
        double radius = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);
        double tan = tanHalfFov();

        maxDistance = Math.max(FAR_MIN, Math.min(renderBlocks(), radius * 2.0 * FAR_DIAGONALS));

        // На глубине d свободная часть видит по горизонтали ±d·tan·aspect·halfX, по вертикали ±d·tan·halfY
        double fit = FILL * Math.min(halfX * tan * aspect(), halfY * tan);

        targetDistance = clampDistance(radius / Math.max(1.0E-3, fit));
        recenter(b);
    }

    /** Опорная точка — в центр габарита (схема ушла из кадра); расстояние и углы прежние. */
    public static void recenter(PlacementOps.Bounds b)
    {
        Vec3 center = b.center();

        targetX = center.x;
        targetY = center.y;
        targetZ = center.z;
        clampTargetToPlayer();
    }

    // ---- внутреннее ----

    private static Vec3 forward(double yawDeg, double pitchDeg)
    {
        double y = Math.toRadians(yawDeg);
        double p = Math.toRadians(pitchDeg);

        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /**
     * Глаз — к {@code to}, коробкой {@link #BODY_HALF} через блоки мира: путь, упёршийся в блок по
     * одной оси, продолжается по остальным (скольжение вдоль стены). Из блока, внутри которого глаз уже
     * оказался, выйти можно — ванильная проверка не держит то, с чем коробка уже пересекается.
     */
    private static void moveEye(Vec3 to)
    {
        double dx = to.x - eyeX;
        double dy = to.y - eyeY;
        double dz = to.z - eyeZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 1.0E-6)
        {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (level == null)
        {
            eyeX = to.x;
            eyeY = to.y;
            eyeZ = to.z;

            return;
        }

        int steps = (int) Math.ceil(length / BODY_STEP);
        Vec3 step = new Vec3(dx / steps, dy / steps, dz / steps);

        for (int i = 0; i < Math.min(steps, BODY_STEPS_MAX); i++)
        {
            AABB body = new AABB(eyeX - BODY_HALF, eyeY - BODY_HALF, eyeZ - BODY_HALF,
                    eyeX + BODY_HALF, eyeY + BODY_HALF, eyeZ + BODY_HALF);
            Vec3 moved = Entity.collideBoundingBox(null, step, body, level, List.of());

            eyeX += moved.x;
            eyeY += moved.y;
            eyeZ += moved.z;

            // Упёрлись по всем осям — дальше по тому же направлению тоже стена
            if (moved.lengthSqr() < 1.0E-12)
            {
                break;
            }
        }
    }

    private static void settleAngles()
    {
        targetYaw = yaw;
        targetPitch = pitch;
    }

    private static void settlePivot()
    {
        targetX = pivotX;
        targetY = pivotY;
        targetZ = pivotZ;
    }

    private static double approach(double value, double target, double k)
    {
        double next = value + (target - value) * k;

        return Math.abs(target - next) < 1.0E-4 ? target : next;
    }

    private static double clampDistance(double d)
    {
        return Math.max(MIN_DISTANCE, Math.min(maxDistance, d));
    }

    /** Дальность прорисовки в блоках: дальше от игрока чанков нет. */
    private static double renderBlocks()
    {
        return Math.max(FAR_MIN, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
    }

    /** Чанки грузятся вокруг игрока: опорная точка не уходит от него дальше дальности прорисовки. */
    private static void clampToPlayer()
    {
        double[] p = clampNearPlayer(pivotX, pivotZ);
        pivotX = p[0];
        pivotZ = p[1];
    }

    private static void clampTargetToPlayer()
    {
        double[] p = clampNearPlayer(targetX, targetZ);
        targetX = p[0];
        targetZ = p[1];
    }

    private static double[] clampNearPlayer(double x, double z)
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            return new double[] {x, z};
        }

        double limit = renderBlocks();
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);

        if (d <= limit)
        {
            return new double[] {x, z};
        }

        return new double[] {player.getX() + dx * limit / d, player.getZ() + dz * limit / d};
    }

    static double tanHalfFov()
    {
        return Math.tan(Math.toRadians(CursorRay.fovDegrees() * 0.5));
    }

    static double aspect()
    {
        Window window = Minecraft.getInstance().getWindow();

        return window.getHeight() > 0 ? (double) window.getWidth() / window.getHeight() : 1.0;
    }
}
