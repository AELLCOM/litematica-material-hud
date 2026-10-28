package com.aell.layerlist.schematics.export;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Итог выгрузки для тоста.
 *
 * @param files     записанные файлы (пусто — выгрузка в буфер или ошибка)
 * @param clipboard текст, который надо положить в буфер обмена; {@code null} — не надо
 * @param open      что открыть в браузере (таблица команды, sheets.new); {@code null} — ничего
 * @param error     текст ошибки для игрока; {@code null} — всё получилось
 */
@Environment(EnvType.CLIENT)
public record ExportResult(List<Path> files, @Nullable String clipboard, @Nullable URI open, @Nullable String error)
{
    public ExportResult
    {
        files = List.copyOf(files);
    }

    public static ExportResult failure(String error)
    {
        return new ExportResult(List.of(), null, null, Objects.requireNonNull(error, "error"));
    }

    public boolean ok()
    {
        return this.error == null;
    }
}
