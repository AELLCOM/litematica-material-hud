package com.aell.layerlist.dev;

import com.aell.layerlist.api.LayerListApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Режим {@code zftab}: вкладка «Zone Focus» в настройках Material HUD открывается и рисуется с тем Zone Focus, что
 * лежит в {@code run/mods} (перед выкладкой 10.10.2026 — с публичным 0.0.7: новый Material HUD не должен ломать
 * старый Zone Focus). Снимок — для глаза.
 */
@Environment(EnvType.CLIENT)
public final class ZfTabShot implements CovJob
{
    private int stage;
    private long nextAt;
    private boolean failed;

    @Override
    public String name()
    {
        return "ZFTAB (вкладка Zone Focus с установленной версией)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        CovOut.log("[zftab] Zone Focus " + FabricLoader.getInstance().getModContainer("zonefocus")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("нет"));
        this.nextAt = Util.getMillis() + 1500L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.nextAt)
        {
            return false;
        }

        try
        {
            switch (this.stage++)
            {
                case 0 ->
                {
                    Screen screen = LayerListApi.createSettings(null, null, LayerListApi.TAB_ZONE_FOCUS);
                    this.check("экран настроек на вкладке Zone Focus создан", screen != null);
                    mc.setScreen(screen);
                    this.nextAt = Util.getMillis() + 2500L;
                }
                case 1 ->
                {
                    this.check("экран открыт и рисуется", mc.screen != null);
                    CovOut.log("[zftab] снимок вкладки");
                    Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                    this.nextAt = Util.getMillis() + 1000L;
                }
                default ->
                {
                    mc.setScreen(null);
                    CovOut.log("[zftab] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            this.check("сбой: " + e, false);
            CovOut.log("[zftab] итог: FAIL");
            return true;
        }

        return false;
    }

    private void check(String what, boolean ok)
    {
        CovOut.log("[zftab] " + (ok ? "OK   " : "FAIL ") + what);
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
