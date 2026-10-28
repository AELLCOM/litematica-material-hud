package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * Режим {@code listbobby} (14.10.2026, после ревью 1.0.10): постройка с узорными баннерами собрана верно, игрок
 * уходит далеко. Пишет, считается ли чанк загруженным (Bobby держит его своим кэш-чанком), есть ли у баннера в мире
 * клиента сущность с узором, и счёт строки баннера: «не хватает» и «не то» при нехватке «только пустые» выкл/вкл.
 * Ожидание: верно построенный баннер ни «не хватает», ни «не то» — где бы ни стоял игрок.
 */
@Environment(EnvType.CLIENT)
public final class ListBobbyTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(720, 100, 0);
    private static final BlockPos NEAR = new BlockPos(720, 100, 14);
    private final BlockPos FAR = new BlockPos(40_000 + 64 * (int) (System.currentTimeMillis() / 1000L % 4000L), 100, 56_000);
    private static final String PATTERN = "{patterns:[{color:\"red\",pattern:\"minecraft:cross\"}]}";
    private static final long STABLE_MS = 3000L;
    private static final long STAGE_LIMIT_MS = 30_000L;
    /** Дольше выгрузки кэш-чанков Bobby у игрока ({@code unload-delay-secs=60}). */
    private static final long BOBBY_UNLOAD_MS = 75_000L;

    private int stage;
    private long nextAt;
    private long stageStart;
    private boolean failed;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;
    private String lastSignature = "";
    private long stableSince;

    @Override
    public String name()
    {
        return "listbobby (верно построенные узорные баннеры, игрок ушёл)";
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
        LayerListConfigs.COUNT_ENTITIES.setBooleanValue(true);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 710 -10 740 30");

            for (BlockPos o : new BlockPos[]{REF, NEAR})
            {
                command(server, fill(o, -2, -1, -2, 6, 4, 4, "minecraft:air"));
            }

            for (String c : build(REF))
            {
                command(server, c);
            }

            command(server, "tp @a " + (NEAR.getX() + 1.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    private static String[] build(BlockPos o)
    {
        return new String[]{
                fill(o, 0, 0, 1, 2, 0, 1, "minecraft:stone"),
                set(o, 0, 1, "minecraft:white_banner[rotation=0]" + PATTERN),
                set(o, 2, 1, "minecraft:white_banner[rotation=8]" + PATTERN),
                // Сущность схемы: Bobby сущностей не хранит — в его кэш-чанке стойки «нет»
                String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {Rotation:[0f,0f]}",
                        o.getX() + 1.5D, o.getY() + 1, o.getZ() + 1.5D)};
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
                area.setName("dev-listbobby");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 1, 1), "main"), false);
                area.setExplicitOrigin(REF);
                this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(this.schematic, FAR, "dev-listbobby", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> command(server,
                        "tp @a " + (FAR.getX() + 1.5D) + " " + (FAR.getY() + 6) + " " + (FAR.getZ() + 8.5D) + " 180 30"));
                this.next(now, 6000L);
            }
            case 1 ->
            {
                // Чанки на месте уже загружены сервером — теперь стройка встанет (до телепорта setblock молча не ставит)
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    command(server, fill(FAR, -2, -1, -2, 6, 4, 4, "minecraft:air"));

                    for (String c : build(FAR))
                    {
                        command(server, c);
                    }
                });
                this.next(now, 3000L);
            }
            case 2 ->
            {
                this.world(mc, "рядом");

                if (this.settled(now, "рядом, построено верно", 0, 0))
                {
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> command(server,
                            "tp @a " + (NEAR.getX() + 1.5D) + " " + NEAR.getY() + " " + (NEAR.getZ() + 8.5D) + " 180 -10"));
                    this.next(now, 6000L);
                }
            }
            case 3 ->
            {
                this.world(mc, "ушли (сразу)");

                if (this.settled(now, "ушли (сразу), нехватка — и «не то»", 0, 0))
                {
                    LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
                    this.next(now, 1000L);
                }
            }
            case 4 ->
            {
                if (this.settled(now, "ушли (сразу), нехватка — только пустые (как у игрока)", 0, 0))
                {
                    LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
                    this.next(now, BOBBY_UNLOAD_MS);
                    CovOut.log("[listbobby] жду выгрузки кэш-чанков Bobby " + BOBBY_UNLOAD_MS / 1000 + " с");
                }
            }
            case 5 ->
            {
                this.world(mc, "ушли + " + BOBBY_UNLOAD_MS / 1000 + " с");

                if (this.settled(now, "ушли + " + BOBBY_UNLOAD_MS / 1000 + " с", 0, 0))
                {
                    CovOut.log("[listbobby] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
            }
            default -> { return true; }
        }

        return false;
    }

    /** Что видит клиент на месте постройки: чанк, сущность баннера и её узор. */
    private void world(Minecraft mc, String what)
    {
        if (this.lastSignature.isEmpty())
        {
            BlockPos banner = FAR.offset(0, 1, 1);
            boolean loaded = WorldUtils.isClientChunkLoaded(mc.level, banner.getX() >> 4, banner.getZ() >> 4);
            BlockEntity be = mc.level.getBlockEntity(banner);
            String pattern = be instanceof BannerBlockEntity b ? "слоёв узора " + b.getPatterns().layers().size()
                    : be == null ? "сущности нет" : be.getClass().getSimpleName();
            CovOut.log("[listbobby] " + what + ": чанк постройки загружен = " + loaded + ", блок " + mc.level.getBlockState(banner)
                    + ", " + pattern);
        }
    }

    private void next(long now, long delay)
    {
        ++this.stage;
        this.stageStart = now + delay;
        this.lastSignature = "";
        this.nextAt = now + delay;
        LayerCountManager.getInstance().markDirty();
    }

    private boolean settled(long now, String what, int wantMissing, int wantMismatch)
    {
        LayerCountManager hud = LayerCountManager.getInstance();
        int missing = 0;
        int mismatch = 0;
        int total = 0;
        int standTotal = 0;
        int standMissing = 0;

        for (MaterialListEntry entry : hud.getEntries())
        {
            if (isPatternedBanner(entry.getStack()))
            {
                total += entry.getCountTotal();
                missing += entry.getCountMissing();
                mismatch += entry.getCountMismatched();
            }
            else if (entry.getStack().is(net.minecraft.world.item.Items.ARMOR_STAND))
            {
                standTotal += entry.getCountTotal();
                standMissing += entry.getCountMissing();
            }
        }

        String signature = hud.hasData() + " " + total + " " + missing + " " + mismatch + " " + standTotal + " " + standMissing;

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

        boolean ok = total == 2 && missing == wantMissing && mismatch == wantMismatch && standTotal == 1 && standMissing == 0;
        CovOut.log(String.format(Locale.ROOT, "[listbobby] %s %s — баннер с узором: всего %d, не хватает %d, не то %d "
                        + "(ждали 2/%d/%d); стойка: всего %d, не хватает %d (ждали 1/0); непроверенных чанков %d, из кэша %d",
                ok ? "OK  " : "FAIL", what, total, missing, mismatch, wantMissing, wantMismatch, standTotal, standMissing,
                hud.getUnverifiedChunks(), hud.getCachedChunks()));
        this.failed |= ! ok;
        return true;
    }

    private static boolean isPatternedBanner(ItemStack stack)
    {
        BannerPatternLayers patterns = stack.get(DataComponents.BANNER_PATTERNS);
        return patterns != null && ! patterns.layers().isEmpty();
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

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[listbobby] команда не прошла (" + command + "): " + e);
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
