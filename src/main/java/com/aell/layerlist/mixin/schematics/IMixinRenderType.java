package com.aell.layerlist.mixin.schematics;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Настройки типа рендера (конвейер и текстуры) для превью из видеопамяти: в 1.21.11 нет готового «нарисовать из
 * своего буфера», проход собирается как в {@code RenderType.draw}. Аксессором — 1.21.11 обфусцирована.
 */
@Environment(EnvType.CLIENT)
@Mixin(RenderType.class)
public interface IMixinRenderType
{
    @Accessor("state")
    RenderSetup layerlist$state();
}
