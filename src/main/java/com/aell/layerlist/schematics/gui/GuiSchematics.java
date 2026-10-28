package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.config.LayerListSettings;
import com.aell.layerlist.schematics.SchematicsModule;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Anim;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.SettingsScreen;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Splitter;
import com.aell.layerlist.ui.kit.TextFocus;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Tooltip;
import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Экран «Схемы» (M21) — на всё окно, без паузы. Каркас — как у экрана настроек (токены {@link Ui}):
 * шапка {@link Ui#HEADER_H} (иконка мода, «Material HUD · Схемы», хлебные крошки папки, кнопки
 * «Открыть папку», «Обновить», шестерёнка), строка подвкладок {@link Ui#TABS_H} (Библиотека ·
 * Размещения · Материалы), тело, подвал {@link Ui#FOOTER_H} (возврат слева, действия справа).
 *
 * <p>«Библиотека»: файлы | 3D-превью | карточка, границы перетаскиваются (ширины — в
 * {@link SchematicsState}); уже {@link #WIDE} карточка уходит под превью, уже {@link #NARROW} — одна
 * колонка с переключателем «Файлы / Просмотр». Всплывающее (меню, окна, тосты, подсказки)
 * рисуется последним, в {@link #drawButtonHoverTexts}, и первым получает ввод.
 *
 * <p>Возврат — по стеку {@link Nav}: откуда открыли (вкладка настроек, кнопка шапки, главное меню
 * Litematica), туда и вернёмся; из хоткея в игре — закрыть. Ошибки показываются тостами на месте,
 * в чат ничего не пишется.
 */
@Environment(EnvType.CLIENT)
public final class GuiSchematics extends GuiBase
{
    /** Подвкладки экрана. */
    enum Tab
    {
        LIBRARY,
        PLACEMENTS,
        /** С 1.0.12 — свой редактор выделения Litematica («Редактор региона»). */
        SELECTION,
        MATERIALS,
        /** С 0.0.27 — вкладкой, а не окном поверх «Библиотеки». */
        TRASH
    }

    /** От этой ширины — три колонки. */
    private static final int WIDE = 640;
    /** Уже — одна колонка. */
    private static final int NARROW = 420;

    private static final int FILES_MIN = 140;
    private static final int FILES_DEFAULT_MIN = 180;
    private static final int FILES_DEFAULT_MAX = 300;
    private static final int INFO_MIN = 180;
    private static final int INFO_DEFAULT_MIN = 220;
    private static final int INFO_DEFAULT_MAX = 280;
    private static final int CENTER_MIN = 160;
    private static final int PREVIEW_MIN_H = 120;
    private static final int CRUMBS_MIN = 48;
    private static final int FOOTER_BUTTON_MIN = 56;

    private static final String SPLIT_FILES = "files";
    private static final String SPLIT_INFO = "info";

    /** Подвкладка запоминается на время игры — как вкладки экрана настроек. */
    private static Tab lastTab = Tab.LIBRARY;
    /** Последнее размещение, созданное отсюда: «Разместить…» ставит новое на его место. */
    private static WeakReference<SchematicPlacement> lastPlaced = new WeakReference<>(null);

    final Layers layers = new Layers();
    final Toasts toasts = new Toasts();

    private final Anim.Pool hoverAnim = new Anim.Pool(Ui.ANIM_HOVER_MS);
    private final Map<SchematicEntry, CompletableFuture<SchematicInfo>> infoCache = new HashMap<>();

    private final FileListPane files;
    private final PreviewPane preview;
    private final InfoPane info;
    private final PlacementsPane placements;
    private final SelectionPane selection;
    private final MaterialsPane materials;
    private final TrashPane trash;
    private final Splitter filesSplit;
    private final Splitter infoSplit;

    private final Button back;
    private final Button openFolder;
    private final Button refresh;
    private final Button settings;
    private final Button showFiles;
    private final Button showView;
    private final Button footerPlace;
    private final Button footerMaterials;
    /** «Разместить и поделиться» (Syncmatica) — в подвале узкого окна, где карточка с кнопками ниже края. */
    private final Button footerShare;

    private final List<TabBox> tabBoxes = new ArrayList<>();
    private final List<Crumb> crumbs = new ArrayList<>();
    private final List<Button> footerButtons = new ArrayList<>();

    private Tab tab = lastTab;
    /** Что удалила чистка корзины при открытии: показать, когда панели готовы ({@link #purgeTrash}). */
    private @Nullable String purgeNote;
    private boolean narrowShowsView;
    private int mode = 3;
    private int footerH = Ui.FOOTER_H;
    private int bodyY;
    private int bodyH;
    private String headerTitle = "";
    private String footerStatus = "";
    private @Nullable String footerStatusTip;
    private int footerStatusX;
    private int footerStatusW;
    private int filesBeforeCollapse = -1;
    private int infoBeforeCollapse = -1;

    private @Nullable SchematicEntry selected;
    private @Nullable CompletableFuture<SchematicInfo> selectedInfo;
    private @Nullable CompletableFuture<VoxelSnapshot> snapshot;
    private boolean snapshotApplied;
    private @Nullable SchematicEntry placing;

    private record TabBox(Tab tab, String label, int x, int w) {}

    private record Crumb(String label, @Nullable Path path, int x, int w) {}

    private GuiSchematics()
    {
        this.title = Strings.plain("layerlist.schematics.title");
        // Экран снова открыт — HUD «Смотреть на мир» больше не нужен
        SelectionHud.leave();
        PlacementHud.leave();

        // Корень перечитывается раз на открытие: метод Litematica создаёт папку, а игрок мог сменить её
        try
        {
            SchematicLibrary.get().refreshRoot();
        }
        catch (RuntimeException e)
        {
            // Без корня список просто покажет ошибку папки
        }

        // Корзина: старше 30 дней удаляется насовсем при каждом открытии экрана (и раз при старте клиента)
        this.purgeTrash();

        this.files = new FileListPane(this);
        this.preview = new PreviewPane(this);
        this.info = new InfoPane(this);
        this.placements = new PlacementsPane(this);
        this.selection = new SelectionPane(this);
        this.materials = new MaterialsPane(this);
        this.trash = new TrashPane(this);

        // Итог чистки — строкой на вкладке «Корзина», если экран открылся на ней: тост лёг бы на кнопки её строк
        if (this.purgeNote != null)
        {
            if (this.tab == Tab.TRASH)
            {
                this.trash.notice(this.purgeNote);
            }
            else
            {
                this.toasts.show(this.purgeNote, Ui.C_TEXT_2);
            }

            this.purgeNote = null;
        }

        this.filesSplit = new Splitter(this::moveFilesSplit).onDoubleClick(this::toggleFiles)
                .tooltip(Tr.t("splitter.tip"));
        this.infoSplit = new Splitter(this::moveInfoSplit).onDoubleClick(this::toggleInfo)
                .tooltip(Tr.t("splitter.tip"));

        this.back = new Button("", Button.Style.SECONDARY, Nav::back);
        this.openFolder = new Button(Tr.t("header.open_folder"), Button.Style.SECONDARY, this::openCurrentFolder)
                .glyph(Glyphs.OPEN_FOLDER);
        this.refresh = new Button(Tr.t("header.refresh"), Button.Style.SECONDARY, this::refresh).glyph(Glyphs.REFRESH);
        // С 0.0.27 — надписью «Настройки», а не шестерёнкой (просьба пользователя); значок — только в самом узком окне
        this.settings = new Button(Tr.t("header.settings_label"), Button.Style.SECONDARY, () -> SettingsDialog.open(this))
                .tooltip(Tr.t("header.settings"));
        this.showFiles = new Button(Tr.t("narrow.files"), Button.Style.GHOST, () -> this.setNarrowView(false));
        this.showView = new Button(Tr.t("narrow.view"), Button.Style.GHOST, () -> this.setNarrowView(true));
        this.footerPlace = new Button(Tr.t("info.place_here"), Button.Style.PRIMARY, () -> this.placeSelected(true))
                .accent(Theme.GREEN).tooltip(Tr.t("info.place_here.tip"));
        this.footerShare = new Button(Tr.t("sync.place_share"), Button.Style.PRIMARY, () ->
        {
            if (this.selected != null)
            {
                this.placeAndShare(this.selected);
            }
        }).accent(PlacementsPane.SYNC).tooltip(Tr.t("sync.place_share.tip"));
        this.footerMaterials = new Button(Tr.t("info.materials"), Button.Style.SECONDARY,
                () -> this.showMaterials(this.selected, null)).tooltip(Tr.t("info.materials.tip"));
    }

    /** Экран без родителя: для {@code Model.Tab.opens}, где родителя кладёт в стек сам экран настроек. */
    public static GuiSchematics create()
    {
        return new GuiSchematics();
    }

    /** Сразу на вкладке «Выделение» — вместо «Редактора региона» Litematica ({@code OwnScreens}). */
    public static void openSelection(@Nullable Screen parent)
    {
        lastTab = Tab.SELECTION;
        open(parent);
    }

    /** Сразу на «Размещениях» — вместо «Размещённых голограмм» и «Загруженных схем» Litematica. */
    public static void openPlacements(@Nullable Screen parent)
    {
        lastTab = Tab.PLACEMENTS;
        open(parent);
    }

    /** Сразу на «Библиотеке» — вместо «Загрузить схему» Litematica. */
    public static void openLibrary(@Nullable Screen parent)
    {
        lastTab = Tab.LIBRARY;
        open(parent);
    }

    public static void open(@Nullable Screen parent)
    {
        GuiSchematics screen = create();
        screen.setParent(parent);
        Nav.open(screen, parent);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    // ===== Раскладка =====

    @Override
    public void initGui()
    {
        super.initGui();

        this.layers.setScreen(this.width, this.height);
        this.layoutBody();
    }

    private void layoutBody()
    {
        this.footerH = this.height < Ui.WINDOW_SHORT ? Ui.FOOTER_H_TIGHT : Ui.FOOTER_H;
        this.bodyY = Ui.HEADER_H + Ui.TABS_H;
        this.bodyH = Math.max(0, this.height - this.bodyY - this.footerH);
        this.mode = this.width >= WIDE ? 3 : this.width >= NARROW ? 2 : 1;

        this.filesSplit.setVisible(false);
        this.infoSplit.setVisible(false);

        switch (this.tab)
        {
            case LIBRARY -> this.layoutLibrary();
            case PLACEMENTS -> this.placements.setBounds(0, this.bodyY, this.width, this.bodyH);
            case SELECTION -> this.selection.setBounds(0, this.bodyY, this.width, this.bodyH);
            case MATERIALS -> this.materials.setBounds(0, this.bodyY, this.width, this.bodyH);
            case TRASH -> this.trash.setBounds(0, this.bodyY, this.width, this.bodyH);
        }

        this.toasts.setAnchor(this.width - Ui.OUTER, this.height - this.footerH - Ui.PAD_IN, this.width);
    }

    private void layoutLibrary()
    {
        this.info.setFooterHasMain(this.mode < 3);

        if (this.mode == 1)
        {
            this.files.setVisible(! this.narrowShowsView);
            this.preview.setVisible(this.narrowShowsView);
            this.info.setVisible(this.narrowShowsView);
            this.files.setBounds(0, this.bodyY, this.width, this.bodyH);
            this.stackPreviewAndInfo(0, this.width);
            return;
        }

        this.files.setVisible(true);
        this.preview.setVisible(true);
        this.info.setVisible(true);

        int filesW = this.preferredFiles();
        int infoW = this.mode == 3 ? this.preferredInfo() : 0;
        int room = this.width - Ui.BORDER * 2 - CENTER_MIN;

        // Не влезло: сначала карточка до минимума, потом файлы, потом карточка дальше
        if (filesW + infoW > room)
        {
            int over = filesW + infoW - room;
            int cut = Math.min(over, Math.max(0, infoW - INFO_MIN));
            infoW -= cut;
            over -= cut;
            cut = Math.min(over, Math.max(0, filesW - FILES_MIN));
            filesW -= cut;
            over -= cut;
            infoW = Math.max(0, infoW - over);
        }

        this.files.setVisible(filesW > 0);
        this.files.setBounds(0, this.bodyY, filesW, this.bodyH);
        this.filesSplit.setVisible(true);
        this.filesSplit.place(filesW, this.bodyY, this.bodyH, 0,
                Math.max(0, this.width - Ui.BORDER * 2 - CENTER_MIN - infoW));

        int centerX = filesW + Ui.BORDER;

        if (this.mode == 2)
        {
            this.stackPreviewAndInfo(centerX, this.width - centerX);
            return;
        }

        int infoX = this.width - infoW;
        this.preview.setBounds(centerX, this.bodyY, Math.max(0, infoX - Ui.BORDER - centerX), this.bodyH);
        this.info.setVisible(infoW > 0);
        this.info.setBounds(infoX, this.bodyY, infoW, this.bodyH);
        this.infoSplit.setVisible(true);
        this.infoSplit.place(infoX - Ui.BORDER, this.bodyY, this.bodyH, filesW + Ui.BORDER + CENTER_MIN,
                this.width - Ui.BORDER);
    }

    /** Узкое окно: превью сверху, карточка под ним. */
    private void stackPreviewAndInfo(int x, int w)
    {
        int previewH = Math.max(Math.min(PREVIEW_MIN_H, this.bodyH), this.bodyH * 55 / 100);
        this.preview.setBounds(x, this.bodyY, w, previewH);
        this.info.setBounds(x, this.bodyY + previewH + Ui.BORDER, w, Math.max(0, this.bodyH - previewH - Ui.BORDER));
    }

    private int preferredFiles()
    {
        int stored = SchematicsState.get().splitter(SPLIT_FILES, -1);

        if (stored == 0)
        {
            return 0;
        }

        int fallback = Ui.clamp(this.width * 30 / 100, FILES_DEFAULT_MIN, FILES_DEFAULT_MAX);
        return stored < 0 ? fallback : Math.max(FILES_MIN, stored);
    }

    private int preferredInfo()
    {
        int stored = SchematicsState.get().splitter(SPLIT_INFO, -1);

        if (stored == 0)
        {
            return 0;
        }

        int fallback = Ui.clamp(this.width * 25 / 100, INFO_DEFAULT_MIN, INFO_DEFAULT_MAX);
        return stored < 0 ? fallback : Math.max(INFO_MIN, stored);
    }

    /** Перетаскивание: меньше минимума — прилипает к минимуму, совсем узко — панель сворачивается. */
    private static int snap(int value, int min)
    {
        return value >= min ? value : value < min / 2 ? 0 : min;
    }

    private void moveFilesSplit(int position)
    {
        SchematicsState.get().setSplitter(SPLIT_FILES, snap(position, FILES_MIN));
        this.layoutBody();
    }

    private void moveInfoSplit(int position)
    {
        SchematicsState.get().setSplitter(SPLIT_INFO, snap(this.width - position - Ui.BORDER, INFO_MIN));
        this.layoutBody();
    }

    private void toggleFiles()
    {
        int now = this.files.visible() ? this.files.w() : 0;

        if (now > 0)
        {
            this.filesBeforeCollapse = now;
            SchematicsState.get().setSplitter(SPLIT_FILES, 0);
        }
        else
        {
            SchematicsState.get().setSplitter(SPLIT_FILES, this.filesBeforeCollapse);
        }

        this.layoutBody();
    }

    private void toggleInfo()
    {
        int now = this.info.visible() ? this.info.w() : 0;

        if (now > 0)
        {
            this.infoBeforeCollapse = now;
            SchematicsState.get().setSplitter(SPLIT_INFO, 0);
        }
        else
        {
            SchematicsState.get().setSplitter(SPLIT_INFO, this.infoBeforeCollapse);
        }

        this.layoutBody();
    }

    private void setNarrowView(boolean view)
    {
        this.narrowShowsView = view;
        this.layoutBody();
    }

    void setTab(Tab next)
    {
        if (next == this.tab)
        {
            return;
        }

        this.blurAll();
        this.tab = next;
        lastTab = next;

        if (next == Tab.PLACEMENTS)
        {
            this.placements.reload();
        }
        else if (next == Tab.TRASH)
        {
            // Тосты «… в корзине · Отменить» здесь лишние (есть «Восстановить») и при GUI 3 ложатся на кнопки строк
            this.toasts.clear();
            this.trash.entered();
        }

        this.layoutBody();
    }

    Tab tab()
    {
        return this.tab;
    }

    private void layoutHeader()
    {
        Font font = this.font;
        int iconW = Ui.HEADER_ICON + Ui.HEADER_ICON_GAP;
        String full = this.title;
        String shortTitle = Strings.plain("layerlist.gui.tab_schematics");
        int right = this.width - Ui.OUTER;
        int crumbsMin = this.tab == Tab.LIBRARY ? CRUMBS_MIN : 0;

        // Что сжимается первым: подписи «Открыть папку» и «Обновить» (значки остаются), потом «Настройки» — в шестерёнку
        String[][] labels = {
                { Tr.t("header.open_folder"), Tr.t("header.refresh"), Tr.t("header.settings_label") },
                { "", "", Tr.t("header.settings_label") },
                { "", "", "" } };
        String[] titles = { full, shortTitle };
        String chosenTitle = shortTitle;
        boolean withLabels = false;

        search:
        for (String[] variant : labels)
        {
            for (String candidate : titles)
            {
                this.openFolder.label(variant[0]);
                this.refresh.label(variant[1]);
                this.settings.label(variant[2]);
                this.settings.glyph(variant[2].isEmpty() ? Glyphs.GEAR : null);
                int buttons = this.headerButtonsWidth();
                int need = Ui.OUTER + iconW + font.width(candidate) + Ui.PAD_GROUP + crumbsMin + buttons;

                if (need <= right)
                {
                    chosenTitle = candidate;
                    withLabels = ! variant[0].isEmpty();
                    break search;
                }
            }
        }

        this.headerTitle = chosenTitle;
        this.openFolder.tooltip(withLabels ? Tr.t("header.open_folder.tip")
                : Tr.f("header.icon_tip", Tr.t("header.open_folder"), Tr.t("header.open_folder.tip")));
        this.refresh.tooltip(withLabels ? Tr.t("header.refresh.tip")
                : Tr.f("header.icon_tip", Tr.t("header.refresh"), Tr.t("header.refresh.tip")));

        int y = (Ui.HEADER_H - Ui.HEAD_BTN_H) / 2;
        int x = right;

        for (Button button : List.of(this.settings, this.refresh, this.openFolder))
        {
            int w = button.preferredWidth(font);
            x -= w;
            button.setBounds(x, y, w, Ui.HEAD_BTN_H);
            x -= Ui.HEAD_BTN_GAP;
        }

        int crumbsX = Ui.OUTER + iconW + font.width(this.headerTitle) + Ui.PAD_GROUP;
        this.layoutCrumbs(crumbsX, x - Ui.PAD_GROUP - crumbsX);
    }

    private int headerButtonsWidth()
    {
        return this.settings.preferredWidth(this.font) + this.refresh.preferredWidth(this.font)
                + this.openFolder.preferredWidth(this.font) + Ui.HEAD_BTN_GAP * 2;
    }

    /** Крошки папки: корень / … / текущая; не влезают — левые сворачиваются в «…». */
    private void layoutCrumbs(int x, int room)
    {
        this.crumbs.clear();

        if (this.tab != Tab.LIBRARY || room <= 0)
        {
            return;
        }

        Path root = SchematicLibrary.get().root();
        Path folder = this.files.folder();
        List<Path> chain = new ArrayList<>();

        for (Path p = folder; p != null && p.startsWith(root); p = p.getParent())
        {
            chain.addFirst(p);

            if (p.equals(root))
            {
                break;
            }
        }

        if (chain.isEmpty())
        {
            chain.add(root);
        }

        String separator = Tr.t("crumbs.separator");
        int sepW = this.font.width(separator);
        int used = 0;
        int first = chain.size();

        for (int i = chain.size() - 1; i >= 0; --i)
        {
            int w = this.font.width(crumbName(chain.get(i))) + (i < chain.size() - 1 ? sepW : 0);
            int ellipsis = i > 0 ? this.font.width(Tr.t("crumbs.more")) + sepW : 0;

            if (used + w + ellipsis > room && i < chain.size() - 1)
            {
                break;
            }

            used += w;
            first = i;
        }

        int cx = x;

        if (first > 0)
        {
            String more = Tr.t("crumbs.more");
            this.crumbs.add(new Crumb(more, chain.get(first - 1), cx, this.font.width(more)));
            cx += this.font.width(more) + sepW;
        }

        for (int i = first; i < chain.size(); ++i)
        {
            String name = crumbName(chain.get(i));
            String shown = Paint.fit(this.font, name, Math.max(0, x + room - cx));
            int w = this.font.width(shown);
            this.crumbs.add(new Crumb(shown, i == chain.size() - 1 ? null : chain.get(i), cx, w));
            cx += w + sepW;
        }
    }

    private static String crumbName(Path path)
    {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    private void layoutTabs()
    {
        this.tabBoxes.clear();

        int count = this.placements.count();
        int trashed = SchematicTrash.get().count();
        String[] labels = {
                Tr.t("tab.library"),
                count > 0 ? Tr.f("tab.placements_count", count) : Tr.t("tab.placements"),
                Tr.t("tab.selection"),
                Tr.t("tab.materials"),
                trashed > 0 ? Tr.f("tab.trash_count", trashed) : Tr.t("tab.trash") };
        Tab[] tabs = Tab.values();

        int right = this.width - Ui.OUTER;

        if (this.mode == 1 && this.tab == Tab.LIBRARY)
        {
            int segY = Ui.HEADER_H + Ui.PAD_MICRO;
            int segH = Ui.TABS_H - Ui.PAD_IN;
            int viewW = this.showView.preferredWidth(this.font);
            int filesW = this.showFiles.preferredWidth(this.font);
            this.showView.setBounds(right - viewW, segY, viewW, segH);
            this.showFiles.setBounds(right - viewW - filesW, segY, filesW, segH);
            this.showFiles.selected(! this.narrowShowsView);
            this.showView.selected(this.narrowShowsView);
            right -= viewW + filesW + Ui.PAD_IN;
        }

        int pad = Ui.TAB_PAD_X;
        int total = 0;

        for (String label : labels)
        {
            total += this.font.width(label);
        }

        while (pad > Ui.TAB_PAD_X_MIN && Ui.OUTER + total + pad * 2 * labels.length > right)
        {
            --pad;
        }

        int budget = (right - Ui.OUTER) / labels.length - pad * 2;
        int x = Ui.OUTER;

        for (int i = 0; i < labels.length; ++i)
        {
            String shown = Ui.OUTER + total + pad * 2 * labels.length > right
                    ? Paint.fit(this.font, labels[i], Math.max(0, budget))
                    : labels[i];
            int w = this.font.width(shown) + pad * 2;
            this.tabBoxes.add(new TabBox(tabs[i], shown, x, w));
            x += w;
        }
    }

    private void layoutFooter()
    {
        int y = this.height - this.footerH + (this.footerH - Ui.BUTTON_H) / 2;
        Screen parent = Nav.peek();
        String backLabel = parent == null ? Strings.t("close")
                : Strings.t("back", parent instanceof SettingsScreen ? Strings.t("settings") : parent.getTitle().getString());

        this.back.label(backLabel);
        int backW = Math.min(this.back.preferredWidth(this.font), Math.max(0, this.width / 3));
        this.back.setBounds(Ui.OUTER, y, backW, Ui.BUTTON_H);

        this.footerButtons.clear();
        this.footerStatus = "";
        this.footerStatusTip = null;

        switch (this.tab)
        {
            case LIBRARY ->
            {
                this.footerStatus = this.files.footerStatus();

                // В три колонки те же кнопки есть в карточке; в узком окне карточка может быть не видна
                if (this.mode < 3 && this.selected != null)
                {
                    boolean busy = this.placing != null;
                    this.footerPlace.label(busy ? Tr.t("place.loading") : Tr.t("info.place_here"));
                    this.footerPlace.setEnabled(! busy);
                    boolean zoned = this.info.zonesSplit();
                    this.footerPlace.accent(zoned ? InfoPane.ZONED : Theme.GREEN).dashed(zoned);
                    this.footerButtons.add(this.footerPlace);
                    this.footerButtons.add(this.footerMaterials);

                    if (com.aell.layerlist.compat.Syncmatica.installed())
                    {
                        boolean live = com.aell.layerlist.compat.Syncmatica.active();
                        this.footerShare.setEnabled(! busy && live);
                        this.footerShare.tooltip(Tr.t(live ? "sync.place_share.tip" : "sync.error.inactive"));
                        this.footerButtons.add(this.footerShare);
                    }
                }
            }
            case PLACEMENTS ->
            {
                this.footerStatus = this.placements.footerStatus();
                this.footerButtons.addAll(this.placements.footerActions());
            }
            case SELECTION ->
            {
                this.footerStatus = this.selection.footerStatus();
                this.footerButtons.addAll(this.selection.footerActions());
            }
            case MATERIALS ->
            {
                this.footerStatus = this.materials.footerStatus();
                this.footerStatusTip = this.materials.footerStatusTip();
                this.footerButtons.addAll(this.materials.footerActions());
            }
            case TRASH ->
            {
                this.footerStatus = this.trash.footerStatus();
                this.footerButtons.addAll(this.trash.footerActions());
            }
        }

        int right = this.width - Ui.OUTER;
        int leftLimit = Ui.OUTER + backW + Ui.PAD_REL;

        for (Button button : this.footerButtons)
        {
            int w = footerButtonWidth(this.font, button);

            if (right - w < leftLimit)
            {
                button.setVisible(false);
                continue;
            }

            button.setVisible(true);
            right -= w;
            button.setBounds(right, y, w, Ui.BUTTON_H);
            right -= Ui.PAD_REL;
        }

        this.footerStatusX = leftLimit + Ui.PAD_REL;
        this.footerStatusW = Math.max(0, right - Ui.PAD_REL - this.footerStatusX);
    }

    /**
     * Ширина кнопки подвала: не уже {@link #FOOTER_BUTTON_MIN} (короткая подпись вроде «CSV» всё равно выглядит
     * кнопкой), а кнопка-значок без подписи — по своему размеру, как шестерёнка шапки (0.0.27, «Папка с выгрузками» в
     * тесном подвале «Материалов»). {@code MaterialsPane} оценивает место по этому же правилу.
     */
    static int footerButtonWidth(Font font, Button button)
    {
        int preferred = button.preferredWidth(font);

        return button.label().isEmpty() ? preferred : Math.max(FOOTER_BUTTON_MIN, preferred);
    }

    // ===== Отрисовка =====

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY)
    {
        Tooltip.beginFrame();
        // «Смотреть на мир» на «Выделении»: фон тает до нуля — дальше игра, и переход в неё без скачка
        float world = this.worldView();

        // Ванильный стиль: фон меню Minecraft; в «Смотреть на мир» фон просто убирается
        if (Style.vanilla())
        {
            if (world < 1.0F)
            {
                Vanilla.screen(ctx, this.width, this.height);
                // Тело окна — на фоне списка Minecraft: на голом размытом мире серый текст не читается
                int bodyTop = Ui.HEADER_H + Ui.TABS_H;
                Vanilla.listBackground(ctx, 0, bodyTop, this.width, this.height - this.footerH - bodyTop);
            }

            return;
        }

        Paint.rect(ctx, 0, 0, this.width, this.height, Ui.alpha(Ui.C_PANEL, 1.0F - world));
    }

    /** Доля выезда «Смотреть на мир» вкладки «Выделение» (0 — обычный экран). */
    private float worldView()
    {
        return this.tab == Tab.SELECTION ? this.selection.worldView() : 0.0F;
    }

    /** Панели «Выделения» едут в мир или обратно (или курсор у карточки): шапка, вкладки и подвал ввод не принимают. */
    private boolean worldMoving()
    {
        return this.tab == Tab.SELECTION && this.selection.chromeHidden();
    }

    /** Курсор у карточки координат «Смотреть на мир»: весь ввод — карточке, щелчок мимо — снова игра. */
    private boolean cursorAtCard()
    {
        return this.tab == Tab.SELECTION && this.selection.parked();
    }

    /** Опцию «Составное выделение» переключили в настройках — режим Litematica сразу. */
    void syncSelectionMode()
    {
        this.selection.syncMode();
    }

    /** Доехали до мира: экран закрывается, координаты остаются HUD-ом, Esc в игре вернёт сюда. */
    @Override
    public void tick()
    {
        super.tick();

        if (this.tab == Tab.SELECTION && this.selection.reachedWorld() && GuiUtils.getCurrentScreen() == this)
        {
            SelectionHud.enter(Nav.peek());
            Nav.clear();
            Minecraft.getInstance().gui.setScreen(null);
        }
    }

    /** Клавиша курсора в игре из «Смотреть на мир»: экран с одной живой карточкой на месте HUD. */
    static void openCursor(@Nullable Screen parent)
    {
        lastTab = Tab.SELECTION;
        GuiSchematics screen = create();
        screen.selection.park();
        screen.setParent(parent);
        Nav.open(screen, parent);
    }

    /** Esc в игре из «Смотреть на мир»: «Выделение» снова на экране, панели въезжают на места. */
    static void returnFromWorld(@Nullable Screen parent)
    {
        lastTab = Tab.SELECTION;
        GuiSchematics screen = create();
        screen.selection.arrive();
        screen.setParent(parent);
        Nav.open(screen, parent);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        this.tickSelection();
        this.tickSync();
        this.files.tick();
        this.placements.tick();
        this.selection.tick();
        this.materials.tick();
        this.trash.tick();

        this.layoutHeader();
        this.layoutTabs();
        this.layoutFooter();

        boolean covered = this.layers.covers(mouseX, mouseY) || this.toasts.contains(mouseX, mouseY);
        int mx = covered ? -1 : mouseX;
        int my = covered ? -1 : mouseY;

        // «Смотреть на мир»: шапка с вкладками уезжает вверх, подвал — вниз
        float world = this.worldView();
        boolean chromeLive = ! this.worldMoving();
        int cx = chromeLive ? mx : -1;
        int cy = chromeLive ? my : -1;

        if (world < 1.0F)
        {
            ctx.pose().pushMatrix();
            // На то же расстояние, что и верхняя строка «Выделения»: едут одним блоком и не наезжают друг на друга
            ctx.pose().translate(0, Math.round(-world * this.selection.upDistance()));
            this.drawHeader(ctx, cx, cy);
            this.drawTabs(ctx, cx, cy);
            ctx.pose().popMatrix();
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.visible())
            {
                pane.render(ctx, mx, my);
            }
        }

        this.drawDividers(ctx);
        this.filesSplit.render(ctx, mx, my);
        this.infoSplit.render(ctx, mx, my);

        if (world < 1.0F)
        {
            ctx.pose().pushMatrix();
            ctx.pose().translate(0, Math.round(world * this.footerH));
            this.drawFooter(ctx, cx, cy);
            ctx.pose().popMatrix();
        }
        SchematicLibrary.get().frameDrawn();

        // Модальное окно забирает весь ввод: поле под ним фокус теряет, как в ванили при новом экране
        // (иначе оба поля каждый кадр перехватывали бы ввод текста SDL друг у друга)
        if (this.layers.blocking())
        {
            this.blurAll();
        }

        TextFocus.hold(this, this.typeAhead());
    }

    /**
     * Буква в списке файлов или в таблице материалов начинает поиск ({@code charTyped} панелей). В 26.3
     * буквы приходят только при включённом вводе текста SDL — поэтому, пока такой список на экране
     * и ни одно поле не в фокусе, ввод держит сам экран.
     */
    private boolean typeAhead()
    {
        if (this.layers.blocking() || this.focusedPaneInput() != null)
        {
            return false;
        }

        return switch (this.tab)
        {
            case LIBRARY -> this.files.visible();
            case MATERIALS -> this.materials.visible();
            case PLACEMENTS, SELECTION, TRASH -> false;
        };
    }

    private @Nullable TextInput focusedPaneInput()
    {
        for (Pane pane : List.of(this.files, this.preview, this.info, this.placements, this.selection, this.materials, this.trash))
        {
            TextInput input = pane.focusedInput();

            if (input != null)
            {
                return input;
            }
        }

        return null;
    }

    private void drawHeader(GuiContext ctx, int mouseX, int mouseY)
    {
        // Ванильный стиль: шапка без заливки, под ней разделитель Minecraft
        if (Style.vanilla())
        {
            Vanilla.headerSeparator(ctx, 0, Ui.HEADER_H - 2, this.width);
        }
        else
        {
            Paint.rect(ctx, 0, 0, this.width, Ui.HEADER_H, Ui.C_CHROME);
            Paint.rect(ctx, 0, Ui.HEADER_H - Ui.BORDER, this.width, Ui.BORDER, Ui.C_DIVIDER);
        }

        Paint.texture(ctx, LayerListSettings.ICON, Ui.OUTER, (Ui.HEADER_H - Ui.HEADER_ICON) / 2, Ui.HEADER_ICON);

        int x = Ui.OUTER + Ui.HEADER_ICON + Ui.HEADER_ICON_GAP;
        String title = this.headerTitle;
        int dot = title.indexOf(Ui.TITLE_SEPARATOR);

        // Как в шапке настроек: имя мода ярко, разделитель и раздел — приглушённо
        if (dot > 0)
        {
            String name = title.substring(0, dot);
            Paint.midText(ctx, this.font, name, x, 0, Ui.HEADER_H, Ui.C_TEXT, true);
            x += this.font.width(name);
            Paint.midText(ctx, this.font, Ui.TITLE_SEPARATOR, x, 0, Ui.HEADER_H, Ui.C_TEXT_3, false);
            x += this.font.width(Ui.TITLE_SEPARATOR);
            Paint.midText(ctx, this.font, title.substring(dot + Ui.TITLE_SEPARATOR.length()), x, 0, Ui.HEADER_H,
                    Ui.C_TEXT_2, false);
        }
        else
        {
            Paint.midText(ctx, this.font, title, x, 0, Ui.HEADER_H, Ui.C_TEXT, true);
        }

        String separator = Tr.t("crumbs.separator");

        for (int i = 0; i < this.crumbs.size(); ++i)
        {
            Crumb crumb = this.crumbs.get(i);
            boolean last = i == this.crumbs.size() - 1;
            boolean over = crumb.path != null && Paint.hit(mouseX, mouseY, crumb.x, 0, crumb.w, Ui.HEADER_H);
            int color = last ? Ui.C_TEXT : over ? Ui.C_TEXT : Ui.C_TEXT_2;

            Paint.midText(ctx, this.font, crumb.label, crumb.x, 0, Ui.HEADER_H, color, false);

            if (over)
            {
                Paint.rect(ctx, crumb.x, (Ui.HEADER_H + Ui.GLYPH_H) / 2 + 1, crumb.w, Ui.BORDER, Ui.C_TEXT_2);
                Tooltip.offer(crumb, crumb.path.toString());
            }

            if (! last)
            {
                Paint.midText(ctx, this.font, separator, crumb.x + crumb.w, 0, Ui.HEADER_H, Ui.C_TEXT_3, false);
            }
        }

        this.openFolder.render(ctx, mouseX, mouseY);
        this.refresh.render(ctx, mouseX, mouseY);
        this.settings.render(ctx, mouseX, mouseY);
    }

    private void drawTabs(GuiContext ctx, int mouseX, int mouseY)
    {
        int stripY = Ui.HEADER_H;

        boolean vanilla = Style.vanilla();

        if (! vanilla)
        {
            Paint.rect(ctx, 0, stripY, this.width, Ui.TABS_H, Ui.C_PANEL);
        }

        for (TabBox box : this.tabBoxes)
        {
            boolean active = box.tab == this.tab;
            boolean over = Paint.hit(mouseX, mouseY, box.x, stripY, box.w, Ui.TABS_H);
            float hover = this.hoverAnim.update("tab#" + box.tab, over && ! active);

            if (vanilla)
            {
                // Вкладка Minecraft; подчёркивания и плавного наведения нет
                // Спрайт на всю высоту полосы, между вкладками зазор 1 px. У невыбранной вкладки сверху прозрачные
                // строки спрайта — надпись по центру её видимой части, ниже на них, как у Minecraft
                int drop = active ? 0 : Vanilla.TAB_IDLE_DROP;
                Vanilla.tab(ctx, box.x + 1, stripY + 1, box.w - 2, Ui.TABS_H - 1, active, over);
                Paint.midText(ctx, this.font, box.label, box.x + (box.w - this.font.width(box.label)) / 2,
                        stripY + 1 + drop, Ui.TABS_H - 1 - drop, Vanilla.tabText(active, over), true);
                continue;
            }

            if (hover > 0.0F)
            {
                Paint.round(ctx, box.x, stripY + Ui.PAD_MICRO, box.w, Ui.TABS_H - Ui.PAD_IN, Ui.R_ROW,
                        Ui.alpha(Ui.C_ROW_HOVER, hover));
            }

            Paint.midText(ctx, this.font, box.label, box.x + (box.w - this.font.width(box.label)) / 2, stripY,
                    Ui.TABS_H, active ? Ui.C_TEXT : Ui.C_TEXT_2, active);

            if (active)
            {
                Paint.rect(ctx, box.x, stripY + Ui.TABS_H - Ui.TAB_UNDERLINE, box.w, Ui.TAB_UNDERLINE, Ui.C_ACCENT);
            }
        }

        if (this.mode == 1 && this.tab == Tab.LIBRARY)
        {
            this.showFiles.render(ctx, mouseX, mouseY);
            this.showView.render(ctx, mouseX, mouseY);
        }

        if (vanilla)
        {
            Vanilla.headerSeparator(ctx, 0, stripY + Ui.TABS_H - 2, this.width);
            return;
        }

        Paint.rect(ctx, 0, stripY + Ui.TABS_H - Ui.BORDER, this.width, Ui.BORDER, Ui.C_DIVIDER);
    }

    /** Разделитель между превью и карточкой в узком окне — горизонтальный, не перетаскивается. */
    private void drawDividers(GuiContext ctx)
    {
        if (this.tab == Tab.LIBRARY && this.mode < 3 && this.info.visible() && this.info.h() > 0)
        {
            Paint.rect(ctx, this.info.x(), this.info.y() - Ui.BORDER, this.info.w(), Ui.BORDER, Ui.C_DIVIDER);
        }
    }

    private void drawFooter(GuiContext ctx, int mouseX, int mouseY)
    {
        int y = this.height - this.footerH;

        // Ванильный стиль: подвал без заливки, над ним разделитель Minecraft
        if (Style.vanilla())
        {
            Vanilla.footerSeparator(ctx, 0, y, this.width);
        }
        else
        {
            Paint.rect(ctx, 0, y, this.width, this.footerH, Ui.C_CHROME);
            Paint.rect(ctx, 0, y, this.width, Ui.BORDER, Ui.C_DIVIDER);
        }

        this.back.render(ctx, mouseX, mouseY);

        if (! this.footerStatus.isEmpty() && this.footerStatusW > 0)
        {
            Paint.midText(ctx, this.font, Paint.fit(this.font, this.footerStatus, this.footerStatusW), this.footerStatusX,
                    y, this.footerH, Ui.C_TEXT_3, false);

            if (this.footerStatusTip != null && mouseX >= this.footerStatusX && mouseX < this.footerStatusX + this.footerStatusW
                    && mouseY >= y && mouseY < y + this.footerH)
            {
                Tooltip.offer(this.footerStatus, this.footerStatusTip);
            }
        }

        for (Button button : this.footerButtons)
        {
            button.render(ctx, mouseX, mouseY);
        }
    }

    @Override
    protected void drawButtonHoverTexts(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        boolean blocked = this.layers.blocking();

        this.toasts.render(ctx, blocked ? -1 : mouseX, blocked ? -1 : mouseY);
        this.layers.render(ctx, mouseX, mouseY);
        Tooltip.render(ctx, this.font, mouseX, mouseY, this.width, this.height);
    }

    private List<Pane> activePanes()
    {
        return switch (this.tab)
        {
            case LIBRARY -> List.of(this.files, this.preview, this.info);
            case PLACEMENTS -> List.of(this.placements);
            case SELECTION -> List.of(this.selection);
            case MATERIALS -> List.of(this.materials);
            case TRASH -> List.of(this.trash);
        };
    }

    // ===== Ввод =====

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.layers.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (this.toasts.mouseClicked(click))
        {
            return true;
        }

        // Карточка стоит выше тела вкладки (на месте HUD) — щелчок ей напрямую, по всему экрану
        if (this.cursorAtCard())
        {
            return this.selection.mouseClicked(click, doubleClick);
        }

        for (Pane pane : this.activePanes())
        {
            if (! pane.contains(click.x(), click.y()))
            {
                pane.blur();
            }
        }

        if (! this.worldMoving() && this.clickChrome(click, doubleClick))
        {
            return true;
        }

        if (this.filesSplit.visible() && this.filesSplit.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (this.infoSplit.visible() && this.infoSplit.mouseClicked(click, doubleClick))
        {
            return true;
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.visible() && pane.contains(click.x(), click.y()))
            {
                return pane.mouseClicked(click, doubleClick);
            }
        }

        return false;
    }

    private boolean clickChrome(MouseButtonEvent click, boolean doubleClick)
    {
        if (click.y() < Ui.HEADER_H)
        {
            for (Button button : List.of(this.openFolder, this.refresh, this.settings))
            {
                if (button.mouseClicked(click, doubleClick))
                {
                    return true;
                }
            }

            for (Crumb crumb : this.crumbs)
            {
                if (crumb.path != null && Kit.leftHit(click, crumb.x, 0, crumb.w, Ui.HEADER_H))
                {
                    Vanilla.click();
                    this.files.open(crumb.path);
                    return true;
                }
            }

            return true;
        }

        if (click.y() < Ui.HEADER_H + Ui.TABS_H)
        {
            if (this.mode == 1 && this.tab == Tab.LIBRARY
                    && (this.showFiles.mouseClicked(click, doubleClick) || this.showView.mouseClicked(click, doubleClick)))
            {
                return true;
            }

            for (TabBox box : this.tabBoxes)
            {
                if (Kit.leftHit(click, box.x, Ui.HEADER_H, box.w, Ui.TABS_H))
                {
                    Vanilla.click();
                    this.setTab(box.tab);
                    return true;
                }
            }

            return true;
        }

        if (click.y() >= this.height - this.footerH)
        {
            if (this.back.mouseClicked(click, doubleClick))
            {
                return true;
            }

            for (Button button : List.copyOf(this.footerButtons))
            {
                if (button.visible() && button.mouseClicked(click, doubleClick))
                {
                    return true;
                }
            }

            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (this.layers.mouseDragged(click, dx, dy))
        {
            return true;
        }

        if (this.filesSplit.mouseDragged(click, dx, dy) || this.infoSplit.mouseDragged(click, dx, dy))
        {
            return true;
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.mouseDragged(click, dx, dy))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        if (this.layers.mouseReleased(click))
        {
            return true;
        }

        for (Button button : List.of(this.back, this.openFolder, this.refresh, this.settings, this.showFiles,
                this.showView, this.footerPlace, this.footerMaterials))
        {
            button.mouseReleased(click);
        }

        for (Button button : this.footerButtons)
        {
            button.mouseReleased(click);
        }

        boolean handled = this.filesSplit.mouseReleased(click) | this.infoSplit.mouseReleased(click);

        for (Pane pane : this.activePanes())
        {
            handled |= pane.mouseReleased(click);
        }

        return handled;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical)
    {
        if (this.layers.mouseScrolled(mouseX, mouseY, vertical))
        {
            return true;
        }

        if (this.cursorAtCard())
        {
            return this.selection.mouseScrolled(mouseX, mouseY, vertical);
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.visible() && pane.contains(mouseX, mouseY))
            {
                return pane.mouseScrolled(mouseX, mouseY, vertical);
            }
        }

        return false;
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.layers.keyPressed(input))
        {
            return true;
        }

        int key = input.key();

        // Клавиша курсора ещё раз — снова игра (в поля координат буквы всё равно не вводятся)
        if (this.cursorAtCard() && SelectionHud.isCursorKey(key))
        {
            this.selection.unpark(true);
            return true;
        }

        if (key == InputConstants.KEY_ESCAPE)
        {
            // Esc в поле ввода только снимает фокус; пока панели едут в мир — отменяет; курсор у карточки — экран
            // целиком; дальше — назад
            if (! this.blurAll() && ! (this.tab == Tab.SELECTION && this.selection.leaveWorldView()))
            {
                Nav.back();
            }

            return true;
        }

        if (key == InputConstants.KEY_F5)
        {
            this.refresh();
            return true;
        }

        if (this.tab == Tab.LIBRARY && key == InputConstants.KEY_F && input.hasControlDownWithQuirk())
        {
            if (this.mode == 1)
            {
                this.setNarrowView(false);
            }

            this.files.focusSearch();
            return true;
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.visible() && pane.keyPressed(input))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onCharTyped(CharacterEvent event)
    {
        if (this.layers.charTyped(event))
        {
            return true;
        }

        for (Pane pane : this.activePanes())
        {
            if (pane.visible() && pane.charTyped(event))
            {
                return true;
            }
        }

        return false;
    }

    /** Черновик IME (26.3): ваниль отдаёт его экрану, а мы — полю, которое держит ввод текста. */
    @Override
    public boolean preeditUpdated(@Nullable PreeditEvent event)
    {
        return TextFocus.preedit(event) || super.preeditUpdated(event);
    }

    /** Файлы брошены в окно: копия в текущую папку библиотеки. */
    @Override
    public boolean onMouseDropFiles(List<Path> paths)
    {
        this.setTab(Tab.LIBRARY);

        if (this.mode == 1)
        {
            this.setNarrowView(false);
        }

        this.files.acceptDropped(paths);
        return true;
    }

    private boolean blurAll()
    {
        boolean any = false;

        for (Pane pane : List.of(this.files, this.preview, this.info, this.placements, this.selection, this.materials, this.trash))
        {
            any |= pane.blur();
        }

        return any;
    }

    @Override
    public void removed()
    {
        // Ваниль при смене экрана ввод текста уже выключила; фокус полей снимаем сами — иначе по
        // возвращении поле рисовалось бы в фокусе и снова включало ввод без клика игрока
        this.blurAll();
        TextFocus.release(this);

        super.removed();

        // Экран уходит (в том числе под редактор размещения) — видеопамять и превью отпускаем,
        // при возвращении initGui поднимет их заново
        this.files.release();
        this.preview.release();
        this.info.release();
        this.placements.release();
        this.selection.release();
        this.materials.release();
        this.trash.release();
        this.layers.closeAll();
        this.snapshotApplied = false;
        SchematicsState.get().save();
    }

    // ===== Выбор схемы =====

    /** Сведения о файле, один запрос на запись (запись меняется вместе с размером/временем файла). */
    CompletableFuture<SchematicInfo> info(SchematicEntry entry)
    {
        return this.infoCache.computeIfAbsent(entry, e ->
        {
            try
            {
                return SchematicLibrary.get().info(e);
            }
            catch (RuntimeException error)
            {
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    void clearInfoCache()
    {
        this.infoCache.clear();
    }

    @Nullable SchematicEntry selected()
    {
        return this.selected;
    }

    /** Сведения о выбранной схеме; {@code null} — ещё читаются (или выбора нет). */
    @Nullable SchematicInfo selectedInfo()
    {
        return done(this.selectedInfo);
    }

    @Nullable Throwable selectedInfoError()
    {
        return failure(this.selectedInfo);
    }

    boolean snapshotPending()
    {
        return this.snapshot != null && ! this.snapshot.isDone();
    }

    @Nullable Throwable snapshotError()
    {
        return failure(this.snapshot);
    }

    void select(@Nullable SchematicEntry entry)
    {
        if (Objects.equals(entry, this.selected))
        {
            return;
        }

        this.selected = entry;
        this.selectedInfo = entry == null ? null : this.info(entry);
        this.snapshotApplied = false;

        if (entry == null)
        {
            this.snapshot = null;
        }
        else
        {
            this.requestSnapshot(entry);
        }

        this.preview.onSelect(entry);
        this.info.onSelect(entry);
    }

    /** Кадр: снимок блоков готов — отдать превью (виджет сам решает, когда строить модель). */
    // ===== Syncmatica =====

    /** Общее размещение ждёт разрешения сервера на правку. */
    private @Nullable SchematicPlacement syncEditWaiting;
    private long syncEditSince;
    /** Общее размещение в редакторе: по возвращении сюда правка уходит на сервер. */
    private @Nullable SchematicPlacement syncEditOpen;
    /** «Разместить и поделиться»: поделиться, когда редактор закроют «Готово». */
    private @Nullable SchematicPlacement pendingShare;
    private boolean shareNext;

    /** «Разместить и поделиться» (Библиотека): как «Разместить здесь», а после «Готово» — на сервер. */
    void placeAndShare(SchematicEntry entry)
    {
        this.shareNext = true;
        this.place(entry, true);
    }

    /** Поделиться размещением на сервере Syncmatica, с сообщением. */
    void share(SchematicPlacement placement)
    {
        String error = com.aell.layerlist.compat.Syncmatica.share(placement);
        this.toasts.show(error == null ? Tr.f("sync.shared", placement.getName())
                : net.minecraft.client.resources.language.I18n.get(error), error == null ? Ui.C_ON : Ui.C_WARN);
    }

    private void tickSync()
    {
        if (this.syncEditWaiting != null)
        {
            SchematicPlacement p = this.syncEditWaiting;

            if (com.aell.layerlist.compat.Syncmatica.editable(p))
            {
                this.syncEditWaiting = null;
                this.syncEditOpen = p;
                this.openEditor(p, false, false);
                return;
            }

            if (net.minecraft.util.Util.getMillis() - this.syncEditSince > 6000L)
            {
                this.syncEditWaiting = null;
                this.toasts.show(Tr.t("sync.edit.denied"), Ui.C_WARN);
            }
        }

        if (GuiUtils.getCurrentScreen() != this)
        {
            return;
        }

        if (this.syncEditOpen != null)
        {
            SchematicPlacement p = this.syncEditOpen;
            this.syncEditOpen = null;
            com.aell.layerlist.compat.Syncmatica.finishEdit(p);
            this.toasts.show(Tr.t("sync.edit.sent"), Ui.C_ON);
        }

        if (this.pendingShare != null)
        {
            SchematicPlacement p = this.pendingShare;
            this.pendingShare = null;

            // «Отмена» нового размещения убирает его — тогда делиться нечем
            if (PlacementOps.all().contains(p))
            {
                this.share(p);
            }
        }
    }

    /** Предел клеток, с которым просили снимок: сменили «Качественно / Оптимизировано» — снять заново. */
    private long snapshotCells;

    private void requestSnapshot(SchematicEntry entry)
    {
        this.snapshotCells = SchematicLibrary.previewCells();

        try
        {
            this.snapshot = SchematicLibrary.get().snapshot(entry);
        }
        catch (RuntimeException e)
        {
            this.snapshot = CompletableFuture.failedFuture(e);
        }
    }

    private void tickSelection()
    {
        // Качество сменили — снимок другого размера (прошлая модель на экране, пока новая не готова)
        if (this.selected != null && this.snapshotApplied && SchematicLibrary.previewCells() != this.snapshotCells)
        {
            this.snapshotApplied = false;
            this.requestSnapshot(this.selected);
        }

        if (this.snapshotApplied || this.snapshot == null || ! this.snapshot.isDone() || this.selected == null)
        {
            return;
        }

        this.snapshotApplied = true;
        VoxelSnapshot ready = done(this.snapshot);

        if (ready != null)
        {
            this.preview.show(ready, this.selected);
        }
    }

    static <T> @Nullable T done(@Nullable CompletableFuture<T> future)
    {
        return future != null && future.isDone() && ! future.isCompletedExceptionally() ? future.getNow(null) : null;
    }

    static @Nullable Throwable failure(@Nullable CompletableFuture<?> future)
    {
        if (future == null || ! future.isCompletedExceptionally())
        {
            return null;
        }

        try
        {
            future.join();
            return null;
        }
        catch (RuntimeException e)
        {
            return e;
        }
    }

    // ===== Действия =====

    boolean busyPlacing()
    {
        return this.placing != null;
    }

    void placeSelected(boolean here)
    {
        if (this.selected != null)
        {
            this.place(this.selected, here);
        }
    }

    /**
     * «Разместить здесь» ({@code here}): перед игроком и сразу редактор в виде сверху.
     * «Разместить…»: редактор в обычном виде, схема — на месте прошлого размещения отсюда, иначе
     * у блока под прицелом, иначе перед игроком.
     */
    void place(SchematicEntry entry, boolean here)
    {
        Minecraft mc = Minecraft.getInstance();

        if (this.placing != null)
        {
            return;
        }

        if (mc.level == null || mc.player == null)
        {
            this.toasts.show(Tr.t("place.need_world"), Ui.C_WARN);
            return;
        }

        CompletableFuture<LoadedSchematic> loading;

        try
        {
            loading = SchematicLibrary.get().load(entry);
        }
        catch (RuntimeException e)
        {
            loading = CompletableFuture.failedFuture(e);
        }

        this.placing = entry;
        Tooltip.reset();

        loading.whenCompleteAsync((loaded, error) ->
        {
            this.placing = null;

            // Пока грузилось, игрок мог уйти с экрана — тогда молча ничего не делаем
            if (GuiUtils.getCurrentScreen() != this)
            {
                return;
            }

            if (error != null || loaded == null)
            {
                this.toasts.show(Tr.f("place.failed", error == null ? "?" : Tr.reason(error)), Ui.C_DANGER);
                return;
            }

            this.finishPlace(loaded, here);
        }, mc);
    }

    private void finishPlace(LoadedSchematic loaded, boolean here)
    {
        SchematicPlacement placement;
        // Место для «Разместить…» — до создания: иначе новое размещение само стало бы «прошлым»
        BlockPos spot = here ? null : this.lastSpot();

        try
        {
            // Сразу на своё место: перенос после создания перестраивал бы чанки дважды
            placement = spot != null ? PlacementOps.placeAt(loaded, spot) : PlacementOps.placeInFront(loaded);
        }
        catch (RuntimeException e)
        {
            this.toasts.show(Tr.f("place.failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        lastPlaced = new WeakReference<>(placement);
        this.applyFileZones(placement);

        if (this.shareNext)
        {
            this.shareNext = false;
            this.pendingShare = placement;
        }
        this.edit(placement, true, here);
    }

    /**
     * Схема разбита на зоны (сетка запомнена для файла) — новое размещение сразу делится теми же зонами, даже если
     * у Zone Focus сейчас зоны другого размещения: без этого он раздал бы их только когда зон нет совсем.
     */
    private void applyFileZones(SchematicPlacement placement)
    {
        java.nio.file.Path file = placement.getSchematicFile();

        if (file == null || ! com.aell.layerlist.compat.ZoneFocusFiles.available())
        {
            return;
        }

        int[] grid = com.aell.layerlist.compat.ZoneFocusFiles.grid(file);

        if (grid != null)
        {
            com.aell.layerlist.compat.ZoneFocusFiles.split(file, grid[0], grid[1], grid[2]);
        }
    }

    private @Nullable BlockPos lastSpot()
    {
        SchematicPlacement previous = lastPlaced.get();

        try
        {
            if (previous != null && PlacementOps.all().contains(previous))
            {
                return previous.getOrigin();
            }
        }
        catch (RuntimeException e)
        {
            // Список размещений недоступен — берём прицел
        }

        HitResult hit = Minecraft.getInstance().hitResult;

        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK)
        {
            return block.getBlockPos().relative(block.getDirection());
        }

        return null;
    }

    /** Редактор размещения поверх этого экрана; «Готово»/«Отмена» вернут сюда. */
    void edit(SchematicPlacement placement, boolean createdNow, boolean topView)
    {
        // Общее размещение Syncmatica заперто: сначала разрешение сервера, редактор — когда отопрёт
        if (! createdNow && com.aell.layerlist.compat.Syncmatica.shared(placement)
                && ! com.aell.layerlist.compat.Syncmatica.editable(placement))
        {
            String error = com.aell.layerlist.compat.Syncmatica.requestEdit(placement);

            if (error != null)
            {
                this.toasts.show(net.minecraft.client.resources.language.I18n.get(error), Ui.C_WARN);
                return;
            }

            this.syncEditWaiting = placement;
            this.syncEditSince = net.minecraft.util.Util.getMillis();
            this.toasts.show(Tr.t("sync.edit.waiting"), Ui.C_TEXT_2);
            return;
        }

        if (com.aell.layerlist.compat.Syncmatica.shared(placement))
        {
            this.syncEditOpen = placement;
        }

        this.openEditor(placement, createdNow, topView);
    }

    private void openEditor(SchematicPlacement placement, boolean createdNow, boolean topView)
    {
        try
        {
            PlacementEditorScreen.open(this, placement, createdNow, topView);
        }
        catch (RuntimeException e)
        {
            this.toasts.show(Tr.f("place.editor_failed", Tr.reason(e)), Ui.C_WARN);
        }
    }

    // ===== Удаление файлов и корзина (0.0.27) =====

    /** Удалить файл схемы или пустую папку из «Библиотеки»: подтверждение, корзина, тост с «Отменить». */
    void confirmDelete(SchematicEntry entry)
    {
        DeleteFlow.confirm(this, entry);
    }

    /** Строка «Корзина (N)» в корне списка файлов: на вкладку «Корзина». */
    void openTrash()
    {
        this.setTab(Tab.TRASH);
    }

    /** Файл ушёл в корзину: забыть его сведения, снимок и миниатюру, список — без него, выбор — на соседа. */
    void fileGone(Path path)
    {
        Path gone = path.toAbsolutePath().normalize();
        this.infoCache.keySet().removeIf(e -> e.path().toAbsolutePath().normalize().equals(gone));

        try
        {
            SchematicLibrary.get().forget(gone);
        }
        catch (RuntimeException e)
        {
            // Кеши подчистятся при следующем открытии экрана
        }

        this.files.deleted(gone);
    }

    /** Файл вернулся из корзины: список перечитать и выбрать его, если он в открытой папке. */
    void fileBack(Path path)
    {
        this.files.restored(path.toAbsolutePath().normalize());
    }

    /** Переименовать файл схемы (меню ПКМ строки): окно с именем, тост с «Отменить». */
    void confirmRename(SchematicEntry entry)
    {
        RenameFlow.open(this, entry);
    }

    /** Файл переименован: кеши старого имени забыть, список перечитать, выбрать файл под новым именем. */
    void fileRenamed(Path from, Path to)
    {
        Path old = from.toAbsolutePath().normalize();
        this.infoCache.keySet().removeIf(e -> e.path().toAbsolutePath().normalize().equals(old));

        try
        {
            SchematicLibrary.get().renamed(old, to);
        }
        catch (RuntimeException e)
        {
            // Кеши подчистятся при следующем открытии экрана
        }

        this.files.renamed(old, to.toAbsolutePath().normalize());
        this.placements.reload();
    }

    /** Корзина изменилась на её вкладке: число в строке «Корзина» списка файлов. */
    void trashChanged()
    {
        this.files.refresh();
    }

    private void purgeTrash()
    {
        List<SchematicTrash.Item> purged;

        try
        {
            purged = SchematicTrash.get().purgeExpired();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (! purged.isEmpty())
        {
            TrashPane.dropThumbnails(purged);
            this.purgeNote = Tr.f("trash.purged", SchematicTrash.KEEP_DAYS, Tr.num(purged.size()));
        }
    }

    void confirmRemove(SchematicPlacement placement)
    {
        Modal.confirm(this.layers, Tr.t("delete.title"), Tr.f("delete.text", placement.getName()), Tr.t("delete.yes"),
                true, () ->
                {
                    try
                    {
                        PlacementOps.remove(placement);
                        this.placements.reload();
                    }
                    catch (RuntimeException e)
                    {
                        this.toasts.show(Tr.f("delete.failed", Tr.reason(e)), Ui.C_DANGER);
                    }
                }, Tr.t("cancel"));
    }

    void toggleEnabled(SchematicPlacement placement)
    {
        try
        {
            placement.setEnabled(! placement.isEnabled());
        }
        catch (RuntimeException e)
        {
            this.toasts.show(Tr.f("placements.toggle_failed", Tr.reason(e)), Ui.C_WARN);
        }
    }

    /** Рисовать ли голограммы размещения (флаг рендера Litematica, само размещение остаётся включённым). */
    void toggleRender(SchematicPlacement placement)
    {
        try
        {
            placement.setRenderSchematic(! placement.isRenderingEnabled());
        }
        catch (RuntimeException e)
        {
            this.toasts.show(Tr.f("placements.toggle_failed", Tr.reason(e)), Ui.C_WARN);
        }
    }

    /** Подвкладка «Материалы» с этой схемой (или размещением). */
    /** «Разбить на зоны…»: выбрать файл и открыть под превью панель разбиения (Zone Focus). */
    void openZones(SchematicEntry entry)
    {
        this.setTab(Tab.LIBRARY);
        this.select(entry);

        if (! this.narrowShowsView)
        {
            this.setNarrowView(true);
        }

        this.preview.openZonePanel();
    }

    /** «Управление зонами…»: размещена — экран Zone Focus, нет — панель разбиения в превью. */
    void manageZones(SchematicEntry entry)
    {
        if (! com.aell.layerlist.compat.ZoneFocusFiles.openManager(entry.path()))
        {
            this.openZones(entry);
        }
    }

    /** Блоки выделенной области — на вкладке «Материалы». */
    /** Куда «Сохранить схему» кладёт файл: открытая папка «Библиотеки». */
    java.nio.file.Path libraryFolder()
    {
        return this.files.folder();
    }

    String libraryFolderName()
    {
        java.nio.file.Path root = SchematicLibrary.get().root().toAbsolutePath().normalize();
        java.nio.file.Path dir = this.files.folder().toAbsolutePath().normalize();
        java.nio.file.Path name = root.getFileName();
        String base = name == null ? root.toString() : name.toString();
        return dir.equals(root) || ! dir.startsWith(root) ? base : base + "/" + root.relativize(dir).toString().replace('\\', '/');
    }

    /**
     * Схема сохранена: в «Библиотеку», в её папку, файл выбран и висит сверху в жёлтой рамке с «NEW» (рамка гаснет
     * за 30 с). Экран уже закрыли — только отметка: откроют «Библиотеку» — схема там сверху.
     */
    void showSaved(java.nio.file.Path file)
    {
        SchematicLibrary.get().forget(file);
        FileListPane.markNew(file);

        if (GuiUtils.getCurrentScreen() != this)
        {
            return;
        }

        this.setTab(Tab.LIBRARY);
        java.nio.file.Path dir = file.getParent();

        if (dir != null)
        {
            this.files.open(dir);
        }

        this.files.refresh();
        // «Сохранено» Litematica покажет сама — второе сообщение ни к чему
        this.files.selectPath(file);
    }

    void showArea(com.aell.layerlist.schematics.gui.materials.TableSource.Area area)
    {
        this.setTab(Tab.MATERIALS);
        this.materials.openArea(area);
    }

    void showMaterials(@Nullable SchematicEntry entry, @Nullable SchematicPlacement placement)
    {
        this.materials.open(entry, placement);
        this.setTab(Tab.MATERIALS);
    }

    void openExport(@Nullable SchematicEntry entry)
    {
        ExportDialog.open(this, entry);
    }

    /** Открыть папку (или папку файла) в системе. */
    void openInSystem(Path path)
    {
        Path target = Files.isDirectory(path) ? path : path.getParent();

        try
        {
            if (target != null)
            {
                Blaze3D.openPath(target);
            }
        }
        catch (RuntimeException e)
        {
            this.toasts.show(Tr.f("toast.open_failed", Tr.reason(e)), Ui.C_DANGER);
        }
    }

    void copyPath(Path path)
    {
        Minecraft.getInstance().keyboardHandler.setClipboard(path.toAbsolutePath().toString());
        this.toasts.show(Tr.t("toast.path_copied"), Theme.GREEN);
    }

    private void openCurrentFolder()
    {
        this.openInSystem(this.files.folder());
    }

    private void refresh()
    {
        try
        {
            SchematicLibrary.get().refreshRoot();
        }
        catch (RuntimeException e)
        {
            // Корень остаётся прежним
        }

        this.clearInfoCache();
        this.files.refresh();
        this.placements.reload();

        // На «Материалах» — заново прочитать схему, инвентарь и сундуки
        if (this.tab == Tab.MATERIALS)
        {
            this.materials.refresh();
        }

        if (this.selected != null)
        {
            SchematicEntry again = this.selected;
            this.selected = null;
            this.select(again);
        }
    }

    /**
     * Сообщения Litematica и malilib, пока открыт этот экран (они ищут текущий {@code GuiBase}), — тостом
     * в углу, а не чёрной плашкой malilib посреди окна поверх превью и карточки.
     */
    @Override
    public void addMessage(Message.MessageType type, int lifeTime, String messageKey, Object... args)
    {
        if (SchematicsModule.noiseMessage(messageKey))
        {
            return;
        }

        int color = switch (type)
        {
            case ERROR -> Ui.C_DANGER;
            case WARNING -> Ui.C_WARN;
            case SUCCESS -> Theme.GREEN;
            default -> Ui.C_TEXT_2;
        };

        this.toast(StringUtils.translate(messageKey, args), color);
    }

    @Override
    public void addMessage(Message.MessageType type, String messageKey, Object... args)
    {
        this.addMessage(type, 5000, messageKey, args);
    }

    /** Сообщение-тост для панелей. */
    void toast(String text, int color, Toasts.Action... actions)
    {
        this.toasts.show(text, color, actions);
    }
}
