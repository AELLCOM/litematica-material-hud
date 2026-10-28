package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Заголовок раздела окна, как «Размещения этой схемы» в карточке: приглушённый текст и линия под
 * ним; справа — необязательные мелкие кнопки (высота кнопок шапки). Подсказка по наведению на
 * текст — по желанию ({@link #tooltip}).
 */
@Environment(EnvType.CLIENT)
final class SectionTitle extends Widget
{
    /** Высота строки заголовка вместе с линией. */
    static final int HEIGHT = Ui.SECTION_H + Ui.BORDER;

    private final Supplier<String> text;
    private final List<Button> trailing;
    private @Nullable String tooltip;

    SectionTitle(Supplier<String> text, Button... trailing)
    {
        this.text = text;
        this.trailing = List.of(trailing);
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        Font font = Kit.font();
        int right = x + w;
        int by = y + (Ui.SECTION_H - Ui.HEAD_BTN_H) / 2;

        for (int i = this.trailing.size() - 1; i >= 0; --i)
        {
            Button button = this.trailing.get(i);

            if (! button.visible())
            {
                button.setBounds(right, by, 0, 0);
                continue;
            }

            int bw = Math.min(button.preferredWidth(font), Math.max(0, w / 3));
            right -= bw;
            button.setBounds(right, by, bw, Ui.HEAD_BTN_H);
            right -= Ui.PAD_IN;
        }
    }

    /** Подсказка при наведении на текст заголовка (не на кнопки справа). */
    SectionTitle tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    /** Где кончается место под текст (левее первой кнопки). */
    private int textRoom()
    {
        int room = this.w;

        for (Button button : this.trailing)
        {
            if (button.visible() && button.w() > 0)
            {
                room = Math.min(room, button.x() - Ui.PAD_REL - this.x);
            }
        }

        return Math.max(0, room);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();

        Paint.midText(ctx, font, Paint.fit(font, this.text.get(), this.textRoom()), this.x, this.y, Ui.SECTION_H,
                Ui.C_TEXT_3, false);
        Paint.rect(ctx, this.x, this.y + Ui.SECTION_H, this.w, Ui.BORDER, Ui.C_DIVIDER);

        if (this.tooltip != null && Paint.hit(mouseX, mouseY, this.x, this.y, this.textRoom(), Ui.SECTION_H))
        {
            Tooltip.offer(this, this.tooltip);
        }

        for (Button button : this.trailing)
        {
            button.render(ctx, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        for (Button button : this.trailing)
        {
            if (button.visible() && button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : this.trailing)
        {
            button.mouseReleased(click);
        }

        return false;
    }
}
