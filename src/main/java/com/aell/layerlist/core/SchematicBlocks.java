package com.aell.layerlist.core;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.SchematicUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Блоки включённых размещений в координатах мира — прямо из контейнеров схемы, без мира схемы
 * Litematica. Мир схемы есть только там, где Litematica построила чанки для прорисовки (рядом с
 * игроком и у размещений с включённой отрисовкой), а список материалов от прорисовки зависеть не
 * должен: здесь ответ одинаков для любой клетки схемы, как бы далеко она ни была.
 *
 * <p>Поворот и отражение — те же, что при счёте слоя ({@link LayerCountManager}); замены блоков
 * ({@code BlockReplacements}) уже в палитре контейнера. Сущностей блоков нет — для {@code canSurvive}
 * они не нужны. Только клиентский поток.
 */
@Environment(EnvType.CLIENT)
public final class SchematicBlocks implements BlockGetter
{
    private record Region(SchematicPlacement placement, SubRegionPlacement subRegion, LitematicaSchematic schematic,
                          String name, LitematicaBlockStateContainer container,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          Mirror mirrorMain, Mirror mirrorSub, Rotation rotation)
    {
        boolean contains(int x, int y, int z)
        {
            return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY
                    && z >= this.minZ && z <= this.maxZ;
        }
    }

    private final List<Region> regions = new ArrayList<>();
    private final LevelHeightAccessor height;
    private @Nullable Region last;

    public SchematicBlocks(Iterable<SchematicPlacement> placements, LevelHeightAccessor height)
    {
        this.height = height;

        for (SchematicPlacement placement : placements)
        {
            if (! placement.isEnabled())
            {
                continue;
            }

            LitematicaSchematic schematic = placement.getSchematic();

            for (Map.Entry<String, Box> entry : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet())
            {
                Box box = entry.getValue();
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(entry.getKey());
                SubRegionPlacement subRegion = placement.getRelativeSubRegionPlacement(entry.getKey());

                if (box.getPos1() == null || box.getPos2() == null || container == null || subRegion == null)
                {
                    continue;
                }

                Rotation mainRotation = placement.getRotation();
                Mirror mirrorSub = subRegion.getMirror();

                // Как в счёте слоя: при повороте на 90° отражение подрегиона меняет ось
                if (mirrorSub != Mirror.NONE
                        && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90))
                {
                    mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
                }

                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();
                this.regions.add(new Region(placement, subRegion, schematic, entry.getKey(), container,
                        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()),
                        placement.getMirror(), mirrorSub, mainRotation.getRotated(subRegion.getRotation())));
            }
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        Region hit = this.last;

        if (hit != null && hit.contains(x, y, z))
        {
            BlockState state = read(hit, pos);

            if (! state.isAir())
            {
                return state;
            }
        }

        // Размещения могут перекрываться: первый непустой блок, как у мира схемы
        for (Region region : this.regions)
        {
            if (region == hit || ! region.contains(x, y, z))
            {
                continue;
            }

            BlockState state = read(region, pos);

            if (! state.isAir())
            {
                this.last = region;
                return state;
            }
        }

        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState read(Region region, BlockPos pos)
    {
        BlockPos at = SchematicUtils.getSchematicContainerPositionFromWorldPosition(pos.immutable(),
                region.schematic(), region.name(), region.placement(), region.subRegion(), region.container());

        if (at == null)
        {
            return Blocks.AIR.defaultBlockState();
        }

        Vec3i size = region.container().getSize();

        if (at.getX() < 0 || at.getY() < 0 || at.getZ() < 0
                || at.getX() >= size.getX() || at.getY() >= size.getY() || at.getZ() >= size.getZ())
        {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = region.container().get(at.getX(), at.getY(), at.getZ());

        if (state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID)
        {
            return Blocks.AIR.defaultBlockState();
        }

        if (region.mirrorMain() != Mirror.NONE)
        {
            state = state.mirror(region.mirrorMain());
        }

        if (region.mirrorSub() != Mirror.NONE)
        {
            state = state.mirror(region.mirrorSub());
        }

        if (region.rotation() != Rotation.NONE)
        {
            state = state.rotate(region.rotation());
        }

        return state;
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
    public int getHeight()
    {
        return this.height.getHeight();
    }

    @Override
    public int getMinY()
    {
        return this.height.getMinY();
    }
}
