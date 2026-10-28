package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.library.VoxelSnapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Снимок схемы глазами тесселятора Minecraft (M23): {@code ModelBlockRenderer} и {@code FluidRenderer}
 * спрашивают соседей через этот вид, поэтому отсечение граней и AO работают внутри схемы, а всё
 * снаружи габарита — воздух.
 *
 * <p>Срез по высоте: всё выше {@code top} тоже воздух — так у верхнего видимого слоя появляются
 * верхние грани, которые в полной модели закрыты блоками сверху.
 *
 * <p>Свет не моделируем (движок света пустой, вершины всё равно получают полную яркость), блочных
 * сущностей нет. Оттенок травы, листвы и воды — как в равнинах.
 */
@Environment(EnvType.CLIENT)
final class PreviewBlockView implements BlockAndTintGetter
{
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final VoxelSnapshot snapshot;
    private final int top;
    private final Tint tint;

    PreviewBlockView(VoxelSnapshot snapshot, int top, Tint tint)
    {
        this.snapshot = snapshot;
        this.top = top;
        this.tint = tint;
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        int y = pos.getY();

        return y > this.top ? AIR : this.snapshot.get(pos.getX(), y, pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos)
    {
        return null;
    }

    @Override
    public LevelLightEngine getLightEngine()
    {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public float getShade(Direction direction, boolean shade)
    {
        return shade ? faceShade(direction) : 1.0F;
    }

    /** Затенение грани как в обычном измерении: низ 0,5, верх 1, север-юг 0,8, запад-восток 0,6. */
    static float faceShade(Direction direction)
    {
        return switch (direction)
        {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver)
    {
        return this.tint.color(resolver, pos);
    }

    @Override
    public int getHeight()
    {
        return this.snapshot.sizeY();
    }

    @Override
    public int getMinY()
    {
        return 0;
    }

    /**
     * Оттенок равнин. В мире берём настоящий биом равнин из реестра клиента — так совпадёт и с
     * ресурспаками, меняющими цвета биомов. Без мира (превью из главного меню) — по таблицам травы и
     * листвы для климата равнин, вода — стандартного цвета.
     */
    static final class Tint
    {
        /** Климат равнин: температура и влажность из определения биома. */
        private static final double PLAINS_TEMPERATURE = 0.8;
        private static final double PLAINS_DOWNFALL = 0.4;

        /** Стандартный цвет воды биомов без своей воды (равнины в их числе). */
        private static final int DEFAULT_WATER = 0xFF3F76E4;

        private final @Nullable Biome plains;

        private Tint(@Nullable Biome plains)
        {
            this.plains = plains;
        }

        /** Только с клиентского потока: читает реестр текущего мира. */
        static Tint capture()
        {
            ClientLevel level = Minecraft.getInstance().level;
            Biome plains = null;

            if (level != null)
            {
                try
                {
                    plains = level.registryAccess().lookupOrThrow(Registries.BIOME).getValue(Biomes.PLAINS);
                }
                catch (RuntimeException e)
                {
                    // Сервер без равнин в реестре — не повод ломать превью, хватит таблиц
                }
            }

            return new Tint(plains);
        }

        int color(ColorResolver resolver, BlockPos pos)
        {
            if (this.plains != null)
            {
                return resolver.getColor(this.plains, pos.getX(), pos.getZ());
            }

            if (resolver == BiomeColors.GRASS_COLOR_RESOLVER)
            {
                return GrassColor.get(PLAINS_TEMPERATURE, PLAINS_DOWNFALL);
            }

            if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER)
            {
                return FoliageColor.get(PLAINS_TEMPERATURE, PLAINS_DOWNFALL);
            }

            if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER)
            {
                return DryFoliageColor.get(PLAINS_TEMPERATURE, PLAINS_DOWNFALL);
            }

            if (resolver == BiomeColors.WATER_COLOR_RESOLVER)
            {
                return DEFAULT_WATER;
            }

            return -1;
        }
    }
}
