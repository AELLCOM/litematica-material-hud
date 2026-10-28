package com.aell.layerlist.mixin.ghost;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Настройки {@link RenderType} — чтобы взять его текстуру для полупрозрачного двойника голограммы. */
@Mixin(RenderType.class)
public interface IMixinRenderType
{
    @Accessor("state")
    RenderSetup layerlist$state();
}
