package com.aell.layerlist.schematics.library;

import java.util.Arrays;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Миниатюра схемы без GPU: изометрия кубиками на процессоре. Камера смотрит с угла (+X, +Y, +Z),
 * на экране видны верх, грань +Z (слева) и грань +X (справа); свет слева, как в каноне иконок:
 * верх 1.0, левая 0.8, правая 0.6. Рисуем в двойном разрешении и сводим 2×2 — края гладкие.
 *
 * <p>Цвета блоков приходят готовыми (их достаёт {@link BlockColorTable} на клиентском потоке),
 * поэтому сам рендер можно гонять в фоне и проверять вне игры.
 */
@Environment(EnvType.CLIENT)
final class ThumbnailRenderer
{
    /** Сторона миниатюры. */
    static final int SIZE = 64;

    private static final int SUPER = 2;
    private static final int CANVAS = SIZE * SUPER;
    private static final double MARGIN = 3.0;

    private static final double COS30 = Math.sqrt(3.0) / 2.0;
    private static final double SIN30 = 0.5;

    private static final float LIGHT_TOP = 1.0F;
    private static final float LIGHT_LEFT = 0.8F;
    private static final float LIGHT_RIGHT = 0.6F;

    /** Меньше этого размера ребра (в пикселях холста) грань уже не растеризуем, а ставим точкой. */
    private static final double POINT_MODE = 1.5;

    private ThumbnailRenderer() {}

    /**
     * @param colors по два ARGB на номер палитры снимка: {@code [2·id]} — верх, {@code [2·id + 1]} —
     *               бока; альфа 0 — блок не рисуется и не закрывает соседей
     * @return ARGB построчно, {@link #SIZE}²
     */
    static int[] render(VoxelSnapshot snapshot, int[] colors)
    {
        int palette = snapshot.paletteSize();
        boolean[] solid = new boolean[palette];

        for (int id = 1; id < palette && 2 * id + 1 < colors.length; ++id)
        {
            solid[id] = (colors[2 * id] >>> 24) != 0 || (colors[2 * id + 1] >>> 24) != 0;
        }

        // Вписываем не габарит, а то, что в нём нарисуется: схема «один блок в пустой коробке»
        // иначе превратилась бы в точку. Отмечаем занятые слои по каждой оси; края занятой части —
        // первая и последняя отметка.
        boolean[] usedX = new boolean[snapshot.sizeX()];
        boolean[] usedY = new boolean[snapshot.sizeY()];
        boolean[] usedZ = new boolean[snapshot.sizeZ()];
        boolean any = false;

        for (int y = 0; y < usedY.length; ++y)
        {
            for (int z = 0; z < usedZ.length; ++z)
            {
                for (int x = 0; x < usedX.length; ++x)
                {
                    if (solid[snapshot.idAt(x, y, z)])
                    {
                        usedX[x] = usedY[y] = usedZ[z] = true;
                        any = true;
                    }
                }
            }
        }

        if (! any)
        {
            return new int[SIZE * SIZE];
        }

        int minX = firstMarked(usedX);
        int minY = firstMarked(usedY);
        int minZ = firstMarked(usedZ);
        int sx = lastMarked(usedX) - minX + 1;
        int sy = lastMarked(usedY) - minY + 1;
        int sz = lastMarked(usedZ) - minZ + 1;

        // Масштаб — чтобы проекция занятой части влезла в холст с полями
        double width = (sx + sz) * COS30;
        double height = (sx + sz) * SIN30 + sy;
        double unit = Math.min((CANVAS - 2 * MARGIN) / width, (CANVAS - 2 * MARGIN) / height);
        Canvas canvas = new Canvas(unit,
                (CANVAS - width * unit) / 2.0 + sz * COS30 * unit,
                (CANVAS - height * unit) / 2.0 + sy * unit);

        for (int y = 0; y < sy; ++y)
        {
            for (int z = 0; z < sz; ++z)
            {
                for (int x = 0; x < sx; ++x)
                {
                    int id = snapshot.idAt(minX + x, minY + y, minZ + z);

                    if (! solid[id])
                    {
                        continue;
                    }

                    boolean top = ! solid[snapshot.idAt(minX + x, minY + y + 1, minZ + z)];
                    boolean left = ! solid[snapshot.idAt(minX + x, minY + y, minZ + z + 1)];
                    boolean right = ! solid[snapshot.idAt(minX + x + 1, minY + y, minZ + z)];

                    if (top || left || right)
                    {
                        canvas.cube(x, y, z, top, left, right, colors[2 * id], colors[2 * id + 1]);
                    }
                }
            }
        }

        return canvas.downsample();
    }

    /** Номер первой отметки; вызывать только когда отмеченная клетка есть. */
    private static int firstMarked(boolean[] marks)
    {
        int i = 0;

        while (! marks[i])
        {
            ++i;
        }

        return i;
    }

    /** Номер последней отметки; вызывать только когда отмеченная клетка есть. */
    private static int lastMarked(boolean[] marks)
    {
        int i = marks.length - 1;

        while (! marks[i])
        {
            --i;
        }

        return i;
    }

    /** Встроенное превью Litematica (квадрат ARGB любой стороны) → {@link #SIZE}² усреднением площадей. */
    static int[] fromPreview(int[] argb, int side)
    {
        int[] out = new int[SIZE * SIZE];
        double step = (double) side / SIZE;

        for (int oy = 0; oy < SIZE; ++oy)
        {
            for (int ox = 0; ox < SIZE; ++ox)
            {
                int x0 = (int) Math.floor(ox * step);
                int y0 = (int) Math.floor(oy * step);
                int x1 = Math.max(x0 + 1, Math.min(side, (int) Math.ceil((ox + 1) * step)));
                int y1 = Math.max(y0 + 1, Math.min(side, (int) Math.ceil((oy + 1) * step)));
                Mix mix = new Mix();

                for (int y = y0; y < y1; ++y)
                {
                    for (int x = x0; x < x1; ++x)
                    {
                        mix.add(argb[y * side + x]);
                    }
                }

                out[oy * SIZE + ox] = mix.result();
            }
        }

        return out;
    }

    /** Запасной цвет блока — цвет карты (ванильный, без спрайтов); прозрачный, если у блока его нет. */
    static int mapColor(BlockState state)
    {
        try
        {
            int rgb = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;

            return rgb == 0 ? 0 : 0xFF000000 | rgb;
        }
        catch (RuntimeException error)
        {
            return 0;
        }
    }

    static int shade(int argb, float light)
    {
        int r = Math.round(((argb >> 16) & 0xFF) * light);
        int g = Math.round(((argb >> 8) & 0xFF) * light);
        int b = Math.round((argb & 0xFF) * light);

        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Холст двойного разрешения с буфером глубины (глубина = x + y + z: ближе к камере — больше). */
    private static final class Canvas
    {
        private final int[] pixels = new int[CANVAS * CANVAS];
        private final int[] depth = new int[CANVAS * CANVAS];
        private final double unit;
        private final double originX;
        private final double originY;

        // Экранные векторы рёбер куба вдоль осей X, Y, Z
        private final double xx;
        private final double xy;
        private final double zx;
        private final double zy;
        private final double yy;

        Canvas(double unit, double originX, double originY)
        {
            this.unit = unit;
            this.originX = originX;
            this.originY = originY;
            this.xx = COS30 * unit;
            this.xy = SIN30 * unit;
            this.zx = -COS30 * unit;
            this.zy = SIN30 * unit;
            this.yy = -unit;
            Arrays.fill(this.depth, Integer.MIN_VALUE);
        }

        private double screenX(double x, double z)
        {
            return this.originX + x * this.xx + z * this.zx;
        }

        private double screenY(double x, double y, double z)
        {
            return this.originY + x * this.xy + y * this.yy + z * this.zy;
        }

        void cube(int x, int y, int z, boolean top, boolean left, boolean right, int topColor, int sideColor)
        {
            int d = x + y + z;
            int topArgb = shade(topColor != 0 ? topColor : sideColor, LIGHT_TOP);
            int leftArgb = shade(sideColor != 0 ? sideColor : topColor, LIGHT_LEFT);
            int rightArgb = shade(sideColor != 0 ? sideColor : topColor, LIGHT_RIGHT);

            if (this.unit < POINT_MODE)
            {
                // Куб меньше пары пикселей: по точке в центре каждой видимой грани
                if (left)
                {
                    this.point(this.screenX(x + 0.5, z + 1.0), this.screenY(x + 0.5, y + 0.5, z + 1.0), d, leftArgb);
                }

                if (right)
                {
                    this.point(this.screenX(x + 1.0, z + 0.5), this.screenY(x + 1.0, y + 0.5, z + 0.5), d, rightArgb);
                }

                if (top)
                {
                    this.point(this.screenX(x + 0.5, z + 0.5), this.screenY(x + 0.5, y + 1.0, z + 0.5), d, topArgb);
                }

                return;
            }

            if (top)
            {
                // Верх: плоскость y+1, рёбра вдоль X и Z
                this.face(this.screenX(x, z), this.screenY(x, y + 1, z), this.xx, this.xy, this.zx, this.zy, d, topArgb);
            }

            if (left)
            {
                // Грань +Z: плоскость z+1, рёбра вдоль X и Y
                this.face(this.screenX(x, z + 1), this.screenY(x, y, z + 1), this.xx, this.xy, 0.0, this.yy, d, leftArgb);
            }

            if (right)
            {
                // Грань +X: плоскость x+1, рёбра вдоль Z и Y
                this.face(this.screenX(x + 1, z), this.screenY(x + 1, y, z), this.zx, this.zy, 0.0, this.yy, d, rightArgb);
            }
        }

        private void point(double px, double py, int d, int argb)
        {
            this.plot((int) Math.floor(px), (int) Math.floor(py), d, argb);
        }

        /** Параллелограмм O + a·E1 + b·E2, a,b ∈ [0,1): пиксель внутри, если его центр внутри. */
        private void face(double ox, double oy, double e1x, double e1y, double e2x, double e2y, int d, int argb)
        {
            double det = e1x * e2y - e1y * e2x;

            if (Math.abs(det) < 1.0E-9)
            {
                return;
            }

            double minX = Math.min(Math.min(ox, ox + e1x), Math.min(ox + e2x, ox + e1x + e2x));
            double maxX = Math.max(Math.max(ox, ox + e1x), Math.max(ox + e2x, ox + e1x + e2x));
            double minY = Math.min(Math.min(oy, oy + e1y), Math.min(oy + e2y, oy + e1y + e2y));
            double maxY = Math.max(Math.max(oy, oy + e1y), Math.max(oy + e2y, oy + e1y + e2y));

            // Чуть шире единичного квадрата — чтобы между соседними гранями не светились щели
            double lo = -0.02;
            double hi = 1.02;

            for (int py = (int) Math.floor(minY); py <= (int) Math.ceil(maxY); ++py)
            {
                for (int px = (int) Math.floor(minX); px <= (int) Math.ceil(maxX); ++px)
                {
                    double dx = px + 0.5 - ox;
                    double dy = py + 0.5 - oy;
                    double a = (dx * e2y - dy * e2x) / det;
                    double b = (e1x * dy - e1y * dx) / det;

                    if (a >= lo && a < hi && b >= lo && b < hi)
                    {
                        this.plot(px, py, d, argb);
                    }
                }
            }
        }

        private void plot(int px, int py, int d, int argb)
        {
            if (px < 0 || py < 0 || px >= CANVAS || py >= CANVAS)
            {
                return;
            }

            int index = py * CANVAS + px;

            if (d >= this.depth[index])
            {
                this.depth[index] = d;
                this.pixels[index] = argb;
            }
        }

        int[] downsample()
        {
            int[] out = new int[SIZE * SIZE];

            for (int y = 0; y < SIZE; ++y)
            {
                for (int x = 0; x < SIZE; ++x)
                {
                    Mix mix = new Mix();

                    for (int dy = 0; dy < SUPER; ++dy)
                    {
                        for (int dx = 0; dx < SUPER; ++dx)
                        {
                            mix.add(this.pixels[(y * SUPER + dy) * CANVAS + x * SUPER + dx]);
                        }
                    }

                    out[y * SIZE + x] = mix.result();
                }
            }

            return out;
        }
    }

    /** Среднее ARGB с учётом прозрачности: цвет взвешен альфой, альфа — простым средним. */
    private static final class Mix
    {
        private long r;
        private long g;
        private long b;
        private long a;
        private int count;

        void add(int argb)
        {
            int alpha = (argb >>> 24) & 0xFF;
            this.r += (long) ((argb >> 16) & 0xFF) * alpha;
            this.g += (long) ((argb >> 8) & 0xFF) * alpha;
            this.b += (long) (argb & 0xFF) * alpha;
            this.a += alpha;
            ++this.count;
        }

        int result()
        {
            if (this.a == 0 || this.count == 0)
            {
                return 0;
            }

            int alpha = (int) Math.round((double) this.a / this.count);

            return (alpha << 24)
                    | ((int) (this.r / this.a) << 16)
                    | ((int) (this.g / this.a) << 8)
                    | (int) (this.b / this.a);
        }
    }
}
