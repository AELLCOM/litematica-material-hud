package com.aell.layerlist.schematics.preview;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Сетка зон по габариту схемы — для черновика разбиения на превью, пока его не применили. Та же раскладка,
 * что у Zone Focus ({@code ZoneManager.slices} и {@code FileZones.localGrid}): отрезок делится на n долей,
 * остаток раздаётся первым долям по одному; порядок — Y внешний, потом Z, потом X. Номера черновика
 * поэтому совпадут с номерами после «Разбить».
 */
@Environment(EnvType.CLIENT)
public final class ZoneGrid
{
    private ZoneGrid() {}

    public static int[][] slices(int min, int max, int n)
    {
        int length = max - min + 1;
        n = Math.max(1, Math.min(n, length));
        int base = length / n;
        int remainder = length % n;
        int[][] result = new int[n][2];
        int start = min;

        for (int i = 0; i < n; ++i)
        {
            int size = base + (i < remainder ? 1 : 0);
            result[i][0] = start;
            result[i][1] = start + size - 1;
            start += size;
        }

        return result;
    }

    /** Зоны {minX, minY, minZ, maxX, maxY, maxZ} (включительно) в блоках схемы от угла 0. */
    public static int[][] local(int sizeX, int sizeY, int sizeZ, int nx, int ny, int nz)
    {
        int[][] xs = slices(0, Math.max(0, sizeX - 1), nx);
        int[][] ys = slices(0, Math.max(0, sizeY - 1), ny);
        int[][] zs = slices(0, Math.max(0, sizeZ - 1), nz);
        List<int[]> out = new ArrayList<>(xs.length * ys.length * zs.length);

        for (int[] y : ys)
        {
            for (int[] z : zs)
            {
                for (int[] x : xs)
                {
                    out.add(new int[] { x[0], y[0], z[0], x[1], y[1], z[1] });
                }
            }
        }

        return out.toArray(new int[0][]);
    }
}
