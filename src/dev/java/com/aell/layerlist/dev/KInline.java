package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportFormat;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.export.ExportsFolder;
import com.aell.layerlist.schematics.gui.export.SystemOpen;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27: итог выгрузки — в строке окна, а не тостом (тост ложился на «Закрыть» и «Папку с
 * выгрузками»).
 * <ul>
 *   <li>после выгрузки из окна: в строке итога имя файла и кнопки «Открыть файл», «Показать в папке»; тостов
 *       нет (стопка экрана спрятана окном, новых не добавилось); строка и её кнопки — в видимой части тела,
 *       выше подвала окна;</li>
 *   <li>«Показать в папке» — настоящим кликом мыши (событие SDL через {@code MouseHandler}): открытие
 *       перехвачено ({@link SystemOpen.TestHooks#intercept}) — проводник не открывается, проверяется, что
 *       выделили бы именно этот файл;</li>
 *   <li>окно закрыли, пока пишется CSV, — итог тостом экрана: над подвалом, не на его кнопках, с теми же
 *       действиями; «Показать в папке» тоста — тоже мышью;</li>
 *   <li>«Очистить» в «Кто собирает» — сообщение с «Вернуть» под полем, без тоста; «Вернуть» мышью
 *       возвращает ростер.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class KInline
{
    private final DumpSchematics k;
    /** Что открыли бы в системе (перехват {@link SystemOpen}). */
    private final List<String> opened = new ArrayList<>();
    private int toastsBefore;
    private @Nullable List<String> rosterBefore;
    /** Последний файл до CSV без окна: итог пришёл — файл сменился. */
    private @Nullable Path lastBefore;

    KInline(DumpSchematics k)
    {
        this.k = k;
    }

    /** Перехватить открытия в системе на весь прогон: клик по кнопке не должен открыть проводник. */
    void intercept()
    {
        SystemOpen.TestHooks.intercept(this.opened::add);
    }

    void cleanup()
    {
        SystemOpen.TestHooks.intercept(null);
    }

    private @Nullable Layers layers()
    {
        return KProbe.fieldOfType(this.k.gui(), Layers.class);
    }

    private @Nullable ExportWindow window()
    {
        Layers layers = this.layers();

        return layers != null && layers.top() instanceof ExportWindow window ? window : null;
    }

    private @Nullable Toasts toasts()
    {
        return KProbe.fieldOfType(this.k.gui(), Toasts.class);
    }

    /** Запомнить, сколько тостов было до действия в окне. */
    boolean markToasts()
    {
        this.toastsBefore = views(this.toasts()).size();
        return true;
    }

    // ===== Итог в строке окна =====

    /** После выгрузки {@code file} из окна: строка итога, кнопки, тостов нет, всё выше подвала. */
    boolean resultInWindow(@Nullable Path file)
    {
        ExportWindow window = this.window();

        if (window == null || file == null)
        {
            this.k.fail("итог в окне: окна выгрузки или файла нет");
            return true;
        }

        String name = file.getFileName().toString();
        String text = ExportWindow.TestHooks.statusText(window);
        List<String> actions = ExportWindow.TestHooks.statusActions(window);
        this.k.check("строка итога окна называет файл «" + name + "»", true, text != null && text.contains(name));
        this.k.check("строка итога окна — зелёная", true, ExportWindow.TestHooks.statusColor(window) == Theme.GREEN);
        this.k.check("строка итога окна: кнопки", List.of(I18n.get("layerlist.schematics.export.dialog.open_file"),
                I18n.get("layerlist.schematics.export.dialog.reveal")), actions);
        this.noToasts(window, name);
        this.insideBody(window, "строка итога", ExportWindow.TestHooks.statusBox(window));

        for (String label : actions)
        {
            this.insideBody(window, "кнопка «" + label + "»", ExportWindow.TestHooks.statusAction(window, label));
        }

        return true;
    }

    /** Пока окно открыто — тостов нет: новых не добавилось, стопка спрятана, текста итога ни в одном нет. */
    private void noToasts(ExportWindow window, String name)
    {
        Toasts toasts = this.toasts();
        List<ToastView> views = views(toasts);
        this.k.check("окно открыто: новых тостов нет", this.toastsBefore, views.size());
        this.k.check("окно открыто: стопка тостов спрятана окном", true, toasts != null && owner(toasts) == window);
        this.k.check("окно открыто: ни один тост не говорит об итоге", true,
                views.stream().noneMatch(view -> view.text().contains(name)));
        this.k.check("окно открыто: тосты не ловят мышь (угол подвала окна)", false,
                toasts != null && toasts.contains(bound(window, "x") + bound(window, "w") - 4.0,
                        bound(window, "y") + bound(window, "h") - 4.0));
    }

    /** Прямоугольник {@code box} — целиком в видимой части тела окна и выше его подвала. */
    private void insideBody(ExportWindow window, String what, int @Nullable [] box)
    {
        if (box == null)
        {
            this.k.fail(what + ": нет на экране");
            return;
        }

        int bodyY = bound(window, "bodyY");
        int bodyBottom = bodyY + bound(window, "bodyH");
        int footerY = bound(window, "y") + bound(window, "h") - Ui.FOOTER_H;
        boolean inside = box[3] > 0 && box[1] >= bodyY && box[1] + box[3] <= bodyBottom && box[1] + box[3] <= footerY;
        this.k.check(what + " видна целиком и выше подвала окна (" + box[1] + "…" + (box[1] + box[3]) + ", тело " + bodyY
                + "…" + bodyBottom + ", подвал с " + footerY + ")", true, inside);

        for (Button button : footerButtons(window))
        {
            this.k.check(what + " не на кнопке подвала «" + button.label() + "»", false, overlaps(box, button));
        }
    }

    /** Кнопка строки итога настоящим кликом мыши. */
    boolean clickStatusAction(String key)
    {
        ExportWindow window = this.window();
        int[] box = window == null ? null : ExportWindow.TestHooks.statusAction(window, I18n.get(key));

        if (box == null)
        {
            this.k.fail("строка итога: нет кнопки «" + I18n.get(key) + "»");
            return true;
        }

        this.opened.clear();
        realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    /** Клик ушёл в «Показать в папке»: выделили бы {@code target}, окно на месте (если было). */
    boolean revealed(@Nullable Path target, boolean windowOpen)
    {
        String expected = target == null ? "?" : "reveal " + target.toAbsolutePath().normalize();
        this.k.check("«Показать в папке» мышью — выделит файл", List.of(expected), List.copyOf(this.opened));
        this.k.check(windowOpen ? "окно выгрузки после клика открыто" : "окна выгрузки нет", windowOpen, this.window() != null);
        return true;
    }

    // ===== Окно закрыли раньше, чем записался файл =====

    /** CSV в окне (иконок не ждёт — сразу в фон) и тут же Esc: итог придёт уже без окна. */
    boolean csvThenClose(Minecraft mc)
    {
        ExportWindow window = this.window();
        Object grid = window == null ? null : KProbe.fieldNamedType(window, "FormatGrid");
        Button csv = null;

        try
        {
            csv = grid == null ? null : (Button) KProbe.call(grid, "button", ExportFormat.CSV);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("сетка форматов не читается: " + e);
        }

        if (csv == null || ! csv.enabled())
        {
            this.k.fail("CSV в окне: кнопки нет или она выключена");
            return true;
        }

        Path before = ExportsFolder.TestHooks.lastFile();
        this.markToasts();
        KProbe.pressDirect(csv);
        this.k.closeExportDialog(mc);
        // Итог отдаётся клиентскому потоку — он занят этим шагом, раньше закрытия окна итог прийти не мог
        this.k.check("окно закрыто раньше, чем пришёл итог CSV", true, Objects.equals(before, ExportsFolder.TestHooks.lastFile()));
        this.lastBefore = before;
        return true;
    }

    /** Итог CSV — тостом экрана: над подвалом, не на его кнопках, с действиями; кнопка папки горит. */
    boolean closedToast()
    {
        Path last = ExportsFolder.TestHooks.lastFile();

        if (last == null || last.equals(this.lastBefore))
        {
            return false;
        }

        GuiSchematics gui = this.k.gui();
        String name = last.getFileName().toString();
        this.k.check("CSV записан после закрытия окна: " + name, true, name.endsWith(".csv"));
        ToastView toast = views(this.toasts()).stream().filter(view -> view.text().contains(name)).findFirst().orElse(null);

        if (gui == null || toast == null)
        {
            this.k.fail("итог CSV после закрытия окна: тоста с «" + name + "» нет");
            return true;
        }

        int footerY = gui.height - footerH(gui);
        this.k.check("тост итога — над подвалом экрана (низ " + (toast.y() + toast.h()) + ", подвал с " + footerY + ")", true,
                toast.y() + toast.h() <= footerY - Ui.PAD_MICRO);
        this.k.check("тост итога — в экране", true, toast.x() >= 0 && toast.x() + toast.w() <= gui.width && toast.y() >= 0);

        for (Button button : screenFooter(gui))
        {
            this.k.check("тост итога не на кнопке подвала «" + button.label() + "»", false,
                    overlaps(new int[] { toast.x(), toast.y(), toast.w(), toast.h() }, button));
        }

        this.k.check("тост итога: кнопки", List.of(I18n.get("layerlist.schematics.export.dialog.open_file"),
                I18n.get("layerlist.schematics.export.dialog.reveal")), toast.actions());
        this.k.check("кнопка папки горит после записи без окна (" + ExportsFolder.glow() + ")", true, ExportsFolder.glow() >= 0.95F);
        return true;
    }

    /** Действие тоста настоящим кликом мыши. */
    boolean clickToastAction(String key)
    {
        String label = I18n.get(key);

        for (ToastView view : views(this.toasts()))
        {
            int i = view.actions().indexOf(label);

            if (i >= 0 && i < view.boxes().size())
            {
                int[] box = view.boxes().get(i);
                this.opened.clear();
                realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
                return true;
            }
        }

        this.k.fail("тост: нет кнопки «" + label + "»");
        return true;
    }

    boolean revealedLast()
    {
        return this.revealed(ExportsFolder.TestHooks.lastFile(), false);
    }

    boolean clearToasts()
    {
        Toasts toasts = this.toasts();

        if (toasts != null)
        {
            toasts.clear();
        }

        return true;
    }

    // ===== «Кто собирает»: «Очистить» → «Вернуть» =====

    /** «Очистить» через экран; под полем — «очищено» с «Вернуть», тостов не прибавилось. */
    boolean clearRoster()
    {
        ExportWindow window = this.window();
        GuiSchematics gui = this.k.gui();
        Object roster = window == null ? null : KProbe.fieldNamedType(window, "RosterField");
        Button clear = KProbe.button(roster, "layerlist.schematics.export.dialog.roster.clear");

        if (gui == null || window == null || clear == null)
        {
            this.k.fail("«Очистить»: окна или кнопки нет");
            return true;
        }

        this.rosterBefore = List.copyOf(SchematicsState.get().roster());
        this.markToasts();
        this.k.clickButton(gui, clear, null);
        this.k.check("«Очистить» опустошил ростер (было " + this.rosterBefore.size() + ")", true,
                SchematicsState.get().roster().isEmpty() && ! this.rosterBefore.isEmpty());

        String note = ExportWindow.TestHooks.rosterNote(window);
        this.k.check("под полем — «очищено», ников " + this.rosterBefore.size(), I18n.get(
                "layerlist.schematics.export.dialog.roster.cleared", this.rosterBefore.size()), note);
        this.k.check("у сообщения — кнопка «Вернуть»", List.of(I18n.get("layerlist.schematics.export.dialog.roster.undo")),
                ExportWindow.TestHooks.rosterNoteActions(window));
        this.k.check("«Очистить»: новых тостов нет", this.toastsBefore, views(this.toasts()).size());
        return true;
    }

    /** «Вернуть» — на месте (окно уже разложило строку после «Очистить») и нажат настоящим кликом. */
    boolean clickUndo()
    {
        ExportWindow window = this.window();
        int[] box = window == null ? null : ExportWindow.TestHooks.rosterNoteAction(window,
                I18n.get("layerlist.schematics.export.dialog.roster.undo"));

        if (window == null || box == null)
        {
            this.k.fail("«Вернуть»: кнопки нет");
            return true;
        }

        this.insideBody(window, "«Вернуть»", box);
        realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    boolean undone()
    {
        ExportWindow window = this.window();
        this.k.check("«Вернуть» мышью вернул ростер", this.rosterBefore, SchematicsState.get().roster());
        this.k.check("после «Вернуть» сообщения нет", true, window != null && ExportWindow.TestHooks.rosterNote(window) == null);
        return true;
    }

    /** Курсор SDL — к левому краю: подсказка под настоящей мышью не ложится на снимок. */
    boolean mouseAside()
    {
        Minecraft mc = Minecraft.getInstance();
        DevInput.onMove(mc.mouseHandler, mc.getWindow().handle(), 2.0, mc.getWindow().getScreenHeight() / 2.0, 0.0, 0.0);
        return true;
    }

    // ===== Внутреннее =====

    /** Левый клик в точку GUI тем же путём, что событие мыши SDL: перемещение, нажатие, отпускание. */
    static void realClick(double guiX, double guiY)
    {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double x = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
        MouseButtonInfo left = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);

        DevInput.onMove(mc.mouseHandler, window.handle(), x, y, 0.0, 0.0);
        DevInput.onButton(mc.mouseHandler, window.handle(), left, InputConstants.PRESS);
        DevInput.onButton(mc.mouseHandler, window.handle(), left, InputConstants.RELEASE);
    }

    /** Тост, как его разложила стопка: текст, прямоугольник, подписи и места кнопок. */
    private record ToastView(String text, int x, int y, int w, int h, List<String> actions, List<int[]> boxes) {}

    private static List<ToastView> views(@Nullable Toasts toasts)
    {
        List<ToastView> out = new ArrayList<>();
        Object list = toasts == null ? null : read(Toasts.class, toasts, "toasts");

        if (! (list instanceof List<?> items))
        {
            return out;
        }

        for (Object toast : items)
        {
            List<String> labels = new ArrayList<>();
            List<int[]> boxes = new ArrayList<>();

            if (read(toast.getClass(), toast, "actions") instanceof List<?> actions)
            {
                for (Object action : actions)
                {
                    if (action instanceof Toasts.Action a)
                    {
                        labels.add(a.label());
                    }
                }
            }

            if (read(toast.getClass(), toast, "actionBoxes") instanceof List<?> places)
            {
                for (Object place : places)
                {
                    if (place instanceof int[] box)
                    {
                        boxes.add(box);
                    }
                }
            }

            out.add(new ToastView(String.valueOf(read(toast.getClass(), toast, "text")), num(toast, "x"), num(toast, "y"),
                    num(toast, "w"), num(toast, "h"), labels, boxes));
        }

        return out;
    }

    private static @Nullable Object owner(Toasts toasts)
    {
        return read(Toasts.class, toasts, "owner");
    }

    private static int num(Object owner, String name)
    {
        return read(owner.getClass(), owner, name) instanceof Integer value ? value : -1;
    }

    private static int bound(Modal modal, String name)
    {
        return read(Modal.class, modal, name) instanceof Integer value ? value : -1;
    }

    private static int footerH(GuiSchematics gui)
    {
        return read(GuiSchematics.class, gui, "footerH") instanceof Integer value ? value : Ui.FOOTER_H;
    }

    private static List<Button> footerButtons(Modal modal)
    {
        return buttons(read(Modal.class, modal, "buttons"));
    }

    /** Кнопки подвала экрана: «Назад» и правые. */
    private static List<Button> screenFooter(GuiSchematics gui)
    {
        List<Button> out = buttons(read(GuiSchematics.class, gui, "footerButtons"));

        if (read(GuiSchematics.class, gui, "back") instanceof Button back)
        {
            out.add(back);
        }

        return out;
    }

    private static List<Button> buttons(@Nullable Object value)
    {
        List<Button> out = new ArrayList<>();

        if (value instanceof List<?> list)
        {
            for (Object o : list)
            {
                if (o instanceof Button button && button.visible() && button.w() > 0)
                {
                    out.add(button);
                }
            }
        }

        return out;
    }

    private static boolean overlaps(int[] box, Button button)
    {
        return box[0] < button.x() + button.w() && button.x() < box[0] + box[2]
                && box[1] < button.y() + button.h() && button.y() < box[1] + box[3];
    }

    private static @Nullable Object read(Class<?> type, Object owner, String name)
    {
        try
        {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            CovOut.log(DumpSchematics.TAG + type.getSimpleName() + "." + name + " не читается: " + e);
            return null;
        }
    }
}
