package com.aell.ui;

import fi.dy.masa.malilib.gui.GuiBase;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class Nav
{
    private static final List<Screen> STACK = new ArrayList<>();

    private Nav() {}

    public static void open(Screen screen, @Nullable Screen parent)
    {
        if (parent == null)
        {
            STACK.clear();
        }
        else
        {
            int existing = indexOfSameScreen(screen);

            if (existing >= 0)
            {
                while (STACK.size() > existing)
                {
                    STACK.removeLast();
                }
            }
            else if (STACK.size() >= Ui.STACK_MAX)
            {
                STACK.set(STACK.size() - 1, parent);
            }
            else if (STACK.isEmpty() || STACK.getLast() != parent)
            {
                STACK.add(parent);
            }
        }

        GuiBase.openGui(screen);
    }

    public static @Nullable Screen peek()
    {
        return STACK.isEmpty() ? null : STACK.getLast();
    }

    public static void back()
    {
        if (STACK.isEmpty())
        {
            GuiBase.openGui(null);

            return;
        }

        GuiBase.openGui(STACK.removeLast());
    }

    public static void clear()
    {
        STACK.clear();
    }

    private static int indexOfSameScreen(Screen screen)
    {
        for (int i = 0; i < STACK.size(); ++i)
        {
            if (STACK.get(i) == screen || STACK.get(i).getClass() == screen.getClass())
            {
                return i;
            }
        }

        return -1;
    }
}
