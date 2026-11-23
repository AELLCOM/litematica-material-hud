package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.config.LayerListSettings;
import com.aell.layerlist.core.HotbarLocks;
import com.aell.layerlist.ghost.HiddenGhosts;
import com.aell.layerlist.hud.TransferButton;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code hotbarlock} (19.11.2026): запертые ячейки хотбара и головка поршня в режиме «Только блоки из инвентаря».
 *
 * <p>1. Поршни. Схема с двумя выдвинутыми поршнями (обычный и липкий). Без поршней с собой спрятаны и основания, и
 * головки, рамок «пропущено» на головках нет; с обычным поршнем видна только его головка; с липким — обе.
 * <p>2. Карточка в настройках. По умолчанию не заперто ничего; настоящие щелчки по замкам запирают первую и девятую
 * ячейки и открывают девятую обратно — меняется опция мода {@code lockedHotbarSlots} и его файл, настройка Litematica
 * {@code pickBlockableSlots} не трогается.
 * <p>3. Easy Place. Хотбар занят, в руке неразрушимый меч в первой ячейке, золото в рюкзаке. Ячейка открыта — блок
 * встаёт на место меча, меч уходит в рюкзак (жалоба); заперта — меч на месте, блок взят в другую ячейку.
 * <p>4. Перенос из сундука. Девятая ячейка пустая: открыта — Shift+клик кладёт в неё; заперта — остаётся пустой.
 */
@Environment(EnvType.CLIENT)
public final class HotbarLockTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(1040, 100, 0);
    private static final BlockPos TARGET = new BlockPos(1040, 100, 16);
    private static final BlockPos CHEST = TARGET.offset(3, 0, 8);
    private static final BlockPos HEAD = TARGET.offset(0, 2, 0);
    private static final BlockPos STICKY_HEAD = TARGET.offset(2, 2, 0);
    private static final BlockPos BASE = TARGET.offset(0, 1, 0);
    private static final BlockPos GOLD_A = TARGET.offset(4, 0, 0);
    private static final BlockPos GOLD_B = TARGET.offset(5, 0, 0);
    private static final BlockPos IRON = TARGET.offset(6, 0, 0);

    private int stage;
    private long nextAt;
    private boolean failed;
    private int attempts;
    private SchematicPlacement placement;
    private String slotsWas = "1,2,3,4,5";
    private int locksWas;
    private boolean unsupportedWas;
    private boolean inventoryOnlyWas;
    private boolean hudWas;
    private boolean epWas;
    private boolean easyPlaceWas;

    @Override
    public String name()
    {
        return "hotbarlock (запертые ячейки хотбара, головка поршня)";
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
        this.slotsWas = Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        this.locksWas = LayerListConfigs.LOCKED_HOTBAR_SLOTS.getIntegerValue();
        this.unsupportedWas = LayerListConfigs.GHOST_HIDE_UNSUPPORTED.getBooleanValue();
        // «Скрывать без опоры» прячет головку по своей причине (основания под ней ещё нет) — здесь проверяем только инвентарь
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        this.check("по умолчанию не заперто ничего", LayerListConfigs.LOCKED_HOTBAR_SLOTS.getDefaultIntegerValue() == 0, "");
        this.check("опция в списке настроек", LayerListConfigs.OPTIONS.contains(LayerListConfigs.LOCKED_HOTBAR_SLOTS), "");
        setLocks(0);
        setSlots("1,2,3,4,5");
        this.inventoryOnlyWas = LayerListConfigs.GHOST_INVENTORY_ONLY.getBooleanValue();
        this.hudWas = LayerListConfigs.HUD_ENABLED;
        this.epWas = LayerListConfigs.EP_ENABLED.getBooleanValue();
        this.easyPlaceWas = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1030 -10 1060 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -3, 9, -1, 11, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 9, 6, 11, "minecraft:air"));
            }

            // Поршни выдвигает блок редстоуна под ними — головки появляются сами, как в настоящей постройке
            command(server, set(REF, 0, 0, 0, "minecraft:redstone_block"));
            command(server, set(REF, 0, 1, 0, "minecraft:piston[facing=up]"));
            command(server, set(REF, 2, 0, 0, "minecraft:redstone_block"));
            command(server, set(REF, 2, 1, 0, "minecraft:sticky_piston[facing=up]"));
            command(server, fill(REF, 4, 0, 0, 5, 0, 0, "minecraft:gold_block"));
            command(server, set(REF, 6, 0, 0, "minecraft:iron_block"));
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", REF.getX() + 3.5D, REF.getY(), REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 4000L;
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

        if (mc.player == null || mc.level == null || now < this.nextAt)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        Inventory inventory = mc.player.getInventory();
        long wait = 1000L;

        switch (this.stage)
        {
            // ===== 1. Поршни =====
            case 0 ->
            {
                this.check("в образце головки поршней на месте", mc.level.getBlockState(REF.offset(0, 2, 0)).is(Blocks.PISTON_HEAD)
                        && mc.level.getBlockState(REF.offset(2, 2, 0)).is(Blocks.PISTON_HEAD),
                        mc.level.getBlockState(REF.offset(0, 2, 0)) + " / " + mc.level.getBlockState(REF.offset(2, 2, 0)));
                AreaSelection area = new AreaSelection();
                area.setName("dev-hotbarlock");
                area.addSubRegionBox(new Box(REF, REF.offset(6, 2, 0), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-hotbarlock", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    // Золото с собой — чтобы было с чем сравнить: его голограмма обязана остаться
                    command(server, "item replace entity @a hotbar.3 with minecraft:gold_block 4");
                    tpViewer(server);
                });
                wait = 4000L;
            }
            case 1 ->
            {
                look(mc, TARGET.offset(3, 1, 0));
                var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
                this.check("в схеме на месте головки — головка поршня", schematicWorld != null
                        && schematicWorld.getBlockState(HEAD).is(Blocks.PISTON_HEAD), "");
                this.check("без поршней: предмета для головки нет",
                        schematicWorld != null && ! com.aell.layerlist.ghost.InventoryGhosts.available(schematicWorld.getBlockState(HEAD)), "");
                this.check("без поршней: головка обычного спрятана", HiddenGhosts.hidden(HEAD), "");
                this.check("без поршней: головка липкого спрятана", HiddenGhosts.hidden(STICKY_HEAD), "");
                this.check("без поршней: основание спрятано", HiddenGhosts.hidden(BASE), "");
                this.check("золото с собой: его голограмма видна", ! HiddenGhosts.hidden(GOLD_A), "");

                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                wait = 4000L;
            }
            case 2 ->
            {
                List<BlockPos> missing = LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.MISSING);
                this.check("Ctrl+B без поршней: головки не «пропущены»", ! missing.contains(HEAD) && ! missing.contains(STICKY_HEAD),
                        "рамок пропущено " + missing.size());
                this.check("Ctrl+B: золото «пропущено»", missing.contains(GOLD_A), "рамок пропущено " + missing.size());
                shot(mc, "поршней нет");
                server.execute(() -> command(server, "item replace entity @a hotbar.4 with minecraft:piston 2"));
                wait = 3500L;
            }
            case 3 ->
            {
                this.check("с обычным поршнем: его головка видна", ! HiddenGhosts.hidden(HEAD), "");
                this.check("с обычным поршнем: головка липкого спрятана", HiddenGhosts.hidden(STICKY_HEAD), "");
                shot(mc, "есть обычный поршень");
                server.execute(() -> command(server, "item replace entity @a hotbar.5 with minecraft:sticky_piston 2"));
                wait = 3500L;
            }
            case 4 ->
            {
                this.check("с обоими поршнями: обе головки видны", ! HiddenGhosts.hidden(HEAD) && ! HiddenGhosts.hidden(STICKY_HEAD), "");
                shot(mc, "есть оба поршня");

                if (LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);

                // ===== 2. Карточка в настройках =====
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "item replace entity @a hotbar.0 with minecraft:diamond_sword[unbreakable={}]");
                    command(server, "item replace entity @a hotbar.1 with minecraft:diamond_pickaxe");
                    command(server, "item replace entity @a hotbar.2 with minecraft:gold_block 32");
                    command(server, "item replace entity @a hotbar.4 with minecraft:cooked_beef 16");
                    command(server, "item replace entity @a hotbar.8 with minecraft:torch 40");
                });
                wait = 1500L;
            }
            case 5 ->
            {
                var screen = com.aell.layerlist.config.GuiConfigs.create(null, null);
                mc.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                search(screen, com.aell.layerlist.shaded.ui.Strings.plain("layerlist.config.hotbarLocks.name"));
                wait = 2500L;
            }
            case 6 ->
            {
                int[] first = LayerListSettings.HOTBAR_LOCKS.slotBounds(0);
                this.check("карточка нарисована", first[2] > 0, java.util.Arrays.toString(first));
                this.check("сначала все ячейки открыты", HotbarLocks.lockedCount() == 0, "заперто " + HotbarLocks.lockedCount());
                shot(mc, "замки по умолчанию");
                clickSlot(mc, 0);
                wait = 1200L;
            }
            case 7 ->
            {
                this.check("щелчок запер первую ячейку", HotbarLocks.locked(0) && HotbarLocks.lockedCount() == 1,
                        "опция " + LayerListConfigs.LOCKED_HOTBAR_SLOTS.getIntegerValue());
                this.check("настройка Litematica не тронута", "1,2,3,4,5".equals(Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue())
                        && "[0, 1, 2, 3, 4]".equals(liveList()), Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue() + " / " + liveList());
                clickSlot(mc, 8);
                wait = 1200L;
            }
            case 8 ->
            {
                this.check("щелчок запер девятую ячейку", HotbarLocks.locked(8) && HotbarLocks.lockedCount() == 2,
                        "опция " + LayerListConfigs.LOCKED_HOTBAR_SLOTS.getIntegerValue());
                this.check("файл настроек мода сохранён сразу", savedLocks().contains("257"), savedLocks());
                // Курсор — на запертую первую ячейку: в кадре и наведение, и золотой пульс
                int[] box = LayerListSettings.HOTBAR_LOCKS.slotBounds(0);
                SupportTest.moveMouse(mc, box[0] + box[2] / 2.0D, box[1] + box[3] / 2.0D);
                wait = 700L;
            }
            case 9 ->
            {
                shot(mc, "замки: первая и девятая заперты");
                wait = 1200L;
            }
            case 10 ->
            {
                // Полпериода спустя — золото другой яркости: пульс виден парой снимков
                shot(mc, "замки: пульс");
                clickSlot(mc, 8);
                wait = 1000L;
            }
            case 11 ->
            {
                this.check("повторный щелчок открыл девятую", ! HotbarLocks.locked(8) && HotbarLocks.locked(0),
                        "опция " + LayerListConfigs.LOCKED_HOTBAR_SLOTS.getIntegerValue());
                mc.setScreen(null);

                // ===== 3. Easy Place: ячейка открыта — меч вытесняется (жалоба) =====
                setLocks(0);
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                this.fullHotbar(server);
                this.attempts = 0;
                wait = 3000L;
            }
            case 12 ->
            {
                if (this.placing(mc, inventory, GOLD_A, now))
                {
                    return false;
                }

                this.check("ячейка открыта: блок встал", mc.level.getBlockState(GOLD_A).is(Blocks.GOLD_BLOCK), String.valueOf(mc.level.getBlockState(GOLD_A)));
                this.check("ячейка открыта: меч вытеснен из первой ячейки (жалоба воспроизведена)",
                        ! inventory.getItem(0).is(Items.DIAMOND_SWORD), "в первой " + inventory.getItem(0) + ", меч в ячейке " + find(inventory, Items.DIAMOND_SWORD));

                // ===== Заперта — меч на месте =====
                HotbarLocks.setLocked(0, true);
                this.fullHotbar(server);
                this.attempts = 0;
                wait = 3000L;
            }
            case 13 ->
            {
                if (this.placing(mc, inventory, GOLD_B, now))
                {
                    return false;
                }

                this.check("ячейка заперта: блок встал", mc.level.getBlockState(GOLD_B).is(Blocks.GOLD_BLOCK), String.valueOf(mc.level.getBlockState(GOLD_B)));
                this.check("ячейка заперта: меч остался в первой ячейке", inventory.getItem(0).is(Items.DIAMOND_SWORD),
                        "в первой " + inventory.getItem(0) + ", меч в ячейке " + find(inventory, Items.DIAMOND_SWORD));
                int gold = find(inventory, Items.GOLD_BLOCK);
                this.check("ячейка заперта: золото взято в открытую ячейку хотбара", gold >= 1 && gold <= 4, "золото в ячейке " + gold);
                shot(mc, "Easy Place с запертой ячейкой");

                // ===== Запертая пустая ячейка: пустую Litematica берёт первой, мимо общей проверки =====
                setLocks(0b11);
                this.fullHotbar(server);
                server.execute(() -> {
                    command(server, "item replace entity @a hotbar.1 with minecraft:air");
                    command(server, "item replace entity @a inventory.5 with minecraft:iron_block 8");
                });
                this.attempts = 0;
                wait = 3000L;
            }
            case 14 ->
            {
                if (this.placing(mc, inventory, IRON, now))
                {
                    return false;
                }

                this.check("пустая запертая: блок встал", mc.level.getBlockState(IRON).is(Blocks.IRON_BLOCK), String.valueOf(mc.level.getBlockState(IRON)));
                this.check("пустая запертая: вторая ячейка осталась пустой, меч на месте", inventory.getItem(1).isEmpty()
                        && inventory.getItem(0).is(Items.DIAMOND_SWORD), hotbar(inventory));
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);

                // ===== 4. Перенос из сундука: девятая открыта =====
                setLocks(0);
                LayerListConfigs.HUD_ENABLED = true;
                this.chestSetup(server);
                wait = 4000L;
            }
            case 15, 18 ->
            {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
                wait = 2500L;
            }
            case 16, 19 ->
            {
                int[] bounds = TransferButton.bounds();
                this.check("сундук открыт, кнопка переноса нарисована", fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?> && bounds[2] > 0,
                        fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() + " " + java.util.Arrays.toString(bounds));

                if (bounds[2] > 0)
                {
                    SupportTest.click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + bounds[3] / 2.0D);
                }

                wait = 3000L;
            }
            case 17 ->
            {
                this.check("девятая открыта: перенос кладёт в неё (как Shift+клик)", ! inventory.getItem(8).isEmpty(),
                        "в девятой " + inventory.getItem(8) + "; " + hotbar(inventory));
                mc.player.closeContainer();

                // Заперта только девятая
                HotbarLocks.setLocked(8, true);
                this.chestSetup(server);
                wait = 4000L;
            }
            case 20 ->
            {
                this.check("девятая заперта: осталась пустой", inventory.getItem(8).isEmpty(), "в девятой " + inventory.getItem(8));
                this.check("девятая заперта: нехватка всё равно перенесена", count(inventory, Items.GOLD_BLOCK) == 2
                                && count(inventory, Items.IRON_BLOCK) == 1 && count(inventory, Items.PISTON) == 1,
                        "золото " + count(inventory, Items.GOLD_BLOCK) + ", железо " + count(inventory, Items.IRON_BLOCK)
                                + ", поршни " + count(inventory, Items.PISTON) + "; " + hotbar(inventory));
                this.check("курсор пуст", mc.player.containerMenu.getCarried().isEmpty(), String.valueOf(mc.player.containerMenu.getCarried()));
                shot(mc, "перенос с запертой девятой");
                mc.player.closeContainer();
            }
            default ->
            {
                CovOut.log("[hotbarlock] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Шаг Easy Place по клетке; истина — ещё ставим (блока нет и попытки не вышли). */
    private boolean placing(Minecraft mc, Inventory inventory, BlockPos cell, long now)
    {
        if (! mc.level.getBlockState(cell).isAir() || this.attempts >= 30)
        {
            return false;
        }

        if (this.attempts == 0)
        {
            inventory.setSelectedSlot(0);
        }

        ++this.attempts;
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atLowerCornerOf(cell).add(0.5D, 0.98D, 0.5D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
        this.nextAt = now + 200L;
        return true;
    }

    /** Хотбар занят (ячейки 1–5), рука на неразрушимом мече в первой, золото — в рюкзаке. */
    private void fullHotbar(MinecraftServer server)
    {
        server.execute(() -> {
            command(server, "gamemode survival @a");
            command(server, "clear @a");
            command(server, "item replace entity @a hotbar.0 with minecraft:diamond_sword[unbreakable={}]");
            command(server, "item replace entity @a hotbar.1 with minecraft:dirt 8");
            command(server, "item replace entity @a hotbar.2 with minecraft:cobblestone 8");
            command(server, "item replace entity @a hotbar.3 with minecraft:oak_planks 8");
            command(server, "item replace entity @a hotbar.4 with minecraft:stone 8");
            command(server, "item replace entity @a inventory.4 with minecraft:gold_block 8");
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 40", TARGET.getX() + 5.0D, TARGET.getY(), TARGET.getZ() + 2.5D));
        });
    }

    /** Пустой инвентарь, пустая площадка, сундук с нехваткой схемы: золото и поршень — стопками целиком, железо — с избытком. */
    private void chestSetup(MinecraftServer server)
    {
        server.execute(() -> {
            command(server, "clear @a");
            command(server, fill(TARGET, 0, 0, 0, 6, 2, 0, "minecraft:air"));
            command(server, set(TARGET, 3, 0, 8, "minecraft:air"));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                            + "{Slot:0b,id:\"minecraft:gold_block\",count:2},{Slot:1b,id:\"minecraft:iron_block\",count:64},"
                            + "{Slot:2b,id:\"minecraft:piston\",count:1},{Slot:3b,id:\"minecraft:dirt\",count:5}]}",
                    CHEST.getX(), CHEST.getY(), CHEST.getZ()));
            command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
        });
    }

    private static void tpViewer(MinecraftServer server)
    {
        command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 10", TARGET.getX() + 3.5D, TARGET.getY() + 1, TARGET.getZ() + 6.5D));
    }

    private static void clickSlot(Minecraft mc, int slot)
    {
        int[] box = LayerListSettings.HOTBAR_LOCKS.slotBounds(slot);
        SupportTest.click(mc, box[0] + box[2] / 2.0D, box[1] + box[3] / 2.0D);
    }

    private static void setLocks(int mask)
    {
        LayerListConfigs.LOCKED_HOTBAR_SLOTS.setIntegerValue(mask);
    }

    private static void setSlots(String value)
    {
        Configs.Generic.PICK_BLOCKABLE_SLOTS.setValueFromString(value);
        fi.dy.masa.litematica.util.InventoryUtils.setPickBlockableSlots(value);
    }

    /** Рабочий список Litematica (номера с нуля) — им она выбирает ячейку на деле. */
    private static String liveList()
    {
        try
        {
            var field = fi.dy.masa.litematica.util.InventoryUtils.class.getDeclaredField("PICK_BLOCKABLE_SLOTS");
            field.setAccessible(true);
            return String.valueOf(field.get(null));
        }
        catch (ReflectiveOperationException e)
        {
            return String.valueOf(e);
        }
    }

    /** Строка {@code lockedHotbarSlots} из файла настроек мода на диске. */
    private static String savedLocks()
    {
        try
        {
            for (String line : Files.readAllLines(FabricLoader.getInstance().getConfigDir().resolve("layerlist.json")))
            {
                if (line.contains("lockedHotbarSlots"))
                {
                    return line.trim();
                }
            }

            return "строки нет";
        }
        catch (java.io.IOException e)
        {
            return String.valueOf(e);
        }
    }

    private static int find(Inventory inventory, Item item)
    {
        for (int i = 0; i < 36; ++i)
        {
            if (inventory.getItem(i).is(item))
            {
                return i;
            }
        }

        return -1;
    }

    private static int count(Inventory inventory, Item item)
    {
        int count = 0;

        for (int i = 0; i < 36; ++i)
        {
            if (inventory.getItem(i).is(item))
            {
                count += inventory.getItem(i).getCount();
            }
        }

        return count;
    }

    private static String hotbar(Inventory inventory)
    {
        StringBuilder text = new StringBuilder("хотбар:");

        for (int i = 0; i < 9; ++i)
        {
            text.append(' ').append(i + 1).append('=').append(inventory.getItem(i).isEmpty() ? "-" : inventory.getItem(i).toString());
        }

        return text.toString();
    }

    private static void look(Minecraft mc, BlockPos at)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atCenterOf(at);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** Строка поиска настроек — как в {@link SettingsShot}. */
    private static void search(Object screen, String query)
    {
        try
        {
            Class<?> type = com.aell.layerlist.shaded.ui.SettingsScreen.class;
            var field = type.getDeclaredField("search");
            field.setAccessible(true);
            field.set(screen, query);
            var layout = type.getDeclaredMethod("layout");
            layout.setAccessible(true);
            layout.invoke(screen);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[hotbarlock] поиск не задан: " + e);
        }
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static String set(BlockPos o, int x, int y, int z, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY() + y, o.getZ() + z, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[hotbarlock] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[hotbarlock] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[hotbarlock] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        Configs.Generic.PICK_BLOCKABLE_SLOTS.setValueFromString(this.slotsWas);
        fi.dy.masa.litematica.util.InventoryUtils.setPickBlockableSlots(this.slotsWas);
        LayerListConfigs.LOCKED_HOTBAR_SLOTS.setIntegerValue(this.locksWas);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(this.unsupportedWas);
        new LayerListConfigs().save();
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(this.inventoryOnlyWas);
        LayerListConfigs.HUD_ENABLED = this.hudWas;
        LayerListConfigs.EP_ENABLED.setBooleanValue(this.epWas);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(this.easyPlaceWas);
    }
}
