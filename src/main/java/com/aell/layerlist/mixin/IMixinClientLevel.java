package com.aell.layerlist.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ClientLevel.getBlockStatePredictionHandler} пакетно-приватен, а номер
 * последовательности нужен снаружи: по нему определяется, ответил ли сервер на наш клик.
 */
@Mixin(ClientLevel.class)
public interface IMixinClientLevel
{
    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler layerlist$predictions();
}
