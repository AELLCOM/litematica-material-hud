package com.aell.layerlist;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.gui.GuiHudPosition;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import java.io.File;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

@Environment(EnvType.CLIENT)
public final class DevScreenshots
{
    private static final int START_TICKS = 60;
    private static final int STEP_TICKS = 10;

    private static int ticks;
    private static int step;

    private DevScreenshots() {}

    public static void register()
    {
        if (! enabled())
        {
            return;
        }

        ClientTickEvents.END_CLIENT_TICK.register(DevScreenshots::tick);
    }

    private static boolean enabled()
    {
        if ("1".equals(System.getenv("LL_SHOTS")))
        {
            return true;
        }

        return new File(FabricLoader.getInstance().getGameDir().toFile(), "ll_shots").exists();
    }

    private static void tick(Minecraft mc)
    {
        ++ticks;

        if (mc.level == null || mc.player == null || ticks < START_TICKS
                || (ticks - START_TICKS) % STEP_TICKS != 0)
        {
            return;
        }

        run(mc, step);
        ++step;
    }

    private static void run(Minecraft mc, int index)
    {
        switch (index)
        {
            case 0 -> mc.setScreen(GuiConfigs.create(null, null).showTab(0));
            case 1 -> shot(mc, "1-hud");
            case 2 -> mc.setScreen(GuiConfigs.create(null, null).showTab(2));
            case 3 -> shot(mc, "2-highlight");
            case 4 -> mc.setScreen(GuiConfigs.create(null, null).showTab(3));
            case 5 -> shot(mc, "3-hotkeys");
            case 6 ->
            {
                if (fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen()
                        instanceof fi.dy.masa.malilib.gui.GuiBase gui)
                {
                    for (int i = 0; i < 6; ++i)
                    {
                        gui.onMouseScrolled(200.0D, 150.0D, 0.0D, -1.0D);
                    }
                }
            }
            case 7 -> shot(mc, "3b-hotkeys-scrolled");
            case 8 -> GuiHudPosition.openFromKeybind();
            case 9 -> shot(mc, "4-hud-position");
            case 10 ->
            {
                fi.dy.masa.litematica.data.DataManager.setConfigGuiTab(
                        fi.dy.masa.litematica.gui.GuiConfigs.ConfigGuiTab.GENERIC);
                mc.setScreen(new fi.dy.masa.litematica.gui.GuiConfigs());
            }
            case 11 -> shot(mc, "5-litematica-tabs");
            case 12 -> mc.setScreen(new fi.dy.masa.litematica.gui.GuiRenderLayer());
            case 13 -> shot(mc, "6-litematica-render-layers");
            // Экран «Схемы» (0.0.26): лишний шаг ожидания — миниатюры и превью читаются в фоне
            case 14 -> GuiSchematics.open(null);
            case 15 ->
            {
            }
            case 16 -> shot(mc, "7-schematics");
            default -> mc.stop();
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
        System.out.println("[layerlist] screenshot: " + name);
    }
}
