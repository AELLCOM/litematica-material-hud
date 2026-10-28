package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Переименование файла схемы в «Библиотеке» (меню ПКМ строки). Расширение остаётся прежним; имя, которое
 * Windows не примет, или занятое — окно не закрывается, причина тостом. Вместе с файлом на новое имя переходит
 * всё, что мод и Litematica помнят по пути: размещения и загруженная схема ({@link PlacementOps#repoint}), замены
 * и отключения «Материалов», сетка зон Zone Focus, миниатюра. Имя схемы внутри файла и имена размещений не
 * меняются. После — тост с «Отменить»: файл и всё перечисленное возвращаются к старому имени.
 */
@Environment(EnvType.CLIENT)
final class RenameFlow
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final int WIDTH = 300;
    private static final String ILLEGAL = "<>:\"/\\|?*";
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private RenameFlow() {}

    static void open(GuiSchematics screen, SchematicEntry entry)
    {
        if (entry.directory())
        {
            return;
        }

        String ext = extension(entry);
        TextInput name = new TextInput().maxLength(128).hint(Tr.t("rename.hint"));
        name.setText(entry.name());

        Modal modal = new Modal(screen.layers, Tr.t("rename.title"), Tr.t("cancel"));
        Runnable apply = () -> attempt(screen, modal, entry, ext, name.text());
        name.onSubmit(value -> apply.run());

        modal.width(WIDTH).text(ext.isEmpty() ? Tr.t("rename.text_plain") : Tr.f("rename.text", ext));
        int placed = placements(entry.path());

        if (placed > 0)
        {
            modal.text(Tr.f("rename.placed", placed));
        }

        Button ok = new Button(Tr.t("rename.ok"), Button.Style.PRIMARY, apply).accent(Theme.GREEN);
        modal.row(name, Ui.CTRL_H)
                .button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel))
                .button(ok)
                .defaultButton(ok)
                .open();
        name.setFocused(true);
        name.selectAll();
    }

    /** Расширение формата с точкой, как у файла (регистр — файла); формат не узнан — пусто. */
    private static String extension(SchematicEntry entry)
    {
        String file = entry.fileName();
        return file.length() > entry.name().length() ? file.substring(entry.name().length()) : "";
    }

    private static void attempt(GuiSchematics screen, Modal modal, SchematicEntry entry, String ext, String typed)
    {
        String base = typed.strip();

        // Расширение дописали сами — второй раз не добавлять
        if (! ext.isEmpty() && base.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT)))
        {
            base = base.substring(0, base.length() - ext.length()).strip();
        }

        String problem = problem(base);

        if (problem != null)
        {
            screen.toast(problem, Ui.C_WARN);
            return;
        }

        Path from = entry.path().toAbsolutePath().normalize();
        Path to = from.resolveSibling(base + ext);

        if (to.getFileName().toString().equals(entry.fileName()))
        {
            modal.dismiss();
            return;
        }

        if (! Files.isRegularFile(from))
        {
            modal.dismiss();
            screen.toast(Tr.f("rename.gone", entry.fileName()), Ui.C_DANGER);
            screen.fileGone(from);
            return;
        }

        if (Files.exists(to) && ! sameFile(from, to))
        {
            screen.toast(Tr.f("rename.taken", to.getFileName()), Ui.C_WARN);
            return;
        }

        modal.dismiss();
        run(screen, from, to, true);
    }

    /** Почему имя не годится в имя файла (Windows строже всех); годится — {@code null}. */
    private static @Nullable String problem(String base)
    {
        if (base.isEmpty())
        {
            return Tr.t("rename.empty");
        }

        for (int i = 0; i < base.length(); ++i)
        {
            char c = base.charAt(i);

            if (c < 32 || ILLEGAL.indexOf(c) >= 0)
            {
                return Tr.f("rename.illegal", "\\ / : * ? \" < > |");
            }
        }

        // Точка в начале — библиотека такие файлы прячет; в конце и имена устройств — Windows не примет
        if (base.startsWith(".") || base.endsWith(".") || RESERVED.contains(base.toUpperCase(Locale.ROOT)))
        {
            return Tr.f("rename.reserved", base);
        }

        return null;
    }

    /**
     * Переименовать и перевести на новое имя всё, что помнит путь. {@code undoable} — тост с «Отменить» (у самой
     * отмены его нет).
     */
    private static void run(GuiSchematics screen, Path from, Path to, boolean undoable)
    {
        // Сетку зон узнать до переезда: Zone Focus ищет её по старому пути
        int[] grid = ZoneFocusFiles.grid(from);

        try
        {
            move(from, to);
        }
        catch (IOException | RuntimeException e)
        {
            screen.toast(Tr.f("rename.failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        int placements = 0;

        try
        {
            // Порядок важен: сетка под новым путём — пока ни одно размещение на него не смотрит (иначе Zone Focus
            // разбил бы участок заново), старая запись — когда уже никто не смотрит на старый (иначе снял бы участок)
            if (grid != null)
            {
                ZoneFocusFiles.split(to, grid[0], grid[1], grid[2]);
            }

            placements = PlacementOps.repoint(from, to);

            if (grid != null && PlacementOps.of(from).isEmpty())
            {
                ZoneFocusFiles.clear(from);
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Файл уже под новым именем — размещения дойдут до него при следующем входе только через «Загрузить»
            LOGGER.warn("Could not move placements of {} to {}", from, to, e);
            screen.toast(Tr.f("rename.placements_failed", Tr.reason(e)), Ui.C_WARN);
        }

        try
        {
            BlockReplacements.moved(from, to);
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Could not move block replacements of {} to {}", from, to, e);
        }

        screen.fileRenamed(from, to);

        String oldName = String.valueOf(from.getFileName());
        String newName = String.valueOf(to.getFileName());

        if (! undoable)
        {
            screen.toast(Tr.f("rename.undone", newName), Theme.GREEN);
            return;
        }

        String text = placements > 0 ? Tr.f("rename.done_placements", oldName, newName, placements)
                : Tr.f("rename.done", oldName, newName);
        screen.toast(text, Theme.GREEN, Toasts.Action.once(Tr.t("remove.undo"), () -> undo(screen, from, to)));
    }

    /** «Отменить» из тоста: вернуть старое имя, если оно ещё свободно и файл ещё под новым. */
    private static void undo(GuiSchematics screen, Path from, Path to)
    {
        if (! Files.isRegularFile(to))
        {
            screen.toast(Tr.f("rename.gone", to.getFileName()), Ui.C_DANGER);
            return;
        }

        if (Files.exists(from) && ! sameFile(from, to))
        {
            screen.toast(Tr.f("rename.undo_taken", from.getFileName()), Ui.C_WARN);
            return;
        }

        run(screen, to, from, false);
    }

    /**
     * Сам переезд. Только регистр букв (на Windows это тот же файл) — через временное имя: иначе система сочтёт,
     * что переименовывать нечего. Занятое имя — исключение, чужой файл не затирается.
     */
    private static void move(Path from, Path to) throws IOException
    {
        if (sameFile(from, to))
        {
            Path temp = from.resolveSibling(from.getFileName() + ".rename-" + Util.getMillis() + ".tmp");
            Files.move(from, temp);

            try
            {
                Files.move(temp, to);
            }
            catch (IOException | RuntimeException e)
            {
                Files.move(temp, from);
                throw e;
            }

            return;
        }

        Files.move(from, to);
    }

    private static boolean sameFile(Path a, Path b)
    {
        try
        {
            return Files.exists(b) && Files.isSameFile(a, b);
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static int placements(Path file)
    {
        try
        {
            return PlacementOps.of(file).size();
        }
        catch (RuntimeException e)
        {
            // Без мира — размещений нет
            return 0;
        }
    }
}
