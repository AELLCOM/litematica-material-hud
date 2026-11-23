package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.materials.Availability;
import com.aell.layerlist.schematics.place.PlacementGroups;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.TextInput;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code issues} (23.11.2026): пять заявок GitHub Material HUD разом.
 * <ol>
 *   <li>#6 — переименование размещения: «Переименовать…» в «Размещениях», набор имени, Enter.</li>
 *   <li>#5 — группа «Мост» из двух размещений: заголовок над ними, сворачивание щелчком по стрелке, «Материалы
 *   группы» — счёт двух размещений вместе.</li>
 *   <li>#4 — «Склад только рядом»: сундук рядом в счёте, сундук за 120 блоков — нет.</li>
 *   <li>#2 — Chest Tracker (если стоит в run/mods): свои записи забыты — склад всё равно видит сундуки из его памяти;
 *   опция выключена — не видит.</li>
 *   <li>#3 — ткацкий станок: в панели узорный флаг схемы — у станка карточка с шагами; жёлтый флаг в слоте станка —
 *   первый шаг сделан.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class IssuesTest implements CovJob
{
    private static final String TAG = "[issues] ";
    private static final int SCANCODE_MASK = 1 << 30;
    private static final BlockPos REF = new BlockPos(1600, 100, 0);
    private static final BlockPos BANNER_REF = new BlockPos(1610, 100, 0);
    private static final BlockPos P1 = new BlockPos(1600, 100, 14);
    private static final BlockPos P2 = new BlockPos(1606, 100, 14);
    private static final BlockPos P3 = new BlockPos(1612, 100, 14);
    private static final BlockPos PB = new BlockPos(1618, 100, 14);
    private static final BlockPos NEAR = new BlockPos(1602, 100, 24);
    private static final BlockPos FAR = new BlockPos(1602, 100, 144);
    private static final BlockPos LOOM = new BlockPos(1604, 100, 24);
    private static final String STONE = "minecraft:stone";
    private static final String BANNER = "[{pattern:\"minecraft:stripe_top\",color:\"red\"},{pattern:\"minecraft:creeper\",color:\"black\"}]";
    private static final String NEW_NAME = "Съезд моста";
    private static final String GROUP = "Мост";

    private int stage;
    private long nextAt;
    private boolean failed;
    private final List<SchematicPlacement> placements = new ArrayList<>();
    private @Nullable PlacementGroups.Group group;

    @Override
    public String name()
    {
        return "issues (заявки GitHub #2–#6)";
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
        forgetChests(mc);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(true);
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
        LayerListConfigs.STOCK_NEAR_ONLY.setBooleanValue(false);
        LayerListConfigs.STOCK_RADIUS.setIntegerValue(64);
        LayerListConfigs.STOCK_CHEST_TRACKER.setBooleanValue(true);
        LayerListConfigs.LOOM_GUIDE.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1590 -10 1640 160");
            command(server, fill(REF, -3, -1, -3, 30, -1, 30, "minecraft:stone"));
            command(server, fill(REF, -3, 0, -3, 30, 6, 30, "minecraft:air"));
            command(server, fill(FAR, -2, -1, -2, 2, -1, 2, "minecraft:stone"));
            command(server, fill(FAR, -2, 0, -2, 2, 3, 2, "minecraft:air"));
            command(server, fill(REF, 0, 0, 0, 2, 0, 0, STONE));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:yellow_banner[rotation=0]{patterns:%s}",
                    BANNER_REF.getX(), BANNER_REF.getY(), BANNER_REF.getZ(), BANNER));
            command(server, "tp @a " + (REF.getX() + 3.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3500L;
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
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                LitematicaSchematic stone = schematic(mc, REF, REF.offset(2, 0, 0), "dev-issues-stone");
                LitematicaSchematic banner = schematic(mc, BANNER_REF, BANNER_REF, "dev-issues-banner");

                for (BlockPos at : new BlockPos[]{P1, P2, P3})
                {
                    this.place(stone, at, "Пролёт " + (this.placements.size() + 1));
                }

                this.place(banner, PB, "Флаг");
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> command(server, "tp @a " + (P1.getX() + 3.5D) + " " + P1.getY() + " " + (NEAR.getZ() + 2.5D) + " 180 30"));
                GuiSchematics.openPlacements(null);
                wait = 2500L;
            }
            // ===== #6: переименование размещения =====
            case 1 ->
            {
                Object pane = this.pane(mc);
                Table<?> table = KTrash.field(pane, "table");
                Object row = this.rowOf(table, this.placements.get(0));
                this.check("#6: строка размещения в таблице", row != null, "");

                if (row != null)
                {
                    select(table, row);

                    try
                    {
                        // То же, что пункт «Переименовать…» меню ПКМ строки
                        KProbe.call(pane, "rename", this.placements.get(0));
                    }
                    catch (ReflectiveOperationException e)
                    {
                        this.check("#6: пункт «Переименовать…»", false, e.toString());
                    }
                }

                wait = 800L;
            }
            case 2 ->
            {
                Modal modal = this.topModal(mc);
                this.check("#6: «Переименовать…» открыло окно", modal != null, "");

                if (modal == null)
                {
                    break;
                }

                type(NEW_NAME);
                TextInput field = nameField(modal);
                this.check("#6: в поле набранное имя", field != null && NEW_NAME.equals(field.text()),
                        field == null ? "нет поля" : field.text());
                shot(mc, "issues-1-rename");
                realKey(InputConstants.KEY_RETURN, 0);
                wait = 800L;
            }
            case 3 ->
            {
                this.check("#6: окно закрылось", this.topModal(mc) == null, "");
                this.check("#6: имя размещения сменилось", NEW_NAME.equals(this.placements.get(0).getName()),
                        this.placements.get(0).getName());
                shot(mc, "issues-2-renamed");
                // ===== #5: группа из первых двух =====
                this.group = PlacementGroups.get().add(GROUP, List.of(this.placements.get(0), this.placements.get(1)));
                wait = 1500L;
            }
            case 4 ->
            {
                List<String> rows = this.rowNames(mc);
                CovOut.log(TAG + "строки после группы: " + rows);
                this.check("#5: заголовок группы первым, под ним её два размещения",
                        rows.size() >= 3 && rows.get(0).equals("[" + GROUP + "]")
                                && java.util.Set.of(rows.get(1), rows.get(2)).equals(java.util.Set.of(NEW_NAME, "Пролёт 2")),
                        rows.toString());
                this.check("#5: размещение вне группы — ниже", rows.indexOf("Пролёт 3") > 2, rows.toString());
                shot(mc, "issues-3-group");
                // Щелчок по стрелке заголовка — свернуть
                Object pane = this.pane(mc);
                Table<?> table = KTrash.field(pane, "table");
                ScrollList<?> body = table.body();
                KProbe.click(this.gui(mc), body.x() + 6, body.y() + com.aell.layerlist.shaded.ui.Ui.ROW_H / 2.0);
                wait = 1000L;
            }
            case 5 ->
            {
                List<String> rows = this.rowNames(mc);
                CovOut.log(TAG + "строки после щелчка по стрелке: " + rows);
                this.check("#5: группа свернулась", this.group != null && this.group.collapsed(), "");
                this.check("#5: свёрнутая — только заголовок", ! rows.contains(NEW_NAME) && ! rows.contains("Пролёт 2")
                        && rows.contains("Пролёт 3"), rows.toString());
                shot(mc, "issues-4-collapsed");

                try
                {
                    KProbe.call(this.gui(mc), "showGroupMaterials", this.group);
                }
                catch (ReflectiveOperationException e)
                {
                    this.check("#5: «Материалы группы» открылись", false, e.toString());
                }

                wait = 5000L;
            }
            case 6 ->
            {
                Object materials = KProbe.fieldNamedType(this.gui(mc), "MaterialsPane");
                long stone = 0L;
                List<?> lines = KTrash.field(materials, "lines");

                if (lines != null)
                {
                    for (Object line : lines)
                    {
                        MaterialLine l = (MaterialLine) line;

                        if (StorageIndex.itemId(l.stack()).equals(STONE))
                        {
                            stone = l.row().total();
                        }
                    }
                }

                String label = "";

                try
                {
                    label = String.valueOf(KProbe.call(materials, "sourceLabel"));
                }
                catch (ReflectiveOperationException e)
                {
                    label = e.toString();
                }

                CovOut.log(TAG + "материалы группы: «" + label + "», камня всего " + stone);
                this.check("#5: источник таблицы — группа", label.contains(GROUP), label);
                this.check("#5: камень двух размещений вместе = 6", stone == 6L, "" + stone);
                shot(mc, "issues-5-group-materials");
                mc.gui.setScreen(null);
                // ===== #4: сундуки рядом и далеко =====
                server.execute(() -> {
                    command(server, chest(NEAR, "{Slot:0b,id:\"" + STONE + "\",count:2}"));
                    command(server, chest(FAR, "{Slot:0b,id:\"" + STONE + "\",count:5}"));
                    command(server, "gamemode survival @a");
                });
                wait = 1500L;
            }
            case 7 ->
            {
                this.open(mc, NEAR);
                wait = 1500L;
            }
            case 8 ->
            {
                mc.player.closeContainer();
                server.execute(() -> command(server, "tp @a " + (FAR.getX() + 0.5D) + " " + FAR.getY() + " " + (FAR.getZ() + 2.5D) + " 180 30"));
                wait = 2500L;
            }
            case 9 ->
            {
                this.open(mc, FAR);
                wait = 1500L;
            }
            case 10 ->
            {
                mc.player.closeContainer();
                server.execute(() -> command(server, "tp @a " + (P1.getX() + 3.5D) + " " + P1.getY() + " " + (NEAR.getZ() + 2.5D) + " 180 30"));
                wait = 2500L;
            }
            case 11 ->
            {
                ItemStack stone = new ItemStack(Items.STONE);
                long all = Availability.capture().inChests(stone);
                LayerListConfigs.STOCK_NEAR_ONLY.setBooleanValue(true);
                long near = Availability.capture().inChests(stone);
                LayerListConfigs.STOCK_RADIUS.setIntegerValue(200);
                long wide = Availability.capture().inChests(stone);
                LayerListConfigs.STOCK_RADIUS.setIntegerValue(64);
                CovOut.log(TAG + "склад: все " + all + ", рядом (64) " + near + ", рядом (200) " + wide);
                this.check("#4: без опции — оба сундука (2 + 5)", all == 7L, "" + all);
                this.check("#4: «Склад только рядом», 64 — только ближний", near == 2L, "" + near);
                this.check("#4: радиус 200 — снова оба", wide == 7L, "" + wide);
                LayerListConfigs.STOCK_NEAR_ONLY.setBooleanValue(false);
                // ===== #2: Chest Tracker =====
                boolean tracker = FabricLoader.getInstance().isModLoaded("chesttracker");
                CovOut.log(TAG + "Chest Tracker " + (tracker ? "стоит" : "не стоит — шаги #2 пропущены"));

                if (tracker)
                {
                    forgetChests(mc);
                }

                wait = 3000L;
            }
            case 12 ->
            {
                if (FabricLoader.getInstance().isModLoaded("chesttracker"))
                {
                    ItemStack stone = new ItemStack(Items.STONE);
                    long own = StorageIndex.getInstance().recordsInScope(StorageIndex.scope(mc)).size();
                    long withTracker = Availability.capture().inChests(stone);
                    CovOut.log(TAG + "свои записи забыты; записей в области (с Chest Tracker) " + own + ", камня " + withTracker
                            + ", Chest Tracker дал хранилищ " + com.aell.layerlist.compat.ChestTracker.count());
                    this.check("#2: склад видит оба сундука из памяти Chest Tracker", withTracker == 7L, "" + withTracker);
                    LayerListConfigs.STOCK_CHEST_TRACKER.setBooleanValue(false);
                    long without = Availability.capture().inChests(stone);
                    this.check("#2: опция выключена — из Chest Tracker ничего", without == 0L, "" + without);
                    LayerListConfigs.STOCK_CHEST_TRACKER.setBooleanValue(true);
                }

                // ===== #3: ткацкий станок =====
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "give @a minecraft:yellow_banner 1");
                    command(server, "give @a minecraft:red_dye 1");
                    command(server, "give @a minecraft:black_dye 1");
                    command(server, "give @a minecraft:creeper_banner_pattern 1");
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:loom", LOOM.getX(), LOOM.getY(), LOOM.getZ()));
                });
                LayerCountManager.getInstance().markDirty();
                wait = 3000L;
            }
            case 13 ->
            {
                this.open(mc, LOOM);
                wait = 1500L;
            }
            case 14 ->
            {
                List<?> recipes = this.recipes();
                CovOut.log(TAG + "экран " + (mc.gui.screen() == null ? "нет" : mc.gui.screen().getClass().getSimpleName())
                        + ", рецептов флагов " + recipes.size());
                this.check("#3: открыт ткацкий станок", mc.gui.screen() instanceof LoomScreen, "");
                this.check("#3: в панели узорный флаг — один рецепт", recipes.size() == 1, "" + recipes.size());
                this.check("#3: крипер требует предмет-узор", this.creeperNeedsItem(), "");
                shot(mc, "issues-6-loom");

                if (mc.gui.screen() instanceof LoomScreen loom)
                {
                    for (Slot slot : loom.getMenu().slots)
                    {
                        if (slot.getItem().getItem() instanceof net.minecraft.world.item.BannerItem && slot.container == mc.player.getInventory())
                        {
                            mc.gameMode.handleContainerInput(loom.getMenu().containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                            break;
                        }
                    }
                }

                wait = 1500L;
            }
            case 15 ->
            {
                int done = -1;

                if (mc.gui.screen() instanceof LoomScreen loom && ! this.recipes().isEmpty())
                {
                    done = this.done(this.recipes().get(0), loom.getMenu().getBannerSlot().getItem());
                }

                this.check("#3: жёлтый флаг в станке — первый шаг сделан", done == 1, "" + done);
                shot(mc, "issues-7-loom-banner");

                if (mc.gui.screen() != null)
                {
                    mc.player.closeContainer();
                }

                wait = 800L;
            }
            default ->
            {
                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    // ===== Схемы и размещения =====

    private static LitematicaSchematic schematic(Minecraft mc, BlockPos from, BlockPos to, String name)
    {
        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(from, to, "main"), false);
        area.setExplicitOrigin(from);
        return LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                "dev", CovOut::log);
    }

    private void place(LitematicaSchematic schematic, BlockPos at, String name)
    {
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, at, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        this.placements.add(placement);
    }

    // ===== Экран «Схемы» =====

    private @Nullable GuiSchematics gui(Minecraft mc)
    {
        return mc.gui.screen() instanceof GuiSchematics gui ? gui : null;
    }

    private @Nullable Object pane(Minecraft mc)
    {
        return KProbe.fieldNamedType(this.gui(mc), "PlacementsPane");
    }

    private @Nullable Modal topModal(Minecraft mc)
    {
        Layers layers = KProbe.fieldOfType(this.gui(mc), Layers.class);
        return layers != null && layers.top() instanceof Modal modal ? modal : null;
    }

    private static @Nullable TextInput nameField(@Nullable Modal modal)
    {
        List<?> rows = KTrash.field(modal, "rows");

        if (rows != null)
        {
            for (Object row : rows)
            {
                if (KProbe.recordComponent(row, com.aell.layerlist.ui.kit.Widget.class) instanceof TextInput input)
                {
                    return input;
                }
            }
        }

        return null;
    }

    private @Nullable Object rowOf(@Nullable Table<?> table, SchematicPlacement placement)
    {
        if (table == null)
        {
            return null;
        }

        for (Object row : table.body().items())
        {
            if (KProbe.recordComponent(row, SchematicPlacement.class) == placement)
            {
                return row;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static void select(Table<?> table, Object row)
    {
        ((Table<Object>) table).setSelected(row);
    }

    /** Строки таблицы: имя размещения или «[имя группы]» у заголовка. */
    private List<String> rowNames(Minecraft mc)
    {
        List<String> out = new ArrayList<>();
        Table<?> table = KTrash.field(this.pane(mc), "table");

        if (table != null)
        {
            for (Object row : table.body().items())
            {
                Object placement = KProbe.recordComponent(row, SchematicPlacement.class);
                Object group = KProbe.recordComponent(row, PlacementGroups.Group.class);
                out.add(placement instanceof SchematicPlacement p ? p.getName()
                        : group instanceof PlacementGroups.Group g ? "[" + g.name() + "]" : "?");
            }
        }

        return out;
    }

    // ===== Станок =====

    private List<?> recipes()
    {
        try
        {
            Method method = com.aell.layerlist.hud.LoomGuide.class.getDeclaredMethod("recipes", List.class);
            method.setAccessible(true);
            List<MaterialListEntry> entries = LayerCountManager.getInstance().getEntries();
            return (List<?>) method.invoke(null, entries);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "recipes: " + e);
            return List.of();
        }
    }

    private int done(Object recipe, ItemStack inLoom)
    {
        try
        {
            Method method = com.aell.layerlist.hud.LoomGuide.class.getDeclaredMethod("done", recipe.getClass(), ItemStack.class);
            method.setAccessible(true);
            return (int) method.invoke(null, recipe, inLoom);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "done: " + e);
            return -1;
        }
    }

    private boolean creeperNeedsItem()
    {
        List<?> recipes = this.recipes();

        if (recipes.isEmpty())
        {
            return false;
        }

        try
        {
            Method accessor = recipes.get(0).getClass().getDeclaredMethod("layers");
            accessor.setAccessible(true);
            List<?> layers = (List<?>) accessor.invoke(recipes.get(0));
            Object creeper = layers.get(1);
            Object holder = creeper.getClass().getMethod("pattern").invoke(creeper);
            Method method = com.aell.layerlist.hud.LoomGuide.class.getDeclaredMethod("patternItem", net.minecraft.core.Holder.class);
            method.setAccessible(true);
            Object item = method.invoke(null, holder);
            CovOut.log(TAG + "узор крипера требует: " + item);
            return item == Items.CREEPER_BANNER_PATTERN;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log(TAG + "patternItem: " + e);
            return false;
        }
    }

    // ===== Ввод и мир =====

    private static long handle()
    {
        return Minecraft.getInstance().getWindow().handle();
    }

    private static void realKey(int scancode, int mods)
    {
        int keycode = switch (scancode)
        {
            case InputConstants.KEY_RETURN -> '\r';
            case InputConstants.KEY_ESCAPE -> 0x1B;
            default -> scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z
                    ? 'a' + scancode - InputConstants.KEY_A : scancode | SCANCODE_MASK;
        };
        KeyEvent event = new KeyEvent(scancode, keycode, mods);
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.keyPress(handle(), InputConstants.PRESS, event);
        mc.keyboardHandler.keyPress(handle(), InputConstants.RELEASE, event);
    }

    private static void type(String text)
    {
        Minecraft.getInstance().keyboardHandler.textInput(handle(), text);
    }

    private void open(Minecraft mc, BlockPos pos)
    {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false));
    }

    @SuppressWarnings("unchecked")
    private static void forgetChests(Minecraft mc)
    {
        String scope = StorageIndex.scope(mc);
        java.util.Map<String, StorageIndex.Rec> records = KTrash.field(StorageIndex.getInstance(), "records");

        if (records != null)
        {
            int before = records.size();
            records.values().removeIf(rec -> scope.equals(rec.scope));
            StorageIndex.getInstance().touch();
            CovOut.log(TAG + "индекс: забыто своих записей этого мира " + (before - records.size()));
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(TAG + "снимок " + name);
        Screenshot.grab(mc, false);
    }

    private static String chest(BlockPos pos, String items)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=south]{Items:[%s]}", pos.getX(), pos.getY(),
                pos.getZ(), items);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log(TAG + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        LayerListConfigs.STOCK_NEAR_ONLY.setBooleanValue(false);
        LayerListConfigs.STOCK_CHEST_TRACKER.setBooleanValue(true);

        if (this.group != null)
        {
            PlacementGroups.get().dissolve(this.group);
        }

        for (SchematicPlacement placement : this.placements)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
        }
    }
}
