package com.aell.layerlist.schematics.library;

import com.mojang.serialization.Dynamic;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Записи палитры схемы → {@link BlockState} текущей версии. Один экземпляр на один файл: версия
 * данных у файла одна, а одинаковые записи (в разных регионах, в разных палитрах) разбираются один
 * раз.
 *
 * <p>Старые записи прогоняются через ванильный DataFixer по типу {@code BLOCK_STATE} — тот же путь,
 * которым игра обновляет блоки старых миров; он потокобезопасен (игра зовёт его из рабочих потоков
 * загрузки чанков). Неизвестный блок (мод, которого нет, или опечатка) становится воздухом, но
 * запоминается — снимок честно сообщит, сколько клеток пропало.
 */
@Environment(EnvType.CLIENT)
final class BlockPalette
{
    /**
     * С этой версии данных ключи состояния в NBT — {@code id}/{@code properties}, до неё —
     * {@code Name}/{@code Properties}. Litematica 26.3 по-прежнему пишет старые ключи, а ванильные
     * структуры — новые, поэтому читаем оба и перед DataFixer приводим запись к ключам её версии.
     */
    static final int ID_KEYS_VERSION = 5006;

    private static final String NEW_NAME = "id";
    private static final String NEW_PROPERTIES = "properties";
    private static final String OLD_NAME = "Name";
    private static final String OLD_PROPERTIES = "Properties";

    /** Сколько имён неизвестных блоков запоминать для подсказки — дальше это уже не читают. */
    static final int UNKNOWN_NAMES = 8;

    private static volatile int currentVersion;

    private final int dataVersion;
    private final Map<CompoundTag, Optional<BlockState>> cache = new HashMap<>();
    private final Set<String> unknownNames = new LinkedHashSet<>();

    /** @param dataVersion версия данных файла; 0 и меньше — неизвестна, обновлять не пытаемся */
    BlockPalette(int dataVersion)
    {
        this.dataVersion = dataVersion;
    }

    static int currentDataVersion()
    {
        int version = currentVersion;

        if (version == 0)
        {
            version = SharedConstants.getCurrentVersion().dataVersion().version();
            currentVersion = version;
        }

        return version;
    }

    /** Состояние по записи палитры ({@code Name}/{@code Properties} или {@code id}/{@code properties}); {@code null} — неизвестный блок. */
    @Nullable BlockState resolve(CompoundTag entry)
    {
        Optional<BlockState> known = this.cache.get(entry);

        if (known == null)
        {
            known = Optional.ofNullable(this.parse(this.upgrade(entry)));
            this.cache.put(entry, known);
        }

        return known.orElse(null);
    }

    /** Состояние по строке Sponge: {@code minecraft:oak_log[axis=y]}; {@code null} — неизвестный блок. */
    @Nullable BlockState resolve(String state)
    {
        return this.resolve(fromString(state));
    }

    /** Имена неизвестных блоков в порядке встречи, не больше {@link #UNKNOWN_NAMES}. */
    Set<String> unknownNames()
    {
        return this.unknownNames;
    }

    /** Строка состояния → запись с ключами {@code Name}/{@code Properties} (версию приведёт {@link #upgrade}). */
    static CompoundTag fromString(String state)
    {
        CompoundTag entry = new CompoundTag();
        String text = state.trim();
        int open = text.indexOf('[');

        if (open < 0 || ! text.endsWith("]"))
        {
            entry.putString(OLD_NAME, text);

            return entry;
        }

        entry.putString(OLD_NAME, text.substring(0, open));
        CompoundTag properties = new CompoundTag();

        for (String pair : text.substring(open + 1, text.length() - 1).split(","))
        {
            int eq = pair.indexOf('=');

            if (eq > 0)
            {
                properties.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }

        if (! properties.isEmpty())
        {
            entry.put(OLD_PROPERTIES, properties);
        }

        return entry;
    }

    private CompoundTag upgrade(CompoundTag entry)
    {
        int target = currentDataVersion();

        if (this.dataVersion <= 0 || this.dataVersion >= target)
        {
            return entry;
        }

        try
        {
            CompoundTag input = withKeys(entry, this.dataVersion >= ID_KEYS_VERSION);
            Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                    new Dynamic<>(NbtOps.INSTANCE, input), this.dataVersion, target);

            return fixed.getValue() instanceof CompoundTag compound ? compound : entry;
        }
        catch (RuntimeException error)
        {
            // Фиксер не справился с записью — пробуем прочитать как есть: чаще всего имя не менялось
            return entry;
        }
    }

    /** Копия записи с ключами нужного поколения; лишнее (сторонние поля) не переносим. */
    private static CompoundTag withKeys(CompoundTag entry, boolean idKeys)
    {
        CompoundTag copy = new CompoundTag();
        String name = name(entry);
        CompoundTag properties = properties(entry);

        copy.putString(idKeys ? NEW_NAME : OLD_NAME, name == null ? "" : name);

        if (properties != null)
        {
            copy.put(idKeys ? NEW_PROPERTIES : OLD_PROPERTIES, properties.copy());
        }

        return copy;
    }

    private static @Nullable String name(CompoundTag entry)
    {
        Optional<String> name = entry.getString(NEW_NAME);

        return name.isPresent() ? name.get() : entry.getString(OLD_NAME).orElse(null);
    }

    private static @Nullable CompoundTag properties(CompoundTag entry)
    {
        Optional<CompoundTag> properties = entry.getCompound(NEW_PROPERTIES);

        return properties.isPresent() ? properties.get() : entry.getCompound(OLD_PROPERTIES).orElse(null);
    }

    private @Nullable BlockState parse(CompoundTag entry)
    {
        String name = name(entry);
        Identifier id = name == null ? null : Identifier.tryParse(name.trim());
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);

        if (block.isEmpty())
        {
            if (this.unknownNames.size() < UNKNOWN_NAMES)
            {
                this.unknownNames.add(name == null || name.isBlank() ? "?" : name);
            }

            return null;
        }

        BlockState state = block.get().defaultBlockState();
        CompoundTag properties = properties(entry);

        if (properties == null)
        {
            return state;
        }

        StateDefinition<Block, BlockState> definition = block.get().getStateDefinition();

        for (String key : properties.keySet())
        {
            Property<?> property = definition.getProperty(key);
            String value = properties.getString(key).orElse(null);

            // Свойство, которого у блока больше нет, или чужое значение — остаётся значение по умолчанию
            if (property != null && value != null)
            {
                state = with(state, property, value);
            }
        }

        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value)
    {
        Optional<T> parsed = property.getValue(value);

        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }
}
