package com.aell.layerlist.schematics;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.language.I18n;

/**
 * Опции вкладки «Схемы». Живут отдельно, но входят в общие списки {@code LayerListConfigs}
 * ({@code OPTIONS}, {@code HOTKEYS}): так malilib сохраняет их в {@code layerlist.json} вместе с
 * остальными, а «Сбросить всё» сбрасывает и их. Показываются в окне настроек самого экрана
 * «Схемы», не в экране настроек мода (кроме хоткея — он на вкладке «Клавиши» рядом с прочими).
 */
@Environment(EnvType.CLIENT)
public final class SchematicsConfig
{
    /** Папка выгрузок по умолчанию — в папке игры. */
    public static final String DEFAULT_EXPORT_FOLDER = "material-hud-exports";

    public static final ConfigBoolean MAIN_MENU_BUTTON = new ConfigBoolean("schematicsMainMenuButton", true);
    /** Главное меню Litematica в цветах Material HUD (aell-ui). */
    public static final ConfigBoolean LITEMATICA_SKIN = new ConfigBoolean("litematicaSkin", false);
    /** Свои экраны вместо экранов Litematica: «Редактор региона» → вкладка «Выделение». */
    public static final ConfigBoolean LITEMATICA_OWN_SCREENS = new ConfigBoolean("litematicaOwnScreens", false);
    public static final ConfigBoolean THUMBNAILS      = new ConfigBoolean("schematicsThumbnails", true);
    public static final ConfigOptionList PREVIEW_QUALITY =
            new ConfigOptionList("schematicsPreviewQuality", PreviewQuality.OPTIMIZED);
    public static final ConfigBoolean LIVE_GHOSTS     = new ConfigBoolean("schematicsLiveGhosts", true);
    public static final ConfigBoolean TOP_HIDE_CLOUDS = new ConfigBoolean("schematicsTopHideClouds", true);
    /**
     * Вкладка «Выделение»: составное выделение Litematica (режим «Normal», несколько компонентов) вместо простого.
     * Режим Litematica подстраивается под опцию, пока вкладка на экране; переключателя на самой вкладке нет.
     */
    public static final ConfigBoolean SELECTION_COMPOUND = new ConfigBoolean("selectionCompound", false);

    /** Пусто — {@link #DEFAULT_EXPORT_FOLDER} в папке игры; путь в конфиге тогда переносим между компьютерами. */
    public static final ConfigString EXPORT_DIR          = new ConfigString("schematicsExportDir", "");
    public static final ConfigString SHEETS_WEB_APP_URL  = new ConfigString("schematicsSheetsWebAppUrl", "");
    public static final ConfigString SHEETS_TOKEN        = new ConfigString("schematicsSheetsToken", "");
    /** Цвета Google Таблиц: файла для импорта и листа в таблице команды. */
    public static final ConfigOptionList SHEETS_PALETTE =
            new ConfigOptionList("schematicsSheetsPalette", SheetsPalette.LIGHT_GRAY);

    /** Не назначен: свободных сочетаний рядом с Litematica мало, пусть игрок выберет сам. */
    public static final ConfigHotkey OPEN_SCHEMATICS = new ConfigHotkey("openSchematics", "");
    /**
     * «Смотреть на мир»: курсор к карточке координат и обратно в игру. Действует, только пока карточка на экране;
     * R — свободна и в игре, и в Litematica (Alt занят: сдвиг выделения и инструмент).
     */
    public static final ConfigHotkey SELECTION_CURSOR = new ConfigHotkey("selectionWorldCursor", "R");

    public static final List<IConfigBase> OPTIONS = ImmutableList.of(
            MAIN_MENU_BUTTON, LITEMATICA_SKIN, LITEMATICA_OWN_SCREENS, THUMBNAILS, PREVIEW_QUALITY, LIVE_GHOSTS, TOP_HIDE_CLOUDS,
            SELECTION_COMPOUND, EXPORT_DIR, SHEETS_PALETTE, SHEETS_WEB_APP_URL, SHEETS_TOKEN);

    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(OPEN_SCHEMATICS, SELECTION_CURSOR);

    private SchematicsConfig() {}

    public static PreviewQuality previewQuality()
    {
        return PREVIEW_QUALITY.getOptionListValue() instanceof PreviewQuality quality
                ? quality
                : PreviewQuality.OPTIMIZED;
    }

    public static SheetsPalette sheetsPalette()
    {
        return SHEETS_PALETTE.getOptionListValue() instanceof SheetsPalette palette
                ? palette
                : SheetsPalette.LIGHT_GRAY;
    }

    /** Папка выгрузок: из опции (относительный путь — от папки игры) или папка по умолчанию. */
    public static Path exportDir()
    {
        Path game = FabricLoader.getInstance().getGameDir();
        String value = EXPORT_DIR.getStringValue().trim();

        if (! value.isEmpty())
        {
            try
            {
                return game.resolve(value).toAbsolutePath().normalize();
            }
            catch (InvalidPathException e)
            {
                // Кривой путь в конфиге не должен ломать выгрузку — пишем туда, где точно можно
            }
        }

        return game.resolve(DEFAULT_EXPORT_FOLDER).toAbsolutePath().normalize();
    }

    /**
     * Отображение схемы в 3D-превью (0.0.27, вместо «Авто / Высокое / Быстрое»): «Оптимизировано» — бюджет по объёму
     * схемы, большая — кубами (как прежнее «Авто»); «Качественно» — честными блоками, пока хватает памяти игры
     * ({@link PreviewLimits}). Прежние значения конфига: {@code high} → «Качественно», {@code auto} и {@code fast} →
     * «Оптимизировано».
     */
    public enum PreviewQuality implements IConfigOptionListEntry
    {
        OPTIMIZED("optimized"),
        QUALITY("quality");

        private final String id;

        PreviewQuality(String id)
        {
            this.id = id;
        }

        @Override
        public String getStringValue()
        {
            return this.id;
        }

        @Override
        public String getDisplayName()
        {
            return I18n.get("layerlist.config.schematicsPreviewQuality." + this.id);
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward)
        {
            PreviewQuality[] all = values();
            int next = (this.ordinal() + (forward ? 1 : all.length - 1)) % all.length;

            return all[next];
        }

        @Override
        public IConfigOptionListEntry fromString(String value)
        {
            for (PreviewQuality quality : values())
            {
                if (quality.id.equalsIgnoreCase(value))
                {
                    return quality;
                }
            }

            // До 0.0.27: «Высокое» ближе всего к честным блокам, «Авто» и «Быстрое» — к прежнему виду
            return "high".equalsIgnoreCase(value) ? QUALITY : OPTIMIZED;
        }
    }

    /** Палитра Google Таблиц (1.0.1): светлая серая по умолчанию, прежняя тёмная — по выбору. */
    public enum SheetsPalette implements IConfigOptionListEntry
    {
        LIGHT_GRAY("light_gray"),
        DARK_VIOLET("dark_violet");

        private final String id;

        SheetsPalette(String id)
        {
            this.id = id;
        }

        @Override
        public String getStringValue()
        {
            return this.id;
        }

        @Override
        public String getDisplayName()
        {
            return I18n.get("layerlist.config.schematicsSheetsPalette." + this.id);
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward)
        {
            SheetsPalette[] all = values();
            int next = (this.ordinal() + (forward ? 1 : all.length - 1)) % all.length;

            return all[next];
        }

        @Override
        public IConfigOptionListEntry fromString(String value)
        {
            for (SheetsPalette palette : values())
            {
                if (palette.id.equalsIgnoreCase(value))
                {
                    return palette;
                }
            }

            return LIGHT_GRAY;
        }
    }
}
