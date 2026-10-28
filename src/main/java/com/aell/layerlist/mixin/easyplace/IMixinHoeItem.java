package com.aell.layerlist.mixin.easyplace;

import com.mojang.datafixers.util.Pair;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Таблица «что вспахивается мотыгой»; нужна только как проверка «этот блок вспахиваем». */
@Environment(EnvType.CLIENT)
@Mixin(HoeItem.class)
public interface IMixinHoeItem
{
    @Accessor("TILLABLES")
    static Map<Block, Pair<Predicate<UseOnContext>, Consumer<UseOnContext>>> layerlist$tillables()
    {
        throw new AssertionError();
    }
}
