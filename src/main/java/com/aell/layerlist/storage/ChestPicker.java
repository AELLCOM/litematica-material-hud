package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.util.Chat;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим выбора сундуков (29.10.2026): клавиша {@link LayerListConfigs#PICK_CHESTS} включает его, и ПКМ по сундуку,
 * бочке, шалкеру или другому хранилищу отмечает его для счёта (или снимает отметку) вместо открытия. ПКМ по
 * эндер-сундуку включает и выключает счёт эндер-сундука. Ещё раз клавиша — выход.
 *
 * <p>Пока режим включён, выбранные хранилища светятся зелёным, хранилище под прицелом обведено, над хотбаром —
 * подсказка. Первая отметка сама включает «Только выбранные сундуки»: человек, который отмечает сундуки, хочет
 * считать по ним.
 *
 * <p>Клик гасится в начале {@code useItemOn} ({@code MixinMultiPlayerGameMode}), до Easy Place и до пакета на сервер:
 * сундук не открывается, блок не ставится. Зажатая ПКМ повторяет клик каждые 4 тика — переключает только первый.
 */
@Environment(EnvType.CLIENT)
public final class ChestPicker
{
    /** Подсказка над хотбаром живёт 3 с; обновляем чаще, чтобы не мигала. */
    private static final int HINT_EVERY_TICKS = 20;
    /** Отклик на щелчок держится столько, потом снова подсказка. */
    private static final long NOTICE_MS = 2000L;
    /** Как часто убирать отметки со сломанных хранилищ. */
    private static final int CLEAN_EVERY_TICKS = 40;
    /** Сломанное хранилище убирается из выбранных только рядом: там блок точно прогружен. */
    private static final double CLEAN_DISTANCE_SQ = 48.0 * 48.0;
    /** Дальше этого выбранные не рисуются. */
    private static final double DRAW_DISTANCE_SQ = 128.0 * 128.0;
    private static final double LABEL_DISTANCE_SQ = 32.0 * 32.0;
    private static final long PULSE_MS = 2400L;

    /** Выбранное хранилище — зелёный: «в счёте». */
    public static final int C_CHOSEN = Theme.GREEN;
    /** Выбран, но ни разу не открыт: содержимое неизвестно. */
    private static final int C_UNKNOWN = Theme.ORANGE;
    private static final int C_ENDER = 0xFFB98CF5;
    private static final int C_HOVER = 0xFFFFFFFF;

    private static boolean active;
    /** ПКМ ещё держится после переключения — повторы клика не переключают. */
    private static boolean latched;
    /** Состояние ПКМ в момент переключения (для режима «Использовать: переключение»). */
    private static boolean latchedDown;
    /** Последнее, что режим сам вывел над хотбаром: подсказку возвращаем, только пока там наше или пусто. */
    private static @Nullable Component lastShown;
    private static long noticeUntil;
    private static int ticks;

    private ChestPicker() {}

    public static boolean isActive()
    {
        return active;
    }

    /** Клавиша: войти в режим или выйти из него. */
    public static void toggleFromHotkey()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null)
        {
            return;
        }

        active = ! active;
        noticeUntil = 0L;

        if (active)
        {
            showHint(mc);
        }
        else
        {
            String scope = StorageIndex.scope(mc);
            notice(mc, Chat.tr("layerlist.message.pick_chests.done", Chat.TEXT,
                    ChosenStorages.getInstance().count(scope)));
        }
    }

    /** Выйти без сообщения (смена мира, самопроверка). */
    public static void stop()
    {
        active = false;
        latched = false;
    }

    /**
     * Easy Place Litematica спрашивает раньше {@code useItemOn}: в режиме выбора клик по хранилищу должен дойти до
     * ванили (и там погаснуть), а не уйти в установку блока схемы.
     */
    public static boolean claimsClick()
    {
        if (! active)
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = targetOf(mc.hitResult);
        return pos != null && pickable(mc, pos);
    }

    /**
     * Начало {@code useItemOn}: в режиме выбора клик по хранилищу переключает отметку и гасится. Истина — погасить.
     */
    public static boolean interceptUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit)
    {
        if (! active)
        {
            return false;
        }

        // Клики Easy Place (наш конвейер и Litematica) — не выбор: они бьют в соседний блок-опору, и это может быть
        // сундук. Ручной клик сюда приходит уже вне обработчиков: при прицеле на хранилище они его пропускают (PASS)
        if (com.aell.layerlist.easyplace.AutoPlaceState.inHandler() || fi.dy.masa.litematica.util.EasyPlaceUtils.isHandling())
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = hit.getBlockPos();

        if (! pickable(mc, pos))
        {
            return false;
        }

        // «Использовать: переключение» (специальные возможности): каждое настоящее нажатие меняет isDown, а повторы
        // удержания — нет. Отпускания там не бывает, поэтому защёлку снимает смена состояния
        boolean down = mc.options.keyUse.isDown();

        if (latched && mc.options.toggleUse().get() && down != latchedDown)
        {
            latched = false;
        }

        if (latched || hand != InteractionHand.MAIN_HAND)
        {
            return true;
        }

        latched = true;
        latchedDown = down;

        if (StorageIndex.isEnderChest(mc, pos))
        {
            toggleEnder(mc);
        }
        else
        {
            toggleChest(mc, pos);
        }

        return true;
    }

    private static boolean pickable(Minecraft mc, BlockPos pos)
    {
        return StorageIndex.isEnderChest(mc, pos) || StorageIndex.isRecordable(mc, pos);
    }

    private static void toggleChest(Minecraft mc, BlockPos pos)
    {
        String scope = StorageIndex.scope(mc);
        BlockPos canonical = StorageIndex.canonicalPos(mc, pos);
        // Половину этого сундука могли выбрать, когда он был одинарным: сначала переносим отметку, потом переключаем
        ChosenStorages.getInstance().canonicalize(scope, canonical, StorageIndex.physicalBlocks(mc, canonical));
        boolean chosen = ChosenStorages.getInstance().toggle(scope, canonical);
        int count = ChosenStorages.getInstance().count(scope);
        MutableComponent message;

        if (chosen)
        {
            boolean known = StorageIndex.getInstance().get(scope, canonical) != null;
            message = Chat.tr(known ? "layerlist.message.pick_chests.added"
                    : "layerlist.message.pick_chests.added_unknown", known ? Chat.OK : Chat.WARN, count);

            if (! LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue())
            {
                LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(true);
                message.append(Chat.text(" · ", Chat.DIM))
                        .append(Chat.tr("layerlist.message.pick_chests.enabled", Chat.TEXT));
            }
        }
        else
        {
            message = Chat.tr("layerlist.message.pick_chests.removed", Chat.DIM, count);
        }

        notice(mc, message);
    }

    private static void toggleEnder(Minecraft mc)
    {
        boolean on = ! LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue();
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(on);

        if (! on)
        {
            notice(mc, Chat.tr("layerlist.message.pick_chests.ender_off", Chat.DIM));
            return;
        }

        boolean known = com.aell.layerlist.schematics.materials.Availability.enderRecord(mc) != null;
        notice(mc, Chat.tr(known ? "layerlist.message.pick_chests.ender_on"
                : "layerlist.message.pick_chests.ender_on_unknown", known ? Chat.OK : Chat.WARN));
    }

    public static void onClientTick(Minecraft mc)
    {
        if (! mc.options.keyUse.isDown())
        {
            latched = false;
        }

        if (mc.level == null || mc.player == null)
        {
            stop();
            return;
        }

        // Клавишу сняли или сбросили, пока режим включён: выйти из него иначе было бы нечем, а ПКМ по сундукам гасился бы
        if (active && LayerListConfigs.PICK_CHESTS.getKeybind().getKeys().isEmpty())
        {
            stop();
            lastShown = null;
            return;
        }

        ++ticks;

        if (active && ticks % HINT_EVERY_TICKS == 0 && Util.getMillis() >= noticeUntil && mc.screen == null
                && overlayFree(mc))
        {
            showHint(mc);
        }

        if ((active || LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue()) && ticks % CLEAN_EVERY_TICKS == 0)
        {
            dropVanished(mc);
        }
    }

    /**
     * Хранилище сломали или заменили другим блоком — отметка с него снимается, иначе старый снимок считался бы вечно. К
     * одинарному сундуку приставили второй, и каноническая половина сменилась — отметка переезжает на неё.
     */
    private static void dropVanished(Minecraft mc)
    {
        String scope = StorageIndex.scope(mc);
        Vec3 eye = mc.player.position();
        List<BlockPos> gone = new ArrayList<>();
        List<BlockPos> moved = new ArrayList<>();

        for (BlockPos pos : ChosenStorages.getInstance().in(scope))
        {
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > CLEAN_DISTANCE_SQ || ! mc.level.isLoaded(pos))
            {
                continue;
            }

            if (! StorageIndex.isRecordable(mc, pos))
            {
                gone.add(pos);
            }
            else if (! StorageIndex.canonicalPos(mc, pos).equals(pos))
            {
                moved.add(pos);
            }
        }

        for (BlockPos pos : gone)
        {
            ChosenStorages.getInstance().remove(scope, pos);
        }

        for (BlockPos pos : moved)
        {
            BlockPos canonical = StorageIndex.canonicalPos(mc, pos);
            ChosenStorages.getInstance().canonicalize(scope, canonical, StorageIndex.physicalBlocks(mc, canonical));
        }
    }

    /** Над хотбаром пусто или там наше же: чужое сообщение (Litematica, Easy Place, другие клавиши) не перебиваем. */
    private static boolean overlayFree(Minecraft mc)
    {
        com.aell.layerlist.mixin.IMixinHud hud = (com.aell.layerlist.mixin.IMixinHud) mc.gui;
        return hud.layerlist_getOverlayMessageTime() <= 0 || hud.layerlist_getOverlayMessage() == lastShown;
    }

    private static void showHint(Minecraft mc)
    {
        String key = LayerListConfigs.PICK_CHESTS.getKeybind().getKeysDisplayString();
        int count = ChosenStorages.getInstance().count(StorageIndex.scope(mc));
        lastShown = Chat.tr("layerlist.message.pick_chests.hint", Chat.MARK, key, count);
        mc.gui.setOverlayMessage(lastShown, false);
    }

    /** Отклик на щелчок — над хотбаром и в обход «Сообщений в чат»: режим визуальный, чат им не засоряем. */
    private static void notice(Minecraft mc, Component body)
    {
        lastShown = body;
        mc.gui.setOverlayMessage(body, false);
        noticeUntil = Util.getMillis() + NOTICE_MS;
    }

    private static @Nullable BlockPos targetOf(@Nullable HitResult hit)
    {
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    /** Мир: выбранные хранилища зелёным (не открытые — оранжевым), хранилище под прицелом — белым контуром. */
    public static void render()
    {
        Minecraft mc = Minecraft.getInstance();

        if (! active || mc.player == null || mc.level == null)
        {
            return;
        }

        String scope = StorageIndex.scope(mc);
        Vec3 eye = mc.player.position();
        double phase = (Util.getMillis() % PULSE_MS) / (double) PULSE_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
        float strength = LayerListConfigs.HIGHLIGHT_GLOW.getIntegerValue() / 100.0F;
        BlockPos target = targetOf(mc.hitResult);
        BlockPos targetCanonical = target != null && StorageIndex.isRecordable(mc, target)
                ? StorageIndex.canonicalPos(mc, target) : null;
        String inCount = I18n.get("layerlist.pick_chests.label.counted");
        String unknown = I18n.get("layerlist.pick_chests.label.unknown");

        for (BlockPos pos : ChosenStorages.getInstance().in(scope))
        {
            AABB box = StorageHighlightRenderer.shapeOf(mc, pos);

            if (eye.distanceToSqr(box.getCenter()) > DRAW_DISTANCE_SQ || ! mc.level.isLoaded(pos))
            {
                continue;
            }

            boolean known = StorageIndex.getInstance().get(scope, pos) != null;
            int color = known ? C_CHOSEN : C_UNKNOWN;
            StorageHighlightRenderer.glow(box, List.of(color), strength, pulse);

            if (eye.distanceToSqr(box.getCenter()) <= LABEL_DISTANCE_SQ)
            {
                label(box, known ? inCount : unknown, color);
            }
        }

        if (target == null)
        {
            return;
        }

        if (StorageIndex.isEnderChest(mc, target))
        {
            boolean on = LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue();
            AABB box = StorageHighlightRenderer.shapeOf(mc, target);
            Gizmos.cuboid(box.inflate(0.03), GizmoStyle.stroke(
                    StorageHighlightRenderer.withAlpha(on ? C_ENDER : C_HOVER, 0.85F), 2.0F)).setAlwaysOnTop();
            label(box, I18n.get(on ? "layerlist.pick_chests.label.ender_on" : "layerlist.pick_chests.label.ender_off"),
                    on ? C_ENDER : C_HOVER);
        }
        else if (targetCanonical != null && ! ChosenStorages.getInstance().contains(scope, targetCanonical))
        {
            AABB box = StorageHighlightRenderer.shapeOf(mc, targetCanonical);
            Gizmos.cuboid(box.inflate(0.03), GizmoStyle.stroke(
                    StorageHighlightRenderer.withAlpha(C_HOVER, 0.55F + 0.3F * pulse), 2.0F)).setAlwaysOnTop();
        }
    }

    private static void label(AABB box, String text, int color)
    {
        Gizmos.billboardText(text, new Vec3((box.minX + box.maxX) / 2.0, box.maxY + 0.4, (box.minZ + box.maxZ) / 2.0),
                TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
    }
}
