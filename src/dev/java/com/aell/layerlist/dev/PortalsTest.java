package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.HiddenGhosts;
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
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code portals}: опция «Скрывать порталы» во вкладке Ghost Alpha (просьба 14.11.2026). Схема: кольцо рамок
 * портала Энда с порталом внутри, портал Незера в обсидиане, врата Энда, золотой блок. По умолчанию опция выключена —
 * голограммы всех порталов на месте; включили — рамок и порталов нет, обсидиан и золото остались, прицел проходит
 * клетки порталов насквозь; выключили — всё вернулось. Снимки на каждом шаге и строка настроек.
 */
@Environment(EnvType.CLIENT)
public final class PortalsTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(980, 100, 0);
    private static final BlockPos TARGET = new BlockPos(980, 100, 16);

    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean hideWas;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "portals (скрывать порталы в голограмме)";
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
        this.hideWas = LayerListConfigs.GHOST_HIDE_PORTALS.getBooleanValue();
        this.check("по умолчанию выключена", ! LayerListConfigs.GHOST_HIDE_PORTALS.getDefaultBooleanValue(), "");
        this.check("опция в списке настроек", LayerListConfigs.OPTIONS.contains(LayerListConfigs.GHOST_HIDE_PORTALS), "");
        LayerListConfigs.GHOST_HIDE_PORTALS.resetToDefault();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 970 -10 1000 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -3, 14, -1, 8, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 14, 6, 8, "minecraft:air"));
            }

            // Кольцо рамок портала Энда (часть с оком) и портал внутри
            command(server, fill(REF, 1, 0, 0, 3, 0, 0, "minecraft:end_portal_frame[facing=south,eye=true]"));
            command(server, fill(REF, 1, 0, 4, 3, 0, 4, "minecraft:end_portal_frame[facing=north]"));
            command(server, fill(REF, 0, 0, 1, 0, 0, 3, "minecraft:end_portal_frame[facing=east,eye=true]"));
            command(server, fill(REF, 4, 0, 1, 4, 0, 3, "minecraft:end_portal_frame[facing=west]"));
            command(server, fill(REF, 1, 0, 1, 3, 0, 3, "minecraft:end_portal"));
            // Портал Незера 2×3 в обсидиановой раме 4×5
            command(server, fill(REF, 6, 0, 2, 9, 4, 2, "minecraft:obsidian"));
            command(server, fill(REF, 7, 1, 2, 8, 3, 2, "minecraft:nether_portal[axis=x]"));
            // Врата Энда и золотой блок — обычный блок, который должен остаться
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_gateway", REF.getX() + 11, REF.getY() + 1, REF.getZ() + 2));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", REF.getX() + 11, REF.getY(), REF.getZ() + 2));
            tpViewer(server);
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    private static void tpViewer(MinecraftServer server)
    {
        command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", TARGET.getX() + 5.5D, TARGET.getY() + 4, TARGET.getZ() + 12.5D));
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

        if (mc.player == null || mc.level == null || now < this.nextAt)
        {
            return false;
        }

        long wait = 1000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-portals");
                area.addSubRegionBox(new Box(REF, REF.offset(11, 4, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-portals", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> tpViewer(server));
                wait = 4000L;
            }
            case 1 ->
            {
                look(mc);
                this.cells("выкл. (по умолчанию)", false);
                shot(mc, "portals-off");
                LayerListConfigs.GHOST_HIDE_PORTALS.setBooleanValue(true);
                wait = 3000L;
            }
            case 2 ->
            {
                look(mc);
                this.cells("вкл.", true);
                shot(mc, "portals-on");
                LayerListConfigs.GHOST_HIDE_PORTALS.setBooleanValue(false);
                wait = 3000L;
            }
            case 3 ->
            {
                look(mc);
                this.cells("снова выкл.", false);
                shot(mc, "portals-off-again");
                var screen = com.aell.layerlist.config.GuiConfigs.create(null, null);
                mc.gui.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                search(screen, "Порталы");
                wait = 2500L;
            }
            case 4 ->
            {
                shot(mc, "portals-settings");
                wait = 500L;
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log("[portals] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Прицел и фильтр голограмм: клетки порталов спрятаны только при включённой опции, обсидиан и золото — никогда. */
    private void cells(String what, boolean wantHidden)
    {
        BlockPos[] portals = {
                TARGET.offset(1, 0, 0), TARGET.offset(4, 0, 2),   // рамки портала Энда
                TARGET.offset(2, 0, 2),                           // портал Энда
                TARGET.offset(7, 1, 2), TARGET.offset(8, 3, 2),   // портал Незера
                TARGET.offset(11, 1, 2)};                         // врата Энда
        BlockPos[] plain = {TARGET.offset(6, 0, 2), TARGET.offset(9, 4, 2), TARGET.offset(11, 0, 2)};
        int hidden = 0;

        for (BlockPos pos : portals)
        {
            hidden += HiddenGhosts.hidden(pos) ? 1 : 0;
        }

        int plainHidden = 0;

        for (BlockPos pos : plain)
        {
            plainHidden += HiddenGhosts.hidden(pos) ? 1 : 0;
        }

        this.check(what + (wantHidden ? ": порталы спрятаны" : ": порталы видны"),
                wantHidden ? hidden == portals.length : hidden == 0, "спрятано " + hidden + " из " + portals.length);
        this.check(what + ": обсидиан и золото видны", plainHidden == 0, "спрятано " + plainHidden);
    }

    private static void look(Minecraft mc)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = new Vec3(TARGET.getX() + 5.5D, TARGET.getY() + 1.0D, TARGET.getZ() + 2.5D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** Строка поиска настроек — как в {@link SettingsShot}. */
    private static void search(Object screen, String query)
    {
        try
        {
            Class<?> type = com.aell.layerlist.shaded.ui.SettingsScreen.class;
            var field = type.getDeclaredField("search");
            field.setAccessible(true);
            field.set(screen, query);
            var layout = type.getDeclaredMethod("layout");
            layout.setAccessible(true);
            layout.invoke(screen);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[portals] поиск не задан: " + e);
        }
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[portals] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[portals] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[portals] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.GHOST_HIDE_PORTALS.setBooleanValue(this.hideWas);
    }
}
