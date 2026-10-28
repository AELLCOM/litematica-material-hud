package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Поза на тик вперёд: нужный угол взгляда уезжает на сервер ванильным пакетом движения, а клик
 * уходит следующим тиком. Смысл — не отправлять ни одного своего пакета поворота.
 *
 * <p><b>Почему обычная подмена стоит дорого.</b> {@link LookSpoof#announced} шлёт пакет поворота
 * перед кликом и второй на восстановление угла после него. Ванильный клиент за тик отправляет
 * ровно один пакет движения ({@code LocalPlayer.sendPosition}, ветки взаимоисключающие), а стоя на
 * месте — один раз в двадцать тиков. Итого в тике с установкой уходит три пакета движения вместо
 * одного, и серверные timer-проверки считают именно их.
 *
 * <p><b>Почему нельзя просто не восстанавливать угол.</b> Порядок ванильного тика проверен по
 * байткоду: {@code Minecraft.tick} сначала зовёт {@code handleKeybinds}, потом
 * {@code level.tickEntities} с единственной отправкой движения, потом
 * {@code ServerboundClientTickEndPacket}. Обработчик malilib, через который Litematica ставит блоки
 * при зажатой клавише, висит на {@code @At("RETURN")} метода {@code tick()V}, то есть установки
 * уходят в самом конце тика — уже после пакета движения. Без своего восстановления подставленный
 * угол пережил бы границу тика, и следующее действие игрока сервер посчитал бы со старым углом.
 *
 * <p><b>Что делает эта схема.</b> Когда решателю нужен угол, которого у сервера ещё нет, клик в
 * этом тике не уходит вовсе: угол применяется локально ровно на время сборки ванильного пакета
 * движения (миксин на {@code sendPosition}), поэтому камера не дёргается, а сервер получает угол
 * штатным пакетом. Следующим тиком клик уходит уже без единого своего пакета. Пока угол нужен, он
 * подставляется в каждый пакет, и ваниль ничего лишнего не шлёт: для неё угол не менялся. Когда
 * угол больше не нужен, подстановка снимается и ближайший ванильный пакет несёт настоящий.
 *
 * <p><b>Голова сервера.</b> Наблюдатель, поршень, раздатчик, бочка встают по ближайшему
 * направлению взгляда, а его сервер считает по {@code yHeadRot}. Пакет поворота голову не
 * трогает: она догоняет тело только в тике игрока на сервере ({@code Player.aiStep}). Угол,
 * пришедший вместе с кликом, такие блоки не видят вовсе — они вставали по настоящему взгляду
 * игрока. Для них схема включается сама, даже при выключенной опции, и клик ждёт, пока угол
 * проведёт на сервере хотя бы тик ({@link #headMatters}, {@link #settled}).
 *
 * <p><b>Цена.</b> Каждая клетка, требующая поворота, встаёт на тик позже; при темпе 10 блоков в
 * секунду этот тик и так проходил в паузе. Пока угол держится, сервер не видит настоящего взгляда
 * игрока, поэтому удар мышью в это время он посчитает по подставленному углу — удержание
 * снимается, как только игрок перестал строить или нажал атаку. Из-за этой платы опция выключена
 * по умолчанию.
 */
@Environment(EnvType.CLIENT)
public final class PoseAhead
{
    /** Сколько тиков без запроса держим подставленный угол, прежде чем отпустить. */
    private static final int HOLD_TICKS = 4;

    /** Запрошенный угол, который ещё не уехал на сервер. */
    private static @Nullable LookSpoof.Pose wanted;

    /** Угол, уже уехавший ванильным пакетом движения. */
    private static @Nullable LookSpoof.Pose sent;

    private static int idle;

    /** Тиков ждём подтверждения запрошенного угла, прежде чем сдаться. */
    private static final int WAIT_LIMIT = 2;

    /** Насколько тиков схема отключается, если пакет движения так и не ушёл. */
    private static final int STALL_TICKS = 40;

    private static int waiting;
    private static int stalled;

    /** Удержание включено ради клика, который сервер разберёт по голове, — и без опции. */
    private static boolean forced;

    /** {@code tickCount} игрока, когда угол {@link #sent} впервые ушёл на сервер. */
    private static int sentTick;

    /** Часы клиента ({@code Util.getMillis}) в тот же момент. */
    private static long sentAt;

    /**
     * Сколько угол должен пробыть на сервере, прежде чем клик по нему разберут по голове: два
     * серверных тика при 20 TPS. Тиков клиента для этого мало — при FPS ниже 20 игра крутит
     * несколько тиков подряд в одном кадре, и пакет угла с кликом уходят с разницей в
     * миллисекунды, то есть в одно окно между серверными тиками. Проверено харнессом
     * {@code j}: по одному счётчику тиков сервер ещё ставил часть блоков по старой голове.
     */
    private static final long SETTLE_MS = 100L;

    /** Поворот из последнего пакета движения прошлых тиков — его сервер уже вписал в голову. */
    private static float lastSentYaw = Float.NaN;

    private PoseAhead() {}

    /**
     * Схема включена. Пока {@code stalled} не истёк, ведём себя как раньше: пакет движения в
     * тике может не уйти вовсе — например, игрок верхом, там ваниль собирает поворот сама и
     * {@code sendPosition} не зовёт. Тогда клик не должен зависнуть в ожидании навсегда.
     */
    public static boolean enabled()
    {
        return stalled <= 0 && LayerListConfigs.EP_POSE_AHEAD.getBooleanValue() && Pacing.mayTurn();
    }

    /**
     * Клик в этом тике не отправляем: нужного угла у сервера ещё нет, он уедет ближайшим
     * ванильным пакетом движения. Клетку метить нельзя — клика не было.
     */
    public static boolean deferring(LocalPlayer player, LookSpoof.Pose pose)
    {
        return deferring(player, pose, false);
    }

    /**
     * {@code head} — клик зависит от головы сервера ({@link #headMatters}): тогда угол должен
     * не просто уехать, а прожить на сервере тик, и ждём его даже при выключенной опции.
     */
    public static boolean deferring(LocalPlayer player, LookSpoof.Pose pose, boolean head)
    {
        if (head)
        {
            if (stalled > 0 || ! Pacing.mayTurn() || settled(player, pose))
            {
                return false;
            }

            if (ready(pose))
            {
                // Угол уже у сервера — осталось дождаться, пока он там проживёт тик
                wanted = null;
            }
            else
            {
                want(pose);
            }

            forced = true;
            idle = 0;

            return true;
        }

        if (! enabled() || sameAim(LookSpoof.Pose.of(player), pose) || ready(pose))
        {
            return false;
        }

        want(pose);
        idle = 0;

        return true;
    }

    /**
     * Счётчик ожидания сбрасываем только на новый угол: Litematica повторяет отложенный клик
     * каждый тик, и сброс на каждом повторе не дал бы {@link #onClientTick} заметить, что пакет
     * движения не уходит вовсе.
     */
    private static void want(LookSpoof.Pose pose)
    {
        if (wanted == null || ! sameAim(wanted, pose))
        {
            waiting = 0;
        }

        wanted = pose;
    }

    /** Угол уже у сервера: клик уходит без своего пакета поворота. */
    public static boolean ready(LookSpoof.Pose pose)
    {
        return sent != null && sameAim(sent, pose);
    }

    /**
     * Угол не только у сервера, но и пережил там тик: пакет с ним ушёл в одном из прошлых
     * тиков клиента и не меньше {@link #SETTLE_MS} назад, и между ним и кликом сервер успел
     * вписать его в голову.
     */
    public static boolean settled(LocalPlayer player, LookSpoof.Pose pose)
    {
        return ready(pose) && player.tickCount > sentTick
                && Util.getMillis() - sentAt >= SETTLE_MS;
    }

    /**
     * Сервер поставил бы блок иначе, если его голова ещё смотрит по-старому. Сверяем то, что
     * ждёт решатель, с симуляцией, где поворот головы — прошлый: из последнего пакета движения
     * или настоящий взгляд игрока. Блокам, чьё состояние от поворота головы не зависит (печь,
     * лестница, всё, что встаёт по грани клика), ждать незачем.
     */
    public static boolean headMatters(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            LookSpoof.Pose pose)
    {
        ItemStack stack = player.getItemInHand(hand);

        if (! (stack.getItem() instanceof BlockItem))
        {
            return false;
        }

        float real = player.getYRot();
        float last = lastSentYaw;

        try
        {
            return LookSpoof.silent(player, pose, () ->
            {
                BlockState meant = simulate(player, hand, stack, hit, pose, pose.yaw());

                if (meant == null)
                {
                    return false;
                }

                return ! meant.equals(simulate(player, hand, stack, hit, pose, real))
                        || (! Float.isNaN(last) && ! meant.equals(simulate(player, hand, stack, hit, pose, last)));
            });
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static @Nullable BlockState simulate(LocalPlayer player, InteractionHand hand, ItemStack stack,
            BlockHitResult hit, LookSpoof.Pose pose, float headYaw)
    {
        return SimPlaceContext.of(player.level(), player, hand, stack, hit, pose, headYaw).placementState();
    }

    /**
     * Обёртка клика. Пока схема выключена или угол на сервер не уезжал, ведём себя как раньше;
     * иначе шлём разве что присед — он идёт отдельным пакетом ввода, а не движения.
     */
    public static <T> T applied(LocalPlayer player, LookSpoof.Pose pose, Supplier<T> body)
    {
        if (holding() && ready(pose))
        {
            idle = 0;

            return LookSpoof.sneakOnly(player, pose, body);
        }

        return LookSpoof.announced(player, pose, body);
    }

    /**
     * Угол, который надо подставить в собираемый ванильный пакет движения, либо {@code null}.
     * Зовётся из миксина на {@code LocalPlayer.sendPosition}.
     */
    public static @Nullable LookSpoof.Pose forPacket()
    {
        if (! holding())
        {
            return null;
        }

        return wanted != null ? wanted : sent;
    }

    /** Пакет с подставленным углом собран и ушёл: теперь этот угол знает и сервер. */
    public static void packetSent(LookSpoof.Pose pose, int tickCount)
    {
        if (sent == null || ! sameAim(sent, pose))
        {
            sentTick = tickCount;
            sentAt = Util.getMillis();
            EpDebug.say("угол " + pose.yaw() + "/" + pose.pitch() + " ушёл пакетом движения, тик игрока " + tickCount);
        }

        sent = pose;
        wanted = null;
    }

    /**
     * Перед сборкой пакета движения: поворот, который ушёл в прошлых тиках (поле
     * {@code yRotLast} игрока). Сервер его уже применил и, скорее всего, вписал в голову.
     */
    public static void noteLastSent(float yaw)
    {
        lastSentYaw = yaw;
    }

    public static void onClientTick(Minecraft mc)
    {
        if (stalled > 0)
        {
            --stalled;
        }

        if (wanted != null && ++waiting > WAIT_LIMIT)
        {
            // Запрошенный угол так и не уехал: сдаёмся на время и работаем по-старому,
            // иначе установка встала бы намертво
            EpDebug.say("поза на тик вперёд: пакет движения не ушёл, откат к обычной подмене");
            clear();
            stalled = STALL_TICKS;

            return;
        }

        if (sent == null && wanted == null)
        {
            return;
        }

        boolean building = AutoPlaceState.isAutomated(
                LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue());
        boolean attacking = mc.options != null && mc.options.keyAttack.isDown();

        // Игрок перестал строить или ударил мышью — подставленный угол больше не наше дело:
        // отпускаем, и ближайший ванильный пакет унесёт настоящий взгляд.
        if (! building || attacking || ++idle > HOLD_TICKS)
        {
            clear();
        }
    }

    public static void clear()
    {
        wanted = null;
        sent = null;
        idle = 0;
        waiting = 0;
        forced = false;
    }

    /** Угол подставляется в пакеты: по опции или ради клика, который сервер разберёт по голове. */
    private static boolean holding()
    {
        return stalled <= 0 && Pacing.mayTurn()
                && (forced || LayerListConfigs.EP_POSE_AHEAD.getBooleanValue());
    }

    /** Сверяем только угол: присед живёт в отдельном пакете ввода. */
    private static boolean sameAim(LookSpoof.Pose a, LookSpoof.Pose b)
    {
        return a.yaw() == b.yaw() && a.pitch() == b.pitch();
    }
}
