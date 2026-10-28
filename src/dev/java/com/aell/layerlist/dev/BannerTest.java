package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
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
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code x}: баннеры с узором из рюкзака (жалоба 08.10.2026: «флаги ставятся, но не достаются в нужный
 * слот»). Схема — два стоячих баннера с узором. Нужный баннер лежит в рюкзаке, а не в хотбаре: в первом
 * случае — точно такой же, как в схеме; во втором — с тем же узором, но с именем (так бывает с баннерами с
 * сервера). Easy Place в выживании, протокол выключен — встал ли баннер с узором, сам, без помощи игрока.
 */
@Environment(EnvType.CLIENT)
public final class BannerTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(460, 100, 0);
    private static final BlockPos TARGET = new BlockPos(460, 100, 12);
    private static final String PATTERN = "[{pattern:\"minecraft:stripe_top\",color:\"red\"},{pattern:\"minecraft:cross\",color:\"blue\"}]";
    private static final long PER_CELL_MS = 8000L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private long cellDeadline;
    private long lastTick = Long.MIN_VALUE;

    @Override
    public String name()
    {
        return "X (баннеры из рюкзака)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        LayerListConfigs.EP_DEBUG.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 450 -10 475 25");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", o.getX() - 2, o.getY() - 1,
                        o.getZ() - 2, o.getX() + 6, o.getY() - 1, o.getZ() + 6));
                command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", o.getX() - 2, o.getY(),
                        o.getZ() - 2, o.getX() + 6, o.getY() + 3, o.getZ() + 6));
            }

            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:white_banner[rotation=8]{patterns:%s}",
                    REF.getX() + 1, REF.getY(), REF.getZ() + 1, PATTERN));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:white_banner[rotation=8]{patterns:%s}",
                    REF.getX() + 3, REF.getY(), REF.getZ() + 1, PATTERN));
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 4.5D));
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

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-banners");
                BlockPos min = REF.offset(0, -1, 0);
                area.addSubRegionBox(new Box(min, REF.offset(4, 1, 2), "main"), false);
                area.setExplicitOrigin(min);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET.offset(0, -1, 0), "dev-banners", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                this.nextAt = now + 4000L;
                ++this.stage;
            }
            case 1 ->
            {
                // Хотбар занят землёй, нужный баннер — в рюкзаке (inventory.11), как у игрока со склада
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");

                    for (int slot = 0; slot < 9; ++slot)
                    {
                        command(server, "item replace entity @a hotbar." + slot + " with minecraft:dirt 16");
                    }

                    command(server, "item replace entity @a inventory.11 with minecraft:white_banner[banner_patterns=" + PATTERN + "] 1");
                    command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 4.5D) + " 180 30");
                });
                this.nextAt = now + 2000L;
                this.cellDeadline = now + 2000L + PER_CELL_MS;
                ++this.stage;
            }
            case 2 -> this.placeCell(mc, now, TARGET.offset(1, 0, 1), "баннер точно как в схеме, в рюкзаке");
            case 3 ->
            {
                server.execute(() -> {
                    command(server, "clear @a minecraft:white_banner");
                    command(server, "item replace entity @a inventory.14 with minecraft:white_banner[banner_patterns=" + PATTERN
                            + ",custom_name='\"Флаг клана\"'] 1");
                    command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 4.5D) + " 180 30");
                });
                this.nextAt = now + 2000L;
                this.cellDeadline = now + 2000L + PER_CELL_MS;
                ++this.stage;
            }
            case 4 -> this.placeCell(mc, now, TARGET.offset(3, 0, 1), "баннер с тем же узором и с именем, в рюкзаке");
            default ->
            {
                CovOut.log("[banner] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        return false;
    }

    private void placeCell(Minecraft mc, long now, BlockPos cell, String what)
    {
        BlockState have = mc.level.getBlockState(cell);
        boolean placed = mc.level.getBlockEntity(cell) instanceof BannerBlockEntity banner
                && ! banner.collectComponents().getOrDefault(DataComponents.BANNER_PATTERNS,
                        net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY).layers().isEmpty();

        if (placed || now > this.cellDeadline)
        {
            CovOut.log("[banner] " + (placed ? "OK   " : "FAIL ") + what + " — в мире " + have + ", в руке "
                    + mc.player.getMainHandItem() + " (слот " + mc.player.getInventory().getSelectedSlot() + ")");
            this.failed |= ! placed;
            ++this.stage;
            this.nextAt = now + 500L;
            return;
        }

        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atCenterOf(cell);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[banner] команда не прошла (" + command + "): " + e);
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
