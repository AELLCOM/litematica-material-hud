package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Строка состояния окна: текст с переносом цветом смысла (прогресс, готово, ошибка) и, по желанию,
 * кнопки действий («Открыть файл», «Показать в папке»…). Кнопки встают следом за текстом в его
 * последней строке, если влезают, иначе — строкой ниже (и дальше с переносом). Нет текста — высота 0,
 * и окно строку не показывает. Текст берётся у владельца каждый кадр.
 *
 * <p>С 0.0.27 итог выгрузки живёт здесь, а не тостом: тост ложился поверх подвала окна.
 */
@Environment(EnvType.CLIENT)
final class StatusLine extends Widget
{
    /** Что показать; {@code color} — токен смысла ({@code Theme}/{@code Ui}); действия — кнопками строки. */
    record Message(String text, int color, List<Toasts.Action> actions)
    {
        Message
        {
            actions = List.copyOf(actions);
        }

        Message(String text, int color)
        {
            this(text, color, List.of());
        }
    }

    /** Строки текста и кнопки при данной ширине; координаты — от левого верхнего угла строки. */
    private record Layout(List<String> lines, int[] lineY, List<int[]> boxes, int height) {}

    private final Supplier<@Nullable Message> source;

    StatusLine(Supplier<@Nullable Message> source)
    {
        this.source = source;
    }

    int height(int width)
    {
        Message message = this.message();

        return message == null ? 0 : layout(message, width).height();
    }

    /** Что показано сейчас ({@code null} — строка скрыта). */
    @Nullable Message message()
    {
        Message message = this.source.get();

        return message == null || message.text().isEmpty() ? null : message;
    }

    /**
     * Где кнопка действия с подписью {@code label} на экране: {@code {x, y, w, h}}; {@code null} — такой нет
     * или строка скрыта.
     */
    int @Nullable [] actionBox(String label)
    {
        Message message = this.message();

        if (message == null || this.h <= 0)
        {
            return null;
        }

        Layout layout = layout(message, this.w);

        for (int i = 0; i < message.actions().size(); ++i)
        {
            if (message.actions().get(i).label().equals(label))
            {
                int[] box = layout.boxes().get(i);
                return new int[] { this.x + box[0], this.y + box[1], box[2], Ui.HEAD_BTN_H };
            }
        }

        return null;
    }

    private static Layout layout(Message message, int width)
    {
        Font font = Kit.font();
        List<String> lines = Kit.wrap(font, message.text(), width);
        int[] lineY = new int[lines.size()];
        List<int[]> boxes = new ArrayList<>(message.actions().size());

        for (int i = 0; i < lines.size(); ++i)
        {
            lineY[i] = i * Ui.TIP_LINE;
        }

        if (message.actions().isEmpty())
        {
            return new Layout(lines, lineY, boxes, lines.size() * Ui.TIP_LINE);
        }

        int last = lines.size() - 1;
        int lastW = last >= 0 ? font.width(lines.get(last)) : 0;
        int firstW = actionWidth(font, message.actions().getFirst(), width);
        // Влезает первая кнопка за текстом — ряд кнопок начинается в последней строке текста
        boolean inline = last >= 0 && lastW + Ui.PAD_REL + firstW <= width;
        int rowY = last < 0 ? 0 : inline ? last * Ui.TIP_LINE : lines.size() * Ui.TIP_LINE + Ui.PAD_MICRO;
        int ax = inline ? lastW + Ui.PAD_REL : 0;

        if (inline)
        {
            // Ряд кнопок выше строки текста — текст по его середине
            lineY[last] = rowY + (Ui.HEAD_BTN_H - Ui.TIP_LINE) / 2;
        }

        for (Toasts.Action action : message.actions())
        {
            int aw = actionWidth(font, action, width);

            if (ax > 0 && ax + aw > width)
            {
                rowY += Ui.HEAD_BTN_H + Ui.PAD_MICRO;
                ax = 0;
            }

            boxes.add(new int[] { ax, rowY, aw });
            ax += aw + Ui.PAD_IN;
        }

        return new Layout(lines, lineY, boxes, rowY + Ui.HEAD_BTN_H);
    }

    private static int actionWidth(Font font, Toasts.Action action, int width)
    {
        return Math.min(font.width(action.label()) + Ui.HEAD_BTN_PAD_X * 2, Math.max(0, width));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Message message = this.message();

        if (message == null || this.h <= 0)
        {
            return;
        }

        Font font = Kit.font();
        Layout layout = layout(message, this.w);

        for (int i = 0; i < layout.lines().size(); ++i)
        {
            Paint.text(ctx, font, layout.lines().get(i), this.x, this.y + layout.lineY()[i] + 1, message.color(), false);
        }

        // Как кнопки действий тоста: рамка акцента, наведение — подсветка
        for (int i = 0; i < message.actions().size(); ++i)
        {
            int[] box = layout.boxes().get(i);
            int bx = this.x + box[0];
            int by = this.y + box[1];
            boolean over = Paint.hit(mouseX, mouseY, bx, by, box[2], Ui.HEAD_BTN_H);

            // Ванильный стиль: кнопка действия — обычная кнопка Minecraft с белой надписью
            if (Style.vanilla())
            {
                Vanilla.button(ctx, bx, by, box[2], Ui.HEAD_BTN_H, true, over);
                Vanilla.buttonLabel(ctx, font,
                        Paint.fit(font, message.actions().get(i).label(), box[2] - Ui.HEAD_BTN_PAD_X * 2),
                        bx, by, box[2], Ui.HEAD_BTN_H, true);
                continue;
            }

            if (over)
            {
                Paint.round(ctx, bx, by, box[2], Ui.HEAD_BTN_H, Ui.R_CTRL, Ui.C_ROW_HOVER);
            }

            Paint.roundBorder(ctx, bx, by, box[2], Ui.HEAD_BTN_H, Ui.R_CTRL, Ui.alpha(Ui.C_ACCENT, 0.7F));
            Paint.midText(ctx, font, Paint.fit(font, message.actions().get(i).label(), box[2] - Ui.HEAD_BTN_PAD_X * 2),
                    bx + Ui.HEAD_BTN_PAD_X, by, Ui.HEAD_BTN_H, over ? Ui.C_TEXT : Ui.C_ACCENT, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        Message message = this.message();

        if (message == null || this.h <= 0 || message.actions().isEmpty() || ! Kit.left(click))
        {
            return false;
        }

        Layout layout = layout(message, this.w);

        for (int i = 0; i < message.actions().size(); ++i)
        {
            int[] box = layout.boxes().get(i);

            if (Kit.leftHit(click, this.x + box[0], this.y + box[1], box[2], Ui.HEAD_BTN_H))
            {
                Vanilla.click();
                message.actions().get(i).run().run();
                return true;
            }
        }

        return false;
    }
}
