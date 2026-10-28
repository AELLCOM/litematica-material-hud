package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.HiddenGhosts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Луч Litematica по схеме (прицел Easy Place, выбор блока, «самый дальний блок перед ванильным») идёт
 * сквозь голограммы, которые мод не рисует: «только блоки из инвентаря» и «скрывать блоки без опоры».
 * Раньше невидимая клетка на слое ловила луч, и видимую голограмму за ней было не поставить. Только лучи,
 * которые уважают видимый слой ({@code respectLayerRange}), и только по миру схемы.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = RayTraceUtils.class, remap = false)
public abstract class MixinRayTraceUtilsHidden
{
    @WrapOperation(method = {"rayTraceBlocks", "rayTraceBlocksToList"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private static BlockState layerlist$passHiddenBlock(Level world, BlockPos pos, Operation<BlockState> original,
            @Local(argsOnly = true, ordinal = 2) boolean respectLayerRange)
    {
        BlockState state = original.call(world, pos);
        return respectLayerRange && ! state.isAir() && world instanceof WorldSchematic && HiddenGhosts.hidden(pos)
                ? Blocks.AIR.defaultBlockState() : state;
    }

    @WrapOperation(method = {"rayTraceBlocks", "rayTraceBlocksToList"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            require = 0)
    private static FluidState layerlist$passHiddenFluid(Level world, BlockPos pos, Operation<FluidState> original,
            @Local(argsOnly = true, ordinal = 2) boolean respectLayerRange)
    {
        FluidState state = original.call(world, pos);
        return respectLayerRange && world instanceof WorldSchematic && ! state.isEmpty() && HiddenGhosts.hidden(pos)
                ? Fluids.EMPTY.defaultFluidState() : state;
    }
}
