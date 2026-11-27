package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.materials.MaterialRow;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Флажки окна выгрузки; последние выбранные хранятся в {@code SchematicsState}.
 *
 * @param onlyMissing  только недостающее
 * @param icons        иконки предметов
 * @param rawMaterials блок «Крупное сырьё» по рецептам игры
 * @param chart        диаграмма «Кто сколько собрал»
 * @param useStock     «С учётом склада» (1.0.35): недостающее за вычетом инвентаря и известных сундуков;
 *                     включает и {@code onlyMissing} — вычитать склад из «всего» незачем
 */
@Environment(EnvType.CLIENT)
public record ExportOptions(boolean onlyMissing, boolean icons, boolean rawMaterials, boolean chart, boolean useStock)
{
    /** Как в образце таблицы сбора: всё, с иконками, сырьём и диаграммой. */
    public static final ExportOptions DEFAULT = new ExportOptions(false, true, true, true, false);

    /** Какое количество идёт в выгрузку — переключатель «Всё / Только недостающее / С учётом склада». */
    public enum Amount
    {
        ALL,
        MISSING,
        STOCK
    }

    public ExportOptions
    {
        onlyMissing = onlyMissing || useStock;
    }

    /** Как до 1.0.35: без «С учётом склада». */
    public ExportOptions(boolean onlyMissing, boolean icons, boolean rawMaterials, boolean chart)
    {
        this(onlyMissing, icons, rawMaterials, chart, false);
    }

    public Amount amount()
    {
        return this.useStock ? Amount.STOCK : this.onlyMissing ? Amount.MISSING : Amount.ALL;
    }

    public ExportOptions withAmount(Amount amount)
    {
        return new ExportOptions(amount != Amount.ALL, this.icons, this.rawMaterials, this.chart, amount == Amount.STOCK);
    }

    /**
     * Сколько собрать по строке: всё, недостающее в мире или недостающее за вычетом того, что уже лежит в
     * инвентаре и известных сундуках (то же число, что колонка «Не хватает» вкладки «Материалы»).
     */
    public long quantity(MaterialRow row)
    {
        return switch (this.amount())
        {
            case ALL -> row.total();
            case MISSING -> row.missing();
            case STOCK -> Math.max(0L, Math.max(0L, row.missing()) - have(row));
        };
    }

    private static long have(MaterialRow row)
    {
        long sum = Math.max(0L, row.inInventory()) + Math.max(0L, row.inChests());

        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
