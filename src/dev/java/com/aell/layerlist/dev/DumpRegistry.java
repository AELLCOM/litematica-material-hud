package com.aell.layerlist.dev;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Дамп A: инвентаризация реестра блоков. Ничего не ставит и ничего не решает — только
 * описывает, что вообще есть в 26.2 и что из этого игрок может держать в руке.
 *
 * <p>Добываемость определяется по загруженному датапаку: предмет считается добываемым,
 * если его выдаёт хоть один рецепт или хоть одна блочная лут-таблица (роллы идут набором
 * инструментов, включая шёлковое касание). Предметы из сундуков, торговли и мобов сюда не
 * попадают — такие блоки помечаются UNKNOWN, а не догадкой.
 */
@Environment(EnvType.CLIENT)
public final class DumpRegistry implements CovJob
{
    private static final String HEADER = String.join(",",
            "id", "class", "states", "item", "item_class", "pick_item", "obtainable", "obtain_src",
            "no_item", "derived_states", "thin_ghost", "thin_min", "needs_support", "support",
            "block_entity", "replaceable", "waterloggable", "properties");

    private static final int LOOT_CHUNK = 24;

    private CovOut out;
    private List<Block> blocks = new ArrayList<>();
    private final Set<net.minecraft.world.item.Item> fromRecipes = new HashSet<>();
    private final Set<net.minecraft.world.item.Item> fromLoot = new HashSet<>();
    private final AtomicBoolean pending = new AtomicBoolean();
    private int lootIndex;
    private int index;
    private boolean lootDone;

    @Override
    public String name()
    {
        return "A/registry.csv";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            this.out = CovOut.open("registry.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("не открыть registry.csv: " + e);
            return false;
        }

        this.blocks = new ArrayList<>(BuiltInRegistries.BLOCK.stream().toList());

        CovOut.log("блоков в реестре: " + this.blocks.size());

        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            CovOut.log("нет встроенного сервера — рецепты и лут не собрать");
            return false;
        }

        this.collectRecipes(server);

        return true;
    }

    private void collectRecipes(MinecraftServer server)
    {
        AtomicBoolean ready = new AtomicBoolean();

        server.execute(() ->
        {
            try
            {
                ServerLevel level = server.overworld();
                var context = SlotDisplayContext.fromLevel(level);

                for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes())
                {
                    for (RecipeDisplay display : holder.value().display())
                    {
                        for (ItemStack stack : display.result().resolveForStacks(context))
                        {
                            if (! stack.isEmpty())
                            {
                                this.fromRecipes.add(stack.getItem());
                            }
                        }
                    }
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                CovOut.log("рецепты не собрались: " + e);
            }
            finally
            {
                ready.set(true);
            }
        });

        // рецепты нужны до первой строки, поэтому ждём их здесь — это единственное место,
        // где клиентский поток стоит и ждёт серверный
        for (int i = 0; i < 200 && ! ready.get(); ++i)
        {
            try
            {
                Thread.sleep(50L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        CovOut.log("предметов из рецептов: " + this.fromRecipes.size());
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (! this.lootDone)
        {
            return this.stepLoot(mc);
        }

        return this.stepRows(mc);
    }

    /** Лут крутится на серверном потоке порциями: там его случайность и его регистры. */
    private boolean stepLoot(Minecraft mc)
    {
        if (this.pending.get())
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            this.lootDone = true;
            return false;
        }

        if (this.lootIndex >= this.blocks.size())
        {
            this.lootDone = true;
            CovOut.log("предметов из блочного лута: " + this.fromLoot.size());
            return false;
        }

        int from = this.lootIndex;
        int to = Math.min(this.blocks.size(), from + LOOT_CHUNK);

        this.lootIndex = to;
        this.pending.set(true);

        server.execute(() ->
        {
            try
            {
                ServerLevel level = server.overworld();
                List<ItemStack> tools = tools(level);
                BlockPos pos = CovArena.PLACE;

                for (int i = from; i < to; ++i)
                {
                    for (BlockState state : DumpSolver.candidates(this.blocks.get(i)))
                    {
                        this.roll(level, tools, pos, state);
                    }
                }
            }
            finally
            {
                this.pending.set(false);
            }
        });

        return false;
    }

    private void roll(ServerLevel level, List<ItemStack> tools, BlockPos pos, BlockState state)
    {
        for (ItemStack tool : tools)
        {
            try
            {
                for (ItemStack drop : Block.getDrops(state, level, pos, null, null, tool))
                {
                    if (! drop.isEmpty())
                    {
                        this.fromLoot.add(drop.getItem());
                    }
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                // блок требует сущности блока или чужой лут-таблицы — пропускаем
            }
        }
    }

    private static List<ItemStack> tools(ServerLevel level)
    {
        List<ItemStack> tools = new ArrayList<>();
        ItemStack silk = new ItemStack(Items.NETHERITE_PICKAXE);

        try
        {
            silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SILK_TOUCH), 1);
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log("шёлковое касание не наложилось: " + e);
        }

        tools.add(silk);
        tools.add(new ItemStack(Items.NETHERITE_PICKAXE));
        tools.add(new ItemStack(Items.NETHERITE_AXE));
        tools.add(new ItemStack(Items.NETHERITE_SHOVEL));
        tools.add(new ItemStack(Items.NETHERITE_HOE));
        tools.add(new ItemStack(Items.SHEARS));
        tools.add(ItemStack.EMPTY);

        return tools;
    }

    private boolean stepRows(Minecraft mc)
    {
        if (this.index >= this.blocks.size())
        {
            return true;
        }

        Block block = this.blocks.get(this.index++);
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String key = id == null ? block.toString() : id.toString();

        if (this.out.has(key))
        {
            return false;
        }

        if ((this.index & 127) == 0)
        {
            CovOut.log("A: " + this.index + "/" + this.blocks.size());
        }

        this.out.row(this.describe(mc, block, key));

        return false;
    }

    private List<String> describe(Minecraft mc, Block block, String key)
    {
        BlockState def = block.defaultBlockState();
        List<BlockState> states = block.getStateDefinition().getPossibleStates();
        net.minecraft.world.item.Item item = block.asItem();
        boolean noItem = item == Items.AIR;

        String pick = "";

        try
        {
            ItemStack stack = def.getCloneItemStack(mc.level, CovArena.target(CovArena.Station.FLOOR),
                    false);

            pick = stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
        catch (RuntimeException | LinkageError e)
        {
            pick = "ERROR";
        }

        String obtainable;
        String source;

        if (noItem)
        {
            obtainable = "NO_ITEM";
            source = "";
        }
        else if (this.fromRecipes.contains(item) && this.fromLoot.contains(item))
        {
            obtainable = "YES";
            source = "recipe+loot";
        }
        else if (this.fromRecipes.contains(item))
        {
            obtainable = "YES";
            source = "recipe";
        }
        else if (this.fromLoot.contains(item))
        {
            obtainable = "YES";
            source = "loot";
        }
        else
        {
            obtainable = "UNKNOWN";
            source = "";
        }

        int derived = 0;

        for (BlockState state : states)
        {
            if (derivedState(state))
            {
                ++derived;
            }
        }

        double thin = thickness(mc, def);
        String support = support(mc, def);

        return List.of(key,
                block.getClass().getSimpleName(),
                Integer.toString(states.size()),
                noItem ? "" : BuiltInRegistries.ITEM.getKey(item).toString(),
                noItem ? "" : item.getClass().getSimpleName(),
                pick,
                obtainable,
                source,
                Boolean.toString(noItem),
                Integer.toString(derived),
                Boolean.toString(thin >= 0.0D && thin < 0.2D),
                thin < 0.0D ? "" : String.format(java.util.Locale.ROOT, "%.3f", thin),
                Boolean.toString(! support.contains("free")),
                support,
                Boolean.toString(block instanceof EntityBlock),
                Boolean.toString(def.canBeReplaced()),
                Boolean.toString(def.hasProperty(BlockStateProperties.WATERLOGGED)),
                properties(block));
    }

    static boolean derivedState(BlockState state)
    {
        if (state.getBlock() instanceof GrowingPlantBodyBlock)
        {
            return true;
        }

        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
        {
            return true;
        }

        return state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
    }

    /** Наименьшая толщина голограммы по осям: кувшинка, ковёр, плита давления, лишайник. */
    private static double thickness(Minecraft mc, BlockState state)
    {
        if (mc.level == null)
        {
            return -1.0D;
        }

        BlockPos pos = CovArena.target(CovArena.Station.FLOOR);

        try
        {
            VoxelShape shape = state.getShape(mc.level, pos);

            if (shape.isEmpty())
            {
                shape = state.getVisualShape(mc.level, pos, net.minecraft.world.phys.shapes.CollisionContext.empty());
            }

            if (shape.isEmpty())
            {
                return 0.0D;
            }

            net.minecraft.world.phys.AABB box = shape.bounds();

            return Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize()));
        }
        catch (RuntimeException | LinkageError e)
        {
            return -1.0D;
        }
    }

    /** На каких станциях состояние выживает: это и есть требование к опоре. */
    private static String support(Minecraft mc, BlockState state)
    {
        if (mc.level == null)
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (survives(mc, state, CovArena.FREE))
        {
            sb.append("free");
        }

        for (CovArena.Station station : CovArena.Station.values())
        {
            if (survives(mc, state, CovArena.target(station)))
            {
                if (sb.length() > 0)
                {
                    sb.append('|');
                }

                sb.append(station.name().toLowerCase(java.util.Locale.ROOT));
            }
        }

        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static boolean survives(Minecraft mc, BlockState state, BlockPos pos)
    {
        try
        {
            return state.canSurvive(mc.level, pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static String properties(Block block)
    {
        StringBuilder sb = new StringBuilder();

        for (Property<?> property : block.getStateDefinition().getProperties())
        {
            if (sb.length() > 0)
            {
                sb.append(';');
            }

            sb.append(property.getName()).append(':')
                    .append(property.getValueClass().getSimpleName()).append('(');

            boolean first = true;

            for (Object value : property.getPossibleValues())
            {
                if (! first)
                {
                    sb.append('|');
                }

                sb.append(value);
                first = false;
            }

            sb.append(')');
        }

        return sb.toString();
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            CovOut.log("registry.csv: строк " + this.out.doneCount());
            this.out.close();
        }
    }
}
