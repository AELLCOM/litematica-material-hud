package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

/**
 * Ячейка «Предмет» таблицы «Материалы» с заменой блоков (0.0.27): название, у заменённой строки —
 * пометка «← Дубовые доски» цветом изменённого ({@link Ui#C_CHANGED}) и кнопка отката справа — как
 * сброс у строк настроек aell-ui: квадрат {@link Ui#RESET_W} в рамке {@link Ui#C_DIVIDER} (под мышью —
 * {@link Ui#C_CHANGED}) со стрелкой {@link Paint#resetGlyph}. Под мышью у строки, которую можно
 * заменить, — шеврон «выбрать» ({@link Glyphs#CHEVRON_DOWN}), как у выпадающих кнопок набора. У отключённого
 * блока та же кнопка включает его обратно, а название потухшее и зачёркнутое. У блока, отключённого на слоях
 * (1.0.12), — пометка «· откл. на слоях 3, 5» тем же цветом, кнопка возвращает его на эти слои.
 *
 * <p>У {@code Table.CellClick} нет координат ячейки, поэтому ячейка запоминает, где в этом кадре легли
 * она и её кнопка отката: по ним панель решает, куда пришёлся клик, а харнесс — куда кликать.
 */
@Environment(EnvType.CLIENT)
public final class ReplaceCell
{
    private static final String ARROW = " ← ";

    private final Map<String, int[]> cells = new HashMap<>();
    private final Map<String, int[]> reverts = new HashMap<>();

    /** Начало кадра таблицы: прошлые места ячеек недействительны (прокрутка, раскладка). */
    public void beginFrame()
    {
        this.cells.clear();
        this.reverts.clear();
    }

    /**
     * Нарисовать ячейку.
     *
     * @param replaceable строку можно заменить (шеврон под мышью)
     * @param tip         подсказка ячейки (мимо кнопки отката)
     * @param revertTip   подсказка кнопки отката
     * @param layersNote  пометка об отключении на слоях («откл. на слоях 3, 5»); {@code null} — нет
     */
    public void render(GuiContext ctx, MaterialLine line, boolean replaceable, int x, int y, int w, int h,
            boolean hovered, int mouseX, int mouseY, @Nullable String tip, @Nullable String revertTip,
            @Nullable String layersNote)
    {
        Font font = Kit.font();
        String key = line.key();
        this.cells.put(key, new int[] { x, y, w, h });

        int left = x + Ui.PAD_IN;
        int right = x + w - Ui.PAD_IN;
        boolean overRevert = false;
        List<String> from = line.replacedFrom();

        // Золотая кнопка: откат замены или, у отключённого блока, «включить обратно»
        if ((! from.isEmpty() || line.disabled() || layersNote != null) && right - left >= Ui.RESET_W)
        {
            int rx = right - Ui.RESET_W;
            int ry = y + (h - Ui.RESET_W) / 2;
            overRevert = Paint.hit(mouseX, mouseY, rx, ry, Ui.RESET_W, Ui.RESET_W);

            if (Style.vanilla())
            {
                // Ванильная кнопка, значок отката поверх
                Vanilla.button(ctx, rx, ry, Ui.RESET_W, Ui.RESET_W, true, overRevert);
            }
            else
            {
                Paint.roundBorder(ctx, rx, ry, Ui.RESET_W, Ui.RESET_W, Ui.R_CTRL, overRevert ? Ui.C_CHANGED : Ui.C_DIVIDER);
            }

            Paint.resetGlyph(ctx, rx + (Ui.RESET_W - Ui.ICON_W) / 2, ry + (Ui.RESET_W - Ui.ICON_W) / 2, Ui.C_CHANGED);
            this.reverts.put(key, new int[] { rx, ry, Ui.RESET_W, Ui.RESET_W });
            right = rx - Ui.PAD_REL;

            if (overRevert)
            {
                Tooltip.offer(List.of("revert", key), revertTip);
            }
        }

        if (hovered && replaceable && ! overRevert && ! line.disabled())
        {
            int gw = Glyphs.width(Glyphs.CHEVRON_DOWN);

            if (right - left > gw + Ui.PAD_REL)
            {
                Glyphs.draw(ctx, Glyphs.CHEVRON_DOWN, right - gw, y + (h - Glyphs.height(Glyphs.CHEVRON_DOWN)) / 2,
                        Ui.C_TEXT_2);
                right -= gw + Ui.PAD_REL;
            }
        }

        int room = Math.max(0, right - left);
        String name = Paint.fit(font, line.name(), room);
        Paint.midText(ctx, font, name, left, y, h, line.disabled() ? Ui.C_TEXT_OFF : Ui.C_TEXT, false);

        int used = font.width(name);

        if (line.disabled())
        {
            // Потухшее и зачёркнуто: блок в схеме есть, но сейчас она его не строит
            Paint.rect(ctx, left, y + h / 2, used, 1, Ui.C_TEXT_OFF);
        }
        boolean cut = ! name.equals(line.name());

        if (! from.isEmpty() && ! cut)
        {
            String suffix = ARROW + String.join(", ", from);
            String shown = Paint.fit(font, suffix, room - used);

            // Одна стрелка без названия ничего не говорит — тогда пометки нет, она есть в подсказке
            if (shown.length() > ARROW.length() + 1)
            {
                Paint.midText(ctx, font, shown, left + used, y, h, Ui.C_CHANGED, false);
                used += font.width(shown);
                cut = ! shown.equals(suffix);
            }
        }

        if (layersNote != null && ! cut)
        {
            String suffix = " · " + layersNote;
            String shown = Paint.fit(font, suffix, room - used);

            if (shown.length() > 4)
            {
                Paint.midText(ctx, font, shown, left + used, y, h, Ui.C_CHANGED, false);
            }
        }

        if (hovered && ! overRevert)
        {
            Tooltip.offer(List.of("item", key), tip);
        }
    }

    /** Клик по кнопке отката строки {@code key} (по месту в последнем кадре). */
    public boolean overRevert(String key, double mouseX, double mouseY)
    {
        int[] box = this.reverts.get(key);

        return box != null && Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), box[0], box[1], box[2], box[3]);
    }

    /** Где в последнем кадре легла ячейка строки: {x, y, w, h}; {@code null} — строка не видна. */
    public int @Nullable [] cellBox(String key)
    {
        int[] box = this.cells.get(key);
        return box == null ? null : box.clone();
    }

    /** Кнопка отката строки в последнем кадре: {x, y, w, h}; {@code null} — у строки её нет или не видна. */
    public int @Nullable [] revertBox(String key)
    {
        int[] box = this.reverts.get(key);
        return box == null ? null : box.clone();
    }
}
