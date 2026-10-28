package com.aell.layerlist.shelf;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

@Environment(EnvType.CLIENT)
public final class ShelfRenderer
{
    public static final int C_STOCKED = 0xFF57C97A;
    public static final int C_EMPTY = 0xFF6E6E7A;

    private ShelfRenderer() {}

    public static void render()
    {
        ShelfIndex index = ShelfIndex.getInstance();

        if (! index.isActive() || Minecraft.getInstance().player == null)
        {
            return;
        }

        boolean seeThrough = LayerListConfigs.VERIFY_SEE_THROUGH.getBooleanValue();
        boolean showEmpty = LayerListConfigs.SHELF_SHOW_EMPTY.getBooleanValue();

        for (ShelfIndex.Shelf shelf : index.shelves())
        {
            if (! shelf.stocked() && ! showEmpty)
            {
                continue;
            }

            boolean stocked = shelf.stocked();
            int color = stocked ? C_STOCKED : C_EMPTY;
            GizmoStyle style = stocked
                    ? GizmoStyle.strokeAndFill(color, 2.0F, (0x50 << 24) | (color & 0xFFFFFF))
                    : GizmoStyle.stroke(color, 1.0F);

            BlockPos pos = shelf.pos();
            AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D)
                    .inflate(stocked ? 0.004D : 0.001D);
            GizmoProperties gizmo = Gizmos.cuboid(box, style);

            if (seeThrough)
            {
                gizmo.setAlwaysOnTop();
            }
        }
    }
}
