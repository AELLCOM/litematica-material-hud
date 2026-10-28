package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.ghost.PotPlantRender;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.util.OverlayType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.BlockAndTintGetter;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Горшок с цветком строится в два приёма: пустой горшок, потом цветок кликом. Между ними
 * Litematica видит в клетке «не тот блок» — прячет голограмму (клетка занята) и красит горшок
 * красным оверлеем, хотя ломать его не нужно. Здесь такая клетка рисуется как недостроенная:
 * голограмма растения в стоящем горшке ({@link PotPlantRender}) и оверлей «не хватает». Только пустой
 * горшок: горшок с другим цветком — по-прежнему «не тот блок».
 */
@Environment(EnvType.CLIENT)
@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class MixinChunkRendererSchematicPotting
{
    @Shadow
    protected ChunkCacheSchematic clientWorldView;

    // Первое isAir в методе — у состояния мира: «клетка пуста, голограмму рисовать». Для пустого
    // горшка под цветок отвечаем «пуста», и Litematica рисует голограмму, а не прячет её.
    @ModifyExpressionValue(method = "renderBlocksAndOverlay",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z",
                    ordinal = 0),
            require = 0, allow = 1)
    private boolean layerlist$potAwaitsPlant(boolean clientAir,
            @Local(ordinal = 0) BlockState schematic, @Local(ordinal = 1) BlockState client)
    {
        return clientAir || Potting.needsPlant(schematic, client);
    }

    // Рисуется модель «горшок с растением» из схемы, но без граней горшка и земли
    // (PotPlantRender): растение в ней уже стоит в горшке и нужного размера
    @WrapOperation(method = "renderBlocksAndOverlay",
            at = @At(value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/render/IWorldSchematicRenderer;renderBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/BufferBuilder;)Z"),
            require = 0, allow = 1)
    private boolean layerlist$plantWithoutPot(IWorldSchematicRenderer renderer, BlockAndTintGetter world,
            BlockState schematic, BlockPos pos, PoseStack poseStack, BufferBuilder buffer, Operation<Boolean> original)
    {
        if (! Potting.needsPlant(schematic, this.clientWorldView.getBlockState(pos)))
        {
            return original.call(renderer, world, schematic, pos, poseStack, buffer);
        }

        PotPlantRender.begin();

        try
        {
            return original.call(renderer, world, schematic, pos, poseStack, buffer);
        }
        finally
        {
            PotPlantRender.end();
        }
    }

    @Inject(method = "getOverlayType", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$potIsMissingNotWrong(BlockState schematic, BlockState client,
            CallbackInfoReturnable<OverlayType> cir)
    {
        if (Potting.needsPlant(schematic, client))
        {
            cir.setReturnValue(OverlayType.MISSING);
        }
    }
}
