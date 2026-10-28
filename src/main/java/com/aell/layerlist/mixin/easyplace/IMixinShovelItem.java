package com.aell.layerlist.mixin.easyplace;

import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Таблица «что во что утаптывается лопатой»: земля и её родня → тропинка. */
@Environment(EnvType.CLIENT)
@Mixin(ShovelItem.class)
public interface IMixinShovelItem
{
    @Accessor("FLATTENABLES")
    static Map<Block, BlockState> layerlist$flattenables()
    {
        throw new AssertionError();
    }
}
