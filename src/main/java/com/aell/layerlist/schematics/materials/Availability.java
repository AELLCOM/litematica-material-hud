package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.storage.ChosenStorages;
import com.aell.layerlist.storage.StockArea;
import com.aell.layerlist.storage.StorageIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
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
 * игрок открывал или помнит Chest Tracker (с «Только выбранные сундуки» — только выбранные из них, со «Склад только
 * рядом» — только в радиусе от игрока), вместе с содержимым шалкеров и
 * мешков в них, и эндер-сундук игрока. Предмет с данными (узорный флаг, расписной горшок, голова игрока, свет) ищется
 * по тем же данными, «обычный» того же вида — без узорных ({@link StorageIndex#stored}); записи, сделанные до 1.0.32,
 * состава не знают — предметы с данными в них не видны, пока хранилище не откроют заново (это в подсказке колонки).
 *
 * <p>Снимать — на клиентском потоке.
 */
@Environment(EnvType.CLIENT)
public final class Availability
{
    /** Шалкер в мешке в шалкере — глубже вложенности в ванили не бывает. */
    private static final int MAX_DEPTH = 3;

    private static final Availability NONE = new Availability(Map.of(), List.of());

    private final Map<DataItems.Key, Long> inventory;
    /** Записи хранилищ, которые в счёте; сколько в них предмета — {@link StorageIndex#stored}. */
    private final List<StorageIndex.Rec> chests;
    /** Уже спрошенное: строк сотни, записей бывают тысячи, а таблица спрашивает одну строку не раз. */
    private final Map<DataItems.Key, Long> chestsAsked = new HashMap<>();

    private Availability(Map<DataItems.Key, Long> inventory, List<StorageIndex.Rec> chests)
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

    /** Подсказка колонки «В сундуках»: откуда число и чего в нём нет — и что сейчас входит в счёт. */
    public static String chestsHint()
    {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder hint = new StringBuilder(I18n.get("layerlist.schematics.materials.hint.chests"));

        if (LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue())
        {
            String scope = StorageIndex.scope(mc);
            hint.append(". ").append(I18n.get("layerlist.schematics.materials.hint.chests_chosen",
                    scope.isEmpty() ? 0 : ChosenStorages.getInstance().count(scope)));
        }

        if (StockArea.active())
        {
            hint.append(". ").append(I18n.get("layerlist.schematics.materials.hint.chests_near", StockArea.radius()));
        }

        if (com.aell.layerlist.compat.ChestTracker.active())
        {
            hint.append(". ").append(I18n.get("layerlist.schematics.materials.hint.chests_tracker"));
        }

        if (LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue())
        {
            hint.append(". ").append(I18n.get(enderRecord(mc) != null
                    ? "layerlist.schematics.materials.hint.chests_ender"
                    : "layerlist.schematics.materials.hint.chests_ender_unknown"));
        }

        return hint.toString();
    }

    /** Сколько таких предметов в инвентаре (с данными — только с теми же данными). */
    public long inInventory(ItemStack stack)
    {
        return stack.isEmpty() ? 0L : this.inventory.getOrDefault(new DataItems.Key(stack), 0L);
    }

    /**
     * Сколько таких предметов в известных хранилищах: с данными — только с теми же данными, «обычных» — без узорных
     * ({@link StorageIndex#stored}). Как и «В инвентаре», одно число на строку: строки с разными данными запас не делят.
     */
    public long inChests(ItemStack stack)
    {
        if (stack.isEmpty() || this.chests.isEmpty())
        {
            return 0L;
        }

        return this.chestsAsked.computeIfAbsent(new DataItems.Key(stack), key ->
        {
            String id = StorageIndex.itemId(stack);
            String dataId = DataItems.dataId(stack);
            boolean carry = DataItems.canCarry(stack);
            long count = 0L;

            for (StorageIndex.Rec rec : this.chests)
            {
                count += StorageIndex.stored(rec, id, dataId, carry);
            }

            return count;
        });
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

    /**
     * Склад по id: известные хранилища области — все или только выбранные («Только выбранные сундуки»), и эндер-сундук
     * игрока, если он в счёте. Эндер-сундук считается и в режиме выбранных: он личный, чужим не бывает.
     */
    private static List<StorageIndex.Rec> chestsOf(Minecraft mc)
    {
        List<StorageIndex.Rec> records = new ArrayList<>();
        String scope = StorageIndex.scope(mc);

        if (scope.isEmpty())
        {
            return records;
        }

        Set<BlockPos> chosen = LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue()
                ? ChosenStorages.getInstance().in(scope) : null;

        for (StorageIndex.Rec rec : StorageIndex.getInstance().recordsInScope(scope))
        {
            if (rec.counts != null && (chosen == null || chosen.contains(rec.pos())) && StockArea.inRange(mc, rec.pos()))
            {
                records.add(rec);
            }
        }

        StorageIndex.Rec ender = LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue() ? enderRecord(mc) : null;

        if (ender != null && ender.counts != null)
        {
            records.add(ender);
        }

        return records;
    }

    /** Последний снимок эндер-сундука игрока на этом сервере; {@code null} — ещё ни разу не открывался. */
    public static StorageIndex.@Nullable Rec enderRecord(Minecraft mc)
    {
        String scope = StorageIndex.enderScope(mc);
        return scope.isEmpty() ? null : StorageIndex.getInstance().get(scope, StorageIndex.ENDER_POS);
    }
}
