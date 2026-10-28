package com.aell.layerlist.hud;

import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import com.aell.layerlist.mixin.IMixinAbstractRecipeBookScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Место панели рядом с открытым контейнером. Считается в масштабированных пикселях экрана —
 * в тех же единицах, что {@code leftPos}/{@code topPos} экрана и
 * {@code GuiUtils.getScaledWindowWidth()}: масштаб GUI и разрешение окна учитываются сами собой,
 * масштаб панели — её размером в пикселях, который сюда и передают.
 *
 * <p>Порядок: со стороны из настроек («справа» по умолчанию с 1.0.1, раньше всегда слева), верх
 * вровень с верхом окна, если панель помещается между окном и краем экрана; иначе с другой стороны;
 * не влезает ни туда, ни туда в своём масштабе — ужимается, сначала под свою сторону, потом под
 * другую, но не ниже половины масштаба (и не ниже 0.5); не помогло и это — {@code null}, и панель
 * остаётся на своём обычном месте. Слева учитывается открытая книга
 * рецептов (инвентарь, верстак, печи), справа — столбец эффектов инвентаря и творческого режима.
 */
@Environment(EnvType.CLIENT)
public final class HudDock
{
    /** Зазор между панелью и окном контейнера. */
    public static final int GAP = 4;

    /** Отступ панели от краёв экрана. */
    public static final int MARGIN = 2;

    /** Ниже этой доли своего масштаба панель не ужимается — лучше остаться на обычном месте. */
    public static final double MIN_SHRINK = 0.5D;

    /** Абсолютный нижний предел масштаба при ужатии. */
    public static final double MIN_SCALE = 0.5D;

    /**
     * Место панели: левый верхний угол в пикселях экрана и масштаб, в котором её рисовать;
     * {@code buttonY} — верх кнопки над панелью ({@code -1} — кнопки нет).
     */
    public record Dock(int x, int y, double scale, int buttonY)
    {
        public Dock(int x, int y, double scale)
        {
            this(x, y, scale, -1);
        }

        public boolean hasButton()
        {
            return this.buttonY >= 0;
        }
    }

    // Ванильная раскладка (26.3): RecipeBookComponent.OFFSET_X_POSITION, порог «узкого окна»
    // из AbstractRecipeBookScreen.init и столбец эффектов из EffectsInInventory
    private static final int BOOK_OFFSET_X = 86;
    private static final int BOOK_NARROW_WINDOW = 379;
    private static final int EFFECTS_GAP = 2;
    private static final int EFFECTS_COMPACT = 32;
    private static final int EFFECTS_FULL = 120;

    private HudDock() {}

    /**
     * @param layoutW   ширина панели в её собственных единицах (до масштаба)
     * @param layoutH   высота панели в её собственных единицах
     * @param scale     масштаб панели из настроек
     * @param preferRight сначала пробовать место справа от окна, а не слева
     * @return место и масштаб или {@code null}, если это не экран контейнера или панель не
     *         помещается ни с одной стороны даже ужатой
     */
    public static @Nullable Dock beside(@Nullable Screen screen, int layoutW, int layoutH, double scale,
            boolean preferRight)
    {
        return beside(screen, layoutW, layoutH, scale, preferRight, 0);
    }

    /**
     * То же, но над панелью оставить {@code reserveTop} пикселей под кнопку. Кнопка встаёт вровень с
     * верхом окна контейнера и никогда не выше него, панель — под ней; не влезает по высоте — панель
     * ужимается (не ниже тех же пределов), не помогло и это — место без кнопки, как у {@link #beside}.
     */
    public static @Nullable Dock beside(@Nullable Screen screen, int layoutW, int layoutH, double scale,
            boolean preferRight, int reserveTop)
    {
        Dock plain = place(screen, layoutW, layoutH, scale, preferRight);

        if (plain == null || reserveTop <= 0)
        {
            return plain;
        }

        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
        IMixinAbstractContainerScreen access = (IMixinAbstractContainerScreen) container;
        int top = Math.max(0, access.layerlist_getTopPos());
        int room = container.height - MARGIN - top - reserveTop;
        double fitted = plain.scale();

        if ((int) Math.ceil(layoutH * fitted) > room)
        {
            double fit = shrunk(room, layoutH);

            if (fit < Math.max(MIN_SCALE, scale * MIN_SHRINK))
            {
                return plain;
            }

            fitted = Math.min(fitted, fit);
        }

        int oldWidth = (int) Math.ceil(layoutW * plain.scale());
        int width = (int) Math.ceil(layoutW * fitted);
        boolean onLeft = plain.x() < access.layerlist_getLeftPos();
        int x = onLeft ? plain.x() + oldWidth - width : plain.x();

        return new Dock(x, top + reserveTop, fitted, top);
    }

    private static @Nullable Dock place(@Nullable Screen screen, int layoutW, int layoutH, double scale,
            boolean preferRight)
    {
        if (! (screen instanceof AbstractContainerScreen<?> container) || layoutW <= 0 || scale <= 0.0D)
        {
            return null;
        }

        IMixinAbstractContainerScreen access = (IMixinAbstractContainerScreen) container;
        int top = access.layerlist_getTopPos();
        int left = access.layerlist_getLeftPos();
        int right = left + access.layerlist_getImageWidth();

        left = Math.min(left, recipeBookLeft(container, left));
        right = Math.max(right, effectsRight(container, right));

        int roomLeft = left - GAP - MARGIN;
        int roomRight = container.width - MARGIN - right - GAP;
        int roomFirst = preferRight ? roomRight : roomLeft;
        int roomSecond = preferRight ? roomLeft : roomRight;
        int width = (int) Math.ceil(layoutW * scale);
        double fitted = scale;
        boolean onFirst;

        if (width <= roomFirst)
        {
            onFirst = true;
        }
        else if (width <= roomSecond)
        {
            onFirst = false;
        }
        else
        {
            // В своём масштабе не влезает — ужимаем, сначала под свою сторону, шаг сотая
            double min = Math.max(MIN_SCALE, scale * MIN_SHRINK);
            double fitFirst = shrunk(roomFirst, layoutW);
            double fitSecond = shrunk(roomSecond, layoutW);

            if (fitFirst >= min)
            {
                onFirst = true;
                fitted = fitFirst;
            }
            else if (fitSecond >= min)
            {
                onFirst = false;
                fitted = fitSecond;
            }
            else
            {
                return null;
            }

            width = (int) Math.ceil(layoutW * fitted);
        }

        boolean onLeft = onFirst != preferRight;
        int height = (int) Math.ceil(layoutH * fitted);
        int x = onLeft ? left - GAP - width : right + GAP;
        int y = Math.max(0, Math.min(top, container.height - MARGIN - height));

        return new Dock(x, y, fitted);
    }

    /** Масштаб, в котором панель ширины {@code layoutW} встаёт в {@code room} пикселей; 0 — места нет. */
    private static double shrunk(int room, int layoutW)
    {
        return room <= 0 ? 0.0D : Math.floor(room / (double) layoutW * 100.0D) / 100.0D;
    }

    /** Левый край занятого места с учётом открытой книги рецептов. */
    private static int recipeBookLeft(AbstractContainerScreen<?> screen, int left)
    {
        if (! (screen instanceof AbstractRecipeBookScreen<?>))
        {
            return left;
        }

        try
        {
            RecipeBookComponent<?> book = ((IMixinAbstractRecipeBookScreen) screen).layerlist_getRecipeBook();

            if (book == null || ! book.isVisible())
            {
                return left;
            }

            // Книга и окно отцентрованы вместе (RecipeBookComponent.updateScreenPosition); в узком
            // окне книга ложится поверх окна контейнера, и левее него ничего нет
            int offset = screen.width < BOOK_NARROW_WINDOW ? 0 : BOOK_OFFSET_X;

            return Math.min(left, (screen.width - RecipeBookComponent.IMAGE_WIDTH) / 2 - offset);
        }
        catch (RuntimeException | LinkageError e)
        {
            return left;
        }
    }

    /** Правый край занятого места с учётом столбца эффектов инвентаря. */
    private static int effectsRight(AbstractContainerScreen<?> screen, int right)
    {
        if (! (screen instanceof InventoryScreen) && ! (screen instanceof CreativeModeInventoryScreen))
        {
            return right;
        }

        try
        {
            LocalPlayer player = Minecraft.getInstance().player;

            if (player == null || player.getActiveEffects().isEmpty())
            {
                return right;
            }

            // EffectsInInventory: столбец начинается через 2 px от окна; при запасе от 120 px —
            // с названиями (120), от 32 — одни значки (32), меньше 32 не рисуется вовсе
            int start = right + EFFECTS_GAP;
            int room = screen.width - start;

            if (room < EFFECTS_COMPACT)
            {
                return right;
            }

            return start + (room >= EFFECTS_FULL ? EFFECTS_FULL : EFFECTS_COMPACT);
        }
        catch (RuntimeException | LinkageError e)
        {
            return right;
        }
    }
}
