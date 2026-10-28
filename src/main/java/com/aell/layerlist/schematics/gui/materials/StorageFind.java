package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * «Найти в сундуках» из таблицы: тот же поиск по складам, что у клавиши поиска HUD
 * ({@link StorageSearch#togglePage}, публичный вход M05), только для одного предмета. Отметки
 * рисуются в мире, поэтому увидеть их можно, закрыв экран.
 *
 * <p>Поиск привязан к странице HUD: при включённом «Следовать за страницей» следующий пересчёт HUD
 * заменит отметку его страницей — так работает сам поиск, и это сказано в подсказке пункта меню.
 */
@Environment(EnvType.CLIENT)
public final class StorageFind
{
    public enum Outcome
    {
        /** Отмечены хранилища рядом. */
        MARKED,
        /** Предмет есть в индексе, но рядом (в радиусе, в прогруженных чанках) хранилищ с ним нет. */
        NOT_NEAR,
        /** Ни в одном открытом хранилище этого измерения его не видели. */
        NOT_KNOWN,
        /** Этот предмет уже искали — поиск выключен. */
        CLEARED,
        NO_WORLD
    }

    /** @param storages сколько хранилищ отмечено */
    public record Result(Outcome outcome, int storages) {}

    private StorageFind() {}

    /** Сейчас отмечен именно этот предмет (и только он): пункт меню тогда выключает поиск. */
    public static boolean marking(ItemStack stack)
    {
        StorageSearch search = StorageSearch.getInstance();

        return search.isActive() && search.colorFor(stack) != null && search.markedItems().size() == 1;
    }

    /** Отметить хранилища с предметом строки или, если он уже отмечен, выключить поиск. Клиентский поток. */
    public static Result toggle(MaterialRow row)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null)
        {
            return new Result(Outcome.NO_WORLD, 0);
        }

        StorageSearch search = StorageSearch.getInstance();

        if (marking(row.stack()))
        {
            search.clear();
            return new Result(Outcome.CLEARED, 0);
        }

        int missing = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, row.missing()));
        int total = (int) Math.min(Integer.MAX_VALUE, Math.max(missing, row.total()));
        // Наличие 0: игрок просит найти предмет явно, «Пропускать имеющееся» тут не к месту
        MaterialListEntry entry = new MaterialListEntry(row.stack(), total, missing, 0, 0);
        search.togglePage(List.of(entry));

        // Новый набор пересчитывается сразу (в мире), поэтому совпадения уже готовы
        int storages = search.getMatches().size();

        if (storages > 0)
        {
            return new Result(Outcome.MARKED, storages);
        }

        return new Result(row.inChests() > 0L ? Outcome.NOT_NEAR : Outcome.NOT_KNOWN, 0);
    }

    /** Радиус поиска, блоков — для текста «не рядом». */
    public static int radius()
    {
        return LayerListConfigs.SEARCH_RADIUS.getIntegerValue();
    }

    /** HUD перехватит отметку при пересчёте (опция «Следовать за страницей»). */
    public static boolean followsHud()
    {
        return LayerListConfigs.SEARCH_FOLLOWS_PAGE.getBooleanValue();
    }
}
