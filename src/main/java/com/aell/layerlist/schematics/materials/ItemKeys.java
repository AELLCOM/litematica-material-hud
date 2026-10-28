package com.aell.layerlist.schematics.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.PotDecorations;

/**
 * Строковый ключ строки материалов: id предмета и те же опознающие компоненты, по которым
 * {@code DataItems.Key} отличает один баннер от другого, — узор, черепки, профиль головы, уровень света.
 * Остальные компоненты (имя, износ) в ключ не входят, как и в {@code DataItems.Key}.
 *
 * <p>Ключ читаемый и не зависит от сессии: {@code minecraft:white_banner[patterns=minecraft:creeper/black]}.
 * На нём держатся кеш иконок, выбор строки в таблице и колонка id в выгрузках.
 */
@Environment(EnvType.CLIENT)
public final class ItemKeys
{
    private ItemKeys() {}

    public static String of(ItemStack stack)
    {
        StringBuilder key = new StringBuilder(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());

        BannerPatternLayers layers = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

        if (! layers.layers().isEmpty())
        {
            List<String> parts = new ArrayList<>(layers.layers().size());

            for (BannerPatternLayers.Layer layer : layers.layers())
            {
                parts.add(layer.pattern().getRegisteredName() + "/" + layer.color().getName());
            }

            key.append("[patterns=").append(String.join(",", parts)).append(']');
        }

        PotDecorations pot = stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);

        if (! pot.equals(PotDecorations.EMPTY))
        {
            key.append("[sherds=").append(side(pot.back())).append(',').append(side(pot.left())).append(',')
                    .append(side(pot.right())).append(',').append(side(pot.front())).append(']');
        }

        ResolvableProfile profile = stack.get(DataComponents.PROFILE);

        if (profile != null)
        {
            // Имя есть почти всегда; без него — UUID из неполного профиля
            key.append("[profile=").append(profile.name().orElseGet(() -> String.valueOf(profile.partialProfile().id())))
                    .append(']');
        }

        // Невидимый свет: уровень в данных предмета, как у DataItems.Key (у остальных предметов — -1)
        int light = com.aell.layerlist.easyplace.ItemData.lightLevel(stack);

        if (light >= 0)
        {
            key.append("[light=").append(light).append(']');
        }

        return key.toString();
    }

    private static String side(Optional<Item> side)
    {
        return side.map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).orElse("-");
    }
}
