package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code light}: невидимый блок света ({@code minecraft:light}) через Easy Place (жалоба 24.10.2026: «блок
 * невидимого света изиплейсом не ставится»). Схема: свет 15 и 0 на полу, свет 7 в воздухе на высоте 2, камень —
 * контроль, свет 3 на высоте 1. Круг 1 — выживание, в руке камень, свет 15/7/0 в хотбаре, света 3 нет; круг 2 —
 * творческий, руки пусты. Ожидание: каждая клетка встаёт с уровнем как в схеме; свет 3 в выживании не ставится
 * вовсе (а не уровнем 15); в списке HUD уровни — отдельными строками «Свет N». С jar EPF+ в {@code run/mods}
 * тот же стенд проверяет движок EPF+ (Easy Place Material HUD тогда выключен).
 */
@Environment(EnvType.CLIENT)
public final class LightTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(820, 100, 0);
    private static final BlockPos TARGET = new BlockPos(820, 100, 14);
    private static final BlockPos[] CELLS = {
            new BlockPos(1, 0, 3), new BlockPos(3, 2, 3), new BlockPos(5, 0, 3), new BlockPos(7, 0, 3),
            new BlockPos(6, 1, 3) };
    private static final String[] STATES = {
            "minecraft:light[level=15]", "minecraft:light[level=7]", "minecraft:light[level=0]", "minecraft:stone",
            "minecraft:light[level=3]" };
    /** Света уровня 3 у игрока в выживании нет: клетка должна остаться пустой, а не получить чужой уровень. */
    private static final int ABSENT = 4;
    private static final long PLACE_MS = 6000L;

    private int stage;
    private int cell;
    private int round;
    private long nextAt;
    private long placeDeadline;
    private long lastTick = Long.MIN_VALUE;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "light (невидимый блок света через Easy Place)";
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

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 810 -10 840 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 8, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 5, 8, "minecraft:air"));
            }

            for (int i = 0; i < CELLS.length; ++i)
            {
                command(server, set(REF, CELLS[i], STATES[i]));
            }

            command(server, "tp @a " + (REF.getX() + 4.5D) + " " + REF.getY() + " " + (REF.getZ() + 8.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
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
                AreaSelection area = new AreaSelection();
                area.setName("dev-light");
                area.addSubRegionBox(new Box(REF, REF.offset(8, 3, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-light", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                LayerListConfigs.EP_DEBUG.setBooleanValue(true);
                epfOption("EP_ENABLED", true);
                epfOption("EP_DEBUG", true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                this.prepareRound(server);
                wait = 4000L;
            }
            case 1 ->
            {
                if (this.cell == 0 && this.placeDeadline == 0L)
                {
                    shot(mc, "light-r" + this.round + "-before");
                    this.logSchematic();
                    this.checkList();
                }

                BlockPos pos = TARGET.offset(CELLS[this.cell]);
                BlockState want = schematicState(pos);
                BlockState have = mc.level.getBlockState(pos);
                boolean done = want != null && have.equals(want);

                if (this.placeDeadline == 0L)
                {
                    this.placeDeadline = now + PLACE_MS;
                    CovOut.log("[light] круг " + this.round + ": клетка " + STATES[this.cell] + " — в схеме " + want);
                    if (want != null)
                    {
                        net.minecraft.world.item.ItemStack cache = fi.dy.masa.litematica.materials.MaterialCache.getInstance()
                                .getRequiredBuildItemForState(want, SchematicWorldHandler.getSchematicWorld(), pos);
                        CovOut.log("[light]   кэш материалов: " + cache + " " + cache.get(net.minecraft.core.component.DataComponents.BLOCK_STATE));
                    }
                }

                if (! done && now < this.placeDeadline)
                {
                    this.easyPlace(mc, pos);
                    return false;
                }

                boolean absent = this.round == 1 && this.cell == ABSENT;

                if (absent)
                {
                    this.check("круг 1 (выживание): света 3 нет — клетка пустая, чужой уровень не встал",
                            have.isAir(), "в мире " + have);
                }
                else this.check("круг " + this.round + " (" + (this.round == 1 ? "выживание" : "творческий") + "): "
                        + STATES[this.cell] + " встал", done, "в мире " + have + ", рука " + mc.player.getMainHandItem() + " "
                        + mc.player.getMainHandItem().get(net.minecraft.core.component.DataComponents.BLOCK_STATE));
                this.placeDeadline = 0L;

                if (++this.cell < CELLS.length)
                {
                    this.nextAt = now + 300L;
                    return false;
                }

                shot(mc, "light-r" + this.round + "-after");
                this.cell = 0;

                if (this.round == 1)
                {
                    this.round = 2;
                    this.prepareRound(server);
                    this.nextAt = now + 3000L;
                    return false;
                }

                wait = 500L;
            }
            default ->
            {
                CovOut.log("[light] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** С jar EPF+ в {@code run/mods} Easy Place ведёт он: его опции — рефлексией (без EPF+ — ничего). */
    private static void epfOption(String field, boolean value)
    {
        try
        {
            Object option = Class.forName("com.aell.easyplacefixplus.config.FixConfigs").getField(field).get(null);
            ((fi.dy.masa.malilib.config.options.ConfigBoolean) option).setBooleanValue(value);
            CovOut.log("[light] EPF+: " + field + " = " + value);
        }
        catch (ReflectiveOperationException | LinkageError | ClassCastException ignored)
        {
        }
    }

    private void prepareRound(MinecraftServer server)
    {
        if (this.round == 0)
        {
            this.round = 1;
        }

        int round = this.round;
        server.execute(() -> {
            command(server, fill(TARGET, -2, 0, -2, 9, 5, 8, "minecraft:air"));
            command(server, "clear @a");

            if (round == 1)
            {
                command(server, "gamemode survival @a");
                command(server, "effect give @a minecraft:saturation 600 1 true");
                command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                command(server, "item replace entity @a hotbar.1 with minecraft:light 4");
                command(server, "item replace entity @a hotbar.2 with minecraft:light[minecraft:block_state={level:\"7\"}] 4");
                command(server, "item replace entity @a hotbar.3 with minecraft:light[minecraft:block_state={level:\"0\"}] 4");
            }
            else
            {
                command(server, "gamemode creative @a");
            }

            command(server, "tp @a " + (TARGET.getX() + 4.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 0.5D) + " 0 20");
        });
    }

    /** Строки HUD: у каждого уровня света своя строка с уровнем в подписи. */
    private void checkList()
    {
        java.util.List<String> labels = new ArrayList<>();

        for (fi.dy.masa.litematica.materials.MaterialListEntry entry
                : com.aell.layerlist.core.LayerCountManager.getInstance().getEntries())
        {
            labels.add(com.aell.layerlist.core.DataItems.label(entry.getStack()) + " ×" + entry.getCountMissing());
        }

        CovOut.log("[light] строки HUD: " + labels);
        long lights = labels.stream().filter(l -> l.matches(".* (0|3|7|15) ×1")).count();
        this.check("HUD: свет 15, 7, 0, 3 — четыре отдельные строки с уровнем", lights == 4, labels.toString());
    }

    private void logSchematic()
    {
        for (BlockPos c : CELLS)
        {
            CovOut.log("[light] схема " + c + " → " + schematicState(TARGET.offset(c)));
        }
    }

    private static BlockState schematicState(BlockPos pos)
    {
        Level schematic = SchematicWorldHandler.getSchematicWorld();
        return schematic != null ? schematic.getBlockState(pos) : null;
    }

    /** Взгляд в середину клетки (у света нет видимой грани) и шаг Easy Place — раз в тик. */
    private void easyPlace(Minecraft mc, BlockPos cell)
    {
        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atCenterOf(cell);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
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
        CovOut.log("[light] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[light] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[light] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        LayerListConfigs.EP_DEBUG.setBooleanValue(false);
        epfOption("EP_DEBUG", false);
    }
}
