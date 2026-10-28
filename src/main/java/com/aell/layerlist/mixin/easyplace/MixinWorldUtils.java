package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.EntityPlace;
import com.aell.layerlist.easyplace.SoilDig;
import com.aell.layerlist.easyplace.StateRepair;
import fi.dy.masa.litematica.util.WorldUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(value = WorldUtils.class, remap = false)
public abstract class MixinWorldUtils
{
    @Inject(method = "doEasyPlaceAction", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$enter(CallbackInfoReturnable<InteractionResult> cir)
    {
        // Модуль выключен (свой тумблер или standalone-мод рядом) — в обработчик не заходим:
        // иначе inHandler() поднимается и прицельные миксины начинают править чужую установку
        if (! EasyPlaceModule.isActive())
        {
            return;
        }

        AutoPlaceState.enter();

        boolean repaired;

        try
        {
            // Голограмма сущности под прицелом (стойка, подушка, рамка…) — раньше блоков:
            // трассировка Litematica сущностей схемы не видит вовсе
            repaired = EntityPlace.run() || StateRepair.run() || SoilDig.run();
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        if (repaired)
        {
            AutoPlaceState.exit();
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    @Inject(method = "doEasyPlaceAction", at = @At("RETURN"), require = 0)
    private static void layerlist$exit(CallbackInfoReturnable<InteractionResult> cir)
    {
        AutoPlaceState.exit();
    }

    // ---- 1.21.11 (Litematica 0.26): помощники старого пути — свои копии в WorldUtils, а не в EasyPlaceUtils ----

    @Inject(method = "easyPlaceIsPositionCached", at = @At("RETURN"), cancellable = true, require = 0)
    private static void layerlist$letCountedGrow(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (EasyPlaceModule.isActive() && Boolean.TRUE.equals(cir.getReturnValue())
                && (com.aell.layerlist.easyplace.CountedProgress.stillGrowing(pos) || StateRepair.needsFix(pos)
                        || com.aell.layerlist.easyplace.SchematicTarget.needsClick(pos)))
        {
            cir.setReturnValue(false);
        }
    }

    // Ручной клик по контейнеру — мимо «Ограничения размещения» (как MixinEasyPlaceUtils для нового пути)
    @Inject(method = "placementRestrictionInEffect(Lnet/minecraft/client/Minecraft;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$letContainerThroughMc(net.minecraft.client.Minecraft mc,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (com.aell.layerlist.easyplace.ContainerClick.shouldPass())
        {
            cir.setReturnValue(false);
        }
    }

    // Подвесная табличка: протокол Easy Place её не знает — клик идёт без него
    @Inject(method = "applyPlacementProtocolAll", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$keepHangingSigns(BlockPos pos, BlockState stateSchematic, Vec3 hitVec,
            CallbackInfoReturnable<WorldUtils.PlacementProtocolData> cir)
    {
        if (EasyPlaceModule.isActive() && stateSchematic.getBlock() instanceof CeilingHangingSignBlock)
        {
            cir.setReturnValue(new WorldUtils.PlacementProtocolData());
        }
    }

    /**
     * Счётная клетка (свечи, морские огурцы, черепашьи яйца, лепестки и опавшие листья, снег), а в руке не тот
     * предмет. Litematica спрашивает «можно ли дополнить клетку» ванильным {@code canBeReplaced} по предмету
     * В РУКЕ — ещё до того, как возьмёт нужный: стопка кончилась или в руке другой блок — ответ «нельзя», и
     * клетка навсегда остаётся недобранной (жалоба 09.10.2026: «свечи не прокликиваются до нужного
     * количества»; стенд {@code LL_COVERAGE=build} с мелкими стопками). Та же проверка, но с предметом,
     * который клетке нужен, — его Litematica и возьмёт в руку следом.
     */
    @Inject(method = "easyPlaceBlockChecksCancel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void layerlist$checkWithRequiredItem(BlockState stateSchematic, BlockState stateClient,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.HitResult trace,
            net.minecraft.world.item.ItemStack stack, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (! EasyPlaceModule.isActive() || stack.isEmpty()
                    || ! (trace instanceof net.minecraft.world.phys.BlockHitResult hit)
                    || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                    || com.aell.layerlist.easyplace.BlockFamilies.countProperty(stateSchematic) == null
                    || ! stateClient.is(stateSchematic.getBlock()))
            {
                return;
            }

            // Клетке не хватает штук, а нужный предмет Litematica возьмёт следом — дополнять можно. Грань и
            // предмет в руке не важны: клик соберёт наш конвейер (снег — кликом по верху, с любой стороны)
            var property = com.aell.layerlist.easyplace.BlockFamilies.countProperty(stateSchematic);

            if (stack.is(stateSchematic.getBlock().asItem())
                    && com.aell.layerlist.easyplace.BlockFamilies.count(stateClient, property) < com.aell.layerlist.easyplace.BlockFamilies.count(stateSchematic, property))
            {
                cir.setReturnValue(false);
                return;
            }

            net.minecraft.world.item.context.BlockPlaceContext context = new net.minecraft.world.item.context.BlockPlaceContext(
                    player, net.minecraft.world.InteractionHand.MAIN_HAND, stack, hit);
            cir.setReturnValue(! stateClient.canBeReplaced(context));
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // Сбой — пусть решает Litematica как обычно
        }
    }
}
