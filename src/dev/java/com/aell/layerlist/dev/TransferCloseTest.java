package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.TransferButton;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code transferclose}: автоперенос и закрытие окна посреди точного переноса (жалоба 14.10.2026 —
 * «автозабор набирает много лишних блоков», найдено на сервере с пингом 80+ мс). Схема — 30 камня, в сундуке
 * стопка: берётся часть стопки, по одному предмету за щелчок. Окно закрывается штатно ({@code Screen.onClose},
 * как Esc), пока перенос идёт; ждём ровно 30 у игрока и остаток в сундуке, окно закрывается само.
 *
 * <p>Круги — {@link #ROUNDS}: закрыть сразу; чуть позже; трижды подряд; стопка 40 (лишнее меньше нехватки —
 * другая ветка точного переноса); не закрывать вовсе; закрыть в обход экрана ({@code LocalPlayer.closeContainer},
 * как чужие моды) — придержать нельзя, но у игрока не больше нехватки и камень не пропал.
 *
 * <p>Работает и в одиночной игре, и на настоящем сервере ({@code LL_SERVER}, игрок с правами оператора): команды
 * тогда уходят в чат, а сундук пересчитывается открытием с выключенным «Auto». Пинг, который видит мод, — в лог.
 */
@Environment(EnvType.CLIENT)
public final class TransferCloseTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(640, 100, 0);
    private static final BlockPos TARGET = new BlockPos(640, 100, 14);
    private static final BlockPos CHEST = TARGET.offset(3, 0, 9);
    private static final int NEED = 30;

    /**
     * Круг: стопка в сундуке, через сколько мс после начала переноса закрыть (-1 — не закрывать), сколько раз, в обход
     * экрана, «Выходить из сундука после набора».
     */
    private record Round(int stack, long closeAfter, int closes, boolean direct, boolean autoClose)
    {
        Round(int stack, long closeAfter, int closes, boolean direct)
        {
            this(stack, closeAfter, closes, direct, true);
        }
    }

    private static final Round[] ROUNDS = {
            new Round(64, 0L, 1, false),
            new Round(64, 200L, 1, false),
            new Round(64, 120L, 3, false),
            new Round(40, 150L, 1, false),
            new Round(64, -1L, 0, false),
            new Round(64, 200L, 1, true),
            // Нужного в сундуке нет (земля): автоперенос ничего не берёт, окно остаётся открытым
            new Round(0, -1L, 0, false),
            // Выход после набора выключен (по умолчанию): забрал — окно открыто
            new Round(64, -1L, 0, false, false),
    };

    private int stage;
    private int round;
    private long nextAt;
    private long started;
    private int playerStone;
    private boolean failed;
    private SchematicPlacement placement;
    /** Как «Auto» стоял до прогона — вернуть в {@link #close()}: настройка сохраняется в конфиг стенда. */
    private boolean autoWas;
    private boolean instantWas;
    private boolean autoCloseWas;

    @Override
    public String name()
    {
        return "transferclose (закрытие окна посреди автопереноса)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        // На сервере права оператора выдаются с консоли, когда игрок зашёл, — команды чуть позже
        this.stage = -1;
        this.nextAt = Util.getMillis() + (mc.getSingleplayerServer() != null ? 0L : 6000L);
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

        long wait = 3000L;
        Round r = ROUNDS[Math.min(this.round, ROUNDS.length - 1)];

        switch (this.stage)
        {
            case -1 ->
            {
                CovOut.log("[close] " + (mc.getSingleplayerServer() != null ? "одиночная игра" : "сервер, пинг " + latency(mc) + " мс"));
                command(mc, "gamemode creative @s");
                command(mc, "forceload add 630 -10 655 30");

                for (BlockPos o : new BlockPos[]{REF, TARGET})
                {
                    command(mc, fill(o, -2, -1, -2, 8, -1, 12, "minecraft:stone"));
                    command(mc, fill(o, -2, 0, -2, 8, 5, 12, "minecraft:air"));
                }

                // 5 × 6 = 30 камня
                command(mc, fill(REF, 0, 0, 0, 4, 0, 5, "minecraft:stone"));
                command(mc, "tp @s " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 8.5D));
                wait = 5000L;
            }
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-close");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 0, 5), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-close", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                this.autoWas = LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue();
                this.instantWas = LayerListConfigs.HUD_TRANSFER_INSTANT.getBooleanValue();
                this.autoCloseWas = LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.getBooleanValue();
                LayerListConfigs.HUD_TRANSFER_INSTANT.setBooleanValue(! "1".equals(System.getenv("LL_TRANSFER_PACED")));

                LayerListConfigs.HUD_TRANSFER_BUTTON.setBooleanValue(true);
                wait = 500L;
            }
            case 1 ->
            {
                // Круг: пустой инвентарь, в сундуке стопка камня
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(true);
                LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(r.autoClose());
                command(mc, "gamemode survival @s");
                command(mc, "clear @s");
                command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                        + "{Slot:0b,id:\"minecraft:%s\",count:%d}]}", CHEST.getX(), CHEST.getY(), CHEST.getZ(),
                        r.stack() > 0 ? "stone" : "dirt", r.stack() > 0 ? r.stack() : 10));
                command(mc, "tp @s " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                wait = 2500L;
            }
            case 2 ->
            {
                openChest(mc);
                this.started = now;
                wait = 0L;
            }
            case 3 ->
            {
                // Нужного нет — автопереноса не будет: ждём и проверяем, что окно открыто
                if (r.stack() == 0)
                {
                    if (now - this.started < 3000L)
                    {
                        this.nextAt = now + 50L;
                        return false;
                    }

                    this.check(this.tag(r) + ": нужного нет — переноса не было", ! TransferButton.busy(), "");
                    this.check(this.tag(r) + ": окно осталось открытым", mc.gui.screen() instanceof AbstractContainerScreen<?>,
                            String.valueOf(mc.gui.screen()));
                    this.check(this.tag(r) + ": камня у игрока нет", countStone(mc.player.getInventory()) == 0, "камня " + countStone(mc.player.getInventory()));
                    this.stage = 8;
                    return false;
                }

                // Ждём начала автопереноса
                if (! TransferButton.busy())
                {
                    if (now - this.started > 8000L)
                    {
                        this.check(this.tag(r) + ": автоперенос начался", false, "за 8 с так и не начался");
                        this.stage = 8;
                        return false;
                    }

                    this.nextAt = now + 5L;
                    return false;
                }

                wait = Math.max(0L, r.closeAfter());
            }
            case 4 ->
            {
                // Автовыход: дождаться, пока окно закроется само, — и сразу Esc (стадии 20, 21)
                if (r.closeAfter() < 0L && r.autoClose() && r.stack() > 0)
                {
                    this.stage = 20;
                    this.nextAt = now + 5L;
                    return false;
                }

                if (r.closeAfter() >= 0L)
                {
                    Screen screen = mc.gui.screen();
                    boolean carried = mc.player.containerMenu != null && ! mc.player.containerMenu.getCarried().isEmpty();
                    CovOut.log("[close] " + this.tag(r) + ": закрываю окно, перенос идёт " + TransferButton.busy() + ", на курсоре "
                            + (carried ? mc.player.containerMenu.getCarried() : "пусто") + ", пинг " + latency(mc) + " мс");

                    for (int i = 0; i < r.closes() && mc.gui.screen() instanceof AbstractContainerScreen<?>; ++i)
                    {
                        if (r.direct())
                        {
                            mc.player.closeContainer();
                        }
                        else
                        {
                            mc.gui.screen().onClose();
                        }
                    }

                    CovOut.log("[close] " + this.tag(r) + ": сразу после просьбы закрыть окно "
                            + (mc.gui.screen() == screen ? "ещё открыто" : "закрыто"));
                }

                wait = 4000L;
            }
            case 5 ->
            {
                this.check(this.tag(r) + ": перенос закончен", ! TransferButton.busy(), "");

                // Закрывали посреди переноса — окно закрылось после сверки; не закрывали — выход после набора (если включён)
                boolean shouldClose = r.closeAfter() >= 0L || r.autoClose();
                this.check(this.tag(r) + (r.closeAfter() >= 0L ? ": окно закрылось само"
                                : r.autoClose() ? ": выход после набора — нужного больше нет" : ": выход после набора выключен — окно открыто"),
                        (mc.gui.screen() == null) == shouldClose, String.valueOf(mc.gui.screen()));

                if (mc.gui.screen() != null)
                {
                    mc.player.closeContainer();
                }

                this.playerStone = countStone(mc.player.getInventory());
                // Остаток в сундуке: открыть с выключенным «Auto» и посчитать по слотам (так и на сервере)
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(false);
                wait = 1000L;
            }
            case 6 ->
            {
                openChest(mc);
                wait = 2000L;
            }
            case 7 ->
            {
                int chest = -1;

                if (mc.gui.screen() instanceof AbstractContainerScreen<?> container)
                {
                    chest = 0;

                    for (Slot slot : container.getMenu().slots)
                    {
                        if (! (slot.container instanceof Inventory) && slot.getItem().is(Items.STONE))
                        {
                            chest += slot.getItem().getCount();
                        }
                    }

                    mc.player.closeContainer();
                }

                int stone = this.playerStone;

                if (r.direct())
                {
                    this.check(this.tag(r) + ": у игрока не больше нехватки, камень не пропал",
                            stone <= NEED && stone + chest == r.stack(), "у игрока " + stone + ", в сундуке " + chest);
                }
                else
                {
                    this.check(this.tag(r) + ": у игрока ровно нехватка, в сундуке остаток",
                            stone == NEED && chest == r.stack() - NEED, "у игрока " + stone + ", в сундуке " + chest);
                }

                wait = 500L;
            }
            case 20 ->
            {
                if (mc.gui.screen() != null)
                {
                    if (now - this.started > 8000L)
                    {
                        this.check(this.tag(r) + ": автовыход", false, "окно так и не закрылось");
                        this.stage = 5;
                        return false;
                    }

                    this.nextAt = now + 5L;
                    return false;
                }

                // Игрок жмёт Esc в миг автовыхода — меню паузы не открывается
                EscGuardTest.esc(mc);
                this.check(this.tag(r) + ": Esc сразу после автовыхода — паузы нет",
                        ! (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen), String.valueOf(mc.gui.screen()));
                this.stage = 21;
                this.nextAt = now + 1700L;
                return false;
            }
            case 21 ->
            {
                EscGuardTest.esc(mc);
                this.check(this.tag(r) + ": через 1,7 с Esc — пауза как обычно",
                        mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen, String.valueOf(mc.gui.screen()));
                mc.gui.setScreen(null);
                this.stage = 5;
                this.nextAt = now + 1000L;
                return false;
            }
            case 8 ->
            {
                if (mc.gui.screen() != null)
                {
                    mc.player.closeContainer();
                }

                if (++this.round < ROUNDS.length)
                {
                    this.stage = 1;
                    this.nextAt = now + 500L;
                    return false;
                }

                wait = 500L;
            }
            default ->
            {
                CovOut.log("[close] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private String tag(Round r)
    {
        return "круг " + (this.round + 1) + " (" + (r.stack() > 0 ? "стопка " + r.stack() : "в сундуке только земля") + ", "
                + (r.closeAfter() < 0L ? "не закрывать" : "закрыть через " + r.closeAfter() + " мс, раз " + r.closes())
                + (r.direct() ? ", в обход экрана" : "") + (r.autoClose() ? "" : ", выход выключен") + ")";
    }

    private static void openChest(Minecraft mc)
    {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
    }

    private static int countStone(Inventory inventory)
    {
        int stone = 0;

        for (int i = 0; i < inventory.getContainerSize(); ++i)
        {
            if (inventory.getItem(i).is(Items.STONE))
            {
                stone += inventory.getItem(i).getCount();
            }
        }

        return stone;
    }

    /** Пинг игрока, как его видит мод (по нему считается ожидание ответа сервера). */
    private static int latency(Minecraft mc)
    {
        @Nullable PlayerInfo info = mc.getConnection() != null && mc.player != null
                ? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
        return info != null ? info.getLatency() : -1;
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[close] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    /** Команда от имени игрока: в одиночной игре — встроенному серверу напрямую, на сервере — в чат (нужен оператор). */
    private static void command(Minecraft mc, String command)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            mc.getConnection().sendCommand(command);
            return;
        }

        server.execute(() -> {
            try
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput()
                        .withEntity(server.getPlayerList().getPlayers().get(0)), command);
            }
            catch (RuntimeException e)
            {
                CovOut.log("[close] команда не прошла (" + command + "): " + e);
            }
        });
    }

    @Override
    public void close()
    {
        LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(this.autoWas);
        LayerListConfigs.HUD_TRANSFER_INSTANT.setBooleanValue(this.instantWas);
        LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(this.autoCloseWas);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
