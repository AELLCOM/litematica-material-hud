package com.aell.ui;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public interface SettingsModel
{
    String modName();

    String modId();

    String maliModName();

    List<Model.Tab> tabs();

    default List<Model.HeaderButton> headerButtons()
    {
        return List.of();
    }

    default @Nullable Identifier icon()
    {
        return null;
    }

    default @Nullable Model.Status status()
    {
        return null;
    }

    /**
     * Полноэкранный режим экрана настроек: панель на всё окно. {@code null} — у мода такой опции
     * нет, и панель растёт только под ряд вкладок, не шире двух третей окна.
     */
    default @Nullable Values.Bool fullscreen()
    {
        return null;
    }

    void save();

    void resetAll();

    default String nameKey(String optionKey)
    {
        return this.modId() + ".config." + optionKey + ".name";
    }

    default String tooltipKey(String optionKey)
    {
        return this.modId() + ".config." + optionKey + ".tooltip";
    }
}
