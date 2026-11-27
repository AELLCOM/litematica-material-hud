package com.aell.layerlist.party;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Party — стройка одной схемы компанией: список материалов схемы делится на доли по номерам участников.
 *
 * @param id          идентификатор (у повторной рассылки тот же)
 * @param name        название — что видят в приглашении
 * @param roster      ники по порядку номеров: первый — №1
 * @param fingerprint отпечаток списка схемы у ведущего ({@link PartySplit#fingerprint}); по нему Party находит
 *                    свою схему у каждого участника
 * @param own         создана здесь: состав можно править; чужую — только принять заново
 * @param view        чью долю показывать: {@link #VIEW_MINE}, {@link #VIEW_ALL} или номер участника с нуля
 * @param file        файл схемы, у которой Party создана или к которой привязана (путь строкой); пусто — схема из
 *                    памяти или Party ещё не нашла схему
 * @param local       отпечаток списка своей схемы, к которой Party привязана вручную (у ведущего список другой);
 *                    пусто — привязки вручную нет. Схему без файла иначе не опознать
 * @param time        когда создана или принята, мс
 */
public record Party(String id, String name, List<String> roster, String fingerprint, boolean own, int view, String file,
                    String local, long time)
{
    /** Показывать свою долю (по своему нику в составе). */
    public static final int VIEW_MINE = - 1;
    /** Показывать весь список: делёж на паузе. */
    public static final int VIEW_ALL = - 2;

    public Party
    {
        Objects.requireNonNull(id, "id");
        name = name == null ? "" : name;
        roster = List.copyOf(roster);
        fingerprint = fingerprint == null ? "" : fingerprint;
        file = file == null ? "" : file;
        local = local == null ? "" : local;
    }

    /** Номер участника с ником {@code nick} с нуля (регистр не важен); нет в составе — {@code -1}. */
    public int indexOf(String nick)
    {
        String folded = nick.toLowerCase(Locale.ROOT);

        for (int i = 0; i < this.roster.size(); ++i)
        {
            if (this.roster.get(i).toLowerCase(Locale.ROOT).equals(folded))
            {
                return i;
            }
        }

        return - 1;
    }

    /**
     * Чья доля показывается, номером с нуля: своя — по нику {@code self}; {@code -1} — никакая (весь список,
     * либо своего ника в составе нет).
     */
    public int shownMember(String self)
    {
        if (this.view == VIEW_ALL)
        {
            return - 1;
        }

        if (this.view == VIEW_MINE)
        {
            return this.indexOf(self);
        }

        return this.view < this.roster.size() ? this.view : - 1;
    }

    public Party withName(String next)
    {
        return new Party(this.id, next, this.roster, this.fingerprint, this.own, this.view, this.file, this.local, this.time);
    }

    public Party withRoster(List<String> next)
    {
        // Показывали долю участника, которого больше нет, — назад к своей
        int shown = this.view >= next.size() ? VIEW_MINE : this.view;

        return new Party(this.id, this.name, next, this.fingerprint, this.own, shown, this.file, this.local, this.time);
    }

    public Party withView(int next)
    {
        return new Party(this.id, this.name, this.roster, this.fingerprint, this.own, next, this.file, this.local, this.time);
    }

    public Party withFingerprint(String next)
    {
        return new Party(this.id, this.name, this.roster, next, this.own, this.view, this.file, this.local, this.time);
    }

    /** Привязать к своей схеме: её файл и отпечаток её списка. */
    public Party withBinding(String nextFile, String nextLocal)
    {
        return new Party(this.id, this.name, this.roster, this.fingerprint, this.own, this.view, nextFile, nextLocal, this.time);
    }
}
