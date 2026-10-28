package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27: удаление в «Библиотеке» и «Корзина». Всё — обычным вводом экрана: Delete настоящей
 * клавишей ({@code KeyboardHandler.keyPress}), меню ПКМ, кнопки окон и тостов кликом в их точку.
 * <ul>
 *   <li>Delete на выбранной схеме → окно с именем файла → «Удалить»: файл в корзине, выбор — на соседа,
 *       тост с «Отменить» → «Отменить»: файл на месте, тост ушёл;</li>
 *   <li>пустая папка — через меню ПКМ «Удалить…»; у непустой пункт выключен;</li>
 *   <li>схема с размещением: в окне — предупреждение и второй выбор. «Удалить» — размещение остаётся;
 *       «Удалить и убрать размещения» — размещения и схемы в «Загруженных» нет, «Отменить» возвращает всё;</li>
 *   <li>строка «Корзина (N)» внизу корня → вкладка «Корзина»: откуда, размер, дата, «осталось 30 дн.»; «Восстановить» в
 *       занятое имя → «Имя (2)»; «Удалить навсегда» с подтверждением; запись, состаренная на 31 день, удаляется
 *       при открытии экрана, на 25 дней — «осталось 5 дн.»; «Очистить корзину (N)» с подтверждением.</li>
 * </ul>
 * Корзина прогона — своя папка в {@code LL_COVERAGE_OUT} ({@link SchematicTrash.TestHooks#root}).
 */
@Environment(EnvType.CLIENT)
final class KTrash
{
    private static final int SCANCODE_MASK = 1 << 30;
    private static final String A = "trash-a.litematic";
    private static final String B = "trash-b.litematic";
    private static final String C = "trash-c.litematic";
    private static final String EMPTY = "empty-folder";
    private static final String FULL = "full-folder";

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();
    private Path dir = Path.of(".");
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable SchematicPlacement placed;
    private @Nullable LitematicaSchematic placedSchematic;

    KTrash(DumpSchematics k)
    {
        this.k = k;
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    // ===== Сценарий =====

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "корзина: своя папка и схемы для удаления", 5_000L, mc -> this.prepare(p)));
        s.add(step(p + "корзина: открыть папку теста", 5_000L, mc -> this.openDir()));
        s.add(step(p + "Delete: выбрать " + A, 5_000L, mc -> this.select(A)));
        s.add(step(p + "Delete настоящей клавишей → окно", 3_000L, this.k.settleThen(200L, mc -> this.deleteKey(A, false))));
        s.add(step(p + "снимок: подтверждение удаления", 5_000L, cleanShot(this.k, p + "delete-confirm")));
        s.add(step(p + "«Удалить» → файл в корзине", 3_000L, mc -> this.confirmDelete(A, B)));
        s.add(step(p + "снимок: тост с «Отменить»", 5_000L, cleanShot(this.k, p + "delete-toast")));
        s.add(step(p + "«Отменить» в тосте → файл на месте", 3_000L, mc -> this.undo(A, false)));
        s.add(step(p + "снимок: файл вернулся", 5_000L, cleanShot(this.k, p + "undo-restored")));
        s.add(step(p + "меню ПКМ: у непустой папки «Удалить…» выключен", 3_000L, this.k.settleThen(200L, mc -> this.fullFolderMenu())));
        s.add(step(p + "меню ПКМ «Удалить…» пустой папки → окно", 3_000L, this.k.settleThen(200L, mc -> this.emptyFolderMenu())));
        s.add(step(p + "«Удалить» → папка в корзине", 3_000L, this.k.settleThen(200L, mc -> this.confirmFolder())));
        s.add(step(p + "размещение схемы " + B, 15_000L, mc -> this.place(B)));
        s.add(step(p + "Delete: выбрать " + B, 5_000L, mc -> this.select(B)));
        s.add(step(p + "Delete на размещённой → предупреждение", 3_000L, this.k.settleThen(200L, mc -> this.deleteKey(B, true))));
        s.add(step(p + "снимок: удаление размещённой схемы", 5_000L, cleanShot(this.k, p + "delete-placed-confirm")));
        s.add(step(p + "«Удалить»: размещение осталось", 3_000L, mc -> this.deleteKeep(B)));
        s.add(step(p + "«Отменить» → файл на месте, размещение то же", 3_000L, this.k.settleThen(300L, mc -> this.undo(B, true))));
        s.add(step(p + "Delete снова на " + B, 5_000L, this.k.settleThen(200L, mc -> this.select(B) && this.deleteKey(B, true))));
        s.add(step(p + "«Удалить и убрать размещения»", 3_000L, this.k.settleThen(200L, mc -> this.deleteWithPlacements(B))));
        s.add(step(p + "снимок: схема и размещения убраны", 5_000L, cleanShot(this.k, p + "delete-placed-removed")));
        s.add(step(p + "«Отменить» → файл, схема и размещение вернулись", 3_000L, mc -> this.undoPlacements(B)));
        s.add(step(p + "снимок: размещение вернулось", 5_000L, cleanShot(this.k, p + "undo-placements-back")));
        s.add(step(p + "в корзину " + A + ", " + B + ", " + C + " (Delete, Enter)", 10_000L, mc -> this.trashThree()));
        s.add(step(p + "корень: строка «Корзина (N)» внизу", 5_000L, this.k.settleThen(200L, mc -> this.trashRow(4))));
        s.add(step(p + "End, Enter → вкладка «Корзина»", 3_000L, mc -> this.openTrashByKeys()));
        s.add(step(p + "вкладка «Корзина»: откуда, размер, дата, срок", 3_000L, this.k.settleThen(300L, mc -> this.dialogRows(4))));
        s.add(step(p + "снимок: вкладка «Корзина»", 5_000L, cleanShot(this.k, p + "trash-tab")));
        s.add(step(p + "«Восстановить» в занятое имя → «(2)»", 3_000L, mc -> this.restoreClash()));
        s.add(step(p + "снимок: восстановлено под другим именем", 5_000L, cleanShot(this.k, p + "trash-restored-renamed")));
        s.add(step(p + "«Удалить навсегда» → подтверждение", 3_000L, this.k.settleThen(200L, mc -> this.forever(B))));
        s.add(step(p + "снимок: подтверждение «Удалить навсегда»", 5_000L, cleanShot(this.k, p + "trash-forever-confirm")));
        s.add(step(p + "подтвердить «Удалить навсегда»", 3_000L, mc -> this.foreverConfirm(B)));
        s.add(step(p + "состарить записи: 31 и 25 дней", 3_000L, mc -> this.backdate()));
        s.add(step(p + "экран заново → просроченное удалено", 10_000L, this.reopen()));
        s.add(step(p + "после чистки: «осталось 5 дн.», тост", 3_000L, this.k.settleThen(300L, mc -> this.afterPurge())));
        s.add(step(p + "снимок: вкладка «Корзина» после чистки", 5_000L, cleanShot(this.k, p + "trash-after-purge")));
        s.add(step(p + "«Очистить корзину (N)» → подтверждение", 3_000L, this.k.settleThen(200L, mc -> this.emptyAsk())));
        s.add(step(p + "снимок: подтверждение очистки", 5_000L, cleanShot(this.k, p + "trash-empty-confirm")));
        s.add(step(p + "очистить: корзина пуста", 3_000L, mc -> this.emptyConfirm()));
        s.add(step(p + "снимок: корзина пуста", 5_000L, cleanShot(this.k, p + "trash-emptied")));
        s.add(step(p + "на «Библиотеку», строки «Корзина» нет", 3_000L, this.k.settleThen(200L, mc -> this.closeDialog())));
        s.add(step(p + "корзина: убрать за собой", 5_000L, mc -> this.tidy()));
        return s;
    }

    void cleanup()
    {
        this.tidy();
        SchematicTrash.TestHooks.root(null);
    }

    /** Снимок без подсказки под курсором: мышь — в пустой угол шапки (вход событий мыши), потом кадры и снимок. */
    static DumpSchematics.Action cleanShot(DumpSchematics k, String name)
    {
        boolean[] moved = {false};
        DumpSchematics.Action shot = k.settleShot(name);

        return mc ->
        {
            if (! moved[0])
            {
                moved[0] = true;
                DevInput.onMove(mc.mouseHandler, mc.getWindow().handle(), 2.0, 2.0, 0.0, 0.0);
            }

            return shot.run(mc);
        };
    }

    // ===== Подготовка =====

    private boolean prepare(String p)
    {
        // Экран мог закрыться (шаги клавиши) — открыть и встать на «Библиотеку»
        if (this.k.gui() == null)
        {
            GuiSchematics.open(null);
            return false;
        }

        this.k.setTab("LIBRARY");
        SchematicTrash.TestHooks.root(null);
        Path expected = FabricLoader.getInstance().getGameDir().resolve(SchematicTrash.FOLDER).toAbsolutePath().normalize();
        this.k.check("корзина по умолчанию — <папка игры>/material-hud-trash", expected, SchematicTrash.get().root());
        this.k.check("корзина хранит дней", 30, SchematicTrash.KEEP_DAYS);

        SchematicEntry sample = this.k.generated(".litematic");

        if (sample == null)
        {
            this.k.fail("корзина: нет k-generated.litematic");
            return true;
        }

        try
        {
            Path trash = this.k.outDir().resolve(p.isEmpty() ? "trash" : "trash-s3");
            KData.recreate(trash);
            SchematicTrash.TestHooks.root(trash);

            this.dir = SchematicLibrary.get().root().resolve(p.isEmpty() ? "_k_trash" : "_k_trash_s3").toAbsolutePath().normalize();
            KData.recreate(this.dir);
            this.dirs.add(this.dir);

            for (String name : List.of(A, B, C))
            {
                Files.copy(sample.path(), this.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.createDirectories(this.dir.resolve(EMPTY));
            Files.createDirectories(this.dir.resolve(FULL));
            Files.writeString(this.dir.resolve(FULL).resolve("note.txt"), "not a schematic");
        }
        catch (IOException | RuntimeException e)
        {
            this.k.fail("корзина: подготовка — " + e);
        }

        this.k.check("корзина прогона пуста", 0, SchematicTrash.get().count());
        return true;
    }

    // ===== Помощники экрана =====

    private @Nullable GuiSchematics gui()
    {
        return this.k.gui();
    }

    private @Nullable Layers layers()
    {
        return KProbe.fieldOfType(this.gui(), Layers.class);
    }

    private @Nullable Toasts toasts()
    {
        return KProbe.fieldOfType(this.gui(), Toasts.class);
    }

    private @Nullable ScrollList<?> fileList()
    {
        return KProbe.fieldOfType(this.k.files(), ScrollList.class);
    }

    private Path at(String name)
    {
        return this.dir.resolve(name);
    }

    private boolean openDir()
    {
        Object files = this.k.files();

        if (files == null)
        {
            this.k.fail("корзина: нет панели файлов");
            return true;
        }

        try
        {
            KProbe.call(files, "open", this.dir);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("корзина: open(Path) — " + e);
        }

        this.k.check("папка теста открыта, " + A + " в списке", this.indexOf(this.at(A)) >= 0);
        return true;
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
            SchematicEntry entry = (SchematicEntry) KProbe.recordComponent(items.get(i), SchematicEntry.class);

            if (entry != null && entry.path().toAbsolutePath().normalize().equals(path))
            {
                return i;
            }
        }

        return -1;
    }

    private boolean select(String name)
    {
        SchematicEntry entry = SchematicLibrary.get().entry(this.at(name));

        if (entry == null)
        {
            this.k.fail("корзина: нет записи " + name);
            return true;
        }

        return this.k.selectEntry(entry);
    }

    private @Nullable SchematicEntry selected()
    {
        try
        {
            GuiSchematics gui = this.gui();
            return gui == null ? null : (SchematicEntry) KProbe.call(gui, "selected");
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    /** Клавиша через вход событий SDL ({@code KeyboardHandler.keyPress}), как настоящая. */
    static void realKey(int scancode)
    {
        int keycode = switch (scancode)
        {
            case InputConstants.KEY_RETURN -> '\r';
            case InputConstants.KEY_ESCAPE -> 0x1B;
            case InputConstants.KEY_DELETE -> 0x7F;
            default -> scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z
                    ? 'a' + scancode - InputConstants.KEY_A : scancode | SCANCODE_MASK;
        };
        KeyEvent event = new KeyEvent(scancode, keycode, 0);
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().handle();
        DevInput.keyPress(mc.keyboardHandler, window, InputConstants.PRESS, event);
        DevInput.keyPress(mc.keyboardHandler, window, InputConstants.RELEASE, event);
    }

    private @Nullable Modal topModal()
    {
        Layers layers = this.layers();
        return layers != null && layers.top() instanceof Modal modal ? modal : null;
    }

    @SuppressWarnings("unchecked")
    static <T> @Nullable T field(@Nullable Object owner, String name)
    {
        for (Class<?> c = owner == null ? null : owner.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            try
            {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                // Поле у предка
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                return null;
            }
        }

        return null;
    }

    static String modalTitle(@Nullable Modal modal)
    {
        String title = field(modal, "title");
        return title == null ? "" : title;
    }

    static String modalText(@Nullable Modal modal)
    {
        List<String> paragraphs = field(modal, "paragraphs");
        return paragraphs == null ? "" : String.join("\n", paragraphs);
    }

    static @Nullable Button modalButton(@Nullable Modal modal, String label)
    {
        List<Button> buttons = field(modal, "buttons");

        if (buttons != null)
        {
            for (Button button : buttons)
            {
                if (button.label().equals(label))
                {
                    return button;
                }
            }
        }

        return null;
    }

    private boolean clickModal(String label)
    {
        GuiSchematics gui = this.gui();
        Modal modal = this.topModal();
        Button button = modalButton(modal, label);

        if (gui == null || button == null)
        {
            this.k.fail("корзина: в окне нет кнопки «" + label + "»");
            return false;
        }

        this.k.clickButton(gui, button, null);
        return true;
    }

    private static String t(String suffix)
    {
        return Strings.plain("layerlist.schematics." + suffix);
    }

    private static String f(String suffix, Object... args)
    {
        return I18n.get("layerlist.schematics." + suffix, args);
    }

    private @Nullable SchematicTrash.Item trashed(String name)
    {
        for (SchematicTrash.Item item : SchematicTrash.get().items())
        {
            if (item.name().equals(name))
            {
                return item;
            }
        }

        return null;
    }

    private boolean toastHas(String text)
    {
        Toasts toasts = this.toasts();
        return toasts != null && Toasts.TestHooks.texts(toasts).stream().anyMatch(line -> line.contains(text));
    }

    private boolean clickToast(String label)
    {
        GuiSchematics gui = this.gui();
        Toasts toasts = this.toasts();
        int[] box = toasts == null ? null : Toasts.TestHooks.actionBox(toasts, label);

        if (gui == null || box == null)
        {
            this.k.fail("корзина: у тоста нет кнопки «" + label + "»");
            return false;
        }

        KProbe.click(gui, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    // ===== Удаление файла и «Отменить» =====

    private boolean deleteKey(String name, boolean placedWarning)
    {
        SchematicEntry chosen = this.selected();

        if (chosen == null || ! chosen.path().toAbsolutePath().normalize().equals(this.at(name)))
        {
            this.k.fail("Delete: выбрана не " + name + " (" + chosen + ")");
        }

        realKey(InputConstants.KEY_DELETE);
        Modal modal = this.topModal();

        if (! this.k.check("Delete открыл окно подтверждения (" + name + ")", modal != null))
        {
            return true;
        }

        this.k.check("заголовок окна", t("remove.title"), modalTitle(modal));
        this.k.check("окно называет файл " + name, modalText(modal).contains("«" + name + "»"));
        this.k.check("окно называет корзину и срок 30 дней", modalText(modal).contains("30"));

        boolean second = modalButton(modal, t("remove.with_placements")) != null;
        this.k.check("второй выбор «Удалить и убрать размещения» — только у размещённой (" + name + ")", placedWarning, second);

        if (placedWarning)
        {
            this.k.check("предупреждение о размещениях с числом", modalText(modal).contains(f("remove.placed", 1)));
        }

        return true;
    }

    private boolean confirmDelete(String name, String neighbour)
    {
        if (! this.clickModal(t("remove.yes")))
        {
            return true;
        }

        this.k.check(name + " ушёл из папки", ! Files.exists(this.at(name)));
        SchematicTrash.Item item = this.trashed(name);

        if (this.k.check(name + " в корзине", item != null))
        {
            this.k.check("лежит в <корзина>/<дата>/" + name, Files.isRegularFile(SchematicTrash.get().stored(item)));
            this.k.check("папка удаления — дата-время", item.id().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(-\\d+)?"));
            this.k.check("откуда — запомнено", this.at(name), item.original());
            this.k.check("индекс корзины записан", Files.isRegularFile(SchematicTrash.get().root().resolve("index.json")));
        }

        this.k.check("строки " + name + " в списке нет", -1, this.indexOf(this.at(name)));
        SchematicEntry now = this.selected();
        this.k.check("выбор перешёл на соседа " + neighbour,
                now != null && now.path().toAbsolutePath().normalize().equals(this.at(neighbour)));
        this.k.check("тост называет файл", this.toastHas(name));
        return true;
    }

    private boolean undo(String name, boolean placementStays)
    {
        SchematicPlacement p = this.placed;

        if (! this.clickToast(t("remove.undo")))
        {
            return true;
        }

        this.k.check("«Отменить»: " + name + " снова в папке", Files.isRegularFile(this.at(name)));
        this.k.check("«Отменить»: корзина без " + name, this.trashed(name) == null);
        this.k.check("«Отменить»: строка снова в списке", this.indexOf(this.at(name)) >= 0);
        SchematicEntry now = this.selected();
        this.k.check("«Отменить»: выбран вернувшийся файл",
                now != null && now.path().toAbsolutePath().normalize().equals(this.at(name)));
        // Закрылся именно этот тост; у соседних (папка удалена раньше) «Отменить» живёт своё время
        this.k.check("тост «в корзине» с «Отменить» закрылся сам", ! this.toastHas(f("remove.done", name)));
        this.k.check("тост «возвращено»", this.toastHas(f("remove.undone", name)));

        if (placementStays && p != null)
        {
            this.k.check("размещение на месте после «Отменить»", PlacementOps.all().contains(p));
        }

        return true;
    }

    // ===== Папки =====

    private boolean rightClick(Path path)
    {
        GuiSchematics gui = this.gui();
        ScrollList<?> list = this.fileList();
        int index = this.indexOf(path);

        if (gui == null || list == null || index < 0)
        {
            this.k.fail("ПКМ: нет строки " + path.getFileName());
            return false;
        }

        list.ensureVisible(index);
        double x = list.x() + list.w() / 3.0;

        for (int y = list.y(); y < list.y() + list.h(); ++y)
        {
            if (list.indexAt(x, y + 0.5) == index)
            {
                MouseButtonEvent click = new MouseButtonEvent(x, y + 4.5, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                gui.onMouseClicked(click, false);
                gui.onMouseReleased(click);
                return true;
            }
        }

        this.k.fail("ПКМ: строка " + path.getFileName() + " не на экране");
        return false;
    }

    private @Nullable ContextMenu menu()
    {
        Layers layers = this.layers();
        return layers != null && layers.top() instanceof ContextMenu menu ? menu : null;
    }

    private boolean fullFolderMenu()
    {
        if (! this.rightClick(this.at(FULL)))
        {
            return true;
        }

        ContextMenu menu = this.menu();
        List<ContextMenu.Item> items = field(menu, "items");

        if (this.k.check("ПКМ по папке открыл меню", items != null && ! items.isEmpty()))
        {
            ContextMenu.Item last = items.getLast();
            this.k.check("последний пункт — «Удалить…»", t("menu.delete"), last.label());
            this.k.check("у непустой папки «Удалить…» выключен", ! last.enabled());
            this.k.check("причина в подсказке", t("menu.delete.not_empty"), last.tooltip());
        }

        GuiSchematics gui = this.gui();

        if (gui != null)
        {
            KProbe.press(gui, InputConstants.KEY_ESCAPE);
        }

        return true;
    }

    private boolean emptyFolderMenu()
    {
        GuiSchematics gui = this.gui();

        if (gui == null || ! this.rightClick(this.at(EMPTY)))
        {
            return true;
        }

        // ↑ из ничего — на последний живой пункт («Удалить…»), Enter — выбрать: как с клавиатуры
        KProbe.press(gui, InputConstants.KEY_UP);
        KProbe.press(gui, InputConstants.KEY_RETURN);

        Modal modal = this.topModal();

        if (this.k.check("«Удалить…» пустой папки открыл окно", modal != null))
        {
            this.k.check("заголовок окна папки", t("remove.title_folder"), modalTitle(modal));
            this.k.check("окно называет папку", modalText(modal).contains("«" + EMPTY + "»"));
        }

        return true;
    }

    private boolean confirmFolder()
    {
        if (! this.clickModal(t("remove.yes")))
        {
            return true;
        }

        SchematicTrash.Item item = this.trashed(EMPTY);
        this.k.check("пустая папка ушла", ! Files.exists(this.at(EMPTY)));
        this.k.check("папка в корзине (как папка)", item != null && item.directory()
                && Files.isDirectory(SchematicTrash.get().stored(item)));
        this.k.check("тост о папке", this.toastHas(f("remove.done_folder", EMPTY)));
        return true;
    }

    // ===== Размещённая схема =====

    private boolean place(String name)
    {
        SchematicEntry entry = SchematicLibrary.get().entry(this.at(name));

        if (entry == null)
        {
            this.k.fail("размещение: нет записи " + name);
            return true;
        }

        if (this.loading == null)
        {
            this.loading = SchematicLibrary.get().load(entry);
        }

        if (! this.loading.isDone())
        {
            return false;
        }

        CompletableFuture<LoadedSchematic> done = this.loading;
        this.loading = null;

        if (done.isCompletedExceptionally())
        {
            this.k.fail("размещение: загрузка " + name + " не удалась");
            return true;
        }

        SchematicPlacement p = PlacementOps.placeAt(done.join(), DumpSchematics.STAND.offset(4, 0, 14));
        this.made.add(p);
        this.placed = p;
        this.placedSchematic = p.getSchematic();
        this.k.check("размещений " + name, 1, PlacementOps.of(this.at(name)).size());
        return true;
    }

    private boolean deleteKeep(String name)
    {
        if (! this.clickModal(t("remove.yes")))
        {
            return true;
        }

        this.k.check("«Удалить»: " + name + " в корзине", this.trashed(name) != null);
        this.k.check("«Удалить»: размещение осталось", this.placed != null && PlacementOps.all().contains(this.placed));
        this.k.check("«Удалить»: схема осталась в «Загруженных»", this.holderHas(this.placedSchematic));
        return true;
    }

    private boolean deleteWithPlacements(String name)
    {
        if (! this.clickModal(t("remove.with_placements")))
        {
            return true;
        }

        this.k.check("«…и убрать размещения»: " + name + " в корзине", this.trashed(name) != null);
        this.k.check("«…и убрать размещения»: размещения нет", this.placed != null && ! PlacementOps.all().contains(this.placed));
        this.k.check("«…и убрать размещения»: схемы нет в «Загруженных»", ! this.holderHas(this.placedSchematic));
        this.k.check("тост называет число размещений", this.toastHas(f("remove.done_placements", name, 1)));
        return true;
    }

    private boolean undoPlacements(String name)
    {
        if (! this.clickToast(t("remove.undo")))
        {
            return true;
        }

        this.k.check("«Отменить»: " + name + " снова в папке", Files.isRegularFile(this.at(name)));
        this.k.check("«Отменить»: размещение вернулось", this.placed != null && PlacementOps.all().contains(this.placed));
        this.k.check("«Отменить»: схема снова в «Загруженных»", this.holderHas(this.placedSchematic));
        this.k.check("«Отменить»: размещение снова находится по файлу", 1, PlacementOps.of(this.at(name)).size());
        return true;
    }

    private boolean holderHas(@Nullable LitematicaSchematic schematic)
    {
        return schematic != null && SchematicHolder.getInstance().getAllSchematics().contains(schematic);
    }

    // ===== Окно «Корзина» =====

    private boolean trashThree()
    {
        GuiSchematics gui = this.gui();

        if (gui == null)
        {
            return true;
        }

        for (String name : List.of(A, B, C))
        {
            if (! Files.exists(this.at(name)))
            {
                continue;
            }

            // Сразу за раз: выбрать, Delete, Enter — кнопка по умолчанию окна («Удалить»)
            this.select(name);
            realKey(InputConstants.KEY_DELETE);
            KProbe.press(gui, InputConstants.KEY_RETURN);
            this.k.check("Delete, Enter: " + name + " в корзине", this.trashed(name) != null);
        }

        for (SchematicPlacement p : this.made)
        {
            this.k.check("размещение после Delete+Enter осталось (выбор по умолчанию — оставить)", PlacementOps.all().contains(p));
        }

        Object files = this.k.files();

        try
        {
            if (files != null)
            {
                KProbe.call(files, "open", SchematicLibrary.get().root());
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("корень: " + e);
        }

        return true;
    }

    private boolean trashRow(int expected)
    {
        ScrollList<?> list = this.fileList();

        if (list == null || list.items().isEmpty())
        {
            this.k.fail("корень: список пуст");
            return true;
        }

        Object last = list.items().getLast();
        Object kind = KProbe.recordComponent(last, Enum.class);
        this.k.check("последняя строка корня — «Корзина»", "TRASH", String.valueOf(kind));
        this.k.check("в корзине записей", expected, SchematicTrash.get().count());
        return true;
    }

    private boolean openTrashByKeys()
    {
        GuiSchematics gui = this.gui();

        if (gui == null)
        {
            return true;
        }

        // Строка «Корзина» — в корне «Библиотеки»: экран мог открыться на другой вкладке (она запоминается)
        Object tab = field(gui, "tab");

        if (! "LIBRARY".equals(String.valueOf(tab)))
        {
            this.showTab("LIBRARY");
        }

        KProbe.press(gui, InputConstants.KEY_END);
        KProbe.press(gui, InputConstants.KEY_RETURN);
        this.k.check("End, Enter на «Корзине» открыли вкладку «Корзина»", this.onTrashTab());
        this.k.check("вкладки нет поверх окном", this.topModal() == null);
        return true;
    }

    /** Открыта вкладка «Корзина» (0.0.27: вкладка экрана вместо окна поверх «Библиотеки»). */
    private boolean onTrashTab()
    {
        // Через Object: иначе String.valueOf(T) выберет перегрузку char[]
        Object tab = field(this.gui(), "tab");
        return "TRASH".equals(String.valueOf(tab));
    }

    /** Переключить вкладку экрана, как клик по ней ({@code GuiSchematics.setTab}). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void showTab(String name)
    {
        GuiSchematics gui = this.gui();

        try
        {
            Class type = Class.forName("com.aell.layerlist.schematics.gui.GuiSchematics$Tab");
            KProbe.call(gui, "setTab", Enum.valueOf(type, name));
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail("вкладка " + name + ": " + e);
        }
    }

    /** Подпись вкладки «Корзина» в шапке экрана («Корзина» или «Корзина (N)»). */
    private String trashTabLabel()
    {
        List<?> boxes = field(this.gui(), "tabBoxes");

        if (boxes != null)
        {
            for (Object box : boxes)
            {
                if ("TRASH".equals(String.valueOf(KProbe.recordComponent(box, Enum.class))))
                {
                    return String.valueOf(KProbe.recordComponent(box, String.class));
                }
            }
        }

        return "";
    }

    /** Кнопка подвала экрана с этой подписью (у вкладки «Корзина» — «Очистить корзину (N)», «Открыть папку»). */
    private @Nullable Button footerButton(String label)
    {
        List<Button> buttons = field(this.gui(), "footerButtons");

        if (buttons != null)
        {
            for (Button button : buttons)
            {
                if (button.label().equals(label))
                {
                    return button;
                }
            }
        }

        return null;
    }

    /** Панель вкладки «Корзина»; открыта другая вкладка — {@code null}. */
    private @Nullable Object dialog()
    {
        return this.onTrashTab() ? field(this.gui(), "trash") : null;
    }

    @SuppressWarnings("unchecked")
    private List<SchematicTrash.Item> dialogItems()
    {
        Object dialog = this.dialog();

        try
        {
            return dialog == null ? List.of() : (List<SchematicTrash.Item>) KProbe.call(dialog, "items");
        }
        catch (ReflectiveOperationException e)
        {
            return List.of();
        }
    }

    private String dialogStatus()
    {
        Object dialog = this.dialog();

        try
        {
            return dialog == null ? "" : String.valueOf(KProbe.call(dialog, "statusText"));
        }
        catch (ReflectiveOperationException e)
        {
            return "";
        }
    }

    private String meta(SchematicTrash.Item item)
    {
        Object dialog = this.dialog();

        try
        {
            return dialog == null ? "" : String.valueOf(KProbe.call(dialog, "meta", item, SchematicTrash.now()));
        }
        catch (ReflectiveOperationException e)
        {
            return "";
        }
    }

    private boolean dialogRows(int expected)
    {
        List<SchematicTrash.Item> items = this.dialogItems();
        this.k.check("строк на вкладке «Корзина»", expected, items.size());
        this.k.check("подпись вкладки — «Корзина (" + expected + ")»", f("tab.trash_count", expected), this.trashTabLabel());

        String from = f("trash.from", this.dir.getFileName().toString());
        String fresh = f("trash.days_left", 30);

        for (SchematicTrash.Item item : items)
        {
            String meta = this.meta(item);
            this.k.check("«" + item.name() + "»: откуда — " + from, meta.contains(from));
            this.k.check("«" + item.name() + "»: " + fresh, meta.contains(fresh));
            this.k.check("«" + item.name() + "»: дата удаления", meta.contains(f("trash.deleted", "").strip()));
            this.k.check("«" + item.name() + "»: размер или «папка»",
                    item.directory() ? meta.contains(t("trash.folder")) : item.size() > 0 && ! meta.contains(t("trash.folder")));
        }

        Button empty = this.footerButton(f("trash.empty", expected));
        this.k.check("в подвале «Очистить корзину (" + expected + ")»", empty != null && empty.enabled());
        this.k.check("в подвале «Открыть папку»", this.footerButton(t("trash.open_folder")) != null);
        return true;
    }

    private @Nullable Button rowButton(SchematicTrash.Item item, int which)
    {
        Map<String, Button[]> buttons = field(this.dialog(), "rowButtons");
        Button[] row = buttons == null ? null : buttons.get(item.id());
        return row == null ? null : row[which];
    }

    private boolean clickRow(String name, int which)
    {
        GuiSchematics gui = this.gui();
        SchematicTrash.Item item = this.trashed(name);
        Button button = item == null ? null : this.rowButton(item, which);

        if (gui == null || button == null || button.w() <= 0)
        {
            this.k.fail("вкладка «Корзина»: нет кнопки строки «" + name + "»");
            return false;
        }

        Widget pane = (Widget) this.dialog();
        this.k.check("кнопка строки «" + button.label() + "» внутри вкладки", pane != null && pane.contains(button.x(), button.y())
                && pane.contains(button.x() + button.w() - 1, button.y() + button.h() - 1));
        KProbe.click(gui, button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
        return true;
    }

    private boolean restoreClash()
    {
        SchematicEntry sample = this.k.generated(".litematic");

        try
        {
            // Пока «trash-a» лежит в корзине, на его месте появился новый файл с тем же именем
            if (sample != null)
            {
                Files.copy(sample.path(), this.at(A), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            this.k.fail("занять имя: " + e);
        }

        if (! this.clickRow(A, 0))
        {
            return true;
        }

        Path renamed = this.at("trash-a (2).litematic");
        this.k.check("«Восстановить» в занятое имя → «trash-a (2).litematic»", Files.isRegularFile(renamed));
        this.k.check("новый файл с прежним именем не тронут", Files.isRegularFile(this.at(A)));
        this.k.check("запись ушла из корзины", this.trashed(A) == null);
        this.k.check("строка итога на вкладке: «под именем … (2)»", f("remove.undone_renamed", "trash-a (2).litematic"),
                this.dialogStatus());
        this.k.check("строк на вкладке после восстановления", 3, this.dialogItems().size());
        return true;
    }

    private boolean forever(String name)
    {
        if (! this.clickRow(name, 1))
        {
            return true;
        }

        Modal modal = this.topModal();
        this.k.check("«Удалить навсегда» спрашивает", modal != null);
        this.k.check("заголовок «Удалить навсегда?»", t("trash.forever.title"), modalTitle(modal));
        this.k.check("окно называет файл", modalText(modal).contains("«" + name + "»"));
        return true;
    }

    private boolean foreverConfirm(String name)
    {
        SchematicTrash.Item item = this.trashed(name);
        Path holder = item == null ? null : SchematicTrash.get().root().resolve(item.id());

        if (! this.clickModal(t("trash.forever")))
        {
            return true;
        }

        this.k.check("«Удалить навсегда»: записи нет", this.trashed(name) == null);
        this.k.check("«Удалить навсегда»: папка удаления стёрта", holder != null && ! Files.exists(holder));
        this.k.check("подтверждение закрылось, вкладка «Корзина» на месте", this.topModal() == null && this.onTrashTab());
        this.k.check("строка итога: удалено навсегда", f("trash.deleted_forever", name), this.dialogStatus());
        this.k.check("строк на вкладке", 2, this.dialogItems().size());
        return true;
    }

    private @Nullable String purgedId;
    private @Nullable String agedId;

    private boolean backdate()
    {
        SchematicTrash.Item old = this.trashed(C);
        SchematicTrash.Item aged = this.trashed(EMPTY);

        if (old == null || aged == null)
        {
            this.k.fail("состарить: нет записей " + C + " / " + EMPTY);
            return true;
        }

        this.purgedId = old.id();
        this.agedId = aged.id();
        this.k.check("состарить " + C + " на 31 день", SchematicTrash.TestHooks.backdate(old.id(), 31.0));
        this.k.check("состарить " + EMPTY + " на 25 дней", SchematicTrash.TestHooks.backdate(aged.id(), 25.0));

        // Дата удаления — из индекса на диске, а не из памяти: перечитываем
        SchematicTrash.TestHooks.reload();
        SchematicTrash.Item again = this.trashed(EMPTY);
        this.k.check("дата удаления пережила перечитывание индекса", again != null && again.daysLeft(SchematicTrash.now()) == 5);
        return true;
    }

    private DumpSchematics.Action reopen()
    {
        boolean[] opened = {false};

        return mc ->
        {
            if (! opened[0])
            {
                opened[0] = true;
                Nav.clear();
                GuiBase.openGui(null);
                GuiSchematics.open(null);
                return false;
            }

            GuiSchematics gui = this.gui();

            if (gui == null)
            {
                return false;
            }

            Path purged = this.purgedId == null ? null : SchematicTrash.get().root().resolve(this.purgedId);
            this.k.check("при открытии экрана 31-дневное удалено", this.trashed(C) == null);
            this.k.check("его папка стёрта с диска", purged != null && ! Files.exists(purged));
            this.k.check("25-дневное осталось", this.trashed(EMPTY) != null);
            // Экран открылся на «Корзине» (вкладка запоминается) — итог чистки строкой на ней, а не тостом поверх строк
            String note = f("trash.purged", 30, 1);
            this.k.check("сообщение о чистке — " + (this.onTrashTab() ? "строкой на вкладке" : "тостом"),
                    this.onTrashTab() ? note.equals(this.dialogStatus()) && ! this.toastHas(note) : this.toastHas(note));

            Object files = this.k.files();

            try
            {
                if (files != null)
                {
                    KProbe.call(files, "open", SchematicLibrary.get().root());
                }
            }
            catch (ReflectiveOperationException e)
            {
                this.k.fail("корень: " + e);
            }

            return true;
        };
    }

    private boolean afterPurge()
    {
        // Вторая запись для «Очистить» — сам файл, занявший имя «trash-a»
        try
        {
            SchematicTrash.get().trash(this.at(A));
        }
        catch (IOException e)
        {
            this.k.fail("в корзину " + A + ": " + e);
        }

        Object files = this.k.files();

        try
        {
            if (files != null)
            {
                KProbe.call(files, "refresh");
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("refresh: " + e);
        }

        this.openTrashByKeys();

        SchematicTrash.Item aged = this.trashed(EMPTY);
        this.k.check("на вкладке «осталось 5 дн.»", aged != null && this.meta(aged).contains(f("trash.days_left", 5)));
        this.k.check("строк на вкладке после чистки", 2, this.dialogItems().size());
        return true;
    }

    private boolean emptyAsk()
    {
        GuiSchematics gui = this.gui();
        Button empty = this.footerButton(f("trash.empty", 2));

        if (gui == null || empty == null)
        {
            this.k.fail("нет кнопки «Очистить корзину (2)»");
            return true;
        }

        this.k.clickButton(gui, empty, null);
        Modal modal = this.topModal();
        this.k.check("«Очистить корзину» спрашивает", t("trash.empty.title"), modalTitle(modal));
        this.k.check("окно называет число", modalText(modal).contains(f("trash.empty.text", 2)));
        return true;
    }

    private boolean emptyConfirm()
    {
        if (! this.clickModal(t("trash.empty.yes")))
        {
            return true;
        }

        this.k.check("корзина пуста", 0, SchematicTrash.get().count());
        this.k.check("строка итога: очищено", f("trash.emptied", 2), this.dialogStatus());
        this.k.check("на вкладке пусто", 0, this.dialogItems().size());

        try (Stream<Path> left = Files.list(SchematicTrash.get().root()))
        {
            this.k.check("на диске корзины — только индекс", List.of("index.json"),
                    left.map(p -> p.getFileName().toString()).sorted().toList());
        }
        catch (IOException e)
        {
            this.k.fail("корзина на диске: " + e);
        }

        Button empty = this.footerButton(f("trash.empty", 0));
        this.k.check("«Очистить корзину (0)» выключена", empty != null && ! empty.enabled());
        return true;
    }

    private boolean closeDialog()
    {
        // Шапка перерисовалась после очистки — число у вкладки ушло
        this.k.check("подпись вкладки без числа — «Корзина»", t("tab.trash"), this.trashTabLabel());
        this.showTab("LIBRARY");
        this.k.check("снова «Библиотека»", this.dialog() == null && ! this.onTrashTab());

        ScrollList<?> list = this.fileList();
        Object last = list == null || list.items().isEmpty() ? null : list.items().getLast();
        this.k.check("в корне больше нет строки «Корзина»", ! "TRASH".equals(String.valueOf(KProbe.recordComponent(last, Enum.class))));
        return true;
    }

    private boolean tidy()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.placed = null;

        if (this.placedSchematic != null)
        {
            SchematicHolder.getInstance().removeSchematic(this.placedSchematic);
            this.placedSchematic = null;
        }

        for (Path folder : this.dirs)
        {
            try (Stream<Path> walk = Files.walk(folder))
            {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.deleteIfExists(path);
                }
            }
            catch (IOException | RuntimeException e)
            {
                // Уже убрано
            }
        }

        this.dirs.clear();
        return true;
    }
}
