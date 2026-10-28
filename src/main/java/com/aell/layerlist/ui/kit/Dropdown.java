package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Anim;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Выбор из списка: контрол как у экрана настроек (высота по раскладке, рамка {@link Ui#C_DIVIDER},
 * значок {@link Glyphs#CHEVRON_DOWN} справа), список раскрывается попапом {@link ContextMenu} через
 * {@link Layers} — поэтому он поверх всего и закрывается кликом мимо.
 */
@Environment(EnvType.CLIENT)
public final class Dropdown<T> extends Widget
{
    private final Layers layers;
    private final List<T> values;
    private final Function<T, String> label;
    private final Consumer<T> onChange;
    private final Anim hoverAnim = new Anim(Ui.ANIM_HOVER_MS);

    private T value;
    private String prefix = "";
    private @Nullable String tooltip;
    private @Nullable ContextMenu open;
    /** С какого момента курсор над закрытым списком: длинное значение бежит с начала (-1 — не над ним). */
    private long overSince = -1L;

    public Dropdown(Layers layers, List<T> values, T value, Function<T, String> label, Consumer<T> onChange)
    {
        this.layers = layers;
        this.values = List.copyOf(values);
        this.value = value;
        this.label = label;
        this.onChange = onChange;
    }

    /** Подпись перед значением: «Сортировка: Имя». */
    public Dropdown<T> prefix(String prefix)
    {
        this.prefix = prefix;
        return this;
    }

    public Dropdown<T> tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    public T value()
    {
        return this.value;
    }

    /** Без вызова {@code onChange}: синхронизация с внешним состоянием. */
    public void setValue(T value)
    {
        this.value = value;
    }

    public int preferredWidth(Font font)
    {
        int widest = 0;

        for (T candidate : this.values)
        {
            // Очень длинное значение (имя схемы) поле не раздувает: шире Ui.LONG_TEXT_W — многоточие, под курсором бежит
            widest = Math.max(widest, Math.min(font.width(this.label.apply(candidate)), Ui.LONG_TEXT_W));
        }

        return font.width(this.prefix) + widest + Ui.BUTTON_PAD_X * 2 + Ui.PAD_IN
                + Glyphs.width(Glyphs.CHEVRON_DOWN);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0)
        {
            return;
        }

        Font font = Kit.font();
        boolean expanded = this.open != null && this.layers.isOpen(this.open);
        boolean over = this.contains(mouseX, mouseY);
        float hover = this.hoverAnim.update(over && this.enabled);

        boolean vanilla = Style.vanilla();

        // Ванильный вид: закрытый список — кнопка Minecraft (подсвечена при наведении и пока раскрыт)
        if (vanilla)
        {
            Vanilla.button(ctx, this.x, this.y, this.w, this.h, this.enabled, over || expanded);
        }
        else
        {
            if (hover > 0.0F)
            {
                Paint.round(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.alpha(Ui.C_ROW_HOVER, hover));
            }

            Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL,
                    expanded ? Ui.C_ACCENT : this.enabled ? Ui.C_DIVIDER : Ui.alpha(Ui.C_DIVIDER, Ui.A_TRACK_OFF));
        }

        int chevronX = this.x + this.w - Ui.BUTTON_PAD_X - Glyphs.width(Glyphs.CHEVRON_DOWN);
        int textX = this.x + Ui.BUTTON_PAD_X;
        int room = chevronX - Ui.PAD_IN - textX;
        int color = vanilla ? Vanilla.buttonText(this.enabled) : this.enabled ? Ui.C_TEXT : Ui.C_TEXT_OFF;
        String current = this.label.apply(this.value);

        // Узко — приставка уходит первой, значение важнее
        if (! this.prefix.isEmpty() && font.width(this.prefix + current) <= room)
        {
            Paint.midText(ctx, font, this.prefix, textX, this.y, this.h,
                    vanilla ? Style.V_GRAY : Ui.C_TEXT_3, false);
            textX += font.width(this.prefix);
            room -= font.width(this.prefix);
        }

        if (! over)
        {
            this.overSince = -1L;
        }
        else if (this.overSince < 0L)
        {
            this.overSince = net.minecraft.util.Util.getMillis();
        }

        Paint.midMarquee(ctx, font, current, textX, this.y, this.h, room, color, false, this.overSince);
        Glyphs.draw(ctx, Glyphs.CHEVRON_DOWN, chevronX, this.y + (this.h - Glyphs.height(Glyphs.CHEVRON_DOWN) + 1) / 2,
                vanilla ? Vanilla.buttonText(this.enabled) : this.enabled ? Ui.C_TEXT_2 : Ui.C_TEXT_OFF);

        if (over)
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

        if (this.enabled)
        {
            // Щелчок при открытии списка (только ванильный стиль)
            Vanilla.click();
            this.expand();
        }

        return true;
    }

    public void expand()
    {
        List<ContextMenu.Item> items = new ArrayList<>();

        for (T candidate : this.values)
        {
            boolean current = Objects.equals(candidate, this.value);
            items.add(new ContextMenu.Item(this.label.apply(candidate), true, false, current, null, () ->
            {
                this.value = candidate;
                this.onChange.accept(candidate);
            }));
        }

        this.open = ContextMenu.below(this.layers, this.x, this.y, this.w, this.h, items);
    }
}
