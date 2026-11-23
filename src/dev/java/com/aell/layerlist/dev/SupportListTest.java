package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.ghost.HiddenGhosts;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code supportlist} (19.11.2026): «Скрывать блоки без опоры» и список HUD. Правило владельца: блок, который
 * сейчас не поставить, скрыт полностью — и из списка панели; блок, который встанет после опоры из схемы, и сама опора
 * в списке есть.
 *
 * <p>Схема висит в воздухе (под ней пусто), чтобы пол площадки никого не держал:
 * <ul>
 *   <li>факел на булыжнике схемы; подвесная табличка под досками схемы; нажимная плита на кирпичах и ковёр на плите
 *       (цепочка); настенная подвесная табличка с блоком сбоку; тростник на песке у воды; пшеница над грядкой, на
 *       месте которой в мире земля; большой капельник на глине — всё это «после опоры», в списке есть;</li>
 *   <li>рычаг на стене, которой нет ни в мире, ни в схеме, настенная подвесная табличка без блоков сбоку и морковь
 *       без грядки (грядки нет ни в мире, ни в схеме, а рядом — посторонний блок схемы) — «никак», в списке нет;
 *       стену построили в мире — рычаг появился.</li>
 * </ul>
 * Одиночный слой: опора факела на соседнем слое — без «Опоры с соседнего слоя» факела и булыжника в списке нет, с ней
 * оба есть. Режим выключен — список прежний: в нём всё, опора вне слоя — всегда.
 */
@Environment(EnvType.CLIENT)
public final class SupportListTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(1100, 100, 0);
    private static final BlockPos TARGET = new BlockPos(1100, 100, 16);
    /** Схема начинается на две клетки выше пола площадки. */
    private static final int LIFT = 2;
    private static final int LAST_X = 18;

    private static final int[] TORCH = {0, 1, 1};
    private static final int[] COBBLE = {0, 0, 1};
    private static final int[] LEVER = {4, 1, 1};
    private static final int[] LEVER_WALL = {4, 1, 0};
    private static final int[] WALL_SIGN = {8, 1, 1};
    private static final int[] LONE_SIGN = {LAST_X, 1, 1};
    private static final int[] FARMLAND = {14, 0, 1};
    private static final int[] CLAY = {16, 0, 1};
    private static final int[] STEM = {16, 1, 1};

    /** Кадр под программным рендером снимается с опозданием в секунды: после снимка стенд ждёт и только потом меняет мир. */
    private static final long SHOT_HOLD_MS = 3500L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private Runnable after;
    private long afterWait;
    private long totalWhenOff;
    private SchematicPlacement placement;
    private boolean hideWas;
    private boolean fromLayersWas;
    private boolean onlyMissingWas;
    private boolean stockWas;
    private boolean inventoryOnlyWas;
    private boolean hudWas;

    @Override
    public String name()
    {
        return "supportlist (список HUD и блоки без опоры)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        try
        {
            Object zm = Class.forName("com.aell.zonefocus.zone.ZoneManager").getMethod("getInstance").invoke(null);
            zm.getClass().getMethod("clear").invoke(zm);
        }
        catch (ReflectiveOperationException | RuntimeException ignored)
        {
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        this.hideWas = LayerListConfigs.GHOST_HIDE_UNSUPPORTED.getBooleanValue();
        this.fromLayersWas = LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.getBooleanValue();
        this.onlyMissingWas = LayerListConfigs.ONLY_MISSING.getBooleanValue();
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.inventoryOnlyWas = LayerListConfigs.GHOST_INVENTORY_ONLY.getBooleanValue();
        this.hudWas = LayerListConfigs.HUD_ENABLED;
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.HUD_ENABLED = true;

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1090 -10 1130 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -4, LAST_X + 4, -1, 10, "minecraft:stone"));
                command(server, fill(o, -3, 0, -4, LAST_X + 4, 8, 10, "minecraft:air"));
            }

            // 1. Факел на булыжнике схемы
            command(server, set(REF, COBBLE, "minecraft:cobblestone"));
            command(server, set(REF, TORCH, "minecraft:torch"));
            // 2. Подвесная табличка под досками схемы
            command(server, set(REF, 2, 3, 1, "minecraft:oak_planks"));
            command(server, set(REF, 2, 2, 1, "minecraft:spruce_hanging_sign"));
            // 3. Рычаг на стене, которой в схеме нет: стена — на клетку севернее рамки схемы
            command(server, set(REF, LEVER_WALL, "minecraft:stone"));
            command(server, set(REF, LEVER, "minecraft:lever[face=wall,facing=south]"));
            // 4. Цепочка: кирпичи → нажимная плита → ковёр
            command(server, set(REF, 6, 0, 1, "minecraft:bricks"));
            command(server, set(REF, 6, 1, 1, "minecraft:stone_pressure_plate"));
            command(server, set(REF, 6, 2, 1, "minecraft:white_carpet"));
            // 5. Настенная подвесная табличка: с блоком сбоку (в схеме) и без блоков сбоку вовсе
            command(server, set(REF, 9, 1, 1, "minecraft:polished_andesite"));
            command(server, set(REF, WALL_SIGN, "minecraft:oak_wall_hanging_sign[facing=south]"));
            command(server, set(REF, LONE_SIGN, "minecraft:birch_wall_hanging_sign[facing=south]"));
            // 6. Тростник: песок под ним и вода рядом с песком (вода в чаше из гладкого камня; дно чаши и опора
            //    песка — под рамкой схемы)
            command(server, set(REF, 12, -1, 1, "minecraft:stone"));
            command(server, set(REF, 12, -1, 2, "minecraft:stone"));
            command(server, set(REF, 11, 0, 2, "minecraft:smooth_stone"));
            command(server, set(REF, 13, 0, 2, "minecraft:smooth_stone"));
            command(server, set(REF, 12, 0, 3, "minecraft:smooth_stone"));
            command(server, set(REF, 12, 0, 1, "minecraft:sand"));
            command(server, set(REF, 12, 0, 2, "minecraft:water"));
            command(server, set(REF, 12, 1, 1, "minecraft:sugar_cane"));
            // 7. Пшеница на грядке
            command(server, set(REF, FARMLAND, "minecraft:farmland[moisture=7]"));
            command(server, set(REF, 14, 1, 1, "minecraft:wheat[age=7]"));
            // 8. Большой капельник на глине: стебель и лист держат друг друга
            command(server, set(REF, CLAY, "minecraft:clay"));
            command(server, set(REF, STEM, "minecraft:big_dripleaf_stem[facing=south]"));
            command(server, set(REF, 16, 2, 1, "minecraft:big_dripleaf[facing=south]"));
            // 9. Морковь без грядки в схеме: грядка образца — под рамкой. Рядом стекло схемы: посторонний блок
            //    опорой не считается (19.11.2026: проверка света в срезе мира выдавала такую морковь за «после опоры»)
            command(server, set(REF, 10, -1, 2, "minecraft:farmland[moisture=7]"));
            command(server, set(REF, 10, 0, 2, "minecraft:carrots[age=7]"));
            command(server, set(REF, 10, 0, 3, "minecraft:glass"));
            // В мире на месте грядки — земля: вспахать её можно, значит пшеница «после опоры»
            command(server, set(TARGET, FARMLAND, "minecraft:dirt"));
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", REF.getX() + 9.5D, REF.getY() + LIFT, REF.getZ() + 6.5D));
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
        long wait = 3000L;

        if (this.after != null)
        {
            Runnable action = this.after;
            this.after = null;
            action.run();
            this.nextAt = now + this.afterWait;
            return false;
        }

        switch (this.stage)
        {
            case 0 ->
            {
                this.check("образец цел: тростник, пшеница, капельник, рычаг на месте",
                        ! mc.level.getBlockState(at(REF, 12, 1, 1)).isAir() && ! mc.level.getBlockState(at(REF, 14, 1, 1)).isAir()
                                && ! mc.level.getBlockState(at(REF, STEM)).isAir() && ! mc.level.getBlockState(at(REF, LEVER)).isAir(),
                        mc.level.getBlockState(at(REF, 12, 1, 1)) + " " + mc.level.getBlockState(at(REF, 14, 1, 1)) + " "
                                + mc.level.getBlockState(at(REF, STEM)) + " " + mc.level.getBlockState(at(REF, LEVER)));
                AreaSelection area = new AreaSelection();
                area.setName("dev-supportlist");
                // Стена рычага (z = 0) и опоры под песком и водой (y = −1) в рамку не входят
                area.addSubRegionBox(new Box(at(REF, 0, 0, 1), at(REF, LAST_X, 3, 3), "main"), false);
                area.setExplicitOrigin(at(REF, 0, 0, 1));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, at(TARGET, 0, 0, 1), "dev-supportlist", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 5",
                        TARGET.getX() + 9.5D, TARGET.getY() + LIFT, TARGET.getZ() + 9.5D)));
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                this.recount();
                wait = 5000L;
            }
            case 1 ->
            {
                // Режим выключен: список прежний — в нём всё
                Map<String, int[]> list = this.list();
                this.expect(list, "режим выключен", "torch", 1, 1);
                this.expect(list, "режим выключен", "cobblestone", 1, 1);
                this.expect(list, "режим выключен", "lever", 1, 1);
                this.expect(list, "режим выключен", "birch_hanging_sign", 1, 1);
                this.expect(list, "режим выключен", "oak_hanging_sign", 1, 1);
                this.expect(list, "режим выключен", "big_dripleaf", 2, 2);
                this.expect(list, "режим выключен", "carrot", 1, 1);
                this.check("режим выключен: скрытых нет", LayerCountManager.getInstance().getBlocksHidden() == 0L,
                        "скрыто " + LayerCountManager.getInstance().getBlocksHidden());
                this.totalWhenOff = LayerCountManager.getInstance().getBlocksTotal();
                shot(mc, "режим выключен");
                this.then(() -> LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(true), 3500L);
            }
            case 2 ->
            {
                Map<String, int[]> list = this.list();
                // «После опоры»: блок и опора в списке
                this.expect(list, "режим включён", "torch", 1, 1);
                this.expect(list, "режим включён", "cobblestone", 1, 1);
                this.expect(list, "режим включён", "spruce_hanging_sign", 1, 1);
                this.expect(list, "режим включён", "oak_planks", 1, 1);
                this.expect(list, "режим включён", "stone_pressure_plate", 1, 1);
                this.expect(list, "режим включён", "white_carpet", 1, 1);
                this.expect(list, "режим включён", "bricks", 1, 1);
                this.expect(list, "режим включён", "oak_hanging_sign", 1, 1);
                this.expect(list, "режим включён", "polished_andesite", 1, 1);
                this.expect(list, "режим включён", "sugar_cane", 1, 1);
                this.expect(list, "режим включён", "sand", 1, 1);
                this.expect(list, "режим включён", "wheat_seeds", 1, 1);
                this.expect(list, "режим включён", "big_dripleaf", 2, 2);
                this.expect(list, "режим включён", "clay", 1, 1);
                // «Никак»: в списке нет
                this.absent(list, "режим включён", "lever");
                this.absent(list, "режим включён", "birch_hanging_sign");
                this.absent(list, "режим включён", "carrot");
                this.expect(list, "режим включён", "glass", 1, 1);
                LayerCountManager hud = LayerCountManager.getInstance();
                this.check("режим включён: скрыто три клетки", hud.getBlocksHidden() == 3L, "скрыто " + hud.getBlocksHidden());
                // Панель считает без скрытых, аддонам отдаётся вся постройка: «всего» и «не хватает» больше на скрытые,
                // а построенное (всего − не хватает) — то же самое число
                long apiTotal = com.aell.layerlist.api.LayerListApi.getBlocksTotal();
                long apiMissing = com.aell.layerlist.api.LayerListApi.getBlocksMissing();
                this.check("аддонам недостроенное видно: по API не хватает на 3 больше, построено столько же",
                        apiMissing == hud.getBlocksMissing() + 3L && apiTotal == hud.getBlocksTotal() + 3L
                                && this.totalWhenOff == apiTotal,
                        "API " + apiTotal + "/" + apiMissing + ", панель " + hud.getBlocksTotal() + "/" + hud.getBlocksMissing()
                                + ", без режима всего " + this.totalWhenOff);
                // Голограммы: скрыты и «после опоры», и «никак»; опора видна
                this.check("голограммы: факел, рычаг и таблички без опоры скрыты", HiddenGhosts.hidden(at(TARGET, TORCH))
                        && HiddenGhosts.hidden(at(TARGET, LEVER)) && HiddenGhosts.hidden(at(TARGET, WALL_SIGN))
                        && HiddenGhosts.hidden(at(TARGET, LONE_SIGN)) && HiddenGhosts.hidden(at(TARGET, STEM)),
                        "факел " + HiddenGhosts.hidden(at(TARGET, TORCH)) + ", рычаг " + HiddenGhosts.hidden(at(TARGET, LEVER))
                                + ", табличка с опорой в схеме " + HiddenGhosts.hidden(at(TARGET, WALL_SIGN))
                                + ", табличка без опоры " + HiddenGhosts.hidden(at(TARGET, LONE_SIGN))
                                + ", стебель " + HiddenGhosts.hidden(at(TARGET, STEM)));
                this.check("голограммы: булыжник и глина видны", ! HiddenGhosts.hidden(at(TARGET, COBBLE))
                        && ! HiddenGhosts.hidden(at(TARGET, CLAY)), "");
                shot(mc, "режим включён");
                // Стену рычага строим в мире — рычаг должен появиться сам, без таймера
                this.then(() -> server.execute(() -> command(server, set(TARGET, LEVER_WALL, "minecraft:stone"))), 2500L);
            }
            case 3 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "стена в мире", "lever", 1, 1);
                this.absent(list, "стена в мире", "birch_hanging_sign");
                this.absent(list, "стена в мире", "carrot");
                this.check("стена в мире: скрыто две клетки", LayerCountManager.getInstance().getBlocksHidden() == 2L,
                        "скрыто " + LayerCountManager.getInstance().getBlocksHidden());
                this.check("стена в мире: голограмма рычага видна", ! HiddenGhosts.hidden(at(TARGET, LEVER)), "");
                // Опоры поставлены — блоки на них ставятся сейчас
                server.execute(() -> {
                    command(server, set(TARGET, COBBLE, "minecraft:cobblestone"));
                    command(server, set(TARGET, CLAY, "minecraft:clay"));
                });
                wait = 2500L;
            }
            case 4 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "опоры в мире", "torch", 1, 1);
                this.expect(list, "опоры в мире", "cobblestone", 1, 0);
                this.expect(list, "опоры в мире", "big_dripleaf", 2, 2);
                this.check("опоры в мире: голограммы факела и капельника видны", ! HiddenGhosts.hidden(at(TARGET, TORCH))
                        && ! HiddenGhosts.hidden(at(TARGET, STEM)) && ! HiddenGhosts.hidden(at(TARGET, 16, 2, 1)),
                        "факел " + HiddenGhosts.hidden(at(TARGET, TORCH)) + ", стебель " + HiddenGhosts.hidden(at(TARGET, STEM))
                                + ", лист " + HiddenGhosts.hidden(at(TARGET, 16, 2, 1)));
                shot(mc, "опоры поставлены");
                // Одиночный слой с факелом; его опора (булыжник) — слоем ниже. Булыжник убираем: опоры в мире нет
                this.then(() ->
                {
                    server.execute(() -> command(server, set(TARGET, COBBLE, "minecraft:air")));
                    this.layer(1);
                    this.recount();
                }, 4500L);
            }
            case 5 ->
            {
                Map<String, int[]> list = this.list();
                this.absent(list, "слой, опора соседнего слоя выключена", "torch");
                this.absent(list, "слой, опора соседнего слоя выключена", "cobblestone");
                this.absent(list, "слой, опора соседнего слоя выключена", "stone_pressure_plate");
                // Опора в том же слое — блок «после опоры» и в слое
                this.expect(list, "слой, опора соседнего слоя выключена", "oak_hanging_sign", 1, 1);
                this.expect(list, "слой, опора соседнего слоя выключена", "polished_andesite", 1, 1);
                this.expect(list, "слой, опора соседнего слоя выключена", "lever", 1, 1);
                shot(mc, "слой без опоры соседнего слоя");
                this.then(() -> LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(true), 4500L);
            }
            case 6 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "слой, опора соседнего слоя включена", "torch", 1, 1);
                this.expect(list, "слой, опора соседнего слоя включена", "cobblestone", 1, 1);
                this.expect(list, "слой, опора соседнего слоя включена", "stone_pressure_plate", 1, 1);
                this.expect(list, "слой, опора соседнего слоя включена", "bricks", 1, 1);
                this.absent(list, "слой, опора соседнего слоя включена", "birch_hanging_sign");
                shot(mc, "слой с опорой соседнего слоя");
                // Режим выключен — как с 13.10.2026: блок слоя и его опора вне слоя в списке всегда
                this.then(() ->
                {
                    LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
                    LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
                }, 4500L);
            }
            case 7 ->
            {
                Map<String, int[]> list = this.list();
                this.expect(list, "слой, режим выключен", "torch", 1, 1);
                this.expect(list, "слой, режим выключен", "cobblestone", 1, 1);
                this.expect(list, "слой, режим выключен", "birch_hanging_sign", 1, 1);
                this.check("слой, режим выключен: скрытых нет", LayerCountManager.getInstance().getBlocksHidden() == 0L,
                        "скрыто " + LayerCountManager.getInstance().getBlocksHidden());
            }
            default ->
            {
                CovOut.log("[supportlist] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + (this.after != null ? SHOT_HOLD_MS : wait);
        ++this.stage;
        return false;
    }

    private void recount()
    {
        LayerCountManager.getInstance().markDirty();
    }

    /** Сначала дать снимку сняться ({@link #SHOT_HOLD_MS}), потом сделать {@code action} и ждать {@code wait} до проверки. */
    private void then(Runnable action, long wait)
    {
        this.after = action;
        this.afterWait = wait;
    }

    private void layer(int dy)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(LayerMode.SINGLE_LAYER);
        range.setAxis(Direction.Axis.Y);
        range.setLayerSingle(TARGET.getY() + LIFT + dy);
    }

    /** Строки списка панели: предмет → {всего, не хватает}. */
    private Map<String, int[]> list()
    {
        Map<String, int[]> rows = new TreeMap<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            String key = BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).getPath();
            int[] row = rows.computeIfAbsent(key, k -> new int[2]);
            row[0] += entry.getCountTotal();
            row[1] += entry.getCountMissing();
        }

        return rows;
    }

    private void expect(Map<String, int[]> list, String when, String item, int total, int missing)
    {
        int[] row = list.get(item);
        this.check(when + ": " + item + " — всего " + total + ", не хватает " + missing,
                row != null && row[0] == total && row[1] == missing,
                row == null ? "строки нет; список: " + show(list) : "всего " + row[0] + ", не хватает " + row[1]);
    }

    private void absent(Map<String, int[]> list, String when, String item)
    {
        int[] row = list.get(item);
        this.check(when + ": " + item + " — в списке нет", row == null,
                row == null ? "" : "всего " + row[0] + ", не хватает " + row[1]);
    }

    private static String show(Map<String, int[]> list)
    {
        StringBuilder text = new StringBuilder();

        for (Map.Entry<String, int[]> row : list.entrySet())
        {
            text.append(row.getKey()).append(' ').append(row.getValue()[0]).append('/').append(row.getValue()[1]).append("; ");
        }

        return text.toString();
    }

    private static BlockPos at(BlockPos origin, int... cell)
    {
        return origin.offset(cell[0], cell[1] + LIFT, cell[2]);
    }

    private static String set(BlockPos origin, int[] cell, String block)
    {
        return set(origin, cell[0], cell[1], cell[2], block);
    }

    private static String set(BlockPos origin, int x, int y, int z, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", origin.getX() + x, origin.getY() + LIFT + y,
                origin.getZ() + z, block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atCenterOf(at(TARGET, 9, 1, 1));
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        CovOut.log("[supportlist] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[supportlist] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[supportlist] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(this.hideWas);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(this.fromLayersWas);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(this.inventoryOnlyWas);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(this.onlyMissingWas);
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);
        LayerListConfigs.HUD_ENABLED = this.hudWas;
    }
}
