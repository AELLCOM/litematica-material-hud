package com.aell.layerlist.easyplace;

import com.aell.layerlist.util.Chat;
import fi.dy.masa.malilib.config.IConfigBoolean;
import java.lang.reflect.Field;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Вытеснение оригинального EasyPlaceFix: пока наш модуль Easy Place включён, его главный
 * тумблер {@code enableFix} принудительно держится выключенным — Litematica работает своим
 * штатным потоком, а все исправления делает наш фильтр на клике. Когда наш модуль выключают
 * тумблером (или он выключен изначально), чужой тумблер возвращается в то положение, в котором
 * мы его застали. Доступ рефлексией и только к публичному полю конфига; если поле в будущей
 * версии мода исчезнет, вытеснение тихо не работает — тогда срабатывает страховка
 * {@code PlacementGuard#decideForeign} + {@code DeferredPlace}.
 */
@Environment(EnvType.CLIENT)
public final class ForeignSuppressor
{
    private static final String MOD_ID = "easyplacefix";
    private static final int CHECK_EVERY_TICKS = 20;

    private static final IConfigBoolean FOREIGN_TOGGLE = resolve();

    private static int ticks;
    private static boolean suppressed;

    private ForeignSuppressor() {}

    private static IConfigBoolean resolve()
    {
        if (! FabricLoader.getInstance().isModLoaded(MOD_ID))
        {
            return null;
        }

        try
        {
            Field field = Class.forName("org.uiop.easyplacefix.config.easyPlacefixConfig")
                    .getField("ENABLE_FIX");

            return field.get(null) instanceof IConfigBoolean toggle ? toggle : null;
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            return null;
        }
    }

    public static void onClientTick(Minecraft mc)
    {
        if (FOREIGN_TOGGLE == null || mc.level == null || ++ticks < CHECK_EVERY_TICKS)
        {
            return;
        }

        ticks = 0;

        if (EasyPlaceModule.isActive())
        {
            if (FOREIGN_TOGGLE.getBooleanValue())
            {
                FOREIGN_TOGGLE.setBooleanValue(false);
                suppressed = true;
                Chat.send(Chat.tr("layerlist.message.ep_foreign_suppressed", Chat.TEXT));
            }
        }
        else if (suppressed)
        {
            suppressed = false;

            if (! FOREIGN_TOGGLE.getBooleanValue())
            {
                FOREIGN_TOGGLE.setBooleanValue(true);
                Chat.send(Chat.tr("layerlist.message.ep_foreign_restored", Chat.TEXT));
            }
        }
    }
}
