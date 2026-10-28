package com.aell.layerlist.easyplace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

@Environment(EnvType.CLIENT)
public final class Controllable
{
    private static final Set<Property<?>> NOT_AT_PLACEMENT = Set.of(
            BlockStateProperties.WATERLOGGED,
            BlockStateProperties.POWERED,
            BlockStateProperties.POWER,
            BlockStateProperties.TRIGGERED,
            BlockStateProperties.ENABLED,
            BlockStateProperties.LOCKED,
            BlockStateProperties.LIT,
            BlockStateProperties.OPEN,
            BlockStateProperties.EXTENDED,
            BlockStateProperties.IN_WALL,
            BlockStateProperties.SNOWY,
            BlockStateProperties.PERSISTENT,
            BlockStateProperties.DISTANCE,
            BlockStateProperties.STABILITY_DISTANCE,
            BlockStateProperties.BOTTOM,
            BlockStateProperties.SHORT,
            BlockStateProperties.DRAG,
            BlockStateProperties.FALLING,
            BlockStateProperties.UNSTABLE,
            BlockStateProperties.CONDITIONAL,
            BlockStateProperties.SIGNAL_FIRE,
            // Установкой не задаются ни при каком клике (проверено дампом B: решатель
            // считал их своими, никогда не получал точный план и отдавал PARTIAL).
            // Режим компаратора доводится StateFix.cycling, а изголовье кровати Litematica
            // отсекает раньше — пустым стеком в MaterialCache.getStateToItemOverride.
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.BED_PART,
            BlockStateProperties.SIDE_CHAIN_PART,
            BlockStateProperties.OCCUPIED,
            BlockStateProperties.DISARMED,
            BlockStateProperties.SHRIEKING,
            BlockStateProperties.CAN_SUMMON,
            BlockStateProperties.BLOOM,
            BlockStateProperties.TIP,
            BlockStateProperties.BERRIES,
            BlockStateProperties.CRACKED,
            BlockStateProperties.DUSTED,
            BlockStateProperties.NATURAL,
            BlockStateProperties.INVERTED,
            BlockStateProperties.EYE,
            BlockStateProperties.HAS_BOOK,
            BlockStateProperties.HAS_RECORD,
            BlockStateProperties.HAS_BOTTLE_0,
            BlockStateProperties.HAS_BOTTLE_1,
            BlockStateProperties.HAS_BOTTLE_2,
            BlockStateProperties.CRAFTING,
            BlockStateProperties.OMINOUS,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.TILT,
            BlockStateProperties.DRIPSTONE_THICKNESS,
            BlockStateProperties.SCULK_SENSOR_PHASE,
            BlockStateProperties.TRIAL_SPAWNER_STATE,
            BlockStateProperties.VAULT_STATE,
            BlockStateProperties.CREAKING_HEART_STATE,
            BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BAMBOO_LEAVES,
            BlockStateProperties.MAP,
            BlockStateProperties.TEST_BLOCK_MODE,
            BlockStateProperties.STRUCTUREBLOCK_MODE,
            BlockStateProperties.NOTEBLOCK_INSTRUMENT,
            BlockStateProperties.NOTE,
            BlockStateProperties.DELAY,
            BlockStateProperties.BITES,
            BlockStateProperties.HATCH,
            BlockStateProperties.MOISTURE,
            BlockStateProperties.STAGE,
            BlockStateProperties.LEVEL,
            BlockStateProperties.LEVEL_CAULDRON,
            BlockStateProperties.LEVEL_COMPOSTER,
            BlockStateProperties.LEVEL_FLOWING,
            BlockStateProperties.LEVEL_HONEY,
            BlockStateProperties.RESPAWN_ANCHOR_CHARGES,
            BlockStateProperties.DRIED_GHAST_HYDRATION_LEVELS,
            BlockStateProperties.AGE_1,
            BlockStateProperties.AGE_2,
            BlockStateProperties.AGE_3,
            BlockStateProperties.AGE_4,
            BlockStateProperties.AGE_5,
            BlockStateProperties.AGE_7,
            BlockStateProperties.AGE_15,
            BlockStateProperties.AGE_25,
            BlockStateProperties.SLOT_0_OCCUPIED,
            BlockStateProperties.SLOT_1_OCCUPIED,
            BlockStateProperties.SLOT_2_OCCUPIED,
            BlockStateProperties.SLOT_3_OCCUPIED,
            BlockStateProperties.SLOT_4_OCCUPIED,
            BlockStateProperties.SLOT_5_OCCUPIED);

    private static final Set<Property<?>> SIDES = Set.of(
            BlockStateProperties.UP,
            BlockStateProperties.DOWN,
            BlockStateProperties.NORTH,
            BlockStateProperties.EAST,
            BlockStateProperties.SOUTH,
            BlockStateProperties.WEST,
            BlockStateProperties.NORTH_WALL,
            BlockStateProperties.EAST_WALL,
            BlockStateProperties.SOUTH_WALL,
            BlockStateProperties.WEST_WALL,
            BlockStateProperties.NORTH_REDSTONE,
            BlockStateProperties.EAST_REDSTONE,
            BlockStateProperties.SOUTH_REDSTONE,
            BlockStateProperties.WEST_REDSTONE);

    private static final Set<Property<?>> ORIENTATION = Set.of(
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.HORIZONTAL_AXIS,
            BlockStateProperties.AXIS,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.ROTATION_16,
            BlockStateProperties.HALF,
            BlockStateProperties.DOUBLE_BLOCK_HALF,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.VERTICAL_DIRECTION,
            BlockStateProperties.ATTACHED,
            // Фонарь: hanging задаётся установкой (LanternBlock), но без этой строки
            // Plan.misplaced() его не видел и «Пропускать то, что встанет неверно» не
            // отсекало клетку, где потолка ещё нет — фонарь молча вставал стоя.
            BlockStateProperties.HANGING,
            // Форма рельса. Установкой задаётся только ось (угол взгляда), остальное —
            // ванильная доводка соседями: её считает Rails.settle, и решателю форма
            // видна как обычное свойство ориентации. Без этой строки перебор угла под
            // рельс вообще не запускался — он ложился по той оси, куда смотрел игрок.
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.PISTON_TYPE);

    private Controllable() {}

    public static boolean orientation(Property<?> property)
    {
        return ORIENTATION.contains(property);
    }

    /**
     * Свойство «сторона»: у забора, стены, панели и провода это соединение с соседом,
     * у лишайника и лозы — грань, на которой блок сидит. Первое задаётся окружением,
     * второе кликом; кто есть кто, решает {@link Connections}.
     */
    public static boolean side(Property<?> property)
    {
        return SIDES.contains(property);
    }

    /**
     * Свойство, которое установкой не задаётся ни при каком клике. Для сверки двух
     * состояний это значит «расхождение здесь не мешает»: его либо чинит отдельная
     * починка, либо оно вырастает само.
     */
    public static boolean notAtPlacement(Property<?> property)
    {
        return NOT_AT_PLACEMENT.contains(property);
    }

    public static boolean orientable(BlockState wanted)
    {
        // Только то, что решатель и правда сверяет (scored): у нити «натянута» (attached) задают крюки, а не
        // клик — с ним в списке нить считалась ориентируемой, перебор поз был пуст, и клетку молча пропускали
        for (Property<?> property : scored(wanted))
        {
            if (ORIENTATION.contains(property))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Блок уже стоит, и все расхождения со схемой лежат вне того, что задаёт установка.
     *
     * <p>Решатель сверяется только со {@link #scored}: свойства из {@link #NOT_AT_PLACEMENT} и
     * боковые у не-multiface он прощает, потому что кликом их не задать. Проверка «есть ли здесь
     * работа» такого списка не знала и считала работой любое расхождение, кроме четырёх частных
     * случаев. Из-за расхождения двух взглядов стоящая ступень с недосчитанной {@code shape},
     * трава со {@code snowy} и листва с {@code distance} выглядели недоделанными: Litematica
     * теряла двухсекундную блокировку клетки, клик уходил в уже занятую клетку, а ванильный
     * {@code BlockPlaceContext} уводил установку на клетку за гранью клика — чаще всего вверх.
     *
     * <p>Счётное свойство семьи (свечи, морские огурцы) и {@code LAYERS} работой остаются: их как
     * раз добавляют новые клики, и ведёт их {@link CountedProgress}.
     */
    public static boolean placementCannotChange(BlockState wanted, BlockState world)
    {
        if (wanted.isAir() || ! world.is(wanted.getBlock()))
        {
            return false;
        }

        List<Property<?>> byPlacement = new ArrayList<>(scored(wanted));
        Property<?> counted = BlockFamilies.countProperty(wanted);

        if (counted != null)
        {
            byPlacement.add(counted);
        }

        byPlacement.add(BlockStateProperties.LAYERS);

        boolean differs = false;

        for (Property<?> property : wanted.getBlock().getStateDefinition().getProperties())
        {
            if (same(wanted, world, property))
            {
                continue;
            }

            if (byPlacement.contains(property))
            {
                return false;
            }

            differs = true;
        }

        return differs;
    }

    /**
     * Клетку ещё можно довести самой установкой, ничего не ломая: ванильный клик тем же
     * предметом заменяет блок на месте, а не уходит на клетку за гранью.
     *
     * <p>Единственный такой случай — вторая половина плиты: клик по стоящей половине тем же
     * предметом даёт полную плиту ({@code SlabBlock.canBeReplaced} разрешает замену на месте).
     * Добивает её {@link PlacementGuard#decideDoubleSlab}, и сама Litematica такую клетку
     * работой тоже считает ({@code EasyPlaceUtils.easyPlaceBlockChecksCancel} отдельной веткой
     * выпускает плиту, которой не хватает половины).
     *
     * <p>Предикат общий нарочно: без него «есть ли здесь работа» и конвейер расходились во
     * взглядах. Прицел объявлял клетку тупиком (MixinLitematicaRayTraceUtils.layerlist$deadEnd),
     * уводил луч на другую клетку, и до {@code decideDoubleSlab} дело не доходило никогда —
     * вторая половина плиты не вставала вовсе.
     */
    public static boolean placementUpgradesInPlace(BlockState wanted, BlockState world)
    {
        return world.is(wanted.getBlock())
                && wanted.hasProperty(BlockStateProperties.SLAB_TYPE)
                && world.hasProperty(BlockStateProperties.SLAB_TYPE)
                && wanted.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && world.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
    }

    /**
     * Стоит тот же блок, но разошлись свойства, которые задаёт САМА установка (facing, ось,
     * половина — всё из {@link #scored}). Кликом такую клетку не исправить: она занята, и
     * ванильный {@code BlockPlaceContext} уводит установку на клетку за гранью клика — там и
     * вырастает лишний блок. Поправить можно только сломав стоящее.
     */
    public static boolean placementOnlyDiffers(BlockState wanted, BlockState world)
    {
        // Достройка второй половины плиты (placementUpgradesInPlace, 19.09.2026) из этого
        // предиката временно убрана: сборка 0.0.23 идёт от стабильного 0.0.20 (16.09), а этот
        // клапан менял, что прицел и страховка считают работой. Вернуть — после отдельной проверки.
        if (wanted.isAir() || ! world.is(wanted.getBlock()))
        {
            return false;
        }

        for (Property<?> property : scored(wanted))
        {
            if (! same(wanted, world, property))
            {
                return true;
            }
        }

        return false;
    }

    private static <T extends Comparable<T>> boolean same(BlockState a, BlockState b,
            Property<T> property)
    {
        if (! a.hasProperty(property) || ! b.hasProperty(property))
        {
            return true;
        }

        return a.getValue(property).equals(b.getValue(property));
    }

    public static List<Property<?>> scored(BlockState wanted)
    {
        List<Property<?>> out = new ArrayList<>();
        Property<?> counted = BlockFamilies.countProperty(wanted);
        boolean facesMatter = wanted.getBlock() instanceof MultifaceBlock
                || wanted.getBlock() instanceof VineBlock;
        boolean tripwire = wanted.getBlock() instanceof TripWireBlock;

        for (Property<?> property : wanted.getBlock().getStateDefinition().getProperties())
        {
            if (property.equals(counted) || property.equals(BlockStateProperties.LAYERS))
            {
                continue;
            }

            if (NOT_AT_PLACEMENT.contains(property))
            {
                continue;
            }

            if (SIDES.contains(property) && ! facesMatter)
            {
                continue;
            }

            if (tripwire && property.equals(BlockStateProperties.ATTACHED))
            {
                continue;
            }

            out.add(property);
        }

        return out;
    }
}
