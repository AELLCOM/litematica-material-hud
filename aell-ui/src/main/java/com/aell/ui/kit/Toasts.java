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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Всплывающие сообщения экрана — вместо сообщений в чат: стопка карточек в правом нижнем углу
 * (над подвалом), каждая — цветная полоска смысла слева, текст с переносом и, по желанию, кнопки
 * действий («Открыть файл», «Показать в папке»). Исчезают сами (с действиями — дольше), пока
 * курсор над карточкой — не исчезают. Крестик закрывает сразу. Время — {@link Util#getMillis()}.
 * Кнопки действий, не влезшие в строку, переносятся на следующую.
 *
 * <p>Окно, поверх которого тостам не место (окно выгрузки: стопка в правом нижнем углу ложилась на его
 * подвал), берёт стопку себе ({@link #claim}): пока она взята, тостов нет — не рисуются, не ловят мышь
 * ({@link #contains}, {@link #mouseClicked}), а итоги окно показывает у себя. Стопка та же — после
 * {@link #unclaim} тосты снова видны (кто успел истечь, пока окно было открыто, исчезает сразу).
 */
@Environment(EnvType.CLIENT)
public final class Toasts
{
    /** Кнопка карточки; {@code closes} — после нажатия карточка уходит сама («Отменить»: второй раз жать нечего). */
    public record Action(String label, Runnable run, boolean closes)
    {
        public Action(String label, Runnable run)
        {
            this(label, run, false);
        }

        public static Action once(String label, Runnable run)
        {
            return new Action(label, run, true);
        }
    }

    private static final int WIDTH = 240;
    private static final int MAX = 4;
    private static final long LIFE_MS = 5000L;
    private static final long LIFE_ACTIONS_MS = 9000L;
    private static final long FADE_MS = 300L;
    private static final int BAR = 2;

    private static final class Toast
    {
        private final String text;
        private final int color;
        private final List<Action> actions;
        private final long life;
        private long born;
        private int x;
        private int y;
        private int w;
        private int h;
        private List<String> lines = List.of();
        private final List<int[]> actionBoxes = new ArrayList<>();

        private Toast(String text, int color, List<Action> actions)
        {
            this.text = text;
            this.color = color;
            this.actions = actions;
            this.life = actions.isEmpty() ? LIFE_MS : LIFE_ACTIONS_MS;
            this.born = Util.getMillis();
        }
    }

    private final List<Toast> toasts = new ArrayList<>();
    private @Nullable Object owner;
    private int right;
    private int bottom;
    private int screenW;

    /** Правый нижний угол стопки (обычно — над подвалом у правого края). */
    public void setAnchor(int right, int bottom, int screenW)
    {
        this.right = right;
        this.bottom = bottom;
        this.screenW = screenW;
    }

    public void show(String text, int color, Action... actions)
    {
        this.toasts.add(new Toast(text, color, List.of(actions)));
        Vanilla.toastIn();

        while (this.toasts.size() > MAX)
        {
            this.toasts.removeFirst();
        }
    }

    public void clear()
    {
        this.toasts.clear();
    }

    /** Спрятать стопку, пока открыт попап {@code owner} (до {@link #unclaim} с ним же). */
    public void claim(Object owner)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Вернуть стопку экрану; чужой {@code owner} ничего не меняет. */
    public void unclaim(Object owner)
    {
        if (this.owner == owner)
        {
            this.owner = null;
        }
    }

    private void layout(Font font)
    {
        int w = Math.min(WIDTH, Math.max(0, this.screenW - Ui.OUTER * 2));
        int textW = w - BAR - Ui.PAD_REL * 2 - Glyphs.width(Glyphs.CROSS) - Ui.PAD_IN;
        int y = this.bottom;

        for (int i = this.toasts.size() - 1; i >= 0; --i)
        {
            Toast toast = this.toasts.get(i);
            toast.lines = Kit.wrap(font, toast.text, textW);
            toast.w = w;

            // Кнопки — строками: не влезла в строку — на следующую (иначе вылезла бы за карточку)
            int actionsW = Math.max(0, w - BAR - Ui.PAD_REL * 2);
            List<int[]> places = new ArrayList<>(toast.actions.size());
            int ax = 0;
            int actionRows = toast.actions.isEmpty() ? 0 : 1;

            for (Action action : toast.actions)
            {
                int aw = Math.min(font.width(action.label) + Ui.HEAD_BTN_PAD_X * 2, actionsW);

                if (ax > 0 && ax + aw > actionsW)
                {
                    ++actionRows;
                    ax = 0;
                }

                places.add(new int[] { ax, actionRows - 1, aw });
                ax += aw + Ui.PAD_IN;
            }

            toast.h = Ui.PAD_REL * 2 + toast.lines.size() * Ui.TIP_LINE + actionRows * (Ui.PAD_IN + Ui.HEAD_BTN_H);
            toast.x = this.right - w;
            toast.y = y - toast.h;
            y = toast.y - Ui.PAD_IN;

            toast.actionBoxes.clear();
            int actionsY = toast.y + Ui.PAD_REL + toast.lines.size() * Ui.TIP_LINE + Ui.PAD_IN;

            for (int[] place : places)
            {
                toast.actionBoxes.add(new int[] { toast.x + BAR + Ui.PAD_REL + place[0],
                        actionsY + place[1] * (Ui.HEAD_BTN_H + Ui.PAD_IN), place[2], Ui.HEAD_BTN_H });
            }
        }
    }

    public boolean contains(double mouseX, double mouseY)
    {
        if (this.owner != null)
        {
            return false;
        }

        for (Toast toast : this.toasts)
        {
            if (Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), toast.x, toast.y, toast.w, toast.h))
            {
                return true;
            }
        }

        return false;
    }

    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (this.owner != null || this.toasts.isEmpty())
        {
            return;
        }

        Font font = Kit.font();
        long now = Util.getMillis();

        // Над карточкой время стоит: начинаем отсчёт заново, пока игрок читает
        this.layout(font);

        for (Toast toast : this.toasts)
        {
            if (Paint.hit(mouseX, mouseY, toast.x, toast.y, toast.w, toast.h))
            {
                toast.born = now;
            }
        }

        // Ушло по времени — звук ухода (ванильный стиль)
        if (this.toasts.removeIf(toast -> now - toast.born > toast.life))
        {
            Vanilla.toastOut();
        }

        this.layout(font);

        for (Toast toast : this.toasts)
        {
            long left = toast.life - (now - toast.born);
            float alpha = left < FADE_MS ? Math.max(0.0F, left / (float) FADE_MS) : 1.0F;

            ctx.elementUp();

            if (Style.vanilla())
            {
                Vanilla.toast(ctx, toast.x, toast.y, toast.w, toast.h, alpha);
            }
            else
            {
                Paint.card(ctx, toast.x, toast.y, toast.w, toast.h, Ui.R_PLATE, Ui.alpha(Ui.C_CHROME, alpha),
                        Ui.alpha(Ui.C_PANEL_EDGE, alpha));
            }

            Paint.rect(ctx, toast.x + Ui.BORDER, toast.y + Ui.R_PLATE, BAR, toast.h - Ui.R_PLATE * 2,
                    Ui.alpha(toast.color, alpha));

            int ty = toast.y + Ui.PAD_REL;

            for (String line : toast.lines)
            {
                Paint.text(ctx, font, line, toast.x + BAR + Ui.PAD_REL, ty, Ui.alpha(Ui.C_TEXT, alpha), false);
                ty += Ui.TIP_LINE;
            }

            int cx = toast.x + toast.w - Ui.PAD_REL - Glyphs.width(Glyphs.CROSS);
            boolean overClose = Paint.hit(mouseX, mouseY, cx - Ui.PAD_MICRO, toast.y + Ui.PAD_MICRO,
                    Glyphs.width(Glyphs.CROSS) + Ui.PAD_IN, Glyphs.height(Glyphs.CROSS) + Ui.PAD_IN);
            Glyphs.draw(ctx, Glyphs.CROSS, cx, toast.y + Ui.PAD_REL,
                    Ui.alpha(overClose ? Ui.C_TEXT : Ui.C_TEXT_3, alpha));

            for (int i = 0; i < toast.actions.size(); ++i)
            {
                int[] box = toast.actionBoxes.get(i);
                boolean over = Paint.hit(mouseX, mouseY, box[0], box[1], box[2], box[3]);

                if (Style.vanilla())
                {
                    // Кнопка действия — ванильная кнопка, надпись белая с тенью
                    Vanilla.button(ctx, box[0], box[1], box[2], box[3], true, over);
                    Paint.centered(ctx, font, Paint.fit(font, toast.actions.get(i).label, box[2] - Ui.HEAD_BTN_PAD_X * 2),
                            box[0] + box[2] / 2, box[1] + (box[3] - 8) / 2, Ui.alpha(Style.V_WHITE, alpha));
                    continue;
                }

                if (over)
                {
                    Paint.round(ctx, box[0], box[1], box[2], box[3], Ui.R_CTRL, Ui.C_ROW_HOVER);
                }

                Paint.roundBorder(ctx, box[0], box[1], box[2], box[3], Ui.R_CTRL, Ui.alpha(Ui.C_ACCENT, alpha * 0.7F));
                Paint.midText(ctx, font, Paint.fit(font, toast.actions.get(i).label, box[2] - Ui.HEAD_BTN_PAD_X * 2),
                        box[0] + Ui.HEAD_BTN_PAD_X, box[1], box[3], Ui.alpha(over ? Ui.C_TEXT : Ui.C_ACCENT, alpha), false);
            }
        }
    }

    public boolean mouseClicked(MouseButtonEvent click)
    {
        if (this.owner != null)
        {
            return false;
        }

        int mx = (int) Math.floor(click.x());
        int my = (int) Math.floor(click.y());

        for (Toast toast : List.copyOf(this.toasts))
        {
            if (! Paint.hit(mx, my, toast.x, toast.y, toast.w, toast.h))
            {
                continue;
            }

            if (! Kit.left(click))
            {
                return true;
            }

            for (int i = 0; i < toast.actions.size(); ++i)
            {
                int[] box = toast.actionBoxes.get(i);

                if (Paint.hit(mx, my, box[0], box[1], box[2], box[3]))
                {
                    Action action = toast.actions.get(i);
                    Vanilla.click();

                    if (action.closes)
                    {
                        this.toasts.remove(toast);
                        Vanilla.toastOut();
                    }

                    action.run.run();
                    return true;
                }
            }

            int cx = toast.x + toast.w - Ui.PAD_REL - Glyphs.width(Glyphs.CROSS);

            if (Paint.hit(mx, my, cx - Ui.PAD_MICRO, toast.y + Ui.PAD_MICRO, Glyphs.width(Glyphs.CROSS) + Ui.PAD_IN,
                    Glyphs.height(Glyphs.CROSS) + Ui.PAD_IN))
            {
                this.toasts.remove(toast);
                Vanilla.toastOut();
            }

            return true;
        }

        return false;
    }

    /** Только для харнесса (режим k): что показано и где кнопка действия. В игре не зовётся. */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static List<String> texts(Toasts toasts)
        {
            List<String> out = new ArrayList<>();

            for (Toast toast : toasts.toasts)
            {
                out.add(toast.text);
            }

            return out;
        }

        /** {x, y, w, h} кнопки с этой подписью у самого свежего тоста (раскладка последнего кадра); {@code null} — нет. */
        public static int @Nullable [] actionBox(Toasts toasts, String label)
        {
            for (Toast toast : toasts.toasts.reversed())
            {
                for (int i = 0; i < toast.actions.size() && i < toast.actionBoxes.size(); ++i)
                {
                    if (toast.actions.get(i).label.equals(label))
                    {
                        return toast.actionBoxes.get(i).clone();
                    }
                }
            }

            return null;
        }
    }
}
