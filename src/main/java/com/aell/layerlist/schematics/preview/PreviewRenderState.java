package com.aell.layerlist.schematics.preview;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fc;

/**
 * Заявка на кадр превью для ванильного «картинки в картинке» (M23): прямоугольник в GUI, ножницы,
 * поза GUI и то, что рисовать, — модель и снимок камеры. Ключ текстуры — пара ({@code model.id},
 * {@code camera}): пока он прежний, {@link PreviewPipRenderer} не перерисовывает, а только копирует.
 *
 * <p>{@code scale} всегда 1: единица позы PIP — пиксель GUI, свой масштаб (пикселей на блок) — в камере.
 * {@code flipX} / {@code flipY} — отразить готовую картинку при копировании (карта сверху отражённого
 * размещения); на ключ текстуры не влияют.
 */
@Environment(EnvType.CLIENT)
public record PreviewRenderState(PreviewModel model, PreviewCamera.Key camera, int x0, int y0, int x1, int y1,
        float scale, Matrix3x2fc pose, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds,
        boolean flipX, boolean flipY)
        implements PictureInPictureRenderState
{
    public PreviewRenderState(PreviewModel model, PreviewCamera.Key camera, int x0, int y0, int x1, int y1,
            Matrix3x2fc pose, @Nullable ScreenRectangle scissorArea, boolean flipX, boolean flipY)
    {
        this(model, camera, x0, y0, x1, y1, 1.0F, pose, scissorArea, bounds(x0, y0, x1, y1, pose, scissorArea), flipX, flipY);
    }

    /** 26.2: ванильный {@code getBounds} позу не берёт — переводим углы сами, как это делает 26.3. */
    private static @Nullable ScreenRectangle bounds(int x0, int y0, int x1, int y1, Matrix3x2fc pose,
            @Nullable ScreenRectangle scissorArea)
    {
        org.joml.Vector2f a = pose.transformPosition(x0, y0, new org.joml.Vector2f());
        org.joml.Vector2f b = pose.transformPosition(x1, y1, new org.joml.Vector2f());
        int left = (int) Math.floor(Math.min(a.x, b.x));
        int top = (int) Math.floor(Math.min(a.y, b.y));
        int right = (int) Math.ceil(Math.max(a.x, b.x));
        int bottom = (int) Math.ceil(Math.max(a.y, b.y));

        return PictureInPictureRenderState.getBounds(left, top, right, bottom, scissorArea);
    }
}
