package com.aell.layerlist.api;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.preview.PreviewCamera;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.schematics.preview.ZoneOverlay;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * 3D-превью схемы с зонами — для «Управления зонами» Zone Focus (API 5). То же превью, что в библиотеке:
 * изометрия, ЛКМ — вращать, ПКМ или Shift+ЛКМ — двигать, колесо — масштаб, двойной щелчок — вернуть вид.
 * Зоны — в блоках схемы от минимального угла всех регионов ({minX, minY, minZ, maxX, maxY, maxZ}
 * включительно), номер зоны — её место в массиве плюс один.
 *
 * <p>Экран владельца зовёт {@link #render} каждый кадр и отдаёт мышь; уходя — {@link #close()} (видеопамять).
 * Щелчок без протяжки — {@link #clicked()} после {@link #mouseReleased}: зона под курсором.
 */
@Environment(EnvType.CLIENT)
public final class ZonePreview
{
    private static final double CLICK_SLOP = 3.0;

    private final PreviewWidget widget = new PreviewWidget();
    private final ZoneOverlay overlay = new ZoneOverlay();
    private @Nullable Path file;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private boolean moved;
    private int clicked = -1;
    private int hovered = -1;

    ZonePreview()
    {
        this.widget.setView(PreviewCamera.View.ISOMETRIC);
    }

    /** Файл схемы; тот же — модель не пересобирается. */
    public void setFile(@Nullable Path path)
    {
        Path next = path != null ? path.toAbsolutePath().normalize() : null;

        if (Objects.equals(next, this.file))
        {
            return;
        }

        this.file = next;
        SchematicEntry entry = next != null ? SchematicLibrary.get().entry(next) : null;

        if (entry == null || entry.directory())
        {
            this.widget.setSource(null, "none");
            return;
        }

        CompletableFuture<com.aell.layerlist.schematics.library.VoxelSnapshot> snapshot;

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
        this.widget.render(ctx, x, y, w, h, mouseX, mouseY);
        boolean inside = mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
        this.hovered = ! this.dragging && inside && this.overlay.count() <= 400
                ? this.overlay.pick(this.widget, mouseX, mouseY) : -1;
        this.overlay.setHovered(this.hovered);
        this.overlay.draw(ctx, this.widget);
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
        return handled;
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
     * Готовность «блок в блок»: модель красится по сверке с миром у размещения — построенное как есть,
     * непостроенное «призраком», чужие блоки красным; {@code null} — без неё.
     */
    public void setBuildPlacement(fi.dy.masa.litematica.schematic.placement.@Nullable SchematicPlacement placement)
    {
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

    /** Предустановки камеры — кнопки «Изометрия», «Сверху», «Спереди», как у превью библиотеки. API 5. */
    public enum View
    {
        ISOMETRIC, TOP, FRONT
    }

    /** Вид камеры; масштаб и сдвиг — заново «вписать». API 5. */
    public void setView(View view)
    {
        this.widget.setView(switch (view)
        {
            case TOP -> PreviewCamera.View.TOP;
            case FRONT -> PreviewCamera.View.FRONT;
            default -> PreviewCamera.View.ISOMETRIC;
        });
    }

    /** Последняя выбранная предустановка (подсветить кнопку). API 5. */
    public View view()
    {
        return switch (this.widget.view())
        {
            case TOP -> View.TOP;
            case FRONT -> View.FRONT;
            default -> View.ISOMETRIC;
        };
    }

    /**
     * Карта сверху для 2D «Управления зонами»: снимок схемы строго сверху в повороте и отражении размещения —
     * север сверху, как у карты зон, — без вращения, сдвига и масштаба мышью. Габарит схемы вписывается в окно
     * {@link #render} ровно, без полей: окно — прямоугольник, в который карта кладёт габарит размещения, тогда
     * клетки зон ложатся на постройку. Картинка перерисовывается, только когда меняются модель или окно. API 5.
     */
    public void setMap(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement)
    {
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

    /** Карта: не рисовать блоки выше слоя {@code topY} схемы (от её нижнего края); меньше нуля — без среза. API 5. */
    public void setMapCut(int topY)
    {
        int scale = Math.max(1, this.widget.sourceScale());
        this.widget.setLayerCut(topY < 0 ? Integer.MAX_VALUE : topY / scale);
    }

    /** Освободить видеопамять; файл и камера остаются, следующий {@link #render} соберёт модель заново. */
    public void close()
    {
        this.widget.close();
        this.dragging = false;
    }
}
