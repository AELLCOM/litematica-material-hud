package com.aell.layerlist.schematics.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

/**
 * Полный JSON для своих скриптов и ботов: метаданные, ростер, статусы, итоги, все колонки строк
 * и (по флажку) крупное сырьё. Булевых полей нет и здесь: вид строки — словом
 * ({@code "block"}/{@code "entity"}), фильтр — {@code "all"}/{@code "missing"}.
 */
@Environment(EnvType.CLIENT)
final class JsonExport
{
    static final String FORMAT = "material-hud-export";
    static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonExport() {}

    static Path write(ExportData data, @Nullable RawMaterials.Result raw, Path dir) throws IOException
    {
        String base = ExportFiles.freeBase(dir, data.fileName(), ".json");
        Path target = dir.resolve(base + ".json");
        ExportFiles.writeString(target, text(data, raw) + "\n", false);

        return target;
    }

    static String text(ExportData data, @Nullable RawMaterials.Result raw)
    {
        return GSON.toJson(tree(data, raw));
    }

    static JsonObject tree(ExportData data, @Nullable RawMaterials.Result raw)
    {
        ExportRequest request = data.request();
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("mod", modVersion());
        root.addProperty("title", data.title());
        root.addProperty("source", request.source());
        root.addProperty("created", data.created().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        root.addProperty("filter", request.options().onlyMissing() ? "missing" : "all");

        JsonArray roster = new JsonArray();
        request.roster().forEach(roster::add);
        root.add("roster", roster);

        JsonArray statuses = new JsonArray();
        statuses.add(ExportText.statusTaking());
        statuses.add(ExportText.statusDone());
        root.add("statuses", statuses);

        JsonObject totals = new JsonObject();
        totals.addProperty("positions", data.lines().size());
        totals.addProperty("blocks", data.blocks());
        totals.addProperty("shulkers", round(data.shulkers()));
        root.add("totals", totals);

        JsonArray items = new JsonArray();

        for (ExportData.Line line : data.lines())
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", line.id());
            item.addProperty("key", line.row().key());
            item.addProperty("name", line.name());
            item.addProperty("kind", line.row().entity() ? "entity" : "block");
            item.addProperty("quantity", line.quantity());
            item.addProperty("total", line.row().total());
            item.addProperty("missing", line.row().missing());
            item.addProperty("inInventory", line.row().inInventory());
            item.addProperty("inChests", line.row().inChests());
            item.addProperty("maxStack", line.maxStack());
            item.addProperty("shulkers", round(line.shulkers()));
            item.addProperty("breakdown", line.breakdown());
            items.add(item);
        }

        root.add("items", items);

        if (raw != null && request.options().rawMaterials())
        {
            JsonArray resources = new JsonArray();

            for (RawMaterials.Resource resource : raw.resources())
            {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", resource.id().contains(":") || resource.id().startsWith("any_")
                        ? resource.id()
                        : "minecraft:" + resource.id());
                entry.addProperty("name", resource.name());
                entry.addProperty("count", resource.count());
                entry.addProperty("maxStack", resource.maxStack());
                JsonArray sources = new JsonArray();
                resource.sources().forEach(sources::add);
                entry.add("sources", sources);
                resources.add(entry);
            }

            root.add("raw", resources);
            root.addProperty("logsTotal", raw.logs());
        }

        return root;
    }

    static String modVersion()
    {
        try
        {
            return FabricLoader.getInstance().getModContainer("layerlist")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        }
        catch (RuntimeException | LinkageError e)
        {
            // Вне загрузчика (проверочный прогон) версии нет — это не повод ронять выгрузку
            return "";
        }
    }

    private static double round(double value)
    {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
