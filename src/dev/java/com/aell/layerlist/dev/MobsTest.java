package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.GhostMobs;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code mobs}: опция «Мобы в голограммах» (просьба 13.11.2026: «по умолчанию не отображать в схеме мобов,
 * добавить опцию и рядом курицу в 3D»). Схема: курица и стойка для брони. По умолчанию опция выключена — в кадре
 * Litematica стойка есть, курицы нет; включили — есть обе; выключили — снова без курицы. Курица — по счётчикам
 * {@link GhostMobs} за 1,5 с (список кадра Litematica к концу кадра пуст), стойка — на снимках. В конце — снимок
 * строки настроек с курицей.
 */
@Environment(EnvType.CLIENT)
public final class MobsTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(940, 100, 0);
    private static final BlockPos TARGET = new BlockPos(940, 100, 14);
    private static final long SAMPLE_MS = 1500L;

    private int stage;
    private long nextAt;
    private long sampleUntil;
    /** За замер: курица показана (раз) / спрятана (раз). */
    private int chickens;
    private int stands;
    private long shownAt;
    private long hiddenAt;
    private boolean failed;
    private boolean showWas;
    private boolean entitiesWas;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "mobs (мобы в голограммах)";
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
        this.showWas = LayerListConfigs.GHOST_SHOW_MOBS.getBooleanValue();
        this.check("по умолчанию выключена", ! LayerListConfigs.GHOST_SHOW_MOBS.getDefaultBooleanValue(), "");
        this.check("опция в списке настроек", LayerListConfigs.OPTIONS.contains(LayerListConfigs.GHOST_SHOW_MOBS), "");
        LayerListConfigs.GHOST_SHOW_MOBS.resetToDefault();
        // Сущности схемы Litematica рисует только с этой своей опцией (у игроков с мобами в голограмме она включена)
        this.entitiesWas = fi.dy.masa.litematica.config.Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.getBooleanValue();
        fi.dy.masa.litematica.config.Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 930 -10 955 30");
            command(server, String.format(Locale.ROOT, "kill @e[type=!player,x=%d,y=%d,z=%d,dx=12,dy=6,dz=24]",
                    REF.getX() - 2, REF.getY() - 1, REF.getZ() - 2));

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 8, -1, 6, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 8, 4, 6, "minecraft:air"));
            }

            // Блоки в обоих чанках схемы: Litematica перебирает сущности по своим отрисованным чанкам, а чанк
            // без единого блока схемы не отрисовывается — без них голограмм сущностей нет вовсе
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", REF.getX(), REF.getY(), REF.getZ() + 1));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:gold_block", REF.getX() + 7, REF.getY(), REF.getZ() + 1));
            command(server, String.format(Locale.ROOT, "summon minecraft:chicken %.1f %d %.1f {NoAI:1b,Silent:1b,Rotation:[180f,0f]}",
                    REF.getX() + 2.5D, REF.getY(), REF.getZ() + 1.5D));
            command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {Rotation:[180f,0f]}",
                    REF.getX() + 5.5D, REF.getY(), REF.getZ() + 1.5D));
            command(server, "tp @a " + (TARGET.getX() + 4.0D) + " " + (TARGET.getY() + 1) + " " + (TARGET.getZ() + 7.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        long now = Util.getMillis();
        return now < this.nextAt && now >= this.sampleUntil;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (mc.player == null || mc.level == null)
        {
            return false;
        }

        // Выборка кадра Litematica: между шагами — каждый тик
        if (now < this.sampleUntil)
        {
            this.sample();
            return false;
        }

        if (now < this.nextAt)
        {
            return false;
        }

        long wait = 1000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-mobs");
                area.addSubRegionBox(new Box(REF, REF.offset(7, 2, 3), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-mobs", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
                MinecraftServer server = mc.getSingleplayerServer();
                server.execute(() -> command(server, "tp @a " + (TARGET.getX() + 4.0D) + " " + (TARGET.getY() + 1)
                        + " " + (TARGET.getZ() + 7.5D)));
                wait = 4000L;
            }
            case 1 ->
            {
                this.startSample(mc, now);
                return false;
            }
            case 2 ->
            {
                this.report("выкл. (по умолчанию)", false);
                shot(mc, "mobs-off");
                LayerListConfigs.GHOST_SHOW_MOBS.setBooleanValue(true);
                wait = 500L;
            }
            case 3 ->
            {
                this.startSample(mc, now);
                return false;
            }
            case 4 ->
            {
                this.report("вкл.", true);
                shot(mc, "mobs-on");
                LayerListConfigs.GHOST_SHOW_MOBS.setBooleanValue(false);
                wait = 500L;
            }
            case 5 ->
            {
                this.startSample(mc, now);
                return false;
            }
            case 6 ->
            {
                this.report("снова выкл.", false);
                var screen = com.aell.layerlist.config.GuiConfigs.create(null, null);
                mc.gui.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                search(screen, "Мобы");
                wait = 2500L;
            }
            case 7 ->
            {
                shot(mc, "mobs-settings");
                wait = 500L;
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log("[mobs] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private void startSample(Minecraft mc, long now)
    {
        look(mc);
        this.chickens = 0;
        this.stands = 0;
        this.shownAt = GhostMobs.shown;
        this.hiddenAt = GhostMobs.hidden;
        this.sampleUntil = now + SAMPLE_MS;
        this.nextAt = this.sampleUntil;
        ++this.stage;
    }

    private void report(String what, boolean wantChicken)
    {
        var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        if (world != null)
        {
            var near = world.getEntities((net.minecraft.world.entity.Entity) null,
                    new net.minecraft.world.phys.AABB(TARGET).inflate(12.0D), e -> true);
            CovOut.log("[mobs] в мире схемы у размещения сущностей: " + near.size() + " " + near);
        }

        CovOut.log("[mobs] " + what + ": курица показана " + this.chickens + " раз, спрятана " + this.stands + " раз");
        this.check(what + (wantChicken ? ": курица в голограмме" : ": курицы в голограмме нет"),
                wantChicken ? this.chickens > 0 && this.stands == 0 : this.chickens == 0 && this.stands > 0,
                "показана " + this.chickens + ", спрятана " + this.stands);
    }

    /** Сколько раз за замер Litematica спросила про моба схемы: спрятан / показан (счётчики {@link GhostMobs}). */
    private void sample()
    {
        this.chickens = (int) Math.min(Integer.MAX_VALUE, GhostMobs.shown - this.shownAt);
        this.stands = (int) Math.min(Integer.MAX_VALUE, GhostMobs.hidden - this.hiddenAt);
    }

    private static void look(Minecraft mc)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = new Vec3(TARGET.getX() + 4.0D, TARGET.getY() + 0.5D, TARGET.getZ() + 1.5D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** Строка поиска настроек — как в {@link SettingsShot}. */
    private static void search(Object screen, String query)
    {
        try
        {
            Class<?> type = com.aell.layerlist.shaded.ui.SettingsScreen.class;
            var field = type.getDeclaredField("search");
            field.setAccessible(true);
            field.set(screen, query);
            var layout = type.getDeclaredMethod("layout");
            layout.setAccessible(true);
            layout.invoke(screen);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[mobs] поиск не задан: " + e);
        }
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[mobs] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[mobs] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[mobs] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.GHOST_SHOW_MOBS.setBooleanValue(this.showWas);
        fi.dy.masa.litematica.config.Configs.Visuals.RENDER_SCHEMATIC_ENTITIES.setBooleanValue(this.entitiesWas);
    }
}
