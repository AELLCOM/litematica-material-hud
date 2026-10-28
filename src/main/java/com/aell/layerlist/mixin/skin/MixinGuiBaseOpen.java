package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.OwnScreens;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Экраны Litematica «Размещённые голограммы», «Загруженные схемы» и «Загрузить схему» — свои вкладки «Схем»
 * ({@link OwnScreens}), откуда бы их ни открывали (кнопка меню, хоткей, «Назад» из настройки размещения).
 * {@code require = 0}.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = GuiBase.class, remap = false)
public abstract class MixinGuiBaseOpen
{
    @Inject(method = "openGui", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$ownScreens(Screen gui, CallbackInfo ci)
    {
        if (OwnScreens.redirect(gui))
        {
            ci.cancel();
        }
    }
}
