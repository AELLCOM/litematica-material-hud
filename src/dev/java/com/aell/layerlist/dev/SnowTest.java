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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

/**
 * Режим {@code snow}: снежный покров на месте блока схемы (просьба 13.11.2026: «если лежит снег там, где должен
 * стоять блок, в HUD этот блок не отображается»). Схема: доски, каменные кирпичи, снежный покров (сама схема просит
 * снег), кирпичи. В мире: на досках — покров в 1 слой, на кирпичах — в 3 слоя, на покрове — такой же покров, на
 * кирпичах — булыжник. Ожидание при «Нехватка — только пустые» (по умолчанию вкл.): доски и каменные кирпичи — по 1
 * в нехватке, снежного покрова нет (построен), кирпичей нет (стоит не то — правило опции не меняется). Выключенная
 * опция — кирпичи тоже в нехватке.
 */
@Environment(EnvType.CLIENT)
public final class SnowTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(860, 100, 0);
    private static final BlockPos TARGET = new BlockPos(860, 100, 14);
    private static final BlockPos[] CELLS = {
            new BlockPos(1, 0, 1), new BlockPos(3, 0, 1), new BlockPos(5, 0, 1), new BlockPos(7, 0, 1) };
    private static final String[] WANT = {
            "minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:snow[layers=1]", "minecraft:bricks" };
    private static final String[] WORLD = {
            "minecraft:snow[layers=1]", "minecraft:snow[layers=3]", "minecraft:snow[layers=1]", "minecraft:cobblestone" };

    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean onlyEmptyWas;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "snow (снежный покров на месте блока схемы)";
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
        this.onlyEmptyWas = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 850 -10 880 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 4, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 4, 4, "minecraft:air"));
            }

            for (int i = 0; i < CELLS.length; ++i)
            {
                command(server, set(REF, CELLS[i], WANT[i]));
                command(server, set(TARGET, CELLS[i], WORLD[i]));
            }

            command(server, "tp @a " + (TARGET.getX() + 4.5D) + " " + (TARGET.getY() + 1) + " " + (TARGET.getZ() + 5.5D) + " 180 40");
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
                area.setName("dev-snow");
                area.addSubRegionBox(new Box(REF, REF.offset(8, 1, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-snow", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerCountManager.getInstance().markDirty();
                // В творческом без полёта игрок падает с площадки на землю мира — снег из кадра пропадает
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> command(server, "tp @a " + (TARGET.getX() + 4.5D) + " " + (TARGET.getY() + 1)
                        + " " + (TARGET.getZ() + 5.5D)));
                this.nextAt = now + 4000L;
            }
            case 1 ->
            {
                Map<String, Integer> rows = rows();
                CovOut.log("[snow] «только пустые» вкл., строки HUD: " + rows);
                this.check("доски под покровом в 1 слой — в нехватке 1", missing(rows, "planks") == 1, rows.toString());
                this.check("каменные кирпичи под покровом в 3 слоя — в нехватке 1", missing(rows, "stone brick") == 1, rows.toString());
                this.check("покров, который просит сама схема, построен — в нехватке 0", missing(rows, "snow") == 0, rows.toString());
                this.check("кирпичи на месте булыжника — не в нехватке (правило опции прежнее)",
                        missing(rows, "^bricks") == 0, rows.toString());
                look(mc);
                shot(mc, "snow-hud");
                LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
                LayerCountManager.getInstance().markDirty();
                this.nextAt = now + 3000L;
            }
            case 2 ->
            {
                Map<String, Integer> rows = rows();
                CovOut.log("[snow] «только пустые» выкл., строки HUD: " + rows);
                this.check("выкл.: доски — 1", missing(rows, "planks") == 1, rows.toString());
                this.check("выкл.: кирпичи на месте булыжника — 1", missing(rows, "^bricks") == 1, rows.toString());
                this.check("выкл.: покров — 0", missing(rows, "snow") == 0, rows.toString());
                this.nextAt = now + 500L;
            }
            default ->
            {
                CovOut.log("[snow] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        ++this.stage;
        return false;
    }

    /** Строки HUD: имя предмета (англ. ключ без «minecraft:») → нехватка. */
    private static Map<String, Integer> rows()
    {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<MaterialListEntry> entries = LayerCountManager.getInstance().getAllEntries();

        for (MaterialListEntry entry : entries)
        {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).getPath()
                    .replace('_', ' ');
            out.put(id + " / " + DataItems.label(entry.getStack()), entry.getCountMissing());
        }

        return out;
    }

    private static int missing(Map<String, Integer> rows, String what)
    {
        boolean anchored = what.startsWith("^");
        String key = anchored ? what.substring(1) : what;

        for (Map.Entry<String, Integer> e : rows.entrySet())
        {
            String id = e.getKey().substring(0, e.getKey().indexOf(" / "));

            if (anchored ? id.equals(key) : id.contains(key))
            {
                return e.getValue();
            }
        }

        return 0;
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

    /** Взгляд на середину ряда клеток — снег и HUD в одном кадре. */
    private static void look(Minecraft mc)
    {
        net.minecraft.world.phys.Vec3 eyes = mc.player.getEyePosition(1.0F);
        net.minecraft.world.phys.Vec3 aim = net.minecraft.world.phys.Vec3.atCenterOf(TARGET.offset(4, 0, 1));
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        CovOut.log("[snow] глаза " + eyes + ", в мире под прицелом " + mc.level.getBlockState(TARGET.offset(1, 0, 1)));
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[snow] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[snow] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[snow] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(this.onlyEmptyWas);
    }
}
