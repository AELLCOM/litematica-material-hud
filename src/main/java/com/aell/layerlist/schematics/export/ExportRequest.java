package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.materials.MaterialRow;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Что выгружать. Строки уже отобраны источником; {@link ExportOptions#onlyMissing()} выгрузка
 * применяет сама, чтобы окно могло переключать флажок без пересчёта.
 *
 * @param title    название таблицы (по умолчанию — имя схемы): шапка листа, ярлык листа, подписи
 * @param source   подпись источника для шапки: «Схема: …» / «Список HUD»
 * @param rows     строки материалов
 * @param roster   ники «Кто собирает», по порядку
 * @param options  флажки
 * @param fileName имя файла без расширения (0.0.27): имя схемы, у списка HUD — схемы его размещений;
 *                 очистку под Windows и «(2)» для занятого имени делает выгрузка. Пусто — {@code title}
 */
@Environment(EnvType.CLIENT)
public record ExportRequest(String title, String source, List<MaterialRow> rows, List<String> roster,
                            ExportOptions options, String fileName)
{
    public ExportRequest
    {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(fileName, "fileName");
        rows = List.copyOf(rows);
        roster = List.copyOf(roster);
    }

    /** Как до 0.0.27: файл называется по названию таблицы. */
    public ExportRequest(String title, String source, List<MaterialRow> rows, List<String> roster,
                         ExportOptions options)
    {
        this(title, source, rows, roster, options, title);
    }
}
