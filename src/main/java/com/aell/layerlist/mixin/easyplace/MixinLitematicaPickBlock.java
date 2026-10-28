package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.EpDebug;
import com.aell.layerlist.easyplace.ItemData;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.util.InventoryUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pick-block Litematica находит в инвентаре блок только с теми же компонентами, что у предмета схемы: и
 * ванильный {@code Inventory.findSlotMatchingItem}, и {@code swapItemToMainHand} malilib сравнивают их целиком.
 * Блок с любыми лишними данными (название, описание, метка плагина или магазина сервера) так не находится —
 * Litematica переключает руку на другую ячейку и ничего не ставит. А предмет в руке она же сверяет без
 * компонентов, поэтому взятый в руку вручную блок ставится (жалоба 12.10.2026: «не ставятся блоки иногда,
 * если я не возьму их в руку, или пока не поставлю 1 сам»).
 *
 * <p>Здесь — второй, мягкий поиск, только когда строгий ничего не нашёл: тот же предмет без учёта компонентов.
 * Повреждаемые предметы (инструменты) мягко не ищем — их Litematica различает нарочно. У предметов с данными
 * (узор баннера, черепки, голова, уровень света) мягкий поиск сверяет эти данные ({@link ItemData#same}): иначе
 * в творческом вместо точного предмета схемы в руку вставал свет 15 вместо 7 (24.10.2026).
 */
@Environment(EnvType.CLIENT)
@Mixin(value = InventoryUtils.class, remap = false)
public abstract class MixinLitematicaPickBlock
{
    // schematicWorldPickBlock ищет сам и при −1 до setPickedItemToHand не доходит — перехватываем оба поиска
    @WrapOperation(method = {"schematicWorldPickBlock",
            "setPickedItemToHand(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/Minecraft;)V"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;findSlotMatchingItem(Lnet/minecraft/world/item/ItemStack;)I"),
            require = 0)
    private static int layerlist$findLoosely(Inventory inventory, ItemStack wanted, Operation<Integer> original)
    {
        int slot = original.call(inventory, wanted);

        if (slot != -1 || ! layerlist$loose(wanted))
        {
            return slot;
        }

        int found = layerlist$looseSlot(inventory, wanted);

        if (found != -1)
        {
            EpDebug.say("pick-block: " + wanted.getItem() + " найден без учёта данных предмета (слот " + found + ")");
        }

        return found;
    }

    @WrapOperation(method = "setPickedItemToHand(ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/InventoryUtils;swapItemToMainHand(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/Minecraft;)Z"),
            require = 0)
    private static boolean layerlist$swapLoosely(ItemStack wanted, Minecraft mc, Operation<Boolean> original)
    {
        if (original.call(wanted, mc))
        {
            return true;
        }

        if (mc.player == null || ! layerlist$loose(wanted))
        {
            return false;
        }

        // Строгий обмен не нашёл стопку — меняем ту, что есть: malilib найдёт её уже точно
        ItemStack present = layerlist$looseStack(mc.player.getInventory(), wanted);

        if (present != null && original.call(present.copy(), mc))
        {
            return true;
        }

        return present != null && layerlist$swapPast(mc, wanted);
    }

    /**
     * malilib не меняет руку, если в ней уже «тот же предмет» по одному id: флаг того же цвета без узора (или с
     * другим) в руке — и нужный флаг из рюкзака не берётся никогда, клетка с узором стоит, пока игрок не возьмёт
     * его сам (жалоба 26.10.2026). Здесь — тот же обмен, что делает malilib (щелчок «поменять с ячейкой руки» в
     * инвентаре игрока), только когда данные предмета в руке и правда другие. Ожидание ответа сервера — то же,
     * что у обмена malilib ({@code MixinMalilibInventoryUtils}).
     */
    private static boolean layerlist$swapPast(Minecraft mc, ItemStack wanted)
    {
        net.minecraft.client.player.LocalPlayer player = mc.player;
        Inventory inventory = player.getInventory();
        ItemStack hand = player.getMainHandItem();

        // И наоборот: нужен флаг без узора, а в руке узорный того же цвета
        boolean data = com.aell.layerlist.core.DataItems.hasData(wanted) || com.aell.layerlist.core.DataItems.hasData(hand);

        if (mc.gameMode == null || ! data || ! ItemStack.isSameItem(hand, wanted) || ItemData.same(hand, wanted))
        {
            return false;
        }

        // Ячейка с точно такими данными (у флага без узора — тоже без узора), не сама рука
        int slot = -1;

        for (int i = 0; i < 36 && slot == -1; ++i)
        {
            ItemStack stack = inventory.getItem(i);

            if (i != inventory.getSelectedSlot() && ! stack.isEmpty() && ItemData.same(stack, wanted))
            {
                slot = i;
            }
        }

        if (slot == -1)
        {
            return false;
        }

        if (slot < Inventory.getSelectionSize())
        {
            inventory.setSelectedSlot(slot);
            EpDebug.say("pick-block: " + wanted.getItem() + " с нужными данными — ячейка хотбара " + slot);
            return true;
        }

        if (com.aell.layerlist.easyplace.AutoPlaceState.isAutomated(
                com.aell.layerlist.config.LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue())
                && com.aell.layerlist.easyplace.InventorySync.swapTooSoon())
        {
            ++com.aell.layerlist.easyplace.InventorySync.swapWaits;
            return false;
        }

        // В меню инвентаря игрока ячейки рюкзака 9–35 стоят под теми же номерами
        com.aell.layerlist.easyplace.InventorySync.ownSwap(true);

        try
        {
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, inventory.getSelectedSlot(),
                    net.minecraft.world.inventory.ContainerInput.SWAP, player);
        }
        finally
        {
            com.aell.layerlist.easyplace.InventorySync.ownSwap(false);
        }

        EpDebug.say("pick-block: в руке " + hand.getItem() + " с другими данными — меняю на слот " + slot);
        return true;
    }

    private static boolean layerlist$loose(ItemStack wanted)
    {
        return EasyPlaceModule.isActive() && ! wanted.isEmpty() && ! wanted.isDamageableItem();
    }

    /** Первый слот с тем же предметом без учёта прочих компонентов: хотбар, затем рюкзак; −1 — нет. */
    private static int layerlist$looseSlot(Inventory inventory, ItemStack wanted)
    {
        boolean data = com.aell.layerlist.core.DataItems.hasData(wanted);

        for (int slot = 0; slot < 36; ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (! stack.isEmpty() && (data ? ItemData.same(stack, wanted) : stack.is(wanted.getItem())))
            {
                return slot;
            }
        }

        return -1;
    }

    private static @Nullable ItemStack layerlist$looseStack(Inventory inventory, ItemStack wanted)
    {
        int slot = layerlist$looseSlot(inventory, wanted);
        return slot == -1 ? null : inventory.getItem(slot);
    }
}
