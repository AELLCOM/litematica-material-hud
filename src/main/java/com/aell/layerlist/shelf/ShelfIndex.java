package com.aell.layerlist.shelf;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.aell.layerlist.verify.LayerVerifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

@Environment(EnvType.CLIENT)
public final class ShelfIndex
{
    public record Shelf(BlockPos pos, int filled, List<ItemStack> items)
    {
        public boolean stocked()
        {
            return this.filled > 0;
        }
    }

    private static final ShelfIndex INSTANCE = new ShelfIndex();
    private static final int REFRESH_TICKS = 10;

    private final List<Shelf> shelves = new ArrayList<>();

    private boolean active;
    private int ticks;

    private ShelfIndex() {}

    public static ShelfIndex getInstance()
    {
        return INSTANCE;
    }

    public static boolean wanted()
    {
        return LayerListConfigs.VERIFY_ENABLED.getBooleanValue()
                && LayerListConfigs.VERIFY_SHELVES.getBooleanValue()
                && LayerVerifier.getInstance().isHighlighting();
    }

    public boolean isActive()
    {
        return this.active;
    }

    public List<Shelf> shelves()
    {
        return this.shelves;
    }

    public int stockedCount()
    {
        int count = 0;

        for (Shelf shelf : this.shelves)
        {
            if (shelf.stocked())
            {
                ++count;
            }
        }

        return count;
    }

    public void onClientTick(Minecraft mc)
    {
        boolean wanted = wanted();

        if (this.active != wanted)
        {
            this.active = wanted;
            this.ticks = REFRESH_TICKS;
            this.shelves.clear();
        }

        if (! this.active)
        {
            return;
        }

        if (mc.level == null || mc.player == null)
        {
            this.shelves.clear();

            return;
        }

        if (++this.ticks < REFRESH_TICKS)
        {
            return;
        }

        this.ticks = 0;
        this.scan(mc);
    }

    private void scan(Minecraft mc)
    {
        this.shelves.clear();

        ClientLevel level = mc.level;

        if (level == null || mc.player == null)
        {
            return;
        }

        int range = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();
        double limit = (double) range * range;
        BlockPos eye = mc.player.blockPosition();
        int centreX = SectionPos.blockToSectionCoord(eye.getX());
        int centreZ = SectionPos.blockToSectionCoord(eye.getZ());
        int chunks = range / 16 + 1;
        int cap = LayerListConfigs.VERIFY_MAX_MARKERS.getIntegerValue();

        for (int cx = centreX - chunks; cx <= centreX + chunks; ++cx)
        {
            for (int cz = centreZ - chunks; cz <= centreZ + chunks; ++cz)
            {
                if (! level.hasChunk(cx, cz))
                {
                    continue;
                }

                LevelChunk chunk = level.getChunk(cx, cz);

                for (BlockEntity entity : chunk.getBlockEntities().values())
                {
                    if (! (entity instanceof ShelfBlockEntity shelf))
                    {
                        continue;
                    }

                    BlockPos pos = entity.getBlockPos();

                    if (pos.distToCenterSqr(eye.getX() + 0.5D, eye.getY() + 0.5D, eye.getZ() + 0.5D) > limit)
                    {
                        continue;
                    }

                    List<ItemStack> items = new ArrayList<>();
                    int filled = 0;

                    for (ItemStack stack : shelf.getItems())
                    {
                        if (! stack.isEmpty())
                        {
                            ++filled;
                            items.add(stack);
                        }
                    }

                    this.shelves.add(new Shelf(pos.immutable(), filled, items));

                    if (this.shelves.size() >= cap)
                    {
                        return;
                    }
                }
            }
        }
    }
}
