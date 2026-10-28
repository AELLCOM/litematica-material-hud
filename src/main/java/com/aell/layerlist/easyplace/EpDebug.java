package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Отладка Easy Place: каждое решение конвейера — строкой в чат. Включается
 * настройкой «Отладка Easy Place» и нужна ровно для одного: когда «не ставится»,
 * увидеть, какая именно проверка съела клик, вместо гадания по коду.
 *
 * Повторы (зажатая кнопка бьёт каждый тик) схлопываются: одинаковая строка не
 * печатается чаще, чем раз в 800 мс.
 */
@Environment(EnvType.CLIENT)
public final class EpDebug
{
    private static final long REPEAT_MS = 800L;

    private static String lastMessage = "";
    private static long lastMs;

    private EpDebug() {}

    public static boolean on()
    {
        return LayerListConfigs.EP_DEBUG.getBooleanValue();
    }

    public static void say(String message)
    {
        if (! on())
        {
            return;
        }

        long now = Util.getMillis();

        if (message.equals(lastMessage) && now - lastMs < REPEAT_MS)
        {
            return;
        }

        lastMessage = message;
        lastMs = now;

        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null)
        {
            mc.player.sendSystemMessage(
                    Component.literal("[EP] " + message).withStyle(ChatFormatting.GRAY));
        }
    }

    public static String at(BlockPos pos)
    {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static String name(BlockState state)
    {
        try
        {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        }
        catch (RuntimeException e)
        {
            return state.getBlock().toString();
        }
    }
}
