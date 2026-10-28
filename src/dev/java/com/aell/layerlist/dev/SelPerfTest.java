package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.shaded.ui.Nav;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Режим {@code selperf}: замер 3D-превью вкладки «Выделение» на настоящей местности ({@code LL_COVERAGE_TERRAIN=1}) при
 * дальности 32. Ждёт, пока прогрузятся чанки; открывает вкладку; засекает снимок области и сборку модели; кадры в
 * секунду в покое и при вращении превью (перетаскивание ЛКМ). Снимки: превью в покое и во время вращения.
 * Профиль — снаружи: {@code jcmd <pid> JFR.start}, метки «[selperf] mark …» в журнале.
 */
@Environment(EnvType.CLIENT)
public final class SelPerfTest implements CovJob
{
    private static final int RADIUS = 32;
    private static final long CHUNKS_TIMEOUT_MS = 420_000L;
    private static final long MODEL_TIMEOUT_MS = 240_000L;
    private static final long MEASURE_MS = 8_000L;

    private int stage;
    private long nextAt;
    private long since;
    private int lastChunks = -1;
    private long chunksStableSince;
    private long openedAt;
    private long capturedAt;
    private final List<Integer> fps = new ArrayList<>();
    private long lastFpsAt;
    private int dragX;
    private boolean cpuPass;
    /** Превью рисует Voxy: сравнения со старым путём нет, только кадры и снимки. */
    private boolean voxy;
    private BlockPos[] before = new BlockPos[2];

    @Override
    public String name()
    {
        return "SELPERF (3D-превью «Выделения»: дальность 32, рельеф)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        this.nextAt = Util.getMillis() + 1000L;
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
        if (Util.getMillis() < this.nextAt)
        {
            return false;
        }

        try
        {
            return this.run(mc);
        }
        catch (RuntimeException | LinkageError | ReflectiveOperationException e)
        {
            CovOut.log("[selperf] сбой: " + e);
            mc.gui.setScreen(null);
            return true;
        }
    }

    private boolean run(Minecraft mc) throws ReflectiveOperationException
    {
        long now = Util.getMillis();

        switch (this.stage)
        {
            case 0 ->
            {
                mc.options.renderDistance().set(RADIUS);
                // Серверу — новые настройки клиента: чанки шлются по меньшей из дальностей сервера и клиента
                mc.options.broadcastOptions();
                mc.player.getAbilities().flying = true;
                BlockPos at = mc.player.blockPosition();
                int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
                mc.player.setPos(at.getX() + 0.5, top + 24, at.getZ() + 0.5);
                this.since = now;
                this.stage = 1;
                CovOut.log("[selperf] дальность " + RADIUS + ", жду чанки");
            }
            case 1 ->
            {
                int loaded = mc.level.getChunkSource().getLoadedChunksCount();

                if (loaded != this.lastChunks)
                {
                    this.lastChunks = loaded;
                    this.chunksStableSince = now;
                }

                if (now - this.lastFpsAt >= 15_000L)
                {
                    this.lastFpsAt = now;
                    CovOut.log("[selperf] чанков " + loaded);
                }

                // Готово: почти весь квадрат или 15 с без новых
                int want = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);

                if (loaded >= want * 95 / 100 || (loaded > 2000 && now - this.chunksStableSince > 30_000L) || now - this.since > CHUNKS_TIMEOUT_MS)
                {
                    CovOut.log("[selperf] чанков загружено " + loaded + " из " + want + " за " + (now - this.since) / 1000 + " с");
                    this.prepare(mc);
                    this.openedAt = now;
                    CovOut.log("[selperf] mark open");
                    GuiSchematics.openSelection(null);
                    this.stage = 2;
                }
            }
            case 2 ->
            {
                Object pane = field(mc.gui.screen(), "selection");
                PreviewWidget preview = (PreviewWidget) field(pane, "preview");

                if (this.capturedAt == 0L && field(pane, "area") != null)
                {
                    this.capturedAt = now;
                    CovOut.log("[selperf] снимок области: " + (now - this.openedAt) + " мс, масштаб клетки " + preview.sourceScale());
                }

                boolean voxy = (boolean) field(pane, "voxyShown");

                if (voxy)
                {
                    CovOut.log("[selperf] рисует Voxy: " + (now - this.openedAt) + " мс от открытия; статус: " + preview.status());
                    CovOut.log("[selperf] mark idle");
                    this.voxy = true;
                    this.since = now;
                    this.lastFpsAt = now;
                    this.fps.clear();
                    this.stage = 3;
                }
                else if (preview.hasModel() && preview.progress() < 0.0F)
                {
                    Object model = field(preview, "model");
                    CovOut.log("[selperf] модель готова: " + (now - this.openedAt) + " мс от открытия; квадов "
                            + model.getClass().getMethod("quads").invoke(model) + ", упрощение ×" + model.getClass().getMethod("lodFactor").invoke(model)
                            + "; статус: " + preview.status());
                    CovOut.log("[selperf] mark idle");
                    this.since = now;
                    this.lastFpsAt = now;
                    this.fps.clear();
                    this.stage = 3;
                }
                else if (now - this.openedAt > MODEL_TIMEOUT_MS)
                {
                    CovOut.log("[selperf] модель не готова за " + MODEL_TIMEOUT_MS / 1000 + " с; статус: " + preview.status());
                    this.stage = 5;
                }
            }
            case 3 ->
            {
                this.sampleFps(mc, now);

                if (now - this.since > MEASURE_MS)
                {
                    CovOut.log("[selperf] покой, FPS по секундам: " + this.fps);
                    Screenshot.grab(mc, false);
                    Object pane = field(mc.gui.screen(), "selection");
                    PreviewWidget preview = (PreviewWidget) field(pane, "preview");
                    int[] b = preview.bounds();
                    this.dragX = b[0] + b[2] / 2;
                    preview.mouseClicked(this.dragX, b[1] + b[3] / 2.0, InputConstants.MOUSE_BUTTON_LEFT);
                    CovOut.log("[selperf] mark rotate");
                    redrawStats();
                    this.since = now;
                    this.lastFpsAt = now;
                    this.fps.clear();
                    this.stage = 4;
                }
            }
            case 4 ->
            {
                Object pane = field(mc.gui.screen(), "selection");
                PreviewWidget preview = (PreviewWidget) field(pane, "preview");
                int[] b = preview.bounds();
                this.dragX += 6;
                preview.mouseDragged(this.dragX, b[1] + b[3] / 2.0, InputConstants.MOUSE_BUTTON_LEFT, 6.0, 0.0);
                this.sampleFps(mc, now);

                if (now - this.since > MEASURE_MS)
                {
                    boolean cpu = this.cpuPass;
                    long[] stats = redrawStats();
                    CovOut.log(String.format("[selperf] вращение (%s), FPS по секундам: %s; перерисовок %d, клиентского потока на одну %.2f мс",
                            cpu ? "по-старому, вершины каждый кадр" : "из видеопамяти", this.fps, stats[0],
                            stats[0] == 0 ? 0.0 : stats[1] / 1e6 / stats[0]));

                    if (! cpu && ! this.voxy)
                    {
                        Screenshot.grab(mc, false);
                        // Тот же поворот старым путём — для сравнения на этом же стенде
                        setForceCpu(true);
                        this.cpuPass = true;
                        redrawStats();
                        this.since = now;
                        this.lastFpsAt = now;
                        this.fps.clear();
                        CovOut.log("[selperf] mark rotate-cpu");
                    }
                    else
                    {
                        if (this.voxy)
                        {
                            Screenshot.grab(mc, false);
                        }

                        setForceCpu(false);
                        preview.mouseReleased(this.dragX, b[1] + b[3] / 2.0, InputConstants.MOUSE_BUTTON_LEFT);
                        CovOut.log("[selperf] mark end");
                        this.stage = 5;
                    }
                }
            }
            case 5 ->
            {
                // Щелчок без сдвига по середине превью — угол встаёт на блок под ним (у Voxy — свой луч)
                Object pane = field(mc.gui.screen(), "selection");
                PreviewWidget preview = (PreviewWidget) field(pane, "preview");
                int[] b = preview.bounds();
                Box box = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox();
                this.before = new BlockPos[] { box.getPos1(), box.getPos2() };
                double cx = b[0] + b[2] / 2.0;
                double cy = b[1] + b[3] / 2.0;
                {
                    Object camera = field(preview, "camera");
                    Object key = camera.getClass().getMethod("key").invoke(camera);
                    StringBuilder picks = new StringBuilder();

                    for (int[] d : new int[][] { { 0, 0 }, { -30, 0 }, { 30, 0 }, { 0, -30 }, { 0, 30 } })
                    {
                        picks.append(java.util.Arrays.toString(preview.pickAt(cx + d[0], cy + d[1]))).append(' ');
                    }

                    Object src = field(preview, "source");
                    CovOut.log("[selperf] щелчок: окно " + java.util.Arrays.toString(b) + ", камера " + key + ", снимок "
                            + (src == null ? "нет" : src.getClass().getMethod("sizeX").invoke(src) + "×" + src.getClass().getMethod("sizeY").invoke(src)
                            + "×" + src.getClass().getMethod("sizeZ").invoke(src)) + ", выбор: " + picks);
                }
                boolean down = preview.mouseClicked(cx, cy, InputConstants.MOUSE_BUTTON_LEFT);
                boolean up = preview.mouseReleased(cx, cy, InputConstants.MOUSE_BUTTON_LEFT);
                CovOut.log("[selperf] щелчок принят: нажатие " + down + ", отпускание " + up);
                this.stage = 6;
            }
            case 6 ->
            {
                Box box = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox();
                BlockPos moved = ! box.getPos1().equals(this.before[0]) ? box.getPos1() : ! box.getPos2().equals(this.before[1]) ? box.getPos2() : null;
                BlockPos at = mc.player.blockPosition();
                boolean near = moved != null && Math.abs(moved.getX() - at.getX()) < 96 && Math.abs(moved.getZ() - at.getZ()) < 96;
                CovOut.log("[selperf] " + (near ? "OK   " : "FAIL ") + "щелчок по середине превью" + (this.voxy ? " (луч Voxy)" : "")
                        + ": угол → " + (moved == null ? "не сдвинулся" : moved.toShortString()) + ", игрок " + at.toShortString());
                this.stage = 7;
            }
            default ->
            {
                mc.gui.setScreen(null);
                Nav.clear();
                CovOut.log("[selperf] итог: OK");
                return true;
            }
        }

        // Не крутиться вхолостую: харнесс зовёт шаг, пока waiting() ложно, — это отнимало кадры у замера
        if (this.nextAt <= now)
        {
            this.nextAt = now + (this.stage == 4 ? 40L : 100L);
        }

        return false;
    }

    private void sampleFps(Minecraft mc, long now)
    {
        if (now - this.lastFpsAt >= 1000L)
        {
            this.lastFpsAt = now;
            this.fps.add(mc.getFps());
        }
    }

    /** Простое выделение у игрока — рамка в превью, как обычно. */
    private void prepare(Minecraft mc)
    {
        SelectionManager manager = DataManager.getSelectionManager();

        if (manager.getSelectionMode() != SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();
        BlockPos at = mc.player.blockPosition();
        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
        BlockPos a = new BlockPos(at.getX() + 4, top - 4, at.getZ() + 4);
        BlockPos b = new BlockPos(at.getX() + 20, top + 8, at.getZ() + 20);
        Box box = selection.getSelectedSubRegionBox();

        if (box == null)
        {
            selection.createNewSubRegionBox(a, "selperf");
            box = selection.getSelectedSubRegionBox();
        }

        selection.setSubRegionCornerPos(box, Corner.CORNER_1, a);
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, b);
    }

    /** Рендерер PIP превью — класс пакета, снаружи только отражением. */
    private static final String PIP = "com.aell.layerlist.schematics.preview.PreviewPipRenderer";

    private static long[] redrawStats() throws ReflectiveOperationException
    {
        java.lang.reflect.Method m = Class.forName(PIP).getDeclaredMethod("takeRedrawStats");
        m.setAccessible(true);
        return (long[]) m.invoke(null);
    }

    private static void setForceCpu(boolean on) throws ReflectiveOperationException
    {
        Field f = Class.forName(PIP).getDeclaredField("forceCpu");
        f.setAccessible(true);
        f.setBoolean(null, on);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @Override
    public void close()
    {
    }
}
