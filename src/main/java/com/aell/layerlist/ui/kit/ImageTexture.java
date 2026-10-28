package com.aell.layerlist.ui.kit;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Картинка из массива ARGB в видеопамяти (встроенное превью Litematica в карточке и т. п.).
 * Держит одну текстуру на ключ: новый ключ — старая освобождается. Освобождать обязательно
 * ({@link #release()} при закрытии экрана): {@code TextureManager} сам не забывает
 * зарегистрированное.
 */
@Environment(EnvType.CLIENT)
public final class ImageTexture
{
    private final Identifier id;
    private @Nullable Object key;
    private boolean registered;

    /** {@code id} — своё имя в пространстве мода, например {@code layerlist:schematics/card_preview}. */
    public ImageTexture(Identifier id)
    {
        this.id = id;
    }

    /**
     * Текстура для ключа {@code key}; пиксели ({@code side}² ARGB построчно) загружаются только
     * при смене ключа. Битые данные — {@code null}.
     */
    public @Nullable Identifier get(Object key, int @Nullable [] argb, int side)
    {
        if (this.registered && Objects.equals(this.key, key))
        {
            return this.id;
        }

        this.release();

        if (argb == null || side <= 0 || argb.length != side * side)
        {
            return null;
        }

        NativeImage image = new NativeImage(side, side, false);

        for (int y = 0; y < side; ++y)
        {
            for (int x = 0; x < side; ++x)
            {
                image.setPixel(x, y, argb[y * side + x]);
            }
        }

        // Конструктор сам загружает пиксели в видеопамять — поэтому сначала заполняем картинку
        DynamicTexture texture = new DynamicTexture(this.id::toString, image);
        Minecraft.getInstance().getTextureManager().register(this.id, texture);
        this.key = key;
        this.registered = true;

        return this.id;
    }

    public void release()
    {
        if (this.registered)
        {
            Minecraft.getInstance().getTextureManager().release(this.id);
            this.registered = false;
        }

        this.key = null;
    }
}
