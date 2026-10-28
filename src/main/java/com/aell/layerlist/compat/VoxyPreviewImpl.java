package com.aell.layerlist.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.lang.reflect.Method;
import java.util.Locale;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * Всё с типами Voxy для {@link VoxyPreview}. Перенос окна 3D-карты VoxyMap LTS ({@code WindowMapRenderer}, MIT,
 * © 2026 raben12paste; в редакции VoxyMap Bobby): свой вид у выбора видов Voxy ({@code ViewportSelector.getOrCreate}
 * по своему ключу), перспектива и поворот как у камеры игры, непрозрачный Voxy — в свою цель кадра.
 *
 * <p>VoxyMap Bobby вне своей карты глушит вывод Voxy и обновляет его картинку раз в несколько кадров
 * ({@code VoxyRenderGate}); на время кадра превью ему сказано, что Voxy нужен ({@code externalView}), — если такой
 * версии нет, превью может выйти пустым, и тогда пусть стоит Voxy отдельным модом.
 *
 * <p>1.21.11 (Voxy 0.2.16): Voxy рисует в привязанный буфер кадра OpenGL и берёт размер из {@code glViewport} — на
 * время кадра привязываем свой буфер с цветом и глубиной цели превью, потом возвращаем прежние. В игре Voxy 0.2.16
 * зовётся изнутри отрисовки чанков Sodium, и состояние OpenGL после него приводит в порядок Sodium; здесь этого нет —
 * {@link GlState} снимает состояние до кадра и возвращает его после, иначе кэш состояний игры врёт и GUI рисуется без
 * смешивания и не теми текстурами.
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
    private static final int CLEAR_ARGB = 0xFF0E0E11;
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
    /** Свой буфер кадра OpenGL над текстурами цели (Voxy 0.2.16 рисует в привязанный); 0 — нет. */
    private static int framebuffer;
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
        return GET_OR_CREATE != null && ! MUTED_BY_MAP && openGl() && ! shaderPackInUse() && IGetVoxyRenderSystem.getNullable() != null;
    }

    static boolean render(double x, double y, double z, float yaw, float pitch, float fov, int width, int height)
    {
        hasFrame = false;
        VoxyRenderSystem system = IGetVoxyRenderSystem.getNullable();

        if (system == null || width <= 0 || height <= 0)
        {
            return false;
        }

        int w = Math.min(width, MAX_TARGET_SIZE);
        int h = Math.min(height, MAX_TARGET_SIZE);

        if (target == null)
        {
            target = new TextureTarget("Material HUD selection preview (Voxy)", w, h, true);
            deleteFramebuffer();
        }
        else if (target.width != w || target.height != h)
        {
            target.resize(w, h);
            deleteFramebuffer();
        }

        RenderTarget out = target;
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(out.getColorTexture(), CLEAR_ARGB,
                out.getDepthTexture(), RenderProperties.getRenderProperties().clearDepth());

        Matrix4f projectionMatrix = new Matrix4f().perspective((float) Math.toRadians(fov), w / (float) h, NEAR_PLANE, FAR_PLANE);
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

            int drawBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readBefore = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int[] viewportBefore = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewportBefore);
            GlState state = GlState.capture();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer(out));
            GL11.glViewport(0, 0, w, h);

            try
            {
                Viewport<?> viewport = system.setupViewport(projectionMatrix, modelView, NO_FOG, x, y, z);

                if (viewport == null)
                {
                    return false;
                }

                system.renderOpaque(viewport);
            }
            finally
            {
                state.restore();
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBefore);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBefore);
                GL11.glViewport(viewportBefore[0], viewportBefore[1], viewportBefore[2], viewportBefore[3]);
            }
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

        deleteFramebuffer();

        if (target != null)
        {
            target.destroyBuffers();
            target = null;
        }
    }

    /** Буфер кадра над цветом и глубиной цели; пересоздаётся вместе с её текстурами. */
    private static int framebuffer(RenderTarget out)
    {
        if (framebuffer == 0)
        {
            int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    ((GlTexture) out.getColorTexture()).glId(), 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                    ((GlTexture) out.getDepthTexture()).glId(), 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        }

        return framebuffer;
    }

    private static void deleteFramebuffer()
    {
        if (framebuffer != 0)
        {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
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

            String name = device.getBackendName();
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

    /** Состояние OpenGL, которое игра 1.21.11 держит в своём кэше: до кадра Voxy — снять, после — вернуть как было. */
    private static final class GlState
    {
        private static final int[] CAPS = { GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST,
                GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_STENCIL_TEST, GL11.GL_COLOR_LOGIC_OP };
        private static final int[] BUFFERS = { GL15.GL_ARRAY_BUFFER, GL31.GL_UNIFORM_BUFFER, GL43.GL_SHADER_STORAGE_BUFFER,
                GL40.GL_DRAW_INDIRECT_BUFFER, GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0x88EB, 0x88EC };
        private static final int[] BUFFER_BINDINGS = { GL15.GL_ARRAY_BUFFER_BINDING, GL31.GL_UNIFORM_BUFFER_BINDING,
                GL43.GL_SHADER_STORAGE_BUFFER_BINDING, GL40.GL_DRAW_INDIRECT_BUFFER_BINDING, 0x8F36, 0x8F37, 0x88ED, 0x88EF };
        private static final int UNITS = 16;

        private final boolean[] caps = new boolean[CAPS.length];
        private final int[] blend = new int[6];
        private int depthFunc;
        private final double[] depthRange = new double[2];
        private int clipOrigin;
        private int clipDepth;
        private boolean depthMask;
        private int cullFace;
        private int frontFace;
        private final int[] scissor = new int[4];
        private float offsetFactor;
        private float offsetUnits;
        private final byte[] colorMask = new byte[4];
        private int program;
        private int vertexArray;
        private final int[] buffers = new int[BUFFERS.length];
        private int activeTexture;
        private final int[] textures = new int[UNITS];
        private final int[] samplers = new int[UNITS];

        static GlState capture()
        {
            GlState s = new GlState();

            for (int i = 0; i < CAPS.length; ++i)
            {
                s.caps[i] = GL11.glIsEnabled(CAPS[i]);
            }

            s.blend[0] = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            s.blend[1] = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            s.blend[2] = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            s.blend[3] = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            s.blend[4] = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            s.blend[5] = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            s.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            s.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, s.depthRange);
            s.clipOrigin = GL11.glGetInteger(GL45.GL_CLIP_ORIGIN);
            s.clipDepth = GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE);
            s.cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            s.frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, s.scissor);
            s.offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            s.offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);

            java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(16);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            mask.get(s.colorMask, 0, 4);

            s.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            s.vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

            for (int i = 0; i < BUFFERS.length; ++i)
            {
                s.buffers[i] = GL11.glGetInteger(BUFFER_BINDINGS[i]);
            }

            s.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

            for (int unit = 0; unit < UNITS; ++unit)
            {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                s.textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                s.samplers[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            }

            GL13.glActiveTexture(s.activeTexture);
            return s;
        }

        void restore()
        {
            for (int i = 0; i < CAPS.length; ++i)
            {
                if (this.caps[i])
                {
                    GL11.glEnable(CAPS[i]);
                }
                else
                {
                    GL11.glDisable(CAPS[i]);
                }
            }

            GL14.glBlendFuncSeparate(this.blend[0], this.blend[1], this.blend[2], this.blend[3]);
            GL20.glBlendEquationSeparate(this.blend[4], this.blend[5]);
            GL11.glDepthFunc(this.depthFunc);
            GL11.glDepthMask(this.depthMask);
            GL11.glDepthRange(this.depthRange[0], this.depthRange[1]);
            GL45.glClipControl(this.clipOrigin, this.clipDepth);
            GL11.glCullFace(this.cullFace);
            GL11.glFrontFace(this.frontFace);
            GL11.glScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
            GL11.glPolygonOffset(this.offsetFactor, this.offsetUnits);
            GL11.glColorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
            GL20.glUseProgram(this.program);
            GL30.glBindVertexArray(this.vertexArray);

            for (int i = 0; i < BUFFERS.length; ++i)
            {
                GL15.glBindBuffer(BUFFERS[i], this.buffers[i]);
            }

            for (int unit = 0; unit < UNITS; ++unit)
            {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textures[unit]);
                GL33.glBindSampler(unit, this.samplers[unit]);
            }

            GL13.glActiveTexture(this.activeTexture);
        }
    }
}
