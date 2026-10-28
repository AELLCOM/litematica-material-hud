package com.aell.layerlist.schematics.library;

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
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Цвета блоков для миниатюр: средний цвет текстуры грани (верх и бок) из запечённой модели блока,
 * с ванильным оттенком по умолчанию (трава, листва, вода — как на равнинах). Нет модели или спрайта
 * — цвет карты. Только клиентский поток: модели и атлас меняются при перезагрузке ресурсов.
 *
 * <p>Пиксели спрайта берём так же, как {@code storage/ItemColors} (M05), — через аксессор
 * {@link IMixinSpriteContents} к исходной картинке спрайта; но без «оживления» насыщенности:
 * миниатюра должна выглядеть как постройка, а не как маркер.
 */
@Environment(EnvType.CLIENT)
final class BlockColorTable
{
    private static final int ALPHA_CUTOFF = 32;
    private static final int WHITE = 0xFFFFFF;

    private static final Map<BlockState, Long> STATES = new IdentityHashMap<>();
    private static final Map<SpriteContents, Integer> SPRITES = new IdentityHashMap<>();

    private BlockColorTable() {}

    /** По два ARGB на номер палитры снимка (верх, бок) — формат {@link ThumbnailRenderer#render}. */
    static int[] colors(VoxelSnapshot snapshot)
    {
        int[] out = new int[snapshot.paletteSize() * 2];

        for (int id = 1; id < snapshot.paletteSize(); ++id)
        {
            long packed = STATES.computeIfAbsent(snapshot.palette(id), BlockColorTable::compute);
            out[2 * id] = (int) (packed >>> 32);
            out[2 * id + 1] = (int) packed;
        }

        return out;
    }

    /** Забыть посчитанное: при закрытии экрана (до следующего раза могут смениться ресурсы). */
    static void clear()
    {
        STATES.clear();
        SPRITES.clear();
    }

    /** Верх — старшие 32 бита, бок — младшие. */
    private static long compute(BlockState state)
    {
        int top = 0;
        int side = 0;

        try
        {
            boolean fluid = ! state.getFluidState().isEmpty();

            if (state.isAir() || (state.getRenderShape() == RenderShape.INVISIBLE && ! fluid))
            {
                // Воздух, барьер, свет, пустота структуры — на миниатюре их нет
                return 0L;
            }

            Minecraft mc = Minecraft.getInstance();
            BlockStateModel model = mc.getBlockRenderer().getBlockModel(state);
            List<BlockModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);

            BlockColors tints = mc.getBlockColors();
            top = faceColor(parts, Direction.UP, state, tints);
            side = faceColor(parts, Direction.SOUTH, state, tints);

            if (side == 0)
            {
                side = faceColor(parts, Direction.EAST, state, tints);
            }

            if (top == 0 && side == 0)
            {
                // Жидкости и «особые» блоки (сундуки, шалкеры) без граней в модели — по частице
                TextureAtlasSprite particle = model.particleIcon();
                int color = particle == null ? 0 : spriteColor(particle);
                top = color == 0 ? 0 : tinted(color, tint(state, 0, tints));
            }
        }
        catch (RuntimeException | LinkageError error)
        {
            top = 0;
            side = 0;
        }

        if (top == 0 && side == 0)
        {
            top = ThumbnailRenderer.mapColor(state);
        }

        if (top == 0)
        {
            top = side;
        }

        if (side == 0)
        {
            side = top;
        }

        return ((long) top << 32) | (side & 0xFFFFFFFFL);
    }

    /** Цвет грани: первая четверть модели, смотрящая в эту сторону (из отсекаемых или общих). */
    private static int faceColor(List<BlockModelPart> parts, Direction direction, BlockState state, BlockColors tints)
    {
        BakedQuad quad = null;

        for (BlockModelPart part : parts)
        {
            quad = first(part.getQuads(direction), null);

            if (quad == null)
            {
                quad = first(part.getQuads(null), direction);
            }

            if (quad != null)
            {
                break;
            }
        }

        if (quad == null)
        {
            return 0;
        }

        int color = spriteColor(quad.sprite());

        if (color == 0)
        {
            return 0;
        }

        return quad.isTinted() ? tinted(color, tint(state, quad.tintIndex(), tints)) : color;
    }

    private static @Nullable BakedQuad first(List<BakedQuad> quads, @Nullable Direction facing)
    {
        for (BakedQuad quad : quads)
        {
            if (facing == null || quad.direction() == facing)
            {
                return quad;
            }
        }

        return null;
    }

    private static int tint(BlockState state, int layer, BlockColors tints)
    {
        if (layer < 0)
        {
            return WHITE;
        }

        // 1.21.11: оттенок без мира — BlockColors.getColor с пустыми миром и позицией (как у предметов-блоков)
        int color = tints.getColor(state, null, null, layer);

        // -1 — «слой без оттенка» (у лепестков и т. п.)
        return color == -1 ? WHITE : color & WHITE;
    }

    private static int tinted(int argb, int rgb)
    {
        int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 0xFF;
        int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 0xFF;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 0xFF;

        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Средний цвет первого кадра спрайта по непрозрачным пикселям; 0 — пикселей нет. */
    private static int spriteColor(@Nullable TextureAtlasSprite sprite)
    {
        if (sprite == null)
        {
            return 0;
        }

        SpriteContents contents = sprite.contents();
        Integer known = SPRITES.get(contents);

        if (known != null)
        {
            return known;
        }

        int color = 0;
        NativeImage image = ((IMixinSpriteContents) (Object) contents).layerlist$originalImage();

        if (image != null)
        {
            // Анимированный спрайт — полоса кадров; берём только первый кадр
            int width = Math.min(contents.width(), image.getWidth());
            int height = Math.min(contents.height(), image.getHeight());
            long r = 0L;
            long g = 0L;
            long b = 0L;
            long n = 0L;

            for (int y = 0; y < height; ++y)
            {
                for (int x = 0; x < width; ++x)
                {
                    int pixel = image.getPixel(x, y);

                    if (((pixel >>> 24) & 0xFF) < ALPHA_CUTOFF)
                    {
                        continue;
                    }

                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    ++n;
                }
            }

            if (n > 0)
            {
                color = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }

        SPRITES.put(contents, color);

        return color;
    }
}
