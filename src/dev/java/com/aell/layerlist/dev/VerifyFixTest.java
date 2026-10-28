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
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code verifyfix} (жалобы 26.10.2026):
 * <ul>
 *   <li>Ctrl+B (проверка блоков) подсвечивает и сущности схемы: подушку, стойку и рамку с алмазом, которых в мире нет;</li>
 *   <li>флаг с узором встаёт Easy Place, когда в руке флаг того же цвета без узора (malilib считал «уже в руке» и
 *       не менял), и наоборот — флаг без узора, когда в руке узорный;</li>
 *   <li>вода-источник из схемы, налитая в мире, — строка «Ведро воды» без нехватки.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class VerifyFixTest implements CovJob
{
    private static final String T = "[verifyfix] ";
    private static final BlockPos REF = new BlockPos(940, 100, 0);
    private static final BlockPos TARGET = new BlockPos(940, 100, 20);
    private static final String PATTERN = "[{pattern:\"minecraft:gradient\",color:\"lime\"},{pattern:\"minecraft:skull\",color:\"white\"}]";
    private static final BlockPos PATTERNED = new BlockPos(1, 0, 4);
    private static final BlockPos PLAIN = new BlockPos(3, 0, 4);
    private static final BlockPos WATER = new BlockPos(5, -1, 4);
    private static final long PLACE_MS = 8000L;

    private int stage;
    private long nextAt;
    private long deadline;
    private long lastTick = Long.MIN_VALUE;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "verifyfix (сущности в Ctrl+B, флаг с узором при таком же флаге в руке, вода-источник в счёте)";
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
        LayerListConfigs.VERIFY_ENABLED.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "time set noon");
            command(server, "forceload add 930 -10 960 40");
            command(server, "kill @e[type=!player,x=930,y=90,z=-10,dx=30,dy=20,dz=50]");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -2, -2, 8, -1, 8, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 4, 8, "minecraft:air"));
                command(server, set(o, new BlockPos(5, 0, 2), "minecraft:stone"));
                command(server, set(o, WATER, "minecraft:water"));
            }

            command(server, set(REF, PATTERNED, "minecraft:black_banner[rotation=0]{patterns:" + PATTERN + "}"));
            command(server, set(REF, PLAIN, "minecraft:black_banner[rotation=0]"));
            command(server, summon(REF, "minecraft:armor_stand", 3.5D, 0.0D, 1.5D, "{Rotation:[90f,0f]}"));
            command(server, summon(REF, "minecraft:item_frame", 5.5D, 0.5D, 1.96875D,
                    "{Facing:2b,Item:{id:\"minecraft:diamond\",count:1}}"));
            command(server, summon(REF, "minecraft:cushion", 1.5D, 0.0D, 1.5D, "{color:\"red\",Rotation:[0f,0f],block_pos:[I;"
                    + (REF.getX() + 1) + "," + REF.getY() + "," + (REF.getZ() + 1) + "]}"));
            command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + (TARGET.getY() + 3) + " " + (TARGET.getZ() - 3.5D)
                    + " facing " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 2.5D));
        });

        // Площадка строится командами сервера, а чанки только что загружены силой: даём время
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
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                int entities = mc.level.getEntities((net.minecraft.world.entity.Entity) null,
                        new net.minecraft.world.phys.AABB(Vec3.atCenterOf(REF.offset(-1, -1, -1)), Vec3.atCenterOf(REF.offset(7, 3, 7)))).size();
                CovOut.log(T + "сущностей в образце: " + entities);
                AreaSelection area = new AreaSelection();
                area.setName("dev-verifyfix");
                area.addSubRegionBox(new Box(REF.offset(0, -1, 0), REF.offset(6, 2, 5), "main"), false);
                area.setExplicitOrigin(REF.offset(0, -1, 0));
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET.offset(0, -1, 0), "dev-verifyfix", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                LayerListConfigs.EP_DEBUG.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                wait = 4000L;
            }
            case 1 ->
            {
                // Харнесс переносит игрока после begin — встаём у схемы заново, в пределах дальности подсветки
                server.execute(() -> command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " "
                        + (TARGET.getZ() - 1.5D) + " facing " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " "
                        + (TARGET.getZ() + 2.5D)));

                if (! LayerVerifier.getInstance().isHighlighting())
                {
                    LayerVerifier.getInstance().toggle();
                }

                wait = 4000L;
            }
            case 2 ->
            {
                List<String> marks = new ArrayList<>();
                LayerVerifier.getInstance().getEntityMarks().forEach(m -> marks.add(m.category() + " " + m.box().getCenter()));
                CovOut.log(T + "рамки сущностей: " + marks + ", игрок " + mc.player.position() + ", подсветка " + LayerVerifier.getInstance().isHighlighting());
                var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                for (var wanted : com.aell.layerlist.core.SchematicEntities.collect(mc.level))
                {
                    var ghost = com.aell.layerlist.core.SchematicEntities.ghost(world, wanted);
                    CovOut.log(T + "  нужна " + wanted.kind() + " " + wanted.pos() + ", голограмма " + ghost
                            + ", сверка " + (ghost != null ? com.aell.layerlist.core.SchematicEntities.match(mc.level, ghost)
                                    : com.aell.layerlist.core.SchematicEntities.matchApprox(mc.level, wanted)).statuses());
                }
                long missing = LayerVerifier.getInstance().getEntityMarks().stream()
                        .filter(m -> m.category() == LayerVerifier.Category.MISSING).count();
                // Подушек до 26.3 нет — summon молча не встаёт: ждём стойку и рамку
                this.check("Ctrl+B: стойка и рамка — две красные рамки (подушек в этой версии нет)", missing == 2, marks.toString());
                shot(mc, "verify-entities");
                wait = 500L;
            }
            case 3 ->
            {
                // Ставим настоящую стойку не тем поворотом — рамка жёлтая
                server.execute(() -> command(server, summon(TARGET, "minecraft:armor_stand", 3.5D, 0.0D, 1.5D, "{Rotation:[0f,0f]}")));
                wait = 3000L;
            }
            case 4 ->
            {
                List<String> marks = new ArrayList<>();
                LayerVerifier.getInstance().getEntityMarks().forEach(m -> marks.add(m.category() + " " + m.box().getCenter()));
                long wrong = LayerVerifier.getInstance().getEntityMarks().stream()
                        .filter(m -> m.category() == LayerVerifier.Category.WRONG_STATE).count();
                this.check("Ctrl+B: стойка не тем поворотом — жёлтая рамка", wrong == 1, marks.toString());
                shot(mc, "verify-entities-wrong-stand");
                LayerVerifier.getInstance().toggle();
                wait = 500L;
            }
            case 5 ->
            {
                MaterialListEntry water = row(Items.WATER_BUCKET);
                this.check("вода-источник налита — у «Ведро воды» нехватки нет",
                        water == null || water.getCountMissing() == 0,
                        water == null ? "строки нет" : "всего " + water.getCountTotal() + ", нет " + water.getCountMissing());
                server.execute(() -> {
                    command(server, "clear @a");
                    command(server, "gamemode survival @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:black_banner 4");
                    // Хотбар забит, как у игрока на стройке: Litematica не найдёт пустой ячейки и будет менять ту, где флаг
                    String[] fillers = {"stone", "dirt", "oak_planks", "cobblestone", "sand", "gravel", "andesite", "diorite"};

                    for (int i = 0; i < fillers.length; ++i)
                    {
                        command(server, "item replace entity @a hotbar." + (i + 1) + " with minecraft:" + fillers[i] + " 16");
                    }

                    command(server, "item replace entity @a inventory.11 with minecraft:black_banner[banner_patterns=" + PATTERN + "] 2");
                    command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", TARGET.getX() + 2.0D, TARGET.getY(),
                            TARGET.getZ() + 2.5D));
                });
                wait = 3000L;
            }
            case 6 ->
            {
                mc.player.getInventory().setSelectedSlot(0);

                if (this.place(mc, now, PATTERNED))
                {
                    return false;
                }

                this.check("флаг с узором встал, хотя в руке был чёрный без узора", this.patterns(mc, PATTERNED) == 2,
                        "узоров " + this.patterns(mc, PATTERNED) + ", в мире " + mc.level.getBlockState(TARGET.offset(PATTERNED)));
                wait = 1500L;
            }
            case 7 ->
            {
                if (this.place(mc, now, PLAIN))
                {
                    return false;
                }

                this.check("флаг без узора встал, хотя в руке был узорный", this.patterns(mc, PLAIN) == 0
                                && mc.level.getBlockState(TARGET.offset(PLAIN)).getBlock() instanceof net.minecraft.world.level.block.BannerBlock,
                        "узоров " + this.patterns(mc, PLAIN) + ", в мире " + mc.level.getBlockState(TARGET.offset(PLAIN))
                                + ", рука " + mc.player.getMainHandItem());
                shot(mc, "verify-banners");
                wait = 500L;
            }
            default ->
            {
                CovOut.log(T + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Easy Place по клетке, пока не встанет флаг или не выйдет срок; {@code true} — ещё ставим. */
    private boolean place(Minecraft mc, long now, BlockPos cell)
    {
        BlockPos pos = TARGET.offset(cell);

        if (this.deadline == 0L)
        {
            this.deadline = now + PLACE_MS;
            CovOut.log(T + "ставлю " + cell + ", в руке " + mc.player.getMainHandItem() + " "
                    + mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.BANNER_PATTERNS));
        }

        if (! mc.level.getBlockState(pos).isAir() || now >= this.deadline)
        {
            this.deadline = 0L;
            return false;
        }

        long tick = mc.level.getGameTime();

        if (tick != this.lastTick)
        {
            this.lastTick = tick;
            Vec3 eyes = mc.player.getEyePosition(1.0F);
            Vec3 aim = Vec3.atCenterOf(pos).add(0.0D, 0.2D, 0.0D);
            double dx = aim.x - eyes.x;
            double dy = aim.y - eyes.y;
            double dz = aim.z - eyes.z;
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            WorldUtils.handleEasyPlace(mc);
        }

        this.nextAt = now + 50L;
        return true;
    }

    private int patterns(Minecraft mc, BlockPos cell)
    {
        return mc.level.getBlockEntity(TARGET.offset(cell)) instanceof BannerBlockEntity banner ? banner.getPatterns().layers().size() : -1;
    }

    private static MaterialListEntry row(net.minecraft.world.item.Item item)
    {
        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            if (entry.getStack().is(item))
            {
                return entry;
            }
        }

        return null;
    }

    private static String summon(BlockPos o, String type, double x, double y, double z, String nbt)
    {
        return String.format(Locale.ROOT, "summon %s %.5f %.5f %.5f %s", type, o.getX() + x, o.getY() + y, o.getZ() + z, nbt);
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
        CovOut.log(T + "снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(T + (ok ? "OK   " : "FAIL ") + what + (detail == null || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log(T + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (LayerVerifier.getInstance().isHighlighting())
        {
            LayerVerifier.getInstance().toggle();
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        LayerListConfigs.EP_DEBUG.setBooleanValue(false);
    }
}
