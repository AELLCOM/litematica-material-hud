package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.storage.AllChestsSearch;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code chestsweep}: «Подсветить все сундуки вне зависимости от слоя/страницы» (17.10.2026). Сундук A — с
 * материалом схемы, сундук B — с блоком, которого в схеме нет; оба открыты (индекс складов). Опция включена — A
 * отмечен цветом своего предмета с подписью-именем (1.0.14), B нет; клавиша переключает режим без окна; выключили —
 * поиск вернулся к странице панели (B так и не отмечен, «весь список» не остался).
 */
@Environment(EnvType.CLIENT)
public final class SearchAllTest implements CovJob
{
    private static final BlockPos A = new BlockPos(380, 100, 0);
    private static final BlockPos B = new BlockPos(382, 100, 0);
    private String needName = "";

    private int stage;
    private long nextAt;
    private boolean failed;
    private CompletableFuture<List<MaterialRow>> rows;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "SEARCHALL (подсветить все сундуки)";
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

        StorageSearch.getInstance().clear();
        LayerListConfigs.SEARCH_ALL_CHESTS.setBooleanValue(false);
        Path file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("_k").resolve("k-generated.litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(file);
        this.placement = SchematicPlacement.createFor(schematic, new BlockPos(400, 100, 40), "searchall", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        this.rows = SchematicMaterials.ofLitematica(schematic);

        server.execute(() ->
        {
            command(server, "forceload add 370 -10 400 20");
            command(server, "fill 374 99 -6 392 99 10 minecraft:stone");
            command(server, "fill 374 100 -6 392 106 10 minecraft:air");
            command(server, "gamemode creative @a");
            command(server, "tp @a 381.5 100 3.5 180 20");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        this.nextAt = Util.getMillis() + 1200L;
        MinecraftServer server = mc.getSingleplayerServer();

        switch (this.stage++)
        {
            case 0 ->
            {
                if (! this.rows.isDone())
                {
                    --this.stage;
                    return false;
                }

                String item = null;

                for (MaterialRow row : this.rows.join())
                {
                    if (! row.entity() && ! row.stack().isEmpty())
                    {
                        item = BuiltInRegistries.ITEM.getKey(row.stack().getItem()).toString();
                        break;
                    }
                }

                this.check("в схеме есть материалы", item != null, String.valueOf(item));
                String need = item;
                this.needName = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item))
                        .getDefaultInstance().getHoverName().getString();
                server.execute(() ->
                {
                    command(server, chest(A, need));
                    command(server, chest(B, "minecraft:dragon_egg"));
                });
                StorageIndex.getInstance().removeNonCanonical(StorageIndex.scope(mc), BlockPos.ZERO, List.of(A, B));
            }
            case 1 -> click(mc, A);
            case 2 ->
            {
                mc.player.closeContainer();
                click(mc, B);
            }
            case 3 ->
            {
                mc.player.closeContainer();
                LayerListConfigs.SEARCH_ALL_CHESTS.setBooleanValue(true);
                this.nextAt = Util.getMillis() + 2500L;
            }
            case 4 ->
            {
                shot(mc, "chestsweep-all");
                var matches = StorageSearch.getInstance().getMatches();
                List<Integer> a = matches.get(A);
                this.check("сундук с материалом схемы отмечен", a != null && ! a.isEmpty(), String.valueOf(a));
                this.check("над сундуком подписан его предмет", a != null && a.stream()
                        .anyMatch(c -> StorageSearch.getInstance().labelFor(c).equals(this.needName)), this.needName);
                this.check("сундук без материалов схемы не отмечен", ! matches.containsKey(B), String.valueOf(matches.get(B)));
                // Поиск страницы, пока опция включена, ничего не меняет
                int before = StorageSearch.getInstance().markedItems().size();
                StorageSearch.getInstance().togglePage(List.of());
                this.check("клавиша поиска страницы не сбивает «все сундуки»", StorageSearch.getInstance().isActive()
                        && StorageSearch.getInstance().markedItems().size() == before, "");
                AllChestsSearch.toggleFromHotkey();
                this.check("клавиша выключает без окна", ! AllChestsSearch.enabled() && mc.screen == null, "");
            }
            case 5 ->
            {
                shot(mc, "chestsweep-page");
                var search = StorageSearch.getInstance();
                var page = com.aell.layerlist.core.LayerCountManager.getInstance().searchEntries();
                this.check("выключили — поиск по странице панели", search.markedItems().size()
                        == StorageSearch.countSearchable(page) && search.isActive() == ! search.isEmpty(),
                        search.markedItems().size() + " / " + StorageSearch.countSearchable(page));
                this.check("выключили — сундук без материалов не отмечен", ! search.getMatches().containsKey(B), "");
                AllChestsSearch.toggleFromHotkey();
                this.check("клавиша включает без окна", AllChestsSearch.enabled() && mc.screen == null, "");
                LayerListConfigs.SEARCH_ALL_CHESTS.setBooleanValue(false);
            }
            default ->
            {
                CovOut.log("[searchall] " + (this.failed ? "ЕСТЬ ОШИБКИ" : "всё прошло"));
                return true;
            }
        }

        return false;
    }

    /** Снимок кадра в run/screenshots — глазами сверить рамку «все сундуки» и подписи. */
    private static void shot(Minecraft mc, String name)
    {
        java.nio.file.Path path = FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve(name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), image ->
        {
            try (image)
            {
                java.nio.file.Files.createDirectories(path.getParent());
                image.writeToFile(path);
            }
            catch (java.io.IOException | RuntimeException e)
            {
                CovOut.log("[searchall] снимок не записан: " + e);
            }
        });
    }

    private static String chest(BlockPos pos, String item)
    {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"" + item + "\",count:5}]}";
    }

    private static void click(Minecraft mc, BlockPos pos)
    {
        Vec3 face = Vec3.atCenterOf(pos).add(0, 0, 0.5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.SOUTH, pos, false));
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[searchall] команда не прошла (" + command + "): " + e);
        }
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[searchall] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    @Override
    public void close()
    {
        LayerListConfigs.SEARCH_ALL_CHESTS.setBooleanValue(false);
        StorageSearch.getInstance().clear();
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
    }
}
