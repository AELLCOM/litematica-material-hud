package com.aell.layerlist.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Геометрия окна контейнера — для стыковки панели рядом с ним ({@code hud.HudDock}) — и ячейка под курсором для
 * карточки флага ({@code hud.BannerCard}).
 */
@Mixin(AbstractContainerScreen.class)
public interface IMixinAbstractContainerScreen
{
    @Accessor("leftPos")
    int layerlist_getLeftPos();

    @Accessor("topPos")
    int layerlist_getTopPos();

    @Accessor("imageWidth")
    int layerlist_getImageWidth();

    @Accessor("hoveredSlot")
    net.minecraft.world.inventory.Slot layerlist_getHoveredSlot();
}
