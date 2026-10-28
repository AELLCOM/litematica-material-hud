package com.aell.ui;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Куда поставить нашу кнопку-слот рядом с экраном контейнера, чтобы она ничего не накрыла.
 *
 * <p>Чистая арифметика без классов Minecraft: правила у всех веток одни, а типы экранов разные,
 * поэтому геометрию собирает вызывающий, а порядок предпочтений живёт здесь.
 *
 * <p>Порядок такой. Обычное место — справа от окна, вровень с его верхом. Справа же ванильный
 * инвентарь рисует панель эффектов (с 26.x она именно справа, а не слева), поэтому при активных
 * эффектах кнопка уходит влево. Слева бывает раскрытая книга рецептов — тогда окно сдвинуто
 * вправо от центра, и левый край занят: в этом случае кнопка встаёт справа под панелью эффектов.
 * Дальше — над окном (в креативе нельзя: там ряд вкладок), под окном, и в самом конце — прижатой
 * к краю экрана, чтобы на крошечном разрешении она хотя бы осталась нажимаемой.
 */
@Environment(EnvType.CLIENT)
public final class SlotAnchor
{
    /** Отступ от края экрана: кнопка не должна прилипать к нему вплотную. */
    private static final int MARGIN = 1;

    public record Rect(int x, int y, int w, int h)
    {
        public boolean intersects(int otherX, int otherY, int otherW, int otherH)
        {
            return this.x < otherX + otherW && otherX < this.x + this.w
                    && this.y < otherY + otherH && otherY < this.y + this.h;
        }
    }

    private SlotAnchor() {}

    /**
     * Прямоугольник ванильной панели эффектов справа от окна — по правилам самой игры.
     * Одинаковы на 1.21.11, 26.1.x и 26.2. {@code count} — сколько эффектов висит на игроке;
     * ноль или слишком узкий экран — панели нет.
     */
    public static @Nullable Rect effectsPanel(int screenW, int guiX, int guiY, int guiW, int count)
    {
        if (count <= 0)
        {
            return null;
        }

        int x = guiX + guiW + 2;
        int available = screenW - x;

        if (available < 32)
        {
            return null;
        }

        int width = available >= 120 ? available - 7 : 32;
        int step = count > 5 ? 132 / (count - 1) : 33;

        return new Rect(x, guiY, width, (count - 1) * step + 32);
    }

    /** Окно сдвинуто вправо от центра — слева раскрыта книга рецептов, туда кнопку нельзя. */
    public static boolean leftOccupied(int screenW, int guiX, int guiW)
    {
        return guiX > (screenW - guiW) / 2 + 2;
    }

    public static int[] place(int screenW, int screenH, int guiX, int guiY, int guiW, int guiH,
            int size, int gap, @Nullable Rect effects, boolean leftOccupied, boolean allowAbove,
            List<Rect> occupied)
    {
        int right = guiX + guiW + gap;
        int left = guiX - gap - size;
        int rightEdge = guiX + guiW - size;
        int belowEffects = effects == null ? guiY : effects.y() + effects.h() + gap;

        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        candidates.add(new int[] {right, guiY});

        if (! leftOccupied)
        {
            candidates.add(new int[] {left, guiY});
        }

        candidates.add(new int[] {right, belowEffects});

        if (allowAbove)
        {
            candidates.add(new int[] {rightEdge, guiY - gap - size});
        }

        candidates.add(new int[] {rightEdge, guiY + guiH + gap});

        for (int[] candidate : candidates)
        {
            if (fits(candidate[0], candidate[1], size, screenW, screenH)
                    && free(candidate[0], candidate[1], size, effects, occupied))
            {
                return candidate;
            }
        }

        // Ничего не подошло: прижимаем к правому краю экрана на высоте окна
        return new int[] {
                clamp(right, MARGIN, screenW - size - MARGIN),
                clamp(guiY, MARGIN, screenH - size - MARGIN) };
    }

    private static boolean fits(int x, int y, int size, int screenW, int screenH)
    {
        return x >= MARGIN && y >= MARGIN && x + size <= screenW - MARGIN && y + size <= screenH - MARGIN;
    }

    private static boolean free(int x, int y, int size, @Nullable Rect effects, List<Rect> occupied)
    {
        if (effects != null && effects.intersects(x, y, size, size))
        {
            return false;
        }

        for (Rect rect : occupied)
        {
            if (rect.intersects(x, y, size, size))
            {
                return false;
            }
        }

        return true;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
