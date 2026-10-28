package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.compat.VoxyPreview;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.area.AreaCapture;
import com.aell.layerlist.schematics.area.AreaCount;
import com.aell.layerlist.schematics.area.AreaMap;
import com.aell.layerlist.schematics.gui.materials.TableSource;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.schematics.preview.PreviewCamera;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.ImageTexture;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Slider;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiAreaSelectionManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.PositionUtils.CoordinateType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Подвкладка «Выделение» — свой редактор выделения Litematica («Редактор региона»).
 *
 * <p>Справа сверху — карточка «Координаты» ({@link SelectionHud.Card}): столбцы X Y Z цветами осей, строки — угол 1,
 * угол 2 (поле и кнопка «+/−», колесо над полем; Shift ×10, Ctrl ×100; «Двигать в мире» — прицел, «К игроку» —
 * человечек), размер и точка привязки (зелёная) с тумблером «своя / по углам». В заголовке карточки — «Смотреть на
 * мир». Под карточкой — компоненты (подрегионы) с размером и объёмом, «Добавить», «Имя…», «Удалить». Сверху — имя,
 * режим «Простое / Составное», «Все выделения…» и «Сбросить» (с подтверждением: все компоненты убираются, точка
 * привязки — по углам).
 *
 * <p>Слева — живое превью прогруженной области мира ({@link AreaCapture}: только загруженные игрой чанки, без
 * Bobby и Voxy; радиус {@code min(32, дальность прорисовки)}): 3D — изометрия как в «Библиотеке», 2D — карта сверху в
 * цветах Bobby Map ({@link AreaMap});
 * «Качественно / Оптимизировано», срез по слоям, «Обновить». Рамки компонентов видны на превью, щелчок по блоку
 * ставит угол (по очереди 1 и 2; вода насквозь — берётся дно).
 *
 * <p>В подвале — «Анализ области» (блоки в компонентах — списком на вкладке «Материалы») и «Сохранить схему».
 * Данные — само выделение Litematica: правки сразу видны рамкой в мире и сохраняются Litematica как обычно.
 *
 * <p>«Смотреть на мир»: шапка, вкладки и верхняя строка уезжают вверх, подвал — вниз, превью — влево, компоненты —
 * вправо, фон тает, а карточка координат поднимается в правый верхний угол. Потом экран закрывается и идёт обычная
 * игра, карточка остаётся HUD-ом ({@link SelectionHud}); Esc в игре — экран въезжает обратно. Esc, пока панели едут, —
 * отмена.
 */
@Environment(EnvType.CLIENT)
final class SelectionPane extends Pane
{
    private static final int ROW_H = Ui.ROW_H;
    private static final int GAP = Ui.PAD_REL;
    private static final int TIGHT = 2;
    /** Плитка «Компоненты»: подпись и кнопки внутри серой карточки. */
    private int boxesTileY;
    private int boxesTileH;
    private static final int NUDGE_W = SelectionHud.NUDGE_W;
    private static final int ICON_W = SelectionHud.ICON_W;
    private static final int STATUS_H = 12;
    private static final long CAPTURE_BUDGET_NS = 4_000_000L;
    private static final long COUNT_BUDGET_NS = 4_000_000L;
    /**
     * Клеток снимка области: при 32 чанках это клетка в 2 блока, а не в 4 (байт на клетку — десятки мегабайт, и только
     * на время, пока вкладка открыта).
     */
    private static final long CELLS_QUALITY = 64_000_000L;
    private static final long CELLS_FAST = 24_000_000L;
    /**
     * Квадов модели области не меньше: орбита рисуется из видеопамяти, и без этого 32 чанка огрублялись до одноцветных
     * кубов по 8–16 блоков.
     */
    private static final int QUADS_QUALITY = 800_000;
    /** Вид Voxy: угол обзора (узкий — почти изометрия) и как часто обновлять кадр, пока камера стоит. */
    private static final float VOXY_FOV = 25.0F;
    private static final long VOXY_IDLE_MS = 250L;
    private static final int QUADS_FAST = 400_000;
    /**
     * Выезд панелей — за постоянное время при любом FPS (экспонента {@code Anim} при кадре дольше своей постоянной
     * доходит до конца за один кадр, и выезда не видно).
     */
    private static final float WORLD_ANIM_MS = 280.0F;

    /** Цвета смысла: угол 1, угол 2, точка привязки — как у рамки Litematica (красный, синий) и зелёный. */
    static final int C_CORNER1 = 0xFFE8604E;
    static final int C_CORNER2 = 0xFF4FA3FF;
    static final int C_ORIGIN = Theme.GREEN;

    /** Вид превью: изометрия (3D) или карта сверху (2D) — на время игры. */
    private static boolean view3d = true;
    private static final int MAP_BG = 0xFF0E0E11;
    private static SchematicsConfig.PreviewQuality quality = SchematicsConfig.PreviewQuality.OPTIMIZED;

    private final Table<Box> table;
    private final TextInput name;
    private final Button rename;
    private final Button allSelections;
    private final Button reset;
    private final Button add;
    private final Button renameBox;
    private final Button removeBox;
    private final CornerBlock corner1;
    private final CornerBlock corner2;
    private final CornerBlock origin;
    private final Toggle explicitOrigin;
    /** «Сбросить» на самой карточке — в мире (курсор у карточки), где верхней строки экрана нет. */
    private final Button resetCard;
    private final Button save;
    private final Button analyze;

    private final PreviewWidget preview = new PreviewWidget();
    private final Button mode3d;
    private final Button mode2d;
    private final ImageTexture mapTexture = new ImageTexture(Identifier.fromNamespaceAndPath("layerlist", "selection/area_map"));
    private @Nullable AreaMap map;
    /** Карта: пикселей экрана на блок и центр вида в мире. */
    private float mapZoom = -1.0F;
    private double mapCenterX;
    private double mapCenterZ;
    private int mapButton = -1;
    private double mapPressX;
    private double mapPressY;
    private boolean mapMoved;
    private final Button qualityHigh;
    private final Button qualityFast;
    private final Slider cut;
    private final Button refresh;

    private final List<Widget> widgets = new ArrayList<>();

    /** «Смотреть на мир»: что уезжает и куда. Карточка координат поднимается на место HUD. */
    private final Button seeWorld;
    private final List<Widget> slideUp = new ArrayList<>();
    private final List<Widget> slideLeft = new ArrayList<>();
    private final List<Widget> slideRight = new ArrayList<>();
    /** Цель выезда: {@code true} — к миру (доехали — экран закрывается), {@code false} — обратно. */
    private boolean worldView;
    /** Ход выезда 0…1 — линейно по времени (до смягчения), и когда считали в последний раз. */
    private float worldT;
    private long worldAt;
    /**
     * Курсор у карточки (R в игре): всё уехало, фон прозрачный, карточка — на месте HUD и живая; R, щелчок мимо
     * карточки — обратно в игру, Esc — панели въезжают, экран целиком.
     */
    private boolean parked;
    /** Опция «Составное выделение» включена, а составного выделения в Litematica ещё не было — заводили один раз. */
    private boolean compoundTried;
    /** Карточка координат и левый край правой колонки (карточка, под ней компоненты). */
    private SelectionHud.Card card = SelectionHud.Card.at(0, 0, SelectionHud.cardWidth(0));
    private int colX;

    /** Последний кадр Voxy: камера, размер окна и когда снят; {@code voxyShown} — в этом кадре рисует Voxy. */
    private PreviewCamera.@Nullable Key voxyKey;
    private int voxyW;
    private int voxyH;
    private long voxyAt;
    private boolean voxyShown;

    private @Nullable AreaCapture capture;
    private AreaCapture.@Nullable Result area;
    /**
     * Срез по слоям выделения: пока ползунок «Срез» не справа, превью показывает только коробку выделения (все
     * компоненты), блок в блок, до выбранного слоя. Снимок коробки — свой ({@link AreaCapture#startBox}), снимается
     * заново, когда коробка меняется. {@code sliceLayers == 0} — выделения нет, ползунок режет всю область.
     */
    private @Nullable AreaCapture sliceCapture;
    private AreaCapture.@Nullable Result slice;
    private @Nullable BoundingBox sliceBox;
    private int sliceMinY;
    private int sliceLayers;
    private @Nullable AreaCount counting;
    private Corner nextCorner = Corner.CORNER_1;
    private long renderedAt;

    private int signature;
    private @Nullable String shownName;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private int cutLabelX;

    SelectionPane(GuiSchematics screen)
    {
        super(screen);

        this.table = new Table<Box>(ROW_H).columns(
                Table.Column.<Box>flex(Tr.t("selection.col.name"), 3)
                        .minWidth(50)
                        .text(Box::getName)
                        .sort(Comparator.comparing(Box::getName, String.CASE_INSENSITIVE_ORDER)),
                Table.Column.<Box>fixed(Tr.t("selection.col.size"), 78)
                        .align(Table.Align.RIGHT)
                        .text(SelectionPane::sizeText),
                Table.Column.<Box>fixed(Tr.t("selection.col.volume"), 56)
                        .align(Table.Align.RIGHT)
                        .hideBelow(250)
                        .text(b -> Tr.num(volume(b))))
                .listener(new ScrollList.Listener<>()
                {
                    @Override
                    public void select(@Nullable Box item, int index)
                    {
                        AreaSelection selection = selection();

                        if (selection != null && item != null)
                        {
                            selection.setSelectedSubRegionBox(item.getName());
                        }
                    }

                    @Override
                    public void activate(Box item, int index)
                    {
                        SelectionPane.this.renameBox(item);
                    }
                });

        this.name = new TextInput().maxLength(128).hint(Tr.t("selection.name.hint"));
        this.name.onSubmit(value -> this.renameSelection());
        this.rename = new Button(Tr.t("selection.rename"), Button.Style.SECONDARY, this::renameSelection)
                .tooltip(Tr.t("selection.rename.tip"));
        this.allSelections = new Button(Tr.t("selection.all"), Button.Style.SECONDARY, this::openManager)
                .tooltip(Tr.t("selection.all.tip"));
        this.reset = new Button(Tr.t("selection.reset"), Button.Style.DANGER, this::confirmReset).tooltip(Tr.t("selection.reset.tip"));
        this.add = new Button(Tr.t("selection.add"), Button.Style.SECONDARY, this::addBox).tooltip(Tr.t("selection.add.tip"));
        this.renameBox = new Button(Tr.t("selection.rename_box"), Button.Style.SECONDARY, () -> this.withBox(this::renameBox));
        this.removeBox = new Button(Tr.t("selection.remove_box"), Button.Style.DANGER, () -> this.withBox(this::removeBox));

        this.corner1 = new CornerBlock(Corner.CORNER_1, C_CORNER1);
        this.corner2 = new CornerBlock(Corner.CORNER_2, C_CORNER2);
        this.origin = new CornerBlock(Corner.NONE, C_ORIGIN);
        this.explicitOrigin = new Toggle(this::hasExplicitOrigin, this::setExplicitOrigin).tooltip(Tr.t("selection.origin.explicit.tip"));
        this.resetCard = new Button(Tr.t("selection.reset"), Button.Style.DANGER, this::confirmReset).tooltip(Tr.t("selection.reset.tip"));

        this.save = new Button(Tr.t("selection.save"), Button.Style.PRIMARY, this::saveSchematic).accent(Theme.GREEN)
                .tooltip(Tr.t("selection.save.tip"));
        this.analyze = new Button(Tr.t("selection.analyze"), Button.Style.SECONDARY, this::analyze)
                .tooltip(Tr.t("selection.analyze.tip"));

        this.mode3d = new Button(Tr.t("selection.view.3d"), Button.Style.SECONDARY, () -> this.setView3d(true))
                .tooltip(Tr.t("selection.view.3d.tip"));
        this.mode2d = new Button(Tr.t("selection.view.2d"), Button.Style.SECONDARY, () -> this.setView3d(false))
                .tooltip(Tr.t("selection.view.2d.tip"));
        this.qualityHigh = new Button(Tr.t("selection.quality.high"), Button.Style.SECONDARY,
                () -> this.setQuality(SchematicsConfig.PreviewQuality.QUALITY)).tooltip(Tr.t("selection.quality.high.tip"));
        this.qualityFast = new Button(Tr.t("selection.quality.fast"), Button.Style.SECONDARY,
                () -> this.setQuality(SchematicsConfig.PreviewQuality.OPTIMIZED)).tooltip(Tr.t("selection.quality.fast.tip"));
        this.cut = new Slider(0, 0, 0, this::onCut).tooltip(Tr.t("selection.cut.tip"));
        this.refresh = new IconButton(Glyphs.REFRESH, Tr.t("selection.refresh.tip"), Button.Style.SECONDARY, this::recapture);

        this.preview.setQuality(quality);
        this.preview.setBudgetFloor(quality == SchematicsConfig.PreviewQuality.QUALITY ? QUADS_QUALITY : QUADS_FAST);
        this.preview.setPick((cell, button) -> this.picked(cell));

        // Акцентом и надписью: главный путь с этой вкладки в игру
        this.seeWorld = new Button(Tr.t("selection.world"), Button.Style.PRIMARY, this::toggleWorldView).glyph(Glyphs.EYE)
                .accent(Ui.C_ACCENT).tooltip(Tr.t("selection.world.tip"));

        this.slideUp.addAll(List.of(this.name, this.rename, this.seeWorld, this.allSelections, this.reset));
        this.slideLeft.addAll(List.of(this.mode3d, this.mode2d, this.qualityHigh, this.qualityFast, this.cut, this.refresh));
        this.slideRight.addAll(List.of(this.add, this.renameBox, this.removeBox));

        this.widgets.addAll(this.slideUp);
        this.widgets.addAll(this.slideLeft);
        this.widgets.addAll(this.slideRight);
        this.widgets.add(this.explicitOrigin);
        this.widgets.add(this.resetCard);

        for (CornerBlock block : List.of(this.corner1, this.corner2, this.origin))
        {
            this.widgets.addAll(block.widgets());
        }
    }

    // ===== «Смотреть на мир» =====

    private void toggleWorldView()
    {
        if (Minecraft.getInstance().level == null)
        {
            return;
        }

        this.worldView = true;
        // Уезжающие поля не должны держать ввод, список — клавиши
        this.screen.layers.closeAll();
        this.blur();
        this.table.setFocused(false);
    }

    /**
     * Esc: пока панели едут к миру — отмена; курсор у карточки — экран целиком (панели въезжают). {@code true} — было
     * что возвращать.
     */
    boolean leaveWorldView()
    {
        if (this.parked)
        {
            this.unpark(false);
            return true;
        }

        if (! this.worldView)
        {
            return false;
        }

        this.worldView = false;
        return true;
    }

    /** Экран открыт клавишей курсора из игры: сразу «в мире», карточка живая на месте HUD. */
    void park()
    {
        this.parked = true;
        this.worldView = true;
        this.worldT = 1.0F;
        this.worldAt = 0L;
    }

    /** Курсор уходит от карточки: {@code toGame} — снова игра (экран закроется), иначе — панели въезжают на места. */
    void unpark(boolean toGame)
    {
        if (! this.parked)
        {
            return;
        }

        this.parked = false;
        this.worldView = toGame;
        this.worldT = 1.0F;
        this.worldAt = 0L;
        this.blur();
        // Карточка с места HUD — на своё место на экране; выезд сдвигает её обратно наверх, без скачка
        this.setBounds(this.x(), this.y(), this.w(), this.h());
    }

    boolean parked()
    {
        return this.parked;
    }

    /** Экран открыт возвратом из мира: панели въезжают на места. */
    void arrive()
    {
        this.worldView = false;
        this.worldT = 1.0F;
        this.worldAt = 0L;
    }

    /** Доехали до мира — экрану пора закрыться. */
    boolean reachedWorld()
    {
        return this.worldView && this.worldT >= 1.0F && ! this.parked;
    }

    /** Панели едут (в мир или обратно): ввод не принимает ничего, даже карточка. */
    boolean moving()
    {
        return ! this.parked && (this.worldView || this.worldT > 0.0F);
    }

    /** Шапка, вкладки и подвал уехали или едут (и при курсоре у карточки): ввод не принимают. */
    boolean chromeHidden()
    {
        return this.worldView || this.worldT > 0.0F;
    }

    /** Насколько уезжает вверх всё над телом — шапка, вкладки и верхняя строка вместе, одним блоком. */
    int upDistance()
    {
        return this.name.y() + ROW_H + Ui.PAD_SECTION;
    }

    /** Доля выезда 0…1 — для шапки, подвала и фона экрана; звать раз в кадр и больше — можно. */
    float worldView()
    {
        long now = System.nanoTime();
        // Первый вызов только засекает время: экран, открытый возвратом из мира, едет с первого кадра, а не с открытия
        float step = this.worldAt == 0L ? 0.0F : (now - this.worldAt) / 1_000_000.0F / WORLD_ANIM_MS;
        this.worldAt = now;
        this.worldT = this.worldView ? Math.min(1.0F, this.worldT + step) : Math.max(0.0F, this.worldT - step);
        float t = this.worldT;
        // Плавно трогается и плавно встаёт (кубическая «ease-in-out»)
        return t < 0.5F ? 4.0F * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 3) / 2.0F;
    }

    /** Виджеты карточки координат: она не уезжает, а поднимается на место HUD. */
    private boolean stays(Widget widget)
    {
        return ! this.slideUp.contains(widget) && ! this.slideLeft.contains(widget) && ! this.slideRight.contains(widget);
    }

    // ===== Данные Litematica =====

    static @Nullable AreaSelection selection()
    {
        try
        {
            return Minecraft.getInstance().level == null ? null : DataManager.getSelectionManager().getCurrentSelection();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    static @Nullable Box selectedBox()
    {
        AreaSelection selection = selection();
        return selection == null ? null : selection.getSelectedSubRegionBox();
    }

    /** Есть что сбрасывать: хоть один угол хоть у одного компонента. */
    static boolean resettable(@Nullable AreaSelection selection)
    {
        return selection != null && selection.getAllSubRegionBoxes().stream().anyMatch(b -> b.getPos1() != null || b.getPos2() != null);
    }

    /**
     * Точка привязки для показа: своя — она; иначе — по углам компонентов; углов нет (после «Сбросить») — {@code null},
     * поля показывают нули (Litematica в этом случае отдаёт заглушку 60000000).
     */
    static @Nullable BlockPos originShown(@Nullable AreaSelection selection)
    {
        if (selection == null)
        {
            return null;
        }

        if (selection.getExplicitOrigin() != null)
        {
            return selection.getExplicitOrigin();
        }

        return resettable(selection) ? selection.getEffectiveOrigin() : null;
    }

    /** Строка под углами: размер и объём выбранного компонента или почему их нет. */
    static String sizeLine(@Nullable AreaSelection selection, @Nullable Box box)
    {
        if (selection == null)
        {
            return Minecraft.getInstance().level == null ? Tr.t("selection.no_world") : Tr.t("selection.none");
        }

        return box == null ? Tr.t("selection.pick_box") : Tr.f("selection.size", sizeText(box), Tr.num(volume(box)));
    }

    private static SelectionMode mode()
    {
        return DataManager.getSelectionManager().getSelectionMode();
    }

    private static int[] size(Box box)
    {
        BlockPos a = box.getPos1();
        BlockPos b = box.getPos2();

        if (a == null || b == null)
        {
            return new int[] { 0, 0, 0 };
        }

        return new int[] { Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1, Math.abs(a.getZ() - b.getZ()) + 1 };
    }

    private static String sizeText(Box box)
    {
        int[] s = size(box);
        return s[0] + " × " + s[1] + " × " + s[2];
    }

    private static long volume(Box box)
    {
        int[] s = size(box);
        return (long) s[0] * s[1] * s[2];
    }

    private static List<BoundingBox> boundingBoxes(@Nullable AreaSelection selection)
    {
        List<BoundingBox> out = new ArrayList<>();

        if (selection != null)
        {
            for (Box box : selection.getAllSubRegionBoxes())
            {
                if (box.getPos1() != null && box.getPos2() != null)
                {
                    out.add(BoundingBox.fromCorners(box.getPos1(), box.getPos2()));
                }
            }
        }

        return out;
    }

    private static int amount()
    {
        int amount = 1;
        amount *= GuiBase.isCtrlDown() ? 100 : 1;
        amount *= GuiBase.isShiftDown() ? 10 : 1;
        return amount;
    }

    private static int component(BlockPos pos, CoordinateType type)
    {
        return switch (type)
        {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static BlockPos with(BlockPos pos, CoordinateType type, int value)
    {
        return switch (type)
        {
            case X -> new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }

    /** Сообщения Litematica (имя занято и т. п.) — тостами экрана. */
    private final IMessageConsumer feedback = new IMessageConsumer()
    {
        @Override
        public void addMessage(Message.MessageType type, String key, Object... args)
        {
            SelectionPane.this.screen.toast(StringUtils.translate(key, args), type == Message.MessageType.ERROR ? Ui.C_DANGER
                    : type == Message.MessageType.WARNING ? Ui.C_WARN : Ui.C_TEXT_2);
        }

        @Override
        public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args)
        {
            this.addMessage(type, key, args);
        }
    };

    // ===== Действия выделения =====

    private void renameSelection()
    {
        AreaSelection selection = selection();
        String value = this.name.text().trim();

        if (selection == null || value.isEmpty() || value.equals(selection.getName()))
        {
            return;
        }

        SelectionManager manager = DataManager.getSelectionManager();

        if (mode() == SelectionMode.NORMAL)
        {
            manager.renameSelection(manager.getCurrentSelectionId(), value, this.feedback);
        }
        else
        {
            SelectionManager.renameSubRegionBoxIfSingle(selection, value);
            selection.setName(value);
        }

        this.shownName = null;
        this.name.setFocused(false);
    }

    /**
     * Режим Litematica — по опции «Составное выделение» (кнопок на вкладке нет). Составного выделения ещё не было —
     * заводим одно в папке выделений Litematica (раз за открытие экрана), как «Новое» в «Все выделения…».
     */
    void syncMode()
    {
        if (Minecraft.getInstance().level == null)
        {
            return;
        }

        SelectionManager manager = DataManager.getSelectionManager();
        SelectionMode target = SchematicsConfig.SELECTION_COMPOUND.getBooleanValue() ? SelectionMode.NORMAL : SelectionMode.SIMPLE;

        if (manager.getSelectionMode() == target)
        {
            return;
        }

        if (target == SelectionMode.NORMAL && ! manager.hasNormalSelection())
        {
            if (this.compoundTried)
            {
                return;
            }

            this.compoundTried = true;

            try
            {
                String name = Tr.t("selection.new_compound");
                manager.setCurrentSelection(manager.createNewSelection(DataManager.getAreaSelectionsBaseDirectory(), name));
                this.screen.toast(Tr.f("selection.mode.created", name), Ui.C_TEXT_2);
            }
            catch (RuntimeException e)
            {
                // Не вышло (папка недоступна) — подскажем путь руками
            }

            if (! manager.hasNormalSelection())
            {
                this.screen.toast(Tr.t("selection.mode.no_normal"), Ui.C_WARN);
                return;
            }
        }

        manager.switchSelectionMode();
        this.signature = 0;
        this.shownName = null;
    }

    private void openManager()
    {
        GuiAreaSelectionManager manager = new GuiAreaSelectionManager();
        manager.setParent(this.screen);
        GuiBase.openGui(manager);
    }

    /**
     * «Сбросить»: выделения в мире больше нет — составное теряет все компоненты, у простого (его компонент Litematica
     * удалить не даёт) углы стираются; точки привязки нет, поля — нули. Блоки в мире не трогаем.
     */
    private void confirmReset()
    {
        if (selection() == null)
        {
            return;
        }

        Modal.confirm(this.screen.layers, Tr.t("selection.reset.title"), Tr.t("selection.reset.text"), Tr.t("selection.reset.yes"),
                true, this::resetSelection, Tr.t("cancel"));
    }

    void resetSelection()
    {
        AreaSelection selection = selection();

        if (selection == null)
        {
            return;
        }

        selection.removeAllSubRegionBoxes();

        for (Box box : selection.getAllSubRegionBoxes())
        {
            selection.setSubRegionCornerPos(box, Corner.CORNER_1, null);
            selection.setSubRegionCornerPos(box, Corner.CORNER_2, null);
            box.setSelectedCorner(Corner.NONE);
        }

        selection.setExplicitOrigin(null);
        selection.setOriginSelected(false);
        this.nextCorner = Corner.CORNER_1;
        this.signature = 0;
    }

    private String freeBoxName(AreaSelection selection)
    {
        String base = Tr.t("selection.new_box");
        String name = base;

        for (int i = 2; selection.getSubRegionBox(name) != null; ++i)
        {
            name = base + " " + i;
        }

        return name;
    }

    private void addBox()
    {
        AreaSelection selection = selection();

        if (selection == null)
        {
            return;
        }

        String name = this.freeBoxName(selection);

        if (DataManager.getSelectionManager().createNewSubRegionIfDoesntExist(name, Minecraft.getInstance(), this.feedback))
        {
            selection.setSelectedSubRegionBox(name);
            this.signature = 0;
        }
    }

    private void withBox(java.util.function.Consumer<Box> action)
    {
        Box box = this.table.selectedItem();

        if (box != null)
        {
            action.accept(box);
        }
    }

    private void renameBox(Box box)
    {
        AreaSelection selection = selection();

        if (selection == null)
        {
            return;
        }

        TextInput input = new TextInput().maxLength(128);
        input.setText(box.getName());
        input.selectAll();
        Modal modal = new Modal(this.screen.layers, Tr.t("selection.rename_box.title"), Strings.t("close"));
        Runnable apply = () ->
        {
            String value = input.text().trim();

            if (! value.isEmpty() && ! value.equals(box.getName()))
            {
                selection.renameSubRegionBox(box.getName(), value, this.feedback);
                this.signature = 0;
            }

            modal.dismiss();
        };
        Button ok = new Button(Tr.t("selection.rename_box.ok"), Button.Style.PRIMARY, apply);
        input.onSubmit(value -> apply.run());
        modal.width(260).row(input, ROW_H).button(ok).defaultButton(ok).open();
        input.setFocused(true);
    }

    private void removeBox(Box box)
    {
        AreaSelection selection = selection();

        if (selection == null)
        {
            return;
        }

        Modal.confirm(this.screen.layers, Tr.t("selection.remove_box.title"), Tr.f("selection.remove_box.text", box.getName()),
                Tr.t("selection.remove_box.yes"), true, () ->
                {
                    selection.removeSubRegionBox(box.getName());
                    this.signature = 0;
                }, Tr.t("cancel"));
    }

    private boolean hasExplicitOrigin()
    {
        AreaSelection selection = selection();
        return selection != null && selection.getExplicitOrigin() != null;
    }

    private void setExplicitOrigin(boolean on)
    {
        AreaSelection selection = selection();

        if (selection != null)
        {
            selection.setExplicitOrigin(on ? selection.getEffectiveOrigin() : null);
        }
    }

    /** «Сохранить схему»: своя плитка (имя и опции Litematica), потом — в «Библиотеку» к новой схеме. */
    private void saveSchematic()
    {
        AreaSelection selection = selection();

        if (selection != null)
        {
            SaveSchematicDialog.open(this.screen, selection);
        }
    }

    /** «Анализ области»: блоки в компонентах по кадрам, потом — списком на вкладке «Материалы». */
    private void analyze()
    {
        AreaSelection selection = selection();
        ClientLevel level = Minecraft.getInstance().level;

        if (selection == null || level == null || this.counting != null)
        {
            return;
        }

        List<BoundingBox> boxes = boundingBoxes(selection);

        if (boxes.isEmpty())
        {
            this.screen.toast(Tr.t("selection.analyze.empty"), Ui.C_WARN);
            return;
        }

        this.counting = AreaCount.start(level, boxes);
    }

    private void pollCount()
    {
        AreaCount job = this.counting;

        if (job == null || ! job.tick(COUNT_BUDGET_NS))
        {
            return;
        }

        this.counting = null;
        AreaSelection selection = selection();
        String title = selection == null ? "" : selection.getName();

        try
        {
            this.screen.showArea(new TableSource.Area(title, SchematicMaterials.ofBlocks(job.counts())));
        }
        catch (RuntimeException e)
        {
            this.screen.toast(SchematicMaterials.describe(e), Ui.C_DANGER);
            return;
        }

        if (job.unloaded() > 0)
        {
            this.screen.toast(Tr.f("selection.analyze.unloaded", Tr.num(job.unloaded())), Ui.C_WARN);
        }
    }

    // ===== Превью области =====

    private void setView3d(boolean value)
    {
        view3d = value;
        this.applyView();
    }

    private void applyView()
    {
        this.preview.setEye(null);

        if (view3d)
        {
            this.preview.setView(PreviewCamera.View.ISOMETRIC);
        }
    }

    private void setQuality(SchematicsConfig.PreviewQuality value)
    {
        if (quality != value)
        {
            quality = value;
            this.preview.setQuality(value);
            this.preview.setBudgetFloor(value == SchematicsConfig.PreviewQuality.QUALITY ? QUADS_QUALITY : QUADS_FAST);
            this.recapture();
        }
    }

    private void onCut(int layer)
    {
        if (this.sliceLayers > 0)
        {
            this.syncSlice();
            this.applySlice();
            return;
        }

        this.preview.setLayerCut(layer >= this.cut.max() ? Integer.MAX_VALUE : layer);
    }

    /** Ползунок не справа — режем по слоям выделения. */
    private boolean slicing()
    {
        return this.sliceLayers > 0 && this.cut.value() < this.sliceLayers;
    }

    /** Что сейчас в превью: снимок коробки выделения (срез) или вся область. */
    private AreaCapture.@Nullable Result shownArea()
    {
        return this.slicing() ? this.slice : this.area;
    }

    /** Коробка всех компонентов выделения; {@code null} — углов нет. */
    private static @Nullable BoundingBox selectionBounds(@Nullable AreaSelection selection)
    {
        BoundingBox bounds = null;

        for (BoundingBox box : boundingBoxes(selection))
        {
            bounds = bounds == null ? box : BoundingBox.encapsulating(bounds, box);
        }

        return bounds;
    }

    /**
     * Ползунок — по слоям выделения (0 — нижний слой, справа — «всё»), без выделения — по всей области. Срез включён и
     * коробка другая — снять её заново.
     */
    private void syncSlice()
    {
        BoundingBox bounds = selectionBounds(selection());

        if (bounds == null)
        {
            if (this.sliceLayers > 0)
            {
                this.sliceLayers = 0;
                this.dropSlice();
                AreaCapture.Result result = this.area;
                int top = result == null ? 0 : Math.max(0, result.snapshot().sizeY() - 1);
                this.cut.setRange(0, top, top);

                if (result != null)
                {
                    this.preview.setSource(result.snapshot(), result);
                }

                this.preview.setLayerCut(Integer.MAX_VALUE);
            }

            return;
        }

        int layers = bounds.getYSpan();

        if (layers != this.sliceLayers || bounds.minY() != this.sliceMinY)
        {
            // Тот же слой от верха, что был (выделение подросло или опустилось), без среза — снова «всё»
            int fromTop = this.slicing() ? this.sliceLayers - this.cut.value() : 0;
            this.sliceLayers = layers;
            this.sliceMinY = bounds.minY();
            this.cut.setRange(0, layers, fromTop > 0 ? Math.max(0, layers - fromTop) : layers);
            this.applySlice();
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (this.slicing() && level != null && ! bounds.equals(this.sliceBox))
        {
            if (this.sliceCapture != null)
            {
                this.sliceCapture.cancel();
            }

            this.sliceBox = bounds;
            this.sliceCapture = AreaCapture.startBox(level, bounds, quality == SchematicsConfig.PreviewQuality.QUALITY ? CELLS_QUALITY : CELLS_FAST);
        }
    }

    /** Источник и срез превью по ползунку: срез — снимок коробки до слоя, иначе вся область целиком. */
    private void applySlice()
    {
        if (this.sliceLayers == 0)
        {
            return;
        }

        AreaCapture.Result result = this.slicing() ? this.slice : this.area;

        if (result == null)
        {
            return;
        }

        this.preview.setSource(result.snapshot(), result);
        this.preview.setLayerCut(this.slicing() ? this.cut.value() / result.scale() : Integer.MAX_VALUE);
    }

    private void pollSlice()
    {
        AreaCapture running = this.sliceCapture;

        if (running == null || ! running.tick(CAPTURE_BUDGET_NS))
        {
            return;
        }

        this.sliceCapture = null;
        AreaCapture.Result result = running.result();

        if (result != null)
        {
            this.slice = result;
            this.applySlice();
        }
    }

    private void dropSlice()
    {
        if (this.sliceCapture != null)
        {
            this.sliceCapture.cancel();
            this.sliceCapture = null;
        }

        this.slice = null;
        this.sliceBox = null;
    }

    /** Снять область заново (мир поменялся, другое качество). */
    private void recapture()
    {
        ClientLevel level = Minecraft.getInstance().level;
        LocalPlayer player = Minecraft.getInstance().player;

        if (level == null || player == null)
        {
            return;
        }

        int radius = Math.min(AreaCapture.MAX_RADIUS, Minecraft.getInstance().options.getEffectiveRenderDistance());
        long cells = quality == SchematicsConfig.PreviewQuality.QUALITY ? CELLS_QUALITY : CELLS_FAST;

        if (this.capture != null)
        {
            this.capture.cancel();
        }

        // Мир или качество поменялись — снимок коробки тоже снять заново
        this.sliceBox = null;
        this.capture = AreaCapture.start(level, player.blockPosition(), radius, cells, boundingBoxes(selection()));
        int r = Math.max(1, radius);
        int ccx = player.blockPosition().getX() >> 4;
        int ccz = player.blockPosition().getZ() >> 4;
        this.map = new AreaMap(level, (ccx - r) * 16, (ccz - r) * 16, (r * 2 + 1) * 16);
        this.mapZoom = -1.0F;
    }

    private void pollCapture()
    {
        AreaCapture running = this.capture;

        if (running == null || ! running.tick(CAPTURE_BUDGET_NS))
        {
            return;
        }

        this.capture = null;
        AreaCapture.Result result = running.result();

        if (result == null)
        {
            return;
        }

        this.area = result;

        // Срез по слоям выделения идёт — его снимок остаётся в превью; ползунок по выделению не трогаем
        if (this.sliceLayers == 0)
        {
            this.preview.setSource(result.snapshot(), result);
            this.cut.setRange(0, Math.max(0, result.snapshot().sizeY() - 1), Math.max(0, result.snapshot().sizeY() - 1));
            this.preview.setLayerCut(Integer.MAX_VALUE);
        }
        else
        {
            this.applySlice();
        }

        this.applyView();
    }

    /** Щелчок по блоку превью: угол по очереди — 1, потом 2. Компонента нет — создаём с этим блоком. */
    private void picked(int[] cell)
    {
        AreaCapture.Result result = this.shownArea();

        if (result != null)
        {
            this.pickedAt(result.toWorld(cell));
        }
    }

    /** Блок мира выбран (превью, карта): угол по очереди — 1, потом 2. Компонента нет — создаём с этим блоком. */
    private void pickedAt(BlockPos pos)
    {
        AreaSelection selection = selection();

        if (selection == null)
        {
            return;
        }

        Box box = selection.getSelectedSubRegionBox();

        if (box == null)
        {
            String name = mode() == SelectionMode.NORMAL ? this.freeBoxName(selection) : selection.getName();
            selection.createNewSubRegionBox(pos, name);
            selection.setSelectedSubRegionBox(name);
            this.nextCorner = Corner.CORNER_2;
            this.signature = 0;
            return;
        }

        selection.setSubRegionCornerPos(box, this.nextCorner, pos);
        this.nextCorner = this.nextCorner == Corner.CORNER_1 ? Corner.CORNER_2 : Corner.CORNER_1;
        this.signature = 0;
    }

    // ===== Кадр =====

    @Override
    void tick()
    {
        // Снимок и подсчёт идут, только пока вкладка на экране (tick зовётся и для скрытых)
        boolean shown = Util.getMillis() - this.renderedAt < 500L;

        if (shown)
        {
            this.syncMode();
        }

        // Курсор у карточки: превью не видно — и снимать область незачем
        if (shown && ! this.parked && this.area == null && this.capture == null && Minecraft.getInstance().level != null)
        {
            this.recapture();
        }

        if (shown)
        {
            this.pollCapture();
            this.syncSlice();
            this.pollSlice();

            // Карта сверху — только когда её видно (2D): иначе это ещё 4 мс каждого кадра впустую
            if (! view3d && this.map != null && ! this.map.done())
            {
                this.map.tick(CAPTURE_BUDGET_NS);
            }
        }

        this.pollCount();

        AreaSelection selection = selection();
        int sig = Objects.hash(System.identityHashCode(selection), mode(), selection == null ? 0 : selection.getAllSubRegions().hashCode(),
                selection == null ? null : selection.getCurrentSubRegionBoxName());

        if (sig != this.signature)
        {
            this.signature = sig;
            List<Box> boxes = selection == null ? List.of() : List.copyOf(selection.getAllSubRegionBoxes());
            this.table.setItems(boxes);
            this.table.setSelected(selection == null ? null : selection.getSelectedSubRegionBox());
            this.table.setEmptyText(Minecraft.getInstance().level == null ? Tr.t("selection.no_world") : Tr.t("selection.empty"));
        }

        String current = selection == null ? "" : selection.getName();

        if (! this.name.focused() && ! current.equals(this.shownName))
        {
            this.name.setText(current);
            this.shownName = current;
        }

        boolean any = selection != null;
        boolean multi = any && mode() == SelectionMode.NORMAL;
        Box box = selectedBox();
        this.name.setEnabled(any);
        this.rename.setEnabled(any);
        this.reset.setEnabled(resettable(selection));
        this.resetCard.setEnabled(resettable(selection));
        // На экране сброс — в верхней строке; на карточке он нужен там, где верхней строки нет: в мире
        this.resetCard.setVisible(this.parked || this.worldView || this.worldT > 0.0F);
        this.add.setEnabled(multi);
        this.renameBox.setEnabled(multi && this.table.selectedItem() != null);
        this.removeBox.setEnabled(multi && this.table.selectedItem() != null);
        this.corner1.refresh(selection, box);
        this.corner2.refresh(selection, box);
        this.origin.refresh(selection, box);
        this.explicitOrigin.setEnabled(any);
        this.save.setEnabled(any);
        this.analyze.setEnabled(any && this.counting == null);
        this.analyze.label(this.counting != null ? Tr.f("selection.analyze.progress", Math.round(this.counting.progress() * 100))
                : Tr.t("selection.analyze"));
        this.mode3d.selected(view3d);
        this.mode2d.selected(! view3d);
        this.qualityHigh.selected(quality == SchematicsConfig.PreviewQuality.QUALITY);
        this.qualityFast.selected(quality == SchematicsConfig.PreviewQuality.OPTIMIZED);
        // Срез по выделению рисует своё превью (Voxy резать не умеет) — ползунок живой и при Voxy
        this.cut.setEnabled(view3d && (this.sliceLayers > 0 ? this.area != null : this.area != null && ! this.voxyShown));
        this.refresh.setEnabled(this.capture == null && Minecraft.getInstance().level != null);
        this.seeWorld.setEnabled(Minecraft.getInstance().level != null);
        this.seeWorld.tooltip(Tr.f("selection.world.tip", SelectionHud.cursorKey()));
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        Font font = Kit.font();
        int left = x + Ui.OUTER;
        int top = y + Ui.PAD_IN;
        int right = x + w - Ui.OUTER;

        // Верхняя строка: имя · «Переименовать» … «Смотреть на мир» · «Все выделения…» · «Сбросить»
        // («Простое / Составное» — в настройках экрана, SchematicsConfig.SELECTION_COMPOUND)
        int resetW = this.reset.preferredWidth(font);
        int allW = this.allSelections.preferredWidth(font);
        int seeW = this.seeWorld.preferredWidth(font);
        int renameW = this.rename.preferredWidth(font);
        int nameW = Math.max(60, Math.min(220, right - left - resetW - allW - seeW - renameW - GAP * 4));
        this.name.setBounds(left, top, nameW, ROW_H);
        this.rename.setBounds(left + nameW + GAP, top, renameW, ROW_H);
        this.reset.setBounds(right - resetW, top, resetW, ROW_H);
        this.allSelections.setBounds(right - resetW - GAP - allW, top, allW, ROW_H);
        this.seeWorld.setBounds(right - resetW - GAP - allW - GAP - seeW, top, seeW, ROW_H);

        int bodyTop = top + ROW_H + Ui.PAD_GROUP;
        int bodyBottom = y + h - Ui.PAD_IN;
        int colW = SelectionHud.cardWidth(w);
        this.colX = right - colW;

        // Справа сверху — карточка координат; ширина та же, что у HUD, и в игре она встаёт на том же краю
        // Режим курсора («Смотреть на мир», клавиша R в игре): карточка сразу на месте HUD — там, где её видели в игре
        SelectionHud.Card hud = SelectionHud.hudCard(w);
        SelectionHud.Card c = this.parked ? hud : SelectionHud.Card.at(this.colX, bodyTop, colW);
        this.card = c;
        this.corner1.layout(c, c.corner1Y());
        this.corner2.layout(c, c.corner2Y());
        this.origin.layout(c, c.originY());
        int toggleW = this.explicitOrigin.preferredWidth(font);
        this.explicitOrigin.setBounds(c.right() - toggleW, c.originCaptionY(), toggleW, SelectionHud.CAPTION_H);
        int cardResetW = this.resetCard.preferredWidth(font);
        this.resetCard.setBounds(c.right() - cardResetW, c.sizeY(), cardResetW, SelectionHud.SIZE_H);

        // Под карточкой — компоненты: список, внизу плитка «Компоненты» с «Добавить / Имя… / Удалить» — видно, что
        // кнопки про список, а не про углы
        int pad = Ui.PAD_IN;
        int cardBottom = c.y() + c.h();
        this.boxesTileH = pad + Ui.TEXT_H + TIGHT + ROW_H + pad;
        this.boxesTileY = Math.max(cardBottom + GAP, bodyBottom - this.boxesTileH);
        int by = this.boxesTileY + pad + Ui.TEXT_H + TIGHT;
        int inner = colW - pad * 2;
        int each = (inner - GAP * 2) / 3;
        this.add.setBounds(this.colX + pad, by, each, ROW_H);
        this.renameBox.setBounds(this.colX + pad + each + GAP, by, each, ROW_H);
        this.removeBox.setBounds(this.colX + pad + (each + GAP) * 2, by, inner - (each + GAP) * 2, ROW_H);
        this.table.setBounds(this.colX, cardBottom + GAP, colW, Math.max(0, this.boxesTileY - GAP - cardBottom - GAP));

        // Слева — превью: панель кнопок, окно превью, строка статуса
        int px = left;
        int pw = Math.max(0, this.colX - Ui.PAD_SECTION - px);
        int bx = px;
        int w3 = this.mode3d.preferredWidth(font);
        int w2 = this.mode2d.preferredWidth(font);
        this.mode3d.setBounds(bx, bodyTop, w3, ROW_H);
        this.mode2d.setBounds(bx + w3, bodyTop, w2, ROW_H);
        bx += w3 + w2 + GAP;
        int qh = this.qualityHigh.preferredWidth(font);
        int qf = this.qualityFast.preferredWidth(font);
        this.qualityHigh.setBounds(bx, bodyTop, qh, ROW_H);
        this.qualityFast.setBounds(bx + qh, bodyTop, qf, ROW_H);
        bx += qh + qf + GAP;
        this.refresh.setBounds(px + pw - ICON_W, bodyTop, ICON_W, ROW_H);
        String cutLabel = Tr.t("selection.cut");
        this.cutLabelX = bx;
        int sliderX = bx + font.width(cutLabel) + Ui.PAD_IN;
        this.cut.setBounds(sliderX, bodyTop + (ROW_H - Ui.CTRL_H) / 2, Math.max(0, px + pw - ICON_W - GAP - sliderX), Ui.CTRL_H);

        this.viewX = px;
        this.viewY = bodyTop + ROW_H + TIGHT;
        this.viewW = pw;
        this.viewH = Math.max(0, bodyBottom - STATUS_H - this.viewY);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.renderedAt = Util.getMillis();
        Font font = Kit.font();
        AreaSelection selection = selection();
        float slide = this.worldView();

        // Пока панели едут (в мир или обратно), ничто не реагирует на курсор
        boolean moving = this.moving();
        int hx = moving ? -1 : mouseX;
        int hy = moving ? -1 : mouseY;

        if (slide < 1.0F)
        {
            int px = this.viewX;
            int dUp = Math.round(-slide * this.upDistance());
            int dLeft = Math.round(-slide * (px + this.viewW + Ui.PAD_SECTION));
            int dRight = Math.round(slide * (this.x() + this.w() - this.colX + Ui.PAD_SECTION));

            ctx.pose().pushMatrix();
            ctx.pose().translate(0, dUp);
            this.renderAll(ctx, this.slideUp, hx, hy);
            ctx.pose().popMatrix();

            // Превью рисуется в свою текстуру по координатам окна, а не через позу — сдвигаем само окно
            this.viewX = px + dLeft;

            try
            {
                this.renderPreview(ctx, font, selection, hx, hy);
            }
            finally
            {
                this.viewX = px;
            }

            ctx.pose().pushMatrix();
            ctx.pose().translate(dLeft, 0);
            Paint.midText(ctx, font, Tr.t("selection.cut"), this.cutLabelX, this.cut.y(), this.cut.h(),
                    this.cut.enabled() ? Ui.C_TEXT_2 : Ui.C_TEXT_OFF, false);
            this.renderAll(ctx, this.slideLeft, hx, hy);
            ctx.pose().popMatrix();

            ctx.pose().pushMatrix();
            ctx.pose().translate(dRight, 0);
            this.renderBoxes(ctx, font, selection, hx, hy);
            ctx.pose().popMatrix();
        }

        // Карточка координат поднимается туда, где в игре встанет HUD (при курсоре она уже там)
        SelectionHud.Card hud = SelectionHud.hudCard(this.screen.width);
        int dx = this.parked ? 0 : Math.round(slide * (hud.x() - this.card.x()));
        int dy = this.parked ? 0 : Math.round(slide * (hud.y() - this.card.y()));
        ctx.pose().pushMatrix();
        ctx.pose().translate(dx, dy);
        this.renderCard(ctx, font, selection, hx, hy);
        ctx.pose().popMatrix();
    }

    private void renderAll(GuiContext ctx, List<Widget> list, int mouseX, int mouseY)
    {
        for (Widget widget : list)
        {
            if (widget.visible())
            {
                widget.render(ctx, mouseX, mouseY);
            }
        }
    }

    /** Список компонентов и плитка «Компоненты» с кнопками — уезжают вправо. */
    private void renderBoxes(GuiContext ctx, Font font, @Nullable AreaSelection selection, int mouseX, int mouseY)
    {
        this.table.render(ctx, mouseX, mouseY);

        int tx = this.table.x();
        int tw = this.table.w();
        Paint.card(ctx, tx, this.boxesTileY, tw, this.boxesTileH, Ui.R_PLATE, Ui.C_ROW_HOVER, Ui.C_DIVIDER);
        boolean multi = selection != null && mode() == SelectionMode.NORMAL;
        String boxesCaption = Tr.t(multi ? "selection.boxes.title" : "selection.boxes.title_simple");
        Paint.midText(ctx, font, Paint.fit(font, boxesCaption, tw - Ui.PAD_IN * 2), tx + Ui.PAD_IN, this.boxesTileY + Ui.PAD_IN,
                Ui.TEXT_H, multi ? Ui.C_TEXT_2 : Ui.C_TEXT_3, false);

        if (mouseX >= tx && mouseX < tx + tw && mouseY >= this.boxesTileY && mouseY < this.boxesTileY + Ui.PAD_IN + Ui.TEXT_H)
        {
            com.aell.layerlist.ui.kit.Tooltip.offer("selection#boxes", Tr.t("selection.boxes.tip"));
        }

        this.renderAll(ctx, this.slideRight, mouseX, mouseY);
    }

    /** Превью области с рамками выделения и строкой статуса — уезжает влево. */
    private void renderPreview(GuiContext ctx, Font font, @Nullable AreaSelection selection, int mouseX, int mouseY)
    {
        Paint.card(ctx, this.viewX, this.viewY, this.viewW, this.viewH, Ui.R_PLATE, 0x60000000, Ui.C_DIVIDER);

        if (view3d)
        {
            if (! this.renderVoxy(ctx, selection))
            {
                this.preview.setExternal(false);
                this.preview.setPicker(null);
                this.preview.render(ctx, this.viewX + 1, this.viewY + 1, this.viewW - 2, this.viewH - 2, mouseX, mouseY);
                this.drawSelection(ctx, selection);
            }
        }
        else
        {
            this.voxyShown = false;
            this.drawMap(ctx, selection);
        }

        this.drawCaptureStatus(ctx, font);
    }

    // ===== 3D движком Voxy (если стоит) =====

    /**
     * 3D-превью видом Voxy — как окно 3D-карты VoxyMap LTS ({@link VoxyPreview}): LOD, которые Voxy уже собрал, без
     * своей модели. Камера — та же орбита виджета (мышь крутит, двигает и масштабирует как обычно): глаз далеко с узким
     * углом обзора, картинка почти как изометрия. Снимок области остаётся — по нему выбор блока щелчком и «Анализ».
     * {@code false} — Voxy нет или не вышло: рисует своё превью.
     */
    private boolean renderVoxy(GuiContext ctx, @Nullable AreaSelection selection)
    {
        AreaCapture.Result result = this.area;
        int x = this.viewX + 1;
        int y = this.viewY + 1;
        int w = this.viewW - 2;
        int h = this.viewH - 2;

        if (result == null || w < 8 || h < 8 || this.slicing() || ! VoxyPreview.ready())
        {
            this.voxyShown = false;
            return false;
        }

        this.preview.setExternal(true);
        this.preview.setPicker(this::voxyPick);
        PreviewCamera.Key key = this.preview.frame(x, y, w, h);
        long now = Util.getMillis();
        boolean fresh = key.equals(this.voxyKey) && w == this.voxyW && h == this.voxyH && now - this.voxyAt < VOXY_IDLE_MS;

        // Камера стоит — новый кадр Voxy не чаще VOXY_IDLE_MS (подтянуть дочитанные LOD), иначе прошлый
        if (! fresh || VoxyPreview.color() == null)
        {
            double[] eye = this.voxyEye(result, key, h);
            int scale = (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));

            if (! VoxyPreview.render(eye[0], eye[1], eye[2], key.yaw(), key.pitch(), VOXY_FOV, w * scale, h * scale))
            {
                this.voxyShown = false;
                return false;
            }

            this.voxyKey = key;
            this.voxyW = w;
            this.voxyH = h;
            this.voxyAt = now;
        }

        com.mojang.blaze3d.textures.GpuTextureView color = VoxyPreview.color();

        if (color == null)
        {
            this.voxyShown = false;
            return false;
        }

        ctx.blit(color, com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(
                com.mojang.blaze3d.textures.FilterMode.LINEAR), x, y, x + w, y + h, 0.0F, 1.0F, 1.0F, 0.0F);
        this.voxyShown = true;
        this.drawSelectionVoxy(ctx, selection);
        return true;
    }

    /**
     * Глаз Voxy для орбиты: точка под серединой окна (центр снимка со сдвигом вида) и отступ к зрителю на такое
     * расстояние, чтобы при угле {@link #VOXY_FOV} по высоте окна было столько же блоков, сколько при ортопроекции.
     */
    private double[] voxyEye(AreaCapture.Result result, PreviewCamera.Key key, int h)
    {
        double pitch = Math.toRadians(key.pitch());
        double turn = Math.toRadians(key.yaw() + 180.0F);
        double sp = Math.sin(pitch);
        double cp = Math.cos(pitch);
        double st = Math.sin(turn);
        double ct = Math.cos(turn);
        double[] right = { ct, 0.0, st };
        double[] up = { sp * st, cp, -sp * ct };
        double[] back = { -cp * st, sp, cp * ct };
        com.aell.layerlist.schematics.library.VoxelSnapshot snapshot = result.snapshot();
        double zoom = Math.max(1.0E-3, key.zoom());
        double s = result.scale();
        double cx = snapshot.sizeX() * 0.5 - right[0] * key.panX() / zoom - up[0] * key.panY() / zoom;
        double cy = snapshot.sizeY() * 0.5 - right[1] * key.panX() / zoom - up[1] * key.panY() / zoom;
        double cz = snapshot.sizeZ() * 0.5 - right[2] * key.panX() / zoom - up[2] * key.panY() / zoom;
        double tx = result.origin().getX() + cx * s;
        double ty = result.origin().getY() + cy * s;
        double tz = result.origin().getZ() + cz * s;
        double halfBlocks = h * 0.5 / zoom * s;
        double dist = halfBlocks / Math.tan(Math.toRadians(VOXY_FOV * 0.5));
        return new double[] { tx + back[0] * dist, ty + back[1] * dist, tz + back[2] * dist };
    }

    /** Точка мира → окно превью по кадру Voxy; за глазом — {@code null}. */
    private float @Nullable [] voxyScreen(double x, double y, double z)
    {
        double[] ndc = VoxyPreview.project(x, y, z);

        if (ndc == null)
        {
            return null;
        }

        int vx = this.viewX + 1;
        int vy = this.viewY + 1;
        return new float[] { (float) (vx + (ndc[0] + 1.0) * 0.5 * (this.viewW - 2)), (float) (vy + (1.0 - ndc[1]) * 0.5 * (this.viewH - 2)) };
    }

    /** Рамки компонентов поверх вида Voxy — как {@link #drawSelection}, только проекция Voxy и мировые координаты. */
    private void drawSelectionVoxy(GuiContext ctx, @Nullable AreaSelection selection)
    {
        if (selection == null)
        {
            return;
        }

        Box selected = selection.getSelectedSubRegionBox();
        int[][] edges = { { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 }, { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };

        for (Box box : selection.getAllSubRegionBoxes())
        {
            if (box.getPos1() == null || box.getPos2() == null)
            {
                continue;
            }

            BoundingBox b = BoundingBox.fromCorners(box.getPos1(), box.getPos2());
            float[][] p = new float[8][];

            for (int i = 0; i < 8; ++i)
            {
                p[i] = this.voxyScreen((i & 1) == 0 ? b.minX() : b.maxX() + 1, (i & 2) == 0 ? b.minY() : b.maxY() + 1,
                        (i & 4) == 0 ? b.minZ() : b.maxZ() + 1);
            }

            int color = box == selected ? 0xFFFFFFFF : 0x90FFFFFF;
            float width = box == selected ? 1.5F : 1.0F;

            for (int[] e : edges)
            {
                if (p[e[0]] != null && p[e[1]] != null)
                {
                    this.clippedLine(ctx, p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1], width, color);
                }
            }

            if (box == selected)
            {
                this.voxyDot(ctx, box.getPos1(), C_CORNER1);
                this.voxyDot(ctx, box.getPos2(), C_CORNER2);
            }
        }
    }

    private void voxyDot(GuiContext ctx, BlockPos pos, int color)
    {
        float[] s = this.voxyScreen(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        if (s != null && this.inView(s[0], s[1]))
        {
            Paint.round(ctx, Math.round(s[0]) - 3, Math.round(s[1]) - 3, 6, 6, 3, color);
        }
    }

    /**
     * Щелчок по виду Voxy: луч из его глаза через точку окна — по снимку области (вода насквозь, как у своего превью).
     * Клетка снимка или {@code null}.
     */
    private int @Nullable [] voxyPick(double mx, double my)
    {
        AreaCapture.Result result = this.area;

        if (result == null)
        {
            return null;
        }

        double ndcX = (mx - (this.viewX + 1)) / (this.viewW - 2) * 2.0 - 1.0;
        double ndcY = 1.0 - (my - (this.viewY + 1)) / (this.viewH - 2) * 2.0;
        double[] ray = VoxyPreview.ray(ndcX, ndcY);

        if (ray == null)
        {
            return null;
        }

        com.aell.layerlist.schematics.library.VoxelSnapshot snapshot = result.snapshot();
        double s = result.scale();
        // В клетках снимка: глаз и направление
        double ox = (ray[0] - result.origin().getX()) / s;
        double oy = (ray[1] - result.origin().getY()) / s;
        double oz = (ray[2] - result.origin().getZ()) / s;
        double[] near = { 0.0 };
        double far = clipToBox(ox, oy, oz, ray[3], ray[4], ray[5], snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(), near);

        if (far < 0.0)
        {
            return null;
        }

        // Шаг в четверть клетки — щелчок, не кадр: точности хватает, а простого обхода — тем более
        for (double t = near[0]; t <= far; t += 0.25)
        {
            int cx = (int) Math.floor(ox + ray[3] * t);
            int cy = (int) Math.floor(oy + ray[4] * t);
            int cz = (int) Math.floor(oz + ray[5] * t);

            if (cx < 0 || cy < 0 || cz < 0 || cx >= snapshot.sizeX() || cy >= snapshot.sizeY() || cz >= snapshot.sizeZ()
                    || snapshot.idAt(cx, cy, cz) == 0)
            {
                continue;
            }

            if (! snapshot.get(cx, cy, cz).getFluidState().isEmpty())
            {
                continue;
            }

            return new int[] { cx, cy, cz };
        }

        return null;
    }

    /** Луч и габарит {@code [0, size)}: дальний параметр выхода или −1 (мимо); {@code near[0]} — вход. */
    private static double clipToBox(double ox, double oy, double oz, double dx, double dy, double dz, int sx, int sy, int sz, double[] near)
    {
        double t0 = 0.0;
        double t1 = Double.MAX_VALUE;
        double[] o = { ox, oy, oz };
        double[] d = { dx, dy, dz };
        int[] size = { sx, sy, sz };

        for (int i = 0; i < 3; ++i)
        {
            if (Math.abs(d[i]) < 1.0E-12)
            {
                if (o[i] < 0.0 || o[i] >= size[i])
                {
                    return -1.0;
                }

                continue;
            }

            double a = (0.0 - o[i]) / d[i];
            double b = (size[i] - o[i]) / d[i];
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
        }

        near[0] = t0;
        return t1 >= t0 ? t1 : -1.0;
    }

    /** Карточка координат: углы, размер, точка привязки и «Смотреть на мир» — она же HUD в игре. */
    private void renderCard(GuiContext ctx, Font font, @Nullable AreaSelection selection, int mouseX, int mouseY)
    {
        SelectionHud.Card c = this.card;
        // Курсор у карточки (parked) — она на месте HUD и в игре выглядит как HUD
        SelectionHud.frame(ctx, font, c, this.parked);

        // Курсор у карточки — те же подсказки, что у HUD: R — в игру, Esc — меню
        if (this.parked)
        {
            SelectionHud.hints(ctx, font, c, true);
        }
        int room = c.w() - SelectionHud.PAD * 2 - (this.resetCard.visible() ? this.resetCard.w() + SelectionHud.GAP : 0);
        Paint.midText(ctx, font, Paint.fit(font, sizeLine(selection, selectedBox()), room), c.inner(), c.sizeY(),
                SelectionHud.SIZE_H, Ui.C_TEXT_2, false);

        // «Точка привязки» (её цветом рисует frame) — с объяснением при наведении
        int ow = font.width(Tr.t("selection.origin.title"));

        if (Paint.hit(mouseX, mouseY, c.inner(), c.originCaptionY(), ow, SelectionHud.CAPTION_H))
        {
            com.aell.layerlist.ui.kit.Tooltip.offer("selection#origin", Tr.t("selection.origin.explain"));
        }

        for (Widget widget : this.widgets)
        {
            if (widget.visible() && this.stays(widget))
            {
                widget.render(ctx, mouseX, mouseY);
            }
        }

        this.corner1.renderLabels(ctx, font);
        this.corner2.renderLabels(ctx, font);
        this.origin.renderLabels(ctx, font);
    }

    private void drawCaptureStatus(GuiContext ctx, Font font)
    {
        AreaCapture running = this.capture;
        String text;

        if (running != null)
        {
            text = Tr.f("selection.capture", Math.round(running.progress() * 100));
            int bar = Math.round((this.viewW - 2) * running.progress());
            Paint.rect(ctx, this.viewX + 1, this.viewY + this.viewH - 3, bar, 2, C_CORNER2);
        }
        else if (this.area == null)
        {
            text = Minecraft.getInstance().level == null ? Tr.t("selection.no_world") : "";
        }
        else
        {
            text = Tr.t(view3d ? "selection.hint.3d" : "selection.hint.2d") + Ui.TITLE_SEPARATOR
                    + Tr.t(this.nextCorner == Corner.CORNER_1 ? "selection.hint.next1" : "selection.hint.next2");
        }

        String status = view3d ? (this.voxyShown ? Tr.t("selection.voxy") : this.preview.status()) : this.map != null && ! this.map.done()
                ? Tr.f("selection.map.building", Math.round(this.map.progress() * 100)) : "";

        // Срез по слоям выделения: какой слой и где он в мире; пока коробка снимается — сколько готово
        if (view3d && this.slicing())
        {
            AreaCapture slicing = this.sliceCapture;
            int layer = this.cut.value();
            status = slicing != null ? Tr.f("selection.slice.capture", Math.round(slicing.progress() * 100))
                    : Tr.f("selection.slice.layer", layer + 1, this.sliceLayers, this.sliceMinY + layer);
        }

        // «Упрощённый вид: схема большая» — здесь это область, и подсказка управления остаётся рядом
        if (status != null && status.equals(Strings.plain("layerlist.schematics.preview.simplified")))
        {
            status = Tr.t("selection.simplified");
        }

        String line = running == null && status != null && ! status.isEmpty() ? status + Ui.TITLE_SEPARATOR + text : text;
        Paint.midText(ctx, font, Paint.fit(font, line, this.viewW), this.viewX, this.viewY + this.viewH, STATUS_H, Ui.C_TEXT_3, false);
    }

    /** Рамки компонентов на превью: выбранный — ярче, его углы — точками цвета угла. */
    private void drawSelection(GuiContext ctx, @Nullable AreaSelection selection)
    {
        AreaCapture.Result result = this.shownArea();

        if (selection == null || result == null || ! this.preview.hasModel())
        {
            return;
        }

        Box selected = selection.getSelectedSubRegionBox();
        int[][] edges = { { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 }, { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };

        for (Box box : selection.getAllSubRegionBoxes())
        {
            if (box.getPos1() == null || box.getPos2() == null)
            {
                continue;
            }

            BoundingBox b = BoundingBox.fromCorners(box.getPos1(), box.getPos2());
            float[] lo = result.toCell(b.minX(), b.minY(), b.minZ());
            float[] hi = result.toCell(b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
            float[][] p = new float[8][];

            for (int i = 0; i < 8; ++i)
            {
                p[i] = this.preview.project((i & 1) == 0 ? lo[0] : hi[0], (i & 2) == 0 ? lo[1] : hi[1], (i & 4) == 0 ? lo[2] : hi[2]);
            }

            int color = box == selected ? 0xFFFFFFFF : 0x90FFFFFF;
            float width = box == selected ? 1.5F : 1.0F;

            for (int[] e : edges)
            {
                if (p[e[0]] != null && p[e[1]] != null)
                {
                    this.clippedLine(ctx, p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1], width, color);
                }
            }

            if (box == selected)
            {
                this.cornerDot(ctx, result, box.getPos1(), C_CORNER1);
                this.cornerDot(ctx, result, box.getPos2(), C_CORNER2);
            }
        }
    }

    private void cornerDot(GuiContext ctx, AreaCapture.Result result, BlockPos pos, int color)
    {
        float[] c = result.toCell(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        float[] s = this.preview.project(c[0], c[1], c[2]);

        if (s != null && s[0] >= this.viewX && s[1] >= this.viewY && s[0] < this.viewX + this.viewW && s[1] < this.viewY + this.viewH)
        {
            Paint.round(ctx, Math.round(s[0]) - 3, Math.round(s[1]) - 3, 6, 6, 3, color);
        }
    }

    /** Отрезок, обрезанный по окну превью (Лианг — Барски). */
    private void clippedLine(GuiContext ctx, float x1, float y1, float x2, float y2, float width, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float t0 = 0.0F;
        float t1 = 1.0F;
        float[] p = { -dx, dx, -dy, dy };
        float[] q = { x1 - this.viewX, this.viewX + this.viewW - x1, y1 - this.viewY, this.viewY + this.viewH - y1 };

        for (int i = 0; i < 4; ++i)
        {
            if (p[i] == 0.0F)
            {
                if (q[i] < 0.0F)
                {
                    return;
                }

                continue;
            }

            float t = q[i] / p[i];

            if (p[i] < 0.0F)
            {
                t0 = Math.max(t0, t);
            }
            else
            {
                t1 = Math.min(t1, t);
            }
        }

        if (t0 > t1)
        {
            return;
        }

        line(ctx, x1 + dx * t0, y1 + dy * t0, x1 + dx * t1, y1 + dy * t1, width, color);
    }

    private static void line(GuiContext ctx, float x1, float y1, float x2, float y2, float width, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);

        if (length < 0.05F)
        {
            return;
        }

        ctx.pose().pushMatrix();
        ctx.pose().translate(x1, y1);
        ctx.pose().rotate((float) Math.atan2(dy, dx));
        ctx.pose().translate(0.0F, -width * 0.5F);
        ctx.pose().scale(length, width);
        ctx.fill(0, 0, 1, 1, color);
        ctx.pose().popMatrix();
    }

    @Override
    List<Button> footerActions()
    {
        return List.of(this.save, this.analyze);
    }

    @Override
    String footerStatus()
    {
        AreaSelection selection = selection();
        return selection == null ? "" : Tr.f("selection.status", selection.getName(), selection.getAllSubRegionNames().size());
    }

    @Override
    void release()
    {
        VoxyPreview.release();
        this.voxyKey = null;

        if (this.capture != null)
        {
            this.capture.cancel();
            this.capture = null;
        }

        this.preview.close();
        this.mapTexture.release();
    }

    // ===== 2D: карта сверху =====

    private boolean mapReady()
    {
        return this.map != null && this.map.done();
    }

    private void fitMap()
    {
        AreaMap m = this.map;

        if (m == null)
        {
            return;
        }

        this.mapZoom = Math.max(0.05F, Math.min((this.viewW - 2) / (float) m.side(), (this.viewH - 2) / (float) m.side()));
        LocalPlayer player = Minecraft.getInstance().player;
        this.mapCenterX = player != null ? player.getX() : m.minX() + m.side() * 0.5;
        this.mapCenterZ = player != null ? player.getZ() : m.minZ() + m.side() * 0.5;
    }

    private float mapScreenX(double worldX)
    {
        return (float) (this.viewX + this.viewW * 0.5 + (worldX - this.mapCenterX) * this.mapZoom);
    }

    private float mapScreenY(double worldZ)
    {
        return (float) (this.viewY + this.viewH * 0.5 + (worldZ - this.mapCenterZ) * this.mapZoom);
    }

    private void drawMap(GuiContext ctx, @Nullable AreaSelection selection)
    {
        AreaMap m = this.map;

        if (m == null || ! m.done())
        {
            return;
        }

        if (this.mapZoom <= 0.0F)
        {
            this.fitMap();
        }

        Identifier tex = this.mapTexture.get(m, m.pixels(), m.side());
        int vx0 = this.viewX + 1;
        int vy0 = this.viewY + 1;
        int vx1 = this.viewX + this.viewW - 1;
        int vy1 = this.viewY + this.viewH - 1;
        Paint.rect(ctx, vx0, vy0, vx1 - vx0, vy1 - vy0, MAP_BG);

        if (tex != null)
        {
            // Видимая часть карты: экранный прямоугольник ∩ карта, UV — доли стороны карты
            double wx0 = this.mapCenterX + (vx0 - (this.viewX + this.viewW * 0.5)) / this.mapZoom;
            double wz0 = this.mapCenterZ + (vy0 - (this.viewY + this.viewH * 0.5)) / this.mapZoom;
            double wx1 = this.mapCenterX + (vx1 - (this.viewX + this.viewW * 0.5)) / this.mapZoom;
            double wz1 = this.mapCenterZ + (vy1 - (this.viewY + this.viewH * 0.5)) / this.mapZoom;
            double cx0 = Math.max(wx0, m.minX());
            double cz0 = Math.max(wz0, m.minZ());
            double cx1 = Math.min(wx1, m.minX() + m.side());
            double cz1 = Math.min(wz1, m.minZ() + m.side());

            if (cx1 > cx0 && cz1 > cz0)
            {
                int sx0 = Math.round(this.mapScreenX(cx0));
                int sy0 = Math.round(this.mapScreenY(cz0));
                int sx1 = Math.round(this.mapScreenX(cx1));
                int sy1 = Math.round(this.mapScreenY(cz1));
                ctx.blit(tex, sx0, sy0, sx1, sy1, (float) ((cx0 - m.minX()) / m.side()), (float) ((cx1 - m.minX()) / m.side()),
                        (float) ((cz0 - m.minZ()) / m.side()), (float) ((cz1 - m.minZ()) / m.side()));
            }
        }

        // Рамки компонентов сверху и игрок
        if (selection != null)
        {
            Box selected = selection.getSelectedSubRegionBox();

            for (Box box : selection.getAllSubRegionBoxes())
            {
                if (box.getPos1() == null || box.getPos2() == null)
                {
                    continue;
                }

                BoundingBox b = BoundingBox.fromCorners(box.getPos1(), box.getPos2());
                float x0 = this.mapScreenX(b.minX());
                float y0 = this.mapScreenY(b.minZ());
                float x1 = this.mapScreenX(b.maxX() + 1);
                float y1 = this.mapScreenY(b.maxZ() + 1);
                int color = box == selected ? 0xFFFFFFFF : 0x90FFFFFF;
                float width = box == selected ? 1.5F : 1.0F;
                this.clippedLine(ctx, x0, y0, x1, y0, width, color);
                this.clippedLine(ctx, x1, y0, x1, y1, width, color);
                this.clippedLine(ctx, x1, y1, x0, y1, width, color);
                this.clippedLine(ctx, x0, y1, x0, y0, width, color);

                if (box == selected)
                {
                    this.mapDot(ctx, box.getPos1(), C_CORNER1);
                    this.mapDot(ctx, box.getPos2(), C_CORNER2);
                }
            }
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player != null)
        {
            int px = Math.round(this.mapScreenX(player.getX()));
            int py = Math.round(this.mapScreenY(player.getZ()));

            if (px > vx0 && py > vy0 && px < vx1 && py < vy1)
            {
                Paint.round(ctx, px - 3, py - 3, 6, 6, 3, 0xFF101014);
                Paint.round(ctx, px - 2, py - 2, 4, 4, 2, 0xFFFFFFFF);
            }
        }
    }

    private void mapDot(GuiContext ctx, BlockPos pos, int color)
    {
        int x = Math.round(this.mapScreenX(pos.getX() + 0.5));
        int y = Math.round(this.mapScreenY(pos.getZ() + 0.5));

        if (this.inView(x, y))
        {
            Paint.round(ctx, x - 3, y - 3, 6, 6, 3, color);
        }
    }

    private boolean mapClicked(double mx, double my, int button)
    {
        this.mapButton = button;
        this.mapPressX = mx;
        this.mapPressY = my;
        this.mapMoved = false;
        return true;
    }

    private boolean mapDragged(double mx, double my, double dx, double dy)
    {
        if (this.mapButton < 0 || this.mapZoom <= 0.0F)
        {
            return false;
        }

        if (! this.mapMoved && Math.abs(mx - this.mapPressX) + Math.abs(my - this.mapPressY) < 3.0)
        {
            return true;
        }

        this.mapMoved = true;
        this.mapCenterX -= dx / this.mapZoom;
        this.mapCenterZ -= dy / this.mapZoom;
        return true;
    }

    private boolean mapReleased(double mx, double my, int button)
    {
        if (this.mapButton != button)
        {
            return false;
        }

        this.mapButton = -1;
        AreaMap m = this.map;

        if (! this.mapMoved && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && m != null && m.done() && this.mapZoom > 0.0F)
        {
            int wx = (int) Math.floor(this.mapCenterX + (mx - (this.viewX + this.viewW * 0.5)) / this.mapZoom);
            int wz = (int) Math.floor(this.mapCenterZ + (my - (this.viewY + this.viewH * 0.5)) / this.mapZoom);
            int wy = m.floorY(wx, wz);

            if (wy != Integer.MIN_VALUE)
            {
                this.pickedAt(new BlockPos(wx, wy, wz));
            }
        }

        return true;
    }

    private boolean mapScrolled(double mx, double my, double amount)
    {
        if (this.mapZoom <= 0.0F)
        {
            return false;
        }

        // Масштаб вокруг курсора: точка под ним остаётся на месте
        double wx = this.mapCenterX + (mx - (this.viewX + this.viewW * 0.5)) / this.mapZoom;
        double wz = this.mapCenterZ + (my - (this.viewY + this.viewH * 0.5)) / this.mapZoom;
        this.mapZoom = (float) Math.max(0.05, Math.min(32.0, this.mapZoom * Math.pow(1.2, amount)));
        this.mapCenterX = wx - (mx - (this.viewX + this.viewW * 0.5)) / this.mapZoom;
        this.mapCenterZ = wz - (my - (this.viewY + this.viewH * 0.5)) / this.mapZoom;
        return true;
    }

    @Override
    @Nullable TextInput focusedInput()
    {
        for (Widget widget : this.widgets)
        {
            if (widget instanceof TextInput input && input.focused())
            {
                return input;
            }
        }

        return null;
    }

    private boolean inView(double mx, double my)
    {
        return mx >= this.viewX && my >= this.viewY && mx < this.viewX + this.viewW && my < this.viewY + this.viewH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        // Панели едут: всё под курсором уже не там, где нарисовано
        if (this.moving())
        {
            return this.contains(click.x(), click.y());
        }

        // Курсор у карточки: живая только она; щелчок мимо — обратно в игру
        if (this.parked)
        {
            return this.parkedClick(click, doubleClick);
        }

        for (Widget widget : this.widgets)
        {
            if (widget instanceof TextInput input && ! input.contains(click.x(), click.y()))
            {
                input.setFocused(false);
            }
        }

        for (Widget widget : this.widgets)
        {
            if (widget.visible() && widget.contains(click.x(), click.y()))
            {
                widget.mouseClicked(click, doubleClick);
                return true;
            }
        }

        if (this.inView(click.x(), click.y()))
        {
            if (view3d)
            {
                this.preview.mouseClicked(click.x(), click.y(), click.button());
            }
            else
            {
                this.mapClicked(click.x(), click.y(), click.button());
            }

            return true;
        }

        if (this.table.contains(click.x(), click.y()))
        {
            this.table.setFocused(true);
            this.table.mouseClicked(click, doubleClick);
            return true;
        }

        return this.contains(click.x(), click.y());
    }

    /** Щелчок при курсоре у карточки: поля и кнопки карточки; мимо карточки — снова игра. */
    private boolean parkedClick(MouseButtonEvent click, boolean doubleClick)
    {
        for (Widget widget : this.widgets)
        {
            if (widget instanceof TextInput input && ! input.contains(click.x(), click.y()))
            {
                input.setFocused(false);
            }
        }

        for (Widget widget : this.widgets)
        {
            if (widget.visible() && this.stays(widget) && widget.contains(click.x(), click.y()))
            {
                widget.mouseClicked(click, doubleClick);
                return true;
            }
        }

        if (! Paint.hit((int) click.x(), (int) click.y(), this.card.x(), this.card.y(), this.card.w(), this.card.h()))
        {
            this.unpark(true);
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (this.moving())
        {
            return false;
        }

        if (this.parked)
        {
            TextInput input = this.focusedInput();
            return input != null && input.mouseDragged(click, dx, dy);
        }

        if (this.cut.mouseDragged(click, dx, dy))
        {
            return true;
        }

        if (view3d ? this.preview.mouseDragged(click.x(), click.y(), click.button(), dx, dy) : this.mapDragged(click.x(), click.y(), dx, dy))
        {
            return true;
        }

        TextInput input = this.focusedInput();
        return input != null ? input.mouseDragged(click, dx, dy) : this.table.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        boolean handled = this.cut.mouseReleased(click);
        handled |= view3d ? this.preview.mouseReleased(click.x(), click.y(), click.button()) : this.mapReleased(click.x(), click.y(), click.button());
        TextInput input = this.focusedInput();
        handled |= input != null ? input.mouseReleased(click) : this.table.mouseReleased(click);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        if (this.moving())
        {
            return false;
        }

        if (this.parked)
        {
            for (CornerBlock block : List.of(this.corner1, this.corner2, this.origin))
            {
                if (block.scrolled(mouseX, mouseY, amount))
                {
                    return true;
                }
            }

            return false;
        }

        if (this.inView(mouseX, mouseY))
        {
            return view3d ? this.preview.mouseScrolled(mouseX, mouseY, amount) : this.mapScrolled(mouseX, mouseY, amount);
        }

        if (this.cut.contains(mouseX, mouseY))
        {
            return this.cut.mouseScrolled(mouseX, mouseY, amount);
        }

        // Колесо над полем координаты — шаг ±1 (Shift ×10, Ctrl ×100)
        for (CornerBlock block : List.of(this.corner1, this.corner2, this.origin))
        {
            if (block.scrolled(mouseX, mouseY, amount))
            {
                return true;
            }
        }

        return this.table.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        TextInput input = this.focusedInput();

        if (input != null)
        {
            return input.keyPressed(key);
        }

        if (this.moving() || this.parked)
        {
            return false;
        }

        this.table.setFocused(true);
        return this.table.keyPressed(key);
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        TextInput input = this.focusedInput();
        return input != null && input.charTyped(event);
    }

    /**
     * Строка карточки — угол компонента ({@code CORNER_1}, {@code CORNER_2}) или точка привязки ({@code NONE}): метка,
     * X Y Z с «+/−» по столбцам карточки, «Двигать в мире» (прицел) и «К игроку» (человечек).
     */
    private final class CornerBlock
    {
        private final Corner corner;
        private final int color;
        private final IconButton grab;
        private final IconButton toPlayer;
        private final TextInput[] fields = new TextInput[3];
        private final AxisNudge[] plus = new AxisNudge[3];
        private @Nullable SelectionHud.Card card;
        private int y;
        private boolean enabled;

        CornerBlock(Corner corner, int color)
        {
            this.corner = corner;
            this.color = color;
            this.grab = new IconButton(Glyphs.TARGET, Tr.t("selection.grab.tip"), Button.Style.SECONDARY, this::toggleGrab);
            this.toPlayer = new IconButton(Glyphs.PLAYER, Tr.t("selection.to_player.tip"), Button.Style.SECONDARY, this::moveToPlayer);

            CoordinateType[] types = CoordinateType.values();

            for (int i = 0; i < 3; ++i)
            {
                CoordinateType type = types[i];
                TextInput input = new TextInput().maxLength(9).filter(c -> Character.isDigit(c) || c == '-');
                input.onChange(text -> this.typed(type, text));
                this.fields[i] = input;
                this.plus[i] = new AxisNudge(i, Tr.t("selection.nudge.tip"), sign -> this.nudge(type, sign * amount()));
            }
        }

        List<Widget> widgets()
        {
            List<Widget> out = new ArrayList<>(List.of(this.grab, this.toPlayer));

            for (int i = 0; i < 3; ++i)
            {
                out.add(this.fields[i]);
                out.add(this.plus[i]);
            }

            return out;
        }

        /** Строка карточки на высоте {@code y}: поля и «+/−» — по столбцам X Y Z, значки — у правого края. */
        void layout(SelectionHud.Card card, int y)
        {
            this.card = card;
            this.y = y;
            this.toPlayer.setBounds(card.toPlayerX(), y, ICON_W, ROW_H);
            this.grab.setBounds(card.grabX(), y, ICON_W, ROW_H);

            for (int i = 0; i < 3; ++i)
            {
                this.fields[i].setBounds(card.fieldX()[i], y, card.fieldW(), ROW_H);
                // Половины «+» и «−» по 9 точек и шов между ними
                this.plus[i].setBounds(card.nudgeX()[i], y + 1, NUDGE_W, ROW_H - 1);
            }
        }

        /** Метка строки: номер угла или зелёный кружок точки привязки (подписи осей — над столбцами карточки). */
        void renderLabels(GuiContext ctx, Font font)
        {
            if (this.card == null)
            {
                return;
            }

            switch (this.corner)
            {
                case CORNER_1 -> SelectionHud.tag(ctx, font, this.card, this.y, "1", this.enabled);
                case CORNER_2 -> SelectionHud.tag(ctx, font, this.card, this.y, "2", this.enabled);
                default -> SelectionHud.originTag(ctx, this.card, this.enabled);
            }
        }

        private @Nullable BlockPos position(@Nullable AreaSelection selection, @Nullable Box box)
        {
            if (selection == null)
            {
                return null;
            }

            if (this.corner == Corner.NONE)
            {
                return originShown(selection);
            }

            return box == null ? null : box.getPosition(this.corner);
        }

        void refresh(@Nullable AreaSelection selection, @Nullable Box box)
        {
            BlockPos pos = this.position(selection, box);
            boolean origin = this.corner == Corner.NONE;
            // Угол стёрт («Сбросить») — поле всё равно правится: число встанет на угол, остальные оси — нули
            this.enabled = origin ? pos != null && selection.getExplicitOrigin() != null : box != null;
            CoordinateType[] types = CoordinateType.values();

            for (int i = 0; i < 3; ++i)
            {
                TextInput input = this.fields[i];
                input.setEnabled(this.enabled);
                this.plus[i].setEnabled(this.enabled);
                // Выделения в мире нет (сброшено) — нули, как в Litematica
                String text = pos == null ? (selection != null ? "0" : "") : String.valueOf(component(pos, types[i]));

                if (! input.focused() && ! text.equals(input.text()))
                {
                    input.setText(text);
                }
            }

            this.toPlayer.setEnabled(this.enabled);
            this.grab.setEnabled(pos != null);
            boolean grabbed = pos != null && (origin ? selection.isOriginSelected() : box != null && box.getSelectedCorner() == this.corner);
            this.grab.style(grabbed ? Button.Style.PRIMARY : Button.Style.SECONDARY).accent(this.color).selected(grabbed);
        }

        boolean scrolled(double mx, double my, double amount)
        {
            CoordinateType[] types = CoordinateType.values();

            for (int i = 0; i < 3; ++i)
            {
                if (this.enabled && this.fields[i].contains(mx, my))
                {
                    this.nudge(types[i], (amount > 0 ? 1 : -1) * amount());
                    return true;
                }
            }

            return false;
        }

        private void typed(CoordinateType type, String text)
        {
            try
            {
                this.set(type, Integer.parseInt(text.trim()));
            }
            catch (NumberFormatException e)
            {
                // Пусто или один минус — ждём, пока допечатают
            }
        }

        private void nudge(CoordinateType type, int delta)
        {
            BlockPos pos = this.position(selection(), selectedBox());
            this.set(type, (pos == null ? 0 : component(pos, type)) + delta);
        }

        private void set(CoordinateType type, int value)
        {
            AreaSelection selection = selection();

            if (selection == null)
            {
                return;
            }

            if (this.corner == Corner.NONE)
            {
                BlockPos explicit = selection.getExplicitOrigin();

                if (explicit != null)
                {
                    selection.setExplicitOrigin(with(explicit, type, value));
                }

                return;
            }

            Box box = selection.getSelectedSubRegionBox();

            if (box != null && box.getPosition(this.corner) == null)
            {
                selection.setSubRegionCornerPos(box, this.corner, with(BlockPos.ZERO, type, value));
            }
            else if (box != null)
            {
                selection.setCoordinate(box, this.corner, type, value);
            }
        }

        private void moveToPlayer()
        {
            AreaSelection selection = selection();
            LocalPlayer player = Minecraft.getInstance().player;

            if (selection == null || player == null)
            {
                return;
            }

            BlockPos pos = player.blockPosition();

            if (this.corner == Corner.NONE)
            {
                selection.setExplicitOrigin(pos);
                return;
            }

            Box box = selection.getSelectedSubRegionBox();

            if (box != null)
            {
                selection.setSubRegionCornerPos(box, this.corner, pos);
            }
        }

        /** Какой угол (или точку привязки) двигают клавиши и инструмент Litematica в мире. */
        private void toggleGrab()
        {
            AreaSelection selection = selection();

            if (selection == null)
            {
                return;
            }

            if (this.corner == Corner.NONE)
            {
                boolean on = ! selection.isOriginSelected();
                selection.setOriginSelected(on);
                selection.clearCurrentSelectedCorner();
                return;
            }

            Box box = selection.getSelectedSubRegionBox();
            selection.setOriginSelected(false);

            if (box != null && box.getSelectedCorner() == this.corner)
            {
                selection.clearCurrentSelectedCorner();
            }
            else
            {
                selection.setCurrentSelectedCorner(this.corner);
                SelectionPane.this.nextCorner = this.corner;
            }
        }
    }
}
