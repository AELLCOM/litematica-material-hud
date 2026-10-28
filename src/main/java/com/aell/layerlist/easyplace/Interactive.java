package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

@Environment(EnvType.CLIENT)
public final class Interactive
{
    private Interactive() {}

    /**
     * Правый клик по блоку открывает экран: сундук, бочка, шалкер, печь, воронка, раздатчик,
     * верстак, наковальня. Ровно то, ради чего игрок и кликает вручную посреди стройки.
     * Эндер-сундук — отдельно: он открывает экран из {@code useWithoutItem}, а
     * {@code getMenuProvider} у него не переопределён и отдаёт null.
     */
    public static boolean opensScreen(Level level, BlockPos pos, BlockState state)
    {
        try
        {
            return ! state.isAir() && (state.getMenuProvider(level, pos) != null
                    || state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    public static boolean eatsRightClick(Level level, BlockPos pos, BlockState state)
    {
        if (state.isAir())
        {
            return false;
        }

        if (state.hasBlockEntity() || state.getMenuProvider(level, pos) != null)
        {
            return true;
        }

        return state.getBlock() instanceof ShelfBlock
                || state.getBlock() instanceof RedstoneWireBlock
                || state.getBlock() instanceof LightBlock
                || state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof LeverBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
                || state.getBlock() instanceof NoteBlock
                || state.getBlock() instanceof DiodeBlock
                || state.getBlock() instanceof DaylightDetectorBlock
                || state.getBlock() instanceof CakeBlock
                || state.getBlock() instanceof CandleCakeBlock
                || state.getBlock() instanceof AbstractCandleBlock
                || state.getBlock() instanceof CampfireBlock
                || state.getBlock() instanceof AbstractCauldronBlock
                || state.getBlock() instanceof ComposterBlock
                || state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof RespawnAnchorBlock
                || state.getBlock() instanceof FlowerPotBlock
                || state.getBlock() instanceof BellBlock
                || state.getBlock() instanceof BeehiveBlock
                || state.getBlock() instanceof AnvilBlock
                || state.getBlock() instanceof CraftingTableBlock
                || state.getBlock() instanceof DragonEggBlock
                || state.getBlock() instanceof SweetBerryBushBlock
                || state.getBlock() instanceof CaveVinesBlock
                || state.getBlock() instanceof CaveVinesPlantBlock;
    }
}
