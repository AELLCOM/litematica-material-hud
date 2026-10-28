package com.aell.layerlist.config;

import fi.dy.masa.malilib.config.options.ConfigDouble;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class ConfigDoubleStepped extends ConfigDouble
{
    private final double step;

    public ConfigDoubleStepped(String name, double defaultValue, double minValue, double maxValue, double step)
    {
        super(name, defaultValue, minValue, maxValue, true, "");
        this.step = step;
    }

    @Override
    protected double getClampedValue(double value)
    {
        return super.getClampedValue(Math.round(value / this.step) * this.step);
    }
}
