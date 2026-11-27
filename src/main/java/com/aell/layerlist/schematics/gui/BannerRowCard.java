package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.hud.BannerCard;
import com.aell.layerlist.hud.BannerCardView;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.malilib.render.GuiContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Карточка «как сделать флаг» у строки таблицы «Материалы» (просьба владельца 24.11.2026: «чтобы крафт флагов так же
 * открывался в списке материалов, при наведении на флаг»). Та же карточка-карусель, что у ячейки с флагом в окне
 * контейнера ({@link BannerCard}), только хозяин — таблица.
 *
 * <ul>
 *   <li>Открывается, когда курсор постоял на иконке или названии флага с узором — с той же задержкой, что подсказки
 *       этого экрана ({@link Ui#TIP_DELAY_MS}); подсказку названия она заменяет. Столбцы счёта оставлены своим
 *       подсказкам.</li>
 *   <li>Встаёт правее курсора (не влезает — левее) под строкой флага, а если снизу места нет — над ней: сама строка
 *       с названием и счётом остаётся видна, и вести курсор вниз по списку карточка не мешает. Держится, пока курсор
 *       на строке этого флага или на ней самой: на неё можно перевести курсор, листать и щёлкать.</li>
 *   <li>Колёсико листает карточку, только когда курсор на ней: над строками оно, как всегда, крутит таблицу.</li>
 *   <li>Пока открыто меню или окно поверх экрана, карточки нет.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class BannerRowCard
{
    /** Зазор между курсором и карточкой. */
    private static final int GAP = 10;
    /** Отступ карточки от краёв области таблицы. */
    private static final int EDGE = 2;
    /** Мельче карточку не ужимаем: на таком окне её не прочесть. */
    private static final float MIN_SCALE = 0.5F;

    private @Nullable BannerCardView view;
    /** Ключ строки, у которой открыта карточка. */
    private @Nullable String openKey;
    /** Ключ строки, на флаге которой стоит курсор, и с какого момента. */
    private @Nullable String armedKey;
    private long armedSince;
    private long lastOver;
    /** Флаг под курсором в этом кадре — сообщают ячейки иконки и названия. */
    private @Nullable String frameKey;
    private ItemStack frameStack = ItemStack.EMPTY;
    private int frameRowY;
    private int frameRowH;

    /** Курсор на открытой карточке: строки под ней его не видят. */
    boolean captures(double mouseX, double mouseY)
    {
        return this.view != null && this.view.contains(mouseX, mouseY);
    }

    /** Перед отрисовкой таблицы. */
    void beginFrame()
    {
        this.frameKey = null;
        this.frameStack = ItemStack.EMPTY;
    }

    /**
     * Из ячеек иконки и названия строки: курсор на этом предмете. Не флаг с узором — ничего не делает.
     *
     * @param rowY верх строки на экране
     * @param rowH её высота
     */
    void hover(String key, ItemStack stack, int rowY, int rowH)
    {
        if (BannerCard.patterned(stack))
        {
            this.frameKey = key;
            this.frameStack = stack;
            this.frameRowY = rowY;
            this.frameRowH = rowH;
        }
    }

    /**
     * После таблицы, поверх неё.
     *
     * @param rowKey  ключ строки под курсором (в любом столбце) или {@code null}
     * @param allowed курсор не под меню или окном и поверх экрана ничего не открыто
     */
    void render(GuiContext ctx, int mouseX, int mouseY, @Nullable String rowKey, int areaX, int areaY, int areaW, int areaH,
            boolean allowed)
    {
        if (! allowed || BannerCard.off())
        {
            this.close();
            return;
        }

        // Кадр GUI на клиентском потоке: исключение отсюда уронило бы игру
        try
        {
            this.frame(ctx, mouseX, mouseY, rowKey, areaX, areaY, areaW, areaH);
        }
        catch (RuntimeException | LinkageError e)
        {
            this.close();
            BannerCard.fail(e);
        }
    }

    private void frame(GuiContext ctx, int mouseX, int mouseY, @Nullable String rowKey, int areaX, int areaY, int areaW, int areaH)
    {
        long now = Util.getMillis();

        if (this.captures(mouseX, mouseY))
        {
            this.lastOver = now;
            this.armedKey = null;
        }
        else if (this.frameKey != null && ! this.frameKey.equals(this.openKey))
        {
            // Курсор на флаге, чья карточка ещё не открыта: ждём, как подсказку
            if (! this.frameKey.equals(this.armedKey))
            {
                this.armedKey = this.frameKey;
                this.armedSince = now;
            }

            if (now - this.armedSince >= Ui.TIP_DELAY_MS)
            {
                this.open(this.frameKey, this.frameStack, mouseX, mouseY, this.frameRowY, this.frameRowH, areaX, areaY, areaW, areaH);
                this.lastOver = now;
            }
        }
        else
        {
            this.armedKey = null;

            // Курсор на строке этого флага (в любом столбце): карточка стоит
            if (this.view != null && rowKey != null && rowKey.equals(this.openKey))
            {
                this.lastOver = now;
            }
        }

        if (this.view != null && now - this.lastOver > BannerCard.LINGER_MS)
        {
            this.view = null;
            this.openKey = null;
        }

        if (this.view != null || this.armedKey != null)
        {
            // Карточка вместо подсказки: ни до неё, ни вместе с ней подсказка не выскакивает
            Tooltip.reset();
        }

        if (this.view != null)
        {
            // Выше строк таблицы: их текст и иконки рисуются поверх заливок своего слоя
            ctx.elementUp();
            this.view.draw(ctx, Kit.font(), mouseX, mouseY);
        }
    }

    private void open(String key, ItemStack stack, int mouseX, int mouseY, int rowY, int rowH, int areaX, int areaY, int areaW,
            int areaH)
    {
        float scale = Math.min(1.0F, Math.min((areaW - EDGE * 2) / (float) BannerCardView.W, (areaH - EDGE * 2) / (float) BannerCardView.H));

        if (scale < MIN_SCALE)
        {
            return;
        }

        int w = (int) Math.ceil(BannerCardView.W * scale);
        int h = (int) Math.ceil(BannerCardView.H * scale);
        int x = mouseX + GAP;

        if (x + w > areaX + areaW - EDGE)
        {
            x = mouseX - GAP - w;
        }

        x = Ui.clamp(x, areaX + EDGE, Math.max(areaX + EDGE, areaX + areaW - EDGE - w));
        int top = areaY + EDGE;
        int bottom = areaY + areaH - EDGE;
        int y;

        if (rowY + rowH + 1 + h <= bottom)
        {
            // Под строкой: она остаётся видна целиком
            y = rowY + rowH + 1;
        }
        else if (rowY - 1 - h >= top)
        {
            y = rowY - 1 - h;
        }
        else
        {
            // Ни снизу, ни сверху места нет: сбоку от курсора, строка флага — в верхней четверти карточки
            y = Ui.clamp(mouseY - h / 4, top, Math.max(top, bottom - h));
        }

        BannerCardView next = new BannerCardView(stack);
        next.place(x, y, scale);
        this.view = next;
        this.openKey = key;
        this.armedKey = null;
    }

    void close()
    {
        this.view = null;
        this.openKey = null;
        this.armedKey = null;
    }

    boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        return this.view != null && this.view.click(mouseX, mouseY, button);
    }

    /** Колёсико листает карточку, только когда курсор на ней. */
    boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        if (this.view == null || amount == 0.0D || ! this.view.contains(mouseX, mouseY))
        {
            return false;
        }

        this.view.turn(amount < 0.0D ? 1 : - 1);
        return true;
    }

    /** ← → листают, Backspace — назад со страницы «Как получить». */
    boolean keyPressed(int key)
    {
        return this.view != null && this.view.key(key);
    }

    // ===== Для стенда =====

    /** {x, y, ширина, высота} открытой карточки или {@code null}. */
    int @Nullable [] bounds()
    {
        return this.view == null ? null : new int[]{this.view.x(), this.view.y(), this.view.width(), this.view.height()};
    }

    String state()
    {
        return this.view == null ? "закрыта" : this.view.state();
    }

    @Nullable String openKey()
    {
        return this.openKey;
    }

    double @Nullable [] infoSpot(int index)
    {
        return this.view == null ? null : this.view.infoSpot(index);
    }
}
