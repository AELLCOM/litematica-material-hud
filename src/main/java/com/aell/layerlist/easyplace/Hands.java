package com.aell.layerlist.easyplace;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Поиск нужного предмета у игрока: рука, вторая рука, хотбар (сменой слота) и
 * остальной инвентарь (через штатный pick-block Litematica). В творческом режиме инструмента доводки
 * может не быть вовсе — тогда он берётся «из ничего», как блоки берёт pick-block Litematica (жалоба
 * 09.10.2026: в креативе не доводились книги в полке, ягоды на лозах, свечи и прочее с инструментом).
 */
@Environment(EnvType.CLIENT)
public final class Hands
{
    /** Образцы инструментов доводки для творческого режима — первый подходящий под условие. */
    private static final List<Supplier<ItemStack>> CREATIVE_SAMPLES = List.of(
            () -> new ItemStack(Items.SHEARS), () -> new ItemStack(Items.IRON_AXE), () -> new ItemStack(Items.IRON_SHOVEL),
            () -> new ItemStack(Items.IRON_HOE), () -> new ItemStack(Items.FLINT_AND_STEEL), () -> new ItemStack(Items.FIRE_CHARGE),
            () -> new ItemStack(Items.WATER_BUCKET), () -> new ItemStack(Items.BUCKET), () -> new ItemStack(Items.HONEYCOMB),
            () -> new ItemStack(Items.BONE_MEAL), () -> new ItemStack(Items.BOOK), () -> new ItemStack(Items.GLOWSTONE),
            () -> new ItemStack(Items.ENDER_EYE), () -> new ItemStack(Items.GLASS_BOTTLE),
            () -> net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
                    net.minecraft.world.item.alchemy.Potions.WATER),
            () -> new ItemStack(Items.LAVA_BUCKET), () -> new ItemStack(Items.POWDER_SNOW_BUCKET));

    private Hands() {}

    /** Инструмент «из ничего» для творческого режима; null — не творческий режим или образца под условие нет. */
    private static @Nullable ItemStack creativeSample(Player player, Predicate<ItemStack> tool)
    {
        if (! player.hasInfiniteMaterials())
        {
            return null;
        }

        for (Supplier<ItemStack> sample : CREATIVE_SAMPLES)
        {
            ItemStack stack = sample.get();

            if (tool.test(stack))
            {
                return stack;
            }
        }

        return null;
    }

    /** Рука, в которой предмет уже лежит или оказался после смены слота; null — предмета нет. */
    public static @Nullable InteractionHand with(LocalPlayer player, @Nullable Predicate<ItemStack> tool)
    {
        if (tool == null)
        {
            return InteractionHand.MAIN_HAND;
        }

        if (tool.test(player.getOffhandItem()))
        {
            return InteractionHand.OFF_HAND;
        }

        if (tool.test(player.getMainHandItem()))
        {
            return InteractionHand.MAIN_HAND;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < Inventory.getSelectionSize(); ++slot)
        {
            if (tool.test(inventory.getItem(slot)))
            {
                inventory.setSelectedSlot(slot);

                return InteractionHand.MAIN_HAND;
            }
        }

        for (int slot = Inventory.getSelectionSize(); slot < inventory.getNonEquipmentItems().size(); ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (! tool.test(stack))
            {
                continue;
            }

            try
            {
                fi.dy.masa.litematica.util.InventoryUtils.setPickedItemToHand(slot, stack.copy(),
                        Minecraft.getInstance());
            }
            catch (RuntimeException | LinkageError e)
            {
                return null;
            }

            return tool.test(player.getMainHandItem()) ? InteractionHand.MAIN_HAND : null;
        }

        // Творческий режим: инструмента нет — кладём образец в выбранный слот, как pick-block Litematica
        ItemStack sample = creativeSample(player, tool);
        Minecraft mc = Minecraft.getInstance();

        if (sample != null && mc.gameMode != null)
        {
            int slot = inventory.getSelectedSlot();
            inventory.setItem(slot, sample);
            mc.gameMode.handleCreativeModeItemAdd(sample, 36 + slot);

            return InteractionHand.MAIN_HAND;
        }

        return null;
    }

    /** Есть ли предмет где-нибудь у игрока — без смены слота и без pick-block. */
    public static boolean has(Player player, Predicate<ItemStack> tool)
    {
        if (tool.test(player.getOffhandItem()) || tool.test(player.getMainHandItem()))
        {
            return true;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); ++slot)
        {
            if (tool.test(inventory.getItem(slot)))
            {
                return true;
            }
        }

        return creativeSample(player, tool) != null;
    }
}
