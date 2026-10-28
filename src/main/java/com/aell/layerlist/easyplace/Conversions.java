package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Пары «блок A + предмет → блок B», которых у Easy Place не было.
 *
 * {@link StateFix#plan} после починок горшка и обтёсывания делает жёсткий отсев
 * {@code if (! world.is(schematic.getBlock())) return null} — всё ниже работает только
 * внутри одного блока. Поэтому каждая ванильная пара с разными блоками требует ветки
 * выше отсева, и держать их поштучно в лестнице {@code plan()} не выйдет: в 26.2 таких
 * пар пять, не считая уже сделанных.
 *
 * Здесь они собраны в одном месте и в двух ролях:
 *
 * <ul>
 *   <li>{@link #fix} — доводка стоящего блока инструментом до состояния схемы: медь
 *       топором и сотами, земля мотыгой и лопатой, свеча на торт;</li>
 *   <li>{@link #substitute} — чем ставить клетку, у которой своего предмета в выживании
 *       нет: торт под свечной торт, земля под грядку и тропинку. Подменяем только когда
 *       доводящий инструмент у игрока в самом деле есть — иначе в клетке навсегда
 *       останется торт вместо свечного торта, а это хуже, чем не тронуть её вовсе.</li>
 * </ul>
 *
 * Ни одна из этих доводок не ставит блок в новую клетку: все они — клик по уже стоящему
 * блоку, который меняет его на месте. Ошибка в них не может положить лишний блок мимо
 * схемы, худший случай — потраченная прочность инструмента.
 */
@Environment(EnvType.CLIENT)
public final class Conversions
{
    private static final Predicate<ItemStack> AXE = stack -> stack.is(ItemTags.AXES);
    private static final Predicate<ItemStack> HOE = stack -> stack.is(ItemTags.HOES);
    private static final Predicate<ItemStack> SHOVEL = stack -> stack.is(ItemTags.SHOVELS);
    private static final Predicate<ItemStack> HONEYCOMB = stack -> stack.is(Items.HONEYCOMB);
    /** {@code PumpkinBlock.useItemOn} проверяет строго ванильные ножницы. */
    private static final Predicate<ItemStack> SHEARS = stack -> stack.is(Items.SHEARS);

    /** Вспахиваются в грядку именно эти; коренастая и грубая земля дают землю, а не грядку. */
    private static final Set<Block> TO_FARMLAND = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.DIRT_PATH);

    private static @Nullable Map<Block, Block> candleByCake;

    private Conversions() {}

    public static boolean enabled()
    {
        return LayerListConfigs.EP_FINISH_TOOLS.getBooleanValue();
    }

    /**
     * Клик инструментом, который превращает стоящий блок в блок схемы, либо null.
     * Свойства, общие у двух состояний, должны совпадать: топор меняет стадию окисления,
     * но не поворот ступени — если поворот разошёлся, доводка ничего не исправит.
     */
    public static @Nullable StateFix.Fix fix(BlockState schematic, BlockState world)
    {
        if (! enabled() || world.is(schematic.getBlock()) || ! sameShape(schematic, world))
        {
            return null;
        }

        StateFix.Fix copper = copper(schematic, world);

        if (copper != null)
        {
            return copper;
        }

        StateFix.Fix soil = soil(schematic, world);

        if (soil != null)
        {
            return soil;
        }

        StateFix.Fix carve = pumpkin(schematic, world);

        if (carve != null)
        {
            return carve;
        }

        return candleCake(schematic, world);
    }

    /**
     * Тыква → вырезанная тыква: ножницы режут лицо той гранью, по которой клик (клик сверху или снизу
     * взял бы направление от игрока), поэтому грань — сторона, куда лицо смотрит в схеме.
     */
    private static @Nullable StateFix.Fix pumpkin(BlockState schematic, BlockState world)
    {
        if (! schematic.is(Blocks.CARVED_PUMPKIN) || ! world.is(Blocks.PUMPKIN)
                || ! schematic.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
        {
            return null;
        }

        return new StateFix.Fix("carve", StateFix.Path.TOOL, SHEARS, true, false, null, null,
                schematic.getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    /**
     * Починке нужен воздух над клеткой: у мотыги это условие прямо в
     * {@code HoeItem.onlyIfAirAbove}, у лопаты — в {@code ShovelItem.useOn}. Без проверки
     * клик уходил бы вхолостую и повторялся каждые две секунды до конца стройки, поэтому
     * такие клетки ждут своей очереди в порядке укладки.
     */
    public static boolean needsAirAbove(StateFix.Fix fix)
    {
        return "till".equals(fix.id()) || "flatten".equals(fix.id());
    }

    /**
     * Чем ставить клетку: блок-заготовка, которую потом доведёт {@link #fix}. Отдаётся
     * только если доводящий инструмент у игрока есть — как у обтёсывания брёвен.
     */
    public static @Nullable ItemStack substitute(Player player, BlockState schematic)
    {
        if (! enabled())
        {
            return null;
        }

        if (candle(schematic) != null && Hands.has(player, candleStack(schematic))
                && Hands.has(player, stack -> stack.is(Items.CAKE)))
        {
            return new ItemStack(Items.CAKE);
        }

        if (schematic.is(Blocks.FARMLAND) && Hands.has(player, HOE)
                && Hands.has(player, stack -> stack.is(Items.DIRT)))
        {
            return new ItemStack(Items.DIRT);
        }

        if (schematic.is(Blocks.DIRT_PATH) && Hands.has(player, SHOVEL)
                && Hands.has(player, stack -> stack.is(Items.DIRT)))
        {
            return new ItemStack(Items.DIRT);
        }

        return null;
    }

    /**
     * Клетка, которую надо дожать инструментом сразу после установки заготовки, — иначе
     * доводка дождётся лишь следующего прохода прицелом по этой клетке.
     */
    public static boolean mayFollowUp(BlockState schematic)
    {
        return enabled() && (candle(schematic) != null || schematic.is(Blocks.FARMLAND)
                || schematic.is(Blocks.DIRT_PATH));
    }

    /** Заготовка для этой клетки — законный предмет при «Только по схеме». */
    public static boolean allowedItem(BlockState schematic, Block held)
    {
        if (! enabled())
        {
            return false;
        }

        Block candle = candle(schematic);

        if (candle != null)
        {
            // Свеча — тоже блок, и без этой строки «Только по схеме» съедало бы клик
            // самой доводки, если игрок делает её вручную
            return held == Blocks.CAKE || held == candle;
        }

        return (schematic.is(Blocks.FARMLAND) || schematic.is(Blocks.DIRT_PATH))
                && held == Blocks.DIRT;
    }

    // Медь: топор снимает воск и стадию окисления, соты воск наносят. Обе таблицы
    // ванильные, в 26.2 это пятнадцать семейств по 4 стадии × {обычный, вощёный} —
    // 120 блоков реестра. Обратной операции нет: окисление растёт только со временем,
    // поэтому когда схема просит стадию ПОЗЖЕ той, что стоит в мире, доводки не будет —
    // такая клетка недостижима, и честнее её не трогать.
    private static @Nullable StateFix.Fix copper(BlockState schematic, BlockState world)
    {
        Block wantedBase = unwaxed(schematic.getBlock());
        Block worldBase = unwaxed(world.getBlock());

        if (! sameFamily(wantedBase, worldBase))
        {
            return null;
        }

        boolean waxedWorld = isWaxed(world.getBlock());
        boolean waxedWanted = isWaxed(schematic.getBlock());
        int stageWorld = stage(worldBase);
        int stageWanted = stage(wantedBase);

        // Топор снимает сначала воск, потом окисление — по одному шагу за клик
        if (stageWorld > stageWanted || (waxedWorld && ! waxedWanted))
        {
            return new StateFix.Fix("copper_scrape", StateFix.Path.TOOL, AXE, false, false, null);
        }

        if (stageWorld == stageWanted && waxedWanted && ! waxedWorld)
        {
            return new StateFix.Fix("copper_wax", StateFix.Path.TOOL, HONEYCOMB, false, false, null);
        }

        return null;
    }

    // Мотыга: земля → грядка, лопата: земля → тропинка. У обеих ванильных операций
    // условие «сверху воздух», у лопаты ещё и «клик не по нижней грани», — поэтому
    // нижняя грань обходится, а порядок укладки для таких клеток важен.
    private static @Nullable StateFix.Fix soil(BlockState schematic, BlockState world)
    {
        if (schematic.is(Blocks.FARMLAND) && TO_FARMLAND.contains(world.getBlock())
                && tillable(world.getBlock()))
        {
            return new StateFix.Fix("till", StateFix.Path.TOOL, HOE, true, false, null);
        }

        BlockState flattened = flattenables().get(world.getBlock());

        if (flattened != null && schematic.is(flattened.getBlock()))
        {
            return new StateFix.Fix("flatten", StateFix.Path.TOOL, SHOVEL, true, false, null);
        }

        return null;
    }

    // Свеча на торт: CakeBlock + предмет из ItemTags.CANDLES при BITES == 0. Какая
    // именно свеча — берётся обратным ходом по CandleCakeBlock.byCandle, чтобы не
    // держать свой список из семнадцати цветов.
    private static @Nullable StateFix.Fix candleCake(BlockState schematic, BlockState world)
    {
        Block candle = candle(schematic);

        if (candle == null || ! world.is(Blocks.CAKE))
        {
            return null;
        }

        if (world.hasProperty(BlockStateProperties.BITES) && world.getValue(BlockStateProperties.BITES) != 0)
        {
            return null;
        }

        return new StateFix.Fix("candle_cake", StateFix.Path.TOOL, candleStack(schematic), true, false,
                null);
    }

    private static Predicate<ItemStack> candleStack(BlockState schematic)
    {
        Block candle = candle(schematic);

        return candle == null ? stack -> false : stack -> stack.is(candle.asItem());
    }

    /** Свеча, которая на торте даёт блок схемы, либо null, если схема просит не свечной торт. */
    private static @Nullable Block candle(BlockState schematic)
    {
        return candleMap().get(schematic.getBlock());
    }

    private static Map<Block, Block> candleMap()
    {
        Map<Block, Block> cached = candleByCake;

        if (cached != null)
        {
            return cached;
        }

        Map<Block, Block> out = new HashMap<>();

        try
        {
            for (Block block : BuiltInRegistries.BLOCK)
            {
                if (block instanceof CandleBlock candle)
                {
                    out.putIfAbsent(CandleCakeBlock.byCandle(candle).getBlock(), block);
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            out.clear();
        }

        candleByCake = out;

        return out;
    }

    private static boolean sameShape(BlockState schematic, BlockState world)
    {
        for (Property<?> property : world.getBlock().getStateDefinition().getProperties())
        {
            if (! schematic.hasProperty(property) || Controllable.notAtPlacement(property))
            {
                continue;
            }

            if (! schematic.getValue(property).equals(world.getValue(property)))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean isWaxed(Block block)
    {
        return waxOff().containsKey(block);
    }

    private static Block unwaxed(Block block)
    {
        Block plain = waxOff().get(block);

        return plain == null ? block : plain;
    }

    /**
     * Одно медное семейство. Блок вне цепочки окисления {@code getFirst} отдаёт сам
     * себя, поэтому равенство здесь возможно только для двух блоков одной цепочки — либо
     * для вощёного и обычного варианта одной стадии (случай наващивания). Пара
     * «одинаковый блок» до сюда не доходит: её отсекает проверка выше.
     */
    private static boolean sameFamily(Block a, Block b)
    {
        try
        {
            return WeatheringCopper.getFirst(a) == WeatheringCopper.getFirst(b);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Стадия окисления: 0 — свежая медь, дальше по одному шагу назад до неё. */
    private static int stage(Block block)
    {
        int steps = 0;
        Block current = block;

        for (int guard = 0; guard < 8; ++guard)
        {
            Optional<Block> previous = WeatheringCopper.getPrevious(current);

            if (previous.isEmpty() || previous.get() == current)
            {
                break;
            }

            current = previous.get();
            ++steps;
        }

        return steps;
    }

    private static Map<Block, Block> waxOff()
    {
        try
        {
            return HoneycombItem.WAX_OFF_BY_BLOCK.get();
        }
        catch (RuntimeException | LinkageError e)
        {
            return Map.of();
        }
    }

    private static Map<Block, BlockState> flattenables()
    {
        try
        {
            return ToolTables.flattenables();
        }
        catch (RuntimeException | LinkageError e)
        {
            return Map.of();
        }
    }

    private static boolean tillable(Block block)
    {
        try
        {
            return ToolTables.tillable(block);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }
}
