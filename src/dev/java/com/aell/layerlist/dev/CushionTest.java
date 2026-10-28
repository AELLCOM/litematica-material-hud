package com.aell.layerlist.dev;

import com.aell.layerlist.core.SchematicEntities;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code cushion}: подушки через Easy Place (жалоба 10.10.2026: «Изиплейс не ставит подушку» — на снегу в 8 и
 * 7 слоёв «сервер трижды не принял установку»).
 *
 * <p>Часть А — «Тестовая схема» пользователя: все её блоки ставит сервер, подушки (на снегу в 1–8 слоёв, на люке, в
 * клетках забора и калитки) ставит Easy Place в выживании. Часть Б — образец рядом: подушка в котле, на его краю, в
 * воронке и в компостере, стойка на снегу в 8 слоёв без соседей; с образца снимается схема и кладётся на такие же
 * блоки. В котёл, воронку и компостер сервер кладёт подушку по лучу от глаз игрока, поэтому их пробуем с нескольких
 * точек: низко сбоку (дна может быть не видно — тогда не ставить и ничего не ставить мимо), выше сбоку, сверху.
 *
 * <p>Итог — каждая сущность на месте голограммы (цвет, сторона, поворот) и ни одной лишней рядом.
 */
@Environment(EnvType.CLIENT)
public final class CushionTest implements CovJob
{
    private static final BlockPos ORIGIN = new BlockPos(1000, 100, 40);
    private static final BlockPos REF = new BlockPos(1000, 100, 100);
    private static final BlockPos TARGET = new BlockPos(1000, 100, 120);
    private static final int USER_CUSHIONS = 11;
    private static final int SAMPLE_ENTITIES = 5;
    private static final long SPOT_MS = 4500L;
    private static final double[] FRACTIONS = {0.5D, 0.3D, 0.7D, 0.15D, 0.85D};

    private enum Stage { WAIT_A, SNAPSHOT, WAIT_B, PLACE, VERIFY, DONE }

    /** Голограмма и точки (ноги игрока), откуда её ставить по очереди. */
    private record Case(String name, Entity ghost, List<Vec3> spots) {}

    private Stage stage = Stage.WAIT_A;
    private long nextAt;
    private int waits;
    private boolean summoned;
    private SchematicPlacement user;
    private SchematicPlacement sample;
    private BlockPos min;
    private BlockPos max;
    private final List<Case> cases = new ArrayList<>();
    private int index;
    private int spot = -1;
    private long spotEnd;
    private long lastTick = Long.MIN_VALUE;
    private int ok;
    private int failed;

    @Override
    public String name()
    {
        return "CUSHION (подушки и стойка через Easy Place)";
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
        BuildTest.applyProfile("user");
        CovOut.log("[cushion] Easy Place ведёт "
                + (FabricLoader.getInstance().isModLoaded("easyplacefixplus") ? "Easy Place Fix+" : "Material HUD"));

        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Тестовая схема.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("[cushion] схема не загрузилась: " + path);
            return false;
        }

        this.user = SchematicPlacement.createFor(schematic, ORIGIN, "dev-cushion", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.user, false);
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        for (Box box : this.user.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
        {
            for (BlockPos p : new BlockPos[]{box.getPos1(), box.getPos2()})
            {
                lo[0] = Math.min(lo[0], p.getX());
                lo[1] = Math.min(lo[1], p.getY());
                lo[2] = Math.min(lo[2], p.getZ());
                hi[0] = Math.max(hi[0], p.getX());
                hi[1] = Math.max(hi[1], p.getY());
                hi[2] = Math.max(hi[2], p.getZ());
            }
        }

        this.min = new BlockPos(lo[0], lo[1], lo[2]);
        this.max = new BlockPos(hi[0], hi[1], hi[2]);
        BlockPos a = this.min.offset(-6, 0, -6);
        BlockPos b = this.max.offset(6, 6, 6);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", a.getX(), a.getZ(), b.getX(), b.getZ()));
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", REF.getX() - 4, REF.getZ() - 4,
                    REF.getX() + 20, TARGET.getZ() + 8));
            command(server, "gamerule advance_time false");
            command(server, "gamerule random_tick_speed 0");
            command(server, "time set noon");
            ServerLevel level = server.overworld();
            fill(level, a.below(), new BlockPos(b.getX(), this.min.getY() - 1, b.getZ()), Blocks.STONE.defaultBlockState());
            fill(level, new BlockPos(a.getX(), this.min.getY(), a.getZ()), b, Blocks.AIR.defaultBlockState());
            sampleBlocks(level, REF);
            sampleBlocks(level, TARGET);
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", this.min.getX() - 4.0D, this.min.getY() + 2,
                    (this.min.getZ() + this.max.getZ()) / 2.0D));
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

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (this.stage.ordinal() >= Stage.PLACE.ordinal())
        {
            fly(mc);
        }

        switch (this.stage)
        {
            case WAIT_A ->
            {
                if (! this.summoned)
                {
                    // Сущности прошлых прогонов к этому времени подгрузились вместе с чанками — убираем и ставим образец
                    this.summoned = true;
                    server.execute(() -> {
                        for (AABB box : List.of(this.areaA(), sampleArea(REF), sampleArea(TARGET)))
                        {
                            for (String type : List.of("cushion", "armor_stand"))
                            {
                                command(server, String.format(Locale.ROOT, "kill @e[type=minecraft:%s,x=%.1f,y=%.1f,z=%.1f,dx=%.1f,dy=%.1f,dz=%.1f]",
                                        type, box.minX, box.minY, box.minZ, box.getXsize(), box.getYsize(), box.getZsize()));
                            }
                        }

                        summonSample(server);
                    });
                    this.nextAt = now + 1500L;
                    return false;
                }

                List<Entity> ghosts = world == null ? List.of() : ghosts(world, this.areaA(), e -> e instanceof Cushion);

                if (ghosts.size() < USER_CUSHIONS)
                {
                    if (++this.waits > 60)
                    {
                        CovOut.log("[cushion] голограмм подушек в схеме " + ghosts.size() + " из " + USER_CUSHIONS);
                        return true;
                    }

                    this.nextAt = now + 500L;
                    return false;
                }

                // Все блоки схемы — сервером, как есть: остаются только сущности
                List<BlockPos> cells = new ArrayList<>();
                List<BlockState> states = new ArrayList<>();

                for (BlockPos p : BlockPos.betweenClosed(this.min, this.max))
                {
                    BlockState state = world.getBlockState(p);

                    if (! state.isAir())
                    {
                        cells.add(p.immutable());
                        states.add(state);
                    }
                }

                server.execute(() -> {
                    ServerLevel level = server.overworld();

                    for (int i = 0; i < cells.size(); ++i)
                    {
                        level.setBlock(cells.get(i), states.get(i), 2);
                    }
                });

                CovOut.log("[cushion] блоков схемы поставлено сервером: " + cells.size() + ", голограмм подушек " + ghosts.size());
                ghosts.sort(Comparator.comparingDouble(e -> e.position().z));

                for (Entity ghost : ghosts)
                {
                    this.cases.add(new Case("A " + describe(ghost), ghost, new ArrayList<>()));
                }

                this.waits = 0;
                this.stage = Stage.SNAPSHOT;
                this.nextAt = now + 2500L;
            }
            case SNAPSHOT ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-cushion-sample");
                area.addSubRegionBox(new Box(REF.offset(-1, -1, -1), REF.offset(15, 3, 3), "main"), false);
                area.setExplicitOrigin(REF.offset(-1, -1, -1));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

                if (schematic == null)
                {
                    CovOut.log("[cushion] образец не снялся");
                    return true;
                }

                this.sample = SchematicPlacement.createFor(schematic, TARGET.offset(-1, -1, -1), "dev-cushion-sample", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.sample, false);
                this.stage = Stage.WAIT_B;
                this.nextAt = now + 1000L;
            }
            case WAIT_B ->
            {
                List<Entity> ghosts = world == null ? List.of()
                        : ghosts(world, sampleArea(TARGET), e -> e instanceof Cushion || e instanceof ArmorStand);

                if (ghosts.size() < SAMPLE_ENTITIES && ++this.waits <= 60)
                {
                    this.nextAt = now + 500L;
                    return false;
                }

                CovOut.log("[cushion] голограмм образца " + ghosts.size() + " из " + SAMPLE_ENTITIES);
                ghosts.sort(Comparator.comparingDouble(e -> e.position().x));

                for (Entity ghost : ghosts)
                {
                    BlockPos under = BlockPos.containing(ghost.position().x, ghost.position().y - 1.0E-3D, ghost.position().z);
                    Vec3 c = Vec3.atBottomCenterOf(under);
                    List<Vec3> spots = new ArrayList<>();

                    if (ghost instanceof ArmorStand)
                    {
                        spots.add(c.add(0.0D, 0.5D, -2.2D));
                        spots.add(c.add(-2.2D, 0.5D, 0.0D));
                    }
                    else if (ghost.position().y - under.getY() > 0.99D)
                    {
                        spots.add(c.add(0.0D, 1.0D, -1.5D));
                    }
                    else
                    {
                        spots.add(c.add(0.0D, 0.0D, -1.2D));
                        spots.add(c.add(0.0D, 1.0D, -1.5D));
                        spots.add(c.add(0.0D, 2.2D, -0.35D));
                    }

                    this.cases.add(new Case("Б " + describe(ghost), ghost, spots));
                }

                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 100000 1 true");

                    for (ServerPlayer player : server.getPlayerList().getPlayers())
                    {
                        player.getInventory().add(new ItemStack(Items.CUSHION.pick(DyeColor.GREEN), USER_CUSHIONS));

                        for (DyeColor color : List.of(DyeColor.RED, DyeColor.BLUE, DyeColor.YELLOW, DyeColor.WHITE))
                        {
                            player.getInventory().add(new ItemStack(Items.CUSHION.pick(color), 2));
                        }

                        player.getInventory().add(new ItemStack(Items.ARMOR_STAND, 2));
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.getAbilities().invulnerable = true;
                        player.onUpdateAbilities();
                    }
                });

                this.stage = Stage.PLACE;
                this.nextAt = now + 1500L;
            }
            case PLACE -> this.place(mc, now);
            case VERIFY ->
            {
                this.verify(mc.level);
                this.stage = Stage.DONE;
            }
            case DONE ->
            {
                return true;
            }
        }

        return this.stage == Stage.DONE;
    }

    /** По голограмме: встать в точку, смотреть на видимую часть голограммы и жать Easy Place раз в тик. */
    private void place(Minecraft mc, long now)
    {
        if (this.index >= this.cases.size())
        {
            this.stage = Stage.VERIFY;
            this.nextAt = now + 1500L;
            return;
        }

        Case c = this.cases.get(this.index);

        if (this.spot < 0)
        {
            if (c.spots().isEmpty())
            {
                Vec3 feet = spotFor(mc.level, c.ghost());

                if (feet == null)
                {
                    CovOut.log("[cushion] " + c.name() + ": неоткуда поставить");
                    ++this.index;
                    return;
                }

                c.spots().add(feet);
            }

            this.goTo(mc, c, 0, now);
            return;
        }

        boolean placed = exact(mc.level, c.ghost()) != null;

        if (placed || now > this.spotEnd)
        {
            Vec3 feet = c.spots().get(this.spot);
            CovOut.log(String.format(Locale.ROOT, "[cushion] %s, точка %d (ноги %.2f %.2f %.2f): %s", c.name(), this.spot + 1,
                    feet.x, feet.y, feet.z, placed ? "встала" : "не встала"));

            for (Entity near : mc.level.getEntities((Entity) null, c.ghost().getBoundingBox().inflate(1.5D),
                    e -> e.getType() == c.ghost().getType() && e.position().distanceTo(c.ghost().position()) > 0.02D))
            {
                CovOut.log("[cushion]   рядом не на месте: " + describe(near));
            }

            if (! placed && this.spot + 1 < c.spots().size())
            {
                this.goTo(mc, c, this.spot + 1, now);
            }
            else
            {
                ++this.index;
                this.spot = -1;
                this.nextAt = now + 200L;
            }

            return;
        }

        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        Vec3 point = aimPoint(mc.level, mc.player.getEyePosition(1.0F), c.ghost());
        look(mc, point != null ? point : c.ghost().getBoundingBox().getCenter());
        WorldUtils.handleEasyPlace(mc);
    }

    private void goTo(Minecraft mc, Case c, int spot, long now)
    {
        this.spot = spot;
        Vec3 feet = c.spots().get(spot);
        MinecraftServer server = mc.getSingleplayerServer();
        Vec3 p = c.ghost().position();
        BlockPos under = BlockPos.containing(p.x, p.y - 1.0E-3D, p.z);
        CovOut.log("[cushion] " + c.name() + ": под ней у клиента " + mc.level.getBlockState(under) + ", ниже " + mc.level.getBlockState(under.below()));
        server.execute(() -> {
            CovOut.log("[cushion]   у сервера " + server.overworld().getBlockState(under) + ", ниже " + server.overworld().getBlockState(under.below()));
            command(server, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", feet.x, feet.y, feet.z));
        });
        this.spotEnd = now + 400L + SPOT_MS;
        this.nextAt = now + 400L;
    }

    /** Каждая голограмма — на месте и такая же; лишних подушек и стоек рядом нет. */
    private void verify(ClientLevel level)
    {
        for (Case c : this.cases)
        {
            Entity have = exact(level, c.ghost());
            this.record(c.name() + " на " + name(level, c.ghost()), "как в схеме", have == null ? "нет" : compare(c.ghost(), have));
        }

        for (AABB box : List.of(this.areaA(), sampleArea(TARGET)))
        {
            for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Cushion || e instanceof ArmorStand))
            {
                boolean wanted = this.cases.stream().anyMatch(c -> c.ghost().getType() == entity.getType()
                        && c.ghost().position().distanceTo(entity.position()) < 0.05D);

                if (! wanted && (entity instanceof Cushion || box != this.areaA()))
                {
                    this.record("лишняя " + describe(entity), "нет", "есть");
                }
            }
        }
    }

    // ===== Мир =====

    private static void sampleBlocks(ServerLevel level, BlockPos o)
    {
        fill(level, o.offset(-3, -1, -4), o.offset(17, -1, 5), Blocks.STONE.defaultBlockState());
        fill(level, o.offset(-3, 0, -4), o.offset(17, 5, 5), Blocks.AIR.defaultBlockState());
        level.setBlock(o.offset(1, 0, 1), Blocks.CAULDRON.defaultBlockState(), 2);
        level.setBlock(o.offset(4, 0, 1), Blocks.CAULDRON.defaultBlockState(), 2);
        level.setBlock(o.offset(7, 0, 1), Blocks.HOPPER.defaultBlockState(), 2);
        level.setBlock(o.offset(10, 0, 1), Blocks.COMPOSTER.defaultBlockState(), 2);
        level.setBlock(o.offset(13, 0, 1), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8), 2);
    }

    /** Подушки там, куда их кладёт игра: дно котла 4/16, край котла, дно воронки 11/16, дно компостера 2/16. */
    private static void summonSample(MinecraftServer server)
    {
        int x = REF.getX();
        int y = REF.getY();
        int z = REF.getZ();
        cushion(server, "red", x + 1.5D, y + 0.25D, z + 1.5D, 0.0F);
        cushion(server, "blue", x + 4.5D, y + 1.0D, z + 1.5D, 90.0F);
        cushion(server, "yellow", x + 7.5D, y + 0.6875D, z + 1.5D, 180.0F);
        cushion(server, "white", x + 10.5D, y + 0.125D, z + 1.5D, 270.0F);
        command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.4f %.4f %.4f {Rotation:[45f,0f]}",
                x + 13.5D, y + 0.875D, z + 1.5D));
    }

    private static void cushion(MinecraftServer server, String color, double x, double y, double z, float yaw)
    {
        BlockPos cell = BlockPos.containing(x, y, z);
        command(server, String.format(Locale.ROOT, "summon minecraft:cushion %.4f %.4f %.4f {color:\"%s\",Rotation:[%.0ff,0f],block_pos:[I;%d,%d,%d]}",
                x, y, z, color, yaw, cell.getX(), cell.getY(), cell.getZ()));
    }

    private static void fill(ServerLevel level, BlockPos from, BlockPos to, BlockState state)
    {
        for (BlockPos p : BlockPos.betweenClosed(from, to))
        {
            if (! level.getBlockState(p).equals(state))
            {
                level.setBlock(p, state, 2);
            }
        }
    }

    private AABB areaA()
    {
        return new AABB(Vec3.atLowerCornerOf(this.min), Vec3.atLowerCornerOf(this.max).add(1.0D, 1.0D, 1.0D)).inflate(0.5D);
    }

    private static AABB sampleArea(BlockPos o)
    {
        return new AABB(o.getX() - 1.0D, o.getY() - 1.0D, o.getZ() - 1.0D, o.getX() + 16.0D, o.getY() + 4.0D, o.getZ() + 4.0D);
    }

    /** Голограммы без двойников: Litematica без deduplicateSchematicEntities держит по две на сущность. */
    private static List<Entity> ghosts(WorldSchematic world, AABB box, java.util.function.Predicate<Entity> filter)
    {
        List<Entity> out = new ArrayList<>();

        for (Entity entity : world.getEntities((Entity) null, box, filter::test))
        {
            boolean twin = out.stream().anyMatch(o -> o.getType() == entity.getType()
                    && o.position().distanceToSqr(entity.position()) < 1.0E-4D);

            if (box.contains(entity.position()) && ! twin)
            {
                out.add(entity);
            }
        }

        return out;
    }

    /** Настоящая сущность ровно на месте голограммы (подушки не двигаются, стойка падает на снег точно). */
    private static @Nullable Entity exact(ClientLevel level, Entity ghost)
    {
        for (Entity entity : level.getEntities((Entity) null, ghost.getBoundingBox().inflate(0.5D),
                e -> e.getType() == ghost.getType() && ! e.isRemoved()))
        {
            if (entity.position().distanceTo(ghost.position()) < 0.02D)
            {
                return entity;
            }
        }

        return null;
    }

    /** Точка (ноги), откуда голограмму видно и достать: игрок помещается, луч до неё не упирается в блок. */
    private static @Nullable Vec3 spotFor(ClientLevel level, Entity ghost)
    {
        Vec3 p = ghost.position();

        for (double up : new double[]{0.4D, 1.0D, -0.4D, 1.6D})
        {
            for (int[] d : new int[][]{{-1, 0}, {-1, -1}, {-1, 1}, {0, -1}, {0, 1}, {1, 0}, {1, -1}, {1, 1}})
            {
                for (double r : new double[]{2.0D, 2.6D, 3.2D})
                {
                    double len = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
                    Vec3 feet = new Vec3(p.x + d[0] / len * r, p.y + up, p.z + d[1] / len * r);
                    AABB body = EntityTypes.PLAYER.getDimensions().makeBoundingBox(feet.x, feet.y, feet.z);
                    Vec3 eye = feet.add(0.0D, 1.62D, 0.0D);

                    if (level.noCollision(body) && eye.distanceTo(p) < 4.0D && aimPoint(level, eye, ghost) != null)
                    {
                        return feet;
                    }
                }
            }
        }

        return null;
    }

    /** Точка на верхней грани голограммы, до которой луч от глаза не упирается в настоящий блок. */
    private static @Nullable Vec3 aimPoint(ClientLevel level, Vec3 eye, Entity ghost)
    {
        AABB box = ghost.getBoundingBox();

        for (double fx : FRACTIONS)
        {
            for (double fz : FRACTIONS)
            {
                Vec3 point = new Vec3(box.minX + box.getXsize() * fx, box.maxY - 1.0E-3D, box.minZ + box.getZsize() * fz);
                BlockHitResult wall = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                        Minecraft.getInstance().player));

                if (wall.getType() == HitResult.Type.MISS || wall.getLocation().distanceTo(eye) >= point.distanceTo(eye) - 1.0E-3D)
                {
                    return point;
                }
            }
        }

        return null;
    }

    /** Ваниль гасит полёт, когда игрок касается земли, — держим его в воздухе сами. */
    private static void fly(Minecraft mc)
    {
        if (! mc.player.getAbilities().flying)
        {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
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

    private static String compare(Entity want, Entity have)
    {
        List<String> diff = new ArrayList<>();

        if (want instanceof Cushion w && have instanceof Cushion h)
        {
            if (w.getColor() != h.getColor())
            {
                diff.add("цвет " + h.getColor().getName());
            }

            if (Direction.fromYRot(w.getYRot()) != Direction.fromYRot(h.getYRot()))
            {
                diff.add("сторона " + Direction.fromYRot(h.getYRot()));
            }
        }

        if (want instanceof ArmorStand && SchematicEntities.standYaw(want.getYRot()) != SchematicEntities.standYaw(have.getYRot()))
        {
            diff.add("поворот " + have.getYRot());
        }

        return diff.isEmpty() ? "как в схеме" : String.join("; ", diff);
    }

    /** Блок под сущностью в мире: на чём лежит. */
    private static String name(ClientLevel level, Entity ghost)
    {
        Vec3 p = ghost.position();
        BlockState state = level.getBlockState(BlockPos.containing(p.x, p.y - 1.0E-3D, p.z));
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

        return state.hasProperty(SnowLayerBlock.LAYERS) ? id + " " + state.getValue(SnowLayerBlock.LAYERS) : id;
    }

    private static String describe(Entity entity)
    {
        Vec3 p = entity.position();
        String extra = entity instanceof Cushion cushion ? " " + cushion.getColor().getName() : "";

        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + extra
                + String.format(Locale.ROOT, " @%.3f/%.4f/%.3f", p.x, p.y, p.z);
    }

    private void record(String subject, Object expected, Object actual)
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

        CovOut.log("[cushion] " + (pass ? "OK  " : "FAIL") + " " + subject + " — ждали " + expected + ", вышло " + actual);
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[cushion] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        BuildTest.resetProfile();

        for (SchematicPlacement placement : new SchematicPlacement[]{this.user, this.sample})
        {
            if (placement != null)
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            }
        }

        CovOut.log("[cushion] итог: OK " + this.ok + ", FAIL " + this.failed);
    }
}
