package com.aell.layerlist.ghost;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Режим «голограммы по инвентарю»: в схеме остаются только те блоки, предмет для которых
 * сейчас лежит у игрока (хотбар, рюкзак, вторая рука). Остальное Litematica не мешит вовсе.
 * В креативе режим молчит: материалы бесконечны, схема видна целиком (запрос 09.10.2026).
 *
 * <p>Фильтр стоит в сборке чанка ({@code renderBlocksAndOverlay}), то есть ниже диапазона
 * слоёв и обрезки Zone Focus: слой и зона по-прежнему решают, ЧТО вообще рисуется, а режим
 * убирает из этого лишнее. Поэтому ничего отдельно согласовывать с ними не нужно — а
 * {@code Ctrl+B} сверяет мир со схемой, а не с мешем, и режимом не затрагивается.
 *
 * <p>Сборка чанков идёт в рабочих потоках, а {@link MaterialCache} не потокобезопасен. Поэтому
 * рабочий поток только читает готовые таблицы, а предмет для нового состояния блока
 * выясняется на клиентском потоке в {@link #onClientTick}. Пока состояние не выяснено,
 * оно рисуется; если окажется, что предмета нет, чанки пересобираются.
 *
 * <p>Пересборка чанка у Litematica 0.29 сначала очищает его буферы — пока меш не выгружен заново,
 * чанк (и подсветка Ctrl+B в нём) пуст несколько кадров. Поэтому без нужды не пересобираем:
 * предмет на курсоре (щелчки по слотам, перенос, изи-плейс) считается «с собой», тип прячется,
 * только если его нет {@link #HIDE_DELAY_TICKS} тиков подряд (появляется сразу), и пересобираются
 * лишь чанки, где фильтр встречал блоки этих типов, с соседями (грани о скрытых соседей).
 */
@Environment(EnvType.CLIENT)
public final class InventoryGhosts
{
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MIN_REFRESH_GAP_TICKS = 10;
    private static final int RESOLVE_PER_PASS = 512;
    /** Сколько тиков предмета не должно быть, чтобы его тип спрятался: перекладывание и курсор не мигают схемой. */
    private static final int HIDE_DELAY_TICKS = 20;

    /** Состояние блока → предмет для его постановки; {@link Items#AIR} — «предмета нет». */
    private static final Map<BlockState, Item> REQUIRED = new ConcurrentHashMap<>();
    /** Состояния, на которые рабочие потоки уже наткнулись, но предмет ещё не выяснен (без повторов: позиций миллионы, состояний сотни). */
    private static final Set<BlockState> UNRESOLVED = ConcurrentHashMap.newKeySet();

    /** Сколько позиций убрано с последнего {@link #takeCulled()} — для самопроверки. */
    private static final AtomicInteger CULLED = new AtomicInteger();
    /** Сколько раз пересобирались чанки и сколько всего чанков — для самопроверки. */
    private static final AtomicInteger REFRESHES = new AtomicInteger();
    private static final AtomicInteger REFRESHED_CHUNKS = new AtomicInteger();

    /**
     * Чанк схемы → состояния, которые фильтр в нём встречал (рабочие потоки сборки). Только пополняется:
     * лишняя пересборка безвредна, пропущенная оставила бы голограмму не того вида.
     */
    private static final Map<Long, Set<BlockState>> SEEN = new ConcurrentHashMap<>();
    /** Когда предмет был у игрока в последний раз (тик) — для задержки перед тем, как спрятать тип. */
    private static final Map<Item, Integer> LAST_HELD = new java.util.HashMap<>();
    /** Предметы, чья видимость сменилась с прошлой пересборки, и состояния, выяснившиеся «прятать». */
    private static final Set<Item> CHANGED_ITEMS = new HashSet<>();
    private static final Set<BlockState> CHANGED_STATES = new HashSet<>();
    private static boolean refreshAll;
    /** Чанки с клетками, чья видимость зависит от инструмента доводки (лопата у костра, ножницы у тыквы). */
    private static final Set<Long> TOOL_CHUNKS = ConcurrentHashMap.newKeySet();
    private static boolean toolsChanged;
    private static @org.jetbrains.annotations.Nullable Object lastLevel;

    private static volatile Set<Item> held = Set.of();
    /** Растёт при каждой смене набора предметов или режима — по нему проверка Ctrl+B пересобирает рамки. */
    private static volatile int version;
    private static volatile boolean wasActive;
    /** Креатив (бесконечные материалы): «с собой» есть всё — режим молчит, схема видна целиком. Пишет клиентский тик. */
    private static volatile boolean infinite;

    private static int tick;
    private static int lastRefreshTick = -MIN_REFRESH_GAP_TICKS;
    private static boolean refreshPending;

    private InventoryGhosts() {}

    /** Режим включён и действует: в креативе любой блок под рукой, прятать нечего. */
    public static boolean active()
    {
        return LayerListConfigs.GHOST_INVENTORY_ONLY.getBooleanValue() && ! infinite;
    }

    /**
     * Из сборки чанка: прятать ли голограмму этого состояния в этой клетке. Заодно запоминает, что
     * такое состояние в этом чанке есть, — смена видимости его типа пересоберёт только такие чанки.
     */
    public static boolean hides(BlockState schematicState, net.minecraft.core.BlockPos pos)
    {
        if (! schematicState.isAir())
        {
            SEEN.computeIfAbsent(chunkKey(pos), k -> ConcurrentHashMap.newKeySet()).add(schematicState);
        }

        return hides(schematicState);
    }

    private static long chunkKey(net.minecraft.core.BlockPos pos)
    {
        return ((long) (pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
    }

    /** Из сборки чанка: клетка видна, пока у игрока есть инструмент доводки. */
    public static void noteToolCell(net.minecraft.core.BlockPos pos)
    {
        TOOL_CHUNKS.add(chunkKey(pos));
    }

    /** Есть ли у игрока предмет под условие (по снимку инвентаря) — для рабочих потоков сборки. */
    public static boolean holds(java.util.function.Predicate<ItemStack> tool)
    {
        for (Item item : held)
        {
            if (tool.test(item.getDefaultInstance()))
            {
                return true;
            }
        }

        return false;
    }

    /** Из рабочего потока сборки чанка: прятать ли голограмму этого состояния схемы. */
    public static boolean hides(BlockState schematicState)
    {
        if (schematicState.isAir())
        {
            return false;
        }

        Item required = REQUIRED.get(schematicState);

        if (required == null)
        {
            UNRESOLVED.add(schematicState);
            return false;
        }

        boolean hide = hidden(required, held);

        if (hide)
        {
            CULLED.incrementAndGet();
        }

        return hide;
    }

    public static int version()
    {
        return version;
    }

    /**
     * Есть ли у игрока предмет для этого состояния — для проверки Ctrl+B. Только клиентский поток:
     * предмет выясняется сразу ({@link MaterialCache} здесь можно звать). Блок без предмета считается
     * «есть»: инвентарём его не закрыть, и прятать его нельзя, как и голограмму.
     */
    public static boolean available(BlockState state)
    {
        if (state.isAir())
        {
            return true;
        }

        Item required = REQUIRED.get(state);

        if (required == null)
        {
            required = requiredItem(state);
            REQUIRED.put(state, required);
        }

        return ! hidden(required, held);
    }

    public static int takeCulled()
    {
        return CULLED.getAndSet(0);
    }

    /** {пересборок, чанков в них} с прошлого вызова — для самопроверки. */
    public static int[] takeRefreshStats()
    {
        return new int[]{REFRESHES.getAndSet(0), REFRESHED_CHUNKS.getAndSet(0)};
    }

    private static boolean hidden(Item required, Set<Item> inventory)
    {
        // Блок без предмета (вода, огонь, технические) инвентарём не закрыть — его не прячем,
        // иначе часть схемы пропадала бы без всякой возможности её вернуть
        return required != Items.AIR && ! inventory.contains(required);
    }

    public static void onClientTick(Minecraft mc)
    {
        ++tick;
        // Сменили режим игры — смена «действует / молчит» ниже пересоберёт схему целиком
        infinite = mc.player != null && mc.player.hasInfiniteMaterials();

        if (mc.level != lastLevel)
        {
            lastLevel = mc.level;
            SEEN.clear();
            TOOL_CHUNKS.clear();
            LAST_HELD.clear();
        }

        boolean active = active();

        if (active != wasActive)
        {
            wasActive = active;
            LAST_HELD.clear();
            held = active ? withDelay(scan(mc)) : Set.of();
            ++version;
            refreshPending = true;
            refreshAll = true;
        }

        if (active && tick % SCAN_INTERVAL_TICKS == 0)
        {
            Set<Item> now = withDelay(scan(mc));

            if (! now.equals(held))
            {
                Set<Item> before = held;
                held = now;
                ++version;

                if (changesVisibility(before, now))
                {
                    refreshPending = true;
                }

                // Появился или пропал не блок (лопата, ножницы) — клетки доводки могли смениться
                if (! TOOL_CHUNKS.isEmpty() && toolChanged(before, now))
                {
                    toolsChanged = true;
                    refreshPending = true;
                }
            }

            if (resolve())
            {
                refreshPending = true;
            }
        }

        if (refreshPending && tick - lastRefreshTick >= MIN_REFRESH_GAP_TICKS)
        {
            refreshPending = false;
            lastRefreshTick = tick;
            refreshChanged();
        }
    }

    /**
     * Набор «с собой» с задержкой на исчезновение: предмет, который был у игрока меньше
     * {@link #HIDE_DELAY_TICKS} тиков назад, ещё считается — переложил, взял на курсор, потратил
     * последний и тут же подобрал новый — схема не мигает.
     */
    private static Set<Item> withDelay(Set<Item> scanned)
    {
        for (Item item : scanned)
        {
            LAST_HELD.put(item, tick);
        }

        Set<Item> items = new HashSet<>();
        LAST_HELD.entrySet().removeIf(e -> tick - e.getValue() > HIDE_DELAY_TICKS);

        for (Map.Entry<Item, Integer> e : LAST_HELD.entrySet())
        {
            items.add(e.getKey());
        }

        return Set.copyOf(items);
    }

    /** Выясняет предметы для накопившихся состояний; истина — если что-то из них теперь прячется. */
    private static boolean resolve()
    {
        boolean hides = false;
        Set<Item> inventory = held;

        Iterator<BlockState> pending = UNRESOLVED.iterator();

        for (int i = 0; i < RESOLVE_PER_PASS && pending.hasNext(); ++i)
        {
            BlockState state = pending.next();
            pending.remove();

            if (REQUIRED.containsKey(state))
            {
                continue;
            }

            Item required = requiredItem(state);
            REQUIRED.put(state, required);

            if (hidden(required, inventory))
            {
                hides = true;
                CHANGED_STATES.add(state);
            }
        }

        return hides;
    }

    /**
     * Предмет, без которого клетку не построить. Litematica для верхней половины двери, высокого цветка
     * и изголовья кровати предмета не называет (считает его по нижней половине), а горшку с растением
     * отдаёт само растение — здесь первым нужен пустой горшок (растение в уже стоящий горшок вызывающий
     * передаёт состоянием растения). {@link Items#AIR} — предмета нет вовсе (вода без источника, огонь).
     */
    static Item requiredItem(BlockState state)
    {
        if (com.aell.layerlist.easyplace.Potting.plant(state) != null)
        {
            return Items.FLOWER_POT;
        }

        try
        {
            ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state);

            if (! stack.isEmpty())
            {
                return stack.getItem();
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // Ниже — предмет самого блока
        }

        return state.getBlock().asItem();
    }

    /** Меняется ли видимость хоть одного уже известного состояния при переходе между наборами; какие типы — в {@link #CHANGED_ITEMS}. */
    private static boolean changesVisibility(Set<Item> before, Set<Item> after)
    {
        boolean changed = false;

        for (Item required : new HashSet<>(REQUIRED.values()))
        {
            if (hidden(required, before) != hidden(required, after))
            {
                CHANGED_ITEMS.add(required);
                changed = true;
            }
        }

        return changed;
    }

    private static boolean toolChanged(Set<Item> before, Set<Item> after)
    {
        for (Item item : before)
        {
            if (! after.contains(item) && ! (item instanceof net.minecraft.world.item.BlockItem))
            {
                return true;
            }
        }

        for (Item item : after)
        {
            if (! before.contains(item) && ! (item instanceof net.minecraft.world.item.BlockItem))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Пересобрать чанки, где встречались блоки сменивших видимость типов, и их соседей по сторонам
     * (грани о скрытого соседа рисуются в соседнем чанке). Смена режима — всё целиком.
     */
    private static void refreshChanged()
    {
        Set<Item> items = new HashSet<>(CHANGED_ITEMS);
        Set<BlockState> states = new HashSet<>(CHANGED_STATES);
        boolean tools = toolsChanged;
        CHANGED_ITEMS.clear();
        CHANGED_STATES.clear();
        toolsChanged = false;

        if (refreshAll)
        {
            refreshAll = false;
            refreshRenderers();
            return;
        }

        fi.dy.masa.litematica.world.WorldSchematic world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        if (world == null || (items.isEmpty() && states.isEmpty() && ! tools))
        {
            return;
        }

        it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        if (tools)
        {
            for (long key : TOOL_CHUNKS)
            {
                addWithNeighbours(chunks, key);
            }
        }

        for (Map.Entry<Long, Set<BlockState>> e : SEEN.entrySet())
        {
            for (BlockState state : e.getValue())
            {
                Item required = REQUIRED.get(state);

                if (states.contains(state) || (required != null && items.contains(required)))
                {
                    addWithNeighbours(chunks, e.getKey());
                    break;
                }
            }
        }

        int scheduled = 0;

        for (long key : chunks)
        {
            int x = (int) (key >> 32);
            int z = (int) key;

            if (world.getChunkSource().hasChunk(x, z))
            {
                world.scheduleChunkRenders(x, z);
                ++scheduled;
            }
        }

        if (scheduled > 0)
        {
            REFRESHES.incrementAndGet();
            REFRESHED_CHUNKS.addAndGet(scheduled);
        }
    }

    private static void addWithNeighbours(it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks, long key)
    {
        int x = (int) (key >> 32);
        int z = (int) key;
        chunks.add(key);
        chunks.add(((long) (x + 1) << 32) | (z & 0xFFFFFFFFL));
        chunks.add(((long) (x - 1) << 32) | (z & 0xFFFFFFFFL));
        chunks.add(((long) x << 32) | ((z + 1) & 0xFFFFFFFFL));
        chunks.add(((long) x << 32) | ((z - 1) & 0xFFFFFFFFL));
    }

    private static Set<Item> scan(Minecraft mc)
    {
        LocalPlayer player = mc.player;

        if (player == null)
        {
            return Set.of();
        }

        Inventory inventory = player.getInventory();
        Set<Item> items = new HashSet<>();

        for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (! stack.isEmpty())
            {
                items.add(stack.getItem());
            }
        }

        // Стопка на курсоре — тоже с собой: пока её переносят щелчками, она не пропала
        ItemStack carried = player.containerMenu.getCarried();

        if (! carried.isEmpty())
        {
            items.add(carried.getItem());
        }

        return Set.copyOf(items);
    }

    /** Внутренние грани включил режим (их не было) — при выключении режима вернуть как было. */
    private static boolean innerSidesByMode;

    /**
     * Режим включили — включить и внутренние грани Ghost Alpha (Litematica): соседи видимой голограммы
     * скрыты, и её грани к ним должны быть видны. Выключили — вернуть грани, если их включал режим.
     */
    public static void onModeToggled(boolean on)
    {
        try
        {
            var innerSides = fi.dy.masa.litematica.config.Configs.Visuals.RENDER_TRANSLUCENT_INNER_SIDES;

            if (on)
            {
                if (! innerSides.getBooleanValue())
                {
                    innerSides.setBooleanValue(true);
                    innerSidesByMode = true;
                }
            }
            else if (innerSidesByMode)
            {
                innerSidesByMode = false;

                if (innerSides.getBooleanValue())
                {
                    innerSides.setBooleanValue(false);
                }
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }

    public static void refreshRenderers()
    {
        REFRESHES.incrementAndGet();

        try
        {
            SchematicWorldRefresher.INSTANCE.updateAll();
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }
}
