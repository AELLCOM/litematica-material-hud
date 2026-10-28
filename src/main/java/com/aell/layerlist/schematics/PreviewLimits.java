package com.aell.layerlist.schematics;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Пределы 3D-превью в режиме «Качественно» (0.0.27): сколько граней модели и клеток снимка схемы можно взять,
 * чтобы большая схема легла честными блоками, а игра не упала от нехватки памяти. Считаются один раз от свободной
 * памяти JVM — при первом обращении после выбора режима ({@link #reset()}): превью пересобирается при каждой смене
 * бюджета, и плавающий от кадра к кадру предел гонял бы сборку по кругу.
 */
@Environment(EnvType.CLIENT)
public final class PreviewLimits
{
    /** На грань: массивы модели (~100 Б), их копия при росте, кеш секций среза (в «Качественно» — одна модель). */
    private static final long BYTES_PER_QUAD = 250L;
    /** На клетку снимка: номер состояния (до 4 Б) и флажок сборки (1 Б) — с запасом. */
    private static final long BYTES_PER_CELL = 6L;
    /** Модели — половина свободной памяти, снимку — четверть (он живёт в кеше, пока его показывают). */
    private static final long QUAD_SHARE = 2L;
    private static final long CELL_SHARE = 4L;
    /** Больше граней одной модели не даём и при огромной памяти: столько уже не рисуется плавно. */
    private static final long MAX_QUADS = 40_000_000L;

    private static long quads;
    private static long cells;

    private PreviewLimits() {}

    /** Режим сменили: посчитать пределы заново при следующем обращении. */
    public static synchronized void reset()
    {
        quads = 0L;
        cells = 0L;
    }

    /** Граней модели в «Качественно»: не меньше {@code floor} (бюджет «Оптимизировано»). */
    public static synchronized int quads(int floor)
    {
        if (quads == 0L)
        {
            quads = Math.min(MAX_QUADS, free() / QUAD_SHARE / BYTES_PER_QUAD);
        }

        return (int) Math.max(floor, quads);
    }

    /** Клеток снимка в «Качественно»: не меньше {@code floor} и не больше {@code cap}. */
    public static synchronized long cells(long floor, long cap)
    {
        if (cells == 0L)
        {
            cells = free() / CELL_SHARE / BYTES_PER_CELL;
        }

        return Math.min(cap, Math.max(floor, cells));
    }

    private static long free()
    {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
    }
}
