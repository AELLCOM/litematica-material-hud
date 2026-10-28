package com.aell.layerlist.schematics.area;

import com.mojang.blaze3d.platform.NativeImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Цвет блока на карте сверху: средний цвет текстуры его верхней грани (из ресурсов игрока, с учётом
 * ресурспаков), умноженный на окраску биома, если грань окрашиваемая (трава, листва, лозы…).
 * Блоки без модели (жидкости, особые) — цвет карты из самой игры.
 *
 * <p>Перенесено из нашего мода Bobby Map (тот же автор, свой код) — 2D-карта вкладки «Выделение» в тех же цветах.
 * Зависимости от Bobby Map нет.
 */
public final class MapColors {
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/area-map");

    private MapColors() {
    }

    /** Что известно о блоке без биома: средний цвет и индекс окраски (-1 — не окрашивается). */
    private record Face(int argb, int tintIndex) {
    }

    private static final Map<BlockState, Face> FACES = new ConcurrentHashMap<>();
    private static final Map<Identifier, Integer> SPRITES = new ConcurrentHashMap<>();
    private static final Map<Biome, Map<BlockState, Integer>> TINTED = new ConcurrentHashMap<>();
    private static volatile BlockStateModelSet models;

    /** Сбросить всё — после перезагрузки ресурсов. */
    public static void clear() {
        FACES.clear();
        SPRITES.clear();
        TINTED.clear();
        models = null;
    }

    /** Подпись набора ресурсов: если поменялась, кэш тайлов на диске надо пересчитать. */
    public static int signature() {
        return String.join("|", Minecraft.getInstance().getResourcePackRepository().getSelectedIds()).hashCode();
    }

    public static boolean isWater(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.BUBBLE_COLUMN || b == Blocks.KELP || b == Blocks.KELP_PLANT
                || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS;
    }

    /** Блоки, которых на карте не видно: воздух и служебные. */
    public static boolean isInvisible(BlockState s) {
        Block b = s.getBlock();
        return s.isAir() || b == Blocks.BARRIER || b == Blocks.LIGHT || b == Blocks.STRUCTURE_VOID;
    }

    /** Цвет воды в биоме (непрозрачный). */
    public static int water(Biome biome) {
        return 0xFF000000 | biome.getWaterColor();
    }

    /** Цвет блока в биоме, ARGB с альфой 255. */
    public static int color(BlockState state, Biome biome) {
        Face face = FACES.computeIfAbsent(state, MapColors::face);
        if (face.tintIndex < 0 || biome == null) return face.argb;
        return TINTED.computeIfAbsent(biome, b -> new ConcurrentHashMap<>())
                .computeIfAbsent(state, s -> tint(s, face, biome));
    }

    private static int tint(BlockState state, Face face, Biome biome) {
        BlockTintSource src = Minecraft.getInstance().getBlockColors().getTintSource(state, face.tintIndex);
        if (src == null) return face.argb;
        int t;
        try {
            t = src.colorInWorld(state, new MapBiomeTint(biome, state), BlockPos.ZERO);
        } catch (RuntimeException e) {
            t = src.color(state);
        }
        if (t == -1) return face.argb;
        return multiply(face.argb, t);
    }

    private static Face face(BlockState state) {
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) && state.getBlock() == Blocks.LAVA) {
            // у жидкостей нет модели блока — берём текстуру неподвижной лавы
            Integer lava = averageOf(Identifier.withDefaultNamespace("block/lava_still"));
            if (lava != null) return new Face(lava, -1);
        }
        try {
            BlockStateModelSet set = models;
            if (set == null) {
                set = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
                models = set;
            }
            BlockStateModel model = set.get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            BakedQuad quad = pick(parts);
            if (quad != null) {
                Integer avg = average(quad.materialInfo().sprite());
                if (avg != null) {
                    return new Face(avg, quad.materialInfo().isTinted() ? quad.materialInfo().tintIndex() : -1);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Bobby Map: no model colour for {}", state, e);
        }
        int map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        return new Face(0xFF000000 | map, -1);
    }

    /** Грань, которую видно сверху: обращённая вверх, иначе любая (цветы, лозы). */
    private static BakedQuad pick(List<BlockStateModelPart> parts) {
        for (BlockStateModelPart p : parts) {
            List<BakedQuad> up = p.getQuads(Direction.UP);
            if (!up.isEmpty()) return up.get(0);
        }
        BakedQuad any = null;
        for (BlockStateModelPart p : parts) {
            for (BakedQuad q : p.getQuads(null)) {
                if (q.direction() == Direction.UP) return q;
                if (any == null) any = q;
            }
        }
        if (any != null) return any;
        for (BlockStateModelPart p : parts) {
            for (Direction d : Direction.values()) {
                List<BakedQuad> qs = p.getQuads(d);
                if (!qs.isEmpty()) return qs.get(0);
            }
        }
        return null;
    }

    private static Integer average(TextureAtlasSprite sprite) {
        return averageOf(sprite.contents().name());
    }

    private static Integer averageOf(Identifier name) {
        Integer cached = SPRITES.get(name);
        if (cached != null) return cached == 0 ? null : cached;
        int result = 0;
        Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(file);
        if (res.isPresent()) {
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                int w = img.getWidth();
                int h = Math.min(img.getHeight(), w); // у анимаций кадры идут столбиком — берём первый
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int c = img.getPixel(x, y);
                        int a = c >>> 24;
                        if (a < 32) continue;
                        r += (long) ((c >> 16) & 0xFF) * a;
                        g += (long) ((c >> 8) & 0xFF) * a;
                        b += (long) (c & 0xFF) * a;
                        n += a;
                    }
                }
                if (n > 0) {
                    result = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                }
            } catch (Exception e) {
                LOGGER.debug("Bobby Map: cannot read {}", file, e);
            }
        }
        SPRITES.put(name, result);
        return result == 0 ? null : result;
    }

    public static int multiply(int argb, int rgb) {
        int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    public static int mix(int a, int b, float t) {
        float u = 1f - t;
        int r = (int) (((a >> 16) & 0xFF) * u + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * u + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * u + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
