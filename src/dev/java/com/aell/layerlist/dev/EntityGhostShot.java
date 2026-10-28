package com.aell.layerlist.dev;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

/**
 * Режим {@code entghost}: голограммы сущностей схемы полупрозрачные, как блоки (жалоба 10.10.2026: «подушки, рамки
 * отображаются как блоки»). Кладёт «Тестовую схему», ничего не строит, встаёт перед рамками и подушками на уступе и
 * снимает экран: с прозрачными голограммами Litematica и без них.
 */
@Environment(EnvType.CLIENT)
public final class EntityGhostShot implements CovJob
{
    private static final BlockPos ORIGIN = new BlockPos(940, 100, 40);

    private int stage;
    private long nextAt;
    private SchematicPlacement placement;
    private BlockPos min;

    @Override
    public String name()
    {
        return "ENTGHOST (голограммы сущностей полупрозрачные)";
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
        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Тестовая схема.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("[entghost] схема не загрузилась: " + path);
            return false;
        }

        this.placement = SchematicPlacement.createFor(schematic, ORIGIN, "dev-entghost", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};

        for (Box box : this.placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
        {
            lo[0] = Math.min(lo[0], Math.min(box.getPos1().getX(), box.getPos2().getX()));
            lo[1] = Math.min(lo[1], Math.min(box.getPos1().getY(), box.getPos2().getY()));
            lo[2] = Math.min(lo[2], Math.min(box.getPos1().getZ(), box.getPos2().getZ()));
        }

        this.min = new BlockPos(lo[0], lo[1], lo[2]);
        BlockPos m = this.min;
        server.execute(() -> {
            command(server, "gamemode spectator @a");
            command(server, "gamerule advance_time false");
            command(server, "time set noon");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", m.getX() - 8, m.getZ() - 8, m.getX() + 16, m.getZ() + 40));
            // Перед уступом с рамками (x = 4, y = 9) и подушками на нём, смотрим на восток и чуть вверх
            command(server, String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f -90 -12", m.getX() + 0.5D, m.getY() + 8.0D, m.getZ() + 12.5D));
        });

        Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.setBooleanValue(true);
        this.nextAt = Util.getMillis() + 6000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null)
        {
            return false;
        }

        switch (this.stage)
        {
            case 0 ->
            {
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
                Configs.Visuals.GHOST_BLOCK_ALPHA.setDoubleValue(0.5D);
                this.nextAt = now + 2500L;
            }
            case 1 ->
            {
                CovOut.log("[entghost] снимок: голограммы прозрачные");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);
                this.nextAt = now + 2500L;
            }
            case 2 ->
            {
                CovOut.log("[entghost] снимок: прозрачность выключена");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
                // Второй вид: сундуки на полу (блок-сущности) и стойки для брони
                MinecraftServer server = mc.getSingleplayerServer();
                BlockPos m = this.min;
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f -60 35",
                        m.getX() - 1.5D, m.getY() + 4.5D, m.getZ() + 9.5D)));
                this.nextAt = now + 3000L;
            }
            case 3 ->
            {
                CovOut.log("[entghost] снимок: сундуки и стойки, прозрачно");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);
                this.nextAt = now + 2500L;
            }
            case 4 ->
            {
                CovOut.log("[entghost] снимок: сундуки и стойки, непрозрачно");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
                this.nextAt = now + 1500L;
            }
            default ->
            {
                CovOut.log("[entghost] итог: снимки сделаны");
                return true;
            }
        }

        ++this.stage;
        return false;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[entghost] команда не прошла (" + command + "): " + e);
        }
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
