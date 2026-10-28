package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.export.ExportOptions;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.export.ExportResult;
import com.aell.layerlist.schematics.export.Exporters;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Режим {@code p}: палитры таблицы (1.0.1). Выгружает одну и ту же таблицу сбора в Excel и Google со
 * светлой серой палитрой и в Google с тёмной фиолетовой; каждую проверяет {@link KExports#xlsx} и по
 * заливкам в {@code xl/styles.xml}. Потом — через окно выгрузки: Excel открывает «Оформление
 * таблицы», клик по карточке меняет палитру, Enter выгружает в ней; плюс снимки настроек.
 */
@Environment(EnvType.CLIENT)
public final class PaletteTest implements CovJob
{
    private static final String LIGHT_CANVAS = "FFF2F2F4";
    private static final String LIGHT_HEAD = "FF3A3A43";
    private static final String DARK_CANVAS = "FF1F063F";

    private boolean failed;

    @Override
    public String name()
    {
        return "P (палитры таблицы)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        return true;
    }

    private int stage;
    private long nextAt;

    @Override
    public boolean step(Minecraft mc)
    {
        long now = net.minecraft.util.Util.getMillis();

        if (now < this.nextAt)
        {
            return false;
        }

        this.nextAt = now + 1500L;

        switch (this.stage++)
        {
            case 0 -> this.exports();
            case 1 ->
            {
                this.check("настройки на весь экран — по умолчанию",
                        com.aell.layerlist.config.LayerListConfigs.SETTINGS_FULLSCREEN.getDefaultBooleanValue());
                this.check("панель у контейнера справа — по умолчанию",
                        com.aell.layerlist.config.LayerListConfigs.HUD_DOCK_RIGHT.getDefaultBooleanValue());
                com.aell.layerlist.config.LayerListConfigs.SETTINGS_FULLSCREEN.setBooleanValue(true);
                mc.gui.setScreen(com.aell.layerlist.config.GuiConfigs.create(null, null).showTab(0));
            }
            case 2 -> this.click(mc.getWindow().getGuiScaledWidth() - 18, 13);
            case 3 -> this.chars("dock");
            case 4 -> net.minecraft.client.Screenshot.grab(mc, false);
            case 5 -> mc.gui.setScreen(com.aell.layerlist.schematics.gui.GuiSchematics.create());
            case 6 ->
            {
                try
                {
                    var dialog = Class.forName("com.aell.layerlist.schematics.gui.SettingsDialog")
                            .getDeclaredMethod("open", com.aell.layerlist.schematics.gui.GuiSchematics.class);
                    dialog.setAccessible(true);
                    dialog.invoke(null, fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen());
                }
                catch (ReflectiveOperationException | ClassCastException e)
                {
                    this.check("окно настроек «Схем» открылось: " + e, false);
                }
            }
            case 7 -> net.minecraft.client.Screenshot.grab(mc, false);
            case 8 -> this.openExportWindow();
            case 9 ->
            {
                // Enter — кнопка по умолчанию (Excel): вместо выгрузки открывается «Оформление таблицы»
                KProbe.press(this.gui, com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
                var top = this.layers.top();
                this.check("Excel открыл окно оформления (а не выгрузку)",
                        top instanceof com.aell.layerlist.ui.kit.Modal modal && top != this.window
                                && net.minecraft.client.resources.language.I18n.get(
                                        "layerlist.schematics.export.dialog.palette.title").equals(KTrash.modalTitle(modal)));
            }
            case 10 ->
            {
                com.aell.layerlist.ui.kit.Widget cards = this.cards();
                this.check("карточки палитр в окне оформления", cards != null);

                if (cards != null)
                {
                    KProbe.click(this.gui, cards.x() + 12, cards.y() + 12);
                }

                this.check("клик по первой карточке выбрал светлую серую",
                        SchematicsConfig.sheetsPalette() == SchematicsConfig.SheetsPalette.LIGHT_GRAY);
            }
            case 11 -> net.minecraft.client.Screenshot.grab(mc, false);
            case 12 ->
            {
                this.exportsBefore = this.newestExport();
                KProbe.press(this.gui, com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
                this.nextAt = now + 5000L;
            }
            case 13 ->
            {
                this.check("окно оформления закрылось, окно выгрузки на месте", this.layers.top() == this.window);
                Path file = this.newestExport();
                this.check("выгружен новый файл", file != null && ! file.equals(this.exportsBefore));

                if (file != null)
                {
                    String styles = entry(file, "xl/styles.xml");
                    this.check("Excel из окна — светлая серая: " + file.getFileName(),
                            styles.contains(LIGHT_CANVAS) && ! styles.contains(DARK_CANVAS));
                }

                net.minecraft.client.Screenshot.grab(mc, false);
            }
            case 14 ->
            {
                // Панели нужна схема: «Портал» рядом с игроком, как в туре экранов
                mc.gui.setScreen(null);
                var path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");
                var schematic = fi.dy.masa.litematica.data.SchematicHolder.getInstance().getOrLoad(path);

                if (schematic != null)
                {
                    var placement = fi.dy.masa.litematica.schematic.placement.SchematicPlacement.createFor(schematic,
                            mc.player.blockPosition().offset(4, 0, 4), "palette-tooltip", true, true);
                    fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
                    fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
                }

                com.aell.layerlist.config.LayerListConfigs.HUD_ENABLED = true;
                this.nextAt = now + 4000L;
            }
            case 15 ->
            {
                if (! com.aell.layerlist.core.LayerCountManager.getInstance().hasData() && now < this.dataDeadline())
                {
                    --this.stage;
                    this.nextAt = now + 500L;
                    return false;
                }

                // Сундук с предметами, курсор на правом верхнем слоте: подсказка тянется вправо, на панель
                var inventory = mc.player.getInventory();
                var chest = new net.minecraft.world.SimpleContainer(54);

                for (int i = 0; i < 54; ++i)
                {
                    chest.setItem(i, new ItemStack(i % 2 == 0 ? Items.DIAMOND_BLOCK : Items.TUFF, 64));
                }

                mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.ContainerScreen(
                        net.minecraft.world.inventory.ChestMenu.sixRows(0, inventory, chest), inventory,
                        net.minecraft.network.chat.Component.literal("Сундук")));
            }
            case 16 ->
            {
                if (fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen()
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)
                {
                    var access = (com.aell.layerlist.mixin.IMixinAbstractContainerScreen) screen;
                    var slot = screen.getMenu().slots.get(8);
                    double gx = access.layerlist_getLeftPos() + slot.x + 8;
                    double gy = access.layerlist_getTopPos() + slot.y + 8;
                    double k = mc.getWindow().getGuiScale();

                    try
                    {
                        var x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                        var y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                        x.setAccessible(true);
                        y.setAccessible(true);
                        x.setDouble(mc.mouseHandler, gx * k);
                        y.setDouble(mc.mouseHandler, gy * k);
                    }
                    catch (ReflectiveOperationException e)
                    {
                        this.check("курсор не поставился: " + e, false);
                    }
                }
            }
            case 17 ->
            {
                CovOut.log("[palette]   снимок: подсказка над панелью у сундука");
                net.minecraft.client.Screenshot.grab(mc, false);
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log("[palette] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        return false;
    }

    private com.aell.layerlist.schematics.gui.GuiSchematics gui;
    private com.aell.layerlist.ui.kit.Layers layers;
    private Object window;
    private Path exportsBefore;

    private void openExportWindow()
    {
        if (! (fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof com.aell.layerlist.schematics.gui.GuiSchematics screen))
        {
            this.check("экран «Схемы» открыт", false);
            return;
        }

        this.gui = screen;
        this.layers = KProbe.fieldOfType(screen, com.aell.layerlist.ui.kit.Layers.class);
        var toasts = KProbe.fieldOfType(screen, com.aell.layerlist.ui.kit.Toasts.class);

        for (int i = 0; i < 3 && this.layers.any(); ++i)
        {
            KProbe.press(screen, com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
        }

        // Начинаем с тёмной: окно оформления должно позволить сменить её на светлую
        SchematicsConfig.SHEETS_PALETTE.setOptionListValue(SchematicsConfig.SheetsPalette.DARK_VIOLET);
        List<MaterialRow> rows = List.of(row(Items.STONE, 2000), row(Items.OAK_PLANKS, 640), row(Items.GLASS, 77));
        this.window = com.aell.layerlist.schematics.gui.export.ExportWindow.open(
                new com.aell.layerlist.schematics.gui.export.ExportWindow.Host(this.layers, toasts, () -> true),
                "Палитра", "Палитра-окно",
                List.of(com.aell.layerlist.schematics.gui.export.ExportSource.rows("Палитра", rows)), 0,
                com.aell.layerlist.schematics.gui.export.ExportFormat.XLSX);
        this.check("окно выгрузки открыто", this.layers.top() == this.window);
    }

    /** Виджет карточек в верхнем окне: строки окна — поле {@code rows}, у строки — {@code widget}. */
    private com.aell.layerlist.ui.kit.Widget cards()
    {
        try
        {
            List<?> rows = KTrash.field(this.layers.top(), "rows");

            for (Object row : rows)
            {
                Object widget = KTrash.field(row, "widget");

                if (widget != null && widget.getClass().getSimpleName().equals("Cards"))
                {
                    return (com.aell.layerlist.ui.kit.Widget) widget;
                }
            }
        }
        catch (RuntimeException e)
        {
            CovOut.log("[palette]   карточки не найдены: " + e);
        }

        return null;
    }

    private Path newestExport()
    {
        try (var files = java.nio.file.Files.list(SchematicsConfig.exportDir()))
        {
            // Файл называется по схеме («Палитра (N).xlsx»), а не по полю названия
            return files.filter(f -> f.getFileName().toString().endsWith(".xlsx"))
                    .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private long dataUntil;

    private long dataDeadline()
    {
        if (this.dataUntil == 0L)
        {
            this.dataUntil = net.minecraft.util.Util.getMillis() + 40_000L;
        }

        return this.dataUntil;
    }

    private void click(int x, int y)
    {
        if (fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof fi.dy.masa.malilib.gui.GuiBase gui)
        {
            gui.onMouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
        }
    }

    private void chars(String text)
    {
        if (fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen() instanceof fi.dy.masa.malilib.gui.GuiBase gui)
        {
            for (int i = 0; i < text.length(); ++i)
            {
                gui.onCharTyped(new net.minecraft.client.input.CharacterEvent(text.charAt(i)));
            }
        }
    }

    private void exports()
    {
        List<MaterialRow> rows = List.of(row(Items.STONE, 2000), row(Items.OAK_PLANKS, 640), row(Items.GLASS, 77),
                row(Items.REDSTONE, 300), row(Items.SPRUCE_LOG, 129));
        ExportRequest request = new ExportRequest("Палитра", "Схема: палитра", rows, List.of("Aell_", "Vi"),
                ExportOptions.DEFAULT);
        SchematicsConfig.SheetsPalette before = SchematicsConfig.sheetsPalette();

        try
        {
            this.check("палитра по умолчанию — светлая серая",
                    SchematicsConfig.SHEETS_PALETTE.getDefaultOptionListValue() == SchematicsConfig.SheetsPalette.LIGHT_GRAY);
            // Excel — в той же палитре, что Google (с 1.0.1 оформление выбирается перед выгрузкой таблицы)
            SchematicsConfig.SHEETS_PALETTE.setOptionListValue(SchematicsConfig.SheetsPalette.LIGHT_GRAY);
            this.table("Excel, светлая серая", Exporters.xlsx(request), LIGHT_CANVAS + " " + LIGHT_HEAD, DARK_CANVAS);
            this.table("Google, светлая серая", Exporters.google(request), LIGHT_CANVAS + " " + LIGHT_HEAD, DARK_CANVAS);

            SchematicsConfig.SHEETS_PALETTE.setOptionListValue(SchematicsConfig.SheetsPalette.DARK_VIOLET);
            this.table("Google, тёмная фиолетовая", Exporters.google(request), DARK_CANVAS, LIGHT_CANVAS);
        }
        finally
        {
            SchematicsConfig.SHEETS_PALETTE.setOptionListValue(before);
        }
    }

    private static MaterialRow row(Item item, long count)
    {
        ItemStack stack = new ItemStack(item);
        return new MaterialRow(stack, item.toString(), stack.getHoverName().getString(), count, count, 0, 0,
                stack.getMaxStackSize(), false);
    }

    /** Файл проходит общую проверку xlsx, в стилях есть все {@code must} и нет {@code mustNot}. */
    private void table(String what, ExportResult result, String must, String mustNot)
    {
        if (! result.ok() || result.files().isEmpty())
        {
            this.check(what + ": выгрузка", false);
            CovOut.log("[palette]   ошибка: " + result.error());
            return;
        }

        Path file = result.files().getFirst();
        String problem = KExports.xlsx(file);
        this.check(what + ": файл " + file.getFileName() + (problem == null ? "" : " — " + problem), problem == null);

        String styles = entry(file, "xl/styles.xml");

        for (String rgb : must.split(" "))
        {
            this.check(what + ": в стилях есть " + rgb, styles.contains(rgb));
        }

        this.check(what + ": в стилях нет " + mustNot, ! styles.contains(mustNot));
        CovOut.log("[palette]   " + what + " → " + file);
    }

    private static String entry(Path file, String name)
    {
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            ZipEntry entry = zip.getEntry(name);
            return entry == null ? "" : new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            return "";
        }
    }

    private void check(String what, boolean ok)
    {
        CovOut.log("[palette] " + (ok ? "OK   " : "FAIL ") + what);
        this.failed |= ! ok;
    }

    @Override
    public void close() {}
}
