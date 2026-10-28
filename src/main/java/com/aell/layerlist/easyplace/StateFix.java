package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.ShelfMushroomBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class StateFix
{
    public enum Path
    {
        TOOL,
        BLOCK_ACTION,
        EMPTY_HAND,
        BUCKET,
        /** Запитанная полка: сначала раскладка хотбара, потом обмен ({@link ShelfSwap}). */
        SHELF_SWAP,
        /** Полка без питания: в руке ровно нужное число, потом клик ({@link ShelfSwap#runSingle}). */
        SHELF_SINGLE
    }

    /** {@code sneak} — клик с приседом: блок не перехватит клик своим действием (ягоды не сорвутся). */
    public record Fix(String id, Path path, @Nullable Predicate<ItemStack> tool, boolean avoidDownFace,
            boolean upperHalf, @Nullable Direction yawFacing,
            @Nullable net.minecraft.world.phys.Vec3 point, @Nullable Direction clickFace, boolean sneak)
    {
        public Fix(String id, Path path, @Nullable Predicate<ItemStack> tool, boolean avoidDownFace,
                boolean upperHalf, @Nullable Direction yawFacing,
                @Nullable net.minecraft.world.phys.Vec3 point, @Nullable Direction clickFace)
        {
            this(id, path, tool, avoidDownFace, upperHalf, yawFacing, point, clickFace, false);
        }

        public Fix(String id, Path path, @Nullable Predicate<ItemStack> tool, boolean avoidDownFace,
                boolean upperHalf, @Nullable Direction yawFacing)
        {
            this(id, path, tool, avoidDownFace, upperHalf, yawFacing, null, null, false);
        }
    }

    private static final Predicate<ItemStack> IGNITER =
            stack -> stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
    private static final Predicate<ItemStack> SHOVEL = stack -> stack.is(ItemTags.SHOVELS);
    private static final Predicate<ItemStack> WATER = stack -> stack.is(Items.WATER_BUCKET);
    /** Бутылка воды: в котёл по одному уровню (ведро наливает сразу три). */
    private static final Predicate<ItemStack> WATER_BOTTLE = stack -> stack.is(Items.POTION)
            && stack.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(net.minecraft.world.item.alchemy.Potions.WATER);
    private static final Predicate<ItemStack> LAVA = stack -> stack.is(Items.LAVA_BUCKET);
    private static final Predicate<ItemStack> POWDER_SNOW = stack -> stack.is(Items.POWDER_SNOW_BUCKET);
    private static final Predicate<ItemStack> EMPTY_BUCKET = stack -> stack.is(Items.BUCKET);
    private static final Predicate<ItemStack> GLOWSTONE = stack -> stack.is(Items.GLOWSTONE);
    private static final Predicate<ItemStack> ENDER_EYE = stack -> stack.is(Items.ENDER_EYE);
    private static final Predicate<ItemStack> SHEARS = stack -> stack.getItem() instanceof ShearsItem;
    private static final Predicate<ItemStack> BONE_MEAL = stack -> stack.is(Items.BONE_MEAL);

    private StateFix() {}

    private static Fix tool(String id, Predicate<ItemStack> tool, boolean avoidDownFace)
    {
        return new Fix(id, Path.TOOL, tool, avoidDownFace, false, null);
    }

    /**
     * Полный план починки: базовые (по паре состояний) плюс заполнение полок, которому
     * нужны block entity схемы и мира. Все пути починки должны ходить сюда; парный
     * {@link #plan(BlockState, BlockState)} остаётся для быстрых проверок без позиции.
     */
    public static @Nullable Fix plan(net.minecraft.world.level.Level level,
            @Nullable net.minecraft.world.level.Level schematicWorld,
            net.minecraft.core.BlockPos pos, BlockState schematic, BlockState world)
    {
        Fix base = plan(schematic, world);

        if (base != null)
        {
            // Мотыга и лопата работают только при воздухе над клеткой — знать это можно
            // лишь здесь, где есть мир и позиция
            return Conversions.needsAirAbove(base) && ! level.getBlockState(pos.above()).isAir()
                    ? null
                    : base;
        }

        return ShelfFill.plan(level, schematicWorld, pos, schematic, world);
    }

    public static @Nullable Fix plan(BlockState schematic, BlockState world)
    {
        Fix potting = potting(schematic, world);

        if (potting != null)
        {
            return potting;
        }

        Fix stripping = stripping(schematic, world);

        if (stripping != null)
        {
            return stripping;
        }

        // Остальные ванильные пары с разными блоками: медь, грядка, тропинка, свеча на
        // торт. Они обязаны стоять выше отсева — ниже него работают только починки
        // внутри одного блока.
        Fix converted = Conversions.fix(schematic, world);

        if (converted != null)
        {
            return converted;
        }

        // Котёл — до отсева по блоку: Easy Place ставит пустой котёл, а котёл с водой — другой блок
        Fix cauldron = cauldron(schematic, world);

        if (cauldron != null)
        {
            return cauldron;
        }

        // Лозы — до отсева по блоку: на месте «тела» в мире законно стоит голова
        Fix plant = plants(schematic, world);

        if (plant != null)
        {
            return plant;
        }

        if (! world.is(schematic.getBlock()))
        {
            return null;
        }

        Fix light = light(schematic, world);

        if (light != null)
        {
            return light;
        }

        Fix water = water(schematic, world);

        if (water != null)
        {
            return water;
        }

        Fix opening = opening(schematic, world);

        if (opening != null)
        {
            return opening;
        }

        Fix toggling = toggling(schematic, world);

        if (toggling != null)
        {
            return toggling;
        }

        Fix pose = pose(schematic, world);

        if (pose != null)
        {
            return pose;
        }

        Fix filling = filling(schematic, world);

        if (filling != null)
        {
            return filling;
        }

        Fix wire = wireShape(schematic, world);

        if (wire != null)
        {
            return wire;
        }

        return cycling(schematic, world);
    }

    // Единственная починка, у которой блок в мире и блок в схеме разные: пустой горшок
    // становится горшком с растением от клика тем самым растением. Снизу по горшку не
    // попасть — он стоит на опоре, поэтому нижнюю грань обходим.
    private static @Nullable Fix potting(BlockState schematic, BlockState world)
    {
        if (! world.is(Blocks.FLOWER_POT))
        {
            return null;
        }

        Item plant = Potting.plant(schematic);

        return plant == null ? null : tool("pot", stack -> stack.is(plant), true);
    }

    // Вторая починка с разными блоками: необтёсанное бревно становится обтёсанным от
    // клика топором. Ось при этом сохраняется, поэтому её сверяет сам Stripping.
    private static @Nullable Fix stripping(BlockState schematic, BlockState world)
    {
        return Stripping.needsStrip(schematic, world)
                ? tool("strip", Stripping.AXE, false)
                : null;
    }

    /**
     * Состояние, которое установкой не задаётся ни при каком клике: дверь и люк встают
     * закрытыми, свеча — незажжённой, блок — сухим. Такую клетку {@link FollowUp} дожимает
     * следующим кликом, не дожидаясь, пока игрок снова наведётся на неё.
     */
    public static boolean mayFollowUp(BlockState schematic)
    {
        if (wants(schematic, BlockStateProperties.OPEN, true)
                || wants(schematic, BlockStateProperties.LIT, LayerListConfigs.EP_FIX_LIT.getBooleanValue())
                || wants(schematic, BlockStateProperties.WATERLOGGED,
                        LayerListConfigs.EP_FIX_WATERLOGGED.getBooleanValue()))
        {
            return true;
        }

        if (ShelfFill.handles(schematic))
        {
            return true;
        }

        if (plantNeedsFollowUp(schematic))
        {
            return true;
        }

        // Рычаг встаёт выключенным всегда — включённый в схеме дожимается кликом
        if (schematic.getBlock() instanceof LeverBlock
                && wants(schematic, BlockStateProperties.POWERED, true))
        {
            return true;
        }

        // Торт под свечной торт, земля под грядку и тропинку: заготовка встала — доводим
        // её сразу, не дожидаясь, пока прицел вернётся к этой клетке
        if (Conversions.mayFollowUp(schematic))
        {
            return true;
        }

        // Одиночный провод встаёт крестом — точку из схемы доводим кликом сразу
        if (isDot(schematic))
        {
            return true;
        }

        // Повторитель встаёт с задержкой 1, компаратор — в режиме сравнения, нотный блок —
        // на ноте 0: другое значение в схеме набирается кликами, и очередь разносит их
        // по тикам сама — иначе ряд повторителей ждал бы прицела на каждом
        if (cyclesFromDefault(schematic))
        {
            return true;
        }

        return schematic.getBlock() instanceof net.minecraft.world.level.block.CopperGolemStatueBlock
                && schematic.hasProperty(BlockStateProperties.COPPER_GOLEM_POSE)
                && schematic.getValue(BlockStateProperties.COPPER_GOLEM_POSE)
                        != net.minecraft.world.level.block.CopperGolemStatueBlock.Pose.STANDING;
    }

    /**
     * Растения, у которых состояние доводится предметом, а не установкой (0.0.23):
     * <ul>
     * <li>кончик лозы «обрезан» — голова с наибольшим возрастом, расти дальше не будет
     *     (плакучая, скручивающаяся, светящаяся лозы, ламинария): ножницами. Установка даёт
     *     возраст 0–24, поэтому любой другой возраст в схеме не воспроизводится и не важен;</li>
     * <li>ягоды светящейся лозы (и на голове, и на теле): костной мукой, лишние — пустым
     *     кликом (снимает). Мука лозу не удлиняет — в отличие от плакучей, скручивающейся и
     *     ламинарии, которым её не даём;</li>
     * <li>трутовик ({@code shelf_mushroom}) возраста 1: костной мукой.</li>
     * </ul>
     * Ножницы и мука — с приседом: без него клик по лозе с ягодами сначала сорвал бы ягоды.
     */
    private static @Nullable Fix plants(BlockState schematic, BlockState world)
    {
        if (! LayerListConfigs.EP_FINISH_TOOLS.getBooleanValue())
        {
            return null;
        }

        if (GrowingPlants.sameFamily(schematic, world))
        {
            if (GrowingPlants.berries(schematic) && ! GrowingPlants.berries(world))
            {
                return new Fix("berries", Path.TOOL, BONE_MEAL, false, false, null, null, null, true);
            }

            if (! GrowingPlants.berries(schematic) && GrowingPlants.berries(world))
            {
                return new Fix("pick_berries", Path.BLOCK_ACTION, null, false, false, null);
            }

            if (GrowingPlants.cut(schematic) && world.getBlock() instanceof GrowingPlantHeadBlock
                    && ! GrowingPlants.cut(world))
            {
                return new Fix("cut", Path.TOOL, SHEARS, false, false, null, null, null, true);
            }

            return null;
        }

        if (schematic.getBlock() instanceof ShelfMushroomBlock && world.is(schematic.getBlock())
                && schematic.getValue(ShelfMushroomBlock.FACING) == world.getValue(ShelfMushroomBlock.FACING)
                && schematic.getValue(ShelfMushroomBlock.AGE) > world.getValue(ShelfMushroomBlock.AGE))
        {
            return new Fix("mushroom", Path.TOOL, BONE_MEAL, false, false, null, null, null, true);
        }

        return null;
    }

    /** Состояние растения, которое установка не даёт никогда, — доводим следующим кликом сразу. */
    private static boolean plantNeedsFollowUp(BlockState schematic)
    {
        if (! LayerListConfigs.EP_FINISH_TOOLS.getBooleanValue())
        {
            return false;
        }

        return GrowingPlants.berries(schematic) || GrowingPlants.cut(schematic)
                || (schematic.getBlock() instanceof ShelfMushroomBlock && schematic.getValue(ShelfMushroomBlock.AGE) > 0);
    }

    private static boolean wants(BlockState schematic,
            net.minecraft.world.level.block.state.properties.BooleanProperty property, boolean enabled)
    {
        return enabled && schematic.hasProperty(property) && schematic.getValue(property);
    }

    /** Перебираемое кликами свойство в схеме стоит не так, как блок встаёт из руки. */
    private static boolean cyclesFromDefault(BlockState schematic)
    {
        BlockState fresh = schematic.getBlock().defaultBlockState();

        return differs(schematic, fresh, BlockStateProperties.DELAY)
                || differs(schematic, fresh, BlockStateProperties.MODE_COMPARATOR)
                || differs(schematic, fresh, BlockStateProperties.INVERTED)
                || differs(schematic, fresh, BlockStateProperties.NOTE);
    }

    private static @Nullable Fix light(BlockState schematic, BlockState world)
    {
        if (! LayerListConfigs.EP_FIX_LIT.getBooleanValue()
                || ! differs(schematic, world, BlockStateProperties.LIT))
        {
            return null;
        }

        boolean wantLit = schematic.getValue(BlockStateProperties.LIT);

        if (wantLit)
        {
            return CandleBlock.canLight(world) || CampfireBlock.canLight(world)
                    || CandleCakeBlock.canLight(world)
                    ? tool("light", IGNITER, false)
                    : null;
        }

        if (world.getBlock() instanceof CampfireBlock)
        {
            return tool("dowse", SHOVEL, true);
        }

        if (world.getBlock() instanceof CandleCakeBlock)
        {
            return new Fix("snuff_cake", Path.EMPTY_HAND, null, false, true, null);
        }

        if (world.getBlock() instanceof CandleBlock)
        {
            return new Fix("snuff", Path.EMPTY_HAND, null, false, false, null);
        }

        return null;
    }

    private static @Nullable Fix water(BlockState schematic, BlockState world)
    {
        if (! LayerListConfigs.EP_FIX_WATERLOGGED.getBooleanValue()
                || ! differs(schematic, world, BlockStateProperties.WATERLOGGED))
        {
            return null;
        }

        return schematic.getValue(BlockStateProperties.WATERLOGGED)
                ? new Fix("waterlog", Path.BUCKET, WATER, false, false, null)
                : new Fix("drain", Path.BUCKET, EMPTY_BUCKET, false, false, null);
    }

    private static @Nullable Fix opening(BlockState schematic, BlockState world)
    {
        if (! differs(schematic, world, BlockStateProperties.OPEN))
        {
            return null;
        }

        if (world.getBlock() instanceof DoorBlock door)
        {
            return door.type().canOpenByHand()
                    ? new Fix("open", Path.BLOCK_ACTION, null, false, false, null)
                    : null;
        }

        if (world.getBlock() instanceof TrapDoorBlock)
        {
            return world.is(Blocks.IRON_TRAPDOOR)
                    ? null
                    : new Fix("open", Path.BLOCK_ACTION, null, false, false, null);
        }

        if (world.getBlock() instanceof FenceGateBlock)
        {
            Direction facing = schematic.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? schematic.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    : null;

            return new Fix("open", Path.BLOCK_ACTION, null, false, false, facing);
        }

        return null;
    }

    // Рычаг: установка всегда даёт powered=false, включает его только отдельный клик.
    // Ровно рычаг, не всё с POWERED: кнопка отожмётся сама (клик уводил бы её в цикл),
    // а у двери и наблюдателя powered — это внешний редстоун, кликом его не задать.
    private static @Nullable Fix toggling(BlockState schematic, BlockState world)
    {
        if (! (world.getBlock() instanceof LeverBlock)
                || ! differs(schematic, world, BlockStateProperties.POWERED))
        {
            return null;
        }

        return new Fix("toggle", Path.BLOCK_ACTION, null, false, false, null);
    }

    // Поза статуи медного голема: установка всегда даёт standing, а любой клик без
    // топора циклит standing → sitting → running → star. Кликаем пустой рукой (топор в
    // главной руке снял бы окисление), по шагу за клик, пока поза не совпадёт со схемой.
    private static @Nullable Fix pose(BlockState schematic, BlockState world)
    {
        if (world.getBlock() instanceof net.minecraft.world.level.block.CopperGolemStatueBlock
                && differs(schematic, world, BlockStateProperties.COPPER_GOLEM_POSE))
        {
            return new Fix("pose", Path.EMPTY_HAND, null, false, false, null);
        }

        return null;
    }

    private static @Nullable Fix filling(BlockState schematic, BlockState world)
    {
        if (! LayerListConfigs.EP_FIX_FILLING.getBooleanValue())
        {
            return null;
        }

        if (world.getBlock() instanceof RespawnAnchorBlock
                && differs(schematic, world, BlockStateProperties.RESPAWN_ANCHOR_CHARGES)
                && schematic.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES)
                        > world.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES))
        {
            return tool("charge", GLOWSTONE, false);
        }

        if (world.getBlock() instanceof EndPortalFrameBlock
                && differs(schematic, world, BlockStateProperties.EYE)
                && schematic.getValue(BlockStateProperties.EYE))
        {
            return tool("eye", ENDER_EYE, false);
        }

        return null;
    }

    /**
     * «Наполнять блоки»: котёл. Под котёл с водой, лавой или пудровым снегом Easy Place ставит пустой котёл —
     * у налитого своего предмета нет, — и раньше доливка его не видела: она умела только добавлять уровень
     * в уже налитый (жалоба 09.10.2026, стенд {@code LL_COVERAGE=build}: котёл уровня 2 оставался пустым).
     * Вода — бутылками по уровню; полный котёл можно и ведром (ведро наливает сразу три). Лава — ведром лавы,
     * пудровый снег — ведром снега (только полный: меньше уровня ведро не даёт).
     */
    /** Пустой (или недолитый) котёл под котёл схемы — работа «Наполнять блоки», хотя блоки разные. */
    public static boolean fillsCauldron(BlockState schematic, BlockState world)
    {
        return cauldron(schematic, world) != null;
    }

    private static @Nullable Fix cauldron(BlockState schematic, BlockState world)
    {
        if (! LayerListConfigs.EP_FIX_FILLING.getBooleanValue())
        {
            return null;
        }

        boolean empty = world.is(net.minecraft.world.level.block.Blocks.CAULDRON);

        if (schematic.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON))
        {
            int want = schematic.getValue(BlockStateProperties.LEVEL_CAULDRON);
            int have = empty ? 0 : world.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON)
                    ? world.getValue(BlockStateProperties.LEVEL_CAULDRON) : -1;

            if (have < 0 || have >= want)
            {
                return null;
            }

            return tool("cauldron", want == 3 ? WATER.or(WATER_BOTTLE) : WATER_BOTTLE, false);
        }

        if (empty && schematic.is(net.minecraft.world.level.block.Blocks.LAVA_CAULDRON))
        {
            return tool("cauldron_lava", LAVA, false);
        }

        if (empty && schematic.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW_CAULDRON)
                && schematic.getValue(BlockStateProperties.LEVEL_CAULDRON) == 3)
        {
            return tool("cauldron_snow", POWDER_SNOW, false);
        }

        return null;
    }

    /**
     * Провод-точка. Одиночный провод встаёт крестом (все четыре стороны), а точкой становится
     * от клика рукой — и обратно; ваниль переключает только одиночный провод, у которого нет
     * ни одного соседа. Разница для схемы не косметическая: крест запитывает блоки по сторонам,
     * точка — нет. Для сверки же это «только соединения» ({@link Connections}), и без отдельной
     * починки точка не получалась никогда.
     */
    private static @Nullable Fix wireShape(BlockState schematic, BlockState world)
    {
        if (world.getBlock() instanceof RedstoneWireBlock
                && ((isDot(schematic) && isCross(world)) || (isCross(schematic) && isDot(world))))
        {
            return new Fix("dot", Path.BLOCK_ACTION, null, false, false, null);
        }

        return null;
    }

    private static boolean isDot(BlockState state)
    {
        return wireSidesAll(state, RedstoneSide.NONE);
    }

    private static boolean isCross(BlockState state)
    {
        return wireSidesAll(state, RedstoneSide.SIDE);
    }

    private static boolean wireSidesAll(BlockState state, RedstoneSide side)
    {
        if (! (state.getBlock() instanceof RedstoneWireBlock))
        {
            return false;
        }

        for (var property : RedstoneWireBlock.PROPERTY_BY_DIRECTION.values())
        {
            if (state.getValue(property) != side)
            {
                return false;
            }
        }

        return true;
    }

    private static @Nullable Fix cycling(BlockState schematic, BlockState world)
    {
        if (differs(schematic, world, BlockStateProperties.DELAY)
                || differs(schematic, world, BlockStateProperties.MODE_COMPARATOR)
                || differs(schematic, world, BlockStateProperties.INVERTED)
                || differs(schematic, world, BlockStateProperties.NOTE))
        {
            return new Fix("cycle", Path.BLOCK_ACTION, null, false, false, null);
        }

        return null;
    }

    private static <T extends Comparable<T>> boolean differs(BlockState schematic, BlockState world,
            Property<T> property)
    {
        return schematic.hasProperty(property) && world.hasProperty(property)
                && ! schematic.getValue(property).equals(world.getValue(property));
    }

    public static Direction face(Fix fix, Direction preferred)
    {
        if (fix.upperHalf())
        {
            return Direction.UP;
        }

        return fix.avoidDownFace() && preferred == Direction.DOWN ? Direction.UP : preferred;
    }
}
