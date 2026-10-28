package com.aell.layerlist.schematics.area;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Подсчёт блоков в компонентах выделения (кнопка «Анализ области»): по кадрам на клиентском потоке, воздух
 * пропускается, блоки незагруженных чанков не считаются (их число — {@link #unloaded()}).
 */
@Environment(EnvType.CLIENT)
public final class AreaCount
{
    private final ClientLevel level;
    private final List<BoundingBox> boxes;
    private final Object2IntOpenHashMap<BlockState> counts = new Object2IntOpenHashMap<>();
    private final long total;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int box;
    private int x;
    private int y;
    private int z;
    private long done;
    private long unloaded;

    private AreaCount(ClientLevel level, List<BoundingBox> boxes)
    {
        this.level = level;
        this.boxes = List.copyOf(boxes);
        long sum = 0L;

        for (BoundingBox b : this.boxes)
        {
            sum += (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
        }

        this.total = sum;

        if (! this.boxes.isEmpty())
        {
            this.reset(this.boxes.getFirst());
        }
    }

    public static AreaCount start(ClientLevel level, List<BoundingBox> boxes)
    {
        return new AreaCount(level, boxes);
    }

    private void reset(BoundingBox b)
    {
        this.x = b.minX();
        this.y = b.minY();
        this.z = b.minZ();
    }

    /** Считать, пока не выйдет {@code budgetNanos}; {@code true} — готово. */
    public boolean tick(long budgetNanos)
    {
        long until = System.nanoTime() + budgetNanos;

        while (this.box < this.boxes.size())
        {
            BoundingBox b = this.boxes.get(this.box);

            for (int i = 0; i < 4096; ++i)
            {
                this.pos.set(this.x, this.y, this.z);

                if (! this.level.hasChunk(this.x >> 4, this.z >> 4))
                {
                    ++this.unloaded;
                }
                else
                {
                    BlockState state = this.level.getBlockState(this.pos);

                    if (! state.isAir())
                    {
                        this.counts.addTo(state, 1);
                    }
                }

                ++this.done;

                if (++this.x > b.maxX())
                {
                    this.x = b.minX();

                    if (++this.z > b.maxZ())
                    {
                        this.z = b.minZ();

                        if (++this.y > b.maxY())
                        {
                            ++this.box;

                            if (this.box < this.boxes.size())
                            {
                                this.reset(this.boxes.get(this.box));
                            }

                            break;
                        }
                    }
                }
            }

            if (System.nanoTime() >= until)
            {
                return this.box >= this.boxes.size();
            }
        }

        return true;
    }

    public float progress()
    {
        return this.total == 0L ? 1.0F : Math.min(1.0F, this.done / (float) this.total);
    }

    public Object2IntOpenHashMap<BlockState> counts()
    {
        return this.counts;
    }

    public long unloaded()
    {
        return this.unloaded;
    }
}
