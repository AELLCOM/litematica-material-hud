package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.gui.export.ExportNames;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementOps;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Какую схему покажет «Схема» вкладки «Материалы», когда в «Библиотеке» ничего не выбрано (0.0.27; игрок с
 * одним размещением видел только «Список HUD» — «Схема» была серой). Кандидаты — размещения мира (выбранное в
 * Litematica — первым) и загруженные в Litematica схемы-файлы без размещений. Одна схема определяется сама:
 * выбранное в Litematica размещение, иначе единственный кандидат; несколько — выбирает игрок из списка.
 */
@Environment(EnvType.CLIENT)
public final class SchematicChoice
{
    /** Пункт выбора: что показать и как подписать. */
    public record Option(TableSource source, String label)
    {
        public Option
        {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(label, "label");
        }
    }

    private SchematicChoice() {}

    /** Все кандидаты по порядку. Клиентский поток; файлы загруженных схем читаются с диска — не каждый кадр. */
    public static List<Option> options()
    {
        List<Option> out = new ArrayList<>();
        Set<Path> files = new HashSet<>();
        SchematicPlacement selected = selectedPlacement();
        List<SchematicPlacement> placements = new ArrayList<>(PlacementOps.all());

        if (selected != null && placements.remove(selected))
        {
            placements.addFirst(selected);
        }

        for (SchematicPlacement placement : placements)
        {
            out.add(new Option(new TableSource.Placed(placement), label(placement)));
            Path file = placement.getSchematicFile();

            if (file != null)
            {
                files.add(file.toAbsolutePath().normalize());
            }
        }

        try
        {
            for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics())
            {
                Path file = schematic.getFile();

                // Схема без файла (из памяти) в «Материалах» открывается только через своё размещение
                if (file == null || ! files.add(file.toAbsolutePath().normalize()))
                {
                    continue;
                }

                SchematicEntry entry = SchematicLibrary.get().entry(file);

                if (entry != null)
                {
                    out.add(new Option(new TableSource.File(entry), entry.name()));
                }
            }
        }
        catch (RuntimeException e)
        {
            // Список загруженных схем Litematica не прочитался — остаются размещения
        }

        return out;
    }

    /** Схема без вопросов: выбранное в Litematica размещение, иначе единственный кандидат; {@code null} — нет одной. */
    public static @Nullable TableSource single(List<Option> options)
    {
        SchematicPlacement selected = selectedPlacement();

        if (selected != null && PlacementOps.all().contains(selected))
        {
            return new TableSource.Placed(selected);
        }

        return options.size() == 1 ? options.getFirst().source() : null;
    }

    private static @Nullable SchematicPlacement selectedPlacement()
    {
        try
        {
            return DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** «Имя размещения · схема», если игрок переименовал размещение; иначе одно имя. */
    private static String label(SchematicPlacement placement)
    {
        String name = Objects.requireNonNullElse(placement.getName(), "").strip();
        String schematic = ExportNames.of(placement).strip();

        if (name.isEmpty())
        {
            return schematic;
        }

        return schematic.isEmpty() || schematic.equals(name) ? name : name + " · " + schematic;
    }
}
