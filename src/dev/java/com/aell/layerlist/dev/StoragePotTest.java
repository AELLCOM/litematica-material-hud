package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.ArrayList;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code q}: индекс хранилищ и горшки (жалоба игрока 08.10.2026).
 *
 * <p>Хранилища — настоящими кликами {@code useItemOn} по трём сундукам: A придавлен камнем и
 * не открывается, B и C открываются. Клик по A, потом по B — содержимое B не должно лечь на A
 * (раньше первый же экран забирал самый старый клик очереди). Двойной клик по C, потом B —
 * лишний клик C не должен забрать экран B. Клик по B и C разом — экраны ничьи, записи не
 * портятся.
 *
 * <p>Горшки — схема из двух клеток: горшок с одуванчиком (в мире — пустой горшок) и горшок с
 * маком (в мире — горшок с одуванчиком). Первая — «не хватает одуванчика», горшок готов; вторая —
 * «не тот блок». Снимок клетки с пустым горшком — для глаз: голограмма цветка и голубой оверлей.
 */
@Environment(EnvType.CLIENT)
public final class StoragePotTest implements CovJob
{
    private static final BlockPos A = new BlockPos(360, 100, 0);
    private static final BlockPos B = new BlockPos(362, 100, 0);
    private static final BlockPos C = new BlockPos(364, 100, 0);
    private static final BlockPos POT_PLANT = new BlockPos(360, 100, 8);
    private static final BlockPos POT_WRONG = new BlockPos(362, 100, 8);

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "Q (хранилища и горшки)";
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

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "forceload add 350 -10 380 20");
            command(server, "fill 354 99 -6 372 99 14 minecraft:stone");
            command(server, "fill 354 100 -6 372 106 14 minecraft:air");
            command(server, chest(A, "minecraft:stone", 3));
            command(server, "setblock 360 101 0 minecraft:stone");
            command(server, chest(B, "minecraft:moss_block", 10));
            command(server, chest(C, "minecraft:oak_planks", 7));
            command(server, "setblock 360 100 8 minecraft:potted_dandelion");
            command(server, "setblock 361 100 8 minecraft:potted_cactus");
            command(server, "setblock 362 100 8 minecraft:potted_poppy");
            command(server, "gamemode creative @a");
            command(server, "clear @a");
            command(server, "tp @a 362.5 100 2.5 180 20");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    private static String chest(BlockPos pos, String item, int count)
    {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"" + item + "\",count:" + count + "}]}";
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        this.nextAt = now + 1000L;

        switch (this.stage)
        {
            // ===== Хранилища =====
            case 0 ->
            {
                // Индекс переживает пересоздание мира — стираем прошлые записи этих сундуков
                StorageIndex.getInstance().removeNonCanonical(StorageIndex.scope(mc), BlockPos.ZERO, java.util.List.of(A, B, C));
                click(mc, A);
            }
            case 1 -> click(mc, B);
            case 2 ->
            {
                this.check("экран сундука B открыт", fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?>,
                        "" + fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen());
                mc.player.closeContainer();
            }
            case 3 ->
            {
                this.check("клик по запертому A не забрал экран B — A не записан", record(mc, A) == null,
                        "A=" + record(mc, A));
                this.check("B записан своим содержимым", Map.of("minecraft:moss_block", 10).equals(record(mc, B)),
                        "B=" + record(mc, B));
                click(mc, C);
                click(mc, C);
            }
            case 4 ->
            {
                mc.player.closeContainer();
                this.nextAt = now + 300L;
            }
            case 5 -> click(mc, B);
            case 6 ->
            {
                mc.player.closeContainer();
                this.nextAt = now + 300L;
            }
            case 7 ->
            {
                this.check("двойной клик по C не забрал экран B — C записан своим", Map.of("minecraft:oak_planks", 7).equals(record(mc, C)),
                        "C=" + record(mc, C));
                this.check("B после повторного открытия — своё", Map.of("minecraft:moss_block", 10).equals(record(mc, B)),
                        "B=" + record(mc, B));
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> command(server, chest(B, "minecraft:moss_block", 15)));
            }
            case 8 ->
            {
                click(mc, B);
                click(mc, C);
            }
            case 9 ->
            {
                mc.player.closeContainer();
                this.nextAt = now + 300L;
            }
            case 10 ->
            {
                this.check("B и C кликнуты разом — чужое не записано", Map.of("minecraft:oak_planks", 7).equals(record(mc, C))
                                && Map.of("minecraft:moss_block", 10).equals(record(mc, B)),
                        "B=" + record(mc, B) + ", C=" + record(mc, C));
                this.pots(mc);
            }
            // ===== Горшки =====
            case 11 ->
            {
                WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
                boolean ready = world != null && world.getBlockState(POT_PLANT).is(Blocks.POTTED_DANDELION);

                if (! ready)
                {
                    this.nextAt = now + 250L;
                    return false;
                }

                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> {
                    command(server, "setblock 360 100 8 minecraft:flower_pot");
                    command(server, "setblock 361 100 8 minecraft:flower_pot");
                    command(server, "setblock 362 100 8 minecraft:potted_dandelion");
                    command(server, "tp @a 361.5 100 10.3 180 38");
                });
                this.nextAt = now + 3000L;
            }
            case 12 ->
            {
                LayerCountManager.getInstance().markDirty();
                this.nextAt = now + 3000L;
            }
            case 13 ->
            {
                int dandelion = missing(Items.DANDELION);
                int pot = missing(Items.FLOWER_POT);
                int poppy = missing(Items.POPPY);
                int cactus = missing(Items.CACTUS);
                this.check("список: одуванчик для пустого горшка нужен", dandelion == 1, "одуванчик " + dandelion);
                this.check("список: кактус для пустого горшка нужен", cactus == 1, "кактус " + cactus);
                this.check("список: горшок уже стоит — не нужен", pot == 0, "горшок " + pot);
                this.check("список: горшок с чужим цветком — «не тот блок», в нехватку не идёт", poppy == 0, "мак " + poppy);
                LayerVerifier.getInstance().toggle();
                this.nextAt = now + 3000L;
            }
            case 14 ->
            {
                LayerVerifier v = LayerVerifier.getInstance();
                int missing = v.getCount(LayerVerifier.Category.MISSING);
                int wrong = v.getCount(LayerVerifier.Category.WRONG_BLOCK);
                this.check("Ctrl+B: пустые горшки — «пропущено», чужой цветок — «не тот блок»", missing == 2 && wrong == 1,
                        "пропущено " + missing + ", не тот блок " + wrong);
                v.toggle();
            }
            case 15 -> Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
            case 16 ->
            {
                CovOut.log("[storagepot] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
            default -> { return true; }
        }

        ++this.stage;
        return false;
    }

    private void pots(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName("dev-pots");
        area.addSubRegionBox(new Box(POT_PLANT, POT_WRONG, "main"), false);
        area.setExplicitOrigin(POT_PLANT);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);

        if (schematic == null)
        {
            this.check("схема горшков снята", false, "null");
            return;
        }

        this.placement = SchematicPlacement.createFor(schematic, POT_PLANT, "dev-pots", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
    }

    private static int missing(Item item)
    {
        for (MaterialListEntry entry : LayerCountManager.getInstance().getEntries())
        {
            if (entry.getStack().getItem() == item)
            {
                return entry.getCountMissing();
            }
        }

        return 0;
    }

    private static Map<String, Integer> record(Minecraft mc, BlockPos pos)
    {
        StorageIndex.Rec rec = StorageIndex.getInstance().get(StorageIndex.scope(mc), pos);

        return rec == null ? null : rec.counts;
    }

    private static void click(Minecraft mc, BlockPos pos)
    {
        Vec3 face = Vec3.atCenterOf(pos).add(0, 0, 0.5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.SOUTH, pos, false));
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[storagepot] " + (ok ? "OK   " : "FAIL ") + what + " — " + detail);
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
            CovOut.log("[storagepot] команда не прошла (" + command + "): " + e);
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
