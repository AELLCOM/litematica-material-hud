package com.aell.layerlist.compat;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D-превью области «Выделения» движком Voxy — как окно 3D-карты VoxyMap LTS: отдельный вид Voxy (свои матрицы и
 * размер) рисуется в свою текстуру, а экран её показывает. Дальний мир — LOD Voxy, уже собранные им из чанков (и кэша
 * Bobby, если его импортировали): ни снимка блоков, ни сборки модели, а поворот — только новый кадр Voxy.
 *
 * <p>Кода Voxy в Material HUD нет — его лицензия (All Rights Reserved) не даёт его распространять: превью рисуется им,
 * только если Voxy стоит отдельно или вложен в другой мод (VoxyMap Bobby). Без Voxy, на Vulkan и со своим шейдерпаком
 * Iris — своё превью. Всё с типами Voxy — в {@link VoxyPreviewImpl}; сбой — в лог один раз, дальше своё превью.
 */
@Environment(EnvType.CLIENT)
public final class VoxyPreview
{
    static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("voxy");
    private static boolean failed;
    /** Идёт кадр превью: миксины на Voxy подменяют вид, держат центр подгрузки на игроке и рисуют ближние секции. */
    private static boolean rendering;

    private VoxyPreview() {}

    /** Voxy стоит (отдельно или вложенным). */
    public static boolean installed()
    {
        return INSTALLED;
    }

    /** Можно рисовать превью Voxy сейчас: он стоит, работает его система отрисовки, OpenGL, шейдерпак выключен. */
    public static boolean ready()
    {
        return INSTALLED && ! failed && guard(VoxyPreviewImpl::ready, false);
    }

    /**
     * Кадр превью в свою текстуру.
     *
     * @param x     глаз, мир
     * @param yaw   рыскание, как у камеры игры (0 — на юг)
     * @param pitch тангаж, как у камеры игры (больше нуля — вниз)
     * @param fov   угол обзора по вертикали, градусы
     * @return {@code true} — картинка есть ({@link #color()})
     */
    public static boolean render(double x, double y, double z, float yaw, float pitch, float fov, int width, int height)
    {
        if (! ready())
        {
            return false;
        }

        rendering = true;

        try
        {
            return guard(() -> VoxyPreviewImpl.render(x, y, z, yaw, pitch, fov, width, height), false);
        }
        finally
        {
            rendering = false;
        }
    }

    /**
     * Кадр ортографической проекцией — мир под голограммой 3D «Управления зонами» Zone Focus (API 6): окно
     * {@code ±halfW × ±halfH} блоков вокруг оси взгляда из точки {@code x, y, z}.
     */
    public static boolean renderOrtho(double x, double y, double z, float yaw, float pitch, float halfW, float halfH,
            float near, float far, int width, int height)
    {
        if (! ready())
        {
            return false;
        }

        rendering = true;

        try
        {
            return guard(() -> VoxyPreviewImpl.renderOrtho(x, y, z, yaw, pitch, halfW, halfH, near, far, width, height), false);
        }
        finally
        {
            rendering = false;
        }
    }

    /** Картинка последнего кадра; кадра нет — {@code null}. */
    public static @Nullable GpuTextureView color()
    {
        return INSTALLED && ! failed ? guard(VoxyPreviewImpl::color, null) : null;
    }

    /** Точка мира → нормированные координаты кадра {@code {x, y}} (−1…1, Y вверх); за глазом — {@code null}. */
    public static double @Nullable [] project(double x, double y, double z)
    {
        return INSTALLED && ! failed ? guard(() -> VoxyPreviewImpl.project(x, y, z), null) : null;
    }

    /** Луч из глаза через точку кадра (−1…1, Y вверх): {@code {глаз x, y, z, направление x, y, z}}; кадра нет — {@code null}. */
    public static double @Nullable [] ray(double ndcX, double ndcY)
    {
        return INSTALLED && ! failed ? guard(() -> VoxyPreviewImpl.ray(ndcX, ndcY), null) : null;
    }

    /** Отпустить текстуру превью (экран закрыт). */
    public static void release()
    {
        if (INSTALLED && ! failed)
        {
            guard(() ->
            {
                VoxyPreviewImpl.release();
                return null;
            }, null);
        }
    }

    /** Миксин на выбор вида Voxy: во время кадра превью — свой вид, иначе {@code null}. */
    public static @Nullable Object viewport(Object selector)
    {
        return rendering && ! failed ? guard(() -> VoxyPreviewImpl.viewport(selector), null) : null;
    }

    public static boolean rendering()
    {
        return rendering;
    }

    static void fail(Throwable error)
    {
        if (! failed)
        {
            failed = true;
            LOGGER.warn("Material HUD: Voxy preview is off, the selection preview draws itself", error);
        }
    }

    private static <T> T guard(Supplier<T> call, T fallback)
    {
        try
        {
            return call.get();
        }
        catch (RuntimeException | LinkageError e)
        {
            fail(e);
            return fallback;
        }
    }
}
