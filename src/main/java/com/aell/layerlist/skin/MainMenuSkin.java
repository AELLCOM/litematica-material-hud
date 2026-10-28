package com.aell.layerlist.skin;

import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Главное меню Litematica в цветах Material HUD. Кнопки Litematica стоят где стояли, в той же сетке (колонки
 * {@code x=12} и {@code x=w+32}, ряды через 22): другие моды ставят свои кнопки по зашитым координатам этой
 * сетки, и они должны попадать туда же. Кнопки других модов остаются там, куда их поставил мод (рамка
 * {@link Ui#C_FOREIGN}); кнопка Material HUD, если на неё легла чужая, плавно едет в свободную клетку
 * ({@link #slideTo}, зовёт {@code LitematicaMenuButton}).
 *
 * <p>Кнопку Litematica отличаем по классу действия ({@code fi.dy.masa.litematica.*}, у окон malilib —
 * {@code fi.dy.masa.malilib.*}); у кнопок других модов действие своё. Повторный {@code initGui} того же меню (смена режима выделения, возврат из подменю) кнопку не
 * гоняет: приехавшее место запоминается по подписи.
 */
@Environment(EnvType.CLIENT)
public final class MainMenuSkin
{
    /** Действия кнопок Litematica и окон malilib, которые она открывает; всё остальное — кнопки других модов. */
    private static final String[] NATIVE_PACKAGES = { "fi.dy.masa.litematica.", "fi.dy.masa.malilib." };
    public static final float SLIDE_MS = 260.0F;

    private static final Map<ButtonBase, Slide> SLIDES = new WeakHashMap<>();
    /** Куда кнопки уже приехали в этом меню, по подписи: повторный {@code initGui} ставит их сразу. */
    private static final Map<String, int[]> SETTLED = new HashMap<>();

    private static @Nullable GuiBase menu;
    private static float slow = 1.0F;

    private MainMenuSkin() {}

    /** Кнопка другого мода (или Material HUD), а не самой Litematica. */
    public static boolean isAddon(ButtonBase button)
    {
        Object listener = SkinAccess.action(button);

        if (listener == null)
        {
            return false;
        }

        String name = listener.getClass().getName();

        for (String prefix : NATIVE_PACKAGES)
        {
            if (name.startsWith(prefix))
            {
                return false;
            }
        }

        return true;
    }

    /** Начало кадра перекрашенного меню: другое меню — забыть прошлые переезды; затем шаг анимации. */
    static void frame(GuiBase screen)
    {
        if (screen != menu)
        {
            menu = screen;
            SETTLED.clear();
            SLIDES.clear();
        }

        tick();
    }

    /**
     * Плавно перевести кнопку в {@code x, y} шириной {@code w}. Та же цель — ничего не делаем; кнопка уже
     * приезжала сюда в этом меню — ставим сразу.
     */
    public static void slideTo(ButtonBase button, int x, int y, int w, float ms)
    {
        button.setWidth(w);
        Slide current = SLIDES.get(button);

        if (current != null && current.tx == x && current.ty == y)
        {
            return;
        }

        int[] settled = SETTLED.get(key(button));

        if ((button.getX() == x && button.getY() == y) || (settled != null && settled[0] == x && settled[1] == y))
        {
            button.setPosition(x, y);
            SLIDES.remove(button);
            return;
        }

        SLIDES.put(button, new Slide(button.getX(), button.getY(), x, y, System.nanoTime(), ms * slow));
    }

    /** Куда кнопка сейчас едет: {x, y}; не едет — {@code null}. */
    public static int @Nullable [] targetOf(ButtonBase button)
    {
        Slide slide = SLIDES.get(button);
        return slide == null ? null : new int[] { slide.tx, slide.ty };
    }

    private static void tick()
    {
        long now = System.nanoTime();
        Iterator<Map.Entry<ButtonBase, Slide>> it = SLIDES.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<ButtonBase, Slide> entry = it.next();
            ButtonBase button = entry.getKey();
            Slide s = entry.getValue();
            float t = Math.min(1.0F, (now - s.start) / 1_000_000.0F / s.ms);
            float k = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            button.setPosition(Math.round(s.fx + (s.tx - s.fx) * k), Math.round(s.fy + (s.ty - s.fy) * k));

            if (t >= 1.0F)
            {
                SETTLED.put(key(button), new int[] { s.tx, s.ty });
                it.remove();
            }
        }
    }

    private static String key(ButtonBase button)
    {
        return SkinAccess.label(button);
    }

    private record Slide(int fx, int fy, int tx, int ty, long start, float ms) {}

    /** Для харнесса: замедлить анимацию, чтобы снять кадр посреди переезда. */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static void slow(float factor)
        {
            slow = Math.max(1.0F, factor);
        }
    }
}
