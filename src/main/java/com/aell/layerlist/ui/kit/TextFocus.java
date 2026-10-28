package com.aell.layerlist.ui.kit;

import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Вариант для 1.21.11 (GLFW): буквы приходят окну всегда, а системы ввода текста с черновиком IME
 * ({@code TextInputManager}, {@code PreeditEvent}, {@code IMEPreeditOverlay}) в этой версии игры ещё нет —
 * она появилась в 26.x. Поэтому здесь только учёт владельца (кто ждёт букв); черновик IME не приходит
 * никогда, и его типы — пустые заглушки с теми же именами, чтобы код полей был общим с 26.x.
 */
@Environment(EnvType.CLIENT)
public final class TextFocus
{
    /** Черновик IME: в 1.21.11 игра его не присылает. */
    public static final class PreeditEvent
    {
        private PreeditEvent() {}
    }

    /** Плашка черновика: в 1.21.11 её не бывает. */
    public static final class Overlay
    {
        private Overlay() {}
    }

    /** Владелец, который показывает черновик IME. */
    public interface Target
    {
        /** {@code null} — черновика больше нет. */
        void preedit(@Nullable PreeditEvent event);
    }

    private static @Nullable Object owner;

    private TextFocus() {}

    /** Ввод текста для {@code who}: в 1.21.11 буквы идут и так — только запоминаем владельца. */
    public static void take(Object who)
    {
        owner = who;
    }

    /** Отпустить ввод, если его держит {@code who}; иначе ничего. */
    public static void release(Object who)
    {
        if (owner == who)
        {
            owner = null;
        }
    }

    /** Держать ввод, пока {@code want}: зовётся каждый кадр. */
    public static void hold(Object who, boolean want)
    {
        if (want)
        {
            take(who);
        }
        else
        {
            release(who);
        }
    }

    public static boolean owns(Object who)
    {
        return owner == who;
    }

    /** Окно кандидатов IME у курсора: в 1.21.11 ставить некуда. */
    public static void caret(Object who, int x, int top, int bottom)
    {
    }

    public static boolean preedit(@Nullable PreeditEvent event)
    {
        return false;
    }

    public static @Nullable Overlay overlay(@Nullable PreeditEvent event, int inputHeight)
    {
        return null;
    }

    public static void showPreedit(GuiContext ctx, Overlay overlay, int x, int top)
    {
    }
}
