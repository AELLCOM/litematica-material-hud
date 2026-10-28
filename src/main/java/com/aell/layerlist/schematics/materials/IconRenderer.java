package com.aell.layerlist.schematics.materials;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Рисует иконки {@link ItemIcons} игрой — тем же путём, каким инвентарь рисует предмет в слот: модель
 * предмета в положении {@link ItemDisplayContext#GUI} (поворот и масштаб из модели предмета), особые
 * отрисовщики (сундук, шалкер, кровать, голова, флаг с узором, ваза, щит, проводник…), свет
 * предметов инвентаря ({@code ITEMS_3D} у объёмных, {@code ITEMS_FLAT} у плоских), полная яркость.
 *
 * <p>Почему «картинка в картинке»: рисовать можно только внутри кадра GUI — там {@code GameRenderer}
 * подставляет световую карту интерфейса (вне его предмет взял бы освещение мира: ночью темнее).
 * Реестр Fabric даёт рендереру вызов {@link #prepare} в этой фазе; пока есть очередь, {@link ItemIcons}
 * при сборке кадра GUI кладёт в его состояние невидимую заявку {@link Pass}, а {@link #prepare}
 * переопределён целиком: на экран ничего не выводится, рисуется своя текстура-сетка.
 *
 * <p>Кадр: клетки {@code size}×{@code size} (до 8×8, сторона ≤ 1024) в своей текстуре цвета и глубины;
 * на клетку — свой проход с ножницами (предмет не залезает к соседу, как не вылезает из слота),
 * ортопроекция на всю текстуру, единица модели — вся клетка (слот 16 при масштабе GUI {@code size/16}).
 * Пиксели — копией текстуры в буфер видеокарты; ответ приходит через кадр-два на клиентском потоке.
 * Цвет в текстуре домножен на альфу (GUI так её и смешивает) — при чтении переводим обратно.
 */
@Environment(EnvType.CLIENT)
final class IconRenderer extends PictureInPictureRenderer<IconRenderer.Pass>
{
    /** Сторона текстуры-сетки не больше этого; клеток в ряд — не больше {@link #MAX_COLUMNS}. */
    private static final int MAX_SIDE = 1024;
    private static final int MAX_COLUMNS = 8;
    private static final Vector4fc TRANSPARENT = new Vector4f(0.0F);
    /** Глубина ортопроекции — как у предметов инвентаря и «картинки в картинке» (±1000). */
    private static final float DEPTH_RANGE = 1000.0F;
    private static final int RGBA = 4;

    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("layerlist item icons");
    private SubmitNodeStorage submits = new SubmitNodeStorage();

    private @Nullable Targets targets;

    /** Невидимая заявка на вызов рендерера в кадре GUI: прямоугольник 1×1, ничего не выводит. */
    record Pass(int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds) implements PictureInPictureRenderState
    {
        static final Pass INSTANCE = new Pass(0, 0, 1, 1, 1.0F, null, new ScreenRectangle(0, 0, 1, 1));
    }

    /** Клетка, в которую предмет нарисовался: левый верхний угол в пикселях текстуры. */
    private record Cell(ItemIcons.Job job, int left, int top) {}

    /** Текстуры сетки одного размера: цвет (рисуем и читаем) и глубина, у каждой — вид для прохода. */
    private record Targets(int side, GpuTexture color, GpuTextureView colorView, GpuTexture depth, GpuTextureView depthView)
    {
        static Targets create(int side)
        {
            GpuDevice device = RenderSystem.getDevice();
            GpuTexture color = device.createTexture(() -> "layerlist item icons",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.RGBA8_UNORM, side, side, 1, 1);
            GpuTexture depth = device.createTexture(() -> "layerlist item icons depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, side, side, 1, 1);

            return new Targets(side, color, device.createTextureView(color), depth, device.createTextureView(depth));
        }

        /** Виды — раньше своих текстур. */
        void release()
        {
            this.colorView.close();
            this.depthView.close();
            this.color.close();
            this.depth.close();
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
        return "layerlist_item_icons";
    }

    @Override
    protected void renderToTexture(Pass state, PoseStack poseStack, SubmitNodeCollector collector)
    {
        // Не зовётся: prepare переопределён целиком (своя текстура-сетка, на экран ничего)
    }

    @Override
    public void prepare(Pass state, GuiRenderState guiState, FeatureRenderDispatcher dispatcher, int guiScale)
    {
        ItemIcons.prepared();
        ItemIcons.Job first = ItemIcons.poll(0);

        if (first == null)
        {
            return;
        }

        List<ItemIcons.Job> taken = new ArrayList<>();
        taken.add(first);

        // Это кадр GUI на клиентском потоке: исключение отсюда уронило бы игру, а иконка — не повод
        try
        {
            this.drawBatch(taken, dispatcher);
        }
        catch (RuntimeException | LinkageError e)
        {
            for (ItemIcons.Job job : taken)
            {
                if (! job.future.isDone())
                {
                    ItemIcons.failed(job, e, false);
                }
            }
        }
    }

    /** Забрать у видеокарты текстуры сетки (заявок давно нет). Клиентский поток. */
    void releaseTextures()
    {
        Targets held = this.targets;
        this.targets = null;

        if (held != null)
        {
            held.release();
        }
    }

    @Override
    public void close()
    {
        this.releaseTextures();
        this.projectionBuffer.close();
        super.close();
    }

    private void drawBatch(List<ItemIcons.Job> taken, FeatureRenderDispatcher dispatcher)
    {
        Minecraft mc = Minecraft.getInstance();
        int size = taken.getFirst().size;
        int columns = Math.max(1, Math.min(MAX_COLUMNS, MAX_SIDE / size));
        int textureSide = columns * size;
        Targets grid = this.targets(textureSide);

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        encoder.clearColorAndDepthTextures(grid.color(), TRANSPARENT, grid.depth(), 0.0);

        // Проекцию, свет и вид модели меняем только на время сетки — дальше кадр GUI рисует как рисовал
        GpuBufferSlice projectionBefore = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType typeBefore = RenderSystem.getProjectionType();
        GpuBufferSlice lightsBefore = RenderSystem.getShaderLights();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();

        this.projection.setupOrtho(-DEPTH_RANGE, DEPTH_RANGE, textureSide, textureSide, true);
        RenderSystem.setProjectionMatrix(this.projectionBuffer.getBuffer(this.projection), ProjectionType.ORTHOGRAPHIC);

        List<Cell> cells = new ArrayList<>();
        long start = Util.getMillis();
        long budget = ItemIcons.frameBudgetMs();

        try
        {
            int index = 0;
            ItemIcons.Job job = taken.getFirst();

            while (job != null)
            {
                int left = (index % columns) * size;
                int top = (index / columns) * size;

                // Сорвавшаяся клетка пропускается совсем: в ней могли остаться недорисованные пиксели
                if (this.drawCell(mc, encoder, dispatcher, grid, job, left, top))
                {
                    cells.add(new Cell(job, left, top));
                }

                if (++index >= columns * columns || Util.getMillis() - start >= budget)
                {
                    break;
                }

                job = ItemIcons.poll(size);

                if (job != null)
                {
                    taken.add(job);
                }
            }
        }
        finally
        {
            RenderSystem.disableScissorForRenderTypeDraws();
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

        if (cells.isEmpty())
        {
            return;
        }

        GpuBuffer buffer = device.createBuffer(() -> "layerlist item icons read-back",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) textureSide * textureSide * RGBA);
        encoder.copyTextureToBuffer(grid.color(), buffer, 0L, () -> readBack(buffer, textureSide, size, cells), 0);
    }

    /** Один предмет в клетку; {@code false} — не нарисован (заявка уже закрыта плашкой). */
    private boolean drawCell(Minecraft mc, CommandEncoder encoder, FeatureRenderDispatcher dispatcher, Targets grid,
            ItemIcons.Job job, int left, int top)
    {
        try
        {
            ItemStackRenderState item = new ItemStackRenderState();
            // Как слот инвентаря: владелец — игрок, мир — текущий, зерно 0
            mc.getItemModelResolver().updateForTopItem(item, job.stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);

            if (item.isEmpty())
            {
                ItemIcons.failed(job, null, true);
                return false;
            }

            int size = job.size;
            PoseStack pose = new PoseStack();
            // Центр клетки; модель занимает клетку целиком, ось Y модели — вверх
            pose.translate(left + size / 2.0F, top + size / 2.0F, 0.0F);
            pose.scale(size, -size, size);

            // Ножницы считаются от нижнего края текстуры
            RenderSystem.enableScissorForRenderTypeDraws(left, grid.side() - top - size, size, size);
            mc.gameRenderer.lighting().setupFor(item.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
            item.submit(pose, this.submits, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);

            // 26.2: проходов нет — вывод в свою текстуру через подмену цели, как в ванильном PIP
            GpuTextureView colorBefore = RenderSystem.outputColorTextureOverride;
            GpuTextureView depthBefore = RenderSystem.outputDepthTextureOverride;
            RenderSystem.outputColorTextureOverride = grid.colorView();
            RenderSystem.outputDepthTextureOverride = grid.depthView();

            try
            {
                dispatcher.renderAllFeatures(this.submits);
            }
            finally
            {
                RenderSystem.outputColorTextureOverride = colorBefore;
                RenderSystem.outputDepthTextureOverride = depthBefore;
            }

            return true;
        }
        catch (RuntimeException | LinkageError e)
        {
            // Недоотправленное в хранилище не должно попасть в следующую клетку
            this.submits = new SubmitNodeStorage();
            ItemIcons.failed(job, e, true);
            return false;
        }
    }

    /** Сетка нужного размера: прежняя или новая (размер иконок сменился — старые текстуры отдаём). */
    private Targets targets(int textureSide)
    {
        Targets held = this.targets;

        if (held != null && held.side() == textureSide)
        {
            return held;
        }

        this.releaseTextures();
        Targets created = Targets.create(textureSide);
        this.targets = created;

        return created;
    }

    /** Ответ видеокарты (клиентский поток): клетки — в пиксели, буфер — закрыть. */
    private static void readBack(GpuBuffer buffer, int textureSide, int size, List<Cell> cells)
    {
        List<int[]> pictures = new ArrayList<>(cells.size());
        Throwable failure = null;

        try (GpuBufferSlice.MappedView view = buffer.map(true, false))
        {
            ByteBuffer data = view.data();

            for (Cell cell : cells)
            {
                pictures.add(pixels(data, textureSide, size, cell.left(), cell.top()));
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = e;
        }
        finally
        {
            buffer.close();
        }

        for (int i = 0; i < cells.size(); ++i)
        {
            ItemIcons.Job job = cells.get(i).job();

            if (failure == null)
            {
                ItemIcons.drawn(job, pictures.get(i));
            }
            else
            {
                ItemIcons.failed(job, failure, false);
            }
        }
    }

    /**
     * Клетка из прочитанной текстуры в ARGB без домножения на альфу. Строки в буфере идут от нижнего
     * края текстуры (так же читает снимок экрана игры), байты пикселя — R, G, B, A.
     */
    private static int[] pixels(ByteBuffer data, int textureSide, int size, int left, int top)
    {
        int[] out = new int[size * size];

        for (int y = 0; y < size; ++y)
        {
            int row = textureSide - 1 - (top + y);
            int at = (row * textureSide + left) * RGBA;

            for (int x = 0; x < size; ++x, at += RGBA)
            {
                int a = data.get(at + 3) & 0xFF;

                if (a == 0)
                {
                    continue;
                }

                int r = data.get(at) & 0xFF;
                int g = data.get(at + 1) & 0xFF;
                int b = data.get(at + 2) & 0xFF;

                if (a < 0xFF)
                {
                    r = Math.min(0xFF, (r * 0xFF + a / 2) / a);
                    g = Math.min(0xFF, (g * 0xFF + a / 2) / a);
                    b = Math.min(0xFF, (b * 0xFF + a / 2) / a);
                }

                out[y * size + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return out;
    }
}
