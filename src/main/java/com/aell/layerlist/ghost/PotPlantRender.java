package com.aell.layerlist.ghost;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;

/**
 * Голограмма растения над уже стоящим пустым горшком. Рисуется модель «горшок с растением» из
 * схемы, но без граней самого горшка и земли в нём: растение в ней уже уменьшено и стоит в
 * горшке по центру — так, как встанет после посадки. Отдельная модель растения для этого не
 * годится: кактус в ней — целый блок, а цветок — полноразмерный крест со случайным сдвигом, как
 * на земле. А грани горшка из схемы легли бы ровно на настоящий горшок и мерцали.
 *
 * <p>Флаг ставится на время вывода одной клетки в рабочем потоке сборки чанка, поэтому он свой
 * у каждого потока.
 */
@Environment(EnvType.CLIENT)
public final class PotPlantRender
{
    private static final Identifier POT = Identifier.withDefaultNamespace("block/flower_pot");
    private static final Identifier SOIL = Identifier.withDefaultNamespace("block/dirt");
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private PotPlantRender() {}

    public static void begin()
    {
        ++DEPTH.get()[0];
    }

    public static void end()
    {
        int[] depth = DEPTH.get();
        depth[0] = Math.max(0, depth[0] - 1);
    }

    /** Грань горшка или земли в нём — пока выводится растение над стоящим горшком. */
    public static boolean skips(BakedQuad quad)
    {
        if (DEPTH.get()[0] == 0)
        {
            return false;
        }

        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        Identifier name = sprite.contents().name();

        return POT.equals(name) || SOIL.equals(name);
    }
}
