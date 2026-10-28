package com.aell.ui.kit;

import com.aell.ui.Ui;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import org.jetbrains.annotations.Nullable;

/**
 * Квадратная кнопка-значок (шапка, строки списка). Подпись не рисуется, она — подсказка: без
 * подсказки значок был бы немым.
 */
@Environment(EnvType.CLIENT)
public class IconButton extends Button
{
    public IconButton(String[] glyph, String tooltip, Style style, @Nullable Runnable action)
    {
        super("", style, action);
        this.glyph = glyph;
        this.tooltip = tooltip;
    }

    /** Сторона по умолчанию — высота кнопки шапки. */
    public static int size()
    {
        return Ui.HEAD_BTN_H;
    }

    @Override
    public int preferredWidth(Font font)
    {
        return Math.max(size(), (this.glyph == null ? 0 : Glyphs.width(this.glyph)) + Ui.PAD_IN * 2);
    }
}
