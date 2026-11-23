package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.storage.ChosenStorages;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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
        /** Включён «Свой склад», а предмет лежит только в невыбранных хранилищах — их поиск не отмечает. */
        NOT_CHOSEN,
        /** Предмет лежит в эндер-сундуке, а блока эндер-сундука рядом нет — отметка появится у любого. */
        IN_ENDER,
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

        // Метки над хранилищами ставятся по виду предмета. Узорный флаг (горшок с черепками, голова игрока), которого
        // нет ни в одном известном хранилище, так отметил бы сундуки с обычными флагами — а колонка «В сундуках» говорит 0
        if (com.aell.layerlist.core.DataItems.dataId(row.stack()) != null)
        {
            String where = StorageIndex.scope(mc);
            Set<BlockPos> own = LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue()
                    ? ChosenStorages.getInstance().in(where) : null;

            if (! knownInWorld(where, row.stack(), own) && ! inEnderChest(mc, row.stack()))
            {
                return new Result(own != null && knownInWorld(where, row.stack(), null) ? Outcome.NOT_CHOSEN
                        : Outcome.NOT_KNOWN, 0);
            }
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

        String scope = StorageIndex.scope(mc);
        // «Свой склад»: поиск отмечает только выбранные — и «не рядом» тоже только про них
        Set<BlockPos> chosen = LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue()
                ? ChosenStorages.getInstance().in(scope) : null;

        if (knownInWorld(scope, row.stack(), chosen))
        {
            return new Result(Outcome.NOT_NEAR, 0);
        }

        if (inEnderChest(mc, row.stack()))
        {
            return new Result(Outcome.IN_ENDER, 0);
        }

        return new Result(chosen != null && knownInWorld(scope, row.stack(), null) ? Outcome.NOT_CHOSEN
                : Outcome.NOT_KNOWN, 0);
    }

    /** Лежит ли предмет в снимке эндер-сундука, пока тот в счёте: тогда поиск светит блоки эндер-сундуков рядом. */
    private static boolean inEnderChest(Minecraft mc, ItemStack stack)
    {
        String enderScope = StorageIndex.enderScope(mc);
        StorageIndex.Rec ender = ! LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue() || enderScope.isEmpty() ? null
                : StorageIndex.getInstance().get(enderScope, StorageIndex.ENDER_POS);

        return StorageIndex.stored(ender, stack) > 0L;
    }

    /**
     * Лежит ли предмет в каком-нибудь записанном хранилище этого измерения ({@code only} — только среди этих). Записи
     * эндер-сундука здесь нет: она в своей области, без места в мире ({@link #inEnderChest}).
     */
    private static boolean knownInWorld(String scope, ItemStack stack, @Nullable Set<BlockPos> only)
    {
        for (StorageIndex.Rec rec : StorageIndex.getInstance().recordsInScope(scope))
        {
            if (StorageIndex.stored(rec, stack) > 0L && (only == null || only.contains(rec.pos())))
            {
                return true;
            }
        }

        return false;
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
