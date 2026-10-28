package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code glow}: снимки новой подсветки хранилищ (13.10.2026) — одиночный и двойной сундук, бочка, шалкер,
 * сундук с двумя искомыми предметами (полосы) и сундук за стеной (видно сквозь неё). Хранилища попадают в индекс
 * настоящими кликами, поиск — как по строкам списка. Снимки: цвета строк, свой цвет, яркость 100 %.
 */
@Environment(EnvType.CLIENT)
final class GlowShot implements CovJob
{
    private static final BlockPos SINGLE = new BlockPos(420, 100, 0);
    private static final BlockPos DOUBLE_L = new BlockPos(422, 100, 0);
    private static final BlockPos DOUBLE_R = new BlockPos(423, 100, 0);
    private static final BlockPos BARREL = new BlockPos(425, 100, 0);
    private static final BlockPos SHULKER = new BlockPos(427, 100, 0);
    private static final BlockPos MIXED = new BlockPos(429, 100, 0);
    private static final BlockPos HIDDEN = new BlockPos(424, 100, -4);
    private static final List<BlockPos> ALL = List.of(SINGLE, DOUBLE_L, BARREL, SHULKER, MIXED, HIDDEN);

    private int stage;
    private long nextAt;

    @Override
    public String name()
    {
        return "GLOW (подсветка хранилищ, снимки)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        LayerListConfigs.HIGHLIGHT_OWN_COLOR.setBooleanValue(false);
        LayerListConfigs.HIGHLIGHT_GLOW.setIntegerValue(60);
        LayerListConfigs.HIGHLIGHT_LABELS.setBooleanValue(true);
        // Схемы в мире нет: «следовать за страницей» подменило бы выбор пустой страницей
        LayerListConfigs.SEARCH_FOLLOWS_PAGE.setBooleanValue(false);

        server.execute(() -> {
            command(server, "forceload add 410 -12 440 12");
            command(server, "fill 412 99 -8 436 99 8 minecraft:grass_block");
            command(server, "fill 412 100 -8 436 106 8 minecraft:air");
            command(server, "time set 6000");
            command(server, "gamerule advance_time false");
            command(server, "setblock " + p(SINGLE) + " minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:stone\",count:20}]}");
            command(server, "setblock " + p(DOUBLE_L) + " minecraft:chest[facing=south,type=right]{Items:[{Slot:0b,id:\"minecraft:oak_planks\",count:30}]}");
            command(server, "setblock " + p(DOUBLE_R) + " minecraft:chest[facing=south,type=left]");
            command(server, "setblock " + p(BARREL) + " minecraft:barrel[facing=up]{Items:[{Slot:0b,id:\"minecraft:moss_block\",count:12}]}");
            command(server, "setblock " + p(SHULKER) + " minecraft:purple_shulker_box{Items:[{Slot:0b,id:\"minecraft:stone\",count:5}]}");
            command(server, "setblock " + p(MIXED) + " minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:stone\",count:4},{Slot:1b,id:\"minecraft:oak_planks\",count:4},{Slot:2b,id:\"minecraft:moss_block\",count:4}]}");
            command(server, "setblock " + p(HIDDEN) + " minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:moss_block\",count:3}]}");
            command(server, "fill 422 100 -3 426 102 -3 minecraft:stone_bricks");
            command(server, "gamemode creative @a");
            command(server, "clear @a");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    private static String p(BlockPos pos)
    {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void command(MinecraftServer server, String command)
    {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
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

        this.nextAt = now + 700L;
        MinecraftServer server = mc.getSingleplayerServer();
        int s = this.stage++;

        // Открыть и закрыть каждое хранилище — так оно попадает в индекс (как у игрока)
        if (s < ALL.size() * 2)
        {
            BlockPos pos = ALL.get(s / 2);

            if (s % 2 == 0)
            {
                mc.player.closeContainer();
                server.execute(() -> command(server, String.format(java.util.Locale.ROOT, "tp @a %.1f 100 %.1f 180 30",
                        pos.getX() + 0.5, pos.getZ() + 2.5)));
                this.nextAt = now + 900L;
            }
            else
            {
                Vec3 face = Vec3.atCenterOf(pos).add(0.0, 0.0, 0.45);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.SOUTH, pos, false));
                this.nextAt = now + 900L;
            }

            return false;
        }

        int t = s - ALL.size() * 2;

        switch (t)
        {
            case 0 ->
            {
                mc.player.closeContainer();
                StorageSearch search = StorageSearch.getInstance();
                search.clear();
                search.togglePage(List.of(entry(Items.STONE), entry(Items.OAK_PLANKS), entry(Items.MOSS_BLOCK)));
                CovOut.log("[glow] хранилищ с совпадениями: " + search.getMatches().size()
                        + ", в индексе сундук: " + StorageIndex.getInstance().has(StorageIndex.scope(mc), SINGLE));
                server.execute(() -> command(server, "tp @a 424.5 102.2 7.5 180 22"));
                this.nextAt = now + 2500L;
            }
            case 1 -> shot(mc, "glow-rows");
            case 2 ->
            {
                LayerListConfigs.HIGHLIGHT_OWN_COLOR.setBooleanValue(true);
                LayerListConfigs.HIGHLIGHT_HUE.setIntegerValue(190);
                this.nextAt = now + 1200L;
            }
            case 3 -> shot(mc, "glow-own");
            case 4 ->
            {
                LayerListConfigs.HIGHLIGHT_OWN_COLOR.setBooleanValue(false);
                LayerListConfigs.HIGHLIGHT_GLOW.setIntegerValue(100);
                this.nextAt = now + 1200L;
            }
            case 5 -> shot(mc, "glow-strong");
            case 6 ->
            {
                LayerListConfigs.HIGHLIGHT_GLOW.setIntegerValue(60);
                server.execute(() -> command(server, "tp @a 424.5 101.2 3.5 180 15"));
                this.nextAt = now + 1500L;
            }
            case 7 -> shot(mc, "glow-close");
            default ->
            {
                StorageSearch.getInstance().clear();
                CovOut.log("[glow] готово");
                return true;
            }
        }

        return false;
    }

    private static MaterialListEntry entry(net.minecraft.world.item.Item item)
    {
        return new MaterialListEntry(new ItemStack(item), 64, 64, 0, 0);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[glow] перед снимком: совпадений " + StorageSearch.getInstance().getMatches().size()
                + ", кадров рендера подсветки " + com.aell.layerlist.storage.StorageHighlightRenderer.frames);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
        CovOut.log("[glow] снимок " + name);
    }

    @Override
    public void close()
    {
    }
}
