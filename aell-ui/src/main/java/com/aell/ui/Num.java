package com.aell.ui;

import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class Num
{
    private Num() {}

    public enum Scale
    {
        LINEAR,
        LOG
    }

    public static final int COMPACT_FROM = 10_000;

    public static float toFraction(int value, int min, int max, Scale scale)
    {
        if (max <= min)
        {
            return 0.0F;
        }

        if (value <= min)
        {
            return 0.0F;
        }

        if (value >= max)
        {
            return 1.0F;
        }

        if (scale == Scale.LOG && min > 0)
        {
            double lo = Math.log(min);
            double hi = Math.log(max);

            return (float) ((Math.log(value) - lo) / (hi - lo));
        }

        return (float) (value - min) / (float) (max - min);
    }

    public static int fromFraction(float fraction, int min, int max, Scale scale)
    {
        float f = Ui.clamp01(fraction);

        if (f <= 0.0F)
        {
            return min;
        }

        if (f >= 1.0F)
        {
            return max;
        }

        if (scale == Scale.LOG && min > 0)
        {
            double lo = Math.log(min);
            double hi = Math.log(max);

            return Ui.clamp((int) Math.round(Math.exp(lo + (hi - lo) * f)), min, max);
        }

        return Ui.clamp(Math.round(min + (max - min) * f), min, max);
    }

    public static float toFraction(double value, double min, double max)
    {
        if (max <= min)
        {
            return 0.0F;
        }

        return Ui.clamp01((float) ((value - min) / (max - min)));
    }

    public static double snap(double value, double min, double max, double step)
    {
        double clamped = Math.min(max, Math.max(min, value));
        double steps = Math.round((clamped - min) / step);

        return Math.min(max, min + steps * step);
    }

    public static int stepCount(double min, double max, double step)
    {
        return (int) Math.round((max - min) / step) + 1;
    }

    public static String compact(int value)
    {
        int abs = Math.abs(value);

        if (abs < COMPACT_FROM)
        {
            return String.valueOf(value);
        }

        if (abs >= 1_000_000)
        {
            double m = value / 1_000_000.0D;

            return m == Math.rint(m) && Math.abs(m) < 10.0D
                    ? String.format(Locale.ROOT, "%.1fM", m)
                    : trim(String.format(Locale.ROOT, "%.1fM", m));
        }

        double k = value / 1000.0D;

        return k == Math.rint(k)
                ? (long) k + "K"
                : trim(String.format(Locale.ROOT, "%.1fK", k));
    }

    private static String trim(String text)
    {
        return text.replace(".0M", "M").replace(".0K", "K");
    }

    public static String format(double value, String pattern)
    {
        return String.format(Locale.ROOT, pattern, value);
    }
}
