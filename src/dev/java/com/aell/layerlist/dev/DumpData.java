package com.aell.layerlist.dev;

import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.Controllable;
import com.aell.layerlist.easyplace.ItemData;
import com.aell.layerlist.easyplace.SchematicTarget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Дамп D — тождество предмета по данным блока. Отвечает на один вопрос: что мод считает
 * требуемым предметом для клеток, у которых предмет различается не состоянием, а
 * компонентами (узор баннера, черепки горшка), и считает ли он такую клетку работой.
 *
 * <p>В отличие от дампов A—C синтетики здесь нет: берётся НАСТОЯЩАЯ схема пользователя
 * (переменная {@code LL_DATA_SCHEMATIC}, по умолчанию «магазин на сервере.litematic» из
 * {@code run/schematics}), кладётся размещением в пустой мир, и каждая клетка баннера,
 * горшка и полной плиты проходит ровно тот путь, каким её ведёт Easy Place:
 *
 * <ul>
 *   <li>есть ли в мире схемы сущность блока и лежит ли в ней узор;
 *   <li>что отдаёт {@code ItemData.required} — предмет с узором или пустышку;
 *   <li>что отдаёт кеш Litematica ВНЕ обработчика (он ключуется по BlockState, поэтому
 *       у двух баннеров с разными узорами предмет получается один);
 *   <li>что отдаёт он же ВНУТРИ обработчика, где работает наша подмена;
 *   <li>считает ли {@code SchematicTarget.actionable} клетку работой.
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DumpData implements CovJob
{
    /** Куда кладём размещение: внутри forceload-области площадки, но в стороне от станций. */
    private static final BlockPos ORIGIN = new BlockPos(0, 72, 16);

    private static final int RANGE_XZ = 24;
    private static final int RANGE_UP = 28;
    private static final int RANGE_DOWN = 8;

    /** Мир схемы собирается асинхронно; step() зовут пачками внутри тика, поэтому ждём по часам. */
    private static final long SETTLE_MS = 8000L;

    private static final String HEADER = String.join(",", List.of(
            "pos", "block", "family", "be", "be_data", "required", "patch",
            "cache_outside_patch", "cache_inside_patch", "crafted_patch",
            "same_as_crafted", "actionable", "slot",
            "half_in_world", "half_only_differs_before", "half_only_differs", "half_actionable"));

    private CovOut out;
    private long deadline;
    private boolean placed;
    private List<BlockPos> cells;
    private int index;

    @Override
    public String name()
    {
        return "d";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            this.out = CovOut.open("data.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("не открыть data.csv: " + e);

            return false;
        }

        return this.place(mc);
    }

    private boolean place(Minecraft mc)
    {
        String name = System.getenv("LL_DATA_SCHEMATIC");

        if (name == null || name.isBlank())
        {
            name = "магазин на сервере.litematic";
        }

        Path path = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(name);

        if (! Files.isRegularFile(path))
        {
            CovOut.log("схемы нет: " + path + " — положи файл в run/schematics"
                    + " или укажи LL_DATA_SCHEMATIC");

            return false;
        }

        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(path);

        if (schematic == null)
        {
            CovOut.log("схема не загрузилась: " + path);

            return false;
        }

        SchematicPlacement placement = SchematicPlacement.createFor(schematic, ORIGIN,
                "dev-data", true, true);

        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);

        CovOut.log("схема «" + name + "» положена в " + ORIGIN + ", жду сборки мира схемы");
        this.placed = true;
        this.deadline = System.currentTimeMillis() + SETTLE_MS;

        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (! this.placed)
        {
            return true;
        }

        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        if (world == null || mc.player == null)
        {
            if (System.currentTimeMillis() < this.deadline)
            {
                return false;
            }

            CovOut.log("мира схемы нет — дамп D пропущен");

            return true;
        }

        if (this.cells == null)
        {
            List<BlockPos> found = this.collect(world);

            // Чанки мира схемы строятся в фоне: пустой результат до срока — это «ещё не
            // собрался», а не «в схеме ничего нет».
            if (found.isEmpty() && System.currentTimeMillis() < this.deadline)
            {
                return false;
            }

            this.cells = found;
            CovOut.log("клеток с данными блока: " + this.cells.size());
        }

        int budget = 0;

        while (this.index < this.cells.size() && budget < 64)
        {
            this.row(mc, world, this.cells.get(this.index));
            ++this.index;
            ++budget;
        }

        return this.index >= this.cells.size();
    }

    /** Клетки, ради которых дамп и затевался: баннеры, горшки и полные плиты. */
    private List<BlockPos> collect(WorldSchematic world)
    {
        List<BlockPos> found = new ArrayList<>();

        for (int y = ORIGIN.getY() - RANGE_DOWN; y <= ORIGIN.getY() + RANGE_UP; ++y)
        {
            for (int x = ORIGIN.getX() - RANGE_XZ; x <= ORIGIN.getX() + RANGE_XZ; ++x)
            {
                for (int z = ORIGIN.getZ() - RANGE_XZ; z <= ORIGIN.getZ() + RANGE_XZ; ++z)
                {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (family(world.getBlockState(pos)) != null)
                    {
                        found.add(pos);
                    }
                }
            }
        }

        return found;
    }

    private static String family(BlockState state)
    {
        if (state.isAir())
        {
            return null;
        }

        if (state.getBlock() instanceof AbstractBannerBlock)
        {
            return "banner";
        }

        if (state.getBlock() instanceof DecoratedPotBlock)
        {
            return "pot";
        }

        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
        {
            return "slab_double";
        }

        return null;
    }

    private void row(Minecraft mc, WorldSchematic world, BlockPos pos)
    {
        BlockState state = world.getBlockState(pos);
        String key = pos.getX() + " " + pos.getY() + " " + pos.getZ();

        if (this.out.has(key))
        {
            return;
        }

        BlockEntity be = null;

        try
        {
            be = world.getBlockEntity(pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            // мир схемы может не отдать сущность — это и есть один из искомых ответов
        }

        ItemStack required = ItemStack.EMPTY;

        try
        {
            required = ItemData.required(world, pos, state);
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log("required упал на " + key + ": " + e);
        }

        ItemStack crafted = crafted(state, be);
        String[] half = halfProbe(mc, pos, state);

        this.out.row(List.of(
                key,
                name(state),
                String.valueOf(family(state)),
                be == null ? "нет" : be.getClass().getSimpleName(),
                beData(be),
                required.isEmpty() ? "пусто" : required.getItem().toString(),
                patch(required),
                patch(cache(state, world, pos, false)),
                patch(cache(state, world, pos, true)),
                patch(crafted),
                String.valueOf(ItemStack.isSameItemSameComponents(required, crafted)),
                String.valueOf(actionable(pos)),
                String.valueOf(slot(mc, required)),
                half[0], half[1], half[2], half[3]));
    }



    /**
     * Проба ради полублоков: кладём в КЛИЕНТСКИЙ мир половину плиты там, где схема просит
     * полную, и спрашиваем мод, есть ли тут работа.
     *
     * <p>Так выглядит вторая половина плиты во время стройки. До правки клетка отвечала
     * «работы нет»: {@code placementOnlyDiffers} видел разошедшийся {@code type}, клетка
     * занята — значит «кликом не исправить». Прицел такую клетку бросал
     * (MixinLitematicaRayTraceUtils), и до {@code PlacementGuard.decideDoubleSlab},
     * который умеет добить вторую половину, дело не доходило никогда.
     */
    private static String[] halfProbe(Minecraft mc, BlockPos pos, BlockState wanted)
    {
        if (mc.level == null || ! "slab_double".equals(family(wanted)))
        {
            return new String[] { "", "", "", "" };
        }

        BlockState half = wanted.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockState before = mc.level.getBlockState(pos);

        try
        {
            mc.level.setBlock(pos, half, 0);

            return new String[] {
                    String.valueOf(half),
                    String.valueOf(onlyDiffersBefore(wanted, half)),
                    String.valueOf(Controllable.placementOnlyDiffers(wanted, half)),
                    String.valueOf(actionable(pos)) };
        }
        finally
        {
            mc.level.setBlock(pos, before, 0);
        }
    }


    /**
     * Предикат «кликом не исправить» в том виде, в каком он был ДО правки: без исключения
     * для плиты, которую установка доводит на месте. Нужен, чтобы дамп показывал сдвиг,
     * а не только итог.
     */
    private static boolean onlyDiffersBefore(BlockState wanted, BlockState world)
    {
        if (wanted.isAir() || ! world.is(wanted.getBlock()))
        {
            return false;
        }

        for (net.minecraft.world.level.block.state.properties.Property<?> property
                : Controllable.scored(wanted))
        {
            if (! sameValue(wanted, world, property))
            {
                return true;
            }
        }

        return false;
    }

    private static <T extends Comparable<T>> boolean sameValue(BlockState a, BlockState b,
            net.minecraft.world.level.block.state.properties.Property<T> property)
    {
        if (! a.hasProperty(property) || ! b.hasProperty(property))
        {
            return true;
        }

        return a.getValue(property).equals(b.getValue(property));
    }

    /**
     * Предмет, каким он лежит у игрока в инвентаре: скрафченный баннер или горшок несёт
     * ровно один компонент — узор или черепки, — и больше ничего. Именно с таким предметом
     * Litematica сверяет требуемый ({@code Inventory.findSlotMatchingItem} сравнивает
     * НАКЛАДКУ компонентов целиком), поэтому лишний компонент в требуемом предмете делает
     * его ненаходимым в выживании.
     */
    private static ItemStack crafted(BlockState state, BlockEntity be)
    {
        ItemStack stack = new ItemStack(state.getBlock());

        if (be instanceof BannerBlockEntity banner
                && ! banner.getPatterns().equals(BannerPatternLayers.EMPTY))
        {
            stack.set(DataComponents.BANNER_PATTERNS, banner.getPatterns());
        }

        if (be instanceof DecoratedPotBlockEntity pot
                && ! pot.getDecorations().equals(PotDecorations.EMPTY))
        {
            stack.set(DataComponents.POT_DECORATIONS, pot.getDecorations());
        }

        return stack;
    }

    /** Накладка компонентов — то, что сравнивает поиск предмета в инвентаре. */
    private static String patch(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return "пусто";
        }

        String text = String.valueOf(stack.getComponentsPatch());

        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static String name(BlockState state)
    {
        return String.valueOf(state);
    }

    private static String beData(BlockEntity be)
    {
        if (be instanceof BannerBlockEntity banner)
        {
            return "слоёв " + banner.getPatterns().layers().size();
        }

        if (be instanceof DecoratedPotBlockEntity pot)
        {
            return "черепки " + pot.getDecorations();
        }

        return be == null ? "" : "—";
    }

    private static String data(ItemStack stack)
    {
        if (stack.isEmpty())
        {
            return "";
        }

        String text = String.valueOf(stack.getComponents());

        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    /** Требуемый предмет по кешу Litematica: снаружи обработчика и внутри него. */
    private static ItemStack cache(BlockState state, WorldSchematic world, BlockPos pos,
            boolean inHandler)
    {
        if (inHandler)
        {
            AutoPlaceState.enter();
        }

        try
        {
            return MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return ItemStack.EMPTY;
        }
        finally
        {
            if (inHandler)
            {
                AutoPlaceState.exit();
            }
        }
    }

    private static String stackText(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return "пусто";
        }

        String data = data(stack);

        return stack.getItem() + (data.isEmpty() ? "" : " " + data);
    }

    private static boolean actionable(BlockPos pos)
    {
        try
        {
            return SchematicTarget.actionable(pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Нашёлся бы такой предмет в инвентаре тем же поиском, каким его ищет Litematica. */
    private static int slot(Minecraft mc, ItemStack required)
    {
        if (mc.player == null || required.isEmpty())
        {
            return -2;
        }

        try
        {
            Inventory inventory = mc.player.getInventory();

            return inventory.findSlotMatchingItem(required);
        }
        catch (RuntimeException | LinkageError e)
        {
            return -3;
        }
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            CovOut.log("дамп D готов: " + this.out.path() + ", строк " + this.out.doneCount());
            this.out.close();
        }
    }
}
