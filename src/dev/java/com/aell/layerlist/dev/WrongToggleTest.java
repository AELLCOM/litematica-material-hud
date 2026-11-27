package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.Shortfall;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Toggle;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code wrongtoggle} (27.11.2026): тумблер «Не тот блок» во вкладке «Материалы» — та же настройка, что
 * «Считать „Не тот блок“» у HUD.
 * <ol>
 *   <li>Схема размещена: тумблер виден и доступен, «С учётом склада» остался на месте, виджеты полосы не наезжают
 *       друг на друга; «Осталось» — только пустые места.</li>
 *   <li>Настоящий щелчок по тумблеру: настройка включилась, «Осталось» выросло на клетки с не тем блоком (не то
 *       состояние не добавилось), панель HUD пересчиталась сама. Второй щелчок — всё как было.</li>
 *   <li>Источник «Список HUD»: тумблер тоже виден, щелчок меняет список.</li>
 *   <li>«Только пустые места» выключено: тумблер серый, щелчок ничего не меняет, подсказка объясняет почему.</li>
 *   <li>Схема не размещена: тумблера нет — сверять не с чем.</li>
 *   <li>Раскладка полосы на всех ширинах: без тумблера (схема не размещена) она прежняя, с тумблером — «С учётом
 *       склада» виден там же, где был, поиск не уже минимума, ничего не наезжает.</li>
 * </ol>
 * Тумблер и кнопки — настоящими щелчками ({@link SupportTest#click}).
 */
@Environment(EnvType.CLIENT)
public final class WrongToggleTest implements CovJob
{
    private static final String TAG = "[wrongtoggle] ";
    private static final BlockPos REF = new BlockPos(2100, 100, 0);
    private static final BlockPos SITE = new BlockPos(2100, 100, 8);
    private static final String STONE = "minecraft:stone";
    private static final String STAIRS = "minecraft:oak_stairs";
    private static final String FLOOR = "minecraft:smooth_stone";
    private static final String NAME = "dev-wrongtoggle";
    private static final long SETTLE_LIMIT_MS = 20_000L;
    private static final int SEARCH_MIN = 80;
    private static final int SWEEP_FROM = 220;
    private static final int SWEEP_TO = 1100;

    /** Раскладка полосы при одной ширине вкладки. */
    private record Layout(boolean stock, boolean cutoff, boolean wrong, boolean compact, int schematicW, int searchW,
                          String problem) {}

    private int stage;
    private long nextAt;
    private long stageStart;
    private boolean failed;
    private int checks;
    private boolean onlyEmptyWas;
    private boolean wrongBlocksWas;
    private boolean stockWas;
    private boolean cutoffWas;
    private boolean hudOnlyMissingWas;
    private boolean ignoreStateWas;
    private boolean paneOnlyMissingWas;
    private @Nullable SchematicEntry entry;
    private @Nullable SchematicPlacement placement;
    private final TreeMap<Integer, Layout> placedSweep = new TreeMap<>();

    @Override
    public String name()
    {
        return "wrongtoggle (тумблер «Не тот блок» во вкладке «Материалы»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        this.onlyEmptyWas = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        this.wrongBlocksWas = LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue();
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.cutoffWas = LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue();
        this.hudOnlyMissingWas = LayerListConfigs.ONLY_MISSING.getBooleanValue();
        this.ignoreStateWas = Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue();
        this.paneOnlyMissingWas = paneOnlyMissing();
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
        LayerListConfigs.SHORTFALL_WRONG_BLOCKS.setBooleanValue(false);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        Configs.Generic.MATERIAL_LIST_IGNORE_STATE.setBooleanValue(false);
        setPaneOnlyMissing(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "clear @a");
            command(server, "forceload add 2090 -10 2120 30");
            command(server, fill(REF, -3, -1, -3, 10, 5, 12, "minecraft:air"));
            // Образец схемы: пол 7, камень 4, ступени 3
            command(server, fill(REF, 0, 0, 0, 6, 0, 0, FLOOR));
            command(server, fill(REF, 0, 1, 0, 3, 1, 0, STONE));
            command(server, fill(REF, 4, 1, 0, 6, 1, 0, STAIRS + "[facing=north]"));
            command(server, "tp @a " + (SITE.getX() + 3.5D) + " " + SITE.getY() + " " + (SITE.getZ() + 6.5D) + " 180 10");
        });

        this.nextAt = Util.getMillis() + 3500L;
        this.stageStart = this.nextAt;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                this.entry = this.write(schematic(mc));
                this.check("схема записана в файл", this.entry != null, "");
                // Место постройки: пол целиком; камень — пусто, земля, камень, пусто; ступени — пусто, задом, булыжник
                server.execute(() -> {
                    command(server, fill(SITE, 0, 0, 0, 6, 0, 0, FLOOR));
                    command(server, set(SITE, 1, "minecraft:dirt"));
                    command(server, set(SITE, 2, STONE));
                    command(server, set(SITE, 5, STAIRS + "[facing=south]"));
                    command(server, set(SITE, 6, "minecraft:cobblestone"));
                });
                wait = 1500L;
            }
            case 1 ->
            {
                this.placement = this.place();
                GuiSchematics.openPlacements(null);
                wait = 2000L;
            }
            case 2 ->
            {
                this.invoke(this.gui(mc), "showMaterials", null, this.placement);
                wait = 1000L;
            }
            case 3 ->
            {
                // Ждём сверку с миром: строки камня и ступеней с «Осталось» 2 и 1
                if (! this.rows(mc, 2L, 1L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                Toggle toggle = this.toggle(pane);
                this.check("схема размещена: строки сверены с миром", Boolean.TRUE.equals(KTrash.field(pane, "verifiedRows")), "");
                this.check("умолчание: камень 2, ступени 1 (только пустые места)", this.rows(mc, 2L, 1L), this.dump(pane));
                this.check("пол построен — строки нет", this.shownLeft(pane, FLOOR) == -1L, this.dump(pane));
                this.check("тумблер «Не тот блок» виден", toggle != null && toggle.visible() && toggle.w() > 0, this.bounds(pane));
                this.check("тумблер доступен и выключен", toggle != null && toggle.enabled()
                        && ! LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue(), "");
                Toggle stock = KTrash.field(pane, "stockAware");
                this.check("«С учётом склада» остался на месте", stock != null && stock.visible() && stock.w() > 0, this.bounds(pane));
                Widget search = KTrash.field(pane, "search");
                this.check("поиск не уже минимума", search != null && search.w() >= SEARCH_MIN,
                        search == null ? "" : "ширина " + search.w());
                String problem = this.problem(pane);
                this.check("виджеты полосы не наезжают друг на друга", problem.isEmpty(), problem);
                CovOut.log(TAG + "полоса: " + this.bounds(pane) + (toggle != null && toggle.compact() ? " · тумблер без подписи"
                        : " · тумблер с подписью"));
                this.hover(mc, toggle);
                wait = 1300L;
            }
            case 4 ->
            {
                Object pane = this.materials(mc);
                Toggle toggle = this.toggle(pane);
                String tip = KTrash.field(toggle, "tooltip");
                this.check("подсказка тумблера — про «Не тот блок»", tip != null
                        && tip.equals(net.minecraft.client.resources.language.I18n.get("layerlist.schematics.materials.wrong_blocks.tip"))
                        && ! tip.contains("materials.wrong_blocks"), String.valueOf(tip));
                shot(mc, "wrongtoggle-1-off");
                // Тик ещё не прошёл: флажок пересчёта панели HUD стоит — значит, её разбудил сам щелчок, а не срок
                clearHudDirty();
                this.clickToggle(mc);
                this.check("щелчок сразу будит пересчёт панели HUD",
                        Boolean.TRUE.equals(KTrash.field(LayerCountManager.getInstance(), "dirty")), "");
                wait = 600L;
            }
            case 5 ->
            {
                if (! this.rows(mc, 3L, 2L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                this.check("щелчок: настройка «Считать „Не тот блок“» включилась",
                        LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue() && Shortfall.current() == Shortfall.WRONG_BLOCKS,
                        "" + Shortfall.current());
                this.check("таблица: камень 3 (земля на месте камня), ступени 2 (булыжник); ступень задом не считается",
                        this.rows(mc, 3L, 2L), this.dump(pane));
                wait = 1500L;
            }
            case 6 ->
            {
                // Панель HUD будит сам тумблер — ждать её очередного пересчёта не надо
                if (! this.hud(3, 2) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                this.check("панель HUD пересчиталась: камень 3, ступени 2", this.hud(3, 2), this.hudDump());
                shot(mc, "wrongtoggle-2-on");
                this.clickToggle(mc);
                wait = 600L;
            }
            case 7 ->
            {
                if (! (this.rows(mc, 2L, 1L) && this.hud(2, 1)) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                this.check("второй щелчок: настройка выключилась", ! LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue(), "");
                this.check("таблица вернулась: камень 2, ступени 1", this.rows(mc, 2L, 1L), this.dump(pane));
                this.check("панель HUD вернулась: камень 2, ступени 1", this.hud(2, 1), this.hudDump());
                // Источник «Список HUD» — настоящим щелчком по его кнопке
                Widget hudButton = KTrash.field(pane, "hudButton");
                this.click(mc, hudButton);
                wait = 2500L;
            }
            case 8 ->
            {
                if (! this.rows(mc, 2L, 1L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                Toggle toggle = this.toggle(pane);
                this.check("источник — «Список HUD»", "Hud".equals(this.sourceKind(pane)), this.sourceKind(pane));
                this.check("«Список HUD»: тумблер виден и доступен", toggle != null && toggle.visible() && toggle.enabled(),
                        this.bounds(pane));
                this.check("«Список HUD», умолчание: камень 2, ступени 1", this.rows(mc, 2L, 1L), this.dump(pane));
                String problem = this.problem(pane);
                this.check("«Список HUD»: виджеты полосы не наезжают", problem.isEmpty(), problem);
                this.clickToggle(mc);
                wait = 600L;
            }
            case 9 ->
            {
                if (! this.rows(mc, 3L, 2L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                this.check("«Список HUD», щелчок: камень 3, ступени 2", this.rows(mc, 3L, 2L), this.dump(pane));
                shot(mc, "wrongtoggle-3-hud-on");
                this.clickToggle(mc);
                wait = 600L;
            }
            case 10 ->
            {
                if (! this.rows(mc, 2L, 1L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                this.check("«Список HUD», второй щелчок: камень 2, ступени 1", this.rows(mc, 2L, 1L), this.dump(this.materials(mc)));
                // Как в настройках HUD: «Только пустые места» выключили — считается всё, что стоит не так
                LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
                wait = 600L;
            }
            case 11 ->
            {
                if (! this.rows(mc, 3L, 3L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                Toggle toggle = this.toggle(pane);
                this.check("«Только пустые места» выкл.: камень 3, ступени 3 (и ступень задом)", this.rows(mc, 3L, 3L), this.dump(pane));
                this.check("тумблер виден, но недоступен", toggle != null && toggle.visible() && ! toggle.enabled(), "");
                String tip = KTrash.field(toggle, "tooltip");
                this.check("подсказка объясняет, почему недоступен", tip != null && tip.equals(
                        net.minecraft.client.resources.language.I18n.get("layerlist.schematics.materials.wrong_blocks.tip.off"))
                        && ! tip.contains("materials.wrong_blocks"), String.valueOf(tip));
                this.clickToggle(mc);
                this.hover(mc, toggle);
                wait = 1300L;
            }
            case 12 ->
            {
                this.check("щелчок по недоступному ничего не меняет", ! LayerListConfigs.SHORTFALL_WRONG_BLOCKS.getBooleanValue()
                        && ! LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue(), "");
                shot(mc, "wrongtoggle-4-disabled");
                SupportTest.moveMouse(mc, 3.0D, 3.0D);
                LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
                this.invoke(this.gui(mc), "showMaterials", null, this.placement);
                wait = 1000L;
            }
            case 13 ->
            {
                if (! this.rows(mc, 2L, 1L) && now - this.stageStart < SETTLE_LIMIT_MS)
                {
                    return this.later(now);
                }

                Object pane = this.materials(mc);
                this.check("снова схема, умолчание: камень 2, ступени 1", this.rows(mc, 2L, 1L), this.dump(pane));
                this.check("тумблер снова доступен", this.toggle(pane) != null && this.toggle(pane).enabled(), "");
                // Короткое имя схемы: подписи кнопки почти нечего отдать — место тумблеру даёт подпись «Только недостающее»
                if (this.placement != null)
                {
                    this.placement.setName("w");
                    TreeMap<Integer, Layout> shortName = new TreeMap<>();
                    this.sweep(pane, shortName);
                    this.sweepChecks("короткое имя", shortName, true);
                    this.placement.setName(NAME);
                }

                this.sweep(pane, this.placedSweep);
                this.sweepChecks("размещена", this.placedSweep, true);

                if (this.placement != null)
                {
                    DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
                    this.placement = null;
                }

                this.invoke(this.gui(mc), "showMaterials", this.entry, null);
                wait = 4000L;
            }
            case 14 ->
            {
                Object pane = this.materials(mc);
                Toggle toggle = this.toggle(pane);
                this.check("схема не размещена", Boolean.TRUE.equals(KTrash.field(pane, "unplaced")), "");
                this.check("не размещена: тумблера «Не тот блок» нет", toggle != null && ! toggle.visible() && toggle.w() == 0,
                        this.bounds(pane));
                String problem = this.problem(pane);
                this.check("не размещена: виджеты полосы не наезжают", problem.isEmpty(), problem);
                TreeMap<Integer, Layout> unplaced = new TreeMap<>();
                this.sweep(pane, unplaced);
                this.sweepChecks("не размещена", unplaced, false);
                this.compare(unplaced);
                shot(mc, "wrongtoggle-5-unplaced");
                wait = 600L;
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK") + ", проверок " + this.checks);
                return true;
            }
        }

        this.nextAt = now + wait;
        this.stageStart = this.nextAt;
        ++this.stage;
        return false;
    }

    /** Счёт ещё идёт: заглянуть снова через десятую секунды (срок стадии не сдвигается). */
    private boolean later(long now)
    {
        this.nextAt = now + 100L;
        return false;
    }

    // ===== Раскладка полосы =====

    private static final String[] WIDGETS = {"schematicButton", "hudButton", "partyButton", "search", "resetAllButton",
            "changedOnly", "cutoff", "stockAware", "wrongBlocks", "missingOnly", "refreshButton"};

    /** Видимые виджеты полосы слева направо: «имя x..x+w». */
    private String bounds(@Nullable Object pane)
    {
        StringBuilder out = new StringBuilder();

        for (String name : WIDGETS)
        {
            Widget widget = KTrash.field(pane, name);

            if (widget != null && widget.visible() && widget.w() > 0)
            {
                out.append(name).append(' ').append(widget.x()).append("..").append(widget.x() + widget.w()).append("; ");
            }
        }

        return out.toString();
    }

    /** Что не так с раскладкой: наезд соседей или выход за края вкладки; пусто — всё в порядке. */
    private String problem(@Nullable Object pane)
    {
        if (! (pane instanceof Widget self))
        {
            return "вкладки нет";
        }

        List<Widget> shown = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (String name : WIDGETS)
        {
            Widget widget = KTrash.field(pane, name);

            if (widget == null)
            {
                return "нет виджета " + name;
            }

            if (widget.visible() && widget.w() > 0)
            {
                shown.add(widget);
                names.add(name);
            }
        }

        List<Integer> order = new ArrayList<>();

        for (int i = 0; i < shown.size(); ++i)
        {
            order.add(i);
        }

        order.sort(Comparator.comparingInt(i -> shown.get(i).x()));

        for (int k = 0; k < order.size(); ++k)
        {
            Widget widget = shown.get(order.get(k));

            if (widget.x() < self.x() || widget.x() + widget.w() > self.x() + self.w())
            {
                return names.get(order.get(k)) + " вышел за вкладку: " + widget.x() + ".." + (widget.x() + widget.w())
                        + " при " + self.x() + ".." + (self.x() + self.w());
            }

            if (k + 1 < order.size())
            {
                Widget next = shown.get(order.get(k + 1));

                if (widget.x() + widget.w() > next.x())
                {
                    return names.get(order.get(k)) + " наехал на " + names.get(order.get(k + 1)) + ": "
                            + (widget.x() + widget.w()) + " > " + next.x();
                }
            }
        }

        return "";
    }

    /** Раскладка полосы на каждой ширине вкладки; границы вкладки возвращаются на место. */
    private void sweep(@Nullable Object pane, TreeMap<Integer, Layout> into)
    {
        if (! (pane instanceof Widget self))
        {
            this.check("вкладка для раскладки", false, "");
            return;
        }

        int x = self.x();
        int y = self.y();
        int w = self.w();
        int h = self.h();

        try
        {
            for (int width = SWEEP_FROM; width <= SWEEP_TO; ++width)
            {
                self.setBounds(x, y, width, h);
                into.put(width, this.layout(pane));
            }

            // Ширина окна стенда — тоже в таблицу
            self.setBounds(x, y, w, h);
            into.put(w, this.layout(pane));
        }
        finally
        {
            self.setBounds(x, y, w, h);
        }

        CovOut.log(TAG + "ширина вкладки на стенде " + w);
    }

    private Layout layout(Object pane)
    {
        Toggle stock = KTrash.field(pane, "stockAware");
        Toggle cutoff = KTrash.field(pane, "cutoff");
        Toggle wrong = KTrash.field(pane, "wrongBlocks");
        Widget schematic = KTrash.field(pane, "schematicButton");
        Widget search = KTrash.field(pane, "search");

        return new Layout(stock != null && stock.visible() && stock.w() > 0, cutoff != null && cutoff.visible() && cutoff.w() > 0,
                wrong != null && wrong.visible() && wrong.w() > 0, wrong != null && wrong.compact(),
                schematic == null ? -1 : schematic.w(), search == null ? -1 : search.w(), this.problem(pane));
    }

    /** Общие правила раскладки на всех ширинах. */
    private void sweepChecks(String what, TreeMap<Integer, Layout> sweep, boolean withToggle)
    {
        String narrowSearch = "";
        String lost = "";
        String alone = "";
        String overlap = "";
        int firstShown = -1;
        int firstFull = -1;

        for (var e : sweep.entrySet())
        {
            Layout layout = e.getValue();
            int width = e.getKey();

            // Любой необязательный тумблер стоит, только если поиску осталось не меньше минимума
            if ((layout.stock() || layout.cutoff() || layout.wrong()) && layout.searchW() < SEARCH_MIN && narrowSearch.isEmpty())
            {
                narrowSearch = "ширина " + width + ": поиск " + layout.searchW();
            }

            // Ревью 27.11.2026: «С учётом склада» стоит, а тумблера нет — так быть не должно ни на одной ширине
            if (withToggle && layout.stock() && ! layout.wrong() && alone.isEmpty())
            {
                alone = "ширина " + width + ": " + layout;
            }

            // Там, где тумблер встал, полоса обязана быть без наездов
            if (layout.wrong() && ! layout.problem().isEmpty() && overlap.isEmpty())
            {
                overlap = "ширина " + width + ": " + layout.problem();
            }

            if (layout.wrong())
            {
                firstShown = firstShown < 0 ? width : firstShown;
                firstFull = ! layout.compact() && firstFull < 0 ? width : firstFull;
            }
            else if (firstShown >= 0 && lost.isEmpty())
            {
                lost = "появился на " + firstShown + ", пропал на " + width;
            }

        }

        this.check(what + ": поиск не уже минимума рядом с тумблерами (ширины " + SWEEP_FROM + "–" + SWEEP_TO + ")",
                narrowSearch.isEmpty(), narrowSearch);

        if (! withToggle)
        {
            this.check(what + ": тумблера «Не тот блок» нет ни на одной ширине", firstShown < 0, "есть на " + firstShown);
            return;
        }

        this.check(what + ": появившись, тумблер больше не пропадает", firstShown >= 0 && lost.isEmpty(),
                firstShown < 0 ? "не появился вовсе" : lost);
        this.check(what + ": пока «С учётом склада» на месте, тумблер тоже на месте", alone.isEmpty(), alone);
        this.check(what + ": с тумблером полоса без наездов", overlap.isEmpty(), overlap);
        // Подпись — последняя в очереди на место: «С учётом склада» и «Считать до Y» встают раньше неё. На широкой
        // вкладке на месте все трое, и тумблер — с подписью
        Layout wide = sweep.get(SWEEP_TO);
        this.check(what + ": на широкой вкладке (" + SWEEP_TO + ") тумблер с подписью, остальные тумблеры на месте",
                wide != null && wide.wrong() && ! wide.compact() && wide.stock() && wide.cutoff(), String.valueOf(wide));
        // 2000 px окна при масштабе 3 — 666 px интерфейса: здесь тумблер обязан стоять
        Layout user = sweep.get(666);
        this.check(what + ": на ширине 666 (окно 2000 px, масштаб 3) тумблер есть, «С учётом склада» тоже",
                user != null && user.wrong() && user.stock(), String.valueOf(user));
        CovOut.log(TAG + what + ": тумблер с ширины " + firstShown + ", с подписью с " + firstFull);
    }

    /** Тумблер ничего не отнял у прежней раскладки, кроме места у подписи схемы и (иногда) «Считать до Y». */
    private void compare(TreeMap<Integer, Layout> unplaced)
    {
        String stock = "";
        String cutoff = "";
        String schematic = "";
        String overlap = "";
        int cutoffLost = 0;
        int squeezed = 0;
        int cleanFrom = -1;

        for (var e : this.placedSweep.entrySet())
        {
            Layout with = e.getValue();
            Layout without = unplaced.get(e.getKey());

            if (without == null)
            {
                continue;
            }

            if (with.stock() != without.stock() && stock.isEmpty())
            {
                stock = "ширина " + e.getKey() + ": с тумблером " + with.stock() + ", без него " + without.stock();
            }

            if (with.cutoff() && ! without.cutoff() && cutoff.isEmpty())
            {
                cutoff = "ширина " + e.getKey();
            }

            if (with.schematicW() > without.schematicW() && schematic.isEmpty())
            {
                schematic = "ширина " + e.getKey() + ": " + with.schematicW() + " > " + without.schematicW();
            }

            // Совсем узкая вкладка наезжала и раньше (кнопки источника не ужимаются) — с тумблером не должно стать хуже
            if (without.problem().isEmpty())
            {
                cleanFrom = cleanFrom < 0 ? e.getKey() : cleanFrom;

                if (! with.problem().isEmpty() && overlap.isEmpty())
                {
                    overlap = "ширина " + e.getKey() + ": " + with.problem();
                }
            }
            else
            {
                cleanFrom = -1;
            }

            cutoffLost += without.cutoff() && ! with.cutoff() ? 1 : 0;
            squeezed += with.schematicW() < without.schematicW() ? 1 : 0;
        }

        this.check("сравнение: с тумблером ничего не наезжает там, где не наезжало без него (без него чисто с ширины "
                + cleanFrom + ")", cleanFrom >= 0 && cleanFrom <= 400 && overlap.isEmpty(), overlap);
        this.check("сравнение: «С учётом склада» виден на тех же ширинах, что и без тумблера", stock.isEmpty(), stock);
        this.check("сравнение: «Считать до Y» с тумблером не появляется там, где его не было", cutoff.isEmpty(), cutoff);
        this.check("сравнение: подпись схемы с тумблером не шире, чем без него", schematic.isEmpty(), schematic);
        CovOut.log(TAG + "сравнение: «Считать до Y» уступил место на " + cutoffLost + " ширинах, подпись схемы ужата на "
                + squeezed + " из " + this.placedSweep.size());
    }

    // ===== Вкладка «Материалы» =====

    private @Nullable GuiSchematics gui(Minecraft mc)
    {
        return mc.gui.screen() instanceof GuiSchematics gui ? gui : null;
    }

    private @Nullable Object materials(Minecraft mc)
    {
        return KProbe.fieldNamedType(this.gui(mc), "MaterialsPane");
    }

    private @Nullable Toggle toggle(@Nullable Object pane)
    {
        return KTrash.field(pane, "wrongBlocks");
    }

    private void clickToggle(Minecraft mc)
    {
        Toggle toggle = this.toggle(this.materials(mc));

        if (toggle == null || ! toggle.visible())
        {
            this.check("тумблер найден", false, "");
            return;
        }

        SupportTest.click(mc, toggle.x() + 8.0D, toggle.y() + toggle.h() / 2.0D);
        SupportTest.moveMouse(mc, 3.0D, 3.0D);
    }

    private void click(Minecraft mc, @Nullable Widget widget)
    {
        if (widget == null || ! widget.visible())
        {
            this.check("кнопка найдена", false, "");
            return;
        }

        SupportTest.click(mc, widget.x() + widget.w() / 2.0D, widget.y() + widget.h() / 2.0D);
        SupportTest.moveMouse(mc, 3.0D, 3.0D);
    }

    private void hover(Minecraft mc, @Nullable Toggle toggle)
    {
        if (toggle != null)
        {
            SupportTest.moveMouse(mc, toggle.x() + 8.0D, toggle.y() + toggle.h() / 2.0D);
        }
    }

    private String sourceKind(@Nullable Object pane)
    {
        Object source = KTrash.field(pane, "source");
        return source == null ? "нет" : source.getClass().getSimpleName();
    }

    /** В таблице ровно такие «Осталось» у камня и ступеней. */
    private boolean rows(Minecraft mc, long stone, long stairs)
    {
        Object pane = this.materials(mc);
        return this.shownLeft(pane, STONE) == stone && this.shownLeft(pane, STAIRS) == stairs;
    }

    /** Строка видна в таблице: её {@code lack} («Осталось»); -1 — строки на экране нет. */
    private long shownLeft(@Nullable Object pane, String itemId)
    {
        List<?> shown = KTrash.field(pane, "shown");

        if (shown != null)
        {
            for (Object line : shown)
            {
                MaterialLine l = (MaterialLine) line;

                if (StorageIndex.itemId(l.stack()).equals(itemId))
                {
                    return l.lack();
                }
            }
        }

        return -1L;
    }

    private String dump(@Nullable Object pane)
    {
        List<?> shown = KTrash.field(pane, "shown");

        if (shown == null)
        {
            return "таблицы нет";
        }

        StringBuilder out = new StringBuilder("на экране:");

        for (Object line : shown)
        {
            MaterialLine l = (MaterialLine) line;
            out.append(' ').append(StorageIndex.itemId(l.stack())).append(" всего=").append(l.need()).append(" осталось=")
                    .append(l.lack()).append(';');
        }

        return out.toString();
    }

    /** Панель HUD: недостающее камня и ступеней. */
    private boolean hud(int stone, int stairs)
    {
        return hudMissing(STONE) == stone && hudMissing(STAIRS) == stairs;
    }

    private static int hudMissing(String itemId)
    {
        LayerCountManager hud = LayerCountManager.getInstance();

        if (! hud.hasData())
        {
            return -1;
        }

        for (MaterialListEntry entry : hud.getAllEntries())
        {
            if (StorageIndex.itemId(entry.getStack()).equals(itemId))
            {
                return (int) entry.getCountMissing();
            }
        }

        return 0;
    }

    private String hudDump()
    {
        return "панель: камень " + hudMissing(STONE) + ", ступени " + hudMissing(STAIRS);
    }

    private void invoke(@Nullable Object target, String method, Object... args)
    {
        if (target == null)
        {
            this.check(method, false, "экран закрыт");
            return;
        }

        try
        {
            KProbe.call(target, method, args);
        }
        catch (ReflectiveOperationException e)
        {
            this.check(method, false, String.valueOf(e.getCause() != null ? e.getCause() : e));
        }
    }

    /** Снять флажок «пересчитать» у счётчика панели HUD: после щелчка он обязан встать снова. */
    private static void clearHudDirty()
    {
        try
        {
            Field field = LayerCountManager.class.getDeclaredField("dirty");
            field.setAccessible(true);
            field.setBoolean(LayerCountManager.getInstance(), false);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "dirty: " + e);
        }
    }

    private static Field paneOnlyMissingField() throws ReflectiveOperationException
    {
        Field field = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane").getDeclaredField("onlyMissing");
        field.setAccessible(true);
        return field;
    }

    private static boolean paneOnlyMissing()
    {
        try
        {
            return paneOnlyMissingField().getBoolean(null);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "onlyMissing: " + e);
            return false;
        }
    }

    private static void setPaneOnlyMissing(boolean value)
    {
        try
        {
            paneOnlyMissingField().setBoolean(null, value);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "onlyMissing: " + e);
        }
    }

    // ===== Схема и мир =====

    private static LitematicaSchematic schematic(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName(NAME);
        area.addSubRegionBox(new Box(REF, REF.offset(6, 1, 0), "main"), false);
        area.setExplicitOrigin(REF);
        return LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                "dev", CovOut::log);
    }

    private Path dir()
    {
        return DataManager.getSchematicsBaseDirectory().resolve(NAME);
    }

    private @Nullable SchematicEntry write(@Nullable LitematicaSchematic schematic)
    {
        try
        {
            Path dir = this.dir();
            Files.createDirectories(dir);

            if (schematic == null || ! schematic.writeToFile(dir, NAME, true))
            {
                return null;
            }

            return SchematicLibrary.get().entry(dir.resolve(NAME + ".litematic"));
        }
        catch (java.io.IOException | RuntimeException e)
        {
            CovOut.log(TAG + "запись схемы: " + e);
            return null;
        }
    }

    private @Nullable SchematicPlacement place()
    {
        LitematicaSchematic read = LitematicaSchematic.createFromFile(this.dir(), NAME + ".litematic");

        if (read == null)
        {
            this.check("схема читается из файла", false, "");
            return null;
        }

        SchematicPlacement placement = SchematicPlacement.createFor(read, SITE, NAME, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        return placement;
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log(TAG + "снимок " + name);
        Screenshot.grab(mc, false);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    /** Блок второго ряда (над полом) места постройки. */
    private static String set(BlockPos o, int x, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY() + 1, o.getZ(), block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
        ++this.checks;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log(TAG + "команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(this.onlyEmptyWas);
        LayerListConfigs.SHORTFALL_WRONG_BLOCKS.setBooleanValue(this.wrongBlocksWas);
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(this.cutoffWas);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(this.hudOnlyMissingWas);
        Configs.Generic.MATERIAL_LIST_IGNORE_STATE.setBooleanValue(this.ignoreStateWas);
        setPaneOnlyMissing(this.paneOnlyMissingWas);
        // Щелчок по тумблеру пишет настройки в файл — возвращаем в файл и прежние значения
        new LayerListConfigs().save();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
