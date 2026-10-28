package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.materials.ItemIcons;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.jetbrains.annotations.Nullable;

/**
 * Режим N — иконки выгрузок (M25) против настоящего GUI. Набор предметов (блоки с особыми
 * отрисовщиками, неполные блоки, плоские предметы, данные: флаг с узором, ваза с черепками, зелье,
 * крашеная броня, блеск) рисуется дважды: {@link ItemIcons#request} (64×64, наш рендер вне экрана) и
 * ванильным {@code GuiGraphicsExtractor.item} в сетке на экране при масштабе GUI 4 (слот 16 → 64 px),
 * экран снимается. В {@code LL_COVERAGE_OUT/icons}: {@code icons-ours.png} (наши, прозрачный фон),
 * {@code icons-gui.png} (вырез снимка), {@code icons-side-by-side.png}, страницы
 * {@code compare-NN.png} (наша на фоне экрана · GUI · разница ×4, крупно) и {@code icons.csv}
 * (доля несовпавших пикселей, средняя разница, сдвиг рамки).
 */
@Environment(EnvType.CLIENT)
public final class DumpIcons implements CovJob
{
    private static final String TAG = "[n] ";
    private static final int SIZE = 64;
    private static final int GUI_SCALE = 4;
    private static final int WINDOW_W = 1600;
    private static final int WINDOW_H = 900;
    /** Шаг сетки на экране, пиксели GUI: слот 16 + зазор 4. */
    private static final int PITCH = 20;
    private static final int COLUMNS = 18;
    private static final int ORIGIN = 8;
    /** Фон экрана — серый слота инвентаря: на нём видны и тёмные, и светлые края. */
    private static final int BACKGROUND = 0xFF8B8B8B;
    /** Пиксель «не совпал», если хоть один канал разошёлся больше чем на столько. */
    private static final int MISMATCH = 40;
    private static final int PAGE_ITEMS = 12;
    private static final int PAGE_COLUMNS = 3;
    private static final int ZOOM = 2;

    private enum Stage
    {
        WINDOW,
        ORDER,
        WAIT_ICONS,
        OPEN_SCREEN,
        SETTLE,
        SHOT,
        WAIT_SHOT,
        WRITE,
        DONE
    }

    private record Sample(String name, ItemStack stack) {}

    private final List<Sample> samples = new ArrayList<>();
    private final List<CompletableFuture<byte[]>> orders = new ArrayList<>();
    private Stage stage = Stage.WINDOW;
    private long stageAt;
    private Path out = Path.of(".");
    private int guiScaleBefore;
    private int windowWBefore;
    private int windowHBefore;
    private volatile @Nullable int[] shot;
    private int shotW;
    private int shotH;
    private int failures;
    private boolean windowAsked;

    @Override
    public String name()
    {
        return "icons";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.level == null || mc.player == null)
        {
            return false;
        }

        try
        {
            this.out = CovOut.dir().resolve("icons");
            Files.createDirectories(this.out);
        }
        catch (IOException e)
        {
            CovOut.log(TAG + "папка не создалась: " + e);
            return false;
        }

        this.collect(mc);
        this.guiScaleBefore = mc.options.guiScale().get();
        this.windowWBefore = mc.getWindow().getWidth();
        this.windowHBefore = mc.getWindow().getHeight();
        CovOut.log(TAG + "предметов " + this.samples.size() + ", выход " + this.out);
        this.enter(Stage.WINDOW);
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long waited = Util.getMillis() - this.stageAt;

        switch (this.stage)
        {
            case WINDOW ->
            {
                if (mc.getWindow().getWidth() != WINDOW_W || mc.getWindow().getHeight() != WINDOW_H)
                {
                    if (! this.windowAsked)
                    {
                        this.windowAsked = true;
                        mc.getWindow().setWindowed(WINDOW_W, WINDOW_H);
                    }

                    if (waited < 5_000L)
                    {
                        return false;
                    }
                }

                mc.options.guiScale().set(GUI_SCALE);
                mc.resizeGui();

                // Экран Xvfb 1600×900 даёт не больше 3 (900 / 4 < 240): ставим 4 напрямую — сетке хватает 400×225
                if (mc.getWindow().getGuiScale() != GUI_SCALE)
                {
                    mc.getWindow().setGuiScale(GUI_SCALE);
                }
                CovOut.log(TAG + "окно " + mc.getWindow().getWidth() + "×" + mc.getWindow().getHeight() + ", GUI "
                        + mc.getWindow().getGuiScale());
                this.enter(Stage.ORDER);
            }
            case ORDER ->
            {
                ItemIcons.clearCache();

                for (Sample sample : this.samples)
                {
                    this.orders.add(ItemIcons.request(sample.stack(), SIZE));
                }

                this.enter(Stage.WAIT_ICONS);
            }
            case WAIT_ICONS ->
            {
                long ready = this.orders.stream().filter(CompletableFuture::isDone).count();

                if (ready < this.orders.size())
                {
                    if (waited > 60_000L)
                    {
                        CovOut.log(TAG + "FAIL иконки за минуту не пришли: " + ready + " из " + this.orders.size());
                        this.enter(Stage.DONE);
                    }

                    return false;
                }

                CovOut.log(TAG + "наши иконки пришли за " + waited + " мс: " + ready);
                this.enter(Stage.OPEN_SCREEN);
            }
            case OPEN_SCREEN ->
            {
                mc.gui.setScreen(new Grid(this.samples));
                this.enter(Stage.SETTLE);
            }
            case SETTLE ->
            {
                if (waited < 2_000L)
                {
                    return false;
                }

                this.enter(Stage.SHOT);
            }
            case SHOT ->
            {
                this.shot = null;
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image ->
                {
                    try (NativeImage owned = image)
                    {
                        int w = owned.getWidth();
                        int h = owned.getHeight();
                        int[] pixels = new int[w * h];

                        for (int y = 0; y < h; ++y)
                        {
                            for (int x = 0; x < w; ++x)
                            {
                                pixels[y * w + x] = owned.getPixel(x, y);
                            }
                        }

                        this.shotW = w;
                        this.shotH = h;
                        this.shot = pixels;
                    }
                });
                this.enter(Stage.WAIT_SHOT);
            }
            case WAIT_SHOT ->
            {
                if (this.shot == null)
                {
                    if (waited > 10_000L)
                    {
                        CovOut.log(TAG + "FAIL снимок экрана не пришёл");
                        this.enter(Stage.DONE);
                    }

                    return false;
                }

                this.enter(Stage.WRITE);
            }
            case WRITE ->
            {
                this.write(mc);
                this.enter(Stage.DONE);
            }
            case DONE ->
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void close()
    {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(null);

        if (this.windowWBefore > 0 && this.windowHBefore > 0)
        {
            mc.getWindow().setWindowed(this.windowWBefore, this.windowHBefore);
        }

        mc.options.guiScale().set(this.guiScaleBefore);
        mc.resizeGui();
        CovOut.log("[icons] предметов " + this.samples.size() + ", сбоев " + this.failures + ", выход " + this.out);
    }

    private void enter(Stage next)
    {
        this.stage = next;
        this.stageAt = Util.getMillis();
    }

    // ===== Набор =====

    private void collect(Minecraft mc)
    {
        String[] ids = {
                "anvil", "campfire", "soul_campfire", "chest", "trapped_chest", "ender_chest", "shulker_box",
                "red_shulker_box", "blue_shulker_box", "lime_shulker_box", "red_bed", "player_head", "zombie_head",
                "creeper_head", "white_banner", "decorated_pot", "shield", "conduit", "bell", "enchanting_table", "lectern",
                "oak_sign", "oak_hanging_sign", "candle", "sea_pickle", "turtle_egg", "glow_berries", "vine", "oak_fence",
                "cobblestone_wall", "oak_stairs", "stone_slab", "glass_pane", "iron_bars", "torch", "lantern", "iron_chain",
                "flower_pot", "armor_stand", "item_frame", "painting", "water_bucket", "diamond_sword", "potion",
                "written_book", "trident", "spyglass", "compass", "clock", "map", "filled_map", "grass_block", "stone",
                "oak_log", "glass", "red_stained_glass", "slime_block", "honey_block", "ice", "beacon", "end_rod",
                "lightning_rod", "copper_grate", "crafter", "vault", "trial_spawner", "white_cushion", "red_cushion",
                "oak_leaves", "leather_chestplate", "furnace", "carved_pumpkin", "hopper", "brewing_stand", "dragon_egg",
                "ladder", "rail", "oak_door", "cauldron", "redstone", "heavy_core", "copper_golem_statue", "oak_shelf"
        };

        for (String id : ids)
        {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));

            if (item == Items.AIR)
            {
                CovOut.log(TAG + "нет предмета " + id + " — пропуск");
                continue;
            }

            this.samples.add(new Sample(id, new ItemStack(item)));
        }

        // Данные, по которым строки выгрузки различаются: узор флага, черепки вазы, зелье, краска, блеск
        HolderGetter<BannerPattern> patterns = mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        ItemStack banner = new ItemStack(Items.BANNER.pick(DyeColor.RED));
        banner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
                .addIfRegistered(patterns, BannerPatterns.CREEPER, DyeColor.BLACK)
                .addIfRegistered(patterns, BannerPatterns.BORDER, DyeColor.YELLOW)
                .build());
        this.samples.add(new Sample("red_banner+creeper", banner));

        ItemStack pot = new ItemStack(Items.DECORATED_POT);
        pot.set(DataComponents.POT_DECORATIONS, new PotDecorations(sherd("angler_pottery_sherd"),
                sherd("archer_pottery_sherd"), Optional.empty(), sherd("heart_pottery_sherd")));
        this.samples.add(new Sample("decorated_pot+sherds", pot));

        this.samples.add(new Sample("potion_healing", PotionContents.createItemStack(Items.POTION, Potions.HEALING)));

        ItemStack armor = new ItemStack(Items.LEATHER_CHESTPLATE);
        armor.set(DataComponents.DYED_COLOR, new DyedItemColor(0x3366CC));
        this.samples.add(new Sample("leather_chestplate+blue", armor));

        ItemStack shiny = new ItemStack(Items.DIAMOND_SWORD);
        shiny.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        this.samples.add(new Sample("diamond_sword+glint", shiny));
    }

    private static Optional<ItemStackTemplate> sherd(String id)
    {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));

        return item == Items.AIR ? Optional.empty() : Optional.of(new ItemStackTemplate(item));
    }

    // ===== Выход =====

    private void write(Minecraft mc)
    {
        int[] screen = this.shot;
        int scale = mc.getWindow().getGuiScale();
        int cell = 16 * scale;

        if (screen == null || cell != SIZE)
        {
            CovOut.log(TAG + "FAIL масштаб GUI " + scale + " — клетка " + cell + " px вместо " + SIZE);
            ++this.failures;
            return;
        }

        int count = this.samples.size();
        int rows = (count + COLUMNS - 1) / COLUMNS;
        int pitch = PITCH * scale;
        int sheetW = COLUMNS * pitch;
        int sheetH = rows * pitch;

        BufferedImage ours = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage gui = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB);
        BufferedImage side = new BufferedImage(sheetW * 2 + pitch, sheetH, BufferedImage.TYPE_INT_ARGB);
        List<int[]> oursOnBackground = new ArrayList<>();
        List<int[]> guiCells = new ArrayList<>();

        try (BufferedWriter csv = Files.newBufferedWriter(this.out.resolve("icons.csv"), StandardCharsets.UTF_8))
        {
            csv.write("item,mismatch_pct,mean_abs_diff,ours_bbox,gui_bbox,ours_transparent_corners\n");

            for (int i = 0; i < count; ++i)
            {
                int col = i % COLUMNS;
                int row = i / COLUMNS;
                int[] icon = decode(this.orders.get(i).getNow(null));
                int[] composed = new int[SIZE * SIZE];
                int[] fromScreen = new int[SIZE * SIZE];
                int sx = (ORIGIN + col * PITCH) * scale;
                int sy = (ORIGIN + row * PITCH) * scale;

                for (int y = 0; y < SIZE; ++y)
                {
                    for (int x = 0; x < SIZE; ++x)
                    {
                        int px = sx + x;
                        int py = sy + y;
                        fromScreen[y * SIZE + x] = px < this.shotW && py < this.shotH ? screen[py * this.shotW + px] | 0xFF000000 : 0;
                        composed[y * SIZE + x] = over(icon == null ? 0 : icon[y * SIZE + x], BACKGROUND);
                    }
                }

                if (icon != null)
                {
                    ours.setRGB(col * pitch, row * pitch, SIZE, SIZE, icon, 0, SIZE);
                }
                else
                {
                    ++this.failures;
                }

                gui.setRGB(col * pitch, row * pitch, SIZE, SIZE, fromScreen, 0, SIZE);
                side.setRGB(col * pitch, row * pitch, SIZE, SIZE, composed, 0, SIZE);
                side.setRGB(sheetW + pitch + col * pitch, row * pitch, SIZE, SIZE, fromScreen, 0, SIZE);
                oursOnBackground.add(composed);
                guiCells.add(fromScreen);

                Diff diff = diff(composed, fromScreen);
                String name = this.samples.get(i).name();
                csv.write(String.format(Locale.ROOT, "%s,%.1f,%.2f,%s,%s,%s%n", name, diff.mismatchPct, diff.meanAbs,
                        bbox(composed), bbox(fromScreen), icon != null && transparentCorners(icon)));
                CovOut.log(String.format(Locale.ROOT, "%s%s %s: расхождение %.1f%% пикселей, средняя разница %.2f, рамка ours %s gui %s",
                        TAG, diff.mismatchPct > 2.0 ? "DIFF" : "OK  ", name, diff.mismatchPct, diff.meanAbs, bbox(composed),
                        bbox(fromScreen)));
            }
        }
        catch (IOException | RuntimeException e)
        {
            CovOut.log(TAG + "FAIL icons.csv: " + e);
            ++this.failures;
        }

        save(ours, "icons-ours.png");
        save(gui, "icons-gui.png");
        save(side, "icons-side-by-side.png");
        this.pages(oursOnBackground, guiCells);
    }

    /** Крупные страницы: наша на фоне экрана · GUI · разница ×4, подпись под тройкой. */
    private void pages(List<int[]> ours, List<int[]> gui)
    {
        int tileW = (3 * SIZE + 2 * 4) * ZOOM;
        int tileH = SIZE * ZOOM + 22;
        int gap = 16;

        for (int page = 0; page * PAGE_ITEMS < ours.size(); ++page)
        {
            int from = page * PAGE_ITEMS;
            int to = Math.min(ours.size(), from + PAGE_ITEMS);
            int rows = (to - from + PAGE_COLUMNS - 1) / PAGE_COLUMNS;
            BufferedImage image = new BufferedImage(PAGE_COLUMNS * (tileW + gap) + gap, rows * (tileH + gap) + gap,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            try
            {
                g.setColor(new Color(0x2B2B33));
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

                for (int i = from; i < to; ++i)
                {
                    int x = gap + ((i - from) % PAGE_COLUMNS) * (tileW + gap);
                    int y = gap + ((i - from) / PAGE_COLUMNS) * (tileH + gap);
                    int[] mine = ours.get(i);
                    int[] real = gui.get(i);
                    int[] delta = new int[SIZE * SIZE];

                    for (int p = 0; p < delta.length; ++p)
                    {
                        int d = Math.min(0xFF, 4 * maxChannelDiff(mine[p], real[p]));
                        delta[p] = 0xFF000000 | (d << 16) | ((d / 3) << 8) | (d / 3);
                    }

                    g.drawImage(picture(mine), x, y, SIZE * ZOOM, SIZE * ZOOM, null);
                    g.drawImage(picture(real), x + (SIZE + 4) * ZOOM, y, SIZE * ZOOM, SIZE * ZOOM, null);
                    g.drawImage(picture(delta), x + 2 * (SIZE + 4) * ZOOM, y, SIZE * ZOOM, SIZE * ZOOM, null);

                    Diff diff = diff(mine, real);
                    g.setColor(diff.mismatchPct > 2.0 ? new Color(0xFFB4A0) : new Color(0xD8F5D0));
                    g.drawString(String.format(Locale.ROOT, "%s — наша · GUI · разница; %.1f%%", this.samples.get(i).name(),
                            diff.mismatchPct), x, y + SIZE * ZOOM + 16);
                }
            }
            finally
            {
                g.dispose();
            }

            save(image, String.format(Locale.ROOT, "compare-%02d.png", page + 1));
        }
    }

    private void save(BufferedImage image, String file)
    {
        try
        {
            ImageIO.write(image, "png", this.out.resolve(file).toFile());
            CovOut.log(TAG + "записан " + this.out.resolve(file));
        }
        catch (IOException e)
        {
            CovOut.log(TAG + "FAIL " + file + ": " + e);
            ++this.failures;
        }
    }

    private static BufferedImage picture(int[] argb)
    {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, SIZE, SIZE, argb, 0, SIZE);
        return image;
    }

    private static int @Nullable [] decode(byte @Nullable [] png)
    {
        if (png == null)
        {
            return null;
        }

        try
        {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

            if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE)
            {
                return null;
            }

            return image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Наш пиксель (альфа не домножена) поверх непрозрачного фона — как GUI кладёт иконку на экран. */
    private static int over(int top, int bottom)
    {
        int a = top >>> 24;

        if (a == 0xFF)
        {
            return top;
        }

        int r = (((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * (0xFF - a) + 127) / 0xFF;
        int g = (((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * (0xFF - a) + 127) / 0xFF;
        int b = ((top & 0xFF) * a + (bottom & 0xFF) * (0xFF - a) + 127) / 0xFF;

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private record Diff(double mismatchPct, double meanAbs) {}

    private static Diff diff(int[] a, int[] b)
    {
        int bad = 0;
        long sum = 0L;

        for (int i = 0; i < a.length; ++i)
        {
            int d = maxChannelDiff(a[i], b[i]);
            sum += d;

            if (d > MISMATCH)
            {
                ++bad;
            }
        }

        return new Diff(100.0 * bad / a.length, (double) sum / a.length);
    }

    private static int maxChannelDiff(int a, int b)
    {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));

        return Math.max(dr, Math.max(dg, db));
    }

    /** Рамка пикселей, отличных от фона экрана: «x0..x1×y0..y1». */
    private static String bbox(int[] cell)
    {
        int x0 = SIZE;
        int y0 = SIZE;
        int x1 = -1;
        int y1 = -1;

        for (int y = 0; y < SIZE; ++y)
        {
            for (int x = 0; x < SIZE; ++x)
            {
                if (maxChannelDiff(cell[y * SIZE + x], BACKGROUND) > 6)
                {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }

        return x1 < 0 ? "пусто" : x0 + ".." + x1 + "×" + y0 + ".." + y1;
    }

    private static boolean transparentCorners(int[] icon)
    {
        return (icon[0] >>> 24) == 0 && (icon[SIZE - 1] >>> 24) == 0 && (icon[SIZE * (SIZE - 1)] >>> 24) == 0
                && (icon[SIZE * SIZE - 1] >>> 24) == 0;
    }

    /** Экран-сетка: те же предметы ванильным путём GUI ({@code GuiGraphicsExtractor.item}) на сером фоне. */
    private static final class Grid extends Screen
    {
        private final List<Sample> samples;

        Grid(List<Sample> samples)
        {
            super(Component.literal("Item icons check"));
            this.samples = samples;
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
        {
            graphics.fill(0, 0, this.width, this.height, BACKGROUND);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)
        {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);

            for (int i = 0; i < this.samples.size(); ++i)
            {
                graphics.item(this.samples.get(i).stack(), ORIGIN + (i % COLUMNS) * PITCH, ORIGIN + (i / COLUMNS) * PITCH);
            }
        }

        @Override
        public boolean isPauseScreen()
        {
            return false;
        }
    }
}
