package com.aell.layerlist.schematics.area;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * Мир из одного блока в одном биоме — чтобы спросить у игры, как она окрасит этот блок в этом биоме,
 * не трогая настоящий мир (столбцы из кэша Bobby в мире не загружены).
 */
final class MapBiomeTint implements BlockAndTintGetter {
    private final Biome biome;
    private final BlockState state;

    MapBiomeTint(Biome biome, BlockState state) {
        this.biome = biome;
        this.state = state;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return CardinalLighting.DEFAULT;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return pos.equals(BlockPos.ZERO) ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinY() {
        return -64;
    }
}
