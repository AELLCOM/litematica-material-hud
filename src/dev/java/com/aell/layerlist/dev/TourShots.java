package com.aell.layerlist.dev;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.hud.HudDock;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.RecipeBookType;

/**
 * Тур I — снимки экранов настроек и стыковки панели к контейнеру (0.0.24): золотая лупа в поиске,
 * ширина панели под ряд вкладок, полноэкранный режим, Esc при вводе клавиши, панель рядом с окном
 * сундука при масштабе GUI 2 (в своём масштабе) и 3 (ужатая), рядом с инвентарём выживания с
 * открытой книгой рецептов, откат на обычное место при масштабе панели 3.0. Кладёт
 * клик по полю поиска (подсказка исчезает, курсор) и набор «hud»; «Портал.litematic» кладёт рядом с игроком, чтобы у панели были данные; ход — по времени (экранам нужно
 * пару кадров на раскладку и отрисовку), шаги пишутся в лог {@code [tour] …}, снимки — в
 * {@code run/screenshots} в порядке шагов. Окно 1600×900 ({@code overrideWidth/Height} в
 * {@code run/options.txt}): масштаб GUI 4 игра не даёт (меньше 240 строк), поэтому проверяются 2 и 3.
 */
@Environment(EnvType.CLIENT)
public final class TourShots implements CovJob
{
    private static final long PAUSE_MS = 900L;
    private static final long DATA_TIMEOUT_MS = 40_000L;

    private int stage;
    private long nextAt;
    private long dataDeadline;
    private boolean failed;

    @Override
    public String name()
    {
        return "I (тур экранов)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.player == null)
        {
            return false;
        }

        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");

        if (! Files.isRegularFile(path))
        {
            CovOut.log("[tour] схемы нет: " + path);
            return false;
        }

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("[tour] схема не загрузилась: " + path);
            return false;
        }

        BlockPos origin = mc.player.blockPosition().offset(4, 0, 4);
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, "tour", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.HUD_BESIDE_CONTAINER.setBooleanValue(true);
        LayerListConfigs.SETTINGS_FULLSCREEN.setBooleanValue(false);
        LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(false);

        this.dataDeadline = Util.getMillis() + DATA_TIMEOUT_MS;
        CovOut.log("[tour] схема положена в " + origin + ", жду данных панели");
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null)
        {
            return false;
        }

        if (this.stage == 0)
        {
            if (! LayerCountManager.getInstance().hasData() && now < this.dataDeadline)
            {
                this.nextAt = now + 500L;
                return false;
            }

            CovOut.log("[tour] данные панели: " + LayerCountManager.getInstance().hasData());
        }

        this.nextAt = now + PAUSE_MS;

        switch (this.stage)
        {
            case 0 -> this.open(mc, GuiConfigs.create(null, null).showTab(0));
            case 1 -> this.shot(mc, "settings-hud");
            case 2 -> this.open(mc, GuiConfigs.create(null, null).showTab("layerlist.gui.tab_hotkeys"));
            case 3 ->
            {
                // Tab — фокус на первую строку (toggleLayerHud), Enter — начать ввод клавиши
                this.key(InputConstants.KEY_TAB);
                this.key(InputConstants.KEY_RETURN);
            }
            case 4 ->
            {
                this.check("ввод начат, клавиша снята на время ввода",
                        LayerListConfigs.TOGGLE_HUD.getKeybind().getKeys().isEmpty());
                this.shot(mc, "keybind-capture");
            }
            case 5 -> this.key(InputConstants.KEY_ESCAPE);
            case 6 ->
            {
                this.check("Esc оставил клавишу не заданной",
                        LayerListConfigs.TOGGLE_HUD.getKeybind().getKeys().isEmpty());
                this.check("Esc при вводе не закрыл экран", GuiUtils.getCurrentScreen() instanceof GuiConfigs);
                this.shot(mc, "keybind-esc-unbound");
            }
            case 7 ->
            {
                LayerListConfigs.TOGGLE_HUD.resetToDefault();
                this.check("сброс вернул значение по умолчанию",
                        ! LayerListConfigs.TOGGLE_HUD.getKeybind().getKeys().isEmpty());
                LayerListConfigs.SETTINGS_FULLSCREEN.setBooleanValue(true);
                this.open(mc, GuiConfigs.create(null, null).showTab(0));
            }
            case 8 -> this.shot(mc, "settings-fullscreen");
            case 9 ->
            {
                // Поле поиска прижато к правому краю шапки; на весь экран его правый край = ширина - 8
                this.click(mc.getWindow().getGuiScaledWidth() - 18, 13);
            }
            case 10 -> this.shot(mc, "search-focus");
            case 11 -> this.chars("hud");
            case 12 ->
            {
                this.shot(mc, "search-typed");
                LayerListConfigs.SETTINGS_FULLSCREEN.setBooleanValue(false);
            }
            case 13 -> this.scale(mc, 2);
            case 14 -> this.chest(mc);
            case 15 -> this.dock(mc, "chest-scale2", true, false, false, true);
            case 16 ->
            {
                // Инвентарь выживания: в креативе InventoryScreen сам подменяется творческим, где книги нет
                this.gameMode(mc, "survival");
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true);
            }
            case 17 -> this.open(mc, new InventoryScreen(mc.player));
            case 18 ->
            {
                this.check("открыт инвентарь выживания", GuiUtils.getCurrentScreen() instanceof InventoryScreen);
                // Книга сдвигает окно вправо: справа остаётся 229 px на панель в 230 — панель уходит налево
                this.dock(mc, "inventory-book-scale2", true, null, true, false);
            }
            case 19 -> this.scale(mc, 3);
            case 20 -> this.chest(mc);
            case 21 -> this.dock(mc, "chest-scale3-shrunk", true, true, false, null);
            case 22 ->
            {
                LayerListConfigs.HUD_SCALE.setDoubleValue(3.0D);
                this.chest(mc);
            }
            case 23 ->
            {
                this.dock(mc, "chest-scale3-hud3-fallback", false, null, false, null);
                LayerListConfigs.HUD_SCALE.resetToDefault();
            }
            case 24 ->
            {
                // «Справа от окна» выключено — как до 1.0.1: слева, книга рецептов учитывается
                LayerListConfigs.HUD_DOCK_RIGHT.setBooleanValue(false);
                this.scale(mc, 2);
            }
            case 25 -> this.open(mc, new InventoryScreen(mc.player));
            case 26 ->
            {
                this.dock(mc, "inventory-book-scale2-left", true, null, true, false);
                LayerListConfigs.HUD_DOCK_RIGHT.resetToDefault();
            }
            case 27 ->
            {
                mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false);
                this.gameMode(mc, "creative");
                this.scale(mc, 0);
                mc.gui.setScreen(null);
                CovOut.log("[tour] итог: " + (this.failed ? "есть провалы" : "все проверки прошли"));
                return true;
            }
            default -> { }
        }

        ++this.stage;
        return false;
    }

    @Override
    public void close() {}

    private void open(Minecraft mc, Screen screen)
    {
        mc.gui.setScreen(screen);
    }

    private void key(int key)
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            gui.onKeyTyped(new KeyEvent(key, 0, 0));
        }
    }

    private void click(int x, int y)
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            gui.onMouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
        }
    }

    private void chars(String text)
    {
        if (GuiUtils.getCurrentScreen() instanceof GuiBase gui)
        {
            for (int i = 0; i < text.length(); ++i)
            {
                gui.onCharTyped(new CharacterEvent(text.charAt(i)));
            }
        }
    }

    private void scale(Minecraft mc, int value)
    {
        mc.options.guiScale().set(value);
        mc.resizeGui();
        CovOut.log("[tour] масштаб GUI " + value + " → экран "
                + mc.getWindow().getGuiScaledWidth() + "×" + mc.getWindow().getGuiScaledHeight());
    }

    private void chest(Minecraft mc)
    {
        Inventory inventory = mc.player.getInventory();
        this.open(mc, new ContainerScreen(ChestMenu.sixRows(0, inventory), inventory,
                Component.literal("Большой сундук")));
    }

    /**
     * Снимок плюс расчёт стыковки: где окно, где панель, с какой стороны и в каком масштабе.
     * {@code expectShrunk == null} — масштаб не проверяется; {@code bookAware} — панель обязана
     * кончаться левее открытой книги рецептов (левый край книги считается как в игре).
     */
    private void dock(Minecraft mc, String name, boolean expectDocked, Boolean expectShrunk, boolean bookAware,
            Boolean expectRight)
    {
        Screen screen = GuiUtils.getCurrentScreen();
        HudDock.Dock dock = LayerHudRenderer.dockBesideContainer(screen);
        double scale = dock == null ? LayerHudRenderer.getScale() : dock.scale();
        int[] size = LayerHudRenderer.getPanelScreenSize(scale);
        String where = "нет (панель на обычном месте)";

        if (screen instanceof AbstractContainerScreen<?> container && dock != null)
        {
            IMixinAbstractContainerScreen access = (IMixinAbstractContainerScreen) container;
            int left = access.layerlist_getLeftPos();
            int right = left + access.layerlist_getImageWidth();
            where = (dock.x() + size[0] <= left ? "слева" : dock.x() >= right ? "справа" : "ПОВЕРХ ОКНА")
                    + " x=" + dock.x() + " y=" + dock.y() + " масштаб " + dock.scale()
                    + " (окно x " + left + "…" + right + ", y от " + access.layerlist_getTopPos() + ")";
            this.check(name + ": панель не перекрывает окно", dock.x() + size[0] <= left || dock.x() >= right);
            this.check(name + ": панель в экране",
                    dock.x() >= 0 && dock.y() >= 0 && dock.x() + size[0] <= screen.width
                            && dock.y() + size[1] <= screen.height);

            if (expectRight != null)
            {
                this.check(name + ": сторона — " + (expectRight ? "справа" : "слева"),
                        expectRight ? dock.x() >= right : dock.x() + size[0] <= left);
            }

            // Книга рецептов открыта слева от окна — мешает только панели слева
            if (bookAware && dock.x() + size[0] <= left)
            {
                int bookLeft = (screen.width - 147) / 2 - 86;
                this.check(name + ": панель левее открытой книги рецептов (край " + bookLeft + ")",
                        dock.x() + size[0] <= bookLeft);
            }
        }

        CovOut.log("[tour] " + name + ": экран " + (screen == null ? "нет" : screen.width + "×" + screen.height)
                + ", панель " + size[0] + "×" + size[1] + ", стыковка: " + where);
        this.check(name + ": стыковка " + (expectDocked ? "есть" : "нет"), (dock != null) == expectDocked);

        if (expectShrunk != null && dock != null)
        {
            this.check(name + ": масштаб " + (expectShrunk ? "ужат" : "свой"),
                    expectShrunk == (dock.scale() < LayerHudRenderer.getScale()));
        }

        this.shot(mc, name);
    }

    private void gameMode(Minecraft mc, String mode)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            this.check("есть встроенный сервер для смены режима", false);
            return;
        }

        server.execute(() ->
        {
            try
            {
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withSuppressedOutput(), "gamemode " + mode + " @a");
            }
            catch (RuntimeException e)
            {
                CovOut.log("[tour] команда gamemode не прошла: " + e);
            }
        });
    }

    private void shot(Minecraft mc, String name)
    {
        CovOut.log("[tour] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok)
    {
        CovOut.log("[tour] " + (ok ? "OK   " : "FAIL ") + what);

        if (! ok)
        {
            this.failed = true;
        }
    }
}
