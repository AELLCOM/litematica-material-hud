package com.aell.layerlist.schematics.gui.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Поиск и фильтр таблицы «Материалы». Запрос делится на слова, строка подходит, если каждое слово
 * есть в её названии или ключе (без регистра): «дуб ступ» найдёт «Дубовые ступени»,
 * «minecraft:oak» — все дубовые предметы. «Только недостающее» — строки, которых ещё нет в мире (список для
 * сбора: склад не вычитается). «С учётом склада» (и без «Только недостающего») — только то, чего не хватает и
 * после инвентаря и сундуков.
 * «Изменённые» — заменённые и отключённые в схеме блоки.
 *
 * @param words       слова запроса в нижнем регистре; пусто — без поиска
 * @param onlyMissing оставлять только строки с нехваткой
 * @param onlyChanged оставлять только заменённые и отключённые блоки
 * @param useStock    оставлять только то, чего не хватает и после инвентаря и сундуков
 */
@Environment(EnvType.CLIENT)
public record MaterialFilter(List<String> words, boolean onlyMissing, boolean onlyChanged, boolean useStock)
{
    public MaterialFilter(List<String> words, boolean onlyMissing, boolean onlyChanged)
    {
        this(words, onlyMissing, onlyChanged, false);
    }

    public MaterialFilter
    {
        words = List.copyOf(words);
    }

    public static MaterialFilter of(String query, boolean onlyMissing)
    {
        return of(query, onlyMissing, false);
    }

    public static MaterialFilter of(String query, boolean onlyMissing, boolean onlyChanged)
    {
        return of(query, onlyMissing, onlyChanged, false);
    }

    public static MaterialFilter of(String query, boolean onlyMissing, boolean onlyChanged, boolean useStock)
    {
        List<String> words = new ArrayList<>();

        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
        {
            if (! word.isEmpty())
            {
                words.add(word);
            }
        }

        return new MaterialFilter(words, onlyMissing, onlyChanged, useStock);
    }

    /** Отбирает хоть что-то (иначе таблица показывает всё). */
    public boolean active()
    {
        return this.onlyMissing || this.useStock || this.onlyChanged || ! this.words.isEmpty();
    }

    public boolean test(MaterialLine line)
    {
        // Отключённая строка — по своим настоящим числам: где стояла включённой, там и стоит
        // Список для сбора: склад не вычитаем — строка уходит, только когда блоки уже стоят в мире
        if (this.onlyMissing && line.lack() <= 0L || this.useStock && line.shortage() <= 0L)
        {
            return false;
        }

        if (this.onlyChanged && ! line.changed())
        {
            return false;
        }

        for (String word : this.words)
        {
            if (! line.haystack().contains(word))
            {
                return false;
            }
        }

        return true;
    }

    /** Подходящие строки в исходном порядке. */
    public List<MaterialLine> apply(List<MaterialLine> lines)
    {
        if (! this.active())
        {
            return lines;
        }

        List<MaterialLine> out = new ArrayList<>(lines.size());

        for (MaterialLine line : lines)
        {
            if (this.test(line))
            {
                out.add(line);
            }
        }

        return out;
    }

    /**
     * Итоги для подвала.
     *
     * @param positions строк
     * @param blocks    сумма «нужно», шт
     * @param shulkers  шалкеровых ящиков на всё (дробно, как колонка шалкеров таблицы сбора)
     * @param shortage  сумма «не хватает», шт
     */
    public record Totals(int positions, long blocks, double shulkers, long shortage)
    {
        public static final Totals EMPTY = new Totals(0, 0L, 0.0D, 0L);

        public static Totals of(List<MaterialLine> lines)
        {
            return of(lines, false);
        }

        /** {@code byLack} — блоки и шалкеры по недостающему ({@code lack}), а не по всему счёту строки. */
        public static Totals of(List<MaterialLine> lines, boolean byLack)
        {
            long blocks = 0L;
            double shulkers = 0.0D;
            long shortage = 0L;

            int positions = 0;

            for (MaterialLine line : lines)
            {
                // Отключённый блок — не позиция стройки
                if (line.disabled())
                {
                    continue;
                }

                ++positions;
                blocks = saturatedSum(blocks, byLack ? line.lack() : line.need());
                shulkers += byLack ? line.lackShulkers() : line.shulkers();
                shortage = saturatedSum(shortage, line.shortage());
            }

            return new Totals(positions, blocks, shulkers, shortage);
        }

        private static long saturatedSum(long a, long b)
        {
            long sum = a + b;
            return sum < 0L ? Long.MAX_VALUE : sum;
        }
    }
}
