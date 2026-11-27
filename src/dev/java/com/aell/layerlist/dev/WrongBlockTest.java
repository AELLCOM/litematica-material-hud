package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.Shortfall;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.config.Configs;
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
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Режим {@code wrongblock} (27.11.2026): настройка «Считать „Не тот блок“» ({@code shortfallWrongBlocks}) и регресс
 * прежних режимов нехватки. Недостроенная схема в одном чанке: пустые клетки, блок другого типа, тот же блок в другом
 * состоянии, флаг с другим узором и узорный флаг на месте обычного.
 *
 * <p>Сверяются «всего», «не хватает» и «не то» у списка HUD и у счётчика вкладки «Материалы» (его же строки идут в
 * выгрузку) во всех сочетаниях двух тумблеров — и трижды: чанк загружен, чанк ушёл (кэш проверки в памяти), кэш
 * перечитан с диска (там «не тот блок» лежит отдельным тегом). Умолчание и «Только пустые места» выкл. обязаны давать
 * те же числа, что до правки.
 */
@Environment(EnvType.CLIENT)
public final class WrongBlockTest implements CovJob
{
    private static final String T = "[wrongblock] ";
    private static final BlockPos REF = new BlockPos(704, 100, 0);
    private static final BlockPos NEAR = new BlockPos(704, 100, 14);
    private final BlockPos FAR = new BlockPos(44_000 + 64 * (int) (System.currentTimeMillis() / 1000L % 4000L), 100, 52_000);
    private static final long STABLE_MS = 4000L;
    private static final long STAGE_LIMIT_MS = 40_000L;
    private static final String BANNER = "banner+узор";
    private static final String CROSS = "{patterns:[{color:\"red\",pattern:\"minecraft:cross\"}]}";
    private static final String STRIPE = "{patterns:[{color:\"blue\",pattern:\"minecraft:stripe_bottom\"}]}";

    /** Схема: пол 11, камень 4, ступени 3, флаг с узором 3, обычный флаг 1. */
    private static final Map<String, Integer> TOTAL =
            expect("smooth_stone", 11, "stone", 4, "oak_stairs", 3, BANNER, 3, "white_banner", 1);
    /** «Не то» от режима не зависит: земля; ступень задом и булыжник; флаг с другим узором и повёрнутый; узорный на месте обычного. */
    private static final Map<String, Integer> MISMATCH = expect("stone", 1, "oak_stairs", 2, BANNER, 2, "white_banner", 1);
    /** Только пустые: камень 2, ступень 1, флаг с узором 1. */
    private static final Map<String, Integer> EMPTY = expect("stone", 2, "oak_stairs", 1, BANNER, 1);
    /** + не тот блок: земля на месте камня, булыжник на месте ступени, другой узор, узорный на месте обычного. */
    private static final Map<String, Integer> WRONG = expect("stone", 3, "oak_stairs", 2, BANNER, 2, "white_banner", 1);
    /** Всё, что стоит не так: ещё ступень задом и повёрнутый флаг. */
    private static final Map<String, Integer> ALL = expect("stone", 3, "oak_stairs", 3, BANNER, 3, "white_banner", 1);

    /** Сочетание тумблеров и нехватка при нём. Первое и последнее — умолчание: возврат обязан дать те же числа. */
    private record Mode(String name, boolean onlyEmpty, boolean wrongBlocks, Map<String, Integer> missing) {}

    private static final List<Mode> MODES = List.of(
            new Mode("умолчание (только пустые)", true, false, EMPTY),
            new Mode("+ «Не тот блок»", true, true, WRONG),
            new Mode("«Только пустые места» выкл., «Не тот блок» вкл.", false, true, ALL),
            new Mode("«Только пустые места» выкл., «Не тот блок» выкл.", false, false, ALL),
            new Mode("снова умолчание", true, false, EMPTY));

    private static final String[] PHASES = {"чанк загружен", "кэш в памяти", "кэш с диска"};

    private int stage;
    private int mode = -1;
    private long nextAt;
    private long stageStart;
    private long tabTickAt;
    private boolean failed;
    private int checks;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;
    private LayerCountManager tab;
    private String lastSignature = "";
    private long stableSince;
    private boolean onlyEmptyWas;
    private boolean wrongBlocksWas;
    private boolean ignoreStateWas;
    private boolean onlyMissingWas;

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
        return "wrongblock («Не тот блок» в нехватке: HUD, «Материалы», кэш проверки)";
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

        this.onlyEmptyWas = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        this.wrongBlocksWas = LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue();
        this.onlyMissingWas = LayerListConfigs.ONLY_MISSING.getBooleanValue();
        this.ignoreStateWas = Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue();

        this.check("умолчание новой настройки — выкл.",
                ! LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getDefaultBooleanValue(), "");
        this.check("умолчание «Только пустые места» — вкл.",
                LayerListConfigs.SHORTFALL_ONLY_MISSING.getDefaultBooleanValue(), "");

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(10);
        Configs.Generic.MATERIAL_LIST_IGNORE_STATE.setBooleanValue(false);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        this.apply(MODES.get(0));
        this.texts();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 694 -10 724 30");

            for (BlockPos o : new BlockPos[]{REF, NEAR})
            {
                command(server, fill(o, -2, -1, -2, 12, 4, 4, "minecraft:air"));
            }

            // Образец схемы — построен целиком
            command(server, fill(REF, 0, 0, 1, 10, 0, 1, "minecraft:smooth_stone"));
            command(server, fill(REF, 0, 1, 1, 3, 1, 1, "minecraft:stone"));
            command(server, fill(REF, 4, 1, 1, 6, 1, 1, "minecraft:oak_stairs[facing=north]"));

            for (int x = 7; x <= 9; ++x)
            {
                command(server, set(REF, x, 1, "minecraft:white_banner[rotation=0]" + CROSS));
            }

            command(server, set(REF, 10, 1, "minecraft:white_banner[rotation=4]"));
            command(server, "tp @a " + (NEAR.getX() + 5.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    /** Тексты окна выгрузки: у списка HUD три разных пояснения, прежние перегрузки отвечают прежними текстами. */
    private void texts()
    {
        String empty = ExportWindow.filterAbout(ExportSource.Kind.HUD, ExportOptions.Amount.MISSING, Shortfall.EMPTY);
        String wrong = ExportWindow.filterAbout(ExportSource.Kind.HUD, ExportOptions.Amount.MISSING, Shortfall.WRONG_BLOCKS);
        String all = ExportWindow.filterAbout(ExportSource.Kind.HUD, ExportOptions.Amount.MISSING, Shortfall.ALL);

        this.check("выгрузка: три разных пояснения списка HUD", empty != null && wrong != null && all != null
                && ! empty.equals(wrong) && ! wrong.equals(all) && ! empty.equals(all), empty + " | " + wrong + " | " + all);
        this.check("выгрузка: текст нового режима переведён", wrong != null && ! wrong.contains("filter.about"), wrong);
        this.check("выгрузка: прежняя перегрузка «только пустые» — прежний текст",
                String.valueOf(ExportWindow.filterAbout(ExportSource.Kind.HUD, true, true)).equals(empty), "");
        this.check("выгрузка: прежняя перегрузка «с не тем» — прежний текст",
                String.valueOf(ExportWindow.filterAbout(ExportSource.Kind.HUD, true, false)).equals(all), "");

        this.apply(MODES.get(1));
        this.check("режим: «Не тот блок» вкл.", Shortfall.current() == Shortfall.WRONG_BLOCKS, "" + Shortfall.current());
        this.apply(MODES.get(2));
        this.check("режим: «Только пустые места» выкл. — всё", Shortfall.current() == Shortfall.ALL, "" + Shortfall.current());
        this.apply(MODES.get(0));
        this.check("режим: умолчание — только пустые", Shortfall.current() == Shortfall.EMPTY, "" + Shortfall.current());
    }

    @Override
    public boolean waiting()
    {
        long now = Util.getMillis();

        // Счётчик вкладки «Материалы» тикает сама вкладка; здесь её нет — тикаем сами, раз в игровой тик
        if (this.tab != null && now - this.tabTickAt >= 45L)
        {
            this.tabTickAt = now;
            this.tab.onClientTick(Minecraft.getInstance());
        }

        return now < this.nextAt;
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
        LayerCountManager hud = LayerCountManager.getInstance();

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-wrongblock");
                area.addSubRegionBox(new Box(REF, REF.offset(10, 1, 1), "main"), false);
                area.setExplicitOrigin(REF);
                this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(this.schematic, FAR, "dev-wrongblock", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> command(server, "tp @a " + (FAR.getX() + 5.5D) + " " + (FAR.getY() + 1) + " "
                        + (FAR.getZ() + 6.5D) + " 180 15"));
                this.next(now, 5000L);
            }
            case 1 ->
            {
                // Мир на месте постройки: пол целиком, остальное — недостроено и построено не так
                server.execute(() -> {
                    command(server, fill(FAR, -2, -1, -2, 12, 4, 8, "minecraft:air"));
                    command(server, fill(FAR, 0, 0, 1, 10, 0, 7, "minecraft:smooth_stone"));
                    command(server, set(FAR, 1, 1, "minecraft:dirt"));
                    command(server, set(FAR, 2, 1, "minecraft:stone"));
                    command(server, set(FAR, 5, 1, "minecraft:oak_stairs[facing=south]"));
                    command(server, set(FAR, 6, 1, "minecraft:cobblestone"));
                    command(server, set(FAR, 8, 1, "minecraft:white_banner[rotation=0]" + STRIPE));
                    command(server, set(FAR, 9, 1, "minecraft:white_banner[rotation=8]" + CROSS));
                    command(server, set(FAR, 10, 1, "minecraft:white_banner[rotation=4]" + CROSS));
                    command(server, "tp @a " + (FAR.getX() + 5.5D) + " " + (FAR.getY() + 1) + " "
                            + (FAR.getZ() + 6.5D) + " 180 15");
                });
                this.tab = LayerCountManager.forPlacements(p -> p == this.placement);

                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                this.next(now, 4000L);
            }
            case 2 ->
            {
                if (hud.getUnverifiedChunks() > 0 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.modes(mc, now, 0))
                {
                    LayerVerifier verifier = LayerVerifier.getInstance();
                    CovOut.log(T + "проверка блоков видит: пропущено " + verifier.getCount(LayerVerifier.Category.MISSING)
                            + ", не тот блок " + verifier.getCount(LayerVerifier.Category.WRONG_BLOCK)
                            + ", не то состояние " + verifier.getCount(LayerVerifier.Category.WRONG_STATE));
                    server.execute(() -> command(server,
                            "tp @a " + (NEAR.getX() + 5.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10"));
                    this.next(now, 6000L);
                }
            }
            case 3 ->
            {
                if (hud.getCachedChunks() < 1 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.modes(mc, now, 1))
                {
                    this.check("кэш в памяти — чанк постройки и правда не загружен", hud.getCachedChunks() >= 1,
                            "из кэша " + hud.getCachedChunks());
                    // Сменить подпись счёта: VerifyCache сохранит запись и при возврате перечитает её с диска
                    LayerRange range = DataManager.getRenderLayerRange();
                    range.setLayerMode(LayerMode.SINGLE_LAYER);
                    range.setAxis(Direction.Axis.Y);
                    range.setLayerSingle(FAR.getY() + 1);
                    this.next(now, 1000L);
                }
            }
            case 4 ->
            {
                if (this.quiet(now, this.signature(hud)))
                {
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                    this.next(now, 1000L);
                }
            }
            case 5 ->
            {
                if (hud.getCachedChunks() < 1 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.modes(mc, now, 2))
                {
                    this.check("кэш с диска — чанк постройки не загружен", hud.getCachedChunks() >= 1,
                            "из кэша " + hud.getCachedChunks());
                    CovOut.log(T + "проверок " + this.checks + ", итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
            default -> { return true; }
        }

        return false;
    }

    /** Пройти все сочетания тумблеров в этой фазе; true — пройдены все. */
    private boolean modes(Minecraft mc, long now, int phase)
    {
        if (this.mode < 0)
        {
            this.enter(0, now);
            return false;
        }

        Mode current = MODES.get(this.mode);
        LayerCountManager hud = LayerCountManager.getInstance();

        if (! this.quiet(now, this.signature(hud)))
        {
            return false;
        }

        String what = PHASES[phase] + ", " + current.name();
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Integer> missing = new TreeMap<>();
        Map<String, Integer> mismatch = new TreeMap<>();
        long rowsMissing = 0L;

        for (MaterialListEntry entry : hud.getEntries())
        {
            String key = key(entry.getStack());
            add(totals, key, entry.getCountTotal());
            add(missing, key, entry.getCountMissing());
            add(mismatch, key, entry.getCountMismatched());
            rowsMissing += entry.getCountMissing();
        }

        this.check(what + " — HUD всего", totals.equals(TOTAL), "есть " + totals + ", ждали " + TOTAL);
        this.check(what + " — HUD не хватает", missing.equals(current.missing()),
                "есть " + missing + ", ждали " + current.missing());
        this.check(what + " — HUD «не то» не зависит от режима", mismatch.equals(MISMATCH),
                "есть " + mismatch + ", ждали " + MISMATCH);
        this.check(what + " — сумма строк = итог панели", rowsMissing == hud.getBlocksMissing(),
                "строки " + rowsMissing + ", панель " + hud.getBlocksMissing());

        Map<String, Integer> tabTotals = new TreeMap<>();
        Map<String, Integer> tabMissing = new TreeMap<>();

        for (MaterialRow row : SchematicMaterials.ofCounter(this.tab))
        {
            String key = key(row.stack());
            add(tabTotals, key, (int) row.total());
            add(tabMissing, key, (int) row.missing());
        }

        this.check(what + " — «Материалы» всего", tabTotals.equals(TOTAL), "есть " + tabTotals + ", ждали " + TOTAL);
        this.check(what + " — «Материалы» не хватает", tabMissing.equals(current.missing()),
                "есть " + tabMissing + ", ждали " + current.missing());

        if (phase == 0 && this.mode <= 1)
        {
            CovOut.log(T + "снимок: " + current.name());
            Screenshot.grab(mc, false);
        }

        if (this.mode + 1 < MODES.size())
        {
            this.enter(this.mode + 1, now);
            return false;
        }

        this.mode = -1;
        return true;
    }

    private void enter(int mode, long now)
    {
        this.mode = mode;
        this.apply(MODES.get(mode));
        this.lastSignature = "";
        this.stageStart = now;
        this.nextAt = now + 500L;
        LayerCountManager.getInstance().markDirty();

        if (this.tab != null)
        {
            this.tab.markDirty();
        }
    }

    private void apply(Mode mode)
    {
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(mode.onlyEmpty());
        LayerListConfigs.SHORTFALL_WRONG_BLOCKS.setBooleanValue(mode.wrongBlocks());
    }

    private String signature(LayerCountManager hud)
    {
        // Только числа строк: счётчики прохода (чанков из кэша, «идёт счёт») мигают на каждом пересчёте
        StringBuilder out = new StringBuilder().append(hud.hasData()).append(' ').append(hud.getBlocksMissing());

        for (MaterialListEntry entry : hud.getEntries())
        {
            out.append(' ').append(key(entry.getStack())).append(':').append(entry.getCountTotal()).append('/')
                    .append(entry.getCountMissing()).append('/').append(entry.getCountMismatched());
        }

        if (this.tab != null)
        {
            out.append(" | ").append(this.tab.hasData());

            for (MaterialListEntry entry : this.tab.getEntries())
            {
                out.append(' ').append(key(entry.getStack())).append(':').append(entry.getCountTotal()).append('/')
                        .append(entry.getCountMissing());
            }
        }

        return out.toString();
    }

    /** Счёт перестал меняться (или вышел срок стадии). */
    private boolean quiet(long now, String signature)
    {
        if (! signature.equals(this.lastSignature))
        {
            this.lastSignature = signature;
            this.stableSince = now;
            return false;
        }

        return now - this.stableSince >= STABLE_MS || now - this.stageStart >= STAGE_LIMIT_MS;
    }

    private void next(long now, long delay)
    {
        ++this.stage;
        this.stageStart = now;
        this.lastSignature = "";
        this.nextAt = now + delay;
        LayerCountManager.getInstance().markDirty();
    }

    private static void add(Map<String, Integer> into, String key, int count)
    {
        if (count != 0)
        {
            into.merge(key, count, Integer::sum);
        }
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
        ++this.checks;
        CovOut.log(T + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log(T + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(20);
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(this.onlyEmptyWas);
        LayerListConfigs.SHORTFALL_WRONG_BLOCKS.setBooleanValue(this.wrongBlocksWas);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(this.onlyMissingWas);
        Configs.Generic.MATERIAL_LIST_IGNORE_STATE.setBooleanValue(this.ignoreStateWas);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }
}
