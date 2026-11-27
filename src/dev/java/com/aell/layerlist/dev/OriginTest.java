package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.SelectionHud;
import com.aell.layerlist.schematics.gui.export.StatusLine;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicOrigin;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code origin} (1.0.40): своя точка привязки далеко от выделения.
 *
 * <p>Беда: Litematica пишет блоки схемы относительно точки привязки выделения и при загрузке ставит эту точку в игрока.
 * Со своей точкой, оставшейся от прежнего выделения, схема у игроков без нашего мода вставала в тысячах блоков от них
 * («схемы нигде нет»), а наше «Разместить» ставит по габариту и этого не показывало.
 *
 * <p>Проверяется настоящими кликами (MouseHandler):
 * <ul>
 *   <li>вкладка «Выделение»: заметка «далеко» у точки привязки; «прицел» точки привязки без своей точки выключен и
 *       щелчок по нему её не выбирает, со своей — работает;</li>
 *   <li>«Сохранить схему» с далёкой точкой: предупреждение и тумблер (вкл.); сохранённый файл — точка привязки в углу,
 *       своя точка выделения снята; как его поставит обычная Litematica (размещение в точке игрока) — у игрока;</li>
 *   <li>тумблер выключен — файл с далёкой точкой, как раньше; точка рядом и без своей точки — окна без предупреждения,
 *       файлы как раньше;</li>
 *   <li>«Библиотека»: у файла с далёкой точкой — предупреждение и «Сохранить исправленную копию»; копия — точка в углу,
 *       блоки, сущности и блочные сущности те же, исходный файл не изменился;</li>
 *   <li>настоящие схемы игрока из {@code LL_ORIGIN_REAL} (папка): копия каждой сверяется с исходной блок в блок.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class OriginTest implements CovJob
{
    private static final String TAG = "[origin] ";
    private static final String KEY = "layerlist.schematics.";
    private static final BlockPos A = KData.SAMPLE;
    private static final BlockPos B = KData.SAMPLE.offset(6, 4, 6);
    private static final BlockPos FAR = KData.SAMPLE.offset(5000, 20, -3000);
    private static final BlockPos NEAR = KData.SAMPLE.offset(-5, 0, -5);

    private interface Action
    {
        boolean run(Minecraft mc) throws Exception;
    }

    private record Step(String name, long timeoutMs, Action action) {}

    private final List<Step> steps = new ArrayList<>();
    private int at;
    private long stepStarted;
    private long nextAt;
    private int ok;
    private int failed;
    private int shotNo;
    private Path shots = Path.of(".");
    private Path dir = Path.of(".");
    private Path realDir = Path.of(".");
    private @Nullable String farSha;
    private boolean diffLogged;
    private final List<Path> realFiles = new ArrayList<>();
    private final List<CompletableFuture<SchematicOrigin.Extent>> realWork = new ArrayList<>();
    private final List<Path> realCopies = new ArrayList<>();

    @Override
    public String name()
    {
        return "ORIGIN (своя точка привязки далеко от выделения: «Выделение», «Сохранить схему», починка в «Библиотеке»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        try
        {
            Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            this.shots = game.resolve("screenshots").resolve("origin");
            KData.recreate(this.shots);
            Path root = SchematicLibrary.get().refreshRoot();
            this.dir = root.resolve("_origin").toAbsolutePath().normalize();
            this.realDir = root.resolve("_origin_real").toAbsolutePath().normalize();
            KData.recreate(this.dir);
            KData.recreate(this.realDir);
            this.copyReal();
        }
        catch (IOException | RuntimeException e)
        {
            CovOut.log(TAG + "подготовка не удалась: " + e);
            return false;
        }

        server.execute(() ->
        {
            KData.command(server, "forceload add 480 -32 576 64");
            KData.buildPlatform(server, 516, -8, 556, 44, DumpSchematics.FLOOR_Y - 1);
            KData.buildSample(server);
            KData.command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 90 20", A.getX() + 14.5, A.getY() + 2, A.getZ() + 3.5));
        });

        this.plan();
        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    /** Настоящие схемы игрока для сверки копий: все {@code .litematic} из папки {@code LL_ORIGIN_REAL}. */
    private void copyReal() throws IOException
    {
        String from = System.getenv("LL_ORIGIN_REAL");

        if (from == null || from.isBlank() || ! Files.isDirectory(Path.of(from)))
        {
            CovOut.log(TAG + "INFO настоящих схем нет (LL_ORIGIN_REAL не задан) — сверка только на образце");
            return;
        }

        try (Stream<Path> list = Files.list(Path.of(from)))
        {
            for (Path file : list.filter(p -> p.getFileName().toString().endsWith(".litematic")).sorted().toList())
            {
                Path to = this.realDir.resolve(file.getFileName().toString());
                Files.copy(file, to);
                this.realFiles.add(to);
            }
        }

        CovOut.log(TAG + "настоящих схем для сверки: " + this.realFiles.size());
    }

    private void add(String name, long timeoutMs, Action action)
    {
        this.steps.add(new Step(name, timeoutMs, action));
    }

    private void pause(long ms)
    {
        this.add("пауза " + ms + " мс", ms + 2000L, mc -> Util.getMillis() - this.stepStarted >= ms);
    }

    private void plan()
    {
        this.add("образец на клиенте", 30_000L, mc -> mc.level != null && mc.level.getBlockState(A).is(Blocks.STONE_BRICKS)
                && mc.level.getBlockState(A.offset(1, 1, 1)).is(Blocks.CHEST));
        this.add("простое выделение образца, своя точка далеко", 5_000L, mc -> this.select("origin-a", FAR));
        this.add("открыть «Выделение»", 10_000L, mc ->
        {
            if (! (mc.gui.screen() instanceof GuiSchematics))
            {
                GuiSchematics.openSelection(null);
                return false;
            }

            return true;
        });
        this.pause(4000L);
        this.add("папка проверки в «Библиотеке»", 5_000L, mc ->
        {
            KProbe.call(this.files(mc), "open", this.dir);
            return this.check("папка сохранения — _origin", this.dir.equals(this.libraryFolder(mc)));
        });
        this.add("заметка «далеко» у точки привязки", 5_000L, mc ->
        {
            int expected = this.expectedGap(FAR);
            this.check("расстояние своей точки до выделения = " + expected + " бл.", expected, this.originGap());
            this.check("это больше порога " + SchematicOrigin.FAR, expected > SchematicOrigin.FAR);
            this.shot(mc, "tab-far");
            return true;
        });
        this.pause(600L);

        // Карточка в игре («Смотреть на мир») — та же заметка
        this.add("клик «Смотреть на мир»", 5_000L, mc -> this.clickWidget(mc, (Widget) field(this.pane(mc), "seeWorld")));
        this.pause(2000L);
        this.add("карточка в игре с заметкой «далеко»", 5_000L, mc ->
        {
            this.check("экран закрыт, карточка в игре включена", mc.gui.screen() == null && SelectionHud.active());
            this.shot(mc, "hud-far");
            return true;
        });
        this.pause(600L);
        this.add("Esc → снова «Выделение»", 5_000L, mc ->
        {
            KeyEvent esc = new KeyEvent(InputConstants.KEY_ESCAPE, 0x1B, 0);
            DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.PRESS, esc);
            DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.RELEASE, esc);
            return true;
        });
        this.add("экран «Выделение» вернулся", 8_000L, mc -> mc.gui.screen() instanceof GuiSchematics && ! SelectionHud.active());
        this.pause(1500L);

        // «Прицел» точки привязки: без своей точки — выключен
        this.add("тумблер «Своя точка» → выкл (клик)", 5_000L, mc -> this.clickWidget(mc, (Widget) field(this.pane(mc), "explicitOrigin")));
        this.pause(400L);
        this.add("без своей точки «прицел» точки привязки выключен", 5_000L, mc ->
        {
            AreaSelection selection = selection();
            Widget grab = this.originGrab(mc);
            this.check("своя точка снята", selection.getExplicitOrigin() == null);
            this.check("«прицел» точки привязки выключен", ! grab.enabled());
            return this.clickWidget(mc, grab);
        });
        this.pause(300L);
        this.add("щелчок по выключенному «прицелу» точку привязки не выбирает", 5_000L, mc ->
        {
            // Выбранная точка привязки — то, после чего щелчок инструментом Litematica ставил свою точку вместо угла
            this.check("точка привязки не выбрана для щелчков в мире", ! selection().isOriginSelected());
            this.shot(mc, "tab-no-own-origin");
            return true;
        });
        this.pause(500L);
        this.add("тумблер «Своя точка» → вкл (клик)", 5_000L, mc -> this.clickWidget(mc, (Widget) field(this.pane(mc), "explicitOrigin")));
        this.pause(400L);
        this.add("со своей точкой «прицел» работает", 5_000L, mc ->
        {
            this.check("своя точка = угол выделения " + A.toShortString(), A, selection().getExplicitOrigin());
            this.check("«прицел» точки привязки включён", this.originGrab(mc).enabled());
            return this.clickWidget(mc, this.originGrab(mc));
        });
        this.pause(300L);
        this.add("«прицел» выбрал точку привязки, второй щелчок — отпустил", 5_000L, mc ->
        {
            this.check("точка привязки выбрана", selection().isOriginSelected());
            return this.clickWidget(mc, this.originGrab(mc));
        });
        this.pause(300L);
        this.add("точка привязки отпущена", 5_000L, mc -> this.check("точка привязки не выбрана", ! selection().isOriginSelected()));

        // Сохранение: далеко (тумблер вкл.), далеко нарочно (тумблер выкл.), рядом, без своей точки
        this.save("origin-a", FAR, true, false, "save-far", (mc, file) ->
        {
            SchematicOrigin.Extent extent = this.extent(file);
            this.check("файл: точка привязки в углу схемы (мин. угол 0,0,0)", "0,0,0", min(extent));
            this.check("файл: расстояние до блоков 0", 0, extent == null ? -1 : extent.gap());
            this.check("своя точка выделения снята", selection().getExplicitOrigin() == null);
            this.asLitematica(mc, file, 0);
        });
        this.save("origin-b", FAR, true, true, "save-far-kept", (mc, file) ->
        {
            SchematicOrigin.Extent extent = this.extent(file);
            int expected = this.expectedGap(FAR);
            this.check("тумблер выкл.: файл с далёкой точкой, как раньше", expected, extent == null ? -1 : extent.gap());
            this.check("своя точка выделения осталась", FAR, selection().getExplicitOrigin());
            this.asLitematica(mc, file, expected);
            this.farSha = sha1(file);
        });
        this.save("origin-c", NEAR, false, false, "save-near", (mc, file) ->
        {
            SchematicOrigin.Extent extent = this.extent(file);
            int expected = this.expectedGap(NEAR);
            this.check("точка рядом (" + expected + " бл.): файл как раньше", expected, extent == null ? -1 : extent.gap());
            this.check("своя точка рядом осталась", NEAR, selection().getExplicitOrigin());
        });
        this.save("origin-d", null, false, false, "save-plain", (mc, file) ->
        {
            SchematicOrigin.Extent extent = this.extent(file);
            this.check("без своей точки: мин. угол 0,0,0", "0,0,0", min(extent));
        });

        // «Библиотека»: файл с далёкой точкой
        this.add("«Библиотека»: выбрать origin-b (далёкая точка)", 5_000L, mc ->
        {
            KProbe.call(this.gui(mc), "setTab", KProbe.enumConstant(KProbe.GUI_PACKAGE + "GuiSchematics$Tab", "LIBRARY"));
            KProbe.call(this.files(mc), "open", this.dir);
            KProbe.call(this.files(mc), "refresh");
            KProbe.call(this.files(mc), "selectPath", this.dir.resolve("origin-b.litematic"));
            return true;
        });
        this.add("карточка прочитала точку привязки", 10_000L, mc -> KProbe.call(this.gui(mc), "selectedOrigin") != null);
        this.add("убрать тосты и курсор", 5_000L, mc -> this.tidyView(mc));
        this.pause(1500L);
        this.add("карточка: предупреждение и кнопка копии", 5_000L, mc ->
        {
            SchematicOrigin.Extent extent = (SchematicOrigin.Extent) KProbe.call(this.gui(mc), "selectedOrigin");
            Button fix = this.fixButton(mc);
            this.check("карточка: точка привязки далеко (" + extent.gap() + " бл.)", extent.far());
            this.check("кнопка «" + fix.label() + "» видна и включена", fix.visible() && fix.enabled());
            this.shot(mc, "library-far");
            return true;
        });
        this.pause(600L);
        this.add("клик «Сохранить исправленную копию»", 5_000L, mc -> this.clickWidget(mc, this.fixButton(mc)));
        this.add("копия записана", 20_000L, mc -> Files.exists(this.fixedCopy()) && ! (boolean) KProbe.call(this.gui(mc), "fixingOrigin"));
        this.pause(1500L);
        this.add("копия: точка привязки в углу, содержимое то же", 20_000L, mc ->
        {
            Path far = this.dir.resolve("origin-b.litematic");
            Path copy = this.fixedCopy();
            SchematicOrigin.Extent extent = this.extent(copy);
            this.check("копия «" + copy.getFileName() + "»: мин. угол 0,0,0", "0,0,0", min(extent));
            this.check("исходный файл не изменился (sha1)", this.farSha, sha1(far));
            this.compare(far, copy);
            this.asLitematica(mc, copy, 0);
            Object selected = KProbe.call(this.gui(mc), "selected");
            this.check("в списке выбрана копия", selected instanceof SchematicEntry e && e.path().equals(copy));
            return true;
        });
        this.add("карточка копии прочитана", 10_000L, mc -> KProbe.call(this.gui(mc), "selectedOrigin") != null);
        this.pause(800L);
        this.add("тост о копии", 5_000L, mc ->
        {
            Toasts toasts = KProbe.fieldOfType(this.gui(mc), Toasts.class);
            String want = I18n.get(KEY + "info.origin_fix.done", this.fixedCopy().getFileName().toString());
            this.check("тост «" + want + "»", toasts != null && Toasts.TestHooks.texts(toasts).contains(want));
            this.shot(mc, "library-fixed-toast");
            return true;
        });
        this.pause(600L);
        this.add("убрать тосты и курсор", 5_000L, mc -> this.tidyView(mc));
        this.pause(600L);
        this.add("у копии предупреждения нет", 5_000L, mc ->
        {
            SchematicOrigin.Extent extent = (SchematicOrigin.Extent) KProbe.call(this.gui(mc), "selectedOrigin");
            this.check("карточка копии: точка привязки у схемы", ! extent.far());
            this.check("кнопки копии нет", ! this.fixButton(mc).visible());
            this.shot(mc, "library-fixed");
            return true;
        });
        this.pause(600L);

        // Настоящие схемы игрока
        this.add("настоящие схемы: запись копий", 5_000L, mc ->
        {
            for (Path file : this.realFiles)
            {
                Path copy = this.realDir.resolve(file.getFileName().toString().replace(".litematic", " (fixed).litematic"));
                this.realCopies.add(copy);
                this.realWork.add(SchematicLibrary.get().fixOrigin(entry(file), copy));
            }

            return true;
        });
        this.add("настоящие схемы: копии готовы", 180_000L, mc -> this.realWork.stream().allMatch(CompletableFuture::isDone));
        this.add("настоящие схемы: сверка с исходными", 180_000L, mc ->
        {
            for (int i = 0; i < this.realFiles.size(); ++i)
            {
                Path file = this.realFiles.get(i);
                Path copy = this.realCopies.get(i);

                if (this.realWork.get(i).isCompletedExceptionally() || ! Files.exists(copy))
                {
                    this.fail("копия «" + file.getFileName() + "» не записана");
                    continue;
                }

                SchematicOrigin.Extent before = this.extent(file);
                SchematicOrigin.Extent after = this.extent(copy);
                this.info("«" + file.getFileName() + "»: точка привязки была в " + (before == null ? "?" : before.gap()) + " бл. от блоков");
                this.check("«" + copy.getFileName() + "»: мин. угол 0,0,0", "0,0,0", min(after));
                this.compare(file, copy);
                this.asLitematica(mc, copy, 0);
            }

            return true;
        });

        this.add("убрать за собой", 5_000L, mc ->
        {
            AreaSelection selection = selection();
            selection.setExplicitOrigin(null);
            selection.setOriginSelected(false);
            Nav.clear();
            mc.gui.setScreen(null);
            KData.recreate(this.realDir);
            Files.deleteIfExists(this.realDir);
            return true;
        });
    }

    private interface Verify
    {
        void run(Minecraft mc, Path file) throws Exception;
    }

    /** Одно сохранение через окно: выделение с точкой {@code origin}, окно, (тумблер), «Сохранить», проверка файла. */
    private void save(String name, @Nullable BlockPos origin, boolean warned, boolean flip, String shot, Verify verify)
    {
        Path file = this.dir.resolve(name + ".litematic");

        this.add(name + ": вкладка «Выделение», точка привязки " + (origin == null ? "по углам" : origin.toShortString()), 5_000L, mc ->
        {
            KProbe.call(this.gui(mc), "setTab", KProbe.enumConstant(KProbe.GUI_PACKAGE + "GuiSchematics$Tab", "SELECTION"));
            return this.select(name, origin);
        });
        this.pause(700L);
        this.add(name + ": клик «Сохранить схему»", 5_000L, mc -> this.clickWidget(mc, (Widget) field(this.pane(mc), "save")));
        this.add(name + ": окно открыто", 5_000L, mc -> this.modal(mc) != null);
        this.pause(400L);
        this.add(name + ": окно " + (warned ? "с предупреждением" : "без предупреждения"), 5_000L, mc ->
        {
            Modal modal = this.modal(mc);
            StatusLine warning = this.row(modal, StatusLine.class, null);
            Toggle snap = this.row(modal, Toggle.class, I18n.get(KEY + "save.origin_snap"));

            this.check(name + ": строка предупреждения " + (warned ? "есть" : "нет"), warned, warning != null && warning.message() != null);
            this.check(name + ": тумблер «" + I18n.get(KEY + "save.origin_snap") + "» " + (warned ? "есть" : "нет"), warned, snap != null);

            if (warned && warning != null && warning.message() != null)
            {
                this.info(name + ": «" + warning.message().text() + "»");
                this.check(name + ": в предупреждении расстояние", warning.message().text().contains(digits(this.expectedGap(FAR))));
            }

            if (snap != null)
            {
                this.check(name + ": тумблер включён по умолчанию", value(snap));

                if (flip)
                {
                    return this.clickAt(mc, snap.x() + Toggle.TRACK / 2.0, snap.y() + snap.h() / 2.0);
                }
            }

            return true;
        });
        this.pause(500L);
        this.add(name + ": снимок окна", 5_000L, mc ->
        {
            if (flip)
            {
                Toggle snap = this.row(this.modal(mc), Toggle.class, I18n.get(KEY + "save.origin_snap"));
                this.check(name + ": тумблер выключен кликом", snap != null && ! value(snap));
            }

            this.shot(mc, shot);
            return true;
        });
        this.pause(600L);
        this.add(name + ": клик «Сохранить»", 5_000L, mc ->
        {
            Button save = KTrash.modalButton(this.modal(mc), I18n.get(KEY + "save.ok"));

            if (save == null)
            {
                this.fail(name + ": кнопки «Сохранить» нет");
                return true;
            }

            return this.clickWidget(mc, save);
        });
        this.add(name + ": файл записан", 30_000L, mc -> Files.exists(file) && Files.size(file) > 0L && this.modal(mc) == null);
        this.pause(1500L);
        this.add(name + ": проверка файла", 20_000L, mc ->
        {
            verify.run(mc, file);
            return true;
        });
    }

    // ===== Выделение =====

    private static AreaSelection selection()
    {
        return DataManager.getSelectionManager().getCurrentSelection();
    }

    private boolean select(String name, @Nullable BlockPos origin)
    {
        SelectionManager manager = DataManager.getSelectionManager();

        if (manager.getSelectionMode() != SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();
        Box box = selection.getSelectedSubRegionBox();

        if (box == null)
        {
            selection.createNewSubRegionBox(A, name);
            box = selection.getSelectedSubRegionBox();
        }

        selection.setSubRegionCornerPos(box, Corner.CORNER_1, A);
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, B);
        SelectionManager.renameSubRegionBoxIfSingle(selection, name);
        selection.setName(name);
        selection.setExplicitOrigin(origin);
        selection.setOriginSelected(false);
        return true;
    }

    private int expectedGap(BlockPos origin)
    {
        return SchematicOrigin.distance(origin.getX(), origin.getY(), origin.getZ(), A.getX(), A.getY(), A.getZ(), B.getX(), B.getY(), B.getZ());
    }

    private int originGap() throws ReflectiveOperationException
    {
        Method method = Class.forName(KProbe.GUI_PACKAGE + "SelectionPane").getDeclaredMethod("originGap", AreaSelection.class);
        method.setAccessible(true);
        return (int) method.invoke(null, selection());
    }

    // ===== Экран =====

    private GuiSchematics gui(Minecraft mc)
    {
        if (mc.gui.screen() instanceof GuiSchematics gui)
        {
            return gui;
        }

        throw new IllegalStateException("экран «Схемы» закрыт");
    }

    private Object pane(Minecraft mc) throws ReflectiveOperationException
    {
        return field(this.gui(mc), "selection");
    }

    private Object files(Minecraft mc) throws ReflectiveOperationException
    {
        return field(this.gui(mc), "files");
    }

    private Path libraryFolder(Minecraft mc) throws ReflectiveOperationException
    {
        return ((Path) KProbe.call(this.gui(mc), "libraryFolder")).toAbsolutePath().normalize();
    }

    private Widget originGrab(Minecraft mc) throws ReflectiveOperationException
    {
        return (Widget) field(field(this.pane(mc), "origin"), "grab");
    }

    private Button fixButton(Minecraft mc) throws ReflectiveOperationException
    {
        return (Button) field(field(this.gui(mc), "info"), "fixOrigin");
    }

    private Path fixedCopy()
    {
        return this.dir.resolve("origin-b " + I18n.get(KEY + "info.origin_fix.suffix") + ".litematic");
    }

    private @Nullable Modal modal(Minecraft mc)
    {
        Layers layers = KProbe.fieldOfType(this.gui(mc), Layers.class);
        return layers != null && layers.top() instanceof Modal modal ? modal : null;
    }

    /** Виджет строки окна по типу; {@code label} — подпись тумблера ({@code null} — любая). */
    @SuppressWarnings("unchecked")
    private <T extends Widget> @Nullable T row(@Nullable Modal modal, Class<T> type, @Nullable String label) throws ReflectiveOperationException
    {
        if (modal == null)
        {
            return null;
        }

        Field rows = Modal.class.getDeclaredField("rows");
        rows.setAccessible(true);

        for (Object row : (List<Object>) rows.get(modal))
        {
            Method widget = row.getClass().getDeclaredMethod("widget");
            widget.setAccessible(true);
            Object value = widget.invoke(row);

            if (type.isInstance(value) && (label == null || label.equals(field(value, "label"))))
            {
                return type.cast(value);
            }
        }

        return null;
    }

    private static boolean value(Toggle toggle) throws ReflectiveOperationException
    {
        return ((BooleanSupplier) field(toggle, "value")).getAsBoolean();
    }

    /** Перед снимком карточки: тосты прошлых шагов не закрывают её, под курсором нет подсказки. */
    private boolean tidyView(Minecraft mc)
    {
        Toasts toasts = KProbe.fieldOfType(this.gui(mc), Toasts.class);

        if (toasts != null)
        {
            toasts.clear();
        }

        SupportTest.moveMouse(mc, 4, 4);
        return true;
    }

    private boolean clickWidget(Minecraft mc, Widget widget)
    {
        return this.clickAt(mc, widget.x() + widget.w() / 2.0, widget.y() + widget.h() / 2.0);
    }

    private boolean clickAt(Minecraft mc, double x, double y)
    {
        SupportTest.click(mc, x, y);
        return true;
    }

    // ===== Файлы =====

    private static SchematicEntry entry(Path file) throws IOException
    {
        return new SchematicEntry(file, false, SchematicFormat.LITEMATIC, Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    private SchematicOrigin.@Nullable Extent extent(Path file) throws IOException
    {
        return SchematicLibrary.get().origin(entry(file)).join();
    }

    private static String min(SchematicOrigin.@Nullable Extent extent)
    {
        return extent == null ? "не прочитан" : extent.minX() + "," + extent.minY() + "," + extent.minZ();
    }

    private static String digits(int value)
    {
        // В тексте число идёт с разделителем разрядов — сверяем по старшим цифрам
        String text = Integer.toString(value);
        return text.length() > 3 ? text.substring(0, text.length() - 3) : text;
    }

    private static String sha1(Path file) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
    }

    /**
     * Как схему поставит обычная Litematica: «Загрузить схему» создаёт размещение с точкой привязки в блоке игрока
     * ({@code GuiSchematicLoad$ButtonListener}: {@code BlockPos.containing(player.position())} → {@code createFor}).
     * Меряем, на сколько блоки размещения отстоят от игрока.
     */
    private void asLitematica(Minecraft mc, Path file, int expectedGap)
    {
        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());

        if (schematic == null)
        {
            this.fail("Litematica не прочитала «" + file.getFileName() + "»");
            return;
        }

        BlockPos player = BlockPos.containing(mc.player.position());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, player, "origin-check", true, false);
        Pair<BlockPos, BlockPos> corners = PositionUtils.getEnclosingAreaCorners(
                placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values());
        BlockPos min = corners.getLeft();
        BlockPos max = corners.getRight();
        int gap = SchematicOrigin.distance(player.getX(), player.getY(), player.getZ(), min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ());

        if (expectedGap == 0)
        {
            this.check("Litematica ставит «" + file.getFileName() + "» у игрока: мин. угол размещения = блок игрока", player, min);
        }
        else
        {
            this.check("Litematica ставит «" + file.getFileName() + "» в стороне от игрока, бл.", expectedGap, gap);
        }
    }

    /** Исходная схема и копия глазами Litematica: регионы, блоки, сущности, блочные сущности, отложенные тики. */
    private void compare(Path original, Path copy)
    {
        LitematicaSchematic a = LitematicaSchematic.createFromFile(original.getParent(), original.getFileName().toString());
        LitematicaSchematic b = LitematicaSchematic.createFromFile(copy.getParent(), copy.getFileName().toString());

        if (a == null || b == null)
        {
            this.fail("Litematica не прочитала пару «" + original.getFileName() + "»");
            return;
        }

        Map<String, BlockPos> positions = a.getAreaPositions();
        Map<String, BlockPos> sizes = a.getAreaSizes();
        String what = "«" + copy.getFileName() + "»";
        this.check(what + ": те же регионы (" + positions.size() + ")", positions.keySet().equals(b.getAreaPositions().keySet()));

        // Сдвиг положений одинаков у всех регионов
        Vec3i shift = null;
        long cells = 0L;
        long different = 0L;
        long entities = 0L;
        long blockEntities = 0L;
        boolean sameShift = true;
        boolean sameSizes = true;
        boolean sameEntities = true;
        boolean sameBlockEntities = true;
        boolean sameTicks = true;

        for (String name : positions.keySet())
        {
            BlockPos pb = b.getAreaPositions().get(name);

            if (pb == null)
            {
                continue;
            }

            Vec3i delta = positions.get(name).subtract(pb);
            sameShift &= shift == null || shift.equals(delta);
            shift = delta;
            sameSizes &= sizes.get(name).equals(b.getAreaSizes().get(name));

            LitematicaBlockStateContainer ca = a.getSubRegionContainer(name);
            LitematicaBlockStateContainer cb = b.getSubRegionContainer(name);
            Vec3i size = ca.getSize();
            sameSizes &= size.equals(cb.getSize());

            if (size.equals(cb.getSize()))
            {
                for (int y = 0; y < size.getY(); ++y)
                {
                    for (int z = 0; z < size.getZ(); ++z)
                    {
                        for (int x = 0; x < size.getX(); ++x)
                        {
                            ++cells;

                            if (ca.get(x, y, z) != cb.get(x, y, z))
                            {
                                ++different;
                            }
                        }
                    }
                }
            }

            List<LitematicaSchematic.EntityInfo> ea = a.getEntityListForRegion(name);
            List<LitematicaSchematic.EntityInfo> eb = b.getEntityListForRegion(name);
            entities += ea.size();
            sameEntities &= ea.size() == eb.size();

            for (int i = 0; i < Math.min(ea.size(), eb.size()); ++i)
            {
                sameEntities &= ea.get(i).posVec().equals(eb.get(i).posVec()) && this.sameData(what, ea.get(i).nbt(), eb.get(i).nbt());
            }

            var ta = a.getBlockEntityMapForRegion(name);
            var tb = b.getBlockEntityMapForRegion(name);
            blockEntities += ta.size();
            sameBlockEntities &= ta.keySet().equals(tb.keySet());

            for (BlockPos pos : ta.keySet())
            {
                sameBlockEntities &= this.sameData(what, ta.get(pos), tb.get(pos));
            }

            sameTicks &= a.getScheduledBlockTicksForRegion(name).keySet().equals(b.getScheduledBlockTicksForRegion(name).keySet());
        }

        this.check(what + ": все регионы сдвинуты одинаково (" + (shift == null ? "?" : shift.toShortString()) + ")", sameShift);
        this.check(what + ": размеры регионов те же", sameSizes);
        this.check(what + ": блоки те же (клеток " + cells + ", отличий " + different + ")", different == 0L && cells > 0L);
        this.check(what + ": сущности те же (" + entities + ")", sameEntities);
        this.check(what + ": блочные сущности те же (" + blockEntities + ")", sameBlockEntities);
        this.check(what + ": отложенные тики те же", sameTicks);
        this.check(what + ": имя, автор и число блоков в метаданных те же",
                String.valueOf(a.getMetadata().getName()).equals(String.valueOf(b.getMetadata().getName()))
                        && String.valueOf(a.getMetadata().getAuthor()).equals(String.valueOf(b.getMetadata().getAuthor()))
                        && a.getMetadata().getTotalBlocks() == b.getMetadata().getTotalBlocks());
    }

    /**
     * Данные сущности в исходнике и в копии — как ванильные NBT: у копии другой порядок ключей в файле, а значения те же
     * ({@code CompoundTag.equals} сравнивает отображения, списки и массивы — по содержимому). Первое расхождение — в лог.
     */
    private boolean sameData(String what, @Nullable CompoundData a, @Nullable CompoundData b)
    {
        if (a == null || b == null)
        {
            return a == b;
        }

        boolean same = DataConverterNbt.toVanillaCompound(a).equals(DataConverterNbt.toVanillaCompound(b));

        if (! same && ! this.diffLogged)
        {
            this.diffLogged = true;
            String ta = String.valueOf(DataConverterNbt.toVanillaCompound(a));
            String tb = String.valueOf(DataConverterNbt.toVanillaCompound(b));
            this.info(what + ": расхождение данных\n  было:  " + ta.substring(0, Math.min(600, ta.length())) + "\n  стало: "
                    + tb.substring(0, Math.min(600, tb.length())));
        }

        return same;
    }

    // ===== Ход =====

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        if (this.at >= this.steps.size())
        {
            CovOut.log(TAG + "итог: " + (this.failed == 0 ? "OK" : "FAIL") + " — OK " + this.ok + ", FAIL " + this.failed);
            return true;
        }

        Step step = this.steps.get(this.at);

        if (this.stepStarted == 0L)
        {
            this.stepStarted = now;
        }

        boolean done;

        try
        {
            done = step.action().run(mc);
        }
        catch (Exception | LinkageError e)
        {
            this.fail("шаг «" + step.name() + "»: " + e);
            done = true;
        }

        if (! done && now - this.stepStarted > step.timeoutMs())
        {
            this.fail("шаг «" + step.name() + "»: не дождались за " + step.timeoutMs() + " мс");
            done = true;
        }

        if (done)
        {
            ++this.at;
            this.stepStarted = 0L;
            this.nextAt = now + 120L;
        }
        else
        {
            this.nextAt = now + 50L;
        }

        return false;
    }

    private void shot(Minecraft mc, String name)
    {
        String file = String.format(Locale.ROOT, "origin-%02d-%s.png", ++this.shotNo, name);
        Path path = this.shots.resolve(file);

        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image ->
        {
            try (NativeImage owned = image)
            {
                owned.writeToFile(path);
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log(TAG + "снимок " + file + " не записан: " + e);
            }
        });

        CovOut.log(TAG + "снимок " + file);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
        {
            try
            {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                // выше по иерархии
            }
        }

        throw new NoSuchFieldException(owner.getClass().getSimpleName() + "." + name);
    }

    private void info(String what)
    {
        CovOut.log(TAG + "INFO " + what);
    }

    private void fail(String what)
    {
        ++this.failed;
        CovOut.log(TAG + "FAIL " + what);
    }

    private boolean check(String what, boolean pass)
    {
        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        CovOut.log(TAG + (pass ? "OK   " : "FAIL ") + what);
        return true;
    }

    private boolean check(String what, Object expected, Object actual)
    {
        boolean pass = String.valueOf(expected).equals(String.valueOf(actual));
        return this.check(what + (pass ? " = " + actual : " — ждали " + expected + ", вышло " + actual), pass);
    }

    @Override
    public void close()
    {
    }
}
