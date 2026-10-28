package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.hud.TransferButton;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code y}: только кнопка «Перенести всё необходимое», на чужом сетапе (моды и конфиги игрока в
 * {@code run/}). Настройки мода не трогает, кроме включения HUD на время прогона. Схема из камня и досок,
 * сундук с камнем, досками и землёй, выживание, щелчок по кнопке настоящим путём мыши. В лог — всё, что
 * нужно для разбора: экран, место кнопки, масштаб GUI, путь, которым рисовалась панель.
 */
@Environment(EnvType.CLIENT)
public final class TransferClickTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(600, 100, 0);
    private static final BlockPos TARGET = new BlockPos(600, 100, 14);
    private static final BlockPos CHEST = TARGET.offset(3, 0, 6);

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "Y (кнопка переноса на сетапе игрока)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        CovOut.log("[click] настройки игрока: hudBesideContainer=" + LayerListConfigs.HUD_BESIDE_CONTAINER.getBooleanValue()
                + " hudTransferButton=" + LayerListConfigs.HUD_TRANSFER_BUTTON.getBooleanValue()
                + " hudScale=" + LayerListConfigs.HUD_SCALE.getDoubleValue()
                + " renderMaterialsInGui=" + fi.dy.masa.litematica.config.Configs.Generic.RENDER_MATERIALS_IN_GUI.getBooleanValue()
                + " guiScale=" + mc.options.guiScale().get());

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 590 -10 615 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 8, -1, 9, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 5, 9, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 0, 2, 0, 0, "minecraft:stone"));
            command(server, fill(REF, 0, 0, 1, 1, 0, 1, "minecraft:oak_planks"));
            command(server, "tp @a " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        long wait = 3000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-click");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 0, 1), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-click", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                                    + "{Slot:0b,id:\"minecraft:stone\",count:64},{Slot:1b,id:\"minecraft:oak_planks\",count:3},"
                                    + "{Slot:2b,id:\"minecraft:dirt\",count:5}]}",
                            CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                    command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                });
                wait = 4000L;
            }
            case 1 ->
            {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
                wait = 2500L;
            }
            case 2 ->
            {
                Screen screen = GuiUtils.getCurrentScreen();
                int[] bounds = TransferButton.bounds();
                var window = mc.getWindow();
                CovOut.log("[click] экран " + (screen == null ? "нет" : screen.getClass().getName()) + " "
                        + (screen == null ? "" : screen.width + "×" + screen.height) + ", окно " + window.getScreenWidth() + "×"
                        + window.getScreenHeight() + ", GUI " + window.getGuiScaledWidth() + "×" + window.getGuiScaledHeight());
                CovOut.log("[click] кнопка " + java.util.Arrays.toString(bounds) + ", нужна " + TransferButton.wanted(screen)
                        + ", поза при рисовании " + TransferButton.lastPose());
                int[] draws = LayerHudRenderer.takeDrawStats();
                CovOut.log("[click] кадров панели всего " + draws[0] + ", из экрана " + draws[1]);

                if (screen instanceof AbstractContainerScreen<?> container)
                {
                    IMixinAbstractContainerScreen access = (IMixinAbstractContainerScreen) container;
                    CovOut.log("[click] окно контейнера x " + access.layerlist_getLeftPos() + "…"
                            + (access.layerlist_getLeftPos() + access.layerlist_getImageWidth()) + ", y " + access.layerlist_getTopPos());
                }

                this.check("сундук открыт", screen instanceof AbstractContainerScreen<?>, String.valueOf(screen));
                this.check("кнопка нарисована", bounds[2] > 0, java.util.Arrays.toString(bounds));
                shot(mc, "до щелчка");

                if (bounds[2] > 0)
                {
                    sdlClick(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + bounds[3] / 2.0D);
                }

                wait = 2500L;
            }
            case 3 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int planks = count(inventory, Items.OAK_PLANKS);
                int dirt = count(inventory, Items.DIRT);
                // Схема — 3 камня и 2 доски: из стопки 64 камня берётся 3, из 3 досок — 2, земли в схеме нет
                this.check("щелчок перенёс ровно нехватку, землю не тронул", stone == 3 && planks == 2 && dirt == 0,
                        "камень " + stone + ", доски " + planks + ", земля " + dirt);
                if (mc.player.containerMenu != null)
                {
                    int chestStone = mc.player.containerMenu.getSlot(0).getItem().getCount();
                    int chestPlanks = mc.player.containerMenu.getSlot(1).getItem().getCount();
                    this.check("остаток остался в сундуке на своих местах", chestStone == 61 && chestPlanks == 1
                                    && mc.player.containerMenu.getCarried().isEmpty(),
                            "камень " + chestStone + ", доски " + chestPlanks + ", на курсоре " + mc.player.containerMenu.getCarried());
                }
                shot(mc, "после щелчка");
                mc.player.closeContainer();
                wait = 1000L;
            }
            default ->
            {
                CovOut.log("[click] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /**
     * Щелчок так, как его даёт настоящая мышь: курсор переносится SDL (событие движения), нажатие и отпускание
     * идут в очередь событий SDL — дальше их разбирает игра сама, через SDLEventHandler и MouseHandler.
     */
    private static void sdlClick(Minecraft mc, double guiX, double guiY)
    {
        var window = mc.getWindow();
        long handle = window.handle();

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
        {
            java.nio.IntBuffer w = stack.mallocInt(1);
            java.nio.IntBuffer h = stack.mallocInt(1);
            java.nio.IntBuffer pw = stack.mallocInt(1);
            java.nio.IntBuffer ph = stack.mallocInt(1);
            org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize(handle, w, h);
            org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels(handle, pw, ph);
            double pixelX = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
            double pixelY = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
            float x = (float) (pixelX * w.get(0) / Math.max(1, pw.get(0)));
            float y = (float) (pixelY * h.get(0) / Math.max(1, ph.get(0)));
            CovOut.log("[click] SDL: окно " + w.get(0) + "×" + h.get(0) + " (пикселей " + pw.get(0) + "×" + ph.get(0)
                    + "), курсор в " + x + "×" + y);
            org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow(handle, x, y);
            int windowId = org.lwjgl.sdl.SDLVideo.SDL_GetWindowID(handle);
            // Под Xvfb перенос курсора движения не порождает — событие движения кладём сами
            org.lwjgl.sdl.SDL_Event motion = org.lwjgl.sdl.SDL_Event.calloc(stack);
            motion.motion().type(org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_MOTION).windowID(windowId).x(x).y(y);
            CovOut.log("[click] SDL движение: " + org.lwjgl.sdl.SDLEvents.SDL_PushEvent(motion));

            for (boolean down : new boolean[]{true, false})
            {
                org.lwjgl.sdl.SDL_Event event = org.lwjgl.sdl.SDL_Event.calloc(stack);
                event.button()
                        .type(down ? org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN : org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP)
                        .windowID(windowId)
                        .button((byte) org.lwjgl.sdl.SDLMouse.SDL_BUTTON_LEFT)
                        .down(down)
                        .clicks((byte) 1)
                        .x(x)
                        .y(y);
                CovOut.log("[click] SDL " + (down ? "нажатие" : "отпускание") + ": " + org.lwjgl.sdl.SDLEvents.SDL_PushEvent(event));
            }
        }
    }

    private static int count(Inventory inventory, Item item)
    {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); ++i)
        {
            if (inventory.getItem(i).is(item))
            {
                count += inventory.getItem(i).getCount();
            }
        }

        return count;
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[click] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[click] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[click] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
