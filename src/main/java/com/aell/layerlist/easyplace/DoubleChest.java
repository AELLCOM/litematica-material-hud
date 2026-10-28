package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Двойной сундук: половина пары одним кликом не получается никогда.
 *
 * {@code ChestBlock.getStateForPlacement} берёт {@code type} не из клика, а из соседей
 * мира — {@code candidatePartnerFacing} требует, чтобы рядом <b>уже стоял</b> сундук того
 * же блока с {@code type=single} и тем же лицом. У первой половины пары такого соседа нет,
 * поэтому решатель при любом зонде получал {@code single}, а {@code CHEST_TYPE} лежит в
 * {@link Controllable} среди задаваемых установкой — {@code Plan.misplaced()} возвращал
 * true, и «Пропускать то, что встанет неверно» гасило клетку навсегда. Свой же харнесс
 * покрытия это и показывал: {@code chest[type=left]} — UNREACHABLE, 13440 зондов,
 * ожидаемое состояние {@code type=single}.
 *
 * Пара собирается отложенно: первый сундук встаёт одиночным, а когда рядом встанет второй,
 * ванильный {@code ChestBlock.updateShape} переводит стоящий {@code single} в
 * {@code neighbourType.getOpposite()} — обе половины становятся двойным сундуком сами.
 *
 * Поэтому цель подменяется на {@code type=single} именно подстановкой значения, а не
 * выбрасыванием свойства из сверки: иначе клик, слипшийся с посторонним сундуком не с той
 * стороны, прошёл бы как «идеальный».
 */
@Environment(EnvType.CLIENT)
public final class DoubleChest
{
    private DoubleChest() {}

    public static boolean is(BlockState state)
    {
        return state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.hasProperty(ChestBlock.FACING);
    }

    /** Схема просит половину пары. */
    public static boolean wantsPair(BlockState state)
    {
        return is(state) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * Партнёр уже стоит в мире одиночным и тем же лицом: этот клик их и слепит, значит
     * {@code type} достижим сразу и сверять его надо как обычно.
     */
    public static boolean partnerReady(BlockGetter level, BlockPos pos, BlockState wanted)
    {
        if (! wantsPair(wanted))
        {
            return false;
        }

        BlockState partner = level.getBlockState(ChestBlock.getConnectedBlockPos(pos, wanted));

        return is(partner) && partner.is(wanted.getBlock())
                && partner.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                && partner.getValue(ChestBlock.FACING).equals(wanted.getValue(ChestBlock.FACING));
    }

    /**
     * Состояние, которое решатель должен получить этим кликом: пока партнёра нет — ровно
     * одиночный сундук нужного лица, не «любой».
     */
    public static BlockState placeInstead(BlockState wanted, BlockGetter level, BlockPos pos)
    {
        return ! wantsPair(wanted) || partnerReady(level, pos, wanted)
                ? wanted
                : wanted.setValue(ChestBlock.TYPE, ChestType.SINGLE);
    }

    /**
     * Клик, которым вторая половина слепляется с первой: присед и боковая грань уже
     * стоящего сундука, смотрящая в клетку цели. Так работает ванильная установка пары —
     * ветка {@code secondaryUse} в {@code ChestBlock.getStateForPlacement} берёт и лицо,
     * и {@code type} от партнёра за кликнутой гранью, угол взгляда не участвует вовсе.
     * Любой другой якорь собирает пару только через auto-merge, где лицо считается из
     * yaw, — а он на сервере зависит от того, дошёл ли Rot-пакет раньше клика.
     *
     * Точка — на настоящей боковой стенке сундука (15/16 от края), не на грани клетки:
     * висящую в воздухе точку античит молча отменяет.
     */
    public static BlockHitResult mergeClick(BlockPos pos, BlockState wanted)
    {
        Direction toPartner = ChestBlock.getConnectedDirection(wanted);
        BlockPos partner = pos.relative(toPartner);
        Direction face = toPartner.getOpposite();
        Vec3 point = new Vec3(partner.getX() + 0.5D + face.getStepX() * 0.4375D,
                partner.getY() + 0.5D,
                partner.getZ() + 0.5D + face.getStepZ() * 0.4375D);

        return new BlockHitResult(point, face, partner, false);
    }

    /**
     * Клетка ждёт вторую половину: стоит тот же сундук нужного лица, но одиночный. Кликом
     * здесь сделать нечего — {@code type} доведёт установка соседа.
     */
    public static boolean awaitingPartner(BlockState schematic, BlockState world)
    {
        return wantsPair(schematic) && is(world) && world.is(schematic.getBlock())
                && world.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                && world.getValue(ChestBlock.FACING).equals(schematic.getValue(ChestBlock.FACING));
    }
}
