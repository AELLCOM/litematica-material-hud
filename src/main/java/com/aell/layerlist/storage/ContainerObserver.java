package com.aell.layerlist.storage;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

/**
 * Сопоставляет открывшийся экран контейнера с блоком, по которому кликнули.
 *
 * <p>Клиент не знает, какой блок открыл экран: сервер присылает только окно. Поэтому клик по
 * контейнеру встаёт в очередь, а открывшийся экран забирает из неё позицию. Ошибка здесь
 * не «не записалось», а «записалось не туда» — содержимое одного сундука ложится на другой,
 * и отметки поиска врут, пока игрок не переоткроет оба. Отсюда три правила:
 * <ul>
 * <li>в очередь идёт только клик, который действительно ушёл на сервер ({@link #onUseItemOnReturned}
 *     зовётся с выхода {@code useItemOn}): клик, погашенный Easy Place, Litematica или
 *     другим модом, экрана не откроет и остался бы в очереди мёртвым;</li>
 * <li>повторный клик по тому же контейнеру (зажатая ПКМ при пинге больше 200 мс, двойной
 *     клик) не добавляет вторую запись, а освежает первую;</li>
 * <li>экран берёт последний клик, а остальные выбрасывает: клик, на который сервер не
 *     ответил (сундук заперт привата, придавлен блоком, на нём сидит кошка), так и не получит
 *     экрана. Если же два разных контейнера кликнуты быстрее, чем ходит ответ сервера, кому
 *     принадлежит экран, не понять — он не записывается вовсе.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class ContainerObserver
{
    private static final long PENDING_TIMEOUT_MS = 3000L;

    private static final int MAX_PENDING = 4;

    /** Два разных клика ближе этого — экран может принадлежать любому из них. */
    private static final long MIN_AMBIGUITY_MS = 400L;
    private static final long MAX_AMBIGUITY_MS = 1500L;

    private record Pending(BlockPos pos, long at) {}

    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();

    private static AbstractContainerMenu activeMenu;
    private static int activeSlots;
    private static BlockPos activePos;
    private static String activeScope;
    private static ClientLevel lastLevel;

    /** Выход из {@code useItemOn}: клик дошёл до ванили и ушёл на сервер. */
    public static void onUseItemOnReturned(LocalPlayer player, BlockPos pos, InteractionResult result)
    {
        if (! result.consumesAction())
        {
            return;
        }

        // Ваниль не открывает блок, если игрок приседает и занята хоть одна рука
        boolean handsOccupied = ! player.getMainHandItem().isEmpty() || ! player.getOffhandItem().isEmpty();
        onBlockInteracted(pos, ! (player.isSecondaryUseActive() && handsOccupied));
    }

    public static void onBlockInteracted(BlockPos pos, boolean opensScreen)
    {
        Minecraft mc = Minecraft.getInstance();

        if (! opensScreen || ! StorageIndex.isRecordable(mc, pos))
        {
            pending.clear();
            return;
        }

        BlockPos canonical = StorageIndex.canonicalPos(mc, pos);
        pending.removeIf(p -> p.pos().equals(canonical));

        if (pending.size() >= MAX_PENDING)
        {
            pending.pollFirst();
        }

        pending.addLast(new Pending(canonical, System.currentTimeMillis()));
    }

    public static void onNonBlockInteraction()
    {
        pending.clear();
    }

    public static void onClientTick(Minecraft mc)
    {
        if (mc.level != lastLevel)
        {
            saveAndClearActive(mc);
            pending.clear();
            lastLevel = mc.level;
        }

        if (mc.level == null)
        {
            return;
        }

        long now = System.currentTimeMillis();

        while (! pending.isEmpty() && now - pending.peekFirst().at() > PENDING_TIMEOUT_MS)
        {
            pending.pollFirst();
        }

        AbstractContainerMenu openedMenu = null;
        Screen screen = fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen();

        if (screen instanceof AbstractContainerScreen<?> containerScreen)
        {
            openedMenu = containerScreen.getMenu();
        }

        if (activeMenu != null && openedMenu != activeMenu)
        {
            saveAndClearActive(mc);
        }

        if (activeMenu == null && openedMenu != null && ! pending.isEmpty() && fromMenuType(openedMenu))
        {
            BlockPos interacted = takeOpener(mc);
            int slots = interacted != null ? storageSlotCount(mc, interacted, openedMenu) : 0;

            if (slots > 0)
            {
                activeMenu = openedMenu;
                activeSlots = slots;
                activePos = StorageIndex.canonicalPos(mc, interacted);
                activeScope = StorageIndex.scope(mc);

                if (StorageIndex.getInstance().removeNonCanonical(activeScope, activePos,
                        StorageIndex.physicalBlocks(mc, activePos)))
                {
                    StorageIndex.getInstance().save();
                    StorageSearch.getInstance().requestRefresh();
                }

                if (record())
                {
                    StorageIndex.getInstance().save();
                }
            }
        }
        else if (activeMenu != null && openedMenu == activeMenu)
        {
            record();
        }
    }

    /**
     * Чей это экран: последний клик, если до него не было другого клика ближе окна неоднозначности.
     * Очередь при этом пустеет целиком — кликам старше открывшегося экрана ответа уже не будет.
     */
    private static @Nullable BlockPos takeOpener(Minecraft mc)
    {
        Pending last = pending.peekLast();
        long window = ambiguityWindow(mc);
        boolean ambiguous = false;

        for (Pending p : pending)
        {
            if (p != last && last.at() - p.at() < window)
            {
                ambiguous = true;
                break;
            }
        }

        pending.clear();

        return ambiguous ? null : last.pos();
    }

    /** Два пинга с запасом: за это время сервер успевает ответить на предыдущий клик. */
    private static long ambiguityWindow(Minecraft mc)
    {
        int latency = 0;

        if (mc.getConnection() != null && mc.player != null)
        {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            latency = info != null ? info.getLatency() : 0;
        }

        return Math.clamp(2L * latency + 200L, MIN_AMBIGUITY_MS, MAX_AMBIGUITY_MS);
    }

    /**
     * Записать содержимое открытого экрана. Пока сервер не прислал слоты (номер состояния меню
     * ещё 0), меню пустое — такая запись стёрла бы содержимое сундука, поэтому ждём.
     */
    private static boolean record()
    {
        if (activeMenu.getStateId() == 0)
        {
            return false;
        }

        if (StorageIndex.getInstance().update(activeScope, activePos, activeMenu, activeSlots))
        {
            StorageSearch.getInstance().requestRefresh();
            return true;
        }

        return false;
    }

    private static boolean fromMenuType(AbstractContainerMenu menu)
    {
        try
        {
            return menu.getType() != null;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    public static void saveAndClearActive(Minecraft mc)
    {
        if (activeMenu != null && activePos != null && activeScope != null)
        {
            record();
            StorageIndex.getInstance().save();
        }

        activeMenu = null;
        activeSlots = 0;
        activePos = null;
        activeScope = null;
    }

    private static int storageSlotCount(Minecraft mc, BlockPos storage, AbstractContainerMenu menu)
    {
        if (mc.level == null)
        {
            return 0;
        }

        int expected = StorageIndex.expectedContainerSize(mc, storage);

        return expected > 0 && menu.slots.size() == expected + 36 ? expected : 0;
    }
}
