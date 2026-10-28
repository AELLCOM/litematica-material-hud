package com.aell.layerlist.core;

import com.aell.layerlist.api.CountScope;
import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.GrowingPlants;
import com.aell.layerlist.easyplace.ItemData;
import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.ghost.GhostSupport;
import com.aell.layerlist.schematics.materials.Availability;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class LayerCountManager
{
    /**
     * Что считает счётчик (1.0.18). HUD — все включённые размещения в слоях Litematica и области аддона, с поиском
     * по сундукам и закреплением между измерениями. Вкладка «Материалы» — размещения своей схемы целиком: без слоёв,
     * без области, без побочных действий, со своим кэшем проверки ({@link #forPlacements}). Сверка с миром одна и та
     * же — числа таблицы и HUD считаются одним кодом.
     *
     * @param placements какие размещения считать, включённые или нет; {@code null} — все включённые
     * @param layers     резать по слоям Litematica
     * @param scope      резать по области аддона ({@link CountScope})
     * @param hud        это HUD: поиск по сундукам, закрепление, «только недостающее» в списке
     */
    public record Target(@Nullable Predicate<SchematicPlacement> placements, boolean layers, boolean scope, boolean hud)
    {
        public static final Target HUD = new Target(null, true, true, true);
    }

    private static final LayerCountManager INSTANCE = new LayerCountManager(Target.HUD, VerifyCache.getInstance());
    /** Сколько живут найденные опоры вне слоя, если подпись счёта не менялась (правку схемы на месте она не видит). */
    private static final long SUPPORTS_TTL_MS = 10_000L;

    /** Строки на экране: {@link #allEntries} за вычетом склада, если он учитывается ({@link #syncStock}). */
    private List<MaterialListEntry> entries = Collections.emptyList();
    /** Все строки последнего счёта — для таблицы «Список HUD», API и закладок поиска. */
    private List<MaterialListEntry> allEntries = Collections.emptyList();
    /** Склад, по которому отобраны {@link #entries}; {@code null} — склад не вычитается. */
    private @Nullable Availability stock;
    /** Сколько строк спрятано складом. */
    private int stockHidden;
    /** Подпись того, по чему отобран склад ({@link #stockSignature}): сменилась — отобрать заново, без пересчёта. */
    private long stockSignature = Long.MIN_VALUE;
    /**
     * Блоки схемы целиком — для опор вне слоя: блок слоя, которому не на чем держаться без клетки
     * соседнего слоя (табличка, факел, лоза…), тянет эту клетку в список. Есть, только пока опоры ищутся
     * заново (см. {@link #supports}); в режиме «всё» опоры и так внутри подсчёта.
     */
    private @Nullable SchematicBlocks schematicBlocks;
    /** Счёт идёт по слоям — опоры вне слоя нужны: ищутся заново ({@link #schematicBlocks}) или берутся готовыми. */
    private boolean supportsActive;
    /**
     * Опоры вне слоя по чанкам слоя: клетка и блок схемы в ней. Зависят только от схемы и слоя, не от мира, — ищутся
     * один раз на подпись ({@link #supportsSignature}) и потом берутся готовыми, а мир сверяется в каждом пересчёте
     * (14.10.2026: слой из 36 864 ковров пересчитывался 33 мс против 7 мс у камня — раз в секунду и на каждый блок).
     */
    private Long2ObjectOpenHashMap<SupportCells> supports = new Long2ObjectOpenHashMap<>();
    /** Опоры, найденные в идущем проходе; станут {@link #supports}, когда проход дойдёт до конца. */
    private @Nullable Long2ObjectOpenHashMap<SupportCells> supportsBuilding;
    private String supportsSignature = "";
    private long supportsBuiltAt;
    private boolean supportsReady;
    private long blocksTotal;
    private long blocksMissing;
    private int unverifiedChunks;
    private int cachedChunks;
    private boolean hasData;
    private int page;

    private boolean counting;
    private ArrayListMultimap<ChunkPos, ChunkBox> boxesInChunks = ArrayListMultimap.create();
    private final ArrayDeque<ChunkPos> pendingChunks = new ArrayDeque<>();
    private int unverifiedCounting;
    private int cachedCounting;
    private Object2IntOpenHashMap<BlockState> countsTotal = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<BlockState> countsMissing = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<BlockState> countsMismatch = new Object2IntOpenHashMap<>();
    // Клетки, где предмет задаётся данными блока (узор баннера, черепки горшка), — своим ключом,
    // иначе все узоры слипаются в один «баннер». См. DataItems.
    private Object2IntOpenHashMap<DataItems.Key> dataTotal = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<DataItems.Key> dataMissing = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<DataItems.Key> dataMismatch = new Object2IntOpenHashMap<>();
    // Сущности схемы (стойки, подушки, рамки, картины…) — по чанкам, а счёт по предмету, которым
    // сущность ставится. Итог берётся из данных схемы, как у блоков; сверка — в загруженных чанках.
    private final ArrayListMultimap<ChunkPos, SchematicEntities.Wanted> entitiesInChunks = ArrayListMultimap.create();
    private Object2IntOpenHashMap<DataItems.Key> entityTotal = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<DataItems.Key> entityMissing = new Object2IntOpenHashMap<>();
    private Object2IntOpenHashMap<DataItems.Key> entityMismatch = new Object2IntOpenHashMap<>();

    private String lastRangeSignature = "";
    private int idleTicks;
    private boolean dirty = true;

    /**
     * Снимок последнего честного счёта. Litematica хранит размещения по измерениям, поэтому
     * в другом измерении считать нечего и нечем — а список нужен: ходить по складу в
     * Оверворлде и собирать материалы для стройки в Незере. Итоги в снимке заморожены,
     * колонка «есть в инвентаре» пересчитывается по текущему инвентарю при каждой публикации.
     * Виден в пакете: {@link MaterialRows#publishedEntityTotals()} читает из него счёт сущностей.
     */
    record Snapshot(Object2IntOpenHashMap<BlockState> total,
                    Object2IntOpenHashMap<BlockState> missing,
                    Object2IntOpenHashMap<BlockState> mismatch,
                    Object2IntOpenHashMap<DataItems.Key> dataTotal,
                    Object2IntOpenHashMap<DataItems.Key> dataMissing,
                    Object2IntOpenHashMap<DataItems.Key> dataMismatch,
                    Object2IntOpenHashMap<DataItems.Key> entityTotal,
                    Object2IntOpenHashMap<DataItems.Key> entityMissing,
                    Object2IntOpenHashMap<DataItems.Key> entityMismatch,
                    long blocksTotal, long blocksMissing,
                    String placements, String dimension) {}

    private final LinkedHashSet<String> placementNames = new LinkedHashSet<>();
    private final Target target;
    private final VerifyCache cache;
    // Снимок, из которого собран опубликованный список; пишется только здесь
    Snapshot pinned;
    private boolean showingPinned;

    private LayerCountManager(Target target, VerifyCache cache)
    {
        this.target = target;
        this.cache = cache;
    }

    public static LayerCountManager getInstance()
    {
        return INSTANCE;
    }

    /**
     * Свой счётчик для размещений {@code placements} целиком (вкладка «Материалы»): тикать его самому
     * ({@link #onClientTick}), HUD он не трогает. Кэш проверки дальних чанков — свой, на диске под своей подписью.
     */
    public static LayerCountManager forPlacements(Predicate<SchematicPlacement> placements)
    {
        return new LayerCountManager(new Target(placements, false, false, false), new VerifyCache("-materials"));
    }

    /** Счёт сущностей по предмету в опубликованном списке — отличить строку «только сущности» от блочной. Копия. */
    public Object2IntOpenHashMap<DataItems.Key> entityTotals()
    {
        return this.pinned == null ? new Object2IntOpenHashMap<>() : new Object2IntOpenHashMap<>(this.pinned.entityTotal());
    }

    /** Имена посчитанных размещений (последний проход). */
    public List<String> countedPlacements()
    {
        return List.copyOf(this.placementNames);
    }

    public boolean isHudEnabled()
    {
        return LayerListConfigs.HUD_ENABLED;
    }

    public boolean toggleHud()
    {
        LayerListConfigs.HUD_ENABLED = ! LayerListConfigs.HUD_ENABLED;
        this.dirty = true;
        return LayerListConfigs.HUD_ENABLED;
    }

    public void markDirty()
    {
        this.dirty = true;
    }

    public boolean hasData()
    {
        return this.hasData;
    }

    /** Идёт проход по чанкам (данных ещё может не быть): вкладка «Материалы» отличает «считаю» от «считать нечего». */
    public boolean isCounting()
    {
        return this.counting;
    }

    public List<MaterialListEntry> getEntries()
    {
        this.syncStock();
        return this.entries;
    }

    /** Все строки счёта, без вычета склада: «Список HUD» в таблице, API аддонов. */
    public List<MaterialListEntry> getAllEntries()
    {
        return this.allEntries;
    }

    /** Строк спрятано «С учётом склада»: всё нужное уже есть в инвентаре и известных сундуках. */
    public int getStockHidden()
    {
        this.syncStock();
        return this.stockHidden;
    }

    /** Склад сейчас вычитается из списка на экране. */
    public boolean isStockApplied()
    {
        this.syncStock();
        return this.stock != null;
    }

    /**
     * Сколько ещё не хватает по строке: недостающее минус инвентарь, а «С учётом склада» — минус и известные
     * сундуки. Строки с данными (узор, черепки) сундуками не покрываются: склад помнит предметы только по id.
     */
    public int getShortfall(MaterialListEntry entry)
    {
        this.syncStock();
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, this.shortfall(entry)));
    }

    private long inStock(MaterialListEntry entry)
    {
        Availability stock = this.stock;
        return stock == null || DataItems.hasData(entry.getStack()) ? 0L : stock.inChests(entry.getStack());
    }

    /**
     * Отобрать строки по складу, если с прошлого раза сменилось то, от чего отбор зависит: тумблер, записи
     * сундуков или открытое хранилище. Пока открыт контейнер, список полный — иначе «Перенести всё необходимое»
     * не забрал бы как раз то, что лежит в этом сундуке. Вызывается и при чтении списка: кнопка переноса может
     * спросить страницу в тот же тик, когда открылось окно.
     */
    private void syncStock()
    {
        long signature = this.stockSignature();

        if (signature == this.stockSignature)
        {
            return;
        }

        this.stockSignature = signature;
        this.applyStock(signature != -1L);
        this.syncSearchToPage();
    }

    private long stockSignature()
    {
        if (! this.target.hud() || ! LayerListConfigs.USE_STOCK.getBooleanValue() || storageScreenOpen())
        {
            return -1L;
        }

        return StorageIndex.getInstance().revision();
    }

    private static boolean storageScreenOpen()
    {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof AbstractContainerScreen<?> && ! (screen instanceof InventoryScreen)
                && ! (screen instanceof CreativeModeInventoryScreen);
    }

    private void applyStock(boolean active)
    {
        List<MaterialListEntry> all = this.allEntries;

        if (! active || all.isEmpty())
        {
            this.stock = active ? Availability.capture() : null;
            this.entries = all;
            this.stockHidden = 0;
        }
        else
        {
            this.stock = Availability.capture();
            List<MaterialListEntry> shown = new ArrayList<>(all.size());

            for (MaterialListEntry entry : all)
            {
                // Готовая строка («Только недостающее» выкл.) — не нужда, её склад не прячет
                if (entry.getCountMissing() <= 0 || this.shortfall(entry) > 0L)
                {
                    shown.add(entry);
                }
            }

            this.stockHidden = all.size() - shown.size();
            this.entries = shown;
        }

        int perPage = Math.max(1, LayerListConfigs.ITEMS_PER_PAGE.getIntegerValue());
        int pages = Math.max(1, (this.entries.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
    }

    private long shortfall(MaterialListEntry entry)
    {
        return (long) entry.getCountMissing() - entry.getCountAvailable() - this.inStock(entry);
    }

    public long getBlocksTotal()
    {
        return this.blocksTotal;
    }

    public long getBlocksMissing()
    {
        return this.blocksMissing;
    }

    public int getUnverifiedChunks()
    {
        return this.unverifiedChunks;
    }

    public int getCachedChunks()
    {
        return this.cachedChunks;
    }

    /** Список на экране — закреплённый снимок из другого измерения, а не живой счёт. */
    public boolean isShowingPinned()
    {
        return this.showingPinned;
    }

    /** Идентификатор измерения, в котором снят закреплённый список, например {@code minecraft:the_nether}. */
    public String getPinnedDimension()
    {
        return this.pinned != null ? this.pinned.dimension() : "";
    }

    /** Имена размещений, вошедших в закреплённый список, через запятую. */
    public String getPinnedPlacements()
    {
        return this.pinned != null ? this.pinned.placements() : "";
    }

    public int getPage()
    {
        return this.page;
    }

    public List<MaterialListEntry> getPageEntries()
    {
        this.syncStock();
        List<MaterialListEntry> list = this.entries;
        int perPage = Math.max(1, LayerListConfigs.ITEMS_PER_PAGE.getIntegerValue());
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        int page = Math.min(this.page, pages - 1);
        int start = page * perPage;
        int end = Math.min(start + perPage, list.size());

        return start < end ? List.copyOf(list.subList(start, end)) : Collections.emptyList();
    }

    public void scrollPage(int direction)
    {
        this.syncStock();
        int perPage = Math.max(1, LayerListConfigs.ITEMS_PER_PAGE.getIntegerValue());
        int pages = Math.max(1, (this.entries.size() + perPage - 1) / perPage);
        this.page = Math.floorMod(this.page + direction, pages);
        this.syncSearchToPage();
    }

    /** Что ищет поиск страницы: вся область аддона ({@link CountScope#searchWholeScope}) или страница панели. */
    public List<MaterialListEntry> searchEntries()
    {
        this.syncStock();
        CountScope scope = LayerListApi.getCountScope();

        return scope != null && scope.isActive() && scope.searchWholeScope() ? this.entries : this.getPageEntries();
    }

    private void syncSearchToPage()
    {
        // Поиск по сундукам следует только за HUD
        if (! this.target.hud())
        {
            return;
        }

        CountScope scope = LayerListApi.getCountScope();

        if (scope != null && scope.isActive() && scope.searchWholeScope())
        {
            StorageSearch.getInstance().followPage(this.entries);
            return;
        }

        if (LayerListConfigs.SEARCH_FOLLOWS_PAGE.getBooleanValue())
        {
            StorageSearch.getInstance().followPage(this.getPageEntries());
        }
    }

    public void reset()
    {
        this.entries = Collections.emptyList();
        this.allEntries = Collections.emptyList();
        this.stock = null;
        this.stockHidden = 0;
        this.stockSignature = Long.MIN_VALUE;
        this.hasData = false;
        this.blocksTotal = 0L;
        this.blocksMissing = 0L;
        this.unverifiedChunks = 0;
        this.cachedChunks = 0;
        this.page = 0;
        this.pinned = null;
        this.showingPinned = false;
        this.abortCounting();
        this.dirty = true;
        this.lastRangeSignature = "";
        this.supports = new Long2ObjectOpenHashMap<>();
        this.supportsSignature = "";
        this.supportsReady = false;
        this.cache.save(true);
    }

    private void abortCounting()
    {
        this.counting = false;
        this.schematicBlocks = null;
        this.supportsActive = false;
        this.supportsBuilding = null;
        this.pendingChunks.clear();
        this.boxesInChunks.clear();
        this.unverifiedCounting = 0;
        this.cachedCounting = 0;
        this.countsTotal.clear();
        this.countsMissing.clear();
        this.countsMismatch.clear();
        this.dataTotal.clear();
        this.dataMissing.clear();
        this.dataMismatch.clear();
        this.entitiesInChunks.clear();
        this.entityTotal.clear();
        this.entityMissing.clear();
        this.entityMismatch.clear();
    }

    public void onClientTick(Minecraft mc)
    {
        if (mc.level == null || mc.player == null)
        {
            if (this.hasData) { this.reset(); }
            return;
        }

        if (this.hasData)
        {
            this.syncStock();
        }

        String sig = this.rangeSignature();

        if (! sig.equals(this.lastRangeSignature))
        {
            this.lastRangeSignature = sig;
            this.dirty = true;

            if (this.counting)
            {
                this.abortCounting();
            }
        }

        if (this.counting)
        {
            this.processChunks(mc);
            return;
        }

        ++this.idleTicks;

        if (this.dirty || this.idleTicks >= LayerListConfigs.REFRESH_INTERVAL.getIntegerValue())
        {
            this.startRecount(mc);
        }
    }

    private String rangeSignature()
    {
        if (! this.target.scope() && ! this.target.layers())
        {
            // Размещения целиком: ни слои, ни область счёт не меняют
            return "whole";
        }

        CountScope scope = this.target.scope() ? LayerListApi.getCountScope() : null;

        if (scope != null && scope.isActive())
        {
            return "scope:" + scope.getSignature()
                    + (scope.applyLayerRange() ? "+" + getLayerSignature() : "");
        }

        return getLayerSignature();
    }

    private static String getLayerSignature()
    {
        LayerRange range = MaterialCutoff.countRange();
        return range.getLayerMode().ordinal() + ":" + range.getAxis().ordinal() + ":"
                + range.getMinLayerBoundary() + ":" + range.getMaxLayerBoundary();
    }

    private void startRecount(Minecraft mc)
    {
        this.dirty = false;
        this.idleTicks = 0;

        CountScope scope = this.target.scope() ? LayerListApi.getCountScope() : null;
        boolean scopeActive = scope != null && scope.isActive();
        List<Box> scopeBoxes = scopeActive ? scope.getBoxes() : List.of();

        this.abortCounting();
        this.placementNames.clear();

        LayerRange range = MaterialCutoff.countRange();
        boolean useLayers = this.target.layers() && (! scopeActive || scope.applyLayerRange())
                && range.getLayerMode() != LayerMode.ALL;
        Predicate<SchematicPlacement> only = this.target.placements();
        // Опоры зависят и от того, чего нет в подписи кэша: поворот и отражение размещения при той же рамке, сам объект схемы
        StringBuilder supportSig = new StringBuilder();

        StringBuilder cacheSig = new StringBuilder(this.lastRangeSignature);
        cacheSig.append('|').append(Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue());
        // Версия правил сверки: поменялись — старые записи кэша проверки не годятся (gp1 — лозы и полки, 0.0.23)
        cacheSig.append("|gp1");
        // sp1 — опоры вне слоя считаются по схеме и лежат в счёте чанка (13.10.2026)
        cacheSig.append("|sp1");

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            // HUD — только включённые размещения; вкладка «Материалы» считает и выключенное (голограмма скрыта, а
            // сверить постройку с миром всё равно можно: блоки сверяются по файлу схемы, не по голограмме)
            if ((only == null && ! placement.isEnabled()) || (only != null && ! only.test(placement)))
            {
                continue;
            }

            this.placementNames.add(placement.getName());
            LitematicaSchematic schematic = placement.getSchematic();
            supportSig.append('|').append(placement.getName()).append(':').append(placement.getRotation()).append(':')
                    .append(placement.getMirror()).append(':').append(System.identityHashCode(schematic));
            // Замена блоков в схеме (0.0.27) — другой набор блоков: кэш дальних чанков от прежнего не годится
            cacheSig.append(BlockReplacements.signature(schematic));

            // У выключенного размещения Litematica не отдаёт подобластей «размещение включено» — вкладке нужны любые,
            // кроме подобластей, выключенных игроком по отдельности (эту часть строить не надо)
            SubRegionPlacement.RequiredEnabled required = only == null
                    ? SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED : SubRegionPlacement.RequiredEnabled.ANY;

            for (Map.Entry<String, Box> regionEntry : placement.getSubRegionBoxes(required).entrySet())
            {
                String regionName = regionEntry.getKey();
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                SubRegionPlacement subRegion = placement.getRelativeSubRegionPlacement(regionName);

                if (container == null || subRegion == null || ! subRegion.isEnabled())
                {
                    continue;
                }

                supportSig.append('/').append(regionName).append(':').append(subRegion.getRotation()).append(':')
                        .append(subRegion.getMirror());
                List<Box> worldBoxes = scopeActive
                        ? intersectWithScope(regionEntry.getValue(), scopeBoxes, regionName)
                        : List.of(regionEntry.getValue());

                if (worldBoxes.isEmpty())
                {
                    continue;
                }

                cacheSig.append('|').append(placement.getName()).append(':').append(regionName);

                for (Box worldBox : worldBoxes)
                {
                    cacheSig.append(':').append(worldBox.getPos1()).append('>').append(worldBox.getPos2());
                }

                RegionRef region = new RegionRef(placement, subRegion, schematic, regionName, container,
                        schematic.getBlockEntityMapForRegion(regionName));
                BiConsumer<ChunkPos, IntBoundingBox> collector = (pos, box) -> this.clampAndAddBox(pos, box, region);

                if (useLayers)
                {
                    PositionUtils.getLayerRangeClampedPerChunkBoxes(worldBoxes, range, collector);
                }
                else
                {
                    PositionUtils.getPerChunkBoxes(worldBoxes, collector);
                }
            }
        }

        if (LayerListConfigs.COUNT_ENTITIES.getBooleanValue() && mc.level != null)
        {
            for (SchematicEntities.Wanted wanted : SchematicEntities.collect(mc.level, only))
            {
                BlockPos at = wanted.blockPos();

                if ((useLayers && ! range.isPositionWithinRange(at)) || (scopeActive && ! inScope(scopeBoxes, at)))
                {
                    continue;
                }

                this.entitiesInChunks.put(new ChunkPos(at.getX() >> 4, at.getZ() >> 4), wanted);
            }
        }

        if (this.boxesInChunks.isEmpty() && this.entitiesInChunks.isEmpty())
        {
            if (this.target.hud() && this.publishPinned(mc))
            {
                return;
            }

            // Считать нечего в том же измерении, где снят снимок: размещение выключили или
            // убрали намеренно — снимок больше не про что, иначе он всплыл бы в соседнем мире.
            this.pinned = null;
            this.showingPinned = false;
            this.entries = Collections.emptyList();
            // И полный список: «Список HUD» в таблице и API аддонов показывали бы строки убранного размещения
            this.allEntries = Collections.emptyList();
            this.hasData = false;
            this.blocksTotal = 0L;
            this.blocksMissing = 0L;
            this.unverifiedChunks = 0;
            this.cachedChunks = 0;
            // Схем больше нет — поиск должен разжать старый набор предметов, иначе
            // отметки из прошлой схемы (или другого измерения) продолжают висеть
            // над сундуками. Сам поиск остаётся включённым и подхватит новую схему.
            this.syncSearchToPage();
            return;
        }

        this.supportsActive = useLayers && mc.level != null;

        if (this.supportsActive)
        {
            String signature = cacheSig + "#" + supportSig;
            boolean fresh = this.supportsReady && signature.equals(this.supportsSignature)
                    && Util.getMillis() - this.supportsBuiltAt < SUPPORTS_TTL_MS;

            if (! fresh)
            {
                // Опоры — заново: сменился слой, размещения или замены, либо вышел срок
                this.schematicBlocks = new SchematicBlocks(
                        DataManager.getSchematicPlacementManager().getAllSchematicsPlacements(), mc.level);
                this.supportsBuilding = new Long2ObjectOpenHashMap<>();
                this.supportsSignature = signature;
                this.supportsReady = false;
            }
        }

        this.cache.ensure(mc, cacheSig.toString());
        this.pendingChunks.addAll(this.boxesInChunks.keySet());

        for (ChunkPos pos : this.entitiesInChunks.keySet())
        {
            if (! this.boxesInChunks.containsKey(pos))
            {
                this.pendingChunks.add(pos);
            }
        }

        this.counting = true;
    }

    private static boolean inScope(List<Box> scopeBoxes, BlockPos pos)
    {
        for (Box box : scopeBoxes)
        {
            if (box.getPos1() == null || box.getPos2() == null)
            {
                continue;
            }

            BlockPos min = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
            BlockPos max = PositionUtils.getMaxCorner(box.getPos1(), box.getPos2());

            if (pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY()
                    && pos.getY() <= max.getY() && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ())
            {
                return true;
            }
        }

        return false;
    }

    private static List<Box> intersectWithScope(Box regionBox, List<Box> scopeBoxes, String regionName)
    {
        if (regionBox.getPos1() == null || regionBox.getPos2() == null)
        {
            return Collections.emptyList();
        }

        BlockPos regionMin = PositionUtils.getMinCorner(regionBox.getPos1(), regionBox.getPos2());
        BlockPos regionMax = PositionUtils.getMaxCorner(regionBox.getPos1(), regionBox.getPos2());
        ArrayList<Box> result = new ArrayList<>();

        for (Box scopeBox : scopeBoxes)
        {
            if (scopeBox.getPos1() == null || scopeBox.getPos2() == null)
            {
                continue;
            }

            BlockPos scopeMin = PositionUtils.getMinCorner(scopeBox.getPos1(), scopeBox.getPos2());
            BlockPos scopeMax = PositionUtils.getMaxCorner(scopeBox.getPos1(), scopeBox.getPos2());

            int minX = Math.max(regionMin.getX(), scopeMin.getX());
            int minY = Math.max(regionMin.getY(), scopeMin.getY());
            int minZ = Math.max(regionMin.getZ(), scopeMin.getZ());
            int maxX = Math.min(regionMax.getX(), scopeMax.getX());
            int maxY = Math.min(regionMax.getY(), scopeMax.getY());
            int maxZ = Math.min(regionMax.getZ(), scopeMax.getZ());

            if (minX > maxX || minY > maxY || minZ > maxZ)
            {
                continue;
            }

            result.add(new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), regionName));
        }

        return result;
    }

    private void clampAndAddBox(ChunkPos pos, IntBoundingBox box, RegionRef region)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level != null)
        {
            box = PositionUtils.clampBoxToWorldHeightRange(box, mc.level);

            if (box != null)
            {
                this.boxesInChunks.put(pos, new ChunkBox(box, region));
            }
        }
    }

    private void processChunks(Minecraft mc)
    {
        if (mc.level == null)
        {
            this.abortCounting();
            return;
        }

        CountScope scope = this.target.scope() ? LayerListApi.getCountScope() : null;
        // Тот же диапазон, что у отбора боксов и сущностей в startRecount: с «Считать до Y» — от низа мира до этой
        // высоты, а не текущий слой рендера (иначе блоки считались только на слое, а сущности — до высоты)
        LayerRange range = MaterialCutoff.countRange();
        boolean rangeAll = ! this.target.layers() || range.getLayerMode() == LayerMode.ALL
                || (scope != null && scope.isActive() && ! scope.applyLayerRange());
        boolean ignoreState = Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue();
        boolean onlyEmpty = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();
        long budget = LayerListConfigs.BLOCKS_PER_TICK.getIntegerValue();
        VerifyCache cache = this.cache;
        // Голограммы сущностей — для сверки рамок и стоек в загруженных чанках. Блоки и их данные
        // (узор баннера, черепки горшка) берутся только из файла схемы: от прорисовки счёт не зависит.
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        RegistryAccess registries = mc.level.registryAccess();
        SchematicBlocks blocks = this.schematicBlocks;
        // Опоры для этой подписи уже найдены — берутся готовыми; иначе ищутся в этом проходе (blocks != null)
        Long2ObjectOpenHashMap<SupportCells> knownSupports = this.supportsActive && blocks == null ? this.supports : null;
        Direction.Axis axis = range.getAxis();
        Direction layerUp = axis != null ? Direction.get(Direction.AxisDirection.POSITIVE, axis) : Direction.UP;
        Direction[] outBelow = {layerUp.getOpposite()};
        Direction[] outAbove = {layerUp};
        Direction[] outBoth = {layerUp, layerUp.getOpposite()};

        while (budget > 0 && ! this.pendingChunks.isEmpty())
        {
            ChunkPos pos = this.pendingChunks.poll();
            long chunkKey = chunkKey(pos);
            boolean verified = WorldUtils.isClientChunkLoaded(mc.level, pos.x(), pos.z());
            // Кэш-чанк Bobby — снимок блоков: сущностей в нём нет, сущностей блоков может не быть (no-block-entities).
            // Узор, черепки и профиль там не сверяются (только состояние), сущности схемы — как в незагруженном чанке
            boolean snapshot = verified && bobbyChunk(mc, pos);
            VerifyCache.ChunkCounts cached = verified ? null : cache.get(chunkKey);

            if (! verified)
            {
                if (cached != null)
                {
                    ++this.cachedCounting;
                }
                else
                {
                    ++this.unverifiedCounting;
                }
            }

            Object2IntOpenHashMap<BlockState> chunkMissing = verified ? new Object2IntOpenHashMap<>() : null;
            Object2IntOpenHashMap<BlockState> chunkMismatch = verified ? new Object2IntOpenHashMap<>() : null;
            Object2IntOpenHashMap<DataItems.Key> chunkMissingData = verified ? new Object2IntOpenHashMap<>() : null;
            Object2IntOpenHashMap<DataItems.Key> chunkMismatchData = verified ? new Object2IntOpenHashMap<>() : null;
            // Кэш проверки ключуется по состоянию: клетки с данными складываем в него по состоянию же,
            // чтобы чанк, выпавший из прорисовки, считался так же, как считался бы без данных.
            Object2IntOpenHashMap<BlockState> foldMissing = verified ? new Object2IntOpenHashMap<>() : null;
            Object2IntOpenHashMap<BlockState> foldMismatch = verified ? new Object2IntOpenHashMap<>() : null;
            // Чанк из кэша: клетки с данными по ключам и состояниям — разложить по ним недостачу кэша (она по
            // состоянию). Один ключ бывает в нескольких состояниях: баннер с узором стоя и на стене, в разные стороны
            HashMap<DataItems.Key, Object2IntOpenHashMap<BlockState>> cachedData = cached != null ? new HashMap<>() : null;
            // Опоры, найденные в чанке в этом проходе, — в supportsBuilding после чанка
            LongArrayList foundCells = blocks != null ? new LongArrayList() : null;
            ArrayList<BlockState> foundStates = blocks != null ? new ArrayList<>() : null;

            for (ChunkBox chunkBox : this.boxesInChunks.get(pos))
            {
                IntBoundingBox bb = chunkBox.box();
                RegionRef region = chunkBox.region();

                IntBoundingBox span = layerClamp(bb, range, rangeAll);

                if (span == null)
                {
                    continue;
                }

                int startX = span.minX();
                int startY = span.minY();
                int startZ = span.minZ();
                int endX = span.maxX();
                int endY = span.maxY();
                int endZ = span.maxZ();

                // Края куска вдоль оси слоёв, за которыми уже не слой: там блоку может понадобиться опора
                boolean lowOut = false;
                boolean highOut = false;
                int axisMin = 0;
                int axisMax = 0;

                if (blocks != null && axis != null)
                {
                    axisMin = axis.choose(startX, startY, startZ);
                    axisMax = axis.choose(endX, endY, endZ);
                    lowOut = ! GhostSupport.rawWithinRange(range, startX - (axis == Direction.Axis.X ? 1 : 0),
                            startY - (axis == Direction.Axis.Y ? 1 : 0), startZ - (axis == Direction.Axis.Z ? 1 : 0));
                    highOut = ! GhostSupport.rawWithinRange(range, endX + (axis == Direction.Axis.X ? 1 : 0),
                            endY + (axis == Direction.Axis.Y ? 1 : 0), endZ + (axis == Direction.Axis.Z ? 1 : 0));
                }

                posMutable.set(startX, startY, startZ);
                BlockPos base = SchematicUtils.getSchematicContainerPositionFromWorldPosition(posMutable,
                        region.schematic(), region.regionName(), region.placement(), region.subRegion(), region.container());

                if (base == null)
                {
                    continue;
                }

                int stepXx = 0;
                int stepXz = 0;
                int stepZx = 0;
                int stepZz = 0;
                int stepYy = 1;

                if (endX > startX)
                {
                    posMutable.set(startX + 1, startY, startZ);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(posMutable,
                            region.schematic(), region.regionName(), region.placement(), region.subRegion(), region.container());

                    if (probe == null)
                    {
                        continue;
                    }

                    stepXx = probe.getX() - base.getX();
                    stepXz = probe.getZ() - base.getZ();
                }

                if (endZ > startZ)
                {
                    posMutable.set(startX, startY, startZ + 1);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(posMutable,
                            region.schematic(), region.regionName(), region.placement(), region.subRegion(), region.container());

                    if (probe == null)
                    {
                        continue;
                    }

                    stepZx = probe.getX() - base.getX();
                    stepZz = probe.getZ() - base.getZ();
                }

                if (endY > startY)
                {
                    posMutable.set(startX, startY + 1, startZ);
                    BlockPos probe = SchematicUtils.getSchematicContainerPositionFromWorldPosition(posMutable,
                            region.schematic(), region.regionName(), region.placement(), region.subRegion(), region.container());

                    if (probe == null)
                    {
                        continue;
                    }

                    stepYy = probe.getY() - base.getY();
                }

                Rotation mainRotation = region.placement().getRotation();
                Rotation rotationCombined = mainRotation.getRotated(region.subRegion().getRotation());
                Mirror mirrorMain = region.placement().getMirror();
                Mirror mirrorSub = region.subRegion().getMirror();

                if (mirrorSub != Mirror.NONE
                        && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90))
                {
                    mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
                }

                // У некоторых схем заявленный размер региона больше фактического контейнера;
                // Litematica клампит base к контейнеру, а наша экстраполяция по шагам — нет,
                // поэтому без проверки границ индекс уезжает за конец LitematicaBitArray.
                Vec3i containerSize = region.container().getSize();
                int sizeX = containerSize.getX();
                int sizeY = containerSize.getY();
                int sizeZ = containerSize.getZ();

                for (int y = startY; y <= endY; ++y)
                {
                    int containerY = base.getY() + (y - startY) * stepYy;

                    if (containerY < 0 || containerY >= sizeY)
                    {
                        continue;
                    }

                    for (int z = startZ; z <= endZ; ++z)
                    {
                        for (int x = startX; x <= endX; ++x)
                        {
                            int containerX = base.getX() + (x - startX) * stepXx + (z - startZ) * stepZx;
                            int containerZ = base.getZ() + (x - startX) * stepXz + (z - startZ) * stepZz;

                            if (containerX < 0 || containerX >= sizeX || containerZ < 0 || containerZ >= sizeZ)
                            {
                                continue;
                            }

                            BlockState stateSchematic = region.container().get(containerX, containerY, containerZ);

                            if (stateSchematic.isAir() || stateSchematic.getBlock() == Blocks.STRUCTURE_VOID)
                            {
                                continue;
                            }

                            if (mirrorMain != Mirror.NONE)
                            {
                                stateSchematic = stateSchematic.mirror(mirrorMain);
                            }

                            if (mirrorSub != Mirror.NONE)
                            {
                                stateSchematic = stateSchematic.mirror(mirrorSub);
                            }

                            if (rotationCombined != Rotation.NONE)
                            {
                                stateSchematic = stateSchematic.rotate(rotationCombined);
                            }

                            if (lowOut || highOut)
                            {
                                int along = axis.choose(x, y, z);
                                boolean below = lowOut && along == axisMin;
                                boolean above = highOut && along == axisMax;

                                if (below || above)
                                {
                                    final boolean verifiedChunk = verified;
                                    final VerifyCache.ChunkCounts cachedChunk = cached;
                                    final Object2IntOpenHashMap<BlockState> supportMissing = chunkMissing;
                                    final Object2IntOpenHashMap<BlockState> supportMismatch = chunkMismatch;
                                    LayerSupports.collect(stateSchematic, new BlockPos(x, y, z),
                                            below && above ? outBoth : below ? outBelow : outAbove, blocks, cell ->
                                            {
                                                BlockState wanted = blocks.getBlockState(BlockPos.of(cell));

                                                if (! wanted.isAir())
                                                {
                                                    foundCells.add(cell);
                                                    foundStates.add(wanted);
                                                    this.countSupport(mc, wanted, cell, verifiedChunk, cachedChunk,
                                                            ignoreState, supportMissing, supportMismatch);
                                                }
                                            });
                                }
                            }

                            DataItems.Key dataKey = null;

                            if (region.blockEntities() != null && ItemData.matters(stateSchematic))
                            {
                                CompoundData tag = region.blockEntities().get(new BlockPos(containerX, containerY, containerZ));
                                ItemStack required = tag != null ? SchematicMaterials.withData(stateSchematic, tag, registries) : null;

                                if (required != null && DataItems.hasData(required))
                                {
                                    dataKey = new DataItems.Key(required);
                                }
                            }

                            // Невидимый свет: уровень — в данных предмета «Свет», а строки Litematica
                            // слипают все 16 уровней в одну (24.10.2026). Сущности блока у него нет
                            if (dataKey == null && stateSchematic.getBlock() instanceof net.minecraft.world.level.block.LightBlock)
                            {
                                dataKey = new DataItems.Key(ItemData.lightStack(stateSchematic));
                            }

                            if (dataKey != null)
                            {
                                this.dataTotal.addTo(dataKey, 1);
                            }
                            else
                            {
                                this.countsTotal.addTo(stateSchematic, 1);
                            }

                            if (! verified)
                            {
                                if (cached == null)
                                {
                                    if (dataKey != null)
                                    {
                                        this.dataMissing.addTo(dataKey, 1);
                                    }
                                    else
                                    {
                                        this.countsMissing.addTo(stateSchematic, 1);
                                    }
                                }
                                else if (dataKey != null)
                                {
                                    cachedData.computeIfAbsent(dataKey, key -> new Object2IntOpenHashMap<>())
                                            .addTo(stateSchematic, 1);
                                }

                                continue;
                            }

                            posMutable.set(x, y, z);
                            BlockState stateClient = mc.level.getBlockState(posMutable);

                            if (dataKey != null)
                            {
                                if (emptyCell(stateSchematic, stateClient))
                                {
                                    chunkMissingData.addTo(dataKey, 1);
                                    foldMissing.addTo(stateSchematic, 1);
                                }
                                else if (differs(stateSchematic, stateClient, ignoreState)
                                        // Блок тот, а узор (черепки, профиль) — другой: стоит не то, что в схеме.
                                        || (! snapshot && ! ItemData.same(ItemData.required(mc.level, posMutable, stateClient),
                                                dataKey.sample())))
                                {
                                    chunkMismatchData.addTo(dataKey, 1);
                                    foldMismatch.addTo(stateSchematic, 1);
                                }

                                continue;
                            }

                            if (emptyCell(stateSchematic, stateClient))
                            {
                                chunkMissing.addTo(stateSchematic, 1);
                            }
                            else if (Potting.needsPlant(stateSchematic, stateClient))
                            {
                                // Пустой горшок на месте горшка с цветком — не «не тот блок», а
                                // недостроенный: горшок готов, нужен только цветок (сажается кликом)
                                chunkMissing.addTo(Potting.plantState(stateSchematic), 1);
                            }
                            else if (differs(stateSchematic, stateClient, ignoreState))
                            {
                                chunkMismatch.addTo(stateSchematic, 1);
                            }
                        }
                    }
                }

                budget -= (long) (endX - startX + 1) * (endY - startY + 1) * (endZ - startZ + 1);
            }

            if (knownSupports != null)
            {
                SupportCells known = knownSupports.get(chunkKey);

                if (known != null)
                {
                    for (int i = 0; i < known.cells().length; ++i)
                    {
                        this.countSupport(mc, known.wanted()[i], known.cells()[i], verified, cached, ignoreState,
                                chunkMissing, chunkMismatch);
                    }
                }
            }
            else if (foundCells != null && ! foundCells.isEmpty() && this.supportsBuilding != null)
            {
                this.supportsBuilding.put(chunkKey, new SupportCells(foundCells.toLongArray(),
                        foundStates.toArray(new BlockState[0])));
            }

            List<SchematicEntities.Wanted> chunkEntities = this.entitiesInChunks.get(pos);

            if (! chunkEntities.isEmpty())
            {
                this.countEntities(mc, chunkEntities, verified ? schematicWorld : null, verified && ! snapshot, onlyEmpty);
            }

            if (verified)
            {
                accumulate(this.countsMissing, this.countsMismatch, chunkMissing, chunkMismatch, onlyEmpty);
                accumulate(this.dataMissing, this.dataMismatch, chunkMissingData, chunkMismatchData, onlyEmpty);
                cache.put(chunkKey, merged(chunkMissing, foldMissing), merged(chunkMismatch, foldMismatch),
                        new VerifyCache.DataCounts(chunkMissingData, chunkMismatchData, foldMissing, foldMismatch));
            }
            else if (cached != null)
            {
                this.addCached(cached, cachedData, onlyEmpty);
            }
        }

        if (this.pendingChunks.isEmpty())
        {
            this.publishResults(mc);
        }
    }

    private void publishResults(Minecraft mc)
    {
        if (this.supportsBuilding != null)
        {
            // Проход дошёл до конца — найденные опоры годятся для следующих пересчётов с той же подписью
            this.supports = this.supportsBuilding;
            this.supportsBuilding = null;
            this.supportsBuiltAt = Util.getMillis();
            this.supportsReady = true;
        }

        long total = 0L;
        long missing = 0L;

        for (BlockState state : this.countsTotal.keySet())
        {
            total += this.countsTotal.getInt(state);
        }

        for (BlockState state : this.countsMissing.keySet())
        {
            missing += this.countsMissing.getInt(state);
        }

        for (DataItems.Key key : this.dataTotal.keySet())
        {
            total += this.dataTotal.getInt(key);
        }

        for (DataItems.Key key : this.dataMissing.keySet())
        {
            missing += this.dataMissing.getInt(key);
        }

        for (DataItems.Key key : this.entityTotal.keySet())
        {
            total += this.entityTotal.getInt(key);
        }

        for (DataItems.Key key : this.entityMissing.keySet())
        {
            missing += this.entityMissing.getInt(key);
        }

        Object2IntOpenHashMap<BlockState> countsTotal = this.countsTotal;
        Object2IntOpenHashMap<BlockState> countsMissing = this.countsMissing;

        List<MaterialListEntry> list = MaterialRows.build(
                new MaterialRows.Counts<>(countsTotal, countsMissing, this.countsMismatch),
                new MaterialRows.Counts<>(this.dataTotal, this.dataMissing, this.dataMismatch),
                new MaterialRows.Counts<>(this.entityTotal, this.entityMissing, this.entityMismatch),
                mc.player);

        this.pinned = new Snapshot(new Object2IntOpenHashMap<>(countsTotal),
                new Object2IntOpenHashMap<>(countsMissing),
                new Object2IntOpenHashMap<>(this.countsMismatch),
                new Object2IntOpenHashMap<>(this.dataTotal),
                new Object2IntOpenHashMap<>(this.dataMissing),
                new Object2IntOpenHashMap<>(this.dataMismatch),
                new Object2IntOpenHashMap<>(this.entityTotal),
                new Object2IntOpenHashMap<>(this.entityMissing),
                new Object2IntOpenHashMap<>(this.entityMismatch),
                total, missing, String.join(", ", this.placementNames), dimensionId(mc));
        this.showingPinned = false;
        this.publish(list, total, missing, this.unverifiedCounting, this.cachedCounting);
    }

    /**
     * Размещений в этом измерении нет, но закрепление включено и снимок снят в другом
     * измерении — показываем его. Итоги схемы заморожены, «есть в инвентаре» — живое.
     * Кэш проверки здесь не трогаем: он ключуется по миру и измерению, а мы в чужом.
     */
    private boolean publishPinned(Minecraft mc)
    {
        Snapshot snap = this.pinned;

        if (snap == null || ! LayerListConfigs.PIN_ACROSS_DIMENSIONS.getBooleanValue()
                || snap.dimension().equals(dimensionId(mc)))
        {
            return false;
        }

        List<MaterialListEntry> list = MaterialRows.build(
                new MaterialRows.Counts<>(snap.total(), snap.missing(), snap.mismatch()),
                new MaterialRows.Counts<>(snap.dataTotal(), snap.dataMissing(), snap.dataMismatch()),
                new MaterialRows.Counts<>(snap.entityTotal(), snap.entityMissing(), snap.entityMismatch()),
                mc.player);
        this.publish(list, snap.blocksTotal(), snap.blocksMissing(), 0, 0);
        this.showingPinned = true;
        return true;
    }

    private static String dimensionId(Minecraft mc)
    {
        return mc.level != null ? mc.level.dimension().identifier().toString() : "";
    }

    private void publish(List<MaterialListEntry> list, long total, long missing, int unverified, int cached)
    {
        // Таблице нужны и готовые строки («Всё» — вся постройка); прятать готовое — дело только HUD
        if (this.target.hud() && LayerListConfigs.ONLY_MISSING.getBooleanValue())
        {
            list.removeIf(entry -> entry.getCountMissing() <= 0);
        }

        Comparator<MaterialListEntry> byName =
                Comparator.comparing(entry -> DataItems.label(entry.getStack()));

        if (LayerListConfigs.SORT_BY_NAME.getBooleanValue())
        {
            list.sort(byName);
        }
        else
        {
            list.sort(Comparator.comparingInt(MaterialListEntry::getCountMissing).reversed().thenComparing(byName));
        }

        this.allEntries = list;
        this.stockSignature = this.stockSignature();
        this.applyStock(this.stockSignature != -1L);
        this.blocksTotal = total;
        this.blocksMissing = missing;
        this.unverifiedChunks = unverified;
        this.cachedChunks = cached;
        this.hasData = true;

        this.abortCounting();
        this.idleTicks = 0;
        this.syncSearchToPage();
        this.cache.save(false);
    }

    /**
     * Сущности схемы этого чанка: итог — всегда, «не хватает» и «не то» — только когда чанк
     * клиента загружен. Сверка идёт с голограммой из мира схемы (там у сущности точные поворот,
     * цвет и содержимое); без голограммы — только «есть ли рядом сущность того же типа».
     */
    private void countEntities(Minecraft mc, List<SchematicEntities.Wanted> wanted, @Nullable WorldSchematic schematicWorld,
                               boolean verified, boolean onlyEmpty)
    {
        Object2IntOpenHashMap<DataItems.Key> missing = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<DataItems.Key> mismatch = new Object2IntOpenHashMap<>();

        for (SchematicEntities.Wanted entity : wanted)
        {
            List<ItemStack> items = entity.items();

            for (ItemStack item : items)
            {
                this.entityTotal.addTo(new DataItems.Key(item), 1);
            }

            if (! verified || mc.level == null)
            {
                continue;
            }

            Entity ghost = SchematicEntities.ghost(schematicWorld, entity);
            SchematicEntities.Match match = ghost != null
                    ? SchematicEntities.match(mc.level, ghost)
                    : SchematicEntities.matchApprox(mc.level, entity);
            List<SchematicEntities.Status> statuses = match.statuses();

            for (int i = 0; i < items.size() && i < statuses.size(); ++i)
            {
                if (statuses.get(i) == SchematicEntities.Status.MISSING)
                {
                    missing.addTo(new DataItems.Key(items.get(i)), 1);
                }
                else if (statuses.get(i) == SchematicEntities.Status.MISMATCH)
                {
                    mismatch.addTo(new DataItems.Key(items.get(i)), 1);
                }
            }
        }

        accumulate(this.entityMissing, this.entityMismatch, missing, mismatch, onlyEmpty);
    }

    /**
     * Опора вне слоя ({@link LayerSupports}) — в счёт чанка, где стоит её блок слоя: итог всегда, «не хватает»
     * и «не то» — как у клеток слоя (по миру в загруженном чанке, иначе всё недостаёт или берётся из кэша).
     * {@code wanted} — блок схемы в клетке опоры, не воздух.
     */
    private void countSupport(Minecraft mc, BlockState wanted, long cell, boolean verified,
                              @Nullable VerifyCache.ChunkCounts cached, boolean ignoreState,
                              @Nullable Object2IntOpenHashMap<BlockState> chunkMissing,
                              @Nullable Object2IntOpenHashMap<BlockState> chunkMismatch)
    {
        BlockPos pos = BlockPos.of(cell);
        this.countsTotal.addTo(wanted, 1);

        if (! verified || chunkMissing == null || chunkMismatch == null)
        {
            if (cached == null)
            {
                this.countsMissing.addTo(wanted, 1);
            }

            return;
        }

        BlockState have = mc.level.getBlockState(pos);

        if (emptyCell(wanted, have))
        {
            chunkMissing.addTo(wanted, 1);
        }
        else if (differs(wanted, have, ignoreState))
        {
            chunkMismatch.addTo(wanted, 1);
        }
    }

    /**
     * Недостача чанка из кэша. Кэш считает по состоянию, клетки с данными сложены туда же; проверенный в этой
     * игре чанк помнит и их раскладку по ключам. Чанк из файла кэша её не знает — тогда недостача состояния
     * отдаётся сначала клеткам с данными этого чанка ({@code data}), остаток — обычным.
     */
    private void addCached(VerifyCache.ChunkCounts cached, HashMap<DataItems.Key, Object2IntOpenHashMap<BlockState>> data,
                           boolean onlyEmpty)
    {
        Object2IntOpenHashMap<BlockState> missing = new Object2IntOpenHashMap<>(cached.missing());
        Object2IntOpenHashMap<BlockState> mismatch = new Object2IntOpenHashMap<>(cached.mismatch());
        Object2IntOpenHashMap<DataItems.Key> missingData = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<DataItems.Key> mismatchData = new Object2IntOpenHashMap<>();
        VerifyCache.DataCounts known = cached.data();

        if (known != null)
        {
            take(missing, known.foldMissing());
            take(mismatch, known.foldMismatch());
            missingData = known.missing();
            mismatchData = known.mismatch();
        }
        else
        {
            for (var keyEntry : data.entrySet())
            {
                for (var entry : keyEntry.getValue().object2IntEntrySet())
                {
                    BlockState state = entry.getKey();
                    int cells = entry.getIntValue();
                    int absent = Math.min(cells, missing.getInt(state));
                    int wrong = Math.min(cells - absent, mismatch.getInt(state));

                    if (absent > 0)
                    {
                        missing.addTo(state, -absent);
                        missingData.addTo(keyEntry.getKey(), absent);
                    }

                    if (wrong > 0)
                    {
                        mismatch.addTo(state, -wrong);
                        mismatchData.addTo(keyEntry.getKey(), wrong);
                    }
                }
            }

            missing.object2IntEntrySet().removeIf(entry -> entry.getIntValue() <= 0);
            mismatch.object2IntEntrySet().removeIf(entry -> entry.getIntValue() <= 0);
        }

        accumulate(this.countsMissing, this.countsMismatch, missing, mismatch, onlyEmpty);
        accumulate(this.dataMissing, this.dataMismatch, missingData, mismatchData, onlyEmpty);
    }

    /** Вычесть счёт {@code part} из {@code from}; что ушло в ноль — убрать. */
    private static void take(Object2IntOpenHashMap<BlockState> from, Object2IntOpenHashMap<BlockState> part)
    {
        for (var entry : part.object2IntEntrySet())
        {
            int left = from.getInt(entry.getKey()) - entry.getIntValue();

            if (left > 0)
            {
                from.put(entry.getKey(), left);
            }
            else
            {
                from.removeInt(entry.getKey());
            }
        }
    }

    /** Долить счёт чанка в общий: несовпадения идут и в «не хватает», если не просили считать только пустые. */
    private static <K> void accumulate(Object2IntOpenHashMap<K> intoMissing, Object2IntOpenHashMap<K> intoMismatch,
                                       Object2IntOpenHashMap<K> missing, Object2IntOpenHashMap<K> mismatch, boolean onlyEmpty)
    {
        for (var entry : missing.object2IntEntrySet())
        {
            intoMissing.addTo(entry.getKey(), entry.getIntValue());
        }

        for (var entry : mismatch.object2IntEntrySet())
        {
            intoMismatch.addTo(entry.getKey(), entry.getIntValue());

            if (! onlyEmpty)
            {
                intoMissing.addTo(entry.getKey(), entry.getIntValue());
            }
        }
    }

    private static Object2IntOpenHashMap<BlockState> merged(Object2IntOpenHashMap<BlockState> a, Object2IntOpenHashMap<BlockState> b)
    {
        if (b.isEmpty())
        {
            return a;
        }

        Object2IntOpenHashMap<BlockState> out = new Object2IntOpenHashMap<>(a);

        for (var entry : b.object2IntEntrySet())
        {
            out.addTo(entry.getKey(), entry.getIntValue());
        }

        return out;
    }

    /**
     * Стоит в мире не то, что в схеме. Кроме точного совпадения и «Игнорировать состояние»
     * (тот же блок), своё правило у лоз: возраст у них при установке случайный, а голова на
     * месте тела — недостроенная колонна, а не ошибка; см. {@link GrowingPlants#sameForList}.
     */
    private static boolean differs(BlockState schematic, BlockState client, boolean ignoreState)
    {
        if (client == schematic || (ignoreState && client.getBlock() == schematic.getBlock()))
        {
            return false;
        }

        // Угол ступени другой записью — та же фигура (Easy Place сам ставит так, где ваниль иначе не соберёт)
        if (com.aell.layerlist.easyplace.Stairs.sameShape(schematic, client))
        {
            return false;
        }

        // Полка: «запитана» и цепочка зависят от редстоуна рядом, а не от того, какую полку
        // поставили, — разница в них не про материал. Важны сторона и вода.
        if (schematic.getBlock() instanceof ShelfBlock && client.is(schematic.getBlock()))
        {
            return client.getValue(ShelfBlock.FACING) != schematic.getValue(ShelfBlock.FACING)
                    || client.getValue(ShelfBlock.WATERLOGGED) != schematic.getValue(ShelfBlock.WATERLOGGED);
        }

        return ! GrowingPlants.sameForList(schematic, client);
    }

    /**
     * Клетка «пуста» для подсчёта: воздух или жидкость. Ламинария и водоросли ставятся в
     * воду — вода в их клетке значит «не поставлено», а не «стоит не то» (с «Нехватка —
     * только пустые» они иначе вовсе выпадали из нехватки).
     */
    private static boolean emptyCell(BlockState schematic, BlockState client)
    {
        if (client.isAir())
        {
            return true;
        }

        // Схема сама просит воду или лаву: такой же источник в мире — построено, а не «пусто» (1.0.22: строка
        // «Ведро воды» у фонтанов и прудов не уменьшалась никогда). Течение на месте источника — по-прежнему пусто
        if (client.getBlock() instanceof LiquidBlock)
        {
            return ! (schematic.getBlock() == client.getBlock() && client.getValue(LiquidBlock.LEVEL) == 0);
        }

        return false;
    }

    /**
     * Часть бокса, которая попадает в диапазон слоёв; null — не попадает ничего. Обрезку делает
     * malilib ({@link LayerRange#getClampedBox}); здесь — только случаи, где его ответ расходится с
     * тем, что нужно счёту: он строит итог через {@code createProper}, то есть вывернутый бокс или
     * вывернутый диапазон не отбрасывает, а разворачивает, а без оси падает на {@code switch}.
     */
    static @Nullable IntBoundingBox layerClamp(IntBoundingBox bb, LayerRange range, boolean rangeAll)
    {
        // Бокс «наизнанку» пуст при любом диапазоне
        if (bb.minX() > bb.maxX() || bb.minY() > bb.maxY() || bb.minZ() > bb.maxZ())
        {
            return null;
        }

        // Весь объём (режим ALL или область счёта без слоёв) и диапазон без оси бокс не режут
        if (rangeAll || range.getAxis() == null)
        {
            return bb;
        }

        // LAYER_RANGE с min > max бывает только из файла настроек — в такой диапазон не попадает ничего
        if (range.getMinLayerBoundary() > range.getMaxLayerBoundary())
        {
            return null;
        }

        return range.getClampedBox(bb);
    }

    /** Чанк клиента — кэш-чанк Bobby ({@code FakeChunk}), а не присланный сервером. */
    private static boolean bobbyChunk(Minecraft mc, ChunkPos pos)
    {
        net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.x(), pos.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        return chunk != null && chunk.getClass().getName().equals("de.johni0702.minecraft.bobby.FakeChunk");
    }

    private static long chunkKey(ChunkPos pos)
    {
        return ((long) pos.z() << 32) | (pos.x() & 0xFFFFFFFFL);
    }

    /** {@code blockEntities} — данные блоков региона из файла схемы, по координатам контейнера. */
    private record RegionRef(SchematicPlacement placement, SubRegionPlacement subRegion, LitematicaSchematic schematic,
                             String regionName, LitematicaBlockStateContainer container,
                             @Nullable Map<BlockPos, CompoundData> blockEntities) {}

    private record ChunkBox(IntBoundingBox box, RegionRef region) {}

    /** Опоры вне слоя одного чанка слоя: клетки ({@link BlockPos#asLong}) и блоки схемы в них, по порядку находки. */
    private record SupportCells(long[] cells, BlockState[] wanted) {}
}
