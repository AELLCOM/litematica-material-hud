package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.schematics.export.Exporters;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Открыть в системе ссылку, файл или папку — из окна выгрузки, тоста её итога и кнопки «Папка с
 * выгрузками» (0.0.27). Само открытие — ядро M26 ({@link Exporters#browse}, {@link Exporters#reveal},
 * {@link Exporters#openFolder}); здесь только узкий вход харнесса: настоящий клик по «Показать в папке»
 * не должен открывать проводник на машине, где идёт прогон.
 */
@Environment(EnvType.CLIENT)
public final class SystemOpen
{
    /** Перехват харнесса: вместо открытия — запись «что открыли бы»; {@code null} — открывать по-настоящему. */
    private static @Nullable Consumer<String> spy;

    private SystemOpen() {}

    static void browse(URI uri)
    {
        Consumer<String> hook = spy;

        if (hook != null)
        {
            hook.accept("browse " + uri);
            return;
        }

        Exporters.browse(uri);
    }

    static void reveal(Path file)
    {
        Consumer<String> hook = spy;

        if (hook != null)
        {
            hook.accept("reveal " + file.toAbsolutePath().normalize());
            return;
        }

        Exporters.reveal(file);
    }

    static void openFolder(Path dir)
    {
        Consumer<String> hook = spy;

        if (hook != null)
        {
            hook.accept("folder " + dir.toAbsolutePath().normalize());
            return;
        }

        Exporters.openFolder(dir);
    }

    /** Только для харнесса (режим k). */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Записывать открытия в {@code calls} вместо настоящих; {@code null} — снова по-настоящему. */
        public static void intercept(@Nullable Consumer<String> calls)
        {
            spy = calls;
        }
    }
}
