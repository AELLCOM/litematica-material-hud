package com.aell.layerlist.schematics.place;

import com.aell.layerlist.compat.Syncmatica;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsModule;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.schematics.gui.AxisNudge;
import com.aell.layerlist.schematics.gui.PlacementHud;
import com.aell.layerlist.schematics.gui.SelectionHud;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.TextFocus;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.position.PositionUtils;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Редактор размещения (M24): мир виден целиком (без паузы и затемнения), справа панель 190 px в
 * виде aell-ui, снизу строка подсказок клавиш. Схему тащат мышью по миру (за угол рамки — по
 * высоте), двигают клавишами, полями и кнопками «−»/«+», крутят вокруг центра, отражают, ставят на
 * землю и на сетку чанков, смотрят на неё облётом ({@link OrbitCamera}) или сверху
 * ({@link TopViewCamera}).
 *
 * <p>Пока схему тащат (по плоскости, за угол или держат «−»/«+»), двигается только наша рамка
 * ({@link PlacementOverlay}); Litematica получает итог при отпускании — каждое её изменение
 * перестраивает все задетые чанки. С тумблером «Голограммы за мышью» схемы до
 * {@link #LIVE_MAX_BLOCKS} блоков переезжают и по дороге, не чаще 4 раз в секунду (и догоняют рамку,
 * когда мышь замерла с зажатой кнопкой). Схема больше — тумблер выключен, и прямо под ним сказано
 * почему: иначе переключение «ничего не меняло». Вся такая серия — один шаг «Отменить шаг».
 *
 * <p>«Готово»/Enter — оставить как есть; «Отмена»/Esc — вернуть исходные origin, поворот и
 * отражение, а только что созданное размещение убрать. Закрытие мимо этих кнопок (выход из мира,
 * смерть, чужой экран) ничего не откатывает и не применяет недотащенное.
 */
@Environment(EnvType.CLIENT)
public final class PlacementEditorScreen extends GuiBase
{
    static final int PANEL_W = 190;

    private static final String KEY = "layerlist.schematics.place.";

    private static final int HINT_H = Ui.STATUS_H;
    private static final int ICON_BUTTON_W = 22;
    private static final int MESSAGE_LINES = 2;

    /** Ниже этой высоты окна панель сжимается: без заголовков разделов и с узкими зазорами. */
    private static final int ROOMY_HEIGHT = 312;

    private static final int CHUNK = 16;

    /** «Голограммы за мышью»: не чаще 4 раз в секунду и только для схем не больше этого числа блоков. */
    private static final long LIVE_PERIOD_MS = 250L;
    static final long LIVE_MAX_BLOCKS = 200_000L;

    private static final long MESSAGE_MS = 4000L;

    /** Осмотр правой кнопкой: единиц {@code Entity.turn} на пиксель GUI при чувствительности 0. */
    private static final double LOOK_SPEED = 2.0;

    /** Облёт правой кнопкой: градусов на пиксель GUI при чувствительности по умолчанию (50 %). */
    private static final double ORBIT_DEG_PER_PX = 0.4;

    /** Ручка угла ловится, если курсор ближе стольких пикселей GUI к углу рамки на экране. */
    public static final int CORNER_PICK_PX = 6;

    /**
     * Подъём за угол: тянут по вертикальной плоскости через угол, повёрнутой к камере. Если взгляд
     * почти отвесный (горизонтальная часть меньше этой доли) — такой плоскости толком нет, и высота
     * берётся из сдвига мыши по вертикали экрана в пикселях (масштаб — на глубине угла).
     */
    private static final double LIFT_MIN_HORIZONTAL = 0.15;

    private static final String[] ROTATE_CW = Glyphs.ROTATE_CW;
    private static final String[] ROTATE_CCW = Glyphs.ROTATE_CCW;
    private static final String[] MIRROR = Glyphs.MIRROR;

    private static final String[] MINUS = {
            "#####"};

    private static final String[] PLUS = {
            "  #  ",
            "  #  ",
            "#####",
            "  #  ",
            "  #  "};

    private static final String[] AXES = {"x", "y", "z"};

    /** Что сейчас делает зажатая левая кнопка: тащит по плоскости, поднимает за угол, держит «−»/«+». */
    private enum Gesture
    {
        NONE, MOVE, LIFT, STEP
    }

    /** Камера редактора: игрока (осмотр ПКМ), облёт вокруг схемы, строго сверху — в таком порядке по кругу. */
    private enum View
    {
        NORMAL, ORBIT, TOP
    }

    /** Шаг и «по сетке чанков» помнятся между открытиями редактора в этой игре. */
    private static boolean chunkSnap;

    private final SchematicPlacement placement;
    private final boolean createdNow;
    private final boolean startTopView;
    private final EditHistory.State initial;
    private final EditHistory history;
    private final PlacementOps.Feedback feedback = new PlacementOps.Feedback();

    /** Закреплено ли размещение, когда редактор открыли: «Отмена» возвращает и замок. */
    private final boolean initialLocked;

    private boolean opened;
    private boolean closing;
    private boolean liveGhostsChanged;
    private boolean hidHud;

    private Gesture gesture = Gesture.NONE;
    private double dragPlaneY;
    private @Nullable Vec3 grabPoint;
    private PlacementOps.Bounds grabBounds = new PlacementOps.Bounds(0, 0, 0, 0, 0, 0);
    private BlockPos grabOrigin = BlockPos.ZERO;
    private BlockPos dragOrigin = BlockPos.ZERO;
    private long liveAt;

    /** «−»/«+»: какая кнопка держится, по какой оси, в какую сторону и на сколько блоков за срабатывание. */
    private EditorControls.@Nullable HoldButton held;
    private int heldAxis;
    private int heldSign;
    private int heldStep;

    /** Подъём за угол: угол (индекс {@link PlacementOps.Bounds#corner}), плоскость или пиксели. */
    private int liftCorner = -1;
    private boolean liftByPixels;
    private Vec3 liftPoint = Vec3.ZERO;
    private Vec3 liftNormal = Vec3.ZERO;
    private double liftGrabY;
    private double liftMouseY;
    private double liftPerPixel;

    /** Угол рамки под курсором (по последнему кадру); {@code -1} — ни один. */
    private int hoverCorner = -1;

    /** Камерная кнопка мыши (ПКМ/СКМ), пока её держат: осмотр, панорама или вращение облёта. */
    private int cameraButton = -1;
    private boolean panning;
    private boolean looking;
    private boolean orbiting;
    private double lastMouseX;
    private double lastMouseY;

    /** Откуда пришли в вид сверху: T возвращает туда (обычный вид или облёт). */
    private View beforeTop = View.NORMAL;

    /** Кадров нарисовано (только для харнесса: дождаться, пока ввод мыши дойдёт до экрана). */
    private long frames;

    /** Опора серии поворотов/отражений: держится, пока между ними нет других правок. */
    private PlacementOps.@Nullable Pivot pivot;

    private final List<EditorControls.Control> controls = new ArrayList<>();
    private final EditorControls.NumberField[] fields = new EditorControls.NumberField[3];
    private final EditorControls.HoldButton[] minus = new EditorControls.HoldButton[3];
    private final EditorControls.HoldButton[] plus = new EditorControls.HoldButton[3];
    private final EditorControls.Button rotateCcw;
    private final EditorControls.Button rotateCw;
    private final EditorControls.Button mirror;
    private final EditorControls.Cycle layers;
    private final EditorControls.Cycle layerAxis;
    private final EditorControls.NumberField layerValue;
    private final EditorControls.NumberField layerMax;
    private final EditorControls.HoldButton layerMinus;
    private final EditorControls.HoldButton layerPlus;
    private final EditorControls.HoldButton layerMaxMinus;
    private final EditorControls.HoldButton layerMaxPlus;
    private final EditorControls.Button layerHere;
    /** Зажатая «−»/«+» слоя: какое поле (0 — основное, 1 — «до») и в какую сторону. */
    private EditorControls.@Nullable HoldButton layerHeld;
    private int layerHeldField;
    private int layerHeldSign;
    private fi.dy.masa.malilib.util.@Nullable LayerMode laidOutMode;
    private int namesY;
    private final EditorControls.Switch snap;
    private final EditorControls.Switch live;
    private final EditorControls.Note liveNote;
    private final EditorControls.Button toPlayer;
    private final EditorControls.Button toGround;
    private final EditorControls.Button lock;
    private final EditorControls.Button seeWorld;
    private final EditorControls.Cycle viewSwitch;
    private final EditorControls.Button undo;
    private final EditorControls.Button cancel;
    private final EditorControls.Button done;
    private final List<SectionTitle> titles = new ArrayList<>();

    private int panelX;
    private int sizeY;
    private int readoutX;
    private int readoutRight;
    private int readoutY;
    private int messageY;
    private int footerY;

    private @Nullable EditorControls.Control tipTarget;
    private long tipSince;

    /** Откуда открывали редактор — туда ведёт «Готово»/«Отмена», когда экран вернётся из «Смотреть на мир». */
    private @Nullable Screen worldParent;

    private PlacementEditorScreen(SchematicPlacement p, boolean createdNow, boolean topView)
    {
        this.placement = p;
        this.createdNow = createdNow;
        this.startTopView = topView;
        this.initial = EditHistory.State.of(p);
        this.initialLocked = p.isLocked();
        this.history = new EditHistory(this.initial);
        this.title = text("title") + Ui.TITLE_SEPARATOR + p.getName();

        for (int i = 0; i < 3; ++i)
        {
            int axis = i;
            this.fields[i] = new EditorControls.NumberField(text("field." + AXES[i]), () -> this.originAxis(axis),
                    value -> this.setOriginAxis(axis, value)).labelColor(AxisNudge.axisColor(axis))
                    .enabled(() -> this.axisMovable(axis));
            this.fields[i].tip(() -> this.fieldTip(axis));
            // «−»/«+» — тонкой рамкой цвета оси, того же, что у подписи поля
            this.minus[i] = new EditorControls.HoldButton(MINUS).enabled(() -> this.axisMovable(axis))
                    .edge(AxisNudge.axisColor(axis));
            this.minus[i].tip(() -> this.stepTip(axis, "field.minus.tip"));
            this.plus[i] = new EditorControls.HoldButton(PLUS).enabled(() -> this.axisMovable(axis))
                    .edge(AxisNudge.axisColor(axis));
            this.plus[i].tip(() -> this.stepTip(axis, "field.plus.tip"));
        }

        // Поворот и отражение — рамка цвета акцента (подсветка при наведении); у закреплённого размещения серые
        this.rotateCcw = new EditorControls.Button(() -> "", shift -> this.rotate(false, shift)).icon(ROTATE_CCW)
                .edge(Ui.C_ACCENT, false).enabled(this::unlocked);
        this.rotateCcw.tip(() -> this.lockedTip(text("rotate_ccw.tip")));
        this.rotateCw = new EditorControls.Button(() -> "", shift -> this.rotate(true, shift)).icon(ROTATE_CW)
                .edge(Ui.C_ACCENT, false).enabled(this::unlocked);
        this.rotateCw.tip(() -> this.lockedTip(text("rotate_cw.tip")));
        this.mirror = new EditorControls.Button(() -> "", this::mirror).icon(MIRROR)
                .edge(Ui.C_ACCENT, false).enabled(this::unlocked);
        this.mirror.tip(() -> this.lockedTip(text("mirror.tip")));

        // Режим слоёв Litematica (вместо «Шага»): все / один / ниже / выше / диапазон и номер слоя
        this.layers = new EditorControls.Cycle(() -> layerRange().getLayerMode().getDisplayName(), this::cycleLayerMode);
        this.layers.tip(() -> text("layers.tip"));
        this.layerAxis = new EditorControls.Cycle(() -> layerRange().getAxis().getName().toUpperCase(java.util.Locale.ROOT),
                PlacementEditorScreen::cycleLayerAxis);
        this.layerAxis.tip(() -> text("layers.axis.tip"));
        this.layerValue = new EditorControls.NumberField(text("layer"), PlacementEditorScreen::layerValue,
                PlacementEditorScreen::setLayerValue);
        this.layerValue.tip(() -> text("layer.tip"));
        this.layerMax = new EditorControls.NumberField(text("layer.to"), () -> layerRange().getLayerRangeMax(),
                value -> layerRange().setLayerRangeMax(value));
        this.layerMax.tip(() -> text("layer.to.tip"));
        this.layerMinus = new EditorControls.HoldButton(MINUS);
        this.layerPlus = new EditorControls.HoldButton(PLUS);
        this.layerMaxMinus = new EditorControls.HoldButton(MINUS);
        this.layerMaxPlus = new EditorControls.HoldButton(PLUS);
        this.layerMinus.tip(() -> text("layer.minus.tip"));
        this.layerPlus.tip(() -> text("layer.plus.tip"));
        this.layerMaxMinus.tip(() -> text("layer.minus.tip"));
        this.layerMaxPlus.tip(() -> text("layer.plus.tip"));
        this.layerHere = new EditorControls.Button(() -> text("layer.here"), shift -> this.layerToPlayer())
                .edge(Ui.C_ACCENT, false).dashed();
        this.layerHere.tip(() -> text("layer.here.tip"));
        this.snap = new EditorControls.Switch(() -> text("chunk_grid"), () -> chunkSnap, this::toggleChunkSnap)
                .enabled(this::unlocked).keepValue();
        this.snap.tip(() -> this.lockedTip(text("chunk_grid.tip")));
        this.live = new EditorControls.Switch(() -> text("live_ghosts"), SchematicsConfig.LIVE_GHOSTS::getBooleanValue,
                this::toggleLiveGhosts).enabled(() -> ! this.liveTooBig());
        // Подсказка и пометка под тумблером говорят одно и то же: пометка — коротко, подсказка — подробно
        this.live.tip(() -> this.liveTooBig()
                ? text("live_ghosts.tip") + " " + I18n.get(KEY + "live_ghosts.too_big.tip", liveLimit())
                : text("live_ghosts.tip"));
        this.liveNote = new EditorControls.Note(() -> this.liveTooBig()
                ? I18n.get(KEY + "live_ghosts.too_big", liveLimit()) : text("live_ghosts.hint"),
                () -> this.liveTooBig() ? Ui.C_TEXT_2 : Ui.C_TEXT_3);
        // «Ко мне» и «На землю» — пунктир цвета акцента: прыжок в расчётное место
        this.toPlayer = new EditorControls.Button(() -> text("to_player"), shift -> this.toPlayer())
                .edge(Ui.C_ACCENT, false).dashed().enabled(this::unlocked);
        this.toPlayer.tip(() -> this.lockedTip(text("to_player.tip")));
        this.toGround = new EditorControls.Button(() -> text("to_ground"), this::toGround)
                .edge(Ui.C_ACCENT, false).dashed().enabled(this::unlocked);
        this.toGround.tip(() -> this.lockedTip(text("to_ground.tip")));
        this.viewSwitch = new EditorControls.Cycle(() -> text(viewKey(this.view())), this::cycleView)
                .solid(() -> this.view() != View.NORMAL, Ui.C_ACCENT);
        this.viewSwitch.tip(() -> text("view.tip"));
        this.undo = new EditorControls.Button(() -> text("undo"), shift -> this.undo())
                .edge(Ui.C_TEXT_3, false).dashed().enabled(() -> this.history.canUndo() && this.unlocked());
        this.undo.tip(() -> this.lockedTip(text("undo.tip")));
        this.cancel = new EditorControls.Button(() -> text("cancel"), shift -> this.cancel())
                .edge(Ui.C_DANGER, false).dashed();
        this.cancel.tip(() -> text(this.createdNow ? "cancel.tip_new" : "cancel.tip"));
        this.done = new EditorControls.Button(() -> text("done"), shift -> this.done()).accent(Theme.GREEN);
        this.done.tip(() -> text("done.tip"));

        // Замок Litematica: закрытый — сплошной золотой, открытый — золотой пунктир без заливки; общему
        // размещению Syncmatica замок не трогаем — его запирает сама Syncmatica
        this.lock = new EditorControls.Button(() -> "", shift -> this.toggleLock())
                .icon(() -> this.placement.isLocked() ? Glyphs.LOCK : Glyphs.UNLOCK)
                .accent(() -> this.placement.isLocked() ? Theme.GOLD : 0)
                .edge(Theme.GOLD, true).dashed(() -> ! this.placement.isLocked())
                .enabled(() -> ! Syncmatica.shared(this.placement));
        this.lock.tip(() -> Syncmatica.shared(this.placement) ? text("lock.shared.tip")
                : text(this.placement.isLocked() ? "lock.on.tip" : "lock.off.tip"));

        // «Смотреть на мир» — сплошной акцент с глазом, как на вкладке «Выделение»
        this.seeWorld = new EditorControls.Button(() -> text("world"), shift -> this.enterWorld())
                .icon(Glyphs.EYE).accent(Ui.C_ACCENT).enabled(() -> this.mc != null && this.mc.level != null);
        this.seeWorld.tip(() -> I18n.get(KEY + "world.tip", SelectionHud.cursorKey()));
    }

    /**
     * @param parent     куда вернуться по «Готово»/«Отмена»
     * @param createdNow размещение только что создано: «Отмена» его удаляет
     * @param topView    открыть сразу в виде сверху
     */
    public static void open(@Nullable Screen parent, SchematicPlacement p, boolean createdNow, boolean topView)
    {
        PlacementOverlay.register();

        // Новый редактор — прежний «Смотреть на мир» (если был) закончен
        PlacementHud.leave();

        PlacementEditorScreen screen = new PlacementEditorScreen(p, createdNow, topView);
        screen.setParent(parent);
        Nav.open(screen, parent);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        this.panelX = Math.max(0, this.width - PANEL_W);
        this.layoutPanel();
        PlacementOverlay.attach(this);

        // Окно сменило размер или масштаб GUI: опорная точка облёта — снова в центр свободной части
        if (OrbitCamera.active())
        {
            OrbitCamera.area(0, 0, this.panelX, this.height - HINT_H, this.width, this.height);
        }

        // Панель быстрого доступа, прицел, рука и виньетка под строкой подсказок и над видом сверху
        // только мешают: на время редактора HUD прячется, как по F1, и возвращается в removed(). Каждый показ
        // экрана заново: из «Смотреть на мир» редактор возвращается тем же экземпляром, а HUD к тому времени
        // уже вернули; смена размера окна его не трогает — он и так спрятан
        // 1.21.11: F1 — это флаг options.hideGui (в 26.x — Hud.toggle)
        if (! this.mc.options.hideGui)
        {
            this.mc.options.hideGui = true;
            this.hidHud = true;
        }

        // Вид сверху «с порога» — только при самом первом показе, не при возвращении из мира
        if (! this.opened)
        {
            this.opened = true;

            if (this.startTopView)
            {
                this.setView(View.TOP);
            }
        }
    }

    private void layoutPanel()
    {
        this.controls.clear();
        this.titles.clear();

        boolean roomy = this.height >= ROOMY_HEIGHT;
        int gap = roomy ? Ui.PAD_REL : Ui.PAD_IN;
        int x = this.panelX + Ui.OUTER;
        int w = PANEL_W - Ui.OUTER * 2;
        int half = (w - Ui.PAD_IN) / 2;

        this.footerY = this.height - Ui.FOOTER_H;

        int y = Ui.HEADER_H + Ui.PAD_IN;
        // Имя схемы и файла — двумя строками, длинное прокручивается в пределах строки
        this.namesY = y;
        y += (Ui.TEXT_H + Ui.PAD_MICRO) * 2;
        this.sizeY = y;
        y += Ui.TEXT_H + gap;

        int positionTitleY = y;
        y = this.sectionTitle(roomy, "section.position", y);

        // Замок — справа в строке заголовка «Позиция»; в тесном окне заголовков нет — он в конце строки поворота
        if (roomy)
        {
            this.add(this.lock.at(x + w - ICON_BUTTON_W, positionTitleY, ICON_BUTTON_W, Ui.SECTION_H));
        }

        int fw = (w - Ui.PAD_IN * 2) / 3;
        int stepperY = y + Ui.CTRL_H + Ui.PAD_MICRO;

        // Три поля в ряд, как раньше; «−» и «+» — под каждым, на всю его ширину пополам: внутри поля
        // 55 px им места нет — число в шесть знаков (−34000) уже не влезло бы
        for (int i = 0; i < 3; ++i)
        {
            int fx = x + i * (fw + Ui.PAD_IN);
            int fieldW = i == 2 ? w - 2 * (fw + Ui.PAD_IN) : fw;
            int halfW = (fieldW - Ui.PAD_MICRO) / 2;

            this.add(this.fields[i].at(fx, y, fieldW, Ui.CTRL_H));
            this.add(this.minus[i].at(fx, stepperY, halfW, Ui.HIT_MIN));
            this.add(this.plus[i].at(fx + halfW + Ui.PAD_MICRO, stepperY, fieldW - halfW - Ui.PAD_MICRO, Ui.HIT_MIN));
        }

        y = stepperY + Ui.HIT_MIN + gap;
        y = this.sectionTitle(roomy, "section.transform", y);
        this.add(this.rotateCcw.at(x, y, ICON_BUTTON_W, Ui.CTRL_H));
        this.add(this.rotateCw.at(x + ICON_BUTTON_W + Ui.PAD_MICRO, y, ICON_BUTTON_W, Ui.CTRL_H));
        this.add(this.mirror.at(x + (ICON_BUTTON_W + Ui.PAD_MICRO) * 2, y, ICON_BUTTON_W, Ui.CTRL_H));
        this.readoutX = x + (ICON_BUTTON_W + Ui.PAD_MICRO) * 3 + Ui.PAD_IN;
        this.readoutRight = x + w;
        this.readoutY = y;

        if (! roomy)
        {
            this.add(this.lock.at(x + w - ICON_BUTTON_W, y, ICON_BUTTON_W, Ui.CTRL_H));
            this.readoutRight = x + w - ICON_BUTTON_W - Ui.PAD_IN;
        }

        y += Ui.CTRL_H + gap;

        // Слои: режим во всю ширину (рядом — ось), под ним поля режима с «−»/«+» и «Ко мне». Без заголовка раздела:
        // подпись режима сама говорит, что это, а высота панели на счету
        int axisW = 30;
        this.add(this.layers.at(x, y, w - axisW - Ui.PAD_MICRO, Ui.CTRL_H));
        this.add(this.layerAxis.at(x + w - axisW, y, axisW, Ui.CTRL_H));
        y += Ui.CTRL_H + Ui.PAD_MICRO;
        fi.dy.masa.malilib.util.LayerMode mode = layerRange().getLayerMode();
        this.laidOutMode = mode;
        int stepW = 20;
        int hereW = this.font.width(text("layer.here")) + Ui.PAD_IN * 2;

        if (mode != fi.dy.masa.malilib.util.LayerMode.ALL)
        {
            boolean range = mode == fi.dy.masa.malilib.util.LayerMode.LAYER_RANGE;
            this.layerValue.label(text(switch (mode)
            {
                case ALL_BELOW -> "layer.below";
                case ALL_ABOVE -> "layer.above";
                case LAYER_RANGE -> "layer.from";
                default -> "layer";
            }));
            int fieldW = w - stepW * 2 - Ui.PAD_MICRO * 2 - (range ? 0 : hereW + Ui.PAD_MICRO);
            this.add(this.layerValue.at(x, y, fieldW, Ui.CTRL_H));
            this.add(this.layerMinus.at(x + fieldW + Ui.PAD_MICRO, y, stepW, Ui.CTRL_H));
            this.add(this.layerPlus.at(x + fieldW + Ui.PAD_MICRO * 2 + stepW, y, stepW, Ui.CTRL_H));

            if (! range)
            {
                this.add(this.layerHere.at(x + w - hereW, y, hereW, Ui.CTRL_H));
            }

            y += Ui.CTRL_H + Ui.PAD_MICRO;

            if (range)
            {
                int maxW = w - stepW * 2 - Ui.PAD_MICRO * 3 - hereW;
                this.add(this.layerMax.at(x, y, maxW, Ui.CTRL_H));
                this.add(this.layerMaxMinus.at(x + maxW + Ui.PAD_MICRO, y, stepW, Ui.CTRL_H));
                this.add(this.layerMaxPlus.at(x + maxW + Ui.PAD_MICRO * 2 + stepW, y, stepW, Ui.CTRL_H));
                this.add(this.layerHere.at(x + w - hereW, y, hereW, Ui.CTRL_H));
                y += Ui.CTRL_H + Ui.PAD_MICRO;
            }
        }

        y += gap - Ui.PAD_MICRO;
        this.add(this.snap.at(x, y, w, Ui.CTRL_H));
        y += Ui.CTRL_H + Ui.ROW_GAP;
        this.add(this.live.at(x, y, w, Ui.CTRL_H));
        y += Ui.CTRL_H;
        int noteH = this.liveNote.height(this.font, w);
        this.add(this.liveNote.at(x, y, w, noteH));
        y += noteH + gap;

        this.add(this.seeWorld.at(x, y, w, Ui.BUTTON_H));
        y += Ui.BUTTON_H + Ui.PAD_IN;
        this.add(this.toPlayer.at(x, y, half, Ui.BUTTON_H));
        this.add(this.toGround.at(x + half + Ui.PAD_IN, y, w - half - Ui.PAD_IN, Ui.BUTTON_H));
        y += Ui.BUTTON_H + Ui.PAD_IN;
        this.add(this.viewSwitch.at(x, y, half, Ui.BUTTON_H));
        this.add(this.undo.at(x + half + Ui.PAD_IN, y, w - half - Ui.PAD_IN, Ui.BUTTON_H));
        y += Ui.BUTTON_H + gap;
        this.messageY = y;

        int by = this.footerY + (Ui.FOOTER_H - Ui.BUTTON_H) / 2;
        this.add(this.cancel.at(x, by, half, Ui.BUTTON_H));
        this.add(this.done.at(x + half + Ui.PAD_IN, by, w - half - Ui.PAD_IN, Ui.BUTTON_H));

        // Совсем низкое окно: что не влезло над подвалом, прячем — клавиши работают и без кнопок
        for (EditorControls.Control control : this.controls)
        {
            if (control != this.cancel && control != this.done && control.y + control.h > this.footerY - Ui.PAD_IN)
            {
                control.visible = false;
            }
        }
    }

    private int sectionTitle(boolean roomy, String key, int y)
    {
        if (! roomy)
        {
            return y;
        }

        this.titles.add(new SectionTitle(key, y));

        return y + Ui.SECTION_H;
    }

    private void add(EditorControls.Control control)
    {
        this.controls.add(control);
    }

    @Override
    public void tick()
    {
        super.tick();

        // Размещение убрали снаружи (меню Litematica, хоткей): редактировать больше нечего
        if (! this.closing && ! PlacementOps.exists(this.placement))
        {
            this.closing = true;
            this.leave();
            return;
        }

        // Мышь замерла с зажатой кнопкой: события перетаскивания не идут, а последний шаг мог
        // попасть в паузу между обновлениями — голограммы догоняют рамку здесь
        if (this.gesture != Gesture.NONE)
        {
            this.liveStep();
        }
    }

    @Override
    public void removed()
    {
        try
        {
            // Закрыли мимо «Готово»/«Отмены»: недотащенное не применяем, остальное — как есть.
            // Недонабранное число тоже: поле выходит из ввода и отпускает ввод текста SDL
            this.gesture = Gesture.NONE;
            this.releaseHeld();
            this.liftCorner = -1;
            this.stopCamera();

            EditorControls.NumberField field = this.editingField();

            if (field != null)
            {
                field.cancel();
            }

            if (this.liveGhostsChanged)
            {
                this.liveGhostsChanged = false;
                new LayerListConfigs().save();
            }
        }
        finally
        {
            if (this.hidHud)
            {
                this.hidHud = false;

                if (this.mc.options.hideGui)
                {
                    this.mc.options.hideGui = false;
                }
            }

            TopViewCamera.disable(this);
            OrbitCamera.disable(this);
            PlacementOverlay.detach(this);
            super.removed();
        }
    }

    // ---- состояние для рамки в мире ----

    /** Габарит, каким его видит игрок: во время перетаскивания — сдвинутый на ещё не применённое. */
    PlacementOps.@Nullable Bounds previewBounds()
    {
        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null || this.gesture == Gesture.NONE)
        {
            return b;
        }

        BlockPos origin = this.placement.getOrigin();

        return b.offset(this.dragOrigin.getX() - origin.getX(), this.dragOrigin.getY() - origin.getY(),
                this.dragOrigin.getZ() - origin.getZ());
    }

    boolean movingSchematic()
    {
        return this.gesture != Gesture.NONE;
    }

    /** Размещение закреплено: рамка золотая, тащить нечего. */
    boolean frameLocked()
    {
        return this.placement.isLocked();
    }

    boolean chunkSnap()
    {
        return chunkSnap;
    }

    /** Ручки углов видны, пока схему не тащат по плоскости и не держат «−»/«+»: тогда они только мешают. */
    boolean cornerHandles()
    {
        return this.gesture == Gesture.NONE || this.gesture == Gesture.LIFT;
    }

    /** Подсвеченный угол: тот, за который поднимают, иначе — под курсором; {@code -1} — ни один. */
    int highlightedCorner()
    {
        return this.gesture == Gesture.LIFT ? this.liftCorner : this.hoverCorner;
    }

    // ---- правки ----

    private View view()
    {
        return TopViewCamera.active() ? View.TOP : OrbitCamera.active() ? View.ORBIT : View.NORMAL;
    }

    private boolean topView()
    {
        return TopViewCamera.active();
    }

    /** Число в поле X/Y/Z: во время серии — то, что показывает рамка (голограммы могут отставать или ждать). */
    private int originAxis(int axis)
    {
        BlockPos origin = this.gesture == Gesture.NONE ? this.placement.getOrigin() : this.dragOrigin;

        return axis == 0 ? origin.getX() : axis == 1 ? origin.getY() : origin.getZ();
    }

    /** Целые числа из текста по порядку («-4781, 207 -4925» → три числа); лишний мусор пропускается. */
    static int[] numbersIn(@Nullable String text)
    {
        if (text == null)
        {
            return new int[0];
        }

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[-−]?\\d{1,9}").matcher(text.replace('\uFF0D', '-'));
        it.unimi.dsi.fastutil.ints.IntArrayList out = new it.unimi.dsi.fastutil.ints.IntArrayList();

        while (m.find())
        {
            out.add(Integer.parseInt(m.group().replace('−', '-')));
        }

        return out.toIntArray();
    }

    /** Вставить положение «X Y Z» одной правкой (одним шагом отмены). */
    private void pasteOrigin(int[] xyz)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        this.finishGesture(true);
        this.setOriginAxis(0, xyz[0]);
        this.setOriginAxis(1, xyz[1]);
        this.setOriginAxis(2, xyz[2]);
        this.feedback.info(KEY + "msg.pasted", xyz[0] + " " + xyz[1] + " " + xyz[2]);
    }

    private void setOriginAxis(int axis, int value)
    {
        BlockPos o = this.placement.getOrigin();

        // То же число — не правка; закреплённое размещение и закреплённая ось говорят об этом, а не молчат
        if (value == (axis == 0 ? o.getX() : axis == 1 ? o.getY() : o.getZ()) || this.axisRefused(axis))
        {
            return;
        }

        BlockPos target = axis == 0 ? new BlockPos(value, o.getY(), o.getZ())
                : axis == 1 ? new BlockPos(o.getX(), value, o.getZ())
                : new BlockPos(o.getX(), o.getY(), value);

        this.commitOrigin(target, "field" + axis);
    }

    /** Ось править нельзя (размещение или эта ось закреплены в Litematica): сказать об этом в панели. */
    private boolean axisRefused(int axis)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return true;
        }

        if (this.lockedAxis(axis))
        {
            this.feedback.error(KEY + "msg.axis_locked", text("field." + AXES[axis]));

            return true;
        }

        return false;
    }

    private void commitOrigin(BlockPos origin, @Nullable String mergeKind)
    {
        PlacementOps.moveTo(this.placement, origin, this.feedback);
        this.afterEdit(mergeKind);
    }

    private void afterEdit(@Nullable String mergeKind)
    {
        this.history.record(EditHistory.State.of(this.placement), mergeKind);
        this.pivot = null;
        this.followCamera();
    }

    private void followCamera()
    {
        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null)
        {
            return;
        }

        if (this.topView())
        {
            TopViewCamera.follow(b);
            // Стрелки, поворот, сетка чанков могут увести схему под панель или за край — камера догоняет
            TopViewCamera.keepInView(b, 0, 0, this.panelX, this.height - HINT_H, this.width, this.height);
        }
        else if (OrbitCamera.active() && ! this.inView(b))
        {
            // Облёт не ездит за схемой на каждом шаге (тогда двигался бы мир, а не она), но и
            // потерять её не даёт: ушла из кадра — опорная точка встаёт в её центр
            OrbitCamera.recenter(b);
        }
    }

    /** Центр габарита виден в свободной части окна. */
    private boolean inView(PlacementOps.Bounds b)
    {
        double[] at = CursorRay.project(b.center(), this.width, this.height);

        return at != null && at[0] >= 0.0 && at[0] < this.panelX && at[1] >= 0.0 && at[1] < this.height - HINT_H;
    }

    private void nudge(int dx, int dy, int dz, @Nullable String mergeKind)
    {
        if (dx == 0 && dy == 0 && dz == 0)
        {
            return;
        }

        this.commitOrigin(this.placement.getOrigin().offset(dx, dy, dz), mergeKind);
    }

    /** Шаг кнопок −/+ и клавиш — 1 блок («Шаг» убран; держать кнопку — повтор). */
    private int step()
    {
        return 1;
    }

    private static fi.dy.masa.malilib.util.LayerRange layerRange()
    {
        return fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
    }

    private void cycleLayerMode(int direction)
    {
        var values = fi.dy.masa.malilib.util.LayerMode.VALUES;
        var range = layerRange();
        int i = values.indexOf(range.getLayerMode());
        range.setLayerMode(values.get(Math.floorMod(i + direction, values.size())));
        // Другой режим — другие поля под ним
        this.layoutPanel();
    }

    private static void cycleLayerAxis(int direction)
    {
        net.minecraft.core.Direction.Axis[] axes = net.minecraft.core.Direction.Axis.values();
        var range = layerRange();
        range.setAxis(axes[Math.floorMod(range.getAxis().ordinal() + direction, axes.length)]);
    }

    /** «Ко мне»: граница слоя (или диапазон) — на уровень игрока по выбранной оси, как клавиша Litematica. */
    private void layerToPlayer()
    {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;

        if (player != null)
        {
            layerRange().setToPosition(player);
        }
    }

    /** Шаг «−»/«+» слоя: поле 0 — основное значение режима, 1 — «до» у диапазона. */
    private static void stepLayer(int field, int delta)
    {
        if (field == 1)
        {
            layerRange().setLayerRangeMax(layerRange().getLayerRangeMax() + delta);
        }
        else
        {
            setLayerValue(layerValue() + delta);
        }
    }

    private boolean pressLayerHold(EditorControls.HoldButton hold)
    {
        int field = hold == this.layerMaxMinus || hold == this.layerMaxPlus ? 1 : 0;
        int sign = hold == this.layerPlus || hold == this.layerMaxPlus ? 1 : hold == this.layerMinus || hold == this.layerMaxMinus ? -1 : 0;

        if (sign == 0)
        {
            return false;
        }

        this.layerHeld = hold;
        this.layerHeldField = field;
        this.layerHeldSign = sign;
        hold.press(Util.getMillis());
        stepLayer(field, sign);
        return true;
    }

    /** Раз в кадр: повторы зажатой «−»/«+» слоя; курсор ушёл с кнопки — хватит. */
    private void updateLayerHold(int mouseX, int mouseY)
    {
        EditorControls.HoldButton hold = this.layerHeld;

        if (hold == null)
        {
            return;
        }

        if (! hold.over(mouseX, mouseY))
        {
            hold.release();
            this.layerHeld = null;
            return;
        }

        int due = hold.due(Util.getMillis());

        if (due > 0)
        {
            stepLayer(this.layerHeldField, due * this.layerHeldSign);
        }
    }

    private static int layerValue()
    {
        var range = layerRange();

        return switch (range.getLayerMode())
        {
            case SINGLE_LAYER -> range.getLayerSingle();
            case ALL_ABOVE -> range.getLayerAbove();
            case ALL_BELOW -> range.getLayerBelow();
            case LAYER_RANGE -> range.getLayerRangeMin();
            default -> 0;
        };
    }

    private static void setLayerValue(int value)
    {
        var range = layerRange();

        switch (range.getLayerMode())
        {
            case SINGLE_LAYER -> range.setLayerSingle(value);
            case ALL_ABOVE -> range.setLayerAbove(value);
            case ALL_BELOW -> range.setLayerBelow(value);
            case LAYER_RANGE -> range.setLayerRangeMin(value);
            default -> { }
        }
    }

    /** По сетке чанков сдвиг по горизонтали — целыми чанками, иначе выравнивание бы сбивалось. */
    private int horizontalStep()
    {
        int s = this.step();

        return chunkSnap ? Math.max(CHUNK, s / CHUNK * CHUNK) : s;
    }

    /** Шаг «−»/«+» по оси: по высоте — как PgUp/PgDn, по горизонтали — как стрелки. */
    private int axisStep(int axis)
    {
        return axis == 1 ? this.step() : this.horizontalStep();
    }

    /** Стрелки: в виде сверху «вверх» = север, в обычном и облёте — куда смотрит камера (по стороне света). */
    private void arrow(int key)
    {
        int fx = 0;
        int fz = -1;
        LocalPlayer player = this.mc.player;

        if (OrbitCamera.active())
        {
            Direction facing = Direction.fromYRot(OrbitCamera.yaw());
            fx = facing.getStepX();
            fz = facing.getStepZ();
        }
        else if (! this.topView() && player != null)
        {
            Direction facing = player.getDirection();
            fx = facing.getStepX();
            fz = facing.getStepZ();
        }

        int s = this.horizontalStep();

        // «Вправо» — по часовой от «вперёд»: (fx, fz) → (−fz, fx)
        switch (key)
        {
            case InputConstants.KEY_UP -> this.nudge(fx * s, 0, fz * s, null);
            case InputConstants.KEY_DOWN -> this.nudge(-fx * s, 0, -fz * s, null);
            case InputConstants.KEY_RIGHT -> this.nudge(-fz * s, 0, fx * s, null);
            case InputConstants.KEY_LEFT -> this.nudge(fz * s, 0, -fx * s, null);
            default -> {}
        }
    }

    private void rotate(boolean clockwise, boolean aroundCorner)
    {
        Rotation next = this.placement.getRotation()
                .getRotated(clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);

        this.transform(next, this.placement.getMirror(), aroundCorner);
    }

    private void mirror(boolean aroundCorner)
    {
        this.transform(this.placement.getRotation(), PlacementOps.nextMirror(this.placement.getMirror()), aroundCorner);
    }

    /** Поворот/отражение вокруг центра габарита (опора держится на всю серию) или вокруг origin. */
    private void transform(Rotation rotation, Mirror mirror, boolean aroundCorner)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);
        PlacementOps.Pivot pv = aroundCorner || b == null ? null : this.pivot != null ? this.pivot : b.pivot();

        PlacementOps.transform(this.placement, rotation, mirror, pv, this.feedback);
        this.history.record(EditHistory.State.of(this.placement));
        this.pivot = pv;
        this.followCamera();
    }

    private void toGround(boolean lowest)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);
        Integer ground = b == null ? null : PlacementOps.groundY(b, ! lowest);

        if (b == null || ground == null)
        {
            this.feedback.error(KEY + "msg.no_ground");

            return;
        }

        this.nudge(0, ground - b.minY(), 0, null);
    }

    private void toPlayer()
    {
        BlockPos origin = PlacementOps.originInFront(this.placement);

        if (origin == null || PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        this.commitOrigin(origin, null);

        // Схема уехала к игроку, возможно далеко: вид сверху встаёт над ней заново, облёт — смотрит на неё
        if (this.topView())
        {
            this.setView(View.TOP);
        }
        else if (OrbitCamera.active())
        {
            this.frameView();
        }
    }

    private void toggleChunkSnap()
    {
        // Тумблер у закреплённого размещения серый, и клавиша C его тоже не переключает
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        chunkSnap = ! chunkSnap;

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (! chunkSnap || b == null || this.placement.isLocked())
        {
            return;
        }

        // Включили — сразу прилепить мин. угол к ближайшей границе чанка
        this.nudge(this.lockedX() ? 0 : snapShift(b.minX(), 0.0), 0, this.lockedZ() ? 0 : snapShift(b.minZ(), 0.0),
                null);
    }

    private void toggleLiveGhosts()
    {
        SchematicsConfig.LIVE_GHOSTS.setBooleanValue(! SchematicsConfig.LIVE_GHOSTS.getBooleanValue());
        this.liveGhostsChanged = true;
    }

    /** T: вид сверху и обратно — в тот вид, из которого пришли (обычный или облёт). */
    private void toggleTopView()
    {
        this.setView(this.topView() ? this.beforeTop : View.TOP);
    }

    /** O: облёт и обратно в обычный вид. */
    private void toggleOrbit()
    {
        this.setView(OrbitCamera.active() ? View.NORMAL : View.ORBIT);
    }

    /** Кнопка вида в панели: обычный → облёт → сверху → обычный (назад — в обратном порядке). */
    private void cycleView(int direction)
    {
        View[] all = View.values();

        this.setView(all[Math.floorMod(this.view().ordinal() + direction, all.length)]);
    }

    /**
     * Сменить камеру. Облёт начинается с позы прежней камеры (из обычного вида — с глаз игрока, из вида
     * сверху — сверху) и плавно встаёт так, чтобы схема была видна целиком; вид сверху каждый раз
     * заново встаёт над схемой.
     */
    private void setView(View next)
    {
        View now = this.view();
        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (next != View.NORMAL && b == null)
        {
            return;
        }

        float startYaw;
        float startPitch;

        if (now == View.TOP)
        {
            startYaw = TopViewCamera.YAW;
            startPitch = TopViewCamera.PITCH;
        }
        else if (now == View.ORBIT)
        {
            startYaw = OrbitCamera.yaw();
            startPitch = OrbitCamera.pitch();
        }
        else
        {
            LocalPlayer player = this.mc.player;
            startYaw = player == null ? TopViewCamera.YAW : player.getYRot();
            startPitch = player == null ? 0.0F : player.getXRot();
        }

        if (next == View.TOP && now != View.TOP)
        {
            this.beforeTop = now;
        }

        TopViewCamera.disable(this);
        OrbitCamera.disable(this);

        if (next == View.TOP)
        {
            TopViewCamera.enable(this, b, 0, 0, this.panelX, this.height - HINT_H, this.width, this.height);
        }
        else if (next == View.ORBIT)
        {
            OrbitCamera.enable(this, b, startYaw, startPitch, 0, 0, this.panelX, this.height - HINT_H, this.width,
                    this.height);
        }

        this.stopCamera();
    }

    /** F: схема целиком. В обычном виде — сразу облёт (он и встаёт так, чтобы она была видна вся). */
    private void frameView()
    {
        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null)
        {
            return;
        }

        switch (this.view())
        {
            case TOP -> TopViewCamera.frame(b, 0, 0, this.panelX, this.height - HINT_H, this.width, this.height);
            case ORBIT -> OrbitCamera.frame(b);
            default -> this.setView(View.ORBIT);
        }
    }

    private void undo()
    {
        // Закреплённое Litematica не сдвинет — указатель истории тогда тоже стоит на месте
        if (! PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            this.restore(this.history.undo());
        }
    }

    private void redo()
    {
        if (! PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            this.restore(this.history.redo());
        }
    }

    private void restore(EditHistory.@Nullable State state)
    {
        if (state == null)
        {
            return;
        }

        PlacementOps.apply(this.placement, state, this.feedback);
        this.pivot = null;
        this.followCamera();
    }

    private void cancel()
    {
        if (this.closing)
        {
            return;
        }

        this.closing = true;
        this.finishGesture(false);

        if (this.createdNow)
        {
            if (PlacementOps.exists(this.placement))
            {
                PlacementOps.remove(this.placement);
            }
        }
        else
        {
            this.restoreInitial();
        }

        this.leave();
    }

    /**
     * «Отмена»: исходные origin, поворот и отражение — и исходное состояние замка. Закреплённое Litematica не
     * двигает, поэтому замок на время возврата снимается, а потом ставится как был. Общее размещение Syncmatica
     * не трогаем: его замок не наш.
     */
    private void restoreInitial()
    {
        boolean shared = Syncmatica.shared(this.placement);
        boolean differs = ! EditHistory.State.of(this.placement).equals(this.initial);

        if (differs)
        {
            if (this.placement.isLocked() && ! shared)
            {
                this.placement.toggleLocked();
            }

            PlacementOps.apply(this.placement, this.initial, this.feedback);
        }

        if (this.placement.isLocked() != this.initialLocked && ! shared)
        {
            this.placement.toggleLocked();
        }
    }

    private void done()
    {
        if (this.closing)
        {
            return;
        }

        this.closing = true;
        this.finishGesture(true);
        this.leave();
    }

    private void leave()
    {
        TopViewCamera.disable(this);
        OrbitCamera.disable(this);
        PlacementOverlay.detach(this);
        Nav.back();
    }

    /** Размещение не закреплено: его можно двигать, крутить и ставить. */
    private boolean unlocked()
    {
        return ! this.placement.isLocked();
    }

    /** Подсказка элемента, который у закреплённого размещения серый: к тексту — почему. */
    private String lockedTip(String tip)
    {
        return this.placement.isLocked() ? tip + " " + text("msg.locked") : tip;
    }

    /** Подсказка поля X/Y/Z; закреплённое размещение или ось — почему поле серое. */
    private String fieldTip(int axis)
    {
        String tip = text("field.tip");

        if (this.placement.isLocked())
        {
            return tip + " " + text("msg.locked");
        }

        return this.lockedAxis(axis) ? tip + " " + I18n.get(KEY + "msg.axis_locked", text("field." + AXES[axis])) : tip;
    }

    /**
     * Замок Litematica ({@code toggleLocked}): закреплённое не сдвинут ни мышь, ни клавиши, ни инструмент самой
     * Litematica. В историю «Отменить шаг» не входит. Общему размещению Syncmatica замок не трогаем.
     */
    private void toggleLock()
    {
        if (Syncmatica.shared(this.placement))
        {
            return;
        }

        // Недотащенное применяем до замка: после него Litematica его уже не примет
        this.finishGesture(true);
        this.placement.toggleLocked();
        this.feedback.info(KEY + (this.placement.isLocked() ? "msg.lock_on" : "msg.lock_off"));
    }

    private boolean lockedX()
    {
        return this.placement.isCoordinateLocked(PositionUtils.CoordinateType.X);
    }

    private boolean lockedY()
    {
        return this.placement.isCoordinateLocked(PositionUtils.CoordinateType.Y);
    }

    private boolean lockedZ()
    {
        return this.placement.isCoordinateLocked(PositionUtils.CoordinateType.Z);
    }

    private boolean lockedAxis(int axis)
    {
        return axis == 0 ? this.lockedX() : axis == 1 ? this.lockedY() : this.lockedZ();
    }

    /** «−»/«+» по оси нажимаются: размещение не закреплено целиком и эта ось — тоже. */
    private boolean axisMovable(int axis)
    {
        return ! this.placement.isLocked() && ! this.lockedAxis(axis);
    }

    /** Подсказка «−»/«+»: ось и шаг, что делает зажатие; закреплённая ось — почему не нажимается. */
    private String stepTip(int axis, String key)
    {
        String axisName = text("field." + AXES[axis]);
        String tip = I18n.get(KEY + key, axisName, this.axisStep(axis));

        if (this.placement.isLocked())
        {
            return tip + " " + text("msg.locked");
        }

        return this.lockedAxis(axis) ? tip + " " + I18n.get(KEY + "msg.axis_locked", axisName) : tip;
    }

    /** Сдвиг, после которого {@code min + raw} ляжет на ближайшую границу чанка. */
    private static int snapShift(int min, double raw)
    {
        return (int) (Math.round((min + raw) / CHUNK) * CHUNK - min);
    }

    // ---- перетаскивание ----

    private boolean liveTooBig()
    {
        return PlacementOps.blockCount(this.placement) > LIVE_MAX_BLOCKS;
    }

    private boolean liveAllowed()
    {
        return SchematicsConfig.LIVE_GHOSTS.getBooleanValue() && ! this.liveTooBig();
    }

    /** «200 000» / «200,000» — разделитель разрядов языка игры, как у чисел экрана «Схемы». */
    private static String liveLimit()
    {
        return Kit.group(LIVE_MAX_BLOCKS, Strings.plain("layerlist.schematics.num_group"));
    }

    /** Голограммы — за рамкой, если можно и пора (не чаще {@link #LIVE_PERIOD_MS}). */
    private void liveStep()
    {
        long now = Util.getMillis();

        if (this.liveAllowed() && now - this.liveAt >= LIVE_PERIOD_MS && ! this.dragOrigin.equals(this.placement.getOrigin()))
        {
            this.liveAt = now;
            PlacementOps.moveTo(this.placement, this.dragOrigin, this.feedback);
        }
    }

    /** Общее начало серии: рамка от текущего origin, голограммы — по правилу {@link #liveStep}. */
    private void beginGesture(Gesture kind)
    {
        this.gesture = kind;
        this.grabOrigin = this.placement.getOrigin().immutable();
        this.dragOrigin = this.grabOrigin;
        this.liveAt = Util.getMillis();
    }

    private void startDrag(double mouseX, double mouseY)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null)
        {
            return;
        }

        // Схватить можно в любом месте: запоминаем точку на плоскости низа схемы и дальше двигаем
        // на разницу, поэтому схема не прыгает углом под курсор
        Vec3 hit = CursorRay.cast(mouseX, mouseY, this.width, this.height).atHeight(b.minY());

        if (hit == null)
        {
            return;
        }

        this.beginGesture(Gesture.MOVE);
        this.dragPlaneY = b.minY();
        this.grabPoint = hit;
        this.grabBounds = b;
    }

    private void updateDrag(double mouseX, double mouseY)
    {
        Vec3 grab = this.grabPoint;
        Vec3 hit = CursorRay.cast(mouseX, mouseY, this.width, this.height).atHeight(this.dragPlaneY);

        if (grab == null || hit == null)
        {
            return;
        }

        double rawX = hit.x - grab.x;
        double rawZ = hit.z - grab.z;
        int dx = chunkSnap ? snapShift(this.grabBounds.minX(), rawX) : (int) Math.round(rawX);
        int dz = chunkSnap ? snapShift(this.grabBounds.minZ(), rawZ) : (int) Math.round(rawZ);

        // Оси, закреплённые в Litematica, она при применении всё равно не сдвинет — рамка тоже стоит
        this.dragOrigin = this.grabOrigin.offset(this.lockedX() ? 0 : dx, 0, this.lockedZ() ? 0 : dz);
        this.liveStep();
    }

    /** Угол рамки под курсором: ближайший на экране, не дальше {@link #CORNER_PICK_PX}; {@code -1} — нет. */
    private int cornerAt(double mouseX, double mouseY)
    {
        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null || this.overPanel(mouseX) || mouseY >= this.height - HINT_H)
        {
            return -1;
        }

        int best = -1;
        double bestD2 = CORNER_PICK_PX * CORNER_PICK_PX;

        for (int i = 0; i < PlacementOps.Bounds.CORNERS; ++i)
        {
            double[] at = CursorRay.project(b.corner(i), this.width, this.height);

            if (at == null)
            {
                continue;
            }

            double dx = at[0] - mouseX;
            double dy = at[1] - mouseY;
            double d2 = dx * dx + dy * dy;

            if (d2 <= bestD2)
            {
                best = i;
                bestD2 = d2;
            }
        }

        return best;
    }

    /**
     * Схватить за угол — схема поедет только по высоте. Точка под курсором ищется на вертикальной
     * плоскости через угол, повёрнутой к камере (нормаль — горизонтальная часть взгляда): тогда
     * курсор вверх-вниз по экрану — это вверх-вниз в мире при любом рыскании. Взгляд почти отвесный
     * (вид сверху, облёт сверху) — плоскость легла бы вдоль луча, поэтому высота — из пикселей.
     */
    private void startLift(int corner, double mouseX, double mouseY)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);

        if (b == null)
        {
            return;
        }

        if (this.lockedY())
        {
            // Litematica закреплённую ось не сдвинет — не делаем вид, что тащим
            this.feedback.error(KEY + "msg.axis_locked", text("field.y"));

            return;
        }

        Vec3 point = b.corner(corner);
        Vec3 f = CursorRay.pose().forward();
        double horizontal = Math.sqrt(f.x * f.x + f.z * f.z);

        if (horizontal < LIFT_MIN_HORIZONTAL)
        {
            double[] at = CursorRay.project(point, this.width, this.height);

            if (at == null)
            {
                return;
            }

            this.liftByPixels = true;
            this.liftPerPixel = CursorRay.blocksPerPixel(at[2], this.height);
            this.liftMouseY = mouseY;
        }
        else
        {
            Vec3 normal = new Vec3(f.x / horizontal, 0.0, f.z / horizontal);
            Vec3 hit = CursorRay.cast(mouseX, mouseY, this.width, this.height).onPlane(point, normal);

            if (hit == null)
            {
                return;
            }

            this.liftByPixels = false;
            this.liftPoint = point;
            this.liftNormal = normal;
            this.liftGrabY = hit.y;
        }

        this.beginGesture(Gesture.LIFT);
        this.liftCorner = corner;
    }

    private void updateLift(double mouseX, double mouseY)
    {
        double dy;

        if (this.liftByPixels)
        {
            dy = (this.liftMouseY - mouseY) * this.liftPerPixel;
        }
        else
        {
            Vec3 hit = CursorRay.cast(mouseX, mouseY, this.width, this.height).onPlane(this.liftPoint, this.liftNormal);

            if (hit == null)
            {
                return;
            }

            dy = hit.y - this.liftGrabY;
        }

        this.dragOrigin = this.grabOrigin.offset(0, (int) Math.round(dy), 0);
        this.liveStep();
    }

    /**
     * Нажали «−»/«+»: рамка сразу сдвигается на шаг; пока кнопку держат — ещё и ещё (см.
     * {@link EditorControls.HoldButton}), голограммы — по правилу {@link #liveStep}, Litematica получает
     * итог при отпускании. Закреплённая ось — сообщение, а не немой клик.
     */
    private void startHold(EditorControls.HoldButton button, int axis, int sign)
    {
        if (PlacementOps.refuseLocked(this.placement, this.feedback))
        {
            return;
        }

        if (this.lockedAxis(axis))
        {
            this.feedback.error(KEY + "msg.axis_locked", text("field." + AXES[axis]));

            return;
        }

        this.beginGesture(Gesture.STEP);
        this.held = button;
        this.heldAxis = axis;
        this.heldSign = sign;
        this.heldStep = this.axisStep(axis);
        button.press(Util.getMillis());
        this.holdAdvance(1);
    }

    private void holdAdvance(int times)
    {
        int d = times * this.heldSign * this.heldStep;

        this.dragOrigin = this.dragOrigin.offset(this.heldAxis == 0 ? d : 0, this.heldAxis == 1 ? d : 0,
                this.heldAxis == 2 ? d : 0);
        this.liveStep();
    }

    /** Раз в кадр: набежавшие повторы зажатой «−»/«+». Курсор ушёл с кнопки — серия закончена. */
    private void updateHold(int mouseX, int mouseY)
    {
        EditorControls.HoldButton button = this.held;

        if (this.gesture != Gesture.STEP || button == null)
        {
            return;
        }

        if (! button.over(mouseX, mouseY))
        {
            this.finishGesture(true);

            return;
        }

        int due = button.due(Util.getMillis());

        if (due > 0)
        {
            this.holdAdvance(due);
        }
    }

    private void releaseHeld()
    {
        if (this.held != null)
        {
            this.held.release();
            this.held = null;
        }
    }

    /** Конец серии (отпустили кнопку, клавиша, «Готово»/«Отмена»): применить или бросить недотащенное. */
    private void finishGesture(boolean apply)
    {
        if (this.gesture == Gesture.NONE)
        {
            return;
        }

        this.gesture = Gesture.NONE;
        this.grabPoint = null;
        this.liftCorner = -1;
        this.releaseHeld();

        if (apply && ! this.dragOrigin.equals(this.placement.getOrigin()))
        {
            PlacementOps.moveTo(this.placement, this.dragOrigin, this.feedback);
        }

        this.afterEdit(null);
    }

    private void pan(double mouseX, double mouseY)
    {
        if (OrbitCamera.active())
        {
            OrbitCamera.pan(CursorRay.cast(this.lastMouseX, this.lastMouseY, this.width, this.height),
                    CursorRay.cast(mouseX, mouseY, this.width, this.height), mouseX - this.lastMouseX,
                    mouseY - this.lastMouseY, this.height);

            return;
        }

        PlacementOps.Bounds b = PlacementOps.bounds(this.placement);
        double planeY = b == null ? TopViewCamera.y() - 1.0 : b.minY();
        Vec3 from = CursorRay.cast(this.lastMouseX, this.lastMouseY, this.width, this.height).atHeight(planeY);
        Vec3 to = CursorRay.cast(mouseX, mouseY, this.width, this.height).atHeight(planeY);

        if (from != null && to != null)
        {
            // Мир «прилипает» к курсору: камера едет в обратную сторону
            TopViewCamera.pan(from.x - to.x, from.z - to.z);
        }
    }

    private void look(double dx, double dy)
    {
        LocalPlayer player = this.mc.player;

        if (player == null)
        {
            return;
        }

        double k = LOOK_SPEED * (0.5 + this.mc.options.sensitivity().get());
        double pitch = this.mc.options.invertMouseY().get() ? -dy : dy;

        player.turn(dx * k, pitch * k);
    }

    /**
     * Облёт ПКМ: схема поворачивается за мышью, как на подставке — вправо по экрану камера уходит
     * по кругу влево, вниз — поднимается выше над схемой.
     */
    private void orbit(double dx, double dy)
    {
        double k = ORBIT_DEG_PER_PX * (0.5 + this.mc.options.sensitivity().get());
        double pitch = this.mc.options.invertMouseY().get() ? -dy : dy;

        OrbitCamera.rotate(dx * k, pitch * k);
    }

    private void stopCamera()
    {
        this.cameraButton = -1;
        this.panning = false;
        this.looking = false;
        this.orbiting = false;
    }

    // ---- «Смотреть на мир» ----

    /**
     * «Смотреть на мир»: экран закрывается, игра идёт как обычно, карточка координат этого размещения остаётся
     * HUD-ом ({@link PlacementHud}). Сеанс правки продолжается — исходное состояние «Отмены», история шагов, замок —
     * всё у этого же экземпляра; вернёт его Esc в игре ({@link #reopen}). Недотащенное применяем, камеры снимаем:
     * в мире нужна обычная.
     */
    private void enterWorld()
    {
        if (this.closing || this.mc.level == null)
        {
            return;
        }

        this.finishEditing();
        this.finishGesture(true);
        this.releaseHeld();
        this.stopCamera();
        this.setView(View.NORMAL);

        // Куда вернут «Готово»/«Отмена»: экран, из которого открыли редактор (в стеке он последний)
        this.worldParent = Nav.peek();
        PlacementHud.enter(this);
        Nav.clear();
        this.mc.setScreen(null);
    }

    /**
     * Вернуть этот же редактор на экран из «Смотреть на мир»: родитель — прежний, так что «Готово»/«Отмена» снова
     * ведут в «Схемы». HUD, рамка в мире и камеры поднимаются в {@link #initGui}, вид сверху заново не включается.
     */
    public void reopen()
    {
        if (this.closing)
        {
            return;
        }

        PlacementHud.leave();
        Nav.open(this, this.worldParent);
    }

    /** Размещение этого редактора. */
    public SchematicPlacement placement()
    {
        return this.placement;
    }

    /** Размещение ещё есть в Litematica (не убрали снаружи). */
    public boolean worldExists()
    {
        return ! this.closing && PlacementOps.exists(this.placement);
    }

    public boolean worldLocked()
    {
        return this.placement.isLocked();
    }

    /** Замок можно трогать: общее размещение Syncmatica запирает сама Syncmatica. */
    public boolean worldLockable()
    {
        return ! Syncmatica.shared(this.placement);
    }

    /** Ось правится: не закреплено ни размещение, ни эта ось. */
    public boolean worldAxisMovable(int axis)
    {
        return this.axisMovable(axis);
    }

    /** Число в поле карточки применено: ось {@code axis} (0…2) — в {@code value}, одним шагом истории. */
    public void worldSetAxis(int axis, int value)
    {
        this.setOriginAxis(axis, value);
    }

    /** Кнопки «+/−» карточки: сдвиг по оси на {@code delta}. */
    public void worldNudge(int axis, int delta)
    {
        if (delta == 0 || this.axisRefused(axis))
        {
            return;
        }

        this.nudge(axis == 0 ? delta : 0, axis == 1 ? delta : 0, axis == 2 ? delta : 0, null);
    }

    /** Клик по серому полю или «+/−» карточки: сообщение, чем ось закреплена. */
    public void worldRefuse(int axis)
    {
        this.axisRefused(axis);
    }

    /** Поворот; {@code aroundCorner} — вокруг origin (Shift у кнопки), иначе вокруг центра габарита. */
    public void worldRotate(boolean clockwise, boolean aroundCorner)
    {
        this.rotate(clockwise, aroundCorner);
    }

    public void worldMirror(boolean aroundCorner)
    {
        this.mirror(aroundCorner);
    }

    public void worldToPlayer()
    {
        this.toPlayer();
    }

    public void worldToggleLock()
    {
        this.toggleLock();
    }

    /** Это размещение — выбранное в Litematica: её инструмент и клавиши действуют на него. */
    public boolean worldSelected()
    {
        try
        {
            return PlacementOps.selected() == this.placement;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** «Двигать в мире»: выбрать это размещение в Litematica (ещё раз — снять выбор). */
    public void worldSelect()
    {
        if (this.worldSelected())
        {
            PlacementOps.deselect();
        }
        else
        {
            PlacementOps.select(this.placement);
        }
    }

    /** Строка поворота и отражения для карточки: «90° · без отражения». */
    public String worldTransform()
    {
        return I18n.get(KEY + "rotation", degrees(this.placement.getRotation())) + Ui.TITLE_SEPARATOR
                + text(mirrorKey(this.placement.getMirror()));
    }

    /** Последнее сообщение редактора (моложе 4 с) для карточки — отказ закреплённого размещения не молчит. */
    public @Nullable String worldMessage()
    {
        return this.feedback.recent(MESSAGE_MS);
    }

    public boolean worldMessageIsError()
    {
        return this.feedback.isError();
    }

    // ---- ввод ----

    private boolean overPanel(double mouseX)
    {
        return mouseX >= this.panelX;
    }

    /** Поля ввода по порядку Tab: X, Y, Z, потом слой (и «до» у диапазона), если они на панели. */
    private List<EditorControls.NumberField> inputFields()
    {
        List<EditorControls.NumberField> out = new java.util.ArrayList<>(List.of(this.fields));

        for (EditorControls.NumberField extra : new EditorControls.NumberField[] { this.layerValue, this.layerMax })
        {
            if (this.controls.contains(extra) && extra.visible)
            {
                out.add(extra);
            }
        }

        return out;
    }

    private @Nullable EditorControls.NumberField editingField()
    {
        for (EditorControls.NumberField field : this.inputFields())
        {
            if (field.editing())
            {
                return field;
            }
        }

        return null;
    }

    /** Закончить ввод в поле (клик мимо, Tab): верное число применяется, неверное — поле остаётся. */
    private boolean finishEditing()
    {
        EditorControls.NumberField field = this.editingField();

        return field == null || field.finish();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        double mx = click.x();
        double my = click.y();
        int button = click.button();

        if (this.overPanel(mx))
        {
            if (button != InputConstants.MOUSE_BUTTON_LEFT)
            {
                return true;
            }

            for (EditorControls.Control control : this.controls)
            {
                if (! control.over(mx, my))
                {
                    continue;
                }

                if (control instanceof EditorControls.NumberField field)
                {
                    // Закреплённое размещение или ось: ввод не начинаем, а говорим почему
                    if (! field.isEnabled())
                    {
                        this.finishEditing();
                        this.refuseField(field);

                        return true;
                    }

                    if (! field.editing() && this.finishEditing())
                    {
                        field.begin();
                    }

                    return true;
                }

                this.finishEditing();

                if (control instanceof EditorControls.HoldButton hold)
                {
                    if (hold.isEnabled())
                    {
                        Vanilla.click();
                    }

                    this.pressHold(hold);

                    return true;
                }

                return control.click(mx, my, click.hasShiftDown());
            }

            this.finishEditing();

            return true;
        }

        this.finishEditing();

        if (my >= this.height - HINT_H)
        {
            return true;
        }

        // Пока левая кнопка что-то тащит, остальные кнопки мира ничего не начинают
        if (this.gesture != Gesture.NONE)
        {
            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT)
        {
            int corner = this.cornerAt(mx, my);

            if (corner >= 0)
            {
                this.startLift(corner, mx, my);
            }
            else
            {
                this.startDrag(mx, my);
            }

            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE)
        {
            boolean middle = button == InputConstants.MOUSE_BUTTON_MIDDLE;

            this.stopCamera();

            // Сверху: ПКМ и СКМ — панорама. Облёт: ПКМ — вращение, СКМ и Shift+ПКМ — панорама.
            // Обычный вид: ПКМ — осмотр, СКМ ничего не делает
            switch (this.view())
            {
                case TOP -> this.panning = true;
                case ORBIT ->
                {
                    this.panning = middle || click.hasShiftDown();
                    this.orbiting = ! this.panning;
                }
                default -> this.looking = ! middle;
            }

            if (! this.panning && ! this.looking && ! this.orbiting)
            {
                return false;
            }

            this.cameraButton = button;
            this.lastMouseX = mx;
            this.lastMouseY = my;

            return true;
        }

        return false;
    }

    /** Клик по серому полю X/Y/Z: сообщение, чем оно закреплено. */
    private void refuseField(EditorControls.NumberField field)
    {
        for (int axis = 0; axis < 3; ++axis)
        {
            if (this.fields[axis] == field)
            {
                this.axisRefused(axis);
            }
        }
    }

    /** «−»/«+» под полем: какая ось и в какую сторону. */
    private void pressHold(EditorControls.HoldButton hold)
    {
        if (this.pressLayerHold(hold))
        {
            return;
        }

        for (int i = 0; i < 3; ++i)
        {
            if (hold == this.minus[i] || hold == this.plus[i])
            {
                this.startHold(hold, i, hold == this.plus[i] ? 1 : -1);

                return;
            }
        }
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragX, double dragY)
    {
        int button = click.button();

        if (button == InputConstants.MOUSE_BUTTON_LEFT && this.gesture != Gesture.NONE)
        {
            if (this.gesture == Gesture.MOVE)
            {
                this.updateDrag(click.x(), click.y());
            }
            else if (this.gesture == Gesture.LIFT)
            {
                this.updateLift(click.x(), click.y());
            }

            return true;
        }

        if (button == this.cameraButton)
        {
            if (this.panning && (this.topView() || OrbitCamera.active()))
            {
                this.pan(click.x(), click.y());
            }
            else if (this.orbiting && OrbitCamera.active())
            {
                // Сдвиг — от прошлой точки события, а не накопленный ванилью: в него попало бы и
                // движение мыши до нажатия, и облёт дёргался бы в первый кадр
                this.orbit(click.x() - this.lastMouseX, click.y() - this.lastMouseY);
            }
            else if (this.looking)
            {
                this.look(dragX, dragY);
            }

            this.lastMouseX = click.x();
            this.lastMouseY = click.y();

            return true;
        }

        return super.onMouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        int button = click.button();

        if (button == InputConstants.MOUSE_BUTTON_LEFT && this.layerHeld != null)
        {
            this.layerHeld.release();
            this.layerHeld = null;
            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT && this.gesture != Gesture.NONE)
        {
            this.finishGesture(true);

            return true;
        }

        if (button == this.cameraButton)
        {
            this.stopCamera();

            return true;
        }

        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (verticalAmount == 0.0)
        {
            return false;
        }

        boolean shift = isShiftDown();

        // Пока левая кнопка что-то тащит или держит «−»/«+», колесо ничего не меняет — ни в мире, ни в полях
        if (this.gesture != Gesture.NONE)
        {
            return true;
        }

        if (this.overPanel(mouseX))
        {
            for (EditorControls.Control control : this.controls)
            {
                if (control.over(mouseX, mouseY) && control.scroll(verticalAmount, shift))
                {
                    return true;
                }
            }

            return true;
        }

        if (this.topView())
        {
            PlacementOps.Bounds b = PlacementOps.bounds(this.placement);
            double planeY = b == null ? TopViewCamera.y() - 1.0 : b.minY();
            Vec3 focus = CursorRay.cast(mouseX, mouseY, this.width, this.height).atHeight(planeY);

            TopViewCamera.zoom(verticalAmount, focus, planeY);
        }
        else if (OrbitCamera.active())
        {
            OrbitCamera.zoom(verticalAmount);
        }
        else
        {
            int amount = shift ? Ui.STEP_FAST : 1;
            this.nudge(0, verticalAmount > 0.0 ? amount : -amount, 0, "wheel");
        }

        return true;
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        int key = input.key();
        EditorControls.NumberField field = this.editingField();

        if (field != null)
        {
            return this.fieldKey(field, input);
        }

        boolean shift = input.hasShiftDown();

        if (input.hasControlDown())
        {
            // Вне поля: Ctrl+C — положение «X Y Z», Ctrl+V — вставить положение (три числа из буфера)
            if (key == InputConstants.KEY_C)
            {
                BlockPos origin = this.placement.getOrigin();
                String text = origin.getX() + " " + origin.getY() + " " + origin.getZ();
                net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(text);
                this.feedback.info(KEY + "msg.copied", text);
                return true;
            }

            if (key == InputConstants.KEY_V)
            {
                int[] numbers = numbersIn(net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard());

                if (numbers.length >= 3)
                {
                    this.pasteOrigin(numbers);
                }
                else
                {
                    this.feedback.error(KEY + "msg.paste_xyz");
                }

                return true;
            }

            if (key == InputConstants.KEY_Z)
            {
                this.finishGesture(true);

                if (shift)
                {
                    this.redo();
                }
                else
                {
                    this.undo();
                }

                return true;
            }

            if (key == InputConstants.KEY_Y)
            {
                this.finishGesture(true);
                this.redo();

                return true;
            }

            return false;
        }

        switch (key)
        {
            case InputConstants.KEY_ESCAPE -> this.cancel();
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> this.done();
            case InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT ->
            {
                this.finishGesture(true);
                this.arrow(key);
            }
            case InputConstants.KEY_PAGEUP ->
            {
                this.finishGesture(true);
                this.nudge(0, this.step(), 0, null);
            }
            case InputConstants.KEY_PAGEDOWN ->
            {
                this.finishGesture(true);
                this.nudge(0, -this.step(), 0, null);
            }
            case InputConstants.KEY_R ->
            {
                this.finishGesture(true);
                this.rotate(! shift, input.hasAltDown());
            }
            case InputConstants.KEY_M ->
            {
                this.finishGesture(true);
                this.mirror(input.hasAltDown());
            }
            case InputConstants.KEY_G ->
            {
                this.finishGesture(true);
                this.toGround(shift);
            }
            case InputConstants.KEY_C -> this.toggleChunkSnap();
            case InputConstants.KEY_T -> this.toggleTopView();
            case InputConstants.KEY_O -> this.toggleOrbit();
            case InputConstants.KEY_F -> this.frameView();
            default ->
            {
                return false;
            }
        }

        return true;
    }

    /** Клавиши, пока идёт ввод в поле: поле забирает всё, чтобы R или M не крутили схему. */
    private boolean fieldKey(EditorControls.NumberField field, KeyEvent input)
    {
        // Ctrl+C / Ctrl+V в поле: число как есть; «X Y Z» в поле положения — сразу все три
        if (input.hasControlDown() && input.key() == InputConstants.KEY_C)
        {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(field.text());
            this.feedback.info(KEY + "msg.copied", field.text());
            return true;
        }

        if (input.hasControlDown() && input.key() == InputConstants.KEY_V)
        {
            int[] numbers = numbersIn(net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard());

            if (numbers.length >= 3 && List.of(this.fields).contains(field))
            {
                field.cancel();
                this.pasteOrigin(numbers);
            }
            else if (numbers.length >= 1)
            {
                field.paste(Integer.toString(numbers[0]));
            }
            else
            {
                this.feedback.error(KEY + "msg.paste_nothing");
            }

            return true;
        }

        switch (input.key())
        {
            case InputConstants.KEY_ESCAPE -> field.cancel();
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> field.finish();
            case InputConstants.KEY_BACKSPACE -> field.backspace();
            case InputConstants.KEY_TAB ->
            {
                if (field.finish())
                {
                    List<EditorControls.NumberField> order = this.inputFields();
                    int index = order.indexOf(field);
                    int next = Math.floorMod(index + (input.hasShiftDown() ? -1 : 1), order.size());
                    order.get(next).begin();
                }
            }
            default -> {}
        }

        return true;
    }

    @Override
    public boolean onCharTyped(CharacterEvent input)
    {
        EditorControls.NumberField field = this.editingField();

        if (field != null)
        {
            field.type(input.codepoint());

            return true;
        }

        return super.onCharTyped(input);
    }

    /** Черновик IME (26.3): ваниль отдаёт его экрану, а мы — полю, которое сейчас в вводе. */

    /**
     * Сообщения Litematica и malilib, пока открыт редактор, — строкой в панели, а не плашкой malilib
     * посреди вида.
     */
    @Override
    public void addMessage(Message.MessageType type, int lifeTime, String messageKey, Object... args)
    {
        if (! SchematicsModule.noiseMessage(messageKey))
        {
            this.feedback.addMessage(type, lifeTime, messageKey, args);
        }
    }

    @Override
    public void addMessage(Message.MessageType type, String messageKey, Object... args)
    {
        this.addMessage(type, 5000, messageKey, args);
    }

    // ---- отрисовка ----

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY)
    {
        // Без затемнения: весь смысл редактора — видеть мир
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        // Раз в кадр: повторы зажатой «−»/«+» и угол под курсором (камера могла сдвинуться и без мыши)
        ++this.frames;
        this.updateHold(mouseX, mouseY);
        this.updateLayerHold(mouseX, mouseY);

        // Режим слоёв сменили клавишей Litematica — поля под ним другие
        if (layerRange().getLayerMode() != this.laidOutMode)
        {
            this.layoutPanel();
        }
        this.hoverCorner = this.gesture == Gesture.NONE && ! this.closing ? this.cornerAt(mouseX, mouseY) : -1;

        if (this.topView())
        {
            this.drawNorth(ctx);
        }

        this.drawHints(ctx);
        this.drawPanel(ctx, mouseX, mouseY);
    }

    /** Вид сверху: метка «север» в углу — без неё сторону света сверху не угадать. */
    private void drawNorth(GuiContext ctx)
    {
        String north = text("north");
        int w = this.font.width(north) + Ui.PAD_REL * 2;

        Paint.card(ctx, Ui.OUTER, Ui.OUTER, w, Ui.BUTTON_H, Ui.R_CTRL, Ui.C_CHROME, Ui.C_DIVIDER);
        Paint.midText(ctx, this.font, north, Ui.OUTER + Ui.PAD_REL, Ui.OUTER, Ui.BUTTON_H, Ui.C_TEXT, false);
    }

    private void drawHints(GuiContext ctx)
    {
        int y = this.height - HINT_H;
        int right = this.panelX - Ui.PAD_REL;

        if (Style.vanilla())
        {
            // Ванильный стиль: строка подсказок — фон списка Minecraft с тёмной рамкой
            Vanilla.panel(ctx, 0, y, this.panelX, HINT_H);
        }
        else
        {
            Paint.rect(ctx, 0, y, this.panelX, HINT_H, Ui.C_CHROME);
            Paint.rect(ctx, 0, y, this.panelX, Ui.BORDER, Ui.C_DIVIDER);
        }

        // По важности: в узком окне хвост не влезет, а мышь, Enter и Esc нужны всегда
        String[] keys = switch (this.view())
        {
            case TOP -> new String[] {
                    "hint.drag", "hint.corner", "hint.pan", "hint.zoom", "hint.done", "hint.cancel", "hint.rotate",
                    "hint.mirror", "hint.ground", "hint.view_back", "hint.frame", "hint.grid", "hint.arrows",
                    "hint.height", "hint.undo"};
            case ORBIT -> new String[] {
                    "hint.drag", "hint.corner", "hint.orbit", "hint.orbit_pan", "hint.orbit_zoom", "hint.done",
                    "hint.cancel", "hint.frame", "hint.rotate", "hint.mirror", "hint.ground", "hint.orbit_off",
                    "hint.view", "hint.grid", "hint.arrows", "hint.height", "hint.undo"};
            default -> new String[] {
                    "hint.drag", "hint.corner", "hint.look", "hint.wheel", "hint.done", "hint.cancel", "hint.rotate",
                    "hint.mirror", "hint.ground", "hint.orbit_key", "hint.view", "hint.grid", "hint.arrows",
                    "hint.height", "hint.undo", "hint.frame"};
        };
        int x = Ui.PAD_REL;

        // Сколько влезает целиком: обрезанная подсказка клавиши хуже отсутствующей
        for (int i = 0; i < keys.length; ++i)
        {
            String part = (i == 0 ? "" : Ui.TITLE_SEPARATOR) + text(keys[i]);
            int w = this.font.width(part);

            if (x + w > right)
            {
                break;
            }

            Paint.midText(ctx, this.font, part, x, y, HINT_H, Ui.C_TEXT_2, false);
            x += w;
        }
    }

    /**
     * Подпись и значение в строку ширины {@code w}: не влезло — значение медленно прокручивается туда и обратно
     * (с паузами на концах), так имя видно целиком, а панель не шире.
     */
    private void marquee(GuiContext ctx, String label, String value, int x, int y, int w)
    {
        String head = label + " ";
        int headW = this.font.width(head);
        int room = Math.max(0, w - headW);
        int valueW = this.font.width(value);
        Paint.text(ctx, this.font, head, x, y, Ui.C_TEXT_3, false);

        if (valueW <= room)
        {
            Paint.text(ctx, this.font, value, x + headW, y, Ui.C_TEXT, false);
            return;
        }

        int overflow = valueW - room;
        double travelMs = overflow * 40.0;
        double pauseMs = 1200.0;
        double period = (travelMs + pauseMs) * 2.0;
        double t = Util.getMillis() % (long) period;
        double offset;

        if (t < pauseMs)
        {
            offset = 0.0;
        }
        else if (t < pauseMs + travelMs)
        {
            offset = (t - pauseMs) / travelMs * overflow;
        }
        else if (t < pauseMs * 2.0 + travelMs)
        {
            offset = overflow;
        }
        else
        {
            offset = overflow * (1.0 - (t - pauseMs * 2.0 - travelMs) / travelMs);
        }

        ctx.enableScissor(x + headW, y - 1, x + w, y + Ui.TEXT_H + 1);
        Paint.text(ctx, this.font, value, x + headW - (int) Math.round(offset), y, Ui.C_TEXT, false);
        ctx.disableScissor();
    }

    private void drawPanel(GuiContext ctx, int mouseX, int mouseY)
    {
        int x = this.panelX + Ui.OUTER;
        int w = PANEL_W - Ui.OUTER * 2;

        if (Style.vanilla())
        {
            // Ванильный стиль: фон списка на всю панель, шапка не заливается — под ней разделитель Minecraft
            Vanilla.panel(ctx, this.panelX, 0, PANEL_W, this.height);
            Vanilla.headerSeparator(ctx, this.panelX + 1, Ui.HEADER_H - 2, PANEL_W - 2);
        }
        else
        {
            Paint.rect(ctx, this.panelX, 0, PANEL_W, this.height, Ui.C_PANEL);
            Paint.rect(ctx, this.panelX, 0, Ui.BORDER, this.height, Ui.C_DIVIDER);

            Paint.rect(ctx, this.panelX, 0, PANEL_W, Ui.HEADER_H, Ui.C_CHROME);
            Paint.rect(ctx, this.panelX, Ui.HEADER_H - Ui.BORDER, PANEL_W, Ui.BORDER, Ui.C_DIVIDER);
        }

        Paint.midText(ctx, this.font, text("title"), x, 0, Ui.HEADER_H, Ui.C_TEXT, false);

        java.nio.file.Path file = this.placement.getSchematicFile();
        String fileName = file == null || file.getFileName() == null ? text("no_file") : file.getFileName().toString();
        this.marquee(ctx, text("name"), this.placement.getName(), x, this.namesY, w);
        this.marquee(ctx, text("file"), fileName, x, this.namesY + Ui.TEXT_H + Ui.PAD_MICRO, w);

        PlacementOps.Bounds b = this.previewBounds();

        if (b != null)
        {
            Paint.text(ctx, this.font, Paint.fit(this.font, I18n.get(KEY + "size", b.sizeX(), b.sizeY(), b.sizeZ()), w),
                    x, this.sizeY, Ui.C_TEXT_2, false);
        }

        for (SectionTitle t : this.titles)
        {
            Paint.midText(ctx, this.font, Paint.fit(this.font, text(t.key()), w), x, t.y(), Ui.SECTION_H,
                    Ui.C_TEXT_3, false);
        }


        if (this.mirror.visible)
        {
            String readout = I18n.get(KEY + "rotation", degrees(this.placement.getRotation()))
                    + Ui.TITLE_SEPARATOR + text(mirrorKey(this.placement.getMirror()));

            Paint.midText(ctx, this.font, Paint.fit(this.font, readout, this.readoutRight - this.readoutX), this.readoutX,
                    this.readoutY, Ui.CTRL_H, Ui.C_TEXT_2, false);
        }

        this.drawMessage(ctx, x, w);

        if (Style.vanilla())
        {
            Vanilla.footerSeparator(ctx, this.panelX + 1, this.footerY, PANEL_W - 2);
        }
        else
        {
            Paint.rect(ctx, this.panelX, this.footerY, PANEL_W, Ui.FOOTER_H, Ui.C_CHROME);
            Paint.rect(ctx, this.panelX, this.footerY, PANEL_W, Ui.BORDER, Ui.C_DIVIDER);
        }

        for (EditorControls.Control control : this.controls)
        {
            control.draw(ctx, this.font, mouseX, mouseY);
        }
    }

    /** Последнее сообщение (Litematica или наше); у закреплённого размещения — постоянное предупреждение. */
    private void drawMessage(GuiContext ctx, int x, int w)
    {
        String message = this.feedback.recent(MESSAGE_MS);
        boolean warn = message != null && this.feedback.isError();

        if (message == null && this.placement.isLocked())
        {
            message = text("msg.locked");
            warn = true;
        }

        if (message == null)
        {
            return;
        }

        List<FormattedCharSequence> lines = this.font.split(FormattedText.of(message), w);
        int room = Math.min(MESSAGE_LINES, (this.footerY - Ui.PAD_IN - this.messageY) / Ui.TIP_LINE);

        for (int i = 0; i < lines.size() && i < room; ++i)
        {
            Paint.text(ctx, this.font, lines.get(i), x, this.messageY + i * Ui.TIP_LINE, warn ? Ui.C_WARN : Ui.C_TEXT_2,
                    false);
        }
    }

    @Override
    protected void drawButtonHoverTexts(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        EditorControls.Control hovered = null;

        for (EditorControls.Control control : this.controls)
        {
            if (control.tip != null && control.over(mouseX, mouseY))
            {
                hovered = control;
                break;
            }
        }

        if (hovered != this.tipTarget)
        {
            this.tipTarget = hovered;
            this.tipSince = Util.getMillis();
        }

        if (hovered == null || hovered.tip == null || Util.getMillis() - this.tipSince < Ui.TIP_DELAY_MS)
        {
            return;
        }

        List<FormattedCharSequence> lines = this.font.split(FormattedText.of(hovered.tip.get()), Ui.TIP_MAX_W);

        if (lines.isEmpty())
        {
            return;
        }

        int w = 0;

        for (FormattedCharSequence line : lines)
        {
            w = Math.max(w, this.font.width(line));
        }

        w += Ui.TIP_PAD * 2;
        int h = lines.size() * Ui.TIP_LINE + Ui.TIP_PAD * 2;

        // Панель справа — подсказка левее курсора, чтобы не уйти за край окна
        int tx = Ui.clamp(mouseX - Ui.PAD_REL - w, 0, Math.max(0, this.width - w));
        int ty = Ui.clamp(mouseY, 0, Math.max(0, this.height - h));

        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, tx, ty, w, h);
        }
        else
        {
            Paint.card(ctx, tx, ty, w, h, Ui.R_PLATE, Ui.C_CHROME, Ui.C_DIVIDER);
        }

        for (int i = 0; i < lines.size(); ++i)
        {
            Paint.text(ctx, this.font, lines.get(i), tx + Ui.TIP_PAD, ty + Ui.TIP_PAD + i * Ui.TIP_LINE, Ui.C_TEXT,
                    false);
        }
    }

    private static int degrees(Rotation rotation)
    {
        return switch (rotation)
        {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

    private static String viewKey(View view)
    {
        return switch (view)
        {
            case ORBIT -> "view_orbit";
            case TOP -> "view_top";
            default -> "view_normal";
        };
    }

    private static String mirrorKey(Mirror mirror)
    {
        return switch (mirror)
        {
            case FRONT_BACK -> "mirror.front_back";
            case LEFT_RIGHT -> "mirror.left_right";
            default -> "mirror.none";
        };
    }

    private static String text(String suffix)
    {
        return Strings.plain(KEY + suffix);
    }

    private record SectionTitle(String key, int y) {}

    /**
     * Узкий вход для харнесса покрытия (режим {@code k}, набор {@code src/dev}): только чтение
     * состояния редактора, без побочных действий. В игре его никто не зовёт. Правки харнесс делает
     * обычным вводом экрана — клавишами и мышью, — чтобы проверялся тот же путь, что у игрока.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Открытый редактор; {@code null} — на экране другое. */
        public static @Nullable PlacementEditorScreen current(@Nullable Screen screen)
        {
            return screen instanceof PlacementEditorScreen editor ? editor : null;
        }

        public static SchematicPlacement placement(PlacementEditorScreen editor)
        {
            return editor.placement;
        }

        public static boolean createdNow(PlacementEditorScreen editor)
        {
            return editor.createdNow;
        }

        /** Состояние при открытии — его возвращает «Отмена». */
        public static EditHistory.State initial(PlacementEditorScreen editor)
        {
            return editor.initial;
        }

        /** Правый край свободной части окна: правее начинается панель. */
        public static int freeRight(PlacementEditorScreen editor)
        {
            return editor.panelX;
        }

        /** Верх строки подсказок: ниже клик мира не трогает. */
        public static int freeBottom(PlacementEditorScreen editor)
        {
            return editor.height - HINT_H;
        }

        /** Левая кнопка что-то тащит: по плоскости, за угол или держит «−»/«+». */
        public static boolean dragging(PlacementEditorScreen editor)
        {
            return editor.gesture != Gesture.NONE;
        }

        /** Что именно: {@code NONE}, {@code MOVE} (по плоскости), {@code LIFT} (за угол), {@code STEP} («−»/«+»). */
        public static String gesture(PlacementEditorScreen editor)
        {
            return editor.gesture.name();
        }

        /** Origin, который показывает рамка: во время серии — ещё не применённый. */
        public static BlockPos frameOrigin(PlacementEditorScreen editor)
        {
            return editor.gesture == Gesture.NONE ? editor.placement.getOrigin() : editor.dragOrigin;
        }

        /** Кнопка «−» ({@code sign < 0}) или «+» под полем оси {@code axis}: {x, y, w, h}; невидимая — нулевой размер. */
        public static int[] stepButton(PlacementEditorScreen editor, int axis, int sign)
        {
            EditorControls.HoldButton b = sign < 0 ? editor.minus[axis] : editor.plus[axis];

            return b.visible ? new int[] {b.x, b.y, b.w, b.h} : new int[] {b.x, b.y, 0, 0};
        }

        /** Нажимается ли «−»/«+» оси (не закреплена ни она, ни всё размещение). */
        public static boolean stepEnabled(PlacementEditorScreen editor, int axis)
        {
            return editor.minus[axis].isEnabled() && editor.plus[axis].isEnabled();
        }

        /** Перераскладка панели (режим слоёв сменили из теста). */
        public static void relayout(PlacementEditorScreen editor)
        {
            editor.layoutPanel();
        }

        /** «+» ({@code sign > 0}) или «−» основного поля слоя: {x, y, w, h}. */
        public static int[] layerStep(PlacementEditorScreen editor, int sign)
        {
            EditorControls.HoldButton b = sign > 0 ? editor.layerPlus : editor.layerMinus;
            return new int[] {b.x, b.y, b.w, b.h};
        }

        /** Кнопка режима слоёв: {x, y, w, h}. */
        public static int[] layersButton(PlacementEditorScreen editor)
        {
            EditorControls.Cycle c = editor.layers;

            return new int[] {c.x, c.y, c.w, c.h};
        }

        /** Подсказка элемента панели под точкой (как её покажет экран); {@code null} — подсказки нет. */
        public static @Nullable String tipAt(PlacementEditorScreen editor, double x, double y)
        {
            for (EditorControls.Control control : editor.controls)
            {
                if (control.tip != null && control.over(x, y))
                {
                    return control.tip.get();
                }
            }

            return null;
        }

        /** Последнее сообщение в панели (моложе 4 с) или {@code null}. */
        public static @Nullable String message(PlacementEditorScreen editor)
        {
            return editor.feedback.recent(MESSAGE_MS);
        }

        /** Подсвеченный угол рамки (под курсором или схваченный); {@code -1} — ни один. */
        public static int highlightedCorner(PlacementEditorScreen editor)
        {
            return editor.highlightedCorner();
        }

        /** Угол {@code index} габарита на экране: {x, y, глубина} в GUI-координатах; {@code null} — за камерой. */
        public static double @Nullable [] cornerOnScreen(PlacementEditorScreen editor, int index)
        {
            PlacementOps.Bounds b = PlacementOps.bounds(editor.placement);

            return b == null ? null : CursorRay.project(b.corner(index), editor.width, editor.height);
        }

        /** Камера редактора: {@code NORMAL}, {@code ORBIT}, {@code TOP}. */
        public static String view(PlacementEditorScreen editor)
        {
            return editor.view().name();
        }

        /** Переключатель вида в панели: {x, y, w, h} (левый шеврон — назад, остальное — вперёд). */
        public static int[] viewSwitch(PlacementEditorScreen editor)
        {
            EditorControls.Cycle c = editor.viewSwitch;

            return new int[] {c.x, c.y, c.w, c.h};
        }

        /** Замок: {x, y, w, h}. */
        public static int[] lockButton(PlacementEditorScreen editor)
        {
            EditorControls.Button b = editor.lock;

            return new int[] {b.x, b.y, b.w, b.h};
        }

        /** «Смотреть на мир»: {x, y, w, h}. */
        public static int[] worldButton(PlacementEditorScreen editor)
        {
            EditorControls.Button b = editor.seeWorld;

            return new int[] {b.x, b.y, b.w, b.h};
        }

        /** Был ли замок закрыт, когда редактор открыли. */
        public static boolean initialLocked(PlacementEditorScreen editor)
        {
            return editor.initialLocked;
        }

        /** Поле оси {@code axis} правится (не закреплено ни оно, ни всё размещение). */
        public static boolean fieldEnabled(PlacementEditorScreen editor, int axis)
        {
            return editor.fields[axis].isEnabled();
        }

        /** Кнопка «Отменить шаг»: {x, y, w, h}. */
        public static int[] undoButton(PlacementEditorScreen editor)
        {
            EditorControls.Button b = editor.undo;

            return new int[] {b.x, b.y, b.w, b.h};
        }

        /** Кнопки подвала: «Отмена» ({@code false}) или «Готово» ({@code true}) — {x, y, w, h}. */
        public static int[] footerButton(PlacementEditorScreen editor, boolean done)
        {
            EditorControls.Button b = done ? editor.done : editor.cancel;

            return new int[] {b.x, b.y, b.w, b.h};
        }

        /**
         * Все элементы панели, что видны: {x, y, w, h} каждого — проверка раскладки (ничего не
         * наезжает друг на друга и не выходит за панель).
         */
        public static List<int[]> panelControls(PlacementEditorScreen editor)
        {
            List<int[]> out = new ArrayList<>();

            for (EditorControls.Control control : editor.controls)
            {
                if (control.visible)
                {
                    out.add(new int[] {control.x, control.y, control.w, control.h});
                }
            }

            return out;
        }

        /** Кадров экрана с открытия: перетаскивание мышью доходит до экрана раз в кадр. */
        public static long frames(PlacementEditorScreen editor)
        {
            return editor.frames;
        }

        /** Сколько элементов панели раскладка спрятала (не влезли над подвалом). */
        public static int hiddenControls(PlacementEditorScreen editor)
        {
            int n = 0;

            for (EditorControls.Control control : editor.controls)
            {
                n += control.visible ? 0 : 1;
            }

            return n;
        }

        /** Текущий шаг стрелок и PgUp/PgDn. */
        public static int step(PlacementEditorScreen editor)
        {
            return editor.step();
        }

        public static boolean chunkSnap()
        {
            return chunkSnap;
        }

        /** Поле X/Y/Z ({@code axis} 0…2) — как владелец ввода текста (сравнивать по ссылке). */
        public static Object field(PlacementEditorScreen editor, int axis)
        {
            return editor.fields[axis];
        }

        /** Прямоугольник поля X/Y/Z в координатах GUI: {x, y, w, h}; невидимое — нулевой размер. */
        public static int[] fieldBounds(PlacementEditorScreen editor, int axis)
        {
            EditorControls.NumberField field = editor.fields[axis];

            return field.visible ? new int[]{field.x, field.y, field.w, field.h} : new int[]{field.x, field.y, 0, 0};
        }

        /** Какое поле в вводе: 0…2, {@code -1} — ни одно. */
        public static int editingAxis(PlacementEditorScreen editor)
        {
            EditorControls.NumberField field = editor.editingField();

            return field == null ? -1 : List.of(editor.fields).indexOf(field);
        }

        /** Тумблер «Голограммы за мышью»: {x, y, w, h} на экране (клик — обычным вводом). */
        public static int[] liveSwitch(PlacementEditorScreen editor)
        {
            EditorControls.Switch s = editor.live;

            return new int[] { s.x, s.y, s.w, s.h };
        }

        /** Тумблер выключен, потому что схема больше {@link #LIVE_MAX_BLOCKS} блоков. */
        public static boolean liveBlocked(PlacementEditorScreen editor)
        {
            return ! editor.live.isEnabled();
        }

        /** Текст пометки под тумблером и видна ли она целиком (не спрятана раскладкой). */
        public static String liveNote(PlacementEditorScreen editor)
        {
            return editor.liveNote.visible ? editor.liveNote.text() : "";
        }

        public static long liveMaxBlocks()
        {
            return LIVE_MAX_BLOCKS;
        }
    }
}
