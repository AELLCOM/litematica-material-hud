package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Strings;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;

/**
 * Имя файла выгрузки по источнику (0.0.27): файл называется по схеме, а не по названию таблицы и не
 * «Список HUD». Google импортирует файл под его именем — так и таблица в Google называется по схеме.
 * Здесь — только «какая схема»; очистку под Windows и «(2)» для занятого имени делает ядро выгрузок.
 *
 * <ul>
 *   <li>файл из «Библиотеки» — имя, как в списке (без расширения формата);</li>
 *   <li>размещение — имя файла его схемы, а не имя размещения (его игрок мог переименовать); схема без
 *       файла (из памяти Litematica) — имя размещения;</li>
 *   <li>список HUD — схемы включённых размещений: одна — её имя, несколько — «Дом + ещё 2», ни одной —
 *       «Список HUD».</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class ExportNames
{
    private static final String KEY = "layerlist.schematics.export.file.";

    private ExportNames() {}

    /** Файл из «Библиотеки». */
    public static String of(SchematicEntry entry)
    {
        return entry.name();
    }

    /** Схема размещения; пусто — Litematica не отдала ни файла, ни имени. */
    public static String of(SchematicPlacement placement)
    {
        try
        {
            Path file = placement.getSchematicFile();

            if (file != null && file.getFileName() != null)
            {
                return new SchematicEntry(file, false, SchematicFormat.of(file), 0L, 0L).name();
            }

            return Objects.requireNonNullElse(placement.getName(), "");
        }
        catch (RuntimeException e)
        {
            return "";
        }
    }

    /** Список HUD: его считают включённые размещения. Клиентский поток. */
    public static String hud()
    {
        List<String> names = new ArrayList<>();

        for (SchematicPlacement placement : PlacementOps.all())
        {
            if (placement.isEnabled())
            {
                names.add(of(placement));
            }
        }

        return hud(names);
    }

    /** Имя по схемам размещений, по порядку: повторы и пустые не считаются. */
    public static String hud(List<String> names)
    {
        Set<String> distinct = new LinkedHashSet<>();

        for (String name : names)
        {
            String plain = name == null ? "" : name.strip();

            if (! plain.isEmpty())
            {
                distinct.add(plain);
            }
        }

        if (distinct.isEmpty())
        {
            return Strings.plain(KEY + "hud");
        }

        String first = distinct.iterator().next();

        return distinct.size() == 1 ? first : I18n.get(KEY + "more", first, distinct.size() - 1);
    }
}
