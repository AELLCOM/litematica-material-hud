package com.aell.layerlist.mixin.easyplace;

import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Таблица «что обтёсывается топором»: бревно → обтёсанное бревно. */
@Environment(EnvType.CLIENT)
@Mixin(AxeItem.class)
public interface IMixinAxeItem
{
    @Accessor("STRIPPABLES")
    static Map<Block, Block> layerlist$strippables()
    {
        throw new AssertionError();
    }
}
