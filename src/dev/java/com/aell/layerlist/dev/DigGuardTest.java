package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
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

/**
 * Режим {@code dig}: «Не копать лишнего» и три его части (13.10.2026). Схема 5×2×5 снимается с мира: камень в A и A2,
 * угловая ступень в S (запись {@code outer_left} спиной на север), одинокий забор в F, пусто в W. На площадке: камень
 * в A (как в схеме), в S — та же ступень второй записью ({@code outer_right} спиной на запад), в F — забор с
 * соединением, в A2 — земля (не тот блок), в W — земля (лишнее), OUT — земля за границей схемы.
 * Проходы сверяют {@code DigGuard.blocks} при разных переключателях и режиме Easy Place, последний — настоящими
 * ударами ({@code startDestroyBlock}, творческий режим).
 * С Easy Place Fix+ в {@code run/mods} проверяется его копия защиты, без него — Material HUD.
 */
@Environment(EnvType.CLIENT)
public final class DigGuardTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(680, 100, 0);
    private static final BlockPos TARGET = new BlockPos(680, 100, 14);
    private static final BlockPos A = new BlockPos(1, 0, 2);
    private static final BlockPos A2 = new BlockPos(0, 0, 2);
    private static final BlockPos S = new BlockPos(3, 0, 2);
    private static final BlockPos F = new BlockPos(2, 0, 3);
    private static final BlockPos W = new BlockPos(2, 0, 1);
    private static final BlockPos OUT = new BlockPos(5, 0, 2);
    private static final BlockPos[] CELLS = {A, S, F, A2, W, OUT};
    private static final String[] NAMES = {"камень как в схеме", "ступень второй записью", "забор с соединением",
            "не тот блок", "лишний блок", "за границей схемы"};

    /** Проход: «вне схемы», «правильно стоящие», «только в Easy Place», режим Easy Place; чего ждём по CELLS. */
    private record Pass(String name, boolean outside, boolean correct, boolean epOnly, boolean easyPlace, boolean[] expect) {}

    private static final Pass[] PASSES = {
            new Pass("всё включено", true, true, true, true, new boolean[]{true, true, true, false, false, true}),
            new Pass("«вне схемы» выкл", false, true, true, true, new boolean[]{true, true, true, false, false, false}),
            new Pass("«правильно стоящие» выкл", true, false, true, true, new boolean[]{false, false, false, false, false, true}),
            new Pass("Easy Place выкл, «только в Easy Place» вкл", true, true, true, false, new boolean[6]),
            new Pass("Easy Place выкл, «только в Easy Place» выкл", true, true, false, false,
                    new boolean[]{true, true, true, false, false, true}),
    };

    private int stage;
    private long nextAt;
    private boolean failed;
    private int waits;
    private SchematicPlacement placement;
    private boolean easyPlaceWas;

    @Override
    public String name()
    {
        return "DIG («Не копать лишнего» и его части)";
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
        this.easyPlaceWas = Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        CovOut.log("[dig] проверяет " + (standalone() ? "Easy Place Fix+" : "Material HUD"));

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 670 -10 695 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 9, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 4, 9, "minecraft:air"));
            }

            command(server, set(REF, A, "minecraft:stone"));
            command(server, set(REF, A2, "minecraft:stone"));
            command(server, set(REF, S, "minecraft:oak_stairs[facing=north,shape=outer_left] strict"));
            command(server, set(REF, F, "minecraft:oak_fence strict"));
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
                CovOut.log("[dig] эталон: S = " + mc.level.getBlockState(REF.offset(S)) + ", F = " + mc.level.getBlockState(REF.offset(F)));
                AreaSelection area = new AreaSelection();
                area.setName("dev-dig");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 1, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-dig", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                server.execute(() -> {
                    command(server, set(TARGET, A, "minecraft:stone"));
                    command(server, set(TARGET, A2, "minecraft:dirt"));
                    command(server, set(TARGET, W, "minecraft:dirt"));
                    command(server, set(TARGET, OUT, "minecraft:dirt"));
                    command(server, set(TARGET, S, "minecraft:oak_stairs[facing=west,shape=outer_right] strict"));
                    command(server, set(TARGET, F, "minecraft:oak_fence[east=true] strict"));
                    command(server, "tp @a " + (TARGET.getX() + 2.5D) + " " + (TARGET.getY() + 2) + " " + (TARGET.getZ() + 7.5D) + " 180 40");
                });
                wait = 3000L;
            }
            case 1 ->
            {
                var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                if ((world == null || world.getBlockState(TARGET.offset(A)).isAir()) && ++this.waits < 40)
                {
                    this.nextAt = now + 250L;
                    return false;
                }

                CovOut.log("[dig] схема в S: " + (world == null ? "нет" : world.getBlockState(TARGET.offset(S)))
                        + ", мир в S: " + mc.level.getBlockState(TARGET.offset(S))
                        + "; схема в F: " + (world == null ? "нет" : world.getBlockState(TARGET.offset(F)))
                        + ", мир в F: " + mc.level.getBlockState(TARGET.offset(F)));

                for (Pass p : PASSES)
                {
                    setOptions(true, p.outside(), p.correct(), p.epOnly());
                    Configs.Generic.EASY_PLACE_MODE.setBooleanValue(p.easyPlace());

                    for (int i = 0; i < CELLS.length; ++i)
                    {
                        boolean got = blocks(TARGET.offset(CELLS[i]));
                        this.check(p.name() + ": " + NAMES[i] + (p.expect()[i] ? " — защищён" : " — копается"),
                                got == p.expect()[i], "");
                    }
                }

                // «Не копать лишнего» выключено — ничего не защищено
                setOptions(false, true, true, true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                this.check("защита выкл: камень как в схеме — копается", ! blocks(TARGET.offset(A)), "");
                this.check("защита выкл: за границей — копается", ! blocks(TARGET.offset(OUT)), "");

                // Настоящие удары: всё включено
                setOptions(true, true, true, true);

                for (BlockPos c : new BlockPos[]{A, S, A2, OUT})
                {
                    BlockPos pos = TARGET.offset(c);
                    boolean sent = mc.gameMode.startDestroyBlock(pos, Direction.UP);
                    CovOut.log("[dig] удар " + c.toShortString() + " → " + sent);
                }

                wait = 1500L;
            }
            case 2 ->
            {
                this.check("удар: камень как в схеме устоял", ! mc.level.getBlockState(TARGET.offset(A)).isAir(),
                        mc.level.getBlockState(TARGET.offset(A)).toString());
                this.check("удар: ступень второй записью устояла", ! mc.level.getBlockState(TARGET.offset(S)).isAir(),
                        mc.level.getBlockState(TARGET.offset(S)).toString());
                this.check("удар: за границей устоял", ! mc.level.getBlockState(TARGET.offset(OUT)).isAir(),
                        mc.level.getBlockState(TARGET.offset(OUT)).toString());
                this.check("удар: не тот блок сломан", mc.level.getBlockState(TARGET.offset(A2)).isAir(),
                        mc.level.getBlockState(TARGET.offset(A2)).toString());
                wait = 300L;
            }
            default ->
            {
                CovOut.log("[dig] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private static boolean standalone()
    {
        return FabricLoader.getInstance().isModLoaded("easyplacefixplus");
    }

    /** Защита того мода, что ведёт Easy Place. */
    private static boolean blocks(BlockPos pos)
    {
        if (standalone())
        {
            try
            {
                return (boolean) Class.forName("com.aell.easyplacefixplus.core.DigGuard")
                        .getMethod("blocks", BlockPos.class).invoke(null, pos);
            }
            catch (ReflectiveOperationException | LinkageError e)
            {
                CovOut.log("[dig] Easy Place Fix+ недоступен: " + e);
                return false;
            }
        }

        return com.aell.layerlist.easyplace.DigGuard.blocks(pos);
    }

    private static void setOptions(boolean on, boolean outside, boolean correct, boolean epOnly)
    {
        LayerListConfigs.EP_DIG_GUARD.setBooleanValue(on);
        LayerListConfigs.EP_DIG_GUARD_OUTSIDE.setBooleanValue(outside);
        LayerListConfigs.EP_DIG_GUARD_CORRECT.setBooleanValue(correct);
        LayerListConfigs.EP_DIG_GUARD_EP_ONLY.setBooleanValue(epOnly);

        try
        {
            Class<?> configs = Class.forName("com.aell.easyplacefixplus.config.FixConfigs");
            String[] names = {"EP_DIG_GUARD", "EP_DIG_GUARD_OUTSIDE", "EP_DIG_GUARD_CORRECT", "EP_DIG_GUARD_EP_ONLY"};
            boolean[] values = {on, outside, correct, epOnly};

            for (int i = 0; i < names.length; ++i)
            {
                Object config = configs.getField(names[i]).get(null);
                config.getClass().getMethod("setBooleanValue", boolean.class).invoke(config, values[i]);
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
        CovOut.log("[dig] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[dig] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        setOptions(false, true, true, true);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(this.easyPlaceWas);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
