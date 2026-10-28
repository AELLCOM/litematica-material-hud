package com.aell.layerlist.mixin.schematics;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Путь файла у размещения Litematica {@code final}, а при переименовании схемы в «Библиотеке» его надо
 * сменить: размещение пишется в мир с этим путём, и со старым при следующем входе его схема не нашлась бы.
 */
@Mixin(value = SchematicPlacement.class, remap = false)
public interface IMixinSchematicPlacementFile
{
    @Mutable
    @Accessor("schematicFile")
    void layerlist$setSchematicFile(Path file);
}
