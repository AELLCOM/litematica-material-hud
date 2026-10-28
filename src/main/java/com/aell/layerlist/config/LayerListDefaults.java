package com.aell.layerlist.config;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class LayerListDefaults
{
    public static final boolean HUD_ENABLED = true;
    public static final int HUD_POS_X = 4;
    public static final int HUD_POS_Y = 4;

    private LayerListDefaults() {}

    public static void resetEverything()
    {
        for (IConfigBase config : LayerListConfigs.OPTIONS)
        {
            if (config instanceof IConfigResettable resettable)
            {
                resettable.resetToDefault();
            }
        }

        for (ConfigHotkey hotkey : LayerListConfigs.HOTKEYS)
        {
            hotkey.resetToDefault();
        }

        LayerListConfigs.HUD_ENABLED = HUD_ENABLED;
        LayerListConfigs.HUD_POS_X = HUD_POS_X;
        LayerListConfigs.HUD_POS_Y = HUD_POS_Y;

        new LayerListConfigs().save();
    }

    public static void clearCustomPosition()
    {
        LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(false);
        LayerListConfigs.HUD_POS_X = HUD_POS_X;
        LayerListConfigs.HUD_POS_Y = HUD_POS_Y;
    }
}
