package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.HiddenGhosts;
import com.aell.layerlist.ghost.InventoryGhosts;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code finish}: доводка при «только блоки из инвентаря» и мигание схемы (запрос 09.10.2026).
 * Схема: потухший костёр, вырезанная тыква лицом на восток, камень. В мире — горящий костёр и целая тыква,
 * у игрока лопата, ножницы и камень (ни костра, ни вырезанной тыквы).
 * <ul>
 *   <li>костёр и тыква не прячутся, пока есть инструмент; без лопаты костёр прячется, как раньше;</li>
 *   <li>Easy Place тушит костёр лопатой и вырезает тыкву ножницами — лицом туда, куда в схеме;</li>
 *   <li>камень пропал из инвентаря на полсекунды (переложили, взяли на курсор) — схема не пересобирается;
 *       пропал насовсем — пересобираются только чанки с камнем, а не вся схема.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class FinishTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(600, 100, 0);
    private static final BlockPos TARGET = new BlockPos(600, 100, 14);
    private static final BlockPos A = new BlockPos(2, 0, 3);
    private static final BlockPos B = new BlockPos(4, 0, 3);
    private static final BlockPos C = new BlockPos(0, 0, 3);
    private static final long PLACE_MS = 8000L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private long placeDeadline;
    private long lastTick = Long.MIN_VALUE;

    @Override
    public String name()
    {
        return "FINISH (доводка при «только из инвентаря», мигание)";
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

        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 590 -10 615 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 8, -1, 8, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 5, 8, "minecraft:air"));
            }

            command(server, set(REF, A, "minecraft:campfire[lit=false]"));
            command(server, set(REF, B, "minecraft:carved_pumpkin[facing=east]"));
            command(server, set(REF, C, "minecraft:stone"));
            command(server, set(TARGET, A, "minecraft:campfire[lit=true]"));
            command(server, set(TARGET, B, "minecraft:pumpkin"));
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 8.5D));
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
        long wait = 3000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-finish");
                area.addSubRegionBox(new Box(REF, REF.offset(5, 1, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-finish", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                    command(server, "item replace entity @a hotbar.1 with minecraft:iron_shovel");
                    command(server, "item replace entity @a hotbar.2 with minecraft:shears");
                    command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 0.5D) + " 0 20");
                });
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                LayerListConfigs.EP_FIX_LIT.setBooleanValue(true);
                LayerListConfigs.EP_FINISH_TOOLS.setBooleanValue(true);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                wait = 4000L;
            }
            case 1 ->
            {
                this.check("горящий костёр не прячется — лопата есть", ! HiddenGhosts.hidden(TARGET.offset(A)), "");
                this.check("целая тыква не прячется — ножницы есть", ! HiddenGhosts.hidden(TARGET.offset(B)), "");
                this.check("камень виден — он в инвентаре", ! HiddenGhosts.hidden(TARGET.offset(C)), "");
                shot(mc, "костёр и тыква видны при «только из инвентаря»");
                server.execute(() -> command(server, "clear @a minecraft:iron_shovel"));
                wait = 1500L;
            }
            case 2 ->
            {
                this.check("без лопаты костёр прячется, как раньше", HiddenGhosts.hidden(TARGET.offset(A)), "");
                server.execute(() -> command(server, "item replace entity @a hotbar.1 with minecraft:iron_shovel"));
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                wait = 1500L;
                this.placeDeadline = now + wait + PLACE_MS;
            }
            case 3 ->
            {
                BlockPos cell = TARGET.offset(A);
                BlockState state = mc.level.getBlockState(cell);
                boolean done = state.is(Blocks.CAMPFIRE) && ! state.getValue(BlockStateProperties.LIT);

                if (! done && now < this.placeDeadline)
                {
                    this.easyPlace(mc, cell);
                    return false;
                }

                this.check("Easy Place потушил костёр лопатой при «только из инвентаря»", done, "в мире " + state);
                wait = 500L;
                this.placeDeadline = now + wait + PLACE_MS;
            }
            case 4 ->
            {
                BlockPos cell = TARGET.offset(B);
                BlockState state = mc.level.getBlockState(cell);
                boolean done = state.is(Blocks.CARVED_PUMPKIN);

                if (! done && now < this.placeDeadline)
                {
                    this.easyPlace(mc, cell);
                    return false;
                }

                this.check("Easy Place вырезал тыкву ножницами", done, "в мире " + state);
                this.check("лицо тыквы — как в схеме (восток)", done
                        && state.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "в мире " + state);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                shot(mc, "костёр потушен, тыква вырезана");
                InventoryGhosts.takeRefreshStats();
                // Камень на полсекунды пропал (переложили, взяли на курсор) — схема мигать не должна
                server.execute(() -> command(server, "clear @a minecraft:stone"));
                wait = 500L;
            }
            case 5 ->
            {
                server.execute(() -> command(server, "item replace entity @a hotbar.0 with minecraft:stone 16"));
                wait = 2500L;
            }
            case 6 ->
            {
                int[] stats = InventoryGhosts.takeRefreshStats();
                this.check("камень пропал на полсекунды — схема не пересобиралась", stats[0] == 0,
                        "пересборок " + stats[0] + ", чанков " + stats[1]);
                server.execute(() -> command(server, "clear @a minecraft:stone"));
                wait = 3500L;
            }
            case 7 ->
            {
                int[] stats = InventoryGhosts.takeRefreshStats();
                this.check("камень кончился — пересобраны только чанки с камнем, не вся схема",
                        stats[0] >= 1 && stats[1] > 0, "пересборок " + stats[0] + ", чанков " + stats[1]);
                this.check("камень кончился — его голограмма спрятана", HiddenGhosts.hidden(TARGET.offset(C)), "");
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[finish] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Взгляд в середину северной грани клетки и шаг Easy Place — раз в тик. */
    private void easyPlace(Minecraft mc, BlockPos cell)
    {
        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atLowerCornerOf(cell).add(0.5D, 0.3D, 0.0D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
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

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[finish] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[finish] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[finish] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
    }
}
