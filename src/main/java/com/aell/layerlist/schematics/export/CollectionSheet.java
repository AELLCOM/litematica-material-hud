package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.export.XlsxWriter.Align;
import com.aell.layerlist.schematics.export.XlsxWriter.Font;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * «Таблица сбора» по образцу «Дворец Мнений» (SPEC §8.1): список A…H с иконками, выпадающими
 * списками «Кто собирает» и «Статус», рамка ввода ников «Кто собирает — впишите ники» (J), блок
 * «Кто сколько собрал» K…O с формулами по этим никам, итогами, легендой статусов и диаграммой,
 * блок «Крупное сырьё» Q…T.
 *
 * <p>Статусы — словами из выпадающего списка; пустая ячейка — «не взято». Источник обоих списков
 * — диапазоны на самом листе (поля ников J4…, легенда статусов): такой список переживает импорт в
 * Google, а список из значений — нет; ник, вписанный в свободное поле, сразу попадает в список F,
 * получает свой цвет, строку итогов и полосу диаграммы.
 *
 * <p>Цвета — из {@link SheetPalette}: фон книги, полосы заголовка и шапки, тело списка с
 * чередованием строк, боковые панели, тонкие рамки вместо серой сетки. Палитра одна на Excel и
 * Google — выбирается перед выгрузкой (по умолчанию светлая серая).
 */
@Environment(EnvType.CLIENT)
final class CollectionSheet
{
    static final int COL_INDEX = 1;
    static final int COL_ICON = 2;
    static final int COL_ITEM = 3;
    static final int COL_COUNT = 4;
    static final int COL_BREAKDOWN = 5;
    static final int COL_WHO = 6;
    static final int COL_STATUS = 7;
    static final int COL_STACK = 8;
    static final int COL_NICK = 10;
    static final int COL_POSITIONS = 11;
    static final int COL_BLOCKS = 12;
    static final int COL_SHULKERS = 13;
    static final int COL_DONE = 14;
    static final int COL_READY = 15;
    static final int COL_RESOURCE = 17;
    static final int COL_NEED = 18;
    static final int COL_RES_SHULKERS = 19;
    static final int COL_WHY = 20;

    static final int ROW_TITLE = 1;
    static final int ROW_HEAD = 2;
    static final int FIRST = 3;
    static final int NICK_FIRST = 4;
    /** Полей под ники не меньше этого … */
    static final int MIN_NICK_SLOTS = 16;
    /** … и после ростера всегда столько свободных: новых игроков вписывают прямо в таблице. */
    static final int SPARE_NICK_SLOTS = 8;

    private static final double[] WIDTHS = {
            5.43, 3.86, 40, 11, 30, 17, 12, 6, 2.71,
            25, 10, 14, 13, 18.71, 10, 2.71,
            32, 11.29, 10.43, 56};
    private static final int ICON_PX = 20;
    private static final double ROW_PT = 18;
    private static final int SLOTS = MaterialRow.SHULKER_SLOTS;
    /** Фон листа — до колонки Z (столько колонок у нового листа Google) … */
    static final int CANVAS_COLS = 26;
    /** … и на столько строк ниже последнего блока, но не меньше {@link #CANVAS_MIN_ROWS}. */
    static final int CANVAS_ROWS_BELOW = 60;
    static final int CANVAS_MIN_ROWS = 120;
    /** Высота строки по умолчанию (15 pt) в пикселях — чтобы найти низ диаграммы. */
    private static final int DEFAULT_ROW_PX = 20;

    private CollectionSheet() {}

    /** Раскладка, по которой можно найти ячейки снаружи (проверки, отчёты). */
    record Layout(int end, int nickSlots, int totalRow, int legendRow, int hintRow, int chartRow) {}

    static XlsxWriter build(ExportData data, @Nullable RawMaterials.Result raw, @Nullable ExportIcons icons,
                            XlsxWriter.Profile profile, SheetPalette palette)
    {
        XlsxWriter book = new XlsxWriter(profile);
        new Builder(book, data, raw, icons, palette).build();

        return book;
    }

    static Layout layout(ExportData data)
    {
        int end = FIRST + Math.max(1, data.lines().size()) - 1;
        int slots = Math.max(MIN_NICK_SLOTS, data.request().roster().size() + SPARE_NICK_SLOTS);
        int totalRow = NICK_FIRST + slots;
        int legendRow = totalRow + 4;

        return new Layout(end, slots, totalRow, legendRow, legendRow + 1, legendRow + 2);
    }

    // Пиксели колонки Excel при ширине w (шрифт Calibri 11, цифра 7 px)
    static int columnPx(double width)
    {
        return (int) ((256 * width + (int) (128.0 / 7)) / 256 * 7);
    }

    private static final class Builder
    {
        private final XlsxWriter book;
        private final ExportData data;
        @Nullable private final RawMaterials.Result raw;
        @Nullable private final ExportIcons icons;
        private final Layout layout;
        private final XlsxWriter.Sheet sheet;
        private final SheetPalette p;

        private final int title;
        private final int band;
        private final int head;
        private final int section;
        private final int sub;
        private final int index;
        private final int text;
        private final int muted;
        private final int center;
        private final int count;
        private final int stack;
        private final int panelText;
        private final int panelMuted;
        private final int panelCount;
        private final int panelDecimal;
        private final int panelPercent;
        private final int sumLabel;
        private final int sumCount;
        private final int sumDecimal;
        private final int sumPercent;
        private final int legendTaking;
        private final int legendDone;
        private final int hint;
        private final int inputHead;
        private final int inputHint;
        private final int input;
        private final int canvas;
        /** Нижняя строка содержимого: блоки и диаграмма; ниже — только фон. */
        private int bottom;

        Builder(XlsxWriter book, ExportData data, @Nullable RawMaterials.Result raw, @Nullable ExportIcons icons,
                SheetPalette palette)
        {
            this.book = book;
            this.p = palette;
            this.data = data;
            this.raw = raw;
            this.icons = icons;
            this.layout = layout(data);

            // Фон и текст книги — до первого стиля; Calibri 11 держит ширины колонок прежними
            Font plain = new Font("Calibri", 11, false, false, palette.text());
            book.canvas(plain, palette.canvas());
            this.sheet = book.sheet(data.title());

            Font soft = new Font(plain.name(), 11, false, false, palette.textMuted());
            Font white = new Font(plain.name(), 11, true, false, palette.headText());
            Font whiteBig = new Font(plain.name(), 12, true, false, palette.headText());
            Font subFont = new Font(plain.name(), 11, true, false, palette.subText());
            Font sumFont = new Font(plain.name(), 11, true, false, palette.sumText());
            Font pixel = new Font("Press Start 2P", 12, false, false, palette.titleText());
            Font mono = new Font("JetBrains Mono", 10, false, false, palette.text());
            Font monoSoft = new Font("JetBrains Mono", 10, false, false, palette.textMuted());
            Font monoBold = new Font("JetBrains Mono", 10, true, false, palette.sumText());
            Font metaFont = new Font("JetBrains Mono", 9, false, false, palette.metaText());
            Font hintFont = new Font("JetBrains Mono", 9, false, false, palette.hintText());
            Font takingFont = new Font(plain.name(), 11, true, false, palette.takenText());
            Font doneFont = new Font(plain.name(), 11, true, false, palette.doneText());
            Font bold = new Font(plain.name(), 11, true, false, palette.text());
            Font inputHintFont = new Font(plain.name(), 10, false, true, palette.inputHintText());
            Font inputHeadFont = new Font(plain.name(), 11, true, false, palette.inputHeadText());
            int line = palette.border();
            int row = palette.row();
            int panel = palette.panel();
            int total = palette.total();

            this.title = book.style(pixel, palette.titleFill(), -1, null, Align.LEFT, false);
            this.band = book.style(metaFont, palette.titleFill(), -1, null, Align.LEFT, false);
            this.head = book.style(white, palette.headFill(), line, null, Align.CENTER, true);
            this.section = book.style(whiteBig, palette.headFill(), line, null, Align.CENTER, false);
            this.sub = book.style(subFont, palette.headFill(), line, null, Align.CENTER, true);
            this.index = book.style(monoSoft, row, line, null, Align.CENTER, false);
            this.text = book.style(plain, row, line, null, Align.LEFT, false);
            this.muted = book.style(soft, row, line, null, Align.LEFT, false);
            this.center = book.style(plain, row, line, null, Align.CENTER, false);
            this.count = book.style(mono, row, line, "#,##0", Align.RIGHT, false);
            this.stack = book.style(monoSoft, row, line, null, Align.CENTER, false);
            this.panelText = book.style(plain, panel, line, null, Align.LEFT, false);
            this.panelMuted = book.style(soft, panel, line, null, Align.LEFT, false);
            this.panelCount = book.style(mono, panel, line, "#,##0", Align.RIGHT, false);
            this.panelDecimal = book.style(mono, panel, line, "0.0", Align.RIGHT, false);
            this.panelPercent = book.style(mono, panel, line, "0%", Align.RIGHT, false);
            this.sumLabel = book.style(sumFont, total, line, null, Align.LEFT, false);
            this.sumCount = book.style(monoBold, total, line, "#,##0", Align.RIGHT, false);
            this.sumDecimal = book.style(monoBold, total, line, "0.0", Align.RIGHT, false);
            this.sumPercent = book.style(monoBold, total, line, "0%", Align.RIGHT, false);
            this.legendTaking = book.style(takingFont, palette.takenFill(), line, null, Align.CENTER, false);
            this.legendDone = book.style(doneFont, palette.doneFill(), line, null, Align.CENTER, false);
            this.hint = book.style(hintFont, -1, -1, null, Align.LEFT, false);
            // Рамка ввода ников: яркий заголовок, подсказка вне полей (в список она не попадёт),
            // поля — светлее панели в пунктире, как поле ввода
            int edge = palette.inputEdge();
            this.inputHead = book.style(inputHeadFont, palette.inputHeadFill(), edge, null, Align.CENTER, true);
            this.inputHint = book.style(inputHintFont, palette.inputHintFill(), edge, null, Align.CENTER, false);
            this.input = book.style(bold, palette.inputFill(), edge, XlsxWriter.Line.DASHED, null, Align.LEFT,
                    false);
            this.canvas = book.style(plain, -1, -1, null, Align.GENERAL, false);
        }

        void build()
        {
            this.columns();
            this.header();
            this.rows();
            this.stats();

            if (this.raw != null && ! this.raw.resources().isEmpty())
            {
                this.rawBlock(this.raw);
            }

            this.emptyHint();
            this.rules();
            this.sheet.freezeRows(ROW_HEAD);
            this.sheet.autoFilter(COL_INDEX, ROW_HEAD, COL_STATUS, this.layout.end());
            this.sheet.tabColor(this.p.tab());
            this.sheet.hideGridLines();
            this.bottom = Math.max(this.bottom, Math.max(this.layout.end(), this.layout.hintRow()));

            if (this.data.request().options().chart())
            {
                this.chart();
            }

            this.sheet.canvas(CANVAS_COLS, Math.max(CANVAS_MIN_ROWS, this.bottom + CANVAS_ROWS_BELOW), this.canvas);
        }

        private void columns()
        {
            for (int i = 0; i < WIDTHS.length; i++)
            {
                this.sheet.column(i + 1, WIDTHS[i], i + 1 == COL_STACK);
            }
        }

        private void header()
        {
            this.sheet.rowHeight(ROW_TITLE, 30);
            this.sheet.data(COL_INDEX, ROW_TITLE, ExportText.t("sheet.title", this.data.title()), this.title);
            this.sheet.merge(COL_INDEX, ROW_TITLE, COL_STACK, ROW_TITLE, this.title);

            // Полоса заголовка — во всю ширину фона, как шапка PNG-карточки; подпись J1 лежит на ней
            for (int c = COL_STACK + 1; c <= CANVAS_COLS; c++)
            {
                this.sheet.blank(c, ROW_TITLE, this.band);
            }

            this.sheet.data(COL_NICK, ROW_TITLE, ExportText.meta(this.data), this.band);

            this.sheet.rowHeight(ROW_HEAD, 30);
            String[] heads = {
                    ExportText.t("col.index"), "", ExportText.t("col.item"), ExportText.t("col.count"),
                    ExportText.t("col.breakdown"), ExportText.t("col.who"), ExportText.t("col.status"),
                    ExportText.t("col.stack")};

            for (int i = 0; i < heads.length; i++)
            {
                this.sheet.text(COL_INDEX + i, ROW_HEAD, heads[i], this.head);
            }
        }

        private void rows()
        {
            int iconOffsetX = Math.max(0, (columnPx(WIDTHS[COL_ICON - 1]) - ICON_PX) / 2);
            int iconOffsetY = Math.max(0, ((int) Math.round(ROW_PT * 4 / 3) - ICON_PX) / 2);
            List<ExportData.Line> lines = this.data.lines();

            for (int i = 0; i < lines.size(); i++)
            {
                ExportData.Line line = lines.get(i);
                int r = FIRST + i;
                this.sheet.rowHeight(r, ROW_PT);
                this.sheet.number(COL_INDEX, r, i + 1, this.index);
                this.sheet.blank(COL_ICON, r, this.center);
                this.sheet.data(COL_ITEM, r, line.name(), this.text);
                this.sheet.number(COL_COUNT, r, line.quantity(), this.count);
                this.sheet.text(COL_BREAKDOWN, r, line.breakdown(), this.muted);
                this.sheet.blank(COL_WHO, r, this.center);
                this.sheet.blank(COL_STATUS, r, this.center);
                this.sheet.number(COL_STACK, r, line.maxStack(), this.stack);

                byte[] png = this.icons == null ? null : this.icons.png(line.row());

                if (png != null)
                {
                    this.sheet.image(COL_ICON, r, png, ICON_PX, ICON_PX, iconOffsetX, iconOffsetY, line.id());
                }
            }

            if (lines.isEmpty())
            {
                // Пустая строка держит формулы и списки в правильном диапазоне
                this.sheet.rowHeight(FIRST, ROW_PT);

                for (int c = COL_INDEX; c <= COL_STACK; c++)
                {
                    this.sheet.blank(c, FIRST, this.center);
                }
            }
        }

        private void stats()
        {
            // Колонка ников — рамка ввода со своим заголовком в закреплённой строке 2: её видно
            // сразу и при любой прокрутке; заголовок блока — над K…O
            this.sheet.text(COL_NICK, ROW_HEAD, ExportText.t("stats.input"), this.inputHead);
            this.sheet.text(COL_NICK, FIRST, ExportText.t("stats.inputHint"), this.inputHint);
            this.sheet.text(COL_POSITIONS, ROW_HEAD, ExportText.t("stats.title"), this.section);
            this.sheet.merge(COL_POSITIONS, ROW_HEAD, COL_READY, ROW_HEAD, this.section);

            String[] subs = {
                    ExportText.t("stats.positions"), ExportText.t("stats.blocks"), ExportText.t("stats.shulkers"),
                    ExportText.t("stats.done"), ExportText.t("stats.ready")};

            for (int i = 0; i < subs.length; i++)
            {
                this.sheet.text(COL_POSITIONS + i, FIRST, subs[i], this.sub);
            }

            String who = this.range(COL_WHO);
            String counts = this.range(COL_COUNT);
            String stacks = this.range(COL_STACK);
            String statuses = this.range(COL_STATUS);
            String done = quoted(ExportText.statusDone());
            List<String> roster = this.data.request().roster();

            for (int k = 0; k < this.layout.nickSlots(); k++)
            {
                int r = NICK_FIRST + k;
                String nick = "$" + col(COL_NICK) + r;

                // Все поля одинаковые: и ростер, и свободные; цвет ника даёт условный формат
                if (k < roster.size())
                {
                    this.sheet.data(COL_NICK, r, roster.get(k), this.input);
                }
                else
                {
                    this.sheet.blank(COL_NICK, r, this.input);
                }

                String empty = "IF(" + nick + "=\"\",\"\",";
                this.sheet.formula(COL_POSITIONS, r, empty + "COUNTIF(" + who + "," + nick + "))", this.panelCount);
                this.sheet.formula(COL_BLOCKS, r, empty + "SUMIF(" + who + "," + nick + "," + counts + "))",
                        this.panelCount);
                this.sheet.formula(COL_SHULKERS, r, empty + "SUMPRODUCT((" + who + "=" + nick + ")*" + counts
                        + "/(" + SLOTS + "*" + stacks + ")))", this.panelDecimal);
                this.sheet.formula(COL_DONE, r, empty + "SUMIFS(" + counts + "," + who + "," + nick + "," + statuses
                        + "," + done + "))", this.panelCount);
                this.sheet.formula(COL_READY, r, "IF(OR(" + nick + "=\"\"," + col(COL_BLOCKS) + r + "=0),\"\","
                        + col(COL_DONE) + r + "/" + col(COL_BLOCKS) + r + ")", this.panelPercent);
            }

            int last = NICK_FIRST + this.layout.nickSlots() - 1;
            int total = this.layout.totalRow();
            this.sheet.text(COL_NICK, total, ExportText.t("stats.total"), this.sumLabel);

            for (int c : new int[] {COL_POSITIONS, COL_BLOCKS, COL_SHULKERS, COL_DONE})
            {
                this.sheet.formula(c, total, "SUM(" + XlsxWriter.range(c, NICK_FIRST, c, last) + ")",
                        c == COL_SHULKERS ? this.sumDecimal : this.sumCount);
            }

            this.sheet.formula(COL_READY, total, this.ready(total), this.sumPercent);

            int all = total + 1;
            this.sheet.text(COL_NICK, all, ExportText.t("stats.all"), this.sumLabel);
            this.sheet.formula(COL_POSITIONS, all, "COUNTA(" + this.range(COL_ITEM) + ")", this.sumCount);
            this.sheet.formula(COL_BLOCKS, all, "SUM(" + counts + ")", this.sumCount);
            this.sheet.formula(COL_SHULKERS, all, "SUMPRODUCT(" + counts + "/(" + SLOTS + "*" + stacks + "))",
                    this.sumDecimal);
            this.sheet.formula(COL_DONE, all, "SUMIF(" + statuses + "," + done + "," + counts + ")", this.sumCount);
            this.sheet.formula(COL_READY, all, this.ready(all), this.sumPercent);

            int free = total + 2;
            this.sheet.text(COL_NICK, free, ExportText.t("stats.free"), this.sumLabel);
            this.sheet.formula(COL_POSITIONS, free, "COUNTBLANK(" + who + ")", this.sumCount);
            this.sheet.formula(COL_BLOCKS, free, "SUMPRODUCT((" + who + "=\"\")*" + counts + ")", this.sumCount);
            this.sheet.formula(COL_SHULKERS, free, "SUMPRODUCT((" + who + "=\"\")*" + counts + "/(" + SLOTS + "*"
                    + stacks + "))", this.sumDecimal);
            this.sheet.blank(COL_DONE, free, this.sumCount);
            this.sheet.blank(COL_READY, free, this.sumPercent);

            int legend = this.layout.legendRow();
            this.sheet.text(COL_NICK, legend, ExportText.t("stats.legend"), this.sub);
            this.sheet.text(COL_POSITIONS, legend, ExportText.statusTaking(), this.legendTaking);
            this.sheet.text(COL_BLOCKS, legend, ExportText.statusDone(), this.legendDone);
            this.sheet.text(COL_SHULKERS, legend, ExportText.t("stats.legendHint"), this.hint);
        }

        /**
         * Подсказка над диаграммой, пока сводка пуста: нет ни одного ника — куда их вписать; ники
         * есть, но ни один не выбран в F — что диаграмма заполнится после выбора. Дальше — пусто.
         */
        private void emptyHint()
        {
            int last = NICK_FIRST + this.layout.nickSlots() - 1;
            String nicks = XlsxWriter.absolute(COL_NICK, NICK_FIRST, COL_NICK, last);
            String blocks = XlsxWriter.absolute(COL_BLOCKS, NICK_FIRST, COL_BLOCKS, last);
            String assign = this.data.request().options().chart()
                    ? "IF(SUM(" + blocks + ")=0," + quoted(ExportText.t("stats.emptyAssign")) + ",\"\")"
                    : "\"\"";

            this.sheet.formula(COL_NICK, this.layout.hintRow(), "IF(COUNTA(" + nicks + ")=0,"
                    + quoted(ExportText.t("stats.emptyNicks")) + "," + assign + ")", this.hint);
        }

        private String ready(int r)
        {
            return "IF(" + col(COL_BLOCKS) + r + "=0,\"\"," + col(COL_DONE) + r + "/" + col(COL_BLOCKS) + r + ")";
        }

        private void rawBlock(RawMaterials.Result result)
        {
            this.sheet.text(COL_RESOURCE, ROW_HEAD, ExportText.t("raw.title"), this.section);
            this.sheet.merge(COL_RESOURCE, ROW_HEAD, COL_WHY, ROW_HEAD, this.section);

            String[] subs = {
                    ExportText.t("raw.resource"), ExportText.t("raw.need"), ExportText.t("raw.shulkers"),
                    ExportText.t("raw.why")};

            for (int i = 0; i < subs.length; i++)
            {
                this.sheet.text(COL_RESOURCE + i, FIRST, subs[i], this.sub);
            }

            int r = NICK_FIRST;

            for (RawMaterials.Resource resource : result.resources())
            {
                this.rawLine(r++, resource.name(), resource.count(), resource.maxStack(),
                        String.join(", ", resource.sources()), false);
            }

            if (result.logs() > 0L)
            {
                r++;
                this.rawLine(r++, ExportText.t("raw.logsTotal"), result.logs(), 64, "", true);
            }

            this.bottom = Math.max(this.bottom, r - 1);
        }

        private void rawLine(int r, String name, long amount, int maxStack, String why, boolean summary)
        {
            this.sheet.data(COL_RESOURCE, r, name, summary ? this.sumLabel : this.panelText);
            this.sheet.number(COL_NEED, r, amount, summary ? this.sumCount : this.panelCount);
            this.sheet.formula(COL_RES_SHULKERS, r, col(COL_NEED) + r + "/(" + SLOTS + "*" + maxStack + ")",
                    summary ? this.sumDecimal : this.panelDecimal);
            this.sheet.data(COL_WHY, r, why, summary ? this.sumLabel : this.panelMuted);
        }

        private void rules()
        {
            int end = this.layout.end();
            String whoRange = XlsxWriter.range(COL_WHO, FIRST, COL_WHO, end);
            String rowRange = XlsxWriter.range(COL_INDEX, FIRST, COL_STATUS, end);
            String who = "$" + col(COL_WHO) + FIRST;
            String status = "$" + col(COL_STATUS) + FIRST;

            // Цвет ника — выше заливки строки: stopIfTrue, и правила ников идут первыми. Текст
            // плашки задаётся тёмным явно: свой текст ячейки на тёмной теме светлый
            for (int k = 0; k < this.layout.nickSlots(); k++)
            {
                String nick = "$" + col(COL_NICK) + "$" + (NICK_FIRST + k);
                this.sheet.conditional(whoRange, "AND(" + who + "<>\"\"," + who + "=" + nick + ")",
                        this.book.dxf(CanonPalette.nick(k), this.p.chipText(), true), true);
            }

            // Вписанный ник красит своё поле тем же тоном, что его строки в F: видно, что ник
            // принят и какой у него цвет; пустое поле остаётся полем ввода
            for (int k = 0; k < this.layout.nickSlots(); k++)
            {
                int r = NICK_FIRST + k;
                this.sheet.conditional(XlsxWriter.cell(COL_NICK, r), "$" + col(COL_NICK) + r + "<>\"\"",
                        this.book.dxf(CanonPalette.nick(k), this.p.chipText(), true), true);
            }

            String done = quoted(ExportText.statusDone());
            String taking = quoted(ExportText.statusTaking());
            this.sheet.conditional(rowRange, status + "=" + done,
                    this.book.dxf(this.p.doneFill(), this.p.doneText(), false), true);
            this.sheet.conditional(rowRange, "OR(" + status + "=" + taking + ",AND(" + who + "<>\"\"," + status
                    + "=\"\"))", this.book.dxf(this.p.takenFill(), this.p.takenText(), false), true);

            // Чередование строк — последним правилом и формулой от номера строки: после сортировки
            // фильтром полосы остаются ровными, а статусы и ники перекрывают его своей заливкой
            this.sheet.conditional(rowRange, "MOD(ROW(),2)=0", this.book.dxf(this.p.rowAlt(), false), false);

            int lastNick = NICK_FIRST + this.layout.nickSlots() - 1;
            this.sheet.listValidation(whoRange, XlsxWriter.absolute(COL_NICK, NICK_FIRST, COL_NICK, lastNick),
                    ExportText.t("dv.who.title"), ExportText.t("dv.who.error"));
            int legend = this.layout.legendRow();
            this.sheet.listValidation(XlsxWriter.range(COL_STATUS, FIRST, COL_STATUS, end),
                    XlsxWriter.absolute(COL_POSITIONS, legend, COL_BLOCKS, legend),
                    ExportText.t("dv.status.title"), ExportText.t("dv.status.error"));
        }

        private void chart()
        {
            int last = NICK_FIRST + this.layout.nickSlots() - 1;
            List<String> names = new ArrayList<>();
            List<String> roster = this.data.request().roster();

            for (int k = 0; k < this.layout.nickSlots(); k++)
            {
                names.add(k < roster.size() ? roster.get(k) : "");
            }

            int width = 0;

            for (int c = COL_NICK; c <= COL_READY; c++)
            {
                width += columnPx(WIDTHS[c - 1]);
            }

            int height = Math.max(280, 120 + 24 * this.layout.nickSlots());
            List<XlsxWriter.Series> series = List.of(
                    new XlsxWriter.Series(this.sheet.reference(COL_BLOCKS, FIRST, COL_BLOCKS, FIRST),
                            ExportText.t("stats.blocks"),
                            this.sheet.reference(COL_BLOCKS, NICK_FIRST, COL_BLOCKS, last), this.p.chartTotal(), false),
                    new XlsxWriter.Series(this.sheet.reference(COL_DONE, FIRST, COL_DONE, FIRST),
                            ExportText.t("stats.done"),
                            this.sheet.reference(COL_DONE, NICK_FIRST, COL_DONE, last), this.p.chartDone(), true));
            XlsxWriter.ChartLook look = new XlsxWriter.ChartLook(this.p.chartTitle(), this.p.textMuted(),
                    this.p.text(), this.p.border(), this.p.panel(), this.p.border());

            this.sheet.barChart(new XlsxWriter.BarChart(COL_NICK, this.layout.chartRow(), width, height,
                    ExportText.t("chart.title"), this.sheet.reference(COL_NICK, NICK_FIRST, COL_NICK, last), names,
                    series, look));
            this.bottom = Math.max(this.bottom, this.layout.chartRow() + (height + DEFAULT_ROW_PX - 1) / DEFAULT_ROW_PX);
        }

        private String range(int column)
        {
            return XlsxWriter.absolute(column, FIRST, column, this.layout.end());
        }
    }

    private static String col(int column)
    {
        return XlsxWriter.column(column);
    }

    /** Строковый литерал формулы: кавычки внутри удваиваются. */
    static String quoted(String value)
    {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
