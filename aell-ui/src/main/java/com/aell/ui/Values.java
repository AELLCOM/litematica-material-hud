package com.aell.ui;

import fi.dy.masa.malilib.config.IConfigDouble;
import fi.dy.masa.malilib.config.IConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class Values
{
    private Values() {}

    public interface Resettable
    {
        boolean isDefault();

        void reset();
    }

    public interface Bool extends Resettable
    {
        boolean get();

        void set(boolean value);

        boolean defaultValue();
    }

    public interface Int extends Resettable
    {
        int get();

        void set(int value);

        int min();

        int max();

        int defaultValue();
    }

    public interface Dbl extends Resettable
    {
        double get();

        void set(double value);

        double min();

        double max();

        double step();

        double defaultValue();
    }

    public interface Key extends Resettable
    {
        String display();

        List<Integer> keys();

        void clearKeys();

        void addKey(int keyCode);

        String defaultDisplay();
    }

    /** Выбор из нескольких вариантов (индекс 0…count-1) с подписями. */
    public interface Choice extends Resettable
    {
        int get();

        void set(int index);

        int count();

        /** Подпись варианта, уже переведённая. */
        String label(int index);

        int defaultIndex();
    }

    /**
     * Выбор поверх целочисленной настройки: значение — индекс варианта, подписи — ключи перевода по порядку
     * (22.10.2026, стиль интерфейса «Aell UI / Vanilla / Vanilla+»).
     */
    public static Choice choice(IConfigInteger config, String... labelKeys)
    {
        return new Choice()
        {
            @Override public int get() { return Ui.clamp(config.getIntegerValue(), 0, labelKeys.length - 1); }
            @Override public void set(int index) { config.setIntegerValue(Ui.clamp(index, 0, labelKeys.length - 1)); }
            @Override public int count() { return labelKeys.length; }
            @Override public String label(int index) { return Strings.plain(labelKeys[Ui.clamp(index, 0, labelKeys.length - 1)]); }
            @Override public int defaultIndex() { return config.getDefaultIntegerValue(); }
            @Override public boolean isDefault() { return get() == defaultIndex(); }
            @Override public void reset() { set(defaultIndex()); }
        };
    }

    public static Bool of(ConfigBoolean config)
    {
        return new Bool()
        {
            @Override public boolean get() { return config.getBooleanValue(); }
            @Override public void set(boolean value) { config.setBooleanValue(value); }
            @Override public boolean defaultValue() { return config.getDefaultBooleanValue(); }
            @Override public boolean isDefault() { return ! config.isModified(); }
            @Override public void reset() { config.resetToDefault(); }
        };
    }

    public static Int of(IConfigInteger config)
    {
        return new Int()
        {
            @Override public int get() { return config.getIntegerValue(); }
            @Override public void set(int value) { config.setIntegerValue(value); }
            @Override public int min() { return config.getMinIntegerValue(); }
            @Override public int max() { return config.getMaxIntegerValue(); }
            @Override public int defaultValue() { return config.getDefaultIntegerValue(); }
            @Override public boolean isDefault() { return get() == defaultValue(); }
            @Override public void reset() { set(defaultValue()); }
        };
    }

    public static Dbl of(IConfigDouble config, double step)
    {
        return new Dbl()
        {
            @Override public double get() { return config.getDoubleValue(); }
            @Override public void set(double value) { config.setDoubleValue(value); }
            @Override public double min() { return config.getMinDoubleValue(); }
            @Override public double max() { return config.getMaxDoubleValue(); }
            @Override public double step() { return step; }
            @Override public double defaultValue() { return config.getDefaultDoubleValue(); }
            @Override public boolean isDefault() { return Math.abs(get() - defaultValue()) < step / 2.0D; }
            @Override public void reset() { set(defaultValue()); }
        };
    }

    public static Key of(ConfigHotkey config)
    {
        return new Key()
        {
            @Override
            public String display()
            {
                return pretty(config.getKeybind().getKeysDisplayString());
            }

            @Override
            public List<Integer> keys()
            {
                return config.getKeybind().getKeys();
            }

            @Override public void clearKeys() { config.getKeybind().clearKeys(); }
            @Override public void addKey(int keyCode) { config.getKeybind().addKey(keyCode); }
            @Override public boolean isDefault() { return ! config.isModified(); }
            @Override public void reset() { config.resetToDefault(); }

            @Override
            public String defaultDisplay()
            {
                return pretty(config.getDefaultStringValue());
            }
        };
    }

    public static IKeybind keybind(ConfigHotkey config)
    {
        return config.getKeybind();
    }

    public static String pretty(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return "";
        }

        String[] parts = raw.replace(" + ", ",").split(",");
        StringBuilder out = new StringBuilder();

        for (String part : parts)
        {
            String name = part.trim();

            if (name.isEmpty())
            {
                continue;
            }

            if (out.length() > 0)
            {
                out.append(" + ");
            }

            out.append(shortName(name));
        }

        return out.toString();
    }

    private static String shortName(String name)
    {
        String upper = name.toUpperCase(java.util.Locale.ROOT).replace(' ', '_');

        return switch (upper)
        {
            case "LEFT_CONTROL", "RIGHT_CONTROL", "LCONTROL", "RCONTROL" -> "Ctrl";
            case "LEFT_SHIFT", "RIGHT_SHIFT", "LSHIFT", "RSHIFT" -> "Shift";
            case "LEFT_ALT", "RIGHT_ALT", "LALT", "RALT" -> "Alt";
            case "PAGE_UP" -> "Page Up";
            case "PAGE_DOWN" -> "Page Down";
            default -> name;
        };
    }
}
