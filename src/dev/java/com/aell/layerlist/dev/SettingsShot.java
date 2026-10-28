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
                mc.gui.setScreen(com.aell.layerlist.config.GuiConfigs.create(null, null));
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
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

    @Override
    public void close() {}
}
