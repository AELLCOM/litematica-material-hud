package com.aell.layerlist.dev;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Файлы дампов и лог прогона. Пишем построчно и с немедленным сбросом на диск: прогон
 * должен пережить падение клиента на середине, а перезапуск — продолжиться с того места,
 * где остановился, по уже записанным ключам.
 */
public final class CovOut implements AutoCloseable
{
    private static final String PREFIX = "[COVERAGE] ";

    private final Path path;
    private final Writer writer;
    private final Set<String> done = new HashSet<>();

    private CovOut(Path path, Writer writer)
    {
        this.path = path;
        this.writer = writer;
    }

    public static void log(String message)
    {
        System.out.println(PREFIX + message);
        System.out.flush();
    }

    /** Каталог дампов: docs/easyplace/coverage рядом с исходниками, а не в run/. */
    public static Path dir()
    {
        String override = System.getenv("LL_COVERAGE_OUT");

        if (override != null && ! override.isBlank())
        {
            return Path.of(override);
        }

        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path root = gameDir.getParent() != null ? gameDir.getParent() : gameDir;

        return root.resolve("docs").resolve("easyplace").resolve("coverage");
    }

    /**
     * Открывает дамп на дозапись. Если файл уже есть, его ключи (первая колонка)
     * попадают в {@link #has(String)} — строки не переписываются заново.
     */
    public static CovOut open(String name, String header) throws IOException
    {
        Path dir = dir();
        Files.createDirectories(dir);

        Path path = dir.resolve(name);
        Set<String> done = new HashSet<>();
        boolean fresh = ! Files.exists(path);

        if (! fresh)
        {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
            {
                String key = firstField(line);

                if (! key.isEmpty())
                {
                    done.add(key);
                }
            }
        }

        Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        CovOut out = new CovOut(path, writer);

        if (fresh)
        {
            out.rawLine(header);
        }
        else
        {
            log("дозапись " + name + ": уже готово строк " + (done.size() - 1));
        }

        return out;
    }

    private static String firstField(String line)
    {
        if (line.isEmpty())
        {
            return "";
        }

        if (line.charAt(0) != '"')
        {
            int comma = line.indexOf(',');

            return comma < 0 ? line : line.substring(0, comma);
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 1; i < line.length(); ++i)
        {
            char c = line.charAt(i);

            if (c != '"')
            {
                sb.append(c);
                continue;
            }

            if (i + 1 < line.length() && line.charAt(i + 1) == '"')
            {
                sb.append('"');
                ++i;
                continue;
            }

            break;
        }

        return sb.toString();
    }

    public boolean has(String key)
    {
        return this.done.contains(key);
    }

    public int doneCount()
    {
        return Math.max(0, this.done.size() - 1);
    }

    public Path path()
    {
        return this.path;
    }

    public void row(List<String> fields)
    {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < fields.size(); ++i)
        {
            if (i > 0)
            {
                sb.append(',');
            }

            sb.append(escape(fields.get(i)));
        }

        if (! fields.isEmpty())
        {
            this.done.add(fields.get(0));
        }

        this.rawLine(sb.toString());
    }

    private void rawLine(String line)
    {
        try
        {
            this.writer.write(line);
            this.writer.write('\n');
            this.writer.flush();
        }
        catch (IOException e)
        {
            log("не записать строку в " + this.path + ": " + e);
        }
    }

    public static String escape(String value)
    {
        String text = value == null ? "" : value;

        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0)
        {
            return text;
        }

        return '"' + text.replace("\"", "\"\"") + '"';
    }

    @Override
    public void close()
    {
        try
        {
            this.writer.close();
        }
        catch (IOException e)
        {
            log("не закрыть " + this.path + ": " + e);
        }
    }
}
