package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.export.PreviewPoster;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportFormat;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.export.ExportsFolder;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.preview.PreviewCapture;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим K, 0.0.27 — постер-превью для выгрузки «Картинка» ({@link PreviewPoster}, снимок —
 * {@link PreviewCapture}). При <b>открытом окне выгрузки</b> заказывает постеры: сгенерированная схема
 * стенда, настоящий «склад» из {@code _k}, самая большая схема из профилей игрока (или
 * {@code LL_K_POSTER_BIG}) и битый файл; плюс голый снимок модели размера по умолчанию. Ждёт, пишет PNG в
 * {@code <выход>/posters/} и проверяет: размер 1920×1200, модель есть (или заглушка у битого), модель
 * не касается краёв своей области (ничего не срезано), упрощённый вид — у большой схемы, окно выгрузки
 * всё это время открыто, заявок и текстур после — ноль. Смотреть глазами: модель не зеркальная и не
 * вверх ногами, текстуры, полупрозрачное, рамка и тексты по канону.
 *
 * <p>{@code LL_K_ONLY=poster} — после списка {@code _k} только эти шаги (без проходов по файлам).
 */
@Environment(EnvType.CLIENT)
final class KPoster
{
    /** Область модели на постере (как {@code PosterPainter}): по ней ищем, не срезана ли модель. */
    private static final int MODEL_X = 68;
    private static final int MODEL_Y = 206;
    private static final int MODEL_W = 1784;
    private static final int MODEL_H = 894;
    /** Фон сцены постера ({@code #2A0B52}); сетка над ним отличается меньше этого порога. */
    private static final int STAGE_RGB = 0x2A0B52;
    private static final int STAGE_DIFF = 90;

    private final DumpSchematics k;
    private final List<Order> orders = new ArrayList<>();
    private @Nullable CompletableFuture<PreviewCapture.Shot> raw;
    private long orderedAt;
    private Path posters = Path.of(".");
    private Path sources = Path.of(".");

    /** Один заказ: что ждём на постере. */
    private record Order(String name, SchematicEntry entry, boolean expectModel, long at,
                         CompletableFuture<PreviewPoster.Result> future) {}

    /** Подобранная схема для заказа. */
    private record Pick(String name, SchematicEntry entry, boolean expectModel) {}

    private final List<Pick> picks = new ArrayList<>();
    /** Выгрузка «Картинка» из окна (0.0.27): прошлый файл выгрузок и само окно. */
    private @Nullable Path lastBefore;
    private @Nullable ExportWindow pngWindow;
    /** Когда строки окна стали готовы; Enter — через {@link #READY_SETTLE_MS}. */
    private long readyAt;
    private static final long READY_SETTLE_MS = 300L;

    KPoster(DumpSchematics k)
    {
        this.k = k;
    }

    List<DumpSchematics.Step> steps()
    {
        List<DumpSchematics.Step> steps = new ArrayList<>();
        steps.add(new DumpSchematics.Step("постер: схемы", 60_000L, mc -> this.prepare()));
        steps.add(new DumpSchematics.Step("постер: снова k-generated.litematic", 5_000L, mc -> this.k.selectGenerated()));
        steps.add(new DumpSchematics.Step("постер: окно выгрузки", 5_000L, this.k::openExportDialog));
        steps.add(new DumpSchematics.Step("постер: заказы при открытом окне выгрузки", 5_000L, this::order));
        steps.add(new DumpSchematics.Step("постер: готовы", 320_000L, this::collect));
        steps.add(new DumpSchematics.Step("постер: снимок окна выгрузки", 5_000L, this.k.settleShot("poster-export-window")));
        steps.add(new DumpSchematics.Step("постер: окно выгрузки закрыто", 3_000L, this.k::closeExportDialog));
        // Постер в самой выгрузке: «Картинка» из окна кладёт превью.png в папку схемы рядом со страницами
        steps.add(new DumpSchematics.Step("постер в выгрузке: окно «Картинка»", 5_000L, this::openPng));
        steps.add(new DumpSchematics.Step("постер в выгрузке: Enter, когда строки посчитаны", 60_000L, this::pressWhenReady));
        steps.add(new DumpSchematics.Step("постер в выгрузке: папка записана", 120_000L, this::pngWritten));
        steps.add(new DumpSchematics.Step("постер в выгрузке: снимок окна", 5_000L, this.k.settleShot("poster-export-png")));
        steps.add(new DumpSchematics.Step("постер в выгрузке: окно закрыто", 3_000L, this.k::closeExportDialog));
        return steps;
    }

    // ===== Шаги =====

    /** Схемы для постеров: из {@code _k}, большая — копией в выход прогона, битая — там же. */
    private boolean prepare()
    {
        this.posters = this.k.outDir().resolve("posters");
        this.sources = this.k.outDir().resolve("poster-src");

        try
        {
            KData.recreate(this.posters);
            KData.recreate(this.sources);
        }
        catch (IOException | RuntimeException e)
        {
            this.k.fail("постер: папки выхода: " + e);
            return true;
        }

        SchematicEntry generated = this.k.generated(".litematic");

        if (generated != null)
        {
            this.picks.add(new Pick("k-generated", generated, true));
        }
        else
        {
            this.k.fail("постер: нет k-generated.litematic");
        }

        SchematicEntry store = SchematicLibrary.get().entry(this.k.kDir().resolve("склад.litematic"));

        if (store != null)
        {
            this.picks.add(new Pick("sklad", store, true));
        }
        else
        {
            this.k.info("постер: «склад.litematic» в _k нет — без него");
        }

        Path big = this.biggest();

        if (big != null)
        {
            try
            {
                Path copy = this.sources.resolve(big.getFileName().toString());
                Files.copy(big, copy, StandardCopyOption.REPLACE_EXISTING);
                SchematicEntry entry = SchematicLibrary.get().entry(copy);
                this.k.info("постер: самая большая схема — " + big + " (" + KData.size(copy) + " Б)");

                if (entry != null)
                {
                    this.picks.add(new Pick("biggest", entry, true));
                }
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail("постер: копия большой схемы: " + e);
            }
        }
        else
        {
            this.k.info("постер: больших схем игрока не нашлось");
        }

        try
        {
            // Мусор с расширением .litematic: постер обязан прийти и для него — с заглушкой
            byte[] junk = new byte[4096];
            new Random(42L).nextBytes(junk);
            Path broken = this.sources.resolve("k-broken.litematic");
            Files.write(broken, junk);
            SchematicEntry entry = SchematicLibrary.get().entry(broken);

            if (entry != null)
            {
                this.picks.add(new Pick("broken", entry, false));
            }
        }
        catch (IOException | RuntimeException e)
        {
            this.k.fail("постер: битый файл: " + e);
        }

        return true;
    }

    private boolean order(Minecraft mc)
    {
        Layers layers = this.layers();
        this.k.check("постер: окно выгрузки открыто перед заказом", layers != null && layers.top() instanceof ExportWindow);

        this.orderedAt = Util.getMillis();

        for (Pick pick : this.picks)
        {
            this.orders.add(new Order(pick.name(), pick.entry(), pick.expectModel(), Util.getMillis(),
                    PreviewPoster.requestResult(pick.entry(), "")));
        }

        SchematicEntry generated = this.k.generated(".litematic");

        if (generated != null)
        {
            this.raw = PreviewCapture.request(generated);
        }

        this.k.info("постер: заказов постера — " + this.orders.size() + ", и голый снимок; заявок снимка в работе — "
                + PreviewCapture.pending());
        return true;
    }

    private boolean collect(Minecraft mc)
    {
        CompletableFuture<PreviewCapture.Shot> shot = this.raw;

        for (Order order : this.orders)
        {
            if (! order.future().isDone())
            {
                return false;
            }
        }

        if (shot != null && ! shot.isDone())
        {
            return false;
        }

        long took = Util.getMillis() - this.orderedAt;
        this.k.info("постер: все готовы за " + took + " мс");

        Layers layers = this.layers();
        this.k.check("постер: окно выгрузки всё это время открыто", layers != null && layers.top() instanceof ExportWindow);

        for (Order order : this.orders)
        {
            this.check(order);
        }

        if (shot != null)
        {
            this.checkRaw(shot);
        }

        this.k.check("постер: заявок снимка не осталось", 0L, (long) PreviewCapture.pending());
        return true;
    }

    /** Окно выгрузки поверх «Схем»: «Схема: k-generated» и «Список HUD», формат «Картинка» уже выбран. */
    private boolean openPng(Minecraft mc)
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = this.layers();
        Toasts toasts = KProbe.fieldOfType(gui, Toasts.class);
        SchematicEntry generated = this.k.generated(".litematic");

        if (gui == null || layers == null || toasts == null || generated == null)
        {
            this.k.fail("постер в выгрузке: нет экрана «Схемы» или k-generated.litematic");
            return true;
        }

        ExportSource schematic = ExportSource.schematic(generated);
        ExportSource hud = ExportSource.hud();
        this.k.check("постер в выгрузке: у источника «Схема» есть файл", generated, schematic.entry());
        this.k.check("постер в выгрузке: у «Списка HUD» файла нет — постера не будет", hud.entry() == null);
        this.k.check("постер в выгрузке: строки «Материалов» несут файл схемы", generated,
                ExportSource.rows("k", List.of(), "k", ExportSource.Kind.SCHEMATIC, false, generated).entry());

        this.lastBefore = ExportsFolder.TestHooks.lastFile();
        ExportWindow.Host host = new ExportWindow.Host(layers, toasts, () -> this.k.gui() == gui);
        this.pngWindow = ExportWindow.open(host, KData.GENERATED, KData.GENERATED, List.of(schematic, hud), 0,
                ExportFormat.PNG);
        this.k.check("постер в выгрузке: окно открыто", layers.top() == this.pngWindow);
        return true;
    }

    private boolean pressWhenReady(Minecraft mc)
    {
        ExportWindow window = this.pngWindow;
        GuiSchematics gui = this.k.gui();

        if (window == null || gui == null)
        {
            this.k.fail("постер в выгрузке: окна нет");
            return true;
        }

        if (! ExportWindow.TestHooks.sourceReady(window))
        {
            return false;
        }

        // Строки только что посчитались — дать окну кадр-другой (прогон 07.10 с двумя клиентами разом: Enter в тот же
        // кадр не начинал выгрузку)
        long now = Util.getMillis();

        if (this.readyAt == 0L)
        {
            this.readyAt = now;
        }

        if (now - this.readyAt < READY_SETTLE_MS)
        {
            return false;
        }

        this.readyAt = 0L;
        KProbe.press(gui, InputConstants.KEY_RETURN);
        this.k.check("постер в выгрузке: Enter начал выгрузку", ExportWindow.TestHooks.busy(window)
                || ! java.util.Objects.equals(this.lastBefore, ExportsFolder.TestHooks.lastFile()));
        return true;
    }

    /** Папка схемы: страницы {@code 1.png}… и {@code превью.png} 1920×1200; строка итога считает его отдельно. */
    private boolean pngWritten(Minecraft mc)
    {
        Path folder = ExportsFolder.TestHooks.lastFile();

        if (folder == null || folder.equals(this.lastBefore))
        {
            return false;
        }

        this.k.check("постер в выгрузке: выделяется папка схемы", true, Files.isDirectory(folder));
        this.k.check("постер в выгрузке: папка названа по схеме", true,
                folder.getFileName().toString().startsWith(KData.GENERATED));

        List<String> names = new ArrayList<>();

        try (Stream<Path> list = Files.list(folder))
        {
            list.forEach(file -> names.add(file.getFileName().toString()));
        }
        catch (IOException e)
        {
            this.k.fail("постер в выгрузке: папка не читается: " + e);
            return true;
        }

        names.sort(Comparator.naturalOrder());
        this.k.info("постер в выгрузке: в папке " + names);
        this.k.check("постер в выгрузке: есть 1.png", true, names.contains("1.png"));
        this.k.check("постер в выгрузке: есть " + PreviewPoster.FILE_NAME, true, names.contains(PreviewPoster.FILE_NAME));
        this.k.check("постер в выгрузке: кроме страниц и превью ничего нет (нет .tmp)", true, names.stream()
                .allMatch(name -> name.equals(PreviewPoster.FILE_NAME) || name.matches("[0-9]+\\.png")));

        Path poster = folder.resolve(PreviewPoster.FILE_NAME);

        try
        {
            BufferedImage image = ImageIO.read(poster.toFile());
            this.k.check("постер в выгрузке: превью — PNG " + PreviewPoster.WIDTH + "×" + PreviewPoster.HEIGHT, true,
                    image != null && image.getWidth() == PreviewPoster.WIDTH && image.getHeight() == PreviewPoster.HEIGHT);
            Files.copy(poster, this.posters.resolve("export-" + PreviewPoster.FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException | RuntimeException e)
        {
            this.k.fail("постер в выгрузке: превью не читается: " + e);
        }

        ExportWindow window = this.pngWindow;
        String status = window == null ? null : ExportWindow.TestHooks.statusText(window);
        String template = Language.getInstance().getOrDefault("layerlist.schematics.export.dialog.saved.folder.poster");
        String tail = template.substring(template.lastIndexOf("%s") + 2);
        long pages = names.stream().filter(name -> name.matches("[0-9]+\\.png")).count();
        this.k.info("постер в выгрузке: строка итога «" + status + "»");
        this.k.check("постер в выгрузке: строка итога говорит про превью", true, status != null && status.endsWith(tail));
        this.k.check("постер в выгрузке: в строке итога — число страниц без превью", true,
                status != null && status.contains(" " + pages + tail));
        return true;
    }

    // ===== Проверки =====

    private void check(Order order)
    {
        PreviewPoster.Result result = order.future().getNow(null);
        String name = order.name();

        if (! this.k.check("постер " + name + ": будущее без исключения", result != null))
        {
            return;
        }

        Path file = this.posters.resolve("poster-" + name + ".png");

        try
        {
            Files.write(file, result.png());
        }
        catch (IOException e)
        {
            this.k.fail("постер " + name + ": не записан: " + e);
        }

        BufferedImage image = decode(result.png());

        if (! this.k.check("постер " + name + ": PNG читается", image != null))
        {
            return;
        }

        this.k.check("постер " + name + ": размер", PreviewPoster.WIDTH + "×" + PreviewPoster.HEIGHT,
                image.getWidth() + "×" + image.getHeight());
        this.k.check("постер " + name + ": модель на постере", order.expectModel(), result.modelShown());
        this.k.info("постер " + name + ": " + order.entry().fileName() + ", упрощённый вид = " + result.simplified()
                + " (кубы " + result.cubeSize() + "), причина заглушки = " + result.failure() + ", PNG " + result.png().length
                + " Б → " + file.getFileName());

        if (name.equals("k-generated"))
        {
            this.k.check("постер k-generated: без упрощения", false, result.simplified());
        }

        if (! result.modelShown())
        {
            return;
        }

        int[] box = bounds(image);
        long pixels = box[4];
        long area = (long) MODEL_W * MODEL_H;

        this.k.check("постер " + name + ": модель видна (≥ 2 % области, вышло " + percent(pixels, area) + ")",
                pixels * 50L >= area);

        int left = box[0] - MODEL_X;
        int top = box[1] - MODEL_Y;
        int right = MODEL_X + MODEL_W - 1 - box[2];
        int bottom = MODEL_Y + MODEL_H - 1 - box[3];
        String margins = String.format(Locale.ROOT, "слева %d, сверху %d, справа %d, снизу %d", left, top, right, bottom);

        this.k.check("постер " + name + ": модель не срезана (" + margins + ")", Math.min(Math.min(left, right), Math.min(top, bottom)) >= 8);
        // Модель вписана по геометрии: по одной из осей она упирается в поле 3 % (≈ 27 px), а не болтается в центре
        this.k.check("постер " + name + ": модель вписана по одной из осей (" + margins + ")",
                Math.min(left + right, top + bottom) <= 2 * 60);
    }

    private void checkRaw(CompletableFuture<PreviewCapture.Shot> future)
    {
        PreviewCapture.Shot shot;

        try
        {
            shot = future.getNow(null);
        }
        catch (RuntimeException e)
        {
            this.k.fail("снимок k-generated: исключение " + e);
            return;
        }

        if (! this.k.check("снимок k-generated: есть", shot != null))
        {
            return;
        }

        BufferedImage image = shot.image();
        this.k.check("снимок k-generated: размер по умолчанию", PreviewCapture.DEFAULT_WIDTH + "×" + PreviewCapture.DEFAULT_HEIGHT,
                image.getWidth() + "×" + image.getHeight());

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        long opaque = 0L;
        long translucent = 0L;

        for (int y = 0; y < image.getHeight(); ++y)
        {
            for (int x = 0; x < image.getWidth(); ++x)
            {
                int a = image.getRGB(x, y) >>> 24;

                if (a == 0)
                {
                    continue;
                }

                opaque += a == 0xFF ? 1 : 0;
                translucent += a < 0xFF ? 1 : 0;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        this.k.check("снимок k-generated: фон прозрачный (угол)", 0, image.getRGB(0, 0) >>> 24);
        // В образце стенда вода в стеклянной ванне — у неё своя альфа, а не 0 и не 255
        this.k.check("снимок k-generated: полупрозрачное есть (вода, стекло)", translucent > 0L);
        this.k.check("снимок k-generated: модель не касается краёв",
                minX > 0 && minY > 0 && maxX < image.getWidth() - 1 && maxY < image.getHeight() - 1);
        this.k.info(String.format(Locale.ROOT, "снимок k-generated: рамка модели [%d %d … %d %d], непрозрачных %d, полупрозрачных %d, "
                + "граней %d, кубы %d", minX, minY, maxX, maxY, opaque, translucent, shot.quads(), shot.cubeSize()));

        try
        {
            Files.write(this.posters.resolve("capture-k-generated.png"), shot.png());
        }
        catch (IOException e)
        {
            this.k.fail("снимок k-generated: не записан: " + e);
        }
    }

    // ===== Мелочи =====

    private @Nullable Layers layers()
    {
        GuiSchematics gui = this.k.gui();

        return gui == null ? null : KProbe.fieldOfType(gui, Layers.class);
    }

    /** Самая большая схема игрока: {@code LL_K_POSTER_BIG} или по размеру файла во всех профилях. */
    private @Nullable Path biggest()
    {
        String override = System.getenv("LL_K_POSTER_BIG");

        if (override != null && ! override.isBlank())
        {
            Path path = Path.of(override.trim());

            return Files.isRegularFile(path) ? path : null;
        }

        Path best = null;
        long bestSize = -1L;

        for (Path folder : KData.profileFolders())
        {
            try (Stream<Path> files = Files.list(folder))
            {
                for (Path file : files.filter(p -> SchematicFormat.of(p) != null && Files.isRegularFile(p))
                        .sorted(Comparator.comparing(Path::toString)).toList())
                {
                    long size = Files.size(file);

                    // Профиль текущей версии игры — при равном размере первым
                    if (size > bestSize || size == bestSize && file.toString().contains("26.3"))
                    {
                        best = file;
                        bestSize = size;
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                this.k.info("постер: не прочитать " + folder + ": " + e);
            }
        }

        return best;
    }

    /**
     * Рамка «не сцены» в области модели постера: пиксели, заметно отличные от фона сцены (сетка над ним
     * отличается слабее порога). {@code [minX, minY, maxX, maxY, count]}.
     */
    private static int[] bounds(BufferedImage image)
    {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int count = 0;
        int sr = (STAGE_RGB >> 16) & 0xFF;
        int sg = (STAGE_RGB >> 8) & 0xFF;
        int sb = STAGE_RGB & 0xFF;

        for (int y = MODEL_Y; y < MODEL_Y + MODEL_H; ++y)
        {
            for (int x = MODEL_X; x < MODEL_X + MODEL_W; ++x)
            {
                int rgb = image.getRGB(x, y);
                int diff = Math.abs(((rgb >> 16) & 0xFF) - sr) + Math.abs(((rgb >> 8) & 0xFF) - sg) + Math.abs((rgb & 0xFF) - sb);

                if (diff <= STAGE_DIFF)
                {
                    continue;
                }

                ++count;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }

        return new int[] {minX, minY, maxX, maxY, count};
    }

    private static @Nullable BufferedImage decode(byte[] png)
    {
        try
        {
            return ImageIO.read(new ByteArrayInputStream(png));
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static String percent(long part, long whole)
    {
        return String.format(Locale.ROOT, "%.1f %%", whole == 0 ? 0.0 : part * 100.0 / whole);
    }
}
