package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D-превью схемы как элемент экрана (M23): экран отдаёт ему прямоугольник и события мыши.
 * Кнопки мыши приходят числами SDL 26.3 — сравниваются только с {@code InputConstants.MOUSE_BUTTON_*}.
 *
 * <p>Рисует через ванильную «картинку в картинке»: каждый кадр подаёт {@link PreviewRenderState} в
 * {@code GuiContext.addSpecialElement}, а перерисовка текстуры случается только при смене модели или
 * камеры. Модель собирается в фоне ({@link PreviewMesher}); пока её нет — по центру строка статуса.
 *
 * <p>Мышь: ЛКМ — вращать, ПКМ или Shift+ЛКМ — двигать, колесо — масштаб к курсору, двойной щелчок —
 * вернуть вид кнопки. Срез по высоте перестраивает модель с задержкой: пока ползунок едет,
 * показывается прошлая.
 *
 * <p>{@link #close()} отменяет сборку и освобождает GPU; источник и камера остаются, и следующий
 * {@link #render} соберёт модель заново — так виджет переживает уход экрана в фон и возврат.
 */
@Environment(EnvType.CLIENT)
public final class PreviewWidget
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics-preview");

    private static final String KEY_EMPTY = "layerlist.schematics.preview.empty";
    private static final String KEY_LOADING = "layerlist.schematics.preview.loading";
    private static final String KEY_BUILDING = "layerlist.schematics.preview.building";
    private static final String KEY_SIMPLIFIED = "layerlist.schematics.preview.simplified";
    /** «Качественно», а модель всё равно упрощена: честным блокам не хватило памяти игры. */
    private static final String KEY_SIMPLIFIED_MEMORY = "layerlist.schematics.preview.simplified_memory";
    private static final String KEY_FAILED = "layerlist.schematics.preview.failed";
    private static final String KEY_BUILD_FAILED = "layerlist.schematics.preview.build_failed";
    private static final String KEY_NO_BLOCKS = "layerlist.schematics.preview.no_blocks";
    private static final String KEY_BELOW_CUT = "layerlist.schematics.preview.below_cut";
    private static final String KEY_HINT = "layerlist.schematics.preview.hint";

    /** Ползунок среза: пересобирать, когда он постоял столько. */
    private static final long CUT_DEBOUNCE_MS = 150L;
    private static final long DOUBLE_CLICK_MS = 300L;
    private static final double DOUBLE_CLICK_SLOP = 4.0;
    /** Меньше — PIP не заводим: текстура в пару пикселей ничего не покажет. */
    private static final int MIN_SIZE = 8;
    private static final int PROGRESS_H = 2;
    private static final long BUILD_RESCAN_MS = 2000L;
    private static final int BUILD_SCAN_PER_FRAME = 120_000;

    private final PreviewCamera camera = new PreviewCamera();

    /** Сдвиг мыши, после которого нажатие — уже вращение, а не щелчок-выбор. */
    private static final double PICK_SLOP = 3.0;

    /** Щелчок по блоку превью: клетка снимка и кнопка мыши. */
    public interface Pick
    {
        void picked(int[] cell, int button);
    }

    /** Клетка снимка под точкой окна — когда картинку рисует не виджет (вид Voxy), а значит, и луч свой. */
    public interface Picker
    {
        int @Nullable [] pick(double x, double y);
    }

    /** Перспективный облёт со столкновениями (превью области мира); {@code null} — обычная орбита. */
    private @Nullable EyeCamera eye;
    private @Nullable Pick pick;
    private @Nullable Picker picker;
    /** Картинку рисует не виджет ({@link #frame}): модель не собираем, камера и мышь — как обычно. */
    private boolean external;
    private int budgetFloor;
    /** Своё качество (вкладка «Выделение»); {@code null} — общее «Отображение схемы». */
    private SchematicsConfig.@Nullable PreviewQuality quality;
    private double pressX;
    private double pressY;
    private boolean pressMoved;

    private @Nullable VoxelSnapshot source;
    private @Nullable Object sourceKey;
    private @Nullable CompletableFuture<VoxelSnapshot> pending;
    private @Nullable String sourceError;

    private int layerCut = Integer.MAX_VALUE;
    private long cutChangedAt;

    private PreviewMesher.@Nullable Context context;
    private PreviewMesher.@Nullable Job job;
    private @Nullable PreviewModel model;
    private @Nullable String buildError;
    private int failedCut;
    private int failedBudget;

    private boolean retained;

    /** Готовность «блок в блок»: размещение, с которым сверяем, текущая сверка и её итог. */
    private fi.dy.masa.litematica.schematic.placement.@Nullable SchematicPlacement buildPlacement;
    private BuildStatus.@Nullable Scan buildScan;
    private @Nullable BuildStatus buildStatus;
    private long buildScannedAt;

    private int x;
    private int y;
    private int w;
    private int h;

    /** Только смотреть: мышь не вращает, не двигает и не масштабирует (карта сверху Zone Focus). */
    private boolean locked;
    /** Отразить готовую картинку при копировании в кадр (карта отражённого размещения). */
    private boolean flipX;
    private boolean flipY;

    private int dragButton = -1;
    private boolean dragPan;
    private boolean hasLastClick;
    private long lastClickAt;
    private double lastClickX;
    private double lastClickY;

    /**
     * @param s   снимок блоков; {@code null} — превью пусто
     * @param key ключ модели: тот же ключ и тот же снимок — та же модель, пересборки нет
     */
    public void setSource(@Nullable VoxelSnapshot s, Object key)
    {
        if (s == this.source && Objects.equals(key, this.sourceKey) && this.pending == null)
        {
            return;
        }

        this.adopt(key);
        this.source = s;
    }

    /**
     * Источник, который ещё читается (обычно {@code SchematicLibrary.snapshot(e)}): пока будущее не
     * завершилось, превью пишет «Загрузка…», при ошибке — «Не удалось прочитать: …». Аддитивно к
     * контракту §9.
     */
    public void setSourceAsync(CompletableFuture<VoxelSnapshot> snapshot, Object key)
    {
        if (snapshot == this.pending && Objects.equals(key, this.sourceKey))
        {
            return;
        }

        this.adopt(key);
        this.pending = snapshot;
    }

    /** Облёт из глаза вместо орбиты; {@code null} — снова орбита. */
    public void setEye(@Nullable EyeCamera e)
    {
        this.eye = e;
    }

    public @Nullable EyeCamera eye()
    {
        return this.eye;
    }

    /** Щелчок без сдвига мыши — выбор блока под курсором (жидкости насквозь). */
    /** Свой луч для щелчка-выбора (вид Voxy); {@code null} — по своей модели. */
    public void setPicker(@Nullable Picker p)
    {
        this.picker = p;
    }

    /**
     * Картинку рисует не виджет, а вызывающий ({@link #frame} даёт ему камеру): модель не собирается, собранная
     * отпускается. Выключили — модель соберётся снова.
     */
    public void setExternal(boolean value)
    {
        if (value && ! this.external)
        {
            this.cancelJob();
        }

        this.external = value;
    }

    public boolean external()
    {
        return this.external;
    }

    /**
     * Кадр без своей картинки ({@link #setExternal}): окно, снимок, вписывание камеры — как у {@link #render}; ключ
     * камеры — тому, кто рисует. Мышь виджет ведёт как обычно.
     */
    public PreviewCamera.Key frame(int x, int y, int w, int h)
    {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.pollSource();

        VoxelSnapshot shownSource = this.source;

        if (this.camera.fitted() && shownSource != null)
        {
            this.camera.fit(shownSource.sizeX(), shownSource.sizeY(), shownSource.sizeZ(), w, h);
        }

        return this.camera.key();
    }

    public void setPick(@Nullable Pick p)
    {
        this.pick = p;
    }

    /**
     * Не меньше стольких квадов (область «Выделения»): орбита рисуется из видеопамяти, лишние квады стоят только сборки
     * и памяти — а без них большая область огрубляется до одноцветных кубов. 0 — бюджет как у «Отображения схемы».
     */
    public void setBudgetFloor(int quads)
    {
        this.budgetFloor = Math.max(0, quads);
    }

    public void setQuality(SchematicsConfig.@Nullable PreviewQuality q)
    {
        this.quality = q;
    }

    public void setView(PreviewCamera.View v)
    {
        this.camera.reset(v);
    }

    /** Текущая предустановка камеры (подсветить кнопку вида). Аддитивно к §9. */
    public PreviewCamera.View view()
    {
        return this.camera.view();
    }

    /**
     * Карта сверху: строго вниз с рысканием {@code yaw} (180 — север сверху), габарит ровно в окно, без полей,
     * мышь не трогает камеру; {@code flipX} / {@code flipY} — отразить картинку. Аддитивно к §9.
     */
    public void setMap(float yaw, boolean flipX, boolean flipY)
    {
        this.camera.top(yaw);
        this.camera.setMargin(0.0F);
        this.locked = true;
        this.flipX = flipX;
        this.flipY = flipY;
        this.dragButton = -1;
    }

    /** Не рисовать блоки выше слоя {@code topY} (координата схемы); {@link Integer#MAX_VALUE} — без среза. */
    /**
     * Показывать готовность постройки по блокам: модель красится по сверке с миром у этого размещения
     * (непостроенное — «призраком», чужое — красным); {@code null} — без неё. Сверка идёт кусками по кадрам
     * и повторяется раз в {@link #BUILD_RESCAN_MS}; сменилась — модель пересобирается, прошлая видна до конца.
     * Только снимок 1:1 (огромная схема снимается грубой сеткой — по ней блоки не сверить).
     */
    public void setBuildPlacement(fi.dy.masa.litematica.schematic.placement.@Nullable SchematicPlacement placement)
    {
        if (placement == this.buildPlacement)
        {
            return;
        }

        this.buildPlacement = placement;
        this.buildScan = null;
        this.buildScannedAt = 0L;

        if (placement == null && this.buildStatus != null)
        {
            this.buildStatus = null;
            this.context = null;
        }
    }

    /** Построено, % (по видимым блокам); −1 — сверки нет. */
    public int builtPercent()
    {
        BuildStatus status = this.buildStatus;
        return status != null ? status.builtPercent() : -1;
    }

    private void updateBuildStatus()
    {
        VoxelSnapshot snapshot = this.source;
        var placement = this.buildPlacement;

        if (placement == null || snapshot == null || snapshot.scale() != 1)
        {
            return;
        }

        long now = Util.getMillis();

        if (this.buildScan == null)
        {
            if (now - this.buildScannedAt < BUILD_RESCAN_MS)
            {
                return;
            }

            this.buildScan = new BuildStatus.Scan(snapshot, placement);
        }

        if (! this.buildScan.step(BUILD_SCAN_PER_FRAME))
        {
            return;
        }

        BuildStatus next = this.buildScan.result();
        this.buildScan = null;
        this.buildScannedAt = now;

        if (this.buildStatus == null || this.buildStatus.signature != next.signature)
        {
            // Модель соберётся заново с новыми цветами; прошлая на экране, пока новая не готова
            this.buildStatus = next;
            this.context = null;
        }
    }

    public void setLayerCut(int topY)
    {
        if (topY != this.layerCut)
        {
            this.layerCut = topY;
            this.cutChangedAt = Util.getMillis();
        }
    }

    public int layerCut()
    {
        return this.layerCut;
    }

    public void render(GuiContext ctx, int x, int y, int w, int h, int mouseX, int mouseY)
    {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;

        if (! this.retained)
        {
            PreviewModule.retain();
            this.retained = true;
        }

        this.pollSource();
        this.updateBuildStatus();
        this.updateBuild();

        VoxelSnapshot shownSource = this.source;

        if (this.camera.fitted() && shownSource != null)
        {
            this.camera.fit(shownSource.sizeX(), shownSource.sizeY(), shownSource.sizeZ(), w, h);
        }

        PreviewModel shown = this.model;

        if (shown != null && ! shown.isEmpty() && w >= MIN_SIZE && h >= MIN_SIZE)
        {
            if (shown.flat != null)
            {
                PreviewModule.ensureFlatTexture();
            }

            EyeCamera eyeCam = this.eye;
            PreviewCamera.Key key = this.camera.key();

            if (eyeCam != null && shownSource != null)
            {
                eyeCam.update(shownSource);
                key = eyeCam.key(h);
            }

            ctx.addSpecialElement(new PreviewRenderState(shown, key, x, y, x + w, y + h,
                    new Matrix3x2f(ctx.pose()), ctx.peekLastScissor(), this.flipX, this.flipY));
        }
        else
        {
            this.drawCenteredStatus(ctx);
        }

        PreviewMesher.Job running = this.job;

        if (running != null && w > 0)
        {
            int bar = Math.round(w * running.progress());
            Paint.rect(ctx, x, y + h - PROGRESS_H, w, PROGRESS_H, Ui.C_TRACK);
            Paint.rect(ctx, x, y + h - PROGRESS_H, bar, PROGRESS_H, Theme.BLUE);
        }
    }

    public boolean mouseClicked(double x, double y, int button)
    {
        if (this.locked || ! this.inside(x, y))
        {
            return false;
        }

        this.pressX = x;
        this.pressY = y;
        this.pressMoved = false;

        // Облёт: ЛКМ — вращение, ПКМ — сдвиг; щелчок без сдвига — выбор блока
        if (this.eye != null && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT))
        {
            this.dragButton = button;
            this.dragPan = button == InputConstants.MOUSE_BUTTON_RIGHT;
            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT)
        {
            long now = Util.getMillis();

            if (this.hasLastClick && now - this.lastClickAt <= DOUBLE_CLICK_MS
                    && Math.abs(x - this.lastClickX) <= DOUBLE_CLICK_SLOP && Math.abs(y - this.lastClickY) <= DOUBLE_CLICK_SLOP)
            {
                this.hasLastClick = false;
                this.dragButton = -1;
                this.camera.reset(this.camera.view());

                return true;
            }

            this.hasLastClick = true;
            this.lastClickAt = now;
            this.lastClickX = x;
            this.lastClickY = y;
            this.dragButton = button;
            this.dragPan = Minecraft.getInstance().hasShiftDown();

            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_RIGHT)
        {
            this.dragButton = button;
            this.dragPan = true;

            return true;
        }

        return false;
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy)
    {
        if (this.dragButton < 0 || button != this.dragButton)
        {
            return false;
        }

        if (! this.pressMoved && Math.abs(x - this.pressX) + Math.abs(y - this.pressY) < PICK_SLOP)
        {
            return true;
        }

        this.pressMoved = true;
        EyeCamera eyeCam = this.eye;

        if (eyeCam != null)
        {
            if (this.dragPan)
            {
                eyeCam.pan(dx, dy, this.h);
            }
            else
            {
                eyeCam.orbit(dx, dy);
            }

            return true;
        }

        if (this.dragPan)
        {
            this.camera.pan(dx, dy);
        }
        else
        {
            this.camera.orbit(dx, dy);
        }

        return true;
    }

    public boolean mouseReleased(double x, double y, int button)
    {
        if (this.dragButton < 0 || button != this.dragButton)
        {
            return false;
        }

        this.dragButton = -1;
        Pick listener = this.pick;

        if (! this.pressMoved && listener != null)
        {
            Picker own = this.picker;
            int[] cell = own != null ? own.pick(x, y) : this.pickAt(x, y);

            if (cell != null)
            {
                listener.picked(cell, button);
            }
        }

        return true;
    }

    public boolean mouseScrolled(double x, double y, double amount)
    {
        if (this.locked || ! this.inside(x, y) || amount == 0.0)
        {
            return false;
        }

        if (this.eye != null)
        {
            this.eye.zoom(amount);
            return true;
        }

        this.camera.zoomAt(amount, x - (this.x + this.w * 0.5), y - (this.y + this.h * 0.5));

        return true;
    }

    /** Строка статуса под превью («Загрузка…», «Сборка модели 45 %»…); пусто — показывать нечего. */
    public String status()
    {
        if (this.sourceError != null)
        {
            return I18n.get(KEY_FAILED, this.sourceError);
        }

        if (this.pending != null)
        {
            return I18n.get(KEY_LOADING);
        }

        if (this.source == null)
        {
            return I18n.get(KEY_EMPTY);
        }

        PreviewMesher.Job running = this.job;

        if (running != null)
        {
            return I18n.get(KEY_BUILDING, Math.round(running.progress() * 100.0F));
        }

        if (this.buildError != null)
        {
            return I18n.get(KEY_BUILD_FAILED, this.buildError);
        }

        PreviewModel shown = this.model;

        if (shown == null)
        {
            return I18n.get(KEY_BUILDING, 0);
        }

        // Огромную схему библиотека отдаёт уже грубой сеткой (scale > 1) — это тоже упрощённый вид
        boolean coarse = this.source.scale() > 1;

        return switch (shown.empty)
        {
            case NO_BLOCKS -> I18n.get(KEY_NO_BLOCKS);
            case BELOW_CUT -> I18n.get(KEY_BELOW_CUT);
            default -> ! shown.simplified() && ! coarse ? ""
                    : I18n.get(SchematicsConfig.previewQuality() == SchematicsConfig.PreviewQuality.QUALITY
                            ? KEY_SIMPLIFIED_MEMORY : KEY_SIMPLIFIED);
        };
    }

    /** Ход сборки 0…1; −1 — ничего не собирается. Аддитивно к §9. */
    public float progress()
    {
        PreviewMesher.Job running = this.job;

        return running != null ? running.progress() : -1.0F;
    }

    /**
     * Точка модели (в клетках снимка, 0…размер) → точка экрана GUI в последнем кадре; {@code null} — снимка
     * нет. Та же цепочка, что у {@code PreviewPipRenderer.submitModel}: центр окна, сдвиг, масштаб, поворот
     * вида, центр модели. Ось Y вида смотрит вверх, экранная — вниз. Для оверлея зон ({@link ZoneOverlay}).
     */
    public float @Nullable [] project(float px, float py, float pz)
    {
        VoxelSnapshot s = this.source;

        if (s == null)
        {
            return null;
        }

        EyeCamera eyeCam = this.eye;

        if (eyeCam != null)
        {
            PreviewCamera.Key key = eyeCam.key(this.h);
            org.joml.Vector3f v = key.rotation().transformPosition(px - key.eyeX(), py - key.eyeY(), pz - key.eyeZ(),
                    new org.joml.Vector3f());

            if (v.z > -EyeCamera.NEAR)
            {
                return null;
            }

            return new float[] { this.x + this.w * 0.5F + key.focal() * v.x / -v.z,
                    this.y + this.h * 0.5F - key.focal() * v.y / -v.z, v.z };
        }

        PreviewCamera.Key key = this.camera.key();
        float[] c = this.center(s);
        org.joml.Vector3f v = key.rotation().transformPosition(px - c[0], py - c[1], pz - c[2], new org.joml.Vector3f());

        return new float[] { this.x + this.w * 0.5F + key.panX() + v.x * key.zoom(),
                this.y + this.h * 0.5F - (key.panY() + v.y * key.zoom()), v.z };
    }

    /**
     * Клетка снимка под точкой экрана: в облёте — луч из глаза, в орбите (вид сверху) — луч вдоль взгляда через
     * точку. Жидкости насквозь. {@code null} — мимо.
     */
    public int @Nullable [] pickAt(double sx, double sy)
    {
        VoxelSnapshot s = this.source;

        if (s == null)
        {
            return null;
        }

        double cx = sx - (this.x + this.w * 0.5);
        double cy = sy - (this.y + this.h * 0.5);

        if (this.eye != null)
        {
            return this.eye.pick(cx, cy, this.h);
        }

        PreviewCamera.Key key = this.camera.key();
        float vx = (float) (cx - key.panX()) / key.zoom();
        float vy = (float) (-cy - key.panY()) / key.zoom();
        float far = s.sizeX() + s.sizeY() + s.sizeZ();
        float[] o = PreviewCamera.Key.toWorld(key.yaw(), key.pitch(), new float[] { vx, vy, far });
        float[] d = PreviewCamera.Key.toWorld(key.yaw(), key.pitch(), new float[] { 0.0F, 0.0F, -1.0F });
        float[] c = this.center(s);
        return EyeCamera.pickRay(s, o[0] + c[0], o[1] + c[1], o[2] + c[2], d[0], d[1], d[2]);
    }

    /** Центр, вокруг которого рендерер ставит модель (её границы); модели нет — середина снимка. */
    private float[] center(VoxelSnapshot s)
    {
        PreviewModel m = this.model;
        return m != null ? new float[] { m.centerX, m.centerY, m.centerZ } : new float[] { s.sizeX() * 0.5F, s.sizeY() * 0.5F, s.sizeZ() * 0.5F };
    }

    /** Строка «к зрителю» поворота вида (см. {@link PreviewCamera.Key#facingRow()}). */
    public float[] facingRow()
    {
        return this.camera.key().facingRow();
    }

    /** Во сколько блоков схемы клетка снимка (огромные схемы снимаются грубой сеткой); 1 — снимка нет. */
    public int sourceScale()
    {
        VoxelSnapshot s = this.source;

        return s != null ? s.scale() : 1;
    }

    /** Модель на экране — по ней можно рисовать оверлей. */
    public boolean hasModel()
    {
        PreviewModel shown = this.model;

        return this.source != null && shown != null && ! shown.isEmpty();
    }

    /** Прямоугольник окна в последнем кадре — {x, y, w, h}. */
    public int[] bounds()
    {
        return new int[] { this.x, this.y, this.w, this.h };
    }

    /** Подсказка по мыши для всплывающей подсказки над превью. Аддитивно к §9. */
    public String hint()
    {
        return I18n.get(KEY_HINT);
    }

    /** Освободить GPU-ресурсы и отменить фоновые сборки. Источник и камера сохраняются. */
    public void close()
    {
        this.cancelJob();
        this.context = null;
        this.model = null;
        this.buildError = null;
        this.dragButton = -1;

        if (this.retained)
        {
            this.retained = false;
            PreviewModule.release();
        }
    }

    // Новый источник: всё прошлое — в сторону; камеру сбрасываем, только если сменилась схема
    private void adopt(Object key)
    {
        boolean sameSchematic = Objects.equals(key, this.sourceKey);

        this.cancelJob();
        this.context = null;
        this.model = null;
        this.buildError = null;
        this.sourceError = null;
        this.pending = null;
        this.source = null;
        this.sourceKey = key;

        if (! sameSchematic)
        {
            this.camera.reset(this.camera.view());
        }
    }

    private void pollSource()
    {
        CompletableFuture<VoxelSnapshot> future = this.pending;

        if (future == null || ! future.isDone())
        {
            return;
        }

        this.pending = null;

        try
        {
            this.source = future.join();
        }
        catch (CompletionException | CancellationException e)
        {
            this.sourceError = message(unwrap(e));
        }
    }

    private void updateBuild()
    {
        VoxelSnapshot snapshot = this.source;

        if (snapshot == null || this.external)
        {
            return;
        }

        PreviewMesher.Context current = this.context;

        // Ресурсы сменились: UV старой модели указывают в старый атлас — её больше не показываем
        if (current != null && (current.stale() || current.snapshot != snapshot))
        {
            this.cancelJob();
            this.model = null;
            current = null;
        }

        if (current == null)
        {
            // Сменилась готовность по блокам — прошлую модель держим на экране, пока новая собирается
            if (this.job != null && this.model != null)
            {
                this.cancelJob();
            }

            current = PreviewMesher.Context.capture(snapshot, this.buildStatus);
            this.context = current;
        }

        this.collectFinished();

        int budget = Math.max(this.budgetFloor,
                PreviewMesher.budget(this.quality != null ? this.quality : SchematicsConfig.previewQuality(), snapshot));
        boolean settled = Util.getMillis() - this.cutChangedAt >= CUT_DEBOUNCE_MS;
        PreviewMesher.Job running = this.job;

        if (running != null)
        {
            if (running.cut == this.layerCut && running.budget == budget || ! settled)
            {
                return;
            }

            this.cancelJob();
        }

        PreviewModel shown = this.model;

        if (shown != null && shown.cut == this.layerCut && shown.budget == budget && shown.statusSig == current.statusSig)
        {
            return;
        }

        if (this.buildError != null && this.failedCut == this.layerCut && this.failedBudget == budget)
        {
            return;
        }

        // Пока ползунок среза едет, держим прошлую модель
        if (shown != null && ! settled)
        {
            return;
        }

        this.buildError = null;
        this.job = PreviewMesher.start(current, this.layerCut, budget);
    }

    private void collectFinished()
    {
        PreviewMesher.Job done = this.job;

        if (done == null || done.future == null || ! done.future.isDone())
        {
            return;
        }

        this.job = null;

        try
        {
            this.model = done.future.join();
            this.buildError = null;
        }
        catch (CompletionException | CancellationException e)
        {
            Throwable cause = unwrap(e);

            if (! (cause instanceof PreviewMesher.Cancelled) && ! done.cancelled())
            {
                LOGGER.warn("Preview: model build failed", cause);
                this.buildError = message(cause);
                this.failedCut = done.cut;
                this.failedBudget = done.budget;
            }
        }
    }

    private void cancelJob()
    {
        PreviewMesher.Job running = this.job;

        if (running != null)
        {
            running.cancel();
            this.job = null;
        }
    }

    private void drawCenteredStatus(GuiContext ctx)
    {
        String text = this.status();

        if (text.isEmpty() || this.w <= Ui.PAD_REL * 2)
        {
            return;
        }

        Font font = Minecraft.getInstance().font;
        String fitted = Paint.fit(font, text, this.w - Ui.PAD_REL * 2);
        int left = this.x + (this.w - font.width(fitted)) / 2;
        boolean failed = this.sourceError != null || this.buildError != null;

        Paint.midText(ctx, font, fitted, left, this.y, this.h, failed ? Theme.RED : Ui.C_TEXT_2, false);
    }

    private boolean inside(double px, double py)
    {
        return px >= this.x && py >= this.y && px < this.x + this.w && py < this.y + this.h;
    }

    private static Throwable unwrap(Throwable e)
    {
        Throwable cause = e;

        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null)
        {
            cause = cause.getCause();
        }

        return cause;
    }

    private static String message(Throwable e)
    {
        String text = e.getMessage();

        return text != null && ! text.isBlank() ? text : e.getClass().getSimpleName();
    }
}
