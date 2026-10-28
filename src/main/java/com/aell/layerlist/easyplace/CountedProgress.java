package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

@Environment(EnvType.CLIENT)
public final class CountedProgress
{
    private CountedProgress() {}

    public static boolean stillGrowing(BlockPos pos)
    {
        if (! EasyPlaceModule.isActive())
        {
            return false;
        }

        ClientLevel level = Minecraft.getInstance().level;
        WorldSchematic schematic = schematicWorld();

        if (level == null || schematic == null)
        {
            return false;
        }

        BlockState wanted = schematic.getBlockState(pos);
        BlockState world = level.getBlockState(pos);

        if (wanted.isAir() || ! world.is(wanted.getBlock()))
        {
            return false;
        }

        IntegerProperty property = BlockFamilies.countProperty(wanted);

        if (property == null)
        {
            return BlockFamilies.isMultiface(wanted) && missingFace(level, pos, wanted, world);
        }

        if (BlockFamilies.count(world, property) >= BlockFamilies.count(wanted, property))
        {
            return false;
        }

        return ! AutoPlaceState.isConfirmationPending(pos, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue());
    }

    private static boolean missingFace(ClientLevel level, BlockPos pos, BlockState wanted,
            BlockState world)
    {
        for (Direction direction : Direction.values())
        {
            if (! MultifaceBlock.hasFace(wanted, direction) || MultifaceBlock.hasFace(world, direction))
            {
                continue;
            }

            BlockPos neighbour = pos.relative(direction);

            if (MultifaceBlock.canAttachTo(level, direction, neighbour, level.getBlockState(neighbour)))
            {
                return true;
            }
        }

        return false;
    }

    private static WorldSchematic schematicWorld()
    {
        try
        {
            return SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
