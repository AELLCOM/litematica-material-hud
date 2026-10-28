package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.LeanInnerSides;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(value = BlockModelRendererSchematic.class, remap = false)
public abstract class MixinBlockModelRendererSchematic
{
    @Shadow
    private boolean useCulling;

    // Растение над стоящим горшком: грани горшка и земли из модели «горшок с растением» не выводим
    @Inject(method = "tessellateQuad", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skipPotQuads(BlockAndTintGetter world, BlockState state, BlockPos pos,
            net.minecraft.client.resources.model.geometry.BakedQuad quad, fi.dy.masa.malilib.util.position.Vec3f offset,
            fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic out,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci)
    {
        if (com.aell.layerlist.ghost.PotPlantRender.skips(quad))
        {
            ci.cancel();
        }
    }

    // Режим «внутренние грани» делает shouldRenderModelSide безусловно истинным. В экономном
    // варианте принудительная грань остаётся только между РАЗНЫМИ блоками; между двумя
    // одинаковыми возвращается ванильное отсечение — внутренность однородного массива
    // (заливка, стена, грунт) не мешится вовсе.
    @Inject(method = "shouldRenderModelSide", at = @At("RETURN"), cancellable = true, require = 0)
    private void layerlist$leanInnerSides(BlockAndTintGetter worldIn, BlockState stateIn,
            BlockPos posIn, Direction face, BlockPos neighbor, CallbackInfoReturnable<Boolean> cir)
    {
        // Сосед отсёк грань, но сам не рисуется («только из инвентаря», «без опоры») — грань видна
        if (! cir.getReturnValueZ())
        {
            if (com.aell.layerlist.ghost.HiddenGhosts.any()
                    && com.aell.layerlist.ghost.HiddenGhosts.hiddenNeighbor(worldIn, neighbor))
            {
                cir.setReturnValue(true);
            }

            return;
        }

        if (! this.useCulling || ! LeanInnerSides.active())
        {
            return;
        }

        BlockState neighborState = worldIn.getBlockState(neighbor);

        if (neighborState != stateIn
                || DataManager.getRenderLayerRange().isPositionAtRenderEdgeOnSide(posIn, face))
        {
            return;
        }

        cir.setReturnValue(Block.shouldRenderFace(stateIn, neighborState, face)
                || (com.aell.layerlist.ghost.HiddenGhosts.any()
                        && com.aell.layerlist.ghost.HiddenGhosts.hiddenNeighbor(worldIn, neighbor)));
    }
}
