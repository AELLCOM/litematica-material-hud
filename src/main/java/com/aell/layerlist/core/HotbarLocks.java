package com.aell.layerlist.core;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Inventory;

/**
 * Запертые ячейки хотбара: в запертую мод не кладёт блоки и не убирает из неё предмет (просьба 19.11.2026:
 * «берутся блоки в первый слот, и меч улетает куда-то в инвентарь»).
 *
 * <p>Список — своя опция {@code lockedHotbarSlots} (биты ячеек, по умолчанию пусто: ничего не заперто). Взятие
 * блока из рюкзака — pick-block схемы, Easy Place, инструмент доводки — целиком идёт через Litematica, и ячейку
 * выбирает она: {@code MixinLitematicaPickBlock} вычёркивает запертые из её выбора. Свои обходные пути мода (обмен с
 * запитанной полкой, творческий режим, перенос из сундука) сверяются с тем же списком здесь.
 *
 * <p>У Litematica есть и своя настройка {@code pickBlockableSlots} («в какие ячейки брать блоки», по умолчанию
 * 1–5) — она действует как раньше, запреты мода идут поверх. Сливать их нельзя: тогда ячейки 6–9 считались бы
 * запертыми у всех с первого запуска, а это отключило бы обмен с запитанной полкой (её окно — ячейки 7–9) и
 * перенаправило бы перенос из сундука.
 */
@Environment(EnvType.CLIENT)
public final class HotbarLocks
{
    public static final int SLOTS = 9;

    private HotbarLocks() {}

    private static int mask()
    {
        return LayerListConfigs.LOCKED_HOTBAR_SLOTS.getIntegerValue();
    }

    /** Заперта ли ячейка хотбара 0–8; всё, что не хотбар, не заперто. */
    public static boolean locked(int slot)
    {
        return slot >= 0 && slot < SLOTS && (mask() & (1 << slot)) != 0;
    }

    public static int lockedCount()
    {
        return Integer.bitCount(mask() & ((1 << SLOTS) - 1));
    }

    /** Есть ли запертая ячейка среди {@code from … to} включительно. */
    public static boolean anyLocked(int from, int to)
    {
        for (int slot = Math.max(0, from); slot <= to && slot < SLOTS; ++slot)
        {
            if (locked(slot))
            {
                return true;
            }
        }

        return false;
    }

    public static void toggle(int slot)
    {
        setLocked(slot, ! locked(slot));
    }

    /** Запереть или открыть ячейку и сразу записать настройки мода: запрет не должен пропасть при вылете игры. */
    public static void setLocked(int slot, boolean lock)
    {
        if (slot < 0 || slot >= SLOTS)
        {
            return;
        }

        LayerListConfigs.LOCKED_HOTBAR_SLOTS.setIntegerValue(lock ? mask() | (1 << slot) : mask() & ~(1 << slot));
        new LayerListConfigs().save();
    }

    /**
     * Остались ли у Litematica ячейки, куда она может взять блок из рюкзака: её собственный список
     * {@code pickBlockableSlots} минус запертые. Нет — блоки из рюкзака в руку не берутся вовсе.
     */
    public static boolean pickTargetLeft()
    {
        String allowed;

        try
        {
            allowed = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        }
        catch (RuntimeException | LinkageError e)
        {
            return lockedCount() < SLOTS;
        }

        for (String part : allowed.split(","))
        {
            try
            {
                // Без trim — как разбирает сама Litematica: «1, 2» для неё только ячейка 1
                int slot = Integer.parseInt(part) - 1;

                if (slot >= 0 && slot < SLOTS && ! locked(slot))
                {
                    return true;
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }

        return false;
    }

    /**
     * Ячейка хотбара, куда мод может положить предмет сам: рука, если она открыта, иначе пустая открытая,
     * иначе первая открытая. −1 — заперто всё.
     */
    public static int target(Inventory inventory)
    {
        int hand = inventory.getSelectedSlot();

        if (! locked(hand))
        {
            return hand;
        }

        int first = -1;

        for (int slot = 0; slot < SLOTS; ++slot)
        {
            if (locked(slot))
            {
                continue;
            }

            if (inventory.getItem(slot).isEmpty())
            {
                return slot;
            }

            first = first < 0 ? slot : first;
        }

        return first;
    }
}
