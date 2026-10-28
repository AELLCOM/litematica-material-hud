package com.aell.layerlist.schematics.preview;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;

/**
 * Камера 3D-превью (M23): орбита вокруг центра габарита схемы, проекция всегда ортографическая.
 *
 * <p>Углы — в соглашении камеры Minecraft: рыскание 0 — взгляд на юг (+Z), 90 — на запад, 180 — на
 * север; тангаж больше нуля — взгляд сверху вниз. Матрица вида та же, что у ванильной камеры:
 * {@code Rx(тангаж) · Ry(рыскание + 180)}. Поэтому изометрия 135° / 35.264° показывает верх, южную грань
 * слева и восточную справа — ровно то, что ванильное затенение граней (1.0 / 0.8 / 0.6) делает «светом
 * слева», как в каноне иконок.
 *
 * <p>Сдвиг и масштаб — в пикселях GUI: масштаб = пикселей на блок, сдвиг — от центра окна превью,
 * ось Y сдвига смотрит вверх.
 */
@Environment(EnvType.CLIENT)
public final class PreviewCamera
{
    /** Тангаж изометрии: arctg(1/√2) — у куба все три видимые грани одинаково сжаты. */
    private static final float ISO_PITCH = 35.264F;

    private static final float ORBIT_DEG_PER_PX = 0.6F;
    private static final float ZOOM_STEP = 1.15F;
    private static final float ZOOM_MIN = 0.02F;
    private static final float ZOOM_MAX = 256.0F;
    private static final float FIT_MARGIN = 8.0F;
    private static final float PITCH_LIMIT = 90.0F;

    /** Предустановки камеры: кнопки «Изометрия», «Сверху», «Спереди». */
    public enum View
    {
        ISOMETRIC(135.0F, ISO_PITCH),
        /** Строго вниз, север сверху. */
        TOP(180.0F, 90.0F),
        /** Взгляд на север: видна южная грань, восток справа. */
        FRONT(180.0F, 0.0F),
        /** Сбоку: взгляд на запад, видна восточная грань, юг слева. */
        SIDE(90.0F, 0.0F);

        private final float yaw;
        private final float pitch;

        View(float yaw, float pitch)
        {
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public float yaw()
        {
            return this.yaw;
        }

        public float pitch()
        {
            return this.pitch;
        }
    }

    /**
     * Неизменяемый снимок камеры для ключа текстуры: пока он равен прошлому (и модель та же),
     * PIP только копирует готовую картинку. {@code focal > 0} — перспектива из глаза ({@code eyeX…eyeZ}, клетки
     * снимка; {@code focal} — фокусное расстояние в пикселях GUI), см. {@link EyeCamera}; иначе ортопроекция.
     */
    public record Key(float yaw, float pitch, float zoom, float panX, float panY, float eyeX, float eyeY, float eyeZ, float focal)
    {
        public boolean perspective()
        {
            return this.focal > 0.0F;
        }

        /** Вектор из осей вида (X вправо, Y вверх, Z к зрителю) в мир: транспонированный поворот вида. */
        public static float[] toWorld(float yaw, float pitch, float[] v)
        {
            double p = Math.toRadians(pitch);
            double t = Math.toRadians(yaw + 180.0F);
            float sp = (float) Math.sin(p);
            float cp = (float) Math.cos(p);
            float st = (float) Math.sin(t);
            float ct = (float) Math.cos(t);
            float[] r0 = { ct, 0.0F, st };
            float[] r1 = { sp * st, cp, -sp * ct };
            float[] r2 = { -cp * st, sp, cp * ct };
            return new float[] {
                    r0[0] * v[0] + r1[0] * v[1] + r2[0] * v[2],
                    r0[1] * v[0] + r1[1] * v[1] + r2[1] * v[2],
                    r0[2] * v[0] + r1[2] * v[1] + r2[2] * v[2] };
        }

        /** Поворот вида: мир → оси экрана (X вправо, Y вверх, Z к зрителю). */
        public Matrix4f rotation()
        {
            return new Matrix4f()
                    .rotationX((float) Math.toRadians(this.pitch))
                    .rotateY((float) Math.toRadians(this.yaw + 180.0F));
        }

        /**
         * Третья строка поворота вида: скалярное произведение с нормалью грани даёт, смотрит ли она
         * на зрителя (больше нуля) или от него.
         */
        public float[] facingRow()
        {
            double pitch = Math.toRadians(this.pitch);
            double turn = Math.toRadians(this.yaw + 180.0F);
            float sp = (float) Math.sin(pitch);
            float cp = (float) Math.cos(pitch);
            float st = (float) Math.sin(turn);
            float ct = (float) Math.cos(turn);

            return new float[] { -cp * st, sp, cp * ct };
        }
    }

    private View view = View.ISOMETRIC;
    private float yaw = View.ISOMETRIC.yaw();
    private float pitch = View.ISOMETRIC.pitch();
    private float zoom = 1.0F;
    private float panX;
    private float panY;

    /** Пока игрок не крутил колесо и не двигал вид — масштаб подгоняется под окно на каждом кадре. */
    private boolean fitted = true;
    /** Поля при вписывании, пиксели GUI; у карты сверху Zone Focus — 0: габарит ровно в окно. */
    private float margin = FIT_MARGIN;

    public View view()
    {
        return this.view;
    }

    public boolean fitted()
    {
        return this.fitted;
    }

    /** Предустановка вида; масштаб и сдвиг — заново «вписать». */
    public void reset(View v)
    {
        this.view = v;
        this.yaw = v.yaw();
        this.pitch = v.pitch();
        this.panX = 0.0F;
        this.panY = 0.0F;
        this.fitted = true;
    }

    /**
     * Строго вниз с рысканием {@code yaw} (180 — север сверху, как у {@link View#TOP}): карта сверху в повороте
     * размещения. Масштаб и сдвиг — заново «вписать»; тот же вид, уже вписанный, не трогаем.
     */
    public void top(float yaw)
    {
        float wrapped = wrapDegrees(yaw);

        if (this.view == View.TOP && this.yaw == wrapped && this.pitch == View.TOP.pitch() && this.fitted)
        {
            return;
        }

        this.reset(View.TOP);
        this.yaw = wrapped;
    }

    /**
     * Любая предустановка с заданным рысканием (карта сбоку или сверху для 2D): масштаб и сдвиг — заново
     * «вписать»; тот же вид, уже вписанный, не трогаем.
     */
    public void lock(View v, float yaw)
    {
        float wrapped = wrapDegrees(yaw);

        if (this.view == v && this.yaw == wrapped && this.pitch == v.pitch() && this.fitted)
        {
            return;
        }

        this.reset(v);
        this.yaw = wrapped;
    }

    public void setMargin(float margin)
    {
        this.margin = Math.max(0.0F, margin);
    }

    public void orbit(double dx, double dy)
    {
        this.yaw = wrapDegrees(this.yaw + (float) dx * ORBIT_DEG_PER_PX);
        this.pitch = clamp(this.pitch + (float) dy * ORBIT_DEG_PER_PX, -PITCH_LIMIT, PITCH_LIMIT);
    }

    /** Сдвиг вслед за мышью; экранный Y смотрит вниз, ось вида — вверх. */
    public void pan(double dx, double dy)
    {
        this.panX += (float) dx;
        this.panY -= (float) dy;
        this.fitted = false;
    }

    /**
     * Масштаб к курсору: точка модели под курсором остаётся под ним.
     *
     * @param amount щелчки колеса, больше нуля — ближе
     * @param fromCenterX курсор относительно центра окна превью, пиксели GUI
     * @param fromCenterY то же по вертикали (экранная ось, вниз)
     */
    public void zoomAt(double amount, double fromCenterX, double fromCenterY)
    {
        float next = clamp(this.zoom * (float) Math.pow(ZOOM_STEP, amount), ZOOM_MIN, ZOOM_MAX);
        float viewX = ((float) fromCenterX - this.panX) / this.zoom;
        float viewY = ((float) -fromCenterY - this.panY) / this.zoom;

        this.panX = (float) fromCenterX - viewX * next;
        this.panY = (float) -fromCenterY - viewY * next;
        this.zoom = next;
        this.fitted = false;
    }

    /**
     * Вписать габарит схемы в окно: проецируем 8 углов габарита текущим поворотом и берём масштаб,
     * при котором крайний угол не заходит в поля.
     */
    public void fit(int sizeX, int sizeY, int sizeZ, int width, int height)
    {
        float[] row0 = this.row(0);
        float[] row1 = this.row(1);
        float maxX = 0.0F;
        float maxY = 0.0F;

        for (int corner = 0; corner < 8; ++corner)
        {
            float x = ((corner & 1) == 0 ? -0.5F : 0.5F) * sizeX;
            float y = ((corner & 2) == 0 ? -0.5F : 0.5F) * sizeY;
            float z = ((corner & 4) == 0 ? -0.5F : 0.5F) * sizeZ;

            maxX = Math.max(maxX, Math.abs(row0[0] * x + row0[1] * y + row0[2] * z));
            maxY = Math.max(maxY, Math.abs(row1[0] * x + row1[1] * y + row1[2] * z));
        }

        float halfW = Math.max(1.0F, width * 0.5F - this.margin);
        float halfH = Math.max(1.0F, height * 0.5F - this.margin);
        float byWidth = maxX > 0.0F ? halfW / maxX : ZOOM_MAX;
        float byHeight = maxY > 0.0F ? halfH / maxY : ZOOM_MAX;

        this.zoom = clamp(Math.min(byWidth, byHeight), ZOOM_MIN, ZOOM_MAX);
        this.panX = 0.0F;
        this.panY = 0.0F;
    }

    public Key key()
    {
        return new Key(this.yaw, this.pitch, this.zoom, this.panX, this.panY, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    // Строки той же матрицы, что строит Key.rotation(): нужны для вписывания без JOML-объектов
    private float[] row(int index)
    {
        double pitch = Math.toRadians(this.pitch);
        double turn = Math.toRadians(this.yaw + 180.0F);
        float sp = (float) Math.sin(pitch);
        float cp = (float) Math.cos(pitch);
        float st = (float) Math.sin(turn);
        float ct = (float) Math.cos(turn);

        return switch (index)
        {
            case 0 -> new float[] { ct, 0.0F, st };
            case 1 -> new float[] { sp * st, cp, -sp * ct };
            default -> new float[] { -cp * st, sp, cp * ct };
        };
    }

    private static float wrapDegrees(float degrees)
    {
        float wrapped = degrees % 360.0F;

        return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }
}
