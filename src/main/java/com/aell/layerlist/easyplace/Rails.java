package com.aell.layerlist.easyplace;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.jetbrains.annotations.Nullable;

/**
 * Форма рельса, посчитанная заранее — до клика и без единой записи в мир.
 *
 * Установкой рельс задаёт только ось: {@code BaseRailBlock.getStateForPlacement} смотрит
 * на {@code context.getHorizontalDirection()} и кладёт {@code EAST_WEST} либо
 * {@code NORTH_SOUTH}. Настоящую форму — поворот, подъём, разворот на соседа — блок
 * получает уже в мире: {@code onPlace} → {@code updateDir} → {@code RailState.place}.
 * На клиенте эта ветка выходит первой же строкой ({@code level.isClientSide}), поэтому
 * ванильная симуляция клика в {@link SimPlaceContext} видит только ось из угла взгляда
 * и о форме не знает ничего.
 *
 * Отсюда и брался главный симптом: решатель считал форму «не своей» и не перебирал под
 * неё угол взгляда, так что рельс ложился по той оси, куда игрок случайно смотрел. Здесь
 * правила ванильной доводки записаны своими словами — данными (у формы два конца, у развилки —
 * старшинство поворотов, у прямого участка — подъём к рельсу этажом выше) и только на чтение:
 * они отвечают, какой формой рельс встанет на самом деле, и решатель выбирает под схему угол.
 *
 * Считается всё на клетке-цели с «голограммой»: сервер зовёт {@code onPlace} уже после того,
 * как блок лёг в мир, поэтому и здесь клетка цели читается как поставленный рельс, а не
 * как воздух — иначе соседи не увидели бы, к чему им подключаться.
 */
@Environment(EnvType.CLIENT)
public final class Rails
{
    private Rails() {}

    public static boolean is(BlockState state)
    {
        return state.getBlock() instanceof BaseRailBlock;
    }

    /**
     * Состояние, каким рельс станет после ванильной доводки соседями. Для не-рельса —
     * то же состояние без изменений: звать это можно на любом результате установки.
     */
    public static BlockState settle(BlockGetter level, BlockPos pos, BlockState placed)
    {
        // Сигнал читается только у рельса: решатель зовёт settle на каждом пробном состоянии
        return is(placed) ? settle(level, pos, placed, signal(level, pos)) : placed;
    }

    /** То же с готовым ответом «есть ли рядом красный сигнал» (так его проверяет харнесс t). */
    static BlockState settle(BlockGetter level, BlockPos pos, BlockState placed, boolean powered)
    {
        if (! (placed.getBlock() instanceof BaseRailBlock rail))
        {
            return placed;
        }

        Property<RailShape> property = rail.getShapeProperty();

        if (! placed.hasProperty(property))
        {
            return placed;
        }

        RailShape placedShape = placed.getValue(property);
        RailShape settled = resolve(new View(level, pos, placed), pos, rail.isStraight(), powered, placedShape);

        return settled == placedShape ? placed : placed.setValue(property, settled);
    }

    /**
     * Форма ещё может измениться сама: рядом есть клетка, где схема просит рельс, а его
     * пока нет. Пока такая клетка есть, позицию с «не той» формой лучше отложить — она
     * встанет верно, когда сосед появится (или ванильная доводка развернёт её сама, когда
     * сосед подключится к ней). Когда ждать больше нечего, откладывать нельзя: иначе с
     * включённым «Пропускать то, что встанет неверно» в пути навсегда осталась бы дыра.
     *
     * Соседство берётся то же, что смотрит {@code RailState}: четыре стороны, каждая — на
     * своём уровне, этажом выше (подъёмы) и этажом ниже (спуски).
     *
     * <p>Ждём только прямого соседа: он встаёт верно и сам, по взгляду. Поворот и подъём сами тоже
     * ждут соседей — кольцо поворотов (или повороты вокруг блока редстоуна) ждало бы друг друга вечно
     * и не вставало вовсе (стенд {@code LL_COVERAGE=build}, 09.10.2026). Такой рельс ставится сразу:
     * следующий подключится к нему, и ванильная доводка формы загнёт его сама.
     */
    public static boolean mayImprove(BlockGetter level, @Nullable BlockGetter schematic, BlockPos pos)
    {
        if (schematic == null)
        {
            return false;
        }

        for (Direction side : Direction.Plane.HORIZONTAL)
        {
            BlockPos neighbour = pos.relative(side);

            for (int step = -1; step <= 1; ++step)
            {
                BlockPos cell = neighbour.above(step);
                BlockState wanted = schematic.getBlockState(cell);

                if (BaseRailBlock.isRail(wanted) && ! BaseRailBlock.isRail(level.getBlockState(cell))
                        && placesAlone(wanted))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Рельс встаёт нужной формой без соседей: прямой и ровный (север—юг или запад—восток). */
    private static boolean placesAlone(BlockState state)
    {
        if (! (state.getBlock() instanceof BaseRailBlock rail))
        {
            return false;
        }

        Property<RailShape> property = rail.getShapeProperty();

        if (! state.hasProperty(property))
        {
            return false;
        }

        RailShape shape = state.getValue(property);
        return shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST;
    }

    /**
     * Рельс уже стоит, и вся разница со схемой — форма. Клик сюда бесполезен и вреден:
     * форму задают соседи, а стоящий рельс не заменяется, поэтому правый клик по нему
     * ушёл бы в клетку над ним — лишним рельсом поверх пути.
     */
    public static boolean onlyShapeDiffers(BlockState schematic, BlockState world)
    {
        if (! (schematic.getBlock() instanceof BaseRailBlock rail)
                || ! world.is(schematic.getBlock()))
        {
            return false;
        }

        Property<RailShape> shape = rail.getShapeProperty();

        if (! schematic.hasProperty(shape) || ! world.hasProperty(shape))
        {
            return false;
        }

        for (Property<?> property : schematic.getBlock().getStateDefinition().getProperties())
        {
            if (! property.equals(shape) && ! same(schematic, world, property))
            {
                return false;
            }
        }

        return ! same(schematic, world, shape);
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

    /**
     * Красный сигнал рядом разводит развилку на три соседа в другую сторону — ванильный
     * {@code updateDir} передаёт его в {@code place} третьим доводом. На клиенте сигнал
     * читается из тех же состояний блоков, что и на сервере, поэтому ответ тот же.
     */
    private static boolean signal(BlockGetter level, BlockPos pos)
    {
        try
        {
            return level instanceof Level world && world.hasNeighborSignal(pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Мир глазами уже поставленного рельса: клетка цели читается как он, остальное — как есть. */
    private record View(BlockGetter level, BlockPos pos, BlockState state)
    {
        BlockState at(BlockPos where)
        {
            return where.equals(this.pos) ? this.state : this.level.getBlockState(where);
        }

        boolean rail(BlockPos where)
        {
            return BaseRailBlock.isRail(this.at(where));
        }

        /** Рельс «у этой клетки»: в ней самой, иначе этажом выше, иначе этажом ниже; null — нет. */
        @Nullable BlockPos railNear(BlockPos cell)
        {
            for (int dy : NEAR_LEVELS)
            {
                BlockPos probe = cell.above(dy);

                if (this.rail(probe))
                {
                    return probe;
                }
            }

            return null;
        }

        RailShape shapeAt(BlockPos rail)
        {
            BlockState state = this.at(rail);
            return state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
        }
    }

    // ===== Правила доводки — данными =====
    //
    // У каждой формы два конца. Конец — сторона и «на подъёме ли он» (поднятый конец смотрит в
    // клетку этажом выше). Запись «N S» читается «север и юг на одном уровне», «W E^» — «запад, и
    // восток этажом выше». Отсюда же берутся и подъёмы: форма с поднятым концом в сторону X —
    // это подъём к X.

    private static final int[] NEAR_LEVELS = { 0, 1, -1 };

    private static final Map<RailShape, End[]> ENDS = endTable(Map.of(
            RailShape.NORTH_SOUTH, "N S", RailShape.EAST_WEST, "W E",
            RailShape.ASCENDING_EAST, "W E^", RailShape.ASCENDING_WEST, "W^ E",
            RailShape.ASCENDING_NORTH, "N^ S", RailShape.ASCENDING_SOUTH, "N S^",
            RailShape.SOUTH_EAST, "E S", RailShape.SOUTH_WEST, "W S",
            RailShape.NORTH_WEST, "W N", RailShape.NORTH_EAST, "E N"));

    // Развилка у рельса с поворотами решается старшинством поворотов: первый в списке, у которого
    // присоединились обе стороны, и есть ответ. Красный сигнал рядом меняет старшинство на другое.
    private static final RailShape[] CORNERS_WITH_SIGNAL = { RailShape.NORTH_WEST, RailShape.NORTH_EAST,
            RailShape.SOUTH_WEST, RailShape.SOUTH_EAST };
    private static final RailShape[] CORNERS_WITHOUT_SIGNAL = { RailShape.SOUTH_EAST, RailShape.SOUTH_WEST,
            RailShape.NORTH_EAST, RailShape.NORTH_WEST };

    // Прямой участок поднимается к стороне, где этажом выше лежит рельс; если таких две —
    // побеждает та, что раньше в этом списке.
    private static final Direction[] RISE_ORDER = { Direction.SOUTH, Direction.NORTH, Direction.WEST, Direction.EAST };

    /** Конец формы: сторона и поднят ли он на этаж. */
    private record End(Direction side, boolean raised)
    {
        BlockPos cell(BlockPos from)
        {
            BlockPos next = from.relative(this.side);
            return this.raised ? next.above() : next;
        }
    }

    private static Map<RailShape, End[]> endTable(Map<RailShape, String> spec)
    {
        Map<RailShape, End[]> table = new EnumMap<>(RailShape.class);

        spec.forEach((shape, text) -> {
            String[] parts = text.split(" ");
            End[] ends = new End[parts.length];

            for (int i = 0; i < parts.length; ++i)
            {
                Direction side = switch (parts[i].charAt(0))
                {
                    case 'N' -> Direction.NORTH;
                    case 'S' -> Direction.SOUTH;
                    case 'W' -> Direction.WEST;
                    default -> Direction.EAST;
                };
                ends[i] = new End(side, parts[i].endsWith("^"));
            }

            table.put(shape, ends);
        });

        return table;
    }

    private static boolean sameColumn(BlockPos a, BlockPos b)
    {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /** Есть ли у рельса в клетке {@code rail} конец, упирающийся в столбец {@code column}. */
    private static boolean reaches(View view, BlockPos rail, BlockPos column)
    {
        for (End end : ENDS.get(view.shapeAt(rail)))
        {
            if (sameColumn(end.cell(rail), column))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Подключится ли к цели рельс со стороны {@code side}. Сосед свободен, пока хотя бы один его
     * конец не занят: занятый конец — тот, у которого рядом лежит рельс, смотрящий обратно на соседа.
     * Сосед, у которого заняты оба конца, подключается, только если один из них и есть цель.
     */
    private static boolean attaches(View view, BlockPos target, Direction side)
    {
        BlockPos neighbour = view.railNear(target.relative(side));

        if (neighbour == null)
        {
            return false;
        }

        int busy = 0;
        boolean toTarget = false;

        for (End end : ENDS.get(view.shapeAt(neighbour)))
        {
            BlockPos cell = end.cell(neighbour);
            BlockPos other = view.railNear(cell);

            if (other != null && reaches(view, other, neighbour))
            {
                ++busy;
                toTarget |= sameColumn(cell, target);
            }
        }

        return toTarget || busy != 2;
    }

    /** Форма, к которой ванильная доводка приведёт рельс {@code pos}; {@code asPlaced} — форма установки. */
    private static RailShape resolve(View view, BlockPos pos, boolean straightOnly, boolean powered, RailShape asPlaced)
    {
        EnumSet<Direction> joined = EnumSet.noneOf(Direction.class);

        for (Direction side : Direction.Plane.HORIZONTAL)
        {
            if (attaches(view, pos, side))
            {
                joined.add(side);
            }
        }

        boolean zAxis = joined.contains(Direction.NORTH) || joined.contains(Direction.SOUTH);
        boolean xAxis = joined.contains(Direction.WEST) || joined.contains(Direction.EAST);

        if (! zAxis && ! xAxis)
        {
            // Соседей нет вовсе — форма целиком за углом взгляда, подъёма тоже нет
            return asPlaced;
        }

        RailShape shape;

        if (zAxis != xAxis)
        {
            shape = zAxis ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
        }
        else if (straightOnly)
        {
            // Соседи по обеим осям у прямого рельса (энергетический, нажимной, активирующий):
            // поворотов у него нет, и ось развилки решает угол взгляда при установке
            shape = asPlaced;
        }
        else
        {
            shape = firstCorner(joined, powered ? CORNERS_WITH_SIGNAL : CORNERS_WITHOUT_SIGNAL, asPlaced);
        }

        return rise(view, pos, shape);
    }

    private static RailShape firstCorner(EnumSet<Direction> joined, RailShape[] order, RailShape fallback)
    {
        for (RailShape corner : order)
        {
            boolean both = true;

            for (End end : ENDS.get(corner))
            {
                both &= joined.contains(end.side());
            }

            if (both)
            {
                return corner;
            }
        }

        return fallback;
    }

    /** Прямой участок становится подъёмом к стороне, где этажом выше лежит рельс. */
    private static RailShape rise(View view, BlockPos pos, RailShape shape)
    {
        if (shape != RailShape.NORTH_SOUTH && shape != RailShape.EAST_WEST)
        {
            return shape;
        }

        for (Direction side : RISE_ORDER)
        {
            if (endsToward(shape, side, false) && view.rail(pos.relative(side).above()))
            {
                return ascendingToward(side, shape);
            }
        }

        return shape;
    }

    private static boolean endsToward(RailShape shape, Direction side, boolean raised)
    {
        for (End end : ENDS.get(shape))
        {
            if (end.side() == side && end.raised() == raised)
            {
                return true;
            }
        }

        return false;
    }

    private static RailShape ascendingToward(Direction side, RailShape fallback)
    {
        for (RailShape shape : RailShape.values())
        {
            if (endsToward(shape, side, true))
            {
                return shape;
            }
        }

        return fallback;
    }
}
