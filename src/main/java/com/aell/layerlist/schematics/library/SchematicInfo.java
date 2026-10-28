package com.aell.layerlist.schematics.library;

import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Сведения о файле схемы для карточки и второй строки списка. Читаются своим лёгким
 * читателем NBT, без загрузки схемы в Litematica.
 *
 * @param name        название из метаданных, иначе имя файла без расширения
 * @param author      автор; пусто — неизвестен
 * @param description описание; пусто — нет
 * @param volume      объём габарита, блоков; -1 — неизвестно
 * @param blocks      непустых блоков; -1 — неизвестно
 * @param regions     регионов; у форматов без регионов — 1
 * @param created     время создания, мс от эпохи; 0 — неизвестно
 * @param modified    время изменения, мс от эпохи; 0 — неизвестно
 * @param dataVersion версия данных Minecraft; 0 — неизвестна
 * @param previewArgb встроенное превью Litematica: ARGB построчно, квадрат {@code previewSide}²; {@code null} — нет
 * @param previewSide сторона превью в пикселях; 0 — превью нет
 * @param error       текст ошибки чтения для карточки; {@code null} — прочитано
 */
@Environment(EnvType.CLIENT)
public record SchematicInfo(String name, String author, String description, int sizeX, int sizeY, int sizeZ,
                            long volume, long blocks, int regions, long created, long modified, int dataVersion,
                            int @Nullable [] previewArgb, int previewSide, @Nullable String error)
{
    public SchematicInfo
    {
        Objects.requireNonNull(name, "name");
        author = author == null ? "" : author;
        description = description == null ? "" : description;

        // Превью без пикселей или с неверной стороной — считаем, что его нет
        if (previewArgb == null || previewSide <= 0 || previewArgb.length != (long) previewSide * previewSide)
        {
            previewArgb = null;
            previewSide = 0;
        }
    }

    /** Файл не прочитался: в карточке будет имя и текст ошибки, числа — «неизвестно». */
    public static SchematicInfo failed(String name, String error)
    {
        return new SchematicInfo(name, "", "", 0, 0, 0, -1L, -1L, 0, 0L, 0L, 0, null, 0,
                Objects.requireNonNull(error, "error"));
    }

    public boolean ok()
    {
        return this.error == null;
    }

    public boolean hasPreview()
    {
        return this.previewArgb != null;
    }
}
