package com.aell.layerlist.util;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

@Environment(EnvType.CLIENT)
public final class Chat
{
    public static final int BRAND = 0x7AA8F5;
    public static final int OK    = 0x4ADE80;
    public static final int TEXT  = 0xE8E8EC;
    public static final int DIM   = 0x9A9AA6;
    public static final int WARN  = 0xE0902E;
    public static final int MARK  = 0x67E8F9;

    private static final String PREFIX = "» ";

    private Chat() {}

    public static MutableComponent paint(MutableComponent component, int rgb)
    {
        return component.withStyle(style -> style.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)));
    }

    public static MutableComponent text(String value, int rgb)
    {
        return paint(Component.literal(value), rgb);
    }

    public static MutableComponent tr(String key, int rgb, Object... args)
    {
        return paint(Component.translatable(key, args), rgb);
    }

    public static MutableComponent joined(String separator, int separatorColor, Iterable<MutableComponent> parts)
    {
        MutableComponent out = Component.empty();
        boolean first = true;

        for (MutableComponent part : parts)
        {
            if (! first)
            {
                out.append(text(separator, separatorColor));
            }

            out.append(part);
            first = false;
        }

        return out;
    }

    public static void send(Component body)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || ! LayerListConfigs.CHAT_MESSAGES.getBooleanValue())
        {
            return;
        }

        mc.player.displayClientMessage(text(PREFIX, BRAND).append(body), false);
    }

    /**
     * Отклик на нажатие клавиши-переключателя: в чат, если сообщения включены, иначе строкой над хотбаром —
     * с выключенным чатом (по умолчанию с 1.0.9) нажатие не должно оставаться без ответа.
     */
    public static void notice(Component body)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null)
        {
            return;
        }

        if (LayerListConfigs.CHAT_MESSAGES.getBooleanValue())
        {
            send(body);
        }
        else
        {
            mc.gui.setOverlayMessage(body, false);
        }
    }
}
