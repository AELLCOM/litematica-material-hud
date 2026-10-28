package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.hud.LayerHudRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Насколько то, что уже есть (инвентарь + сундуки), покрывает нужное — цвет колонки «Не хватает».
 * Те же три цвета и тот же смысл, что у счётчиков HUD: хватает — зелёный, есть часть — жёлтый,
 * нет ничего — красный.
 */
@Environment(EnvType.CLIENT)
public enum Coverage
{
    FULL,
    PARTIAL,
    NONE;

    public static Coverage of(long need, long have)
    {
        if (have >= need)
        {
            return FULL;
        }

        return have > 0L ? PARTIAL : NONE;
    }

    /** Цвет смысла — токены HUD, чтобы таблица и HUD красили одно и то же одинаково. */
    public int color()
    {
        return switch (this)
        {
            case FULL -> LayerHudRenderer.okColor();
            case PARTIAL -> LayerHudRenderer.partialColor();
            case NONE -> LayerHudRenderer.badColor();
        };
    }
}
