package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.preview.PreviewCapture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Постер-превью схемы для выгрузки «Картинка» (0.0.27): PNG {@value #WIDTH}×{@value #HEIGHT}, 3D-модель
 * схемы в изометрии вкладки ({@link PreviewCapture}) на фирменной фиолетовой плашке, название пиксельным
 * шрифтом, подзаголовок «размер · блоков · регионов · [упрощённый вид] · автор», в подвале — вид, дата и
 * знак «Material HUD». Рисунок — {@link PosterPainter}.
 *
 * <p><b>Потоки.</b> {@link #request} можно звать из любого потока; будущее завершается <b>на клиентском
 * потоке</b>, через секунды (сборка модели в фоне, один кадр GUI на рисунок, кадр-два на ответ
 * видеокарты, Java2D в {@code Util.backgroundExecutor()}). На клиентском потоке будущее не ждать
 * {@code join()} — кадры встанут, а без кадров GUI снимок не сделать; там — {@code isDone()}/
 * {@code thenAccept}. В фоновом потоке выгрузки {@code join()} можно, пока игра рисует кадры (окно
 * выгрузки открыто — кадры идут).
 *
 * <p><b>Сбои.</b> Будущее исключением не завершается никогда: не прочиталась схема, сборка упала, кадры
 * не шли дольше таймаута, видеокарта не ответила — постер всё равно приходит, со строкой «Превью
 * недоступно» и контуром куба на месте модели (у схемы из одного воздуха — «В схеме нет блоков»); в лог —
 * один раз за игру. Даже если не нарисовался текст — приходит пустая плашка; пустой массив байт — только
 * если в JVM нет кодировщика PNG вовсе (тогда файл не писать). Выгрузка из-за превью не падает.
 */
@Environment(EnvType.CLIENT)
public final class PreviewPoster
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    public static final int WIDTH = PosterPainter.WIDTH;
    public static final int HEIGHT = PosterPainter.HEIGHT;
    /** Имя файла в папке картинки рядом с {@code 1.png}, {@code 2.png}… (кириллица — как и имена папок схем). */
    public static final String FILE_NAME = "превью.png";

    /** Дольше этого постер не ждёт снимок (огромная схема, свёрнутая игра) — рисует заглушку. */
    static final long DEADLINE_MS = 240_000L;

    private static boolean warned;

    /**
     * Готовый постер и что на нём вышло.
     *
     * @param png         PNG {@value #WIDTH}×{@value #HEIGHT}
     * @param modelShown  на постере модель схемы (иначе — заглушка)
     * @param simplified  модель в упрощённом виде (кубы); подзаголовок об этом говорит
     * @param cubeSize    сторона куба упрощённого вида в блоках; 1 — без упрощения
     * @param failure     почему модели нет (для лога и проверок); {@code null} — модель есть или схема пуста
     */
    public record Result(byte[] png, boolean modelShown, boolean simplified, int cubeSize, @Nullable String failure) {}

    private PreviewPoster() {}

    /** Постер схемы, уже загруженной Litematica: по её файлу каталога. См. {@link #request(SchematicEntry, String)}. */
    public static CompletableFuture<byte[]> request(LoadedSchematic schematic, String title)
    {
        return request(Objects.requireNonNull(schematic, "schematic").entry(), title);
    }

    /**
     * PNG постера для файла схемы {@code entry}; {@code title} — название крупно (пусто — имя файла без
     * расширения, как в списке «Библиотеки»).
     * Будущее завершается на клиентском потоке и исключением не завершается (см. описание класса).
     */
    public static CompletableFuture<byte[]> request(SchematicEntry entry, String title)
    {
        return requestResult(entry, title).thenApply(Result::png);
    }

    /** То же, что {@link #request(SchematicEntry, String)}, с подробностями: вышла ли модель, упрощена ли. */
    public static CompletableFuture<Result> requestResult(SchematicEntry entry, String title)
    {
        Objects.requireNonNull(entry, "entry");
        String heading = title == null ? "" : title;
        Minecraft mc = Minecraft.getInstance();

        if (! mc.isSameThread())
        {
            return CompletableFuture.supplyAsync(() -> requestResult(entry, heading), mc).thenCompose(Function.identity());
        }

        CompletableFuture<Result> result = new CompletableFuture<>();
        CompletableFuture<@Nullable SchematicInfo> info = info(entry);
        CompletableFuture<Outcome> shot;

        try
        {
            shot = PreviewCapture.request(entry, PosterPainter.MODEL_W, PosterPainter.MODEL_H)
                    .orTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .handle((captured, error) -> new Outcome(captured, error == null ? null : unwrap(error)));
        }
        catch (RuntimeException | LinkageError e)
        {
            shot = CompletableFuture.completedFuture(new Outcome(null, e));
        }

        info.thenCombine(shot, (meta, outcome) -> new Inputs(entry, heading, meta, outcome))
                .thenApplyAsync(PreviewPoster::compose, Util.backgroundExecutor())
                .whenCompleteAsync((done, error) ->
                {
                    if (done != null)
                    {
                        result.complete(done);
                        return;
                    }

                    // Сюда приходит только сбой самого рисунка (шрифты, память) — пустая плашка
                    Throwable cause = unwrap(error);
                    warn("drawing the poster failed", cause);
                    byte[] bare;

                    try
                    {
                        bare = encode(PosterPainter.bare());
                    }
                    catch (RuntimeException | LinkageError e)
                    {
                        // PNG не кодируется вовсе — пустой массив: файла не будет, выгрузка остальное допишет
                        bare = new byte[0];
                    }

                    result.complete(new Result(bare, false, false, 1, describe(cause)));
                }, mc);

        return result;
    }

    // ===== Внутреннее =====

    private record Outcome(PreviewCapture.@Nullable Shot shot, @Nullable Throwable error) {}

    private record Inputs(SchematicEntry entry, String title, @Nullable SchematicInfo info, Outcome outcome) {}

    /** Сведения о файле; не прочитались — без них (размер и прочее возьмём у снимка или не покажем). */
    private static CompletableFuture<@Nullable SchematicInfo> info(SchematicEntry entry)
    {
        try
        {
            return SchematicLibrary.get().info(entry).handle((info, error) -> info != null && info.ok() ? info : null);
        }
        catch (RuntimeException e)
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Фон: тексты по ключам, рисунок, PNG. */
    private static Result compose(Inputs in)
    {
        PreviewCapture.Shot shot = in.outcome().shot();
        Throwable error = in.outcome().error();
        String placeholder = null;
        String failure = null;

        if (shot == null)
        {
            failure = error == null ? "no picture" : describe(error);
            warn("no model picture for " + in.entry().fileName(), error);
            placeholder = ExportText.t("poster.unavailable");
        }
        else if (shot.empty())
        {
            placeholder = ExportText.t("poster.no_blocks");
        }

        boolean shown = placeholder == null;
        boolean simplified = shot != null && shot.simplified();
        int cube = shot != null ? shot.cubeSize() : 1;
        PosterPainter.Texts texts = new PosterPainter.Texts(title(in), subtitle(in.info(), shown && simplified, cube),
                ExportText.t("poster.footer", ExportText.date(LocalDateTime.now())), ExportText.t("png.brand"), placeholder);
        BufferedImage image = PosterPainter.paint(texts, shown ? shot.image() : null);

        return new Result(encode(image), shown, simplified, cube, failure);
    }

    private static String title(Inputs in)
    {
        // Пусто — имя файла, как в списке «Библиотеки» (имя из метаданных — часто «Unnamed» — не берём, 0.0.27)
        String title = ExportText.plain(in.title()).strip();

        return title.isEmpty() ? in.entry().name() : title;
    }

    /** «48 × 31 × 52 · блоков: 12 345 · регионов: 3 · упрощённый вид… · автор: …»: неизвестное пропускается. */
    private static List<PosterPainter.Segment> subtitle(@Nullable SchematicInfo info, boolean simplified, int cube)
    {
        List<PosterPainter.Segment> parts = new ArrayList<>();

        if (info != null && info.sizeX() > 0 && info.sizeY() > 0 && info.sizeZ() > 0)
        {
            parts.add(plain(ExportText.t("poster.size", ExportText.count(info.sizeX()), ExportText.count(info.sizeY()),
                    ExportText.count(info.sizeZ()))));
        }

        if (info != null && info.blocks() >= 0)
        {
            parts.add(plain(ExportText.t(info.blocks() == 1 ? "poster.blocks.one" : "poster.blocks",
                    ExportText.count(info.blocks()))));
        }

        if (info != null && info.regions() > 0)
        {
            parts.add(plain(ExportText.t(info.regions() == 1 ? "poster.regions.one" : "poster.regions",
                    ExportText.count(info.regions()))));
        }

        if (simplified)
        {
            String side = Integer.toString(cube);
            parts.add(new PosterPainter.Segment(ExportText.t("poster.simplified", side, side, side), true));
        }

        if (info != null && ! info.author().isBlank())
        {
            parts.add(plain(ExportText.t("poster.author", ExportText.plain(info.author()).strip())));
        }

        return parts;
    }

    private static PosterPainter.Segment plain(String text)
    {
        return new PosterPainter.Segment(text, false);
    }

    private static synchronized void warn(String what, @Nullable Throwable error)
    {
        if (warned)
        {
            return;
        }

        warned = true;
        LOGGER.warn("Preview poster: {}, drawing a placeholder; further failures are not logged", what, error);
    }

    private static byte[] encode(BufferedImage image)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 18);

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
}
