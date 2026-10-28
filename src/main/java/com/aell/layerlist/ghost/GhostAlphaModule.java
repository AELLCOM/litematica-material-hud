package com.aell.layerlist.ghost;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public final class GhostAlphaModule
{
    public static final String STANDALONE_MOD_ID = "ghostalpha";

    private GhostAlphaModule() {}

    public static boolean standalonePresent()
    {
        return FabricLoader.getInstance().isModLoaded(STANDALONE_MOD_ID);
    }
}
