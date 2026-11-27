package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.materials.MaterialRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Запрос, разобранный один раз для всех форматов: фильтр «только недостающее» применён,
 * пустые строки выброшены, у каждой строки есть id предмета и количество к сбору.
 *
 * @param created время выгрузки (для подписи в шапке — настенные часы, а не
 *                {@code Util.getMillis()}: тут нужна дата, а не отрезок времени)
 */
@Environment(EnvType.CLIENT)
record ExportData(ExportRequest request, LocalDateTime created, List<Line> lines)
{
    ExportData
    {
        lines = List.copyOf(lines);
    }

    /**
     * Строка выгрузки.
     *
     * @param quantity сколько собрать: при «только недостающее» — {@code missing}, иначе
     *                 {@code total}; «с учётом склада» — недостающее за вычетом инвентаря и сундуков
     * @param net      количество уже за вычетом склада: красить его «уже есть» не нужно
     */
    record Line(MaterialRow row, String id, long quantity, boolean net)
    {
        String name()
        {
            return ExportText.plain(row.name());
        }

        int maxStack()
        {
            return row.maxStack();
        }

        String breakdown()
        {
            return MaterialRow.breakdown(quantity, row.maxStack());
        }

        double shulkers()
        {
            return (double) quantity / ((double) MaterialRow.SHULKER_SLOTS * row.maxStack());
        }

        /**
         * Колонка «Не хватает» таблиц CSV и «Копировать»: недостающее в мире, а «с учётом склада» — уже за вычетом
         * инвентаря и сундуков (как колонка «Не хватает» вкладки «Материалы»).
         */
        long missing()
        {
            return net ? quantity : row.missing();
        }

        /** Сколько из нужного уже есть в инвентаре и сундуках — для цвета количества. */
        long covered()
        {
            return net ? 0L : Math.min(quantity, Math.max(0L, row.inInventory()) + Math.max(0L, row.inChests()));
        }
    }

    static ExportData of(ExportRequest request, LocalDateTime created)
    {
        ExportOptions options = request.options();
        boolean net = options.useStock();
        List<Line> lines = new ArrayList<>(request.rows().size());

        for (MaterialRow row : request.rows())
        {
            long quantity = options.quantity(row);

            if (quantity > 0L)
            {
                lines.add(new Line(row, idOf(row.stack()), quantity, net));
            }
        }

        return new ExportData(request, created, lines);
    }

    static String idOf(ItemStack stack)
    {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    long blocks()
    {
        long sum = 0L;

        for (Line line : this.lines)
        {
            sum += line.quantity();
        }

        return sum;
    }

    double shulkers()
    {
        double sum = 0.0;

        for (Line line : this.lines)
        {
            sum += line.shulkers();
        }

        return sum;
    }

    String title()
    {
        String title = ExportText.plain(this.request.title()).strip();

        return title.isEmpty() ? ExportText.t("untitled") : title;
    }

    /** Имя файла без расширения, ещё не очищенное: имя схемы из запроса, без него — название таблицы. */
    String fileName()
    {
        String name = ExportText.plain(this.request.fileName()).strip();

        return name.isEmpty() ? this.title() : name;
    }
}
