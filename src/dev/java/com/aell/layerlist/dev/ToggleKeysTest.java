package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.config.LayerListSettings;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Режим {@code toggles}: клавиши-переключатели «Защита от установки мимо схемы» и «Не копать лишнего»
 * (по умолчанию не назначены). Назначаем F8 и F9 тому объекту клавиши, который показывает вкладка «Клавиши»
 * ({@link LayerListSettings#epHotkey}: при Easy Place Fix+ — его), жмём событием SDL, как игрок, и проверяем,
 * что опция переключилась ровно один раз (две живые клавиши на одну опцию вернули бы её обратно) — и обратно
 * вторым нажатием. Назначения и значения потом возвращаются.
 */
@Environment(EnvType.CLIENT)
final class ToggleKeysTest implements CovJob
{

    private record Case(String name, ConfigHotkey key, ConfigBoolean option, int scancode, String keyName) {}

    private List<Case> cases = List.of();
    private int index;
    private int press;
    private long at;
    private boolean before;
    private boolean original;
    private int release;
    private int ok;
    private int fail;

    @Override
    public String name()
    {
        return "TOGGLES (клавиши-переключатели защит Easy Place, Easy Place Fix+ "
                + (FabricLoader.getInstance().isModLoaded("easyplacefixplus") ? "стоит" : "не стоит") + ")";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.player == null)
        {
            return false;
        }

        fi.dy.masa.malilib.gui.GuiBase.openGui(null);
        this.cases = List.of(
                new Case("Защита от установки мимо схемы", LayerListSettings.epHotkey(LayerListConfigs.TOGGLE_STRICT_SCHEMATIC),
                        LayerListSettings.epOption(LayerListConfigs.EP_STRICT_SCHEMATIC), InputConstants.KEY_F8, "F8"),
                new Case("Не копать лишнего", LayerListSettings.epHotkey(LayerListConfigs.TOGGLE_DIG_GUARD),
                        LayerListSettings.epOption(LayerListConfigs.EP_DIG_GUARD), InputConstants.KEY_F9, "F9"));

        // LL_TOGGLES_ORDER=reverse: настоящим нажатием жмётся вторая клавиша — чтобы отличить особенность стенда от клавиши
        if ("reverse".equals(System.getenv("LL_TOGGLES_ORDER")))
        {
            this.cases = List.of(this.cases.get(1), this.cases.get(0));
        }

        // С Easy Place Fix+ своя клавиша Material HUD не должна быть живой второй: вкладка показывает его объект
        if (FabricLoader.getInstance().isModLoaded("easyplacefixplus"))
        {
            check("при Easy Place Fix+ вкладка «Клавиши» правит его клавишу «мимо схемы»",
                    LayerListSettings.epHotkey(LayerListConfigs.TOGGLE_STRICT_SCHEMATIC) != LayerListConfigs.TOGGLE_STRICT_SCHEMATIC);
            check("при Easy Place Fix+ вкладка «Клавиши» правит его клавишу «не копать»",
                    LayerListSettings.epHotkey(LayerListConfigs.TOGGLE_DIG_GUARD) != LayerListConfigs.TOGGLE_DIG_GUARD);
        }

        for (Case c : this.cases)
        {
            CovOut.log("[toggles] " + c.name + ": клавиша " + (c.key == LayerListConfigs.TOGGLE_STRICT_SCHEMATIC
                    || c.key == LayerListConfigs.TOGGLE_DIG_GUARD ? "своя" : "Easy Place Fix+")
                    + ", по умолчанию «" + c.key.getKeybind().getKeysDisplayString() + "»");
            check(c.name + ": по умолчанию не назначена", c.key.getKeybind().getKeys().isEmpty());
            c.key.getKeybind().setValueFromString(c.keyName);
        }

        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();

        this.at = Util.getMillis() + 500L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.at;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.at)
        {
            return false;
        }

        if (this.index >= this.cases.size())
        {
            CovOut.log("[toggles] итог: OK " + this.ok + ", FAIL " + this.fail);
            return true;
        }

        Case c = this.cases.get(this.index);

        if (this.press % 2 == 0)
        {
            // Нажатие: запоминаем значение и жмём
            if (this.press == 0)
            {
                this.original = c.option.getBooleanValue();
            }

            this.before = c.option.getBooleanValue();
            // Отпускание — отдельным кадром: в одном кадре malilib теряет его, клавиша «залипает»,
            // и следующее нажатие выглядит как сочетание двух клавиш
            // 1.21.11 (GLFW): нажатия — тем же вызовом, каким malilib зовёт обработчик клавиши
            // Остальные — тем же вызовом, каким malilib зовёт обработчик клавиши: повторные синтетические события
            // SDL под xvfb до malilib больше не доходят (одно нажатие за запуск — особенность стенда, не мода)
            ((fi.dy.masa.malilib.hotkeys.KeybindMulti) c.key.getKeybind()).getCallback()
                    .onKeyAction(fi.dy.masa.malilib.hotkeys.KeyAction.PRESS, c.key.getKeybind());
            ++this.press;
            this.at = Util.getMillis() + 200L;
            return false;
        }

        // Полсекунды прошло — нажатие обработано (и ровно одним обработчиком: двумя — значение вернулось бы)
        boolean now = c.option.getBooleanValue();
        CovOut.log("[toggles]   зажато по malilib: «" + fi.dy.masa.malilib.hotkeys.KeybindMulti.getActiveKeysString() + "», клавиша нажата "
                + c.key.getKeybind().isPressed() + ", держится " + c.key.getKeybind().isKeybindHeld()
                + ", срабатываний " + fi.dy.masa.malilib.hotkeys.KeybindMulti.getTriggeredCount());
        check(c.name + ": нажатие " + ((this.press + 1) / 2) + " переключило " + this.before + " → " + now, now != this.before);

        if (this.press >= 3)
        {
            check(c.name + ": после двух нажатий значение прежнее (" + this.original + ")", now == this.original);
            c.key.getKeybind().setValueFromString("");
            fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();
            ++this.index;
            this.press = 0;
        }
        else
        {
            ++this.press;
        }

        this.at = Util.getMillis() + 100L;
        return false;
    }

    private void check(String what, boolean pass)
    {
        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.fail;
        }

        CovOut.log("[toggles]   " + (pass ? "OK   " : "FAIL ") + what);
    }



    @Override
    public void close()
    {
    }
}
