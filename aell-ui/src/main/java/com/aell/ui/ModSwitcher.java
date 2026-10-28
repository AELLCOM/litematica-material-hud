package com.aell.ui;

import fi.dy.masa.malilib.MaLiLibConfigs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetDropDownList;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.data.ModInfo;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class ModSwitcher
{
    public static final int WIDTH = 130;
    public static final int HEIGHT = 18;
    public static final int TOP = 6;
    public static final int RIGHT_MARGIN = 25;
    public static final int MAX_LIST_HEIGHT = 200;
    public static final int MAX_VISIBLE = 10;

    private final String modId;

    private @Nullable WidgetDropDownList<ModInfo> widget;
    private boolean available;

    public ModSwitcher(String modId)
    {
        this.modId = modId;
    }

    public static void register(String modId, String modName, Supplier<GuiBase> factory)
    {
        try
        {
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(modId, modName, factory));
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }

    public void layout(int screenWidth, int panelRight)
    {
        this.available = false;
        this.widget = null;

        if (! enabled())
        {
            return;
        }

        List<ModInfo> mods;

        try
        {
            mods = Registry.CONFIG_SCREEN.getAllModsWithConfigScreens();
        }
        catch (RuntimeException | LinkageError ignored)
        {
            return;
        }

        if (mods == null || mods.size() < 2)
        {
            return;
        }

        int x = Math.max(panelRight + Ui.PAD_IN, screenWidth - WIDTH - RIGHT_MARGIN);

        if (x + WIDTH > screenWidth - Ui.PAD_IN)
        {
            return;
        }

        this.widget = new Switcher(x, TOP, mods, this.selected(mods));
        this.available = true;
    }

    public boolean isVisible()
    {
        return this.available && this.widget != null;
    }

    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.isVisible())
        {
            this.widget.render(ctx, mouseX, mouseY, false);
        }
    }

    public boolean click(MouseButtonEvent click, boolean doubleClick)
    {
        boolean handled = this.isVisible()
                && this.widget.isMouseOver((int) click.x(), (int) click.y())
                && this.widget.onMouseClicked(click, doubleClick);

        if (handled)
        {
            // Открытие списка и выбор пункта
            Vanilla.click();
        }

        return handled;
    }

    public boolean scroll(double mouseX, double mouseY, double horizontal, double vertical)
    {
        return this.isVisible() && this.widget.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    public boolean key(KeyEvent input)
    {
        return this.isVisible() && this.widget.onKeyTyped(input);
    }

    private @Nullable ModInfo selected(List<ModInfo> mods)
    {
        for (ModInfo mod : mods)
        {
            if (this.modId.equals(mod.modId()))
            {
                return mod;
            }
        }

        return null;
    }

    private static boolean enabled()
    {
        try
        {
            return MaLiLibConfigs.Generic.ENABLE_CONFIG_SWITCHER.getBooleanValue();
        }
        catch (RuntimeException | LinkageError ignored)
        {
            return true;
        }
    }

    private static final class Switcher extends WidgetDropDownList<ModInfo>
    {
        private Switcher(int x, int y, List<ModInfo> mods, @Nullable ModInfo current)
        {
            super(x, y, WIDTH, HEIGHT, MAX_LIST_HEIGHT, MAX_VISIBLE, mods);

            if (current != null)
            {
                this.setSelectedEntry(current);
            }
        }

        @Override
        protected void setSelectedEntry(int index)
        {
            super.setSelectedEntry(index);

            ModInfo entry = this.getSelectedEntry();

            if (entry == null || entry.configScreenSupplier() == null)
            {
                return;
            }

            Screen screen = entry.configScreenSupplier().get();

            if (screen != null)
            {
                Nav.clear();
                GuiBase.openGui(screen);
            }
        }

        @Override
        protected String getDisplayString(ModInfo entry)
        {
            return entry.modName();
        }
    }
}
