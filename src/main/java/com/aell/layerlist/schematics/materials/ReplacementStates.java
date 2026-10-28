package com.aell.layerlist.schematics.materials;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Как одно состояние блока становится другим при замене в схеме (0.0.27, M25). Чистые функции над
 * реестром блоков игры: без мира, без Litematica.
 *
 * <p>Свойства переносятся по имени и значению: что есть у нового блока (направление, половина, ось,
 * вода, тип плиты…), то берётся из исходного состояния, остальное — как у нового блока по умолчанию.
 * Значение, которого у нового блока нет (направление «вверх» у блока только со сторонами света), тоже
 * остаётся по умолчанию.
 *
 * <p>Блок одного предмета бывает не один: факел и настенный факел, табличка и настенная табличка,
 * флаг и настенный флаг. Настенный вариант заменяется настенным вариантом нового предмета, если он
 * у него есть, — иначе основным блоком.
 */
@Environment(EnvType.CLIENT)
public final class ReplacementStates
{
    /** Признак «настенного» варианта в id: {@code wall_torch}, {@code oak_wall_sign}, {@code white_wall_banner}. */
    private static final String WALL = "wall_";

    private ReplacementStates() {}

    /** Состояние {@code to} со всеми совпадающими по имени и значению свойствами {@code from}. */
    public static BlockState carry(BlockState from, Block to)
    {
        if (from.getBlock() == to)
        {
            return from;
        }

        BlockState out = to.defaultBlockState();

        for (Property<?> property : from.getProperties())
        {
            Property<?> target = to.getStateDefinition().getProperty(property.getName());

            if (target != null)
            {
                out = with(out, target, valueName(from, property));
            }
        }

        return out;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value)
    {
        Optional<T> parsed = property.getValue(value);

        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }

    /**
     * Во что превращается блок {@code current} схемы, когда игрок выбрал {@code target} (основной блок
     * своего предмета). Основной блок предмета — в {@code target}; настенный вариант — в тот вариант
     * предмета {@code target}, что больше похож (тоже настенный, больше общих свойств).
     */
    public static Block variant(Block current, Block target)
    {
        if (main(current) || target.asItem() == Items.AIR)
        {
            return target;
        }

        Item item = target.asItem();
        Block best = target;
        int bestScore = likeness(current, target);

        for (Block block : BuiltInRegistries.BLOCK)
        {
            if (block == target || block.asItem() != item)
            {
                continue;
            }

            int score = likeness(current, block);

            if (score > bestScore)
            {
                best = block;
                bestScore = score;
            }
        }

        return best;
    }

    /** Похожесть вариантов: «настенность» важнее всего, дальше — сколько свойств с теми же именами. */
    private static int likeness(Block a, Block b)
    {
        int score = wall(a) == wall(b) ? 100 : 0;
        Set<String> names = new HashSet<>();

        for (Property<?> property : a.getStateDefinition().getProperties())
        {
            names.add(property.getName());
        }

        for (Property<?> property : b.getStateDefinition().getProperties())
        {
            if (names.contains(property.getName()))
            {
                ++score;
            }
        }

        return score;
    }

    private static boolean wall(Block block)
    {
        return id(block).getPath().contains(WALL);
    }

    /** Блок — основной у своего предмета (тот, что ставит сам предмет), а не настенный вариант. */
    public static boolean main(Block block)
    {
        return block.asItem() instanceof BlockItem item && item.getBlock() == block;
    }

    public static Identifier id(Block block)
    {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    /** Блок по id из файла замен; неизвестный (мод убрали) или воздух — {@code null}. */
    public static @Nullable Block block(String id)
    {
        Identifier key = Identifier.tryParse(id);

        if (key == null)
        {
            return null;
        }

        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(key);

        return block.isEmpty() || block.get().defaultBlockState().isAir() ? null : block.get();
    }

    /** Цель замены по id из файла: как {@link #block}, но воздух — это «блок отключён», он годится. */
    public static @Nullable Block target(String id)
    {
        Identifier key = Identifier.tryParse(id);

        if (key != null && BuiltInRegistries.BLOCK.getOptional(key).map(b -> b.defaultBlockState().isAir()).orElse(false))
        {
            return net.minecraft.world.level.block.Blocks.AIR;
        }

        return block(id);
    }

    /** Название блока для людей — официальное, языка игры: как у его предмета в инвентаре. */
    public static String name(Block block)
    {
        Item item = block.asItem();

        return item == Items.AIR ? block.getName().getString() : new ItemStack(item).getHoverName().getString();
    }

    /** id без пространства имён ванили — для подсказок и поиска: {@code spruce_planks}, {@code mod:thing}. */
    public static String shortId(Block block)
    {
        Identifier key = id(block);

        return "minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString().toLowerCase(Locale.ROOT);
    }
}
