package com.aell.layerlist.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Площадка для проб. Пять «станций» стоят разом и больше не меняются, поэтому дамп
 * решателя (B) не трогает мир вовсе: для каждой пробы достаточно взять клетку той
 * станции, чья опора нужна блоку.
 *
 * <pre>
 *   FLOOR   опора снизу — самый частый случай схемы
 *   BOX     опора со всех шести сторон — максимум свободы для выбора грани клика
 *   WALL    опора только с севера — настенные блоки
 *   CEIL    опора только сверху — потолочные
 *   WATER   источник воды в клетке цели — кувшинки, икра, водные растения
 * </pre>
 */
@Environment(EnvType.CLIENT)
public final class CovArena
{
    public enum Station
    {
        FLOOR(0),
        BOX(8),
        WALL(16),
        CEIL(24),
        WATER(32),
        ONWATER(40),
        DARK(48),
        SAND(56),
        SOIL(64),
        FARM(72);

        public final int x;

        Station(int x)
        {
            this.x = x;
        }
    }

    /** Уровень цели: у всех станций один. */
    public static final int Y = 80;

    /** Служебная клетка: с неё крутится лут в дампе A. */
    public static final BlockPos PLACE = new BlockPos(88, Y, 0);

    /** Клетка без единого соседа: по ней видно, нужна ли блоку опора вообще. */
    public static final BlockPos FREE = new BlockPos(Station.WALL.x, Y + 3, 0);

    private static final int PAD_X = -10;

    private CovArena() {}

    public static BlockPos target(Station station)
    {
        return new BlockPos(station.x, Y, 0);
    }

    public static void prepare(MinecraftServer server)
    {
        server.execute(() ->
        {
            command(server, "gamerule doDaylightCycle false");
            command(server, "gamerule randomTickSpeed 0");
            command(server, "gamerule doMobSpawning false");
            command(server, "gamerule doFireTick false");
            command(server, "gamerule doWeatherCycle false");
            command(server, "gamerule doTileDrops false");
            command(server, "gamerule sendCommandFeedback false");
            command(server, "difficulty peaceful");
            command(server, "time set noon");
            command(server, "weather clear");
            command(server, "forceload add -32 -32 64 32");
            command(server, "gamemode creative @a");
            command(server, "tp @a " + (PAD_X + 0.5D) + " " + (Y + 1) + " 0.5 90 0");

            build(server.overworld());
        });
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("команда не прошла (" + command + "): " + e);
        }
    }

    private static void build(ServerLevel level)
    {
        BlockState stone = Blocks.STONE.defaultBlockState();

        // площадка под ноги
        for (int x = PAD_X - 2; x <= PAD_X + 2; ++x)
        {
            for (int z = -2; z <= 2; ++z)
            {
                level.setBlock(new BlockPos(x, Y, z), stone, Block.UPDATE_ALL);
            }
        }

        for (Station station : Station.values())
        {
            clear(level, target(station), 3);
        }

        clear(level, PLACE, 3);

        set(level, target(Station.FLOOR).below(), stone);

        for (Direction side : Direction.values())
        {
            set(level, target(Station.BOX).relative(side), stone);
        }

        set(level, target(Station.WALL).north(), stone);
        set(level, target(Station.CEIL).above(), stone);

        // чаша для воды: источник ровно в клетке цели, вокруг камень, чтобы не растекался
        BlockPos water = target(Station.WATER);

        for (Direction side : Direction.values())
        {
            if (side != Direction.UP)
            {
                set(level, water.relative(side), stone);
            }
        }

        set(level, water, Blocks.WATER.defaultBlockState());

        // вода под клеткой цели: кувшинка и икра лягушки встают на поверхность воды
        BlockPos pool = target(Station.ONWATER).below();

        for (Direction side : Direction.values())
        {
            if (side != Direction.UP)
            {
                set(level, pool.relative(side), stone);
            }
        }

        set(level, pool, Blocks.WATER.defaultBlockState());

        // тёмный карман на подзоле: грибу нужна либо темнота, либо блок из тега
        // overrides_mushroom_light_requirement под ним — здесь есть и то, и другое
        BlockPos dark = target(Station.DARK);

        set(level, dark.below(), Blocks.PODZOL.defaultBlockState());
        set(level, dark.above(), stone);

        for (Direction side : Direction.Plane.HORIZONTAL)
        {
            set(level, dark.relative(side), stone);
        }

        // песок с водой под боком: кактус, тростник, бамбук
        BlockPos sand = target(Station.SAND);

        set(level, sand.below().below(), stone);
        set(level, sand.below(), Blocks.SAND.defaultBlockState());
        set(level, sand.below().east(), Blocks.WATER.defaultBlockState());
        set(level, sand.below().east().east(), stone);
        set(level, sand.below().east().north(), stone);
        set(level, sand.below().east().south(), stone);
        set(level, sand.below().east().below(), stone);

        // земля и грядка: двойные растения, саженцы, кусты, посевы
        set(level, target(Station.SOIL).below(), Blocks.GRASS_BLOCK.defaultBlockState());
        set(level, target(Station.FARM).below(), Blocks.FARMLAND.defaultBlockState());
        set(level, target(Station.FARM).below().below(), stone);

        set(level, PLACE.below(), stone);

        // приступки: с них игрок дотягивается до клетки станции ванильной дистанцией
        for (Station station : Station.values())
        {
            for (int dx = -1; dx <= 1; ++dx)
            {
                set(level, new BlockPos(station.x + dx, Y - 1, 3), stone);
            }
        }
    }

    /** Куда встать, чтобы кликать по клетке станции. */
    public static void stand(MinecraftServer server, Station station)
    {
        command(server, "tp @a " + (station.x + 0.5D) + " " + Y + " 3.5 180 0");
    }

    /** Вернуть станцию в исходное состояние после пробной установки. */
    public static void reset(ServerLevel level, Station station)
    {
        BlockPos target = target(station);
        BlockState stone = Blocks.STONE.defaultBlockState();

        clear(level, target, 1);

        switch (station)
        {
            case FLOOR -> set(level, target.below(), stone);
            case BOX ->
            {
                for (Direction side : Direction.values())
                {
                    set(level, target.relative(side), stone);
                }
            }
            case WALL -> set(level, target.north(), stone);
            case CEIL -> set(level, target.above(), stone);
            case WATER ->
            {
                bowl(level, target, stone);
                set(level, target, Blocks.WATER.defaultBlockState());
            }
            case ONWATER ->
            {
                bowl(level, target.below(), stone);
                set(level, target.below(), Blocks.WATER.defaultBlockState());
            }
            case DARK ->
            {
                set(level, target.below(), Blocks.PODZOL.defaultBlockState());
                set(level, target.above(), stone);

                for (Direction side : Direction.Plane.HORIZONTAL)
                {
                    set(level, target.relative(side), stone);
                }
            }
            case SOIL -> set(level, target.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            case FARM ->
            {
                set(level, target.below().below(), Blocks.STONE.defaultBlockState());
                set(level, target.below(), Blocks.FARMLAND.defaultBlockState());
            }
            case SAND ->
            {
                set(level, target.below().below(), Blocks.STONE.defaultBlockState());
                set(level, target.below(), Blocks.SAND.defaultBlockState());
                set(level, target.below().east(), Blocks.WATER.defaultBlockState());
            }
        }
    }

    private static void bowl(ServerLevel level, BlockPos centre, BlockState stone)
    {
        for (Direction side : Direction.values())
        {
            if (side != Direction.UP)
            {
                set(level, centre.relative(side), stone);
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos centre, int radius)
    {
        for (int x = -radius; x <= radius; ++x)
        {
            for (int y = -radius; y <= radius; ++y)
            {
                for (int z = -radius; z <= radius; ++z)
                {
                    level.setBlock(centre.offset(x, y, z), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state)
    {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** Дошла ли площадка до клиента: дальше решатель смотрит именно в клиентский мир. */
    public static boolean ready(Minecraft mc, ServerLevel level)
    {
        if (mc.level == null || mc.player == null)
        {
            return false;
        }

        return mc.level.getBlockState(target(Station.FLOOR).below()).is(Blocks.STONE)
                && mc.level.getBlockState(target(Station.BOX).north()).is(Blocks.STONE)
                && mc.level.getBlockState(target(Station.WALL).north()).is(Blocks.STONE)
                && mc.level.getBlockState(target(Station.CEIL).above()).is(Blocks.STONE)
                && mc.level.getBlockState(target(Station.WATER)).is(Blocks.WATER)
                && mc.level.getBlockState(target(Station.ONWATER).below()).is(Blocks.WATER)
                && mc.level.getBlockState(target(Station.ONWATER)).isAir()
                && mc.level.getBlockState(target(Station.DARK).below()).is(Blocks.PODZOL)
                && mc.level.getBlockState(target(Station.SAND).below()).is(Blocks.SAND)
                && mc.level.getBlockState(target(Station.SOIL).below()).is(Blocks.GRASS_BLOCK)
                && mc.level.getBlockState(target(Station.FARM).below()).is(Blocks.FARMLAND)
                && mc.level.getBlockState(target(Station.FLOOR)).isAir();
    }
}
