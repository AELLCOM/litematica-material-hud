package com.aell.layerlist.schematics.export;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * «Копировать» — значения без формул через табуляцию: вставка в Excel и Google Таблицы сама
 * раскладывает их по ячейкам. Формул нет сознательно: разделитель аргументов зависит от
 * локали таблицы (в русской — «;»), и вставленная формула с запятыми сломается.
 */
@Environment(EnvType.CLIENT)
final class TsvExport
{
    private TsvExport() {}

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
                out.append('\t');
            }

            // Таб и перевод строки внутри поля разорвали бы таблицу; кавычка в начале — это CSV-кавычки
            String value = defuse(fields.get(i)).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
            out.append(value.startsWith("\"") ? "'" + value : value);
        }

        out.append('\n');
    }

    /**
     * Текст, который таблица приняла бы за формулу ({@code =}, {@code +}, {@code -}, {@code @} в
     * начале), — с апострофом впереди. Переименованный на наковальне предмет не должен
     * превращаться в формулу у того, кто откроет файл.
     */
    static String defuse(String value)
    {
        if (value.isEmpty())
        {
            return value;
        }

        char first = value.charAt(0);
        boolean number = value.chars().allMatch(c -> c == '-' || Character.isDigit(c)) && value.length() > 1;

        return ! number && "=+-@".indexOf(first) >= 0 ? "'" + value : value;
    }
}
