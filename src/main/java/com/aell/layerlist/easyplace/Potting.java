package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Горшок с растением строится в два приёма: сначала ставится пустой горшок,
 * потом в него правым кликом сажают растение. Предмета у {@code potted_dandelion}
 * не существует вовсе, а {@code getCloneItemStack} у непустого горшка отдаёт его
 * содержимое, поэтому Litematica просит для такой клетки одуванчик — и Easy Place
 * сажает его в землю вместо горшка.
 *
 * Когда же горшок уже стоит, клетка занята, и штатная проверка на заменяемость
 * ({@code canPlaceBlock}) гасит позицию целиком: посадить в него нечего.
 */
@Environment(EnvType.CLIENT)
public final class Potting
{
    private Potting() {}

    /** Предмет растения, которое схема просит в горшке, иначе null (пустой горшок — тоже null). */
    public static @Nullable Item plant(BlockState schematic)
    {
        if (! (schematic.getBlock() instanceof FlowerPotBlock pot))
        {
            return null;
        }

        Item item = pot.getPotted().asItem();

        return item == Items.AIR ? null : item;
    }

    /**
     * Состояние растения, которое схема просит в горшке, иначе null. Им считается и рисуется
     * клетка, где горшок уже стоит: горшок готов, недостаёт только растения.
     */
    public static @Nullable BlockState plantState(BlockState schematic)
    {
        return schematic.getBlock() instanceof FlowerPotBlock pot && plant(schematic) != null
                ? pot.getPotted().defaultBlockState() : null;
    }

    /** Стоит пустой горшок, а схема просит горшок с растением. */
    public static boolean needsPlant(BlockState schematic, BlockState world)
    {
        return world.is(Blocks.FLOWER_POT) && plant(schematic) != null;
    }
}
