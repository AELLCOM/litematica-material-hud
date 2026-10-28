package com.aell.layerlist.schematics.gui.materials;

import com.aell.layerlist.schematics.materials.ReplacementStates;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Tooltip;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * Выбор блока на замену (0.0.27, «Материалы»): что меняем — сверху, ниже поиск и список блоков с
 * иконками, как в меню креатива (настоящие предметы игры, официальные названия языка игры). Похожие
 * на заменяемый блок — сверху: того же вида (ступеньки к ступенькам) или с тем же окончанием id
 * ({@code _planks}, {@code _glass}); дальше все остальные в порядке реестра.
 *
 * <p>Поиск — по названию и по id (каждое слово где-то в названии или id; «spruce pl» и «еловые»
 * находят еловые доски), первый найденный сразу выбран: набрал — Enter. ↑/↓, PgUp/PgDn из поля
 * двигают выбор, двойной клик по строке — сразу заменить. В списке только блоки, у которых есть свой
 * предмет и которые включены в этом мире (экспериментальные — только с флагом мира).
 */
@Environment(EnvType.CLIENT)
public final class BlockPicker extends Modal
{
    private static final String KEY = "layerlist.schematics.materials.replace.";
    private static final int WIDTH = 360;
    private static final int LIST_ROWS = 8;
    /** Меньше строк список не ужимается: в совсем низком окне прокручивается уже тело окна. */
    private static final int LIST_ROWS_MIN = 3;
    /**
     * Сколько после открытия двойной клик в окне не считается двойным: выбор открывает первый клик по
     * строке таблицы, и второй клик того же двойного щелчка, попав в список, иначе сразу заменил бы
     * блок тем, что оказался под мышью.
     */
    private static final long OPEN_GUARD_MS = 500L;
    private static final int ICON = 16;
    /** Меньше этого места под название — id справа не пишется. */
    private static final int NAME_MIN = 80;

    /**
     * Что заменяем.
     *
     * @param stack    предмет строки (иконка)
     * @param name     название строки
     * @param count    сколько в схеме, уже с разрядами и словом («7 шт»)
     * @param current  блок строки — его в списке нет
     * @param onChoose выбран блок
     */
    public record Request(ItemStack stack, String name, String count, @Nullable Block current, Consumer<Block> onChoose) {}

    private record Candidate(Block block, ItemStack stack, String name, String id, String haystack) {}

    private final Request request;
    private final String paragraph;
    private final TextInput search;
    private final ScrollList<Candidate> list;
    private final Button apply;
    private final List<Candidate> all;
    private List<Candidate> shown = List.of();
    private @Nullable String filtered;
    private final long openedAt = Util.getMillis();

    private BlockPicker(Layers layers, Request request)
    {
        super(layers, t("title"), Strings.t("close"));

        this.request = request;
        this.paragraph = I18n.get(KEY + "what", request.name());
        this.width(WIDTH);
        this.text(this.paragraph);

        Source source = new Source();
        this.row(source, Ui.ROW_H);

        this.search = new TextInput().hint(t("search_hint")).searchIcon(true).maxLength(64)
                .onChange(text -> this.filter()).onSubmit(text -> this.choose());
        this.row(this.search, Ui.CTRL_H);

        this.list = new ScrollList<Candidate>(this::renderCandidate).rowHeight(item -> Ui.ROW_H);
        this.list.listener(new ScrollList.Listener<>()
        {
            @Override
            public void activate(Candidate item, int index)
            {
                // Двойной клик по блоку — действие, со щелчком
                Vanilla.click();
                BlockPicker.this.choose();
            }
        });
        this.list.setFocused(true);
        this.row(new Frame(this.list), this::listHeight);

        Info info = new Info();
        this.row(info, Ui.TIP_LINE);

        Button cancel = new Button(t("cancel"), Button.Style.SECONDARY, this::cancel);
        this.apply = new Button(t("apply"), Button.Style.PRIMARY, this::choose)
                .tooltip(I18n.get(KEY + "apply.tip", request.name()));
        this.button(cancel);
        this.button(this.apply);
        this.defaultButton(this.apply);

        this.all = candidates(request.current());
    }

    /** Открыть поверх экрана; поле поиска сразу в фокусе — можно печатать. */
    public static BlockPicker open(Layers layers, Request request)
    {
        BlockPicker picker = new BlockPicker(layers, request);
        picker.filter();
        picker.open();
        picker.search.setFocused(true);

        return picker;
    }

    private static String t(String suffix)
    {
        return Strings.plain(KEY + suffix);
    }

    /**
     * Высота списка: сколько строк влезает в экран вместе с остальным окном (при GUI 3 и в низком окне
     * — меньше восьми), чтобы рамка списка и строка «Найдено» не уезжали под край прокрутки тела.
     * Счёт — по раскладке {@link Modal}: шапка, подвал, поля, абзац с переносом, строки и зазоры между ними.
     */
    private int listHeight(int width)
    {
        int text = Kit.wrap(Kit.font(), this.paragraph, width).size() * Ui.TIP_LINE;
        int chrome = Ui.ROW_H + Ui.FOOTER_H + Ui.PAD_GROUP * 2;
        int rows = Ui.PAD_IN + Ui.ROW_H + Ui.PAD_IN + Ui.CTRL_H + Ui.PAD_IN + Ui.PAD_IN + Ui.TIP_LINE;
        int room = this.layers.screenH() - Ui.OUTER * 2 - chrome - text - rows - Ui.BORDER * 2;

        return Ui.clamp(room / Ui.ROW_H, LIST_ROWS_MIN, LIST_ROWS) * Ui.ROW_H + Ui.BORDER * 2;
    }

    // ===== Список =====

    /** Блоки, которые можно поставить: основной блок своего предмета, включённый в этом мире; похожие — первыми. */
    private static List<Candidate> candidates(@Nullable Block current)
    {
        Minecraft mc = Minecraft.getInstance();
        FeatureFlagSet features = mc.level == null ? null : mc.level.enabledFeatures();
        List<Candidate> similar = new ArrayList<>();
        List<Candidate> rest = new ArrayList<>();

        for (Block block : BuiltInRegistries.BLOCK)
        {
            if (block == current || ! ReplacementStates.main(block))
            {
                continue;
            }

            if (features != null && ! block.asItem().isEnabled(features))
            {
                continue;
            }

            ItemStack stack = new ItemStack(block.asItem());
            String name = stack.getHoverName().getString();
            String id = ReplacementStates.id(block).toString();
            String haystack = (name + '\n' + id + '\n' + ReplacementStates.shortId(block).replace('_', ' '))
                    .toLowerCase(Locale.ROOT);
            Candidate candidate = new Candidate(block, stack, name, id, haystack);

            (current != null && similar(current, block) ? similar : rest).add(candidate);
        }

        similar.addAll(rest);
        return similar;
    }

    /** Того же вида (класс блока, если он не просто «блок») или с тем же окончанием id. */
    private static boolean similar(Block a, Block b)
    {
        if (a.getClass() != Block.class && a.getClass() == b.getClass())
        {
            return true;
        }

        return tail(a).equals(tail(b));
    }

    private static String tail(Block block)
    {
        String path = ReplacementStates.id(block).getPath();
        int cut = path.lastIndexOf('_');

        return cut < 0 ? path : path.substring(cut + 1);
    }

    private void filter()
    {
        String query = this.search.text().strip().toLowerCase(Locale.ROOT);

        if (query.equals(this.filtered))
        {
            return;
        }

        this.filtered = query;
        String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
        List<Candidate> out = new ArrayList<>();

        for (Candidate candidate : this.all)
        {
            boolean hit = true;

            for (String word : words)
            {
                hit &= candidate.haystack().contains(word);
            }

            if (hit)
            {
                out.add(candidate);
            }
        }

        this.shown = out;
        this.list.setItems(out);
        // Сначала выбор, потом прокрутка: до первой раскладки у списка нет высоты, и «показать выбранную»
        // увела бы прокрутку вниз
        this.list.setSelectedIndex(out.isEmpty() ? -1 : 0);
        this.list.setScroll(0);
        this.list.setEmptyText(query.isEmpty() ? "" : I18n.get(KEY + "none", this.search.text().strip()));
    }

    private void choose()
    {
        Candidate chosen = this.list.selectedItem();

        if (chosen == null)
        {
            return;
        }

        this.dismiss();
        this.request.onChoose().accept(chosen.block());
    }

    private void renderCandidate(GuiContext ctx, Candidate item, int index, int x, int y, int w, int h, boolean selected,
            boolean hovered, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        ctx.renderItem(item.stack(), x + Ui.PAD_IN, y + (h - ICON) / 2);

        int textX = x + Ui.PAD_IN + ICON + Ui.PAD_IN;
        int right = x + w - Ui.PAD_IN;
        int idW = font.width(item.id());
        boolean showId = right - textX - idW - Ui.PAD_GROUP >= NAME_MIN;
        int room = right - textX - (showId ? idW + Ui.PAD_GROUP : 0);
        String name = Paint.fit(font, item.name(), room);

        Paint.midText(ctx, font, name, textX, y, h, selected ? Ui.C_TEXT : Ui.C_TEXT_2, false);

        if (showId)
        {
            Paint.midTextRight(ctx, font, item.id(), right, y, h, Ui.C_TEXT_3, false);
        }

        if (hovered)
        {
            Tooltip.offer(List.of("block", item.id()), item.name() + "\n" + item.id());
        }
    }

    // ===== Окно =====

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        this.filter();
        this.apply.setEnabled(this.list.selectedItem() != null);
        super.render(ctx, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        boolean fresh = Util.getMillis() - this.openedAt < OPEN_GUARD_MS;
        return super.mouseClicked(click, doubleClick && ! fresh);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        int code = key.key();

        // Стрелки и страницы из поля поиска — по списку (в поле им делать нечего)
        if (code == InputConstants.KEY_UP || code == InputConstants.KEY_DOWN
                || code == InputConstants.KEY_PAGEUP || code == InputConstants.KEY_PAGEDOWN)
        {
            this.list.keyPressed(key);
            return true;
        }

        return super.keyPressed(key);
    }

    /** Что меняем: иконка, название и сколько его в схеме. */
    private final class Source extends Widget
    {
        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            Font font = Kit.font();
            Request request = BlockPicker.this.request;
            ctx.renderItem(request.stack(), this.x, this.y + (this.h - ICON) / 2);

            int textX = this.x + ICON + Ui.PAD_IN;
            String count = Ui.TITLE_SEPARATOR + request.count();
            int room = this.x + this.w - textX;
            String name = Paint.fit(font, request.name(), Math.max(0, room - font.width(count)));

            Paint.midText(ctx, font, name, textX, this.y, this.h, Ui.C_TEXT, false);
            Paint.midText(ctx, font, Paint.fit(font, count, room - font.width(name)), textX + font.width(name), this.y,
                    this.h, Ui.C_TEXT_3, false);
        }
    }

    /** Рамка вокруг списка: строки внутри на {@link Ui#BORDER} от края. */
    private static final class Frame extends Widget
    {
        private final ScrollList<?> inner;

        private Frame(ScrollList<?> inner)
        {
            this.inner = inner;
        }

        @Override
        public void setBounds(int x, int y, int w, int h)
        {
            super.setBounds(x, y, w, h);
            this.inner.setBounds(x + Ui.BORDER, y + Ui.BORDER, Math.max(0, w - Ui.BORDER * 2), Math.max(0, h - Ui.BORDER * 2));
        }

        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            if (Style.vanilla())
            {
                // Фон списка Minecraft с тёмной рамкой вместо тонкой рамки Aell
                Vanilla.panel(ctx, this.x, this.y, this.w, this.h);
            }
            else
            {
                Paint.roundBorder(ctx, this.x, this.y, this.w, this.h, Ui.R_CTRL, Ui.C_DIVIDER);
            }

            this.inner.render(ctx, mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
        {
            return this.inner.contains(click.x(), click.y()) && this.inner.mouseClicked(click, doubleClick);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
        {
            return this.inner.mouseDragged(click, dx, dy);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent click)
        {
            return this.inner.mouseReleased(click);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount)
        {
            return this.inner.mouseScrolled(mouseX, mouseY, amount);
        }
    }

    /** Строка под списком: сколько блоков и сколько найдено. */
    private final class Info extends Widget
    {
        @Override
        public void render(GuiContext ctx, int mouseX, int mouseY)
        {
            BlockPicker picker = BlockPicker.this;
            String text = picker.search.text().isBlank()
                    ? I18n.get(KEY + "found", Kit.group(picker.all.size(), Strings.plain("layerlist.schematics.num_group")))
                    : I18n.get(KEY + "found_filtered", picker.shown.size(), picker.all.size());

            Paint.midText(ctx, Kit.font(), Paint.fit(Kit.font(), text, this.w), this.x, this.y, this.h, Ui.C_TEXT_3, false);
        }
    }

    /**
     * Узкий вход для харнесса покрытия (режим {@code k}): найти открытый выбор, его поле, строки и
     * центр строки — дальше харнесс жмёт и печатает настоящими событиями. В игре его никто не зовёт.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        public static @Nullable BlockPicker current(Layers layers)
        {
            return layers.top() instanceof BlockPicker picker ? picker : null;
        }

        public static TextInput search(BlockPicker picker)
        {
            return picker.search;
        }

        public static Button apply(BlockPicker picker)
        {
            return picker.apply;
        }

        /** Видимые блоки по порядку списка — id. */
        public static List<String> shown(BlockPicker picker)
        {
            List<String> ids = new ArrayList<>();
            picker.shown.forEach(candidate -> ids.add(candidate.id()));
            return ids;
        }

        public static int total(BlockPicker picker)
        {
            return picker.all.size();
        }

        public static @Nullable String selected(BlockPicker picker)
        {
            Candidate candidate = picker.list.selectedItem();
            return candidate == null ? null : candidate.id();
        }

        /** Название строки списка по id (как его видит игрок). */
        public static @Nullable String name(BlockPicker picker, String id)
        {
            for (Candidate candidate : picker.all)
            {
                if (candidate.id().equals(id))
                {
                    return candidate.name();
                }
            }

            return null;
        }

        /** Центр строки {@code id} на экране или {@code null}, если она не видна целиком. */
        public static double @Nullable [] row(BlockPicker picker, String id)
        {
            ScrollList<Candidate> list = picker.list;
            List<Candidate> items = list.items();

            for (int i = 0; i < items.size(); ++i)
            {
                if (! items.get(i).id().equals(id))
                {
                    continue;
                }

                int top = list.y() + i * Ui.ROW_H - list.scroll();

                if (top < list.y() || top + Ui.ROW_H > list.y() + list.h())
                {
                    return null;
                }

                return new double[] { list.x() + list.w() / 2.0, top + Ui.ROW_H / 2.0 };
            }

            return null;
        }

        /** Где лежит список: {x, y, w, h}. */
        public static int[] listBox(BlockPicker picker)
        {
            return new int[] { picker.list.x(), picker.list.y(), picker.list.w(), picker.list.h() };
        }
    }
}
