package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.CursorRay;
import com.aell.layerlist.schematics.place.EditHistory;
import com.aell.layerlist.schematics.place.OrbitCamera;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.schematics.place.TopViewCamera;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.position.PositionUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Редактор размещения 0.0.27 в режиме «k» — кнопки «−»/«+» у X/Y/Z, подъём за угол рамки, облёт.
 * Мышь — настоящими событиями SDL через {@code MouseHandler} ({@code onMove}/{@code onButton}/{@code onScroll}),
 * как у игрока: перетаскивание доходит до экрана раз в кадр, поэтому после каждого движения шаг ждёт
 * кадр ({@link PlacementEditorScreen.TestHooks#frames}). Клавиши — экрану напрямую, как в {@link KPlace}.
 * <ul>
 *   <li>«−»/«+»: клик по каждой оси = ±шаг, шаг 16 и 64, зажатие ~1,5 с — сдвиг заметно больше шага,
 *       отпускание — стоп, «Отменить шаг» возвращает всю серию; закреплённая ось не двигается;</li>
 *   <li>угол: наведение подсвечивает ручку, перетаскивание вверх/вниз меняет только Y (обычный вид,
 *       облёт, сверху);</li>
 *   <li>облёт: O, вращение ПКМ (рыскание/тангаж меняются, расстояние — нет), пределы тангажа, колесо в
 *       пределах расстояния, F, перетаскивание схемы за курсором, панорама СКМ и Shift+ПКМ; «Готово»,
 *       «Отмена», Esc — камера игрока как была; переключатель вида в панели и T;</li>
 *   <li>большая схема игрока: зажатие «+» не перекладывает размещение Litematica на каждом повторе.</li>
 * </ul>
 * Снимки: панель с «−»/«+», подсказка, ручка угла, облёт с трёх ракурсов, вид сверху — при GUI 2 и 3,
 * и панель в «тесном» окне.
 */
@Environment(EnvType.CLIENT)
final class KEdit
{
    private static final String T = "редактор 0.0.27: ";

    /** Сколько держать «+», мс. */
    private static final long HOLD_MS = 1500L;

    /** Угол тянут на столько пикселей GUI, чтобы вышло ~{@link #LIFT_BLOCKS} блоков, но в этих пределах. */
    private static final double LIFT_PX_MIN = 30.0;
    private static final double LIFT_PX_MAX = 120.0;
    private static final double LIFT_BLOCKS = 2.5;

    /** Отступ точки мыши от краёв свободной части окна. */
    private static final double MARGIN = 12.0;

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private @Nullable SchematicPlacement placement;
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable LoadedSchematic big;
    private boolean bigSearched;

    // Состояние между шагами
    private int heldButton = -1;
    private double mouseX;
    private double mouseY;
    private BlockPos before = BlockPos.ZERO;
    private EditHistory.@Nullable State beforeState;
    private long pressedAt;
    private int corner = -1;
    private double[] cornerAt = new double[3];
    private double liftPx = LIFT_PX_MIN;
    private float yawBefore;
    private float pitchBefore;
    private double distanceBefore;
    /** Расстояние, на котором облёт встал при входе (то же даёт F, пока схема не менялась). */
    private double enterDistance;
    private Vec3 pivotBefore = Vec3.ZERO;
    private int originChanges;
    private BlockPos lastSeenOrigin = BlockPos.ZERO;
    private long idleGap;
    private boolean inactiveNoted;

    /** Стена для проверки столкновений: центр диска, нормаль (путь камеры при отъезде), блоки. */
    private static final double WALL_GAP = 3.0;
    private static final double WALL_RADIUS = 4.0;
    private Vec3 wallAt = Vec3.ZERO;
    private Vec3 wallDir = Vec3.ZERO;
    private final List<BlockPos> wall = new ArrayList<>();

    KEdit(DumpSchematics k)
    {
        this.k = k;
    }

    // ===== Сценарий =====

    /** Всё поведение — при GUI 2 (и снимки), {@code prefix} — к именам снимков. */
    List<DumpSchematics.Step> steps(String prefix)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();

        // Сеанс 1: «−»/«+» и подъём за угол в обычном виде
        s.add(step("размещение k-generated", 30_000L, mc -> this.placeGenerated()));
        s.add(step("открыть (обычный вид)", 3_000L, mc -> this.open(false)));
        s.add(step("открыт, обычный вид", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("раскладка панели", 3_000L, mc -> this.layout(true)));
        s.add(step("мышь в сторону", 2_000L, mc -> this.aside()));
        s.add(step("снимок: панель с «−»/«+»", 6_000L, this.k.settleThen(1200L, this.shot(prefix + "edit-panel"))));
        s.add(step("наведение на «+» X", 2_000L, mc -> this.hoverStep(0, 1)));
        s.add(step("подсказка «+» X", 3_000L, this.k.settleThen(700L, mc -> this.plusTip(0))));
        s.add(step("снимок: подсказка «+»", 6_000L, this.k.settleThen(300L, this.shot(prefix + "edit-plus-tip"))));
        s.add(step("шаг 1", 3_000L, mc -> this.selectStep(0, 1)));

        for (int axis = 0; axis < 3; ++axis)
        {
            int a = axis;
            s.add(step("клик «+» " + axisName(a), 3_000L, mc -> this.clickStep(a, 1, 1)));
            s.add(step("клик «−» " + axisName(a), 3_000L, mc -> this.clickStep(a, -1, 1)));
        }

        s.add(step("слои: ЛКМ — следующий режим", 3_000L, mc -> this.cycleLayers(true)));
        s.add(step("слои: левый шеврон — обратно", 3_000L, mc -> this.cycleLayers(false)));
        s.add(step("Ctrl+V — положение из буфера", 3_000L, mc -> this.pasteOrigin()));
        s.add(step("слой: «+» и «−» в режиме «Один слой»", 3_000L, mc -> this.layerSteps()));
        s.add(step("снова шаг 1", 3_000L, mc -> this.selectStep(0, 1)));

        s.add(step("зажать «+» Z", 3_000L, mc -> this.holdStart(2)));
        s.add(step("«+» Z держится", 5_000L, mc -> this.holdRunning()));
        s.add(step("снимок: «+» зажата", 4_000L, mc -> this.shotNow(prefix + "edit-plus-held")));
        s.add(step("«+» Z держали ~1,5 с — отпустить", 6_000L, mc -> this.holdRelease(2)));
        s.add(step("отпущено — повторов больше нет", 3_000L, this.k.settleThen(600L, mc -> this.holdStopped(2))));
        s.add(step("«Отменить шаг» — вся серия разом", 3_000L, mc -> this.undoHold()));

        s.add(step("закрепить X в Litematica", 3_000L, mc -> this.lockX(true)));
        s.add(step("клик «+» по закреплённой X", 3_000L, mc -> this.clickLocked()));
        s.add(step("снять закрепление X", 3_000L, mc -> this.lockX(false)));

        this.liftSteps(s, "обычный вид", prefix + "edit-corner", true);

        s.add(step("Esc (сеанс «−»/«+»)", 3_000L, mc -> this.key(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc: камера игрока", 5_000L, mc -> this.closedNormalCamera()));

        // Сеанс 2: облёт
        s.add(step("облёт: открыть", 3_000L, mc -> this.open(false)));
        s.add(step("облёт: открыт", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("O — облёт", 3_000L, mc -> this.orbitOn()));
        s.add(step("облёт: камера встала на схему", 5_000L, this.k.settleThen(800L, mc -> this.orbitSettled())));
        s.add(step("мышь в сторону (облёт)", 2_000L, mc -> this.aside()));
        s.add(step("снимок: облёт 1", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-orbit-1"))));
        this.rotateSteps(s, "вращение ПКМ (+80, +30)", 80.0, 30.0);
        s.add(step("снимок: облёт 2", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-orbit-2"))));
        this.rotateSteps(s, "вращение ПКМ (−220, −60)", -220.0, -60.0);
        s.add(step("снимок: облёт 3", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-orbit-3"))));
        this.rotateSteps(s, "тангаж до предела вниз", 0.0, 1200.0);
        s.add(step("тангаж: не круче " + OrbitCamera.MAX_PITCH + "°", 2_000L,
                mc -> this.check("тангаж у верхнего предела", OrbitCamera.pitch() == OrbitCamera.MAX_PITCH)));
        this.rotateSteps(s, "тангаж до предела вверх", 0.0, -2400.0);
        s.add(step("тангаж: не положе " + OrbitCamera.MIN_PITCH + "°", 2_000L,
                mc -> this.check("тангаж у нижнего предела", OrbitCamera.pitch() == OrbitCamera.MIN_PITCH)));
        s.add(step("облёт снизу: камера не ушла под пол", 4_000L, this.k.settleThen(900L, mc -> this.belowFloor())));
        this.rotateSteps(s, "тангаж обратно ~35°", 0.0, Double.NaN);
        s.add(step("колесо +3: ближе", 3_000L, mc -> this.wheel(3.0)));
        s.add(step("колесо +3: расстояние", 4_000L, this.k.settleThen(700L, mc -> this.wheelCloser())));
        s.add(step("колесо −40: дальний предел", 3_000L, mc -> this.wheel(-40.0)));
        s.add(step("колесо −40: не дальше предела", 4_000L, this.k.settleThen(900L, mc -> this.wheelLimit(true))));
        s.add(step("колесо +80: ближний предел", 3_000L, mc -> this.wheel(80.0)));
        s.add(step("колесо +80: не ближе предела", 4_000L, this.k.settleThen(1200L, mc -> this.wheelLimit(false))));
        s.add(step("F — схема целиком", 3_000L, mc -> this.key(InputConstants.KEY_F)));
        s.add(step("F: опора в центре, расстояние как при входе", 4_000L, this.k.settleThen(900L, mc -> this.framed())));
        this.wallSteps(s);
        this.orbitDragSteps(s);
        this.liftSteps(s, "облёт", prefix + "edit-orbit-corner", false);
        this.panSteps(s, "СКМ", InputConstants.MOUSE_BUTTON_MIDDLE, 0);
        this.panSteps(s, "Shift+ПКМ", InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOD_SHIFT);
        s.add(step("облёт: «Готово» мышью", 3_000L, mc -> this.clickFooter(true)));
        s.add(step("«Готово»: камера игрока", 5_000L, mc -> this.closedNormalCamera()));

        // Сеанс 3: переключатель вида и T, вид сверху с ручками; Esc и «Отмена» из облёта
        s.add(step("вид: открыть", 3_000L, mc -> this.open(false)));
        s.add(step("вид: открыт", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("переключатель: → облёт", 3_000L, mc -> this.clickView(false, "ORBIT")));
        s.add(step("переключатель: → сверху", 3_000L, mc -> this.clickView(false, "TOP")));
        s.add(step("переключатель: → обычный", 3_000L, mc -> this.clickView(false, "NORMAL")));
        s.add(step("переключатель: ‹ назад → сверху", 3_000L, mc -> this.clickView(true, "TOP")));
        s.add(step("T из сверху (пришли из обычного) → обычный", 3_000L, mc -> this.keyView(InputConstants.KEY_T, "NORMAL")));
        s.add(step("O → облёт", 3_000L, mc -> this.keyView(InputConstants.KEY_O, "ORBIT")));
        s.add(step("T → сверху", 3_000L, mc -> this.keyView(InputConstants.KEY_T, "TOP")));
        s.add(step("мышь в сторону (сверху)", 2_000L, mc -> this.aside()));
        s.add(step("снимок: вид сверху", 6_000L, this.k.settleThen(1200L, this.shot(prefix + "edit-top"))));
        this.liftSteps(s, "сверху", prefix + "edit-top-corner", false);
        s.add(step("T из сверху (пришли из облёта) → облёт", 3_000L, mc -> this.keyView(InputConstants.KEY_T, "ORBIT")));
        s.add(step("Esc из облёта", 3_000L, mc -> this.key(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc: камера игрока", 5_000L, mc -> this.closedNormalCamera()));
        s.add(step("снова открыть", 3_000L, mc -> this.open(false)));
        s.add(step("снова открыт", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("O → облёт (для «Отмены»)", 3_000L, mc -> this.keyView(InputConstants.KEY_O, "ORBIT")));
        s.add(step("«Отмена» мышью", 3_000L, mc -> this.clickFooter(false)));
        s.add(step("«Отмена»: камера игрока", 5_000L, mc -> this.closedNormalCamera()));
        s.add(step("убрать размещение", 3_000L, mc -> this.removeCurrent()));
        return s;
    }

    /** GUI 3: та же раскладка и снимки, клик «−»/«+», ручка, облёт и сверху. */
    List<DumpSchematics.Step> stepsAtScale3(String prefix)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("GUI 3: размещение", 30_000L, mc -> this.placeGenerated()));
        s.add(step("GUI 3: открыть", 3_000L, mc -> this.open(false)));
        s.add(step("GUI 3: открыт", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("GUI 3: раскладка панели", 3_000L, mc -> this.layout(true)));
        s.add(step("GUI 3: мышь в сторону", 2_000L, mc -> this.aside()));
        s.add(step("снимок: панель GUI 3", 6_000L, this.k.settleThen(1200L, this.shot(prefix + "edit-panel"))));
        s.add(step("GUI 3: шаг 1", 3_000L, mc -> this.selectStep(0, 1)));
        s.add(step("GUI 3: «+» X", 3_000L, mc -> this.clickStep(0, 1, 1)));
        s.add(step("GUI 3: «−» X", 3_000L, mc -> this.clickStep(0, -1, 1)));
        s.add(step("GUI 3: наведение на «+» Y", 2_000L, mc -> this.hoverStep(1, 1)));
        s.add(step("снимок: подсказка «+» GUI 3", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-plus-tip"))));
        s.add(step("GUI 3: угол под курсором", 3_000L, mc -> this.hoverCorner(true, 0)));
        s.add(step("GUI 3: ручка подсвечена", 3_000L, this.waitFrames(3, mc -> this.highlighted(true))));
        s.add(step("снимок: ручка угла GUI 3", 6_000L, this.k.settleThen(700L, this.shot(prefix + "edit-corner"))));
        s.add(step("GUI 3: O — облёт", 3_000L, mc -> this.orbitOn()));
        s.add(step("GUI 3: облёт встал", 5_000L, this.k.settleThen(800L, mc -> this.orbitSettled())));
        s.add(step("GUI 3: мышь в сторону (облёт)", 2_000L, mc -> this.aside()));
        s.add(step("снимок: облёт GUI 3", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-orbit-1"))));
        this.rotateSteps(s, "GUI 3: вращение ПКМ (+120, +20)", 120.0, 20.0);
        s.add(step("снимок: облёт GUI 3, другой ракурс", 6_000L, this.k.settleThen(900L, this.shot(prefix + "edit-orbit-2"))));
        s.add(step("GUI 3: T — сверху", 3_000L, mc -> this.keyView(InputConstants.KEY_T, "TOP")));
        s.add(step("GUI 3: мышь в сторону (сверху)", 2_000L, mc -> this.aside()));
        s.add(step("снимок: сверху GUI 3", 6_000L, this.k.settleThen(1200L, this.shot(prefix + "edit-top"))));
        s.add(step("GUI 3: Esc", 3_000L, mc -> this.key(InputConstants.KEY_ESCAPE)));
        s.add(step("GUI 3: Esc — камера игрока", 5_000L, mc -> this.closedNormalCamera()));
        s.add(step("GUI 3: убрать размещение", 3_000L, mc -> this.removeCurrent()));
        return s;
    }

    /** «Тесное» окно (ниже 300 px GUI): панель без заголовков разделов — раскладка и снимок. */
    List<DumpSchematics.Step> tightSteps(String prefix)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("тесно: размещение", 30_000L, mc -> this.placeGenerated()));
        s.add(step("тесно: открыть", 3_000L, mc -> this.open(false)));
        s.add(step("тесно: открыт", 10_000L, mc -> this.opened("NORMAL")));
        s.add(step("тесно: раскладка панели", 3_000L, mc -> this.layout(false)));
        s.add(step("тесно: «+» Y", 3_000L, mc -> this.clickStep(1, 1, -1)));
        s.add(step("тесно: «−» Y", 3_000L, mc -> this.clickStep(1, -1, -1)));
        s.add(step("тесно: мышь в сторону", 2_000L, mc -> this.aside()));
        s.add(step("снимок: панель, тесное окно", 6_000L, this.k.settleThen(1200L, this.shot(prefix + "edit-panel-tight"))));
        s.add(step("тесно: Esc", 3_000L, mc -> this.key(InputConstants.KEY_ESCAPE)));
        s.add(step("тесно: Esc — камера игрока", 5_000L, mc -> this.closedNormalCamera()));
        s.add(step("тесно: убрать размещение", 3_000L, mc -> this.removeCurrent()));
        return s;
    }

    /**
     * Самая большая схема игрока (как у постера, {@code LL_K_POSTER_BIG} — своя): зажатие «+» Y ~1,5 с.
     * Схема больше 200 000 блоков — голограммы не едут, и размещение Litematica за всё зажатие не
     * меняется ни разу; паузы клиента в покое, при зажатии и после отпускания — строками INFO.
     */
    List<DumpSchematics.Step> bigSteps()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("большая: найти и загрузить", 300_000L, mc -> this.loadBig()));
        s.add(step("большая: открыть", 3_000L, mc -> this.openBig()));
        s.add(step("большая: открыт", 20_000L, mc -> this.opened("NORMAL")));
        s.add(step("большая: покой 4 с", 30_000L, this.k.settleThen(4_000L, mc -> this.bigIdle())));
        s.add(step("большая: шаг 1", 3_000L, mc -> this.selectStep(0, 1)));
        s.add(step("большая: зажать «+» Y", 3_000L, mc -> this.bigHoldStart()));
        s.add(step("большая: держим ~1,5 с", 30_000L, mc -> this.bigHolding()));
        s.add(step("большая: отпустить", 3_000L, mc -> this.bigRelease()));
        s.add(step("большая: после отпускания 4 с", 30_000L, this.k.settleThen(4_000L, mc -> this.bigAfter())));
        s.add(step("большая: Esc", 3_000L, mc -> this.key(InputConstants.KEY_ESCAPE)));
        s.add(step("большая: Esc — как при открытии", 20_000L, mc -> this.bigReverted()));
        s.add(step("большая: убрать", 10_000L, mc -> this.removeBig()));
        return s;
    }

    /** Уборка в конце прогона: свои размещения, большая схема, зажатая кнопка мыши. */
    void cleanup()
    {
        if (this.heldButton >= 0)
        {
            this.realButton(this.heldButton, 0, InputConstants.RELEASE);
        }

        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.unloadBig();
    }

    private void liftSteps(List<DumpSchematics.Step> s, String where, String shotName, boolean down)
    {
        s.add(step(where + ": угол под курсором", 3_000L, mc -> this.hoverCorner(! where.equals("сверху"), -1)));
        s.add(step(where + ": ручка подсвечена", 3_000L, this.waitFrames(3, mc -> this.highlighted(! where.equals("сверху")))));
        s.add(step("снимок: ручка угла (" + where + ")", 6_000L, this.k.settleThen(700L, this.shot(shotName))));
        s.add(step(where + ": ЛКМ на угол", 3_000L, mc -> this.liftPress()));
        s.add(step(where + ": тянуть вверх", 3_000L, mc -> this.liftMove(-1.0)));
        s.add(step(where + ": рамка поднялась", 3_000L, this.waitFrames(3, mc -> this.liftFrame(true))));
        s.add(step(where + ": отпустить — схема выше", 3_000L, mc -> this.liftRelease(true)));

        if (down)
        {
            s.add(step(where + ": снова угол", 3_000L, mc -> this.hoverCorner(true, 1)));
            s.add(step(where + ": ручка подсвечена (вниз)", 3_000L, this.waitFrames(3, mc -> this.highlighted(true))));
            s.add(step(where + ": ЛКМ на угол (вниз)", 3_000L, mc -> this.liftPress()));
            s.add(step(where + ": тянуть вниз", 3_000L, mc -> this.liftMove(1.0)));
            s.add(step(where + ": рамка опустилась", 3_000L, this.waitFrames(3, mc -> this.liftFrame(false))));
            s.add(step(where + ": отпустить — схема ниже", 3_000L, mc -> this.liftRelease(false)));
        }
    }

    private void rotateSteps(List<DumpSchematics.Step> s, String what, double dx, double dy)
    {
        s.add(step(what + ": ПКМ", 3_000L, mc -> this.rotatePress()));
        s.add(step(what + ": тянуть", 3_000L, mc -> this.rotateMove(dx, dy)));
        s.add(step(what + ": кадр", 3_000L, this.waitFrames(3, mc -> true)));
        s.add(step(what + ": отпустить", 3_000L, mc -> this.rotateRelease(what, dx, dy)));
    }

    /** Столкновения облёта (1.0.7): стена камня за камерой, отъезд колесом упирается в неё, без стены — догон. */
    private void wallSteps(List<DumpSchematics.Step> s)
    {
        s.add(step("стена: поставить за камерой", 3_000L, mc -> this.wallBuild()));
        s.add(step("стена: дошла до клиента", 5_000L, mc -> this.wallArrived(true)));
        s.add(step("стена: колесо −6 (дальше)", 3_000L, mc -> this.wheel(-6.0)));
        s.add(step("стена: камера упёрлась", 4_000L, this.k.settleThen(1200L, mc -> this.wallHeld())));
        s.add(step("стена: убрать", 3_000L, mc -> this.wallClear()));
        s.add(step("стена: убрана у клиента", 5_000L, mc -> this.wallArrived(false)));
        s.add(step("стена: камера догнала позу", 4_000L, this.k.settleThen(1200L, mc -> this.wallGone())));
        s.add(step("стена: F — схема целиком", 3_000L, mc -> this.key(InputConstants.KEY_F)));
        s.add(step("стена: F — как при входе", 4_000L, this.k.settleThen(900L, mc -> this.framed())));
    }

    private void orbitDragSteps(List<DumpSchematics.Step> s)
    {
        s.add(step("облёт: схватить схему ЛКМ", 3_000L, mc -> this.orbitDragPress()));
        s.add(step("облёт: тянуть на (+4, 0, +3)", 3_000L, mc -> this.orbitDragMove()));
        s.add(step("облёт: рамка за курсором", 3_000L, this.waitFrames(3, mc -> this.orbitDragFrame())));
        s.add(step("облёт: отпустить — схема за курсором", 3_000L, mc -> this.orbitDragRelease()));
    }

    private void panSteps(List<DumpSchematics.Step> s, String how, int button, int mods)
    {
        s.add(step("облёт: панорама " + how, 3_000L, mc -> this.panPress(button, mods)));
        s.add(step("облёт: панорама " + how + " — тянуть", 3_000L, mc -> this.panMove()));
        s.add(step("облёт: панорама " + how + " — кадр", 3_000L, this.waitFrames(3, mc -> true)));
        s.add(step("облёт: панорама " + how + " — отпустить", 3_000L, mc -> this.panRelease(how, button)));
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(T + name, timeoutMs, action);
    }

    private DumpSchematics.Action shot(String name)
    {
        return mc ->
        {
            this.k.shot(mc, name);
            return true;
        };
    }

    private boolean shotNow(String name)
    {
        this.k.shot(Minecraft.getInstance(), name);
        return true;
    }

    /** Подождать, пока экран нарисует ещё {@code n} кадров (ввод мыши доходит до него раз в кадр), и дальше. */
    private DumpSchematics.Action waitFrames(int n, DumpSchematics.Action then)
    {
        long[] start = {-1L};

        return mc ->
        {
            PlacementEditorScreen e = editor();

            if (e == null)
            {
                return then.run(mc);
            }

            long frames = PlacementEditorScreen.TestHooks.frames(e);

            if (start[0] < 0L)
            {
                start[0] = frames;
            }

            return frames >= start[0] + n && then.run(mc);
        };
    }

    private boolean check(String what, boolean pass)
    {
        this.k.check(T + what, pass);
        return true;
    }

    private void check(String what, Object expected, Object actual)
    {
        this.k.check(T + what, expected, actual);
    }

    private static String axisName(int axis)
    {
        return axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
    }

    // ===== Мышь — событиями SDL =====

    /** Курсор в точку GUI: событие движения окна, как от {@code SDL_EVENT_MOUSE_MOTION}. */
    private void realMove(double guiX, double guiY)
    {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double dx = guiX - this.mouseX;
        double dy = guiY - this.mouseY;

        this.mouseX = guiX;
        this.mouseY = guiY;
        mc.mouseHandler.onMove(window.handle(), guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                guiY * window.getScreenHeight() / window.getGuiScaledHeight(), 0.0, 0.0);

        // Без фокуса окна ваниль не разносит перетаскивание (handleAccumulatedMovement) — тогда то же
        // событие экрану, что разнесла бы она
        if (this.heldButton >= 0 && ! mc.isWindowActive() && mc.gui.screen() != null)
        {
            if (! this.inactiveNoted)
            {
                this.inactiveNoted = true;
                this.k.info(T + "окно без фокуса — перетаскивание доставляется экрану событием mouseDragged");
            }

            mc.gui.screen().mouseDragged(new MouseButtonEvent(guiX, guiY, new MouseButtonInfo(this.heldButton, 0)), dx, dy);
        }
    }

    private void realButton(int button, int mods, int action)
    {
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, mods), action);
        this.heldButton = action == InputConstants.PRESS ? button : -1;
    }

    private void realClick(double guiX, double guiY)
    {
        this.realMove(guiX, guiY);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.PRESS);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.RELEASE);
    }

    private void realClick(double guiX, double guiY, int button)
    {
        this.realMove(guiX, guiY);
        this.realButton(button, 0, InputConstants.PRESS);
        this.realButton(button, 0, InputConstants.RELEASE);
    }

    private void realClick(int[] box)
    {
        this.realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
    }

    private boolean aside()
    {
        PlacementEditorScreen e = editor();
        this.realMove(2.0, e == null ? 100.0 : PlacementEditorScreen.TestHooks.freeBottom(e) - 4.0);
        return true;
    }

    private boolean key(int key)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "клавиша " + key + ": нет редактора");
            return true;
        }

        KProbe.press(e, key);
        return true;
    }

    // ===== Открытие и закрытие =====

    private static @Nullable PlacementEditorScreen editor()
    {
        return PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());
    }

    private boolean placeGenerated()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        if (generated == null)
        {
            this.k.fail(T + "нет k-generated.litematic");
            return true;
        }

        LoadedSchematic loaded = this.k.loaded(generated.path());

        if (loaded == null)
        {
            if (this.loading == null)
            {
                this.loading = SchematicLibrary.get().load(generated);
            }

            if (! this.loading.isDone())
            {
                return false;
            }

            try
            {
                loaded = this.loading.join();
            }
            catch (RuntimeException e)
            {
                this.k.fail(T + "загрузка k-generated: " + e);
                return true;
            }
            finally
            {
                this.loading = null;
            }
        }

        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.placement = p;
        this.made.add(p);
        this.beforeState = EditHistory.State.of(p);
        this.k.ok(T + "размещение k-generated: origin " + p.getOrigin().toShortString());
        return true;
    }

    private boolean open(boolean top)
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null || this.placement == null)
        {
            this.k.fail(T + "нет экрана «Схемы» или размещения");
            return true;
        }

        this.beforeState = EditHistory.State.of(this.placement);
        PlacementEditorScreen.open(gui, this.placement, false, top);
        return true;
    }

    private boolean opened(String view)
    {
        PlacementEditorScreen e = editor();

        if (e == null || PlacementEditorScreen.TestHooks.frames(e) < 2)
        {
            return false;
        }

        this.check("вид при открытии", view, PlacementEditorScreen.TestHooks.view(e));
        this.k.info(T + "окно в фокусе (SDL): " + Minecraft.getInstance().isWindowActive());
        return true;
    }

    /** Камера снова у игрока: облёт и вид сверху погасли, поза — глаза игрока, камера не отвязана. */
    private boolean closedNormalCamera()
    {
        Minecraft mc = Minecraft.getInstance();

        if (editor() != null)
        {
            return false;
        }

        // Поза камеры обновится к следующему кадру — ждём хотя бы один после закрытия
        if (Util.getMillis() - this.k.startedAt() < 300L)
        {
            return false;
        }

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 eye = mc.player == null ? Vec3.ZERO : mc.player.getEyePosition();
        double off = camera.position().distanceTo(eye);

        this.check("закрыт: облёт выключен", false, OrbitCamera.active());
        this.check("закрыт: вид сверху выключен", false, TopViewCamera.active());
        this.check("закрыт: камера не отвязана", false, camera.isDetached());
        this.check(String.format(Locale.ROOT, "закрыт: камера у глаз игрока (%.3f блока)", off), off < 0.6);
        this.check("закрыт: вернулись в «Схемы»", true, this.k.gui() != null);
        return true;
    }

    private boolean removeCurrent()
    {
        SchematicPlacement p = this.placement;

        if (p != null && PlacementOps.all().contains(p))
        {
            PlacementOps.remove(p);
        }

        this.check("размещение убрано", false, p != null && PlacementOps.all().contains(p));
        this.placement = null;
        return true;
    }

    // ===== Раскладка =====

    /**
     * Все видимые элементы панели — внутри неё и не наезжают друг на друга; в просторном окне не
     * спрятан ни один. Числа — строкой INFO: сколько спрятано в тесном.
     */
    private boolean layout(boolean roomy)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "раскладка: нет редактора");
            return true;
        }

        List<int[]> rects = PlacementEditorScreen.TestHooks.panelControls(e);
        int left = PlacementEditorScreen.TestHooks.freeRight(e);
        int overlaps = 0;
        int outside = 0;

        for (int i = 0; i < rects.size(); ++i)
        {
            int[] a = rects.get(i);

            if (a[0] < left || a[0] + a[2] > e.width || a[1] < 0 || a[1] + a[3] > e.height)
            {
                ++outside;
            }

            for (int j = i + 1; j < rects.size(); ++j)
            {
                int[] b = rects.get(j);

                if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3])
                {
                    ++overlaps;
                    this.k.info(T + "наезжают: " + rect(a) + " и " + rect(b));
                }
            }
        }

        int hidden = PlacementEditorScreen.TestHooks.hiddenControls(e);
        this.k.info(T + "панель " + e.width + "×" + e.height + " GUI: элементов " + rects.size() + ", спрятано " + hidden);
        this.check("раскладка: элементы не наезжают друг на друга", 0, overlaps);
        this.check("раскладка: элементы внутри панели", 0, outside);

        for (int axis = 0; axis < 3; ++axis)
        {
            int[] field = PlacementEditorScreen.TestHooks.fieldBounds(e, axis);
            int[] minus = PlacementEditorScreen.TestHooks.stepButton(e, axis, -1);
            int[] plus = PlacementEditorScreen.TestHooks.stepButton(e, axis, 1);
            this.check("раскладка: поле и «−»/«+» " + axisName(axis) + " видны", true,
                    field[2] > 0 && minus[2] > 0 && plus[2] > 0);
        }

        if (roomy)
        {
            this.check("раскладка: в просторном окне ничего не спрятано", 0, hidden);
        }

        return true;
    }

    private static String rect(int[] r)
    {
        return "(" + r[0] + ", " + r[1] + ", " + r[2] + "×" + r[3] + ")";
    }

    // ===== «−»/«+» =====

    private boolean hoverStep(int axis, int sign)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "наведение: нет редактора");
            return true;
        }

        int[] b = PlacementEditorScreen.TestHooks.stepButton(e, axis, sign);
        this.realMove(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
        return true;
    }

    /** Подсказка «+»: про ось и шаг, про зажатие и про «Отменить шаг». */
    private boolean plusTip(int axis)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "подсказка: нет редактора");
            return true;
        }

        int[] b = PlacementEditorScreen.TestHooks.stepButton(e, axis, 1);
        String tip = PlacementEditorScreen.TestHooks.tipAt(e, b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
        String want = I18n.get("layerlist.schematics.place.field.plus.tip", axisName(axis),
                PlacementEditorScreen.TestHooks.step(e));
        this.check("подсказка «+» " + axisName(axis), want, tip);
        return true;
    }

    private fi.dy.masa.malilib.util.LayerMode layersBefore;

    /** Кнопка режима слоёв настоящим кликом: клик — следующий режим, левый шеврон — назад к исходному. */
    private boolean cycleLayers(boolean forward)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "слои: нет редактора");
            return true;
        }

        var range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
        var values = fi.dy.masa.malilib.util.LayerMode.VALUES;
        int[] b = PlacementEditorScreen.TestHooks.layersButton(e);

        if (forward)
        {
            this.layersBefore = range.getLayerMode();
            this.realClick(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
            this.check("слои: следующий режим", values.get((values.indexOf(this.layersBefore) + 1) % values.size()).name(),
                    range.getLayerMode().name());
        }
        else
        {
            this.realClick(b[0] + 4.0, b[1] + b[3] / 2.0);
            this.check("слои: обратно", String.valueOf(this.layersBefore), range.getLayerMode().name());
        }

        return true;
    }

    /** Ctrl+V вне поля: три числа из буфера — сразу всё положение; Ctrl+C — обратно в буфер. */
    private boolean pasteOrigin()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "вставка: нет редактора");
            return true;
        }

        BlockPos was = p.getOrigin();
        BlockPos want = was.offset(3, 1, -2);
        Minecraft.getInstance().keyboardHandler.setClipboard(want.getX() + ", " + want.getY() + ", " + want.getZ());
        KProbe.press(e, InputConstants.KEY_V, InputConstants.MOD_CONTROL);
        this.check("Ctrl+V: положение", want.toShortString(), p.getOrigin().toShortString());
        Minecraft.getInstance().keyboardHandler.setClipboard("");
        KProbe.press(e, InputConstants.KEY_C, InputConstants.MOD_CONTROL);
        this.check("Ctrl+C: положение в буфере", want.getX() + " " + want.getY() + " " + want.getZ(),
                Minecraft.getInstance().keyboardHandler.getClipboard());
        // Вернуть, где было: дальше шаги ищут углы рамки на прежнем месте
        Minecraft.getInstance().keyboardHandler.setClipboard(was.getX() + " " + was.getY() + " " + was.getZ());
        KProbe.press(e, InputConstants.KEY_V, InputConstants.MOD_CONTROL);
        this.check("Ctrl+V: положение вернулось", was.toShortString(), p.getOrigin().toShortString());
        return true;
    }

    /** Режим «Один слой»: «+» поднимает слой на 1, «−» возвращает. */
    private boolean layerSteps()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "слой: нет редактора");
            return true;
        }

        var range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
        var before = range.getLayerMode();
        range.setLayerMode(fi.dy.masa.malilib.util.LayerMode.SINGLE_LAYER);
        PlacementEditorScreen.TestHooks.relayout(e);
        int start = range.getLayerSingle();
        int[] plus = PlacementEditorScreen.TestHooks.layerStep(e, 1);
        this.realClick(plus);
        this.check("слой «+»", start + 1, range.getLayerSingle());
        int[] minus = PlacementEditorScreen.TestHooks.layerStep(e, -1);
        this.realClick(minus);
        this.check("слой «−»", start, range.getLayerSingle());
        range.setLayerMode(before);
        PlacementEditorScreen.TestHooks.relayout(e);
        return true;
    }

    /** «Шаг» убран: кнопки и стрелки всегда двигают на 1 блок. */
    private boolean selectStep(int cell, int want)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "шаг: нет редактора");
            return true;
        }

        if (PlacementEditorScreen.TestHooks.chunkSnap())
        {
            // «По сетке чанков» помнится до выхода из игры — для точных чисел выключаем
            KProbe.press(e, InputConstants.KEY_C);
        }

        this.check("шаг кликом", want, PlacementEditorScreen.TestHooks.step(e));
        return true;
    }

    /** Клик «−»/«+»: origin по этой оси ±шаг ({@code step < 0} — текущий), по остальным — как был. */
    private boolean clickStep(int axis, int sign, int step)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "«−»/«+»: нет редактора");
            return true;
        }

        int s = step < 0 ? PlacementEditorScreen.TestHooks.step(e) : step;
        BlockPos was = p.getOrigin();
        BlockPos want = was.offset(axis == 0 ? sign * s : 0, axis == 1 ? sign * s : 0, axis == 2 ? sign * s : 0);
        this.realClick(PlacementEditorScreen.TestHooks.stepButton(e, axis, sign));
        this.check("клик «" + (sign < 0 ? "−" : "+") + "» " + axisName(axis) + " (шаг " + s + "): origin",
                want.toShortString(), p.getOrigin().toShortString());
        this.check("клик «" + (sign < 0 ? "−" : "+") + "» " + axisName(axis) + ": серия закончилась", "NONE",
                PlacementEditorScreen.TestHooks.gesture(e));
        return true;
    }

    private boolean holdStart(int axis)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "зажатие: нет редактора");
            return true;
        }

        int[] b = PlacementEditorScreen.TestHooks.stepButton(e, axis, 1);
        this.before = p.getOrigin();
        this.beforeState = EditHistory.State.of(p);
        this.realMove(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.PRESS);
        this.pressedAt = Util.getMillis();
        this.check("зажатие: серия «−»/«+» началась", "STEP", PlacementEditorScreen.TestHooks.gesture(e));
        this.check("зажатие: первый шаг — сразу", this.before.offset(0, 0, 1).toShortString(),
                PlacementEditorScreen.TestHooks.frameOrigin(e).toShortString());
        return true;
    }

    private boolean holdRunning()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "зажатие: редактор закрылся");
            return true;
        }

        // Середина зажатия: повторы уже пошли (задержка 350 мс), кнопка ещё держится
        if (Util.getMillis() - this.pressedAt < 900L)
        {
            return false;
        }

        int dz = PlacementEditorScreen.TestHooks.frameOrigin(e).getZ() - this.before.getZ();
        this.check("зажатие ~0,9 с: рамка уехала дальше одного шага (" + dz + ")", dz > 2);
        return true;
    }

    private boolean holdRelease(int axis)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "зажатие: редактор закрылся");
            return true;
        }

        long held = Util.getMillis() - this.pressedAt;

        if (held < HOLD_MS)
        {
            return false;
        }

        BlockPos frame = PlacementEditorScreen.TestHooks.frameOrigin(e);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.RELEASE);
        int dz = p.getOrigin().getZ() - this.before.getZ();
        long expected = 1 + repeats(held);
        this.k.info(T + "зажатие " + held + " мс: сдвиг " + dz + " шагов (по расписанию повторов ~" + expected + ")");
        this.check("зажатие ~1,5 с: сдвиг заметно больше шага (" + dz + " ≥ 8)", dz >= 8);
        this.check("зажатие: не больше, чем по расписанию (" + dz + " ≤ " + (expected + 1) + ")", dz <= expected + 1);
        this.check("отпущено: размещение там, где рамка", frame.toShortString(), p.getOrigin().toShortString());
        this.check("отпущено: X и Y не тронуты", this.before.getX() + " " + this.before.getY(),
                p.getOrigin().getX() + " " + p.getOrigin().getY());
        this.check("отпущено: серия закончилась", "NONE", PlacementEditorScreen.TestHooks.gesture(e));
        this.lastSeenOrigin = p.getOrigin();
        return true;
    }

    private boolean holdStopped(int axis)
    {
        SchematicPlacement p = this.placement;

        if (p == null)
        {
            this.k.fail(T + "после отпускания: нет размещения");
            return true;
        }

        this.check("после отпускания повторов нет", this.lastSeenOrigin.toShortString(), p.getOrigin().toShortString());
        return true;
    }

    private boolean undoHold()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.beforeState == null)
        {
            this.k.fail(T + "«Отменить шаг»: нет редактора");
            return true;
        }

        this.realClick(PlacementEditorScreen.TestHooks.undoButton(e));
        this.check("«Отменить шаг» вернул всю серию зажатия одним шагом", this.beforeState, EditHistory.State.of(p));
        return true;
    }

    private boolean lockX(boolean lock)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "закрепление: нет редактора");
            return true;
        }

        p.setCoordinateLocked(PositionUtils.CoordinateType.X, lock);
        this.check("X закреплена в Litematica: " + lock, lock, p.isCoordinateLocked(PositionUtils.CoordinateType.X));
        this.check("«−»/«+» X нажимаются: " + ! lock, ! lock, PlacementEditorScreen.TestHooks.stepEnabled(e, 0));
        this.check("«−»/«+» Y нажимаются", true, PlacementEditorScreen.TestHooks.stepEnabled(e, 1));
        return true;
    }

    private boolean clickLocked()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "закреплённая ось: нет редактора");
            return true;
        }

        BlockPos was = p.getOrigin();
        this.realClick(PlacementEditorScreen.TestHooks.stepButton(e, 0, 1));
        String message = PlacementEditorScreen.TestHooks.message(e);
        this.check("закреплённая X: origin не сдвинулся", was.toShortString(), p.getOrigin().toShortString());
        this.check("закреплённая X: рамка не сдвинулась", was.toShortString(),
                PlacementEditorScreen.TestHooks.frameOrigin(e).toShortString());
        this.check("закреплённая X: сообщение в панели", I18n.get("layerlist.schematics.place.msg.axis_locked", "X"), message);
        return true;
    }

    /** То же расписание повторов, что у кнопки (задержка 350 мс, 8 → 25 в секунду за 1,5 с). */
    private static long repeats(long ms)
    {
        if (ms < 350L)
        {
            return 0;
        }

        double t = (ms - 350L) / 1000.0;
        double grow = (25.0 - 8.0) / 1.5;
        double n = t <= 1.5 ? 8.0 * t + grow * t * t * 0.5 : 8.0 * 1.5 + grow * 1.5 * 1.5 * 0.5 + 25.0 * (t - 1.5);

        return 1 + (long) Math.floor(n);
    }

    // ===== Угол =====

    /**
     * Курсор на угол рамки: на экране, с запасом от краёв (в сторону {@code direction} — −1 вверх, +1 вниз,
     * 0 — никуда — ещё и на рывок мыши), подальше от других углов. {@code strict} — угол должен ловиться
     * однозначно (в виде сверху верхний и нижний углы почти совпадают — тогда годится любой из пары).
     */
    private boolean hoverCorner(boolean strict, int direction)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "угол: нет редактора");
            return true;
        }

        double right = PlacementEditorScreen.TestHooks.freeRight(e) - MARGIN;
        double bottom = PlacementEditorScreen.TestHooks.freeBottom(e) - MARGIN;
        double[][] at = new double[8][];
        double[] need = new double[8];

        for (int i = 0; i < 8; ++i)
        {
            at[i] = PlacementEditorScreen.TestHooks.cornerOnScreen(e, i);

            // Сколько пикселей — ~2,5 блока на глубине этого угла: подъём должен дать хотя бы блок
            need[i] = at[i] == null ? 0.0 : Math.max(LIFT_PX_MIN, Math.min(LIFT_PX_MAX,
                    LIFT_BLOCKS / Math.max(1.0E-6, CursorRay.blocksPerPixel(at[i][2], e.height))));
        }

        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < 8; ++i)
        {
            double[] a = at[i];

            double up = direction < 0 ? need[i] : 0.0;
            double down = direction > 0 ? need[i] : 0.0;

            if (a == null || a[0] < MARGIN || a[0] > right || a[1] - up < MARGIN || a[1] + down > bottom)
            {
                continue;
            }

            double nearest = Double.POSITIVE_INFINITY;

            for (int j = 0; j < 8; ++j)
            {
                if (j != i && at[j] != null)
                {
                    nearest = Math.min(nearest, Math.hypot(at[j][0] - a[0], at[j][1] - a[1]));
                }
            }

            if (strict && nearest < PlacementEditorScreen.CORNER_PICK_PX * 2.0)
            {
                continue;
            }

            // Верхний угол лучше: его видно над схемой, а не в земле
            double score = Math.min(nearest, 60.0) + ((i & 2) != 0 ? 100.0 : 0.0);

            if (score > bestScore)
            {
                best = i;
                bestScore = score;
            }
        }

        if (best < 0)
        {
            this.k.fail(T + "угол: ни один угол рамки не на свободной части экрана с запасом");
            this.corner = -1;
            return true;
        }

        this.corner = best;
        this.cornerAt = at[best];
        this.liftPx = need[best];
        this.k.info(String.format(Locale.ROOT, "%sугол %d на экране (%.1f, %.1f), глубина %.1f, тянуть на %.0f px", T, best,
                at[best][0], at[best][1], at[best][2], this.liftPx));
        this.realMove(at[best][0], at[best][1]);
        return true;
    }

    private boolean highlighted(boolean strict)
    {
        PlacementEditorScreen e = editor();

        if (e == null || this.corner < 0)
        {
            this.k.fail(T + "ручка: нет редактора или угла");
            return true;
        }

        int lit = PlacementEditorScreen.TestHooks.highlightedCorner(e);

        if (strict)
        {
            this.check("наведение подсвечивает ручку угла " + this.corner, this.corner, lit);
        }
        else
        {
            this.check("наведение подсвечивает ручку (угол " + lit + ", целились в " + this.corner + ")", lit >= 0);
        }

        return true;
    }

    private boolean liftPress()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.corner < 0)
        {
            this.k.fail(T + "подъём: нет редактора или угла");
            return true;
        }

        this.before = p.getOrigin();
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.PRESS);
        this.check("ЛКМ на угол: подъём, а не перетаскивание", "LIFT", PlacementEditorScreen.TestHooks.gesture(e));
        return true;
    }

    /** {@code direction} −1 — вверх по экрану, +1 — вниз. */
    private boolean liftMove(double direction)
    {
        this.realMove(this.cornerAt[0], this.cornerAt[1] + direction * this.liftPx);
        return true;
    }

    private boolean liftFrame(boolean up)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "подъём: редактор закрылся");
            return true;
        }

        BlockPos frame = PlacementEditorScreen.TestHooks.frameOrigin(e);
        int dy = frame.getY() - this.before.getY();
        this.check("рамка " + (up ? "выше" : "ниже") + " (" + dy + " блоков), X/Z на месте",
                (up ? dy > 0 : dy < 0) && frame.getX() == this.before.getX() && frame.getZ() == this.before.getZ());
        return true;
    }

    private boolean liftRelease(boolean up)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail(T + "подъём: редактор закрылся");
            return true;
        }

        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.RELEASE);
        BlockPos now = p.getOrigin();
        int dy = now.getY() - this.before.getY();
        this.k.info(T + "подъём за угол на " + Math.round(this.liftPx) + " px: Y " + (dy >= 0 ? "+" : "") + dy);
        this.check("отпущено: Y " + (up ? "вырос" : "уменьшился") + " (" + dy + ")", up ? dy > 0 : dy < 0);
        this.check("отпущено: X и Z не изменились", this.before.getX() + " " + this.before.getZ(), now.getX() + " " + now.getZ());
        this.check("отпущено: подъём закончен", "NONE", PlacementEditorScreen.TestHooks.gesture(e));
        return true;
    }

    // ===== Облёт =====

    private boolean orbitOn()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "O: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_O);
        this.check("O: облёт", "ORBIT", PlacementEditorScreen.TestHooks.view(e));
        return true;
    }

    /** Переход закончился: опора в центре габарита, камера мира — там, где поза облёта. */
    private boolean orbitSettled()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        PlacementOps.Bounds b = p == null ? null : boundsOf(p);

        if (e == null || b == null)
        {
            this.k.fail(T + "облёт: нет редактора");
            return true;
        }

        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        double off = camera.position().distanceTo(OrbitCamera.position());
        Vec3 f = OrbitCamera.forward();
        double dirOff = f.subtract(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z()).length();

        this.check(String.format(Locale.ROOT, "облёт: опора в центре схемы (%.3f)", OrbitCamera.pivot().distanceTo(b.center())),
                OrbitCamera.pivot().distanceTo(b.center()) < 0.05);
        this.check(String.format(Locale.ROOT, "облёт: камера мира на позе облёта (%.3f блока, взгляд %.4f)", off, dirOff),
                off < 0.05 && dirOff < 1.0E-3);
        this.check("облёт: камера отвязана (игрок виден)", true, camera.isDetached());
        this.check(String.format(Locale.ROOT, "облёт: расстояние в пределах (%.1f из %.1f…%.1f)", OrbitCamera.distance(),
                OrbitCamera.MIN_DISTANCE, OrbitCamera.maxDistance()),
                OrbitCamera.distance() >= OrbitCamera.MIN_DISTANCE - 1.0E-6
                        && OrbitCamera.distance() <= OrbitCamera.maxDistance() + 1.0E-6);
        this.distanceBefore = OrbitCamera.distance();
        this.enterDistance = OrbitCamera.distance();
        this.pivotBefore = OrbitCamera.pivot();

        // Центр схемы на экране — в центре свободной части окна (камера сдвинута вбок под панель)
        double[] c = CursorRay.project(b.center(), e.width, e.height);
        double cx = PlacementEditorScreen.TestHooks.freeRight(e) / 2.0;
        double cy = PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0;
        this.check(String.format(Locale.ROOT, "облёт: центр схемы в центре свободной части (%s, ждали %.1f, %.1f)",
                KPlace.point(c), cx, cy), c != null && Math.abs(c[0] - cx) < 2.0 && Math.abs(c[1] - cy) < 2.0);
        return true;
    }

    private boolean rotatePress()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "вращение: нет редактора");
            return true;
        }

        this.yawBefore = OrbitCamera.yaw();
        this.pitchBefore = OrbitCamera.pitch();
        this.distanceBefore = OrbitCamera.distance();
        this.realMove(PlacementEditorScreen.TestHooks.freeRight(e) / 2.0, PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0);
        this.realButton(InputConstants.MOUSE_BUTTON_RIGHT, 0, InputConstants.PRESS);
        return true;
    }

    /** {@code dy} = NaN — сколько нужно, чтобы тангаж стал ~35°. */
    private boolean rotateMove(double dx, double dy)
    {
        double k = 0.4 * (0.5 + Minecraft.getInstance().options.sensitivity().get());
        double y = Double.isNaN(dy) ? (35.0 - OrbitCamera.pitch()) / k : dy;
        this.realMove(this.mouseX + dx, this.mouseY + y);
        return true;
    }

    private boolean rotateRelease(String what, double dx, double dy)
    {
        this.realButton(InputConstants.MOUSE_BUTTON_RIGHT, 0, InputConstants.RELEASE);
        float yaw = OrbitCamera.yaw();
        float pitch = OrbitCamera.pitch();
        double distance = OrbitCamera.distance();
        this.k.info(String.format(Locale.ROOT, "%s%s: рыскание %.1f → %.1f, тангаж %.1f → %.1f, расстояние %.3f → %.3f", T,
                what, this.yawBefore, yaw, this.pitchBefore, pitch, this.distanceBefore, distance));

        if (dx != 0.0)
        {
            this.check(what + ": рыскание изменилось", Math.abs(yaw - this.yawBefore) > 5.0);
        }

        if (! Double.isNaN(dy) && dy != 0.0 && Math.abs(dy) < 500.0)
        {
            this.check(what + ": тангаж изменился", Math.abs(pitch - this.pitchBefore) > 2.0);
        }

        if (Double.isNaN(dy))
        {
            this.check(String.format(Locale.ROOT, "%s: тангаж ~35° (%.1f)", what, pitch), Math.abs(pitch - 35.0) < 1.5);
        }

        this.check(what + ": расстояние до опоры то же", Math.abs(distance - this.distanceBefore) < 1.0E-6);
        this.check(what + ": кнопка отпущена — вращение закончилось", ! Minecraft.getInstance().mouseHandler.isRightPressed());
        return true;
    }

    private boolean wheel(double notches)
    {
        Minecraft mc = Minecraft.getInstance();
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "колесо: нет редактора");
            return true;
        }

        this.distanceBefore = OrbitCamera.distance();
        this.realMove(PlacementEditorScreen.TestHooks.freeRight(e) / 2.0, PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0);

        // По щелчку: ваниль копит дробные доли и отдаёт экрану целые щелчки
        for (int i = 0; i < Math.abs((int) notches); ++i)
        {
            mc.mouseHandler.onScroll(mc.getWindow().handle(), 0.0, Math.signum(notches));
        }

        return true;
    }

    private boolean wheelCloser()
    {
        double want = Math.max(OrbitCamera.MIN_DISTANCE, this.distanceBefore * Math.pow(0.85, 3));
        this.check(String.format(Locale.ROOT, "колесо +3: ближе (%.2f → %.2f, ждали %.2f)", this.distanceBefore,
                OrbitCamera.distance(), want), Math.abs(OrbitCamera.distance() - want) < 0.05);
        return true;
    }

    private boolean wheelLimit(boolean far)
    {
        double limit = far ? OrbitCamera.maxDistance() : OrbitCamera.MIN_DISTANCE;
        this.check(String.format(Locale.ROOT, "колесо: %s предел (%.2f, предел %.2f)", far ? "дальний" : "ближний",
                OrbitCamera.distance(), limit), Math.abs(OrbitCamera.distance() - limit) < 0.05);
        return true;
    }

    private boolean framed()
    {
        SchematicPlacement p = this.placement;
        PlacementOps.Bounds b = p == null ? null : boundsOf(p);

        if (b == null)
        {
            this.k.fail(T + "F: нет размещения");
            return true;
        }

        this.check(String.format(Locale.ROOT, "F: опора в центре схемы (%.3f)", OrbitCamera.pivot().distanceTo(b.center())),
                OrbitCamera.pivot().distanceTo(b.center()) < 0.05);
        this.check(String.format(Locale.ROOT, "F: расстояние «схема целиком» (%.2f, при входе %.2f)", OrbitCamera.distance(),
                this.enterDistance), Math.abs(OrbitCamera.distance() - this.enterDistance) < 0.05);
        return true;
    }

    // ===== Столкновения облёта (1.0.7) =====

    /** Коробка проверки вокруг глаза — чуть меньше тела камеры, чтобы касание стены не считалось входом. */
    private static AABB eyeBox(Vec3 eye)
    {
        return new AABB(eye, eye).inflate(0.24);
    }

    private static boolean free(Vec3 eye)
    {
        ClientLevel level = Minecraft.getInstance().level;

        return level != null && level.noBlockCollision(null, eyeBox(eye));
    }

    /** Камера мира стоит там же, где настоящий глаз облёта (миксин ставит его, а не расчётную позу). */
    private void cameraOnEye(String what)
    {
        double off = Minecraft.getInstance().gameRenderer.mainCamera().position().distanceTo(OrbitCamera.position());
        this.check(String.format(Locale.ROOT, "%s: камера мира на глазу облёта (%.3f)", what, off), off < 0.05);
    }

    /** Тангаж −80°: расчётная поза под площадкой, глаз — над полом и не в блоке. */
    private boolean belowFloor()
    {
        Vec3 eye = OrbitCamera.position();
        Vec3 want = OrbitCamera.wanted();
        this.k.info(String.format(Locale.ROOT, "%sснизу: глаз y=%.2f, расчётный y=%.2f, пол y=%d", T, eye.y, want.y,
                DumpSchematics.FLOOR_Y));
        this.check("снизу: глаз не в блоке", free(eye));

        if (want.y < DumpSchematics.FLOOR_Y - 1)
        {
            this.check(String.format(Locale.ROOT, "снизу: глаз над полом (%.2f ≥ %d)", eye.y, DumpSchematics.FLOOR_Y),
                    eye.y >= DumpSchematics.FLOOR_Y);
        }

        this.cameraOnEye("снизу");
        return true;
    }

    private boolean wallBuild()
    {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null || ! OrbitCamera.active())
        {
            this.k.fail(T + "стена: нет сервера или облёта");
            return true;
        }

        Vec3 eye = OrbitCamera.position();
        Vec3 dir = eye.subtract(OrbitCamera.pivot()).normalize();
        Vec3 feet = mc.player.position();
        this.wallDir = dir;
        this.wallAt = eye.add(dir.scale(WALL_GAP));
        this.wall.clear();

        // Диск камня поперёк пути камеры при отъезде (глаз едет от опоры вдоль dir); игрока не замуровать
        BlockPos c = BlockPos.containing(this.wallAt);
        int r = (int) Math.ceil(WALL_RADIUS);

        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r)))
        {
            Vec3 d = Vec3.atCenterOf(p).subtract(this.wallAt);

            if (Math.abs(d.dot(dir)) <= 0.9 && d.length() <= WALL_RADIUS && Vec3.atCenterOf(p).distanceTo(feet) > 3.0)
            {
                this.wall.add(p.immutable());
            }
        }

        List<BlockPos> blocks = List.copyOf(this.wall);
        server.execute(() -> blocks.forEach(p -> server.overworld().setBlockAndUpdate(p, Blocks.STONE.defaultBlockState())));
        this.k.info(String.format(Locale.ROOT, "%sстена: %d блоков в %.1f за глазом (%.1f, %.1f, %.1f)", T, blocks.size(),
                WALL_GAP, this.wallAt.x, this.wallAt.y, this.wallAt.z));
        return true;
    }

    private boolean wallClear()
    {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();

        if (server != null)
        {
            List<BlockPos> blocks = List.copyOf(this.wall);
            server.execute(() -> blocks.forEach(p -> server.overworld().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState())));
        }

        return true;
    }

    /** Ждать, пока клиент увидит всю стену ({@code stone}) или её отсутствие. */
    private boolean wallArrived(boolean stone)
    {
        ClientLevel level = Minecraft.getInstance().level;

        return level != null && ! this.wall.isEmpty()
                && this.wall.stream().allMatch(p -> level.getBlockState(p).is(Blocks.STONE) == stone);
    }

    private boolean wallHeld()
    {
        Vec3 p = OrbitCamera.pivot();
        Vec3 eye = OrbitCamera.position();
        double wallD = this.wallAt.subtract(p).dot(this.wallDir);
        double eyeD = eye.subtract(p).dot(this.wallDir);
        double wantD = OrbitCamera.wanted().subtract(p).dot(this.wallDir);

        this.check(String.format(Locale.ROOT, "стена: расчётная поза ушла за стену (%.2f > %.2f)", wantD, wallD),
                wantD > wallD + 1.0);
        this.check(String.format(Locale.ROOT, "стена: глаз остался перед стеной (%.2f < %.2f)", eyeD, wallD),
                eyeD < wallD - 0.5);
        this.check(String.format(Locale.ROOT, "стена: глаз у самой стены, а не встал раньше (%.2f > %.2f)", eyeD,
                wallD - 2.0), eyeD > wallD - 2.0);
        this.check("стена: глаз не в блоке", free(eye));
        this.cameraOnEye("стена");
        return true;
    }

    private boolean wallGone()
    {
        double off = OrbitCamera.position().distanceTo(OrbitCamera.wanted());
        this.check(String.format(Locale.ROOT, "стена убрана: глаз догнал расчётную позу (%.3f)", off), off < 0.05);
        this.cameraOnEye("стена убрана");
        return true;
    }

    private boolean orbitDragPress()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        PlacementOps.Bounds b = p == null ? null : boundsOf(p);

        if (e == null || p == null || b == null)
        {
            this.k.fail(T + "облёт, перетаскивание: нет редактора");
            return true;
        }

        double planeY = b.minY();
        Vec3 from = new Vec3((b.minX() + b.maxX()) / 2.0 + 0.5, planeY, (b.minZ() + b.maxZ()) / 2.0 + 0.5);
        double[] start = KPlace.solve(e, from, planeY);
        double[] end = KPlace.solve(e, from.add(4.0, 0.0, 3.0), planeY);

        if (start == null || end == null || ! KPlace.free(e, start) || ! KPlace.free(e, end))
        {
            this.k.fail(T + "облёт, перетаскивание: точки не на свободной части экрана " + KPlace.point(start) + " → "
                    + KPlace.point(end));
            this.cornerAt = new double[] {-1, -1, 0};
            return true;
        }

        // Точка хвата не должна попасть на ручку угла — иначе это подъём
        this.before = p.getOrigin();
        this.cornerAt = end;
        this.realMove(start[0], start[1]);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.PRESS);
        this.check("облёт: ЛКМ по схеме — перетаскивание", "MOVE", PlacementEditorScreen.TestHooks.gesture(e));
        return true;
    }

    private boolean orbitDragMove()
    {
        if (this.cornerAt[0] >= 0.0)
        {
            this.realMove(this.cornerAt[0], this.cornerAt[1]);
        }

        return true;
    }

    private boolean orbitDragFrame()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "облёт, перетаскивание: редактор закрылся");
            return true;
        }

        this.check("облёт: рамка за курсором", this.before.offset(4, 0, 3).toShortString(),
                PlacementEditorScreen.TestHooks.frameOrigin(e).toShortString());
        return true;
    }

    private boolean orbitDragRelease()
    {
        SchematicPlacement p = this.placement;

        if (p == null)
        {
            this.k.fail(T + "облёт, перетаскивание: нет размещения");
            return true;
        }

        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.RELEASE);
        this.check("облёт: схема переехала за курсором", this.before.offset(4, 0, 3).toShortString(),
                p.getOrigin().toShortString());
        return true;
    }

    private boolean panPress(int button, int mods)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "панорама: нет редактора");
            return true;
        }

        this.yawBefore = OrbitCamera.yaw();
        this.pitchBefore = OrbitCamera.pitch();
        this.distanceBefore = OrbitCamera.distance();
        this.pivotBefore = OrbitCamera.pivot();
        this.realMove(PlacementEditorScreen.TestHooks.freeRight(e) / 2.0, PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0);
        this.realButton(button, mods, InputConstants.PRESS);
        return true;
    }

    private boolean panMove()
    {
        this.realMove(this.mouseX + 60.0, this.mouseY + 20.0);
        return true;
    }

    private boolean panRelease(String how, int button)
    {
        this.realButton(button, 0, InputConstants.RELEASE);
        Vec3 pivot = OrbitCamera.pivot();
        double moved = pivot.distanceTo(this.pivotBefore);
        this.k.info(String.format(Locale.ROOT, "%sпанорама %s: опора сдвинулась на %.2f блока", T, how, moved));
        this.check("панорама " + how + ": опора сдвинулась", moved > 0.5);
        this.check("панорама " + how + ": по горизонтали (Y опоры тот же)", Math.abs(pivot.y - this.pivotBefore.y) < 1.0E-6);
        this.check("панорама " + how + ": углы и расстояние те же", OrbitCamera.yaw() == this.yawBefore
                && OrbitCamera.pitch() == this.pitchBefore && Math.abs(OrbitCamera.distance() - this.distanceBefore) < 1.0E-6);
        return true;
    }

    // ===== Вид =====

    private boolean clickView(boolean back, String want)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "переключатель: нет редактора");
            return true;
        }

        int[] b = PlacementEditorScreen.TestHooks.viewSwitch(e);
        this.realClick(back ? b[0] + 5.0 : b[0] + b[2] * 0.75, b[1] + b[3] / 2.0);
        this.check("переключатель вида" + (back ? " (‹)" : ""), want, PlacementEditorScreen.TestHooks.view(e));
        this.checkViewFlags(want);
        return true;
    }

    private boolean keyView(int key, String want)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "клавиша вида: нет редактора");
            return true;
        }

        KProbe.press(e, key);
        this.check("клавиша вида", want, PlacementEditorScreen.TestHooks.view(e));
        this.checkViewFlags(want);

        if (want.equals("ORBIT"))
        {
            this.enterDistance = OrbitCamera.targetDistance();
        }

        return true;
    }

    private void checkViewFlags(String want)
    {
        this.check("облёт включён: " + want.equals("ORBIT"), want.equals("ORBIT"), OrbitCamera.active());
        this.check("вид сверху включён: " + want.equals("TOP"), want.equals("TOP"), TopViewCamera.active());
    }

    private boolean clickFooter(boolean done)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail(T + "подвал: нет редактора");
            return true;
        }

        this.realClick(PlacementEditorScreen.TestHooks.footerButton(e, done));
        this.check((done ? "«Готово»" : "«Отмена»") + " закрыл редактор", true, editor() == null);
        return true;
    }

    // ===== Большая схема =====

    private boolean loadBig()
    {
        if (! this.bigSearched)
        {
            this.bigSearched = true;
            Path file = biggest();

            if (file == null)
            {
                this.k.info(T + "большой схемы игрока не нашлось — шаги большой схемы без неё");
                return true;
            }

            try
            {
                Path folder = this.k.outDir().resolve("edit-src");
                Files.createDirectories(folder);
                Path copy = folder.resolve(file.getFileName().toString());
                Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
                SchematicEntry entry = SchematicLibrary.get().entry(copy);

                if (entry == null)
                {
                    this.k.fail(T + "большая: нет записи библиотеки для " + copy);
                    return true;
                }

                this.k.info(T + "большая схема: " + file + " (" + KData.size(copy) + " Б)");
                this.loading = SchematicLibrary.get().load(entry);
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail(T + "большая: копия — " + e);
                return true;
            }
        }

        if (this.loading == null)
        {
            return true;
        }

        if (! this.loading.isDone())
        {
            return false;
        }

        try
        {
            this.big = this.loading.join();
            this.k.ok(T + "большая загружена: блоков " + this.big.litematica().getMetadata().getTotalBlocks());
        }
        catch (RuntimeException e)
        {
            this.k.fail(T + "большая: загрузка — " + e);
        }
        finally
        {
            this.loading = null;
        }

        return true;
    }

    private boolean openBig()
    {
        if (this.big == null)
        {
            return true;
        }

        SchematicPlacement p = PlacementOps.placeInFront(this.big);
        this.placement = p;
        this.made.add(p);
        return this.open(false);
    }

    private boolean bigIdle()
    {
        if (this.big == null)
        {
            return true;
        }

        // Пауза покоя — за этот шаг (4 с после открытия)
        this.idleGap = this.k.stepMaxGap();
        return true;
    }

    private boolean bigHoldStart()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (this.big == null)
        {
            return true;
        }

        if (e == null || p == null)
        {
            this.k.fail(T + "большая: нет редактора");
            return true;
        }

        this.k.resetStepMaxGap();
        this.check("большая: тумблер голограмм выключен (схема > 200 000 блоков)", true,
                PlacementEditorScreen.TestHooks.liveBlocked(e));
        int[] b = PlacementEditorScreen.TestHooks.stepButton(e, 1, 1);
        this.before = p.getOrigin();
        this.beforeState = EditHistory.State.of(p);
        this.lastSeenOrigin = this.before;
        this.originChanges = 0;
        this.realMove(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.PRESS);
        this.pressedAt = Util.getMillis();
        return true;
    }

    /** Раз в тик: сколько раз origin Litematica поменялся, пока «+» держат (должно быть ни разу). */
    private boolean bigHolding()
    {
        SchematicPlacement p = this.placement;

        if (this.big == null)
        {
            return true;
        }

        if (p == null)
        {
            this.k.fail(T + "большая: нет размещения");
            return true;
        }

        if (! p.getOrigin().equals(this.lastSeenOrigin))
        {
            ++this.originChanges;
            this.lastSeenOrigin = p.getOrigin();
        }

        return Util.getMillis() - this.pressedAt >= HOLD_MS;
    }

    private boolean bigRelease()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (this.big == null)
        {
            return true;
        }

        if (e == null || p == null)
        {
            this.k.fail(T + "большая: редактор закрылся");
            return true;
        }

        long holdGap = this.k.stepMaxGap();
        BlockPos frame = PlacementEditorScreen.TestHooks.frameOrigin(e);
        this.check("большая: пока держали «+», размещение Litematica не менялось", 0, this.originChanges);
        this.check("большая: рамка уехала вверх (" + (frame.getY() - this.before.getY()) + ")", frame.getY() - this.before.getY() > 2);
        // Программный рендер Xvfb рисует 41 млн голограмм секундами на кадр и неровно (Litematica ещё
        // достраивает чанки схемы) — паузы только строкой; проверка — что за всё зажатие размещение
        // Litematica не перекладывалось ни разу (выше) и применилось один раз при отпускании (ниже)
        this.k.info(T + "большая: пауза клиента в покое до " + this.idleGap + " мс, пока держали «+» до " + holdGap
                + " мс (Xvfb, программный рендер голограмм)");
        this.k.resetStepMaxGap();
        this.realButton(InputConstants.MOUSE_BUTTON_LEFT, 0, InputConstants.RELEASE);
        this.check("большая: отпустили — одно применение, размещение там, где рамка", frame.toShortString(),
                p.getOrigin().toShortString());
        return true;
    }

    private boolean bigAfter()
    {
        if (this.big != null)
        {
            this.k.info(T + "большая: после отпускания (Litematica переносит голограммы) пауза до " + this.k.stepMaxGap() + " мс");
        }

        return true;
    }

    private boolean bigReverted()
    {
        SchematicPlacement p = this.placement;

        if (this.big == null)
        {
            return true;
        }

        if (editor() != null)
        {
            return false;
        }

        this.check("большая: Esc вернул как при открытии", this.beforeState, p == null ? null : EditHistory.State.of(p));
        return true;
    }

    private boolean removeBig()
    {
        if (this.big == null)
        {
            return true;
        }

        this.removeCurrent();
        this.unloadBig();
        return true;
    }

    private void unloadBig()
    {
        LoadedSchematic loaded = this.big;

        if (loaded != null)
        {
            LitematicaSchematic schematic = loaded.litematica();
            SchematicHolder.getInstance().removeSchematic(schematic);
            this.big = null;
        }
    }

    /** Самый большой файл схемы в папках игрока (как у постера); {@code LL_K_POSTER_BIG} — свой путь. */
    private @Nullable Path biggest()
    {
        String override = System.getenv("LL_K_POSTER_BIG");

        if (override != null && ! override.isBlank())
        {
            Path path = Path.of(override.trim());

            return Files.isRegularFile(path) ? path : null;
        }

        Path best = null;
        long bestSize = -1L;

        for (Path folder : KData.profileFolders())
        {
            try (Stream<Path> files = Files.list(folder))
            {
                for (Path file : files.filter(p -> SchematicFormat.of(p) != null && Files.isRegularFile(p))
                        .sorted(Comparator.comparing(Path::toString)).toList())
                {
                    long size = Files.size(file);

                    if (size > bestSize || size == bestSize && file.toString().contains("26.3"))
                    {
                        best = file;
                        bestSize = size;
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                this.k.info(T + "не прочитать " + folder + ": " + e);
            }
        }

        return best;
    }

    private static PlacementOps.@Nullable Bounds boundsOf(SchematicPlacement p)
    {
        KPlace.Bounds b = KPlace.bounds(p);

        return b == null ? null : new PlacementOps.Bounds(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }
}
