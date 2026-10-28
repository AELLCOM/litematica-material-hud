package com.aell.layerlist.schematics.preview;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Подключение 3D-превью к клиенту (M23) и общие для всех виджетов ресурсы.
 *
 * <ul>
 *   <li>{@link #register()} — рендерер «картинки в картинке» в реестре Fabric и подписка на
 *       сброс состояния рендера (F3+A, перезагрузка ресурсов, смена графики в мире). Зовётся из
 *       {@code SchematicsModule.init()}: реестр принимает рендереры только до создания
 *       {@code GameRenderer}, а {@code onInitializeClient} идёт раньше.</li>
 *   <li>Белая текстура 4×4 и свой тип рендера на ванильном конвейере непрозрачных блоков: им рисуются
 *       одноцветные кубы упрощённого вида (цвет в вершинах, текстура не вносит ничего).</li>
 *   <li>Счётчик живых виджетов (и снимков {@link PreviewCapture}): когда закрылся последний,
 *       текстуры PIP и белая текстура освобождаются.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class PreviewModule
{
    private static final Identifier FLAT_TEXTURE = Identifier.fromNamespaceAndPath("layerlist", "schematic_preview/flat");
    private static final int FLAT_TEXTURE_SIZE = 4;
    private static final int WHITE = 0xFFFFFFFF;

    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Set<PreviewPipRenderer> RENDERERS = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean registered;
    private static boolean flatTextureReady;
    private static int liveWidgets;
    private static @Nullable RenderType flatType;

    private PreviewModule() {}

    /** Один раз из {@code SchematicsModule.init()} (то есть из {@code onInitializeClient}). */
    public static void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        PictureInPictureRendererRegistry.register(context ->
        {
            PreviewPipRenderer renderer = new PreviewPipRenderer();
            RENDERERS.add(renderer);

            return renderer;
        });

        InvalidateRenderStateCallback.EVENT.register(GENERATION::incrementAndGet);

        // Снимок превью для постера выгрузки: свой рендерер в тот же реестр и точки кадра GUI
        PreviewCapture.register();
    }

    /** Растёт при каждом сбросе состояния рендера: модели превью, собранные раньше, устарели. */
    static long generation()
    {
        return GENERATION.get();
    }

    /** Виджет начал рисовать. Только клиентский поток. */
    static void retain()
    {
        ++liveWidgets;
    }

    /** Виджет закрыт; последний — освобождает текстуры. Только клиентский поток. */
    static void release()
    {
        liveWidgets = Math.max(0, liveWidgets - 1);

        if (liveWidgets > 0)
        {
            return;
        }

        for (PreviewPipRenderer renderer : RENDERERS)
        {
            renderer.releaseTexture();
        }

        if (flatTextureReady)
        {
            flatTextureReady = false;
            Minecraft.getInstance().getTextureManager().release(FLAT_TEXTURE);
        }
    }

    /**
     * Тип рендера одноцветных кубов. Белую текстуру регистрирует заранее {@link #ensureFlatTexture()}
     * (из виджета, до кадра GUI); без неё тип рисовал бы «нет текстуры» вместо цвета.
     */
    static RenderType flatType()
    {
        RenderType type = flatType;

        if (type == null)
        {
            type = RenderType.create("layerlist_schematic_preview_flat", RenderSetup.builder(RenderPipelines.SOLID_BLOCK)
                    .useLightmap()
                    .withTexture("Sampler0", FLAT_TEXTURE)
                    .createRenderSetup());
            flatType = type;
        }

        return type;
    }

    /** Только клиентский поток: создаёт и выгружает на GPU текстуру при первом упрощённом виде. */
    static void ensureFlatTexture()
    {
        if (flatTextureReady)
        {
            return;
        }

        NativeImage image = new NativeImage(FLAT_TEXTURE_SIZE, FLAT_TEXTURE_SIZE, false);
        image.fillRect(0, 0, FLAT_TEXTURE_SIZE, FLAT_TEXTURE_SIZE, WHITE);
        Minecraft.getInstance().getTextureManager().register(FLAT_TEXTURE, new DynamicTexture(FLAT_TEXTURE::toString, image));
        flatTextureReady = true;
    }
}
