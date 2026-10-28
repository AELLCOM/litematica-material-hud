package com.aell.layerlist.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

/** Режим {@code m}: снимок экрана настроек Material HUD (шапка, вкладки) — для проверки вида глазами. */
@Environment(EnvType.CLIENT)
public final class SettingsShot implements CovJob
{
    private int stage;
    private long nextAt;

    @Override
    public String name()
    {
        return "M (снимок настроек)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        this.nextAt = Util.getMillis() + 2000L;
        return mc.player != null;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        this.nextAt = now + 2000L;

        switch (this.stage++)
        {
            case 0 ->
            {
                var screen = com.aell.layerlist.config.GuiConfigs.create(null, null);
                mc.gui.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                search(screen, System.getenv("LL_SETTINGS_SEARCH"));
            }
            case 1 ->
            {
                CovOut.log("[settingsshot] снимок настроек");
                Screenshot.grab(mc, false);
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log("[settingsshot] итог: OK");
                return true;
            }
        }

        return false;
    }

    /** {@code LL_SETTINGS_SEARCH}: строка поиска настроек — снять нужные опции, не листая вкладки. */
    private static void search(Object screen, String query)
    {
        if (query == null || query.isBlank())
        {
            return;
        }

        try
        {
            Class<?> type = com.aell.layerlist.shaded.ui.SettingsScreen.class;
            var field = type.getDeclaredField("search");
            field.setAccessible(true);
            field.set(screen, query);
            var layout = type.getDeclaredMethod("layout");
            layout.setAccessible(true);
            layout.invoke(screen);
            CovOut.log("[settingsshot] поиск: " + query);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[settingsshot] поиск не задан: " + e);
        }
    }

    @Override
    public void close() {}
}
