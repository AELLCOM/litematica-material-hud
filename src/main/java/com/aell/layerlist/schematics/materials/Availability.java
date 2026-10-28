package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.storage.StorageIndex;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;

/**
 * Что уже есть у игрока для колонок «В инвентаре» и «В сундуках». Снимается один раз на сборку
 * таблицы, дальше строки спрашивают по своему предмету.
 *
 * <p>Инвентарь — по ключу строки ({@link DataItems.Key}: предмет + узор, черепки, профиль), вместе
 * с содержимым шалкеров и мешков, которые лежат в инвентаре. Так считают и HUD-строки Litematica,
 * а баннер с узором здесь ещё и не засчитывается за баннер без узора (и наоборот).
 *
 * <p>Сундуки — индекс складов (M05) текущего сервера и измерения: только контейнеры, которые
 * игрок открывал, только верхние слоты (шалкер в сундуке — один шалкер, без содержимого) и только
 * по id предмета, без узоров. Это честно подписывается в подсказке колонки.
 *
 * <p>Снимать — на клиентском потоке.
 */
@Environment(EnvType.CLIENT)
public final class Availability
{
    /** Шалкер в мешке в шалкере — глубже вложенности в ванили не бывает. */
    private static final int MAX_DEPTH = 3;

    private static final Availability NONE = new Availability(Map.of(), Map.of());

    private final Map<DataItems.Key, Long> inventory;
    private final Map<String, Long> chests;

    private Availability(Map<DataItems.Key, Long> inventory, Map<String, Long> chests)
    {
        this.inventory = inventory;
        this.chests = chests;
    }

    /** Инвентарь и склады прямо сейчас. Без мира — пусто. */
    public static Availability capture()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return NONE;
        }

        return new Availability(inventoryOf(mc.player), chestsOf(mc));
    }

    public static Availability none()
    {
        return NONE;
    }

    /** Подсказка колонки «В инвентаре»: что входит в счёт. */
    public static String inventoryHint()
    {
        return I18n.get("layerlist.schematics.materials.hint.inventory");
    }

    /** Подсказка колонки «В сундуках»: откуда число и чего в нём нет. */
    public static String chestsHint()
    {
        return I18n.get("layerlist.schematics.materials.hint.chests");
    }

    /** Сколько таких предметов в инвентаре (с данными — только с теми же данными). */
    public long inInventory(ItemStack stack)
    {
        return stack.isEmpty() ? 0L : this.inventory.getOrDefault(new DataItems.Key(stack), 0L);
    }

    /** Сколько таких предметов в известных хранилищах (по id, без данных). */
    public long inChests(ItemStack stack)
    {
        return stack.isEmpty() ? 0L : this.chests.getOrDefault(StorageIndex.itemId(stack), 0L);
    }

    private static Map<DataItems.Key, Long> inventoryOf(@Nullable Player player)
    {
        Map<DataItems.Key, Long> counts = new HashMap<>();

        if (player == null)
        {
            return counts;
        }

        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
        {
            add(counts, inventory.getItem(slot), 1L, 0);
        }

        return counts;
    }

    private static void add(Map<DataItems.Key, Long> counts, ItemStack stack, long times, int depth)
    {
        if (stack.isEmpty())
        {
            return;
        }

        long count = stack.getCount() * times;
        counts.merge(new DataItems.Key(stack), count, Long::sum);

        if (depth >= MAX_DEPTH)
        {
            return;
        }

        // Шалкер со всем, что в нём, — это и сам шалкер, и его содержимое, умноженное на число ящиков
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);

        if (contents != null)
        {
            contents.nonEmptyItemCopyStream().forEach(inner -> add(counts, inner, count, depth + 1));
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);

        if (bundle != null)
        {
            bundle.itemCopyStream().forEach(inner -> add(counts, inner, count, depth + 1));
        }
    }

    private static Map<String, Long> chestsOf(Minecraft mc)
    {
        Map<String, Long> counts = new HashMap<>();
        String scope = StorageIndex.scope(mc);

        if (scope.isEmpty())
        {
            return counts;
        }

        for (StorageIndex.Rec rec : StorageIndex.getInstance().recordsInScope(scope))
        {
            if (rec.counts == null)
            {
                continue;
            }

            for (Map.Entry<String, Integer> entry : rec.counts.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                {
                    counts.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
                }
            }
        }

        return counts;
    }
}
