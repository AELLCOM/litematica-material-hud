package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Выбор одного из нескольких вариантов кнопками-сегментами (как «Файлы / Просмотр» экрана):
 * выбранный — нажатый сегмент. Значение читается у владельца каждый кадр.
 */
@Environment(EnvType.CLIENT)
final class Segmented<T> extends Widget
{
    private final List<T> values;
    private final List<Button> buttons = new ArrayList<>();
    private final Supplier<T> current;

    Segmented(List<T> values, Function<T, String> label, Supplier<T> current, Consumer<T> onPick)
    {
        this.values = List.copyOf(values);
        this.current = current;

        for (T value : this.values)
        {
            this.buttons.add(new Button(label.apply(value), Button.Style.SECONDARY, () -> onPick.accept(value)));
        }
    }

    Segmented<T> tooltip(String text)
    {
        for (Button button : this.buttons)
        {
            button.tooltip(text);
        }

        return this;
    }

    int preferredWidth(Font font)
    {
        int width = Ui.PAD_IN * Math.max(0, this.buttons.size() - 1);

        for (Button button : this.buttons)
        {
            width += button.preferredWidth(font);
        }

        return width;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        // Ширины — по подписям; не влезли — поровну (подпись обрежется «…», подсказка покажет)
        Font font = Kit.font();
        int gaps = Ui.PAD_IN * Math.max(0, this.buttons.size() - 1);
        int want = this.preferredWidth(font) - gaps;
        int room = Math.max(0, w - gaps);
        int bx = x;

        for (int i = 0; i < this.buttons.size(); ++i)
        {
            Button button = this.buttons.get(i);
            int bw = want <= room ? button.preferredWidth(font) : room / Math.max(1, this.buttons.size());

            if (i == this.buttons.size() - 1 && want <= room)
            {
                bw = x + w - bx;
            }

            button.setBounds(bx, y, bw, h);
            bx += bw + Ui.PAD_IN;
        }
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        T value = this.current.get();

        for (int i = 0; i < this.buttons.size(); ++i)
        {
            Button button = this.buttons.get(i);
            button.selected(Objects.equals(this.values.get(i), value));
            button.setEnabled(this.enabled);
            button.render(ctx, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! Kit.left(click))
        {
            return false;
        }

        for (Button button : this.buttons)
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
        for (Button button : this.buttons)
        {
            button.mouseReleased(click);
        }

        return false;
    }
}
