package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Widget;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Панель экрана «Схемы»: прямоугольник тела, который раскладывает {@link GuiSchematics}. Свой
 * класс, а не {@code WidgetListBase} malilib: виджеты malilib рисуются в её ванильном виде и сами
 * являются экранами.
 */
@Environment(EnvType.CLIENT)
abstract class Pane extends Widget
{
    protected final GuiSchematics screen;

    Pane(GuiSchematics screen)
    {
        this.screen = screen;
    }

    /** Кадр: опрос фоновых задач (вызывается до отрисовки, даже если панель скрыта). */
    void tick()
    {
    }

    /** Поле ввода с фокусом (чтобы Esc сначала снимал фокус, а клик мимо — гасил его). */
    @Nullable TextInput focusedInput()
    {
        return null;
    }

    /** Снять фокус со всех полей; {@code true} — было с чего снимать. */
    boolean blur()
    {
        TextInput input = this.focusedInput();

        if (input != null)
        {
            input.setFocused(false);
            return true;
        }

        return false;
    }

    /** Кнопки подвала для этой панели, по важности: первая — самая правая. */
    List<Button> footerActions()
    {
        return List.of();
    }

    /** Текст слева от кнопок подвала (счётчики). */
    String footerStatus()
    {
        return "";
    }

    /** Подсказка к тексту подвала (пояснение к счётчикам); {@code null} — без подсказки. */
    @Nullable String footerStatusTip()
    {
        return null;
    }

    /** Экран уходит (другой экран поверх, закрытие): освободить текстуры и GPU. */
    void release()
    {
    }
}
