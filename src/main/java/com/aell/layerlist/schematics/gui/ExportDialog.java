package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.gui.export.ExportFormat;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.materials.MaterialsExport;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Окно выгрузки (M26, интерфейс) — вход из экрана «Схемы». Само окно —
 * {@link ExportWindow} (пакет {@code gui.export}); здесь — то, что знает только экран: его
 * попапы и тосты, выбранная схема и её название, меню «Выгрузить ▾».
 *
 * <p>Источники: из карточки — «Схема: &lt;имя&gt;» (выбран) и «Список HUD»; без схемы — только
 * «Список HUD»; из вкладки «Материалы» — уже посчитанные ею строки
 * ({@link #open(GuiSchematics, MaterialsExport)}, {@link #openRows}).
 */
@Environment(EnvType.CLIENT)
final class ExportDialog
{
    private ExportDialog() {}

    /** Окно без выбранного формата (так зовёт {@code GuiSchematics.openExport}). */
    static void open(GuiSchematics screen, @Nullable SchematicEntry entry)
    {
        open(screen, entry, null);
    }

    /** Окно для схемы (или для списка HUD, если {@code entry == null}); {@code format} — выбран в меню. */
    static void open(GuiSchematics screen, @Nullable SchematicEntry entry, @Nullable ExportFormat format)
    {
        List<ExportSource> sources = new ArrayList<>(2);
        String name = "";

        if (entry != null)
        {
            sources.add(ExportSource.schematic(entry));
            name = defaultName(screen, entry);
        }

        sources.add(ExportSource.hud());

        String title = entry == null ? Tr.t("export.title") : Tr.f("export.title_for", name);
        ExportWindow.open(host(screen), title, name, sources, 0, format);
    }

    /**
     * Окно для строк, которые уже показывает вкладка «Материалы» (M25 UI): без пересчёта, источник
     * — её подпись («Схема: …» / «Список HUD»), {@code fileName} — схема, по которой назовётся файл,
     * {@code entry} — её файл для постера выгрузки «Картинка» ({@code null} у списка HUD).
     */
    static void openRows(GuiSchematics screen, String name, String sourceLabel, List<MaterialRow> rows,
                         @Nullable ExportFormat format, String fileName, @Nullable ExportSource.Kind kind,
                         boolean narrowed, @Nullable SchematicEntry entry)
    {
        openRows(screen, name, sourceLabel, rows, format, fileName, kind, narrowed, entry, 0L);
    }

    /** То же и число отключённых в схеме блоков — окно напомнит, что в таблицу они не вошли. */
    static void openRows(GuiSchematics screen, String name, String sourceLabel, List<MaterialRow> rows,
                         @Nullable ExportFormat format, String fileName, @Nullable ExportSource.Kind kind,
                         boolean narrowed, @Nullable SchematicEntry entry, long disabledBlocks)
    {
        String title = name.isBlank() ? Tr.t("export.title") : Tr.f("export.title_for", name);
        ExportWindow.open(host(screen), title, name,
                List.of(ExportSource.rows(sourceLabel, rows, fileName, kind, narrowed, entry, disabledBlocks)), 0, format);
    }

    /**
     * Кнопка формата в подвале вкладки «Материалы» (M25 UI): строки как в таблице (после поиска,
     * фильтра, в её порядке), формат уже выбран.
     */
    static void open(GuiSchematics screen, MaterialsExport request)
    {
        // «Только недостающее» окна — как у таблицы: иначе количество в выгрузке разошлось бы с её
        // колонкой «Всего» (у списка HUD там недостающее). Флажок окна общий и запоминается — так и задумано
        SchematicsState state = SchematicsState.get();
        state.setExportOptions(request.options(state.exportOptions()));

        openRows(screen, request.title(), request.source(), request.rows(), format(request.format()),
                request.fileName(), request.hud() ? ExportSource.Kind.HUD
                        : request.placed() ? ExportSource.Kind.PLACED : ExportSource.Kind.SCHEMATIC,
                request.rows().size() < request.sourceRows(), request.hud() ? null : request.entry(), request.disabledBlocks());
    }

    /** Кнопки подвала «Материалов» → кнопки окна; без {@code default}, чтобы новый формат не прошёл молча. */
    private static ExportFormat format(com.aell.layerlist.schematics.gui.materials.ExportFormat format)
    {
        return switch (format)
        {
            case XLSX -> ExportFormat.XLSX;
            case GOOGLE -> ExportFormat.GOOGLE;
            case CSV -> ExportFormat.CSV;
            case COPY -> ExportFormat.COPY;
            case DISCORD -> ExportFormat.DISCORD;
            case PNG -> ExportFormat.PNG;
            case JSON -> ExportFormat.JSON;
            case LITEMATICA -> ExportFormat.LITEMATICA;
        };
    }

    /**
     * Меню «Выгрузить ▾» под кнопкой {@code anchor}: форматы (выбор открывает окно с этим форматом —
     * осталось проверить ники и нажать), «Таблица команды» (если настроена) и «Все форматы…».
     */
    static void menu(GuiSchematics screen, Widget anchor, @Nullable SchematicEntry entry)
    {
        List<ContextMenu.Item> items = new ArrayList<>();

        for (ExportFormat format : ExportFormat.grid())
        {
            items.add(ContextMenu.Item.of(format.label(), () -> open(screen, entry, format)).withTooltip(format.tooltip()));
        }

        if (ExportFormat.teamConfigured())
        {
            items.add(ContextMenu.Item.separator());
            items.add(ContextMenu.Item.of(ExportFormat.TEAM.label(), () -> open(screen, entry, ExportFormat.TEAM))
                    .withTooltip(ExportFormat.TEAM.tooltip()));
        }

        items.add(ContextMenu.Item.separator());
        items.add(ContextMenu.Item.of(Tr.t("export.dialog.all_formats"), () -> open(screen, entry, null)));

        ContextMenu.below(screen.layers, anchor.x(), anchor.y(), anchor.w(), anchor.h(), items);
    }

    private static ExportWindow.Host host(GuiSchematics screen)
    {
        return new ExportWindow.Host(screen.layers, screen.toasts, () -> GuiUtils.getCurrentScreen() == screen);
    }

    /** Название таблицы по умолчанию: имя из метаданных схемы, если уже прочитано, иначе имя файла. */
    private static String defaultName(GuiSchematics screen, SchematicEntry entry)
    {
        SchematicInfo info = Objects.equals(screen.selected(), entry) ? screen.selectedInfo() : null;

        return info != null && info.ok() && ! info.name().isBlank() ? info.name() : entry.name();
    }
}
