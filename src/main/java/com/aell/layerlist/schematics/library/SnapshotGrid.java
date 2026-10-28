package com.aell.layerlist.schematics.library;

import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Приёмник блоков для читателей схем: принимает клетки в координатах схемы (от её мин. угла) и
 * сам решает, полный это снимок или грубая сетка. Масштаб — наименьшая степень двойки, при которой
 * клеток не больше бюджета; в клетку грубой сетки попадает первый непустой блок её куба, поэтому
 * силуэт сохраняется, а пустоты не «съедают» стены.
 */
@Environment(EnvType.CLIENT)
final class SnapshotGrid
{
    /** Проверка отмены — раз на столько клеток: чаще дорого, реже — отмена «залипает». */
    private static final int CANCEL_STRIDE = 1 << 16;

    private final VoxelSnapshot.Builder builder;
    private final int scale;
    private final BooleanSupplier cancelled;
    private int sinceCheck;

    private SnapshotGrid(VoxelSnapshot.Builder builder, int scale, BooleanSupplier cancelled)
    {
        this.builder = builder;
        this.scale = scale;
        this.cancelled = cancelled;
    }

    /**
     * @param maxCells бюджет клеток снимка; не больше {@link VoxelSnapshot#MAX_VOLUME}
     * @throws SchematicReadException пустой габарит или схема не сводится даже грубой сеткой
     */
    static SnapshotGrid create(long sizeX, long sizeY, long sizeZ, long maxCells, BooleanSupplier cancelled)
            throws SchematicReadException
    {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
        {
            throw new SchematicReadException("layerlist.schematics.error.empty");
        }

        long budget = Math.min(Math.max(1L, maxCells), VoxelSnapshot.MAX_VOLUME);
        int scale = 1;

        // В double: у битого заголовка произведение сторон переполнило бы long
        while ((double) cells(sizeX, scale) * cells(sizeY, scale) * cells(sizeZ, scale) > budget)
        {
            // Габарит больше миллиона по оси — это уже не схема, а мусор в заголовке
            if (scale >= 1 << 20)
            {
                throw new SchematicReadException("layerlist.schematics.error.too_big", sizeX + "×" + sizeY + "×" + sizeZ);
            }

            scale <<= 1;
        }

        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder((int) cells(sizeX, scale), (int) cells(sizeY, scale),
                (int) cells(sizeZ, scale), scale);

        return new SnapshotGrid(builder, scale, cancelled);
    }

    private static long cells(long size, int scale)
    {
        return (size + scale - 1) / scale;
    }

    /** Номер состояния в палитре снимка; воздух (любой) и неизвестный блок — 0. */
    int id(@Nullable BlockState state)
    {
        return state == null || state.isAir() ? 0 : this.builder.id(state);
    }

    /** Блок в клетку схемы; воздух ничего не стирает (регионы могут перекрываться). */
    void put(int x, int y, int z, int id) throws CancellationException
    {
        if (++this.sinceCheck >= CANCEL_STRIDE)
        {
            this.sinceCheck = 0;

            if (this.cancelled.getAsBoolean())
            {
                throw new CancellationException();
            }
        }

        if (id == 0)
        {
            return;
        }

        if (this.scale == 1)
        {
            this.builder.setId(x, y, z, id);

            return;
        }

        int cx = x / this.scale;
        int cy = y / this.scale;
        int cz = z / this.scale;

        if (this.builder.idAt(cx, cy, cz) == 0)
        {
            this.builder.setId(cx, cy, cz, id);
        }
    }

    void unknown(long cells, Collection<String> names)
    {
        if (cells > 0)
        {
            this.builder.unknown(cells, names);
        }
    }

    VoxelSnapshot build()
    {
        return this.builder.build();
    }
}
