package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.mojang.blaze3d.Blaze3D;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Все форматы выгрузки (M26) за одной дверью. Файлы — в папку выгрузок
 * ({@link SchematicsConfig#exportDir()}) с именем схемы ({@link ExportRequest#fileName()}, очищенным
 * под Windows): {@code <схема>.<ext>}, занято — {@code <схема> (2).<ext>}; запись через {@code .tmp}
 * и атомарное переименование; список для Litematica — в {@code <схемы>/material-lists/}.
 *
 * <p>Методы синхронные и ничего не открывают сами: результат ({@link ExportResult}) говорит окну,
 * что положить в буфер и что открыть ({@link #browse}, {@link #reveal}). Звать с клиентского
 * потока — иконки предметов и переводы берутся из игры; на сотнях строк это доли секунды.
 * Иконки рисует игра в кадре GUI и отдаёт через кадр-два, поэтому перегрузки без набора берут только
 * уже готовые иконки ({@code ItemIcons.png}: из кеша, иначе плашка цвета). Ошибка любого рода
 * превращается в {@link ExportResult#failure} с текстом для игрока.
 *
 * <p>Перегрузки с {@link IconBatch} (xlsx, Google, картинка, таблица команды) берут иконки из
 * заранее заказанного набора и {@code ItemIcons} не трогают: их можно звать с фонового потока —
 * переводы, реестры и рецепты читаются отовсюду. Так окно выгрузки не замораживает игру на записи
 * большой таблицы с картинками. CSV, JSON и список Litematica иконок не берут и тоже годятся для
 * фона (корень папки схем для Litematica окно читает заранее на клиентском потоке).
 */
@Environment(EnvType.CLIENT)
public final class Exporters
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    /**
     * Размер PNG-иконки предмета: слот 16 при масштабе GUI 4 — текстура 16×16 ложится ровно по 4 px.
     * В таблице иконка показывается 20×20, на карточке 32×32 — запас на масштаб и экраны высокой
     * плотности.
     */
    static final int ICON_SIZE = 64;

    private Exporters() {}

    @FunctionalInterface
    private interface Job
    {
        ExportResult run(ExportData data) throws IOException;
    }

    /** Откуда иконки строк: рисовать сейчас ({@link #icons}) или из готового {@link IconBatch}. */
    @FunctionalInterface
    private interface IconSource
    {
        @Nullable
        ExportIcons of(ExportData data);
    }

    /** Excel — таблица сбора. */
    public static ExportResult xlsx(ExportRequest r)
    {
        return writeXlsx(r, Exporters::icons);
    }

    /** Excel с иконками из готового набора — можно звать с фонового потока. */
    public static ExportResult xlsx(ExportRequest r, IconBatch icons)
    {
        return writeXlsx(r, prepared(icons));
    }

    private static ExportResult writeXlsx(ExportRequest r, IconSource icons)
    {
        return run(r, data ->
        {
            XlsxWriter book = CollectionSheet.build(data, raw(data), icons.of(data), XlsxWriter.Profile.EXCEL,
                    SheetPalette.forTables());
            Path dir = SchematicsConfig.exportDir();
            Path target = dir.resolve(ExportFiles.freeBase(dir, data.fileName(), ".xlsx") + ".xlsx");
            ExportFiles.write(target, book::write);

            return new ExportResult(List.of(target), null, null, null);
        });
    }

    /**
     * Тот же xlsx в профиле Google + адрес {@code https://sheets.new} в {@link ExportResult#open()}.
     * Окно показывает подсказку {@code layerlist.schematics.export.google.hint} и предлагает
     * открыть таблицу и папку с файлом.
     */
    public static ExportResult google(ExportRequest r)
    {
        return writeGoogle(r, Exporters::icons);
    }

    /** Файл для Google с иконками из готового набора — можно звать с фонового потока. */
    public static ExportResult google(ExportRequest r, IconBatch icons)
    {
        return writeGoogle(r, prepared(icons));
    }

    private static ExportResult writeGoogle(ExportRequest r, IconSource icons)
    {
        return run(r, data ->
        {
            Path file = GoogleExport.write(data, raw(data), icons.of(data), SchematicsConfig.exportDir());

            return new ExportResult(List.of(file), null, GoogleExport.SHEETS_NEW, null);
        });
    }

    public static ExportResult csv(ExportRequest r)
    {
        return run(r, data -> new ExportResult(List.of(CsvExport.write(data, SchematicsConfig.exportDir())),
                null, null, null));
    }

    /** Значения без формул — в буфер. */
    public static ExportResult tsv(ExportRequest r)
    {
        return run(r, data -> new ExportResult(List.of(), TsvExport.text(data), null, null));
    }

    /** Текст для Discord частями по 2000 символов; {@code part} — номер части с нуля. */
    public static ExportResult discord(ExportRequest r, int part)
    {
        return run(r, data ->
        {
            List<String> parts = DiscordExport.parts(data);

            return part >= 0 && part < parts.size()
                    ? new ExportResult(List.of(), parts.get(part), null, null)
                    : ExportResult.failure(ExportText.t("error.part", part + 1, parts.size()));
        });
    }

    /** Сколько частей у текста для Discord (для кнопки «Следующая часть»); 0 — выгружать нечего. */
    public static int discordParts(ExportRequest r)
    {
        try
        {
            ExportData data = ExportData.of(r, LocalDateTime.now());

            return data.lines().isEmpty() ? 0 : DiscordExport.parts(data).size();
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Export: could not split the Discord text", e);
            return 0;
        }
    }

    /** Картинка-карточка: папка {@code <схема>/} со страницами {@code 1.png}, {@code 2.png}… (по 60 строк). */
    public static ExportResult png(ExportRequest r)
    {
        return run(r, data -> new ExportResult(PngCard.write(data, SchematicsConfig.exportDir(),
                new ExportIcons(ICON_SIZE)), null, null, null));
    }

    /**
     * Картинка с иконками из готового набора (флажок «Иконки предметов» на карточку не действует —
     * набор нужен всегда) — можно звать с фонового потока.
     */
    public static ExportResult png(ExportRequest r, IconBatch icons)
    {
        return png(r, icons, null);
    }

    /**
     * Картинка с иконками из готового набора и постером-превью постройки ({@link PreviewPoster}, 0.0.27):
     * {@code poster} ложится в ту же папку файлом {@link PreviewPoster#FILE_NAME} после страниц. Пустой
     * или {@code null} — постера нет (список HUD, нет кодировщика PNG). Постер не записался — страницы
     * всё равно отдаются: выгрузка из-за превью не падает.
     */
    public static ExportResult png(ExportRequest r, IconBatch icons, byte @Nullable [] poster)
    {
        Objects.requireNonNull(icons, "icons");

        return run(r, data ->
        {
            List<Path> files = new ArrayList<>(PngCard.write(data, SchematicsConfig.exportDir(), icons.icons()));

            if (poster != null && poster.length > 0 && ! files.isEmpty())
            {
                Path target = files.getFirst().resolveSibling(PreviewPoster.FILE_NAME);

                try
                {
                    ExportFiles.write(target, out -> out.write(poster));
                    files.add(target);
                }
                catch (IOException e)
                {
                    LOGGER.warn("Export: could not write the preview poster {}", target, e);
                }
            }

            return new ExportResult(files, null, null, null);
        });
    }

    public static ExportResult json(ExportRequest r)
    {
        return run(r, data -> new ExportResult(List.of(JsonExport.write(data, raw(data), SchematicsConfig.exportDir())),
                null, null, null));
    }

    /** Список для браузера Litematica ({@code .txt} и {@code .json} в {@code material-lists}). */
    public static ExportResult litematica(ExportRequest r)
    {
        return run(r, data -> new ExportResult(LitematicaListExport.write(data, SchematicLibrary.get().root()),
                null, null, null));
    }

    /**
     * «Отправить в таблицу команды» — POST на веб-приложение Apps Script из настроек
     * ({@code schematicsSheetsWebAppUrl} + {@code schematicsSheetsToken}). Тело собирается сразу
     * (иконки и переводы — на вызывающем потоке), сеть — в фоне; будущее завершается на потоке
     * HTTP-клиента, ссылка на новый лист — в {@link ExportResult#open()}.
     */
    public static CompletableFuture<ExportResult> sendToTeamSheet(ExportRequest r)
    {
        return sendToTeamSheet(r, Exporters::icons);
    }

    /**
     * «Отправить в таблицу команды» с иконками из готового набора: тело собирается без
     * {@code ItemIcons}, поэтому звать можно и с фонового потока.
     */
    public static CompletableFuture<ExportResult> sendToTeamSheet(ExportRequest r, IconBatch icons)
    {
        return sendToTeamSheet(r, prepared(icons));
    }

    private static CompletableFuture<ExportResult> sendToTeamSheet(ExportRequest r, IconSource icons)
    {
        URI endpoint = GoogleExport.endpoint(SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue());

        if (endpoint == null)
        {
            String key = SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue().isBlank() ? "team.noUrl" : "team.badUrl";
            return CompletableFuture.completedFuture(ExportResult.failure(ExportText.t(key)));
        }

        try
        {
            ExportData data = ExportData.of(r, LocalDateTime.now());

            if (data.lines().isEmpty())
            {
                return CompletableFuture.completedFuture(ExportResult.failure(ExportText.t("error.empty")));
            }

            String token = SchematicsConfig.SHEETS_TOKEN.getStringValue().strip();

            return GoogleExport.send(endpoint, GoogleExport.payload(data, raw(data), icons.of(data), token));
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Export: could not prepare the team sheet request", e);
            return CompletableFuture.completedFuture(ExportResult.failure(ExportText.t("error.failed", e)));
        }
    }

    /** Текст скрипта-приёмника для кнопки «Скопировать скрипт»; {@code null} — ресурса нет в jar. */
    @Nullable
    public static String teamSheetScript()
    {
        try
        {
            return GoogleExport.script();
        }
        catch (IOException e)
        {
            LOGGER.warn("Export: team sheet script is missing from the jar", e);
            return null;
        }
    }

    /**
     * Прочитать рецепты игры для блока «Крупное сырьё» в фоне — звать при открытии окна выгрузки:
     * первое чтение ~1700 файлов из jar-а занимает доли секунды, и ждать их на кнопке незачем.
     */
    public static void preload()
    {
        Util.backgroundExecutor().execute(RawMaterials::preload);
    }

    /** Открыть адрес в браузере (sheets.new, ссылка на лист команды). */
    public static void browse(URI uri)
    {
        net.minecraft.util.Util.getPlatform().openUri(uri);
    }

    /**
     * Показать файл в проводнике: в Windows — с выделенным файлом, в macOS — в Finder, иначе —
     * открыть его папку.
     */
    public static void reveal(Path file)
    {
        Path target = file.toAbsolutePath().normalize();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        try
        {
            if (os.contains("win"))
            {
                // «/select,» и путь — отдельными аргументами, иначе путь с пробелами explorer не поймёт
                new ProcessBuilder("explorer.exe", "/select,", target.toString()).start();
                return;
            }

            if (os.contains("mac"))
            {
                new ProcessBuilder("open", "-R", target.toString()).start();
                return;
            }
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Export: could not reveal {} in the file manager, opening its folder", target, e);
        }

        Path folder = target.getParent();
        net.minecraft.util.Util.getPlatform().openPath(folder != null ? folder : target);
    }

    /**
     * Открыть папку в проводнике (в Windows — {@code explorer}, иначе — как откроет система); папки
     * нет — создать: пустая папка выгрузок честнее ошибки «путь не найден».
     */
    public static void openFolder(Path dir)
    {
        Path target = dir.toAbsolutePath().normalize();

        try
        {
            Files.createDirectories(target);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Export: could not create the folder {}", target, e);
        }

        net.minecraft.util.Util.getPlatform().openPath(target);
    }

    private static ExportResult run(ExportRequest request, Job job)
    {
        try
        {
            ExportData data = ExportData.of(request, LocalDateTime.now());

            if (data.lines().isEmpty())
            {
                return ExportResult.failure(ExportText.t("error.empty"));
            }

            return job.run(data);
        }
        catch (IOException e)
        {
            LOGGER.warn("Export: could not write the file", e);
            return ExportResult.failure(ExportText.t("error.io", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        catch (RuntimeException | LinkageError e)
        {
            // LinkageError — сборка Java без java.desktop (картинка): игра не должна из-за этого падать
            LOGGER.error("Export failed", e);
            return ExportResult.failure(ExportText.t("error.failed", e.toString()));
        }
    }

    @Nullable
    private static RawMaterials.Result raw(ExportData data)
    {
        return data.request().options().rawMaterials() ? RawMaterials.of(data.lines()) : null;
    }

    @Nullable
    private static ExportIcons icons(ExportData data)
    {
        return data.request().options().icons() ? new ExportIcons(ICON_SIZE) : null;
    }

    /** Флажок «Иконки предметов» действует и на готовый набор: выключен — таблица без картинок. */
    private static IconSource prepared(IconBatch batch)
    {
        Objects.requireNonNull(batch, "icons");

        return data -> data.request().options().icons() ? batch.icons() : null;
    }
}
