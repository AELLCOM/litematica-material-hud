package com.aell.layerlist.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

/**
 * Режим {@code m}: снимок экрана настроек Material HUD (шапка, вкладки) — для проверки вида глазами.
 * {@code LL_SETTINGS_SEARCH} — строка поиска; {@code LL_SETTINGS_SHOW=<ключ опции>} — прокрутить открытую вкладку к
 * опции; {@code LL_SETTINGS_FLIP=<имя настройки>} — второй снимок с переключённой настройкой (после — вернуть).
 */
@Environment(EnvType.CLIENT)
public final class SettingsShot implements CovJob
{
    private int stage;
    private long nextAt;
    private Object screen;
    private fi.dy.masa.malilib.config.options.ConfigBoolean flipped;

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
                this.screen = screen;
                mc.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                search(screen, System.getenv("LL_SETTINGS_SEARCH"));
            }
            case 1 ->
            {
                // Прокрутка — после первого кадра: экран раскладывает строки в init
                show(this.screen, System.getenv("LL_SETTINGS_SHOW"));
            }
            case 2 ->
            {
                CovOut.log("[settingsshot] снимок настроек");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
            }
            case 3 ->
            {
                String name = System.getenv("LL_SETTINGS_FLIP");

                if (name == null || name.isBlank())
                {
                    mc.setScreen(null);
                    CovOut.log("[settingsshot] итог: OK");
                    return true;
                }

                for (var option : com.aell.layerlist.config.LayerListConfigs.OPTIONS)
                {
                    if (option.getName().equals(name)
                            && option instanceof fi.dy.masa.malilib.config.options.ConfigBoolean bool)
                    {
                        this.flipped = bool;
                        bool.setBooleanValue(! bool.getBooleanValue());
                        CovOut.log("[settingsshot] переключено " + name + " → " + bool.getBooleanValue());
                    }
                }
            }
            case 4 ->
            {
                CovOut.log("[settingsshot] снимок настроек после переключения");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
            }
            default ->
            {
                if (this.flipped != null)
                {
                    this.flipped.setBooleanValue(! this.flipped.getBooleanValue());
                }

                mc.setScreen(null);
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

    /** {@code LL_SETTINGS_SHOW}: прокрутить открытую вкладку так, чтобы опция с этим ключом была на виду. */
    private static void show(Object screen, String key)
    {
        if (key == null || key.isBlank() || screen == null)
        {
            return;
        }

        try
        {
            Class<?> type = com.aell.layerlist.shaded.ui.SettingsScreen.class;
            var rowsField = type.getDeclaredField("rows");
            rowsField.setAccessible(true);

            for (Object row : (java.util.List<?>) rowsField.get(screen))
            {
                var optionField = row.getClass().getDeclaredField("option");
                optionField.setAccessible(true);
                Object option = optionField.get(row);

                if (option instanceof com.aell.layerlist.shaded.ui.Model.Option found && found.key.equals(key))
                {
                    var yField = row.getClass().getDeclaredField("y");
                    yField.setAccessible(true);
                    var maxScroll = type.getDeclaredMethod("maxScroll");
                    maxScroll.setAccessible(true);
                    var scroll = type.getDeclaredField("scroll");
                    scroll.setAccessible(true);
                    int want = Math.max(0, Math.min((int) maxScroll.invoke(screen), yField.getInt(row) - 90));
                    scroll.setInt(screen, want);
                    CovOut.log("[settingsshot] прокрутка к " + key + ": " + want);
                    return;
                }
            }

            CovOut.log("[settingsshot] опции " + key + " на открытой вкладке нет");
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[settingsshot] прокрутка не задана: " + e);
        }
    }

    @Override
    public void close() {}
}
