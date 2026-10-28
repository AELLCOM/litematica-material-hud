package com.aell.layerlist.dev;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.TextInputManager;
import com.mojang.blaze3d.platform.Window;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

/**
 * Режим W — ввод текста в своих полях так, как его даёт настоящее окно 26.3 (SDL). Мышь — через
 * {@code MouseHandler.onMove/onButton} (их зовёт цикл событий SDL), клавиши — через
 * {@code KeyboardHandler.keyPress}, буквы — через {@code KeyboardHandler.textInput} (сюда приходит
 * {@code SDL_EVENT_TEXT_INPUT}), черновик IME — через {@code KeyboardHandler.textEditing}. Режим k
 * печатал вызовом {@code insert()} мимо этого пути и не видел, что SDL букв не шлёт вовсе.
 *
 * <p>Главная проверка — не текст в поле (его даёт и {@code textInput} без SDL), а то, что поле в
 * фокусе включило ввод текста: {@code TextInputManager} (закрытые поля — отражением, только здесь)
 * держит владельцем именно это поле, и сам SDL отвечает {@code SDL_TextInputActive}. Плюс итог: поиск
 * фильтрует список, ник с Enter становится чипом, X редактора двигает размещение — и после потери
 * фокуса или закрытия экрана ввод снова выключен.
 *
 * <p>Если запущено под X11 и есть {@code python3} — ещё и сквозная проверка: клавиши через XTest
 * (настоящие события X-сервера), сначала в ванильный чат (контроль окружения), потом в наш поиск.
 *
 * <p>Строки — {@code [w] OK/FAIL/INFO …}, итог — {@code [typing] OK n, FAIL m}; отчёт {@code typing.csv}
 * в {@code LL_COVERAGE_OUT} (без неё — {@code <gameDir>/coverage-w}).
 */
@Environment(EnvType.CLIENT)
public final class DumpTyping implements CovJob
{
    static final String TAG = "[w] ";

    private static final long GAP_MS = 60L;
    private static final long POLL_MS = 40L;
    /** Между кликами — дольше порога двойного клика {@code MouseHandler} (250 мс). */
    private static final long CLICK_GAP_MS = 400L;
    private static final long EXPECT_MS = 4_000L;

    private static final int FLOOR_Y = 100;
    private static final BlockPos STAND = new BlockPos(668, FLOOR_Y, 0);
    private static final BlockPos SAMPLE = new BlockPos(667, FLOOR_Y, 6);
    private static final String[] NAMES = {"w-alpha-tower", "w-beta-house", "w-gamma-bridge"};

    /** SDL3: у клавиш без знака код клавиши — скан-код с этим битом. */
    private static final int SCANCODE_MASK = 1 << 30;
    /** ЙЦУКЕН поверх QWERTY: какая физическая клавиша даёт русскую букву. */
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбю";
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";

    private static final String TAB_CLASS = KProbe.GUI_PACKAGE + "GuiSchematics$Tab";

    private final Deque<DumpSchematics.Step> queue = new ArrayDeque<>();
    private DumpSchematics.@Nullable Step current;
    private long startedAt;
    private long until;
    private long lastClickAt;
    /** Первый вызов текущего шага (действие «один раз», дальше — ожидание). */
    private boolean firstRun;

    private Path out = Path.of(".");
    private Path wDir = Path.of(".");
    private @Nullable BufferedWriter report;
    private int ok;
    private int failed;

    private @Nullable GuiSchematics gui;
    private @Nullable PlacementEditorScreen editor;
    private @Nullable SchematicPlacement placement;
    private @Nullable CompletableFuture<LoadedSchematic> loading;
    private @Nullable GuiConfigs configs;
    private @Nullable ExportWindow exportWindow;
    private @Nullable Modal settingsDialog;
    private @Nullable ChatScreen chat;
    private @Nullable Process xtest;
    private boolean xtestControl;
    private long xWindow;

    // Что вернуть после прогона
    private int guiScaleBefore;
    private List<String> rosterBefore = List.of();
    private String urlBefore = "";
    private String tokenBefore = "";
    private BlockPos originBefore = BlockPos.ZERO;
    private Rotation rotationBefore = Rotation.NONE;
    private Mirror mirrorBefore = Mirror.NONE;
    private int targetX;

    @Override
    public String name()
    {
        return "W (настоящий ввод текста, SDL)";
    }

    // ===== Жизненный цикл =====

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
            this.out = outputDir();
            Files.createDirectories(this.out);
            this.report = Files.newBufferedWriter(this.out.resolve("typing.csv"), StandardCharsets.UTF_8);
            this.report.write("subject,result,detail\n");
            this.wDir = SchematicLibrary.get().refreshRoot().resolve("_w").toAbsolutePath().normalize();
            KData.recreate(this.wDir);
        }
        catch (IOException | RuntimeException e)
        {
            CovOut.log(TAG + "подготовка не удалась: " + e);
            return false;
        }

        this.guiScaleBefore = mc.options.guiScale().get();
        this.rosterBefore = List.copyOf(SchematicsState.get().roster());
        this.urlBefore = SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue();
        this.tokenBefore = SchematicsConfig.SHEETS_TOKEN.getStringValue();

        server.execute(() ->
        {
            KData.command(server, "forceload add 640 -16 704 32");
            KData.buildPlatform(server, STAND.getX() - 12, -8, STAND.getX() + 12, 16, FLOOR_Y - 1);
            KData.command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks",
                    SAMPLE.getX(), SAMPLE.getY(), SAMPLE.getZ(), SAMPLE.getX() + 2, SAMPLE.getY() + 1, SAMPLE.getZ() + 2));
            KData.command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone",
                    SAMPLE.getX() + 1, SAMPLE.getY() + 2, SAMPLE.getZ() + 1));
            KData.command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 30", STAND.getX() + 0.5,
                    STAND.getY(), STAND.getZ() + 0.5));
        });

        this.plan();
        this.until = Util.getMillis() + 2000L;
        CovOut.log(TAG + "выход: " + this.out + ", схемы: " + this.wDir);
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.until;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.until)
        {
            return false;
        }

        if (this.current == null)
        {
            this.current = this.queue.pollFirst();

            if (this.current == null)
            {
                return true;
            }

            this.startedAt = now;
            this.firstRun = true;
        }

        DumpSchematics.Step running = this.current;
        boolean done;

        try
        {
            done = running.action().run(mc);
            this.firstRun = false;
        }
        catch (RuntimeException | LinkageError e)
        {
            this.fail(running.name() + ": исключение " + e);
            e.printStackTrace(System.out);
            done = true;
        }

        if (! done && now - this.startedAt > running.timeoutMs())
        {
            this.fail(running.name() + ": не дождались за " + running.timeoutMs() + " мс");
            done = true;
        }

        if (done)
        {
            this.current = null;
            this.until = Math.max(this.until, Util.getMillis() + GAP_MS);
        }
        else if (this.until <= now)
        {
            this.until = now + POLL_MS;
        }

        return false;
    }

    @Override
    public void close()
    {
        Minecraft mc = Minecraft.getInstance();

        try
        {
            if (this.xtest != null && this.xtest.isAlive())
            {
                this.xtest.destroyForcibly();
            }

            if (this.placement != null && PlacementOps.all().contains(this.placement))
            {
                PlacementOps.remove(this.placement);
            }

            if (GuiUtils.getCurrentScreen() != null || mc.gui.screen() != null)
            {
                Nav.clear();
                GuiBase.openGui(null);
            }

            SchematicsState.get().setRoster(this.rosterBefore);
            SchematicsState.get().save();
            SchematicsConfig.SHEETS_WEB_APP_URL.setValueFromString(this.urlBefore);
            SchematicsConfig.SHEETS_TOKEN.setValueFromString(this.tokenBefore);
            new LayerListConfigs().save();
            DumpSchematics.scale(mc, this.guiScaleBefore);
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log(TAG + "уборка не удалась: " + e);
        }

        CovOut.log("[typing] OK " + this.ok + ", FAIL " + this.failed);

        if (this.report != null)
        {
            try
            {
                this.report.close();
            }
            catch (IOException e)
            {
                CovOut.log(TAG + "отчёт не закрылся: " + e);
            }
        }
    }

    private static Path outputDir()
    {
        String override = System.getenv("LL_COVERAGE_OUT");

        if (override != null && ! override.isBlank())
        {
            return Path.of(override).toAbsolutePath().normalize();
        }

        return FabricLoader.getInstance().getGameDir().resolve("coverage-w").toAbsolutePath().normalize();
    }

    // ===== Сценарий =====

    private void plan()
    {
        this.add("окно 1600×900, GUI 2", 8_000L, this::window);
        this.add("образец на клиенте", 30_000L, mc -> mc.level != null
                && mc.level.getBlockState(SAMPLE).is(Blocks.OAK_PLANKS)
                && mc.level.getBlockState(SAMPLE.offset(1, 2, 1)).is(Blocks.STONE));
        this.add("три схемы в _w", 10_000L, this::writeSchematics);

        this.library();
        this.materials();
        this.exportDialog();
        this.settingsDialog();
        this.editorFields();
        this.closeWithFocus();
        this.settingsScreen();
        this.xtest();
    }

    /** Библиотека: поиск файлов, клавиши, кириллица, IME, «буква в списке начинает поиск». */
    private void library()
    {
        this.add("открыть «Схемы»", 10_000L, this::openSchematics);
        this.add("папка _w в «Библиотеке»", 15_000L, this::openWFolder);
        this.expect("список без фокуса поля: ввод текста держит экран (буква — поиск)",
                () -> this.inputOn() && this.owner() == this.gui && ! this.search().focused(), this::state);

        this.act("клик мышью в поиск файлов", () -> this.clickWidget(this.search()));
        this.expect("поиск в фокусе держит ввод текста SDL",
                () -> this.search().focused() && this.owner() == this.search() && this.inputOn(), this::state);
        this.expect("окно кандидатов IME — у курсора поиска", () -> this.areaInside(this.search()), this::area);

        this.act("набор «beta» клавишами", () -> this.type("beta"));
        this.expectText("поиск", this::search, "beta");
        this.expect("поиск «beta» оставил одну схему — w-beta-house",
                () -> this.files().equals(List.of("w-beta-house.litematic")), () -> this.files().toString());

        this.act("Backspace", () -> this.press(InputConstants.KEY_BACKSPACE, 0));
        this.expectText("поиск после Backspace", this::search, "bet");

        this.act("Ctrl+A и «gamma»", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("gamma");
        });
        this.expectText("поиск после Ctrl+A", this::search, "gamma");
        this.expect("поиск «gamma» оставил одну схему — w-gamma-bridge",
                () -> this.files().equals(List.of("w-gamma-bridge.litematic")), () -> this.files().toString());

        this.act("← ← и «z»", () ->
        {
            this.press(InputConstants.KEY_LEFT, 0);
            this.press(InputConstants.KEY_LEFT, 0);
            this.type("z");
        });
        this.expectText("вставка с середины", this::search, "gamzma");
        this.act("Backspace, Home и «w-»", () ->
        {
            this.press(InputConstants.KEY_BACKSPACE, 0);
            this.press(InputConstants.KEY_HOME, 0);
            this.type("w-");
            this.press(InputConstants.KEY_END, 0);
        });
        this.expectText("Home и вставка в начало", this::search, "w-gamma");

        this.act("Ctrl+A и «дом» (русская раскладка)", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("дом");
        });
        this.expectText("кириллица", this::search, "дом");

        this.act("черновик IME «にほ»", () -> this.preedit("にほ"));
        this.expect("черновик IME показан плашкой у поиска",
                () -> KProbe.fieldOfType(this.search(), IMEPreeditOverlay.class) != null, this::state);
        this.act("IME подтверждает «日本»", () -> this.text("日本"));
        this.expect("подтверждённый набор в поле, плашка ушла",
                () -> "дом日本".equals(this.search().text()) && KProbe.fieldOfType(this.search(), IMEPreeditOverlay.class) == null,
                () -> "«" + this.search().text() + "»");

        this.act("Ctrl+A, Backspace", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.press(InputConstants.KEY_BACKSPACE, 0);
        });
        this.expectText("поиск очищен", this::search, "");

        this.act("Esc в поиске", () -> this.press(InputConstants.KEY_ESCAPE, 0));
        this.expect("Esc снял фокус, экран открыт, ввод текста снова у экрана",
                () -> ! this.search().focused() && GuiUtils.getCurrentScreen() == this.gui && this.owner() == this.gui
                        && this.inputOn(), this::state);

        this.act("буква «a» в списке", () -> this.type("a"));
        this.expect("буква в списке начала поиск: поле в фокусе, «a», ввод у поля",
                () -> this.search().focused() && "a".equals(this.search().text()) && this.owner() == this.search(),
                this::state);

        this.act("Enter в поиске", () -> this.press(InputConstants.KEY_RETURN, 0));
        this.expect("Enter: фокус в списке, текст остался, ввод снова у экрана",
                () -> ! this.search().focused() && "a".equals(this.search().text()) && this.owner() == this.gui,
                this::state);

        this.act("клик в поиск, Ctrl+A, Backspace, Esc", () ->
        {
            this.clickWidget(this.search());
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.press(InputConstants.KEY_BACKSPACE, 0);
            this.press(InputConstants.KEY_ESCAPE, 0);
        });
        this.expect("поиск пуст, список снова полный", () -> this.search().text().isEmpty() && this.files().size() == 3,
                () -> this.files().toString());
    }

    /** «Материалы»: поиск по таблице схемы. */
    private void materials()
    {
        this.add("выбрать w-alpha-tower клавишами", 5_000L, this::selectAlpha);
        this.act("кнопка «Материалы» карточки", () -> this.clickWidget(this.infoButton("layerlist.schematics.info.materials")));
        this.expect("подвкладка «Материалы»", () -> "MATERIALS".equals(this.tab()), this::tab);
        this.add("строки «Материалов»", 30_000L, mc -> this.lines("lines").size() > 0);
        this.expect("«Материалы» без фокуса поля: ввод держит экран", () -> this.owner() == this.gui && this.inputOn(),
                this::state);

        this.act("клик мышью в поиск материалов", () -> this.clickWidget(this.materialsSearch()));
        this.expect("поиск материалов держит ввод текста",
                () -> this.materialsSearch().focused() && this.owner() == this.materialsSearch() && this.inputOn(), this::state);
        this.expect("окно IME — у курсора поиска материалов", () -> this.areaInside(this.materialsSearch()), this::area);

        this.act("набор «oak»", () -> this.type("oak"));
        this.expectText("поиск материалов", this::materialsSearch, "oak");
        // По id предмета: в русской игре «oak» находит дубовое по id, а в имени его нет («Дубовые доски»)
        this.expect("«oak» отфильтровал таблицу: остались только дубовые",
                () -> this.filtered(line -> line.key().toLowerCase(Locale.ROOT).contains("oak")), this::linesState);

        this.act("Ctrl+A и «zzzq»", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("zzzq");
        });
        this.expect("«zzzq» — пустая таблица", () -> this.lines("shown").isEmpty(), this::linesState);

        this.act("Ctrl+A, Backspace, Esc", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.press(InputConstants.KEY_BACKSPACE, 0);
            this.press(InputConstants.KEY_ESCAPE, 0);
        });
        this.expect("таблица снова полная, фокус снят, ввод у экрана",
                () -> this.lines("shown").size() == this.lines("lines").size() && ! this.materialsSearch().focused()
                        && this.owner() == this.gui, this::linesState);

        this.act("назад в «Библиотеку»", () -> this.setTab("LIBRARY"));
        this.expect("«Библиотека», ввод у экрана", () -> "LIBRARY".equals(this.tab()) && this.owner() == this.gui, this::state);
    }

    /** Окно выгрузки: «Кто собирает» (ники → чипы) и название таблицы. */
    private void exportDialog()
    {
        this.act("ростер пуст", () -> SchematicsState.get().setRoster(List.of()));
        this.act("«Выгрузить ▾» карточки", () -> this.clickWidget(Objects.requireNonNull(
                KProbe.cardExport(KProbe.fieldNamedType(this.gui, "InfoPane")), "нет кнопки экспорта карточки")));
        this.expect("меню форматов", () -> this.layers().top() instanceof ContextMenu, this::top);
        this.act("↓ Enter — окно выгрузки", () ->
        {
            this.press(InputConstants.KEY_DOWN, 0);
            this.press(InputConstants.KEY_RETURN, 0);
        });
        this.expect("окно выгрузки открыто; поле не в фокусе — ввода текста нет",
                () -> this.layers().top() instanceof ExportWindow && ! this.inputOn() && ! this.sdlActive(), this::state);
        this.act("найти поля окна", () -> this.exportWindow = (ExportWindow) this.layers().top());

        this.reveal("«Кто собирает»", () -> this.exportWindow, this::roster);
        this.act("клик мышью в «Кто собирает»", () -> this.clickWidget(this.roster()));
        this.expect("поле ника держит ввод текста SDL",
                () -> this.roster().focused() && this.owner() == this.roster() && this.inputOn(), this::state);
        this.expect("окно IME — у курсора поля ника", () -> this.areaInside(this.roster()), this::area);

        this.act("набор «Aell_» (Shift+- даёт «_»)", () -> this.type("Aell_"));
        this.expectText("поле ника", this::roster, "Aell_");
        this.act("Enter", () -> this.press(InputConstants.KEY_RETURN, 0));
        this.expect("Enter: ник стал чипом, поле пусто и в фокусе",
                () -> SchematicsState.get().roster().equals(List.of("Aell_")) && this.roster().text().isEmpty()
                        && this.roster().focused() && this.owner() == this.roster(),
                () -> SchematicsState.get().roster() + " «" + this.roster().text() + "»");

        this.act("«Vi» и Enter (короче трёх)", () ->
        {
            this.type("Vi");
            this.press(InputConstants.KEY_RETURN, 0);
        });
        this.expect("короткий ник не добавлен, остался в поле",
                () -> SchematicsState.get().roster().equals(List.of("Aell_")) && "Vi".equals(this.roster().text()),
                () -> SchematicsState.get().roster() + " «" + this.roster().text() + "»");

        this.act("Ctrl+A, «Drimofey», Enter", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("Drimofey");
            this.press(InputConstants.KEY_RETURN, 0);
        });
        this.expect("второй чип", () -> SchematicsState.get().roster().equals(List.of("Aell_", "Drimofey")),
                () -> SchematicsState.get().roster().toString());

        this.reveal("название таблицы", () -> this.exportWindow, this::exportName);
        this.act("клик мышью в название таблицы", () -> this.clickWidget(this.exportName()));
        this.expect("ввод перешёл к названию, поле ника без фокуса",
                () -> this.exportName().focused() && ! this.roster().focused() && this.owner() == this.exportName()
                        && this.inputOn(), this::state);
        this.act("Ctrl+A и «Моя таблица»", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("Моя таблица");
        });
        this.expectText("название таблицы", this::exportName, "Моя таблица");

        this.act("Tab", () -> this.press(InputConstants.KEY_TAB, 0));
        this.expect("Tab: ввод у поля ника", () -> this.roster().focused() && ! this.exportName().focused()
                && this.owner() == this.roster() && this.inputOn(), this::state);

        this.act("Esc", () -> this.press(InputConstants.KEY_ESCAPE, 0));
        this.expect("первый Esc снял фокус, окно открыто, ввод текста выключен",
                () -> ! this.roster().focused() && this.layers().top() == this.exportWindow && ! this.inputOn()
                        && ! this.sdlActive(), this::state);
        this.act("Esc", () -> this.press(InputConstants.KEY_ESCAPE, 0));
        this.expect("второй Esc закрыл окно, ввод снова у экрана (буква — поиск)",
                () -> ! this.layers().any() && this.owner() == this.gui && this.inputOn(), this::state);
        this.act("ростер как был", () -> SchematicsState.get().setRoster(this.rosterBefore));
    }

    /** Окно настроек вкладки: адрес таблицы, ключ (скрытый). */
    private void settingsDialog()
    {
        this.act("шестерёнка в шапке", () -> this.clickWidget(this.settingsButton()));
        this.expect("окно настроек с тремя полями", () -> this.layers().top() instanceof Modal modal
                && ! (modal instanceof ExportWindow) && modalInputs(modal).size() == 3, this::top);
        this.act("найти поля окна", () -> this.settingsDialog = (Modal) this.layers().top());

        this.reveal("адрес таблицы", () -> this.settingsDialog, () -> this.settingsInput(1));
        this.act("клик мышью в «Адрес веб-приложения»", () -> this.clickWidget(this.settingsInput(1)));
        this.expect("адрес держит ввод текста", () -> this.settingsInput(1).focused()
                && this.owner() == this.settingsInput(1) && this.inputOn(), this::state);
        this.act("Ctrl+A и «https://example.org/w»", () ->
        {
            this.press(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
            this.type("https://example.org/w");
        });
        this.expect("адрес набран и сразу записан в настройку",
                () -> "https://example.org/w".equals(this.settingsInput(1).text())
                        && "https://example.org/w".equals(SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue()),
                () -> "«" + this.settingsInput(1).text() + "» / «" + SchematicsConfig.SHEETS_WEB_APP_URL.getStringValue() + "»");

        this.act("Tab", () -> this.press(InputConstants.KEY_TAB, 0));
        this.expect("Tab: ввод у поля ключа", () -> this.settingsInput(2).focused() && ! this.settingsInput(1).focused()
                && this.owner() == this.settingsInput(2) && this.inputOn(), this::state);

        this.act("Esc, Esc", () ->
        {
            this.press(InputConstants.KEY_ESCAPE, 0);
            this.press(InputConstants.KEY_ESCAPE, 0);
        });
        this.expect("окно настроек закрыто, ввод снова у экрана", () -> ! this.layers().any() && this.owner() == this.gui,
                this::state);
        this.act("настройки как были", () ->
        {
            SchematicsConfig.SHEETS_WEB_APP_URL.setValueFromString(this.urlBefore);
            SchematicsConfig.SHEETS_TOKEN.setValueFromString(this.tokenBefore);
            new LayerListConfigs().save();
        });
    }

    /** Редактор размещения: поля X/Y/Z. */
    private void editorFields()
    {
        this.add("размещение w-alpha-tower", 15_000L, this::place);
        this.act("редактор размещения", () ->
        {
            if (this.gui != null && this.placement != null)
            {
                PlacementEditorScreen.open(this.gui, this.placement, false, false);
            }
        });
        this.expect("редактор открыт; без поля ввода текста нет", () ->
        {
            this.editor = PlacementEditorScreen.TestHooks.current(Minecraft.getInstance().gui.screen());
            return this.editor != null && ! this.inputOn() && ! this.sdlActive();
        }, this::state);

        this.act("клик мышью в X", () -> this.clickField(0));
        this.expect("X в вводе, держит ввод текста SDL", () -> this.editingAxis() == 0
                && this.owner() == this.editorField(0) && this.inputOn(), this::state);
        this.expect("окно IME — у курсора X", () -> this.areaInsideBounds(this.fieldBounds(0)), this::area);

        this.act("R (буква «r») и новое X цифрами", () ->
        {
            SchematicPlacement p = Objects.requireNonNull(this.placement);
            this.originBefore = p.getOrigin();
            this.rotationBefore = p.getRotation();
            this.mirrorBefore = p.getMirror();
            this.targetX = this.originBefore.getX() + 7;
            this.type("r");
            this.type(Integer.toString(this.targetX));
        });
        this.expect("R в поле не повернул схему", () -> this.placement != null && this.placement.getRotation() == this.rotationBefore
                && this.placement.getMirror() == this.mirrorBefore && this.editingAxis() == 0, this::placementState);
        this.act("Enter", () -> this.press(InputConstants.KEY_RETURN, 0));
        this.expect("Enter: размещение сдвинулось по X, поле вышло из ввода, ввод текста выключен",
                () -> this.placement != null && this.placement.getOrigin().getX() == this.targetX
                        && this.placement.getOrigin().getY() == this.originBefore.getY()
                        && this.placement.getOrigin().getZ() == this.originBefore.getZ()
                        && this.editingAxis() == -1 && ! this.inputOn() && ! this.sdlActive(), this::placementState);

        this.act("клик мышью в Y", () -> this.clickField(1));
        this.expect("Y в вводе", () -> this.editingAxis() == 1 && this.owner() == this.editorField(1), this::state);
        this.act("«5», Esc", () ->
        {
            this.type("5");
            this.press(InputConstants.KEY_ESCAPE, 0);
        });
        this.expect("Esc в поле: Y прежний, редактор открыт, ввод текста выключен",
                () -> this.placement != null && this.placement.getOrigin().getY() == this.originBefore.getY()
                        && this.editingAxis() == -1 && Minecraft.getInstance().gui.screen() == this.editor
                        && ! this.inputOn() && ! this.sdlActive(), this::placementState);

        this.act("клик мышью в Z", () -> this.clickField(2));
        this.expect("Z в вводе", () -> this.editingAxis() == 2 && this.owner() == this.editorField(2), this::state);
        this.act("«1» и уход из редактора мимо «Готово»", () ->
        {
            this.type("1");
            Nav.back();
        });
        this.expect("ушли: Z не применён, поле вышло из ввода, ввод у экрана «Схемы»",
                () -> Minecraft.getInstance().gui.screen() == this.gui && this.editor != null
                        && PlacementEditorScreen.TestHooks.editingAxis(this.editor) == -1 && this.placement != null
                        && this.placement.getOrigin().getZ() == this.originBefore.getZ() && this.owner() == this.gui,
                this::placementState);
        this.act("убрать размещение", () ->
        {
            if (this.placement != null && PlacementOps.all().contains(this.placement))
            {
                PlacementOps.remove(this.placement);
            }
        });
    }

    /** Экран закрыли, пока поле было в фокусе. */
    private void closeWithFocus()
    {
        this.act("клик в поиск", () -> this.clickWidget(this.search()));
        this.expect("поиск держит ввод", () -> this.owner() == this.search() && this.inputOn(), this::state);
        this.act("закрыть «Схемы»", () ->
        {
            Nav.clear();
            GuiBase.openGui(null);
        });
        this.expect("экран закрыт: ввод текста выключен, фокус снят",
                () -> Minecraft.getInstance().gui.screen() == null && ! this.inputOn() && ! this.sdlActive()
                        && ! this.search().focused(), this::state);
    }

    /** Экран настроек мода (общий aell-ui): поиск. */
    private void settingsScreen()
    {
        this.act("экран настроек мода", () -> GuiConfigs.open(null, null));
        this.expect("настройки открыты, ввод текста держит экран", () ->
        {
            this.configs = Minecraft.getInstance().gui.screen() instanceof GuiConfigs screen ? screen : null;
            return this.configs != null && this.owner() == this.configs && this.inputOn() && this.sdlActive();
        }, this::state);
        this.act("клик мышью в поиск настроек", () ->
        {
            GuiConfigs screen = Objects.requireNonNull(this.configs);
            int x = this.intField(screen, "searchX");
            int w = this.intField(screen, "searchW");
            int y = this.intField(screen, "panelY") + (Ui.HEADER_H - Ui.SEARCH_H) / 2;
            this.click(x + w / 2.0, y + Ui.SEARCH_H / 2.0);
        });
        this.expect("поиск настроек в фокусе", () -> Boolean.TRUE.equals(this.read(this.configs, "searchFocus")), this::state);
        this.act("набор «scale»", () -> this.type("scale"));
        this.expect("поиск настроек = «scale»", () -> "scale".equals(this.read(this.configs, "search")),
                () -> "«" + this.read(this.configs, "search") + "»");
        this.act("Esc и закрыть", () ->
        {
            this.press(InputConstants.KEY_ESCAPE, 0);
            Nav.clear();
            GuiBase.openGui(null);
        });
        this.expect("настройки закрыты: ввод текста выключен", () -> Minecraft.getInstance().gui.screen() == null
                && ! this.inputOn() && ! this.sdlActive(), this::state);
    }

    /** Сквозная проверка настоящими клавишами X11 (XTest): сначала ванильный чат, потом наш поиск. */
    private void xtest()
    {
        this.add("XTest: окружение", 2_000L, mc ->
        {
            this.xWindow = x11Window();
            boolean x11 = System.getenv("DISPLAY") != null && this.xWindow != 0L && Files.isExecutable(Path.of("/usr/bin/python3"));

            if (! x11)
            {
                this.info("XTest: не X11 или нет python3 — сквозная проверка пропущена");
                this.queue.removeIf(step -> step.name().startsWith("XTest:"));
            }
            else
            {
                this.info("XTest: окно X11 0x" + Long.toHexString(this.xWindow));
            }

            return true;
        });
        this.act("XTest: ванильный чат", () ->
        {
            this.chat = new ChatScreen("", false);
            Minecraft.getInstance().gui.setScreen(this.chat);
        });
        this.add("XTest: «q» в чат", 10_000L, mc -> this.runXTest("q"));
        this.add("XTest: контроль (ванильное поле)", 3_000L, mc ->
        {
            EditBox box = KProbe.fieldOfType(this.chat, EditBox.class);
            boolean got = box != null && box.getValue().contains("q");

            if (! got && Util.getMillis() - this.startedAt < 2_000L)
            {
                return false;
            }

            this.xtestControl = got;
            this.info("XTest: ванильный чат " + (got ? "получил «q» — окружение доставляет ввод" : "не получил «q» — окружение не доставляет XTest, наш итог будет INFO"));
            return true;
        });
        this.act("XTest: «Схемы», клик в поиск", () ->
        {
            Minecraft.getInstance().gui.setScreen(null);
            GuiSchematics.open(null);
        });
        this.add("XTest: поиск в фокусе", 10_000L, mc ->
        {
            if (! (Minecraft.getInstance().gui.screen() instanceof GuiSchematics screen))
            {
                return false;
            }

            this.gui = screen;

            if (! this.clickReady())
            {
                return false;
            }

            this.clickWidget(this.search());
            return this.search().focused();
        });
        this.add("XTest: «xyz» в поиск", 10_000L, mc -> this.runXTest("xyz"));
        this.add("XTest: итог", 3_000L, mc ->
        {
            boolean got = this.search().text().contains("xyz");

            if (! got && Util.getMillis() - this.startedAt < 2_000L)
            {
                return false;
            }

            if (got)
            {
                this.ok("XTest: настоящие клавиши X11 дошли до поиска через SDL («" + this.search().text() + "»)");
            }
            else if (this.xtestControl)
            {
                this.fail("XTest: ванильный чат буквы получил, а наш поиск — нет («" + this.search().text() + "»)");
            }
            else
            {
                this.info("XTest: буквы не дошли ни до чата, ни до поиска — окружение, не мод");
            }

            Nav.clear();
            GuiBase.openGui(null);
            return true;
        });
    }

    // ===== Шаги =====

    private void add(String name, long timeoutMs, DumpSchematics.Action action)
    {
        this.queue.addLast(new DumpSchematics.Step(name, timeoutMs, action));
    }

    /** Действие ввода; клики — не чаще {@link #CLICK_GAP_MS}, чтобы два клика не сложились в двойной. */
    private void act(String name, Runnable action)
    {
        this.add(name, 3_000L, mc ->
        {
            if (! this.clickReady())
            {
                return false;
            }

            action.run();
            return true;
        });
    }

    /** Ждать условия до {@link #EXPECT_MS} (часть состояния меняется только в кадре — отрисовкой). */
    private void expect(String what, BooleanSupplier condition, Supplier<String> detail)
    {
        this.add(what, EXPECT_MS + 1_000L, mc ->
        {
            boolean pass;

            try
            {
                pass = condition.getAsBoolean();
            }
            catch (RuntimeException e)
            {
                pass = false;
            }

            if (pass)
            {
                this.ok(what);
                return true;
            }

            if (Util.getMillis() - this.startedAt < EXPECT_MS)
            {
                return false;
            }

            String why;

            try
            {
                why = detail.get();
            }
            catch (RuntimeException e)
            {
                why = e.toString();
            }

            this.fail(what + " — " + why);
            return true;
        });
    }

    /**
     * Прокрутить тело окна колесом мыши (тем же путём, что событие SDL), пока поле не окажется в
     * видимой части: по полю за краем прокрутки клик игрока не попадёт.
     */
    private void reveal(String what, Supplier<@Nullable Modal> modal, Supplier<TextInput> field)
    {
        this.add("прокрутить окно до поля: " + what, 5_000L, mc ->
        {
            Modal m = Objects.requireNonNull(modal.get(), "окна нет");
            TextInput input = field.get();
            int bodyY = this.intField(m, "bodyY");
            int bodyH = this.intField(m, "bodyH");

            if (input.y() >= bodyY && input.y() + input.h() <= bodyY + bodyH)
            {
                return true;
            }

            Window window = mc.getWindow();
            double x = (this.intField(m, "x") + this.intField(m, "w") / 2.0) * window.getScreenWidth() / window.getGuiScaledWidth();
            double y = (bodyY + bodyH / 2.0) * window.getScreenHeight() / window.getGuiScaledHeight();
            DevInput.onMove(mc.mouseHandler, handle(), x, y, 0.0, 0.0);
            DevInput.onScroll(mc.mouseHandler, handle(), 0.0, input.y() < bodyY ? 1.0 : -1.0);
            return false;
        });
    }

    private void expectText(String what, Supplier<TextInput> field, String expected)
    {
        this.expect(what + " = «" + expected + "»", () -> expected.equals(field.get().text()),
                () -> "вышло «" + field.get().text() + "»");
    }

    private boolean window(Minecraft mc)
    {
        Window window = mc.getWindow();

        if (this.firstRun && (window.getWidth() != 1600 || window.getHeight() != 900))
        {
            window.setWindowed(1600, 900);
            return false;
        }

        if ((window.getWidth() != 1600 || window.getHeight() != 900) && Util.getMillis() - this.startedAt < 5_000L)
        {
            return false;
        }

        DumpSchematics.scale(mc, 2);
        this.info("окно " + window.getWidth() + "×" + window.getHeight() + ", GUI 2 → "
                + window.getGuiScaledWidth() + "×" + window.getGuiScaledHeight());
        return true;
    }

    private boolean writeSchematics(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName("w-sample");
        area.addSubRegionBox(new Box(SAMPLE, SAMPLE.offset(2, 2, 2), "main"), false);
        area.setExplicitOrigin(SAMPLE);

        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "Material HUD harness", CovOut::log);
        boolean all = schematic != null;

        for (String name : NAMES)
        {
            all &= schematic != null && schematic.writeToFile(this.wDir, name, true)
                    && KExports.nonEmpty(this.wDir.resolve(name + ".litematic"));
        }

        this.check("три схемы сохранены Litematica в _w", all);
        return true;
    }

    private boolean openSchematics(Minecraft mc)
    {
        if (this.firstRun)
        {
            GuiSchematics.open(null);
            return false;
        }

        this.gui = mc.gui.screen() instanceof GuiSchematics screen ? screen : null;
        return this.gui != null;
    }

    private boolean openWFolder(Minecraft mc)
    {
        if (this.firstRun)
        {
            this.setTab("LIBRARY");

            try
            {
                KProbe.call(Objects.requireNonNull(this.filesPane()), "open", this.wDir);
            }
            catch (ReflectiveOperationException e)
            {
                this.fail("FileListPane.open: " + e);
                return true;
            }

            return false;
        }

        return this.files().size() == NAMES.length;
    }

    private boolean selectAlpha(Minecraft mc)
    {
        SchematicEntry selected = this.selected();

        if (selected != null && selected.fileName().startsWith(NAMES[0]))
        {
            this.ok("w-alpha-tower выбрана клавишами (Home, ↓)");
            return true;
        }

        if (this.firstRun)
        {
            this.press(InputConstants.KEY_HOME, 0);
        }
        else
        {
            this.press(InputConstants.KEY_DOWN, 0);
        }

        return false;
    }

    private boolean place(Minecraft mc)
    {
        SchematicEntry entry = SchematicLibrary.get().entry(this.wDir.resolve(NAMES[0] + ".litematic"));

        if (entry == null)
        {
            this.fail("нет записи каталога w-alpha-tower");
            return true;
        }

        if (this.loading == null)
        {
            this.loading = SchematicLibrary.get().load(entry);
        }

        if (! this.loading.isDone())
        {
            return false;
        }

        try
        {
            this.placement = PlacementOps.placeInFront(this.loading.join());
            this.ok("размещение w-alpha-tower: origin " + this.placement.getOrigin().toShortString());
        }
        catch (RuntimeException e)
        {
            this.fail("размещение w-alpha-tower: " + e);
        }

        return true;
    }

    // ===== Настоящий ввод =====

    private static long handle()
    {
        return Minecraft.getInstance().getWindow().handle();
    }

    private boolean clickReady()
    {
        return Util.getMillis() - this.lastClickAt >= CLICK_GAP_MS;
    }

    /** Левый клик в точку GUI тем же путём, что событие мыши SDL: перемещение, нажатие, отпускание. */
    private void click(double guiX, double guiY)
    {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double x = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
        MouseButtonInfo left = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);

        DevInput.onMove(mc.mouseHandler, handle(), x, y, 0.0, 0.0);
        DevInput.onButton(mc.mouseHandler, handle(), left, InputConstants.PRESS);
        DevInput.onButton(mc.mouseHandler, handle(), left, InputConstants.RELEASE);
        this.lastClickAt = Util.getMillis();
    }

    private void clickWidget(Widget widget)
    {
        if (! widget.visible() || widget.w() <= 0 || widget.h() <= 0)
        {
            this.fail("клик: виджет не на экране (" + widget.x() + ", " + widget.y() + ", " + widget.w() + "×" + widget.h() + ")");
            return;
        }

        this.click(widget.x() + widget.w() / 2.0, widget.y() + widget.h() / 2.0);
    }

    private void clickField(int axis)
    {
        int[] b = this.fieldBounds(axis);
        this.click(b[0] + b[2] / 2.0, b[1] + b[3] / 2.0);
    }

    /** Клавиша: нажатие и отпускание через {@code KeyboardHandler.keyPress}, как от {@code SDL_EVENT_KEY_*}. */
    private void press(int scancode, int modifiers)
    {
        this.press(scancode, keycode(scancode), modifiers);
    }

    private void press(int scancode, int keycode, int modifiers)
    {
        KeyEvent event = new KeyEvent(scancode, keycode, modifiers);
        DevInput.keyPress(Minecraft.getInstance().keyboardHandler, handle(), InputConstants.PRESS, event);
        DevInput.keyPress(Minecraft.getInstance().keyboardHandler, handle(), InputConstants.RELEASE, event);
    }

    /** Код клавиши SDL3 для клавиш без знака (скан-код с битом маски) и для служебных со знаком. */
    private static int keycode(int scancode)
    {
        return switch (scancode)
        {
            case InputConstants.KEY_RETURN -> '\r';
            case InputConstants.KEY_ESCAPE -> 0x1B;
            case InputConstants.KEY_BACKSPACE -> '\b';
            case InputConstants.KEY_TAB -> '\t';
            case InputConstants.KEY_SPACE -> ' ';
            case InputConstants.KEY_DELETE -> 0x7F;
            default -> scancode >= InputConstants.KEY_A && scancode <= InputConstants.KEY_Z
                    ? 'a' + scancode - InputConstants.KEY_A : scancode | SCANCODE_MASK;
        };
    }

    /**
     * Набор строки, как его даёт SDL: на каждый знак — нажатие клавиши, событие текста, отпускание.
     * Русские буквы — на клавишах ЙЦУКЕН (коды клавиш SDL3 при этом латинские); прочее — только
     * событием текста, как подтверждение IME.
     */
    private void type(String text)
    {
        text.codePoints().forEach(c ->
        {
            String piece = new String(Character.toChars(c));
            int[] key = physicalKey(c);

            if (key == null)
            {
                this.text(piece);
                return;
            }

            KeyEvent event = new KeyEvent(key[0], key[1], key[2]);
            DevInput.keyPress(Minecraft.getInstance().keyboardHandler, handle(), InputConstants.PRESS, event);
            this.text(piece);
            DevInput.keyPress(Minecraft.getInstance().keyboardHandler, handle(), InputConstants.RELEASE, event);
        });
    }

    /** {скан-код, код клавиши, модификаторы} для знака; {@code null} — на клавиатуре его нет. */
    private static int @Nullable [] physicalKey(int c)
    {
        int lower = Character.toLowerCase(c);
        int shift = lower != c ? InputConstants.MOD_SHIFT : 0;
        int ru = RU.indexOf(lower);

        if (ru >= 0)
        {
            lower = EN.charAt(ru);
        }

        if (lower >= 'a' && lower <= 'z')
        {
            return new int[]{InputConstants.KEY_A + lower - 'a', lower, shift};
        }

        if (lower >= '1' && lower <= '9')
        {
            return new int[]{InputConstants.KEY_1 + lower - '1', lower, 0};
        }

        return switch (lower)
        {
            case '0' -> new int[]{InputConstants.KEY_0, '0', 0};
            case '-' -> new int[]{InputConstants.KEY_MINUS, '-', 0};
            case '_' -> new int[]{InputConstants.KEY_MINUS, '-', InputConstants.MOD_SHIFT};
            case ' ' -> new int[]{InputConstants.KEY_SPACE, ' ', 0};
            case '.' -> new int[]{InputConstants.KEY_PERIOD, '.', 0};
            case '/' -> new int[]{InputConstants.KEY_SLASH, '/', 0};
            case ':' -> new int[]{InputConstants.KEY_SEMICOLON, ';', InputConstants.MOD_SHIFT};
            case ';' -> new int[]{InputConstants.KEY_SEMICOLON, ';', 0};
            case ',' -> new int[]{InputConstants.KEY_COMMA, ',', 0};
            case '\'' -> new int[]{InputConstants.KEY_APOSTROPHE, '\'', 0};
            case '[' -> new int[]{InputConstants.KEY_LBRACKET, '[', 0};
            case ']' -> new int[]{InputConstants.KEY_RBRACKET, ']', 0};
            default -> null;
        };
    }

    /** Событие текста SDL: сюда цикл событий отдаёт {@code SDL_EVENT_TEXT_INPUT}. */
    private void text(String value)
    {
        DevInput.textInput(Minecraft.getInstance().keyboardHandler, handle(), value);
    }

    /** Черновик IME: сюда цикл событий отдаёт {@code SDL_EVENT_TEXT_EDITING}. */
    private void preedit(String value)
    {
        Minecraft.getInstance().keyboardHandler.textEditing(handle(), PreeditEvent.fromSdlTextEditing(value, value.length(), 0));
    }

    // ===== Ввод текста: состояние =====

    private static TextInputManager manager()
    {
        return Minecraft.getInstance().textInputManager();
    }

    /** {@code TextInputManager.textInputEnabled} — закрытое поле, отражением (только харнесс). */
    private boolean inputOn()
    {
        return Boolean.TRUE.equals(this.read(manager(), "textInputEnabled"));
    }

    /** {@code TextInputManager.owner}: кто держит ввод текста. */
    private @Nullable Object owner()
    {
        return this.inputOn() ? this.read(manager(), "owner") : null;
    }

    /** Ответ самого SDL. */
    private boolean sdlActive()
    {
        return SDLKeyboard.SDL_TextInputActive(handle());
    }

    /** Область ввода текста SDL в пикселях окна: {x, y, w, h, курсор}; {@code null} — SDL не ответил. */
    private static int @Nullable [] sdlArea()
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            SDL_Rect.Buffer rect = SDL_Rect.malloc(1, stack);
            IntBuffer cursor = stack.mallocInt(1);

            if (! SDLKeyboard.SDL_GetTextInputArea(handle(), rect, cursor))
            {
                return null;
            }

            return new int[]{rect.x(), rect.y(), rect.w(), rect.h(), cursor.get(0)};
        }
    }

    private boolean areaInside(Widget widget)
    {
        return this.areaInsideBounds(new int[]{widget.x(), widget.y(), widget.w(), widget.h()});
    }

    /** Курсор, сообщённый SDL, лежит внутри прямоугольника поля (GUI → пиксели окна). */
    private boolean areaInsideBounds(int[] bounds)
    {
        int[] area = sdlArea();

        if (area == null)
        {
            return false;
        }

        Window window = Minecraft.getInstance().getWindow();
        double k = window.getGuiScale() / (double) window.getPixelDensity();
        double left = bounds[0] * k - 1.0;
        double right = (bounds[0] + bounds[2]) * k + 1.0;
        double top = bounds[1] * k - 1.0;
        double bottom = (bounds[1] + bounds[3]) * k + 1.0;

        return area[0] >= left && area[0] <= right && area[1] >= top && area[1] + area[3] <= bottom && area[3] > 0;
    }

    private String area()
    {
        int[] area = sdlArea();
        return area == null ? "SDL_GetTextInputArea не ответил" : "SDL: x " + area[0] + ", y " + area[1] + ", "
                + area[2] + "×" + area[3] + "; " + this.state();
    }

    private String state()
    {
        Object owner = this.read(manager(), "owner");
        return "ввод " + (this.inputOn() ? "вкл" : "выкл") + ", SDL " + (this.sdlActive() ? "вкл" : "выкл")
                + ", владелец " + describe(owner) + ", экран " + describe(Minecraft.getInstance().gui.screen());
    }

    private static String describe(@Nullable Object value)
    {
        if (value == null)
        {
            return "нет";
        }

        if (value instanceof TextInput input)
        {
            return "TextInput «" + input.text() + "»" + (input.focused() ? " (фокус)" : "");
        }

        return value.getClass().getSimpleName();
    }

    // ===== Экран «Схемы»: что где =====

    private Layers layers()
    {
        return Objects.requireNonNull(KProbe.fieldOfType(this.gui, Layers.class), "нет Layers");
    }

    private @Nullable Object filesPane()
    {
        return KProbe.fieldNamedType(this.gui, "FileListPane");
    }

    private TextInput search()
    {
        return Objects.requireNonNull(KProbe.fieldOfType(this.filesPane(), TextInput.class), "нет поиска файлов");
    }

    private TextInput materialsSearch()
    {
        Object pane = KProbe.fieldNamedType(this.gui, "MaterialsPane");
        return Objects.requireNonNull(KProbe.fieldOfType(pane, TextInput.class), "нет поиска материалов");
    }

    /** Имена файлов в списке (без строки «..» и папок). */
    private List<String> files()
    {
        ScrollList<?> list = KProbe.fieldOfType(this.filesPane(), ScrollList.class);
        List<String> names = new ArrayList<>();

        if (list != null)
        {
            for (Object item : list.items())
            {
                SchematicEntry entry = (SchematicEntry) KProbe.recordComponent(item, SchematicEntry.class);

                if (entry != null && ! entry.directory())
                {
                    names.add(entry.fileName());
                }
            }
        }

        return names;
    }

    private @Nullable SchematicEntry selected()
    {
        try
        {
            return this.gui == null ? null : (SchematicEntry) KProbe.call(this.gui, "selected");
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private String tab()
    {
        try
        {
            return this.gui == null ? "?" : String.valueOf(KProbe.call(this.gui, "tab"));
        }
        catch (ReflectiveOperationException e)
        {
            return e.toString();
        }
    }

    private void setTab(String tab)
    {
        try
        {
            KProbe.call(Objects.requireNonNull(this.gui), "setTab", KProbe.enumConstant(TAB_CLASS, tab));
        }
        catch (ReflectiveOperationException e)
        {
            this.fail("вкладка " + tab + ": " + e);
        }
    }

    private Button infoButton(String key)
    {
        return Objects.requireNonNull(KProbe.button(KProbe.fieldNamedType(this.gui, "InfoPane"), key), "нет кнопки " + key);
    }

    private Button settingsButton()
    {
        return (Button) Objects.requireNonNull(this.read(this.gui, "settings"), "нет шестерёнки");
    }

    @SuppressWarnings("unchecked")
    private List<MaterialLine> lines(String field)
    {
        Object value = this.read(KProbe.fieldNamedType(this.gui, "MaterialsPane"), field);
        return value instanceof List<?> list ? (List<MaterialLine>) list : List.of();
    }

    private boolean filtered(java.util.function.Predicate<MaterialLine> each)
    {
        List<MaterialLine> shown = this.lines("shown");
        return ! shown.isEmpty() && shown.size() < this.lines("lines").size() && shown.stream().allMatch(each);
    }

    private String linesState()
    {
        return "строк " + this.lines("shown").size() + " из " + this.lines("lines").size() + " "
                + this.lines("shown").stream().map(MaterialLine::name).toList() + "; " + this.state();
    }

    private String top()
    {
        Object top = this.layers().top();
        return "сверху " + describe(top) + "; " + this.state();
    }

    /** Поля окна в порядке строк ({@code Widget.textInput()} каждой строки). */
    private static List<TextInput> modalInputs(Modal modal)
    {
        List<TextInput> inputs = new ArrayList<>();
        Object rows = readStatic(modal, Modal.class, "rows");

        if (rows instanceof List<?> list)
        {
            for (Object row : list)
            {
                Widget widget = (Widget) KProbe.recordComponent(row, Widget.class);
                TextInput input = widget == null ? null : widget.textInput();

                if (input != null)
                {
                    inputs.add(input);
                }
            }
        }

        return inputs;
    }

    private TextInput exportName()
    {
        return Objects.requireNonNull(KProbe.fieldOfType(this.exportWindow, TextInput.class), "нет поля названия");
    }

    /** Поле ника — строка окна с {@code RosterField}. */
    private TextInput roster()
    {
        Object rows = readStatic(this.exportWindow, Modal.class, "rows");

        if (rows instanceof List<?> list)
        {
            for (Object row : list)
            {
                Widget widget = (Widget) KProbe.recordComponent(row, Widget.class);

                if (widget != null && widget.getClass().getSimpleName().equals("RosterField") && widget.textInput() != null)
                {
                    return widget.textInput();
                }
            }
        }

        throw new IllegalStateException("нет поля ника");
    }

    private TextInput settingsInput(int index)
    {
        return modalInputs(Objects.requireNonNull(this.settingsDialog)).get(index);
    }

    // ===== Редактор =====

    private int editingAxis()
    {
        return this.editor == null ? -2 : PlacementEditorScreen.TestHooks.editingAxis(this.editor);
    }

    private @Nullable Object editorField(int axis)
    {
        return this.editor == null ? null : PlacementEditorScreen.TestHooks.field(this.editor, axis);
    }

    private int[] fieldBounds(int axis)
    {
        return this.editor == null ? new int[4] : PlacementEditorScreen.TestHooks.fieldBounds(this.editor, axis);
    }

    private String placementState()
    {
        SchematicPlacement p = this.placement;
        return (p == null ? "нет размещения" : "origin " + p.getOrigin().toShortString() + " (было "
                + this.originBefore.toShortString() + ", ждали X " + this.targetX + "), поворот " + p.getRotation()
                + ", отражение " + p.getMirror()) + ", поле " + this.editingAxis() + "; " + this.state();
    }

    // ===== XTest =====

    /** Номер окна X11 у SDL; 0 — не X11. */
    private static long x11Window()
    {
        try
        {
            int props = SDLVideo.SDL_GetWindowProperties(handle());
            return props == 0 ? 0L : SDLProperties.SDL_GetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0L);
        }
        catch (RuntimeException | LinkageError e)
        {
            return 0L;
        }
    }

    /** Отдельный процесс жмёт клавиши через XTest (libXtst из ctypes) — шаг ждёт его конца. */
    private boolean runXTest(String text)
    {
        if (this.xtest == null)
        {
            try
            {
                Path script = this.out.resolve("xtest-type.py");
                Files.writeString(script, XTEST_SCRIPT, StandardCharsets.UTF_8);
                this.xtest = new ProcessBuilder("/usr/bin/python3", "-I", script.toString(), Long.toString(this.xWindow), text)
                        .redirectErrorStream(true).redirectOutput(this.out.resolve("xtest-" + text + ".log").toFile()).start();
            }
            catch (IOException e)
            {
                this.info("XTest: процесс не запустился: " + e);
                return true;
            }

            return false;
        }

        if (this.xtest.isAlive())
        {
            return false;
        }

        try
        {
            this.xtest.waitFor(1, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        this.info("XTest «" + text + "»: процесс вышел с кодом " + this.xtest.exitValue());
        this.xtest = null;
        return true;
    }

    private static final String XTEST_SCRIPT = """
            import ctypes, sys, time
            x11 = ctypes.CDLL("libX11.so.6")
            xtst = ctypes.CDLL("libXtst.so.6")
            x11.XOpenDisplay.restype = ctypes.c_void_p
            x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
            x11.XSetInputFocus.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
            x11.XStringToKeysym.restype = ctypes.c_ulong
            x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
            x11.XKeysymToKeycode.restype = ctypes.c_ubyte
            x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
            x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
            x11.XFlush.argtypes = [ctypes.c_void_p]
            x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
            xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
            d = x11.XOpenDisplay(None)
            if not d:
                sys.exit(2)
            win = int(sys.argv[1])
            if win:
                x11.XSetInputFocus(d, win, 2, 0)
                x11.XSync(d, 0)
                time.sleep(0.3)
            for ch in sys.argv[2]:
                kc = x11.XKeysymToKeycode(d, x11.XStringToKeysym(ch.encode()))
                if not kc:
                    sys.exit(3)
                xtst.XTestFakeKeyEvent(d, kc, 1, 0)
                x11.XFlush(d)
                time.sleep(0.04)
                xtst.XTestFakeKeyEvent(d, kc, 0, 0)
                x11.XFlush(d)
                time.sleep(0.04)
            x11.XSync(d, 0)
            x11.XCloseDisplay(d)
            print("typed", sys.argv[2])
            """;

    // ===== Отражение (только харнесс) =====

    private @Nullable Object read(@Nullable Object owner, String name)
    {
        return owner == null ? null : readStatic(owner, owner.getClass(), name);
    }

    /** Поле по имени от класса {@code from} вверх по иерархии. */
    private static @Nullable Object readStatic(@Nullable Object owner, Class<?> from, String name)
    {
        if (owner == null)
        {
            return null;
        }

        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass())
        {
            try
            {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                // выше по иерархии
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                return null;
            }
        }

        return null;
    }

    private int intField(Object owner, String name)
    {
        return this.read(owner, name) instanceof Integer value ? value : -1;
    }

    // ===== Отчёт =====

    void ok(String what)
    {
        ++this.ok;
        this.line("OK  ", what);
    }

    void fail(String what)
    {
        ++this.failed;
        this.line("FAIL", what);
    }

    void info(String what)
    {
        this.line("INFO", what);
    }

    boolean check(String what, boolean pass)
    {
        if (pass)
        {
            this.ok(what);
        }
        else
        {
            this.fail(what);
        }

        return pass;
    }

    private void line(String result, String what)
    {
        CovOut.log(TAG + result + " " + what);

        if (this.report != null)
        {
            try
            {
                this.report.write("\"" + what.replace("\"", "'") + "\"," + result.strip() + ",\n");
                this.report.flush();
            }
            catch (IOException e)
            {
                CovOut.log(TAG + "отчёт: " + e);
            }
        }
    }
}
