package com.aell.layerlist.compat;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.storage.StorageIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chest Tracker (неофициальный порт, мод {@code chesttracker}) как источник склада — заявка #2 на GitHub (06.11.2026):
 * «у Chest Tracker уже есть база сундуков — берите её, а не открывайте всё заново». Если мод стоит и включена опция
 * {@link LayerListConfigs#STOCK_CHEST_TRACKER}, его память о хранилищах текущего мира и измерения (и об эндер-сундуке)
 * добавляется к своему индексу ({@link StorageIndex#recordsInScope}, {@link StorageIndex#get}): «В сундуках»,
 * «С учётом склада», поиск по сундукам и подсветка видят и то, что игрок открывал, когда Material HUD ещё не стоял.
 * Одно и то же хранилище в обоих — берётся более свежая запись.
 *
 * <p>Без типов Chest Tracker: всё с ними — в {@link ChestTrackerImpl}, он грузится, только если мод стоит. Память
 * читается в игровом потоке раз в {@link #REFRESH_TICKS} тиков и копируется в записи индекса; сменилась — ревизия
 * индекса растёт ({@link StorageIndex#touch}), HUD и таблица пересчитываются. Сбой — в лог один раз, склад работает
 * без Chest Tracker.
 */
@Environment(EnvType.CLIENT)
public final class ChestTracker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("chesttracker");
    private static final int REFRESH_TICKS = 40;

    /** Записи по ключу индекса ({@code область|x,y,z}). */
    private static Map<String, StorageIndex.Rec> records = Map.of();
    /** Те же записи по области. */
    private static Map<String, List<StorageIndex.Rec>> byScope = Map.of();
    private static long print;
    private static int ticks = REFRESH_TICKS;
    private static boolean failed;

    private ChestTracker() {}

    public static boolean installed()
    {
        return INSTALLED;
    }

    /** Склад сейчас берёт память Chest Tracker. */
    public static boolean active()
    {
        return INSTALLED && ! failed && LayerListConfigs.STOCK_CHEST_TRACKER.getBooleanValue();
    }

    /** Сколько хранилищ Chest Tracker сейчас добавляет (для подсказок). */
    public static int count()
    {
        return active() ? records.size() : 0;
    }

    public static List<StorageIndex.Rec> records(String scope)
    {
        return active() ? byScope.getOrDefault(scope, List.of()) : List.of();
    }

    public static StorageIndex.@Nullable Rec get(String key)
    {
        return active() ? records.get(key) : null;
    }

    public static void onClientTick(Minecraft mc)
    {
        if (! INSTALLED || failed || ++ticks < REFRESH_TICKS)
        {
            return;
        }

        ticks = 0;

        if (! LayerListConfigs.STOCK_CHEST_TRACKER.getBooleanValue() || mc.level == null || mc.player == null)
        {
            clear();
            return;
        }

        try
        {
            ChestTrackerImpl.Snapshot snapshot = ChestTrackerImpl.read(mc, print);

            if (snapshot != null)
            {
                print = snapshot.print();
                records = snapshot.records();
                Map<String, List<StorageIndex.Rec>> scopes = new HashMap<>();

                for (StorageIndex.Rec rec : records.values())
                {
                    scopes.computeIfAbsent(rec.scope, k -> new java.util.ArrayList<>()).add(rec);
                }

                byScope = scopes;
                StorageIndex.getInstance().touch();
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            failed = true;
            LOGGER.warn("Chest Tracker memory could not be read; storage counts without it", e);
            clear();
        }
    }

    /** Опция переключена — перечитать на следующем тике. */
    public static void changed()
    {
        clear();
        ticks = REFRESH_TICKS;
        StorageIndex.getInstance().touch();
    }

    private static void clear()
    {
        if (! records.isEmpty())
        {
            StorageIndex.getInstance().touch();
        }

        records = Map.of();
        byScope = Map.of();
        print = 0L;
    }
}
