package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicInfo;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.IconButton;
import com.aell.layerlist.ui.kit.ImageTexture;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Правая панель «Библиотеки» — карточка выбранной схемы: название, автор, описание, встроенное превью
 * Litematica (если есть в файле), сведения (файл — ссылка на папку, формат, размер, объём, блоки,
 * регионы, даты, версия данных), действия («Разместить здесь» — зелёная главная, «Разместить…»,
 * «Материалы», «Выгрузить ▾») и «Размещения этой схемы» (вкл/выкл, «Двигать», «Удалить» с
 * подтверждением). Не влезает по высоте — прокручивается колесом.
 */
@Environment(EnvType.CLIENT)
final class InfoPane extends Pane
{
    private static final int PREVIEW = 64;
    private static final int LINE = Ui.TIP_LINE + 1;
    private static final int PLACEMENT_ROW_H = 26;
    private static final int DESCRIPTION_LINES = 3;
    private static final long PLACEMENTS_REFRESH_MS = 500L;

    private static final Identifier PREVIEW_ID =
            Identifier.fromNamespaceAndPath("layerlist", "schematics/card_preview");

    private final ImageTexture previewImage = new ImageTexture(PREVIEW_ID);
    private final Button placeHere;
    private final Button placeAt;
    private final Button materials;
    private final Button export;
    /** «Разбить на зоны» / «Управление зонами…» — только с Zone Focus 0.0.8+. */
    private final Button zones;
    /** «Разместить и поделиться» — только с Syncmatica на сервере. */
    private final Button syncShare;
    /** Цвет зон — тот же циан, что рамки зон в мире и на превью. */
    static final int ZONED = com.aell.layerlist.schematics.preview.ZoneOverlay.ACTIVE;
    private boolean zonesSplit;
    private long zonesPolledAt;
    private @Nullable SchematicEntry zonesFor;
    private final List<PlacementRow> rows = new ArrayList<>();

    private int scroll;
    private int contentH;
    private boolean footerHasMain;
    private long placementsAt;
    private @Nullable SchematicEntry rowsFor;
    private int fileLinkX;
    private int fileLinkY;
    private int fileLinkW;

    /** Строка «Размещения этой схемы»: тумблер с именем, «Двигать», крестик. */
    private final class PlacementRow
    {
        private final SchematicPlacement placement;
        private final Toggle enabled;
        private final Button move;
        private final Button delete;

        private PlacementRow(SchematicPlacement placement)
        {
            this.placement = placement;
            this.enabled = new Toggle(placement.getName(), placement::isEnabled,
                    value -> InfoPane.this.screen.toggleEnabled(placement)).tooltip(Tr.t("info.enabled.tip"));
            this.move = new Button(Tr.t("info.move"), Button.Style.SECONDARY,
                    () -> InfoPane.this.screen.edit(placement, false, false)).tooltip(Tr.t("info.move.tip"));
            this.delete = new IconButton(Glyphs.CROSS, Tr.t("info.delete.tip"), Button.Style.DANGER,
                    () -> InfoPane.this.screen.confirmRemove(placement));
        }
    }

    InfoPane(GuiSchematics screen)
    {
        super(screen);

        this.placeHere = new Button(Tr.t("info.place_here"), Button.Style.PRIMARY, () -> this.screen.placeSelected(true))
                .accent(Theme.GREEN).tooltip(Tr.t("info.place_here.tip"));
        this.placeAt = new Button(Tr.t("info.place_at"), Button.Style.SECONDARY, () -> this.screen.placeSelected(false))
                .tooltip(Tr.t("info.place_at.tip"));
        this.materials = new Button(Tr.t("info.materials"), Button.Style.SECONDARY,
                () -> this.screen.showMaterials(this.screen.selected(), null)).tooltip(Tr.t("info.materials.tip"));
        this.export = new Button(Tr.t("info.export"), Button.Style.SECONDARY, this::openExportMenu)
                .trailing(Glyphs.CHEVRON_DOWN).tooltip(Tr.t("info.export.tip"));
        // Зоны — тем же цианом, что зоны в мире: разбить схему прямо в превью или перейти к управлению
        this.zones = new Button(Tr.t("menu.zones_split"), Button.Style.PRIMARY, this::zonesAction)
                .accent(ZONED);
        this.syncShare = new Button(Tr.t("sync.place_share"), Button.Style.PRIMARY, () ->
        {
            SchematicEntry entry = this.screen.selected();

            if (entry != null)
            {
                this.screen.placeAndShare(entry);
            }
        }).accent(PlacementsPane.SYNC).tooltip(Tr.t("sync.place_share.tip"));
    }

    /** «Выгрузить ▾»: меню форматов под кнопкой; выбор открывает окно выгрузки с этим форматом. */
    private void openExportMenu()
    {
        ExportDialog.menu(this.screen, this.export, this.screen.selected());
    }

    /**
     * Карточка под превью (узкое окно): «Разместить здесь» и «Материалы» уже в подвале экрана — в карточке
     * остаются только «Разместить…» и «Выгрузить ▾», без второй зелёной кнопки рядом с первой.
     */
    void setFooterHasMain(boolean footerHasMain)
    {
        this.footerHasMain = footerHasMain;
    }

    void onSelect(@Nullable SchematicEntry entry)
    {
        this.scroll = 0;
        this.rows.clear();
        this.rowsFor = null;
        this.placementsAt = 0L;
        this.previewImage.release();
    }

    private void refreshPlacements(SchematicEntry entry)
    {
        long now = Util.getMillis();

        if (entry.equals(this.rowsFor) && now - this.placementsAt < PLACEMENTS_REFRESH_MS)
        {
            return;
        }

        this.rowsFor = entry;
        this.placementsAt = now;

        List<SchematicPlacement> current;

        try
        {
            current = PlacementOps.of(entry.path());
        }
        catch (RuntimeException e)
        {
            current = List.of();
        }

        // Строки тех же размещений сохраняем — у кнопок не сбрасывается наведение
        List<PlacementRow> next = new ArrayList<>();

        for (SchematicPlacement placement : current)
        {
            PlacementRow kept = null;

            for (PlacementRow row : this.rows)
            {
                if (row.placement == placement)
                {
                    kept = row;
                    break;
                }
            }

            next.add(kept != null ? kept : new PlacementRow(placement));
        }

        this.rows.clear();
        this.rows.addAll(next);
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        SchematicEntry entry = this.screen.selected();
        boolean inside = this.contains(mouseX, mouseY);
        int mx = inside ? mouseX : -1;
        int my = inside ? mouseY : -1;

        if (entry == null)
        {
            // Не та же фраза, что посередине превью: две одинаковые подсказки рядом читаются как сбой
            String hint = Paint.fit(font, Tr.t("info.empty"), this.w - Ui.PAD_GROUP * 2);
            Paint.midText(ctx, font, hint, this.x + (this.w - font.width(hint)) / 2, this.y, Math.min(this.h, Ui.ROW_H * 4),
                    Ui.C_TEXT_3, false);
            this.contentH = 0;
            return;
        }

        this.refreshPlacements(entry);

        ctx.enableScissor(this.x, this.y, this.x + this.w, this.y + this.h);
        this.contentH = this.drawCard(ctx, font, entry, mx, my) + this.scroll - this.y;
        ctx.disableScissor();

        int max = Math.max(0, this.contentH - this.h);
        this.scroll = Ui.clamp(this.scroll, 0, max);

        if (max > 0)
        {
            int sx = this.x + this.w - Ui.SCROLL_W - Ui.PAD_MICRO;
            int thumb = Math.max(Ui.SCROLL_THUMB_MIN, this.h * this.h / Math.max(1, this.contentH));
            int thumbY = this.y + (this.h - thumb) * this.scroll / max;

            // Ванильная полоса прокрутки: шире на 2 px, правый край на месте
            if (Style.vanilla())
            {
                Vanilla.scrollerTrack(ctx, sx - 2, this.y, Vanilla.SCROLLER_W, this.h);
                Vanilla.scrollerThumb(ctx, sx - 2, thumbY, Vanilla.SCROLLER_W, thumb);
                return;
            }

            Paint.round(ctx, sx, this.y, Ui.SCROLL_W, this.h, Ui.R_BAR, Ui.C_TRACK);
            Paint.round(ctx, sx, thumbY, Ui.SCROLL_W, thumb, Ui.R_BAR, Ui.C_THUMB);
        }
    }

    /** Карточка сверху вниз; возвращает нижний край содержимого (в экранных координатах). */
    private int drawCard(GuiContext ctx, Font font, SchematicEntry entry, int mouseX, int mouseY)
    {
        int pad = Ui.PAD_GROUP;
        int left = this.x + pad;
        int inner = Math.max(0, this.w - pad * 2 - Ui.SCROLL_W);
        int cy = this.y + pad - this.scroll;

        SchematicInfo info = this.screen.selectedInfo();
        Throwable infoError = this.screen.selectedInfoError();
        boolean ok = info != null && info.ok();

        // Шапка карточки: превью Litematica слева (если есть), название и автор справа от него
        int headX = left;
        int headTop = cy;
        Identifier image = ok && info.hasPreview() ? this.previewImage.get(entry, info.previewArgb(), info.previewSide()) : null;

        if (image != null && inner >= PREVIEW * 2)
        {
            Paint.texture(ctx, image, left, cy, PREVIEW);
            Paint.border(ctx, left - Ui.BORDER, cy - Ui.BORDER, PREVIEW + Ui.BORDER * 2, PREVIEW + Ui.BORDER * 2,
                    Ui.C_DIVIDER);
            headX += PREVIEW + Ui.PAD_REL;
        }

        int headW = left + inner - headX;
        String name = ok && meaningfulName(info.name()) ? info.name() : entry.name();
        List<String> nameLines = Kit.wrap(font, name, headW);
        int ty = cy;

        for (int i = 0; i < Math.min(2, nameLines.size()); ++i)
        {
            String line = i == 1 && nameLines.size() > 2 ? Paint.fit(font, nameLines.get(1) + "…", headW) : nameLines.get(i);
            Paint.midText(ctx, font, line, headX, ty, LINE, Ui.C_TEXT, true);
            ty += LINE;
        }

        if (nameLines.size() > 2 && Paint.hit(mouseX, mouseY, headX, cy, headW, LINE * 2))
        {
            Tooltip.offer("info#name", name);
        }

        if (ok && ! info.author().isBlank())
        {
            String author = Paint.fit(font, Tr.f("info.by", info.author()), headW);
            Paint.midText(ctx, font, author, headX, ty, LINE, Ui.C_TEXT_2, false);
            ty += LINE;
        }

        cy = Math.max(ty, image != null && headX > left ? headTop + PREVIEW : ty) + Ui.PAD_REL;

        if (ok && ! info.description().isBlank())
        {
            List<String> lines = Kit.wrap(font, info.description(), inner);
            int top = cy;

            for (int i = 0; i < Math.min(DESCRIPTION_LINES, lines.size()); ++i)
            {
                String line = i == DESCRIPTION_LINES - 1 && lines.size() > DESCRIPTION_LINES
                        ? Paint.fit(font, lines.get(i) + "…", inner) : lines.get(i);
                Paint.midText(ctx, font, line, left, cy, LINE, Ui.C_TEXT_2, false);
                cy += LINE;
            }

            if (lines.size() > DESCRIPTION_LINES && Paint.hit(mouseX, mouseY, left, top, inner, cy - top))
            {
                Tooltip.offer("info#description", info.description());
            }

            cy += Ui.PAD_REL;
        }

        cy = this.drawFacts(ctx, font, entry, info, left, cy, inner, mouseX, mouseY);

        if (info == null && infoError == null)
        {
            Paint.midText(ctx, font, Paint.fit(font, Tr.t("info.loading"), inner), left, cy, LINE, Ui.C_TEXT_3, false);
            cy += LINE;
        }

        String error = info != null && ! info.ok() ? info.error() : infoError != null ? Tr.reason(infoError) : null;

        if (error != null)
        {
            for (String line : Kit.wrap(font, Tr.f("info.error", error), inner))
            {
                Paint.midText(ctx, font, line, left, cy, LINE, Ui.C_DANGER, false);
                cy += LINE;
            }
        }

        cy += Ui.PAD_REL;
        cy = this.layoutActions(ctx, font, left, cy, inner, mouseX, mouseY);
        cy += Ui.PAD_GROUP;

        return this.drawPlacements(ctx, font, left, cy, inner, mouseX, mouseY) + pad;
    }

    /**
     * Название из метаданных, если оно что-то говорит. «Unnamed» — имя выделения Litematica по умолчанию:
     * у таких схем список слева показывает имя файла, и карточка — тоже.
     */
    private static boolean meaningfulName(String name)
    {
        return ! name.isBlank() && ! "Unnamed".equals(name.strip());
    }

    private int drawFacts(GuiContext ctx, Font font, SchematicEntry entry, @Nullable SchematicInfo info, int left, int cy,
            int inner, int mouseX, int mouseY)
    {
        boolean ok = info != null && info.ok();
        List<String[]> facts = new ArrayList<>();

        facts.add(new String[] { Tr.t("info.file"), entry.fileName() });
        facts.add(new String[] { Tr.t("info.format"), entry.format() == null ? Tr.t("info.unknown") : entry.format().extension() });

        if (ok)
        {
            facts.add(new String[] { Tr.t("info.size"), info.sizeX() > 0
                    ? Tr.f("files.meta.size", info.sizeX(), info.sizeY(), info.sizeZ()) : Tr.t("info.unknown") });
            facts.add(new String[] { Tr.t("info.volume"), info.volume() >= 0 ? Tr.num(info.volume()) : Tr.t("info.unknown") });
            facts.add(new String[] { Tr.t("info.blocks"), info.blocks() >= 0 ? Tr.num(info.blocks()) : Tr.t("info.unknown") });
            facts.add(new String[] { Tr.t("info.regions"), info.regions() > 0 ? Tr.num(info.regions()) : Tr.t("info.unknown") });
            facts.add(new String[] { Tr.t("info.created"), Tr.dateTime(info.created()) });
            facts.add(new String[] { Tr.t("info.modified"), Tr.dateTime(info.modified() > 0L ? info.modified() : entry.modified()) });
            facts.add(new String[] { Tr.t("info.data_version"), info.dataVersion() > 0 ? Integer.toString(info.dataVersion())
                    : Tr.t("info.unknown") });
        }
        else
        {
            facts.add(new String[] { Tr.t("info.modified"), Tr.dateTime(entry.modified()) });
        }

        int labelW = 0;

        for (String[] fact : facts)
        {
            labelW = Math.max(labelW, font.width(fact[0]));
        }

        labelW = Math.min(labelW, inner / 2);

        for (int i = 0; i < facts.size(); ++i)
        {
            String[] fact = facts.get(i);
            int valueX = left + labelW + Ui.PAD_REL;
            int room = left + inner - valueX;
            String value = Paint.fit(font, fact[1], room);
            boolean link = i == 0;
            boolean overValue = Paint.hit(mouseX, mouseY, valueX, cy, Math.min(room, font.width(value)), LINE);

            Paint.midText(ctx, font, Paint.fit(font, fact[0], labelW), left, cy, LINE, Ui.C_TEXT_3, false);
            Paint.midText(ctx, font, value, valueX, cy, LINE, link ? (overValue ? Ui.C_TEXT : Ui.C_ACCENT) : Ui.C_TEXT, false);

            if (link)
            {
                this.fileLinkX = valueX;
                this.fileLinkY = cy;
                this.fileLinkW = font.width(value);

                if (overValue)
                {
                    Paint.rect(ctx, valueX, cy + (LINE + Ui.GLYPH_H) / 2, this.fileLinkW, Ui.BORDER, Ui.C_TEXT);
                    Tooltip.offerLines("info#file", List.of(entry.path().toString(), Tr.t("info.file.tip")), Ui.C_TEXT);
                }
            }
            else if (overValue && ! value.equals(fact[1]))
            {
                Tooltip.offer("info#fact" + i, fact[1]);
            }

            cy += LINE;
        }

        return cy;
    }

    private List<Button> actions()
    {
        return List.of(this.placeHere, this.placeAt, this.materials, this.export, this.zones, this.syncShare);
    }

    private void zonesAction()
    {
        SchematicEntry entry = this.screen.selected();

        if (entry == null)
        {
            return;
        }

        if (this.zonesSplit)
        {
            this.screen.manageZones(entry);
        }
        else
        {
            this.screen.openZones(entry);
        }

        this.zonesPolledAt = 0L;
    }

    /** Разбита ли выбранная схема — раз в полсекунды (вызов Zone Focus считает её зоны). */
    private void pollZones()
    {
        SchematicEntry entry = this.screen.selected();
        long now = net.minecraft.util.Util.getMillis();

        if (entry == null || ! com.aell.layerlist.compat.ZoneFocusFiles.available())
        {
            if (this.zonesSplit)
            {
                this.zonesSplit = false;
                this.styleZoned();
            }

            return;
        }

        if (entry != this.zonesFor || now - this.zonesPolledAt > 500L)
        {
            this.zonesFor = entry;
            this.zonesPolledAt = now;
            this.zonesSplit = com.aell.layerlist.compat.ZoneFocusFiles.hasZones(entry.path());
            this.zones.label(this.zonesSplit ? Tr.t("menu.zones_manage") : Tr.t("menu.zones_split"));
            this.zones.tooltip(this.zonesSplit ? Tr.t("menu.zones_manage.tip") : Tr.t("menu.zones_split.tip"));
            this.styleZoned();
        }
    }

    /** Схема разбита на зоны: кнопки размещения — цветом зон и пунктиром, как рамки зон в мире. */
    private void styleZoned()
    {
        boolean z = this.zonesSplit;
        this.placeHere.accent(z ? ZONED : Theme.GREEN).dashed(z)
                .tooltip(z ? Tr.t("info.place_here.tip") + "\n" + Tr.t("info.place_zoned.tip") : Tr.t("info.place_here.tip"));
        this.placeAt.style(z ? Button.Style.PRIMARY : Button.Style.SECONDARY).accent(ZONED).dashed(z)
                .tooltip(z ? Tr.t("info.place_at.tip") + "\n" + Tr.t("info.place_zoned.tip") : Tr.t("info.place_at.tip"));
        this.zones.dashed(z);
    }

    /** Разбита ли выбранная схема на зоны (по последнему опросу). */
    boolean zonesSplit()
    {
        return this.zonesSplit;
    }

    private int layoutActions(GuiContext ctx, Font font, int left, int cy, int inner, int mouseX, int mouseY)
    {
        boolean busy = this.screen.busyPlacing();

        this.placeHere.label(busy ? Tr.t("place.loading") : Tr.t("info.place_here"));
        this.placeHere.setEnabled(! busy);
        this.placeAt.setEnabled(! busy);

        int h = Ui.BUTTON_H;
        int half = (inner - Ui.PAD_IN) / 2;

        this.placeHere.setVisible(! this.footerHasMain);
        this.materials.setVisible(! this.footerHasMain);

        if (this.footerHasMain)
        {
            this.placeAt.setBounds(left, cy, half, h);
            this.export.setBounds(left + inner - half, cy, half, h);
            cy += h;
        }
        else
        {
            this.placeHere.setBounds(left, cy, inner, h);
            cy += h + Ui.PAD_IN;
            this.placeAt.setBounds(left, cy, half, h);
            this.materials.setBounds(left + inner - half, cy, half, h);
            cy += h + Ui.PAD_IN;
            this.export.setBounds(left, cy, inner, h);
            cy += h;
        }

        // Syncmatica стоит — кнопка есть всегда; без связи с сервером серая, причина в подсказке
        this.syncShare.setVisible(com.aell.layerlist.compat.Syncmatica.installed());

        if (this.syncShare.visible())
        {
            boolean live = com.aell.layerlist.compat.Syncmatica.active();
            this.syncShare.setEnabled(! busy && live);
            this.syncShare.tooltip(Tr.t(live ? "sync.place_share.tip" : "sync.error.inactive"));
            cy += Ui.PAD_IN;
            this.syncShare.setBounds(left, cy, inner, h);
            cy += h;
        }

        this.zones.setVisible(com.aell.layerlist.compat.ZoneFocusFiles.available());

        if (this.zones.visible())
        {
            this.pollZones();
            cy += Ui.PAD_IN;
            this.zones.setBounds(left, cy, inner, h);
            cy += h;
        }

        // «Экспорт списка материалов ▾»; не влезает (половина ширины в узкой карточке) — «Экспорт материалов ▾»
        this.export.label(Tr.t("info.export"));

        if (this.export.preferredWidth(font) > this.export.w())
        {
            this.export.label(Tr.t("info.export.short"));
        }

        for (Button button : this.actions())
        {
            if (button.visible())
            {
                button.render(ctx, mouseX, mouseY);
            }
        }

        return cy;
    }

    private int drawPlacements(GuiContext ctx, Font font, int left, int cy, int inner, int mouseX, int mouseY)
    {
        String title = this.rows.isEmpty() ? Tr.t("info.placements") : Tr.f("info.placements_count", this.rows.size());
        Paint.midText(ctx, font, Paint.fit(font, title, inner), left, cy, Ui.SECTION_H, Ui.C_TEXT_3, false);
        Paint.rect(ctx, left, cy + Ui.SECTION_H, inner, Ui.BORDER, Ui.C_DIVIDER);
        cy += Ui.SECTION_H + Ui.PAD_IN;

        if (this.rows.isEmpty())
        {
            Paint.midText(ctx, font, Paint.fit(font, Tr.t("info.placements.none"), inner), left, cy, LINE, Ui.C_TEXT_3, false);
            return cy + LINE;
        }

        int deleteW = IconButton.size();

        for (PlacementRow row : this.rows)
        {
            int moveW = Math.min(row.move.preferredWidth(font), inner / 3);
            int buttonsW = moveW + Ui.PAD_MICRO + deleteW;
            int textW = Math.max(0, inner - buttonsW - Ui.PAD_REL);
            int lineY = cy + Ui.PAD_MICRO;

            row.enabled.setBounds(left, lineY, textW, LINE);
            row.move.setBounds(left + inner - buttonsW, cy + (PLACEMENT_ROW_H - Ui.BUTTON_H) / 2, moveW, Ui.BUTTON_H);
            row.delete.setBounds(left + inner - deleteW, cy + (PLACEMENT_ROW_H - deleteW) / 2, deleteW, deleteW);

            row.enabled.render(ctx, mouseX, mouseY);

            BlockPos origin = row.placement.getOrigin();
            String coords = Tr.f("info.origin", origin.getX(), origin.getY(), origin.getZ());
            Paint.midText(ctx, font, Paint.fit(font, coords, textW - Toggle.TRACK - Ui.PAD_IN),
                    left + Toggle.TRACK + Ui.PAD_IN, lineY + LINE, LINE, Ui.C_TEXT_3, false);

            row.move.render(ctx, mouseX, mouseY);
            row.delete.render(ctx, mouseX, mouseY);

            cy += PLACEMENT_ROW_H;
        }

        return cy;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.screen.selected() == null)
        {
            return true;
        }

        for (Button button : this.actions())
        {
            if (button.visible() && button.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        if (Kit.leftHit(click, this.fileLinkX, this.fileLinkY, this.fileLinkW, LINE))
        {
            SchematicEntry entry = this.screen.selected();

            if (entry != null)
            {
                Vanilla.click();
                this.screen.openInSystem(entry.path());
            }

            return true;
        }

        for (PlacementRow row : List.copyOf(this.rows))
        {
            if (row.enabled.mouseClicked(click, doubleClick) || row.move.mouseClicked(click, doubleClick)
                    || row.delete.mouseClicked(click, doubleClick))
            {
                return true;
            }
        }

        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        for (Button button : this.actions())
        {
            button.mouseReleased(click);
        }

        for (PlacementRow row : this.rows)
        {
            row.move.mouseReleased(click);
            row.delete.mouseReleased(click);
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        int max = Math.max(0, this.contentH - this.h);

        if (max == 0 || amount == 0.0D)
        {
            return false;
        }

        this.scroll = Ui.clamp(this.scroll - (int) Math.signum(amount) * Ui.SCROLL_WHEEL_ROWS * LINE, 0, max);
        return true;
    }

    @Override
    void release()
    {
        this.previewImage.release();
    }
}
