package com.aell.layerlist.core;

import com.aell.layerlist.config.LayerListConfigs;

/**
 * Что, кроме пустых мест, идёт в «не хватает» списка материалов (панель, вкладка «Материалы», выгрузка).
 * Счёт чанка всегда хранит все три вида клеток отдельно (пусто / стоит не то / из них — не тот блок), режим
 * применяется при сложении ({@code LayerCountManager.accumulate}), поэтому кэш проверки от настройки не зависит.
 *
 * <ul>
 *   <li>{@link #EMPTY} — только пустые места (умолчание: «Только пустые места» вкл., «Считать „Не тот блок“» выкл.);</li>
 *   <li>{@link #WRONG_BLOCKS} — пустые и занятые блоком другого типа (27.11.2026): его ломают и ставят нужный,
 *       материал понадобится. Не то состояние (поворот, половинка) не входит — блок тот же;</li>
 *   <li>{@link #ALL} — всё, что стоит не так («Только пустые места» выкл., как было до 27.11.2026).</li>
 * </ul>
 */
public enum Shortfall
{
    EMPTY,
    WRONG_BLOCKS,
    ALL;

    /** Режим по текущим настройкам. */
    public static Shortfall current()
    {
        if (! LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue())
        {
            return ALL;
        }

        return LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue() ? WRONG_BLOCKS : EMPTY;
    }
}
