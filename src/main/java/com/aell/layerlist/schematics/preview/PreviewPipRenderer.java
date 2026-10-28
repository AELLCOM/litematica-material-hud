package com.aell.layerlist.schematics.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Рендерер 3D-превью для ванильной «картинки в картинке» (M23). Зарегистрирован через
 * {@code PictureInPictureRendererRegistry}; GUI подаёт ему {@link PreviewRenderState}.
 *
 * <p>Картинка перерисовывается только при смене ключа (модель, камера) или размера окна — иначе
 * {@link #textureIsReadyToBlit} отвечает «готово», и кадр GUI просто копирует прошлую текстуру.
 *
 * <p>Орбита (ортопроекция) рисуется из видеопамяти ({@link #prepare}): вершины модели загружаются на GPU один раз,
 * поворот и масштаб — только матрица вида, кадр — по вызову отрисовки на слой, как чанки в игре и LOD у Voxy.
 * Раньше каждый поворот гнал все квады через процессор заново — на области «Выделения» в 32 чанка это сотни тысяч
 * вершин за кадр. Облёт из глаза (перспектива на CPU) и сбой GPU-пути — по-старому, ниже.
 *
 * <p>Перерисовка по-старому — свои закешированные квады через {@code submitCustomGeometry} ванильными типами
 * блоков (атлас блоков, непрозрачные / вырез / полупрозрачные), особые блоки — через
 * {@code BlockModelRenderState.submit}. Перед записью вершин отбрасываются грани, повёрнутые от
 * зрителя (их всё равно отсёк бы конвейер), полупрозрачные сортируются от дальних к ближним.
 *
 * <p>Поза: PIP даёт центр текстуры и масштаб пикселей GUI, ось Y вниз, Z от зрителя. Как ванильный
 * {@code OversizedItemRenderer}, переворачиваем Y и Z — дальше обычная правая система вида (X вправо,
 * Y вверх, Z к зрителю), в которой камера Minecraft и строит поворот. Глубину при сильном увеличении
 * сжимаем, чтобы модель не упиралась в плоскости отсечения ортопроекции PIP (±1000 пикселей текстуры).
 */
@Environment(EnvType.CLIENT)
final class PreviewPipRenderer extends PictureInPictureRenderer<PreviewRenderState>
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics-preview");

    /** Ортопроекция PIP режет глубину на ±1000; оставляем запас. */
    private static final float DEPTH_LIMIT = 900.0F;

    /** Грань считаем повёрнутой от зрителя, если нормаль смотрит назад хотя бы настолько (шум упаковки). */
    private static final float FACING_EPSILON = 0.02F;

    /** Отбрасывать задние грани ещё до записи вершин; выключить, если на глаз пропадут лицевые. */
    private static final boolean CULL_BACK_FACES = true;

    private static final String[] TEXTURE_FIELDS = { "texture", "textureView", "depthTexture", "depthTextureView" };

    private static boolean releaseFailed;
    private static boolean renderFailed;

    private long drawnModel = -1L;
    private PreviewCamera.@Nullable Key drawnCamera;

    @Override
    public Class<PreviewRenderState> getRenderStateClass()
    {
        return PreviewRenderState.class;
    }

    @Override
    protected String getTextureLabel()
    {
        return "layerlist_schematic_preview";
    }

    @Override
    protected float getTranslateY(int height, int guiScale)
    {
        return height / 2.0F;
    }

    @Override
    protected boolean textureIsReadyToBlit(PreviewRenderState state)
    {
        return state.model().id == this.drawnModel && state.camera().equals(this.drawnCamera);
    }

    @Override
    protected void renderToTexture(PreviewRenderState state, PoseStack poseStack, SubmitNodeCollector collector)
    {
        PreviewModel model = state.model();
        PreviewCamera.Key camera = state.camera();

        // Ключ ставим и при сбое: иначе упавшая отрисовка повторялась бы каждый кадр
        this.drawnModel = model.id;
        this.drawnCamera = camera;
        this.redrawn = true;

        // Это кадр GUI на клиентском потоке: исключение отсюда уронило бы игру, а превью — не повод
        try
        {
            submitModel(model, camera, poseStack, collector);
        }
        catch (RuntimeException | LinkageError e)
        {
            if (! renderFailed)
            {
                renderFailed = true;
                LOGGER.warn("Preview: drawing the schematic model failed; the preview stays empty", e);
            }
        }
    }

    // ===== Из видеопамяти: вершины на GPU один раз, кадр — вызов отрисовки =====

    /** Сбой GPU-пути (драйвер, другой API игры) — дальше рисуем по-старому, вершинами каждый кадр. */
    private static boolean gpuFailed;
    /** Моделей на видеокарте не больше: превью «Библиотеки» листают, старые выгружаются. */
    private static final int GPU_MODELS = 3;
    /** Слои вершинных буферов: три слоя блоков и одноцветные кубы упрощённого вида. */
    private static final int GPU_FLAT = PreviewModel.LAYERS;

    private final Map<Long, GpuModel> gpuModels = new LinkedHashMap<>(4, 0.75F, true);
    private final Projection gpuProjection = new Projection();
    private @Nullable ProjectionMatrixBuffer gpuProjectionBuffer;
    private final SubmitNodeStorage gpuSubmits = new SubmitNodeStorage();
    private @Nullable GpuTexture gpuColor;
    private @Nullable GpuTextureView gpuColorView;
    private @Nullable GpuTexture gpuDepth;
    private @Nullable GpuTextureView gpuDepthView;
    private long gpuDrawnModel = -1L;
    private PreviewCamera.@Nullable Key gpuDrawnCamera;

    /** Вершины модели на видеокарте: по буферу на слой и число квадов в нём. */
    private static final class GpuModel implements AutoCloseable
    {
        final @Nullable GpuBuffer[] layers = new GpuBuffer[GPU_FLAT + 1];
        final int[] quads = new int[GPU_FLAT + 1];

        @Override
        public void close()
        {
            for (int i = 0; i < this.layers.length; ++i)
            {
                GpuBuffer buffer = this.layers[i];

                if (buffer != null)
                {
                    buffer.close();
                    this.layers[i] = null;
                }
            }
        }
    }

    /** Самопроверка: рисовать по-старому, вершинами каждый кадр (сравнить с GPU-путём на одном стенде). */
    private static boolean forceCpu;
    /** Самопроверка: перерисовок текстуры и наносекунд клиентского потока на них с прошлого {@link #takeRedrawStats}. */
    private static long statRedraws;
    private static long statNanos;
    private boolean redrawn;

    public static long[] takeRedrawStats()
    {
        long[] stats = { statRedraws, statNanos };
        statRedraws = 0L;
        statNanos = 0L;
        return stats;
    }

    @Override
    public void prepare(PreviewRenderState state, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale)
    {
        long started = System.nanoTime();
        this.redrawn = false;

        try
        {
            this.prepareAny(state, gui, features, guiScale);
        }
        finally
        {
            if (this.redrawn)
            {
                ++statRedraws;
                statNanos += System.nanoTime() - started;
            }
        }
    }

    private void prepareAny(PreviewRenderState state, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale)
    {
        if (gpuFailed || forceCpu || state.camera().perspective())
        {
            super.prepare(state, gui, features, guiScale);
            return;
        }

        try
        {
            this.prepareGpu(state, gui, features, guiScale);
        }
        catch (RuntimeException | LinkageError e)
        {
            gpuFailed = true;
            LOGGER.warn("Preview: drawing from video memory failed; falling back to per-frame vertices", e);
            this.releaseGpu();
            super.prepare(state, gui, features, guiScale);
        }
    }

    /** Как {@code PictureInPictureRenderer.prepare}, только свои текстуры и вершины уже на GPU. */
    private void prepareGpu(PreviewRenderState state, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale)
    {
        int width = (state.x1() - state.x0()) * guiScale;
        int height = (state.y1() - state.y0()) * guiScale;
        PreviewModel model = state.model();
        PreviewCamera.Key camera = state.camera();
        boolean resize = this.gpuColor == null || this.gpuColor.getWidth(0) != width || this.gpuColor.getHeight(0) != height;

        if (! resize && model.id == this.gpuDrawnModel && camera.equals(this.gpuDrawnCamera))
        {
            this.blitGpu(state, gui);
            return;
        }

        // Ключ ставим и при сбое: иначе упавшая отрисовка повторялась бы каждый кадр
        this.gpuDrawnModel = model.id;
        this.gpuDrawnCamera = camera;
        this.redrawn = true;

        GpuDevice device = RenderSystem.getDevice();

        if (resize)
        {
            this.closeGpuTextures();
            this.gpuColor = device.createTexture(() -> "UI layerlist preview texture",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM,
                    width, height, 1, 1);
            this.gpuColorView = device.createTextureView(this.gpuColor);
            this.gpuDepth = device.createTexture(() -> "UI layerlist preview depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.D32_FLOAT, width, height, 1, 1);
            this.gpuDepthView = device.createTextureView(this.gpuDepth);
        }

        device.createCommandEncoder().clearColorAndDepthTextures(this.gpuColor, GuiRenderer.CLEAR_COLOR, this.gpuDepth, 0.0);

        if (this.gpuProjectionBuffer == null)
        {
            this.gpuProjectionBuffer = new ProjectionMatrixBuffer("PIP - layerlist preview");
        }

        this.gpuProjection.setupOrtho(-1000.0F, 1000.0F, width, height, true);
        RenderSystem.setProjectionMatrix(this.gpuProjectionBuffer.getBuffer(this.gpuProjection), ProjectionType.ORTHOGRAPHIC);

        PoseStack pose = new PoseStack();
        pose.translate(width / 2.0F, this.getTranslateY(height, guiScale), 0.0F);
        float scale = guiScale * state.scale();
        pose.scale(scale, scale, -scale);
        orthoTransform(model, camera, pose);

        GpuModel gpu = this.gpuModel(model);
        int maxQuads = 0;

        for (int count : gpu.quads)
        {
            maxQuads = Math.max(maxQuads, count);
        }

        // Матрица вида — в униформы типов рендера (их снимок берёт prepare()); вершины остаются в координатах схемы
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        PreparedRenderType[] types = new PreparedRenderType[GPU_FLAT + 1];
        modelView.pushMatrix();

        try
        {
            modelView.mul(pose.last().pose());

            for (int layer = 0; layer <= GPU_FLAT; ++layer)
            {
                if (gpu.quads[layer] > 0)
                {
                    types[layer] = layerType(layer).prepare();
                }
            }
        }
        finally
        {
            modelView.popMatrix();
        }

        // Сундуки, флаги, головы — особыми моделями, как раньше, в тот же проход
        submitSpecials(model, pose, this.gpuSubmits);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(Math.max(1, maxQuads) * 6);
        FeatureRenderDispatcher.PreparedFrame frame = features.prepareFrame(this.gpuSubmits);

        try (frame; RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Material HUD preview", this.gpuColorView,
                Optional.empty(), this.gpuDepthView, OptionalDouble.empty()))
        {
            RenderSystem.bindDefaultUniforms(pass);

            for (int layer : new int[] { PreviewModel.SOLID, PreviewModel.CUTOUT, GPU_FLAT, PreviewModel.TRANSLUCENT })
            {
                PreparedRenderType type = types[layer];
                GpuBuffer vertices = gpu.layers[layer];

                if (type != null && vertices != null)
                {
                    int count = gpu.quads[layer] * 6;
                    type.drawFromBuffer(new StagedVertexBuffer.ExecuteInfo(vertices, indexBuffer, indices.type(), 0, 0, count,
                            PrimitiveTopology.QUADS), pass);
                }
            }

            FeatureRenderDispatcher.renderAllFeatures(pass, frame);
        }

        this.blitGpu(state, gui);
    }

    private static RenderType layerType(int layer)
    {
        return switch (layer)
        {
            case PreviewModel.SOLID -> RenderTypes.solidMovingBlock();
            case PreviewModel.CUTOUT -> RenderTypes.cutoutMovingBlock();
            case PreviewModel.TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            default -> PreviewModule.flatType();
        };
    }

    /** Вершины модели на видеокарте; нет — собрать и загрузить (один раз на модель). */
    private GpuModel gpuModel(PreviewModel model)
    {
        GpuModel known = this.gpuModels.get(model.id);

        if (known != null)
        {
            return known;
        }

        GpuModel gpu = new GpuModel();

        try
        {
            java.nio.ByteBuffer[] packed = model.packed;

            // Обычно вершины уже упакованы в фоне сборки — только скопировать на GPU и отпустить
            if (packed != null)
            {
                model.packed = null;

                for (int layer = 0; layer <= GPU_FLAT; ++layer)
                {
                    java.nio.ByteBuffer bytes = packed[layer];

                    if (bytes != null)
                    {
                        int index = layer;
                        gpu.layers[layer] = RenderSystem.getDevice().createBuffer(() -> "layerlist preview layer " + index,
                                GpuBuffer.USAGE_VERTEX, bytes);
                        gpu.quads[layer] = model.packedQuads[layer];
                    }
                }
            }
            else if (model.flat != null)
            {
                upload(gpu, GPU_FLAT, java.util.List.of(model.flat));
            }

            for (int layer = 0; packed == null && layer < PreviewModel.LAYERS; ++layer)
            {
                java.util.List<PreviewModel.Quads> parts = new java.util.ArrayList<>();

                for (PreviewModel.Section section : model.sections)
                {
                    PreviewModel.Quads quads = section.layers[layer];

                    if (quads != null && quads.count > 0)
                    {
                        parts.add(quads);
                    }
                }

                upload(gpu, layer, parts);
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            gpu.close();
            throw e;
        }

        this.gpuModels.put(model.id, gpu);

        // Старые модели — с видеокарты долой
        Iterator<Map.Entry<Long, GpuModel>> it = this.gpuModels.entrySet().iterator();

        while (this.gpuModels.size() > GPU_MODELS && it.hasNext())
        {
            Map.Entry<Long, GpuModel> oldest = it.next();

            if (oldest.getKey() != model.id)
            {
                oldest.getValue().close();
                it.remove();
            }
        }

        return gpu;
    }

    /** Квады слоя — в один вершинный буфер (формат блоков), координаты схемы; модель, упакованная не в фоне (старая). */
    private static void upload(GpuModel gpu, int layer, java.util.List<PreviewModel.Quads> parts)
    {
        int total = 0;

        for (PreviewModel.Quads quads : parts)
        {
            total += quads.count;
        }

        if (total == 0)
        {
            return;
        }

        gpu.layers[layer] = RenderSystem.getDevice().createBuffer(() -> "layerlist preview layer " + layer, GpuBuffer.USAGE_VERTEX,
                PreviewModel.packVertices(parts, total));
        gpu.quads[layer] = total;
    }

    /** Копия своей текстуры в кадр GUI — как {@link #blitTexture}, с тем же отражением карты. */
    private void blitGpu(PreviewRenderState state, GuiRenderState gui)
    {
        float u0 = state.flipX() ? 1.0F : 0.0F;
        float v0 = state.flipY() ? 0.0F : 1.0F;
        gui.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(this.gpuColorView, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                state.pose(), state.x0(), state.y0(), state.x1(), state.y1(), u0, 1.0F - u0, v0, 1.0F - v0, -1,
                state.scissorArea(), null));
    }

    private void closeGpuTextures()
    {
        if (this.gpuColorView != null)
        {
            this.gpuColorView.close();
        }

        if (this.gpuColor != null)
        {
            this.gpuColor.close();
        }

        if (this.gpuDepthView != null)
        {
            this.gpuDepthView.close();
        }

        if (this.gpuDepth != null)
        {
            this.gpuDepth.close();
        }

        this.gpuColor = null;
        this.gpuColorView = null;
        this.gpuDepth = null;
        this.gpuDepthView = null;
    }

    /** Всё, что GPU-путь держит в видеопамяти: текстуры кадра и вершины моделей. */
    private void releaseGpu()
    {
        this.gpuDrawnModel = -1L;
        this.gpuDrawnCamera = null;
        this.closeGpuTextures();

        for (GpuModel gpu : this.gpuModels.values())
        {
            gpu.close();
        }

        this.gpuModels.clear();
    }

    /**
     * Модель в позу «картинки в картинке»: {@code poseStack} — центр текстуры, масштаб пикселей и Z от зрителя,
     * как у ванильного PIP ({@code translate(w/2, h/2)} + {@code scale(s, s, −s)}). Общая для экрана и
     * снимка выгрузки ({@link PreviewCapture}): снимок дорисовывает ту же модель той же цепочкой.
     */
    static void submitModel(PreviewModel model, PreviewCamera.Key camera, PoseStack poseStack, SubmitNodeCollector collector)
    {
        if (camera.perspective())
        {
            submitPerspective(model, camera, poseStack, collector);
            return;
        }

        orthoTransform(model, camera, poseStack);

        float[] facing = camera.facingRow();
        PreviewModel.Quads flat = model.flat;

        if (flat != null)
        {
            if (flat.count > 0)
            {
                collector.submitCustomGeometry(poseStack, PreviewModule.flatType(),
                        (pose, consumer) -> emit(flat, pose.pose(), facing, consumer));
            }

            return;
        }

        submitLayer(model, PreviewModel.SOLID, RenderTypes.solidMovingBlock(), poseStack, collector, facing);
        submitLayer(model, PreviewModel.CUTOUT, RenderTypes.cutoutMovingBlock(), poseStack, collector, facing);

        if (model.hasLayer(PreviewModel.TRANSLUCENT))
        {
            collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(),
                    (pose, consumer) -> emitSorted(model, pose.pose(), facing, consumer));
        }

        submitSpecials(model, poseStack, collector);
    }

    /** Орбита: из позы PIP (центр текстуры, пиксели GUI) — в координаты схемы, повёрнутые и увеличенные камерой. */
    private static void orthoTransform(PreviewModel model, PreviewCamera.Key camera, PoseStack poseStack)
    {
        float guiScale = poseStack.last().pose().m00();
        float depth = Math.min(1.0F, DEPTH_LIMIT / Math.max(1.0E-3F, guiScale * camera.zoom() * model.radius));

        poseStack.scale(1.0F, -1.0F, -1.0F);
        poseStack.translate(camera.panX(), camera.panY(), 0.0F);
        poseStack.scale(camera.zoom(), camera.zoom(), camera.zoom() * depth);
        poseStack.mulPose(camera.rotation());
        poseStack.translate(-model.centerX, -model.centerY, -model.centerZ);
    }

    /**
     * Копия готовой картинки в кадр GUI — как у базового класса; карта сверху отражённого размещения
     * копируется зеркально: перевёрнутыми координатами текстуры, а не позой, — рамка PIP и обход вершин
     * четырёхугольника остаются прежними. Поле текстуры приватное — отражением, как в {@link #releaseTexture}.
     */
    @Override
    protected void blitTexture(PreviewRenderState state, GuiRenderState gui)
    {
        GpuTextureView view = state.flipX() || state.flipY() ? textureView(this) : null;

        if (view == null)
        {
            super.blitTexture(state, gui);
            return;
        }

        float u0 = state.flipX() ? 1.0F : 0.0F;
        float v0 = state.flipY() ? 0.0F : 1.0F;
        gui.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                state.pose(), state.x0(), state.y0(), state.x1(), state.y1(), u0, 1.0F - u0, v0, 1.0F - v0, -1,
                state.scissorArea(), null));
    }

    private static @Nullable Field textureViewField;

    private static @Nullable GpuTextureView textureView(PictureInPictureRenderer<?> renderer)
    {
        try
        {
            if (textureViewField == null)
            {
                Field field = PictureInPictureRenderer.class.getDeclaredField("textureView");
                field.setAccessible(true);
                textureViewField = field;
            }

            return textureViewField.get(renderer) instanceof GpuTextureView view ? view : null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Освободить текстуры PIP, когда превью закрыто: базовый класс держит их до следующего кадра с
     * этим рендерером, а он может не наступить до выхода из игры. Поля приватные — отражением; если
     * их переименуют, текстура просто останется до следующего показа (тихо, одно предупреждение в лог).
     * Следующий показ создаст текстуры заново: при {@code texture == null} базовый класс считает,
     * что нужен новый размер.
     */
    void releaseTexture()
    {
        this.drawnModel = -1L;
        this.drawnCamera = null;
        this.releaseGpu();

        if (releaseFailed)
        {
            return;
        }

        try
        {
            for (String name : TEXTURE_FIELDS)
            {
                Field field = PictureInPictureRenderer.class.getDeclaredField(name);
                field.setAccessible(true);

                if (field.get(this) instanceof AutoCloseable resource)
                {
                    resource.close();
                }

                field.set(this, null);
            }
        }
        catch (Exception e)
        {
            releaseFailed = true;
            LOGGER.warn("Preview: could not release the picture-in-picture texture early; it stays until next use", e);
        }
    }

    private static void submitLayer(PreviewModel model, int layer, RenderType type, PoseStack poseStack,
            SubmitNodeCollector collector, float[] facing)
    {
        if (! model.hasLayer(layer))
        {
            return;
        }

        collector.submitCustomGeometry(poseStack, type, (pose, consumer) ->
        {
            for (PreviewModel.Section section : model.sections)
            {
                PreviewModel.Quads quads = section.layers[layer];

                if (quads != null)
                {
                    emit(quads, pose.pose(), facing, consumer);
                }
            }
        });
    }

    /**
     * Сундуки, шалкеры, флаги, головы, вазы: в JSON у них только частица, вид даёт особая модель.
     * Ей нужен направленный свет сущностей — тот же, что ванильное GUI ставит для существ в окне.
     */
    private static void submitSpecials(PreviewModel model, PoseStack poseStack, SubmitNodeCollector collector)
    {
        if (model.specialCount == 0)
        {
            return;
        }

        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

        int left = PreviewMesher.SPECIAL_CAP;

        for (PreviewModel.Section section : model.sections)
        {
            int[] specials = section.specials;

            for (int i = 0; i + 3 < specials.length && left > 0; i += 4, --left)
            {
                BlockModelRenderState special = model.specialState(specials[i + 3]);

                if (special == null)
                {
                    continue;
                }

                poseStack.pushPose();

                try
                {
                    poseStack.translate(specials[i], specials[i + 1], specials[i + 2]);
                    special.submit(poseStack, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                }
                finally
                {
                    poseStack.popPose();
                }
            }
        }
    }

    // ===== Перспектива из глаза (превью области мира, {@link EyeCamera}) =====

    /**
     * Проекция на CPU: точка → вид (поворот, сдвиг от глаза) → деление на глубину; глубина в позе PIP — линейно по
     * расстоянию в пределах ±{@link #DEPTH_LIMIT}. Задние грани — по направлению на глаз, прозрачные — от дальних к
     * ближним, квады у самого глаза (за ближней плоскостью) не рисуются. Особые блоки (сундуки…) рисуются
     * ортопроекцией, в перспективе их нет.
     */
    private static void submitPerspective(PreviewModel model, PreviewCamera.Key camera, PoseStack poseStack,
            SubmitNodeCollector collector)
    {
        float guiScale = poseStack.last().pose().m00();
        Projector projector = new Projector(camera, DEPTH_LIMIT / Math.max(1.0E-3F, guiScale));
        poseStack.scale(1.0F, -1.0F, -1.0F);

        PreviewModel.Quads flat = model.flat;

        if (flat != null)
        {
            if (flat.count > 0)
            {
                collector.submitCustomGeometry(poseStack, PreviewModule.flatType(),
                        (pose, consumer) -> emitPerspective(flat, pose.pose(), projector, consumer));
            }

            return;
        }

        for (int layer : new int[] { PreviewModel.SOLID, PreviewModel.CUTOUT })
        {
            if (! model.hasLayer(layer))
            {
                continue;
            }

            RenderType type = layer == PreviewModel.SOLID ? RenderTypes.solidMovingBlock() : RenderTypes.cutoutMovingBlock();
            collector.submitCustomGeometry(poseStack, type, (pose, consumer) ->
            {
                for (PreviewModel.Section section : model.sections)
                {
                    PreviewModel.Quads quads = section.layers[layer];

                    if (quads != null)
                    {
                        emitPerspective(quads, pose.pose(), projector, consumer);
                    }
                }
            });
        }

        if (model.hasLayer(PreviewModel.TRANSLUCENT))
        {
            collector.submitCustomGeometry(poseStack, RenderTypes.translucentMovingBlock(),
                    (pose, consumer) -> emitPerspectiveSorted(model, pose.pose(), projector, consumer));
        }
    }

    /** Поворот вида, глаз, фокус и масштаб глубины для одного кадра. */
    private static final class Projector
    {
        final float r00, r01, r02, r10, r11, r12, r20, r21, r22;
        final float ex, ey, ez;
        final float focal;
        final float depth;

        Projector(PreviewCamera.Key key, float depth)
        {
            Matrix4fc r = key.rotation();
            // Поворот вида как матрица JOML (столбцы): вид = R · мир
            this.r00 = r.m00(); this.r01 = r.m10(); this.r02 = r.m20();
            this.r10 = r.m01(); this.r11 = r.m11(); this.r12 = r.m21();
            this.r20 = r.m02(); this.r21 = r.m12(); this.r22 = r.m22();
            this.ex = key.eyeX();
            this.ey = key.eyeY();
            this.ez = key.eyeZ();
            this.focal = key.focal();
            this.depth = depth;
        }

        /** Точка мира → {x, y, z} позы PIP; {@code false} — точка за ближней плоскостью. */
        boolean project(float x, float y, float z, float[] out)
        {
            float dx = x - this.ex;
            float dy = y - this.ey;
            float dz = z - this.ez;
            float vx = this.r00 * dx + this.r01 * dy + this.r02 * dz;
            float vy = this.r10 * dx + this.r11 * dy + this.r12 * dz;
            float vz = this.r20 * dx + this.r21 * dy + this.r22 * dz;

            if (vz > -EyeCamera.NEAR)
            {
                return false;
            }

            float dist = -vz;
            out[0] = this.focal * vx / dist;
            out[1] = this.focal * vy / dist;
            out[2] = this.depth - 2.0F * this.depth * Math.min(1.0F, dist / EyeCamera.FAR);
            return true;
        }

        boolean facesEye(PreviewModel.Quads quads, int q)
        {
            int normal = quads.normal[q];

            if (normal == PreviewModel.Quads.NORMAL_NONE)
            {
                return true;
            }

            float[] pos = quads.pos;
            int base = q * 12;
            float cx = (pos[base] + pos[base + 3] + pos[base + 6] + pos[base + 9]) * 0.25F;
            float cy = (pos[base + 1] + pos[base + 4] + pos[base + 7] + pos[base + 10]) * 0.25F;
            float cz = (pos[base + 2] + pos[base + 5] + pos[base + 8] + pos[base + 11]) * 0.25F;
            float dot = PreviewModel.Quads.normalX(normal) * (this.ex - cx) + PreviewModel.Quads.normalY(normal) * (this.ey - cy)
                    + PreviewModel.Quads.normalZ(normal) * (this.ez - cz);
            return dot >= -FACING_EPSILON;
        }
    }

    private static void emitPerspective(PreviewModel.Quads quads, Matrix4fc m, Projector projector, VertexConsumer consumer)
    {
        float[] out = new float[12];

        for (int q = 0; q < quads.count; ++q)
        {
            if (projector.facesEye(quads, q))
            {
                emitQuadPerspective(quads, q, m, projector, consumer, out);
            }
        }
    }

    private static void emitPerspectiveSorted(PreviewModel model, Matrix4fc m, Projector projector, VertexConsumer consumer)
    {
        int total = 0;

        for (PreviewModel.Section section : model.sections)
        {
            PreviewModel.Quads quads = section.layers[PreviewModel.TRANSLUCENT];
            total += quads != null ? quads.count : 0;
        }

        long[] order = new long[total];
        PreviewModel.Quads[] owners = new PreviewModel.Quads[total];
        int[] indices = new int[total];
        int n = 0;

        for (PreviewModel.Section section : model.sections)
        {
            PreviewModel.Quads quads = section.layers[PreviewModel.TRANSLUCENT];

            if (quads == null)
            {
                continue;
            }

            for (int q = 0; q < quads.count; ++q)
            {
                if (! projector.facesEye(quads, q))
                {
                    continue;
                }

                float[] pos = quads.pos;
                int base = q * 12;
                float cx = (pos[base] + pos[base + 3] + pos[base + 6] + pos[base + 9]) * 0.25F - projector.ex;
                float cy = (pos[base + 1] + pos[base + 4] + pos[base + 7] + pos[base + 10]) * 0.25F - projector.ey;
                float cz = (pos[base + 2] + pos[base + 5] + pos[base + 8] + pos[base + 11]) * 0.25F - projector.ez;
                // Дальние раньше: ключ — минус квадрат расстояния
                owners[n] = quads;
                indices[n] = q;
                order[n] = (long) sortableBits(-(cx * cx + cy * cy + cz * cz)) << 32 | n;
                ++n;
            }
        }

        Arrays.sort(order, 0, n);
        float[] out = new float[12];

        for (int i = 0; i < n; ++i)
        {
            int slot = (int) order[i];
            emitQuadPerspective(owners[slot], indices[slot], m, projector, consumer, out);
        }
    }

    private static void emitQuadPerspective(PreviewModel.Quads quads, int q, Matrix4fc m, Projector projector,
            VertexConsumer consumer, float[] out)
    {
        float[] pos = quads.pos;
        float[] p = new float[3];

        for (int v = 0; v < 4; ++v)
        {
            int i = q * 12 + v * 3;

            if (! projector.project(pos[i], pos[i + 1], pos[i + 2], p))
            {
                return;
            }

            out[v * 3] = p[0];
            out[v * 3 + 1] = p[1];
            out[v * 3 + 2] = p[2];
        }

        float[] uv = quads.uv;
        int[] color = quads.color;

        for (int v = 0; v < 4; ++v)
        {
            float x = out[v * 3];
            float y = out[v * 3 + 1];
            float z = out[v * 3 + 2];

            consumer.addVertex(
                    m.m00() * x + m.m10() * y + m.m20() * z + m.m30(),
                    m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
                    m.m02() * x + m.m12() * y + m.m22() * z + m.m32(),
                    color[q * 4 + v], uv[q * 8 + v * 2], uv[q * 8 + v * 2 + 1],
                    OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, 0.0F, 1.0F, 0.0F);
        }
    }

    /** Вершины квадов в позу PIP; задние грани пропускаем. */
    private static void emit(PreviewModel.Quads quads, Matrix4fc m, float[] facing, VertexConsumer consumer)
    {
        for (int q = 0; q < quads.count; ++q)
        {
            if (! facesViewer(quads.normal[q], facing))
            {
                continue;
            }

            emitQuad(quads, q, m, consumer);
        }
    }

    /**
     * Полупрозрачные квады всех секций — от дальних к ближним по глубине центра в позе PIP (там Z
     * растёт к зрителю). Ванильная сортировка при выгрузке тоже есть, но при «улучшенной прозрачности»
     * в настройках она выключена, а PIP её конвейеры не использует — сортируем сами.
     */
    private static void emitSorted(PreviewModel model, Matrix4fc m, float[] facing, VertexConsumer consumer)
    {
        int total = 0;

        for (PreviewModel.Section section : model.sections)
        {
            PreviewModel.Quads quads = section.layers[PreviewModel.TRANSLUCENT];
            total += quads != null ? quads.count : 0;
        }

        // Ключ сортировки и ссылка на квад в одном long: старшие биты — глубина, младшие — номер
        long[] order = new long[total];
        PreviewModel.Quads[] owners = new PreviewModel.Quads[total];
        int[] indices = new int[total];
        int n = 0;

        for (PreviewModel.Section section : model.sections)
        {
            PreviewModel.Quads quads = section.layers[PreviewModel.TRANSLUCENT];

            if (quads == null)
            {
                continue;
            }

            for (int q = 0; q < quads.count; ++q)
            {
                if (! facesViewer(quads.normal[q], facing))
                {
                    continue;
                }

                float[] pos = quads.pos;
                int base = q * 12;
                float cx = (pos[base] + pos[base + 3] + pos[base + 6] + pos[base + 9]) * 0.25F;
                float cy = (pos[base + 1] + pos[base + 4] + pos[base + 7] + pos[base + 10]) * 0.25F;
                float cz = (pos[base + 2] + pos[base + 5] + pos[base + 8] + pos[base + 11]) * 0.25F;
                float depth = m.m02() * cx + m.m12() * cy + m.m22() * cz + m.m32();

                owners[n] = quads;
                indices[n] = q;
                order[n] = (long) sortableBits(depth) << 32 | n;
                ++n;
            }
        }

        Arrays.sort(order, 0, n);

        for (int i = 0; i < n; ++i)
        {
            int slot = (int) order[i];
            emitQuad(owners[slot], indices[slot], m, consumer);
        }
    }

    private static void emitQuad(PreviewModel.Quads quads, int q, Matrix4fc m, VertexConsumer consumer)
    {
        float[] pos = quads.pos;
        float[] uv = quads.uv;
        int[] color = quads.color;

        for (int v = 0; v < 4; ++v)
        {
            int p = q * 12 + v * 3;
            float x = pos[p];
            float y = pos[p + 1];
            float z = pos[p + 2];

            consumer.addVertex(
                    m.m00() * x + m.m10() * y + m.m20() * z + m.m30(),
                    m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
                    m.m02() * x + m.m12() * y + m.m22() * z + m.m32(),
                    color[q * 4 + v], uv[q * 8 + v * 2], uv[q * 8 + v * 2 + 1],
                    OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, 0.0F, 1.0F, 0.0F);
        }
    }

    private static boolean facesViewer(int normal, float[] facing)
    {
        if (! CULL_BACK_FACES || normal == PreviewModel.Quads.NORMAL_NONE)
        {
            return true;
        }

        float dot = facing[0] * PreviewModel.Quads.normalX(normal)
                + facing[1] * PreviewModel.Quads.normalY(normal)
                + facing[2] * PreviewModel.Quads.normalZ(normal);

        return dot >= -FACING_EPSILON;
    }

    /** Биты float как int со знаком, упорядоченные так же, как сами числа (для сортировки через long). */
    private static int sortableBits(float value)
    {
        int bits = Float.floatToIntBits(value);

        return bits >= 0 ? bits : bits ^ 0x7FFFFFFF;
    }
}
