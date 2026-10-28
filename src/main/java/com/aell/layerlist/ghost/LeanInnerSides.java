package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * «Экономные внутренние грани»: штатный режим «внутренние грани» Litematica отключает
 * отсечение целиком — в меш попадают все грани всех блоков схемы, и видеопамять растёт
 * объёмом постройки, а не её поверхностью. Экономный режим оставляет принудительные грани
 * только на границах разных блоков: внутри однородного массива действует обычное ванильное
 * отсечение, и структура сквозь полупрозрачные голограммы читается так же, а геометрия меньше
 * на порядки. При установленном отдельном Ghost Alpha режимом владеет он.
 */
@Environment(EnvType.CLIENT)
public final class LeanInnerSides
{
    private LeanInnerSides() {}

    public static boolean active()
    {
        try
        {
            return ! GhostAlphaModule.standalonePresent()
                    && LayerListConfigs.GHOST_LEAN_INNER_SIDES.getBooleanValue()
                    && Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue()
                    && Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue();
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    public static void refreshRenderers()
    {
        try
        {
            SchematicWorldRefresher.INSTANCE.updateAll();
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }
}
