package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.config.LayerListSettings;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.skin.LitematicaSkin;
import com.aell.layerlist.skin.MainMenuSkin;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.render.GuiContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кнопка «Material HUD» с иконкой мода в главном меню Litematica (зовёт {@code MixinLitematicaMainMenu}
 * после {@code GuiMainMenu.initGui}). Открывает настройки мода ({@link GuiConfigs}) — вход в Material
 * HUD целиком, а не только в «Схемы»: вкладка «Схемы ›» есть в самих настройках, а «Назад» оттуда
 * возвращает в меню Litematica. Место не зашито: берётся всё, что уже лежит в меню, и наша кнопка
 * встаёт в первую свободную клетку правой колонки (шаг — высота кнопки + 2, как у меню); колонка занята
 * до низа — в колонку правее, потом в остальные колонки справа налево; совсем некуда — в правый верхний угол.
 *
 * <p>Другие моды тоже кладут сюда кнопки, часто в зашитую клетку и уже после нас: Litematica Printer
 * ставит «Printer Settings» во вторую клетку правой колонки своим инжектом в конец {@code initGui}.
 * Поэтому место проверяется ещё и перед каждой отрисовкой: легла на нас чужая кнопка или виджет — уходим
 * в свободную клетку. Порядок модов не важен, и клик по чужой кнопке достаётся ей.
 *
 * <p>Списки кнопок и виджетов у malilib {@code GuiBase} приватные и без геттеров — читаются отражением
 * (точки в {@code ContractCheck.reflective()}); ванильные виджеты чужих модов — открытым {@code children()}.
 * Не прочиталось — кнопка встаёт в правый верхний угол: может лечь на кнопку в узком окне, но меню не ломается.
 */
@Environment(EnvType.CLIENT)
public final class LitematicaMenuButton
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    private static final String KEY_LABEL = "layerlist.gui.litematica_menu";
    private static final String KEY_TIP = "layerlist.gui.litematica_menu.tip";
    /** Подпись «Назад» настроек: «← Меню Litematica». */
    private static final String KEY_BACK = "layerlist.gui.parent.litematica_menu";

    /** Высота кнопок меню Litematica ({@code ButtonGeneric} высотой 20) и зазор между ними. */
    private static final int BUTTON_H = 20;
    private static final int STEP = BUTTON_H + 2;
    private static final int COLUMN_GAP = 20;
    private static final int EDGE = 4;
    private static final int ICON = 16;
    private static final int ICON_PAD = 3;
    /** Нижний ряд меню (переключатель инструмента) не трогаем: он прижат к низу окна. */
    private static final int BOTTOM_RESERVE = BUTTON_H * 2;
    /** Списки {@code GuiBase}, где лежат кнопки и виджеты меню (свои Litematica и чужих модов). */
    private static final String[] LIST_FIELDS = { "buttons", "widgets" };

    private static List<Field> listFields = List.of();
    private static boolean lookedUp;
    private static boolean warned;

    private LitematicaMenuButton() {}

    public static void addTo(GuiBase menu)
    {
        try
        {
            if (! SchematicsConfig.MAIN_MENU_BUTTON.getBooleanValue())
            {
                return;
            }

            String label = Strings.plain(KEY_LABEL);
            int labelW = menu.getStringWidth(label) + (ICON + ICON_PAD * 2) * 2;
            ButtonGeneric button = new MenuButton(menu, labelW, label);
            button.setHoverStrings(KEY_TIP);
            menu.addButton(button, (b, mouseButton) ->
            {
                openSettings(menu);
            });
        }
        catch (RuntimeException | LinkageError e)
        {
            // Чужой экран: лучше остаться без кнопки, чем уронить меню Litematica
            warnOnce("Could not add the Material HUD button to the Litematica main menu", e);
        }
    }

    /**
     * Настройки Material HUD поверх меню Litematica. Меню — корень своего пути (его открывают
     * хоткеем), поэтому старый путь «Назад» сбрасывается: из настроек — в меню, из меню — в игру.
     */
    private static void openSettings(GuiBase menu)
    {
        Nav.clear();
        GuiConfigs.open(menu, KEY_BACK);
    }

    private static void warnOnce(String message, Throwable e)
    {
        if (! warned)
        {
            warned = true;
            LOGGER.warn(message, e);
        }
    }

    /** Прямоугольники всего, что лежит в меню, кроме {@code self}: {x, y, w, h}. */
    private static List<int[]> taken(GuiBase menu, Object self)
    {
        List<int[]> out = new ArrayList<>();

        for (Field field : listFields())
        {
            try
            {
                if (field.get(menu) instanceof List<?> list)
                {
                    for (Object element : list)
                    {
                        if (element != self && element instanceof WidgetBase widget)
                        {
                            add(out, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
                        }
                    }
                }
            }
            catch (IllegalAccessException e)
            {
                // Этот список пропускаем, остальные всё равно учтены
            }
        }

        // Ванильные виджеты, которые чужие моды кладут прямо в экран (Fabric Screen API и т. п.)
        for (GuiEventListener child : menu.children())
        {
            if (child != self && child instanceof LayoutElement element)
            {
                add(out, element.getX(), element.getY(), element.getWidth(), element.getHeight());
            }
        }

        return out;
    }

    private static void add(List<int[]> out, int x, int y, int w, int h)
    {
        if (w > 0 && h > 0)
        {
            out.add(new int[] { x, y, w, h });
        }
    }

    private static List<Field> listFields()
    {
        if (! lookedUp)
        {
            lookedUp = true;
            List<Field> found = new ArrayList<>();

            for (String name : LIST_FIELDS)
            {
                try
                {
                    Field field = GuiBase.class.getDeclaredField(name);
                    field.setAccessible(true);
                    found.add(field);
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    LOGGER.warn("malilib GuiBase.{} is not readable; the Material HUD button may land on other buttons", name);
                }
            }

            listFields = List.copyOf(found);
        }

        return listFields;
    }

    /** {x, y, w}: первая свободная клетка правой колонки, затем колонки правее, затем остальных справа налево. */
    static int[] findSpot(List<int[]> taken, int screenW, int screenH, int labelW)
    {
        int bottom = screenH - BOTTOM_RESERVE;
        List<int[]> upper = new ArrayList<>();

        for (int[] rect : taken)
        {
            if (rect[1] + rect[3] <= bottom)
            {
                upper.add(rect);
            }
        }

        if (upper.isEmpty())
        {
            int w = Math.min(labelW, Math.max(0, screenW - EDGE * 2));
            return new int[] { Math.max(EDGE, screenW - w - EDGE), EDGE + STEP, w };
        }

        List<Integer> columns = columns(upper);
        int columnX = columns.get(0);
        int columnW = columnWidth(upper, columnX);
        int columnTop = columnTop(upper, columnX);
        // {x, ширина колонки, верх колонки}
        List<int[]> candidates = new ArrayList<>();
        candidates.add(new int[] { columnX, columnW, columnTop });
        candidates.add(new int[] { columnX + columnW + COLUMN_GAP, columnW, columnTop });

        for (int i = 1; i < columns.size(); ++i)
        {
            int x = columns.get(i);
            candidates.add(new int[] { x, columnWidth(upper, x), columnTop(upper, x) });
        }

        for (int[] column : candidates)
        {
            int x = column[0];
            int width = Math.min(Math.max(column[1], labelW), Math.max(0, screenW - x - EDGE));

            if (width < Math.min(labelW, column[1]))
            {
                continue;
            }

            for (int y = column[2]; y + BUTTON_H <= bottom; y += STEP)
            {
                if (free(taken, x, y, width))
                {
                    return new int[] { x, y, width };
                }
            }
        }

        int fallbackW = Math.min(labelW, Math.max(0, screenW - EDGE * 2));
        return new int[] { Math.max(EDGE, screenW - fallbackW - EDGE), columnTop, fallbackW };
    }

    /**
     * Левые края колонок справа налево. Колонка — край, с которого начинаются хотя бы две кнопки: одиночная
     * чужая кнопка (скажем, в углу окна) колонку не задаёт. Колонок нет — один самый правый край.
     */
    private static List<Integer> columns(List<int[]> upper)
    {
        List<Integer> out = new ArrayList<>();
        int rightmost = Integer.MIN_VALUE;

        for (int[] rect : upper)
        {
            rightmost = Math.max(rightmost, rect[0]);
            int same = 0;

            for (int[] other : upper)
            {
                same += other[0] == rect[0] ? 1 : 0;
            }

            if (same >= 2 && ! out.contains(rect[0]))
            {
                out.add(rect[0]);
            }
        }

        if (out.isEmpty())
        {
            out.add(rightmost);
        }

        out.sort((a, b) -> Integer.compare(b, a));
        return out;
    }

    private static int columnWidth(List<int[]> upper, int x)
    {
        int w = 0;

        for (int[] rect : upper)
        {
            w = rect[0] == x ? Math.max(w, rect[2]) : w;
        }

        return w;
    }

    private static int columnTop(List<int[]> upper, int x)
    {
        int top = Integer.MAX_VALUE;

        for (int[] rect : upper)
        {
            top = rect[0] == x ? Math.min(top, rect[1]) : top;
        }

        return top;
    }

    private static boolean free(List<int[]> taken, int x, int y, int w)
    {
        for (int[] rect : taken)
        {
            boolean apart = x + w <= rect[0] || rect[0] + rect[2] <= x || y + BUTTON_H <= rect[1] || rect[1] + rect[3] <= y;

            if (! apart)
            {
                return false;
            }
        }

        return true;
    }

    /** Кнопка в стиле меню Litematica с иконкой мода 16×16 слева; сама уходит из-под чужих кнопок. */
    private static final class MenuButton extends ButtonGeneric
    {
        private final GuiBase menu;
        private final int labelW;
        private boolean settleFailed;

        private MenuButton(GuiBase menu, int labelW, String label)
        {
            super(0, 0, labelW, BUTTON_H, label);
            this.menu = menu;
            this.labelW = labelW;
            this.moveTo(findSpot(taken(menu, this), menu.width, menu.height, labelW));
        }

        /**
         * Чужой мод положил кнопку или виджет на нашу (его инжект в {@code initGui} шёл после нашего, клетка у
         * него зашита) — уходим в свободную клетку. Без наложения кнопка стоит где стояла.
         */
        private void keepClear()
        {
            if (this.settleFailed)
            {
                return;
            }

            try
            {
                List<int[]> others = taken(this.menu, this);
                int[] target = MainMenuSkin.targetOf(this);
                int x = target != null ? target[0] : this.x;
                int y = target != null ? target[1] : this.y;

                if (! free(others, x, y, this.width))
                {
                    int[] spot = findSpot(others, this.menu.width, this.menu.height, this.labelW);

                    // В цветах Material HUD кнопка не прыгает, а плавно едет в свободную клетку
                    if (LitematicaSkin.enabled())
                    {
                        MainMenuSkin.slideTo(this, spot[0], spot[1], spot[2], MainMenuSkin.SLIDE_MS);
                    }
                    else
                    {
                        this.moveTo(spot);
                    }
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                // Рендер malilib без catch: лучше кнопка на старом месте, чем краш меню
                this.settleFailed = true;
                warnOnce("Could not keep the Material HUD button clear of other buttons in the Litematica main menu", e);
            }
        }

        private void moveTo(int[] spot)
        {
            this.setPosition(spot[0], spot[1]);
            this.setWidth(spot[2]);
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
        {
            this.keepClear();

            // Ванильный стиль: спрайт кнопки Minecraft и белая надпись по центру (иконка слева, отступы симметричны)
            if (Style.vanilla())
            {
                if (this.visible)
                {
                    this.hovered = this.isMouseOver(mouseX, mouseY);
                    Vanilla.button(ctx, this.x, this.y, this.width, this.height, this.enabled, this.hovered || selected);
                    Vanilla.buttonLabel(ctx, Minecraft.getInstance().font, this.displayString, this.x, this.y, this.width,
                            this.height, this.enabled);
                    Paint.texture(ctx, LayerListSettings.ICON, this.x + ICON_PAD, this.y + (this.height - ICON) / 2, ICON);
                }

                return;
            }

            super.render(ctx, mouseX, mouseY, selected);

            if (this.visible)
            {
                Paint.texture(ctx, LayerListSettings.ICON, this.x + ICON_PAD, this.y + (this.height - ICON) / 2, ICON);
            }
        }
    }
}
