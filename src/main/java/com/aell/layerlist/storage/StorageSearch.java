package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

@Environment(EnvType.CLIENT)
public class StorageSearch
{
    private static final StorageSearch INSTANCE = new StorageSearch();

    private static final int[] COLORS = {
            0xFF00E5FF, 0xFFFFD740, 0xFF69F0AE, 0xFFFF80AB,
            0xFFB388FF, 0xFFFFAB40, 0xFF40C4FF, 0xFFEEFF41};

    private static final int REFRESH_INTERVAL_TICKS = 10;

    private static final float GREY_SATURATION = 0.15F;
    private static final float HUE_STEP = 0.017F;
    private static final float MAX_HUE_SHIFT = 0.05F;
    private static final float MIN_HUE_DELTA = 0.04F;
    private static final float MIN_BRIGHTNESS_DELTA = 0.18F;
    private static final float[] BRIGHTNESS_STEPS = { 0.98F, 0.75F, 0.55F, 0.40F };

    public static int paletteColor(int index)
    {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    private final LinkedHashMap<String, Integer> selected = new LinkedHashMap<>();
    private final HashMap<Integer, String> labels = new HashMap<>();

    private final HashMap<BlockPos, List<Integer>> matches = new HashMap<>();
    private boolean active;
    private int ticks;
    private boolean refreshRequested;
    private boolean colorsFromBlocks = LayerListConfigs.COLORS_FROM_BLOCKS.getBooleanValue();

    public static StorageSearch getInstance()
    {
        return INSTANCE;
    }

    /**
     * True while nothing is marked right now. The search itself can still be armed —
     * a page whose blocks are all collected marks nothing but keeps following pages.
     */
    public boolean isEmpty()
    {
        return this.selected.isEmpty();
    }

    /** True while the search is armed, whether or not the current page has anything left to mark. */
    public boolean isActive()
    {
        return this.active;
    }

    public void clear()
    {
        this.active = false;
        this.selected.clear();
        this.labels.clear();
        this.matches.clear();
    }

    public Integer colorFor(ItemStack stack)
    {
        return this.selected.get(StorageIndex.itemId(stack));
    }

    public record Marked(String name, int color) {}

    public java.util.List<Marked> markedItems()
    {
        java.util.List<Marked> result = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : this.selected.entrySet())
        {
            String name = this.labels.getOrDefault(entry.getValue(), "");

            if (! name.isEmpty())
            {
                result.add(new Marked(name, entry.getValue()));
            }
        }

        return result;
    }

    public String labelFor(int color)
    {
        return this.labels.getOrDefault(color, "");
    }

    public Map<BlockPos, List<Integer>> getMatches()
    {
        return this.matches;
    }

    public void requestRefresh()
    {
        this.refreshRequested = true;
    }

    /**
     * «Подсветить все сундуки»: искать сразу все {@code stacks} ({@link AllChestsSearch}) — как поиск страницы, у
     * каждого предмета свой цвет и подпись над сундуком (18.10.2026, раньше — один жёлтый цвет на всё). Страница панели
     * и клавиша поиска страницы, пока опция включена, ничего не меняют.
     */
    public void searchAll(List<ItemStack> stacks)
    {
        LinkedHashMap<String, Integer> next = new LinkedHashMap<>();
        HashMap<Integer, String> nextLabels = new HashMap<>();

        for (ItemStack stack : stacks)
        {
            String itemId = StorageIndex.itemId(stack);

            if (next.containsKey(itemId))
            {
                continue;
            }

            int color = colorFor(stack, next.values());

            // Подпись ищется по цвету — у двух предметов цвет не должен совпасть даже случайно
            while (nextLabels.containsKey(color))
            {
                color = (color & 0xFF000000) | ((color + 1) & 0x00FFFFFF);
            }

            next.put(itemId, color);
            nextLabels.put(color, stack.getHoverName().getString());
        }

        this.active = true;
        this.selected.clear();
        this.selected.putAll(next);
        this.labels.clear();
        this.labels.putAll(nextLabels);
        this.refreshNow();
    }

    /** Выключили «все сундуки» — дальше искать по странице панели, как до включения. */
    public void backToPage(List<MaterialListEntry> pageEntries)
    {
        this.clear();

        if (! pageEntries.isEmpty())
        {
            this.active = true;
            this.syncToPage(pageEntries);
        }
    }

    public int togglePage(List<MaterialListEntry> pageEntries)
    {
        if (AllChestsSearch.enabled())
        {
            return this.selected.size();
        }

        if (this.active && this.matchesPage(pageEntries))
        {
            this.clear();
            return 0;
        }

        this.active = true;

        return this.syncToPage(pageEntries);
    }

    public static int countSearchable(List<MaterialListEntry> pageEntries)
    {
        return searchableItemIds(pageEntries).size();
    }

    private static LinkedHashMap<String, MaterialListEntry> searchableItemIds(List<MaterialListEntry> pageEntries)
    {
        boolean skipOwned = LayerListConfigs.SKIP_ITEMS_IN_INVENTORY.getBooleanValue();
        LinkedHashMap<String, MaterialListEntry> result = new LinkedHashMap<>();

        for (MaterialListEntry entry : pageEntries)
        {
            if (skipOwned && entry.getCountAvailable() >= entry.getCountMissing())
            {
                continue;
            }

            result.putIfAbsent(StorageIndex.itemId(entry.getStack()), entry);
        }

        return result;
    }

    public int followPage(List<MaterialListEntry> pageEntries)
    {
        if (AllChestsSearch.enabled())
        {
            return this.selected.size();
        }

        return this.active ? this.syncToPage(pageEntries) : 0;
    }

    private boolean matchesPage(List<MaterialListEntry> pageEntries)
    {
        return searchableItemIds(pageEntries).keySet().equals(this.selected.keySet());
    }

    private int syncToPage(List<MaterialListEntry> pageEntries)
    {
        LinkedHashMap<String, Integer> nextSelected = new LinkedHashMap<>();
        HashMap<Integer, String> nextLabels = new HashMap<>();

        for (Map.Entry<String, MaterialListEntry> pageEntry : searchableItemIds(pageEntries).entrySet())
        {
            String itemId = pageEntry.getKey();
            ItemStack stack = pageEntry.getValue().getStack();

            Integer color = this.selected.get(itemId);

            if (color == null)
            {
                color = colorFor(stack, nextSelected.values());
            }
            else if (nextSelected.containsValue(color))
            {
                color = distinct(color, nextSelected.values());
            }

            nextSelected.put(itemId, color);
            nextLabels.put(color, stack.getHoverName().getString());
        }

        boolean changed = ! nextSelected.equals(this.selected);

        this.selected.clear();
        this.selected.putAll(nextSelected);
        this.labels.clear();
        this.labels.putAll(nextLabels);

        if (changed)
        {
            this.refreshNow();
        }

        return this.selected.size();
    }

    private void recolor()
    {
        if (this.selected.isEmpty())
        {
            return;
        }

        if (AllChestsSearch.enabled())
        {
            AllChestsSearch.recount();
            return;
        }

        HashMap<String, ItemStack> stacks = new HashMap<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            stacks.putIfAbsent(StorageIndex.itemId(entry.getStack()), entry.getStack());
        }

        LinkedHashMap<String, Integer> nextSelected = new LinkedHashMap<>();
        HashMap<Integer, String> nextLabels = new HashMap<>();

        for (Map.Entry<String, Integer> item : this.selected.entrySet())
        {
            ItemStack stack = stacks.get(item.getKey());
            int color = stack != null
                    ? colorFor(stack, nextSelected.values())
                    : nextColor(nextSelected.values());

            nextSelected.put(item.getKey(), color);
            nextLabels.put(color, this.labels.getOrDefault(item.getValue(), ""));
        }

        this.selected.clear();
        this.selected.putAll(nextSelected);
        this.labels.clear();
        this.labels.putAll(nextLabels);

        this.refreshNow();
    }

    private void refreshNow()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level != null && mc.player != null)
        {
            this.refresh(mc);
        }
        else
        {
            this.requestRefresh();
        }
    }

    public void onClientTick(Minecraft mc)
    {
        if (mc.level == null || mc.player == null)
        {
            if (! this.matches.isEmpty()) { this.matches.clear(); }
            return;
        }

        boolean fromBlocks = LayerListConfigs.COLORS_FROM_BLOCKS.getBooleanValue();

        if (fromBlocks != this.colorsFromBlocks)
        {
            this.colorsFromBlocks = fromBlocks;
            this.recolor();
        }

        if (++this.ticks % REFRESH_INTERVAL_TICKS == 0 || this.refreshRequested)
        {
            this.refreshRequested = false;
            this.refresh(mc);
        }
    }

    private void refresh(Minecraft mc)
    {
        this.matches.clear();

        if (this.selected.isEmpty())
        {
            return;
        }

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double radius = LayerListConfigs.SEARCH_RADIUS.getIntegerValue();
        double radiusSq = radius * radius;

        String scope = StorageIndex.scope(mc);
        // «Свой склад» включён — отмечаем только выбранные хранилища, как и в счёте «В сундуках» (29.10.2026: чужие
        // сундуки, открытые когда-то раньше, подсвечивались и с включённой опцией)
        Set<BlockPos> chosen = LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue()
                ? ChosenStorages.getInstance().in(scope) : null;

        for (StorageIndex.Rec rec : StorageIndex.getInstance().recordsInScope(scope))
        {
            BlockPos pos = rec.pos();

            if (! withinRadius(playerX, playerZ, pos, radiusSq)
                    || ! mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || ! StorageIndex.isRecordable(mc, pos))
            {
                continue;
            }

            BlockPos canonical = StorageIndex.canonicalPos(mc, pos);

            if (! canonical.equals(pos) || (chosen != null && ! chosen.contains(canonical)))
            {
                continue;
            }

            for (Map.Entry<String, Integer> item : rec.counts.entrySet())
            {
                Integer color = this.selected.get(item.getKey());

                if (color != null)
                {
                    addMatch(this.matches, canonical, color);
                }
            }
        }

        this.markEnderChests(mc, playerX, playerZ, radius, radiusSq);

        if (LayerListConfigs.SEARCH_ITEM_FRAMES.getBooleanValue())
        {
            AABB area = new AABB(playerX - radius, mc.level.getMinY(), playerZ - radius,
                    playerX + radius, mc.level.getMaxY(), playerZ + radius);

            for (ItemFrame frame : mc.level.getEntitiesOfClass(ItemFrame.class, area))
            {
                if (frame.getItem().isEmpty())
                {
                    continue;
                }

                String frameItemId = StorageIndex.itemId(frame.getItem());
                Integer color = this.selected.get(frameItemId);

                if (color == null)
                {
                    continue;
                }

                BlockPos support = frame.getPos().relative(frame.getDirection().getOpposite());

                if (withinRadius(playerX, playerZ, support, radiusSq) && StorageIndex.isRecordable(mc, support))
                {
                    BlockPos canonical = StorageIndex.canonicalPos(mc, support);
                    StorageIndex.Rec rec = StorageIndex.getInstance().get(scope, canonical);

                    if (chosen != null && ! chosen.contains(canonical))
                    {
                        continue;
                    }

                    if (rec == null || rec.counts.containsKey(frameItemId))
                    {
                        addMatch(this.matches, canonical, color);
                    }
                }
            }
        }
    }

    /**
     * Эндер-сундук (29.10.2026, «там же лежат нужные мне еловые люки»): у снимка нет места в мире — содержимое одно на
     * все эндер-сундуки игрока, — поэтому светим каждый блок эндер-сундука рядом, если в снимке есть искомое. Только
     * пока он в счёте («Эндер-сундук в счёт»); «Свой склад» его не отсекает — эндер-сундук и так только ваш.
     */
    private void markEnderChests(Minecraft mc, double playerX, double playerZ, double radius, double radiusSq)
    {
        if (! LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue())
        {
            return;
        }

        String enderScope = StorageIndex.enderScope(mc);
        StorageIndex.Rec ender = enderScope.isEmpty() ? null
                : StorageIndex.getInstance().get(enderScope, StorageIndex.ENDER_POS);

        if (ender == null || ender.counts == null)
        {
            return;
        }

        List<Integer> colors = new ArrayList<>();

        for (Map.Entry<String, Integer> item : ender.counts.entrySet())
        {
            Integer color = this.selected.get(item.getKey());

            if (color != null && item.getValue() != null && item.getValue() > 0 && ! colors.contains(color))
            {
                colors.add(color);
            }
        }

        if (colors.isEmpty())
        {
            return;
        }

        // Эндер-сундук — блок с блок-сущностью, она есть у клиента в каждом прогруженном чанке
        int minChunkX = Mth.floor(playerX - radius) >> 4;
        int maxChunkX = Mth.floor(playerX + radius) >> 4;
        int minChunkZ = Mth.floor(playerZ - radius) >> 4;
        int maxChunkZ = Mth.floor(playerZ + radius) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ)
            {
                if (! mc.level.hasChunk(chunkX, chunkZ))
                {
                    continue;
                }

                for (BlockEntity blockEntity : mc.level.getChunk(chunkX, chunkZ).getBlockEntities().values())
                {
                    BlockPos pos = blockEntity.getBlockPos();

                    if (blockEntity instanceof EnderChestBlockEntity && withinRadius(playerX, playerZ, pos, radiusSq)
                            && StorageIndex.isEnderChest(mc, pos))
                    {
                        for (int color : colors)
                        {
                            addMatch(this.matches, pos.immutable(), color);
                        }
                    }
                }
            }
        }
    }

    private static void addMatch(Map<BlockPos, List<Integer>> map, BlockPos pos, int color)
    {
        List<Integer> colors = map.computeIfAbsent(pos, ignored -> new ArrayList<>());

        if (! colors.contains(color))
        {
            colors.add(color);
        }
    }

    private static boolean withinRadius(double playerX, double playerZ, BlockPos pos, double radiusSq)
    {
        double dx = pos.getX() + 0.5 - playerX;
        double dz = pos.getZ() + 0.5 - playerZ;
        return dx * dx + dz * dz <= radiusSq;
    }

    private static int colorFor(ItemStack stack, Collection<Integer> used)
    {
        if (! LayerListConfigs.COLORS_FROM_BLOCKS.getBooleanValue())
        {
            return nextColor(used);
        }

        int fromIcon = ItemColors.forStack(stack);

        return fromIcon != 0 ? distinct(fromIcon, used) : nextColor(used);
    }

    private static int distinct(int color, Collection<Integer> used)
    {
        if (! tooClose(color, used))
        {
            return color;
        }

        if (ItemColors.saturation(color) >= GREY_SATURATION)
        {
            for (float shift = HUE_STEP; shift <= MAX_HUE_SHIFT; shift += HUE_STEP)
            {
                for (int sign = 1; sign >= -1; sign -= 2)
                {
                    int shifted = ItemColors.shiftHue(color, shift * sign);

                    if (! tooClose(shifted, used))
                    {
                        return shifted;
                    }
                }
            }
        }

        for (float brightness : BRIGHTNESS_STEPS)
        {
            int adjusted = ItemColors.withBrightness(color, brightness);

            if (! tooClose(adjusted, used))
            {
                return adjusted;
            }
        }

        return nextColor(used);
    }

    private static boolean tooClose(int color, Collection<Integer> used)
    {
        float hue = ItemColors.hue(color);
        float brightness = ItemColors.brightness(color);
        boolean grey = ItemColors.saturation(color) < GREY_SATURATION;

        for (int other : used)
        {
            boolean otherGrey = ItemColors.saturation(other) < GREY_SATURATION;

            if (grey != otherGrey)
            {
                continue;
            }

            boolean sameBrightness =
                    Math.abs(brightness - ItemColors.brightness(other)) < MIN_BRIGHTNESS_DELTA;

            if (grey)
            {
                if (sameBrightness)
                {
                    return true;
                }

                continue;
            }

            float delta = Math.abs(hue - ItemColors.hue(other));

            if (Math.min(delta, 1.0F - delta) < MIN_HUE_DELTA && sameBrightness)
            {
                return true;
            }
        }

        return false;
    }

    private static int nextColor(Collection<Integer> used)
    {
        for (int color : COLORS)
        {
            if (! used.contains(color))
            {
                return color;
            }
        }

        float hue = used.size() * 0.618034f % 1.0f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.7f, 1.0f) & 0xFFFFFF);
    }
}
