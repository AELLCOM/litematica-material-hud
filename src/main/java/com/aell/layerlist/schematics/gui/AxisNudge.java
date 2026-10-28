package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.function.IntConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Кнопка шага координаты: одна на поле, квадрат во всю высоту строки, разрезана пополам — сверху «+», снизу «−»
 * (значки 7×7; множитель — у владельца:
 * Shift ×10, Ctrl ×100). ПКМ по половине — обратный шаг.
 *
 * <p>Цвета — канонические тройки граней семейства Aell_ (свет слева): X красная, Y зелёная, Z синяя, как оси
 * Minecraft (F3+B). Светлый тон — значок и подписи осей, средний — заливка половины под курсором, тёмный — тень
 * справа и шов между половинами.
 */
@Environment(EnvType.CLIENT)
public final class AxisNudge extends Widget
{
    /** Тройки канона §8: верх (свет), левая грань, правая грань (тень). */
    private static final int[][] FACES = {
            { 0xFFFF8A80, 0xFFEF4444, 0xFFC62828 },
            { 0xFF4ADE80, 0xFF22C55E, 0xFF16A34A },
            { 0xFF7AB6FF, 0xFF3B82F6, 0xFF1D4ED8 } };

    /** Цвета осей — светлый тон тройки: X красный, Y зелёный, Z синий. */
    static final int AXIS_X = FACES[0][0];
    static final int AXIS_Y = FACES[1][0];
    static final int AXIS_Z = FACES[2][0];

    private final int light;
    private final int face;
    private final int shade;
    private final String tooltip;
    private final IntConsumer step;

    AxisNudge(int axis, String tooltip, IntConsumer step)
    {
        int[] faces = FACES[Math.max(0, Math.min(2, axis))];
        this.light = faces[0];
        this.face = faces[1];
        this.shade = faces[2];
        this.tooltip = tooltip;
        this.step = step;
    }

    public static int axisColor(int index)
    {
        return switch (index)
        {
            case 0 -> AXIS_X;
            case 1 -> AXIS_Y;
            default -> AXIS_Z;
        };
    }

    /** Граница половин: верхняя («+») — {@code y .. mid}, нижняя («−») — {@code mid .. y + h}. */
    private int mid()
    {
        return this.y + this.h / 2;
    }

    /** Значок в ванильной кнопке: цвет оси остаётся у значка (кнопка серая, как у Minecraft). */
    private int vanillaGlyph()
    {
        return this.enabled ? this.light : Vanilla.buttonText(false);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0)
        {
            return;
        }

        boolean over = this.enabled && this.contains(mouseX, mouseY);
        boolean overPlus = over && mouseY < this.mid();
        boolean overMinus = over && ! overPlus;
        int mid = this.mid();

        // Ванильный стиль: две кнопки Minecraft одна над другой, значки поверх
        if (Style.vanilla())
        {
            Vanilla.button(ctx, this.x, this.y, this.w, mid - this.y, this.enabled, overPlus);
            Vanilla.button(ctx, this.x, mid, this.w, this.y + this.h - mid, this.enabled, overMinus);
            Glyphs.center(ctx, Glyphs.PLUS, this.x, this.y, this.w, mid - this.y, overPlus ? Style.V_HOVER : this.vanillaGlyph());
            Glyphs.center(ctx, Glyphs.MINUS, this.x, mid + 1, this.w, this.y + this.h - mid - 1,
                    overMinus ? Style.V_HOVER : this.vanillaGlyph());

            if (over)
            {
                Tooltip.offer(this, this.tooltip);
            }

            return;
        }

        if (! this.enabled)
        {
            Paint.card(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, 0x14FFFFFF, Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF));
            Glyphs.center(ctx, Glyphs.PLUS, this.x, this.y, this.w, mid - this.y, Ui.C_TEXT_OFF);
            Glyphs.center(ctx, Glyphs.MINUS, this.x, mid + 1, this.w, this.y + this.h - mid - 1, Ui.C_TEXT_OFF);
            return;
        }

        // Плашка — левая грань тройки, приглушённая; кромка — свет
        Paint.card(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(this.face, over ? 0.30F : 0.20F),
                Ui.alpha(this.light, over ? 0.95F : 0.50F));

        // Половина под курсором — грань целиком
        if (overPlus)
        {
            Paint.rect(ctx, this.x + 1, this.y + 1, this.w - 2, mid - this.y - 1, this.face);
        }
        else if (overMinus)
        {
            Paint.rect(ctx, this.x + 1, mid, this.w - 2, this.y + this.h - 1 - mid, this.face);
        }

        // Свет слева: светлый край слева, тень справа, тёмный шов между половинами
        Paint.rect(ctx, this.x + 1, this.y + 2, 1, this.h - 4, Ui.alpha(this.light, 0.45F));
        Paint.rect(ctx, this.x + this.w - 2, this.y + 2, 1, this.h - 4, Ui.alpha(this.shade, 0.85F));
        Paint.rect(ctx, this.x + 2, mid, this.w - 4, 1, this.shade);

        Glyphs.center(ctx, Glyphs.PLUS, this.x, this.y, this.w, mid - this.y, overPlus ? 0xFFFFFFFF : this.light);
        Glyphs.center(ctx, Glyphs.MINUS, this.x, mid + 1, this.w, this.y + this.h - mid - 1, overMinus ? 0xFFFFFFFF : this.light);

        if (over)
        {
            Tooltip.offer(this, this.tooltip);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.enabled || ! this.visible || ! this.contains(click.x(), click.y()))
        {
            return false;
        }

        int sign = click.y() < this.mid() ? 1 : -1;

        if (Kit.left(click))
        {
            Vanilla.click();
            this.step.accept(sign);
            return true;
        }

        if (Kit.right(click))
        {
            Vanilla.click();
            this.step.accept(-sign);
            return true;
        }

        return false;
    }
}
