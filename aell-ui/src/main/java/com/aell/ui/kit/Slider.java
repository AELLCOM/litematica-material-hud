package com.aell.ui.kit;

import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.function.IntConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Целочисленный ползунок в виде ползунков настроек: дорожка {@link Ui#TRACK_H}, заливка
 * {@link Ui#C_ACCENT}, бегунок {@link Ui#THUMB_W}×{@link Ui#THUMB_H}. Клик по дорожке ставит
 * значение, перетаскивание ведёт, колесо — ±1.
 */
@Environment(EnvType.CLIENT)
public final class Slider extends Widget
{
    private final IntConsumer onChange;
    private int min;
    private int max;
    private int value;
    private boolean dragging;
    private @Nullable String tooltip;

    public Slider(int min, int max, int value, IntConsumer onChange)
    {
        this.onChange = onChange;
        this.setRange(min, max, value);
    }

    public void setRange(int min, int max, int value)
    {
        this.min = min;
        this.max = Math.max(min, max);
        this.value = Ui.clamp(value, this.min, this.max);
    }

    public int value()
    {
        return this.value;
    }

    public int min()
    {
        return this.min;
    }

    public int max()
    {
        return this.max;
    }

    public Slider tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    private float fraction()
    {
        return this.max == this.min ? 1.0F : (this.value - this.min) / (float) (this.max - this.min);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= Ui.THUMB_W)
        {
            return;
        }

        boolean off = ! this.enabled;
        int ty = this.y + (this.h - Ui.TRACK_H) / 2;
        int accent = off ? Ui.alpha(Ui.C_ACCENT, Ui.A_DISABLED)
                : this.dragging ? Ui.darken(Ui.C_ACCENT, Ui.PRESS_DARKEN) : Ui.C_ACCENT;
        float fraction = this.fraction();

        if (Style.vanilla())
        {
            boolean lit = this.enabled && (this.dragging || this.contains(mouseX, mouseY));

            Vanilla.sliderTrack(ctx, this.x, this.y, this.w, this.h, lit);
            Vanilla.sliderHandleAt(ctx, this.x, this.y, this.w, this.h, fraction, lit);

            if (this.contains(mouseX, mouseY))
            {
                Tooltip.offer(this, this.tooltip);
            }

            return;
        }

        Paint.round(ctx, this.x, ty, this.w, Ui.TRACK_H, Ui.R_BAR, Ui.C_TRACK);

        int fill = Math.round(this.w * fraction);

        if (fill > 0)
        {
            Paint.round(ctx, this.x, ty, Math.max(fill, Ui.R_BAR * 2), Ui.TRACK_H, Ui.R_BAR, accent);
        }

        int thumbX = this.x + Math.round((this.w - Ui.THUMB_W) * fraction);
        Paint.round(ctx, thumbX, this.y + (this.h - Ui.THUMB_H) / 2, Ui.THUMB_W, Ui.THUMB_H, 1,
                off ? Ui.C_TEXT_OFF : Ui.C_TEXT);

        if (this.contains(mouseX, mouseY))
        {
            Tooltip.offer(this, this.tooltip);
        }
    }

    private void apply(double mouseX)
    {
        float t = (float) ((mouseX - this.x - Ui.THUMB_W / 2.0) / Math.max(1, this.w - Ui.THUMB_W));
        int next = this.min + Math.round(Ui.clamp01(t) * (this.max - this.min));
        this.set(next);
    }

    private void set(int next)
    {
        int clamped = Ui.clamp(next, this.min, this.max);

        if (clamped != this.value)
        {
            this.value = clamped;
            this.onChange.accept(clamped);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()) || ! Kit.left(click))
        {
            return false;
        }

        if (this.enabled)
        {
            this.dragging = true;
            this.apply(click.x());
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (! this.dragging)
        {
            return false;
        }

        this.apply(click.x());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        boolean was = this.dragging;
        this.dragging = false;

        if (was)
        {
            // Звук на отпускание, как у ползунка Minecraft
            Vanilla.click();
        }

        return was;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        if (! this.contains(mouseX, mouseY) || ! this.enabled || amount == 0.0D)
        {
            return false;
        }

        this.set(this.value + (int) Math.signum(amount));
        return true;
    }
}
