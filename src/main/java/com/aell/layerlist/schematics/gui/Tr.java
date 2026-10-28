package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Kit;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Строки экрана «Схемы»: все — из ключей {@code layerlist.schematics.*}, форматы дат и
 * разделитель разрядов — тоже из перевода (в русском «03.10.2026» и «12 345», в английском своё).
 */
@Environment(EnvType.CLIENT)
final class Tr
{
    private static final String PREFIX = "layerlist.schematics.";

    private Tr() {}

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
        return Kit.group(value, t("num_group"));
    }

    /** Размер файла: «512 Б», «48 КБ», «3,4 МБ» (разделители — из перевода). */
    static String size(long bytes)
    {
        if (bytes < 1024L)
        {
            return f("size.bytes", num(Math.max(0L, bytes)));
        }

        if (bytes < 1024L * 1024L)
        {
            return f("size.kb", num((bytes + 1023L) / 1024L));
        }

        double mb = bytes / (1024.0 * 1024.0);
        long tenths = Math.round(mb * 10.0);
        String text = tenths >= 1000L ? num(Math.round(mb)) : (tenths / 10L) + t("size.decimal") + (tenths % 10L);

        return f("size.mb", text);
    }

    static String date(long millis)
    {
        return format(millis, "date_format");
    }

    static String dateTime(long millis)
    {
        return format(millis, "datetime_format");
    }

    private static String format(long millis, String patternKey)
    {
        if (millis <= 0L)
        {
            return t("info.unknown");
        }

        try
        {
            return DateTimeFormatter.ofPattern(t(patternKey)).withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(millis));
        }
        catch (RuntimeException e)
        {
            // Кривой шаблон в чужом переводе не должен ронять экран — ISO читается везде
            return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
        }
    }

    static String rotation(Rotation rotation)
    {
        return switch (rotation)
        {
            case CLOCKWISE_90 -> t("rotation.cw90");
            case CLOCKWISE_180 -> t("rotation.cw180");
            case COUNTERCLOCKWISE_90 -> t("rotation.ccw90");
            default -> t("rotation.none");
        };
    }

    static String mirror(Mirror mirror)
    {
        return switch (mirror)
        {
            case FRONT_BACK -> t("mirror.front_back");
            case LEFT_RIGHT -> t("mirror.left_right");
            default -> "";
        };
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
