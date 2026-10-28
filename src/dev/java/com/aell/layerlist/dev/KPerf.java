package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Замеры режима {@code k} на настоящих больших схемах (включаются переменными окружения, без них шагов нет):
 * <ul>
 *   <li>{@code LL_K_PERF=<часть имени файла>} (или {@code max} — самый большой файл в {@code _k}) — вращение
 *       3D-превью мышью ~4 с: время кадра ({@link Minecraft#getFrameTimeNs()}) и самая долгая пауза клиента;</li>
 *   <li>{@code LL_K_PERF_EDIT=<часть имени файла>} — «Разместить здесь» этой схемы, редактор в виде сверху,
 *       перетаскивание ЛКМ ~3 с, отпускание (Litematica перестраивает голограммы), Esc.</li>
 * </ul>
 * Числа пишутся строками {@code [k] INFO замер …}: на Xvfb с программным рендером кадры медленнее настоящих,
 * смысл имеют паузы клиентского потока (наш код на Java) и их сравнение между схемами.
 */
@Environment(EnvType.CLIENT)
final class KPerf
{
    private static final long ROTATE_MS = 4_000L;
    private static final long IDLE_MS = 3_000L;
    private static final long DRAG_MS = 3_000L;
    private static final long AFTER_RELEASE_MS = 4_000L;
    private static final long SETTLE_MS = 3_000L;

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();

    KPerf(DumpSchematics k)
    {
        this.k = k;
    }

    List<DumpSchematics.Step> steps(List<SchematicEntry> entries)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        SchematicEntry rotate = pick(entries, System.getenv("LL_K_PERF"));
        SchematicEntry edit = pick(entries, System.getenv("LL_K_PERF_EDIT"));

        if (rotate != null)
        {
            s.add(new DumpSchematics.Step("замер: выбор " + rotate.fileName(), 5_000L, mc -> this.k.selectEntry(rotate)));
            s.add(new DumpSchematics.Step("замер: модель " + rotate.fileName(), 300_000L,
                    mc -> this.k.previewReadyFor(rotate)));
            s.add(new DumpSchematics.Step("замер: вращение превью", IDLE_MS + ROTATE_MS + 10_000L, new Rotate(rotate)));
        }

        if (edit != null)
        {
            s.add(new DumpSchematics.Step("замер: редактор " + edit.fileName(), 300_000L, new EditorDrag(edit)));
            s.add(new DumpSchematics.Step("замер: назад в «Схемы»", 10_000L,
                    mc -> GuiUtils.getCurrentScreen() instanceof GuiSchematics));
        }

        return s;
    }

    /** Убрать свои размещения, если прогон оборвался посреди замера. */
    void cleanup()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
    }

    private static @Nullable SchematicEntry pick(List<SchematicEntry> entries, @Nullable String want)
    {
        if (want == null || want.isBlank())
        {
            return null;
        }

        if (want.equals("max") || want.equals("1"))
        {
            return entries.stream().max(Comparator.comparingLong(SchematicEntry::size)).orElse(null);
        }

        String lower = want.toLowerCase(Locale.ROOT);

        return entries.stream().filter(e -> e.fileName().toLowerCase(Locale.ROOT).contains(lower)).findFirst().orElse(null);
    }

    /** Время кадра: среднее и худшее по выборкам раз в шаг. */
    private static final class Frames
    {
        private long sumNs;
        private long maxNs;
        private int n;

        void sample(Minecraft mc)
        {
            long ns = mc.getFrameTimeNs();

            if (ns > 0L)
            {
                this.sumNs += ns;
                this.maxNs = Math.max(this.maxNs, ns);
                ++this.n;
            }
        }

        @Override
        public String toString()
        {
            return this.n == 0 ? "кадров нет" : String.format(Locale.ROOT, "кадр в среднем %.1f мс, худший %.1f мс (%d выборок)",
                    this.sumNs / 1.0E6 / this.n, this.maxNs / 1.0E6, this.n);
        }
    }

    /** ЛКМ-перетаскивание по центру превью туда-сюда: каждый шаг — новый ракурс и перерисовка текстуры PIP. */
    private final class Rotate implements DumpSchematics.Action
    {
        private final SchematicEntry entry;
        private final Frames frames = new Frames();
        private final Frames idle = new Frames();
        private long idleStart;
        private int idleFps;
        private long idleGap;
        private long start;
        private double x;
        private double y;
        private double dir = 1.0;
        private double baseX;

        private Rotate(SchematicEntry entry)
        {
            this.entry = entry;
        }

        @Override
        public boolean run(Minecraft mc)
        {
            DumpSchematics k = KPerf.this.k;
            GuiSchematics gui = k.gui();
            long now = Util.getMillis();

            if (gui == null || ! (k.previewPane() instanceof Widget pane))
            {
                k.fail("замер вращения: нет экрана или панели превью");
                return true;
            }

            // Сначала столько же покоя: от чего считать (программный рендер Xvfb медленный сам по себе)
            if (this.idleStart == 0L)
            {
                this.idleStart = now;
                k.resetStepMaxGap();
                return false;
            }

            if (this.start == 0L)
            {
                this.idle.sample(mc);

                if (now - this.idleStart < IDLE_MS)
                {
                    return false;
                }

                this.idleFps = mc.getFps();
                this.idleGap = k.stepMaxGap();
                this.start = now;
                this.baseX = pane.x() + pane.w() / 2.0;
                this.x = this.baseX;
                this.y = pane.y() + pane.h() / 2.0;
                k.resetStepMaxGap();
                gui.onMouseClicked(KProbe.left(this.x, this.y), false);
                return false;
            }

            this.frames.sample(mc);

            if (now - this.start < ROTATE_MS)
            {
                double dx = 12.0 * this.dir;

                if (Math.abs(this.x + dx - this.baseX) > pane.w() / 4.0)
                {
                    this.dir = -this.dir;
                    dx = -dx;
                }

                this.x += dx;
                gui.onMouseDragged(KProbe.left(this.x, this.y), dx, 0.0);
                return false;
            }

            gui.onMouseReleased(KProbe.left(this.x, this.y));
            k.info("замер: превью «" + this.entry.fileName() + "» в покое " + IDLE_MS / 1000 + " с: " + this.idle + ", FPS "
                    + this.idleFps + ", пауза до " + this.idleGap + " мс; вращение " + ROTATE_MS / 1000 + " с: " + this.frames
                    + ", FPS " + mc.getFps() + ", пауза до " + k.stepMaxGap() + " мс");
            return true;
        }
    }

    /** «Разместить здесь» → редактор сверху → перетаскивание → отпускание → Esc, с паузами по фазам. */
    private final class EditorDrag implements DumpSchematics.Action
    {
        private final SchematicEntry entry;
        private final Frames dragFrames = new Frames();
        private final Frames idleFrames = new Frames();
        private int phase;
        private long phaseAt;
        private long loadAt;
        private @Nullable CompletableFuture<LoadedSchematic> load;
        private double x;
        private double y;

        private EditorDrag(SchematicEntry entry)
        {
            this.entry = entry;
        }

        private void next(DumpSchematics k, long now)
        {
            ++this.phase;
            this.phaseAt = now;
            k.resetStepMaxGap();
        }

        @Override
        public boolean run(Minecraft mc)
        {
            DumpSchematics k = KPerf.this.k;
            long now = Util.getMillis();
            String who = "«" + this.entry.fileName() + "»";

            switch (this.phase)
            {
                case 0 ->
                {
                    this.loadAt = now;
                    this.load = SchematicLibrary.get().load(this.entry);
                    this.next(k, now);
                    return false;
                }
                case 1 ->
                {
                    if (this.load == null || ! this.load.isDone())
                    {
                        return false;
                    }

                    LoadedSchematic loaded;

                    try
                    {
                        loaded = this.load.join();
                    }
                    catch (RuntimeException e)
                    {
                        k.fail("замер редактора " + who + ": загрузка — " + e);
                        return true;
                    }

                    long placeAt = Util.getMillis();
                    SchematicPlacement p = PlacementOps.placeInFront(loaded);
                    long placed = Util.getMillis();
                    KPerf.this.made.add(p);
                    PlacementEditorScreen.open(k.gui(), p, true, true);
                    k.info("замер: редактор " + who + ": загрузка в Litematica " + (placeAt - this.loadAt)
                            + " мс, placeInFront " + (placed - placeAt) + " мс, открытие редактора " + (Util.getMillis() - placed)
                            + " мс");
                    this.next(k, Util.getMillis());
                    return false;
                }
                case 2 ->
                {
                    // Litematica строит голограммы новой схемы — даём осесть и смотрим, сколько кадр висит
                    if (now - this.phaseAt < SETTLE_MS)
                    {
                        return false;
                    }

                    PlacementEditorScreen e = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());

                    if (e == null)
                    {
                        k.fail("замер редактора " + who + ": редактор не открылся");
                        return true;
                    }

                    k.info("замер: редактор " + who + ", первые " + SETTLE_MS / 1000 + " с после размещения: пауза до "
                            + k.stepMaxGap() + " мс");
                    this.next(k, now);
                    return false;
                }
                case 3 ->
                {
                    // Покой в редакторе (вид сверху, схема целиком в кадре) — от чего считать перетаскивание
                    this.idleFrames.sample(mc);

                    if (now - this.phaseAt < SETTLE_MS)
                    {
                        return false;
                    }

                    PlacementEditorScreen e = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());

                    if (e == null)
                    {
                        k.fail("замер редактора " + who + ": редактор закрылся");
                        return true;
                    }

                    k.info("замер: редактор " + who + " в покое " + SETTLE_MS / 1000 + " с: " + this.idleFrames + ", FPS "
                            + mc.getFps() + ", пауза до " + k.stepMaxGap() + " мс");
                    this.x = PlacementEditorScreen.TestHooks.freeRight(e) / 2.0;
                    this.y = PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0;
                    e.onMouseClicked(KProbe.left(this.x, this.y), false);
                    this.next(k, now);
                    return false;
                }
                case 4 ->
                {
                    PlacementEditorScreen e = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());

                    if (e == null)
                    {
                        k.fail("замер редактора " + who + ": редактор закрылся посреди перетаскивания");
                        return true;
                    }

                    this.dragFrames.sample(mc);

                    if (now - this.phaseAt < DRAG_MS)
                    {
                        this.x += 3.0;
                        this.y += 2.0;
                        e.onMouseDragged(KProbe.left(this.x, this.y), 3.0, 2.0);
                        return false;
                    }

                    k.info("замер: перетаскивание " + who + " " + DRAG_MS / 1000 + " с (тащат = "
                            + PlacementEditorScreen.TestHooks.dragging(e) + "): " + this.dragFrames + ", FPS " + mc.getFps()
                            + ", пауза до " + k.stepMaxGap() + " мс");
                    e.onMouseReleased(KProbe.left(this.x, this.y));
                    this.next(k, Util.getMillis());
                    return false;
                }
                case 5 ->
                {
                    if (now - this.phaseAt < AFTER_RELEASE_MS)
                    {
                        return false;
                    }

                    k.info("замер: отпускание " + who + " (Litematica переносит голограммы), " + AFTER_RELEASE_MS / 1000
                            + " с: пауза до " + k.stepMaxGap() + " мс");
                    PlacementEditorScreen e = PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());

                    if (e != null)
                    {
                        KProbe.press(e, InputConstants.KEY_ESCAPE);
                    }

                    return true;
                }
                default ->
                {
                    return true;
                }
            }
        }
    }
}
