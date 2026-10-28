package com.aell.layerlist.mixin.schematics;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Путь файла у загруженной схемы Litematica {@code final}; переименование в «Библиотеке» меняет его, чтобы
 * {@code SchematicHolder.getOrLoad} и наш загрузчик узнавали схему под новым именем, а не читали её второй раз.
 */
@Mixin(value = LitematicaSchematic.class, remap = false)
public interface IMixinLitematicaSchematicFile
{
    @Mutable
    @Accessor("schematicFile")
    void layerlist$setSchematicFile(Path file);
}
