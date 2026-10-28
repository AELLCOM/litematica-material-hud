package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.materials.MaterialRow;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;

/**
 * Строка таблицы «Материалы» с уже посчитанными числами и подписями. Таблица рисует только видимые
 * строки, но каждый кадр; числа с разрядами и разбивка считаются один раз при сборке строки, а не
 * при каждой отрисовке — так и на тысячах позиций кадр не тратится на форматирование.
 *
 * <p>«Нужно» ({@link #need()}) — то, что стоит в колонке «Всего»: у схемы это весь счёт, у списка HUD —
 * сколько ещё не поставлено в мире (как сам HUD). «Не хватает» — нужное за вычетом того, что уже
 * есть в инвентаре и в известных сундуках.
 *
 * <p>Строка схемы, размещённой в мире и сверенной с ним ({@link #verified()}, 1.0.18): «Всего» — вся постройка,
 * а «нужное» для «Не хватает» и фильтра «Только недостающее» — то, что ещё не поставлено ({@link #lack()}).
 * У остальных строк {@link #lack()} совпадает с {@link #need()}.
 *
 * <p>Строка блока, заменённого в схеме (0.0.27), помнит названия исходных блоков ({@link #replacedFrom()}):
 * таблица пишет «Еловые доски ← Дубовые доски», поиск находит её и по исходному названию.
 *
 * <p>Строка блока, отключённого на слоях (1.0.12), помнит номера этих слоёв ({@link #layersOff()}, 1 — нижний):
 * таблица пишет «· откл. на слоях 3, 5», числа строки — уже без этих клеток.
 *
 * <p>Строка отключённого блока ({@link #disabled()}) стоит там же, где стояла бы включённой: числа у неё
 * настоящие (по ним она сортируется), но в итоги, «не хватает» подвала и выгрузку она не идёт.
 */
@Environment(EnvType.CLIENT)
public final class MaterialLine
{
    /** По названию без регистра, при равенстве — по ключу (баннеры с разными узорами). */
    public static final Comparator<MaterialLine> BY_NAME = Comparator.comparing(MaterialLine::sortName)
            .thenComparing(line -> line.row.key());

    private final MaterialRow row;
    private final boolean hud;
    private final boolean verified;
    private final long need;
    /** Сколько ещё не поставлено в мире: у сверенной схемы — {@code missing} строки, у остальных — то же, что {@link #need}. */
    private final long lack;
    private final long have;
    private final long shortage;
    private final Coverage coverage;
    private final String sortName;
    private final String haystack;
    private final String needText;
    private final String breakdownText;
    /** То же для «осталось поставить» ({@link #lack()}): колонки «Всего» и «Разбивка» при «Только недостающем». */
    private final String lackText;
    private final String lackBreakdownText;
    private final String inventoryText;
    private final String chestsText;
    private final String shortageText;
    private final List<String> replacedFrom;
    /** Слои схемы (1 — нижний), где блок строки отключён; пусто — нигде. */
    private final List<Integer> layersOff;
    /** Блок отключён в схеме: строка потухшая, с золотой кнопкой «включить», числа — как у включённого. */
    private final boolean disabled;

    private MaterialLine(MaterialRow row, boolean hud, boolean verified, LongFunction<String> number,
            List<String> replacedFrom, List<Integer> layersOff)
    {
        this(row, hud, verified, number, replacedFrom, layersOff, false, null);
    }

    private MaterialLine(MaterialRow row, boolean hud, boolean verified, LongFunction<String> number,
            List<String> replacedFrom, List<Integer> layersOff, boolean disabled, String disabledLabel)
    {
        this.row = row;
        this.hud = hud;
        this.verified = verified;
        this.disabled = disabled;
        // У отключённого числа те же, что были бы у включённого: строка не прыгает в сортировке
        this.need = Math.max(0L, hud ? row.missing() : row.total());
        this.lack = verified ? Math.max(0L, row.missing()) : this.need;
        this.have = saturatedSum(Math.max(0L, row.inInventory()), Math.max(0L, row.inChests()));
        this.shortage = Math.max(0L, this.lack - this.have);
        this.coverage = Coverage.of(this.lack, this.have);
        this.sortName = row.name().toLowerCase(Locale.ROOT);
        this.replacedFrom = List.copyOf(replacedFrom);
        this.layersOff = List.copyOf(layersOff);
        String was = this.replacedFrom.isEmpty() ? "" : '\n' + String.join("\n", this.replacedFrom).toLowerCase(Locale.ROOT);
        this.haystack = this.sortName + '\n' + row.key().toLowerCase(Locale.ROOT) + was;
        this.needText = number.apply(this.need);
        this.breakdownText = disabled ? disabledLabel : MaterialRow.breakdown(this.need, row.maxStack());
        this.lackText = this.lack == this.need ? this.needText : number.apply(this.lack);
        this.lackBreakdownText = disabled || this.lack == this.need ? this.breakdownText
                : MaterialRow.breakdown(this.lack, row.maxStack());
        this.inventoryText = number.apply(row.inInventory());
        this.chestsText = number.apply(row.inChests());
        this.shortageText = number.apply(this.shortage);
    }

    /**
     * Строка таблицы из строки материалов.
     *
     * @param hud    источник — список HUD: «нужно» = недостающее в мире, а не весь счёт
     * @param number число с разрядами по языку игры
     */
    public static MaterialLine of(MaterialRow row, boolean hud, LongFunction<String> number)
    {
        return new MaterialLine(row, hud, false, number, List.of(), List.of());
    }

    /**
     * Строка блока, которым в схеме заменены другие.
     *
     * @param replacedFrom названия исходных блоков (языка игры), в порядке замен; пусто — замен нет
     */
    public static MaterialLine of(MaterialRow row, boolean hud, LongFunction<String> number, List<String> replacedFrom)
    {
        return new MaterialLine(row, hud, false, number, replacedFrom, List.of());
    }

    /**
     * Строка блока с заменами и отключениями на слоях.
     *
     * @param layersOff слои схемы (1 — нижний), где блок строки отключён
     */
    public static MaterialLine of(MaterialRow row, boolean hud, LongFunction<String> number, List<String> replacedFrom,
            List<Integer> layersOff)
    {
        return new MaterialLine(row, hud, false, number, replacedFrom, layersOff);
    }

    /**
     * То же для схемы, сверенной с миром: {@code row.missing()} — сколько ещё не поставлено на месте постройки.
     *
     * @param verified строка из подсчёта размещения в мире, а не из файла целиком
     */
    public static MaterialLine of(MaterialRow row, boolean hud, boolean verified, LongFunction<String> number,
            List<String> replacedFrom, List<Integer> layersOff)
    {
        return new MaterialLine(row, hud, verified, number, replacedFrom, layersOff);
    }

    /**
     * Строка блока, отключённого в схеме.
     *
     * @param row   строка с тем же счётом, что у блока до отключения
     * @param label подпись вместо разбивки («отключён»)
     */
    public static MaterialLine disabled(MaterialRow row, LongFunction<String> number, String label)
    {
        return new MaterialLine(row, false, false, number, List.of(), List.of(), true, label);
    }

    /** Сравнение по числу, при равенстве — по названию (порядок не прыгает между перерисовками). */
    public static Comparator<MaterialLine> byCount(ToLongFunction<MaterialLine> count)
    {
        return Comparator.comparingLong(count).thenComparing(BY_NAME);
    }

    private static long saturatedSum(long a, long b)
    {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    public boolean disabled()
    {
        return this.disabled;
    }

    /** Строка изменена в схеме: блок отключён (весь или на слоях) или стоит вместо других (фильтр «Изменённые»). */
    public boolean changed()
    {
        return this.disabled || ! this.replacedFrom.isEmpty() || ! this.layersOff.isEmpty();
    }

    /** Слои схемы (1 — нижний), где блок строки отключён. */
    public List<Integer> layersOff()
    {
        return this.layersOff;
    }

    public MaterialRow row()
    {
        return this.row;
    }

    public ItemStack stack()
    {
        return this.row.stack();
    }

    public String name()
    {
        return this.row.name();
    }

    public String key()
    {
        return this.row.key();
    }

    /** Строка из списка HUD (тогда «нужно» — недостающее в мире). */
    public boolean hud()
    {
        return this.hud;
    }

    public long need()
    {
        return this.need;
    }

    /** Строка сверена с миром: «Всего» — вся постройка, {@link #lack()} — ещё не поставлено. */
    public boolean verified()
    {
        return this.verified;
    }

    /** Сколько ещё не поставлено в мире; с него считаются «Не хватает» и «Только недостающее». */
    public long lack()
    {
        return this.lack;
    }

    /** Инвентарь + сундуки. */
    public long have()
    {
        return this.have;
    }

    public long inventory()
    {
        return this.row.inInventory();
    }

    public long chests()
    {
        return this.row.inChests();
    }

    public long shortage()
    {
        return this.shortage;
    }

    public Coverage coverage()
    {
        return this.coverage;
    }

    /** Шалкеровых ящиков на нужное (дробно — как колонка шалкеров таблицы сбора). */
    public double shulkers()
    {
        return (double) this.need / ((double) MaterialRow.SHULKER_SLOTS * this.row.maxStack());
    }

    /** Шалкеровых ящиков на недостающее (дробно). */
    public double lackShulkers()
    {
        return (double) this.lack / ((double) MaterialRow.SHULKER_SLOTS * this.row.maxStack());
    }

    public String sortName()
    {
        return this.sortName;
    }

    /** Названия исходных блоков, заменённых в схеме этим блоком; пусто — строка не замена. */
    public List<String> replacedFrom()
    {
        return this.replacedFrom;
    }

    /** Название, ключ и исходные названия замен в нижнем регистре, через перевод строки, — для поиска. */
    public String haystack()
    {
        return this.haystack;
    }

    public String needText()
    {
        return this.needText;
    }

    public String breakdownText()
    {
        return this.breakdownText;
    }

    /** Сколько ещё поставить — числом (у строк без сверки — как {@link #needText()}). */
    public String lackText()
    {
        return this.lackText;
    }

    /** Сколько ещё поставить — шалкерами, стаками и штуками. */
    public String lackBreakdownText()
    {
        return this.lackBreakdownText;
    }

    public String inventoryText()
    {
        return this.inventoryText;
    }

    public String chestsText()
    {
        return this.chestsText;
    }

    public String shortageText()
    {
        return this.shortageText;
    }
}
