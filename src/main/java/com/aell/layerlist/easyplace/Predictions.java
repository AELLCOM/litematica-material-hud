package com.aell.layerlist.easyplace;

import com.aell.layerlist.mixin.IMixinClientLevel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Номер последовательности блочных предсказаний — точный признак «сервер ответил».
 *
 * Ванильный протокол: {@code MultiPlayerGameMode.useItemOn} открывает предсказание
 * ({@code startPredicting} инкрементирует счётчик) и кладёт полученный номер в
 * {@code ServerboundUseItemOnPacket}. Сервер зовёт {@code ackBlockChangesUpTo} до всех
 * проверок, кроме {@code hasClientLoaded}, и присылает {@code ClientboundBlockChangedAckPacket};
 * клиент проводит его через {@code ClientLevel.handleBlockChangedAck}. Значит
 * {@code lastAck >= наш номер} означает ровно «сервер обработал наш пакет» — независимо
 * от того, удалась установка или нет. Удача определяется отдельно: сравнением состояния
 * клетки с тем, что было до клика.
 *
 * Из этого получаются две вещи, которых не даёт таймаут: двойные клики перестают
 * зависеть от пинга, и появляется признак «сервер отказал» — без него отличить отказ от
 * задержки было нечем.
 */
@Environment(EnvType.CLIENT)
public final class Predictions
{
    /** Счётчик не прочитался — звать нечего, остаётся страховочное окно по времени. */
    public static final int NONE = -1;

    private Predictions() {}

    /**
     * Номер последнего открытого предсказания. Прочитанный сразу после возврата из
     * клика — это номер ушедшего пакета: {@code close()} счётчик не откатывает.
     */
    public static int current()
    {
        ClientLevel level = Minecraft.getInstance().level;

        if (level == null)
        {
            return NONE;
        }

        try
        {
            return ((IMixinClientLevel) level).layerlist$predictions().currentSequence();
        }
        catch (RuntimeException | LinkageError e)
        {
            return NONE;
        }
    }
}
