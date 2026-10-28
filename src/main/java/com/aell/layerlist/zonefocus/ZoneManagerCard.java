package com.aell.layerlist.zonefocus;

import com.aell.layerlist.compat.ZoneFocusFiles;
import com.aell.layerlist.shaded.ui.Model;
import com.aell.layerlist.shaded.ui.Paint;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.shaded.ui.Style;
import com.aell.layerlist.shaded.ui.Vanilla;
import com.aell.layerlist.shaded.ui.Ui;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Большая карточка «Управление зонами» — главное действие вкладки Zone Focus (с 1.0.5, Zone Focus 0.0.8+).
 * Слева мини-карта зон сверху (срез активной зоны, номера, готовность цветом, активная — циан), в центре
 * заголовок, «схема · зон · сетка», чипы «Активная N», «Готово N%», «Зон готово N из M» и полоса из
 * отрезков-зон по готовности; справа большая кнопка «Открыть ›» (зон нет — «Разбить на зоны ›») и под ней
 * яркая фиолетовая «Настройки Zone Focus» («про другой мод», как его значок). Щелчок по карточке мимо
 * кнопки настроек открывает «Управление зонами».
 *
 * <p>Живая: раз в полсекунды перечитывает Zone Focus через {@link ZoneFocusFiles} — сменилась активная зона,
 * построили блоки, разбили или отменили разбиение — карточка следует. Цвета — канон: синий — главное
 * действие, циан — активная зона (как в мире), зелёный / жёлтый / красный — готово / начато / не начато.
 */
@Environment(EnvType.CLIENT)
public final class ZoneManagerCard implements Model.Card
{
    private static final int ACTIVE = 0xFF50D0FF;
    private static final long POLL_MS = 500L;
    private static final int HEIGHT = 96;
    private static final int BUTTON_H = 24;
    private static final int SETTINGS_H = 18;
    private static final int BUTTONS_GAP = 6;

    private final Runnable open;
    private final Runnable settings;
    /** Кнопка настроек в последнем кадре — {x, y, w, h}; для щелчка и самопроверки. */
    private final int[] settingsBox = new int[4];

    private long polledAt;
    private @Nullable String name;
    private int @Nullable [] summary;
    private int @Nullable [][] boxes;
    private int @Nullable [] progress;

    public ZoneManagerCard(Runnable open, Runnable settings)
    {
        this.open = open;
        this.settings = settings;
    }

    /** Где кнопка «Настройки Zone Focus» в последнем кадре — {x, y, w, h}. */
    public int[] settingsBounds()
    {
        return this.settingsBox.clone();
    }

    private static String t(String key, Object... args)
    {
        return args.length == 0 ? Strings.plain("layerlist.zf.card." + key)
                : StringUtils.translate("layerlist.zf.card." + key, args);
    }

    // ===== Данные =====

    private void poll()
    {
        long now = Util.getMillis();

        if (now - this.polledAt < POLL_MS)
        {
            return;
        }

        this.polledAt = now;
        this.name = ZoneFocusFiles.placementName();
        this.summary = ZoneFocusFiles.summary();
        this.boxes = this.summary != null ? ZoneFocusFiles.worldBoxes() : null;
        this.progress = this.summary != null ? ZoneFocusFiles.zoneProgress() : null;
    }

    private boolean hasZones()
    {
        return this.summary != null && this.boxes != null && this.boxes.length > 0;
    }

    private int count()
    {
        return this.summary != null ? this.summary[0] : 0;
    }

    private int active()
    {
        return this.summary != null ? this.summary[1] : -1;
    }

    /** Средняя готовность по посчитанным зонам, −1 — ни одна не посчитана. */
    private int overall()
    {
        if (this.progress == null)
        {
            return -1;
        }

        int sum = 0;
        int known = 0;

        for (int p : this.progress)
        {
            if (p >= 0)
            {
                sum += p;
                ++known;
            }
        }

        return known == 0 ? -1 : sum / known;
    }

    private int done()
    {
        int n = 0;

        if (this.progress != null)
        {
            for (int p : this.progress)
            {
                n += p >= 100 ? 1 : 0;
            }
        }

        return n;
    }

    private static int tone(int percent)
    {
        return percent < 0 ? Ui.C_TEXT_OFF : percent >= 100 ? Ui.C_ON : percent > 0 ? Ui.C_GOLD : Ui.C_DANGER;
    }

    private String subtitle()
    {
        if (this.name == null)
        {
            return t("no_schematic");
        }

        if (! this.hasZones())
        {
            return t("not_split", this.name);
        }

        int[] s = this.summary;
        String grid = s[2] > 0 ? " · " + t("grid", s[2], s[3], s[4]) : "";
        return this.name + " · " + t("zones", this.count()) + grid;
    }

    private String buttonLabel()
    {
        return (this.name != null && ! this.hasZones() ? t("split") : t("open")) + "  ›";
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
        int[] b = this.settingsBox;

        if (b[2] > 0 && Paint.hit((int) mouseX, (int) mouseY, b[0], b[1], b[2], b[3]))
        {
            Vanilla.click();
            this.settings.run();
            return true;
        }

        if (this.name != null)
        {
            Vanilla.click();
            this.open.run();
            this.polledAt = 0L;
        }

        return true;
    }

    @Override
    public void draw(GuiContext ctx, Font font, int x, int y, int w, int h, int mouseX, int mouseY, boolean focused)
    {
        this.poll();

        boolean live = this.name != null;
        boolean over = live && Paint.hit(mouseX, mouseY, x, y, w, h);
        if (Style.vanilla())
        {
            // Карточка-плитка: ванильная панель, внутри свои ванильные кнопки
            Vanilla.panel(ctx, x, y, w, h);
        }
        else
        {
            Paint.card(ctx, x, y, w, h, Ui.R_PLATE, Ui.alpha(Ui.C_ACCENT, over || focused ? 0.16F : 0.10F),
                    Ui.alpha(Ui.C_ACCENT, over || focused ? 0.9F : 0.55F));
        }

        int pad = Ui.PAD_GROUP;
        int mapW = Math.min(132, w / 4);
        this.miniMap(ctx, font, x + pad, y + pad, mapW, h - pad * 2);

        String label = this.buttonLabel();
        String settingsLabel = t("settings");
        int btnW = Math.max(Math.max(font.width(label), font.width(settingsLabel)) + Ui.PAD_GROUP * 2, 92);
        int btnX = x + w - pad - btnW;
        int btnY = y + (h - BUTTON_H - BUTTONS_GAP - SETTINGS_H) / 2;
        int tx = x + pad + mapW + pad + Ui.PAD_REL;
        int textRight = btnX - pad;

        Paint.text(ctx, font, t("title"), tx, y + pad + 1, Ui.C_TEXT, false);
        Paint.text(ctx, font, Paint.fit(font, this.subtitle(), textRight - tx), tx, y + pad + 15, Ui.C_TEXT_2, false);

        int cy = y + pad + 32;

        if (this.hasZones())
        {
            int cx = chip(ctx, font, t("active", this.active() + 1), tx, cy, ACTIVE);
            int all = this.overall();
            cx = chip(ctx, font, all < 0 ? t("scanning") : t("ready", all), cx + Ui.PAD_IN, cy, tone(all));

            if (this.progress != null && cx + Ui.PAD_IN + font.width(t("done_of", this.done(), this.count())) + Ui.PAD_REL * 2 <= textRight)
            {
                chip(ctx, font, t("done_of", this.done(), this.count()), cx + Ui.PAD_IN, cy, Ui.C_TEXT_2);
            }

            this.segments(ctx, tx, y + h - pad - 8, textRight - tx, 8);
        }
        else if (live)
        {
            Paint.text(ctx, font, Paint.fit(font, t("hint_split"), textRight - tx), tx, cy + 3, Ui.C_TEXT_3, false);
        }

        // Большая кнопка — главное действие, акцент; без схемы приглушена
        boolean btnOver = live && Paint.hit(mouseX, mouseY, btnX, btnY, btnW, BUTTON_H);
        int c = live ? Ui.C_ACCENT : Ui.C_TEXT_OFF;
        if (Style.vanilla())
        {
            // Ванильная кнопка Minecraft; без схемы — выключенная
            Vanilla.button(ctx, btnX, btnY, btnW, BUTTON_H, live, btnOver);
            Vanilla.buttonLabel(ctx, font, label, btnX, btnY, btnW, BUTTON_H, live);
        }
        else
        {
            Paint.card(ctx, btnX, btnY, btnW, BUTTON_H, Ui.R_CTRL, Ui.alpha(c, btnOver ? 0.42F : 0.26F),
                    Ui.alpha(c, btnOver ? 1.0F : 0.8F));
            Paint.midText(ctx, font, label, btnX + (btnW - font.width(label)) / 2, btnY, BUTTON_H,
                    live ? Ui.C_TEXT : Ui.C_TEXT_OFF, false);
        }

        // «Настройки Zone Focus» — ярко: залитая фиолетовым («про другой мод», как его значок), белый текст
        int sy = btnY + BUTTON_H + BUTTONS_GAP;
        boolean sOver = Paint.hit(mouseX, mouseY, btnX, sy, btnW, SETTINGS_H);
        if (Style.vanilla())
        {
            Vanilla.button(ctx, btnX, sy, btnW, SETTINGS_H, true, sOver);
            Vanilla.buttonLabel(ctx, font, settingsLabel, btnX, sy, btnW, SETTINGS_H, true);
        }
        else
        {
            Paint.card(ctx, btnX, sy, btnW, SETTINGS_H, Ui.R_CTRL, Ui.alpha(Ui.C_FOREIGN, sOver ? 0.85F : 0.62F),
                    Ui.alpha(0xFFC9B6FF, sOver ? 1.0F : 0.85F));
            Paint.midText(ctx, font, settingsLabel, btnX + (btnW - font.width(settingsLabel)) / 2, sy, SETTINGS_H,
                    0xFFFFFFFF, false);
        }
        this.settingsBox[0] = btnX;
        this.settingsBox[1] = sy;
        this.settingsBox[2] = btnW;
        this.settingsBox[3] = SETTINGS_H;
    }

    // ===== Куски =====

    private static int chip(GuiContext ctx, Font font, String text, int x, int y, int color)
    {
        int w = font.width(text) + Ui.PAD_REL * 2;
        Paint.card(ctx, x, y, w, 14, Ui.R_CTRL, Ui.alpha(color, 0.14F), Ui.alpha(color, 0.5F));
        Paint.midText(ctx, font, text, x + Ui.PAD_REL, y, 14, color, false);
        return x + w;
    }

    /** Полоса из отрезков-зон: цвет — готовность (у начатой доля готового зелёная), активная — циановая рамка. */
    private void segments(GuiContext ctx, int x, int y, int w, int h)
    {
        int n = this.count();

        if (n <= 0 || w <= 0)
        {
            return;
        }

        int gap = n <= 40 ? 2 : 0;

        for (int i = 0; i < n; ++i)
        {
            int sx = x + (w + gap) * i / n;
            int ex = Math.max(sx + 1, x + (w + gap) * (i + 1) / n - gap);
            int p = this.progress != null && i < this.progress.length ? this.progress[i] : -1;
            Paint.fill(ctx, sx, y, ex, y + h, Ui.alpha(tone(p), p < 0 ? 0.35F : 0.7F));

            if (p > 0 && p < 100)
            {
                Paint.fill(ctx, sx, y, Math.max(sx + 1, sx + (ex - sx) * p / 100), y + h, Ui.alpha(Ui.C_ON, 0.85F));
            }

            if (i == this.active())
            {
                Paint.border(ctx, sx - 1, y - 1, ex - sx + 2, h + 2, ACTIVE);
            }
        }
    }

    /**
     * Мини-карта сверху: клетки зон того же среза по высоте, что у активной; цвет — готовность, активная —
     * циан с номером. Зон нет — «не разбита» (схемы нет — «нет схемы»).
     */
    private void miniMap(GuiContext ctx, Font font, int x, int y, int w, int h)
    {
        Paint.card(ctx, x, y, w, h, Ui.R_PLATE, 0xC0101014, Ui.C_DIVIDER);

        if (! this.hasZones())
        {
            String text = this.name == null ? t("map_none") : t("map_unsplit");
            String shown = Paint.fit(font, text, w - 8);
            Paint.midText(ctx, font, shown, x + (w - font.width(shown)) / 2, y, h, Ui.C_TEXT_3, false);
            return;
        }

        int[][] b = this.boxes;
        int act = Math.max(0, Math.min(b.length - 1, this.active()));
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int[] z : b)
        {
            minX = Math.min(minX, z[0]);
            minZ = Math.min(minZ, z[2]);
            maxX = Math.max(maxX, z[3]);
            maxZ = Math.max(maxZ, z[5]);
        }

        int inset = 6;
        float s = Math.min((w - inset * 2) / (float) (maxX - minX + 1), (h - inset * 2) / (float) (maxZ - minZ + 1));
        int ox = x + inset + Math.round(((w - inset * 2) - (maxX - minX + 1) * s) / 2.0F);
        int oz = y + inset + Math.round(((h - inset * 2) - (maxZ - minZ + 1) * s) / 2.0F);

        for (int i = 0; i < b.length; ++i)
        {
            int[] z = b[i];

            if (z[1] != b[act][1])
            {
                continue;
            }

            int cx = ox + Math.round((z[0] - minX) * s);
            int cz = oz + Math.round((z[2] - minZ) * s);
            int cw = Math.max(1, ox + Math.round((z[3] - minX + 1) * s) - cx);
            int ch = Math.max(1, oz + Math.round((z[5] - minZ + 1) * s) - cz);
            int p = this.progress != null && i < this.progress.length ? this.progress[i] : -1;
            Paint.fill(ctx, cx, cz, cx + cw, cz + ch, Ui.alpha(tone(p), p < 0 ? 0.18F : 0.35F));
            Paint.border(ctx, cx, cz, cw, ch, i == act ? ACTIVE : Ui.alpha(Ui.C_TEXT_2, 0.5F));
            String num = String.valueOf(i + 1);

            if (cw >= font.width(num) + 3 && ch >= 9)
            {
                Paint.text(ctx, font, num, cx + (cw - font.width(num)) / 2 + 1, cz + (ch - 8) / 2 + 1,
                        i == act ? ACTIVE : Ui.C_TEXT_2, false);
            }
        }
    }
}
