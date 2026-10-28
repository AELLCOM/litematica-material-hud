package com.aell.layerlist.hud;

import com.aell.layerlist.api.CountScope;
import com.aell.layerlist.api.LayerListApi;
import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Draw;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.gui.GuiHudPosition;
import com.aell.layerlist.input.InputHandler;
import com.aell.layerlist.schematics.SchematicsModule;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class LayerHudRenderer implements IInfoHudRenderer
{
    private static final LayerHudRenderer INSTANCE = new LayerHudRenderer();

    private static final int COLOR_BG        = Theme.PANEL;
    private static final int COLOR_BORDER    = Theme.EDGE;
    private static final int COLOR_TITLE     = Theme.TEXT;
    private static final int COLOR_LABEL     = Theme.BLUE;
    private static final int COLOR_TEXT      = Theme.TEXT;
    private static final int COLOR_MUTED     = Theme.MUTED;
    private static final int COLOR_OK        = Theme.GREEN;
    private static final int COLOR_PARTIAL   = Theme.YELLOW;
    private static final int COLOR_BAD       = Theme.RED;
    private static final int COLOR_BAR_BG    = Theme.ROW_DEEP;
    private static final int COLOR_BAR_FILL  = Theme.GREEN;

    public static int okColor()
    {
        return COLOR_OK;
    }

    public static int partialColor()
    {
        return COLOR_PARTIAL;
    }

    public static int badColor()
    {
        return COLOR_BAD;
    }

    private static final int PADDING       = 4;
    // Отступ панели от края экрана в раскладке InfoHud
    private static final int HUD_EDGE = 2;
    private static final int LINE_HEIGHT   = 16;
    private static final int TITLE_HEIGHT  = 12;
    private static final int MIN_WIDTH     = 130;
    private static final int TEXT_HEIGHT   = 9;
    private static final int BAR_HEIGHT    = 4;
    private static final int BAR_TEXT_GAP  = 2;
    private static final int PROGRESS_BLOCK = BAR_HEIGHT + BAR_TEXT_GAP + TEXT_HEIGHT;
    private static final int HEADER_GAP    = 3;
    private static final int DONE_HEIGHT   = 12;
    private static final int FOOTER_LINE   = 11;
    /** Подпись области от аддона (имя участка и т. п.) идёт в ширину не больше этого, длиннее — бежит. */
    private static final int SCOPE_LABEL_MAX_W = 100;
    private static final int TITLE_GAP     = 8;
    /** Когда строка впервые появилась на панели: бегущая строка начинает круг с начала и паузы, а не с середины. */
    private static final java.util.Map<String, long[]> MARQUEE_SEEN = new java.util.HashMap<>();

    private static final float GHOST_ALPHA = 0.3f;

    private static float alphaFactor = 1.0f;

    private static final String[] KEY_NAME_REPLACEMENTS = {
            "PAGE_UP", "PgUp",
            "PAGE_DOWN", "PgDn",
            "PRINT_SCREEN", "PrtSc",
            "SCROLL_LOCK", "ScrLk",
            "CAPS_LOCK", "Caps",
            "NUM_LOCK", "NumLk",
            "LEFT_CONTROL", "Ctrl",
            "RIGHT_CONTROL", "RCtrl",
            "LEFT_SHIFT", "Shift",
            "RIGHT_SHIFT", "RShift",
            "LEFT_ALT", "Alt",
            "RIGHT_ALT", "RAlt",
            "LEFT_SUPER", "Super",
            "RIGHT_SUPER", "RSuper",
            "LEFT_BRACKET", "[",
            "RIGHT_BRACKET", "]",
            "BUTTON_", "Mouse",
            "KP_", "Num",
            "BACKSPACE", "Bksp",
            "ESCAPE", "Esc",
            "UP", "Up",
            "DOWN", "Down",
            "LEFT", "Left",
            "RIGHT", "Right",
    };

    private record Row(MaterialListEntry entry, String count, int countColor, String shortfall) {}

    private record Layout(int width, int height, String title, String layerLabel,
                          boolean showProgress, String progressText, long placed, long total,
                          String doneText, boolean doneOk,
                          String pageText, String skippedText, String cachedText, String pinnedText,
                          List<String> addonLines, List<Row> rows) {}

    public static LayerHudRenderer getInstance()
    {
        return INSTANCE;
    }

    @Override
    public boolean getShouldRenderText(RenderPhase phase)
    {
        return false;
    }

    @Override
    public List<String> getText(RenderPhase phase)
    {
        return Collections.emptyList();
    }

    @Override
    public boolean getShouldRenderCustom()
    {
        return LayerCountManager.getInstance().isHudEnabled()
                && LayerCountManager.getInstance().hasData();
    }

    @Override
    public boolean shouldRenderInGuis()
    {
        net.minecraft.client.gui.screens.Screen screen = GuiUtils.getCurrentScreen();

        if (SchematicsModule.coversHud(screen) || ownMenu(screen))
        {
            return false;
        }

        return Configs.Generic.RENDER_MATERIALS_IN_GUI.getBooleanValue()
                || InputHandler.isContainerScrollActive();
    }

    /**
     * Открыто меню одного из наших модов (Material HUD, Zone Focus, Easy Place Fix+, Ghost Alpha, aell-ui и
     * других — пакеты {@code com.aell.} и {@code dev.aell.}): панель на это время прячется, чтобы не лечь
     * поверх настроек. Экран расположения панели — исключение: там её и двигают.
     */
    static boolean ownMenu(net.minecraft.client.gui.screens.@Nullable Screen screen)
    {
        if (screen == null || screen instanceof GuiHudPosition
                || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
        {
            return false;
        }

        String name = screen.getClass().getName();
        return name.startsWith("com.aell.") || name.startsWith("dev.aell.");
    }

    /** Для самопроверки: сколько раз панель нарисована всего и из экрана контейнера с прошлого вызова. */
    private static int drawsTotal;
    private static int drawsByScreen;

    public static int[] takeDrawStats()
    {
        int[] stats = {drawsTotal, drawsByScreen};
        drawsTotal = 0;
        drawsByScreen = 0;
        return stats;
    }

    /*
     * Открытый контейнер: панель рисует сам экран (MixinAbstractContainerScreen) — над слотами, но
     * под предметом на курсоре и под подсказкой предмета. InfoHud Litematica зовёт панель из хвоста
     * Gui.extractRenderState, уже после экрана и его подсказки, и оттуда панель легла бы поверх
     * подсказки. Сдвиги и выравнивание экран берёт у последнего вызова InfoHud; в кадре, где экран
     * панель уже нарисовал, InfoHud её пропускает. Экран-наследник, который не зовёт рисование
     * контейнера, ничего не отметит — тогда панель, как раньше, рисует InfoHud.
     */
    private static final long INFO_HUD_FRESH_MS = 500L;

    private static int infoHudX;
    private static int infoHudY;
    private static @Nullable HudAlignment infoHudAlignment;
    private static long infoHudAt;
    /**
     * Длина последнего кадра (между вызовами InfoHud): «свежесть» — не меньше трёх кадров. При двух-трёх кадрах в
     * секунду (слабая машина, сотня модов) кадр длиннее 500 мс, и экран бросал панель на InfoHud — та ложилась поверх
     * подсказки (14.10.2026).
     */
    private static long infoHudFrameMs;
    private static boolean drawnByScreen;
    private static int drawnHeight;

    private static int mouseX = Integer.MIN_VALUE;
    private static int mouseY = Integer.MIN_VALUE;

    /** Из экрана контейнера — до предмета на курсоре и подсказки. */
    public static void renderUnderTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY)
    {
        LayerHudRenderer hud = getInstance();
        TransferButton.forget();

        if (infoHudAlignment == null
                || net.minecraft.util.Util.getMillis() - infoHudAt > Math.max(INFO_HUD_FRESH_MS, 3L * infoHudFrameMs)
                || ! hud.getShouldRenderCustom() || ! hud.shouldRenderInGuis())
        {
            return;
        }

        LayerHudRenderer.mouseX = mouseX;
        LayerHudRenderer.mouseY = mouseY;

        try
        {
            drawnHeight = hud.draw(graphics, infoHudX, infoHudY, infoHudAlignment);
            drawnByScreen = true;
            ++drawsByScreen;
        }
        finally
        {
            LayerHudRenderer.mouseX = Integer.MIN_VALUE;
            LayerHudRenderer.mouseY = Integer.MIN_VALUE;
        }
    }

    @Override
    public int render(GuiGraphicsExtractor graphics, int xOffset, int yOffset, HudAlignment alignment)
    {
        infoHudX = xOffset;
        infoHudY = yOffset;
        infoHudAlignment = alignment;
        long now = net.minecraft.util.Util.getMillis();
        long frame = now - infoHudAt;
        // Пауза дольше двух секунд — не кадр (HUD не рисовался): со старыми сдвигами экран панель не держит
        infoHudFrameMs = frame <= 2000L ? frame : 0L;
        infoHudAt = now;

        if (drawnByScreen)
        {
            drawnByScreen = false;
            return drawnHeight;
        }

        return this.draw(graphics, xOffset, yOffset, alignment);
    }

    private int draw(GuiGraphicsExtractor graphics, int xOffset, int yOffset, HudAlignment alignment)
    {
        ++drawsTotal;
        Minecraft mc = Minecraft.getInstance();
        GuiContext ctx = GuiContext.fromGuiGraphics(graphics);
        Font font = mc.font;
        double scale = getScale();

        if (scale <= 0.0)
        {
            return 0;
        }

        Layout layout = buildLayout(font);

        alphaFactor = GuiUtils.getCurrentScreen() instanceof GuiHudPosition ? GHOST_ALPHA : 1.0f;

        // Открыт контейнер — панель встаёт рядом с его окном, где бы она ни стояла обычно;
        // места нет ни слева, ни справа — остаётся на своём месте
        if (LayerListConfigs.HUD_BESIDE_CONTAINER.getBooleanValue())
        {
            net.minecraft.client.gui.screens.Screen screen = GuiUtils.getCurrentScreen();
            HudDock.Dock dock = dock(screen, layout, scale);

            if (dock != null)
            {
                if (dock.hasButton() && alphaFactor >= 1.0f)
                {
                    TransferButton.draw(ctx, font, screen, dock.x(), dock.buttonY(),
                            (int) Math.ceil(layout.width() * dock.scale()), mouseX, mouseY);
                }

                drawPanel(ctx, font, layout, dock.x(), dock.y(), dock.scale());
                return 0;
            }
        }

        if (LayerListConfigs.HUD_CUSTOM_POSITION.getBooleanValue())
        {
            int posX = clampScreenX(LayerListConfigs.HUD_POS_X, (int) Math.ceil(layout.width() * scale));
            int posY = clampScreenY(LayerListConfigs.HUD_POS_Y, (int) Math.ceil(layout.height() * scale));
            drawPanel(ctx, font, layout, posX, posY, scale);
            return 0;
        }

        int[] origin = hudOrigin(GuiUtils.getScaledWindowWidth(), scale, layout.width(), layout.height(),
                xOffset, yOffset, alignment, mc.player);

        drawScaled(ctx, font, layout, origin[0], origin[1], scale);

        return (int) Math.ceil(layout.height() * scale) + 4;
    }

    /**
     * Левый верхний угол панели в её собственных единицах (до масштаба) для раскладки InfoHud при этом
     * выравнивании. По ширине — от свободного места: что остаётся от экрана, если панель прижать к левому
     * краю. По высоте — штатной раскладкой malilib ({@code getHudPosY}) плюс сдвиг под значки зелий.
     */
    static int[] hudOrigin(int screenWidth, double scale, int panelWidth, int panelHeight, int xOffset, int yOffset,
                           HudAlignment alignment, @Nullable Player player)
    {
        double freeWidth = (double) screenWidth / scale - panelWidth;
        int x = switch (alignment)
        {
            case TOP_RIGHT, BOTTOM_RIGHT -> (int) (freeWidth - xOffset - HUD_EDGE);
            case CENTER -> (int) (freeWidth / 2.0 - xOffset);
            default -> xOffset + HUD_EDGE;
        };
        // Смещение по высоте тоже в единицах панели; при масштабе 1 деление ничего не меняет
        int y = RenderUtils.getHudPosY(yOffset + HUD_EDGE, (int) (yOffset / scale), panelHeight, scale, alignment)
                + RenderUtils.getHudOffsetForPotions(alignment, scale, player);

        return new int[] { x, y };
    }

    public static int[] getPanelScreenSize()
    {
        return getPanelScreenSize(getScale());
    }

    public static int[] getPanelScreenSize(double scale)
    {
        Layout layout = buildLayout(Minecraft.getInstance().font);

        return new int[] { (int) Math.ceil(layout.width() * scale), (int) Math.ceil(layout.height() * scale) };
    }

    /** Место панели рядом с открытым контейнером в текущих настройках — то же, что берёт {@link #render}. */
    public static HudDock.@Nullable Dock dockBesideContainer(@Nullable net.minecraft.client.gui.screens.Screen screen)
    {
        if (! LayerListConfigs.HUD_BESIDE_CONTAINER.getBooleanValue())
        {
            return null;
        }

        return dock(screen, buildLayout(Minecraft.getInstance().font), getScale());
    }

    /** Место у контейнера; на экране с хранилищем — с кнопкой «Перенести всё необходимое» сверху. */
    private static HudDock.@Nullable Dock dock(@Nullable net.minecraft.client.gui.screens.Screen screen, Layout layout,
            double scale)
    {
        return HudDock.beside(screen, layout.width(), layout.height(), scale,
                LayerListConfigs.HUD_DOCK_RIGHT.getBooleanValue(),
                TransferButton.wanted(screen) ? TransferButton.reserve() : 0);
    }

    public static void drawAtScreenPos(GuiContext ctx, int screenX, int screenY)
    {
        Font font = Minecraft.getInstance().font;
        alphaFactor = 1.0f;
        drawPanel(ctx, font, buildLayout(font), screenX, screenY, getScale());
    }

    public static double getScale()
    {
        return LayerListConfigs.HUD_SCALE.getDoubleValue();
    }

    public static int clampScreenX(int screenX, int panelWidthPx)
    {
        return clamp(screenX, panelWidthPx, GuiUtils.getScaledWindowWidth());
    }

    public static int clampScreenY(int screenY, int panelHeightPx)
    {
        return clamp(screenY, panelHeightPx, GuiUtils.getScaledWindowHeight());
    }

    private static int clamp(int pos, int sizePx, int screenSizePx)
    {
        return Math.max(0, Math.min(pos, Math.max(0, screenSizePx - sizePx)));
    }

    private static void drawPanel(GuiContext ctx, Font font, Layout layout, int screenX, int screenY, double scale)
    {
        int drawX = scale != 1.0 ? (int) Math.round(screenX / scale) : screenX;
        int drawY = scale != 1.0 ? (int) Math.round(screenY / scale) : screenY;
        drawScaled(ctx, font, layout, drawX, drawY, scale);
    }

    private static void drawScaled(GuiContext ctx, Font font, Layout layout, int posX, int posY, double scale)
    {
        if (scale != 1.0)
        {
            ctx.pose().pushMatrix();
            ctx.pose().scale((float) scale, (float) scale);
        }

        drawContents(ctx, font, layout, posX, posY);

        if (scale != 1.0)
        {
            ctx.pose().popMatrix();
        }
    }

    /** Момент, с которого строка видна на панели; не виденные дольше 2 с забываются (снова появится — с начала). */
    private static long marqueeSince(String text)
    {
        long now = net.minecraft.util.Util.getMillis();
        MARQUEE_SEEN.values().removeIf(seen -> now - seen[1] > 2000L);
        long[] seen = MARQUEE_SEEN.computeIfAbsent(text, key -> new long[] { now, now });
        seen[1] = now;

        return seen[0];
    }

    private static void drawContents(GuiContext ctx, Font font, Layout layout, int posX, int posY)
    {
        int width = layout.width();

        if (Style.vanilla() && alphaFactor >= 1.0f)
        {
            // Ванильный стиль: фон панели — рамка подсказки Minecraft (в редакторе позиции панель
            // полупрозрачная, там остаётся заливка с затуханием)
            Vanilla.tooltip(ctx, posX, posY, width, layout.height());
        }
        else
        {
            Draw.card(ctx, posX, posY, width, layout.height(), 6, fade(COLOR_BG), fade(COLOR_BORDER));
        }

        int x = posX + PADDING;
        int y = posY + PADDING;

        Paint.text(ctx, font, layout.title(), x, y, fade(COLOR_TITLE), false);
        int labelRoom = width - PADDING * 2 - font.width(layout.title()) - TITLE_GAP;
        int labelWidth = Math.min(font.width(layout.layerLabel()), labelRoom);
        Paint.marquee(ctx, font, layout.layerLabel(), posX + width - PADDING - labelWidth, y, labelRoom,
                fade(COLOR_LABEL), false, marqueeSince(layout.layerLabel()));
        y += TITLE_HEIGHT;

        if (layout.showProgress())
        {
            int barWidth = width - PADDING * 2;
            int fillWidth = layout.total() > 0
                    ? (int) ((long) barWidth * layout.placed() / layout.total())
                    : barWidth;
            boolean full = fillWidth >= barWidth;
            Draw.round(ctx, x, y, barWidth, BAR_HEIGHT, 2, fade(COLOR_BAR_BG));

            if (fillWidth > 0)
            {
                Draw.round(ctx, x, y, Math.max(fillWidth, BAR_HEIGHT), BAR_HEIGHT, 2,
                        fade(full ? COLOR_BAR_FILL : Theme.BLUE));
            }

            Paint.text(ctx, font, layout.progressText(), x, y + BAR_HEIGHT + BAR_TEXT_GAP,
                    fade(COLOR_MUTED), false);
        }

        y = posY + headerHeight(layout.showProgress());

        if (layout.doneText() != null)
        {
            Paint.text(ctx, font, layout.doneText(), x, y, fade(layout.doneOk() ? COLOR_OK : COLOR_MUTED), false);
            y += DONE_HEIGHT;
        }
        else
        {
            boolean searchMarkers = LayerListConfigs.SHOW_SEARCH_MARKERS.getBooleanValue();

            for (Row row : layout.rows())
            {
                if (searchMarkers)
                {
                    Integer searchColor = StorageSearch.getInstance().colorFor(row.entry().getStack());

                    if (searchColor != null)
                    {
                        Draw.round(ctx, posX + 2, y + 1, 3, LINE_HEIGHT - 3, 1, fade(searchColor));
                    }
                }

                if (alphaFactor >= 1.0f)
                {
                    ctx.renderItem(row.entry().getStack(), x, y);
                }

                String name = DataItems.label(row.entry().getStack());
                Paint.text(ctx, font, name, x + 20, y + 4, fade(COLOR_TEXT), false);

                int countX = posX + width - PADDING - font.width(row.count());
                Paint.text(ctx, font, row.count(), countX, y + 4, fade(row.countColor()), false);

                if (row.shortfall() != null)
                {
                    Paint.text(ctx, font, row.shortfall(),
                            countX - 8 - font.width(row.shortfall()), y + 4, fade(COLOR_BAD), false);
                }

                y += LINE_HEIGHT;
            }
        }

        // Строки подвала необязательные: ширину панели не задают, длиннее неё — бегут (Paint.marquee)
        int room = width - PADDING * 2;

        if (layout.pageText() != null)
        {
            Paint.marquee(ctx, font, layout.pageText(), x, y + 1, room, fade(COLOR_MUTED), false, marqueeSince(layout.pageText()));
            y += FOOTER_LINE;
        }

        if (layout.skippedText() != null)
        {
            Paint.marquee(ctx, font, layout.skippedText(), x, y + 1, room, fade(COLOR_PARTIAL), false, marqueeSince(layout.skippedText()));
            y += FOOTER_LINE;
        }

        if (layout.cachedText() != null)
        {
            Paint.marquee(ctx, font, layout.cachedText(), x, y + 1, room, fade(COLOR_MUTED), false, marqueeSince(layout.cachedText()));
            y += FOOTER_LINE;
        }

        if (layout.pinnedText() != null)
        {
            Paint.marquee(ctx, font, layout.pinnedText(), x, y + 1, room, fade(COLOR_LABEL), false, marqueeSince(layout.pinnedText()));
            y += FOOTER_LINE;
        }

        for (String line : layout.addonLines())
        {
            Paint.marquee(ctx, font, line, x, y + 1, room, fade(COLOR_LABEL), false, marqueeSince(line));
            y += FOOTER_LINE;
        }
    }

    private static int headerHeight(boolean showProgress)
    {
        return PADDING + TITLE_HEIGHT + (showProgress ? PROGRESS_BLOCK : 0) + HEADER_GAP;
    }

    private static int fade(int color)
    {
        if (alphaFactor >= 1.0f)
        {
            return color;
        }

        int alpha = (int) ((color >>> 24) * alphaFactor);

        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static Layout buildLayout(Font font)
    {
        LayerCountManager manager = LayerCountManager.getInstance();
        List<MaterialListEntry> entries = manager.getEntries();
        boolean showProgress = LayerListConfigs.SHOW_PROGRESS_BAR.getBooleanValue();

        int perPage = Math.max(1, LayerListConfigs.ITEMS_PER_PAGE.getIntegerValue());
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int page = Math.min(manager.getPage(), pages - 1);
        int startIndex = page * perPage;
        int rowCount = Math.min(perPage, entries.size() - startIndex);

        String title = Strings.plain("layerlist.hud.title");
        CountScope scope = LayerListApi.getCountScope();
        boolean scopeActive = scope != null && scope.isActive();
        String layerLabel = scopeActive ? scope.getLabel() : getLayerLabel();
        List<String> addonLines = scopeActive ? List.copyOf(scope.getFooterLines()) : List.of();
        int stockHidden = manager.getStockHidden();

        if (stockHidden > 0)
        {
            addonLines = new ArrayList<>(addonLines);
            addonLines.add(StringUtils.translate("layerlist.hud.stock_hidden", stockHidden));
        }

        long total = manager.getBlocksTotal();
        long missing = manager.getBlocksMissing();
        long placed = total - missing;
        int percent = total > 0 ? (int) (placed * 100L / total) : 100;
        String progressText = StringUtils.translate("layerlist.hud.progress", placed, total, percent);

        String doneText = null;
        boolean doneOk = false;

        if (rowCount <= 0)
        {
            doneOk = total > 0 && missing == 0;
            doneText = doneOk
                    ? Strings.plain("layerlist.hud.done")
                    : stockHidden > 0 ? Strings.plain("layerlist.hud.stock_covered")
                    : Strings.plain("layerlist.hud.empty");
            // Всё спрятанное складом — тоже «готово»: зелёным, как собранный слой
            doneOk |= stockHidden > 0;
        }

        String pageText = pages > 1 ? buildPageText(page + 1, pages) : null;
        String skippedText = manager.getUnverifiedChunks() > 0
                ? StringUtils.translate("layerlist.hud.unverified_chunks", manager.getUnverifiedChunks())
                : null;
        String cachedText = manager.getCachedChunks() > 0
                ? StringUtils.translate("layerlist.hud.cached_chunks", manager.getCachedChunks())
                : null;
        String pinnedText = manager.isShowingPinned()
                ? StringUtils.translate("layerlist.hud.pinned",
                        dimensionName(manager.getPinnedDimension()), manager.getPinnedPlacements())
                : null;

        // Своя подпись слоя («Y = 61») короткая; подпись от аддона длиннее SCOPE_LABEL_MAX_W — бежит, а не раздувает
        int labelWidth = font.width(layerLabel);
        int width = font.width(title) + (scopeActive ? Math.min(labelWidth, SCOPE_LABEL_MAX_W) : labelWidth)
                + TITLE_GAP * 2;

        if (showProgress) { width = Math.max(width, font.width(progressText) + 8); }
        if (doneText != null) { width = Math.max(width, font.width(doneText) + 8); }
        // Подвал (страницы, непроверенные и кэшированные чанки, снимок, строки аддонов) ширину не задаёт:
        // длиннее панели — бежит (Paint.marquee), а не раздувает её (правило 24.10.2026, см. Paint.marquee)

        List<Row> rows = new ArrayList<>(Math.max(rowCount, 0));
        boolean showShortfall = LayerListConfigs.SHOW_SHORTFALL.getBooleanValue();

        for (int i = 0; i < rowCount; ++i)
        {
            MaterialListEntry entry = entries.get(startIndex + i);
            String count = formatCount(entry.getCountMissing(), entry.getStack().getMaxStackSize());
            int countColor = entry.getCountAvailable() >= entry.getCountMissing() ? COLOR_OK
                    : entry.getCountAvailable() > 0 ? COLOR_PARTIAL : COLOR_BAD;

            // «С учётом склада» — за вычетом и известных сундуков
            int lacking = manager.getShortfall(entry);
            String shortfall = showShortfall && lacking > 0
                    ? StringUtils.translate("layerlist.hud.shortfall", lacking)
                    : null;

            rows.add(new Row(entry, count, countColor, shortfall));

            int rowWidth = 20 + font.width(DataItems.label(entry.getStack())) + 10 + font.width(count)
                    + (shortfall != null ? font.width(shortfall) + 8 : 0);
            width = Math.max(width, rowWidth);
        }

        width = Math.max(width, MIN_WIDTH) + PADDING * 2;

        int height = headerHeight(showProgress)
                + (doneText != null ? DONE_HEIGHT : rows.size() * LINE_HEIGHT)
                + (pageText != null ? FOOTER_LINE : 0)
                + (skippedText != null ? FOOTER_LINE : 0)
                + (cachedText != null ? FOOTER_LINE : 0)
                + (pinnedText != null ? FOOTER_LINE : 0)
                + addonLines.size() * FOOTER_LINE
                + PADDING;

        return new Layout(width, height, title, layerLabel, showProgress, progressText, placed, total,
                doneText, doneOk, pageText, skippedText, cachedText, pinnedText, addonLines, rows);
    }

    /** Имя измерения для строки о закреплённом списке: ванильные — по переводу, прочие — по пути. */
    private static String dimensionName(String id)
    {
        String key = switch (id)
        {
            case "minecraft:overworld" -> "layerlist.hud.dim.overworld";
            case "minecraft:the_nether" -> "layerlist.hud.dim.the_nether";
            case "minecraft:the_end" -> "layerlist.hud.dim.the_end";
            default -> null;
        };
        int colon = id.indexOf(':');
        return key != null ? Strings.plain(key) : (colon >= 0 ? id.substring(colon + 1) : id);
    }

    private static String buildPageText(int page, int pages)
    {
        String up = keysDisplay(LayerListConfigs.SCROLL_UP.getKeybind());
        String down = keysDisplay(LayerListConfigs.SCROLL_DOWN.getKeybind());

        String hint = up.isEmpty() ? down
                : down.isEmpty() ? up
                : up.equals(down) ? up
                : up + "/" + down;

        return hint.isEmpty()
                ? StringUtils.translate("layerlist.hud.page_no_keys", page, pages)
                : StringUtils.translate("layerlist.hud.page", page, pages, hint);
    }

    private static String keysDisplay(IKeybind keybind)
    {
        if (keybind.getKeys().isEmpty())
        {
            return "";
        }

        String display = keybind.getKeysDisplayString();

        for (int i = 0; i < KEY_NAME_REPLACEMENTS.length; i += 2)
        {
            display = display.replace(KEY_NAME_REPLACEMENTS[i], KEY_NAME_REPLACEMENTS[i + 1]);
        }

        return display;
    }

    private static String getLayerLabel()
    {
        LayerRange range = DataManager.getRenderLayerRange();
        String axis = range.getAxis().getName().toUpperCase();

        return switch (range.getLayerMode())
        {
            case SINGLE_LAYER -> axis + " = " + range.getLayerSingle();
            case LAYER_RANGE -> axis + " " + range.getLayerRangeMin() + " .. " + range.getLayerRangeMax();
            case ALL_ABOVE -> axis + " >= " + range.getLayerAbove();
            case ALL_BELOW -> axis + " <= " + range.getLayerBelow();
            case ALL -> Strings.plain("layerlist.hud.all_layers");
            default -> "";
        };
    }

    private static String formatCount(int count, int maxStackSize)
    {
        if (count > maxStackSize && maxStackSize > 1)
        {
            double boxCount = (double) count / (27.0 * maxStackSize);

            // От целого шалкера — в шалкерах; опцией можно оставить стопки и для больших чисел
            if (boxCount >= 1.0 && LayerListConfigs.HUD_COUNT_IN_BOXES.getBooleanValue())
            {
                return String.format("%d (%.1f SB)", count, boxCount);
            }

            int stacks = count / maxStackSize;
            int remainder = count % maxStackSize;

            if (remainder > 0)
            {
                return String.format("%d (%dx%d+%d)", count, stacks, maxStackSize, remainder);
            }

            return String.format("%d (%dx%d)", count, stacks, maxStackSize);
        }

        return String.valueOf(count);
    }
}
