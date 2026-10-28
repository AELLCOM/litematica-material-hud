package com.aell.layerlist.ui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public abstract class ZfScreen extends GuiBase
{
    protected static final int MAX_WIDTH = 560;

    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;

    protected String crumb = "";
    protected String subtitle = "";
    protected String message = "";
    protected int messageColor = Theme.GREEN;

    protected boolean alignLeft;

    protected boolean edgePinned;

    protected @Nullable com.aell.layerlist.shaded.ui.ModSwitcher switcher;

    private final List<ButtonBase> hoverable = new ArrayList<>();

    protected ZfScreen(@Nullable net.minecraft.client.gui.screens.Screen parent)
    {
        this.setParent(parent);
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.layout();

        if (this.switcher != null)
        {
            this.switcher.layout(this.width, this.panelX + this.panelWidth);
        }
    }

    protected int maxWidth()
    {
        return MAX_WIDTH;
    }

    protected int pad()
    {
        return Theme.PAD;
    }

    protected void layout()
    {
        if (this.edgePinned)
        {
            this.panelX = 0;
            this.panelY = 0;
            this.panelWidth = Math.min(this.maxWidth(), this.width);
            this.panelHeight = this.height;

            return;
        }

        int margin = this.width < 380 ? 6 : 14;
        int available = Math.max(160, this.width - margin * 2);
        int used = Math.min(available, this.maxWidth());

        this.panelX = this.alignLeft ? margin : (this.width - used) / 2;
        this.panelWidth = used;
        this.panelY = this.width < 380 ? 16 : 22;
        this.panelHeight = Math.max(120, this.height - this.panelY - margin);
    }

    protected int contentX()
    {
        return this.panelX + this.pad();
    }

    protected int contentWidth()
    {
        return this.panelWidth - this.pad() * 2;
    }

    protected int contentY()
    {
        return this.panelY + (this.subtitle.isEmpty() ? 26 : 28);
    }

    protected int contentBottom()
    {
        return this.panelY + this.panelHeight - this.pad();
    }

    protected int footerY()
    {
        return this.contentBottom() - (this.message.isEmpty() ? 20 : 32);
    }

    protected ZfButton button(int x, int y, int width, int height, String label, Runnable action)
    {
        ZfButton button = new ZfButton(x, y, width, height, label);

        this.addButton(button, (b, mouseButton) -> {
            if (b instanceof ZfButton zf)
            {
                zf.flash();
            }

            action.run();
        });

        return button;
    }

    protected ZfButton row(int y, String label, String icon, String keyHint, Runnable action)
    {
        return this.button(this.contentX(), y, this.contentWidth(), Theme.ROW_H + 4, label, action)
                .icon(icon)
                .keyHint(keyHint)
                .style(ZfButton.Style.ROW);
    }

    protected void setMessage(String text)
    {
        this.message = text == null ? "" : text;
        this.messageColor = Theme.GREEN;
    }

    protected void setMessage(String text, int color)
    {
        this.message = text == null ? "" : text;
        this.messageColor = color;
    }

    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY)
    {
        Draw.screen(ctx, this.width, this.height);
        Draw.panel(ctx, this.panelX, this.panelY, this.panelWidth, this.panelHeight);
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (! this.crumb.isEmpty() && this.panelY >= 20)
        {
            Draw.tracked(ctx, this.font, this.crumb.toUpperCase(java.util.Locale.ROOT),
                    this.panelX + 2, this.panelY - 12, Theme.FAINT, 1);
        }

        int x = this.contentX();
        int y = this.panelY + this.pad();

        String title = Draw.fit(this.font, this.getTitleString(), this.contentWidth());
        Paint.text(ctx, this.font, title, x, y, Theme.TEXT, false);

        if (! this.subtitle.isEmpty())
        {
            int subtitleX = x + this.font.width(title) + 8;
            int room = this.contentX() + this.contentWidth() - subtitleX;

            if (room > 40)
            {
                Paint.text(ctx, this.font, Draw.fit(this.font, this.subtitle, room), subtitleX, y, Theme.MUTED, false);
            }
        }
    }

    @Override
    public boolean onMouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick)
    {
        if (this.switcher != null && this.switcher.click(click, doubleClick))
        {
            return true;
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onKeyTyped(net.minecraft.client.input.KeyEvent input)
    {
        if (this.switcher != null && this.switcher.key(input))
        {
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical)
    {
        if (this.switcher != null && this.switcher.scroll(mouseX, mouseY, horizontal, vertical))
        {
            return true;
        }

        return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    protected void drawButtonHoverTexts(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.switcher != null)
        {
            this.switcher.render(ctx, mouseX, mouseY);
        }

        if (! this.shouldRenderHoverStuff())
        {
            return;
        }

        for (ButtonBase button : this.hoverable)
        {
            if (button.hasHoverText() && button.isMouseOver())
            {
                drawTip(ctx, this.font, button.getHoverStrings(), mouseX, mouseY, this.width, this.height);
                return;
            }
        }
    }

    private static void drawTip(GuiContext ctx, Font font, List<String> lines,
            int mouseX, int mouseY, int screenWidth, int screenHeight)
    {
        int textWidth = 0;

        for (String line : lines)
        {
            textWidth = Math.max(textWidth, font.width(line));
        }

        int width = textWidth + 12;
        int height = lines.size() * 10 + 6;
        int x = Math.max(4, Math.min(mouseX + 10, screenWidth - width - 4));
        int y = Math.max(4, Math.min(mouseY - 6, screenHeight - height - 4));

        // Ванильная подсказка Minecraft: рамка по области, текст белый с тенью
        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, x, y, width, height);
        }
        else
        {
            Draw.card(ctx, x, y, width, height, Theme.RADIUS, Theme.PANEL_DEEP, Theme.EDGE);
        }

        for (int i = 0; i < lines.size(); ++i)
        {
            Paint.text(ctx, font, lines.get(i), x + 6, y + 4 + i * 10, i == 0 ? Theme.TEXT : Theme.MUTED, false);
        }
    }

    @Override
    public <T extends ButtonBase> T addButton(T button, IButtonActionListener listener)
    {
        this.hoverable.add(button);

        return super.addButton(button, listener);
    }

    @Override
    protected void clearElements()
    {
        super.clearElements();
        this.hoverable.clear();
    }

    protected void drawFooter(GuiContext ctx)
    {
        // Ванильный разделитель над подвалом вместо тонкой линии Aell
        if (Style.vanilla())
        {
            Vanilla.footerSeparator(ctx, this.contentX(), this.footerY() - 8, this.contentWidth());
        }
        else
        {
            Draw.rect(ctx, this.contentX(), this.footerY() - 7, this.contentWidth(), 1, Theme.EDGE_SOFT);
        }

        if (! this.message.isEmpty())
        {
            Paint.text(ctx, this.font, Draw.fit(this.font, this.message, this.contentWidth()),
                    this.contentX(), this.contentBottom() - 9, this.messageColor, false);
        }
    }
}
