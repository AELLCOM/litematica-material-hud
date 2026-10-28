package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.GhostSupport;
import com.aell.layerlist.ghost.HiddenGhosts;
import com.aell.layerlist.ghost.InventoryGhosts;
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
 * Режим {@code frames}: рамка опоры и голограммы по инвентарю в креативе (запросы 09.10.2026).
 * <ul>
 *   <li>«Скрывать блоки без опоры»: висячая табличка под ещё не поставленным потолком спрятана, а клетка
 *       потолка обведена рамкой; потолок поставили — рамки нет, табличка видна;</li>
 *   <li>«Только блоки из инвентаря» с пустым инвентарём: в выживании камень спрятан, в креативе режим молчит
 *       и видно всё, снова выживание — прячет.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class SupportFrameTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(760, 100, 0);
    private static final BlockPos TARGET = new BlockPos(760, 100, 14);
    private static final BlockPos CEILING = new BlockPos(1, 3, 1);
    private static final BlockPos SIGN = new BlockPos(1, 2, 1);
    private static final BlockPos STONE = new BlockPos(3, 0, 1);

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private int waits;

    @Override
    public String name()
    {
        return "FRAMES (рамка опоры, голограммы по инвентарю в креативе)";
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
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 750 -10 775 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 6, -1, 6, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 6, 5, 6, "minecraft:air"));
            }

            command(server, set(REF, CEILING, "minecraft:stone"));
            command(server, set(REF, SIGN, "minecraft:oak_hanging_sign[attached=false,rotation=0]"));
            command(server, set(REF, STONE, "minecraft:polished_andesite"));
            command(server, "tp @a " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
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
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-frames");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 3, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-frames", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                server.execute(() -> {
                    command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 5.5D) + " 180 20");
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                });
                wait = 3000L;
            }
            case 1 ->
            {
                var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                if ((world == null || world.getBlockState(TARGET.offset(CEILING)).isAir()) && ++this.waits < 40)
                {
                    this.nextAt = now + 250L;
                    return false;
                }

                LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(true);
                wait = 3000L;
            }
            case 2 ->
            {
                this.check("без опоры: табличка под непостроенным потолком спрятана", HiddenGhosts.hidden(TARGET.offset(SIGN)), "");
                this.check("рамка: клетка потолка обведена", frames(TARGET.offset(CEILING)), framesText());
                this.check("рамка: на самой табличке рамки нет", ! frames(TARGET.offset(SIGN)), framesText());
                CovOut.log("[frames] снимок: рамка опоры");
                Screenshot.grab(mc, false);
                server.execute(() -> command(server, set(TARGET, CEILING, "minecraft:stone")));
                wait = 3500L;
            }
            case 3 ->
            {
                this.check("потолок поставлен: рамки нет", ! frames(TARGET.offset(CEILING)), framesText());
                this.check("потолок поставлен: табличка видна", ! HiddenGhosts.hidden(TARGET.offset(SIGN)), "");
                LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                wait = 2500L;
            }
            case 4 ->
            {
                this.check("только из инвентаря, выживание: режим действует", InventoryGhosts.active(), "");
                this.check("только из инвентаря, выживание: андезита нет — спрятан", HiddenGhosts.hidden(TARGET.offset(STONE)), "");
                server.execute(() -> command(server, "gamemode creative @a"));
                wait = 2500L;
            }
            case 5 ->
            {
                this.check("креатив: режим молчит", ! InventoryGhosts.active(), "");
                this.check("креатив: андезит виден", ! HiddenGhosts.hidden(TARGET.offset(STONE)), "");
                server.execute(() -> command(server, "gamemode survival @a"));
                wait = 2500L;
            }
            case 6 ->
            {
                this.check("снова выживание: андезит снова спрятан", HiddenGhosts.hidden(TARGET.offset(STONE)), "");
                wait = 300L;
            }
            default ->
            {
                CovOut.log("[frames] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private static boolean frames(BlockPos pos)
    {
        for (long cell : GhostSupport.frameCells())
        {
            if (cell == pos.asLong())
            {
                return true;
            }
        }

        return false;
    }

    private static String framesText()
    {
        StringBuilder out = new StringBuilder("рамки:");

        for (long cell : GhostSupport.frameCells())
        {
            out.append(' ').append(BlockPos.of(cell).subtract(TARGET).toShortString());
        }

        return out.toString();
    }

    private static String set(BlockPos origin, BlockPos c, String block)
    {
        BlockPos pos = origin.offset(c);
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[frames] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[frames] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
