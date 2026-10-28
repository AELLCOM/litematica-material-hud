package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.Conversions;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.ItemData;
import com.aell.layerlist.easyplace.GrowingPlants;
import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.easyplace.Stripping;
import fi.dy.masa.litematica.materials.MaterialCache;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Требуемый предмет для баннеров, горшков и голов — вместе с данными блока,
 * а для горшка с растением — сам горшок.
 *
 * Данные блока: Litematica строит предмет без них ({@code includeData = false})
 * и кеширует по одному BlockState, поэтому у трёх баннеров с разными узорами
 * получается один и тот же предмет без узора: в руку берётся первый подходящий
 * баннер нужного цвета, а узор выходит случайный.
 *
 * Горшок с растением: предмета у {@code potted_dandelion} нет, и штатный подбор
 * отдаёт содержимое горшка — одуванчик, который Easy Place сажает в землю вместо
 * горшка. Первым приёмом нужен пустой горшок; растение в него посадит
 * {@link com.aell.layerlist.easyplace.StateRepair} следующим кликом.
 *
 * Подменяем только внутри обработчика Easy Place ({@link AutoPlaceState#inHandler()}),
 * чтобы список материалов и остальной Litematica работали как раньше, и в обход
 * кеша по состоянию — данные зависят от позиции, а не от состояния.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = MaterialCache.class, remap = false)
public abstract class MixinMaterialCache
{
    @Inject(method = "getRequiredBuildItemForState(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist$itemForEasyPlace(BlockState state, Level world, BlockPos pos,
            CallbackInfoReturnable<ItemStack> cir)
    {
        if (! EasyPlaceModule.isActive())
        {
            return;
        }

        // Огонь: своего предмета нет — зажигаем огнивом (или огненным зарядом) по опоре рядом, см. FirePlace.
        // И вне обработчика: прицел Easy Place спрашивает предмет клетки, а без него обходил бы огонь стороной
        if (com.aell.layerlist.easyplace.FirePlace.is(state))
        {
            cir.setReturnValue(com.aell.layerlist.easyplace.FirePlace.igniter(Minecraft.getInstance().player));

            return;
        }

        if (! AutoPlaceState.inHandler())
        {
            return;
        }

        if (Potting.plant(state) != null)
        {
            cir.setReturnValue(new ItemStack(Blocks.FLOWER_POT));

            return;
        }

        // Дёрна в инвентаре нет, а тумблер разрешает землю — ставим её, дёрн нарастёт сам
        if (com.aell.layerlist.easyplace.GrassSwap.wants(state))
        {
            cir.setReturnValue(new ItemStack(Blocks.DIRT));

            return;
        }

        // Отравленный кеш: список материалов спрашивает предмет БЕЗ мира, у «тела» лозы
        // (cave_vines_plant) своего предмета нет — asItem() даёт пустоту, и она кешируется
        // по состоянию навсегда. Easy Place потом получает из кеша пустоту и гасит позицию.
        // Колонну ставит предмет её головы (светящиеся ягоды) — отдаём его, минуя кеш.
        if (GrowingPlants.is(state))
        {
            net.minecraft.world.item.Item item = GrowingPlants.item(state);

            if (item != null)
            {
                cir.setReturnValue(new ItemStack(item));

                return;
            }
        }

        // Обтёсанного бревна нет, зато есть обычное и топор — ставим обычное, обтешем потом
        LocalPlayer player = Minecraft.getInstance().player;
        ItemStack plain = player == null ? null : Stripping.substitute(player, state);

        if (plain != null)
        {
            cir.setReturnValue(plain);

            return;
        }

        // Свечного торта, грядки и тропинки в выживании не достать: ставим заготовку —
        // торт и землю, — а доведёт её инструментом StateFixer следующим кликом
        ItemStack base = player == null ? null : Conversions.substitute(player, state);

        if (base != null)
        {
            cir.setReturnValue(base);

            return;
        }

        if (world == null || ! ItemData.matters(state))
        {
            return;
        }

        ItemStack stack = ItemData.required(world, pos, state);

        if (! stack.isEmpty())
        {
            // Единственное место, где известны и клетка, и предмет с данными: отсюда и
            // говорим, если такого баннера (горшка) у игрока нет. Иначе Litematica молча
            // вернёт FAIL — предмет в руку не встанет, — и клетка останется пустой без
            // единого слова, хотя список материалов показывает её обеспеченной.
            ItemData.reportIfMissing(pos, stack);
            cir.setReturnValue(ItemData.owned(player, stack));
        }
    }

    /**
     * Список материалов (и родной Easy Place) для «тела» лозы: светящиеся ягоды, плакучая и
     * скручивающаяся лозы, ламинария. Без мира Litematica берёт {@code asItem()}, у тел
     * ({@code cave_vines_plant} и др.) это пустота — клетка выпадала из списка целиком, и на
     * колонну любой длины считался один предмет — её кончик. Подменяем везде, а не только в
     * обработчике: подмена попадает в кеш Litematica и тем самым лечит и его.
     */
    @Inject(method = "getStateToItemOverride", at = @At("RETURN"), cancellable = true, require = 0)
    private void layerlist$growingPlantBody(BlockState state, CallbackInfoReturnable<ItemStack> cir)
    {
        ItemStack override = cir.getReturnValue();

        // Невидимый свет: без мира Litematica берёт asItem() — «Свет» уровня 15 — и кеширует его для всех
        // 16 уровней; список слипал уровни в одну строку, а Easy Place ставил 15 вместо 7 (24.10.2026)
        if ((override == null || override.isEmpty()) && state.getBlock() instanceof LightBlock)
        {
            cir.setReturnValue(ItemData.lightStack(state));

            return;
        }

        if ((override == null || override.isEmpty()) && state.getBlock() instanceof GrowingPlantBodyBlock)
        {
            net.minecraft.world.item.Item item = GrowingPlants.item(state);

            if (item != null)
            {
                cir.setReturnValue(new ItemStack(item));
            }
        }
    }

    /** Лиана с N гранями — это N лиан: за клик ставится одна грань, а Litematica считала одну на клетку. */
    @Inject(method = "overrideStackSize", at = @At("TAIL"), require = 0)
    private void layerlist$vineFaces(BlockState state, ItemStack stack, CallbackInfo ci)
    {
        if (state.getBlock() instanceof VineBlock && ! stack.isEmpty())
        {
            int faces = 0;

            for (var property : VineBlock.PROPERTY_BY_DIRECTION.values())
            {
                if (state.hasProperty(property) && state.getValue(property))
                {
                    ++faces;
                }
            }

            if (faces > 1)
            {
                stack.setCount(faces);
            }
        }

        // Розовые лепестки, полевые цветы, опавшие листья: N сегментов — N предметов, за клик кладётся один.
        // Litematica считала один на клетку — список материалов и перенос из сундука недобирали
        if (state.getBlock() instanceof net.minecraft.world.level.block.SegmentableBlock segmentable && ! stack.isEmpty())
        {
            var property = segmentable.getSegmentAmountProperty();

            if (state.hasProperty(property) && state.getValue(property) > 1)
            {
                stack.setCount(state.getValue(property));
            }
        }
    }
}
