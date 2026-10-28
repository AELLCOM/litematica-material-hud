package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.shaded.ui.Paint;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

/**
 * Зоны Zone Focus поверх 3D-превью: каркас каждой зоны и её номер — те же цвета, что у зон в мире
 * (активная — циан, остальные — зелёный), черновик разбиения — синим. Рисуется в GUI поверх картинки
 * превью, а не в её текстуру: PIP перерисовывается только при смене модели или камеры.
 *
 * <p>Зоны приходят в блоках схемы от минимального угла всех регионов (как снимок библиотеки), включительно;
 * здесь переводятся в клетки снимка (огромная схема снимается грубой сеткой). Рёбра, которые смотрят от
 * зрителя, бледнее; зоны рисуются от дальних к ближним. Клик выбирает зону, чей силуэт под курсором ближе
 * всех к зрителю.
 *
 * <p>Прогресс постройки (с размещения, которое строят): каркас зоны красится по готовности — зелёный готово,
 * жёлтый начато, красный не начато, — у номера процент; активная зона остаётся циановой.
 */
@Environment(EnvType.CLIENT)
public final class ZoneOverlay
{
    /** Зелёный зон в мире ({@code ZoneRenderer} Zone Focus). */
    public static final int EDGE = 0xFF70E070;
    /** Циан активной зоны в мире. */
    public static final int ACTIVE = 0xFF50D0FF;
    /** Черновик — ещё не разбито. */
    public static final int DRAFT = 0xFF7AA8F5;

    /** Больше зон — номера только у активной и наведённой: в куче цифр ничего не прочесть. */
    private static final int MAX_LABELS = 64;
    /** Больше — рисуем только активную и наведённую: тысяча каркасов — это каша и сотни тысяч квадов. */
    private static final int MAX_BOXES = 1200;
    private static final float LINE = 0.75F;
    private static final float LINE_ACTIVE = 1.35F;

    /** Рёбра коробки: ось и какие углы по двум другим осям (0 — минимум, 1 — максимум). */
    private static final int[][] EDGES = {
            {0, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 1, 1},
            {1, 0, 0}, {1, 1, 0}, {1, 0, 1}, {1, 1, 1},
            {2, 0, 0}, {2, 1, 0}, {2, 0, 1}, {2, 1, 1}};

    private int[][] blocks = new int[0][];
    private int active = -1;
    private int @Nullable [] tones;
    private int @Nullable [] percents;
    private boolean draft;
    private int hovered = -1;

    /**
     * @param boxes  зоны {minX, minY, minZ, maxX, maxY, maxZ} в блоках схемы (включительно), по порядку номеров;
     *               {@code null} — зон нет
     * @param active номер активной зоны (с 0) или −1
     * @param tones  цвет каждой зоны (прогресс), 0 — обычный; {@code null} — все обычные
     * @param draft  черновик разбиения — всё синим, активной нет
     */
    public void set(int @Nullable [][] boxes, int active, int @Nullable [] tones, boolean draft)
    {
        this.set(boxes, active, tones, null, draft);
    }

    /** То же с процентом готовности каждой зоны (−1 — не посчитана) — он пишется у номера. */
    public void set(int @Nullable [][] boxes, int active, int @Nullable [] tones, int @Nullable [] percents, boolean draft)
    {
        this.blocks = boxes != null ? boxes : new int[0][];
        this.active = active;
        this.tones = tones;
        this.percents = percents;
        this.draft = draft;
    }

    /** Тон готовности: 100 % — зелёный, начато — жёлтый, не начато — красный; −1 — 0 (обычный цвет). */
    public static int toneOf(int percent)
    {
        return percent < 0 ? 0 : percent >= 100 ? 0xFF57C97A : percent > 0 ? 0xFFE0B341 : 0xFFE05B4A;
    }

    public boolean isEmpty()
    {
        return this.blocks.length == 0;
    }

    public int count()
    {
        return this.blocks.length;
    }

    /** Наведённая зона подсвечивается (её номер виден, даже когда номеров слишком много). */
    public void setHovered(int index)
    {
        this.hovered = index;
    }

    private record Shape(int index, float[][] corners, float depth) {}

    public void draw(GuiContext ctx, PreviewWidget widget)
    {
        if (this.blocks.length == 0 || ! widget.hasModel())
        {
            return;
        }

        List<Shape> shapes = this.project(widget);
        float[] facing = widget.facingRow();
        int[] r = widget.bounds();
        Font font = Minecraft.getInstance().font;
        boolean labels = this.blocks.length <= MAX_LABELS;

        ctx.enableScissor(r[0], r[1], r[0] + r[2], r[1] + r[3]);

        try
        {
            for (Shape shape : shapes)
            {
                boolean active = shape.index == this.active && ! this.draft;
                boolean hover = shape.index == this.hovered;
                int color = active ? ACTIVE : this.colorOf(shape.index, false);
                float width = active || hover ? LINE_ACTIVE : LINE;

                for (int[] edge : EDGES)
                {
                    float[] a = shape.corners[corner(edge[0], edge[1], edge[2], false)];
                    float[] b = shape.corners[corner(edge[0], edge[1], edge[2], true)];
                    boolean front = edgeFaces(edge, facing);
                    int alpha = front ? (active || hover ? 0xFF : 0xC8) : (active ? 0x80 : 0x46);
                    line(ctx, a[0], a[1], b[0], b[1], width, (alpha << 24) | (color & 0xFFFFFF));
                }
            }

            for (Shape shape : shapes)
            {
                boolean active = shape.index == this.active && ! this.draft;

                if (labels || active || shape.index == this.hovered)
                {
                    this.label(ctx, font, shape, active ? ACTIVE : this.colorOf(shape.index, false),
                            this.colorOf(shape.index, false));
                }
            }
        }
        finally
        {
            ctx.disableScissor();
        }
    }

    /** Номер зоны под точкой экрана или −1: из силуэтов под курсором — ближайший к зрителю. */
    public int pick(PreviewWidget widget, double mouseX, double mouseY)
    {
        if (this.blocks.length == 0 || ! widget.hasModel())
        {
            return -1;
        }

        int[] r = widget.bounds();

        if (mouseX < r[0] || mouseY < r[1] || mouseX >= r[0] + r[2] || mouseY >= r[1] + r[3])
        {
            return -1;
        }

        int best = -1;
        float bestDepth = -Float.MAX_VALUE;

        for (Shape shape : this.project(widget))
        {
            if (shape.depth > bestDepth && inHull(shape.corners, (float) mouseX, (float) mouseY))
            {
                best = shape.index;
                bestDepth = shape.depth;
            }
        }

        return best;
    }

    private int colorOf(int index, boolean active)
    {
        if (this.draft)
        {
            return DRAFT;
        }

        if (this.tones != null && index < this.tones.length && this.tones[index] != 0)
        {
            return this.tones[index];
        }

        return active ? ACTIVE : EDGE;
    }

    /** Углы каждой зоны на экране; от дальних к ближним. */
    private List<Shape> project(PreviewWidget widget)
    {
        float scale = Math.max(1, widget.sourceScale());
        List<Shape> out = new ArrayList<>(this.blocks.length);
        boolean all = this.blocks.length <= MAX_BOXES;

        for (int i = 0; i < this.blocks.length; ++i)
        {
            if (! all && i != this.active && i != this.hovered)
            {
                continue;
            }

            int[] b = this.blocks[i];
            float[] lo = { b[0] / scale, b[1] / scale, b[2] / scale };
            float[] hi = { (b[3] + 1) / scale, (b[4] + 1) / scale, (b[5] + 1) / scale };
            float[][] corners = new float[8][];
            float depth = 0.0F;

            for (int c = 0; c < 8; ++c)
            {
                float[] p = widget.project((c & 1) == 0 ? lo[0] : hi[0], (c & 2) == 0 ? lo[1] : hi[1],
                        (c & 4) == 0 ? lo[2] : hi[2]);

                if (p == null)
                {
                    return List.of();
                }

                corners[c] = p;
                depth += p[2];
            }

            out.add(new Shape(i, corners, depth / 8.0F));
        }

        out.sort((a, b) -> Float.compare(a.depth, b.depth));
        return out;
    }

    /** Номер угла: бит 1 — X, 2 — Y, 4 — Z; {@code far} — конец ребра по его оси. */
    private static int corner(int axis, int u, int v, boolean far)
    {
        int[] bits = new int[3];
        bits[axis] = far ? 1 : 0;
        bits[(axis + 1) % 3] = u;
        bits[(axis + 2) % 3] = v;

        return bits[0] | bits[1] << 1 | bits[2] << 2;
    }

    /** Смотрит ли к зрителю хоть одна из двух граней, которые сходятся на ребре. */
    private static boolean edgeFaces(int[] edge, float[] facing)
    {
        int first = (edge[0] + 1) % 3;
        int second = (edge[0] + 2) % 3;
        float a = edge[1] == 0 ? -facing[first] : facing[first];
        float b = edge[2] == 0 ? -facing[second] : facing[second];

        return a > 1.0E-4F || b > 1.0E-4F;
    }

    /**
     * Номер — в середине верхней грани зоны: у высоких зон центры коробок сбиваются в кучу посередине
     * модели, а верхние грани в изометрии разнесены, как клетки карты.
     */
    private void label(GuiContext ctx, Font font, Shape shape, int color, int tone)
    {
        float cx = 0.0F;
        float cy = 0.0F;

        // Верхняя грань — углы с битом Y (2, 3, 6, 7)
        for (int c : new int[] { 2, 3, 6, 7 })
        {
            cx += shape.corners[c][0];
            cy += shape.corners[c][1];
        }

        cx /= 4.0F;
        cy /= 4.0F;
        String text = String.valueOf(shape.index + 1);
        int percent = this.percents != null && shape.index < this.percents.length ? this.percents[shape.index] : -1;
        String suffix = percent >= 0 ? " " + percent + "%" : "";
        int tw = font.width(text) + font.width(suffix);
        int x = Math.round(cx - tw / 2.0F);
        int y = Math.round(cy - 4.0F);
        Paint.fill(ctx, x - 3, y - 2, x + tw + 2, y + 9, 0xC8101014);
        Paint.fill(ctx, x - 3, y + 9, x + tw + 2, y + 10, (0x90 << 24) | (color & 0xFFFFFF));
        Paint.text(ctx, font, text, x, y, 0xFF000000 | color, false);

        if (! suffix.isEmpty())
        {
            Paint.text(ctx, font, suffix, x + font.width(text), y, 0xFF000000 | tone, false);
        }
    }

    /** Отрезок толщиной {@code width} пикселей GUI: единичный квадрат, повёрнутый и растянутый позой. */
    private static void line(GuiContext ctx, float x1, float y1, float x2, float y2, float width, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.05F)
        {
            return;
        }

        ctx.pose().pushMatrix();
        ctx.pose().translate(x1, y1);
        ctx.pose().rotate((float) Math.atan2(dy, dx));
        ctx.pose().translate(0.0F, -width * 0.5F);
        ctx.pose().scale(length, width);
        ctx.fill(0, 0, 1, 1, color);
        ctx.pose().popMatrix();
    }

    /** Точка внутри выпуклой оболочки восьми углов (силуэт коробки в ортографии). */
    private static boolean inHull(float[][] points, float px, float py)
    {
        float[][] sorted = Arrays.copyOf(points, points.length);
        Arrays.sort(sorted, (a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        float[][] hull = new float[sorted.length * 2][];
        int k = 0;

        for (float[] p : sorted)
        {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0.0F)
            {
                --k;
            }

            hull[k++] = p;
        }

        for (int i = sorted.length - 2, lower = k + 1; i >= 0; --i)
        {
            float[] p = sorted[i];

            while (k >= lower && cross(hull[k - 2], hull[k - 1], p) <= 0.0F)
            {
                --k;
            }

            hull[k++] = p;
        }

        if (k < 4)
        {
            return false;
        }

        // Обход против часовой (экранная Y вниз — знак тот же для всех рёбер): точка по одну сторону
        boolean positive = false;
        boolean negative = false;

        for (int i = 0; i < k - 1; ++i)
        {
            float c = cross(hull[i], hull[i + 1], new float[] { px, py });
            positive |= c > 0.0F;
            negative |= c < 0.0F;
        }

        return ! (positive && negative);
    }

    private static float cross(float[] o, float[] a, float[] b)
    {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
}
