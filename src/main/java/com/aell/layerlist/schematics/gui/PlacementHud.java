package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * «Смотреть на мир» редактора размещения — то же, что на вкладке «Выделение» ({@link SelectionHud}): редактор закрыт,
 * игра идёт как обычно — ходить, летать, ломать и ставить, открывать инвентарь, — а карточка координат этого
 * размещения остаётся HUD-ом сверху справа, цифры живые (сдвиг инструментом Litematica в мире виден сразу). Клавиша
 * курсора (R, {@code SchematicsConfig.SELECTION_CURSOR}) оживляет карточку ({@link PlacementCursorScreen}); Esc в игре
 * возвращает редактор целиком — тот же экземпляр, со всем сеансом: исходным состоянием «Отмены», историей шагов,
 * замком ({@code MixinKeyboardHandlerSelectionHud}).
 *
 * <p>Все правки с карточки идут через редактор ({@code PlacementEditorScreen.world*}): замок, сообщения и история
 * шагов работают так же, как на экране. Карточку рисует и ведёт {@link Card} — одна на HUD (без ввода) и одна на
 * экран курсора, поэтому при переходе она не прыгает.
 */
@Environment(EnvType.CLIENT)
public final class PlacementHud
{
    private static final int ICON_W = SelectionHud.ICON_W;
    private static final int ROW_H = SelectionHud.ROW_H;
    private static final int PAD = SelectionHud.PAD;
    private static final int GAP = SelectionHud.GAP;
    private static final int NAME_H = Ui.TEXT_H + 2;

    private static boolean active;
    private static @Nullable PlacementEditorScreen editor;
    private static @Nullable Card look;

    private PlacementHud() {}

    public static void register()
    {
        RenderEventHandler.getInstance().registerGameOverlayRenderer(new IRenderer()
        {
            @Override
            public void onRenderGameOverlayPostAdvanced(GuiContext ctx, float partialTicks, net.minecraft.util.profiling.ProfilerFiller profiler)
            {
                PlacementHud.render(ctx);
            }
        });
    }

    public static boolean active()
    {
        return active;
    }

    /** Редактор в этот момент закрывается ради мира: дальше — игра с карточкой этого размещения. */
    public static void enter(PlacementEditorScreen from)
    {
        SelectionHud.leave();
        active = true;
        editor = from;
    }

    /** Редактор или «Схемы» открыли (как угодно) — карточка больше не нужна. */
    public static void leave()
    {
        active = false;
        editor = null;
    }

    /** Редактор карточки в игре; {@code null} — карточки нет. */
    public static @Nullable PlacementEditorScreen editor()
    {
        return active ? editor : null;
    }

    /** Esc в игре: вернуть редактор. {@code true} — вернули, паузу не открывать. */
    public static boolean escape()
    {
        Minecraft mc = Minecraft.getInstance();
        PlacementEditorScreen back = editor;

        if (! active || back == null || mc.screen != null)
        {
            return false;
        }

        leave();

        if (mc.level == null)
        {
            return false;
        }

        back.reopen();
        return true;
    }

    /**
     * Клавиша курсора в игре: карточка оживает — экран с одной карточкой на этом же месте. {@code true} — открыли
     * (клавишу дальше не отдаём).
     */
    public static boolean cursor()
    {
        Minecraft mc = Minecraft.getInstance();
        PlacementEditorScreen owner = editor;

        if (! active || owner == null || mc.screen != null || mc.level == null)
        {
            return false;
        }

        mc.setScreen(new PlacementCursorScreen(owner));
        return true;
    }

    private static void render(GuiContext ctx)
    {
        PlacementEditorScreen owner = editor;

        if (! active || owner == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        // Мира нет или размещение убрали снаружи (меню Litematica, хоткей) — править больше нечего
        if (mc.level == null || mc.player == null || ! owner.worldExists())
        {
            leave();
            return;
        }

        Screen screen = mc.screen;

        if (mc.options.hideGui || (screen != null && ! (screen instanceof ChatScreen)))
        {
            return;
        }

        if (look == null)
        {
            look = new Card(null);
        }

        look.draw(ctx, Kit.font(), owner, -1, -1, false, GuiUtils.getScaledWindowWidth());
    }

    /**
     * Для харнесса покрытия ({@code src/dev}): где карточка стоит на экране этой ширины — клики настоящей мышью.
     * В игре никто не зовёт.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Карточка целиком: {x, y, w, h}. */
        public static int[] card(int screenWidth)
        {
            Layout l = Layout.at(screenWidth);

            return new int[] {l.x(), l.y(), l.w(), l.h()};
        }

        /** Верхняя половина («+») кнопки шага оси {@code axis}: {x, y, w, h}. */
        public static int[] nudgePlus(int screenWidth, int axis)
        {
            Layout l = Layout.at(screenWidth);

            return new int[] {l.cols().nudgeX()[axis], l.originY() + 1, SelectionHud.NUDGE_W, (ROW_H - 1) / 2};
        }

        /** Поле оси: {x, y, w, h}. */
        public static int[] field(int screenWidth, int axis)
        {
            Layout l = Layout.at(screenWidth);

            return new int[] {l.cols().fieldX()[axis], l.originY(), l.cols().fieldW(), ROW_H};
        }

        /** Замок на карточке: {x, y, w, h}. */
        public static int[] lock(int screenWidth)
        {
            Layout l = Layout.at(screenWidth);

            return new int[] {l.cols().toPlayerX(), l.lastY(), ICON_W, ROW_H};
        }
    }

    /**
     * Строки карточки: заголовок, имя, подписи осей, точка привязки, поворот/отражение/замок. Столбцы (метка, три поля
     * с «+/−», «Двигать в мире», «К игроку») — те же, что у карточки «Выделения» ({@code SelectionHud.Card}); высота
     * своя.
     */
    private record Layout(SelectionHud.Card cols, int h, int titleY, int nameY, int axesY, int originY, int lastY)
    {
        static Layout at(int screenWidth)
        {
            SelectionHud.Card hud = SelectionHud.hudCard(screenWidth);
            int titleY = hud.y() + PAD;
            int nameY = titleY + ROW_H;
            int axesY = nameY + NAME_H + 2;
            int originY = axesY + Ui.TEXT_H + 2;
            int lastY = originY + ROW_H + GAP + 2;
            int h = lastY + ROW_H + PAD - hud.y();

            return new Layout(SelectionHud.Card.at(hud.x(), hud.y(), hud.w()), h, titleY, nameY, axesY, originY, lastY);
        }

        int x()
        {
            return this.cols.x();
        }

        int y()
        {
            return this.cols.y();
        }

        int w()
        {
            return this.cols.w();
        }
    }

    /**
     * Карточка размещения: поля X Y Z с «+/−», «Двигать в мире», «К игроку», поворот, отражение, замок. С {@code owner}
     * — живая (экран курсора), без — только для рисования на HUD (как {@code SelectionHud.Look}).
     */
    static final class Card
    {
        private final @Nullable PlacementEditorScreen owner;
        private final TextInput[] fields = new TextInput[3];
        private final AxisNudge[] nudges = new AxisNudge[3];
        private final IconButton grab;
        private final IconButton toPlayer;
        private final IconButton rotateCcw;
        private final IconButton rotateCw;
        private final IconButton mirror;
        private final IconButton lock;
        private final List<Widget> widgets = new ArrayList<>();
        private Layout layout = Layout.at(0);
        private long nameSince = -1L;

        Card(@Nullable PlacementEditorScreen owner)
        {
            this.owner = owner;

            for (int axis = 0; axis < 3; ++axis)
            {
                int a = axis;
                TextInput input = new TextInput().maxLength(9).filter(c -> Character.isDigit(c) || c == '-');
                input.onSubmit(text -> this.commit(a));
                this.fields[axis] = input;
                this.nudges[axis] = new AxisNudge(axis, Tr.t("selection.nudge.tip"), sign -> this.nudge(a, sign * amount()));
                this.widgets.add(input);
                this.widgets.add(this.nudges[axis]);
            }

            this.grab = new IconButton(Glyphs.TARGET, Tr.t("place.hud.grab.tip"), Button.Style.SECONDARY, this::grab);
            this.toPlayer = new IconButton(Glyphs.PLAYER, Tr.t("place.to_player.tip"), Button.Style.SECONDARY, this::toPlayer);
            this.rotateCcw = new IconButton(Glyphs.ROTATE_CCW, Tr.t("place.rotate_ccw.tip"), Button.Style.SECONDARY,
                    () -> this.rotate(false));
            this.rotateCw = new IconButton(Glyphs.ROTATE_CW, Tr.t("place.rotate_cw.tip"), Button.Style.SECONDARY,
                    () -> this.rotate(true));
            this.mirror = new IconButton(Glyphs.MIRROR, Tr.t("place.mirror.tip"), Button.Style.SECONDARY, this::mirror);
            this.lock = new IconButton(Glyphs.UNLOCK, "", Button.Style.SECONDARY, this::toggleLock);
            this.widgets.addAll(List.of(this.grab, this.toPlayer, this.rotateCcw, this.rotateCw, this.mirror, this.lock));

            for (Button button : List.of(this.rotateCcw, this.rotateCw, this.mirror))
            {
                button.edge(Ui.C_ACCENT, false);
            }

            this.toPlayer.edge(Ui.C_ACCENT, false).dashed(true);
        }

        private static int amount()
        {
            int amount = 1;
            amount *= GuiBase.isCtrlDown() ? 100 : 1;
            amount *= GuiBase.isShiftDown() ? 10 : 1;
            return amount;
        }

        // ---- действия: все через редактор ----

        private void nudge(int axis, int delta)
        {
            if (this.owner != null)
            {
                this.owner.worldNudge(axis, delta);
            }
        }

        private void grab()
        {
            if (this.owner != null)
            {
                this.owner.worldSelect();
            }
        }

        private void toPlayer()
        {
            if (this.owner != null)
            {
                this.commitAll();
                this.owner.worldToPlayer();
            }
        }

        private void rotate(boolean clockwise)
        {
            if (this.owner != null)
            {
                this.commitAll();
                this.owner.worldRotate(clockwise, GuiBase.isShiftDown());
            }
        }

        private void mirror()
        {
            if (this.owner != null)
            {
                this.commitAll();
                this.owner.worldMirror(GuiBase.isShiftDown());
            }
        }

        private void toggleLock()
        {
            if (this.owner != null)
            {
                this.commitAll();
                this.owner.worldToggleLock();
            }
        }

        /** Число из поля оси {@code axis} — в размещение (пустое или «-» — ждём, ничего не применяем). */
        private void commit(int axis)
        {
            TextInput input = this.fields[axis];

            if (this.owner == null)
            {
                return;
            }

            try
            {
                this.owner.worldSetAxis(axis, Integer.parseInt(input.text().trim()));
            }
            catch (NumberFormatException e)
            {
                // Пусто или один минус — оставляем поле как есть, число покажется прежнее после снятия фокуса
            }

            input.setFocused(false);
        }

        /** Применить то, что набрано в полях (Enter, щелчок мимо, выход с экрана курсора). */
        void commitAll()
        {
            for (int axis = 0; axis < 3; ++axis)
            {
                if (this.fields[axis].focused())
                {
                    this.commit(axis);
                }
            }
        }

        /** Снять фокус со всех полей без применения. */
        boolean blur()
        {
            boolean any = false;

            for (TextInput input : this.fields)
            {
                any |= input.focused();
                input.setFocused(false);
            }

            return any;
        }

        boolean hasFocus()
        {
            for (TextInput input : this.fields)
            {
                if (input.focused())
                {
                    return true;
                }
            }

            return false;
        }

        // ---- раскладка и рисование ----

        boolean contains(double mouseX, double mouseY)
        {
            return Paint.hit((int) Math.floor(mouseX), (int) Math.floor(mouseY), this.layout.x(), this.layout.y(), this.layout.w(),
                    this.layout.h());
        }

        /** Поставить контролы на места карточки в окне такой ширины. */
        void place(int screenWidth)
        {
            Layout l = Layout.at(screenWidth);
            this.layout = l;
            SelectionHud.Card c = l.cols();

            for (int i = 0; i < 3; ++i)
            {
                this.fields[i].setBounds(c.fieldX()[i], l.originY(), c.fieldW(), ROW_H);
                this.nudges[i].setBounds(c.nudgeX()[i], l.originY() + 1, SelectionHud.NUDGE_W, ROW_H - 1);
            }

            this.grab.setBounds(c.grabX(), l.originY(), ICON_W, ROW_H);
            this.toPlayer.setBounds(c.toPlayerX(), l.originY(), ICON_W, ROW_H);

            int x = c.inner();
            this.rotateCcw.setBounds(x, l.lastY(), ICON_W, ROW_H);
            this.rotateCw.setBounds(x + ICON_W + Ui.PAD_MICRO, l.lastY(), ICON_W, ROW_H);
            this.mirror.setBounds(x + (ICON_W + Ui.PAD_MICRO) * 2, l.lastY(), ICON_W, ROW_H);
            this.lock.setBounds(c.toPlayerX(), l.lastY(), ICON_W, ROW_H);
        }

        /**
         * Карточка целиком. {@code cursor} — курсор у карточки (подсказки клавиш другие), мышь {@code (-1, -1)} — HUD без
         * ввода. Значения берутся у редактора каждый кадр: сдвиг инструментом Litematica виден сразу.
         */
        void draw(GuiContext ctx, Font font, PlacementEditorScreen editor, int mouseX, int mouseY, boolean cursor, int screenWidth)
        {
            this.place(screenWidth);
            this.refresh(editor);

            Layout l = this.layout;
            SelectionHud.Card c = l.cols();
            boolean live = this.owner != null;

            SelectionHud.panel(ctx, l.x(), l.y(), l.w(), l.h(), true);
            Paint.midText(ctx, font, Tr.t("place.title"), c.inner(), l.titleY(), ROW_H, Ui.C_TEXT, true);
            this.hints(ctx, font, cursor);

            // Имя: в HUD бежит всегда; на экране курсора — под курсором с начала, без наведения — многоточие
            int nameRoom = c.w() - PAD * 2;
            boolean overName = live && Paint.hit(mouseX, mouseY, c.inner(), l.nameY(), nameRoom, NAME_H);

            if (overName && this.nameSince < 0L)
            {
                this.nameSince = Util.getMillis();
            }
            else if (! overName)
            {
                this.nameSince = -1L;
            }

            if (live)
            {
                Paint.midMarquee(ctx, font, editor.placement().getName(), c.inner(), l.nameY(), NAME_H, nameRoom, Ui.C_TEXT_2, false,
                        this.nameSince);
            }
            else
            {
                Paint.midMarquee(ctx, font, editor.placement().getName(), c.inner(), l.nameY(), NAME_H, nameRoom, Ui.C_TEXT_2, false);
            }

            String[] names = { "X", "Y", "Z" };

            for (int i = 0; i < 3; ++i)
            {
                int cx = c.fieldX()[i] + (c.fieldW() + GAP + SelectionHud.NUDGE_W - font.width(names[i])) / 2;
                Paint.midText(ctx, font, names[i], cx, l.axesY(), Ui.TEXT_H, AxisNudge.axisColor(i), true);
            }

            SelectionHud.originTag(ctx, c.tagX(), l.originY(), true);

            for (Widget widget : this.widgets)
            {
                widget.render(ctx, mouseX, mouseY);
            }

            // Поворот и отражение или, пока свежо, сообщение редактора (закреплено, ось закреплена…) — бегущей строкой
            int readX = this.mirror.x() + ICON_W + GAP;
            int room = Math.max(0, this.lock.x() - GAP - readX);
            String message = editor.worldMessage();

            if (message != null)
            {
                Paint.marquee(ctx, font, message, readX, l.lastY() + (ROW_H - Ui.GLYPH_H) / 2, room,
                        editor.worldMessageIsError() ? Ui.C_WARN : Ui.C_TEXT_2, false);
            }
            else
            {
                Paint.midText(ctx, font, Paint.fit(font, editor.worldTransform(), room), readX, l.lastY(), ROW_H, Ui.C_TEXT_2, false);
            }
        }

        /** Подсказки справа в заголовке: «[R] курсор · [Esc] редактор» в игре, «[R] в игру · [Esc] редактор» у карточки. */
        private void hints(GuiContext ctx, Font font, boolean cursor)
        {
            int titleY = this.layout.titleY();
            int x = SelectionHud.hint(ctx, font, titleY, this.layout.cols().right(), "Esc", Tr.t("place.hud.editor"));
            String key = SelectionHud.cursorKey();

            if (! key.isEmpty())
            {
                SelectionHud.hint(ctx, font, titleY, x - Ui.PAD_REL, key, Tr.t(cursor ? "place.hud.game" : "place.hud.cursor"));
            }
        }

        /** Значения и состояния контролов из редактора: числа, замки, выбранность, замок. */
        private void refresh(PlacementEditorScreen editor)
        {
            BlockPos o = editor.placement().getOrigin();
            boolean locked = editor.worldLocked();
            boolean lockable = editor.worldLockable();

            for (int i = 0; i < 3; ++i)
            {
                boolean movable = editor.worldAxisMovable(i);
                TextInput input = this.fields[i];
                String text = String.valueOf(i == 0 ? o.getX() : i == 1 ? o.getY() : o.getZ());

                input.setEnabled(movable);
                this.nudges[i].setEnabled(movable);

                if (! input.focused() && ! text.equals(input.text()))
                {
                    input.setText(text);
                }
            }

            boolean grabbed = editor.worldSelected();
            this.grab.style(grabbed ? Button.Style.PRIMARY : Button.Style.SECONDARY).accent(SelectionPane.C_ORIGIN).selected(grabbed);

            this.toPlayer.setEnabled(! locked);
            this.rotateCcw.setEnabled(! locked);
            this.rotateCw.setEnabled(! locked);
            this.mirror.setEnabled(! locked);

            // Замок: закрытый — сплошной золотой, открытый — золотой пунктир без заливки
            this.lock.glyph(locked ? Glyphs.LOCK : Glyphs.UNLOCK);
            this.lock.edge(Theme.GOLD, true).accent(Theme.GOLD).style(locked ? Button.Style.PRIMARY : Button.Style.SECONDARY);
            this.lock.dashed(! locked && lockable);
            this.lock.setEnabled(lockable);
            this.lock.tooltip(Tr.t(! lockable ? "place.lock.shared.tip" : locked ? "place.lock.on.tip" : "place.lock.off.tip"));
        }

        // ---- ввод (только у живой карточки) ----

        /** Щелчок: контрол под курсором; серое поле или «+/−» — сообщение, чем закреплено. Мимо контролов — просто съедаем. */
        boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            // Поле, по которому не попали, применяется и теряет фокус
            for (int i = 0; i < 3; ++i)
            {
                if (this.fields[i].focused() && ! this.fields[i].contains(click.x(), click.y()))
                {
                    this.commit(i);
                }
            }

            for (int i = 0; i < 3; ++i)
            {
                if (this.owner != null && (this.fields[i].contains(click.x(), click.y()) || this.nudges[i].contains(click.x(), click.y()))
                        && ! this.fields[i].enabled())
                {
                    this.owner.worldRefuse(i);
                    return true;
                }
            }

            for (Widget widget : this.widgets)
            {
                if (widget.visible() && widget.contains(click.x(), click.y()))
                {
                    widget.mouseClicked(click, doubleClick);
                    return true;
                }
            }

            return true;
        }

        boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
        {
            for (TextInput input : this.fields)
            {
                if (input.focused())
                {
                    return input.mouseDragged(click, dx, dy);
                }
            }

            return false;
        }

        boolean mouseReleased(MouseButtonEvent click)
        {
            boolean handled = false;

            for (TextInput input : this.fields)
            {
                handled |= input.mouseReleased(click);
            }

            return handled;
        }

        /** Колесо над полем — шаг ±1 (Shift ×10, Ctrl ×100), как на вкладке «Выделение». */
        boolean mouseScrolled(double mouseX, double mouseY, double amount)
        {
            for (int i = 0; i < 3; ++i)
            {
                if (this.fields[i].contains(mouseX, mouseY))
                {
                    if (this.owner != null && amount != 0.0)
                    {
                        this.owner.worldNudge(i, (amount > 0 ? 1 : -1) * amount());
                    }

                    return true;
                }
            }

            return false;
        }

        boolean keyPressed(KeyEvent key)
        {
            for (TextInput input : this.fields)
            {
                if (input.focused())
                {
                    return input.keyPressed(key);
                }
            }

            return false;
        }

        boolean charTyped(CharacterEvent event)
        {
            for (TextInput input : this.fields)
            {
                if (input.focused())
                {
                    return input.charTyped(event);
                }
            }

            return false;
        }
    }
}
