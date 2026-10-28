package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Кнопки форматов сеткой: 4 колонки в широком окне, 2 — в узком, 1 — совсем узко; колонки
 * равной ширины. Excel — главное действие (зелёная), Google Таблицы — вторая по важности (акцент),
 * Discord — цветом Discord ({@code Theme.DISCORD}): кнопки, которые чаще всего ищут глазами, видны
 * по цвету своего сервиса. Формат, выбранный в меню «Выгрузить ▾», нажат и срабатывает по Enter.
 */
@Environment(EnvType.CLIENT)
final class FormatGrid extends Widget
{
    private static final int[] COLUMN_CHOICES = {4, 2, 1};

    private final Map<ExportFormat, Button> buttons = new EnumMap<>(ExportFormat.class);
    private final List<ExportFormat> order = ExportFormat.grid();

    FormatGrid(Consumer<ExportFormat> onPick, @Nullable ExportFormat selected)
    {
        for (ExportFormat format : this.order)
        {
            Button.Style style = format == ExportFormat.XLSX || format == ExportFormat.GOOGLE
                    || format == ExportFormat.DISCORD ? Button.Style.PRIMARY : Button.Style.SECONDARY;
            Button button = new Button(format.label(), style, () -> onPick.accept(format)).tooltip(format.tooltip());

            if (format == ExportFormat.XLSX)
            {
                button.accent(Theme.GREEN);
            }
            else if (format == ExportFormat.DISCORD)
            {
                button.accent(Theme.DISCORD);
            }

            button.selected(format == selected);
            this.buttons.put(format, button);
        }
    }

    @Nullable Button button(@Nullable ExportFormat format)
    {
        return format == null ? null : this.buttons.get(format);
    }

    /**
     * Можно ли жать: {@code why} — причина «нельзя» (подсказка выключенной кнопки), {@code null} —
     * можно.
     */
    void update(@Nullable String why)
    {
        for (Map.Entry<ExportFormat, Button> entry : this.buttons.entrySet())
        {
            entry.getValue().setEnabled(why == null);
            entry.getValue().tooltip(why == null ? entry.getKey().tooltip() : why);
        }
    }

    private int columns(int width)
    {
        Font font = Kit.font();
        int widest = 0;

        for (Button button : this.buttons.values())
        {
            widest = Math.max(widest, button.preferredWidth(font));
        }

        for (int columns : COLUMN_CHOICES)
        {
            if (columns * widest + (columns - 1) * Ui.PAD_IN <= width)
            {
                return columns;
            }
        }

        return 1;
    }

    int height(int width)
    {
        int columns = this.columns(width);
        int rows = (this.order.size() + columns - 1) / columns;

        return rows * Ui.BUTTON_H + Math.max(0, rows - 1) * Ui.PAD_IN;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        int columns = this.columns(w);
        int cell = Math.max(0, (w - (columns - 1) * Ui.PAD_IN) / columns);

        for (int i = 0; i < this.order.size(); ++i)
        {
            int column = i % columns;
            int row = i / columns;
            // Последняя колонка добирает остаток деления — правый край ровный
            int bw = column == columns - 1 ? x + w - (x + column * (cell + Ui.PAD_IN)) : cell;
            this.buttons.get(this.order.get(i)).setBounds(x + column * (cell + Ui.PAD_IN),
                    y + row * (Ui.BUTTON_H + Ui.PAD_IN), bw, Ui.BUTTON_H);
        }
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        for (ExportFormat format : this.order)
        {
            this.buttons.get(format).render(ctx, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        for (ExportFormat format : this.order)
        {
            if (this.buttons.get(format).mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : this.buttons.values())
        {
            button.mouseReleased(click);
        }

        return false;
    }
}
