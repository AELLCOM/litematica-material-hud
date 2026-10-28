package com.aell.layerlist.core;

import com.aell.layerlist.easyplace.ItemData;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.Nullable;

/**
 * Клетки, где предмет различается не состоянием блока, а его данными: узор баннера,
 * черепки горшка, профиль головы.
 *
 * Litematica считает список материалов по BlockState, а у трёх баннеров с разными узорами
 * состояние одно — в списке они слипались в один «баннер без узора», и человек приносил
 * к стройке не то. Здесь такие клетки учитываются своим ключом: предмет плюс опознающие
 * компоненты (те же, по которым {@link ItemData#same} решает, годится ли предмет из
 * инвентаря). В HUD каждый узор — отдельная строка со своей картинкой (баннер рисуется
 * с узором, горшок — с черепками) и подписью, что именно на нём.
 */
@Environment(EnvType.CLIENT)
public final class DataItems
{
    private DataItems() {}

    /** Ключ учёта. Образец предмета хранится для отрисовки строки, в сравнении не участвует. */
    public static final class Key
    {
        private final Item item;
        private final BannerPatternLayers patterns;
        private final PotDecorations decorations;
        @Nullable private final ResolvableProfile profile;
        /** Уровень предмета «Свет»; у остальных -1. */
        private final int light;
        private final ItemStack sample;

        public Key(ItemStack stack)
        {
            this.item = stack.getItem();
            this.patterns = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
            this.decorations = stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
            this.profile = stack.get(DataComponents.PROFILE);
            this.light = ItemData.lightLevel(stack);
            this.sample = stack.copyWithCount(1);
        }

        /** Предмет с данными — то, что надо принести к этой клетке. */
        public ItemStack sample()
        {
            return this.sample;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Key k && k.item == this.item && k.patterns.equals(this.patterns)
                    && k.decorations.equals(this.decorations) && Objects.equals(k.profile, this.profile)
                    && k.light == this.light;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(this.item, this.patterns, this.decorations, this.profile, this.light);
        }
    }

    /** Есть ли у предмета данные, ради которых его стоит считать отдельно от состояния блока. */
    public static boolean hasData(ItemStack stack)
    {
        return ! stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty()
                || ! stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY).equals(PotDecorations.EMPTY)
                || stack.get(DataComponents.PROFILE) != null
                || ItemData.lightLevel(stack) >= 0;
    }

    /**
     * Подпись строки HUD и сообщений: только имя предмета. Узор баннера и черепки горшка видны на иконке, а их
     * перечень («Оранжевый крест, Оранжевый Андреевский крест +4») только растягивал строку (с 1.0.9);
     * полная подпись — {@link #fullLabel}, в таблице «Материалы».
     */
    public static String label(ItemStack stack)
    {
        return name(stack).getString() + lightSuffix(stack);
    }

    /** У предмета «Свет» к имени — уровень («Свет 7»): иначе 16 строк списка назывались одинаково. */
    private static String lightSuffix(ItemStack stack)
    {
        int level = ItemData.lightLevel(stack);

        return level >= 0 ? " " + level : "";
    }

    /**
     * Имя предмета строки. Голова игрока с пустым именем в профиле (кастомные головы с серверов и из баз голов хранят
     * {@code name: ""} рядом с текстурой) у ванили называется «'s Head» — такой берём обычное имя предмета, «Голова
     * игрока» (14.10.2026). Своё название предмета, если оно есть, остаётся.
     */
    private static Component name(ItemStack stack)
    {
        ResolvableProfile profile = stack.get(DataComponents.PROFILE);

        if (profile != null && stack.get(DataComponents.CUSTOM_NAME) == null
                && profile.name().map(String::isBlank).orElse(false))
        {
            return Component.translatable(stack.getItem().getDescriptionId());
        }

        return stack.getHoverName();
    }

    /**
     * Полная подпись — для таблицы «Материалы» (и её выгрузок): имя предмета, а для баннера и горшка — ещё и
     * что на них. Слои узора называются так же, как в подсказке самого предмета («Black Skull Charge»),
     * показываем первые два, остальные — числом; у горшка — черепки, кроме кирпича.
     */
    public static String fullLabel(ItemStack stack)
    {
        String name = name(stack).getString() + lightSuffix(stack);
        List<String> details = new ArrayList<>();

        BannerPatternLayers layers = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

        for (BannerPatternLayers.Layer layer : layers.layers())
        {
            details.add(Component.translatable(layer.pattern().value().translationKey() + "." + layer.color().getName()).getString());
        }

        PotDecorations decorations = stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
        Set<String> sherds = new LinkedHashSet<>();

        for (Optional<Item> side : List.of(decorations.back(), decorations.left(), decorations.right(), decorations.front()))
        {
            side.ifPresent(sherd ->
            {
                if (sherd != Items.BRICK)
                {
                    sherds.add(sherd.getDefaultInstance().getHoverName().getString());
                }
            });
        }

        details.addAll(sherds);

        if (details.isEmpty())
        {
            return name;
        }

        String shown = String.join(", ", details.subList(0, Math.min(2, details.size())));

        if (details.size() > 2)
        {
            shown += " +" + (details.size() - 2);
        }

        return name + " · " + shown;
    }

    /** Сколько таких предметов у игрока — считая только те, что совпадают по данным. */
    public static int available(@Nullable LocalPlayer player, ItemStack required)
    {
        if (player == null)
        {
            return 0;
        }

        Inventory inventory = player.getInventory();
        int count = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
        {
            count += countIn(inventory.getItem(slot), required, 0);
        }

        return count;
    }

    /**
     * Сколько точно таких предметов в стопке — с содержимым шалкеров и мешков, как «есть» у строк Litematica и
     * колонка «В инвентаре» таблицы (иначе узорный флаг в шалкере в HUD значился бы «нет»).
     */
    private static int countIn(ItemStack stack, ItemStack required, int depth)
    {
        if (stack.isEmpty())
        {
            return 0;
        }

        if (ItemData.same(stack, required))
        {
            return stack.getCount();
        }

        if (depth >= 2)
        {
            return 0;
        }

        int inside = 0;
        net.minecraft.world.item.component.ItemContainerContents container = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);

        if (container != null)
        {
            for (ItemStack item : (Iterable<ItemStack>) container.nonEmptyItemCopyStream()::iterator)
            {
                inside += countIn(item, required, depth + 1);
            }
        }

        net.minecraft.world.item.component.BundleContents bundle = stack.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);

        if (bundle != null)
        {
            for (ItemStack item : (Iterable<ItemStack>) bundle.itemCopyStream()::iterator)
            {
                inside += countIn(item, required, depth + 1);
            }
        }

        return inside * stack.getCount();
    }

    /** Строки списка материалов для клеток с данными — в том же виде, что строки Litematica. */
    public static List<MaterialListEntry> entries(Object2IntOpenHashMap<Key> total, Object2IntOpenHashMap<Key> missing,
                                                  Object2IntOpenHashMap<Key> mismatch, @Nullable LocalPlayer player)
    {
        List<MaterialListEntry> out = new ArrayList<>();

        for (Key key : total.keySet())
        {
            out.add(new MaterialListEntry(key.sample().copy(), total.getInt(key), missing.getInt(key),
                    mismatch.getInt(key), available(player, key.sample())));
        }

        return out;
    }
}
