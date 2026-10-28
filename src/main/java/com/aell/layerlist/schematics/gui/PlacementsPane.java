package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.compat.Syncmatica;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Подвкладка «Размещения»: все размещения Litematica в этом мире — таблица (выбрано, вкл/выкл,
 * имя, файл, X Y Z, поворот/отражение) с сортировкой по заголовкам. Действия над выбранной строкой —
 * в подвале и в меню ПКМ: «Двигать», «Сверху», «Материалы», «Выбрать» / «Снять выбор» (переключатель:
 * на уже выбранном в Litematica размещении снимает выбор; Litematica в обоих случаях закрывает свой
 * открытый список материалов — сказано в подсказке), «Удалить» (с подтверждением). Точка в первой колонке
 * переключает так же. Двойной клик — «Двигать». Список перечитывается раз в секунду: размещения меняются
 * и вне экрана.
 */
@Environment(EnvType.CLIENT)
final class PlacementsPane extends Pane
{
    private static final int ROW_H = Ui.ROW_H;
    private static final long RELOAD_MS = 1000L;

    /** Сортировка таблицы на время игры. */
    private static int sortColumn = 3;
    private static boolean sortDescending;

    private final Table<SchematicPlacement> table;
    private final Button move;
    private final Button materials;
    private final Button choose;
    private final Button delete;
    // ===== Syncmatica: общие размещения сервера =====
    /** Цвет Syncmatica — «чужой» фиолетовый: общее, а не своё. */
    static final int SYNC = Ui.C_FOREIGN;
    private final Table<Syncmatica.Remote> remoteTable;
    private final Button share;
    private final Button server;
    private final Button remoteLoad;
    private final Button remoteRemove;
    private boolean serverView;
    private List<Syncmatica.Remote> remote = List.of();

    private List<SchematicPlacement> all = List.of();
    /** «Выбрать»: отметка нескольких размещений, чтобы удалить их разом. */
    private boolean picking;
    private final java.util.Set<SchematicPlacement> checked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long loadedAt;

    PlacementsPane(GuiSchematics screen)
    {
        super(screen);

        this.table = new Table<SchematicPlacement>(ROW_H).columns(
                Table.Column.<SchematicPlacement>fixed("", ROW_H)
                        .headerTooltip(Tr.t("placements.col.selected"))
                        .cell(this::renderSelectedCell)
                        .onClick((p, click) -> this.clickSelect(p, click)),
                Table.Column.<SchematicPlacement>fixed(Tr.t("placements.col.enabled"), 40)
                        .align(Table.Align.CENTER)
                        .cell(this::renderEnabledCell)
                        .onClick((p, click) -> this.clickToggle(p, click))
                        .sort(Comparator.comparing(SchematicPlacement::isEnabled)),
                Table.Column.<SchematicPlacement>fixed(Tr.t("placements.col.render"), 52)
                        .align(Table.Align.CENTER)
                        .hideBelow(480)
                        .cell(this::renderRenderCell)
                        .onClick((p, click) -> this.clickRender(p, click))
                        .sort(Comparator.comparing(SchematicPlacement::isRenderingEnabled)),
                Table.Column.<SchematicPlacement>flex(Tr.t("placements.col.name"), 3)
                        .minWidth(60)
                        .cell(this::renderNameCell)
                        .sort(Comparator.comparing(SchematicPlacement::getName, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<SchematicPlacement>flex(Tr.t("placements.col.file"), 2)
                        .hideBelow(520)
                        .text(PlacementsPane::fileName)
                        .tooltip(p -> p.getSchematicFile() == null ? Tr.t("placements.no_file") : p.getSchematicFile().toString())
                        .sort(Comparator.comparing(PlacementsPane::fileName, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<SchematicPlacement>fixed(Tr.t("placements.col.origin"), 120)
                        .align(Table.Align.RIGHT)
                        .text(PlacementsPane::origin)
                        .sort(Comparator.<SchematicPlacement>comparingInt(p -> p.getOrigin().getX())
                                .thenComparingInt(p -> p.getOrigin().getY())
                                .thenComparingInt(p -> p.getOrigin().getZ())),
                Table.Column.<SchematicPlacement>fixed(Tr.t("placements.col.transform"), 110)
                        .hideBelow(420)
                        .text(PlacementsPane::transform)
                        .sort(Comparator.comparing((SchematicPlacement p) -> p.getRotation().ordinal())
                                .thenComparing(p -> p.getMirror().ordinal())))
                .onSort((column, descending) ->
                {
                    sortColumn = column;
                    sortDescending = descending;
                })
                .listener(new ScrollList.Listener<>()
                {
                    @Override
                    public void activate(SchematicPlacement item, int index)
                    {
                        PlacementsPane.this.screen.edit(item, false, false);
                    }

                    @Override
                    public void context(SchematicPlacement item, int index, int mouseX, int mouseY)
                    {
                        PlacementsPane.this.openMenu(item, mouseX, mouseY);
                    }
                });
        this.table.setSort(sortColumn, sortDescending);

        this.move = new Button(Tr.t("placements.place"), Button.Style.PRIMARY, () -> this.withSelected(p -> screen.edit(p, false, false)))
                .accent(Theme.GREEN).tooltip(Tr.t("placements.place.tip"));
        // «Материалы» — пунктир цвета акцента: открывает расчёт, а не меняет размещение
        this.materials = new Button(Tr.t("placements.materials"), Button.Style.SECONDARY,
                () -> this.withSelected(p -> screen.showMaterials(null, p))).tooltip(Tr.t("placements.materials.tip"))
                .edge(Ui.C_ACCENT, false);
        this.choose = new Button(Tr.t("placements.pick"), Button.Style.SECONDARY, this::togglePicking)
                .tooltip(Tr.t("placements.pick.tip"));
        this.delete = new Button(Tr.t("placements.delete"), Button.Style.DANGER, this::deleteChosen);

        this.share = new Button(Tr.t("sync.share"), Button.Style.PRIMARY, this::shareOrUnshare).accent(SYNC);
        this.server = new Button(Tr.t("sync.server"), Button.Style.SECONDARY, this::toggleServerView)
                .tooltip(Tr.t("sync.server.tip"));
        this.remoteLoad = new Button(Tr.t("sync.load"), Button.Style.PRIMARY, () -> this.withRemote(this::loadOrDownload))
                .accent(SYNC);
        this.remoteRemove = new Button(Tr.t("sync.remove"), Button.Style.DANGER, () -> this.withRemote(this::confirmRemoveRemote))
                .tooltip(Tr.t("sync.remove.tip"));

        this.remoteTable = new Table<Syncmatica.Remote>(ROW_H).columns(
                Table.Column.<Syncmatica.Remote>flex(Tr.t("placements.col.name"), 3)
                        .minWidth(60)
                        .text(Syncmatica.Remote::name)
                        .sort(Comparator.comparing(Syncmatica.Remote::name, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<Syncmatica.Remote>flex(Tr.t("placements.col.file"), 2)
                        .hideBelow(520)
                        .text(Syncmatica.Remote::file)
                        .sort(Comparator.comparing(Syncmatica.Remote::file, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<Syncmatica.Remote>fixed(Tr.t("sync.col.owner"), 90)
                        .hideBelow(460)
                        .text(Syncmatica.Remote::owner)
                        .sort(Comparator.comparing(Syncmatica.Remote::owner, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<Syncmatica.Remote>fixed(Tr.t("placements.col.origin"), 120)
                        .align(Table.Align.RIGHT)
                        .text(r -> Tr.f("info.origin", r.origin().getX(), r.origin().getY(), r.origin().getZ()))
                        .tooltip(Syncmatica.Remote::dimension),
                Table.Column.<Syncmatica.Remote>fixed(Tr.t("sync.col.state"), 96)
                        .cell(this::renderStateCell)
                        .sort(Comparator.comparingInt(PlacementsPane::stateRank)))
                .listener(new ScrollList.Listener<>()
                {
                    @Override
                    public void activate(Syncmatica.Remote item, int index)
                    {
                        PlacementsPane.this.loadOrDownload(item);
                    }

                    @Override
                    public void context(Syncmatica.Remote item, int index, int mouseX, int mouseY)
                    {
                        PlacementsPane.this.openRemoteMenu(item, mouseX, mouseY);
                    }
                });
        this.remoteTable.setSort(0, false);
    }

    // ===== Syncmatica =====

    private static int stateRank(Syncmatica.Remote r)
    {
        return r.rendered() ? 0 : r.downloading() ? 2 : r.fileReady() ? 1 : 3;
    }

    private static String stateText(Syncmatica.Remote r)
    {
        return Tr.t(r.rendered() ? "sync.state.loaded" : r.downloading() ? "sync.state.downloading"
                : r.fileReady() ? "sync.state.downloaded" : "sync.state.remote");
    }

    private void renderStateCell(GuiContext ctx, Syncmatica.Remote r, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY)
    {
        int color = r.rendered() ? SYNC : r.downloading() ? Ui.C_CHANGED : r.fileReady() ? Ui.C_TEXT : Ui.C_TEXT_3;
        Paint.midText(ctx, Kit.font(), Paint.fit(Kit.font(), stateText(r), w - Ui.PAD_IN * 2), x + Ui.PAD_IN, y, h, color, false);
    }

    /** Имя; общее размещение Syncmatica — с меткой «сервер» справа. */
    private void renderNameCell(GuiContext ctx, SchematicPlacement placement, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY)
    {
        var font = Kit.font();
        int room = w - Ui.PAD_IN * 2;

        // Закреплённое размещение: золотой замок у правого края ячейки, имя — до него
        if (placement.isLocked())
        {
            int lw = Glyphs.width(Glyphs.LOCK);
            int lh = Glyphs.height(Glyphs.LOCK);
            int lx = x + w - Ui.PAD_IN - lw;
            int ly = y + (h - lh) / 2;
            Glyphs.draw(ctx, Glyphs.LOCK, lx, ly, Theme.GOLD);
            room -= lw + Ui.PAD_IN;

            if (Paint.hit(mouseX, mouseY, lx, ly, lw, lh))
            {
                Tooltip.offer("placements#lock", Tr.t(Syncmatica.shared(placement) ? "place.lock.shared.tip" : "place.lock.on.tip"));
            }
        }

        if (Syncmatica.shared(placement))
        {
            String tag = Tr.t("sync.tag");
            int tw = font.width(tag) + 6;
            // Метка сразу за именем; длинное имя обрезается так, чтобы метка влезла
            String name = Paint.fit(font, placement.getName(), Math.max(0, room - tw - Ui.PAD_IN));
            int tx = x + Ui.PAD_IN + font.width(name) + Ui.PAD_IN;
            int th = 11;
            int ty = y + (h - th) / 2;
            Paint.round(ctx, tx, ty, tw, th, 3, Ui.alpha(SYNC, 0.18F));
            Paint.roundBorder(ctx, tx, ty, tw, th, 3, Ui.alpha(SYNC, 0.7F));
            Paint.midText(ctx, font, tag, tx + 3, ty, th, SYNC, false);
            Paint.midText(ctx, font, name, x + Ui.PAD_IN, y, h, Ui.C_TEXT, false);

            if (mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + th)
            {
                Tooltip.offer("placements#sync", Tr.t("sync.tag.tip"));
            }

            return;
        }

        Paint.midText(ctx, font, Paint.fit(font, placement.getName(), Math.max(0, room)), x + Ui.PAD_IN, y, h, Ui.C_TEXT, false);
    }

    private void toggleServerView()
    {
        this.serverView = ! this.serverView;
        this.reload();
        this.layoutTables();
    }

    private void withRemote(Consumer<Syncmatica.Remote> action)
    {
        Syncmatica.Remote r = this.remoteTable.selectedItem();

        if (r != null)
        {
            action.accept(r);
        }
    }

    /** Двойной щелчок / главная кнопка: загружена — выгрузить, файл есть — загрузить, нет — скачать. */
    private void loadOrDownload(Syncmatica.Remote r)
    {
        if (r.rendered())
        {
            Syncmatica.unload(r);
        }
        else if (r.fileReady())
        {
            Syncmatica.load(r);
        }
        else if (! r.downloading())
        {
            Syncmatica.download(r);
            this.screen.toast(Tr.f("sync.downloading", r.name()), Ui.C_TEXT_2);
        }

        this.reload();
    }

    private String loadLabel(Syncmatica.@Nullable Remote r)
    {
        return Tr.t(r == null || (! r.rendered() && r.fileReady()) ? "sync.load"
                : r.rendered() ? "sync.unload" : r.downloading() ? "sync.state.downloading" : "sync.download");
    }

    private void confirmRemoveRemote(Syncmatica.Remote r)
    {
        com.aell.layerlist.ui.kit.Modal.confirm(this.screen.layers, Tr.t("sync.remove.title"), Tr.f("sync.remove.text", r.name()),
                Tr.t("sync.remove.yes"), true, () ->
                {
                    Syncmatica.remove(r);
                    this.reload();
                }, Tr.t("cancel"));
    }

    private void openRemoteMenu(Syncmatica.Remote r, int mouseX, int mouseY)
    {
        List<ContextMenu.Item> items = new ArrayList<>();
        items.add(r.downloading() ? ContextMenu.Item.disabled(this.loadLabel(r), Tr.t("sync.state.downloading"))
                : ContextMenu.Item.of(this.loadLabel(r), () -> this.loadOrDownload(r)));

        if (r.fileReady() && ! r.downloading())
        {
            items.add(ContextMenu.Item.of(Tr.t("sync.redownload"), () ->
            {
                Syncmatica.download(r);
                this.reload();
            }).withTooltip(Tr.t("sync.redownload.tip")));
        }

        items.add(ContextMenu.Item.separator());
        items.add(ContextMenu.Item.danger(Tr.t("sync.remove"), () -> this.confirmRemoveRemote(r)).withTooltip(Tr.t("sync.remove.tip")));
        ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, items);
    }

    /** Своё — поделиться; общее — убрать с сервера (с подтверждением). */
    private void shareOrUnshare()
    {
        this.withSelected(p ->
        {
            if (Syncmatica.shared(p))
            {
                Syncmatica.Remote r = Syncmatica.remoteOf(p);

                if (r != null)
                {
                    this.confirmRemoveRemote(r);
                }
            }
            else
            {
                this.screen.share(p);
            }
        });
    }

    private static String fileName(SchematicPlacement placement)
    {
        Path file = placement.getSchematicFile();
        Path name = file == null ? null : file.getFileName();
        return name == null ? Tr.t("placements.no_file") : name.toString();
    }

    private static String origin(SchematicPlacement placement)
    {
        BlockPos origin = placement.getOrigin();
        return Tr.f("info.origin", origin.getX(), origin.getY(), origin.getZ());
    }

    private static String transform(SchematicPlacement placement)
    {
        String mirror = Tr.mirror(placement.getMirror());
        String rotation = Tr.rotation(placement.getRotation());
        return mirror.isEmpty() ? rotation : rotation + Ui.TITLE_SEPARATOR + mirror;
    }

    private static @Nullable SchematicPlacement litematicaSelected()
    {
        try
        {
            return PlacementOps.selected();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    int count()
    {
        return this.all.size();
    }

    void reload()
    {
        this.loadedAt = Util.getMillis();

        try
        {
            this.all = List.copyOf(PlacementOps.all());
        }
        catch (RuntimeException e)
        {
            this.all = List.of();
        }

        this.table.setItems(this.all);
        this.table.setEmptyText(Minecraft.getInstance().level == null ? Tr.t("placements.no_world") : Tr.t("placements.empty"));

        this.remote = Syncmatica.active() ? Syncmatica.remote() : List.of();
        this.remoteTable.setItems(this.remote);
        this.remoteTable.setEmptyText(Tr.t("sync.empty"));

        if (this.serverView && ! Syncmatica.active())
        {
            this.serverView = false;
        }
    }

    @Override
    void tick()
    {
        if (Util.getMillis() - this.loadedAt >= RELOAD_MS)
        {
            this.reload();
        }
    }

    private void togglePicking()
    {
        this.picking = ! this.picking;
        this.checked.clear();
    }

    /** «Удалить»: в режиме выбора — все отмеченные (одно подтверждение), иначе — строку под курсором. */
    private void deleteChosen()
    {
        if (! this.picking || this.checked.isEmpty())
        {
            this.withSelected(this.screen::confirmRemove);
            return;
        }

        List<SchematicPlacement> doomed = List.copyOf(this.checked);
        com.aell.layerlist.ui.kit.Modal.confirm(this.screen.layers, Tr.t("placements.delete_many.title"),
                Tr.f("placements.delete_many.text", doomed.size()), Tr.t("delete.yes"), true, () ->
                {
                    for (SchematicPlacement p : doomed)
                    {
                        try
                        {
                            PlacementOps.remove(p);
                        }
                        catch (RuntimeException e)
                        {
                            this.screen.toast(Tr.f("delete.failed", Tr.reason(e)), Ui.C_DANGER);
                        }
                    }

                    this.checked.clear();
                    this.picking = false;
                    this.reload();
                }, Tr.t("cancel"));
    }

    private void withSelected(Consumer<SchematicPlacement> action)
    {
        SchematicPlacement selected = this.table.selectedItem();

        if (selected != null)
        {
            action.accept(selected);
        }
    }

    /**
     * «Выбрать» / «Снять выбор»: выбранное в Litematica — снять выбор (ни одно не выбрано), иное — выбрать.
     * Litematica в обоих случаях закрывает свой открытый список материалов ({@code setMaterialList(null)}).
     */
    private void toggleSelect(SchematicPlacement placement)
    {
        boolean deselect = placement == litematicaSelected();

        try
        {
            if (deselect)
            {
                PlacementOps.deselect();
            }
            else
            {
                PlacementOps.select(placement);
            }
        }
        catch (RuntimeException e)
        {
            this.screen.toast(Tr.f(deselect ? "placements.deselect_failed" : "placements.select_failed", Tr.reason(e)),
                    Ui.C_WARN);
        }
    }

    private boolean clickSelect(SchematicPlacement placement, MouseButtonEvent click)
    {
        if (! Kit.left(click))
        {
            return false;
        }

        Vanilla.click();

        if (this.picking)
        {
            if (! this.checked.remove(placement))
            {
                this.checked.add(placement);
            }

            return true;
        }

        this.toggleSelect(placement);
        return true;
    }

    private boolean clickToggle(SchematicPlacement placement, MouseButtonEvent click)
    {
        if (! Kit.left(click))
        {
            return false;
        }

        Vanilla.click();
        this.screen.toggleEnabled(placement);
        return true;
    }

    private boolean clickRender(SchematicPlacement placement, MouseButtonEvent click)
    {
        if (! Kit.left(click))
        {
            return false;
        }

        Vanilla.click();
        this.screen.toggleRender(placement);
        return true;
    }

    private void renderSelectedCell(GuiContext ctx, SchematicPlacement placement, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY)
    {
        if (this.picking)
        {
            // Флажок отметки вместо точки «выбрано в Litematica»
            boolean on = this.checked.contains(placement);
            int box = 9;
            int bx = x + (w - box) / 2;
            int by = y + (h - box) / 2;

            // Ванильный стиль: галочка Minecraft вместо квадратика с акцентом
            if (Style.vanilla())
            {
                Vanilla.checkbox(ctx, bx, by, box, box, on, hovered);
                return;
            }

            Paint.card(ctx, bx, by, box, box, 2, on ? Ui.C_ACCENT : 0x30000000, on ? Ui.C_ACCENT : (hovered ? Ui.C_TEXT_2 : Ui.C_TEXT_3));

            if (on)
            {
                Paint.icon(ctx, com.aell.layerlist.ui.kit.Glyphs.CHECK, bx + 1, by + 2, 0xFF101014);
            }

            return;
        }

        boolean selected = placement == litematicaSelected();
        int size = Ui.STATUS_DOT + 2;
        int dx = x + (w - size) / 2;
        int dy = y + (h - size) / 2;

        if (selected)
        {
            Paint.round(ctx, dx, dy, size, size, size / 2, Theme.BLUE);
        }
        else if (hovered)
        {
            Paint.roundBorder(ctx, dx, dy, size, size, size / 2, Ui.C_TEXT_3);
        }

        if (hovered)
        {
            Tooltip.offer("placements#selected", selected ? Tr.t("placements.deselect.dot_tip") : Tr.t("placements.select.tip"));
        }
    }

    private void renderEnabledCell(GuiContext ctx, SchematicPlacement placement, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY)
    {
        drawToggle(ctx, placement.isEnabled(), x, y, w, h, hovered);

        if (hovered)
        {
            Tooltip.offer("placements#enabled", Tr.f("placements.enabled_state", Strings.onOff(placement.isEnabled())));
        }
    }

    private void renderRenderCell(GuiContext ctx, SchematicPlacement placement, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY)
    {
        drawToggle(ctx, placement.isRenderingEnabled(), x, y, w, h, hovered);

        if (hovered)
        {
            Tooltip.offer("placements#render", Tr.f("placements.render_state", Strings.onOff(placement.isRenderingEnabled())));
        }
    }

    /** Тумблер в ячейке — как {@link Toggle}, но без подписи: подпись — заголовок колонки (до 0.0.27 — флажок). */
    private static void drawToggle(GuiContext ctx, boolean on, int x, int y, int w, int h, boolean hovered)
    {
        Toggle.drawTrack(ctx, x + (w - Toggle.TRACK) / 2, y, h, on ? 1.0F : 0.0F, hovered ? 1.0F : 0.0F, true);
    }

    private void openMenu(SchematicPlacement placement, int mouseX, int mouseY)
    {
        List<ContextMenu.Item> items = new ArrayList<>();
        items.add(ContextMenu.Item.of(Tr.t("placements.place"), () -> this.screen.edit(placement, false, false)));
        items.add(ContextMenu.Item.of(Tr.t("placements.materials"), () -> this.screen.showMaterials(null, placement)));
        boolean chosen = placement == litematicaSelected();
        items.add(ContextMenu.Item.of(Tr.t(chosen ? "placements.deselect" : "placements.select"), () -> this.toggleSelect(placement))
                .withTooltip(Tr.t(chosen ? "placements.deselect.tip" : "placements.select.tip")));

        // Замок Litematica; общее размещение Syncmatica запирает сама Syncmatica — не трогаем
        boolean locked = placement.isLocked();
        String lockLabel = Tr.t(locked ? "placements.unlock" : "placements.lock");
        items.add(Syncmatica.shared(placement) ? ContextMenu.Item.disabled(lockLabel, Tr.t("place.lock.shared.tip"))
                : ContextMenu.Item.of(lockLabel, placement::toggleLocked)
                        .withTooltip(Tr.t(locked ? "place.lock.on.tip" : "place.lock.off.tip")));
        items.add(ContextMenu.Item.separator());

        if (placement.getSchematicFile() != null)
        {
            Path file = placement.getSchematicFile();
            items.add(ContextMenu.Item.of(Tr.t("menu.show_in_folder"), () -> this.screen.openInSystem(file)));
            items.add(ContextMenu.Item.of(Tr.t("menu.copy_path"), () -> this.screen.copyPath(file)));
            items.add(ContextMenu.Item.separator());
        }

        if (Syncmatica.installed() && ! Syncmatica.active())
        {
            items.add(ContextMenu.Item.disabled(Tr.t("sync.share"), Tr.t("sync.error.inactive")));
            items.add(ContextMenu.Item.separator());
        }
        else if (Syncmatica.active())
        {
            if (Syncmatica.shared(placement))
            {
                items.add(ContextMenu.Item.of(Tr.t("sync.hide"), () ->
                {
                    Syncmatica.hide(placement);
                    this.reload();
                }).withTooltip(Tr.t("sync.hide.tip")));
                Syncmatica.Remote r = Syncmatica.remoteOf(placement);

                if (r != null)
                {
                    items.add(ContextMenu.Item.danger(Tr.t("sync.remove"), () -> this.confirmRemoveRemote(r))
                            .withTooltip(Tr.t("sync.remove.tip")));
                }
            }
            else
            {
                items.add(ContextMenu.Item.of(Tr.t("sync.share"), () -> this.screen.share(placement))
                        .withTooltip(Tr.t("sync.share.tip")));
            }

            items.add(ContextMenu.Item.separator());
        }

        items.add(ContextMenu.Item.danger(Tr.t("placements.delete"), () -> this.screen.confirmRemove(placement)));
        ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, items);
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);
        this.layoutTables();
    }

    private void layoutTables()
    {
        this.table.setBounds(this.x + Ui.OUTER, this.y + Ui.PAD_IN, Math.max(0, this.w - Ui.OUTER * 2), Math.max(0, this.h - Ui.PAD_IN * 2));
        this.remoteTable.setBounds(this.x + Ui.OUTER, this.y + Ui.PAD_IN, Math.max(0, this.w - Ui.OUTER * 2), Math.max(0, this.h - Ui.PAD_IN * 2));
    }

    private Table<?> current()
    {
        return this.serverView ? this.remoteTable : this.table;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.current().render(ctx, mouseX, mouseY);
    }

    @Override
    List<Button> footerActions()
    {
        SchematicPlacement row = this.table.selectedItem();
        boolean any = row != null;

        for (Button button : List.of(this.move, this.materials))
        {
            button.setEnabled(any);
        }

        this.materials.dashed(any);

        this.choose.setEnabled(! this.all.isEmpty());
        this.choose.label(Tr.t(this.picking ? "placements.pick.done" : "placements.pick"));
        this.choose.selected(this.picking);
        this.checked.retainAll(this.all);
        this.delete.setEnabled(this.picking ? ! this.checked.isEmpty() : any);
        this.delete.label(this.picking && ! this.checked.isEmpty() ? Tr.f("placements.delete_n", this.checked.size()) : Tr.t("placements.delete"));

        if (! Syncmatica.installed())
        {
            return List.of(this.move, this.materials, this.choose, this.delete);
        }

        if (! Syncmatica.active())
        {
            // Мод стоит, а сервер без Syncmatica: кнопки на месте, серые, причина — в подсказке
            this.share.label(Tr.t("sync.share"));
            this.share.style(Button.Style.PRIMARY);
            this.share.tooltip(Tr.t("sync.error.inactive"));
            this.share.setEnabled(false);
            this.server.label(Tr.t("sync.server"));
            this.server.tooltip(Tr.t("sync.error.inactive"));
            this.server.setEnabled(false);
            return List.of(this.move, this.materials, this.choose, this.delete, this.share, this.server);
        }

        this.server.setEnabled(true);

        this.server.label(this.serverView ? Tr.t("sync.mine") : Tr.f("sync.server_n", this.remote.size()));
        this.server.tooltip(Tr.t(this.serverView ? "sync.mine.tip" : "sync.server.tip"));

        if (this.serverView)
        {
            Syncmatica.Remote r = this.remoteTable.selectedItem();
            this.remoteLoad.label(this.loadLabel(r));
            this.remoteLoad.setEnabled(r != null && ! r.downloading());
            this.remoteRemove.setEnabled(r != null);
            return List.of(this.remoteLoad, this.remoteRemove, this.server);
        }

        boolean shared = Syncmatica.shared(row);
        this.share.label(Tr.t(shared ? "sync.remove" : "sync.share"));
        this.share.style(shared ? Button.Style.DANGER : Button.Style.PRIMARY);
        this.share.tooltip(Tr.t(shared ? "sync.remove.tip" : "sync.share.tip"));
        this.share.setEnabled(any && ! this.picking);
        return List.of(this.move, this.materials, this.choose, this.delete, this.share, this.server);
    }

    @Override
    String footerStatus()
    {
        if (this.serverView)
        {
            return Tr.f("sync.status", Tr.num(this.remote.size()));
        }

        return this.picking ? Tr.f("placements.picked", this.checked.size(), Tr.num(this.all.size()))
                : Tr.f("placements.status", Tr.num(this.all.size()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        this.current().setFocused(true);
        this.current().mouseClicked(click, doubleClick);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.current().mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        return this.current().mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.current().mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        this.current().setFocused(true);
        return this.current().keyPressed(key);
    }
}
