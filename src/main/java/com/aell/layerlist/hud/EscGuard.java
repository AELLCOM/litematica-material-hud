package com.aell.layerlist.hud;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;

/**
 * Esc после автовыхода из хранилища (просьба 15.10.2026): окно закрывает сам перенос — «Выходить из сундука после
 * набора» или закрытие после сверки, — а игрок в тот же миг жмёт или держит Esc, и его нажатие уже уходило в игру:
 * открывалось меню паузы. {@link #HOLD_MS} после такого выхода меню паузы по Esc не открывается; обычный выход по Esc
 * — как в игре. Засекает {@link TransferButton}, гасит {@code MixinMinecraftPause}.
 */
@Environment(EnvType.CLIENT)
public final class EscGuard
{
    /** Сколько после выхода из хранилища Esc не открывает паузу. */
    public static final long HOLD_MS = 1500L;

    private static long closedAt = Long.MIN_VALUE / 2;

    private EscGuard() {}

    /** Перенос сам закрыл окно хранилища. */
    public static void containerClosed()
    {
        closedAt = Util.getMillis();
    }

    /** Меню паузы сейчас не открывать: только что вышли из хранилища. */
    public static boolean blockPause()
    {
        return Util.getMillis() - closedAt < HOLD_MS;
    }
}
