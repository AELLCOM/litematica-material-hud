package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Дамп J — блоки, чьё лицо задаёт взгляд игрока ({@code getNearestLookingDirection}):
 * наблюдатель, поршень, раздатчик, бочка; печь — контроль (её лицо берётся из
 * {@code getHorizontalDirection}). В отличие от дампа C клик идёт всем конвейером: Litematica
 * {@code handleEasyPlace} → {@code PlacementGuard} → пакеты на встроенный сервер, протокол
 * Easy Place выключен, как на обычном сервере. Игрок подходит к каждой клетке с одной из
 * четырёх сторон, так что настоящий взгляд почти всегда не тот, что нужен блоку, — работу
 * делает подмена позы. Каждый вариант (сторона × «Поза на тик вперёд») — своё размещение
 * схемы, чтобы клетки не делили кеши Litematica и мода. Итог — {@code look.csv}.
 */
@Environment(EnvType.CLIENT)
public final class DumpLook implements CovJob
{
    private static final String HEADER = "key,variant,block,expected,actual,ok,player_yaw";
    private static final int Y = 100;
    private static final BlockPos REF = new BlockPos(300, Y, 0);
    private static final int COLS = 7;
    private static final int STEP = 4;
    private static final int AWAY = 2;
    private static final long SETTLE_MS = 600L;
    private static final long PER_CELL_MS = 4000L;
    private static final long READ_MS = 400L;

    private enum Phase { BUILD, SNAPSHOT, SURVIVAL, VARIANT, GHOSTS, CELL, DONE }

    private record Variant(String name, Direction side, boolean poseAhead, BlockPos origin) {}

    private CovOut out;
    private Phase phase = Phase.BUILD;
    private long until;
    private final List<BlockState> sample = sample();
    private final List<Variant> variants = new ArrayList<>();
    private final List<SchematicPlacement> placements = new ArrayList<>();
    private final Map<String, int[]> tally = new LinkedHashMap<>();
    private int variantIndex;
    private int cellIndex;
    private int cellStage;
    private long cellDeadline;
    private long lastTick = Long.MIN_VALUE;
    private long ghostWait;
    private int ok;
    private int failed;
    private int skipped;

    /** Серверный зонд: что видит сервер в момент клика и на каждом своём тике, пока клетка в работе. */
    private static volatile boolean probing;
    private static boolean probeRegistered;
    private static String lastProbe = "";

    @Override
    public String name()
    {
        return "J (лицо по взгляду, весь конвейер)";
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
            java.nio.file.Files.deleteIfExists(CovOut.dir().resolve("look.csv"));
            this.out = CovOut.open("look.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("look.csv не открылся: " + e);
            return false;
        }

        registerProbe();
        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        LayerListConfigs.EP_NO_TURN.setBooleanValue(false);
        LayerListConfigs.EP_DEBUG.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        String only = System.getenv("LL_LOOK_ONLY");
        int k = 0;

        for (boolean ahead : new boolean[] { false, true })
        {
            for (Direction side : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST })
            {
                String name = side.getSerializedName() + (ahead ? "+ahead" : "");
                // Каждое размещение — в своих чанках: два размещения в одном чанке Litematica
                // догружает не всегда, и часть голограмм пропадала
                BlockPos origin = REF.offset(0, 0, 32 + 32 * k++);

                if (only == null || only.isBlank() || name.equals(only) || (only.equals("plain") && ! ahead)
                        || (only.equals("ahead") && ahead))
                {
                    this.variants.add(new Variant(name, side, ahead, origin));
                }
            }
        }

        server.execute(() -> {
            command(server, "forceload add 288 -16 336 288");
            command(server, "gamerule doDaylightCycle false");
            ServerLevel level = server.overworld();
            arena(level, REF);

            for (Variant variant : this.variants)
            {
                arena(level, variant.origin());
            }

            for (int i = 0; i < this.sample.size(); ++i)
            {
                level.setBlock(cell(REF, i), this.sample.get(i), Block.UPDATE_CLIENTS);
            }

            command(server, "tp @a " + (REF.getX() + 12.5D) + " " + Y + " " + (REF.getZ() - 3.5D) + " 0 30");
        });

        this.until = net.minecraft.util.Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = net.minecraft.util.Util.getMillis();

        if (now < this.until || mc.level == null || mc.player == null)
        {
            return false;
        }

        switch (this.phase)
        {
            case BUILD -> this.snapshot(mc);
            case SURVIVAL -> this.survival(mc);
            case VARIANT -> this.enterVariant(mc, now);
            case GHOSTS -> this.waitGhosts(mc, now);
            case CELL -> this.cell(mc, now);
            case SNAPSHOT, DONE -> {
                return this.phase == Phase.DONE;
            }
        }

        return this.phase == Phase.DONE;
    }

    @Override
    public void close()
    {
        for (SchematicPlacement placement : this.placements)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
        }

        for (Map.Entry<String, int[]> entry : this.tally.entrySet())
        {
            CovOut.log("[look] " + entry.getKey() + ": OK " + entry.getValue()[0] + ", FAIL " + entry.getValue()[1]);
        }

        CovOut.log("[look] итог: OK " + this.ok + ", FAIL " + this.failed + ", без голограммы " + this.skipped);

        if (this.out != null)
        {
            this.out.close();
        }
    }

    // ===== Фазы =====

    private void snapshot(Minecraft mc)
    {
        int seen = 0;

        for (int i = 0; i < this.sample.size(); ++i)
        {
            if (mc.level.getBlockState(cell(REF, i)).equals(this.sample.get(i)))
            {
                ++seen;
            }
        }

        if (seen < this.sample.size())
        {
            CovOut.log("[look] образец на клиенте: " + seen + "/" + this.sample.size() + ", жду");
            this.until = net.minecraft.util.Util.getMillis() + 500L;
            return;
        }

        AreaSelection area = new AreaSelection();
        area.setName("dev-look");
        BlockPos max = cell(REF, this.sample.size() - 1).offset(STEP * COLS, 0, 0);
        area.addSubRegionBox(new Box(REF, new BlockPos(max.getX(), Y, max.getZ()), "main"), false);
        area.setExplicitOrigin(REF);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

        if (schematic == null)
        {
            CovOut.log("[look] схема не снялась");
            this.phase = Phase.DONE;
            return;
        }

        for (Variant variant : this.variants)
        {
            SchematicPlacement placement = SchematicPlacement.createFor(schematic, variant.origin(),
                    "dev-look-" + variant.name(), true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
            this.placements.add(placement);
        }

        CovOut.log("[look] схема снята: " + this.sample.size() + " клеток, вариантов " + this.variants.size());
        this.phase = Phase.SURVIVAL;
        this.until = net.minecraft.util.Util.getMillis() + 500L;
    }

    private void survival(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        server.execute(() -> {
            command(server, "gamemode survival @a");
            command(server, "clear @a");

            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                player.getInventory().add(new ItemStack(Items.OBSERVER, 64));
                player.getInventory().add(new ItemStack(Items.PISTON, 64));
                player.getInventory().add(new ItemStack(Items.DISPENSER, 64));
                player.getInventory().add(new ItemStack(Items.BARREL, 64));
                player.getInventory().add(new ItemStack(Items.FURNACE, 64));
            }
        });

        this.variantIndex = 0;
        this.phase = Phase.VARIANT;
        this.until = net.minecraft.util.Util.getMillis() + 1000L;
    }

    private void enterVariant(Minecraft mc, long now)
    {
        if (this.variantIndex >= this.variants.size())
        {
            this.phase = Phase.DONE;
            return;
        }

        Variant variant = this.variants.get(this.variantIndex);
        LayerListConfigs.EP_POSE_AHEAD.setBooleanValue(variant.poseAhead());
        CovOut.log("[look] === вариант " + variant.name() + " ===");

        Vec3 spot = spot(variant, 0);
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %d %.2f", spot.x, Y, spot.z)));

        this.ghostWait = now + 20_000L;
        this.phase = Phase.GHOSTS;
        this.until = now + 1000L;
    }

    private void waitGhosts(Minecraft mc, long now)
    {
        Variant variant = this.variants.get(this.variantIndex);
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        int seen = 0;

        for (int i = 0; i < this.sample.size(); ++i)
        {
            if (world != null && world.getBlockState(cell(variant.origin(), i)).equals(this.sample.get(i)))
            {
                ++seen;
            }
        }

        if (seen < this.sample.size() && now < this.ghostWait)
        {
            this.until = now + 250L;
            return;
        }

        if (seen < this.sample.size())
        {
            CovOut.log("[look] голограмм " + seen + "/" + this.sample.size() + " — иду дальше как есть");
        }

        this.cellIndex = 0;
        this.cellStage = 0;
        this.phase = Phase.CELL;
    }

    /** Одна клетка: встать сбоку, смотреть на голограмму, жать Easy Place раз в тик, прочитать сервер. */
    private void cell(Minecraft mc, long now)
    {
        Variant variant = this.variants.get(this.variantIndex);

        if (this.cellIndex >= this.sample.size())
        {
            ++this.variantIndex;
            this.phase = Phase.VARIANT;
            return;
        }

        BlockPos pos = cell(variant.origin(), this.cellIndex);
        BlockState want = this.sample.get(this.cellIndex);
        MinecraftServer server = mc.getSingleplayerServer();

        switch (this.cellStage)
        {
            case 0 -> {
                WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

                // Голограмма не догрузилась (мир схемы строится в фоне) — кликать не по чему.
                // Это не провал мода: клетку пропускаем и считаем отдельно.
                if (world == null || ! world.getBlockState(pos).equals(want))
                {
                    ++this.skipped;
                    CovOut.log("[look] SKIP " + variant.name() + ": " + label(want) + " — голограммы нет");
                    ++this.cellIndex;
                    return;
                }

                Vec3 spot = spot(variant, this.cellIndex);
                float[] angles = angles(spot.add(0.0D, mc.player.getEyeHeight(), 0.0D), Vec3.atCenterOf(pos));
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.2f %.2f",
                        spot.x, Y, spot.z, angles[0], angles[1])));
                this.cellStage = 1;
                this.cellDeadline = now + SETTLE_MS + PER_CELL_MS;
                this.until = now + SETTLE_MS;
            }
            case 1 -> {
                probing = true;

                if (! mc.level.getBlockState(pos).isAir() || now > this.cellDeadline)
                {
                    // Зонд не гасим: клиент видит блок предсказанием раньше, чем сервер разберёт клик
                    this.cellStage = 2;
                    this.until = now + READ_MS;
                    return;
                }

                long tick = mc.level.getGameTime();

                if (tick == this.lastTick)
                {
                    return;
                }

                this.lastTick = tick;
                CovOut.log(String.format(Locale.ROOT, "[look] клиент: тик игрока %d, жму Easy Place", mc.player.tickCount));
                float[] angles = angles(mc.player.getEyePosition(1.0F), Vec3.atCenterOf(pos));
                mc.player.setYRot(angles[0]);
                mc.player.setXRot(angles[1]);
                mc.player.setYHeadRot(angles[0]);
                WorldUtils.handleEasyPlace(mc);
            }
            default -> {
                probing = false;
                BlockState have = server.overworld().getBlockState(pos);
                boolean pass = have.getBlock() == want.getBlock() && facing(have).equals(facing(want));
                this.record(variant, want, have, pass, mc.player.getYRot());
                ++this.cellIndex;
                this.cellStage = 0;
            }
        }
    }

    // ===== Помощники =====

    private static void registerProbe()
    {
        if (probeRegistered)
        {
            return;
        }

        probeRegistered = true;

        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
        {
            if (probing && ! level.isClientSide())
            {
                CovOut.log(String.format(Locale.ROOT, "[look] сервер: клик на тике %d, yRot %.1f, голова %.1f, xRot %.1f",
                        level.getServer().getTickCount(), player.getYRot(), player.getYHeadRot(), player.getXRot()));
            }

            return InteractionResult.PASS;
        });

        ServerTickEvents.START_SERVER_TICK.register(server ->
        {
            if (! probing)
            {
                return;
            }

            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                String now = String.format(Locale.ROOT, "yRot %.1f, голова %.1f, xRot %.1f", player.getYRot(),
                        player.getYHeadRot(), player.getXRot());

                if (! now.equals(lastProbe))
                {
                    lastProbe = now;
                    CovOut.log("[look] сервер: начало тика " + server.getTickCount() + ": " + now);
                }
            }
        });
    }

    private static List<BlockState> sample()
    {
        List<BlockState> out = new ArrayList<>();

        for (Direction direction : Direction.values())
        {
            out.add(Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, direction));
        }

        for (Direction direction : Direction.values())
        {
            out.add(Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, direction));
        }

        out.add(Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
        out.add(Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
        out.add(Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        out.add(Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
        out.add(Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN));

        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            out.add(Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, direction));
        }

        return out;
    }

    private static BlockPos cell(BlockPos origin, int index)
    {
        return origin.offset((index % COLS) * STEP, 0, (index / COLS) * STEP);
    }

    private static Vec3 spot(Variant variant, int index)
    {
        BlockPos pos = cell(variant.origin(), index);

        return new Vec3(pos.getX() + 0.5D + variant.side().getStepX() * AWAY, Y,
                pos.getZ() + 0.5D + variant.side().getStepZ() * AWAY);
    }

    private static float[] angles(Vec3 eyes, Vec3 point)
    {
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        return new float[] { yaw, pitch };
    }

    /** Пол под площадкой и местами, где стоит игрок, и пустой воздух над ним. */
    private static void arena(ServerLevel level, BlockPos origin)
    {
        for (int x = -4; x <= COLS * STEP; ++x)
        {
            for (int z = -4; z <= 12; ++z)
            {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);

                for (int y = 0; y <= 3; ++y)
                {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static String facing(BlockState state)
    {
        if (state.hasProperty(BlockStateProperties.FACING))
        {
            return state.getValue(BlockStateProperties.FACING).getSerializedName();
        }

        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
        {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getSerializedName();
        }

        return "-";
    }

    private static String label(BlockState state)
    {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                + (state.isAir() ? "" : "[" + facing(state) + "]");
    }

    private void record(Variant variant, BlockState want, BlockState have, boolean pass, float yaw)
    {
        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        String kind = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(want.getBlock()).getPath();
        int[] row = this.tally.computeIfAbsent((variant.poseAhead() ? "ahead " : "plain ") + kind, k -> new int[2]);
        ++row[pass ? 0 : 1];

        CovOut.log("[look] " + (pass ? "OK  " : "FAIL") + " " + variant.name() + ": " + label(want) + " → "
                + label(have) + String.format(Locale.ROOT, " (взгляд игрока %.0f°)", yaw));
        this.out.row(List.of(variant.name() + ":" + label(want), variant.name(), label(want), label(want), label(have),
                pass ? "1" : "0", String.format(Locale.ROOT, "%.1f", yaw)));
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
}
