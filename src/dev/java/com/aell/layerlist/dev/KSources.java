package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.SchematicsState;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.ExportSource;
import com.aell.layerlist.schematics.gui.export.ExportWindow;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.materials.MaterialsJob;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Glyphs;
import com.aell.layerlist.ui.kit.Kit;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.Toggle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27 — три просьбы пользователя по выгрузке:
 * <ul>
 *   <li>что значит «Всё / Только недостающее» — пояснение окна у каждого вида источника и сверка с самим
 *       счётом: у списка HUD неправильно поставленный блок (не тот блок) не входит в «не хватает», пока включена
 *       «Только пустые места», и входит, когда её выключили; выгрузка берёт ровно это число;</li>
 *   <li>«Схема» вкладки «Материалы» без выбора в «Библиотеке»: серая только без схем вовсе (подсказка — почему),
 *       одно размещение — его схема, несколько — список, выбранное в Litematica размещение — оно;</li>
 *   <li>кнопка карточки «Экспорт списка материалов ▾» (где не влезает — «Экспорт материалов ▾») при GUI 2 и 3 и
 *       в узких раскладках.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class KSources
{
    private static final String DIALOG = "layerlist.schematics.export.dialog.";
    private static final String SECOND_NAME = "k-second-place";
    private static final long CHOICES_SETTLE_MS = 1_200L;
    private static final long HUD_WAIT_MS = 20_000L;

    private final DumpSchematics k;
    private final List<SchematicPlacement> made = new ArrayList<>();
    private @Nullable ExportOptions optionsBefore;
    private @Nullable Boolean onlyEmptyBefore;
    private @Nullable Boolean hudBefore;
    private @Nullable SchematicPlacement first;
    private @Nullable SchematicPlacement second;
    private @Nullable CompletableFuture<LoadedSchematic> loadingSecond;
    private @Nullable BlockPos wrongAt;
    private long waitFrom;
    private int missingBefore = -1;
    private int mismatchBefore = -1;
    private boolean reopened;
    private int scrolls;

    // Размещённая схема в «Материалах» (1.0.18): сверка с миром по всему размещению
    private @Nullable SchematicPlacement placedP;
    private @Nullable BlockPos brickCell;
    private long placedTotal = -1L;
    private long placedLack = -1L;
    private @Nullable Item flowerItem;
    private final List<BlockPos> placedCells = new ArrayList<>();
    private @Nullable Boolean onlyBefore;
    private boolean sent;
    private boolean clicked;
    private long recountAt;
    private int rowsAll;
    private int rowsLack;
    private int rowsUnplaced;

    KSources(DumpSchematics k)
    {
        this.k = k;
    }

    // ===== Сценарий =====

    List<DumpSchematics.Step> steps(String p)
    {
        List<DumpSchematics.Step> s = new ArrayList<>();

        // Кнопка карточки
        s.add(step(p + "карточка: «Библиотека»", 2_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(step(p + "карточка: k-generated", 5_000L, mc -> this.k.selectGenerated()));
        s.add(step(p + "карточка: прокрутить до кнопки экспорта", 5_000L, this.k.settleThen(400L, mc -> this.scrollCard())));
        s.add(step(p + "карточка: кнопка экспорта, подпись влезает", 5_000L,
                this.k.settleThen(400L, mc -> this.cardExport("1600×900"))));
        s.add(step(p + "снимок: карточка — кнопка экспорта", 5_000L, this.k.settleShot(p + "card-export")));

        if (p.isEmpty())
        {
            s.add(step("карточка: окно 1200×900, GUI 2", 8_000L, this.k.window(1200, 900, 2)));
            s.add(step("карточка 1200×900: прокрутить до кнопки", 5_000L, this.k.settleThen(600L, mc -> this.scrollCard())));
            s.add(step("карточка 1200×900: кнопка экспорта", 5_000L, this.k.settleThen(400L, mc -> this.cardExport("1200×900"))));
            s.add(step("снимок: карточка 1200×900", 5_000L, this.k.settleShot("card-export-1200")));
            s.add(step("карточка: окно 800×900, GUI 2", 8_000L, this.k.window(800, 900, 2)));
            s.add(step("карточка 800×900: «Просмотр»", 3_000L, mc -> this.k.narrowView()));
            s.add(step("карточка 800×900: прокрутить до кнопки", 5_000L, this.k.settleThen(600L, mc -> this.scrollCard())));
            s.add(step("карточка 800×900: кнопка экспорта", 5_000L, this.k.settleThen(400L, mc -> this.cardExport("800×900"))));
            s.add(step("снимок: карточка 800×900", 5_000L, this.k.settleShot("card-export-800")));
            s.add(step("карточка: окно 1600×900, GUI 2 — обратно", 8_000L, this.k.window(1600, 900, 2)));

            // Что значит «Только недостающее»
            s.add(step("фильтр: окно выгрузки карточки", 5_000L, mc -> this.k.openExportDialog(mc)));
            s.add(step("фильтр: пояснения у схемы", 3_000L, this.k.settleThen(300L, mc -> this.filterTexts())));
            s.add(step("снимок: пояснение «Только недостающее» у схемы", 5_000L, this.k.settleShot("export-filter-schematic")));
            s.add(step("фильтр: Esc закрывает окно", 3_000L, mc -> this.k.closeExportDialog(mc)));
            s.add(step("HUD: размещение k-generated, HUD включён", 30_000L, mc -> this.placeForHud()));
            s.add(step("HUD: счёт пришёл, клетка каменных кирпичей найдена", HUD_WAIT_MS + 5_000L, mc -> this.findCell(mc)));
            s.add(step("HUD: на место кирпичей — земля (не тот блок)", 10_000L, mc -> this.putWrongBlock(mc)));
            s.add(step("HUD: не тот блок — не «не хватает» («Только пустые места» вкл.)", HUD_WAIT_MS + 5_000L,
                    mc -> this.wrongExcluded()));
            s.add(step("фильтр: окно «Список HUD» (без схемы)", 5_000L, mc -> this.openHudWindow()));
            s.add(step("фильтр: пояснения у списка HUD", 3_000L, this.k.settleThen(600L, mc -> this.hudTexts())));
            s.add(step("снимок: пояснение «Только недостающее» у списка HUD", 5_000L, this.k.settleShot("export-filter-hud")));
            s.add(step("фильтр: Esc закрывает окно «Список HUD»", 3_000L, mc -> this.k.closeExportDialog(mc)));
            s.add(step("HUD: «Только пустые места» выкл.", 3_000L, mc -> this.onlyEmptyOff()));
            s.add(step("HUD: «Только пустые места» выкл. — не тот блок входит", HUD_WAIT_MS + 5_000L, mc -> this.wrongIncluded()));
            s.add(step("HUD: уборка", 10_000L, mc -> this.hudTidy(mc)));

            // «Схема» без выбора в «Библиотеке»
            s.add(step("«Схема»: размещений и загруженных схем нет", 5_000L, mc -> this.clearWorld()));
            s.add(step("«Схема»: экран заново, без выбора", 10_000L, mc -> this.reopen()));
            s.add(step("«Схема»: «Материалы» — «Список HUD»", 3_000L, mc -> this.k.setTab("MATERIALS")));
            s.add(step("«Схема»: схем нет — серая, подсказка почему", 5_000L, this.k.settleThen(CHOICES_SETTLE_MS, mc -> this.nothing())));
            s.add(step("снимок: «Схема» серая — схем нет", 5_000L, this.k.settleShot("materials-no-schematic")));
            s.add(step("«Схема»: одно размещение, в Litematica не выбрано", 30_000L, mc -> this.placeOne()));
            s.add(step("«Схема»: экран заново (одно размещение)", 10_000L, mc -> this.reopen()));
            s.add(step("«Схема»: «Материалы» (одно размещение)", 3_000L, mc -> this.k.setTab("MATERIALS")));
            s.add(step("«Схема»: одно размещение — его схема", 5_000L, this.k.settleThen(CHOICES_SETTLE_MS, mc -> this.one())));
            s.add(step("снимок: «Схема» — схема единственного размещения", 5_000L, this.k.settleShot("materials-one-placement")));
            s.add(step("«Схема»: второе размещение (другая схема)", 30_000L, mc -> this.placeSecond()));
            s.add(step("«Схема»: экран заново (два размещения)", 10_000L, mc -> this.reopen()));
            s.add(step("«Схема»: «Материалы» (два размещения)", 3_000L, mc -> this.k.setTab("MATERIALS")));
            s.add(step("«Схема»: два размещения — «Схема ▾» открывает выбор", 5_000L,
                    this.k.settleThen(CHOICES_SETTLE_MS, mc -> this.pickerOpens())));
            s.add(step("снимок: выбор схемы", 5_000L, this.k.settleShot("materials-pick-schematic")));
            s.add(step("«Схема»: ↓ ↓ Enter — вторая схема", 3_000L, mc -> this.pickSecond()));
            s.add(step("«Схема»: экран заново (выбрано в Litematica)", 10_000L, mc -> this.reopenWithSelected()));
            s.add(step("«Схема»: «Материалы» (выбрано в Litematica)", 3_000L, mc -> this.k.setTab("MATERIALS")));
            s.add(step("«Схема»: выбранное в Litematica размещение — сразу оно", 5_000L,
                    this.k.settleThen(CHOICES_SETTLE_MS, mc -> this.selectedWins())));
            s.add(step("«Схема»: уборка", 5_000L, mc -> this.choiceTidy()));
            s.add(step("«Библиотека»: снова k-generated", 5_000L, mc -> this.k.selectGenerated()));
            s.addAll(this.placedSteps());
        }

        return s;
    }

    void cleanup()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.restoreSettings();
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    private void restoreSettings()
    {
        if (this.optionsBefore != null)
        {
            SchematicsState.get().setExportOptions(this.optionsBefore);
            this.optionsBefore = null;
        }

        if (this.onlyEmptyBefore != null)
        {
            LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(this.onlyEmptyBefore);
            this.onlyEmptyBefore = null;
        }

        if (this.hudBefore != null)
        {
            LayerListConfigs.HUD_ENABLED = this.hudBefore;
            this.hudBefore = null;
        }
    }

    // ===== Кнопка карточки =====

    private boolean cardExport(String where)
    {
        GuiSchematics gui = this.k.gui();
        Button button = KProbe.cardExport(this.k.infoPane());

        if (gui == null || button == null)
        {
            this.k.fail("карточка " + where + ": кнопки экспорта нет");
            return true;
        }

        String full = I18n.get("layerlist.schematics.info.export");
        String shortLabel = I18n.get("layerlist.schematics.info.export.short");
        int fullW = new Button(full, Button.Style.SECONDARY, null).trailing(Glyphs.CHEVRON_DOWN).preferredWidth(Kit.font());
        boolean fits = button.preferredWidth(Kit.font()) <= button.w();
        this.k.info("карточка " + where + ": экран " + gui.width + "×" + gui.height + ", кнопка " + button.w() + " px, «"
                + button.label() + "»");
        this.k.check("карточка " + where + ": кнопка экспорта видна", true, button.visible() && button.w() > 0);
        this.k.check("карточка " + where + ": подпись «" + button.label() + "» влезает целиком", true, fits);
        // Длинная подпись — всегда, когда влезает; короткая — только когда длинная не влезла бы
        this.k.check("карточка " + where + ": длинная подпись, если влезает", fullW <= button.w() ? full : shortLabel,
                button.label());
        return true;
    }

    // ===== Что значит «Только недостающее» =====

    private @Nullable ExportWindow window()
    {
        Layers layers = KProbe.fieldOfType(this.k.gui(), Layers.class);

        return layers != null && layers.top() instanceof ExportWindow window ? window : null;
    }

    private static @Nullable String about(ExportWindow window)
    {
        try
        {
            Object message = KProbe.call(window, "filterAbout");
            return message == null ? null : String.valueOf(KProbe.call(message, "text"));
        }
        catch (ReflectiveOperationException e)
        {
            return "?" + e;
        }
    }

    private static void setOnlyMissing(boolean value)
    {
        ExportOptions o = SchematicsState.get().exportOptions();
        SchematicsState.get().setExportOptions(new ExportOptions(value, o.icons(), o.rawMaterials(), o.chart()));
    }

    private boolean filterTexts()
    {
        ExportWindow window = this.window();

        if (window == null)
        {
            this.k.fail("фильтр: окна выгрузки нет");
            return true;
        }

        this.optionsBefore = SchematicsState.get().exportOptions();
        this.onlyEmptyBefore = LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);

        setOnlyMissing(false);
        this.k.check("пояснение: схема, «Всё»", I18n.get(DIALOG + "filter.about.schematic.all"), about(window));
        setOnlyMissing(true);
        this.k.check("пояснение: схема, «Только недостающее» (= «Всё», с миром не сверяется)",
                I18n.get(DIALOG + "filter.about.schematic.missing"), about(window));

        this.k.check("пояснение: строки без вида источника — без пояснения", "null",
                String.valueOf(ExportWindow.filterAbout(null, true, true)));
        return true;
    }

    // ===== Список HUD: не тот блок =====

    private @Nullable LoadedSchematic generated()
    {
        SchematicEntry generated = this.k.generated(".litematic");
        return generated == null ? null : this.k.loaded(generated.path());
    }

    private boolean placeForHud()
    {
        LoadedSchematic loaded = this.generated();

        if (loaded == null)
        {
            this.k.fail("HUD: k-generated не загружена");
            return true;
        }

        this.hudBefore = LayerListConfigs.HUD_ENABLED;
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);

        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.made.add(p);
        this.first = p;
        LayerCountManager.getInstance().markDirty();
        this.waitFrom = Util.getMillis();
        return true;
    }

    private static @Nullable MaterialListEntry bricks()
    {
        for (MaterialListEntry entry : LayerCountManager.getInstance().getEntries())
        {
            if (entry.getStack().is(Items.STONE_BRICKS))
            {
                return entry;
            }
        }

        return null;
    }

    private boolean findCell(Minecraft mc)
    {
        SchematicPlacement p = this.first;
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        MaterialListEntry entry = bricks();

        if (p == null || mc.level == null)
        {
            this.k.fail("HUD: нет размещения или мира");
            return true;
        }

        if (schematicWorld == null || entry == null || ! LayerCountManager.getInstance().hasData())
        {
            return false;
        }

        KPlace.Bounds b = KPlace.bounds(p);

        if (b == null)
        {
            return false;
        }

        for (int y = b.minY(); y <= b.maxY() && this.wrongAt == null; ++y)
        {
            for (int x = b.minX(); x <= b.maxX() && this.wrongAt == null; ++x)
            {
                for (int z = b.minZ(); z <= b.maxZ() && this.wrongAt == null; ++z)
                {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (schematicWorld.getBlockState(pos).is(Blocks.STONE_BRICKS) && mc.level.getBlockState(pos).isAir())
                    {
                        this.wrongAt = pos;
                    }
                }
            }
        }

        if (this.wrongAt == null)
        {
            // Мир схемы заполняется по чанкам — подождать
            return false;
        }

        this.missingBefore = entry.getCountMissing();
        this.mismatchBefore = entry.getCountMismatched();
        this.k.info("HUD: каменные кирпичи — всего " + entry.getCountTotal() + ", не хватает " + this.missingBefore
                + ", не тот блок " + this.mismatchBefore + "; клетка " + this.wrongAt.toShortString());
        return true;
    }

    private boolean putWrongBlock(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos pos = this.wrongAt;

        if (server == null || pos == null || mc.level == null)
        {
            this.k.fail("HUD: нет сервера или клетки");
            return true;
        }

        if (! mc.level.getBlockState(pos).is(Blocks.DIRT))
        {
            if (this.waitFrom >= 0L)
            {
                this.waitFrom = -1L;
                server.execute(() -> KData.command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:dirt",
                        pos.getX(), pos.getY(), pos.getZ())));
            }

            return false;
        }

        LayerCountManager.getInstance().markDirty();
        this.waitFrom = Util.getMillis();
        return true;
    }

    private boolean wrongExcluded()
    {
        MaterialListEntry entry = bricks();

        if (entry == null || entry.getCountMismatched() <= this.mismatchBefore)
        {
            return false;
        }

        int missing = entry.getCountMissing();
        this.k.check("HUD: земля на месте кирпичей — «не тот блок» +1", this.mismatchBefore + 1, entry.getCountMismatched());
        this.k.check("HUD: «Только пустые места» вкл. — «не хватает» на 1 меньше (не тот блок не входит)",
                this.missingBefore - 1, missing);

        MaterialRow row = hudRow();
        this.k.check("«Список HUD» «Материалов» и выгрузок: не хватает = счёт HUD", missing, row == null ? -1 : row.missing());
        this.k.check("выгрузка «Только недостающее» (JSON): кирпичей = не хватает", (long) missing, exportedBricks());
        return true;
    }

    /** Окно выгрузки без схемы — источник один, «Список HUD» (так его открывает экран без выбранной схемы). */
    private boolean openHudWindow()
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null)
        {
            this.k.fail("окно «Список HUD»: экрана нет");
            return true;
        }

        try
        {
            KProbe.call(gui, "openExport", (Object) null);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("openExport(null): " + e);
            return true;
        }

        ExportWindow window = this.window();
        ExportSource source = window == null ? null : KProbe.fieldOfType(window, ExportSource.class);
        this.k.check("окно без схемы — источник «Список HUD»", ExportSource.Kind.HUD, source == null ? null : source.kind());
        return true;
    }

    private boolean hudTexts()
    {
        ExportWindow window = this.window();

        if (window == null)
        {
            this.k.fail("фильтр HUD: окна нет");
            return true;
        }

        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
        this.k.check("пояснение: HUD, «Только пустые места» выкл. — не тот блок входит",
                I18n.get(DIALOG + "filter.about.hud.missing_wrong"), about(window));
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
        setOnlyMissing(false);
        this.k.check("пояснение: HUD, «Всё»", I18n.get(DIALOG + "filter.about.hud.all"), about(window));
        setOnlyMissing(true);
        // Так и остаётся для снимка: «Только недостающее», «Только пустые места» вкл.
        this.k.check("пояснение: HUD, «Только недостающее», «Только пустые места» вкл. — неправильные не входят",
                I18n.get(DIALOG + "filter.about.hud.missing"), about(window));
        return true;
    }

    private boolean onlyEmptyOff()
    {
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(false);
        LayerCountManager.getInstance().markDirty();
        return true;
    }

    /** Прокрутить карточку колесом, пока кнопка экспорта не окажется внутри неё (в стопке карточка ниже превью). */
    private boolean scrollCard()
    {
        GuiSchematics gui = this.k.gui();
        Object pane = this.k.infoPane();
        Button button = KProbe.cardExport(pane);

        if (gui == null || ! (pane instanceof com.aell.layerlist.ui.kit.Widget card) || button == null)
        {
            this.k.fail("карточка: нет экрана, карточки или кнопки");
            return true;
        }

        if (button.y() + button.h() <= card.y() + card.h() || this.scrolls >= 20)
        {
            this.k.check("карточка: кнопка экспорта в видимой части карточки (прокруток " + this.scrolls + ")", true,
                    button.y() >= card.y() && button.y() + button.h() <= card.y() + card.h());
            this.scrolls = 0;
            return true;
        }

        ++this.scrolls;
        gui.onMouseScrolled(card.x() + card.w() / 2.0, card.y() + card.h() / 2.0, 0.0, -1.0);
        return false;
    }

    private boolean wrongIncluded()
    {
        MaterialListEntry entry = bricks();

        if (entry == null || entry.getCountMissing() != this.missingBefore)
        {
            return false;
        }

        this.k.check("HUD: «Только пустые места» выкл. — не тот блок снова в «не хватает»", this.missingBefore,
                entry.getCountMissing());
        MaterialRow row = hudRow();
        this.k.check("«Список HUD»: с выключенной настройкой не хватает = счёт HUD", this.missingBefore,
                row == null ? -1 : row.missing());
        this.k.check("выгрузка «Только недостающее» (JSON): кирпичей вместе с неправильным", (long) this.missingBefore,
                exportedBricks());
        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);
        return true;
    }

    private static @Nullable MaterialRow hudRow()
    {
        for (MaterialRow row : SchematicMaterials.ofHud())
        {
            if (row.stack().is(Items.STONE_BRICKS))
            {
                return row;
            }
        }

        return null;
    }

    /** Сколько каменных кирпичей выгрузка JSON «Списка HUD» с «Только недостающим» кладёт в {@code quantity}. */
    private static long exportedBricks()
    {
        List<MaterialRow> rows = SchematicMaterials.ofHud();
        ExportRequest request = new ExportRequest("k-hud", "HUD", rows, List.of(),
                new ExportOptions(true, false, false, false), "k-hud-missing");
        ExportResult result = Exporters.json(request);

        if (! result.ok() || result.files().isEmpty())
        {
            return -1L;
        }

        try
        {
            Path file = result.files().getFirst();
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Files.deleteIfExists(file);

            for (JsonElement item : root.getAsJsonArray("items"))
            {
                JsonObject o = item.getAsJsonObject();

                if ("minecraft:stone_bricks".equals(o.get("id").getAsString()))
                {
                    return o.get("quantity").getAsLong();
                }
            }

            return 0L;
        }
        catch (IOException | RuntimeException e)
        {
            return -2L;
        }
    }

    private boolean hudTidy(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos pos = this.wrongAt;

        if (server != null && pos != null)
        {
            server.execute(() -> KData.command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air",
                    pos.getX(), pos.getY(), pos.getZ())));
        }

        LayerListConfigs.SHORTFALL_ONLY_MISSING.setBooleanValue(true);

        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.first = null;
        LayerCountManager.getInstance().markDirty();
        return true;
    }

    // ===== Размещённая схема в «Материалах»: сверка с миром (1.0.18) =====

    private static final String MAT = "layerlist.schematics.materials.";

    /**
     * Источник «Схема: k-generated» размещённой схемы идёт от счётчика размещений: «Всего» — файл, «Не хватает» —
     * по миру на всём размещении; «Только недостающее» работает и уходит в выгрузку (вид PLACED). Неразмещённая —
     * тумблер серый и объясняет почему. {@code LL_K_ONLY=placed}.
     */
    List<DumpSchematics.Step> placedSteps()
    {
        String t = "размещённая: ";
        List<DumpSchematics.Step> s = new ArrayList<>();

        s.add(step(t + "«Библиотека», k-generated", 5_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(step(t + "k-generated выбрана", 5_000L, this.k.settleThen(300L, mc -> this.k.selectGenerated())));
        // «Материалы» раньше размещения: файл схемы загружает вкладка, а размещать можно только загруженную. Источник —
        // явно «Схема: k-generated», как кликом по карточке: в полном прогоне до этого таблица бывает на «Списке HUD»
        s.add(step(t + "«Материалы»: Схема k-generated", 5_000L, mc -> this.showGenerated()));
        s.add(step(t + "размещение k-generated, HUD включён", 30_000L, mc -> this.placePlaced()));
        s.add(step(t + "строки сверены с миром, как у HUD", HUD_WAIT_MS + 15_000L, mc -> this.placedReady()));
        s.add(step(t + "тумблер «Только недостающее» выключен", 8_000L, mc -> this.toggleOff()));
        s.add(step(t + "на пустое место — один верный кирпич", 10_000L, mc -> this.putBrick(mc)));
        s.add(step(t + "после пересчёта «Не хватает» на 1 меньше, «Всего» то же", 25_000L, mc -> this.brickRecounted()));
        // Слои Litematica и голограмма на счёт таблицы не влияют (вопрос 21.10.2026): один слой, затем размещение выключено
        s.add(step(t + "Litematica: один слой", 5_000L, mc -> this.singleLayer(true)));
        s.add(step(t + "один слой: «Не хватает» кирпичей то же", 10_000L, this.k.settleThen(500L, mc -> this.lackSame("один слой"))));
        s.add(step(t + "Litematica: снова все слои", 5_000L, mc -> this.singleLayer(false)));
        s.add(step(t + "размещение выключено (голограммы нет)", 5_000L, mc -> this.placementOn(false)));
        s.add(step(t + "размещение выключено: «Не хватает» кирпичей то же", 10_000L,
                this.k.settleThen(500L, mc -> this.lackSame("размещение выключено"))));
        s.add(step(t + "размещение снова включено", 5_000L, mc -> this.placementOn(true)));
        s.add(step(t + "включено: «Не хватает» кирпичей то же", 10_000L, this.k.settleThen(500L, mc -> this.lackSame("включено"))));
        s.add(step(t + "строка, которую можно достроить целиком", 10_000L, mc -> this.pickFlower(mc)));
        s.add(step(t + "достраиваем строку до нуля", 25_000L, mc -> this.buildFlower(mc)));
        s.add(step(t + "строка досчитана до нуля", 25_000L, mc -> this.flowerZero()));
        s.add(step(t + "«Только недостающее»: настоящий клик", 5_000L, mc -> this.clickOnlyMissing(true)));
        s.add(step(t + "строки с нулём ушли", 8_000L, mc -> this.zeroGone()));
        s.add(step(t + "колонка «Осталось»: остаток вместо всей постройки", 5_000L, mc -> this.leftColumn()));
        s.add(step(t + "снимок: сверенная таблица, тумблер включён", 5_000L, this.k.settleShot("placed-only-missing")));
        s.add(step(t + "выгрузка кнопкой подвала", 10_000L, mc -> this.exportFromPane()));
        s.add(step(t + "снимок: окно выгрузки размещённой схемы", 5_000L, this.k.settleShot("placed-export")));
        s.add(step(t + "Esc закрывает окно", 3_000L, mc -> this.k.closeExportDialog(mc)));
        s.add(step(t + "«Только недостающее»: клик ещё раз — строки вернулись", 5_000L, mc -> this.clickOnlyMissing(false)));
        s.add(step(t + "строки вернулись", 8_000L, mc -> this.zeroBack()));
        s.add(step(t + "уборка клеток, размещение снято", 10_000L, mc -> this.placedTidy(mc)));
        s.add(step(t + "неразмещённая: «Библиотека»", 3_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(step(t + "неразмещённая: другая схема", 8_000L, this.k.settleThen(300L, mc -> this.selectOther())));
        s.add(step(t + "неразмещённая: «Материалы»", 5_000L, mc -> this.showOther()));
        s.add(step(t + "неразмещённая: тумблер серый, подсказка почему", 25_000L, mc -> this.unplacedToggle()));
        s.add(step(t + "неразмещённая: клик по серому тумблеру ничего не меняет", 5_000L, mc -> this.unplacedClick()));
        s.add(step(t + "неразмещённая: мышь на тумблер", 3_000L, mc -> this.hoverToggle()));
        s.add(step(t + "снимок: неразмещённая, подсказка тумблера", 5_000L, this.k.settleShot("placed-unplaced-tip")));
        s.add(step(t + "уборка", 5_000L, mc -> this.placedFinish()));
        return s;
    }

    private static @Nullable Object get(@Nullable Object owner, String name)
    {
        for (Class<?> c = owner == null ? null : owner.getClass(); c != null; c = c.getSuperclass())
        {
            try
            {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                // выше по иерархии
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                return null;
            }
        }

        return null;
    }

    private static boolean flag(@Nullable Object owner, String name)
    {
        return get(owner, name) instanceof Boolean b && b;
    }

    private static boolean staticOnlyMissing()
    {
        try
        {
            Class<?> c = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane");
            Field f = c.getDeclaredField("onlyMissing");
            f.setAccessible(true);
            return f.getBoolean(null);
        }
        catch (ReflectiveOperationException e)
        {
            return false;
        }
    }

    private static void setStaticOnlyMissing(boolean value)
    {
        try
        {
            Class<?> c = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane");
            Field f = c.getDeclaredField("onlyMissing");
            f.setAccessible(true);
            f.setBoolean(null, value);
        }
        catch (ReflectiveOperationException e)
        {
            // нечего возвращать
        }
    }

    private boolean paneBusy()
    {
        MaterialsJob job = KProbe.fieldOfType(this.pane(), MaterialsJob.class);
        return job == null || job.running();
    }

    @SuppressWarnings("unchecked")
    private List<MaterialLine> tableLines()
    {
        Table<MaterialLine> table = KProbe.fieldOfType(this.pane(), Table.class);
        return table == null ? List.of() : List.copyOf(table.body().items());
    }

    private @Nullable MaterialLine tableLine(Item item)
    {
        String id = DumpSchematics.id(item);

        for (MaterialLine line : this.tableLines())
        {
            if (line.key().equals(id))
            {
                return line;
            }
        }

        return null;
    }

    private @Nullable Toggle missingToggle()
    {
        return get(this.pane(), "missingOnly") instanceof Toggle toggle ? toggle : null;
    }

    private static @Nullable String tooltipOf(Toggle toggle)
    {
        try
        {
            Field field = Toggle.class.getDeclaredField("tooltip");
            field.setAccessible(true);
            return (String) field.get(toggle);
        }
        catch (ReflectiveOperationException e)
        {
            return "?";
        }
    }

    private static void move(double guiX, double guiY)
    {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        mc.mouseHandler.onMove(window.handle(), guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                guiY * window.getScreenHeight() / window.getGuiScaledHeight(), 0.0, 0.0);
    }

    /** Клетки размещения с блоком схемы, чей предмет — {@code item}: воздух в мире / не тот блок / верный. */
    private record Scan(List<BlockPos> air, int wrong, int right) {}

    private @Nullable Scan scan(Minecraft mc, Item item)
    {
        SchematicPlacement p = this.placedP;
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();
        KPlace.Bounds b = p == null ? null : KPlace.bounds(p);

        if (b == null || sw == null || mc.level == null)
        {
            return null;
        }

        List<BlockPos> air = new ArrayList<>();
        int wrong = 0;
        int right = 0;

        for (int y = b.minY(); y <= b.maxY(); ++y)
        {
            for (int x = b.minX(); x <= b.maxX(); ++x)
            {
                for (int z = b.minZ(); z <= b.maxZ(); ++z)
                {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState want = sw.getBlockState(pos);

                    if (want.isAir() || want.getBlock().asItem() != item)
                    {
                        continue;
                    }

                    BlockState have = mc.level.getBlockState(pos);

                    if (have.isAir())
                    {
                        air.add(pos);
                    }
                    else if (have.is(want.getBlock()))
                    {
                        ++right;
                    }
                    else
                    {
                        ++wrong;
                    }
                }
            }
        }

        return new Scan(air, wrong, right);
    }

    /** Блоки схемы в клетках — серверным {@code setBlock} (состояние берётся у мира схемы). */
    private void setCells(Minecraft mc, List<BlockPos> cells, boolean air)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        WorldSchematic sw = SchematicWorldHandler.getSchematicWorld();

        if (server == null || mc.level == null || sw == null)
        {
            return;
        }

        List<BlockState> states = new ArrayList<>(cells.size());

        for (BlockPos pos : cells)
        {
            states.add(air ? Blocks.AIR.defaultBlockState() : sw.getBlockState(pos));
        }

        List<BlockPos> copy = List.copyOf(cells);
        var dimension = mc.level.dimension();
        server.execute(() ->
        {
            ServerLevel level = server.getLevel(dimension);

            for (int i = 0; i < copy.size() && level != null; ++i)
            {
                level.setBlock(copy.get(i), states.get(i), 3);
            }
        });
    }

    private void recount()
    {
        if (get(this.pane(), "counter") instanceof LayerCountManager counter)
        {
            counter.markDirty();
        }

        LayerCountManager.getInstance().markDirty();
        this.recountAt = Util.getMillis();
    }

    private boolean placePlaced()
    {
        LoadedSchematic loaded = this.generated();

        // Отдельный прогон (LL_K_ONLY=placed): шагов по файлам не было — схема та, что загрузила вкладка «Материалы»
        if (loaded == null && this.k.generated(".litematic") != null
                && KProbe.fieldOfType(this.pane(), fi.dy.masa.litematica.schematic.LitematicaSchematic.class)
                        instanceof fi.dy.masa.litematica.schematic.LitematicaSchematic shown)
        {
            loaded = new LoadedSchematic(this.k.generated(".litematic"), shown);
        }

        // Загрузка после выбора в «Библиотеке» идёт не сразу — ждём (шаг упадёт по своему сроку, если так и не загрузится)
        if (loaded == null)
        {
            return false;
        }

        this.hudBefore = LayerListConfigs.HUD_ENABLED;
        LayerListConfigs.HUD_ENABLED = true;
        this.onlyBefore = staticOnlyMissing();
        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.made.add(p);
        this.placedP = p;
        this.placedCells.clear();
        LayerCountManager.getInstance().markDirty();
        return true;
    }

    private boolean placedReady()
    {
        Minecraft mc = Minecraft.getInstance();
        MaterialLine line = this.tableLine(Items.STONE_BRICKS);
        MaterialListEntry hud = bricks();
        Toggle toggle = this.missingToggle();
        Scan scan = this.scan(mc, Items.STONE_BRICKS);

        if (this.pane() == null || this.paneBusy() || ! flag(this.pane(), "verifiedRows") || line == null || hud == null
                || hud.getCountTotal() <= 0 || toggle == null || scan == null || scan.air().isEmpty())
        {
            return false;
        }

        this.placedTotal = line.need();
        this.placedLack = line.lack();
        this.brickCell = scan.air().getFirst();

        long fileTotal = -1L;

        if (get(this.pane(), "fileRows") instanceof List<?> rows)
        {
            for (Object o : rows)
            {
                if (o instanceof MaterialRow row && row.key().equals(DumpSchematics.id(Items.STONE_BRICKS)))
                {
                    fileTotal = row.total();
                }
            }
        }

        this.k.info("размещённая: каменные кирпичи — таблица всего " + line.need() + ", не хватает " + line.lack()
                + "; файл " + fileTotal + "; HUD всего " + hud.getCountTotal() + ", не хватает " + hud.getCountMissing()
                + "; по миру пустых клеток " + scan.air().size() + ", чужих " + scan.wrong());
        this.k.check("размещённая: строки собраны счётчиком размещений (verified)", true, line.verified());
        this.k.check("размещённая: «Всего» кирпичей = файл", fileTotal, line.need());
        this.k.check("размещённая: «Всего» кирпичей = HUD", (long) hud.getCountTotal(), line.need());
        this.k.check("размещённая: «Не хватает» кирпичей = HUD (слои Litematica все)", (long) hud.getCountMissing(), line.lack());
        this.k.check("размещённая: «Не хватает» кирпичей = пустые клетки размещения"
                + (LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue() ? "" : " + чужие блоки"),
                (long) scan.air().size() + (LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue() ? 0 : scan.wrong()),
                line.lack());
        this.k.check("размещённая: «Только недостающее» доступно", true, toggle.enabled());
        this.k.check("размещённая: подсказка тумблера — про сверку с постройкой", I18n.get(MAT + "only_missing.tip.placed"),
                tooltipOf(toggle));
        return true;
    }

    /** Настоящий клик по тумблеру; {@code want} — каким он должен стать (статическое состояние панели). */
    private boolean clickOnlyMissing(boolean want)
    {
        Toggle toggle = this.missingToggle();

        if (toggle == null || ! toggle.visible() || toggle.w() <= 0)
        {
            return false;
        }

        if (staticOnlyMissing() != want)
        {
            if (want)
            {
                // Состояние таблицы до клика: все строки и среди них строки с нулём
                this.rowsAll = this.tableLines().size();
                this.rowsLack = (int) this.tableLines().stream().filter(line -> line.lack() == 0L).count();
                this.k.check("размещённая: до клика есть строки с нулём (из " + this.rowsAll + ")", true, this.rowsLack > 0);
            }

            KInline.realClick(toggle.x() + toggle.h() / 2.0, toggle.y() + toggle.h() / 2.0);
        }

        return true;
    }

    private boolean toggleOff()
    {
        if (! staticOnlyMissing())
        {
            return true;
        }

        if (! this.clicked)
        {
            this.clicked = true;
            this.clickOnlyMissing(false);
        }

        if (staticOnlyMissing() || this.paneBusy())
        {
            return false;
        }

        this.clicked = false;
        return true;
    }

    private boolean putBrick(Minecraft mc)
    {
        BlockPos cell = this.brickCell;

        if (cell == null || mc.level == null)
        {
            this.k.fail("размещённая: нет пустой клетки кирпичей");
            return true;
        }

        if (! this.sent)
        {
            this.sent = true;
            this.setCells(mc, List.of(cell), false);
            this.placedCells.add(cell);
        }

        if (! mc.level.getBlockState(cell).is(Blocks.STONE_BRICKS))
        {
            return false;
        }

        this.sent = false;
        this.recount();
        return true;
    }

    private fi.dy.masa.malilib.util.@Nullable LayerMode layerModeBefore;
    private int layerSingleBefore;

    /** Litematica «один слой» на нижнем слое размещения (или вернуть прежний режим). */
    private boolean singleLayer(boolean on)
    {
        fi.dy.masa.malilib.util.position.LayerRange range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();

        if (! on)
        {
            if (this.layerModeBefore != null)
            {
                range.setLayerSingle(this.layerSingleBefore);
                range.setLayerMode(this.layerModeBefore);
                this.layerModeBefore = null;
            }

            this.recount();
            return true;
        }

        this.layerModeBefore = range.getLayerMode();
        this.layerSingleBefore = range.getLayerSingle();
        range.setAxis(net.minecraft.core.Direction.Axis.Y);
        range.setLayerMode(fi.dy.masa.malilib.util.LayerMode.SINGLE_LAYER);
        range.setLayerSingle(this.placedP != null ? this.placedP.getOrigin().getY() : 0);
        this.recount();
        return true;
    }

    private boolean placementOn(boolean on)
    {
        if (this.placedP != null)
        {
            this.placedP.setEnabled(on);
        }

        this.recount();
        return true;
    }

    /** «Не хватает» кирпичей в таблице то же, что после поставленного кирпича; таблица сверена с миром. */
    private boolean lackSame(String what)
    {
        MaterialLine line = this.tableLine(Items.STONE_BRICKS);
        Object verified = get(this.pane(), "verifiedRows");

        // Ждём пересчёта: таблица переключается между сверкой и файлом не мгновенно (опрос размещений раз в секунду)
        if (! Boolean.TRUE.equals(verified) || line == null || line.lack() != this.placedLack)
        {
            if (Util.getMillis() - this.recountAt > 2_000L)
            {
                this.recount();
            }

            return false;
        }

        this.k.check("размещённая: " + what + " — таблица сверена с миром", true, true);
        this.k.check("размещённая: " + what + " — «Не хватает» кирпичей", this.placedLack, line.lack());
        return true;
    }

    /** «Только недостающее» у сверенной схемы: заголовок «Осталось», у кирпичей — остаток, порядок — по остатку. */
    private boolean leftColumn()
    {
        Object pane = this.pane();
        com.aell.layerlist.ui.kit.Table<?> table = KProbe.fieldOfType(pane, com.aell.layerlist.ui.kit.Table.class);
        List<?> columns = table == null ? List.of() : KProbe.fieldOfType(table, List.class);
        String left = I18n.get("layerlist.schematics.materials.col.left");
        boolean titled = columns.stream().anyMatch(c -> c instanceof com.aell.layerlist.ui.kit.Table.Column<?> col
                && left.equals(col.title()));
        this.k.check("размещённая: заголовок колонки — «Осталось»", true, titled);
        MaterialLine brick = this.tableLine(Items.STONE_BRICKS);
        this.k.check("размещённая: у кирпичей остаток отличается от всей постройки", true,
                brick != null && brick.lack() < brick.need() && ! brick.lackText().equals(brick.needText()));
        List<MaterialLine> lines = this.tableLines();
        boolean ordered = true;

        for (int i = 1; i < lines.size(); ++i)
        {
            ordered &= lines.get(i - 1).disabled() || lines.get(i).disabled() || lines.get(i - 1).lack() >= lines.get(i).lack();
        }

        this.k.check("размещённая: строки по остатку — больше всего осталось выше", true, ordered);
        return true;
    }

    private boolean brickRecounted()
    {
        MaterialLine line = this.tableLine(Items.STONE_BRICKS);

        if (Util.getMillis() - this.recountAt > 4_000L)
        {
            this.recount();
        }

        if (line == null || line.lack() != this.placedLack - 1L)
        {
            return false;
        }

        this.k.check("размещённая: верный кирпич в мире — «Не хватает» на 1 меньше", this.placedLack - 1L, line.lack());
        this.k.check("размещённая: «Всего» кирпичей не изменилось", this.placedTotal, line.need());
        this.placedLack = line.lack();
        return true;
    }

    /** Строка блока (не кирпичи, не сущность), где пустых клеток ровно «Не хватает»: её можно достроить целиком. */
    private boolean pickFlower(Minecraft mc)
    {
        this.flowerItem = null;
        this.rowsLack = 0;

        for (MaterialLine line : this.tableLines())
        {
            if (! line.disabled() && line.lack() == 0L)
            {
                ++this.rowsLack;
            }
        }

        MaterialLine best = null;
        Scan bestScan = null;

        for (MaterialLine line : this.tableLines())
        {
            if (line.disabled() || line.row().entity() || line.lack() < 1L || line.lack() > 6L
                    || line.stack().is(Items.STONE_BRICKS) || ! (line.stack().getItem() instanceof BlockItem))
            {
                continue;
            }

            Scan scan = this.scan(mc, line.stack().getItem());

            if (scan != null && scan.air().size() == line.lack() && (best == null || line.lack() < best.lack()))
            {
                best = line;
                bestScan = scan;
            }
        }

        if (best != null)
        {
            this.flowerItem = best.stack().getItem();
            this.placedCells.addAll(bestScan.air());
            this.k.info("размещённая: достраиваем строку «" + best.name() + "» (" + best.lack() + " клеток)");
            this.setCells(mc, bestScan.air(), false);
            return true;
        }

        this.k.check("размещённая: есть строка с нулём не хватает или такая, что достраивается", true, this.rowsLack > 0);
        return true;
    }

    private boolean buildFlower(Minecraft mc)
    {
        if (this.flowerItem == null)
        {
            return true;
        }

        Scan scan = this.scan(mc, this.flowerItem);

        if (scan == null || ! scan.air().isEmpty())
        {
            return false;
        }

        this.recount();
        return true;
    }

    private boolean flowerZero()
    {
        if (this.flowerItem == null)
        {
            return true;
        }

        MaterialLine line = this.tableLine(this.flowerItem);

        if (Util.getMillis() - this.recountAt > 4_000L)
        {
            this.recount();
        }

        if (line == null || line.lack() != 0L)
        {
            return false;
        }

        this.k.check("размещённая: строка «" + line.name() + "» достроена — «Не хватает» 0, строка в таблице есть", 0L, line.lack());
        return true;
    }

    private boolean zeroGone()
    {
        if (! staticOnlyMissing() || this.paneBusy())
        {
            return false;
        }

        List<MaterialLine> lines = this.tableLines();

        if (lines.size() != this.rowsAll - this.rowsLack)
        {
            return false;
        }

        this.k.check("размещённая: «Только недостающее» — ушли строки с нулём (было " + this.rowsAll + ", нулевых " + this.rowsLack + ")",
                this.rowsAll - this.rowsLack, lines.size());
        this.k.check("размещённая: в таблице только строки с «Не хватает» > 0", true, lines.stream().allMatch(line -> line.lack() > 0L));
        return true;
    }

    private boolean zeroBack()
    {
        if (staticOnlyMissing() || this.paneBusy() || this.tableLines().size() != this.rowsAll)
        {
            return false;
        }

        this.k.check("размещённая: тумблер выключен — все строки вернулись", this.rowsAll, this.tableLines().size());
        return true;
    }

    /** Подвал «Материалов» → окно выгрузки при включённом «Только недостающее» (тумблер уже включён предыдущим шагом). */
    private boolean exportFromPane()
    {
        if (! this.sent)
        {
            Button pick = null;

            if (get(this.pane(), "exportButtons") instanceof java.util.Map<?, ?> buttons)
            {
                for (Object o : buttons.values())
                {
                    if (pick == null && o instanceof Button b && b.visible() && b.w() > 0 && b.enabled())
                    {
                        pick = b;
                    }
                }
            }

            if (pick == null)
            {
                this.k.fail("размещённая: кнопки выгрузки в подвале нет или она серая");
                return true;
            }

            this.sent = true;
            KInline.realClick(pick.x() + pick.w() / 2.0, pick.y() + pick.h() / 2.0);
            return false;
        }

        ExportWindow window = this.window();
        ExportSource source = window == null ? null : KProbe.fieldOfType(window, ExportSource.class);

        if (source == null || ! (get(source, "rows") instanceof CompletableFuture<?> future) || ! future.isDone())
        {
            return false;
        }

        this.sent = false;
        List<MaterialLine> lines = this.tableLines();
        List<MaterialRow> rows = new ArrayList<>();

        for (Object o : (List<?>) future.join())
        {
            if (o instanceof MaterialRow row)
            {
                rows.add(row);
            }
        }

        this.k.check("размещённая: окно выгрузки — источник «размещённая схема» (PLACED)", ExportSource.Kind.PLACED, source.kind());
        String about = about(window);
        String expected = I18n.get(DIALOG + "filter.about.placed.missing");
        this.k.info("размещённая: пояснение окна «" + about + "»");
        this.k.check("размещённая: пояснение — «только то, чего нет на месте постройки»",
                about != null && about.startsWith(expected), true);
        this.k.check("размещённая: строк в окне = строк таблицы", lines.size(), rows.size());

        long tableSum = lines.stream().mapToLong(MaterialLine::lack).sum();
        long windowSum = rows.stream().mapToLong(MaterialRow::missing).sum();
        this.k.check("размещённая: сумма «не хватает» окна = таблицы", tableSum, windowSum);

        MaterialLine bricks = this.tableLine(Items.STONE_BRICKS);
        this.k.check("размещённая: выгрузка JSON «Только недостающее» — кирпичей = «Не хватает» таблицы",
                bricks == null ? -1L : bricks.lack(), jsonQuantity(rows, "minecraft:stone_bricks"));
        return true;
    }

    private static long jsonQuantity(List<MaterialRow> rows, String id)
    {
        ExportRequest request = new ExportRequest("k-placed", "placed", rows, List.of(),
                new ExportOptions(true, false, false, false), "k-placed-missing");
        ExportResult result = Exporters.json(request);

        if (! result.ok() || result.files().isEmpty())
        {
            return -1L;
        }

        try
        {
            Path file = result.files().getFirst();
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Files.deleteIfExists(file);

            for (JsonElement item : root.getAsJsonArray("items"))
            {
                JsonObject o = item.getAsJsonObject();

                if (id.equals(o.get("id").getAsString()))
                {
                    return o.get("quantity").getAsLong();
                }
            }

            return 0L;
        }
        catch (IOException | RuntimeException e)
        {
            return -2L;
        }
    }

    private boolean placedTidy(Minecraft mc)
    {
        this.setCells(mc, List.copyOf(this.placedCells), true);
        this.placedCells.clear();

        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.placedP = null;
        LayerCountManager.getInstance().markDirty();
        return true;
    }

    private boolean selectOther()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        for (SchematicEntry entry : this.k.listedEntries())
        {
            if (generated != null && entry.path().equals(generated.path()))
            {
                continue;
            }

            boolean placed = false;

            for (SchematicPlacement p : PlacementOps.all())
            {
                placed |= p.getSchematicFile() != null
                        && p.getSchematicFile().toAbsolutePath().normalize().equals(entry.path().toAbsolutePath().normalize());
            }

            if (! placed)
            {
                this.k.info("неразмещённая: берём " + entry.fileName());
                this.otherEntry = entry;
                return this.k.selectEntry(entry);
            }
        }

        this.k.fail("неразмещённая: в библиотеке нет другой схемы без размещения");
        return true;
    }

    private boolean unplacedToggle()
    {
        Toggle toggle = this.missingToggle();
        List<MaterialLine> lines = this.tableLines();

        if (this.pane() == null || this.paneBusy() || ! flag(this.pane(), "unplaced") || toggle == null || lines.isEmpty())
        {
            return false;
        }

        this.rowsUnplaced = lines.size();
        this.k.check("неразмещённая: сверки нет — строки из файла", false, flag(this.pane(), "verifiedRows"));
        this.k.check("неразмещённая: тумблер «Только недостающее» серый", false, toggle.enabled());
        this.k.check("неразмещённая: подсказка — схема не размещена", I18n.get(MAT + "only_missing.unplaced"), tooltipOf(toggle));
        return true;
    }

    private boolean unplacedClick()
    {
        Toggle toggle = this.missingToggle();

        if (toggle == null || ! toggle.visible() || toggle.w() <= 0)
        {
            return false;
        }

        boolean before = staticOnlyMissing();
        KInline.realClick(toggle.x() + toggle.h() / 2.0, toggle.y() + toggle.h() / 2.0);
        this.k.check("неразмещённая: клик по серому тумблеру — состояние то же", before, staticOnlyMissing());
        this.k.check("неразмещённая: строки те же", this.rowsUnplaced, this.tableLines().size());
        return true;
    }

    private boolean hoverToggle()
    {
        Toggle toggle = this.missingToggle();

        if (toggle == null || ! toggle.visible() || toggle.w() <= 0)
        {
            return false;
        }

        move(toggle.x() + toggle.w() / 2.0, toggle.y() + toggle.h() / 2.0);
        return true;
    }

    private boolean placedFinish()
    {
        if (this.onlyBefore != null)
        {
            setStaticOnlyMissing(this.onlyBefore);
            this.onlyBefore = null;
        }

        this.restoreSettings();
        // Следующие шаги (замена) ждут таблицу на k-generated — вернуть, как было до шагов размещённой
        this.showGenerated();
        this.k.setTab("LIBRARY");
        this.k.check("размещённая: своё размещение убрано", false, this.placedP != null && PlacementOps.all().contains(this.placedP));
        return true;
    }

    private @Nullable SchematicEntry otherEntry;

    /** «Материалы» на неразмещённой схеме — явно, как кликом по её карточке (таблица могла стоять на «Списке HUD»). */
    private boolean showOther()
    {
        Object gui = this.k.gui();

        if (this.otherEntry == null || gui == null)
        {
            return this.otherEntry == null;
        }

        try
        {
            KProbe.call(gui, "showMaterials", this.otherEntry, null);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail("неразмещённая: «Материалы»: " + e);
        }

        return true;
    }

    /** «Материалы» на файле k-generated — тем же вызовом, что карточка «Библиотеки» ({@code GuiSchematics.showMaterials}). */
    private boolean showGenerated()
    {
        SchematicEntry entry = this.k.generated(".litematic");
        Object gui = this.k.gui();

        if (entry == null || gui == null)
        {
            return false;
        }

        try
        {
            KProbe.call(gui, "showMaterials", entry, null);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail("размещённая: «Материалы» на k-generated: " + e);
        }

        return true;
    }

    // ===== «Схема» без выбора в «Библиотеке» =====

    private @Nullable Object pane()
    {
        return KProbe.fieldNamedType(this.k.gui(), "MaterialsPane");
    }

    private @Nullable Button schematicButton()
    {
        Object pane = this.pane();

        try
        {
            Field field = pane == null ? null : pane.getClass().getDeclaredField("schematicButton");

            if (field != null)
            {
                field.setAccessible(true);
                return field.get(pane) instanceof Button button ? button : null;
            }
        }
        catch (ReflectiveOperationException e)
        {
            this.k.info("кнопка «Схема» не читается: " + e);
        }

        return null;
    }

    private static @Nullable String tooltipOf(Button button)
    {
        try
        {
            Field field = Button.class.getDeclaredField("tooltip");
            field.setAccessible(true);
            return (String) field.get(button);
        }
        catch (ReflectiveOperationException e)
        {
            return "?";
        }
    }

    private boolean clearWorld()
    {
        for (SchematicPlacement p : PlacementOps.all())
        {
            PlacementOps.remove(p);
        }

        // «Загруженные схемы» Litematica тоже считаются — для «нет ничего» убираем и их (размещения вернут их сами)
        SchematicHolder.getInstance().clearLoadedSchematics();
        this.k.check("«Схема»: размещений нет", 0, PlacementOps.all().size());
        this.k.check("«Схема»: загруженных схем нет", 0, SchematicHolder.getInstance().getAllSchematics().size());
        return true;
    }

    /** Экран заново: у новой вкладки «Материалы» нет ни прошлой схемы, ни выбора в «Библиотеке». */
    private boolean reopen()
    {
        if (! this.reopened)
        {
            this.reopened = true;
            Nav.clear();
            GuiBase.openGui(null);
            GuiSchematics.open(null);
            return false;
        }

        GuiSchematics gui = this.k.gui();

        if (gui == null)
        {
            return false;
        }

        this.reopened = false;

        try
        {
            KProbe.call(gui, "select", (Object) null);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("select(null): " + e);
        }

        try
        {
            this.k.check("экран открыт заново, в «Библиотеке» ничего не выбрано", true, KProbe.call(gui, "selected") == null);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("selected(): " + e);
        }

        return true;
    }

    private boolean nothing()
    {
        Button button = this.schematicButton();

        if (button == null)
        {
            this.k.fail("«Схема»: кнопки нет");
            return true;
        }

        this.k.check("«Схема»: схем нет — серая", false, button.enabled());
        this.k.check("«Схема»: подсказка — почему", I18n.get("layerlist.schematics.materials.source.pick.tip"), tooltipOf(button));
        return true;
    }

    private boolean placeOne()
    {
        LoadedSchematic loaded = this.generated();

        if (loaded == null)
        {
            this.k.fail("«Схема»: k-generated не загружена");
            return true;
        }

        SchematicPlacement p = PlacementOps.placeInFront(loaded);
        this.made.add(p);
        this.first = p;
        // placeInFront выбирает размещение в Litematica — снимаем выбор: проверяем «единственное», а не «выбранное»
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(null);
        this.k.check("«Схема»: в Litematica ничего не выбрано", true,
                DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement() == null);
        return true;
    }

    private boolean one()
    {
        Button button = this.schematicButton();
        GuiSchematics gui = this.k.gui();
        SchematicPlacement p = this.first;

        if (button == null || gui == null || p == null)
        {
            this.k.fail("«Схема»: нет кнопки, экрана или размещения");
            return true;
        }

        this.k.check("«Схема»: одно размещение — кнопка живая", true, button.enabled());
        this.k.check("«Схема»: подпись — схема размещения",
                I18n.get("layerlist.schematics.materials.source.schematic", p.getName()), button.label());
        this.k.clickButton(gui, button, null);

        try
        {
            this.k.check("«Схема»: клик показал размещение", true, KProbe.call(this.pane(), "placement") == p);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("placement(): " + e);
        }

        return true;
    }

    private boolean placeSecond()
    {
        if (this.loadingSecond == null)
        {
            Path copy = this.k.outDir().resolve("naming-src").resolve("k-second.litematic");
            SchematicEntry generated = this.k.generated(".litematic");

            try
            {
                if (! Files.exists(copy) && generated != null)
                {
                    Files.createDirectories(copy.getParent());
                    Files.copy(generated.path(), copy);
                }

                this.loadingSecond = SchematicLibrary.get().load(new SchematicEntry(copy, false, SchematicFormat.LITEMATIC,
                        Files.size(copy), Files.getLastModifiedTime(copy).toMillis()));
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail("вторая схема: " + e);
                return true;
            }
        }

        if (! this.loadingSecond.isDone())
        {
            return false;
        }

        try
        {
            SchematicPlacement p = PlacementOps.placeInFront(this.loadingSecond.join());
            p.setName(SECOND_NAME);
            this.made.add(p);
            this.second = p;
            DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(null);
        }
        catch (RuntimeException e)
        {
            this.k.fail("вторая схема не загрузилась: " + e);
        }

        return true;
    }

    private boolean pickerOpens()
    {
        Button button = this.schematicButton();
        GuiSchematics gui = this.k.gui();
        Layers layers = KProbe.fieldOfType(gui, Layers.class);

        if (button == null || gui == null || layers == null)
        {
            this.k.fail("«Схема»: нет кнопки или экрана");
            return true;
        }

        this.k.check("«Схема»: две схемы — кнопка живая", true, button.enabled());
        this.k.check("«Схема»: подпись «Схема» (выбрать)", I18n.get("layerlist.schematics.materials.source.pick"), button.label());
        this.k.check("«Схема»: подсказка — выберите", I18n.get("layerlist.schematics.materials.source.pick.choose.tip"),
                tooltipOf(button));
        this.k.clickButton(gui, button, null);
        this.k.check("«Схема ▾» открыла выбор", true, layers.top() instanceof ContextMenu);
        return true;
    }

    private boolean pickSecond()
    {
        GuiSchematics gui = this.k.gui();

        if (gui == null)
        {
            this.k.fail("выбор: экрана нет");
            return true;
        }

        KProbe.press(gui, InputConstants.KEY_DOWN);
        KProbe.press(gui, InputConstants.KEY_DOWN);
        KProbe.press(gui, InputConstants.KEY_RETURN);

        try
        {
            this.k.check("выбор: вторая схема в «Материалах»", true,
                    this.second != null && KProbe.call(this.pane(), "placement") == this.second);
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail("placement(): " + e);
        }

        return true;
    }

    private boolean reopenWithSelected()
    {
        if (! this.reopened && this.second != null)
        {
            PlacementOps.select(this.second);
        }

        return this.reopen();
    }

    private boolean selectedWins()
    {
        Button button = this.schematicButton();
        SchematicPlacement p = this.second;

        if (button == null || p == null)
        {
            this.k.fail("«Схема»: нет кнопки или размещения");
            return true;
        }

        this.k.check("«Схема»: выбранное в Litematica — без выбора из списка",
                I18n.get("layerlist.schematics.materials.source.schematic", SECOND_NAME), button.label());
        this.k.check("«Схема»: кнопка живая", true, button.enabled());
        return true;
    }

    private boolean choiceTidy()
    {
        for (SchematicPlacement p : this.made)
        {
            if (PlacementOps.all().contains(p))
            {
                PlacementOps.remove(p);
            }
        }

        this.made.clear();
        this.first = null;
        this.second = null;
        this.restoreSettings();
        this.k.setTab("LIBRARY");
        this.k.check("«Схема»: размещения убраны", 0, PlacementOps.all().size());
        return true;
    }
}
