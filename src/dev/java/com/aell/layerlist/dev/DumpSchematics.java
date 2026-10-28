package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.export.IconBatch;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.schematics.materials.ItemIcons;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.schematics.preview.PreviewCamera;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Slider;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Режим K — вкладка «Схемы» (SPEC §10). Готовит в {@code <схемы>/_k} настоящие схемы игрока
 * (копии из профилей Modrinth) и образец, снятый с мира стенда в трёх форматах, открывает экран
 * {@link GuiSchematics} и проходит его как игрок: папка, список, сведения, миниатюры, 3D-превью
 * (изометрия, сверху, спереди, срез) у каждого файла; сверяет материалы схемы со счётом самой
 * Litematica; открывает «Материалы» и окно выгрузки; выгружает все форматы и проверяет файлы;
 * гоняет редактор размещения ({@link KPlace}); снимает экраны при масштабе GUI 2 и 3 и в узком
 * окне.
 *
 * <p>Строки лога — {@code [k] OK/FAIL/INFO …}, итог — {@code [schematics] OK n, FAIL m}; отчёт
 * {@code schematics.csv}, снимки {@code shots/}, выгрузки {@code exports/} — в
 * {@code LL_COVERAGE_OUT} (без неё — {@code <gameDir>/coverage-k}).
 */
@Environment(EnvType.CLIENT)
public final class DumpSchematics implements CovJob
{
    static final String TAG = "[k] ";
    static final int FLOOR_Y = KData.SAMPLE.getY() + 1;
    /** Где стоит игрок: на площадке, лицом на юг, взгляд вниз на 30° — размещения ложатся на площадку. */
    static final BlockPos STAND = new BlockPos(536, FLOOR_Y, 4);

    private static final int PLATFORM_MIN_X = 516;
    private static final int PLATFORM_MAX_X = 556;
    private static final int PLATFORM_MIN_Z = -8;
    private static final int PLATFORM_MAX_Z = 44;

    private static final long GAP_MS = 120L;
    private static final long POLL_MS = 100L;
    private static final long SETTLE_MS = 900L;

    private static final String KEY_PREVIEW = "layerlist.schematics.preview.";
    private static final String GUI_TAB = KProbe.GUI_PACKAGE + "GuiSchematics$Tab";

    /** Шаг сценария: {@code true} — готово, {@code false} — ещё ждём (повтор через {@link #POLL_MS}). */
    @FunctionalInterface
    interface Action
    {
        boolean run(Minecraft mc);
    }

    record Step(String name, long timeoutMs, Action action) {}

    private final Deque<Step> queue = new ArrayDeque<>();
    private @Nullable Step current;
    private long startedAt;
    private long until;

    private Path out = Path.of(".");
    private Path shots = Path.of(".");
    private Path exports = Path.of(".");
    private Path kDir = Path.of(".");
    private @Nullable BufferedWriter report;
    private int ok;
    private int failed;
    private int shotNo;
    private @Nullable IconBatch exportIcons;
    private @Nullable List<CompletableFuture<byte[]>> iconOrders;

    // Что вернуть после прогона
    private int guiScaleBefore;
    private int windowWBefore;
    private int windowHBefore;
    private String exportDirBefore = "";
    private boolean countEntitiesBefore;
    private boolean thumbnailsBefore;
    private SchematicsState.Sort sortBefore = SchematicsState.Sort.NAME;
    private boolean sortDescendingBefore;
    private String lastFolderBefore = "";
    private int filesSplitBefore = -1;
    private int infoSplitBefore = -1;

    private volatile long expectedBlocks = -1L;
    private boolean screenOpened;
    private @Nullable CompletableFuture<String> serverFiles;
    private final List<SchematicEntry> listed = new ArrayList<>();
    private final Map<Path, String> shortNames = new HashMap<>();
    private final Map<Path, List<MaterialRow>> rowsByFile = new HashMap<>();
    private final Map<Path, LoadedSchematic> loadedByFile = new HashMap<>();
    private final KPlace place = new KPlace(this);
    /** 0.0.27: итог выгрузки в строке окна; перехват открытий в системе. Раньше остальных: ими пользуются они. */
    final KInline inline = new KInline(this);
    private final KDialog dialog = new KDialog(this);
    private final KSkinTour tour = new KSkinTour(this);
    private final KNaming naming = new KNaming(this);
    private final KSources sources = new KSources(this);
    private final KPerf perf = new KPerf(this);
    private final KTrash trash = new KTrash(this);
    private final KRename rename = new KRename(this);
    private final KDeselect deselect = new KDeselect(this);
    private final KHotkey hotkey = new KHotkey(this);
    private final KPoster poster = new KPoster(this);
    private final KQuality quality = new KQuality(this);
    /** 0.0.27: редактор размещения — «−»/«+» у X/Y/Z, подъём за угол, облёт. */
    private final KEdit edit = new KEdit(this);
    /** 0.0.27: замена блоков схемы из таблицы «Материалы» и откат. */
    private final KReplace replace = new KReplace(this);
    /** Тур ванильного стиля: каждое окно в стиле Aell и в стиле Minecraft ({@code LL_K_ONLY=vanilla}). */
    private final KVanillaTour vanillaTour = new KVanillaTour(this);

    // Паузы клиентского потока: промежуток между тиками дольше этого — строка лога (см. stallCheck)
    private static final long STALL_MS = 100L;
    private long lastCallAt;
    private long stepMaxGap;
    private long worstGap;
    private String worstGapStep = "";
    private long selectedAt;

    @Override
    public String name()
    {
        return "K (вкладка «Схемы»)";
    }

    // ===== Жизненный цикл =====

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        try
        {
            this.out = outputDir();
            this.shots = this.out.resolve("shots");
            this.exports = this.out.resolve("exports");
            Files.createDirectories(this.out);
            KData.recreate(this.shots);
            KData.recreate(this.exports);
            this.report = Files.newBufferedWriter(this.out.resolve("schematics.csv"), StandardCharsets.UTF_8);
            this.report.write("subject,result,detail\n");
            this.kDir = SchematicLibrary.get().refreshRoot().resolve("_k").toAbsolutePath().normalize();
            KData.recreate(this.kDir);
        }
        catch (IOException | RuntimeException e)
        {
            CovOut.log(TAG + "подготовка не удалась: " + e);
            return false;
        }

        CovOut.log(TAG + "выход: " + this.out + ", схемы: " + this.kDir);
        this.remember(mc);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        this.copyReal();

        server.execute(() ->
        {
            KData.command(server, "forceload add 480 -32 576 64");
            KData.buildPlatform(server, PLATFORM_MIN_X, PLATFORM_MIN_Z, PLATFORM_MAX_X, PLATFORM_MAX_Z, FLOOR_Y - 1);
            KData.buildSample(server);
            this.expectedBlocks = KData.countSample(server.overworld());
            KData.command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 30", STAND.getX() + 0.5, STAND.getY(),
                    STAND.getZ() + 0.5));
        });

        this.plan();
        this.until = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.until;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();
        this.stallCheck(now);

        if (now < this.until)
        {
            return false;
        }

        if (this.current == null)
        {
            this.current = this.queue.pollFirst();

            if (this.current == null)
            {
                return true;
            }

            this.startedAt = now;
        }

        Step running = this.current;
        boolean done;

        try
        {
            done = running.action().run(mc);
        }
        catch (RuntimeException | LinkageError e)
        {
            this.fail(running.name() + ": исключение " + e);
            e.printStackTrace(System.out);
            done = true;
        }

        if (! done && now - this.startedAt > running.timeoutMs())
        {
            this.fail(running.name() + ": не дождались за " + running.timeoutMs() + " мс");
            done = true;
        }

        if (done)
        {
            if (this.stepMaxGap >= STALL_MS)
            {
                this.info("пауза клиента до " + this.stepMaxGap + " мс на шаге «" + running.name() + "»");
            }

            this.stepMaxGap = 0L;
            this.current = null;
            this.until = Math.max(this.until, Util.getMillis() + GAP_MS);
        }
        else if (this.until <= now)
        {
            this.until = now + POLL_MS;
        }

        return false;
    }

    @Override
    public void close()
    {
        Minecraft mc = Minecraft.getInstance();

        try
        {
            this.perf.cleanup();
            this.replace.cleanup();
            this.edit.cleanup();
            this.hotkey.cleanup();
            this.deselect.cleanup();
            this.trash.cleanup();
            this.rename.cleanup();
            this.naming.cleanup();
            this.sources.cleanup();
            this.dialog.cleanup();
            this.inline.cleanup();
            this.place.cleanup();
            this.restore(mc);
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log(TAG + "уборка не удалась: " + e);
        }

        CovOut.log(TAG + "самая долгая пауза клиента: " + this.worstGap + " мс (шаг «" + this.worstGapStep + "»)");
        CovOut.log("[schematics] OK " + this.ok + ", FAIL " + this.failed);

        if (this.report != null)
        {
            try
            {
                this.report.close();
            }
            catch (IOException e)
            {
                CovOut.log(TAG + "отчёт не закрылся: " + e);
            }
        }
    }

    private static Path outputDir()
    {
        String override = System.getenv("LL_COVERAGE_OUT");

        if (override != null && ! override.isBlank())
        {
            return Path.of(override).toAbsolutePath().normalize();
        }

        return FabricLoader.getInstance().getGameDir().resolve("coverage-k").toAbsolutePath().normalize();
    }

    private void remember(Minecraft mc)
    {
        this.guiScaleBefore = mc.options.guiScale().get();
        this.windowWBefore = mc.getWindow().getWidth();
        this.windowHBefore = mc.getWindow().getHeight();
        this.exportDirBefore = SchematicsConfig.EXPORT_DIR.getStringValue();
        this.countEntitiesBefore = LayerListConfigs.COUNT_ENTITIES.getBooleanValue();
        this.thumbnailsBefore = SchematicsConfig.THUMBNAILS.getBooleanValue();

        SchematicsState state = SchematicsState.get();
        this.sortBefore = state.sort();
        this.sortDescendingBefore = state.sortDescending();
        this.lastFolderBefore = state.lastFolder();
        this.filesSplitBefore = state.splitter("files", -1);
        this.infoSplitBefore = state.splitter("info", -1);

        // Сущности считаем, миниатюры включены, выгрузки — к нам; порядок и ширины панелей — по умолчанию
        LayerListConfigs.COUNT_ENTITIES.setBooleanValue(true);
        SchematicsConfig.THUMBNAILS.setBooleanValue(true);
        SchematicsConfig.EXPORT_DIR.setValueFromString(this.exports.toString());
        state.setSort(SchematicsState.Sort.NAME, false);
        state.setSplitter("files", -1);
        state.setSplitter("info", -1);
    }

    private void restore(Minecraft mc)
    {
        if (GuiUtils.getCurrentScreen() != null)
        {
            Nav.clear();
            GuiBase.openGui(null);
        }

        LayerListConfigs.COUNT_ENTITIES.setBooleanValue(this.countEntitiesBefore);
        SchematicsConfig.THUMBNAILS.setBooleanValue(this.thumbnailsBefore);
        SchematicsConfig.EXPORT_DIR.setValueFromString(this.exportDirBefore);

        SchematicsState state = SchematicsState.get();
        state.setSort(this.sortBefore, this.sortDescendingBefore);
        state.setLastFolder(this.lastFolderBefore);
        state.setSplitter("files", this.filesSplitBefore);
        state.setSplitter("info", this.infoSplitBefore);
        state.save();

        if (this.windowWBefore > 0 && this.windowHBefore > 0
                && (mc.getWindow().getWidth() != this.windowWBefore || mc.getWindow().getHeight() != this.windowHBefore))
        {
            mc.getWindow().setWindowed(this.windowWBefore, this.windowHBefore);
        }

        scale(mc, this.guiScaleBefore);
    }

    // ===== Сценарий =====

    /**
     * Пауза клиентского потока: {@link #step} зовётся раз в тик (после {@link #waiting} порция обрывается),
     * поэтому промежуток между вызовами дольше обычных 50 мс — это зависший кадр или тик.
     */
    private void stallCheck(long now)
    {
        long gap = this.lastCallAt == 0L ? 0L : now - this.lastCallAt;
        this.lastCallAt = now;

        if (gap > this.stepMaxGap)
        {
            this.stepMaxGap = gap;
        }

        if (gap > this.worstGap && this.current != null)
        {
            this.worstGap = gap;
            this.worstGapStep = this.current.name();
        }
    }

    /** Самая длинная пауза с начала шага (для замеров {@link KPerf}). */
    long stepMaxGap()
    {
        return this.stepMaxGap;
    }

    void resetStepMaxGap()
    {
        this.stepMaxGap = 0L;
    }

    private void plan()
    {
        this.add("образец на клиенте", 30_000L, this::sampleSynced);
        this.add("образец → .litematic", 10_000L, this::writeLitematic);
        this.add("образец → .nbt и .schem", 20_000L, this::writeServerFiles);
        this.add("окно 1600×900, GUI 2", 8_000L, this.window(1600, 900, 2));
        this.add("открыть «Схемы»", 10_000L, this::openScreen);
        this.add("вкладка «Библиотека»", 2_000L, mc -> this.setTab("LIBRARY"));
        this.add("из корня", 2_000L, this::toRoot);
        this.add("вход в _k с клавиатуры", 5_000L, this::enterK);
        this.add("папка _k открыта", 5_000L, this::inK);
        this.add("список _k", 15_000L, this::listReady);
        // Дальше — шаги на каждый файл и общий хвост: их ставит listReady, когда известен порядок строк
    }

    /** Хвост сценария после шагов по файлам. */
    private List<Step> tail()
    {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("снова «k-generated.litematic»", 5_000L, mc -> this.selectGenerated()));
        steps.add(new Step("вкладка «Материалы» кнопкой карточки", 5_000L, this::openMaterialsTab));
        steps.add(new Step("строки «Материалов»", 40_000L, new MaterialsTabRows()));
        steps.add(new Step("снимок «Материалы»", 5_000L, this.settleShot("materials")));
        steps.add(new Step("назад в «Библиотеку»", 2_000L, mc -> this.setTab("LIBRARY")));
        steps.add(new Step("окно выгрузки кнопкой карточки", 5_000L, this::openExportDialog));
        steps.add(new Step("снимок окна выгрузки", 5_000L, this.settleShot("export-dialog")));
        steps.add(new Step("Esc закрывает окно выгрузки", 3_000L, this::closeExportDialog));
        steps.add(new Step("иконки выгрузок заказаны и пришли", 60_000L, mc -> this.warmExportIcons()));
        steps.add(new Step("выгрузки всех форматов", 120_000L, mc -> this.runExports()));
        steps.add(new Step("выгрузки средней схемы", 60_000L, mc -> this.runMediumExports()));
        steps.add(new Step("иконки предметов и разбивка", 40_000L, mc -> this.iconsAndBreakdown()));
        // 0.0.27: постер-превью выгрузки «Картинка» — заказ при открытом окне выгрузки, PNG в posters/
        steps.addAll(this.poster.steps());
        // 0.0.27: «Отображение схемы» и «Настройки» надписью — после постера (его копия большой схемы игрока)
        steps.addAll(this.quality.steps());
        steps.addAll(this.place.steps());
        steps.addAll(this.variants());
        // 0.0.27: выбор игроков, Discord частями, тумблер голограмм, кнопка «Material HUD» — при GUI 2 и 3
        steps.addAll(this.dialog.dialogSteps(""));
        // 0.0.27: имена файлов по схеме, кнопка «Папка с выгрузками» и её свечение — при GUI 2 и 3
        steps.addAll(this.naming.steps(""));
        // 0.0.27: что значит «Только недостающее», «Схема» без выбора в «Библиотеке», кнопка карточки
        steps.addAll(this.sources.steps(""));
        steps.addAll(this.place.liveSteps());
        // 0.0.27: редактор размещения — «−»/«+», подъём за угол, облёт; большая схема игрока
        steps.addAll(this.edit.steps(""));
        steps.addAll(this.edit.bigSteps());
        // 0.0.27: замена блоков из таблицы «Материалы», откат; большая схема игрока
        steps.addAll(this.replace.steps(""));
        steps.addAll(this.replace.bigSteps());
        steps.add(new Step("GUI 3 (выгрузка, редактор)", 8_000L, this.window(1600, 900, 3)));
        steps.addAll(this.dialog.dialogSteps("s3-"));
        steps.addAll(this.naming.steps("s3-"));
        steps.addAll(this.sources.steps("s3-"));
        steps.addAll(this.place.liveShotsAtScale3());
        steps.addAll(this.editAtScale3());
        steps.addAll(this.replace.steps("s3-"));
        steps.add(new Step("GUI 2 (меню Litematica)", 8_000L, this.window(1600, 900, 2)));
        steps.addAll(this.dialog.menuSteps(""));
        steps.add(new Step("GUI 3 (меню Litematica)", 8_000L, this.window(1600, 900, 3)));
        steps.addAll(this.dialog.menuSteps("s3-"));
        steps.addAll(this.libSteps());
        steps.add(new Step("GUI 2 — обратно", 8_000L, this.window(1600, 900, 2)));
        steps.add(new Step("закрыть экран", 3_000L, mc -> this.closeScreen()));
        steps.add(new Step("снимки записаны", 10_000L, mc -> this.shotsWritten()));
        return steps;
    }

    /** 0.0.27: редактор размещения при GUI 3 и в «тесном» окне (ниже 300 px GUI) — до них окно уже GUI 3. */
    private List<Step> editAtScale3()
    {
        List<Step> steps = new ArrayList<>();
        steps.addAll(this.edit.stepsAtScale3("s3-"));
        steps.add(new Step("тесное окно 1600×780, GUI 3 (редактор)", 8_000L, this.window(1600, 780, 3)));
        steps.addAll(this.edit.tightSteps("s3t-"));
        steps.add(new Step("окно 1600×900, GUI 3 — после тесного", 8_000L, this.window(1600, 900, 3)));
        return steps;
    }

    /** 0.0.27: удаление в корзину и окно «Корзина», «Снять выбор», клавиша «Схемы» — при GUI 2 и 3. */
    private List<Step> libSteps()
    {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("GUI 2 (корзина, выбор, клавиша)", 8_000L, this.window(1600, 900, 2)));
        steps.addAll(this.trash.steps(""));
        steps.addAll(this.rename.steps(""));
        steps.addAll(this.deselect.steps(""));
        steps.addAll(this.hotkey.steps(""));
        steps.add(new Step("GUI 3 (корзина, выбор, клавиша)", 8_000L, this.window(1600, 900, 3)));
        steps.addAll(this.trash.steps("s3-"));
        steps.addAll(this.rename.steps("s3-"));
        steps.addAll(this.deselect.steps("s3-"));
        steps.addAll(this.hotkey.steps("s3-"));
        steps.add(new Step("GUI 2 — после шагов «Библиотеки»", 8_000L, this.window(1600, 900, 2)));
        return steps;
    }

    void add(String name, long timeoutMs, Action action)
    {
        this.queue.addLast(new Step(name, timeoutMs, action));
    }

    /** Вставить шаги сразу за текущим, в их порядке. */
    void insertNext(List<Step> steps)
    {
        for (int i = steps.size() - 1; i >= 0; --i)
        {
            this.queue.addFirst(steps.get(i));
        }
    }

    /** Подождать, пока кадры перерисуют экран, и снять его. */
    Action settleShot(String name)
    {
        return this.settleThen(SETTLE_MS, mc ->
        {
            this.shot(mc, name);
            return true;
        });
    }

    Action settleThen(long ms, Action then)
    {
        long[] at = {0L};

        return mc ->
        {
            long now = Util.getMillis();

            if (at[0] == 0L)
            {
                at[0] = now + ms;
            }

            return now >= at[0] && then.run(mc);
        };
    }

    // ===== Тестовые данные =====

    private void copyReal()
    {
        this.copyExtra();

        List<Path> folders = KData.profileFolders();

        if (folders.isEmpty())
        {
            this.info("папок схем игрока не нашлось — только сгенерированные файлы");
            return;
        }

        CovOut.log(TAG + "папки игрока: " + folders);

        for (KData.Picked picked : KData.pickReal(folders))
        {
            Path target = this.kDir.resolve(picked.source().getFileName().toString());

            try
            {
                Files.copy(picked.source(), target, StandardCopyOption.REPLACE_EXISTING);
                this.ok("копия игрока: " + picked.why() + " — " + picked.source().getFileName() + " ("
                        + KData.size(target) + " Б)");
            }
            catch (IOException | RuntimeException e)
            {
                this.info("не скопировать " + picked.source() + ": " + e);
            }
        }
    }

    /**
     * {@code LL_K_EXTRA} — папка (или файлы через «;») с настоящими схемами, которые нужно прогнать
     * целиком: копии ложатся в {@code _k} рядом с образцом и проходят те же шаги (превью, срез, материалы).
     */
    private void copyExtra()
    {
        String extra = System.getenv("LL_K_EXTRA");

        if (extra == null || extra.isBlank())
        {
            return;
        }

        for (String part : extra.split(";"))
        {
            Path source = Path.of(part.trim());

            try (Stream<Path> files = Files.isDirectory(source) ? Files.list(source) : Stream.of(source))
            {
                for (Path file : files.filter(p -> SchematicFormat.of(p) != null).toList())
                {
                    Files.copy(file, this.kDir.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    this.ok("копия из LL_K_EXTRA: " + file.getFileName() + " (" + KData.size(file) + " Б)");
                }
            }
            catch (IOException | RuntimeException e)
            {
                this.info("LL_K_EXTRA: не скопировать " + source + ": " + e);
            }
        }
    }

    /** Клиент видит образец целиком: узор флага, воду и стойку — иначе .litematic снимется неполной. */
    private boolean sampleSynced(Minecraft mc)
    {
        if (mc.level == null || this.expectedBlocks < 0L)
        {
            return false;
        }

        BlockPos banner = KData.SAMPLE.offset(3, 1, 5);
        boolean pattern = mc.level.getBlockEntity(banner) instanceof BannerBlockEntity b && ! b.getPatterns().layers().isEmpty();
        boolean water = mc.level.getBlockState(KData.SAMPLE.offset(5, 1, 2)).is(Blocks.WATER);
        AABB box = new AABB(KData.SAMPLE.getX(), KData.SAMPLE.getY(), KData.SAMPLE.getZ(), KData.SAMPLE.getX() + 7,
                KData.SAMPLE.getY() + 5, KData.SAMPLE.getZ() + 7);
        boolean stand = ! mc.level.getEntitiesOfClass(ArmorStand.class, box).isEmpty();

        boolean frames = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, box).size() >= 2;

        if (pattern && water && stand && frames)
        {
            this.ok("образец на клиенте: узор флага, вода, стойка, рамки; непустых клеток " + this.expectedBlocks);
            return true;
        }

        return false;
    }

    private boolean writeLitematic(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName(KData.GENERATED);
        BlockPos max = KData.SAMPLE.offset(KData.SAMPLE_SIZE).offset(-1, -1, -1);
        area.addSubRegionBox(new Box(KData.SAMPLE, max, "main"), false);
        area.setExplicitOrigin(KData.SAMPLE);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "Material HUD harness", CovOut::log);

        boolean written = schematic != null && schematic.writeToFile(this.kDir, KData.GENERATED, true);
        this.check("образец сохранён Litematica: " + KData.GENERATED + ".litematic",
                written && KExports.nonEmpty(this.kDir.resolve(KData.GENERATED + ".litematic")));
        return true;
    }

    private boolean writeServerFiles(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            this.fail("нет встроенного сервера для .nbt и .schem");
            return true;
        }

        if (this.serverFiles == null)
        {
            Path nbt = this.kDir.resolve(KData.GENERATED + ".nbt");
            Path schem = this.kDir.resolve(KData.GENERATED + ".schem");
            this.serverFiles = server.submit(() ->
            {
                try
                {
                    KData.writeStructure(server.overworld(), nbt);
                    KData.writeSponge(server.overworld(), schem);
                    return "";
                }
                catch (IOException | RuntimeException e)
                {
                    return e.toString();
                }
            });
            return false;
        }

        if (! this.serverFiles.isDone())
        {
            return false;
        }

        String error = this.serverFiles.getNow("?");
        this.check("образец → .nbt (StructureTemplate) и .schem (свой Sponge v3)" + (error.isEmpty() ? "" : ": " + error),
                error.isEmpty() && KExports.nonEmpty(this.kDir.resolve(KData.GENERATED + ".nbt"))
                        && KExports.nonEmpty(this.kDir.resolve(KData.GENERATED + ".schem")));
        return true;
    }

    // ===== Окно и масштаб =====

    /** Окно заданного размера и масштаб GUI; окно не меняется (оконный менеджер) — INFO, снимки как есть. */
    Action window(int w, int h, int guiScale)
    {
        boolean[] asked = {false};

        return mc ->
        {
            if (! asked[0])
            {
                asked[0] = true;

                if (mc.getWindow().getWidth() != w || mc.getWindow().getHeight() != h)
                {
                    mc.getWindow().setWindowed(w, h);
                }

                scale(mc, guiScale);
                return false;
            }

            if (mc.getWindow().getWidth() != w || mc.getWindow().getHeight() != h)
            {
                if (Util.getMillis() - this.startedAt < 5_000L)
                {
                    return false;
                }

                this.info("окно не стало " + w + "×" + h + " (сейчас " + mc.getWindow().getWidth() + "×"
                        + mc.getWindow().getHeight() + ") — снимки в том размере, что есть");
            }

            // Масштаб считается от размера окна — переставляем после ресайза
            scale(mc, guiScale);
            CovOut.log(TAG + "окно " + mc.getWindow().getWidth() + "×" + mc.getWindow().getHeight() + ", GUI " + guiScale
                    + " → " + mc.getWindow().getGuiScaledWidth() + "×" + mc.getWindow().getGuiScaledHeight());
            return true;
        };
    }

    static void scale(Minecraft mc, int value)
    {
        mc.options.guiScale().set(value);
        mc.resizeGui();
    }

    // ===== Экран =====

    @Nullable GuiSchematics gui()
    {
        return GuiUtils.getCurrentScreen() instanceof GuiSchematics screen ? screen : null;
    }

    private boolean openScreen(Minecraft mc)
    {
        if (! this.screenOpened)
        {
            this.screenOpened = true;
            GuiSchematics.open(null);
            return false;
        }

        if (this.gui() == null)
        {
            return false;
        }

        this.ok("экран «Схемы» открыт");
        return true;
    }

    boolean setTab(String tab)
    {
        GuiSchematics gui = this.gui();

        if (gui == null)
        {
            this.fail("вкладка " + tab + ": экрана нет");
            return true;
        }

        try
        {
            KProbe.call(gui, "setTab", KProbe.enumConstant(GUI_TAB, tab));
            this.check("вкладка " + tab, tab, String.valueOf(KProbe.call(gui, "tab")));
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("вкладка " + tab + ": " + e);
        }

        return true;
    }

    @Nullable Object files()
    {
        return KProbe.fieldNamedType(this.gui(), "FileListPane");
    }

    @Nullable Object previewPane()
    {
        return KProbe.fieldNamedType(this.gui(), "PreviewPane");
    }

    @Nullable Object infoPane()
    {
        return KProbe.fieldNamedType(this.gui(), "InfoPane");
    }

    private @Nullable ScrollList<?> fileList()
    {
        return KProbe.fieldOfType(this.files(), ScrollList.class);
    }

    private @Nullable Path folder()
    {
        Object files = this.files();

        try
        {
            return files == null ? null : ((Path) KProbe.call(files, "folder")).toAbsolutePath().normalize();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    /** Строка списка → её запись каталога ({@code null} — строка «↑ ..»). */
    private static @Nullable SchematicEntry entryOf(Object item)
    {
        return (SchematicEntry) KProbe.recordComponent(item, SchematicEntry.class);
    }

    private int indexOf(Path path)
    {
        ScrollList<?> list = this.fileList();

        if (list == null)
        {
            return -1;
        }

        List<?> items = list.items();

        for (int i = 0; i < items.size(); ++i)
        {
            SchematicEntry entry = entryOf(items.get(i));

            if (entry != null && entry.path().toAbsolutePath().normalize().equals(path))
            {
                return i;
            }
        }

        return -1;
    }

    private boolean toRoot(Minecraft mc)
    {
        Object files = this.files();

        if (files == null)
        {
            this.fail("нет панели файлов");
            return true;
        }

        try
        {
            KProbe.call(files, "open", SchematicLibrary.get().root());
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("панель файлов: нет open(Path): " + e);
        }

        return true;
    }

    private boolean enterK(Minecraft mc)
    {
        GuiSchematics gui = this.gui();
        int index = this.indexOf(this.kDir);

        if (gui == null || index < 0)
        {
            return false;
        }

        // Как игрок: Home, вниз до папки, Enter
        KProbe.press(gui, InputConstants.KEY_HOME);

        for (int i = 0; i < index; ++i)
        {
            KProbe.press(gui, InputConstants.KEY_DOWN);
        }

        KProbe.press(gui, InputConstants.KEY_RETURN);
        return true;
    }

    private boolean inK(Minecraft mc)
    {
        if (this.kDir.equals(this.folder()))
        {
            this.ok("вход в папку _k с клавиатуры (Home, ↓, Enter)");
            return true;
        }

        if (Util.getMillis() - this.startedAt < 4_000L)
        {
            return false;
        }

        this.fail("Enter на строке _k не открыл папку (сейчас " + this.folder() + ") — открываю напрямую");

        try
        {
            Object files = this.files();

            if (files != null)
            {
                KProbe.call(files, "open", this.kDir);
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("open(_k): " + e);
        }

        return true;
    }

    private boolean listReady(Minecraft mc)
    {
        ScrollList<?> list = this.fileList();
        long expected;

        try (Stream<Path> files = Files.list(this.kDir))
        {
            expected = files.filter(p -> SchematicFormat.of(p) != null).count();
        }
        catch (IOException e)
        {
            expected = -1;
        }

        if (list == null)
        {
            return false;
        }

        List<SchematicEntry> entries = new ArrayList<>();

        for (Object item : list.items())
        {
            SchematicEntry entry = entryOf(item);

            if (entry != null && ! entry.directory())
            {
                entries.add(entry);
            }
        }

        if (entries.size() != expected && Util.getMillis() - this.startedAt < 10_000L)
        {
            return false;
        }

        this.check("файлов в списке _k", expected, (long) entries.size());
        this.listed.clear();
        this.listed.addAll(entries);

        int n = 0;

        for (SchematicEntry entry : entries)
        {
            String shortName = "f" + (++n) + "-" + (entry.format() == null ? "x" : entry.format().extension().replace(".", ""));
            this.shortNames.put(entry.path(), shortName);
            CovOut.log(TAG + shortName + " = " + entry.fileName() + " (" + entry.size() + " Б)");
        }

        List<Step> steps = new ArrayList<>();

        // LL_K_ONLY=lib — только шаги 0.0.27 «Библиотеки» (корзина, «Снять выбор», клавиша): быстрый прогон правки
        if ("lib".equals(System.getenv("LL_K_ONLY")))
        {
            steps.addAll(this.libSteps());
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=rename — только переименование в «Библиотеке» (25.10.2026) при GUI 2 и 3
        if ("rename".equals(System.getenv("LL_K_ONLY")))
        {
            steps.add(new Step("GUI 2 (переименование)", 8_000L, this.window(1600, 900, 2)));
            steps.addAll(this.rename.steps(""));
            steps.add(new Step("GUI 3 (переименование)", 8_000L, this.window(1600, 900, 3)));
            steps.addAll(this.rename.steps("s3-"));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=edit — только редактор размещения 0.0.27 («−»/«+», угол, облёт) при GUI 2 и 3
        if ("edit".equals(System.getenv("LL_K_ONLY")))
        {
            steps.addAll(this.edit.steps(""));
            steps.addAll(this.edit.bigSteps());
            steps.add(new Step("GUI 3 (редактор)", 8_000L, this.window(1600, 900, 3)));
            steps.addAll(this.editAtScale3());
            steps.add(new Step("GUI 2 — после редактора", 8_000L, this.window(1600, 900, 2)));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=quality — «Отображение схемы» и кнопка «Настройки» (0.0.27); постер — ради копии большой схемы
        if ("quality".equals(System.getenv("LL_K_ONLY")))
        {
            steps.addAll(this.poster.steps());
            steps.addAll(this.quality.steps());
            steps.add(new Step("закрыть экран", 3_000L, client -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, client -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=replace — только замена блоков из «Материалов» (0.0.27) при GUI 2 и 3 и большая схема
        if ("replace".equals(System.getenv("LL_K_ONLY")))
        {
            steps.addAll(this.replace.steps(""));
            steps.addAll(this.replace.bigSteps());
            steps.add(new Step("GUI 3 (замена)", 8_000L, this.window(1600, 900, 3)));
            steps.addAll(this.replace.steps("s3-"));
            steps.add(new Step("GUI 2 — после замены", 8_000L, this.window(1600, 900, 2)));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=placed — только «Материалы» размещённой и неразмещённой схемы: сверка с миром, тумблер, выгрузка (1.0.18)
        if ("placed".equals(System.getenv("LL_K_ONLY")))
        {
            steps.add(new Step("GUI 2 (сверка размещённой)", 8_000L, this.window(1600, 900, 2)));
            steps.addAll(this.sources.placedSteps());
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=menu — только кнопка «Material HUD» в главном меню Litematica при GUI 2 и 3 (с чужой кнопкой)
        if ("menu".equals(System.getenv("LL_K_ONLY")))
        {
            steps.add(new Step("GUI 2 (меню Litematica)", 8_000L, this.window(1600, 900, 2)));
            steps.addAll(this.dialog.menuSteps(""));
            steps.add(new Step("GUI 3 (меню Litematica)", 8_000L, this.window(1600, 900, 3)));
            steps.addAll(this.dialog.menuSteps("s3-"));
            steps.add(new Step("GUI 2 — после меню", 8_000L, this.window(1600, 900, 2)));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=vanilla — тур ванильного стиля: каждое окно мода в двух стилях, снимки vanilla-<окно>-aell/-vanilla
        if ("vanilla".equals(System.getenv("LL_K_ONLY")))
        {
            steps.add(new Step("GUI 2 (тур ванильного стиля)", 8_000L, this.window(1600, 900, 2)));
            steps.addAll(this.vanillaTour.steps(""));
            steps.add(new Step("GUI 2 — после тура", 8_000L, this.window(1600, 900, 2)));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=skin — меню Litematica в цветах Material HUD: без перекраски и два варианта, GUI 2 и 3
        if ("skin".equals(System.getenv("LL_K_ONLY")))
        {
            steps.add(new Step("GUI 2 (меню в цветах MH)", 8_000L, this.window(1600, 900, 2)));
            steps.addAll(this.dialog.skinSteps(""));
            steps.addAll(this.tour.steps(""));
            steps.add(new Step("GUI 3 (меню в цветах MH)", 8_000L, this.window(1600, 900, 3)));
            steps.addAll(this.dialog.skinSteps("s3-"));
            steps.addAll(this.tour.steps("s3-"));
            steps.add(new Step("GUI 2 — после меню", 8_000L, this.window(1600, 900, 2)));
            steps.add(new Step("закрыть экран", 3_000L, m -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, m -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        // LL_K_ONLY=poster — только постер-превью выгрузки «Картинка» (0.0.27), без проходов по файлам
        if ("poster".equals(System.getenv("LL_K_ONLY")))
        {
            steps.addAll(this.poster.steps());
            steps.add(new Step("закрыть экран", 3_000L, client -> this.closeScreen()));
            steps.add(new Step("снимки записаны", 10_000L, client -> this.shotsWritten()));
            this.insertNext(steps);
            return true;
        }

        steps.add(new Step("сведения о файлах", 60_000L, new InfoCheck()));
        steps.add(new Step("миниатюры", 70_000L, new ThumbsCheck()));
        steps.add(new Step("снимок списка", 5_000L, this.settleShot("list")));

        for (SchematicEntry entry : entries)
        {
            steps.addAll(this.fileSteps(entry));
        }

        steps.addAll(this.perf.steps(entries));
        steps.addAll(this.tail());
        this.insertNext(steps);
        return true;
    }

    // ===== Сведения и миниатюры =====

    private final class InfoCheck implements Action
    {
        private final Map<SchematicEntry, CompletableFuture<SchematicInfo>> futures = new LinkedHashMap<>();

        @Override
        public boolean run(Minecraft mc)
        {
            if (this.futures.isEmpty())
            {
                for (SchematicEntry entry : DumpSchematics.this.listed)
                {
                    this.futures.put(entry, SchematicLibrary.get().info(entry));
                }
            }

            if (! this.futures.values().stream().allMatch(CompletableFuture::isDone))
            {
                return false;
            }

            for (Map.Entry<SchematicEntry, CompletableFuture<SchematicInfo>> pair : this.futures.entrySet())
            {
                DumpSchematics.this.checkInfo(pair.getKey(), pair.getValue());
            }

            return true;
        }
    }

    private void checkInfo(SchematicEntry entry, CompletableFuture<SchematicInfo> future)
    {
        String who = this.label(entry);
        SchematicInfo info;

        try
        {
            info = future.join();
        }
        catch (RuntimeException e)
        {
            this.fail("сведения " + who + ": " + e);
            return;
        }

        String summary = info.sizeX() + "×" + info.sizeY() + "×" + info.sizeZ() + ", блоков " + info.blocks() + ", регионов "
                + info.regions() + ", данные " + info.dataVersion() + (info.hasPreview() ? ", превью Litematica" : "");

        if (! info.ok())
        {
            this.fail("сведения " + who + ": " + info.error());
            return;
        }

        this.ok("сведения " + who + ": " + summary);

        if (entry.fileName().startsWith(KData.GENERATED + "."))
        {
            this.check("размер " + who, "7×5×7", info.sizeX() + "×" + info.sizeY() + "×" + info.sizeZ());
            this.check("блоков " + who, this.expectedBlocks, info.blocks());
        }
    }

    private final class ThumbsCheck implements Action
    {
        @Override
        public boolean run(Minecraft mc)
        {
            boolean waiting = false;
            List<SchematicEntry> missing = new ArrayList<>();

            for (SchematicEntry entry : DumpSchematics.this.listed)
            {
                Identifier id = SchematicLibrary.get().thumbnail(entry);

                if (id == null)
                {
                    missing.add(entry);
                    // Старому .schematic миниатюра положена только после открытия (снимок в памяти)
                    waiting |= entry.format() != SchematicFormat.SCHEMATICA;
                }
            }

            if (waiting && Util.getMillis() - DumpSchematics.this.startedAt < 60_000L)
            {
                return false;
            }

            for (SchematicEntry entry : DumpSchematics.this.listed)
            {
                String who = DumpSchematics.this.label(entry);

                if (! missing.contains(entry))
                {
                    DumpSchematics.this.ok("миниатюра " + who);
                }
                else if (entry.format() == SchematicFormat.SCHEMATICA)
                {
                    DumpSchematics.this.info("миниатюра " + who + " — нет до открытия (так задумано для .schematic)");
                }
                else
                {
                    DumpSchematics.this.fail("миниатюра " + who + " не появилась за 60 с");
                }
            }

            return true;
        }
    }

    // ===== Файл за файлом =====

    private List<Step> fileSteps(SchematicEntry entry)
    {
        String s = this.shortNames.getOrDefault(entry.path(), "f");
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("выбор " + s, 5_000L, mc -> this.select(entry)));
        steps.add(new Step("превью " + s, 120_000L, mc -> this.previewReady(entry, Integer.MAX_VALUE)));
        steps.add(new Step("снимок " + s + " изометрия", 5_000L, this.settleShot(s + "-iso")));
        steps.add(new Step("вид «Сверху» " + s, 3_000L, mc -> this.view(PreviewCamera.View.TOP, "top")));
        steps.add(new Step("снимок " + s + " сверху", 5_000L, this.settleShot(s + "-top")));
        steps.add(new Step("вид «Спереди» " + s, 3_000L, mc -> this.view(PreviewCamera.View.FRONT, "front")));
        steps.add(new Step("снимок " + s + " спереди", 5_000L, this.settleShot(s + "-front")));
        steps.add(new Step("вид «Изометрия» " + s, 3_000L, mc -> this.view(PreviewCamera.View.ISOMETRIC, "iso")));
        steps.add(new Step("срез " + s, 3_000L, mc -> this.cut(entry, 0.45)));
        steps.add(new Step("модель среза " + s, 60_000L, mc -> this.previewReady(entry, -1)));
        steps.add(new Step("снимок " + s + " срез", 5_000L, this.settleShot(s + "-cut")));
        steps.add(new Step("срез снят " + s, 3_000L, mc -> this.cut(entry, 1.0)));
        steps.add(new Step("материалы " + s, 120_000L, new MaterialsCheck(entry)));
        return steps;
    }

    String label(SchematicEntry entry)
    {
        return this.shortNames.getOrDefault(entry.path(), "?") + " «" + entry.fileName() + "»";
    }

    /** Выбрать строку файла с клавиатуры: Home и ↓ до неё. */
    private boolean select(SchematicEntry entry)
    {
        GuiSchematics gui = this.gui();
        int index = this.indexOf(entry.path().toAbsolutePath().normalize());

        if (gui == null || index < 0)
        {
            this.fail("выбор " + this.label(entry) + ": строки нет в списке");
            return true;
        }

        // Стрелками от текущей строки (без выбора — от первой): лишние схемы по пути не открываются
        ScrollList<?> list = this.fileList();
        int at = list == null ? -1 : list.selectedIndex();

        if (at < 0)
        {
            KProbe.press(gui, InputConstants.KEY_HOME);
            at = 0;
        }

        for (; at < index; ++at)
        {
            KProbe.press(gui, InputConstants.KEY_DOWN);
        }

        for (; at > index; --at)
        {
            KProbe.press(gui, InputConstants.KEY_UP);
        }

        Object selected = null;

        try
        {
            selected = KProbe.call(gui, "selected");
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("selected(): " + e);
        }

        this.check("выбор с клавиатуры " + this.label(entry),
                selected instanceof SchematicEntry chosen && chosen.path().equals(entry.path()));
        this.selectedAt = Util.getMillis();
        return true;
    }

    /** Схемы списка «Библиотеки» этого прогона (для {@link KSources}: взять другую, без размещения). */
    List<SchematicEntry> listedEntries()
    {
        return List.copyOf(this.listed);
    }

    /** Для {@link KPerf}: выбор строки с клавиатуры и ожидание готовой модели, как у шагов по файлам. */
    boolean selectEntry(SchematicEntry entry)
    {
        return this.select(entry);
    }

    boolean previewReadyFor(SchematicEntry entry)
    {
        return this.previewReady(entry, Integer.MAX_VALUE);
    }

    boolean selectGenerated()
    {
        SchematicEntry generated = this.generated(".litematic");

        if (generated == null)
        {
            this.fail("нет k-generated.litematic в списке");
            return true;
        }

        if (this.gui() == null)
        {
            this.fail("экран «Схемы» закрыт");
            return true;
        }

        return this.select(generated);
    }

    @Nullable SchematicEntry generated(String extension)
    {
        for (SchematicEntry entry : this.listed)
        {
            if (entry.fileName().equals(KData.GENERATED + extension))
            {
                return entry;
            }
        }

        return null;
    }

    private @Nullable PreviewWidget widget()
    {
        return KProbe.fieldOfType(this.previewPane(), PreviewWidget.class);
    }

    /**
     * Модель готова: снимок отдан превью, сборки нет, статус не «Загрузка…»/«Сборка…». Ошибка чтения
     * или сборки — FAIL. {@code wantCut} — проверить ещё и верхний слой среза (−1 — любой ниже верха).
     */
    private boolean previewReady(SchematicEntry entry, int wantCut)
    {
        GuiSchematics gui = this.gui();
        PreviewWidget widget = this.widget();

        if (gui == null || widget == null)
        {
            this.fail("превью " + this.label(entry) + ": нет экрана или виджета");
            return true;
        }

        // Срез пересобирается после паузы ползунка (~150 мс): раньше виден ещё старый, готовый вид
        if (wantCut != Integer.MAX_VALUE && Util.getMillis() - this.startedAt < 500L)
        {
            return false;
        }

        Object error = null;

        try
        {
            error = KProbe.call(gui, "snapshotError");
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("snapshotError(): " + e);
        }

        if (error != null)
        {
            this.fail("превью " + this.label(entry) + ": снимок не прочитан — " + error);
            return true;
        }

        VoxelSnapshot source = KProbe.fieldOfType(this.previewPane(), VoxelSnapshot.class);
        String status = widget.status();
        boolean busy = source == null || widget.progress() >= 0.0F || status.equals(I18n.get(KEY_PREVIEW + "loading"))
                || status.equals(I18n.get(KEY_PREVIEW + "building", 0)) || status.equals(I18n.get(KEY_PREVIEW + "empty"));

        if (busy)
        {
            return false;
        }

        boolean broken = status.startsWith(prefix(KEY_PREVIEW + "failed")) || status.startsWith(prefix(KEY_PREVIEW + "build_failed"));
        String detail = "снимок " + source.sizeX() + "×" + source.sizeY() + "×" + source.sizeZ() + " (сетка ×" + source.scale()
                + "), статус «" + status + "»";

        if (wantCut == Integer.MAX_VALUE)
        {
            this.check("модель превью " + this.label(entry) + ": " + detail + ", от выбора " + (Util.getMillis()
                    - this.selectedAt) + " мс", ! broken);
        }
        else
        {
            this.check("модель среза " + this.label(entry) + " до слоя " + widget.layerCut() + ": " + detail,
                    ! broken && widget.layerCut() < Integer.MAX_VALUE);
        }

        return true;
    }

    /** Начало перевода до первой подстановки — чтобы узнать статус-ошибку с любым текстом. */
    private static String prefix(String key)
    {
        String text = I18n.get(key, "\u0000");
        int cut = text.indexOf('\u0000');
        return cut >= 0 ? text.substring(0, cut) : text;
    }

    /** Кнопка вида над превью — кликом через экран. */
    private boolean view(PreviewCamera.View view, String keySuffix)
    {
        GuiSchematics gui = this.gui();
        Button button = KProbe.button(this.previewPane(), KEY_PREVIEW + keySuffix);
        PreviewWidget widget = this.widget();

        if (gui == null || button == null || widget == null)
        {
            this.fail("вид " + view + ": нет кнопки " + KEY_PREVIEW + keySuffix);
            return true;
        }

        this.clickButton(gui, button, this.previewPane());
        this.check("кнопка вида " + view, view, widget.view());
        return true;
    }

    /** Ползунок среза — кликом в долю его ширины (1.0 — верх: срез снят). */
    private boolean cut(SchematicEntry entry, double fraction)
    {
        GuiSchematics gui = this.gui();
        Slider slider = KProbe.fieldOfType(this.previewPane(), Slider.class);
        PreviewWidget widget = this.widget();

        if (gui == null || slider == null || widget == null)
        {
            this.fail("срез " + this.label(entry) + ": нет ползунка");
            return true;
        }

        if (slider.max() <= slider.min() || ! slider.enabled())
        {
            this.info("срез " + this.label(entry) + ": ползунок выключен (один слой) — пропуск");
            // Чтобы «модель среза» не ждала зря — верх уже показан
            this.skipNextCutModel();
            return true;
        }

        double x = fraction >= 1.0 ? slider.x() + slider.w() - 1 : slider.x() + slider.w() * fraction;
        KProbe.click(gui, x, slider.y() + slider.h() / 2.0);

        if (fraction >= 1.0)
        {
            this.check("срез снят " + this.label(entry), Integer.MAX_VALUE, widget.layerCut());
        }
        else
        {
            this.check("срез включён " + this.label(entry) + ": слой " + slider.value() + " из " + slider.max(),
                    widget.layerCut() < Integer.MAX_VALUE);
        }

        return true;
    }

    private void skipNextCutModel()
    {
        // Следующие два шага — «модель среза» и её снимок: модели среза нет, снимаем как есть
        Step next = this.queue.peekFirst();

        if (next != null && next.name().startsWith("модель среза"))
        {
            this.queue.pollFirst();
        }
    }

    /**
     * Нажать кнопку через экран (как мышь). Кнопка за краем прокрутки своей панели или экрана —
     * нажать её саму (INFO): клик в эту точку попал бы в соседнюю панель или подвал.
     */
    void clickButton(GuiBase screen, Button button, @Nullable Object pane)
    {
        double x = button.x() + button.w() / 2.0;
        double y = button.y() + button.h() / 2.0;
        boolean inPane = ! (pane instanceof Widget widget) || (widget.visible() && widget.contains(x, y));
        boolean onScreen = button.visible() && inPane && x >= 0 && y >= 0 && x < screen.width && y < screen.height;

        if (onScreen)
        {
            KProbe.click(screen, x, y);
        }
        else
        {
            this.info("кнопка «" + button.label() + "» вне видимой части панели — нажата напрямую");
            KProbe.pressDirect(button);
        }
    }

    // ===== Материалы: наши строки против Litematica =====

    private final class MaterialsCheck implements Action
    {
        private final SchematicEntry entry;
        private @Nullable CompletableFuture<LoadedSchematic> load;
        private @Nullable CompletableFuture<List<MaterialRow>> rows;
        private long startedAt;
        private long loadedAt;

        private MaterialsCheck(SchematicEntry entry)
        {
            this.entry = entry;
        }

        @Override
        public boolean run(Minecraft mc)
        {
            DumpSchematics k = DumpSchematics.this;

            if (this.load == null)
            {
                this.startedAt = Util.getMillis();
                this.load = SchematicLibrary.get().load(this.entry);
            }

            if (! this.load.isDone())
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
                k.fail("загрузка в Litematica " + k.label(this.entry) + ": " + e);
                return true;
            }

            if (this.rows == null)
            {
                this.loadedAt = Util.getMillis();
                k.loadedByFile.put(this.entry.path(), loaded);
                this.rows = SchematicMaterials.ofSchematic(loaded);
            }

            if (! this.rows.isDone())
            {
                return false;
            }

            List<MaterialRow> ours;

            try
            {
                ours = this.rows.join();
            }
            catch (RuntimeException e)
            {
                k.fail("материалы " + k.label(this.entry) + ": " + SchematicMaterials.describe(e));
                return true;
            }

            long now = Util.getMillis();
            CovOut.log(TAG + "материалы " + k.label(this.entry) + ": загрузка в Litematica " + (this.loadedAt - this.startedAt)
                    + " мс, наши строки " + (now - this.loadedAt) + " мс");
            k.rowsByFile.put(this.entry.path(), ours);
            k.compareMaterials(this.entry, loaded, ours);
            return true;
        }
    }

    /**
     * Сумма по предмету (без компонентов): наши строки против {@code createMaterialListFor} Litematica.
     * Объяснимо: сущности (мы считаем по данным схемы; Litematica 0.29.0 — нет, 0.29.1 — по-своему) в
     * пределах числа сущностей с этим предметом; лиана — у нас по граням. Строки с данными (флаги с
     * узором, вазы, головы) складываются в один предмет — суммы обязаны совпасть.
     */
    private void compareMaterials(SchematicEntry entry, LoadedSchematic loaded, List<MaterialRow> ours)
    {
        boolean detailed = entry.fileName().equals(KData.GENERATED + ".litematic");
        String who = this.label(entry);
        Map<Item, Long> mine = new LinkedHashMap<>();
        Map<Item, Long> theirs = new LinkedHashMap<>();

        for (MaterialRow row : ours)
        {
            mine.merge(row.stack().getItem(), row.total(), Long::sum);
        }

        List<MaterialListEntry> list;

        long litematicaAt = Util.getMillis();

        try
        {
            list = MaterialListUtils.createMaterialListFor(loaded.litematica());
        }
        catch (RuntimeException | LinkageError e)
        {
            this.fail("Litematica createMaterialListFor " + who + ": " + e);
            return;
        }

        CovOut.log(TAG + "материалы " + who + ": createMaterialListFor Litematica " + (Util.getMillis() - litematicaAt) + " мс");

        for (MaterialListEntry row : list)
        {
            if (! row.getStack().isEmpty())
            {
                theirs.merge(row.getStack().getItem(), (long) row.getCountTotal(), Long::sum);
            }
        }

        Map<Item, Integer> fromEntities = entityItems(loaded.litematica());
        Map<String, Item> union = new TreeMap<>();
        mine.keySet().forEach(item -> union.put(id(item), item));
        theirs.keySet().forEach(item -> union.put(id(item), item));

        int same = 0;
        int explained = 0;
        int wrong = 0;

        for (Item item : union.values())
        {
            long a = mine.getOrDefault(item, 0L);
            long b = theirs.getOrDefault(item, 0L);
            long diff = a - b;
            String line = who + " " + id(item) + ": у нас " + a + ", у Litematica " + b;

            if (diff == 0L)
            {
                ++same;

                if (detailed)
                {
                    this.ok("материалы " + line);
                }
            }
            else if (a == 0L && id(item).endsWith("_spawn_egg") && b <= fromEntities.getOrDefault(item, 0))
            {
                // Litematica 0.29.1 считает мобов яйцами призыва; наш счёт сущностей (M20) — только то,
                // что ставится предметом (стойки, рамки, картины, вагонетки…), мобов в списке нет по замыслу
                ++explained;
                this.ok("материалы " + line + " — мобы схемы: у Litematica яйца призыва, у нас мобы не считаются");
            }
            else if (Math.abs(diff) <= fromEntities.getOrDefault(item, 0))
            {
                ++explained;
                this.ok("материалы " + line + " — разница от сущностей (их с этим предметом "
                        + fromEntities.get(item) + ")");
            }
            else if (item == Items.VINE && diff > 0L)
            {
                ++explained;
                this.ok("материалы " + line + " — лиана считается по граням");
            }
            else
            {
                ++wrong;
                this.fail("материалы " + line);
            }
        }

        String summary = "материалы " + who + ": строк у нас " + ours.size() + ", у Litematica " + list.size() + "; предметов "
                + union.size() + ": совпало " + same + ", объяснимо " + explained + ", расходится " + wrong;
        this.check(summary, wrong == 0);

        if (detailed)
        {
            this.generatedRowChecks(ours);
        }
    }

    /** Правила, которые видно только на своём образце: узор флага, лиана на двух гранях, стойка. */
    private void generatedRowChecks(List<MaterialRow> ours)
    {
        long bannerRows = ours.stream().filter(r -> r.stack().is(Items.BANNER.pick(DyeColor.WHITE))).count();
        long bannerTotal = ours.stream().filter(r -> r.stack().is(Items.BANNER.pick(DyeColor.WHITE))).mapToLong(MaterialRow::total).sum();
        long vine = ours.stream().filter(r -> r.stack().is(Items.VINE)).mapToLong(MaterialRow::total).sum();
        MaterialRow stand = ours.stream().filter(r -> r.stack().is(Items.ARMOR_STAND)).findFirst().orElse(null);

        this.check("флаг с узором — своей строкой (строк white_banner)", 2L, bannerRows);
        this.check("флагов всего", 2L, bannerTotal);
        this.check("лиана на двух гранях = 2 лианы", 2L, vine);
        this.check("стойка для брони — строка сущности", true, stand != null && stand.entity() && stand.total() == 1L);
    }

    /** Предметы, которые дают сущности схемы: сама сущность (или её яйцо призыва), предмет в рамке, снаряжение стойки. */
    private static Map<Item, Integer> entityItems(LitematicaSchematic schematic)
    {
        Map<Item, Integer> out = new HashMap<>();

        for (String region : schematic.getAreaSizes().keySet())
        {
            List<LitematicaSchematic.EntityInfo> infos = schematic.getEntityListForRegion(region);

            if (infos == null)
            {
                continue;
            }

            for (LitematicaSchematic.EntityInfo info : infos)
            {
                try
                {
                    CompoundData nbt = info.nbt();
                    String id = nbt.getString("id");
                    countItem(out, id);
                    // Моб — у Litematica строка яйца призыва
                    countItem(out, id + "_spawn_egg");

                    if (id.endsWith("cushion") && nbt.contains("color", 8))
                    {
                        countItem(out, "minecraft:" + nbt.getString("color") + "_cushion");
                    }

                    if (nbt.contains("Item", 10))
                    {
                        countItem(out, nbt.getCompound("Item").getString("id"));
                    }

                    if (nbt.contains("equipment", 10))
                    {
                        CompoundData equipment = nbt.getCompound("equipment");

                        for (String slot : equipment.getKeys())
                        {
                            if (equipment.contains(slot, 10))
                            {
                                countItem(out, equipment.getCompound(slot).getString("id"));
                            }
                        }
                    }
                }
                catch (RuntimeException e)
                {
                    // Битая запись сущности — не предмет
                }
            }
        }

        return out;
    }

    private static void countItem(Map<Item, Integer> out, @Nullable String id)
    {
        Identifier key = id == null || id.isEmpty() ? null : Identifier.tryParse(id);

        if (key != null)
        {
            BuiltInRegistries.ITEM.getOptional(key).filter(item -> item != Items.AIR).ifPresent(item -> out.merge(item, 1, Integer::sum));
        }
    }

    static String id(Item item)
    {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    // ===== Подвкладка «Материалы» и окно выгрузки =====

    private boolean openMaterialsTab(Minecraft mc)
    {
        GuiSchematics gui = this.gui();
        Button button = KProbe.button(this.infoPane(), "layerlist.schematics.info.materials");

        if (gui == null || button == null)
        {
            this.fail("кнопка «Материалы» карточки не найдена");
            return true;
        }

        this.clickButton(gui, button, this.infoPane());

        try
        {
            if (! this.check("кнопка «Материалы» открыла подвкладку", "MATERIALS", String.valueOf(KProbe.call(gui, "tab"))))
            {
                // Дальше сценарий должен идти: жмём кнопку саму
                KProbe.pressDirect(button);
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("tab(): " + e);
        }

        return true;
    }

    /** Ждём строк, если интерфейс таблицы уже есть; у заглушки — короткая пауза и INFO. */
    private final class MaterialsTabRows implements Action
    {
        @Override
        public boolean run(Minecraft mc)
        {
            DumpSchematics k = DumpSchematics.this;
            Object pane = KProbe.fieldNamedType(k.gui(), "MaterialsPane");
            int rows = KProbe.materialRows(pane);
            long waited = Util.getMillis() - k.startedAt;

            if (rows == -1 && waited > 3_000L)
            {
                k.info("подвкладка «Материалы» без таблицы строк (заглушка) — снимок как есть");
                return true;
            }

            if (rows > 0)
            {
                SchematicEntry generated = k.generated(".litematic");
                List<MaterialRow> expected = generated == null ? null : k.rowsByFile.get(generated.path());
                k.check("строк в подвкладке «Материалы»", expected == null ? rows : expected.size(), rows);
                return true;
            }

            return false;
        }
    }

    boolean openExportDialog(Minecraft mc)
    {
        GuiSchematics gui = this.gui();
        Button button = KProbe.cardExport(this.infoPane());
        Layers layers = KProbe.fieldOfType(gui, Layers.class);

        if (gui == null || button == null || layers == null)
        {
            this.fail("кнопка «Выгрузить» или слои экрана не найдены");
            return true;
        }

        this.clickButton(gui, button, this.infoPane());

        // С 0.0.26 «Выгрузить ▾» открывает меню форматов, а окно — пункт меню
        if (! this.check("«Выгрузить ▾» открыло меню форматов", layers.top() instanceof ContextMenu))
        {
            KProbe.pressDirect(button);
        }

        if (layers.top() instanceof ContextMenu)
        {
            // ↓ — первый пункт (Excel), Enter — окно с этим форматом; сам формат окно по Enter не жмёт
            KProbe.press(gui, InputConstants.KEY_DOWN);
            KProbe.press(gui, InputConstants.KEY_RETURN);
        }

        this.check("пункт меню открыл окно выгрузки", layers.top() instanceof ExportWindow);
        return true;
    }

    boolean closeExportDialog(Minecraft mc)
    {
        GuiSchematics gui = this.gui();
        Layers layers = KProbe.fieldOfType(gui, Layers.class);

        if (gui == null || layers == null)
        {
            this.fail("экран «Схемы» закрыт");
            return true;
        }

        // Первый Esc может только снять фокус с поля окна (как в самом экране) — не больше трёх
        int presses = 0;

        while (layers.any() && presses < 3)
        {
            KProbe.press(gui, InputConstants.KEY_ESCAPE);
            ++presses;
        }

        this.check("Esc закрыл окно выгрузки (нажатий " + presses + "), экран остался", ! layers.any() && this.gui() == gui);
        return true;
    }

    // ===== Выгрузки =====

    private ExportRequest request(String title, String source, List<MaterialRow> rows)
    {
        return new ExportRequest(title, source, rows, List.of("Aell_", "Vi", "Drimofey"), ExportOptions.DEFAULT);
    }

    private boolean runExports()
    {
        SchematicEntry generated = this.generated(".litematic");
        List<MaterialRow> rows = generated == null ? null : this.rowsByFile.get(generated.path());

        if (rows == null || rows.isEmpty())
        {
            this.fail("выгрузки: нет строк материалов k-generated.litematic");
            return true;
        }

        // Без Exporters.preload(): рецепты для «Крупного сырья» читаются тут же, синхронно — без гонки с фоном
        ExportRequest r = this.request(KData.GENERATED, "Схема: " + KData.GENERATED, rows);

        this.checkTable("xlsx", Exporters.xlsx(r));
        this.checkTable("Google (xlsx)", Exporters.google(r));
        this.checkFiles("CSV", Exporters.csv(r), KExports::csv);

        ExportResult tsv = Exporters.tsv(r);
        this.checkText("TSV (буфер → файл)", tsv, "tsv.txt");

        int parts = Exporters.discordParts(r);
        this.check("Discord: частей", true, parts >= 1);

        for (int part = 0; part < parts; ++part)
        {
            ExportResult discord = Exporters.discord(r, part);
            this.checkText("Discord, часть " + (part + 1), discord, "discord-" + (part + 1) + ".txt");
            this.check("Discord, часть " + (part + 1) + " ≤ 2000 символов", true,
                    discord.clipboard() != null && discord.clipboard().length() <= 2000);
        }

        this.checkFiles("PNG", Exporters.png(r), file -> KExports.png(file) == null ? "не PNG" : null);
        this.checkFiles("JSON", Exporters.json(r), KExports::json);

        ExportResult litematica = Exporters.litematica(r);
        this.checkFiles("список Litematica", litematica,
                file -> file.toString().endsWith(".txt") ? KExports.litematicaTxt(file) : KExports.json(file));
        this.copyLitematicaLists(litematica);
        return true;
    }

    /**
     * Иконки рисует игра в кадре GUI и отдаёт через кадр-два, а выгрузки ниже (перегрузки без набора)
     * берут только готовые из кеша: заказываем иконки строк обеих выгрузок заранее и ждём.
     */
    private boolean warmExportIcons()
    {
        if (this.exportIcons == null)
        {
            List<MaterialRow> rows = new ArrayList<>();
            SchematicEntry generated = this.generated(".litematic");

            if (generated != null && this.rowsByFile.containsKey(generated.path()))
            {
                rows.addAll(this.rowsByFile.get(generated.path()));
            }

            SchematicEntry biggest = this.biggestReal();

            if (biggest != null)
            {
                rows.addAll(this.rowsByFile.get(biggest.path()));
            }

            this.exportIcons = IconBatch.of(this.request("icons", "icons", rows));
            this.info("иконки выгрузок: заказано " + this.exportIcons.total());
        }

        if (! this.exportIcons.step(6L))
        {
            return false;
        }

        this.check("иконки выгрузок пришли", this.exportIcons.total(), this.exportIcons.done());
        return true;
    }

    private @Nullable SchematicEntry biggestReal()
    {
        SchematicEntry biggest = null;

        for (SchematicEntry entry : this.listed)
        {
            if (entry.format() == SchematicFormat.LITEMATIC && ! entry.fileName().startsWith(KData.GENERATED)
                    && this.rowsByFile.containsKey(entry.path()) && (biggest == null || entry.size() > biggest.size()))
            {
                biggest = entry;
            }
        }

        return biggest;
    }

    /** Средняя настоящая схема — больше строк (несколько картинок, длинный Discord). */
    private boolean runMediumExports()
    {
        SchematicEntry biggest = null;

        for (SchematicEntry entry : this.listed)
        {
            if (entry.format() == SchematicFormat.LITEMATIC && ! entry.fileName().startsWith(KData.GENERATED)
                    && this.rowsByFile.containsKey(entry.path()) && (biggest == null || entry.size() > biggest.size()))
            {
                biggest = entry;
            }
        }

        if (biggest == null)
        {
            this.info("выгрузки средней схемы: настоящих .litematic нет — пропуск");
            return true;
        }

        String title = biggest.name();
        ExportRequest r = this.request(title, "Схема: " + title, this.rowsByFile.get(biggest.path()));
        this.checkTable("xlsx " + this.label(biggest), Exporters.xlsx(r));
        this.checkFiles("PNG " + this.label(biggest), Exporters.png(r), file -> KExports.png(file) == null ? "не PNG" : null);
        this.checkFiles("CSV " + this.label(biggest), Exporters.csv(r), KExports::csv);
        return true;
    }

    private void checkTable(String what, ExportResult result)
    {
        this.checkFiles(what, result, KExports::xlsx);
    }

    @FunctionalInterface
    private interface FileCheck
    {
        @Nullable String problem(Path file);
    }

    private void checkFiles(String what, ExportResult result, FileCheck check)
    {
        if (! result.ok() || result.files().isEmpty())
        {
            this.fail("выгрузка " + what + ": " + (result.error() != null ? result.error() : "нет файлов"));
            return;
        }

        for (Path file : result.files())
        {
            String problem = KExports.nonEmpty(file) ? check.problem(file) : "файла нет или он пуст";
            this.check("выгрузка " + what + ": " + file.getFileName() + (problem == null ? "" : " — " + problem), problem == null);
        }
    }

    private void checkText(String what, ExportResult result, String fileName)
    {
        String text = result.clipboard();

        if (! result.ok() || text == null)
        {
            this.fail("выгрузка " + what + ": " + (result.error() != null ? result.error() : "буфер пуст"));
            return;
        }

        try
        {
            Files.writeString(this.exports.resolve(fileName), text, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            this.info("не записать " + fileName + ": " + e);
        }

        String problem = KExports.words(text);
        this.check("выгрузка " + what + ": " + text.length() + " символов" + (problem == null ? "" : " — " + problem),
                problem == null);
    }

    /** Список для Litematica пишется в {@code <схемы>/material-lists}: копия — к выгрузкам, оригинал убираем. */
    private void copyLitematicaLists(ExportResult result)
    {
        Path target = this.exports.resolve("litematica");

        try
        {
            Files.createDirectories(target);

            for (Path file : result.files())
            {
                if (Files.isRegularFile(file))
                {
                    Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(file);
                }
            }
        }
        catch (IOException e)
        {
            this.info("список Litematica не скопирован: " + e);
        }
    }

    /** Иконки выгрузок (M25) на наборе из MODULES.md и разбивка §7 на эталонных числах. */
    private boolean iconsAndBreakdown()
    {
        Path icons = this.exports.resolve("icons");

        try
        {
            Files.createDirectories(icons);
        }
        catch (IOException e)
        {
            this.fail("папка иконок: " + e);
            return true;
        }

        List<Item> set = List.of(Items.STONE, Items.GRASS_BLOCK, Items.OAK_STAIRS, Items.OAK_SLAB, Items.GLASS, Items.OAK_LEAVES,
                Items.TORCH, Items.POTION, Items.LEATHER_CHESTPLATE, Items.BANNER.pick(DyeColor.WHITE), Items.CHEST, Items.SHULKER_BOX,
                Items.PLAYER_HEAD);

        // Иконки рисует игра в кадре GUI: заказ, потом ждём, пока придут все (шаг повторяется)
        if (this.iconOrders == null)
        {
            this.iconOrders = new ArrayList<>();

            for (Item item : set)
            {
                this.iconOrders.add(ItemIcons.request(new ItemStack(item), 64));
            }
        }

        for (CompletableFuture<byte[]> order : this.iconOrders)
        {
            if (! order.isDone())
            {
                return false;
            }
        }

        for (int i = 0; i < set.size(); ++i)
        {
            String name = BuiltInRegistries.ITEM.getKey(set.get(i)).getPath();

            try
            {
                byte[] png = this.iconOrders.get(i).join();
                Files.write(icons.resolve(name + ".png"), png);
                this.check("иконка " + name, "64×64", Objects.requireNonNullElse(KExports.png(png), "не PNG"));
            }
            catch (IOException | RuntimeException e)
            {
                this.fail("иконка " + name + ": " + e);
            }
        }

        MaterialRow.Units units = MaterialRow.Units.words("шалк.", "ст.", "шт");
        this.check("разбивка 3000/64", "1 шалк. + 19 ст. + 56 шт", MaterialRow.breakdown(3000, 64, units));
        this.check("разбивка 1675/64", "26 ст. + 11 шт", MaterialRow.breakdown(1675, 64, units));
        this.check("разбивка 1/64", "1 шт", MaterialRow.breakdown(1, 64, units));
        this.check("разбивка 0/64", "0 шт", MaterialRow.breakdown(0, 64, units));
        this.check("разбивка 57/1", "2 шалк. + 3 шт", MaterialRow.breakdown(57, 1, units));
        return true;
    }

    // ===== Размеры окна и масштабы =====

    private List<Step> variants()
    {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("GUI 3", 8_000L, this.window(1600, 900, 3)));
        steps.add(new Step("снова k-generated (GUI 3)", 5_000L, mc -> this.selectGenerated()));
        steps.add(new Step("модель (GUI 3)", 60_000L, mc -> this.previewReadyGenerated()));
        steps.add(new Step("снимок «Библиотека» GUI 3", 5_000L, this.settleShot("s3-library")));
        steps.add(new Step("окно выгрузки GUI 3", 5_000L, this::openExportDialog));
        steps.add(new Step("снимок окна выгрузки GUI 3", 5_000L, this.settleShot("s3-export-dialog")));
        steps.add(new Step("Esc закрывает окно выгрузки GUI 3", 3_000L, this::closeExportDialog));
        steps.add(new Step("«Материалы» GUI 3", 2_000L, mc -> this.setTab("MATERIALS")));
        steps.add(new Step("снимок «Материалы» GUI 3", 5_000L, this.settleShot("s3-materials")));
        steps.add(new Step("«Размещения» GUI 3", 2_000L, mc -> this.setTab("PLACEMENTS")));
        steps.add(new Step("снимок «Размещения» GUI 3", 5_000L, this.settleShot("s3-placements")));
        steps.add(new Step("«Библиотека» GUI 3", 2_000L, mc -> this.setTab("LIBRARY")));
        steps.addAll(this.place.topViewAtScale3());
        steps.add(new Step("узкое окно 800×900, GUI 2", 8_000L, this.window(800, 900, 2)));
        steps.add(new Step("снимок узкого окна: файлы", 5_000L, this.settleShot("narrow-files")));
        steps.add(new Step("узкое окно: «Просмотр»", 3_000L, mc -> this.narrowView()));
        steps.add(new Step("снимок узкого окна: просмотр", 5_000L, this.settleShot("narrow-view")));
        steps.add(new Step("узкое окно: «Просмотр» выбран", 2_000L, mc -> this.narrowViewSelected()));
        steps.add(new Step("окно 1200×900, GUI 2", 8_000L, this.window(1200, 900, 2)));
        steps.add(new Step("снимок средней ширины", 5_000L, this.settleShot("medium-library")));
        steps.add(new Step("окно 1600×900, GUI 2 — обратно", 8_000L, this.window(1600, 900, 2)));
        return steps;
    }

    boolean previewReadyGenerated()
    {
        SchematicEntry generated = this.generated(".litematic");
        return generated == null || this.previewReady(generated, Integer.MAX_VALUE);
    }

    boolean narrowView()
    {
        GuiSchematics gui = this.gui();
        Button view = KProbe.button(gui, "layerlist.schematics.narrow.view");

        if (gui == null || view == null)
        {
            this.fail("узкое окно: кнопки «Просмотр» нет");
            return true;
        }

        if (! view.visible() || gui.width >= 420)
        {
            this.info("узкое окно: экран " + gui.width + " px — раскладка не одноколоночная, «Просмотр» не нужен");
            return true;
        }

        this.clickButton(gui, view, null);
        return true;
    }

    /** Отметка кнопки ставится раскладкой кадра — проверяем после снимка. */
    private boolean narrowViewSelected()
    {
        GuiSchematics gui = this.gui();
        Button view = KProbe.button(gui, "layerlist.schematics.narrow.view");

        if (gui != null && view != null && gui.width < 420)
        {
            this.check("узкое окно: «Просмотр» выбран", view.isSelected());
        }

        return true;
    }

    private boolean closeScreen()
    {
        Nav.clear();
        GuiBase.openGui(null);
        this.check("экран закрыт", GuiUtils.getCurrentScreen() == null);
        return true;
    }

    // ===== Снимки и отчёт =====

    void shot(Minecraft mc, String name)
    {
        String file = String.format(Locale.ROOT, "k-%02d-%s.png", ++this.shotNo, name);
        Path path = this.shots.resolve(file);

        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image ->
        {
            try (NativeImage owned = image)
            {
                owned.writeToFile(path);
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log(TAG + "снимок " + file + " не записан: " + e);
            }
        });

        CovOut.log(TAG + "снимок " + file + " (" + mc.getWindow().getWidth() + "×" + mc.getWindow().getHeight() + ", GUI "
                + mc.options.guiScale().get() + ")");
    }

    /** Снимки пишутся после чтения кадра с видеокарты — ждём, пока лягут все. */
    private boolean shotsWritten()
    {
        long written;

        try (Stream<Path> files = Files.list(this.shots))
        {
            written = files.filter(p -> p.toString().endsWith(".png")).count();
        }
        catch (IOException e)
        {
            this.fail("папка снимков: " + e);
            return true;
        }

        if (written < this.shotNo && Util.getMillis() - this.startedAt < 8_000L)
        {
            return false;
        }

        this.check("снимков записано", (long) this.shotNo, written);
        return true;
    }

    void ok(String what)
    {
        ++this.ok;
        this.line("OK  ", what);
    }

    void fail(String what)
    {
        ++this.failed;
        this.line("FAIL", what);
    }

    void info(String what)
    {
        this.line("INFO", what);
    }

    boolean check(String what, boolean pass)
    {
        if (pass)
        {
            this.ok(what);
        }
        else
        {
            this.fail(what);
        }

        return pass;
    }

    boolean check(String what, Object expected, Object actual)
    {
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(actual));
        return this.check(what + (pass ? " = " + actual : " — ждали " + expected + ", вышло " + actual), pass);
    }

    private void line(String result, String what)
    {
        CovOut.log(TAG + result + " " + what);

        if (this.report != null)
        {
            try
            {
                this.report.write(CovOut.escape(what) + "," + result.strip() + ",\n");
                this.report.flush();
            }
            catch (IOException e)
            {
                CovOut.log(TAG + "отчёт не пишется: " + e);
            }
        }
    }

    long startedAt()
    {
        return this.startedAt;
    }

    @Nullable LoadedSchematic loaded(Path path)
    {
        return this.loadedByFile.get(path);
    }

    /** Строки материалов файла, посчитанные шагами по файлам; {@code null} — не считались. */
    @Nullable List<MaterialRow> rowsOf(Path path)
    {
        return this.rowsByFile.get(path);
    }

    /** Папка выгрузок прогона ({@code LL_COVERAGE_OUT/exports}). */
    Path exportsDir()
    {
        return this.exports;
    }

    /** Папка выхода прогона. */
    Path outDir()
    {
        return this.out;
    }

    /** Папка схем прогона ({@code <схемы>/_k}). */
    Path kDir()
    {
        return this.kDir;
    }

}
