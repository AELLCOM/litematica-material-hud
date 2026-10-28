package com.aell.layerlist.ghost;

import fi.dy.masa.litematica.config.Configs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class GhostAlpha
{
    public static final int FULL = 100;

    private GhostAlpha() {}

    public static int transparency()
    {
        if (! Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue())
        {
            return 0;
        }

        return clamp(FULL - (int) Math.round(Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue() * FULL));
    }

    public static void setTransparency(int value)
    {
        int wanted = clamp(value);

        if (wanted <= 0)
        {
            Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);
        }
        else
        {
            Configs.Visuals.GHOST_BLOCK_ALPHA.setDoubleValue((FULL - wanted) / (double) FULL);
            Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
        }
    }

    public static int nudge(int delta)
    {
        int value = clamp(transparency() + delta);

        setTransparency(value);
        save();

        return value;
    }

    public static boolean isTranslucent()
    {
        return transparency() > 0;
    }

    public static boolean ghostsHidden()
    {
        return ! Configs.Visuals.ENABLE_RENDERING.getBooleanValue()
                || ! Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
                || ! Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue();
    }

    public static void resetToDefault()
    {
        Configs.Visuals.GHOST_BLOCK_ALPHA.resetToDefault();
        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.resetToDefault();
        Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.resetToDefault();
        Configs.Visuals.RENDER_ENABLE_TRANSLUCENT_RESORTING.resetToDefault();
    }

    public static void save()
    {
        try
        {
            Configs.saveToFile();
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }

    private static int clamp(int value)
    {
        return Math.max(0, Math.min(FULL, value));
    }
}
