package com.aell.layerlist.schematics.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * CSV для Excel с русской Windows: UTF-8 с BOM (без него Excel читает кракозябры), разделитель
 * «;» (системный разделитель списка в RU), строки через CRLF. Строку-подсказку {@code sep=;} не
 * пишем: с ней Excel перестаёт видеть BOM.
 */
@Environment(EnvType.CLIENT)
final class CsvExport
{
    private static final char SEPARATOR = ';';
    private static final String NEWLINE = "\r\n";

    private CsvExport() {}

    static Path write(ExportData data, Path dir) throws IOException
    {
        String base = ExportFiles.freeBase(dir, data.fileName(), ".csv");
        Path target = dir.resolve(base + ".csv");
        ExportFiles.writeString(target, text(data), true);

        return target;
    }

    static String text(ExportData data)
    {
        StringBuilder out = new StringBuilder();
        line(out, List.of(
                ExportText.t("csv.item"), ExportText.t("csv.total"), ExportText.t("csv.breakdown"),
                ExportText.t("csv.inventory"), ExportText.t("csv.chests"), ExportText.t("csv.missing"),
                ExportText.t("csv.id")));

        for (ExportData.Line line : data.lines())
        {
            line(out, List.of(
                    line.name(), Long.toString(line.row().total()), line.row().breakdown(),
                    Long.toString(line.row().inInventory()), Long.toString(line.row().inChests()),
                    Long.toString(line.row().missing()), line.id()));
        }

        return out.toString();
    }

    private static void line(StringBuilder out, List<String> fields)
    {
        for (int i = 0; i < fields.size(); i++)
        {
            if (i > 0)
            {
                out.append(SEPARATOR);
            }

            out.append(field(fields.get(i)));
        }

        out.append(NEWLINE);
    }

    private static String field(String raw)
    {
        String value = TsvExport.defuse(raw);
        boolean quote = value.indexOf(SEPARATOR) >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || ! value.equals(value.strip());

        return quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
