package com.aell.layerlist.schematics.library;

import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Четыре формата, которые умеет загружать Litematica. Формат определяется только по
 * расширению: заглядывать в файл ради списка папки дорого.
 */
@Environment(EnvType.CLIENT)
public enum SchematicFormat
{
    LITEMATIC(".litematic"),
    SPONGE(".schem"),
    VANILLA(".nbt"),
    SCHEMATICA(".schematic");

    private final String extension;

    SchematicFormat(String extension)
    {
        this.extension = extension;
    }

    /** Расширение с точкой и в нижнем регистре: {@code ".litematic"}. */
    public String extension()
    {
        return this.extension;
    }

    /** Формат по имени файла без учёта регистра; {@code null} — не схема. Файл без имени до точки — не схема. */
    public static @Nullable SchematicFormat of(Path path)
    {
        Path name = path.getFileName();

        if (name == null)
        {
            return null;
        }

        String lower = name.toString().toLowerCase(Locale.ROOT);

        for (SchematicFormat format : values())
        {
            if (lower.endsWith(format.extension) && lower.length() > format.extension.length())
            {
                return format;
            }
        }

        return null;
    }
}
