package com.aell.layerlist.schematics.library;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Схема, загруженная самой Litematica (её объект — то, с чем работают размещение и список
 * материалов), вместе с файлом каталога, из которого она пришла. Создаётся только на клиентском
 * потоке — так грузит и сама Litematica.
 */
@Environment(EnvType.CLIENT)
public final class LoadedSchematic
{
    private final SchematicEntry entry;
    private final LitematicaSchematic litematica;

    public LoadedSchematic(SchematicEntry entry, LitematicaSchematic litematica)
    {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.litematica = Objects.requireNonNull(litematica, "litematica");
    }

    public LitematicaSchematic litematica()
    {
        return this.litematica;
    }

    public SchematicEntry entry()
    {
        return this.entry;
    }
}
