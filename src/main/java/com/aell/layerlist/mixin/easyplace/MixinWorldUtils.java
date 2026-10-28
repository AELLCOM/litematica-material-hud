package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.EntityPlace;
import com.aell.layerlist.easyplace.SoilDig;
import com.aell.layerlist.easyplace.StateRepair;
import fi.dy.masa.litematica.util.WorldUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(value = WorldUtils.class, remap = false)
public abstract class MixinWorldUtils
{
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$enter(CallbackInfoReturnable<InteractionResult> cir)
    {
        // Модуль выключен (свой тумблер или standalone-мод рядом) — в обработчик не заходим:
        // иначе inHandler() поднимается и прицельные миксины начинают править чужую установку
        if (! EasyPlaceModule.isActive())
        {
            return;
        }

        AutoPlaceState.enter();

        boolean repaired;

        try
        {
            // Голограмма сущности под прицелом (стойка, подушка, рамка…) — раньше блоков:
            // трассировка Litematica сущностей схемы не видит вовсе
            repaired = EntityPlace.run() || StateRepair.run() || SoilDig.run();
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        if (repaired)
        {
            AutoPlaceState.exit();
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), require = 0)
    private static void layerlist$exit(CallbackInfoReturnable<InteractionResult> cir)
    {
        AutoPlaceState.exit();
    }
}
