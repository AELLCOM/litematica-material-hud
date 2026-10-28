package com.aell.layerlist.easyplace;

import fi.dy.masa.litematica.mixin.block.IMixinAbstractBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Блоки, у которых предмет различается не состоянием, а компонентами: узор
 * баннера, черепки горшка, профиль головы. Состояние блока у трёх баннеров с
 * разными узорами одно и то же, поэтому подбор по состоянию их не различает.
 * И невидимый блок света: уровень у него в состоянии, но предмет «Свет» одного
 * вида на все 16 уровней — уровень лежит в компоненте {@code block_state}, и
 * Litematica, сверяя руку только по виду предмета, ставила свет 15 вместо 7.
 *
 * У Litematica требуемый предмет берётся как
 * {@code litematica_getPickStack(world, pos, state, false)} — без данных блока —
 * и кешируется в MaterialCache по одному BlockState, так что узор теряется
 * дважды. Здесь мы строим предмет с данными и сравниваем именно те компоненты,
 * которые задают его тождество.
 */
@Environment(EnvType.CLIENT)
public final class ItemData
{
    private ItemData() {}

    /** Стоит ли для этого блока смотреть на компоненты, а не только на состояние. */
    public static boolean matters(BlockState state)
    {
        return state.getBlock() instanceof AbstractBannerBlock
                || state.getBlock() instanceof DecoratedPotBlock
                || state.getBlock() instanceof AbstractSkullBlock
                || state.getBlock() instanceof LightBlock;
    }

    /** Требуемый предмет вместе с данными блока (узором, черепками, профилем). */
    public static ItemStack required(LevelReader schematicWorld, BlockPos pos, BlockState state)
    {
        try
        {
            ItemStack stack = ((IMixinAbstractBlock) state.getBlock())
                    .litematica_getPickStack(schematicWorld, pos, state, true);

            if (stack != null && ! stack.isEmpty())
            {
                return stack;
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Схема без сущности блока или чужая реализация — падать нельзя
        }

        return state.getBlock().asItem().getDefaultInstance();
    }

    /**
     * Одинаковы ли предметы по тождеству: сам предмет и опознающие компоненты.
     * Отсутствующий компонент и пустой считаются одним и тем же — у баннера без
     * узора компонента может не быть вовсе, и иначе он не совпал бы сам с собой.
     * Имя, износ и прочее не сравниваем: переименованный баннер с тем же узором
     * для стройки годится.
     */
    public static boolean same(ItemStack stack, ItemStack required)
    {
        if (stack.isEmpty() || required.isEmpty() || ! stack.is(required.getItem()))
        {
            return false;
        }

        return patterns(stack).equals(patterns(required))
                && decorations(stack).equals(decorations(required))
                && java.util.Objects.equals(stack.get(DataComponents.PROFILE),
                        required.get(DataComponents.PROFILE))
                && lightLevel(stack) == lightLevel(required);
    }

    /** Предмет «Свет» ровно того уровня, что у блока света {@code state}. */
    public static ItemStack lightStack(BlockState state)
    {
        return LightBlock.setLightOnStack(new ItemStack(Items.LIGHT), state.getValue(LightBlock.LEVEL));
    }

    /**
     * Уровень, который поставит предмет «Свет»: из компонента {@code block_state}, без него — 15 (уровень
     * блока по умолчанию). Не свет — -1.
     */
    public static int lightLevel(ItemStack stack)
    {
        if (! stack.is(Items.LIGHT))
        {
            return -1;
        }

        Integer level = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
                .get(LightBlock.LEVEL);

        return level != null ? level : LightBlock.MAX_LEVEL;
    }

    private static BannerPatternLayers patterns(ItemStack stack)
    {
        return stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
    }

    private static PotDecorations decorations(ItemStack stack)
    {
        return stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
    }

    /**
     * Лежит ли у игрока предмет, в точности совпадающий с требуемым.
     *
     * <p>Тем же вопросом задаётся и сама Litematica: в выживании она ищет предмет через
     * {@code Inventory.findSlotMatchingItem}, а тот сверяет НАКЛАДКУ компонентов целиком.
     * Баннера с нужным узором в инвентаре нет — предмет в руку не встанет, и
     * {@code EntityUtils.getUsedHandForItem} вернёт null: клик не уйдёт вовсе.
     */
    public static boolean available(LocalPlayer player, ItemStack required)
    {
        if (required.isEmpty() || same(player.getMainHandItem(), required)
                || same(player.getOffhandItem(), required))
        {
            return true;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); ++slot)
        {
            if (same(inventory.getItem(slot), required))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Предмет игрока с теми же данными, что требует схема (узор, черепки, профиль), — копией, со всеми
     * его прочими компонентами; нет такого — сам {@code required}.
     *
     * <p>Litematica ищет предмет в инвентаре ванильным {@code findSlotMatchingItem}, а тот сверяет
     * компоненты целиком: баннер с тем же узором, но с именем или описанием (с сервера, из сундука
     * клана) не находился, в руку не вставал, и клик не уходил вовсе. Отдав ей копию предмета игрока,
     * мы сохраняем сверку по данным ({@link #same}), а поиск и выбор руки у неё срабатывают.
     */
    public static ItemStack owned(@Nullable LocalPlayer player, ItemStack required)
    {
        if (player == null || required.isEmpty())
        {
            return required;
        }

        for (ItemStack held : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()})
        {
            if (same(held, required))
            {
                return held.copyWithCount(1);
            }
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (same(stack, required))
            {
                return stack.copyWithCount(1);
            }
        }

        return required;
    }

    /**
     * Сказать вслух, что клетку не построить: нужен баннер с конкретным узором (горшок с
     * конкретными черепками), а такого в инвентаре нет.
     *
     * <p>Без этого сообщения пропажа выглядит поломкой мода: список материалов просит
     * просто «баннер» — кеш Litematica ключуется по BlockState, а узор в состояние не
     * входит, — игрок приносит баннер без узора, и клетка молча остаётся пустой. До того
     * как мод начал требовать предмет с данными, на её месте вставал баннер без узора,
     * то есть заведомо не по схеме.
     */
    public static void reportIfMissing(BlockPos pos, ItemStack required)
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || player.isCreative() || available(player, required))
        {
            return;
        }

        AutoPlaceState.countMissingItem(pos, required);
    }

    /**
     * Берёт в руку предмет, совпадающий со схемой: сначала хотбар (смена слота),
     * потом остальной инвентарь через штатный pick-block Litematica.
     * Возвращает false, если такого предмета у игрока нет.
     */
    public static boolean takeInHand(LocalPlayer player, ItemStack required)
    {
        if (same(player.getMainHandItem(), required))
        {
            return true;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < Inventory.getSelectionSize(); ++slot)
        {
            if (same(inventory.getItem(slot), required))
            {
                inventory.setSelectedSlot(slot);

                return true;
            }
        }

        for (int slot = Inventory.getSelectionSize(); slot < inventory.getNonEquipmentItems().size(); ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (! same(stack, required))
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
                return false;
            }

            return same(player.getMainHandItem(), required);
        }

        return false;
    }

    /**
     * Нужный предмет для позиции, если у блока тождество в компонентах,
     * иначе null (обычным блокам эта проверка ни к чему).
     */
    public static @Nullable ItemStack requiredIfMatters(@Nullable LevelReader schematicWorld, BlockPos pos,
            BlockState state)
    {
        if (schematicWorld == null || ! matters(state))
        {
            return null;
        }

        ItemStack required = required(schematicWorld, pos, state);

        return required.isEmpty() ? null : required;
    }
}
