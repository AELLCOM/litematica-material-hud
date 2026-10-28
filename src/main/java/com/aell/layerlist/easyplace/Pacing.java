package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;

/**
 * Темп автоматических кликов и политика поворота головы.
 *
 * Античиты смотрят на две вещи: сколько блоков в секунду ставит игрок и как резко
 * прыгает его взгляд. Первое ограничивает «Блоков в секунду», второе —
 * «Не поворачивать игрока»: с ним мод отправляет клики только с тем углом взгляда,
 * который у игрока и так есть, а позиции, где без поворота нужное состояние не
 * получить, остаются на потом.
 *
 * <p>Темп считается в тиках клиента, а не по часам. Серверный «timer»-античит меряет
 * пакеты на свой тик, и клиентский тик — единственная величина, которая идёт с ним в
 * ногу: по часам два клика легко ложатся в один тик (несколько кадров на тик, догоняющие
 * тики после лага), и сервер видит пачку там, где клиент насчитал честную паузу.
 * «Блоков в секунду» переводится в целое число тиков между кликами (20 → каждый тик,
 * 10 → через тик, 5 → каждый четвёртый), и меньше одного тика не бывает никогда — даже
 * на 20 автоматика не шлёт два клика за тик. Часы остаются страховкой от второго клика
 * внутри одного тика, пока счётчик тиков ещё не сдвинулся.
 */
@Environment(EnvType.CLIENT)
public final class Pacing
{
    private static final int NEVER = Integer.MIN_VALUE;

    private static int lastClickTick = NEVER;
    private static long lastClickMs;

    /** «Неровный темп»: лишний тик, случайно добавленный к паузе после прошлого клика. */
    private static int jitterTicks;

    private Pacing() {}

    /** Прошлый автоматический клик был слишком недавно — этот пропускаем. */
    public static boolean tooSoon()
    {
        int interval = intervalTicks() + jitterTicks;
        LocalPlayer player = Minecraft.getInstance().player;

        if (player != null && lastClickTick != NEVER)
        {
            int elapsed = player.tickCount - lastClickTick;

            // Отрицательное — счётчик начался заново (новый игрок после смерти или
            // портала): прошлый клик был в другой жизни, паузу по нему не считаем
            if (elapsed >= 0 && elapsed < interval)
            {
                return true;
            }
        }

        // Страховка по часам — от второго клика внутри того же тика. Полтика допуска,
        // чтобы штатные ~50 мс между соседними тиками не считались «слишком рано»
        return lastClickMs != 0L && Util.getMillis() - lastClickMs < interval * 50L - 25L;
    }

    public static void mark()
    {
        LocalPlayer player = Minecraft.getInstance().player;

        lastClickTick = player == null ? NEVER : player.tickCount;
        lastClickMs = Util.getMillis();

        // Рука не кликает как метроном: ровный интервал — примета автоматики для timer-античита
        jitterTicks = LayerListConfigs.EP_JITTER.getBooleanValue()
                ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2) : 0;
    }

    /** Смена мира или сервера: пауза с прошлого места сюда не переносится. */
    public static void clear()
    {
        lastClickTick = NEVER;
        lastClickMs = 0L;
        jitterTicks = 0;
    }

    /**
     * Сколько тиков между автоматическими кликами даёт «Блоков в секунду». Округление
     * вверх: заявленный темп не превышается никогда, 15 даёт через тик, как и 10.
     */
    public static int intervalTicks()
    {
        int rate = Math.max(1, Math.min(20, LayerListConfigs.EP_PLACE_RATE.getIntegerValue()));

        return Math.max(1, (20 + rate - 1) / rate);
    }

    /** Можно ли ради нужного состояния поворачивать игрока. */
    public static boolean mayTurn()
    {
        return ! LayerListConfigs.EP_NO_TURN.getBooleanValue();
    }

    /** Поза с нетронутым углом взгляда, если поворот запрещён настройкой. */
    public static LookSpoof.Pose pose(LocalPlayer player, LookSpoof.Pose wanted)
    {
        return mayTurn()
                ? wanted
                : new LookSpoof.Pose(player.getYRot(), player.getXRot(), wanted.sneak());
    }
}
