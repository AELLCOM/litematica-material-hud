package com.aell.layerlist.ui;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class ZfButton extends ButtonGeneric
{
    public enum Style
    {
        ROW,
        PRIMARY,
        GHOST,
        SQUARE
    }

    private final Anim hover = new Anim(110.0F);
    private final Anim press = new Anim(90.0F);

    private Style style = Style.ROW;
    private String icon = "";
    private String keyHint = "";
    private int dotColor;
    private int accent = Theme.GREEN;
    private boolean center;
    private long pressedAt;

    public ZfButton(int x, int y, int width, int height, String text)
    {
        super(x, y, width, height, text);
        this.setRenderDefaultBackground(false);
        this.textCentered = false;
    }

    public ZfButton style(Style value)
    {
        this.style = value;
        this.center = value == Style.PRIMARY || value == Style.GHOST || value == Style.SQUARE;

        return this;
    }

    public ZfButton icon(String value)
    {
        this.icon = value == null ? "" : value;

        return this;
    }

    public ZfButton keyHint(@Nullable String value)
    {
        this.keyHint = value == null ? "" : value;

        return this;
    }

    public ZfButton dot(int color)
    {
        this.dotColor = color;

        return this;
    }

    public ZfButton accent(int color)
    {
        this.accent = color;

        return this;
    }

    public ZfButton centered(boolean value)
    {
        this.center = value;

        return this;
    }

    public void flash()
    {
        this.pressedAt = System.currentTimeMillis();
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (! this.visible)
        {
            return;
        }

        this.hovered = this.isMouseOver(mouseX, mouseY);

        boolean live = this.hovered && this.enabled;
        float hoverValue = this.hover.update(live);
        float pressValue = this.press.update(System.currentTimeMillis() - this.pressedAt < 130L);

        int fill;
        int edge;

        switch (this.style)
        {
            case PRIMARY ->
            {
                fill = Theme.mix(Theme.GREEN_FILL, Theme.alpha(this.accent, 0.34F), hoverValue);
                edge = Theme.alpha(this.accent, 0.55F + 0.35F * hoverValue);
            }
            case GHOST ->
            {
                fill = Theme.alpha(Theme.ROW_HOVER, hoverValue * 0.8F);
                edge = Theme.mix(Theme.EDGE, Theme.alpha(this.accent, 0.75F), hoverValue);
            }
            default ->
            {
                fill = Theme.mix(Theme.ROW, Theme.ROW_HOVER, hoverValue);
                edge = Theme.alpha(this.accent, 0.72F * Math.max(hoverValue, pressValue));
            }
        }

        if (selected)
        {
            fill = Theme.mix(fill, Theme.BLUE_FILL, 0.75F);
            edge = Theme.BLUE_EDGE;
        }

        if (! this.enabled)
        {
            fill = Theme.ROW;
            edge = Theme.EDGE_SOFT;
        }

        int radius = this.style == Style.SQUARE ? 4 : Theme.RADIUS;

        if (com.aell.layerlist.shaded.ui.Style.vanilla())
        {
            // Ванильный стиль: кнопка Minecraft, выбранная — подсвечена
            Vanilla.button(ctx, this.x, this.y, this.width, this.height, this.enabled, live || selected);
        }
        else
        {
            Draw.card(ctx, this.x, this.y, this.width, this.height, radius, fill, edge);
        }

        int textY = this.y + (this.height - this.fontHeight) / 2 + 1;
        int textColor = this.enabled ? Theme.TEXT : Theme.OFF;

        if (com.aell.layerlist.shaded.ui.Style.vanilla())
        {
            textColor = ! this.enabled ? com.aell.layerlist.shaded.ui.Style.V_GRAY
                    : selected ? com.aell.layerlist.shaded.ui.Style.V_HOVER : com.aell.layerlist.shaded.ui.Style.V_WHITE;
        }

        int inset = this.style == Style.SQUARE ? 0 : this.height <= 15 ? 5 : 9;
        int left = this.x + inset;
        int right = this.x + this.width - Math.max(4, inset - 1);

        if (this.dotColor != 0)
        {
            int size = 4;
            float lift = 0.55F + 0.45F * hoverValue;
            Draw.round(ctx, left, this.y + (this.height - size) / 2, size, size, 1,
                    Theme.alpha(this.enabled ? this.dotColor : Theme.OFF, lift));
            left += size + 6;
        }

        if (! this.icon.isEmpty())
        {
            int iconColor = Theme.mix(Theme.MUTED, this.accent, hoverValue);
            Paint.text(ctx, this.textRenderer, this.icon, left, textY, iconColor, false);
            left += this.textRenderer.width(this.icon) + 6;
        }

        if (! this.keyHint.isEmpty())
        {
            Draw.textRight(ctx, this.textRenderer, this.keyHint, right, textY, Theme.FAINT);
            right -= this.textRenderer.width(this.keyHint) + 8;
        }

        String label = Draw.fit(this.textRenderer, this.displayString, Math.max(8, right - left));

        if (this.center)
        {
            int x = this.x + (this.width - this.textRenderer.width(label)) / 2;
            Paint.text(ctx, this.textRenderer, label, x, textY, textColor, false);
        }
        else
        {
            Paint.text(ctx, this.textRenderer, label, left, textY, textColor, false);
        }
    }
}
