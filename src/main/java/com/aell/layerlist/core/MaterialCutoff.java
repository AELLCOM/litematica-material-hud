package com.aell.layerlist.core;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.core.Direction;

/**
 * Диапазон слоёв, по которому считается список материалов. Обычно это тот же диапазон, что у рендера
 * Litematica. С настройкой «Считать до Y» список берёт всё на высоте Y и ниже, а рендер слоёв не меняется
 * (22.10.2026).
 */
public final class MaterialCutoff
{
    /** Нижняя граница с запасом: ниже неё блоков в мире нет. */
    private static final int BOTTOM = -2048;

    /** Собственный диапазон. Рендер его не читает, поэтому обновлять ему ничего не нужно. */
    private static final LayerRange CUTOFF = new LayerRange(new IRangeChangeListener()
    {
        @Override
        public void updateAll()
        {
        }

        @Override
        public void updateBetweenX(int minX, int maxX)
        {
        }

        @Override
        public void updateBetweenY(int minY, int maxY)
        {
        }

        @Override
        public void updateBetweenZ(int minZ, int maxZ)
        {
        }
    });

    static
    {
        CUTOFF.setAxis(Direction.Axis.Y);
        CUTOFF.setLayerMode(LayerMode.LAYER_RANGE);
        CUTOFF.setLayerRangeMin(BOTTOM);
    }

    private MaterialCutoff()
    {
    }

    /** Граница высоты для счёта схемы; {@link Long#MAX_VALUE} — без границы. */
    public static long limitY()
    {
        return LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue()
                ? LayerListConfigs.MATERIAL_CUTOFF_Y.getIntegerValue()
                : Long.MAX_VALUE;
    }

    /** Диапазон для счёта: либо как у рендера, либо от нижней границы мира до заданной высоты. */
    public static LayerRange countRange()
    {
        if (! LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue())
        {
            return DataManager.getRenderLayerRange();
        }

        CUTOFF.setLayerRangeMax(LayerListConfigs.MATERIAL_CUTOFF_Y.getIntegerValue());

        return CUTOFF;
    }
}
