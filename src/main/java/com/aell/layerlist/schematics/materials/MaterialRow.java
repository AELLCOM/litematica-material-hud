package com.aell.layerlist.schematics.materials;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;

/**
 * Строка таблицы материалов — общая для экрана, всех выгрузок и источников «Схема целиком» /
 * «Список HUD».
 *
 * @param stack       предмет, количество 1 (для иконки и названия); у строк с данными — с узором,
 *                    черепками или профилем
 * @param key         ключ строки: id предмета + опознающие компоненты, как у {@code DataItems.Key}
 *                    ({@link ItemKeys#of}); одинаковый ключ — одна и та же строка
 * @param name        название для людей ({@code DataItems.fullLabel})
 * @param total       нужно всего, шт
 * @param missing     не хватает: у источника HUD — недостающее в мире; у источника «схема» сверки с
 *                    миром нет, поэтому равно {@code total}
 * @param inInventory есть в инвентаре игрока (вместе с шалкерами и мешками в нём), по ключу строки
 * @param inChests    есть в известных хранилищах текущего сервера и измерения (только верхние слоты,
 *                    без различия узоров — индекс складов хранит лишь id предмета)
 * @param maxStack    размер стака предмета, не меньше 1
 * @param entity      строка целиком из сущностей схемы (рамка, стойка…); строка, где тот же предмет
 *                    нужен и блоками, — не сущность
 */
@Environment(EnvType.CLIENT)
public record MaterialRow(ItemStack stack, String key, String name, long total, long missing, long inInventory,
                          long inChests, int maxStack, boolean entity)
{
    /** Слотов в шалкеровом ящике. */
    public static final int SHULKER_SLOTS = 27;

    public MaterialRow
    {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        maxStack = Math.max(1, maxStack);
    }

    /**
     * Слова разбивки: шаблоны частей с {@code %s} на месте числа и соединитель между частями.
     * Выгрузке, которой нужен язык, отличный от языка игры, хватает своих слов
     * ({@link #words}); экрану — {@link #translated()}.
     */
    public record Units(String shulkers, String stacks, String pieces, String join)
    {
        private static final String KEY_SHULKERS = "layerlist.schematics.breakdown.shulkers";
        private static final String KEY_STACKS = "layerlist.schematics.breakdown.stacks";
        private static final String KEY_PIECES = "layerlist.schematics.breakdown.pieces";
        private static final String KEY_JOIN = "layerlist.schematics.breakdown.join";

        public Units
        {
            Objects.requireNonNull(shulkers, "shulkers");
            Objects.requireNonNull(stacks, "stacks");
            Objects.requireNonNull(pieces, "pieces");
            Objects.requireNonNull(join, "join");
        }

        /**
         * Слова текущего языка игры (ключи {@code layerlist.schematics.breakdown.*}). Можно звать из
         * любого потока: читается только текущий словарь.
         */
        public static Units translated()
        {
            Language language = Language.getInstance();

            return new Units(language.getOrDefault(KEY_SHULKERS), language.getOrDefault(KEY_STACKS),
                    language.getOrDefault(KEY_PIECES), language.getOrDefault(KEY_JOIN));
        }

        /** Свои слова после числа: {@code words("шалк.", "ст.", "шт")} → «5 шалк. + 17 ст. + 16 шт». */
        public static Units words(String shulkers, String stacks, String pieces)
        {
            return new Units(after(shulkers), after(stacks), after(pieces), " + ");
        }

        private static String after(String word)
        {
            // Знак процента в слове — не шаблон
            return "%s " + word.replace("%", "%%");
        }

        String part(String pattern, long amount)
        {
            try
            {
                return String.format(Locale.ROOT, pattern, amount);
            }
            catch (IllegalFormatException e)
            {
                return amount + " " + pattern;
            }
        }
    }

    /** Разбивка {@link #total()} словами языка игры: «5 шалк. + 17 ст. + 16 шт». */
    public String breakdown()
    {
        return breakdown(this.total, this.maxStack);
    }

    /** Разбивка {@link #total()} заданными словами. */
    public String breakdown(Units units)
    {
        return breakdown(this.total, this.maxStack, units);
    }

    /**
     * Разбивка любого количества на шалкеры, стаки и штуки словами языка игры, без нулевых частей:
     * «25 ст. + 51 шт», «1 шт»; для несобираемых в стак (размер 1) стаков нет — «2 шалк. + 3 шт».
     * Ноль — «0 шт».
     */
    public static String breakdown(long count, int maxStack)
    {
        return breakdown(count, maxStack, Units.translated());
    }

    /** То же заданными словами — для выгрузок, которым нужен свой язык или формат. */
    public static String breakdown(long count, int maxStack, Units units)
    {
        long amount = Math.max(0L, count);
        int stack = Math.max(1, maxStack);
        long perBox = (long) SHULKER_SLOTS * stack;

        long boxes = amount / perBox;
        long rest = amount % perBox;
        long stacks = stack == 1 ? 0L : rest / stack;
        long pieces = stack == 1 ? rest : rest % stack;

        List<String> parts = new ArrayList<>(3);

        if (boxes > 0L)
        {
            parts.add(units.part(units.shulkers(), boxes));
        }

        if (stacks > 0L)
        {
            parts.add(units.part(units.stacks(), stacks));
        }

        if (pieces > 0L || parts.isEmpty())
        {
            parts.add(units.part(units.pieces(), pieces));
        }

        return String.join(units.join(), parts);
    }
}
