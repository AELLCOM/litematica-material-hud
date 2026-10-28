package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.materials.BuildSpot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Запомнить клетку, куда игрок поставил блок, — «слой, на котором строил» для «Отключить на слое…». Только
 * клиентский мир (в одиночке тот же метод зовёт и встроенный сервер). Косметика — {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(BlockItem.class)
public abstract class MixinBlockItemBuildSpot
{
    @Inject(method = "place", at = @At("RETURN"), require = 0)
    private void layerlist$rememberBuildSpot(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir)
    {
        if (context.getLevel().isClientSide() && cir.getReturnValue().consumesAction())
        {
            BuildSpot.placed(context.getClickedPos());
        }
    }
}
