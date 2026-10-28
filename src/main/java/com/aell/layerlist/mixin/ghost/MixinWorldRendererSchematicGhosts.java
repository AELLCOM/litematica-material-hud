package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.GhostSubmits;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Сущности и блок-сущности схемы Litematica рисует в общий приёмник обычным рендером — непрозрачными, как
 * настоящие. Подменяем приёмник обёрткой {@link GhostSubmits}: голограммы становятся полупрозрачными, как блоки.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = WorldRendererSchematic.class, remap = false)
public abstract class MixinWorldRendererSchematicGhosts
{
    @ModifyVariable(method = "renderEntities", at = @At("HEAD"), argsOnly = true, require = 0)
    private SubmitNodeCollector layerlist$ghostEntities(SubmitNodeCollector queue)
    {
        return GhostSubmits.wrap(queue);
    }

    @ModifyVariable(method = "renderBlockEntities", at = @At("HEAD"), argsOnly = true, require = 0)
    private SubmitNodeCollector layerlist$ghostBlockEntities(SubmitNodeCollector queue)
    {
        return GhostSubmits.wrap(queue);
    }
}
