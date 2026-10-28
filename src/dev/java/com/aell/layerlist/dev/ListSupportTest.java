package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Режим {@code listsupport}: список материалов HUD по слою — только из схемы, с опорами вне слоя
 * (запрос 13.10.2026: «не должен зависеть от прорисовки», «блоки опоры», «все блоки, которым нужна опора»).
 *
 * <p>Сцена: пол из камня; на нём табличка, факел, ковёр, рельс, песок, камень (контроль) и баннер с узором;
 * слоем выше — подвесная табличка под потолком, фонарь на цепи под потолком, плакучая лоза в две клетки и
 * камень (контроль). Схема ставится в пустое место, мир там пуст — всё «не хватает». Счёт снимается в трёх
 * положениях: рядом с отрисовкой, рядом без отрисовки и за 40 000 блоков (чанки не загружены, мира схемы
 * нет) — итоги всех трёх обязаны совпасть с эталоном.
 */
@Environment(EnvType.CLIENT)
public final class ListSupportTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(560, 100, 0);
    private static final BlockPos NEAR = new BlockPos(560, 100, 14);
    /**
     * Дальнее место — своё на каждый прогон: прошлый прогон там строил, и кэш проверки на диске честно помнит
     * постройку, а этому прогону нужен ни разу не виденный чанк.
     */
    private final BlockPos FAR = new BlockPos(40_000 + 64 * (int) (System.currentTimeMillis() / 1000L % 4000L), 100, 40_000);
    /** Список не менялся столько — пересчёт закончен. */
    private static final long STABLE_MS = 3000L;
    private static final long STAGE_LIMIT_MS = 30_000L;

    private static final String BANNER = "banner+узор";

    /** Слой 1 — на полу; опора каждого, кроме камня, — камень пола (6 клеток). */
    private static final Map<String, Integer> LAYER1 = expect(
            "oak_sign", 1, "torch", 1, "white_carpet", 1, "rail", 1, "sand", 1, "stone", 1 + 6, BANNER, 1);
    /** Слой 2 — висящие; потолок над табличкой, цепь над фонарём (сама цепь висит), тело лозы и камень над ним. */
    private static final Map<String, Integer> LAYER2 = expect(
            "oak_hanging_sign", 1, "lantern", 1, "iron_chain", 1, "weeping_vines", 2, "stone", 1 + 1 + 1);
    /** Слои 1–2 вместе: опоры между ними внутри диапазона, снаружи — пол снизу и потолок с камнем сверху. */
    private static final Map<String, Integer> BOTH = expect(
            "oak_sign", 1, "torch", 1, "white_carpet", 1, "rail", 1, "sand", 1, BANNER, 1,
            "oak_hanging_sign", 1, "lantern", 1, "iron_chain", 1, "weeping_vines", 2, "stone", 1 + 6 + 1 + 1 + 1);

    /** Блоки слоя 1 на месте схемы с началом {@code o} (баннер — с тем же узором, что в схеме). */
    private static List<String> layer1Blocks(BlockPos o)
    {
        return List.of(
                set(o, 0, 1, "minecraft:oak_sign[rotation=0]"),
                set(o, 1, 1, "minecraft:torch"),
                set(o, 2, 1, "minecraft:white_carpet"),
                set(o, 3, 1, "minecraft:rail"),
                set(o, 4, 1, "minecraft:sand"),
                set(o, 5, 1, "minecraft:stone"),
                set(o, 6, 1, "minecraft:white_banner[rotation=0]{patterns:[{color:\"red\",pattern:\"minecraft:cross\"}]}"));
    }

    private int stage;
    private long nextAt;
    private long stageStart;
    private boolean failed;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;
    private String lastSignature = "";
    private long stableSince;
    /** Когда отдали команды стройки; когда список впервые совпал с ожиданием после них. */
    private final java.util.concurrent.atomic.AtomicLong builtAt = new java.util.concurrent.atomic.AtomicLong();
    private long matchedAt;

    private static Map<String, Integer> expect(Object... pairs)
    {
        TreeMap<String, Integer> out = new TreeMap<>();

        for (int i = 0; i < pairs.length; i += 2)
        {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }

        return out;
    }

    @Override
    public String name()
    {
        return "listsupport (список по схеме, опоры вне слоя)";
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

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(10);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 550 -10 580 30");

            for (BlockPos o : new BlockPos[]{REF, NEAR})
            {
                command(server, fill(o, -2, -1, -2, 10, 6, 4, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 1, 6, 0, 1, "minecraft:stone"));
            command(server, set(REF, 0, 1, "minecraft:oak_sign[rotation=0]"));
            command(server, set(REF, 1, 1, "minecraft:torch"));
            command(server, set(REF, 2, 1, "minecraft:white_carpet"));
            command(server, set(REF, 3, 1, "minecraft:rail"));
            command(server, set(REF, 4, 1, "minecraft:sand"));
            command(server, set(REF, 5, 1, "minecraft:stone"));
            command(server, set(REF, 6, 1,
                    "minecraft:white_banner[rotation=0]{patterns:[{color:\"red\",pattern:\"minecraft:cross\"}]}"));
            command(server, set(REF, 0, 3, "minecraft:stone"));
            command(server, set(REF, 0, 2, "minecraft:oak_hanging_sign[rotation=0,attached=false]"));
            command(server, set(REF, 1, 4, "minecraft:stone"));
            command(server, set(REF, 1, 3, "minecraft:iron_chain[axis=y]"));
            command(server, set(REF, 1, 2, "minecraft:lantern[hanging=true]"));
            command(server, set(REF, 2, 4, "minecraft:stone"));
            command(server, set(REF, 2, 3, "minecraft:weeping_vines_plant"));
            command(server, set(REF, 2, 2, "minecraft:weeping_vines[age=25]"));
            command(server, set(REF, 3, 2, "minecraft:stone"));
            command(server, "tp @a " + (NEAR.getX() + 3.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10");
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

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-listsupport");
                area.addSubRegionBox(new Box(REF, REF.offset(6, 4, 1), "main"), false);
                area.setExplicitOrigin(REF);
                this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.place(NEAR, true);
                this.layer(1);
                this.next(now);
            }
            case 1 -> { if (this.settled(mc, now, "рядом, отрисовка, слой 1", LAYER1)) { this.layer(2); this.next(now); } }
            case 2 -> { if (this.settled(mc, now, "рядом, отрисовка, слой 2", LAYER2)) { this.range(1, 2); this.next(now); } }
            case 3 -> { if (this.settled(mc, now, "рядом, отрисовка, слои 1–2", BOTH)) { this.placement.setRenderSchematic(false); this.layer(1); this.next(now); } }
            case 4 -> { if (this.settled(mc, now, "рядом без отрисовки, слой 1", LAYER1)) { this.layer(2); this.next(now); } }
            case 5 -> { if (this.settled(mc, now, "рядом без отрисовки, слой 2", LAYER2)) { this.place(FAR, true); this.layer(1); this.next(now); } }
            case 6 -> { if (this.settled(mc, now, "далеко (чанки не загружены), слой 1", LAYER1)) { this.layer(2); this.next(now); } }
            case 7 -> { if (this.settled(mc, now, "далеко (чанки не загружены), слой 2", LAYER2)) { this.range(1, 2); this.next(now); } }
            case 8 ->
            {
                if (this.settled(mc, now, "далеко (чанки не загружены), слои 1–2", BOTH))
                {
                    // Стройка рядом: список обязан следовать за миром сам, без таймера (интервал — максимум, 10 с)
                    LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(200);
                    this.place(NEAR, true);
                    this.layer(1);
                    this.next(now);
                }
            }
            case 9 -> { if (this.settled(mc, now, "стройка: пусто", LAYER1)) { this.build(mc, now, fill(NEAR, 0, 0, 1, 6, 0, 1, "minecraft:stone")); } }
            case 10 ->
            {
                if (this.settled(mc, now, "стройка: пол-опора поставлен", LAYER1, missing(LAYER1, "stone", 1, "oak_sign", 1, "torch", 1,
                        "white_carpet", 1, "rail", 1, "sand", 1, BANNER, 1), true))
                {
                    this.build(mc, now, layer1Blocks(NEAR).toArray(new String[0]));
                }
            }
            case 11 ->
            {
                if (this.settled(mc, now, "стройка: слой 1 построен", LAYER1, missing(LAYER1), true))
                {
                    this.build(mc, now, "setblock " + (NEAR.getX() + 6) + " " + (NEAR.getY() + 1) + " " + (NEAR.getZ() + 1)
                            + " minecraft:white_banner[rotation=0]{patterns:[{color:\"blue\",pattern:\"minecraft:border\"}]} replace");
                }
            }
            case 12 ->
            {
                if (this.settled(mc, now, "стройка: баннер с чужим узором", LAYER1, missing(LAYER1, BANNER, 1), true))
                {
                    // Прогресс дальнего чанка: строим вдали, уходим — счёт держится из кэша проверки
                    this.place(FAR, true);
                    this.layer(1);
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        command(server, "tp @a " + (FAR.getX() + 3.5D) + " " + (FAR.getY() + 8) + " " + (FAR.getZ() + 8.5D) + " 180 30");
                        command(server, fill(FAR, -2, -1, -2, 10, 6, 4, "minecraft:air"));
                    });
                    this.next(now);
                    this.nextAt = now + 6000L;
                }
            }
            case 13 ->
            {
                if (this.settled(mc, now, "вдали: пусто, чанк загружен", LAYER1))
                {
                    List<String> cmds = new ArrayList<>(List.of(fill(FAR, 0, 0, 1, 6, 0, 1, "minecraft:stone")));

                    for (String c : layer1Blocks(FAR))
                    {
                        if (! c.contains("torch"))
                        {
                            cmds.add(c);
                        }
                    }

                    this.build(mc, now, cmds.toArray(new String[0]));
                }
            }
            case 14 ->
            {
                if (this.settled(mc, now, "вдали: построено всё, кроме факела", LAYER1, missing(LAYER1, "torch", 1), true))
                {
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> command(server,
                            "tp @a " + (NEAR.getX() + 3.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10"));
                    this.next(now);
                    this.nextAt = now + 6000L;
                }
            }
            case 15 ->
            {
                LayerCountManager hud = LayerCountManager.getInstance();
                // С Bobby ушедший чанк ещё долго «загружен» — его кэш-чанком (14.10.2026): счёт по нему, не по кэшу проверки
                boolean bobby = bobbyChunk(mc, FAR);

                if (! bobby && hud.getCachedChunks() < 1 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.settled(mc, now, "ушли от постройки — счёт из кэша", LAYER1, missing(LAYER1, "torch", 1), false))
                {
                    this.check(bobby ? "чанк постройки — кэш-чанк Bobby" : "чанк постройки считается из кэша",
                            bobby ? hud.getUnverifiedChunks() == 0 : hud.getCachedChunks() == 1 && hud.getUnverifiedChunks() == 0,
                            "из кэша " + hud.getCachedChunks() + ", непроверенных " + hud.getUnverifiedChunks());
                    CovOut.log("[listsupport] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
            default -> { return true; }
        }

        return false;
    }

    /** Чанк клиента в этой клетке — кэш-чанк Bobby ({@code FakeChunk}), а не присланный сервером. */
    private static boolean bobbyChunk(Minecraft mc, BlockPos pos)
    {
        net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.getX() >> 4,
                pos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        return chunk != null && chunk.getClass().getName().equals("de.johni0702.minecraft.bobby.FakeChunk");
    }

    /** Отдать команды стройки и ждать, что список догонит мир сам: без {@code markDirty}, таймер — 10 с. */
    private void build(Minecraft mc, long now, String... commands)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        this.builtAt.set(0L);
        server.execute(() -> {
            for (String c : commands)
            {
                command(server, c);
            }

            // Отсчёт — от того, как сервер правда поставил блоки: под нагрузкой команды ждут его тика
            this.builtAt.set(Util.getMillis());
        });
        ++this.stage;
        this.stageStart = now;
        this.matchedAt = 0L;
        this.lastSignature = "";
    }

    /** Ожидание «не хватает»: всё по нулям, кроме перечисленного. */
    private static Map<String, Integer> missing(Map<String, Integer> totals, Object... left)
    {
        TreeMap<String, Integer> out = new TreeMap<>();
        totals.keySet().forEach(k -> out.put(k, 0));

        for (int i = 0; i < left.length; i += 2)
        {
            out.put((String) left[i], (Integer) left[i + 1]);
        }

        return out;
    }

    private void next(long now)
    {
        ++this.stage;
        this.stageStart = now;
        this.lastSignature = "";
        this.nextAt = now + 1000L;
        LayerCountManager.getInstance().markDirty();
    }

    private void place(BlockPos origin, boolean render)
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        this.placement = SchematicPlacement.createFor(this.schematic, origin, "dev-listsupport", true, render);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
    }

    private void layer(int dy)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(LayerMode.SINGLE_LAYER);
        range.setAxis(Direction.Axis.Y);
        range.setLayerSingle(this.placement.getOrigin().getY() + dy);
    }

    private void range(int from, int to)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(LayerMode.LAYER_RANGE);
        range.setAxis(Direction.Axis.Y);
        // Сеттеры malilib сверяют границу с другой, ещё старой (из прошлого прогона), — ставим дважды
        for (int i = 0; i < 2; ++i)
        {
            range.setLayerRangeMin(this.placement.getOrigin().getY() + from);
            range.setLayerRangeMax(this.placement.getOrigin().getY() + to);
        }
    }

    /** Мир на месте схемы пуст: не хватает всего. */
    private boolean settled(Minecraft mc, long now, String what, Map<String, Integer> expected)
    {
        return this.settled(mc, now, what, expected, expected, false);
    }

    /**
     * Ждёт, пока список не меняется {@link #STABLE_MS}, и сверяет итоги и «не хватает» с ожиданием. {@code timed} —
     * после стройки: список должен догнать мир быстрее периодического пересчёта.
     */
    private boolean settled(Minecraft mc, long now, String what, Map<String, Integer> expected,
                            Map<String, Integer> expectedMissing, boolean timed)
    {
        LayerCountManager hud = LayerCountManager.getInstance();
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Integer> missing = new TreeMap<>();

        for (MaterialListEntry entry : hud.getEntries())
        {
            String key = key(entry.getStack());
            totals.merge(key, entry.getCountTotal(), Integer::sum);
            missing.merge(key, entry.getCountMissing(), Integer::sum);
        }

        if (timed && this.builtAt.get() == 0L)
        {
            this.stageStart = now;
            return false;
        }

        String signature = hud.hasData() + " " + totals + " " + missing;

        if (timed && this.matchedAt == 0L && missing.equals(expectedMissing))
        {
            this.matchedAt = now;
        }

        if (! signature.equals(this.lastSignature))
        {
            this.lastSignature = signature;
            this.stableSince = now;
            return false;
        }

        if (now - this.stableSince < STABLE_MS && now - this.stageStart < STAGE_LIMIT_MS)
        {
            return false;
        }

        this.check(what + " — итоги", totals.equals(expected), "есть " + totals + ", ждали " + expected);
        this.check(what + " — не хватает", missing.equals(expectedMissing),
                "не хватает " + missing + ", ждали " + expectedMissing);

        if (timed)
        {
            long took = this.matchedAt == 0L ? -1L : this.matchedAt - this.builtAt.get();
            this.check(what + " — список догнал стройку сам, быстрее таймера", took >= 0L && took < 6000L,
                    "через " + took + " мс (таймер 10 000 мс)");
        }

        LayerRange range = DataManager.getRenderLayerRange();
        CovOut.log("[listsupport] " + what + ": слои " + range.getLayerMode() + " " + range.getMinLayerBoundary() + ".."
                + range.getMaxLayerBoundary() + ", непроверенных чанков " + hud.getUnverifiedChunks()
                + ", из кэша " + hud.getCachedChunks());
        return true;
    }

    private static String key(ItemStack stack)
    {
        BannerPatternLayers patterns = stack.get(DataComponents.BANNER_PATTERNS);

        if (patterns != null && ! patterns.layers().isEmpty())
        {
            return BANNER;
        }

        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String set(BlockPos o, int x, int y, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY() + y, o.getZ() + 1, block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[listsupport] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[listsupport] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(20);
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }
}
