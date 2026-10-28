package com.aell.layerlist.schematics.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Неизменяемый снимок блоков схемы в её собственных координатах: все регионы сведены в один
 * габарит от (0,0,0), отрицательные размеры регионов уже нормализованы. Хранится палитра и плотный
 * массив номеров палитры; номер 0 — всегда воздух. Ширина ячейки растёт с палитрой (1, 2 или 4
 * байта), чтобы большая схема из пары сотен блоков не стоила по 4 байта на клетку.
 *
 * <p>Снимок общий для превью, миниатюры и рамки размещения, поэтому читается из любых потоков;
 * собирается {@link Builder} в одном потоке.
 *
 * <p>Очень большую схему читатель сводит к грубой сетке: одна клетка снимка — куб
 * {@link #scale()}³ блоков схемы (в клетку попадает первый непустой блок куба). Габарит снимка тогда
 * в {@code scale} раз меньше габарита схемы по каждой оси.
 */
@Environment(EnvType.CLIENT)
public final class VoxelSnapshot
{
    /**
     * Потолок клеток в одном снимке. Выше — читатель обязан проредить схему (или отказаться):
     * даже по байту на клетку это уже четверть гигабайта.
     */
    public static final long MAX_VOLUME = 1L << 28;

    private static final int BYTE_IDS = 1 << 8;
    private static final int SHORT_IDS = 1 << 16;

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState[] palette;
    private final byte @Nullable [] bytes;
    private final short @Nullable [] shorts;
    private final int @Nullable [] ints;
    private final long nonAir;
    private final int scale;
    private final long unknown;
    private final List<String> unknownNames;

    private VoxelSnapshot(Builder builder)
    {
        this.sizeX = builder.sizeX;
        this.sizeY = builder.sizeY;
        this.sizeZ = builder.sizeZ;
        this.palette = builder.palette.toArray(new BlockState[0]);
        this.bytes = builder.bytes;
        this.shorts = builder.shorts;
        this.ints = builder.ints;
        this.nonAir = this.countNonAir();
        this.scale = builder.scale;
        this.unknown = builder.unknown;
        this.unknownNames = List.copyOf(builder.unknownNames);
    }

    public int sizeX()
    {
        return this.sizeX;
    }

    public int sizeY()
    {
        return this.sizeY;
    }

    public int sizeZ()
    {
        return this.sizeZ;
    }

    public long volume()
    {
        return (long) this.sizeX * this.sizeY * this.sizeZ;
    }

    /** Состояние в клетке; за пределами габарита — воздух (удобно для проверки соседей). */
    public BlockState get(int x, int y, int z)
    {
        return this.palette[this.idAt(x, y, z)];
    }

    public int paletteSize()
    {
        return this.palette.length;
    }

    public BlockState palette(int id)
    {
        return this.palette[id];
    }

    /** Номер палитры в клетке; за пределами габарита — 0 (воздух). */
    public int idAt(int x, int y, int z)
    {
        if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ)
        {
            return 0;
        }

        int index = (y * this.sizeZ + z) * this.sizeX + x;

        if (this.bytes != null)
        {
            return this.bytes[index] & 0xFF;
        }

        if (this.shorts != null)
        {
            return this.shorts[index] & 0xFFFF;
        }

        return Objects.requireNonNull(this.ints)[index];
    }

    /** Клеток не из воздуха (любой воздух палитры — обычный, пещерный, пустотный — не считается). */
    public long nonAir()
    {
        return this.nonAir;
    }

    /**
     * Сколько блоков схемы в одной клетке снимка по каждой оси: 1 — снимок полный, 2 — схема сведена
     * кубами 2×2×2 и т. д.
     */
    public int scale()
    {
        return this.scale;
    }

    /**
     * Клеток схемы с блоками, которых нет в игре (мод не установлен, неизвестное имя). В снимке на
     * их месте воздух. Считается по исходной схеме, до прореживания.
     */
    public long unknownBlocks()
    {
        return this.unknown;
    }

    /** Имена неизвестных блоков — несколько первых, для подсказки. */
    public List<String> unknownNames()
    {
        return this.unknownNames;
    }

    private long countNonAir()
    {
        boolean[] air = new boolean[this.palette.length];

        for (int id = 0; id < this.palette.length; ++id)
        {
            air[id] = this.palette[id].isAir();
        }

        long count = 0L;
        int cells = (int) this.volume();

        for (int i = 0; i < cells; ++i)
        {
            int id = this.bytes != null ? this.bytes[i] & 0xFF
                    : this.shorts != null ? this.shorts[i] & 0xFFFF
                    : Objects.requireNonNull(this.ints)[i];

            if (! air[id])
            {
                ++count;
            }
        }

        return count;
    }

    /**
     * Сборка снимка: палитра пополняется по ходу, ширина ячейки растёт сама. Не потокобезопасен —
     * один читатель файла на один сборщик.
     */
    public static final class Builder
    {
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> ids = new IdentityHashMap<>();
        private final int scale;
        private final Set<String> unknownNames = new LinkedHashSet<>();
        private byte @Nullable [] bytes;
        private short @Nullable [] shorts;
        private int @Nullable [] ints;
        private long unknown;
        private boolean built;

        /** @throws IllegalArgumentException сторона не положительна или объём больше {@link #MAX_VOLUME} */
        public Builder(int sizeX, int sizeY, int sizeZ)
        {
            this(sizeX, sizeY, sizeZ, 1);
        }

        /**
         * @param scale блоков схемы на клетку снимка по каждой оси ({@link VoxelSnapshot#scale()})
         * @throws IllegalArgumentException сторона или масштаб не положительны, объём больше {@link #MAX_VOLUME}
         */
        public Builder(int sizeX, int sizeY, int sizeZ, int scale)
        {
            long volume = (long) sizeX * sizeY * sizeZ;

            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || scale <= 0 || volume > MAX_VOLUME)
            {
                throw new IllegalArgumentException("snapshot size " + sizeX + "x" + sizeY + "x" + sizeZ + " /" + scale);
            }

            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.scale = scale;
            this.bytes = new byte[(int) volume];
            this.id(Blocks.AIR.defaultBlockState());
        }

        /** Состояние по номеру палитры (снимок области смотрит, что уже лежит в клетке). */
        public BlockState palette(int id)
        {
            return this.palette.get(id);
        }

        /** Номер состояния в палитре; нового — добавляет. */
        public int id(BlockState state)
        {
            this.checkOpen();

            Integer known = this.ids.get(state);

            if (known != null)
            {
                return known;
            }

            int id = this.palette.size();
            this.palette.add(state);
            this.ids.put(state, id);
            this.widen(this.palette.size());

            return id;
        }

        public int paletteSize()
        {
            return this.palette.size();
        }

        /** Номер в клетке; за пределами габарита — 0. Нужен читателю грубой сетки: «клетка ещё пустая?». */
        public int idAt(int x, int y, int z)
        {
            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ)
            {
                return 0;
            }

            int index = (y * this.sizeZ + z) * this.sizeX + x;

            if (this.bytes != null)
            {
                return this.bytes[index] & 0xFF;
            }

            if (this.shorts != null)
            {
                return this.shorts[index] & 0xFFFF;
            }

            return Objects.requireNonNull(this.ints)[index];
        }

        /** Учесть клетки с неизвестными блоками (см. {@link VoxelSnapshot#unknownBlocks()}). */
        public void unknown(long cells, Collection<String> names)
        {
            this.checkOpen();
            this.unknown += Math.max(0L, cells);

            for (String name : names)
            {
                if (this.unknownNames.size() >= BlockPalette.UNKNOWN_NAMES)
                {
                    break;
                }

                this.unknownNames.add(name);
            }
        }

        public void set(int x, int y, int z, BlockState state)
        {
            this.setId(x, y, z, this.id(state));
        }

        /** @param id номер, ранее выданный {@link #id(BlockState)} */
        public void setId(int x, int y, int z, int id)
        {
            this.checkOpen();

            if (x < 0 || y < 0 || z < 0 || x >= this.sizeX || y >= this.sizeY || z >= this.sizeZ)
            {
                throw new IndexOutOfBoundsException(x + "," + y + "," + z);
            }

            if (id < 0 || id >= this.palette.size())
            {
                throw new IllegalArgumentException("palette id " + id);
            }

            int index = (y * this.sizeZ + z) * this.sizeX + x;

            if (this.bytes != null)
            {
                this.bytes[index] = (byte) id;
            }
            else if (this.shorts != null)
            {
                this.shorts[index] = (short) id;
            }
            else
            {
                Objects.requireNonNull(this.ints)[index] = id;
            }
        }

        /** Собрать снимок; после этого сборщик не используется. */
        public VoxelSnapshot build()
        {
            this.checkOpen();
            this.built = true;

            return new VoxelSnapshot(this);
        }

        // Массивы после сборки принадлежат снимку: правка через сборщик сломала бы неизменяемость
        private void checkOpen()
        {
            if (this.built)
            {
                throw new IllegalStateException("snapshot already built");
            }
        }

        private void widen(int paletteSize)
        {
            if (this.bytes != null && paletteSize > BYTE_IDS)
            {
                short[] wider = new short[this.bytes.length];

                for (int i = 0; i < wider.length; ++i)
                {
                    wider[i] = (short) (this.bytes[i] & 0xFF);
                }

                this.shorts = wider;
                this.bytes = null;
            }

            if (this.shorts != null && paletteSize > SHORT_IDS)
            {
                int[] wider = new int[this.shorts.length];

                for (int i = 0; i < wider.length; ++i)
                {
                    wider[i] = this.shorts[i] & 0xFFFF;
                }

                this.ints = wider;
                this.shorts = null;
            }
        }
    }
}
