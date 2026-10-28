package com.aell.layerlist.easyplace;

import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Огонь и огонь душ (просьба игроков 26.10.2026): своего предмета у огня нет, Litematica такую клетку не ставит вовсе.
 * Игрок зажигает его огнивом или огненным зарядом — щелчком по блоку рядом, огонь встаёт в клетку за гранью щелчка
 * ({@code FlintAndSteelItem.useOn}: клик по опоре, огонь — в {@code clickedPos.relative(face)}). Огонь душ получается
 * сам, если внизу земля или песок душ, — это уже дело схемы и пола.
 *
 * <p>Требуемый предмет клетки ({@code MixinMaterialCache}) — огниво, а нет его — огненный заряд; клик Litematica по
 * самой пустой клетке переписывается в клик по опоре: снизу (грань вверх), иначе сбоку или сверху. Опора, которая сама
 * загорится от огнива (свеча, костёр, свечной торт) или съест щелчок (сундук, дверь), не годится — берётся другая.
 */
@Environment(EnvType.CLIENT)
public final class FirePlace
{
    public static final Predicate<ItemStack> IGNITER =
            stack -> stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);

    /** Опоры по порядку: снизу, по сторонам, сверху. Грань щелчка — к клетке огня. */
    private static final Direction[] SUPPORTS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP };

    private FirePlace() {}

    public static boolean is(BlockState state)
    {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /**
     * Чем зажечь: огниво, если оно есть у игрока (или игрок в творческом); иначе огненный заряд; нет ни того ни
     * другого — огниво, чтобы Litematica сказала, чего не хватает.
     */
    public static ItemStack igniter(@Nullable LocalPlayer player)
    {
        if (player != null && ! player.hasInfiniteMaterials() && ! has(player.getInventory(), Items.FLINT_AND_STEEL)
                && has(player.getInventory(), Items.FIRE_CHARGE))
        {
            return new ItemStack(Items.FIRE_CHARGE);
        }

        return new ItemStack(Items.FLINT_AND_STEEL);
    }

    private static boolean has(Inventory inventory, net.minecraft.world.item.Item item)
    {
        for (int i = 0; i < inventory.getContainerSize(); ++i)
        {
            if (inventory.getItem(i).is(item))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Клик Easy Place в клетку огня: {@code null} — клетка не огонь; иначе клик по опоре, отмена (огонь здесь не
     * удержится, в руке не огниво, огонь уже горит).
     */
    public static PlacementGuard.@Nullable Decision decide(ClientLevel level, LocalPlayer player, ItemStack stack,
            BlockPos target, BlockState schematic, BlockState world)
    {
        if (! is(schematic))
        {
            return null;
        }

        if (is(world))
        {
            EpDebug.say("огонь " + EpDebug.at(target) + ": уже горит");
            return PlacementGuard.Decision.CANCEL;
        }

        if (! IGNITER.test(stack))
        {
            EpDebug.say("огонь " + EpDebug.at(target) + ": в руке " + stack.getItem() + ", а нужно огниво или огненный заряд");
            return PlacementGuard.Decision.CANCEL;
        }

        if (! world.canBeReplaced() || ! BaseFireBlock.canBePlacedAt(level, target, player.getDirection()))
        {
            EpDebug.say("огонь " + EpDebug.at(target) + ": здесь он не удержится (" + EpDebug.name(world) + ")");
            return PlacementGuard.Decision.CANCEL;
        }

        for (Direction side : SUPPORTS)
        {
            BlockPos support = target.relative(side);
            BlockState state = level.getBlockState(support);

            if (state.isAir() || state.canBeReplaced() || lightable(state) || Interactive.eatsRightClick(level, support, state))
            {
                continue;
            }

            Direction face = side.getOpposite();
            Vec3 spot = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            EpDebug.say("огонь " + EpDebug.at(target) + ": щелчок " + stack.getItem() + " по " + EpDebug.name(state)
                    + " " + EpDebug.at(support) + " грань " + face);

            return new PlacementGuard.Decision.Click(new BlockHitResult(spot, face, support, false), null, target, world);
        }

        EpDebug.say("огонь " + EpDebug.at(target) + ": рядом нет опоры, по которой можно щёлкнуть");
        return PlacementGuard.Decision.CANCEL;
    }

    /** Огниво зажгло бы саму опору, а не поставило огонь рядом. */
    private static boolean lightable(BlockState state)
    {
        return CandleBlock.canLight(state) || CampfireBlock.canLight(state) || CandleCakeBlock.canLight(state);
    }
}
