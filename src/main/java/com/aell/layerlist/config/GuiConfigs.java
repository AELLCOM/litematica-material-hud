package com.aell.layerlist.config;

import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.SettingsScreen;
import com.aell.layerlist.ui.kit.TextFocus;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class GuiConfigs extends SettingsScreen
{
    private GuiConfigs()
    {
        super(LayerListSettings.getInstance());
    }

    public static GuiConfigs create(@Nullable Screen parent, @Nullable String backLabelKey)
    {
        GuiConfigs screen = new GuiConfigs();
        screen.setParent(parent);
        screen.backLabel(backLabelKey);

        return screen;
    }

    public static void open(@Nullable Screen parent, @Nullable String backLabelKey)
    {
        Nav.open(create(parent, backLabelKey), parent);
    }

    /**
     * Поиск и ввод числа в общем экране настроек (aell-ui) ловят буквы в {@code onCharTyped}, но ввод
     * текста SDL 26.3 сами не включают: библиотека общая с модами под версии без SDL. Поэтому, пока
     * экран открыт, ввод текста держит он целиком — иначе буквы до поиска не доходят.
     */
    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);
        TextFocus.take(this);
    }

    @Override
    public void removed()
    {
        TextFocus.release(this);
        super.removed();
    }
}
