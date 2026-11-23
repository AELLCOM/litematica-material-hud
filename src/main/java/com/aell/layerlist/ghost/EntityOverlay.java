package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.SchematicEntities;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Подсветка «надо поставить» для сущностей схемы (жалоба 16.11.2026: «подушки не светятся синим, как остальные
 * блоки»). Litematica подсвечивает цветом «блока нет» только клетки: сущности в её подсветку не входят вовсе,
 * и недостающая подушка выглядит так же, как уже положенная. Здесь — коробка по габариту голограммы тем же
 * цветом и с теми же выключателями (подсветка схемы, тип «блока нет», рамки, заливка, «видно насквозь»).
 * Что считать недостающим — общая сверка {@link SchematicEntities#verdict}; фильтр по инвентарю — как у блоков.
 */
@Environment(EnvType.CLIENT)
public final class EntityOverlay
{
    private static final int INTERVAL_TICKS = 10;
    /** Дальше подсветка не нужна: голограмму сущности на таком расстоянии игра уже не рисует. */
    private static final double RANGE = 64.0D;

    private static List<AABB> boxes = List.of();
    /** Недостающие блоки под подушками: голограмма подушки закрывает их собой — рисуются поверх неё. */
    private static List<AABB> covered = List.of();
    private static int ticks;

    private EntityOverlay() {}

    /** Коробки подсвеченных сейчас сущностей. */
    public static List<AABB> boxes()
    {
        return boxes;
    }

    /** Контуры недостающих блоков, закрытых голограммой подушки. */
    public static List<AABB> covered()
    {
        return covered;
    }

    /** Пересобрать подсветку сущностей на ближайшем тике (после отключения или включения в списке материалов). */
    public static void markDirty()
    {
        ticks = 0;
    }

    public static void onClientTick(Minecraft mc)
    {
        if (--ticks > 0)
        {
            return;
        }

        ticks = INTERVAL_TICKS;
        List<AABB> under = new ArrayList<>();
        boxes = build(mc, under);
        covered = under;
    }

    private static boolean enabled()
    {
        try
        {
            return LayerListConfigs.GHOST_ENTITY_OVERLAY.getBooleanValue()
                    && Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                    && Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                    && Configs.Visuals.ENABLE_SCHEMATIC_OVERLAY.getBooleanValue()
                    && Configs.Visuals.SCHEMATIC_OVERLAY_TYPE_MISSING.getBooleanValue()
                    && Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.getBooleanValue();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static List<AABB> build(Minecraft mc, List<AABB> under)
    {
        if (mc.level == null || mc.player == null || ! enabled())
        {
            return List.of();
        }


        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (world == null)
        {
            return List.of();
        }

        List<AABB> out = new ArrayList<>();

        for (SchematicEntities.Wanted wanted : SchematicEntities.collect(mc.level))
        {
            if (wanted.pos().distanceToSqr(mc.player.position()) > RANGE * RANGE
                    || ! DataManager.getRenderLayerRange().isPositionWithinRange(wanted.blockPos()))
            {
                continue;
            }

            // Нет голограммы (чанк схемы не собран, сущности скрыты) — подсвечивать нечего
            Entity ghost = SchematicEntities.ghost(world, wanted);

            if (ghost == null || SchematicEntities.hiddenByInventory(ghost))
            {
                continue;
            }

            List<SchematicEntities.Status> statuses = SchematicEntities.verdict(mc, world, wanted);

            if (statuses == null || ! statuses.contains(SchematicEntities.Status.MISSING))
            {
                continue;
            }

            out.add(ghost.getBoundingBox());
        }

        return out;
    }

    public static void render()
    {
        if ((boxes.isEmpty() && covered.isEmpty()) || Minecraft.getInstance().player == null || ! enabled())
        {
            return;
        }

        int color = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getIntegerValue();
        int line = 0xFF000000 | (color & 0xFFFFFF);
        boolean outlines = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_OUTLINES.getBooleanValue();
        boolean sides = Configs.Visuals.SCHEMATIC_OVERLAY_ENABLE_SIDES.getBooleanValue();
        boolean through = Configs.Visuals.SCHEMATIC_OVERLAY_RENDER_THROUGH.getBooleanValue();

        if (! outlines && ! sides)
        {
            return;
        }

        float width = (float) (through ? Configs.Visuals.SCHEMATIC_OVERLAY_OUTLINE_WIDTH_THROUGH.getDoubleValue()
                : Configs.Visuals.SCHEMATIC_OVERLAY_OUTLINE_WIDTH.getDoubleValue());
        GizmoStyle style = outlines && sides ? GizmoStyle.strokeAndFill(line, width, color)
                : outlines ? GizmoStyle.stroke(line, width) : GizmoStyle.fill(color);

        for (AABB box : boxes)
        {
            GizmoProperties gizmo = Gizmos.cuboid(box.inflate(0.01D), style);

            if (through)
            {
                gizmo.setAlwaysOnTop();
            }
        }

        // Опоры под подушками — всегда поверх: иначе их не видно сквозь голограмму подушки
        for (AABB box : covered)
        {
            Gizmos.cuboid(box.inflate(0.005D), GizmoStyle.strokeAndFill(line, Math.max(width, 2.0F), color)).setAlwaysOnTop();
        }
    }
}
