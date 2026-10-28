package com.aell.layerlist.dev;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

/**
 * Режим {@code endghost}: голограмма портала и шлюза Энда с прозрачностью Litematica (краш 20.10.2026: «Missing elements
 * in vertex» в {@code GhostSubmits$Faded} — портал пишет в вершину одни координаты, а полупрозрачный тип сущностей ждёт
 * текстуру, свет и нормаль).
 *
 * <p>Ставит в мире портал 3×3 и шлюз, снимает их в схему, кладёт её голограммой рядом на пустое место, включает
 * прозрачность голограмм и смотрит на неё несколько секунд — игра не должна упасть. Снимок: голограмма.
 */
@Environment(EnvType.CLIENT)
public final class EndPortalGhostTest implements CovJob
{
    private int stage;
    private long nextAt;
    private BlockPos ref = BlockPos.ZERO;
    private BlockPos target = BlockPos.ZERO;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "ENDGHOST (голограмма портала и шлюза Энда, прозрачная)";
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
        BlockPos at = mc.player.blockPosition();
        this.ref = at.offset(6, 0, -6);
        this.target = at.offset(6, 0, 4);
        BlockPos r = this.ref;
        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:end_portal", r.getX(), r.getY(), r.getZ(),
                    r.getX() + 2, r.getY(), r.getZ() + 2));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_gateway", r.getX() + 4, r.getY() + 1, r.getZ() + 1));
        });

        this.nextAt = Util.getMillis() + 2500L;
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

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-endghost");
                area.addSubRegionBox(new Box(this.ref, this.ref.offset(4, 2, 2), "main"), false);
                area.setExplicitOrigin(this.ref);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, this.target, "dev-endghost", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(true);
                Configs.Visuals.GHOST_BLOCK_ALPHA.setDoubleValue(0.5D);
                BlockPos t = this.target;
                server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f 180 30", t.getX() + 2.5D,
                        t.getY() + 2.0D, t.getZ() + 5.5D)));
                CovOut.log("[endghost] схема с порталом и шлюзом размещена в " + t.toShortString() + ", прозрачность 0.5");
                this.nextAt = now + 6000L;
                ++this.stage;
            }
            case 1 ->
            {
                CovOut.log("[endghost] снимок: голограмма портала и шлюза, прозрачная");
                Screenshot.grab(mc, false);
                // Для сравнения — без прозрачности: так Litematica рисует их сама, без обёртки голограмм
                Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);
                this.nextAt = now + 3000L;
                ++this.stage;
            }
            case 2 ->
            {
                CovOut.log("[endghost] снимок: без прозрачности (сравнение)");
                Screenshot.grab(mc, false);
                this.nextAt = now + 1000L;
                ++this.stage;
            }
            default ->
            {
                CovOut.log("[endghost] OK   игра не упала: голограмма портала и шлюза на экране 8 с");
                CovOut.log("[endghost] итог: OK");
                return true;
            }
        }

        return false;
    }

    private static void command(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    @Override
    public void close()
    {
        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
