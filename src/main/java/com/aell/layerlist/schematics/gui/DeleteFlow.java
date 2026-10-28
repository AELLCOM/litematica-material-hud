package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Toasts;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Удаление в «Библиотеке» (0.0.27): файл схемы или пустая папка уходит в корзину Material HUD
 * ({@link SchematicTrash}), а не стирается. Всегда с подтверждением, где названо, что удаляется. Если
 * схема стоит размещением Litematica — окно говорит, что будет (размещения этой сессии остаются, но
 * Litematica при смене измерения или следующем входе их не загрузит: её {@code SchematicHolder.getOrLoad}
 * отказывает, если файла нет), и даёт второй выбор — «Удалить и убрать размещения». После удаления —
 * тост с «Отменить»: файл возвращается на место, убранные размещения — тоже.
 */
@Environment(EnvType.CLIENT)
final class DeleteFlow
{
    private static final int WIDTH = 300;
    private static final int WIDTH_PLACED = 380;

    private DeleteFlow() {}

    /** Подтверждение для строки списка: файл схемы или папка (непустую не удаляем — тост с причиной). */
    static void confirm(GuiSchematics screen, SchematicEntry entry)
    {
        Path path = entry.path();
        String name = entry.fileName();

        if (entry.directory() && ! emptyFolder(path))
        {
            screen.toast(Tr.f("remove.not_empty", name), Ui.C_WARN);
            return;
        }

        List<SchematicPlacement> placed = entry.directory() ? List.of() : placements(path);
        boolean folder = entry.directory();

        Modal modal = new Modal(screen.layers, Tr.t(folder ? "remove.title_folder" : "remove.title"), Tr.t("cancel"));
        modal.text(Tr.f(folder ? "remove.text_folder" : "remove.text", name, SchematicTrash.KEEP_DAYS));

        if (! placed.isEmpty())
        {
            modal.text(Tr.f("remove.placed", placed.size()));
        }

        modal.button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel));

        if (! placed.isEmpty())
        {
            modal.button(new Button(Tr.t("remove.with_placements"), Button.Style.DANGER, () ->
            {
                modal.dismiss();
                run(screen, entry, true);
            }).tooltip(Tr.f("remove.with_placements.tip", placed.size())));
        }

        Button yes = new Button(Tr.t("remove.yes"), Button.Style.DANGER, () ->
        {
            modal.dismiss();
            run(screen, entry, false);
        });

        if (! placed.isEmpty())
        {
            yes.tooltip(Tr.t("remove.yes.keep_tip"));
        }

        modal.button(yes);
        modal.defaultButton(yes);
        modal.width(placed.isEmpty() ? WIDTH : WIDTH_PLACED).open();
    }

    static boolean emptyFolder(Path path)
    {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path))
        {
            return ! stream.iterator().hasNext();
        }
        catch (IOException | RuntimeException e)
        {
            return false;
        }
    }

    private static List<SchematicPlacement> placements(Path path)
    {
        try
        {
            return PlacementOps.of(path);
        }
        catch (RuntimeException e)
        {
            // Без мира или с изменившейся Litematica — удаляем без предупреждения о размещениях
            return List.of();
        }
    }

    private static void run(GuiSchematics screen, SchematicEntry entry, boolean withPlacements)
    {
        Path path = entry.path().toAbsolutePath().normalize();
        PlacementOps.Detached detached = null;

        try
        {
            detached = withPlacements ? PlacementOps.detach(path) : null;
        }
        catch (RuntimeException e)
        {
            screen.toast(Tr.f("remove.failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        SchematicTrash.Item item;

        try
        {
            item = SchematicTrash.get().trash(path);
        }
        catch (IOException | RuntimeException e)
        {
            // Файл остался на месте — вернуть и размещения, иначе удаление «наполовину»
            reattachQuietly(detached);
            screen.toast(Tr.f("remove.failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        screen.fileGone(path);

        PlacementOps.Detached undoPlacements = detached;
        String text = entry.directory() ? Tr.f("remove.done_folder", item.name())
                : detached != null && ! detached.placements().isEmpty()
                        ? Tr.f("remove.done_placements", item.name(), detached.placements().size())
                        : Tr.f("remove.done", item.name());

        screen.toast(text, Ui.C_WARN, Toasts.Action.once(Tr.t("remove.undo"), () -> undo(screen, item, undoPlacements)));
    }

    /** «Отменить» из тоста: файл — на место (имя занято — «Имя (2)»), размещения — если файл лёг туда же. */
    static void undo(GuiSchematics screen, SchematicTrash.Item item, PlacementOps.@Nullable Detached detached)
    {
        Path back;

        try
        {
            back = SchematicTrash.get().restore(item, SchematicLibrary.get().root());
        }
        catch (IOException | RuntimeException e)
        {
            screen.toast(Tr.f("remove.undo_failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        boolean samePlace = back.equals(item.original());

        if (samePlace)
        {
            reattachQuietly(detached);
        }

        screen.fileBack(back);

        String shown = back.getFileName() == null ? item.name() : back.getFileName().toString();
        screen.toast(samePlace ? Tr.f("remove.undone", shown) : Tr.f("remove.undone_renamed", shown), Theme.GREEN);

        if (! samePlace && detached != null && ! detached.placements().isEmpty())
        {
            screen.toast(Tr.t("remove.undone_no_placements"), Ui.C_WARN);
        }
    }

    private static void reattachQuietly(PlacementOps.@Nullable Detached detached)
    {
        if (detached == null || detached.isEmpty())
        {
            return;
        }

        try
        {
            PlacementOps.reattach(detached);
        }
        catch (RuntimeException e)
        {
            // Вернуть размещения не вышло (мир сменился) — файл важнее, он уже на месте
        }
    }
}
