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
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code cushionstack} (1.0.30, версия 1.21.11). Подушек в 1.21.11 нет, поэтому проверяется то, что общее для
 * всех сущностей схемы — стоек для брони и рамок:
 * <ul>
 *   <li>Ctrl+B отмечает недостающие сущности, подсветка «надо поставить» (EntityOverlay) их выделяет;</li>
 *   <li>«Только блоки из инвентаря»: без предмета с собой нет голограмм, рамок и подсветки; с предметом — есть;</li>
 *   <li>издалека (дальше радиуса, в котором сервер присылает сущности) сущности не «пропущены»;</li>
 *   <li>Easy Place по голограмме стойки ставит её (кроме подушек: опора под подушкой, их тут нет).</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class CushionStackTest implements CovJob
{
    private static final String T = "[cushionstack] ";
    private static final BlockPos REF = new BlockPos(1100, 100, 200);
    private static final BlockPos TARGET = new BlockPos(1100, 100, 230);
    private static final int COUNT = 8;

    private int stage;
    private long nextAt;
    private int ok;
    private int failed;
    private SchematicPlacement placement;
    private int nearMarks;
    private int nearRow;
    private double scalingWas = 1.0D;
    private int rangeWas = -1;

    @Override
    public String name()
    {
        return "cushionstack (1.21.11: стойки и рамки схемы — Ctrl+B, подсветка, инвентарь, издалека)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new java.util.ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
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

                if (i % 2 == 0)
                {
                    command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %d %.2f {Rotation:[%df,0f]}",
                            p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 45 * i));
                }
                else
                {
                    command(server, String.format(Locale.ROOT,
                            "summon minecraft:item_frame %.2f %.2f %.2f {Facing:1b,block_pos:[I;%d,%d,%d],Item:{id:\"minecraft:diamond\",count:1}}",
                            p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, p.getX(), p.getY(), p.getZ()));
                }
            }

            command(server, tp(REF, 8.0D, 4.0D, -2.5D) + facing(REF, 8.0D, 0.0D, 3.0D));
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
                AreaSelection area = new AreaSelection();
                area.setName("dev-cushionstack");
                area.addSubRegionBox(new Box(REF.offset(-1, -1, -1), REF.offset(2 * COUNT + 1, 2, 3), "main"), false);
                area.setExplicitOrigin(REF.offset(-1, -1, -1));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

                if (schematic == null)
                {
                    CovOut.log(T + "образец не снялся");
                    return true;
                }

                this.placement = SchematicPlacement.createFor(schematic, TARGET.offset(-1, -1, -1), "dev-cushionstack", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                server.execute(() -> command(server, tp(TARGET, 8.0D, 2.5D, -2.5D) + facing(TARGET, 8.0D, 0.0D, 2.0D)));
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
                this.check("Ctrl+B: недостающие сущности отмечены (" + COUNT + ")", marks(TARGET) == COUNT, "рамок " + marks(TARGET));
                this.check("подсветка «надо поставить» у сущностей (" + COUNT + ")", this.litCount(TARGET) == COUNT,
                        "подсвечено " + this.litCount(TARGET));
                shot(mc, "stack-verify");
                // «Только блоки из инвентаря», выживание, предметов нет
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
                int ghosts = 0;
                int hidden = 0;

                if (world != null)
                {
                    for (Entity ghost : world.getEntities((Entity) null, area(TARGET), e -> true))
                    {
                        ++ghosts;
                        hidden += com.aell.layerlist.core.SchematicEntities.hiddenByInventory(ghost) ? 1 : 0;
                    }
                }

                this.check("только из инвентаря, предметов нет: рамок Ctrl+B нет", marks(TARGET) == 0, "рамок " + marks(TARGET));
                this.check("только из инвентаря, предметов нет: подсветки нет", this.litCount(TARGET) == 0, "подсвечено " + this.litCount(TARGET));
                this.check("только из инвентаря, предметов нет: голограммы скрыты", ghosts > 0 && hidden == ghosts,
                        "скрыто " + hidden + " из " + ghosts);
                shot(mc, "stack-inventory-none");
                server.execute(() -> command(server, "give @a minecraft:armor_stand 8"));
                wait = 4000L;
            }
            case 4 ->
            {
                fly(mc);
                // Стойки с собой — видны и отмечены только стойки, рамки по-прежнему скрыты
                int stands = (COUNT + 1) / 2;
                this.check("только из инвентаря, стойки с собой: отмечены стойки и только они", marks(TARGET) == stands,
                        "рамок " + marks(TARGET) + ", ждали " + stands);
                shot(mc, "stack-inventory-stands");
                LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "gamemode creative @a");
                    command(server, tp(TARGET, 8.0D, 2.5D, -2.5D) + facing(TARGET, 8.0D, 0.0D, 2.0D));
                });
                wait = 3000L;
            }
            case 5 ->
            {
                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                LayerCountManager.getInstance().markDirty();
                wait = 6000L;
            }
            case 6 ->
            {
                this.nearMarks = marks(TARGET);
                this.nearRow = entitiesMissing();
                CovOut.log(T + "вблизи: рамок на площадке " + this.nearMarks + ", в списке не хватает " + this.nearRow);
                // Радиус сущностей вдвое меньше — и встаём за ним, но в загруженных чанках
                this.rangeWas = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();
                LayerListConfigs.VERIFY_DRAW_RANGE.setIntegerValue(LayerListConfigs.VERIFY_DRAW_RANGE.getMaxIntegerValue());
                mc.options.entityDistanceScaling().set(0.5D);
                double range = EntityType.ARMOR_STAND.clientTrackingRange() * 16.0D * 0.5D;
                CovOut.log(T + "радиус стоек " + range + ", дальность чанков " + mc.options.getEffectiveRenderDistance());
                server.execute(() -> command(server, tp(TARGET, 8.0D - range - 24.0D, 6.0D, 4.5D)
                        + String.format(Locale.ROOT, " facing %d %d %d", TARGET.getX() + 8, TARGET.getY(), TARGET.getZ() + 4)));
                wait = 9000L;
            }
            case 7 ->
            {
                LayerCountManager.getInstance().markDirty();
                wait = 4000L;
            }
            case 8 ->
            {
                int seen = mc.level.getEntitiesOfClass(Entity.class, area(TARGET), e -> e != mc.player).size();
                boolean loaded = WorldUtils.isClientChunkLoaded(mc.level, TARGET.getX() >> 4, TARGET.getZ() >> 4);
                int farMarks = marks(TARGET);
                int farRow = entitiesMissing();
                CovOut.log(T + "издалека: игрок " + mc.player.position() + ", чанк площадки загружен " + loaded
                        + ", сущностей площадки у клиента " + seen);
                this.check("издалека: условия опыта (чанк есть, сущностей у клиента нет)", loaded && seen == 0, "");
                this.check("издалека: рамок столько же, сколько вблизи", farMarks == this.nearMarks,
                        "вблизи " + this.nearMarks + ", издалека " + farMarks);
                this.check("издалека: в списке не хватает столько же", farRow == this.nearRow,
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
        return origin.offset(2 * i, 0, 0);
    }

    private static int marks(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) LayerVerifier.getInstance().getEntityMarks().stream()
                .filter(m -> area.contains(m.box().getCenter())).count();
    }

    private int litCount(BlockPos origin)
    {
        AABB area = area(origin);

        return (int) com.aell.layerlist.ghost.EntityOverlay.boxes().stream().filter(box -> area.contains(box.getCenter())).count();
    }

    private static int entitiesMissing()
    {
        int total = 0;

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getStack().getItem()).getPath();

            if (path.equals("armor_stand") || path.equals("item_frame"))
            {
                total += (int) entry.getCountMissing();
            }
        }

        return total;
    }

    private static AABB area(BlockPos o)
    {
        return new AABB(o.getX() - 2.0D, o.getY() - 1.0D, o.getZ() - 2.0D, o.getX() + 2.0D * COUNT + 2.0D, o.getY() + 4.0D, o.getZ() + 4.0D);
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

    private static void fly(Minecraft mc)
    {
        if (! mc.player.getAbilities().flying)
        {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(T + "снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
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

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        CovOut.log(T + "итог: OK " + this.ok + ", FAIL " + this.failed);
    }
}
