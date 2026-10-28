package com.aell.layerlist.schematics.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Минимальный писатель OOXML-таблиц (.xlsx) на одном {@code java.util.zip}: общие строки, стили
 * (шрифты, заливки, рамки, числовые форматы), листы с ширинами и скрытыми колонками,
 * закреплением, объединением, автофильтром, выпадающими списками из диапазонов, условным
 * форматированием по формулам, формулами без кэша (книга помечена «пересчитать при открытии»),
 * картинками PNG поверх ячеек и сгруппированной столбчатой диаграммой. Книга может нести свой фон
 * ({@link #canvas}): им окрашены стиль «Обычный», колонки листа и пустые ячейки вокруг таблицы.
 *
 * <p>Порядок дочерних элементов листа, книги и диаграммы — строго по схеме ISO/IEC 29500: Excel
 * «чинит» файл с переставленными элементами, а Google теряет их при импорте.
 *
 * <p>Булевых ячеек писатель не умеет вовсе, а перед записью {@link #verify()} проверяет, что ни в
 * одной ячейке, формуле, правиле и списке нет TRUE/FALSE (SPEC §0.3).
 */
@Environment(EnvType.CLIENT)
public final class XlsxWriter
{
    /** Чьим импортом файл будут открывать: от этого зависит запись картинок. */
    public enum Profile
    {
        EXCEL,
        GOOGLE
    }

    public enum Align
    {
        GENERAL,
        LEFT,
        CENTER,
        RIGHT
    }

    /** Линия рамки ячейки: тонкая сплошная или тонкий пунктир (поле ввода). */
    public enum Line
    {
        THIN("thin"),
        DASHED("dashed");

        private final String code;

        Line(String code)
        {
            this.code = code;
        }
    }

    /** Шрифт ячейки; {@code rgb} — 0xRRGGBB. */
    public record Font(String name, double size, boolean bold, boolean italic, int rgb)
    {
        public Font
        {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Ряд столбчатой диаграммы.
     *
     * @param nameRef   ссылка на ячейку с именем ряда ({@code 'Лист'!$L$3})
     * @param name      то же имя — кэш для программ, которые не пересчитывают книгу
     * @param valuesRef ссылка на значения
     * @param rgb       цвет столбцов
     * @param labels    подписывать ли значения
     */
    public record Series(String nameRef, String name, String valuesRef, int rgb, boolean labels) {}

    /**
     * Цвета диаграммы (0xRRGGBB). Всё задаётся явно: чего в файле нет, Google при импорте берёт
     * своё (чёрный текст осей, серая сетка, белый фон).
     *
     * @param titleRgb  заголовок
     * @param textRgb   подписи осей и легенда
     * @param labelRgb  подписи значений у рядов с {@link Series#labels()}
     * @param gridRgb   линии сетки и оси
     * @param areaRgb   фон диаграммы и области построения
     * @param borderRgb рамка диаграммы
     */
    public record ChartLook(int titleRgb, int textRgb, int labelRgb, int gridRgb, int areaRgb, int borderRgb) {}

    /**
     * Сгруппированная горизонтальная столбчатая диаграмма; первая категория — сверху, шкала
     * значений — от нуля.
     *
     * @param col        колонка левого верхнего угла (с 1)
     * @param row        строка левого верхнего угла (с 1)
     * @param categories ссылка на подписи категорий
     * @param names      подписи категорий — кэш (пустые позиции пропускаются)
     */
    public record BarChart(int col, int row, int widthPx, int heightPx, String title, String categories,
                           List<String> names, List<Series> series, ChartLook look)
    {
        public BarChart
        {
            names = List.copyOf(names);
            series = List.copyOf(series);
            Objects.requireNonNull(look, "look");
        }
    }

    static final int EMU_PER_PX = 9525;
    /**
     * Подписи значений диаграммы: ноль не подписывается — у пустых слотов (формула вернула «»)
     * и у ников без блоков не висит строй нулей.
     */
    static final String CHART_LABEL_FORMAT = "#,##0;;";
    /**
     * Шкала значений — целые блоки: ноль — «0», дробные деления меньше единицы — пусто. На пустых
     * данных шкала 0…1 с шагом 0,2 иначе читается как «0 0 0 1 1 1».
     */
    static final String CHART_AXIS_FORMAT = "[=0]0;[<1]\"\";#,##0";

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String NS_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String NS_XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    private static final String REL = NS_R + "/";
    private static final String TYPE_OOXML = "application/vnd.openxmlformats-officedocument.";

    private static final int FILL_NONE = -1;
    private static final int SHEET_NAME_MAX = 31;
    private static final int FIRST_CUSTOM_FORMAT = 164;
    /** Ширина колонки Excel по умолчанию (8.43 знака Calibri 11) в том виде, как её пишет сам Excel. */
    private static final double DEFAULT_COLUMN_WIDTH = 9.140625;

    private record Xf(int font, int fill, int border, int numFmt, Align align, boolean wrap) {}

    private record Dxf(int fillRgb, int fontRgb, boolean bold) {}

    private record Border(int rgb, Line line) {}

    private enum Kind
    {
        TEXT,
        DATA,
        NUMBER,
        FORMULA,
        BLANK
    }

    private record Cell(Kind kind, String value, int style) {}

    private record Column(double width, boolean hidden) {}

    private record Validation(String sqref, String source, String title, String error) {}

    private record Rule(String sqref, String formula, int dxf, boolean stop, int priority) {}

    private record Picture(int col, int row, int widthPx, int heightPx, int offsetXPx, int offsetYPx, int media,
                           String description) {}

    private final Profile profile;
    private final List<Font> fonts = new ArrayList<>();
    private final List<Integer> fills = new ArrayList<>();
    private final List<Border> borders = new ArrayList<>();
    private final Map<String, Integer> formats = new LinkedHashMap<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final List<Dxf> dxfs = new ArrayList<>();
    private final List<Sheet> sheets = new ArrayList<>();
    private final List<byte[]> media = new ArrayList<>();
    private final Map<String, Integer> mediaByHash = new LinkedHashMap<>();
    private final Map<String, Integer> strings = new LinkedHashMap<>();
    /** Заливка стиля «Обычный»; 0 — без заливки (белый лист). */
    private int baseFill;

    public XlsxWriter(Profile profile)
    {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.fonts.add(new Font("Calibri", 11, false, false, 0x000000));
        // Заливки 0 и 1 Excel держит за собой («нет» и «серый 12,5 %»)
        this.fills.add(FILL_NONE);
        this.fills.add(FILL_NONE);
        this.borders.add(new Border(-1, Line.THIN));
        this.xfs.add(new Xf(0, 0, 0, 0, Align.GENERAL, false));
    }

    public Font defaultFont()
    {
        return this.fonts.get(0);
    }

    /**
     * Фон и шрифт книги — стиль «Обычный» ({@code cellStyleXfs} и {@code cellXfs} номер 0). Им
     * Excel рисует все ячейки без своего стиля, в том числе пустые за таблицей; стили «без
     * заливки» ({@link #style} с {@code -1}) берут этот же фон, а не белый. Ширины колонок Excel
     * считает по цифре шрифта «Обычный» — для прежних ширин это должен остаться Calibri 11.
     * Звать до первого {@link #style}.
     */
    public void canvas(Font font, int fillRgb)
    {
        if (this.xfs.size() > 1 || this.fonts.size() > 1)
        {
            throw new IllegalStateException("canvas() goes before the first style");
        }

        this.fonts.set(0, Objects.requireNonNull(font, "font"));
        this.baseFill = indexOfFrom(this.fills, fillRgb & 0xFFFFFF, 2);
        this.xfs.set(0, new Xf(0, this.baseFill, 0, 0, Align.GENERAL, false));
    }

    /**
     * Стиль ячейки (номер {@code cellXfs}, с 1 — номер 0 остаётся стилю «Обычный»); одинаковые
     * стили не дублируются.
     *
     * @param fillRgb   сплошная заливка или {@code -1} — фон книги ({@link #canvas}, иначе без заливки)
     * @param borderRgb тонкая рамка этого цвета со всех сторон или {@code -1} — без рамки
     * @param numFmt    код числового формата ({@code "#,##0"}) или {@code null} — общий
     */
    public int style(Font font, int fillRgb, int borderRgb, @Nullable String numFmt, Align align, boolean wrap)
    {
        return this.style(font, fillRgb, borderRgb, Line.THIN, numFmt, align, wrap);
    }

    /** То же, с линией рамки: {@link Line#DASHED} — пунктир, которым на листе помечены поля ввода. */
    public int style(Font font, int fillRgb, int borderRgb, Line line, @Nullable String numFmt, Align align,
                     boolean wrap)
    {
        int fontId = indexOf(this.fonts, font);
        int fillId = fillRgb < 0 ? this.baseFill : indexOfFrom(this.fills, fillRgb & 0xFFFFFF, 2);
        int borderId = borderRgb < 0 ? 0 : indexOfFrom(this.borders, new Border(borderRgb & 0xFFFFFF, line), 1);
        int formatId = 0;

        if (numFmt != null)
        {
            formatId = this.formats.computeIfAbsent(numFmt, code -> FIRST_CUSTOM_FORMAT + this.formats.size());
        }

        // Свой номер даже у стиля, равного «Обычному»: ячейка с явным s="N" держит фон и там,
        // где программа не применяет стиль по умолчанию к пустым ячейкам
        return indexOfFrom(this.xfs, new Xf(fontId, fillId, borderId, formatId, align, wrap), 1);
    }

    /** Формат для условного форматирования: сплошная заливка и, по желанию, жирный шрифт. */
    public int dxf(int fillRgb, boolean bold)
    {
        return this.dxf(fillRgb, -1, bold);
    }

    /**
     * Формат для условного форматирования: сплошная заливка, цвет текста ({@code -1} — текст
     * ячейки не меняется) и, по желанию, жирный шрифт.
     */
    public int dxf(int fillRgb, int fontRgb, boolean bold)
    {
        return indexOf(this.dxfs, new Dxf(fillRgb & 0xFFFFFF, fontRgb < 0 ? -1 : fontRgb & 0xFFFFFF, bold));
    }

    /** Новый лист; имя приводится к правилам Excel (≤ 31 символа, без {@code []:*?/\}). */
    public Sheet sheet(String name)
    {
        String clean = sheetName(name);
        String unique = clean;

        for (int n = 2; this.hasSheet(unique); n++)
        {
            String suffix = " (" + n + ")";
            unique = clean.substring(0, Math.min(clean.length(), SHEET_NAME_MAX - suffix.length())) + suffix;
        }

        Sheet sheet = new Sheet(unique, this.sheets.size() + 1);
        this.sheets.add(sheet);

        return sheet;
    }

    private boolean hasSheet(String name)
    {
        for (Sheet sheet : this.sheets)
        {
            if (sheet.name.equalsIgnoreCase(name))
            {
                return true;
            }
        }

        return false;
    }

    public static String sheetName(String name)
    {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            out.append("[]:*?/\\".indexOf(c) >= 0 || c < 0x20 ? ' ' : c);
        }

        String clean = out.toString().replaceAll("\\s+", " ").strip();

        while (clean.startsWith("'"))
        {
            clean = clean.substring(1).strip();
        }

        if (clean.length() > SHEET_NAME_MAX)
        {
            clean = clean.substring(0, SHEET_NAME_MAX);

            // Не резать суррогатную пару пополам
            if (Character.isHighSurrogate(clean.charAt(clean.length() - 1)))
            {
                clean = clean.substring(0, clean.length() - 1);
            }
        }

        while (clean.endsWith("'"))
        {
            clean = clean.substring(0, clean.length() - 1).strip();
        }

        return clean.isEmpty() ? "Sheet1" : clean;
    }

    /** Буквы колонки: 1 → A, 27 → AA. */
    public static String column(int col)
    {
        StringBuilder out = new StringBuilder(3);

        for (int n = col; n > 0; n = (n - 1) / 26)
        {
            out.insert(0, (char) ('A' + (n - 1) % 26));
        }

        return out.toString();
    }

    public static String cell(int col, int row)
    {
        return column(col) + row;
    }

    /** Абсолютная ссылка {@code $A$1} / {@code $A$1:$B$2}. */
    public static String absolute(int col1, int row1, int col2, int row2)
    {
        String first = "$" + column(col1) + "$" + row1;

        return col1 == col2 && row1 == row2 ? first : first + ":$" + column(col2) + "$" + row2;
    }

    public static String range(int col1, int row1, int col2, int row2)
    {
        return cell(col1, row1) + ":" + cell(col2, row2);
    }

    /**
     * Проверка SPEC §0.3: ни одной булевой заглушки. Ячейки-тексты генератора не равны TRUE/FALSE
     * (и их русским именам), в формулах, правилах и списках нет слов TRUE/FALSE вне строковых
     * литералов. Данные игрока ({@link Sheet#data}) — ники, названия — не проверяются: ник
     * «True» — это ник, а не заглушка.
     *
     * @throws IllegalStateException с адресом нарушения
     */
    public void verify()
    {
        for (Sheet sheet : this.sheets)
        {
            sheet.verify();
        }
    }

    /** Собрать пакет и записать в поток (поток не закрывается). */
    public void write(OutputStream out) throws IOException
    {
        this.verify();
        this.strings.clear();

        List<String> sheetXml = new ArrayList<>(this.sheets.size());

        // Листы пишутся до sharedStrings: при записи они и заполняют таблицу строк
        for (Sheet sheet : this.sheets)
        {
            String xml = sheet.xml();

            if (xml.contains(" t=\"b\""))
            {
                throw new IllegalStateException("boolean cell in sheet " + sheet.name);
            }

            sheetXml.add(xml);
        }

        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        put(zip, "[Content_Types].xml", this.contentTypes());
        put(zip, "_rels/.rels", HEADER + "<Relationships xmlns=\"" + NS_PACKAGE_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        put(zip, "xl/workbook.xml", this.workbook());
        put(zip, "xl/_rels/workbook.xml.rels", this.workbookRels());
        put(zip, "xl/styles.xml", this.styles());

        int chartNumber = 0;

        for (int i = 0; i < this.sheets.size(); i++)
        {
            Sheet sheet = this.sheets.get(i);
            put(zip, "xl/worksheets/sheet" + sheet.number + ".xml", sheetXml.get(i));

            if (sheet.hasDrawing())
            {
                put(zip, "xl/worksheets/_rels/sheet" + sheet.number + ".xml.rels", HEADER
                        + "<Relationships xmlns=\"" + NS_PACKAGE_REL + "\">"
                        + "<Relationship Id=\"rId1\" Type=\"" + REL + "drawing\" Target=\"../drawings/drawing"
                        + sheet.number + ".xml\"/></Relationships>");

                StringBuilder rels = new StringBuilder(HEADER).append("<Relationships xmlns=\"")
                        .append(NS_PACKAGE_REL).append("\">");
                String drawing = sheet.drawingXml(chartNumber, rels);
                rels.append("</Relationships>");
                put(zip, "xl/drawings/drawing" + sheet.number + ".xml", drawing);
                put(zip, "xl/drawings/_rels/drawing" + sheet.number + ".xml.rels", rels.toString());

                for (BarChart chart : sheet.charts)
                {
                    chartNumber++;
                    put(zip, "xl/charts/chart" + chartNumber + ".xml", chartXml(chart));
                }
            }
        }

        put(zip, "xl/sharedStrings.xml", this.sharedStrings());

        for (int i = 0; i < this.media.size(); i++)
        {
            zip.putNextEntry(new ZipEntry("xl/media/image" + (i + 1) + ".png"));
            zip.write(this.media.get(i));
            zip.closeEntry();
        }

        zip.finish();
    }

    private int stringIndex(String value)
    {
        return this.strings.computeIfAbsent(value, key -> this.strings.size());
    }

    private int mediaIndex(byte[] png)
    {
        String hash;

        try
        {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(png));
        }
        catch (NoSuchAlgorithmException e)
        {
            hash = Integer.toHexString(Arrays.hashCode(png)) + ":" + png.length;
        }

        return this.mediaByHash.computeIfAbsent(hash, key ->
        {
            this.media.add(png.clone());
            return this.media.size() - 1;
        });
    }

    private int chartCount()
    {
        int count = 0;

        for (Sheet sheet : this.sheets)
        {
            count += sheet.charts.size();
        }

        return count;
    }

    private String contentTypes()
    {
        StringBuilder xml = new StringBuilder(HEADER).append("<Types xmlns=\"").append(NS_TYPES).append("\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                .append(override("/xl/workbook.xml", "spreadsheetml.sheet.main+xml"))
                .append(override("/xl/styles.xml", "spreadsheetml.styles+xml"))
                .append(override("/xl/sharedStrings.xml", "spreadsheetml.sharedStrings+xml"));

        for (Sheet sheet : this.sheets)
        {
            xml.append(override("/xl/worksheets/sheet" + sheet.number + ".xml", "spreadsheetml.worksheet+xml"));

            if (sheet.hasDrawing())
            {
                xml.append(override("/xl/drawings/drawing" + sheet.number + ".xml", "drawing+xml"));
            }
        }

        for (int i = 1; i <= this.chartCount(); i++)
        {
            xml.append(override("/xl/charts/chart" + i + ".xml", "drawingml.chart+xml"));
        }

        return xml.append("</Types>").toString();
    }

    private static String override(String part, String type)
    {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + TYPE_OOXML + type + "\"/>";
    }

    private String workbook()
    {
        StringBuilder xml = new StringBuilder(HEADER).append("<workbook xmlns=\"").append(NS_MAIN)
                .append("\" xmlns:r=\"").append(NS_R).append("\">")
                .append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");

        for (Sheet sheet : this.sheets)
        {
            xml.append("<sheet name=\"").append(escape(sheet.name)).append("\" sheetId=\"").append(sheet.number)
                    .append("\" r:id=\"rId").append(sheet.number).append("\"/>");
        }

        xml.append("</sheets>");

        StringBuilder names = new StringBuilder();

        for (Sheet sheet : this.sheets)
        {
            if (sheet.filter != null)
            {
                // Так Excel узнаёт диапазон автофильтра; Google и openpyxl пишут то же имя
                names.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(sheet.number - 1)
                        .append("\" hidden=\"1\">").append(escape(sheet.reference(sheet.filter))).append("</definedName>");
            }
        }

        if (! names.isEmpty())
        {
            xml.append("<definedNames>").append(names).append("</definedNames>");
        }

        // Формулы пишутся без кэша значений: пусть Excel пересчитает книгу при открытии
        return xml.append("<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>").toString();
    }

    private String workbookRels()
    {
        StringBuilder xml = new StringBuilder(HEADER).append("<Relationships xmlns=\"").append(NS_PACKAGE_REL).append("\">");

        for (Sheet sheet : this.sheets)
        {
            xml.append("<Relationship Id=\"rId").append(sheet.number).append("\" Type=\"").append(REL)
                    .append("worksheet\" Target=\"worksheets/sheet").append(sheet.number).append(".xml\"/>");
        }

        int next = this.sheets.size() + 1;
        xml.append("<Relationship Id=\"rId").append(next).append("\" Type=\"").append(REL)
                .append("styles\" Target=\"styles.xml\"/>");
        xml.append("<Relationship Id=\"rId").append(next + 1).append("\" Type=\"").append(REL)
                .append("sharedStrings\" Target=\"sharedStrings.xml\"/>");

        return xml.append("</Relationships>").toString();
    }

    private String styles()
    {
        StringBuilder xml = new StringBuilder(HEADER).append("<styleSheet xmlns=\"").append(NS_MAIN).append("\">");

        if (! this.formats.isEmpty())
        {
            xml.append("<numFmts count=\"").append(this.formats.size()).append("\">");
            this.formats.forEach((code, id) -> xml.append("<numFmt numFmtId=\"").append(id)
                    .append("\" formatCode=\"").append(escape(code)).append("\"/>"));
            xml.append("</numFmts>");
        }

        xml.append("<fonts count=\"").append(this.fonts.size()).append("\">");

        for (Font font : this.fonts)
        {
            xml.append("<font>");

            if (font.bold())
            {
                xml.append("<b/>");
            }

            if (font.italic())
            {
                xml.append("<i/>");
            }

            xml.append("<sz val=\"").append(number(font.size())).append("\"/>")
                    .append("<color rgb=\"").append(argb(font.rgb())).append("\"/>")
                    .append("<name val=\"").append(escape(font.name())).append("\"/>")
                    .append("<family val=\"").append(font.name().equals("Calibri") ? 2 : 3).append("\"/></font>");
        }

        xml.append("</fonts><fills count=\"").append(this.fills.size()).append("\">")
                .append("<fill><patternFill patternType=\"none\"/></fill>")
                .append("<fill><patternFill patternType=\"gray125\"/></fill>");

        for (int i = 2; i < this.fills.size(); i++)
        {
            xml.append("<fill>").append(solid(this.fills.get(i))).append("</fill>");
        }

        xml.append("</fills><borders count=\"").append(this.borders.size()).append("\">")
                .append("<border><left/><right/><top/><bottom/><diagonal/></border>");

        for (int i = 1; i < this.borders.size(); i++)
        {
            Border border = this.borders.get(i);
            String color = "<color rgb=\"" + argb(border.rgb()) + "\"/>";
            xml.append("<border>");

            for (String side : List.of("left", "right", "top", "bottom"))
            {
                xml.append('<').append(side).append(" style=\"").append(border.line().code).append("\">").append(color)
                        .append("</").append(side).append('>');
            }

            xml.append("<diagonal/></border>");
        }

        xml.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"").append(this.baseFill)
                .append("\" borderId=\"0\"/></cellStyleXfs>")
                .append("<cellXfs count=\"").append(this.xfs.size()).append("\">");

        for (int i = 0; i < this.xfs.size(); i++)
        {
            Xf xf = this.xfs.get(i);
            xml.append("<xf numFmtId=\"").append(xf.numFmt()).append("\" fontId=\"").append(xf.font())
                    .append("\" fillId=\"").append(xf.fill()).append("\" borderId=\"").append(xf.border())
                    .append("\" xfId=\"0\"");

            if (xf.numFmt() != 0)
            {
                xml.append(" applyNumberFormat=\"1\"");
            }

            if (xf.font() != 0)
            {
                xml.append(" applyFont=\"1\"");
            }

            if (xf.fill() != 0)
            {
                xml.append(" applyFill=\"1\"");
            }

            if (xf.border() != 0)
            {
                xml.append(" applyBorder=\"1\"");
            }

            if (xf.align() == Align.GENERAL && ! xf.wrap() && i == 0)
            {
                xml.append("/>");
                continue;
            }

            xml.append(" applyAlignment=\"1\"><alignment");

            if (xf.align() != Align.GENERAL)
            {
                xml.append(" horizontal=\"").append(xf.align().name().toLowerCase(Locale.ROOT)).append('"');
            }

            xml.append(" vertical=\"center\"");

            if (xf.wrap())
            {
                xml.append(" wrapText=\"1\"");
            }

            xml.append("/></xf>");
        }

        xml.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
                .append("<dxfs count=\"").append(this.dxfs.size()).append("\">");

        for (Dxf dxf : this.dxfs)
        {
            // В dxf Excel берёт цвет сплошной заливки из bgColor, Google — из fgColor: пишем оба
            xml.append("<dxf><font>").append(dxf.bold() ? "<b/>" : "");

            if (dxf.fontRgb() >= 0)
            {
                xml.append("<color rgb=\"").append(argb(dxf.fontRgb())).append("\"/>");
            }

            xml.append("</font><fill>").append(solid(dxf.fillRgb())).append("</fill><border/></dxf>");
        }

        return xml.append("</dxfs><tableStyles count=\"0\"/></styleSheet>").toString();
    }

    private String sharedStrings()
    {
        StringBuilder xml = new StringBuilder(HEADER).append("<sst xmlns=\"").append(NS_MAIN).append("\" count=\"")
                .append(this.strings.size()).append("\" uniqueCount=\"").append(this.strings.size()).append("\">");

        for (String value : this.strings.keySet())
        {
            xml.append("<si><t>").append(escape(value)).append("</t></si>");
        }

        return xml.append("</sst>").toString();
    }

    private static String chartXml(BarChart chart)
    {
        ChartLook look = chart.look();
        StringBuilder xml = new StringBuilder(HEADER).append("<c:chartSpace xmlns:c=\"").append(NS_C)
                .append("\" xmlns:a=\"").append(NS_A).append("\" xmlns:r=\"").append(NS_R).append("\">")
                .append("<c:roundedCorners val=\"0\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr>")
                .append("<a:defRPr sz=\"1200\" b=\"1\">").append(srgb(look.titleRgb())).append("</a:defRPr></a:pPr>")
                .append("<a:r><a:rPr lang=\"ru-RU\" sz=\"1200\" b=\"1\">").append(srgb(look.titleRgb())).append("</a:rPr>")
                .append("<a:t>").append(escape(chart.title())).append("</a:t></a:r></a:p></c:rich></c:tx>")
                .append("<c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>")
                .append("<c:plotArea><c:layout/><c:barChart><c:barDir val=\"bar\"/><c:grouping val=\"clustered\"/>")
                .append("<c:varyColors val=\"0\"/>");

        for (int i = 0; i < chart.series().size(); i++)
        {
            Series series = chart.series().get(i);
            xml.append("<c:ser><c:idx val=\"").append(i).append("\"/><c:order val=\"").append(i).append("\"/>")
                    .append("<c:tx><c:strRef><c:f>").append(escape(series.nameRef())).append("</c:f>")
                    .append(stringCache(List.of(series.name()))).append("</c:strRef></c:tx>")
                    .append("<c:spPr>").append(srgb(series.rgb())).append("<a:ln><a:noFill/></a:ln></c:spPr>")
                    .append("<c:invertIfNegative val=\"0\"/>");

            if (series.labels())
            {
                xml.append("<c:dLbls><c:numFmt formatCode=\"").append(escape(CHART_LABEL_FORMAT))
                        .append("\" sourceLinked=\"0\"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>")
                        .append(text(look.labelRgb(), 900))
                        .append("<c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/><c:showCatName val=\"0\"/>")
                        .append("<c:showSerName val=\"0\"/><c:showPercent val=\"0\"/><c:showBubbleSize val=\"0\"/></c:dLbls>");
            }

            xml.append("<c:cat><c:strRef><c:f>").append(escape(chart.categories())).append("</c:f>")
                    .append(stringCache(chart.names())).append("</c:strRef></c:cat>")
                    .append("<c:val><c:numRef><c:f>").append(escape(series.valuesRef())).append("</c:f></c:numRef></c:val></c:ser>");
        }

        String axisLine = "<c:spPr><a:ln>" + srgb(look.gridRgb()) + "</a:ln></c:spPr>";
        String area = "<c:spPr>" + srgb(look.areaRgb()) + "<a:ln><a:noFill/></a:ln></c:spPr>";

        // Первая категория сверху (maxMin), поэтому шкала значений пересекает ось у «max» — внизу.
        // Шкала от нуля: без неё Google на пустых данных подписывает ось «-1 -1 0»
        return xml.append("<c:gapWidth val=\"60\"/><c:overlap val=\"-10\"/>")
                .append("<c:axId val=\"500000001\"/><c:axId val=\"500000002\"/></c:barChart>")
                .append("<c:catAx><c:axId val=\"500000001\"/><c:scaling><c:orientation val=\"maxMin\"/></c:scaling>")
                .append("<c:delete val=\"0\"/><c:axPos val=\"l\"/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/>")
                .append("<c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>")
                .append(axisLine).append(text(look.textRgb(), 900))
                .append("<c:crossAx val=\"500000002\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/>")
                .append("<c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>")
                .append("<c:valAx><c:axId val=\"500000002\"/><c:scaling><c:orientation val=\"minMax\"/>")
                .append("<c:min val=\"0\"/></c:scaling>")
                .append("<c:delete val=\"0\"/><c:axPos val=\"b\"/><c:majorGridlines>").append(axisLine)
                .append("</c:majorGridlines><c:numFmt formatCode=\"").append(escape(CHART_AXIS_FORMAT))
                .append("\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/>")
                .append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>")
                .append("<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>").append(text(look.textRgb(), 900))
                .append("<c:crossAx val=\"500000001\"/><c:crosses val=\"max\"/><c:crossBetween val=\"between\"/></c:valAx>")
                .append(area).append("</c:plotArea>")
                .append("<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/>").append(text(look.textRgb(), 900))
                .append("</c:legend><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart>")
                .append("<c:spPr>").append(srgb(look.areaRgb())).append("<a:ln>").append(srgb(look.borderRgb()))
                .append("</a:ln></c:spPr>").append(text(look.textRgb(), 900)).append("</c:chartSpace>")
                .toString();
    }

    /** Свойства текста элемента диаграммы ({@code c:txPr}): цвет и кегль в сотых пункта. */
    private static String text(int rgb, int size)
    {
        return "<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"" + size + "\">" + srgb(rgb)
                + "</a:defRPr></a:pPr><a:endParaRPr lang=\"ru-RU\"/></a:p></c:txPr>";
    }

    private static String stringCache(List<String> values)
    {
        StringBuilder xml = new StringBuilder("<c:strCache><c:ptCount val=\"").append(values.size()).append("\"/>");

        for (int i = 0; i < values.size(); i++)
        {
            if (! values.get(i).isEmpty())
            {
                xml.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(escape(values.get(i))).append("</c:v></c:pt>");
            }
        }

        return xml.append("</c:strCache>").toString();
    }

    private static String solid(int rgb)
    {
        return "<patternFill patternType=\"solid\"><fgColor rgb=\"" + argb(rgb) + "\"/><bgColor rgb=\"" + argb(rgb)
                + "\"/></patternFill>";
    }

    private static String srgb(int rgb)
    {
        return "<a:solidFill><a:srgbClr val=\"" + String.format("%06X", rgb & 0xFFFFFF) + "\"/></a:solidFill>";
    }

    private static String argb(int rgb)
    {
        return String.format("FF%06X", rgb & 0xFFFFFF);
    }

    /** Число без экспоненты и локали: «9744», «0.5». */
    static String number(double value)
    {
        if (value == Math.rint(value) && Math.abs(value) < 1e15)
        {
            return Long.toString((long) value);
        }

        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Текст для XML 1.0: экранирование, без управляющих символов, одиноких суррогатов и кодов
     * форматирования Minecraft ({@code §x}) — они бывают в названиях предметов с данными.
     */
    static String escape(String text)
    {
        StringBuilder out = new StringBuilder(text.length() + 16);

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            if (c == '§' && i + 1 < text.length())
            {
                i++;
                continue;
            }

            if (Character.isHighSurrogate(c))
            {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)))
                {
                    out.append(c).append(text.charAt(++i));
                }

                continue;
            }

            switch (c)
            {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default ->
                {
                    boolean legal = c == '\t' || c == '\n' || c == '\r'
                            || (c >= 0x20 && ! Character.isLowSurrogate(c) && c != 0xFFFE && c != 0xFFFF);

                    if (legal)
                    {
                        out.append(c);
                    }
                }
            }
        }

        return out.toString();
    }

    /** Слово TRUE/FALSE (в любом регистре) вне строковых литералов {@code "…"} и имён листов {@code '…'}. */
    static boolean hasBooleanWord(String formula)
    {
        int i = 0;
        int length = formula.length();

        while (i < length)
        {
            char c = formula.charAt(i);

            if (c == '"' || c == '\'')
            {
                int close = i + 1;

                while (close < length)
                {
                    if (formula.charAt(close) == c)
                    {
                        // Удвоенная кавычка внутри литерала — это сама кавычка
                        if (close + 1 < length && formula.charAt(close + 1) == c)
                        {
                            close += 2;
                            continue;
                        }

                        break;
                    }

                    close++;
                }

                i = close + 1;
                continue;
            }

            if (Character.isLetter(c) || c == '_')
            {
                int end = i;

                while (end < length && (Character.isLetterOrDigit(formula.charAt(end)) || formula.charAt(end) == '_'
                        || formula.charAt(end) == '.'))
                {
                    end++;
                }

                String word = formula.substring(i, end);

                if (isBooleanWord(word))
                {
                    return true;
                }

                i = end;
                continue;
            }

            i++;
        }

        return false;
    }

    private static boolean isBooleanWord(String word)
    {
        String upper = word.strip().toUpperCase(Locale.ROOT);

        return upper.equals("TRUE") || upper.equals("FALSE") || upper.equals("ИСТИНА") || upper.equals("ЛОЖЬ");
    }

    private static <T> int indexOf(List<T> list, T value)
    {
        int index = list.indexOf(value);

        if (index >= 0)
        {
            return index;
        }

        list.add(value);

        return list.size() - 1;
    }

    // Поиск начиная с from: зарезервированные места в начале списка не сравниваются
    private static <T> int indexOfFrom(List<T> list, T value, int from)
    {
        for (int i = from; i < list.size(); i++)
        {
            if (list.get(i).equals(value))
            {
                return i;
            }
        }

        list.add(value);

        return list.size() - 1;
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Лист книги. Колонки и строки — с единицы, как в Excel. */
    public final class Sheet
    {
        private final String name;
        private final int number;
        private final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
        private final TreeMap<Integer, Double> heights = new TreeMap<>();
        private final TreeMap<Integer, Column> columns = new TreeMap<>();
        private final List<int[]> merges = new ArrayList<>();
        private final List<Validation> validations = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private final List<Picture> pictures = new ArrayList<>();
        private final List<BarChart> charts = new ArrayList<>();
        private int frozenRows;
        private int tabColor = -1;
        private int @Nullable [] filter;
        private boolean gridLines = true;
        private int canvasCols;
        private int canvasRows;
        private int canvasStyle;

        private Sheet(String name, int number)
        {
            this.name = name;
            this.number = number;
        }

        public String name()
        {
            return this.name;
        }

        /** Ссылка с именем листа для диаграмм и имён: {@code 'Лист'!$J$4:$J$15}. */
        public String reference(int col1, int row1, int col2, int row2)
        {
            return "'" + this.name.replace("'", "''") + "'!" + absolute(col1, row1, col2, row2);
        }

        private String reference(int[] box)
        {
            return this.reference(box[0], box[1], box[2], box[3]);
        }

        /** Текст, который пишет сам генератор (заголовки, статусы): проходит проверку на TRUE/FALSE. */
        public void text(int col, int row, String value, int style)
        {
            this.put(col, row, new Cell(Kind.TEXT, value.strip(), style));
        }

        /** Данные игрока (ники, названия предметов): проверку на TRUE/FALSE не проходят. */
        public void data(int col, int row, String value, int style)
        {
            this.put(col, row, new Cell(Kind.DATA, value.strip(), style));
        }

        public void number(int col, int row, double value, int style)
        {
            this.put(col, row, new Cell(Kind.NUMBER, XlsxWriter.number(value), style));
        }

        /** Формула без ведущего «=», синтаксис en-US (запятые, английские имена функций). */
        public void formula(int col, int row, String formula, int style)
        {
            this.put(col, row, new Cell(Kind.FORMULA, formula, style));
        }

        public void blank(int col, int row, int style)
        {
            this.put(col, row, new Cell(Kind.BLANK, "", style));
        }

        /** Ширина в «символах» Calibri 11, как в Excel. */
        public void column(int col, double width, boolean hidden)
        {
            this.columns.put(col, new Column(width, hidden));
        }

        public void rowHeight(int row, double points)
        {
            this.heights.put(row, points);
        }

        public void freezeRows(int count)
        {
            this.frozenRows = Math.max(0, count);
        }

        public void tabColor(int rgb)
        {
            this.tabColor = rgb & 0xFFFFFF;
        }

        /** Без серой сетки листа (вид «Сетка» выключен). */
        public void hideGridLines()
        {
            this.gridLines = false;
        }

        /**
         * Фон пустой области: колонки 1…{@code lastCol} получают стиль по умолчанию, а каждая
         * ещё не занятая ячейка прямоугольника A1…{@code lastCol}{@code lastRow} пишется пустой
         * со стилем {@code style}. Стиль «Обычный» книги ({@link XlsxWriter#canvas}) красит лист в
         * Excel целиком; явные ячейки и стили колонок — для программ, которые стиль по умолчанию
         * к пустым ячейкам не применяют (импорт Google).
         */
        public void canvas(int lastCol, int lastRow, int style)
        {
            this.canvasCols = Math.max(0, lastCol);
            this.canvasRows = Math.max(0, lastRow);
            this.canvasStyle = style;
        }

        /** Объединение; значение и стиль — в левой верхней, остальные ячейки — пустые с тем же стилем. */
        public void merge(int col1, int row1, int col2, int row2, int style)
        {
            this.merges.add(new int[] {col1, row1, col2, row2});

            for (int r = row1; r <= row2; r++)
            {
                for (int c = col1; c <= col2; c++)
                {
                    if (r != row1 || c != col1)
                    {
                        this.blank(c, r, style);
                    }
                }
            }
        }

        public void autoFilter(int col1, int row1, int col2, int row2)
        {
            this.filter = new int[] {col1, row1, col2, row2};
        }

        /**
         * Выпадающий список из диапазона ячеек этого листа. Список из значений ({@code "a,b"}) не
         * используется: импорт Google его не переносит, а диапазон — переносит.
         */
        public void listValidation(String sqref, String sourceRange, String title, String error)
        {
            this.validations.add(new Validation(sqref, sourceRange, title, error));
        }

        /** Правило условного форматирования «формула»; приоритет — порядок добавления на листе. */
        public void conditional(String sqref, String formula, int dxf, boolean stopIfTrue)
        {
            this.rules.add(new Rule(sqref, formula, dxf, stopIfTrue, this.rules.size() + 1));
        }

        /** Картинка PNG поверх ячейки (якорь — одна ячейка, размер в пикселях 96 dpi). */
        public void image(int col, int row, byte[] png, int widthPx, int heightPx, int offsetXPx, int offsetYPx,
                          String description)
        {
            this.pictures.add(new Picture(col, row, widthPx, heightPx, offsetXPx, offsetYPx,
                    XlsxWriter.this.mediaIndex(png), description));
        }

        public void barChart(BarChart chart)
        {
            this.charts.add(chart);
        }

        private boolean hasDrawing()
        {
            return ! this.pictures.isEmpty() || ! this.charts.isEmpty();
        }

        private void put(int col, int row, Cell cell)
        {
            if (col < 1 || row < 1)
            {
                throw new IllegalArgumentException("cell " + col + ":" + row);
            }

            this.rows.computeIfAbsent(row, r -> new TreeMap<>()).put(col, cell);
        }

        private void verify()
        {
            this.rows.forEach((row, cells) -> cells.forEach((col, cell) ->
            {
                boolean bad = switch (cell.kind())
                {
                    case TEXT -> isBooleanWord(cell.value());
                    case FORMULA -> hasBooleanWord(cell.value());
                    default -> false;
                };

                if (bad)
                {
                    throw new IllegalStateException("TRUE/FALSE in " + this.name + "!" + cell(col, row) + ": " + cell.value());
                }
            }));

            for (Validation validation : this.validations)
            {
                if (hasBooleanWord(validation.source()))
                {
                    throw new IllegalStateException("TRUE/FALSE in validation " + validation.sqref());
                }

                this.verifyListSource(validation);
            }

            for (Rule rule : this.rules)
            {
                if (hasBooleanWord(rule.formula()))
                {
                    throw new IllegalStateException("TRUE/FALSE in rule " + rule.sqref() + ": " + rule.formula());
                }
            }
        }

        // Значения, на которые ссылается список, — тоже не булевы заглушки
        private void verifyListSource(Validation validation)
        {
            String plain = validation.source().replace("$", "");
            int colon = plain.indexOf(':');

            if (colon < 0 || plain.indexOf('!') >= 0)
            {
                return;
            }

            int[] from = parseCell(plain.substring(0, colon));
            int[] to = parseCell(plain.substring(colon + 1));

            for (int r = from[1]; r <= to[1]; r++)
            {
                TreeMap<Integer, Cell> cells = this.rows.get(r);

                for (int c = from[0]; cells != null && c <= to[0]; c++)
                {
                    Cell cell = cells.get(c);

                    if (cell != null && cell.kind() != Kind.NUMBER && isBooleanWord(cell.value())
                            && cell.kind() != Kind.DATA)
                    {
                        throw new IllegalStateException("TRUE/FALSE in list source " + validation.source());
                    }
                }
            }
        }

        private String xml()
        {
            int maxCol = Math.max(1, this.canvasCols);
            int maxRow = Math.max(1, this.canvasRows);

            for (Map.Entry<Integer, TreeMap<Integer, Cell>> entry : this.rows.entrySet())
            {
                maxRow = Math.max(maxRow, entry.getKey());
                maxCol = Math.max(maxCol, entry.getValue().lastKey());
            }

            StringBuilder xml = new StringBuilder(HEADER).append("<worksheet xmlns=\"").append(NS_MAIN)
                    .append("\" xmlns:r=\"").append(NS_R).append("\">");

            if (this.tabColor >= 0)
            {
                xml.append("<sheetPr><tabColor rgb=\"").append(argb(this.tabColor)).append("\"/></sheetPr>");
            }

            xml.append("<dimension ref=\"").append(range(1, 1, maxCol, maxRow)).append("\"/>")
                    .append("<sheetViews><sheetView").append(this.gridLines ? "" : " showGridLines=\"0\"")
                    .append(this.number == 1 ? " tabSelected=\"1\"" : "").append(" workbookViewId=\"0\">");

            if (this.frozenRows > 0)
            {
                String top = cell(1, this.frozenRows + 1);
                xml.append("<pane ySplit=\"").append(this.frozenRows).append("\" topLeftCell=\"").append(top)
                        .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                        .append("<selection pane=\"bottomLeft\" activeCell=\"").append(top).append("\" sqref=\"")
                        .append(top).append("\"/>");
            }

            xml.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>");

            this.appendColumns(xml);
            xml.append("<sheetData>");
            TreeMap<Integer, Boolean> allRows = new TreeMap<>();
            this.rows.keySet().forEach(r -> allRows.put(r, true));
            this.heights.keySet().forEach(r -> allRows.put(r, true));

            for (int r = 1; r <= this.canvasRows; r++)
            {
                allRows.put(r, true);
            }

            for (int row : allRows.keySet())
            {
                xml.append("<row r=\"").append(row).append('"');
                Double height = this.heights.get(row);

                if (height != null)
                {
                    xml.append(" ht=\"").append(XlsxWriter.number(height)).append("\" customHeight=\"1\"");
                }

                xml.append('>');
                TreeMap<Integer, Cell> cells = this.rows.get(row);
                int pad = row <= this.canvasRows ? this.canvasCols : 0;
                int last = Math.max(pad, cells == null ? 0 : cells.lastKey());

                for (int col = 1; col <= last; col++)
                {
                    Cell cell = cells == null ? null : cells.get(col);

                    if (cell != null)
                    {
                        this.appendCell(xml, col, row, cell);
                    }
                    else if (col <= pad)
                    {
                        this.appendCell(xml, col, row, new Cell(Kind.BLANK, "", this.canvasStyle));
                    }
                }

                xml.append("</row>");
            }

            xml.append("</sheetData>");

            if (this.filter != null)
            {
                xml.append("<autoFilter ref=\"").append(range(this.filter[0], this.filter[1], this.filter[2], this.filter[3]))
                        .append("\"/>");
            }

            if (! this.merges.isEmpty())
            {
                xml.append("<mergeCells count=\"").append(this.merges.size()).append("\">");

                for (int[] box : this.merges)
                {
                    xml.append("<mergeCell ref=\"").append(range(box[0], box[1], box[2], box[3])).append("\"/>");
                }

                xml.append("</mergeCells>");
            }

            // Соседние правила с одним диапазоном — в одном элементе
            for (int i = 0; i < this.rules.size(); )
            {
                String sqref = this.rules.get(i).sqref();
                xml.append("<conditionalFormatting sqref=\"").append(sqref).append("\">");

                while (i < this.rules.size() && this.rules.get(i).sqref().equals(sqref))
                {
                    Rule rule = this.rules.get(i++);
                    xml.append("<cfRule type=\"expression\" dxfId=\"").append(rule.dxf()).append("\" priority=\"")
                            .append(rule.priority()).append('"').append(rule.stop() ? " stopIfTrue=\"1\"" : "")
                            .append("><formula>").append(escape(rule.formula())).append("</formula></cfRule>");
                }

                xml.append("</conditionalFormatting>");
            }

            if (! this.validations.isEmpty())
            {
                xml.append("<dataValidations count=\"").append(this.validations.size()).append("\">");

                for (Validation v : this.validations)
                {
                    // showDropDown="1" в OOXML прячет стрелку списка — атрибут не пишем
                    xml.append("<dataValidation type=\"list\" allowBlank=\"1\" showErrorMessage=\"1\" errorTitle=\"")
                            .append(escape(v.title())).append("\" error=\"").append(escape(v.error()))
                            .append("\" sqref=\"").append(v.sqref()).append("\"><formula1>").append(escape(v.source()))
                            .append("</formula1></dataValidation>");
                }

                xml.append("</dataValidations>");
            }

            xml.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");

            if (this.hasDrawing())
            {
                xml.append("<drawing r:id=\"rId1\"/>");
            }

            return xml.append("</worksheet>").toString();
        }

        // Колонки по возрастанию без перекрытий: заданные — каждая своей записью, промежутки до
        // canvasCols — одной записью с шириной по умолчанию; у всех стиль фона, если он задан
        private void appendColumns(StringBuilder xml)
        {
            int last = Math.max(this.canvasCols, this.columns.isEmpty() ? 0 : this.columns.lastKey());

            if (last == 0)
            {
                return;
            }

            String style = this.canvasCols > 0 && this.canvasStyle != 0 ? " style=\"" + this.canvasStyle + "\"" : "";
            xml.append("<cols>");

            for (int col = 1; col <= last; )
            {
                Column column = this.columns.get(col);

                if (column != null)
                {
                    xml.append("<col min=\"").append(col).append("\" max=\"").append(col).append("\" width=\"")
                            .append(XlsxWriter.number(column.width())).append("\" customWidth=\"1\"")
                            .append(column.hidden() ? " hidden=\"1\"" : "").append(col <= this.canvasCols ? style : "")
                            .append("/>");
                    col++;
                    continue;
                }

                int to = col;

                while (to + 1 <= this.canvasCols && ! this.columns.containsKey(to + 1))
                {
                    to++;
                }

                if (col <= this.canvasCols)
                {
                    xml.append("<col min=\"").append(col).append("\" max=\"").append(to).append("\" width=\"")
                            .append(XlsxWriter.number(DEFAULT_COLUMN_WIDTH)).append('"').append(style).append("/>");
                }

                col = to + 1;
            }

            xml.append("</cols>");
        }

        private void appendCell(StringBuilder xml, int col, int row, Cell cell)
        {
            xml.append("<c r=\"").append(cell(col, row)).append('"');

            if (cell.style() != 0)
            {
                xml.append(" s=\"").append(cell.style()).append('"');
            }

            switch (cell.kind())
            {
                case TEXT, DATA ->
                {
                    if (cell.value().isEmpty())
                    {
                        xml.append("/>");
                    }
                    else
                    {
                        xml.append(" t=\"s\"><v>").append(XlsxWriter.this.stringIndex(cell.value())).append("</v></c>");
                    }
                }
                case NUMBER -> xml.append("><v>").append(cell.value()).append("</v></c>");
                case FORMULA -> xml.append("><f>").append(escape(cell.value())).append("</f></c>");
                case BLANK -> xml.append("/>");
            }
        }

        private String drawingXml(int chartsBefore, StringBuilder rels)
        {
            StringBuilder xml = new StringBuilder(HEADER).append("<xdr:wsDr xmlns:xdr=\"").append(NS_XDR)
                    .append("\" xmlns:a=\"").append(NS_A).append("\" xmlns:r=\"").append(NS_R).append("\">");
            int nextRel = 1;
            int shapeId = 1;

            for (int i = 0; i < this.charts.size(); i++)
            {
                BarChart chart = this.charts.get(i);
                String rel = "rId" + nextRel++;
                rels.append("<Relationship Id=\"").append(rel).append("\" Type=\"").append(REL)
                        .append("chart\" Target=\"../charts/chart").append(chartsBefore + i + 1).append(".xml\"/>");
                xml.append("<xdr:oneCellAnchor>").append(from(chart.col(), chart.row(), 0, 0))
                        .append(ext(chart.widthPx(), chart.heightPx()))
                        .append("<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"").append(shapeId)
                        .append("\" name=\"Chart ").append(shapeId).append("\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>")
                        .append("<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic>")
                        .append("<a:graphicData uri=\"").append(NS_C).append("\"><c:chart xmlns:c=\"").append(NS_C)
                        .append("\" r:id=\"").append(rel).append("\"/></a:graphicData></a:graphic></xdr:graphicFrame>")
                        .append("<xdr:clientData/></xdr:oneCellAnchor>");
                shapeId++;
            }

            Map<Integer, String> mediaRels = new LinkedHashMap<>();

            for (Picture picture : this.pictures)
            {
                String rel = mediaRels.get(picture.media());

                if (rel == null)
                {
                    rel = "rId" + nextRel++;
                    mediaRels.put(picture.media(), rel);
                    rels.append("<Relationship Id=\"").append(rel).append("\" Type=\"").append(REL)
                            .append("image\" Target=\"../media/image").append(picture.media() + 1).append(".png\"/>");
                }

                xml.append("<xdr:oneCellAnchor>")
                        .append(from(picture.col(), picture.row(), picture.offsetXPx(), picture.offsetYPx()))
                        .append(ext(picture.widthPx(), picture.heightPx()))
                        .append(XlsxWriter.this.pictureXml(picture, shapeId, rel))
                        .append(XlsxWriter.this.profile == Profile.GOOGLE
                                ? "<xdr:clientData fLocksWithSheet=\"0\"/>"
                                : "<xdr:clientData/>")
                        .append("</xdr:oneCellAnchor>");
                shapeId++;
            }

            return xml.append("</xdr:wsDr>").toString();
        }
    }

    private String pictureXml(Picture picture, int shapeId, String rel)
    {
        String cx = Long.toString((long) picture.widthPx() * EMU_PER_PX);
        String cy = Long.toString((long) picture.heightPx() * EMU_PER_PX);

        if (this.profile == Profile.GOOGLE)
        {
            // Та же запись, что Google делает сам, выгружая картинку поверх ячеек
            return "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"" + shapeId + "\" name=\"image" + shapeId + ".png\" title=\""
                    + escape(picture.description()) + "\"/><xdr:cNvPicPr preferRelativeResize=\"0\"/></xdr:nvPicPr>"
                    + "<xdr:blipFill><a:blip cstate=\"print\" r:embed=\"" + rel + "\"/><a:stretch><a:fillRect/></a:stretch>"
                    + "</xdr:blipFill><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></xdr:spPr></xdr:pic>";
        }

        return "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"" + shapeId + "\" name=\"Icon " + shapeId + "\" descr=\""
                + escape(picture.description()) + "\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr>"
                + "</xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"" + rel + "\"/><a:stretch><a:fillRect/></a:stretch>"
                + "</xdr:blipFill><xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy
                + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>";
    }

    // Якорь в EMU; колонка и строка в разметке рисунка считаются с нуля
    private static String from(int col, int row, int offsetXPx, int offsetYPx)
    {
        return "<xdr:from><xdr:col>" + (col - 1) + "</xdr:col><xdr:colOff>" + (long) offsetXPx * EMU_PER_PX
                + "</xdr:colOff><xdr:row>" + (row - 1) + "</xdr:row><xdr:rowOff>" + (long) offsetYPx * EMU_PER_PX
                + "</xdr:rowOff></xdr:from>";
    }

    private static String ext(int widthPx, int heightPx)
    {
        return "<xdr:ext cx=\"" + (long) widthPx * EMU_PER_PX + "\" cy=\"" + (long) heightPx * EMU_PER_PX + "\"/>";
    }

    private static int[] parseCell(String ref)
    {
        int col = 0;
        int i = 0;

        while (i < ref.length() && Character.isLetter(ref.charAt(i)))
        {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }

        return new int[] {col, Integer.parseInt(ref.substring(i))};
    }
}
