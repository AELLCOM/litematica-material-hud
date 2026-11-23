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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Выбранные сундуки (29.10.2026): хранилища, из которых идёт счёт «В сундуках», когда включено «Только выбранные
 * сундуки». Без этого в счёт шли все контейнеры, которые игрок когда-либо открывал: сундуки друзей, общий склад
 * сервера, случайная медная решётка в чужой бочке.
 *
 * <p>Ключ — та же область, что у {@link StorageIndex} (сервер и измерение), позиция — каноническая (у двойного
 * сундука — меньшая половина), как у записей индекса: выбор и содержимое сходятся по одной позиции. Выбранный, но ни
 * разу не открытый сундук — в наборе, но в счёт даёт ноль: что в нём, мод не знает.
 *
 * <p>Файл маленький (десятки позиций), пишется сразу при каждой перемене.
 */
@Environment(EnvType.CLIENT)
public final class ChosenStorages
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, List<String>>>(){}.getType();

    private static final ChosenStorages INSTANCE = new ChosenStorages();

    private final Map<String, Set<BlockPos>> byScope = new LinkedHashMap<>();

    public static ChosenStorages getInstance()
    {
        return INSTANCE;
    }

    private ChosenStorages() {}

    private static Path filePath()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("layerlist_chosen_storages.json");
    }

    /** Выбранные в области; пустое множество, если там ничего не выбрано. Только для чтения. */
    public Set<BlockPos> in(String scope)
    {
        Set<BlockPos> set = this.byScope.get(scope);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    public int count(String scope)
    {
        Set<BlockPos> set = this.byScope.get(scope);
        return set == null ? 0 : set.size();
    }

    public boolean contains(String scope, BlockPos canonical)
    {
        Set<BlockPos> set = this.byScope.get(scope);
        return set != null && set.contains(canonical);
    }

    /** Выбрать или снять; истина — теперь выбран. */
    public boolean toggle(String scope, BlockPos canonical)
    {
        Set<BlockPos> set = this.byScope.computeIfAbsent(scope, s -> new LinkedHashSet<>());
        boolean chosen;

        if (set.remove(canonical))
        {
            chosen = false;

            if (set.isEmpty())
            {
                this.byScope.remove(scope);
            }
        }
        else
        {
            set.add(canonical.immutable());
            chosen = true;
        }

        this.changed();
        return chosen;
    }

    /**
     * Сундук сменил форму: одинарный стал двойным (или наоборот), и каноническая половина теперь другая. Отметка с любой
     * его половины переезжает на {@code canonical} — туда же, куда индекс переносит запись содержимого
     * ({@link StorageIndex#removeNonCanonical}). Без этого выбранный сундук молча выпадал из счёта: запись ушла на новую
     * половину, а отметка осталась на старой.
     */
    public void canonicalize(String scope, BlockPos canonical, List<BlockPos> physical)
    {
        Set<BlockPos> set = this.byScope.get(scope);

        if (set == null)
        {
            return;
        }

        boolean moved = false;

        for (BlockPos part : physical)
        {
            if (! part.equals(canonical) && set.remove(part))
            {
                moved = true;
            }
        }

        if (moved)
        {
            set.add(canonical.immutable());
            this.changed();
        }
    }

    public boolean remove(String scope, BlockPos canonical)
    {
        Set<BlockPos> set = this.byScope.get(scope);

        if (set == null || ! set.remove(canonical))
        {
            return false;
        }

        if (set.isEmpty())
        {
            this.byScope.remove(scope);
        }

        this.changed();
        return true;
    }

    private void changed()
    {
        // Счёт склада (панель, «В сундуках») пересобирается по ревизии индекса, подсветка поиска — на следующем тике
        StorageIndex.getInstance().touch();
        StorageSearch.getInstance().requestRefresh();
        this.save();
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
            Map<String, List<String>> loaded = GSON.fromJson(reader, DATA_TYPE);
            this.byScope.clear();

            if (loaded != null)
            {
                for (Map.Entry<String, List<String>> entry : loaded.entrySet())
                {
                    if (entry.getKey() == null || entry.getValue() == null)
                    {
                        continue;
                    }

                    Set<BlockPos> set = new LinkedHashSet<>();

                    for (String text : entry.getValue())
                    {
                        BlockPos pos = parse(text);

                        if (pos != null)
                        {
                            set.add(pos);
                        }
                    }

                    if (! set.isEmpty())
                    {
                        this.byScope.put(entry.getKey(), set);
                    }
                }
            }

            StorageIndex.getInstance().touch();
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not load chosen storages {}", path, e);
        }
    }

    private void save()
    {
        Map<String, List<String>> out = new LinkedHashMap<>();

        for (Map.Entry<String, Set<BlockPos>> entry : this.byScope.entrySet())
        {
            List<String> list = new ArrayList<>();

            for (BlockPos pos : entry.getValue())
            {
                list.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }

            out.put(entry.getKey(), list);
        }

        Path path = filePath();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(out, DATA_TYPE, writer);
            }

            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not save chosen storages {}", path, e);

            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private static BlockPos parse(String text)
    {
        if (text == null)
        {
            return null;
        }

        String[] parts = text.split(",");

        if (parts.length != 3)
        {
            return null;
        }

        try
        {
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
