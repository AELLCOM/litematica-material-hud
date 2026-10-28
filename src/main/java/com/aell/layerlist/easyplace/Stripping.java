package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Обтёсанные брёвна и древесина: своего рецепта установки у них нет — в мире их
 * получают топором из обычного бревна. Схема просит обтёсанное, а в сундуках лежит
 * обычное, и позиция стоит.
 *
 * Модуль делает то же, что игрок: ставит необтёсанный аналог (ось у него та же) и
 * тем же топором обтёсывает его следующим кликом — так же, как свеча ставится и
 * потом поджигается огнивом. Если обтёсанный блок у игрока есть, подмены не будет.
 */
@Environment(EnvType.CLIENT)
public final class Stripping
{
    public static final Predicate<ItemStack> AXE = stack -> stack.is(ItemTags.AXES);

    private static @Nullable Map<Block, Block> plainByStripped;

    private Stripping() {}

    public static boolean enabled()
    {
        return LayerListConfigs.EP_STRIP_LOGS.getBooleanValue();
    }

    /** Необтёсанный блок, который топором превращается в этот. */
    public static @Nullable Block plainFor(BlockState stripped)
    {
        return map().get(stripped.getBlock());
    }

    /** В клетке стоит необтёсанный аналог с той же осью — остаётся пройтись топором. */
    public static boolean needsStrip(BlockState schematic, BlockState world)
    {
        if (! enabled())
        {
            return false;
        }

        Block plain = plainFor(schematic);

        return plain != null && world.is(plain) && sameAxis(schematic, world);
    }

    /**
     * Чем ставить клетку с обтёсанным блоком: сам обтёсанный, если он у игрока есть,
     * иначе необтёсанный аналог — при условии, что и он, и топор в наличии.
     */
    public static @Nullable ItemStack substitute(Player player, BlockState schematic)
    {
        if (! enabled())
        {
            return null;
        }

        Block plain = plainFor(schematic);

        if (plain == null)
        {
            return null;
        }

        Item wanted = schematic.getBlock().asItem();
        Item fallback = plain.asItem();

        if (fallback == Items.AIR
                || (wanted != Items.AIR && Hands.has(player, stack -> stack.is(wanted))))
        {
            return null;
        }

        return Hands.has(player, stack -> stack.is(fallback)) && Hands.has(player, AXE)
                ? new ItemStack(fallback)
                : null;
    }

    /**
     * Что на самом деле встанет от этого клика: если в руке необтёсанный аналог, решателю
     * состояния надо подбирать клик под него (ось у обоих одна), а не под обтёсанный блок
     * из схемы — иначе позиция будет пропущена как «встанет неверно».
     */
    public static BlockState placeInstead(BlockState schematic, ItemStack stack)
    {
        if (! enabled())
        {
            return schematic;
        }

        Block plain = plainFor(schematic);

        return plain != null && stack.getItem() == plain.asItem()
                ? plain.withPropertiesOf(schematic)
                : schematic;
    }

    private static boolean sameAxis(BlockState schematic, BlockState world)
    {
        if (! schematic.hasProperty(BlockStateProperties.AXIS)
                || ! world.hasProperty(BlockStateProperties.AXIS))
        {
            return true;
        }

        return schematic.getValue(BlockStateProperties.AXIS)
                == world.getValue(BlockStateProperties.AXIS);
    }

    private static Map<Block, Block> map()
    {
        Map<Block, Block> cached = plainByStripped;

        if (cached != null)
        {
            return cached;
        }

        Map<Block, Block> out = new HashMap<>();

        try
        {
            for (Map.Entry<Block, Block> entry : ToolTables.strippables().entrySet())
            {
                out.putIfAbsent(entry.getValue(), entry.getKey());
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            out.clear();
        }

        plainByStripped = out;

        return out;
    }
}
