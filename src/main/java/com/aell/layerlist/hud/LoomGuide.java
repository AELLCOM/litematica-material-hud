package com.aell.layerlist.hud;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.Draw;
import com.aell.layerlist.ui.Theme;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BannerPatternTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.Nullable;

/**
 * «Узоры флагов у ткацкого станка» (заявка #3 на GitHub, 06.11.2026: «показать прямо в станке, как сделать флаг из
 * схемы — какие узоры, какими красителями и в каком порядке»). Пока открыт ткацкий станок, а в списке материалов есть
 * узорные флаги, рядом с окном — панель с рецептом каждого: флаг какого цвета взять и по шагам — узор, краситель и, если
 * нужен, предмет-узор («Крипер», «Цветок»…). Флаг в слоте станка, который уже совпадает с началом рецепта, отмечает
 * сделанные шаги и подсвечивает следующий: положили флаг — видно, что делать дальше. В станке не больше
 * {@link #LOOM_LIMIT} узоров — шаги сверх этого помечены, их в станке не сделать.
 *
 * <p>Флаги — из строк панели материалов ({@link LayerCountManager#getEntries}): только то, что панель сейчас считает
 * нужным. Встаёт с другой стороны окна, чем панель материалов; не влезает — под ней.
 */
@Environment(EnvType.CLIENT)
public final class LoomGuide
{
    /** Ванильный предел узоров ткацкого станка. */
    public static final int LOOM_LIMIT = 6;
    private static final int MAX_BANNERS = 3;
    private static final int PAD = 5;
    private static final int LINE = 10;
    private static final int HEAD = 18;
    private static final int GAP = 4;
    private static final int MIN_W = 120;
    private static final int MAX_W = 280;

    private static @Nullable Map<Holder<BannerPattern>, java.util.Optional<Item>> patternItems;
    private static long openedAt;
    private static @Nullable Screen lastScreen;
    private static @Nullable Object lastLevel;

    /** Рецепт одного флага. */
    record Recipe(ItemStack stack, DyeColor base, List<BannerPatternLayers.Layer> layers, long missing) {}

    /** Строка панели. */
    private record Line(@Nullable ItemStack icon, String text, String count, int color, boolean step) {}

    private LoomGuide() {}

    /** Из хука экрана контейнера, после панели материалов. */
    public static void render(GuiGraphics graphics, Screen screen)
    {
        if (! (screen instanceof LoomScreen loom) || ! LayerListConfigs.LOOM_GUIDE.getBooleanValue()
                || ! LayerCountManager.getInstance().isHudEnabled())
        {
            lastScreen = null;
            return;
        }

        if (lastLevel != Minecraft.getInstance().level)
        {
            lastLevel = Minecraft.getInstance().level;
            reset();
        }

        if (lastScreen != screen)
        {
            lastScreen = screen;
            openedAt = net.minecraft.util.Util.getMillis();
        }

        List<Recipe> recipes = recipes(LayerCountManager.getInstance().getEntries());

        if (recipes.isEmpty())
        {
            return;
        }

        ItemStack inLoom = loom.getMenu().getBannerSlot().getItem();
        Font font = Minecraft.getInstance().font;
        List<Line> lines = lines(recipes, inLoom);
        int width = MIN_W;

        for (Line line : lines)
        {
            width = Math.max(width, Math.min(MAX_W, (line.icon() != null ? 20 : 0) + font.width(line.text()) + 6 + font.width(line.count()) + PAD * 2));
        }

        int height = PAD * 2 + LINE + 2;

        for (Line line : lines)
        {
            height += line.icon() != null ? HEAD : LINE;
        }

        double scale = LayerHudRenderer.getScale();
        HudDock.Dock dock = place(screen, width, height, scale);

        if (dock == null)
        {
            return;
        }

        GuiContext ctx = GuiContext.fromGuiGraphics(graphics);
        ctx.pose().pushMatrix();
        ctx.pose().translate(dock.x(), dock.y());
        ctx.pose().scale((float) dock.scale(), (float) dock.scale());
        draw(ctx, font, lines, width, height);
        ctx.pose().popMatrix();
    }

    /** С другой стороны окна, чем панель материалов; не влезает — под панелью; и там нет — не рисуем. */
    private static HudDock.@Nullable Dock place(Screen screen, int width, int height, double scale)
    {
        boolean hudRight = LayerListConfigs.HUD_DOCK_RIGHT.getBooleanValue();
        HudDock.Dock hud = LayerHudRenderer.dockBesideContainer(screen);
        HudDock.Dock dock = HudDock.beside(screen, width, height, scale, hud == null ? hudRight : ! hudRight);

        if (dock == null || hud == null)
        {
            return dock;
        }

        int[] hudSize = LayerHudRenderer.getPanelScreenSize(hud.scale());
        int w = (int) Math.ceil(width * dock.scale());
        boolean overlaps = dock.x() < hud.x() + hudSize[0] && hud.x() < dock.x() + w;

        if (! overlaps)
        {
            return dock;
        }

        int below = hud.y() + hudSize[1] + HudDock.GAP;
        int h = (int) Math.ceil(height * hud.scale());

        if (below + h > screen.height - HudDock.MARGIN)
        {
            return null;
        }

        return new HudDock.Dock(hud.x(), below, hud.scale());
    }

    private static void draw(GuiContext ctx, Font font, List<Line> lines, int width, int height)
    {
        if (Style.vanilla())
        {
            Vanilla.tooltip(ctx, 0, 0, width, height);
        }
        else
        {
            Draw.card(ctx, 0, 0, width, height, 6, Theme.PANEL, Theme.EDGE);
        }

        int x = PAD;
        int y = PAD;
        int room = width - PAD * 2;
        Paint.marquee(ctx, font, I18n.get("layerlist.loom.title"), x, y, room, Theme.TEXT, false, openedAt);
        y += LINE + 2;

        for (Line line : lines)
        {
            int countW = line.count().isEmpty() ? 0 : font.width(line.count()) + 6;

            if (line.icon() != null)
            {
                ctx.renderItem(line.icon(), x, y);
                Paint.marquee(ctx, font, line.text(), x + 20, y + 4, room - 20 - countW, line.color(), false, openedAt);

                if (countW > 0)
                {
                    Paint.text(ctx, font, line.count(), x + room - countW + 6, y + 4, Theme.RED, false);
                }

                y += HEAD;
                continue;
            }

            Paint.marquee(ctx, font, line.text(), x + 4, y + 1, room - 4, line.color(), false, openedAt);
            y += LINE;
        }
    }

    /** Строки: на каждый флаг — заголовок с иконкой и шаги; флаг, который уже лежит в станке, — первым. */
    static List<Line> lines(List<Recipe> recipes, ItemStack inLoom)
    {
        List<Recipe> order = new ArrayList<>(recipes);
        order.sort((a, b) -> Integer.compare(done(b, inLoom), done(a, inLoom)));
        List<Line> lines = new ArrayList<>();
        int shown = 0;

        for (Recipe recipe : order)
        {
            if (shown == MAX_BANNERS)
            {
                lines.add(new Line(null, I18n.get("layerlist.loom.more", order.size() - shown), "", Theme.MUTED, false));
                break;
            }

            ++shown;
            int done = done(recipe, inLoom);
            lines.add(new Line(recipe.stack(), DataItems.label(recipe.stack()), recipe.missing() > 0 ? "×" + recipe.missing() : "",
                    Theme.TEXT, false));
            ItemStack plain = recipe.stack().copy();
            plain.remove(DataComponents.BANNER_PATTERNS);
            lines.add(step(1, I18n.get("layerlist.loom.base", plain.getHoverName().getString()), done, false));

            for (int i = 0; i < recipe.layers().size(); ++i)
            {
                BannerPatternLayers.Layer layer = recipe.layers().get(i);
                StringBuilder text = new StringBuilder(layer.description().getString());
                Item item = patternItem(layer.pattern());

                if (item != null)
                {
                    text.append(" · ").append(I18n.get("layerlist.loom.needs", new ItemStack(item).getHoverName().getString()));
                }

                lines.add(step(i + 2, text.toString(), done, i >= LOOM_LIMIT));
            }
        }

        return lines;
    }

    private static Line step(int number, String text, int done, boolean beyondLimit)
    {
        boolean finished = number <= done;
        boolean next = number == done + 1;
        String mark = finished ? "✔ " : next ? "▶ " : number + ". ";
        String suffix = beyondLimit ? " " + I18n.get("layerlist.loom.beyond_limit") : "";
        int color = beyondLimit ? Theme.RED : finished ? Theme.GREEN : next ? Theme.YELLOW : Theme.MUTED;
        return new Line(null, mark + text + suffix, "", color, true);
    }

    /**
     * Сколько шагов рецепта уже сделано у флага в слоте станка: 0 — не тот флаг (другой цвет, слои не совпадают с
     * началом рецепта или их больше); 1 — нужный чистый флаг; дальше — плюс каждый совпавший слой.
     */
    static int done(Recipe recipe, ItemStack inLoom)
    {
        if (inLoom.isEmpty() || ! (inLoom.getItem() instanceof BannerItem banner) || banner.getColor() != recipe.base())
        {
            return 0;
        }

        BannerPatternLayers have = inLoom.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

        if (have.layers().size() > recipe.layers().size())
        {
            return 0;
        }

        for (int i = 0; i < have.layers().size(); ++i)
        {
            BannerPatternLayers.Layer a = have.layers().get(i);
            BannerPatternLayers.Layer b = recipe.layers().get(i);

            if (a.color() != b.color() || ! a.pattern().equals(b.pattern()))
            {
                return 0;
            }
        }

        return 1 + have.layers().size();
    }

    /** Узорные флаги из строк панели, без повторов (один и тот же флаг — одной строкой, счёт складывается). */
    static List<Recipe> recipes(List<MaterialListEntry> entries)
    {
        Map<DataItems.Key, Recipe> byKey = new LinkedHashMap<>();

        for (MaterialListEntry entry : entries)
        {
            ItemStack stack = entry.getStack();

            if (! (stack.getItem() instanceof BannerItem banner))
            {
                continue;
            }

            BannerPatternLayers layers = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

            if (layers.layers().isEmpty())
            {
                continue;
            }

            long missing = Math.max(0, entry.getCountMissing());
            byKey.merge(new DataItems.Key(stack), new Recipe(stack.copyWithCount(1), banner.getColor(), layers.layers(), missing),
                    (a, b) -> new Recipe(a.stack(), a.base(), a.layers(), a.missing() + b.missing()));
        }

        return new ArrayList<>(byKey.values());
    }

    /** Предмет-узор, без которого станок узор не даст; узор из общего набора — {@code null}. */
    static @Nullable Item patternItem(Holder<BannerPattern> pattern)
    {
        if (pattern.is(BannerPatternTags.NO_ITEM_REQUIRED))
        {
            return null;
        }

        if (patternItems == null)
        {
            patternItems = new HashMap<>();
        }

        return patternItems.computeIfAbsent(pattern, LoomGuide::findPatternItem).orElse(null);
    }

    private static java.util.Optional<Item> findPatternItem(Holder<BannerPattern> pattern)
    {
        for (Item item : BuiltInRegistries.ITEM)
        {
            // 1.21.11: у предмета-узора не набор узоров, а тег
            TagKey<BannerPattern> provided = new ItemStack(item).get(DataComponents.PROVIDES_BANNER_PATTERNS);

            if (provided != null && pattern.is(provided))
            {
                return java.util.Optional.of(item);
            }
        }

        return java.util.Optional.empty();
    }

    /** Сменился мир (теги и реестры узоров от сервера): забыть найденные предметы-узоры. */
    public static void reset()
    {
        patternItems = null;
    }
}
