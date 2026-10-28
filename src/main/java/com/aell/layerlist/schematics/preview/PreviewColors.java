package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.mixin.IMixinSpriteContents;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Средний цвет блока для упрощённого вида превью (M23): средний цвет текстур его верхних граней
 * (с оттенком травы/листвы/воды), для жидкости — неподвижной текстуры, иначе цвет блока на карте.
 *
 * <p>Пиксели спрайтов читаются только с клиентского потока: при перезагрузке ресурсов картинки
 * старого атласа освобождаются на нём же, и чтение из фона могло бы попасть в освобождённую память.
 * Средние спрайтов кешируются до смены набора моделей.
 */
@Environment(EnvType.CLIENT)
final class PreviewColors
{
    /** Пиксели почти прозрачнее этого не участвуют в среднем (края листвы, решётки). */
    private static final int ALPHA_CUTOFF = 32;

    /** Блок без текстур и без цвета на карте: нейтрально-серый, чтобы куб не пропал. */
    private static final int NEUTRAL = 0xFF808080;

    private static final Map<TextureAtlasSprite, Integer> SPRITES = new IdentityHashMap<>();
    private static @Nullable BlockModelShaper spritesFor;

    private PreviewColors() {}

    /**
     * Цвета ARGB (непрозрачные) для номеров палитры из {@code ids}; остальные ячейки — 0.
     * Только клиентский поток.
     */
    static int[] compute(PreviewMesher.Context context, int[] ids)
    {
        if (spritesFor != context.models)
        {
            SPRITES.clear();
            spritesFor = context.models;
        }

        int[] colors = new int[context.snapshot.paletteSize()];
        PreviewBlockView view = new PreviewBlockView(context.snapshot, Integer.MAX_VALUE, context.tint);
        RandomSource random = RandomSource.create(42L);
        List<BlockModelPart> parts = new ArrayList<>();

        for (int id : ids)
        {
            if (id > 0 && id < colors.length && colors[id] == 0)
            {
                colors[id] = 0xFF000000 | colorOf(context, view, context.snapshot.palette(id), random, parts);
            }
        }

        return colors;
    }

    private static int colorOf(PreviewMesher.Context context, PreviewBlockView view, BlockState state,
            RandomSource random, List<BlockModelPart> parts)
    {
        int fromModel = 0;

        try
        {
            fromModel = modelColor(context.models, context.colors, view, state, random, parts);

            if (fromModel == 0)
            {
                fromModel = fluidColor(context.models, context.colors, view, state);
            }
        }
        catch (RuntimeException e)
        {
            fromModel = 0;
        }

        if (fromModel != 0)
        {
            return fromModel;
        }

        try
        {
            int map = state.getMapColor(view, BlockPos.ZERO).col;

            return map != 0 ? map : NEUTRAL;
        }
        catch (RuntimeException e)
        {
            return NEUTRAL;
        }
    }

    // Верхние грани модели: в изометрии и сверху именно они задают цвет пятна
    private static int modelColor(BlockModelShaper models, BlockColors blockColors, PreviewBlockView view,
            BlockState state, RandomSource random, List<BlockModelPart> parts)
    {
        parts.clear();
        random.setSeed(42L);
        models.getBlockModel(state).collectParts(random, parts);

        List<BakedQuad> chosen = new ArrayList<>();

        for (BlockModelPart part : parts)
        {
            chosen.addAll(part.getQuads(Direction.UP));

            for (BakedQuad quad : part.getQuads(null))
            {
                if (quad.direction() == Direction.UP)
                {
                    chosen.add(quad);
                }
            }
        }

        if (chosen.isEmpty())
        {
            for (BlockModelPart part : parts)
            {
                for (Direction direction : Direction.values())
                {
                    chosen.addAll(part.getQuads(direction));
                }

                chosen.addAll(part.getQuads(null));
            }
        }

        long r = 0L;
        long g = 0L;
        long b = 0L;
        int n = 0;

        for (BakedQuad quad : chosen)
        {
            int base = spriteAverage(quad.sprite());

            if (base == 0)
            {
                continue;
            }

            int tintIndex = quad.tintIndex();

            if (tintIndex != -1)
            {
                base = multiply(base, blockColors.getColor(state, view, BlockPos.ZERO, tintIndex));
            }

            r += (base >> 16) & 0xFF;
            g += (base >> 8) & 0xFF;
            b += base & 0xFF;
            ++n;
        }

        parts.clear();

        if (n == 0)
        {
            return 0;
        }

        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    // 1.21.11: моделей жидкостей нет — берём частицу блока жидкости (вода — still) и оттенок блока по слою 0
    private static int fluidColor(BlockModelShaper models, BlockColors blockColors, PreviewBlockView view, BlockState state)
    {
        FluidState fluid = state.getFluidState();

        if (fluid.isEmpty())
        {
            return 0;
        }

        BlockState legacy = fluid.createLegacyBlock();
        int base = spriteAverage(models.getBlockModel(legacy).particleIcon());

        if (base == 0)
        {
            return 0;
        }

        return multiply(base, blockColors.getColor(legacy, view, BlockPos.ZERO, 0));
    }

    /** Средний цвет непрозрачных пикселей спрайта, RGB; 0 — прочитать не вышло. */
    private static int spriteAverage(TextureAtlasSprite sprite)
    {
        Integer cached = SPRITES.get(sprite);

        if (cached != null)
        {
            return cached;
        }

        int average = 0;

        try
        {
            NativeImage image = ((IMixinSpriteContents) (Object) sprite.contents()).layerlist$originalImage();

            if (image != null)
            {
                average = averageOf(image);
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            average = 0;
        }

        SPRITES.put(sprite, average);

        return average;
    }

    private static int averageOf(NativeImage image)
    {
        long r = 0L;
        long g = 0L;
        long b = 0L;
        long weight = 0L;

        for (int y = 0; y < image.getHeight(); ++y)
        {
            for (int x = 0; x < image.getWidth(); ++x)
            {
                int pixel = image.getPixel(x, y);
                int alpha = (pixel >>> 24) & 0xFF;

                if (alpha < ALPHA_CUTOFF)
                {
                    continue;
                }

                r += (long) ((pixel >> 16) & 0xFF) * alpha;
                g += (long) ((pixel >> 8) & 0xFF) * alpha;
                b += (long) (pixel & 0xFF) * alpha;
                weight += alpha;
            }
        }

        if (weight == 0L)
        {
            return 0;
        }

        // Чёрный спрайт честно даёт 0 по каналам — сдвигаем на единицу, чтобы 0 значил «нет данных»
        int rgb = (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);

        return rgb != 0 ? rgb : 0x010101;
    }

    private static int multiply(int rgb, int tint)
    {
        if (tint == -1)
        {
            return rgb;
        }

        int r = ((rgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 0xFF;
        int g = ((rgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 0xFF;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 0xFF;
        int result = r << 16 | g << 8 | b;

        return result != 0 ? result : 0x010101;
    }
}
