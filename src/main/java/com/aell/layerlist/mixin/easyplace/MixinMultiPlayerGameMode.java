package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.DigGuard;
import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.PlacementGuard;
import com.aell.layerlist.easyplace.StateFixer;
import com.aell.layerlist.easyplace.ToolPick;
import com.aell.layerlist.easyplace.WaterPlace;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode
{
    @Unique
    private static boolean layerlist$reentrant;

    /**
     * Ванильная установка на воду тем же правым кликом. {@code Minecraft.startUseItem}
     * зовёт {@code useItem} следом за кликом по блоку, а при промахе — сразу, и кувшинка
     * ложится туда, куда смотрит игрок, а не туда, куда просит схема.
     */
    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void layerlist$guardWaterUse(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        boolean stray;

        try
        {
            stray = WaterPlace.strayUse(player, hand);
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        if (stray)
        {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    /**
     * Номер ушедшего пакета известен только после возврата из клика — вот здесь он и
     * дописывается в запись, заведённую перед кликом. Инжект отдельный, потому что часть
     * решений конвейера ванильный клик не переписывает, а пропускает как есть.
     *
     * {@code useItem} — путь кувшинки, икры лягушки и ведра: у него в пакете свой номер
     * последовательности, и запись заводится так же.
     */
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void layerlist$stampOnBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        layerlist$stamp();

        // Клик Easy Place ушёл: ответ сервера про руку ещё в пути — обмен в руку подождёт его
        try
        {
            if (cir.getReturnValue() != null && cir.getReturnValue().consumesAction()
                    && AutoPlaceState.isAutomated(com.aell.layerlist.config.LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue()))
            {
                com.aell.layerlist.easyplace.InventorySync.onPlaceClick();
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }
    }

    /**
     * Клик по инвентарю игрока (обмен Litematica, автопополнение Inventory Profiles Next…): пока сервер его не
     * подтвердил, установки Easy Place ждут ({@code InventorySync}).
     */
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void layerlist$inventoryClick(int containerId, int slot, int button,
            net.minecraft.world.inventory.ContainerInput input, Player player, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci)
    {
        if (player != null && player.inventoryMenu != null && containerId == player.inventoryMenu.containerId)
        {
            com.aell.layerlist.easyplace.InventorySync.onInventoryClick();
        }
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void layerlist$stampOnUse(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        layerlist$stamp();
    }

    @Unique
    private static void layerlist$stamp()
    {
        try
        {
            AutoPlaceState.stampSequence();
        }
        catch (RuntimeException | LinkageError e)
        {
            // Номер — только уточнение поверх окна: не прочитался, значит работаем по окну
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void layerlist$guard(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir)
    {
        if (layerlist$reentrant)
        {
            return;
        }

        // «Защита от установки мимо схемы»: любой блок, ручной тоже, — только туда, где схема его ждёт
        try
        {
            if (com.aell.layerlist.easyplace.StrictGuard.blocks(player, hand, hit))
            {
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // Сбой проверки не должен ронять клик
        }

        PlacementGuard.Decision decision;

        try
        {
            decision = PlacementGuard.decide(player, hand, hit);
        }
        catch (RuntimeException | LinkageError e)
        {
            return;
        }

        switch (decision)
        {
            case PlacementGuard.Decision.Pass ignored ->
            {
            }
            case PlacementGuard.Decision.Cancel ignored -> cir.setReturnValue(InteractionResult.FAIL);
            case PlacementGuard.Decision.Fix fix ->
            {
                layerlist$reentrant = true;

                try
                {
                    boolean done = StateFixer.run(Minecraft.getInstance(), player, fix.pos(),
                            fix.schematic(), fix.world(), fix.face());
                    cir.setReturnValue(done ? InteractionResult.SUCCESS : InteractionResult.FAIL);
                }
                finally
                {
                    layerlist$reentrant = false;
                }
            }
            case PlacementGuard.Decision.Water water ->
            {
                layerlist$reentrant = true;

                try
                {
                    boolean done = WaterPlace.place(Minecraft.getInstance(), player, hand,
                            water.pos(), water.pose(), water.world());
                    cir.setReturnValue(done ? InteractionResult.SUCCESS : InteractionResult.FAIL);
                }
                finally
                {
                    layerlist$reentrant = false;
                }
            }
            case PlacementGuard.Decision.Click click ->
            {
                BlockHitResult target = click.hit() == null ? hit : click.hit();

                // Наблюдатель, поршень, раздатчик, бочка: сервер считает их лицо по голове
                // игрока, а голова догоняет присланный угол только в серверном тике. Такой
                // клик ждёт, пока угол проживёт на сервере тик, — иначе блок встаёт по
                // настоящему взгляду игрока.
                boolean head = click.pose() != null
                        && com.aell.layerlist.easyplace.PoseAhead.headMatters(player, hand, target, click.pose());

                // «Поза на тик вперёд»: нужного угла у сервера ещё нет, и он уедет ближайшим
                // ванильным пакетом движения. В этом тике не кликаем и клетку не метим —
                // клика не было; следующим тиком клик уйдёт без своего пакета поворота
                if (click.pose() != null
                        && com.aell.layerlist.easyplace.PoseAhead.deferring(player, click.pose(), head))
                {
                    com.aell.layerlist.easyplace.EpDebug.say(head
                            ? "жду, пока угол проживёт на сервере тик: блок встаёт по голове"
                            : "жду свой пакет движения с углом");
                    cir.setReturnValue(InteractionResult.FAIL);

                    return;
                }

                if (click.markPos() != null && click.markState() != null)
                {
                    AutoPlaceState.markClicked(click.markPos(), click.markState());
                }

                if (! click.rewrites())
                {
                    return;
                }

                MultiPlayerGameMode self = (MultiPlayerGameMode) (Object) this;
                LookSpoof.Pose pose = click.pose() == null
                        ? LookSpoof.Pose.of(player)
                        : click.pose();

                layerlist$reentrant = true;

                try
                {
                    InteractionResult result = com.aell.layerlist.easyplace.PoseAhead.applied(
                            player, pose, () -> self.useItemOn(player, hand, target));

                    com.aell.layerlist.easyplace.EpDebug.say("переписанный клик → " + result);
                    cir.setReturnValue(result);
                }
                finally
                {
                    layerlist$reentrant = false;
                }
            }
        }
    }

    /**
     * Левый клик рядом с Easy Place: «Не копать лишнего» отменяет удар по блоку вне схемы
     * и по блоку, уже стоящему как в схеме; «Брать инструмент для копания» перед началом
     * копания берёт в руку лучший инструмент. Оба помощника сами проверяют свои тумблеры.
     */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void layerlist$guardDigStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (DigGuard.blocks(pos))
            {
                cir.setReturnValue(false);

                return;
            }

            ToolPick.before(pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            // Помощники копания — надстройка: сломались — копаем как обычно
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void layerlist$guardDigContinue(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir)
    {
        layerlist$guardDig(pos, cir);
    }

    /** Мгновенная ломка в креативе идёт сюда, минуя {@code startDestroyBlock}. */
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void layerlist$guardDigInstant(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        layerlist$guardDig(pos, cir);
    }

    @Unique
    private static void layerlist$guardDig(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            if (DigGuard.blocks(pos))
            {
                cir.setReturnValue(false);
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            // см. выше
        }
    }
}
