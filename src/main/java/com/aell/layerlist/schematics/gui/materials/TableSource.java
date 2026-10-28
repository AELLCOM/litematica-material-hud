package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Что показывает таблица «Материалы»: файл схемы из каталога, размещение Litematica (его схема —
 * тот самый объект, с которым работает Litematica) или список HUD. Это выбор игрока, ещё не
 * загруженный: загрузку и счёт делает {@link MaterialsJob}.
 */
@Environment(EnvType.CLIENT)
public sealed interface TableSource permits TableSource.File, TableSource.Placed, TableSource.Hud, TableSource.Area
{
    /** Имя для «Схема: &lt;имя&gt;» и заголовка выгрузки; у списка HUD — пусто. */
    String name();

    /** Файл схемы в каталоге (для окна выгрузки); {@code null} — список HUD или размещение без файла. */
    @Nullable SchematicEntry entry();

    /** Источник — схема (файл или размещение), а не список HUD. */
    default boolean schematic()
    {
        return ! (this instanceof Hud);
    }

    /** Файл из «Библиотеки». */
    record File(SchematicEntry entry) implements TableSource
    {
        public File
        {
            Objects.requireNonNull(entry, "entry");
        }

        @Override
        public String name()
        {
            return this.entry.name();
        }
    }

    /** Размещение из «Размещений» (или карточки схемы). */
    record Placed(SchematicPlacement placement) implements TableSource
    {
        public Placed
        {
            Objects.requireNonNull(placement, "placement");
        }

        @Override
        public String name()
        {
            try
            {
                return Objects.requireNonNullElse(this.placement.getName(), "");
            }
            catch (RuntimeException e)
            {
                return "";
            }
        }

        @Override
        public @Nullable SchematicEntry entry()
        {
            try
            {
                Path file = this.placement.getSchematicFile();
                return file == null ? null : SchematicLibrary.get().entry(file);
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }
    }

    /**
     * Блоки выделенной области мира (анализ на вкладке «Выделение»), строки уже посчитаны. Не схема: замен, отключения
     * и слоёв нет.
     */
    record Area(String name, List<MaterialRow> rows) implements TableSource
    {
        public Area
        {
            Objects.requireNonNull(name, "name");
            rows = List.copyOf(rows);
        }

        @Override
        public @Nullable SchematicEntry entry()
        {
            return null;
        }

        @Override
        public boolean schematic()
        {
            return false;
        }
    }

    /** Опубликованный список HUD: недостающее в мире для включённых размещений. */
    record Hud() implements TableSource
    {
        @Override
        public String name()
        {
            return "";
        }

        @Override
        public @Nullable SchematicEntry entry()
        {
            return null;
        }
    }
}
