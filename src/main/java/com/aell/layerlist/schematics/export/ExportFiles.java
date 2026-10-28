package com.aell.layerlist.schematics.export;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Имена и запись файлов выгрузки. Файл называется по схеме: {@code <схема>.<ext>}; занятое имя —
 * {@code <схема> (2).<ext>}, {@code (3)}… — прежний файл не затирается никогда (его могли уже открыть
 * или залить в Google). Запись — в {@code .tmp} рядом и атомарный перенос: оборванная запись не
 * оставляет полфайла под настоящим именем.
 */
@Environment(EnvType.CLIENT)
final class ExportFiles
{
    /** Имя без расширения не длиннее этого: длинный путь в Windows не откроют ни Excel, ни проводник. */
    static final int NAME_MAX = 100;
    // Знаки, запрещённые в именах Windows (кроме управляющих — они отдельно)
    private static final String FORBIDDEN = "<>:\"/\\|?*";

    // Имена устройств Windows: файл «CON.xlsx» там не создать
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private ExportFiles() {}

    /** Тело записи файла. */
    @FunctionalInterface
    interface Body
    {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Имя, которое можно создать на любой системе. Запрещённые Windows знаки {@code <>:"/\|?*} и
     * управляющие становятся пробелом, пробелы схлопываются; невидимые знаки форматирования и эмодзи
     * выбрасываются; без точек и пробелов по краям; не длиннее {@link #NAME_MAX} (режется по слову);
     * имя устройства ({@code CON}, {@code COM1}…) получает «_». Кириллица и прочие буквы остаются.
     * Пусто — «Материалы» из перевода.
     */
    static String safeName(String name)
    {
        String text = ExportText.plain(name);
        StringBuilder out = new StringBuilder(Math.min(text.length(), NAME_MAX * 2));
        boolean gap = false;

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            // Половинки эмодзи и невидимые знаки (в том числе разворот письма U+202E, которым подделывают
            // расширение) — источник бед в архиваторах, мессенджерах и самом проводнике
            if (Character.isSurrogate(c) || Character.getType(c) == Character.FORMAT)
            {
                continue;
            }

            if (Character.isISOControl(c) || FORBIDDEN.indexOf(c) >= 0 || Character.isWhitespace(c)
                    || Character.isSpaceChar(c))
            {
                gap = true;
                continue;
            }

            if (gap && out.length() > 0)
            {
                out.append(' ');
            }

            gap = false;
            out.append(c);
        }

        String result = trimEdges(cut(out.toString()));

        if (result.isEmpty())
        {
            result = trimEdges(cut(ExportText.plain(ExportText.t("untitled"))));
        }

        if (result.isEmpty())
        {
            result = "materials";
        }

        int dot = result.indexOf('.');
        String stem = dot < 0 ? result : result.substring(0, dot);

        if (RESERVED.contains(stem.strip().toUpperCase(Locale.ROOT)))
        {
            result = stem + "_" + (dot < 0 ? "" : result.substring(dot));
        }

        return result;
    }

    /** Не длиннее {@link #NAME_MAX}: по последнему пробелу, если он не дальше середины, иначе — как есть. */
    private static String cut(String name)
    {
        if (name.length() <= NAME_MAX)
        {
            return name;
        }

        int space = name.lastIndexOf(' ', NAME_MAX);

        return space >= NAME_MAX / 2 ? name.substring(0, space) : name.substring(0, NAME_MAX);
    }

    /**
     * Без пробелов и точек по краям: Windows молча срезает хвостовые (файл «Дом.» стал бы «Дом»), а
     * точка в начале прячет файл в Linux и macOS.
     */
    private static String trimEdges(String name)
    {
        int from = 0;
        int to = name.length();

        while (from < to && (name.charAt(from) == '.' || name.charAt(from) == ' '))
        {
            from++;
        }

        while (to > from && (name.charAt(to - 1) == '.' || name.charAt(to - 1) == ' '))
        {
            to--;
        }

        return name.substring(from, to);
    }

    /**
     * Свободная основа имени в папке: {@code <имя>}, а если файл с ней и любым из {@code suffixes}
     * уже есть — {@code <имя> (2)}, {@code (3)}… Суффиксы — то, что дописывается к основе
     * ({@code ".xlsx"}; у списка Litematica — оба файла пары; пустой — сама основа, папка картинок), чтобы ни
     * одна часть набора не легла поверх чужой.
     */
    static String freeBase(Path dir, String name, String... suffixes)
    {
        String base = safeName(name);

        for (int n = 1; ; n++)
        {
            String candidate = n == 1 ? base : base + " (" + n + ")";
            boolean taken = false;

            for (String suffix : suffixes)
            {
                if (Files.exists(dir.resolve(candidate + suffix)))
                {
                    taken = true;
                    break;
                }
            }

            if (! taken)
            {
                return candidate;
            }
        }
    }

    static void write(Path target, Body body) throws IOException
    {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");

        try
        {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp)))
            {
                body.write(out);
            }

            try
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    static void writeString(Path target, String text, boolean bom) throws IOException
    {
        write(target, out ->
        {
            if (bom)
            {
                out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            }

            out.write(text.getBytes(StandardCharsets.UTF_8));
        });
    }
}
