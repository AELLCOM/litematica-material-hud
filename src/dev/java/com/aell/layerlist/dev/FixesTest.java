package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.MaterialRow;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code fixes} (26.10.2026, просьбы игроков): нить и огонь через Easy Place, отключение любой строки «Материалов»
 * (жалоба: у «Светящихся ягод» нет «Отключить») и вид голограммы редстоуна. Схема снимается с площадки, пишется в файл
 * (отключения помнятся по файлу) и ставится размещением рядом, с тем же полом.
 * <ul>
 *   <li>Easy Place: круг 1 — выживание (нить, огниво, огненный заряд в хотбаре), круг 2 — творческий; нить на полу,
 *       огонь на незерраке, огонь душ на земле душ — тип блока должен совпасть;</li>
 *   <li>отключение: для каждой строки списка HUD — «можно отключить», после отключения все клетки, которые список считал
 *       этой строкой, в схеме пусты, «Сбросить всё» возвращает схему как была;</li>
 *   <li>снимки редстоуна: как есть, без заливки «нет блока», без оверлея вовсе.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class FixesTest implements CovJob
{
    private static final String T = "[fixes] ";
    private static final BlockPos REF = new BlockPos(900, 100, 0);
    private static final BlockPos TARGET = new BlockPos(900, 100, 20);
    /** Клетки схемы относительно начала, слой 0 — над полом; пол (y = −1) тоже в схеме. */
    private static final Object[][] CELLS = {
            {new BlockPos(1, 0, 1), "minecraft:tripwire"},
            {new BlockPos(2, 0, 1), "minecraft:tripwire"},
            {new BlockPos(3, 0, 1), "minecraft:tripwire"},
            {new BlockPos(1, -1, 3), "minecraft:netherrack"},
            {new BlockPos(1, 0, 3), "minecraft:fire"},
            {new BlockPos(4, -1, 3), "minecraft:soul_soil"},
            {new BlockPos(4, 0, 3), "minecraft:soul_fire"},
            {new BlockPos(1, 0, 5), "minecraft:redstone_wire"},
            {new BlockPos(2, 0, 5), "minecraft:redstone_wire"},
            {new BlockPos(3, 0, 5), "minecraft:redstone_wire"},
            {new BlockPos(4, 0, 5), "minecraft:redstone_wire"},
            {new BlockPos(3, 1, 5), "minecraft:stone"},
            {new BlockPos(3, 0, 6), "minecraft:redstone_wire"},
            {new BlockPos(3, 0, 7), "minecraft:redstone_wire"},
            {new BlockPos(1, 3, 8), "minecraft:stone"},
            {new BlockPos(1, 2, 8), "minecraft:cave_vines_plant[berries=true]"},
            {new BlockPos(1, 1, 8), "minecraft:cave_vines[berries=true]"},
            {new BlockPos(3, 0, 9), "minecraft:potted_poppy"},
            {new BlockPos(4, 0, 9), "minecraft:flower_pot"},
            {new BlockPos(5, 0, 9), "minecraft:water_cauldron[level=3]"},
            {new BlockPos(1, -1, 11), "minecraft:farmland"},
            {new BlockPos(1, 0, 11), "minecraft:wheat[age=7]"},
            {new BlockPos(3, -1, 11), "minecraft:grass_block"},
            {new BlockPos(3, 0, 11), "minecraft:sweet_berry_bush[age=2]"},
            {new BlockPos(5, 0, 11), "minecraft:candle_cake"},
            {new BlockPos(6, 0, 1), "minecraft:oak_stairs[waterlogged=true]"},
    };
    /** Что ставит Easy Place: нить и огонь. */
    private static final int[] PLACE = {0, 1, 2, 4, 6};
    private static final long PLACE_MS = 6000L;

    private int stage;
    private int index;
    private int round;
    private long nextAt;
    private long deadline;
    private long lastTick = Long.MIN_VALUE;
    private boolean failed;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;
    private final List<Item> rowItems = new ArrayList<>();
    /** Состояния клеток схемы до отключений: после «Сбросить всё» должно вернуться ровно это. */
    private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();

    @Override
    public String name()
    {
        return "fixes (нить и огонь Easy Place, отключение любой строки, голограмма редстоуна)";
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
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "time set noon");
            command(server, "gamerule doFireTick false");
            command(server, "gamerule fire_spread_radius_around_player 0");
            command(server, "forceload add 890 -10 920 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -2, -2, 9, -1, 13, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 5, 13, "minecraft:air"));
            }

            for (Object[] cell : CELLS)
            {
                command(server, set(REF, (BlockPos) cell[0], (String) cell[1]));
            }

            // Пол под схемой — как у образца: Easy Place проверяем на клетках над ним
            for (Object[] cell : CELLS)
            {
                BlockPos c = (BlockPos) cell[0];

                if (c.getY() < 0)
                {
                    command(server, set(TARGET, c, (String) cell[1]));
                }
            }

            command(server, "tp @a " + (REF.getX() + 3.5D) + " " + REF.getY() + " " + (REF.getZ() - 2.5D));
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
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                for (Object[] cell : CELLS)
                {
                    CovOut.log(T + "образец " + cell[0] + " " + cell[1] + " → " + mc.level.getBlockState(REF.offset((BlockPos) cell[0])));
                }

                AreaSelection area = new AreaSelection();
                area.setName("dev-fixes");
                area.addSubRegionBox(new Box(REF.offset(0, -1, 0), REF.offset(6, 3, 11), "main"), false);
                area.setExplicitOrigin(REF.offset(0, -1, 0));
                LitematicaSchematic made = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                Path dir = com.aell.layerlist.schematics.library.SchematicLibrary.get().refreshRoot().resolve("_f");

                try
                {
                    java.nio.file.Files.createDirectories(dir);
                }
                catch (java.io.IOException e)
                {
                    CovOut.log(T + "папка для схемы: " + e);
                }

                boolean written = made.writeToFile(dir, "dev-fixes", true);
                this.schematic = written ? SchematicHolder.getInstance().getOrLoad(dir.resolve("dev-fixes.litematic")) : null;
                this.check("схема записана в файл и загружена Litematica", this.schematic != null, String.valueOf(dir));

                if (this.schematic == null)
                {
                    this.stage = 99;
                    return false;
                }

                BlockReplacements.revertAll(this.schematic);
                this.placement = SchematicPlacement.createFor(this.schematic, TARGET.offset(0, -1, 0), "dev-fixes", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                LayerListConfigs.EP_DEBUG.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                wait = 4000L;
            }
            case 1 ->
            {
                // Голограмма редстоуна: как есть, без заливки «нет блока», без оверлея
                look(mc, server, TARGET.offset(3, 3, 3), TARGET.offset(3, 0, 6));
                wait = 2500L;
            }
            case 2 ->
            {
                // С опцией «без заливки» (по умолчанию) — узор пыли виден, у клеток только рамка
                this.check("опция «Редстоун, рельсы и нить без заливки» по умолчанию включена",
                        LayerListConfigs.GHOST_FLAT_NO_FILL.getBooleanValue(), "");
                shot(mc, "redstone-flat-no-fill");
                LayerListConfigs.GHOST_FLAT_NO_FILL.setBooleanValue(false);
                wait = 3000L;
            }
            case 3 ->
            {
                shot(mc, "redstone-litematica-fill");
                LayerListConfigs.GHOST_FLAT_NO_FILL.setBooleanValue(true);
                wait = 3000L;
            }
            case 4 ->
            {
                shot(mc, "redstone-flat-no-fill-again");
                this.collectRows();
                this.index = 0;
                wait = 500L;
            }
            case 5 ->
            {
                // Отключение: по строке за шаг
                if (this.index < this.rowItems.size())
                {
                    this.disableOne(this.rowItems.get(this.index++));
                    this.nextAt = now + 300L;
                    return false;
                }

                wait = 500L;
            }
            case 6 ->
            {
                // Меню строки «Светящиеся ягоды» во вкладке «Материалы»: пункт «Отключить» на месте (снимок)
                com.aell.layerlist.schematics.gui.GuiSchematics.openPlacements(null);
                wait = 1500L;
            }
            case 7 ->
            {
                try
                {
                    var gui = (com.aell.layerlist.schematics.gui.GuiSchematics) mc.screen;
                    java.lang.reflect.Method show = com.aell.layerlist.schematics.gui.GuiSchematics.class.getDeclaredMethod(
                            "showMaterials", com.aell.layerlist.schematics.library.SchematicEntry.class, SchematicPlacement.class);
                    show.setAccessible(true);
                    show.invoke(gui, null, this.placement);
                }
                catch (ReflectiveOperationException | ClassCastException e)
                {
                    this.check("вкладка «Материалы» открылась", false, String.valueOf(e));
                }

                wait = 4000L;
            }
            case 8 ->
            {
                var gui = mc.screen instanceof com.aell.layerlist.schematics.gui.GuiSchematics g ? g : null;
                Object pane = KProbe.fieldNamedType(gui, "MaterialsPane");
                var cell = KProbe.fieldOfType(pane, com.aell.layerlist.schematics.gui.materials.ReplaceCell.class);
                String key = com.aell.layerlist.schematics.materials.ItemKeys.of(new ItemStack(Items.GLOW_BERRIES));
                int[] box = cell == null ? null : cell.cellBox(key);
                this.check("строка «Светящиеся ягоды» видна в таблице", box != null, "ключ " + key);

                if (gui != null && box != null)
                {
                    var click = new net.minecraft.client.input.MouseButtonEvent(box[0] + box[2] / 3.0, box[1] + box[3] / 2.0,
                            new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT, 0));
                    gui.onMouseClicked(click, false);
                    gui.onMouseReleased(click);
                }

                wait = 1500L;
            }
            case 9 ->
            {
                shot(mc, "materials-glow-berries-menu");
                wait = 1000L;
            }
            case 10 ->
            {
                mc.setScreen(null);
                this.round = 1;
                this.index = 0;
                this.prepareRound(server);
                wait = 3000L;
            }
            case 11 ->
            {
                if (this.placeStep(mc, now))
                {
                    return false;
                }

                shot(mc, "fixes-r" + this.round + "-after");

                if (this.round == 1)
                {
                    this.round = 2;
                    this.index = 0;
                    this.prepareRound(server);
                    this.nextAt = now + 3000L;
                    return false;
                }

                wait = 500L;
            }
            default ->
            {
                CovOut.log(T + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Одна клетка Easy Place за вызов; {@code true} — ещё есть работа в этом круге. */
    private boolean placeStep(Minecraft mc, long now)
    {
        if (this.index >= PLACE.length)
        {
            return false;
        }

        Object[] cell = CELLS[PLACE[this.index]];
        BlockPos pos = TARGET.offset((BlockPos) cell[0]);
        BlockState want = schematicState(pos);
        BlockState have = mc.level.getBlockState(pos);
        boolean done = want != null && have.getBlock() == want.getBlock();

        if (this.deadline == 0L)
        {
            this.deadline = now + PLACE_MS;
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", pos.getX() + 0.5D,
                    pos.getY(), pos.getZ() - 1.5D)));
            ItemStack cache = want == null ? ItemStack.EMPTY
                    : MaterialCache.getInstance().getRequiredBuildItemForState(want, SchematicWorldHandler.getSchematicWorld(), pos);
            CovOut.log(T + "круг " + this.round + ": " + cell[1] + " — в схеме " + want + ", кэш материалов " + cache);
            this.nextAt = now + 600L;
            return true;
        }

        if (! done && now < this.deadline)
        {
            this.easyPlace(mc, pos);
            return true;
        }

        this.check("круг " + this.round + " (" + (this.round == 1 ? "выживание" : "творческий") + "): " + cell[1] + " встал", done,
                "в мире " + have + ", рука " + mc.player.getMainHandItem());
        this.deadline = 0L;
        ++this.index;
        this.nextAt = now + 300L;
        return true;
    }

    /** Строки списка — по клеткам схемы, тем же путём, что считает Litematica. */
    private void collectRows()
    {
        Set<Item> items = new LinkedHashSet<>();

        for (Object[] cell : CELLS)
        {
            BlockPos pos = TARGET.offset((BlockPos) cell[0]);
            BlockState state = this.stored(pos);
            this.before.put(pos, state);
            items.addAll(cellItems(state));
        }

        this.rowItems.addAll(items);
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(BuiltInRegistries.ITEM.getKey(item).getPath()));
        CovOut.log(T + "строки схемы: " + names);
    }

    private static Set<Item> cellItems(BlockState state)
    {
        Set<Item> items = new LinkedHashSet<>();

        if (state == null || state.isAir())
        {
            return items;
        }

        MaterialCache cache = MaterialCache.getInstance();

        if (cache.requiresMultipleItems(state))
        {
            cache.getItems(state).forEach(stack -> { if (! stack.isEmpty()) items.add(stack.getItem()); });
        }
        else
        {
            ItemStack stack = cache.getRequiredBuildItemForState(state);

            if (! stack.isEmpty())
            {
                items.add(stack.getItem());
            }
        }

        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
        {
            items.add(Items.WATER_BUCKET);
        }

        return items;
    }

    private void disableOne(Item item)
    {
        String name = BuiltInRegistries.ITEM.getKey(item).getPath();
        ItemStack stack = new ItemStack(item);
        MaterialRow row = new MaterialRow(stack, com.aell.layerlist.schematics.materials.ItemKeys.of(stack), stack.getHoverName().getString(),
                1L, 1L, 0L, 0L, stack.getMaxStackSize(), false);
        BlockReplacements.RowState state = BlockReplacements.rows(this.schematic, List.of(row), false).get(row.key());
        boolean can = state != null && state.disableable();
        this.check("строка «" + name + "»: в меню есть «Отключить»", can,
                state == null ? "нет состояния" : "замена " + state.refusal() + ", отключение " + state.off());

        if (! can)
        {
            return;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disable(this.schematic, item);
        this.check("строка «" + name + "»: отключилась", outcome.ok(), outcome.error());

        // Каждая клетка, которую список считал этой строкой, в схеме пуста (у блока «в воде» — только без воды)
        List<String> left = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> pair : this.before.entrySet())
        {
            if (! cellItems(pair.getValue()).contains(item))
            {
                continue;
            }

            BlockState now = this.stored(pair.getKey());
            boolean gone = now == null || now.isAir()
                    || item == Items.WATER_BUCKET && now.hasProperty(BlockStateProperties.WATERLOGGED)
                            && ! now.getValue(BlockStateProperties.WATERLOGGED);

            if (! gone)
            {
                left.add(pair.getKey().subtract(TARGET) + " " + now);
            }
        }

        this.check("строка «" + name + "»: все её клетки пропали из схемы", left.isEmpty(), left.toString());

        BlockReplacements.Outcome back = BlockReplacements.revertAll(this.schematic);
        List<String> wrong = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> pair : this.before.entrySet())
        {
            BlockState now = this.stored(pair.getKey());

            if (now != pair.getValue())
            {
                wrong.add(pair.getKey().subtract(TARGET) + " " + now + " ≠ " + pair.getValue());
            }
        }

        this.check("строка «" + name + "»: «Сбросить всё» вернуло схему", back.ok() && wrong.isEmpty(), back.error() + " " + wrong);
    }

    private void prepareRound(MinecraftServer server)
    {
        int round = this.round;
        server.execute(() -> {
            for (int i : PLACE)
            {
                command(server, set(TARGET, (BlockPos) CELLS[i][0], "minecraft:air"));
            }

            command(server, "clear @a");

            if (round == 1)
            {
                command(server, "gamemode survival @a");
                command(server, "effect give @a minecraft:saturation 600 1 true");
                command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                command(server, "item replace entity @a hotbar.1 with minecraft:string 16");
                command(server, "item replace entity @a hotbar.2 with minecraft:flint_and_steel");
                command(server, "item replace entity @a hotbar.3 with minecraft:fire_charge 4");
            }
            else
            {
                command(server, "gamemode creative @a");
            }
        });
    }

    /** Состояние в самой схеме (а не в мире голограмм: тот перестраивается с задержкой). */
    private BlockState stored(BlockPos pos)
    {
        BlockPos local = pos.subtract(TARGET.offset(0, -1, 0));
        var container = this.schematic.getSubRegionContainer("main");
        return container == null ? null : container.get(local.getX(), local.getY(), local.getZ());
    }

    private static BlockState schematicState(BlockPos pos)
    {
        Level schematic = SchematicWorldHandler.getSchematicWorld();
        return schematic != null ? schematic.getBlockState(pos) : null;
    }

    /** Взгляд в середину клетки и шаг Easy Place — раз в тик. */
    private void easyPlace(Minecraft mc, BlockPos cell)
    {
        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        aim(mc, Vec3.atCenterOf(cell).add(0.0D, -0.45D, 0.0D));
        WorldUtils.handleEasyPlace(mc);
    }

    private static void aim(Minecraft mc, Vec3 aim)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private static void look(Minecraft mc, MinecraftServer server, BlockPos from, BlockPos at)
    {
        server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f facing %.1f %.1f %.1f",
                from.getX() + 0.5D, from.getY(), from.getZ() + 0.5D, at.getX() + 0.5D, at.getY() + 0.1D, at.getZ() + 0.5D)));
    }

    private static String set(BlockPos origin, BlockPos c, String block)
    {
        BlockPos pos = origin.offset(c);
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(T + "снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(T + (ok ? "OK   " : "FAIL ") + what + (detail == null || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log(T + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (this.schematic != null)
        {
            BlockReplacements.revertAll(this.schematic);
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        LayerListConfigs.GHOST_FLAT_NO_FILL.setBooleanValue(true);
        LayerListConfigs.EP_DEBUG.setBooleanValue(false);
    }
}
