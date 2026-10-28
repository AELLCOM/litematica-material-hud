package com.aell.layerlist.schematics.library;

import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Строка каталога: папка или файл схемы. Без чтения NBT — только то, что даёт файловая система.
 * Равенство записей (путь, размер, время) — ключ кеша сведений и миниатюр: изменился файл —
 * изменилась и запись.
 *
 * @param path      абсолютный путь
 * @param directory папка ли это
 * @param format    формат файла; у папки {@code null}
 * @param size      размер файла в байтах; у папки 0
 * @param modified  время изменения, мс от эпохи; 0 — неизвестно
 */
@Environment(EnvType.CLIENT)
public record SchematicEntry(Path path, boolean directory, @Nullable SchematicFormat format, long size, long modified)
{
    public SchematicEntry
    {
        Objects.requireNonNull(path, "path");
    }

    /** Имя для списка: у файла — без расширения формата, у папки — как есть. */
    public String name()
    {
        String name = this.fileName();

        if (! this.directory && this.format != null && name.length() > this.format.extension().length())
        {
            return name.substring(0, name.length() - this.format.extension().length());
        }

        return name;
    }

    /** Имя файла или папки целиком, с расширением. */
    public String fileName()
    {
        Path name = this.path.getFileName();

        return name == null ? this.path.toString() : name.toString();
    }
}
