package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code cushionstack} (26.2-вариант). В 26.3 он проверяет подушки с опорами; подушек в 26.2 нет, поэтому здесь
 * та часть, что относится ко всем сущностям схемы, на стойках для брони:
 * <ul>
 *   <li>Ctrl+B отмечает недостающие стойки;</li>
 *   <li>недостающая стойка подсвечена, как недостающий блок ({@code ghostEntityOverlay});</li>
 *   <li>«Только блоки из инвентаря»: без стоек с собой голограммы скрыты, рамок и подсветки нет; со стойкой — есть;</li>
 *   <li>издалека (дальше радиуса, в котором сервер присылает сущности) поставленные стойки не «пропущены».</li>
 * </ul>
 * Проверки порядка «опора, потом подушка» (Easy Place) не портируются: опор у сущностей 26.2 нет.
 */
@Environment(EnvType.CLIENT)
public final class CushionStackTest implements CovJob
{
    private static final String T = "[cushionstack] ";
    private static final BlockPos REF = new BlockPos(1100, 100, 200);
    private static final BlockPos TARGET = new BlockPos(1100, 100, 230);
    private static final int COUNT = 12;
    private static final int PER_ROW = 6;

    private int stage;
    private long nextAt;
    private int ok;
    private int failed;
    private SchematicPlacement first;
    private int nearMarks;
    private int nearRow;
    private double scalingWas = 1.0D;
    private int rangeWas = -1;

    @Override
    public String name()
    {
        return "cushionstack (26.2: стойки — Ctrl+B, подсветка, только из инвентаря, издалека)";
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
        BuildTest.applyProfile("user");
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        LayerListConfigs.VERIFY_ENABLED.setBooleanValue(true);
        Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.setBooleanValue(true);
        this.scalingWas = mc.options.entityDistanceScaling().get();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "gamerule advance_time false");
            command(server, "gamerule random_tick_speed 0");
            command(server, "time set noon");
            command(server, "weather clear");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", REF.getX() - 4, REF.getZ() - 4,
                    REF.getX() + 24, TARGET.getZ() + 14));

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, String.format(Locale.ROOT, "kill @e[type=!player,x=%d,y=%d,z=%d,dx=28,dy=12,dz=18]",
                        o.getX() - 4, o.getY() - 4, o.getZ() - 4));
                command(server, fill(o, -3, -2, -3, 22, -1, 12, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 22, 5, 12, "minecraft:air"));
            }

            for (int i = 0; i < COUNT; ++i)
            {
                BlockPos p = cell(REF, i);
                command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %d %.2f {Rotation:[%df,0f]}",
                        p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 45 * (i % 8)));
            }

            command(server, tp(REF, 5.5D, 4.0D, -2.5D) + facing(REF, 5.5D, 0.0D, 3.0D));
        });

        this.nextAt = Util.getMillis() + 6000L;
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
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        long wait = 1000L;

        switch (this.stage)
        {
            case 0 ->
            {
                int stands = mc.level.getEntitiesOfClass(ArmorStand.class, area(REF)).size();
                this.check("стойки стоят в мире (" + COUNT + ")", stands == COUNT, "у клиента " + stands);

                AreaSelection area = new AreaSelection();
                area.setName("dev-cushionstack");
                area.addSubRegionBox(new Box(REF.offset(-1, -1, -1), REF.offset(14, 2, 10), "main"), false);
                area.setExplicitOrigin(REF.offset(-1, -1, -1));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

                if (schematic == null)
                {
                    CovOut.log(T + "образец не снялся");
                    return true;
                }

                this.first = SchematicPlacement.createFor(schematic, TARGET.offset(-1, -1, -1), "dev-cushionstack", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.first, false);
                server.execute(() -> command(server, tp(TARGET, 5.5D, 2.5D, -2.5D) + facing(TARGET, 5.5D, 0.0D, 2.0D)));
                wait = 6000L;
            }
            case 1 ->
            {
                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                wait = 5000L;
            }
            case 2 ->
            {
                int marks = marks(TARGET);
                this.check("Ctrl+B: недостающие стойки отмечены (" + COUNT + ")", marks == COUNT, "рамок " + marks);
                shot(mc, "stack-verify");
                // «Только блоки из инвентаря», выживание, стоек с собой нет: ни голограмм, ни рамок, ни подсветки
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(true);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    this.hover(server);
                });
                wait = 4000L;
            }
            case 3 ->
            {
                fly(mc);
                int marks = marks(TARGET);
                int lit = this.litCount(TARGET);
                int ghosts = 0;
                int hidden = 0;

                for (Entity ghost : world.getEntities((Entity) null, area(TARGET), e -> e instanceof ArmorStand))
                {
                    ++ghosts;
                    hidden += com.aell.layerlist.core.SchematicEntities.hiddenByInventory(ghost) ? 1 : 0;
                }

                this.check("только из инвентаря, стоек нет: рамок Ctrl+B нет", marks == 0, "рамок " + marks);
                this.check("только из инвентаря, стоек нет: подсветки нет", lit == 0, "подсвечено " + lit);
                this.check("только из инвентаря, стоек нет: голограммы скрыты", ghosts > 0 && hidden == ghosts,
                        "скрыто " + hidden + " из " + ghosts);
                shot(mc, "stack-inventory-none");
                server.execute(() -> command(server, "give @a minecraft:armor_stand 16"));
                wait = 4000L;
            }
            case 4 ->
            {
                fly(mc);
                int marks = marks(TARGET);
                this.check("только из инвентаря, стойки с собой: рамки вернулись", marks == COUNT, "рамок " + marks);
                shot(mc, "stack-inventory-have");
                wait = 800L;
            }
            case 5 ->
            {
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "gamemode creative @a");
                });
                wait = 3000L;
            }
            case 6 ->
            {
                LayerVerifier.getInstance().toggle();
                wait = 1500L;
            }
            case 7 ->
            {
                // Без Ctrl+B: видно ли, что стойки надо ставить
                int lit = this.litCount(TARGET);
                this.check("подсветка «надо поставить» у стоек (" + COUNT + ")", lit == COUNT, "подсвечено " + lit);
                shot(mc, "stack-ghost");

                // Ставим стойки на площадку: вблизи они есть, дальше проверим, что издалека не «пропущены»
                server.execute(() -> {
                    for (int i = 0; i < COUNT; ++i)
                    {
                        BlockPos p = cell(TARGET, i);
                        command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %d %.2f {Rotation:[%df,0f]}",
                                p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 45 * (i % 8)));
                    }
                });
                wait = 2000L;
            }
            case 8 ->
            {
                this.check("вблизи: поставленные стойки не подсвечены", this.litCount(TARGET) == 0, "подсвечено " + this.litCount(TARGET));

                // Ломаем одну, чтобы было что считать «не хватает»
                server.execute(() -> command(server, String.format(Locale.ROOT, "kill @e[type=armor_stand,x=%d,y=%d,z=%d,distance=..1]",
                        cell(TARGET, 0).getX(), cell(TARGET, 0).getY(), cell(TARGET, 0).getZ())));
                wait = 2000L;
            }
            case 9 ->
            {
                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                LayerCountManager.getInstance().markDirty();
                wait = 5000L;
            }
            case 10 ->
            {
                this.nearMarks = marks(TARGET);
                this.nearRow = standsMissing();
                CovOut.log(T + "вблизи: рамок стоек на площадке " + this.nearMarks + ", в списке не хватает стоек " + this.nearRow);
                this.check("вблизи: недостаёт ровно одной стойки", this.nearMarks == 1 && this.nearRow == 1,
                        "рамок " + this.nearMarks + ", в списке " + this.nearRow);
                // Радиус сущностей вдвое меньше — и встаём за ним, но в загруженных чанках
                this.rangeWas = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();
                LayerListConfigs.VERIFY_DRAW_RANGE.setIntegerValue(LayerListConfigs.VERIFY_DRAW_RANGE.getMaxIntegerValue());
                mc.options.entityDistanceScaling().set(0.5D);
                double range = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.clientTrackingRange() * 16.0D * 0.5D;
                CovOut.log(T + "радиус стоек у сервера " + range + ", дальность чанков " + mc.options.getEffectiveRenderDistance());
                server.execute(() -> command(server, tp(TARGET, 6.5D - range - 24.0D, 6.0D, 4.5D)
                        + String.format(Locale.ROOT, " facing %d %d %d", TARGET.getX() + 6, TARGET.getY(), TARGET.getZ() + 4)));
                wait = 9000L;
            }
            case 11 ->
            {
                LayerCountManager.getInstance().markDirty();
                wait = 4000L;
            }
            case 12 ->
            {
                int seen = mc.level.getEntitiesOfClass(ArmorStand.class, area(TARGET)).size();
                boolean loaded = WorldUtils.isClientChunkLoaded(mc.level, TARGET.getX() >> 4, TARGET.getZ() >> 4);
                int farMarks = marks(TARGET);
                int farRow = standsMissing();
                CovOut.log(T + "издалека: игрок " + mc.player.position() + ", чанк площадки загружен " + loaded
                        + ", стоек площадки у клиента " + seen);
                this.check("издалека: условия опыта (чанк есть, стоек у клиента нет)", loaded && seen == 0, "");
                this.check("издалека: рамок стоек столько же, сколько вблизи", farMarks == this.nearMarks,
                        "вблизи " + this.nearMarks + ", издалека " + farMarks);
                this.check("издалека: в списке не хватает стоек столько же", farRow == this.nearRow,
                        "вблизи " + this.nearRow + ", издалека " + farRow);
                shot(mc, "stack-far");
                wait = 800L;
            }
            default ->
            {
                return true;
            }
        }

        ++this.stage;
        this.nextAt = now + wait;
        return false;
    }

    // ===== Помощники =====

    private static BlockPos cell(BlockPos origin, int i)
    {
        return origin.offset(2 * (i % PER_ROW), 0, 2 * (i / PER_ROW));
    }

    private static int marks(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) LayerVerifier.getInstance().getEntityMarks().stream()
                .filter(m -> area.contains(m.box().getCenter())).count();
    }

    private static int standsMissing()
    {
        int total = 0;

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            if (entry.getStack().is(net.minecraft.world.item.Items.ARMOR_STAND))
            {
                total += (int) entry.getCountMissing();
            }
        }

        return total;
    }

    private static AABB area(BlockPos o)
    {
        return new AABB(o.getX() - 2.0D, o.getY() - 1.0D, o.getZ() - 2.0D, o.getX() + 22.0D, o.getY() + 4.0D, o.getZ() + 12.0D);
    }

    private static String tp(BlockPos o, double x, double y, double z)
    {
        return String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f", o.getX() + x, o.getY() + y, o.getZ() + z);
    }

    private static String facing(BlockPos o, double x, double y, double z)
    {
        return String.format(Locale.ROOT, " facing %.2f %.2f %.2f", o.getX() + x, o.getY() + y, o.getZ() + z);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    /** В выживании игрок упал бы с точки обзора — держим полёт. */
    private void hover(MinecraftServer server)
    {
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers())
        {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();
        }
    }

    private int litCount(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) com.aell.layerlist.ghost.EntityOverlay.boxes().stream().filter(box -> area.contains(box.getCenter())).count();
    }

    private static void fly(Minecraft mc)
    {
        if (! mc.player.getAbilities().flying)
        {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
    }

    private static void look(Minecraft mc, Vec3 point)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(yaw);
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        mc.player.setYHeadRot(yaw);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(T + "снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(T + (ok ? "OK   " : "FAIL ") + what + (ok || detail == null || detail.isEmpty() ? "" : " — " + detail));
        this.ok += ok ? 1 : 0;
        this.failed += ok ? 0 : 1;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log(T + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        Minecraft.getInstance().options.entityDistanceScaling().set(this.scalingWas);

        if (this.rangeWas >= 0)
        {
            LayerListConfigs.VERIFY_DRAW_RANGE.setIntegerValue(this.rangeWas);
        }

        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        BuildTest.resetProfile();

        if (this.first != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.first);
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        CovOut.log(T + "итог: OK " + this.ok + ", FAIL " + this.failed);
    }
}
