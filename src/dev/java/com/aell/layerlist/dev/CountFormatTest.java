package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.LayerHudRenderer;
import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Режим {@code countformat}: опция «Счёт в шалкерах» ({@code hudCountInBoxes}, просьба игрока 14.10.2026 — «вместо
 * 1,2 SB показать стопки, как во второй строке»). Включена — от целого шалкера «N (1,2 SB)», выключена — стопки
 * «N (32x64+17)»; меньше шалкера — стопки при любой. Опция в списке настроек, подписи есть на ru и en.
 */
@Environment(EnvType.CLIENT)
public final class CountFormatTest implements CovJob
{
    private boolean failed;

    @Override
    public String name()
    {
        return "countformat (счёт в шалкерах или стопками)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        boolean was = LayerListConfigs.HUD_COUNT_IN_BOXES.getBooleanValue();

        try
        {
            Method format = LayerHudRenderer.class.getDeclaredMethod("formatCount", int.class, int.class);
            format.setAccessible(true);

            LayerListConfigs.HUD_COUNT_IN_BOXES.setBooleanValue(true);
            String boxes = (String) format.invoke(null, 2065, 64);
            String small = (String) format.invoke(null, 1526, 64);
            LayerListConfigs.HUD_COUNT_IN_BOXES.setBooleanValue(false);
            String stacks = (String) format.invoke(null, 2065, 64);
            String exact = (String) format.invoke(null, 640, 64);
            String pearls = (String) format.invoke(null, 500, 16);

            this.check("включена: от шалкера — в шалкерах", boxes.matches("2065 \\(1[.,]2 SB\\)"), boxes);
            this.check("включена: меньше шалкера — стопки", small.equals("1526 (23x64+54)"), small);
            this.check("выключена: и большое число — стопки", stacks.equals("2065 (32x64+17)"), stacks);
            this.check("выключена: ровно стопки без остатка", exact.equals("640 (10x64)"), exact);
            this.check("выключена: стопка по 16", pearls.equals("500 (31x16+4)"), pearls);
        }
        catch (ReflectiveOperationException e)
        {
            this.check("formatCount доступен", false, e.toString());
        }
        finally
        {
            LayerListConfigs.HUD_COUNT_IN_BOXES.setBooleanValue(was);
        }

        this.check("опция в списке настроек", LayerListConfigs.OPTIONS.contains(LayerListConfigs.HUD_COUNT_IN_BOXES), "");
        this.check("по умолчанию выключена — стопки (просьба 14.10.2026)", ! LayerListConfigs.HUD_COUNT_IN_BOXES.getDefaultBooleanValue(), "");

        for (String lang : new String[]{"ru_ru", "en_us"})
        {
            try (java.io.InputStream in = CountFormatTest.class.getResourceAsStream("/assets/layerlist/lang/" + lang + ".json"))
            {
                String text = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                this.check("подписи " + lang, text.contains("\"layerlist.config.hudCountInBoxes.name\"")
                        && text.contains("\"layerlist.config.hudCountInBoxes.tooltip\""), "");
            }
            catch (java.io.IOException e)
            {
                this.check("подписи " + lang, false, e.toString());
            }
        }

        CovOut.log("[count] итог: " + (this.failed ? "FAIL" : "OK"));
        return true;
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[count] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
