package com.aell.layerlist.core;

import com.aell.layerlist.ghost.GhostSupport;
import java.util.function.LongConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Опоры блоков слоя, лежащие вне слоя, — для списка материалов. Решается только по схеме
 * ({@link SchematicBlocks}), без мира игрока и без прорисовки: блок слоя в схеме держится, а без
 * соседа с другого слоя — нет; значит, чтобы поставить слой, этот сосед нужен тоже.
 *
 * <p>«Держится» — ванильный {@code canSurvive} (через {@link GhostSupport#survives}: у подвесной
 * таблички на стене — {@code canPlace}), то есть любой блок, которому нужна опора: таблички на полу,
 * на стене и подвесные, факелы, свечи, ковры, рельсы, кнопки, двери, растения, лозы, сталактиты…
 * Сыпучие блоки (песок, гравий, бетонная смесь, наковальня) по {@code canSurvive} держатся всегда,
 * но без блока снизу упадут — им опора снизу тоже нужна.
 *
 * <p>Опора, которой самой нужна опора дальше от слоя (лоза над лозой, цепь под потолком), тянет её
 * за собой — по той же стороне, до {@link #MAX_CHAIN} звеньев; сыпучая опора — только сама.
 */
@Environment(EnvType.CLIENT)
public final class LayerSupports
{
    private static final int MAX_CHAIN = 64;

    private LayerSupports() {}

    /**
     * Опоры клетки слоя {@code pos} с блоком схемы {@code state}. {@code outside} — стороны вдоль оси
     * слоёв, где соседняя клетка вне слоя (у клетки в глубине диапазона их нет). Каждая опора
     * отдаётся в {@code out} как {@link BlockPos#asLong} один раз.
     */
    public static void collect(BlockState state, BlockPos pos, Direction[] outside, BlockGetter schematic,
                               LongConsumer out)
    {
        if (outside.length == 0 || ! stands(state, schematic, pos))
        {
            // В самой схеме блок не держится (опора — рельеф вне схемы) — опоры из схемы у него нет
            return;
        }

        BlockGetter bare = schematic;

        for (Direction side : outside)
        {
            bare = GhostSupport.with(bare, pos.relative(side), Blocks.AIR.defaultBlockState());
        }

        if (stands(state, bare, pos))
        {
            return;
        }

        boolean any = false;

        for (Direction side : outside)
        {
            BlockPos near = pos.relative(side);
            BlockState support = schematic.getBlockState(near);

            if (! support.isAir() && stands(state, GhostSupport.with(bare, near, support), pos))
            {
                chain(near, support, side, schematic, out);
                any = true;
            }
        }

        if (any)
        {
            return;
        }

        // Ни одной опоры хватит по отдельности — нужны сразу все (стебель большого капельника)
        for (Direction side : outside)
        {
            BlockPos near = pos.relative(side);
            BlockState support = schematic.getBlockState(near);

            if (! support.isAir())
            {
                chain(near, support, side, schematic, out);
            }
        }
    }

    /** Опора и то, на чём держится она сама, дальше от слоя по стороне {@code side}. */
    private static void chain(BlockPos first, BlockState firstState, Direction side, BlockGetter schematic,
                              LongConsumer out)
    {
        BlockPos at = first.immutable();
        BlockState state = firstState;
        out.accept(at.asLong());

        for (int i = 0; i < MAX_CHAIN; ++i)
        {
            // Песок на песке дальше не тянем: толща сыпучего под слоем (пустыня в схеме) иначе вошла бы
            // в список целиком, а строят её всё равно раньше слоя
            if (state.getBlock() instanceof FallingBlock)
            {
                return;
            }

            BlockPos next = at.relative(side);
            BlockState nextState = schematic.getBlockState(next);

            if (nextState.isAir() || ! stands(state, schematic, at)
                    || stands(state, GhostSupport.with(schematic, next, Blocks.AIR.defaultBlockState()), at))
            {
                return;
            }

            out.accept(next.asLong());
            at = next;
            state = nextState;
        }
    }

    /** Устоит ли блок в клетке: {@code canSurvive}, а сыпучий — ещё и не упадёт. */
    private static boolean stands(BlockState state, BlockGetter world, BlockPos pos)
    {
        if (! GhostSupport.survives(state, world, pos))
        {
            return false;
        }

        return ! (state.getBlock() instanceof FallingBlock) || ! FallingBlock.isFree(world.getBlockState(pos.below()));
    }
}
