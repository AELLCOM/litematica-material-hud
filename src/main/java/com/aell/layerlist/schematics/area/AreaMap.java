package com.aell.layerlist.schematics.area;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 2D-карта прогруженной области сверху (вкладка «Выделение», вид «2D»): верхний блок каждого столбца в цветах
 * {@link MapColors}, вода — цветом воды биома, смешанным с дном по глубине, рельеф — тенями (свет с северо-запада),
 * как в Bobby Map. Только загруженные игрой чанки; незагруженное — прозрачно. Строится по кадрам на клиентском
 * потоке ({@link #tick}).
 */
@Environment(EnvType.CLIENT)
public final class AreaMap
{
    private static final int WATER_SCAN = 64;

    private final ClientLevel level;
    private final int minX;
    private final int minZ;
    private final int side;
    private final int[] color;
    private final short[] height;
    private final byte[] depth;
    /** Высота дна столбца (под водой — первый не водяной блок): щелчок по воде выбирает дно. */
    private final short[] floor;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int row;
    private int[] pixels;

    public AreaMap(ClientLevel level, int minX, int minZ, int side)
    {
        this.level = level;
        this.minX = minX;
        this.minZ = minZ;
        this.side = side;
        int n = side * side;
        this.color = new int[n];
        this.height = new short[n];
        this.depth = new byte[n];
        this.floor = new short[n];
    }

    public int minX()
    {
        return this.minX;
    }

    public int minZ()
    {
        return this.minZ;
    }

    public int side()
    {
        return this.side;
    }

    public boolean done()
    {
        return this.pixels != null;
    }

    public float progress()
    {
        return this.done() ? 1.0F : this.row / (float) this.side;
    }

    /** Готовая картинка ARGB ({@code side × side}, построчно с севера), после {@link #done()}. */
    public int[] pixels()
    {
        return this.pixels;
    }

    /** Высота дна столбца в мире; {@code Integer.MIN_VALUE} — столбец не загружен. */
    public int floorY(int worldX, int worldZ)
    {
        int x = worldX - this.minX;
        int z = worldZ - this.minZ;

        if (x < 0 || z < 0 || x >= this.side || z >= this.side || this.color[z * this.side + x] == 0)
        {
            return Integer.MIN_VALUE;
        }

        return this.floor[z * this.side + x];
    }

    public boolean tick(long budgetNanos)
    {
        if (this.done())
        {
            return true;
        }

        long until = System.nanoTime() + budgetNanos;

        while (this.row < this.side && System.nanoTime() < until)
        {
            for (int x = 0; x < this.side; ++x)
            {
                this.column(x, this.row);
            }

            ++this.row;
        }

        if (this.row >= this.side)
        {
            this.shade();
        }

        return this.done();
    }

    private void column(int x, int z)
    {
        int wx = this.minX + x;
        int wz = this.minZ + z;
        int i = z * this.side + x;

        if (! this.level.hasChunk(wx >> 4, wz >> 4))
        {
            return;
        }

        int top = this.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
        int waterY = Integer.MIN_VALUE;
        int water = 0;
        Biome waterBiome = null;

        for (int y = top; y >= Math.max(this.level.getMinY(), top - WATER_SCAN); --y)
        {
            this.pos.set(wx, y, wz);
            BlockState state = this.level.getBlockState(this.pos);

            if (MapColors.isInvisible(state))
            {
                continue;
            }

            if (MapColors.isWater(state))
            {
                if (waterY == Integer.MIN_VALUE)
                {
                    waterY = y;
                    waterBiome = this.level.getBiome(this.pos).value();
                }

                ++water;
                continue;
            }

            int c = MapColors.color(state, this.level.getBiome(this.pos).value());

            if (waterY != Integer.MIN_VALUE)
            {
                this.color[i] = MapColors.mix(c, MapColors.water(waterBiome), Math.min(0.9F, 0.5F + water * 0.035F));
                this.height[i] = (short) waterY;
                this.depth[i] = (byte) Math.min(127, water);
            }
            else
            {
                this.color[i] = c;
                this.height[i] = (short) y;
            }

            this.floor[i] = (short) y;
            return;
        }

        if (waterY != Integer.MIN_VALUE)
        {
            this.color[i] = MapColors.water(waterBiome);
            this.height[i] = (short) waterY;
            this.depth[i] = (byte) Math.min(127, water);
            this.floor[i] = (short) (waterY - water);
        }
    }

    /** Тени рельефа (формула карты Bobby Map): склон к северо-западу светлее, от него — темнее; на воде — нет. */
    private void shade()
    {
        int n = this.side;
        int[] out = new int[n * n];

        for (int z = 0; z < n; ++z)
        {
            for (int x = 0; x < n; ++x)
            {
                int i = z * n + x;
                int c = this.color[i];

                if (c == 0 || this.depth[i] != 0)
                {
                    out[i] = c;
                    continue;
                }

                int h = this.height[i];
                int hn = z > 0 && this.color[i - n] != 0 && this.depth[i - n] == 0 ? this.height[i - n] : h;
                int hw = x > 0 && this.color[i - 1] != 0 && this.depth[i - 1] == 0 ? this.height[i - 1] : h;
                int d = (h - hn) + (h - hw);
                out[i] = d == 0 ? c : scale(c, Math.max(0.72F, Math.min(1.22F, 1.0F + d * 0.055F)));
            }
        }

        this.pixels = out;
    }

    private static int scale(int c, float f)
    {
        int r = Math.min(255, (int) (((c >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((c >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((c & 0xFF) * f));
        return (c & 0xFF000000) | r << 16 | g << 8 | b;
    }
}
