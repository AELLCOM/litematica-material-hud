package com.aell.layerlist.schematics.gui;

import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Tooltip;
import fi.dy.masa.malilib.render.GuiContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Подвкладка «Корзина» (0.0.27): что удалено из «Библиотеки» — имя, из какой папки, размер, когда удалено и
 * сколько дней осталось до удаления насовсем. У строки — «Восстановить» (в исходную папку; имя занято —
 * «Имя (2)») и «Удалить навсегда» (с подтверждением); в подвале экрана — «Очистить корзину (N)» (с
 * подтверждением и числом) и «Открыть папку» корзины. Итог действия — строкой над списком. Сначала корзина была
 * окном поверх «Библиотеки» ({@code TrashDialog}); по просьбе пользователя стала вкладкой экрана рядом с
 * «Материалами», строка «Корзина (N)» внизу корня списка файлов теперь переключает на неё.
 */
@Environment(EnvType.CLIENT)
final class TrashPane extends Pane
{
    /** Три строки текста: имя; откуда и размер; когда удалено и сколько осталось — при GUI 3 одной строкой не влезает. */
    private static final int ROW_H = 3 * Ui.TIP_LINE + 2 * Ui.PAD_MICRO + Ui.PAD_IN * 2;
    /** Осталось столько дней и меньше — срок янтарным. */
    private static final int DAYS_WARN = 3;
    private static final int TEXT_MIN = 90;
    /** Полоска цвета смысла слева от строки итога. */
    private static final int BAR = 2;

    private final ScrollList<SchematicTrash.Item> list;
    private final Button folder;
    private final Button empty;
    private final Map<String, Button[]> rowButtons = new HashMap<>();
    private List<SchematicTrash.Item> items = List.of();
    private String status = "";
    private int statusColor = Ui.C_TEXT_2;

    TrashPane(GuiSchematics screen)
    {
        super(screen);

        this.list = new ScrollList<SchematicTrash.Item>(this::renderRow)
        {
            @Override
            public boolean mouseReleased(MouseButtonEvent click)
            {
                for (Button[] buttons : TrashPane.this.rowButtons.values())
                {
                    for (Button button : buttons)
                    {
                        button.mouseReleased(click);
                    }
                }

                return super.mouseReleased(click);
            }
        }.rowHeight(item -> ROW_H).showSelection(false).listener(new ScrollList.Listener<>()
        {
            @Override
            public boolean click(SchematicTrash.Item item, int index, int rowX, int rowY, int rowW, int rowH,
                    MouseButtonEvent click)
            {
                for (Button button : TrashPane.this.buttons(item))
                {
                    if (button.mouseClicked(click, false))
                    {
                        return true;
                    }
                }

                return true;
            }
        });
        this.list.setEmptyText(Tr.t("trash.empty_list"));

        this.folder = new Button(Tr.t("trash.open_folder"), Button.Style.SECONDARY, this::openFolder)
                .glyph(Glyphs.OPEN_FOLDER).tooltip(SchematicTrash.get().root().toString());
        this.empty = new Button("", Button.Style.DANGER, this::confirmEmpty).tooltip(Tr.t("trash.empty.tip"));
        this.reload();
    }

    /** Перечитать корзину: при входе во вкладку, после действия здесь или удаления в «Библиотеке». */
    void reload()
    {
        try
        {
            this.items = SchematicTrash.get().items();
        }
        catch (RuntimeException e)
        {
            this.items = List.of();
        }

        this.list.setItems(this.items);
        this.rowButtons.keySet().retainAll(this.items.stream().map(SchematicTrash.Item::id).toList());
        this.empty.label(Tr.f("trash.empty", Tr.num(this.items.size())));
        this.empty.setEnabled(! this.items.isEmpty());
    }

    /** Войти во вкладку: свежий список, прежний итог не показывать. */
    void entered()
    {
        this.status = "";
        this.reload();
    }

    /** Сообщение экрана (итог чистки просроченного при открытии) — в строку итога вкладки. */
    void notice(String text)
    {
        this.show(text, Ui.C_TEXT_2);
    }

    List<SchematicTrash.Item> items()
    {
        return this.items;
    }

    @Override
    void tick()
    {
        // Удалили или вернули файл, пока вкладка открыта (тост «Отменить», чистка просроченного) — догоняем
        if (this.visible() && SchematicTrash.get().count() != this.items.size())
        {
            this.reload();
        }
    }

    private Button[] buttons(SchematicTrash.Item item)
    {
        return this.rowButtons.computeIfAbsent(item.id(), id -> new Button[] {
                new Button(Tr.t("trash.restore"), Button.Style.SECONDARY, () -> this.restore(item))
                        .accent(Theme.GREEN).tooltip(Tr.t("trash.restore.tip")),
                new Button(Tr.t("trash.forever"), Button.Style.DANGER, () -> this.confirmForever(item))
                        .tooltip(Tr.t("trash.forever.tip")) });
    }

    private void show(String text, int color)
    {
        this.status = text;
        this.statusColor = color;
        this.setBounds(this.x, this.y, this.w, this.h);
    }

    // ===== Действия =====

    private void restore(SchematicTrash.Item item)
    {
        Path back;

        try
        {
            back = SchematicTrash.get().restore(item, SchematicLibrary.get().root());
        }
        catch (IOException | RuntimeException e)
        {
            this.reload();
            this.show(Tr.f("trash.failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        this.screen.fileBack(back);
        this.reload();

        String shown = back.getFileName() == null ? item.name() : back.getFileName().toString();
        boolean renamed = item.original() != null && ! back.equals(item.original());
        this.show(renamed ? Tr.f("remove.undone_renamed", shown) : Tr.f("trash.restored", shown, this.from(back)),
                Theme.GREEN);
    }

    private void confirmForever(SchematicTrash.Item item)
    {
        Modal.confirm(this.screen.layers, Tr.t("trash.forever.title"), Tr.f("trash.forever.text", item.name()),
                Tr.t("trash.forever"), true, () ->
                {
                    String result;
                    int color;

                    try
                    {
                        SchematicTrash.get().deleteForever(item);
                        dropThumbnails(List.of(item));
                        result = Tr.f("trash.deleted_forever", item.name());
                        color = Ui.C_WARN;
                    }
                    catch (IOException | RuntimeException e)
                    {
                        result = Tr.f("trash.failed", Tr.reason(e));
                        color = Ui.C_DANGER;
                    }

                    this.reload();
                    this.show(result, color);
                    this.screen.trashChanged();
                }, Tr.t("cancel"));
    }

    private void confirmEmpty()
    {
        int count = this.items.size();

        if (count == 0)
        {
            return;
        }

        Modal.confirm(this.screen.layers, Tr.t("trash.empty.title"), Tr.f("trash.empty.text", Tr.num(count)),
                Tr.t("trash.empty.yes"), true, () ->
                {
                    String result;
                    int color;

                    try
                    {
                        List<SchematicTrash.Item> gone = SchematicTrash.get().empty();
                        dropThumbnails(gone);
                        result = Tr.f("trash.emptied", Tr.num(gone.size()));
                        color = Ui.C_WARN;
                    }
                    catch (IOException | RuntimeException e)
                    {
                        result = Tr.f("trash.failed", Tr.reason(e));
                        color = Ui.C_DANGER;
                    }

                    this.reload();
                    this.show(result, color);
                    this.screen.trashChanged();
                }, Tr.t("cancel"));
    }

    /** Удалено насовсем: PNG миниатюр этих файлов на диске больше не нужны. */
    static void dropThumbnails(List<SchematicTrash.Item> gone)
    {
        for (SchematicTrash.Item item : gone)
        {
            if (item.original() != null && ! item.directory())
            {
                try
                {
                    SchematicLibrary.get().dropThumbnailFiles(item.original());
                }
                catch (RuntimeException e)
                {
                    // Миниатюра осталась на диске — место, не поломка
                }
            }
        }
    }

    private void openFolder()
    {
        Path root = SchematicTrash.get().root();

        try
        {
            Files.createDirectories(root);
        }
        catch (IOException | RuntimeException e)
        {
            this.show(Tr.f("toast.open_failed", Tr.reason(e)), Ui.C_DANGER);
            return;
        }

        this.screen.openInSystem(root);
    }

    // ===== Подвал =====

    /** Первая — самая правая: «Открыть папку», левее — «Очистить корзину (N)». */
    @Override
    List<Button> footerActions()
    {
        return List.of(this.folder, this.empty);
    }

    /** «В корзине: 3 · 12 КБ» — сколько записей и сколько места занимают файлы (у папок размера нет). */
    @Override
    String footerStatus()
    {
        if (this.items.isEmpty())
        {
            return Tr.t("trash.empty_list");
        }

        long bytes = 0L;

        for (SchematicTrash.Item item : this.items)
        {
            bytes += item.directory() ? 0L : item.size();
        }

        return Tr.f("trash.status", Tr.num(this.items.size()), Tr.size(bytes));
    }

    // ===== Раскладка и отрисовка =====

    /** Пояснение про срок и строка итога над списком, с переносом по ширине панели. */
    private List<String> headLines(int width)
    {
        return Kit.wrap(Kit.font(), Tr.f("trash.text", SchematicTrash.KEEP_DAYS), width);
    }

    private List<String> statusLines(int width)
    {
        return this.status.isEmpty() ? List.of() : Kit.wrap(Kit.font(), this.status, Math.max(0, width - BAR - Ui.PAD_REL));
    }

    private int headHeight(int width)
    {
        int head = this.headLines(width).size() * Ui.TIP_LINE;
        int status = this.statusLines(width).size() * Ui.TIP_LINE;

        return head + (status > 0 ? Ui.PAD_IN + status : 0) + Ui.PAD_IN;
    }

    @Override
    public void setBounds(int x, int y, int w, int h)
    {
        super.setBounds(x, y, w, h);

        int innerW = Math.max(0, w - Ui.OUTER * 2);
        int top = y + Ui.PAD_IN + this.headHeight(innerW);
        this.list.setBounds(x + Ui.OUTER, top, innerW, Math.max(0, y + h - Ui.PAD_IN - top));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY)
    {
        if (! this.visible)
        {
            return;
        }

        Font font = Kit.font();
        int left = this.x + Ui.OUTER;
        int innerW = Math.max(0, this.w - Ui.OUTER * 2);
        int ty = this.y + Ui.PAD_IN;

        for (String line : this.headLines(innerW))
        {
            Paint.midText(ctx, font, line, left, ty, Ui.TIP_LINE, Ui.C_TEXT_2, false);
            ty += Ui.TIP_LINE;
        }

        List<String> status = this.statusLines(innerW);

        if (! status.isEmpty())
        {
            ty += Ui.PAD_IN;
            Paint.rect(ctx, left, ty, BAR, status.size() * Ui.TIP_LINE, this.statusColor);

            for (String line : status)
            {
                Paint.midText(ctx, font, line, left + BAR + Ui.PAD_REL, ty, Ui.TIP_LINE, Ui.C_TEXT, false);
                ty += Ui.TIP_LINE;
            }
        }

        this.list.render(ctx, mouseX, mouseY);
    }

    /** «из: _k/стройки» — папка относительно папки схем, корень — «папка схем»; вне её — полный путь. */
    private String from(@Nullable Path file)
    {
        Path parent = file == null ? null : file.getParent();

        if (parent == null)
        {
            return Tr.t("files.root");
        }

        Path root = SchematicLibrary.get().root();

        if (parent.startsWith(root))
        {
            String rel = root.relativize(parent).toString().replace('\\', '/');
            return rel.isEmpty() ? Tr.t("files.root") : rel;
        }

        return parent.toString();
    }

    /** «из: _k · 2 КБ» (вторая строка). */
    private String place(SchematicTrash.Item item)
    {
        return Tr.f("trash.from", this.from(item.original())) + Ui.TITLE_SEPARATOR
                + (item.directory() ? Tr.t("trash.folder") : Tr.size(item.size()));
    }

    /** «удалено 07.10.2026 13:49 · осталось 30 дн.» (третья строка). */
    private String when(SchematicTrash.Item item, long now)
    {
        return Tr.f("trash.deleted", Tr.dateTime(item.deletedAt())) + Ui.TITLE_SEPARATOR
                + Tr.f("trash.days_left", item.daysLeft(now));
    }

    /** Все сведения строки одной строкой (подсказка, харнесс). */
    private String meta(SchematicTrash.Item item, long now)
    {
        return this.place(item) + Ui.TITLE_SEPARATOR + this.when(item, now);
    }

    private void renderRow(GuiContext ctx, SchematicTrash.Item item, int index, int x, int y, int w, int h,
            boolean selected, boolean hovered, int mouseX, int mouseY)
    {
        Font font = Kit.font();
        Button[] buttons = this.buttons(item);
        Button restore = buttons[0];
        Button forever = buttons[1];

        int restoreW = restore.preferredWidth(font);
        int foreverW = forever.preferredWidth(font);
        int room = w - Ui.PAD_IN * 2 - Ui.PAD_MICRO - TEXT_MIN - Ui.PAD_REL;

        // Узкое окно: кнопки делят место поровну, подписи обрежутся «…», подсказка покажет целиком
        if (restoreW + foreverW > room)
        {
            restoreW = Math.max(Ui.BUTTON_PAD_X * 2, room / 2);
            foreverW = restoreW;
        }

        int by = y + (h - Ui.BUTTON_H) / 2;
        int bx = x + w - Ui.PAD_IN - foreverW;
        forever.setBounds(bx, by, foreverW, Ui.BUTTON_H);
        bx -= Ui.PAD_MICRO + restoreW;
        restore.setBounds(bx, by, restoreW, Ui.BUTTON_H);

        int iconW = Glyphs.width(Glyphs.FOLDER);
        int textX = x + Ui.PAD_IN + iconW + Ui.PAD_REL;
        int textW = Math.max(0, bx - Ui.PAD_REL - textX);
        int top = y + (h - Ui.TIP_LINE * 3 - Ui.PAD_MICRO * 2) / 2;

        if (item.directory())
        {
            Glyphs.draw(ctx, Glyphs.FOLDER, x + Ui.PAD_IN, y + (h - Glyphs.height(Glyphs.FOLDER)) / 2, Ui.C_TEXT_2);
        }
        else
        {
            Glyphs.draw(ctx, Glyphs.TRASH, x + Ui.PAD_IN + (iconW - Glyphs.width(Glyphs.TRASH)) / 2,
                    y + (h - Glyphs.height(Glyphs.TRASH)) / 2, Ui.C_TEXT_3);
        }

        long now = SchematicTrash.now();
        String place = this.place(item);
        String when = this.when(item, now);
        String shownName = Paint.fit(font, item.name(), textW);
        String shownPlace = Paint.fit(font, place, textW);
        String shownWhen = Paint.fit(font, when, textW);
        int lineStep = Ui.TIP_LINE + Ui.PAD_MICRO;

        Paint.midText(ctx, font, shownName, textX, top, Ui.TIP_LINE, Ui.C_TEXT, false);
        Paint.midText(ctx, font, shownPlace, textX, top + lineStep, Ui.TIP_LINE, Ui.C_TEXT_3, false);
        Paint.midText(ctx, font, shownWhen, textX, top + lineStep * 2, Ui.TIP_LINE,
                item.daysLeft(now) <= DAYS_WARN ? Ui.C_WARN : Ui.C_TEXT_3, false);

        restore.render(ctx, mouseX, mouseY);
        forever.render(ctx, mouseX, mouseY);

        boolean overText = Paint.hit(mouseX, mouseY, x, y, Math.max(0, bx - x), h);

        if (overText && (! shownName.equals(item.name()) || ! shownPlace.equals(place) || ! shownWhen.equals(when)))
        {
            Path original = item.original();
            Tooltip.offer(item, item.name() + "\n" + (original == null ? "" : original + "\n") + place + "\n" + when);
        }
    }

    String statusText()
    {
        return this.status;
    }

    // ===== Мышь и клавиатура =====

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        return this.list.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy)
    {
        return this.list.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click)
    {
        return this.list.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount)
    {
        return this.list.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent key)
    {
        return this.list.keyPressed(key);
    }
}
