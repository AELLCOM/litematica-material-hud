package com.aell.layerlist.schematics.export;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Флажки окна выгрузки; последние выбранные хранятся в {@code SchematicsState}.
 *
 * @param onlyMissing  только недостающее
 * @param icons        иконки предметов
 * @param rawMaterials блок «Крупное сырьё» по рецептам игры
 * @param chart        диаграмма «Кто сколько собрал»
 */
@Environment(EnvType.CLIENT)
public record ExportOptions(boolean onlyMissing, boolean icons, boolean rawMaterials, boolean chart)
{
    /** Как в образце таблицы сбора: всё, с иконками, сырьём и диаграммой. */
    public static final ExportOptions DEFAULT = new ExportOptions(false, true, true, true);
}
