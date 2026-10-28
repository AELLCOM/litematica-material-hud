package com.aell.layerlist.mixin.ghost;

import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.ghost.GhostSupport;
import com.aell.layerlist.ghost.HiddenGhosts;
import com.aell.layerlist.ghost.InventoryGhosts;
import com.aell.layerlist.ghost.LeanInnerSides;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.malilib.util.LayerRange;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.chunk.VisGraph;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public abstract class MixinChunkRendererSchematicVbo
{
    @Shadow
    protected ChunkCacheSchematic schematicWorldView;

    @Shadow
    protected ChunkCacheSchematic clientWorldView;

    @Shadow
    @Final
    protected BlockPos.MutableBlockPos position;

    // Litematica 0.26 (1.21.11): блок кладётся в буфер со сдвигом PoseStack, а не offset-аргументом, и
    // использованные слои собираются в два набора — так и зовём для опор вне слоя
    @Shadow
    protected abstract void renderBlocksAndOverlay(BlockPos pos, ChunkRenderDataSchematic data,
            ChunkMeshDataSchematic chunkMeshData, ChunkRenderDispatcherBuffers pack,
            Set<?> usedLayers, Set<?> usedRenderTypes, PoseStack poseStack, VisGraph visGraph);

    // Режим «голограммы по инвентарю»: позиция, чей блок схемы не закрыт предметами игрока, не
    // мешится совсем — ни модель, ни жидкость, ни оверлей. Выше по стеку слои Litematica и обрезка
    // Zone Focus уже отобрали позиции, так что режим ложится поверх них. Рабочий поток сборки.
    @Inject(method = "renderBlocksAndOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$inventoryGhostsOnly(CallbackInfo ci, @Local(argsOnly = true) BlockPos pos)
    {
        boolean inventory = InventoryGhosts.active();
        boolean support = GhostSupport.hideUnsupported();

        if (! inventory && ! support)
        {
            return;
        }

        // Для отсечения граней о скрытых соседей (MixinBlockModelRendererSchematic)
        HiddenGhosts.building(this.clientWorldView);
        BlockState schematic = this.schematicWorldView.getBlockState(pos);
        BlockState client = this.clientWorldView.getBlockState(pos);

        if (inventory)
        {
            // Пустой горшок уже стоит — нужен только цветок, по нему и решаем
            BlockState needed = Potting.needsPlant(schematic, client) ? Potting.plantState(schematic) : schematic;

            // Блок уже стоит и доводится инструментом (костёр, лоза, тыква) — голограмму и оверлей
            // «не то состояние» показываем, даже если предмета самого блока нет
            if (InventoryGhosts.hides(needed, pos) && ! HiddenGhosts.finishableInBuild(schematic, client, pos))
            {
                ci.cancel();
                return;
            }
        }

        // «Скрывать блоки без опоры»: держаться не на чем — ни голограммы, ни оверлея
        if (support && GhostSupport.unsupported(schematic, client, pos, this.clientWorldView, this.schematicWorldView))
        {
            GhostSupport.countCulled();
            ci.cancel();
        }
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"), require = 0)
    private void layerlist$chunkBuilt(CallbackInfo ci)
    {
        HiddenGhosts.building(null);
    }

    // «Опора с соседнего слоя»: чанк, который слой не задевает вовсе (слои по X/Z), Litematica не
    // собирает — а опора в нём может лежать
    @ModifyExpressionValue(method = "rebuildChunk",
            at = @At(value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/LayerRange;intersectsBox(IIIIII)Z"),
            require = 0, allow = 1)
    private boolean layerlist$chunkHoldsSupport(boolean intersects)
    {
        return intersects || GhostSupport.cellsInChunk(this.position.getX() >> 4, this.position.getZ() >> 4).length > 0;
    }

    // Опоры вне слоя дорисовываются тем же путём, что и клетки слоя: модель, жидкость, оверлей,
    // и все фильтры выше (инвентарь, «без опоры») действуют и на них. В 0.26 disableCache зовётся
    // на каждую коробку — встаём один раз, после всех коробок (второй обход списка — по слоям)
    @Inject(method = "rebuildChunk",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 1),
            require = 0, allow = 1)
    private void layerlist$renderSupportCells(CallbackInfo ci,
            @Local(argsOnly = true) ChunkRenderDispatcherBuffers pack,
            @Local LayerRange range,
            @Local ChunkRenderDataSchematic data,
            @Local ChunkMeshDataSchematic chunkMeshData,
            @Local(ordinal = 0) Set<?> usedLayers,
            @Local(ordinal = 1) Set<?> usedRenderTypes,
            @Local PoseStack poseStack,
            @Local VisGraph visGraph)
    {
        long[] cells = GhostSupport.cellsInChunk(this.position.getX() >> 4, this.position.getZ() >> 4);

        if (cells.length == 0)
        {
            return;
        }

        int bottomY = this.position.getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockModelRendererSchematic.enableCache();

        try
        {
            for (long cell : cells)
            {
                pos.set(cell);

                if (GhostSupport.rawWithinRange(range, pos.getX(), pos.getY(), pos.getZ()))
                {
                    continue;
                }

                GhostSupport.countDrawn();
                poseStack.pushPose();
                poseStack.translate((float) (pos.getX() & 15), (float) (pos.getY() - bottomY), (float) (pos.getZ() & 15));
                this.renderBlocksAndOverlay(pos.immutable(), data, chunkMeshData, pack, usedLayers, usedRenderTypes,
                        poseStack, visGraph);
                poseStack.popPose();
            }
        }
        finally
        {
            BlockModelRendererSchematic.disableCache();
        }
    }

    // Режим «внутренние грани» отключает штатное отсечение оверлея целиком, и у свежей схемы
    // маркеры рисуются для каждой замурованной позиции. В экономном режиме Litematica при этой
    // проверке слышит «внутренние грани выключены» — и отсекает сама, своим кодом и по своей опции
    // schematicOverlayEnableCulling: позиция без единой видимой грани отсекается, граница объёма
    // остаётся видимой. Подменяется одно значение: чтение RENDER_TRANSLUCENT_INNER_SIDES внутри
    // shouldCullOverlayPos (в 0.26.16, 0.29.0 и 0.29.1 — GETSTATIC и сразу getBooleanValue).
    @ModifyExpressionValue(method = "shouldCullOverlayPos",
            at = @At(value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;getBooleanValue()Z",
                    ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lfi/dy/masa/litematica/config/Configs$Visuals;RENDER_TRANSLUCENT_INNER_SIDES:Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;")),
            require = 0, allow = 1)
    private boolean layerlist$leanOverlayCulling(boolean innerSides)
    {
        return innerSides && ! LeanInnerSides.active();
    }

    // Редстоун, рельсы и нить: заливка оверлея ложится на всю плоскость их модели сплошным цветом и прячет узор —
    // у таких клеток оверлей только контуром (FlatGhosts). Первое чтение опции в renderOverlay — «стороны оверлея».
    @ModifyExpressionValue(method = "renderOverlay",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;getBooleanValue()Z",
                    ordinal = 0),
            require = 0)
    private boolean layerlist$flatNoFill(boolean sides, @Local(argsOnly = true) BlockState state)
    {
        return sides && ! com.aell.layerlist.ghost.FlatGhosts.noFill(state);
    }
}
