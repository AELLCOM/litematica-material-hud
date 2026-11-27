package com.aell.layerlist.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Откуда берутся предметы для узорного флага — для карточки {@link BannerCard}: рецепт верстака или место, где искать.
 *
 * <p>Клиент на чужом сервере рецептов и таблиц добычи не знает, поэтому таблица своя. Сверена с данными игры 26.3
 * (24.11.2026, {@code data/minecraft} в jar): шесть предметов-узоров делаются из бумаги ({@code recipe/*_banner_pattern}),
 * четыре только находятся — «Рыло» в сундуках развалин бастиона ({@code chests/bastion_other}), «Вихрь» и «Поток» в
 * хранилищах дворца испытаний ({@code chests/trial_chambers/reward_unique}, {@code reward_ominous_unique}), «Земной
 * шар» продаёт картограф-мастер ({@code villager_trade/cartographer/5}). Стенд {@code bannercard} сверяет рецепты с
 * сервером и проверяет, что у каждого предмета-узора из реестра здесь есть запись.
 *
 * <p>Тексты мест — строки самой игры (описания достижений, названия предметов и профессий): они переведены на все
 * языки Minecraft, своих переводов для них не нужно.
 */
@Environment(EnvType.CLIENT)
public final class BannerSources
{
    /** Как достаётся предмет. */
    public enum Kind
    {
        /** Рецепт верстака: {@link Source#inputs} → {@link Source#result}. */
        CRAFT,
        /** Только найти или купить: {@link Source#inputs} — значки, {@link Source#lines} — где. */
        FOUND,
        /** Узор из общего набора станка: предмета не нужно. */
        LOOM,
        /** Предмет-узор, которого в таблице нет (другая версия игры, набор данных). */
        UNKNOWN
    }

    /** Страница «Как получить». */
    public record Source(Kind kind, ItemStack result, String title, List<ItemStack> inputs, List<String> lines) {}

    /** Предмет-узор → второй предмет рецепта (первый — бумага). */
    private static final Map<String, String> CRAFTS = new LinkedHashMap<>();
    /** Предмет-узор → {значки через запятую, ключи строк игры через запятую}. */
    private static final Map<String, String[]> FOUND = new LinkedHashMap<>();

    static
    {
        CRAFTS.put("flower_banner_pattern", "oxeye_daisy");
        CRAFTS.put("creeper_banner_pattern", "creeper_head");
        CRAFTS.put("skull_banner_pattern", "wither_skeleton_skull");
        CRAFTS.put("mojang_banner_pattern", "enchanted_golden_apple");
        CRAFTS.put("field_masoned_banner_pattern", "bricks");
        CRAFTS.put("bordure_indented_banner_pattern", "vine");
        FOUND.put("piglin_banner_pattern", new String[]{"chest", "advancements.nether.loot_bastion.description"});
        FOUND.put("guster_banner_pattern", new String[]{"vault,trial_key",
                "advancements.adventure.minecraft_trials_edition.description,advancements.adventure.under_lock_and_key.description"});
        FOUND.put("flow_banner_pattern", new String[]{"vault,ominous_trial_key",
                "advancements.adventure.minecraft_trials_edition.description,advancements.adventure.revaulting.description"});
        FOUND.put("globe_banner_pattern", new String[]{"emerald*8", ""});
    }

    private BannerSources() {}

    /** Как получить предмет-узор. */
    public static Source of(Item item)
    {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        ItemStack result = new ItemStack(item);
        String title = name(result);
        String second = CRAFTS.get(path);

        if (second != null)
        {
            ItemStack paper = new ItemStack(Items.PAPER);
            ItemStack other = stack(second, 1);
            return new Source(Kind.CRAFT, result, title, List.of(paper, other), List.of(
                    I18n.get("block.minecraft.crafting_table"), plain(paper) + " + " + plain(other), I18n.get("layerlist.banner.kept")));
        }

        String[] found = FOUND.get(path);

        if (found == null)
        {
            return new Source(Kind.UNKNOWN, result, title, List.of(), List.of(I18n.get("layerlist.banner.unknown")));
        }

        List<ItemStack> icons = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (String icon : found[0].split(","))
        {
            int star = icon.indexOf('*');
            ItemStack stack = star < 0 ? stack(icon, 1) : stack(icon.substring(0, star), Integer.parseInt(icon.substring(star + 1)));

            if (! stack.isEmpty())
            {
                icons.add(stack);
            }
        }

        if (path.equals("globe_banner_pattern"))
        {
            // Картограф пятого уровня продаёт за изумруды: профессия, уровень и предмет — названия самой игры
            lines.add(I18n.get("entity.minecraft.villager.cartographer") + " · " + I18n.get("merchant.level.5"));
            lines.add("8 × " + plain(new ItemStack(Items.EMERALD)));
        }
        else
        {
            for (String key : found[1].split(","))
            {
                if (! key.isEmpty() && net.minecraft.locale.Language.getInstance().has(key))
                {
                    lines.add(I18n.get(key));
                }
            }
        }

        lines.add(I18n.get("layerlist.banner.kept"));
        return new Source(Kind.FOUND, result, title, icons, lines);
    }

    /** Флаг-основа: шесть блоков шерсти его цвета и палка. */
    public static Source banner(ItemStack plain, DyeColor color)
    {
        ItemStack wool = stack(color.getName() + "_wool", 6);
        ItemStack stick = new ItemStack(Items.STICK);
        return new Source(Kind.CRAFT, plain, plain(plain), List.of(wool, stick), List.of(
                I18n.get("block.minecraft.crafting_table"), "6 × " + plain(wool) + " + " + plain(stick)));
    }

    /** Сам ткацкий станок: две нити и две доски. */
    public static Source loom()
    {
        ItemStack loom = stack("loom", 1);
        ItemStack string = new ItemStack(Items.STRING, 2);
        ItemStack planks = stack("oak_planks", 2);
        return new Source(Kind.CRAFT, loom, plain(loom), List.of(string, planks), List.of(
                I18n.get("block.minecraft.crafting_table"), "2 × " + plain(string) + " + 2 × " + plain(planks)));
    }

    /** Узор из общего набора станка. */
    public static Source noItem(String patternName)
    {
        ItemStack loom = stack("loom", 1);
        return new Source(Kind.LOOM, loom, patternName, List.of(loom), List.of(plain(loom), I18n.get("layerlist.banner.no_item")));
    }

    /** Предмет по имени в реестре; нет такого (другая версия игры) — пустая стопка. */
    public static ItemStack stack(String path, int count)
    {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** Название предмета; у предмета-узора — с названием самого узора («Узор флага — Крипер»). */
    public static String name(ItemStack stack)
    {
        String name = plain(stack);
        String pattern = stack.getItem().getDescriptionId() + ".desc";
        return net.minecraft.locale.Language.getInstance().has(pattern) ? name + " — " + I18n.get(pattern) : name;
    }

    private static String plain(ItemStack stack)
    {
        return stack.getHoverName().getString();
    }

    /** Для стенда: второй предмет рецепта (первый — бумага) или {@code null}, если предмет не делается на верстаке. */
    public static @Nullable String craftIngredient(Item item)
    {
        return CRAFTS.get(BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    /** Для стенда: есть ли о предмете-узоре запись. */
    public static boolean known(Item item)
    {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return CRAFTS.containsKey(path) || FOUND.containsKey(path);
    }
}
