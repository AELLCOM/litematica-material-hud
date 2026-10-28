package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.easyplace.GrowingPlants;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Дамп H — растения и полки (0.0.23): светящиеся ягоды и прочие лозы (подсчёт тел в
 * списке, ягоды костной мукой, обрезанный кончик ножницами), бледный мох, лиана на трёх
 * гранях, трутовик возраста 1, запитанная цепочка полок с предметами (обмен с хотбаром) и
 * полка без питания. Строит образец, снимает с него схему, кладёт её на такие же опоры,
 * проверяет список до, ставит всё Easy Place в выживании, сверяет клетки и список после.
 * Результат — {@code plants.csv}.
 */
@Environment(EnvType.CLIENT)
public final class DumpPlants implements CovJob
{
    /** {@code LL_PLANTS_SHELF_HOTBAR=1}: нужное полке — в хотбаре, на игроке шлем (проверка номеров меню, 14.10.2026). */
    private static final boolean SHELF_HOTBAR = "1".equals(System.getenv("LL_PLANTS_SHELF_HOTBAR"));

    private static final String HEADER = "subject,expected,actual,ok";
    private static final BlockPos REF = new BlockPos(300, 100, 0);
    private static final BlockPos TARGET = new BlockPos(300, 100, 24);
    private static final long STABLE_MS = 3000L;
    private static final long PER_CELL_MS = 8000L;
    /** Без обновления соседей: иначе лоза при постановке сама пересоберёт голову и тело. */
    private static final int EXACT = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private enum Phase { BUILD, SNAPSHOT, GHOSTS, LIST_BEFORE, SURVIVAL, PLACE, VERIFY, LIST_AFTER, DONE }

    private CovOut out;
    private Phase phase = Phase.BUILD;
    private long until;
    private int ok;
    private int failed;
    private SchematicPlacement placement;
    private List<BlockPos> cells;
    private int cellIndex;
    private boolean positioned;
    private long cellDeadline;
    private long lastTick = Long.MIN_VALUE;
    private long ghostWait;
    private String listSignature = "";
    private long listStableAt;

    @Override
    public String name()
    {
        return "H (растения и полки)";
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
            Files.deleteIfExists(CovOut.dir().resolve("plants.csv"));
            this.out = CovOut.open("plants.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("plants.csv не открылся: " + e);
            return false;
        }

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        LayerListConfigs.EP_FINISH_TOOLS.setBooleanValue(true);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.EP_DEBUG.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
        Configs.Generic.MATERIAL_LIST_IGNORE_STATE.setBooleanValue(false);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        server.execute(() -> {
            command(server, "forceload add 288 -16 320 48");
            ServerLevel level = server.overworld();
            clear(level, REF);
            clear(level, TARGET);
            supports(level, REF, false);
            supports(level, TARGET, true);
            plants(level, REF);

            // Как у игрока: полки стоят, потом под ними появляется редстоун — они запитываются
            // и сцепляются сами (getStateForPlacement при setBlock не работает)
            for (int x = 1; x <= 3; ++x)
            {
                level.setBlockAndUpdate(REF.offset(x, -1, 8), Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
            command(server, "tp @a " + (REF.getX() + 4.5D) + " " + REF.getY() + " " + (REF.getZ() + 3.5D));
        });

        this.until = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.until || mc.level == null || mc.player == null)
        {
            return false;
        }

        switch (this.phase)
        {
            case BUILD -> this.snapshot(mc);
            case SNAPSHOT, GHOSTS -> this.waitGhosts(now);
            case LIST_BEFORE -> this.listCheck(now, false);
            case SURVIVAL -> this.survival(mc);
            case PLACE -> this.place(mc, now);
            case VERIFY -> this.verify(mc);
            case LIST_AFTER -> this.listCheck(now, true);
            case DONE -> {
                return true;
            }
        }

        return this.phase == Phase.DONE;
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        CovOut.log("[plants] итог: OK " + this.ok + ", FAIL " + this.failed);

        if (this.out != null)
        {
            this.out.close();
        }
    }

    // ===== Фазы =====

    private void snapshot(Minecraft mc)
    {
        // Схема видит то, что видит клиент: проверяем, что образец до него дошёл как задумано
        this.record("build", "светящаяся лоза: низ — голова с ягодами", true,
                mc.level.getBlockState(REF.offset(1, 0, 1)).getBlock() instanceof GrowingPlantHeadBlock
                        && GrowingPlants.berries(mc.level.getBlockState(REF.offset(1, 0, 1))));
        this.record("build", "запитанная полка", true, mc.level.getBlockState(REF.offset(1, 0, 8)).getValue(ShelfBlock.POWERED));

        AreaSelection area = new AreaSelection();
        area.setName("dev-plants");
        BlockPos min = REF.offset(0, -1, 0);
        area.addSubRegionBox(new Box(min, REF.offset(12, 4, 9), "main"), false);
        area.setExplicitOrigin(min);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);

        if (schematic == null)
        {
            this.record("build", "схема снята", 1, 0);
            this.phase = Phase.DONE;
            return;
        }

        this.placement = SchematicPlacement.createFor(schematic, TARGET.offset(0, -1, 0), "dev-plants", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        this.phase = Phase.GHOSTS;
        this.ghostWait = 0L;
        this.until = Util.getMillis() + 1000L;
    }

    private void waitGhosts(long now)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        BlockPos probe = TARGET.offset(1, 0, 1);
        boolean ready = world != null && world.getBlockState(probe).is(Blocks.CAVE_VINES)
                && world.getBlockState(TARGET.offset(1, 0, 8)).getBlock() instanceof ShelfBlock;

        if (this.ghostWait == 0L)
        {
            this.ghostWait = now + 20_000L;
        }

        if (! ready && now < this.ghostWait)
        {
            this.until = now + 250L;
            return;
        }

        this.record("ghosts", "мир схемы собран", true, ready);
        this.cells = targetCells(world);
        LayerCountManager.getInstance().markDirty();
        this.phase = Phase.LIST_BEFORE;
        this.listSignature = "";
        this.until = now + 500L;
    }

    private void listCheck(long now, boolean after)
    {
        Map<String, int[]> rows = rows();
        String signature = rows.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1] + "/" + e.getValue()[2])
                .sorted().reduce("", (a, b) -> a + ";" + b);

        if (! signature.equals(this.listSignature))
        {
            this.listSignature = signature;
            this.listStableAt = now + STABLE_MS;
            this.until = now + 250L;
            return;
        }

        if (now < this.listStableAt)
        {
            this.until = now + 250L;
            return;
        }

        Map<Item, Integer> expected = new LinkedHashMap<>();
        expected.put(Items.GLOW_BERRIES, 4);
        expected.put(Items.WEEPING_VINES, 5);
        expected.put(Items.TWISTING_VINES, 3);
        expected.put(Items.KELP, 3);
        expected.put(Items.PALE_HANGING_MOSS, 2);
        expected.put(Items.VINE, 3);
        expected.put(Items.OAK_SHELF, 4);
        String phase = after ? "list_after" : "list_before";

        for (Map.Entry<Item, Integer> entry : expected.entrySet())
        {
            String key = id(entry.getKey());
            int[] row = rows.getOrDefault(key, new int[]{0, 0, 0});
            this.record(phase, key + " всего", entry.getValue(), row[0]);
            this.record(phase, key + " не хватает", after ? 0 : entry.getValue(), row[1]);
            this.record(phase, key + " не то", 0, row[2]);
        }

        this.phase = after ? Phase.DONE : Phase.SURVIVAL;
        this.until = now + 200L;
    }

    /**
     * Вариант {@code LL_PLANTS_SHELF_HOTBAR=1}: трава для полки без питания — в слоте хотбара 6 (вне окна запитанной полки
     * 7–9) стопкой больше нужного, слот 8 пуст. До 14.10.2026 «Полка без питания» переводила номер ячейки хотбара в
     * меню инвентаря как есть — и брала из слота 5 меню шлем вместо травы.
     */
    private static void shelfHotbar(Inventory inventory)
    {
        for (int slot = 0; slot < 36; ++slot)
        {
            if (slot != 5 && inventory.getItem(slot).is(Items.GRASS_BLOCK))
            {
                ItemStack grass = inventory.getItem(slot);
                inventory.setItem(slot, inventory.getItem(5));
                inventory.setItem(5, grass);
                break;
            }
        }

        ItemStack seventh = inventory.getItem(7);

        for (int slot = 9; slot < 36 && ! seventh.isEmpty(); ++slot)
        {
            if (inventory.getItem(slot).isEmpty())
            {
                inventory.setItem(slot, seventh);
                inventory.setItem(7, ItemStack.EMPTY);
                break;
            }
        }
    }

    private void survival(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        server.execute(() -> {
            command(server, "gamemode survival @a");
            command(server, "clear @a");

            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                Inventory inventory = player.getInventory();
                // Чужое в хотбаре — нарочно: запитанная полка обязана сначала освободить окно
                inventory.add(new ItemStack(Items.COBBLESTONE, 32));
                inventory.add(new ItemStack(Items.GLOW_BERRIES, 8));
                inventory.add(new ItemStack(Items.WEEPING_VINES, 8));
                inventory.add(new ItemStack(Items.TWISTING_VINES, 8));
                inventory.add(new ItemStack(Items.KELP, 8));
                inventory.add(new ItemStack(Items.PALE_HANGING_MOSS, 4));
                inventory.add(new ItemStack(Items.VINE, 8));
                inventory.add(new ItemStack(Items.OAK_SHELF, 4));
                inventory.add(new ItemStack(Items.BONE_MEAL, 32));
                inventory.add(new ItemStack(Items.SHEARS));
                inventory.add(new ItemStack(Items.DIRT, 5));
                inventory.add(new ItemStack(Items.STONE, 5));
                inventory.add(new ItemStack(Items.APPLE, 5));
                inventory.add(new ItemStack(Items.BREAD, 5));
                inventory.add(new ItemStack(Items.DIAMOND, 5));
                inventory.add(new ItemStack(Items.GRASS_BLOCK, 5));

                if (SHELF_HOTBAR)
                {
                    shelfHotbar(inventory);
                    player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                }
            }
        });

        this.cellIndex = 0;
        this.positioned = false;
        this.phase = Phase.PLACE;
        this.until = Util.getMillis() + 1500L;
    }

    /** По клетке: встать рядом, смотреть в неё, жать Easy Place раз в тик, пока клетка не станет как в схеме. */
    private void place(Minecraft mc, long now)
    {
        if (this.cellIndex >= this.cells.size())
        {
            this.phase = Phase.VERIFY;
            this.until = now + 1500L;
            return;
        }

        BlockPos cell = this.cells.get(this.cellIndex);
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        BlockState wanted = world == null ? Blocks.AIR.defaultBlockState() : world.getBlockState(cell);

        if (! this.positioned)
        {
            Vec3 spot = standingSpot(cell);
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %d %.2f", spot.x, TARGET.getY(), spot.z)));
            this.positioned = true;
            this.cellDeadline = now + PER_CELL_MS;
            this.until = now + 400L;
            return;
        }

        String problem = mismatch(mc, wanted, mc.level.getBlockState(cell), cell, world);

        if (problem != null && now > this.cellDeadline)
        {
            this.diagnose(mc, wanted, cell);
        }

        if (problem == null || now > this.cellDeadline)
        {
            boolean informational = wanted.getBlock() instanceof VineBlock;
            this.record("place" + (informational ? " (лиана, справочно)" : ""), describe(wanted, cell), "как в схеме",
                    problem == null ? "как в схеме" : problem);
            ++this.cellIndex;
            this.positioned = false;
            this.until = now + 100L;
            return;
        }

        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        look(mc, aimPoint(wanted, cell));

        // Жмём, только когда сервер уже знает этот взгляд: поворот уходит пакетом движения в
        // конце тика, а LookSpoof не шлёт его, если клиентский угол и так нужный, — клик в тот
        // же тик ушёл бы со старым углом (полка вставала задом наперёд)
        if (synced(mc))
        {
            WorldUtils.handleEasyPlace(mc);
        }
    }

    /** Почему клетка не встала: рука, запас, кэш и темп Litematica. */
    private void diagnose(Minecraft mc, BlockState wanted, BlockPos cell)
    {
        StringBuilder out = new StringBuilder("[plants]   диагноз " + describe(wanted, cell) + ": в руке "
                + mc.player.getMainHandItem() + ", слот " + mc.player.getInventory().getSelectedSlot());

        try
        {
            Class<?> utils = fi.dy.masa.litematica.util.EasyPlaceUtils.class;
            var cached = utils.getDeclaredMethod("easyPlaceIsPositionCached", BlockPos.class);
            var fast = utils.getDeclaredMethod("easyPlaceIsTooFast");
            cached.setAccessible(true);
            fast.setAccessible(true);
            out.append(", кэш ").append(cached.invoke(null, cell)).append(", быстро ").append(fast.invoke(null));
        }
        catch (ReflectiveOperationException e)
        {
            out.append(", кэш ? (").append(e).append(")");
        }

        Item item = GrowingPlants.item(wanted);

        if (item != null)
        {
            int count = 0;

            for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); ++slot)
            {
                if (mc.player.getInventory().getItem(slot).is(item))
                {
                    count += mc.player.getInventory().getItem(slot).getCount();
                }
            }

            out.append(", ").append(id(item)).append(" в инвентаре ").append(count)
                    .append(", опора ").append(GrowingPlants.awaitingSupport(mc.level, cell, wanted, mc.level.getBlockState(cell)) ? "нет" : "есть");
        }

        CovOut.log(out.toString());
    }

    /** Итоговая сверка всех клеток (полки — только после того, как поставлены все, и цепочка сложилась). */
    private void verify(Minecraft mc)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        for (BlockPos cell : this.cells)
        {
            BlockState wanted = world.getBlockState(cell);
            String problem = mismatch(mc, wanted, mc.level.getBlockState(cell), cell, world);
            boolean informational = wanted.getBlock() instanceof VineBlock;
            this.record("verify" + (informational ? " (лиана, справочно)" : ""), describe(wanted, cell), "как в схеме",
                    problem == null ? "как в схеме" : problem);
        }

        if (SHELF_HOTBAR)
        {
            this.record("verify", "шлем на игроке (нужное полке — в хотбаре)", "iron_helmet",
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                            mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem()).getPath());
        }

        this.phase = Phase.LIST_AFTER;
        this.listSignature = "";
        LayerCountManager.getInstance().markDirty();
        this.until = Util.getMillis() + 500L;
    }

    // ===== Сверка клетки =====

    private static @org.jetbrains.annotations.Nullable String mismatch(Minecraft mc, BlockState wanted, BlockState have,
            BlockPos cell, WorldSchematic world)
    {
        if (wanted.getBlock() instanceof net.minecraft.world.level.block.GrowingPlantBlock)
        {
            if (! GrowingPlants.sameFamily(wanted, have))
            {
                return "в мире " + name(have);
            }

            if (GrowingPlants.berries(wanted) != GrowingPlants.berries(have))
            {
                return "ягоды " + GrowingPlants.berries(have);
            }

            if (GrowingPlants.cut(wanted) && have.getBlock() instanceof GrowingPlantHeadBlock && ! GrowingPlants.cut(have))
            {
                return "не обрезана";
            }

            return null;
        }

        if (wanted.getBlock() instanceof HangingMossBlock)
        {
            return have.is(wanted.getBlock()) ? null : "в мире " + name(have);
        }

        if (wanted.getBlock() instanceof ShelfBlock)
        {
            if (! have.is(wanted.getBlock()) || have.getValue(ShelfBlock.FACING) != wanted.getValue(ShelfBlock.FACING))
            {
                return "в мире " + name(have);
            }

            if (have.getValue(ShelfBlock.POWERED) != wanted.getValue(ShelfBlock.POWERED))
            {
                return "питание " + have.getValue(ShelfBlock.POWERED);
            }

            List<ItemStack> want = items(world.getBlockEntity(cell));
            List<ItemStack> got = items(mc.level.getBlockEntity(cell));

            for (int slot = 0; slot < 3; ++slot)
            {
                // По типу предмета: полка без питания забирает из руки всю стопку — так в ванили
                if (want.get(slot).isEmpty() != got.get(slot).isEmpty()
                        || (! want.get(slot).isEmpty() && ! ItemStack.isSameItem(want.get(slot), got.get(slot))))
                {
                    return "слот " + slot + ": " + got.get(slot) + " вместо " + want.get(slot);
                }
            }

            return null;
        }

        return have == wanted ? null : "в мире " + name(have);
    }

    private static List<ItemStack> items(@org.jetbrains.annotations.Nullable net.minecraft.world.level.block.entity.BlockEntity entity)
    {
        List<ItemStack> out = new ArrayList<>();

        for (int slot = 0; slot < 3; ++slot)
        {
            out.add(entity instanceof ShelfBlockEntity shelf ? shelf.getItem(slot) : ItemStack.EMPTY);
        }

        return out;
    }

    // ===== Мир =====

    private static void clear(ServerLevel level, BlockPos origin)
    {
        for (int x = -2; x <= 14; ++x)
        {
            for (int z = -2; z <= 11; ++z)
            {
                for (int y = -1; y <= 6; ++y)
                {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), EXACT);
                }
            }
        }
    }

    /** Опоры — и у образца, и у цели: пол, потолок над лозами, колодец с водой, стенки, редстоун под полками. */
    private static void supports(ServerLevel level, BlockPos o, boolean redstone)
    {
        for (int x = 0; x <= 8; ++x)
        {
            for (int z = 0; z <= 9; ++z)
            {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), EXACT);
            }
        }

        for (int x = 0; x <= 6; ++x)
        {
            for (int z = 0; z <= 2; ++z)
            {
                level.setBlock(o.offset(x, 4, z), Blocks.STONE.defaultBlockState(), EXACT);
            }
        }

        // Бассейн для ламинарии за площадкой: вода x 9–11, z 3–6, глубина 3, каменные бортики.
        // Открытый: сквозь стекло ни луч Litematica, ни игрок клетку не видят — стоим прямо в воде.
        for (int x = 8; x <= 12; ++x)
        {
            for (int z = 2; z <= 7; ++z)
            {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), EXACT);

                for (int y = 0; y <= 2; ++y)
                {
                    boolean wall = x == 8 || x == 12 || z == 2 || z == 7;
                    level.setBlock(o.offset(x, y, z), wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), EXACT);
                }
            }
        }

        // Лиана в углу: стена с севера, с востока и потолок
        level.setBlock(o.offset(5, 1, 4), Blocks.STONE.defaultBlockState(), EXACT);
        level.setBlock(o.offset(6, 1, 5), Blocks.STONE.defaultBlockState(), EXACT);
        level.setBlock(o.offset(5, 2, 5), Blocks.STONE.defaultBlockState(), EXACT);
        // Стенка под трутовик
        level.setBlock(o.offset(8, 1, 1), Blocks.STONE.defaultBlockState(), EXACT);

        // Редстоун под тремя полками — у цели заранее: полка запитается при установке
        for (int x = 1; x <= 3 && redstone; ++x)
        {
            level.setBlock(o.offset(x, -1, 8), Blocks.REDSTONE_BLOCK.defaultBlockState(), EXACT);
        }
    }

    private static void plants(ServerLevel level, BlockPos o)
    {
        BlockState plant = Blocks.CAVE_VINES_PLANT.defaultBlockState();
        BlockState head = Blocks.CAVE_VINES.defaultBlockState();
        level.setBlock(o.offset(1, 3, 1), plant.setValue(CaveVines.BERRIES, true), EXACT);
        level.setBlock(o.offset(1, 2, 1), plant.setValue(CaveVines.BERRIES, false), EXACT);
        level.setBlock(o.offset(1, 1, 1), plant.setValue(CaveVines.BERRIES, true), EXACT);
        level.setBlock(o.offset(1, 0, 1), head.setValue(CaveVines.BERRIES, true).setValue(GrowingPlantHeadBlock.AGE, 25), EXACT);

        level.setBlock(o.offset(3, 3, 1), Blocks.WEEPING_VINES_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(3, 2, 1), Blocks.WEEPING_VINES_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(3, 1, 1), Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 25), EXACT);
        level.setBlock(o.offset(5, 3, 1), Blocks.WEEPING_VINES_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(5, 2, 1), Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 10), EXACT);

        level.setBlock(o.offset(6, 3, 1), Blocks.PALE_HANGING_MOSS.defaultBlockState().setValue(HangingMossBlock.TIP, false), EXACT);
        level.setBlock(o.offset(6, 2, 1), Blocks.PALE_HANGING_MOSS.defaultBlockState().setValue(HangingMossBlock.TIP, true), EXACT);

        level.setBlock(o.offset(1, 0, 5), Blocks.TWISTING_VINES_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(1, 1, 5), Blocks.TWISTING_VINES_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(1, 2, 5), Blocks.TWISTING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 25), EXACT);

        level.setBlock(o.offset(10, 0, 5), Blocks.KELP_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(10, 1, 5), Blocks.KELP_PLANT.defaultBlockState(), EXACT);
        level.setBlock(o.offset(10, 2, 5), Blocks.KELP.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, 25), EXACT);

        level.setBlock(o.offset(5, 1, 5), Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true)
                .setValue(VineBlock.EAST, true).setValue(VineBlock.UP, true), EXACT);

        shelf(level, o.offset(1, 0, 8), true, new ItemStack(Items.DIRT), ItemStack.EMPTY, new ItemStack(Items.STONE));
        shelf(level, o.offset(2, 0, 8), true, new ItemStack(Items.APPLE), new ItemStack(Items.BREAD), ItemStack.EMPTY);
        shelf(level, o.offset(3, 0, 8), true, new ItemStack(Items.DIAMOND), ItemStack.EMPTY, ItemStack.EMPTY);
        shelf(level, o.offset(5, 0, 8), false, new ItemStack(Items.GRASS_BLOCK), ItemStack.EMPTY, new ItemStack(Items.GRASS_BLOCK));
    }

    private static void shelf(ServerLevel level, BlockPos pos, boolean powered, ItemStack a, ItemStack b, ItemStack c)
    {
        // Ставим с обновлениями: полка сама проверит питание и сцепится с соседями, как в игре
        level.setBlockAndUpdate(pos, Blocks.OAK_SHELF.defaultBlockState().setValue(ShelfBlock.FACING, Direction.NORTH));

        if (level.getBlockEntity(pos) instanceof ShelfBlockEntity shelf)
        {
            shelf.setItem(0, a);
            shelf.setItem(1, b);
            shelf.setItem(2, c);
            shelf.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_ALL);
        }
    }

    // ===== Помощники =====

    /** Клетки цели в порядке, в каком их ставит человек: висячее сверху вниз, растущее вверх — снизу. */
    private static List<BlockPos> targetCells(WorldSchematic world)
    {
        List<BlockPos> out = new ArrayList<>();
        int[][] hanging = {{1, 1}, {3, 1}, {5, 1}, {6, 1}};

        for (int[] column : hanging)
        {
            for (int y = 3; y >= 0; --y)
            {
                add(out, world, TARGET.offset(column[0], y, column[1]));
            }
        }

        for (int[] column : new int[][]{{1, 5}, {10, 5}})
        {
            for (int y = 0; y <= 3; ++y)
            {
                add(out, world, TARGET.offset(column[0], y, column[1]));
            }
        }

        add(out, world, TARGET.offset(5, 1, 5));
        add(out, world, TARGET.offset(7, 1, 1));

        for (int x : new int[]{1, 2, 3, 5})
        {
            add(out, world, TARGET.offset(x, 0, 8));
        }

        if (SHELF_HOTBAR)
        {
            // Полку без питания — первой, пока хотбар разложен как задумано (цепочка запитанных его перекладывает целиком)
            BlockPos single = TARGET.offset(5, 0, 8);

            if (out.remove(single))
            {
                out.add(0, single);
            }
        }

        return out;
    }

    private static void add(List<BlockPos> out, WorldSchematic world, BlockPos pos)
    {
        BlockState state = world.getBlockState(pos);

        if (! state.isAir() && ! state.is(Blocks.WATER) && ! state.is(Blocks.GLASS) && ! state.is(Blocks.STONE))
        {
            out.add(pos);
        }
    }

    /**
     * Где стоять, чтобы клетку было видно и до неё дотянуться: висячее и трутовик — с ряда
     * z = 3 под краем потолка, скручивающаяся лоза — сбоку, лиана в углу — с юга (с севера и
     * востока у неё стенки), ламинария — прямо в бассейне, полки — перед лицевой гранью.
     */
    private static Vec3 standingSpot(BlockPos cell)
    {
        int rx = cell.getX() - TARGET.getX();
        int rz = cell.getZ() - TARGET.getZ();
        int x = rx;
        int z = 3;

        if (rx == 10)
        {
            z = 3;
        }
        else if (rx == 5 && rz == 5)
        {
            z = 7;
        }
        else if (rx == 1 && rz == 5)
        {
            x = 0;
            z = 3;
        }
        else if (rz == 8)
        {
            z = 6;
        }

        return new Vec3(TARGET.getX() + x + 0.5D, TARGET.getY(), TARGET.getZ() + z + 0.5D);
    }

    /** Куда смотреть: центр клетки, у полки — её лицевая грань, у лозы — середина стебля. */
    private static Vec3 aimPoint(BlockState wanted, BlockPos cell)
    {
        if (wanted.getBlock() instanceof ShelfBlock)
        {
            Direction facing = wanted.getValue(ShelfBlock.FACING);
            return Vec3.atCenterOf(cell).add(facing.getStepX() * 0.45D, 0.25D, facing.getStepZ() * 0.45D);
        }

        return Vec3.atCenterOf(cell);
    }

    private static boolean synced(Minecraft mc)
    {
        try
        {
            var field = net.minecraft.client.player.LocalPlayer.class.getDeclaredField("yRotLast");
            field.setAccessible(true);
            return Math.abs(field.getFloat(mc.player) - mc.player.getYRot()) < 0.01F;
        }
        catch (ReflectiveOperationException e)
        {
            return true;
        }
    }

    private static void look(Minecraft mc, Vec3 point)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
    }

    private static Map<String, int[]> rows()
    {
        Map<String, int[]> rows = new LinkedHashMap<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getEntries())
        {
            rows.put(id(entry.getStack().getItem()), new int[]{entry.getCountTotal(), entry.getCountMissing(), entry.getCountMismatched()});
        }

        return rows;
    }

    private static String describe(BlockState state, BlockPos pos)
    {
        BlockPos rel = pos.subtract(TARGET);
        return name(state) + " @" + rel.getX() + "/" + rel.getY() + "/" + rel.getZ();
    }

    private static String name(BlockState state)
    {
        return state.toString().replace("Block{minecraft:", "").replace("}", "");
    }

    private static String id(Item item)
    {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("команда не прошла (" + command + "): " + e);
        }
    }

    private void record(String phase, String subject, Object expected, Object actual)
    {
        boolean pass = String.valueOf(expected).equals(String.valueOf(actual));

        if (pass || phase.contains("справочно"))
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        CovOut.log("[plants] " + (pass ? "OK  " : (phase.contains("справочно") ? "INFO" : "FAIL")) + " " + phase + ": " + subject
                + " — ждали " + expected + ", вышло " + actual);
        this.out.row(List.of(phase + ":" + subject, String.valueOf(expected), String.valueOf(actual), pass ? "1" : "0"));
    }
}
