package com.aell.layerlist.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Приглашение в Party строкой чата — как точки Xaero: обычное сообщение с кодом, сервер о нём ничего не знает.
 * У кого мод есть, тот видит вместо него приглашение словами со ссылкой «Принять приглашение»; у кого нет —
 * слова ведущего и сам код: {@code [Material HUD] Приглашаю в Party: mhud-party:…}.
 *
 * <p>Код: {@code mhud-party:<версия>:<id>:<отпечаток списка>:<ник,ник,…>:<название>}. Только то, что не посчитать
 * у себя: состав по порядку номеров и отпечаток списка схемы. Сами доли каждый считает сам ({@link PartySplit}).
 * Название — последним: в нём могут быть любые знаки, а всё нужное для счёта стоит до него.
 */
public final class PartyCode
{
    /** Версия кода, которую понимает эта сборка; код новее — «обновите мод», без ссылки. */
    public static final int VERSION = 1;
    /** Сервер режет сообщение чата по этой длине. */
    public static final int CHAT_MAX = 256;
    public static final int NAME_MAX = 32;

    private static final String MARK = "mhud-party:";
    /**
     * Метка перед словами ведущего: по ней приглашение находит своё начало в сообщении, на каком бы языке слова ни
     * были (слова — на языке игры ведущего, строку приглашения каждый видит на своём).
     */
    private static final String TAG = "[Material HUD] ";
    /** Слов между меткой и кодом — не больше стольких знаков: дальше метка уже не от этого кода. */
    private static final int WORDS_MAX = 80;
    private static final String HEAD = "(?:" + Pattern.quote(TAG) + ".{0," + WORDS_MAX + "}?)?" + Pattern.quote(MARK);
    private static final String NICK = "[.*]?[A-Za-z0-9_]{1,16}";
    private static final Pattern CODE = Pattern.compile(HEAD
            + "(\\d{1,4}):([0-9a-z]{4,12}):([0-9a-f]{8}):(" + NICK + "(?:," + NICK + ")*):(.*)$", Pattern.DOTALL);
    private static final Pattern NEWER = Pattern.compile(HEAD + "(\\d{1,4}):.*$", Pattern.DOTALL);

    private PartyCode() {}

    /**
     * Приглашение из чата.
     *
     * @param id          идентификатор Party (у повторной рассылки тот же — состав обновляется)
     * @param fingerprint отпечаток списка схемы у ведущего ({@link PartySplit#fingerprint})
     * @param roster      ники по порядку номеров
     * @param name        название Party
     */
    public record Invite(String id, String fingerprint, List<String> roster, String name)
    {
        public Invite
        {
            roster = List.copyOf(roster);
        }
    }

    /**
     * Код, найденный в тексте сообщения.
     *
     * @param start  с какого знака текста начинается приглашение (метка и слова ведущего, если они есть, иначе код)
     * @param invite приглашение; {@code null} — код новее этой сборки или испорчен
     * @param newer  код от более новой версии мода
     */
    public record Found(int start, @Nullable Invite invite, boolean newer) {}

    /** Только код, без метки и слов: аргумент команды ссылки. */
    public static String code(Invite invite)
    {
        return MARK + VERSION + ':' + invite.id() + ':' + invite.fingerprint() + ':' + String.join(",", invite.roster())
                + ':' + cleanName(invite.name());
    }

    /**
     * Сообщение для чата: метка, слова ведущего и код; {@code null} — не влезает даже без названия (слишком много
     * участников). Не влезло целиком — сначала уходят слова, потом укорачивается название.
     *
     * @param words приглашение словами для игроков без мода («Приглашаю в Party:»)
     */
    public static @Nullable String message(Invite invite, String words)
    {
        String intro = TAG + cleanName(words).strip() + ' ';

        String name = cleanName(invite.name());
        String head = MARK + VERSION + ':' + invite.id() + ':' + invite.fingerprint() + ':' + String.join(",", invite.roster())
                + ':';

        if (head.length() > CHAT_MAX)
        {
            return null;
        }

        if (intro.length() - TAG.length() <= WORDS_MAX && intro.length() + head.length() + name.length() <= CHAT_MAX)
        {
            return intro + head + name;
        }

        return head + name.substring(0, Math.min(name.length(), CHAT_MAX - head.length())).strip();
    }

    /** Найти код в тексте сообщения чата; {@code null} — кода нет. */
    public static @Nullable Found find(String text)
    {
        if (text == null || ! text.contains(MARK))
        {
            return null;
        }

        Matcher code = CODE.matcher(text);

        if (code.find())
        {
            int version;

            try
            {
                version = Integer.parseInt(code.group(1));
            }
            catch (NumberFormatException e)
            {
                return null;
            }

            if (version > VERSION)
            {
                return new Found(code.start(), null, true);
            }

            List<String> roster = new ArrayList<>();
            List<String> seen = new ArrayList<>();

            for (String nick : code.group(4).split(","))
            {
                // Повтор ника сбил бы номера у всех, кто после него
                if (seen.contains(nick.toLowerCase(Locale.ROOT)))
                {
                    return new Found(code.start(), null, false);
                }

                seen.add(nick.toLowerCase(Locale.ROOT));
                roster.add(nick);
            }

            if (roster.size() > PartySplit.MAX_MEMBERS)
            {
                return new Found(code.start(), null, false);
            }

            return new Found(code.start(), new Invite(code.group(2), code.group(3), roster, cleanName(code.group(5))), false);
        }

        Matcher newer = NEWER.matcher(text);

        if (newer.find())
        {
            try
            {
                return new Found(newer.start(), null, Integer.parseInt(newer.group(1)) > VERSION);
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }

        return null;
    }

    /** Название для кода: одна строка, без знаков, которые чат не пропустит, не длиннее {@link #NAME_MAX}. */
    public static String cleanName(@Nullable String name)
    {
        if (name == null)
        {
            return "";
        }

        StringBuilder out = new StringBuilder(name.length());

        name.codePoints().forEach(point ->
        {
            // «§» и управляющие знаки сервер выбросит вместе с игроком
            if (point == '§' || point < 32 || point == 127)
            {
                out.append(' ');
            }
            else
            {
                out.appendCodePoint(point);
            }
        });

        String clean = out.toString().strip().replaceAll("\\s+", " ");

        if (clean.codePointCount(0, clean.length()) > NAME_MAX)
        {
            clean = clean.substring(0, clean.offsetByCodePoints(0, NAME_MAX)).strip();
        }

        return clean;
    }
}
