package com.aell.layerlist.schematics.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Рисует заявки {@link PreviewCapture} в кадре GUI — той же цепочкой, что окно превью вкладки
 * ({@link PreviewPipRenderer#submitModel}), только в свою текстуру, а не на экран. Как у иконок
 * выгрузок, {@link #prepare} переопределён целиком: невидимая заявка {@link Pass} 1×1 нужна только
 * затем, чтобы рендер GUI позвал рендерер в фазе, где включена световая карта интерфейса.
 *
 * <p>Кадр: текстуры цвета и глубины {@code w·s}×{@code h·s}, ортопроекция ±1000 по глубине (как у
 * PIP), поза — центр текстуры, масштаб {@code s} и Z от зрителя, как ванильный PIP при масштабе GUI
 * {@code s}. Модель рисуется <b>дважды — на чёрном и на белом</b>: непрозрачные и вырезанные слои пишут в
 * альфу цели альфу текстуры (у листвы и краёв мип-уровней она меньше единицы, хотя на экране там сплошной
 * цвет), поэтому покрытие берём не из альфы, а из разницы двух фонов — непрозрачное на обоих одинаково,
 * полупрозрачное смешалось с фоном ровно на свою альфу. Пиксели — копиями текстур в буферы видеокарты;
 * ответ приходит через кадр-два на клиентском потоке, тогда же текстуры и буферы отдаются. За кадр —
 * одна заявка: сотни тысяч граней.
 */
@Environment(EnvType.CLIENT)
final class CaptureRenderer extends PictureInPictureRenderer<CaptureRenderer.Pass>
{
    private static final Vector4fc BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
    private static final Vector4fc WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final float DEPTH_RANGE = 1000.0F;
    private static final int RGBA = 4;

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("layerlist preview capture");
    private SubmitNodeStorage submits = new SubmitNodeStorage();

    /** Невидимая заявка на вызов рендерера в кадре GUI: прямоугольник 1×1, ничего не выводит. */
    record Pass(int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState
    {
        static final Pass INSTANCE = new Pass(0, 0, 1, 1, 1.0F, null, new ScreenRectangle(0, 0, 1, 1));
    }

    /** Текстуры кадра одной заявки (цвет на чёрном, цвет на белом, общая глубина) и буферы чтения. */
    private static final class Targets implements AutoCloseable
    {
        final GpuTexture onBlack;
        final GpuTextureView onBlackView;
        final GpuTexture onWhite;
        final GpuTextureView onWhiteView;
        final GpuTexture depth;
        final GpuTextureView depthView;
        @Nullable GpuBuffer blackBuffer;
        @Nullable GpuBuffer whiteBuffer;
        /** Сколько копий в буфер уже пришло (ждём обе). */
        int arrived;
        private boolean closed;

        Targets(int width, int height)
        {
            GpuDevice device = RenderSystem.getDevice();
            this.onBlack = color(device, "layerlist preview capture (black)", width, height);
            this.onWhite = color(device, "layerlist preview capture (white)", width, height);
            this.depth = device.createTexture(() -> "layerlist preview capture depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, width, height, 1, 1);
            this.onBlackView = device.createTextureView(this.onBlack);
            this.onWhiteView = device.createTextureView(this.onWhite);
            this.depthView = device.createTextureView(this.depth);
        }

        private static GpuTexture color(GpuDevice device, String label, int width, int height)
        {
            return device.createTexture(() -> label,
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        }

        @Override
        public void close()
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;

            // Виды — раньше своих текстур
            this.onBlackView.close();
            this.onWhiteView.close();
            this.depthView.close();
            this.onBlack.close();
            this.onWhite.close();
            this.depth.close();

            for (GpuBuffer buffer : new GpuBuffer[] {this.blackBuffer, this.whiteBuffer})
            {
                if (buffer != null)
                {
                    buffer.close();
                }
            }

            this.blackBuffer = null;
            this.whiteBuffer = null;
        }
    }

    @Override
    public Class<Pass> getRenderStateClass()
    {
        return Pass.class;
    }

    @Override
    protected String getTextureLabel()
    {
        return "layerlist_preview_capture";
    }

    @Override
    protected void renderToTexture(Pass state, PoseStack poseStack, SubmitNodeCollector collector)
    {
        // Не зовётся: prepare переопределён целиком (своя текстура, на экран ничего)
    }

    @Override
    public void prepare(Pass state, GuiRenderState guiState, FeatureRenderDispatcher dispatcher, int guiScale)
    {
        PreviewCapture.Job job = PreviewCapture.pollReady();

        if (job == null)
        {
            return;
        }

        // Кадр GUI на клиентском потоке: исключение отсюда уронило бы игру, а снимок — не повод
        try
        {
            this.draw(job, dispatcher);
        }
        catch (RuntimeException | LinkageError e)
        {
            // Недоотправленное в хранилище не должно попасть в следующий кадр
            this.submits = new SubmitNodeStorage();
            PreviewCapture.fail(job, new PreviewCapture.CaptureFailed("drawing failed", e));
        }
    }

    @Override
    public void close()
    {
        this.projectionBuffer.close();
        super.close();
    }

    private void draw(PreviewCapture.Job job, FeatureRenderDispatcher dispatcher)
    {
        PreviewModel model = Objects.requireNonNull(job.model, "model");
        PreviewCamera.Key camera = Objects.requireNonNull(job.camera, "camera");
        int scale = job.supersample;
        int width = job.width * scale;
        int height = job.height * scale;

        Targets targets = new Targets(width, height);
        job.targets = targets;

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();

        // Проекцию, свет и вид модели меняем только на время снимка — дальше кадр GUI рисует как рисовал
        GpuBufferSlice projectionBefore = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType typeBefore = RenderSystem.getProjectionType();
        GpuBufferSlice lightsBefore = RenderSystem.getShaderLights();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();

        try
        {
            this.projection.setupOrtho(-DEPTH_RANGE, DEPTH_RANGE, width, height, true);
            RenderSystem.setProjectionMatrix(this.projectionBuffer.getBuffer(this.projection), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.disableScissorForRenderTypeDraws();

            this.pass(encoder, dispatcher, model, camera, targets.onBlack, targets.onBlackView, BLACK, targets, width, height, scale);
            this.pass(encoder, dispatcher, model, camera, targets.onWhite, targets.onWhiteView, WHITE, targets, width, height, scale);
        }
        finally
        {
            modelView.popMatrix();

            if (projectionBefore != null)
            {
                RenderSystem.setProjectionMatrix(projectionBefore, typeBefore);
            }

            if (lightsBefore != null)
            {
                RenderSystem.setShaderLights(lightsBefore);
            }
        }

        long bytes = (long) width * height * RGBA;
        targets.blackBuffer = device.createBuffer(() -> "layerlist preview capture read-back (black)",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
        targets.whiteBuffer = device.createBuffer(() -> "layerlist preview capture read-back (white)",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
        PreviewCapture.drawn(job);
        encoder.copyTextureToBuffer(targets.onBlack, targets.blackBuffer, 0L, () -> arrived(job, targets, width, height), 0);
        encoder.copyTextureToBuffer(targets.onWhite, targets.whiteBuffer, 0L, () -> arrived(job, targets, width, height), 0);
    }

    /** Один проход: фон — {@code background}, глубина — заново, модель — та же цепочка, что у окна превью. */
    private void pass(CommandEncoder encoder, FeatureRenderDispatcher dispatcher, PreviewModel model, PreviewCamera.Key camera,
            GpuTexture color, GpuTextureView colorView, Vector4fc background, Targets targets, int width, int height, int scale)
    {
        encoder.clearColorAndDepthTextures(color, background, targets.depth, 0.0);

        // Как поза ванильного PIP при масштабе GUI = scale: центр текстуры, Z от зрителя
        PoseStack pose = new PoseStack();
        pose.translate(width / 2.0F, height / 2.0F, 0.0F);
        pose.scale(scale, scale, -scale);
        PreviewPipRenderer.submitModel(model, camera, pose, this.submits);

        // 26.2: проходов нет — вывод в свою текстуру через подмену цели, как в ванильном PIP
        GpuTextureView colorBefore = RenderSystem.outputColorTextureOverride;
        GpuTextureView depthBefore = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = colorView;
        RenderSystem.outputDepthTextureOverride = targets.depthView;

        try
        {
            dispatcher.renderAllFeatures(this.submits);
        }
        finally
        {
            RenderSystem.outputColorTextureOverride = colorBefore;
            RenderSystem.outputDepthTextureOverride = depthBefore;
        }
    }

    /** Ответ видеокарты на одну из двух копий (клиентский поток); на второй — байты в массивы, ресурсы — отдать. */
    private static void arrived(PreviewCapture.Job job, Targets targets, int width, int height)
    {
        if (++targets.arrived < 2)
        {
            return;
        }

        byte[] black = null;
        byte[] white = null;
        RuntimeException failure = null;

        if (job.targets == targets && targets.blackBuffer != null && targets.whiteBuffer != null)
        {
            try
            {
                black = read(targets.blackBuffer, width, height);
                white = read(targets.whiteBuffer, width, height);
            }
            catch (RuntimeException e)
            {
                failure = e;
            }
        }

        if (job.targets == targets)
        {
            job.releaseTargets();
        }
        else
        {
            // Заявку уже закрыли по таймауту — свои ресурсы отдаём сами
            targets.close();
        }

        if (black != null && white != null)
        {
            PreviewCapture.readBack(job, black, white);
        }
        else if (failure != null)
        {
            PreviewCapture.fail(job, new PreviewCapture.CaptureFailed("GPU read-back failed", failure));
        }
    }

    private static byte[] read(GpuBuffer buffer, int width, int height)
    {
        try (GpuBufferSlice.MappedView view = buffer.map(true, false))
        {
            ByteBuffer data = view.data();
            byte[] rgba = new byte[width * height * RGBA];
            data.get(0, rgba);

            return rgba;
        }
    }
}
