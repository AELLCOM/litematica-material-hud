package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.schematics.place.CursorRay;
import com.aell.layerlist.schematics.place.EditHistory;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.schematics.place.TopViewCamera;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Button;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Редактор размещения в режиме «k» — тремя сеансами, все правки обычным вводом экрана:
 * <ol>
 *   <li>«Разместить здесь» в карточке → редактор сразу в виде сверху, размещение новое: мышью на
 *       (+5, 0, +3), R, M, PgUp и G, «по сетке чанков», Ctrl+Z/Ctrl+Y, Esc — размещение убрано;</li>
 *   <li>размещение через API, редактор в обычном виде: перетаскивание, R, M, G, T туда и обратно,
 *       Esc — origin, поворот и отражение как при открытии;</li>
 *   <li>снова редактор: стрелка, Enter — сдвиг остался.</li>
 * </ol>
 * Габарит и origin каждый раз читаются у самой Litematica ({@code getSubRegionBoxes},
 * {@code getOrigin}); точки экрана для мыши подбираются по {@link CursorRay} — тем же лучом, что
 * у редактора.
 */
@Environment(EnvType.CLIENT)
final class KPlace
{
    private static final int CHUNK = 16;
    private static final int BIG_X = 64;
    private static final int BIG_Y = 52;
    private static final int BIG_Z = 64;

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private @Nullable SchematicPlacement placement;
    private EditHistory.@Nullable State initial;
    private EditHistory.@Nullable State beforeSnap;
    private EditHistory.@Nullable State snapped;
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private boolean hudBefore;
    private boolean hudTouched;
    private boolean liveBefore;
    private boolean liveTouched;
    private @Nullable LitematicaSchematic big;
    private @Nullable BlockPos grabbedFrom;
    private @Nullable BlockPos grabbedTo;
    private double @Nullable [] grabEnd;

    KPlace(DumpSchematics k)
    {
        this.k = k;
    }

    /** Габарит размещения по всем подобластям (как его считает редактор). */
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        int sizeX()
        {
            return this.maxX - this.minX + 1;
        }

        int sizeZ()
        {
            return this.maxZ - this.minZ + 1;
        }

        /** Удвоенный центр: у чётной стороны он между блоками. */
        int c2x()
        {
            return this.minX + this.maxX;
        }

        int c2z()
        {
            return this.minZ + this.maxZ;
        }

        @Override
        public String toString()
        {
            return "[" + this.minX + " " + this.minY + " " + this.minZ + " … " + this.maxX + " " + this.maxY + " " + this.maxZ + "]";
        }
    }

    // ===== Сценарий =====

    List<DumpSchematics.Step> steps()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();

        // Сеанс 1 — как игрок: кнопка карточки, новое размещение, вид сверху
        s.add(step("к k-generated.litematic", 5_000L, mc -> this.k.selectGenerated()));
        s.add(step("«Разместить здесь» кнопкой карточки", 3_000L, this::placeHere));
        s.add(step("редактор: новое, сразу сверху", 20_000L, mc -> this.waitEditor(mc, true, true)));
        s.add(step("снимок: редактор сверху", 6_000L, this.k.settleThen(1500L, this.shot("editor-top"))));
        s.add(step("сверху: перетаскивание на (+5, 0, +3)", 3_000L, mc -> this.drag(5, 3)));
        s.add(step("сверху: R", 3_000L, mc -> this.rotate()));
        s.add(step("сверху: M", 3_000L, mc -> this.mirror()));
        s.add(step("сверху: PgUp ×4", 3_000L, mc -> this.up(4)));
        s.add(step("сверху: G", 3_000L, mc -> this.ground()));
        s.add(step("сверху: C — по сетке чанков", 3_000L, mc -> this.snapOn()));
        s.add(step("сверху: Ctrl+Z", 3_000L, mc -> this.undo()));
        s.add(step("сверху: Ctrl+Y", 3_000L, mc -> this.redo()));
        s.add(step("сверху: C — сетка выключена", 3_000L, mc -> this.snapOff()));
        s.add(step("снимок: после правок сверху", 6_000L, this.k.settleThen(1200L, this.shot("editor-top-edited"))));
        s.add(step("Esc у нового размещения", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc: новое размещение убрано", 5_000L, mc -> this.cancelledNew()));

        // Сеанс 2 — обычный вид, размещение уже было: Esc возвращает исходное
        s.add(step("размещение перед игроком (API)", 30_000L, mc -> this.placeDirect()));
        s.add(step("редактор в обычном виде", 3_000L, mc -> this.open(false)));
        s.add(step("редактор: прежнее, обычный вид", 10_000L, mc -> this.waitEditor(mc, false, false)));
        s.add(step("снимок: редактор, обычный вид", 6_000L, this.k.settleThen(1500L, this.shot("editor-normal"))));
        s.add(step("обычный вид: перетаскивание на (+5, 0, +3)", 3_000L, mc -> this.drag(5, 3)));
        s.add(step("обычный вид: R", 3_000L, mc -> this.rotate()));
        s.add(step("обычный вид: M", 3_000L, mc -> this.mirror()));
        s.add(step("обычный вид: PgUp ×2", 3_000L, mc -> this.up(2)));
        s.add(step("обычный вид: G", 3_000L, mc -> this.ground()));
        s.add(step("T — вид сверху", 3_000L, mc -> this.toggleTop(true)));
        s.add(step("снимок: T из обычного вида", 6_000L, this.k.settleThen(1500L, this.shot("editor-normal-to-top"))));
        s.add(step("T — обратно", 3_000L, mc -> this.toggleTop(false)));
        s.add(step("Esc у прежнего размещения", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc: всё как при открытии", 5_000L, mc -> this.reverted()));

        // Сеанс 3 — Enter оставляет сделанное
        s.add(step("редактор снова", 3_000L, mc -> this.open(false)));
        s.add(step("редактор: снова открыт", 10_000L, mc -> this.waitEditor(mc, false, false)));
        s.add(step("→ — шаг вправо", 3_000L, mc -> this.arrowRight(mc)));
        s.add(step("Enter — оставить", 5_000L, mc -> this.enterKeeps()));
        s.add(step("HUD по размещению", 20_000L, mc -> this.hudRows()));
        // Снимки с живым размещением: строка в карточке и таблица «Размещений»
        s.add(step("снимок: карточка с размещением", 6_000L, this.k.settleThen(1200L, this.shot("card-placement"))));
        s.add(step("«Размещения» с размещением", 2_000L, mc -> this.k.setTab("PLACEMENTS")));
        s.add(step("снимок: «Размещения»", 6_000L, this.k.settleThen(1200L, this.shot("placements"))));
        s.add(step("назад в «Библиотеку»", 2_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(step("убрать размещение", 3_000L, mc -> this.removeCurrent()));
        return s;
    }

    /** Вид сверху при масштабе GUI 3 — только снимок. */
    List<DumpSchematics.Step> topViewAtScale3()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("размещение для GUI 3", 30_000L, mc -> this.placeDirect()));
        s.add(step("редактор сверху GUI 3", 3_000L, mc -> this.open(true)));
        s.add(step("редактор GUI 3 открыт", 10_000L, mc -> this.waitEditor(mc, false, true)));
        s.add(step("снимок: редактор сверху GUI 3", 6_000L, this.k.settleThen(1500L, this.shot("s3-editor-top"))));
        s.add(step("Esc (GUI 3)", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc (GUI 3): назад в «Схемы»", 5_000L, mc -> this.reverted()));
        s.add(step("убрать размещение GUI 3", 3_000L, mc -> this.removeCurrent()));
        return s;
    }

    /**
     * «Голограммы за мышью» (0.0.27): у маленькой схемы тумблер правда меняет поведение —
     * включён: голограммы (origin Litematica) едут за рамкой по дороге и догоняют её, когда мышь
     * замерла; выключен: стоят до отпускания. У схемы больше 200 000 блоков тумблер выключен,
     * под ним причина, клик его не переключает, голограммы ждут отпускания. Снимки обеих панелей.
     */
    List<DumpSchematics.Step> liveSteps()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("голограммы: размещение k-generated (API)", 30_000L, mc -> this.placeDirect()));
        // Сверху: экран и мир связаны линейно, и сдвиг на пару блоков не уводит мышь за край
        s.add(step("голограммы: редактор сверху", 3_000L, mc -> this.open(true)));
        s.add(step("голограммы: редактор открыт", 10_000L, mc -> this.waitEditor(mc, false, true)));
        s.add(step("голограммы: тумблер включён, пометка «как работает»", 3_000L, mc -> this.liveSmall()));
        s.add(step("снимок: тумблер голограмм, маленькая схема", 6_000L, this.k.settleThen(1200L, this.shot("editor-live-small"))));
        s.add(step("голограммы вкл.: схватить и сдвинуть на (+3, +2)", 3_000L, mc -> this.grab(3, 2)));
        s.add(step("голограммы вкл.: мышь замерла — голограммы догнали рамку", 3_000L,
                this.k.settleThen(450L, mc -> this.ghostsFollowed(true))));
        s.add(step("голограммы вкл.: отпустить", 3_000L, mc -> this.release()));
        s.add(step("тумблер кликом — выкл.", 3_000L, mc -> this.clickLive(false)));
        s.add(step("голограммы выкл.: схватить и сдвинуть на (−3, −2)", 3_000L, mc -> this.grab(-3, -2)));
        s.add(step("голограммы выкл.: мышь замерла — голограммы на месте", 3_000L,
                this.k.settleThen(450L, mc -> this.ghostsFollowed(false))));
        s.add(step("голограммы выкл.: отпустить — переехали", 3_000L, mc -> this.release()));
        s.add(step("тумблер кликом — снова вкл.", 3_000L, mc -> this.clickLive(true)));
        s.add(step("Esc (голограммы)", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc (голограммы): всё как при открытии", 5_000L, mc -> this.reverted()));
        s.add(step("убрать размещение (голограммы)", 3_000L, mc -> this.removeCurrent()));

        s.add(step("большая схема в памяти: 64×52×64 камня", 20_000L, mc -> this.placeBig()));
        s.add(step("большая: редактор сверху", 3_000L, mc -> this.open(true)));
        s.add(step("большая: редактор открыт", 20_000L, mc -> this.waitEditor(mc, false, true)));
        s.add(step("большая: тумблер выключен, причина под ним", 3_000L, mc -> this.liveBig()));
        s.add(step("снимок: тумблер голограмм, схема > 200 000 блоков", 8_000L,
                this.k.settleThen(2000L, this.shot("editor-live-big"))));
        s.add(step("большая: клик по тумблеру ничего не меняет", 3_000L, mc -> this.clickLiveBlocked()));
        s.add(step("большая: схватить и сдвинуть на 4", 3_000L, mc -> this.grab(4, 0)));
        s.add(step("большая: мышь замерла — голограммы на месте", 3_000L,
                this.k.settleThen(450L, mc -> this.ghostsFollowed(false))));
        s.add(step("большая: отпустить — переехали", 3_000L, mc -> this.release()));
        s.add(step("Esc (большая)", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("Esc (большая): назад в «Схемы»", 5_000L, mc -> this.reverted()));
        s.add(step("убрать большую схему", 3_000L, mc -> this.removeBig()));
        return s;
    }

    /** Те же две панели при масштабе GUI 3 — только снимки (поведение проверено при GUI 2). */
    List<DumpSchematics.Step> liveShotsAtScale3()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step("GUI 3: размещение k-generated", 30_000L, mc -> this.placeDirect()));
        s.add(step("GUI 3: редактор", 3_000L, mc -> this.open(false)));
        s.add(step("GUI 3: редактор открыт", 10_000L, mc -> this.waitEditor(mc, false, false)));
        s.add(step("снимок: тумблер голограмм GUI 3, маленькая", 6_000L,
                this.k.settleThen(1200L, this.shot("s3-editor-live-small"))));
        s.add(step("GUI 3: Esc", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("GUI 3: Esc — назад", 5_000L, mc -> this.reverted()));
        s.add(step("GUI 3: убрать размещение", 3_000L, mc -> this.removeCurrent()));
        s.add(step("GUI 3: большая схема", 20_000L, mc -> this.placeBig()));
        s.add(step("GUI 3: редактор большой", 3_000L, mc -> this.open(true)));
        s.add(step("GUI 3: редактор большой открыт", 20_000L, mc -> this.waitEditor(mc, false, true)));
        s.add(step("GUI 3: пометка о большой схеме видна", 3_000L, mc -> this.liveBig()));
        s.add(step("снимок: тумблер голограмм GUI 3, большая", 8_000L,
                this.k.settleThen(2000L, this.shot("s3-editor-live-big"))));
        s.add(step("GUI 3: Esc (большая)", 3_000L, mc -> this.press(InputConstants.KEY_ESCAPE)));
        s.add(step("GUI 3: Esc (большая) — назад", 5_000L, mc -> this.reverted()));
        s.add(step("GUI 3: убрать большую", 3_000L, mc -> this.removeBig()));
        return s;
    }

    /** Уборка в конце прогона: свои размещения и тумблер HUD. */
    void cleanup()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();

        if (this.big != null)
        {
            SchematicHolder.getInstance().removeSchematic(this.big);
            this.big = null;
        }

        if (this.hudTouched)
        {
            LayerListConfigs.HUD_ENABLED = this.hudBefore;
        }

        if (this.liveTouched)
        {
            SchematicsConfig.LIVE_GHOSTS.setBooleanValue(this.liveBefore);
        }
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    private DumpSchematics.Action shot(String name)
    {
        return mc ->
        {
            this.k.shot(mc, name);
            return true;
        };
    }

    // ===== Открытие =====

    private static @Nullable PlacementEditorScreen editor()
    {
        return PlacementEditorScreen.TestHooks.current(GuiUtils.getCurrentScreen());
    }

    private boolean placeHere(Minecraft mc)
    {
        GuiSchematics gui = this.k.gui();
        Button button = KProbe.button(this.k.infoPane(), "layerlist.schematics.info.place_here");

        if (gui == null || button == null)
        {
            this.k.fail("кнопки «Разместить здесь» нет");
            return true;
        }

        this.k.clickButton(gui, button, this.k.infoPane());
        return true;
    }

    private boolean waitEditor(Minecraft mc, boolean createdNow, boolean top)
    {
        PlacementEditorScreen e = editor();

        if (e == null || (top && ! TopViewCamera.active()))
        {
            return false;
        }

        SchematicPlacement p = PlacementEditorScreen.TestHooks.placement(e);
        this.placement = p;

        if (! this.made.contains(p))
        {
            this.made.add(p);
        }

        this.k.check("редактор: «только что создано»", createdNow, PlacementEditorScreen.TestHooks.createdNow(e));
        this.k.check("редактор: вид сверху", top, TopViewCamera.active());
        this.log("при открытии", p);

        if (createdNow)
        {
            SchematicEntry generated = this.k.generated(".litematic");
            this.k.check("размещение из файла k-generated.litematic", true,
                    generated != null && p.getSchematicFile() != null
                            && p.getSchematicFile().toAbsolutePath().normalize().equals(generated.path().toAbsolutePath().normalize()));
            this.checkInFront(mc, p);
        }

        return true;
    }

    /** «Разместить здесь»: ближняя грань — в 2 блоках перед игроком, поперёк — по центру, низ — у ног. */
    private void checkInFront(Minecraft mc, SchematicPlacement p)
    {
        LocalPlayer player = mc.player;
        Bounds b = bounds(p);

        if (player == null || b == null)
        {
            this.k.fail("перед игроком: нет игрока или габарита");
            return;
        }

        BlockPos feet = player.blockPosition();
        int sx = b.sizeX();
        int sz = b.sizeZ();
        BlockPos expected = switch (player.getDirection())
        {
            case NORTH -> new BlockPos(feet.getX() - sx / 2, feet.getY(), feet.getZ() - 2 - (sz - 1));
            case SOUTH -> new BlockPos(feet.getX() - sx / 2, feet.getY(), feet.getZ() + 2);
            case WEST -> new BlockPos(feet.getX() - 2 - (sx - 1), feet.getY(), feet.getZ() - sz / 2);
            default -> new BlockPos(feet.getX() + 2, feet.getY(), feet.getZ() - sz / 2);
        };

        this.k.check("«Разместить здесь»: мин. угол перед игроком (" + player.getDirection() + ")", expected,
                new BlockPos(b.minX(), b.minY(), b.minZ()));
    }

    private boolean placeDirect()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        if (generated == null)
        {
            this.k.fail("нет k-generated.litematic");
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
                this.k.fail("загрузка k-generated: " + e);
                return true;
            }
        }

        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.placement = p;
        this.made.add(p);
        this.initial = EditHistory.State.of(p);
        this.k.ok("размещение через PlacementOps.placeInFront: origin " + p.getOrigin().toShortString());
        return true;
    }

    private boolean open(boolean top)
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null || this.placement == null)
        {
            this.k.fail("редактор: нет экрана «Схемы» или размещения");
            return true;
        }

        PlacementEditorScreen.open(gui, this.placement, false, top);
        return true;
    }

    // ===== Правки =====

    private boolean drag(int dx, int dz)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        Bounds b = p == null ? null : bounds(p);

        if (e == null || p == null || b == null)
        {
            this.k.fail("перетаскивание: нет редактора или размещения");
            return true;
        }

        BlockPos before = p.getOrigin();
        double planeY = b.minY();
        Vec3 from = new Vec3(b.c2x() / 2.0 + 0.5, planeY, b.c2z() / 2.0 + 0.5);
        Vec3 to = from.add(dx, 0.0, dz);
        double[] start = solve(e, from, planeY);
        double[] end = solve(e, to, planeY);

        if (start == null || end == null || ! free(e, start) || ! free(e, end))
        {
            LocalPlayer player = Minecraft.getInstance().player;
            this.k.fail("перетаскивание: точки " + from + " → " + to + " не на свободной части экрана: мышь "
                    + point(start) + " → " + point(end) + ", свободно " + PlacementEditorScreen.TestHooks.freeRight(e) + "×"
                    + PlacementEditorScreen.TestHooks.freeBottom(e) + (player == null ? "" : String.format(java.util.Locale.ROOT,
                    ", игрок (%.2f, %.2f, %.2f) рыскание %.1f тангаж %.1f", player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot())));
            return true;
        }

        CovOut.log(DumpSchematics.TAG + String.format(java.util.Locale.ROOT, "мышь (%.1f, %.1f) → (%.1f, %.1f), экран %d×%d",
                start[0], start[1], end[0], end[1], e.width, e.height));
        e.onMouseClicked(KProbe.left(start[0], start[1]), false);
        this.k.check("ЛКМ по миру схватила схему", true, PlacementEditorScreen.TestHooks.dragging(e));
        e.onMouseDragged(KProbe.left(end[0], end[1]), end[0] - start[0], end[1] - start[1]);
        e.onMouseReleased(KProbe.left(end[0], end[1]));
        this.k.check("перетаскивание: origin", before.offset(dx, 0, dz).toShortString(), p.getOrigin().toShortString());
        this.log("после перетаскивания", p);
        return true;
    }

    private boolean rotate()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        Bounds b0 = p == null ? null : bounds(p);

        if (e == null || p == null || b0 == null)
        {
            this.k.fail("R: нет редактора");
            return true;
        }

        Rotation want = p.getRotation().getRotated(Rotation.CLOCKWISE_90);
        KProbe.press(e, InputConstants.KEY_R);
        this.k.check("R: поворот по часовой", want, p.getRotation());
        this.checkCentre("R", b0, bounds(p));
        this.log("после R", p);
        return true;
    }

    private boolean mirror()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        Bounds b0 = p == null ? null : bounds(p);

        if (e == null || p == null || b0 == null)
        {
            this.k.fail("M: нет редактора");
            return true;
        }

        Mirror want = switch (p.getMirror())
        {
            case NONE -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.LEFT_RIGHT;
            default -> Mirror.NONE;
        };

        KProbe.press(e, InputConstants.KEY_M);
        this.k.check("M: отражение по кругу", want, p.getMirror());
        this.checkCentre("M", b0, bounds(p));
        this.log("после M", p);
        return true;
    }

    /** Центр габарита после поворота/отражения — на месте с точностью до блока. */
    private void checkCentre(String what, Bounds before, @Nullable Bounds after)
    {
        boolean kept = after != null && Math.abs(after.c2x() - before.c2x()) <= 2 && Math.abs(after.c2z() - before.c2z()) <= 2;
        this.k.check(what + ": центр габарита на месте (±1 блок): " + before + " → " + after, kept);
    }

    private boolean up(int times)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        Bounds b0 = p == null ? null : bounds(p);

        if (e == null || p == null || b0 == null)
        {
            this.k.fail("PgUp: нет редактора");
            return true;
        }

        int step = PlacementEditorScreen.TestHooks.step(e);

        for (int i = 0; i < times; ++i)
        {
            KProbe.press(e, InputConstants.KEY_PAGEUP);
        }

        Bounds b1 = bounds(p);
        this.k.check("PgUp ×" + times + " (шаг " + step + "): низ", b0.minY() + times * step, b1 == null ? null : b1.minY());
        return true;
    }

    private boolean ground()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail("G: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_G);
        Bounds b = bounds(p);
        this.k.check("G: низ на площадке", DumpSchematics.FLOOR_Y, b == null ? null : b.minY());
        this.log("после G", p);
        return true;
    }

    private boolean snapOn()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail("C: нет редактора");
            return true;
        }

        if (PlacementEditorScreen.TestHooks.chunkSnap())
        {
            // Тумблер помнится до выхода из игры — выключаем, чтобы проверить включение
            KProbe.press(e, InputConstants.KEY_C);
            this.k.info("«по сетке чанков» был включён с прошлого раза — выключен");
        }

        this.beforeSnap = EditHistory.State.of(p);
        KProbe.press(e, InputConstants.KEY_C);
        Bounds b = bounds(p);
        this.snapped = EditHistory.State.of(p);
        this.k.check("C: тумблер включён", true, PlacementEditorScreen.TestHooks.chunkSnap());
        this.k.check("C: мин. угол X/Z на границе чанка " + b, true,
                b != null && Math.floorMod(b.minX(), CHUNK) == 0 && Math.floorMod(b.minZ(), CHUNK) == 0);
        this.log("после C", p);
        return true;
    }

    private boolean undo()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.beforeSnap == null)
        {
            this.k.fail("Ctrl+Z: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_Z, InputConstants.MOD_CONTROL);
        this.k.check("Ctrl+Z: как до сетки", this.beforeSnap, EditHistory.State.of(p));
        return true;
    }

    private boolean redo()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.snapped == null)
        {
            this.k.fail("Ctrl+Y: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_Y, InputConstants.MOD_CONTROL);
        this.k.check("Ctrl+Y: снова по сетке", this.snapped, EditHistory.State.of(p));
        return true;
    }

    private boolean snapOff()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.snapped == null)
        {
            this.k.fail("C: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_C);
        this.k.check("C: тумблер выключен", false, PlacementEditorScreen.TestHooks.chunkSnap());
        this.k.check("C: выключение не двигает", this.snapped, EditHistory.State.of(p));
        return true;
    }

    private boolean toggleTop(boolean want)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail("T: нет редактора");
            return true;
        }

        KProbe.press(e, InputConstants.KEY_T);
        this.k.check("T: вид сверху", want, TopViewCamera.active());
        return true;
    }

    private boolean press(int key)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail("клавиша " + key + ": нет редактора");
            return true;
        }

        KProbe.press(e, key);
        return true;
    }

    private boolean arrowRight(Minecraft mc)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || mc.player == null)
        {
            this.k.fail("→: нет редактора");
            return true;
        }

        int step = PlacementEditorScreen.TestHooks.step(e);
        Direction facing = mc.player.getDirection();
        BlockPos before = p.getOrigin();
        KProbe.press(e, InputConstants.KEY_RIGHT);
        // «Вправо» — по часовой от взгляда: (fx, fz) → (−fz, fx)
        BlockPos want = before.offset(-facing.getStepZ() * step, 0, facing.getStepX() * step);
        this.k.check("→ (взгляд " + facing + ", шаг " + step + "): origin", want.toShortString(), p.getOrigin().toShortString());
        return true;
    }

    // ===== «Голограммы за мышью» =====

    private boolean liveSmall()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail("голограммы: нет редактора");
            return true;
        }

        if (! this.liveTouched)
        {
            this.liveTouched = true;
            this.liveBefore = SchematicsConfig.LIVE_GHOSTS.getBooleanValue();
        }

        if (! SchematicsConfig.LIVE_GHOSTS.getBooleanValue())
        {
            this.clickSwitch(e);
        }

        this.k.check("голограммы: тумблер включён", true, SchematicsConfig.LIVE_GHOSTS.getBooleanValue());
        this.k.check("голограммы: маленькая схема — тумблер доступен", false, PlacementEditorScreen.TestHooks.liveBlocked(e));
        this.k.check("голограммы: пометка под тумблером — как работает",
                Strings.plain("layerlist.schematics.place.live_ghosts.hint"), PlacementEditorScreen.TestHooks.liveNote(e));
        return true;
    }

    private boolean liveBig()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail("большая: нет редактора");
            return true;
        }

        String limit = I18n.get("layerlist.schematics.place.live_ghosts.too_big",
                com.aell.layerlist.ui.kit.Kit.group(PlacementEditorScreen.TestHooks.liveMaxBlocks(),
                        Strings.plain("layerlist.schematics.num_group")));
        this.k.check("большая: блоков в метаданных > " + PlacementEditorScreen.TestHooks.liveMaxBlocks(), true,
                p.getSchematic().getMetadata().getTotalBlocks() > PlacementEditorScreen.TestHooks.liveMaxBlocks());
        this.k.check("большая: тумблер выключен", true, PlacementEditorScreen.TestHooks.liveBlocked(e));
        this.k.check("большая: причина под тумблером", limit, PlacementEditorScreen.TestHooks.liveNote(e));
        return true;
    }

    /** Клик по тумблеру обычным вводом экрана — в середину его строки. */
    private void clickSwitch(PlacementEditorScreen e)
    {
        int[] r = PlacementEditorScreen.TestHooks.liveSwitch(e);
        KProbe.click(e, r[0] + r[2] / 2.0, r[1] + r[3] / 2.0);
    }

    private boolean clickLive(boolean want)
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail("тумблер: нет редактора");
            return true;
        }

        this.clickSwitch(e);
        this.k.check("тумблер кликом: «Голограммы за мышью»", want, SchematicsConfig.LIVE_GHOSTS.getBooleanValue());
        return true;
    }

    private boolean clickLiveBlocked()
    {
        PlacementEditorScreen e = editor();

        if (e == null)
        {
            this.k.fail("большая: нет редактора");
            return true;
        }

        boolean before = SchematicsConfig.LIVE_GHOSTS.getBooleanValue();
        this.clickSwitch(e);
        this.k.check("большая: клик по выключенному тумблеру не меняет опцию", before,
                SchematicsConfig.LIVE_GHOSTS.getBooleanValue());
        return true;
    }

    /**
     * Схватить схему за центр и сразу сдвинуть мышь на ({@code dx}, {@code dz}) блоков, кнопку не
     * отпускать. Сразу после хвата (меньше 250 мс) голограммы ещё на месте при любом тумблере.
     */
    private boolean grab(int dx, int dz)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;
        Bounds b = p == null ? null : bounds(p);

        if (e == null || p == null || b == null)
        {
            this.k.fail("хват: нет редактора или размещения");
            return true;
        }

        double planeY = b.minY();
        Vec3 from = new Vec3(b.c2x() / 2.0 + 0.5, planeY, b.c2z() / 2.0 + 0.5);
        double[] start = solve(e, from, planeY);
        double[] end = solve(e, from.add(dx, 0.0, dz), planeY);

        if (start == null || end == null || ! free(e, start) || ! free(e, end))
        {
            this.k.fail("хват: точки мыши вне свободной части экрана: " + point(start) + " → " + point(end));
            return true;
        }

        BlockPos before = p.getOrigin();
        this.grabbedFrom = before;
        this.grabbedTo = before.offset(dx, 0, dz);
        this.grabEnd = end;

        e.onMouseClicked(KProbe.left(start[0], start[1]), false);
        e.onMouseDragged(KProbe.left(end[0], end[1]), end[0] - start[0], end[1] - start[1]);
        this.k.check("хват: схема схвачена", true, PlacementEditorScreen.TestHooks.dragging(e));
        this.k.check("хват: сразу после хвата голограммы на месте", before.toShortString(), p.getOrigin().toShortString());
        return true;
    }

    /** Кнопка всё ещё зажата, мышь стоит: голограммы (origin Litematica) догнали рамку — или нет. */
    private boolean ghostsFollowed(boolean want)
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.grabbedFrom == null || this.grabbedTo == null)
        {
            this.k.fail("голограммы: нет хвата");
            return true;
        }

        this.k.check("кнопка зажата: идёт перетаскивание", true, PlacementEditorScreen.TestHooks.dragging(e));
        this.k.check(want ? "голограммы догнали рамку до отпускания" : "голограммы ждут отпускания",
                (want ? this.grabbedTo : this.grabbedFrom).toShortString(), p.getOrigin().toShortString());
        return true;
    }

    private boolean release()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null || this.grabbedTo == null || this.grabEnd == null)
        {
            this.k.fail("отпускание: нет хвата");
            return true;
        }

        e.onMouseReleased(KProbe.left(this.grabEnd[0], this.grabEnd[1]));
        this.k.check("отпущено: схема на месте рамки", this.grabbedTo.toShortString(), p.getOrigin().toShortString());
        this.grabbedFrom = null;
        this.grabbedTo = null;
        this.grabEnd = null;
        return true;
    }

    /**
     * Схема больше 200 000 блоков — в памяти, без файла (64×52×64 = 212 992 камня): так проверка не
     * зависит от схем игрока, а Litematica держит её как обычную загруженную.
     */
    private boolean placeBig()
    {
        if (this.big == null)
        {
            AreaSelection area = new AreaSelection();
            area.setName("k-big");
            area.addSubRegionBox(new Box(BlockPos.ZERO, new BlockPos(BIG_X - 1, BIG_Y - 1, BIG_Z - 1), "main"), false);
            area.setExplicitOrigin(BlockPos.ZERO);

            LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "Material HUD harness");
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer("main");

            if (container == null)
            {
                this.k.fail("большая: Litematica не дала контейнер области");
                return true;
            }

            BlockState stone = Blocks.STONE.defaultBlockState();

            for (int y = 0; y < BIG_Y; ++y)
            {
                for (int z = 0; z < BIG_Z; ++z)
                {
                    for (int x = 0; x < BIG_X; ++x)
                    {
                        container.set(x, y, z, stone);
                    }
                }
            }

            schematic.getMetadata().setName("k-big");
            schematic.getMetadata().setTotalBlocks(BIG_X * BIG_Y * BIG_Z);
            this.big = schematic;
        }

        SchematicEntry entry = new SchematicEntry(SchematicLibrary.get().root().resolve("k-big.litematic"), false,
                SchematicFormat.LITEMATIC, 0L, 0L);
        SchematicPlacement p = PlacementOps.placeInFront(new LoadedSchematic(entry, this.big));
        this.placement = p;
        this.made.add(p);
        this.initial = EditHistory.State.of(p);
        this.k.ok("большая схема размещена: блоков " + p.getSchematic().getMetadata().getTotalBlocks() + ", origin "
                + p.getOrigin().toShortString());
        return true;
    }

    private boolean removeBig()
    {
        this.removeCurrent();
        return true;
    }

    // ===== Закрытие =====

    private boolean cancelledNew()
    {
        SchematicPlacement p = this.placement;

        if (editor() != null)
        {
            return false;
        }

        this.k.check("Esc: вернулись в «Схемы»", true, this.k.gui() != null);
        this.k.check("Esc: вид сверху выключен", false, TopViewCamera.active());
        this.k.check("Esc: новое размещение удалено", false, p != null && PlacementOps.all().contains(p));
        return true;
    }

    private boolean reverted()
    {
        SchematicPlacement p = this.placement;

        if (editor() != null)
        {
            return false;
        }

        this.k.check("Esc: вернулись в «Схемы»", true, this.k.gui() != null);
        this.k.check("Esc: размещение осталось", true, p != null && PlacementOps.all().contains(p));

        if (p != null && this.initial != null)
        {
            this.k.check("Esc: origin/поворот/отражение как при открытии", this.initial, EditHistory.State.of(p));
            this.log("после Esc", p);
        }

        return true;
    }

    private boolean enterKeeps()
    {
        PlacementEditorScreen e = editor();
        SchematicPlacement p = this.placement;

        if (e == null || p == null)
        {
            this.k.fail("Enter: нет редактора");
            return true;
        }

        BlockPos moved = p.getOrigin();
        KProbe.press(e, InputConstants.KEY_RETURN);
        this.k.check("Enter: редактор закрыт, «Схемы» на экране", true, editor() == null && this.k.gui() != null);
        this.k.check("Enter: размещение осталось", true, PlacementOps.all().contains(p));
        this.k.check("Enter: сдвиг сохранился", moved.toShortString(), p.getOrigin().toShortString());
        return true;
    }

    /** Строки «Списка HUD» (M25) — те же, что у HUD, в том же порядке. */
    private boolean hudRows()
    {
        if (! this.hudTouched)
        {
            this.hudTouched = true;
            this.hudBefore = LayerListConfigs.HUD_ENABLED;
            LayerListConfigs.HUD_ENABLED = true;
            LayerCountManager.getInstance().markDirty();
        }

        LayerCountManager hud = LayerCountManager.getInstance();

        if (! hud.hasData())
        {
            if (Util.getMillis() - this.k.startedAt() < 15_000L)
            {
                return false;
            }

            this.k.info("HUD без данных за 15 с — «Список HUD» не сверен");
            return true;
        }

        List<String> expected = new ArrayList<>();

        for (MaterialListEntry entry : hud.getEntries())
        {
            if (! entry.getStack().isEmpty())
            {
                expected.add(DumpSchematics.id(entry.getStack().getItem()) + "×" + entry.getCountTotal());
            }
        }

        List<String> actual = new ArrayList<>();

        for (MaterialRow row : SchematicMaterials.ofHud())
        {
            actual.add(DumpSchematics.id(row.stack().getItem()) + "×" + row.total());
        }

        this.k.check("«Список HUD» = строки HUD (" + expected.size() + ")", expected, actual);
        return true;
    }

    private boolean removeCurrent()
    {
        SchematicPlacement p = this.placement;

        if (p != null && PlacementOps.all().contains(p))
        {
            PlacementOps.remove(p);
        }

        this.k.check("размещение убрано", false, p != null && PlacementOps.all().contains(p));
        this.placement = null;
        return true;
    }

    // ===== Помощники =====

    private void log(String when, SchematicPlacement p)
    {
        CovOut.log(DumpSchematics.TAG + "размещение " + when + ": origin " + p.getOrigin().toShortString() + ", "
                + p.getRotation() + ", " + p.getMirror() + ", габарит " + bounds(p));
    }

    static @Nullable Bounds bounds(SchematicPlacement p)
    {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean any = false;

        for (Box box : p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values())
        {
            BlockPos a = box.getPos1();
            BlockPos b = box.getPos2();

            if (a == null || b == null)
            {
                continue;
            }

            any = true;
            minX = Math.min(minX, Math.min(a.getX(), b.getX()));
            minY = Math.min(minY, Math.min(a.getY(), b.getY()));
            minZ = Math.min(minZ, Math.min(a.getZ(), b.getZ()));
            maxX = Math.max(maxX, Math.max(a.getX(), b.getX()));
            maxY = Math.max(maxY, Math.max(a.getY(), b.getY()));
            maxZ = Math.max(maxZ, Math.max(a.getZ(), b.getZ()));
        }

        return any ? new Bounds(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    /**
     * Точка экрана, луч из которой падает на плоскость {@code y = planeY} в {@code target}: Ньютон по
     * {@link CursorRay} (в виде сверху отображение аффинное — хватает шага, в обычном — несколько).
     */
    static double @Nullable [] solve(PlacementEditorScreen e, Vec3 target, double planeY)
    {
        double x = PlacementEditorScreen.TestHooks.freeRight(e) / 2.0;
        double y = PlacementEditorScreen.TestHooks.freeBottom(e) / 2.0;
        double lastX = x;
        double lastY = y;
        double damping = 1.0;

        for (int i = 0; i < 60; ++i)
        {
            Vec3 p = hit(e, x, y, planeY);
            Vec3 px = hit(e, x + 1.0, y, planeY);
            Vec3 py = hit(e, x, y + 1.0, planeY);

            if (p == null || px == null || py == null)
            {
                // В перспективе шаг Ньютона может увести луч выше горизонта — назад и шаг вдвое короче
                damping *= 0.5;
                x = lastX + (x - lastX) * 0.5;
                y = lastY + (y - lastY) * 0.5;

                if (damping < 1.0E-3)
                {
                    return null;
                }

                continue;
            }

            lastX = x;
            lastY = y;

            double ex = p.x - target.x;
            double ez = p.z - target.z;

            if (ex * ex + ez * ez < 1.0E-10)
            {
                return new double[]{x, y};
            }

            double a = px.x - p.x;
            double b = py.x - p.x;
            double c = px.z - p.z;
            double d = py.z - p.z;
            double det = a * d - b * c;

            if (Math.abs(det) < 1.0E-12)
            {
                return null;
            }

            x -= damping * (d * ex - b * ez) / det;
            y -= damping * (-c * ex + a * ez) / det;
            damping = Math.min(1.0, damping * 2.0);
        }

        Vec3 last = hit(e, x, y, planeY);
        return last != null && last.distanceToSqr(target.x, planeY, target.z) < 1.0E-6 ? new double[]{x, y} : null;
    }

    static String point(double @Nullable [] p)
    {
        return p == null ? "нет решения" : String.format(java.util.Locale.ROOT, "(%.1f, %.1f)", p[0], p[1]);
    }

    private static @Nullable Vec3 hit(PlacementEditorScreen e, double mouseX, double mouseY, double planeY)
    {
        return CursorRay.cast(mouseX, mouseY, e.width, e.height).atHeight(planeY);
    }

    static boolean free(PlacementEditorScreen e, double[] point)
    {
        return point[0] >= 0.0 && point[1] >= 0.0 && point[0] < PlacementEditorScreen.TestHooks.freeRight(e)
                && point[1] < PlacementEditorScreen.TestHooks.freeBottom(e);
    }
}
