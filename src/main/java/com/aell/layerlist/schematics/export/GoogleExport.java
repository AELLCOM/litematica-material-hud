package com.aell.layerlist.schematics.export;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Google Таблицы «по кнопке» без входа в Google из игры: та же таблица сбора, записанная в профиле
 * Google (картинки — в той разметке, в какой Google выгружает их сам), и адрес
 * {@code https://sheets.new}: игрок открывает новую таблицу и делает «Файл → Импорт → Загрузка».
 * По сети мод ничего не отправляет.
 */
@Environment(EnvType.CLIENT)
final class GoogleExport
{
    static final URI SHEETS_NEW = URI.create("https://sheets.new");

    private GoogleExport() {}

    static Path write(ExportData data, @Nullable RawMaterials.Result raw, @Nullable ExportIcons icons, Path dir)
            throws IOException
    {
        XlsxWriter book = CollectionSheet.build(data, raw, icons, XlsxWriter.Profile.GOOGLE,
                SheetPalette.forTables());
        String base = ExportFiles.freeBase(dir, data.fileName(), ".xlsx");
        Path target = dir.resolve(base + ".xlsx");
        ExportFiles.write(target, book::write);

        return target;
    }
}
