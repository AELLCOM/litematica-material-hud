package com.aell.layerlist.ui.kit;

import com.mojang.blaze3d.platform.TextInputManager;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.input.PreeditEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Ввод текста SDL для своих полей (26.3). Буквы приходят окну событием {@code SDL_EVENT_TEXT_INPUT},
 * а SDL шлёт его, только пока ввод текста включён ({@link TextInputManager#startTextInput}). Ванильный
 * {@code EditBox} включает его сам в {@code setFocused}; наши поля — не {@code EditBox}, и без этого
 * класса {@code charTyped} до них не доходил вовсе: стрелки и Backspace работали, буквы — нет.
 *
 * <p>Владелец — тот, кто сейчас ждёт букв: поле в фокусе или экран, у которого буква в списке
 * начинает поиск. {@link #take} зовут при получении фокуса и каждый кадр, пока поле в фокусе рисуется:
 * ваниль выключает ввод при каждой смене экрана, а поле об этом не узнаёт. {@link #release} — при
 * потере фокуса; чужого владельца он не трогает (так устроен сам {@code TextInputManager}), поэтому
 * порядок «новое поле взяло — старое отпустило» безопасен.
 *
 * <p>Черновик IME (набор иероглифов до подтверждения) ваниль отдаёт экрану в
 * {@code preeditUpdated}; наши экраны передают его в {@link #preedit}, а отсюда он идёт владельцу,
 * если тот {@link Target}. Рисуется ванильным {@link IMEPreeditOverlay} — там же, где у {@code EditBox}.
 */
@Environment(EnvType.CLIENT)
public final class TextFocus
{
    /** Владелец, который показывает черновик IME. */
    public interface Target
    {
        /** {@code null} — черновика больше нет (подтверждён, отменён или фокус ушёл). */
        void preedit(@Nullable PreeditEvent event);
    }

    private static @Nullable Object owner;

    private TextFocus() {}

    private static TextInputManager manager()
    {
        return Minecraft.getInstance().textInputManager();
    }

    /** Включить ввод текста для {@code who}; уже включённый лишь меняет владельца. */
    public static void take(Object who)
    {
        owner = who;
        manager().startTextInput(who);
    }

    /** Выключить ввод, если его держит {@code who}; иначе ничего. */
    public static void release(Object who)
    {
        if (owner == who)
        {
            owner = null;
        }

        manager().stopTextInput(who);
    }

    /** Держать ввод, пока {@code want}: зовётся каждый кадр (экран со «списком, где буква — поиск»). */
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

    /** Ввод держит {@code who} (по нашей записи: ваниль могла выключить его при смене экрана). */
    public static boolean owns(Object who)
    {
        return owner == who;
    }

    /**
     * Где курсор, в координатах GUI: столбик шириной 1 px от {@code top} до {@code bottom}. По нему
     * система ставит окно кандидатов IME — рядом с полем, а не в углу окна игры.
     */
    public static void caret(Object who, int x, int top, int bottom)
    {
        if (owner == who)
        {
            manager().setTextInputArea(x, top, x + 1, Math.max(top + 1, bottom));
        }
    }

    /** Черновик IME от экрана — владельцу; {@code false} — владелец черновиков не показывает. */
    public static boolean preedit(@Nullable PreeditEvent event)
    {
        if (owner instanceof Target target)
        {
            target.preedit(event);
            return true;
        }

        return false;
    }

    /** Плашка черновика под полем высотой {@code inputHeight}; {@code null} — черновика нет. */
    public static @Nullable IMEPreeditOverlay overlay(@Nullable PreeditEvent event, int inputHeight)
    {
        return event == null ? null : new IMEPreeditOverlay(event, Kit.font(), inputHeight);
    }

    /**
     * Показать плашку черновика у курсора ({@code x}) под полем с верхом {@code top}. Плашку рисует
     * сама ваниль в конце кадра (поверх попапов и вне ножниц поля), и она же переносит туда окно
     * кандидатов IME.
     */
    public static void showPreedit(GuiContext ctx, IMEPreeditOverlay overlay, int x, int top)
    {
        overlay.updateInputPosition(x, top);

        // GuiContext malilib — копия кадра; отложенное рисование читает исходный
        GuiGraphicsExtractor frame = ctx.getGuiGraphics();

        if (frame != null)
        {
            frame.setPreeditOverlay(overlay);
        }
        else
        {
            overlay.extractRenderState(ctx, -1, -1, 0.0F);
        }
    }
}
