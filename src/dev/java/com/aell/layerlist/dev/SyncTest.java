package com.aell.layerlist.dev;

import com.aell.layerlist.compat.Syncmatica;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.GuiUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Режим {@code syncmatica}: Syncmatica во вкладках (запрос 17.10.2026). Нужен Syncmatica в {@code run/mods}; одиночная игра —
 * встроенный сервер Syncmatica.
 * <ol>
 *   <li>«Поделиться» в «Размещениях» — схема на сервере, у строки метка «сервер» (снимок);</li>
 *   <li>«Сервер (N)» — список сервера (снимок); «Скрыть у себя» и «Загрузить» обратно;</li>
 *   <li>«Размещение» на общем: запрос серверу, редактор после разрешения, «Готово» — правка ушла, снова заперто;</li>
 *   <li>«Убрать с сервера» — список пуст;</li>
 *   <li>«Разместить и поделиться» в «Библиотеке» — после «Готово» схема на сервере.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class SyncTest implements CovJob
{
    private int stage;
    private long nextAt;
    private long startedAt;
    private boolean failed;
    private Path file;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "SYNC (Syncmatica во вкладках)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (! Syncmatica.installed())
        {
            CovOut.log("[sync] нет Syncmatica в run/mods");
            return false;
        }

        this.file = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("_k").resolve("k-generated.litematic")
                .toAbsolutePath().normalize();
        clearPlacements();
        this.startedAt = Util.getMillis();
        this.nextAt = this.startedAt + 1500L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.nextAt)
        {
            return false;
        }

        long wait = 2500L;

        try
        {
            switch (this.stage)
            {
                case 0 ->
                {
                    if (! Syncmatica.active() && Util.getMillis() - this.startedAt < 20000L)
                    {
                        this.nextAt = Util.getMillis() + 500L;
                        return false;
                    }

                    this.check("Syncmatica подключена", Syncmatica.active(), "");
                    LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(this.file);
                    this.placement = SchematicPlacement.createFor(schematic, mc.player.blockPosition().offset(6, 0, 6), "sync-test", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                    GuiSchematics.openPlacements(null);
                    wait = 1500L;
                }
                case 1 ->
                {
                    Object pane = this.pane();
                    @SuppressWarnings("unchecked")
                    com.aell.layerlist.ui.kit.Table<SchematicPlacement> table =
                            (com.aell.layerlist.ui.kit.Table<SchematicPlacement>) field(pane, "table");
                    invoke(pane, "reload");
                    table.setSelected(this.placement);
                    invoke(pane, "shareOrUnshare");
                    wait = 3000L;
                }
                case 2 ->
                {
                    List<Syncmatica.Remote> remote = Syncmatica.remote();
                    this.check("на сервере одна схема", remote.size() == 1, String.valueOf(remote.size()));
                    SchematicPlacement local = onlyPlacement();
                    this.check("размещение общее", Syncmatica.shared(local), String.valueOf(local));
                    this.placement = local;
                    @SuppressWarnings("unchecked")
                    com.aell.layerlist.ui.kit.Table<SchematicPlacement> table =
                            (com.aell.layerlist.ui.kit.Table<SchematicPlacement>) field(this.pane(), "table");
                    table.setSelected(local);
                    shot(mc, "размещения — метка «сервер»");
                    invoke(this.pane(), "toggleServerView");
                    wait = 1500L;
                }
                case 3 ->
                {
                    shot(mc, "размещения — список сервера");
                    Syncmatica.hide(this.placement);
                    wait = 1500L;
                }
                case 4 ->
                {
                    List<Syncmatica.Remote> remote = Syncmatica.remote();
                    this.check("«Скрыть у себя»: на сервере осталась, у себя нет",
                            remote.size() == 1 && ! remote.getFirst().rendered() && allPlacements().isEmpty(),
                            remote + " / " + allPlacements().size());
                    Syncmatica.load(remote.getFirst());
                    wait = 2000L;
                }
                case 5 ->
                {
                    List<Syncmatica.Remote> remote = Syncmatica.remote();
                    this.check("«Загрузить»: снова у себя", remote.size() == 1 && remote.getFirst().rendered() && allPlacements().size() == 1,
                            remote.toString());
                    this.placement = onlyPlacement();
                    this.check("общее заперто", this.placement.isLocked(), "");
                    invoke(this.pane(), "toggleServerView");
                    Method edit = GuiSchematics.class.getDeclaredMethod("edit", SchematicPlacement.class, boolean.class, boolean.class);
                    edit.setAccessible(true);
                    edit.invoke(GuiUtils.getCurrentScreen(), this.placement, false, false);
                    wait = 3000L;
                }
                case 6 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    this.check("редактор открыт после разрешения сервера", screen instanceof PlacementEditorScreen, String.valueOf(screen));
                    this.check("на время правки отперто", ! this.placement.isLocked(), "");

                    if (screen instanceof PlacementEditorScreen editor)
                    {
                        int[] b = PlacementEditorScreen.TestHooks.footerButton(editor, true);
                        SupportTest.click(mc, b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
                    }

                    wait = 2000L;
                }
                case 7 ->
                {
                    this.check("после «Готово» снова заперто", this.placement.isLocked(), "");
                    this.check("вернулись во вкладку", GuiUtils.getCurrentScreen() instanceof GuiSchematics,
                            String.valueOf(GuiUtils.getCurrentScreen()));
                    Syncmatica.remove(Syncmatica.remote().getFirst());
                    wait = 2500L;
                }
                case 8 ->
                {
                    this.check("«Убрать с сервера»: пусто", Syncmatica.remote().isEmpty(), Syncmatica.remote().toString());
                    clearPlacements();
                    GuiSchematics.openLibrary(null);
                    wait = 1500L;
                }
                case 9 ->
                {
                    SchematicEntry entry = SchematicLibrary.get().entry(this.file);
                    Method select = GuiSchematics.class.getDeclaredMethod("select", SchematicEntry.class);
                    select.setAccessible(true);
                    select.invoke(GuiUtils.getCurrentScreen(), entry);
                    wait = 2500L;
                }
                case 10 ->
                {
                    shot(mc, "библиотека — «Разместить и поделиться»");
                    Method place = GuiSchematics.class.getDeclaredMethod("placeAndShare", SchematicEntry.class);
                    place.setAccessible(true);
                    place.invoke(GuiUtils.getCurrentScreen(), SchematicLibrary.get().entry(this.file));
                    wait = 3000L;
                }
                case 11 ->
                {
                    Screen screen = GuiUtils.getCurrentScreen();
                    this.check("«Разместить и поделиться» открыл редактор", screen instanceof PlacementEditorScreen, String.valueOf(screen));

                    if (screen instanceof PlacementEditorScreen editor)
                    {
                        int[] b = PlacementEditorScreen.TestHooks.footerButton(editor, true);
                        SupportTest.click(mc, b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
                    }

                    wait = 3500L;
                }
                case 12 ->
                {
                    List<Syncmatica.Remote> remote = Syncmatica.remote();
                    this.check("после «Готово» схема на сервере", remote.size() == 1, remote.toString());

                    for (Syncmatica.Remote r : remote)
                    {
                        Syncmatica.remove(r);
                    }

                    mc.setScreen(null);
                    wait = 1500L;
                }
                default ->
                {
                    CovOut.log("[sync] " + (this.failed ? "ЕСТЬ ОШИБКИ" : "всё прошло"));
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.check("сбой на шаге " + this.stage, false, e.toString());
            return true;
        }

        ++this.stage;
        this.nextAt = Util.getMillis() + wait;
        return false;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    private Object pane() throws ReflectiveOperationException
    {
        return field(GuiUtils.getCurrentScreen(), "placements");
    }

    private static void invoke(Object owner, String name) throws ReflectiveOperationException
    {
        Method m = owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(owner);
    }

    private static List<SchematicPlacement> allPlacements()
    {
        return new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
    }

    private static SchematicPlacement onlyPlacement()
    {
        List<SchematicPlacement> all = allPlacements();
        return all.isEmpty() ? null : all.getFirst();
    }

    private static void clearPlacements()
    {
        for (SchematicPlacement old : allPlacements())
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Class<?> c = owner.getClass();

        while (c != null)
        {
            try
            {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
        }

        throw new NoSuchFieldException(name);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[sync] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[sync] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
        for (Syncmatica.Remote r : Syncmatica.remote())
        {
            Syncmatica.remove(r);
        }

        clearPlacements();
    }
}
