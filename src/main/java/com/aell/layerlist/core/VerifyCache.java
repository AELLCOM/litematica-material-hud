package com.aell.layerlist.core;

import fi.dy.masa.malilib.util.StringUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class VerifyCache
{
    private static final VerifyCache INSTANCE = new VerifyCache();
    private static final int MAX_SIGNATURES = 8;
    private static final long SAVE_INTERVAL_MS = 10_000L;

    private final Long2ObjectOpenHashMap<ChunkCounts> chunks = new Long2ObjectOpenHashMap<>();
    /**
     * Приписка к имени файла: у счёта вкладки «Материалы» свой файл ({@code -materials}), чтобы его подписи (каждая
     * правка схемы — новая) не вытесняли из общего лимита {@link #MAX_SIGNATURES} подписи HUD.
     */
    private final String fileSuffix;
    private String worldKey = "";
    private String signature = "";
    private boolean dirty;
    private long lastSave;

    public VerifyCache()
    {
        this("");
    }

    public VerifyCache(String fileSuffix)
    {
        this.fileSuffix = fileSuffix;
    }

    public static VerifyCache getInstance()
    {
        return INSTANCE;
    }

    public void ensure(Minecraft mc, String signature)
    {
        String worldKey = worldKey(mc);

        if (worldKey.equals(this.worldKey) && signature.equals(this.signature))
        {
            return;
        }

        this.save(true);
        this.chunks.clear();
        this.worldKey = worldKey;
        this.signature = signature;
        this.dirty = false;
        this.load(mc);
    }

    public ChunkCounts get(long chunkKey)
    {
        return this.chunks.get(chunkKey);
    }

    public void put(long chunkKey, Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch)
    {
        this.put(chunkKey, missing, mismatch, null);
    }

    /**
     * {@code data} — та же недостача клеток с данными (узор, черепки, профиль) своими ключами. Живёт только в
     * памяти: на диск идёт счёт по состоянию, где эти клетки уже сложены.
     */
    public void put(long chunkKey, Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch,
                    @Nullable DataCounts data)
    {
        ChunkCounts previous = this.chunks.put(chunkKey, new ChunkCounts(missing, mismatch, data));

        if (previous == null || ! previous.missing().equals(missing) || ! previous.mismatch().equals(mismatch))
        {
            this.dirty = true;
        }
    }

    public void save(boolean force)
    {
        if (! this.dirty || this.worldKey.isEmpty())
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (! force && now - this.lastSave < SAVE_INTERVAL_MS)
        {
            return;
        }

        this.lastSave = now;
        this.dirty = false;

        try
        {
            Path file = cacheFile(this.worldKey + this.fileSuffix);
            Files.createDirectories(file.getParent());
            CompoundTag root = Files.exists(file)
                    ? NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    : new CompoundTag();
            List<CompoundTag> others = new ArrayList<>();

            for (Tag tag : root.getListOrEmpty("entries"))
            {
                if (tag instanceof CompoundTag entry && ! this.signature.equals(entry.getStringOr("sig", "")))
                {
                    others.add(entry);
                }
            }

            others.sort(Comparator.comparingLong((CompoundTag entry) -> entry.getLongOr("used", 0L)).reversed());

            ListTag next = new ListTag();
            next.add(this.writeEntry(now));

            for (int i = 0; i < others.size() && i < MAX_SIGNATURES - 1; ++i)
            {
                next.add(others.get(i));
            }

            CompoundTag out = new CompoundTag();
            out.put("entries", next);
            NbtIo.writeCompressed(out, file);
        }
        catch (Exception e) {}
    }

    private CompoundTag writeEntry(long now)
    {
        CompoundTag entry = new CompoundTag();
        entry.putString("sig", this.signature);
        entry.putLong("used", now);
        ListTag list = new ListTag();

        for (var chunkEntry : this.chunks.long2ObjectEntrySet())
        {
            CompoundTag tag = new CompoundTag();
            tag.putLong("pos", chunkEntry.getLongKey());
            tag.put("missing", writeCounts(chunkEntry.getValue().missing()));
            tag.put("mismatch", writeCounts(chunkEntry.getValue().mismatch()));
            list.add(tag);
        }

        entry.put("chunks", list);
        return entry;
    }

    private static ListTag writeCounts(Object2IntOpenHashMap<BlockState> counts)
    {
        ListTag list = new ListTag();

        for (var entry : counts.object2IntEntrySet())
        {
            CompoundTag tag = new CompoundTag();
            tag.put("state", NbtUtils.writeBlockState(entry.getKey()));
            tag.putInt("count", entry.getIntValue());
            list.add(tag);
        }

        return list;
    }

    private void load(Minecraft mc)
    {
        if (mc.level == null)
        {
            return;
        }

        try
        {
            Path file = cacheFile(this.worldKey + this.fileSuffix);

            if (! Files.exists(file))
            {
                return;
            }

            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            HolderGetter<Block> blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);

            for (Tag tag : root.getListOrEmpty("entries"))
            {
                if (! (tag instanceof CompoundTag entry) || ! this.signature.equals(entry.getStringOr("sig", "")))
                {
                    continue;
                }

                for (Tag chunkTag : entry.getListOrEmpty("chunks"))
                {
                    if (chunkTag instanceof CompoundTag chunk)
                    {
                        this.chunks.put(chunk.getLongOr("pos", 0L),
                                new ChunkCounts(readCounts(blocks, chunk.getListOrEmpty("missing")),
                                        readCounts(blocks, chunk.getListOrEmpty("mismatch")), null));
                    }
                }

                return;
            }
        }
        catch (Exception e)
        {
            this.chunks.clear();
        }
    }

    private static Object2IntOpenHashMap<BlockState> readCounts(HolderGetter<Block> blocks, ListTag list)
    {
        Object2IntOpenHashMap<BlockState> counts = new Object2IntOpenHashMap<>();

        for (Tag tag : list)
        {
            if (tag instanceof CompoundTag item)
            {
                BlockState state = NbtUtils.readBlockState(blocks, item.getCompoundOrEmpty("state"));
                int count = item.getIntOr("count", 0);

                if (! state.isAir() && count > 0)
                {
                    counts.addTo(state, count);
                }
            }
        }

        return counts;
    }

    private static String worldKey(Minecraft mc)
    {
        String name = StringUtils.getWorldOrServerNameOrDefault("world");
        String dimension = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";

        return sanitize(name + "_" + dimension);
    }

    private static Path cacheFile(String worldKey)
    {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("layerlist").resolve("verify-cache").resolve(worldKey + ".nbt");
    }

    private static String sanitize(String text)
    {
        StringBuilder result = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); ++i)
        {
            char c = text.charAt(i);
            result.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' ? c : '_');
        }

        return result.toString();
    }

    /**
     * Счёт чанка по состоянию (клетки с данными сложены сюда же по своему состоянию) и, если чанк проверялся в
     * этой игре, — раскладка клеток с данными по ключам; из файла кэша она не читается.
     */
    public record ChunkCounts(Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch,
                              @Nullable DataCounts data) {}

    /** Клетки с данными: недостача по ключам и та же недостача по состоянию — сколько её сидит в счёте чанка. */
    public record DataCounts(Object2IntOpenHashMap<DataItems.Key> missing, Object2IntOpenHashMap<DataItems.Key> mismatch,
                             Object2IntOpenHashMap<BlockState> foldMissing, Object2IntOpenHashMap<BlockState> foldMismatch) {}
}
