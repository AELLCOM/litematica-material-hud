package com.aell.layerlist.mixin;

import com.aell.layerlist.shaded.ui.LitematicaSlots;
import com.aell.layerlist.shaded.ui.Strings;
import fi.dy.masa.litematica.gui.GuiRenderLayer;
import fi.dy.masa.malilib.gui.GuiBase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(GuiRenderLayer.class)
public abstract class MixinLitematicaRenderLayerScreen
{
    private static final String[] SLOT_ORDER = { "layerlist", "zonefocus", "ghostalpha", "easyplacefixplus" };

    @Inject(method = "initGui", at = @At("TAIL"))
    private void layerlist$addModButton(CallbackInfo ci)
    {
        GuiBase screen = (GuiBase) (Object) this;

        screen.addButton(LitematicaSlots.addonTabButton(screen,
                Strings.plain("layerlist.gui.litematica_button"),
                com.aell.layerlist.config.LayerListSettings.ICON,
                LitematicaSlots.hasAllTab(screen), SLOT_ORDER, "layerlist"),
                (b, mouseButton) -> com.aell.layerlist.config.GuiConfigs.open(screen,
                        "layerlist.gui.parent.litematica"));
    }
}
