package com.aell.ui.kit;

import com.aell.ui.Paint;
import com.aell.ui.Style;
import com.aell.ui.Ui;
import com.aell.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Подсказка с задержкой {@link Ui#TIP_DELAY_MS}, как в экране настроек. Виджеты во время
 * отрисовки «предлагают» подсказку для того, что под курсором ({@link #offer}); экран в начале кадра
 * зовёт {@link #beginFrame()}, а в самом конце, поверх всего, — {@link #render}. Побеждает последнее
 * предложение кадра: попапы рисуются позже панелей и перекрывают их подсказки.
 *
 * <p>Один экран за раз, поэтому состояние статическое: так подсказку может предложить любой
 * виджет, не таская ссылку на экран.
 */
@Environment(EnvType.CLIENT)
public final class Tooltip
{
    private static @Nullable Object candidate;
    private static List<String> candidateLines = List.of();
    private static int candidateColor = Ui.C_TEXT;

    private static @Nullable Object shown;
    private static long since;

    private Tooltip() {}

    public static void beginFrame()
    {
        candidate = null;
        candidateLines = List.of();
    }

    /** Подсказка для цели {@code key} (сравнение по {@code equals}); пустой текст — не показывать. */
    public static void offer(Object key, @Nullable String text)
    {
        offer(key, text, Ui.C_TEXT);
    }

    public static void offer(Object key, @Nullable String text, int color)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }

        candidate = key;
        candidateLines = List.of(text);
        candidateColor = color;
    }

    /** Несколько абзацев: первый — цветом {@code color}, остальные приглушённо. */
    public static void offerLines(Object key, List<String> lines, int color)
    {
        if (lines.isEmpty())
        {
            return;
        }

        candidate = key;
        candidateLines = List.copyOf(lines);
        candidateColor = color;
    }

    /** Сбросить ожидание (после клика подсказка не должна выскакивать сразу же). */
    public static void reset()
    {
        shown = null;
        since = Util.getMillis();
    }

    public static void render(GuiContext ctx, Font font, int mouseX, int mouseY, int screenW, int screenH)
    {
        if (! Objects.equals(candidate, shown))
        {
            shown = candidate;
            since = Util.getMillis();
        }

        if (candidate == null || Util.getMillis() - since < Ui.TIP_DELAY_MS)
        {
            return;
        }

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int limit = Ui.TIP_MAX_W - Ui.TIP_PAD * 2;

        for (int i = 0; i < candidateLines.size(); ++i)
        {
            for (String piece : Kit.wrap(font, candidateLines.get(i), limit))
            {
                lines.add(piece);
                colors.add(i == 0 ? candidateColor : Ui.C_TEXT_2);
            }
        }

        int w = 0;

        for (String line : lines)
        {
            w = Math.max(w, font.width(line));
        }

        w += Ui.TIP_PAD * 2;
        int h = lines.size() * Ui.TIP_LINE + Ui.TIP_PAD * 2;

        int x = mouseX + Ui.PAD_REL + w <= screenW ? mouseX + Ui.PAD_REL : mouseX - Ui.PAD_REL - w;
        int y = mouseY + Ui.PAD_REL + h <= screenH ? mouseY + Ui.PAD_REL : mouseY - h;

        x = Ui.clamp(x, 0, Math.max(0, screenW - w));
        y = Ui.clamp(y, 0, Math.max(0, screenH - h));

        ctx.elementUp();
        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, Ui.R_PLATE, Ui.C_CHROME, Ui.C_DIVIDER);
        }

        for (int i = 0; i < lines.size(); ++i)
        {
            int color = Style.vanilla() && colors.get(i) == Ui.C_TEXT_2 ? Style.V_WHITE : colors.get(i);
            Paint.text(ctx, font, lines.get(i), x + Ui.TIP_PAD, y + Ui.TIP_PAD + i * Ui.TIP_LINE, color, false);
        }
    }
}
