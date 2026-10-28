package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Anim;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.function.IntConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Перетаскиваемая вертикальная граница между панелями. Положение — x линии; пределы
 * {@code min..max} задаёт владелец при каждой раскладке (они зависят от ширины окна). В покое —
 * линия {@link Ui#C_DIVIDER}; при наведении и перетаскивании — акцент и «ручка» из трёх точек,
 * курсор «↔». Захват шире линии ({@link #GRAB} px), чтобы в неё было легко попасть.
 *
 * <p>Двойной клик — {@link #onDoubleClick} (владелец сворачивает/разворачивает панель).
 */
@Environment(EnvType.CLIENT)
public final class Splitter extends Widget
{
    public static final int GRAB = Ui.PAD_REL;

    private static final int DOT = 2;

    private final IntConsumer onMove;
    private final Anim hoverAnim = new Anim(Ui.ANIM_HOVER_MS);
    private @Nullable Runnable onDoubleClick;
    private @Nullable String tooltip;
    private int position;
    private int min;
    private int max;
    private boolean dragging;
    private int grab;

    public Splitter(IntConsumer onMove)
    {
        this.onMove = onMove;
    }

    public Splitter onDoubleClick(@Nullable Runnable action)
    {
        this.onDoubleClick = action;
        return this;
    }

    public Splitter tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    /** Раскладка: линия в {@code position}, высота {@code y..y+h}, пределы перетаскивания. */
    public void place(int position, int y, int h, int min, int max)
    {
        this.position = position;
        this.min = min;
        this.max = Math.max(min, max);
        this.setBounds(position - GRAB / 2, y, GRAB, h);
    }

    public int position()
    {
        return this.position;
    }

    public boolean dragging()
    {
        return this.dragging;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.h <= 0)
        {
            return;
        }

        boolean over = this.contains(mouseX, mouseY);
        float hover = this.hoverAnim.update(over || this.dragging);

        if (Style.vanilla())
        {
            // Тонкая квадратная линия: серая, при наведении светлеет
            Paint.fill(ctx, this.position, this.y, this.position + Ui.BORDER, this.y + this.h,
                    Paint.mix(0x40FFFFFF, 0xC0FFFFFF, hover));
        }
        else
        {
            Paint.rect(ctx, this.position, this.y, Ui.BORDER, this.h, Paint.mix(Ui.C_DIVIDER, Ui.C_ACCENT, hover));
        }

        if (hover > 0.0F)
        {
            int dots = this.y + this.h / 2 - (DOT * 3 + Ui.PAD_MICRO * 2) / 2;

            for (int i = 0; i < 3; ++i)
            {
                Paint.rect(ctx, this.position - DOT / 2, dots + i * (DOT + Ui.PAD_MICRO), DOT, DOT,
                        Ui.alpha(Ui.C_TEXT_2, hover));
            }
        }

        if (over || this.dragging)
        {
            ctx.requestCursor(CursorTypes.RESIZE_EW);
        }

        if (over && ! this.dragging)
        {
            Tooltip.offer(this, this.tooltip);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()) || ! Kit.left(click))
        {
            return false;
        }

        if (doubleClick && this.onDoubleClick != null)
        {
            this.onDoubleClick.run();
            return true;
        }

        this.dragging = true;
        this.grab = (int) click.x() - this.position;
        Tooltip.reset();

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (! this.dragging)
        {
            return false;
        }

        int next = Ui.clamp((int) click.x() - this.grab, this.min, this.max);

        if (next != this.position)
        {
            this.position = next;
            this.setBounds(next - GRAB / 2, this.y, GRAB, this.h);
            this.onMove.accept(next);
        }

        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        boolean was = this.dragging;
        this.dragging = false;
        return was;
    }
}
