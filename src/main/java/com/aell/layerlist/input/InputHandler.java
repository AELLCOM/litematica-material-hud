package com.aell.layerlist.input;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.gui.GuiHudPosition;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsModule;
import com.aell.layerlist.storage.StorageSearch;
import com.aell.layerlist.util.Chat;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class InputHandler implements IKeybindProvider, IHotkeyCallback
{
    private static final InputHandler INSTANCE = new InputHandler();

    public static InputHandler getInstance()
    {
        return INSTANCE;
    }

    public void registerKeyCallbacks()
    {
        for (ConfigHotkey hotkey : LayerListConfigs.HOTKEYS)
        {
            hotkey.getKeybind().setCallback(this);
        }
    }

    @Override
    public void addKeysToMap(IKeybindManager manager)
    {
        for (ConfigHotkey hotkey : LayerListConfigs.HOTKEYS)
        {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager)
    {
        manager.addHotkeysForCategory("layerlist", "Litematica Material HUD", LayerListConfigs.HOTKEYS);
    }

    /** Показать или спрятать панель материалов (клавиша Y): в мире — хоткеем malilib, в инвентаре — {@link #toggleHudInScreen}. */
    public static void toggleHud()
    {
        boolean enabled = LayerCountManager.getInstance().toggleHud();

        if (enabled)
        {
            InfoHud.getInstance().addInfoHudRenderer(LayerHudRenderer.getInstance(), true);
        }

        var message = enabled ? Chat.tr("layerlist.message.hud_on", Chat.OK) : Chat.tr("layerlist.message.hud_off", Chat.DIM);
        Chat.send(message);
        // В открытом инвентаре чат не виден — над хотбаром
        net.minecraft.client.Minecraft.getInstance().gui.setOverlayMessage(message, false);
    }

    /**
     * Клавиша панели материалов в инвентаре и контейнерах: хоткеи malilib в открытом экране не срабатывают,
     * поэтому её ловит экран контейнера — там же, где ванилла проверяет свою клавишу инвентаря, то есть
     * после полей ввода (поиск творческого инвентаря, книги рецептов): буква в поиске печатается, а не
     * прячет панель. Сочетание с модификаторами — модификаторы должны быть зажаты. Истина — клавиша наша.
     */
    public static boolean toggleHudInScreen(int key)
    {
        java.util.List<Integer> keys = LayerListConfigs.TOGGLE_HUD.getKeybind().getKeys();

        if (keys.isEmpty() || keys.get(keys.size() - 1) != key)
        {
            return false;
        }

        for (int i = 0; i < keys.size() - 1; ++i)
        {
            if (! com.mojang.blaze3d.platform.InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow(), keys.get(i)))
            {
                return false;
            }
        }

        toggleHud();
        return true;
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key)
    {
        LayerCountManager manager = LayerCountManager.getInstance();

        if (key == LayerListConfigs.TOGGLE_HUD.getKeybind())
        {
            toggleHud();
            return true;
        }

        if (key == LayerListConfigs.SCROLL_UP.getKeybind() || key == LayerListConfigs.SCROLL_DOWN.getKeybind())
        {
            if (! isScrollAllowedHere())
            {
                return false;
            }

            manager.scrollPage(key == LayerListConfigs.SCROLL_UP.getKeybind() ? -1 : 1);
            return true;
        }

        if (key == LayerListConfigs.TOGGLE_SEARCH_ALL_CHESTS.getKeybind())
        {
            com.aell.layerlist.storage.AllChestsSearch.toggleFromHotkey();
            return true;
        }

        if (key == LayerListConfigs.CLEAR_SEARCH.getKeybind())
        {
            if (! isSearchKeyAllowedHere() || ! StorageSearch.getInstance().isActive())
            {
                return false;
            }

            StorageSearch.getInstance().clear();
            Chat.notice(Chat.tr("layerlist.message.storage_search_cleared", Chat.DIM));
            return true;
        }

        if (key == LayerListConfigs.VERIFY_KEY.getKeybind())
        {
            Chat.notice(LayerVerifier.getInstance().toggle());
            return true;
        }

        if (key == LayerListConfigs.GHOST_INVENTORY_KEY.getKeybind())
        {
            boolean on = ! LayerListConfigs.GHOST_INVENTORY_ONLY.getBooleanValue();

            LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(on);
            boolean creative = on && Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.hasInfiniteMaterials();
            Chat.notice(Chat.tr(on ? (creative ? "layerlist.message.ghost_inventory_on_creative" : "layerlist.message.ghost_inventory_on")
                    : "layerlist.message.ghost_inventory_off", on ? Chat.OK : Chat.DIM));
            return true;
        }

        if (key == LayerListConfigs.TOGGLE_STRICT_SCHEMATIC.getKeybind())
        {
            return toggleEasyPlace(LayerListConfigs.EP_STRICT_SCHEMATIC, "layerlist.message.strict_schematic");
        }

        if (key == LayerListConfigs.TOGGLE_DIG_GUARD.getKeybind())
        {
            return toggleEasyPlace(LayerListConfigs.EP_DIG_GUARD, "layerlist.message.dig_guard");
        }

        if (key == LayerListConfigs.OPEN_CONFIG.getKeybind())
        {
            GuiConfigs.open(null, null);
            return true;
        }

        if (key == LayerListConfigs.OPEN_HUD_POSITION.getKeybind())
        {
            GuiHudPosition.openFromKeybind();
            return true;
        }

        if (key == SchematicsConfig.OPEN_SCHEMATICS.getKeybind())
        {
            SchematicsModule.openFromHotkey();
            return true;
        }

        // «Смотреть на мир» (выделение или редактор размещения): курсор к карточке координат; без карточки клавиша
        // идёт дальше, в игру и другим модам
        if (key == SchematicsConfig.SELECTION_CURSOR.getKeybind())
        {
            return com.aell.layerlist.schematics.gui.SelectionHud.cursor()
                    || com.aell.layerlist.schematics.gui.PlacementHud.cursor();
        }

        if (key == LayerListConfigs.SEARCH_STORAGE.getKeybind())
        {
            if (! isSearchKeyAllowedHere())
            {
                return false;
            }

            List<MaterialListEntry> pageEntries = manager.getPageEntries();

            if (pageEntries.isEmpty())
            {
                Chat.notice(Chat.tr("layerlist.message.storage_search_empty", Chat.WARN));
                return true;
            }

            int searching = StorageSearch.getInstance().togglePage(pageEntries);

            if (searching > 0)
            {
                java.util.List<StorageSearch.Marked> marked = StorageSearch.getInstance().markedItems();
                java.util.List<net.minecraft.network.chat.MutableComponent> names = new java.util.ArrayList<>();

                for (StorageSearch.Marked item : marked)
                {
                    names.add(Chat.text(item.name(), item.color()));
                }

                net.minecraft.network.chat.MutableComponent line = Chat.tr(
                        "layerlist.message.storage_search_on", Chat.TEXT,
                        Chat.text(String.valueOf(searching), Chat.MARK));

                if (! names.isEmpty())
                {
                    line.append(Chat.text(" — ", Chat.DIM))
                            .append(Chat.joined(", ", Chat.DIM, names));
                }

                Chat.notice(line);
            }
            else if (StorageSearch.getInstance().isActive())
            {
                // Armed with nothing to mark here: everything on this page is already collected.
                // The search keeps following pages, so the next page lights up on its own.
                Chat.notice(Chat.tr("layerlist.message.storage_search_armed", Chat.WARN));
            }
            else
            {
                Chat.notice(Chat.tr("layerlist.message.storage_search_off", Chat.DIM));
            }
            return true;
        }

        return false;
    }


    private static boolean isScrollAllowedHere()
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (screen == null)
        {
            return true;
        }

        return isContainerScrollActive(screen) && ! (screen.getFocused() instanceof EditBox);
    }

    /**
     * The search keys use the ANY keybind context so they stay usable with a chest open.
     * Limit that to container screens without a focused text field, so the key does not
     * fire while typing in a sign, an anvil or the creative search box.
     */
    private static boolean isSearchKeyAllowedHere()
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (screen == null)
        {
            return true;
        }

        return screen instanceof AbstractContainerScreen<?> && ! (screen.getFocused() instanceof EditBox);
    }

    public static boolean isContainerScrollActive()
    {
        return isContainerScrollActive(GuiUtils.getCurrentScreen());
    }

    private static boolean isContainerScrollActive(Screen screen)
    {
        return screen instanceof AbstractContainerScreen<?>;
    }

    private static void sendMessage(String text)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null && LayerListConfigs.CHAT_MESSAGES.getBooleanValue())
        {
            mc.player.displayClientMessage(Component.literal(text), false);
        }
    }

    private static boolean toggleEasyPlace(fi.dy.masa.malilib.config.options.ConfigBoolean own, String message)
    {
        fi.dy.masa.malilib.config.options.ConfigBoolean option = com.aell.layerlist.config.LayerListSettings.epOption(own);
        boolean on = ! option.getBooleanValue();

        option.setBooleanValue(on);
        com.aell.layerlist.config.LayerListSettings.saveEasyPlace();
        Chat.notice(Chat.tr(message + (on ? "_on" : "_off"), on ? Chat.OK : Chat.DIM));
        return true;
    }
}
