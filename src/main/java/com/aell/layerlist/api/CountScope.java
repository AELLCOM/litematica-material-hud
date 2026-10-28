package com.aell.layerlist.api;

import fi.dy.masa.litematica.selection.Box;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public interface CountScope
{
    boolean isActive();

    List<Box> getBoxes();

    String getSignature();

    String getLabel();

    default List<String> getFooterLines()
    {
        return List.of();
    }

    default boolean applyLayerRange()
    {
        return false;
    }

    default boolean searchWholeScope()
    {
        return false;
    }
}
