package com.aell.layerlist.schematics.place;

import com.aell.layerlist.storage.StorageIndex;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Группы размещений (заявка #5 на GitHub, 15.11.2026: «строим железную дорогу, мост — 2 съезда и 19 пролётов: хочу
 * собрать их в группу, видеть, сколько материалов нужно на весь мост, и сворачивать группу в списке»). Группа — имя и
 * размещения Litematica по их постоянному id ({@link SchematicPlacement#getHashId}, Litematica хранит его в своих
 * сохранениях как {@code hash_code}): переименование, перемещение и перезаход размещение из группы не выкидывают.
 * Размещение — не больше чем в одной группе.
 *
 * <p>Группы свои у каждого мира и измерения ({@link StorageIndex#scope}) — как размещения Litematica. Файл —
 * {@code config/layerlist_placement_groups.json}. Удалённое размещение уходит из группы при следующем
 * {@link #prune}; группа без размещений исчезает. Всё — на клиентском потоке.
 */
@Environment(EnvType.CLIENT)
public final class PlacementGroups
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, List<Group>>>(){}.getType();
    public static final int MAX_NAME = 64;

    private static final PlacementGroups INSTANCE = new PlacementGroups();

    /** Группа: имя, свёрнута ли в «Размещениях», id размещений по порядку добавления. */
    public static final class Group
    {
        public String name = "";
        public boolean collapsed;
        public LinkedHashSet<String> members = new LinkedHashSet<>();

        public String name()
        {
            return this.name;
        }

        public boolean collapsed()
        {
            return this.collapsed;
        }

        public Set<String> members()
        {
            return this.members;
        }
    }

    private final Map<String, List<Group>> byScope = new LinkedHashMap<>();
    private boolean loaded;
    private long revision;

    private PlacementGroups() {}

    public static PlacementGroups get()
    {
        return INSTANCE;
    }

    /** Растёт при каждой перемене групп. */
    public long revision()
    {
        return this.revision;
    }

    public static String id(SchematicPlacement placement)
    {
        return String.valueOf(placement.getHashId());
    }

    /** Группы текущего мира и измерения, по имени. */
    public List<Group> groups()
    {
        List<Group> list = new ArrayList<>(this.current(false));
        list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    public @Nullable Group of(SchematicPlacement placement)
    {
        String id = id(placement);

        for (Group group : this.current(false))
        {
            if (group.members.contains(id))
            {
                return group;
            }
        }

        return null;
    }

    public @Nullable Group named(String name)
    {
        for (Group group : this.current(false))
        {
            if (group.name.equalsIgnoreCase(name.strip()))
            {
                return group;
            }
        }

        return null;
    }

    /** Размещения группы, что есть сейчас в мире, в порядке списка Litematica. */
    public static List<SchematicPlacement> members(Group group, Collection<SchematicPlacement> all)
    {
        List<SchematicPlacement> out = new ArrayList<>();

        for (SchematicPlacement placement : all)
        {
            if (group.members.contains(id(placement)))
            {
                out.add(placement);
            }
        }

        return out;
    }

    /** Положить размещения в группу (новую или с этим именем); из прежних групп они уходят. */
    public Group add(String name, Collection<SchematicPlacement> placements)
    {
        String clean = clean(name);
        Group target = this.named(clean);

        if (target == null)
        {
            target = new Group();
            target.name = clean;
            this.current(true).add(target);
        }

        this.removeFromAll(placements, target);

        for (SchematicPlacement placement : placements)
        {
            target.members.add(id(placement));
        }

        this.changed();
        return target;
    }

    /** Убрать размещения из их групп; опустевшая группа исчезает. */
    public void ungroup(Collection<SchematicPlacement> placements)
    {
        this.removeFromAll(placements, null);
        this.changed();
    }

    public void rename(Group group, String name)
    {
        String clean = clean(name);
        Group same = this.named(clean);

        // Имя другой группы — слить в неё
        if (same != null && same != group)
        {
            same.members.addAll(group.members);
            same.collapsed &= group.collapsed;
            this.current(true).remove(group);
        }
        else
        {
            group.name = clean;
        }

        this.changed();
    }

    /** Распустить: размещения остаются в мире, только без группы. */
    public void dissolve(Group group)
    {
        this.current(true).remove(group);
        this.changed();
    }

    public void setCollapsed(Group group, boolean collapsed)
    {
        if (group.collapsed != collapsed)
        {
            group.collapsed = collapsed;
            this.changed();
        }
    }

    /**
     * Забыть размещения, которых в мире больше нет. Только когда Litematica уже прочитала размещения измерения
     * ({@code all} не пуст): сразу после входа список бывает пуст, и группы не должны пропасть.
     */
    public void prune(Collection<SchematicPlacement> all)
    {
        if (all.isEmpty())
        {
            return;
        }

        Set<String> present = new HashSet<>();
        all.forEach(placement -> present.add(id(placement)));
        boolean changed = false;
        List<Group> groups = this.current(false);

        for (Group group : List.copyOf(groups))
        {
            changed |= group.members.retainAll(present);

            if (group.members.isEmpty())
            {
                groups.remove(group);
                changed = true;
            }
        }

        if (changed)
        {
            this.changed();
        }
    }

    private void removeFromAll(Collection<SchematicPlacement> placements, @Nullable Group keep)
    {
        Set<String> ids = new HashSet<>();
        placements.forEach(placement -> ids.add(id(placement)));
        List<Group> groups = this.current(true);

        for (Group group : List.copyOf(groups))
        {
            if (group != keep)
            {
                group.members.removeAll(ids);

                if (group.members.isEmpty())
                {
                    groups.remove(group);
                }
            }
        }
    }

    public static String clean(String name)
    {
        String clean = name.strip().replaceAll("\\s+", " ");
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }

    private List<Group> current(boolean create)
    {
        this.load();
        String scope = StorageIndex.scope(Minecraft.getInstance());

        if (scope.isEmpty())
        {
            return create ? new ArrayList<>() : List.of();
        }

        List<Group> list = this.byScope.get(scope);

        if (list == null)
        {
            if (! create)
            {
                return List.of();
            }

            list = new ArrayList<>();
            this.byScope.put(scope, list);
        }

        return list;
    }

    private void changed()
    {
        ++this.revision;
        this.byScope.values().removeIf(List::isEmpty);
        this.save();
    }

    private static Path file()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("layerlist_placement_groups.json");
    }

    private void load()
    {
        if (this.loaded)
        {
            return;
        }

        this.loaded = true;
        Path path = file();

        if (! Files.isRegularFile(path))
        {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            Map<String, List<Group>> data = GSON.fromJson(reader, DATA_TYPE);

            if (data == null)
            {
                return;
            }

            for (Map.Entry<String, List<Group>> entry : data.entrySet())
            {
                List<Group> groups = new ArrayList<>();

                for (Group group : entry.getValue() == null ? List.<Group>of() : entry.getValue())
                {
                    if (group != null && group.name != null && ! group.name.isBlank() && group.members != null)
                    {
                        groups.add(group);
                    }
                }

                if (entry.getKey() != null && ! groups.isEmpty())
                {
                    this.byScope.put(entry.getKey(), groups);
                }
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not read placement groups {}", path, e);
        }
    }

    private void save()
    {
        Path path = file();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(this.byScope, DATA_TYPE, writer);
            }

            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not save placement groups {}", path, e);
        }
    }

    @Override
    public String toString()
    {
        return String.format(Locale.ROOT, "PlacementGroups[%d scopes]", this.byScope.size());
    }
}
