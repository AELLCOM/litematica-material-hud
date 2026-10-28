package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Редстоун, рельсы и нить в голограммах (просьба игроков 26.10.2026: «отображение редстоуна»). Их модель — плоскость во
 * всю клетку, на которой узор рисует прозрачная текстура. Заливка оверлея Litematica («блока нет», «не тот блок»…)
 * ложится на ту же плоскость сплошным цветом, без прозрачности, — и вместо дорожек пыли видны голубые квадраты. С
 * опцией {@code ghostFlatNoFill} у таких клеток остаётся только контур оверлея (цвет тот же), а голограмма узора видна.
 * Рабочие потоки сборки чанков: только чтение опции.
 */
@Environment(EnvType.CLIENT)
public final class FlatGhosts
{
    private FlatGhosts() {}

    /** Клетке не рисовать заливку оверлея: опция включена, и блок — плоский узор на полу. */
    public static boolean noFill(BlockState state)
    {
        return LayerListConfigs.GHOST_FLAT_NO_FILL.getBooleanValue() && flat(state);
    }

    static boolean flat(BlockState state)
    {
        return state.getBlock() instanceof RedStoneWireBlock || state.getBlock() instanceof BaseRailBlock
                || state.getBlock() instanceof TripWireBlock;
    }
}
