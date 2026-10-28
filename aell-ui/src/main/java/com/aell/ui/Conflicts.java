package com.aell.ui;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindCategory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class Conflicts
{
    public record Hit(@Nullable String optionKey, String label, boolean hard) {}

    private final Map<String, Hit> byOption = new HashMap<>();

    private Conflicts() {}

    public @Nullable Hit get(String optionKey)
    {
        return this.byOption.get(optionKey);
    }

    public boolean isEmpty()
    {
        return this.byOption.isEmpty();
    }

    public static Conflicts scan(List<Model.Option> options,
            java.util.function.Function<String, String> nameOf, String ownModName)
    {
        Conflicts result = new Conflicts();

        for (int i = 0; i < options.size(); ++i)
        {
            Model.Option a = options.get(i);
            List<Integer> keysA = keysOf(a);

            if (keysA.isEmpty())
            {
                continue;
            }

            for (int j = 0; j < options.size(); ++j)
            {
                if (i == j)
                {
                    continue;
                }

                Model.Option b = options.get(j);

                if (keysA.equals(keysOf(b)))
                {
                    result.byOption.put(a.key, new Hit(b.key,
                            Strings.t("conflict.hard", nameOf.apply(b.key)), true));
                    break;
                }
            }
        }

        for (Model.Option option : options)
        {
            if (result.byOption.containsKey(option.key))
            {
                continue;
            }

            List<Integer> keys = keysOf(option);

            if (keys.isEmpty())
            {
                continue;
            }

            Hit soft = findSoft(option, keys, ownModName);

            if (soft != null)
            {
                result.byOption.put(option.key, soft);
            }
        }

        return result;
    }

    private static @Nullable Hit findSoft(Model.Option option, List<Integer> keys, String ownModName)
    {
        try
        {
            for (KeybindCategory category : InputEventHandler.getKeybindManager().getKeybindCategories())
            {
                if (ownModName.equals(category.getModName()))
                {
                    continue;
                }

                for (IHotkey hotkey : category.getHotkeys())
                {
                    IKeybind other = hotkey.getKeybind();

                    if (other == option.keybind || ! keys.equals(other.getKeys()))
                    {
                        continue;
                    }

                    return new Hit(null, Strings.t("conflict.soft", category.getModName(),
                            prettyName(hotkey)), false);
                }
            }
        }
        catch (LinkageError | RuntimeException e)
        {
        }

        if (keys.size() != 1 || keys.getFirst() < 0)
        {
            return null;
        }

        try
        {
            Minecraft mc = Minecraft.getInstance();

            if (mc.options == null)
            {
                return null;
            }

            // Тип «клавиатура» — первый в перечислении и в 26.2 (KEYSYM), и в 26.3 (KEYBOARD):
            // так одна библиотека собирается под обе версии.
            String key = InputConstants.Type.values()[0].getOrCreate(keys.getFirst()).getName();

            for (KeyMapping mapping : mc.options.keyMappings)
            {
                if (! mapping.isUnbound() && key.equals(mapping.saveString()))
                {
                    return new Hit(null, Strings.t("conflict.soft", Strings.t("vanilla"),
                            Strings.plain(mapping.getName())), false);
                }
            }
        }
        catch (LinkageError | RuntimeException e)
        {
        }

        return null;
    }

    private static String prettyName(IHotkey hotkey)
    {
        String translated = hotkey.getTranslatedName();

        return translated == null || translated.isEmpty() ? hotkey.getName() : translated;
    }

    private static List<Integer> keysOf(Model.Option option)
    {
        if (option.keybind == null)
        {
            return List.of();
        }

        List<Integer> keys = option.keybind.getKeys();

        return keys == null ? List.of() : new ArrayList<>(keys);
    }
}
