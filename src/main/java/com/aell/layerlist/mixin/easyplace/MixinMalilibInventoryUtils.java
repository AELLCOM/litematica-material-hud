package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.EpDebug;
import com.aell.layerlist.easyplace.InventorySync;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Обмен предмета из рюкзака в руку (pick block Litematica в Easy Place) не идёт сразу после установки: ответ
 * сервера на неё ещё в пути и перезаписал бы руку после обмена — так на сервере вставал не тот блок
 * ({@link InventorySync}). Отказ — Litematica в этот тик не ставит (предмета в руке нет) и повторит позже.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = InventoryUtils.class, remap = false)
public abstract class MixinMalilibInventoryUtils
{
    @Inject(method = "swapItemToMainHand", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$waitForServer(ItemStack stack, Minecraft mc, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (EasyPlaceModule.isActive() && AutoPlaceState.isAutomated(LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue())
                    && InventorySync.swapTooSoon())
            {
                ++InventorySync.swapWaits;
                EpDebug.say("обмен " + stack.getItem() + " в руку ждёт: ответ сервера на прошлую установку ещё в пути");
                cir.setReturnValue(false);
                return;
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // Сбой проверки не должен мешать обмену
        }

        InventorySync.ownSwap(true);
    }

    @Inject(method = "swapItemToMainHand", at = @At("RETURN"), require = 0)
    private static void layerlist$swapDone(ItemStack stack, Minecraft mc, CallbackInfoReturnable<Boolean> cir)
    {
        InventorySync.ownSwap(false);
    }
}
