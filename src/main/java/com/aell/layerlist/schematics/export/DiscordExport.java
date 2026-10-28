package com.aell.layerlist.schematics.export;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Текст для Discord: шапка и по строке на предмет («• Камень — 5 шалк. + 17 ст.»). Сообщение
 * Discord — не больше 2000 символов, поэтому список режется по границам строк на части, у
 * каждой своя шапка с номером. Таблиц Discord не показывает, а моноширинный блок на телефоне
 * переносится — поэтому простой список.
 */
@Environment(EnvType.CLIENT)
final class DiscordExport
{
    static final int LIMIT = 2000;
    private static final String MARKDOWN = "\\*_~`|>";

    private DiscordExport() {}

    static List<String> parts(ExportData data)
    {
        String head = ExportText.t("discord.header", escape(data.title()), ExportText.summary(data));
        List<String> lines = new ArrayList<>(data.lines().size());

        for (ExportData.Line line : data.lines())
        {
            lines.add(ExportText.t("discord.line", escape(line.name()), line.breakdown()));
        }

        // Номер части («(2/5)») занимает место — сначала режем с запасом под самый длинный номер
        String widest = ExportText.t("discord.part", lines.size() + 1, lines.size() + 1);
        int budget = LIMIT - head.length() - widest.length() - 1;
        List<List<String>> chunks = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        int used = 0;

        for (String line : lines)
        {
            String fitted = line.length() > budget - 1 ? line.substring(0, Math.max(1, budget - 2)) + "…" : line;
            int cost = fitted.length() + 1;

            if (! chunk.isEmpty() && used + cost > budget)
            {
                chunks.add(chunk);
                chunk = new ArrayList<>();
                used = 0;
            }

            chunk.add(fitted);
            used += cost;
        }

        if (! chunk.isEmpty() || chunks.isEmpty())
        {
            chunks.add(chunk);
        }

        List<String> parts = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++)
        {
            String number = chunks.size() > 1 ? ExportText.t("discord.part", i + 1, chunks.size()) : "";
            StringBuilder text = new StringBuilder(head).append(number);

            for (String line : chunks.get(i))
            {
                text.append('\n').append(line);
            }

            parts.add(text.toString());
        }

        return parts;
    }

    /** Разметка Discord в названиях (звёздочки, подчёркивания) не должна ломать список. */
    static String escape(String text)
    {
        StringBuilder out = new StringBuilder(text.length() + 8);

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            if (MARKDOWN.indexOf(c) >= 0)
            {
                out.append('\\');
            }

            out.append(c == '\n' || c == '\r' ? ' ' : c);
        }

        return out.toString();
    }
}
