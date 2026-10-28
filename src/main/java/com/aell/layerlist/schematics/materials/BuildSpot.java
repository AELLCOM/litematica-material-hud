package com.aell.layerlist.schematics.materials;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Где игрок последний раз поставил блок (клиент, 1.0.14) — «слой, на котором строил»: с него открывается окно
 * «Отключить на слое…» и по нему работает пункт «Отключить на текущем слое». Пишет {@code MixinBlockItemBuildSpot}.
 */
@Environment(EnvType.CLIENT)
public final class BuildSpot
{
    private static volatile @Nullable BlockPos last;

    private BuildSpot() {}

    public static void placed(BlockPos pos)
    {
        last = pos.immutable();
    }

    public static @Nullable BlockPos last()
    {
        return last;
    }
}
