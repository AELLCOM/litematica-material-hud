package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.Controllable;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * Дамп R — редстоун через весь конвейер Easy Place, как у игрока: выживание, протокол Easy Place
 * выключен (обычный сервер). Образец строится командами (с обновлениями, схема сама досчитывает
 * питание), с него снимается схема и кладётся на такой же пол. Дальше каждая клетка ставится
 * Easy Place: опоры, потом остальное по рядам. После каждой клетки — сверка того, что задаёт
 * установка; в конце, когда схема досчиталась, — полная сверка и поиск лишних блоков в клетках,
 * где схема пуста.
 *
 * <p>Ряды (z): 1–2 — рычаг → провод → повторитель → провод → компаратор → провод → лампа, подъём
 * провода на блок; 4–5 — факел, провод с углом и тройником, провод-точка, настенный факел, провод
 * на блоке; 7–8 — повторители на 4 стороны с задержками 1–4, компараторы в обоих режимах,
 * наблюдатели; 10–11 — поршни, кнопки, рычаг на стене, воронка в выбрасыватель, нотный блок,
 * датчик света, мишень. Игрок стоит в ряду за рядом (z + 2 от начала ряда).
 */
@Environment(EnvType.CLIENT)
public final class DumpRedstone implements CovJob
{
    private static final BlockPos REF = new BlockPos(420, 100, 0);
    private static final BlockPos TARGET = new BlockPos(420, 100, 40);
    private static final int SIZE_X = 15;
    private static final int SIZE_Z = 13;
    private static final long PER_CELL_MS = 8000L;

    private enum Phase { BUILD, SNAPSHOT, GHOSTS, SURVIVAL, PLACE, SETTLE, VERIFY, DONE }

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

    @Override
    public String name()
    {
        return "R (редстоун)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        LayerListConfigs.EP_DEBUG.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 410 -10 440 60");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                fill(server, o.offset(-2, -1, -2), o.offset(SIZE_X + 1, -1, SIZE_Z + 1), "minecraft:stone");
                fill(server, o.offset(-2, 0, -2), o.offset(SIZE_X + 1, 4, SIZE_Z + 1), "minecraft:air");
            }

            build(server, REF);
            command(server, "tp @a " + (REF.getX() + 7.5D) + " " + REF.getY() + " " + (REF.getZ() + 14.5D));
        });

        this.until = Util.getMillis() + 5000L;
        return true;
    }

    /** Образец. Опоры раньше всего, что на них держится; рычаг включается последним. */
    private static void build(MinecraftServer server, BlockPos o)
    {
        // Ряд 1–2: рычаг → провод → повторитель (3) → провод → компаратор (вычитание) → провод → лампа
        set(server, o, 1, 0, 1, "redstone_wire");
        set(server, o, 2, 0, 1, "redstone_wire");
        set(server, o, 3, 0, 1, "redstone_wire");
        set(server, o, 4, 0, 1, "repeater[facing=west,delay=3]");
        set(server, o, 5, 0, 1, "redstone_wire");
        set(server, o, 6, 0, 1, "redstone_wire");
        set(server, o, 7, 0, 1, "comparator[facing=west,mode=subtract]");
        set(server, o, 8, 0, 1, "redstone_wire");
        set(server, o, 9, 0, 1, "redstone_lamp");
        // Подъём провода на блок и спуск с него
        set(server, o, 11, 0, 1, "redstone_wire");
        set(server, o, 12, 0, 1, "stone");
        set(server, o, 12, 1, 1, "redstone_wire");
        set(server, o, 13, 0, 1, "redstone_wire");

        // Ряд 4–5: факел → провод с углом и тройником; точка; настенный факел; провод на блоке
        set(server, o, 0, 0, 4, "redstone_torch");
        set(server, o, 1, 0, 4, "redstone_wire");
        set(server, o, 2, 0, 4, "redstone_wire");
        set(server, o, 3, 0, 4, "redstone_wire");
        set(server, o, 2, 0, 5, "redstone_wire");
        set(server, o, 6, 0, 4, "redstone_wire[north=none,east=none,south=none,west=none]");
        set(server, o, 9, 0, 4, "stone");
        set(server, o, 9, 0, 5, "redstone_wall_torch[facing=south]");
        set(server, o, 12, 0, 4, "stone");
        set(server, o, 12, 1, 4, "redstone_wire");

        // Ряд 7–8: повторители на 4 стороны, компараторы, наблюдатели
        set(server, o, 1, 0, 7, "repeater[facing=north,delay=2]");
        set(server, o, 3, 0, 7, "repeater[facing=east,delay=4]");
        set(server, o, 5, 0, 7, "repeater[facing=south,delay=1]");
        set(server, o, 7, 0, 7, "repeater[facing=west,delay=3]");
        set(server, o, 9, 0, 7, "comparator[facing=north,mode=compare]");
        set(server, o, 11, 0, 7, "comparator[facing=south,mode=subtract]");
        set(server, o, 13, 0, 7, "observer[facing=up]");
        set(server, o, 13, 0, 8, "observer[facing=west]");

        // Ряд 10–11: поршни, кнопки, рычаг на стене, воронка → выбрасыватель, нотный блок, датчик, мишень
        set(server, o, 1, 0, 10, "piston[facing=up]");
        set(server, o, 3, 0, 10, "sticky_piston[facing=north]");
        set(server, o, 5, 0, 10, "stone");
        set(server, o, 5, 0, 11, "stone_button[face=wall,facing=south]");
        set(server, o, 7, 0, 10, "stone_button[face=floor,facing=north]");
        set(server, o, 9, 0, 10, "stone");
        set(server, o, 9, 0, 11, "lever[face=wall,facing=south]");
        set(server, o, 11, 0, 10, "hopper[facing=east]");
        set(server, o, 12, 0, 10, "dropper[facing=up]");
        set(server, o, 13, 0, 10, "note_block[note=7]");
        set(server, o, 11, 0, 11, "daylight_detector[inverted=true]");
        set(server, o, 13, 0, 11, "target");

        set(server, o, 0, 0, 1, "lever[face=floor,facing=east,powered=true]");
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
            case SURVIVAL -> this.survival(mc);
            case PLACE -> this.place(mc, now);
            case SETTLE ->
            {
                this.phase = Phase.VERIFY;
                this.until = now + 4000L;
            }
            case VERIFY -> this.verify(mc);
            case DONE -> { return true; }
        }

        return this.phase == Phase.DONE;
    }

    private void snapshot(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName("dev-redstone");
        BlockPos min = REF.offset(0, -1, 0);
        area.addSubRegionBox(new Box(min, REF.offset(SIZE_X - 1, 2, SIZE_Z - 1), "main"), false);
        area.setExplicitOrigin(min);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);

        if (schematic == null)
        {
            this.record("build", "схема снята", 1, 0);
            this.phase = Phase.DONE;
            return;
        }

        this.placement = SchematicPlacement.createFor(schematic, TARGET.offset(0, -1, 0), "dev-redstone", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        this.phase = Phase.GHOSTS;
        this.until = Util.getMillis() + 1000L;
    }

    private void waitGhosts(long now)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        boolean ready = world != null && world.getBlockState(TARGET.offset(4, 0, 1)).is(Blocks.REPEATER)
                && world.getBlockState(TARGET.offset(13, 0, 11)).is(Blocks.TARGET);

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
        CovOut.log("[redstone] клеток к установке: " + this.cells.size());

        for (BlockPos cell : this.cells)
        {
            CovOut.log("[redstone]   схема " + describe(world.getBlockState(cell), cell));
        }

        this.phase = Phase.SURVIVAL;
        this.until = now + 500L;
    }

    private void survival(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        server.execute(() -> {
            command(server, "gamemode survival @a");
            command(server, "clear @a");
            command(server, "effect give @a minecraft:saturation 600 1 true");

            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                Inventory inventory = player.getInventory();
                inventory.add(new ItemStack(Items.REDSTONE, 64));
                inventory.add(new ItemStack(Items.REPEATER, 16));
                inventory.add(new ItemStack(Items.COMPARATOR, 16));
                inventory.add(new ItemStack(Items.STONE, 32));
                inventory.add(new ItemStack(Items.REDSTONE_TORCH, 16));
                inventory.add(new ItemStack(Items.LEVER, 8));
                inventory.add(new ItemStack(Items.OBSERVER, 8));
                inventory.add(new ItemStack(Items.REDSTONE_LAMP, 4));
                inventory.add(new ItemStack(Items.PISTON, 4));
                inventory.add(new ItemStack(Items.STICKY_PISTON, 4));
                inventory.add(new ItemStack(Items.STONE_BUTTON, 8));
                inventory.add(new ItemStack(Items.HOPPER, 4));
                inventory.add(new ItemStack(Items.DROPPER, 4));
                inventory.add(new ItemStack(Items.NOTE_BLOCK, 4));
                inventory.add(new ItemStack(Items.DAYLIGHT_DETECTOR, 4));
                inventory.add(new ItemStack(Items.TARGET, 4));
            }
        });

        this.cellIndex = 0;
        this.positioned = false;
        this.phase = Phase.PLACE;
        this.until = Util.getMillis() + 1500L;
    }

    private void place(Minecraft mc, long now)
    {
        if (this.cellIndex >= this.cells.size())
        {
            this.phase = Phase.SETTLE;
            this.until = now + 500L;
            return;
        }

        BlockPos cell = this.cells.get(this.cellIndex);
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        BlockState wanted = world.getBlockState(cell);

        if (! this.positioned)
        {
            Vec3 spot = standingSpot(world, cell);
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %d %.2f", spot.x, TARGET.getY(), spot.z)));
            this.positioned = true;
            this.cellDeadline = now + PER_CELL_MS;
            this.until = now + 400L;
            return;
        }

        String problem = placedProblem(wanted, mc.level.getBlockState(cell));

        if (problem == null || now > this.cellDeadline)
        {
            this.record("place", describe(wanted, cell), "встал", problem == null ? "встал" : problem);
            ++this.cellIndex;
            this.positioned = false;
            this.until = now + 300L;
            return;
        }

        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        look(mc, aimPoint(world, wanted, cell));
        // Каждый тик, как игрок с зажатой ПКМ: угол головы для наблюдателя и поршня мод подставляет
        // сам и ждёт, пока сервер его усвоит, — сверять «отправлен ли угол» здесь нельзя
        WorldUtils.handleEasyPlace(mc);
    }

    /** Итог: полное состояние каждой клетки и лишние блоки там, где схема пуста. */
    private void verify(Minecraft mc)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        for (BlockPos cell : this.cells)
        {
            BlockState wanted = world.getBlockState(cell);
            BlockState have = mc.level.getBlockState(cell);
            this.record("verify", describe(wanted, cell), "как в схеме", have == wanted ? "как в схеме" : diff(wanted, have));
        }

        int extra = 0;

        for (int x = -2; x <= SIZE_X + 1; ++x)
        {
            for (int z = -2; z <= SIZE_Z + 1; ++z)
            {
                for (int y = 0; y <= 3; ++y)
                {
                    BlockPos pos = TARGET.offset(x, y, z);
                    BlockState have = mc.level.getBlockState(pos);
                    boolean inside = x >= 0 && x < SIZE_X && z >= 0 && z < SIZE_Z && y <= 2;
                    BlockState wanted = inside ? world.getBlockState(pos) : Blocks.AIR.defaultBlockState();

                    if (wanted.isAir() && ! have.isAir())
                    {
                        ++extra;
                        this.record("verify", "лишний блок @" + x + "/" + y + "/" + z, "пусто", name(have));
                    }
                }
            }
        }

        CovOut.log("[redstone] лишних блоков: " + extra);
        CovOut.log("[redstone] итог: OK " + this.ok + ", FAIL " + this.failed);
        this.phase = Phase.DONE;
    }

    /** Что не так из того, что задаёт установка (питание, соединения и прочее производное не в счёт). */
    private static @org.jetbrains.annotations.Nullable String placedProblem(BlockState wanted, BlockState have)
    {
        if (! have.is(wanted.getBlock()))
        {
            return "в мире " + name(have);
        }

        StringBuilder out = new StringBuilder();

        for (Property<?> property : wanted.getProperties())
        {
            boolean derived = (Controllable.notAtPlacement(property) || Controllable.side(property))
                    && ! property.getName().equals("delay") && ! property.getName().equals("mode")
                    && ! property.getName().equals("note") && ! property.getName().equals("inverted");

            if (! derived && ! wanted.getValue(property).equals(have.getValue(property)))
            {
                out.append(property.getName()).append('=').append(have.getValue(property)).append(" (надо ")
                        .append(wanted.getValue(property)).append(") ");
            }
        }

        return out.isEmpty() ? null : out.toString().trim();
    }

    private static String diff(BlockState wanted, BlockState have)
    {
        if (! have.is(wanted.getBlock()))
        {
            return "в мире " + name(have);
        }

        StringBuilder out = new StringBuilder();

        for (Property<?> property : wanted.getProperties())
        {
            if (! wanted.getValue(property).equals(have.getValue(property)))
            {
                out.append(property.getName()).append('=').append(have.getValue(property)).append(" (надо ")
                        .append(wanted.getValue(property)).append(") ");
            }
        }

        return out.toString().trim();
    }

    /** Клетки цели: сначала камень-опоры, потом остальное по рядам, снизу вверх. */
    private static List<BlockPos> targetCells(WorldSchematic world)
    {
        List<BlockPos> supports = new ArrayList<>();
        List<BlockPos> rest = new ArrayList<>();

        for (int z = 0; z < SIZE_Z; ++z)
        {
            for (int x = 0; x < SIZE_X; ++x)
            {
                for (int y = 0; y <= 2; ++y)
                {
                    BlockPos pos = TARGET.offset(x, y, z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir())
                    {
                        continue;
                    }

                    (state.is(Blocks.STONE) ? supports : rest).add(pos);
                }
            }
        }

        supports.addAll(rest);
        return supports;
    }

    /**
     * Где стоять: в двух клетках от цели с той стороны, где между игроком и целью нет других
     * клеток схемы (иначе луч Litematica упрётся в их голограмму). Сначала с юга, как стоит
     * игрок перед рядом, потом с остальных сторон.
     */
    private static Vec3 standingSpot(WorldSchematic world, BlockPos cell)
    {
        BlockPos ground = new BlockPos(cell.getX(), TARGET.getY(), cell.getZ());

        for (net.minecraft.core.Direction side : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST})
        {
            BlockPos near = ground.relative(side);
            BlockPos stand = ground.relative(side, 2);
            int rx = stand.getX() - TARGET.getX();
            int rz = stand.getZ() - TARGET.getZ();

            if (rx < -2 || rx > SIZE_X + 1 || rz < -2 || rz > SIZE_Z + 1)
            {
                continue;
            }

            if (free(world, near) && free(world, stand))
            {
                return new Vec3(stand.getX() + 0.5D, TARGET.getY(), stand.getZ() + 0.5D);
            }
        }

        return new Vec3(cell.getX() + 0.5D, TARGET.getY(), cell.getZ() + 2.5D);
    }

    private static boolean free(WorldSchematic world, BlockPos ground)
    {
        return world.getBlockState(ground).isAir() && world.getBlockState(ground.above()).isAir();
    }

    /**
     * Куда смотреть: в середину контура голограммы — луч Litematica ловит клетку по контуру, и
     * игрок целится в сам предмет (факел на стене, кнопку), а не в центр клетки. У провода —
     * в его слой на полу: стенка подъёма в контур входит, но в неё не целятся.
     */
    private static Vec3 aimPoint(WorldSchematic world, BlockState wanted, BlockPos cell)
    {
        if (wanted.getBlock() instanceof net.minecraft.world.level.block.RedstoneWireBlock)
        {
            return new Vec3(cell.getX() + 0.5D, cell.getY() + 0.03D, cell.getZ() + 0.5D);
        }

        var shape = wanted.getShape(world, cell);

        return shape.isEmpty() ? Vec3.atCenterOf(cell) : shape.bounds().getCenter().add(cell.getX(), cell.getY(), cell.getZ());
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

    private static void set(MinecraftServer server, BlockPos o, int x, int y, int z, String block)
    {
        command(server, "setblock " + (o.getX() + x) + " " + (o.getY() + y) + " " + (o.getZ() + z) + " minecraft:" + block);
    }

    private static void fill(MinecraftServer server, BlockPos a, BlockPos b, String block)
    {
        command(server, "fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " "
                + b.getZ() + " " + block);
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

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[redstone] команда не прошла (" + command + "): " + e);
        }
    }

    private void record(String phase, String subject, Object expected, Object actual)
    {
        boolean pass = String.valueOf(expected).equals(String.valueOf(actual));

        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        CovOut.log("[redstone] " + (pass ? "OK  " : "FAIL") + " " + phase + ": " + subject + " — ждали " + expected
                + ", вышло " + actual);
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
