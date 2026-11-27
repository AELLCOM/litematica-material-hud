package com.aell.layerlist.hud;

import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Draw;
import com.aell.layerlist.ui.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.Nullable;

/**
 * Карточка-карусель «как сделать флаг» — сама карточка, без того, где и когда она показана. Одна и та же стоит у
 * ячейки с флагом в окне контейнера ({@link BannerCard}), у строки флага во вкладке «Материалы» и закреплённой у
 * ткацкого станка ({@link BannerPin}).
 *
 * <ul>
 *   <li>Страницы: «Что нужно» (флаг-основа, красители со счётом, предметы-узоры, станок; по {@link #ROWS} строк) и по
 *       странице на узор — флаг до, краситель, узор, флаг после; шаги сверх {@link LoomGuide#LOOM_LIMIT} красные.</li>
 *   <li>Щелчок по узору шага или по строке с «?» — «Как получить» ({@link BannerSources}).</li>
 *   <li>Закреплённая карточка знает, сколько шагов уже сделано у флага в станке ({@link #follow}): сама переходит к
 *       следующему шагу и помечает сделанные.</li>
 * </ul>
 *
 * <p>Рисуется в своих координатах от левого верхнего угла, место и масштаб задаёт хозяин ({@link #place}): у станка на
 * узком экране карточка ужимается, как остальные панели у окна.
 */
@Environment(EnvType.CLIENT)
public final class BannerCardView
{
    public static final int W = 172;
    private static final int PAD = 6;
    private static final int HEAD = 22;
    private static final int BODY = 98;
    private static final int FOOT = 26;
    public static final int H = PAD + HEAD + 3 + BODY + 3 + FOOT + PAD;
    private static final int ROW = 17;
    private static final int ROWS = 5;
    /** Цвет подложки ванильной подсказки, без прозрачности. */
    private static final int VANILLA_BACK = 0xFF100010;

    private static final String[] ARROW_LEFT = {"   #", "  ##", " ###", "####", " ###", "  ##", "   #"};
    private static final String[] ARROW_RIGHT = {"#   ", "##  ", "### ", "####", "### ", "##  ", "#   "};
    private static final String[] PLUS = {"  #  ", "  #  ", "#####", "  #  ", "  #  "};
    private static final String[] TO = {"   #   ", "    #  ", "###### ", "    #  ", "   #   "};
    private static final String[] INFO = {" ### ", "#   #", "   # ", "  #  ", "     ", "  #  "};
    private static final String[] CROSS = {"#     #", " #   # ", "  # #  ", "   #   ", "  # #  ", " #   # ", "#     #"};

    /** Строка «Что нужно». */
    record Need(ItemStack stack, int count, BannerSources.@Nullable Source source) {}

    /** Страница карусели. */
    sealed interface Page permits Needs, Step {}

    record Needs(List<Need> rows, int part, int parts) implements Page {}

    record Step(int number, int total, BannerPatternLayers.Layer layer, @Nullable Item item, BannerPatternLayers before,
            BannerPatternLayers after) implements Page {}

    /** Щёлкаемая область текущего кадра — в координатах карточки. */
    private record Hit(int x, int y, int w, int h, boolean info, Runnable action) {}

    private final ItemStack stack;
    private final DyeColor base;
    private final List<BannerPatternLayers.Layer> layers;
    private final List<Page> pages;
    private final int needsPages;
    private final BannerFlagModel flag;
    private final long openedAt;
    private final List<Hit> hits = new ArrayList<>();
    /** Полотна текущего кадра: рисуются одной картинкой после страницы ({@link BannerFlags}). */
    private final List<BannerFlags.Flag> flags = new ArrayList<>();
    private int page;
    private BannerSources.@Nullable Source detail;
    private long turnedAt;
    private int x;
    private int y;
    private float scale = 1.0F;
    /** Сколько узоров уже нанесено у флага в станке; −1 — за флагом не следим или в станке не он. */
    private int made = - 1;
    private boolean pinned;
    private @Nullable Runnable unpin;

    /** @param banner флаг с узорами (см. {@link BannerCard#patterned}) */
    public BannerCardView(ItemStack banner)
    {
        long now = Util.getMillis();
        this.stack = banner.copyWithCount(1);
        this.base = ((BannerItem) this.stack.getItem()).getColor();
        this.layers = List.copyOf(this.stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers());
        this.pages = build(this.stack, this.base, this.layers);
        int needs = 0;

        for (Page built : this.pages)
        {
            needs += built instanceof Needs ? 1 : 0;
        }

        this.needsPages = needs;
        this.openedAt = now;
        this.turnedAt = now;
        // Модель полотна — как у окна станка: печётся заново, чтобы пережить перезагрузку ресурсов
        this.flag = new BannerFlagModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.STANDING_BANNER_FLAG));
    }

    /** Страницы: «Что нужно» (по {@link #ROWS} строк), затем шаги станка. */
    private static List<Page> build(ItemStack banner, DyeColor color, List<BannerPatternLayers.Layer> layers)
    {
        ItemStack plain = new ItemStack(banner.getItem());
        Map<DyeColor, Integer> dyes = new LinkedHashMap<>();
        Set<Item> items = new LinkedHashSet<>();

        for (BannerPatternLayers.Layer layer : layers)
        {
            dyes.merge(layer.color(), 1, Integer::sum);
            Item item = LoomGuide.patternItem(layer.pattern());

            if (item != null)
            {
                items.add(item);
            }
        }

        List<Need> needs = new ArrayList<>();
        needs.add(new Need(plain, 1, BannerSources.banner(plain, color)));

        for (Map.Entry<DyeColor, Integer> dye : dyes.entrySet())
        {
            ItemStack dyeStack = BannerSources.stack(dye.getKey().getName() + "_dye", 1);

            if (! dyeStack.isEmpty())
            {
                needs.add(new Need(dyeStack, dye.getValue(), null));
            }
        }

        for (Item item : items)
        {
            needs.add(new Need(new ItemStack(item), 1, BannerSources.of(item)));
        }

        BannerSources.Source loom = BannerSources.loom();

        if (! loom.result().isEmpty())
        {
            needs.add(new Need(loom.result(), 1, loom));
        }

        List<Page> out = new ArrayList<>();
        int parts = (needs.size() + ROWS - 1) / ROWS;

        for (int part = 0; part < parts; ++part)
        {
            out.add(new Needs(List.copyOf(needs.subList(part * ROWS, Math.min(needs.size(), (part + 1) * ROWS))), part + 1, parts));
        }

        for (int i = 0; i < layers.size(); ++i)
        {
            BannerPatternLayers.Layer layer = layers.get(i);
            out.add(new Step(i + 1, layers.size(), layer, LoomGuide.patternItem(layer.pattern()),
                    new BannerPatternLayers(List.copyOf(layers.subList(0, i))),
                    new BannerPatternLayers(List.copyOf(layers.subList(0, i + 1)))));
        }

        return out;
    }

    // ===== Место =====

    public ItemStack stack()
    {
        return this.stack;
    }

    /** Левый верхний угол на экране и масштаб (1 — пиксель в пиксель GUI). */
    public void place(int x, int y, float scale)
    {
        this.x = x;
        this.y = y;
        this.scale = scale <= 0.0F ? 1.0F : scale;
    }

    public int x()
    {
        return this.x;
    }

    public int y()
    {
        return this.y;
    }

    /** Ширина на экране с учётом масштаба. */
    public int width()
    {
        return (int) Math.ceil(W * this.scale);
    }

    public int height()
    {
        return (int) Math.ceil(H * this.scale);
    }

    public boolean contains(double mouseX, double mouseY)
    {
        return mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width() && mouseY < this.y + this.height();
    }

    /** Закреплённая карточка: пометка в шапке и крестик «открепить». */
    public BannerCardView pinned(Runnable unpin)
    {
        this.pinned = true;
        this.unpin = unpin;
        return this;
    }

    /**
     * Сколько узоров уже нанесено у флага в ячейке станка (−1 — в станке не этот флаг). Число сменилось — карточка сама
     * открывает следующий шаг; пока оно прежнее, листает игрок.
     */
    public void follow(int layersMade)
    {
        if (layersMade == this.made)
        {
            return;
        }

        this.made = layersMade;

        if (layersMade >= 0 && ! this.layers.isEmpty())
        {
            this.go(this.needsPages + Math.min(layersMade, this.layers.size() - 1));
        }
    }

    // ===== Рисование =====

    /** Нарисовать на своём месте; мышь — в координатах экрана. */
    public void draw(GuiContext ctx, Font font, int mouseX, int mouseY)
    {
        int localX = (int) Math.floor((mouseX - this.x) / this.scale);
        int localY = (int) Math.floor((mouseY - this.y) / this.scale);
        ctx.pose().pushMatrix();
        ctx.pose().translate(this.x, this.y);
        ctx.pose().scale(this.scale, this.scale);

        try
        {
            this.drawLocal(ctx, font, localX, localY);
        }
        finally
        {
            ctx.pose().popMatrix();
        }
    }

    private void drawLocal(GuiContext ctx, Font font, int mouseX, int mouseY)
    {
        this.hits.clear();
        this.flags.clear();

        // Карточка лежит поверх ячеек и строк: подложка глухая, иначе сквозь неё видно то, что под ней
        if (Style.vanilla())
        {
            Paint.rect(ctx, 0, 0, W, H, VANILLA_BACK);
            Vanilla.tooltip(ctx, 0, 0, W, H);
        }
        else
        {
            Draw.card(ctx, 0, 0, W, H, 6, Theme.PANEL_DEEP | 0xFF000000, Theme.EDGE);
        }

        int left = PAD;
        int room = W - PAD * 2;
        int top = PAD;
        int count = this.layers.size();
        boolean over = count > LoomGuide.LOOM_LIMIT;
        int headRoom = room - 20 - (this.unpin != null ? 14 : 0);
        ctx.renderItem(this.stack, left, top + 2);
        Paint.marquee(ctx, font, this.stack.getHoverName().getString(), left + 20, top + 1, headRoom, Theme.TEXT, false, this.openedAt);
        String sub = (this.pinned ? I18n.get("layerlist.banner.pinned") + " · " : "") + I18n.get("layerlist.banner.patterns", count)
                + (over ? " · " + I18n.get("layerlist.banner.over_limit") : "");
        Paint.marquee(ctx, font, sub, left + 20, top + 12, headRoom, over ? Theme.RED : this.pinned ? Theme.GOLD : Theme.MUTED, false,
                this.openedAt);

        if (this.unpin != null)
        {
            int crossX = left + room - 11;
            boolean hot = Paint.hit(mouseX, mouseY, crossX - 2, top, 13, 13);
            Draw.round(ctx, crossX - 2, top, 13, 13, 3, hot ? Theme.ROW_HOVER : Theme.ROW);
            Paint.icon(ctx, CROSS, crossX + 1, top + 3, hot ? Theme.TEXT : Theme.MUTED);
            this.hits.add(new Hit(crossX - 2, top, 13, 13, false, this.unpin));
        }

        int bodyY = top + HEAD + 3;
        Draw.round(ctx, left, bodyY, room, BODY, 4, Theme.ROW);

        if (this.detail != null)
        {
            this.drawDetail(ctx, font, this.detail, left, bodyY, room);
        }
        else if (this.pages.get(this.page) instanceof Needs needs)
        {
            this.drawNeeds(ctx, font, needs, left, bodyY, room, mouseX, mouseY);
        }
        else if (this.pages.get(this.page) instanceof Step step)
        {
            this.drawStep(ctx, font, step, left, bodyY, room, mouseX, mouseY);
        }

        BannerFlags.draw(ctx, this.flag, this.flags, left, bodyY, room, BODY, this.pinned);
        this.drawFooter(ctx, font, left, bodyY + BODY + 3, room, mouseX, mouseY);
    }

    private void drawNeeds(GuiContext ctx, Font font, Needs needs, int left, int bodyY, int room, int mouseX, int mouseY)
    {
        String title = I18n.get("layerlist.banner.needs") + (needs.parts() > 1 ? " · " + needs.part() + "/" + needs.parts() : "");
        Paint.marquee(ctx, font, title, left + 4, bodyY + 3, room - 8, Theme.MUTED, false, this.turnedAt);
        int rowY = bodyY + 12;

        for (Need need : needs.rows())
        {
            boolean clickable = need.source() != null;
            boolean hot = clickable && Paint.hit(mouseX, mouseY, left + 2, rowY, room - 4, ROW - 1);

            if (hot)
            {
                Draw.round(ctx, left + 2, rowY, room - 4, ROW - 1, 3, Theme.ROW_HOVER);
            }

            ctx.renderItem(need.stack(), left + 3, rowY);
            String count = need.count() > 1 ? "×" + need.count() : "";
            int tail = clickable ? 9 : count.isEmpty() ? 0 : font.width(count) + 4;
            Paint.marquee(ctx, font, BannerSources.name(need.stack()), left + 23, rowY + 4, room - 23 - 6 - tail, Theme.TEXT, false,
                    this.turnedAt);

            if (clickable)
            {
                Paint.icon(ctx, INFO, left + room - 11, rowY + 5, hot ? Theme.GOLD : Theme.FAINT);
                BannerSources.Source source = need.source();
                this.hits.add(new Hit(left + 2, rowY, room - 4, ROW - 1, true, () -> this.show(source)));
            }
            else if (! count.isEmpty())
            {
                Paint.text(ctx, font, count, left + room - 4 - font.width(count), rowY + 4, Theme.MUTED, false);
            }

            rowY += ROW;
        }
    }

    private void drawStep(GuiContext ctx, Font font, Step step, int left, int bodyY, int room, int mouseX, int mouseY)
    {
        boolean beyond = step.number() > LoomGuide.LOOM_LIMIT;
        // У закреплённой карточки шаг помечен: сделан или следующий (как в панели «Рецепты флагов у станка»)
        boolean finished = this.made >= 0 && step.number() <= this.made;
        boolean next = this.made >= 0 && step.number() == this.made + 1;
        String title = (finished ? "✔ " : next ? "▶ " : "") + I18n.get("layerlist.banner.step", step.number(), step.total())
                + (beyond ? " " + I18n.get("layerlist.loom.beyond_limit") : "");
        Paint.marquee(ctx, font, title, left + 4, bodyY + 3, room - 8,
                beyond ? Theme.RED : finished ? Theme.GREEN : next ? Theme.YELLOW : Theme.MUTED, false, this.turnedAt);

        int top = bodyY + 13;
        // Слева флаг до шага, справа — после
        int afterX = left + room - 6 - 30;
        this.flag(ctx, this.base, step.before(), left + 6, top + 6, 24, 48, bodyY);
        this.flag(ctx, this.base, step.after(), afterX, top, 30, 60, bodyY);

        // Между ними: краситель + узор → результат
        int midLeft = left + 6 + 24;
        int midRoom = afterX - midLeft;
        int mid = midLeft + (midRoom - 64) / 2;
        int centerY = top + 30;
        Paint.icon(ctx, PLUS, mid, centerY - 2, Theme.MUTED);
        ItemStack dye = BannerSources.stack(step.layer().color().getName() + "_dye", 1);

        if (! dye.isEmpty())
        {
            ctx.renderItem(dye, mid + 8, centerY - 8);
        }

        Paint.icon(ctx, PLUS, mid + 27, centerY - 2, Theme.MUTED);
        int patternX = mid + 35;
        boolean hot = Paint.hit(mouseX, mouseY, patternX - 2, top, 22, 60);

        if (hot)
        {
            Draw.round(ctx, patternX - 2, top, 22, 60, 3, Theme.GOLD_FILL);
            Draw.roundEdge(ctx, patternX - 2, top, 22, 60, 3, Theme.GOLD_EDGE);
        }

        // Узор отдельно — своим цветом на нейтральном полотне
        DyeColor cloth = step.layer().color() == DyeColor.WHITE || step.layer().color() == DyeColor.LIGHT_GRAY ? DyeColor.GRAY
                : DyeColor.WHITE;
        this.flag(ctx, cloth, new BannerPatternLayers(List.of(step.layer())), patternX, top + 2, 18, 36, bodyY);

        if (step.item() != null)
        {
            ctx.renderItem(new ItemStack(step.item()), patternX + 1, top + 42);
        }

        Paint.icon(ctx, TO, mid + 57, centerY - 2, Theme.MUTED);
        BannerSources.Source source = step.item() != null ? BannerSources.of(step.item())
                : BannerSources.noItem(step.layer().description().getString());
        this.hits.add(new Hit(patternX - 2, top, 22, 60, true, () -> this.show(source)));

        Paint.marquee(ctx, font, step.layer().description().getString(), left + 4, bodyY + 76, room - 8, Theme.TEXT, false,
                this.turnedAt);

        if (step.item() != null)
        {
            String needs = I18n.get("layerlist.banner.needs_item", BannerSources.name(new ItemStack(step.item())));
            boolean lineHot = Paint.hit(mouseX, mouseY, left + 2, bodyY + 85, room - 4, 11);
            Paint.marquee(ctx, font, needs, left + 4, bodyY + 87, room - 8, lineHot || hot ? Theme.GOLD : Theme.YELLOW, false,
                    this.turnedAt);
            this.hits.add(new Hit(left + 2, bodyY + 85, room - 4, 11, true, () -> this.show(source)));
        }
        else
        {
            Paint.marquee(ctx, font, I18n.get("layerlist.banner.no_item"), left + 4, bodyY + 87, room - 8, Theme.FAINT, false,
                    this.turnedAt);
        }
    }

    private void drawDetail(GuiContext ctx, Font font, BannerSources.Source source, int left, int bodyY, int room)
    {
        Paint.marquee(ctx, font, I18n.get("layerlist.banner.how"), left + 4, bodyY + 3, room - 8, Theme.MUTED, false, this.turnedAt);
        Paint.marquee(ctx, font, source.title(), left + 4, bodyY + 14, room - 8, Theme.TEXT, false, this.turnedAt);
        int iconX = left + 6;
        int iconY = bodyY + 28;

        for (int i = 0; i < source.inputs().size(); ++i)
        {
            ItemStack input = source.inputs().get(i);

            if (i > 0 && source.kind() == BannerSources.Kind.CRAFT)
            {
                Paint.icon(ctx, PLUS, iconX + 2, iconY + 6, Theme.MUTED);
                iconX += 9;
            }

            if (input.getCount() > 1)
            {
                String count = input.getCount() + "×";
                Paint.text(ctx, font, count, iconX, iconY + 4, Theme.TEXT, false);
                iconX += font.width(count) + 2;
            }

            ctx.renderItem(input, iconX, iconY);
            iconX += 18;
        }

        if (source.kind() == BannerSources.Kind.CRAFT)
        {
            Paint.icon(ctx, TO, iconX + 2, iconY + 6, Theme.MUTED);
            ctx.renderItem(source.result(), iconX + 13, iconY);
        }

        int lineY = bodyY + 50;

        for (int i = 0; i < source.lines().size() && i < 4; ++i)
        {
            Paint.marquee(ctx, font, source.lines().get(i), left + 4, lineY, room - 8, i == 0 ? Theme.TEXT : Theme.MUTED, false,
                    this.turnedAt);
            lineY += 11;
        }
    }

    private void drawFooter(GuiContext ctx, Font font, int left, int footY, int room, int mouseX, int mouseY)
    {
        if (this.detail != null)
        {
            String back = I18n.get("gui.back");
            int width = font.width(back) + 19;
            boolean hot = Paint.hit(mouseX, mouseY, left, footY, width, 12);
            Draw.round(ctx, left, footY, width, 12, 3, hot ? Theme.ROW_HOVER : Theme.ROW);
            Paint.icon(ctx, ARROW_LEFT, left + 4, footY + 3, hot ? Theme.TEXT : Theme.MUTED);
            Paint.text(ctx, font, back, left + 13, footY + 2, hot ? Theme.TEXT : Theme.MUTED, false);
            this.hits.add(new Hit(left, footY, width, 12, true, this::back));
        }
        else
        {
            this.arrow(ctx, ARROW_LEFT, left, footY, this.page > 0, mouseX, mouseY, () -> this.turn(- 1));
            this.arrow(ctx, ARROW_RIGHT, left + room - 16, footY, this.page < this.pages.size() - 1, mouseX, mouseY, () -> this.turn(1));
            int count = this.pages.size();

            if (count <= 12)
            {
                int dotsX = left + (room - (count * 7 - 3)) / 2;

                for (int i = 0; i < count; ++i)
                {
                    // У закреплённой карточки точки сделанных шагов зелёные
                    boolean stepMade = this.made >= 0 && i >= this.needsPages && i - this.needsPages < this.made;
                    Paint.rect(ctx, dotsX + i * 7, footY + 4, 4, 4, i == this.page ? Theme.GOLD : stepMade ? Theme.GREEN_EDGE : Theme.OFF);
                    int target = i;
                    this.hits.add(new Hit(dotsX + i * 7 - 1, footY + 1, 6, 10, false, () -> this.go(target)));
                }
            }
            else
            {
                Paint.centered(ctx, font, (this.page + 1) + "/" + count, left + room / 2, footY + 2, Theme.MUTED);
            }
        }

        Paint.marquee(ctx, font, I18n.get("layerlist.banner.hint"), left + 2, footY + 16, room - 4, Theme.FAINT, false, this.openedAt);
    }

    private void arrow(GuiContext ctx, String[] mask, int ax, int ay, boolean enabled, int mouseX, int mouseY, Runnable action)
    {
        boolean hot = enabled && Paint.hit(mouseX, mouseY, ax, ay, 16, 12);
        Draw.round(ctx, ax, ay, 16, 12, 3, hot ? Theme.ROW_HOVER : Theme.ROW);
        Paint.icon(ctx, mask, ax + 6, ay + 3, ! enabled ? Theme.OFF : hot ? Theme.TEXT : Theme.MUTED);

        if (enabled)
        {
            this.hits.add(new Hit(ax, ay, 16, 12, false, action));
        }
    }

    /**
     * Полотно флага в кадр: все полотна страницы рисуются одной картинкой ({@link BannerFlags}). Вокруг — тонкая
     * кромка: чёрное полотно на тёмной карточке иначе не видно.
     */
    private void flag(GuiContext ctx, DyeColor cloth, BannerPatternLayers shown, int fx, int fy, int width, int height, int bodyY)
    {
        Paint.border(ctx, fx - 1, fy - 1, width + 2, height + 2, Theme.EDGE);
        this.flags.add(new BannerFlags.Flag(cloth, shown, fx - PAD, fy - bodyY, width, height));
    }

    // ===== Листание и ввод =====

    public void turn(int by)
    {
        this.go(this.page + by);
    }

    private void go(int target)
    {
        int next = Math.max(0, Math.min(this.pages.size() - 1, target));

        if (next != this.page || this.detail != null)
        {
            this.page = next;
            this.detail = null;
            this.turnedAt = Util.getMillis();
        }
    }

    private void show(BannerSources.Source source)
    {
        this.detail = source;
        this.turnedAt = Util.getMillis();
    }

    private void back()
    {
        this.detail = null;
        this.turnedAt = Util.getMillis();
    }

    /**
     * Щелчок: левый — по тому, что под курсором; любой другой — назад со страницы «Как получить».
     *
     * @return щелчок пришёлся на карточку (и ей достался)
     */
    public boolean click(double mouseX, double mouseY, int button)
    {
        if (! this.contains(mouseX, mouseY))
        {
            return false;
        }

        if (button != InputConstants.MOUSE_BUTTON_LEFT)
        {
            if (this.detail != null)
            {
                this.back();
            }

            return true;
        }

        int localX = (int) Math.floor((mouseX - this.x) / this.scale);
        int localY = (int) Math.floor((mouseY - this.y) / this.scale);

        for (Hit hit : List.copyOf(this.hits))
        {
            if (Paint.hit(localX, localY, hit.x(), hit.y(), hit.w(), hit.h()))
            {
                hit.action().run();
                break;
            }
        }

        return true;
    }

    /** ← → листают, Backspace — назад со страницы «Как получить». */
    public boolean key(int key)
    {
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT)
        {
            this.turn(key == InputConstants.KEY_LEFT ? - 1 : 1);
            return true;
        }

        if (key == InputConstants.KEY_BACKSPACE && this.detail != null)
        {
            this.back();
            return true;
        }

        return false;
    }

    // ===== Для стенда =====

    /** Номер страницы, число страниц и открыта ли «Как получить» — одной строкой. */
    public String state()
    {
        return "страница " + (this.page + 1) + "/" + this.pages.size() + (this.detail == null ? "" : ", как получить: "
                + this.detail.kind() + " " + this.detail.title());
    }

    /** Середина (на экране) области, ведущей на «Как получить» или назад: узор шага, строка предмета, «Назад». */
    public double @Nullable [] infoSpot(int index)
    {
        int seen = 0;

        for (Hit hit : this.hits)
        {
            if (hit.info() && seen++ == index)
            {
                return this.onScreen(hit);
            }
        }

        return null;
    }

    /** Середина крестика «открепить» на экране; {@code null} — карточка не закреплена. */
    public double @Nullable [] unpinSpot()
    {
        for (Hit hit : this.hits)
        {
            if (hit.action() == this.unpin && this.unpin != null)
            {
                return this.onScreen(hit);
            }
        }

        return null;
    }

    private double[] onScreen(Hit hit)
    {
        return new double[]{this.x + (hit.x() + hit.w() / 2.0D) * this.scale, this.y + (hit.y() + hit.h() / 2.0D) * this.scale};
    }

    /** Сколько шагов считается сделанными (−1 — не следим). */
    public int made()
    {
        return this.made;
    }
}
