package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.library.VoxelSnapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Перспективная камера-облёт для превью области мира (вкладка «Выделение»): точка взгляда, углы и дистанция;
 * глаз — тело. Он не входит в блоки и воду (от точки взгляда к глазу идёт луч, глаз встаёт перед первым
 * препятствием), не покидает прогруженную область по горизонтали и высоты мира по вертикали. Точка взгляда при
 * сдвиге тоже не проходит сквозь блоки и воду.
 *
 * <p>Координаты — клетки снимка ({@link VoxelSnapshot}, 0…размер); углы — в соглашении камеры Minecraft, как у
 * {@link PreviewCamera}: рыскание 0 — взгляд на юг (+Z), тангаж больше нуля — взгляд вниз.
 */
@Environment(EnvType.CLIENT)
public final class EyeCamera
{
    public static final float FOV = 70.0F;
    /** Ближняя плоскость: квады ближе к глазу не рисуются. */
    public static final float NEAR = 0.05F;
    public static final float FAR = 2048.0F;

    private static final float ORBIT_DEG_PER_PX = 0.4F;
    private static final float PITCH_LIMIT = 89.0F;
    private static final float ZOOM_STEP = 1.15F;
    private static final float DISTANCE_MIN = 1.5F;
    private static final float DISTANCE_MAX = 600.0F;
    /** Зазор глаза до препятствия и до края области. */
    private static final float SKIN = 0.3F;
    private static final float MARCH_STEP = 0.1F;
    private static final int PICK_LIMIT = 4096;

    private float targetX;
    private float targetY;
    private float targetZ;
    private float yaw = 135.0F;
    private float pitch = 35.0F;
    private float distance = 24.0F;

    private float eyeX;
    private float eyeY;
    private float eyeZ;

    /** Высоты мира в клетках снимка: глаз не уходит выше и ниже. */
    private float worldBottom = Float.NEGATIVE_INFINITY;
    private float worldTop = Float.POSITIVE_INFINITY;
    private boolean placed;

    private @Nullable VoxelSnapshot snapshot;
    private byte[] blocking = new byte[0];

    public boolean placed()
    {
        return this.placed;
    }

    /** Поставить камеру: точка взгляда, углы, дистанция. */
    public void place(float x, float y, float z, float yaw, float pitch, float distance)
    {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.yaw = yaw;
        this.pitch = clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT);
        this.distance = clamp(distance, DISTANCE_MIN, DISTANCE_MAX);
        this.placed = true;
    }

    public void setWorldHeight(float bottom, float top)
    {
        this.worldBottom = bottom;
        this.worldTop = top;
    }

    public void orbit(double dx, double dy)
    {
        this.yaw = wrap(this.yaw + (float) dx * ORBIT_DEG_PER_PX);
        this.pitch = clamp(this.pitch + (float) dy * ORBIT_DEG_PER_PX, -PITCH_LIMIT, PITCH_LIMIT);
    }

    /** Сдвиг точки взгляда вдоль экрана; шаг растёт с дистанцией. Точка не входит в блоки и воду. */
    public void pan(double dx, double dy, int height)
    {
        float perPx = this.distance * 2.0F * (float) Math.tan(Math.toRadians(FOV / 2.0F)) / Math.max(1, height);
        double yawRad = Math.toRadians(this.yaw);
        // Вправо по экрану: перпендикуляр к взгляду в горизонтальной плоскости; вверх по экрану — по высоте
        float rightX = (float) -Math.cos(yawRad);
        float rightZ = (float) -Math.sin(yawRad);
        float mx = -(float) dx * perPx * rightX;
        float mz = -(float) dx * perPx * rightZ;
        float my = (float) dy * perPx;
        this.moveTarget(mx, 0.0F, 0.0F);
        this.moveTarget(0.0F, my, 0.0F);
        this.moveTarget(0.0F, 0.0F, mz);
    }

    public void zoom(double amount)
    {
        float factor = (float) Math.pow(ZOOM_STEP, -amount);
        this.distance = clamp(this.distance * factor, DISTANCE_MIN, DISTANCE_MAX);
    }

    /** Навести на точку (щелчок в превью): взгляд туда же, дистанция прежняя. */
    public void lookAt(float x, float y, float z)
    {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    private void moveTarget(float dx, float dy, float dz)
    {
        float nx = this.targetX + dx;
        float ny = this.targetY + dy;
        float nz = this.targetZ + dz;

        if (! this.blockedAt(nx, ny, nz) && this.insideBounds(nx, ny, nz))
        {
            this.targetX = nx;
            this.targetY = ny;
            this.targetZ = nz;
        }
    }

    /** Пересчитать глаз по снимку: луч от точки взгляда, остановка перед препятствием и краем. */
    public void update(@Nullable VoxelSnapshot s)
    {
        if (s != this.snapshot)
        {
            this.snapshot = s;
            this.blocking = new byte[s == null ? 0 : s.paletteSize()];
        }

        float[] f = forward(this.yaw, this.pitch);
        float reach = this.distance;

        for (float t = MARCH_STEP; t <= this.distance; t += MARCH_STEP)
        {
            float x = this.targetX - f[0] * t;
            float y = this.targetY - f[1] * t;
            float z = this.targetZ - f[2] * t;

            if (this.blockedAt(x, y, z) || ! this.insideBounds(x, y, z))
            {
                reach = Math.max(SKIN, t - SKIN);
                break;
            }
        }

        this.eyeX = this.targetX - f[0] * reach;
        this.eyeY = this.targetY - f[1] * reach;
        this.eyeZ = this.targetZ - f[2] * reach;
    }

    /** Ключ кадра превью: углы, глаз и фокусное расстояние в пикселях GUI для окна высотой {@code height}. */
    public PreviewCamera.Key key(int height)
    {
        return new PreviewCamera.Key(this.yaw, this.pitch, 1.0F, 0.0F, 0.0F, this.eyeX, this.eyeY, this.eyeZ, focal(height));
    }

    public static float focal(int height)
    {
        return (height * 0.5F) / (float) Math.tan(Math.toRadians(FOV / 2.0F));
    }

    /**
     * Блок под точкой окна ({@code px}, {@code py} — от центра окна, Y вниз): луч из глаза. Вода и другие жидкости
     * пропускаются — щелчок по воде выбирает дно. {@code null} — луч никуда не попал.
     */
    public int @Nullable [] pick(double px, double py, int height)
    {
        VoxelSnapshot s = this.snapshot;

        if (s == null)
        {
            return null;
        }

        float focal = focal(height);
        float[] dirView = { (float) px / focal, (float) -py / focal, -1.0F };
        float[] dir = PreviewCamera.Key.toWorld(this.yaw, this.pitch, dirView);
        return pickRay(s, this.eyeX, this.eyeY, this.eyeZ, dir[0], dir[1], dir[2]);
    }

    /** Первая непустая клетка по лучу (жидкости — насквозь); DDA по сетке снимка. */
    public static int @Nullable [] pickRay(VoxelSnapshot s, float ox, float oy, float oz, float dx, float dy, float dz)
    {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (len < 1.0E-6F)
        {
            return null;
        }

        dx /= len;
        dy /= len;
        dz /= len;

        // До входа в габарит снимка: луч мог начаться снаружи (вид сверху)
        float[] enter = enterBox(s, ox, oy, oz, dx, dy, dz);

        if (enter == null)
        {
            return null;
        }

        ox = enter[0];
        oy = enter[1];
        oz = enter[2];

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        float tMaxX = dx != 0 ? ((dx > 0 ? x + 1 - ox : ox - x) / Math.abs(dx)) : Float.POSITIVE_INFINITY;
        float tMaxY = dy != 0 ? ((dy > 0 ? y + 1 - oy : oy - y) / Math.abs(dy)) : Float.POSITIVE_INFINITY;
        float tMaxZ = dz != 0 ? ((dz > 0 ? z + 1 - oz : oz - z) / Math.abs(dz)) : Float.POSITIVE_INFINITY;
        float tDeltaX = dx != 0 ? 1.0F / Math.abs(dx) : Float.POSITIVE_INFINITY;
        float tDeltaY = dy != 0 ? 1.0F / Math.abs(dy) : Float.POSITIVE_INFINITY;
        float tDeltaZ = dz != 0 ? 1.0F / Math.abs(dz) : Float.POSITIVE_INFINITY;

        for (int i = 0; i < PICK_LIMIT; ++i)
        {
            if (x >= 0 && y >= 0 && z >= 0 && x < s.sizeX() && y < s.sizeY() && z < s.sizeZ())
            {
                BlockState state = s.get(x, y, z);

                if (! state.isAir() && ! liquid(state))
                {
                    return new int[] { x, y, z };
                }
            }
            else if (i > 0)
            {
                return null;
            }

            if (tMaxX < tMaxY && tMaxX < tMaxZ)
            {
                x += stepX;
                tMaxX += tDeltaX;
            }
            else if (tMaxY < tMaxZ)
            {
                y += stepY;
                tMaxY += tDeltaY;
            }
            else
            {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }

        return null;
    }

    private static float @Nullable [] enterBox(VoxelSnapshot s, float ox, float oy, float oz, float dx, float dy, float dz)
    {
        float tMin = 0.0F;
        float tMax = Float.POSITIVE_INFINITY;
        float[] o = { ox, oy, oz };
        float[] d = { dx, dy, dz };
        float[] size = { s.sizeX(), s.sizeY(), s.sizeZ() };

        for (int a = 0; a < 3; ++a)
        {
            if (Math.abs(d[a]) < 1.0E-9F)
            {
                if (o[a] < 0 || o[a] > size[a])
                {
                    return null;
                }

                continue;
            }

            float t1 = (0 - o[a]) / d[a];
            float t2 = (size[a] - o[a]) / d[a];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        if (tMax < tMin)
        {
            return null;
        }

        float t = tMin + 1.0E-4F;
        return new float[] { ox + dx * t, oy + dy * t, oz + dz * t };
    }

    static boolean liquid(BlockState state)
    {
        return state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN);
    }

    private boolean blockedAt(float x, float y, float z)
    {
        VoxelSnapshot s = this.snapshot;

        if (s == null)
        {
            return false;
        }

        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);

        if (cx < 0 || cy < 0 || cz < 0 || cx >= s.sizeX() || cy >= s.sizeY() || cz >= s.sizeZ())
        {
            return false;
        }

        int id = s.idAt(cx, cy, cz);

        if (id <= 0 || id >= this.blocking.length)
        {
            return false;
        }

        if (this.blocking[id] == 0)
        {
            BlockState state = s.palette(id);
            boolean solid = ! state.isAir() && (! state.getFluidState().isEmpty() || liquid(state)
                    || ! state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
            this.blocking[id] = (byte) (solid ? 2 : 1);
        }

        return this.blocking[id] == 2;
    }

    /** По горизонтали — прогруженная область, по высоте — пределы мира. */
    private boolean insideBounds(float x, float y, float z)
    {
        VoxelSnapshot s = this.snapshot;

        if (s == null)
        {
            return true;
        }

        return x >= SKIN && z >= SKIN && x <= s.sizeX() - SKIN && z <= s.sizeZ() - SKIN
                && y >= this.worldBottom + SKIN && y <= this.worldTop - SKIN;
    }

    /** Направление взгляда в мире (как у камеры Minecraft). */
    static float[] forward(float yaw, float pitch)
    {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new float[] { (float) (-Math.sin(y) * Math.cos(p)), (float) -Math.sin(p), (float) (Math.cos(y) * Math.cos(p)) };
    }

    private static float wrap(float degrees)
    {
        float wrapped = degrees % 360.0F;
        return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }
}
