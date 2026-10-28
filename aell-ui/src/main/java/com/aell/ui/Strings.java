package com.aell.ui;

import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.locale.Language;

@Environment(EnvType.CLIENT)
public final class Strings
{
    public static final String PREFIX = "aellui.";

    private static final String FORMAT_ERROR = "Format Error:";

    private Strings() {}

    public static String plain(String key)
    {
        String translated = StringUtils.translate(key);

        if (translated.startsWith(FORMAT_ERROR))
        {
            return translated.substring(FORMAT_ERROR.length());
        }

        if (key.equals(translated))
        {
            return Language.getInstance().getOrDefault(key);
        }

        return translated;
    }

    public static String t(String suffix)
    {
        return plain(PREFIX + suffix);
    }

    public static String t(String suffix, Object... args)
    {
        return StringUtils.translate(PREFIX + suffix, args);
    }

    public static String raw(String key, String fallback)
    {
        String translated = plain(key);

        return key.equals(translated) ? fallback : translated;
    }

    public static String onOff(boolean value)
    {
        return value ? t("on") : t("off");
    }

    public static String notSet()
    {
        return t("key.unset");
    }
}
