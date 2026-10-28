package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.shaded.ui.Strings;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Форматы выгрузки в порядке кнопок подвала «Материалов» (SPEC §3.3): первый — главный, он встаёт
 * правее всех. Окно выгрузки получает формат готовым к выбору ({@link MaterialsExport#format()}).
 * Подписи — ключи {@code layerlist.schematics.materials.export.<id>}, подсказки — {@code ….tip}.
 */
@Environment(EnvType.CLIENT)
public enum ExportFormat
{
    /** Таблица сбора .xlsx — {@code Exporters.xlsx}. */
    XLSX(true),
    /** Тот же xlsx для импорта в Google Таблицы — {@code Exporters.google}. */
    GOOGLE(true),
    /** {@code Exporters.csv}. */
    CSV(false),
    /** TSV в буфер обмена — {@code Exporters.tsv}. */
    COPY(false),
    /** Текст частями — {@code Exporters.discord}. */
    DISCORD(false),
    /** Картинка-карточка — {@code Exporters.png}. */
    PNG(false),
    /** {@code Exporters.json}. */
    JSON(false),
    /** Список для браузера материалов Litematica — {@code Exporters.litematica}. */
    LITEMATICA(false);

    private static final String KEY = "layerlist.schematics.materials.export.";

    private final boolean primary;

    ExportFormat(boolean primary)
    {
        this.primary = primary;
    }

    /** Выделенная кнопка (жирные в SPEC: Excel, Google Таблицы). */
    public boolean primary()
    {
        return this.primary;
    }

    /** Короткое имя для ключей перевода и состояния: {@code xlsx}, {@code google}, … */
    public String id()
    {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public String label()
    {
        return Strings.plain(KEY + this.id());
    }

    public String description()
    {
        return Strings.plain(KEY + this.id() + ".tip");
    }
}
