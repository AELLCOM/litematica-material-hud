package com.aell.layerlist.compat;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.shaded.ui.Nav;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuImpl implements ModMenuApi
{
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return screen ->
        {
            Nav.clear();
            return GuiConfigs.create(screen, "layerlist.gui.parent.modmenu");
        };
    }
}
