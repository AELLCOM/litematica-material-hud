package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.TransferButton;
import com.aell.layerlist.storage.StorageIndex;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code transferbox}: опция «Забирать шалкеры» ({@code hudTransferShulkers}, просьба 14.10.2026 — «так же
 * забирать шалкеры для нажатия по кнопке/авто»). Схема — 30 камня. Круги: опция выключена — шалкер с камнем не
 * трогается; включена — шалкер забирается; россыпь 20 + шалкер — оба; россыпь 40 + шалкер — ровно 30 россыпью,
 * шалкер остаётся; шалкер с землёй и шалкер с камнем — только с камнем; то же по кнопке, а не «Auto».
 */
@Environment(EnvType.CLIENT)
public final class TransferBoxTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(700, 100, 0);
    private static final BlockPos TARGET = new BlockPos(700, 100, 14);
    private static final BlockPos CHEST = TARGET.offset(3, 0, 9);

    /**
     * Круг: опция, россыпь камня в сундуке, шалкеры (что внутри: "stone"/"dirt"), щелчок кнопкой вместо «Auto»;
     * ожидание — у игрока россыпью, шалкеров у игрока, россыпью в сундуке, шалкеров в сундуке.
     */
    private record Round(String name, boolean option, int loose, List<String> boxes, boolean button,
                         int playerLoose, int playerBoxes, int chestLoose, int chestBoxes, int looseStacks, int freeSlots)
    {
        /** Россыпь одной стопкой, инвентарь пуст. */
        Round(String name, boolean option, int loose, List<String> boxes, boolean button,
              int playerLoose, int playerBoxes, int chestLoose, int chestBoxes)
        {
            this(name, option, loose, boxes, button, playerLoose, playerBoxes, chestLoose, chestBoxes, 1, 36);
        }
    }

    private static final Round[] ROUNDS = {
            new Round("опция выключена", false, 0, List.of("stone"), false, 0, 0, 0, 1),
            new Round("только шалкер", true, 0, List.of("stone"), false, 0, 1, 0, 0),
            new Round("россыпь 20 + шалкер", true, 20, List.of("stone"), false, 20, 1, 0, 0),
            new Round("россыпь 40 + шалкер", true, 40, List.of("stone"), false, 30, 0, 10, 1),
            new Round("шалкер с землёй и с камнем", true, 0, List.of("dirt", "stone"), false, 0, 1, 0, 1),
            new Round("по кнопке", true, 0, List.of("stone"), true, 0, 1, 0, 0),
            // Россыпь 3 стопки по 10, а свободен один слот — шалкер вперёд, россыпь остаётся (просьба 15.10.2026)
            new Round("россыпь не влезает — шалкер вперёд", true, 30, List.of("stone"), false, 0, 1, 30, 0, 3, 1),
            // То же, но места хватает — россыпью, шалкер не нужен
            new Round("россыпь влезает — россыпью", true, 30, List.of("stone"), false, 30, 0, 0, 1, 3, 36),
    };

    private int stage;
    private int round;
    private long nextAt;
    private long started;
    private int[] player = new int[3];
    private boolean failed;
    private SchematicPlacement placement;
    private boolean autoWas;
    private boolean boxesWas;
    private boolean autoCloseWas;

    @Override
    public String name()
    {
        return "transferbox (перенос шалкеров с нужным)";
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
        this.stage = -1;
        this.nextAt = Util.getMillis();
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
                command(mc, "gamemode creative @s");
                command(mc, "forceload add 690 -10 715 30");

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
                area.setName("dev-box");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 0, 5), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-box", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                this.autoWas = LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue();
                this.boxesWas = LayerListConfigs.HUD_TRANSFER_SHULKERS.getBooleanValue();
                this.autoCloseWas = LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.getBooleanValue();
                // Сундук пересчитывается через открытое окно — автовыход мешал бы
                LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(false);
                LayerListConfigs.HUD_TRANSFER_BUTTON.setBooleanValue(true);
                wait = 500L;
            }
            case 1 ->
            {
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(! r.button());
                LayerListConfigs.HUD_TRANSFER_SHULKERS.setBooleanValue(r.option());
                command(mc, "gamemode survival @s");
                command(mc, "clear @s");
                command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                StringBuilder items = new StringBuilder();
                int slot = 0;

                // Россыпь — r.looseStacks() стопками поровну
                for (int k = 0; r.loose() > 0 && k < r.looseStacks(); ++k)
                {
                    int count = r.loose() / r.looseStacks() + (k < r.loose() % r.looseStacks() ? 1 : 0);
                    items.append(items.isEmpty() ? "" : ",").append(String.format(Locale.ROOT,
                            "{Slot:%db,id:\"minecraft:stone\",count:%d}", slot++, count));
                }

                // Занять инвентарь игрока, оставив r.freeSlots() свободных (хотбар и рюкзак — 36 слотов)
                for (int k = 0; k < 36 - r.freeSlots(); ++k)
                {
                    command(mc, String.format(Locale.ROOT, "item replace entity @s %s with minecraft:cobbled_deepslate 64",
                            k < 9 ? "hotbar." + k : "inventory." + (k - 9)));
                }

                for (String inside : r.boxes())
                {
                    items.append(items.isEmpty() ? "" : ",").append(String.format(Locale.ROOT,
                            "{Slot:%db,id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":"
                                    + "[{slot:0,item:{id:\"minecraft:%s\",count:64}}]}}", slot++, inside));
                }

                command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:[%s]}",
                        CHEST.getX(), CHEST.getY(), CHEST.getZ(), items));
                command(mc, "tp @s " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                wait = 2500L;
            }
            case 2 ->
            {
                openChest(mc);
                this.started = now;
                wait = r.button() ? 1500L : 0L;
            }
            case 3 ->
            {
                if (r.button())
                {
                    int[] b = TransferButton.bounds();
                    boolean clicked = b[2] > 0 && TransferButton.mouseClicked(mc.screen, b[0] + b[2] / 2.0D,
                            b[1] + b[3] / 2.0D, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
                    this.check(this.tag(r) + ": кнопка нажата", clicked, "кнопка " + b[0] + "×" + b[1] + " " + b[2] + "×" + b[3]);
                    wait = 4000L;
                }
                else
                {
                    // Автоперенос: ждём начала; не начался за 4 с — значит, брать нечего (круг «опция выключена»)
                    if (! TransferButton.busy() && now - this.started < 4000L)
                    {
                        this.nextAt = now + 5L;
                        return false;
                    }

                    wait = 4000L;
                }
            }
            case 4 ->
            {
                this.check(this.tag(r) + ": перенос закончен", ! TransferButton.busy(), "");

                if (mc.screen != null)
                {
                    mc.player.closeContainer();
                }

                List<ItemStack> own = new ArrayList<>();

                for (int i = 0; i < mc.player.getInventory().getContainerSize(); ++i)
                {
                    own.add(mc.player.getInventory().getItem(i));
                }

                this.player = count(own);
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(false);
                wait = 1000L;
            }
            case 5 ->
            {
                openChest(mc);
                wait = 2000L;
            }
            case 6 ->
            {
                int[] chest = {-1, -1, -1};

                if (mc.screen instanceof AbstractContainerScreen<?> container)
                {
                    List<ItemStack> stacks = new ArrayList<>();

                    for (Slot slot : container.getMenu().slots)
                    {
                        if (! (slot.container instanceof Inventory))
                        {
                            stacks.add(slot.getItem());
                        }
                    }

                    chest = count(stacks);
                    mc.player.closeContainer();
                }

                String got = "у игрока россыпью " + this.player[0] + ", шалкеров " + this.player[1] + " (камня в них " + this.player[2]
                        + "); в сундуке россыпью " + chest[0] + ", шалкеров " + chest[1];
                boolean ok = this.player[0] == r.playerLoose() && this.player[1] == r.playerBoxes()
                        && chest[0] == r.chestLoose() && chest[1] == r.chestBoxes()
                        && this.player[2] == 64 * r.playerBoxes();
                this.check(this.tag(r) + ": ждём россыпью " + r.playerLoose() + ", шалкеров " + r.playerBoxes()
                        + "; в сундуке " + r.chestLoose() + " и " + r.chestBoxes(), ok, got);
                wait = 500L;
            }
            case 7 ->
            {
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
                CovOut.log("[box] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private String tag(Round r)
    {
        return "круг " + (this.round + 1) + " (" + r.name() + ")";
    }

    /** {россыпь камня, шалкеров, камня внутри шалкеров}. */
    private static int[] count(List<ItemStack> stacks)
    {
        int[] out = new int[3];

        for (ItemStack stack : stacks)
        {
            if (stack.is(Items.STONE))
            {
                out[0] += stack.getCount();
            }
            else if (stack.is(Items.SHULKER_BOX))
            {
                ++out[1];
                StorageIndex.forEachInside(stack, inner -> out[2] += inner.is(Items.STONE) ? inner.getCount() : 0);
            }
        }

        return out;
    }

    private static void openChest(Minecraft mc)
    {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[box] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

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
                CovOut.log("[box] команда не прошла (" + command + "): " + e);
            }
        });
    }

    @Override
    public void close()
    {
        LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(this.autoWas);
        LayerListConfigs.HUD_TRANSFER_SHULKERS.setBooleanValue(this.boxesWas);
        LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(this.autoCloseWas);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
