package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Соединения с соседями: свойства, которые блок получает не от клика, а от окружения.
 *
 * Забор, ограда, стена, панель, решётка, хоруc и провод хранят в состоянии то, с какими
 * соседями они срослись. Установка эти свойства не задаёт никак: ванильный
 * {@code updateShape} досчитывает их по соседям сразу после установки и переписывает
 * заново каждый раз, когда сосед появляется или исчезает.
 *
 * Для Easy Place это значит, что сверка «состояние в мире == состояние в схеме» на таких
 * блоках почти всегда ложно-отрицательная: пока сосед по линии не построен, стоящий забор
 * отличается от схемы одним-двумя соединениями — и выглядит как недоделанная клетка,
 * в которую надо кликать ещё. Кликать туда нельзя: клетка занята, и ванильный
 * {@code BlockPlaceContext} уводит установку на клетку за гранью клика — над забором
 * вырастает лишний.
 */
@Environment(EnvType.CLIENT)
public final class Connections
{
    private Connections() {}

    /**
     * Блок уже стоит, и вся разница со схемой — только в соединениях с соседями.
     * Работы в клетке нет: соединения досчитаются сами, когда встанут соседи.
     */
    public static boolean onlyConnectionsDiffer(BlockState wanted, BlockState world)
    {
        if (wanted == world || wanted.isAir() || ! world.is(wanted.getBlock()))
        {
            return false;
        }

        // Лишайник, жилы и лозы держат в тех же свойствах не соединения, а грани, на
        // которых блок сидит: их задаёт как раз клик, и каждой нужен свой
        if (wanted.getBlock() instanceof MultifaceBlock || wanted.getBlock() instanceof VineBlock)
        {
            return false;
        }

        boolean differs = false;

        for (Property<?> property : wanted.getBlock().getStateDefinition().getProperties())
        {
            if (same(wanted, world, property))
            {
                continue;
            }

            if (! connection(property))
            {
                return false;
            }

            differs = true;
        }

        return differs;
    }

    private static boolean connection(Property<?> property)
    {
        return Controllable.side(property) || property.equals(BlockStateProperties.IN_WALL);
    }

    private static <T extends Comparable<T>> boolean same(BlockState a, BlockState b,
            Property<T> property)
    {
        if (! a.hasProperty(property) || ! b.hasProperty(property))
        {
            return true;
        }

        return a.getValue(property).equals(b.getValue(property));
    }
}
