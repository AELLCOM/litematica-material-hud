package com.aell.layerlist.mixin.ghost;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Текстуры {@link RenderSetup} по именам сэмплеров ({@code Sampler0} — основная). */
@Mixin(RenderSetup.class)
public interface IMixinRenderSetup
{
    @Accessor("textures")
    Map<String, ?> layerlist$textures();
}
