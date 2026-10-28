package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Догоняющая починка состояния. Дверь и люк всегда встают закрытыми: {@code open}
 * при установке не задаётся ничем, его правит отдельный клик. Раньше этот клик
 * случался, только если игрок всё ещё смотрел на ту же клетку, — стоило вести
 * прицел дальше, и дверь оставалась закрытой до следующего прохода.
 *
 * Теперь клетка, которой после установки нужен такой клик, встаёт в очередь и
 * доводится до состояния схемы сама — но только пока Easy Place работает (окно
 * автоматизации), только вблизи и не чаще, чем разрешает «Блоков в секунду».
 * Вне работы Easy Place мод не кликает ничем и никогда.
 *
 * <p>Очередь — единственный источник дожимающих кликов, и за проход по ней уходит ровно
 * один клик. Блокам, которым до состояния схемы нужно несколько кликов (задержка
 * повторителя, режим компаратора, нота нотного блока — до 24 шагов), клики не
 * отправляются пачкой за один тик: после каждого клика клетка уходит в конец очереди
 * и получает следующий, когда до неё снова дойдёт ход и сервер подтвердит предыдущий.
 * Ряд таких блоков дожимается по кругу — по одному клику на клетку за проход, — и на
 * сервер никогда не уходит больше одного взаимодействия за тик, сколько бы клеток ни
 * ждало: пачка пакетов за тик — ровно то, на что срабатывает «timer»-античит.
 */
@Environment(EnvType.CLIENT)
public final class FollowUp
{
    /** Тиков без клика, после которых клетка забывается: игрок ушёл или блок не встал. */
    private static final int MAX_TICKS = 100;

    /** Кликов на одну клетку: нотный блок — самое длинное, 24 шага, плюс запас на отказы. */
    private static final int CLICK_LIMIT = 30;

    private static final int QUEUE_LIMIT = 16;

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();

    private static final class Entry
    {
        final BlockPos pos;
        int ticks;
        int clicks;

        Entry(BlockPos pos)
        {
            this.pos = pos;
        }
    }

    private enum Step
    {
        /** Клетка совпала со схемой или дожимать её больше нечем — из очереди долой. */
        DONE,

        /** Пока нельзя: блок ещё не встал, клетка вне досягаемости или ждёт ack сервера. */
        WAIT,

        /** Клик ушёл — в этот проход больше не кликаем, клетка — в конец очереди. */
        CLICKED
    }

    private FollowUp() {}

    public static void schedule(BlockPos pos)
    {
        for (Entry entry : QUEUE)
        {
            if (entry.pos.equals(pos))
            {
                entry.ticks = 0;

                return;
            }
        }

        if (QUEUE.size() >= QUEUE_LIMIT)
        {
            QUEUE.pollFirst();
        }

        QUEUE.addLast(new Entry(pos.immutable()));
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
        ClientLevel level = mc.level;

        if (player == null || level == null || ! EasyPlaceModule.isActive())
        {
            QUEUE.clear();

            return;
        }

        for (Entry entry : QUEUE)
        {
            ++entry.ticks;
        }

        QUEUE.removeIf(entry -> entry.ticks > MAX_TICKS);

        if (! AutoPlaceState.isAutomated(LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue())
                || Pacing.tooSoon())
        {
            return;
        }

        WorldSchematic schematic = schematicWorld();

        if (schematic == null)
        {
            return;
        }

        Entry clicked = null;
        Iterator<Entry> it = QUEUE.iterator();

        while (it.hasNext())
        {
            Entry entry = it.next();

            switch (run(mc, player, level, schematic, entry.pos))
            {
                case DONE -> it.remove();
                case WAIT -> { }
                case CLICKED ->
                {
                    it.remove();
                    clicked = entry;
                }
            }

            if (clicked != null)
            {
                break;
            }
        }

        if (clicked == null)
        {
            return;
        }

        Pacing.mark();

        // Следующий шаг — не раньше, чем очередь обойдёт остальных и сервер ответит на этот
        clicked.ticks = 0;

        if (++clicked.clicks < CLICK_LIMIT)
        {
            QUEUE.addLast(clicked);
        }
    }

    private static Step run(Minecraft mc, LocalPlayer player, ClientLevel level,
            WorldSchematic schematic, BlockPos pos)
    {
        BlockState wanted = schematic.getBlockState(pos);
        BlockState world = level.getBlockState(pos);

        if (wanted.isAir() || AutoPlaceState.refused(pos))
        {
            return Step.DONE;
        }

        // Блок ещё не долетел до мира: установка ждёт ответа сервера, судить рано
        if (world.canBeReplaced())
        {
            return Step.WAIT;
        }

        if (StateFix.plan(level, schematic, pos, wanted, world) == null)
        {
            return Step.DONE;
        }

        if (! visible(player, level, pos)
                || AutoPlaceState.isConfirmationPending(pos, world,
                        LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Step.WAIT;
        }

        return StateFixer.run(mc, player, pos, wanted, world, Direction.UP) ? Step.CLICKED : Step.WAIT;
    }

    /**
     * Клетка в досягаемости и на виду. Античиты проверяют, что игрок ставит и кликает
     * то, куда реально может дотянуться и что видит (reach, «players must look at blocks
     * they place»): дальше ванильной дистанции взаимодействия, за спиной или за стеной
     * клетку не дожимаем — дождётся, пока игрок снова окажется рядом.
     */
    private static boolean visible(LocalPlayer player, ClientLevel level, BlockPos pos)
    {
        Vec3 eyes = player.getEyePosition(1.0F);
        Vec3 center = Vec3.atCenterOf(pos);
        double reach = player.blockInteractionRange();

        if (eyes.distanceToSqr(center) > reach * reach)
        {
            return false;
        }

        // Не дальше 90° от направления взгляда — «глазами» игрок клетку видит
        if (player.getViewVector(1.0F).dot(center.subtract(eyes).normalize()) < 0.0D)
        {
            return false;
        }

        HitResult trace = level.clip(new net.minecraft.world.level.ClipContext(eyes, center,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));

        // Либо дошли до самой клетки, либо упёрлись прямо в неё
        return trace.getType() != HitResult.Type.BLOCK
                || ((BlockHitResult) trace).getBlockPos().equals(pos);
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
