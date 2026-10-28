package com.aell.layerlist.easyplace;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Автозаполнение полок по схеме — тем же способом, каким их заполняет игрок.
 *
 * Резная книжная полка: шесть слотов — это шесть свойств состояния
 * ({@code SLOT_0..5_OCCUPIED}), а какая именно книга лежит в слоте, знает только
 * block entity схемы. Книга вставляется кликом по грани {@code FACING}: колонка —
 * треть грани по горизонтали, ряд — половина по вертикали (геометрия
 * {@code SelectableSlotContainer.getHitSlot}). Клик пустой рукой ВЫНИМАЕТ книгу,
 * поэтому кликаем только с книгой в руке и только по пустым слотам.
 *
 * Полка с предметами: три слота по третям грани {@code FACING}, содержимое живёт
 * только в block entity (у мира оно синхронизируется — полка рендерит предметы).
 * Клик предметом кладёт его в слот, при занятом слоте — меняет местами, так что
 * неверный предмет исправляется одним кликом. Запитанную редстоуном полку не
 * трогаем: у неё клик обменивает весь хотбар.
 */
@Environment(EnvType.CLIENT)
public final class ShelfFill
{
    private ShelfFill() {}

    public static boolean handles(BlockState schematic)
    {
        return schematic.getBlock() instanceof ChiseledBookShelfBlock
                || schematic.getBlock() instanceof ShelfBlock;
    }

    public static @Nullable StateFix.Fix plan(Level level, @Nullable Level schematicWorld,
            BlockPos pos, BlockState schematic, BlockState world)
    {
        if (schematicWorld == null || ! world.is(schematic.getBlock()))
        {
            return null;
        }

        // Полка стоит не тем боком: наполнение слотов поворот не исправит, а клик по такой
        // полке уводит установку на клетку за гранью. Отказываемся от плана — дальше по
        // конвейеру клетка честно попадёт в «кликом не повернуть, нужно сломать».
        if (schematic.hasProperty(net.minecraft.world.level.block.state.properties
                .BlockStateProperties.HORIZONTAL_FACING)
                && world.hasProperty(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.HORIZONTAL_FACING)
                && schematic.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.HORIZONTAL_FACING)
                        != world.getValue(net.minecraft.world.level.block.state.properties
                                .BlockStateProperties.HORIZONTAL_FACING))
        {
            return null;
        }

        try
        {
            if (schematic.getBlock() instanceof ChiseledBookShelfBlock)
            {
                return bookshelf(schematicWorld, pos, schematic, world);
            }

            if (schematic.getBlock() instanceof ShelfBlock)
            {
                return shelf(level, schematicWorld, pos, world);
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }

        return null;
    }

    private static @Nullable StateFix.Fix bookshelf(Level schematicWorld, BlockPos pos,
            BlockState schematic, BlockState world)
    {
        Direction facing = world.getValue(ChiseledBookShelfBlock.FACING);
        List<ItemStack> wantedBooks = items(schematicWorld.getBlockEntity(pos), 6);

        for (int slot = 0; slot < 6; ++slot)
        {
            BooleanProperty occupied = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot);

            if (! schematic.getValue(occupied) || world.getValue(occupied))
            {
                continue;
            }

            ItemStack book = wantedBooks.get(slot);
            Item item = book.isEmpty() ? Items.BOOK : book.getItem();

            return new StateFix.Fix("book", StateFix.Path.TOOL, stack -> stack.is(item),
                    false, false, null, slotSpot(pos, facing, slot % 3, slot / 3, 2), facing);
        }

        return null;
    }

    private static @Nullable StateFix.Fix shelf(Level level, Level schematicWorld, BlockPos pos,
            BlockState world)
    {
        // Запитанная полка кладёт не по предмету, а меняет всю цепочку с хотбаром
        if (world.getValue(ShelfBlock.POWERED))
        {
            return ShelfSwap.plan(level, schematicWorld, pos, world);
        }

        Direction facing = world.getValue(ShelfBlock.FACING);
        List<ItemStack> wanted = items(schematicWorld.getBlockEntity(pos), 3);
        List<ItemStack> placed = items(level.getBlockEntity(pos), 3);

        for (int slot = 0; slot < 3; ++slot)
        {
            ItemStack want = wanted.get(slot);

            if (want.isEmpty() || placed.get(slot).is(want.getItem()))
            {
                continue;
            }

            Item item = want.getItem();

            // Полка забирает из руки всю стопку — в руку кладём ровно нужное (ShelfSwap.runSingle)
            return new StateFix.Fix("shelf", StateFix.Path.SHELF_SINGLE, stack -> stack.is(item),
                    false, false, null, slotSpot(pos, facing, slot, 0, 1), facing);
        }

        return null;
    }

    static List<ItemStack> items(@Nullable BlockEntity entity, int size)
    {
        List<ItemStack> stored = switch (entity)
        {
            case ChiseledBookShelfBlockEntity shelf -> shelf.getItems();
            case ShelfBlockEntity shelf -> shelf.getItems();
            case null, default -> null;
        };

        if (stored == null || stored.size() < size)
        {
            return java.util.Collections.nCopies(size, ItemStack.EMPTY);
        }

        return stored;
    }

    /**
     * Центр под-прямоугольника слота на грани {@code facing} — обратное отображение
     * геометрии {@code getHitSlot}: сервер по этой точке вычислит тот же слот.
     */
    static Vec3 slotSpot(BlockPos pos, Direction facing, int column, int row, int rows)
    {
        double u = (column + 0.5D) / 3.0D;
        double v = rows == 1 ? 0.5D : row == 0 ? 0.75D : 0.25D;
        double y = pos.getY() + v;

        return switch (facing)
        {
            case SOUTH -> new Vec3(pos.getX() + u, y, pos.getZ() + 1.0D);
            case WEST -> new Vec3(pos.getX(), y, pos.getZ() + u);
            case EAST -> new Vec3(pos.getX() + 1.0D, y, pos.getZ() + 1.0D - u);
            default -> new Vec3(pos.getX() + 1.0D - u, y, pos.getZ());
        };
    }
}
