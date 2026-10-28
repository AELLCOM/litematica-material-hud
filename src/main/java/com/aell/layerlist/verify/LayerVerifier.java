package com.aell.layerlist.verify;

import com.aell.layerlist.api.CountScope;
import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.ghost.GhostSupport;
import com.aell.layerlist.ghost.InventoryGhosts;
import com.aell.layerlist.shaded.ui.Strings;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.aell.layerlist.util.Chat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import com.aell.layerlist.core.SchematicEntities;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class LayerVerifier
{
    public enum Category
    {
        MISSING("missing", 0xFFFF5555),
        WRONG_BLOCK("wrong_block", 0xFFFFA030),
        WRONG_STATE("wrong_state", 0xFFFFE050),
        EXTRA("extra", 0xFF55AAFF);

        private final String key;
        private final int color;

        Category(String key, int color)
        {
            this.key = key;
            this.color = color;
        }

        public int color()
        {
            return this.color;
        }

        public String label()
        {
            return Strings.plain("layerlist.verify." + this.key);
        }

        public boolean isEnabled()
        {
            return switch (this)
            {
                case MISSING -> LayerListConfigs.VERIFY_MISSING.getBooleanValue();
                case WRONG_BLOCK -> LayerListConfigs.VERIFY_WRONG_BLOCK.getBooleanValue();
                case WRONG_STATE -> LayerListConfigs.VERIFY_WRONG_STATE.getBooleanValue();
                case EXTRA -> LayerListConfigs.VERIFY_EXTRA.getBooleanValue();
            };
        }
    }

    public record ScanBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        public int sizeX() { return this.maxX - this.minX + 1; }
        public int sizeY() { return this.maxY - this.minY + 1; }
        public int sizeZ() { return this.maxZ - this.minZ + 1; }
    }

    private static final Category[] CATEGORIES = Category.values();

    private static final int MAX_FOUND = 60_000;

    private static final int RANGE_INTERVAL = 10;

    private static final int RETRY_INTERVAL = 100;

    private static final byte NONE = -1;

    private static final LayerVerifier INSTANCE = new LayerVerifier();

    private final Long2ByteOpenHashMap found = newMap();

    private final IntOpenHashSet scannedLayers = new IntOpenHashSet();

    private final IntOpenHashSet incompleteLayers = new IntOpenHashSet();

    private final IntArrayList queue = new IntArrayList();

    private final LongOpenHashSet reloadedChunks = new LongOpenHashSet();

    private final EnumMap<Category, List<BlockPos>> visible = new EnumMap<>(Category.class);

    private final int[] shown = new int[CATEGORIES.length];

    /**
     * Сущность схемы, которой в мире нет или которая стоит не так (рамка, стойка, подушка, картина…): рамка по
     * габариту её голограммы. Красная — нет самой сущности или предмета в рамке, жёлтая — повёрнута не так,
     * другой цвет подушки, другой предмет в рамке.
     */
    public record EntityMark(AABB box, Category category) {}

    private final List<EntityMark> entityMarks = new ArrayList<>();

    private boolean enabled;

    private boolean limitReached;
    private boolean announceResult;

    private String signature = "";

    private List<ScanBox> volume = List.of();

    private int volumeMin;
    private int volumeMax;

    private int queuedSliceMin = Integer.MIN_VALUE;
    private int queuedSliceMax = Integer.MAX_VALUE;

    private int queueIndex;
    private int planeBoxIndex;
    private long planeIndex;
    private boolean planeIncomplete;
    private int retryTicks = RETRY_INTERVAL;

    private boolean layerFilter;
    private Direction.Axis layerAxis = Direction.Axis.Y;
    private int layerMin = Integer.MIN_VALUE;
    private int layerMax = Integer.MAX_VALUE;

    private boolean visibleDirty;
    /** Версия набора предметов «голограмм по инвентарю», под которую собраны рамки. */
    private int inventoryVersion = -1;
    private boolean supportFilter;
    private int rangeTicks;
    private BlockPos lastRenderOrigin = BlockPos.ZERO;

    private LayerVerifier()
    {
        for (Category category : CATEGORIES)
        {
            this.visible.put(category, new ArrayList<>());
        }
    }

    private static Long2ByteOpenHashMap newMap()
    {
        Long2ByteOpenHashMap map = new Long2ByteOpenHashMap();
        map.defaultReturnValue(NONE);

        return map;
    }

    public static LayerVerifier getInstance()
    {
        return INSTANCE;
    }

    public boolean isHighlighting()
    {
        return this.enabled;
    }

    public boolean isScanning()
    {
        return this.queueIndex < this.queue.size();
    }

    public int getProgressPercent()
    {
        int total = this.queue.size();

        return total > 0 ? this.queueIndex * 100 / total : 100;
    }

    public int getCount(Category category)
    {
        return this.shown[category.ordinal()];
    }

    public Map<Category, List<BlockPos>> getVisible()
    {
        return this.visible;
    }

    public List<EntityMark> getEntityMarks()
    {
        return this.entityMarks;
    }

    public boolean isLimitReached()
    {
        return this.limitReached;
    }

    public String getSummary()
    {
        if (! this.enabled)
        {
            return Strings.plain("layerlist.verify.off");
        }

        if (this.isScanning() && this.found.isEmpty())
        {
            return StringUtils.translate("layerlist.verify.scanning", this.getProgressPercent());
        }

        StringBuilder builder = new StringBuilder();
        boolean byInventory = InventoryGhosts.active();

        for (Category category : CATEGORIES)
        {
            if (! category.isEnabled() || (byInventory && category == Category.EXTRA))
            {
                continue;
            }

            if (builder.length() > 0)
            {
                builder.append(" · ");
            }

            builder.append(label(category)).append(' ').append(this.getCount(category));
        }

        if (builder.length() == 0)
        {
            return Strings.plain("layerlist.verify.no_categories");
        }

        return byInventory ? Strings.plain("layerlist.verify.inventory_prefix") + " " + builder : builder.toString();
    }

    public MutableComponent toggle()
    {
        if (this.enabled)
        {
            this.stop();
            return Chat.tr("layerlist.message.verify_off", Chat.DIM);
        }

        if (! LayerListConfigs.VERIFY_ENABLED.getBooleanValue())
        {
            return Chat.tr("layerlist.message.verify_disabled", Chat.WARN);
        }

        if (SchematicWorldHandler.getSchematicWorld() == null)
        {
            return Chat.tr("layerlist.message.verify_no_placement", Chat.WARN);
        }

        if (scanVolume().isEmpty())
        {
            return Chat.tr("layerlist.message.verify_empty", Chat.WARN);
        }

        this.enabled = true;
        this.announceResult = true;
        this.refreshLayers();
        this.retarget(currentSignature());

        return Chat.tr("layerlist.message.verify_started", Chat.OK,
                Chat.text(String.valueOf(this.queuedPositions()), Chat.TEXT));
    }

    public MutableComponent summaryComponent()
    {
        if (! this.enabled)
        {
            return Chat.tr("layerlist.verify.off", Chat.DIM);
        }

        if (this.isScanning() && this.found.isEmpty())
        {
            return Chat.tr("layerlist.verify.scanning", Chat.TEXT, this.getProgressPercent());
        }

        List<MutableComponent> parts = new ArrayList<>();
        boolean byInventory = InventoryGhosts.active();

        for (Category category : CATEGORIES)
        {
            // «Лишних» в режиме «только блоки из инвентаря» нет — там ничего ставить не надо
            if (! category.isEnabled() || (byInventory && category == Category.EXTRA))
            {
                continue;
            }

            int count = this.getCount(category);
            parts.add(Chat.text(label(category) + " " + count, count > 0 ? category.color() : Chat.DIM));
        }

        if (parts.isEmpty())
        {
            return Chat.tr("layerlist.verify.no_categories", Chat.WARN);
        }

        MutableComponent out = Chat.joined(" · ", Chat.DIM, parts);

        if (byInventory)
        {
            out = Chat.text(Strings.plain("layerlist.verify.inventory_prefix") + " ", Chat.DIM).append(out);
        }

        if (this.limitReached)
        {
            out.append(Chat.text(" — ", Chat.DIM))
                    .append(Chat.tr("layerlist.message.verify_limit", Chat.WARN));
        }

        return out;
    }

    public void stop()
    {
        this.enabled = false;
        this.limitReached = false;
        this.announceResult = false;
        this.signature = "";
        this.volume = List.of();
        this.visibleDirty = false;
        this.found.clear();
        this.scannedLayers.clear();
        this.incompleteLayers.clear();
        this.queue.clear();
        this.queueIndex = 0;
        this.reloadedChunks.clear();
        this.clearVisible();
    }

    private void clearVisible()
    {
        for (Category category : CATEGORIES)
        {
            this.visible.get(category).clear();
            this.shown[category.ordinal()] = 0;
        }

        this.entityMarks.clear();
    }

    public void onClientTick(Minecraft mc)
    {
        if (! this.enabled)
        {
            return;
        }

        if (mc.level == null || mc.player == null || ! LayerListConfigs.VERIFY_ENABLED.getBooleanValue())
        {
            this.stop();
            return;
        }

        if (SchematicWorldHandler.getSchematicWorld() == null)
        {
            if (! this.found.isEmpty() || ! this.signature.isEmpty())
            {
                this.forget();
                this.signature = "";
                this.visibleDirty = true;
            }
        }
        else
        {
            boolean axisChanged = this.refreshLayers();
            String current = currentSignature();

            if (! current.equals(this.signature))
            {
                this.retarget(current);
            }
            else if (axisChanged)
            {
                this.scannedLayers.clear();
                this.incompleteLayers.clear();
                this.recomputeVolumeBounds();
                this.refreshQueue(mc);
            }
            else if (this.sliceChanged())
            {
                this.refreshQueue(mc);
            }

            if (this.incompleteLayers.isEmpty())
            {
                this.retryTicks = RETRY_INTERVAL;
            }
            else if (--this.retryTicks <= 0)
            {
                this.retryTicks = RETRY_INTERVAL;
                this.incompleteLayers.clear();
                this.refreshQueue(mc);
            }

            if (! this.processReloadedChunks(mc))
            {
                this.scan(mc);
            }

            if (this.announceResult && ! this.isScanning())
            {
                this.announce(mc);
            }
        }

        // Режим «только блоки из инвентаря» или набор предметов сменился — «пропущено» пересчитать
        if (InventoryGhosts.version() != this.inventoryVersion)
        {
            this.inventoryVersion = InventoryGhosts.version();
            this.visibleDirty = true;
        }

        // То же при переключении «скрывать блоки без опоры»
        if (GhostSupport.hideUnsupported() != this.supportFilter)
        {
            this.supportFilter = GhostSupport.hideUnsupported();
            this.visibleDirty = true;
        }

        if (this.visibleDirty || --this.rangeTicks <= 0
                || ! mc.player.blockPosition().equals(this.lastRenderOrigin))
        {
            this.rangeTicks = RANGE_INTERVAL;
            this.lastRenderOrigin = mc.player.blockPosition();
            this.visibleDirty = false;
            this.rebuildVisible(mc);
        }
    }

    private void forget()
    {
        this.found.clear();
        this.scannedLayers.clear();
        this.incompleteLayers.clear();
        this.queue.clear();
        this.queueIndex = 0;
        this.reloadedChunks.clear();
        this.planeBoxIndex = 0;
        this.planeIndex = 0;
        this.planeIncomplete = false;
    }

    private void retarget(String newSignature)
    {
        this.signature = newSignature;
        this.volume = scanVolume();
        this.scannedLayers.clear();
        this.incompleteLayers.clear();
        this.limitReached = false;
        this.visibleDirty = true;
        this.reloadedChunks.clear();

        if (this.volume.isEmpty())
        {
            this.forget();
            return;
        }

        ObjectIterator<Long2ByteMap.Entry> it = this.found.long2ByteEntrySet().fastIterator();

        while (it.hasNext())
        {
            if (! this.contains(it.next().getLongKey()))
            {
                it.remove();
            }
        }

        this.recomputeVolumeBounds();
        this.refreshQueue(Minecraft.getInstance());
    }

    private void recomputeVolumeBounds()
    {
        this.volumeMin = Integer.MAX_VALUE;
        this.volumeMax = Integer.MIN_VALUE;

        for (ScanBox box : this.volume)
        {
            this.volumeMin = Math.min(this.volumeMin, this.axisMin(box));
            this.volumeMax = Math.max(this.volumeMax, this.axisMax(box));
        }
    }

    private void refreshQueue(Minecraft mc)
    {
        this.queue.clear();
        this.queueIndex = 0;
        this.planeBoxIndex = 0;
        this.planeIndex = 0;
        this.planeIncomplete = false;
        this.queuedSliceMin = this.sliceMin();
        this.queuedSliceMax = this.sliceMax();

        if (this.volume.isEmpty())
        {
            return;
        }

        int min = Math.max(this.sliceMin(), this.volumeMin);
        int max = Math.min(this.sliceMax(), this.volumeMax);

        if (min > max)
        {
            return;
        }

        int center = mc.player == null ? min : this.axisOf(mc.player.blockPosition());
        center = Math.max(min, Math.min(max, center));

        for (int distance = 0; distance <= Math.max(center - min, max - center); ++distance)
        {
            this.offer(center - distance, min, max);

            if (distance > 0)
            {
                this.offer(center + distance, min, max);
            }
        }
    }

    private long queuedPositions()
    {
        long total = 0;

        for (int i = this.queueIndex; i < this.queue.size(); ++i)
        {
            int layer = this.queue.getInt(i);

            for (ScanBox box : this.volume)
            {
                if (layer >= this.axisMin(box) && layer <= this.axisMax(box))
                {
                    total += this.planeVolume(box);
                }
            }
        }

        return total;
    }

    private void offer(int layer, int min, int max)
    {
        if (layer >= min && layer <= max
                && ! this.scannedLayers.contains(layer) && ! this.incompleteLayers.contains(layer))
        {
            this.queue.add(layer);
        }
    }

    public void onChunkLoaded(int chunkX, int chunkZ)
    {
        if (! this.enabled || this.scannedLayers.isEmpty())
        {
            return;
        }

        int minX = chunkX << 4;
        int minZ = chunkZ << 4;

        for (ScanBox box : this.volume)
        {
            if (box.minX() <= minX + 15 && box.maxX() >= minX
                    && box.minZ() <= minZ + 15 && box.maxZ() >= minZ)
            {
                this.reloadedChunks.add(((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL));
                return;
            }
        }
    }

    private boolean processReloadedChunks(Minecraft mc)
    {
        if (this.reloadedChunks.isEmpty())
        {
            return false;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (schematicWorld == null || mc.level == null)
        {
            this.reloadedChunks.clear();
            return false;
        }

        long budget = LayerListConfigs.BLOCKS_PER_TICK.getIntegerValue();
        LongIterator it = this.reloadedChunks.iterator();

        while (budget > 0 && it.hasNext())
        {
            long key = it.nextLong();
            it.remove();

            int chunkX = (int) key;
            int chunkZ = (int) (key >> 32);

            if (WorldUtils.isClientChunkLoaded(mc.level, chunkX, chunkZ))
            {
                budget -= this.rescanChunk(mc, schematicWorld, chunkX, chunkZ);
            }
        }

        this.visibleDirty = true;

        return true;
    }

    private long rescanChunk(Minecraft mc, WorldSchematic schematicWorld, int chunkX, int chunkZ)
    {
        long checked = 16;
        int chunkMinX = chunkX << 4;
        int chunkMinZ = chunkZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (ScanBox box : this.volume)
        {
            int x1 = Math.max(box.minX(), chunkMinX);
            int x2 = Math.min(box.maxX(), chunkMinX + 15);
            int z1 = Math.max(box.minZ(), chunkMinZ);
            int z2 = Math.min(box.maxZ(), chunkMinZ + 15);

            if (x1 > x2 || z1 > z2)
            {
                continue;
            }

            for (int y = box.minY(); y <= box.maxY(); ++y)
            {
                for (int z = z1; z <= z2; ++z)
                {
                    for (int x = x1; x <= x2; ++x)
                    {
                        int layer = switch (this.layerAxis)
                        {
                            case X -> x;
                            case Y -> y;
                            case Z -> z;
                        };

                        if (! this.scannedLayers.contains(layer))
                        {
                            continue;
                        }

                        ++checked;
                        pos.set(x, y, z);

                        long key = pos.asLong();
                        Category category = categoryAt(schematicWorld.getBlockState(pos),
                                mc.level.getBlockState(pos));

                        if (category == null)
                        {
                            this.found.remove(key);
                        }
                        else if (this.found.size() < MAX_FOUND || this.found.containsKey(key))
                        {
                            this.found.put(key, (byte) category.ordinal());
                        }
                        else
                        {
                            this.limitReached = true;
                        }
                    }
                }
            }
        }

        return checked;
    }

    private void scan(Minecraft mc)
    {
        if (this.queueIndex >= this.queue.size())
        {
            return;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (schematicWorld == null || mc.level == null)
        {
            return;
        }

        long budget = LayerListConfigs.BLOCKS_PER_TICK.getIntegerValue();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        boolean chunkLoaded = false;

        while (budget > 0 && this.queueIndex < this.queue.size())
        {
            int layer = this.queue.getInt(this.queueIndex);

            while (budget > 0 && this.planeBoxIndex < this.volume.size())
            {
                ScanBox box = this.volume.get(this.planeBoxIndex);
                long planeVolume = layer >= this.axisMin(box) && layer <= this.axisMax(box)
                        ? this.planeVolume(box)
                        : 0L;

                while (budget > 0 && this.planeIndex < planeVolume)
                {
                    this.setPlanePos(pos, box, layer, this.planeIndex++);
                    --budget;

                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;

                    if (chunkX != lastChunkX || chunkZ != lastChunkZ)
                    {
                        lastChunkX = chunkX;
                        lastChunkZ = chunkZ;
                        chunkLoaded = WorldUtils.isClientChunkLoaded(mc.level, chunkX, chunkZ);
                    }

                    if (! chunkLoaded)
                    {
                        this.planeIncomplete = true;
                        continue;
                    }

                    long key = pos.asLong();
                    Category category = categoryAt(schematicWorld.getBlockState(pos),
                            mc.level.getBlockState(pos));

                    if (category == null)
                    {
                        this.found.remove(key);
                    }
                    else if (this.found.size() < MAX_FOUND || this.found.containsKey(key))
                    {
                        this.found.put(key, (byte) category.ordinal());
                    }
                    else
                    {
                        this.limitReached = true;
                    }
                }

                if (this.planeIndex >= planeVolume)
                {
                    ++this.planeBoxIndex;
                    this.planeIndex = 0;
                }
            }

            if (this.planeBoxIndex >= this.volume.size())
            {
                if (this.planeIncomplete)
                {
                    this.incompleteLayers.add(layer);
                }
                else
                {
                    this.scannedLayers.add(layer);
                }

                ++this.queueIndex;
                this.planeBoxIndex = 0;
                this.planeIndex = 0;
                this.planeIncomplete = false;
                this.visibleDirty = true;
            }
        }

    }

    private void announce(Minecraft mc)
    {
        this.announceResult = false;
        this.rebuildVisible(mc);
        this.visibleDirty = false;

        Chat.send(this.summaryComponent());
    }

    private void rebuildVisible(Minecraft mc)
    {
        if (mc.player == null)
        {
            return;
        }

        long range = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();
        long rangeSq = range * range;
        int limit = LayerListConfigs.VERIFY_MAX_MARKERS.getIntegerValue();
        BlockPos origin = mc.player.blockPosition();
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        this.clearVisible();

        boolean byInventory = InventoryGhosts.active();
        boolean bySupport = GhostSupport.hideUnsupported() && mc.level != null;
        WorldSchematic schematicWorld = byInventory || bySupport ? SchematicWorldHandler.getSchematicWorld() : null;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        ObjectIterator<Long2ByteMap.Entry> it = this.found.long2ByteEntrySet().fastIterator();

        while (it.hasNext())
        {
            Long2ByteMap.Entry entry = it.next();
            long key = entry.getLongKey();
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);

            if (! this.withinLayers(x, y, z))
            {
                continue;
            }

            Category category = CATEGORIES[entry.getByteValue()];

            if (byInventory && schematicWorld != null && ! shownByInventory(category, schematicWorld, mc, cell.set(x, y, z)))
            {
                continue;
            }

            // «Скрывать блоки без опоры»: голограммы нет — нет и рамки (клетка свободна, держаться не на чем)
            if (bySupport && schematicWorld != null && category == Category.MISSING
                    && GhostSupport.unsupported(schematicWorld.getBlockState(cell.set(x, y, z)),
                            mc.level.getBlockState(cell), cell, mc.level, schematicWorld))
            {
                continue;
            }

            ++this.shown[category.ordinal()];

            if (! category.isEnabled())
            {
                continue;
            }

            List<BlockPos> list = this.visible.get(category);

            if (list.size() >= limit)
            {
                continue;
            }

            long dx = x - originX;
            long dy = y - originY;
            long dz = z - originZ;

            if (dx * dx + dy * dy + dz * dz <= rangeSq)
            {
                list.add(new BlockPos(x, y, z));
            }
        }

        this.rebuildEntities(mc, rangeSq, limit);
    }

    /**
     * Сущности схемы в той же области, слоях и дальности, что блоки (жалоба 26.10.2026: Ctrl+B не подсвечивал
     * подушки, стойки и рамки). Сверка — та же, что у счёта ({@link SchematicEntities#match}): голограмма сущности
     * против настоящей рядом; без голограммы — по типу и месту. Только загруженные чанки: в незагруженных мира нет.
     */
    private void rebuildEntities(Minecraft mc, long rangeSq, int limit)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();


        if (mc.level == null || mc.player == null || world == null || this.volume.isEmpty())
        {
            return;
        }

        int[] marked = new int[CATEGORIES.length];

        for (SchematicEntities.Wanted wanted : SchematicEntities.collect(mc.level))
        {
            BlockPos pos = wanted.blockPos();

            if (! this.contains(pos.asLong()) || ! this.withinLayers(pos.getX(), pos.getY(), pos.getZ())
                    || ! WorldUtils.isClientChunkLoaded(mc.level, pos.getX() >> 4, pos.getZ() >> 4))
            {
                continue;
            }

            net.minecraft.world.entity.Entity ghost = SchematicEntities.ghost(world, wanted);
            SchematicEntities.Match match = ghost != null ? SchematicEntities.match(mc.level, ghost)
                    : SchematicEntities.matchApprox(mc.level, wanted);

            if (match.satisfied())
            {
                continue;
            }

            Category category = match.statuses().contains(SchematicEntities.Status.MISSING) ? Category.MISSING
                    : Category.WRONG_STATE;
            ++this.shown[category.ordinal()];

            if (! category.isEnabled() || marked[category.ordinal()] >= limit
                    || wanted.pos().distanceToSqr(mc.player.position()) > rangeSq)
            {
                continue;
            }

            ++marked[category.ordinal()];
            AABB box = ghost != null ? ghost.getBoundingBox() : AABB.ofSize(wanted.pos(), 0.5, 0.5, 0.5);
            this.entityMarks.add(new EntityMark(box.inflate(0.03), category));
        }
    }

    private void patchVisible(BlockPos pos, @Nullable Category previous, @Nullable Category current)
    {
        // С фильтром по инвентарю «посчитана ли клетка» зависит от предметов на момент сборки, без опоры —
        // от соседей (поставили опору — держится и висящее рядом); точечная правка счётчиков разошлась бы
        // с ними — пересобрать целиком в этом же тике
        if (InventoryGhosts.active() || GhostSupport.hideUnsupported())
        {
            this.visibleDirty = true;
            return;
        }

        boolean inSlice = this.withinLayers(pos.getX(), pos.getY(), pos.getZ());

        if (previous != null && inSlice)
        {
            --this.shown[previous.ordinal()];
            this.visible.get(previous).remove(pos);
        }

        if (current == null || ! inSlice)
        {
            return;
        }

        ++this.shown[current.ordinal()];

        if (! current.isEnabled())
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> list = this.visible.get(current);
        double range = LayerListConfigs.VERIFY_DRAW_RANGE.getIntegerValue();

        if (mc.player != null && list.size() < LayerListConfigs.VERIFY_MAX_MARKERS.getIntegerValue()
                && mc.player.blockPosition().distSqr(pos) <= range * range)
        {
            list.add(pos);
        }
    }

    public void onBlockChanged(BlockPos pos, BlockState newState)
    {
        if (! this.enabled)
        {
            return;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (schematicWorld == null)
        {
            return;
        }

        BlockPos immutable = pos.immutable();
        long key = immutable.asLong();
        byte stored = this.found.remove(key);
        Category previous = stored == NONE ? null : CATEGORIES[stored];
        Category current = null;

        if (this.contains(key))
        {
            current = categoryAt(schematicWorld.getBlockState(immutable), newState);

            if (current != null && (this.found.size() < MAX_FOUND || previous != null))
            {
                this.found.put(key, (byte) current.ordinal());
            }
        }

        if (previous != current)
        {
            this.patchVisible(immutable, previous, current);
        }
    }

    private boolean contains(long key)
    {
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);

        for (ScanBox box : this.volume)
        {
            if (x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY()
                    && z >= box.minZ() && z <= box.maxZ())
            {
                return true;
            }
        }

        return false;
    }

    private boolean refreshLayers()
    {
        boolean filter = false;
        Direction.Axis axis = this.layerAxis;
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;

        CountScope scope = LayerListApi.getCountScope();
        boolean ignoreLayers = scope != null && scope.isActive() && ! scope.applyLayerRange();

        if (! ignoreLayers)
        {
            LayerRange layerRange = DataManager.getRenderLayerRange();

            if (layerRange.getLayerMode() != LayerMode.ALL)
            {
                filter = true;
                axis = layerRange.getAxis();
                min = layerRange.getLayerMin();
                max = layerRange.getLayerMax();
            }
        }

        boolean axisChanged = axis != this.layerAxis;

        this.layerFilter = filter;
        this.layerAxis = axis;
        this.layerMin = min;
        this.layerMax = max;

        return axisChanged;
    }

    private boolean sliceChanged()
    {
        return this.sliceMin() != this.queuedSliceMin || this.sliceMax() != this.queuedSliceMax;
    }

    private int sliceMin()
    {
        return this.layerFilter ? this.layerMin : Integer.MIN_VALUE;
    }

    private int sliceMax()
    {
        return this.layerFilter ? this.layerMax : Integer.MAX_VALUE;
    }

    private boolean withinLayers(int x, int y, int z)
    {
        if (! this.layerFilter)
        {
            return true;
        }

        int value = switch (this.layerAxis)
        {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };

        return value >= this.layerMin && value <= this.layerMax;
    }

    private int axisOf(BlockPos pos)
    {
        return switch (this.layerAxis)
        {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private int axisMin(ScanBox box)
    {
        return switch (this.layerAxis)
        {
            case X -> box.minX();
            case Y -> box.minY();
            case Z -> box.minZ();
        };
    }

    private int axisMax(ScanBox box)
    {
        return switch (this.layerAxis)
        {
            case X -> box.maxX();
            case Y -> box.maxY();
            case Z -> box.maxZ();
        };
    }

    private long planeVolume(ScanBox box)
    {
        return switch (this.layerAxis)
        {
            case X -> (long) box.sizeY() * box.sizeZ();
            case Y -> (long) box.sizeX() * box.sizeZ();
            case Z -> (long) box.sizeX() * box.sizeY();
        };
    }

    private void setPlanePos(BlockPos.MutableBlockPos pos, ScanBox box, int layer, long index)
    {
        switch (this.layerAxis)
        {
            case X -> pos.set(layer,
                    box.minY() + (int) (index % box.sizeY()),
                    box.minZ() + (int) (index / box.sizeY()));
            case Y -> pos.set(box.minX() + (int) (index % box.sizeX()),
                    layer,
                    box.minZ() + (int) (index / box.sizeX()));
            case Z -> pos.set(box.minX() + (int) (index % box.sizeX()),
                    box.minY() + (int) (index / box.sizeX()),
                    layer);
        }
    }

    @Nullable
    private static Category categoryAt(BlockState schematic, BlockState client)
    {
        if (schematic.isAir())
        {
            return client.isAir() ? null : Category.EXTRA;
        }

        // Пустой горшок там, где нужен горшок с цветком, — недостроенная клетка: цветок сажается
        // в него кликом, ломать горшок не нужно
        if (client.isAir() || Potting.needsPlant(schematic, client))
        {
            return Category.MISSING;
        }

        if (client == schematic)
        {
            return null;
        }

        return client.getBlock() == schematic.getBlock() ? Category.WRONG_STATE : Category.WRONG_BLOCK;
    }

    /**
     * «Только блоки из инвентаря» (вкладка «Подсветка»): остаются только клетки, где нужен блок,
     * который у игрока есть, — как голограммы в этом режиме. «Пропущено», «не тот блок» и «не то
     * состояние» — по предмету блока из схемы; «лишних» в режиме нет вовсе: там никакой блок не
     * нужен, только сломать. Пустой горшок под цветок — по цветку.
     */
    private static boolean shownByInventory(Category category, WorldSchematic schematicWorld, Minecraft mc, BlockPos pos)
    {
        return category != Category.EXTRA && haveItemFor(schematicWorld, mc, pos);
    }

    private static boolean haveItemFor(WorldSchematic schematicWorld, Minecraft mc, BlockPos pos)
    {
        BlockState wanted = schematicWorld.getBlockState(pos);
        BlockState schematic = wanted;

        if (mc.level != null && Potting.needsPlant(wanted, mc.level.getBlockState(pos)))
        {
            wanted = Potting.plantState(wanted);
        }

        // Доводка инструментом (костёр, лоза, тыква) — рамку показываем, как и голограмму
        return InventoryGhosts.available(wanted) || (mc.level != null && com.aell.layerlist.ghost.HiddenGhosts.finishable(
                schematic, mc.level.getBlockState(pos), pos, mc.level, schematicWorld));
    }

    private static String label(Category category)
    {
        return category.label();
    }

    private static String currentSignature()
    {
        CountScope scope = LayerListApi.getCountScope();
        StringBuilder builder = new StringBuilder(
                scope != null && scope.isActive() ? "scope:" + scope.getSignature() : "-");

        for (SchematicPlacement placement : enabledPlacements())
        {
            builder.append('|').append(placement.getName()).append(placement.getOrigin())
                    .append(placement.getRotation()).append(placement.getMirror());
        }

        return builder.toString();
    }

    private static List<SchematicPlacement> enabledPlacements()
    {
        List<SchematicPlacement> result = new ArrayList<>();

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            if (placement.isEnabled())
            {
                result.add(placement);
            }
        }

        return result;
    }

    private static List<ScanBox> scanVolume()
    {
        CountScope scope = LayerListApi.getCountScope();
        boolean scopeActive = scope != null && scope.isActive();
        List<Box> scopeBoxes = scopeActive ? scope.getBoxes() : List.of();

        List<ScanBox> result = new ArrayList<>();

        for (SchematicPlacement placement : enabledPlacements())
        {
            for (Box sub : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                ScanBox region = toScanBox(sub);

                if (region == null)
                {
                    continue;
                }

                if (! scopeActive)
                {
                    result.add(region);
                    continue;
                }

                for (Box scopeBox : scopeBoxes)
                {
                    ScanBox clip = toScanBox(scopeBox);
                    ScanBox intersection = clip == null ? null : intersect(region, clip);

                    if (intersection != null)
                    {
                        result.add(intersection);
                    }
                }
            }
        }

        return result;
    }

    @Nullable
    private static ScanBox toScanBox(Box box)
    {
        if (box.getPos1() == null || box.getPos2() == null)
        {
            return null;
        }

        BlockPos min = PositionUtils.getMinCorner(box.getPos1(), box.getPos2());
        BlockPos max = PositionUtils.getMaxCorner(box.getPos1(), box.getPos2());

        return new ScanBox(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    @Nullable
    private static ScanBox intersect(ScanBox a, ScanBox b)
    {
        int x1 = Math.max(a.minX(), b.minX());
        int y1 = Math.max(a.minY(), b.minY());
        int z1 = Math.max(a.minZ(), b.minZ());
        int x2 = Math.min(a.maxX(), b.maxX());
        int y2 = Math.min(a.maxY(), b.maxY());
        int z2 = Math.min(a.maxZ(), b.maxZ());

        return x1 <= x2 && y1 <= y2 && z1 <= z2 ? new ScanBox(x1, y1, z1, x2, y2, z2) : null;
    }
}
