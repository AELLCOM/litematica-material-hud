package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.hud.TransferButton;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code statuepose} (19.11.2026): статуи медного голема набираются, игнорируя позу; различаются только
 * окисленность и воск (это разные блоки и предметы).
 *
 * <p>Схема: две медные статуи (сидит, «звезда») и одна вощёная окисленная (бежит). У игрока и в сундуке — статуи тех же
 * видов, но в других позах (поза лежит в компоненте {@code block_state} предмета). Проверяется: одна строка списка на
 * вид; «есть с собой» считает статуи любой позы; предмет клетки у Litematica — без позы, с миром и без; перенос из
 * сундука берёт статуи других поз; Easy Place берёт в руку и ставит статую другой позы и доводит позу до схемы; статуя
 * той же меди в другой позе в мире — «построено» для списка, но «не то состояние» для проверки; другая окисленность —
 * не построено.
 */
@Environment(EnvType.CLIENT)
public final class StatuePoseTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(1160, 100, 0);
    private static final BlockPos TARGET = new BlockPos(1160, 100, 16);
    private static final BlockPos CHEST = TARGET.offset(2, 0, 8);
    private static final BlockPos SITTING = TARGET.offset(0, 0, 0);
    private static final BlockPos STAR = TARGET.offset(2, 0, 0);
    private static final BlockPos WAXED = TARGET.offset(4, 0, 0);
    private static final String COPPER_ID = "copper_golem_statue";
    private static final String WAXED_ID = "waxed_oxidized_copper_golem_statue";
    private static final String EXPOSED_ID = "exposed_copper_golem_statue";

    private int stage;
    private long nextAt;
    private boolean failed;
    private int attempts;
    private SchematicPlacement placement;
    private boolean hudWas;
    private boolean onlyMissingWas;
    private boolean stockWas;
    private boolean shortfallWas;
    private boolean epWas;
    private boolean easyPlaceWas;

    @Override
    public String name()
    {
        return "statuepose (статуи медного голема без учёта позы)";
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
        this.hudWas = LayerListConfigs.HUD_ENABLED;
        this.onlyMissingWas = LayerListConfigs.ONLY_MISSING.getBooleanValue();
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.shortfallWas = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        this.epWas = LayerListConfigs.EP_ENABLED.getBooleanValue();
        this.easyPlaceWas = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        // «Стоит не то» идёт в нехватку: видно, что другая окисленность — не построено, а другая поза — построено
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1150 -10 1180 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -3, 8, -1, 11, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 8, 5, 11, "minecraft:air"));
            }

            command(server, set(REF, 0, 0, 0, "minecraft:copper_golem_statue[copper_golem_pose=sitting,facing=south]"));
            command(server, set(REF, 2, 0, 0, "minecraft:copper_golem_statue[copper_golem_pose=star,facing=south]"));
            command(server, set(REF, 4, 0, 0, "minecraft:waxed_oxidized_copper_golem_statue[copper_golem_pose=running,facing=south]"));
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", REF.getX() + 2.5D, REF.getY(), REF.getZ() + 5.5D));
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
        long wait = 3000L;

        switch (this.stage)
        {
            case 0 ->
            {
                this.check("образец: три статуи на месте", mc.level.getBlockState(REF).getBlock() instanceof CopperGolemStatueBlock
                        && mc.level.getBlockState(REF.offset(2, 0, 0)).getBlock() instanceof CopperGolemStatueBlock
                        && is(mc.level.getBlockState(REF.offset(4, 0, 0)), WAXED_ID),
                        mc.level.getBlockState(REF) + " " + mc.level.getBlockState(REF.offset(4, 0, 0)));
                AreaSelection area = new AreaSelection();
                area.setName("dev-statuepose");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 0, 0), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-statuepose", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    // В сундуке — медные статуи в позах, которых в схеме нет, двумя разными стопками, и вощёная стоя
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                                    + "{Slot:0b,id:\"minecraft:copper_golem_statue\",count:1,components:{\"minecraft:block_state\":{copper_golem_pose:\"running\"}}},"
                                    + "{Slot:1b,id:\"minecraft:copper_golem_statue\",count:1,components:{\"minecraft:block_state\":{copper_golem_pose:\"standing\"}}},"
                                    + "{Slot:2b,id:\"minecraft:waxed_oxidized_copper_golem_statue\",count:1,components:{\"minecraft:block_state\":{copper_golem_pose:\"standing\"}}},"
                                    + "{Slot:3b,id:\"minecraft:exposed_copper_golem_statue\",count:1}]}",
                            CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                    command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                });
                LayerCountManager.getInstance().markDirty();
                wait = 5000L;
            }
            case 1 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "пустой инвентарь", "copper_golem_statue", 2, 2, 0);
                this.expect(list, "пустой инвентарь", "waxed_oxidized_copper_golem_statue", 1, 1, 0);
                this.check("строк статуй — по одной на вид, а не на позу", list.size() == 2, show(list));
                // Предмет клетки у Litematica — без позы: и с миром схемы (так спрашивают Easy Place и pick-block), и без
                var schematicWorld = SchematicWorldHandler.getSchematicWorld();
                BlockState sitting = schematicWorld == null ? Blocks.AIR.defaultBlockState() : schematicWorld.getBlockState(SITTING);
                this.check("в мире схемы на месте — статуя сидя", sitting.getBlock() instanceof CopperGolemStatueBlock
                        && sitting.getValue(CopperGolemStatueBlock.POSE) == CopperGolemStatueBlock.Pose.SITTING, String.valueOf(sitting));
                ItemStack withWorld = MaterialCache.getInstance().getRequiredBuildItemForState(sitting, schematicWorld, SITTING);
                ItemStack noWorld = MaterialCache.getInstance().getRequiredBuildItemForState(sitting);
                // «Без позы схемы» — как только что скрафченная: у предмета статуи в игре по умолчанию записано «стоит»
                ItemStack plain = sitting.getBlock().asItem().getDefaultInstance();
                this.check("предмет клетки (с миром схемы) — обычная медная статуя, не «сидит» из схемы", is(withWorld, COPPER_ID)
                        && ItemStack.isSameItemSameComponents(withWorld, plain), withWorld + " " + withWorld.get(DataComponents.BLOCK_STATE));
                this.check("предмет клетки (без мира) — обычная медная статуя", is(noWorld, COPPER_ID)
                        && ItemStack.isSameItemSameComponents(noWorld, plain), noWorld + " " + noWorld.get(DataComponents.BLOCK_STATE));
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
                wait = 2500L;
            }
            case 2 ->
            {
                int[] bounds = TransferButton.bounds();
                this.check("сундук открыт, кнопка переноса нарисована",
                        fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?> && bounds[2] > 0,
                        fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() + " " + java.util.Arrays.toString(bounds));
                shot(mc, "сундук со статуями в других позах");

                if (bounds[2] > 0)
                {
                    SupportTest.click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + bounds[3] / 2.0D);
                }

                wait = 3500L;
            }
            case 3 ->
            {
                this.check("перенос взял обе медные статуи (позы не те, что в схеме) и вощёную", count(inventory, COPPER_ID) == 2
                                && count(inventory, WAXED_ID) == 1,
                        "медных " + count(inventory, COPPER_ID) + ", вощёных окисленных " + count(inventory, WAXED_ID));
                this.check("перенос не взял статую другой окисленности", count(inventory, EXPOSED_ID) == 0,
                        "потемневших " + count(inventory, EXPOSED_ID));
                mc.player.closeContainer();
                LayerCountManager.getInstance().markDirty();
                wait = 3000L;
            }
            case 4 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "статуи с собой (другие позы)", "copper_golem_statue", 2, 2, 2);
                this.expect(list, "статуи с собой (другие позы)", "waxed_oxidized_copper_golem_statue", 1, 1, 1);
                shot(mc, "статуи с собой засчитаны");
                // Easy Place: рука на пустой ячейке, статуи — где положил перенос
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 40",
                        TARGET.getX() + 1.5D, TARGET.getY(), TARGET.getZ() + 2.5D)));
                this.attempts = 0;
                wait = 2500L;
            }
            case 5 ->
            {
                // Ставим и доводим позу: шаг Easy Place раз в 250 мс, пока клетка не совпадёт со схемой
                BlockState have = mc.level.getBlockState(SITTING);
                boolean posed = have.getBlock() instanceof CopperGolemStatueBlock
                        && have.getValue(CopperGolemStatueBlock.POSE) == CopperGolemStatueBlock.Pose.SITTING;

                if (! posed && this.attempts < 60)
                {
                    ++this.attempts;
                    aim(mc, SITTING);
                    WorldUtils.handleEasyPlace(mc);
                    this.nextAt = now + 250L;
                    return false;
                }

                this.check("Easy Place поставил медную статую из предмета в другой позе", is(have, COPPER_ID), String.valueOf(have));
                this.check("Easy Place довёл позу до схемы (сидит)", posed, have + ", шагов " + this.attempts);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                // Вторую клетку ставит сервер: та же медь, поза не та (стоит вместо «звезды»). Третью — другой окисленности
                server.execute(() -> {
                    command(server, set(TARGET, 2, 0, 0, "minecraft:copper_golem_statue[copper_golem_pose=standing,facing=south]"));
                    command(server, set(TARGET, 4, 0, 0, "minecraft:exposed_copper_golem_statue[copper_golem_pose=running,facing=south]"));
                });

                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                LayerCountManager.getInstance().markDirty();
                wait = 5000L;
            }
            case 6 ->
            {
                Map<String, int[]> list = this.list();
                int[] copper = list.get("copper_golem_statue");
                this.check("список: медные — обе построены (вторая стоит в другой позе)", copper != null && copper[0] == 2 && copper[1] == 0,
                        copper == null ? show(list) : "всего " + copper[0] + ", не хватает " + copper[1]);
                int[] waxed = list.get("waxed_oxidized_copper_golem_statue");
                this.check("список: вощёная окисленная — не построена (на её месте другая окисленность)", waxed != null && waxed[0] == 1 && waxed[1] == 1,
                        waxed == null ? show(list) : "всего " + waxed[0] + ", не хватает " + waxed[1]);
                var wrongState = LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.WRONG_STATE);
                var wrongBlock = LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.WRONG_BLOCK);
                this.check("Ctrl+B: статуя в другой позе — «не то состояние» (позу доводит клик)", wrongState.contains(STAR),
                        "не то состояние: " + wrongState + ", не тот блок: " + wrongBlock);
                this.check("Ctrl+B: другая окисленность — «не тот блок»", wrongBlock.contains(WAXED),
                        "не то состояние: " + wrongState + ", не тот блок: " + wrongBlock);
                shot(mc, "статуя в другой позе — построено");
            }
            default ->
            {
                CovOut.log("[statuepose] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private static void aim(Minecraft mc, BlockPos cell)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atLowerCornerOf(cell).add(0.5D, 0.5D, 0.5D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** Строки списка панели: предмет → {всего, не хватает, есть с собой}. */
    private Map<String, int[]> list()
    {
        Map<String, int[]> rows = new TreeMap<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            String key = BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).getPath();
            int[] row = rows.computeIfAbsent(key + (rows.containsKey(key) ? "#" + rows.size() : ""), k -> new int[3]);
            row[0] += entry.getCountTotal();
            row[1] += entry.getCountMissing();
            row[2] += entry.getCountAvailable();
        }

        return rows;
    }

    private void expect(Map<String, int[]> list, String when, String item, int total, int missing, int available)
    {
        int[] row = list.get(item);
        this.check(when + ": " + item + " — всего " + total + ", не хватает " + missing + ", с собой " + available,
                row != null && row[0] == total && row[1] == missing && row[2] == available,
                row == null ? "строки нет; список: " + show(list) : "всего " + row[0] + ", не хватает " + row[1] + ", с собой " + row[2]);
    }

    private static String show(Map<String, int[]> list)
    {
        StringBuilder text = new StringBuilder();

        for (Map.Entry<String, int[]> row : list.entrySet())
        {
            text.append(row.getKey()).append(' ').append(row.getValue()[0]).append('/').append(row.getValue()[1])
                    .append('/').append(row.getValue()[2]).append("; ");
        }

        return text.toString();
    }

    private static boolean is(BlockState state, String id)
    {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals(id);
    }

    private static boolean is(ItemStack stack, String id)
    {
        return ! stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(id);
    }

    private static int count(Inventory inventory, String id)
    {
        int count = 0;

        for (int i = 0; i < 36; ++i)
        {
            if (is(inventory.getItem(i), id))
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

    private static String set(BlockPos o, int x, int y, int z, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY() + y, o.getZ() + z, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[statuepose] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[statuepose] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[statuepose] команда не прошла (" + command + "): " + e);
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

        LayerListConfigs.HUD_ENABLED = this.hudWas;
        LayerListConfigs.ONLY_MISSING.setBooleanValue(this.onlyMissingWas);
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(this.shortfallWas);
        LayerListConfigs.EP_ENABLED.setBooleanValue(this.epWas);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(this.easyPlaceWas);
    }
}
