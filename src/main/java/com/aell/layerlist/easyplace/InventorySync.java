package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Util;

/**
 * Перекладка предметов и установка не наступают друг другу на пятки (жалоба 09.10.2026: «иногда ставится не
 * тот блок, если быстро строить», 10 блоков в секунду на сервере).
 *
 * <p>Гонка такая. Блок A поставлен из слота руки — сервер потом пришлёт «в этом слоте A, на штуку меньше».
 * Пока ответ в пути, Litematica уже меняет в этом слоте A на B (обмен с рюкзаком кликом по инвентарю) и
 * ставит B. Запоздавший ответ про A перезаписывает у клиента руку обратно: клиент думает, что держит A,
 * сервер — что B; нужен снова A — клиент «уже держит» его и ставит без обмена, а сервер ставит B. Стенд
 * {@code LL_COVERAGE=sync} (Material HUD) воспроизводит это задержкой пакетов: при пинге 150 мс без защиты
 * встало 6 не тех блоков из 24. Так же действует автопополнение Inventory Profiles Next.
 *
 * <p>Правило: обмен с рюкзаком ({@code swapItemToMainHand} malilib) ждёт, пока сервер подтвердит прошлую
 * установку Easy Place ({@code ClientboundBlockChangedAckPacket} по номеру пакета): сервер рассылает обновление
 * слотов в тике мира, а подтверждение — следом, в тике соединения, так что к подтверждению ответ про руку уже
 * дошёл, и обмен идёт сразу; обновления не было (стопка не менялась) — после короткого хвоста. Подтверждение не пришло за {@link #MAX_WAIT_MS} — обмен идёт (сервер встал
 * намертво — ждать дальше бессмысленно). Номер пакета не прочитался или сервер подтверждений не шлёт вовсе
 * (старый сервер за ViaVersion) — ждём по пингу, как раньше.
 * Установка Easy Place ждёт после чужого клика по инвентарю (автопополнение и т. п.) — по пингу: на клик
 * сервер подтверждения не шлёт. Свой обмен Litematica установку не задерживает: обмен и клик уходят на сервер
 * по порядку, сервер ставит из той руки, что получилась после обмена.
 * В одиночной игре — без ожиданий. Блок, который уже лежит в хотбаре, выбирается сменой слота — она идёт
 * пакетом по порядку, её это не задерживает.
 */
@Environment(EnvType.CLIENT)
public final class InventorySync
{
    private static final long MIN_MS = 100L;
    private static final long MAX_MS = 600L;
    /** После подтверждения: обновление слота идёт следом в том же тике сервера — тика клиента хватает. */
    private static final long ACK_TAIL_MS = 50L;
    /** Подтверждения так и нет — дольше не держим. */
    private static final long MAX_WAIT_MS = 1500L;
    /** Для самопроверки: своё ожидание, мс (−1 — по пингу, 0 — без ожиданий, как в одиночной игре). */
    public static long forcedSettleMs = -1L;
    /** Для самопроверки: сколько раз обмен в руку и установка подождали ответа сервера. */
    public static int swapWaits;
    public static int placeWaits;

    private static long inventoryClickAt = Long.MIN_VALUE / 2;
    private static long placeAt = Long.MIN_VALUE / 2;
    /** Номер пакета последней установки; подтверждён — когда пришёл ack с номером не меньше. */
    private static int placeSeq = Predictions.NONE;
    private static long placeAckAt = Long.MIN_VALUE / 2;
    /** Порядок событий: уход установки и обновления слотов инвентаря — по одному счётчику. */
    private static long events;
    private static long placeEvent = Long.MAX_VALUE;
    private static long slotEvent = Long.MIN_VALUE;
    private static boolean placeAcked = true;
    /** Сервер вообще шлёт подтверждения (в этом мире): старый сервер за ViaVersion их не шлёт — тогда ждём по пингу. */
    private static boolean acksSeen;
    private static @org.jetbrains.annotations.Nullable Object ackLevel;
    /** Идёт обмен Litematica в руку — его клики по инвентарю свои, установку не задерживают. Метка живёт
     *  не дольше {@link #OWN_SWAP_MS}: оборвись обмен исключением, она не залипнет. */
    private static long ownSwapUntil;
    private static final long OWN_SWAP_MS = 50L;

    private InventorySync() {}

    /** Клик по инвентарю игрока (обмен, перекладка) — от Litematica, Inventory Profiles Next, кого угодно. */
    public static void onInventoryClick()
    {
        long now = Util.getMillis();

        if (now > ownSwapUntil)
        {
            inventoryClickAt = now;
        }
    }

    public static void ownSwap(boolean active)
    {
        ownSwapUntil = active ? Util.getMillis() + OWN_SWAP_MS : 0L;
    }

    /** Установка Easy Place ушла на сервер — звать после возврата из клика, когда номер пакета известен. */
    public static void onPlaceClick()
    {
        placeAt = Util.getMillis();
        placeSeq = Predictions.current();
        placeAcked = false;
        placeEvent = ++events;
    }

    /** Сервер подтвердил пакеты по этот номер включительно. */
    public static void onAck(int sequence)
    {
        acksSeen = true;
        ackLevel = Minecraft.getInstance().level;

        if (! placeAcked && placeSeq != Predictions.NONE && sequence >= placeSeq)
        {
            placeAcked = true;
            placeAckAt = Util.getMillis();
        }
    }

    /**
     * Пришло обновление слотов инвентаря игрока (обмен, установка, подбор). Сервер 26.3 рассылает изменения
     * инвентаря в тике мира, а подтверждение установки — позже, в тике соединения того же тика: к приходу
     * подтверждения обновление руки уже здесь (трасса {@code LL_SYNC_TRACE=1} стенда {@code sync}).
     */
    public static void onSlotUpdate()
    {
        slotEvent = ++events;
    }

    /** Сколько ждать ответа сервера на клик по инвентарю: два пинга с запасом; одиночная игра — 0. */
    public static long settleMs()
    {
        if (forcedSettleMs >= 0L)
        {
            return forcedSettleMs;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.isLocalServer() || mc.getConnection() == null || mc.player == null)
        {
            return 0L;
        }

        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        int latency = info != null ? Math.max(0, info.getLatency()) : 0;
        return Math.max(MIN_MS, Math.min(MAX_MS, 2L * latency + 60L));
    }

    /** Установка сейчас прочитала бы руку, которую сервер ещё не подтвердил. */
    public static boolean placeTooSoon()
    {
        long settle = settleMs();
        return settle > 0L && Util.getMillis() - inventoryClickAt < settle;
    }

    /** Обмен сейчас попал бы под запоздавший ответ на прошлую установку. */
    public static boolean swapTooSoon()
    {
        long settle = settleMs();

        if (settle <= 0L)
        {
            return false;
        }

        long now = Util.getMillis();

        // Номер пакета не прочитался или сервер подтверждений не шлёт — по пингу, как раньше
        if (placeSeq == Predictions.NONE || ! acksSeen || ackLevel != Minecraft.getInstance().level)
        {
            return now - placeAt < settle;
        }

        if (! placeAcked)
        {
            return now - placeAt < MAX_WAIT_MS;
        }

        // Подтверждение пришло, и слоты обновлялись уже после ухода установки — ответ про руку здесь, менять
        // можно сразу. Обновления не было (стопка не менялась: креатив, сервер отказал) — короткий хвост
        return slotEvent < placeEvent && now - placeAckAt < ACK_TAIL_MS;
    }
}
