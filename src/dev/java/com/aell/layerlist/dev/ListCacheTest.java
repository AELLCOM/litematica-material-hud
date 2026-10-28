package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import java.util.ArrayList;
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
 * Режим {@code listcache} (14.10.2026, после ревью 1.0.10): дальний чанк, счёт из кэша проверки, прочитанного
 * с диска ({@code VerifyCache.DataCounts} нет). В чанке два одинаковых узорных баннера в разных состояниях
 * (поворот 0 и 8) — один ключ {@code DataItems.Key}, два {@code BlockState}. Ожидание: «не хватает» у баннера с
 * узором — 2, как и до перечитывания кэша; сумма строк совпадает с итогом футера.
 */
@Environment(EnvType.CLIENT)
public final class ListCacheTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(640, 100, 0);
    private static final BlockPos NEAR = new BlockPos(640, 100, 14);
    private final BlockPos FAR = new BlockPos(40_000 + 64 * (int) (System.currentTimeMillis() / 1000L % 4000L), 100, 48_000);
    private static final long STABLE_MS = 3000L;
    private static final long STAGE_LIMIT_MS = 30_000L;
    private static final String BANNER = "banner+узор";
    private static final String PATTERN = "{patterns:[{color:\"red\",pattern:\"minecraft:cross\"}]}";

    /** Всё пусто: пол 3 камня, два баннера с одним узором. */
    private static final Map<String, Integer> ALL = expect("stone", 3, BANNER, 2);

    private int stage;
    private long nextAt;
    private long stageStart;
    private boolean failed;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;
    private String lastSignature = "";
    private long stableSince;

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
        return "listcache (узорные баннеры в дальнем чанке из кэша с диска)";
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
        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(10);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 630 -10 660 30");

            for (BlockPos o : new BlockPos[]{REF, NEAR})
            {
                command(server, fill(o, -2, -1, -2, 6, 4, 4, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 1, 2, 0, 1, "minecraft:stone"));
            command(server, set(REF, 0, 1, "minecraft:white_banner[rotation=0]" + PATTERN));
            command(server, set(REF, 2, 1, "minecraft:white_banner[rotation=8]" + PATTERN));
            command(server, "tp @a " + (NEAR.getX() + 1.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10");
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
                area.setName("dev-listcache");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 1, 1), "main"), false);
                area.setExplicitOrigin(REF);
                this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.place(FAR);
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    command(server, "tp @a " + (FAR.getX() + 1.5D) + " " + (FAR.getY() + 8) + " " + (FAR.getZ() + 8.5D) + " 180 30");
                    command(server, fill(FAR, -2, -1, -2, 6, 4, 4, "minecraft:air"));
                });
                this.next(now);
                this.nextAt = now + 6000L;
            }
            case 1 ->
            {
                LayerCountManager hud = LayerCountManager.getInstance();

                if (hud.getUnverifiedChunks() > 0 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.settled(now, "вдали, чанк загружен и проверен", ALL, ALL))
                {
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> command(server,
                            "tp @a " + (NEAR.getX() + 1.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10"));
                    this.next(now);
                    this.nextAt = now + 6000L;
                }
            }
            case 2 ->
            {
                LayerCountManager hud = LayerCountManager.getInstance();

                if (hud.getCachedChunks() < 1 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.settled(now, "ушли — счёт из кэша в памяти", ALL, ALL))
                {
                    // Сменить подпись счёта: VerifyCache сохранит запись и при возврате перечитает её с диска
                    LayerRange range = DataManager.getRenderLayerRange();
                    range.setLayerMode(LayerMode.SINGLE_LAYER);
                    range.setAxis(Direction.Axis.Y);
                    range.setLayerSingle(FAR.getY() + 1);
                    this.next(now);
                }
            }
            case 3 ->
            {
                // Слой баннеров: камень под каждым — опора вне слоя (1.0.10), чанк по новой подписи ещё не проверялся
                if (this.settled(now, "другой слой (подпись кэша сменилась)", expect(BANNER, 2, "stone", 2),
                        expect(BANNER, 2, "stone", 2)))
                {
                    DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                    this.next(now);
                }
            }
            case 4 ->
            {
                LayerCountManager hud = LayerCountManager.getInstance();

                if (hud.getCachedChunks() < 1 && now - this.stageStart < STAGE_LIMIT_MS)
                {
                    return false;
                }

                if (this.settled(now, "вернули слой — счёт из кэша, перечитанного с диска", ALL, ALL))
                {
                    CovOut.log("[listcache] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
            default -> { return true; }
        }

        return false;
    }

    private void next(long now)
    {
        ++this.stage;
        this.stageStart = now;
        this.lastSignature = "";
        this.nextAt = now + 1000L;
        LayerCountManager.getInstance().markDirty();
    }

    private void place(BlockPos origin)
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        this.placement = SchematicPlacement.createFor(this.schematic, origin, "dev-listcache", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
    }

    private boolean settled(long now, String what, Map<String, Integer> expected, Map<String, Integer> expectedMissing)
    {
        LayerCountManager hud = LayerCountManager.getInstance();
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Integer> missing = new TreeMap<>();
        long rowsMissing = 0L;

        for (MaterialListEntry entry : hud.getEntries())
        {
            String key = key(entry.getStack());
            totals.merge(key, entry.getCountTotal(), Integer::sum);
            missing.merge(key, entry.getCountMissing(), Integer::sum);
            rowsMissing += entry.getCountMissing();
        }

        String signature = hud.hasData() + " " + totals + " " + missing + " " + hud.getBlocksMissing();

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
        this.check(what + " — сумма строк = итог футера", rowsMissing == hud.getBlocksMissing(),
                "строки " + rowsMissing + ", футер " + hud.getBlocksMissing());
        CovOut.log("[listcache] " + what + ": непроверенных чанков " + hud.getUnverifiedChunks()
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
        CovOut.log("[listcache] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[listcache] команда не прошла (" + command + "): " + e);
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
