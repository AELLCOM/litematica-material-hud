package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * «Брать инструмент для копания». Перед тем как блок начнёт ломаться, в руку берётся самый
 * быстрый подходящий инструмент из инвентаря — так же, как Easy Place берёт нужный блок для
 * установки: хотбар сменой слота, остальной инвентарь через штатный pick-block Litematica.
 *
 * <p>Инструмент, с которым блок не выпадет (камень без кирки), не выигрывает даже у пустой
 * руки. Только в выживании: в креативе блок ломается мгновенно чем угодно.
 *
 * <p>Выбор — «гонка с форой»: рука держит лидерство, пока какой-нибудь слот не окажется быстрее
 * лидера больше чем на {@link #LEAD}; при равенстве остаётся то, что раньше (рука, затем меньший
 * номер слота). Слоты экипировки (броня, вторая рука) в гонке не участвуют.
 */
@Environment(EnvType.CLIENT)
public final class ToolPick
{
    // Чем блок не добыть, тот ставим ниже любой настоящей скорости копания — даже ниже пустой руки
    private static final float NO_DROPS = -1.0F;
    // Насколько слот должен обогнать лидера, чтобы рука сменилась: равные по скорости руку не меняют
    private static final float LEAD = 1.0E-4F;

    private ToolPick() {}

    /** Вызывается в начале копания; сама решает, включена ли она. */
    public static void before(BlockPos pos)
    {
        if (! LayerListConfigs.EP_AUTO_TOOL.getBooleanValue() || ! EasyPlaceModule.isActive()
                || ! DigGuard.easyPlaceOn())
        {
            return;
        }

        pick(pos);
    }

    /** Взять лучший инструмент для блока в этой клетке. false — сменить руку не вышло. */
    static boolean pick(BlockPos pos)
    {
        Minecraft mc = Minecraft.getInstance();
        BlockState target = digTarget(mc, pos);

        if (target == null)
        {
            return false;
        }

        Inventory inventory = mc.player.getInventory();
        int slot = fasterSlot(inventory.getNonEquipmentItems(), target, rate(mc.player.getMainHandItem(), target));

        // Рука и так не хуже любого слота — менять нечего, но и неудачей это не считается
        return slot < 0 || takeInHand(mc, inventory, slot, pos);
    }

    /** Что будет копаться, или null — подбирать не для чего (нет игрока или мира, креатив, воздух). */
    private static @Nullable BlockState digTarget(Minecraft mc, BlockPos pos)
    {
        if (mc.player == null || mc.level == null || mc.player.isCreative())
        {
            return null;
        }

        BlockState state = mc.level.getBlockState(pos);

        return state.isAir() ? null : state;
    }

    /** Номер слота, который обгоняет руку (и всех, кто обогнал её раньше); -1 — никто не обогнал. */
    private static int fasterSlot(List<ItemStack> items, BlockState target, float handRate)
    {
        int leader = -1;
        float toBeat = handRate + LEAD;

        for (int slot = 0; slot < items.size(); ++slot)
        {
            ItemStack stack = items.get(slot);
            float rate = stack.isEmpty() ? Float.NaN : rate(stack, target);

            // NaN не больше ничего: пустой слот гонку не выигрывает
            if (rate > toBeat)
            {
                leader = slot;
                toBeat = rate + LEAD;
            }
        }

        return leader;
    }

    /** Скорость копания этим предметом с учётом того, выпадет ли блок вообще. */
    private static float rate(ItemStack stack, BlockState target)
    {
        boolean drops = ! target.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(target);

        return drops ? stack.getDestroySpeed(target) : NO_DROPS;
    }

    /** Хотбар — сменой выбранного слота; остальное — штатным pick-block Litematica. */
    private static boolean takeInHand(Minecraft mc, Inventory inventory, int slot, BlockPos pos)
    {
        ItemStack tool = inventory.getItem(slot);

        if (Inventory.isHotbarSlot(slot))
        {
            inventory.setSelectedSlot(slot);
        }
        else if (! pickFromStorage(mc, slot, tool))
        {
            return false;
        }

        EpDebug.say("инструмент для " + EpDebug.at(pos) + ": " + tool.getItem());

        return true;
    }

    private static boolean pickFromStorage(Minecraft mc, int slot, ItemStack tool)
    {
        try
        {
            fi.dy.masa.litematica.util.InventoryUtils.setPickedItemToHand(slot, tool.copy(), mc);
            return true;
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }
}
