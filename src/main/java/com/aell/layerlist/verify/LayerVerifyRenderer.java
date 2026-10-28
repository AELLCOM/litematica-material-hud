package com.aell.layerlist.verify;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

@Environment(EnvType.CLIENT)
public final class LayerVerifyRenderer
{
    private LayerVerifyRenderer() {}

    public static void render()
    {
        LayerVerifier verifier = LayerVerifier.getInstance();

        if (! verifier.isHighlighting() || Minecraft.getInstance().player == null)
        {
            return;
        }

        boolean seeThrough = LayerListConfigs.VERIFY_SEE_THROUGH.getBooleanValue();
        boolean fill = LayerListConfigs.VERIFY_FILL.getBooleanValue();
        Map<LayerVerifier.Category, List<BlockPos>> visible = verifier.getVisible();

        for (Map.Entry<LayerVerifier.Category, List<BlockPos>> entry : visible.entrySet())
        {
            int color = entry.getKey().color();
            GizmoStyle style = fill
                    ? GizmoStyle.strokeAndFill(color, 2.0f, (0x40 << 24) | (color & 0xFFFFFF))
                    : GizmoStyle.stroke(color, 2.0f);

            for (BlockPos pos : entry.getValue())
            {
                AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0).inflate(0.002);
                GizmoProperties gizmo = Gizmos.cuboid(box, style);

                if (seeThrough)
                {
                    gizmo.setAlwaysOnTop();
                }
            }
        }

        // Сущности схемы (рамки, стойки, подушки…) — рамкой по габариту голограммы, теми же цветами
        for (LayerVerifier.EntityMark mark : verifier.getEntityMarks())
        {
            int color = mark.category().color();
            GizmoStyle style = fill
                    ? GizmoStyle.strokeAndFill(color, 2.0f, (0x40 << 24) | (color & 0xFFFFFF))
                    : GizmoStyle.stroke(color, 2.0f);
            GizmoProperties gizmo = Gizmos.cuboid(mark.box(), style);

            if (seeThrough)
            {
                gizmo.setAlwaysOnTop();
            }
        }
    }
}
