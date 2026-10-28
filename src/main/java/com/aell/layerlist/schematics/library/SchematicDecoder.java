package com.aell.layerlist.schematics.library;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Свой разбор блоков трёх форматов в {@link VoxelSnapshot} — без Litematica и без клиентского
 * потока, только NBT, реестр блоков и DataFixer. Все регионы сводятся в один габарит в координатах
 * схемы: (0,0,0) — минимальный угол охватывающего бокса.
 *
 * <p>{@code .schematic} (числовые id Schematica) здесь не читается: его переводит в состояния сама
 * Litematica, снимок тогда строится из её контейнеров ({@link LitematicaSnapshot}).
 */
@Environment(EnvType.CLIENT)
final class SchematicDecoder
{
    /** У Sponge v1 версии данных нет; формат появился с 1.13 — от неё и обновляем. */
    static final int SPONGE_V1_DATA_VERSION = 1519;

    /** Ванильная структура без версии — так же считает и сама игра. */
    static final int STRUCTURE_DEFAULT_DATA_VERSION = 500;

    private SchematicDecoder() {}

    /**
     * @param maxCells  бюджет клеток; больше — снимок станет грубой сеткой ({@link VoxelSnapshot#scale()})
     * @param cancelled проверяется по ходу; {@code true} — разбор бросает {@link CancellationException}
     */
    static VoxelSnapshot decode(Path file, SchematicFormat format, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        if (format == SchematicFormat.SCHEMATICA)
        {
            throw new SchematicReadException("layerlist.schematics.error.needs_litematica");
        }

        CompoundTag root;

        try
        {
            root = NbtFiles.read(file);
        }
        catch (IOException | RuntimeException error)
        {
            throw new SchematicReadException(error, "layerlist.schematics.error.read", SchematicReadException.reason(error));
        }

        if (cancelled.getAsBoolean())
        {
            throw new CancellationException();
        }

        try
        {
            return switch (format)
            {
                case LITEMATIC -> litematic(root, maxCells, cancelled);
                case SPONGE -> sponge(root, maxCells, cancelled);
                case VANILLA -> structure(root, maxCells, cancelled);
                case SCHEMATICA -> throw new IllegalStateException("checked above");
            };
        }
        catch (RuntimeException error)
        {
            if (error instanceof CancellationException cancel)
            {
                throw cancel;
            }

            throw new SchematicReadException(error, "layerlist.schematics.error.corrupt", SchematicReadException.reason(error));
        }
    }

    // ===== .litematic =====

    /** Регион .litematic: мин. угол в координатах схемы (знаковые размеры уже развёрнуты) и данные. */
    private record Region(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, CompoundTag tag) {}

    private static VoxelSnapshot litematic(CompoundTag root, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        List<Region> list = new ArrayList<>();

        for (String name : regions.keySet())
        {
            CompoundTag region = regions.getCompound(name).orElse(null);

            if (region == null)
            {
                continue;
            }

            CompoundTag position = region.getCompoundOrEmpty("Position");
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int sx = size.getIntOr("x", 0);
            int sy = size.getIntOr("y", 0);
            int sz = size.getIntOr("z", 0);

            if (sx == 0 || sy == 0 || sz == 0)
            {
                continue;
            }

            // Отрицательный размер — регион растянут от позиции «назад»: позиция — его дальний угол
            list.add(new Region(
                    position.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0),
                    position.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0),
                    position.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0),
                    Math.abs(sx), Math.abs(sy), Math.abs(sz), region));
        }

        if (list.isEmpty())
        {
            throw new SchematicReadException("layerlist.schematics.error.empty");
        }

        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;

        for (Region region : list)
        {
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, (long) region.minX() + region.sizeX());
            maxY = Math.max(maxY, (long) region.minY() + region.sizeY());
            maxZ = Math.max(maxZ, (long) region.minZ() + region.sizeZ());
        }

        SnapshotGrid grid = SnapshotGrid.create(maxX - minX, maxY - minY, maxZ - minZ, maxCells, cancelled);
        BlockPalette resolver = new BlockPalette(root.getIntOr("MinecraftDataVersion", 0));

        for (Region region : list)
        {
            litematicRegion(region, (int) (region.minX() - minX), (int) (region.minY() - minY),
                    (int) (region.minZ() - minZ), grid, resolver);
        }

        return grid.build();
    }

    private static void litematicRegion(Region region, int offX, int offY, int offZ, SnapshotGrid grid,
                                        BlockPalette resolver) throws SchematicReadException
    {
        ListTag palette = region.tag().getListOrEmpty("BlockStatePalette");
        long[] data = region.tag().getLongArray("BlockStates").orElse(new long[0]);
        int count = palette.size();

        if (count == 0)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "BlockStatePalette");
        }

        int[] ids = new int[count];
        boolean[] unknown = new boolean[count];

        for (int i = 0; i < count; ++i)
        {
            BlockState state = resolver.resolve(palette.getCompoundOrEmpty(i));
            unknown[i] = state == null;
            ids[i] = grid.id(state);
        }

        // Ширина записи — по размеру палитры, но не меньше двух бит; записи идут подряд и могут
        // переходить через границу long (в отличие от ванильных секций, где хвост long пустует)
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(count - 1));
        long mask = (1L << bits) - 1L;
        long volume = (long) region.sizeX() * region.sizeY() * region.sizeZ();

        if ((long) data.length * Long.SIZE < volume * bits)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "BlockStates");
        }

        long bit = 0L;
        long unknownCells = 0L;

        for (int y = 0; y < region.sizeY(); ++y)
        {
            for (int z = 0; z < region.sizeZ(); ++z)
            {
                for (int x = 0; x < region.sizeX(); ++x)
                {
                    int word = (int) (bit >>> 6);
                    int shift = (int) (bit & 63L);
                    long value = data[word] >>> shift;

                    if (shift + bits > Long.SIZE)
                    {
                        value |= data[word + 1] << (Long.SIZE - shift);
                    }

                    bit += bits;
                    int index = (int) (value & mask);

                    if (index >= count)
                    {
                        ++unknownCells;
                        continue;
                    }

                    if (unknown[index])
                    {
                        ++unknownCells;
                    }

                    grid.put(offX + x, offY + y, offZ + z, ids[index]);
                }
            }
        }

        grid.unknown(unknownCells, resolver.unknownNames());
    }

    // ===== Sponge .schem (v1–v3) =====

    private static VoxelSnapshot sponge(CompoundTag root, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        // v3 завёрнут в «Schematic», блоки — в «Blocks»; v1/v2 — всё в корне
        CompoundTag body = root.getCompound("Schematic").orElse(root);
        int width = unsignedShort(body, "Width");
        int height = unsignedShort(body, "Height");
        int length = unsignedShort(body, "Length");

        CompoundTag palette;
        byte[] data;
        Tag blocksTag = body.get("Blocks");

        if (blocksTag instanceof CompoundTag blocks)
        {
            palette = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
        }
        else
        {
            palette = body.getCompoundOrEmpty("Palette");
            data = body.getByteArray("BlockData").orElse(new byte[0]);
        }

        SnapshotGrid grid = SnapshotGrid.create(width, height, length, maxCells, cancelled);
        BlockPalette resolver = new BlockPalette(body.getIntOr("DataVersion", SPONGE_V1_DATA_VERSION));

        int top = -1;

        for (String key : palette.keySet())
        {
            top = Math.max(top, palette.getIntOr(key, -1));
        }

        if (top < 0)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "Palette");
        }

        int[] ids = new int[top + 1];
        boolean[] unknown = new boolean[top + 1];

        for (String key : palette.keySet())
        {
            int index = palette.getIntOr(key, -1);

            if (index >= 0)
            {
                BlockState state = resolver.resolve(key);
                unknown[index] = state == null;
                ids[index] = grid.id(state);
            }
        }

        int at = 0;
        long unknownCells = 0L;

        for (int y = 0; y < height; ++y)
        {
            for (int z = 0; z < length; ++z)
            {
                for (int x = 0; x < width; ++x)
                {
                    // varint: 7 бит на байт, старший бит — «дальше ещё байт»
                    int index = 0;
                    int shift = 0;
                    byte b;

                    do
                    {
                        if (at >= data.length || shift > 28)
                        {
                            throw new SchematicReadException("layerlist.schematics.error.corrupt", "BlockData");
                        }

                        b = data[at++];
                        index |= (b & 0x7F) << shift;
                        shift += 7;
                    }
                    while ((b & 0x80) != 0);

                    if (index < 0 || index > top)
                    {
                        ++unknownCells;
                        continue;
                    }

                    if (unknown[index])
                    {
                        ++unknownCells;
                    }

                    grid.put(x, y, z, ids[index]);
                }
            }
        }

        grid.unknown(unknownCells, resolver.unknownNames());

        return grid.build();
    }

    /** Стороны Sponge — short, но без знака (до 65535). */
    static int unsignedShort(CompoundTag tag, String key)
    {
        int value = tag.getIntOr(key, 0);

        return value < 0 ? value & 0xFFFF : value;
    }

    // ===== Ванильная структура .nbt =====

    private static VoxelSnapshot structure(CompoundTag root, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        ListTag size = root.getListOrEmpty("size");
        int sx = size.getIntOr(0, 0);
        int sy = size.getIntOr(1, 0);
        int sz = size.getIntOr(2, 0);

        SnapshotGrid grid = SnapshotGrid.create(sx, sy, sz, maxCells, cancelled);
        BlockPalette resolver = new BlockPalette(root.getIntOr("DataVersion", STRUCTURE_DEFAULT_DATA_VERSION));
        ListTag palette = structurePalette(root);

        if (palette == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "palette");
        }

        int count = palette.size();
        int[] ids = new int[count];
        boolean[] unknown = new boolean[count];

        for (int i = 0; i < count; ++i)
        {
            BlockState state = resolver.resolve(palette.getCompoundOrEmpty(i));
            unknown[i] = state == null;
            ids[i] = grid.id(state);
        }

        ListTag blocks = root.getListOrEmpty("blocks");
        long unknownCells = 0L;

        for (int i = 0; i < blocks.size(); ++i)
        {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            ListTag pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1);
            int y = pos.getIntOr(1, -1);
            int z = pos.getIntOr(2, -1);
            int state = block.getIntOr("state", -1);

            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || state < 0 || state >= count)
            {
                continue;
            }

            if (unknown[state])
            {
                ++unknownCells;
            }

            grid.put(x, y, z, ids[state]);
        }

        grid.unknown(unknownCells, resolver.unknownNames());

        return grid.build();
    }

    /**
     * Палитра структуры: «palette» или первая из «palettes». Несколько палитр — это варианты одной
     * постройки (например, породы дерева у кораблекрушений); игра выбирает случайно, мы — всегда
     * первую, чтобы превью и миниатюра не менялись от открытия к открытию.
     */
    static @Nullable ListTag structurePalette(CompoundTag root)
    {
        ListTag single = root.getList("palette").orElse(null);

        if (single != null)
        {
            return single;
        }

        ListTag variants = root.getListOrEmpty("palettes");

        return variants.isEmpty() ? null : variants.getListOrEmpty(0);
    }
}
