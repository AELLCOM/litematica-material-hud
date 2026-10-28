package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.SchematicEntities;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.util.EntityUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Замена блоков в схеме прямо из таблицы «Материалы» (0.0.27, M25). Меняется сам объект схемы
 * Litematica — тот, что держат её размещения: голограммы перестраиваются её же средствами, список
 * материалов (наш и её) пересчитывается, Easy Place ставит новый блок. Файл схемы на диске не
 * меняется: замены хранятся отдельно ({@link ReplacementStore}) и накладываются на схему каждый раз,
 * когда её читают с диска (вход в мир, смена измерения, «Загрузить» в Litematica, наша загрузка).
 *
 * <h2>Как</h2>
 * Клетки контейнера региона — номера в палитре, палитра — список состояний. Замена переписывает только
 * палитру ({@code ILitematicaBlockStatePalette.setMapping}): номер, под которым было «дубовые ступеньки
 * [на север]», теперь значит «еловые ступеньки [на север]». Клеток это не касается вовсе, поэтому
 * замена в схеме на 41 млн блоков — те же доли миллисекунды, что и в маленькой, без прохода по клеткам
 * и без фона. Два номера палитры с одним состоянием (заменили A → B, а B в схеме и так было) Litematica
 * читает как есть; при росте палитры она переписывает её по порядку номеров, и соответствие не теряется.
 *
 * <h2>Откат</h2>
 * Для каждого региона запоминается исходная палитра — состояния из файла по номерам. Текущая палитра
 * всегда считается от исходной и набора замен «исходный блок → новый блок», а не цепочкой правок:
 * откат A не трогает клеток, где B было изначально, и возвращает ровно исходные состояния.
 *
 * <h2>Отключение на слое (1.0.12)</h2>
 * Палитрой его не сделать — блок пропадает не везде, а на одном горизонтальном слое схемы. Поэтому здесь
 * меняются сами клетки: на слое клетки, где исходно стоит этот блок, получают номер воздуха, а прежние номера
 * запоминаются ({@link LayerCells}). Откат возвращает именно номер, а не состояние: после замен в палитре
 * бывают два номера с одним состоянием, и возврат «по состоянию» сломал бы откат замены. Как и палитра,
 * клетки всегда считаются от исходных: перед наложением набора слоёв прежние клетки возвращаются на место.
 * Слой — номер от нижней клетки всей схемы (0 — нижний), регионы сдвинуты по высоте — каждый своим отступом.
 *
 * <h2>Отключение сущностей (1.0.18)</h2>
 * Рамки, стойки, картины, вагонетки, лодки и прочие учитываемые сущности ({@link SchematicEntities}) живут в схеме
 * отдельными списками по регионам. Отключить строку сущности — убрать из этих списков сущности, чей предмет — предмет
 * строки; строка содержимого рамки (карта, предмет в рамке) снимает его с рамок, а рамки остаются. Список Litematica
 * не правится на месте: в схему ставится новый список, исходный запоминается — откат ставит его копию. Какой предмет
 * у сущности, узнаётся так же, как при счёте, — созданием сущности по её данным, поэтому наложение ждёт клиентского
 * потока и мира ({@link #settleEntities}); чтение схемы из другого потока только отмечает, что ждёт.
 *
 * <h2>Потоки</h2>
 * Чтение схемы Litematica (и вызов {@link #onRead} из миксина) бывает не на клиентском потоке, поэтому
 * всё состояние — под одним замком. Действия игрока и перестройка размещений — только клиентский поток.
 */
@Environment(EnvType.CLIENT)
public final class BlockReplacements
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");
    private static final String KEY = "layerlist.schematics.materials.replace.";
    /** Раз в секунду проверить схемы размещений и «Загруженных схем» — страховка, если миксин чтения не встал. */
    private static final int POLL_TICKS = 20;
    /** Отключение на слое: клетка становится воздухом / блок «в воде» на ней сохнет. */
    private static final byte HIT_AIR = 1;
    private static final byte HIT_DRY = 2;

    private static final Object LOCK = new Object();
    /** Схема Litematica → её замены и исходные палитры; слабо: выгрузили схему — ушло и это. */
    private static final Map<LitematicaSchematic, Live> LIVE = new WeakHashMap<>();

    private static boolean registered;
    private static int pollTicks;
    /** Отключения сущностей ждут клиентского потока и мира (схему прочитали в другом потоке или без мира). */
    private static volatile boolean entitiesPending;


    private BlockReplacements() {}

    /** Почему строку таблицы нельзя заменить; {@link #NONE} — можно. */
    public enum Refusal
    {
        NONE,
        /** «Список HUD»: строки собраны из всех размещений. */
        HUD,
        /** Сущность схемы (рамка, стойка) или содержимое рамки: заменить нельзя, отключить можно. */
        ENTITY,
        /** Не блок: ведро, яйцо призыва… */
        NOT_BLOCK,
        /** Предмет-блок, но отдельных таких блоков в схеме нет (растение в горшке, нить растяжки…). */
        INSIDE,
        /** Схема без файла: замену негде запомнить. */
        NO_FILE,
        /** Схема ещё не загружена. */
        NO_SCHEMATIC,
        /** Вода или лава (строка — ведро): отключить можно, заменить на другой блок — нет. */
        FLUID
    }

    /**
     * Строка таблицы и замены.
     *
     * @param refusal        можно ли заменить и если нет — почему
     * @param off            можно ли отключить и если нет — почему (у воды замены нет, а отключение есть;
     *                       в «Списке HUD» отключение идёт во все схемы размещений, замена — нет)
     * @param replacedFrom   исходные блоки, которые теперь стоят этой строкой (для «← …» и отката)
     * @param layersOff      слои схемы (0 — нижний), где блок строки отключён, по возрастанию
     * @param layerOriginals исходные блоки строки, отключённые на слоях (для «включить на слое»)
     */
    public record RowState(Refusal refusal, Refusal off, List<Block> replacedFrom, List<Integer> layersOff,
                           List<Block> layerOriginals)
    {
        public static final RowState HUD = new RowState(Refusal.HUD, List.of());

        public RowState
        {
            replacedFrom = List.copyOf(replacedFrom);
            layersOff = List.copyOf(layersOff);
            layerOriginals = List.copyOf(layerOriginals);
        }

        /** Отключение — по той же причине, что и замена (у воды, лавы и сущностей оно есть, хоть замены нет). */
        public RowState(Refusal refusal, List<Block> replacedFrom, List<Integer> layersOff, List<Block> layerOriginals)
        {
            this(refusal, refusal == Refusal.FLUID || refusal == Refusal.ENTITY ? Refusal.NONE : refusal, replacedFrom,
                    layersOff, layerOriginals);
        }

        public RowState(Refusal refusal, List<Block> replacedFrom)
        {
            this(refusal, replacedFrom, List.of(), List.of());
        }

        public boolean replaceable()
        {
            return this.refusal == Refusal.NONE;
        }

        /** Строку можно отключить: целиком, на слое — в схеме; в «Списке HUD» — во всех схемах размещений. */
        public boolean disableable()
        {
            return this.off == Refusal.NONE;
        }

        /** Почему нельзя заменить — для подсказки и тоста; у заменяемой строки — пусто. */
        public String reason(String name)
        {
            return why(this.refusal, name);
        }

        /** Почему нельзя отключить; у строки, которую можно, — пусто. */
        public String offReason(String name)
        {
            return this.off == Refusal.HUD ? I18n.get(KEY + "why.hud_off") : why(this.off, name);
        }

        private static String why(Refusal refusal, String name)
        {
            return switch (refusal)
            {
                case NONE -> "";
                case HUD -> I18n.get(KEY + "why.hud");
                case ENTITY -> I18n.get(KEY + "why.entity");
                case NOT_BLOCK -> I18n.get(KEY + "why.not_block", name);
                case INSIDE -> I18n.get(KEY + "why.inside", name);
                case NO_FILE -> I18n.get(KEY + "why.no_file");
                case NO_SCHEMATIC -> I18n.get(KEY + "why.no_schematic");
                case FLUID -> I18n.get(KEY + "why.fluid", name);
            };
        }
    }

    /**
     * Итог действия игрока.
     *
     * @param error   текст для игрока; пусто — получилось
     * @param changed исходные блоки, чьи замены поменялись
     */
    public record Outcome(String error, List<Block> changed)
    {
        public Outcome
        {
            changed = List.copyOf(changed);
        }

        static Outcome failed(String key, Object... args)
        {
            return new Outcome(I18n.get(KEY + key, args), List.of());
        }

        public boolean ok()
        {
            return this.error.isEmpty();
        }
    }

    /** Замены и исходные палитры одной схемы. */
    private static final class Live
    {
        /** Файл схемы; меняется, когда его переименовали в библиотеке ({@link #moved}). */
        private @Nullable Path file;
        /** Имя региона → контейнер и его исходная палитра (номер → состояние из файла). */
        private final Map<String, Region> regions = new HashMap<>();
        private Map<Block, Block> applied = Map.of();
        private String signature = "";
        private int generation;
        /** Кеш {@link #disabledStates}: для какой правки ({@code generation}) посчитан. */
        private @Nullable Map<BlockState, Long> disabled;
        private int disabledFor = -1;
        /** Отключения на слоях: исходный блок → слои (0 — нижний). */
        private Map<Block, Set<Integer>> layers = Map.of();
        /** Регион → клетки, которым отключение на слое поставило воздух, с их прежними номерами. */
        private final Map<String, LayerCells> layerCells = new HashMap<>();
        /** Отключённые сущности: предметы строк, чьи сущности (или содержимое рамок) убраны из схемы. */
        private Set<Item> entities = Set.of();
        /** То, что сейчас стоит в списках схемы: {@link #entities} ждёт мира, пока его нет. */
        private Set<Item> entitiesApplied = Set.of();
        /** Регион → исходный список сущностей (копия того, что прочитала Litematica). */
        private final Map<String, List<LitematicaSchematic.EntityInfo>> entityOriginal = new HashMap<>();
        /** Регион → список, который сейчас стоит в схеме: Litematica поставила другой — исходный снимается заново. */
        private final Map<String, List<LitematicaSchematic.EntityInfo>> entitySeen = new HashMap<>();
        /** Регион → предметы исходных сущностей по номеру в {@link #entityOriginal}; считаются в мире, лениво. */
        private final Map<String, List<EntityItems>> entityItems = new HashMap<>();
        /** Исходная рамка → она же без содержимого: один объект на рамку, чтобы списки сравнивались по ссылкам. */
        private final Map<LitematicaSchematic.EntityInfo, LitematicaSchematic.EntityInfo> entityStripped = new IdentityHashMap<>();
        /** Кеш {@link #disabledEntities}; сбрасывается правкой сущностей. */
        private @Nullable List<DisabledEntity> disabledEntities;

        private Live(@Nullable Path file)
        {
            this.file = file;
        }
    }

    private record Region(LitematicaBlockStateContainer container, List<BlockState> original) {}

    /**
     * Предметы сущности схемы — те же, что у счёта ({@link SchematicEntities#items}): {@code own} — сама сущность
     * (рамка, стойка…; пусто — сущность не учитывается), {@code content} — содержимое рамки (пусто — нет).
     */
    private record EntityItems(ItemStack own, ItemStack content)
    {
        private static final EntityItems NONE = new EntityItems(ItemStack.EMPTY, ItemStack.EMPTY);
    }

    /** Клетки региона, отключённые на слоях: номер клетки → прежний номер палитры. */
    private record LayerCells(LitematicaBlockStateContainer container, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap cells) {}

    /** Копия набора правок на время действия игрока: замены и отключения на слоях. */
    private static final class Changes
    {
        private final Map<Block, Block> map;
        private final Map<Block, Set<Integer>> layers;
        /** Отключённые сущности (предметы строк). */
        private final Set<Item> entities;
        /** Правка тронула сущности — блоков в итоге может и не быть. */
        private boolean entitiesTouched;

        private Changes(Map<Block, Block> map, Map<Block, Set<Integer>> layers, Set<Item> entities)
        {
            this.map = new LinkedHashMap<>(map);
            this.layers = new LinkedHashMap<>();
            layers.forEach((block, set) -> this.layers.put(block, new java.util.TreeSet<>(set)));
            this.entities = new LinkedHashSet<>(entities);
        }
    }

    // ===== Подключение =====

    /** Страховочный опрос схем размещений (раз в секунду). Зовётся один раз из {@code SchematicsModule.init}. */
    public static void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(BlockReplacements::poll);
    }

    /**
     * Litematica прочитала схему с диска (миксин на её чтение файла): наложить сохранённые замены.
     * Любой поток. Тот же объект прочитан заново — прежние палитры недействительны, всё с начала.
     */
    public static void onRead(LitematicaSchematic schematic)
    {
        boolean reread;

        synchronized (LOCK)
        {
            Live before = LIVE.remove(schematic);
            reread = before != null && (! before.applied.isEmpty() || ! before.layers.isEmpty() || ! before.entities.isEmpty());
            ensureLocked(schematic);
        }

        // Перечитанный объект уже мог стоять в размещениях — их голограммы надо перестроить
        if (reread)
        {
            Minecraft.getInstance().execute(() -> refresh(schematic));
        }
    }

    /**
     * Файл схемы переименовали в библиотеке: замены и отключения едут за ним — запись на диске и уже
     * загруженные схемы этого файла (иначе следующая правка легла бы под старое имя). Клиентский поток.
     */
    public static void moved(Path from, Path to)
    {
        Path old = from.toAbsolutePath().normalize();
        Path now = to.toAbsolutePath().normalize();

        synchronized (LOCK)
        {
            for (Live live : LIVE.values())
            {
                if (old.equals(live.file))
                {
                    live.file = now;
                }
            }

            ReplacementStore.get().move(old, now);
        }
    }

    /**
     * Схема загружена своим путём (каталог «Схем»): наложить сохранённые замены, если ещё нет. Клиентский
     * поток; уже знакомая схема — ничего.
     */
    public static void ensure(LitematicaSchematic schematic)
    {
        boolean applied;

        synchronized (LOCK)
        {
            applied = ensureLocked(schematic);
        }

        applied |= settleEntities(schematic);

        if (applied)
        {
            refresh(schematic);
        }
    }

    private static void poll(Minecraft mc)
    {
        if (mc.level == null)
        {
            return;
        }


        // Ждут отключения сущностей — не ждать секунды: голограммы отключённых висели бы зря
        boolean pending = entitiesPending;

        if (! pending && ++pollTicks < POLL_TICKS)
        {
            return;
        }

        pollTicks = 0;
        entitiesPending = false;
        Set<LitematicaSchematic> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                LitematicaSchematic schematic = placement.getSchematic();

                if (schematic != null && seen.add(schematic))
                {
                    ensure(schematic);
                }
            }

            for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics())
            {
                if (schematic != null && seen.add(schematic))
                {
                    ensure(schematic);
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Страховка не должна ронять тик: миксин чтения делает то же самое раньше
            LOGGER.debug("Block replacement poll failed", e);
        }
    }

    // ===== Чтение состояния (UI, HUD, харнесс) =====

    /**
     * Состояние строк таблицы одной схемы: какие можно заменить и что в них уже заменено. Клиентский
     * поток; палитры просматриваются один раз на весь список.
     *
     * @param schematic схема источника; {@code null} — ещё не загружена
     * @param hud       источник — «Список HUD»
     * @return ключ строки ({@link MaterialRow#key()}) → состояние
     */
    public static Map<String, RowState> rows(@Nullable LitematicaSchematic schematic, List<MaterialRow> rows, boolean hud)
    {
        Map<String, RowState> out = new HashMap<>();

        if (hud)
        {
            rows.forEach(row -> out.put(row.key(), RowState.HUD));
            return out;
        }

        if (schematic == null)
        {
            rows.forEach(row -> out.put(row.key(), new RowState(Refusal.NO_SCHEMATIC, List.of())));
            return out;
        }

        Set<Item> placed = new HashSet<>();
        // Строками списка (как их считает Litematica): тело лозы — ягоды, растение в горшке, нить, грядка — земля…
        Set<Item> counted = new HashSet<>();
        Map<Block, Set<Item>> byBlock;
        Set<Item> inEntities;
        Map<Block, Block> applied;
        Map<Block, Set<Integer>> layers;
        boolean file;

        synchronized (LOCK)
        {
            // Обычно схема уже знакома (миксин чтения, MaterialsJob зовёт ensure до счёта) — тут ничего
            ensureLocked(schematic);
            Live live = LIVE.get(schematic);
            applied = live == null ? Map.of() : live.applied;
            layers = live == null ? Map.of() : live.layers;
            file = live != null && live.file != null;

            for (BlockState state : currentStates(schematic))
            {
                placed.add(rowItemOf(state.getBlock()));
                counted.addAll(cellItems(state));

                // Блок «в воде» — это ещё и ведро воды в списке (так считает Litematica)
                if (waterlogged(state))
                {
                    placed.add(Items.WATER_BUCKET);
                }
            }

            byBlock = itemsByBlock(schematic, live);

            inEntities = live == null ? Set.of() : entityItemsPresent(schematic, live);
        }

        for (MaterialRow row : rows)
        {
            Item item = row.stack().getItem();
            List<Block> from = new ArrayList<>();

            for (Map.Entry<Block, Block> pair : applied.entrySet())
            {
                if (pair.getValue().asItem() == item)
                {
                    from.add(pair.getKey());
                }
            }

            // Предмет есть у сущностей схемы (сама сущность или содержимое рамки) — заменить нельзя, отключить можно
            Refusal entity = inEntities.contains(item) ? (file ? Refusal.ENTITY : Refusal.NO_FILE) : null;
            Refusal refusal = row.entity() ? (entity != null ? entity : Refusal.INSIDE)
                    : ! (item instanceof BlockItem) && ! fluid(item) ? (entity != null ? entity : Refusal.NOT_BLOCK)
                    : ! placed.contains(item) ? (entity != null ? entity : Refusal.INSIDE)
                    : ! file ? Refusal.NO_FILE
                    : fluid(item) ? Refusal.FLUID
                    : Refusal.NONE;
            // Отключить можно любую строку, чьи клетки есть в схеме: заменить — только блок своим предметом
            // (тело лозы или растение в горшке на другой блок не заменить), а убрать — что угодно
            Refusal offRefusal = refusal == Refusal.FLUID || refusal == Refusal.ENTITY ? Refusal.NONE
                    : refusal != Refusal.INSIDE && refusal != Refusal.NOT_BLOCK ? refusal
                    : counted.contains(item) ? (file ? Refusal.NONE : Refusal.NO_FILE)
                    : refusal;

            // Отключено на слоях: исходные блоки, что сейчас стоят этой строкой
            Set<Integer> layersOff = new java.util.TreeSet<>();
            List<Block> layerOriginals = new ArrayList<>();

            for (Map.Entry<Block, Set<Integer>> pair : layers.entrySet())
            {
                if (blockMakes(byBlock, applied.getOrDefault(pair.getKey(), pair.getKey()), item))
                {
                    layersOff.addAll(pair.getValue());
                    layerOriginals.add(pair.getKey());
                }
            }

            out.put(row.key(), new RowState(refusal, offRefusal, from, new ArrayList<>(layersOff), layerOriginals));
        }

        return out;
    }

    /** Действующие замены схемы: исходный блок → новый. */
    public static Map<Block, Block> active(@Nullable LitematicaSchematic schematic)
    {
        synchronized (LOCK)
        {
            Live live = schematic == null ? null : LIVE.get(schematic);
            return live == null ? Map.of() : new LinkedHashMap<>(live.applied);
        }
    }

    /** Счётчик правок палитры схемы: сменился — посчитанное по ней раньше устарело. */
    public static int generation(@Nullable LitematicaSchematic schematic)
    {
        synchronized (LOCK)
        {
            Live live = schematic == null ? null : LIVE.get(schematic);
            return live == null ? 0 : live.generation;
        }
    }

    /**
     * Метка замен для ключа кэша проверки HUD ({@code VerifyCache}): без замен — пусто, с заменами —
     * своя метка на каждый набор (дальние чанки, посчитанные до замены, иначе показывали бы старое).
     */
    public static String signature(@Nullable LitematicaSchematic schematic)
    {
        synchronized (LOCK)
        {
            Live live = schematic == null ? null : LIVE.get(schematic);
            return live == null ? "" : live.signature;
        }
    }

    // ===== Действия игрока =====

    /**
     * Заменить все блоки строки {@code rowItem} (предмет строки таблицы) на {@code target}. Считается от
     * исходных блоков: каждый исходный блок, который сейчас стоит этой строкой, получает новую цель;
     * цель, равная исходному блоку, — это откат его замены. Клиентский поток.
     */
    public static Outcome replace(LitematicaSchematic schematic, Item rowItem, Block target)
    {
        Objects.requireNonNull(schematic, "schematic");

        if (! ReplacementStates.main(target))
        {
            return Outcome.failed("error.target", ReplacementStates.name(target));
        }

        return edit(schematic, map ->
        {
            List<Block> changed = new ArrayList<>();

            for (Block original : originalBlocks(schematic))
            {
                Block current = map.getOrDefault(original, original);

                if (current.asItem() != rowItem)
                {
                    continue;
                }

                Block next = ReplacementStates.variant(current, target);

                if (next == original)
                {
                    map.remove(original);
                }
                else
                {
                    map.put(original, next);
                }

                changed.add(original);
            }

            return changed;
        });
    }

    /** Вернуть исходные блоки всех замен, которые сейчас стоят строкой {@code rowItem}. Клиентский поток. */
    public static Outcome revert(LitematicaSchematic schematic, Item rowItem)
    {
        return edit(schematic, map ->
        {
            List<Block> changed = new ArrayList<>();
            map.entrySet().removeIf(pair ->
            {
                boolean hit = pair.getValue().asItem() == rowItem;

                if (hit)
                {
                    changed.add(pair.getKey());
                }

                return hit;
            });

            return changed;
        });
    }

    /**
     * Отключить блок строки: в схеме он становится воздухом — голограммы, список и Easy Place его больше не
     * видят. Файл схемы не меняется; вернуть — {@link #revertOne} (золотая кнопка строки) или {@link #revertAll}.
     * Клиентский поток.
     */
    public static Outcome disable(LitematicaSchematic schematic, Item rowItem)
    {
        Objects.requireNonNull(schematic, "schematic");

        return editAll(schematic, changes ->
        {
            Map<Block, Block> map = changes.map;
            List<Block> changed = new ArrayList<>();

            // Сущности строки (рамки, стойки…) и содержимое рамок — вон из схемы вместе с блоками того же предмета
            Live live = LIVE.get(schematic);

            if (live != null && entityItemsPresent(schematic, live).contains(rowItem) && changes.entities.add(rowItem))
            {
                changes.entitiesTouched = true;
            }

            Map<Block, Set<Item>> byBlock = itemsByBlock(schematic, live);

            for (Block original : originalBlocks(schematic))
            {
                Block current = map.getOrDefault(original, original);

                if (! current.defaultBlockState().isAir() && blockMakes(byBlock, current, rowItem))
                {
                    map.put(original, Blocks.AIR);
                    changed.add(original);
                }
            }

            // Ведро воды — это и блоки «в воде»: «вода → воздух» снимает с них воду (см. apply), даже когда
            // отдельной воды в схеме нет
            if (rowItem == Items.WATER_BUCKET && ! changed.contains(Blocks.WATER) && ! off(map, Blocks.WATER)
                    && hasWaterlogged(schematic, map))
            {
                map.put(Blocks.WATER, Blocks.AIR);
                changed.add(Blocks.WATER);
            }

            return changed;
        });
    }

    // ===== «Список HUD»: отключение во всех схемах размещений =====

    /**
     * Схемы включённых размещений — из них HUD собирает «Список HUD», без повторов (у нескольких размещений
     * бывает один объект схемы). Клиентский поток.
     */
    public static List<LitematicaSchematic> hudSchematics()
    {
        Map<LitematicaSchematic, Boolean> seen = new IdentityHashMap<>();
        List<LitematicaSchematic> out = new ArrayList<>();

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                LitematicaSchematic schematic = placement == null || ! placement.isEnabled() ? null : placement.getSchematic();

                if (schematic != null && seen.put(schematic, Boolean.TRUE) == null)
                {
                    ensure(schematic);
                    out.add(schematic);
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.debug("Could not list placement schematics", e);
        }

        return out;
    }

    /**
     * Состояние строк «Списка HUD». Заменить нельзя (строки собраны из всех размещений), а отключить можно,
     * если строку можно отключить хоть в одной схеме {@code schematics}, — отключится во всех, где она есть
     * ({@link #disableAll}). Клиентский поток.
     */
    public static Map<String, RowState> hudRows(List<MaterialRow> rows, List<LitematicaSchematic> schematics)
    {
        List<Map<String, RowState>> each = new ArrayList<>(schematics.size());

        for (LitematicaSchematic schematic : schematics)
        {
            each.add(rows(schematic, rows, false));
        }

        Map<String, RowState> out = new HashMap<>();

        for (MaterialRow row : rows)
        {
            // Нет ни одной схемы — «нечего отключать»; «нет в этой схеме» уступает настоящей причине из другой
            Refusal off = Refusal.HUD;

            for (Map<String, RowState> states : each)
            {
                RowState state = states.get(row.key());

                if (state == null)
                {
                    continue;
                }

                if (state.disableable())
                {
                    off = Refusal.NONE;
                    break;
                }

                if (off == Refusal.HUD || off == Refusal.INSIDE)
                {
                    off = state.off();
                }
            }

            out.put(row.key(), new RowState(Refusal.HUD, off, List.of(), List.of(), List.of()));
        }

        return out;
    }

    /**
     * Отключить строку {@code row} во всех схемах {@code schematics}, где её можно отключить («Список HUD»).
     * Получилось хоть в одной — успех. Клиентский поток.
     */
    public static Outcome disableAll(List<LitematicaSchematic> schematics, MaterialRow row)
    {
        List<Block> changed = new ArrayList<>();
        String error = null;
        // Успех — хоть одна схема: у сущностей блоков в итоге нет, поэтому не по списку блоков
        boolean any = false;

        for (LitematicaSchematic schematic : schematics)
        {
            RowState state = rows(schematic, List.of(row), false).get(row.key());

            if (state == null || ! state.disableable())
            {
                continue;
            }

            Outcome outcome = disable(schematic, row.stack().getItem());

            if (outcome.ok())
            {
                changed.addAll(outcome.changed());
                any = true;
            }
            else if (error == null)
            {
                error = outcome.error();
            }
        }

        if (any)
        {
            return new Outcome("", changed);
        }

        return error != null ? new Outcome(error, List.of()) : Outcome.failed("error.nothing");
    }

    /** Включить обратно отключённые блоки в нескольких схемах разом: схема → её исходные блоки. Клиентский поток. */
    public static Outcome enableAll(Map<LitematicaSchematic, ? extends Collection<Block>> originals)
    {
        Map<LitematicaSchematic, SchematicMaterials.DisabledRow> rows = new IdentityHashMap<>();
        MaterialRow none = new MaterialRow(ItemStack.EMPTY, "", "", 0L, 0L, 0L, 0L, 1, false);
        originals.forEach((schematic, blocks) -> rows.put(schematic, new SchematicMaterials.DisabledRow(none, List.copyOf(blocks))));
        return enableRows(rows);
    }

    /**
     * Включить обратно потухшую строку «Списка HUD» во всех схемах: схема → её строка отключённого (блоки и сущности).
     * Получилось хоть в одной — успех. Клиентский поток.
     */
    public static Outcome enableRows(Map<LitematicaSchematic, SchematicMaterials.DisabledRow> rows)
    {
        List<Block> changed = new ArrayList<>();
        String error = null;
        boolean any = false;

        for (Map.Entry<LitematicaSchematic, SchematicMaterials.DisabledRow> pair : rows.entrySet())
        {
            Outcome outcome = enable(pair.getKey(), pair.getValue().originals(), pair.getValue().entities());

            if (outcome.ok())
            {
                changed.addAll(outcome.changed());
                any = true;
            }
            else if (error == null)
            {
                error = outcome.error();
            }
        }

        if (any)
        {
            return new Outcome("", changed);
        }

        return error != null ? new Outcome(error, List.of()) : Outcome.failed("error.nothing");
    }

    // ===== Отключение на слое (1.0.12) =====

    /** Сколько слоёв в схеме: от нижней клетки всех регионов до верхней. Схемы нет — 0. */
    public static int layerCount(@Nullable LitematicaSchematic schematic)
    {
        if (schematic == null)
        {
            return 0;
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            min = Math.min(min, regionMinY(schematic, name));
            max = Math.max(max, regionMaxY(schematic, name));
        }

        return min > max ? 0 : max - min + 1;
    }

    /** Высота слоя 0 в координатах схемы: мировой Y слоя = Y начала размещения + это + номер слоя. */
    public static int layerBase(@Nullable LitematicaSchematic schematic)
    {
        return schematic == null ? 0 : minLayerY(schematic);
    }

    /**
     * Сколько клеток строки {@code rowItem} сейчас стоит на слое {@code layer} (0 — нижний) — столько и пропадёт
     * от «отключить на слое». Уже отключённые там клетки — воздух, их нет. Клиентский поток.
     */
    public static long countOnLayer(@Nullable LitematicaSchematic schematic, Item rowItem, int layer)
    {
        if (schematic == null || layer < 0)
        {
            return 0L;
        }

        synchronized (LOCK)
        {
            ensureLocked(schematic);
            int base = minLayerY(schematic);
            long count = 0L;

            for (String name : List.copyOf(schematic.getAreas().keySet()))
            {
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

                if (container == null)
                {
                    continue;
                }

                net.minecraft.core.Vec3i size = container.getSize();
                int localY = base + layer - regionMinY(schematic, name);

                if (localY < 0 || localY >= size.getY())
                {
                    continue;
                }

                ILitematicaBlockStatePalette palette = container.getPalette();
                boolean[] hit = new boolean[palette.getPaletteSize()];

                for (int id = 0; id < hit.length; ++id)
                {
                    hit[id] = makes(stateAt(palette, id), rowItem);
                }

                long layerSize = (long) size.getX() * size.getZ();
                var cells = container.getArray();
                long from = localY * layerSize;
                long to = Math.min(from + layerSize, cells.size());

                for (long index = from; index < to; ++index)
                {
                    int id = cells.getAt(index);

                    if (id >= 0 && id < hit.length && hit[id])
                    {
                        ++count;
                    }
                }
            }

            return count;
        }
    }

    /**
     * Отключить блок строки на одном слое схемы (0 — нижний): там он станет воздухом — голограммы, список и
     * Easy Place его не видят, на остальных слоях всё как было. Файл схемы не меняется. Клиентский поток.
     */
    public static Outcome disableLayer(LitematicaSchematic schematic, Item rowItem, int layer)
    {
        Objects.requireNonNull(schematic, "schematic");

        if (layer < 0 || layer >= layerCount(schematic))
        {
            return Outcome.failed("error.layer_range", layer + 1, layerCount(schematic));
        }

        if (countOnLayer(schematic, rowItem, layer) == 0L)
        {
            return Outcome.failed("error.layer_empty", layer + 1);
        }

        return editAll(schematic, changes ->
        {
            List<Block> changed = new ArrayList<>();
            Map<Block, Set<Item>> byBlock = itemsByBlock(schematic, LIVE.get(schematic));

            for (Block original : originalBlocks(schematic))
            {
                Block current = changes.map.getOrDefault(original, original);

                if (! current.defaultBlockState().isAir() && blockMakes(byBlock, current, rowItem)
                        && changes.layers.computeIfAbsent(original, block -> new java.util.TreeSet<>()).add(layer))
                {
                    changed.add(original);
                }
            }

            // Вода на слое — и блоки «в воде» на нём (disableCells ставит им сухое состояние)
            if (rowItem == Items.WATER_BUCKET && ! changed.contains(Blocks.WATER) && ! off(changes.map, Blocks.WATER)
                    && hasWaterlogged(schematic, changes.map)
                    && changes.layers.computeIfAbsent(Blocks.WATER, block -> new java.util.TreeSet<>()).add(layer))
            {
                changed.add(Blocks.WATER);
            }

            return changed;
        });
    }

    /** Включить обратно на слое {@code layer} исходные блоки {@code originals}. Клиентский поток. */
    public static Outcome enableLayer(LitematicaSchematic schematic, Collection<Block> originals, int layer)
    {
        return editAll(schematic, changes ->
        {
            List<Block> changed = new ArrayList<>();

            for (Block original : originals)
            {
                Set<Integer> set = changes.layers.get(original);

                if (set != null && set.remove(layer))
                {
                    changed.add(original);
                }
            }

            return changed;
        });
    }

    /** Включить обратно на всех слоях исходные блоки {@code originals}. Клиентский поток. */
    public static Outcome enableLayers(LitematicaSchematic schematic, Collection<Block> originals)
    {
        return editAll(schematic, changes ->
        {
            List<Block> changed = new ArrayList<>();

            for (Block original : originals)
            {
                if (changes.layers.remove(original) != null)
                {
                    changed.add(original);
                }
            }

            return changed;
        });
    }

    /** Отключения на слоях схемы: исходный блок → слои (0 — нижний). Любой поток. */
    public static Map<Block, Set<Integer>> layersOff(@Nullable LitematicaSchematic schematic)
    {
        synchronized (LOCK)
        {
            Live live = schematic == null ? null : LIVE.get(schematic);
            return live == null ? Map.of() : new LinkedHashMap<>(live.layers);
        }
    }

    /** Вернуть схему в исходное состояние: все замены и отключения разом. Клиентский поток. */
    public static Outcome revertAll(LitematicaSchematic schematic)
    {
        return editAll(schematic, changes ->
        {
            Set<Block> changed = new LinkedHashSet<>(changes.map.keySet());
            changed.addAll(changes.layers.keySet());
            changes.map.clear();
            changes.layers.clear();
            changes.entitiesTouched = ! changes.entities.isEmpty();
            changes.entities.clear();
            return new ArrayList<>(changed);
        });
    }

    /** Отключённый блок схемы и сколько его клеток в схеме (по исходной палитре). */
    public record Disabled(Block original, long count) {}

    /**
     * Строка отключённых сущностей: предмет (с теми же данными, что у строки счёта), сколько его было в схеме и
     * какие отключения его убрали ({@code causes}: стойка — сама стойка; содержимое рамки — оно само или рамка).
     */
    public record DisabledEntity(ItemStack stack, long count, List<Item> causes)
    {
        public DisabledEntity
        {
            causes = List.copyOf(causes);
        }
    }

    /** Отключённые предметы сущностей схемы. Любой поток (то, что поставлено сейчас). */
    public static Set<Item> disabledEntityItems(@Nullable LitematicaSchematic schematic)
    {
        synchronized (LOCK)
        {
            Live live = schematic == null ? null : LIVE.get(schematic);
            return live == null ? Set.of() : live.entities;
        }
    }

    /**
     * Что отключение сущностей убрало из схемы — по исходным спискам, тем же предметом, что считает таблица: число в
     * потухшей строке то же, что было до отключения. Клиентский поток (предметы узнаются созданием сущности).
     */
    public static List<DisabledEntity> disabledEntities(@Nullable LitematicaSchematic schematic)
    {
        Level level = Minecraft.getInstance().level;

        if (schematic == null || level == null || ! Minecraft.getInstance().isSameThread())
        {
            return List.of();
        }

        synchronized (LOCK)
        {
            Live live = LIVE.get(schematic);

            if (live == null || live.entities.isEmpty())
            {
                return List.of();
            }

            if (live.disabledEntities != null)
            {
                return live.disabledEntities;
            }

            captureEntities(schematic, live);
            Set<Item> off = live.entities;
            // Ключ строки → предмет, счёт, причины
            Map<String, ItemStack> stacks = new LinkedHashMap<>();
            Map<String, Long> counts = new HashMap<>();
            Map<String, Set<Item>> causes = new HashMap<>();

            for (String name : live.entityOriginal.keySet())
            {
                for (EntityItems one : entityItems(live, name, level))
                {
                    if (one.own().isEmpty())
                    {
                        continue;
                    }

                    Item own = one.own().getItem();

                    if (off.contains(own))
                    {
                        addDisabled(stacks, counts, causes, one.own(), own);

                        if (! one.content().isEmpty())
                        {
                            addDisabled(stacks, counts, causes, one.content(), own);

                            // Содержимое отключено и само — кнопка строки вернёт и его, а не одни рамки
                            if (off.contains(one.content().getItem()))
                            {
                                causes.get(ItemKeys.of(one.content())).add(one.content().getItem());
                            }
                        }
                    }
                    else if (! one.content().isEmpty() && off.contains(one.content().getItem()))
                    {
                        addDisabled(stacks, counts, causes, one.content(), one.content().getItem());
                    }
                }
            }

            List<DisabledEntity> out = new ArrayList<>(stacks.size());
            stacks.forEach((key, stack) -> out.add(new DisabledEntity(stack, counts.get(key), new ArrayList<>(causes.get(key)))));
            live.disabledEntities = List.copyOf(out);
            return live.disabledEntities;
        }
    }

    private static void addDisabled(Map<String, ItemStack> stacks, Map<String, Long> counts, Map<String, Set<Item>> causes,
            ItemStack stack, Item cause)
    {
        String key = ItemKeys.of(stack);
        stacks.putIfAbsent(key, stack.copyWithCount(1));
        counts.merge(key, 1L, Long::sum);
        causes.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(cause);
    }

    /**
     * Включить обратно отключённые блоки {@code originals} и сущности {@code entityItems} (кнопка потухшей строки) одной
     * правкой. Клиентский поток.
     */
    public static Outcome enable(LitematicaSchematic schematic, Collection<Block> originals, Collection<Item> entityItems)
    {
        return editAll(schematic, changes ->
        {
            List<Block> changed = new ArrayList<>();

            for (Block original : originals)
            {
                Block current = changes.map.get(original);

                if (current != null && current.defaultBlockState().isAir())
                {
                    changes.map.remove(original);
                    changed.add(original);
                }
            }

            for (Item item : entityItems)
            {
                changes.entitiesTouched |= changes.entities.remove(item);
            }

            return changed;
        });
    }

    /**
     * Отключённые блоки схемы с числом клеток. Блок, которого в схеме уже нет (файл пересохранили), — с нулём.
     * Любой поток.
     */
    public static List<Disabled> disabled(@Nullable LitematicaSchematic schematic)
    {
        Map<Block, Long> counts = new LinkedHashMap<>();

        for (Block block : disabledBlocks(schematic))
        {
            counts.put(block, 0L);
        }

        disabledStates(schematic).forEach((state, count) -> counts.merge(state.getBlock(), count, Long::sum));

        List<Disabled> out = new ArrayList<>();
        counts.forEach((block, count) -> out.add(new Disabled(block, count)));
        return out;
    }

    /** Исходные блоки, что сейчас отключены (заменены воздухом). Любой поток. */
    public static List<Block> disabledBlocks(@Nullable LitematicaSchematic schematic)
    {
        List<Block> out = new ArrayList<>();

        for (Map.Entry<Block, Block> pair : active(schematic).entrySet())
        {
            if (pair.getValue().defaultBlockState().isAir())
            {
                out.add(pair.getKey());
            }
        }

        return out;
    }

    /**
     * Исходные состояния отключённых блоков с числом клеток — то, что стояло бы в схеме без отключения.
     * Из них таблица собирает строку отключённого блока тем же путём, что и обычную (двойная плита — два
     * предмета, дверь — один), поэтому число в строке не меняется от того, что блок выключили. Счёт — проходом
     * по массиву клеток регионов ({@code getBlockCounts} Litematica — кеш, который заполнен не всегда), итог
     * кешируется до следующей правки замен. Любой поток.
     */
    public static Map<BlockState, Long> disabledStates(@Nullable LitematicaSchematic schematic)
    {
        if (schematic == null)
        {
            return Map.of();
        }

        synchronized (LOCK)
        {
            Live live = LIVE.get(schematic);

            if (live == null)
            {
                return Map.of();
            }

            if (live.disabledFor == live.generation && live.disabled != null)
            {
                return live.disabled;
            }

            Set<Block> off = new HashSet<>();

            for (Map.Entry<Block, Block> pair : live.applied.entrySet())
            {
                if (pair.getValue().defaultBlockState().isAir())
                {
                    off.add(pair.getKey());
                }
            }

            Map<BlockState, Long> counts = new LinkedHashMap<>();
            boolean dry = off.contains(Blocks.WATER);

            if (! off.isEmpty())
            {
                for (Region region : live.regions.values())
                {
                    List<BlockState> original = region.original();
                    BlockState[] byId = new BlockState[original.size()];
                    boolean any = false;

                    for (int id = 0; id < original.size(); ++id)
                    {
                        BlockState state = original.get(id);

                        if (off.contains(state.getBlock()))
                        {
                            byId[id] = state;
                            any = true;
                        }
                        else if (dry && waterlogged(state))
                        {
                            // Вода, снятая с блока «в воде», — клетка воды: строка «Ведро воды» отключённой воды
                            byId[id] = Blocks.WATER.defaultBlockState();
                            any = true;
                        }
                    }

                    if (! any)
                    {
                        continue;
                    }

                    try
                    {
                        var array = region.container().getArray();
                        long size = array.size();
                        long[] perId = new long[byId.length];

                        for (long i = 0; i < size; ++i)
                        {
                            int id = array.getAt(i);

                            if (id >= 0 && id < perId.length)
                            {
                                ++perId[id];
                            }
                        }

                        for (int id = 0; id < byId.length; ++id)
                        {
                            if (byId[id] != null && perId[id] > 0L)
                            {
                                counts.merge(byId[id], perId[id], Long::sum);
                            }
                        }
                    }
                    catch (RuntimeException e)
                    {
                        LOGGER.warn("Could not count disabled blocks of {}", live.file, e);
                    }
                }
            }

            live.disabled = Map.copyOf(counts);
            live.disabledFor = live.generation;

            return live.disabled;
        }
    }

    /**
     * Включить обратно исходные блоки из {@code originals} (отключённые строкой таблицы); прочие замены не
     * трогаются. Клиентский поток.
     */
    public static Outcome enable(LitematicaSchematic schematic, Collection<Block> originals)
    {
        return edit(schematic, map ->
        {
            List<Block> changed = new ArrayList<>();

            for (Block original : originals)
            {
                Block current = map.get(original);

                if (current != null && current.defaultBlockState().isAir())
                {
                    map.remove(original);
                    changed.add(original);
                }
            }

            return changed;
        });
    }

    /** Вернуть один исходный блок. Клиентский поток. */
    public static Outcome revertOne(LitematicaSchematic schematic, Block original)
    {
        return edit(schematic, map -> map.remove(original) != null ? List.of(original) : List.of());
    }

    /** Правка набора замен: {@code change} меняет копию набора и возвращает затронутые исходные блоки. */
    private static Outcome edit(LitematicaSchematic schematic, Function<Map<Block, Block>, List<Block>> change)
    {
        return editAll(schematic, changes -> change.apply(changes.map));
    }

    /** Правка замен и отключений на слоях: {@code change} меняет копию и возвращает затронутые исходные блоки. */
    private static Outcome editAll(LitematicaSchematic schematic, Function<Changes, List<Block>> change)
    {
        if (! Minecraft.getInstance().isSameThread())
        {
            return Outcome.failed("error.thread");
        }

        List<LitematicaSchematic> touched;
        List<Block> changed;

        synchronized (LOCK)
        {
            ensureLocked(schematic);
            Live live = LIVE.get(schematic);
            Path file = live == null ? null : live.file;

            if (file == null)
            {
                return Outcome.failed("why.no_file");
            }

            capture(schematic, live);
            Changes changes = new Changes(live.applied, live.layers, live.entities);
            changed = change.apply(changes);

            if (changed.isEmpty() && ! changes.entitiesTouched)
            {
                return Outcome.failed("error.nothing");
            }

            changes.layers.values().removeIf(Set::isEmpty);
            remember(file, changes.map, changes.layers, changes.entities);
            touched = applyToFile(file, changes.map, changes.layers, changes.entities, schematic);
        }

        for (LitematicaSchematic one : touched)
        {
            refresh(one);
        }

        return new Outcome("", changed);
    }

    // ===== Внутреннее (под замком) =====

    /**
     * Первая встреча со схемой: завести её состояние и, если для её файла есть замены и файл тот же,
     * наложить их. {@code true} — палитра изменилась. Уже знакомая схема — {@code false} сразу.
     */
    private static boolean ensureLocked(LitematicaSchematic schematic)
    {
        if (LIVE.containsKey(schematic))
        {
            return false;
        }

        Path file = fileOf(schematic);
        Live live = new Live(file);
        LIVE.put(schematic, live);

        if (file == null)
        {
            return false;
        }

        ReplacementStore store = ReplacementStore.get();
        ReplacementStore.Entry entry = store.entry(file);

        if (entry == null)
        {
            return false;
        }

        switch (ReplacementStore.check(file, entry))
        {
            case MISSING ->
            {
                // Файла нет на месте (корзина, переименование) — запись подождёт: вернут файл — вернутся и замены
                return false;
            }
            case CHANGED ->
            {
                LOGGER.info("Schematic file {} changed since its blocks were replaced; {} replacement(s) dropped",
                        file, entry.blocks().size());
                store.remove(file);
                return false;
            }
            case TOUCHED ->
            {
                ReplacementStore.Fingerprint print = ReplacementStore.fingerprint(file);

                if (print != null)
                {
                    store.put(file, entry.withFile(print, entry.crc()));
                }
            }
            case SAME ->
            {
            }
        }

        Map<Block, Block> map = resolve(entry.blocks(), file);
        Map<Block, Set<Integer>> layers = resolveLayers(entry.layers(), file);
        live.entities = resolveItems(entry.entities(), file);

        if (! live.entities.isEmpty())
        {
            // Сущности наложит settleEntities — в клиентском потоке и с миром
            entitiesPending = true;
        }

        if (map.isEmpty() && layers.isEmpty())
        {
            return false;
        }

        capture(schematic, live);
        return apply(schematic, live, map, layers);
    }

    /** id из файла замен → слои отключения; неизвестный блок пропускается, как и у замен. */
    private static Map<Block, Set<Integer>> resolveLayers(Map<String, List<Integer>> ids, Path file)
    {
        Map<Block, Set<Integer>> layers = new LinkedHashMap<>();

        for (Map.Entry<String, List<Integer>> pair : ids.entrySet())
        {
            Block block = ReplacementStates.block(pair.getKey());

            if (block == null)
            {
                LOGGER.info("Layer disable of {} for {} skipped: unknown block", pair.getKey(), file);
                continue;
            }

            Set<Integer> set = new java.util.TreeSet<>();

            for (Integer layer : pair.getValue())
            {
                if (layer != null && layer >= 0)
                {
                    set.add(layer);
                }
            }

            if (! set.isEmpty())
            {
                layers.put(block, set);
            }
        }

        return layers;
    }

    /** id из файла замен → блоки; неизвестный блок (мод убрали) пропускается с записью в лог. */
    private static Map<Block, Block> resolve(Map<String, String> ids, Path file)
    {
        Map<Block, Block> map = new LinkedHashMap<>();

        for (Map.Entry<String, String> pair : ids.entrySet())
        {
            Block from = ReplacementStates.block(pair.getKey());
            Block to = ReplacementStates.target(pair.getValue());

            if (from == null || to == null)
            {
                LOGGER.info("Block replacement {} -> {} for {} skipped: unknown block", pair.getKey(), pair.getValue(), file);
                continue;
            }

            map.put(from, to);
        }

        return map;
    }

    /** Запомнить исходные палитры регионов, которых ещё нет (до первой замены это и есть файл). */
    private static void capture(LitematicaSchematic schematic, Live live)
    {
        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

            if (container == null)
            {
                continue;
            }

            Region region = live.regions.get(name);

            if (region == null || region.container() != container)
            {
                if (region != null)
                {
                    LOGGER.info("Schematic {} region {} was rebuilt; its current blocks become the original", live.file, name);
                }

                live.regions.put(name, new Region(container, palette(container.getPalette())));
                live.layerCells.remove(name);
            }
        }
    }

    /**
     * Текущая палитра каждого региона — исходная с заменами {@code map}. Номера и клетки не трогаются.
     * {@code true} — хоть одна палитра изменилась.
     */
    private static boolean apply(LitematicaSchematic schematic, Live live, Map<Block, Block> map,
            Map<Block, Set<Integer>> layers)
    {
        boolean changed = false;

        for (Region region : live.regions.values())
        {
            ILitematicaBlockStatePalette palette = region.container().getPalette();
            int size = palette.getPaletteSize();
            List<BlockState> original = region.original();

            // Litematica дописала состояния после нашего снимка (правка схемы) — они тоже «исходные»
            for (int id = original.size(); id < size; ++id)
            {
                original.add(stateAt(palette, id));
            }

            List<BlockState> next = new ArrayList<>(size);
            boolean differs = false;
            boolean dry = off(map, Blocks.WATER);

            for (int id = 0; id < size; ++id)
            {
                BlockState from = original.get(id);
                Block to = from.isAir() ? null : map.get(from.getBlock());
                BlockState state = to == null ? from : ReplacementStates.carry(from, to);

                // Вода отключена — блоки «в воде» стоят сухими: ни ведра в списке, ни воды в голограмме
                if (dry && waterlogged(state))
                {
                    state = state.setValue(BlockStateProperties.WATERLOGGED, false);
                }

                next.add(state);
                differs |= stateAt(palette, id) != state;
            }

            if (differs)
            {
                if (palette.setMapping(next))
                {
                    changed = true;
                }
                else
                {
                    LOGGER.warn("Litematica refused the replaced palette of {} ({} states)", live.file, size);
                }
            }
        }

        changed |= applyLayers(schematic, live, layers);
        live.applied = Map.copyOf(map);
        live.signature = signatureOf(map, live.layers, live.entitiesApplied);
        ++live.generation;

        return changed;
    }

    /**
     * Клетки отключений на слоях: сперва прежние — на место, затем {@code layers} заново от исходных номеров.
     * {@code true} — клетки изменились.
     */
    private static boolean applyLayers(LitematicaSchematic schematic, Live live, Map<Block, Set<Integer>> layers)
    {
        boolean changed = restoreLayers(live);
        Map<Block, Set<Integer>> next = new LinkedHashMap<>();
        layers.forEach((block, set) ->
        {
            if (! set.isEmpty())
            {
                next.put(block, Set.copyOf(set));
            }
        });

        if (! next.isEmpty())
        {
            int base = minLayerY(schematic);

            for (Map.Entry<String, Region> pair : live.regions.entrySet())
            {
                changed |= disableCells(schematic, live, pair.getKey(), pair.getValue(), base, next);
            }
        }

        changed |= ! next.equals(live.layers);
        live.layers = Map.copyOf(next);
        return changed;
    }

    /** Вернуть клетки всех отключений на слоях по их прежним номерам. {@code true} — что-то вернули. */
    private static boolean restoreLayers(Live live)
    {
        boolean any = false;

        for (Map.Entry<String, LayerCells> pair : live.layerCells.entrySet())
        {
            LayerCells saved = pair.getValue();
            Region region = live.regions.get(pair.getKey());

            // Регион пересобран Litematica — его клетки уже другие, возвращать нечего
            if (region == null || region.container() != saved.container() || saved.cells().isEmpty())
            {
                continue;
            }

            var cells = saved.container().getArray();

            for (var entry : saved.cells().long2IntEntrySet())
            {
                cells.setAt(entry.getLongKey(), entry.getIntValue());
            }

            any = true;
        }

        live.layerCells.clear();
        return any;
    }

    /** Воздух в клетки региона, где на отключённом слое исходно стоит отключённый блок; прежние номера — запомнить. */
    private static boolean disableCells(LitematicaSchematic schematic, Live live, String name, Region region, int base,
            Map<Block, Set<Integer>> layers)
    {
        LitematicaBlockStateContainer container = region.container();
        net.minecraft.core.Vec3i size = container.getSize();
        int regionY = regionMinY(schematic, name);
        long layerSize = (long) size.getX() * size.getZ();
        List<BlockState> original = region.original();
        // Локальный слой региона → что стало с номерами палитры на нём: HIT_AIR — воздух, HIT_DRY — без воды
        Map<Integer, byte[]> byLocalY = new HashMap<>();

        for (Map.Entry<Block, Set<Integer>> pair : layers.entrySet())
        {
            boolean water = pair.getKey() == Blocks.WATER;

            for (int layer : pair.getValue())
            {
                int localY = base + layer - regionY;

                if (localY < 0 || localY >= size.getY())
                {
                    continue;
                }

                byte[] hit = byLocalY.computeIfAbsent(localY, y -> new byte[original.size()]);

                for (int id = 0; id < original.size(); ++id)
                {
                    BlockState state = original.get(id);

                    if (state.getBlock() == pair.getKey())
                    {
                        hit[id] = HIT_AIR;
                    }
                    else if (water && hit[id] == 0 && waterlogged(state))
                    {
                        hit[id] = HIT_DRY;
                    }
                }
            }
        }

        if (byLocalY.isEmpty() || layerSize <= 0L)
        {
            return false;
        }

        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap saved = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        // Сухие номера по прежним: сухого состояния в палитре может не быть — Litematica его допишет
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap dryIds = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int air = -1;

        for (Map.Entry<Integer, byte[]> pair : byLocalY.entrySet())
        {
            byte[] hit = pair.getValue();
            long from = pair.getKey() * layerSize;
            long to = Math.min(from + layerSize, container.getArray().size());

            for (long index = from; index < to; ++index)
            {
                int id = container.getArray().getAt(index);

                if (id < 0 || id >= hit.length || hit[id] == 0)
                {
                    continue;
                }

                int next;

                if (hit[id] == HIT_DRY)
                {
                    BlockState current = stateAt(container.getPalette(), id);

                    // Уже сухой (вода отключена целиком) — трогать нечего
                    if (! waterlogged(current))
                    {
                        continue;
                    }

                    if (! dryIds.containsKey(id))
                    {
                        dryIds.put(id, container.getPalette().idFor(current.setValue(BlockStateProperties.WATERLOGGED, false)));
                    }

                    next = dryIds.get(id);
                }
                else
                {
                    if (air < 0)
                    {
                        // Номер воздуха: если в палитре его нет, Litematica допишет и при нужде расширит массив клеток
                        air = container.getPalette().idFor(LitematicaBlockStateContainer.AIR_BLOCK_STATE);
                    }

                    next = air;
                }

                container.getArray().setAt(index, next);
                saved.put(index, id);
            }
        }

        if (saved.isEmpty())
        {
            return false;
        }

        live.layerCells.put(name, new LayerCells(container, saved));
        return true;
    }

    /** Нижняя клетка региона по высоте, в координатах схемы (размер региона бывает отрицательным). */
    private static int regionMinY(LitematicaSchematic schematic, String name)
    {
        net.minecraft.core.BlockPos pos = schematic.getSubRegionPosition(name);
        net.minecraft.core.BlockPos size = schematic.getAreaSize(name);

        if (pos == null || size == null)
        {
            return 0;
        }

        int end = pos.getY() + size.getY() + (size.getY() < 0 ? 1 : -1);
        return Math.min(pos.getY(), end);
    }

    /** Верхняя клетка региона по высоте, в координатах схемы. */
    private static int regionMaxY(LitematicaSchematic schematic, String name)
    {
        net.minecraft.core.BlockPos pos = schematic.getSubRegionPosition(name);
        net.minecraft.core.BlockPos size = schematic.getAreaSize(name);

        if (pos == null || size == null)
        {
            return 0;
        }

        int end = pos.getY() + size.getY() + (size.getY() < 0 ? 1 : -1);
        return Math.max(pos.getY(), end);
    }

    /** Нижняя клетка всей схемы (по всем регионам) — слой 0. */
    private static int minLayerY(LitematicaSchematic schematic)
    {
        int min = Integer.MAX_VALUE;

        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            min = Math.min(min, regionMinY(schematic, name));
        }

        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** Наложить набор замен на все знакомые схемы этого файла (их бывает несколько). Те, что изменились. */
    private static List<LitematicaSchematic> applyToFile(Path file, Map<Block, Block> map, Map<Block, Set<Integer>> layers,
            Set<Item> entities, LitematicaSchematic first)
    {
        List<LitematicaSchematic> touched = new ArrayList<>();
        Level level = Minecraft.getInstance().level;

        for (Map.Entry<LitematicaSchematic, Live> pair : new ArrayList<>(LIVE.entrySet()))
        {
            LitematicaSchematic schematic = pair.getKey();
            Live live = pair.getValue();

            if (schematic == null || ! file.equals(live.file))
            {
                continue;
            }

            capture(schematic, live);
            boolean changed = apply(schematic, live, map, layers);
            live.entities = Set.copyOf(entities);

            // Правка игрока — клиентский поток; без мира сущности наложит settleEntities, когда мир будет
            if (level != null)
            {
                changed |= applyEntities(schematic, live, level);
            }
            else if (! live.entities.equals(live.entitiesApplied))
            {
                entitiesPending = true;
            }

            if (changed || schematic == first)
            {
                touched.add(schematic);
            }
        }

        return touched;
    }

    /** Сохранить набор замен файла; пустой — убрать запись. Новая запись — с отпечатком файла, CRC — в фоне. */
    private static void remember(Path file, Map<Block, Block> map, Map<Block, Set<Integer>> layers, Set<Item> entities)
    {
        ReplacementStore store = ReplacementStore.get();

        if (map.isEmpty() && layers.isEmpty() && entities.isEmpty())
        {
            store.remove(file);
            return;
        }

        List<String> entityIds = new ArrayList<>();
        entities.forEach(item -> entityIds.add(BuiltInRegistries.ITEM.getKey(item).toString()));
        entityIds.sort(Comparator.naturalOrder());

        Map<String, String> ids = new LinkedHashMap<>();
        map.forEach((from, to) -> ids.put(ReplacementStates.id(from).toString(), ReplacementStates.id(to).toString()));
        Map<String, List<Integer>> layerIds = new LinkedHashMap<>();
        layers.forEach((block, set) -> layerIds.put(ReplacementStates.id(block).toString(), new ArrayList<>(new java.util.TreeSet<>(set))));
        ReplacementStore.Entry entry = store.entry(file);

        if (entry != null)
        {
            store.put(file, entry.withChanges(ids, layerIds, entityIds));
            return;
        }

        ReplacementStore.Fingerprint print = ReplacementStore.fingerprint(file);

        if (print == null)
        {
            return;
        }

        store.put(file, new ReplacementStore.Entry(print.size(), print.modified(), ReplacementStore.NO_CRC, ids, layerIds,
                entityIds));
        Util.ioPool().execute(() -> store.setCrc(file, print, ReplacementStore.crc(file)));
    }

    /**
     * Предмет строки таблицы, которой стоит блок: у воды и лавы — ведро (источник Litematica считает ведром),
     * у остальных — предмет блока.
     */
    static Item rowItemOf(Block block)
    {
        return block == Blocks.WATER ? Items.WATER_BUCKET : block == Blocks.LAVA ? Items.LAVA_BUCKET : block.asItem();
    }

    /** Строка — вода или лава: блока-предмета нет, но отключить можно. */
    public static boolean fluid(Item item)
    {
        return item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET;
    }

    /** Блок «в воде» (ступени, плиты, заборы…): Litematica просит на него ещё и ведро воды. */
    static boolean waterlogged(BlockState state)
    {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** Состояние даёт клетку строки {@code rowItem}: свой предмет; у воды и лавы — только источник; ведро воды — и блок «в воде». */
    private static boolean makes(BlockState state, Item rowItem)
    {
        if (state.isAir())
        {
            return false;
        }

        if (rowItem == Items.WATER_BUCKET && waterlogged(state))
        {
            return true;
        }

        Block block = state.getBlock();

        if (block == Blocks.WATER || block == Blocks.LAVA)
        {
            return rowItemOf(block) == rowItem && state.getValue(LiquidBlock.LEVEL) == 0;
        }

        return cellItems(state).contains(rowItem);
    }

    /**
     * Предметы, которыми список материалов считает клетку этого состояния, — тем же путём, что Litematica
     * ({@code MaterialListUtils}): растение в горшке и котёл с водой — несколько предметов, тело лозы — предмет
     * её головы (наш миксин {@code MaterialCache}), грядка — земля, верх двери и изголовье кровати — ничего. Ведро
     * воды блока «в воде» сюда не входит — его даёт {@link #cellItems}. Только клиентский поток: кеш
     * {@code MaterialCache} не потокобезопасен.
     */
    static Set<Item> rowItems(BlockState state)
    {
        Block block = state.getBlock();

        if (block == Blocks.WATER || block == Blocks.LAVA)
        {
            return Set.of(rowItemOf(block));
        }

        Set<Item> items = new LinkedHashSet<>(2);

        try
        {
            MaterialCache cache = MaterialCache.getInstance();

            if (cache.requiresMultipleItems(state))
            {
                for (ItemStack stack : cache.getItems(state))
                {
                    addItem(items, stack);
                }
            }
            else
            {
                addItem(items, cache.getRequiredBuildItemForState(state));
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Litematica другой версии: хотя бы собственный предмет блока, как было до 1.0.22
            LOGGER.debug("Could not ask Litematica for the items of {}", state, e);
            addItem(items, block.asItem().getDefaultInstance());
        }

        return items;
    }

    /** {@link #rowItems} клетки вместе с ведром воды блока «в воде» — всё, что за эту клетку стоит в списке. */
    static Set<Item> cellItems(BlockState state)
    {
        Set<Item> items = rowItems(state);

        if (! waterlogged(state))
        {
            return items;
        }

        Set<Item> out = new LinkedHashSet<>(items);
        out.add(Items.WATER_BUCKET);
        return out;
    }

    private static void addItem(Set<Item> items, ItemStack stack)
    {
        if (! stack.isEmpty() && stack.getItem() != Items.AIR)
        {
            items.add(stack.getItem());
        }
    }

    /**
     * Блок → предметы строк, которыми список считает его клетки ({@link #rowItems}), по состояниям палитр —
     * исходных и нынешних (заменённый блок стоит своими состояниями). Клиентский поток, под {@code LOCK}.
     */
    private static Map<Block, Set<Item>> itemsByBlock(LitematicaSchematic schematic, @Nullable Live live)
    {
        List<BlockState> states = new ArrayList<>(currentStates(schematic));

        if (live != null)
        {
            live.regions.values().forEach(region -> states.addAll(region.original()));
        }

        Set<BlockState> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Block, Set<Item>> out = new HashMap<>();

        for (BlockState state : states)
        {
            if (state.isAir() || state.getBlock() == Blocks.STRUCTURE_VOID || ! seen.add(state))
            {
                continue;
            }

            out.computeIfAbsent(state.getBlock(), block -> new HashSet<>()).addAll(rowItems(state));
        }

        return out;
    }

    /**
     * Клетки блока {@code block} идут в список строкой {@code rowItem}. Блока нет в палитрах (отключён на всех слоях,
     * стоит только заменой) — по его обычному состоянию.
     */
    private static boolean blockMakes(Map<Block, Set<Item>> byBlock, Block block, Item rowItem)
    {
        Set<Item> items = byBlock.get(block);
        return (items != null ? items : rowItems(block.defaultBlockState())).contains(rowItem);
    }

    /**
     * Строка {@code rowItem} — часть клетки, в которой есть и другой предмет (растение в горшке, вода в котле):
     * отключение уберёт клетку целиком, вместе с горшком или котлом. Клиентский поток.
     */
    public static boolean sharesCells(@Nullable LitematicaSchematic schematic, Item rowItem)
    {
        if (schematic == null)
        {
            return false;
        }

        synchronized (LOCK)
        {
            ensureLocked(schematic);

            for (Set<Item> items : itemsByBlock(schematic, LIVE.get(schematic)).values())
            {
                if (items.size() > 1 && items.contains(rowItem))
                {
                    return true;
                }
            }

            return false;
        }
    }

    /** Предмет строки, которой блок {@code block} стоит в списке (у горшка с растением — первый, горшок). Клиентский поток. */
    public static Item mainRowItem(Block block)
    {
        Set<Item> items = rowItems(block.defaultBlockState());
        return items.isEmpty() ? block.asItem() : items.iterator().next();
    }

    /** Блок отключён целиком (заменён воздухом). */
    private static boolean off(Map<Block, Block> map, Block block)
    {
        Block to = map.get(block);
        return to != null && to.defaultBlockState().isAir();
    }

    /** В схеме есть блок «в воде», который не отключён сам. Под замком. */
    private static boolean hasWaterlogged(LitematicaSchematic schematic, Map<Block, Block> map)
    {
        Live live = LIVE.get(schematic);

        if (live == null)
        {
            return false;
        }

        for (Region region : live.regions.values())
        {
            for (BlockState state : region.original())
            {
                if (waterlogged(state) && ! off(map, state.getBlock()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    // ===== Сущности (1.0.18) =====

    /** Карта «регион → список сущностей» схемы Litematica (закрытое поле): список в ней мы подменяем целиком. */
    private static @Nullable java.lang.reflect.Field entitiesField;
    private static boolean entitiesFieldMissing;

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, List<LitematicaSchematic.EntityInfo>> entityMap(LitematicaSchematic schematic)
    {
        if (entitiesFieldMissing)
        {
            return null;
        }

        try
        {
            if (entitiesField == null)
            {
                java.lang.reflect.Field field = LitematicaSchematic.class.getDeclaredField("entities");
                field.setAccessible(true);
                entitiesField = field;
            }

            return (Map<String, List<LitematicaSchematic.EntityInfo>>) entitiesField.get(schematic);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // Litematica переименовала поле — отключать сущности нечем, блоки работают как раньше
            entitiesFieldMissing = true;
            LOGGER.warn("Litematica schematic entity lists are out of reach; entities can't be disabled", e);
            return null;
        }
    }

    /**
     * Снять исходные списки сущностей регионов: впервые или когда Litematica поставила свой новый список (схему
     * перечитали, регион пересобран). Наш поставленный список исходным не считается. Под замком.
     */
    private static void captureEntities(LitematicaSchematic schematic, Live live)
    {
        Map<String, List<LitematicaSchematic.EntityInfo>> map = entityMap(schematic);

        if (map == null)
        {
            return;
        }

        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            List<LitematicaSchematic.EntityInfo> current = map.get(name);

            if (live.entityOriginal.containsKey(name) && live.entitySeen.get(name) == current)
            {
                continue;
            }

            live.entityOriginal.put(name, current == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(current)));
            live.entitySeen.put(name, current);
            live.entityItems.remove(name);
            live.disabledEntities = null;
        }
    }

    /** Предметы исходных сущностей региона по номеру — создать сущность по данным, как при счёте. Клиентский поток. */
    private static List<EntityItems> entityItems(Live live, String name, Level level)
    {
        List<EntityItems> cached = live.entityItems.get(name);

        if (cached != null)
        {
            return cached;
        }

        List<LitematicaSchematic.EntityInfo> original = live.entityOriginal.getOrDefault(name, List.of());
        List<EntityItems> items = new ArrayList<>(original.size());

        for (LitematicaSchematic.EntityInfo info : original)
        {
            items.add(itemsOf(info, level));
        }

        live.entityItems.put(name, items);
        return items;
    }

    private static EntityItems itemsOf(LitematicaSchematic.EntityInfo info, Level level)
    {
        try
        {
            Entity entity = EntityUtils.createEntityAndPassengersFromData(info.nbt(), level);
            SchematicEntities.Kind kind = entity == null ? null : SchematicEntities.kind(entity);

            if (kind == null)
            {
                return EntityItems.NONE;
            }

            List<ItemStack> items = SchematicEntities.items(entity, kind);

            if (items.isEmpty())
            {
                return EntityItems.NONE;
            }

            return new EntityItems(items.get(0), kind == SchematicEntities.Kind.ITEM_FRAME && items.size() > 1
                    ? items.get(1) : ItemStack.EMPTY);
        }
        catch (RuntimeException | LinkageError e)
        {
            return EntityItems.NONE;
        }
    }

    private static boolean sameEntities(List<LitematicaSchematic.EntityInfo> a, List<LitematicaSchematic.EntityInfo> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }

        for (int i = 0; i < a.size(); ++i)
        {
            if (a.get(i) != b.get(i))
            {
                return false;
            }
        }

        return true;
    }

    /** Рамка без содержимого — те же данные, только без предмета. */
    private static LitematicaSchematic.EntityInfo withoutContent(LitematicaSchematic.EntityInfo info)
    {
        fi.dy.masa.malilib.util.data.tag.CompoundData nbt = info.nbt().copy();
        nbt.remove("Item");
        return new LitematicaSchematic.EntityInfo(info.posVec(), nbt);
    }

    /**
     * Поставить в схему списки сущностей по {@code live.entities}: исходный список без отключённых сущностей, у рамок с
     * отключённым содержимым — без содержимого. Список меняется, только если он другой. {@code true} — списки
     * сменились. Клиентский поток, под замком.
     */
    private static boolean applyEntities(LitematicaSchematic schematic, Live live, Level level)
    {
        Map<String, List<LitematicaSchematic.EntityInfo>> map = entityMap(schematic);

        if (map == null)
        {
            return false;
        }

        captureEntities(schematic, live);
        Set<Item> off = live.entities;
        boolean changed = false;

        for (Map.Entry<String, List<LitematicaSchematic.EntityInfo>> pair : live.entityOriginal.entrySet())
        {
            String name = pair.getKey();
            List<LitematicaSchematic.EntityInfo> original = pair.getValue();
            List<LitematicaSchematic.EntityInfo> target = original;

            if (! off.isEmpty() && ! original.isEmpty())
            {
                List<EntityItems> items = entityItems(live, name, level);
                target = new ArrayList<>(original.size());

                for (int i = 0; i < original.size(); ++i)
                {
                    EntityItems one = i < items.size() ? items.get(i) : EntityItems.NONE;
                    LitematicaSchematic.EntityInfo info = original.get(i);

                    if (! one.own().isEmpty() && off.contains(one.own().getItem()))
                    {
                        continue;
                    }

                    target.add(! one.content().isEmpty() && off.contains(one.content().getItem())
                            ? live.entityStripped.computeIfAbsent(info, BlockReplacements::withoutContent) : info);
                }
            }

            List<LitematicaSchematic.EntityInfo> current = map.get(name);

            // По ссылкам, не equals: CompoundData malilib сравнивает только ключи левой стороны — рамка без
            // предмета у него «равна» рамке с предметом, и откат содержимого рамок молча не случался бы
            if (sameEntities(current == null ? List.of() : current, target))
            {
                continue;
            }

            // Новый список, а не правка на месте: Litematica читает списки в своих потоках, а наш счёт кеширует
            // предметы сущностей по экземпляру списка (SchematicEntities.specs)
            List<LitematicaSchematic.EntityInfo> next = new ArrayList<>(target);
            map.put(name, next);
            live.entitySeen.put(name, next);
            changed = true;
        }

        live.entitiesApplied = off;

        if (changed)
        {
            live.signature = signatureOf(live.applied, live.layers, live.entitiesApplied);
            ++live.generation;
            live.disabledEntities = null;
        }

        return changed;
    }

    /**
     * Наложить ждущие отключения сущностей: в клиентском потоке, когда есть мир. {@code true} — списки сущностей
     * сменились (нужен {@link #refresh}).
     */
    private static boolean settleEntities(LitematicaSchematic schematic)
    {
        Minecraft mc = Minecraft.getInstance();

        if (schematic == null || ! mc.isSameThread() || mc.level == null)
        {
            return false;
        }

        synchronized (LOCK)
        {
            Live live = LIVE.get(schematic);

            if (live == null || live.entities.isEmpty() && live.entitiesApplied.isEmpty())
            {
                return false;
            }

            return applyEntities(schematic, live, mc.level);
        }
    }

    /**
     * Предметы сущностей, что сейчас стоят в схеме (сами сущности и содержимое рамок), — из исходных без отключённых.
     * Нет мира — пусто: строку сущности тогда не отключить. Под замком.
     */
    private static Set<Item> entityItemsPresent(LitematicaSchematic schematic, Live live)
    {
        Level level = Minecraft.getInstance().level;

        if (level == null || ! Minecraft.getInstance().isSameThread())
        {
            return Set.of();
        }

        captureEntities(schematic, live);
        Set<Item> out = new HashSet<>();

        for (String name : live.entityOriginal.keySet())
        {
            for (EntityItems one : entityItems(live, name, level))
            {
                if (one.own().isEmpty() || live.entities.contains(one.own().getItem()))
                {
                    continue;
                }

                out.add(one.own().getItem());

                if (! one.content().isEmpty() && ! live.entities.contains(one.content().getItem()))
                {
                    out.add(one.content().getItem());
                }
            }
        }

        return out;
    }

    /** id из файла замен → предметы отключённых сущностей; неизвестный предмет (мод убрали) пропускается. */
    private static Set<Item> resolveItems(List<String> ids, Path file)
    {
        Set<Item> items = new LinkedHashSet<>();

        for (String id : ids)
        {
            Identifier key = Identifier.tryParse(id);
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);

            if (item == null || item == Items.AIR)
            {
                LOGGER.info("Entity disable of {} for {} skipped: unknown item", id, file);
                continue;
            }

            items.add(item);
        }

        return Set.copyOf(items);
    }

    /** Исходные блоки схемы (без воздуха), в порядке палитр. */
    private static Set<Block> originalBlocks(LitematicaSchematic schematic)
    {
        Set<Block> blocks = new LinkedHashSet<>();
        Live live = LIVE.get(schematic);

        if (live == null)
        {
            return blocks;
        }

        for (Region region : live.regions.values())
        {
            for (BlockState state : region.original())
            {
                if (! state.isAir() && state.getBlock() != Blocks.STRUCTURE_VOID)
                {
                    blocks.add(state.getBlock());
                }
            }
        }

        return blocks;
    }

    /** Текущие состояния палитр всех регионов (без воздуха). */
    private static List<BlockState> currentStates(LitematicaSchematic schematic)
    {
        List<BlockState> states = new ArrayList<>();

        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

            if (container == null)
            {
                continue;
            }

            for (BlockState state : palette(container.getPalette()))
            {
                if (! state.isAir() && state.getBlock() != Blocks.STRUCTURE_VOID)
                {
                    states.add(state);
                }
            }
        }

        return states;
    }

    private static List<BlockState> palette(ILitematicaBlockStatePalette palette)
    {
        int size = palette.getPaletteSize();
        List<BlockState> states = new ArrayList<>(size);

        for (int id = 0; id < size; ++id)
        {
            states.add(stateAt(palette, id));
        }

        return states;
    }

    /** Состояние по номеру; пустой номер Litematica читает как воздух — так и здесь. */
    private static BlockState stateAt(ILitematicaBlockStatePalette palette, int id)
    {
        BlockState state = palette.getBlockState(id);
        return state == null ? LitematicaBlockStateContainer.AIR_BLOCK_STATE : state;
    }

    private static @Nullable Path fileOf(LitematicaSchematic schematic)
    {
        try
        {
            Path file = schematic.getFile();
            return file == null ? null : file.toAbsolutePath().normalize();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String signatureOf(Map<Block, Block> map, Map<Block, Set<Integer>> layers, Set<Item> entities)
    {
        if (map.isEmpty() && layers.isEmpty() && entities.isEmpty())
        {
            return "";
        }

        List<String> pairs = new ArrayList<>();
        map.forEach((from, to) -> pairs.add(ReplacementStates.id(from) + ">" + ReplacementStates.id(to)));
        layers.forEach((block, set) -> pairs.add(ReplacementStates.id(block) + "@" + new java.util.TreeSet<>(set)));
        entities.forEach(item -> pairs.add("e:" + BuiltInRegistries.ITEM.getKey(item)));
        pairs.sort(Comparator.naturalOrder());

        return "|r" + Integer.toHexString(String.join(",", pairs).hashCode());
    }

    // ===== После правки (клиентский поток) =====

    /**
     * Палитра схемы изменилась: наш счёт забыть, голограммы размещений перестроить средствами
     * Litematica, её открытый список материалов этой схемы пересчитать, HUD пересчитать.
     */
    private static void refresh(LitematicaSchematic schematic)
    {
        SchematicMaterials.forget(schematic);
        LayerCountManager.getInstance().markDirty();

        try
        {
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            manager.markAllPlacementsOfSchematicForRebuild(schematic);
            refreshMaterialList(schematic, manager.getAllPlacementsOfSchematic(schematic));
        }
        catch (RuntimeException | LinkageError e)
        {
            // Замена уже в схеме: голограммы догонят при следующей перестройке чанков, HUD пересчитается сам
            LOGGER.warn("Could not ask Litematica to rebuild placements of {}", fileOf(schematic), e);
        }
    }

    /** Открытый в Litematica список материалов этой схемы (или её размещения) — пересчитать. */
    private static void refreshMaterialList(LitematicaSchematic schematic, Collection<SchematicPlacement> placements)
    {
        MaterialListBase list = DataManager.getMaterialList();

        if (list == null)
        {
            return;
        }

        boolean ours = false;

        if (list instanceof MaterialListSchematic)
        {
            ours = Objects.equals(list.getName(), schematic.getMetadata().getName());
        }
        else if (list instanceof MaterialListPlacement)
        {
            for (SchematicPlacement placement : placements)
            {
                ours |= Objects.equals(list.getName(), placement.getName());
            }
        }

        if (ours)
        {
            list.reCreateMaterialList();
        }
    }

    /**
     * Узкий вход для харнесса покрытия (режим {@code k}): файл замен, запись файла схемы, «перезапуск»
     * хранилища. В игре его никто не зовёт.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static Path storeFile()
        {
            return ReplacementStore.path();
        }

        /** Замены файла на диске (id → id); {@code null} — записи нет. */
        public static @Nullable Map<String, String> stored(Path file)
        {
            ReplacementStore.Entry entry = ReplacementStore.get().entry(file);
            return entry == null ? null : entry.blocks();
        }

        /**
         * Как после перезапуска игры для схем, прочитанных дальше: хранилище забывает прочитанное и читает
         * файл заново. Уже загруженные схемы (и их исходные палитры) остаются — они живут и в игре.
         */
        /** Отключения на слоях в файле замен для схемы {@code file}: id блока → слои (0 — нижний); записи нет — {@code null}. */
        public static @Nullable Map<String, List<Integer>> storedLayers(Path file)
        {
            ReplacementStore.Entry entry = ReplacementStore.get().entry(file);
            return entry == null ? null : entry.layers();
        }

        /** Отключённые сущности в файле замен (id предметов); записи нет — {@code null}. */
        public static @Nullable List<String> storedEntities(Path file)
        {
            ReplacementStore.Entry entry = ReplacementStore.get().entry(file);
            return entry == null ? null : entry.entities();
        }

        public static void reloadStore()
        {
            ReplacementStore.get().forgetMemory();
        }

        /** Знает ли модуль эту схему (её видел миксин чтения, загрузчик или опрос). */
        public static boolean known(LitematicaSchematic schematic)
        {
            synchronized (LOCK)
            {
                return LIVE.containsKey(schematic);
            }
        }
    }
}
