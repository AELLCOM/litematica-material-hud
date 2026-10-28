package com.aell.layerlist.schematics.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;

/**
 * Тексты выгрузок: всё, что видит человек, — через ключи {@code layerlist.schematics.export.*}
 * текущего языка игры; числа — с разделителем тысяч из того же перевода.
 */
@Environment(EnvType.CLIENT)
final class ExportText
{
    static final String PREFIX = "layerlist.schematics.export.";

    private ExportText() {}

    static String t(String key, Object... args)
    {
        return I18n.get(PREFIX + key, args);
    }

    /** Без кодов форматирования Minecraft ({@code §c}): в файлах и чатах они видны как мусор. */
    static String plain(String text)
    {
        if (text.indexOf('§') < 0)
        {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            if (c == '§' && i + 1 < text.length())
            {
                i++;
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    /** «96 077»: разделитель тысяч — из перевода (в русском — неразрывный пробел). */
    static String count(long value)
    {
        String plain = Long.toString(Math.abs(value));
        String separator = t("thousands");
        StringBuilder out = new StringBuilder(plain.length() + plain.length() / 3);

        for (int i = 0; i < plain.length(); i++)
        {
            if (i > 0 && (plain.length() - i) % 3 == 0)
            {
                out.append(separator);
            }

            out.append(plain.charAt(i));
        }

        return value < 0 ? "-" + out : out.toString();
    }

    /** Дробь с одним знаком; десятичный знак — из перевода («60,2» / «60.2»). */
    static String decimal(double value)
    {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);

        return rounded.toPlainString().replace(".", t("decimal"));
    }

    static String date(LocalDateTime time)
    {
        try
        {
            return time.format(DateTimeFormatter.ofPattern(t("date"), Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            // Битый шаблон в переводе не должен ломать выгрузку
            return time.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }

    /** Строка итогов: «516 поз. · 96 077 бл. · ≈ 60,2 шалк.». */
    static String summary(ExportData data)
    {
        return t("summary", count(data.lines().size()), count(data.blocks()), decimal(data.shulkers()));
    }

    /** Подпись «Material HUD · дата · источник [· только недостающее]». */
    static String meta(ExportData data)
    {
        String base = t("meta", date(data.created()), plain(data.request().source()));

        return data.request().options().onlyMissing() ? base + t("meta.missing") : base;
    }

    static String statusTaking()
    {
        return t("status.taking");
    }

    static String statusDone()
    {
        return t("status.done");
    }
}
