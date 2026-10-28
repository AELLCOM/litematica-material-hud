package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.export.ExportOptions;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Что выгружать: кнопки форматов окна (порядок SPEC §3.3) и «Таблица команды». Подписи и
 * подсказки — ключи {@code layerlist.schematics.export.dialog.format.<id>}.
 */
@Environment(EnvType.CLIENT)
public enum ExportFormat
{
    XLSX("xlsx"),
    GOOGLE("google"),
    CSV("csv"),
    COPY("copy"),
    DISCORD("discord"),
    PNG("png"),
    JSON("json"),
    LITEMATICA("litematica"),
    /** Не кнопка сетки: своя строка окна, видна, только если задан адрес скрипта. */
    TEAM("team");

    private static final List<ExportFormat> GRID = List.of(XLSX, GOOGLE, CSV, COPY, DISCORD, PNG, JSON, LITEMATICA);

    private final String id;

    ExportFormat(String id)
    {
        this.id = id;
    }

    /** Кнопки сетки форматов, по порядку. */
    public static List<ExportFormat> grid()
    {
        return GRID;
    }

    /** Задан ли адрес скрипта таблицы команды (тогда строка «Отправить…» видна). */
    public static boolean teamConfigured()
    {
        return ! SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue().isBlank();
    }

    public String label()
    {
        return DialogText.t("format." + this.id);
    }

    public String tooltip()
    {
        return DialogText.t("format." + this.id + ".tip");
    }

    /** Нужны ли иконки предметов: картинке — всегда, таблицам — по флажку «Иконки предметов». */
    boolean needsIcons(ExportOptions options)
    {
        return this == PNG || options.icons() && (this == XLSX || this == GOOGLE || this == TEAM);
    }

    /** Таблица с оформлением: перед выгрузкой спрашивается палитра ({@link PaletteChooser}). */
    boolean styled()
    {
        return this == XLSX || this == GOOGLE || this == TEAM;
    }

    /** Текст в буфер обмена, а не файл: быстро, прямо на клиентском потоке. */
    boolean toClipboard()
    {
        return this == COPY || this == DISCORD;
    }
}
