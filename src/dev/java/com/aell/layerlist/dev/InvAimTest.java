package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code v}: «только блоки из инвентаря» и прицел (жалоба 08.10.2026: «не удаётся поставить
 * рендерящийся блок сквозь невидимый блок на слое»). Схема: столбик досок (их нет в инвентаре — невидимы),
 * вплотную за ним камень (есть — виден). Луч из глаз в северную грань камня проходит сквозь доски.
 * <ul>
 *   <li>режим выкл — луч Litematica упирается в доски (так было и с режимом — это и есть баг);</li>
 *   <li>режим вкл — внутренние грани Ghost Alpha включились сами; луч проходит доски, попадает в камень;
 *       Easy Place в выживании ставит камень, доски не трогает; снимок — у непрозрачной голограммы камня
 *       видна грань к скрытым доскам;</li>
 *   <li>режим выкл — внутренние грани вернулись как были.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class InvAimTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(560, 100, 0);
    private static final BlockPos TARGET = new BlockPos(560, 100, 14);
    private static final BlockPos A = new BlockPos(2, 0, 3);
    private static final BlockPos A2 = new BlockPos(2, 1, 3);
    private static final BlockPos B = new BlockPos(2, 0, 4);
    private static final long PLACE_MS = 8000L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private long placeDeadline;
    private long lastTick = Long.MIN_VALUE;
    private boolean translucentBefore;

    @Override
    public String name()
    {
        return "V (прицел сквозь скрытые голограммы)";
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
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        this.translucentBefore = Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue();
        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(false);
        Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.setBooleanValue(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 550 -10 575 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 8, -1, 8, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 5, 8, "minecraft:air"));
            }

            command(server, set(REF, A, "minecraft:oak_planks"));
            command(server, set(REF, A2, "minecraft:oak_planks"));
            command(server, set(REF, B, "minecraft:stone"));
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
                area.setName("dev-invaim");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 2, 5), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-invaim", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                    command(server, "tp @a " + (TARGET.getX() + 2.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 0.5D) + " 0 10");
                });
                wait = 4000L;
            }
            case 1 ->
            {
                this.aim(mc);
                BlockPos hit = this.trace(mc);
                this.check("режим выкл — луч упирается в доски (как было)", TARGET.offset(A).equals(hit) || TARGET.offset(A2).equals(hit),
                        "попадание " + (hit == null ? "нет" : hit.toShortString()));
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
            }
            case 2 ->
            {
                this.check("режим вкл — внутренние грани Ghost Alpha включились",
                        Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue(), "");
                this.aim(mc);
                BlockPos hit = this.trace(mc);
                this.check("режим вкл — луч проходит невидимые доски и попадает в камень", TARGET.offset(B).equals(hit),
                        "попадание " + (hit == null ? "нет" : hit.toShortString()));
                shot(mc, "камень за скрытыми досками (непрозрачные голограммы)");
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                wait = 1500L;
                this.placeDeadline = now + wait + PLACE_MS;
            }
            case 3 ->
            {
                BlockPos cell = TARGET.offset(B);
                boolean placed = mc.level.getBlockState(cell).is(Blocks.STONE);

                if (! placed && now < this.placeDeadline)
                {
                    long tick = mc.level.getGameTime();

                    if (tick != this.lastTick)
                    {
                        this.lastTick = tick;
                        this.aim(mc);
                        WorldUtils.handleEasyPlace(mc);
                    }

                    return false;
                }

                this.check("Easy Place ставит видимый камень сквозь невидимые доски", placed,
                        "в мире " + mc.level.getBlockState(cell) + ", доски " + mc.level.getBlockState(TARGET.offset(A)));
                this.check("невидимые доски не тронуты", mc.level.getBlockState(TARGET.offset(A)).isAir()
                        && mc.level.getBlockState(TARGET.offset(A2)).isAir(), "");
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                wait = 1000L;
            }
            case 4 ->
            {
                this.check("режим выкл — внутренние грани вернулись как были",
                        ! Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue(), "");
                // Включённые до режима грани режим при выключении не трогает
                Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.setBooleanValue(true);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                this.check("грани, включённые игроком, режим не выключает",
                        Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.getBooleanValue(), "");
                Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES.setBooleanValue(false);
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[invaim] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Взгляд в середину северной грани камня — сквозь клетку досок. */
    private void aim(Minecraft mc)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atLowerCornerOf(TARGET.offset(B)).add(0.5D, 0.5D, 0.0D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private BlockPos trace(Minecraft mc)
    {
        RayTraceUtils.RayTraceWrapper wrapper = RayTraceUtils.getGenericTrace(mc.level, mc.player, 6.0D, true, true, false);
        return wrapper == null || wrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK
                ? null : wrapper.getBlockHitResult().getBlockPos();
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
        CovOut.log("[invaim] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[invaim] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[invaim] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.setBooleanValue(this.translucentBefore);
    }
}
