package com.aell.layerlist.schematics.library;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Снимок блоков из мира схем Litematica по коробке мира — для 3D «Управления зонами» Zone Focus, где участок
 * может состоять из нескольких размещений: в мире схем они уже сложены вместе, с поворотами, отражениями и
 * подрегионами, а состояния блоков повёрнуты, как у голограммы. Читается кусками на клиентском потоке
 * ({@link #step}), как сверка готовности: мир схем меняется только там же, гонок нет.
 *
 * <p>Клетки снимка идут от минимального угла коробки; у большой коробки — грубая сетка ({@link #scale()}).
 */
@Environment(EnvType.CLIENT)
public final class WorldSnapshotJob
{
    private final SnapshotGrid grid;
    private final int scale;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long total;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private long index;
    private boolean cancelled;
    private @Nullable VoxelSnapshot result;

    /**
     * @param maxCells бюджет клеток снимка ({@link SchematicLibrary#previewCells()})
     * @throws SchematicReadException пустая коробка или не сводится даже грубой сеткой
     */
    public WorldSnapshotJob(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long maxCells)
            throws SchematicReadException
    {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = maxX - minX + 1;
        this.sizeY = maxY - minY + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.grid = SnapshotGrid.create(this.sizeX, this.sizeY, this.sizeZ, maxCells, () -> this.cancelled);
        this.scale = this.grid.scale();
        this.total = (long) this.sizeX * this.sizeY * this.sizeZ;
    }

    /** Прочитать до {@code budget} блоков; истина — снимок готов ({@link #result()}). Мира схем нет — ждём. */
    public boolean step(long budget)
    {
        if (this.result != null)
        {
            return true;
        }

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (world == null || this.cancelled)
        {
            return false;
        }

        long area = (long) this.sizeX * this.sizeZ;

        while (budget > 0 && this.index < this.total)
        {
            long i = this.index++;
            --budget;
            int x = (int) (i % this.sizeX);
            int z = (int) ((i / this.sizeX) % this.sizeZ);
            int y = (int) (i / area);
            this.pos.set(this.minX + x, this.minY + y, this.minZ + z);
            BlockState state = world.getBlockState(this.pos);

            if (! state.isAir())
            {
                this.grid.put(x, y, z, this.grid.id(state));
            }
        }

        if (this.index >= this.total)
        {
            this.result = this.grid.build();
            return true;
        }

        return false;
    }

    public void cancel()
    {
        this.cancelled = true;
    }

    public @Nullable VoxelSnapshot result()
    {
        return this.result;
    }

    /** Блоков мира на клетку снимка по каждой оси. */
    public int scale()
    {
        return this.scale;
    }

    public float progress()
    {
        return this.total == 0L ? 1.0F : (float) ((double) this.index / this.total);
    }

    public BlockPos min()
    {
        return new BlockPos(this.minX, this.minY, this.minZ);
    }
}
