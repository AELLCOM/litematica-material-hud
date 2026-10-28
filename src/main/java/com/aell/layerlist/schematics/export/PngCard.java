package com.aell.layerlist.schematics.export;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Картинка-карточка списка для чата и Discord: шапка на фирменной плашке канона
 * ({@code #6D28D9 → #3F1178} под 45°), заголовок пиксельным Press Start 2P, строки с иконками
 * предметов и числами JetBrains Mono, не больше 60 строк на картинку; страницы — {@code 1.png}, {@code 2.png}…
 * в своей папке {@code <схема>/} (0.0.27).
 *
 * <p>Рисуется Java2D без экрана: игра запускается с {@code java.awt.headless=true}, а
 * {@code BufferedImage}, шрифты из файла и {@code ImageIO} в этом режиме работают. Шрифты — OFL,
 * лежат в jar рядом с текстом лицензии ({@code assets/layerlist/fonts/}).
 */
@Environment(EnvType.CLIENT)
final class PngCard
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    static final int ROWS_PER_PAGE = 60;

    private static final int WIDTH = 800;
    private static final int PAD = 24;
    private static final int HEADER_H = 112;
    private static final int COLUMNS_H = 30;
    private static final int ROW_H = 40;
    private static final int FOOTER_H = 44;
    private static final int ICON = 32;
    private static final int RADIUS = 16;
    private static final int NAME_X = PAD + ICON + 14;
    private static final int COUNT_RIGHT = 560;
    private static final int BREAKDOWN_X = 580;
    /** Кегли Press Start 2P — кратные 8 px, иначе пиксельный шрифт мылится. */
    private static final int[] TITLE_SIZES = {16, 12, 8};

    private static final String FONT_DIR = "/assets/layerlist/fonts/";

    @Nullable private static Font pixelFont;
    @Nullable private static Font monoFont;

    private PngCard() {}

    /**
     * Своя папка на выгрузку, названная по схеме ({@code <схема>/}, занята — {@code <схема> (2)/}…), в ней
     * страницы {@code 1.png}, {@code 2.png}… — и одна страница тоже {@code 1.png}. В прежнюю папку не пишем
     * никогда: там могут лежать картинки, уже отправленные в чат.
     */
    static List<Path> write(ExportData data, Path dir, ExportIcons icons) throws IOException
    {
        int pages = pages(data);
        // Пустой суффикс: занято ли само имя — папкой или файлом
        Path folder = dir.resolve(ExportFiles.freeBase(dir, data.fileName(), ""));
        Files.createDirectories(folder);
        List<Path> files = new ArrayList<>(pages);

        for (int page = 0; page < pages; page++)
        {
            BufferedImage image = page(data, page, pages, icons);
            Path target = folder.resolve((page + 1) + ".png");

            ExportFiles.write(target, out ->
            {
                if (! ImageIO.write(image, "png", out))
                {
                    throw new IOException("no PNG writer");
                }
            });

            files.add(target);
        }

        return files;
    }

    static int pages(ExportData data)
    {
        return Math.max(1, (data.lines().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    static BufferedImage page(ExportData data, int page, int pages, ExportIcons icons)
    {
        List<ExportData.Line> lines = data.lines();
        int from = page * ROWS_PER_PAGE;
        int to = Math.min(lines.size(), from + ROWS_PER_PAGE);
        int height = HEADER_H + COLUMNS_H + Math.max(1, to - from) * ROW_H + FOOTER_H;

        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            g.setColor(color(CanonPalette.VIOLET_NIGHT));
            g.fillRect(0, 0, WIDTH, height);
            header(g, data, page, pages);
            columns(g);

            for (int i = from; i < to; i++)
            {
                row(g, lines.get(i), HEADER_H + COLUMNS_H + (i - from) * ROW_H, (i - from) % 2 == 1, icons);
            }

            footer(g, height, page, pages);
        }
        finally
        {
            g.dispose();
        }

        roundCorners(image);

        return image;
    }

    private static void header(Graphics2D g, ExportData data, int page, int pages)
    {
        // Градиент строго под 45°, как фон знака: t = (x + y) / (W + H)
        int diagonal = (WIDTH + HEADER_H) / 2;
        g.setPaint(new GradientPaint(0, 0, color(CanonPalette.VIOLET), diagonal, diagonal,
                color(CanonPalette.VIOLET_DEEP)));
        g.fillRect(0, 0, WIDTH, HEADER_H);

        String title = ExportText.t("sheet.title", data.title());
        Font titleFont = titleFont(g, title);
        boolean pixel = titleFont.getFamily(Locale.ROOT).equals(pixel().getFamily(Locale.ROOT));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, pixel
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(titleFont);
        g.setColor(color(CanonPalette.WHITE));
        g.drawString(fit(g, title, WIDTH - 2 * PAD), PAD, 46);

        textMode(g);
        g.setFont(mono(14f, false));
        g.setColor(color(CanonPalette.SUBTITLE));
        g.drawString(fit(g, ExportText.meta(data), WIDTH - 2 * PAD), PAD, 76);

        String summary = ExportText.summary(data);

        if (pages > 1)
        {
            summary += ExportText.t("png.page", page + 1, pages);
        }

        g.setFont(mono(13f, false));
        g.setColor(color(CanonPalette.TEXT_ON_DARK));
        g.drawString(fit(g, summary, WIDTH - 2 * PAD), PAD, 98);
    }

    private static void columns(Graphics2D g)
    {
        int top = HEADER_H;
        textMode(g);
        g.setFont(mono(11f, false));
        g.setColor(color(CanonPalette.OVERLINE));
        int baseline = top + 20;
        g.drawString(ExportText.t("png.col.item").toUpperCase(Locale.ROOT), NAME_X, baseline);
        String count = ExportText.t("png.col.count").toUpperCase(Locale.ROOT);
        g.drawString(count, COUNT_RIGHT - g.getFontMetrics().stringWidth(count), baseline);
        g.drawString(ExportText.t("png.col.breakdown").toUpperCase(Locale.ROOT), BREAKDOWN_X, baseline);

        g.setColor(withAlpha(CanonPalette.GRID, 0.45f));
        g.fillRect(PAD, top + COLUMNS_H - 1, WIDTH - 2 * PAD, 1);
    }

    private static void row(Graphics2D g, ExportData.Line line, int top, boolean zebra, ExportIcons icons)
    {
        if (zebra)
        {
            g.setColor(color(CanonPalette.VIOLET_ZEBRA));
            g.fillRect(0, top, WIDTH, ROW_H);
        }

        icon(g, icons.png(line.row()), PAD, top + (ROW_H - ICON) / 2);

        textMode(g);
        int baseline = top + 26;
        g.setFont(mono(15f, false));
        g.setColor(color(CanonPalette.BADGE));
        g.drawString(fit(g, line.name(), COUNT_RIGHT - 110 - NAME_X), NAME_X, baseline);

        String count = ExportText.count(line.quantity());
        g.setFont(mono(15f, true));
        g.setColor(color(coverage(line)));
        g.drawString(count, COUNT_RIGHT - g.getFontMetrics().stringWidth(count), baseline);

        g.setFont(mono(13f, false));
        g.setColor(color(CanonPalette.TEXT_ON_DARK));
        g.drawString(fit(g, line.breakdown(), WIDTH - PAD - BREAKDOWN_X), BREAKDOWN_X, baseline);
    }

    /** Цвет количества — как в HUD: зелёный — всё есть, янтарный — часть, красный — ничего. */
    static int coverage(ExportData.Line line)
    {
        long covered = line.covered();

        if (covered >= line.quantity())
        {
            return CanonPalette.GREEN_TOP;
        }

        return covered > 0L ? CanonPalette.AMBER_TOP : CanonPalette.RED_TOP;
    }

    private static void footer(Graphics2D g, int height, int page, int pages)
    {
        int top = height - FOOTER_H;
        g.setColor(withAlpha(CanonPalette.GRID, 0.45f));
        g.fillRect(PAD, top, WIDTH - 2 * PAD, 1);

        textMode(g);
        g.setFont(mono(12f, false));
        FontMetrics metrics = g.getFontMetrics();
        int baseline = top + 27;
        int x = PAD;

        String[] labels = {ExportText.t("png.legend.have"), ExportText.t("png.legend.part"), ExportText.t("png.legend.none")};
        int[] tones = {CanonPalette.GREEN_TOP, CanonPalette.AMBER_TOP, CanonPalette.RED_TOP};

        for (int i = 0; i < labels.length; i++)
        {
            g.setColor(color(tones[i]));
            g.fill(new Ellipse2D.Float(x, baseline - 9, 9, 9));
            x += 15;
            g.setColor(color(CanonPalette.OVERLINE));
            g.drawString(labels[i], x, baseline);
            x += metrics.stringWidth(labels[i]) + 18;
        }

        String brand = pages > 1 ? ExportText.t("png.brand") + " · " + (page + 1) + "/" + pages : ExportText.t("png.brand");
        g.drawString(brand, WIDTH - PAD - metrics.stringWidth(brand), baseline);
    }

    private static void icon(Graphics2D g, byte @Nullable [] png, int x, int y)
    {
        BufferedImage picture = null;

        if (png != null)
        {
            try
            {
                picture = ImageIO.read(new ByteArrayInputStream(png));
            }
            catch (IOException e)
            {
                LOGGER.debug("Export: broken item icon PNG", e);
            }
        }

        if (picture != null)
        {
            // Иконка крупнее места на карточке: уменьшаем с усреднением, иначе рёбра блоков рвутся лесенкой
            Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, picture.getWidth() > ICON
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                    : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(picture, x, y, ICON, ICON, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            return;
        }

        // Без иконки — пустая плашка того же размера, чтобы колонки не плясали
        RoundRectangle2D plate = new RoundRectangle2D.Float(x + 2, y + 2, ICON - 4, ICON - 4, 6, 6);
        g.setColor(color(CanonPalette.VIOLET_DEEP));
        g.fill(plate);
        g.setStroke(new BasicStroke(1f));
        g.setColor(withAlpha(CanonPalette.GRID, 0.6f));
        g.draw(plate);
    }

    // Скругление углов плашки по канону: маска с мягким краем, снаружи — прозрачно
    private static void roundCorners(BufferedImage image)
    {
        BufferedImage mask = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = mask.createGraphics();

        try
        {
            m.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            m.setColor(Color.WHITE);
            m.fill(new RoundRectangle2D.Float(0, 0, image.getWidth(), image.getHeight(), RADIUS * 2, RADIUS * 2));
        }
        finally
        {
            m.dispose();
        }

        Graphics2D g = image.createGraphics();

        try
        {
            g.setComposite(AlphaComposite.DstIn);
            g.drawImage(mask, 0, 0, null);
        }
        finally
        {
            g.dispose();
        }
    }

    private static Font titleFont(Graphics2D g, String title)
    {
        Font base = pixel();

        if (base.canDisplayUpTo(title) == -1)
        {
            for (int size : TITLE_SIZES)
            {
                Font font = base.deriveFont((float) size);

                if (g.getFontMetrics(font).stringWidth(title) <= WIDTH - 2 * PAD)
                {
                    return font;
                }
            }

            return base.deriveFont((float) TITLE_SIZES[TITLE_SIZES.length - 1]);
        }

        // Знаков, которых нет в пиксельном шрифте (иероглифы в названии), — моноширинным
        return mono(20f, true);
    }

    private static void textMode(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /** Обрезка по ширине с «…» в конце. */
    static String fit(Graphics2D g, String text, int width)
    {
        FontMetrics metrics = g.getFontMetrics();

        if (metrics.stringWidth(text) <= width)
        {
            return text;
        }

        String ellipsis = "…";
        int end = text.length();

        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > width)
        {
            end--;

            if (end > 0 && Character.isLowSurrogate(text.charAt(end)))
            {
                end--;
            }
        }

        return text.substring(0, end).stripTrailing() + ellipsis;
    }

    private static synchronized Font pixel()
    {
        if (pixelFont == null)
        {
            pixelFont = load("PressStart2P.ttf", new Font(Font.MONOSPACED, Font.BOLD, 16));
        }

        return pixelFont;
    }

    private static Font mono(float size, boolean bold)
    {
        Font base;

        synchronized (PngCard.class)
        {
            if (monoFont == null)
            {
                monoFont = load("JetBrainsMono.ttf", new Font(Font.MONOSPACED, Font.PLAIN, 14));
            }

            base = monoFont;
        }

        return base.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static Font load(String file, Font fallback)
    {
        try (InputStream in = PngCard.class.getResourceAsStream(FONT_DIR + file))
        {
            if (in == null)
            {
                throw new IOException("missing " + FONT_DIR + file);
            }

            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
        catch (IOException | FontFormatException | RuntimeException e)
        {
            LOGGER.warn("Export: could not load font {}, using a system monospace font", file, e);
            return fallback;
        }
    }

    private static Color color(int rgb)
    {
        return new Color(rgb & 0xFFFFFF);
    }

    private static Color withAlpha(int rgb, float alpha)
    {
        return new Color((Math.round(alpha * 255) << 24) | (rgb & 0xFFFFFF), true);
    }
}
