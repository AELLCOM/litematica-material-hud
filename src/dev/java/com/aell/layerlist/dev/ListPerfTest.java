package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.LayerSupports;
import com.aell.layerlist.core.SchematicBlocks;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Режим {@code listperf} (14.10.2026, после ревью 1.0.10): замер — сколько стоит полный пересчёт списка HUD по слою, где
 * у каждой клетки есть опора вне слоя (ковёр на полу), против слоя без опор (камень) и режима «весь объём».
 * Бюджет — максимум, пересчёт целиком за один вызов; время — {@code System.nanoTime} вокруг прямых вызовов
 * {@code onClientTick}. Отдельно — чистая цена {@code LayerSupports.collect} на клетку.
 */
@Environment(EnvType.CLIENT)
public final class ListPerfTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(800, 100, 0);
    private static final int SIZE = 192;
    private static final int RUNS = 7;

    private int stage;
    private long nextAt;
    private SchematicPlacement placement;
    private LitematicaSchematic schematic;

    @Override
    public String name()
    {
        return "listperf (цена опор в пересчёте списка)";
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
        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(200);
        LayerListConfigs.BLOCKS_PER_TICK.setIntegerValue(4_000_000);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", REF.getX(), REF.getZ(),
                    REF.getX() + SIZE, REF.getZ() + SIZE));

            for (int x = 0; x < SIZE; x += 64)
            {
                for (int z = 0; z < SIZE; z += 64)
                {
                    command(server, fill(x, 0, z, x + 63, 0, z + 63, "minecraft:stone"));
                    command(server, fill(x, 1, z, x + 63, 1, z + 63, "minecraft:white_carpet"));
                    command(server, fill(x, 2, z, x + 63, 2, z + 63, "minecraft:stone"));
                }
            }

            command(server, "tp @a " + (REF.getX() + SIZE / 2) + " " + (REF.getY() + 6) + " " + (REF.getZ() + SIZE / 2) + " 0 90");
        });

        this.nextAt = Util.getMillis() + 8000L;
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
                area.setName("dev-listperf");
                area.addSubRegionBox(new Box(REF, REF.offset(SIZE - 1, 2, SIZE - 1), "main"), false);
                area.setExplicitOrigin(REF);
                this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                // Размещение поверх оригинала: мир совпадает со схемой, чанки загружены — как у игрока на стройке
                this.placement = SchematicPlacement.createFor(this.schematic, REF, "dev-listperf", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                ++this.stage;
                this.nextAt = now + 8000L;
            }
            case 1 ->
            {
                this.measure(mc, "весь объём (опор нет)", LayerMode.ALL, 0);
                this.measure(mc, "слой ковров (у каждой клетки опора снизу)", LayerMode.SINGLE_LAYER, 1);
                this.measure(mc, "слой камня (опора не нужна)", LayerMode.SINGLE_LAYER, 2);
                this.measure(mc, "слой пола (камень, снизу пусто)", LayerMode.SINGLE_LAYER, 0);
                this.collectOnly(mc);
                return true;
            }
            default -> { return true; }
        }

        return false;
    }

    private void measure(Minecraft mc, String what, LayerMode mode, int dy)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        range.setLayerMode(mode);
        range.setAxis(Direction.Axis.Y);

        if (mode == LayerMode.SINGLE_LAYER)
        {
            range.setLayerSingle(REF.getY() + dy);
        }

        LayerCountManager hud = LayerCountManager.getInstance();
        long[] times = new long[RUNS];

        // Первый пересчёт после смены слоя — прогрев (подпись, кэш проверки), в замер не идёт
        this.recount(mc, hud);

        for (int i = 0; i < RUNS; ++i)
        {
            times[i] = this.recount(mc, hud);
        }

        Arrays.sort(times);
        CovOut.log(String.format(Locale.ROOT, "[listperf] %s: пересчёт медиана %.1f мс, мин %.1f, макс %.1f; итого блоков %d",
                what, times[RUNS / 2] / 1e6, times[0] / 1e6, times[RUNS - 1] / 1e6, hud.getBlocksTotal()));
    }

    /** Полный пересчёт: пометить, затем звать тик, пока счёт не опубликован. Время — только вызовы тика. */
    private long recount(Minecraft mc, LayerCountManager hud)
    {
        hud.markDirty();
        long start = System.nanoTime();
        int calls = 0;

        do
        {
            hud.onClientTick(mc);
            ++calls;
        }
        while ((flag(hud, "counting") || flag(hud, "dirty")) && calls < 10_000);

        return System.nanoTime() - start;
    }

    private void collectOnly(Minecraft mc)
    {
        SchematicBlocks blocks = new SchematicBlocks(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements(), mc.level);
        Direction[] both = {Direction.UP, Direction.DOWN};
        long[] times = new long[RUNS];
        long[] found = new long[1];

        for (int run = 0; run < RUNS; ++run)
        {
            found[0] = 0L;
            long start = System.nanoTime();

            for (int x = 0; x < SIZE; ++x)
            {
                for (int z = 0; z < SIZE; ++z)
                {
                    BlockPos pos = REF.offset(x, 1, z);
                    BlockState state = blocks.getBlockState(pos);
                    LayerSupports.collect(state, pos, both, blocks, cell -> ++found[0]);
                }
            }

            times[run] = System.nanoTime() - start;
        }

        Arrays.sort(times);
        CovOut.log(String.format(Locale.ROOT, "[listperf] LayerSupports.collect по слою ковров (%d клеток): медиана %.1f мс "
                + "(%.2f мкс на клетку), опор найдено %d", SIZE * SIZE, times[RUNS / 2] / 1e6,
                times[RUNS / 2] / 1e3 / (SIZE * SIZE), found[0]));
    }

    private static boolean flag(LayerCountManager hud, String name)
    {
        try
        {
            Field field = LayerCountManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(hud);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", REF.getX() + x1, REF.getY() + y1, REF.getZ() + z1,
                REF.getX() + x2, REF.getY() + y2, REF.getZ() + z2, block);
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[listperf] команда не прошла (" + command + "): " + e);
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
        LayerListConfigs.BLOCKS_PER_TICK.setIntegerValue(250_000);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }
}
