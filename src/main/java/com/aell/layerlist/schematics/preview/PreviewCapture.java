package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Снимок 3D-превью схемы в картинку — для постера выгрузки (M26 {@code PreviewPoster}) и всего, что
 * захочет картинку модели вне экрана. Модель та же, что во вкладке: {@link PreviewMesher} с бюджетом
 * из настройки качества (большие схемы — упрощённым видом), камера — изометрия вкладки (рыскание 135°,
 * тангаж 35.264°, ортопроекция, свет слева затенением граней); масштаб и сдвиг подобраны по самой
 * геометрии, а не по габариту, — модель занимает кадр целиком с небольшим полем.
 *
 * <p>Как и иконки выгрузок ({@code ItemIcons}), рисовать можно только внутри кадра GUI: там игра
 * подставляет световую карту интерфейса. Поэтому снимок — заявка: сборка модели идёт в фоновом потоке
 * превью, потом в ближайшем кадре GUI {@link CaptureRenderer} рисует её в свои текстуры (вдвое крупнее
 * — для сглаживания; дважды — на чёрном и на белом фоне, чтобы честно узнать прозрачность) и копирует
 * пиксели в буферы видеокарты; ответ приходит через кадр-два, разделение цвета и прозрачности и
 * уменьшение — в фоне ({@link #matte}). Кадр просят только при сборке GUI (HUD в мире, конец
 * отрисовки любого экрана — и при открытом окне выгрузки): {@code Gui.extractRenderState} начинает
 * кадр со сброса состояния, и заявка, положенная в такте, пропала бы.
 *
 * <p>Будущее {@link #request} завершается <b>на клиентском потоке</b>: картинкой ({@link Shot}) или
 * исключением {@link CaptureFailed} (схема не прочиталась, сборка упала, кадры GUI не шли дольше
 * {@link #FRAME_TIMEOUT_MS}, видеокарта не ответила). {@code join()} на клиентском потоке — зависание:
 * только {@code isDone()}/{@code thenAccept}. Текстуры и буфер видеокарты отдаются сразу после чтения
 * (и при любом сбое).
 */
@Environment(EnvType.CLIENT)
public final class PreviewCapture
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics-preview");

    /** Размер по умолчанию: область модели на постере выгрузки. */
    public static final int DEFAULT_WIDTH = 1600;
    public static final int DEFAULT_HEIGHT = 1000;
    public static final int MIN_SIDE = 16;
    /** Текстура снимка (с запасом сглаживания) не больше этого по стороне — влезает в любую видеокарту. */
    public static final int MAX_SIDE = 4096;

    /** Во сколько раз текстура крупнее картинки: рёбра блоков без лесенки, мелкие текстуры без ряби. */
    private static final int SUPERSAMPLE = 2;
    /** Поле вокруг модели — доля меньшей стороны кадра. */
    private static final float FIT_MARGIN = 0.03F;

    /** Сборка модели огромной схемы идёт долго, но не дольше этого. */
    static final long MESH_TIMEOUT_MS = 180_000L;
    /** Модель готова, а кадра GUI нет столько — снимок не состоится (игра свёрнута, интерфейс скрыт F1). */
    static final long FRAME_TIMEOUT_MS = 20_000L;
    /** Нарисовано, а пиксели с видеокарты не пришли за столько. */
    static final long READ_TIMEOUT_MS = 20_000L;

    private static final Identifier HUD_HOOK = Identifier.fromNamespaceAndPath("layerlist", "preview_capture");

    private static final List<Job> ACTIVE = new ArrayList<>();
    private static final ArrayDeque<Job> READY = new ArrayDeque<>();

    private static boolean registered;
    private static boolean warnedFailure;

    private PreviewCapture() {}

    /**
     * Картинка модели: ARGB без домножения на альфу, фон прозрачный (полупрозрачная вода и стекло —
     * с настоящей альфой).
     *
     * @param image         картинка запрошенного размера
     * @param lodFactor     1 — каждый блок как в игре; 2, 4… — упрощённый вид кубами столько клеток снимка
     * @param snapshotScale 1 — снимок полный; 2, 4… — огромная схема уже снята грубой сеткой (клетка = столько блоков)
     * @param quads         граней в модели
     * @param empty         в модели нечего рисовать (схема из воздуха) — картинка прозрачная
     */
    public record Shot(BufferedImage image, int lodFactor, int snapshotScale, int quads, boolean empty)
    {
        /** Модель упрощена: кубы вместо настоящих блоков. */
        public boolean simplified()
        {
            return this.lodFactor > 1 || this.snapshotScale > 1;
        }

        /** Сторона одного куба упрощённого вида в блоках схемы; 1 — без упрощения. */
        public int cubeSize()
        {
            return Math.max(1, this.lodFactor) * Math.max(1, this.snapshotScale);
        }

        /** PNG этой картинки (кодирование — на вызывающем потоке). */
        public byte[] png()
        {
            return encode(this.image);
        }
    }

    /** Снимок не получился; текст — для лога, игроку не показывается. */
    public static final class CaptureFailed extends RuntimeException
    {
        CaptureFailed(String message, @Nullable Throwable cause)
        {
            super(message, cause);
        }
    }

    /**
     * Один раз из {@link PreviewModule#register()} (то есть из {@code onInitializeClient}): рендерер в
     * реестре «картинки в картинке» Fabric (только до создания {@code GameRenderer}), две точки сборки
     * кадра GUI, где очередь просит кадр, и такт клиента — снимать просроченные заявки.
     */
    static void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        SpecialGuiElementRegistry.register(context -> new CaptureRenderer(context.vertexConsumers()));
        // В мире без экрана; элемент ничего не рисует
        HudElementRegistry.addLast(HUD_HOOK, (graphics, deltaTracker) -> requestFrame());
        // Любой экран — в том числе «Схемы» с окном выгрузки поверх
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) -> requestFrame()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** Снимок схемы из каталога размером по умолчанию ({@value #DEFAULT_WIDTH}×{@value #DEFAULT_HEIGHT}). */
    public static CompletableFuture<Shot> request(SchematicEntry entry)
    {
        return request(entry, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Снимок схемы из каталога: блоки читает {@link SchematicLibrary#snapshot} (тот же снимок, что у
     * вкладки, общий кеш). Из любого потока; будущее завершается на клиентском.
     */
    public static CompletableFuture<Shot> request(SchematicEntry entry, int width, int height)
    {
        Objects.requireNonNull(entry, "entry");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<VoxelSnapshot> snapshot;

        try
        {
            snapshot = SchematicLibrary.get().snapshot(entry);
        }
        catch (RuntimeException e)
        {
            snapshot = CompletableFuture.failedFuture(e);
        }

        return snapshot.handleAsync((blocks, error) ->
        {
            if (blocks == null)
            {
                Throwable cause = unwrap(error);
                return CompletableFuture.<Shot>failedFuture(new CaptureFailed("schematic not read: " + describe(cause), cause));
            }

            return request(blocks, width, height);
        }, mc).thenCompose(Function.identity());
    }

    /**
     * Снимок готового снимка блоков {@code width}×{@code height} (каждая сторона {@value #MIN_SIDE}…
     * {@value #MAX_SIDE}). Из любого потока; будущее завершается на клиентском.
     */
    public static CompletableFuture<Shot> request(VoxelSnapshot snapshot, int width, int height)
    {
        Objects.requireNonNull(snapshot, "snapshot");
        Minecraft mc = Minecraft.getInstance();

        if (! mc.isSameThread())
        {
            return CompletableFuture.supplyAsync(() -> request(snapshot, width, height), mc).thenCompose(Function.identity());
        }

        int w = clamp(width);
        int h = clamp(height);
        // Сглаживание — пока текстура влезает; огромный кадр рисуется как есть
        int supersample = Math.max(1, Math.min(SUPERSAMPLE, MAX_SIDE / Math.max(w, h)));
        Job job = new Job(snapshot, w, h, supersample);

        start(job);

        return job.future;
    }

    /** Заявок в работе (сборка, кадр или чтение с видеокарты). Клиентский поток. */
    public static int pending()
    {
        return ACTIVE.size();
    }

    // ===== Ход заявки (клиентский поток, если не сказано иное) =====

    private static void start(Job job)
    {
        ACTIVE.add(job);
        // Белая текстура упрощённого вида живёт, пока есть хоть один пользователь превью
        PreviewModule.retain();
        job.retained = true;

        try
        {
            PreviewMesher.Context context = PreviewMesher.Context.capture(job.snapshot);
            int budget = PreviewMesher.budget(SchematicsConfig.previewQuality(), job.snapshot);

            job.mesh = PreviewMesher.start(context, Integer.MAX_VALUE, budget);
            job.since = Util.getMillis();
        }
        catch (RuntimeException | LinkageError e)
        {
            fail(job, new CaptureFailed("model build did not start", e));
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<PreviewModel> built = Objects.requireNonNull(job.mesh.future, "mesh future");

        // Поле кадра считаем по всем вершинам — в фоне; дальше снова клиентский поток
        built.thenApplyAsync(model -> new Framed(model, fit(model, job.width, job.height)), Util.backgroundExecutor())
                .whenCompleteAsync((framed, error) ->
                {
                    if (framed != null)
                    {
                        modelReady(job, framed);
                    }
                    else
                    {
                        Throwable cause = unwrap(error);
                        fail(job, new CaptureFailed("model build failed: " + describe(cause), cause));
                    }
                }, mc);
    }

    private static void modelReady(Job job, Framed framed)
    {
        if (job.stage != Stage.MESHING)
        {
            return;
        }

        job.model = framed.model();
        job.camera = framed.camera();

        if (framed.model().isEmpty())
        {
            // Рисовать нечего: прозрачная картинка, без кадра GUI
            BufferedImage blank = new BufferedImage(job.width, job.height, BufferedImage.TYPE_INT_ARGB);
            finish(job, new Shot(blank, framed.model().lodFactor(), job.snapshot.scale(), 0, true));
            return;
        }

        if (framed.model().flat != null)
        {
            // Как виджет: белую текстуру выгружаем до кадра GUI, а не посреди него
            PreviewModule.ensureFlatTexture();
        }

        job.stage = Stage.READY;
        job.since = Util.getMillis();
        READY.addLast(job);
    }

    /** Следующая заявка, готовая к кадру. Для {@link CaptureRenderer#prepare}. */
    static @Nullable Job pollReady()
    {
        Job job = READY.pollFirst();

        while (job != null && job.stage != Stage.READY)
        {
            job = READY.pollFirst();
        }

        return job;
    }

    /** Нарисовано, копия в буфер видеокарты заказана. Для {@link CaptureRenderer}. */
    static void drawn(Job job)
    {
        job.stage = Stage.DRAWN;
        job.since = Util.getMillis();
    }

    /**
     * Пиксели пришли (клиентский поток): два кадра RGBA (на чёрном и на белом фоне), строки от нижнего
     * края текстуры. Покрытие и цвет — по разнице кадров, уменьшение до запрошенного размера — в фоне,
     * итог — снова на клиентском потоке.
     */
    static void readBack(Job job, byte[] onBlack, byte[] onWhite)
    {
        if (job.stage != Stage.DRAWN)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        PreviewModel model = Objects.requireNonNull(job.model, "model");

        CompletableFuture.supplyAsync(() -> matte(onBlack, onWhite, job.width, job.height, job.supersample), Util.backgroundExecutor())
                .whenCompleteAsync((image, error) ->
                {
                    if (image != null)
                    {
                        finish(job, new Shot(image, model.lodFactor(), job.snapshot.scale(), model.quads(), false));
                    }
                    else
                    {
                        fail(job, new CaptureFailed("pixels not converted", unwrap(error)));
                    }
                }, mc);
    }

    /** Сбой на любом этапе: текстуры — назад, будущее — исключением; в лог — первый случай. */
    static void fail(Job job, CaptureFailed error)
    {
        if (job.stage == Stage.DONE)
        {
            return;
        }

        if (! warnedFailure)
        {
            warnedFailure = true;
            LOGGER.warn("Preview capture {}: {}; further failures are not logged", job.label, error.getMessage(), error.getCause());
        }

        close(job);
        job.future.completeExceptionally(error);
    }

    private static void finish(Job job, Shot shot)
    {
        if (job.stage == Stage.DONE)
        {
            return;
        }

        close(job);
        job.future.complete(shot);
    }

    private static void close(Job job)
    {
        job.stage = Stage.DONE;
        ACTIVE.remove(job);
        READY.remove(job);
        job.releaseTargets();

        PreviewMesher.Job mesh = job.mesh;

        if (mesh != null)
        {
            mesh.cancel();
        }

        if (job.retained)
        {
            job.retained = false;
            PreviewModule.release();
        }
    }

    /**
     * Сборка кадра GUI: есть готовая модель — положить в состояние кадра невидимую «картинку в
     * картинке» 1×1; тогда рендер GUI этого кадра позовёт {@link CaptureRenderer#prepare}.
     */
    private static void requestFrame()
    {
        if (READY.isEmpty())
        {
            return;
        }

        GuiRenderState frame = ((com.aell.layerlist.mixin.schematics.IMixinGameRendererGui) Minecraft.getInstance().gameRenderer).layerlist$guiRenderState();
        boolean[] present = {false};
        frame.forEachPictureInPicture(state -> present[0] |= state == CaptureRenderer.Pass.INSTANCE);

        if (! present[0])
        {
            frame.submitPicturesInPictureState(CaptureRenderer.Pass.INSTANCE);
        }
    }

    private static void tick()
    {
        if (ACTIVE.isEmpty())
        {
            return;
        }

        long now = Util.getMillis();

        for (Job job : List.copyOf(ACTIVE))
        {
            long limit = switch (job.stage)
            {
                case MESHING -> MESH_TIMEOUT_MS;
                case READY -> FRAME_TIMEOUT_MS;
                case DRAWN -> READ_TIMEOUT_MS;
                case DONE -> Long.MAX_VALUE;
            };

            if (now - job.since > limit)
            {
                String what = switch (job.stage)
                {
                    case MESHING -> "the model was not built";
                    case READY -> "the GUI renderer never ran";
                    default -> "the GPU read-back never arrived";
                };

                fail(job, new CaptureFailed(what + " in " + limit + " ms", null));
            }
        }
    }

    // ===== Кадр и пиксели =====

    /**
     * Камера изометрии вкладки, вписанная по самой геометрии: все вершины (и кубы особых блоков с запасом
     * на высоту флага) проецируются поворотом вида, масштаб — чтобы крайние точки легли на поле
     * {@link #FIT_MARGIN}, сдвиг — чтобы середина рамки модели была в центре. Любой поток (модель
     * неизменяема).
     */
    static PreviewCamera.Key fit(PreviewModel model, int width, int height)
    {
        float yaw = PreviewCamera.View.ISOMETRIC.yaw();
        float pitch = PreviewCamera.View.ISOMETRIC.pitch();
        Matrix4f rotation = new PreviewCamera.Key(yaw, pitch, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F).rotation();
        Extent extent = new Extent(rotation, model.centerX, model.centerY, model.centerZ);

        if (model.flat != null)
        {
            extent.quads(model.flat);
        }

        for (PreviewModel.Section section : model.sections)
        {
            for (PreviewModel.Quads quads : section.layers)
            {
                if (quads != null)
                {
                    extent.quads(quads);
                }
            }

            int[] specials = section.specials;

            for (int i = 0; i + 3 < specials.length; i += 4)
            {
                // Флаг стоит на два блока вверх, настенный свисает на блок вниз
                extent.box(specials[i], specials[i + 1] - 1, specials[i + 2], specials[i] + 1, specials[i + 1] + 2, specials[i + 2] + 1);
            }
        }

        if (! extent.any())
        {
            // Пусто — габарит снимка, чтобы ключ всё равно был осмысленным
            VoxelSnapshot snapshot = model.snapshot;
            extent.box(0, 0, 0, snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ());
        }

        float margin = FIT_MARGIN * Math.min(width, height);
        float spanX = Math.max(1.0E-3F, extent.maxX - extent.minX);
        float spanY = Math.max(1.0E-3F, extent.maxY - extent.minY);
        float zoom = Math.min((width - 2.0F * margin) / spanX, (height - 2.0F * margin) / spanY);
        float panX = -zoom * (extent.minX + extent.maxX) * 0.5F;
        float panY = -zoom * (extent.minY + extent.maxY) * 0.5F;

        return new PreviewCamera.Key(yaw, pitch, zoom, panX, panY, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    /**
     * Два кадра {@code w·s}×{@code h·s} (RGBA, строки снизу вверх; один на чёрном фоне, другой на белом) →
     * картинка {@code w}×{@code h} ARGB без домножения на альфу. Смешение конвейера — «поверх»: на чёрном
     * выходит {@code c·a}, на белом {@code c·a + (1 − a)}, значит {@code a = 1 − (белый − чёрный)} и
     * {@code c = чёрный / a}. Так непрозрачное остаётся непрозрачным, что бы слой ни записал в альфу, а
     * вода и стекло получают свою настоящую альфу. Квадрат {@code s}×{@code s} усредняется до деления —
     * края блоков сглажены без тёмной каймы. Альфу, записанную в кадр, не читаем вовсе.
     */
    static BufferedImage matte(byte[] onBlack, byte[] onWhite, int w, int h, int s)
    {
        int textureW = w * s;
        int textureH = h * s;
        int n = s * s;
        int[] out = new int[w * h];

        for (int y = 0; y < h; ++y)
        {
            for (int x = 0; x < w; ++x)
            {
                int blackR = 0;
                int blackG = 0;
                int blackB = 0;
                int gap = 0;

                for (int dy = 0; dy < s; ++dy)
                {
                    int row = textureH - 1 - (y * s + dy);
                    int at = (row * textureW + x * s) * 4;

                    for (int dx = 0; dx < s; ++dx, at += 4)
                    {
                        int r = onBlack[at] & 0xFF;
                        int g = onBlack[at + 1] & 0xFF;
                        int b = onBlack[at + 2] & 0xFF;

                        blackR += r;
                        blackG += g;
                        blackB += b;
                        // Сколько фона просвечивает, по трём каналам сразу
                        gap += (onWhite[at] & 0xFF) - r + (onWhite[at + 1] & 0xFF) - g + (onWhite[at + 2] & 0xFF) - b;
                    }
                }

                // Покрытие: сумма a·255 по трём каналам и s² клеткам; 255·3·s² — непрозрачно, 0 — пусто
                int coverage = Math.max(0, Math.min(0xFF * n * 3, 0xFF * n * 3 - gap));

                if (coverage < n * 3)
                {
                    continue;
                }

                int a = Math.min(0xFF, (coverage + (n * 3) / 2) / (n * 3));
                // c = чёрный / a: средний чёрный (сумма / n) делим на покрытие (coverage / (255·3·n))
                int r = Math.min(0xFF, (int) ((blackR * 765L + coverage / 2) / coverage));
                int g = Math.min(0xFF, (int) ((blackG * 765L + coverage / 2) / coverage));
                int b = Math.min(0xFF, (int) ((blackB * 765L + coverage / 2) / coverage));

                out[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, out, 0, w);

        return image;
    }

    static byte[] encode(BufferedImage image)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);

        // Поток в памяти: без него ImageIO пишет промежуточный файл во временную папку
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes))
        {
            if (! ImageIO.write(image, "png", stream))
            {
                throw new IllegalStateException("no PNG writer");
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        return bytes.toByteArray();
    }

    private static int clamp(int side)
    {
        return Math.max(MIN_SIDE, Math.min(MAX_SIDE, side));
    }

    private static Throwable unwrap(@Nullable Throwable error)
    {
        Throwable cause = error;

        while (cause instanceof CompletionException && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause != null ? cause : new IllegalStateException("unknown");
    }

    private static String describe(Throwable error)
    {
        String text = error.getMessage();

        return text != null && ! text.isBlank() ? text : error.getClass().getSimpleName();
    }

    // ===== Внутреннее =====

    enum Stage
    {
        MESHING,
        READY,
        DRAWN,
        DONE
    }

    private record Framed(PreviewModel model, PreviewCamera.Key camera) {}

    /** Рамка проекций вершин на оси экрана (X вправо, Y вверх), в блоках. */
    private static final class Extent
    {
        private final float r00;
        private final float r10;
        private final float r20;
        private final float r01;
        private final float r11;
        private final float r21;
        private final float cx;
        private final float cy;
        private final float cz;

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        Extent(Matrix4f rotation, float cx, float cy, float cz)
        {
            // Две первые строки поворота (JOML хранит по столбцам: mXY — столбец X, строка Y)
            this.r00 = rotation.m00();
            this.r10 = rotation.m10();
            this.r20 = rotation.m20();
            this.r01 = rotation.m01();
            this.r11 = rotation.m11();
            this.r21 = rotation.m21();
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }

        void point(float x, float y, float z)
        {
            float px = x - this.cx;
            float py = y - this.cy;
            float pz = z - this.cz;
            float sx = this.r00 * px + this.r10 * py + this.r20 * pz;
            float sy = this.r01 * px + this.r11 * py + this.r21 * pz;

            this.minX = Math.min(this.minX, sx);
            this.maxX = Math.max(this.maxX, sx);
            this.minY = Math.min(this.minY, sy);
            this.maxY = Math.max(this.maxY, sy);
        }

        void quads(PreviewModel.Quads quads)
        {
            float[] pos = quads.pos;
            int end = quads.count * 12;

            for (int i = 0; i < end; i += 3)
            {
                this.point(pos[i], pos[i + 1], pos[i + 2]);
            }
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1)
        {
            for (int corner = 0; corner < 8; ++corner)
            {
                this.point((corner & 1) == 0 ? x0 : x1, (corner & 2) == 0 ? y0 : y1, (corner & 4) == 0 ? z0 : z1);
            }
        }

        boolean any()
        {
            return this.minX <= this.maxX;
        }
    }

    /** Одна заявка; поля меняет только клиентский поток. */
    static final class Job
    {
        final VoxelSnapshot snapshot;
        final int width;
        final int height;
        final int supersample;
        final String label;
        final CompletableFuture<Shot> future = new CompletableFuture<>();

        Stage stage = Stage.MESHING;
        long since;
        boolean retained;
        PreviewMesher.@Nullable Job mesh;
        @Nullable PreviewModel model;
        PreviewCamera.@Nullable Key camera;
        /** Текстуры и буфер кадра; отдаются после чтения или при сбое. */
        @Nullable AutoCloseable targets;

        Job(VoxelSnapshot snapshot, int width, int height, int supersample)
        {
            this.snapshot = snapshot;
            this.width = width;
            this.height = height;
            this.supersample = supersample;
            this.label = snapshot.sizeX() + "×" + snapshot.sizeY() + "×" + snapshot.sizeZ() + " @" + width + "×" + height;
        }

        void releaseTargets()
        {
            AutoCloseable held = this.targets;
            this.targets = null;

            if (held == null)
            {
                return;
            }

            try
            {
                held.close();
            }
            catch (Exception e)
            {
                LOGGER.debug("Preview capture: releasing GPU targets failed", e);
            }
        }
    }
}
