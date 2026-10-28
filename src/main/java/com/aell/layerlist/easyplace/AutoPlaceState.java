package com.aell.layerlist.easyplace;

import com.aell.layerlist.util.Chat;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class AutoPlaceState
{
    private static final Map<BlockPos, Pending> PENDING = new HashMap<>();
    private static final Map<BlockPos, Integer> REFUSALS = new HashMap<>();

    /** Состояние клетки на момент последнего отказа: по нему приговор и снимается. */
    private static final Map<BlockPos, BlockState> REFUSED_AT = new HashMap<>();
    private static final long PRUNE_AFTER_MS = 5000L;

    /**
     * Сколько подтверждённых сервером отказов по одной клетке терпим, прежде чем
     * перестать в неё кликать. Отказ здесь — не таймаут, а факт: сервер ответил
     * ack на наш пакет, а состояние клетки не изменилось.
     */
    private static final int REFUSAL_LIMIT = 6;

    private static final int REFUSALS_CAP = 256;

    /**
     * Клетки, которые мы сами решили пропустить, не отправляя клик: решатель не нашёл
     * позы с нужным поворотом, либо любой доступный клик поставил бы блок неверно.
     * Нужно ровно для того, чтобы сказать об этом один раз на клетку: Litematica на
     * легаси-пути кеширует позицию ДО клика и возвращает SUCCESS независимо от нашего
     * ответа, поэтому наш отказ для неё невидим, а для игрока выглядит как «мод просто
     * перестал ставить вот здесь» — та же причина, по которой говорит countRefusal.
     */
    private static final Set<BlockPos> SKIPS = new HashSet<>();

    private static final int SKIPS_CAP = 256;

    private static int depth;
    private static int ownClicks;
    private static long lastEasyPlaceMs;
    private static int lastAck;
    private static @Nullable BlockPos lastMarked;
    private static @Nullable Level level;

    private AutoPlaceState() {}

    /**
     * @param seqBefore номер предсказания до клика: если после клика он не вырос, пакет
     *                  не ушёл (клик отменили раньше) и подтверждать нечего
     * @param seq       номер ушедшего пакета либо {@link Predictions#NONE}
     */
    private record Pending(BlockState state, long time, int seqBefore, int seq) {}

    public static void enter()
    {
        ++depth;
        lastEasyPlaceMs = now();
    }

    public static void exit()
    {
        depth = Math.max(0, depth - 1);
        lastEasyPlaceMs = now();
    }

    /**
     * Клик, который надо разбирать как строительный. Это либо клик изнутри обработчика
     * Litematica, либо любой клик, пока удерживается клавиша активации Easy Place: у кого
     * она висит на той же кнопке, которой кликает ваниль, установки идут МИМО обработчика,
     * и защиты от лишнего блока их не видели. Пока клавиша зажата, игрок строит по схеме,
     * а не ставит что-то своё, — отменять такие клики правильно.
     */
    public static boolean ourClick()
    {
        return inHandler() || activationHeld();
    }

    public static boolean inHandler()
    {
        return depth > 0;
    }

    /**
     * Наш собственный клик по уже стоящему блоку: позиция, грань, предмет и поза
     * посчитаны здесь же, поэтому разбирать его как обычную установку не надо —
     * {@link PlacementGuard} такой клик пропускает как есть.
     */
    public static <T> T ownClick(Supplier<T> body)
    {
        ++ownClicks;

        try
        {
            return body.get();
        }
        finally
        {
            ownClicks = Math.max(0, ownClicks - 1);
        }
    }

    public static boolean isOwnClick()
    {
        return ownClicks > 0;
    }

    public static boolean isAutomated(long recentWindowMs)
    {
        if (depth > 0)
        {
            return true;
        }

        if (! easyPlaceEnabled())
        {
            return false;
        }

        return now() - lastEasyPlaceMs <= recentWindowMs || activationHeld();
    }

    public static boolean isConfirmationPending(BlockPos pos, BlockState worldState, long windowMs)
    {
        prune();

        Pending pending = PENDING.get(pos);

        if (pending == null)
        {
            return false;
        }

        // Номер ушедшего пакета известен — судим по ОТВЕТУ сервера, а не по виду клетки.
        // Ваниль применяет своё предсказание синхронно внутри самого клика, поэтому
        // «клетка изменилась» наступает мгновенно и закрывало бы окно, не дождавшись
        // сервера: на 20 блоках в секунду следующий клик уходил по непроверенному миру,
        // а решатель считал позу и цель по клиентскому состоянию, которого на сервере
        // ещё нет. Отсюда и неверные повороты, и блоки не в той клетке при высоком темпе.
        if (pending.seq() != Predictions.NONE)
        {
            if (lastAck >= pending.seq())
            {
                PENDING.remove(pos);

                if (worldState == pending.state())
                {
                    // Сервер ответил, а клетка не изменилась — это отказ, а не задержка
                    countRefusal(pos);
                }
                else
                {
                    // Клик сработал: прошлые отказы по этой клетке больше не в счёт
                    REFUSALS.remove(pos);
                }

                return false;
            }

            return now() - pending.time() < windowMs;
        }

        // Номер проставить не удалось — остаётся прежнее правило по виду клетки
        if (worldState != pending.state())
        {
            PENDING.remove(pos);
            REFUSALS.remove(pos);

            return false;
        }

        return now() - pending.time() < windowMs;
    }

    public static void markClicked(BlockPos pos, BlockState worldState)
    {
        BlockPos key = pos.immutable();

        PENDING.put(key, new Pending(worldState, now(), Predictions.current(), Predictions.NONE));
        lastMarked = key;
    }

    /**
     * Дописать номер ушедшего пакета в уже заведённую запись. Звать это можно только
     * ПОСЛЕ возврата из клика: номер известен только тогда. Записанный заранее номер
     * принадлежал бы предыдущему клику, оказался бы подтверждённым мгновенно — и вся
     * защита от второго клика тихо отключилась бы целиком.
     */
    public static void stampSequence()
    {
        BlockPos pos = lastMarked;

        if (pos == null)
        {
            return;
        }

        lastMarked = null;

        Pending pending = PENDING.get(pos);

        if (pending == null || pending.seq() != Predictions.NONE)
        {
            return;
        }

        int after = Predictions.current();

        // Счётчик не вырос — предсказание не открывали, пакет не ушёл (клик отменили
        // раньше): подтверждать нечего, запись остаётся на страховочном окне
        if (after == Predictions.NONE || after <= pending.seqBefore())
        {
            return;
        }

        PENDING.put(pos, new Pending(pending.state(), pending.time(), pending.seqBefore(), after));
    }

    /** Подтверждение сервера: все пакеты с номером ≤ этого обработаны. */
    public static void onAck(int sequence)
    {
        if (sequence > lastAck)
        {
            lastAck = sequence;
        }
    }

    /**
     * Сервер отказал по этой клетке столько раз, что дальше кликать бессмысленно.
     * Главный источник таких клеток — {@code PlacementUtils.isReplaceable} в malilib:
     * он почти всегда возвращает true, поэтому последняя защита Litematica не срабатывает,
     * клик уходит в занятую клетку, сервер его игнорирует, состояние со схемой так и не
     * совпадает — и это повторяется каждые две секунды бесконечно.
     */
    public static boolean refused(BlockPos pos)
    {
        if (! giveUp() || REFUSALS.getOrDefault(pos, 0) < REFUSAL_LIMIT)
        {
            return false;
        }

        // Клетку могли сломать и переставить, сосед мог достроиться — приговор снимаем,
        // как только состояние клетки изменилось. Без этого шесть отказов гасили клетку
        // до конца сессии, хотя в чате обещана лишь пауза.
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        BlockState was = REFUSED_AT.get(pos);

        if (level != null && was != null && level.getBlockState(pos) != was)
        {
            REFUSALS.remove(pos);
            REFUSED_AT.remove(pos);

            return false;
        }

        return true;
    }

    public static void forget(BlockPos pos)
    {
        PENDING.remove(pos);
        REFUSALS.remove(pos);
        REFUSED_AT.remove(pos);
    }

    public static void clear()
    {
        PENDING.clear();
        REFUSALS.clear();
        REFUSED_AT.clear();
        SKIPS.clear();
        depth = 0;
        ownClicks = 0;
        lastAck = 0;
        lastMarked = null;
    }

    /**
     * Счётчик предсказаний живёт в {@link net.minecraft.client.multiplayer.ClientLevel} и
     * при смене мира или измерения начинается с нуля. Записи со старыми номерами тогда
     * не дождались бы своего ack никогда, поэтому со сменой уровня состояние чистится —
     * и вместе с ним всё, что тянется между кликами: очереди дожима и отложенных установок
     * с координатами прошлого мира и отсчёт темпа с прошлого сервера. Ничто из этого не
     * должно пережить дисконнект или портал.
     */
    public static void onClientTick(Minecraft mc)
    {
        if (mc.level != level)
        {
            level = mc.level;
            clear();
            Pacing.clear();
            SoilDig.clear();
            PoseAhead.clear();
            FollowUp.clear();
            DeferredPlace.clear();
            EntityPlace.clear();
        }
    }

    /**
     * Мы сами пропустили клетку. Причина уходит в отладку всегда, а в чат — один раз на
     * клетку, чтобы зажатая кнопка не залила чат.
     *
     * @param reason короткая причина для отладочной строки
     */
    /**
     * Клетке нужен предмет с данными блока (баннер с узором, горшок с черепками), а его у
     * игрока нет. Сообщаем один раз на клетку, тем же счётчиком, что и пропуск поворота.
     */
    public static void countMissingItem(BlockPos pos, net.minecraft.world.item.ItemStack required)
    {
        EpDebug.say("нет предмета " + EpDebug.at(pos) + ": " + required.getItem());

        if (SKIPS.size() >= SKIPS_CAP && ! SKIPS.contains(pos))
        {
            SKIPS.clear();
        }

        if (SKIPS.add(pos.immutable()))
        {
            Chat.send(Chat.tr("layerlist.message.ep_missing_data_item", Chat.WARN,
                    required.getHoverName(), pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    public static void countSkip(BlockPos pos, String reason)
    {
        EpDebug.say("пропуск " + EpDebug.at(pos) + ": " + reason);

        if (SKIPS.size() >= SKIPS_CAP && ! SKIPS.contains(pos))
        {
            SKIPS.clear();
        }

        if (SKIPS.add(pos.immutable()))
        {
            Chat.send(Chat.tr("layerlist.message.ep_skipped_wrong", Chat.WARN,
                    pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    /**
     * Тумблер «Бросать клетку после отказов сервера». Выключен — клетку не бросаем и в чат
     * не пишем: так просят те, кто ставит блок в одну и ту же клетку раз за разом (кафедра
     * под жителем, пока не выпадут нужные торги), — для них пауза только мешает.
     */
    private static boolean giveUp()
    {
        return com.aell.layerlist.config.LayerListConfigs.EP_GIVE_UP_REFUSED.getBooleanValue();
    }

    private static void countRefusal(BlockPos pos)
    {
        if (! giveUp())
        {
            EpDebug.say("сервер отказал " + EpDebug.at(pos) + ": клетку не бросаем, тумблер выключен");
            return;
        }

        int count = REFUSALS.getOrDefault(pos, 0) + 1;

        if (count > REFUSAL_LIMIT)
        {
            return;
        }

        if (REFUSALS.size() >= REFUSALS_CAP && ! REFUSALS.containsKey(pos))
        {
            REFUSALS.clear();
        REFUSED_AT.clear();
        }

        REFUSALS.put(pos.immutable(), count);

        net.minecraft.world.level.Level level = Minecraft.getInstance().level;

        if (level != null)
        {
            REFUSED_AT.put(pos.immutable(), level.getBlockState(pos));
        }
        EpDebug.say("сервер отказал " + EpDebug.at(pos) + ": попытка " + count + " из " + REFUSAL_LIMIT);

        // Ровно один раз на клетку: дальше в неё не кликаем, и молчать об этом нельзя —
        // иначе для игрока это выглядит как «мод просто перестал ставить вот здесь»
        if (count == REFUSAL_LIMIT)
        {
            Chat.send(Chat.tr("layerlist.message.ep_refused", Chat.WARN,
                    pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    private static void prune()
    {
        long time = now();
        Iterator<Map.Entry<BlockPos, Pending>> it = PENDING.entrySet().iterator();

        while (it.hasNext())
        {
            if (time - it.next().getValue().time() > PRUNE_AFTER_MS)
            {
                it.remove();
            }
        }
    }

    private static boolean easyPlaceEnabled()
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

    private static boolean activationHeld()
    {
        try
        {
            return Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static long now()
    {
        return Util.getMillis();
    }
}
