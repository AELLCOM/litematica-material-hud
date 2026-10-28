package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Листание текста для Discord: «Discord: скопирована часть 1 из 3» и кнопка «Скопировать следующую
 * часть». Discord режет сообщения на 2000 символах, поэтому список копируется частями: первая —
 * кнопкой формата, остальные — отсюда. Строка видна, только пока частей больше одной и запрос тот же;
 * она же — строка итога копирования (общая строка окна её не повторяет).
 *
 * <p>Пока есть нескопированные части, кнопка горит золотом, как лупа поиска настроек
 * ({@link Ui#C_GOLD}): игрок вставил часть в Discord, вернулся — и сразу видит, куда жать дальше.
 * Всё скопировано — кнопка серая и выключена («Все части скопированы»).
 */
@Environment(EnvType.CLIENT)
final class DiscordRow extends Widget
{
    private final Button next;
    private int part = -1;
    private int parts;

    DiscordRow(Runnable onNext)
    {
        this.next = new Button(DialogText.t("discord.next"), Button.Style.PRIMARY, onNext).accent(Ui.C_GOLD);
    }

    /** Скопирована часть {@code part} (с нуля) из {@code parts}. */
    void copied(int part, int parts)
    {
        this.part = part;
        this.parts = parts;
    }

    void reset()
    {
        this.part = -1;
        this.parts = 0;
    }

    int part()
    {
        return this.part;
    }

    boolean shown()
    {
        return this.part >= 0 && this.parts > 1;
    }

    /** Остались ли нескопированные части (кнопка золотая и живая). */
    boolean more()
    {
        return this.part + 1 < this.parts;
    }

    /** Кнопка «дальше» — пока есть нескопированные части и окно не занято выгрузкой. */
    void update(boolean idle)
    {
        boolean more = this.more();

        this.next.label(more ? DialogText.t("discord.next") : DialogText.t("discord.all"));
        this.next.setEnabled(more && idle);
        this.next.tooltip(! idle ? DialogText.t("busy")
                : more ? DialogText.f("discord.next.tip", this.part + 2, this.parts) : DialogText.t("discord.all.tip"));
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        int bw = Math.min(this.next.preferredWidth(Kit.font()), Math.max(0, w / 2));
        this.next.setBounds(x + w - bw, y, bw, Math.min(h, Ui.BUTTON_H));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.shown())
        {
            return;
        }

        Font font = Kit.font();
        String text = DialogText.f("discord.part", this.part + 1, this.parts);
        int room = Math.max(0, this.next.x() - Ui.PAD_REL - this.x);

        Paint.midText(ctx, font, Paint.fit(font, text, room), this.x, this.y, Ui.BUTTON_H, Theme.GREEN, false);
        this.next.render(ctx, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return this.shown() && this.next.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        this.next.mouseReleased(click);
        return false;
    }
}
