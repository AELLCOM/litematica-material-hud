package com.aell.layerlist.schematics.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Google Таблицы «по кнопке» без входа в Google из игры.
 *
 * <ol>
 *   <li>Файл для импорта: та же таблица сбора, записанная в профиле Google (картинки — в той
 *       разметке, в какой Google выгружает их сам), и адрес {@code https://sheets.new}: игрок
 *       открывает новую таблицу и делает «Файл → Импорт → Загрузка».</li>
 *   <li>Таблица команды: POST JSON на веб-приложение Apps Script
 *       ({@code assets/layerlist/export/material-hud-sheets.gs}), один раз установленное в
 *       таблицу команды. Скрипт строит лист той же разметки и отвечает ссылкой на него.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
final class GoogleExport
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    static final URI SHEETS_NEW = URI.create("https://sheets.new");
    static final String SCRIPT_RESOURCE = "/assets/layerlist/export/material-hud-sheets.gs";
    static final int PROTOCOL = 1;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    // Скрипт строит лист с картинками и диаграммой — на сотнях строк это десятки секунд
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(120);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // Один клиент на игру: у каждого свой поток-селектор, плодить их на каждую отправку незачем
    @Nullable private static HttpClient client;

    private GoogleExport() {}

    static Path write(ExportData data, @Nullable RawMaterials.Result raw, @Nullable ExportIcons icons, Path dir)
            throws IOException
    {
        XlsxWriter book = CollectionSheet.build(data, raw, icons, XlsxWriter.Profile.GOOGLE,
                SheetPalette.forTables());
        String base = ExportFiles.freeBase(dir, data.fileName(), ".xlsx");
        Path target = dir.resolve(base + ".xlsx");
        ExportFiles.write(target, book::write);

        return target;
    }

    /** Адрес веб-приложения из настроек: только https; {@code null} — пусто или не адрес. */
    @Nullable
    static URI endpoint(String value)
    {
        String text = value.strip();

        if (text.isEmpty())
        {
            return null;
        }

        try
        {
            URI uri = new URI(text);

            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
        }
        catch (URISyntaxException e)
        {
            return null;
        }
    }

    /**
     * Тело запроса к скрипту. Все подписи листа приходят от мода на языке игрока, скрипт их только
     * расставляет. Булевых полей нет: включённые части перечислены в {@code parts}.
     */
    static JsonObject payload(ExportData data, @Nullable RawMaterials.Result raw, @Nullable ExportIcons icons,
                              String token)
    {
        ExportRequest request = data.request();
        JsonObject root = new JsonObject();
        root.addProperty("protocol", PROTOCOL);
        root.addProperty("token", token);
        root.addProperty("name", data.title());
        root.addProperty("title", ExportText.t("sheet.title", data.title()));
        root.addProperty("meta", ExportText.meta(data));

        JsonObject labels = new JsonObject();

        for (String key : List.of(
                "col.index", "col.item", "col.count", "col.breakdown", "col.who", "col.status", "col.stack",
                "stats.title", "stats.nick", "stats.positions", "stats.blocks", "stats.shulkers", "stats.done",
                "stats.ready", "stats.total", "stats.all", "stats.free", "stats.legend", "stats.legendHint",
                "chart.title", "raw.title", "raw.resource", "raw.need", "raw.shulkers", "raw.why", "raw.logsTotal",
                "dv.who.error", "dv.status.error"))
        {
            labels.addProperty(key, ExportText.t(key));
        }

        root.add("labels", labels);
        // Цвета листа (1.0.1): скрипт без поддержки поля его не читает и красит по-своему
        root.add("palette", SheetPalette.forTables().forScript());

        JsonArray statuses = new JsonArray();
        statuses.add(ExportText.statusTaking());
        statuses.add(ExportText.statusDone());
        root.add("statuses", statuses);

        JsonArray nicks = new JsonArray();
        request.roster().forEach(nicks::add);
        root.add("nicks", nicks);

        JsonArray parts = new JsonArray();

        if (icons != null)
        {
            parts.add("icons");
        }

        if (raw != null)
        {
            parts.add("raw");
        }

        if (request.options().chart())
        {
            parts.add("chart");
        }

        root.add("parts", parts);

        JsonArray items = new JsonArray();

        for (ExportData.Line line : data.lines())
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", line.id());
            item.addProperty("name", line.name());
            item.addProperty("count", line.quantity());
            item.addProperty("stack", line.maxStack());
            item.addProperty("breakdown", line.breakdown());
            byte[] png = icons == null ? null : icons.png(line.row());

            if (png != null)
            {
                item.addProperty("icon", Base64.getEncoder().encodeToString(png));
            }

            items.add(item);
        }

        root.add("items", items);

        if (raw != null)
        {
            JsonArray resources = new JsonArray();

            for (RawMaterials.Resource resource : raw.resources())
            {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", resource.name());
                entry.addProperty("count", resource.count());
                entry.addProperty("stack", resource.maxStack());
                entry.addProperty("sources", String.join(", ", resource.sources()));
                resources.add(entry);
            }

            root.add("raw", resources);
            root.addProperty("logsTotal", raw.logs());
        }

        return root;
    }

    /**
     * Отправить тело скрипту. Ответ Apps Script приходит редиректом 302 на одноразовый адрес
     * {@code script.googleusercontent.com}; JDK по 302 после POST идёт туда GET-ом — так и надо.
     */
    static CompletableFuture<ExportResult> send(URI endpoint, JsonObject payload)
    {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REPLY_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return client().sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) ->
                {
                    if (error != null)
                    {
                        LOGGER.warn("Export: team sheet request failed", error);
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        return ExportResult.failure(ExportText.t("team.network", describe(cause)));
                    }

                    return reply(response.statusCode(), response.body());
                });
    }

    /** Разбор ответа скрипта: {@code {"ok":"yes","url":…}} или {@code {"ok":"no","error":…}}. */
    static ExportResult reply(int status, @Nullable String body)
    {
        if (status != 200)
        {
            return ExportResult.failure(ExportText.t("team.http", status));
        }

        JsonElement root;

        try
        {
            root = JsonParser.parseString(body == null ? "" : body);
        }
        catch (RuntimeException e)
        {
            root = null;
        }

        // Страница входа Google вместо JSON — веб-приложение развёрнуто не «для всех»
        if (! (root instanceof JsonObject object) || ! (object.get("ok") instanceof JsonPrimitive ok))
        {
            return ExportResult.failure(ExportText.t("team.notJson"));
        }

        if (! "yes".equals(ok.getAsString()))
        {
            String error = object.get("error") instanceof JsonPrimitive text ? text.getAsString() : "";

            return ExportResult.failure("token".equals(error)
                    ? ExportText.t("team.badToken")
                    : ExportText.t("team.failed", error));
        }

        if (object.get("url") instanceof JsonPrimitive url)
        {
            URI link = endpoint(url.getAsString());

            if (link != null)
            {
                return new ExportResult(List.of(), null, link, null);
            }
        }

        return ExportResult.failure(ExportText.t("team.notJson"));
    }

    private static synchronized HttpClient client()
    {
        if (client == null)
        {
            client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
        }

        return client;
    }

    /** Текст скрипта-приёмника для кнопки «Скопировать скрипт». */
    static String script() throws IOException
    {
        try (InputStream in = GoogleExport.class.getResourceAsStream(SCRIPT_RESOURCE))
        {
            if (in == null)
            {
                throw new IOException("missing " + SCRIPT_RESOURCE);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String describe(Throwable error)
    {
        String message = error.getMessage();
        String type = error.getClass().getSimpleName().replace("Exception", "").toLowerCase(Locale.ROOT);

        return message == null || message.isBlank() ? type : message;
    }
}
