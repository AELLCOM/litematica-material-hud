package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.LeanInnerSides;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(value = BlockModelRendererSchematic.class, remap = false)
public abstract class MixinBlockModelRendererSchematic
{
    // Растение над стоящим горшком: грани горшка и земли из модели «горшок с растением» не выводим
    // (0.26: каждый квадрат — и плоский, и сглаженный путь — идёт через renderQuad)
    @Inject(method = "renderQuad", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$skipPotQuads(BlockAndTintGetter world, BlockState state, BlockPos pos,
            com.mojang.blaze3d.vertex.VertexConsumer buffer, com.mojang.blaze3d.vertex.PoseStack matrices,
            net.minecraft.client.renderer.block.model.BakedQuad quad,
            fi.dy.masa.litematica.render.schematic.ao.AOLightmap lightmap, int overlay,
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
    private static void layerlist$leanInnerSides(BlockAndTintGetter worldIn, BlockState stateIn,
            BlockPos posIn, Direction face, boolean useCulling, BlockPos neighbor, CallbackInfoReturnable<Boolean> cir)
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

        // В 0.26 shouldRenderModelSide статический, а флаг отсечения приходит аргументом
        if (! useCulling || ! LeanInnerSides.active())
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
