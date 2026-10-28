package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.Nullable;

/**
 * «Не копать лишнего». Пока включён режим Easy Place в Litematica, левый клик не ломает
 * блоки вне области схемы и блоки, уже стоящие как в схеме: копать можно только
 * то, что схеме мешает — чужой или неверно вставший блок внутри области. Причина отказа
 * показывается строкой над хотбаром, не чаще раза в секунду.
 *
 * <p>С 13.10.2026 три части защиты включаются по отдельности: вне схемы, правильно стоящие и
 * «только в режиме Easy Place» (выключено — защита работает и без него). «Правильно стоящим»
 * считается и блок, отличный от схемы лишь тем, что установкой не задаётся: ступень-близнец
 * ({@link Stairs#sameShape}), соединения забора, {@code distance} листвы и т. п.
 *
 * <p>Собственные раскопки почвы ({@link SoilDig}) под защиту не попадают: они ломают блок,
 * который схеме как раз не нужен.
 */
@Environment(EnvType.CLIENT)
public final class DigGuard
{
    private static final int HINT_INTERVAL_TICKS = 20;

    private static int lastHintTick = Integer.MIN_VALUE;

    private DigGuard() {}

    /** Удар по этой клетке надо отменить. */
    public static boolean blocks(BlockPos pos)
    {
        if (! LayerListConfigs.EP_DIG_GUARD.getBooleanValue() || ! EasyPlaceModule.isActive()
                || (LayerListConfigs.EP_DIG_GUARD_EP_ONLY.getBooleanValue() && ! easyPlaceOn())
                || SoilDig.digging(pos))
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level == null || player == null)
        {
            return false;
        }

        // Шалкер — склад игрока: поставил рядом со стройкой, опустошил, забирает. Ломать его
        // можно всегда, и вне области схемы тоже, иначе свой же ящик не поднять.
        if (level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)
        {
            return false;
        }

        if (! SchematicTarget.insideSchematic(pos))
        {
            if (! LayerListConfigs.EP_DIG_GUARD_OUTSIDE.getBooleanValue())
            {
                return false;
            }

            hint(player, "layerlist.dig_guard.outside");
            EpDebug.say("копание " + EpDebug.at(pos) + " отменено: вне схемы");

            return true;
        }

        WorldSchematic schematic = schematicWorld();

        if (schematic == null || ! LayerListConfigs.EP_DIG_GUARD_CORRECT.getBooleanValue())
        {
            return false;
        }

        BlockState wanted = schematic.getBlockState(pos);

        if (! wanted.isAir() && matches(wanted, level.getBlockState(pos)))
        {
            hint(player, "layerlist.dig_guard.correct");
            EpDebug.say("копание " + EpDebug.at(pos) + " отменено: блок уже как в схеме");

            return true;
        }

        return false;
    }

    /** Блок стоит как в схеме — с точностью до того, что установкой не задаётся. */
    static boolean matches(BlockState wanted, BlockState world)
    {
        return wanted == world || Stairs.sameShape(wanted, world)
                || Controllable.placementCannotChange(wanted, world);
    }

    /** Включён ли режим Easy Place самой Litematica. */
    static boolean easyPlaceOn()
    {
        try
        {
            return Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static void hint(LocalPlayer player, String key)
    {
        int now = player.tickCount;

        if (lastHintTick != Integer.MIN_VALUE && now - lastHintTick >= 0
                && now - lastHintTick < HINT_INTERVAL_TICKS)
        {
            return;
        }

        lastHintTick = now;
        player.displayClientMessage(Component.translatable(key), true);
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
