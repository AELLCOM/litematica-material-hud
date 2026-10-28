package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.export.IconBatch;
import com.aell.layerlist.schematics.export.PreviewPoster;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Одна выгрузка в файл (или в таблицу команды), не замораживающая игру:
 * <ol>
 *   <li>иконки предметов — заказ в {@code ItemIcons} (M25): игра рисует их в кадре GUI порциями по
 *       {@link #FRAME_BUDGET_MS} мс и отдаёт с видеокарты через кадр-два; окно зовёт {@link #tick()}
 *       из своей отрисовки, пока не придут все;</li>
 *   <li>сам файл (xlsx, PNG, CSV, JSON, список Litematica) или тело запроса к таблице команды —
 *       в {@code Util.ioPool()}: перегрузки {@code Exporters} с {@link IconBatch} {@code ItemIcons}
 *       не трогают; у «Картинки» рядом со страницами — постер-превью схемы ({@link PreviewPoster}, 0.0.27):
 *       его заказ идёт вместе с иконками, запись ждёт и то и другое;</li>
 *   <li>итог — обратно на клиентский поток, в {@code onDone}, даже если окно уже закрыли (файл
 *       записан — тост об этом нужен всё равно).</li>
 * </ol>
 * Закрыли окно, пока рисуются иконки, — выгрузка отменяется ({@link #cancel()}), файл не пишется.
 * Фоновые записи идут по одной в порядке запуска (общая очередь), даже из разных окон.
 */
@Environment(EnvType.CLIENT)
final class ExportJob
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    /** Сколько миллисекунд кадра отдаём иконкам: заметно быстрее, чем по одной, и без рывков. */
    static final long FRAME_BUDGET_MS = 6L;

    /** Хвост очереди фоновых записей (на всю игру). Только клиентский поток. */
    private static CompletableFuture<?> queue = CompletableFuture.completedFuture(null);

    enum Phase
    {
        ICONS,
        WORKING,
        FINISHED,
        CANCELLED
    }

    private final ExportFormat format;
    private final ExportRequest request;
    private final IconBatch icons;
    /** Постер «Картинки»; {@code null} — у формата его нет или схема без файла. Завершается на клиентском потоке. */
    private final @Nullable CompletableFuture<byte[]> poster;
    private final BiConsumer<ExportFormat, ExportResult> onDone;
    private Phase phase = Phase.ICONS;

    private ExportJob(ExportFormat format, ExportRequest request, IconBatch icons,
                      @Nullable CompletableFuture<byte[]> poster, BiConsumer<ExportFormat, ExportResult> onDone)
    {
        this.format = format;
        this.request = request;
        this.icons = icons;
        this.poster = poster;
        this.onDone = onDone;
    }

    /** Начать без постера (источник без файла схемы). */
    static ExportJob start(ExportFormat format, ExportRequest request, BiConsumer<ExportFormat, ExportResult> onDone)
    {
        return start(format, request, null, onDone);
    }

    /**
     * Начать: набор иконок (если формату они нужны), у «Картинки» со схемой {@code entry} — заказ постера;
     * если ждать нечего, сразу запись.
     */
    static ExportJob start(ExportFormat format, ExportRequest request, @Nullable SchematicEntry entry,
                           BiConsumer<ExportFormat, ExportResult> onDone)
    {
        IconBatch icons = format.needsIcons(request.options()) ? IconBatch.of(request) : IconBatch.none();
        // Будущее постера никогда не завершается исключением: сбой — заглушка на месте модели
        CompletableFuture<byte[]> poster = format == ExportFormat.PNG && entry != null
                ? PreviewPoster.request(entry, request.title()) : null;
        ExportJob job = new ExportJob(format, request, icons, poster, onDone);

        if (icons.finished() && job.posterReady())
        {
            job.launch();
        }

        return job;
    }

    ExportFormat format()
    {
        return this.format;
    }

    Phase phase()
    {
        return this.phase;
    }

    /** Ещё идёт (иконки или запись). */
    boolean running()
    {
        return this.phase == Phase.ICONS || this.phase == Phase.WORKING;
    }

    /** Кадр, клиентский поток: следующая порция иконок; иконки и постер готовы — запись уходит в фон. */
    void tick()
    {
        if (this.phase == Phase.ICONS && this.icons.step(FRAME_BUDGET_MS) && this.posterReady())
        {
            this.launch();
        }
    }

    private boolean posterReady()
    {
        return this.poster == null || this.poster.isDone();
    }

    /** Отменить, пока не началась запись; запись в фоне доводится до конца. */
    void cancel()
    {
        if (this.phase == Phase.ICONS)
        {
            this.phase = Phase.CANCELLED;
        }
    }

    /** Строка прогресса для окна. */
    String progress()
    {
        return switch (this.phase)
        {
            case ICONS -> this.icons.finished()
                    ? DialogText.t("progress.poster")
                    : DialogText.f("progress.icons", DialogText.num(this.icons.done()), DialogText.num(this.icons.total()));
            case WORKING -> this.format == ExportFormat.TEAM ? DialogText.t("team.sending") : DialogText.t("progress.writing");
            default -> "";
        };
    }

    private void launch()
    {
        this.phase = Phase.WORKING;
        Minecraft mc = Minecraft.getInstance();

        // Метод Litematica за корнем папки схем создаёт папку — пусть это будет на клиентском потоке
        if (this.format == ExportFormat.LITEMATICA)
        {
            SchematicLibrary.get().root();
        }

        CompletableFuture<ExportResult> work;

        try
        {
            // Фоновые записи — строго по одной: окно можно закрыть и начать новую, пока пишется прошлая,
            // а две записи за одну минуту выбрали бы одно и то же свободное имя файла
            CompletableFuture<Void> previous = queue.handle((ignored, error) -> null);

            if (this.format == ExportFormat.TEAM)
            {
                // В очереди — только сборка тела запроса; ответа скрипта (до двух минут) следующие не ждут
                CompletableFuture<CompletableFuture<ExportResult>> sent = previous.thenApplyAsync(
                        ignored -> Exporters.sendToTeamSheet(this.request, this.icons), Util.ioPool());
                queue = sent;
                work = sent.thenCompose(Function.identity());
            }
            else
            {
                work = previous.thenApplyAsync(ignored -> this.write(), Util.ioPool());
                queue = work;
            }
        }
        catch (RuntimeException e)
        {
            // Пул остановлен (игра закрывается) — ответить сразу, на этом же потоке
            work = CompletableFuture.failedFuture(e);
        }

        work.whenCompleteAsync((result, error) ->
        {
            this.phase = Phase.FINISHED;

            if (error != null || result == null)
            {
                LOGGER.warn("Export {} failed outside the exporter", this.format, error);
                result = ExportResult.failure(DialogText.f("failed", error == null ? "?" : DialogText.reason(error)));
            }

            this.onDone.accept(this.format, result);
        }, mc);
    }

    /** Фон: файл формата. Все вызовы — без {@code ItemIcons} (иконки уже в наборе). */
    private ExportResult write()
    {
        return switch (this.format)
        {
            case XLSX -> Exporters.xlsx(this.request, this.icons);
            case GOOGLE -> Exporters.google(this.request, this.icons);
            // Постер уже готов (запись начинается только после него) — getNow не ждёт
            case PNG -> Exporters.png(this.request, this.icons, this.poster == null ? null : this.poster.getNow(null));
            case CSV -> Exporters.csv(this.request);
            case JSON -> Exporters.json(this.request);
            case LITEMATICA -> Exporters.litematica(this.request);
            // Буфер обмена и таблица команды сюда не попадают — у них свой путь
            default -> ExportResult.failure(DialogText.f("failed", this.format.name()));
        };
    }
}
