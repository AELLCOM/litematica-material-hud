package com.aell.layerlist.dev;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code escguard}: защита от паузы — только после автовыхода (его проверяет {@code transferclose}); обычный
 * выход из сундука по Esc — как в игре: тут же Esc ещё раз — меню паузы открывается. И свой инвентарь — тоже.
 * Настоящим путём клавиатуры игры.
 */
@Environment(EnvType.CLIENT)
public final class EscGuardTest implements CovJob
{
    private static final BlockPos CHEST = new BlockPos(720, 100, 10);

    private int stage;
    private long nextAt;
    private boolean failed;

    @Override
    public String name()
    {
        return "escguard (Esc после выхода из сундука)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        server.execute(() -> {
            command(server, "forceload add 710 0 730 20");
            command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", CHEST.getX() - 3, CHEST.getY() - 1,
                    CHEST.getZ() - 3, CHEST.getX() + 3, CHEST.getY() - 1, CHEST.getZ() + 4));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=south]", CHEST.getX(), CHEST.getY(), CHEST.getZ()));
            command(server, "gamemode survival @a");
            command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
        });
        this.nextAt = Util.getMillis() + 3000L;
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

        long wait = 1500L;

        switch (this.stage)
        {
            case 0 -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
            case 1 ->
            {
                this.check("сундук открыт", mc.screen instanceof AbstractContainerScreen<?>, String.valueOf(mc.screen));
                esc(mc);
                this.check("Esc вышел из сундука", mc.screen == null, String.valueOf(mc.screen));
                esc(mc);
                this.check("обычный выход по Esc, тут же Esc — пауза как в игре", mc.screen instanceof PauseScreen,
                        String.valueOf(mc.screen));
                mc.setScreen(null);
                wait = 500L;
            }
            case 2 ->
            {
                wait = 100L;
            }
            case 3 ->
            {
                // Свой инвентарь: открыть и закрыть — защита не включается
                mc.setScreen(new InventoryScreen(mc.player));
                wait = 500L;
            }
            case 4 ->
            {
                esc(mc);
                this.check("инвентарь закрыт по Esc", mc.screen == null, String.valueOf(mc.screen));
                esc(mc);
                this.check("после своего инвентаря Esc — пауза как обычно", mc.screen instanceof PauseScreen,
                        String.valueOf(mc.screen));
                mc.setScreen(null);
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[esc] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Нажатие и отпускание Esc через обработчик клавиатуры игры — как настоящая клавиша. */
    static void esc(Minecraft mc)
    {
        KeyEvent event = new KeyEvent(InputConstants.KEY_ESCAPE, 0x1B, 0);
        DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.PRESS, event);
        DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.RELEASE, event);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[esc] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[esc] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
    }
}
