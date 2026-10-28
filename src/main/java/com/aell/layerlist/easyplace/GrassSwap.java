package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * «Ставить землю, если схема просит дёрн».
 *
 * Дёрн в выживании не добыть без шёлкового касания, и стройка встаёт на первой же клетке
 * газона. Земля ставится вместо него, а дёрн нарастает сам от соседей, если сверху свет, —
 * поэтому клетка с землёй считается готовой и мод к ней больше не возвращается. Настоящий
 * дёрн в инвентаре всегда в приоритете: подменяем только когда его нет.
 *
 * Тумблер выключен по умолчанию: с выключенным всё как раньше — клетка с землёй остаётся
 * недоделанной, и мод будет пытаться поставить туда дёрн.
 */
@Environment(EnvType.CLIENT)
public final class GrassSwap
{
    private GrassSwap() {}

    public static boolean enabled()
    {
        return LayerListConfigs.EP_DIRT_FOR_GRASS.getBooleanValue();
    }

    /** Схема просит дёрн, настоящего дёрна в инвентаре нет — значит годится земля. */
    public static boolean wants(BlockState schematic)
    {
        if (! enabled() || ! schematic.is(Blocks.GRASS_BLOCK))
        {
            return false;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            return true;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
        {
            if (inventory.getItem(slot).is(Items.GRASS_BLOCK))
            {
                return false;
            }
        }

        return true;
    }

    /** В клетке земля там, где схема просит дёрн: работы здесь нет, дёрн нарастёт сам. */
    public static boolean satisfies(BlockState schematic, BlockState world)
    {
        return wants(schematic) && world.is(Blocks.DIRT);
    }
}
