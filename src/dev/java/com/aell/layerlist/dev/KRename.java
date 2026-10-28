package com.aell.layerlist.dev;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», переименование в «Библиотеке» (25.10.2026). Ввод — настоящий: меню ПКМ, Enter, Ctrl+A и Esc через
 * {@code KeyboardHandler.keyPress}, имя — событиями текста SDL ({@code textInput}), кнопки окна и тоста — кликом.
 * <ul>
 *   <li>меню ПКМ файла → «Переименовать…» (перед «Удалить…») → окно с именем без расширения, выделенным целиком;
 *       новое имя кириллицей + Enter: файл
 *       переименован, выбран под новым именем, тост с «Отменить» → старое имя вернулось;</li>
 *   <li>недопустимое имя и занятое — окно остаётся, причина тостом, файлы не тронуты;</li>
 *   <li>размещённая схема с заменой блоков и сеткой зон Zone Focus: в окне — строка о размещениях; размещение и схема Litematica
 *       указывают на новый файл (и в JSON размещения, и для {@code getOrLoad}), замены и сетка — под новым
 *       именем, следующая правка замен ложится уже туда; «Отменить» возвращает всё к старому имени.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class KRename
{
    private static final int SCANCODE_MASK = 1 << 30;
    private static final String A = "rename-a.litematic";
    private static final String B = "rename-b.litematic";
    private static final String TAKEN = "taken.litematic";
    private static final String NEW_A = "Замок рыцаря";
    private static final String NEW_B = "Башня";

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();
    private Path dir = Path.of(".");
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable SchematicPlacement placed;
    private @Nullable LitematicaSchematic placedSchematic;

    KRename(DumpSchematics k)
    {
        this.k = k;
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "переименование: папка и схемы", 5_000L, mc -> this.prepare(p)));
        s.add(step(p + "переименование: открыть папку теста", 5_000L, mc -> this.openDir()));
        s.add(step(p + "выбрать " + A, 5_000L, mc -> this.select(A)));
        s.add(step(p + "меню ПКМ «Переименовать…» → окно", 3_000L, this.k.settleThen(200L, mc -> this.openRename(A, false))));
        s.add(step(p + "снимок: окно переименования", 5_000L, KTrash.cleanShot(this.k, p + "rename-dialog")));
        s.add(step(p + "имя кириллицей + Enter → переименован", 3_000L, mc -> this.renameA()));
        s.add(step(p + "снимок: тост с «Отменить»", 5_000L, KTrash.cleanShot(this.k, p + "rename-toast")));
        s.add(step(p + "«Отменить» → старое имя", 3_000L, mc -> this.undoA()));
        s.add(step(p + "окно снова на " + A, 3_000L, this.k.settleThen(200L, mc -> this.select(A) && this.openRename(A, false))));
        s.add(step(p + "недопустимое имя → окно остаётся", 3_000L, mc -> this.illegal()));
        s.add(step(p + "снимок: недопустимое имя", 5_000L, KTrash.cleanShot(this.k, p + "rename-illegal")));
        s.add(step(p + "занятое имя → окно остаётся", 3_000L, this.k.settleThen(200L, mc -> this.taken())));
        s.add(step(p + "Esc, Esc → окно закрыто, файлы те же", 3_000L, mc -> this.escape()));
        s.add(step(p + "размещение " + B + ", замена, зоны", 15_000L, mc -> this.place(B)));
        s.add(step(p + "меню ПКМ «Переименовать…» размещённой", 3_000L, this.k.settleThen(300L, mc -> this.openRename(B, true))));
        s.add(step(p + "снимок: окно размещённой схемы", 5_000L, KTrash.cleanShot(this.k, p + "rename-placed-dialog")));
        s.add(step(p + "новое имя + «Переименовать» → всё переехало", 3_000L, mc -> this.renameB()));
        s.add(step(p + "снимок: размещённая переименована", 5_000L, KTrash.cleanShot(this.k, p + "rename-placed-done")));
        s.add(step(p + "«Отменить» → всё вернулось к " + B, 3_000L, mc -> this.undoB()));
        s.add(step(p + "правка замен после отмены — под старым именем", 3_000L, mc -> this.editAfter()));
        s.add(step(p + "переименование: убрать за собой", 5_000L, mc -> this.tidy()));
        return s;
    }

    void cleanup()
    {
        this.tidy();
    }

    // ===== Подготовка =====

    private boolean prepare(String p)
    {
        if (this.k.gui() == null)
        {
            GuiSchematics.open(null);
            return false;
        }

        this.k.setTab("LIBRARY");
        SchematicEntry sample = this.k.generated(".litematic");

        if (sample == null)
        {
            this.k.fail("переименование: нет k-generated.litematic");
            return true;
        }

        try
        {
            this.dir = SchematicLibrary.get().root().resolve(p.isEmpty() ? "_k_rename" : "_k_rename_s3").toAbsolutePath().normalize();
            KData.recreate(this.dir);
            this.dirs.add(this.dir);

            for (String name : List.of(A, B, TAKEN))
            {
                Files.copy(sample.path(), this.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            this.k.fail("переименование: подготовка — " + e);
        }

        return true;
    }

    // ===== Помощники =====

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

    private static String t(String suffix)
    {
        return Strings.plain("layerlist.schematics." + suffix);
    }

    private static String f(String suffix, Object... args)
    {
        return I18n.get("layerlist.schematics." + suffix, args);
    }

    private boolean openDir()
    {
        Object files = this.k.files();

        try
        {
            KProbe.call(files, "open", this.dir);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail("переименование: open(Path) — " + e);
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
            this.k.fail("переименование: нет записи " + name);
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

    private static long handle()
    {
        return Minecraft.getInstance().getWindow().handle();
    }

    /** Клавиша через вход событий SDL, как настоящая; {@code mods} — модификаторы (Ctrl для Ctrl+A). */
    private static void realKey(int scancode, int mods)
    {
        int keycode = switch (scancode)
        {
            case InputConstants.KEY_RETURN -> '\r';
            case InputConstants.KEY_ESCAPE -> 0x1B;
            default -> scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z
                    ? 'a' + scancode - InputConstants.KEY_A : scancode | SCANCODE_MASK;
        };
        KeyEvent event = new KeyEvent(scancode, keycode, mods);
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.keyPress(handle(), InputConstants.PRESS, event);
        mc.keyboardHandler.keyPress(handle(), InputConstants.RELEASE, event);
    }

    /** Текст — событием SDL {@code TEXT_INPUT}, как его даёт раскладка (кириллица в том числе). */
    private static void type(String text)
    {
        Minecraft.getInstance().keyboardHandler.textInput(handle(), text);
    }

    private @Nullable Modal topModal()
    {
        Layers layers = this.layers();
        return layers != null && layers.top() instanceof Modal modal ? modal : null;
    }

    /** Поле имени в окне — строка-виджет {@link TextInput}. */
    private @Nullable TextInput nameField(@Nullable Modal modal)
    {
        List<?> rows = KTrash.field(modal, "rows");

        if (rows != null)
        {
            for (Object row : rows)
            {
                if (KProbe.recordComponent(row, com.aell.layerlist.ui.kit.Widget.class) instanceof TextInput input)
                {
                    return input;
                }
            }
        }

        return null;
    }

    private boolean clickModal(String label)
    {
        GuiSchematics gui = this.gui();
        var button = KTrash.modalButton(this.topModal(), label);

        if (gui == null || button == null)
        {
            this.k.fail("переименование: в окне нет кнопки «" + label + "»");
            return false;
        }

        this.k.clickButton(gui, button, null);
        return true;
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
            this.k.fail("переименование: у тоста нет кнопки «" + label + "»");
            return false;
        }

        KProbe.click(gui, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    private boolean isSelected(Path path)
    {
        SchematicEntry now = this.selected();
        return now != null && now.path().toAbsolutePath().normalize().equals(path);
    }

    // ===== Простое переименование =====

    private boolean renameA()
    {
        // Имя выделено целиком — набор заменяет его
        type(NEW_A);
        TextInput field = this.nameField(this.topModal());
        this.k.check("набранное заменило выделенное имя", NEW_A, field == null ? null : field.text());
        realKey(InputConstants.KEY_RETURN, 0);

        Path renamed = this.at(NEW_A + ".litematic");
        this.k.check("Enter: окно закрылось", this.topModal() == null);
        this.k.check("старого файла нет", ! Files.exists(this.at(A)));
        this.k.check("файл под новым именем (кириллица, с расширением)", Files.isRegularFile(renamed));
        this.k.check("строка с новым именем в списке", this.indexOf(renamed) >= 0);
        this.k.check("строки со старым именем нет", -1, this.indexOf(this.at(A)));
        this.k.check("выбран файл под новым именем", this.isSelected(renamed));
        this.k.check("тост «старое → новое»", this.toastHas(f("rename.done", A, NEW_A + ".litematic")));
        return true;
    }

    private boolean undoA()
    {
        if (! this.clickToast(t("remove.undo")))
        {
            return true;
        }

        this.k.check("«Отменить»: " + A + " снова на месте", Files.isRegularFile(this.at(A)));
        this.k.check("«Отменить»: нового имени нет", ! Files.exists(this.at(NEW_A + ".litematic")));
        this.k.check("«Отменить»: выбран файл со старым именем", this.isSelected(this.at(A)));
        this.k.check("тост «Имя возвращено»", this.toastHas(f("rename.undone", A)));
        this.k.check("тост с «Отменить» закрылся", ! this.toastHas(f("rename.done", A, NEW_A + ".litematic")));
        return true;
    }

    private boolean illegal()
    {
        realKey(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        type("плохое:имя?");
        realKey(InputConstants.KEY_RETURN, 0);
        this.k.check("недопустимое имя: окно осталось", this.topModal() != null);
        this.k.check("недопустимое имя: причина тостом", this.toastHas(f("rename.illegal", "\\ / : * ? \" < > |")));
        this.k.check("недопустимое имя: " + A + " на месте", Files.isRegularFile(this.at(A)));
        return true;
    }

    private boolean taken()
    {
        realKey(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        type(TAKEN.replace(".litematic", ""));
        realKey(InputConstants.KEY_RETURN, 0);
        this.k.check("занятое имя: окно осталось", this.topModal() != null);
        this.k.check("занятое имя: причина тостом", this.toastHas(f("rename.taken", TAKEN)));
        this.k.check("занятое имя: " + A + " на месте", Files.isRegularFile(this.at(A)));
        this.k.check("занятое имя: " + TAKEN + " не затёрт", Files.isRegularFile(this.at(TAKEN)));
        return true;
    }

    private boolean escape()
    {
        // Первый Esc — поле теряет фокус (так у всех окон ui.kit), второй — окно закрывается
        realKey(InputConstants.KEY_ESCAPE, 0);
        Modal modal = this.topModal();
        TextInput field = this.nameField(modal);
        this.k.check("первый Esc: окно осталось, поле без фокуса", modal != null && field != null && ! field.focused());
        realKey(InputConstants.KEY_ESCAPE, 0);
        this.k.check("второй Esc: окно закрылось", this.topModal() == null);
        this.k.check("Esc: экран «Схемы» открыт", this.gui() != null);
        this.k.check("Esc: " + A + " на месте", Files.isRegularFile(this.at(A)));
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

        SchematicPlacement p = PlacementOps.placeAt(done.join(), DumpSchematics.STAND.offset(4, 0, 30));
        this.made.add(p);
        this.placed = p;
        this.placedSchematic = p.getSchematic();
        this.k.check("размещений " + name, 1, PlacementOps.of(this.at(name)).size());

        BlockReplacements.Outcome off = BlockReplacements.disable(this.placedSchematic, Items.OAK_PLANKS);
        this.k.check("замена (отключение досок) записана под " + name + " (" + off + ")",
                BlockReplacements.TestHooks.stored(this.at(name)) != null);

        if (this.k.check("Zone Focus в dev-клиенте", ZoneFocusFiles.available()))
        {
            ZoneFocusFiles.split(this.at(name), 2, 1, 2);
            this.k.check("сетка зон файла " + name, "[2, 1, 2]", Arrays.toString(ZoneFocusFiles.grid(this.at(name))));
        }

        this.select(name);
        return true;
    }

    /** ПКМ по строке → «Переименовать…» с клавиатуры меню (↑ ↑ Enter) → окно: заголовок, расширение, поле. */
    private boolean openRename(String name, boolean placedWarning)
    {
        GuiSchematics gui = this.gui();
        ScrollList<?> list = this.fileList();
        int index = this.indexOf(this.at(name));

        if (gui == null || list == null || index < 0)
        {
            this.k.fail("ПКМ: нет строки " + name);
            return true;
        }

        list.ensureVisible(index);
        double x = list.x() + list.w() / 3.0;
        boolean clicked = false;

        for (int y = list.y(); y < list.y() + list.h() && ! clicked; ++y)
        {
            if (list.indexAt(x, y + 0.5) == index)
            {
                MouseButtonEvent click = new MouseButtonEvent(x, y + 4.5, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
                gui.onMouseClicked(click, false);
                gui.onMouseReleased(click);
                clicked = true;
            }
        }

        Layers layers = this.layers();
        ContextMenu menu = layers != null && layers.top() instanceof ContextMenu m ? m : null;
        List<ContextMenu.Item> items = KTrash.field(menu, "items");

        if (! this.k.check("ПКМ по " + name + " открыл меню", items != null && items.size() >= 2))
        {
            return true;
        }

        ContextMenu.Item rename = items.get(items.size() - 2);
        this.k.check("предпоследний пункт — «Переименовать…» (перед «Удалить…»)", t("menu.rename"), rename.label());
        this.k.check("«Переименовать…» включён", rename.enabled());
        this.k.check("последний — «Удалить…»", t("menu.delete"), items.getLast().label());

        // ↑ из ничего — на «Удалить…», ещё ↑ — на «Переименовать…», Enter — выбрать
        KProbe.press(gui, InputConstants.KEY_UP);
        KProbe.press(gui, InputConstants.KEY_UP);
        KProbe.press(gui, InputConstants.KEY_RETURN);

        Modal modal = this.topModal();

        if (! this.k.check("«Переименовать…» открыл окно (" + name + ")", modal != null))
        {
            return true;
        }

        this.k.check("заголовок окна", t("rename.title"), KTrash.modalTitle(modal));
        this.k.check("окно говорит, что расширение останется", KTrash.modalText(modal).contains(".litematic"));
        this.k.check("строка о размещениях — только у размещённой (" + name + ")", placedWarning,
                KTrash.modalText(modal).contains(f("rename.placed", 1)));
        TextInput field = this.nameField(modal);

        if (this.k.check("в окне есть поле имени", field != null))
        {
            this.k.check("в поле — имя без расширения", name.replace(".litematic", ""), field.text());
            this.k.check("поле в фокусе", field.focused());
        }

        return true;
    }

    private boolean renameB()
    {
        type(NEW_B);

        if (! this.clickModal(t("rename.ok")))
        {
            return true;
        }

        Path old = this.at(B);
        Path now = this.at(NEW_B + ".litematic");
        SchematicPlacement p = this.placed;
        LitematicaSchematic s = this.placedSchematic;

        this.k.check("файл под новым именем", Files.isRegularFile(now) && ! Files.exists(old));
        this.k.check("тост называет размещения", this.toastHas(f("rename.done_placements", B, NEW_B + ".litematic", 1)));

        if (p != null && s != null)
        {
            this.k.check("размещение то же и на месте", PlacementOps.all().contains(p));
            this.k.check("размещение смотрит на новый файл", now, p.getSchematicFile().toAbsolutePath().normalize());
            this.k.check("схема Litematica смотрит на новый файл", now, s.getFile().toAbsolutePath().normalize());
            this.k.check("размещений нового файла", 1, PlacementOps.of(now).size());
            this.k.check("размещений старого файла", 0, PlacementOps.of(old).size());
            this.k.check("JSON размещения (запись в мир) — с новым путём",
                    p.toJson().toString().contains(NEW_B + ".litematic"));
            this.k.check("getOrLoad(новый путь) — та же схема, без перечтения",
                    SchematicHolder.getInstance().getOrLoad(now) == s);
            this.k.check("доски всё ещё отключены у схемы",
                    BlockReplacements.disabledBlocks(s).contains(net.minecraft.world.level.block.Blocks.OAK_PLANKS));
        }

        this.k.check("замены под новым именем", BlockReplacements.TestHooks.stored(now) != null);
        this.k.check("замен под старым именем нет", BlockReplacements.TestHooks.stored(old) == null);

        if (ZoneFocusFiles.available())
        {
            this.k.check("сетка зон под новым именем", "[2, 1, 2]", Arrays.toString(ZoneFocusFiles.grid(now)));
            this.k.check("сетки под старым именем нет", ZoneFocusFiles.grid(old) == null);
        }

        this.k.check("выбран файл под новым именем", this.isSelected(now));
        return true;
    }

    private boolean undoB()
    {
        if (! this.clickToast(t("remove.undo")))
        {
            return true;
        }

        Path old = this.at(B);
        Path now = this.at(NEW_B + ".litematic");
        SchematicPlacement p = this.placed;
        LitematicaSchematic s = this.placedSchematic;

        this.k.check("«Отменить»: файл снова " + B, Files.isRegularFile(old) && ! Files.exists(now));

        if (p != null && s != null)
        {
            this.k.check("«Отменить»: размещение снова на " + B, old, p.getSchematicFile().toAbsolutePath().normalize());
            this.k.check("«Отменить»: схема снова на " + B, old, s.getFile().toAbsolutePath().normalize());
        }

        this.k.check("«Отменить»: замены снова под " + B, BlockReplacements.TestHooks.stored(old) != null);
        this.k.check("«Отменить»: под новым именем замен нет", BlockReplacements.TestHooks.stored(now) == null);

        if (ZoneFocusFiles.available())
        {
            this.k.check("«Отменить»: сетка снова под " + B, "[2, 1, 2]", Arrays.toString(ZoneFocusFiles.grid(old)));
            this.k.check("«Отменить»: под новым именем сетки нет", ZoneFocusFiles.grid(now) == null);
        }

        return true;
    }

    /** Схема уже дважды сменила путь: правка замен должна лечь под её нынешнее имя, а не под какое-то из прежних. */
    private boolean editAfter()
    {
        if (this.placedSchematic == null)
        {
            return true;
        }

        BlockReplacements.revertAll(this.placedSchematic);
        this.k.check("откат замен снял запись " + B, BlockReplacements.TestHooks.stored(this.at(B)) == null);
        this.k.check("и под новым именем записи нет", BlockReplacements.TestHooks.stored(this.at(NEW_B + ".litematic")) == null);
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
            BlockReplacements.revertAll(this.placedSchematic);
            SchematicHolder.getInstance().removeSchematic(this.placedSchematic);
            this.placedSchematic = null;
        }

        for (Path folder : this.dirs)
        {
            ZoneFocusFiles.clear(folder.resolve(B));
            ZoneFocusFiles.clear(folder.resolve(NEW_B + ".litematic"));

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
