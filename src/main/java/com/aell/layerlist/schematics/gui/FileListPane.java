package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Dropdown;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Tooltip;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Левая панель «Библиотеки»: поиск (с тумблером «во всех папках»), сортировка Имя/Дата/Размер,
 * список «↑ ..», папок (число схем внутри) и файлов (миниатюра 32×32, имя и строка сведений
 * «64×32×80 · 12 345 бл. · .litematic · 03.10.2026»). Данные — из {@link SchematicLibrary}; сведения
 * запрашиваются только для видимых строк.
 *
 * <p>Мышь: клик по файлу — выбрать, двойной клик — «Разместить здесь», клик по папке — войти, ПКМ —
 * меню. Клавиатура: ↑/↓, PgUp/PgDn, Home/End, Enter, Backspace — папка выше, буква — в поиск,
 * Ctrl+F — поиск, Delete — удалить строку (в корзину, с подтверждением). Брошенные в окно файлы схем
 * копируются в текущую папку (с вопросом о замене). Внизу корня, пока корзина не пуста, — строка
 * «Корзина (N)»: вкладка «Корзина» ({@link TrashPane}).
 */
@Environment(EnvType.CLIENT)
final class FileListPane extends Pane
{
    private static final int FILE_ROW_H = 36;
    private static final int THUMB = 32;
    private static final int THUMB_PAD = 2;
    private static final long SEARCH_DELAY_MS = 150L;

    enum Kind
    {
        UP,
        FOLDER,
        FILE,
        /** Строка «Корзина» внизу корня — открывает окно корзины. */
        TRASH
    }

    record Item(Kind kind, @Nullable SchematicEntry entry) {}

    private record CopyResult(int copied, List<String> failed, @Nullable Path first) {}

    private static final Item UP_ITEM = new Item(Kind.UP, null);
    private static final Item TRASH_ITEM = new Item(Kind.TRASH, null);

    private final TextInput search;
    private final Toggle allFolders;
    private final Dropdown<SchematicsState.Sort> sort;
    private final Button direction;
    private final ScrollList<Item> list;
    private final Map<Path, Integer> folderCounts = new HashMap<>();

    private Path folder;
    private List<SchematicEntry> listing = List.of();

    // ===== Только что сохранённая схема («Сохранить схему» во «Выделении») =====
    /** Подсветка гаснет за это время. */
    static final long NEW_FADE_MS = 30_000L;
    private static final int NEW_COLOR = 0xFFF5C542;
    /** Свежая схема — первой в списке, пока экран открыт (один раз); подсветка — {@link #NEW_FADE_MS}. */
    private static @Nullable Path freshPath;
    private static boolean freshPinned;
    private static long freshAt;

    /** Отметить только что сохранённый файл: сверху списка и в жёлтой рамке с «NEW». */
    static void markNew(Path file)
    {
        freshPath = file.toAbsolutePath().normalize();
        freshPinned = true;
        freshAt = net.minecraft.util.Util.getMillis();
    }

    /** Насколько ещё видна подсветка свежей схемы: 1 — только что, 0 — погасла. */
    private static float freshFade(@Nullable SchematicEntry entry)
    {
        if (entry == null || freshPath == null || ! entry.path().toAbsolutePath().normalize().equals(freshPath))
        {
            return 0.0F;
        }

        long age = net.minecraft.util.Util.getMillis() - freshAt;
        return age >= NEW_FADE_MS ? 0.0F : 1.0F - age / (float) NEW_FADE_MS;
    }
    private @Nullable String listError;
    private String query = "";
    private long searchDue;
    private @Nullable CompletableFuture<List<SchematicEntry>> searchFuture;
    private @Nullable List<SchematicEntry> found;
    private @Nullable String searchError;
    private int fileCount;
    private int folderCount;
    private int trashCount;

    FileListPane(GuiSchematics screen)
    {
        super(screen);

        SchematicsState state = SchematicsState.get();

        this.search = new TextInput().hint(Tr.t("files.search_hint")).searchIcon(true).maxLength(128)
                .onChange(this::onQuery).onSubmit(text -> this.focusList(true));
        this.allFolders = new Toggle(Tr.t("files.all_folders"), () -> SchematicsState.get().searchAllFolders(),
                value ->
                {
                    SchematicsState.get().setSearchAllFolders(value);
                    this.restartSearch();
                }).tooltip(Tr.t("files.all_folders.tip"));
        this.sort = new Dropdown<>(screen.layers, List.of(SchematicsState.Sort.values()), state.sort(),
                FileListPane::sortLabel, value ->
                {
                    SchematicsState.get().setSort(value, SchematicsState.get().sortDescending());
                    this.rebuild();
                }).tooltip(Tr.t("files.sort.tip"));
        this.direction = new IconButton(Glyphs.SORT_ASC, Tr.t("files.sort_dir.tip"), Button.Style.GHOST,
                this::flipDirection);
        this.list = new ScrollList<Item>(this::renderRow)
                .rowHeight(item -> item.kind == Kind.FILE ? FILE_ROW_H : Ui.ROW_H)
                .listener(new ScrollList.Listener<>()
                {
                    @Override
                    public void select(@Nullable Item item, int index)
                    {
                        FileListPane.this.screen.select(item != null && item.kind == Kind.FILE ? item.entry : null);
                    }

                    @Override
                    public void activate(Item item, int index)
                    {
                        FileListPane.this.activate(item);
                    }

                    @Override
                    public void context(Item item, int index, int mouseX, int mouseY)
                    {
                        FileListPane.this.openMenu(item, mouseX, mouseY);
                    }

                    @Override
                    public boolean click(Item item, int index, int rowX, int rowY, int rowW, int rowH,
                            MouseButtonEvent click)
                    {
                        // Папку не «выбирают» — в неё входят: превью для папки показывать нечего
                        if (Kit.left(click) && item.kind != Kind.FILE)
                        {
                            FileListPane.this.activate(item);
                            return true;
                        }

                        return false;
                    }
                });

        this.folder = this.initialFolder();
        this.syncDirection();
        this.reload();
    }

    private static String sortLabel(SchematicsState.Sort sort)
    {
        return switch (sort)
        {
            case NAME -> Tr.t("files.sort.name");
            case DATE -> Tr.t("files.sort.date");
            case SIZE -> Tr.t("files.sort.size");
        };
    }

    private Path root()
    {
        return SchematicLibrary.get().root();
    }

    private Path initialFolder()
    {
        Path root = this.root();
        String last = SchematicsState.get().lastFolder();

        if (last.isEmpty())
        {
            return root;
        }

        try
        {
            Path candidate = root.resolve(last).normalize();
            return candidate.startsWith(root) && Files.isDirectory(candidate) ? candidate : root;
        }
        catch (RuntimeException e)
        {
            return root;
        }
    }

    Path folder()
    {
        return this.folder;
    }

    private String relative(Path path)
    {
        Path root = this.root();
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : "";
    }

    // ===== Навигация и данные =====

    void open(Path dir)
    {
        this.folder = dir.toAbsolutePath().normalize();
        SchematicsState.get().setLastFolder(this.relative(this.folder));

        if (! this.query.isEmpty())
        {
            this.query = "";
            this.search.setText("");
            this.found = null;
            this.searchFuture = null;
            this.searchDue = 0L;
        }

        this.screen.select(null);
        this.reload();
        this.list.setScroll(0);
        this.list.setSelectedIndex(-1);
    }

    private void up()
    {
        Path parent = this.folder.getParent();

        if (this.folder.equals(this.root()) || parent == null || ! parent.startsWith(this.root()))
        {
            return;
        }

        Path from = this.folder;
        this.open(parent);

        // Возврат на папку, из которой вышли, — как в проводнике
        for (Item item : this.list.items())
        {
            if (item.entry != null && item.entry.path().equals(from))
            {
                this.list.setSelected(item);
                break;
            }
        }
    }

    void refresh()
    {
        this.folderCounts.clear();
        this.reload();
        this.restartSearch();
    }

    private void reload()
    {
        try
        {
            this.listing = SchematicLibrary.get().list(this.folder);
            this.listError = null;
        }
        catch (RuntimeException e)
        {
            this.listing = List.of();
            this.listError = Tr.reason(e);
        }

        this.rebuild();
    }

    private void rebuild()
    {
        List<Item> items = new ArrayList<>();
        List<SchematicEntry> source;

        if (this.query.isEmpty())
        {
            if (! this.folder.equals(this.root()))
            {
                items.add(UP_ITEM);
            }

            source = this.listing;
            this.list.setEmptyText(this.listError != null ? Tr.f("files.read_error", this.listError) : Tr.t("files.empty"));
        }
        else if (this.found == null)
        {
            source = List.of();
            this.list.setEmptyText(this.searchError != null ? Tr.f("files.search_error", this.searchError)
                    : Tr.t("files.searching"));
        }
        else
        {
            source = this.found;
            this.list.setEmptyText(Tr.t("files.not_found"));
        }

        this.fileCount = 0;
        this.folderCount = 0;
        List<SchematicEntry> sorted = new ArrayList<>(source);
        sorted.sort(this.comparator());

        // Только что сохранённая — первой, над папками (один раз: до закрытия экрана)
        if (freshPinned && freshPath != null && this.query.isEmpty())
        {
            for (SchematicEntry entry : sorted)
            {
                if (! entry.directory() && entry.path().toAbsolutePath().normalize().equals(freshPath))
                {
                    sorted.remove(entry);
                    items.add(new Item(Kind.FILE, entry));
                    ++this.fileCount;
                    break;
                }
            }
        }


        for (SchematicEntry entry : sorted)
        {
            if (entry.directory())
            {
                items.add(new Item(Kind.FOLDER, entry));
                ++this.folderCount;
            }
        }

        for (SchematicEntry entry : sorted)
        {
            if (! entry.directory())
            {
                items.add(new Item(Kind.FILE, entry));
                ++this.fileCount;
            }
        }

        // Корзина — внизу корня и только когда в ней что-то есть: пустую показывать незачем
        this.trashCount = 0;

        if (this.query.isEmpty() && this.folder.equals(this.root()))
        {
            try
            {
                this.trashCount = SchematicTrash.get().count();
            }
            catch (RuntimeException e)
            {
                this.trashCount = 0;
            }

            if (this.trashCount > 0)
            {
                items.add(TRASH_ITEM);
            }
        }

        this.list.setItems(items);
        this.syncSelection();
    }

    /** Выбор экрана — по пути: после «Обновить» запись файла новая (размер, время), а файл тот же. */
    private void syncSelection()
    {
        SchematicEntry selected = this.screen.selected();

        if (selected == null)
        {
            return;
        }

        for (Item item : this.list.items())
        {
            if (item.entry != null && item.entry.path().equals(selected.path()))
            {
                this.list.setSelected(item);

                if (! item.entry.equals(selected))
                {
                    this.screen.select(item.entry);
                }

                return;
            }
        }
    }

    private Comparator<SchematicEntry> comparator()
    {
        SchematicsState state = SchematicsState.get();
        Comparator<SchematicEntry> byName = Comparator.comparing(SchematicEntry::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<SchematicEntry> order = switch (state.sort())
        {
            case NAME -> byName;
            case DATE -> Comparator.comparingLong(SchematicEntry::modified).thenComparing(byName);
            case SIZE -> Comparator.comparingLong(SchematicEntry::size).thenComparing(byName);
        };

        return state.sortDescending() ? order.reversed() : order;
    }

    private void flipDirection()
    {
        SchematicsState state = SchematicsState.get();
        state.setSort(state.sort(), ! state.sortDescending());
        this.syncDirection();
        this.rebuild();
    }

    private void syncDirection()
    {
        boolean descending = SchematicsState.get().sortDescending();
        this.direction.glyph(descending ? Glyphs.SORT_DESC : Glyphs.SORT_ASC);
        this.direction.tooltip(Tr.t(descending ? "files.sort_dir.desc" : "files.sort_dir.asc"));
    }

    private void onQuery(String text)
    {
        this.query = text.strip();
        this.found = null;
        this.searchError = null;
        this.searchFuture = null;
        this.searchDue = this.query.isEmpty() ? 0L : Util.getMillis() + SEARCH_DELAY_MS;
        this.rebuild();
    }

    private void restartSearch()
    {
        if (! this.query.isEmpty())
        {
            this.found = null;
            this.searchFuture = null;
            this.searchDue = Util.getMillis();
            this.rebuild();
        }
    }

    @Override
    void tick()
    {
        if (this.searchDue > 0L && Util.getMillis() >= this.searchDue)
        {
            this.searchDue = 0L;
            Path base = SchematicsState.get().searchAllFolders() ? this.root() : this.folder;

            try
            {
                this.searchFuture = SchematicLibrary.get().search(base, this.query);
            }
            catch (RuntimeException e)
            {
                this.searchFuture = CompletableFuture.failedFuture(e);
            }
        }

        CompletableFuture<List<SchematicEntry>> pending = this.searchFuture;

        if (pending != null && pending.isDone())
        {
            this.searchFuture = null;
            List<SchematicEntry> result = GuiSchematics.done(pending);
            Throwable error = GuiSchematics.failure(pending);
            this.found = result != null ? result : error == null ? List.of() : null;
            this.searchError = error == null ? null : Tr.reason(error);
            this.rebuild();
        }
    }

    private int folderCount(Path dir)
    {
        return this.folderCounts.computeIfAbsent(dir, d ->
        {
            try
            {
                return (int) SchematicLibrary.get().list(d).stream().filter(e -> ! e.directory()).count();
            }
            catch (RuntimeException e)
            {
                return -1;
            }
        });
    }

    private void activate(Item item)
    {
        Vanilla.click();
        switch (item.kind)
        {
            case UP -> this.up();
            case FOLDER ->
            {
                if (item.entry != null)
                {
                    this.open(item.entry.path());
                }
            }
            case FILE ->
            {
                if (item.entry != null)
                {
                    this.screen.place(item.entry, true);
                }
            }
            case TRASH -> this.screen.openTrash();
        }
    }

    private void openMenu(Item item, int mouseX, int mouseY)
    {
        if (item.kind == Kind.TRASH)
        {
            this.openTrashMenu(mouseX, mouseY);
            return;
        }

        SchematicEntry entry = item.entry;

        if (entry == null)
        {
            return;
        }

        List<ContextMenu.Item> items = new ArrayList<>();

        if (item.kind == Kind.FILE)
        {
            boolean busy = this.screen.busyPlacing();
            items.add(busy ? ContextMenu.Item.disabled(Tr.t("menu.place_here"), Tr.t("place.loading"))
                    : ContextMenu.Item.of(Tr.t("menu.place_here"), () -> this.screen.place(entry, true)));
            items.add(busy ? ContextMenu.Item.disabled(Tr.t("menu.place_at"), Tr.t("place.loading"))
                    : ContextMenu.Item.of(Tr.t("menu.place_at"), () -> this.screen.place(entry, false)));
            items.add(ContextMenu.Item.of(Tr.t("menu.materials"), () -> this.screen.showMaterials(entry, null)));

            // Syncmatica: разместить и сразу поделиться с сервером (после «Готово» в редакторе)
            if (com.aell.layerlist.compat.Syncmatica.installed())
            {
                boolean live = com.aell.layerlist.compat.Syncmatica.active();
                items.add(busy || ! live ? ContextMenu.Item.disabled(Tr.t("sync.place_share"),
                        Tr.t(live ? "place.loading" : "sync.error.inactive"))
                        : ContextMenu.Item.of(Tr.t("sync.place_share"), () -> this.screen.placeAndShare(entry))
                                .withTooltip(Tr.t("sync.place_share.tip")));
            }

            // Зоны Zone Focus: разбить прямо в превью (без размещения) или перейти к управлению зонами
            if (com.aell.layerlist.compat.ZoneFocusFiles.available())
            {
                items.add(ContextMenu.Item.separator());

                if (com.aell.layerlist.compat.ZoneFocusFiles.hasZones(entry.path()))
                {
                    items.add(ContextMenu.Item.of(Tr.t("menu.zones_manage"), () -> {
                        this.list.setSelected(item);
                        this.screen.manageZones(entry);
                    }).withTooltip(Tr.t("menu.zones_manage.tip")));
                    items.add(ContextMenu.Item.danger(Tr.t("menu.zones_undo"),
                            () -> com.aell.layerlist.compat.ZoneFocusFiles.clear(entry.path()))
                            .withTooltip(Tr.t("menu.zones_undo.tip")));
                }
                else
                {
                    items.add(ContextMenu.Item.of(Tr.t("menu.zones_split"), () -> {
                        this.list.setSelected(item);
                        this.screen.openZones(entry);
                    }).withTooltip(Tr.t("menu.zones_split.tip")));
                }
            }
        }
        else
        {
            items.add(ContextMenu.Item.of(Tr.t("menu.open_folder"), () -> this.open(entry.path())));
        }

        items.add(ContextMenu.Item.separator());
        items.add(ContextMenu.Item.of(Tr.t("menu.show_in_folder"), () -> this.screen.openInSystem(entry.path())));
        items.add(ContextMenu.Item.of(Tr.t("menu.copy_path"), () -> this.screen.copyPath(entry.path())));
        items.add(ContextMenu.Item.separator());

        // Переименовать — рядом с удалением, как в проводнике; пока схема грузится в размещение — нельзя. Клавиши нет:
        // F2 игра забирает под снимок экрана раньше любого экрана
        if (item.kind == Kind.FILE)
        {
            items.add(this.screen.busyPlacing() ? ContextMenu.Item.disabled(Tr.t("menu.rename"), Tr.t("place.loading"))
                    : ContextMenu.Item.of(Tr.t("menu.rename"), () -> this.screen.confirmRename(entry))
                            .withTooltip(Tr.t("menu.rename.tip")));
        }

        // Удаление — последним и красным; непустую папку не удаляем (в ней бывает не только то, что видно в списке)
        if (item.kind == Kind.FOLDER && ! DeleteFlow.emptyFolder(entry.path()))
        {
            items.add(ContextMenu.Item.disabled(Tr.t("menu.delete"), Tr.t("menu.delete.not_empty")));
        }
        else
        {
            items.add(ContextMenu.Item.danger(Tr.t("menu.delete"), () -> this.screen.confirmDelete(entry))
                    .withTooltip(Tr.f("menu.delete.tip", SchematicTrash.KEEP_DAYS)));
        }

        ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, items);
    }

    private void openTrashMenu(int mouseX, int mouseY)
    {
        List<ContextMenu.Item> items = new ArrayList<>();
        items.add(ContextMenu.Item.of(Tr.t("menu.open_folder"), this.screen::openTrash));
        items.add(ContextMenu.Item.of(Tr.t("menu.show_in_folder"), () ->
        {
            Path root = SchematicTrash.get().root();

            if (Files.isDirectory(root))
            {
                this.screen.openInSystem(root);
            }
        }));
        ContextMenu.open(this.screen.layers, mouseX, mouseY, 0, items);
    }

    void focusSearch()
    {
        this.list.setFocused(false);
        this.search.setFocused(true);
        this.search.selectAll();
    }

    private void focusList(boolean pickFirst)
    {
        this.search.setFocused(false);
        this.list.setFocused(true);

        if (pickFirst && this.list.selectedIndex() < 0)
        {
            List<Item> items = this.list.items();

            for (int i = 0; i < items.size(); ++i)
            {
                if (items.get(i).kind == Kind.FILE)
                {
                    this.list.setSelectedIndex(i);
                    this.screen.select(items.get(i).entry);
                    return;
                }
            }
        }
    }

    // ===== Раскладка и отрисовка =====

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        Font font = Kit.font();
        int pad = Ui.PAD_IN;
        int inner = Math.max(0, w - pad * 2);
        int row = y + pad;

        this.search.setBounds(x + pad, row, inner, Ui.CTRL_H);
        row += Ui.CTRL_H + pad;

        int dirW = IconButton.size();
        int sortW = Math.min(this.sort.preferredWidth(font), Math.max(0, inner / 2));
        this.direction.setBounds(x + w - pad - dirW, row + (Ui.CTRL_H - dirW) / 2, dirW, dirW);
        this.sort.setBounds(this.direction.x() - Ui.PAD_MICRO - sortW, row, sortW, Ui.CTRL_H);
        this.allFolders.setBounds(x + pad, row, Math.max(0, this.sort.x() - pad - (x + pad)), Ui.CTRL_H);
        row += Ui.CTRL_H + pad;

        this.list.setBounds(x + Ui.PAD_MICRO, row + Ui.PAD_MICRO, Math.max(0, w - Ui.PAD_MICRO * 2),
                Math.max(0, y + h - row - Ui.PAD_MICRO * 2));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.search.render(ctx, mouseX, mouseY);
        this.allFolders.render(ctx, mouseX, mouseY);
        this.sort.render(ctx, mouseX, mouseY);
        this.direction.render(ctx, mouseX, mouseY);

        Paint.rect(ctx, this.x, this.list.y() - Ui.PAD_MICRO, this.w, Ui.BORDER, Ui.C_DIVIDER);
        this.list.render(ctx, mouseX, mouseY);
    }

    private void renderRow(GuiContext ctx, Item item, int index, int x, int y, int w, int h, boolean selected,
            boolean hovered, int mouseX, int mouseY)
    {
        Font font = Kit.font();

        switch (item.kind)
        {
            case UP ->
            {
                Glyphs.draw(ctx, Glyphs.UP, x + Ui.PAD_IN + 1, y + (h - Glyphs.height(Glyphs.UP)) / 2, Ui.C_TEXT_2);
                Paint.midText(ctx, font, Tr.t("files.up"), x + Ui.PAD_IN + Glyphs.width(Glyphs.FOLDER) + Ui.PAD_REL, y, h,
                        hovered ? Ui.C_TEXT : Ui.C_TEXT_2, false);

                if (hovered)
                {
                    Tooltip.offer(item, Tr.t("files.up.tip"));
                }
            }
            case FOLDER -> this.renderFolder(ctx, font, item, x, y, w, h, hovered);
            case FILE ->
            {
                this.renderFile(ctx, font, item, x, y, w, h, selected, hovered);
                float fade = freshFade(item.entry);

                if (fade > 0.0F)
                {
                    drawFresh(ctx, font, x, y, w, h, fade);
                }
            }
            case TRASH -> this.renderTrash(ctx, font, item, x, y, w, h, hovered);
        }
    }

    /**
     * Свежая схема: жёлтая рамка по строке (скругление как у строк) и значок «NEW» — жёлтая плашка-пилюля с белой
     * надписью и искоркой в правом верхнем углу строки. Всё гаснет вместе за {@link #NEW_FADE_MS}.
     */
    private static void drawFresh(GuiContext ctx, Font font, int x, int y, int w, int h, float fade)
    {
        float a = (float) Math.sqrt(fade);
        Paint.round(ctx, x, y, w, h, Ui.R_ROW, Ui.alpha(NEW_COLOR, 0.10F * a));
        Paint.roundBorder(ctx, x, y, w, h, Ui.R_ROW, Ui.alpha(NEW_COLOR, a));
    }

    private static int newBadgeWidth(Font font)
    {
        return 4 + 5 + 2 + font.width("NEW") + 4;
    }

    /** Значок «NEW» от {@code bx}: пилюля {@link Ui#R_PILL}, искорка 5×5 слева от надписи; высота строки {@code lineH}. */
    static void drawNewBadge(GuiContext ctx, Font font, int bx, int lineTop, int lineH, float alpha)
    {
        String text = "NEW";
        int sparkW = 5;
        int bw = newBadgeWidth(font);
        int bh = 11;
        int top = lineTop + (lineH - bh) / 2;
        Paint.round(ctx, bx, top, bw, bh, Math.min(Ui.R_PILL, bh / 2), Ui.alpha(NEW_COLOR, alpha));
        int white = Ui.alpha(0xFFFFFFFF, alpha);
        // Искорка-ромб 5×5
        int sx = bx + 4;
        int sy = top + (bh - 5) / 2;
        Paint.fill(ctx, sx + 2, sy, sx + 3, sy + 5, white);
        Paint.fill(ctx, sx + 1, sy + 1, sx + 4, sy + 4, white);
        Paint.fill(ctx, sx, sy + 2, sx + 5, sy + 3, white);
        Paint.midText(ctx, font, text, sx + sparkW + 2, top, bh, white, false);
    }

    private void renderTrash(GuiContext ctx, Font font, Item item, int x, int y, int w, int h, boolean hovered)
    {
        int iconW = Glyphs.width(Glyphs.FOLDER);
        Glyphs.draw(ctx, Glyphs.TRASH, x + Ui.PAD_IN + (iconW - Glyphs.width(Glyphs.TRASH)) / 2,
                y + (h - Glyphs.height(Glyphs.TRASH)) / 2, Ui.C_TEXT_2);

        int textX = x + Ui.PAD_IN + iconW + Ui.PAD_REL;
        int right = x + w - Ui.PAD_IN;
        String count = Tr.num(this.trashCount);
        Paint.midTextRight(ctx, font, count, right, y, h, Ui.C_TEXT_3, false);
        right -= font.width(count) + Ui.PAD_REL;

        Paint.midText(ctx, font, Paint.fit(font, Tr.t("trash.row"), Math.max(0, right - textX)), textX, y, h,
                hovered ? Ui.C_TEXT : Ui.C_TEXT_2, false);

        if (hovered)
        {
            Tooltip.offer(item, Tr.f("trash.row.tip", SchematicTrash.KEEP_DAYS, SchematicTrash.get().root()));
        }
    }

    private void renderFolder(GuiContext ctx, Font font, Item item, int x, int y, int w, int h, boolean hovered)
    {
        SchematicEntry entry = item.entry;

        if (entry == null)
        {
            return;
        }

        Glyphs.draw(ctx, Glyphs.FOLDER, x + Ui.PAD_IN, y + (h - Glyphs.height(Glyphs.FOLDER)) / 2, Ui.C_TEXT_2);

        int textX = x + Ui.PAD_IN + Glyphs.width(Glyphs.FOLDER) + Ui.PAD_REL;
        int right = x + w - Ui.PAD_IN;
        int count = this.folderCount(entry.path());

        if (count >= 0)
        {
            String text = Tr.num(count);
            Paint.midTextRight(ctx, font, text, right, y, h, Ui.C_TEXT_3, false);
            right -= font.width(text) + Ui.PAD_REL;
        }

        String name = entry.name();
        String shown = Paint.fit(font, name, right - textX);
        Paint.midText(ctx, font, shown, textX, y, h, Ui.C_TEXT, false);

        if (hovered)
        {
            Tooltip.offer(item, count >= 0 ? name + "\n" + Tr.f("files.folder_count.tip", Tr.num(count)) : name);
        }
    }

    private void renderFile(GuiContext ctx, Font font, Item item, int x, int y, int w, int h, boolean selected,
            boolean hovered)
    {
        SchematicEntry entry = item.entry;

        if (entry == null)
        {
            return;
        }

        int tx = x + THUMB_PAD;
        int ty = y + (h - THUMB) / 2;
        Identifier thumb = null;

        if (SchematicsConfig.THUMBNAILS.getBooleanValue())
        {
            try
            {
                thumb = SchematicLibrary.get().thumbnail(entry);
            }
            catch (RuntimeException e)
            {
                // Без миниатюры — нейтральная заглушка
            }
        }

        if (thumb != null)
        {
            Paint.texture(ctx, thumb, tx, ty, THUMB);
        }
        else
        {
            Paint.round(ctx, tx, ty, THUMB, THUMB, Ui.R_PLATE, Ui.C_TRACK);
            Glyphs.cube(ctx, tx + (THUMB - Glyphs.width(Glyphs.CUBE)) / 2, ty + (THUMB - Glyphs.height(Glyphs.CUBE)) / 2,
                    Ui.C_TEXT_3);
        }

        int textX = tx + THUMB + Ui.PAD_REL;
        int room = x + w - Ui.PAD_IN - textX;
        String name = entry.name();
        float fresh = freshFade(entry);
        int badgeW = fresh > 0.0F ? newBadgeWidth(font) + Ui.PAD_IN : 0;
        String shownName = Paint.fit(font, name, room - badgeW);
        int lineH = Ui.TIP_LINE;
        int top = y + (h - lineH * 2 - Ui.PAD_MICRO) / 2;

        Paint.midText(ctx, font, shownName, textX, top, lineH, Ui.C_TEXT, false);

        if (fresh > 0.0F)
        {
            drawNewBadge(ctx, font, textX + font.width(shownName) + Ui.PAD_IN, top, lineH, (float) Math.sqrt(fresh));
        }

        boolean busy = this.screen.busyPlacing() && entry.equals(this.screen.selected());
        String meta = busy ? Tr.t("place.loading") : this.metaLine(entry);
        String shownMeta = Paint.fit(font, meta, room);
        Paint.midText(ctx, font, shownMeta, textX, top + lineH + Ui.PAD_MICRO, lineH,
                busy ? Theme.BLUE : selected ? Ui.C_TEXT_2 : Ui.C_TEXT_3, false);

        if (hovered && (! shownName.equals(name) || ! shownMeta.equals(meta)))
        {
            Tooltip.offer(item, entry.fileName() + "\n" + meta);
        }
    }

    /** «64×32×80 · 12 345 бл. · .litematic · 03.10.2026»; в поиске — ещё папка, если не текущая. */
    private String metaLine(SchematicEntry entry)
    {
        List<String> parts = new ArrayList<>();

        if (! this.query.isEmpty())
        {
            Path parent = entry.path().getParent();

            if (parent != null && ! parent.equals(this.folder))
            {
                String rel = this.relative(parent);
                parts.add(rel.isEmpty() ? Tr.t("files.root") : rel);
            }
        }

        CompletableFuture<SchematicInfo> request = this.screen.info(entry);
        SchematicInfo info = GuiSchematics.done(request);

        if (info != null && info.ok())
        {
            if (info.sizeX() > 0)
            {
                parts.add(Tr.f("files.meta.size", info.sizeX(), info.sizeY(), info.sizeZ()));
            }

            if (info.blocks() >= 0)
            {
                parts.add(Tr.f("files.meta.blocks", Tr.num(info.blocks())));
            }
        }
        else if (info != null || GuiSchematics.failure(request) != null)
        {
            parts.add(Tr.t("files.meta.unreadable"));
        }

        if (entry.format() != null)
        {
            parts.add(entry.format().extension());
        }

        if (entry.modified() > 0L)
        {
            parts.add(Tr.date(entry.modified()));
        }

        return String.join(Ui.TITLE_SEPARATOR, parts);
    }

    @Override
    String footerStatus()
    {
        if (! this.query.isEmpty())
        {
            return this.found == null ? Tr.t("files.searching") : Tr.f("files.status_search", Tr.num(this.fileCount));
        }

        return Tr.f("files.status", Tr.num(this.fileCount), Tr.num(this.folderCount));
    }

    // ===== Ввод =====

    @Override
    @Nullable TextInput focusedInput()
    {
        return this.search.focused() ? this.search : null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.search.mouseClicked(click, doubleClick))
        {
            this.list.setFocused(false);
            return true;
        }

        if (this.allFolders.mouseClicked(click, doubleClick) || this.sort.mouseClicked(click, doubleClick)
                || this.direction.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (this.list.contains(click.x(), click.y()))
        {
            this.search.setFocused(false);
            this.list.setFocused(true);
            return this.list.mouseClicked(click, doubleClick);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.search.mouseDragged(click, dx, dy) || this.list.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        this.direction.mouseReleased(click);
        return this.search.mouseReleased(click) | this.list.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.list.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        int code = key.key();

        if (this.search.focused())
        {
            // Из поиска стрелкой вниз или Tab — сразу к результатам
            if (code == InputConstants.KEY_DOWN || code == InputConstants.KEY_PAGEDOWN || code == InputConstants.KEY_TAB)
            {
                this.focusList(code != InputConstants.KEY_TAB);
                return true;
            }

            return this.search.keyPressed(key);
        }

        if (code == InputConstants.KEY_BACKSPACE)
        {
            if (this.query.isEmpty())
            {
                this.up();
            }
            else
            {
                this.search.setFocused(true);
                this.search.keyPressed(key);
            }

            return true;
        }

        if (code == InputConstants.KEY_TAB)
        {
            this.focusSearch();
            return true;
        }

        // Delete — удалить выбранную строку (файл или пустую папку) через то же подтверждение, что и меню
        if (code == InputConstants.KEY_DELETE)
        {
            Item selected = this.list.selectedItem();

            if (selected != null && selected.entry != null && (selected.kind == Kind.FILE || selected.kind == Kind.FOLDER))
            {
                this.screen.confirmDelete(selected.entry);
            }

            return true;
        }

        boolean navigation = code == InputConstants.KEY_UP || code == InputConstants.KEY_DOWN
                || code == InputConstants.KEY_PAGEUP || code == InputConstants.KEY_PAGEDOWN
                || code == InputConstants.KEY_HOME || code == InputConstants.KEY_END
                || code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER;

        if (navigation && ! this.list.focused())
        {
            this.list.setFocused(true);
        }

        return this.list.keyPressed(key);
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        if (this.search.focused())
        {
            return this.search.charTyped(event);
        }

        // Буква в списке — начало поиска, как в проводнике
        if (event.isAllowedChatCharacter() && Character.isLetterOrDigit(event.codepoint()))
        {
            this.list.setFocused(false);
            this.search.setFocused(true);
            this.search.insert(event.codepointAsString());
            return true;
        }

        return false;
    }

    // ===== Удаление и возврат (0.0.27) =====

    /**
     * Файл ушёл в корзину: список — без него, выбор — на строку, вставшую на его место (иначе на
     * предыдущую), как в проводнике; строк не осталось — выбора нет.
     */
    void deleted(Path path)
    {
        List<Item> before = this.list.items();
        int at = -1;

        for (int i = 0; i < before.size(); ++i)
        {
            if (before.get(i).entry != null && before.get(i).entry.path().toAbsolutePath().normalize().equals(path))
            {
                at = i;
                break;
            }
        }

        SchematicEntry selected = this.screen.selected();
        Item selectedRow = this.list.selectedItem();
        boolean wasSelected = (selected != null && selected.path().toAbsolutePath().normalize().equals(path))
                || (selectedRow != null && selectedRow.entry != null
                && selectedRow.entry.path().toAbsolutePath().normalize().equals(path));

        if (this.found != null)
        {
            this.found = new ArrayList<>(this.found);
            this.found.removeIf(e -> e.path().toAbsolutePath().normalize().equals(path));
        }

        this.folderCounts.clear();
        this.reload();

        if (! wasSelected)
        {
            return;
        }

        List<Item> items = this.list.items();
        int pick = -1;

        for (int i = Math.min(Math.max(at, 0), items.size() - 1); i >= 0; --i)
        {
            Kind kind = items.get(i).kind;

            // Сосед — файл или папка; «..» и «Корзина» выбором не становятся
            if (kind == Kind.FILE || kind == Kind.FOLDER)
            {
                pick = i;
                break;
            }
        }

        this.list.setSelectedIndex(pick);
        Item next = pick >= 0 ? items.get(pick) : null;
        this.screen.select(next != null && next.kind == Kind.FILE ? next.entry : null);
    }

    /** Файл вернулся (отмена удаления, «Восстановить»): перечитать и выбрать, если он здесь. */
    void restored(Path path)
    {
        this.folderCounts.clear();
        this.reload();
        this.restartSearch();

        Path parent = path.getParent();

        if (parent != null && parent.equals(this.folder) && this.query.isEmpty())
        {
            this.selectPath(path);
        }
    }

    /**
     * Файл переименован: список перечитать (в поиске — подменить запись на месте, искать заново незачем) и
     * выбрать его под новым именем; свежесохранённая схема остаётся свежей.
     */
    void renamed(Path from, Path to)
    {
        if (freshPath != null && freshPath.equals(from))
        {
            freshPath = to;
        }

        if (this.found != null)
        {
            SchematicEntry moved = SchematicLibrary.get().entry(to);
            List<SchematicEntry> next = new ArrayList<>();

            for (SchematicEntry e : this.found)
            {
                if (! e.path().toAbsolutePath().normalize().equals(from))
                {
                    next.add(e);
                }
                else if (moved != null)
                {
                    next.add(moved);
                }
            }

            this.found = next;
        }

        this.folderCounts.clear();
        this.reload();
        this.selectPath(to);
    }

    // ===== Перетаскивание файлов в окно =====

    void acceptDropped(List<Path> paths)
    {
        List<Path> schematics = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (Path path : paths)
        {
            if (Files.isRegularFile(path) && SchematicFormat.of(path) != null)
            {
                schematics.add(path);
            }
            else
            {
                Path name = path.getFileName();
                rejected.add(name == null ? path.toString() : name.toString());
            }
        }

        if (schematics.isEmpty())
        {
            this.screen.toast(Tr.t("drop.none"), Ui.C_WARN);
            return;
        }

        if (! rejected.isEmpty())
        {
            this.screen.toast(Tr.f("drop.not_schematic", String.join(", ", rejected)), Ui.C_WARN);
        }

        Path target = this.folder;
        List<Path> work = new ArrayList<>();
        List<String> clashes = new ArrayList<>();

        for (Path source : schematics)
        {
            Path destination = target.resolve(source.getFileName().toString());

            // Тот же файл бросили в его же папку — копировать нечего
            if (sameFile(source, destination))
            {
                continue;
            }

            work.add(source);

            if (Files.exists(destination))
            {
                clashes.add(source.getFileName().toString());
            }
        }

        if (work.isEmpty())
        {
            return;
        }

        if (clashes.isEmpty())
        {
            this.copy(work, target, false);
            return;
        }

        Modal modal = new Modal(this.screen.layers, Tr.t("drop.replace.title"), Tr.t("cancel"));
        modal.text(Tr.f("drop.replace.text", this.folderName(target), String.join(", ", clashes)));
        modal.button(new Button(Tr.t("cancel"), Button.Style.SECONDARY, modal::cancel));
        modal.button(new Button(Tr.t("drop.replace.skip"), Button.Style.SECONDARY, () ->
        {
            modal.dismiss();
            this.copy(work, target, false);
        }));
        Button replace = new Button(Tr.t("drop.replace.yes"), Button.Style.PRIMARY, () ->
        {
            modal.dismiss();
            this.copy(work, target, true);
        }).accent(Ui.C_WARN);
        modal.button(replace);
        modal.defaultButton(replace);
        modal.width(300).open();
    }

    private String folderName(Path dir)
    {
        String rel = this.relative(dir);
        return rel.isEmpty() ? Tr.t("files.root") : rel;
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

    /** Копия в фоне (файлы бывают большими), итог — тостом на клиентском потоке. */
    private void copy(List<Path> sources, Path target, boolean replace)
    {
        Minecraft mc = Minecraft.getInstance();

        CompletableFuture.supplyAsync(() ->
        {
            int copied = 0;
            List<String> failed = new ArrayList<>();
            Path first = null;

            for (Path source : sources)
            {
                Path destination = target.resolve(source.getFileName().toString());

                if (! replace && Files.exists(destination))
                {
                    continue;
                }

                try
                {
                    if (replace)
                    {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    else
                    {
                        Files.copy(source, destination);
                    }

                    ++copied;

                    if (first == null)
                    {
                        first = destination;
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    failed.add(Tr.f("drop.failed_item", source.getFileName(), Tr.reason(e)));
                }
            }

            return new CopyResult(copied, failed, first);
        }, Util.ioPool()).whenCompleteAsync((result, error) ->
        {
            if (error != null || result == null)
            {
                this.screen.toast(Tr.f("drop.failed", error == null ? "?" : Tr.reason(error)), Ui.C_DANGER);
                return;
            }

            this.folderCounts.clear();
            this.reload();

            if (result.first != null)
            {
                this.selectPath(result.first);
            }

            if (result.copied > 0)
            {
                this.screen.toast(Tr.f("drop.copied", result.copied), Theme.GREEN,
                        new Toasts.Action(Tr.t("menu.show_in_folder"), () -> this.screen.openInSystem(target)));
            }

            if (! result.failed.isEmpty())
            {
                this.screen.toast(Tr.f("drop.failed", String.join("; ", result.failed)), Ui.C_DANGER);
            }
        }, mc);
    }

    void selectPath(Path path)
    {
        List<Item> items = this.list.items();

        for (int i = 0; i < items.size(); ++i)
        {
            Item item = items.get(i);

            if (item.entry != null && item.kind == Kind.FILE && item.entry.path().equals(path))
            {
                this.list.setSelectedIndex(i);
                this.screen.select(item.entry);
                return;
            }
        }
    }

    @Override
    void release()
    {
        // Экран закрыли — свежая схема больше не висит сверху (подсветка, если ещё не погасла, остаётся)
        freshPinned = false;

        try
        {
            SchematicLibrary.get().releaseTextures();
        }
        catch (RuntimeException e)
        {
            // Освобождение — по возможности; при следующем открытии миниатюры запросятся заново
        }
    }
}
