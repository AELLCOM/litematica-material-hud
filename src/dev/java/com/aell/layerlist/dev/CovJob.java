package com.aell.layerlist.dev;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/** Один дамп. {@link #step} вызывается порциями по времени, пока не вернёт true. */
@Environment(EnvType.CLIENT)
public interface CovJob
{
    String name();

    boolean begin(Minecraft mc);

    boolean step(Minecraft mc);

    /**
     * Делать в этом тике больше нечего (ждём срок): {@link Coverage} не крутит {@link #step} до конца
     * своей порции — иначе харнесс съедал бы по 25 мс каждого тика и мерить кадры было бы нельзя.
     */
    default boolean waiting()
    {
        return false;
    }

    void close();
}
