package com.aell.layerlist.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class StorageIndex
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    // Без отступов: файл индекса — тысячи сундуков, и красивый формат удваивает его и время записи
    private static final Gson GSON = new GsonBuilder().create();
    private static final long FLUSH_TIMEOUT_MS = 5000L;
    private static final Type DATA_TYPE = new TypeToken<Map<String, Rec>>(){}.getType();
    /** Глубина вложенности хранилищ в предметах: шалкер → мешочек → мешочек… */
    private static final int NESTED_DEPTH = 4;

    private static final StorageIndex INSTANCE = new StorageIndex();

    private final Map<String, Rec> records = new LinkedHashMap<>();

    /*
     * Запись файла — в своём потоке. Индекс копится годами (у игрока на серверах — 3,7 тыс. сундуков,
     * 1,3 МБ), и запись целиком в игровом потоке при каждом открытии и закрытии сундука давала фриз.
     * Игровой поток только снимает копию записей (Rec после вставки не меняются — update кладёт новый);
     * подряд идущие сохранения склеиваются в одно, пишется последняя копия.
     */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
    {
        Thread thread = new Thread(r, "layerlist-storage-save");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.atomic.AtomicReference<Map<String, Rec>> pending =
            new java.util.concurrent.atomic.AtomicReference<>();
    private boolean dirty;
    /** Растёт при каждой перемене записей: списки, которые вычитают склад, по нему узнают, что пора пересобраться. */
    private long revision;

    public static StorageIndex getInstance()
    {
        return INSTANCE;
    }

    public long revision()
    {
        return this.revision;
    }


    public static class Rec
    {
        public String scope;
        public int x;
        public int y;
        public int z;
        public Map<String, Integer> counts;
        public long updatedAt;

        public BlockPos pos()
        {
            return new BlockPos(this.x, this.y, this.z);
        }
    }

    private static Path filePath()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("layerlist_storage.json");
    }

    public void load()
    {
        Path path = filePath();

        if (! Files.exists(path))
        {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            Map<String, Rec> loaded = GSON.fromJson(reader, DATA_TYPE);
            this.records.clear();

            if (loaded != null)
            {
                for (Map.Entry<String, Rec> entry : loaded.entrySet())
                {
                    Rec rec = entry.getValue();

                    if (rec != null && rec.scope != null && rec.counts != null)
                    {
                        this.records.put(entry.getKey(), rec);
                    }
                }
            }

            ++this.revision;
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not load storage index {}", path, e);
        }
    }

    /** Сохранить, если с прошлого раза что-то изменилось; файл пишется в фоне. */
    public void save()
    {
        if (! this.dirty)
        {
            return;
        }

        this.dirty = false;

        if (this.pending.getAndSet(new LinkedHashMap<>(this.records)) == null)
        {
            this.writer.execute(this::drain);
        }
    }

    /** Дождаться записи — при выходе из игры, чтобы последнее открытие не потерялось. */
    public void flush()
    {
        this.save();

        try
        {
            this.writer.submit(() -> {}).get(FLUSH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            LOGGER.warn("Storage index was not written in time", e);
        }
    }

    private void drain()
    {
        Map<String, Rec> snapshot;

        while ((snapshot = this.pending.getAndSet(null)) != null)
        {
            write(snapshot);
        }
    }

    private static void write(Map<String, Rec> snapshot)
    {
        Path path = filePath();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(snapshot, DATA_TYPE, writer);
            }

            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not save storage index {}", path, e);

            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    public boolean update(String scope, BlockPos pos, AbstractContainerMenu menu, int storageSlots)
    {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();

        for (int slot = 0; slot < storageSlots && slot < menu.slots.size(); ++slot)
        {
            ItemStack stack = menu.getSlot(slot).getItem();

            if (! stack.isEmpty())
            {
                counts.merge(itemId(stack), stack.getCount(), Integer::sum);
                // Шалкер или мешочек в контейнере — его содержимое тоже в счёт: маркер встаёт над самим
                // контейнером, а не только над слотом шалкера, когда контейнер уже открыт
                forEachInside(stack, inner -> counts.merge(itemId(inner), inner.getCount(), Integer::sum));
            }
        }

        String key = scope + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        Rec previous = this.records.get(key);

        if (previous != null && previous.counts.equals(counts))
        {
            return false;
        }

        Rec rec = new Rec();
        rec.scope = scope;
        rec.x = pos.getX();
        rec.y = pos.getY();
        rec.z = pos.getZ();
        rec.counts = counts;
        rec.updatedAt = System.currentTimeMillis();
        this.records.put(key, rec);
        this.dirty = true;
        ++this.revision;
        return true;
    }

    public boolean has(String scope, BlockPos pos)
    {
        return this.records.containsKey(scope + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    public Rec get(String scope, BlockPos pos)
    {
        return this.records.get(scope + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }

    public boolean removeNonCanonical(String scope, BlockPos canonical, List<BlockPos> physical)
    {
        boolean changed = false;

        for (BlockPos part : physical)
        {
            if (! part.equals(canonical))
            {
                changed |= this.records.remove(
                        scope + "|" + part.getX() + "," + part.getY() + "," + part.getZ()) != null;
            }
        }

        this.dirty |= changed;
        this.revision += changed ? 1 : 0;
        return changed;
    }

    public static int expectedContainerSize(Minecraft mc, BlockPos pos)
    {
        if (mc.level == null)
        {
            return 0;
        }

        BlockState state = mc.level.getBlockState(pos);
        Block block = state.getBlock();

        if (block instanceof ChestBlock)
        {
            return state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    ? 54 : 27;
        }

        if (block instanceof BarrelBlock || block instanceof ShulkerBoxBlock)
        {
            return 27;
        }

        if (block instanceof CrafterBlock || block instanceof ChiseledBookShelfBlock
                || block instanceof JukeboxBlock || block instanceof DecoratedPotBlock
                || block instanceof CampfireBlock || block instanceof LecternBlock)
        {
            return 0;
        }

        return mc.level.getBlockEntity(pos) instanceof Container container
                ? container.getContainerSize() : 0;
    }

    public static boolean isRecordable(Minecraft mc, BlockPos pos)
    {
        return expectedContainerSize(mc, pos) > 0;
    }

    public List<Rec> recordsInScope(String scope)
    {
        ArrayList<Rec> result = new ArrayList<>();

        for (Rec rec : this.records.values())
        {
            if (scope.equals(rec.scope))
            {
                result.add(rec);
            }
        }

        return result;
    }

    public static String itemId(ItemStack stack)
    {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Всё, что лежит внутри предмета-хранилища: шалкер (и любой предмет с содержимым контейнера) или мешочек,
     * вложенные тоже — мешочек в шалкере, мешочек в мешочке, не глубже {@link #NESTED_DEPTH}. Сам предмет не
     * передаётся, только содержимое.
     */
    public static void forEachInside(ItemStack stack, java.util.function.Consumer<ItemStack> action)
    {
        forEachInside(stack, action, 0);
    }

    private static void forEachInside(ItemStack stack, java.util.function.Consumer<ItemStack> action, int depth)
    {
        if (depth >= NESTED_DEPTH)
        {
            return;
        }

        net.minecraft.core.NonNullList<ItemStack> contents;

        try
        {
            if (fi.dy.masa.malilib.util.InventoryUtils.shulkerBoxHasItems(stack))
            {
                contents = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack);
            }
            else if (stack.getItem() instanceof net.minecraft.world.item.BundleItem
                    && fi.dy.masa.malilib.util.InventoryUtils.bundleHasItems(stack))
            {
                contents = fi.dy.masa.malilib.util.InventoryUtils.getBundleItems(stack);
            }
            else
            {
                return;
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        for (ItemStack inner : contents)
        {
            if (! inner.isEmpty())
            {
                action.accept(inner);
                forEachInside(inner, action, depth + 1);
            }
        }
    }

    public static String scope(Minecraft mc)
    {
        if (mc.level == null)
        {
            return "";
        }

        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip
                : (mc.getSingleplayerServer() != null
                        ? "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName()
                        : "local");

        return server + "|" + mc.level.dimension().identifier();
    }

    public static boolean isStorage(Minecraft mc, BlockPos pos)
    {
        if (mc.level == null)
        {
            return false;
        }

        Block block = mc.level.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                || mc.level.getBlockEntity(pos) instanceof Container;
    }

    public static BlockPos canonicalPos(Minecraft mc, BlockPos pos)
    {
        if (mc.level == null)
        {
            return pos.immutable();
        }

        BlockState state = mc.level.getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
        {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
            return compare(pos, other) <= 0 ? pos.immutable() : other.immutable();
        }

        return pos.immutable();
    }

    public static List<BlockPos> physicalBlocks(Minecraft mc, BlockPos canonical)
    {
        ArrayList<BlockPos> positions = new ArrayList<>();
        positions.add(canonical);

        if (mc.level != null)
        {
            BlockState state = mc.level.getBlockState(canonical);

            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            {
                positions.add(ChestBlock.getConnectedBlockPos(canonical, state));
            }
        }

        return positions;
    }

    private static int compare(BlockPos first, BlockPos second)
    {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) { return x; }
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }
}
