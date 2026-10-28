package com.aell.layerlist.core;

import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link MaterialListUtils#getMaterialList} без привязки к версии Litematica.
 * В 0.29.1 к нему добавились три карты сущностей (MaterialListEntityInfo), а вариант
 * на четыре аргумента убрали — jar, собранный против 0.29.0, падал с NoSuchMethodError.
 * Ищем любой из двух один раз; в новый передаём пустые карты: сущности HUD считает сам
 * ({@link SchematicEntities}) — по всей схеме и со сверкой цвета, содержимого и поворота.
 */
@Environment(EnvType.CLIENT)
final class MaterialListCompat
{
    private static final MethodHandle GET_MATERIAL_LIST = resolve();

    private MaterialListCompat()
    {
    }

    private static MethodHandle resolve()
    {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> map = Object2IntOpenHashMap.class;

        try
        {
            // 0.29.1+: блоки total/missing/mismatch, сущности total/missing/mismatch, игрок
            return lookup.findStatic(MaterialListUtils.class, "getMaterialList",
                    MethodType.methodType(List.class, map, map, map, map, map, map, Player.class));
        }
        catch (NoSuchMethodException | IllegalAccessException ignored)
        {
        }

        try
        {
            // до 0.29.0 включительно — только блоки; дописываем пустые карты сущностей
            MethodHandle h = lookup.findStatic(MaterialListUtils.class, "getMaterialList",
                    MethodType.methodType(List.class, map, map, map, Player.class));
            return MethodHandles.dropArguments(h, 3, map, map, map);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException("Litematica: не найден MaterialListUtils.getMaterialList", e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<MaterialListEntry> getMaterialList(Object2IntOpenHashMap<BlockState> total,
            Object2IntOpenHashMap<BlockState> missing, Object2IntOpenHashMap<BlockState> mismatch, Player player)
    {
        try
        {
            return (List<MaterialListEntry>) GET_MATERIAL_LIST.invoke(total, missing, mismatch,
                    new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>(), new Object2IntOpenHashMap<>(),
                    player);
        }
        catch (RuntimeException | Error e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException(t);
        }
    }
}
