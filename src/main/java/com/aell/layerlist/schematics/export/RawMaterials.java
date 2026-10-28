package com.aell.layerlist.schematics.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Блок «Крупное сырьё» таблицы сбора: во что раскладываются предметы списка по рецептам самой
 * игры (данные {@code data/minecraft/recipe} и теги предметов из jar-а Minecraft).
 *
 * <p>Правила те же, что у нашего {@code north-sheets/raw.py}: из нескольких рецептов берётся самый
 * дешёвый; то, что добывают, а не крафтят (природные камни, руды-слитки, красители, шерсть), не
 * раскладывается; медь любой стадии окисления считается как обычная; бетон — через сухой бетон;
 * тег одной породы дерева сохраняет породу, а смешанный тег даёт «брёвна любой породы».
 */
@Environment(EnvType.CLIENT)
final class RawMaterials
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    static final String ANY_LOG = "any_log";
    static final String ANY_LEAVES = "any_leaves";
    private static final int SOURCES_SHOWN = 3;

    // Что добывают, а не крафтят: дальше не раскладываем
    private static final Set<String> MINED = Set.of(
            "redstone", "coal", "charcoal", "quartz", "diamond", "emerald", "lapis_lazuli", "amethyst_shard",
            "flint", "leather", "string", "bone", "honeycomb", "slime_ball", "glowstone_dust", "blaze_rod",
            "ender_pearl", "gunpowder", "honey_bottle", "resin_clump", "sugar_cane", "wheat", "clay_ball",
            "prismarine_shard", "prismarine_crystals", "kelp", "bamboo", "melon_slice", "nether_star", "shulker_shell",
            "ink_sac", "obsidian", "crying_obsidian", "netherite_scrap", "breeze_rod", "echo_shard", "ghast_tear",
            "magma_cream", "feather", "egg", "rabbit_hide", "phantom_membrane", "snowball", "pointed_dripstone",
            "andesite", "granite", "diorite", "stone", "deepslate", "coarse_dirt", "tuff", "calcite", "blackstone",
            "basalt", "netherrack", "end_stone", "sand", "red_sand", "gravel", "dirt", "mud", "moss_block",
            "pale_moss_block", "cobblestone", "cobbled_deepslate", "soul_sand", "soul_soil", "ice", "snow_block");
    private static final List<String> MINED_SUFFIXES = List.of("_ingot", "_dye", "_wool");

    // Из равноценных путей реже выбираем эти ресурсы (лоза и древесный уголь — неудобный сбор)
    private static final Map<String, Double> PENALTY = Map.of("vine", 1.5, "charcoal", 1.5, "deepslate", 1.2);

    private static final Pattern OXIDATION = Pattern.compile("^(waxed_)?(exposed_|weathered_|oxidized_)");
    private static final Pattern WOOD_FORM = Pattern.compile("(_log|_wood|_stem|_hyphae|_planks|_slab|_leaves)$");

    private static final Object LOCK = new Object();
    @Nullable private static volatile Book book;
    private static volatile boolean unavailable;

    private RawMaterials() {}

    /**
     * Ресурс.
     *
     * @param id       путь предмета ({@code oak_log}) или {@link #ANY_LOG}/{@link #ANY_LEAVES}
     * @param sources  до трёх предметов списка, ради которых он нужен больше всего
     */
    record Resource(String id, String name, long count, int maxStack, List<String> sources) {}

    /**
     * @param logs брёвна и стебли всех пород вместе, включая обтёсанные, — для строки «всего»
     */
    record Result(List<Resource> resources, long logs) {}

    /** Разложение строк выгрузки; {@code null} — рецепты прочитать не удалось, блок не рисуется. */
    @Nullable
    static Result of(List<ExportData.Line> lines)
    {
        Book recipes = book();

        return recipes == null ? null : recipes.decompose(lines);
    }

    /** Прочитать рецепты заранее (с фонового потока), чтобы первая выгрузка не ждала. */
    static void preload()
    {
        book();
    }

    @Nullable
    private static Book book()
    {
        Book ready = book;

        if (ready != null || unavailable)
        {
            return ready;
        }

        synchronized (LOCK)
        {
            if (book == null && ! unavailable)
            {
                try
                {
                    Path data = FabricLoader.getInstance().getModContainer("minecraft")
                            .flatMap(container -> container.findPath("data/minecraft"))
                            .orElseThrow(() -> new IOException("no data/minecraft in the game jar"));
                    book = load(data, RawMaterials::isKnownItem);
                }
                catch (IOException | RuntimeException e)
                {
                    unavailable = true;
                    LOGGER.warn("Export: could not read vanilla recipes, raw materials block is skipped", e);
                }
            }

            return book;
        }
    }

    /** Прочитать рецепты и теги из папки {@code data/minecraft} (в jar-е игры или распакованной). */
    static Book load(Path data, Predicate<String> known) throws IOException
    {
        Map<String, List<Recipe>> recipes = new HashMap<>();
        Path recipeDir = data.resolve("recipe");

        // Только верхний уровень: в подпапке brewing — зелья, к стройке отношения не имеют
        try (Stream<Path> files = Files.list(recipeDir))
        {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList())
            {
                JsonElement root = readJson(file);

                if (root instanceof JsonObject object)
                {
                    Recipe recipe = parseRecipe(object);

                    if (recipe != null)
                    {
                        recipes.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
                    }
                }
            }
        }

        Map<String, List<String>> tags = new HashMap<>();
        Path tagDir = data.resolve("tags").resolve("item");

        if (Files.isDirectory(tagDir))
        {
            try (Stream<Path> files = Files.walk(tagDir))
            {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList())
                {
                    List<String> parts = new ArrayList<>();
                    tagDir.relativize(file).forEach(part -> parts.add(part.toString()));
                    String name = String.join("/", parts);
                    name = name.substring(0, name.length() - ".json".length());

                    if (readJson(file) instanceof JsonObject object && object.get("values") instanceof JsonArray values)
                    {
                        List<String> members = new ArrayList<>();

                        for (JsonElement value : values)
                        {
                            if (value instanceof JsonPrimitive primitive && primitive.isString())
                            {
                                members.add(primitive.getAsString());
                            }
                            else if (value instanceof JsonObject entry && entry.get("id") instanceof JsonPrimitive id)
                            {
                                members.add(id.getAsString());
                            }
                        }

                        tags.put(local(name), members);
                    }
                }
            }
        }

        return new Book(recipes, tags, known);
    }

    @Nullable
    private static JsonElement readJson(Path file)
    {
        try (Reader reader = Files.newBufferedReader(file))
        {
            return JsonParser.parseReader(reader);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.debug("Export: skipping unreadable recipe file {}", file, e);
            return null;
        }
    }

    @Nullable
    private static Recipe parseRecipe(JsonObject json)
    {
        if (! (json.get("type") instanceof JsonPrimitive type)
                || ! (json.get("result") instanceof JsonObject result)
                || ! (result.get("id") instanceof JsonPrimitive id))
        {
            return null;
        }

        int count = result.get("count") instanceof JsonPrimitive n && n.isNumber() ? n.getAsInt() : 1;
        Map<Ingredient, Integer> inputs = new LinkedHashMap<>();
        boolean ok = switch (local(type.getAsString()))
        {
            case "crafting_shaped" -> shaped(json, inputs);
            case "crafting_shapeless" -> each(json.get("ingredients"), inputs);
            case "crafting_transmute" -> add(json.get("input"), inputs) && add(json.get("material"), inputs);
            case "stonecutting", "smelting", "blasting" -> add(json.get("ingredient"), inputs);
            default -> false;
        };

        return ok && ! inputs.isEmpty() && count > 0 ? new Recipe(local(id.getAsString()), count, inputs) : null;
    }

    private static boolean shaped(JsonObject json, Map<Ingredient, Integer> inputs)
    {
        if (! (json.get("pattern") instanceof JsonArray pattern) || ! (json.get("key") instanceof JsonObject key))
        {
            return false;
        }

        for (JsonElement line : pattern)
        {
            String row = line.getAsString();

            for (int i = 0; i < row.length(); i++)
            {
                char symbol = row.charAt(i);

                if (symbol != ' ' && ! add(key.get(String.valueOf(symbol)), inputs))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean each(@Nullable JsonElement list, Map<Ingredient, Integer> inputs)
    {
        if (! (list instanceof JsonArray array))
        {
            return false;
        }

        for (JsonElement element : array)
        {
            if (! add(element, inputs))
            {
                return false;
            }
        }

        return true;
    }

    private static boolean add(@Nullable JsonElement element, Map<Ingredient, Integer> inputs)
    {
        Ingredient ingredient = ingredient(element);

        if (ingredient == null)
        {
            return false;
        }

        inputs.merge(ingredient, 1, Integer::sum);

        return true;
    }

    @Nullable
    private static Ingredient ingredient(@Nullable JsonElement element)
    {
        if (element instanceof JsonPrimitive primitive && primitive.isString())
        {
            String value = primitive.getAsString();

            return value.startsWith("#") ? new TagOf(local(value.substring(1))) : new ItemOf(local(value));
        }

        if (element instanceof JsonArray array && ! array.isEmpty())
        {
            List<Ingredient> options = new ArrayList<>();

            for (JsonElement option : array)
            {
                Ingredient parsed = ingredient(option);

                if (parsed == null)
                {
                    return null;
                }

                options.add(parsed);
            }

            return new AnyOf(List.copyOf(options));
        }

        return null;
    }

    /** {@code minecraft:oak_log} → {@code oak_log}; чужие пространства имён остаются полными. */
    static String local(String id)
    {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static boolean isKnownItem(String id)
    {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);

        return key != null && (BuiltInRegistries.ITEM.containsKey(key) || BuiltInRegistries.BLOCK.containsKey(key));
    }

    static String name(String id)
    {
        if (id.equals(ANY_LOG))
        {
            return ExportText.t("raw.anyLog");
        }

        if (id.equals(ANY_LEAVES))
        {
            return ExportText.t("raw.anyLeaves");
        }

        Item item = item(id);

        return item == null ? id : new ItemStack(item).getHoverName().getString();
    }

    static int maxStack(String id)
    {
        Item item = item(id);

        return item == null ? 64 : Math.max(1, item.getDefaultMaxStackSize());
    }

    @Nullable
    private static Item item(String id)
    {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);

        return key != null && BuiltInRegistries.ITEM.containsKey(key) ? BuiltInRegistries.ITEM.getValue(key) : null;
    }

    private sealed interface Ingredient permits ItemOf, TagOf, AnyOf {}

    private record ItemOf(String id) implements Ingredient {}

    private record TagOf(String tag) implements Ingredient {}

    private record AnyOf(List<Ingredient> options) implements Ingredient {}

    private record Recipe(String output, int count, Map<Ingredient, Integer> inputs) {}

    /** Точная дробь: 1/9 блока девять раз — ровно один, без хвостов double перед округлением вверх. */
    private record Ratio(BigInteger num, BigInteger den)
    {
        static final Ratio ONE = new Ratio(BigInteger.ONE, BigInteger.ONE);
        static final Ratio ZERO = new Ratio(BigInteger.ZERO, BigInteger.ONE);

        static Ratio of(long num, long den)
        {
            return reduce(BigInteger.valueOf(num), BigInteger.valueOf(den));
        }

        private static Ratio reduce(BigInteger num, BigInteger den)
        {
            BigInteger gcd = num.gcd(den);

            return gcd.signum() == 0 ? ZERO : new Ratio(num.divide(gcd), den.divide(gcd));
        }

        Ratio plus(Ratio other)
        {
            return reduce(this.num.multiply(other.den).add(other.num.multiply(this.den)), this.den.multiply(other.den));
        }

        Ratio times(Ratio other)
        {
            return reduce(this.num.multiply(other.num), this.den.multiply(other.den));
        }

        long ceil()
        {
            BigInteger[] parts = this.num.divideAndRemainder(this.den);

            return parts[0].longValue() + (parts[1].signum() > 0 ? 1 : 0);
        }

        double value()
        {
            return this.num.doubleValue() / this.den.doubleValue();
        }
    }

    /** Рецепты и теги одной версии игры; разложение кешируется по предмету. */
    static final class Book
    {
        private final Map<String, List<Recipe>> recipes;
        private final Map<String, List<String>> tags;
        private final Predicate<String> known;
        private final Map<String, Map<String, Ratio>> memo = new HashMap<>();

        private Book(Map<String, List<Recipe>> recipes, Map<String, List<String>> tags, Predicate<String> known)
        {
            this.recipes = recipes;
            this.tags = tags;
            this.known = known;
        }

        int recipeCount()
        {
            int count = 0;

            for (List<Recipe> list : this.recipes.values())
            {
                count += list.size();
            }

            return count;
        }

        synchronized Result decompose(List<ExportData.Line> lines)
        {
            // Варианты одного предмета (флаги с разными узорами) раскладываются вместе
            Map<String, Long> wanted = new LinkedHashMap<>();
            Map<String, String> names = new HashMap<>();

            for (ExportData.Line line : lines)
            {
                String id = local(line.id());
                wanted.merge(id, line.quantity(), Long::sum);
                names.putIfAbsent(id, line.name());
            }

            Map<String, Ratio> need = new LinkedHashMap<>();
            Map<String, Map<String, Ratio>> reasons = new HashMap<>();

            for (Map.Entry<String, Long> entry : wanted.entrySet())
            {
                Map<String, Ratio> cost = this.cost(entry.getKey(), new ArrayDeque<>());

                if (cost == null)
                {
                    cost = Map.of(entry.getKey(), Ratio.ONE);
                }

                Ratio amount = Ratio.of(entry.getValue(), 1);

                for (Map.Entry<String, Ratio> part : cost.entrySet())
                {
                    Ratio share = part.getValue().times(amount);
                    need.merge(part.getKey(), share, Ratio::plus);
                    reasons.computeIfAbsent(part.getKey(), k -> new HashMap<>()).merge(entry.getKey(), share, Ratio::plus);
                }
            }

            List<Resource> resources = new ArrayList<>();
            Ratio logs = Ratio.ZERO;

            for (Map.Entry<String, Ratio> entry : need.entrySet())
            {
                String resource = entry.getKey();

                if (isLog(resource))
                {
                    logs = logs.plus(entry.getValue());
                }

                Map<String, Ratio> why = reasons.get(resource);

                // Ресурс, который нужен только сам по себе, уже есть в основном списке
                if (why.keySet().stream().allMatch(resource::equals))
                {
                    continue;
                }

                List<String> sources = why.entrySet().stream()
                        .sorted(Comparator.comparingDouble((Map.Entry<String, Ratio> e) -> -e.getValue().value())
                                .thenComparing(Map.Entry::getKey))
                        .limit(SOURCES_SHOWN)
                        .map(e -> names.getOrDefault(e.getKey(), name(e.getKey())))
                        .toList();

                resources.add(new Resource(resource, name(resource), entry.getValue().ceil(), maxStackOf(resource), sources));
            }

            resources.sort(Comparator.comparingLong((Resource r) -> -r.count()).thenComparing(Resource::name));

            return new Result(List.copyOf(resources), logs.ceil());
        }

        private static boolean isLog(String id)
        {
            return id.equals(ANY_LOG) || id.endsWith("_log") || id.endsWith("_stem");
        }

        private static int maxStackOf(String id)
        {
            return id.equals(ANY_LOG) || id.equals(ANY_LEAVES) ? 64 : maxStack(id);
        }

        private boolean mined(String id)
        {
            if (MINED.contains(id) || ! this.recipes.containsKey(id))
            {
                return true;
            }

            for (String suffix : MINED_SUFFIXES)
            {
                if (id.endsWith(suffix))
                {
                    return true;
                }
            }

            return false;
        }

        /** Сырьё на одну штуку; {@code null} — путь есть только через цикл рецептов. */
        @Nullable
        private Map<String, Ratio> cost(String id, Deque<String> path)
        {
            Map<String, Ratio> known = this.memo.get(id);

            if (known != null)
            {
                return known;
            }

            // Бетон не крафтится — его льют из сухого бетона
            if (id.endsWith("_concrete") && ! this.recipes.containsKey(id))
            {
                return this.cost(id + "_powder", path);
            }

            Matcher oxidized = OXIDATION.matcher(id);

            if (oxidized.find())
            {
                String plain = oxidized.replaceFirst(oxidized.group(1) == null ? "" : oxidized.group(1));
                List<String> candidates = new ArrayList<>(List.of(plain, plain + "_block"));

                if (plain.equals("copper") || plain.equals("waxed_copper"))
                {
                    candidates.add("copper_block");
                }

                for (String candidate : candidates)
                {
                    if (! candidate.equals(id) && (this.recipes.containsKey(candidate) || this.known.test(candidate)))
                    {
                        path.push(id);
                        Map<String, Ratio> cost = this.cost(candidate, path);
                        path.pop();

                        if (cost != null)
                        {
                            return cost;
                        }
                    }
                }
            }

            if (this.mined(id))
            {
                return Map.of(id, Ratio.ONE);
            }

            Map<String, Ratio> best = null;
            boolean cycled = false;
            path.push(id);

            for (Recipe recipe : this.recipes.get(id))
            {
                Map<String, Ratio> total = new HashMap<>();
                boolean complete = true;
                Ratio perOutput = Ratio.of(1, recipe.count());

                for (Map.Entry<Ingredient, Integer> input : recipe.inputs().entrySet())
                {
                    Map<String, Ratio> part = this.ingredientCost(input.getKey(), path);

                    if (part == null)
                    {
                        complete = false;
                        cycled = true;
                        break;
                    }

                    Ratio factor = Ratio.of(input.getValue(), 1).times(perOutput);
                    part.forEach((resource, amount) -> total.merge(resource, amount.times(factor), Ratio::plus));
                }

                if (complete && (best == null || score(total) < score(best)))
                {
                    best = total;
                }
            }

            path.pop();

            if (best == null && ! cycled)
            {
                best = Map.of(id, Ratio.ONE);
            }

            // Итог, полученный в обход цикла, зависит от пути — его не запоминаем
            if (! cycled && best != null)
            {
                this.memo.put(id, best);
            }

            return best;
        }

        @Nullable
        private Map<String, Ratio> ingredientCost(Ingredient ingredient, Deque<String> path)
        {
            return switch (ingredient)
            {
                case ItemOf item -> path.contains(item.id()) ? null : this.cost(item.id(), path);
                case AnyOf any -> this.cheapest(any.options(), path);
                case TagOf tag -> this.tagCost(tag.tag(), path);
            };
        }

        @Nullable
        private Map<String, Ratio> cheapest(List<Ingredient> options, Deque<String> path)
        {
            Map<String, Ratio> best = null;

            for (Ingredient option : options)
            {
                Map<String, Ratio> cost = this.ingredientCost(option, path);

                if (cost != null && (best == null || score(cost) < score(best)))
                {
                    best = cost;
                }
            }

            return best;
        }

        @Nullable
        private Map<String, Ratio> tagCost(String tag, Deque<String> path)
        {
            List<String> members = this.members(tag, new HashSet<>());

            if (members.isEmpty())
            {
                return null;
            }

            String first = members.get(0);
            Map<String, Ratio> cost = path.contains(first) ? null : this.cost(first, path);

            if (cost == null)
            {
                return null;
            }

            // Тег одной породы (#birch_logs) — порода важна; смешанный (#planks) — годится любая
            Set<String> species = new HashSet<>();
            members.forEach(member -> species.add(species(member)));

            if (species.size() == 1)
            {
                return cost;
            }

            Map<String, Ratio> generic = new HashMap<>();
            cost.forEach((resource, amount) -> generic.merge(generic(resource), amount, Ratio::plus));

            return generic;
        }

        private List<String> members(String tag, Set<String> seen)
        {
            List<String> out = new ArrayList<>();

            if (! seen.add(tag))
            {
                return out;
            }

            for (String value : this.tags.getOrDefault(tag, List.of()))
            {
                if (value.startsWith("#"))
                {
                    out.addAll(this.members(local(value.substring(1)), seen));
                }
                else
                {
                    out.add(local(value));
                }
            }

            return out;
        }

        private static String species(String id)
        {
            String bare = id.startsWith("stripped_") ? id.substring("stripped_".length()) : id;

            return WOOD_FORM.matcher(bare).replaceFirst("");
        }

        private static String generic(String resource)
        {
            if ((resource.endsWith("_log") || resource.endsWith("_stem")) && ! resource.startsWith("stripped_"))
            {
                return ANY_LOG;
            }

            return resource.endsWith("_leaves") ? ANY_LEAVES : resource;
        }

        private static double score(Map<String, Ratio> cost)
        {
            double sum = 0.0;

            for (Map.Entry<String, Ratio> entry : cost.entrySet())
            {
                sum += entry.getValue().value() * PENALTY.getOrDefault(entry.getKey(), 1.0);
            }

            return sum;
        }
    }
}
