package com.aell.layerlist.mixin.schematics;

import com.aell.layerlist.schematics.materials.BlockReplacements;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Замены блоков «Материалов» (0.0.27) — на каждую схему, прочитанную с диска. Все чтения файла
 * Litematica сходятся в {@code LitematicaSchematic.readFromFile(FileType)}: «Загрузить» её браузера,
 * размещения при входе в мир и смене измерения ({@code SchematicHolder.getOrLoad} →
 * {@code createFromFile}), конвертации .schem/.nbt и наша загрузка. Замены ложатся до того, как схему
 * увидит размещение, поэтому голограммы сразу строятся с новыми блоками.
 *
 * <p>{@code require = 0}: переименуют метод — замены всё равно наложит страховочный опрос
 * {@link BlockReplacements} (раз в секунду по схемам размещений), только на секунду позже.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = LitematicaSchematic.class, remap = false)
public abstract class MixinSchematicReplacements
{
    @Inject(method = "readFromFile(Lfi/dy/masa/litematica/util/FileType;)Z", at = @At("RETURN"), require = 0)
    private void layerlist$applyBlockReplacements(FileType type, CallbackInfoReturnable<Boolean> cir)
    {
        if (cir.getReturnValueZ())
        {
            BlockReplacements.onRead((LitematicaSchematic) (Object) this);
        }
    }
}
