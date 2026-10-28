package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code nestedsearch}: поиск по складам видит предметы внутри шалкеров и мешочков, лежащих в любом
 * контейнере (просьба 14.10.2026: «сундук не подсвечивается, только шалкер, когда заглянешь внутрь»). Схема —
 * камень, доски, стекло. Сундук A: шалкер с камнем. Бочка B: мешочек с досками. Сундук C: шалкер, в нём мешочек
 * со стеклом (двойная вложенность). Сундук D: земля — маркера быть не должно. Каждый контейнер открывается и
 * закрывается (так его запоминает индекс), затем поиск по странице: маркеры должны встать над A, B, C.
 */
@Environment(EnvType.CLIENT)
public final class NestedSearchTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(680, 100, 0);
    private static final BlockPos TARGET = new BlockPos(680, 100, 14);
    private static final BlockPos A = TARGET.offset(0, 0, 8);
    private static final BlockPos B = TARGET.offset(2, 0, 8);
    private static final BlockPos C = TARGET.offset(4, 0, 8);
    private static final BlockPos D = TARGET.offset(6, 0, 8);
    private static final BlockPos[] ALL = {A, B, C, D};

    private int stage;
    private int opened;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "nestedsearch (поиск по складам внутри шалкеров и мешочков)";
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
        StorageSearch.getInstance().clear();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 670 -10 700 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 12, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 5, 12, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 0, 2, 0, 0, "minecraft:stone"));
            command(server, fill(REF, 0, 0, 1, 1, 0, 1, "minecraft:oak_planks"));
            command(server, fill(REF, 0, 0, 2, 0, 0, 2, "minecraft:glass"));
            command(server, "tp @a " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
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
                area.setName("dev-nested");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 0, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-nested", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, chest(A, "chest", "{Slot:0b,id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":"
                            + "[{slot:0,item:{id:\"minecraft:stone\",count:5}}]}}"));
                    command(server, chest(B, "barrel", "{Slot:0b,id:\"minecraft:bundle\",count:1,components:{\"minecraft:bundle_contents\":"
                            + "[{id:\"minecraft:oak_planks\",count:3}]}}"));
                    command(server, chest(C, "chest", "{Slot:4b,id:\"minecraft:white_shulker_box\",count:1,components:{\"minecraft:container\":"
                            + "[{slot:2,item:{id:\"minecraft:bundle\",count:1,components:{\"minecraft:bundle_contents\":"
                            + "[{id:\"minecraft:glass\",count:2}]}}}]}}"));
                    command(server, chest(D, "chest", "{Slot:0b,id:\"minecraft:dirt\",count:7}"));
                    command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " " + (A.getZ() + 2.5D) + " 180 30");
                });
                wait = 3500L;
            }
            case 1 ->
            {
                // Открыть и закрыть каждый контейнер по очереди — так его запоминает индекс
                if (mc.screen != null)
                {
                    mc.player.closeContainer();
                    ++this.opened;
                    wait = 700L;
                    this.nextAt = now + wait;
                    return false;
                }

                if (this.opened < ALL.length)
                {
                    BlockPos pos = ALL[this.opened];
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false));
                    this.nextAt = now + 1500L;
                    return false;
                }

                String scope = StorageIndex.scope(mc);

                for (BlockPos pos : ALL)
                {
                    StorageIndex.Rec rec = StorageIndex.getInstance().get(scope, pos);
                    CovOut.log("[nested] индекс " + name(pos) + ": " + (rec == null ? "нет записи" : rec.counts));
                }

                StorageIndex.Rec a = StorageIndex.getInstance().get(scope, A);
                StorageIndex.Rec b = StorageIndex.getInstance().get(scope, B);
                StorageIndex.Rec c = StorageIndex.getInstance().get(scope, C);
                this.check("в индексе A камень из шалкера", a != null && Integer.valueOf(5).equals(a.counts.get("minecraft:stone")), "");
                this.check("в индексе B доски из мешочка (бочка)", b != null && Integer.valueOf(3).equals(b.counts.get("minecraft:oak_planks")), "");
                this.check("в индексе C стекло из мешочка в шалкере", c != null && Integer.valueOf(2).equals(c.counts.get("minecraft:glass")), "");
                this.check("в индексе C и сами шалкер с мешочком", c != null && c.counts.containsKey("minecraft:white_shulker_box")
                        && c.counts.containsKey("minecraft:bundle"), "");

                int searchable = StorageSearch.getInstance().togglePage(LayerCountManager.getInstance().getPageEntries());
                StorageSearch.getInstance().requestRefresh();
                CovOut.log("[nested] поиск по странице: искомых предметов " + searchable);
                wait = 1500L;
            }
            case 2 ->
            {
                Map<BlockPos, List<Integer>> matches = StorageSearch.getInstance().getMatches();
                CovOut.log("[nested] маркеры: " + matches.keySet());
                this.check("маркер над сундуком A (шалкер с камнем)", matches.containsKey(A), "");
                this.check("маркер над бочкой B (мешочек с досками)", matches.containsKey(B), "");
                this.check("маркер над сундуком C (мешочек со стеклом в шалкере)", matches.containsKey(C), "");
                this.check("над сундуком D с землёй маркера нет", ! matches.containsKey(D), "");
                StorageSearch.getInstance().clear();
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[nested] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private static String name(BlockPos pos)
    {
        return pos.equals(A) ? "A" : pos.equals(B) ? "B" : pos.equals(C) ? "C" : "D";
    }

    private static String chest(BlockPos pos, String block, String items)
    {
        String facing = "facing=south";
        return String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s[%s]{Items:[%s]}", pos.getX(), pos.getY(), pos.getZ(),
                block, facing, items);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[nested] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[nested] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        StorageSearch.getInstance().clear();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
