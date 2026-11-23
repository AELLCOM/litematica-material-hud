package com.aell.layerlist.mixin.ghost;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import fi.dy.masa.litematica.util.invoker.IEntityRendererInvoker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Мобы схемы в голограмме — только с опцией «Мобы в голограммах» (13.11.2026, по умолчанию выкл.). Litematica
 * отбирает сущности для кадра в {@code prepareEntities} своей проверкой «видна ли»; для моба при выключенной опции
 * ответ — «нет». Стойки, рамки, картины, вагонетки — не мобы и рисуются как раньше. Счёт и Easy Place сущностей
 * берут их из файла схемы, а не из кадра, — их это не касается.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = WorldRendererSchematic.class, remap = false)
public abstract class MixinWorldRendererSchematicMobs
{
    @WrapOperation(method = "prepareEntities", require = 0,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/util/invoker/IEntityRendererInvoker;"
                    + "litematica_shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean layerlist$hideMobs(IEntityRendererInvoker renderer, Entity entity, Frustum frustum,
            double x, double y, double z, Operation<Boolean> original)
    {
        if (com.aell.layerlist.ghost.GhostMobs.hide(entity))
        {
            return false;
        }

        // «Только блоки из инвентаря»: подушку, стойку, рамку без предмета с собой не рисуем — как блок
        if (com.aell.layerlist.core.SchematicEntities.hiddenByInventory(entity))
        {
            return false;
        }

        return original.call(renderer, entity, frustum, x, y, z);
    }
}
