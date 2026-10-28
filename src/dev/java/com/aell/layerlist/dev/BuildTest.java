package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.InventorySync;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.config.IConfigInteger;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code build}: стройка настоящей схемы Easy Place «как игрок» (жалоба 09.10.2026: «некоторые свечи не
 * прокликиваются до нужного количества, некоторые блоки не доводятся» — при включённых новых функциях).
 *
 * <p>Схема — {@code LL_BUILD_SCHEMATIC} (по умолчанию «Тестовая схема.litematic» из {@code run/schematics}).
 * Каркас (полные кубы андезита, земля, дёрн, блок редстоуна, вода) ставится напрямую на сервере; всё остальное
 * ставит Easy Place в выживании. Игрок летает и не получает урона, встаёт у клетки со стороны, где в схеме пусто,
 * смотрит на блок и держит клавишу активации Easy Place — Litematica сама кликает каждый тик, как у игрока со
 * старым путём Easy Place ({@code easyPlacePostRewrite = false}). Предметы — группами (до 26 видов) плюс
 * инструменты доводки; часть в хотбаре, часть в рюкзаке. Проходы: снизу вверх, потом сверху вниз (лозы с
 * потолка), потом ещё раз. Итог — клетки, где мир сервера разошёлся со схемой, с различающимися свойствами.
 *
 * <p>Профиль — {@code LL_BUILD_PROFILE}:
 * <ul>
 *   <li>{@code user} — как у пользователя: железные защиты, «Только по схеме», голограммы по инвентарю, без опоры,
 *       опора с соседнего слоя, доводка, «сервер ванильный»; ожидание сервера 100 мс (LAN);</li>
 *   <li>{@code user+fill} — то же и «Наполнять блоки»;</li>
 *   <li>{@code base} — новые функции выключены (защиты, голограммы, ожидание сервера).</li>
 * </ul>
 * {@code LL_BUILD_LAG} — задержка ответов сервера, мс (по умолчанию 0); {@code LL_BUILD_ONLY} — только клетки
 * с этими блоками (через запятую, без {@code minecraft:}).
 */
@Environment(EnvType.CLIENT)
public final class BuildTest implements CovJob
{
    private static final BlockPos ORIGIN = new BlockPos(900, 100, 40);
    /** Тиков на клетку за проход: свечам нужно до 4 кликов с ожиданием подтверждений, доводке — ещё клик. */
    private static final int HOLD_TICKS = 50;
    /** Видов предметов в группе; {@code LL_BUILD_GROUP} — своё число (мелким стопкам LL_BUILD_SPLIT нужно меньше — иначе не влезут). */
    private static final int GROUP_ITEMS = groupItems();

    private static int groupItems()
    {
        try
        {
            String env = System.getenv("LL_BUILD_GROUP");
            return env == null || env.isBlank() ? 23 : Math.max(1, Integer.parseInt(env.trim()));
        }
        catch (NumberFormatException e)
        {
            return 23;
        }
    }
    private static final Set<String> FRAME_DEFAULT = Set.of("polished_andesite", "dirt", "grass_block", "redstone_block",
            "water", "stone", "andesite", "cobblestone");
    private static final Set<String> FRAME = frame();

    /** {@code LL_BUILD_FRAME} — свой список каркаса через запятую ({@code none} — каркаса нет, всё ставит Easy Place). */
    private static Set<String> frame()
    {
        String env = System.getenv("LL_BUILD_FRAME");

        if (env == null || env.isBlank())
        {
            return FRAME_DEFAULT;
        }

        Set<String> out = new LinkedHashSet<>();

        for (String s : env.split("[,; ]+"))
        {
            if (! s.isBlank() && ! s.trim().equalsIgnoreCase("none"))
            {
                out.add(s.trim());
            }
        }

        return out;
    }

    private enum Stage { LOAD, WAIT_WORLD, PREBUILD, GROUP, MOVE, AIM, HOLD, NEXT_PASS, VERIFY, DONE }

    private Stage stage = Stage.LOAD;
    private long nextAt;
    private SchematicPlacement placement;
    private final List<BlockPos> decor = new ArrayList<>();
    private final List<BlockPos> frame = new ArrayList<>();
    private List<List<BlockPos>> groups = List.of();
    private int group;
    private int pass;
    private List<BlockPos> order = new ArrayList<>();
    private int index;
    private int waits;
    private int held;
    private Vec3 eye;
    private Vec3 aim;
    private BlockPos min;
    private BlockPos max;
    private final Set<BlockPos> unreachable = new LinkedHashSet<>();
    private String profile = "user";
    /** {@code LL_BUILD_MODE=creative}: стройка в креативе — предметы берёт pick-block Litematica, инвентарь пуст. */
    private final boolean creative = "creative".equalsIgnoreCase(String.valueOf(System.getenv("LL_BUILD_MODE")).trim());
    private InvSyncTest.Lag lag;
    private boolean finalRound;

    @Override
    public String name()
    {
        return "BUILD (стройка настоящей схемы Easy Place, профиль " + profile() + ")";
    }

    private static String profile()
    {
        String p = System.getenv("LL_BUILD_PROFILE");
        return p == null || p.isBlank() ? "user" : p.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        this.profile = profile();

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        applyProfile(this.profile);
        CovOut.log("[build] профиль " + this.profile + ", Easy Place ведёт "
                + (FabricLoader.getInstance().isModLoaded("easyplacefixplus") ? "Easy Place Fix+" : "Material HUD"));

        String name = System.getenv("LL_BUILD_SCHEMATIC");
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics")
                .resolve(name == null || name.isBlank() ? "Тестовая схема.litematic" : name);

        if (! Files.isRegularFile(path))
        {
            CovOut.log("[build] схемы нет: " + path);
            return false;
        }

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("[build] схема не загрузилась: " + path);
            return false;
        }

        this.placement = SchematicPlacement.createFor(schematic, ORIGIN, "dev-build", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);

        // Коробка схемы в мире
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

        for (Box box : this.placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
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
        CovOut.log("[build] схема " + path.getFileName() + " в " + this.min.toShortString() + " … " + this.max.toShortString());

        BlockPos a = this.min.offset(-6, 0, -6);
        BlockPos b = this.max.offset(6, 6, 6);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", a.getX(), a.getZ(), b.getX(), b.getZ()));
            // Имена правил в 26.3 — snake_case (randomTickSpeed/doDaylightCycle молча не проходят)
            command(server, "gamerule advance_time false");
            // Без случайных тиков: медь не темнеет и лозы не растут за время стройки — иначе итог зависел бы от минут
            command(server, "gamerule random_tick_speed 0");
            command(server, "time set noon");
            ServerLevel level = server.overworld();

            for (BlockPos p : BlockPos.betweenClosed(a.below(), new BlockPos(b.getX(), this.min.getY() - 1, b.getZ())))
            {
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
            }

            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(a.getX(), this.min.getY(), a.getZ()), b))
            {
                if (! level.getBlockState(p).isAir())
                {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }

            Vec3 c = Vec3.atCenterOf(this.min).add(Vec3.atCenterOf(this.max)).scale(0.5D);
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", this.min.getX() - 4.0D, this.min.getY() + 2, c.z));
        });

        this.stage = Stage.WAIT_WORLD;
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
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();

        if (this.stage.ordinal() >= Stage.GROUP.ordinal())
        {
            fly(mc);
        }

        switch (this.stage)
        {
            case WAIT_WORLD ->
            {
                if (schematic == null || ! this.collect(schematic))
                {
                    if (++this.waits > 60)
                    {
                        CovOut.log("[build] мир схемы так и не загрузился");
                        return true;
                    }

                    this.nextAt = now + 500L;
                    return false;
                }

                CovOut.log("[build] каркас " + this.frame.size() + " клеток, декор " + this.decor.size());
                this.stage = Stage.PREBUILD;
            }
            case PREBUILD ->
            {
                List<BlockPos> cells = new ArrayList<>(this.frame);
                Map<BlockPos, BlockState> states = new LinkedHashMap<>();

                for (BlockPos p : cells)
                {
                    BlockState s = schematic.getBlockState(p);
                    states.put(p, s.is(Blocks.WATER) ? Blocks.WATER.defaultBlockState() : s);
                }

                // Под ламинарией — вода: ламинарию ставят в воду
                for (BlockPos p : this.decor)
                {
                    if (schematic.getBlockState(p).is(Blocks.KELP) || schematic.getBlockState(p).is(Blocks.KELP_PLANT))
                    {
                        states.put(p, Blocks.WATER.defaultBlockState());
                    }
                }

                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    states.forEach((p, s) -> level.setBlock(p, s, 2));
                    command(server, this.creative ? "gamemode creative @a" : "gamemode survival @a");
                    command(server, "effect give @a minecraft:saturation 100000 1 true");

                    for (ServerPlayer player : server.getPlayerList().getPlayers())
                    {
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.getAbilities().invulnerable = true;
                        player.onUpdateAbilities();
                    }
                });

                this.groups = groups(schematic, this.decor);
                CovOut.log("[build] групп предметов: " + this.groups.size());

                String lagMs = System.getenv("LL_BUILD_LAG");

                if (lagMs != null && ! lagMs.isBlank() && Long.parseLong(lagMs.trim()) > 0L)
                {
                    this.lag = InvSyncTest.Lag.install(mc);
                    this.lag.delayMs = Long.parseLong(lagMs.trim());
                    CovOut.log("[build] задержка ответов сервера " + this.lag.delayMs + " мс");
                }

                this.group = 0;
                this.stage = Stage.GROUP;
                this.nextAt = now + 1500L;
            }
            case GROUP ->
            {
                if (this.group >= this.groups.size())
                {
                    // Добивка: группы делят клетки по предметам, и звенья одной цепочки (лоза с потолка) могут
                    // попасть в разные — нижнее звено ждало опору, которой в его группе ещё не было
                    List<BlockPos> left = new ArrayList<>();

                    for (BlockPos p : this.decor)
                    {
                        if (! placed(mc.level.getBlockState(p), schematic.getBlockState(p)) && ! this.unreachable.contains(p))
                        {
                            left.add(p);
                        }
                    }

                    if (! this.finalRound && ! left.isEmpty())
                    {
                        this.finalRound = true;
                        this.groups = groups(schematic, left);
                        this.group = 0;
                        CovOut.log("[build] добивка: осталось " + left.size());
                        this.nextAt = now + 300L;
                        return false;
                    }

                    this.stage = Stage.VERIFY;
                    this.nextAt = now + 2500L;
                    return false;
                }

                List<BlockPos> cells = this.groups.get(this.group);

                if (this.creative)
                {
                    server.execute(() -> command(server, "clear @a"));
                }
                else
                {
                    this.giveItems(server, schematic, cells);
                }
                this.pass = 0;
                this.order = this.ordered(cells, false);
                this.index = 0;
                CovOut.log("[build] группа " + (this.group + 1) + "/" + this.groups.size() + ": " + cells.size() + " клеток");
                this.stage = Stage.MOVE;
                this.nextAt = now + 1200L;
            }
            case MOVE ->
            {
                release();

                // Клетка уже как в схеме (поставили раньше или соседней установкой) — дальше
                while (this.index < this.order.size() && placed(mc.level.getBlockState(this.order.get(this.index)),
                        schematic.getBlockState(this.order.get(this.index))))
                {
                    ++this.index;
                }

                if (this.index >= this.order.size())
                {
                    this.stage = Stage.NEXT_PASS;
                    return false;
                }

                BlockPos cell = this.order.get(this.index);
                Vec3[] spot = approach(mc.level, schematic, cell);

                if (spot == null)
                {
                    this.unreachable.add(cell);
                    ++this.index;
                    return false;
                }

                this.eye = spot[0];
                this.aim = spot[1];
                double feetY = this.eye.y - 1.62D;
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", this.eye.x, feetY, this.eye.z)));
                CovOut.log("[build] → " + rel(cell) + " " + name(schematic.getBlockState(cell)));
                this.waits = 0;
                this.stage = Stage.AIM;
                this.nextAt = now + 100L;
            }
            case AIM ->
            {
                Vec3 feet = mc.player.position();

                if (Math.abs(feet.x - this.eye.x) > 0.05D || Math.abs(feet.z - this.eye.z) > 0.05D
                        || Math.abs(feet.y - (this.eye.y - 1.62D)) > 0.1D)
                {
                    if (++this.waits < 40)
                    {
                        this.nextAt = now + 50L;
                        return false;
                    }

                    CovOut.log("[build]   телепорт не дошёл до клиента: " + feet);
                }

                look(mc, this.aim);
                this.held = 0;
                this.stage = Stage.HOLD;
                this.nextAt = now + 60L;
            }
            case HOLD ->
            {
                BlockPos cell = this.order.get(this.index);
                look(mc, this.aim);
                hold();

                if (placed(mc.level.getBlockState(cell), schematic.getBlockState(cell)) || ++this.held > HOLD_TICKS)
                {
                    if (this.held > HOLD_TICKS)
                    {
                        diagnose(mc, schematic, cell);
                    }

                    release();
                    ++this.index;
                    this.stage = Stage.MOVE;
                    this.nextAt = now + 60L;
                    return false;
                }

                this.nextAt = now + 45L;
            }
            case NEXT_PASS ->
            {
                release();
                List<BlockPos> left = new ArrayList<>();

                for (BlockPos p : this.groups.get(this.group))
                {
                    if (! placed(mc.level.getBlockState(p), schematic.getBlockState(p)) && ! this.unreachable.contains(p))
                    {
                        left.add(p);
                    }
                }

                if (left.isEmpty() || ++this.pass >= 3)
                {
                    ++this.group;
                    this.stage = Stage.GROUP;
                    this.nextAt = now + 300L;
                    return false;
                }

                // Второй проход — сверху вниз: висячие цепочки строятся от опоры
                this.order = this.ordered(left, this.pass == 1);
                this.index = 0;
                CovOut.log("[build]   проход " + (this.pass + 1) + ": осталось " + left.size());
                this.stage = Stage.MOVE;
            }
            case VERIFY ->
            {
                release();
                this.verify(server, schematic);
                this.stage = Stage.DONE;
            }
            default ->
            {
                return true;
            }
        }

        return false;
    }

    // ===== Клетки =====

    /** Каркас и декор; ложь — мир схемы ещё не собрался. */
    private boolean collect(WorldSchematic schematic)
    {
        this.frame.clear();
        this.decor.clear();
        String only = System.getenv("LL_BUILD_ONLY");
        Set<String> filter = new LinkedHashSet<>();

        if (only != null && ! only.isBlank())
        {
            for (String s : only.split("[,; ]+"))
            {
                filter.add(s.trim());
            }
        }

        for (BlockPos p : BlockPos.betweenClosed(this.min, this.max))
        {
            BlockState state = schematic.getBlockState(p);

            if (state.isAir())
            {
                continue;
            }

            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

            if (FRAME.contains(id))
            {
                // Текучая вода (level > 0) — не ставится вовсе
                if (! state.is(Blocks.WATER) || state.getFluidState().isSource())
                {
                    this.frame.add(p.immutable());
                }
            }
            else if (filter.isEmpty() || filter.contains(id))
            {
                this.decor.add(p.immutable());
            }
        }

        return ! this.frame.isEmpty() || ! this.decor.isEmpty();
    }

    /** Снизу вверх (или сверху вниз), глубокие клетки (от открытой стороны дальше) первыми. */
    private List<BlockPos> ordered(List<BlockPos> cells, boolean topDown)
    {
        List<BlockPos> out = new ArrayList<>(cells);
        // BlockPos::getX неоднозначен (есть статический getX(long)) — лямбдами
        Comparator<BlockPos> byY = Comparator.comparingInt((BlockPos p) -> p.getY());
        Comparator<BlockPos> byX = Comparator.comparingInt((BlockPos p) -> p.getX());
        out.sort((topDown ? byY.reversed() : byY).thenComparing(byX.reversed())
                .thenComparingInt((BlockPos p) -> p.getZ()));
        return out;
    }

    /** Группы клеток по предметам: не больше {@link #GROUP_ITEMS} видов на группу, в порядке стройки. */
    private List<List<BlockPos>> groups(WorldSchematic schematic, List<BlockPos> cells)
    {
        List<List<BlockPos>> out = new ArrayList<>();
        List<BlockPos> current = new ArrayList<>();
        Set<Item> items = new LinkedHashSet<>();

        for (BlockPos p : this.ordered(cells, false))
        {
            Item item = required(schematic, p).getItem();

            if (! items.contains(item) && items.size() >= GROUP_ITEMS)
            {
                out.add(current);
                current = new ArrayList<>();
                items.clear();
            }

            items.add(item);
            current.add(p);
        }

        if (! current.isEmpty())
        {
            out.add(current);
        }

        return out;
    }

    private static ItemStack required(WorldSchematic schematic, BlockPos pos)
    {
        try
        {
            ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(schematic.getBlockState(pos), schematic, pos);

            if (! stack.isEmpty())
            {
                return stack;
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }

        return new ItemStack(schematic.getBlockState(pos).getBlock().asItem());
    }

    /** Инвентарь группы: инструменты доводки и предметы схемы; часть в хотбаре, часть в рюкзаке. */
    private void giveItems(MinecraftServer server, WorldSchematic schematic, List<BlockPos> cells)
    {
        Map<Item, Integer> counts = new LinkedHashMap<>();

        for (BlockPos p : cells)
        {
            ItemStack stack = required(schematic, p);

            if (! stack.isEmpty() && stack.getItem() != Items.AIR)
            {
                counts.merge(stack.getItem(), Math.max(1, stack.getCount()), Integer::sum);
            }

            // Растение в горшке: сначала пустой горшок (Litematica называет только растение)
            if (schematic.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock pot
                    && pot.getPotted() != Blocks.AIR)
            {
                counts.merge(Items.FLOWER_POT, 1, Integer::sum);
            }
        }

        List<ItemStack> blocks = new ArrayList<>();
        // LL_BUILD_SPLIT=1: каждый предмет мелкими стопками по 3 (запас +2) — рука пустеет посреди клетки, нужен обмен
        boolean split = "1".equals(System.getenv("LL_BUILD_SPLIT"));
        counts.forEach((item, n) -> {
            int total = Math.min(item.getDefaultMaxStackSize(), n * 2 + 2);

            if (split && item.getDefaultMaxStackSize() > 1)
            {
                for (int left = n + 2; left > 0; left -= 3)
                {
                    blocks.add(new ItemStack(item, Math.min(3, left)));
                }
            }
            else if (n + 4 > item.getDefaultMaxStackSize())
            {
                // Большая схема: одной стопки не хватает — несколько полных, с небольшим запасом
                for (int left = n + 4; left > 0; left -= item.getDefaultMaxStackSize())
                {
                    blocks.add(new ItemStack(item, Math.min(item.getDefaultMaxStackSize(), left)));
                }
            }
            else
            {
                blocks.add(new ItemStack(item, total));
            }
        });
        // LL_BUILD_NAMED=1: блоки с названием (компонент предмета) — как выданные плагином или магазином сервера
        if ("1".equals(System.getenv("LL_BUILD_NAMED")))
        {
            for (ItemStack stack : blocks)
            {
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        net.minecraft.network.chat.Component.literal("Со склада"));
            }
        }

        List<ItemStack> tools = new ArrayList<>(List.of(new ItemStack(Items.SHEARS), new ItemStack(Items.IRON_AXE),
                new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.FLINT_AND_STEEL), new ItemStack(Items.WATER_BUCKET),
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.HONEYCOMB, 16), new ItemStack(Items.BONE_MEAL, 32),
                new ItemStack(Items.BOOK, 8), new ItemStack(Items.GLOWSTONE, 8),
                net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER),
                net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER),
                net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.WATER)));

        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                Inventory inventory = player.getInventory();
                inventory.clearContent();
                int slot = 0;

                // Хотбар: четыре блока и пять инструментов; остальное — в рюкзак
                for (int i = 0; i < 4 && i < blocks.size(); ++i)
                {
                    inventory.setItem(slot++, blocks.get(i).copy());
                }

                for (int i = 0; i < 5; ++i)
                {
                    inventory.setItem(slot++, tools.get(i).copy());
                }

                slot = Math.max(slot, 9);

                for (int i = 4; i < blocks.size() && slot < 36; ++i)
                {
                    inventory.setItem(slot++, blocks.get(i).copy());
                }

                for (int i = 5; i < tools.size() && slot < 36; ++i)
                {
                    inventory.setItem(slot++, tools.get(i).copy());
                }

                // Выбранный слот — и клиенту пакетом: иначе сервер держит слот 0, клиент — свой, и сервер ставит
                // из другой руки (так в клетку лозы вставал котёл)
                inventory.setSelectedSlot(0);
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
                player.inventoryMenu.broadcastChanges();
                player.containerMenu.broadcastChanges();
            }
        });
    }

    /**
     * Откуда смотреть: сначала прямо со стороны, где в схеме и в мире пусто; не вышло — любая точка вокруг
     * (по диагоналям тоже), из которой луч до середины формы блока первым в схеме встречает именно эту клетку
     * и не упирается в мир. Тело игрока не задевает ни мир, ни саму клетку.
     */
    private static @Nullable Vec3[] approach(ClientLevel level, WorldSchematic schematic, BlockPos cell)
    {
        BlockState wanted = schematic.getBlockState(cell);
        AABB shape;

        try
        {
            var outline = wanted.getShape(schematic, cell);
            shape = outline.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : outline.bounds();
        }
        catch (RuntimeException e)
        {
            shape = new AABB(0, 0, 0, 1, 1, 1);
        }

        // Середина самого крупного куска формы: у провода «вверх по стене» середина рамки — пустота
        try
        {
            AABB biggest = null;

            for (AABB box : wanted.getShape(schematic, cell).toAabbs())
            {
                if (biggest == null || box.getXsize() * box.getYsize() * box.getZsize()
                        > biggest.getXsize() * biggest.getYsize() * biggest.getZsize())
                {
                    biggest = box;
                }
            }

            if (biggest != null)
            {
                shape = biggest;
            }
        }
        catch (RuntimeException ignored)
        {
        }

        // Чуть в сторону от середины: глаз на сетке кандидатов и середина клетки иначе дают луч ровно по ребру
        // двух клеток (45° с шагом 2.4), и проверка «видно» с трассой Litematica расходились бы в том, чья клетка
        Vec3 center = new Vec3(cell.getX() + (shape.minX + shape.maxX) / 2.0D + 0.0123D,
                cell.getY() + (shape.minY + shape.maxY) / 2.0D - 0.0217D,
                cell.getZ() + (shape.minZ + shape.maxZ) / 2.0D + 0.0091D);
        List<Vec3> candidates = new ArrayList<>();

        // LL_BUILD_APPROACH=side: только сбоку, глаз на высоте середины формы — луч бьёт в боковую грань
        if ("side".equalsIgnoreCase(String.valueOf(System.getenv("LL_BUILD_APPROACH")).trim()))
        {
            for (Direction d : Direction.Plane.HORIZONTAL)
            {
                BlockPos near = cell.relative(d);

                if (! schematic.getBlockState(near).isAir() || ! level.getBlockState(near).getCollisionShape(level, near).isEmpty())
                {
                    continue;
                }

                Vec3 eye = new Vec3(center.x + d.getStepX() * 2.3D, center.y, center.z + d.getStepZ() * 2.3D);

                if (free(level, schematic, eye, cell))
                {
                    return new Vec3[]{eye, center};
                }
            }

            return null;
        }

        for (Direction d : new Direction[]{Direction.UP, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.DOWN})
        {
            candidates.add(switch (d)
            {
                case UP -> new Vec3(cell.getX() + 0.5D, cell.getY() + 3.2D, cell.getZ() + 0.5D);
                case DOWN -> new Vec3(cell.getX() + 0.5D, cell.getY() - 1.3D, cell.getZ() + 0.5D);
                default -> Vec3.atCenterOf(cell).add(d.getStepX() * 2.3D, 0.4D, d.getStepZ() * 2.3D);
            });
        }

        for (double r : new double[]{2.4D, 3.2D})
        {
            for (int dy : new int[]{2, 1, 0, 3, -1})
            {
                for (int a = 0; a < 16; ++a)
                {
                    double angle = Math.PI * 2.0D * a / 16.0D;
                    candidates.add(Vec3.atCenterOf(cell).add(Math.cos(angle) * r, dy + 0.4D, Math.sin(angle) * r));
                }
            }
        }

        for (Vec3 eye : candidates)
        {
            if (eye.distanceTo(center) > 4.3D || ! free(level, schematic, eye, cell) || ! sees(level, schematic, eye, center, cell))
            {
                continue;
            }

            return new Vec3[]{eye, center};
        }

        return null;
    }

    /** Луч из глаза в точку: в мире — пусто до самой клетки, в схеме — первой встречается эта клетка. */
    private static boolean sees(ClientLevel level, WorldSchematic schematic, Vec3 eye, Vec3 point, BlockPos cell)
    {
        // Ровно до точки: продлённый луч у тонких блоков (провод, ковёр) упирался бы в пол под ними
        Vec3 end = point;
        var player = net.minecraft.world.phys.shapes.CollisionContext.empty();
        var world = level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));

        if (world.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && ! world.getBlockPos().equals(cell))
        {
            return false;
        }

        var ghost = schematic.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return ghost.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && ghost.getBlockPos().equals(cell);
    }

    /** Тело игрока с глазами в {@code eye} не задевает ни мир, ни саму клетку. */
    private static boolean free(ClientLevel level, WorldSchematic schematic, Vec3 eye, BlockPos cell)
    {
        AABB body = new AABB(eye.x - 0.3D, eye.y - 1.62D, eye.z - 0.3D, eye.x + 0.3D, eye.y + 0.18D, eye.z + 0.3D);

        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX, body.maxY, body.maxZ)))
        {
            if (p.equals(cell) || ! level.getBlockState(p).getCollisionShape(level, p).isEmpty())
            {
                return false;
            }
        }

        return true;
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

    // ===== Клавиша активации Easy Place =====

    private static void hold()
    {
        setHeld(true);
    }

    private static void release()
    {
        setHeld(false);
    }

    private static void setHeld(boolean on)
    {
        try
        {
            Object keybind = Hotkeys.EASY_PLACE_ACTIVATION.getKeybind();
            Field pressed = keybind.getClass().getDeclaredField("pressed");
            pressed.setAccessible(true);
            pressed.setBoolean(keybind, on);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log("[build] клавишу активации не зажать: " + e);
        }
    }

    /** Почему клетка не встала: рука, запас, кэш и темп Litematica, скрытая голограмма, ожидания сервера. */
    private void diagnose(Minecraft mc, WorldSchematic schematic, BlockPos cell)
    {
        StringBuilder out = new StringBuilder("[build]   не встала " + rel(cell) + ": мир " + name(mc.level.getBlockState(cell))
                + ", в руке " + mc.player.getMainHandItem());
        ItemStack need = required(schematic, cell);
        int have = 0;

        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); ++slot)
        {
            if (mc.player.getInventory().getItem(slot).is(need.getItem()))
            {
                have += mc.player.getInventory().getItem(slot).getCount();
            }
        }

        out.append(", нужен ").append(BuiltInRegistries.ITEM.getKey(need.getItem()).getPath()).append(" — есть ").append(have);

        try
        {
            Class<?> utils = fi.dy.masa.litematica.util.EasyPlaceUtils.class;
            var cached = utils.getDeclaredMethod("easyPlaceIsPositionCached", BlockPos.class);
            var fast = utils.getDeclaredMethod("easyPlaceIsTooFast");
            cached.setAccessible(true);
            fast.setAccessible(true);
            out.append(", кэш ").append(cached.invoke(null, cell)).append(", быстро ").append(fast.invoke(null));
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            out.append(", кэш ?");
        }

        out.append(", спрятана ").append(com.aell.layerlist.ghost.HiddenGhosts.hidden(cell));
        out.append(", взгляд ").append(mc.hitResult == null ? "нет" : mc.hitResult.getType() + " "
                + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? b.getBlockPos().subtract(this.min).toShortString() : ""));
        CovOut.log(out.toString());
    }

    // ===== Итог =====

    private void verify(MinecraftServer server, WorldSchematic schematic)
    {
        List<BlockPos> cells = new ArrayList<>(this.decor);
        Map<BlockPos, BlockState> wanted = new LinkedHashMap<>();

        for (BlockPos p : cells)
        {
            wanted.put(p, schematic.getBlockState(p));
        }

        List<String> lines = server.submit(() -> {
            List<String> out = new ArrayList<>();
            ServerLevel level = server.overworld();
            int ok = 0;
            int growth = 0;
            int twins = 0;

            for (Map.Entry<BlockPos, BlockState> e : wanted.entrySet())
            {
                BlockState want = e.getValue();
                BlockState have = level.getBlockState(e.getKey());

                if (same(have, want))
                {
                    ++ok;
                    continue;
                }

                // Угол ступени второй записью: фигура та же
                if (com.aell.layerlist.easyplace.Stairs.sameShape(want, have))
                {
                    ++ok;
                    ++twins;
                    continue;
                }

                // Возраст и стадия роста ваниль задаёт случайно при установке и двигает сама — это не работа Easy Place
                if (onlyGrowth(want, have))
                {
                    ++growth;
                    out.add("РОСТ " + rel(e.getKey()) + " " + name(want) + " → " + diff(want, have));
                    continue;
                }

                String what = have.isAir() ? "НЕТ" : ! have.is(want.getBlock()) ? "ЧУЖОЙ " + name(have) : "СВОЙСТВА " + diff(want, have);
                out.add(rel(e.getKey()) + " " + name(want) + " → " + what);
            }

            // Лишние блоки: там, где схема пуста, — так видно блок, съехавший на клетку за гранью
            int extra = 0;

            for (BlockPos p : BlockPos.betweenClosed(this.min, this.max))
            {
                if (schematic.getBlockState(p).isAir() && ! level.getBlockState(p).isAir())
                {
                    ++extra;
                    out.add("ЛИШНИЙ " + rel(p) + " " + name(level.getBlockState(p)));
                }
            }

            out.add(0, "совпало " + ok + " из " + wanted.size() + ", ещё " + growth + " отличаются только возрастом роста, лишних " + extra
                    + ", ступеней второй записью угла " + twins);
            return out;
        }).join();

        CovOut.log("[build] итог (" + this.profile + (this.creative ? ", креатив" : "") + "): " + lines.get(0));

        for (int i = 1; i < lines.size(); ++i)
        {
            CovOut.log("[build]   " + lines.get(i));
        }

        for (BlockPos p : this.unreachable)
        {
            CovOut.log("[build]   не подойти к " + rel(p) + " " + name(schematic.getBlockState(p)));
        }
    }

    private static boolean same(BlockState have, BlockState want)
    {
        return have == want;
    }

    /**
     * Клетку можно оставить: стоит как в схеме или ступень уже легла нужной спиной и половиной
     * (своей записью угла или второй). Форму ступень доберёт от соседей позже — её судит итог.
     */
    private static boolean placed(BlockState have, BlockState want)
    {
        if (same(have, want) || com.aell.layerlist.easyplace.Stairs.sameShape(want, have))
        {
            return true;
        }

        if (! com.aell.layerlist.easyplace.Stairs.is(want) || ! have.is(want.getBlock()))
        {
            return false;
        }

        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF;
        BlockState twin = com.aell.layerlist.easyplace.Stairs.twin(want);

        return have.getValue(half) == want.getValue(half)
                && (have.getValue(facing) == want.getValue(facing) || have.getValue(facing) == twin.getValue(facing));
    }

    /** Тот же блок, и разница — только в возрасте или стадии роста (лозы, ламинария, саженцы, посевы). */
    private static boolean onlyGrowth(BlockState want, BlockState have)
    {
        if (! have.is(want.getBlock()) || ! want.isRandomlyTicking() && ! have.isRandomlyTicking())
        {
            return false;
        }

        boolean any = false;

        for (Property<?> property : want.getProperties())
        {
            if (have.hasProperty(property) && ! have.getValue(property).equals(want.getValue(property)))
            {
                if (! property.getName().equals("age") && ! property.getName().equals("stage"))
                {
                    return false;
                }

                any = true;
            }
        }

        return any;
    }

    private static String diff(BlockState want, BlockState have)
    {
        StringBuilder out = new StringBuilder();

        for (Property<?> property : want.getProperties())
        {
            if (have.hasProperty(property) && ! have.getValue(property).equals(want.getValue(property)))
            {
                out.append(property.getName()).append('=').append(have.getValue(property))
                        .append(" (надо ").append(want.getValue(property)).append(") ");
            }
        }

        return out.toString().trim();
    }

    private String rel(BlockPos p)
    {
        BlockPos r = p.subtract(this.min);
        return r.getX() + "/" + r.getY() + "/" + r.getZ();
    }

    private static String name(BlockState state)
    {
        return state.toString().replace("Block{minecraft:", "").replace("}", "");
    }

    // ===== Профиль =====

    static void applyProfile(String profile)
    {
        boolean user = profile.startsWith("user");
        boolean fill = profile.contains("fill");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("easyPlaceFix", true);
        values.put("easyPlaceSkipWrongState", true);
        values.put("easyPlaceOnlySchematic", true);
        values.put("easyPlaceStrictSchematic", user);
        values.put("easyPlaceStrictBlock", user);
        values.put("easyPlaceFixLit", true);
        values.put("easyPlaceFixWaterlogged", true);
        values.put("easyPlaceFixFilling", fill);
        values.put("easyPlaceStripLogs", true);
        values.put("easyPlaceFinishTools", true);
        values.put("easyPlaceOpenContainers", true);
        values.put("easyPlaceVanillaServer", true);
        values.put("easyPlaceNoTurn", false);
        values.put("easyPlacePoseAhead", false);
        values.put("easyPlaceJitter", false);
        values.put("easyPlaceDigGuard", false);
        values.put("easyPlaceAutoTool", true);
        values.put("easyPlaceReplaceSoil", false);
        values.put("easyPlaceDirtForGrass", true);
        values.put("easyPlaceEntities", true);
        values.put("easyPlaceBlocksPerSecond", 10);
        values.put("easyPlaceConfirmWindow", 300);
        values.put("easyPlaceAutomationWindow", 1000);
        values.put("easyPlaceDebug", true);
        values.put("ghostInventoryOnly", user);
        values.put("ghostHideUnsupported", user);
        values.put("ghostSupportFromLayers", user);

        set(LayerListConfigs.OPTIONS, values);

        try
        {
            @SuppressWarnings("unchecked")
            List<IConfigBase> epf = (List<IConfigBase>) Class.forName("com.aell.easyplacefixplus.config.FixConfigs")
                    .getField("OPTIONS").get(null);
            set(epf, values);
        }
        catch (ReflectiveOperationException | LinkageError ignored)
        {
            // Easy Place Fix+ нет — ведёт Material HUD
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_HOLD_ENABLED.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
        Configs.Generic.EASY_PLACE_SWAP_INTERVAL.setIntegerValue(5);
        Configs.Generic.EASY_PLACE_VANILLA_REACH.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_CLICK_ADJACENT.setBooleanValue(false);
        Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.setBooleanValue(true);
        Configs.Generic.PICK_BLOCK_AVOID_TOOLS.setBooleanValue(false);
        Configs.Generic.PICK_BLOCKABLE_SLOTS.setValueFromString("1,2,3,4,5,6,7,8,9");
        Configs.Generic.PLACEMENT_RESTRICTION.setBooleanValue(false);

        // Сервер в локальной сети: ожидание подтверждений от 100 мс (в одиночной игре его нет)
        setForced(user ? 100L : 0L);
    }

    private static void set(List<IConfigBase> options, Map<String, Object> values)
    {
        for (IConfigBase option : options)
        {
            Object value = values.get(option.getName());

            if (value instanceof Boolean b && option instanceof IConfigBoolean config)
            {
                config.setBooleanValue(b);
            }
            else if (value instanceof Integer i && option instanceof IConfigInteger config)
            {
                config.setIntegerValue(i);
            }
        }
    }

    private static void setForced(long ms)
    {
        InventorySync.forcedSettleMs = ms;

        try
        {
            Class.forName("com.aell.easyplacefixplus.core.InventorySync").getField("forcedSettleMs").setLong(null, ms);
        }
        catch (ReflectiveOperationException | LinkageError ignored)
        {
        }
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[build] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        release();

        if (this.lag != null)
        {
            this.lag.delayMs = 0L;
        }

        resetProfile();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }

    /** Новые функции, которые профиль включил, — обратно выключены; Easy Place выключен. */
    static void resetProfile()
    {
        setForced(-1L);
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("easyPlaceStrictSchematic", false);
        off.put("easyPlaceStrictBlock", false);
        off.put("ghostInventoryOnly", false);
        off.put("ghostHideUnsupported", false);
        off.put("ghostSupportFromLayers", false);
        off.put("easyPlaceFixFilling", false);
        set(LayerListConfigs.OPTIONS, off);

        try
        {
            @SuppressWarnings("unchecked")
            List<IConfigBase> epf = (List<IConfigBase>) Class.forName("com.aell.easyplacefixplus.config.FixConfigs")
                    .getField("OPTIONS").get(null);
            set(epf, off);
        }
        catch (ReflectiveOperationException | LinkageError ignored)
        {
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
    }
}
