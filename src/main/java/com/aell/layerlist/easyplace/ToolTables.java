package com.aell.layerlist.easyplace;

import com.aell.layerlist.mixin.easyplace.IMixinAxeItem;
import com.aell.layerlist.mixin.easyplace.IMixinHoeItem;
import com.aell.layerlist.mixin.easyplace.IMixinShovelItem;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Таблицы «что во что превращает инструмент» — вариант для 26.2: статические карты
 * ShovelItem / HoeItem / AxeItem читаются аксессорами. (В 26.3 этих классов нет, там
 * таблицы собираются из реестра block_transformer — см. основное дерево.)
 */
@Environment(EnvType.CLIENT)
public final class ToolTables
{
    private ToolTables() {}

    /** Лопата: блок → состояние, которое встанет на его место. */
    public static Map<Block, BlockState> flattenables()
    {
        try
        {
            return IMixinShovelItem.layerlist$flattenables();
        }
        catch (RuntimeException | LinkageError e)
        {
            return Map.of();
        }
    }

    /** Мотыга: вспахивается ли этот блок вообще. */
    public static boolean tillable(Block block)
    {
        try
        {
            return IMixinHoeItem.layerlist$tillables().containsKey(block);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Топор: обычное бревно → обтёсанное. */
    public static Map<Block, Block> strippables()
    {
        try
        {
            return IMixinAxeItem.layerlist$strippables();
        }
        catch (RuntimeException | LinkageError e)
        {
            return Map.of();
        }
    }
}
