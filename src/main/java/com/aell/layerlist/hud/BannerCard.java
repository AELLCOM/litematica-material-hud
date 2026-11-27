package com.aell.layerlist.hud;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.Nullable;

/**
 * «Рецепт флага при наведении» (24.11.2026, просьба владельца): в инвентаре или любом контейнере зажать Shift и
 * навести курсор на флаг с узором — вместо обычной подсказки рядом с ячейкой открывается карточка-карусель
 * ({@link BannerCardView}). Без Shift карточки нет (слово владельца 24.11.2026: «не надо показывать рецепт флага в
 * инвентаре, показывай его только если игрок зажмёт шифт и наведётся») — подсказка предмета остаётся обычной.
 *
 * <p>Карточка стоит вплотную к ячейке и не уходит, пока зажат Shift, а курсор на ячейке или на ней самой: на неё можно
 * перевести курсор и щёлкать. Листается колёсиком, клавишами ← → и стрелками в подвале. Подсказку предмета игра в это
 * время не рисует ({@link #hidesTooltip}): иначе она легла бы поверх. Щелчок с Shift мимо карточки — игрок перекладывает
 * предметы: карточка прячется, пока Shift не отпущен, и не закрывает соседние ячейки ({@link #quiet}). В отличие от
 * {@link LoomGuide}, флаг — любой, а не только из списка материалов. Та же карточка открывается у строки флага во
 * вкладке «Материалы» (там — простым наведением) и закрепляется у ткацкого станка ({@link BannerPin}).
 */
@Environment(EnvType.CLIENT)
public final class BannerCard
{
    static final int W = BannerCardView.W;
    static final int H = BannerCardView.H;
    /** Сколько карточка живёт без курсора: хватает перейти с ячейки на неё через зазор. */
    public static final long LINGER_MS = 180L;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("layerlist/banner-card");
    /** Отрисовка карточки упала: до перезапуска игры она выключена — карточка не повод ронять окно с ячейками. */
    private static boolean broken;
    private static @Nullable Screen shownOn;
    private static @Nullable Slot anchor;
    private static @Nullable BannerCardView view;
    private static long lastOver;
    /** С зажатым Shift щёлкнули мимо карточки: до отпускания Shift она не показывается. */
    private static boolean quiet;
    /** Только для стенда: Shift, которого нет у невидимого клиента ({@code null} — настоящая клавиша). */
    private static @Nullable Boolean forcedShift;

    private BannerCard() {}

    // ===== Показ =====

    /** Из хука экрана контейнера: после панели материалов и до предмета на курсоре. */
    public static void render(GuiGraphics graphics, Screen screen, int mouseX, int mouseY)
    {
        if (broken)
        {
            return;
        }

        // Это кадр GUI на клиентском потоке: исключение отсюда уронило бы игру
        try
        {
            BannerPin.render(graphics, screen, mouseX, mouseY);
            show(graphics, screen, mouseX, mouseY);
        }
        catch (RuntimeException | LinkageError e)
        {
            fail(e);
        }
    }

    /** Сбой отрисовки карточки (любой из трёх хозяев): выключить её до перезапуска игры, игру не ронять. */
    public static void fail(Throwable e)
    {
        broken = true;
        close();
        LOGGER.warn("Banner recipe card failed to draw and is switched off until the game restarts", e);
    }

    /** Карточка выключена настройкой или сбоем — для хозяев вне окна контейнера. */
    public static boolean off()
    {
        return broken || ! LayerListConfigs.BANNER_CARD.getBooleanValue();
    }

    /** Отрисовка карточки упала — закреплённую тоже не рисуем (настройка наведения её не касается). */
    static boolean broken()
    {
        return broken;
    }

    /** Зажат ли Shift (любой из двух). */
    private static boolean shift()
    {
        Boolean forced = forcedShift;
        return forced != null ? forced : Minecraft.getInstance().hasShiftDown();
    }

    private static void show(GuiGraphics graphics, Screen screen, int mouseX, int mouseY)
    {
        if (! (screen instanceof AbstractContainerScreen<?> container) || ! LayerListConfigs.BANNER_CARD.getBooleanValue()
                || ! container.getMenu().getCarried().isEmpty())
        {
            close();
            return;
        }

        // Только с зажатым Shift; после щелчка с Shift мимо карточки — молчим, пока Shift не отпустят
        if (! shift())
        {
            quiet = false;
            close();
            return;
        }

        if (quiet)
        {
            close();
            return;
        }

        long now = Util.getMillis();
        Slot hovered = ((IMixinAbstractContainerScreen) screen).layerlist_getHoveredSlot();
        ItemStack over = hovered != null && hovered.hasItem() ? hovered.getItem() : ItemStack.EMPTY;

        if (shownOn == screen && view != null && view.contains(mouseX, mouseY))
        {
            // Курсор на карточке: под ней могут лежать другие флаги — на них не переключаемся
            lastOver = now;
        }
        else if (patterned(over) && ! BannerPin.covers(screen, over, mouseX, mouseY))
        {
            if (shownOn != screen || view == null || hovered != anchor || ! ItemStack.isSameItemSameComponents(over, view.stack()))
            {
                open(container, hovered, over);
            }

            lastOver = now;
        }
        else if (shownOn != screen || now - lastOver > LINGER_MS)
        {
            close();
        }

        if (shownOn == screen && view != null)
        {
            view.draw(GuiContext.fromGuiGraphics(graphics), Minecraft.getInstance().font, mouseX, mouseY);
        }
    }

    /** Флаг с хотя бы одним узором. */
    public static boolean patterned(ItemStack stack)
    {
        return stack.getItem() instanceof BannerItem
                && ! stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty();
    }

    private static void open(AbstractContainerScreen<?> screen, Slot slot, ItemStack over)
    {
        shownOn = screen;
        anchor = slot;
        view = new BannerCardView(over);

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) screen;
        int slotX = geometry.layerlist_getLeftPos() + slot.x;
        int slotY = geometry.layerlist_getTopPos() + slot.y;
        // Вплотную справа от ячейки; не влезает — слева
        int x = slotX + 17;

        if (x + W > screen.width - 2)
        {
            x = slotX - 1 - W;
        }

        view.place(Math.max(2, Math.min(screen.width - W - 2, x)), Math.max(2, Math.min(screen.height - H - 2, slotY - 8)), 1.0F);
    }

    private static void close()
    {
        shownOn = null;
        anchor = null;
        view = null;
    }

    // ===== Ввод =====

    /**
     * Щелчок по карточке — её; ячейкам под ней он не достаётся. Наведённая карточка лежит поверх закреплённой.
     * Щелчок с Shift мимо карточек прячет наведённую до отпускания Shift.
     */
    public static boolean mouseClicked(Screen screen, double mouseX, double mouseY, int button)
    {
        if (screen != null && shownOn == screen && view != null && view.click(mouseX, mouseY, button))
        {
            return true;
        }

        if (BannerPin.mouseClicked(screen, mouseX, mouseY, button))
        {
            return true;
        }

        // Shift и щелчок мимо карточки — быстрый перенос предметов: карточка не должна выскакивать у каждого флага и
        // закрывать соседние ячейки, пока Shift не отпущен
        if (shift())
        {
            quiet = true;
            close();
        }

        return false;
    }

    /**
     * Колёсико, пока карточка открыта (курсор на флаге или на ней), листает страницы. В творческом инвентаре колёсико
     * над ячейкой листает список предметов — там карточку оно листает, только когда курсор на ней самой.
     */
    public static boolean mouseScrolled(Screen screen, double mouseX, double mouseY, double amount)
    {
        if (screen == null || amount == 0.0D)
        {
            return false;
        }

        if (shownOn == screen && view != null)
        {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
                    && ! view.contains(mouseX, mouseY))
            {
                return false;
            }

            view.turn(amount < 0.0D ? 1 : - 1);
            return true;
        }

        return BannerPin.mouseScrolled(screen, mouseX, mouseY, amount);
    }

    /** ← → листают, Backspace — назад со страницы «Как получить»; нет наведённой карточки — клавиши закреплённой. */
    public static boolean keyPressed(Screen screen, int key)
    {
        if (screen == null)
        {
            return false;
        }

        if (shownOn == screen && view != null)
        {
            return view.key(key);
        }

        return BannerPin.keyPressed(screen, key);
    }

    /** Пока карточка открыта, подсказку предмета экран не рисует: карточка её заменяет. */
    public static boolean hidesTooltip(Screen screen)
    {
        return screen != null && ((shownOn == screen && view != null) || BannerPin.hidesTooltip(screen));
    }

    // ===== Для стенда =====

    /** {x, y, ширина, высота} открытой карточки или {@code null}. */
    public static int @Nullable [] bounds()
    {
        return view == null ? null : new int[]{view.x(), view.y(), view.width(), view.height()};
    }

    /** Номер страницы с нуля, число страниц и открыта ли «Как получить» — одной строкой. */
    public static String state()
    {
        return view == null ? "закрыта" : view.state();
    }

    /** Середина первой щёлкаемой области, ведущей на «Как получить» (узор шага, строка предмета). */
    public static double @Nullable [] firstInfoSpot(int index)
    {
        return view == null ? null : view.infoSpot(index);
    }

    /** Только для харнесса. В игре никто не зовёт. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /**
         * Shift для стенда: игра читает клавишу из состояния клавиатуры SDL, а у невидимого клиента его нет.
         * {@code null} — снова настоящая клавиша.
         */
        public static void shift(@Nullable Boolean held)
        {
            forcedShift = held;
        }

        /** Карточка спрятана до отпускания Shift (после щелчка с Shift мимо неё). */
        public static boolean quiet()
        {
            return quiet;
        }
    }
}
