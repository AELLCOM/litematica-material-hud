package com.aell.layerlist.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Проверки файлов выгрузки без сторонних библиотек: xlsx разбирается {@link ZipFile}, картинки —
 * {@link ImageIO}, JSON — Gson из игры. Главная проверка таблиц — правило SPEC §0.3: ни одной
 * булевой заглушки (ячейки {@code t="b"}, слова {@code TRUE}/{@code FALSE} в тексте, списках и
 * формулах).
 */
@Environment(EnvType.CLIENT)
final class KExports
{
    /** Слова-заглушки целиком; атрибуты OOXML пишутся строчными ({@code hidden="true"}) — это не они. */
    private static final Pattern BOOLEAN_WORD = Pattern.compile("\\b(TRUE|FALSE)\\b");
    private static final Pattern BOOLEAN_CELL = Pattern.compile("<c\\b[^>]*\\bt=\"b\"");
    private static final Pattern LITEMATICA_LINE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+ \\d+");

    private KExports() {}

    /** Итог проверки файла: {@code null} — всё хорошо, иначе — что не так. */
    static @Nullable String xlsx(Path file)
    {
        if (! nonEmpty(file))
        {
            return "файла нет или он пуст";
        }

        List<String> problems = new ArrayList<>();
        boolean workbook = false;
        boolean sheet = false;

        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                workbook |= name.equals("xl/workbook.xml");
                sheet |= name.startsWith("xl/worksheets/sheet");

                if (! name.endsWith(".xml") && ! name.endsWith(".rels"))
                {
                    continue;
                }

                String text;

                try (InputStream in = zip.getInputStream(entry))
                {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }

                Matcher word = BOOLEAN_WORD.matcher(text);

                if (word.find())
                {
                    problems.add(name + ": «" + word.group() + "» у позиции " + word.start());
                }

                if (BOOLEAN_CELL.matcher(text).find())
                {
                    problems.add(name + ": булева ячейка t=\"b\"");
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            return "не разбирается как zip: " + e;
        }

        if (! workbook || ! sheet)
        {
            problems.add("нет xl/workbook.xml или листа");
        }

        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** CSV для Excel RU: BOM, «;», CRLF, без булевых слов. */
    static @Nullable String csv(Path file)
    {
        try
        {
            byte[] bytes = Files.readAllBytes(file);

            if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xEF || (bytes[1] & 0xFF) != 0xBB || (bytes[2] & 0xFF) != 0xBF)
            {
                return "нет BOM UTF-8";
            }

            String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);

            if (! text.contains(";") || ! text.contains("\r\n"))
            {
                return "нет «;» или CRLF";
            }

            return words(text);
        }
        catch (IOException e)
        {
            return "не читается: " + e;
        }
    }

    /** Текст без булевых слов. */
    static @Nullable String words(@Nullable String text)
    {
        if (text == null || text.isEmpty())
        {
            return "пусто";
        }

        Matcher word = BOOLEAN_WORD.matcher(text);
        return word.find() ? "«" + word.group() + "» у позиции " + word.start() : null;
    }

    static @Nullable String json(Path file)
    {
        try
        {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return root.isJsonObject() || root.isJsonArray() ? null : "корень не объект и не массив";
        }
        catch (IOException | RuntimeException e)
        {
            return "не разбирается: " + e;
        }
    }

    /** Список для Litematica: «minecraft:id count» по строке. */
    static @Nullable String litematicaTxt(Path file)
    {
        try
        {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int good = 0;

            for (String line : lines)
            {
                String trimmed = line.strip();

                if (trimmed.isEmpty() || trimmed.startsWith("#"))
                {
                    continue;
                }

                if (! LITEMATICA_LINE.matcher(trimmed).matches())
                {
                    return "строка не по формату: " + trimmed;
                }

                ++good;
            }

            return good > 0 ? null : "ни одной строки";
        }
        catch (IOException e)
        {
            return "не читается: " + e;
        }
    }

    /** Размер картинки «Ш×В»; {@code null} — не PNG или не читается. */
    static @Nullable String png(Path file)
    {
        try
        {
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? null : image.getWidth() + "×" + image.getHeight();
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    static @Nullable String png(byte[] bytes)
    {
        try
        {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? null : image.getWidth() + "×" + image.getHeight();
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    static boolean nonEmpty(@Nullable Path file)
    {
        try
        {
            return file != null && Files.isRegularFile(file) && Files.size(file) > 0;
        }
        catch (IOException e)
        {
            return false;
        }
    }
}
