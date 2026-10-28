package com.aell.layerlist.easyplace;

import com.aell.layerlist.mixin.easyplace.IMixinGrowingPlantBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Растения, которые строятся клетка за кликом вдоль опоры: светящиеся ягоды,
 * плакучие и скручивающиеся лозы, ламинария, свисающий мох и свисающие корни.
 *
 * У таких растений два подвоха. Во-первых, у «тела» лозы ({@code cave_vines_plant})
 * своего предмета нет вовсе — ставится всегда голова, а верхняя клетка сама
 * превращается в тело, когда под ней появляется следующая. Во-вторых, правый клик
 * по уже висящей лозе снимает ягоды вместо установки, поэтому клик идёт с приседом.
 *
 * Прицелиться в такую голограмму почти невозможно — за это отвечает {@link AirTarget}.
 */
@Environment(EnvType.CLIENT)
public final class GrowingPlants
{
    private GrowingPlants() {}

    public static boolean is(BlockState state)
    {
        return state.getBlock() instanceof GrowingPlantBlock
                || state.getBlock() instanceof HangingMossBlock
                || state.getBlock() instanceof HangingRootsBlock;
    }

    /** Сторона клетки, с которой растение держится: у свисающих — верх, у растущих вверх — низ. */
    public static Direction support(BlockState state)
    {
        if (! (state.getBlock() instanceof GrowingPlantBlock plant))
        {
            return Direction.UP;
        }

        try
        {
            return ((IMixinGrowingPlantBlock) plant).layerlist$growthDirection().getOpposite();
        }
        catch (RuntimeException | LinkageError e)
        {
            return hangsDown(plant) ? Direction.UP : Direction.DOWN;
        }
    }

    /**
     * Клетка уже «своя» для этой колонны, клик ей не нужен. Голова, стоящая там, где
     * схема просит тело, — норма: она превратится в тело сама, когда встанет следующая
     * клетка ниже. У мха и корней так же сам обновляется кончик (tip). Ягоды и возраст
     * кликом не задаются вовсе. Считать такую клетку «недоделанной» вредно: Easy Place
     * бился бы в неё бесконечно, а настоящая работа — в пустой клетке дальше по колонне.
     */
    public static boolean satisfies(BlockState schematic, BlockState world)
    {
        if (world.isAir() || ! is(schematic))
        {
            return false;
        }

        if (schematic.getBlock() instanceof GrowingPlantBlock)
        {
            return world.getBlock() instanceof GrowingPlantBlock
                    && headBlock(schematic) == headBlock(world);
        }

        return world.is(schematic.getBlock());
    }

    /**
     * Для списка материалов: в мире стоит то же растение, что в схеме. Голова на месте тела
     * и тело на месте головы — норма (колонна достраивается или растёт дальше), возраст не
     * важен: при установке он случайный. Важны две вещи, которые теперь доводит Easy Place:
     * ягоды (костной мукой, {@link #berries}) и обрезанный кончик — голова с наибольшим
     * возрастом, которая дальше не растёт (ножницами, {@link #cut}).
     */
    public static boolean sameForList(BlockState schematic, BlockState world)
    {
        if (! sameFamily(schematic, world))
        {
            return false;
        }

        if (berries(schematic) != berries(world))
        {
            return false;
        }

        return ! (cut(schematic) && world.getBlock() instanceof GrowingPlantHeadBlock && ! cut(world));
    }

    /** Клетка растения пуста, а опоры для него ещё нет — ставить нечего, пока не построят выше (ниже). */
    public static boolean awaitingSupport(net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos pos,
            BlockState schematic, BlockState world)
    {
        return is(schematic) && world.canBeReplaced() && ! placed(schematic).canSurvive(level, pos);
    }

    /** Одна и та же лоза: голова и тело одного растения считаются одним. */
    public static boolean sameFamily(BlockState schematic, BlockState world)
    {
        return schematic.getBlock() instanceof GrowingPlantBlock && world.getBlock() instanceof GrowingPlantBlock
                && headBlock(schematic) != null && headBlock(schematic) == headBlock(world);
    }

    /** Ягоды на светящейся лозе (голова и тело — оба несут свойство). */
    public static boolean berries(BlockState state)
    {
        return state.hasProperty(CaveVines.BERRIES) && state.getValue(CaveVines.BERRIES);
    }

    /** Обрезанный кончик: голова с наибольшим возрастом — так её делают ножницы, дальше она не растёт. */
    public static boolean cut(BlockState state)
    {
        return state.getBlock() instanceof GrowingPlantHeadBlock head && head.isMaxAge(state);
    }

    /** Состояние, которое реально появится от клика: у «тела» лозы это голова. */
    public static BlockState placed(BlockState schematic)
    {
        Block head = headBlock(schematic);

        return head == null ? schematic : head.defaultBlockState();
    }

    /** Предмет, которым эта клетка ставится, или null, если такого предмета нет. */
    public static @Nullable Item item(BlockState schematic)
    {
        Block head = headBlock(schematic);
        Item item = (head == null ? schematic.getBlock() : head).asItem();

        return item == Items.AIR ? null : item;
    }

    private static @Nullable Block headBlock(BlockState schematic)
    {
        if (! (schematic.getBlock() instanceof GrowingPlantBlock plant))
        {
            return null;
        }

        try
        {
            return ((IMixinGrowingPlantBlock) plant).layerlist$getHeadBlock();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    private static boolean hangsDown(GrowingPlantBlock plant)
    {
        return plant instanceof CaveVinesBlock || plant instanceof CaveVinesPlantBlock
                || plant instanceof WeepingVinesBlock || plant instanceof WeepingVinesPlantBlock;
    }
}
