package com.aell.ui;

import java.util.function.BooleanSupplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Стиль интерфейса: собственный (по умолчанию) или ванильный Minecraft (22.10.2026).
 *
 * <p>Флаг задаёт мод-хозяин через {@link #vanillaWhen}. В общем модуле он всегда выключен, пока мод его
 * не включит: Zone Focus, EPF+ и другие моды, которые про него не знают, рисуются как раньше. У каждого
 * мода своя затенённая копия модуля, поэтому флаг одного мода не трогает другой.
 *
 * <p>В ванильном режиме примитивы {@link Paint} переводят палитру Aell на цвета Minecraft ({@link #color}),
 * рисуют прямые углы ({@link #radius}) и текст с тенью ({@link #shadow}); виджеты рисуют спрайты
 * Minecraft через {@link Vanilla}. Цвета, которых нет в таблице (цвет предмета, палитра выгрузки),
 * проходят как есть.
 */
@Environment(EnvType.CLIENT)
public final class Style
{
    private Style() {}

    private static BooleanSupplier vanilla = () -> false;
    private static BooleanSupplier vanillaPlus = () -> false;

    /** Цвета текста Minecraft (ChatFormatting и надписи кнопок). */
    public static final int V_WHITE       = 0xFFFFFFFF;
    public static final int V_HOVER       = 0xFFFFFFA0;
    public static final int V_GRAY        = 0xFFA0A0A0;
    public static final int V_DARK_GRAY   = 0xFF707070;
    public static final int V_YELLOW      = 0xFFFFFF55;
    public static final int V_GOLD        = 0xFFFFAA00;
    public static final int V_GREEN       = 0xFF55FF55;
    public static final int V_RED         = 0xFFFF5555;
    public static final int V_AQUA        = 0xFF55FFFF;
    public static final int V_PURPLE      = 0xFFFF55FF;
    /** Заливка выбранной строки и рамка в фокусе, как у списков Minecraft. */
    public static final int V_SELECT_FILL = 0xFF000000;
    public static final int V_SELECT_EDGE = 0xFFFFFFFF;
    public static final int V_SELECT_IDLE = 0xFF808080;
    /** Тёмная подложка панели, если текстуру фона не нарисовать. */
    public static final int V_PANEL       = 0xC0000000;
    /** Предел непрозрачности для тёмных заливок панелей Aell в ванильном режиме. */
    private static final int PANEL_ALPHA_MAX = 0xC0;

    /** Мод-хозяин включает ванильный стиль: обычно это ссылка на свою настройку. */
    public static void vanillaWhen(BooleanSupplier source)
    {
        vanilla = source != null ? source : () -> false;
    }

    public static boolean vanilla()
    {
        return vanilla.getAsBoolean();
    }

    /**
     * «Vanilla+»: ванильный вид, но экран настроек строками, как у Litematica (название слева, справа плитка значения
     * и «Сброс»). Без него ванильный экран настроек — плитки в две колонки, как «Настройки» Minecraft. Имеет смысл только
     * вместе с {@link #vanilla()}.
     */
    public static void vanillaPlusWhen(BooleanSupplier source)
    {
        vanillaPlus = source != null ? source : () -> false;
    }

    public static boolean vanillaPlus()
    {
        return vanilla() && vanillaPlus.getAsBoolean();
    }

    /** Уровень стиля для настройки «Стиль интерфейса»: 0 — Aell UI, 1 — Vanilla, 2 — Vanilla+. */
    public static final int LEVEL_AELL = 0;
    public static final int LEVEL_VANILLA = 1;
    public static final int LEVEL_VANILLA_PLUS = 2;

    /** Подписи уровней стиля (ключи перевода общего модуля). */
    public static final String[] LEVEL_KEYS = { "aellui.style.aell", "aellui.style.vanilla", "aellui.style.vanilla_plus" };

    /**
     * Стиль мода-хозяина по его настройке-уровню; соседям Material HUD — с его уровнем, если своя настройка не задана
     * (Aell UI): Zone Focus и EasyPlaceFix+ рядом с Material HUD в одной теме с ним.
     */
    public static void levelWhen(java.util.function.IntSupplier own, boolean followMaterialHud)
    {
        java.util.function.IntSupplier hud = followMaterialHud ? materialHudLevel() : () -> LEVEL_AELL;
        java.util.function.IntSupplier level = () ->
        {
            int mine = own.getAsInt();

            return mine != LEVEL_AELL ? mine : hud.getAsInt();
        };
        vanillaWhen(() -> level.getAsInt() >= LEVEL_VANILLA);
        vanillaPlusWhen(() -> level.getAsInt() == LEVEL_VANILLA_PLUS);
    }

    private static java.util.function.IntSupplier materialHudLevel;

    /** Уровень стиля Material HUD ({@code LayerListApi.uiStyle}, отражением); без него — Aell UI. */
    public static java.util.function.IntSupplier materialHudLevel()
    {
        if (materialHudLevel == null)
        {
            java.util.function.IntSupplier found = () -> LEVEL_AELL;

            try
            {
                java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                        Class.forName("com.aell.layerlist.api.LayerListApi"), "uiStyle",
                        java.lang.invoke.MethodType.methodType(int.class));
                found = () ->
                {
                    try
                    {
                        return (int) handle.invokeExact();
                    }
                    catch (Throwable e)
                    {
                        return LEVEL_AELL;
                    }
                };
            }
            catch (ReflectiveOperationException | LinkageError ignored)
            {
                // Material HUD нет или он старее — своя настройка
            }

            materialHudLevel = found;
        }

        return materialHudLevel;
    }

    private static BooleanSupplier materialHud;

    /**
     * Ванильный стиль Material HUD, если он установлен ({@code LayerListApi.isVanillaStyle}, ищется отражением);
     * иначе всегда {@code false}. Соседи Material HUD берут его в свой стиль, чтобы быть в одной теме с ним.
     */
    public static BooleanSupplier materialHud()
    {
        if (materialHud == null)
        {
            BooleanSupplier found = () -> false;

            try
            {
                java.lang.invoke.MethodHandle handle = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                        Class.forName("com.aell.layerlist.api.LayerListApi"), "isVanillaStyle",
                        java.lang.invoke.MethodType.methodType(boolean.class));
                found = () ->
                {
                    try
                    {
                        return (boolean) handle.invokeExact();
                    }
                    catch (Throwable e)
                    {
                        return false;
                    }
                };
            }
            catch (ReflectiveOperationException | LinkageError ignored)
            {
                // Material HUD нет или он старее 22.10.2026 — своя настройка
            }

            materialHud = found;
        }

        return materialHud;
    }

    /** Радиус скругления: в ванильном режиме углы прямые. */
    public static int radius(int radius)
    {
        return vanilla() ? 0 : radius;
    }

    /** Тень текста: в ванильном режиме надписи всегда с тенью, как у Minecraft. */
    public static boolean shadow(boolean shadow)
    {
        return shadow || vanilla();
    }

    /** Цвет заливки, рамки или текста в текущем стиле. Альфа сохраняется. */
    public static int color(int argb)
    {
        if (! vanilla())
        {
            return argb;
        }

        int a = argb >>> 24;
        int rgb = argb & 0x00FFFFFF;

        if (isPanelRgb(rgb))
        {
            return Math.min(a, PANEL_ALPHA_MAX) << 24;
        }

        int mapped = vanillaRgb(rgb);

        return mapped < 0 ? argb : (a << 24) | mapped;
    }

    /** Тёмные тона фона панелей Aell (Ui и Theme мода). */
    public static boolean isPanelRgb(int rgb)
    {
        return switch (rgb)
        {
            case 0x121215, 0x1A1A20, 0x1B1B1F, 0x101012 -> true;
            default -> false;
        };
    }

    /** Заливка панели Aell с рамкой или без неё: такую карточку ванильный режим рисует текстурой. */
    public static boolean isPanel(int argb)
    {
        return (argb >>> 24) >= 0x80 && isPanelRgb(argb & 0x00FFFFFF);
    }

    /** Таблица Aell → Minecraft по RGB; {@code -1} — цвет не из палитры, остаётся как есть. */
    private static int vanillaRgb(int rgb)
    {
        return switch (rgb)
        {
            // Текст и серые
            case 0xE8E8EC -> 0xFFFFFF;
            case 0x9A9AA6 -> 0xA0A0A0;
            case 0x787885 -> 0x808080;
            case 0x6E6E7A -> 0x707070;
            case 0x55555F -> 0x555555;
            // Акцент и выбор: тёплый «наведённый» цвет надписей Minecraft и белая рамка выбора
            case 0x4FA3FF -> 0xFFFFA0;
            case 0x7AA8F5 -> 0xFFFFFF;
            // Смысловые цвета → цвета форматирования Minecraft
            case 0x57C97A, 0x4ADE80 -> 0x55FF55;
            case 0xE05B4A, 0xF87171 -> 0xFF5555;
            case 0xE0902E, 0xFB923C, 0xF5C451, 0xE0A030 -> 0xFFAA00;
            case 0xE0B341, 0xFACC15 -> 0xFFFF55;
            case 0x9B7FE0 -> 0xFF55FF;
            case 0x67E8F9 -> 0x55FFFF;
            default -> -1;
        };
    }
}
