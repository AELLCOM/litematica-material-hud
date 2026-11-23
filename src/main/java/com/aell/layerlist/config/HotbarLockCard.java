package com.aell.layerlist.config;

import com.aell.layerlist.core.HotbarLocks;
import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.shaded.ui.Vanilla;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * Карточка «Запертые ячейки хотбара» (просьба 19.11.2026): хотбар как в игре — тот же спрайт и предметы игрока, —
 * в каждой ячейке замок. Открытый — серый; щелчок запирает ячейку: замок закрывается, становится золотым и медленно
 * пульсирует вместе с рамкой ячейки. Что значит «заперта» и где это хранится — {@link HotbarLocks}.
 */
@Environment(EnvType.CLIENT)
public final class HotbarLockCard implements Model.Card
{
    private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
    /** Спрайт хотбара игры и его разметка: девять ячеек 16×16 с шагом 20. */
    private static final int BAR_W = 182;
    private static final int BAR_H = 22;
    private static final int CELL = 16;
    private static final int PITCH = 20;
    private static final int INSET = 3;

    private static final int PAD = Ui.PAD_GROUP;
    private static final int TITLE_GAP = Ui.PAD_REL;
    private static final int HEIGHT = PAD + Ui.GLYPH_H + TITLE_GAP + BAR_H + PAD;
    /** Полный круг пульса запертой ячейки — «медленно». */
    private static final long PULSE_MS = 2400L;

    private static final int OUTLINE = 0xE6141418;
    private static final int KEYHOLE = 0xFF1B1B20;
    private static final int OPEN_MAIN = 0xFFA9A9B6;
    private static final int OPEN_LIGHT = 0xFFD0D0DA;
    private static final int OPEN_SHADE = 0xFF7C7C88;
    private static final int OPEN_HOVER = 0xFFE8E8EC;
    private static final int GOLD_LOW = 0xFFC8962E;
    private static final int GOLD_HIGH = 0xFFFFD75E;
    private static final int GOLD_LIGHT_LOW = 0xFFE9BE55;
    private static final int GOLD_LIGHT_HIGH = 0xFFFFF0A8;
    private static final int GOLD_SHADE_LOW = 0xFF8F6A1C;
    private static final int GOLD_SHADE_HIGH = 0xFFD9A93A;
    private static final int GOLD_GLOW = 0xFFE0B341;
    /** Вуаль над предметом в ячейке: замок читается поверх любой иконки. */
    private static final int VEIL = 0x99101014;

    /** Замок 9×11: {@code #} — тело и дужка, {@code h} — блик, {@code d} — тень, {@code .} — скважина. */
    private static final char[][] CLOSED = outlined(
            "         ",
            "         ",
            "  #####  ",
            " ##   ## ",
            " #     # ",
            " #     # ",
            "hhhhhhhhh",
            "###...###",
            "###...###",
            "####.####",
            "ddddddddd");
    /** Открытый: дужка поднята на две точки, правый конец вышел из тела — просвет виден сразу. */
    private static final char[][] OPEN = outlined(
            "  #####  ",
            " ##   ## ",
            " #     # ",
            " #     # ",
            " #       ",
            " #       ",
            "hhhhhhhhh",
            "###...###",
            "###...###",
            "####.####",
            "ddddddddd");

    /** Хотбар в последнем кадре — левый верхний угол спрайта; для щелчка и самопроверки. */
    private int barX = -1;
    private int barY = -1;
    private int hovered = -1;
    private long hoveredAt;

    /** Где ячейка {@code slot} (0–8) в последнем кадре — {x, y, w, h} зоны щелчка; до первого кадра — нули. */
    public int[] slotBounds(int slot)
    {
        return this.barX < 0 ? new int[4] : new int[]{this.barX + 1 + slot * PITCH, this.barY, PITCH, BAR_H};
    }

    private int slotAt(double mouseX, double mouseY)
    {
        if (this.barX < 0 || mouseY < this.barY || mouseY >= this.barY + BAR_H)
        {
            return -1;
        }

        int slot = (int) Math.floor((mouseX - this.barX - 1) / PITCH);
        return mouseX >= this.barX + 1 && slot >= 0 && slot < HotbarLocks.SLOTS ? slot : -1;
    }

    // ===== Model.Card =====

    @Override
    public int height(int width)
    {
        return HEIGHT;
    }

    @Override
    public boolean click(double mouseX, double mouseY, int x, int y, int w, int h)
    {
        int slot = this.slotAt(mouseX, mouseY);

        if (slot >= 0)
        {
            Vanilla.click();
            HotbarLocks.toggle(slot);
        }

        return true;
    }

    @Override
    public void draw(GuiContext ctx, Font font, int x, int y, int w, int h, int mouseX, int mouseY, boolean focused)
    {
        if (Style.vanilla())
        {
            Vanilla.panel(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, Ui.R_PLATE, Ui.alpha(Ui.C_PANEL, 0.45F), focused ? Ui.C_PANEL_EDGE : Ui.C_DIVIDER);
        }

        Paint.text(ctx, font, Paint.fit(font, Strings.plain("layerlist.config.hotbarLocks.name"), w - PAD * 2),
                x + PAD, y + PAD, Ui.C_TEXT, false);

        this.barX = x + PAD;
        this.barY = y + PAD + Ui.GLYPH_H + TITLE_GAP;
        int over = this.slotAt(mouseX, mouseY);

        if (over != this.hovered)
        {
            this.hovered = over;
            this.hoveredAt = Util.getMillis();
        }

        ctx.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, this.barX, this.barY, BAR_W, BAR_H);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null)
        {
            for (int slot = 0; slot < HotbarLocks.SLOTS; ++slot)
            {
                ItemStack stack = player.getInventory().getItem(slot);

                if (! stack.isEmpty())
                {
                    ctx.renderItem(stack, this.barX + INSET + slot * PITCH, this.barY + INSET);
                }
            }
        }

        // Предметы игра кладёт поверх заливок своего слоя — вуаль и замки идут следующим
        ctx.nextStratum();
        float pulse = 0.5F - 0.5F * (float) Math.cos(Math.PI * 2.0 * (Util.getMillis() % PULSE_MS) / PULSE_MS);

        for (int slot = 0; slot < HotbarLocks.SLOTS; ++slot)
        {
            int sx = this.barX + INSET + slot * PITCH;
            int sy = this.barY + INSET;
            boolean locked = HotbarLocks.locked(slot);
            boolean hover = slot == over;

            // Вуаль — во всех ячейках: пустая без неё светлее занятой (у спрайта хотбара полупрозрачное дно)
            ctx.fill(sx, sy, sx + CELL, sy + CELL, VEIL);

            if (locked)
            {
                ctx.fill(sx, sy, sx + CELL, sy + CELL, Ui.alpha(GOLD_GLOW, 0.16F + 0.20F * pulse));
                frame(ctx, sx - 1, sy - 1, CELL + 2, Ui.alpha(Paint.mix(GOLD_LOW, GOLD_HIGH, pulse), 0.55F + 0.45F * pulse));
            }

            if (hover)
            {
                frame(ctx, sx - 1, sy - 1, CELL + 2, locked ? GOLD_LIGHT_HIGH : 0xD0FFFFFF);
            }

            int main = locked ? Paint.mix(GOLD_LOW, GOLD_HIGH, pulse) : hover ? OPEN_HOVER : OPEN_MAIN;
            int light = locked ? Paint.mix(GOLD_LIGHT_LOW, GOLD_LIGHT_HIGH, pulse) : hover ? 0xFFFFFFFF : OPEN_LIGHT;
            int shade = locked ? Paint.mix(GOLD_SHADE_LOW, GOLD_SHADE_HIGH, pulse) : OPEN_SHADE;
            // Знак с обводкой 11×13 — по центру ячейки 16×16
            glyph(ctx, locked ? CLOSED : OPEN, sx + 2, sy + 1, main, light, shade);
        }

        // Статус — правее хотбара: необязательная строка, ширину карточки не задаёт (не влезла — бежит под курсором)
        int statusX = this.barX + BAR_W + PAD;
        int room = x + w - PAD - statusX;

        if (room > 24)
        {
            boolean inCard = Paint.hit(mouseX, mouseY, x, y, w, h);
            Paint.midMarquee(ctx, font, status(), statusX, this.barY, BAR_H, room, statusColor(), false,
                    inCard ? this.hoveredAt : -1L);
        }
    }

    // ===== Куски =====

    private static String status()
    {
        int locked = HotbarLocks.lockedCount();

        if (locked == 0)
        {
            return Strings.plain("layerlist.hotbar_locks.none");
        }

        // Заперты все ячейки, куда Litematica берёт блоки (её pickBlockableSlots, по умолчанию 1–5)
        if (! HotbarLocks.pickTargetLeft())
        {
            return Strings.plain("layerlist.hotbar_locks.all");
        }

        StringBuilder list = new StringBuilder();

        for (int slot = 0; slot < HotbarLocks.SLOTS; ++slot)
        {
            if (HotbarLocks.locked(slot))
            {
                list.append(list.isEmpty() ? "" : ", ").append(slot + 1);
            }
        }

        return StringUtils.translate("layerlist.hotbar_locks.some", list.toString());
    }

    private static int statusColor()
    {
        return HotbarLocks.lockedCount() == 0 ? Ui.C_TEXT_3 : ! HotbarLocks.pickTargetLeft() ? Ui.C_WARN : Ui.C_GOLD;
    }

    /** Рамка 1 px квадрата со стороной {@code size}. */
    private static void frame(GuiContext ctx, int x, int y, int size, int color)
    {
        ctx.fill(x, y, x + size, y + 1, color);
        ctx.fill(x, y + size - 1, x + size, y + size, color);
        ctx.fill(x, y + 1, x + 1, y + size - 1, color);
        ctx.fill(x + size - 1, y + 1, x + size, y + size - 1, color);
    }

    /** Знак по сетке: одноцветные отрезки строки — одной заливкой. Цвета — мимо палитры стиля: золото везде золото. */
    private static void glyph(GuiContext ctx, char[][] grid, int x, int y, int main, int light, int shade)
    {
        for (int row = 0; row < grid.length; ++row)
        {
            char[] line = grid[row];

            for (int column = 0; column < line.length; )
            {
                char c = line[column];
                int end = column + 1;

                while (end < line.length && line[end] == c)
                {
                    ++end;
                }

                int color = switch (c)
                {
                    case '#' -> main;
                    case 'h' -> light;
                    case 'd' -> shade;
                    case '.' -> KEYHOLE;
                    case 'o' -> OUTLINE;
                    default -> 0;
                };

                if (color != 0)
                {
                    ctx.fill(x + column, y + row, x + end, y + row + 1, color);
                }

                column = end;
            }
        }
    }

    /** Сетка знака с тёмной обводкой в 1 px ({@code o}) вокруг всех закрашенных точек — на точку шире с каждой стороны. */
    private static char[][] outlined(String... mask)
    {
        int h = mask.length;
        int w = mask[0].length();
        char[][] grid = new char[h + 2][w + 2];

        for (int row = 0; row < h + 2; ++row)
        {
            for (int column = 0; column < w + 2; ++column)
            {
                char own = at(mask, row - 1, column - 1);

                if (own != ' ')
                {
                    grid[row][column] = own;
                    continue;
                }

                boolean edge = false;

                for (int dy = -1; dy <= 1 && ! edge; ++dy)
                {
                    for (int dx = -1; dx <= 1 && ! edge; ++dx)
                    {
                        edge = at(mask, row - 1 + dy, column - 1 + dx) != ' ';
                    }
                }

                grid[row][column] = edge ? 'o' : ' ';
            }
        }

        return grid;
    }

    private static char at(String[] mask, int row, int column)
    {
        return row < 0 || row >= mask.length || column < 0 || column >= mask[row].length() ? ' ' : mask[row].charAt(column);
    }
}
