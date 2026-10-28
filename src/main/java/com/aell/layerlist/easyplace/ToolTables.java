package com.aell.layerlist.easyplace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.component.BlockTransformers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.CopyPropertiesProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Таблицы «что во что превращает инструмент»: лопата → тропинка, мотыга → грядка,
 * топор → обтёсанное бревно.
 *
 * До 26.2 это были статические карты в ShovelItem / HoeItem / AxeItem, и мод читал их
 * аксессорами. В 26.3 классов инструментов больше нет — превращения стали данными:
 * компонент BLOCK_TRANSFORMER у предмета ссылается на запись реестра block_transformer,
 * а внутри неё правила «если блок такой — поставить такой» ({@link RuleBasedStateProvider}).
 * Условие правила лежит в {@link BlockPredicate} без публичных полей, поэтому условие
 * читается через его же кодек в JSON — ту самую форму, в какой оно записано в датапаке
 * (matching_blocks, matching_block_tag, all_of). Условия со смещением (например
 * «сверху воздух») — это требования к соседям, а не к самому блоку; в таблицу они не входят:
 * как и раньше, их проверяет остальной код.
 *
 * Таблицы считаются лениво и держатся, пока не сменится мир: реестр приходит с сервера
 * и может отличаться от ванильного.
 */
@Environment(EnvType.CLIENT)
public final class ToolTables
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/tool-tables");

    private ToolTables() {}

    @Nullable private static ClientLevel levelOf;
    private static Map<Block, BlockState> flattenables = Map.of();
    private static Map<Block, Block> tillables = Map.of();
    private static Map<Block, Block> strippables = Map.of();

    /** Лопата: блок → состояние, которое встанет на его место. */
    public static Map<Block, BlockState> flattenables()
    {
        refresh();
        return flattenables;
    }

    /** Мотыга: вспахивается ли этот блок вообще (что получится — знает остальной код). */
    public static boolean tillable(Block block)
    {
        refresh();
        return tillables.containsKey(block);
    }

    /**
     * Топор: обычное бревно → обтёсанное. Только обтёсывание: снятие воска и окисла топор
     * тоже делает, но это другие таблицы — как и в старом AxeItem.STRIPPABLES.
     */
    public static Map<Block, Block> strippables()
    {
        refresh();
        return strippables;
    }

    private static void refresh()
    {
        ClientLevel level = Minecraft.getInstance().level;

        if (level == null || level == levelOf)
        {
            return;
        }

        levelOf = level;

        try
        {
            HolderLookup.RegistryLookup<BlockTransformer> transformers = level.registryAccess().lookupOrThrow(Registries.BLOCK_TRANSFORMER);
            HolderLookup.RegistryLookup<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());

            Map<Block, BlockState> flat = new HashMap<>();
            for (Rule r : rules(transformers, blocks, ops, BlockTransformers.SHOVEL))
            {
                for (Block b : r.inputs) flat.putIfAbsent(b, r.result);
            }

            Map<Block, Block> till = new HashMap<>();
            for (Rule r : rules(transformers, blocks, ops, BlockTransformers.HOE))
            {
                for (Block b : r.inputs) till.putIfAbsent(b, r.result.getBlock());
            }

            Map<Block, Block> strip = new HashMap<>();
            for (Rule r : rules(transformers, blocks, ops, BlockTransformers.AXE))
            {
                if (! BuiltInRegistries.BLOCK.getKey(r.result.getBlock()).getPath().startsWith("stripped_"))
                {
                    continue;
                }
                for (Block b : r.inputs) strip.putIfAbsent(b, r.result.getBlock());
            }

            flattenables = Map.copyOf(flat);
            tillables = Map.copyOf(till);
            strippables = Map.copyOf(strip);
            LOGGER.info("tool tables from block_transformer registry: shovel {}, hoe {}, axe {}", flat.size(), till.size(), strip.size());
        }
        catch (RuntimeException | LinkageError e)
        {
            flattenables = Map.of();
            tillables = Map.of();
            strippables = Map.of();
            LOGGER.warn("tool tables unavailable: {}", e.toString());
        }
    }

    private record Rule(List<Block> inputs, BlockState result) {}

    private static List<Rule> rules(HolderLookup.RegistryLookup<BlockTransformer> transformers, HolderLookup.RegistryLookup<Block> blocks,
                                    DynamicOps<JsonElement> ops, ResourceKey<BlockTransformer> key)
    {
        List<Rule> out = new ArrayList<>();
        Optional<Holder.Reference<BlockTransformer>> holder = transformers.get(key);

        if (holder.isEmpty())
        {
            return out;
        }

        for (BlockTransformer.BlockTransformData data : holder.get().value().transforms())
        {
            if (! (data.blockStateProvider().value() instanceof RuleBasedStateProvider ruled))
            {
                continue;
            }

            for (RuleBasedStateProvider.Rule rule : ruled.rules())
            {
                BlockState result = resultOf(rule.then().value(), 0);

                if (result == null)
                {
                    continue;
                }

                List<Block> inputs = new ArrayList<>();
                BlockPredicate.CODEC.encodeStart(ops, rule.ifTrue()).result().ifPresent(json -> collect(json, blocks, inputs));

                if (! inputs.isEmpty())
                {
                    out.add(new Rule(List.copyOf(inputs), result));
                }
            }
        }

        return out;
    }

    /** Что ставит правило: простое состояние или «скопировать свойства с блока-источника». */
    @Nullable
    private static BlockState resultOf(BlockStateProvider provider, int depth)
    {
        if (depth > 4)
        {
            return null;
        }
        if (provider instanceof SimpleStateProvider simple)
        {
            return simple.state();
        }
        if (provider instanceof CopyPropertiesProvider copy)
        {
            return resultOf(copy.source().value(), depth + 1);
        }
        return null;
    }

    /** Блоки, о которых говорит условие правила — без условий про соседей (со смещением). */
    private static void collect(JsonElement json, HolderLookup.RegistryLookup<Block> blocks, List<Block> into)
    {
        if (! json.isJsonObject())
        {
            return;
        }

        JsonObject o = json.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";

        if (o.has("offset"))
        {
            for (JsonElement n : o.getAsJsonArray("offset"))
            {
                if (n.getAsInt() != 0)
                {
                    return;
                }
            }
        }

        if (type.endsWith("all_of") || type.endsWith("any_of"))
        {
            if (o.has("predicates"))
            {
                for (JsonElement p : o.getAsJsonArray("predicates"))
                {
                    collect(p, blocks, into);
                }
            }
        }
        else if (type.endsWith("matching_blocks") && o.has("blocks"))
        {
            JsonElement b = o.get("blocks");
            if (b.isJsonArray())
            {
                for (JsonElement id : b.getAsJsonArray()) addBlock(id.getAsString(), blocks, into);
            }
            else
            {
                addBlock(b.getAsString(), blocks, into);
            }
        }
        else if (type.endsWith("matching_block_tag") && o.has("tag"))
        {
            addTag(o.get("tag").getAsString(), blocks, into);
        }
    }

    private static void addBlock(String id, HolderLookup.RegistryLookup<Block> blocks, List<Block> into)
    {
        if (id.startsWith("#"))
        {
            addTag(id.substring(1), blocks, into);
            return;
        }

        Identifier location = Identifier.tryParse(id);

        if (location != null)
        {
            blocks.get(ResourceKey.create(Registries.BLOCK, location)).ifPresent(h -> into.add(h.value()));
        }
    }

    private static void addTag(String id, HolderLookup.RegistryLookup<Block> blocks, List<Block> into)
    {
        Identifier location = Identifier.tryParse(id);

        if (location == null)
        {
            return;
        }

        Optional<HolderSet.Named<Block>> set = blocks.get(TagKey.create(Registries.BLOCK, location));
        set.ifPresent(named -> named.forEach(h -> into.add(h.value())));
    }
}
