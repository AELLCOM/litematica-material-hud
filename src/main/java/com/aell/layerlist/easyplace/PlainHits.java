package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Честные точки попадания для режима «Считать сервер ванильным».
 *
 * Протокол Easy Place кодирует нужное состояние блока в саму точку попадания:
 * к координате прибавляются смещения по +2 за свойство, и точка уезжает далеко за
 * пределы блока. Ванильный клиент так не кликает никогда, поэтому такие пакеты —
 * готовая сигнатура для античитов (Grim: FabricatedPlace; AntiLitematica ловит
 * «аномальные hit-векторы» напрямую).
 *
 * Ванильная точка попадания — это пересечение луча взгляда с гранью блока. Здесь
 * она так и строится: луч из глаз по текущему взгляду, пересечение с плоскостью
 * кликнутой грани, зажатое внутрь грани. Если луч смотрит в другую сторону —
 * центр грани, лучшее из оставшегося.
 */
@Environment(EnvType.CLIENT)
public final class PlainHits
{
    /** Насколько точка может выходить за куб блока, оставаясь «ванильной». */
    private static final double TOLERANCE = 0.01D;

    /** Отступ от рёбер грани, чтобы точка была строго на грани, а не на ребре. */
    private static final double INSET = 0.05D;

    private PlainHits() {}

    /** Точка попадания вне куба блока — след протокола Easy Place, чистим. Иначе null. */
    public static @Nullable BlockHitResult sanitize(LocalPlayer player, BlockHitResult hit)
    {
        if (! LayerListConfigs.EP_PLAIN_HITS.getBooleanValue() || withinBlock(hit))
        {
            return null;
        }

        return new BlockHitResult(
                spot(player.getEyePosition(1.0F), player.getYRot(), player.getXRot(),
                        hit.getBlockPos(), hit.getDirection()),
                hit.getDirection(), hit.getBlockPos(), hit.isInside());
    }

    private static boolean withinBlock(BlockHitResult hit)
    {
        BlockPos pos = hit.getBlockPos();
        Vec3 loc = hit.getLocation();

        return loc.x >= pos.getX() - TOLERANCE && loc.x <= pos.getX() + 1.0D + TOLERANCE
                && loc.y >= pos.getY() - TOLERANCE && loc.y <= pos.getY() + 1.0D + TOLERANCE
                && loc.z >= pos.getZ() - TOLERANCE && loc.z <= pos.getZ() + 1.0D + TOLERANCE;
    }

    /**
     * Пересечение луча взгляда с плоскостью грани, зажатое внутрь грани.
     * Когда луч параллелен грани или уходит от неё — центр грани.
     */
    public static Vec3 spot(Vec3 eyes, float yaw, float pitch, BlockPos pos, Direction face)
    {
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        double plane = axisValue(pos, face) ;
        double along = axisComponent(dir, face.getAxis());
        double from = axisComponent(eyes, face.getAxis());

        if (Math.abs(along) > 1.0E-6D)
        {
            double t = (plane - from) / along;

            if (t > 0.0D)
            {
                return clampToFace(eyes.add(dir.scale(t)), pos, face);
            }
        }

        return faceCenter(pos, face);
    }

    private static double axisValue(BlockPos pos, Direction face)
    {
        return switch (face.getAxis())
        {
            case X -> pos.getX() + (face == Direction.EAST ? 1.0D : 0.0D);
            case Y -> pos.getY() + (face == Direction.UP ? 1.0D : 0.0D);
            case Z -> pos.getZ() + (face == Direction.SOUTH ? 1.0D : 0.0D);
        };
    }

    private static double axisComponent(Vec3 vec, Direction.Axis axis)
    {
        return switch (axis)
        {
            case X -> vec.x;
            case Y -> vec.y;
            case Z -> vec.z;
        };
    }

    private static Vec3 clampToFace(Vec3 point, BlockPos pos, Direction face)
    {
        double x = face.getAxis() == Direction.Axis.X
                ? point.x
                : Mth.clamp(point.x, pos.getX() + INSET, pos.getX() + 1.0D - INSET);
        double y = face.getAxis() == Direction.Axis.Y
                ? point.y
                : Mth.clamp(point.y, pos.getY() + INSET, pos.getY() + 1.0D - INSET);
        double z = face.getAxis() == Direction.Axis.Z
                ? point.z
                : Mth.clamp(point.z, pos.getZ() + INSET, pos.getZ() + 1.0D - INSET);

        return new Vec3(x, y, z);
    }

    private static Vec3 faceCenter(BlockPos pos, Direction face)
    {
        return new Vec3(pos.getX() + 0.5D + face.getStepX() * 0.5D,
                pos.getY() + 0.5D + face.getStepY() * 0.5D,
                pos.getZ() + 0.5D + face.getStepZ() * 0.5D);
    }
}
