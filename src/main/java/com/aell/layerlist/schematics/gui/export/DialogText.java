package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Kit;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.language.I18n;

/**
 * Строки окна выгрузки — ключи {@code layerlist.schematics.export.dialog.*}; разделитель разрядов
 * — тот же, что у экрана «Схемы» ({@code layerlist.schematics.num_group}).
 */
@Environment(EnvType.CLIENT)
final class DialogText
{
    static final String PREFIX = "layerlist.schematics.export.dialog.";

    private DialogText() {}

    /** Строка без подстановок; одиночный «%» не ломается ({@code Strings.plain}). */
    static String t(String suffix)
    {
        return Strings.plain(PREFIX + suffix);
    }

    /** Строка с подстановками {@code %s}. */
    static String f(String suffix, Object... args)
    {
        return I18n.get(PREFIX + suffix, args);
    }

    static String num(long value)
    {
        return Kit.group(value, Strings.plain("layerlist.schematics.num_group"));
    }

    /** Путь для игрока: от папки игры, если файл в ней (короче и узнаваемо), иначе полный. */
    static String shortPath(Path file)
    {
        Path target = file.toAbsolutePath().normalize();

        try
        {
            Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();

            if (target.startsWith(game) && ! target.equals(game))
            {
                return game.relativize(target).toString();
            }
        }
        catch (RuntimeException e)
        {
            // Чужая файловая система или странный путь — покажем полный
        }

        return target.toString();
    }

    /** Причина ошибки для игрока: без обёрток {@code CompletionException}, иначе имя класса. */
    static String reason(Throwable error)
    {
        Throwable cause = error;

        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        String message = cause.getMessage();

        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
