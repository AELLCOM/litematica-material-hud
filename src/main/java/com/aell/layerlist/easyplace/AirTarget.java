package com.aell.layerlist.easyplace;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Клетка схемы, сквозь которую луч проходит, но мимо самой голограммы.
 *
 * Litematica наводится на голограмму её собственным контуром: у цветка это крестик из
 * двух плоскостей, у свечи фигурка в четверть клетки, у лозы — тонкий столбик. Промах
 * мимо контура — и луч летит дальше, в блок за голограммой. Здесь такая клетка ловится
 * целиком, кубом.
 *
 * Список «мелких» блоков для этого не нужен и был бы неточен: критерий геометрический —
 * <i>луч прошёл сквозь клетку, но мимо контура голограммы</i>. У блока размером с клетку
 * промахнуться нельзя, поэтому сюда попадает ровно мелочь и ничего кроме неё.
 */
@Environment(EnvType.CLIENT)
public final class AirTarget
{
    private static final double STEP = 0.05D;

    private AirTarget() {}

    public static @Nullable BlockHitResult find(Level clientLevel, Entity entity, double maxRange,
            boolean furthest)
    {
        if (! EasyPlaceModule.isActive())
        {
            return null;
        }

        WorldSchematic schematic = schematicWorld();

        if (schematic == null)
        {
            return null;
        }

        Vec3 eyes = entity.getEyePosition(1.0F);
        Vec3 end = eyes.add(entity.getViewVector(1.0F).scale(maxRange));
        double limit = solidLimit(clientLevel, entity, eyes, end);
        LayerRange range = DataManager.getRenderLayerRange();
        BlockHitResult chosen = null;
        double chosenDist = -1.0D;

        for (BlockPos pos : walk(eyes, end))
        {
            BlockHitResult cube = Shapes.block().clip(eyes, end, pos);

            if (cube == null)
            {
                continue;
            }

            double dist = cube.getLocation().distanceToSqr(eyes);

            if (dist > limit || ! range.isPositionWithinRange(pos))
            {
                continue;
            }

            BlockState wanted = schematic.getBlockState(pos);
            BlockState world = clientLevel.getBlockState(pos);

            // Клетка, которую схема просит заполнить и которая ещё не заполнена
            if (wanted.isAir() || wanted == world || ! world.canBeReplaced())
            {
                continue;
            }

            // Голограмму клетки мод не рисует (нет в инвентаре, не на чем держаться) — её не видно,
            // луч идёт дальше, к видимой
            if (com.aell.layerlist.ghost.HiddenGhosts.any()
                    && com.aell.layerlist.ghost.HiddenGhosts.hidden(wanted, world, pos, clientLevel, schematic))
            {
                continue;
            }

            // Без предмета клетку не поставить (верх двери, верх пиона) — луч идёт дальше,
            // к нижней половине, иначе Easy Place умрёт на «требуемый предмет пуст»
            if (! SchematicTarget.hasItem(schematic, pos, wanted))
            {
                continue;
            }

            if (aimable(wanted, schematic, pos, eyes, end))
            {
                continue;
            }

            if (chosen == null || (furthest ? dist > chosenDist : dist < chosenDist))
            {
                chosen = cube;
                chosenDist = dist;
            }

            if (! furthest)
            {
                break;
            }
        }

        return chosen;
    }

    private static boolean aimable(BlockState state, WorldSchematic schematic, BlockPos pos, Vec3 eyes,
            Vec3 end)
    {
        VoxelShape shape = state.getShape(schematic, pos);

        return ! shape.isEmpty() && shape.clip(eyes, end, pos) != null;
    }

    private static double solidLimit(Level level, Entity entity, Vec3 eyes, Vec3 end)
    {
        HitResult trace = level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, entity));

        return trace == null || trace.getType() != HitResult.Type.BLOCK
                ? Double.MAX_VALUE
                : trace.getLocation().distanceToSqr(eyes);
    }

    private static List<BlockPos> walk(Vec3 from, Vec3 to)
    {
        List<BlockPos> out = new ArrayList<>();
        double length = from.distanceTo(to);
        BlockPos last = null;

        for (double travelled = 0.0D; travelled <= length; travelled += STEP)
        {
            Vec3 point = from.lerp(to, length == 0.0D ? 0.0D : travelled / length);
            BlockPos pos = BlockPos.containing(point);

            if (! pos.equals(last))
            {
                out.add(pos);
                last = pos;
            }
        }

        return out;
    }

    private static @Nullable WorldSchematic schematicWorld()
    {
        try
        {
            return SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
