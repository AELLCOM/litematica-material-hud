package com.aell.layerlist.mixin.schematics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Голограммы сущностей мира схемы Litematica — по одной записи за раз (1.0.18). Чанки размещений Litematica
 * перестраивает в нескольких своих потоках, а добавление сущности в мир схемы — «проверить, есть ли такой UUID, и
 * записать» — не атомарно. У двух размещений одной схемы (и у вставки) сущности с одинаковыми UUID из файла: два
 * потока одновременно видят «такой нет», оба не дают сущности новый UUID, и запись второго ({@code SchematicEntityLookup
 * .put}) убирает сущность первого. Одна голограмма пропадала насовсем — в харнессе F примерно в каждом пятом прогоне
 * после отключения или включения сущностей (21.10.2026), а у игрока — при любой перестройке, где схема стоит дважды.
 *
 * <p>Здесь все изменения набора сущностей мира схемы (добавление, выгрузка чанка, очистка) идут под одним замком —
 * друг за другом. {@code addFreshEntity} мира схемы — тот же {@code addFreshEntitySafe} (Litematica 0.26–0.29), его
 * оборачивать не нужно. Чтение для отрисовки и поиска не трогается: оно идёт по потокобезопасным картам Litematica.
 * {@code require = 0}: переименуют метод — останется прежнее поведение Litematica, без вылета.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = WorldSchematic.class, remap = false)
public abstract class MixinSchematicWorldEntities
{
    @Unique
    private static final Object LAYERLIST$ENTITIES = new Object();

    @WrapMethod(method = "addFreshEntitySafe", require = 0)
    private boolean layerlist$addFreshSafe(Entity entity, Operation<Boolean> original)
    {
        synchronized (LAYERLIST$ENTITIES)
        {
            return original.call(entity);
        }
    }

    @WrapMethod(method = "unloadEntitiesByChunk", require = 0)
    private void layerlist$unloadByChunk(int chunkX, int chunkZ, Operation<Void> original)
    {
        synchronized (LAYERLIST$ENTITIES)
        {
            original.call(chunkX, chunkZ);
        }
    }

    @WrapMethod(method = "clearEntities", require = 0)
    private void layerlist$clear(Operation<Void> original)
    {
        synchronized (LAYERLIST$ENTITIES)
        {
            original.call();
        }
    }
}
