package com.aell.ui;

import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Элементы Minecraft для ванильного стиля ({@link Style#vanilla()}): спрайты кнопок, полей, ползунков,
 * галочек, полос прокрутки, вкладок, подсказок и всплывающих окон, фоны списков и разделители (22.10.2026).
 *
 * <p>Спрайты рисуются {@code blitSprite}: Minecraft сам режет их по краям по {@code .mcmeta}, поэтому
 * кнопка любой ширины и высоты выглядит как ванильная. Всё здесь пишет в {@code ctx} напрямую, мимо
 * {@link Paint}, чтобы перевод палитры не трогал ванильные цвета.
 */
@Environment(EnvType.CLIENT)
public final class Vanilla
{
    private Vanilla() {}

    public static final Identifier BUTTON              = sprite("widget/button");
    public static final Identifier BUTTON_HIGHLIGHTED  = sprite("widget/button_highlighted");
    public static final Identifier BUTTON_DISABLED     = sprite("widget/button_disabled");
    public static final Identifier FIELD               = sprite("widget/text_field");
    public static final Identifier FIELD_HIGHLIGHTED   = sprite("widget/text_field_highlighted");
    public static final Identifier SLIDER              = sprite("widget/slider");
    public static final Identifier SLIDER_HIGHLIGHTED  = sprite("widget/slider_highlighted");
    public static final Identifier HANDLE              = sprite("widget/slider_handle");
    public static final Identifier HANDLE_HIGHLIGHTED  = sprite("widget/slider_handle_highlighted");
    public static final Identifier CHECKBOX            = sprite("widget/checkbox");
    public static final Identifier CHECKBOX_HIGHLIGHTED = sprite("widget/checkbox_highlighted");
    public static final Identifier CHECKBOX_SELECTED   = sprite("widget/checkbox_selected");
    public static final Identifier CHECKBOX_SELECTED_HIGHLIGHTED = sprite("widget/checkbox_selected_highlighted");
    public static final Identifier SCROLLER            = sprite("widget/scroller");
    public static final Identifier SCROLLER_BACKGROUND = sprite("widget/scroller_background");
    public static final Identifier TAB                 = sprite("widget/tab");
    public static final Identifier TAB_HIGHLIGHTED     = sprite("widget/tab_highlighted");
    public static final Identifier TAB_SELECTED        = sprite("widget/tab_selected");
    public static final Identifier TAB_SELECTED_HIGHLIGHTED = sprite("widget/tab_selected_highlighted");
    public static final Identifier TOOLTIP_BACKGROUND  = sprite("tooltip/background");
    public static final Identifier TOOLTIP_FRAME       = sprite("tooltip/frame");
    public static final Identifier POPUP               = sprite("popup/background");
    public static final Identifier TOAST               = sprite("toast/system");

    public static final Identifier LIST_BACKGROUND         = texture("menu_list_background");
    public static final Identifier LIST_BACKGROUND_INWORLD = texture("inworld_menu_list_background");
    public static final Identifier MENU_BACKGROUND         = texture("menu_background");
    public static final Identifier MENU_BACKGROUND_INWORLD = texture("inworld_menu_background");
    public static final Identifier HEADER_SEPARATOR        = texture("header_separator");
    public static final Identifier HEADER_SEPARATOR_INWORLD = texture("inworld_header_separator");
    public static final Identifier FOOTER_SEPARATOR        = texture("footer_separator");
    public static final Identifier FOOTER_SEPARATOR_INWORLD = texture("inworld_footer_separator");

    /** Сторона тайла фонов меню и списков, px. */
    public static final int TILE = 32;
    /** Ванильная галочка — 17×17. */
    public static final int CHECKBOX_SIZE = 17;
    /** Ширина ручки ползунка Minecraft. */
    public static final int HANDLE_W = 8;
    /** Ширина полосы прокрутки Minecraft. */
    public static final int SCROLLER_W = 6;
    /** Отступ спрайта подсказки за видимую рамку: фон и рамка подсказки рисуются шире содержимого. */
    private static final int TOOLTIP_BLEED = 9;
    /** Насколько всплывающее окно шире своей области, px. */
    public static final int POPUP_BLEED = 4;
    /** Подложка панели в мире: ванильная текстура списка там полупрозрачна, мир за ней мешал бы читать. */
    private static final int PANEL_UNDERLAY_INWORLD = 0x99000000;

    private static Identifier sprite(String path)
    {
        return Identifier.withDefaultNamespace(path);
    }

    private static Identifier texture(String name)
    {
        return Identifier.withDefaultNamespace("textures/gui/" + name + ".png");
    }

    // --- Звуки ---------------------------------------------------------------------------------------

    /** Окно, в которое второй щелчок того же нажатия не звучит (вложенные элементы, кнопка в строке). */
    private static final long CLICK_DEDUP_MS = 40L;
    private static long lastClickAt;

    /**
     * Щелчок кнопки Minecraft — только в ванильном стиле. Звучит на нажатие кнопки, галочки, вкладки, плитки,
     * пункта меню и выпадающего списка, на отпускание ползунка и шага «−/+», как у элементов Minecraft. Дважды за
     * одно нажатие не звучит.
     */
    public static void click()
    {
        if (! Style.vanilla())
        {
            return;
        }

        long now = net.minecraft.util.Util.getMillis();

        if (now - lastClickAt < CLICK_DEDUP_MS)
        {
            return;
        }

        lastClickAt = now;
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Звук появления уведомления Minecraft — только в ванильном стиле. */
    public static void toastIn()
    {
        playUi(net.minecraft.sounds.SoundEvents.UI_TOAST_IN);
    }

    /** Звук ухода уведомления Minecraft — только в ванильном стиле. */
    public static void toastOut()
    {
        playUi(net.minecraft.sounds.SoundEvents.UI_TOAST_OUT);
    }

    private static void playUi(net.minecraft.sounds.SoundEvent sound)
    {
        if (Style.vanilla())
        {
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, 1.0F, 1.0F));
        }
    }

    /** Открыт ли мир: Minecraft рисует поверх мира полупрозрачные фоны, а в меню — непрозрачные. */
    public static boolean inWorld()
    {
        return Minecraft.getInstance().level != null;
    }

    public static void sprite(GuiContext ctx, Identifier sprite, int x, int y, int w, int h)
    {
        if (w > 0 && h > 0)
        {
            ctx.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
        }
    }

    // --- Кнопки и поля -------------------------------------------------------------------------------

    /** Кнопка Minecraft: обычная, подсвеченная (наведение или фокус) или выключенная. */
    public static void button(GuiContext ctx, int x, int y, int w, int h, boolean enabled, boolean highlighted)
    {
        sprite(ctx, ! enabled ? BUTTON_DISABLED : highlighted ? BUTTON_HIGHLIGHTED : BUTTON, x, y, w, h);
    }

    /** Цвет надписи на кнопке Minecraft: белая, у выключенной — серая. */
    public static int buttonText(boolean enabled)
    {
        return enabled ? Style.V_WHITE : Style.V_GRAY;
    }

    /** Надпись кнопки по центру, с тенью, как у Minecraft. */
    public static void buttonLabel(GuiContext ctx, Font font, String text, int x, int y, int w, int h, boolean enabled)
    {
        int tx = x + (w - font.width(text)) / 2;
        int ty = y + (h - 8) / 2;
        ctx.drawString(font, text, tx, ty, buttonText(enabled), true);
    }

    /** Поле ввода Minecraft; в фокусе — с белой рамкой. */
    public static void field(GuiContext ctx, int x, int y, int w, int h, boolean focused)
    {
        sprite(ctx, focused ? FIELD_HIGHLIGHTED : FIELD, x, y, w, h);
    }

    // --- Ползунок, галочка, прокрутка ----------------------------------------------------------------

    /** Дорожка ползунка Minecraft (вся область ползунка). */
    public static void sliderTrack(GuiContext ctx, int x, int y, int w, int h, boolean highlighted)
    {
        sprite(ctx, highlighted ? SLIDER_HIGHLIGHTED : SLIDER, x, y, w, h);
    }

    /** Ручка ползунка Minecraft: обычно {@link #HANDLE_W} в ширину на всю высоту дорожки. */
    public static void sliderHandle(GuiContext ctx, int x, int y, int w, int h, boolean highlighted)
    {
        sprite(ctx, highlighted ? HANDLE_HIGHLIGHTED : HANDLE, x, y, w, h);
    }

    /** Ручка ползунка по доле {@code t} 0…1 внутри дорожки. */
    public static void sliderHandleAt(GuiContext ctx, int x, int y, int w, int h, float t, boolean highlighted)
    {
        int room = Math.max(0, w - HANDLE_W);
        int hx = x + Math.round(room * Ui.clamp01(t));
        sliderHandle(ctx, hx, y, Math.min(HANDLE_W, w), h, highlighted);
    }

    /**
     * Галочка Minecraft. Рисуется в своём размере 17×17 у левого края области {@code (x, y, w, h)}: так спрайт не
     * мылится, а переключатель-«рычажок» Aell меняется на ванильную галочку без перестройки раскладки.
     * Если область ниже 17, галочка сжимается до её высоты.
     */
    public static void checkbox(GuiContext ctx, int x, int y, int w, int h, boolean selected, boolean highlighted)
    {
        int size = Math.min(CHECKBOX_SIZE, Math.max(8, Math.max(h, Math.min(w, CHECKBOX_SIZE))));
        // По левому краю области: место «рычажка» Aell шире галочки, подпись справа не должна отъезжать
        int cx = w >= size ? x : x + (w - size) / 2;
        int cy = y + (h - size) / 2;
        Identifier id = selected
                ? highlighted ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED
                : highlighted ? CHECKBOX_HIGHLIGHTED : CHECKBOX;
        sprite(ctx, id, cx, cy, size, size);
    }

    /** Подложка полосы прокрутки Minecraft. */
    public static void scrollerTrack(GuiContext ctx, int x, int y, int w, int h)
    {
        sprite(ctx, SCROLLER_BACKGROUND, x, y, w, h);
    }

    /** Бегунок полосы прокрутки Minecraft. */
    public static void scrollerThumb(GuiContext ctx, int x, int y, int w, int h)
    {
        sprite(ctx, SCROLLER, x, y, w, h);
    }

    // --- Вкладки -------------------------------------------------------------------------------------

    /**
     * Вкладка как в настройках Litematica/malilib: ряд ванильных кнопок. Выбранная — «нажатая» (спрайт выключенной
     * кнопки, тёмная), наведённая — подсвеченная, остальные — обычные. Между соседними вкладками — зазор
     * {@link #TAB_GAP} (по половине с каждой стороны), раскладка вкладок не меняется.
     */
    public static void tab(GuiContext ctx, int x, int y, int w, int h, boolean selected, boolean highlighted)
    {
        int half = TAB_GAP / 2;
        sprite(ctx, selected ? BUTTON_DISABLED : highlighted ? BUTTON_HIGHLIGHTED : BUTTON, x + half, y, w - half * 2, h);
    }

    /** Сдвиг надписи невыбранной вкладки вниз: у вкладок-кнопок его нет, надпись по центру. */
    public static final int TAB_IDLE_DROP = 0;
    /** Зазор между соседними вкладками-кнопками, px. */
    public static final int TAB_GAP = 2;

    /** Цвет надписи вкладки: выбранная («нажатая» кнопка) — серая, как у Litematica, остальные — белые. */
    public static int tabText(boolean selected, boolean highlighted)
    {
        return selected ? Style.V_GRAY : Style.V_WHITE;
    }

    // --- Окна, подсказки, панели ---------------------------------------------------------------------

    /** Рамка подсказки Minecraft (фиолетовая кайма), видимая граница — ровно {@code (x, y, w, h)}. */
    public static void tooltip(GuiContext ctx, int x, int y, int w, int h)
    {
        int b = TOOLTIP_BLEED;
        sprite(ctx, TOOLTIP_BACKGROUND, x - b, y - b, w + b * 2, h + b * 2);
        sprite(ctx, TOOLTIP_FRAME, x - b, y - b, w + b * 2, h + b * 2);
    }

    /**
     * Всплывающее окно Minecraft (подложка диалогов). Кайма спрайта толще рамки Aell, поэтому окно рисуется на
     * {@link #POPUP_BLEED} шире области: заголовок и кнопки, разложенные под тонкую рамку, не упираются в кайму.
     */
    public static void popup(GuiContext ctx, int x, int y, int w, int h)
    {
        int b = POPUP_BLEED;
        sprite(ctx, POPUP, x - b, y - b, w + b * 2, h + b * 2);
    }

    /** Фон области списка без рамки (тело окна между шапкой и подвалом), как у списков меню Minecraft. */
    public static void listBackground(GuiContext ctx, int x, int y, int w, int h)
    {
        if (w <= 0 || h <= 0)
        {
            return;
        }

        if (inWorld())
        {
            ctx.fill(x, y, x + w, y + h, PANEL_UNDERLAY_INWORLD);
        }

        tile(ctx, inWorld() ? LIST_BACKGROUND_INWORLD : LIST_BACKGROUND, x, y, w, h);
    }

    /** Подложка уведомления Minecraft (системный тост). */
    public static void toast(GuiContext ctx, int x, int y, int w, int h)
    {
        sprite(ctx, TOAST, x, y, w, h);
    }

    /** Подложка уведомления, гаснущая вместе с содержимым: {@code alpha} 0…1. */
    public static void toast(GuiContext ctx, int x, int y, int w, int h, float alpha)
    {
        if (w > 0 && h > 0)
        {
            int a = Math.round(Ui.clamp01(alpha) * 255.0F);
            ctx.blitSprite(RenderPipelines.GUI_TEXTURED, TOAST, x, y, w, h, (a << 24) | 0x00FFFFFF);
        }
    }

    /** Повторяющаяся текстура фона по области. */
    public static void tile(GuiContext ctx, Identifier texture, int x, int y, int w, int h)
    {
        if (w > 0 && h > 0)
        {
            ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) x, (float) y, w, h, TILE, TILE);
        }
    }

    /**
     * Панель окна: фон списка Minecraft (в мире — полупрозрачный) и тёмная рамка в 1 px. Это ванильная
     * замена карточки Aell с заливкой {@code C_PANEL}/{@code C_CHROME}.
     */
    public static void panel(GuiContext ctx, int x, int y, int w, int h)
    {
        if (w <= 0 || h <= 0)
        {
            return;
        }

        if (inWorld())
        {
            ctx.fill(x, y, x + w, y + h, PANEL_UNDERLAY_INWORLD);
        }

        tile(ctx, inWorld() ? LIST_BACKGROUND_INWORLD : LIST_BACKGROUND, x, y, w, h);
        outline(ctx, x, y, w, h, 0xFF000000);
    }

    /**
     * Фон всего экрана, как у меню Minecraft: в мире — размытие мира и полупрозрачная текстура поверх, в главном
     * меню — непрозрачная. Размыть можно только раз за кадр: если экран уже размыт (Minecraft или другой слой), второй
     * раз пропускается.
     */
    public static void screen(GuiContext ctx, int w, int h)
    {
        if (inWorld())
        {
            try
            {
                ctx.blurBeforeThisStratum();
            }
            catch (IllegalStateException alreadyBlurred)
            {
                // Кадр уже размыт — достаточно текстуры
            }
        }

        tile(ctx, inWorld() ? MENU_BACKGROUND_INWORLD : MENU_BACKGROUND, 0, 0, w, h);
    }

    /** Разделитель под шапкой (2 px), как над списком в меню Minecraft. */
    public static void headerSeparator(GuiContext ctx, int x, int y, int w)
    {
        if (w > 0)
        {
            ctx.blit(RenderPipelines.GUI_TEXTURED, inWorld() ? HEADER_SEPARATOR_INWORLD : HEADER_SEPARATOR,
                    x, y, 0.0F, 0.0F, w, 2, TILE, 2);
        }
    }

    /** Разделитель над подвалом (2 px). */
    public static void footerSeparator(GuiContext ctx, int x, int y, int w)
    {
        if (w > 0)
        {
            ctx.blit(RenderPipelines.GUI_TEXTURED, inWorld() ? FOOTER_SEPARATOR_INWORLD : FOOTER_SEPARATOR,
                    x, y, 0.0F, 0.0F, w, 2, TILE, 2);
        }
    }

    /** Рамка в 1 px. */
    public static void outline(GuiContext ctx, int x, int y, int w, int h, int color)
    {
        if (w <= 0 || h <= 0)
        {
            return;
        }

        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /**
     * Выбранная строка списка Minecraft: чёрная заливка и рамка — белая в фокусе, серая без него.
     */
    public static void selection(GuiContext ctx, int x, int y, int w, int h, boolean focused)
    {
        ctx.fill(x, y, x + w, y + h, focused ? Style.V_SELECT_EDGE : Style.V_SELECT_IDLE);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, Style.V_SELECT_FILL);
    }
}
