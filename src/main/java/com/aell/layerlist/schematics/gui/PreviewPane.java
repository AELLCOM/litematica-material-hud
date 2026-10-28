package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.ui.kit.Modal;

import com.aell.layerlist.schematics.SchematicsConfig;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.schematics.preview.PreviewCamera;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.schematics.preview.ZoneOverlay;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Slider;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.malilib.render.GuiContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Центр «Библиотеки»: 3D-превью ({@link PreviewWidget}, модуль M23) с полоской вида сверху
 * («Изометрия», «Сверху», «Спереди» и ползунок «Срез» — верхний показанный слой) и строкой статуса
 * снизу («Загрузка…», «Сборка модели 45 %», ошибка). Ничего не выбрано — подсказка по центру.
 *
 * <p>Мышь внутри окна превью целиком отдаётся виджету: вращение, панорама, масштаб, сброс — его дело.
 *
 * <p>С Zone Focus 0.0.8+ на модели рисуются зоны схемы с номерами ({@link ZoneOverlay}), а пункт меню файла
 * «Разбить на зоны…» открывает снизу панель разбиения: число зон по X, Y, Z (поля с вводом, −/+, колесо),
 * «Разбить», «Отменить разбиение», «Управление зонами…» (если схема размещена). Разбивать можно без
 * размещения — сетка хранится для файла и встаёт на размещение сама. Пока числа не совпадают с
 * разбиением, на модели черновик синим. Щелчок по зоне размещённой схемы делает её активной. Выбрали
 * другой файл — панель закрывается.
 */
@Environment(EnvType.CLIENT)
final class PreviewPane extends Pane
{
    /** Ключ пустого превью: виджет сравнивает ключи, чтобы не пересобирать модель. */
    private static final Object NO_SOURCE = new Object();
    private static final int SLIDER_W = 96;
    private static final int SLIDER_MIN_W = 40;

    private static final int ZONE_FIELD_W = 26;
    private static final int ZONE_POLL_FRAMES = 10;
    private static final long CONFIRM_MS = 3000L;
    private static final double CLICK_SLOP = 3.0;

    private final PreviewWidget widget = new PreviewWidget();
    private final Button iso;
    private final Button top;
    private final Button front;
    private final Button side;
    private final Slider cut;
    /** «Качественно | Оптимизировано» — два сегмента вплотную, выбранный подсвечен. */
    private final Button qualityHigh;
    private final Button qualityFast;

    // ===== Зоны Zone Focus =====
    private final ZoneOverlay zones = new ZoneOverlay();
    private final TextInput[] zoneFields = new TextInput[3];
    private final Button[] zoneMinus = new Button[3];
    private final Button[] zonePlus = new Button[3];
    private final Button zoneSplit;
    private final Button zoneUndo;
    private final Button zoneManage;
    /** «Зоны» в углу превью: показать или убрать каркасы — посмотреть на постройку без полос. */
    private final Button zoneFrames;
    private static boolean showZoneFrames = true;
    private final List<Button> zoneRow2 = new ArrayList<>();
    private final int[] draft = { 2, 1, 2 };
    private boolean zonePanel;
    private int zonePanelY;
    private int zoneAxisX;
    private int zonePoll;
    private long undoArmedUntil;
    /** Разбиение файла сейчас: сетка (или {@code null}), есть ли зоны, размещён ли. */
    private int @Nullable [] zoneGrid;
    private boolean zoneSplitDone;
    private boolean zonePlaced;
    private int zoneHover = -1;
    private double pressX;
    private double pressY;
    private boolean pressMoved;

    private PreviewCamera.View view = PreviewCamera.View.ISOMETRIC;
    private @Nullable VoxelSnapshot source;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private int cutLabelX;
    private boolean cutLabelShown;
    private boolean widgetDragging;

    PreviewPane(GuiSchematics screen)
    {
        super(screen);

        this.iso = new Button(Tr.t("preview.iso"), Button.Style.SECONDARY, () -> this.setView(PreviewCamera.View.ISOMETRIC));
        this.qualityHigh = new Button(Tr.t("preview.quality.high"), Button.Style.SECONDARY, () -> this.chooseQuality(true))
                .tooltip(Tr.t("preview.quality.tip"));
        this.qualityFast = new Button(Tr.t("preview.quality.fast"), Button.Style.SECONDARY, () -> this.chooseQuality(false))
                .tooltip(Tr.t("preview.quality.tip"));
        this.top = new Button(Tr.t("preview.top"), Button.Style.SECONDARY, () -> this.setView(PreviewCamera.View.TOP));
        this.front = new Button(Tr.t("preview.front"), Button.Style.SECONDARY, () -> this.setView(PreviewCamera.View.FRONT));
        this.side = new Button(Tr.t("preview.side"), Button.Style.SECONDARY, () -> this.setView(PreviewCamera.View.SIDE));
        this.cut = new Slider(0, 0, 0, this::onCut).tooltip(Tr.t("preview.cut.tip"));
        this.syncViewButtons();

        for (int axis = 0; axis < 3; ++axis)
        {
            final int a = axis;
            // Знак рисуется поверх: в кнопку шириной с высоту строки подпись с отступами кита не влезает
            this.zoneMinus[a] = new Button("", Button.Style.SECONDARY, () -> this.nudgeZones(a, -1)).centered(true);
            this.zonePlus[a] = new Button("", Button.Style.SECONDARY, () -> this.nudgeZones(a, 1)).centered(true);
            this.zoneFields[a] = new TextInput().maxLength(3).centered(true).filter(c -> c >= '0' && c <= '9')
                    .onChange(text -> this.typedZones(a, text))
                    .onSubmit(text -> this.zoneFields[a].setFocused(false))
                    .tooltip(Tr.t("zones.field.tip"));
        }

        this.zoneSplit = new Button("", Button.Style.PRIMARY, this::applyZones).centered(true);
        this.zoneUndo = new Button(Tr.t("zones.undo"), Button.Style.DANGER, this::undoZones).centered(true)
                .tooltip(Tr.t("zones.undo.tip"));
        this.zoneManage = new Button(Tr.t("zones.manage"), Button.Style.SECONDARY, this::manageZones).centered(true)
                .tooltip(Tr.t("zones.manage.tip"));
        this.zoneRow2.addAll(List.of(this.zoneSplit, this.zoneUndo, this.zoneManage));
        this.zoneFrames = new Button(Tr.t("zones.frames"), Button.Style.SECONDARY, () -> {
            showZoneFrames = ! showZoneFrames;
            this.zonePoll = 0;
        }).centered(true).tooltip(Tr.t("zones.frames.tip"));
        this.zoneFrames.setVisible(false);
        this.syncZoneFields(true);
    }

    private List<Button> barButtons()
    {
        return List.of(this.iso, this.top, this.front, this.side);
    }

    /** «Отображение схемы» над превью; «Качественно» нагружает игру — сначала предупредить. */
    private void chooseQuality(boolean wantHigh)
    {
        boolean high = SchematicsConfig.previewQuality() == SchematicsConfig.PreviewQuality.QUALITY;

        if (wantHigh == high)
        {
            return;
        }

        if (! wantHigh)
        {
            SettingsDialog.applyQuality(SchematicsConfig.PreviewQuality.OPTIMIZED);
            return;
        }

        Modal.confirm(this.screen.layers, Tr.t("settings.quality.warn.title"), Tr.t("settings.quality.warn.text"),
                Tr.t("settings.quality.warn.yes"), false, () -> SettingsDialog.applyQuality(SchematicsConfig.PreviewQuality.QUALITY),
                Tr.t("cancel"));
    }

    private void setView(PreviewCamera.View next)
    {
        this.view = next;
        this.widget.setView(next);
        this.syncViewButtons();
    }

    private void syncViewButtons()
    {
        this.iso.selected(this.view == PreviewCamera.View.ISOMETRIC);
        this.top.selected(this.view == PreviewCamera.View.TOP);
        this.front.selected(this.view == PreviewCamera.View.FRONT);
        this.side.selected(this.view == PreviewCamera.View.SIDE);
        boolean high = SchematicsConfig.previewQuality() == SchematicsConfig.PreviewQuality.QUALITY;
        this.qualityHigh.selected(high);
        this.qualityFast.selected(! high);
    }

    private void onCut(int layer)
    {
        // Ползунок на самом верху — без среза: модель целиком, без перестройки секций
        this.widget.setLayerCut(layer >= this.cut.max() ? Integer.MAX_VALUE : layer);
    }

    /** Сменился выбор: старое превью убираем сразу, новое придёт с готовым снимком. */
    void onSelect(@Nullable SchematicEntry entry)
    {
        boolean wasOpen = this.zonePanel;
        this.zonePanel = false;
        this.zoneFrames.setVisible(false);
        this.widget.setBuildPlacement(null);
        this.zonePoll = 0;
        this.zonePlaced = false;

        for (TextInput f : this.zoneFields)
        {
            f.setFocused(false);
        }

        if (wasOpen)
        {
            this.setBounds(this.x, this.y, this.w, this.h);
        }

        this.zoneHover = -1;
        this.zones.set(null, -1, null, false);
        this.source = null;
        this.widget.setSource(null, NO_SOURCE);
        this.cut.setRange(0, 0, 0);
        this.widget.setLayerCut(Integer.MAX_VALUE);
    }

    /** Снимок готов. */
    void show(VoxelSnapshot snapshot, Object key)
    {
        this.source = snapshot;
        this.widget.setSource(snapshot, key);
        this.widget.setView(this.view);
        int topLayer = Math.max(0, snapshot.sizeY() - 1);
        this.cut.setRange(0, topLayer, topLayer);
        this.widget.setLayerCut(Integer.MAX_VALUE);
        // Подпись среза зависит от высоты схемы — раскладка полоски заново
        this.setBounds(this.x, this.y, this.w, this.h);
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        Font font = Kit.font();
        int pad = Ui.PAD_IN;
        int barY = y + pad;
        int bx = x + pad;

        List<Button> bar = this.barButtons();

        for (Button button : bar)
        {
            int bw = button.preferredWidth(font);
            button.setBounds(bx, barY, bw, Ui.CTRL_H);
            bx += bw + Ui.PAD_MICRO;
        }

        // Не влезли кнопки и ползунок — кнопки ужимаются (подписи с «…», полные — в подсказке)
        int right = x + w - pad;
        int labelW = this.cutLabelWidth(font) + Ui.PAD_IN;
        int sliderW = Math.min(SLIDER_W, Math.max(0, right - bx - Ui.PAD_REL - labelW));

        if (sliderW < SLIDER_MIN_W)
        {
            int buttonsRoom = Math.max(0, w - pad * 2 - SLIDER_MIN_W - Ui.PAD_REL - Ui.PAD_MICRO * (bar.size() - 1));
            int each = buttonsRoom / bar.size();
            bx = x + pad;

            for (Button button : bar)
            {
                button.setBounds(bx, barY, each, Ui.CTRL_H);
                bx += each + Ui.PAD_MICRO;
            }

            sliderW = Math.max(0, right - bx - Ui.PAD_REL);
            this.cutLabelShown = false;
        }
        else
        {
            this.cutLabelShown = true;
        }

        this.cut.setBounds(right - sliderW, barY, sliderW, Ui.CTRL_H);
        this.cutLabelX = right - sliderW - labelW;

        int statusH = Ui.STATUS_H;
        this.viewX = x + pad;
        this.viewY = barY + Ui.CTRL_H + pad;
        this.viewW = Math.max(0, w - pad * 2);
        int rows = this.zonePlaced ? 4 : 3;
        int panelH = this.zonePanelShown() ? Ui.CTRL_H * (rows - 1) + Ui.PAD_IN * rows : 0;
        this.viewH = Math.max(0, y + h - statusH - this.viewY - panelH);
        this.zonePanelY = this.viewY + this.viewH + Ui.PAD_IN;
        // «Качественно | Оптимизировано» — парой в левом верхнем углу превью (в строке видов не помещалась бы целиком)
        int qh = this.qualityHigh.preferredWidth(font);
        int qf = this.qualityFast.preferredWidth(font);
        this.qualityHigh.setBounds(this.viewX + Ui.PAD_IN, this.viewY + Ui.PAD_IN, qh, Ui.CTRL_H);
        this.qualityFast.setBounds(this.viewX + Ui.PAD_IN + qh, this.viewY + Ui.PAD_IN, qf, Ui.CTRL_H);
        int fw = this.zoneFrames.preferredWidth(font);
        this.zoneFrames.setBounds(this.viewX + this.viewW - fw - Ui.PAD_IN, this.viewY + Ui.PAD_IN, fw, Ui.CTRL_H);
        this.layoutZonePanel(font);
    }

    private boolean zonePanelShown()
    {
        return this.zonePanel && ZoneFocusFiles.available() && this.screen.selected() != null;
    }

    /**
     * Панель разбиения под моделью: оси «X [−][ 2 ][+]  Y …  Z …»; под ними пополам «Разбить на N зон» и
     * «Отменить разбиение»; схема размещена — ещё строка «Управление зонами…» во всю ширину.
     */
    private void layoutZonePanel(Font font)
    {
        int y1 = this.zonePanelY;
        int x = this.viewX;
        int axisW = font.width("X") + Ui.PAD_IN + Ui.CTRL_H * 2 + ZONE_FIELD_W + Ui.PAD_MICRO * 2;
        int gap = Math.max(Ui.PAD_REL, Math.min(Ui.PAD_GROUP, (this.viewW - axisW * 3) / 3));
        this.zoneAxisX = x;

        for (int axis = 0; axis < 3; ++axis)
        {
            int ax = x + axis * (axisW + gap) + font.width("X") + Ui.PAD_IN;
            this.zoneMinus[axis].setBounds(ax, y1, Ui.CTRL_H, Ui.CTRL_H);
            this.zoneFields[axis].setBounds(ax + Ui.CTRL_H + Ui.PAD_MICRO, y1, ZONE_FIELD_W, Ui.CTRL_H);
            this.zonePlus[axis].setBounds(ax + Ui.CTRL_H + Ui.PAD_MICRO * 2 + ZONE_FIELD_W, y1, Ui.CTRL_H, Ui.CTRL_H);
        }

        // «Разбить» и «Отменить разбиение» делят строку по длине подписей — обе видны целиком, если влезают
        int y2 = y1 + Ui.CTRL_H + Ui.PAD_IN;
        int room = this.viewW - Ui.PAD_IN;
        int splitPref = Math.max(this.zoneSplit.preferredWidth(font), font.width(Tr.f("zones.split", 999)) + Ui.PAD_REL * 2);
        int undoPref = Math.max(this.zoneUndo.preferredWidth(font), font.width(Tr.t("zones.undo_confirm")) + Ui.PAD_REL * 2);
        int splitW = Math.max(room / 3, Math.min(room - undoPref, room * splitPref / Math.max(1, splitPref + undoPref)));
        this.zoneSplit.setBounds(x, y2, splitW, Ui.CTRL_H);
        this.zoneUndo.setBounds(x + splitW + Ui.PAD_IN, y2, room - splitW, Ui.CTRL_H);
        this.zoneManage.setBounds(x, y2 + Ui.CTRL_H + Ui.PAD_IN, this.viewW, Ui.CTRL_H);
        this.zoneManage.setVisible(this.zonePlaced);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        boolean hasSelection = this.screen.selected() != null;
        boolean ready = this.source != null;

        this.iso.setEnabled(ready);
        this.top.setEnabled(ready);
        this.front.setEnabled(ready);
        this.side.setEnabled(ready);
        this.cut.setEnabled(ready && this.cut.max() > 0);

        for (Button button : this.barButtons())
        {
            button.render(ctx, mouseX, mouseY);
        }

        if (this.cutLabelShown)
        {
            // Подпись прижата к ползунку: место под неё — по самому длинному «Срез 25/25»
            String label = this.cutLabel();
            Paint.midText(ctx, font, label, Math.max(this.cutLabelX, this.cut.x() - Ui.PAD_IN - font.width(label)),
                    this.cut.y(), this.cut.h(), this.cut.enabled() ? Ui.C_TEXT_2 : Ui.C_TEXT_OFF, false);
        }

        this.cut.render(ctx, mouseX, mouseY);

        Paint.round(ctx, this.viewX, this.viewY, this.viewW, this.viewH, Ui.R_PLATE, Theme.ROW_DEEP);

        if (! hasSelection)
        {
            this.centerText(ctx, font, Tr.t("preview.empty"), Ui.C_TEXT_3);
        }
        else if (ready)
        {
            this.widget.render(ctx, this.viewX, this.viewY, this.viewW, this.viewH, mouseX, mouseY);
            this.renderZones(ctx, mouseX, mouseY);

            // Выбор качества меняют и окно подтверждения, и настройки — подсветка по текущему значению каждый кадр
            boolean high = SchematicsConfig.previewQuality() == SchematicsConfig.PreviewQuality.QUALITY;
            this.qualityHigh.selected(high);
            this.qualityFast.selected(! high);

            // Подложка, чтобы пара читалась поверх модели
            Paint.round(ctx, this.qualityHigh.x() - 1, this.qualityHigh.y() - 1,
                    this.qualityHigh.w() + this.qualityFast.w() + 2, Ui.CTRL_H + 2, Ui.R_CTRL + 1, Ui.alpha(Ui.C_PANEL, 0.85F));
            this.qualityHigh.render(ctx, mouseX, mouseY);
            this.qualityFast.render(ctx, mouseX, mouseY);

            if (this.zoneFrames.visible())
            {
                this.zoneFrames.selected(showZoneFrames);
                this.zoneFrames.render(ctx, mouseX, mouseY);
            }
        }

        if (this.zonePanelShown())
        {
            this.renderZonePanel(ctx, font, mouseX, mouseY);
        }
        else if (this.screen.snapshotPending())
        {
            this.centerText(ctx, font, Tr.t("preview.loading"), Ui.C_TEXT_2);
        }

        this.drawStatus(ctx, font, hasSelection, mouseX, mouseY);
    }

    /** «Срез» без среза; срез включён — какой слой верхний из скольких («Срез 12/25»). */
    private String cutLabel()
    {
        int value = this.cut.value();
        int max = this.cut.max();

        return this.source != null && max > 0 && value < max ? Tr.f("preview.cut.layer", value + 1, max + 1) : Tr.t("preview.cut");
    }

    /** Место под подпись среза — по самому длинному варианту для этой схемы, чтобы ползунок не прыгал. */
    private int cutLabelWidth(Font font)
    {
        int layers = this.source != null ? Math.max(1, this.source.sizeY()) : 1;

        return Math.max(font.width(Tr.t("preview.cut")), font.width(Tr.f("preview.cut.layer", layers, layers)));
    }

    private void centerText(GuiContext ctx, Font font, String text, int color)
    {
        String shown = Paint.fit(font, text, this.viewW - Ui.PAD_GROUP * 2);
        Paint.midText(ctx, font, shown, this.viewX + (this.viewW - font.width(shown)) / 2, this.viewY, this.viewH,
                color, false);
    }

    private void drawStatus(GuiContext ctx, Font font, boolean hasSelection, int mouseX, int mouseY)
    {
        String text;
        int color = Ui.C_TEXT_3;
        Throwable error = this.screen.snapshotError();

        if (! hasSelection)
        {
            text = "";
        }
        else if (error != null)
        {
            text = Tr.f("preview.failed", Tr.reason(error));
            color = Ui.C_DANGER;
        }
        else if (this.source == null)
        {
            text = Tr.t("preview.loading");
        }
        else if (this.zoneHover >= 0 && this.zoneHover < this.zoneBoxes.length)
        {
            int[] b = this.zoneBoxes[this.zoneHover];
            text = Tr.f("zones.hover", this.zoneHover + 1, b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1)
                    + (this.zonePlaced && ! this.zoneDraft ? "  ·  " + Tr.t("zones.hover.click") : "");
            color = ZoneOverlay.EDGE;
        }
        else
        {
            String status = this.widget.status();
            int built = this.widget.builtPercent();
            text = status.isEmpty()
                    ? Tr.f("files.meta.size", this.source.sizeX(), this.source.sizeY(), this.source.sizeZ())
                            + (built >= 0 ? "  ·  " + Tr.f("zones.built", built) : "")
                    : status;
        }

        if (text.isEmpty())
        {
            return;
        }

        int y = this.y + this.h - Ui.STATUS_H;
        String shown = Paint.fit(font, text, this.w - Ui.PAD_IN * 2);
        Paint.midText(ctx, font, shown, this.x + Ui.PAD_IN, y, Ui.STATUS_H, color, false);

        boolean hintShown = this.drawControlsHint(ctx, font, font.width(shown), y, mouseX, mouseY);

        // Над строкой статуса — полный текст и (если в строку не влезло) кнопки мыши; над моделью ничего не всплывает
        if (Paint.hit(mouseX, mouseY, this.x, y, this.w, Ui.STATUS_H) && (! shown.equals(text) || ! hintShown))
        {
            Tooltip.offerLines("preview#status", this.source != null && ! hintShown
                    ? List.of(text, Tr.t("preview.controls.tip")) : List.of(text), Ui.C_TEXT);
        }
    }

    /**
     * Мышь над моделью — справа в строке статуса, приглушённо, какие кнопки что делают. Всплывающей
     * подсказкой это закрывало бы саму модель и соседнюю карточку, пока курсор стоит на превью.
     */
    private boolean drawControlsHint(GuiContext ctx, Font font, int statusW, int y, int mouseX, int mouseY)
    {
        int room = this.w - Ui.PAD_IN * 2 - statusW - Ui.PAD_GROUP;

        for (String key : List.of("preview.controls.tip", "preview.controls.short"))
        {
            String hint = Tr.t(key);
            int hintW = font.width(hint);

            if (hintW <= room)
            {
                if (this.source != null && ! this.widgetDragging && this.inView(mouseX, mouseY))
                {
                    Paint.midText(ctx, font, hint, this.x + this.w - Ui.PAD_IN - hintW, y, Ui.STATUS_H, Ui.C_TEXT_3, false);
                }

                return true;
            }
        }

        // Не влезло рядом со статусом (узкое окно, «Упрощённый вид…») — подсказка у самой строки статуса
        return false;
    }

    private boolean inView(double mouseX, double mouseY)
    {
        return this.source != null && mouseX >= this.viewX && mouseX < this.viewX + this.viewW
                && mouseY >= this.viewY && mouseY < this.viewY + this.viewH;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        for (TextInput f : this.zoneFields)
        {
            if (f.focused() && ! f.contains(click.x(), click.y()))
            {
                f.setFocused(false);
                this.syncZoneFields(true);
            }
        }

        for (Button button : this.barButtons())
        {
            if (button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        if (this.zonePanelShown())
        {
            for (Widget w : this.zonePanelWidgets())
            {
                if (w.visible() && w.mouseClicked(click, doubleClick))
                {
                    return true;
                }
            }
        }

        if (this.zoneFrames.visible() && this.source != null && this.zoneFrames.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (this.source != null && (this.qualityHigh.mouseClicked(click, doubleClick) || this.qualityFast.mouseClicked(click, doubleClick)))
        {
            return true;
        }

        if (this.cut.mouseClicked(click, doubleClick))
        {
            return true;
        }

        if (this.inView(click.x(), click.y()) && this.widget.mouseClicked(click.x(), click.y(), click.button()))
        {
            this.widgetDragging = true;
            this.pressX = click.x();
            this.pressY = click.y();
            this.pressMoved = false;
            return true;
        }

        return true;
    }

    private List<Widget> zonePanelWidgets()
    {
        List<Widget> out = new ArrayList<>();

        for (int axis = 0; axis < 3; ++axis)
        {
            out.add(this.zoneMinus[axis]);
            out.add(this.zoneFields[axis]);
            out.add(this.zonePlus[axis]);
        }

        out.addAll(this.zoneRow2);
        return out;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        if (this.cut.mouseDragged(click, dx, dy))
        {
            return true;
        }

        if (this.widgetDragging && Math.abs(click.x() - this.pressX) + Math.abs(click.y() - this.pressY) > CLICK_SLOP)
        {
            this.pressMoved = true;
        }

        return this.widgetDragging && this.widget.mouseDragged(click.x(), click.y(), click.button(), dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : this.barButtons())
        {
            button.mouseReleased(click);
        }

        if (this.zonePanelShown())
        {
            for (Widget w : this.zonePanelWidgets())
            {
                w.mouseReleased(click);
            }
        }

        this.zoneFrames.mouseReleased(click);
        this.qualityHigh.mouseReleased(click);
        this.qualityFast.mouseReleased(click);
        boolean handled = this.cut.mouseReleased(click);

        if (this.widgetDragging)
        {
            this.widgetDragging = false;
            handled |= this.widget.mouseReleased(click.x(), click.y(), click.button());

            // Щелчок без протяжки по зоне размещённой схемы — она становится активной (как в мире)
            if (! this.pressMoved && Kit.left(click) && this.zonePlaced && ! this.zoneDraft && showZoneFrames)
            {
                int zone = this.zones.pick(this.widget, click.x(), click.y());
                SchematicEntry entry = this.screen.selected();

                if (zone >= 0 && entry != null)
                {
                    Vanilla.click();
                    ZoneFocusFiles.setActive(entry.path(), zone);
                    this.zonePoll = 0;
                }
            }
        }

        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        if (this.zonePanelShown() && amount != 0.0)
        {
            for (int axis = 0; axis < 3; ++axis)
            {
                TextInput f = this.zoneFields[axis];

                if (mouseY >= f.y() && mouseY < f.y() + f.h() && mouseX >= this.zoneMinus[axis].x()
                        && mouseX < this.zonePlus[axis].x() + this.zonePlus[axis].w())
                {
                    this.nudgeZones(axis, (amount > 0 ? 1 : -1) * (net.minecraft.client.Minecraft.getInstance().hasShiftDown() ? 10 : 1));
                    return true;
                }
            }
        }

        if (this.cut.mouseScrolled(mouseX, mouseY, amount))
        {
            return true;
        }

        return this.inView(mouseX, mouseY) && this.widget.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        for (int axis = 0; axis < 3; ++axis)
        {
            TextInput f = this.zoneFields[axis];

            if (f.focused())
            {
                if (key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_UP || key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN)
                {
                    this.nudgeZones(axis, (key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_UP ? 1 : -1) * (net.minecraft.client.Minecraft.getInstance().hasShiftDown() ? 10 : 1));
                    return true;
                }

                if (key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN)
                {
                    f.setFocused(false);
                    this.applyZones();
                    return true;
                }

                return f.keyPressed(key) || true;
            }
        }

        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event)
    {
        for (TextInput f : this.zoneFields)
        {
            if (f.focused())
            {
                return f.charTyped(event);
            }
        }

        return false;
    }

    @Override
    @Nullable TextInput focusedInput()
    {
        for (TextInput f : this.zoneFields)
        {
            if (f.focused())
            {
                return f;
            }
        }

        return null;
    }

    // ===== Зоны =====

    private int[][] zoneBoxes = new int[0][];
    private boolean zoneDraft;

    /** Пункт меню файла «Разбить на зоны…» / «Управление зонами…» для неразмещённой схемы. */
    void openZonePanel()
    {
        this.zonePanel = true;
        this.zonePoll = 0;
        SchematicEntry entry = this.screen.selected();
        int[] grid = entry != null ? ZoneFocusFiles.grid(entry.path()) : null;

        if (grid != null)
        {
            System.arraycopy(grid, 0, this.draft, 0, 3);
        }

        this.syncZoneFields(true);
        this.setBounds(this.x, this.y, this.w, this.h);
    }

    boolean zonePanelOpen()
    {
        return this.zonePanelShown();
    }

    /** Габарит схемы в блоках: из сведений файла, пока их нет — по снимку. */
    private int[] schematicSize()
    {
        SchematicInfo info = this.screen.selectedInfo();

        if (info != null && info.sizeX() > 0 && info.sizeY() > 0 && info.sizeZ() > 0)
        {
            return new int[] { info.sizeX(), info.sizeY(), info.sizeZ() };
        }

        VoxelSnapshot s = this.source;
        return s == null ? new int[] { 1, 1, 1 }
                : new int[] { s.sizeX() * s.scale(), s.sizeY() * s.scale(), s.sizeZ() * s.scale() };
    }

    private void nudgeZones(int axis, int delta)
    {
        int[] size = this.schematicSize();
        this.draft[axis] = Math.max(1, Math.min(Math.min(999, size[axis]), this.draft[axis] + delta));
        this.syncZoneFields(true);
        this.zonePoll = 0;
    }

    private void typedZones(int axis, String text)
    {
        if (! text.isEmpty())
        {
            try
            {
                int[] size = this.schematicSize();
                this.draft[axis] = Math.max(1, Math.min(Math.min(999, size[axis]), Integer.parseInt(text)));
                this.zonePoll = 0;
            }
            catch (NumberFormatException ignored)
            {
            }
        }
    }

    private void syncZoneFields(boolean force)
    {
        for (int axis = 0; axis < 3; ++axis)
        {
            TextInput f = this.zoneFields[axis];

            if (f != null && (force || ! f.focused()))
            {
                String v = String.valueOf(this.draft[axis]);

                if (! v.equals(f.text()))
                {
                    f.setText(v);
                }
            }
        }
    }

    private boolean draftMatches()
    {
        int[] g = this.zoneGrid;
        return g != null && g[0] == this.draft[0] && g[1] == this.draft[1] && g[2] == this.draft[2];
    }

    private void applyZones()
    {
        SchematicEntry entry = this.screen.selected();

        if (entry == null)
        {
            return;
        }

        this.syncZoneFields(true);
        ZoneFocusFiles.split(entry.path(), this.draft[0], this.draft[1], this.draft[2]);
        this.zonePoll = 0;
    }

    private void undoZones()
    {
        SchematicEntry entry = this.screen.selected();
        long now = net.minecraft.util.Util.getMillis();

        if (entry == null)
        {
            return;
        }

        if (now > this.undoArmedUntil)
        {
            this.undoArmedUntil = now + CONFIRM_MS;
            return;
        }

        this.undoArmedUntil = 0L;
        ZoneFocusFiles.clear(entry.path());
        this.zonePoll = 0;
    }

    private void manageZones()
    {
        SchematicEntry entry = this.screen.selected();

        if (entry != null)
        {
            ZoneFocusFiles.openManager(entry.path());
        }
    }

    /** Раз в несколько кадров — зоны файла у Zone Focus; панель открыта и числа другие — черновик. */
    private void pollZones()
    {
        SchematicEntry entry = this.screen.selected();

        if (entry == null || ! ZoneFocusFiles.available())
        {
            this.zoneBoxes = new int[0][];
            this.zones.set(null, -1, null, false);
            return;
        }

        if (--this.zonePoll > 0)
        {
            return;
        }

        this.zonePoll = ZONE_POLL_FRAMES;
        Path file = entry.path();
        // Схема размещена — модель по готовности «блок в блок»: построенное как есть, остальное «призраком»
        this.widget.setBuildPlacement(this.placementOf(file));
        int[] size = this.schematicSize();
        int[][] applied = ZoneFocusFiles.boxes(file, size[0], size[1], size[2]);
        this.zoneGrid = ZoneFocusFiles.grid(file);
        this.zoneSplitDone = applied != null && applied.length > 0;
        boolean placed = ZoneFocusFiles.placed(file);

        if (placed != this.zonePlaced)
        {
            // Строка «Управление зонами…» появилась или ушла — панель другой высоты
            this.zonePlaced = placed;
            this.setBounds(this.x, this.y, this.w, this.h);
        }

        this.zoneFrames.setVisible(this.zonePanelShown() || this.zoneSplitDone);

        // Каркасы убраны — превью показывает только постройку
        if (! showZoneFrames)
        {
            this.zoneBoxes = new int[0][];
            this.zoneDraft = false;
            this.zones.set(null, -1, null, false);
            return;
        }

        this.zoneDraft = this.zonePanelShown() && ! this.draftMatches();

        if (this.zoneDraft)
        {
            this.zoneBoxes = com.aell.layerlist.schematics.preview.ZoneGrid.local(size[0], size[1], size[2],
                    this.draft[0], this.draft[1], this.draft[2]);
            this.zones.set(this.zoneBoxes, -1, null, true);
        }
        else
        {
            // Размещение строят — у каждой зоны её готовность (скан Zone Focus запускается сам)
            this.zoneBoxes = applied != null ? applied : new int[0][];
            int[] percents = this.zonePlaced ? ZoneFocusFiles.progress(file) : null;
            int[] tones = null;

            if (percents != null)
            {
                tones = new int[percents.length];

                for (int i = 0; i < percents.length; ++i)
                {
                    tones[i] = ZoneOverlay.toneOf(percents[i]);
                }
            }

            this.zones.set(applied, ZoneFocusFiles.active(file), tones, percents, false);
        }
    }

    /** Включённое размещение этого файла (первое) или {@code null}. */
    private fi.dy.masa.litematica.schematic.placement.@Nullable SchematicPlacement placementOf(Path file)
    {
        for (var placement : com.aell.layerlist.schematics.place.PlacementOps.of(file))
        {
            if (placement.isEnabled())
            {
                return placement;
            }
        }

        return null;
    }

    private void renderZones(GuiContext ctx, int mouseX, int mouseY)
    {
        this.pollZones();
        this.zoneHover = ! this.widgetDragging && this.inView(mouseX, mouseY) && this.zones.count() <= 400
                ? this.zones.pick(this.widget, mouseX, mouseY) : -1;
        this.zones.setHovered(this.zoneHover);
        this.zones.draw(ctx, this.widget);
    }

    private void renderZonePanel(GuiContext ctx, Font font, int mouseX, int mouseY)
    {
        this.syncZoneFields(false);
        String[] axes = { "X", "Y", "Z" };

        for (int axis = 0; axis < 3; ++axis)
        {
            Paint.midText(ctx, font, axes[axis], this.zoneMinus[axis].x() - Ui.PAD_IN - font.width(axes[axis]),
                    this.zonePanelY, Ui.CTRL_H, Ui.C_ACCENT, false);
        }

        int total = this.draft[0] * this.draft[1] * this.draft[2];
        boolean matches = this.draftMatches();
        this.zoneSplit.label(matches ? Tr.t("zones.split_done") : Tr.f("zones.split", total));
        this.zoneSplit.setEnabled(! matches);
        boolean armed = net.minecraft.util.Util.getMillis() <= this.undoArmedUntil;
        this.zoneUndo.label(armed ? Tr.t("zones.undo_confirm") : Tr.t("zones.undo"));
        this.zoneUndo.setEnabled(this.zoneSplitDone);
        for (Widget w : this.zonePanelWidgets())
        {
            if (w.visible())
            {
                w.render(ctx, mouseX, mouseY);
            }
        }

        for (int axis = 0; axis < 3; ++axis)
        {
            for (Button b : new Button[] { this.zoneMinus[axis], this.zonePlus[axis] })
            {
                String sign = b == this.zoneMinus[axis] ? "−" : "+";
                int color = b.contains(mouseX, mouseY) ? Ui.C_TEXT : Ui.C_TEXT_2;
                Paint.midText(ctx, font, sign, b.x() + (b.w() - font.width(sign) + 1) / 2, b.y(), b.h(), color, false);
            }
        }
    }

    @Override
    void release()
    {
        // Модель и видеопамять превью — до возвращения на экран; источник выставится заново
        this.widget.close();
        this.source = null;
    }
}
