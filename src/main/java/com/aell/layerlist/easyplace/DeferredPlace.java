package com.aell.layerlist.easyplace;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Установка, отложенная на пару тиков: погашенный чужой клик (см.
 * {@code PlacementGuard#decideForeign}) переигрывается обычным кликом по клетке цели, когда
 * чужая блокировка look-пакетов уже снята — дальше клик проходит через {@code PlacementGuard}
 * и переписывается штатным решателем с работающим {@code LookSpoof}.
 */
@Environment(EnvType.CLIENT)
public final class DeferredPlace
{
    private static final int MIN_WAIT_TICKS = 2;
    private static final int MAX_WAIT_TICKS = 40;
    private static final int QUEUE_LIMIT = 8;

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();

    private static final class Entry
    {
        final BlockPos target;
        final InteractionHand hand;
        int ticks;

        Entry(BlockPos target, InteractionHand hand)
        {
            this.target = target;
            this.hand = hand;
        }
    }

    private DeferredPlace() {}

    public static void schedule(BlockPos target, InteractionHand hand)
    {
        for (Entry entry : QUEUE)
        {
            if (entry.target.equals(target))
            {
                return;
            }
        }

        if (QUEUE.size() >= QUEUE_LIMIT)
        {
            QUEUE.pollFirst();
        }

        QUEUE.addLast(new Entry(target.immutable(), hand));
    }

    public static void clear()
    {
        QUEUE.clear();
    }

    public static void onClientTick(Minecraft mc)
    {
        if (QUEUE.isEmpty())
        {
            return;
        }

        LocalPlayer player = mc.player;

        if (player == null || mc.level == null || mc.gameMode == null || ! EasyPlaceModule.isActive())
        {
            QUEUE.clear();

            return;
        }

        for (Entry entry : QUEUE)
        {
            ++entry.ticks;
        }

        QUEUE.removeIf(entry -> entry.ticks > MAX_WAIT_TICKS);

        Entry next = QUEUE.peekFirst();

        if (next == null || next.ticks < MIN_WAIT_TICKS || ForeignLookLock.armed())
        {
            return;
        }

        QUEUE.pollFirst();

        if (! stillWanted(mc, player, next))
        {
            return;
        }

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(next.target), Direction.UP,
                next.target, false);

        mc.gameMode.useItemOn(player, next.hand, hit);
    }

    private static boolean stillWanted(Minecraft mc, LocalPlayer player, Entry entry)
    {
        WorldSchematic schematic = schematicWorld();

        if (schematic == null || ! mc.level.getBlockState(entry.target).canBeReplaced())
        {
            return false;
        }

        BlockState wanted = schematic.getBlockState(entry.target);
        ItemStack stack = player.getItemInHand(entry.hand);

        return ! wanted.isAir() && stack.getItem() instanceof BlockItem
                && wanted.getBlock().asItem() == stack.getItem();
    }

    private static @Nullable WorldSchematic schematicWorld()
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
