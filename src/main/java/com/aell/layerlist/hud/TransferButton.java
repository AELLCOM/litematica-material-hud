package com.aell.layerlist.hud;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.easyplace.ItemData;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Draw;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.util.Chat;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кнопка «Перенести всё необходимое» над панелью у открытого контейнера. Щелчок забирает из контейнера
 * ровно столько, сколько не хватает по строкам текущей страницы панели (недостающее минус то, что уже с
 * собой): нужная целиком стопка — Shift+кликом, из большей — ровно нехватка, остаток остаётся в контейнере.
 * Слоты игрока, результат крафта и торговли не трогаются.
 *
 * <p>Щелчок ловится раньше всех ({@code MixinMouseHandlerTransfer}, {@code MouseHandler.onButton}):
 * моды с оверлеями поверх экрана контейнера могут забрать его до {@code Screen.mouseClicked}. Щелчки по
 * слотам — все за один тик ({@code hudTransferInstant}, по умолчанию) или в темпе быстрого игрока, 4 за тик;
 * часть стопки берётся кратчайшей цепочкой ({@link #exactClicks}). Итог считается по инвентарю после ответа
 * сервера и показывается над хотбаром, даже если сообщения в чат выключены. Автоперенос, забравший своё, закрывает
 * хранилище, если нужного в нём больше нет ({@code hudTransferAutoClose}, по умолчанию выкл.).
 *
 * <p>«Что уже с собой» считается заново в момент щелчка, а не берётся из строки панели: строка
 * обновляется с задержкой, и второй сундук подряд забирал бы ту же нехватку ещё раз. После ответа
 * сервера итог сверяется с целью: на сервере щелчки по части стопки иногда расходятся с предсказанием
 * клиента — лишнее возвращается в контейнер, недобор добирается ещё одним проходом.
 *
 * <p>Справа маленькая кнопка «Auto»: включена — перенос сам запускается, когда открывается хранилище
 * (сундук, бочка, шалкер, раздатчик, воронка) и сервер прислал его содержимое, — даже если панель
 * материалов скрыта клавишей (Y).
 *
 * <p>Опция «Забирать шалкеры» ({@code hudTransferShulkers}): чего не хватило россыпью, добирается шалкерами из
 * контейнера, в которых это лежит, — целиком (часть содержимого из закрытого шалкера не взять). Содержимое
 * засчитывается в нехватку; взятое россыпью до шалкера в контейнер не возвращается. Шалкеры без нужного не трогаются.
 * Если россыпь не влезет в инвентарь (стопок больше, чем свободных слотов), шалкеры берутся первыми, а россыпью —
 * только остаток.
 */
@Environment(EnvType.CLIENT)
public final class TransferButton
{
    public static final int HEIGHT = 14;
    public static final int GAP = 2;
    private static final float MIN_TEXT_SCALE = 0.6F;
    /** Левая кнопка мыши. В 26.3 номера кнопок — SDL (левая — 1), а не GLFW (левая — 0). */
    private static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    /** Сколько тиков ждать ответа сервера после последнего Shift+клика, прежде чем подводить итог. */
    private static final int SETTLE_TICKS = 6;
    /** Щелчков по слотам за тик — темп быстрого игрока, не пачка за кадр (мгновенный перенос выключен). */
    private static final int CLICKS_PER_TICK = 4;
    /** Мгновенный перенос: щелчков за тик, не больше (страховка от зацикливания). */
    private static final int INSTANT_CLICKS = 256;
    /** Сверок с сервером (проход + добор/возврат) на один перенос, не больше. */
    private static final int MAX_PASSES = 8;
    /** Сколько тиков, не больше, окно ждёт конца переноса после просьбы закрыть (4 с). */
    private static final int CLOSE_HOLD_TICKS = 80;
    /** Тиков после прихода содержимого контейнера до автопереноса. */
    private static final int AUTO_DELAY = 3;
    private static final String AUTO_LABEL = "Auto";
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");

    /** Где кнопка стоит в этом кадре; {@code width == 0} — не нарисована. */
    private static int bx;
    private static int by;
    private static int bw;
    private static int bh;
    /** Кнопка «Auto» в этом кадре, в пикселях экрана; {@code aw == 0} — не нарисована. */
    private static int ax;
    private static int ay;
    private static int aw;
    private static int ah;
    private static String lastPose = "нет";
    private static boolean poseWarned;
    private static @Nullable Screen drawnOn;
    /** Нажатие пришлось на кнопку — отпускание той же кнопки мыши экрану тоже не отдаём. */
    private static boolean pressed;
    private static @Nullable Job job;
    /** Экран, на котором автоперенос уже отработал (или решил не работать). */
    private static @Nullable Screen autoSeen;
    private static int autoWait;

    private TransferButton() {}

    /** Сколько пикселей экрана кнопка занимает над панелью. */
    public static int reserve()
    {
        return HEIGHT + GAP;
    }

    /** Нужна ли кнопка на этом экране: контейнер с чужими слотами, не инвентарь игрока. */
    public static boolean wanted(@Nullable Screen screen)
    {
        return LayerListConfigs.HUD_TRANSFER_BUTTON.getBooleanValue()
                && screen instanceof AbstractContainerScreen<?> container
                && ! (screen instanceof InventoryScreen) && ! (screen instanceof CreativeModeInventoryScreen)
                && hasStorage(container.getMenu());
    }

    private static boolean hasStorage(AbstractContainerMenu menu)
    {
        for (Slot slot : menu.slots)
        {
            if (source(slot))
            {
                return true;
            }
        }

        return false;
    }

    /** Слот, из которого можно забирать: не инвентарь игрока и не выход крафта/торговли. */
    private static boolean source(Slot slot)
    {
        return ! (slot.container instanceof Inventory) && ! (slot.container instanceof CraftingContainer)
                && ! (slot.container instanceof ResultContainer) && ! (slot instanceof ResultSlot)
                && ! (slot instanceof MerchantResultSlot);
    }

    static void forget()
    {
        bw = 0;
        aw = 0;
        drawnOn = null;
    }

    /** Хранилище, у которого автоперенос уместен: не печь, не верстак, не торговля. */
    private static boolean storage(AbstractContainerMenu menu)
    {
        return menu instanceof net.minecraft.world.inventory.ChestMenu
                || menu instanceof net.minecraft.world.inventory.ShulkerBoxMenu
                || menu instanceof net.minecraft.world.inventory.DispenserMenu
                || menu instanceof net.minecraft.world.inventory.HopperMenu;
    }

    private static int[] screenRect(org.joml.Matrix3x2f pose, int x, int y, int w, int h)
    {
        org.joml.Vector2f from = pose.transformPosition(new org.joml.Vector2f(x, y));
        org.joml.Vector2f to = pose.transformPosition(new org.joml.Vector2f(x + w, y + h));

        return new int[]{(int) Math.floor(Math.min(from.x, to.x)), (int) Math.floor(Math.min(from.y, to.y)),
                (int) Math.ceil(Math.abs(to.x - from.x)), (int) Math.ceil(Math.abs(to.y - from.y))};
    }

    /** Нарисовать кнопку шириной с панель; {@code x}, {@code y}, {@code width} — пиксели экрана. */
    static void draw(GuiContext ctx, Font font, Screen screen, int x, int y, int width, int mouseX, int mouseY)
    {
        // Место для щелчка — там, где кнопку видно: если выше по стеку кто-то сдвинул или растянул
        // отрисовку, координаты рисования и мыши расходятся
        org.joml.Matrix3x2f pose = ctx.pose();
        int autoWidth = font.width(AUTO_LABEL) + 10;
        int mainWidth = width - autoWidth - GAP;
        int[] main = screenRect(pose, x, y, mainWidth, HEIGHT);
        int[] auto = screenRect(pose, x + mainWidth + GAP, y, autoWidth, HEIGHT);
        bx = main[0];
        by = main[1];
        bw = main[2];
        bh = main[3];
        ax = auto[0];
        ay = auto[1];
        aw = auto[2];
        ah = auto[3];
        drawnOn = screen;
        boolean identity = pose.m00() == 1.0F && pose.m11() == 1.0F && pose.m01() == 0.0F && pose.m10() == 0.0F
                && pose.m20() == 0.0F && pose.m21() == 0.0F;
        lastPose = identity ? "без сдвига" : pose.toString();

        if (! identity && ! poseWarned)
        {
            poseWarned = true;
            LOGGER.info("[layerlist] кнопка переноса рисуется со сдвигом {} (экран {})", pose,
                    screen.getClass().getSimpleName());
        }

        boolean busy = job != null && job.screen == screen;
        boolean any = busy || ! plan(screen, null).isEmpty();
        boolean hover = any && ! busy && hit(mouseX, mouseY);
        boolean autoOn = LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue();
        boolean autoHover = hitAuto(mouseX, mouseY);
        boolean vanilla = Style.vanilla();
        int fill = hover ? Theme.GREEN_FILL : any ? Theme.PANEL : Theme.PANEL_DEEP;
        int edge = hover || busy ? Theme.GREEN_EDGE : Theme.EDGE;

        if (vanilla)
        {
            // Ванильный стиль: обычная кнопка Minecraft, нет работы — выключенная
            Vanilla.button(ctx, x, y, mainWidth, HEIGHT, any, hover || busy);
        }
        else
        {
            Draw.card(ctx, x, y, mainWidth, HEIGHT, 4, fill, edge);
        }

        // Наведена «Auto» — на большой кнопке подсказка, что она делает
        String label = StringUtils.translate(autoHover ? (autoOn ? "layerlist.hud.transfer.auto_on_tip"
                : "layerlist.hud.transfer.auto_off_tip")
                : busy ? "layerlist.hud.transfer.busy" : "layerlist.hud.transfer");
        int color = autoHover ? Theme.MUTED : any ? (hover || busy ? Theme.GREEN : Theme.TEXT) : Theme.MUTED;

        if (vanilla)
        {
            color = autoHover || ! any ? Style.V_GRAY : hover || busy ? Style.V_HOVER : Style.V_WHITE;
        }

        label(ctx, font, label, x, y, mainWidth, color);

        // «Auto»: включена — светится золотом (мягкий ореол вокруг), выключена — те же цвета, что у
        // большой кнопки, и то же наведение
        int autoX = x + mainWidth + GAP;
        int autoFill;
        int autoEdge;
        int autoText;

        if (vanilla)
        {
            // Auto: включена — золотая надпись на кнопке Minecraft, без ореола
            autoFill = 0;
            autoEdge = 0;
            autoText = autoOn ? Style.V_GOLD : any || autoHover ? Style.V_WHITE : Style.V_GRAY;
        }
        else if (autoOn)
        {
            Draw.card(ctx, autoX - 1, y - 1, autoWidth + 2, HEIGHT + 2, 5, 0, Theme.GOLD_GLOW);
            autoFill = autoHover ? 0x4DF5C451 : Theme.GOLD_FILL;
            autoEdge = Theme.GOLD_EDGE;
            autoText = Theme.GOLD;
        }
        else
        {
            autoFill = autoHover ? Theme.GREEN_FILL : any ? Theme.PANEL : Theme.PANEL_DEEP;
            autoEdge = autoHover ? Theme.GREEN_EDGE : Theme.EDGE;
            autoText = autoHover ? Theme.GREEN : any ? Theme.TEXT : Theme.MUTED;
        }

        if (vanilla)
        {
            Vanilla.button(ctx, autoX, y, autoWidth, HEIGHT, autoOn || any || autoHover, autoHover);
        }
        else
        {
            Draw.card(ctx, autoX, y, autoWidth, HEIGHT, 4, autoFill, autoEdge);
        }

        label(ctx, font, AUTO_LABEL, autoX, y, autoWidth, autoText);
    }

    private static void label(GuiContext ctx, Font font, String label, int x, int y, int width, int color)
    {
        int room = width - 8;
        int textWidth = font.width(label);
        float scale = textWidth > room ? Math.max(MIN_TEXT_SCALE, room / (float) textWidth) : 1.0F;

        if (scale < 1.0F)
        {
            String shown = Draw.fit(font, label, (int) (room / scale));
            ctx.pose().pushMatrix();
            ctx.pose().translate(x + width / 2.0F, y + (HEIGHT - font.lineHeight * scale) / 2.0F + 0.5F);
            ctx.pose().scale(scale, scale);
            Paint.text(ctx, font, shown, -font.width(shown) / 2, 0, color, false);
            ctx.pose().popMatrix();
        }
        else
        {
            Paint.text(ctx, font, label, x + (width - textWidth) / 2, y + (HEIGHT - 8) / 2, color, false);
        }
    }

    /** Где кнопка видна в последнем кадре — {x, y, ширина, высота}; ширина 0 — не нарисована. Для самопроверки. */
    public static int[] bounds()
    {
        return new int[]{bx, by, bw, bh};
    }

    /** Где кнопка «Auto» в последнем кадре — как {@link #bounds()}. Для самопроверки. */
    public static int[] autoBounds()
    {
        return new int[]{ax, ay, aw, ah};
    }

    /** Трансформация отрисовки в последнем кадре кнопки — для самопроверки. */
    public static String lastPose()
    {
        return lastPose;
    }

    /** Идёт ли перенос — для самопроверки. */
    public static boolean busy()
    {
        return job != null;
    }

    private static boolean hit(double mouseX, double mouseY)
    {
        return bw > 0 && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
    }

    private static boolean hitAuto(double mouseX, double mouseY)
    {
        return aw > 0 && mouseX >= ax && mouseX < ax + aw && mouseY >= ay && mouseY < ay + ah;
    }

    /**
     * Кнопка мыши, как её видит {@code MouseHandler} ({@code action}: {@code InputConstants.PRESS} — нажатие,
     * {@code RELEASE} — отпускание);
     * истина — событие наше, дальше его не пускать.
     */
    public static boolean onMouseButton(@Nullable Screen screen, double mouseX, double mouseY, int button, int action)
    {
        if (action == com.mojang.blaze3d.platform.InputConstants.RELEASE)
        {
            boolean ours = pressed && button == LEFT;
            pressed &= ! ours;
            return ours;
        }

        if (action != com.mojang.blaze3d.platform.InputConstants.PRESS || ! mouseClicked(screen, mouseX, mouseY, button))
        {
            // Для разбора жалоб «не нажимается»: левый щелчок рядом с кнопкой, но мимо неё
            if (action == com.mojang.blaze3d.platform.InputConstants.PRESS && button == LEFT && bw > 0 && screen == drawnOn && mouseX >= bx - 24 && mouseX < bx + bw + 24
                    && mouseY >= by - 24 && mouseY < by + bh + 24)
            {
                LOGGER.info("[layerlist] щелчок рядом с кнопкой переноса, мимо: мышь {}×{}, кнопка {}×{} {}×{}",
                        (int) mouseX, (int) mouseY, bx, by, bw, bh);
            }

            return false;
        }

        pressed = true;
        return true;
    }

    /** Щелчок в экране контейнера; истина — щелчок по кнопке, экрану его не отдавать. */
    public static boolean mouseClicked(@Nullable Screen screen, double mouseX, double mouseY, int button)
    {
        if (button != LEFT || screen == null || screen != drawnOn || ! wanted(screen))
        {
            return false;
        }

        if (hitAuto(mouseX, mouseY))
        {
            Vanilla.click();
            toggleAuto();
            return true;
        }

        if (! hit(mouseX, mouseY))
        {
            return false;
        }

        // Звук только если кнопка не выключена (есть работа или перенос идёт)
        if (job != null || ! plan(screen, null).isEmpty())
        {
            Vanilla.click();
        }

        start((AbstractContainerScreen<?>) screen, false);
        return true;
    }

    private static void toggleAuto()
    {
        boolean on = ! LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue();
        LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(on);
        new LayerListConfigs().save();
        // Включили в открытом сундуке — пусть сразу и заберёт
        autoSeen = null;
        autoWait = 0;
        Minecraft.getInstance().gui.hud.setOverlayMessage(Chat.paint(Component.translatable(on
                ? "layerlist.message.transfer_auto_on" : "layerlist.message.transfer_auto_off"),
                on ? Chat.OK : Chat.DIM), false);
    }

    // ===== Перенос =====

    /** Строка страницы на время переноса: что нужно и сколько ещё не хватает. */
    private static final class Need
    {
        final ItemStack required;
        int left;

        Need(ItemStack required, int left)
        {
            this.required = required;
            this.left = left;
        }
    }

    /** Щелчок по слоту; {@code home} — куда вернуть курсор, если окно закроют посреди цепочки ({@code -1} — сам слот). */
    private record Click(int slot, int button, ContainerInput input, int home)
    {
        Click(int slot, int button, ContainerInput input)
        {
            this(slot, button, input, -1);
        }
    }

    private static final class Job
    {
        final AbstractContainerScreen<?> screen;
        final int containerId;
        final List<Need> needs;
        /** Сколько таких предметов было у игрока до переноса — итог считается по разнице. */
        final int[] before;
        /** Сколько должно стать: было плюс нехватка. Сверка после ответа сервера идёт к этой цели. */
        final int[] want;
        final boolean auto;
        /** Щелчки по слотам, ещё не отправленные (точный перенос части стопки). */
        final java.util.ArrayDeque<Click> clicks = new java.util.ArrayDeque<>();
        int sent;
        int settle;
        int pass = 1;
        int returned;
        boolean full;
        /** Слот, откуда взято то, что сейчас на курсоре; {@code -1} — курсор пуст. */
        int home = -1;
        /** Сколько тиков окно держится открытым после просьбы закрыть; {@code -1} — закрыть не просили. */
        int closing = -1;
        /** Сколько по каждой строке пришло внутри забранных шалкеров. */
        final int[] boxed;
        /** Забранные шалкеры (копии до щелчка) — в итоге сверяется, что они у игрока. */
        final List<ItemStack> boxes = new ArrayList<>();

        Job(AbstractContainerScreen<?> screen, List<Need> needs, int[] before, boolean auto)
        {
            this.screen = screen;
            this.containerId = screen.getMenu().containerId;
            this.needs = needs;
            this.before = before;
            this.want = new int[needs.size()];
            this.boxed = new int[needs.size()];
            this.auto = auto;

            for (int i = 0; i < this.want.length; ++i)
            {
                this.want[i] = before[i] + needs.get(i).left;
            }
        }
    }

    /** Что забрать; {@code inside != null} — шалкер целиком, в нём столько по каждой строке. */
    private record Move(Slot slot, Need need, int @Nullable [] inside) {}

    /**
     * Нехватка по строкам страницы. «Есть с собой» — заново по инвентарю сейчас (как его считает Litematica:
     * с шалкерами и мешками, по её настройке — и с эндер-сундуком), а не из строки: строка обновляется с
     * задержкой, и второй сундук подряд забрал бы ту же нехватку ещё раз.
     */
    private static List<Need> needs()
    {
        List<Need> needs = new ArrayList<>();
        LocalPlayer player = Minecraft.getInstance().player;
        List<MaterialListEntry> page = LayerCountManager.getInstance().getPageEntries();
        List<MaterialListEntry> live = new ArrayList<>(page.size());

        for (MaterialListEntry entry : page)
        {
            live.add(new MaterialListEntry(entry.getStack(), entry.getCountTotal(), entry.getCountMissing(),
                    entry.getCountMismatched(), entry.getCountAvailable()));
        }

        if (player != null)
        {
            try
            {
                fi.dy.masa.litematica.materials.MaterialListUtils.updateAvailableCounts(live, player);
            }
            catch (LinkageError | RuntimeException e)
            {
                // Другая Litematica — остаётся счёт из строки
            }
        }

        for (int i = 0; i < page.size(); ++i)
        {
            MaterialListEntry entry = page.get(i);
            int have = DataItems.hasData(entry.getStack()) ? DataItems.available(player, entry.getStack())
                    : live.get(i).getCountAvailable();
            int left = entry.getCountMissing() - have;

            if (left > 0)
            {
                needs.add(new Need(entry.getStack(), left));
            }
        }

        return needs;
    }

    /** Какие слоты контейнера перенести, пока у строки есть нехватка; {@code null} — по странице сейчас. */
    private static List<Move> plan(Screen screen, @Nullable List<Need> given)
    {
        List<Move> moves = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();

        if (! (screen instanceof AbstractContainerScreen<?> container) || mc.player == null
                || ! container.getMenu().getCarried().isEmpty())
        {
            return moves;
        }

        List<Need> needs = given != null ? given : needs();

        if (needs.isEmpty())
        {
            return moves;
        }

        int[] left = new int[needs.size()];

        for (int i = 0; i < left.length; ++i)
        {
            left[i] = needs.get(i).left;
        }

        AbstractContainerMenu menu = container.getMenu();
        boolean boxes = LayerListConfigs.HUD_TRANSFER_SHULKERS.getBooleanValue();

        // Россыпь не влезет в инвентарь (стопок больше, чем свободных слотов) — шалкеры вперёд: шалкер занимает один
        // слот, а везёт до 27 стопок; россыпью — только то, что шалкеры не покрыли
        if (boxes)
        {
            List<Move> loose = new ArrayList<>();
            addLoose(menu, mc.player, needs, left.clone(), loose);

            if (loose.size() > freeSlots(menu))
            {
                addBoxes(menu, mc.player, needs, left, moves);
                addLoose(menu, mc.player, needs, left, moves);
                return moves;
            }
        }

        // Обычно: россыпь, а чего не хватило — шалкерами (опция); шалкер уходит целиком
        addLoose(menu, mc.player, needs, left, moves);

        if (boxes)
        {
            addBoxes(menu, mc.player, needs, left, moves);
        }

        return moves;
    }

    /** Стопки россыпью, пока у строки есть нехватка {@code left}; {@code left} уменьшается. */
    private static void addLoose(AbstractContainerMenu menu, LocalPlayer player, List<Need> needs, int[] left, List<Move> moves)
    {
        for (Slot slot : menu.slots)
        {
            if (! source(slot) || ! slot.hasItem() || ! slot.mayPickup(player) || planned(moves, slot))
            {
                continue;
            }

            ItemStack stack = slot.getItem();

            for (int i = 0; i < left.length; ++i)
            {
                if (left[i] > 0 && matches(stack, needs.get(i).required))
                {
                    moves.add(new Move(slot, needs.get(i), null));
                    left[i] -= stack.getCount();
                    break;
                }
            }
        }
    }

    /** Шалкеры, в которых есть нужное по строкам с нехваткой {@code left}; {@code left} уменьшается на их содержимое. */
    private static void addBoxes(AbstractContainerMenu menu, LocalPlayer player, List<Need> needs, int[] left, List<Move> moves)
    {
        for (Slot slot : menu.slots)
        {
            if (! source(slot) || ! slot.hasItem() || ! slot.mayPickup(player) || ! shulker(slot.getItem())
                    || planned(moves, slot))
            {
                continue;
            }

            int[] inside = inside(slot.getItem(), needs);
            int first = -1;

            for (int i = 0; i < left.length && first < 0; ++i)
            {
                first = left[i] > 0 && inside[i] > 0 ? i : -1;
            }

            if (first < 0)
            {
                continue;
            }

            moves.add(new Move(slot, needs.get(first), inside));

            for (int i = 0; i < left.length; ++i)
            {
                left[i] -= inside[i];
            }
        }
    }

    /** Пустых слотов инвентаря игрока в этом окне. */
    private static int freeSlots(AbstractContainerMenu menu)
    {
        int free = 0;

        for (Slot slot : menu.slots)
        {
            free += slot.container instanceof Inventory && ! slot.hasItem() ? 1 : 0;
        }

        return free;
    }

    private static boolean shulker(ItemStack stack)
    {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem block
                && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    private static boolean planned(List<Move> moves, Slot slot)
    {
        for (Move move : moves)
        {
            if (move.slot() == slot)
            {
                return true;
            }
        }

        return false;
    }

    /** Сколько по каждой строке лежит внутри шалкера (с мешочками в нём). */
    private static int[] inside(ItemStack box, List<Need> needs)
    {
        int[] counts = new int[needs.size()];
        com.aell.layerlist.storage.StorageIndex.forEachInside(box, inner -> {
            for (int i = 0; i < counts.length; ++i)
            {
                if (matches(inner, needs.get(i).required))
                {
                    counts[i] += inner.getCount();
                    break;
                }
            }
        });
        return counts;
    }

    private static boolean matches(ItemStack stack, ItemStack required)
    {
        return DataItems.hasData(required) ? ItemData.same(stack, required) : ItemStack.isSameItem(stack, required);
    }

    private static int carried(LocalPlayer player, ItemStack required)
    {
        Inventory inventory = player.getInventory();
        int count = 0;

        for (int slot = 0; slot < inventory.getContainerSize(); ++slot)
        {
            ItemStack stack = inventory.getItem(slot);

            if (matches(stack, required))
            {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static void start(AbstractContainerScreen<?> screen, boolean auto)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.gameMode == null || job != null)
        {
            return;
        }

        List<Need> needs = needs();
        List<Move> moves = plan(screen, needs);
        LOGGER.info("[layerlist] перенос: строк с нехваткой {}, стопок в контейнере {} (экран {})",
                needs.size(), moves.size(), screen.getClass().getSimpleName());

        if (moves.isEmpty())
        {
            // Автоперенос молчит, когда брать нечего: сундуки открывают не только за материалами
            if (! auto)
            {
                report(Component.translatable(needs.isEmpty() ? "layerlist.message.transfer_none_needed"
                        : "layerlist.message.transfer_nothing"), Chat.DIM);
            }

            return;
        }

        int[] before = new int[needs.size()];

        for (int i = 0; i < before.length; ++i)
        {
            before[i] = carried(mc.player, needs.get(i).required);
        }

        job = new Job(screen, needs, before, auto);
    }

    /** Автоперенос: открылось хранилище, сервер прислал содержимое, панель с кнопкой на экране. */
    private static void autoTick(Minecraft mc)
    {
        Screen screen = mc.gui.screen();

        if (screen == null)
        {
            autoSeen = null;
            return;
        }

        if (screen == autoSeen || job != null || ! LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue()
                || ! (screen instanceof AbstractContainerScreen<?> container) || ! storage(container.getMenu())
                || ! wanted(screen))
        {
            return;
        }

        // Номер состояния 0 — сервер ещё не прислал слоты. Панель скрыта клавишей (Y) — автоперенос всё равно
        // работает: «Auto» включён, а список материалов считается и без панели
        if (container.getMenu().getStateId() == 0 || ! LayerCountManager.getInstance().hasData())
        {
            autoWait = 0;
            return;
        }

        if (++autoWait < AUTO_DELAY)
        {
            return;
        }

        autoSeen = screen;
        autoWait = 0;
        start(container, true);
    }

    /** Сколько тиков ждать ответа сервера: два пинга с запасом, на одиночной игре — {@link #SETTLE_TICKS}. */
    private static int settleTicks(Minecraft mc)
    {
        int latency = 0;

        if (mc.getConnection() != null && mc.player != null)
        {
            net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            latency = info != null ? info.getLatency() : 0;
        }

        return Math.min(60, SETTLE_TICKS + (2 * Math.max(0, latency) + 49) / 50);
    }

    /**
     * Шаг переноса. Стопка, нужная целиком, уходит Shift+кликом. Из стопки больше нехватки берётся ровно
     * сколько надо: стопка на курсор, по одному предмету в слот инвентаря (или обратно в слот сундука —
     * что короче), остаток на место. Не больше {@link #CLICKS_PER_TICK} щелчков за тик, в конце ожидание
     * ответа сервера и итог.
     */
    public static void onClientTick(Minecraft mc)
    {
        autoTick(mc);
        Job current = job;

        if (current == null)
        {
            return;
        }

        LocalPlayer player = mc.player;

        if (player == null || mc.gameMode == null || mc.gui.screen() != current.screen
                || current.screen.getMenu().containerId != current.containerId)
        {
            finish(current, true);
            return;
        }

        // Окно просили закрыть, а перенос со сверкой всё не кончается (сервер молчит) — курсор на место и закрыть
        if (current.closing >= 0 && ++current.closing > CLOSE_HOLD_TICKS)
        {
            returnCursor(current, mc, player);
            finish(current, true);
            return;
        }

        if (current.clicks.isEmpty() && current.settle > 0)
        {
            if (--current.settle == 0 && ! reconcile(current, player))
            {
                finish(current, false);
            }

            return;
        }

        // Темп: всё за тик (мгновенный перенос) или как быстрый игрок. После каждого переноса сразу следующий:
        // клиент применяет щелчок тут же, и план видит слоты уже после него
        int budget = LayerListConfigs.HUD_TRANSFER_INSTANT.getBooleanValue() ? INSTANT_CLICKS : CLICKS_PER_TICK;

        for (int guard = 0; budget > 0 && guard < INSTANT_CLICKS * 2; ++guard)
        {
            if (! current.clicks.isEmpty())
            {
                Click click = current.clicks.poll();
                mc.gameMode.handleContainerInput(current.containerId, click.slot(), click.button(), click.input(), player);
                ++current.sent;
                --budget;
                current.home = current.screen.getMenu().getCarried().isEmpty() ? -1
                        : click.home() >= 0 ? click.home() : current.home < 0 ? click.slot() : current.home;
                continue;
            }

            List<Move> moves = current.full ? List.of() : plan(current.screen, current.needs);

            if (moves.isEmpty())
            {
                current.settle = settleTicks(mc);
                return;
            }

            int sent = current.sent;
            startMove(current, moves.get(0), mc, player);
            budget -= current.sent - sent;
        }
    }

    /** Начать перенос из одного слота: шалкер целиком, Shift+клик целиком или короткая цепочка щелчков — в очередь. */
    private static void startMove(Job current, Move move, Minecraft mc, LocalPlayer player)
    {
        if (move.inside() != null)
        {
            takeBox(current, move, mc, player);
            return;
        }

        ItemStack stack = move.slot().getItem().copy();
        int need = move.need().left;

        if (stack.getCount() <= need)
        {
            mc.gameMode.handleContainerInput(current.containerId, move.slot().index, 0, ContainerInput.QUICK_MOVE, player);
            ++current.sent;

            // Клиент применяет Shift+клик сразу; что осталось в слоте — не влезло
            int left = move.slot().hasItem() && ItemStack.isSameItem(move.slot().getItem(), stack)
                    ? move.slot().getItem().getCount() : 0;
            move.need().left -= stack.getCount() - left;
            current.full |= left > 0;
            return;
        }

        Slot target = target(current.screen.getMenu(), stack, need);

        if (target == null)
        {
            current.full = true;
            return;
        }

        int source = move.slot().index;
        ItemStack there = target.getItem();
        int startT = there.isEmpty() ? 0 : there.getCount();
        int capS = Math.min(stack.getMaxStackSize(), move.slot().getMaxStackSize(stack));
        int capT = Math.min(stack.getMaxStackSize(), target.getMaxStackSize(stack));
        List<Click> fastest = exactClicks(source, target.index, stack.getCount(), startT, need, capS, capT);

        if (fastest != null)
        {
            current.clicks.addAll(fastest);
            move.need().left = 0;
            return;
        }

        int excess = stack.getCount() - need;
        current.clicks.add(new Click(source, 0, ContainerInput.PICKUP));

        if (need <= excess)
        {
            // По одному в инвентарь, остаток — обратно в тот же слот
            for (int i = 0; i < need; ++i)
            {
                current.clicks.add(new Click(target.index, 1, ContainerInput.PICKUP));
            }

            current.clicks.add(new Click(source, 0, ContainerInput.PICKUP));
        }
        else
        {
            // Лишнее по одному обратно, нужное разом в инвентарь
            for (int i = 0; i < excess; ++i)
            {
                current.clicks.add(new Click(source, 1, ContainerInput.PICKUP));
            }

            current.clicks.add(new Click(target.index, 0, ContainerInput.PICKUP));
        }

        move.need().left = 0;
    }

    /** Шалкер с нужным внутри — Shift+кликом целиком; его содержимое закрывает нехватку строк. */
    private static void takeBox(Job current, Move move, Minecraft mc, LocalPlayer player)
    {
        ItemStack box = move.slot().getItem().copy();
        mc.gameMode.handleContainerInput(current.containerId, move.slot().index, 0, ContainerInput.QUICK_MOVE, player);
        ++current.sent;

        // Клиент применяет Shift+клик сразу; шалкер остался в слоте — в инвентаре нет места
        if (move.slot().hasItem() && ItemStack.matches(move.slot().getItem(), box))
        {
            current.full = true;
            return;
        }

        current.boxes.add(box);

        for (int i = 0; i < current.needs.size(); ++i)
        {
            int got = move.inside()[i];

            if (got <= 0)
            {
                continue;
            }

            Need need = current.needs.get(i);
            current.boxed[i] += got;
            need.left = Math.max(0, need.left - got);
            // Россыпью теперь нужно меньше; взятое россыпью до шалкера сверка обратно не отдаёт
            current.want[i] = Math.max(carried(player, need.required), current.want[i] - got);
        }
    }

    /**
     * Игрок закрывает окно, пока идёт перенос. Закрыть сразу нельзя: точный перенос держит часть стопки на курсоре,
     * а сервер при закрытии кладёт всё с курсора в инвентарь — вместо нехватки вышла бы почти целая стопка, и
     * лишнее уже некуда вернуть. Поэтому окно остаётся открытым, пока перенос не дойдёт до точного счёта (сверка
     * с сервером, возврат лишнего), и закрывается само, не дольше {@link #CLOSE_HOLD_TICKS}. Истина — закрытие
     * отложено. Зовёт {@code MixinAbstractContainerScreen} в начале {@code onClose} (Esc, клавиша инвентаря).
     */
    public static boolean holdClose(Screen screen)
    {
        Job current = job;

        if (current == null || screen != current.screen)
        {
            return false;
        }

        if (current.closing < 0)
        {
            current.closing = 0;
            LOGGER.info("[layerlist] перенос: окно закроется после сверки");
        }

        return true;
    }

    /**
     * Окно закрывают в обход экрана посреди переноса — придержать нельзя, но стопку с курсора до пакета закрытия
     * вернуть на место можно (иначе сервер отдал бы её игроку целиком). Зовёт {@code MixinLocalPlayerTransfer}.
     */
    public static void beforeClose(LocalPlayer player)
    {
        Job current = job;

        if (current != null && player.containerMenu.containerId == current.containerId)
        {
            returnCursor(current, Minecraft.getInstance(), player);
        }
    }

    /**
     * Вернуть стопку с курсора в слот, откуда она взята (там пусто или тот же предмет, место есть); очередь —
     * сбросить. Истина — щелчок отправлен.
     */
    private static boolean returnCursor(Job current, Minecraft mc, LocalPlayer player)
    {
        current.clicks.clear();

        if (current.home < 0 || mc.gameMode == null || current.screen.getMenu().getCarried().isEmpty())
        {
            return false;
        }

        mc.gameMode.handleContainerInput(current.containerId, current.home, 0, ContainerInput.PICKUP, player);
        ++current.sent;
        LOGGER.info("[layerlist] перенос: курсор возвращён в слот {}", current.home);
        current.home = -1;
        return true;
    }

    /**
     * Кратчайшая цепочка щелчков, переносящая ровно {@code need} из слота контейнера {@code source} (там
     * {@code s0}) в слот инвентаря {@code target} (там {@code t0}) — поиск в ширину по «источник, курсор, цель».
     * Щелчки — ванильные: левый с пустым курсором берёт всё, с полным кладёт сколько влезет; правый с пустым
     * берёт половину (с округлением вверх), с полным кладёт один. По одному предмету 30 из 64 — 32 щелчка, так —
     * 4; хуже всего 9 вместо 34. Из цели не берётся больше, чем туда положено: курсор всегда можно вернуть в
     * источник ({@code home}) целиком. Не нашлось — {@code null} (тогда по одному, как раньше).
     */
    static @Nullable List<Click> exactClicks(int source, int target, int s0, int t0, int need, int capS, int capT)
    {
        int total = s0 + t0;
        int goalS = s0 - need;
        int goalT = t0 + need;

        if (need <= 0 || goalS < 0 || goalT > capT || s0 > capS)
        {
            return null;
        }

        // Состояние — (s, t), курсор = total − s − t; откуда пришли и каким щелчком
        int side = Math.max(capS, capT) + 1;
        int[] from = new int[side * side];
        int[] how = new int[side * side];
        java.util.Arrays.fill(from, -2);
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        int start = s0 * side + t0;
        from[start] = -1;
        queue.add(start);

        while (! queue.isEmpty())
        {
            int state = queue.poll();
            int s = state / side;
            int t = state % side;
            int c = total - s - t;

            if (s == goalS && t == goalT && c == 0)
            {
                java.util.ArrayDeque<Click> path = new java.util.ArrayDeque<>();

                for (int at = state; from[at] >= 0; at = from[at])
                {
                    int move = how[at];
                    path.addFirst(new Click((move & 2) == 0 ? source : target, move & 1, ContainerInput.PICKUP, source));
                }

                return new ArrayList<>(path);
            }

            // move: бит 1 — слот (0 источник, 1 цель), бит 0 — кнопка (0 левая, 1 правая)
            for (int move = 0; move < 4; ++move)
            {
                boolean onTarget = (move & 2) != 0;
                boolean right = (move & 1) != 0;
                int x = onTarget ? t : s;
                int cap = onTarget ? capT : capS;
                int nx;
                int nc;

                if (c == 0)
                {
                    if (x == 0)
                    {
                        continue;
                    }

                    nc = right ? (x + 1) / 2 : x;
                    nx = x - nc;
                }
                else
                {
                    int put = right ? Math.min(1, cap - x) : Math.min(c, cap - x);

                    if (put <= 0)
                    {
                        continue;
                    }

                    nx = x + put;
                    nc = c - put;
                }

                int ns = onTarget ? s : nx;
                int nt = onTarget ? nx : t;

                if (nt < t0 || ns < 0 || nt < 0 || ns >= side || nt >= side || nc < 0)
                {
                    continue;
                }

                int next = ns * side + nt;

                if (from[next] == -2)
                {
                    from[next] = state;
                    how[next] = move;
                    queue.add(next);
                }
            }
        }

        return null;
    }

    /**
     * Сверка после ответа сервера: у игрока должно стать ровно «было + нехватка». Лишнее (на сервере
     * щелчок по части стопки разошёлся с предсказанием) — обратно в контейнер, но не больше пришедшего;
     * недобор — ещё один проход, если в контейнере такое есть. Истина — работа продолжается.
     */
    private static boolean reconcile(Job job, LocalPlayer player)
    {
        if (job.pass >= MAX_PASSES)
        {
            return false;
        }

        // После ответа сервера на курсоре что-то осталось — сперва на место, считать будем следующим проходом
        if (! job.screen.getMenu().getCarried().isEmpty())
        {
            ++job.pass;
            return returnCursor(job, Minecraft.getInstance(), player);
        }

        boolean refill = false;
        int back = 0;
        int missing = 0;

        for (int i = 0; i < job.needs.size(); ++i)
        {
            Need need = job.needs.get(i);
            int got = carried(player, need.required);
            int over = Math.min(got - job.want[i], got - job.before[i]);

            if (over > 0)
            {
                back += giveBack(job, need.required, over);
                need.left = 0;
            }
            else
            {
                need.left = Math.max(0, job.want[i] - got);
                refill |= need.left > 0 && ! job.full;
            }
        }

        // Недобор добираем, только если в контейнере такое ещё есть
        if (refill && ! plan(job.screen, job.needs).isEmpty())
        {
            for (Need need : job.needs)
            {
                missing += need.left;
            }
        }
        else
        {
            refill = false;
        }

        if (back == 0 && ! refill)
        {
            return false;
        }

        job.returned += back;
        ++job.pass;
        LOGGER.info("[layerlist] перенос: сверка с сервером, проход {}: вернуть лишних {}, добрать {}", job.pass, back, missing);
        return true;
    }

    /**
     * Вернуть {@code amount} предметов из инвентаря в контейнер: стопка на курсор, по одному в слот
     * контейнера с таким же предметом (или в пустой), остаток на место. Сколько поставлено в очередь.
     */
    private static int giveBack(Job job, ItemStack required, int amount)
    {
        AbstractContainerMenu menu = job.screen.getMenu();
        int queued = 0;

        for (Slot from : menu.slots)
        {
            if (queued >= amount)
            {
                break;
            }

            if (! (from.container instanceof Inventory) || ! from.hasItem() || ! matches(from.getItem(), required))
            {
                continue;
            }

            ItemStack stack = from.getItem();
            Slot into = null;
            int room = 0;

            for (Slot slot : menu.slots)
            {
                if (! source(slot) || ! slot.mayPlace(stack))
                {
                    continue;
                }

                ItemStack there = slot.getItem();
                int cap = Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));

                if (there.isEmpty() ? into == null : ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < cap)
                {
                    into = slot;
                    room = cap - there.getCount();

                    if (! there.isEmpty())
                    {
                        break;
                    }
                }
            }

            if (into == null)
            {
                break;
            }

            int count = Math.min(Math.min(amount - queued, stack.getCount()), room);
            job.clicks.add(new Click(from.index, 0, ContainerInput.PICKUP));

            for (int k = 0; k < count; ++k)
            {
                job.clicks.add(new Click(into.index, 1, ContainerInput.PICKUP));
            }

            if (count < stack.getCount())
            {
                job.clicks.add(new Click(from.index, 0, ContainerInput.PICKUP));
            }

            queued += count;
            // Слоты меню клиент обновит только щелчками; второй раз по тому же слоту не идём
            break;
        }

        return queued;
    }

    /**
     * Слот инвентаря игрока, куда лягут {@code amount} таких предметов разом: стопка того же предмета с местом,
     * иначе пустой. Порядок — как у Shift+клика из сундука (с конца: сперва хотбар справа).
     */
    private static @Nullable Slot target(AbstractContainerMenu menu, ItemStack stack, int amount)
    {
        Slot empty = null;

        for (int i = menu.slots.size() - 1; i >= 0; --i)
        {
            Slot slot = menu.slots.get(i);

            if (! (slot.container instanceof Inventory) || ! slot.mayPlace(stack))
            {
                continue;
            }

            ItemStack have = slot.getItem();

            if (have.isEmpty())
            {
                empty = empty == null ? slot : empty;
            }
            else if (ItemStack.isSameItemSameComponents(have, stack)
                    && have.getCount() + amount <= Math.min(have.getMaxStackSize(), slot.getMaxStackSize(stack)))
            {
                return slot;
            }
        }

        return empty;
    }

    private static void finish(Job done, boolean aborted)
    {
        job = null;
        Minecraft mc = Minecraft.getInstance();
        LayerCountManager.getInstance().markDirty();

        if (mc.player == null)
        {
            return;
        }

        // Окно просили закрыть во время переноса — закрываем теперь, когда счёт сведён
        if (done.closing >= 0 && mc.gui.screen() == done.screen
                && mc.player.containerMenu.containerId == done.containerId)
        {
            returnCursor(done, mc, mc.player);
            done.screen.onClose();
            EscGuard.containerClosed();
        }

        Map<String, Integer> moved = new LinkedHashMap<>();
        Map<String, Integer> extra = new LinkedHashMap<>();

        for (int i = 0; i < done.needs.size(); ++i)
        {
            ItemStack required = done.needs.get(i).required;
            int got = carried(mc.player, required);
            int delta = got - done.before[i];
            int over = Math.min(got - done.want[i], delta);

            if (delta > 0)
            {
                moved.merge(DataItems.label(required), delta, Integer::sum);
            }

            if (over > 0)
            {
                extra.merge(DataItems.label(required), over, Integer::sum);
            }
        }

        int boxes = boxesHeld(mc.player, done.boxes);
        Map<String, Integer> inBoxes = new LinkedHashMap<>();

        for (int i = 0; i < done.needs.size() && boxes == done.boxes.size(); ++i)
        {
            if (done.boxed[i] > 0)
            {
                inBoxes.merge(DataItems.label(done.needs.get(i).required), done.boxed[i], Integer::sum);
            }
        }

        LOGGER.info("[layerlist] перенос: щелчков {}, получено {}{}{}{}{}{}{}", done.sent, moved,
                done.boxes.isEmpty() ? "" : ", шалкеров " + boxes + " из " + done.boxes.size() + " " + inBoxes,
                extra.isEmpty() ? "" : ", сверх нехватки " + extra,
                done.full ? ", инвентарь заполнен" : "", aborted ? ", окно закрыто раньше" : "",
                done.returned > 0 ? ", возвращено лишних " + done.returned : "", done.auto ? ", авто" : "");

        if (moved.isEmpty() && boxes == 0)
        {
            if (done.auto && ! done.full)
            {
                return;
            }

            report(Component.translatable(done.full ? "layerlist.message.transfer_full"
                    : "layerlist.message.transfer_rejected"), Chat.WARN);
            return;
        }

        List<MutableComponent> parts = new ArrayList<>();
        moved.forEach((name, count) -> parts.add(Chat.text(count + "× " + name, Chat.TEXT)));

        if (boxes > 0)
        {
            List<MutableComponent> contents = new ArrayList<>();
            inBoxes.forEach((name, count) -> contents.add(Chat.text(count + "× " + name, Chat.TEXT)));
            parts.add(contents.isEmpty() ? Chat.tr("layerlist.message.transfer_boxes_short", Chat.TEXT, boxes)
                    : Chat.tr("layerlist.message.transfer_boxes", Chat.TEXT, boxes, Chat.joined(", ", Chat.DIM, contents)));
        }
        MutableComponent text = Chat.tr("layerlist.message.transfer_done", Chat.OK, Chat.joined(", ", Chat.DIM, parts));

        if (done.full)
        {
            text.append(Chat.text(" · ", Chat.DIM)).append(Chat.tr("layerlist.message.transfer_full", Chat.WARN));
        }

        // Сверх нехватки осталось (контейнер забит, сервер не принял возврат, окно закрыл сервер) — сказать прямо
        if (! extra.isEmpty())
        {
            List<MutableComponent> over = new ArrayList<>();
            extra.forEach((name, count) -> over.add(Chat.text(count + "× " + name, Chat.TEXT)));
            text.append(Chat.text(" · ", Chat.DIM))
                    .append(Chat.tr("layerlist.message.transfer_extra", Chat.WARN, Chat.joined(", ", Chat.DIM, over)));
        }

        report(text, extra.isEmpty() ? Chat.OK : Chat.WARN);

        // Автоперенос забрал своё, а нужного в хранилище больше нет — выйти из него (опция, по умолчанию выкл.).
        // Хранилище, где для списка ничего не было, сюда не доходит (итог пустой) — его можно спокойно открыть и положить
        if (done.auto && ! aborted && ! done.full && done.closing < 0 && LayerListConfigs.HUD_TRANSFER_AUTO_CLOSE.getBooleanValue()
                && mc.gui.screen() == done.screen && mc.player.containerMenu.containerId == done.containerId
                && done.screen.getMenu().getCarried().isEmpty() && plan(done.screen, null).isEmpty())
        {
            LOGGER.info("[layerlist] перенос: нужного в хранилище больше нет — закрываю");
            done.screen.onClose();
            EscGuard.containerClosed();
        }
    }

    /** Сколько забранных шалкеров у игрока после ответа сервера (одинаковые считаются по разу на слот). */
    private static int boxesHeld(LocalPlayer player, List<ItemStack> boxes)
    {
        Inventory inventory = player.getInventory();
        boolean[] used = new boolean[inventory.getContainerSize()];
        int held = 0;

        for (ItemStack box : boxes)
        {
            for (int slot = 0; slot < used.length; ++slot)
            {
                if (! used[slot] && ItemStack.matches(inventory.getItem(slot), box))
                {
                    used[slot] = true;
                    ++held;
                    break;
                }
            }
        }

        return held;
    }

    /** Итог — над хотбаром всегда (действие игрока, ответ нужен), в чат — по настройке сообщений. */
    private static void report(Component text, int color)
    {
        Minecraft mc = Minecraft.getInstance();
        MutableComponent shown = text instanceof MutableComponent mutable ? mutable : text.copy();

        if (shown.getStyle().getColor() == null)
        {
            shown = Chat.paint(shown, color);
        }

        mc.gui.hud.setOverlayMessage(shown, false);
        Chat.send(shown);
    }
}
