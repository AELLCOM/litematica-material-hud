package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportNames;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.export.ExportsFolder;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.ItemKeys;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27: имена файлов выгрузки по схеме и кнопка «Папка с выгрузками».
 * <ul>
 *   <li>имя файла — по источнику: файл «Библиотеки» — его имя, размещение — файл его схемы (не имя
 *       размещения), список HUD — схемы включённых размещений («k-generated», «k-generated + ещё 1»,
 *       без размещений — «Список HUD»);</li>
 *   <li>занятое имя — «(2)», «(3)»…, у картинки на две страницы — по всему набору («(часть 2)»);</li>
 *   <li>очистка под Windows: запрещённые и управляющие знаки, края, имена устройств, длина по слову,
 *       пустое — «Материалы»; кириллица остаётся;</li>
 *   <li>как игрок: «Материалы» размещения → Excel в подвале → окно → Enter; файл назван по схеме,
 *       кнопка папки горит ≈ 1, через 15 с (сдвиг часов свечения) — 0,2…0,8, через 31 с — 0; снимки
 *       окна и подвала «Материалов».</li>
 * </ul>
 * Выгрузки — в своей папке {@code exports/naming}: имена должны быть предсказуемы.
 */
@Environment(EnvType.CLIENT)
final class KNaming
{
    private static final String GENERATED = KData.GENERATED;
    private static final String SECOND = "k-second";
    private static final String PLACEMENT_NAME = "Мой дом";
    private static final String TITLE = "Моя таблица";
    private static final String KEY_FILE = "layerlist.schematics.export.file.";
    private static final ExportOptions FAST = new ExportOptions(false, false, false, false);
    private static final int PNG_ROWS = 70;
    private static final Pattern SHEET = Pattern.compile("<sheet\\b[^>]*\\bname=\"([^\"]*)\"");

    private final DumpSchematics k;
    private @Nullable String exportDirBefore;
    private Path dir = Path.of(".");
    /** Сколько раз выгружено «имя + расширение» в {@link #dir}: отсюда ждём «(2)», «(3)»… */
    private final Map<String, Integer> written = new HashMap<>();
    private final List<SchematicPlacement> made = new ArrayList<>();
    private @Nullable SchematicPlacement placement;
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable CompletableFuture<LoadedSchematic> loadingSecond;
    private @Nullable Path lastBefore;

    KNaming(DumpSchematics k)
    {
        this.k = k;
    }

    // ===== Сценарий =====

    /** {@code p} — префикс снимков и шагов ({@code s3-} при GUI 3); проверки имён без экрана — только при пустом. */
    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "имена: своя папка выгрузок", 3_000L, mc -> this.useOwnFolder(p.isEmpty())));

        if (p.isEmpty())
        {
            s.add(step("имена: схема «Библиотеки», занятое имя → (2)", 60_000L, mc -> this.schematicNames()));
            s.add(step("имена: картинка на две страницы", 30_000L, mc -> this.pngParts()));
            s.add(step("имена: список для Litematica", 10_000L, mc -> this.litematicaNames()));
            s.add(step("имена: очистка под Windows", 20_000L, mc -> this.sanitising()));
            s.add(step("имена: список HUD без размещений", 5_000L, mc -> this.hudNone()));
        }

        s.add(step(p + "имена: размещение k-generated", 30_000L, mc -> this.placeGenerated()));

        if (p.isEmpty())
        {
            s.add(step("имена: список HUD, одно размещение", 5_000L, mc -> this.hudOne()));
            s.add(step("имена: список HUD, два размещения одной схемы", 5_000L, mc -> this.hudSameTwice()));
            s.add(step("имена: список HUD, две схемы", 30_000L, mc -> this.hudTwo()));
        }

        s.add(step(p + "имена: «Материалы» по размещению", 5_000L, mc -> this.showPlacement()));
        s.add(step(p + "имена: строки «Материалов» размещения", 40_000L, mc -> this.placementRows()));
        s.add(step(p + "мышь в сторону (снимки без подсказок)", 3_000L, mc -> this.mouseAside(mc)));
        s.add(step(p + "подвал «Материалов»: кнопка папки на месте", 5_000L,
                this.k.settleThen(400L, mc -> this.materialsFooter(false))));
        s.add(step(p + "снимок: подвал «Материалов» до выгрузки", 5_000L, this.k.settleShot(p + "folder-materials")));
        s.add(step(p + "«Ещё ▾» подвала: меню форматов", 3_000L, mc -> this.openMore()));
        s.add(step(p + "снимок: меню «Ещё ▾» подвала", 5_000L, this.k.settleShot(p + "materials-more")));
        s.add(step(p + "Esc закрывает меню «Ещё ▾»", 3_000L, mc -> this.closeMenu()));
        s.add(step(p + "Excel кнопкой подвала → окно выгрузки", 5_000L, mc -> this.openWindow()));
        s.add(step(p + "окно: имя файла — схема размещения, кнопка папки", 3_000L,
                this.k.settleThen(300L, mc -> this.windowChecks())));
        s.add(step(p + "снимок: окно выгрузки до выгрузки (сетка форматов)", 5_000L, this.k.settleShot(p + "export-grid")));
        s.add(step(p + "окно: Enter — выгрузить", 3_000L, mc -> this.k.inline.markToasts() && this.pressEnter()));
        s.add(step(p + "окно: файл записан, кнопка папки горит", 40_000L, mc -> this.exported()));
        // 0.0.27: итог — в строке окна с кнопками, тостов нет (тост ложился на «Закрыть» и кнопку папки)
        s.add(step(p + "окно: итог в строке окна, тостов нет", 3_000L,
                this.k.settleThen(300L, mc -> this.k.inline.resultInWindow(ExportsFolder.TestHooks.lastFile()))));
        s.add(step(p + "снимок: окно выгрузки, кнопка горит", 5_000L, this.k.settleShot(p + "folder-export-glow")));
        s.add(step(p + "окно: «Показать в папке» мышью", 3_000L,
                mc -> this.k.inline.clickStatusAction("layerlist.schematics.export.dialog.reveal")));
        s.add(step(p + "окно: «Показать в папке» выделит файл", 3_000L,
                this.k.settleThen(300L, mc -> this.k.inline.revealed(ExportsFolder.TestHooks.lastFile(), true))));
        s.add(step(p + "снимок: наведение на «Показать в папке»", 5_000L, this.k.settleShot(p + "export-inline-hover")));
        s.add(step(p + "мышь в сторону (после клика)", 3_000L, mc -> this.mouseAside(mc)));
        s.add(step(p + "часы +15 с: кнопка гаснет", 3_000L, mc -> this.age(15_000L, 0.2F, 0.8F)));
        s.add(step(p + "снимок: +15 с", 5_000L, this.k.settleShot(p + "folder-export-15s")));
        s.add(step(p + "часы +31 с: кнопка погасла", 3_000L, mc -> this.age(31_000L, 0.0F, 0.0F)));
        s.add(step(p + "снимок: +31 с", 5_000L, this.k.settleShot(p + "folder-export-31s")));
        s.add(step(p + "часы настоящие", 3_000L, mc -> this.realClock()));
        s.add(step(p + "Esc закрывает окно выгрузки (имена)", 3_000L, mc -> this.k.closeExportDialog(mc)));
        s.add(step(p + "подвал «Материалов»: кнопка горит", 5_000L, this.k.settleThen(300L, mc -> this.materialsFooter(true))));
        s.add(step(p + "снимок: подвал «Материалов», кнопка горит", 5_000L, this.k.settleShot(p + "folder-materials-glow")));
        s.add(step(p + "мышь на кнопку папки", 3_000L, mc -> this.mouseOnFolder(mc)));
        s.add(step(p + "снимок: подсказка кнопки папки", 5_000L, this.k.settleShot(p + "folder-tooltip")));
        s.add(step(p + "мышь в сторону", 3_000L, mc -> this.mouseAside(mc)));
        // 0.0.27: окно закрыли раньше, чем записался файл, — итог тостом экрана над подвалом
        s.add(step(p + "окно выгрузки снова (подвал «Материалов»)", 5_000L, mc -> this.openWindow()));
        s.add(step(p + "окно: CSV и сразу Esc", 5_000L, mc -> this.k.inline.csvThenClose(mc)));
        s.add(step(p + "без окна: итог CSV тостом над подвалом", 20_000L, mc -> this.k.inline.closedToast()));
        s.add(step(p + "снимок: итог тостом после закрытия окна", 5_000L, this.k.settleShot(p + "export-closed-toast")));
        s.add(step(p + "тост: «Показать в папке» мышью", 3_000L,
                mc -> this.k.inline.clickToastAction("layerlist.schematics.export.dialog.reveal")));
        s.add(step(p + "тост: «Показать в папке» выделит файл", 3_000L, this.k.settleThen(300L, mc -> this.k.inline.revealedLast())));
        s.add(step(p + "тосты убраны, мышь в сторону", 3_000L, mc -> this.k.inline.clearToasts() && this.mouseAside(mc)));
        s.add(step(p + "имена: уборка", 5_000L, mc -> this.tidy()));
        return s;
    }

    void cleanup()
    {
        ExportsFolder.TestHooks.shiftClock(0L);

        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();

        if (this.exportDirBefore != null)
        {
            SchematicsConfig.EXPORT_DIR.setValueFromString(this.exportDirBefore);
            this.exportDirBefore = null;
        }
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    // ===== Выгрузки без экрана =====

    private boolean useOwnFolder(boolean fresh)
    {
        this.dir = this.k.exportsDir().resolve("naming");

        if (fresh)
        {
            try
            {
                KData.recreate(this.dir);
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail("папка exports/naming: " + e);
            }

            this.written.clear();
        }

        if (this.exportDirBefore == null)
        {
            this.exportDirBefore = SchematicsConfig.EXPORT_DIR.getStringValue();
        }

        SchematicsConfig.EXPORT_DIR.setValueFromString(this.dir.toString());
        this.k.check("папка выгрузок — exports/naming", this.dir.toAbsolutePath().normalize(), SchematicsConfig.exportDir());
        // Кнопки «Показать в папке» дальше жмутся мышью — проводник открываться не должен
        this.k.inline.intercept();
        return true;
    }

    private @Nullable List<MaterialRow> generatedRows()
    {
        SchematicEntry generated = this.k.generated(".litematic");
        List<MaterialRow> rows = generated == null ? null : this.k.rowsOf(generated.path());

        if (rows == null || rows.isEmpty())
        {
            this.k.fail("имена: нет строк материалов k-generated.litematic");
            return null;
        }

        return rows;
    }

    private static ExportRequest request(String fileName, List<MaterialRow> rows, ExportOptions options)
    {
        return new ExportRequest(TITLE, "Схема: " + fileName, rows, List.of("Aell_"), options, fileName);
    }

    /** Какое имя должна выбрать выгрузка в чистой папке: первое — как есть, дальше — «(2)», «(3)»… */
    private String next(String base, String extension)
    {
        int n = this.written.merge(base + extension, 1, Integer::sum);

        return n == 1 ? base + extension : base + " (" + n + ")" + extension;
    }

    private void expect(String what, ExportResult result, String... names)
    {
        if (! result.ok() || result.files().isEmpty())
        {
            this.k.fail("имена: " + what + ": " + (result.error() != null ? result.error() : "нет файлов"));
            return;
        }

        List<String> actual = new ArrayList<>();

        for (Path file : result.files())
        {
            actual.add(file.getFileName().toString());
        }

        this.k.check("имя файла: " + what, List.of(names), actual);

        for (Path file : result.files())
        {
            if (! KExports.nonEmpty(file))
            {
                this.k.fail("имена: " + what + ": файла " + file.getFileName() + " нет или он пуст");
            }
        }
    }

    /** Файлы выгрузки — в папке {@code folder} внутри папки выгрузок, по именам {@code names}. */
    private void expectInFolder(String what, ExportResult result, String folder, String... names)
    {
        if (! result.ok() || result.files().isEmpty())
        {
            this.k.fail("имена: " + what + ": " + (result.error() != null ? result.error() : "нет файлов"));
            return;
        }

        List<String> actual = new ArrayList<>();
        List<String> expected = new ArrayList<>();

        for (Path file : result.files())
        {
            Path parent = file.getParent();
            actual.add((parent == null ? "" : this.dir.relativize(parent) + "/") + file.getFileName());
        }

        for (String name : names)
        {
            expected.add(folder + "/" + name);
        }

        this.k.check("имя файла: " + what, expected, actual);
    }

    private boolean schematicNames()
    {
        SchematicEntry entry = this.k.generated(".litematic");
        List<MaterialRow> rows = this.generatedRows();

        if (entry == null || rows == null)
        {
            return true;
        }

        this.k.check("имя схемы «Библиотеки» = как в списке", GENERATED, ExportNames.of(entry));
        this.k.check("источник «Схема»: имя файла", GENERATED, ExportSource.schematic(entry).fileName());

        // Название таблицы другое — файл всё равно по схеме, а лист — по названию
        ExportRequest r = request(ExportNames.of(entry), rows, ExportOptions.DEFAULT);
        ExportResult first = Exporters.xlsx(r);
        this.expect("Excel по схеме, а не по названию «" + TITLE + "»", first, this.next(GENERATED, ".xlsx"));
        this.k.check("Excel: лист назван по названию таблицы", TITLE, first.files().isEmpty() ? "" : sheetName(first.files().getFirst()));
        this.expect("Excel ещё раз — «(2)», прежний не затёрт", Exporters.xlsx(r), this.next(GENERATED, ".xlsx"));
        this.k.check("прежний Excel на месте", true, ! first.files().isEmpty() && KExports.nonEmpty(first.files().getFirst()));
        this.expect("Google (тот же .xlsx) — «(3)»", Exporters.google(r), this.next(GENERATED, ".xlsx"));
        this.expect("CSV", Exporters.csv(r), this.next(GENERATED, ".csv"));
        this.expect("CSV ещё раз", Exporters.csv(r), this.next(GENERATED, ".csv"));
        this.expect("JSON", Exporters.json(r), this.next(GENERATED, ".json"));

        // Запрос прежнего вида (без имени файла) — файл по названию таблицы, как до 0.0.27
        ExportRequest old = new ExportRequest("Старое имя", "Схема", rows, List.of(), FAST);
        this.expect("запрос без имени файла — по названию", Exporters.csv(old), this.next("Старое имя", ".csv"));
        return true;
    }

    private boolean pngParts()
    {
        List<MaterialRow> rows = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM)
        {
            if (rows.size() >= PNG_ROWS)
            {
                break;
            }

            if (item instanceof BlockItem)
            {
                ItemStack stack = new ItemStack(item);
                long total = 9L + rows.size() * 13L;
                rows.add(new MaterialRow(stack, ItemKeys.of(stack), stack.getHoverName().getString(), total, total, 0L, 0L,
                        stack.getMaxStackSize(), false));
            }
        }

        // Картинка — своей папкой по схеме, страницы 1.png, 2.png… (0.0.27); прежнюю папку не трогаем
        ExportRequest r = request(GENERATED, rows, FAST);
        ExportResult first = Exporters.png(r);
        this.expectInFolder("картинка на 2 страницы — папка по схеме", first, GENERATED, "1.png", "2.png");
        this.expectInFolder("картинка ещё раз — новая папка «(2)»", Exporters.png(r), GENERATED + " (2)", "1.png", "2.png");

        try
        {
            // Папка с таким именем уже есть (пустая или чужая) — в неё не пишем, берём следующее имя
            Files.createDirectories(this.dir.resolve(GENERATED + " (3)"));
        }
        catch (IOException e)
        {
            this.k.info("папку «(3)» не создать: " + e);
        }

        this.expectInFolder("папка «(3)» уже есть — «(4)»", Exporters.png(r), GENERATED + " (4)", "1.png", "2.png");

        List<MaterialRow> few = this.generatedRows();

        if (few != null && few.size() <= 60)
        {
            this.expectInFolder("одна страница — тоже папка и 1.png", Exporters.png(request("k-one-page", few, FAST)),
                    "k-one-page", "1.png");
        }

        this.k.check("PNG: картинки читаются", true, ! first.files().isEmpty()
                && first.files().stream().allMatch(f -> KExports.png(f) != null));
        this.k.check("PNG: «Показать в папке» и кнопка папки — папка картинок", this.dir.resolve(GENERATED),
                first.files().isEmpty() ? "" : ExportWindow.revealTarget(
                        com.aell.layerlist.schematics.gui.export.ExportFormat.PNG, first.files()));
        this.k.check("Excel: «Показать в папке» — сам файл", this.dir.resolve("x.xlsx"), ExportWindow.revealTarget(
                com.aell.layerlist.schematics.gui.export.ExportFormat.XLSX, List.of(this.dir.resolve("x.xlsx"))));
        return true;
    }

    private boolean litematicaNames()
    {
        List<MaterialRow> rows = this.generatedRows();

        if (rows == null)
        {
            return true;
        }

        Path lists = SchematicLibrary.get().root().resolve("material-lists");

        // Списки харнесса прошлых прогонов (если прогон оборвался) — иначе ждали бы «(2)»
        for (String name : List.of(GENERATED + ".txt", GENERATED + ".json"))
        {
            try
            {
                if (Files.deleteIfExists(lists.resolve(name)))
                {
                    this.k.info("список прошлого прогона убран: " + name);
                }
            }
            catch (IOException e)
            {
                this.k.info("не убрать " + name + ": " + e);
            }
        }

        ExportResult result = Exporters.litematica(request(GENERATED, rows, FAST));
        this.expect("список для Litematica", result, GENERATED + ".txt", GENERATED + ".json");

        Path target = this.dir.resolve("litematica");

        try
        {
            Files.createDirectories(target);

            for (Path file : result.files())
            {
                if (Files.isRegularFile(file))
                {
                    Files.move(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        catch (IOException e)
        {
            this.k.info("список Litematica не перенесён: " + e);
        }

        return true;
    }

    private boolean sanitising()
    {
        List<MaterialRow> rows = this.generatedRows();

        if (rows == null)
        {
            return true;
        }

        StringBuilder longName = new StringBuilder();
        List<String> words = new ArrayList<>();

        for (int i = 1; i <= 20; ++i)
        {
            String word = String.format("слово%02d", i);
            words.add(word);
            longName.append(i == 1 ? "" : " ").append(word);
        }

        // 12 слов по 7 знаков с пробелами — 95 знаков; 13-е уже за 100
        String longExpected = String.join(" ", words.subList(0, 12));
        String untitled = I18n.get("layerlist.schematics.export.untitled");

        String[][] cases = {
                {"запрещённые Windows знаки", "k<bad>:\"name/part\\x|y?z*", "k bad name part x y z"},
                {"управляющие знаки", "k\tline\nbreak\u0007bell", "k line break bell"},
                {"точки и пробелы по краям", "  ..k-dots...  ", "k-dots"},
                {"имя устройства CON", "CON", "CON_"},
                {"имя устройства nul.backup", "nul.backup", "nul_.backup"},
                {"LPT9", "LPT9", "LPT9_"},
                {"COM10 — не устройство", "COM10", "COM10"},
                {"кириллица и скобки", "Дворец Мнений (северное крыло)", "Дворец Мнений (северное крыло)"},
                {"длинное — по слову, ≤ 100", longName.toString(), longExpected},
                {"одни запрещённые — «" + untitled + "»", "???", untitled},
                {"эмодзи и разворот письма", "🏰 Замок‮gpj", "Замокgpj"},
                {"коды цвета §", "§cКрасный §lдом", "Красный дом"},
                {"пустое имя — по названию таблицы", "   ", TITLE},
        };

        for (String[] c : cases)
        {
            this.expect("очистка: " + c[0], Exporters.csv(request(c[1], rows, FAST)), this.next(c[2], ".csv"));
        }

        this.k.check("длинное имя ≤ 100 знаков", true, longExpected.length() <= 100);

        try
        {
            // Схема с запрещёнными знаками в имени файла (в Linux такой файл возможен, в Windows — нет)
            SchematicEntry bad = new SchematicEntry(Path.of("k:bad*name?.litematic"), false, SchematicFormat.LITEMATIC, 0L, 0L);
            this.k.check("схема с запрещёнными знаками: имя как в списке", "k:bad*name?", ExportNames.of(bad));
            this.expect("схема с запрещёнными знаками", Exporters.csv(request(ExportNames.of(bad), rows, FAST)),
                    this.next("k bad name", ".csv"));
        }
        catch (InvalidPathException e)
        {
            this.k.info("путь с запрещёнными знаками не создать на этой системе: " + e.getMessage());
        }

        return true;
    }

    // ===== Список HUD и размещения =====

    private boolean hudNone()
    {
        String none = I18n.get(KEY_FILE + "hud");
        long enabled = PlacementOps.all().stream().filter(SchematicPlacement::isEnabled).count();
        this.k.check("перед проверкой HUD включённых размещений нет", 0L, enabled);
        this.k.check("HUD без размещений: имя", none, ExportNames.hud());
        this.k.check("источник «Список HUD» без размещений: имя файла", none, ExportSource.hud().fileName());
        this.k.check("HUD: повторы и пустые не считаются", I18n.get(KEY_FILE + "more", "A", 2),
                ExportNames.hud(List.of("A", "B", "A", "  ", "C")));
        this.k.check("HUD: пустой список", none, ExportNames.hud(List.of()));

        List<MaterialRow> rows = this.generatedRows();

        if (rows != null)
        {
            this.expect("HUD без размещений", Exporters.csv(request(ExportNames.hud(), rows, FAST)), this.next(none, ".csv"));
        }

        return true;
    }

    private @Nullable LoadedSchematic loadedGenerated()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        if (generated == null)
        {
            this.k.fail("нет k-generated.litematic");
            return null;
        }

        LoadedSchematic loaded = this.k.loaded(generated.path());

        if (loaded != null)
        {
            return loaded;
        }

        if (this.loading == null)
        {
            this.loading = SchematicLibrary.get().load(generated);
        }

        return this.loading.isDone() && ! this.loading.isCompletedExceptionally() ? this.loading.join() : null;
    }

    private boolean placeGenerated()
    {
        LoadedSchematic loaded = this.loadedGenerated();

        if (loaded == null)
        {
            if (this.loading != null && this.loading.isCompletedExceptionally())
            {
                this.k.fail("загрузка k-generated не удалась");
                return true;
            }

            return this.k.generated(".litematic") == null;
        }

        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.made.add(p);
        this.placement = p;
        // Игрок переименовал размещение — файл всё равно называется по схеме
        p.setName(PLACEMENT_NAME);
        this.k.check("размещение «" + PLACEMENT_NAME + "»: имя файла — схема", GENERATED, ExportNames.of(p));
        return true;
    }

    private boolean hudOne()
    {
        this.k.check("HUD, одно размещение: имя", GENERATED, ExportNames.hud());
        this.k.check("источник «Список HUD», одно размещение: имя файла", GENERATED, ExportSource.hud().fileName());

        List<MaterialRow> rows = this.generatedRows();

        if (rows != null)
        {
            this.expect("HUD, одно размещение", Exporters.csv(request(ExportNames.hud(), rows, FAST)),
                    this.next(GENERATED, ".csv"));
        }

        return true;
    }

    private boolean hudSameTwice()
    {
        LoadedSchematic loaded = this.loadedGenerated();

        if (loaded == null)
        {
            this.k.fail("HUD: k-generated не загружена");
            return true;
        }

        SchematicPlacement twin = PlacementOps.placeInFront(loaded);
        this.made.add(twin);
        this.k.check("HUD, два размещения одной схемы: имя одно", GENERATED, ExportNames.hud());
        PlacementOps.remove(twin);
        this.made.remove(twin);
        return true;
    }

    private boolean hudTwo()
    {
        if (this.loadingSecond == null)
        {
            SchematicEntry generated = this.k.generated(".litematic");

            if (generated == null)
            {
                this.k.fail("нет k-generated.litematic");
                return true;
            }

            try
            {
                // Вторая схема — копия образца под другим именем вне _k: список «Библиотеки» не меняется
                Path folder = this.k.outDir().resolve("naming-src");
                Files.createDirectories(folder);
                Path copy = folder.resolve(SECOND + ".litematic");
                Files.copy(generated.path(), copy, StandardCopyOption.REPLACE_EXISTING);
                SchematicEntry entry = new SchematicEntry(copy, false, SchematicFormat.LITEMATIC, Files.size(copy),
                        Files.getLastModifiedTime(copy).toMillis());
                this.loadingSecond = SchematicLibrary.get().load(entry);
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail("вторая схема не подготовлена: " + e);
                return true;
            }
        }

        if (! this.loadingSecond.isDone())
        {
            return false;
        }

        LoadedSchematic second;

        try
        {
            second = this.loadingSecond.join();
        }
        catch (RuntimeException e)
        {
            this.k.fail("вторая схема не загрузилась: " + e);
            return true;
        }

        SchematicPlacement p = PlacementOps.placeInFront(second);
        this.made.add(p);
        String both = I18n.get(KEY_FILE + "more", GENERATED, 1);
        this.k.check("размещение второй схемы: имя", SECOND, ExportNames.of(p));
        this.k.check("HUD, две схемы: имя", both, ExportNames.hud());
        this.k.check("источник «Список HUD», две схемы: имя файла", both, ExportSource.hud().fileName());

        List<MaterialRow> rows = this.generatedRows();

        if (rows != null)
        {
            this.expect("HUD, две схемы", Exporters.csv(request(ExportNames.hud(), rows, FAST)), this.next(both, ".csv"));
        }

        p.setEnabled(false);
        this.k.check("вторая выключена — в имени только первая", GENERATED, ExportNames.hud());
        PlacementOps.remove(p);
        this.made.remove(p);
        return true;
    }

    // ===== Как игрок: «Материалы» размещения → Excel → окно =====

    private @Nullable Layers layers()
    {
        return KProbe.fieldOfType(this.k.gui(), Layers.class);
    }

    private @Nullable ExportWindow window()
    {
        Layers layers = this.layers();

        return layers != null && layers.top() instanceof ExportWindow window ? window : null;
    }

    private @Nullable Object pane()
    {
        return KProbe.fieldNamedType(this.k.gui(), "MaterialsPane");
    }

    private boolean showPlacement()
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null || this.placement == null)
        {
            this.k.fail("«Материалы» размещения: нет экрана или размещения");
            return true;
        }

        try
        {
            KProbe.call(gui, "showMaterials", null, this.placement);
            this.k.check("«Материалы» открыты по размещению", "MATERIALS", String.valueOf(KProbe.call(gui, "tab")));
            this.k.check("«Материалы»: источник — то самое размещение", true, KProbe.call(this.pane(), "placement") == this.placement);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("showMaterials: " + e);
        }

        return true;
    }

    private boolean placementRows()
    {
        int rows = KProbe.materialRows(this.pane());

        if (rows <= 0)
        {
            return false;
        }

        this.k.ok("«Материалы» размещения: строк " + rows);
        return true;
    }

    private static List<Button> buttonsIn(Object owner, Class<?> type, String field)
    {
        List<Button> out = new ArrayList<>();

        try
        {
            Field f = type.getDeclaredField(field);
            f.setAccessible(true);

            if (f.get(owner) instanceof List<?> list)
            {
                for (Object o : list)
                {
                    if (o instanceof Button b)
                    {
                        out.add(b);
                    }
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(DumpSchematics.TAG + type.getSimpleName() + "." + field + " не читается: " + e);
        }

        return out;
    }

    /** Граница окна ({@code Modal} держит их закрытыми). */
    private static int modalBound(Modal modal, String name)
    {
        try
        {
            Field field = Modal.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(modal);
        }
        catch (ReflectiveOperationException e)
        {
            return -1;
        }
    }

    private static @Nullable Button backButton(GuiSchematics gui)
    {
        try
        {
            Field field = GuiSchematics.class.getDeclaredField("back");
            field.setAccessible(true);
            return field.get(gui) instanceof Button button ? button : null;
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private static @Nullable Button folderIn(List<Button> buttons)
    {
        for (Button button : buttons)
        {
            if (ExportsFolder.TestHooks.isFolderButton(button))
            {
                return button;
            }
        }

        return null;
    }

    private static boolean overlap(Widget a, Widget b)
    {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    private boolean tooltipShowsFolder(Button folder)
    {
        List<String> lines = ExportsFolder.TestHooks.tooltip(folder);
        String expected = ExportsFolder.TestHooks.folder().getFileName().toString();
        return lines.stream().anyMatch(line -> line.endsWith(expected));
    }

    /** Подвал «Материалов»: кнопка папки есть, видна, в окне, левее форматов и ни на что не легла. */
    private boolean materialsFooter(boolean glowing)
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null)
        {
            this.k.fail("подвал «Материалов»: экрана нет");
            return true;
        }

        List<Button> footer = buttonsIn(gui, GuiSchematics.class, "footerButtons");
        Button folder = folderIn(footer);

        if (! this.k.check("подвал «Материалов»: кнопка «Папка с выгрузками» есть", true, folder != null && folder.visible()))
        {
            return true;
        }

        Button back = backButton(gui);
        this.k.info("подвал «Материалов»: кнопка папки " + folder.x() + "," + folder.y() + " " + folder.w() + "×" + folder.h()
                + (ExportsFolder.TestHooks.compact(folder) ? " (только значок)" : " «" + folder.label() + "»")
                + ", экран " + gui.width + "×" + gui.height + ", кнопок подвала " + footer.size());
        this.k.check("подвал «Материалов»: кнопка папки в окне", true, folder.x() >= 0 && folder.y() >= 0
                && folder.x() + folder.w() <= gui.width && folder.y() + folder.h() <= gui.height);

        boolean clear = true;
        int leftmostFormat = Integer.MAX_VALUE;

        for (Button other : footer)
        {
            if (other == folder || ! other.visible())
            {
                continue;
            }

            clear &= ! overlap(folder, other);
            leftmostFormat = Math.min(leftmostFormat, other.x());
        }

        this.k.check("подвал «Материалов»: кнопка папки ни на что не легла", true, clear);
        this.k.check("подвал «Материалов»: кнопка папки левее форматов", true, folder.x() + folder.w() <= leftmostFormat);

        if (back != null && back.visible())
        {
            this.k.check("подвал «Материалов»: кнопка папки не легла на «Назад»", false, overlap(folder, back));
        }

        this.k.check("подвал «Материалов»: подсказка — путь папки", true, this.tooltipShowsFolder(folder));

        this.copyInFooter(footer);

        // Кнопка папки не вытесняет главные форматы в «Ещё ▾»
        for (var format : List.of(com.aell.layerlist.schematics.gui.materials.ExportFormat.XLSX,
                com.aell.layerlist.schematics.gui.materials.ExportFormat.GOOGLE))
        {
            Button main = this.exportButton(format);
            this.k.check("подвал «Материалов»: «" + format.label() + "» видна рядом с кнопкой папки", true,
                    main != null && main.visible() && footer.contains(main));
        }

        long since = ExportsFolder.TestHooks.sinceExport();

        if (! glowing && since >= 0L && since < ExportsFolder.GLOW_MS)
        {
            // Прошлый проход (GUI 2) выгружал меньше 30 с назад — «обычный вид» смотрим через 31 с после него
            this.k.info("прошлая выгрузка " + since + " мс назад — часы свечения сдвинуты на 31 с после неё");
            ExportsFolder.TestHooks.age(ExportsFolder.GLOW_MS + 1_000L);
        }

        float glow = ExportsFolder.glow();

        if (glowing)
        {
            this.k.check("подвал «Материалов» после выгрузки: кнопка горит (" + glow + ")", true, glow > 0.3F && glow < 1.0F);
        }
        else
        {
            this.k.check("подвал «Материалов» до выгрузки: кнопка в обычном виде", 0.0F, glow);
        }

        return true;
    }

    /** Кнопка формата подвала «Материалов» ({@code MaterialsPane.exportButtons}). */
    private @Nullable Button exportButton(com.aell.layerlist.schematics.gui.materials.ExportFormat format)
    {
        Object pane = this.pane();

        try
        {
            Field field = pane == null ? null : pane.getClass().getDeclaredField("exportButtons");

            if (field != null)
            {
                field.setAccessible(true);

                if (field.get(pane) instanceof Map<?, ?> map && map.get(format) instanceof Button button)
                {
                    return button;
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("кнопки форматов подвала не читаются: " + e);
        }

        return null;
    }

    private boolean openWindow()
    {
        GuiSchematics gui = this.k.gui();
        Button excel = this.exportButton(com.aell.layerlist.schematics.gui.materials.ExportFormat.XLSX);

        if (gui == null || excel == null)
        {
            this.k.fail("подвал «Материалов»: нет кнопки Excel");
            return true;
        }

        this.lastBefore = ExportsFolder.TestHooks.lastFile();
        ExportsFolder.TestHooks.shiftClock(0L);
        this.k.clickButton(gui, excel, null);
        this.k.check("Excel подвала открыл окно выгрузки", true, this.window() != null);
        return true;
    }

    private boolean windowChecks()
    {
        ExportWindow window = this.window();

        if (window == null)
        {
            this.k.fail("окна выгрузки нет");
            return true;
        }

        ExportSource source = KProbe.fieldOfType(window, ExportSource.class);
        this.k.check("окно: источник — имя файла по схеме размещения", GENERATED, source == null ? "" : source.fileName());

        try
        {
            if (KProbe.call(window, "request") instanceof ExportRequest request)
            {
                this.k.check("окно: запрос — имя файла", GENERATED, request.fileName());
                this.k.check("окно: название таблицы — имя размещения (как в «Размещениях»)", PLACEMENT_NAME, request.title());
            }
            else
            {
                this.k.fail("окно: запрос не собран (строки не готовы)");
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("окно: request() не зовётся: " + e);
        }

        List<Button> buttons = buttonsIn(window, Modal.class, "buttons");
        Button folder = folderIn(buttons);

        if (! this.k.check("окно: в подвале кнопка «Папка с выгрузками»", true, folder != null && folder.visible()))
        {
            return true;
        }

        int wx = modalBound(window, "x");
        int wy = modalBound(window, "y");
        int ww = modalBound(window, "w");
        int wh = modalBound(window, "h");
        this.k.check("окно: кнопка папки в окне", true, folder.x() >= wx && folder.y() >= wy
                && folder.x() + folder.w() <= wx + ww && folder.y() + folder.h() <= wy + wh);

        for (Button other : buttons)
        {
            if (other != folder)
            {
                this.k.check("окно: кнопка папки не легла на «" + other.label() + "» и левее неё", true,
                        ! overlap(folder, other) && folder.x() + folder.w() <= other.x());
            }
        }

        this.k.check("окно: подсказка — путь папки", true, this.tooltipShowsFolder(folder));
        this.copyInGrid(window);
        return true;
    }

    /** Курсор SDL — к левому краю: подсказка под настоящей мышью не ложится на снимки. */
    private boolean mouseAside(Minecraft mc)
    {
        DevInput.onMove(mc.mouseHandler, mc.getWindow().handle(), 2.0, mc.getWindow().getScreenHeight() / 2.0, 0.0, 0.0);
        return true;
    }

    /** Курсор SDL — на кнопку папки подвала «Материалов» (для снимка подсказки). */
    private boolean mouseOnFolder(Minecraft mc)
    {
        GuiSchematics gui = this.k.gui();
        Button folder = gui == null ? null : folderIn(buttonsIn(gui, GuiSchematics.class, "footerButtons"));

        if (folder == null)
        {
            this.k.fail("подсказка: кнопки папки нет");
            return true;
        }

        var window = mc.getWindow();
        double x = (folder.x() + folder.w() / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = (folder.y() + folder.h() / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight();
        DevInput.onMove(mc.mouseHandler, window.handle(), x, y, 0.0, 0.0);
        return true;
    }

    /** Подпись влезает в кнопку целиком (без «…»). */
    private static boolean fits(Button button)
    {
        return button.w() > 0 && Kit.font().width(button.label()) <= button.w() - Ui.BUTTON_PAD_X * 2;
    }

    /**
     * Формат «в буфер» (0.0.27) подписан «Экспортировать» / «Export» с подсказкой, что он делает; в подвале — кнопкой
     * (подпись влезает) или пунктом «Ещё ▾».
     */
    private void copyInFooter(List<Button> footer)
    {
        var copy = com.aell.layerlist.schematics.gui.materials.ExportFormat.COPY;
        String label = I18n.get("layerlist.schematics.materials.export.copy");
        this.k.check("подвал «Материалов»: формат «в буфер» подписан", label, copy.label());
        this.k.check("подвал «Материалов»: подсказка «в буфер» — что делает",
                I18n.get("layerlist.schematics.materials.export.copy.tip"), copy.description());
        Button button = this.exportButton(copy);

        if (button != null && button.visible() && footer.contains(button))
        {
            this.k.check("подвал «Материалов»: «" + label + "» — кнопкой, подпись влезает (" + button.w() + " px)", true,
                    label.equals(button.label()) && fits(button));
            return;
        }

        Object overflow = null;

        try
        {
            Field field = this.pane() == null ? null : this.pane().getClass().getDeclaredField("overflow");

            if (field != null)
            {
                field.setAccessible(true);
                overflow = field.get(this.pane());
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("MaterialsPane.overflow не читается: " + e);
        }

        this.k.check("подвал «Материалов»: «" + label + "» — пунктом «Ещё ▾»", true,
                overflow instanceof List<?> list && list.contains(copy));
    }

    private void copyInGrid(ExportWindow window)
    {
        Object grid = KProbe.fieldNamedType(window, "FormatGrid");
        String label = I18n.get("layerlist.schematics.export.dialog.format.copy");

        try
        {
            Object button = grid == null ? null : KProbe.call(grid, "button",
                    com.aell.layerlist.schematics.gui.export.ExportFormat.COPY);

            if (button instanceof Button copy)
            {
                this.k.check("окно: формат «в буфер» — «" + label + "», подпись влезает в ячейку (" + copy.w() + " px)",
                        true, label.equals(copy.label()) && fits(copy));
            }
            else
            {
                this.k.fail("окно: нет кнопки формата «в буфер»");
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("окно: сетка форматов не читается: " + e);
        }

        this.k.check("окно: подсказка «в буфер» — что делает", I18n.get("layerlist.schematics.export.dialog.format.copy.tip"),
                com.aell.layerlist.schematics.gui.export.ExportFormat.COPY.tooltip());
    }

    private boolean openMore()
    {
        GuiSchematics gui = this.k.gui();
        Object pane = this.pane();
        Button more = null;

        try
        {
            Field field = pane == null ? null : pane.getClass().getDeclaredField("more");

            if (field != null)
            {
                field.setAccessible(true);
                more = field.get(pane) instanceof Button button ? button : null;
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("кнопка «Ещё ▾» не читается: " + e);
        }

        if (gui == null || more == null || ! more.visible())
        {
            this.k.info("«Ещё ▾» в подвале нет — все форматы влезли");
            return true;
        }

        this.k.clickButton(gui, more, null);
        Layers layers = this.layers();
        this.k.check("«Ещё ▾» открыло меню форматов", true, layers != null && layers.top() instanceof ContextMenu);
        return true;
    }

    private boolean closeMenu()
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = this.layers();

        if (gui != null && layers != null && layers.top() instanceof ContextMenu)
        {
            KProbe.press(gui, InputConstants.KEY_ESCAPE);
            this.k.check("Esc закрыл меню «Ещё ▾», экран остался", true, ! layers.any() && this.k.gui() == gui);
        }

        return true;
    }

    private boolean pressEnter()
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null || this.window() == null)
        {
            this.k.fail("Enter: окна выгрузки нет");
            return true;
        }

        KProbe.press(gui, InputConstants.KEY_RETURN);

        // С 1.0.1 Excel сначала спрашивает «Оформление таблицы»; его «Выгрузить» — тоже по Enter
        com.aell.layerlist.ui.kit.Layers layers = KProbe.fieldOfType(gui, com.aell.layerlist.ui.kit.Layers.class);

        if (layers != null && layers.top() != this.window())
        {
            KProbe.press(gui, InputConstants.KEY_RETURN);
        }

        return true;
    }

    private boolean exported()
    {
        Path last = ExportsFolder.TestHooks.lastFile();

        if (last == null || last.equals(this.lastBefore))
        {
            return false;
        }

        float glow = ExportsFolder.glow();
        String expected = this.next(GENERATED, ".xlsx");
        this.k.check("окно → Excel: файл назван по схеме размещения", expected, last.getFileName().toString());
        this.k.check("окно → Excel: в папке выгрузок", this.dir.toAbsolutePath().normalize(), last.getParent());
        this.k.check("окно → Excel: таблица цела", true, KExports.xlsx(last) == null);
        this.k.check("окно → Excel: лист — название таблицы", PLACEMENT_NAME, sheetName(last));
        this.k.check("сразу после выгрузки кнопка горит ≈ 1 (" + glow + ")", true, glow >= 0.95F);

        ExportWindow window = this.window();
        Button folder = window == null ? null : folderIn(buttonsIn(window, Modal.class, "buttons"));
        List<String> tip = folder == null ? List.of() : ExportsFolder.TestHooks.tooltip(folder);
        this.k.check("подсказка кнопки называет последний файл", true,
                tip.stream().anyMatch(line -> line.contains(last.getFileName().toString())));
        this.k.check("кнопка откроет папку последнего файла", last.getParent(), ExportsFolder.TestHooks.folder());
        return true;
    }

    private boolean age(long ms, float low, float high)
    {
        ExportsFolder.TestHooks.age(ms);
        float glow = ExportsFolder.glow();
        String range = low == high ? "= " + low : low + "…" + high;
        this.k.check("через " + ms / 1000L + " с свечение " + range + " (" + glow + ")", true, glow >= low && glow <= high);
        return true;
    }

    private boolean realClock()
    {
        ExportsFolder.TestHooks.shiftClock(0L);
        float glow = ExportsFolder.glow();
        this.k.check("настоящие часы: прошло " + ExportsFolder.TestHooks.sinceExport() + " мс, кнопка ещё горит (" + glow + ")",
                true, glow > 0.5F);
        return true;
    }

    private boolean tidy()
    {
        ExportsFolder.TestHooks.shiftClock(0L);

        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.placement = null;

        if (this.exportDirBefore != null)
        {
            SchematicsConfig.EXPORT_DIR.setValueFromString(this.exportDirBefore);
            this.exportDirBefore = null;
        }

        this.k.check("имена: размещения убраны", 0L, PlacementOps.all().stream().filter(SchematicPlacement::isEnabled).count());
        this.k.setTab("LIBRARY");
        return true;
    }

    /** Имя первого листа книги (из {@code xl/workbook.xml}). */
    private static String sheetName(Path file)
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            ZipEntry entry = zip.getEntry("xl/workbook.xml");

            if (entry == null)
            {
                return "";
            }

            String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = SHEET.matcher(xml);

            return m.find() ? m.group(1).replace("&amp;", "&").replace("&quot;", "\"") : "";
        }
        catch (IOException e)
        {
            return "";
        }
    }
}
