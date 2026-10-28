package com.aell.layerlist.gui;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.config.LayerListDefaults;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.shaded.ui.ModSwitcher;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.Draw;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.ZfButton;
import com.aell.layerlist.ui.ZfScreen;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

@Environment(EnvType.CLIENT)
public class GuiHudPosition extends ZfScreen
{
    private static final int SNAP_DISTANCE = 6;
    private static final int HANDLE_PAD = 3;
    private static final int BUTTON_H = 18;
    private static final int PANEL_H = 96;
    private static final int MIN_PANEL_W = 240;
    private static final int MAX_PANEL_W = MAX_WIDTH;
    private static final int CHIP_STEP = 14;

    private final int enteredX;
    private final int enteredY;
    private final boolean enteredCustom;

    private int posX = LayerListConfigs.HUD_POS_X;
    private int posY = LayerListConfigs.HUD_POS_Y;
    private boolean dragging;
    private int grabOffsetX;
    private int grabOffsetY;
    private boolean snappedX;
    private boolean snappedY;
    private boolean saveOnClose = true;

    private GuiHudPosition(@Nullable Screen parent)
    {
        super(parent);
        this.title = Strings.plain("layerlist.gui.move_hud.title");
        this.crumb = "Litematica Material HUD";
        this.alignLeft = true;
        this.switcher = new ModSwitcher("layerlist");
        this.enteredX = LayerListConfigs.HUD_POS_X;
        this.enteredY = LayerListConfigs.HUD_POS_Y;
        this.enteredCustom = LayerListConfigs.HUD_CUSTOM_POSITION.getBooleanValue();
    }

    public static void openFromSettings()
    {
        Screen current = GuiUtils.getCurrentScreen();
        Nav.open(new GuiHudPosition(current), current);
    }

    public static void openFromKeybind()
    {
        Nav.open(new GuiHudPosition(null), null);
    }

    @Override
    protected int maxWidth()
    {
        int need = this.getStringWidth(Strings.plain("layerlist.gui.move_hud.hint_default"));

        need = Math.max(need, this.getStringWidth(Strings.plain("layerlist.gui.move_hud.hint_custom")));
        need = Math.max(need, this.buttonsWidth());
        need = Math.max(need, this.getStringWidth(this.title) + 60);

        return Math.max(MIN_PANEL_W, Math.min(MAX_PANEL_W, need + this.pad() * 2));
    }

    private int buttonsWidth()
    {
        return this.labelWidth("layerlist.gui.move_hud.done")
                + this.labelWidth("layerlist.gui.move_hud.reset")
                + this.labelWidth("layerlist.gui.move_hud.cancel")
                + Theme.GAP * 2;
    }

    private int labelWidth(String labelKey)
    {
        return this.getStringWidth(Strings.plain(labelKey)) + 24;
    }

    @Override
    protected void layout()
    {
        int margin = this.width < 380 ? 6 : 14;
        int available = Math.max(160, this.width - margin * 2);

        this.panelWidth = Math.min(available, this.maxWidth());
        this.panelHeight = Math.min(PANEL_H, Math.max(60, this.height - 26));
        this.panelX = margin;
        this.panelY = Math.max(20, this.height - margin - this.panelHeight);
    }

    @Override
    public void initGui()
    {
        super.initGui();

        this.subtitle = "X " + this.posX + " · Y " + this.posY;
        this.message = Strings.plain(LayerListConfigs.HUD_CUSTOM_POSITION.getBooleanValue()
                ? "layerlist.gui.move_hud.hint_custom"
                : "layerlist.gui.move_hud.hint_default");
        this.messageColor = Theme.MUTED;

        int room = this.contentWidth() - Theme.GAP * 2;
        int done = this.labelWidth("layerlist.gui.move_hud.done");
        int reset = this.labelWidth("layerlist.gui.move_hud.reset");
        int cancel = this.labelWidth("layerlist.gui.move_hud.cancel");
        int total = done + reset + cancel;

        if (total > room)
        {
            done = Math.max(30, done * room / total);
            reset = Math.max(30, reset * room / total);
            cancel = Math.max(30, room - done - reset);
        }

        int x = this.contentX();
        int y = this.footerY();

        this.button(x, y, done, BUTTON_H, Strings.plain("layerlist.gui.move_hud.done"), () -> {
            this.saveOnClose = true;
            Nav.back();
        }).style(ZfButton.Style.PRIMARY).accent(Theme.GREEN);

        x += done + Theme.GAP;

        this.button(x, y, reset, BUTTON_H, Strings.plain("layerlist.gui.move_hud.reset"), () -> {
            LayerListDefaults.clearCustomPosition();
            this.posX = LayerListDefaults.HUD_POS_X;
            this.posY = LayerListDefaults.HUD_POS_Y;
            this.dragging = false;
            this.reinit();
        }).style(ZfButton.Style.GHOST).accent(Theme.BLUE);

        x += reset + Theme.GAP;

        this.button(x, y, cancel, BUTTON_H, Strings.plain("layerlist.gui.move_hud.cancel"), () -> {
            this.saveOnClose = false;
            Nav.back();
        }).style(ZfButton.Style.GHOST).accent(Theme.RED);
    }

    private void reinit()
    {
        this.clearElements();
        this.initGui();
    }

    @Override
    public void removed()
    {
        super.removed();

        if (this.saveOnClose)
        {
            LayerListConfigs.HUD_POS_X = this.posX;
            LayerListConfigs.HUD_POS_Y = this.posY;
        }
        else
        {
            LayerListConfigs.HUD_POS_X = this.enteredX;
            LayerListConfigs.HUD_POS_Y = this.enteredY;
            LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(this.enteredCustom);
        }

        new LayerListConfigs().save();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (super.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        int mouseX = (int) click.x();
        int mouseY = (int) click.y();
        int[] size = LayerHudRenderer.getPanelScreenSize();

        if (mouseX >= this.posX && mouseY >= this.posY
                && mouseX < this.posX + size[0] && mouseY < this.posY + size[1])
        {
            this.dragging = true;
            this.grabOffsetX = mouseX - this.posX;
            this.grabOffsetY = mouseY - this.posY;
            LayerListConfigs.HUD_CUSTOM_POSITION.setBooleanValue(true);

            return true;
        }

        return false;
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragX, double dragY)
    {
        if (this.dragging)
        {
            this.moveTo((int) click.x() - this.grabOffsetX, (int) click.y() - this.grabOffsetY);

            return true;
        }

        return super.onMouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        this.dragging = false;

        return super.onMouseReleased(click);
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.switcher != null && this.switcher.key(input))
        {
            return true;
        }

        if (input.key() == InputConstants.KEY_ESCAPE)
        {
            this.saveOnClose = false;
            Nav.back();

            return true;
        }

        return super.onKeyTyped(input);
    }

    private void moveTo(int screenX, int screenY)
    {
        int[] size = LayerHudRenderer.getPanelScreenSize();

        this.snappedX = false;
        this.snappedY = false;

        int centerX = (this.width - size[0]) / 2;
        int centerY = (this.height - size[1]) / 2;

        int[] stopsX = { 0, centerX, this.width - size[0] };
        int[] stopsY = { 0, centerY, this.height - size[1] };

        for (int stop : stopsX)
        {
            if (Math.abs(screenX - stop) <= SNAP_DISTANCE)
            {
                screenX = stop;
                this.snappedX = true;
                break;
            }
        }

        for (int stop : stopsY)
        {
            if (Math.abs(screenY - stop) <= SNAP_DISTANCE)
            {
                screenY = stop;
                this.snappedY = true;
                break;
            }
        }

        this.posX = LayerHudRenderer.clampScreenX(screenX, size[0]);
        this.posY = LayerHudRenderer.clampScreenY(screenY, size[1]);
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        int[] size = LayerHudRenderer.getPanelScreenSize();
        boolean hovered = mouseX >= this.posX && mouseY >= this.posY
                && mouseX < this.posX + size[0] && mouseY < this.posY + size[1];

        Draw.roundEdge(ctx, this.posX - HANDLE_PAD, this.posY - HANDLE_PAD,
                size[0] + HANDLE_PAD * 2, size[1] + HANDLE_PAD * 2, Theme.RADIUS,
                this.dragging || hovered ? Theme.GREEN : Theme.GREEN_EDGE);

        if (this.dragging)
        {
            if (this.snappedX)
            {
                Draw.rect(ctx, this.posX + size[0] / 2, 0, 1, this.height,
                        Theme.alpha(Theme.GREEN, 0.7F));
            }

            if (this.snappedY)
            {
                Draw.rect(ctx, 0, this.posY + size[1] / 2, this.width, 1,
                        Theme.alpha(Theme.GREEN, 0.7F));
            }
        }

        LayerHudRenderer.drawAtScreenPos(ctx, this.posX, this.posY);

        this.subtitle = "X " + this.posX + " · Y " + this.posY;

        this.drawChips(ctx, size);
        this.drawFooter(ctx);
    }

    private record Chip(String text, int text2, int fill, int edge) {}

    private void drawChips(GuiContext ctx, int[] size)
    {
        int x = this.contentX();
        int y = this.contentY();
        int limit = this.contentX() + this.contentWidth();

        for (Chip chip : this.chips(size))
        {
            String text = Draw.fit(this.font, chip.text(), this.contentWidth() - 12);
            int width = this.getStringWidth(text) + 12;

            if (x > this.contentX() && x + width > limit)
            {
                x = this.contentX();
                y += CHIP_STEP;
            }

            Draw.chip(ctx, this.font, text, x, y, chip.text2(), chip.fill(), chip.edge());
            x += width + Theme.GAP;
        }
    }

    private List<Chip> chips(int[] size)
    {
        List<Chip> chips = new ArrayList<>(3);
        boolean custom = LayerListConfigs.HUD_CUSTOM_POSITION.getBooleanValue();

        chips.add(new Chip(Strings.plain(custom
                ? "layerlist.gui.move_hud.chip_custom"
                : "layerlist.gui.move_hud.chip_default"),
                custom ? Theme.GREEN : Theme.MUTED,
                custom ? Theme.GREEN_FILL : Theme.ROW,
                custom ? Theme.GREEN_EDGE : Theme.EDGE_SOFT));

        chips.add(new Chip(size[0] + "×" + size[1], Theme.BLUE, Theme.BLUE_FILL, Theme.BLUE_EDGE));

        double scale = LayerHudRenderer.getScale();

        if (Math.abs(scale - 1.0) > 0.001)
        {
            chips.add(new Chip(String.format("%.2f×", scale), Theme.MUTED, Theme.ROW, Theme.EDGE_SOFT));
        }

        return chips;
    }
}
