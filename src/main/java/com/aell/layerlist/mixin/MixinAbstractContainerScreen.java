package com.aell.layerlist.mixin;

import com.aell.layerlist.storage.SlotHighlighter;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinAbstractContainerScreen
{
    @Inject(
            method = "extractContents",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;",
                    shift = At.Shift.AFTER))
    private void layerlist_highlightSearchedSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci)
    {
        SlotHighlighter.render(GuiContext.fromGuiGraphics(graphics), (AbstractContainerScreen<?>) (Object) this);
    }

    // Панель материалов у открытого контейнера — до предмета на курсоре и до подсказки предмета
    // (её Screen выводит позже, extractDeferredElements): подсказка ложится поверх панели
    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractCarriedItem(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"),
            require = 0)
    private void layerlist_hudUnderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks,
            CallbackInfo ci)
    {
        com.aell.layerlist.hud.LayerHudRenderer.renderUnderTooltip(graphics, mouseX, mouseY);
    }

    // Клавиша панели материалов (Y) в инвентаре и контейнерах — перед ванильной проверкой клавиши инвентаря:
    // поля ввода (поиск, книга рецептов) уже забрали своё нажатие, буква в поиске печатается как буква
    @Inject(method = "keyPressed",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;matches(Lnet/minecraft/client/input/KeyEvent;)Z",
                    ordinal = 0),
            cancellable = true, require = 0)
    private void layerlist_toggleHudKey(net.minecraft.client.input.KeyEvent event,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir)
    {
        if (com.aell.layerlist.input.InputHandler.toggleHudInScreen(event.key()))
        {
            cir.setReturnValue(true);
        }
    }

    // Esc / клавиша инвентаря во время переноса: окно закроется само, когда счёт сведётся с сервером
    // (иначе стопка с курсора ушла бы игроку целиком)
    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist_holdCloseForTransfer(CallbackInfo ci)
    {
        if (com.aell.layerlist.hud.TransferButton.holdClose((net.minecraft.client.gui.screens.Screen) (Object) this))
        {
            ci.cancel();
        }
    }

    // Кнопка «Перенести всё необходимое» над панелью — до слотов экрана: под ней их нет, но
    // щелчок мимо окна контейнера экран счёл бы выбросом предмета
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist_transferButton(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir)
    {
        if (com.aell.layerlist.hud.TransferButton.mouseClicked((net.minecraft.client.gui.screens.Screen) (Object) this,
                event.x(), event.y(), event.button()))
        {
            cir.setReturnValue(true);
        }
    }
}
