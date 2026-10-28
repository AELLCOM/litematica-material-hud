package com.aell.layerlist.mixin.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Куда растёт лоза и какой блок у неё «головной»: и то и другое в ванили скрыто,
 * а модулю нужно, чтобы кликать по клетке с той стороны, где у растения опора,
 * и требовать предмет головы для клеток «тела».
 */
@Environment(EnvType.CLIENT)
@Mixin(GrowingPlantBlock.class)
public interface IMixinGrowingPlantBlock
{
    @Accessor("growthDirection")
    Direction layerlist$growthDirection();

    @Invoker("getHeadBlock")
    GrowingPlantHeadBlock layerlist$getHeadBlock();
}
