package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

@Environment(EnvType.CLIENT)
public final class EasyPlaceModule
{
    public static final String STANDALONE_MOD_ID = "easyplacefixplus";

    /** Имя standalone-мода до переименования: у кого стоит он, тоже должен вытеснять модуль. */
    private static final String LEGACY_STANDALONE_MOD_ID = "easyplaceplus";

    private EasyPlaceModule() {}

    public static boolean standalonePresent()
    {
        FabricLoader loader = FabricLoader.getInstance();

        return loader.isModLoaded(STANDALONE_MOD_ID) || loader.isModLoaded(LEGACY_STANDALONE_MOD_ID);
    }

    public static boolean isActive()
    {
        return ! standalonePresent() && LayerListConfigs.EP_ENABLED.getBooleanValue();
    }
}
