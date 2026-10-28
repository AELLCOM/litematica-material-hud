package com.aell.layerlist.easyplace;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Наполнение запитанной полки (0.0.23) — её «второе состояние».
 *
 * <p>Полка под сигналом редстоуна ({@code powered=true}, на вид — цельная панель) кликом
 * не кладёт один предмет, а меняет местами всю свою цепочку (до трёх полок, по три слота,
 * слева направо) со слотами хотбара {@code [9 − 3N … 8]} — целыми стопками
 * ({@code ShelfBlock.swapHotbar}). Поэтому сначала раскладываем хотбар: в каждом слоте
 * «окна» — ровно то, что схема просит в соответствующем слоте полки (столько же штук),
 * или пусто. Чужое из окна уходит в свободные ячейки инвентаря. Каждый вызов — один шаг:
 * убрать лишнее, положить недостающее, и, когда окно сложено, — клик по полке. Состояния
 * между вызовами нет: шаг выбирается по текущему хотбару, поэтому лаг и чужой клик ничего
 * не ломают. Без всех нужных предметов обмен не делаем: он снял бы с полки и уже
 * правильные.
 */
@Environment(EnvType.CLIENT)
public final class ShelfSwap
{
    private static final int SLOTS = 3;
    /** Номера слотов меню инвентаря игрока: основной инвентарь 9–35, хотбар 36–44. */
    private static final int MENU_HOTBAR = 36;

    private ShelfSwap() {}

    /** План для запитанной полки, если хоть один слот цепочки не такой, как в схеме. */
    static @Nullable StateFix.Fix plan(Level level, Level schematicWorld, BlockPos pos, BlockState world)
    {
        List<BlockPos> chain = chain(level, pos, world);

        if (chain.isEmpty())
        {
            return null;
        }

        for (BlockPos part : chain)
        {
            List<ItemStack> wanted = ShelfFill.items(schematicWorld.getBlockEntity(part), SLOTS);
            List<ItemStack> placed = ShelfFill.items(level.getBlockEntity(part), SLOTS);

            for (int slot = 0; slot < SLOTS; ++slot)
            {
                if (! wanted.get(slot).isEmpty() && ! placed.get(slot).is(wanted.get(slot).getItem()))
                {
                    Direction facing = world.getValue(ShelfBlock.FACING);

                    return new StateFix.Fix("shelf_swap", StateFix.Path.SHELF_SWAP, null, false, false, null,
                            ShelfFill.slotSpot(pos, facing, 1, 0, 1), facing);
                }
            }
        }

        return null;
    }

    /** Один шаг: раскладка окна хотбара или, когда оно сложено, клик-обмен. */
    static boolean run(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world, StateFix.Fix fix,
            Direction preferredFace)
    {
        Level schematicWorld = schematicWorld();

        if (mc.level == null || mc.gameMode == null || schematicWorld == null)
        {
            return false;
        }

        List<BlockPos> chain = chain(mc.level, pos, world);

        if (chain.isEmpty())
        {
            return false;
        }

        ItemStack[] window = window(schematicWorld, chain);
        int first = 9 - window.length;
        Inventory inventory = player.getInventory();

        // 0. Рука — вне окна. Опустевший слот в руке автопополнение (Inventory Profiles Next и подобные) тут же заполняет
        //    той же вещью, и окно не складывалось никогда (14.10.2026). Окно меньше хотбара — рука уходит на слот левее
        //    окна; окно во весь хотбар — на слот, где полке нужен предмет: его шаг 1 кладёт, не опустошая руку.
        int hand = inventory.getSelectedSlot();

        if (hand >= first && ! player.hasInfiniteMaterials())
        {
            int wantHand = first > 0 ? first - 1 : first + wantedSlot(window, hand - first);

            if (wantHand != hand)
            {
                inventory.setSelectedSlot(wantHand);
                EpDebug.say("полка " + EpDebug.at(pos) + ": рука — на слот хотбара " + (wantHand + 1) + ", вне обмена");
                return true;
            }
        }

        // 1. Лишнее из окна — вон: чужой предмет или больше штук, чем нужно
        for (int k = 0; k < window.length; ++k)
        {
            int hotbar = first + k;
            ItemStack have = inventory.getItem(hotbar);
            ItemStack want = window[k];

            if (have.isEmpty() || (! want.isEmpty() && ItemStack.isSameItemSameComponents(have, want)
                    && have.getCount() == want.getCount()))
            {
                continue;
            }

            if (player.hasInfiniteMaterials())
            {
                setCreative(mc, player, hotbar, want.isEmpty() ? ItemStack.EMPTY : want.copy());
                EpDebug.say("полка " + EpDebug.at(pos) + ": слот хотбара " + (hotbar + 1) + " — творческий режим");
                return true;
            }

            int free = freeSlot(inventory, first);

            if (free < 0)
            {
                EpDebug.say("полка " + EpDebug.at(pos) + ": в инвентаре нет места, чтобы освободить хотбар");
                return false;
            }

            if (! want.isEmpty() && ItemStack.isSameItemSameComponents(have, want) && have.getCount() > want.getCount())
            {
                // Нужного больше, чем просит полка: берём стопку, кладём сколько нужно, остаток — в свободную ячейку
                click(mc, player, MENU_HOTBAR + hotbar, 0, ContainerInput.PICKUP);

                for (int i = 0; i < want.getCount(); ++i)
                {
                    click(mc, player, MENU_HOTBAR + hotbar, 1, ContainerInput.PICKUP);
                }

                click(mc, player, free, 0, ContainerInput.PICKUP);
            }
            else if (hotbar == inventory.getSelectedSlot() && ! want.isEmpty())
            {
                // Рука в окне (окно во весь хотбар): лишнее — вон и нужное — в руку одним шагом, пустой рука не остаётся
                int source = source(inventory, want, first);

                if (source < 0)
                {
                    AutoPlaceState.countMissingItem(pos, want);
                    return false;
                }

                int menuSource = source < 9 ? MENU_HOTBAR + source : source;
                click(mc, player, MENU_HOTBAR + hotbar, 0, ContainerInput.PICKUP);
                click(mc, player, free, 0, ContainerInput.PICKUP);
                click(mc, player, menuSource, 0, ContainerInput.PICKUP);

                for (int i = 0; i < want.getCount(); ++i)
                {
                    click(mc, player, MENU_HOTBAR + hotbar, 1, ContainerInput.PICKUP);
                }

                click(mc, player, menuSource, 0, ContainerInput.PICKUP);
                EpDebug.say("полка " + EpDebug.at(pos) + ": в руке (слот хотбара " + (hotbar + 1) + ") — "
                        + want.getHoverName().getString() + " ×" + want.getCount());
                return true;
            }
            else
            {
                click(mc, player, free, hotbar, ContainerInput.SWAP);
            }

            EpDebug.say("полка " + EpDebug.at(pos) + ": освободил слот хотбара " + (hotbar + 1));
            return true;
        }

        // 2. Недостающее — в окно, ровно столько, сколько просит полка
        for (int k = 0; k < window.length; ++k)
        {
            int hotbar = first + k;
            ItemStack want = window[k];

            if (want.isEmpty() || ! inventory.getItem(hotbar).isEmpty())
            {
                continue;
            }

            if (player.hasInfiniteMaterials())
            {
                setCreative(mc, player, hotbar, want.copy());
                return true;
            }

            int source = source(inventory, want, first);

            if (source < 0)
            {
                AutoPlaceState.countMissingItem(pos, want);
                return false;
            }

            int menuSource = source < 9 ? MENU_HOTBAR + source : source;
            ItemStack stack = inventory.getItem(source);

            if (stack.getCount() == want.getCount())
            {
                click(mc, player, menuSource, hotbar, ContainerInput.SWAP);
            }
            else
            {
                click(mc, player, menuSource, 0, ContainerInput.PICKUP);

                for (int i = 0; i < want.getCount(); ++i)
                {
                    click(mc, player, MENU_HOTBAR + hotbar, 1, ContainerInput.PICKUP);
                }

                click(mc, player, menuSource, 0, ContainerInput.PICKUP);
            }

            EpDebug.say("полка " + EpDebug.at(pos) + ": положил в слот хотбара " + (hotbar + 1) + " "
                    + want.getHoverName().getString() + " ×" + want.getCount());
            return true;
        }

        // 3. Окно сложено — обмен
        EpDebug.say("полка " + EpDebug.at(pos) + ": обмен цепочки из " + chain.size() + " с хотбаром");
        return StateFixer.click(mc, player, pos, world, fix, preferredFace, InteractionHand.MAIN_HAND, false);
    }

    /**
     * Полка без питания: клик кладёт в слот всю стопку из руки. Поэтому сначала в руке
     * оказывается ровно столько, сколько просит схема (обычно один), — иначе пять блоков
     * травы уходили в первый слот, а на второй уже не хватало. Руку игрока без нужды не
     * трогаем: сначала ищем слот хотбара, где уже лежит ровно нужное, потом пустой, и только
     * если хотбар полон — освобождаем текущий.
     */
    static boolean runSingle(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world, StateFix.Fix fix,
            Direction preferredFace)
    {
        Level schematicWorld = schematicWorld();

        if (mc.level == null || mc.gameMode == null || schematicWorld == null)
        {
            return false;
        }

        ItemStack want = firstMissing(mc.level, schematicWorld, pos);

        if (want == null)
        {
            return false;
        }

        Inventory inventory = player.getInventory();

        // Уже есть слот хотбара ровно с нужным — выбираем его и кликаем
        for (int hotbar = 0; hotbar < 9; ++hotbar)
        {
            if (exact(inventory.getItem(hotbar), want))
            {
                inventory.setSelectedSlot(hotbar);
                return StateFixer.click(mc, player, pos, world, fix, preferredFace, InteractionHand.MAIN_HAND, false);
            }
        }

        if (player.hasInfiniteMaterials())
        {
            setCreative(mc, player, inventory.getSelectedSlot(), want.copy());
            return true;
        }

        int empty = -1;

        for (int hotbar = 0; hotbar < 9 && empty < 0; ++hotbar)
        {
            if (inventory.getItem(hotbar).isEmpty())
            {
                empty = hotbar;
            }
        }

        if (empty < 0)
        {
            // Хотбар полон: та же вещь стопкой больше — отщипываем лишнее (слот не пустеет), иначе освобождаем слот не в
            // руке: опустевшую руку автопополнение (Inventory Profiles Next и подобные) тут же заполнило бы той же вещью
            int free = freeSlot(inventory, 9);

            if (free < 0)
            {
                EpDebug.say("полка " + EpDebug.at(pos) + ": в инвентаре нет места, чтобы отложить лишнее");
                return false;
            }

            for (int hotbar = 0; hotbar < 9; ++hotbar)
            {
                ItemStack have = inventory.getItem(hotbar);

                if (ItemStack.isSameItemSameComponents(have, want) && have.getCount() > want.getCount())
                {
                    click(mc, player, MENU_HOTBAR + hotbar, 0, ContainerInput.PICKUP);

                    for (int i = 0; i < want.getCount(); ++i)
                    {
                        click(mc, player, MENU_HOTBAR + hotbar, 1, ContainerInput.PICKUP);
                    }

                    click(mc, player, free, 0, ContainerInput.PICKUP);
                    EpDebug.say("полка " + EpDebug.at(pos) + ": отложил лишнее из слота хотбара " + (hotbar + 1));
                    return true;
                }
            }

            int spare = (inventory.getSelectedSlot() + 1) % 9;
            click(mc, player, free, spare, ContainerInput.SWAP);
            EpDebug.say("полка " + EpDebug.at(pos) + ": освободил слот хотбара " + (spare + 1));
            return true;
        }

        int source = source(inventory, want, 9);
        int sourceHotbar = -1;

        if (source < 0)
        {
            // Нужное лежит только в хотбаре стопкой больше — отщипнём из неё в пустой слот
            for (int hotbar = 0; hotbar < 9; ++hotbar)
            {
                ItemStack stack = inventory.getItem(hotbar);

                if (ItemStack.isSameItemSameComponents(stack, want) && stack.getCount() > want.getCount())
                {
                    sourceHotbar = hotbar;
                    break;
                }
            }

            if (sourceHotbar < 0)
            {
                AutoPlaceState.countMissingItem(pos, want);
                return false;
            }
        }

        // source — номер ячейки инвентаря; хотбар (0–8) в меню инвентаря — 36–44, а слоты меню 0–8 — крафт и броня
        // (до 14.10.2026 клик уходил туда: нужное лежит в хотбаре стопкой больше — шаг крутился вхолостую, а из
        // хотбара 6–9 рука брала броню)
        int menuSource = source >= 0 ? (source < 9 ? MENU_HOTBAR + source : source) : MENU_HOTBAR + sourceHotbar;
        ItemStack stack = source >= 0 ? inventory.getItem(source) : inventory.getItem(sourceHotbar);

        if (stack.getCount() == want.getCount())
        {
            click(mc, player, menuSource, empty, ContainerInput.SWAP);
        }
        else
        {
            click(mc, player, menuSource, 0, ContainerInput.PICKUP);

            for (int i = 0; i < want.getCount(); ++i)
            {
                click(mc, player, MENU_HOTBAR + empty, 1, ContainerInput.PICKUP);
            }

            click(mc, player, menuSource, 0, ContainerInput.PICKUP);
        }

        inventory.setSelectedSlot(empty);
        EpDebug.say("полка " + EpDebug.at(pos) + ": в руку " + want.getHoverName().getString() + " ×" + want.getCount());
        return true;
    }

    /** Первый слот полки, где схема просит предмет, а его нет, — сам предмет с нужным числом. */
    private static @Nullable ItemStack firstMissing(Level level, Level schematicWorld, BlockPos pos)
    {
        List<ItemStack> wanted = ShelfFill.items(schematicWorld.getBlockEntity(pos), SLOTS);
        List<ItemStack> placed = ShelfFill.items(level.getBlockEntity(pos), SLOTS);

        for (int slot = 0; slot < SLOTS; ++slot)
        {
            ItemStack want = wanted.get(slot);

            if (! want.isEmpty() && ! placed.get(slot).is(want.getItem()))
            {
                return want.copyWithCount(Math.max(1, want.getCount()));
            }
        }

        return null;
    }

    private static boolean exact(ItemStack have, ItemStack want)
    {
        return ! have.isEmpty() && ItemStack.isSameItemSameComponents(have, want) && have.getCount() == want.getCount();
    }

    /** Цепочка запитанных полок, в которую входит эта, слева направо — как её видит игра. */
    private static List<BlockPos> chain(Level level, BlockPos pos, BlockState world)
    {
        if (! (world.getBlock() instanceof ShelfBlock shelf) || ! world.getValue(ShelfBlock.POWERED))
        {
            return List.of();
        }

        return shelf.getAllBlocksConnectedTo(level, pos);
    }

    /** Что должно лежать в окне хотбара: слот k ↔ полка k / 3, слот k % 3. */
    private static ItemStack[] window(Level schematicWorld, List<BlockPos> chain)
    {
        ItemStack[] window = new ItemStack[chain.size() * SLOTS];

        for (int part = 0; part < chain.size(); ++part)
        {
            List<ItemStack> wanted = ShelfFill.items(schematicWorld.getBlockEntity(chain.get(part)), SLOTS);

            for (int slot = 0; slot < SLOTS; ++slot)
            {
                ItemStack want = wanted.get(slot);
                window[part * SLOTS + slot] = want.isEmpty() ? ItemStack.EMPTY : want.copyWithCount(Math.max(1, want.getCount()));
            }
        }

        return window;
    }

    /** Слот окна для руки: этот, если полке в нём нужен предмет, иначе первый такой; нет ни одного — этот. */
    private static int wantedSlot(ItemStack[] window, int slot)
    {
        if (! window[slot].isEmpty())
        {
            return slot;
        }

        for (int k = 0; k < window.length; ++k)
        {
            if (! window[k].isEmpty())
            {
                return k;
            }
        }

        return slot;
    }

    /** Свободная ячейка основного инвентаря (9–35) или хотбара вне окна; номер — для меню. */
    private static int freeSlot(Inventory inventory, int windowStart)
    {
        for (int slot = 9; slot < 36; ++slot)
        {
            if (inventory.getItem(slot).isEmpty())
            {
                return slot;
            }
        }

        for (int slot = 0; slot < windowStart; ++slot)
        {
            if (inventory.getItem(slot).isEmpty())
            {
                return MENU_HOTBAR + slot;
            }
        }

        return -1;
    }

    /** Где взять нужный предмет: любая ячейка вне окна. */
    private static int source(Inventory inventory, ItemStack want, int windowStart)
    {
        for (int slot = 0; slot < 36; ++slot)
        {
            if (slot >= windowStart && slot < 9)
            {
                continue;
            }

            ItemStack stack = inventory.getItem(slot);

            if (! stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, want) && stack.getCount() >= want.getCount())
            {
                return slot;
            }
        }

        return -1;
    }

    private static void click(Minecraft mc, LocalPlayer player, int menuSlot, int button, ContainerInput input)
    {
        // Свой клик уходит на сервер до установки и по порядку — ждать его подтверждения ей незачем
        InventorySync.ownSwap(true);

        try
        {
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, button, input, player);
        }
        finally
        {
            InventorySync.ownSwap(false);
        }
    }

    private static void setCreative(Minecraft mc, LocalPlayer player, int hotbar, ItemStack stack)
    {
        player.getInventory().setItem(hotbar, stack);
        mc.gameMode.handleCreativeModeItemAdd(stack, MENU_HOTBAR + hotbar);
    }

    private static @Nullable Level schematicWorld()
    {
        try
        {
            return fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
