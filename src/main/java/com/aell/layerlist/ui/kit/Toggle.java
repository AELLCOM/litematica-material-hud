package com.aell.layerlist.ui.kit;

import com.aell.layerlist.shaded.ui.Anim;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Тумблер экрана настроек: дорожка {@link Ui#SWITCH_W}×{@link Ui#SWITCH_H}, бегунок с анимацией
 * {@link Ui#ANIM_KNOB_MS} и плавным переходом {@link Ui#C_OFF} → {@link Ui#C_ON}, справа — «Вкл/Выкл»
 * (строки aell-ui) или своя подпись (0.0.27: так тумблеры заменили флажки вкладки «Схемы» — «во всех
 * папках», «Только недостающее», параметры выгрузки, размещения в карточке; кликабельна вся строка).
 * Значение читается из источника каждый кадр — переключатель не расходится с опцией, если её поменяли
 * где-то ещё.
 *
 * <p>27.11.2026: {@link #swatch} — цветная метка после дорожки, как у опций экрана настроек (цвет категории,
 * к которой относится тумблер), и {@link #compact} — без подписи, когда в полосе инструментов тесно: остаются
 * дорожка и метка, подпись уходит в подсказку.
 */
@Environment(EnvType.CLIENT)
public final class Toggle extends Widget
{
    /** Ширина дорожки: под неё выравнивают текст, который стоит под подписью тумблера. */
    public static final int TRACK = Ui.SWITCH_W;

    private final BooleanSupplier value;
    private final Consumer<Boolean> onChange;
    /** Своя подпись; {@code null} — «Вкл/Выкл» по значению. */
    private final @Nullable String label;
    private final Anim knob = new Anim(Ui.ANIM_KNOB_MS);
    private final Anim hoverAnim = new Anim(Ui.ANIM_HOVER_MS);
    private @Nullable String tooltip;
    /** Рамка акцентом вокруг всего тумблера: «обрати внимание» (у выключенного — подсказка объясняет почему). */
    private boolean highlight;
    /** На сколько пикселей GUI рамка подсветки выходит за тумблер по бокам. */
    private static final int HIGHLIGHT_OUT = 3;
    /** Цвет метки после дорожки; 0 — метки нет. */
    private int swatch;
    /** Без подписи: дорожка и метка, подпись — в подсказке. */
    private boolean compact;

    /** Тумблер опции: справа «Вкл/Выкл». */
    public Toggle(BooleanSupplier value, Consumer<Boolean> onChange)
    {
        this(null, value, onChange);
    }

    /** Тумблер с подписью справа (вместо «Вкл/Выкл»): подпись подсвечивается под мышью. */
    public Toggle(@Nullable String label, BooleanSupplier value, Consumer<Boolean> onChange)
    {
        this.label = label;
        this.value = value;
        this.onChange = onChange;
    }

    public Toggle tooltip(@Nullable String text)
    {
        this.tooltip = text;
        return this;
    }

    /** Рамка акцентом вокруг тумблера, как у выделенных элементов управления. */
    public Toggle highlight(boolean on)
    {
        this.highlight = on;
        return this;
    }

    /** Цветная метка {@link Ui#SWATCH} между дорожкой и подписью; {@code 0} — без метки. */
    public Toggle swatch(int color)
    {
        this.swatch = color;
        return this;
    }

    /** Без подписи (она остаётся в подсказке): для тесной полосы инструментов. Ширину отдаёт {@link #preferredWidth}. */
    public Toggle compact(boolean on)
    {
        this.compact = on;
        return this;
    }

    public boolean compact()
    {
        return this.compact;
    }

    public int preferredWidth(Font font)
    {
        if (this.compact)
        {
            return TRACK + (this.swatch != 0 ? Ui.PAD_IN + Ui.SWATCH : 0);
        }

        int text = this.label != null ? font.width(this.label)
                : Math.max(font.width(Strings.onOff(true)), font.width(Strings.onOff(false)));

        return TRACK + Ui.PAD_IN + (this.swatch != 0 ? Ui.SWATCH_COL : 0) + text;
    }

    /**
     * Дорожка с бегунком по центру строки высотой {@code h}: {@code knob} — положение бегунка (0 — выкл.,
     * 1 — вкл.), {@code hover} — подсветка под мышью (0…1). Для ячеек таблицы, где подпись — заголовок колонки.
     */
    public static void drawTrack(GuiContext ctx, int x, int y, int h, float knob, float hover, boolean enabled)
    {
        if (Style.vanilla())
        {
            // Ванильная галочка по центру области дорожки; раскладка та же
            Vanilla.checkbox(ctx, x, y, Ui.SWITCH_W, h, knob >= 0.5F, hover > 0.01F);
            return;
        }

        int base = Paint.mix(Paint.mix(Ui.C_OFF, Ui.C_ON, knob), Ui.C_TEXT, hover * 0.25F);
        int fill = enabled ? base : Ui.alpha(base, Ui.A_DISABLED);
        int sy = y + (h - Ui.SWITCH_H) / 2;

        Paint.round(ctx, x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, Ui.alpha(fill, Ui.A_TRACK_OFF));
        Paint.roundBorder(ctx, x, sy, Ui.SWITCH_W, Ui.SWITCH_H, Ui.R_PILL, fill);
        Paint.round(ctx, x + Math.round((Ui.SWITCH_W - Ui.SWITCH_KNOB) * knob), sy, Ui.SWITCH_KNOB, Ui.SWITCH_H,
                Ui.R_PILL, fill);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible || this.w <= 0)
        {
            return;
        }

        Font font = Kit.font();
        boolean on = this.value.getAsBoolean();
        boolean over = this.contains(mouseX, mouseY);
        float k = this.knob.update(on);
        float hover = this.label != null ? this.hoverAnim.update(over && this.enabled) : 0.0F;

        if (this.highlight)
        {
            // Рамка — с отступом наружу: дорожка стоит у левого края, подпись — у правого, вплотную к ним тесно
            Paint.roundBorder(ctx, this.x - HIGHLIGHT_OUT, this.y - 1, this.w + 2 * HIGHLIGHT_OUT, this.h + 2, Ui.R_CTRL,
                    Ui.alpha(Ui.C_ACCENT, 0.7F));
        }

        drawTrack(ctx, this.x, this.y, this.h, k, hover, this.enabled);

        int labelX = this.x + TRACK + Ui.PAD_IN;

        if (this.swatch != 0)
        {
            Paint.rect(ctx, labelX, this.y + (this.h - Ui.SWATCH) / 2, Ui.SWATCH, Ui.SWATCH,
                    this.enabled ? this.swatch : Ui.alpha(this.swatch, Ui.A_SWATCH_OFF));
            labelX += Ui.SWATCH_COL;
        }

        String text = this.label != null ? this.label : Strings.onOff(on);
        String shown = this.compact ? "" : Paint.fit(font, text, this.x + this.w - labelX);

        if (Style.vanilla())
        {
            Paint.text(ctx, font, shown, labelX, this.y + (this.h - Ui.GLYPH_H) / 2,
                    this.enabled ? Style.V_WHITE : Style.V_GRAY, true);
        }
        else
        {
            Paint.midText(ctx, font, shown, labelX, this.y, this.h,
                ! this.enabled ? Ui.C_TEXT_OFF : Paint.mix(Ui.C_TEXT_2, Ui.C_TEXT, hover), false);
        }

        if (over)
        {
            String tip = this.tooltip != null ? this.tooltip : shown.equals(text) ? null : text;

            if (tip != null)
            {
                Tooltip.offer(this, tip);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (! this.contains(click.x(), click.y()) || ! Kit.left(click))
        {
            return false;
        }

        if (this.enabled)
        {
            Vanilla.click();
            this.onChange.accept(! this.value.getAsBoolean());
        }

        return true;
    }
}
