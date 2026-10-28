package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Что кнопка формата в подвале «Материалов» передаёт окну выгрузки: строки как на экране и
 * формат, который окно должно открыть уже выбранным.
 *
 * <p>{@code onlyMissing} подобран так, чтобы количество в выгрузке совпало с колонкой «Всего»
 * таблицы: ядро выгрузок берёт {@code missing} при «только недостающем» и {@code total} без него.
 * У схемы без сверки с миром {@code missing == total}, у сверенной ({@code placed}) флажок — как в таблице, а у списка HUD «Всего» — это и есть {@code missing}, поэтому для
 * HUD флажок включён всегда.
 *
 * @param title       название таблицы по умолчанию: имя схемы, у списка HUD — его подпись
 * @param source      подпись источника («Схема: имя» / «Список HUD») — для {@link ExportRequest#source()}
 * @param rows        строки таблицы после поиска и фильтра, в её текущем порядке
 * @param sourceRows  строк у источника до поиска и фильтра (окно может сказать «12 из 345»)
 * @param onlyMissing для {@link ExportOptions#onlyMissing()}
 * @param format      формат, выбранный кнопкой
 * @param entry       файл схемы, если он есть (заголовок окна); {@code null} — HUD или размещение без
 *                    файла
 * @param fileName    имя файла выгрузки (0.0.27): схема файла или размещения, у списка HUD — схемы
 *                    включённых размещений ({@code gui.export.ExportNames})
 * @param hud         строки — список HUD (0.0.27): окно пишет, что значит «не хватает» у этого источника
 * @param disabledBlocks сколько блоков отключено в таблице (в выгрузку они не идут): окно напоминает об этом
 * @param placed      строки схемы сверены с миром (размещена): {@code missing} — что ещё не поставлено, и
 *                    {@code onlyMissing} — ровно как в таблице, а не принудительно (1.0.18)
 */
@Environment(EnvType.CLIENT)
public record MaterialsExport(String title, String source, List<MaterialRow> rows, int sourceRows, boolean onlyMissing,
                              ExportFormat format, @Nullable SchematicEntry entry, String fileName, boolean hud,
                              long disabledBlocks, boolean placed)
{
    public MaterialsExport
    {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fileName, "fileName");
        rows = List.copyOf(rows);
    }

    /** Схема без сверки с миром (как до 1.0.18). */
    public MaterialsExport(String title, String source, List<MaterialRow> rows, int sourceRows, boolean onlyMissing,
                           ExportFormat format, @Nullable SchematicEntry entry, String fileName, boolean hud,
                           long disabledBlocks)
    {
        this(title, source, rows, sourceRows, onlyMissing, format, entry, fileName, hud, disabledBlocks, false);
    }

    /** Без отключённых блоков (как до 1.0.12). */
    public MaterialsExport(String title, String source, List<MaterialRow> rows, int sourceRows, boolean onlyMissing,
                           ExportFormat format, @Nullable SchematicEntry entry, String fileName, boolean hud)
    {
        this(title, source, rows, sourceRows, onlyMissing, format, entry, fileName, hud, 0L, false);
    }

    /** Как до 0.0.27: файл — по имени файла схемы, без него — по названию. */
    public MaterialsExport(String title, String source, List<MaterialRow> rows, int sourceRows, boolean onlyMissing,
                           ExportFormat format, @Nullable SchematicEntry entry)
    {
        this(title, source, rows, sourceRows, onlyMissing, format, entry, entry != null ? entry.name() : title, false, 0L, false);
    }

    /** Флажки окна по умолчанию: запомненные, но «только недостающее» — как у таблицы. */
    public ExportOptions options(ExportOptions remembered)
    {
        return new ExportOptions(this.onlyMissing, remembered.icons(), remembered.rawMaterials(), remembered.chart());
    }

    /** Запрос к ядру выгрузок (M26): название и флажки — из окна, строки и источник — отсюда. */
    public ExportRequest request(String title, List<String> roster, ExportOptions options)
    {
        return new ExportRequest(title, this.source, this.rows, roster, options, this.fileName);
    }
}
