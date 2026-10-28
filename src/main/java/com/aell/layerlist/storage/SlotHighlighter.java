package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@Environment(EnvType.CLIENT)
public class SlotHighlighter
{
    public static void render(GuiContext ctx, AbstractContainerScreen<?> screen)
    {
        if (! LayerListConfigs.HIGHLIGHT_IN_CONTAINER.getBooleanValue()
                || StorageSearch.getInstance().isEmpty())
        {
            return;
        }

        StorageSearch search = StorageSearch.getInstance();

        for (Slot slot : screen.getMenu().slots)
        {
            if (! slot.hasItem())
            {
                continue;
            }

            ItemStack stack = slot.getItem();
            Integer color = search.colorFor(stack);

            if (color != null)
            {
                int fill = (0x88 << 24) | (color & 0xFFFFFF);
                RenderUtils.drawRect(ctx, slot.x, slot.y, 16, 16, fill);
                RenderUtils.drawOutline(ctx, slot.x - 1, slot.y - 1, 18, 18, 1, color);
                continue;
            }

            Integer nestedColor = colorInsideContainerItem(search, stack);

            if (nestedColor != null)
            {
                RenderUtils.drawOutline(ctx, slot.x - 1, slot.y - 1, 18, 18, 1, nestedColor);
            }
        }
    }

    /** Цвет первого искомого предмета внутри шалкера или мешочка, вложенные тоже ({@link StorageIndex#forEachInside}). */
    private static Integer colorInsideContainerItem(StorageSearch search, ItemStack stack)
    {
        Integer[] found = new Integer[1];
        StorageIndex.forEachInside(stack, inner -> {
            if (found[0] == null)
            {
                found[0] = search.colorFor(inner);
            }
        });
        return found[0];
    }
}
