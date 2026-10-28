package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.library.VoxelSnapshot;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * Готовая к показу модель превью (M23): квады по слоям и секциям 16³, упрощённая сетка для
 * больших схем, позиции особых блоков (сундуки, шалкеры, флаги, головы…). После публикации из
 * фона не меняется; PIP-рендерер только читает её на клиентском потоке.
 *
 * <p>Координаты вершин — в системе схемы от (0,0,0), уже со сдвигом блока; цвет вершины — с оттенком,
 * затенением грани и AO, свет не хранится (в окне GUI всё равно полная яркость).
 */
@Environment(EnvType.CLIENT)
public final class PreviewModel
{
    static final int SOLID = 0;
    static final int CUTOUT = 1;
    static final int TRANSLUCENT = 2;
    static final int LAYERS = 3;

    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    /** Почему модель пуста: для строки статуса. */
    enum Empty
    {
        NONE,
        NO_BLOCKS,
        BELOW_CUT
    }

    final long id = NEXT_ID.getAndIncrement();
    final VoxelSnapshot snapshot;
    final float centerX;
    final float centerY;
    final float centerZ;
    final float radius;
    final List<Section> sections;
    final @Nullable Quads flat;
    final int lodFactor;
    final int quadCount;
    final int specialCount;
    final int cut;
    final int budget;
    final Empty empty;
    /** Подпись готовности по блокам, с которой собрана модель (0 — без неё). */
    long statusSig;
    /**
     * Вершины слоёв, уже упакованные в формат блоков ({@link #pack}, фон сборки): GPU-путь PIP только копирует их в
     * видеопамять и отпускает. {@code null} — не паковали или уже загружено.
     */
    volatile java.nio.@Nullable ByteBuffer[] packed;
    final int[] packedQuads = new int[LAYERS + 1];

    // Только клиентский поток: состояния особых моделей по номеру палитры, создаются при первом показе
    private final Map<Integer, Special> specialStates = new HashMap<>();

    PreviewModel(VoxelSnapshot snapshot, List<Section> sections, @Nullable Quads flat, int lodFactor,
            int specialCount, int cut, int budget, Empty empty)
    {
        this.snapshot = snapshot;
        this.centerX = snapshot.sizeX() * 0.5F;
        this.centerY = snapshot.sizeY() * 0.5F;
        this.centerZ = snapshot.sizeZ() * 0.5F;
        this.radius = 0.5F * (float) Math.sqrt((double) snapshot.sizeX() * snapshot.sizeX()
                + (double) snapshot.sizeY() * snapshot.sizeY() + (double) snapshot.sizeZ() * snapshot.sizeZ()) + 1.0F;
        this.sections = List.copyOf(sections);
        this.flat = flat;
        this.lodFactor = lodFactor;
        this.specialCount = specialCount;
        this.cut = cut;
        this.budget = budget;
        this.empty = empty;

        int quads = flat != null ? flat.count : 0;

        for (Section section : this.sections)
        {
            quads += section.quads;
        }

        this.quadCount = quads;
    }

    public long id()
    {
        return this.id;
    }

    public int quads()
    {
        return this.quadCount;
    }

    /** 1 — каждый блок как в игре; 2, 4… — упрощённый вид кубами такого размера. */
    public int lodFactor()
    {
        return this.lodFactor;
    }

    public boolean simplified()
    {
        return this.lodFactor > 1;
    }

    public boolean isEmpty()
    {
        return this.quadCount == 0 && this.specialCount == 0;
    }

    boolean hasLayer(int layer)
    {
        for (Section section : this.sections)
        {
            Quads quads = section.layers[layer];

            if (quads != null && quads.count > 0)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Упаковать вершины слоёв (три слоя блоков и одноцветные кубы — индекс {@link #LAYERS}) в формат блоков, в фоне
     * сборки: основной поток потом только копирует их в видеопамять — без заминки на сотнях тысяч квадов.
     */
    void pack()
    {
        java.nio.ByteBuffer[] out = new java.nio.ByteBuffer[LAYERS + 1];

        for (int layer = 0; layer <= LAYERS; ++layer)
        {
            List<Quads> parts = new java.util.ArrayList<>();

            if (layer == LAYERS)
            {
                if (this.flat != null && this.flat.count > 0)
                {
                    parts.add(this.flat);
                }
            }
            else
            {
                for (Section section : this.sections)
                {
                    Quads quads = section.layers[layer];

                    if (quads != null && quads.count > 0)
                    {
                        parts.add(quads);
                    }
                }
            }

            int total = 0;

            for (Quads quads : parts)
            {
                total += quads.count;
            }

            if (total > 0)
            {
                out[layer] = packVertices(parts, total);
                this.packedQuads[layer] = total;
            }
        }

        this.packed = out;
    }

    /** Квады — вершинами формата блоков (позиция, цвет, UV, свет) в обычный прямой буфер (его забирает сборщик мусора). */
    static java.nio.ByteBuffer packVertices(List<Quads> parts, int total)
    {
        com.mojang.blaze3d.vertex.VertexFormat format = com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK;

        try (com.mojang.blaze3d.vertex.ByteBufferBuilder bytes = com.mojang.blaze3d.vertex.ByteBufferBuilder.exactlySized(
                total * 4 * format.getVertexSize()))
        {
            com.mojang.blaze3d.vertex.BufferBuilder builder = new com.mojang.blaze3d.vertex.BufferBuilder(bytes,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, format);

            for (Quads quads : parts)
            {
                float[] pos = quads.pos;
                float[] uv = quads.uv;
                int[] color = quads.color;

                for (int v = 0, n = quads.count * 4; v < n; ++v)
                {
                    builder.addVertex(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], color[v], uv[v * 2], uv[v * 2 + 1],
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                            0.0F, 1.0F, 0.0F);
                }
            }

            try (com.mojang.blaze3d.vertex.MeshData mesh = builder.buildOrThrow())
            {
                java.nio.ByteBuffer source = mesh.vertexBuffer();
                java.nio.ByteBuffer copy = java.nio.ByteBuffer.allocateDirect(source.remaining()).order(java.nio.ByteOrder.nativeOrder());
                copy.put(source).flip();
                return copy;
            }
        }
    }

    /**
     * Особый блок для рисования (1.21.11): сам блок и поворот вокруг вертикали. Особые модели 1.21.11 знают
     * только блок ({@code SpecialBlockModelRenderer.renderByBlock}), а не состояние, — сторону задаём поворотом.
     */
    record Special(net.minecraft.world.level.block.Block block, float yRot) {}

    /** Особая модель для номера палитры; только с клиентского потока (рендер PIP). */
    @Nullable Special specialState(int paletteId)
    {
        Special known = this.specialStates.get(paletteId);

        if (known != null || this.specialStates.containsKey(paletteId))
        {
            return known;
        }

        Special result = null;

        try
        {
            net.minecraft.world.level.block.state.BlockState state = this.snapshot.palette(paletteId);
            float yRot = 0.0F;

            if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
            {
                yRot = 180.0F - state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).toYRot();
            }
            else if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16))
            {
                yRot = 180.0F - state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16) * 22.5F;
            }

            result = new Special(state.getBlock(), yRot);
        }
        catch (RuntimeException e)
        {
            result = null;
        }

        this.specialStates.put(paletteId, result);

        return result;
    }

    /**
     * Секция 16³ схемы: квады по слоям ({@link #SOLID}, {@link #CUTOUT}, {@link #TRANSLUCENT}) и особые
     * блоки четвёрками {@code x, y, z, номер палитры}. Неизменяема после сборки, поэтому одну и ту же
     * секцию разделяют модели с разным срезом.
     */
    static final class Section
    {
        final @Nullable Quads[] layers;
        final int[] specials;
        final int quads;

        Section(@Nullable Quads[] layers, int[] specials)
        {
            this.layers = layers;
            this.specials = specials;

            int total = 0;

            for (Quads layer : layers)
            {
                total += layer != null ? layer.count : 0;
            }

            this.quads = total;
        }

        boolean isEmpty()
        {
            return this.quads == 0 && this.specials.length == 0;
        }
    }

    /**
     * Плотный буфер квадов одного слоя: на квад 4 вершины (позиция, UV, цвет) и упакованная внешняя
     * нормаль — по ней PIP пропускает грани, повёрнутые от зрителя, ещё до записи вершин.
     */
    static final class Quads
    {
        static final int NORMAL_NONE = 0;

        private static final int NORMAL_VALID = 1 << 24;
        private static final float NORMAL_SCALE = 127.0F;
        private static final float MIN_AREA = 1.0E-8F;

        float[] pos;
        float[] uv;
        int[] color;
        int[] normal;
        int count;

        /** +1 — у ванильных граней правая нормаль (по правилу правой руки) смотрит наружу, −1 — внутрь. */
        private final int windingSign;

        Quads(int windingSign, int capacity)
        {
            int quads = Math.max(4, capacity);

            this.windingSign = windingSign;
            this.pos = new float[quads * 12];
            this.uv = new float[quads * 8];
            this.color = new int[quads * 4];
            this.normal = new int[quads];
        }

        /** @param p 12 координат, @param t 8 текстурных координат, @param c 4 цвета ARGB — вершины по порядку */
        void put(float[] p, float[] t, int[] c)
        {
            if (this.count == this.normal.length)
            {
                int grown = this.count * 2;
                this.pos = Arrays.copyOf(this.pos, grown * 12);
                this.uv = Arrays.copyOf(this.uv, grown * 8);
                this.color = Arrays.copyOf(this.color, grown * 4);
                this.normal = Arrays.copyOf(this.normal, grown);
            }

            System.arraycopy(p, 0, this.pos, this.count * 12, 12);
            System.arraycopy(t, 0, this.uv, this.count * 8, 8);
            System.arraycopy(c, 0, this.color, this.count * 4, 4);
            this.normal[this.count] = this.packNormal(p);
            ++this.count;
        }

        /** Обрезать запас: секции живут в кеше, лишняя ёмкость — лишние мегабайты. */
        void trim()
        {
            if (this.count < this.normal.length)
            {
                this.pos = Arrays.copyOf(this.pos, this.count * 12);
                this.uv = Arrays.copyOf(this.uv, this.count * 8);
                this.color = Arrays.copyOf(this.color, this.count * 4);
                this.normal = Arrays.copyOf(this.normal, this.count);
            }
        }

        static float normalX(int packed)
        {
            return (byte) packed / NORMAL_SCALE;
        }

        static float normalY(int packed)
        {
            return (byte) (packed >> 8) / NORMAL_SCALE;
        }

        static float normalZ(int packed)
        {
            return (byte) (packed >> 16) / NORMAL_SCALE;
        }

        // Нормаль по диагоналям: верна и для чуть неплоских граней (жидкость с наклоном)
        private int packNormal(float[] p)
        {
            float ax = p[6] - p[0];
            float ay = p[7] - p[1];
            float az = p[8] - p[2];
            float bx = p[9] - p[3];
            float by = p[10] - p[4];
            float bz = p[11] - p[5];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

            if (length < MIN_AREA)
            {
                return NORMAL_NONE;
            }

            float k = this.windingSign * NORMAL_SCALE / length;

            return NORMAL_VALID
                    | (Math.round(nx * k) & 0xFF)
                    | ((Math.round(ny * k) & 0xFF) << 8)
                    | ((Math.round(nz * k) & 0xFF) << 16);
        }
    }
}
