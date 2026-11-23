package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Мобы схемы в голограмме Litematica — только с опцией «Мобы в голограммах» (13.11.2026, по умолчанию выкл.).
 * Стойки, рамки, картины, вагонетки — не мобы и видны всегда. Счётчики — сколько мобов спрятано и показано с начала
 * игры (для стенда {@code mobs}).
 */
@Environment(EnvType.CLIENT)
public final class GhostMobs
{
    public static long hidden;
    public static long shown;

    private GhostMobs()
    {
    }

    /** Моба схемы в кадр не пускать. */
    public static boolean hide(Entity entity)
    {
        if (! (entity instanceof Mob))
        {
            return false;
        }

        if (LayerListConfigs.GHOST_SHOW_MOBS.getBooleanValue())
        {
            ++shown;
            return false;
        }

        ++hidden;
        return true;
    }
}
