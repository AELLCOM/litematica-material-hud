package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.hud.TransferButton;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
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
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code bannertake}: «Забрать» и флаги одного цвета с узором и без (жалоба 13.11.2026: «в схеме чёрный флаг и
 * чёрный с узорами — мод ставит правильно, но чёрный с узорами всё равно набирает»). Схема: 2 обычных чёрных флага и
 * 1 чёрный с крестом (узор A). В сундуке по 5: узор A, узор B (другой), обычные — узорные первыми, чтобы перенос
 * наткнулся на них раньше. Круг 1 — рюкзак пуст: ждём 2 обычных, 1 с узором A, 0 с узором B. Круг 2 — в рюкзаке
 * уже 3 с узором A: строка узора закрыта, строке обычного они не в счёт — ждём +2 обычных, A не прибавилось.
 */
@Environment(EnvType.CLIENT)
public final class BannerTakeTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(900, 100, 0);
    private static final BlockPos TARGET = new BlockPos(900, 100, 14);
    private static final BlockPos CHEST = TARGET.offset(3, 0, 6);
    private static final String A = "[{pattern:\"minecraft:cross\",color:\"white\"}]";
    private static final String B = "[{pattern:\"minecraft:stripe_top\",color:\"red\"}]";

    private int stage;
    private int round = 1;
    private long nextAt;
    private long started;
    private boolean failed;
    private boolean autoWas;
    private boolean autoCloseWas;
    private boolean instantWas;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "bannertake (Забрать: обычный флаг и флаг с узором)";
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

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 890 -10 915 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 8, -1, 9, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 4, 9, "minecraft:air"));
            }

            command(server, set(REF, 1, "minecraft:black_banner[rotation=8]"));
            command(server, set(REF, 3, "minecraft:black_banner[rotation=8]"));
            command(server, set(REF, 5, "minecraft:black_banner[rotation=8]{patterns:" + A + "}"));
            command(server, "tp @a " + (REF.getX() + 3.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
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
        long wait = 1000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-bannertake");
                area.addSubRegionBox(new Box(REF, REF.offset(6, 0, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-bannertake", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                this.autoWas = LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue();
                this.autoCloseWas = LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.getBooleanValue();
                this.instantWas = LayerListConfigs.HUD_TRANSFER_INSTANT.getBooleanValue();
                LayerListConfigs.HUD_TRANSFER_BUTTON.setBooleanValue(true);
                LayerListConfigs.HUD_TRANSFER_INSTANT.setBooleanValue(true);
                LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(false);
                LayerCountManager.getInstance().markDirty();
                wait = 3000L;
            }
            case 1 ->
            {
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(true);
                int round = this.round;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");

                    if (round == 2)
                    {
                        command(server, "item replace entity @a inventory.10 with minecraft:black_banner[banner_patterns=" + A + "] 3");
                    }

                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                                    + "{Slot:0b,id:\"minecraft:black_banner\",count:5,components:{\"minecraft:banner_patterns\":%s}},"
                                    + "{Slot:1b,id:\"minecraft:black_banner\",count:5,components:{\"minecraft:banner_patterns\":%s}},"
                                    + "{Slot:2b,id:\"minecraft:black_banner\",count:5}]}",
                            CHEST.getX(), CHEST.getY(), CHEST.getZ(), A, B));
                    command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                });
                wait = 2500L;
            }
            case 2 ->
            {
                this.logRows();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
                this.started = now;
                wait = 1500L;
            }
            case 3 ->
            {
                if (TransferButton.busy() && now - this.started < 10000L)
                {
                    this.nextAt = now + 50L;
                    return false;
                }

                CovOut.log("[btake] круг " + this.round + ": снимок");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                wait = 700L;
            }
            case 4 ->
            {
                Inventory inv = mc.player.getInventory();
                int plain = count(inv, null);
                int a = count(inv, "cross");
                int b = count(inv, "stripe_top");
                String got = "обычных " + plain + ", с крестом " + a + ", с полосой " + b;
                CovOut.log("[btake] круг " + this.round + ": у игрока " + got);

                if (this.round == 1)
                {
                    this.check("круг 1: обычных забрано 2", plain == 2, got);
                    this.check("круг 1: с крестом (как в схеме) забран 1", a == 1, got);
                }
                else
                {
                    this.check("круг 2: узорные в рюкзаке не в счёт строке обычного — забрано 2 обычных", plain == 2, got);
                    this.check("круг 2: с крестом не прибавилось (их 3, нужен 1)", a == 3, got);
                }

                this.check("круг " + this.round + ": чужой узор (полоса) не взят", b == 0, got);

                if (mc.screen instanceof AbstractContainerScreen<?>)
                {
                    mc.player.closeContainer();
                }

                if (this.round == 1)
                {
                    this.round = 2;
                    this.stage = 1;
                    this.nextAt = now + 1000L;
                    return false;
                }
            }
            default ->
            {
                CovOut.log("[btake] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private void logRows()
    {
        java.util.List<String> rows = new ArrayList<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            rows.add(entry.getStack().getItem() + layers(entry.getStack()) + " ×" + entry.getCountMissing());
        }

        CovOut.log("[btake] круг " + this.round + ": строки HUD " + rows);
    }

    private static String layers(ItemStack stack)
    {
        BannerPatternLayers p = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        return p.layers().isEmpty() ? "" : "+" + p.layers().get(0).pattern().getRegisteredName();
    }

    /** Чёрных флагов: {@code pattern == null} — без узора, иначе — с первым слоем этого узора. */
    private static int count(Inventory inv, String pattern)
    {
        int n = 0;

        for (int i = 0; i < inv.getContainerSize(); ++i)
        {
            ItemStack stack = inv.getItem(i);

            if (! net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("black_banner"))
            {
                continue;
            }

            String l = layers(stack);

            if (pattern == null ? l.isEmpty() : l.endsWith(":" + pattern))
            {
                n += stack.getCount();
            }
        }

        return n;
    }

    private static String set(BlockPos o, int x, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY(), o.getZ() + 1, block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[btake] " + (ok ? "OK   " : "FAIL ") + what + (ok ? "" : " — " + detail));
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
            CovOut.log("[btake] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(this.autoWas);
        LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.setBooleanValue(this.autoCloseWas);
        LayerListConfigs.HUD_TRANSFER_INSTANT.setBooleanValue(this.instantWas);
    }
}
