package com.aell.layerlist.mixin.ghost;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Привязка текстуры в {@code RenderSetup} — класс закрытый, достаём только адрес текстуры. */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface IMixinTextureBinding
{
    @Accessor("location")
    Identifier layerlist$location();
}
