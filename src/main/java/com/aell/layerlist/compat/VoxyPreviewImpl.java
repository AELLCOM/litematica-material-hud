package com.aell.layerlist.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import java.lang.reflect.Method;
import java.util.Locale;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.Projection;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Всё с типами Voxy для {@link VoxyPreview}. Перенос окна 3D-карты VoxyMap LTS ({@code WindowMapRenderer}, MIT,
 * © 2026 raben12paste; в редакции VoxyMap Bobby): свой вид у выбора видов Voxy ({@code ViewportSelector.getOrCreate}
 * по своему ключу), перспектива и поворот как у камеры игры, непрозрачный Voxy — в свою цель кадра.
 *
 * <p>VoxyMap Bobby вне своей карты глушит вывод Voxy и обновляет его картинку раз в несколько кадров
 * ({@code VoxyRenderGate}); на время кадра превью ему сказано, что Voxy нужен ({@code externalView}), — если такой
 * версии нет, превью может выйти пустым, и тогда пусть стоит Voxy отдельным модом.
 */
@Environment(EnvType.CLIENT)
final class VoxyPreviewImpl
{
    private static final Object VIEWPORT_KEY = new Object();
    private static final int MAX_TARGET_SIZE = 2048;
    private static final float NEAR_PLANE = 0.5F;
    private static final float FAR_PLANE = 16384.0F;
    /** Фон кадра — тёмный, как у карты «Выделения» (0x0E0E11). */
    private static final Vector4f CLEAR_COLOR = new Vector4f(0.055F, 0.055F, 0.067F, 1.0F);
    private static final float NO_FOG_DISTANCE = 1.0E7F;
    private static final FogParameters NO_FOG = new FogParameters(CLEAR_COLOR.x, CLEAR_COLOR.y, CLEAR_COLOR.z, 0.0F,
            NO_FOG_DISTANCE, NO_FOG_DISTANCE, NO_FOG_DISTANCE, NO_FOG_DISTANCE);
    private static final @Nullable Method GET_OR_CREATE = resolveGetOrCreate();
    private static final @Nullable Method EXTERNAL_VIEW = resolveExternalView();
    /**
     * Старый VoxyMap Bobby (до 1.0.4-bobby.32): Voxy вложен в него, сигнала «чужой вид» нет — он глушит вывод Voxy вне
     * своей карты, и превью вышло бы тёмным. Тогда — своё превью.
     */
    private static final boolean MUTED_BY_MAP = mutedByMap();

    private static @Nullable RenderTarget target;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static final Vector3f FORWARD = new Vector3f();
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static boolean hasFrame;
    private static @Nullable Boolean openGl;
    private static boolean loggedOnce;

    private VoxyPreviewImpl() {}

    static boolean ready()
    {
        return GET_OR_CREATE != null && ! MUTED_BY_MAP && openGl() && ! shaderPackInUse() && IVoxyRenderSystemHolder.getNullable() != null;
    }

    static boolean render(double x, double y, double z, float yaw, float pitch, float fov, int width, int height)
    {
        int w = Math.min(width, MAX_TARGET_SIZE);
        int h = Math.min(height, MAX_TARGET_SIZE);
        Projection projection = new Projection();
        projection.setupPerspective(NEAR_PLANE, FAR_PLANE, fov, w, h);
        return renderWith(x, y, z, yaw, pitch, projection.getMatrix(new Matrix4f()), width, height);
    }

    /**
     * Кадр ортографической проекцией: окно {@code ±halfW × ±halfH} блоков вокруг оси взгляда (та же камера, что у
     * ортографического превью Material HUD — так мир Voxy ложится ровно под голограмму). API 6.
     */
    static boolean renderOrtho(double x, double y, double z, float yaw, float pitch, float halfW, float halfH,
            float near, float far, int width, int height)
    {
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        Matrix4f projectionMatrix = new Matrix4f().setOrtho(-halfW, halfW, -halfH, halfH, near, far, zeroToOne);
        return renderWith(x, y, z, yaw, pitch, projectionMatrix, width, height);
    }

    private static boolean renderWith(double x, double y, double z, float yaw, float pitch, Matrix4f projectionMatrix,
            int width, int height)
    {
        hasFrame = false;
        VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();

        if (system == null || width <= 0 || height <= 0)
        {
            return false;
        }

        int w = Math.min(width, MAX_TARGET_SIZE);
        int h = Math.min(height, MAX_TARGET_SIZE);

        if (target == null)
        {
            target = new TextureTarget("Material HUD selection preview (Voxy)", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        else if (target.width != w || target.height != h)
        {
            target.resize(w, h);
        }

        RenderTarget out = target;
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(out.getColorTexture(), CLEAR_COLOR,
                out.getDepthTexture(), RenderProperties.getRenderProperties().clearDepth());

        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yaw), -(float) Math.toRadians(pitch), 0.0F);
        Matrix4f modelView = new Matrix4f().rotation(rotation.conjugate(new Quaternionf()));

        externalView(true);

        try
        {
            if (! loggedOnce)
            {
                loggedOnce = true;
                VoxyPreview.LOGGER.info("Material HUD: selection preview drawn by Voxy (near sections by Voxy: {})",
                        me.cortex.voxy.client.VoxyClient.disableSodiumChunkRender());
            }

            Viewport<?> viewport = system.setupViewport(projectionMatrix, modelView, NO_FOG, w, h, x, y, z);

            if (viewport == null)
            {
                return false;
            }

            system.renderOpaque(viewport, ((GlTextureView) out.getDepthTextureView()).glId(), ((GlTextureView) out.getColorTextureView()).glId());
        }
        finally
        {
            externalView(false);
        }

        VIEW_PROJECTION.set(projectionMatrix).mul(modelView);
        INVERSE.set(VIEW_PROJECTION).invert();
        double p = Math.toRadians(pitch);
        double t = Math.toRadians(yaw);
        FORWARD.set((float) (-Math.sin(t) * Math.cos(p)), (float) -Math.sin(p), (float) (Math.cos(t) * Math.cos(p)));
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        hasFrame = true;
        return true;
    }

    static @Nullable GpuTextureView color()
    {
        return hasFrame && target != null ? target.getColorTextureView() : null;
    }

    static double @Nullable [] project(double x, double y, double z)
    {
        if (! hasFrame)
        {
            return null;
        }

        Vector4f clip = VIEW_PROJECTION.transform(new Vector4f((float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ), 1.0F));

        if (clip.w <= 0.05F)
        {
            return null;
        }

        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        return Double.isFinite(ndcX) && Double.isFinite(ndcY) ? new double[] { ndcX, ndcY } : null;
    }

    static double @Nullable [] ray(double ndcX, double ndcY)
    {
        if (! hasFrame)
        {
            return null;
        }

        // Глаз — начало координат вида: любая точка луча, снятая с кадра, и есть направление
        Vector4f point = INVERSE.transform(new Vector4f((float) ndcX, (float) ndcY, 0.5F, 1.0F));

        if (Math.abs(point.w) < 1.0E-9F)
        {
            return null;
        }

        Vector3f dir = new Vector3f(point.x / point.w, point.y / point.w, point.z / point.w);

        if (dir.lengthSquared() < 1.0E-12F)
        {
            return null;
        }

        dir.normalize();

        if (dir.dot(FORWARD) < 0.0F)
        {
            dir.negate();
        }

        return new double[] { cameraX, cameraY, cameraZ, dir.x, dir.y, dir.z };
    }

    static void release()
    {
        hasFrame = false;

        if (target != null)
        {
            target.destroyBuffers();
            target = null;
        }
    }

    static @Nullable Object viewport(Object selector)
    {
        if (GET_OR_CREATE == null)
        {
            return null;
        }

        try
        {
            return GET_OR_CREATE.invoke(selector, VIEWPORT_KEY);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** Voxy работает только на OpenGL (на Vulkan его вида нет). */
    private static boolean openGl()
    {
        Boolean known = openGl;

        if (known == null)
        {
            GpuDevice device = RenderSystem.tryGetDevice();

            if (device == null)
            {
                return false;
            }

            String name = device.getDeviceInfo().backendName();
            known = name != null && name.toLowerCase(Locale.ROOT).contains("opengl");
            openGl = known;
        }

        return known;
    }

    /** Шейдерпак Iris: Voxy рисует его конвейером, отдельный вид не получится. */
    private static boolean shaderPackInUse()
    {
        if (! FabricLoader.getInstance().isModLoaded("iris"))
        {
            return false;
        }

        try
        {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return true;
        }
    }

    /** VoxyMap Bobby: «Voxy нужен» на время кадра превью (иначе он глушит вывод вне своей карты). */
    private static void externalView(boolean on)
    {
        if (EXTERNAL_VIEW != null)
        {
            try
            {
                EXTERNAL_VIEW.invoke(null, on);
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                // Без сигнала превью просто может выйти пустым
            }
        }
    }

    private static @Nullable Method resolveGetOrCreate()
    {
        try
        {
            Method method = Class.forName("me.cortex.voxy.client.core.rendering.ViewportSelector").getDeclaredMethod("getOrCreate", Object.class);
            method.setAccessible(true);
            return method;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            VoxyPreview.LOGGER.warn("Material HUD: this Voxy has no extra views, the selection preview draws itself", e);
            return null;
        }
    }

    private static boolean mutedByMap()
    {
        if (EXTERNAL_VIEW != null)
        {
            return false;
        }

        try
        {
            Class<?> gate = Class.forName("com.voxymap.client.map.VoxyRenderGate");
            return ! (boolean) gate.getMethod("standaloneVoxy").invoke(null);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            // Карты нет (или в ней нет такого выключателя) — Voxy никто не глушит
            return false;
        }
    }

    private static @Nullable Method resolveExternalView()
    {
        try
        {
            return Class.forName("com.voxymap.client.map.VoxyRenderGate").getMethod("externalView", boolean.class);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
