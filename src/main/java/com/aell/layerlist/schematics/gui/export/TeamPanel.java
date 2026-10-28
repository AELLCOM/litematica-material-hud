package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Раздел «Таблица команды» (SPEC §8.2.2): «Отправить в таблицу команды» — только если в
 * настройках задан адрес скрипта; «Скопировать скрипт» и короткая инструкция установки
 * («Как установить» раскрывает шаги) — всегда: они как раз для тех, у кого таблица ещё не
 * настроена.
 */
@Environment(EnvType.CLIENT)
final class TeamPanel extends Widget
{
    private final SectionTitle title;
    private final Button send;
    private final Button copyScript;
    private final Button howto;
    private boolean expanded;

    TeamPanel(Runnable onSend, Runnable onCopyScript, boolean selected)
    {
        this.title = new SectionTitle(() -> DialogText.t("team"));
        this.send = new Button(DialogText.t("team.send"), Button.Style.PRIMARY, onSend)
                .tooltip(DialogText.t("team.send.tip")).selected(selected);
        this.copyScript = new Button(DialogText.t("team.copy_script"), Button.Style.SECONDARY, onCopyScript)
                .tooltip(DialogText.t("team.copy_script.tip"));
        this.howto = new Button(DialogText.t("team.howto"), Button.Style.GHOST, this::toggleHowto)
                .trailing(Glyphs.CHEVRON_DOWN);
    }

    Button sendButton()
    {
        return this.send;
    }

    private void toggleHowto()
    {
        this.expanded = ! this.expanded;
        this.howto.label(this.expanded ? DialogText.t("team.howto.hide") : DialogText.t("team.howto"));
        this.howto.trailing(this.expanded ? null : Glyphs.CHEVRON_DOWN);
    }

    /** Отправка: {@code why} — почему нельзя (подсказка выключенной кнопки), {@code null} — можно. */
    void update(@Nullable String why)
    {
        this.send.setEnabled(why == null);
        this.send.tooltip(why == null ? DialogText.t("team.send.tip") : why);
    }

    private List<Button> buttons()
    {
        List<Button> list = new ArrayList<>(3);

        if (ExportFormat.teamConfigured())
        {
            list.add(this.send);
        }

        list.add(this.copyScript);
        list.add(this.howto);

        return list;
    }

    int height(int width)
    {
        Font font = Kit.font();
        int h = SectionTitle.HEIGHT + Ui.PAD_IN;

        if (! ExportFormat.teamConfigured())
        {
            h += Kit.wrap(font, DialogText.t("team.off"), width).size() * Ui.TIP_LINE + Ui.PAD_IN;
        }

        int lines = this.placeButtons(width, 0, 0, false);
        h += lines * Ui.BUTTON_H + Math.max(0, lines - 1) * Ui.PAD_IN;

        if (this.expanded)
        {
            h += Ui.PAD_IN + Kit.wrap(font, DialogText.t("team.steps"), width).size() * Ui.TIP_LINE;
        }

        return h;
    }

    /** Кнопки в строку с переносом; возвращает число строк. */
    private int placeButtons(int width, int x, int y, boolean apply)
    {
        Font font = Kit.font();
        int cx = 0;
        int line = 0;

        for (Button button : this.buttons())
        {
            int bw = Math.min(button.preferredWidth(font), Math.max(0, width));

            if (cx > 0 && cx + bw > width)
            {
                ++line;
                cx = 0;
            }

            if (apply)
            {
                button.setBounds(x + cx, y + line * (Ui.BUTTON_H + Ui.PAD_IN), bw, Ui.BUTTON_H);
            }

            cx += bw + Ui.PAD_IN;
        }

        return line + 1;
    }

    private int buttonsTop()
    {
        int top = this.y + SectionTitle.HEIGHT + Ui.PAD_IN;

        if (! ExportFormat.teamConfigured())
        {
            top += Kit.wrap(Kit.font(), DialogText.t("team.off"), this.w).size() * Ui.TIP_LINE + Ui.PAD_IN;
        }

        return top;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        this.title.setBounds(x, y, w, SectionTitle.HEIGHT);
        this.send.setVisible(ExportFormat.teamConfigured());
        this.placeButtons(w, x, this.buttonsTop(), true);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        boolean configured = ExportFormat.teamConfigured();

        this.title.render(ctx, mouseX, mouseY);

        if (! configured)
        {
            int ty = this.y + SectionTitle.HEIGHT + Ui.PAD_IN;

            for (String line : Kit.wrap(font, DialogText.t("team.off"), this.w))
            {
                Paint.text(ctx, font, line, this.x, ty + 1, Ui.C_TEXT_3, false);
                ty += Ui.TIP_LINE;
            }
        }

        int bottom = this.y;

        for (Button button : this.buttons())
        {
            button.render(ctx, mouseX, mouseY);
            bottom = Math.max(bottom, button.y() + button.h());
        }

        if (this.expanded)
        {
            int ty = bottom + Ui.PAD_IN;

            for (String line : Kit.wrap(font, DialogText.t("team.steps"), this.w))
            {
                Paint.text(ctx, font, line, this.x, ty + 1, Ui.C_TEXT_2, false);
                ty += Ui.TIP_LINE;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        for (Button button : this.buttons())
        {
            if (button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : List.of(this.send, this.copyScript, this.howto))
        {
            button.mouseReleased(click);
        }

        return false;
    }
}
