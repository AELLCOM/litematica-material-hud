package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Железные защиты (запросы 09.10.2026, обе по умолчанию выкл) — в отличие от «Только по схеме», которая
 * отменяет только клики самого Easy Place, эти гасят любую установку блока, ручную тоже. Действуют только
 * в пределах схемы — внутри коробок включённых регионов включённых размещений; за границей ставится всё,
 * как без защиты.
 * <ul>
 *   <li>«Защита от установки мимо схемы» — в пустую клетку схемы не встаёт ничего;</li>
 *   <li>«Защита от не того блока» — в клетку, где схема ждёт блок, встаёт только он
 *       ({@link PlacementGuard#expectedItem} — с горшками, землёй под дёрн, тортом под свечной торт…).</li>
 * </ul>
 * Клетка — та, куда блок ляжет ({@link BlockPlaceContext}, с учётом «заменяемой» клетки и
 * {@code updatePlacementContext}). Клик гасится до отправки на сервер, над хотбаром — почему.
 *
 * <p>Не установка — не трогаем: в руке не блок; клик без приседа по тому, что съедает правый клик
 * ({@link Interactive#eatsRightClick}: сундук, дверь, рычаг, полка…); клетка не примет блок вовсе.
 * Мир схемы в этой клетке не загружен — не знаем, что там, и не мешаем.
 */
@Environment(EnvType.CLIENT)
public final class StrictGuard
{
    private static final long NOTICE_MS = 1500L;
    private static long noticeAt;

    private StrictGuard() {}

    /** Истина — клик ставит блок мимо схемы или не тот, его надо погасить. */
    public static boolean blocks(LocalPlayer player, InteractionHand hand, BlockHitResult hit)
    {
        boolean emptyCells = LayerListConfigs.EP_STRICT_SCHEMATIC.getBooleanValue();
        boolean wrongBlock = LayerListConfigs.EP_STRICT_BLOCK.getBooleanValue();

        // С Easy Place Fix+ защиту ведёт он (опции общие), иначе клик проверялся бы дважды
        if (EasyPlaceModule.standalonePresent() || ! emptyCells && ! wrongBlock)
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ItemStack stack = player.getItemInHand(hand);

        if (level == null || ! (stack.getItem() instanceof BlockItem item))
        {
            return false;
        }

        BlockPos clicked = hit.getBlockPos();
        BlockState clickedState = level.getBlockState(clicked);

        // Правый клик по сундуку, двери, рычагу без приседа — интеракция, не установка
        if (! player.isSecondaryUseActive() && Interactive.eatsRightClick(level, clicked, clickedState))
        {
            return false;
        }

        // Снег слоем сбоку или снизу: Easy Place перепишет клик на верх этой же клетки — это не «мимо»
        WorldSchematic early = SchematicWorldHandler.getSchematicWorld();

        if (early != null && PlacementGuard.snowFromSide(early.getBlockState(clicked), clickedState, stack, hit.getDirection()))
        {
            return false;
        }

        BlockPlaceContext context = item.updatePlacementContext(new BlockPlaceContext(player, hand, stack, hit));

        if (context == null || ! context.canPlace())
        {
            return false;
        }

        BlockPos target = context.getClickedPos();

        // Только в пределах схемы: за её границей защиты молчат
        if (! insideSchematic(target))
        {
            return false;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (schematicWorld == null || ! schematicWorld.getChunkSource().hasChunk(target.getX() >> 4, target.getZ() >> 4))
        {
            return false;
        }

        BlockState schematic = schematicWorld.getBlockState(target);

        if (schematic.isAir())
        {
            if (! emptyCells)
            {
                return false;
            }
        }
        else if (! wrongBlock || PlacementGuard.expectedItem(stack, schematic))
        {
            return false;
        }

        notice(stack, schematic);
        return true;
    }

    /** Клетка внутри коробки какого-нибудь включённого региона включённого размещения. */
    private static boolean insideSchematic(BlockPos pos)
    {
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (! placement.isEnabled())
            {
                continue;
            }

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();

                if (a != null && b != null
                        && pos.getX() >= Math.min(a.getX(), b.getX()) && pos.getX() <= Math.max(a.getX(), b.getX())
                        && pos.getY() >= Math.min(a.getY(), b.getY()) && pos.getY() <= Math.max(a.getY(), b.getY())
                        && pos.getZ() >= Math.min(a.getZ(), b.getZ()) && pos.getZ() <= Math.max(a.getZ(), b.getZ()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static void notice(ItemStack stack, BlockState schematic)
    {
        long now = Util.getMillis();

        if (now - noticeAt < NOTICE_MS)
        {
            return;
        }

        noticeAt = now;
        Component text = schematic.isAir()
                ? Component.translatable("layerlist.message.strict_empty", stack.getHoverName())
                : Component.translatable("layerlist.message.strict_other", stack.getHoverName(),
                        schematic.getBlock().getName());
        Minecraft.getInstance().gui.setOverlayMessage(text.copy().withColor(0xE0902E), false);
    }
}
