package com.aell.layerlist.core;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Сборка строк списка материалов из счёта: общая для HUD ({@link LayerCountManager}) и вкладки
 * «Схемы» (таблица материалов файла схемы).
 *
 * <p>Три вида счёта — по состоянию блока, по клеткам с данными (узор, черепки, профиль) и по
 * сущностям — превращаются в строки одним и тем же путём: состояния переводит в предметы сама
 * Litematica (там же действуют наши правки {@code MaterialCache} для лоз), строки с данными и
 * сущностями дописываются своими ключами. Пока путь один, таблица схемы и HUD не расходятся ни
 * в названиях, ни в счёте.
 *
 * <p>Только клиентский поток: кеш {@code MaterialCache} Litematica не потокобезопасен.
 */
@Environment(EnvType.CLIENT)
public final class MaterialRows
{
    private MaterialRows() {}

    /**
     * Три карты одного вида счёта: всего, не хватает и «стоит не то». Карты не копируются —
     * держатель только связывает их на время сборки.
     */
    public record Counts<K>(Object2IntOpenHashMap<K> total, Object2IntOpenHashMap<K> missing,
                            Object2IntOpenHashMap<K> mismatch)
    {
        public Counts
        {
            Objects.requireNonNull(total, "total");
            Objects.requireNonNull(missing, "missing");
            Objects.requireNonNull(mismatch, "mismatch");
        }

        public static <K> Counts<K> empty()
        {
            return new Counts<>(new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>());
        }

        /**
         * Схема без сверки с миром: всё нужное считается недостающим, «не то» — пусто. Так же
         * Litematica строит список по файлу. Карты — копии: исходный счёт можно держать в кеше.
         */
        public static <K> Counts<K> wholeMissing(Object2IntOpenHashMap<K> total)
        {
            return new Counts<>(new Object2IntOpenHashMap<>(total), new Object2IntOpenHashMap<>(total),
                    new Object2IntOpenHashMap<>());
        }
    }

    /**
     * Строки списка: сначала блоки (через Litematica), затем клетки с данными, затем сущности —
     * сущность с тем же предметом, что уже есть строкой, сливается с ней ({@link #mergeRows}).
     * Список изменяемый, в порядке сборки; фильтр и сортировку делает вызывающий.
     */
    public static List<MaterialListEntry> build(Counts<BlockState> blocks, Counts<DataItems.Key> data,
                                                Counts<DataItems.Key> entities, @Nullable LocalPlayer player)
    {
        List<MaterialListEntry> list = MaterialListCompat.getMaterialList(
                blocks.total(), blocks.missing(), blocks.mismatch(), player);
        list.addAll(DataItems.entries(data.total(), data.missing(), data.mismatch(), player));
        mergeRows(list, DataItems.entries(entities.total(), entities.missing(), entities.mismatch(), player));

        return list;
    }

    /**
     * Дописать строки сущностей в список. Предмет, который уже есть строкой (камень в рамке
     * и камень в стене), — одна строка с общим счётом, а не две одинаковых.
     */
    public static void mergeRows(List<MaterialListEntry> list, List<MaterialListEntry> extra)
    {
        for (MaterialListEntry add : extra)
        {
            DataItems.Key key = new DataItems.Key(add.getStack());
            boolean merged = false;

            for (int i = 0; i < list.size(); ++i)
            {
                MaterialListEntry have = list.get(i);

                if (key.equals(new DataItems.Key(have.getStack())))
                {
                    list.set(i, new MaterialListEntry(have.getStack(), have.getCountTotal() + add.getCountTotal(),
                            have.getCountMissing() + add.getCountMissing(),
                            have.getCountMismatched() + add.getCountMismatched(),
                            Math.max(have.getCountAvailable(), add.getCountAvailable())));
                    merged = true;
                    break;
                }
            }

            if (! merged)
            {
                list.add(add);
            }
        }
    }

    /**
     * Счёт сущностей по предмету в том снимке, из которого собран текущий список HUD. Нужен,
     * чтобы отличить строку «только сущности» от блочной: строки сливаются, а по итогу строки
     * этого не видно. Пусто, пока HUD ничего не опубликовал. Копия — снимок менять нельзя.
     */
    public static Object2IntOpenHashMap<DataItems.Key> publishedEntityTotals()
    {
        LayerCountManager.Snapshot snapshot = LayerCountManager.getInstance().pinned;

        return snapshot == null ? new Object2IntOpenHashMap<>() : new Object2IntOpenHashMap<>(snapshot.entityTotal());
    }
}
