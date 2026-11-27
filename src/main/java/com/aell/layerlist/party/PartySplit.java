package com.aell.layerlist.party;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Делёж списка материалов схемы между участниками Party.
 *
 * <p>Единица делёжа — ячейка инвентаря: стак предмета (последний стак бывает неполным). Каждый предмет
 * раздаётся целыми стаками поровну; стаки, которые поровну не делятся, получают те, у кого сейчас занято меньше
 * ячеек (при равенстве — у кого меньше разных предметов и блоков). Мелочь, которой меньше стака на
 * человека, не дробится: 40 факелов достаются одному. Крупное идёт первым. Неполные стаки раздаются отдельным
 * проходом — тому, у кого блоков больше. Итог (замер на 9000 случайных списков, 2–8 участников): ячеек и разных
 * предметов у всех поровну с точностью до одной, блоков — в среднем до трети стака.
 *
 * <p>Расчёт зависит только от списка (ключ, количество, размер стака) и числа участников — у всех, у кого та
 * же схема, он один и тот же: по сети передавать нечего. Сверить списки можно по {@link #fingerprint}.
 *
 * <p>Доля любого другого количества того же предмета (осталось поставить, слой HUD) — {@link #part}: то же
 * отношение долей, а сумма по участникам в точности равна количеству.
 */
public final class PartySplit
{
    /** Не больше стольких участников: больше не влезет в строку чата. */
    public static final int MAX_MEMBERS = 10;

    /**
     * Строка списка схемы.
     *
     * @param key      ключ предмета ({@code ItemKeys.of})
     * @param total    сколько нужно на всю схему
     * @param maxStack размер стака, не меньше 1
     */
    public record Item(String key, long total, int maxStack)
    {
        public Item
        {
            maxStack = Math.max(1, maxStack);
        }
    }

    /**
     * Итог одного участника.
     *
     * @param slots  ячеек инвентаря (стаков, неполный — тоже ячейка)
     * @param blocks предметов, шт
     * @param kinds  разных предметов
     */
    public record Member(long slots, long blocks, int kinds) {}

    private final int size;
    /** Ключ → сколько кому на всю схему; сумма — количество предмета. */
    private final Map<String, long[]> amounts;
    private final List<Member> members;

    private PartySplit(int size, Map<String, long[]> amounts, List<Member> members)
    {
        this.size = size;
        this.amounts = amounts;
        this.members = members;
    }

    /** Неполный стак предмета: сколько в нём не хватает до полного и кто держит стаки этого предмета. */
    private record Partial(long deficit, String key, long[] share, long[] got) {}

    /** Разделить {@code items} на {@code members} участников (не меньше одного). Повторы ключа складываются. */
    public static PartySplit of(Collection<Item> items, int members)
    {
        int n = Math.max(1, members);
        List<Item> sorted = merged(items);
        // Крупное — первым: остатки мелочи потом выравнивают то, что не поделилось
        sorted.sort(Comparator.comparingLong(PartySplit::units).reversed()
                .thenComparing(Comparator.comparingLong(Item::total).reversed())
                .thenComparing(Item::key));

        long[] slots = new long[n];
        long[] blocks = new long[n];
        int[] kinds = new int[n];
        Map<String, long[]> amounts = new HashMap<>();
        List<Partial> partials = new ArrayList<>();
        // Мелочь (меньше стака на человека) добавляет получателю новый предмет — сначала тем, у кого их меньше
        Comparator<Integer> bySlots = Comparator.comparingLong(i -> slots[i]);
        Comparator<Integer> small = bySlots.thenComparingInt(i -> kinds[i]).thenComparingLong(i -> blocks[i])
                .thenComparingInt(i -> i);
        Comparator<Integer> big = bySlots.thenComparingLong(i -> blocks[i]).thenComparingInt(i -> i);

        // Проход 1: целые стаки. Неполный стак пока считается полным — кому он достанется, решит проход 2
        for (Item item : sorted)
        {
            long stack = item.maxStack();
            long rest = item.total() % stack;
            long units = units(item);
            long each = units / n;
            int extra = (int) (units % n);
            long[] got = new long[n];

            for (int i = 0; i < n; ++i)
            {
                got[i] = each;
            }

            // Стаки сверх «поровну» — тем, у кого сейчас меньше занято
            if (extra > 0)
            {
                List<Integer> order = new ArrayList<>(n);

                for (int i = 0; i < n; ++i)
                {
                    order.add(i);
                }

                order.sort(each == 0L ? small : big);

                for (int i = 0; i < extra; ++i)
                {
                    ++got[order.get(i)];
                }
            }

            long[] share = new long[n];

            for (int i = 0; i < n; ++i)
            {
                share[i] = got[i] * stack;
                slots[i] += got[i];
                blocks[i] += share[i];
                kinds[i] += got[i] > 0 ? 1 : 0;
            }

            amounts.put(item.key(), share);

            if (rest > 0L)
            {
                partials.add(new Partial(stack - rest, item.key(), share, got));
            }
        }

        // Проход 2: неполные стаки, самые «пустые» первыми, — тому из держателей предмета, у кого блоков больше
        // всех. Ячеек и видов это не меняет, а блоки выравнивает втрое лучше, чем решать по одному предмету
        partials.sort(Comparator.comparingLong(Partial::deficit).reversed().thenComparing(Partial::key));

        for (Partial partial : partials)
        {
            int holder = - 1;

            for (int i = 0; i < n; ++i)
            {
                if (partial.got()[i] > 0L && (holder < 0 || blocks[i] > blocks[holder]))
                {
                    holder = i;
                }
            }

            if (holder >= 0)
            {
                partial.share()[holder] -= partial.deficit();
                blocks[holder] -= partial.deficit();
            }
        }

        List<Member> out = new ArrayList<>(n);

        for (int i = 0; i < n; ++i)
        {
            out.add(new Member(slots[i], blocks[i], kinds[i]));
        }

        return new PartySplit(n, amounts, List.copyOf(out));
    }

    /** Сколько участников. */
    public int size()
    {
        return this.size;
    }

    /** Итог участника {@code member} (с нуля). */
    public Member member(int member)
    {
        return this.members.get(member);
    }

    /** Предмет есть в списке, по которому делили. */
    public boolean knows(String key)
    {
        return this.amounts.containsKey(key);
    }

    /** Сколько предмета {@code key} участнику {@code member} на всю схему; предмета в списке нет — 0. */
    public long amount(String key, int member)
    {
        long[] share = this.amounts.get(key);

        return share == null || member < 0 || member >= share.length ? 0L : share[member];
    }

    /**
     * Доля участника в количестве {@code quantity} того же предмета — в том же отношении, что доли на всю схему;
     * сумма по всем участникам равна {@code quantity}. Предмета в списке нет — количество не делится
     * (возвращается целиком): чужая схема в том же списке HUD не должна пропасть.
     */
    public long part(String key, long quantity, int member)
    {
        long[] share = this.amounts.get(key);

        if (share == null || quantity <= 0L || member < 0 || member >= share.length)
        {
            return share == null ? Math.max(0L, quantity) : 0L;
        }

        long before = 0L;

        for (int i = 0; i < member; ++i)
        {
            before += share[i];
        }

        long through = before + share[member];
        long total = through;

        for (int i = member + 1; i < share.length; ++i)
        {
            total += share[i];
        }

        if (total <= 0L)
        {
            return 0L;
        }

        return scaled(quantity, through, total) - scaled(quantity, before, total);
    }

    /** {@code floor(quantity * part / total)} без переполнения. */
    private static long scaled(long quantity, long part, long total)
    {
        if (part <= 0L)
        {
            return 0L;
        }

        if (Math.multiplyHigh(quantity, part) == 0L && quantity * part >= 0L)
        {
            return quantity * part / total;
        }

        return BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(part)).divide(BigInteger.valueOf(total)).longValue();
    }

    /**
     * Отпечаток списка: восемь шестнадцатеричных знаков. У одинаковых списков одинаков, порядок строк не важен.
     * По нему приглашение находит свою схему у участника и замечает, что списки разошлись.
     */
    public static String fingerprint(Collection<Item> items)
    {
        Map<String, Item> byKey = new TreeMap<>();
        merged(items).forEach(item -> byKey.put(item.key(), item));
        CRC32 crc = new CRC32();

        for (Item item : byKey.values())
        {
            crc.update((item.key() + '=' + item.total() + '/' + item.maxStack() + '\n').getBytes(StandardCharsets.UTF_8));
        }

        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    /** Стаков (ячеек) у предмета: неполный — тоже ячейка. */
    private static long units(Item item)
    {
        return item.total() / item.maxStack() + (item.total() % item.maxStack() > 0L ? 1L : 0L);
    }

    private static List<Item> merged(Collection<Item> items)
    {
        Map<String, Item> byKey = new HashMap<>();

        for (Item item : items)
        {
            if (item.total() <= 0L)
            {
                continue;
            }

            byKey.merge(item.key(), item, (a, b) -> new Item(a.key(), a.total() + b.total(), a.maxStack()));
        }

        return new ArrayList<>(byKey.values());
    }
}
