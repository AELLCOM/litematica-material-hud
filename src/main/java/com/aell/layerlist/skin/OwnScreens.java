package com.aell.layerlist.skin;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import fi.dy.masa.litematica.gui.GuiAreaSelectionEditorNormal;
import fi.dy.masa.litematica.gui.GuiSchematicLoad;
import fi.dy.masa.litematica.gui.GuiSchematicLoadedList;
import fi.dy.masa.litematica.gui.GuiSchematicPlacementsList;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Свои экраны вместо экранов Litematica (опция {@code litematicaOwnScreens}): «Редактор региона» — вкладка
 * «Выделение», «Размещённые голограммы» и «Загруженные схемы» — «Размещения», «Загрузить схему» — «Библиотека»
 * (миксин {@code MixinGuiBaseOpen} на {@code GuiBase.openGui}). Остальные кнопки меню — экраны Litematica. Перехват — в точке, через которую Litematica открывает редактор и с кнопки меню, и
 * по своему хоткею ({@code SelectionManager.openEditGui}, миксин {@code MixinSelectionManagerOpen}). С зажатым
 * Shift — экран Litematica, как был.
 */
@Environment(EnvType.CLIENT)
public final class OwnScreens
{
    private OwnScreens() {}

    public static boolean enabled()
    {
        return SchematicsConfig.LITEMATICA_OWN_SCREENS.getBooleanValue() && ! GuiBase.isShiftDown();
    }

    /** {@code true} — открыли свою вкладку «Выделение», экран Litematica открывать не нужно. */
    public static boolean openAreaEditor(@Nullable Screen parent)
    {
        if (! enabled())
        {
            return false;
        }

        GuiSchematics.openSelection(parent);
        return true;
    }

    /**
     * Экран Litematica, вместо которого — своя вкладка: «Размещённые голограммы» и «Загруженные схемы» →
     * «Размещения», «Загрузить схему» → «Библиотека». {@code true} — открыли свою, экран Litematica не нужен.
     */
    public static boolean redirect(@Nullable Screen screen)
    {
        if (screen == null || ! enabled())
        {
            return false;
        }

        Screen parent = screen instanceof GuiBase base ? base.getParent() : null;

        if (screen instanceof GuiSchematicPlacementsList || screen instanceof GuiSchematicLoadedList)
        {
            GuiSchematics.openPlacements(parent);
            return true;
        }

        // «Редактор региона» с кнопки меню открывается не через openEditGui, а сразу экраном
        if (screen instanceof GuiAreaSelectionEditorNormal)
        {
            GuiSchematics.openSelection(parent);
            return true;
        }

        if (screen instanceof GuiSchematicLoad)
        {
            GuiSchematics.openLibrary(parent);
            return true;
        }

        return false;
    }
}
