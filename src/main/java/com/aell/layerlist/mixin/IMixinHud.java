package com.aell.layerlist.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Строка над хотбаром: что в ней сейчас и сколько ей осталось — чтобы подсказка режима выбора сундуков
 * ({@code storage.ChestPicker}) не перебивала чужие сообщения, а возвращалась, когда они погасли.
 */
@Mixin(Hud.class)
public interface IMixinHud
{
    @Accessor("overlayMessageString")
    Component layerlist_getOverlayMessage();

    @Accessor("overlayMessageTime")
    int layerlist_getOverlayMessageTime();
}
