package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.PreviewLimits;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.export.PreviewPoster;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Dropdown;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27: «Отображение схемы» («Оптимизировано» / «Качественно») и кнопка «Настройки» в шапке экрана.
 * <ul>
 *   <li>в шапке — надпись «Настройки», не шестерёнка; окно настроек открывается ею (снимок);</li>
 *   <li>выбор «Качественно» сначала спрашивает (предупреждение о FPS и памяти), «Отмена» оставляет «Оптимизировано»,
 *       «Включить» — включает; обратно на «Оптимизировано» — без вопроса;</li>
 *   <li>большая схема игрока (копия из шагов постера): в «Оптимизировано» снимок грубый (масштаб больше 1), в
 *       «Качественно» — честный, если память позволяет (пределы — строкой INFO).</li>
 * </ul>
 * В конце режим возвращается к прежнему.
 */
@Environment(EnvType.CLIENT)
final class KQuality
{
    private final DumpSchematics k;
    private SchematicsConfig.PreviewQuality before = SchematicsConfig.PreviewQuality.OPTIMIZED;
    private @Nullable SchematicEntry big;
    private @Nullable CompletableFuture<VoxelSnapshot> snapshot;
    private @Nullable CompletableFuture<PreviewPoster.Result> poster;
    private long posterAt;

    KQuality(DumpSchematics k)
    {
        this.k = k;
    }

    List<DumpSchematics.Step> steps()
    {
        return List.of(
                new DumpSchematics.Step("качество: «Настройки» надписью в шапке", 5_000L, mc -> this.headerLabel()),
                new DumpSchematics.Step("качество: окно настроек кнопкой", 5_000L, mc -> this.openSettings()),
                new DumpSchematics.Step("снимок: окно настроек схем", 5_000L, this.k.settleShot("quality-settings")),
                new DumpSchematics.Step("качество: окно настроек закрыто", 3_000L, this.k::closeExportDialog),
                new DumpSchematics.Step("качество: «Оптимизировано» — снимок большой схемы", 120_000L, this.bigSnapshot(false)),
                new DumpSchematics.Step("качество: «Качественно» спрашивает", 3_000L, mc -> this.askQuality()),
                new DumpSchematics.Step("снимок: предупреждение «Качественно»", 5_000L, this.k.settleShot("quality-warn")),
                new DumpSchematics.Step("качество: «Отмена» — без перемен", 3_000L, mc -> this.answer(false)),
                new DumpSchematics.Step("качество: «Включить»", 3_000L, mc -> this.askQuality() && this.answer(true)),
                new DumpSchematics.Step("качество: «Качественно» — снимок большой схемы", 240_000L, this.bigSnapshot(true)),
                new DumpSchematics.Step("качество: «Качественно» — постер большой схемы", 600_000L, this.bigPoster()),
                new DumpSchematics.Step("качество: обратно «Оптимизировано» без вопроса", 3_000L, mc -> this.backToOptimized()));
    }

    // ===== Шапка и окно =====

    private boolean headerLabel()
    {
        GuiSchematics gui = this.k.gui();
        Button settings = KTrash.field(gui, "settings");

        if (settings == null)
        {
            this.k.fail("качество: нет кнопки настроек в шапке");
            return true;
        }

        this.before = SchematicsConfig.previewQuality();
        this.k.check("в шапке надпись «" + I18n.get("layerlist.schematics.header.settings_label") + "»",
                I18n.get("layerlist.schematics.header.settings_label"), settings.label());
        this.k.check("у «Настройки» нет шестерёнки при широком окне", KTrash.field(settings, "glyph") == null);
        return true;
    }

    private boolean openSettings()
    {
        GuiSchematics gui = this.k.gui();
        Button settings = KTrash.field(gui, "settings");

        if (gui == null || settings == null)
        {
            this.k.fail("качество: экрана нет");
            return true;
        }

        this.k.clickButton(gui, settings, null);
        Modal top = this.topModal();
        this.k.check("«Настройки» открыли окно настроек схем", I18n.get("layerlist.schematics.settings.title"),
                KTrash.modalTitle(top));
        return true;
    }

    // ===== Выбор режима =====

    /** Тот же путь, что у списка в окне настроек ({@code SettingsDialog.chooseQuality}). */
    private void choose(SchematicsConfig.PreviewQuality value)
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = KTrash.field(gui, "layers");

        if (gui == null || layers == null)
        {
            this.k.fail("качество: экрана нет");
            return;
        }

        try
        {
            Dropdown<SchematicsConfig.PreviewQuality> list = new Dropdown<>(layers,
                    List.of(SchematicsConfig.PreviewQuality.values()), SchematicsConfig.previewQuality(),
                    SchematicsConfig.PreviewQuality::getDisplayName, ignored -> {});
            Class<?> dialog = Class.forName("com.aell.layerlist.schematics.gui.SettingsDialog");
            Method method = dialog.getDeclaredMethod("chooseQuality", GuiSchematics.class, Dropdown.class,
                    SchematicsConfig.PreviewQuality.class);
            method.setAccessible(true);
            method.invoke(null, gui, list, value);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail("качество: выбор " + value + ": " + e);
        }
    }

    private boolean askQuality()
    {
        this.choose(SchematicsConfig.PreviewQuality.QUALITY);
        Modal top = this.topModal();
        this.k.check("«Качественно» спрашивает", I18n.get("layerlist.schematics.settings.quality.warn.title"),
                KTrash.modalTitle(top));
        this.k.check("предупреждение — про FPS и память", KTrash.modalText(top).contains("FPS"));
        this.k.check("до ответа режим прежний", SchematicsConfig.PreviewQuality.OPTIMIZED, SchematicsConfig.previewQuality());
        return true;
    }

    private boolean answer(boolean yes)
    {
        String label = yes ? I18n.get("layerlist.schematics.settings.quality.warn.yes") : I18n.get("layerlist.schematics.cancel");
        Button button = KTrash.modalButton(this.topModal(), label);
        GuiSchematics gui = this.k.gui();

        if (gui == null || button == null)
        {
            this.k.fail("качество: в предупреждении нет кнопки «" + label + "»");
            return true;
        }

        this.k.clickButton(gui, button, null);
        this.k.check(yes ? "«Включить» — «Качественно»" : "«Отмена» — «Оптимизировано»",
                yes ? SchematicsConfig.PreviewQuality.QUALITY : SchematicsConfig.PreviewQuality.OPTIMIZED,
                SchematicsConfig.previewQuality());
        this.k.check("предупреждение закрылось", this.topModal() == null);
        return true;
    }

    private boolean backToOptimized()
    {
        this.choose(SchematicsConfig.PreviewQuality.OPTIMIZED);
        this.k.check("«Оптимизировано» — без вопроса", this.topModal() == null);
        this.k.check("режим — «Оптимизировано»", SchematicsConfig.PreviewQuality.OPTIMIZED, SchematicsConfig.previewQuality());

        if (this.before != SchematicsConfig.PreviewQuality.OPTIMIZED)
        {
            SchematicsConfig.PREVIEW_QUALITY.setOptionListValue(this.before);
            PreviewLimits.reset();
        }

        return true;
    }

    // ===== Снимок большой схемы =====

    private DumpSchematics.Action bigSnapshot(boolean quality)
    {
        return mc ->
        {
            if (this.snapshot == null)
            {
                this.big = this.biggestCopy();

                if (this.big == null)
                {
                    this.k.info("качество: большой схемы игрока нет — снимок не проверяется");
                    return true;
                }

                this.snapshot = SchematicLibrary.get().snapshot(this.big);
                return false;
            }

            if (! this.snapshot.isDone())
            {
                return false;
            }

            CompletableFuture<VoxelSnapshot> done = this.snapshot;
            this.snapshot = null;
            VoxelSnapshot shot = done.getNow(null);

            if (shot == null || done.isCompletedExceptionally())
            {
                this.k.fail("качество: снимок большой схемы не снят");
                return true;
            }

            long cells = SchematicLibrary.previewCells();
            long volume = (long) shot.sizeX() * shot.sizeY() * shot.sizeZ() * shot.scale() * shot.scale() * shot.scale();
            this.k.info("качество: " + (quality ? "«Качественно»" : "«Оптимизировано»") + " — предел клеток " + cells
                    + ", граней " + PreviewLimits.quads(250_000) + ", снимок " + shot.sizeX() + "×" + shot.sizeY() + "×"
                    + shot.sizeZ() + " ×" + shot.scale() + " (≈ " + volume + " клеток схемы), память JVM "
                    + Runtime.getRuntime().maxMemory() / (1L << 20) + " МБ");

            if (quality)
            {
                this.k.check("«Качественно»: предел клеток больше обычного", cells > SchematicLibrary.PREVIEW_CELLS);

                if (volume <= cells)
                {
                    this.k.check("«Качественно»: снимок большой схемы честный (масштаб 1)", 1, shot.scale());
                }
                else
                {
                    this.k.info("качество: схема больше предела памяти — снимок грубый, так и задумано");
                }
            }
            else if (volume > SchematicLibrary.PREVIEW_CELLS)
            {
                this.k.check("«Оптимизировано»: снимок большой схемы грубый", true, shot.scale() > 1);
            }

            return true;
        };
    }

    /**
     * Постер большой схемы в «Качественно» — та же сборка модели, что у превью вкладки: видно глазами, честные ли блоки
     * ({@code <выход>/posters/quality-big.png}); упрощён — строкой INFO, сколько памяти не хватило.
     */
    private DumpSchematics.Action bigPoster()
    {
        return mc ->
        {
            if (this.big == null)
            {
                return true;
            }

            if (this.poster == null)
            {
                this.posterAt = Util.getMillis();
                this.poster = PreviewPoster.requestResult(this.big, "");
                return false;
            }

            if (! this.poster.isDone())
            {
                return false;
            }

            PreviewPoster.Result result = this.poster.getNow(null);
            this.poster = null;

            if (result == null || result.png().length == 0)
            {
                this.k.fail("качество: постер большой схемы не пришёл");
                return true;
            }

            try
            {
                Path out = this.k.outDir().resolve("posters");
                Files.createDirectories(out);
                Files.write(out.resolve("quality-big.png"), result.png());
            }
            catch (IOException e)
            {
                this.k.fail("качество: постер не записан: " + e);
            }

            this.k.info("качество: постер большой схемы за " + (Util.getMillis() - this.posterAt) + " мс, модель "
                    + (result.modelShown() ? "есть" : "нет") + ", " + (result.simplified()
                    ? "упрощённая (кубы " + result.cubeSize() + ")" : "честными блоками")
                    + (result.failure() == null ? "" : ", сбой: " + result.failure()));
            this.k.check("«Качественно»: модель большой схемы на постере есть", result.modelShown());
            return true;
        };
    }

    /** Копия самой большой схемы игрока, которую положили шаги постера ({@code <выход>/poster-src}). */
    private @Nullable SchematicEntry biggestCopy()
    {
        Path dir = this.k.outDir().resolve("poster-src");

        try (Stream<Path> files = Files.list(dir))
        {
            Path best = files.filter(p -> SchematicFormat.of(p) != null && ! p.getFileName().toString().startsWith("k-"))
                    .max(Comparator.comparingLong(KQuality::size)).orElse(null);

            return best == null ? null : SchematicLibrary.get().entry(best);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private static long size(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    private @Nullable Modal topModal()
    {
        Layers layers = KTrash.field(this.k.gui(), "layers");
        return layers != null && layers.top() instanceof Modal modal ? modal : null;
    }
}
