package com.aell.layerlist.schematics.gui.export;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.export.SheetPalette;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * «Оформление таблицы» — шаг между кнопкой Excel, Google Таблиц или таблицы команды и самой
 * выгрузкой: карточки палитр с образцом листа в её цветах. Выбор сразу пишется в
 * {@link SchematicsConfig#SHEETS_PALETTE} и остаётся выбранным в следующий раз, поэтому
 * обычная выгрузка — по-прежнему один Enter: «Выгрузить» — кнопка по умолчанию.
 */
@Environment(EnvType.CLIENT)
final class PaletteChooser
{
    private static final int WIDTH = 360;

    private PaletteChooser() {}

    static void open(Layers layers, ExportFormat format, Runnable export)
    {
        Modal modal = new Modal(layers, DialogText.t("palette.title"), Strings.t("close"));
        modal.width(WIDTH);
        modal.text(DialogText.t("palette.text"));

        Button go = new Button(DialogText.f("palette.go", format.label()), Button.Style.PRIMARY, () ->
        {
            modal.dismiss();
            export.run();
        });
        Cards cards = new Cards(() ->
        {
            modal.dismiss();
            export.run();
        });
        modal.row(cards, Cards.HEIGHT);
        modal.button(new Button(Strings.plain("layerlist.schematics.cancel"), Button.Style.SECONDARY, modal::cancel));
        modal.button(go);
        modal.defaultButton(go);
        modal.open();
    }

    private static void choose(SchematicsConfig.SheetsPalette value)
    {
        SchematicsConfig.SHEETS_PALETTE.setOptionListValue(value);
        // Как окно настроек схем: значение пишется в layerlist.json сразу
        new LayerListConfigs().save();
    }

    /** Карточки палитр в ряд; выбранная обведена акцентом. Клик — выбрать, двойной клик — выбрать и выгрузить. */
    private static final class Cards extends Widget
    {
        static final int HEIGHT = 92;
        private static final int LABEL_H = 14;

        private final List<SchematicsConfig.SheetsPalette> values = List.of(SchematicsConfig.SheetsPalette.values());
        private final Runnable export;

        Cards(Runnable export)
        {
            this.export = export;
        }

        private int cardW()
        {
            return (this.w - Ui.PAD_REL * (this.values.size() - 1)) / this.values.size();
        }

        private int cardX(int index)
        {
            return this.x + index * (this.cardW() + Ui.PAD_REL);
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            Font font = Kit.font();
            SchematicsConfig.SheetsPalette current = SchematicsConfig.sheetsPalette();
            int cw = this.cardW();

            for (int i = 0; i < this.values.size(); ++i)
            {
                SchematicsConfig.SheetsPalette value = this.values.get(i);
                int cx = this.cardX(i);
                boolean selected = value == current;
                boolean hover = Paint.hit(mouseX, mouseY, cx, this.y, cw, HEIGHT);

                if (Style.vanilla())
                {
                    // Ванильный стиль: карточка — кнопка Minecraft, выбранная и наведённая — подсвеченная
                    Vanilla.button(ctx, cx, this.y, cw, HEIGHT, true, selected || hover);
                }
                else
                {
                    Paint.card(ctx, cx, this.y, cw, HEIGHT, Ui.R_CTRL, hover ? Ui.C_ROW_HOVER : 0x00000000,
                            selected ? Ui.C_ACCENT : Ui.C_PANEL_EDGE);
                }

                sample(ctx, SheetPalette.of(value), cx + Ui.PAD_IN, this.y + Ui.PAD_IN, cw - 2 * Ui.PAD_IN,
                        HEIGHT - 2 * Ui.PAD_IN - LABEL_H);
                String label = (selected ? "● " : "") + value.getDisplayName();
                Paint.midText(ctx, font, Paint.fit(font, label, cw - 2 * Ui.PAD_IN), cx + Ui.PAD_IN,
                        this.y + HEIGHT - LABEL_H - Ui.PAD_MICRO, LABEL_H, Style.vanilla() ? (selected || hover ? Style.V_HOVER : Style.V_WHITE)
                                : selected ? Ui.C_TEXT : Ui.C_TEXT_2, false);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            if (! Kit.left(click))
            {
                return false;
            }

            for (int i = 0; i < this.values.size(); ++i)
            {
                if (Kit.leftHit(click, this.cardX(i), this.y, this.cardW(), HEIGHT))
                {
                    Vanilla.click();
                    choose(this.values.get(i));

                    if (doubleClick)
                    {
                        this.export.run();
                    }

                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Образец листа в цветах палитры: полоса заголовка, шапка, строки с чередованием (одна —
     * «Собираю», одна — «Собрано»), справа — блок «Кто сколько собрал» с никами и итогом и
     * золотой заголовок рамки ввода. Текст — полосками, чтобы образец читался при любом размере.
     */
    private static void sample(GuiContext ctx, SheetPalette p, int x, int y, int w, int h)
    {
        swatch(ctx, x, y, w, h, opaque(p.canvas()));

        int list = w * 3 / 5;
        int side = x + list + 3;
        int sideW = x + w - side - 2;
        int titleH = 7;

        // Полоса заголовка во всю ширину: название — цветом заголовка, подпись справа — цветом подписи
        swatch(ctx, x, y, w, titleH, opaque(p.titleFill()));
        swatch(ctx, x + 3, y + 3, list / 2, 2, opaque(p.titleText()));
        swatch(ctx, side, y + 3, sideW / 2, 1, opaque(p.metaText()));

        // Список: шапка и строки
        int top = y + titleH + 2;
        swatch(ctx, x + 2, top, list - 2, 6, opaque(p.headFill()));
        swatch(ctx, x + 5, top + 2, list / 3, 2, opaque(p.headText()));
        int rows = Math.max(3, (y + h - 2 - (top + 7)) / 6);

        for (int r = 0; r < rows; ++r)
        {
            int ry = top + 7 + r * 6;

            if (ry + 5 > y + h - 1)
            {
                break;
            }

            int fill = r == 1 ? p.takenFill() : r == 3 ? p.doneFill() : (r % 2 == 0 ? p.row() : p.rowAlt());
            int text = r == 1 ? p.takenText() : r == 3 ? p.doneText() : p.text();
            swatch(ctx, x + 2, ry, list - 2, 5, opaque(fill));
            swatch(ctx, x + 2, ry + 5, list - 2, 1, opaque(p.border()));
            swatch(ctx, x + 4, ry + 2, 3, 1, opaque(p.textMuted()));
            swatch(ctx, x + 10, ry + 2, list / 3 + (r * 7) % 9, 1, opaque(text));
            swatch(ctx, x + list - 18, ry + 1, 10, 3, opaque(p.nick(r)));
        }

        // Боковой блок: заголовок, подписи, ники, итог
        swatch(ctx, side, top, sideW, 6, opaque(p.headFill()));
        swatch(ctx, side + 2, top + 2, sideW / 2, 2, opaque(p.subText()));

        for (int n = 0; n < 3; ++n)
        {
            int ny = top + 7 + n * 6;
            swatch(ctx, side, ny, sideW, 5, opaque(p.panel()));
            swatch(ctx, side, ny, sideW / 3, 5, opaque(p.nick(n * 5)));
            swatch(ctx, side + sideW / 3 + 2, ny + 2, sideW / 3, 1, opaque(p.text()));
        }

        int totalY = top + 7 + 3 * 6;
        swatch(ctx, side, totalY, sideW, 5, opaque(p.total()));
        swatch(ctx, side + 2, totalY + 2, sideW / 2, 1, opaque(p.sumText()));

        // Рамка ввода ников — заголовок её цветом, под ним пунктирное поле
        int inputY = totalY + 8;

        if (inputY + 9 <= y + h - 1)
        {
            swatch(ctx, side, inputY, sideW, 4, opaque(p.inputHeadFill()));
            swatch(ctx, side, inputY + 5, sideW, 4, opaque(p.inputFill()));
            swatchBorder(ctx, side, inputY + 5, sideW, 4, opaque(p.inputEdge()));
        }
    }

        /** Цвет образца — настоящий цвет палитры: идёт мимо {@link Paint}, чтобы стиль его не перекрасил. */
    private static void swatch(GuiContext ctx, int x, int y, int w, int h, int color)
    {
        if (w > 0 && h > 0)
        {
            ctx.fill(x, y, x + w, y + h, color);
        }
    }

    private static void swatchBorder(GuiContext ctx, int x, int y, int w, int h, int color)
    {
        swatch(ctx, x, y, w, 1, color);
        swatch(ctx, x, y + h - 1, w, 1, color);
        swatch(ctx, x, y + 1, 1, h - 2, color);
        swatch(ctx, x + w - 1, y + 1, 1, h - 2, color);
    }

private static int opaque(int rgb)
    {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }
}
