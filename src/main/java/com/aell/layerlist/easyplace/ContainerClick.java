package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Ручной клик по контейнеру посреди стройки.
 *
 * При включённом Easy Place сундук не открыть, и глушителей у этого четыре, а не один:
 * <ul>
 * <li>своё «Только по схеме» — клетка перед сундуком в схеме пуста, а блок в руке есть,
 *     и клик отменяется в {@link PlacementGuard};</li>
 * <li>Litematica: {@code handleEasyPlace} отдаёт FAIL, когда цель выбрана, но поставить
 *     нельзя (клетка занята либо уже совпадает со схемой), а {@code handleEasyPlaceWithMessage}
 *     превращает любой не-PASS в отмену клика;</li>
 * <li>Litematica: «Ограничение размещения» гасит клик при любом непустом предмете в руке,
 *     если наведённая клетка пуста в схеме и лежит рядом с регионом;</li>
 * <li>свой перенос прицела ({@link AirTarget}) — там, где Litematica пропустила бы клик,
 *     на луче находится другая недостроенная клетка и клик уходит на установку.</li>
 * </ul>
 *
 * Признак «игрок хочет открыть, а не строить» берётся однозначный: прицел стоит прямо на
 * блоке с экраном, игрок не приседает, схема ничего не просит в клетке за гранью прицела —
 * и правая кнопка именно <b>нажата сейчас</b>, а не удерживается со стройки. Последнее
 * обязательно: Easy Place вешают на ту же ПКМ, и без проверки фронта нажатия сундук
 * распахивался бы посреди зажатой стройки, стоило прицелу его задеть.
 *
 * Присед остаётся ванильным способом поставить блок к контейнеру — и решатель для таких
 * якорей его и так форсирует ({@link PlaceSolver} через {@link Interactive#eatsRightClick}).
 *
 * Пропуск безопасен: вана съедает такой клик в {@code useWithoutItem} раньше установки,
 * лишний блок из этого не вырастет.
 */
@Environment(EnvType.CLIENT)
public final class ContainerClick
{
    /** Была ли ПКМ зажата на конец прошлого тика: клик приходит раньше следующего замера. */
    private static boolean heldLastTick;

    private ContainerClick() {}

    public static void onClientTick(Minecraft client)
    {
        heldLastTick = client.options != null && client.options.keyUse.isDown();
    }

    public static boolean shouldPass()
    {
        return shouldPass(null);
    }

    /**
     * То же для конкретного клика. Перехватывать можно только тот клик, который и есть
     * ручной клик по перекрестью: автоматический клик Easy Place уходит на другую клетку
     * или грань, и если пустить его здесь, он минует решатель и обе защиты от лишнего
     * блока. В ряду камнерезов, печей и бочек перекрестье почти всегда лежит на соседнем
     * блоке с экраном, поэтому без этой сверки ряд мебели строился бы мимо конвейера —
     * блок вставал бы тем боком, куда смотрит игрок, и лишний ложился бы на клетку за гранью.
     * {@code null} означает «клика нет, суди по одному перекрестью» — так зовут те места,
     * где исполняемого клика ещё не существует.
     */
    public static boolean shouldPass(BlockHitResult click)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null)
        {
            return false;
        }

        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK
                || ! (mc.hitResult instanceof BlockHitResult hit))
        {
            return false;
        }

        if (click != null && (! click.getBlockPos().equals(hit.getBlockPos())
                || click.getDirection() != hit.getDirection()))
        {
            return false;
        }

        BlockPos pos = hit.getBlockPos();

        if (! Interactive.opensScreen(level, pos, level.getBlockState(pos)))
        {
            return false;
        }

        // Прицел на контейнере — дальше каждый отказ объясняется в отладке: молчаливый
        // отказ здесь неотличим от четырёх чужих глушителей, ради которых класс и написан

        // Отдельный мод спрашиваем первым: пока он стоит, значение тумблера живёт у него,
        // и прочитанное здесь своё объяснило бы отказ несуществующей причиной
        if (EasyPlaceModule.standalonePresent())
        {
            EpDebug.say("контейнер " + EpDebug.at(pos) + ": стоит отдельный EasyPlaceFixPlus");
            return false;
        }

        // Не isActive(): три глушителя из четырёх — свои у Litematica и работают даже когда
        // наш модуль Easy Place выключен, поэтому тумблер контейнеров смотрим и в этом случае.
        if (! LayerListConfigs.EP_OPEN_CONTAINERS.getBooleanValue())
        {
            EpDebug.say("контейнер " + EpDebug.at(pos) + ": тумблер «Открывать контейнеры» выключен");
            return false;
        }

        if (player.isShiftKeyDown())
        {
            EpDebug.say("контейнер " + EpDebug.at(pos) + ": присед — клик строительный");
            return false;
        }

        if (heldLastTick)
        {
            EpDebug.say("контейнер " + EpDebug.at(pos)
                    + ": ПКМ удерживается со стройки — не открываю");
            return false;
        }

        // Схема просит блок вплотную к контейнеру — это всё-таки клик на установку.
        // «Просит» здесь строго: клик обязан мочь построить эту клетку. Клетка вне
        // активного слоя не в счёт (Litematica вне слоя не строит), клетка с уже
        // стоящим — пусть и не тем — блоком тоже (кликом её не поправить, только
        // сломать руками; тот же фильтр держит AirTarget). Без оговорок сундук в зоне
        // схемы не открывался: клик не строил и не открывал, а умирал.
        BlockPos front = pos.relative(hit.getDirection());

        if (SchematicTarget.actionable(front) && withinRenderLayer(front)
                && level.getBlockState(front).canBeReplaced())
        {
            EpDebug.say("контейнер " + EpDebug.at(pos)
                    + ": схема просит блок вплотную — клик строительный");
            return false;
        }

        EpDebug.say("контейнер " + EpDebug.at(pos) + ": ручной клик, пропускаю");

        return true;
    }

    private static boolean withinRenderLayer(BlockPos pos)
    {
        try
        {
            return fi.dy.masa.litematica.data.DataManager.getRenderLayerRange()
                    .isPositionWithinRange(pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
        }
    }
}
