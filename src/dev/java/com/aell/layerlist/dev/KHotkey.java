package com.aell.layerlist.dev;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Nav;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.input.KeyCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;

/**
 * Режим «k», 0.0.27: клавиша «Открыть меню «Схемы»» ({@code openSchematics}). По умолчанию не назначена,
 * на вкладке «Клавиши» — первой строкой раздела «Экраны» и видна целиком (снимок), действует в игре
 * (контекст malilib INGAME): назначили F8 программно, нажали F8 событием в очереди SDL ({@code SDL_PushEvent}
 * {@code SDL_EVENT_KEY_DOWN/UP}) → открылся экран «Схемы»; поверх другого экрана — не открывается. Под X11 с
 * {@code python3} — ещё и настоящей клавишей X-сервера (XTest). Назначение потом возвращается как было.
 *
 * <p>Почему не {@code KeyboardHandler.keyPress}, как у полей ввода: malilib 0.30 (26.3) узнаёт зажатые клавиши
 * только из сырого события SDL (свой миксин на {@code SDLEventHandler.handleKeyEvent}, HEAD →
 * {@code KeybindMulti.onKeyInputPre}), а срабатывает уже в {@code keyPress}. Вызов одного {@code keyPress} хоткей
 * не нажимает (проверено: первый прогон — FAIL), игрок же всегда идёт через очередь SDL.
 */
@Environment(EnvType.CLIENT)
final class KHotkey
{
    private static final String KEY_NAME = "F8";
    private static final int SCANCODE_MASK = 1 << 30;

    private final DumpSchematics k;
    private @Nullable String before;
    private long pressedAt;
    private boolean endPressed;
    private @Nullable Process xtest;

    KHotkey(DumpSchematics k)
    {
        this.k = k;
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "клавиша «Схемы»: по умолчанию пусто, в игре", 3_000L, mc -> this.defaults()));
        s.add(step(p + "настройки, вкладка «Клавиши»", 5_000L, mc -> this.openKeysTab(mc)));
        s.add(step(p + "строка «Открыть меню «Схемы»» на экране", 5_000L, this.k.settleThen(400L, mc -> this.row(mc, false))));
        s.add(step(p + "снимок: вкладка «Клавиши»", 5_000L, KTrash.cleanShot(this.k, p + "keys-tab")));
        s.add(step(p + "назначить " + KEY_NAME, 3_000L, mc -> this.bind()));
        s.add(step(p + "строка показывает " + KEY_NAME, 5_000L, this.k.settleThen(300L, mc -> this.row(mc, true))));
        s.add(step(p + "снимок: «Схемы» на " + KEY_NAME, 5_000L, KTrash.cleanShot(this.k, p + "keys-tab-bound")));
        s.add(step(p + KEY_NAME + " поверх настроек «Схемы» не открывает", 3_000L, mc -> this.pressOverScreen()));
        s.add(step(p + "закрыть все экраны", 3_000L, mc -> this.closeAll()));
        s.add(step(p + KEY_NAME + " в игре → экран «Схемы»", 5_000L, this.k.settleThen(400L, mc -> this.pressInGame())));
        s.add(step(p + "XTest: закрыть все экраны", 3_000L, mc -> this.closeAll()));
        s.add(step(p + "XTest: " + KEY_NAME + " клавишей X-сервера → «Схемы»", 15_000L, this.k.settleThen(400L, mc -> this.xtestInGame())));
        s.add(step(p + "клавиша — как была", 3_000L, mc -> this.restore()));
        return s;
    }

    void cleanup()
    {
        if (this.xtest != null && this.xtest.isAlive())
        {
            this.xtest.destroyForcibly();
        }

        this.restore();
    }

    private static IKeybind keybind()
    {
        return SchematicsConfig.OPEN_SCHEMATICS.getKeybind();
    }

    private boolean defaults()
    {
        if (this.before == null)
        {
            this.before = SchematicsConfig.OPEN_SCHEMATICS.getStringValue();
        }

        this.k.check("по умолчанию клавиши нет", "", SchematicsConfig.OPEN_SCHEMATICS.getDefaultStringValue());
        this.k.check("действует в игре (контекст malilib)", KeybindSettings.Context.INGAME, keybind().getSettings().getContext());
        this.k.check("имя строки", "Открыть меню «Схемы»".equals(I18n.get("layerlist.config.openSchematics.name"))
                || "Open the Schematics menu".equals(I18n.get("layerlist.config.openSchematics.name")));
        return true;
    }

    private boolean openKeysTab(Minecraft mc)
    {
        Nav.clear();
        mc.gui.setScreen(GuiConfigs.create(null, null).showTab("layerlist.gui.tab_hotkeys"));
        this.k.check("открыты настройки", GuiUtils.getCurrentScreen() instanceof GuiConfigs);
        return true;
    }

    /** Строка клавиши в экране настроек: первая в «Экранах», на экране целиком (иначе End и ещё раз). */
    private boolean row(Minecraft mc, boolean bound)
    {
        Screen screen = GuiUtils.getCurrentScreen();
        List<?> rows = KTrash.field(screen, "rows");

        if (rows == null)
        {
            this.k.fail("настройки: нет строк");
            return true;
        }

        Object mine = null;
        Model.Section section = null;
        Model.Section current = null;

        for (Object row : rows)
        {
            Model.Section rowSection = KTrash.field(row, "section");
            Model.Option option = KTrash.field(row, "option");

            if (option == null && rowSection != null)
            {
                current = rowSection;
            }

            if (option != null && option.keybind == keybind())
            {
                mine = row;
                section = current;
            }
        }

        if (! this.k.check("строка клавиши «Схемы» на вкладке «Клавиши»", mine != null))
        {
            return true;
        }

        this.k.check("раздел — «Экраны»", "layerlist.section.screen_keys", section == null ? "—" : section.key);
        this.k.check("первая в разделе", "openSchematics", section == null || section.options.isEmpty() ? "—"
                : section.options.getFirst().key);

        Integer y = KTrash.field(mine, "y");
        Integer h = KTrash.field(mine, "h");
        Integer scroll = KTrash.field(screen, "scroll");
        Integer bodyY = KTrash.field(screen, "bodyY");
        Integer bodyH = KTrash.field(screen, "bodyH");

        if (y == null || h == null || scroll == null || bodyY == null || bodyH == null)
        {
            this.k.fail("настройки: нет полей раскладки");
            return true;
        }

        int top = bodyY + y - scroll;
        boolean visible = top >= bodyY && top + h <= bodyY + bodyH;

        if (! visible && ! this.endPressed)
        {
            // Строка ниже края — End, как прокрутил бы игрок, и проверка снова на следующем опросе
            this.endPressed = true;
            KTrash.realKey(InputConstants.KEY_END);
            this.k.info("строка «Схемы» ниже края — End");
            return false;
        }

        this.endPressed = false;
        this.k.check("строка «Схемы» видна целиком", visible);

        if (bound)
        {
            this.k.check("в строке клавиша " + KEY_NAME, KEY_NAME, keybind().getKeysDisplayString());
        }

        return true;
    }

    private boolean bind()
    {
        // Имя клавиши malilib переводит в код клавиши SDL (со знаком маски), а хранит скан-код
        this.k.check("имя " + KEY_NAME + " у malilib — код клавиши SDL F8", InputConstants.KEY_F8 | SCANCODE_MASK,
                KeyCodes.getKeyCodeFromName(KEY_NAME));
        keybind().setValueFromString(KEY_NAME);
        InputEventHandler.getKeybindManager().updateUsedKeys();
        this.k.check("назначено " + KEY_NAME, List.of(InputConstants.KEY_F8), keybind().getKeys());
        return true;
    }

    /** Нажатие и отпускание в очередь событий SDL — дальше как от клавиатуры: насос событий игры, malilib, ваниль. */
    private static void pushKey(int scancode)
    {
        long window = Minecraft.getInstance().getWindow().handle();
        int windowId = SDLVideo.SDL_GetWindowID(window);

        for (boolean down : new boolean[] {true, false})
        {
            SDL_Event event = SDL_Event.calloc();

            try
            {
                event.key(key -> key.type(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP)
                        .windowID(windowId).scancode(scancode).key(scancode | SCANCODE_MASK).down(down).repeat(false));
                SDLEvents.SDL_PushEvent(event);
            }
            finally
            {
                event.free();
            }
        }
    }

    private boolean pressOverScreen()
    {
        if (this.pressedAt == 0L)
        {
            this.pressedAt = Util.getMillis();
            pushKey(InputConstants.KEY_F8);
            return false;
        }

        // Событие обработается в ближайшем кадре — даём ему полсекунды
        if (Util.getMillis() - this.pressedAt < 500L)
        {
            return false;
        }

        this.pressedAt = 0L;
        this.k.check(KEY_NAME + " поверх настроек: экран тот же", GuiUtils.getCurrentScreen() instanceof GuiConfigs);
        return true;
    }

    private boolean closeAll()
    {
        Nav.clear();
        GuiBase.openGui(null);
        this.k.check("экранов нет", GuiUtils.getCurrentScreen() == null);
        return true;
    }

    private boolean pressInGame()
    {
        if (this.pressedAt == 0L)
        {
            this.pressedAt = Util.getMillis();
            pushKey(InputConstants.KEY_F8);
        }

        if (! (GuiUtils.getCurrentScreen() instanceof GuiSchematics) && Util.getMillis() - this.pressedAt < 2_000L)
        {
            return false;
        }

        this.pressedAt = 0L;
        this.k.check(KEY_NAME + " в игре (событие SDL) открыла экран «Схемы»", GuiUtils.getCurrentScreen() instanceof GuiSchematics);
        this.k.check("родитель — игра: «Закрыть», а не «Назад»", Nav.peek() == null);

        // Не открылось — открыть самим, чтобы дальше шло как обычно
        if (! (GuiUtils.getCurrentScreen() instanceof GuiSchematics))
        {
            GuiSchematics.open(null);
        }

        return true;
    }

    /** Номер окна X11 у SDL; 0 — не X11. */
    private static long x11Window()
    {
        try
        {
            int props = SDLVideo.SDL_GetWindowProperties(Minecraft.getInstance().getWindow().handle());
            return props == 0 ? 0L : SDLProperties.SDL_GetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0L);
        }
        catch (RuntimeException | LinkageError e)
        {
            return 0L;
        }
    }

    /** F8 настоящей клавишей X-сервера (XTest из {@code python3}): SDL видит её как с клавиатуры игрока. */
    private boolean xtestInGame()
    {
        if (this.xtest == null && this.pressedAt == 0L)
        {
            long window = x11Window();

            if (System.getenv("DISPLAY") == null || window == 0L || ! Files.isExecutable(Path.of("/usr/bin/python3")))
            {
                this.k.info("XTest: не X11 или нет python3 — проверка клавишей X-сервера пропущена");
                return this.reopenIfClosed();
            }

            try
            {
                Path script = this.k.outDir().resolve("xtest-key.py");
                Files.writeString(script, XTEST_SCRIPT, StandardCharsets.UTF_8);
                this.xtest = new ProcessBuilder("/usr/bin/python3", "-I", script.toString(), Long.toString(window), KEY_NAME)
                        .redirectErrorStream(true).redirectOutput(this.k.outDir().resolve("xtest-" + KEY_NAME + ".log").toFile())
                        .start();
                this.pressedAt = Util.getMillis();
            }
            catch (IOException e)
            {
                this.k.info("XTest: процесс не запустился: " + e);
                return this.reopenIfClosed();
            }

            return false;
        }

        if (this.xtest != null && this.xtest.isAlive())
        {
            return false;
        }

        if (this.xtest != null)
        {
            try
            {
                this.xtest.waitFor(1, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            this.k.info("XTest " + KEY_NAME + ": процесс вышел с кодом " + this.xtest.exitValue());
            this.xtest = null;
        }

        if (! (GuiUtils.getCurrentScreen() instanceof GuiSchematics) && Util.getMillis() - this.pressedAt < 3_000L)
        {
            return false;
        }

        this.pressedAt = 0L;
        this.k.check("XTest: " + KEY_NAME + " клавишей X-сервера открыла экран «Схемы»",
                GuiUtils.getCurrentScreen() instanceof GuiSchematics);
        return this.reopenIfClosed();
    }

    private boolean reopenIfClosed()
    {
        if (! (GuiUtils.getCurrentScreen() instanceof GuiSchematics))
        {
            GuiSchematics.open(null);
        }

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
            kc = x11.XKeysymToKeycode(d, x11.XStringToKeysym(sys.argv[2].encode()))
            if not kc:
                sys.exit(3)
            xtst.XTestFakeKeyEvent(d, kc, 1, 0)
            x11.XFlush(d)
            time.sleep(0.08)
            xtst.XTestFakeKeyEvent(d, kc, 0, 0)
            x11.XSync(d, 0)
            x11.XCloseDisplay(d)
            print("pressed", sys.argv[2])
            """;

    private boolean restore()
    {
        if (this.before != null)
        {
            SchematicsConfig.OPEN_SCHEMATICS.setValueFromString(this.before);
            InputEventHandler.getKeybindManager().updateUsedKeys();
            this.k.check("клавиша «Схемы» вернулась к прежней", this.before, SchematicsConfig.OPEN_SCHEMATICS.getStringValue());
            this.before = null;
        }

        return true;
    }
}
