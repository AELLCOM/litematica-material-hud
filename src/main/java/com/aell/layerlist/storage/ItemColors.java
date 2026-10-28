package com.aell.layerlist.storage;

import com.aell.layerlist.mixin.IMixinItemStackRenderState;
import com.aell.layerlist.mixin.IMixinSpriteContents;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

@Environment(EnvType.CLIENT)
public final class ItemColors
{
    private static final Map<String, Integer> CACHE = new HashMap<>();

    private static final int ALPHA_CUTOFF = 32;
    private static final int NO_TINT = 0xFFFFFFFF;

    private static final float MIN_SATURATION = 0.30F;
    private static final float MIN_BRIGHTNESS = 0.55F;
    private static final float MAX_BRIGHTNESS = 0.98F;
    private static final float GREY_SATURATION = 0.10F;

    private ItemColors() {}

    public static void clearCache()
    {
        CACHE.clear();
    }

    public static int forStack(ItemStack stack)
    {
        String id = StorageIndex.itemId(stack);
        Integer cached = CACHE.get(id);

        if (cached != null)
        {
            return cached;
        }

        int color = compute(stack);
        CACHE.put(id, color);

        return color;
    }

    private static int compute(ItemStack stack)
    {
        int fromSprite = averageSpriteColor(stack);

        if (fromSprite != 0)
        {
            return liven(fromSprite);
        }

        try
        {
            if (stack.getItem() instanceof BlockItem blockItem)
            {
                int mapColor = blockItem.getBlock().defaultBlockState().getMapColor(null, null).col;

                if (mapColor != 0)
                {
                    return liven(0xFF000000 | mapColor);
                }
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }

        return 0;
    }

    private static int averageSpriteColor(ItemStack stack)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return 0;
        }

        NativeImage image;
        int tint;

        try
        {
            ItemStackRenderState state = new ItemStackRenderState();
            mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);

            Material.Baked baked = state.pickParticleMaterial(RandomSource.create(0L));

            if (baked == null)
            {
                return 0;
            }

            TextureAtlasSprite sprite = baked.sprite();
            image = ((IMixinSpriteContents) (Object) sprite.contents()).layerlist$originalImage();
            tint = firstTint(state);
        }
        catch (Exception | LinkageError exception)
        {
            return 0;
        }

        if (image == null)
        {
            return 0;
        }

        double sumR = 0.0;
        double sumG = 0.0;
        double sumB = 0.0;
        double sumWeight = 0.0;

        int tintR = (tint >> 16) & 0xFF;
        int tintG = (tint >> 8) & 0xFF;
        int tintB = tint & 0xFF;

        for (int y = 0; y < image.getHeight(); ++y)
        {
            for (int x = 0; x < image.getWidth(); ++x)
            {
                int pixel = image.getPixel(x, y);
                int alpha = (pixel >>> 24) & 0xFF;

                if (alpha < ALPHA_CUTOFF)
                {
                    continue;
                }

                int r = ((pixel >> 16) & 0xFF) * tintR / 0xFF;
                int g = ((pixel >> 8) & 0xFF) * tintG / 0xFF;
                int b = (pixel & 0xFF) * tintB / 0xFF;

                float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                double weight = (alpha / 255.0) * (0.25 + hsb[1]) * (0.35 + hsb[2]);

                sumR += r * weight;
                sumG += g * weight;
                sumB += b * weight;
                sumWeight += weight;
            }
        }

        if (sumWeight <= 0.0)
        {
            return 0;
        }

        return 0xFF000000
                | ((int) Math.round(sumR / sumWeight) << 16)
                | ((int) Math.round(sumG / sumWeight) << 8)
                | (int) Math.round(sumB / sumWeight);
    }

    private static int firstTint(ItemStackRenderState state)
    {
        try
        {
            IMixinItemStackRenderState access = (IMixinItemStackRenderState) (Object) state;
            ItemStackRenderState.LayerRenderState[] layers = access.layerlist$layers();
            int count = Math.min(access.layerlist$activeLayerCount(), layers == null ? 0 : layers.length);

            for (int i = 0; i < count; ++i)
            {
                IntList tints = layers[i].tintLayers();

                for (int j = 0; j < tints.size(); ++j)
                {
                    int tint = tints.getInt(j) & 0x00FFFFFF;

                    if (tint != 0 && tint != 0x00FFFFFF)
                    {
                        return 0xFF000000 | tint;
                    }
                }
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
        }

        return NO_TINT;
    }

    private static int liven(int color)
    {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        float saturation = hsb[1] < GREY_SATURATION ? hsb[1] : Math.max(hsb[1], MIN_SATURATION);
        float brightness = Math.min(MAX_BRIGHTNESS, Math.max(hsb[2], MIN_BRIGHTNESS));

        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hsb[0], saturation, brightness) & 0xFFFFFF);
    }

    public static float hue(int color)
    {
        return java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null)[0];
    }

    public static float saturation(int color)
    {
        return java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null)[1];
    }

    public static float brightness(int color)
    {
        return java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null)[2];
    }

    public static int shiftHue(int color, float shift)
    {
        float[] hsb = java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);
        float hue = (hsb[0] + shift) % 1.0F;

        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue < 0 ? hue + 1.0F : hue,
                Math.max(hsb[1], MIN_SATURATION), hsb[2]) & 0xFFFFFF);
    }

    public static int withBrightness(int color, float brightness)
    {
        float[] hsb = java.awt.Color.RGBtoHSB((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, null);

        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hsb[0], hsb[1],
                Math.min(MAX_BRIGHTNESS, Math.max(0.35F, brightness))) & 0xFFFFFF);
    }
}
