package com.aell.layerlist.mixin.skin;

import com.aell.layerlist.skin.OwnScreens;
import fi.dy.masa.litematica.selection.SelectionManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Редактор региона» Litematica (кнопка меню и хоткей) — своя вкладка «Выделение», если включена опция
 * {@code litematicaOwnScreens} ({@link OwnScreens}). {@code require = 0}: переименуют метод — откроется редактор
 * Litematica.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = SelectionManager.class, remap = false)
public abstract class MixinSelectionManagerOpen
{
    @Inject(method = "openEditGui", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$ownAreaEditor(Screen parent, CallbackInfo ci)
    {
        if (OwnScreens.openAreaEditor(parent))
        {
            ci.cancel();
        }
    }
}
