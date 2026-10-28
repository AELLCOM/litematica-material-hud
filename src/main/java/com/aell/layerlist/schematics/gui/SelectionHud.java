package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toggle;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;

/**
 * «Смотреть на мир» (вкладка «Выделение»): экран закрыт, игра идёт как обычно — ходить, летать, ломать и ставить,
 * открывать инвентарь. Карточка координат остаётся HUD-ом сверху справа, там же, где стояла на экране, только без
 * кнопок; цифры живые — углы, сдвинутые инструментом Litematica в мире, видны сразу. Клавиша курсора (R) — карточка
 * оживает: тот же экран «Выделение» с одной карточкой, поля правятся, «+/−» жмутся; R ещё раз или щелчок мимо карточки
 * — снова игра. Esc — назад к выделению целиком: экран въезжает обратно, меню паузы в этот раз не открывается
 * ({@code MixinKeyboardHandlerSelectionHud}); пауза при сворачивании окна — как обычно.
 *
 * <p>Раскладку карточки (ширина, строки, столбцы X Y Z) считает {@link Card}: экран ставит свои поля в те же места,
 * поэтому при переходе карточка не прыгает.
 */
@Environment(EnvType.CLIENT)
public final class SelectionHud
{
    /**
     * Пиксельный стиль Material HUD, как у панели материалов в игре ({@code LayerHudRenderer}): углы — таблицей отступов
     * в пикселях интерфейса, радиус {@link #CARD_RADIUS}, кромка в пиксель интерфейса. Отступы — токены экрана: поле
     * {@code OUTER}, между соседними контролами {@code PAD_IN}, между столбцами X Y Z {@code PAD_REL}, между разделами
     * {@code PAD_GROUP} (черт внутри карточки нет).
     */
    static final int PAD = Ui.OUTER;
    static final int GAP = Ui.PAD_IN;
    private static final int GROUP_GAP = Ui.PAD_REL;
    private static final int SECTION_GAP = Ui.PAD_GROUP;
    /** Радиус угла карточки — как у панели материалов в игре. */
    static final int CARD_RADIUS = 6;
    /** Плашки клавиш и номера углов. */
    private static final int KEY_H = 11;
    static final int ROW_H = Ui.ROW_H;
    static final int TAG_W = 11;
    /** Кнопка «+/−» — квадрат во всю высоту строки (поле стоит на {@code y + 1}, высота {@code ROW_H - 1}). */
    static final int NUDGE_W = ROW_H - 1;
    static final int ICON_W = 18;
    static final int SIZE_H = Ui.BUTTON_H;
    static final int CAPTION_H = Ui.CTRL_H;
    private static final int CARD_MIN = 250;
    private static final int CARD_MAX = 340;

    private static boolean active;
    /** Откуда открывали экран «Выделение» — туда ведёт «Назад», когда экран вернётся. */
    private static @Nullable Screen parent;

    private SelectionHud() {}

    /**
     * Карточка координат: поле {@link #PAD}, зазоры между контролами {@link #GAP}, разделы — отступом, без черт. Строки
     * сверху вниз — заголовок, подписи
     * осей, угол 1, угол 2, размер (в мире справа — «Сбросить»), «Точка привязки», её строка; столбцы — метка, три
     * поля с «+/−», «Двигать в мире» и «К игроку».
     */
    record Card(int x, int y, int w, int h, int titleY, int axesY, int corner1Y, int corner2Y, int sizeY,
                int originCaptionY, int originY, int tagX, int[] fieldX, int fieldW, int[] nudgeX, int grabX, int toPlayerX)
    {
        static Card at(int x, int y, int w)
        {
            int titleY = y + PAD;
            int axesY = titleY + ROW_H + GAP;
            int corner1Y = axesY + Ui.TEXT_H + 2;
            int corner2Y = corner1Y + ROW_H + GAP;
            int sizeY = corner2Y + ROW_H + GAP;
            int originCaptionY = sizeY + SIZE_H + SECTION_GAP;
            int originY = originCaptionY + CAPTION_H + 2;
            int h = originY + ROW_H + PAD - y;

            int right = x + w - PAD;
            int toPlayerX = right - ICON_W;
            int grabX = toPlayerX - GAP - ICON_W;
            int from = x + PAD + TAG_W + GAP;
            int group = (grabX - GROUP_GAP - from - GROUP_GAP * 2) / 3;
            int fieldW = Math.max(26, group - GAP - NUDGE_W);
            int[] fieldX = new int[3];
            int[] nudgeX = new int[3];

            for (int i = 0; i < 3; ++i)
            {
                fieldX[i] = from + i * (group + GROUP_GAP);
                nudgeX[i] = fieldX[i] + fieldW + GAP;
            }

            return new Card(x, y, w, h, titleY, axesY, corner1Y, corner2Y, sizeY, originCaptionY, originY, x + PAD, fieldX, fieldW,
                    nudgeX, grabX, toPlayerX);
        }

        int inner()
        {
            return this.x + PAD;
        }

        int right()
        {
            return this.x + this.w - PAD;
        }
    }

    /** Ширина карточки на экране этой ширины — та же у экрана и у HUD. */
    static int cardWidth(int screenWidth)
    {
        return Math.max(CARD_MIN, Math.min(CARD_MAX, (screenWidth - Ui.OUTER * 2) * 43 / 100));
    }

    /** Место карточки в игре: сверху справа, ниже значков эффектов. */
    static Card hudCard(int screenWidth)
    {
        int w = cardWidth(screenWidth);
        int potions = RenderUtils.getHudOffsetForPotions(HudAlignment.TOP_RIGHT, 1.0, Minecraft.getInstance().player);
        return Card.at(screenWidth - Ui.OUTER - w, Ui.OUTER + potions, w);
    }

    public static void register()
    {
        RenderEventHandler.getInstance().registerInGameGuiRenderer(new IRenderer()
        {
            @Override
            public void onExtractGuiOverlayPost(GuiContext ctx, float partialTicks, ProfilerFiller profiler)
            {
                SelectionHud.render(ctx);
            }
        });
    }

    public static boolean active()
    {
        return active;
    }

    /** Экран доехал до мира и закрывается: дальше — игра с карточкой. */
    static void enter(@Nullable Screen from)
    {
        active = true;
        parent = from;
    }

    /** Экран «Схемы» открыли (как угодно) — карточка больше не нужна. */
    static void leave()
    {
        active = false;
        parent = null;
    }

    /** Esc в игре: вернуть экран «Выделение». {@code true} — вернули, паузу не открывать. */
    public static boolean escape()
    {
        Minecraft mc = Minecraft.getInstance();

        if (! active || mc.gui.screen() != null)
        {
            return false;
        }

        Screen back = parent;
        leave();

        if (mc.level == null)
        {
            return false;
        }

        GuiSchematics.returnFromWorld(back);
        return true;
    }

    /**
     * Клавиша курсора в игре ({@code SchematicsConfig.SELECTION_CURSOR}): карточка оживает — экран «Выделение» с одной
     * карточкой на этом же месте, поля правятся, «+/−» жмутся. {@code true} — открыли (клавишу дальше не отдаём).
     */
    public static boolean cursor()
    {
        Minecraft mc = Minecraft.getInstance();

        if (! active || mc.gui.screen() != null || mc.level == null)
        {
            return false;
        }

        Screen back = parent;
        leave();
        GuiSchematics.openCursor(back);
        return true;
    }

    /** Клавиша курсора — как подписать её на плашке; не назначена — пусто. */
    public static String cursorKey()
    {
        IKeybind keybind = SchematicsConfig.SELECTION_CURSOR.getKeybind();
        return keybind.getKeys().isEmpty() ? "" : keybind.getKeysDisplayString().replace("LEFT_", "").replace("RIGHT_", "R");
    }

    /** Эта клавиша — клавиша курсора (назначена одной клавишей). */
    static boolean isCursorKey(int key)
    {
        java.util.List<Integer> keys = SchematicsConfig.SELECTION_CURSOR.getKeybind().getKeys();
        return keys.size() == 1 && keys.get(0) == key;
    }

    private static void render(GuiContext ctx)
    {
        if (! active)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null)
        {
            leave();
            return;
        }

        Screen screen = mc.gui.screen();

        if (mc.gui.hud.isHidden() || (screen != null && ! (screen instanceof ChatScreen)))
        {
            return;
        }

        draw(ctx, Kit.font(), hudCard(GuiUtils.getScaledWindowWidth()));
    }

    /**
     * Карточка в игре — те же контролы, что на экране, только без ввода: клавиша курсора (R) делает живыми их же, на
     * этом же месте. Экземпляры — только для рисования.
     */
    private static final class Look
    {
        final TextInput[] fields = new TextInput[9];
        final AxisNudge[] nudges = new AxisNudge[9];
        final IconButton[] grab = new IconButton[3];
        final IconButton[] toPlayer = new IconButton[3];
        final Toggle origin = new Toggle(() -> originOn, value -> {});
        final Button reset = new Button("", Button.Style.DANGER, null);

        Look()
        {
            for (int row = 0; row < 3; ++row)
            {
                for (int axis = 0; axis < 3; ++axis)
                {
                    this.fields[row * 3 + axis] = new TextInput();
                    this.nudges[row * 3 + axis] = new AxisNudge(axis, "", step -> {});
                }

                this.grab[row] = new IconButton(Glyphs.TARGET, "", Button.Style.SECONDARY, null);
                this.toPlayer[row] = new IconButton(Glyphs.PLAYER, "", Button.Style.SECONDARY, null);
            }
        }
    }

    private static @Nullable Look look;
    private static boolean originOn;

    /** Карточка в игре: в заголовке — подсказки клавиш, ниже — то же, что на экране, без ввода. */
    private static void draw(GuiContext ctx, Font font, Card card)
    {
        if (look == null)
        {
            look = new Look();
        }

        Look l = look;
        AreaSelection selection = SelectionPane.selection();
        Box box = SelectionPane.selectedBox();
        boolean any = box != null;

        frame(ctx, font, card, true);
        hints(ctx, font, card, false);

        row(ctx, l, 0, card, card.corner1Y(), any ? box.getPos1() : null, any, selection != null,
                any && box.getSelectedCorner() == Corner.CORNER_1);
        row(ctx, l, 1, card, card.corner2Y(), any ? box.getPos2() : null, any, selection != null,
                any && box.getSelectedCorner() == Corner.CORNER_2);
        tag(ctx, font, card, card.corner1Y(), "1", any);
        tag(ctx, font, card, card.corner2Y(), "2", any);

        // Размер и «Сбросить» справа (сброс — по R, курсором)
        l.reset.label(Tr.t("selection.reset"));
        l.reset.setEnabled(SelectionPane.resettable(selection));
        int resetW = l.reset.preferredWidth(font);
        l.reset.setBounds(card.right() - resetW, card.sizeY(), resetW, SIZE_H);
        l.reset.render(ctx, -1, -1);
        Paint.midText(ctx, font, Paint.fit(font, SelectionPane.sizeLine(selection, box), card.w() - PAD * 2 - resetW - GAP), card.inner(),
                card.sizeY(), SIZE_H, Ui.C_TEXT_2, false);

        boolean explicit = selection != null && selection.getExplicitOrigin() != null;
        originOn = explicit;
        int toggleW = l.origin.preferredWidth(font);
        l.origin.setEnabled(selection != null);
        l.origin.setBounds(card.right() - toggleW, card.originCaptionY(), toggleW, CAPTION_H);
        l.origin.render(ctx, -1, -1);
        row(ctx, l, 2, card, card.originY(), SelectionPane.originShown(selection), explicit, selection != null,
                selection != null && selection.isOriginSelected());
        originTag(ctx, card, explicit);
    }

    /** Строка карточки без ввода: три поля с «+/−», прицел и «к игроку» — как {@code SelectionPane.CornerBlock}. */
    private static void row(GuiContext ctx, Look l, int row, Card card, int y, @Nullable BlockPos pos, boolean enabled, boolean any,
            boolean grabbed)
    {
        for (int i = 0; i < 3; ++i)
        {
            TextInput field = l.fields[row * 3 + i];
            field.setBounds(card.fieldX()[i], y, card.fieldW(), ROW_H);
            field.setEnabled(enabled);
            field.setText(pos == null ? (any ? "0" : "") : String.valueOf(i == 0 ? pos.getX() : i == 1 ? pos.getY() : pos.getZ()));
            field.render(ctx, -1, -1);

            AxisNudge nudge = l.nudges[row * 3 + i];
            nudge.setBounds(card.nudgeX()[i], y + 1, NUDGE_W, ROW_H - 1);
            nudge.setEnabled(enabled);
            nudge.render(ctx, -1, -1);
        }

        int color = row == 0 ? SelectionPane.C_CORNER1 : row == 1 ? SelectionPane.C_CORNER2 : SelectionPane.C_ORIGIN;
        l.grab[row].style(grabbed ? Button.Style.PRIMARY : Button.Style.SECONDARY).accent(color).selected(grabbed);
        l.grab[row].setEnabled(pos != null);
        l.grab[row].setBounds(card.grabX(), y, ICON_W, ROW_H);
        l.grab[row].render(ctx, -1, -1);
        l.toPlayer[row].setEnabled(enabled);
        l.toPlayer[row].setBounds(card.toPlayerX(), y, ICON_W, ROW_H);
        l.toPlayer[row].render(ctx, -1, -1);
    }

    /** Общее у экрана и HUD: подложка, заголовок, подписи осей, «Точка привязки». */
    static void frame(GuiContext ctx, Font font, Card card)
    {
        frame(ctx, font, card, false);
    }

    /** {@code hud} — карточка в игре: в ванильном стиле она в рамке подсказки Minecraft, на экране — панелью окна. */
    static void frame(GuiContext ctx, Font font, Card card, boolean hud)
    {
        panel(ctx, card.x(), card.y(), card.w(), card.h(), hud);

        Paint.midText(ctx, font, Tr.t("selection.coords"), card.inner(), card.titleY(), ROW_H, Ui.C_TEXT, true);

        String[] names = { "X", "Y", "Z" };

        for (int i = 0; i < 3; ++i)
        {
            int cx = card.fieldX()[i] + (card.fieldW() + GAP + NUDGE_W - font.width(names[i])) / 2;
            Paint.midText(ctx, font, names[i], cx, card.axesY(), Ui.TEXT_H, AxisNudge.axisColor(i), true);
        }

        Paint.midText(ctx, font, Tr.t("selection.origin.title"), card.inner(), card.originCaptionY(), CAPTION_H, SelectionPane.C_ORIGIN, false);
    }

    /** Подложка карточки: панель окна или рамка подсказки Minecraft (ванильный стиль, в игре) — и у карточки размещения. */
    static void panel(GuiContext ctx, int x, int y, int w, int h, boolean hud)
    {
        if (hud && Style.vanilla())
        {
            Vanilla.tooltip(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, CARD_RADIUS, Ui.C_PANEL, Ui.C_PANEL_EDGE);
        }
    }

    /**
     * Подсказки справа в заголовке карточки: «[R] курсор · [Esc] меню» в игре, «[R] в игру · [Esc] меню», когда курсор
     * у карточки ({@code cursor}).
     */
    static void hints(GuiContext ctx, Font font, Card card, boolean cursor)
    {
        int x = hint(ctx, font, card, card.right(), "Esc", Tr.t("selection.hud.menu"));
        String key = cursorKey();

        if (! key.isEmpty())
        {
            hint(ctx, font, card, x - Ui.PAD_REL, key, Tr.t(cursor ? "selection.hud.game" : "selection.hud.cursor"));
        }
    }

    /** Клавиша плашкой (как метки углов) и что она делает — справа налево от {@code right}; возвращает левый край. */
    private static int hint(GuiContext ctx, Font font, Card card, int right, String key, String text)
    {
        return hint(ctx, font, card.titleY(), right, key, text);
    }

    /** То же для карточки с другими строками (размещение): {@code titleY} — верх строки заголовка. */
    static int hint(GuiContext ctx, Font font, int titleY, int right, String key, String text)
    {
        int keyW = font.width(key) + 6;
        int tx = right - font.width(text);
        int kx = tx - GAP - keyW;
        Paint.card(ctx, kx, titleY + (ROW_H - KEY_H) / 2, keyW, KEY_H, 2, 0x30FFFFFF, Ui.C_PANEL_EDGE);
        Paint.midText(ctx, font, key, kx + 3, titleY, ROW_H, Ui.C_TEXT, false);
        Paint.midText(ctx, font, text, tx, titleY, ROW_H, Ui.C_TEXT_2, false);
        return kx;
    }

    /** Номер угла — нейтральной плашкой: цвета заняты осями. */
    static void tag(GuiContext ctx, Font font, Card card, int y, String tag, boolean enabled)
    {
        Paint.card(ctx, card.tagX(), y + (ROW_H - KEY_H) / 2, TAG_W, KEY_H, 2, 0x30FFFFFF, Ui.C_PANEL_EDGE);
        Paint.midText(ctx, font, tag, card.tagX() + (TAG_W - font.width(tag)) / 2 + 1, y, ROW_H, enabled ? Ui.C_TEXT : Ui.C_TEXT_OFF, false);
    }

    /** Метка строки точки привязки — кружок её цвета. */
    static void originTag(GuiContext ctx, Card card, boolean enabled)
    {
        originTag(ctx, card.tagX(), card.originY(), enabled);
    }

    /** То же для карточки с другими строками (размещение): кружок в столбце меток {@code tagX}, строка с верхом {@code rowY}. */
    static void originTag(GuiContext ctx, int tagX, int rowY, boolean enabled)
    {
        int s = 7;
        Paint.round(ctx, tagX + (TAG_W - s) / 2, rowY + (ROW_H - s) / 2, s, s, 3,
                enabled ? SelectionPane.C_ORIGIN : Ui.alpha(SelectionPane.C_ORIGIN, Ui.A_DISABLED));
    }
}
