package com.aell.layerlist.compat;

import com.aell.layerlist.storage.StorageIndex;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.api.memory.MemoryKey;

/**
 * Чтение памяти Chest Tracker через его API ({@code red.jackf.chesttracker.api.memory}): загруженный банк памяти
 * (Chest Tracker сам грузит банк текущего мира при входе), ключ — измерение, в нём хранилища по месту. Двойной сундук
 * у Chest Tracker — одна запись с «другими местами»; в индексе Material HUD он лежит под меньшим из двух мест — так и
 * кладём. Эндер-сундук — отдельный ключ, берётся последний снимок. Грузить, только если мод стоит
 * ({@link ChestTracker}).
 */
@Environment(EnvType.CLIENT)
final class ChestTrackerImpl
{
    record Snapshot(long print, Map<String, StorageIndex.Rec> records) {}

    private ChestTrackerImpl() {}

    /** Записи текущего мира, если память сменилась с отпечатка {@code previous}; не сменилась — {@code null}. */
    static @Nullable Snapshot read(Minecraft mc, long previous)
    {
        Optional<MemoryBank> loaded = MemoryBankAccess.INSTANCE.getLoaded();
        String scope = StorageIndex.scope(mc);

        if (loaded.isEmpty() || scope.isEmpty() || mc.level == null)
        {
            return previous == 0L ? null : new Snapshot(0L, Map.of());
        }

        MemoryBank bank = loaded.get();
        Identifier dimension = mc.level.dimension().identifier();
        Map<BlockPos, Memory> world = memories(bank, dimension);
        Map<BlockPos, Memory> ender = memories(bank, CommonKeys.ENDER_CHEST_KEY);

        long print = 17L + System.identityHashCode(bank);
        print = print * 31L + scope.hashCode();
        print = fold(print, world);
        print = fold(print, ender) * 31L + 1L;

        if (print == previous)
        {
            return null;
        }

        Map<String, StorageIndex.Rec> records = new HashMap<>();

        for (Map.Entry<BlockPos, Memory> entry : world.entrySet())
        {
            Memory memory = entry.getValue();

            if (memory == null || entry.getKey() == null)
            {
                continue;
            }

            BlockPos canonical = entry.getKey();

            for (BlockPos other : memory.otherPositions())
            {
                if (other != null && before(other, canonical))
                {
                    canonical = other;
                }
            }

            StorageIndex.Rec rec = StorageIndex.recordOf(scope, canonical, memory.items(), millis(memory));
            StorageIndex.Rec same = records.get(StorageIndex.key(rec));

            if (same == null || same.updatedAt < rec.updatedAt)
            {
                records.put(StorageIndex.key(rec), rec);
            }
        }

        String enderScope = StorageIndex.enderScope(mc);
        Memory newest = null;

        for (Memory memory : ender.values())
        {
            if (memory != null && (newest == null || millis(newest) < millis(memory)))
            {
                newest = memory;
            }
        }

        if (newest != null && ! enderScope.isEmpty())
        {
            StorageIndex.Rec rec = StorageIndex.recordOf(enderScope, StorageIndex.ENDER_POS, newest.items(), millis(newest));
            records.put(StorageIndex.key(rec), rec);
        }

        return new Snapshot(print == 0L ? 1L : print, records);
    }

    private static Map<BlockPos, Memory> memories(MemoryBank bank, Identifier key)
    {
        Optional<MemoryKey> memoryKey = bank.getKey(key);
        return memoryKey.isEmpty() ? Map.of() : memoryKey.get().getMemories();
    }

    private static long fold(long print, Map<BlockPos, Memory> memories)
    {
        print = print * 31L + memories.size();

        for (Map.Entry<BlockPos, Memory> entry : memories.entrySet())
        {
            Memory memory = entry.getValue();
            print = print * 31L + (entry.getKey() == null ? 0L : entry.getKey().asLong());
            print = print * 31L + System.identityHashCode(memory);
            print = print * 31L + (memory == null ? 0L : millis(memory) + memory.items().size());
        }

        return print;
    }

    private static long millis(Memory memory)
    {
        Instant time = memory.realTimestamp();
        return time == null ? 0L : time.toEpochMilli();
    }

    private static boolean before(BlockPos a, BlockPos b)
    {
        if (a.getX() != b.getX())
        {
            return a.getX() < b.getX();
        }

        return a.getY() != b.getY() ? a.getY() < b.getY() : a.getZ() < b.getZ();
    }
}
