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
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Рисунок постера-превью ({@link PreviewPoster}) — только Java2D, без игры: тексты уже переведены,
 * модель уже снята. Вид — из семьи PNG-карточки и канона Aell_ v6: вся картинка — фирменная плашка
 * {@code #6D28D9 → #3F1178} под 45° (§31: {@code t = (x/(W−1) + y/(H−1)) / 2}) со скруглёнными углами,
 * в ней тёмная «сцена» цвета тела карточки {@code #2A0B52} с тонкой изометрической сеткой (те же ±30°,
 * что у рёбер блоков в изометрии превью), модель — по центру сцены. Над сценой — название пиксельным
 * Press Start 2P (кегли кратны 8, без переноса, §34) и подзаголовок JetBrains Mono; под ней — подпись
 * вида и знак «Material HUD», как в подвале карточки.
 */
@Environment(EnvType.CLIENT)
final class PosterPainter
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    static final int WIDTH = 1920;
    static final int HEIGHT = 1200;

    /** Толщина фиолетовой рамки вокруг сцены и радиус углов плашки (как у карточки: 2 % ширины). */
    private static final int PAD = 40;
    private static final int RADIUS = 40;
    private static final int STAGE_RADIUS = 24;
    /** Сцена: от низа шапки до верха подвала. */
    private static final int STAGE_TOP = 178;
    private static final int STAGE_BOTTOM = HEIGHT - 72;
    /** Поле между краем сцены и картинкой модели. */
    private static final int STAGE_INSET = 28;

    static final int MODEL_X = PAD + STAGE_INSET;
    static final int MODEL_Y = STAGE_TOP + STAGE_INSET;
    /** Область модели: снимок превью заказывается ровно такого размера и ложится без масштаба. */
    static final int MODEL_W = WIDTH - 2 * MODEL_X;
    static final int MODEL_H = STAGE_BOTTOM - STAGE_TOP - 2 * STAGE_INSET;

    private static final int TITLE_BASELINE = 84;
    private static final int SUBTITLE_BASELINE = 146;
    private static final int FOOTER_BASELINE = HEIGHT - 28;
    /** Кегли Press Start 2P — кратные 8 px, иначе пиксельный шрифт мылится; сбрасываются по очереди. */
    private static final int[] TITLE_SIZES = {40, 32, 24};
    private static final float SUBTITLE_SIZE = 28.0F;
    private static final float FOOTER_SIZE = 22.0F;
    private static final float PLACEHOLDER_SIZE = 30.0F;
    private static final int BRAND_ICON = 32;

    /** Изометрическая сетка сцены: шаг, каждая 4-я линия — акцентная; альфы — вдвое ниже баннерных. */
    private static final float GRID_STEP = 72.0F;
    private static final float GRID_ALPHA = 0.10F;
    private static final float GRID_ACCENT_ALPHA = 0.20F;
    private static final double TAN30 = Math.tan(Math.toRadians(30.0));

    private static final String FONT_DIR = "/assets/layerlist/fonts/";
    private static final String ICON = "/assets/layerlist/icon.png";

    @Nullable private static Font pixelFont;
    @Nullable private static Font monoFont;
    @Nullable private static BufferedImage brandIcon;
    private static boolean iconMissing;

    /** Кусок подзаголовка; {@code accent} — янтарём (пометка «упрощённый вид»). */
    record Segment(String text, boolean accent) {}

    /**
     * Все тексты постера, уже переведённые.
     *
     * @param title       название схемы
     * @param subtitle    подзаголовок кусками, разделитель « · » ставит рисунок
     * @param footer      подпись слева в подвале (вид и дата)
     * @param brand       знак справа в подвале
     * @param placeholder что написать на сцене вместо модели; {@code null} — модель есть
     */
    record Texts(String title, List<Segment> subtitle, String footer, String brand, @Nullable String placeholder) {}

    private PosterPainter() {}

    /** Постер {@value #WIDTH}×{@value #HEIGHT}; {@code model} — {@link #MODEL_W}×{@link #MODEL_H} или {@code null}. */
    static BufferedImage paint(Texts texts, @Nullable BufferedImage model)
    {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            plate(g);
            stage(g);

            if (model != null && texts.placeholder() == null)
            {
                drawModel(g, model);
            }
            else
            {
                placeholder(g, texts.placeholder() != null ? texts.placeholder() : "");
            }

            title(g, texts.title());
            subtitle(g, texts.subtitle());
            footer(g, texts.footer(), texts.brand());
        }
        finally
        {
            g.dispose();
        }

        return image;
    }

    /**
     * Запасной постер на самый крайний случай (не загрузились даже шрифты): плашка и пустая сцена без
     * текста. Выгрузка картинкой не должна падать из-за превью.
     */
    static BufferedImage bare()
    {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            plate(g);
            stage(g);
        }
        finally
        {
            g.dispose();
        }

        return image;
    }

    // ===== Слои =====

    /** Плашка: градиент канона строго по долям сторон (§31), снаружи скругления — прозрачно. */
    private static void plate(Graphics2D g)
    {
        // Вектор градиента подобран так, что t = x/(2W) + y/(2H): (0,0) → 0, (W,H) → 1
        float norm = (float) WIDTH * WIDTH + (float) HEIGHT * HEIGHT;
        float dx = 2.0F * WIDTH * HEIGHT * HEIGHT / norm;
        float dy = 2.0F * WIDTH * WIDTH * HEIGHT / norm;

        g.setPaint(new GradientPaint(0, 0, color(CanonPalette.VIOLET), dx, dy, color(CanonPalette.VIOLET_DEEP)));
        g.fill(new RoundRectangle2D.Float(0, 0, WIDTH, HEIGHT, RADIUS * 2, RADIUS * 2));
    }

    private static Shape stageShape()
    {
        return new RoundRectangle2D.Float(PAD, STAGE_TOP, WIDTH - 2 * PAD, STAGE_BOTTOM - STAGE_TOP,
                STAGE_RADIUS * 2, STAGE_RADIUS * 2);
    }

    /** Сцена — тело карточки, сетка под ±30° и тонкая кайма цвета сетки. */
    private static void stage(Graphics2D g)
    {
        Shape stage = stageShape();
        g.setColor(color(CanonPalette.VIOLET_NIGHT));
        g.fill(stage);

        Shape clip = g.getClip();
        g.clip(stage);

        try
        {
            grid(g);
        }
        finally
        {
            g.setClip(clip);
        }

        g.setStroke(new BasicStroke(2.0F));
        g.setColor(withAlpha(CanonPalette.GRID, 0.45F));
        g.draw(stage);
    }

    /**
     * Две серии линий под ±30° (рёбра блоков в изометрии превью идут так же), начало — в центре сцены,
     * чтобы сетка лежала симметрично. Каждая 4-я линия — акцентным тоном сетки канона.
     */
    private static void grid(Graphics2D g)
    {
        float cx = WIDTH / 2.0F;
        float cy = (STAGE_TOP + STAGE_BOTTOM) / 2.0F;
        float halfW = WIDTH / 2.0F;
        // Линии y = ±tg30·(x − cx) + c; соседние — через шаг по вертикали
        float stepY = (float) (GRID_STEP / Math.cos(Math.toRadians(30.0)));
        int lines = (int) Math.ceil((HEIGHT + 2.0 * halfW * TAN30) / stepY) + 1;
        BasicStroke stroke = new BasicStroke(1.5F);
        g.setStroke(stroke);

        for (int sign = -1; sign <= 1; sign += 2)
        {
            for (int i = -lines; i <= lines; ++i)
            {
                boolean accent = Math.floorMod(i, 4) == 0;
                g.setColor(accent ? withAlpha(CanonPalette.GRID_ACCENT, GRID_ACCENT_ALPHA) : withAlpha(CanonPalette.GRID, GRID_ALPHA));

                float offset = cy + i * stepY;
                float y0 = (float) (offset - sign * TAN30 * halfW);
                float y1 = (float) (offset + sign * TAN30 * halfW);
                g.draw(new Line2D.Float(cx - halfW, y0, cx + halfW, y1));
            }
        }
    }

    private static void drawModel(Graphics2D g, BufferedImage model)
    {
        // Снимок заказан ровно под область — кладём пиксель в пиксель; иной размер вписываем
        int w = model.getWidth();
        int h = model.getHeight();

        if (w == MODEL_W && h == MODEL_H)
        {
            g.drawImage(model, MODEL_X, MODEL_Y, null);
            return;
        }

        float k = Math.min(MODEL_W / (float) w, MODEL_H / (float) h);
        int dw = Math.round(w * k);
        int dh = Math.round(h * k);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(model, MODEL_X + (MODEL_W - dw) / 2, MODEL_Y + (MODEL_H - dh) / 2, dw, dh, null);
    }

    /** Модели нет: контур куба в изометрии каркасным цветом и строка о причине. */
    private static void placeholder(Graphics2D g, String message)
    {
        float cx = WIDTH / 2.0F;
        float cy = (STAGE_TOP + STAGE_BOTTOM) / 2.0F - 40.0F;
        float a = 120.0F;
        float ax = (float) (a * Math.cos(Math.toRadians(30.0)));
        float ay = a / 2.0F;

        Path2D cube = new Path2D.Float();
        // Шестиугольник силуэта и три внутренних ребра от ближнего угла
        cube.moveTo(cx, cy - a);
        cube.lineTo(cx + ax, cy - ay);
        cube.lineTo(cx + ax, cy + ay);
        cube.lineTo(cx, cy + a);
        cube.lineTo(cx - ax, cy + ay);
        cube.lineTo(cx - ax, cy - ay);
        cube.closePath();
        cube.moveTo(cx, cy);
        cube.lineTo(cx, cy + a);
        cube.moveTo(cx, cy);
        cube.lineTo(cx + ax, cy - ay);
        cube.moveTo(cx, cy);
        cube.lineTo(cx - ax, cy - ay);

        g.setStroke(new BasicStroke(3.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(withAlpha(CanonPalette.FRAME, 0.35F));
        g.draw(cube);

        if (message.isEmpty())
        {
            return;
        }

        textMode(g);
        g.setFont(mono(PLACEHOLDER_SIZE, false));
        g.setColor(color(CanonPalette.TEXT_ON_DARK));
        String fitted = fit(g, message, WIDTH - 2 * MODEL_X);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(fitted, cx - metrics.stringWidth(fitted) / 2.0F, cy + a + 70.0F);
    }

    private static void title(Graphics2D g, String title)
    {
        int width = WIDTH - 2 * PAD;
        Font font = titleFont(g, title, width);
        boolean pixel = font.getFamily(Locale.ROOT).equals(pixel().getFamily(Locale.ROOT));

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, pixel
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setFont(font);
        g.setColor(color(CanonPalette.WHITE));
        g.drawString(fit(g, title, width), PAD, TITLE_BASELINE);
    }

    /** Куски через « · »; не влезло целиком — одной строкой с «…» в конце (обрезается хвост — автор). */
    private static void subtitle(Graphics2D g, List<Segment> segments)
    {
        if (segments.isEmpty())
        {
            return;
        }

        textMode(g);
        g.setFont(mono(SUBTITLE_SIZE, false));
        FontMetrics metrics = g.getFontMetrics();
        int width = WIDTH - 2 * PAD;
        String separator = " · ";
        StringBuilder whole = new StringBuilder();

        for (Segment segment : segments)
        {
            if (! whole.isEmpty())
            {
                whole.append(separator);
            }

            whole.append(segment.text());
        }

        if (metrics.stringWidth(whole.toString()) > width)
        {
            g.setColor(color(CanonPalette.SUBTITLE));
            g.drawString(fit(g, whole.toString(), width), PAD, SUBTITLE_BASELINE);
            return;
        }

        float x = PAD;

        for (int i = 0; i < segments.size(); ++i)
        {
            if (i > 0)
            {
                g.setColor(color(CanonPalette.OVERLINE));
                g.drawString(separator, x, SUBTITLE_BASELINE);
                x += metrics.stringWidth(separator);
            }

            Segment segment = segments.get(i);
            g.setColor(color(segment.accent() ? CanonPalette.AMBER_TOP : CanonPalette.SUBTITLE));
            g.drawString(segment.text(), x, SUBTITLE_BASELINE);
            x += metrics.stringWidth(segment.text());
        }
    }

    private static void footer(Graphics2D g, String note, String brand)
    {
        textMode(g);
        g.setFont(mono(FOOTER_SIZE, false));
        FontMetrics metrics = g.getFontMetrics();
        BufferedImage icon = icon();
        int brandWidth = metrics.stringWidth(brand) + (icon != null ? BRAND_ICON + 12 : 0);

        g.setColor(color(CanonPalette.SUBTITLE));
        g.drawString(fit(g, note, WIDTH - 2 * PAD - brandWidth - 40), PAD, FOOTER_BASELINE);

        int right = WIDTH - PAD;
        g.setColor(color(CanonPalette.BADGE));
        g.drawString(brand, right - metrics.stringWidth(brand), FOOTER_BASELINE);

        if (icon != null)
        {
            int x = right - brandWidth;
            int y = FOOTER_BASELINE - metrics.getAscent() / 2 - BRAND_ICON / 2 + 2;
            g.drawImage(icon, x, y, null);
        }
    }

    // ===== Шрифты, знак, мелочи =====

    private static Font titleFont(Graphics2D g, String title, int width)
    {
        Font base = pixel();

        if (base.canDisplayUpTo(title) == -1)
        {
            for (int size : TITLE_SIZES)
            {
                Font font = base.deriveFont((float) size);

                if (g.getFontMetrics(font).stringWidth(title) <= width)
                {
                    return font;
                }
            }

            return base.deriveFont((float) TITLE_SIZES[TITLE_SIZES.length - 1]);
        }

        // Знаков, которых нет в пиксельном шрифте (иероглифы в названии), — моноширинным
        return mono(36.0F, true);
    }

    private static void textMode(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /** Обрезка по ширине с «…» в конце. */
    private static String fit(Graphics2D g, String text, int width)
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

        synchronized (PosterPainter.class)
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
        try (InputStream in = PosterPainter.class.getResourceAsStream(FONT_DIR + file))
        {
            if (in == null)
            {
                throw new IOException("missing " + FONT_DIR + file);
            }

            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
        catch (IOException | FontFormatException | RuntimeException e)
        {
            LOGGER.warn("Poster: could not load font {}, using a system monospace font", file, e);
            return fallback;
        }
    }

    /** Знак мода из jar (512 px), уменьшенный до {@value #BRAND_ICON} px усреднением; нет — без знака. */
    private static synchronized @Nullable BufferedImage icon()
    {
        if (brandIcon != null || iconMissing)
        {
            return brandIcon;
        }

        try (InputStream in = PosterPainter.class.getResourceAsStream(ICON))
        {
            BufferedImage source = in == null ? null : ImageIO.read(in);

            if (source == null)
            {
                iconMissing = true;
                return null;
            }

            brandIcon = shrink(source, BRAND_ICON);
        }
        catch (IOException | RuntimeException e)
        {
            iconMissing = true;
            LOGGER.debug("Poster: brand icon not loaded", e);
        }

        return brandIcon;
    }

    /** Уменьшение вдвое за шаг (билинейно), пока не дойдём до стороны {@code side}: без ряби на сетке знака. */
    private static BufferedImage shrink(BufferedImage source, int side)
    {
        BufferedImage current = source;

        while (current.getWidth() > side)
        {
            int next = Math.max(side, current.getWidth() / 2);
            BufferedImage smaller = new BufferedImage(next, next, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = smaller.createGraphics();

            try
            {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setComposite(AlphaComposite.Src);
                g.drawImage(current, 0, 0, next, next, null);
            }
            finally
            {
                g.dispose();
            }

            current = smaller;
        }

        return current;
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
