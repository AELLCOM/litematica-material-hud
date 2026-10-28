package com.aell.layerlist.schematics.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Список для Litematica: {@code .txt} («{@code minecraft:stone 9744}» по строке, {@code #} —
 * комментарий) и {@code .json} ({@code {"name", "items":[{"id","count"}]}}) в папке
 * {@code <схемы>/material-lists/}. Оба формата Litematica 0.29 открывает из своего браузера схем
 * как собственный список материалов. Варианты одного предмета (флаги с узорами) там не
 * различаются — количества складываются по id.
 */
@Environment(EnvType.CLIENT)
final class LitematicaListExport
{
    static final String FOLDER = "material-lists";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LitematicaListExport() {}

    static List<Path> write(ExportData data, Path schematicsRoot) throws IOException
    {
        Path dir = schematicsRoot.resolve(FOLDER);
        String base = ExportFiles.freeBase(dir, data.fileName(), ".txt", ".json");
        Path txt = dir.resolve(base + ".txt");
        Path json = dir.resolve(base + ".json");
        ExportFiles.writeString(txt, text(data), false);
        ExportFiles.writeString(json, json(data) + "\n", false);

        return List.of(txt, json);
    }

    static Map<String, Long> counts(ExportData data)
    {
        Map<String, Long> counts = new LinkedHashMap<>();

        for (ExportData.Line line : data.lines())
        {
            counts.merge(line.id(), line.quantity(), Long::sum);
        }

        // Litematica читает количество как int; больше в схему и не влезет, но строку она бы отвергла
        counts.replaceAll((id, count) -> Math.min(count, Integer.MAX_VALUE));

        return counts;
    }

    static String text(ExportData data)
    {
        StringBuilder out = new StringBuilder();
        // «#» — комментарий для читателя Litematica; пишем его сами, а не из перевода, и в одну строку:
        // перевод строки в названии превратил бы хвост в «предмет»
        String title = data.title().replace('\n', ' ').replace('\r', ' ');
        out.append("# ").append(ExportText.t("litematica.comment", title)).append('\n');
        counts(data).forEach((id, count) -> out.append(id).append(' ').append(count).append('\n'));

        return out.toString();
    }

    static String json(ExportData data)
    {
        JsonObject root = new JsonObject();
        root.addProperty("name", data.title());
        JsonArray items = new JsonArray();

        counts(data).forEach((id, count) ->
        {
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("count", count);
            items.add(item);
        });

        root.add("items", items);

        return GSON.toJson(root);
    }
}
