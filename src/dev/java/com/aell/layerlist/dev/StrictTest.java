package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code strict}: железные защиты (запросы 09.10.2026). Схема: камень в A и в A2, пустая клетка B внутри
 * области, OUT — за её границей. Ручная установка ({@code useItemOn}, как правый клик игрока), четыре прохода:
 * <ul>
 *   <li>только «мимо схемы»: камень в A встаёт, в пустую B — нет, земля в A2 (схема ждёт камень) — встаёт,
 *       за границу — встаёт (защита только в пределах схемы);</li>
 *   <li>только «не тот блок»: камень в A и в пустую B встают, земля в A2 — нет, земля за границу — встаёт;</li>
 *   <li>обе: B и A2 — нет, A и за границу — да; клик камнем по сундуку без приседа открывает сундук;</li>
 *   <li>обе выкл: всё встаёт, как раньше.</li>
 * </ul>
 * С Easy Place Fix+ в {@code run/mods} проверку ведёт он (его опции включаются рефлексией), без него —
 * Material HUD.
 */
@Environment(EnvType.CLIENT)
public final class StrictTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(640, 100, 0);
    private static final BlockPos TARGET = new BlockPos(640, 100, 14);
    private static final BlockPos A = new BlockPos(1, 0, 2);
    private static final BlockPos A2 = new BlockPos(3, 0, 2);
    private static final BlockPos B = new BlockPos(2, 0, 2);
    /** За границей области схемы (область — 0…4 по X, 0…1 по Y, 0…4 по Z). */
    private static final BlockPos OUT = new BlockPos(5, 0, 2);
    private static final BlockPos CHEST = new BlockPos(2, 0, 5);
    private static final int STONE = 0;
    private static final int DIRT = 1;

    /** Проход: опции, что куда ставим (слот хотбара), что должно встать (null — ничего). */
    private record Pass(String name, boolean empty, boolean block, BlockPos[] cells, int[] slots, Block[] expect) {}

    private static final Pass[] PASSES = {
            new Pass("только «мимо схемы»", true, false,
                    new BlockPos[]{A, B, A2, OUT}, new int[]{STONE, STONE, DIRT, STONE},
                    new Block[]{Blocks.STONE, null, Blocks.DIRT, Blocks.STONE}),
            new Pass("только «не тот блок»", false, true,
                    new BlockPos[]{A, B, A2, OUT}, new int[]{STONE, STONE, DIRT, DIRT},
                    new Block[]{Blocks.STONE, Blocks.STONE, null, Blocks.DIRT}),
            new Pass("обе защиты", true, true,
                    new BlockPos[]{A, B, A2, OUT}, new int[]{STONE, STONE, DIRT, STONE},
                    new Block[]{Blocks.STONE, null, null, Blocks.STONE}),
            new Pass("обе выкл", false, false,
                    new BlockPos[]{B, A2}, new int[]{STONE, DIRT},
                    new Block[]{Blocks.STONE, Blocks.DIRT}),
    };

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private int waits;
    private int pass;
    private boolean easyPlaceWas;

    @Override
    public String name()
    {
        return "STRICT (железные защиты: мимо схемы и не тот блок)";
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
        // Клики здесь ручные: с включённым режимом Easy Place их перехватил бы сам Easy Place
        this.easyPlaceWas = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        CovOut.log("[strict] проверку ведёт " + (FabricLoader.getInstance().isModLoaded("easyplacefixplus")
                ? "Easy Place Fix+" : "Material HUD"));

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 630 -10 655 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 9, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 4, 9, "minecraft:air"));
            }

            command(server, set(REF, A, "minecraft:stone"));
            command(server, set(REF, A2, "minecraft:stone"));
            command(server, set(TARGET, CHEST, "minecraft:chest[facing=north]"));
            // Схему снимаем с мира клиента — эталон должен быть в его загруженных чанках
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
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
        long wait = 1200L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-strict");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 1, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-strict", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:stone 32");
                    command(server, "item replace entity @a hotbar.1 with minecraft:dirt 32");
                    command(server, "tp @a " + (TARGET.getX() + 2.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 0.5D) + " 0 30");
                });
                wait = 3000L;
            }
            case 1 ->
            {
                // Ждём, пока Litematica загрузит схему в свой мир (чанки подгружаются после телепорта)
                var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                if ((world == null || world.getBlockState(TARGET.offset(A)).isAir()) && ++this.waits < 40)
                {
                    this.nextAt = now + 250L;
                    return false;
                }

                CovOut.log("[strict] мир схемы в A: " + (world == null ? "нет" : world.getBlockState(TARGET.offset(A)))
                        + ", в B: " + (world == null ? "нет" : world.getBlockState(TARGET.offset(B))));
                wait = 300L;
            }
            case 2 ->
            {
                // Проход: опции и установки
                Pass p = PASSES[this.pass];
                setOptions(p.empty(), p.block());
                CovOut.log("[strict] проход: " + p.name());

                for (int i = 0; i < p.cells().length; ++i)
                {
                    mc.player.getInventory().setSelectedSlot(p.slots()[i]);
                    this.place(mc, p.cells()[i]);
                }

                mc.player.getInventory().setSelectedSlot(STONE);
                wait = 1500L;
            }
            case 3 ->
            {
                Pass p = PASSES[this.pass];

                for (int i = 0; i < p.cells().length; ++i)
                {
                    var state = mc.level.getBlockState(TARGET.offset(p.cells()[i]));
                    Block want = p.expect()[i];
                    String where = p.cells()[i] == OUT ? "за границу схемы" : p.cells()[i] == B ? "в пустую клетку схемы"
                            : "в клетку схемы с камнем";
                    String what = (p.slots()[i] == STONE ? "камень " : "земля ") + where;
                    this.check(p.name() + ": " + what + (want == null ? " — не встаёт" : " — встаёт"),
                            want == null ? state.isAir() : state.is(want), state.toString());
                }

                if (p.empty() && p.block())
                {
                    // Сундук — интеракция, не установка: открывается и с камнем в руке
                    mc.player.getInventory().setSelectedSlot(STONE);
                    BlockPos chest = TARGET.offset(CHEST);
                    var opened = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0, -0.5), Direction.NORTH, chest, false));
                    CovOut.log("[strict] клик по сундуку → " + opened);
                }

                wait = 1000L;
            }
            case 4 ->
            {
                Pass p = PASSES[this.pass];

                if (p.empty() && p.block())
                {
                    this.check(p.name() + ": клик камнем по сундуку открыл сундук",
                            mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen,
                            String.valueOf(mc.screen));
                    mc.player.closeContainer();
                }

                // Площадку — пустой (сундук за границей, z = 5, не трогаем)
                server.execute(() -> command(server, fill(TARGET, 0, 0, 0, 5, 1, 4, "minecraft:air")));

                if (++this.pass < PASSES.length)
                {
                    this.nextAt = now + 1200L;
                    this.stage = 2;
                    return false;
                }

                setOptions(false, false);
                wait = 300L;
            }
            default ->
            {
                CovOut.log("[strict] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Правый клик по полу (верхняя грань блока под клеткой) — блок встал бы в клетку. */
    private void place(Minecraft mc, BlockPos cell)
    {
        BlockPos floor = TARGET.offset(cell).below();
        var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        CovOut.log("[strict] клик " + cell.toShortString() + " с " + mc.player.getMainHandItem() + " → " + result);
    }

    /** Опции там, где идёт проверка: Easy Place Fix+, если он стоит, иначе Material HUD. */
    private static void setOptions(boolean empty, boolean block)
    {
        LayerListConfigs.EP_STRICT_SCHEMATIC.setBooleanValue(empty);
        LayerListConfigs.EP_STRICT_BLOCK.setBooleanValue(block);

        try
        {
            Class<?> configs = Class.forName("com.aell.easyplacefixplus.config.FixConfigs");

            for (String[] f : new String[][]{{"EP_STRICT_SCHEMATIC", String.valueOf(empty)}, {"EP_STRICT_BLOCK", String.valueOf(block)}})
            {
                Object config = configs.getField(f[0]).get(null);
                config.getClass().getMethod("setBooleanValue", boolean.class).invoke(config, Boolean.parseBoolean(f[1]));
            }
        }
        catch (ReflectiveOperationException | LinkageError ignored)
        {
            // Easy Place Fix+ нет — проверяет Material HUD
        }
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

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[strict] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[strict] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        setOptions(false, false);
        fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(this.easyPlaceWas);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
