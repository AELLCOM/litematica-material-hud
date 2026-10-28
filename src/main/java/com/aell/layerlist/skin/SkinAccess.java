package com.aell.layerlist.skin;

import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.lang.reflect.Field;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Закрытые поля кнопки malilib, которые нужны перекраске: подпись, «включена» и действие (по действию отличаем
 * кнопки Litematica от кнопок других модов). Отражением, а не аксессором миксина: переименуют поле — перекраска
 * выключится с одним WARN, а игра загрузится (точки в {@code ContractCheck}).
 */
@Environment(EnvType.CLIENT)
public final class SkinAccess
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/skin");

    private static @Nullable Field displayString;
    private static @Nullable Field enabled;
    private static @Nullable Field actionListener;
    private static boolean lookedUp;
    private static boolean usable;

    private SkinAccess() {}

    /** Все три поля на месте. Нет — перекраска не включается. */
    public static boolean usable()
    {
        if (! lookedUp)
        {
            lookedUp = true;

            try
            {
                displayString = field("displayString");
                enabled = field("enabled");
                actionListener = field("actionListener");
                usable = true;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                LOGGER.warn("malilib ButtonBase fields are not readable; Litematica's menu keeps its own look", e);
            }
        }

        return usable;
    }

    public static String label(ButtonBase button)
    {
        return get(displayString, button) instanceof String s ? s : "";
    }

    public static boolean enabled(ButtonBase button)
    {
        return ! (get(enabled, button) instanceof Boolean b) || b;
    }

    public static @Nullable Object action(ButtonBase button)
    {
        return get(actionListener, button);
    }

    private static Field field(String name) throws NoSuchFieldException
    {
        Field field = ButtonBase.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static @Nullable Object get(@Nullable Field field, ButtonBase button)
    {
        try
        {
            return field == null ? null : field.get(button);
        }
        catch (IllegalAccessException e)
        {
            return null;
        }
    }
}
