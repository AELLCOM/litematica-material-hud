package com.aell.layerlist.dev;

import com.aell.layerlist.config.GuiConfigs;
import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportFormat;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.export.OnlinePlayers;
import com.aell.layerlist.schematics.gui.export.PlayerPicker;
import com.aell.layerlist.schematics.materials.ItemKeys;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.skin.MainMenuSkin;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.GuiUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», правки 0.0.27 по отзыву игрока: окно выгрузки и главное меню Litematica.
 * <ul>
 *   <li>«Кто собирает»: пояснение под заголовком, «+ игроки онлайн» открывает выбор
 *       ({@link PlayerPicker}) — свой ник первым, дальше по алфавиту; клик по чипу добавляет, фильтр
 *       сужает, «Добавить всех» берёт видимых, «Готово» возвращает к ростеру. Список игроков в одиночной
 *       игре — один игрок, поэтому к нему подмешиваются выдуманные ники ({@link OnlinePlayers.TestHooks});</li>
 *   <li>Discord: кнопка формата цвета Discord, текст на 150 позиций режется на части, «Скопировать
 *       следующую часть» живая, пока части остались, и выключена, когда скопировано всё;</li>
 *   <li>кнопка «Material HUD» в главном меню Litematica открывает настройки мода, «Назад» (Esc) —
 *       снова меню; кнопка другого мода, добавленная после нашей в ту же клетку (так делает Litematica
 *       Printer), выталкивает нашу в свободную клетку, и клик по ней достаётся тому моду.</li>
 * </ul>
 * Всё нажимается обычным вводом экрана; снимки — с префиксом масштаба ({@code s3-} при GUI 3).
 */
@Environment(EnvType.CLIENT)
final class KDialog
{
    /** Выдуманные ники: разный регистр, префиксы Bedrock, длинный — сортировка и обрезка видны на снимке. */
    private static final List<String> FAKE = List.of(
            "Zeta_Miner", "alex_builder", "Bob_Stone", "builder_kate", "Creeper_Hugger", "dark_Miner", "Drimofey", "Eve",
            "fox_tail", "GoldDigger_99", "iron_golem_42", "Jade", "kraken_lord", "lava_surfer", "Max_Redstone",
            "miner_mia", "Nether_Star", "Oak_Leaf", "pixel_panda", "QuartzQueen", "red_beard", "Sky_Walker_2008",
            "slime_ball", "Tnt_Tom", "ultra_crafter", "Vi", "VeryLongNickName", "wolf_pack", "Xx_Shadow_xX",
            "yellow_bee", ".BedrockSteve", "*Console", "anna_k", "Bard", "cobble_king", "Dune", "ember", "Frost",
            "Granite_Gus", "hopper_hal", "Ivy", "jungle_jo", "Kelp", "Lantern_Lu", "moss_mo", "Nova", "obsidian_ox",
            "Prism", "quarry_q", "Rune", "sandy", "Torch", "umber", "Vex_Hunter", "warden_w", "Yew");
    private static final String FILTER = "an";
    private static final String CHIP = "alex_builder";
    private static final int DISCORD_ROWS = 150;

    private final DumpSchematics k;
    private @Nullable List<String> rosterBefore;
    private @Nullable ExportWindow discordWindow;
    private @Nullable Screen menu;
    private boolean menuButtonBefore = true;
    private @Nullable ButtonBase foreign;
    private boolean foreignClicked;

    KDialog(DumpSchematics k)
    {
        this.k = k;
    }

    // ===== Сценарий =====

    /** Окно выгрузки: «Кто собирает» и выбор игроков, потом Discord частями. {@code p} — префикс снимков. */
    List<DumpSchematics.Step> dialogSteps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "ростер и выдуманные игроки онлайн", 3_000L, mc -> this.prepareRoster()));
        s.add(step(p + "окно выгрузки (ростер)", 5_000L, mc -> this.k.openExportDialog(mc)));
        s.add(step(p + "снимок «Кто собирает» с пояснением", 5_000L, this.k.settleShot(p + "export-roster")));
        s.add(step(p + "«+ игроки онлайн» открывает выбор", 3_000L, mc -> this.openPicker()));
        s.add(step(p + "выбор: порядок и отметки", 3_000L, mc -> this.pickerOrder()));
        s.add(step(p + "снимок выбора игроков", 5_000L, this.k.settleShot(p + "picker")));
        s.add(step(p + "выбор: клик по чипу добавляет", 3_000L, mc -> this.clickChip()));
        s.add(step(p + "выбор: фильтр «" + FILTER + "»", 3_000L, mc -> this.filter()));
        s.add(step(p + "снимок выбора с фильтром", 5_000L, this.k.settleShot(p + "picker-filter")));
        s.add(step(p + "выбор: «Добавить всех»", 3_000L, mc -> this.addAll()));
        s.add(step(p + "выбор: «Готово» — назад к ростеру", 3_000L, mc -> this.done()));
        s.add(step(p + "снимок ростера после выбора", 5_000L, this.k.settleShot(p + "roster-after-picker")));
        // 0.0.27: «Очистить» — «Вернуть» под полем, не тостом (пока окно открыто, тостов нет)
        s.add(step(p + "«Очистить»: «Вернуть» под полем, без тоста", 3_000L,
                mc -> this.k.inline.mouseAside() && this.k.inline.clearRoster()));
        s.add(step(p + "снимок: ростер очищен, «Вернуть»", 5_000L, this.k.settleShot(p + "roster-cleared")));
        s.add(step(p + "«Вернуть» мышью", 3_000L, mc -> this.k.inline.clickUndo()));
        s.add(step(p + "«Вернуть» вернул ростер", 3_000L, this.k.settleThen(300L, mc -> this.k.inline.undone())));
        s.add(step(p + "Esc закрывает окно выгрузки (ростер)", 3_000L, mc -> this.k.closeExportDialog(mc)));
        s.add(step(p + "ростер как был", 3_000L, mc -> this.restoreRoster()));
        s.add(step(p + "окно выгрузки на " + DISCORD_ROWS + " позиций", 5_000L, mc -> this.openDiscordWindow()));
        s.add(step(p + "Discord: кнопка формата видна (прокрутка окна)", 5_000L, mc -> this.discordVisible()));
        s.add(step(p + "Discord: первая часть", 5_000L, mc -> this.discordFirst()));
        s.add(step(p + "Discord: части остались — кнопка золотая и живая", 3_000L,
                this.k.settleThen(300L, mc -> this.discordPartsLeft())));
        s.add(step(p + "снимок: Discord, части остались", 5_000L, this.k.settleShot(p + "discord-parts-left")));
        s.add(step(p + "Discord: все части по кнопке", 5_000L, mc -> this.discordRest()));
        s.add(step(p + "снимок: Discord, всё скопировано", 5_000L, this.k.settleShot(p + "discord-all-copied")));
        s.add(step(p + "Esc закрывает окно Discord", 3_000L, mc -> this.k.closeExportDialog(mc)));
        return s;
    }

    /** Главное меню Litematica: кнопка «Material HUD» → настройки → Esc → меню. Экран «Схемы» потом открыт снова. */
    List<DumpSchematics.Step> menuSteps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "главное меню Litematica", 5_000L, mc -> this.openMenu()));
        s.add(step(p + "кнопка «Material HUD» в меню", 3_000L, mc -> this.menuButton() != null || this.noMenuButton()));
        s.add(step(p + "снимок: меню Litematica", 5_000L, this.k.settleShot(p + "litematica-menu")));
        s.add(step(p + "кнопка открывает настройки Material HUD", 5_000L, mc -> this.clickMenuButton()));
        s.add(step(p + "настройки открыты, «Назад» — в меню", 5_000L, mc -> this.settingsOpen()));
        s.add(step(p + "снимок: настройки из меню Litematica", 5_000L, this.k.settleShot(p + "settings-from-menu")));
        s.add(step(p + "Esc — назад в меню Litematica", 5_000L, mc -> this.backToMenu()));
        s.add(step(p + "чужая кнопка ложится на нашу (как Printer)", 3_000L, mc -> this.addForeign()));
        s.add(step(p + "наша кнопка ушла с чужой", 3_000L, this.k.settleThen(300L, mc -> this.foreignApart())));
        s.add(step(p + "снимок: меню с чужой кнопкой", 5_000L, this.k.settleShot(p + "litematica-menu-foreign")));
        s.add(step(p + "клик по чужой кнопке достаётся ей", 5_000L, mc -> this.clickForeign()));
        s.add(step(p + "опция выкл. — кнопки в меню нет", 5_000L, mc -> this.menuButtonOff()));
        s.add(step(p + "снова экран «Схемы»", 10_000L, mc -> this.reopenSchematics()));
        return s;
    }

    /**
     * Меню Litematica в цветах Material HUD: без перекраски и с перекраской (кнопки модов на своих местах, наша плавно
     * уступает занятое место). Анимация замедлена в 6 раз — снимок посреди переезда.
     */
    List<DumpSchematics.Step> skinSteps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(p + "меню Litematica без перекраски", 5_000L, mc -> this.openSkinned(false)));
        s.add(step(p + "снимок: без перекраски", 5_000L, this.k.settleShot(p + "skin-0-off")));

        s.add(step(p + "перекраска: открыть меню", 5_000L, mc -> this.openSkinned(true)));
        s.add(step(p + "перекраска: снимок посреди переезда", 5_000L, this.k.settleThen(500L, mc ->
        {
            this.k.shot(mc, p + "skin-slide-mid");
            return true;
        })));
        s.add(step(p + "перекраска: переезд закончен", 8_000L, this.k.settleThen(2_000L, mc ->
        {
            this.k.shot(mc, p + "skin");
            return this.skinSettled();
        })));

        s.add(step(p + "перекраска выкл.", 3_000L, mc -> this.skinOff()));
        return s;
    }

    private boolean openSkinned(boolean skin)
    {
        SchematicsConfig.LITEMATICA_SKIN.setBooleanValue(skin);
        MainMenuSkin.TestHooks.slow(skin ? 6.0F : 1.0F);
        Nav.clear();
        GuiBase.openGui(new GuiMainMenu());
        return true;
    }

    private boolean skinSettled()
    {
        ButtonBase button = this.menuButton();
        Screen screen = GuiUtils.getCurrentScreen();

        if (button == null || screen == null)
        {
            this.k.fail("кнопки «Material HUD» нет в перекрашенном меню");
            return true;
        }

        this.checkApart(screen, button);
        for (ButtonBase other : buttons(screen))
        {
            this.k.info("кнопка «" + label(other) + "»: " + other.getX() + "," + other.getY() + " " + other.getWidth()
                    + (MainMenuSkin.isAddon(other) ? " (мод)" : ""));

            // Кнопка другого мода — там, где её поставил мод (Printer: правая колонка, второй ряд)
            if (MainMenuSkin.isAddon(other) && other != button)
            {
                this.k.check("на своём месте: «" + label(other) + "» y", 52, other.getY());
            }
        }

        return true;
    }

    private boolean skinOff()
    {
        SchematicsConfig.LITEMATICA_SKIN.setBooleanValue(false);
        MainMenuSkin.TestHooks.slow(1.0F);
        return true;
    }

    void cleanup()
    {
        OnlinePlayers.TestHooks.fakeOnline(null);
        SchematicsConfig.MAIN_MENU_BUTTON.setBooleanValue(this.menuButtonBefore);
        this.skinOff();

        if (this.rosterBefore != null)
        {
            SchematicsState.get().setRoster(this.rosterBefore);
            this.rosterBefore = null;
        }
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    // ===== «Кто собирает» и выбор игроков =====

    private static String self()
    {
        return Minecraft.getInstance().getUser().getName();
    }

    private @Nullable Layers layers()
    {
        return KProbe.fieldOfType(this.k.gui(), Layers.class);
    }

    private @Nullable PlayerPicker picker()
    {
        Layers layers = this.layers();
        return layers == null ? null : PlayerPicker.TestHooks.current(layers);
    }

    private boolean prepareRoster()
    {
        if (this.rosterBefore == null)
        {
            this.rosterBefore = List.copyOf(SchematicsState.get().roster());
        }

        OnlinePlayers.TestHooks.fakeOnline(FAKE);
        SchematicsState.get().setRoster(List.of(self(), "builder_kate", "Drimofey"));
        this.k.check("ростер: три ника", 3, SchematicsState.get().roster().size());
        return true;
    }

    private boolean restoreRoster()
    {
        OnlinePlayers.TestHooks.fakeOnline(null);

        if (this.rosterBefore != null)
        {
            SchematicsState.get().setRoster(this.rosterBefore);
            this.rosterBefore = null;
        }

        return true;
    }

    private boolean openPicker()
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = this.layers();
        Object window = layers == null ? null : layers.top();
        Object roster = KProbe.fieldNamedType(window, "RosterField");
        Button online = KProbe.button(roster, "layerlist.schematics.export.dialog.roster.online");

        if (gui == null || ! (window instanceof ExportWindow) || online == null)
        {
            this.k.fail("«+ игроки онлайн»: нет окна выгрузки или кнопки");
            return true;
        }

        int before = SchematicsState.get().roster().size();
        this.k.clickButton(gui, online, null);
        this.k.check("«+ игроки онлайн» открыл выбор (а не вписал всех)", true, this.picker() != null);
        this.k.check("ростер не изменился от открытия выбора", before, SchematicsState.get().roster().size());
        return true;
    }

    private boolean pickerOrder()
    {
        PlayerPicker picker = this.picker();

        if (picker == null)
        {
            this.k.fail("выбор игроков не открыт");
            return true;
        }

        List<String> shown = PlayerPicker.TestHooks.shown(picker);
        this.k.check("выбор: игроков (свой + выдуманные)", FAKE.size() + 1, shown.size());
        this.k.check("выбор: свой ник первым", self(), shown.isEmpty() ? "" : shown.getFirst());

        List<String> rest = new ArrayList<>(shown.subList(Math.min(1, shown.size()), shown.size()));
        List<String> sorted = new ArrayList<>(rest);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        this.k.check("выбор: дальше по алфавиту без учёта регистра", sorted, rest);
        // В ростере трое (свой ник, builder_kate, Drimofey) — все они есть и в списке онлайн
        this.k.check("выбор: «Добавить всех» считает только не добавленных",
                I18n.get("layerlist.schematics.export.dialog.picker.add_all", shown.size() - 3),
                PlayerPicker.TestHooks.addAll(picker).label());
        return true;
    }

    private boolean clickChip()
    {
        GuiSchematics gui = this.k.gui();
        PlayerPicker picker = this.picker();
        double[] at = picker == null ? null : PlayerPicker.TestHooks.chip(picker, CHIP);

        if (gui == null || at == null)
        {
            this.k.fail("выбор: чипа «" + CHIP + "» не видно");
            return true;
        }

        KProbe.click(gui, at[0], at[1]);
        this.k.check("выбор: клик по «" + CHIP + "» добавил его в ростер", true, SchematicsState.get().roster().contains(CHIP));
        this.k.check("выбор: окно выбора осталось открытым", true, this.picker() == picker);

        int size = SchematicsState.get().roster().size();
        KProbe.click(gui, at[0], at[1]);
        this.k.check("выбор: повторный клик по добавленному не дублирует", size, SchematicsState.get().roster().size());
        return true;
    }

    private boolean filter()
    {
        PlayerPicker picker = this.picker();

        if (picker == null)
        {
            this.k.fail("выбор: окно закрыто");
            return true;
        }

        PlayerPicker.TestHooks.filter(picker).insert(FILTER);

        List<String> expected = new ArrayList<>();

        for (String nick : FAKE)
        {
            if (nick.toLowerCase(Locale.ROOT).contains(FILTER))
            {
                expected.add(nick);
            }
        }

        if (self().toLowerCase(Locale.ROOT).contains(FILTER))
        {
            expected.add(self());
        }

        List<String> shown = PlayerPicker.TestHooks.shown(picker);
        this.k.check("фильтр «" + FILTER + "»: найдено", expected.size(), shown.size());
        this.k.check("фильтр «" + FILTER + "»: все найденные содержат его", true,
                shown.stream().allMatch(n -> n.toLowerCase(Locale.ROOT).contains(FILTER)));
        return true;
    }

    private boolean addAll()
    {
        GuiSchematics gui = this.k.gui();
        PlayerPicker picker = this.picker();

        if (gui == null || picker == null)
        {
            this.k.fail("«Добавить всех»: окно закрыто");
            return true;
        }

        List<String> shown = PlayerPicker.TestHooks.shown(picker);
        // roster() — живой вид списка: копия, иначе «до» вырастет вместе с ростером
        List<String> before = List.copyOf(SchematicsState.get().roster());
        long addable = shown.stream().filter(n -> before.stream().noneMatch(n::equalsIgnoreCase)).count();
        this.k.clickButton(gui, PlayerPicker.TestHooks.addAll(picker), null);

        List<String> roster = SchematicsState.get().roster();
        this.k.check("«Добавить всех»: все найденные в ростере (" + shown.size() + ")", true, roster.containsAll(shown));
        this.k.check("«Добавить всех»: добавлены только найденные и новые", (long) before.size() + addable, (long) roster.size());
        return true;
    }

    private boolean done()
    {
        GuiSchematics gui = this.k.gui();
        PlayerPicker picker = this.picker();
        Layers layers = this.layers();

        if (gui == null || picker == null || layers == null)
        {
            this.k.fail("«Готово»: окно выбора закрыто");
            return true;
        }

        this.k.clickButton(gui, PlayerPicker.TestHooks.done(picker), null);
        this.k.check("«Готово»: выбор закрыт, наверху окно выгрузки", true,
                this.picker() == null && layers.top() instanceof ExportWindow);
        return true;
    }

    // ===== Discord =====

    /** Окно выгрузки на готовых строках: 150 разных блоков, у каждого своё количество. */
    private boolean openDiscordWindow()
    {
        GuiSchematics gui = this.k.gui();
        Layers layers = this.layers();
        Toasts toasts = KProbe.fieldOfType(gui, Toasts.class);

        if (gui == null || layers == null || toasts == null)
        {
            this.k.fail("Discord: нет экрана «Схемы»");
            return true;
        }

        List<MaterialRow> rows = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM)
        {
            if (rows.size() >= DISCORD_ROWS)
            {
                break;
            }

            if (item instanceof BlockItem)
            {
                ItemStack stack = new ItemStack(item);
                long total = 17L + rows.size() * 37L;
                rows.add(new MaterialRow(stack, ItemKeys.of(stack), stack.getHoverName().getString(), total, total, 0L, 0L,
                        stack.getMaxStackSize(), false));
            }
        }

        ExportWindow.Host host = new ExportWindow.Host(layers, toasts, () -> this.k.gui() == gui);
        this.discordWindow = ExportWindow.open(host, "Discord", "k-discord", List.of(ExportSource.rows("k-discord", rows)), 0,
                null);
        this.k.check("окно выгрузки на " + rows.size() + " строк", true, layers.top() == this.discordWindow);
        return true;
    }

    private @Nullable Button discordFormat()
    {
        Object grid = KProbe.fieldNamedType(this.discordWindow, "FormatGrid");

        try
        {
            return grid == null ? null : (Button) KProbe.call(grid, "button", ExportFormat.DISCORD);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private @Nullable Object discordRow()
    {
        return KProbe.fieldNamedType(this.discordWindow, "DiscordRow");
    }

    private @Nullable Button discordNext()
    {
        return KProbe.fieldOfType(this.discordRow(), Button.class);
    }

    private boolean discordFirst()
    {
        GuiSchematics gui = this.k.gui();
        Button format = this.discordFormat();
        Button next = this.discordNext();

        if (gui == null || format == null || next == null)
        {
            this.k.fail("Discord: нет кнопки формата или строки частей");
            return true;
        }

        if (! format.enabled())
        {
            // Строки готовы не в тот же кадр — ждём, пока кнопка оживёт
            return false;
        }

        this.k.clickButton(gui, format, null);
        return true;
    }

    /**
     * При GUI 3 окно выше экрана и кнопки форматов ниже края тела окна — игрок прокрутил бы колесом.
     * Крутим колесом над окном, пока кнопка Discord и строка частей под ней не окажутся над подвалом.
     */
    private boolean discordVisible()
    {
        GuiSchematics gui = this.k.gui();
        Button format = this.discordFormat();
        Layers layers = this.layers();

        if (gui == null || format == null || layers == null)
        {
            this.k.fail("Discord: нет окна");
            return true;
        }

        // Тело окна кончается над подвалом (FOOTER_H) и нижним полем экрана (OUTER); строка частей — ниже кнопки
        int bottom = layers.screenH() - 8 - 24 - 10;

        if (format.y() + format.h() * 2 + 4 <= bottom)
        {
            return true;
        }

        gui.onMouseScrolled(layers.screenW() / 2.0, layers.screenH() / 2.0, 0.0, -1.0);
        return false;
    }

    private boolean discordPartsLeft()
    {
        Button next = this.discordNext();

        try
        {
            Object row = this.discordRow();
            boolean shown = Boolean.TRUE.equals(KProbe.call(row, "shown"));
            boolean more = Boolean.TRUE.equals(KProbe.call(row, "more"));
            this.k.check("Discord: частей больше одной, строка частей видна", true, shown);
            this.k.check("Discord: остались части — кнопка живая", true, more && next != null && next.enabled());
            this.k.check("Discord: подпись кнопки", Strings.plain("layerlist.schematics.export.dialog.discord.next"),
                    next == null ? "" : next.label());
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("Discord: строка частей не читается: " + e);
        }

        return true;
    }

    private boolean discordRest()
    {
        GuiSchematics gui = this.k.gui();
        Button next = this.discordNext();

        if (gui == null || next == null)
        {
            this.k.fail("Discord: нет кнопки «дальше»");
            return true;
        }

        int clicks = 0;

        while (next.enabled() && clicks < 20)
        {
            this.k.clickButton(gui, next, null);
            // Подпись и доступность кнопки обновляет кадр окна — здесь тот же расчёт вручную
            try
            {
                KProbe.call(this.discordRow(), "update", true);
            }
            catch (ReflectiveOperationException e)
            {
                this.k.fail("Discord: update не зовётся: " + e);
                return true;
            }

            ++clicks;
        }

        this.k.info("Discord: «дальше» нажата " + clicks + " раз");
        this.k.check("Discord: все части скопированы — кнопка выключена", false, next.enabled());
        this.k.check("Discord: подпись", Strings.plain("layerlist.schematics.export.dialog.discord.all"), next.label());
        return true;
    }

    // ===== Главное меню Litematica =====

    private boolean openMenu()
    {
        if (this.menu == null)
        {
            Nav.clear();
            GuiBase.openGui(new GuiMainMenu());
            this.menu = GuiUtils.getCurrentScreen();
            return false;
        }

        return GuiUtils.getCurrentScreen() instanceof GuiMainMenu;
    }

    private boolean noMenuButton()
    {
        this.k.fail("в главном меню Litematica нет кнопки «Material HUD»");
        return true;
    }

    private static List<ButtonBase> buttons(Screen screen)
    {
        List<ButtonBase> out = new ArrayList<>();

        try
        {
            Field field = GuiBase.class.getDeclaredField("buttons");
            field.setAccessible(true);

            if (field.get(screen) instanceof List<?> list)
            {
                for (Object o : list)
                {
                    if (o instanceof ButtonBase b)
                    {
                        out.add(b);
                    }
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(DumpSchematics.TAG + "GuiBase.buttons не читается: " + e);
        }

        return out;
    }

    private static String label(ButtonBase button)
    {
        try
        {
            Field field = ButtonBase.class.getDeclaredField("displayString");
            field.setAccessible(true);
            return String.valueOf(field.get(button));
        }
        catch (ReflectiveOperationException e)
        {
            return "?";
        }
    }

    private @Nullable ButtonBase menuButton()
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (! (screen instanceof GuiMainMenu))
        {
            return null;
        }

        for (ButtonBase button : buttons(screen))
        {
            if (button.getClass().getName().endsWith("LitematicaMenuButton$MenuButton"))
            {
                return button;
            }
        }

        return null;
    }

    private boolean clickMenuButton()
    {
        ButtonBase button = this.menuButton();
        Screen screen = GuiUtils.getCurrentScreen();

        if (button == null || ! (screen instanceof GuiBase menu))
        {
            this.k.fail("кнопки «Material HUD» нет");
            return true;
        }

        this.k.check("кнопка меню: подпись", Strings.plain("layerlist.gui.litematica_menu"), label(button));
        this.k.check("кнопка меню: внутри окна", true, button.getX() >= 0 && button.getY() >= 0
                && button.getX() + button.getWidth() <= menu.width && button.getY() + button.getHeight() <= menu.height);

        this.checkApart(screen, button);
        KProbe.click(menu, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
        return true;
    }

    /** Наша кнопка не лежит ни на одной другой кнопке меню (своей или чужой). */
    private void checkApart(Screen screen, ButtonBase button)
    {
        boolean apart = true;

        for (ButtonBase other : buttons(screen))
        {
            if (other != button && other.getX() < button.getX() + button.getWidth() && button.getX() < other.getX() + other.getWidth()
                    && other.getY() < button.getY() + button.getHeight() && button.getY() < other.getY() + other.getHeight())
            {
                this.k.fail("кнопка меню легла на «" + label(other) + "»");
                apart = false;
            }
        }

        if (apart)
        {
            this.k.ok("кнопка меню ни на что не легла (" + button.getX() + "," + button.getY() + " " + button.getWidth() + "×"
                    + button.getHeight() + ")");
        }
    }

    /**
     * Чужой мод добавляет свою кнопку уже после нашей и ровно в нашу клетку — как Litematica Printer
     * (зашитая клетка, свой инжект в конец {@code initGui}). До 1.0.12 наша оставалась под ней: видна
     * чужая подпись, а клик забирала наша.
     */
    private boolean addForeign()
    {
        ButtonBase button = this.menuButton();

        if (button == null || ! (GuiUtils.getCurrentScreen() instanceof GuiBase menu))
        {
            this.k.fail("кнопки «Material HUD» нет — чужую класть некуда");
            return true;
        }

        this.foreignClicked = false;
        this.foreign = new ButtonGeneric(button.getX(), button.getY(), button.getWidth(), button.getHeight(), "Foreign Mod");
        menu.addButton(this.foreign, (b, mouseButton) -> this.foreignClicked = true);
        this.k.info("чужая кнопка: " + button.getX() + "," + button.getY() + " " + button.getWidth() + "×" + button.getHeight());
        return true;
    }

    private boolean foreignApart()
    {
        ButtonBase button = this.menuButton();
        Screen screen = GuiUtils.getCurrentScreen();

        if (button == null || screen == null)
        {
            this.k.fail("кнопки «Material HUD» нет после чужой");
            return true;
        }

        this.checkApart(screen, button);
        this.k.check("кнопка меню с чужой: внутри окна", true, button.getX() >= 0 && button.getY() >= 0
                && button.getX() + button.getWidth() <= screen.width && button.getY() + button.getHeight() <= screen.height);
        return true;
    }

    private boolean clickForeign()
    {
        ButtonBase foreign = this.foreign;
        Screen screen = GuiUtils.getCurrentScreen();

        if (foreign == null || ! (screen instanceof GuiBase menu))
        {
            this.k.fail("чужой кнопки нет");
            return true;
        }

        KProbe.click(menu, foreign.getX() + foreign.getWidth() / 2.0, foreign.getY() + foreign.getHeight() / 2.0);
        this.k.check("клик по чужой кнопке: сработала чужая", true, this.foreignClicked);
        this.k.check("клик по чужой кнопке: настройки Material HUD не открылись", false,
                GuiUtils.getCurrentScreen() instanceof GuiConfigs);
        this.foreign = null;

        if (GuiUtils.getCurrentScreen() instanceof GuiConfigs)
        {
            GuiBase.openGui(new GuiMainMenu());
        }

        return true;
    }

    private boolean settingsOpen()
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (! (screen instanceof GuiConfigs))
        {
            return false;
        }

        this.k.ok("кнопка «Material HUD» открыла настройки мода");
        this.k.check("«Назад» из настроек ведёт в меню Litematica", true, Nav.peek() == this.menu);
        return true;
    }

    private boolean backToMenu()
    {
        Screen screen = GuiUtils.getCurrentScreen();

        if (screen instanceof GuiConfigs settings)
        {
            KProbe.press(settings, InputConstants.KEY_ESCAPE);
            return false;
        }

        this.k.check("Esc из настроек вернул в меню Litematica", true, screen instanceof GuiMainMenu);
        return true;
    }

    /** Опция «Кнопка Material HUD в меню Litematica» выключена — кнопки нет; потом как было. */
    private boolean menuButtonOff()
    {
        this.menuButtonBefore = SchematicsConfig.MAIN_MENU_BUTTON.getBooleanValue();
        SchematicsConfig.MAIN_MENU_BUTTON.setBooleanValue(false);
        GuiBase.openGui(new GuiMainMenu());
        this.k.check("опция выкл.: кнопки «Material HUD» в меню нет", true, this.menuButton() == null);
        SchematicsConfig.MAIN_MENU_BUTTON.setBooleanValue(this.menuButtonBefore);
        return true;
    }

    private boolean reopenSchematics()
    {
        if (this.k.gui() != null)
        {
            this.menu = null;
            return true;
        }

        if (this.menu != null)
        {
            this.menu = null;
            Nav.clear();
            GuiSchematics.open(null);
        }

        return false;
    }
}
