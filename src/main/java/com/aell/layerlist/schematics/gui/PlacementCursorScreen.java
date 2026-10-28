package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.TextFocus;
import com.aell.layerlist.ui.kit.Tooltip;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Курсор у карточки «Смотреть на мир» редактора размещения (клавиша R в игре): экран без паузы и без затемнения, на
 * нём ТОЛЬКО карточка — на том же месте, что HUD, — с живыми контролами: числа вводятся (цифры и минус, Enter или
 * щелчок мимо применяют), «+/−» жмутся (Shift ×10, Ctrl ×100), колесо над полем, «Двигать в мире», «К игроку»,
 * поворот, отражение, замок. R ещё раз или щелчок мимо карточки — снова игра (карточка остаётся HUD-ом); Esc — редактор
 * целиком (первый Esc в поле только снимает ввод).
 *
 * <p>Все правки идут через редактор ({@link PlacementHud.Card}): замок, сообщения и история шагов «Отмены» работают так же,
 * как в самом редакторе.
 */
@Environment(EnvType.CLIENT)
public final class PlacementCursorScreen extends GuiBase
{
    private final PlacementEditorScreen editor;
    private final PlacementHud.Card card;

    PlacementCursorScreen(PlacementEditorScreen editor)
    {
        this.editor = editor;
        this.card = new PlacementHud.Card(editor);
        this.title = "";
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    /** Без затемнения: мир виден, рисуется только карточка. */
    @Override
    protected void drawScreenBackground(GuiContext ctx, int mouseX, int mouseY)
    {
        Tooltip.beginFrame();
    }

    @Override
    protected void drawTitle(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
    }

    @Override
    public void tick()
    {
        super.tick();

        // Размещение убрали снаружи — карточке нечего показывать
        if (! this.editor.worldExists() || PlacementHud.editor() != this.editor)
        {
            PlacementHud.leave();
            this.toGame();
        }
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        this.card.draw(ctx, this.font, this.editor, mouseX, mouseY, true, this.width);
    }

    @Override
    protected void drawButtonHoverTexts(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        Tooltip.render(ctx, Kit.font(), mouseX, mouseY, this.width, this.height);
    }

    /** Снова игра: введённое применено, карточка остаётся HUD-ом. */
    private void toGame()
    {
        this.card.commitAll();
        this.mc.gui.setScreen(null);
    }

    /** Esc: редактор целиком — тот же экземпляр, со всем сеансом. */
    private void toEditor()
    {
        this.card.commitAll();
        PlacementHud.leave();
        this.editor.reopen();
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.card.contains(click.x(), click.y()))
        {
            this.toGame();
            return true;
        }

        return this.card.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double dragX, double dragY)
    {
        return this.card.mouseDragged(click, dragX, dragY);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        return this.card.mouseReleased(click);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        return this.card.mouseScrolled(mouseX, mouseY, verticalAmount);
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        int key = input.key();

        // Клавиша курсора ещё раз — снова игра (в поля координат буквы всё равно не вводятся)
        if (SelectionHud.isCursorKey(key))
        {
            this.toGame();
            return true;
        }

        if (key == InputConstants.KEY_ESCAPE)
        {
            // Первый Esc в поле только снимает ввод (число остаётся прежним), следующий — редактор
            if (! this.card.blur())
            {
                this.toEditor();
            }

            return true;
        }

        return this.card.keyPressed(input);
    }

    @Override
    public boolean onCharTyped(CharacterEvent input)
    {
        return this.card.charTyped(input);
    }

    /** Черновик IME (26.3): ваниль отдаёт его экрану, а мы — полю, которое держит ввод текста. */
    @Override
    public boolean preeditUpdated(@Nullable PreeditEvent event)
    {
        return TextFocus.preedit(event) || super.preeditUpdated(event);
    }

    @Override
    public void removed()
    {
        // Ваниль при смене экрана ввод текста уже выключила; фокус полей снимаем сами
        this.card.blur();
        super.removed();
    }
}
