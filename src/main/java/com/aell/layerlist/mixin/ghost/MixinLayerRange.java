package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.ghost.GhostSupport;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * «Опора с соседнего слоя»: клетка опоры для прицела Litematica (и Easy Place за ним) считается внутри
 * видимого слоя — иначе луч проходит её насквозь и поставить опору нельзя. Остальные перегрузки
 * {@code isPositionWithinRange} зовут эту. Только диапазон рендера, а не чужие копии {@link LayerRange}.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = LayerRange.class, remap = false)
public abstract class MixinLayerRange
{
    @Inject(method = "isPositionWithinRange(III)Z", at = @At("RETURN"), cancellable = true, require = 0)
    private void layerlist$supportCellWithinRange(int x, int y, int z, CallbackInfoReturnable<Boolean> cir)
    {
        if (! cir.getReturnValueZ() && GhostSupport.isSupportCell(x, y, z)
                && (Object) this == DataManager.getRenderLayerRange())
        {
            cir.setReturnValue(true);
        }
    }
}
