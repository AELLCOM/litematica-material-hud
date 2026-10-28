package com.aell.layerlist.api;

import com.aell.layerlist.compat.VoxyPreview;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicReadException;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.schematics.library.WorldSnapshotJob;
import com.aell.layerlist.schematics.preview.PreviewCamera;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.schematics.preview.ZoneOverlay;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * 3D-превью схемы с зонами — для «Управления зонами» Zone Focus (API 5, расширено в API 6). То же превью, что в
 * библиотеке: изометрия, ЛКМ — вращать, ПКМ или Shift+ЛКМ — двигать, колесо — масштаб, двойной щелчок — вернуть
 * вид.
 *
 * <p>Два источника модели. {@link #setFile} — файл схемы (API 5): зоны — в блоках схемы от минимального угла всех
 * регионов. {@link #setPlacements} — участок из одного или нескольких размещений (API 6): снимок снимается с мира
 * схем Litematica по объединённому габариту, где размещения уже сложены с поворотами и отражениями; зоны — в блоках
 * мира от минимального угла габарита ({@link #worldMin}). Во втором режиме, если стоит Voxy, под голограммой
 * рисуется живой мир той же ортографической камерой ({@link #setWorldBackground}).
 *
 * <p>Экран владельца зовёт {@link #render} каждый кадр и отдаёт мышь; уходя — {@link #close()} (видеопамять).
 * Щелчок без протяжки — {@link #clicked()} после {@link #mouseReleased}: зона под курсором.
 */
@Environment(EnvType.CLIENT)
public final class ZonePreview
{
    private static final double CLICK_SLOP = 3.0;
    /** Блоков мира схем за кадр при снятии снимка участка. */
    private static final long SNAPSHOT_BLOCKS_PER_FRAME = 1_500_000L;
    /** Камера стоит — новый кадр Voxy не чаще этого (подтянуть дочитанные LOD). */
    private static final long VOXY_IDLE_MS = 1500L;
    private static final float VOXY_NEAR = 0.05F;

    private final PreviewWidget widget = new PreviewWidget();
    private final ZoneOverlay overlay = new ZoneOverlay();
    private @Nullable Path file;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private boolean moved;
    private int clicked = -1;
    private int hovered = -1;

    // Режим участка (API 6)
    private @Nullable String placementsKey;
    private @Nullable WorldSnapshotJob job;
    private @Nullable BlockPos worldMin;
    private int @Nullable [] worldSize;
    private boolean buildRequested;
    private boolean worldBackground = true;
    private int layerCutBlocks = -1;

    // Кадр Voxy под голограммой
    private PreviewCamera.@Nullable Key voxyKey;
    private int voxyW;
    private int voxyH;
    private long voxyAt;
    private boolean voxyShown;

    ZonePreview()
    {
        this.widget.setView(PreviewCamera.View.ISOMETRIC);
    }

    /** Файл схемы; тот же — модель не пересобирается. */
    public void setFile(@Nullable Path path)
    {
        Path next = path != null ? path.toAbsolutePath().normalize() : null;

        if (Objects.equals(next, this.file) && this.placementsKey == null)
        {
            return;
        }

        this.leaveWorldMode();
        this.file = next;
        SchematicEntry entry = next != null ? SchematicLibrary.get().entry(next) : null;

        if (entry == null || entry.directory())
        {
            this.widget.setSource(null, "none");
            return;
        }

        CompletableFuture<VoxelSnapshot> snapshot;

        try
        {
            snapshot = SchematicLibrary.get().snapshot(entry);
        }
        catch (RuntimeException e)
        {
            snapshot = CompletableFuture.failedFuture(e);
        }

        this.widget.setSourceAsync(snapshot, next);
    }

    private void leaveWorldMode()
    {
        if (this.job != null)
        {
            this.job.cancel();
            this.job = null;
        }

        this.placementsKey = null;
        this.worldMin = null;
        this.worldSize = null;
        this.widget.setBuildWorld(null);
        this.voxyKey = null;
        this.voxyShown = false;
    }

    /**
     * Участок: одно или несколько размещений. Снимок — с мира схем по объединённому габариту их подрегионов,
     * читается кусками по кадрам; пока не готов, показывается прошлая модель. Тот же состав и положение — без
     * пересборки. Пустой список — превью пусто. API 6.
     */
    public void setPlacements(List<SchematicPlacement> placements)
    {
        int[] union = null;
        StringBuilder key = new StringBuilder("world|").append(SchematicLibrary.previewCells()).append('|');

        for (SchematicPlacement placement : placements)
        {
            key.append(placement.getHashId()).append('@').append(placement.getOrigin()).append(placement.getRotation())
                    .append(placement.getMirror()).append(';');

            for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values())
            {
                BlockPos a = box.getPos1();
                BlockPos b = box.getPos2();

                if (a == null || b == null)
                {
                    continue;
                }

                int[] current = { Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()) };
                key.append(current[0]).append(',').append(current[1]).append(',').append(current[2]).append('/')
                        .append(current[3]).append(',').append(current[4]).append(',').append(current[5]).append(';');

                if (union == null)
                {
                    union = current;
                }
                else
                {
                    for (int i = 0; i < 3; ++i)
                    {
                        union[i] = Math.min(union[i], current[i]);
                        union[i + 3] = Math.max(union[i + 3], current[i + 3]);
                    }
                }
            }
        }

        String next = key.toString();

        if (next.equals(this.placementsKey))
        {
            return;
        }

        if (this.job != null)
        {
            this.job.cancel();
            this.job = null;
        }

        this.file = null;
        this.placementsKey = next;
        this.voxyKey = null;

        if (union == null)
        {
            this.worldMin = null;
            this.worldSize = null;
            this.widget.setBuildWorld(null);
            this.widget.setSource(null, next);
            return;
        }

        this.worldMin = new BlockPos(union[0], union[1], union[2]);
        this.worldSize = new int[] { union[3] - union[0] + 1, union[4] - union[1] + 1, union[5] - union[2] + 1 };

        try
        {
            this.job = new WorldSnapshotJob(union[0], union[1], union[2], union[3], union[4], union[5],
                    SchematicLibrary.previewCells());
        }
        catch (SchematicReadException e)
        {
            this.job = null;
            this.widget.setSource(null, next);
        }
    }

    /** Минимальный угол габарита участка в мире — начало координат зон и {@link #project} в режиме участка; {@code null} — режим файла. API 6. */
    public @Nullable BlockPos worldMin()
    {
        return this.worldMin;
    }

    /** Габарит модели в блоках {sizeX, sizeY, sizeZ}; {@code null} — модели нет. API 6. */
    public int @Nullable [] size()
    {
        if (this.worldSize != null)
        {
            return this.worldSize.clone();
        }

        VoxelSnapshot s = this.widget.source();

        if (s == null)
        {
            return null;
        }

        int scale = Math.max(1, s.scale());
        return new int[] { s.sizeX() * scale, s.sizeY() * scale, s.sizeZ() * scale };
    }

    /** Блоков на клетку снимка (огромные схемы снимаются грубой сеткой); 1 — снимка нет. API 6. */
    public int scale()
    {
        return Math.max(1, this.widget.sourceScale());
    }

    /** Модель на экране. API 6. */
    public boolean hasModel()
    {
        return this.widget.hasModel();
    }

    /** Снятие снимка участка, 0…1; снимок готов или не снимается — 1. API 6. */
    public float snapshotProgress()
    {
        WorldSnapshotJob current = this.job;
        return current != null ? current.progress() : 1.0F;
    }

    /**
     * @param boxes  зоны по номерам; {@code null} — зон нет
     * @param active активная (с 0) или −1
     * @param tones  цвет каждой зоны (прогресс), 0 — обычный; {@code null} — все обычные
     * @param draft  черновик разбиения — синим
     */
    public void setZones(int @Nullable [][] boxes, int active, int @Nullable [] tones, boolean draft)
    {
        this.overlay.set(boxes, active, tones, draft);
    }

    /**
     * Зоны с прогрессом постройки: {@code percents} — готовность каждой зоны (−1 — не посчитана); каркас
     * красится по ней (зелёный, жёлтый, красный), у номера — процент.
     */
    public void setZones(int @Nullable [][] boxes, int active, int @Nullable [] percents)
    {
        int[] tones = null;

        if (percents != null)
        {
            tones = new int[percents.length];

            for (int i = 0; i < percents.length; ++i)
            {
                tones[i] = ZoneOverlay.toneOf(percents[i]);
            }
        }

        this.overlay.set(boxes, active, tones, percents, false);
    }

    public void render(GuiContext ctx, int x, int y, int w, int h, int mouseX, int mouseY)
    {
        this.pollJob();
        boolean background = this.renderWorldBackground(ctx, x, y, w, h);
        this.widget.render(ctx, x, y, w, h, mouseX, mouseY);
        this.voxyShown = background;
        boolean inside = mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
        this.hovered = ! this.dragging && inside && this.overlay.count() <= 400
                ? this.overlay.pick(this.widget, mouseX, mouseY) : -1;
        this.overlay.setHovered(this.hovered);
        this.overlay.draw(ctx, this.widget);
    }

    private void pollJob()
    {
        WorldSnapshotJob current = this.job;

        if (current == null || ! current.step(SNAPSHOT_BLOCKS_PER_FRAME))
        {
            return;
        }

        this.job = null;
        VoxelSnapshot snapshot = current.result();
        this.widget.setSource(snapshot, this.placementsKey);
        this.widget.setBuildWorld(this.buildRequested && this.worldMin != null ? this.worldMin : null);

        if (this.layerCutBlocks >= 0)
        {
            this.setLayerCut(this.layerCutBlocks);
        }
    }

    /**
     * Мир Voxy под голограммой: та же ортографическая камера, что у модели (поворот, масштаб, сдвиг), глаз — за
     * габаритом участка. Кадр перерисовывается при движении камеры и раз в {@link #VOXY_IDLE_MS} в покое.
     */
    private boolean renderWorldBackground(GuiContext ctx, int x, int y, int w, int h)
    {
        BlockPos min = this.worldMin;
        VoxelSnapshot source = this.widget.source();

        if (! this.worldBackground || min == null || source == null || w < 8 || h < 8 || ! VoxyPreview.ready())
        {
            return false;
        }

        PreviewCamera.Key key = this.widget.frame(x, y, w, h);
        float[] center = this.widget.modelCenter();

        if (center == null || key.zoom() <= 0.0F)
        {
            return false;
        }

        long now = Util.getMillis();
        boolean fresh = key.equals(this.voxyKey) && w == this.voxyW && h == this.voxyH && now - this.voxyAt < VOXY_IDLE_MS;

        if (! fresh || VoxyPreview.color() == null)
        {
            double pitch = Math.toRadians(key.pitch());
            double turn = Math.toRadians(key.yaw() + 180.0F);
            double sp = Math.sin(pitch);
            double cp = Math.cos(pitch);
            double st = Math.sin(turn);
            double ct = Math.cos(turn);
            double[] right = { ct, 0.0, st };
            double[] up = { sp * st, cp, -sp * ct };
            double[] back = { -cp * st, sp, cp * ct };
            double scale = Math.max(1, source.scale());
            double zoom = key.zoom();
            // Точка модели в центре окна: центр модели минус сдвиг вида (сдвиг — пиксели GUI, ось Y вверх)
            double shiftX = key.panX() / zoom * scale;
            double shiftY = key.panY() / zoom * scale;
            double tx = min.getX() + center[0] * scale - right[0] * shiftX - up[0] * shiftY;
            double ty = min.getY() + center[1] * scale - right[1] * shiftX - up[1] * shiftY;
            double tz = min.getZ() + center[2] * scale - right[2] * shiftX - up[2] * shiftY;
            double sx = source.sizeX() * scale;
            double sy = source.sizeY() * scale;
            double sz = source.sizeZ() * scale;
            double radius = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);
            double dist = radius + 32.0;
            float halfW = (float) (w * 0.5 / zoom * scale);
            float halfH = (float) (h * 0.5 / zoom * scale);
            float far = (float) (dist * 2.0 + radius + 512.0);
            int guiScale = (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));

            if (! VoxyPreview.renderOrtho(tx + back[0] * dist, ty + back[1] * dist, tz + back[2] * dist,
                    key.yaw(), key.pitch(), halfW, halfH, VOXY_NEAR, far, w * guiScale, h * guiScale))
            {
                return false;
            }

            this.voxyKey = key;
            this.voxyW = w;
            this.voxyH = h;
            this.voxyAt = now;
        }

        GpuTextureView color = VoxyPreview.color();

        if (color == null)
        {
            return false;
        }

        ctx.blit(color, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR), x, y, x + w, y + h, 0.0F, 1.0F, 1.0F, 0.0F);
        return true;
    }

    /** Мир Voxy под голограммой в режиме участка (по умолчанию включён; без Voxy ничего не меняет). API 6. */
    public void setWorldBackground(boolean on)
    {
        this.worldBackground = on;
    }

    /** В последнем кадре под голограммой был мир Voxy. API 6. */
    public boolean worldBackgroundShown()
    {
        return this.voxyShown;
    }

    /** Зона под курсором в последнем кадре или −1. */
    public int hovered()
    {
        return this.hovered;
    }

    /** Зона под точкой или −1. */
    public int pick(double x, double y)
    {
        return this.overlay.pick(this.widget, x, y);
    }

    /**
     * Точка модели в блоках (от начала координат зон: угол схемы или {@link #worldMin}) → точка экрана GUI
     * {@code {x, y, глубина}} в последнем кадре; {@code null} — модели нет. Для рисования и перетаскивания плоскостей
     * разреза владельцем. API 6.
     */
    public float @Nullable [] project(float bx, float by, float bz)
    {
        float scale = this.scale();
        return this.widget.project(bx / scale, by / scale, bz / scale);
    }

    public boolean mouseClicked(double x, double y, int button)
    {
        if (this.widget.mouseClicked(x, y, button))
        {
            this.dragging = true;
            this.pressX = x;
            this.pressY = y;
            this.moved = false;
            this.clicked = -1;
            return true;
        }

        return false;
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy)
    {
        if (this.dragging && Math.abs(x - this.pressX) + Math.abs(y - this.pressY) > CLICK_SLOP)
        {
            this.moved = true;
        }

        return this.dragging && this.widget.mouseDragged(x, y, button, dx, dy);
    }

    public boolean mouseReleased(double x, double y, int button)
    {
        if (! this.dragging)
        {
            return false;
        }

        this.dragging = false;
        boolean handled = this.widget.mouseReleased(x, y, button);
        this.clicked = ! this.moved && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                ? this.overlay.pick(this.widget, x, y) : -1;
        this.rightPick = ! this.moved && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT
                ? this.pickBlock(x, y) : null;
        return handled;
    }

    private int @Nullable [] rightPick;

    /** Блок модели под точкой экрана {x, y, z} в блоках от начала координат зон; мимо — {@code null}. API 6. */
    public int @Nullable [] pickBlock(double x, double y)
    {
        int[] cell = this.widget.pickAt(x, y);

        if (cell == null)
        {
            return null;
        }

        int scale = this.scale();
        return new int[] { cell[0] * scale, cell[1] * scale, cell[2] * scale };
    }

    /** Блок под правым щелчком без протяжки при последнем отпускании или {@code null}; читается один раз. API 6. */
    public int @Nullable [] rightClicked()
    {
        int[] block = this.rightPick;
        this.rightPick = null;
        return block;
    }

    /** Зона, по которой щёлкнули без протяжки при последнем отпускании, или −1; читается один раз. */
    public int clicked()
    {
        int zone = this.clicked;
        this.clicked = -1;
        return zone;
    }

    public boolean mouseScrolled(double x, double y, double amount)
    {
        return this.widget.mouseScrolled(x, y, amount);
    }

    /**
     * Готовность «блок в блок»: модель красится по сверке с миром — построенное как есть, непостроенное
     * «призраком», чужие блоки красным; {@code null} — без неё. В режиме участка сверяется снимок с мира, а
     * размещение служит только признаком «включить».
     */
    public void setBuildPlacement(@Nullable SchematicPlacement placement)
    {
        this.buildRequested = placement != null;

        if (this.placementsKey != null)
        {
            this.widget.setBuildPlacement(null);
            this.widget.setBuildWorld(this.buildRequested && this.worldMin != null && this.job == null ? this.worldMin : null);
            return;
        }

        this.widget.setBuildPlacement(placement);
    }

    /** Построено, % по видимым блокам; −1 — сверки нет. */
    public int builtPercent()
    {
        return this.widget.builtPercent();
    }

    /** «Загрузка…», «Сборка модели 45 %», ошибка; пусто — модель на экране. */
    public String status()
    {
        return this.widget.status();
    }

    /** Вернуть изометрию (кнопка «Вид»). */
    public void resetView()
    {
        this.widget.setView(PreviewCamera.View.ISOMETRIC);
    }

    /** Предустановки камеры — кнопки «Изометрия», «Сверху», «Спереди», «Сбоку» (API 6), как у превью библиотеки. */
    public enum View
    {
        ISOMETRIC, TOP, FRONT, SIDE
    }

    private static PreviewCamera.View toCamera(View view)
    {
        return switch (view)
        {
            case TOP -> PreviewCamera.View.TOP;
            case FRONT -> PreviewCamera.View.FRONT;
            case SIDE -> PreviewCamera.View.SIDE;
            default -> PreviewCamera.View.ISOMETRIC;
        };
    }

    /** Вид камеры; масштаб и сдвиг — заново «вписать». API 5. */
    public void setView(View view)
    {
        this.widget.setView(toCamera(view));
    }

    /** Последняя выбранная предустановка (подсветить кнопку). API 5. */
    public View view()
    {
        return switch (this.widget.view())
        {
            case TOP -> View.TOP;
            case FRONT -> View.FRONT;
            case SIDE -> View.SIDE;
            default -> View.ISOMETRIC;
        };
    }

    /**
     * Карта сверху для 2D «Управления зонами»: снимок схемы строго сверху в повороте и отражении размещения —
     * север сверху, как у карты зон, — без вращения, сдвига и масштаба мышью. Габарит схемы вписывается в окно
     * {@link #render} ровно, без полей: окно — прямоугольник, в который карта кладёт габарит размещения, тогда
     * клетки зон ложатся на постройку. Картинка перерисовывается, только когда меняются модель или окно. API 5.
     * В режиме участка ({@link #setPlacements}) снимок уже в осях мира — поворот и отражение не нужны.
     */
    public void setMap(SchematicPlacement placement)
    {
        if (this.placementsKey != null)
        {
            this.setMapView(View.TOP);
            return;
        }

        int turns = switch (placement.getRotation())
        {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        // Litematica отражает схему до поворота: после поворота это отражение по той оси мира, в которую ушла ось
        // схемы (при четверти оборота X и Z меняются местами). Экран: X — восток, Y — юг
        boolean flipFileZ = placement.getMirror() == net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
        boolean flipFileX = placement.getMirror() == net.minecraft.world.level.block.Mirror.FRONT_BACK;
        boolean quarter = (turns & 1) == 1;
        // Рыскание: верх кадра — та сторона схемы, что в мире смотрит на север
        this.widget.setMap(180.0F - 90.0F * turns, quarter ? flipFileZ : flipFileX, quarter ? flipFileX : flipFileZ);
    }

    /**
     * Карта без мыши любой предустановкой в осях мира (режим участка): {@link View#TOP} — сверху, север вверху;
     * {@link View#FRONT} — спереди (X вправо, Y вверх); {@link View#SIDE} — сбоку (Z, Y). Габарит ровно в окно. API 6.
     */
    public void setMapView(View view)
    {
        PreviewCamera.View camera = toCamera(view);
        this.widget.setMapView(camera, camera.yaw(), false, false);
        // Карта — только сама постройка: мир Voxy не нужен, а его кадр один на все превью
        this.worldBackground = false;
    }

    /** Карта: не рисовать блоки выше слоя {@code topY} схемы (от её нижнего края); меньше нуля — без среза. API 5. */
    public void setMapCut(int topY)
    {
        this.setLayerCut(topY);
    }

    /** Срез по высоте для любого вида: блоки выше слоя {@code topY} (от нижнего края) не рисуются; меньше нуля — без среза. API 6. */
    public void setLayerCut(int topY)
    {
        this.layerCutBlocks = topY;
        int scale = this.scale();
        this.widget.setLayerCut(topY < 0 ? Integer.MAX_VALUE : topY / scale);
    }

    /** Освободить видеопамять; источник и камера остаются, следующий {@link #render} соберёт модель заново. */
    public void close()
    {
        this.widget.close();
        this.dragging = false;
        this.voxyKey = null;

        if (this.voxyShown)
        {
            VoxyPreview.release();
            this.voxyShown = false;
        }
    }
}
