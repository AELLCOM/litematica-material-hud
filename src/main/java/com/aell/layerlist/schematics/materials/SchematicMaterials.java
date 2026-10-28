package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.core.MaterialCutoff;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.MaterialRows;
import com.aell.layerlist.core.SchematicEntities;
import com.aell.layerlist.easyplace.ItemData;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBitArray;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.nbt.NbtView;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Источники строк материалов (M25): схема целиком и текущий список HUD.
 *
 * <p>Схема считается тем же конвейером, что HUD ({@link MaterialRows}): состояния блоков переводит
 * в предметы Litematica (с нашими правками {@code MaterialCache}), баннеры с узором, горшки с
 * черепками и головы с профилем — отдельными строками, сущности — по правилам
 * {@link SchematicEntities}. Поэтому таблица схемы и HUD называют и считают одно и то же одинаково.
 *
 * <p>Потоки. Счёт клеток по палитре — в фоне: это чистые данные контейнера. Всё, что трогает
 * Minecraft и Litematica (кеш {@code MaterialCache}, создание сущностей блоков и сущностей,
 * инвентарь, индекс складов), — на клиентском потоке. Будущее {@link #ofSchematic} завершается
 * на клиентском потоке; ждать его ({@code join}) на клиентском потоке нельзя — повиснет.
 */
@Environment(EnvType.CLIENT)
public final class SchematicMaterials
{
    private static final String KEY_NO_WORLD = "layerlist.schematics.materials.no_world";
    private static final String KEY_FAILED = "layerlist.schematics.materials.failed";

    /**
     * Счёт по схеме, без наличия: наличие меняется, состав схемы — нет. Ключ — объект Litematica
     * (слабо): выгрузили схему — ушёл и счёт. Только клиентский поток.
     */
    private static final Map<LitematicaSchematic, Tally> TALLIES = new WeakHashMap<>();

    private SchematicMaterials() {}

    /** Три карты счёта схемы. После сборки не меняются — строки собираются из копий. */
    private record Tally(Object2IntOpenHashMap<BlockState> blocks, Object2IntOpenHashMap<DataItems.Key> data,
                         Object2IntOpenHashMap<DataItems.Key> entities) {}

    /** Регион схемы, снятый на клиентском потоке, чтобы фону не ходить в Litematica за списками. */
    private record Region(String name, LitematicaBlockStateContainer container,
                          @Nullable Map<BlockPos, CompoundData> blockEntities,
                          @Nullable List<LitematicaSchematic.EntityInfo> entities, int offsetY) {}

    /**
     * Все материалы схемы: строки в порядке «больше всего — выше», при равенстве по названию.
     * {@code missing} = {@code total}: сверки с миром у файла нет. Проваленное будущее — с понятным
     * текстом в {@link #describe(Throwable)}: нет мира, Litematica сменила API и т. п.
     */
    public static CompletableFuture<List<MaterialRow>> ofSchematic(LoadedSchematic s)
    {
        Objects.requireNonNull(s, "schematic");
        Minecraft mc = Minecraft.getInstance();
        Job job = new Job(s.litematica());

        return CompletableFuture.supplyAsync(job::prepare, mc)
                .thenApplyAsync(Job::countStates, Util.backgroundExecutor())
                .thenApplyAsync(Job::rows, mc);
    }

    /** То же по схеме Litematica напрямую (размещение без файла библиотеки): «Подсветить все сундуки». */
    public static CompletableFuture<List<MaterialRow>> ofLitematica(LitematicaSchematic schematic)
    {
        Objects.requireNonNull(schematic, "schematic");
        Minecraft mc = Minecraft.getInstance();
        Job job = new Job(schematic);

        return CompletableFuture.supplyAsync(job::prepare, mc)
                .thenApplyAsync(Job::countStates, Util.backgroundExecutor())
                .thenApplyAsync(Job::rows, mc);
    }

    /**
     * Строки по подсчёту блоков (анализ выделенной области, вкладка «Выделение»): как у схемы, «не хватает» =
     * «всего». Только клиентский поток.
     */
    public static List<MaterialRow> ofBlocks(Object2IntOpenHashMap<BlockState> blocks)
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            throw new NoWorldException();
        }

        List<MaterialListEntry> entries = MaterialRows.build(MaterialRows.Counts.wholeMissing(blocks), MaterialRows.Counts.empty(),
                MaterialRows.Counts.empty(), player);
        List<MaterialRow> rows = toRows(entries, new Object2IntOpenHashMap<>(), Availability.capture());
        rows.sort(Comparator.comparingLong(MaterialRow::total).reversed().thenComparing(MaterialRow::name));
        return rows;
    }

    /**
     * Текущие опубликованные строки HUD (недостающее в мире), в порядке HUD. Новый список, его
     * можно сортировать. Только клиентский поток.
     */
    public static List<MaterialRow> ofHud()
    {
        LayerCountManager hud = LayerCountManager.getInstance();

        if (! hud.hasData())
        {
            return new ArrayList<>();
        }

        return toRows(hud.getAllEntries(), MaterialRows.publishedEntityTotals(), Availability.capture());
    }

    /**
     * Строки собственного счётчика размещений ({@link LayerCountManager#forPlacements}): вкладка «Материалы»
     * сверяет схему с миром по всей постройке. «Всего» — вся схема, {@code missing} — сколько ещё не поставлено;
     * порядок как у счёта схемы (больше всего — выше). Новый список, его можно сортировать. Только клиентский поток.
     */
    public static List<MaterialRow> ofCounter(LayerCountManager counter)
    {
        Objects.requireNonNull(counter, "counter");

        if (! counter.hasData())
        {
            return new ArrayList<>();
        }

        List<MaterialRow> rows = toRows(counter.getEntries(), counter.entityTotals(), Availability.capture());
        rows.sort(Comparator.comparingLong(MaterialRow::total).reversed().thenComparing(MaterialRow::name));

        return rows;
    }

    /**
     * Строка отключённого блока и исходные блоки за ней (стенной и обычный факел — одна строка «Факел»).
     *
     * @param row       строка с тем же счётом, что была у блока до отключения, и наличием сейчас
     * @param originals исходные блоки схемы, которые включает обратно кнопка строки
     * @param entities  отключённые сущности (предметы строк), которые она включает обратно (1.0.18)
     */
    public record DisabledRow(MaterialRow row, List<Block> originals, List<Item> entities)
    {
        public DisabledRow
        {
            originals = List.copyOf(originals);
            entities = List.copyOf(entities);
        }

        public DisabledRow(MaterialRow row, List<Block> originals)
        {
            this(row, originals, List.of());
        }
    }

    /**
     * Строки отключённых блоков схемы — тем же путём, что строки счёта ({@link MaterialRows#build}): число
     * в строке то же, что было до отключения. Узор баннера и прочие данные блока здесь не различаются.
     * С 1.0.18 — и отключённые сущности (рамки, стойки…, содержимое рамок): строка с тем же предметом, что у
     * блоков, — одна. Только клиентский поток.
     */
    public static List<DisabledRow> disabledRows(@Nullable LitematicaSchematic schematic)
    {
        Minecraft mc = Minecraft.getInstance();
        Map<BlockState, Long> states = BlockReplacements.disabledStates(schematic);
        // Тот же выключатель, что у счёта: сущности не считаются — и потухших строк сущностей нет
        List<BlockReplacements.DisabledEntity> offEntities = LayerListConfigs.COUNT_ENTITIES.getBooleanValue()
                ? BlockReplacements.disabledEntities(schematic) : List.of();

        if (mc.player == null || states.isEmpty() && offEntities.isEmpty())
        {
            return List.of();
        }

        Map<Block, Object2IntOpenHashMap<BlockState>> byBlock = new LinkedHashMap<>();
        states.forEach((state, count) -> byBlock.computeIfAbsent(state.getBlock(), block -> new Object2IntOpenHashMap<>())
                .put(state, (int) Math.min(Integer.MAX_VALUE, count)));

        Availability have = Availability.capture();
        Map<String, MaterialRow> rows = new LinkedHashMap<>();
        Map<String, List<Block>> originals = new LinkedHashMap<>();

        for (Map.Entry<Block, Object2IntOpenHashMap<BlockState>> pair : byBlock.entrySet())
        {
            List<MaterialListEntry> entries = MaterialRows.build(MaterialRows.Counts.wholeMissing(pair.getValue()),
                    MaterialRows.Counts.empty(), MaterialRows.Counts.empty(), mc.player);

            for (MaterialRow row : toRows(entries, new Object2IntOpenHashMap<>(), have))
            {
                rows.merge(row.key(), row, (a, b) -> new MaterialRow(a.stack(), a.key(), a.name(),
                        saturatedSum(a.total(), b.total()), saturatedSum(a.missing(), b.missing()),
                        a.inInventory(), a.inChests(), a.maxStack(), false));
                List<Block> list = originals.computeIfAbsent(row.key(), key -> new ArrayList<>());

                if (! list.contains(pair.getKey()))
                {
                    list.add(pair.getKey());
                }
            }
        }

        Map<String, List<Item>> entityCauses = new LinkedHashMap<>();

        if (! offEntities.isEmpty())
        {
            Object2IntOpenHashMap<DataItems.Key> counts = new Object2IntOpenHashMap<>();
            Map<String, List<Item>> causesByKey = new HashMap<>();

            for (BlockReplacements.DisabledEntity off : offEntities)
            {
                counts.addTo(new DataItems.Key(off.stack()), (int) Math.min(Integer.MAX_VALUE, off.count()));
                causesByKey.computeIfAbsent(ItemKeys.of(off.stack()), key -> new ArrayList<>()).addAll(off.causes());
            }

            List<MaterialListEntry> entries = MaterialRows.build(MaterialRows.Counts.empty(), MaterialRows.Counts.empty(),
                    MaterialRows.Counts.wholeMissing(counts), mc.player);

            for (MaterialRow row : toRows(entries, counts, have))
            {
                // Тот же предмет и у отключённых блоков (камень в стене и в рамке) — одна строка, уже не «сущность»
                rows.merge(row.key(), row, (a, b) -> new MaterialRow(a.stack(), a.key(), a.name(),
                        saturatedSum(a.total(), b.total()), saturatedSum(a.missing(), b.missing()),
                        a.inInventory(), a.inChests(), a.maxStack(), false));
                originals.computeIfAbsent(row.key(), key -> new ArrayList<>());
                entityCauses.put(row.key(), causesByKey.getOrDefault(row.key(), List.of()));
            }
        }

        List<DisabledRow> out = new ArrayList<>(rows.size());
        rows.forEach((key, row) -> out.add(new DisabledRow(row, originals.get(key), entityCauses.getOrDefault(key, List.of()))));
        return out;
    }

    private static long saturatedSum(long a, long b)
    {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    /** Забыть счёт этой схемы (кнопка «Обновить» после правки схемы в Litematica). Клиентский поток. */
    public static void forget(LoadedSchematic s)
    {
        TALLIES.remove(s.litematica());
    }

    /** Забыть счёт схемы Litematica (замена блоков переписала её палитру, 0.0.27). Клиентский поток. */
    public static void forget(LitematicaSchematic s)
    {
        TALLIES.remove(s);
    }

    /** Забыть счёт всех схем. Клиентский поток. */
    public static void clearCache()
    {
        TALLIES.clear();
    }

    /** Текст ошибки для игрока из провала {@link #ofSchematic}. */
    public static String describe(Throwable error)
    {
        Throwable cause = error;

        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        if (cause instanceof NoWorldException)
        {
            return cause.getMessage();
        }

        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();

        return I18n.get(KEY_FAILED, detail);
    }

    /** Строки таблицы из строк списка материалов; наличие — из снимка {@code have}. */
    static List<MaterialRow> toRows(List<MaterialListEntry> entries, Object2IntOpenHashMap<DataItems.Key> entityTotals,
                                    Availability have)
    {
        List<MaterialRow> rows = new ArrayList<>(entries.size());

        for (MaterialListEntry entry : entries)
        {
            ItemStack stack = entry.getStack();

            if (stack.isEmpty())
            {
                continue;
            }

            // Ключ строки Litematica бывает со счётом 2 (двойная плита первой) — в таблицу идёт один предмет
            ItemStack one = stack.copyWithCount(1);
            int fromEntities = entityTotals.getInt(new DataItems.Key(one));
            // Слитая строка (камень в рамке и в стене) больше счёта сущностей — это уже не «сущность»
            boolean entity = fromEntities > 0 && fromEntities >= entry.getCountTotal();

            rows.add(new MaterialRow(one, ItemKeys.of(one), DataItems.fullLabel(one), entry.getCountTotal(),
                    entry.getCountMissing(), have.inInventory(one), have.inChests(one), one.getMaxStackSize(), entity));
        }

        return rows;
    }

    private static List<MaterialRow> rows(Tally tally, LocalPlayer player)
    {
        // Тот же выключатель, что у HUD: не считаешь сущности там — не видишь их и здесь
        Object2IntOpenHashMap<DataItems.Key> entities = LayerListConfigs.COUNT_ENTITIES.getBooleanValue()
                ? tally.entities() : new Object2IntOpenHashMap<>();

        List<MaterialListEntry> entries = MaterialRows.build(
                MaterialRows.Counts.wholeMissing(tally.blocks()),
                MaterialRows.Counts.wholeMissing(tally.data()),
                MaterialRows.Counts.wholeMissing(entities),
                player);

        List<MaterialRow> rows = toRows(entries, entities, Availability.capture());
        rows.sort(Comparator.comparingLong(MaterialRow::total).reversed().thenComparing(MaterialRow::name));

        return rows;
    }

    /**
     * Клетки контейнера по номерам палитры: без {@code get(x, y, z)} и хеша на каждую клетку.
     * Воздух и пустота структуры не нужны — их и HUD пропускает.
     */
    private static void countRegion(LitematicaBlockStateContainer container, Object2LongOpenHashMap<BlockState> into,
                                    long maxLocalY)
    {
        Vec3i size = container.getSize();
        long volume = (long) size.getX() * size.getY() * size.getZ();
        LitematicaBitArray cells = container.getArray();
        ILitematicaBlockStatePalette palette = container.getPalette();
        // Клетки идут слоями по Y: нужны только первые (maxLocalY + 1) слоя
        long layer = (long) size.getX() * size.getZ();
        long end = Math.min(volume, cells.size());
        end = Math.min(end, (Math.min(maxLocalY, size.getY() - 1) + 1) * layer);
        long[] perId = new long[Math.max(1, palette.getPaletteSize())];

        for (long i = 0; i < end; ++i)
        {
            int id = cells.getAt(i);

            if (id >= perId.length)
            {
                perId = Arrays.copyOf(perId, id + 1);
            }

            ++perId[id];
        }

        for (int id = 0; id < perId.length; ++id)
        {
            if (perId[id] == 0L)
            {
                continue;
            }

            // Номер без записи в палитре Litematica читает как воздух
            BlockState state = palette.getBlockState(id);

            if (state == null || state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID)
            {
                continue;
            }

            into.addTo(state, perId[id]);
        }
    }

    /**
     * Предмет клетки с данными блока — узором, черепками, профилем. Сущность блока поднимается из
     * данных схемы так же, как её поднимает сама Litematica при вставке; в предмет переходят только
     * опознающие компоненты (как в {@code DataItems.Key}): имя баннера или содержимое горшка сделали
     * бы подпись строки чужой.
     */
    public static @Nullable ItemStack withData(BlockState state, CompoundData tag, RegistryAccess registries)
    {
        if (! (state.getBlock() instanceof EntityBlock block))
        {
            return null;
        }

        try
        {
            BlockEntity blockEntity = block.newBlockEntity(BlockPos.ZERO, state);

            if (blockEntity == null)
            {
                return null;
            }

            blockEntity.loadWithComponents(NbtView.getReader(tag, registries).getReader());
            DataComponentMap parts = blockEntity.collectComponents();
            ItemStack base = MaterialCache.getInstance().getRequiredBuildItemForState(state);

            if (base.isEmpty())
            {
                base = state.getBlock().asItem().getDefaultInstance();
            }

            if (base.isEmpty())
            {
                return null;
            }

            ItemStack stack = base.copyWithCount(1);
            copy(parts, stack, DataComponents.BANNER_PATTERNS);
            copy(parts, stack, DataComponents.POT_DECORATIONS);
            copy(parts, stack, DataComponents.PROFILE);

            return stack;
        }
        catch (RuntimeException | LinkageError e)
        {
            // Битые данные клетки — считаем её по состоянию, как дальнюю клетку в HUD
            return null;
        }
    }

    private static <T> void copy(DataComponentMap from, ItemStack to, DataComponentType<T> type)
    {
        T value = from.get(type);

        if (value != null)
        {
            to.set(type, value);
        }
    }

    /** Ошибка «нет мира»: её текст уже переведён и показывается как есть. */
    private static final class NoWorldException extends IllegalStateException
    {
        NoWorldException()
        {
            super(I18n.get(KEY_NO_WORLD));
        }
    }

    /** Один расчёт: этапы передают его друг другу (клиент → фон → клиент). */
    private static final class Job
    {
        private final LitematicaSchematic schematic;
        private final List<Region> regions = new ArrayList<>();
        /** Правка палитры заменой блоков на момент снятия регионов: сменилась за время счёта — счёт не кешируется. */
        private int generation;
        /** Граница высоты схемы; {@link Long#MAX_VALUE} — без границы. Снимается на клиентском потоке. */
        private long limitY = Long.MAX_VALUE;
        @Nullable private Tally tally;
        @Nullable private Object2LongOpenHashMap<BlockState> states;

        Job(LitematicaSchematic schematic)
        {
            this.schematic = schematic;
        }

        /** Клиентский поток: проверить мир, взять готовый счёт или снять регионы для фона. */
        Job prepare()
        {
            Minecraft mc = Minecraft.getInstance();

            // Без мира нет реестров (предметы, сущности) и игрока — считать не во что
            if (mc.level == null || mc.player == null)
            {
                throw new NoWorldException();
            }

            // Отключения (блоки и сущности) — до счёта: схему могли прочитать в другом потоке, сущности ждут мира
            BlockReplacements.ensure(this.schematic);

            this.limitY = MaterialCutoff.limitY();
            // С границей счёт другой — кэш по схеме его не отдаёт и не принимает
            this.tally = this.limitY == Long.MAX_VALUE ? TALLIES.get(this.schematic) : null;

            if (this.tally != null)
            {
                return this;
            }

            this.generation = BlockReplacements.generation(this.schematic);

            for (String name : List.copyOf(this.schematic.getAreas().keySet()))
            {
                LitematicaBlockStateContainer container = this.schematic.getSubRegionContainer(name);

                if (container != null)
                {
                    this.regions.add(new Region(name, container, this.schematic.getBlockEntityMapForRegion(name),
                            this.schematic.getEntityListForRegion(name), offsetY(name)));
                }
            }

            return this;
        }

        /** Фон: счёт клеток по состояниям. */
        Job countStates()
        {
            if (this.tally == null)
            {
                Object2LongOpenHashMap<BlockState> counted = new Object2LongOpenHashMap<>();

                for (Region region : this.regions)
                {
                    countRegion(region.container(), counted, localLimit(region));
                }

                this.states = counted;
            }

            return this;
        }

        /** Клиентский поток: клетки с данными, сущности, перевод в предметы и наличие. */
        List<MaterialRow> rows()
        {
            Minecraft mc = Minecraft.getInstance();

            if (mc.level == null || mc.player == null)
            {
                throw new NoWorldException();
            }

            if (this.tally == null)
            {
                this.tally = this.finish(mc.level);

                // Палитру переписала замена, пока фон считал клетки: счёт смешанный — в кеш его не класть
                if (this.limitY == Long.MAX_VALUE && BlockReplacements.generation(this.schematic) == this.generation)
                {
                    TALLIES.put(this.schematic, this.tally);
                }
            }

            return SchematicMaterials.rows(this.tally, mc.player);
        }

        /** Смещение региона по Y в схеме: Y клетки в схеме = смещение + локальный Y. */
        private int offsetY(String name)
        {
            BlockPos at = this.schematic.getSubRegionPosition(name);

            return at == null ? 0 : at.getY();
        }

        private long localLimit(Region region)
        {
            return this.limitY == Long.MAX_VALUE ? Long.MAX_VALUE : this.limitY - region.offsetY();
        }

        /**
         * Сущность региона попадает под границу высоты: по клетке, где стоит (как в HUD — рамка на стене слоя
         * границы ещё считается, хоть её центр выше клетки на полблока).
         */
        private boolean withinLimit(Region region, SchematicEntities.Spec spec)
        {
            return this.limitY == Long.MAX_VALUE || Math.floor(region.offsetY() + spec.posVec().y) <= this.limitY;
        }

        private Tally finish(Level level)
        {
            Object2IntOpenHashMap<BlockState> blocks = new Object2IntOpenHashMap<>();

            if (this.states != null)
            {
                for (Object2LongMap.Entry<BlockState> entry : this.states.object2LongEntrySet())
                {
                    blocks.put(entry.getKey(), (int) Math.min(Integer.MAX_VALUE, entry.getLongValue()));
                }
            }

            Object2IntOpenHashMap<DataItems.Key> data = new Object2IntOpenHashMap<>();
            RegistryAccess registries = level.registryAccess();

            for (Region region : this.regions)
            {
                if (region.blockEntities() != null)
                {
                    this.countDataCells(region, blocks, data, registries);
                }
            }

            // Невидимый свет: уровень — в данных предмета «Свет»; по состоянию Litematica слепила бы
            // все 16 уровней в одну строку (24.10.2026)
            for (BlockState state : new ArrayList<>(blocks.keySet()))
            {
                if (state.getBlock() instanceof net.minecraft.world.level.block.LightBlock)
                {
                    data.addTo(new DataItems.Key(ItemData.lightStack(state)), blocks.removeInt(state));
                }
            }

            Object2IntOpenHashMap<DataItems.Key> entities = new Object2IntOpenHashMap<>();

            for (Region region : this.regions)
            {
                if (region.entities() == null || region.entities().isEmpty())
                {
                    continue;
                }

                // Список сущностей региона — тот же объект, что держит схема: по нему specs кэширует разбор
                for (SchematicEntities.Spec spec : SchematicEntities.specs(region.entities(), level))
                {
                    if (! this.withinLimit(region, spec))
                    {
                        continue;
                    }

                    for (ItemStack item : spec.items())
                    {
                        entities.addTo(new DataItems.Key(item), 1);
                    }
                }
            }

            return new Tally(blocks, data, entities);
        }

        /**
         * Клетки, где предмет задают данные блока: из счёта по состоянию — в счёт по ключу с данными.
         * Карта сущностей блоков Litematica ключуется координатами контейнера региона.
         */
        private void countDataCells(Region region, Object2IntOpenHashMap<BlockState> blocks,
                                    Object2IntOpenHashMap<DataItems.Key> data, RegistryAccess registries)
        {
            Vec3i size = region.container().getSize();

            for (Map.Entry<BlockPos, CompoundData> cell : region.blockEntities().entrySet())
            {
                BlockPos pos = cell.getKey();

                if (pos == null || cell.getValue() == null || region.offsetY() + pos.getY() > this.limitY
                        || pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                        || pos.getX() >= size.getX() || pos.getY() >= size.getY() || pos.getZ() >= size.getZ())
                {
                    continue;
                }

                BlockState state = region.container().get(pos.getX(), pos.getY(), pos.getZ());

                if (! ItemData.matters(state))
                {
                    continue;
                }

                ItemStack item = withData(state, cell.getValue(), registries);

                if (item == null || ! DataItems.hasData(item))
                {
                    continue;
                }

                data.addTo(new DataItems.Key(item), 1);
                int left = blocks.getInt(state) - 1;

                if (left > 0)
                {
                    blocks.put(state, left);
                }
                else
                {
                    blocks.removeInt(state);
                }
            }
        }
    }
}
