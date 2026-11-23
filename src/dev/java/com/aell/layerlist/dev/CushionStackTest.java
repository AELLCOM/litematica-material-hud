package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.verify.LayerVerifier;
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
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code cushionstack} (жалобы 16.11.2026): подушка в одной клетке со своей опорой — кнопкой, плитой,
 * ковром, снегом, полублоком, котлом и прочими блоками ниже целой клетки.
 * <ul>
 *   <li>какие блоки вообще держат подушку в своей клетке (сама игра: подушка ставится на верх контура и выживает);</li>
 *   <li>Ctrl+B показывает и недостающую опору, и подушку;</li>
 *   <li>недостающая подушка подсвечена, как недостающий блок, опора под подушкой — тоже;</li>
 *   <li>Easy Place по голограмме подушки ставит сначала опору, потом подушку; опору — и сквозь уже лежащую подушку;</li>
 *   <li>издалека (дальше радиуса, в котором сервер присылает сущности) поставленные подушки не «пропущены».</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class CushionStackTest implements CovJob
{
    private static final String T = "[cushionstack] ";
    private static final BlockPos REF = new BlockPos(1100, 100, 200);
    private static final BlockPos TARGET = new BlockPos(1100, 100, 230);
    private static final BlockPos THROUGH = new BlockPos(1100, 100, 260);
    private static final int PER_ROW = 10;
    private static final long CASE_MS = 7000L;
    private static final String[] COLORS = {"red", "blue", "yellow", "white", "green", "orange", "pink", "cyan"};

    /** Блок-опора; {@code top} — высота, на которую игра кладёт подушку (−1 — верх контура блока). */
    private record Sup(String block, double top) {}

    private static final List<Sup> SUPPORTS = List.of(
            new Sup("stone_button[face=floor,facing=north]", -1), new Sup("oak_pressure_plate", -1),
            new Sup("heavy_weighted_pressure_plate", -1), new Sup("white_carpet", -1), new Sup("moss_carpet", -1),
            new Sup("snow[layers=1]", -1), new Sup("snow[layers=3]", -1), new Sup("snow[layers=7]", -1),
            new Sup("smooth_stone_slab[type=bottom]", -1), new Sup("oak_trapdoor[half=bottom]", -1),
            new Sup("daylight_detector", -1), new Sup("repeater", -1), new Sup("comparator", -1), new Sup("rail", -1),
            new Sup("lever[face=floor,facing=north]", -1), new Sup("redstone_wire", -1), new Sup("stonecutter", -1),
            new Sup("enchanting_table", -1), new Sup("candle", -1), new Sup("flower_pot", -1), new Sup("lantern", -1),
            new Sup("torch", -1), new Sup("end_rod", -1), new Sup("sculk_sensor", -1), new Sup("campfire[lit=false]", -1),
            new Sup("chest", -1), new Sup("dirt_path", -1), new Sup("soul_sand", -1), new Sup("cake", -1),
            new Sup("turtle_egg", -1), new Sup("sea_pickle[waterlogged=false]", -1), new Sup("brewing_stand", -1),
            new Sup("lectern", -1), new Sup("grindstone[face=floor]", -1), new Sup("amethyst_cluster", -1),
            new Sup("decorated_pot", -1), new Sup("skeleton_skull", -1), new Sup("lightning_rod", -1),
            new Sup("oak_fence", -1), new Sup("cobblestone_wall", -1), new Sup("cauldron", 0.25D),
            new Sup("hopper", 0.6875D), new Sup("composter", 0.125D), new Sup("stone", -1));

    private static final net.minecraft.world.entity.EntityDimensions CUSHION = EntityTypes.CUSHION.getDimensions();

    private int stage;
    private long nextAt;
    private long lastTick = Long.MIN_VALUE;
    private int ok;
    private int failed;
    private final double[] tops = new double[SUPPORTS.size()];
    private final boolean[] alive = new boolean[SUPPORTS.size()];
    private final boolean[] placed = new boolean[SUPPORTS.size()];
    private final boolean[] through = new boolean[SUPPORTS.size()];
    private SchematicPlacement first;
    private SchematicPlacement second;
    private int index;
    private long caseEnd;
    private long blockAt;
    private long cushionAt;
    private int nearMarks;
    private int nearRow;
    private double scalingWas = 1.0D;
    private int rangeWas = -1;

    @Override
    public String name()
    {
        return "cushionstack (подушка в одной клетке с опорой: Ctrl+B, подсветка, порядок Easy Place, издалека)";
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
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.VERIFY_ENABLED.setBooleanValue(true);
        Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.setBooleanValue(true);
        this.scalingWas = mc.options.entityDistanceScaling().get();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "gamerule advance_time false");
            command(server, "gamerule random_tick_speed 0");
            command(server, "time set noon");
            command(server, "weather clear");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", REF.getX() - 4, REF.getZ() - 4,
                    REF.getX() + 24, THROUGH.getZ() + 14));

            for (BlockPos o : new BlockPos[]{REF, TARGET, THROUGH})
            {
                command(server, String.format(Locale.ROOT, "kill @e[type=!player,x=%d,y=%d,z=%d,dx=28,dy=12,dz=18]",
                        o.getX() - 4, o.getY() - 4, o.getZ() - 4));
                command(server, fill(o, -3, -2, -3, 22, -1, 12, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 22, 5, 12, "minecraft:air"));
            }

            for (int i = 0; i < SUPPORTS.size(); ++i)
            {
                BlockPos p = cell(REF, i);
                command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", p.getX(), p.getY(), p.getZ(),
                        SUPPORTS.get(i).block()));
            }

            command(server, tp(REF, 9.5D, 4.0D, -2.5D) + facing(REF, 9.5D, 0.0D, 3.0D));
        });

        this.nextAt = Util.getMillis() + 6000L;
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
        long wait = 1000L;

        switch (this.stage)
        {
            case 0 ->
            {
                // Подушки — на верх контура каждой опоры: так их кладёт игра (у котла, воронки и компостера — на дно)
                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    BlockPos p = cell(REF, i);
                    VoxelShape shape = mc.level.getBlockState(p).getShape(mc.level, p);
                    this.tops[i] = SUPPORTS.get(i).top() >= 0.0D ? SUPPORTS.get(i).top()
                            : shape.isEmpty() ? 0.0D : shape.bounds().maxY;
                }

                server.execute(() -> {
                    for (int i = 0; i < SUPPORTS.size(); ++i)
                    {
                        summonCushion(server, REF, i, this.tops[i]);
                    }
                });
                // Подушка проверяет опору раз в 100 тиков — ждём дольше
                wait = 8000L;
            }
            case 1 ->
            {
                List<String> same = new ArrayList<>();
                List<String> above = new ArrayList<>();
                List<String> none = new ArrayList<>();

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    this.alive[i] = real(mc.level, REF, i) != null;
                    String label = String.format(Locale.ROOT, "%s (%.4f)", SUPPORTS.get(i).block(), this.tops[i]);
                    (! this.alive[i] ? none : this.tops[i] < 1.0D ? same : above).add(label);
                    // Дальше проверяем только соседство в одной клетке
                    this.alive[i] &= this.tops[i] < 1.0D;
                }

                CovOut.log(T + "подушка в клетке опоры (" + same.size() + "): " + same);
                CovOut.log(T + "подушка клеткой выше (" + above.size() + "): " + above);
                CovOut.log(T + "подушка не держится (" + none.size() + "): " + none);

                AreaSelection area = new AreaSelection();
                area.setName("dev-cushionstack");
                area.addSubRegionBox(new Box(REF.offset(-1, -1, -1), REF.offset(20, 2, 10), "main"), false);
                area.setExplicitOrigin(REF.offset(-1, -1, -1));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

                if (schematic == null)
                {
                    CovOut.log(T + "образец не снялся");
                    return true;
                }

                this.first = SchematicPlacement.createFor(schematic, TARGET.offset(-1, -1, -1), "dev-cushionstack", true, true);
                this.second = SchematicPlacement.createFor(schematic, THROUGH.offset(-1, -1, -1), "dev-cushionstack-2", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.first, false);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.second, false);
                server.execute(() -> command(server, tp(TARGET, 4.5D, 2.5D, -2.5D) + facing(TARGET, 4.5D, 0.0D, 2.0D)));
                wait = 6000L;
            }
            case 2 ->
            {
                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                wait = 5000L;
            }
            case 3 ->
            {
                List<BlockPos> missing = LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.MISSING);
                List<String> noBlock = new ArrayList<>();
                List<String> noCushion = new ArrayList<>();
                int cases = 0;

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    if (! this.alive[i])
                    {
                        continue;
                    }

                    ++cases;

                    if (! missing.contains(cell(TARGET, i)))
                    {
                        noBlock.add(SUPPORTS.get(i).block());
                    }

                    if (! marked(TARGET, i, LayerVerifier.Category.MISSING))
                    {
                        noCushion.add(SUPPORTS.get(i).block());
                    }
                }

                this.check("Ctrl+B: недостающая опора под подушкой отмечена (" + cases + ")", noBlock.isEmpty(), "без рамки: " + noBlock);
                this.check("Ctrl+B: недостающая подушка отмечена (" + cases + ")", noCushion.isEmpty(), "без рамки: " + noCushion);
                shot(mc, "stack-verify");
                wait = 800L;
            }
            case 4 ->
            {
                // «Только блоки из инвентаря», выживание, подушек с собой нет: ни голограмм, ни рамок, ни подсветки
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    this.hover(server);
                });
                wait = 4000L;
            }
            case 5 ->
            {
                fly(mc);
                int marks = marks(TARGET);
                int lit = this.litCount(TARGET);
                int ghosts = 0;
                int hidden = 0;

                for (Entity ghost : world.getEntities((Entity) null, area(TARGET), e -> e instanceof Cushion))
                {
                    ++ghosts;
                    hidden += com.aell.layerlist.core.SchematicEntities.hiddenByInventory(ghost) ? 1 : 0;
                }

                this.check("только из инвентаря, подушек нет: рамок Ctrl+B у подушек нет", marks == 0, "рамок " + marks);
                this.check("только из инвентаря, подушек нет: подсветки подушек нет", lit == 0, "подсвечено " + lit);
                this.check("только из инвентаря, подушек нет: голограммы подушек скрыты", ghosts > 0 && hidden == ghosts,
                        "скрыто " + hidden + " из " + ghosts);
                shot(mc, "stack-inventory-none");
                server.execute(() -> command(server, "give @a minecraft:red_cushion 8"));
                wait = 4000L;
            }
            case 6 ->
            {
                fly(mc);
                // Красные подушки с собой — видны и отмечены только красные
                int red = 0;
                int redMarked = 0;
                int otherMarked = 0;

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    boolean isRed = COLORS[i % COLORS.length].equals("red");
                    boolean marked = this.marked(TARGET, i, LayerVerifier.Category.MISSING);
                    red += isRed ? 1 : 0;
                    redMarked += isRed && marked ? 1 : 0;
                    otherMarked += ! isRed && marked ? 1 : 0;
                }

                this.check("только из инвентаря, красные подушки с собой: отмечены красные и только они",
                        redMarked == red && otherMarked == 0, "красных " + redMarked + " из " + red + ", прочих " + otherMarked);
                shot(mc, "stack-inventory-red");
                wait = 800L;
            }
            case 7 ->
            {
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "gamemode creative @a");
                });
                wait = 3000L;
            }
            case 8 ->
            {
                LayerVerifier.getInstance().toggle();
                wait = 1500L;
            }
            case 9 ->
            {
                // Без Ctrl+B: видно ли, что подушку и опору под ней надо ставить
                List<String> unlit = new ArrayList<>();
                int cases = 0;

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    if (this.alive[i])
                    {
                        ++cases;

                        if (! lit(TARGET, i))
                        {
                            unlit.add(SUPPORTS.get(i).block());
                        }
                    }
                }

                this.check("подсветка «надо поставить» у подушек (" + cases + ")", unlit.isEmpty(), "без подсветки: " + unlit);
                shot(mc, "stack-ghost");
                wait = 800L;
            }
            case 10 ->
            {
                server.execute(() -> command(server, tp(TARGET, 1.0D, 1.0D, -1.6D) + facing(TARGET, 1.0D, 0.0D, 0.5D)));
                wait = 800L;
            }
            case 11 ->
            {
                shot(mc, "stack-ghost-close");
                this.index = -1;
                wait = 500L;
            }
            case 12 ->
            {
                // Easy Place по голограмме подушки: сначала опора, потом подушка
                if (this.easyPlace(mc, now, world, TARGET, false))
                {
                    return false;
                }

                int cases = 0;
                List<String> bad = new ArrayList<>();

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    if (this.alive[i])
                    {
                        ++cases;

                        if (! this.placed[i])
                        {
                            bad.add(SUPPORTS.get(i).block());
                        }
                    }
                }

                this.check("Easy Place: опора, потом подушка (" + cases + ")", bad.isEmpty(), "не вышло: " + bad);
                server.execute(() -> command(server, tp(TARGET, 4.5D, 2.5D, -2.5D) + facing(TARGET, 4.5D, 0.0D, 2.0D)));
                wait = 1500L;
            }
            case 13 ->
            {
                shot(mc, "stack-built");
                // Вторая площадка: подушки уже лежат (их положили раньше опоры), блока под ними нет
                server.execute(() -> {
                    for (int i = 0; i < SUPPORTS.size(); ++i)
                    {
                        if (this.alive[i])
                        {
                            summonCushion(server, THROUGH, i, this.tops[i]);
                        }
                    }

                    command(server, tp(THROUGH, 4.5D, 2.5D, -2.5D) + facing(THROUGH, 4.5D, 0.0D, 2.0D));
                });
                wait = 8000L;
            }
            case 14 ->
            {
                List<String> held = new ArrayList<>();

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    this.through[i] = this.alive[i] && real(mc.level, THROUGH, i) != null;

                    if (this.through[i])
                    {
                        held.add(SUPPORTS.get(i).block());
                    }
                }

                CovOut.log(T + "подушка лежит без своей опоры (" + held.size() + "): " + held);
                shot(mc, "stack-through-before");
                this.index = -1;
                wait = 500L;
            }
            case 15 ->
            {
                if (this.easyPlace(mc, now, world, THROUGH, true))
                {
                    return false;
                }

                int cases = 0;
                List<String> bad = new ArrayList<>();

                for (int i = 0; i < SUPPORTS.size(); ++i)
                {
                    if (this.through[i])
                    {
                        ++cases;

                        if (! this.placed[i])
                        {
                            bad.add(SUPPORTS.get(i).block());
                        }
                    }
                }

                // Игра не даёт подушке лежать без своей опоры даже в шестнадцатой над полом — случаев обычно нет
                if (cases > 0)
                {
                    this.check("Easy Place: опора сквозь лежащую подушку (" + cases + ")", bad.isEmpty(), "не вышло: " + bad);
                }
                server.execute(() -> command(server, tp(TARGET, 4.5D, 2.5D, -2.5D) + facing(TARGET, 4.5D, 0.0D, 2.0D)));

                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                LayerCountManager.getInstance().markDirty();
                wait = 6000L;
            }
            case 16 ->
            {
                this.nearMarks = marks(TARGET);
                this.nearRow = cushionsMissing();
                CovOut.log(T + "вблизи: рамок подушек на площадке " + this.nearMarks + ", в списке не хватает подушек " + this.nearRow);
                // Радиус сущностей вдвое меньше — и встаём за ним, но в загруженных чанках
                this.rangeWas = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();
                LayerListConfigs.VERIFY_DRAW_RANGE.setIntegerValue(LayerListConfigs.VERIFY_DRAW_RANGE.getMaxIntegerValue());
                mc.options.entityDistanceScaling().set(0.5D);
                double range = EntityTypes.CUSHION.clientTrackingRange() * 16.0D * 0.5D;
                CovOut.log(T + "радиус подушек у сервера " + range + ", дальность чанков " + mc.options.getEffectiveRenderDistance());
                server.execute(() -> command(server, tp(TARGET, 9.5D - range - 24.0D, 6.0D, 4.5D)
                        + String.format(Locale.ROOT, " facing %d %d %d", TARGET.getX() + 9, TARGET.getY(), TARGET.getZ() + 4)));
                wait = 9000L;
            }
            case 17 ->
            {
                LayerCountManager.getInstance().markDirty();
                wait = 4000L;
            }
            case 18 ->
            {
                int seen = mc.level.getEntitiesOfClass(Cushion.class, area(TARGET)).size();
                boolean loaded = WorldUtils.isClientChunkLoaded(mc.level, TARGET.getX() >> 4, TARGET.getZ() >> 4);
                int farMarks = marks(TARGET);
                int farRow = cushionsMissing();
                CovOut.log(T + "издалека: игрок " + mc.player.position() + ", чанк площадки загружен " + loaded
                        + ", подушек площадки у клиента " + seen);
                this.check("издалека: условия опыта (чанк есть, подушек у клиента нет)", loaded && seen == 0, "");
                this.check("издалека: рамок подушек столько же, сколько вблизи", farMarks == this.nearMarks,
                        "вблизи " + this.nearMarks + ", издалека " + farMarks);
                this.check("издалека: в списке не хватает подушек столько же", farRow == this.nearRow,
                        "вблизи " + this.nearRow + ", издалека " + farRow);
                shot(mc, "stack-far");
                wait = 800L;
            }
            default ->
            {
                return true;
            }
        }

        ++this.stage;
        this.nextAt = now + wait;
        return false;
    }

    /** Очередной случай площадки; {@code true} — ещё работаем. */
    private boolean easyPlace(Minecraft mc, long now, @Nullable WorldSchematic world, BlockPos origin, boolean throughOnly)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (this.index >= 0 && this.index < SUPPORTS.size())
        {
            int i = this.index;
            BlockPos pos = cell(origin, i);
            BlockState wanted = world == null ? null : world.getBlockState(pos);
            BlockState have = mc.level.getBlockState(pos);
            boolean block = wanted != null && have.getBlock() == wanted.getBlock();
            boolean cushion = real(mc.level, origin, i) != null;

            if (block && this.blockAt == 0L)
            {
                this.blockAt = now;
            }

            if (cushion && this.cushionAt == 0L)
            {
                this.cushionAt = now;
            }

            if ((block && cushion) || now > this.caseEnd)
            {
                boolean order = throughOnly || this.blockAt <= this.cushionAt;
                this.placed[i] = block && cushion && order;
                CovOut.log(T + (this.placed[i] ? "OK   " : "FAIL ") + (throughOnly ? "сквозь подушку: " : "") + SUPPORTS.get(i).block()
                        + " — блок " + (block ? (have == wanted ? "как в схеме" : "не то состояние " + have) : "нет (" + have + ")")
                        + ", подушка " + (cushion ? "есть" : "нет")
                        + (block && cushion && ! order ? ", подушка встала раньше опоры" : ""));
                this.failed += this.placed[i] ? 0 : 1;
                this.ok += this.placed[i] ? 1 : 0;
            }
            else
            {
                long tick = mc.level.getGameTime();

                if (tick != this.lastTick)
                {
                    this.lastTick = tick;
                    fly(mc);
                    look(mc, this.inside(i) ? new Vec3(pos.getX() + 0.5D, pos.getY() + this.tops[i] + 0.1D, pos.getZ() + 0.5D)
                            : new Vec3(pos.getX() + 0.5D, pos.getY() + this.tops[i] + CUSHION.height() - 0.02D,
                                    pos.getZ() + 0.5D + CUSHION.width() / 2.0D - 0.05D));
                    WorldUtils.handleEasyPlace(mc);
                }

                return true;
            }
        }

        do
        {
            ++this.index;
        }
        while (this.index < SUPPORTS.size() && ! (throughOnly ? this.through[this.index] : this.alive[this.index]));

        if (this.index >= SUPPORTS.size())
        {
            return false;
        }

        java.util.Arrays.fill(this.placed, this.index, this.index + 1, false);
        this.blockAt = 0L;
        this.cushionAt = throughOnly ? 0L : 0L;
        BlockPos pos = cell(origin, this.index);
        boolean inside = this.inside(this.index);
        // Обычно — пологий взгляд спереди в дальний край верха голограммы: луч уходит за подушку, мимо мелкой опоры.
        // Котёл, воронка, компостер — сверху: сбоку подушку внутри заслоняет стенка
        server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", pos.getX() + 0.5D,
                pos.getY() + (inside ? 2.2D : 0.0D), pos.getZ() - (inside ? 0.35D : 2.2D))));
        this.caseEnd = now + 500L + CASE_MS;
        this.nextAt = now + 500L;
        return true;
    }

    // ===== Помощники =====

    /** Подушка внутри блока (котёл, воронка, компостер): её высота задана дном, а не верхом контура. */
    private boolean inside(int i)
    {
        return SUPPORTS.get(i).top() >= 0.0D;
    }

    private static BlockPos cell(BlockPos origin, int i)
    {
        return origin.offset(2 * (i % PER_ROW), 0, 2 * (i / PER_ROW));
    }

    private Vec3 spot(BlockPos origin, int i)
    {
        BlockPos p = cell(origin, i);
        return new Vec3(p.getX() + 0.5D, p.getY() + this.tops[i], p.getZ() + 0.5D);
    }

    private @Nullable Cushion real(ClientLevel level, BlockPos origin, int i)
    {
        Vec3 at = this.spot(origin, i);

        for (Cushion cushion : level.getEntitiesOfClass(Cushion.class, new AABB(at, at).inflate(0.6D)))
        {
            if (! cushion.isRemoved() && cushion.position().distanceTo(at) < 0.02D)
            {
                return cushion;
            }
        }

        return null;
    }

    /** Есть ли рамка проверки вокруг подушки этого случая. */
    private boolean marked(BlockPos origin, int i, LayerVerifier.Category category)
    {
        Vec3 at = this.spot(origin, i).add(0.0D, 0.05D, 0.0D);

        return LayerVerifier.getInstance().getEntityMarks().stream()
                .anyMatch(m -> m.category() == category && m.box().inflate(0.05D).contains(at));
    }

    /** Подсвечена ли подушка «надо поставить» без Ctrl+B. */
    private boolean lit(BlockPos origin, int i)
    {
        Vec3 at = this.spot(origin, i).add(0.0D, 0.05D, 0.0D);

        return com.aell.layerlist.ghost.EntityOverlay.boxes().stream().anyMatch(box -> box.inflate(0.05D).contains(at));
    }

    private static int marks(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) LayerVerifier.getInstance().getEntityMarks().stream()
                .filter(m -> area.contains(m.box().getCenter())).count();
    }

    private static int cushionsMissing()
    {
        int total = 0;

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).getPath().endsWith("_cushion"))
            {
                total += (int) entry.getCountMissing();
            }
        }

        return total;
    }

    private static AABB area(BlockPos o)
    {
        return new AABB(o.getX() - 2.0D, o.getY() - 1.0D, o.getZ() - 2.0D, o.getX() + 22.0D, o.getY() + 4.0D, o.getZ() + 12.0D);
    }

    private static void summonCushion(MinecraftServer server, BlockPos origin, int i, double top)
    {
        BlockPos p = cell(origin, i);
        double y = p.getY() + top;
        command(server, String.format(Locale.ROOT,
                "summon minecraft:cushion %.4f %.4f %.4f {color:\"%s\",Rotation:[0f,0f],block_pos:[I;%d,%d,%d]}",
                p.getX() + 0.5D, y, p.getZ() + 0.5D, COLORS[i % COLORS.length], p.getX(), (int) Math.floor(y), p.getZ()));
    }

    private static String tp(BlockPos o, double x, double y, double z)
    {
        return String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", o.getX() + x, o.getY() + y, o.getZ() + z);
    }

    private static String facing(BlockPos o, double x, double y, double z)
    {
        return String.format(Locale.ROOT, " facing %.2f %.2f %.2f", o.getX() + x, o.getY() + y, o.getZ() + z);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    /** В выживании игрок упал бы с точки обзора — держим полёт. */
    private void hover(MinecraftServer server)
    {
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers())
        {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
        }
    }

    private int litCount(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) com.aell.layerlist.ghost.EntityOverlay.boxes().stream().filter(box -> area.contains(box.getCenter())).count();
    }

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
        mc.player.setYRot(yaw);
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        mc.player.setYHeadRot(yaw);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(T + "снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(T + (ok ? "OK   " : "FAIL ") + what + (ok || detail == null || detail.isEmpty() ? "" : " — " + detail));
        this.ok += ok ? 1 : 0;
        this.failed += ok ? 0 : 1;
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
        Minecraft.getInstance().options.entityDistanceScaling().set(this.scalingWas);

        if (this.rangeWas >= 0)
        {
            LayerListConfigs.VERIFY_DRAW_RANGE.setIntegerValue(this.rangeWas);
        }

        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        BuildTest.resetProfile();

        for (SchematicPlacement placement : new SchematicPlacement[]{this.first, this.second})
        {
            if (placement != null)
            {
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
            }
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        CovOut.log(T + "итог: OK " + this.ok + ", FAIL " + this.failed);
    }
}
