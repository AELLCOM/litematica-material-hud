package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.lang.reflect.Method;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code s}: «Скрывать блоки без опоры» и «Опора с соседнего слоя» в Easy Place Fix+ (его копия, без
 * Material HUD). Стенд — этот же клиент с jar EPF+ в {@code run/mods} и {@code -Deasyplacefixplus.ghostSupportWithHud=true}
 * (иначе при Material HUD копия EPF+ молчит); опции Material HUD выключены, опции EPF+ включаются рефлексией.
 * Эталон тот же, что в {@code u}: табличка, ягоды в два звена, мох, лиана под потолком.
 */
@Environment(EnvType.CLIENT)
public final class EpfSupportTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(640, 100, 0);
    private static final BlockPos TARGET = new BlockPos(640, 100, 14);
    private static final int[][] CEILING = {{1, 3, 1}, {3, 3, 1}, {5, 3, 1}, {7, 3, 1}};
    private static final int[][] HANGING = {{1, 2, 1}, {3, 2, 1}, {3, 1, 1}, {5, 2, 1}, {7, 2, 1}};
    private static final String VIEW = "tp @a " + (TARGET.getX() + 6.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 8.5D)
            + " 180 -10";
    private static final long PLACE_MS = 8000L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private long placeDeadline;
    private long lastTick = Long.MIN_VALUE;
    private Class<?> support;
    private ConfigBoolean hide;
    private ConfigBoolean fromLayers;

    @Override
    public String name()
    {
        return "S (опора в Easy Place Fix+)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        try
        {
            this.support = Class.forName("com.aell.easyplacefixplus.core.GhostSupport");
            Class<?> configs = Class.forName("com.aell.easyplacefixplus.config.FixConfigs");
            this.hide = (ConfigBoolean) configs.getField("GHOST_HIDE_UNSUPPORTED").get(null);
            this.fromLayers = (ConfigBoolean) configs.getField("GHOST_SUPPORT_FROM_LAYERS").get(null);
            this.check("копия EPF+ включена на стенде", this.support.getField("HERE").getBoolean(null), "");
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[epfsupport] нет Easy Place Fix+ в run/mods: " + e);
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        this.hide.setBooleanValue(false);
        this.fromLayers.setBooleanValue(false);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 630 -10 660 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 14, -1, 10, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 14, 6, 10, "minecraft:air"));
            }

            for (int[] c : CEILING)
            {
                command(server, set(REF, c, "minecraft:stone"));
            }

            command(server, set(REF, HANGING[0], "minecraft:oak_hanging_sign[rotation=0,attached=false]"));
            command(server, set(REF, HANGING[1], "minecraft:cave_vines_plant[berries=true]"));
            command(server, set(REF, HANGING[2], "minecraft:cave_vines[berries=true,age=0]"));
            command(server, set(REF, HANGING[3], "minecraft:pale_hanging_moss[tip=true]"));
            command(server, set(REF, HANGING[4], "minecraft:vine[up=true]"));
            command(server, "tp @a " + (REF.getX() + 6.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
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
        long wait = 4000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-epfsupport");
                area.addSubRegionBox(new Box(REF, REF.offset(8, 3, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-epfsupport", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> command(server, VIEW));
                wait = 5000L;
            }
            case 1 ->
            {
                int hidden = this.hiddenCount(mc);
                this.check("тумблер EPF+ выключен — ничего не скрыто", hidden == 0, "скрыто " + hidden);
                this.stats();
                this.hide.setBooleanValue(true);
            }
            case 2 ->
            {
                int[] stats = this.stats();
                int hidden = this.hiddenCount(mc);
                this.check("тумблер EPF+ включён — ровно висящие клетки без опоры", hidden == HANGING.length,
                        "скрыто " + hidden);
                this.check("сборка чанка EPF+ убрала висящие голограммы", stats[0] >= HANGING.length, "убрано " + stats[0]);
                shot(mc, "EPF+ без опоры скрыто");
                this.hide.setBooleanValue(false);
                LayerRange range = DataManager.getRenderLayerRange();
                range.setLayerMode(LayerMode.SINGLE_LAYER);
                range.setAxis(Direction.Axis.Y);
                range.setLayerSingle(TARGET.getY() + 2);
                this.stats();
                this.fromLayers.setBooleanValue(true);
            }
            case 3 ->
            {
                LayerRange range = DataManager.getRenderLayerRange();
                int count = (int) this.call("supportCount");
                this.check("EPF+: опор вне слоя — ровно четыре клетки потолка", count == CEILING.length, "опор " + count);
                boolean all = true;

                for (int[] c : CEILING)
                {
                    all &= range.isPositionWithinRange(at(TARGET, c));
                }

                this.check("EPF+: клетки потолка — «внутри слоя» для прицела", all, "");
                int[] stats = this.stats();
                this.check("EPF+: опоры дорисованы сборкой чанка", stats[1] >= CEILING.length, "дорисовано " + stats[1]);
                shot(mc, "EPF+ опора с соседнего слоя");
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                    command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 4.5D) + " 180 -30");
                });
                wait = 2000L;
                this.placeDeadline = now + wait + PLACE_MS;
            }
            case 4 ->
            {
                BlockPos cell = at(TARGET, CEILING[0]);
                boolean placed = mc.level.getBlockState(cell).is(Blocks.STONE);

                if (! placed && now < this.placeDeadline)
                {
                    long tick = mc.level.getGameTime();

                    if (tick != this.lastTick)
                    {
                        this.lastTick = tick;
                        Vec3 eyes = mc.player.getEyePosition(1.0F);
                        Vec3 aim = Vec3.atCenterOf(cell).add(0.0D, 0.0D, 0.5D);
                        double dx = aim.x - eyes.x;
                        double dy = aim.y - eyes.y;
                        double dz = aim.z - eyes.z;
                        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                        WorldUtils.handleEasyPlace(mc);
                    }

                    return false;
                }

                this.check("EPF+: Easy Place поставил опору вне слоя", placed, "в мире " + mc.level.getBlockState(cell));
                wait = 2500L;
            }
            case 5 ->
            {
                int count = (int) this.call("supportCount");
                this.check("EPF+: поставленная опора ушла из списка", count == CEILING.length - 1, "опор " + count);
                this.fromLayers.setBooleanValue(false);
                DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[epfsupport] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private int hiddenCount(Minecraft mc)
    {
        var world = SchematicWorldHandler.getSchematicWorld();
        int count = 0;

        try
        {
            Method unsupported = this.support.getMethod("unsupported", BlockState.class, BlockState.class, BlockPos.class,
                    BlockGetter.class, BlockGetter.class);
            Method hidden = this.support.getMethod("hidden", BlockPos.class);

            for (int[] c : HANGING)
            {
                BlockPos pos = at(TARGET, c);
                boolean any = (boolean) unsupported.invoke(null, world.getBlockState(pos), mc.level.getBlockState(pos), pos,
                        mc.level, world);
                count += any && (boolean) hidden.invoke(null, pos) ? 1 : 0;
            }
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[epfsupport] вызов не прошёл: " + e);
            return -1;
        }

        return count;
    }

    private int[] stats()
    {
        Object out = this.call("takeStats");
        return out instanceof int[] a ? a : new int[]{-1, -1};
    }

    private Object call(String method)
    {
        try
        {
            return this.support.getMethod(method).invoke(null);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[epfsupport] " + method + ": " + e);
            return -1;
        }
    }

    private static BlockPos at(BlockPos origin, int[] c)
    {
        return origin.offset(c[0], c[1], c[2]);
    }

    private static String set(BlockPos origin, int[] c, String block)
    {
        BlockPos pos = at(origin, c);
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[epfsupport] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[epfsupport] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[epfsupport] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (this.hide != null)
        {
            this.hide.setBooleanValue(false);
            this.fromLayers.setBooleanValue(false);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }
}
