package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.LeanInnerSides;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(value = FluidModelRendererSchematic.class, remap = false)
public abstract class MixinFluidModelRendererSchematic
{
    // «Внутренние грани» принудительно рисуют верх/низ жидкости даже между двумя слоями одной
    // и той же жидкости — такие грани не видны и в полупрозрачном режиме. В экономном варианте
    // принудительной остаётся только грань к ДРУГОМУ содержимому (блок, другая жидкость).
    @Inject(method = "shouldRenderTopFace", at = @At("RETURN"), cancellable = true, require = 0)
    private void layerlist$leanTop(FluidState fluidState, BlockPos pos, FluidState fluidStateUp,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (! cir.getReturnValueZ() || ! LeanInnerSides.active()
                || ! fluidStateUp.getType().isSame(fluidState.getType())
                || DataManager.getRenderLayerRange().isPositionAtRenderEdgeOnSide(pos, Direction.UP))
        {
            return;
        }

        cir.setReturnValue(false);
    }

    @Inject(method = "shouldRenderBottomFace", at = @At("RETURN"), cancellable = true, require = 0)
    private void layerlist$leanBottom(FluidState fluidState, BlockState blockState, BlockPos pos,
            FluidState fluidStateDown, BlockState blockStateDown, CallbackInfoReturnable<Boolean> cir)
    {
        if (! cir.getReturnValueZ() || ! LeanInnerSides.active()
                || ! fluidStateDown.getType().isSame(fluidState.getType())
                || DataManager.getRenderLayerRange().isPositionAtRenderEdgeOnSide(pos, Direction.DOWN))
        {
            return;
        }

        cir.setReturnValue(false);
    }
}
