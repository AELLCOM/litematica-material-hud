package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Кнопка «Папка с выгрузками» (0.0.27) — в подвале окна выгрузки и вкладки «Материалы». Открывает
 * проводник: после выгрузки в этой игре — с выделенным последним файлом (так же, как «Показать в
 * папке» тоста), до неё — папку выгрузок из настроек (её нет — создаётся).
 *
 * <p>После каждой выгрузки в файл кнопка загорается золотом ({@link Ui#C_GOLD}, как лупа поиска
 * настроек) и плавно гаснет за {@link #GLOW_MS}: игрок нажал «Excel», закрыл окно — и видит, куда
 * идти за файлом. Время выгрузки общее на игру, поэтому горят обе кнопки, где бы игрок ни оказался.
 * Буфер обмена (Копировать, Discord) и «Таблица команды» файлов не пишут — кнопку не зажигают.
 */
@Environment(EnvType.CLIENT)
public final class ExportsFolder
{
    /** Сколько гаснет кнопка после выгрузки. */
    public static final long GLOW_MS = 30_000L;
    /** Первая секунда — полное золото: игрок ещё смотрит на тост «Excel: …» и должен заметить кнопку. */
    private static final long HOLD_MS = 1_000L;
    /** Заливка при полном свечении — заметнее главной кнопки ({@code Button.FILL_IDLE} 0,16). */
    private static final float GLOW_FILL = 0.30F;

    private static boolean exported;
    private static long exportedAt;
    private static @Nullable Path lastFile;
    /** Сдвиг часов свечения — только харнесс ({@link TestHooks#shiftClock}). */
    private static long clockShift;

    private ExportsFolder() {}

    /** Новая кнопка (у каждого подвала своя; свечение общее). */
    public static Button button()
    {
        return new FolderButton();
    }

    /** Выгрузка записана (файл, у картинки — её папка) — клиентский поток. */
    static void exported(Path file)
    {
        lastFile = file.toAbsolutePath().normalize();
        exportedAt = Util.getMillis();
        exported = true;
    }

    /**
     * Свечение: 1 — только что выгрузили, 0 — обычный вид. Секунду горит полностью, потом гаснет с
     * замедлением (ease-out, квадрат остатка): сразу заметно тускнеет, а под конец едва тлеет — без
     * резкого «выключения». Часы — {@code Util.getMillis()}.
     */
    public static float glow()
    {
        if (! exported)
        {
            return 0.0F;
        }

        long elapsed = sinceExport();

        if (elapsed >= GLOW_MS)
        {
            return 0.0F;
        }

        if (elapsed <= HOLD_MS)
        {
            return 1.0F;
        }

        float left = 1.0F - (elapsed - HOLD_MS) / (float) (GLOW_MS - HOLD_MS);

        return left * left;
    }

    private static long sinceExport()
    {
        return Util.getMillis() + clockShift - exportedAt;
    }

    /** Последняя выгрузка (файл или папка картинок), если она ещё на месте; {@code null} — не было или убрали. */
    static @Nullable Path lastFile()
    {
        Path file = lastFile;

        return file != null && Files.exists(file) ? file : null;
    }

    /**
     * Папка, которую откроет кнопка: последнего файла (список для Litematica лежит не в папке выгрузок,
     * а в {@code material-lists} схем) или папка выгрузок.
     */
    static Path folder()
    {
        Path file = lastFile();
        Path parent = file == null ? null : file.getParent();

        return parent != null ? parent : SchematicsConfig.exportDir();
    }

    static void open()
    {
        Path file = lastFile();

        if (file != null)
        {
            SystemOpen.reveal(file);
        }
        else
        {
            SystemOpen.openFolder(SchematicsConfig.exportDir());
        }
    }

    /** Подсказка: путь папки и что именно откроется. */
    private static List<String> tooltip(boolean withLabel)
    {
        List<String> lines = new ArrayList<>(3);
        Path file = lastFile();

        if (withLabel)
        {
            lines.add(DialogText.t("folder"));
        }

        lines.add(DialogText.shortPath(folder()));
        lines.add(file != null ? DialogText.f("folder.last", file.getFileName()) : DialogText.t("folder.tip"));

        return lines;
    }

    /** Обычная вторичная кнопка со значком папки, поверх — золото свечения. */
    private static final class FolderButton extends Button
    {
        private boolean compact;

        FolderButton()
        {
            super(DialogText.t("folder"), Style.SECONDARY, ExportsFolder::open);
            this.glyph(Glyphs.OPEN_FOLDER);
        }

        /** Только значок (тесный подвал); подпись уходит в подсказку. */
        void compact(boolean value)
        {
            this.compact = value;
            this.label(value ? "" : DialogText.t("folder"));
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            super.render(ctx, mouseX, mouseY);

            if (! this.visible || this.w <= 0 || this.h <= 0)
            {
                return;
            }

            boolean over = this.contains(mouseX, mouseY);
            float glow = glow();

            if (glow > 0.0F && this.enabled)
            {
                // Поверх обычного вида: при glow → 0 золото тает до прежней кнопки без скачка
                // Ванильный стиль: заливку кнопки не затемняем золотом — только золотая рамка (Style здесь — Button.Style)
                if (com.aell.layerlist.shaded.ui.Style.vanilla())
                {
                    com.aell.layerlist.shaded.ui.Vanilla.outline(ctx, this.x, this.y, this.w, this.h,
                            Ui.alpha(Ui.C_GOLD, glow));
                }
                else
                {
                    Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_GOLD, GLOW_FILL * glow));
                    Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_GOLD, glow));
                }

                this.drawFace(ctx, Kit.font(), Paint.mix(over ? Ui.C_TEXT : Ui.C_TEXT_2, Ui.C_GOLD, glow));
            }

            if (over)
            {
                Tooltip.offerLines(this, ExportsFolder.tooltip(this.compact), Ui.C_TEXT);
            }
        }
    }

    /** Только для харнесса (режим k): часы свечения и что видит кнопка. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Сдвинуть часы свечения на {@code ms} вперёд (0 — настоящие). */
        public static void shiftClock(long ms)
        {
            clockShift = ms;
        }

        /** Часы свечения — ровно через {@code ms} после последней выгрузки (сдвиг считается сам). */
        public static void age(long ms)
        {
            clockShift = exportedAt + ms - Util.getMillis();
        }

        /** Сколько прошло с последней выгрузки по часам свечения; выгрузок не было — {@code -1}. */
        public static long sinceExport()
        {
            return exported ? ExportsFolder.sinceExport() : -1L;
        }

        public static @Nullable Path lastFile()
        {
            return ExportsFolder.lastFile();
        }

        public static Path folder()
        {
            return ExportsFolder.folder();
        }

        public static boolean isFolderButton(@Nullable Object button)
        {
            return button instanceof FolderButton;
        }

        public static boolean compact(Button button)
        {
            return button instanceof FolderButton folder && folder.compact;
        }

        public static List<String> tooltip(Button button)
        {
            return ExportsFolder.tooltip(compact(button));
        }
    }

    /** Подвал «Материалов»: свернуть кнопку до значка, когда подписи не хватает места. */
    public static void compact(Button button, boolean value)
    {
        if (button instanceof FolderButton folder)
        {
            folder.compact(value);
        }
    }
}
