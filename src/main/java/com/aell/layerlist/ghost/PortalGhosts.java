package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * С 14.11.2026, опция «Скрывать порталы» (вкладка Ghost Alpha): голограммы рамок портала Края и самих порталов
 * (Нижнего мира, Края, шлюза Края) не рисуются — ни модель, ни оверлей, а прицел проходит такую клетку насквозь,
 * как у других фильтров {@link HiddenGhosts}. Проверка Ctrl+B с 25.11.2026 такие клетки тоже не отмечает
 * (рамок голограммы нет — нет и рамок проверки). Список материалов их по-прежнему видит.
 * Рабочие потоки сборки и клиентский поток: только чтение опции и блока.
 */
@Environment(EnvType.CLIENT)
public final class PortalGhosts
{
    private PortalGhosts() {}

    public static boolean active()
    {
        return LayerListConfigs.GHOST_HIDE_PORTALS.getBooleanValue();
    }

    public static boolean portal(BlockState state)
    {
        Block block = state.getBlock();
        return block == Blocks.END_PORTAL_FRAME || block == Blocks.NETHER_PORTAL
                || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY;
    }

    /** Опция включена, и в клетке схемы портал или его рамка. */
    public static boolean hides(BlockState schematic)
    {
        return active() && portal(schematic);
    }
}
