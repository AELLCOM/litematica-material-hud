package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Подсветка найденных хранилищ (13.10.2026, вместо ромбов-точек и рамок): мягкая полупрозрачная заливка самой
 * формы контейнера (двойной сундук — целиком), светящийся ореол из нескольких слоёв с плавным спадом прозрачности
 * и тонкий чистый контур. Подсветка еле заметно «дышит», видна и сквозь стены.
 *
 * <p>Цвет — цвет строки списка (палитра или цвет блока, «Цвета подсветки из блоков»), или один свой цвет
 * («Свой цвет подсветки», оттенок). Если в контейнере несколько искомых предметов, заливка делится на
 * горизонтальные полосы их цветов, а ореол берёт их смесь.
 */
@Environment(EnvType.CLIENT)
public class StorageHighlightRenderer
{
    private static final double LABEL_DISTANCE_SQ = 32.0 * 32.0;

    private static final double LABEL_SPACING   = 0.27;
    private static final double LABEL_GAP       = 0.4;
    private static final double LABEL_CLEARANCE = 2.2;
    private static final int    LABEL_MAX_LINES = 5;

    /** Период «дыхания» подсветки, мс. */
    private static final long PULSE_MS = 2400L;
    /** Слои ореола: насколько раздвинуты от формы, толщина линии и доля прозрачности контура. */
    private static final double[] HALO_INFLATE = { 0.035, 0.085, 0.15 };
    private static final float[] HALO_WIDTH = { 2.5F, 4.0F, 6.0F };
    private static final float[] HALO_ALPHA = { 0.55F, 0.26F, 0.11F };
    /** Насыщенность и яркость своего цвета: мягкий, не кислотный. */
    private static final float OWN_SATURATION = 0.55F;
    private static final float OWN_BRIGHTNESS = 1.0F;

    /** Режим «все сундуки»: золотая рамка снаружи ореола — чтобы отличать от поиска по списку панели. */
    private static final int ALL_FRAME_COLOR = 0xFFF5C542;
    private static final double ALL_FRAME_INFLATE = 0.22;

    private record LabelStack(double x, double z, double bottom, double top) {}

    /** Свой цвет подсветки (оттенок из настроек) — и для образца в настройках. */
    public static int ownColor()
    {
        float hue = LayerListConfigs.HIGHLIGHT_HUE.getIntegerValue() / 360.0F;
        return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, OWN_SATURATION, OWN_BRIGHTNESS);
    }

    /** Образец для настройки яркости: свой цвет или первый цвет палитры. */
    public static int sampleColor()
    {
        return LayerListConfigs.HIGHLIGHT_OWN_COLOR.getBooleanValue() ? ownColor() : StorageSearch.paletteColor(0);
    }

    /** Для самопроверки: сколько кадров рендер подсветки что-то рисовал. */
    public static int frames;

    public static void render()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null)
        {
            return;
        }

        Map<BlockPos, List<Integer>> matches = StorageSearch.getInstance().getMatches();

        if (matches.isEmpty())
        {
            return;
        }

        boolean labels = LayerListConfigs.HIGHLIGHT_LABELS.getBooleanValue();
        boolean own = LayerListConfigs.HIGHLIGHT_OWN_COLOR.getBooleanValue();
        float strength = LayerListConfigs.HIGHLIGHT_GLOW.getIntegerValue() / 100.0F;
        double phase = (Util.getMillis() % PULSE_MS) / (double) PULSE_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
        boolean allMode = AllChestsSearch.enabled();
        Vec3 playerPos = mc.player.position();

        List<Map.Entry<BlockPos, List<Integer>>> sorted = new ArrayList<>(matches.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<BlockPos, List<Integer>> e) -> e.getKey().getY())
                .thenComparingInt(e -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getZ()));

        List<LabelStack> placed = new ArrayList<>();
        ++frames;

        for (Map.Entry<BlockPos, List<Integer>> entry : sorted)
        {
            BlockPos pos = entry.getKey();
            List<Integer> rowColors = entry.getValue();
            List<Integer> colors = own ? List.of(ownColor()) : rowColors;
            AABB box = shapeOf(mc, pos);

            glow(box, colors, strength, pulse);

            if (allMode)
            {
                Gizmos.cuboid(box.inflate(ALL_FRAME_INFLATE),
                        GizmoStyle.stroke(withAlpha(ALL_FRAME_COLOR, 0.65F + 0.25F * pulse), 2.0F)).setAlwaysOnTop();
            }

            if (labels && playerPos.distanceToSqr(box.getCenter()) <= LABEL_DISTANCE_SQ)
            {
                drawLabels(rowColors, own, (box.minX + box.maxX) / 2.0, (box.minZ + box.maxZ) / 2.0,
                        box.maxY + LABEL_GAP, placed);
            }
        }
    }

    /** Форма контейнера в мире: объединение контуров всех его блоков (двойной сундук — оба). */
    private static AABB shapeOf(Minecraft mc, BlockPos pos)
    {
        AABB union = null;

        for (BlockPos part : StorageIndex.physicalBlocks(mc, pos))
        {
            AABB box;

            try
            {
                BlockState state = mc.level.getBlockState(part);
                VoxelShape shape = state.getShape(mc.level, part);
                box = shape.isEmpty() ? new AABB(part) : shape.bounds().move(part);
            }
            catch (RuntimeException e)
            {
                box = new AABB(part);
            }

            union = union == null ? box : union.minmax(box);
        }

        return union == null ? new AABB(pos) : union;
    }

    private static void glow(AABB box, List<Integer> colors, float strength, float pulse)
    {
        int count = Math.max(1, colors.size());
        int halo = soft(blend(colors));
        float breathe = 0.85F + 0.15F * pulse;

        // Заливка: мягкая, полосами по цветам (снизу вверх в порядке строк)
        float fillAlpha = (0.10F + 0.22F * strength) * breathe;
        double height = (box.maxY - box.minY) / count;

        for (int i = 0; i < count; ++i)
        {
            AABB band = new AABB(box.minX, box.minY + height * i, box.minZ, box.maxX, box.minY + height * (i + 1), box.maxZ);
            fillBox(band.deflate(0.002), withAlpha(soft(colors.get(Math.min(i, colors.size() - 1))), fillAlpha));
        }

        // Ореол: несколько раздвинутых контуров, прозрачнее наружу
        for (int layer = 0; layer < HALO_INFLATE.length; ++layer)
        {
            float alpha = HALO_ALPHA[layer] * (0.45F + 0.55F * strength) * breathe;
            Gizmos.cuboid(box.inflate(HALO_INFLATE[layer]), GizmoStyle.stroke(withAlpha(halo, alpha), HALO_WIDTH[layer]))
                    .setAlwaysOnTop();
        }

        // Чистый тонкий контур по самой форме
        Gizmos.cuboid(box.inflate(0.004), GizmoStyle.stroke(withAlpha(lighten(halo, 0.25F), 0.75F + 0.25F * strength), 1.5F))
                .setAlwaysOnTop();
    }

    /**
     * Заливка коробки. 1.21.11: кубоид-гизмо выводит грани в одном обходе, и половина их отсекается как задние —
     * рисуем каждую грань прямоугольником в обоих обходах (так же рисовались ромбы).
     */
    private static void fillBox(AABB box, int color)
    {
        Vec3[] c = {
                new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.maxX, box.minY, box.maxZ), new Vec3(box.minX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ)};
        int[][] faces = {{0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 2, 6, 5}};
        GizmoStyle style = GizmoStyle.fill(color);

        for (int[] f : faces)
        {
            Gizmos.rect(c[f[0]], c[f[1]], c[f[2]], c[f[3]], style).setAlwaysOnTop();
            Gizmos.rect(c[f[0]], c[f[3]], c[f[2]], c[f[1]], style).setAlwaysOnTop();
        }
    }

    /** Чуть светлее и мягче: цвет строки, смешанный с белым. */
    private static int soft(int color)
    {
        return lighten(color, 0.18F);
    }

    private static int lighten(int color, float amount)
    {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Средний цвет нескольких строк — для ореола контейнера с несколькими предметами. */
    private static int blend(List<Integer> colors)
    {
        if (colors.isEmpty())
        {
            return 0xFFFFFFFF;
        }

        int r = 0;
        int g = 0;
        int b = 0;

        for (int color : colors)
        {
            r += (color >> 16) & 0xFF;
            g += (color >> 8) & 0xFF;
            b += color & 0xFF;
        }

        int n = colors.size();
        return 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    private static int withAlpha(int color, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static void drawLabels(List<Integer> colors, boolean own, double x, double z, double naturalBottom,
                                   List<LabelStack> placed)
    {
        StorageSearch search = StorageSearch.getInstance();
        List<String> lines = new ArrayList<>(Math.min(colors.size(), LABEL_MAX_LINES + 1));
        List<Integer> lineColors = new ArrayList<>(lines.size());

        for (int i = 0; i < colors.size() && i < LABEL_MAX_LINES; ++i)
        {
            String text = search.labelFor(colors.get(i));

            if (! text.isEmpty())
            {
                lines.add(text);
                lineColors.add(own ? soft(ownColor()) : soft(colors.get(i)));
            }
        }

        if (lines.isEmpty())
        {
            return;
        }

        if (colors.size() > LABEL_MAX_LINES)
        {
            lines.add("+" + (colors.size() - LABEL_MAX_LINES));
            lineColors.add(0xFFFFFFFF);
        }

        double height = (lines.size() - 1) * LABEL_SPACING + LABEL_SPACING;
        double bottom = naturalBottom;

        for (boolean moved = true; moved; )
        {
            moved = false;

            for (LabelStack other : placed)
            {
                boolean nearInPlan = Math.abs(other.x() - x) < LABEL_CLEARANCE
                        && Math.abs(other.z() - z) < LABEL_CLEARANCE;

                if (nearInPlan && bottom < other.top() && bottom + height > other.bottom())
                {
                    bottom = other.top() + LABEL_SPACING * 0.5;
                    moved = true;
                }
            }
        }

        placed.add(new LabelStack(x, z, bottom, bottom + height));

        if (bottom > naturalBottom + 0.01)
        {
            int leaderColor = (lineColors.getFirst() & 0x00FFFFFF) | 0x70000000;
            Gizmos.line(new Vec3(x, naturalBottom - LABEL_GAP * 0.5, z),
                    new Vec3(x, bottom - LABEL_SPACING * 0.4, z), leaderColor, 1.0f).setAlwaysOnTop();
        }

        for (int i = 0; i < lines.size(); ++i)
        {
            Gizmos.billboardText(lines.get(i), new Vec3(x, bottom + i * LABEL_SPACING, z),
                    TextGizmo.Style.forColorAndCentered(lineColors.get(i))).setAlwaysOnTop();
        }
    }
}
