package com.aell.layerlist.core;

import com.aell.layerlist.schematics.materials.BlockReplacements;
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
                          int sizeX, int sizeY, int sizeZ,
                          boolean stepsValid, int baseX, int baseY, int baseZ,
                          int stepXx, int stepXz, int stepZx, int stepZz, int stepYy,
                          Mirror mirrorMain, Mirror mirrorSub, Rotation rotation,
                          BlockReplacements.@Nullable DisabledRegion disabled)
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
        BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();

        for (SchematicPlacement placement : placements)
        {
            if (! placement.isEnabled())
            {
                continue;
            }

            LitematicaSchematic schematic = placement.getSchematic();

            if (schematic == null)
            {
                continue;
            }

            BlockReplacements.ensure(schematic);

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
                int minX = Math.min(a.getX(), b.getX());
                int minY = Math.min(a.getY(), b.getY());
                int minZ = Math.min(a.getZ(), b.getZ());
                int maxX = Math.max(a.getX(), b.getX());
                int maxY = Math.max(a.getY(), b.getY());
                int maxZ = Math.max(a.getZ(), b.getZ());

                Vec3i size = container.getSize();
                probePos.set(minX, minY, minZ);
                BlockPos base = SchematicUtils.getSchematicContainerPositionFromWorldPosition(probePos,
                        schematic, entry.getKey(), placement, subRegion, container);
                boolean stepsValid = base != null;
                int baseX = base != null ? base.getX() : 0;
                int baseY = base != null ? base.getY() : 0;
                int baseZ = base != null ? base.getZ() : 0;
                int stepXx = 0;
                int stepXz = 0;
                int stepZx = 0;
                int stepZz = 0;
                int stepYy = 1;

                if (stepsValid && maxX > minX)
                {
                    probePos.set(minX + 1, minY, minZ);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(probePos,
                            schematic, entry.getKey(), placement, subRegion, container);

                    if (probe != null)
                    {
                        stepXx = probe.getX() - baseX;
                        stepXz = probe.getZ() - baseZ;
                    }
                    else
                    {
                        stepsValid = false;
                    }
                }

                if (stepsValid && maxZ > minZ)
                {
                    probePos.set(minX, minY, minZ + 1);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(probePos,
                            schematic, entry.getKey(), placement, subRegion, container);

                    if (probe != null)
                    {
                        stepZx = probe.getX() - baseX;
                        stepZz = probe.getZ() - baseZ;
                    }
                    else
                    {
                        stepsValid = false;
                    }
                }

                if (stepsValid && maxY > minY)
                {
                    probePos.set(minX, minY + 1, minZ);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(probePos,
                            schematic, entry.getKey(), placement, subRegion, container);

                    if (probe != null)
                    {
                        stepYy = probe.getY() - baseY;
                    }
                    else
                    {
                        stepsValid = false;
                    }
                }

                this.regions.add(new Region(placement, subRegion, schematic, entry.getKey(), container,
                        minX, minY, minZ, maxX, maxY, maxZ,
                        size.getX(), size.getY(), size.getZ(),
                        stepsValid, baseX, baseY, baseZ,
                        stepXx, stepXz, stepZx, stepZz, stepYy,
                        placement.getMirror(), mirrorSub, mainRotation.getRotated(subRegion.getRotation()),
                        BlockReplacements.disabledRegion(schematic, entry.getKey(), container)));
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

    /**
     * Клетка мира {@code pos} принадлежит отключённому блоку схемы (целиком или на этом слое), и ни одно другое
     * включённое размещение не ставит в неё обычный блок.
     */
    public boolean isDisabled(BlockPos pos)
    {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        boolean disabled = false;

        for (Region region : this.regions)
        {
            if (! region.contains(x, y, z))
            {
                continue;
            }

            if (! read(region, pos).isAir())
            {
                return false;
            }

            disabled |= isRegionCellDisabled(region, pos);
        }

        return disabled;
    }

    private static BlockState read(Region region, BlockPos pos)
    {
        int cx;
        int cy;
        int cz;

        if (region.stepsValid())
        {
            int dx = pos.getX() - region.minX();
            int dy = pos.getY() - region.minY();
            int dz = pos.getZ() - region.minZ();
            cx = region.baseX() + dx * region.stepXx() + dz * region.stepZx();
            cy = region.baseY() + dy * region.stepYy();
            cz = region.baseZ() + dx * region.stepXz() + dz * region.stepZz();
        }
        else
        {
            BlockPos at = SchematicUtils.getSchematicContainerPositionFromWorldPosition(pos.immutable(),
                    region.schematic(), region.name(), region.placement(), region.subRegion(), region.container());

            if (at == null)
            {
                return Blocks.AIR.defaultBlockState();
            }

            cx = at.getX();
            cy = at.getY();
            cz = at.getZ();
        }

        if (cx < 0 || cy < 0 || cz < 0
                || cx >= region.sizeX() || cy >= region.sizeY() || cz >= region.sizeZ())
        {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = region.container().get(cx, cy, cz);

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

    private static boolean isRegionCellDisabled(Region region, BlockPos pos)
    {
        BlockReplacements.DisabledRegion disabled = region.disabled();

        if (disabled == null)
        {
            return false;
        }

        int cx;
        int cy;
        int cz;

        if (region.stepsValid())
        {
            int dx = pos.getX() - region.minX();
            int dy = pos.getY() - region.minY();
            int dz = pos.getZ() - region.minZ();
            cx = region.baseX() + dx * region.stepXx() + dz * region.stepZx();
            cy = region.baseY() + dy * region.stepYy();
            cz = region.baseZ() + dx * region.stepXz() + dz * region.stepZz();
        }
        else
        {
            BlockPos at = SchematicUtils.getSchematicContainerPositionFromWorldPosition(pos.immutable(),
                    region.schematic(), region.name(), region.placement(), region.subRegion(), region.container());

            if (at == null)
            {
                return false;
            }

            cx = at.getX();
            cy = at.getY();
            cz = at.getZ();
        }

        if (cx < 0 || cy < 0 || cz < 0
                || cx >= region.sizeX() || cy >= region.sizeY() || cz >= region.sizeZ())
        {
            return false;
        }

        return disabled.isDisabled(cx, cy, cz);
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
