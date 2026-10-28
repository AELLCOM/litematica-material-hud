package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.jetbrains.annotations.Nullable;

/**
 * Угловые ступени: одна и та же фигура записывается двумя состояниями.
 *
 * <p>Внешний и внутренний угол — это две четверти, сведённые в угол, и у такого угла две
 * «спины»: {@code facing=west, shape=outer_left} и {@code facing=south, shape=outer_right}
 * рисуются одной и той же моделью (у ванильных blockstates у них одинаковый поворот).
 * Схема может хранить любую из двух записей — смотря чем и в каком порядке её строили
 * (Axiom, WorldEdit, вставка без обновления соседей).
 *
 * <p>Установкой задаются только {@code facing} и {@code half}; {@code shape} ступень берёт
 * сама от соседей — спереди и сзади по своей оси. Поэтому если схема записала угол «чужой»
 * спиной, ступень, поставленная ровно с её {@code facing}, соберётся в другую фигуру:
 * прямую или угол в другую сторону. Это и есть «ступеньки коннектятся не так».
 *
 * <p>Здесь — только чтение: какой фигурой ступень станет среди ожидаемых соседей, и какую
 * из двух записей угла ставить, чтобы ваниль собрала нужную. Правило соединения — своими
 * словами: угол наружу, если спереди ступень поперёк, угол внутрь, если такая сзади, и
 * сосед сбоку с той же спиной не даёт углу сложиться.
 */
@Environment(EnvType.CLIENT)
public final class Stairs
{
    private Stairs() {}

    public static boolean is(BlockState state)
    {
        return state.getBlock() instanceof StairBlock;
    }

    /**
     * Та же фигура, другой записью: для прямой ступени — она сама. Угол «левый» при спине
     * F — это «правый» угол при спине, повёрнутой от F против часовой.
     */
    public static BlockState twin(BlockState state)
    {
        if (! is(state))
        {
            return state;
        }

        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);

        return switch (state.getValue(BlockStateProperties.STAIRS_SHAPE))
        {
            case STRAIGHT -> state;
            case OUTER_LEFT -> turn(state, facing.getCounterClockWise(), StairsShape.OUTER_RIGHT);
            case OUTER_RIGHT -> turn(state, facing.getClockWise(), StairsShape.OUTER_LEFT);
            case INNER_LEFT -> turn(state, facing.getCounterClockWise(), StairsShape.INNER_RIGHT);
            case INNER_RIGHT -> turn(state, facing.getClockWise(), StairsShape.INNER_LEFT);
        };
    }

    /**
     * Две ступени одной фигуры: тот же блок, та же половина и то же всё прочее, а спина
     * с формой — одной из двух записей. Сверка «клетка готова» обязана это знать: иначе угол,
     * поставленный второй записью, выглядит недоделанным, и мод снова кликает в занятую
     * клетку — а ваниль уводит такой клик на клетку за гранью.
     */
    public static boolean sameShape(BlockState wanted, BlockState world)
    {
        if (wanted == world)
        {
            return true;
        }

        return is(wanted) && world.is(wanted.getBlock()) && twin(wanted) == world;
    }

    /**
     * Какое состояние ставить в {@code pos}, чтобы ступень собралась фигурой схемы.
     * Сначала своя запись схемы; если среди ожидаемых соседей она соберётся не той
     * фигурой, а вторая запись — той, ставится вторая. Ни одна не выходит — своя запись,
     * как раньше: дальше это решают соседи, которых ещё нет, или схема, которую ваниль
     * не собирает вовсе (её писали без обновления блоков).
     *
     * @param world     мир игрока
     * @param schematic мир схемы: где в мире ещё пусто, сосед будет таким
     */
    public static BlockState placeInstead(BlockState wanted, BlockGetter world, BlockGetter schematic, BlockPos pos)
    {
        if (! is(wanted) || wanted.getValue(BlockStateProperties.STAIRS_SHAPE) == StairsShape.STRAIGHT)
        {
            return wanted;
        }

        StairsShape shape = wanted.getValue(BlockStateProperties.STAIRS_SHAPE);

        if (settledShape(world, schematic, pos, wanted) == shape)
        {
            return wanted;
        }

        BlockState twin = twin(wanted);

        if (settledShape(world, schematic, pos, twin) == twin.getValue(BlockStateProperties.STAIRS_SHAPE))
        {
            return twin;
        }

        return wanted;
    }

    /** Форма, которую ступень с этими {@code facing} и {@code half} получит среди ожидаемых соседей. */
    public static StairsShape settledShape(BlockGetter world, BlockGetter schematic, BlockPos pos, BlockState stair)
    {
        Direction facing = stair.getValue(BlockStateProperties.HORIZONTAL_FACING);
        BlockState front = expected(world, schematic, pos.relative(facing));

        if (sameHalfStair(stair, front))
        {
            Direction frontFacing = front.getValue(BlockStateProperties.HORIZONTAL_FACING);

            if (frontFacing.getAxis() != facing.getAxis()
                    && ! sameBack(stair, expected(world, schematic, pos.relative(frontFacing.getOpposite()))))
            {
                return frontFacing == facing.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
            }
        }

        BlockState back = expected(world, schematic, pos.relative(facing.getOpposite()));

        if (sameHalfStair(stair, back))
        {
            Direction backFacing = back.getValue(BlockStateProperties.HORIZONTAL_FACING);

            if (backFacing.getAxis() != facing.getAxis()
                    && ! sameBack(stair, expected(world, schematic, pos.relative(backFacing))))
            {
                return backFacing == facing.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
            }
        }

        return StairsShape.STRAIGHT;
    }

    /**
     * Сосед, каким он будет к концу стройки: что стоит в мире — то и останется, пустая
     * клетка получит блок схемы. Сосед-ступень, которого схема записала «чужой» спиной,
     * сам встанет второй записью, поэтому и читается так, как его поставит мод.
     */
    private static BlockState expected(BlockGetter world, BlockGetter schematic, BlockPos pos)
    {
        BlockState here = world.getBlockState(pos);

        if (! here.canBeReplaced())
        {
            return here;
        }

        BlockState planned = schematic.getBlockState(pos);

        return is(planned) ? neighbourChoice(world, schematic, pos, planned) : planned;
    }

    /** Выбор записи для соседа — тот же, что для цели, но без дальнейшего спуска по соседям. */
    private static BlockState neighbourChoice(BlockGetter world, BlockGetter schematic, BlockPos pos, BlockState planned)
    {
        if (planned.getValue(BlockStateProperties.STAIRS_SHAPE) == StairsShape.STRAIGHT)
        {
            return planned;
        }

        BlockGetter flat = new Plain(world, schematic);

        if (settledShape(flat, flat, pos, planned) == planned.getValue(BlockStateProperties.STAIRS_SHAPE))
        {
            return planned;
        }

        BlockState twin = twin(planned);

        return settledShape(flat, flat, pos, twin) == twin.getValue(BlockStateProperties.STAIRS_SHAPE) ? twin : planned;
    }

    private static boolean sameHalfStair(BlockState stair, @Nullable BlockState other)
    {
        return other != null && is(other)
                && other.getValue(BlockStateProperties.HALF) == stair.getValue(BlockStateProperties.HALF);
    }

    /** Сосед сбоку — ступень с той же спиной и половиной: угол в его сторону не складывается. */
    private static boolean sameBack(BlockState stair, BlockState side)
    {
        return sameHalfStair(stair, side)
                && side.getValue(BlockStateProperties.HORIZONTAL_FACING) == stair.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    private static BlockState turn(BlockState state, Direction facing, StairsShape shape)
    {
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.STAIRS_SHAPE, shape);
    }

    /**
     * Соседи соседа — без второго спуска: мир, где пусто, — схема как есть.
     * Иначе выбор записи ходил бы по цепочке ступеней сколь угодно далеко.
     */
    private record Plain(BlockGetter world, BlockGetter schematic) implements BlockGetter
    {
        @Override
        public BlockState getBlockState(BlockPos pos)
        {
            BlockState here = this.world.getBlockState(pos);

            return here.canBeReplaced() ? this.schematic.getBlockState(pos) : here;
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos)
        {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public @Nullable net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos)
        {
            return null;
        }

        @Override
        public int getHeight()
        {
            return this.world.getHeight();
        }

        @Override
        public int getMinY()
        {
            return this.world.getMinY();
        }
    }
}
