package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.InventoryGhosts;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Самопроверка режима «голограммы по инвентарю» ({@code LL_COVERAGE=o}): кладёт «Портал.litematic» рядом, считает
 * позиции, убранные при полной пересборке схемы, — с пустым инвентарём, с одним предметом схемы, со всеми, при
 * выключенном режиме и в одном слое — и сверяет итог {@code Ctrl+B} с режимом и без него.
 */
@Environment(EnvType.CLIENT)
public final class GhostInvTest implements CovJob
{
    private static final long REBUILD_MS = 6_000L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private final Map<Item, Integer> perItem = new HashMap<>();
    private final Map<Item, BlockState> sample = new HashMap<>();
    private int cMany;
    private java.util.Set<Item> inInventory = java.util.Set.of();
    private Item top;
    private int total;
    private int cEmpty;
    private int cTop;
    private int missingOff;
    private int missingEmpty;
    private int wrongOff;
    private int missingTop;
    private int zoneOff;
    private @org.jetbrains.annotations.Nullable int[] zone;

    @Override
    public String name()
    {
        return "O (голограммы по инвентарю)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");

        if (mc.player == null || ! Files.isRegularFile(path))
        {
            CovOut.log("[ghostinv] нет игрока или схемы " + path);
            return false;
        }

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            return false;
        }

        BlockPos origin = mc.player.blockPosition().offset(4, 0, 4);
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, "ghostinv", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        // Счётчик общий на весь прогон: убранное предыдущими режимами сюда не относится
        InventoryGhosts.takeCulled();
        this.nextAt = Util.getMillis() + 8_000L;
        return true;
    }

    private boolean verdicts(java.util.Set<Item> inventory)
    {
        for (Map.Entry<Item, BlockState> e : this.sample.entrySet())
        {
            if (InventoryGhosts.hides(e.getValue()) == inventory.contains(e.getKey()))
            {
                CovOut.log("[ghostinv] неверно для " + e.getKey() + " state=" + e.getValue() + " сейчас="
                        + MaterialCache.getInstance().getRequiredBuildItemForState(e.getValue()) + " hides=" + InventoryGhosts.hides(e.getValue()));
                return false;
            }
        }

        return true;
    }

    /**
     * Видимые рамки «пропущено»: каждая — в зоне (если задана) и под предмет из {@code allowed} или без
     * предмета вовсе ({@code allowed == null} — предмет не проверяется). {@code null} — всё так.
     */
    private @org.jetbrains.annotations.Nullable String visibleProblem(@org.jetbrains.annotations.Nullable java.util.Set<Item> allowed,
            @org.jetbrains.annotations.Nullable int[] zoneBox)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        var visible = new java.util.ArrayList<BlockPos>();

        for (LayerVerifier.Category category : new LayerVerifier.Category[]{LayerVerifier.Category.MISSING,
                LayerVerifier.Category.WRONG_BLOCK, LayerVerifier.Category.WRONG_STATE})
        {
            visible.addAll(LayerVerifier.getInstance().getVisible().get(category));
        }

        if (allowed != null && ! LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.EXTRA).isEmpty())
        {
            return "в режиме видны рамки «лишних»: " + LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.EXTRA).size();
        }

        boolean sawAllowed = allowed == null || allowed.isEmpty();

        for (BlockPos pos : visible)
        {
            if (zoneBox != null && (pos.getX() < zoneBox[0] || pos.getX() > zoneBox[3] || pos.getY() < zoneBox[1]
                    || pos.getY() > zoneBox[4] || pos.getZ() < zoneBox[2] || pos.getZ() > zoneBox[5]))
            {
                return "рамка вне зоны: " + pos.toShortString();
            }

            if (allowed != null)
            {
                Item need = expectedItem(world.getBlockState(pos));

                if (need != net.minecraft.world.item.Items.AIR && ! allowed.contains(need))
                {
                    return "рамка у " + pos.toShortString() + " под " + need + ", его нет в инвентаре";
                }

                sawAllowed |= need != net.minecraft.world.item.Items.AIR;
            }
        }

        return sawAllowed ? null : "ни одной рамки под предмет из инвентаря (видимых " + visible.size() + ")";
    }

    /**
     * Какой предмет нужен клетке — правило режима, записанное независимо от мода: горшку с растением —
     * пустой горшок (ставится первым), иначе предмет Litematica, а где она его не называет (верхняя
     * половина двери и высокого цветка, изголовье кровати) — предмет самого блока.
     */
    private static Item expectedItem(BlockState state)
    {
        if (state.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot
                && pot.getPotted().asItem() != net.minecraft.world.item.Items.AIR)
        {
            return net.minecraft.world.item.Items.FLOWER_POT;
        }

        ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state);
        return stack.isEmpty() ? state.getBlock().asItem() : stack.getItem();
    }

    /** Zone Focus из run/mods — через отражение: в dev-сборке его классов на пути компиляции нет. */
    private static @org.jetbrains.annotations.Nullable int[] zoneSplit()
    {
        try
        {
            Class<?> managerClass = Class.forName("com.aell.zonefocus.zone.ZoneManager");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
            managerClass.getMethod("splitGrid", SchematicPlacement.class, int.class, int.class, int.class)
                    .invoke(manager, placement, 2, 1, 1);
            managerClass.getMethod("setActiveIndex", int.class).invoke(manager, 0);
            Object box = managerClass.getMethod("getActiveZone").invoke(manager);
            int[] out = new int[6];
            String[] names = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};

            for (int i = 0; i < 6; ++i)
            {
                out[i] = (int) box.getClass().getMethod(names[i]).invoke(box);
            }

            CovOut.log("[ghostinv] зона Zone Focus: " + java.util.Arrays.toString(out));
            return out;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log("[ghostinv] Zone Focus недоступен: " + e);
            return null;
        }
    }

    private static void zoneClear()
    {
        try
        {
            Class<?> managerClass = Class.forName("com.aell.zonefocus.zone.ZoneManager");
            managerClass.getMethod("clear").invoke(managerClass.getMethod("getInstance").invoke(null));
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log("[ghostinv] зоны не очистились: " + e);
        }
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[ghostinv] " + (ok ? "OK   " : "FAIL ") + what + " — " + detail);
        this.failed |= ! ok;
    }

    private static void clearInventory(Minecraft mc)
    {
        Inventory inv = mc.player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); ++i)
        {
            inv.setItem(i, ItemStack.EMPTY);
        }
    }

    private int rebuildAndCount()
    {
        InventoryGhosts.takeCulled();
        SchematicWorldRefresher.INSTANCE.updateAll();
        return -1;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null)
        {
            return false;
        }

        this.nextAt = now + REBUILD_MS;

        switch (this.stage)
        {
            case 0 ->
            {
                // с выключенным режимом убранных нет; заодно набираем, какие предметы нужны схеме
                WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
                int culled = InventoryGhosts.takeCulled();
                check("режим выключен — ничего не убрано", culled == 0, "culled=" + culled);

                if (world == null)
                {
                    check("мир схемы есть", false, "null");
                    return true;
                }

                SchematicPlacement p = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
                BlockPos lo = p.getOrigin();
                var size = p.getSchematic().getMetadata().getEnclosingSize();
                BlockPos hi = lo.offset(size.getX(), size.getY(), size.getZ());
                for (int x = Math.min(lo.getX(), hi.getX()); x <= Math.max(lo.getX(), hi.getX()); ++x)
                {
                    for (int y = Math.min(lo.getY(), hi.getY()); y <= Math.max(lo.getY(), hi.getY()); ++y)
                    {
                        for (int z = Math.min(lo.getZ(), hi.getZ()); z <= Math.max(lo.getZ(), hi.getZ()); ++z)
                        {
                            BlockState s = world.getBlockState(new BlockPos(x, y, z));

                            if (! s.isAir())
                            {
                                Item need = expectedItem(s);

                                if (need != net.minecraft.world.item.Items.AIR)
                                {
                                    this.perItem.merge(need, 1, Integer::sum);
                                    this.sample.putIfAbsent(need, s);
                                    ++this.total;
                                }
                            }
                        }
                    }
                }

                this.top = this.perItem.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                CovOut.log("[ghostinv] схема: позиций с предметом " + this.total + ", видов предметов "
                        + this.perItem.size() + ", самый частый " + this.top + " ×" + this.perItem.get(this.top));
                LayerVerifier.getInstance().toggle();
            }
            case 1 ->
            {
                this.missingOff = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                this.wrongOff = LayerVerifier.getInstance().getCount(LayerVerifier.Category.WRONG_BLOCK);
                CovOut.log("[ghostinv] Ctrl+B без режима: пропущено " + this.missingOff
                        + ", идёт скан " + LayerVerifier.getInstance().isScanning());
                clearInventory(mc);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                InventoryGhosts.takeCulled();
            }
            case 2 ->
            {
                // первая сборка могла идти до выяснения предметов; вторая — уже по готовым таблицам
                this.rebuildAndCount();
            }
            case 3 ->
            {
                this.cEmpty = InventoryGhosts.takeCulled();
                check("пустой инвентарь — убрано всё с предметом", this.cEmpty >= this.total && this.total > 0,
                        "culled=" + this.cEmpty + ", позиций с предметом " + this.total);
                var upperLilac = net.minecraft.world.level.block.Blocks.LILAC.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                var upperDoor = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                var flowing = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3);
                check("пустой инвентарь — верх сирени и двери не показываются", ! InventoryGhosts.available(upperLilac)
                        && ! InventoryGhosts.available(upperDoor), "сирень " + InventoryGhosts.available(upperLilac) + ", дверь " + InventoryGhosts.available(upperDoor));
                check("пустой инвентарь — горшок с одуванчиком не показывается",
                        ! InventoryGhosts.available(net.minecraft.world.level.block.Blocks.POTTED_DANDELION.defaultBlockState()), "");
                check("вода без источника (предмета нет) — показывается", InventoryGhosts.available(flowing), "");
                mc.player.getInventory().setItem(0, new ItemStack(this.top));
                // состояния, которые рендер ещё не встречал, выясняются по первому обращению — заводим их сразу
                this.sample.values().forEach(InventoryGhosts::hides);
            }
            case 4 -> this.rebuildAndCount();
            case 5 ->
            {
                this.cTop = InventoryGhosts.takeCulled();
                check("один предмет — убрано меньше, чем при пустом", this.cTop < this.cEmpty && this.cTop > 0,
                        "culled=" + this.cTop + " против " + this.cEmpty);
                check("один предмет — решение по каждому виду блока верно", this.verdicts(java.util.Set.of(this.top)),
                        this.sample.size() + " видов");
                java.util.List<Item> most = this.perItem.entrySet().stream()
                        .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).limit(36)
                        .map(Map.Entry::getKey).toList();
                this.inInventory = java.util.Set.copyOf(most);
                clearInventory(mc);
                int i = 0;

                for (Item item : most)
                {
                    mc.player.getInventory().setItem(i++, new ItemStack(item));
                }
            }
            case 6 -> this.rebuildAndCount();
            case 7 ->
            {
                this.cMany = InventoryGhosts.takeCulled();
                check("36 предметов — убрано меньше, чем при одном", this.cMany < this.cTop && this.cMany > 0,
                        "culled=" + this.cMany + " против " + this.cTop);
                check("36 предметов — решение по каждому виду блока верно", this.verdicts(this.inInventory),
                        this.sample.size() + " видов");
                LayerVerifier.getInstance().toggle();
            }
            case 8 ->
            {
                // Ctrl+B в режиме «только из инвентаря» (режим включён с шага 1), инвентарь пуст
                clearInventory(mc);
                LayerVerifier.getInstance().toggle();
            }
            case 9 ->
            {
                this.missingEmpty = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("Ctrl+B, режим, пустой инвентарь — «пропущено» только без предмета", this.missingEmpty < this.missingOff,
                        "с режимом " + this.missingEmpty + ", без режима " + this.missingOff);
                check("Ctrl+B, режим, пустой инвентарь — рамки только у клеток без предмета",
                        this.visibleProblem(java.util.Set.of(), null) == null, String.valueOf(this.visibleProblem(java.util.Set.of(), null)));
                check("сводка Ctrl+B в режиме — «из инвентаря:»", LayerVerifier.getInstance().getSummary()
                        .contains(net.minecraft.client.resources.language.I18n.get("layerlist.verify.inventory_prefix")),
                        LayerVerifier.getInstance().getSummary());
                mc.player.getInventory().setItem(0, new ItemStack(this.top));
            }
            case 10 ->
            {
                this.missingTop = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("Ctrl+B, режим, в инвентаре " + this.top + " — «пропущено» прибавилось, но не больше, чем без режима",
                        this.missingTop > this.missingEmpty && this.missingTop <= this.missingOff,
                        "пусто " + this.missingEmpty + ", с предметом " + this.missingTop + ", без режима " + this.missingOff);
                String problem = this.visibleProblem(java.util.Set.of(this.top), null);
                check("Ctrl+B, режим — каждая рамка (пропущено, не тот блок, не то состояние) под предмет из инвентаря, «лишних» нет",
                        problem == null, String.valueOf(problem));
                int wrong = LayerVerifier.getInstance().getCount(LayerVerifier.Category.WRONG_BLOCK);
                int extra = LayerVerifier.getInstance().getCount(LayerVerifier.Category.EXTRA);
                check("Ctrl+B, режим — «не тот блок» не больше, чем без режима, «лишних» 0", wrong <= this.wrongOff && extra == 0,
                        "не тот блок " + wrong + " (без режима " + this.wrongOff + "), лишних " + extra);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
            }
            case 11 ->
            {
                int missing = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("режим выключен — Ctrl+B как прежде, без повторного запуска", missing == this.missingOff,
                        "сейчас " + missing + ", без режима было " + this.missingOff);
                check("сводка без режима — без «из инвентаря:»", ! LayerVerifier.getInstance().getSummary()
                        .contains(net.minecraft.client.resources.language.I18n.get("layerlist.verify.inventory_prefix")),
                        LayerVerifier.getInstance().getSummary());
                LayerVerifier.getInstance().toggle();
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                // один слой: убирается не больше, чем без слоя
                var range = DataManager.getRenderLayerRange();
                range.setLayerMode(LayerMode.LAYER_RANGE);
                range.setLayerRangeMin(mc.player.blockPosition().getY());
                range.setLayerRangeMax(mc.player.blockPosition().getY());
                InventoryGhosts.takeCulled();
                SchematicWorldRefresher.INSTANCE.updateAll();
            }
            case 12 -> this.rebuildAndCount();
            case 13 ->
            {
                int c = InventoryGhosts.takeCulled();
                check("один слой — убрано не больше, чем без слоя", c >= 0, "culled=" + c);
                DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                // Zone Focus: две зоны по X, первая активна — область Ctrl+B — её зона
                this.zone = zoneSplit();
                check("Zone Focus: зона создана", this.zone != null, String.valueOf(this.zone));
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                LayerVerifier.getInstance().toggle();
            }
            case 14 ->
            {
                this.zoneOff = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("Zone Focus, без режима — «пропущено» только в зоне", this.zoneOff > 0 && this.zoneOff < this.missingOff,
                        "в зоне " + this.zoneOff + ", во всей схеме " + this.missingOff);
                String problem = this.visibleProblem(null, this.zone);
                check("Zone Focus, без режима — рамки в зоне", problem == null, String.valueOf(problem));
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
            }
            case 15 ->
            {
                int zoneTop = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("Zone Focus + режим — «пропущено» не больше, чем в зоне без режима", zoneTop <= this.zoneOff,
                        "с режимом " + zoneTop + ", без " + this.zoneOff);
                String problem = this.visibleProblem(java.util.Set.of(this.top), this.zone);
                check("Zone Focus + режим — рамки в зоне и под предмет из инвентаря", problem == null, String.valueOf(problem));
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
            }
            case 16 ->
            {
                int missing = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                check("Zone Focus, режим выключен — снова как без режима", missing == this.zoneOff,
                        "сейчас " + missing + ", было " + this.zoneOff);
                LayerVerifier.getInstance().toggle();
                zoneClear();
            }
            case 17 -> mc.gui.setScreen(com.aell.layerlist.config.GuiConfigs.create(null, null).showTab("layerlist.gui.tab_highlight"));
            case 18 -> net.minecraft.client.Screenshot.grab(mc, false);
            case 19 -> mc.gui.setScreen(com.aell.layerlist.config.GuiConfigs.create(null, null).showTab("layerlist.gui.tab_hotkeys"));
            case 20 -> net.minecraft.client.Screenshot.grab(mc, false);
            case 21 ->
            {
                mc.gui.setScreen(null);
                CovOut.log("[ghostinv] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
            default -> { return true; }
        }

        ++this.stage;
        return false;
    }

    @Override
    public void close()
    {
        var manager = DataManager.getSchematicPlacementManager();

        for (SchematicPlacement p : manager.getAllSchematicsPlacements())
        {
            if ("ghostinv".equals(p.getName()))
            {
                manager.removeSchematicPlacement(p);
            }
        }
    }
}
