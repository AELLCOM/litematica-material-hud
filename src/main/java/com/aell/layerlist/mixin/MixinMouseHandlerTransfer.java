package com.aell.layerlist.mixin;

import com.aell.layerlist.hud.TransferButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопка «Перенести всё необходимое» ловит щелчок на входе в {@code MouseHandler}, до экрана: моды с
 * оверлеями поверх контейнера (Inventory Profiles Next и подобные) вправе забрать щелчок раньше
 * {@code Screen.mouseClicked}, и кнопка за пределами окна контейнера его бы не увидела.
 */
@Environment(EnvType.CLIENT)
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerTransfer
{
    private static final boolean DEBUG = System.getenv("LL_COVERAGE") != null;

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true, require = 0)
    private void layerlist_transferButton(long handle, MouseButtonInfo input, int action, CallbackInfo ci)
    {
        Minecraft mc = Minecraft.getInstance();

        if (DEBUG)
        {
            MouseHandler self = (MouseHandler) (Object) this;
            org.slf4j.LoggerFactory.getLogger("layerlist").info("[layerlist] onButton окно {} (наше {}), кнопка {}, действие {}, экран {}, мышь {}×{}",
                    handle, mc.getWindow().handle(), input.button(), action, mc.screen,
                    self.getScaledXPos(mc.getWindow()), self.getScaledYPos(mc.getWindow()));
        }

        // Как у самой игры: SDL отдаёт окно 0, если событие ни к какому окну не привязано, — такой
        // щелчок тоже наш (иначе он уходил мимо кнопки к экрану, где его забирают чужие оверлеи)
        if ((handle != 0L && handle != mc.getWindow().handle()) || mc.screen == null)
        {
            return;
        }

        MouseHandler self = (MouseHandler) (Object) this;

        if (TransferButton.onMouseButton(mc.screen, self.getScaledXPos(mc.getWindow()),
                self.getScaledYPos(mc.getWindow()), input.button(), action))
        {
            ci.cancel();
        }
    }
}
