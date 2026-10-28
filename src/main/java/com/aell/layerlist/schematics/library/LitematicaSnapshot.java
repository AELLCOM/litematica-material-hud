package com.aell.layerlist.schematics.library;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

/**
 * Снимок из схемы, которую уже загрузила Litematica, — путь для {@code .schematic}: числовые id
 * Schematica переводит в состояния только она. Читаем её контейнеры регионов (это обычные массивы;
 * после загрузки схема не меняется, поэтому читать можно и в фоне).
 */
@Environment(EnvType.CLIENT)
final class LitematicaSnapshot
{
    private LitematicaSnapshot() {}

    static VoxelSnapshot of(LitematicaSchematic schematic, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        Map<String, BlockPos> positions = schematic.getAreaPositions();
        Map<String, BlockPos> sizes = schematic.getAreaSizes();

        // Общая коробка всех регионов по осям X, Y, Z: начало и конец (исключительно)
        long[] from = { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
        long[] to = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };

        for (Map.Entry<String, BlockPos> area : positions.entrySet())
        {
            BlockPos size = sizes.get(area.getKey());
            BlockPos min = minCorner(area.getValue(), size);

            if (min == null || size == null)
            {
                continue;
            }

            widen(from, to, 0, min.getX(), Math.abs(size.getX()));
            widen(from, to, 1, min.getY(), Math.abs(size.getY()));
            widen(from, to, 2, min.getZ(), Math.abs(size.getZ()));
        }

        if (from[0] == Long.MAX_VALUE)
        {
            throw new SchematicReadException("layerlist.schematics.error.empty");
        }

        long minX = from[0];
        long minY = from[1];
        long minZ = from[2];
        SnapshotGrid grid = SnapshotGrid.create(to[0] - minX, to[1] - minY, to[2] - minZ, maxCells, cancelled);

        for (Map.Entry<String, BlockPos> area : positions.entrySet())
        {
            BlockPos min = minCorner(area.getValue(), sizes.get(area.getKey()));
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(area.getKey());

            if (min == null || container == null)
            {
                continue;
            }

            // Контейнер нумерует клетки от минимального угла региона, размеры — уже без знака
            Vec3i size = container.getSize();
            int offX = (int) (min.getX() - minX);
            int offY = (int) (min.getY() - minY);
            int offZ = (int) (min.getZ() - minZ);

            for (int y = 0; y < size.getY(); ++y)
            {
                for (int z = 0; z < size.getZ(); ++z)
                {
                    for (int x = 0; x < size.getX(); ++x)
                    {
                        grid.put(offX + x, offY + y, offZ + z, grid.id(container.get(x, y, z)));
                    }
                }
            }
        }

        return grid.build();
    }

    /** Раздвигает коробку по оси {@code axis}, чтобы в неё вошёл отрезок [start, start + length). */
    private static void widen(long[] from, long[] to, int axis, long start, long length)
    {
        if (start < from[axis])
        {
            from[axis] = start;
        }

        if (start + length > to[axis])
        {
            to[axis] = start + length;
        }
    }

    private static @Nullable BlockPos minCorner(@Nullable BlockPos position, @Nullable BlockPos size)
    {
        if (position == null || size == null || size.getX() == 0 || size.getY() == 0 || size.getZ() == 0)
        {
            return null;
        }

        return new BlockPos(
                position.getX() + (size.getX() < 0 ? size.getX() + 1 : 0),
                position.getY() + (size.getY() < 0 ? size.getY() + 1 : 0),
                position.getZ() + (size.getZ() < 0 ? size.getZ() + 1 : 0));
    }
}
