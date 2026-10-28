package com.aell.layerlist.schematics.place;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

/**
 * Луч из глаза камеры через курсор — путь мира на экран, пройденный в обратную сторону, и сам этот
 * путь ({@link #project}: точка мира → точка экрана). Вертикальный угол обзора — тот, что камера
 * отдала в проекцию этого кадра ({@link Camera#getFov()}), соотношение сторон — окна в пикселях, как
 * у ванильной проекции; курсор (в масштабированных GUI-единицах) пропорционален пикселям окна, так
 * что доли экрана совпадают точно.
 *
 * <p>В виде сверху и в облёте поза камеры берётся не из {@link Camera}, а из {@link TopViewCamera} и
 * {@link OrbitCamera}: за один кадр приходит несколько событий мыши, а камера обновится только к
 * следующему кадру — панорама и вращение по устаревшей позе «плыли» бы.
 */
@Environment(EnvType.CLIENT)
public final class CursorRay
{
    /** Дальше пересечения с плоскостью не верим: у горизонта луч почти параллелен ей. */
    public static final double MAX_DISTANCE = 1024.0;

    /** Вид сверху: взгляд строго вниз, верх экрана — север (−Z), левый край — запад (−X). */
    private static final Vec3 DOWN = new Vec3(0.0, -1.0, 0.0);
    private static final Vec3 NORTH = new Vec3(0.0, 0.0, -1.0);
    private static final Vec3 WEST = new Vec3(-1.0, 0.0, 0.0);

    private CursorRay() {}

    public static Ray cast(double mouseX, double mouseY, int screenW, int screenH)
    {
        Pose pose = pose();
        double tan = tanHalfFov();
        double right = (screenW > 0 ? mouseX * 2.0 / screenW - 1.0 : 0.0) * tan * aspect();
        double up = (screenH > 0 ? 1.0 - mouseY * 2.0 / screenH : 0.0) * tan;
        Vec3 f = pose.forward();
        Vec3 u = pose.up();
        Vec3 l = pose.left();

        Vec3 direction = new Vec3(
                f.x + u.x * up - l.x * right,
                f.y + u.y * up - l.y * right,
                f.z + u.z * up - l.z * right).normalize();

        return new Ray(pose.origin(), direction);
    }

    /**
     * Точка мира на экране: {x, y} в GUI-координатах экрана {@code screenW × screenH} и глубина вдоль
     * взгляда третьим числом; {@code null} — точка позади камеры (или вровень с ней).
     */
    public static double @Nullable [] project(Vec3 point, int screenW, int screenH)
    {
        Pose pose = pose();
        Vec3 d = point.subtract(pose.origin());
        double depth = d.dot(pose.forward());

        if (depth <= 1.0E-3)
        {
            return null;
        }

        double tan = tanHalfFov();
        double nx = -d.dot(pose.left()) / (depth * tan * aspect());
        double ny = d.dot(pose.up()) / (depth * tan);

        return new double[] {(nx + 1.0) * 0.5 * screenW, (1.0 - ny) * 0.5 * screenH, depth};
    }

    /** Блоков на пиксель GUI окна высотой {@code screenH} на глубине {@code depth} вдоль взгляда. */
    public static double blocksPerPixel(double depth, int screenH)
    {
        return screenH > 0 ? 2.0 * depth * tanHalfFov() / screenH : 0.0;
    }

    /** Поза, из которой мир виден в этом кадре (или будет виден в следующем — вид сверху, облёт). */
    public static Pose pose()
    {
        if (TopViewCamera.active())
        {
            return new Pose(TopViewCamera.position(), DOWN, NORTH, WEST);
        }

        if (OrbitCamera.active())
        {
            return new Pose(OrbitCamera.position(), OrbitCamera.forward(), OrbitCamera.up(), OrbitCamera.left());
        }

        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();

        return new Pose(camera.position(), vec(camera.forwardVector()), vec(camera.upVector()), vec(camera.leftVector()));
    }

    private static Vec3 vec(Vector3fc v)
    {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static double tanHalfFov()
    {
        return Math.tan(Math.toRadians(Minecraft.getInstance().gameRenderer.mainCamera().getFov() * 0.5));
    }

    private static double aspect()
    {
        Window window = Minecraft.getInstance().getWindow();

        return window.getHeight() > 0 ? (double) window.getWidth() / window.getHeight() : 1.0;
    }

    /** Глаз камеры и её оси в мире: взгляд, верх и левый край экрана (единичные). */
    public record Pose(Vec3 origin, Vec3 forward, Vec3 up, Vec3 left) {}

    public record Ray(Vec3 origin, Vec3 direction)
    {
        /** Точка на горизонтальной плоскости {@code y = planeY} впереди камеры; {@code null} — мимо. */
        public @Nullable Vec3 atHeight(double planeY)
        {
            if (Math.abs(this.direction.y) < 1.0E-6)
            {
                return null;
            }

            double t = (planeY - this.origin.y) / this.direction.y;

            if (t <= 0.0 || t > MAX_DISTANCE)
            {
                return null;
            }

            return this.origin.add(this.direction.scale(t));
        }

        /** Точка на плоскости через {@code point} с нормалью {@code normal} впереди камеры; {@code null} — мимо. */
        public @Nullable Vec3 onPlane(Vec3 point, Vec3 normal)
        {
            double along = this.direction.dot(normal);

            if (Math.abs(along) < 1.0E-6)
            {
                return null;
            }

            double t = point.subtract(this.origin).dot(normal) / along;

            if (t <= 0.0 || t > MAX_DISTANCE)
            {
                return null;
            }

            return this.origin.add(this.direction.scale(t));
        }
    }
}
