package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.SchematicEntities;
import com.aell.layerlist.schematics.gui.materials.BlockPicker;
import com.aell.layerlist.schematics.gui.materials.MaterialFilter;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.materials.MaterialsJob;
import com.aell.layerlist.schematics.gui.materials.ReplaceCell;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicFormat;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.ReplacementStates;
import com.aell.layerlist.schematics.place.PlacementOps;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Toasts;
import com.aell.layerlist.ui.kit.Toggle;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.ILitematicaBlockStatePalette;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Util;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «k», 0.0.27 — замена блоков схемы прямо из таблицы «Материалы» (просьба игрока: «по нажатию на
 * блок» и откат «как в настройках клавиш»). Всё — настоящими событиями: клики мышью через
 * {@code MouseHandler.onMove/onButton}, печать — нажатие клавиши, событие текста SDL, отпускание
 * ({@code KeyboardHandler}), с проверкой, что ввод текста включён для поля поиска выбора.
 *
 * <p>Проверяется: клик по строке открывает выбор, поиск по названию языка игры и по id; замена дубовых
 * досок на еловые в k-generated — во всех тех клетках контейнера новый блок, в прочих без изменений;
 * строка «Еловые доски ← Дубовые доски»; голограмма размещения (мир схемы Litematica) с новым блоком;
 * ступеньки сохраняют facing/half; откат — побайтно (палитра и упакованный массив клеток); A → B при
 * уже существующих B — откат не трогает исходные B; не блочные строки и «Список HUD» выбор не
 * открывают; Litematica загрузила схему заново ({@code SchematicHolder.getOrLoad}) — замены на месте, и
 * после «перезапуска» хранилища тоже; файл «потрогали» — замены остаются, файл другой — молча сброшены;
 * большая схема игрока — время замены и паузы кадров.
 */
@Environment(EnvType.CLIENT)
final class KReplace
{
    private static final String T = "замена: ";
    private static final long REBUILD_MS = 20_000L;
    private static final long ROWS_MS = 40_000L;
    private static final long BIG_MS = 300_000L;
    /** Дольше этого вызов замены в большой схеме — провал: палитра, а не клетки, должна быть мгновенной. */
    private static final long BIG_REPLACE_MAX_MS = 1_000L;
    private static final int SCANCODE_MASK = 1 << 30;
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбю";
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";

    private final DumpSchematics k;

    /** k-generated — тот объект Litematica, с которым работает таблица. */
    private @Nullable LitematicaSchematic schematic;
    private @Nullable Path file;
    /** Исходные клетки, палитры и упакованные массивы регионов k-generated (до первой замены). */
    private final Map<String, BlockState[]> cellsBefore = new LinkedHashMap<>();
    private final Map<String, List<BlockState>> palettesBefore = new LinkedHashMap<>();
    private final Map<String, long[]> arraysBefore = new LinkedHashMap<>();
    private @Nullable SchematicPlacement placement;
    private @Nullable Boolean hudBefore;
    private final List<LitematicaSchematic> fresh = new ArrayList<>();
    private @Nullable LitematicaSchematic reloaded;

    private @Nullable Path copy;
    private @Nullable CompletableFuture<LoadedSchematic> copyLoading;
    private @Nullable LitematicaSchematic copySchematic;

    private boolean bigSearched;
    private @Nullable SchematicEntry bigEntry;
    private @Nullable CompletableFuture<LoadedSchematic> bigLoading;
    private @Nullable LitematicaSchematic big;
    private @Nullable Item bigItem;
    private @Nullable Block bigTarget;
    private long bigCountFrom;

    KReplace(DumpSchematics k)
    {
        this.k = k;
    }

    private static DumpSchematics.Step step(String name, long timeoutMs, DumpSchematics.Action action)
    {
        return new DumpSchematics.Step(name, timeoutMs, action);
    }

    // ===== Сценарий =====

    /** Шаги при текущем масштабе GUI; {@code p} — префикс снимков и строк («s3-» — GUI 3, короче). */
    List<DumpSchematics.Step> steps(String p)
    {
        boolean full = p.isEmpty();
        List<DumpSchematics.Step> s = new ArrayList<>();

        s.add(step(p + T + "«Библиотека»", 2_000L, mc -> this.k.setTab("LIBRARY")));
        s.add(step(p + T + "k-generated", 5_000L, mc -> this.k.selectGenerated()));
        s.add(step(p + T + "вкладка «Материалы»", 2_000L, mc -> this.k.setTab("MATERIALS")));
        s.add(step(p + T + "строки k-generated", ROWS_MS, mc -> this.ready(p)));

        if (full)
        {
            s.add(step(T + "размещение k-generated в мире", 5_000L, mc -> this.place()));
            s.add(step(T + "голограмма: дубовые доски на месте", REBUILD_MS,
                    mc -> this.world(Blocks.OAK_PLANKS, this.originalCount(Blocks.OAK_PLANKS), Blocks.SPRUCE_PLANKS, 0)));
            // Схема теперь размещена — таблица переходит на сверку с миром (1.0.18): ждём её строк, дальше клики по ним
            s.add(step(T + "таблица сверена с миром", ROWS_MS, mc -> this.verifiedReady()));
        }

        // Дубовые доски → еловые: клик по названию, поиск названием языка игры, Enter
        this.pick(s, p, Items.OAK_PLANKS, ReplacementStates.name(Blocks.SPRUCE_PLANKS), "minecraft:spruce_planks", true);
        s.add(step(p + T + "Enter — заменить", 3_000L, mc -> this.enter()));
        s.add(step(p + T + "выбор закрыт", 3_000L, mc -> this.k.check(p + T + "Enter закрыл выбор", this.picker() == null)));
        s.add(step(p + T + "в контейнере доски еловые, прочее без изменений", 3_000L,
                mc -> this.expect(p + "доски", Map.of(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS), true)));
        s.add(step(p + T + "строка «еловые ← дубовые»", ROWS_MS,
                mc -> this.replacedRow(p, Items.SPRUCE_PLANKS, List.of(Blocks.OAK_PLANKS), Items.OAK_PLANKS)));

        if (full)
        {
            s.add(step(T + "голограмма: еловые доски", REBUILD_MS,
                    mc -> this.world(Blocks.SPRUCE_PLANKS, this.originalCount(Blocks.OAK_PLANKS), Blocks.OAK_PLANKS, 0)));
            s.add(step(T + "файл замен на диске", 3_000L, mc -> this.stored(Map.of("minecraft:oak_planks", "minecraft:spruce_planks"))));
        }

        s.add(step(p + T + "мышь на кнопку отката", 3_000L, this.k.settleThen(300L, mc -> this.hoverRevert(Items.SPRUCE_PLANKS))));
        s.add(step(p + "снимок: заменённая строка и кнопка отката", 5_000L, this.k.settleShot(p + "replace-row")));

        if (full)
        {
            this.stairs(s);
            this.refusals(s);
        }

        s.add(step(p + T + "клик по кнопке отката", 3_000L, mc -> this.clickRevert(Items.SPRUCE_PLANKS)));
        s.add(step(p + T + "откат: строка «дубовые доски» вернулась", ROWS_MS,
                mc -> this.replacedRow(p, Items.OAK_PLANKS, List.of(), Items.SPRUCE_PLANKS)));
        s.add(step(p + T + "откат: схема побайтно как была", 3_000L, mc -> this.restored(p)));
        this.disabling(s, p);
        this.entities(s, p);

        if (full)
        {
            s.add(step(T + "откат: голограмма снова с дубовыми досками", REBUILD_MS,
                    mc -> this.world(Blocks.OAK_PLANKS, this.originalCount(Blocks.OAK_PLANKS), Blocks.SPRUCE_PLANKS, 0)));
            s.add(step(T + "откат: записи в файле замен нет", 3_000L, mc -> this.stored(null)));
            this.layering(s);
            this.reload(s);
            this.changedFile(s);
            s.add(step(T + "уборка", 5_000L, mc -> this.tidy()));
        }

        return s;
    }

    /**
     * Отключение блока (просьба 13.10.2026, переделка для 1.0.9): блок становится воздухом в схеме, а его строка
     * остаётся на том же месте таблицы — тот же ключ, тот же счёт, потухшая, с золотой кнопкой «включить»; в итоги
     * не идёт. Кнопка возвращает схему побайтно. Тумблер «Изменённые» оставляет только заменённые и отключённые
     * строки; «Сброс» сверху снимает разом и отключение, и замену.
     */
    private void disabling(List<DumpSchematics.Step> s, String p)
    {
        String key = DumpSchematics.id(Items.OAK_PLANKS);
        long[] before = new long[3];
        s.add(step(p + T + "отключение: строка «дубовые доски» до", ROWS_MS, mc -> this.rememberRow(p, key, before)));
        s.add(step(p + T + "отключение: дубовые доски", 3_000L, mc -> this.coreDisable(p)));
        s.add(step(p + T + "отключение: строка на месте, потухшая, тот же счёт", ROWS_MS, mc -> this.disabledRow(p, key, before)));
        s.add(step(p + T + "отключение: в схеме на их месте воздух", 3_000L,
                mc -> this.expect(p + "отключение", Map.of(Blocks.OAK_PLANKS, Blocks.AIR), false)));
        s.add(step(p + T + "отключение: «Сброс» и «Изменённые» на виду", 3_000L, mc -> this.resetShown(p, true)));
        s.add(step(p + T + "отключение: строка на виду", 3_000L, mc -> this.revealKey(key)));
        s.add(step(p + "снимок: отключённая строка и «Сброс»", 5_000L, this.k.settleShot(p + "disabled-row")));
        s.add(step(p + T + "«Изменённые»: клик", 3_000L, this.k.settleThen(300L, mc -> this.clickChanged())));
        s.add(step(p + T + "«Изменённые»: только изменённые строки", 3_000L, mc -> this.onlyChangedShown(p, key)));
        s.add(step(p + "снимок: фильтр «Изменённые»", 5_000L, this.k.settleShot(p + "changed-filter")));
        s.add(step(p + T + "«Изменённые»: клик ещё раз", 3_000L, this.k.settleThen(300L, mc -> this.clickChanged())));
        s.add(step(p + T + "«Изменённые»: снова все строки", 3_000L, mc -> this.allShown(p, before)));
        s.add(step(p + T + "включение: клик по золотой кнопке", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(key))));
        s.add(step(p + T + "включение: строка та же и включена", ROWS_MS, mc -> this.enabledRow(p, key, before)));
        s.add(step(p + T + "включение: схема побайтно как была", 3_000L, mc -> this.restored(p)));
        s.add(step(p + T + "«Сброс»: кнопки нет без правок", 3_000L, mc -> this.resetShown(p, false)));
        s.add(step(p + T + "«Сброс»: отключение и замена", 3_000L, mc -> this.disableAndReplace(p)));
        s.add(step(p + T + "«Сброс»: кнопка на виду", ROWS_MS, mc -> this.resetShown(p, true)));
        s.add(step(p + T + "«Сброс»: клик", 3_000L, this.k.settleThen(300L, mc -> this.clickReset())));
        s.add(step(p + T + "«Сброс»: строка снова включена", ROWS_MS, mc -> this.enabledRow(p, key, before)));
        s.add(step(p + T + "«Сброс»: схема побайтно как была", 3_000L, mc -> this.restored(p)));

        // Вода: строку ведра нельзя заменить, но можно отключить — блоки «в воде» при этом высыхают
        String water = DumpSchematics.id(Items.WATER_BUCKET);
        long[] waterBefore = new long[3];
        long[] slabBefore = new long[1];
        s.add(step(p + T + "отключение: вода: в схеме есть и вода, и плита «в воде»", 3_000L, mc -> this.wetPalettes(p)));
        s.add(step(p + T + "отключение: вода: строка «ведро воды» до", ROWS_MS, mc -> this.rememberRow(p, water, waterBefore)
                && this.rememberNeed(Items.OAK_SLAB, slabBefore)));
        s.add(step(p + T + "отключение: вода", 3_000L, mc -> this.coreDisableItem(p, Items.WATER_BUCKET)));
        s.add(step(p + T + "отключение: вода: строка на месте, потухшая, тот же счёт", ROWS_MS,
                mc -> this.disabledRow(p, water, waterBefore)));
        s.add(step(p + T + "отключение: вода: в замене вода → воздух, в палитре воды и «в воде» нет", 3_000L, mc -> this.dried(p)));
        s.add(step(p + T + "отключение: вода: плита «в воде» держит свой счёт", ROWS_MS,
                mc -> this.slabKept(p, slabBefore[0])));
        s.add(step(p + T + "отключение: вода: строка на виду", 3_000L, mc -> this.revealKey(water)));
        s.add(step(p + "снимок: отключённая вода", 5_000L, this.k.settleShot(p + "water-disabled")));
        s.add(step(p + T + "включение воды: клик по золотой кнопке", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(water))));
        s.add(step(p + T + "включение воды: строка та же и включена", ROWS_MS, mc -> this.enabledRow(p, water, waterBefore)));
        s.add(step(p + T + "включение воды: схема побайтно как была", 3_000L, mc -> this.restored(p)));
        s.add(step(p + T + "включение воды: вода и «в воде» на месте", 3_000L, mc -> this.wetPalettes(p)));
    }

    /** В палитрах исходной схемы есть источник воды и блок «в воде» — иначе проверки воды ничего не доказывают. */
    private boolean wetPalettes(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.busy())
        {
            return schematic == null;
        }

        Map<String, BlockState[]> cells = new LinkedHashMap<>();
        Map<String, List<BlockState>> palettes = new LinkedHashMap<>();
        Map<String, long[]> arrays = new LinkedHashMap<>();
        snapshot(schematic, cells, palettes, arrays);
        this.k.check(p + T + "в палитрах есть вода", this.paletteHas(palettes, state -> state.is(Blocks.WATER)));
        this.k.check(p + T + "в палитрах есть блок «в воде»", this.paletteHas(palettes, KReplace::wet));
        return true;
    }

    private boolean paletteHas(Map<String, List<BlockState>> palettes, java.util.function.Predicate<BlockState> test)
    {
        for (List<BlockState> palette : palettes.values())
        {
            for (BlockState state : palette)
            {
                if (test.test(state))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Блок «в воде» — у состояния есть свойство и оно включено. */
    private static boolean wet(BlockState state)
    {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private boolean rememberNeed(Item item, long[] out)
    {
        MaterialLine line = this.line(item);

        if (line == null)
        {
            return false;
        }

        out[0] = line.need();
        return true;
    }

    private boolean coreDisableItem(String p, Item item)
    {
        if (this.schematic == null || this.busy())
        {
            return this.schematic == null;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disable(this.schematic, item);
        this.k.check(p + T + "отключение ядром (" + this.lineName(item) + ") — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.refreshPane();
        return true;
    }

    /** Вода отключена: в замене вода → воздух, в палитрах нет воды и блоков «в воде». */
    private boolean dried(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.busy())
        {
            return schematic == null;
        }

        this.k.check(p + T + "в замене вода → воздух", BlockReplacements.active(schematic).get(Blocks.WATER) == Blocks.AIR);
        Map<String, BlockState[]> cells = new LinkedHashMap<>();
        Map<String, List<BlockState>> palettes = new LinkedHashMap<>();
        Map<String, long[]> arrays = new LinkedHashMap<>();
        snapshot(schematic, cells, palettes, arrays);
        this.k.check(p + T + "в палитрах воды нет", ! this.paletteHas(palettes, state -> state.is(Blocks.WATER)));
        this.k.check(p + T + "в палитрах нет блоков «в воде»", ! this.paletteHas(palettes, KReplace::wet));
        return true;
    }

    /** Строка плиты не потухла и держит прежний счёт: высохла сама плита, а не её строка. */
    private boolean slabKept(String p, long expected)
    {
        if (this.busy())
        {
            return false;
        }

        MaterialLine line = this.line(Items.OAK_SLAB);
        this.k.check(p + T + "плита: строка на месте и включена", line != null && ! line.disabled());
        this.k.check(p + T + "плита: «Всего» " + (line == null ? -1L : line.need()) + " = " + expected,
                line != null && line.need() == expected);
        return true;
    }

    /** Место строки в таблице, её «Всего» и число позиций в итогах — до отключения. */
    private boolean rememberRow(String p, String key, long[] out)
    {
        if (this.busy())
        {
            return false;
        }

        List<MaterialLine> lines = this.lines();

        for (int i = 0; i < lines.size(); ++i)
        {
            if (lines.get(i).key().equals(key) && ! lines.get(i).disabled())
            {
                out[0] = i;
                out[1] = lines.get(i).need();
                out[2] = this.positions();
                this.k.ok(p + T + "до: строка №" + i + ", всего " + out[1] + ", позиций " + out[2]);
                return true;
            }
        }

        return false;
    }

    private long positions()
    {
        MaterialFilter.Totals totals = KProbe.fieldOfType(this.pane(), MaterialFilter.Totals.class);
        return totals == null ? -1L : totals.positions();
    }

    private void refreshPane()
    {
        try
        {
            Object pane = this.pane();
            java.lang.reflect.Method method = pane.getClass().getDeclaredMethod("refreshAfterEdit");
            method.setAccessible(true);
            method.invoke(pane);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "пересчёт таблицы: " + e);
        }
    }

    private boolean coreDisable(String p)
    {
        if (this.schematic == null || this.busy())
        {
            return this.schematic == null;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disable(this.schematic, Items.OAK_PLANKS);
        this.k.check(p + T + "отключение ядром — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.refreshPane();
        return true;
    }

    private boolean disableAndReplace(String p)
    {
        if (this.schematic == null || this.busy())
        {
            return this.schematic == null;
        }

        BlockReplacements.Outcome off = BlockReplacements.disable(this.schematic, Items.OAK_PLANKS);
        BlockReplacements.Outcome swap = BlockReplacements.replace(this.schematic, Items.OAK_STAIRS, Blocks.SPRUCE_STAIRS);
        this.k.check(p + T + "отключение и замена ядром — " + off.ok() + "/" + swap.ok(), off.ok() && swap.ok());
        this.refreshPane();
        return true;
    }

    private boolean disabledRow(String p, String key, long[] before)
    {
        if (this.busy())
        {
            return false;
        }

        List<MaterialLine> lines = this.lines();

        for (int i = 0; i < lines.size(); ++i)
        {
            MaterialLine line = lines.get(i);

            if (line.key().equals(key) && line.disabled())
            {
                // Кнопка видна только у строки на экране: при GUI 3 строка бывает ниже края — сперва к ней
                if (! this.onScreen(key))
                {
                    return false;
                }

                this.k.check(p + T + "строка на том же месте: №" + i + " = №" + before[0], i == before[0]);
                this.k.check(p + T + "«Всего» " + line.need() + " = до отключения " + before[1], line.need() == before[1]);
                this.k.check(p + T + "в итоги не идёт: позиций " + this.positions() + " = " + (before[2] - 1),
                        this.positions() == before[2] - 1);
                this.k.check(p + T + "строка помечена изменённой", line.changed());
                ReplaceCell cell = this.cell();
                this.k.check(p + T + "у строки золотая кнопка", cell != null && cell.revertBox(key) != null);
                long same = lines.stream().filter(other -> other.key().equals(key)).count();
                this.k.check(p + T + "строка одна, без двойника", same == 1L);
                return true;
            }
        }

        return false;
    }

    /** Строка нарисована в последнем кадре; нет — прокрутить к ней (проверка повторится на следующем такте). */
    private boolean onScreen(String key)
    {
        ReplaceCell cell = this.cell();

        if (cell != null && cell.cellBox(key) != null)
        {
            return true;
        }

        this.revealKey(key);
        return false;
    }

    private boolean enabledRow(String p, String key, long[] before)
    {
        if (this.busy())
        {
            return false;
        }

        List<MaterialLine> lines = this.lines();

        for (int i = 0; i < lines.size(); ++i)
        {
            MaterialLine line = lines.get(i);

            if (line.key().equals(key))
            {
                if (line.disabled())
                {
                    return false;
                }

                this.k.check(p + T + "включена на том же месте: №" + i + " = №" + before[0], i == before[0]);
                this.k.check(p + T + "«Всего» " + line.need() + " = " + before[1], line.need() == before[1]);
                this.k.check(p + T + "позиций снова " + before[2], this.positions() == before[2]);
                return true;
            }
        }

        return false;
    }

    private @Nullable Toggle changedToggle()
    {
        Object pane = this.pane();

        try
        {
            java.lang.reflect.Field field = pane.getClass().getDeclaredField("changedOnly");
            field.setAccessible(true);
            return (Toggle) field.get(pane);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private boolean clickChanged()
    {
        Toggle toggle = this.changedToggle();

        if (toggle == null || ! toggle.visible() || toggle.w() <= 0)
        {
            return false;
        }

        KInline.realClick(toggle.x() + toggle.h() / 2.0, toggle.y() + toggle.h() / 2.0);
        return true;
    }

    private boolean onlyChangedShown(String p, String key)
    {
        List<MaterialLine> lines = this.lines();

        if (this.busy() || lines.isEmpty() || lines.size() > 3)
        {
            return false;
        }

        this.k.check(p + T + "«Изменённые»: все " + lines.size() + " строк изменены", lines.stream().allMatch(MaterialLine::changed));
        this.k.check(p + T + "«Изменённые»: отключённая строка в списке", lines.stream().anyMatch(line -> line.key().equals(key)));
        return true;
    }

    private boolean allShown(String p, long[] before)
    {
        List<MaterialLine> lines = this.lines();

        if (this.busy() || lines.size() <= before[0])
        {
            return false;
        }

        this.k.ok(p + T + "«Изменённые» выкл.: строк " + lines.size());
        return true;
    }

    private @Nullable Button resetButton()
    {
        Object pane = this.pane();

        try
        {
            java.lang.reflect.Field field = pane.getClass().getDeclaredField("resetAllButton");
            field.setAccessible(true);
            return (Button) field.get(pane);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private boolean resetShown(String p, boolean shown)
    {
        Button button = this.resetButton();

        if (button == null || this.busy())
        {
            return false;
        }

        boolean visible = button.visible() && button.w() > 0;

        if (visible != shown)
        {
            return false;
        }

        this.k.ok(p + T + "«Сброс» " + (shown ? "на виду: «" + button.label() + "» " + button.w() + "×" + button.h()
                + " @" + button.x() + "," + button.y() + " gui " + net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale() : "скрыта"));
        return true;
    }

    private boolean clickReset()
    {
        Button button = this.resetButton();

        if (button == null || ! button.visible() || button.w() <= 0)
        {
            return false;
        }

        KInline.realClick(button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
        return true;
    }

    private boolean revealKey(String key)
    {
        Table<MaterialLine> table = this.table();

        if (table == null || this.busy())
        {
            return false;
        }

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(key))
            {
                table.setSelected(line);
                return true;
            }
        }

        return false;
    }

    private boolean clickRevertKey(String key)
    {
        ReplaceCell cell = this.cell();
        int[] box = cell == null ? null : cell.revertBox(key);

        if (box == null)
        {
            return false;
        }

        KInline.realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    /** Шаги выбора: строка на виду → клик по названию → выбор открыт → поиск → снимок. */
    private void pick(List<DumpSchematics.Step> s, String p, Item row, String query, String expectId, boolean shot)
    {
        String what = DumpSchematics.id(row);
        s.add(step(p + T + what + " на виду", 3_000L, mc -> this.reveal(row)));
        s.add(step(p + T + "клик по названию " + what, 5_000L, this.k.settleThen(300L, mc -> this.clickName(row))));
        s.add(step(p + T + "выбор открыт (" + what + ")", 3_000L, mc -> this.pickerOpened(p, row, true)));
        s.add(step(p + T + "поиск «" + query + "»", 3_000L, mc -> this.search(p, query, expectId)));

        if (shot)
        {
            s.add(step(p + "снимок: выбор блока с поиском", 5_000L, this.k.settleShot(p + "replace-picker")));
        }
    }

    /** Ступеньки: перенос facing/half; A → B при уже существующих B и откат. Доски в это время заменены. */
    private void stairs(List<DumpSchematics.Step> s)
    {
        Map<Block, Block> planks = Map.of(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS);

        this.pick(s, "", Items.OAK_STAIRS, ReplacementStates.name(Blocks.SPRUCE_STAIRS), "minecraft:spruce_stairs", false);
        s.add(step(T + "ступеньки: Enter", 3_000L, mc -> this.enter()));
        s.add(step(T + "ступеньки: еловые с тем же facing/half", 3_000L, mc -> this.stairsCarried(Blocks.SPRUCE_STAIRS)
                && this.expect("доски+ступеньки", Map.of(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.OAK_STAIRS,
                Blocks.SPRUCE_STAIRS), false)));
        s.add(step(T + "ступеньки: строка «еловые ← дубовые»", ROWS_MS,
                mc -> this.replacedRow("", Items.SPRUCE_STAIRS, List.of(Blocks.OAK_STAIRS), Items.OAK_STAIRS)));
        s.add(step(T + "ступеньки: кнопка отката", 3_000L, this.k.settleThen(300L, mc -> this.clickRevert(Items.SPRUCE_STAIRS))));
        s.add(step(T + "ступеньки: откат — строка вернулась", ROWS_MS,
                mc -> this.replacedRow("", Items.OAK_STAIRS, List.of(), Items.SPRUCE_STAIRS)));
        s.add(step(T + "ступеньки: откат — клетки как были", 3_000L, mc -> this.expect("откат ступенек", planks, false)));

        // A → B, где B в схеме уже есть: дубовые ступеньки → ступеньки из каменных кирпичей (поиск по id)
        this.pick(s, "", Items.OAK_STAIRS, "stone_brick_stairs", "minecraft:stone_brick_stairs", false);
        s.add(step(T + "A→B: Enter", 3_000L, mc -> this.enter()));
        s.add(step(T + "A→B: клетки A — B с тем же facing/half, исходные B не тронуты", 3_000L,
                mc -> this.stairsCarried(Blocks.STONE_BRICK_STAIRS) && this.expect("A→B", Map.of(Blocks.OAK_PLANKS,
                        Blocks.SPRUCE_PLANKS, Blocks.OAK_STAIRS, Blocks.STONE_BRICK_STAIRS), false)));
        s.add(step(T + "A→B: строка B «← A», вдвое больше", ROWS_MS,
                mc -> this.replacedRow("", Items.STONE_BRICK_STAIRS, List.of(Blocks.OAK_STAIRS), Items.OAK_STAIRS)
                        && this.need(Items.STONE_BRICK_STAIRS, this.originalCount(Blocks.STONE_BRICK_STAIRS)
                        + this.originalCount(Blocks.OAK_STAIRS))));
        s.add(step(T + "A→B: кнопка отката", 3_000L, this.k.settleThen(300L, mc -> this.clickRevert(Items.STONE_BRICK_STAIRS))));
        s.add(step(T + "A→B: откат — A вернулись", ROWS_MS,
                mc -> this.replacedRow("", Items.OAK_STAIRS, List.of(), null)
                        && this.need(Items.STONE_BRICK_STAIRS, this.originalCount(Blocks.STONE_BRICK_STAIRS))));
        s.add(step(T + "A→B: откат — исходные B как были, A на местах", 3_000L, mc -> this.expect("откат A→B", planks, false)));
    }

    /** Строки, которые не заменяются: вода (ведро), сущность (стойка), «Список HUD». */
    private void refusals(List<DumpSchematics.Step> s)
    {
        s.add(step(T + "вода на виду", 3_000L, mc -> this.reveal(Items.WATER_BUCKET)));
        s.add(step(T + "клик по воде", 5_000L, this.k.settleThen(300L, mc -> this.clickName(Items.WATER_BUCKET))));
        s.add(step(T + "вода: выбор не открыт, тост — почему", 3_000L,
                this.k.settleThen(300L, mc -> this.refused("вода",
                        I18n.get("layerlist.schematics.materials.replace.why.fluid", this.lineName(Items.WATER_BUCKET))))));
        s.add(step(T + "стойка на виду", 3_000L, mc -> this.reveal(Items.ARMOR_STAND)));
        s.add(step(T + "клик по стойке", 5_000L, this.k.settleThen(300L, mc -> this.clickName(Items.ARMOR_STAND))));
        s.add(step(T + "стойка: выбор не открыт, тост — почему", 3_000L,
                this.k.settleThen(300L, mc -> this.refused("стойка", I18n.get("layerlist.schematics.materials.replace.why.entity")))));
        s.add(step(T + "«Список HUD» кнопкой", 3_000L, mc -> this.hudSource()));
        s.add(step(T + "«Список HUD»: строки", ROWS_MS, mc -> this.hudRows()));
        s.add(step(T + "«Список HUD»: клик по строке", 5_000L, this.k.settleThen(300L, mc -> this.clickFirst())));
        s.add(step(T + "«Список HUD»: выбор не открыт, тост — почему", 3_000L,
                this.k.settleThen(300L, mc -> this.refused("HUD", I18n.get("layerlist.schematics.materials.replace.why.hud")))));
        // «Список HUD»: отключить можно — во всех схемах включённых размещений разом (просьба 21.10.2026)
        s.add(step(T + "«Список HUD»: строка каменных кирпичей, отключить можно", ROWS_MS, mc -> this.hudBefore(Items.STONE_BRICKS)));
        // Путь игрока: ПКМ по строке → «Отключить блок» → строка потухла → окно выгрузки помнит об отключённом → золотая кнопка
        s.add(step(T + "«Список HUD»: строка на виду", 3_000L, mc -> this.hudLine == null || this.revealKey(this.hudLine.key())));
        s.add(step(T + "«Список HUD»: ПКМ по строке", 3_000L,
                this.k.settleThen(300L, mc -> this.hudLine == null || this.rightClickRow(this.hudLine.key()))));
        s.add(step(T + "«Список HUD»: в меню есть «Отключить блок»", 3_000L,
                mc -> this.menuHas(I18n.get("layerlist.schematics.materials.disabled.menu.off"))));
        s.add(step("снимок: «Список HUD» — меню строки", 5_000L, this.k.settleShot("hud-menu")));
        s.add(step(T + "«Список HUD»: «Отключить блок» из меню", 3_000L,
                mc -> this.chooseMenu(I18n.get("layerlist.schematics.materials.disabled.menu.off"))));
        s.add(step(T + "«Список HUD»: строка потухла, тот же счёт", ROWS_MS, mc -> this.hudDisabledRow()));
        s.add(step(T + "«Список HUD»: в схеме камень-кирпич → воздух", 3_000L, mc -> this.hudOff(true)));
        s.add(step(T + "«Список HUD»: потухшая строка на виду", 3_000L, mc -> this.hudLine == null || this.revealKey(this.hudLine.key())));
        s.add(step("снимок: «Список HUD» — отключённая строка", 5_000L, this.k.settleShot("hud-disabled-row")));
        s.add(step(T + "выгрузка: окно из «Материалов»", 5_000L, mc -> this.openExport()));
        s.add(step(T + "выгрузка: окно говорит, что отключённое в таблицу не идёт", 5_000L, mc -> this.exportNote()));
        s.add(step("снимок: окно выгрузки с пометкой", 5_000L, this.k.settleShot("export-note")));
        s.add(step(T + "выгрузка: закрыть окно", 3_000L, mc -> this.closeExport()));
        s.add(step(T + "«Список HUD»: включение золотой кнопкой", 3_000L,
                this.k.settleThen(300L, mc -> this.hudLine == null || this.clickRevertKey(this.hudLine.key()))));
        s.add(step(T + "«Список HUD»: строка снова включена", ROWS_MS, mc -> this.hudEnabledRow()));
        s.add(step(T + "«Список HUD»: в схеме замены камня-кирпича нет", 3_000L, mc -> this.hudOff(false)));
        this.hudEntity(s);
        s.add(step(T + "обратно к схеме", 3_000L, mc -> this.backToSchematic()));
        s.add(step(T + "строки схемы снова", ROWS_MS, mc -> this.replacedRow("", Items.SPRUCE_PLANKS, List.of(Blocks.OAK_PLANKS),
                Items.OAK_PLANKS, false)));
    }

    /** Litematica загрузила схему заново: сама ({@code getOrLoad}, как при входе в мир) и после «перезапуска». */
    private void reload(List<DumpSchematics.Step> s)
    {
        s.add(step(T + "перезагрузка: замена (ядро)", 3_000L, mc -> this.coreReplace()));
        s.add(step(T + "перезагрузка: выгрузить из Litematica", 5_000L, mc -> this.unload()));
        s.add(step(T + "перезагрузка: Litematica загрузила снова — замены наложены", 10_000L, mc -> this.reloadCheck(false)));
        s.add(step(T + "перезагрузка: после «перезапуска» (файл замен с диска)", 10_000L, mc -> this.reloadCheck(true)));
        s.add(step(T + "перезагрузка: откат касается и перечитанной схемы", 5_000L, mc -> this.reloadRevert()));
        this.entityReload(s);
    }

    /** Файл схемы на диске сменился: «потрогали» — замены остаются (CRC тот же), другой файл — сброшены. */
    private void changedFile(List<DumpSchematics.Step> s)
    {
        s.add(step(T + "файл: копия k-generated загружена", 20_000L, mc -> this.copyLoaded()));
        s.add(step(T + "файл: замена в копии (доски → берёзовые)", 3_000L, mc -> this.copyReplace()));
        s.add(step(T + "файл: время другое, содержимое то же — замены на месте", 10_000L,
                this.k.settleThen(1_500L, mc -> this.copyTouched())));
        s.add(step(T + "файл: другое содержимое — замены сброшены молча", 10_000L, mc -> this.copyChanged()));
    }

    /** Большая схема игрока: время замены (палитра — мгновенно), паузы кадров после, откат. */
    List<DumpSchematics.Step> bigSteps()
    {
        List<DumpSchematics.Step> s = new ArrayList<>();
        s.add(step(T + "большая: найти и загрузить", BIG_MS, mc -> this.loadBig()));
        s.add(step(T + "большая: «Материалы» этой схемы", 3_000L, mc -> this.openBig()));
        s.add(step(T + "большая: строки посчитаны", BIG_MS, mc -> this.bigRows()));
        s.add(step(T + "большая: строка на виду", 3_000L, mc -> this.bigReveal()));
        s.add(step(T + "большая: клик по названию", 5_000L, this.k.settleThen(300L, mc -> this.bigItem == null || this.clickName(this.bigItem))));
        s.add(step(T + "большая: выбор открыт", 3_000L, mc -> this.bigItem == null || this.pickerOpened("", this.bigItem, false)));
        s.add(step(T + "большая: поиск по id", 3_000L, mc -> this.bigTarget == null
                || this.search("", ReplacementStates.id(this.bigTarget).toString(), ReplacementStates.id(this.bigTarget).toString())));
        s.add(step(T + "большая: Enter — время замены", 3_000L, mc -> this.bigEnter()));
        s.add(step(T + "большая: 5 с после замены — паузы кадров", 30_000L, this.k.settleThen(5_000L, mc -> this.bigPauses("замены"))));
        s.add(step(T + "большая: пересчёт строк", BIG_MS, mc -> this.bigRecounted()));
        s.add(step(T + "большая: откат — время", 5_000L, this.k.settleThen(300L, mc -> this.bigRevert())));
        s.add(step(T + "большая: 5 с после отката — паузы кадров", 30_000L, this.k.settleThen(5_000L, mc -> this.bigPauses("отката"))));
        s.add(step(T + "большая: убрать", 10_000L, mc -> this.unloadBig()));
        return s;
    }

    void cleanup()
    {
        this.tidy();
        this.unloadBig();

        if (this.hudBefore != null)
        {
            LayerListConfigs.HUD_ENABLED = this.hudBefore;
            this.hudBefore = null;
        }
    }

    // ===== Экран =====

    private @Nullable Object pane()
    {
        return KProbe.fieldNamedType(this.k.gui(), "MaterialsPane");
    }

    @SuppressWarnings("unchecked")
    private @Nullable Table<MaterialLine> table()
    {
        return KProbe.fieldOfType(this.pane(), Table.class);
    }

    private @Nullable ReplaceCell cell()
    {
        return KProbe.fieldOfType(this.pane(), ReplaceCell.class);
    }

    private @Nullable Layers layers()
    {
        return KProbe.fieldOfType(this.k.gui(), Layers.class);
    }

    private @Nullable BlockPicker picker()
    {
        Layers layers = this.layers();
        return layers == null ? null : BlockPicker.TestHooks.current(layers);
    }

    private boolean busy()
    {
        MaterialsJob job = KProbe.fieldOfType(this.pane(), MaterialsJob.class);
        return job == null || job.running();
    }

    private List<MaterialLine> lines()
    {
        Table<MaterialLine> table = this.table();
        return table == null ? List.of() : List.copyOf(table.body().items());
    }

    /** Строка предмета без данных (ключ — сам id). */
    private @Nullable MaterialLine line(Item item)
    {
        String id = DumpSchematics.id(item);

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(id))
            {
                return line;
            }
        }

        return null;
    }

    private String lineName(Item item)
    {
        MaterialLine line = this.line(item);
        return line == null ? "?" : line.name();
    }

    // ===== Подготовка =====

    /** Строки посчитаны, поиск таблицы пуст; первый раз — снимок исходных клеток k-generated. */
    /** Таблица собрана счётчиком размещения (поле {@code verifiedRows} панели) и не занята. */
    private boolean verifiedReady()
    {
        Object pane = this.pane();

        if (pane == null || this.busy())
        {
            return false;
        }

        try
        {
            java.lang.reflect.Field field = pane.getClass().getDeclaredField("verifiedRows");
            field.setAccessible(true);

            if (! field.getBoolean(pane))
            {
                return false;
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "сверка таблицы: " + e);
            return true;
        }

        this.k.ok(T + "таблица сверена с миром");
        return this.line(Items.OAK_PLANKS) != null;
    }

    private boolean ready(String p)
    {
        Object pane = this.pane();
        TextInput search = KProbe.fieldOfType(pane, TextInput.class);

        if (search != null && ! search.text().isEmpty())
        {
            search.setText("");
        }

        if (pane == null || this.busy() || this.line(Items.OAK_PLANKS) == null)
        {
            return false;
        }

        LitematicaSchematic schematic = KProbe.fieldOfType(pane, LitematicaSchematic.class);

        if (! this.k.check(p + T + "у таблицы есть схема Litematica", schematic != null))
        {
            return true;
        }

        this.schematic = schematic;
        this.file = schematic.getFile() == null ? null : schematic.getFile().toAbsolutePath().normalize();

        if (this.cellsBefore.isEmpty())
        {
            this.k.check(p + T + "замен в схеме ещё нет", BlockReplacements.active(schematic).isEmpty());
            snapshot(schematic, this.cellsBefore, this.palettesBefore, this.arraysBefore);
            this.k.ok(p + T + "снимок исходных клеток: регионов " + this.cellsBefore.size() + ", досок "
                    + this.originalCount(Blocks.OAK_PLANKS) + ", дубовых ступенек " + this.originalCount(Blocks.OAK_STAIRS)
                    + ", ступенек из каменных кирпичей " + this.originalCount(Blocks.STONE_BRICK_STAIRS));
        }
        else
        {
            // Повторный проход (GUI 3): схема та же по содержимому, что и до первой замены
            this.expect(p + "перед проходом", Map.of(), false);
        }

        return true;
    }

    private boolean place()
    {
        LitematicaSchematic schematic = this.schematic;
        SchematicEntry entry = this.k.generated(".litematic");

        if (schematic == null || entry == null)
        {
            this.k.fail(T + "размещение: нет схемы");
            return true;
        }

        this.hudBefore = LayerListConfigs.HUD_ENABLED;
        LayerListConfigs.HUD_ENABLED = true;
        this.placement = PlacementOps.placeInFront(new LoadedSchematic(entry, schematic));
        LayerCountManager.getInstance().markDirty();
        this.k.ok(T + "размещение «" + this.placement.getName() + "» поставлено");
        return true;
    }

    // ===== Клики и клавиши =====

    private boolean reveal(Item item)
    {
        Table<MaterialLine> table = this.table();
        MaterialLine line = this.line(item);

        if (table == null || line == null)
        {
            return false;
        }

        table.setSelected(line);
        return true;
    }

    private boolean clickName(Item item)
    {
        ReplaceCell cell = this.cell();
        MaterialLine line = this.line(item);
        int[] box = cell == null || line == null ? null : cell.cellBox(line.key());

        if (box == null)
        {
            return false;
        }

        KInline.realClick(box[0] + box[2] / 3.0, box[1] + box[3] / 2.0);
        return true;
    }

    /** Клик по названию первой видимой строки (список HUD). */
    private boolean clickFirst()
    {
        ReplaceCell cell = this.cell();
        List<MaterialLine> lines = this.lines();
        int[] box = cell == null || lines.isEmpty() ? null : cell.cellBox(lines.getFirst().key());

        if (box == null)
        {
            return false;
        }

        KInline.realClick(box[0] + box[2] / 3.0, box[1] + box[3] / 2.0);
        return true;
    }

    private boolean hoverRevert(Item item)
    {
        ReplaceCell cell = this.cell();
        MaterialLine line = this.line(item);
        int[] box = cell == null || line == null ? null : cell.revertBox(line.key());

        if (box == null)
        {
            return false;
        }

        move(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        this.k.ok(T + "кнопка отката у «" + line.name() + "»: " + box[2] + "×" + box[3] + " в (" + box[0] + ", " + box[1] + ")");
        return true;
    }

    private boolean clickRevert(Item item)
    {
        ReplaceCell cell = this.cell();
        MaterialLine line = this.line(item);
        int[] box = cell == null || line == null ? null : cell.revertBox(line.key());

        if (box == null)
        {
            return false;
        }

        KInline.realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    private boolean pickerOpened(String p, Item row, boolean similarFirst)
    {
        BlockPicker picker = this.picker();

        if (picker == null)
        {
            return false;
        }

        TextInput search = BlockPicker.TestHooks.search(picker);
        this.k.check(p + T + "поле поиска выбора в фокусе", search.focused());
        this.k.check(p + T + "ввод текста SDL — у поля поиска выбора", owner() == search);

        List<String> shown = BlockPicker.TestHooks.shown(picker);
        String own = DumpSchematics.id(row);
        this.k.check(p + T + "в списке нет самого заменяемого блока (" + own + ")", ! shown.contains(own));
        this.k.check(p + T + "список не пуст: блоков " + shown.size(), shown.size() > 100);

        // Похожие сверху: у досок — доски, у ступенек — ступеньки
        if (similarFirst)
        {
            String tail = own.substring(own.lastIndexOf('_') + 1);
            this.k.check(p + T + "первым — похожий (…_" + tail + "): " + shown.getFirst(), shown.getFirst().endsWith("_" + tail));
        }

        return true;
    }

    private boolean search(String p, String query, String expectId)
    {
        BlockPicker picker = this.picker();

        if (picker == null)
        {
            this.k.fail(p + T + "поиск: выбора нет");
            return true;
        }

        type(query);
        TextInput search = BlockPicker.TestHooks.search(picker);
        this.k.check(p + T + "поле поиска получило набранное", query, search.text());

        List<String> shown = BlockPicker.TestHooks.shown(picker);
        this.k.check(p + T + "поиск «" + query + "» нашёл " + expectId + " первым", expectId,
                shown.isEmpty() ? "ничего" : shown.getFirst());
        this.k.check(p + T + "найденный сразу выбран", expectId, BlockPicker.TestHooks.selected(picker));
        this.k.info(p + T + "найдено " + shown.size() + " из " + BlockPicker.TestHooks.total(picker) + ": "
                + shown.subList(0, Math.min(5, shown.size())));
        return true;
    }

    private boolean enter()
    {
        press(InputConstants.KEY_RETURN);
        return true;
    }

    private boolean refused(String what, String reason)
    {
        this.k.check(T + what + ": выбор не открылся", this.picker() == null);
        Toasts toasts = KProbe.fieldOfType(this.k.gui(), Toasts.class);
        List<String> texts = toasts == null ? List.of() : Toasts.TestHooks.texts(toasts);
        boolean said = texts.stream().anyMatch(text -> text.contains(reason));
        this.k.check(T + what + ": тост объясняет — " + (said ? reason : texts), said);

        if (toasts != null)
        {
            toasts.clear();
        }

        return true;
    }

    private boolean hudSource()
    {
        Button hud = KProbe.button(this.pane(), "layerlist.schematics.materials.source.hud");

        if (hud == null)
        {
            this.k.fail(T + "кнопки «Список HUD» нет");
            return true;
        }

        KInline.realClick(hud.x() + hud.w() / 2.0, hud.y() + hud.h() / 2.0);
        return true;
    }

    private boolean hudRows()
    {
        Object pane = this.pane();
        boolean hud = false;

        try
        {
            hud = KProbe.call(pane, "entry") == null && KProbe.call(pane, "placement") == null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "источник таблицы: " + e);
            return true;
        }

        return hud && ! this.busy() && ! this.lines().isEmpty() && LayerCountManager.getInstance().hasData();
    }

    /** Строка «Списка HUD» до отключения: можно ли отключить и её счёт. */
    private @Nullable MaterialLine hudLine;
    private long hudNeed;

    private boolean hudBefore(Item item)
    {
        if (this.busy() || this.line(item) == null || this.schematic == null)
        {
            return false;
        }

        MaterialLine line = this.line(item);
        this.hudLine = line;
        this.hudNeed = line.need();
        List<LitematicaSchematic> schematics = BlockReplacements.hudSchematics();
        this.k.check(T + "«Список HUD»: схема размещения — k-generated", schematics.contains(this.schematic));
        BlockReplacements.RowState state = BlockReplacements.hudRows(List.of(line.row()), schematics).get(line.key());
        this.k.check(T + "«Список HUD»: строку можно отключить, а заменить нельзя",
                state != null && state.disableable() && state.refusal() == BlockReplacements.Refusal.HUD);
        return true;
    }

    private boolean hudDisable()
    {
        MaterialLine line = this.hudLine;

        if (line == null || this.busy() || this.schematic == null)
        {
            return line == null;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.disableAll(BlockReplacements.hudSchematics(), line.row());
        this.k.check(T + "«Список HUD»: отключение во всех схемах — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.k.check(T + "«Список HUD»: в схеме камень-кирпич → воздух",
                BlockReplacements.active(this.schematic).get(Blocks.STONE_BRICKS) == Blocks.AIR);
        this.refreshPane();
        return true;
    }

    /** Список HUD пересчитан: строка осталась, потухла и считает то же. */
    private boolean hudDisabledRow()
    {
        MaterialLine was = this.hudLine;

        if (was == null || this.busy())
        {
            return was == null;
        }

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(was.key()) && line.disabled())
            {
                this.k.check(T + "«Список HUD»: «Всего» " + line.need() + " = до отключения " + this.hudNeed, line.need() == this.hudNeed);
                return true;
            }
        }

        return false;
    }

    /** Блок строки HUD в схеме k-generated отключён ({@code true}) или нет. */
    private boolean hudOff(boolean off)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return true;
        }

        boolean now = BlockReplacements.active(schematic).get(Blocks.STONE_BRICKS) == Blocks.AIR;
        this.k.check(T + "«Список HUD»: камень-кирпич " + (off ? "отключён" : "включён") + " в схеме", now == off);
        return true;
    }

    /** Кнопка формата подвала «Материалов» (xlsx) — тот же вызов, что у игрока. */
    private boolean openExport()
    {
        try
        {
            KProbe.call(this.pane(), "export", com.aell.layerlist.schematics.gui.materials.ExportFormat.XLSX);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "выгрузка: окно не открылось: " + e);
        }

        return true;
    }

    private boolean exportNote()
    {
        Layers layers = this.layers();

        if (layers == null || ! (layers.top() instanceof com.aell.layerlist.schematics.gui.export.ExportWindow window))
        {
            return false;
        }

        if (! com.aell.layerlist.schematics.gui.export.ExportWindow.TestHooks.sourceReady(window))
        {
            return false;
        }

        long disabled = com.aell.layerlist.schematics.gui.export.ExportWindow.TestHooks.source(window).disabledBlocks();
        String text = "";

        try
        {
            Object message = KProbe.call(window, "filterAbout");
            // StatusLine.Message закрыт в своём пакете — текст достаём отражением
            if (message != null)
            {
                java.lang.reflect.Method getter = message.getClass().getDeclaredMethod("text");
                getter.setAccessible(true);
                text = String.valueOf(getter.invoke(message));
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "выгрузка: пояснение окна: " + e);
        }

        this.k.check(T + "выгрузка: отключено блоков " + disabled + " = " + this.hudNeed, disabled == this.hudNeed);
        String note = I18n.get("layerlist.schematics.export.dialog.disabled.note", "").split("\\(")[0];
        this.k.check(T + "выгрузка: пояснение «" + text + "» говорит об отключённых", text.contains(note));
        return true;
    }

    private boolean closeExport()
    {
        com.aell.layerlist.schematics.gui.GuiSchematics gui = this.k.gui();
        Layers layers = this.layers();

        if (gui == null || layers == null)
        {
            return true;
        }

        if (layers.top() instanceof com.aell.layerlist.schematics.gui.export.ExportWindow)
        {
            KProbe.press(gui, InputConstants.KEY_ESCAPE);
            return false;
        }

        return true;
    }

    private boolean hudEnable()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.busy())
        {
            return schematic == null;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.enableAll(Map.of(schematic, List.of(Blocks.STONE_BRICKS)));
        this.k.check(T + "«Список HUD»: включение — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.k.check(T + "«Список HUD»: в схеме замены камня-кирпича нет", ! BlockReplacements.active(schematic).containsKey(Blocks.STONE_BRICKS));
        this.refreshPane();
        return true;
    }

    private boolean hudEnabledRow()
    {
        MaterialLine was = this.hudLine;

        if (was == null || this.busy())
        {
            return was == null;
        }

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(was.key()) && ! line.disabled())
            {
                this.k.check(T + "«Список HUD»: строка включена, счёт " + line.need() + " = " + this.hudNeed, line.need() == this.hudNeed);
                return true;
            }
        }

        return false;
    }

    private boolean backToSchematic()
    {
        SchematicEntry entry = this.k.generated(".litematic");

        try
        {
            KProbe.call(this.pane(), "open", entry, null);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "обратно к схеме: " + e);
        }

        return true;
    }

    // ===== Сущности (1.0.18) =====

    /** Списки сущностей регионов k-generated до отключения: после золотой кнопки они должны быть теми же (по ссылкам). */
    private final Map<String, List<LitematicaSchematic.EntityInfo>> entBefore = new LinkedHashMap<>();
    private long entFramesBefore;
    private long entGlowBefore;
    /** Перечитанная схема шага «после перечитывания» и сколько раз её уже опрашивали. */
    private @Nullable LitematicaSchematic entLoaded;
    private @Nullable LitematicaSchematic entLast;

    /**
     * Отключение сущностей (просьба 21.10.2026): стойка уходит из схемы целиком; предмет в рамке снимается, рамки
     * остаются; рамка уходит вместе с содержимым, и тогда потухают две строки; золотая кнопка возвращает списки
     * сущностей теми же. Образец: стойка, рамка с алмазом, пустая светящаяся рамка.
     */
    private void entities(List<DumpSchematics.Step> s, String p)
    {
        String stand = DumpSchematics.id(Items.ARMOR_STAND);
        String frame = DumpSchematics.id(Items.ITEM_FRAME);
        String diamond = DumpSchematics.id(Items.DIAMOND);
        String off = I18n.get("layerlist.schematics.materials.disabled.menu.off");
        String layer = I18n.get("layerlist.schematics.materials.layer.menu");
        String E = p + T + "сущности: ";

        // а) стойка — путь игрока: ПКМ → «Отключить блок»
        long[] standBefore = new long[3];
        s.add(step(E + "стойка: строка и списки до", ROWS_MS, mc -> this.rememberRow(p, stand, standBefore) && this.rememberEntities(p)));
        s.add(step(E + "стойка: отключить можно, заменить нельзя", 3_000L, mc -> this.entityRowState(p, Items.ARMOR_STAND)));
        s.add(step(E + "стойка: строка на виду", 3_000L, mc -> this.revealKey(stand)));
        s.add(step(E + "стойка: ПКМ по строке", 3_000L, this.k.settleThen(300L, mc -> this.rightClickRow(stand))));
        s.add(step(E + "стойка: в меню есть «Отключить блок»", 3_000L, mc -> this.menuHas(off)));
        s.add(step(E + "стойка: в меню нет «Отключить на слое…»", 3_000L, mc -> this.menuLacks(layer)));
        s.add(step(p + "снимок: меню строки сущности", 5_000L, this.k.settleShot(p + "entity-menu")));
        s.add(step(E + "стойка: «Отключить блок» из меню", 3_000L, mc -> this.chooseMenu(off)));
        s.add(step(E + "стойка: строка потухла, тот же счёт", ROWS_MS, mc -> this.disabledRow(p, stand, standBefore)));
        s.add(step(E + "стойки нет в списках схемы, в файле замен есть", 10_000L,
                mc -> this.entityGone(p, SchematicEntities.Kind.ARMOR_STAND, "minecraft:armor_stand")));
        s.add(step(E + "стойка: потухшая строка на виду", 3_000L, mc -> this.revealKey(stand)));
        s.add(step(p + "снимок: отключённая стойка", 5_000L, this.k.settleShot(p + "entity-disabled")));
        s.add(step(E + "стойка: золотая кнопка", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(stand))));
        s.add(step(E + "стойка: строка включена, тот же счёт", ROWS_MS, mc -> this.enabledRow(p, stand, standBefore)));
        s.add(step(E + "стойка: списки сущностей те же, записи в файле нет", 10_000L, mc -> this.entitiesRestored(p, "minecraft:armor_stand")));

        // б) алмаз в рамке: рамки остаются, предмет снят
        long[] diamondBefore = new long[3];
        long[] frameBefore = new long[3];
        s.add(step(E + "алмаз: строки и списки до", ROWS_MS, mc -> this.rememberRow(p, diamond, diamondBefore)
                && this.rememberRow(p, frame, frameBefore) && this.rememberEntities(p)));
        s.add(step(E + "алмаз: отключение ядром", 3_000L, mc -> this.coreDisableItem(p, Items.DIAMOND)));
        s.add(step(E + "алмаз: строка потухла, тот же счёт", ROWS_MS, mc -> this.disabledRow(p, diamond, diamondBefore)));
        s.add(step(E + "алмаз: рамки на месте, предмета в них нет", 10_000L, mc -> this.framesStripped(p)));
        s.add(step(E + "алмаз: строка рамки держит свой счёт", ROWS_MS, mc -> this.enabledNeed(p, "рамка", frame, frameBefore[1])));
        s.add(step(E + "алмаз: потухшая строка на виду", 3_000L, mc -> this.revealKey(diamond)));
        s.add(step(E + "алмаз: золотая кнопка", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(diamond))));
        s.add(step(E + "алмаз: строка включена, тот же счёт", ROWS_MS, mc -> this.enabledRow(p, diamond, diamondBefore)));
        s.add(step(E + "алмаз: списки сущностей те же", 10_000L, mc -> this.entitiesRestored(p, "minecraft:diamond")));

        // в) рамка: уходит с содержимым — две потухшие строки, одна кнопка возвращает обе
        s.add(step(E + "рамка: строки и списки до", ROWS_MS, mc -> this.rememberRow(p, diamond, diamondBefore)
                && this.rememberRow(p, frame, frameBefore) && this.rememberEntities(p)));
        s.add(step(E + "рамка: отключение ядром", 3_000L, mc -> this.coreDisableItem(p, Items.ITEM_FRAME)));
        s.add(step(E + "рамка: обычных рамок нет, светящаяся на месте", 10_000L, mc -> this.framesGone(p)));
        s.add(step(E + "рамка: потухла строка рамки", ROWS_MS, mc -> this.greyed(p, "рамка", frame, frameBefore[1])));
        s.add(step(E + "рамка: потухла и строка алмаза", ROWS_MS, mc -> this.greyed(p, "алмаз", diamond, diamondBefore[1])));
        s.add(step(E + "рамка: строка рамки на виду", 3_000L, mc -> this.revealKey(frame)));
        s.add(step(E + "рамка: золотая кнопка рамки", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(frame))));
        s.add(step(E + "рамка: обе строки включены", ROWS_MS,
                mc -> this.enabledNeed(p, "рамка", frame, frameBefore[1]) && this.enabledNeed(p, "алмаз", diamond, diamondBefore[1])));
        s.add(step(E + "рамка: списки сущностей те же", 10_000L, mc -> this.entitiesRestored(p, "minecraft:item_frame")));
    }

    /** Списки сущностей регионов (копии списков; сами записи — те же объекты). Пусто без схемы. */
    /**
     * Списки сущностей те же — по ссылкам на записи: {@code CompoundData.equals} malilib сравнивает только ключи левой
     * стороны, и рамка без предмета у него «равна» рамке с предметом.
     */
    private static boolean sameLists(Map<String, List<LitematicaSchematic.EntityInfo>> a,
            Map<String, List<LitematicaSchematic.EntityInfo>> b)
    {
        if (! a.keySet().equals(b.keySet()))
        {
            return false;
        }

        for (Map.Entry<String, List<LitematicaSchematic.EntityInfo>> pair : a.entrySet())
        {
            List<LitematicaSchematic.EntityInfo> x = pair.getValue();
            List<LitematicaSchematic.EntityInfo> y = b.get(pair.getKey());

            if (x.size() != y.size())
            {
                return false;
            }

            for (int i = 0; i < x.size(); ++i)
            {
                if (x.get(i) != y.get(i))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static Map<String, List<LitematicaSchematic.EntityInfo>> entityLists(@Nullable LitematicaSchematic schematic)
    {
        Map<String, List<LitematicaSchematic.EntityInfo>> out = new LinkedHashMap<>();

        if (schematic == null)
        {
            return out;
        }

        for (String region : schematic.getAreaSizes().keySet())
        {
            List<LitematicaSchematic.EntityInfo> list = schematic.getEntityListForRegion(region);
            out.put(region, list == null ? List.of() : new ArrayList<>(list));
        }

        return out;
    }

    /** Разобранные сущности схемы (стойки, рамки…) — по живым спискам, как их читает модуль. */
    private static List<SchematicEntities.Spec> specsOf(@Nullable LitematicaSchematic schematic)
    {
        List<SchematicEntities.Spec> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();

        if (schematic == null || mc.level == null)
        {
            return out;
        }

        for (String region : schematic.getAreaSizes().keySet())
        {
            List<LitematicaSchematic.EntityInfo> list = schematic.getEntityListForRegion(region);

            if (list != null)
            {
                out.addAll(SchematicEntities.specs(list, mc.level));
            }
        }

        return out;
    }

    private static long kindCount(@Nullable LitematicaSchematic schematic, SchematicEntities.Kind kind)
    {
        return specsOf(schematic).stream().filter(spec -> spec.kind() == kind).count();
    }

    private static long typeCount(@Nullable LitematicaSchematic schematic, String type)
    {
        return specsOf(schematic).stream().filter(spec -> spec.type() != null
                && type.equals(BuiltInRegistries.ENTITY_TYPE.getKey(spec.type()).toString())).count();
    }

    /** Запомнить списки сущностей и убедиться, что образец их содержит: стойка и две рамки (с алмазом и светящаяся). */
    private boolean rememberEntities(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return false;
        }

        this.entBefore.clear();
        this.entBefore.putAll(entityLists(schematic));
        this.entFramesBefore = typeCount(schematic, "minecraft:item_frame");
        this.entGlowBefore = typeCount(schematic, "minecraft:glow_item_frame");
        this.k.check(p + T + "сущности: в схеме стойка", kindCount(schematic, SchematicEntities.Kind.ARMOR_STAND) >= 1L);
        this.k.check(p + T + "сущности: в схеме обычная рамка с алмазом", this.entFramesBefore >= 1L
                && specsOf(schematic).stream().anyMatch(spec -> "minecraft:item_frame".equals(BuiltInRegistries.ENTITY_TYPE.getKey(spec.type()).toString())
                        && spec.items().stream().anyMatch(stack -> stack.is(Items.DIAMOND))));
        this.k.check(p + T + "сущности: в схеме светящаяся рамка", this.entGlowBefore >= 1L);
        return true;
    }

    /** Строка сущности: отключить можно (меню «Отключить блок»), заменить нельзя из-за сущности. */
    private boolean entityRowState(String p, Item item)
    {
        MaterialLine line = this.line(item);

        if (this.busy() || line == null || this.schematic == null)
        {
            return false;
        }

        BlockReplacements.RowState state = BlockReplacements.rows(this.schematic, List.of(line.row()), false).get(line.key());
        this.k.check(p + T + "сущности: «" + line.name() + "» — отключить можно, заменить нельзя (сущность)",
                state != null && state.disableable() && state.refusal() == BlockReplacements.Refusal.ENTITY);
        return true;
    }

    /** В открытом меню нет пункта с такой подписью (у строк сущностей нет «Отключить на слое…»). */
    private boolean menuLacks(String label)
    {
        Layers layers = this.layers();

        if (layers == null || ! (layers.top() instanceof com.aell.layerlist.ui.kit.ContextMenu menu))
        {
            return false;
        }

        List<com.aell.layerlist.ui.kit.ContextMenu.Item> items = KProbe.fieldOfType(menu, List.class);
        boolean found = items.stream().anyMatch(i -> ! i.isSeparator() && label.equals(i.label()));
        this.k.check(T + "сущности: в меню нет «" + label + "»", ! found);
        return true;
    }

    /** Сущности такого рода убраны из списков схемы, а в файле замен записан предмет. Ждёт наложения (такт клиента). */
    private boolean entityGone(String p, SchematicEntities.Kind kind, String stored)
    {
        LitematicaSchematic schematic = this.schematic;
        Path file = this.file;

        if (schematic == null || file == null)
        {
            this.k.fail(p + T + "сущности: нет схемы или файла");
            return true;
        }

        if (kindCount(schematic, kind) != 0L)
        {
            return false;
        }

        List<String> entities = BlockReplacements.TestHooks.storedEntities(file);
        this.k.check(p + T + "сущности: в файле замен " + stored + " (" + entities + ")", entities != null && entities.contains(stored));
        this.k.ok(p + T + "сущности: в списках схемы " + kind + " нет");
        return true;
    }

    /** Золотая кнопка вернула списки сущностей (по ссылкам) и убрала запись из файла замен. */
    private boolean entitiesRestored(String p, String storedGone)
    {
        LitematicaSchematic schematic = this.schematic;
        Path file = this.file;

        if (schematic == null || file == null)
        {
            this.k.fail(p + T + "сущности: нет схемы или файла");
            return true;
        }

        if (! sameLists(entityLists(schematic), this.entBefore))
        {
            return false;
        }

        List<String> entities = BlockReplacements.TestHooks.storedEntities(file);
        this.k.check(p + T + "сущности: списки как были, в файле замен нет " + storedGone + " (" + entities + ")",
                entities == null || ! entities.contains(storedGone));
        return true;
    }

    /** Предмет снят с рамок, сами рамки на месте: число то же, у каждой один предмет (она сама). */
    private boolean framesStripped(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return true;
        }

        List<SchematicEntities.Spec> frames = specsOf(schematic).stream()
                .filter(spec -> spec.kind() == SchematicEntities.Kind.ITEM_FRAME).toList();

        if (frames.stream().anyMatch(spec -> spec.items().size() != 1))
        {
            return false;
        }

        this.k.check(p + T + "сущности: рамок " + frames.size() + " = до отключения " + (this.entFramesBefore + this.entGlowBefore),
                frames.size() == this.entFramesBefore + this.entGlowBefore);
        this.k.check(p + T + "сущности: алмаза ни в одной рамке нет",
                frames.stream().noneMatch(spec -> spec.items().stream().anyMatch(stack -> stack.is(Items.DIAMOND))));
        return true;
    }

    /** Обычные рамки убраны с содержимым, светящаяся осталась (у неё свой предмет и своя строка). */
    private boolean framesGone(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return true;
        }

        if (typeCount(schematic, "minecraft:item_frame") != 0L)
        {
            return false;
        }

        this.k.check(p + T + "сущности: светящихся рамок " + typeCount(schematic, "minecraft:glow_item_frame") + " = " + this.entGlowBefore,
                typeCount(schematic, "minecraft:glow_item_frame") == this.entGlowBefore);
        return true;
    }

    /** Потухшая строка с прежним счётом (место и позиции итогов не сверяем: отключение рамки гасит две строки). */
    private boolean greyed(String p, String what, String key, long need)
    {
        if (this.busy())
        {
            return false;
        }

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(key) && line.disabled())
            {
                if (! this.onScreen(key))
                {
                    return false;
                }

                this.k.check(p + T + "сущности: " + what + " потухла, «Всего» " + line.need() + " = " + need, line.need() == need);
                this.k.check(p + T + "сущности: " + what + ": у строки золотая кнопка",
                        this.cell() != null && this.cell().revertBox(key) != null);
                return true;
            }
        }

        return false;
    }

    /** Включённая строка с этим счётом (строка потухшая — ждём). */
    private boolean enabledNeed(String p, String what, String key, long need)
    {
        if (this.busy())
        {
            return false;
        }

        for (MaterialLine line : this.lines())
        {
            if (line.key().equals(key))
            {
                if (line.disabled())
                {
                    return false;
                }

                this.k.check(p + T + "сущности: " + what + " включена, «Всего» " + line.need() + " = " + need, line.need() == need);
                return true;
            }
        }

        return false;
    }

    /** Перечитанная схема без стойки: стойка отключена → выгрузка из Litematica → загрузка заново → ждать наложения. */
    private void entityReload(List<DumpSchematics.Step> s)
    {
        String stand = DumpSchematics.id(Items.ARMOR_STAND);
        String E = T + "сущности: после перечитывания: ";
        long[] before = new long[3];

        s.add(step(E + "строка и списки до", ROWS_MS, mc -> this.lineBefore(stand, before) && this.rememberEntities("")));
        s.add(step(E + "стойка отключена (ядро)", 3_000L, mc -> this.coreDisableItem("", Items.ARMOR_STAND)));
        s.add(step(E + "выгрузить из Litematica", 5_000L, mc -> this.entityUnload()));
        s.add(step(E + "Litematica загрузила снова — стойки нет", 10_000L, mc -> this.entityReloadCheck(false)));
        s.add(step(E + "«перезапуск» (файл замен с диска) — стойки нет", 10_000L, mc -> this.entityReloadCheck(true)));
        s.add(step(E + "включение: стойка вернулась в обе схемы, в файле записи нет", 10_000L, mc -> this.entityReenable()));
    }

    /** Счёт строки до отключения — без проверок позиций (их считает rememberRow для таблицы схемы). */
    private boolean lineBefore(String key, long[] out)
    {
        return this.rememberRow("", key, out);
    }

    private boolean entityUnload()
    {
        if (this.placement != null && PlacementOps.all().contains(this.placement))
        {
            PlacementOps.remove(this.placement);
        }

        this.placement = null;
        removeFromHolder(this.file);
        this.k.check(T + "сущности: схемы файла нет в «Загруженных схемах»", ! inHolder(this.file));
        this.k.check(T + "сущности: в старом объекте стойки нет",
                kindCount(this.schematic, SchematicEntities.Kind.ARMOR_STAND) == 0L);
        this.entLoaded = null;
        return true;
    }

    private boolean entityReloadCheck(boolean restart)
    {
        Path file = this.file;

        if (file == null)
        {
            this.k.fail(T + "сущности: нет файла");
            return true;
        }

        String how = restart ? "после «перезапуска»" : "Litematica сама";

        if (this.entLoaded == null)
        {
            if (restart)
            {
                removeFromHolder(file);
                BlockReplacements.TestHooks.reloadStore();
            }

            LitematicaSchematic loaded = SchematicHolder.getInstance().getOrLoad(file);

            if (! this.k.check(T + "сущности (" + how + "): схема загружена новым объектом", loaded != null && loaded != this.schematic))
            {
                return true;
            }

            this.fresh.add(loaded);
            this.entLoaded = loaded;
            this.entLast = loaded;
        }

        // Наложит такт клиента (опрос загруженных схем): ждём, пока стойка уйдёт из списков
        if (kindCount(this.entLoaded, SchematicEntities.Kind.ARMOR_STAND) != 0L)
        {
            return false;
        }

        List<String> entities = BlockReplacements.TestHooks.storedEntities(file);
        this.k.check(T + "сущности (" + how + "): стойки нет, в файле замен она есть (" + entities + ")",
                entities != null && entities.contains("minecraft:armor_stand"));
        this.k.check(T + "сущности (" + how + "): рамки на месте",
                typeCount(this.entLoaded, "minecraft:item_frame") == this.entFramesBefore);
        this.entLoaded = null;
        return true;
    }

    /** Включить стойку и убедиться, что она вернулась и в своём объекте, и в перечитанном, а запись из файла ушла. */
    private boolean entityReenable()
    {
        LitematicaSchematic schematic = this.schematic;
        Path file = this.file;

        if (schematic == null || file == null)
        {
            return true;
        }

        if (! BlockReplacements.disabledEntityItems(schematic).isEmpty())
        {
            BlockReplacements.Outcome outcome = BlockReplacements.enable(schematic, List.of(), List.of(Items.ARMOR_STAND));
            this.k.check(T + "сущности: включение ядром — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        }

        if (kindCount(schematic, SchematicEntities.Kind.ARMOR_STAND) < 1L
                || this.entLast != null && kindCount(this.entLast, SchematicEntities.Kind.ARMOR_STAND) < 1L)
        {
            return false;
        }

        List<String> entities = BlockReplacements.TestHooks.storedEntities(file);
        this.k.check(T + "сущности: в файле замен стойки больше нет (" + entities + ")", entities == null || ! entities.contains("minecraft:armor_stand"));
        this.k.check(T + "сущности: списки своего объекта те же, что были", sameLists(entityLists(schematic), this.entBefore));
        removeFromHolder(file);
        this.entLast = null;
        return true;
    }

    /** «Список HUD»: стойка отключается из меню строки, оверлей HUD перестаёт её считать, золотая кнопка возвращает. */
    private @Nullable MaterialLine hudStand;
    private int hudStandCount;

    private void hudEntity(List<DumpSchematics.Step> s)
    {
        String stand = DumpSchematics.id(Items.ARMOR_STAND);
        String off = I18n.get("layerlist.schematics.materials.disabled.menu.off");
        String H = T + "«Список HUD»: стойка: ";

        s.add(step(H + "строка и счёт в оверлее до", ROWS_MS, mc -> this.hudStandBefore()));
        s.add(step(H + "строка на виду", 3_000L, mc -> this.revealKey(stand)));
        s.add(step(H + "ПКМ по строке", 3_000L, this.k.settleThen(300L, mc -> this.rightClickRow(stand))));
        s.add(step(H + "в меню есть «Отключить блок»", 3_000L, mc -> this.menuHas(off)));
        s.add(step(H + "«Отключить блок» из меню", 3_000L, mc -> this.chooseMenu(off)));
        s.add(step(H + "строка потухла, тот же счёт", ROWS_MS, mc -> this.greyed("", "стойка (HUD)", stand, this.hudStand == null ? 0L : this.hudStand.need())));
        s.add(step(H + "пересчёт HUD", 3_000L, mc ->
        {
            LayerCountManager.getInstance().markDirty();
            return true;
        }));
        s.add(step(H + "в оверлее стойки больше нет", 30_000L, mc -> this.hudStandGone()));
        s.add(step(H + "потухшая строка на виду", 3_000L, mc -> this.revealKey(stand)));
        s.add(step("снимок: «Список HUD» — отключённая стойка", 5_000L, this.k.settleShot("hud-entity-disabled")));
        s.add(step(H + "золотая кнопка", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(stand))));
        s.add(step(H + "строка включена, тот же счёт", ROWS_MS,
                mc -> this.enabledNeed("", "стойка (HUD)", stand, this.hudStand == null ? 0L : this.hudStand.need())));
        s.add(step(H + "пересчёт HUD после включения", 3_000L, mc ->
        {
            LayerCountManager.getInstance().markDirty();
            return true;
        }));
        s.add(step(H + "в оверлее стойка снова считается", 30_000L, mc -> this.hudStandBack()));
    }

    /** Сколько стоек считает оверлей HUD (сумма «всего» по строкам со стойкой). */
    private static int hudStands()
    {
        int total = 0;

        for (fi.dy.masa.litematica.materials.MaterialListEntry entry : LayerCountManager.getInstance().getEntries())
        {
            if (entry.getStack().is(Items.ARMOR_STAND))
            {
                total += entry.getCountTotal();
            }
        }

        return total;
    }

    private boolean hudStandBefore()
    {
        MaterialLine line = this.line(Items.ARMOR_STAND);

        if (this.busy() || line == null || this.schematic == null || ! LayerCountManager.getInstance().hasData() || hudStands() <= 0)
        {
            return false;
        }

        this.hudStand = line;
        this.hudStandCount = hudStands();
        BlockReplacements.RowState state = BlockReplacements.hudRows(List.of(line.row()), BlockReplacements.hudSchematics()).get(line.key());
        this.k.check(T + "«Список HUD»: стойку можно отключить", state != null && state.disableable());
        this.k.ok(T + "«Список HUD»: стоек в оверлее " + this.hudStandCount + ", в строке " + line.need());
        return true;
    }

    private boolean hudStandGone()
    {
        if (! LayerCountManager.getInstance().hasData() || hudStands() != 0)
        {
            return false;
        }

        this.k.ok(T + "«Список HUD»: стойки в оверлее нет");
        return true;
    }

    private boolean hudStandBack()
    {
        if (! LayerCountManager.getInstance().hasData() || hudStands() == 0)
        {
            return false;
        }

        this.k.check(T + "«Список HUD»: стоек в оверлее " + hudStands() + " = до отключения " + this.hudStandCount,
                hudStands() == this.hudStandCount);
        return true;
    }

    // ===== Проверки контейнера =====

    private static void snapshot(LitematicaSchematic schematic, Map<String, BlockState[]> cells,
            Map<String, List<BlockState>> palettes, Map<String, long[]> arrays)
    {
        for (String name : List.copyOf(schematic.getAreas().keySet()))
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);

            if (container == null)
            {
                continue;
            }

            cells.put(name, cells(container));
            palettes.put(name, palette(container.getPalette()));
            arrays.put(name, container.getBackingLongArray().clone());
        }
    }

    private static BlockState[] cells(LitematicaBlockStateContainer container)
    {
        Vec3i size = container.getSize();
        BlockState[] out = new BlockState[size.getX() * size.getY() * size.getZ()];
        int i = 0;

        for (int y = 0; y < size.getY(); ++y)
        {
            for (int z = 0; z < size.getZ(); ++z)
            {
                for (int x = 0; x < size.getX(); ++x)
                {
                    out[i++] = container.get(x, y, z);
                }
            }
        }

        return out;
    }

    private static List<BlockState> palette(ILitematicaBlockStatePalette palette)
    {
        List<BlockState> out = new ArrayList<>();

        for (int id = 0; id < palette.getPaletteSize(); ++id)
        {
            out.add(palette.getBlockState(id));
        }

        return out;
    }

    private long originalCount(Block block)
    {
        long count = 0L;

        for (BlockState[] cells : this.cellsBefore.values())
        {
            for (BlockState state : cells)
            {
                if (state.is(block))
                {
                    ++count;
                }
            }
        }

        return count;
    }

    /**
     * Клетки схемы {@code schematic} (по умолчанию k-generated) — исходные с заменами {@code mapping}:
     * заменённые клетки — новый блок с перенесёнными свойствами, прочие — ровно исходное состояние.
     */
    private boolean expect(String what, Map<Block, Block> mapping, boolean countReplaced)
    {
        return this.expectOn(this.schematic, what, mapping, countReplaced);
    }

    private boolean expectOn(@Nullable LitematicaSchematic schematic, String what, Map<Block, Block> mapping, boolean countReplaced)
    {
        if (schematic == null || this.cellsBefore.isEmpty())
        {
            this.k.fail(T + what + ": нет схемы или исходного снимка");
            return true;
        }

        int wrong = 0;
        int replaced = 0;
        String first = "";

        for (Map.Entry<String, BlockState[]> region : this.cellsBefore.entrySet())
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(region.getKey());

            if (container == null)
            {
                this.k.fail(T + what + ": нет региона " + region.getKey());
                return true;
            }

            BlockState[] now = cells(container);
            BlockState[] was = region.getValue();

            for (int i = 0; i < was.length; ++i)
            {
                Block to = mapping.get(was[i].getBlock());
                BlockState expected = to == null ? was[i] : ReplacementStates.carry(was[i], to);

                if (to != null)
                {
                    ++replaced;
                }

                if (i >= now.length || now[i] != expected)
                {
                    if (wrong++ == 0)
                    {
                        first = " (клетка " + i + ": ждали " + expected + ", стоит " + (i < now.length ? now[i] : "—") + ")";
                    }
                }
            }
        }

        this.k.check(T + what + ": клеток не как ждали — 0" + first, 0, wrong);

        if (countReplaced)
        {
            long expected = 0L;

            for (Block from : mapping.keySet())
            {
                expected += this.originalCount(from);
            }

            this.k.check(T + what + ": заменено клеток", expected, (long) replaced);
        }

        return true;
    }

    /** Клетки, где были дубовые ступеньки, — {@code to} с тем же facing и half. */
    private boolean stairsCarried(Block to)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return true;
        }

        int checked = 0;
        int wrong = 0;

        for (Map.Entry<String, BlockState[]> region : this.cellsBefore.entrySet())
        {
            BlockState[] now = cells(schematic.getSubRegionContainer(region.getKey()));
            BlockState[] was = region.getValue();

            for (int i = 0; i < was.length; ++i)
            {
                if (! was[i].is(Blocks.OAK_STAIRS))
                {
                    continue;
                }

                ++checked;
                Direction facing = was[i].getValue(StairBlock.FACING);
                Half half = was[i].getValue(StairBlock.HALF);

                if (! now[i].is(to) || now[i].getValue(StairBlock.FACING) != facing || now[i].getValue(StairBlock.HALF) != half)
                {
                    ++wrong;
                }
            }
        }

        this.k.check(T + "ступеньки → " + ReplacementStates.shortId(to) + ": facing/half перенесены у " + checked
                + " клеток, не так — 0", 0, wrong);
        return true;
    }

    /** Откат последней замены: клетки, палитры и упакованные массивы регионов — побайтно как до первой. */
    private boolean restored(String p)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            this.k.fail(p + T + "откат: нет схемы");
            return true;
        }

        this.expect(p + "откат", Map.of(), false);
        Map<String, BlockState[]> cells = new LinkedHashMap<>();
        Map<String, List<BlockState>> palettes = new LinkedHashMap<>();
        Map<String, long[]> arrays = new LinkedHashMap<>();
        snapshot(schematic, cells, palettes, arrays);

        boolean palettesSame = true;
        boolean arraysSame = true;

        for (String name : this.palettesBefore.keySet())
        {
            List<BlockState> was = this.palettesBefore.get(name);
            List<BlockState> now = palettes.get(name);
            palettesSame &= now != null && now.size() == was.size();

            for (int i = 0; palettesSame && i < was.size(); ++i)
            {
                palettesSame = now.get(i) == was.get(i);
            }

            arraysSame &= Arrays.equals(this.arraysBefore.get(name), arrays.get(name));
        }

        this.k.check(p + T + "откат: палитры регионов те же (по номерам)", palettesSame);
        this.k.check(p + T + "откат: упакованные массивы клеток побайтно те же", arraysSame);
        this.k.check(p + T + "откат: замен у схемы нет", BlockReplacements.active(schematic).isEmpty());
        return true;
    }

    // ===== Отключение на слое (1.0.12) =====

    private static final String L = "слой: ";
    private @Nullable Item layerItem;
    private String layerKey = "";
    /** Выбранный слой (0 — нижний), клеток блока строки на нём, «Всего» строки до отключения. */
    private int layerIndex = -1;
    private long layerCells;
    private long layerNeedBefore;
    private long layerWorldBefore;
    /** Второй отключённый слой и его клетки; {@code -1} — второго слоя с этим блоком нет. */
    private int layerOther = -1;
    private long layerOtherCells;

    /**
     * Отключение блока на одном слое (просьба 14.10.2026: «поле ввода и ползунок по числу слоёв схемы»). ПКМ по
     * строке → «Отключить на слое…» с клавиатуры → окно: ползунок 1…слоёв схемы, номер — печатью в поле, колесо,
     * число за краем; «Отключить» — в клетках этого слоя воздух, остальные клетки побайтно прежние; строка с
     * пометкой и золотой кнопкой; голограмма; файл замен; схема, перечитанная с диска; второй слой; кнопка
     * возвращает схему побайтно; вместе с заменой — «Сброс» снимает всё.
     */
    private void layering(List<DumpSchematics.Step> s)
    {
        s.add(step(T + L + "строка и слой", ROWS_MS, mc -> this.pickLayer()));
        s.add(step(T + L + "строка на виду", 3_000L, mc -> this.revealKey(this.layerKey)));
        s.add(step(T + L + "ПКМ по строке", 3_000L, this.k.settleThen(300L, mc -> this.rightClickRow(this.layerKey))));
        s.add(step(T + L + "пункт «Отключить на слое…»", 3_000L, mc -> this.chooseMenu(I18n.get("layerlist.schematics.materials.layer.menu"))));
        s.add(step(T + L + "окно открыто: ползунок по слоям схемы", 3_000L, mc -> this.dialogOpened()));
        s.add(step(T + L + "номер печатью в поле", 3_000L, this.k.settleThen(200L, mc -> this.typeLayer(this.layerIndex + 1))));
        s.add(step(T + L + "поле и ползунок заодно", 3_000L, mc -> this.pickerAt(this.layerIndex + 1, true)));
        s.add(step(T + L + "колесо над полем", 3_000L, mc -> this.wheel()));
        s.add(step(T + L + "число за краем схемы", 3_000L, mc -> this.beyond()));
        s.add(step(T + L + "номер снова", 3_000L, this.k.settleThen(200L, mc -> this.typeLayer(this.layerIndex + 1))));
        s.add(step(T + L + "снова на слое", 3_000L, mc -> this.pickerAt(this.layerIndex + 1, true)));
        s.add(step("снимок: окно «Отключить на слое»", 5_000L, this.k.settleShot("layer-dialog")));
        s.add(step(T + L + "«Отключить»", 3_000L, this.k.settleThen(200L, mc -> this.clickDialogButton("layerlist.schematics.materials.layer.disable"))));
        s.add(step(T + L + "в клетках слоя воздух, прочие как были", 3_000L, mc -> this.layerCells(Map.of(this.layerIndex, true))));
        s.add(step(T + L + "строка: счёт меньше, пометка, кнопка", ROWS_MS, mc -> this.layerRow(List.of(this.layerIndex + 1), this.layerCells)));
        s.add(step(T + L + "голограмма без блока на слое", REBUILD_MS, mc -> this.layerWorld(this.layerWorldBefore - this.layerCells)));
        s.add(step(T + L + "файл замен", 3_000L, mc -> this.layerStored(List.of(this.layerIndex))));
        s.add(step(T + L + "строка на виду перед снимком", 3_000L, mc -> this.revealKey(this.layerKey)));
        s.add(step("снимок: строка, отключённая на слое", 5_000L, this.k.settleThen(300L, mc -> this.hoverRevertKey(this.layerKey))));
        s.add(step("снимок: строка со слоем", 5_000L, this.k.settleShot("layer-row")));
        s.add(step(T + L + "схема, перечитанная с диска", 10_000L, mc -> this.layerReread()));
        s.add(step(T + L + "второй слой ядром, подпись диапазоном", ROWS_MS, mc -> this.secondLayer()));
        s.add(step(T + L + "строка с двумя слоями (и при счёте 0 — на месте)", ROWS_MS, mc -> this.layerOther < 0
                || this.layerRow(java.util.stream.Stream.of(this.layerIndex + 1, this.layerOther + 1).sorted().toList(),
                        this.layerCells + this.layerOtherCells)));
        // После второго слоя строка встала ниже по счёту — сперва прокрутить к ней
        s.add(step(T + L + "строка на виду перед кнопкой", ROWS_MS, mc -> this.revealKey(this.layerKey)));
        s.add(step(T + L + "золотая кнопка", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(this.layerKey))));
        s.add(step(T + L + "кнопка: строка без слоёв", ROWS_MS, mc -> this.layerRow(List.of(), 0L)));
        s.add(step(T + L + "кнопка: схема побайтно как была", 3_000L, mc -> this.restored("")));
        s.add(step(T + L + "кнопка: голограмма как была", REBUILD_MS, mc -> this.layerWorld(this.layerWorldBefore)));
        s.add(step(T + L + "слой и замена, потом «Сброс»", 3_000L, mc -> this.layerAndReplace()));
        s.add(step(T + L + "«Сброс»: клик", 3_000L, this.k.settleThen(300L, mc -> this.clickReset())));
        s.add(step(T + L + "«Сброс»: схема побайтно как была", ROWS_MS, mc -> ! this.busy() && this.restored("")));
        s.add(step(T + L + "«Сброс»: записи в файле замен нет", 3_000L, mc -> this.stored(null)));
        // «Отключить на текущем слое (N)» (1.0.14, просьба Arkadiusz): слой — тот, что показывает список HUD
        s.add(step(T + L + "текущий слой: Litematica «один слой»", 3_000L, mc -> this.singleLayerHere(true)));
        s.add(step(T + L + "текущий слой: строка на виду", 3_000L, mc -> this.revealKey(this.layerKey)));
        s.add(step(T + L + "текущий слой: ПКМ по строке", 3_000L, this.k.settleThen(300L, mc -> this.rightClickRow(this.layerKey))));
        s.add(step(T + L + "пункт «Отключить на текущем слое (N)»", 3_000L,
                mc -> this.chooseMenu(I18n.get("layerlist.schematics.materials.layer.menu.here", this.layerIndex + 1))));
        s.add(step(T + L + "текущий слой: в клетках слоя воздух, прочие как были", 3_000L, mc -> this.layerCells(Map.of(this.layerIndex, true))));
        s.add(step(T + L + "текущий слой: строка с пометкой", ROWS_MS, mc -> this.layerRow(List.of(this.layerIndex + 1), this.layerCells)));
        s.add(step(T + L + "текущий слой: строка на виду перед кнопкой", ROWS_MS, mc -> this.revealKey(this.layerKey)));
        s.add(step(T + L + "текущий слой: золотая кнопка", 3_000L, this.k.settleThen(300L, mc -> this.clickRevertKey(this.layerKey))));
        s.add(step(T + L + "текущий слой: схема побайтно как была", ROWS_MS, mc -> ! this.busy() && this.restored("")));
        // Окно «Отключить на слое…» открывается на слое, где игрок строил: режим «все слои», блок поставлен в схему
        s.add(step(T + L + "слой стройки: «все слои», блок поставлен в схему", 3_000L, mc -> this.placeOnLayer(mc)));
        s.add(step(T + L + "слой стройки: строка на виду", 3_000L, mc -> this.revealKey(this.layerKey)));
        s.add(step(T + L + "слой стройки: ПКМ по строке", 3_000L, this.k.settleThen(300L, mc -> this.rightClickRow(this.layerKey))));
        s.add(step(T + L + "слой стройки: пункт «на текущем слое» с его номером", 3_000L, mc -> this.menuHas(
                I18n.get("layerlist.schematics.materials.layer.menu.here", this.layerIndex + 1))));
        s.add(step(T + L + "слой стройки: «Отключить на слое…»", 3_000L, mc -> this.chooseMenu(I18n.get("layerlist.schematics.materials.layer.menu"))));
        s.add(step(T + L + "слой стройки: окно открыто", 3_000L, mc -> this.dialogOpened(this.layerIndex + 1, "слоя последнего поставленного блока")));
        s.add(step(T + L + "слой стройки: окно на слое последнего блока", 3_000L, mc -> this.pickerAt(this.layerIndex + 1, true)));
        s.add(step(T + L + "слой стройки: «Отмена»", 3_000L, this.k.settleThen(200L, mc -> this.clickDialogButton("layerlist.schematics.cancel"))));
        s.add(step(T + L + "слой стройки: блок убран", 3_000L, mc -> this.unplace(mc)));
        s.add(step(T + L + "режим слоёв как был", 3_000L, mc -> this.singleLayerHere(false)));
    }

    private @Nullable net.minecraft.core.BlockPos placedSpot;
    private @Nullable net.minecraft.world.level.block.state.BlockState placedWas;

    /** Litematica «все слои» и настоящий BlockItem.place на клиенте в клетку выбранного слоя внутри размещения. */
    private boolean placeOnLayer(net.minecraft.client.Minecraft mc)
    {
        LitematicaSchematic schematic = this.schematic;
        var placements = schematic == null ? List.<fi.dy.masa.litematica.schematic.placement.SchematicPlacement>of()
                : fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic);

        if (placements.isEmpty() || mc.level == null || mc.player == null)
        {
            this.k.fail(T + L + "слой стройки: нет размещения или мира");
            return true;
        }

        fi.dy.masa.litematica.data.DataManager.getRenderLayerRange().setLayerMode(fi.dy.masa.malilib.util.LayerMode.ALL);
        var placement = placements.get(0);
        var box = placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values().iterator().next();
        int y = placement.getOrigin().getY() + BlockReplacements.layerBase(schematic) + this.layerIndex;
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(Math.min(box.getPos1().getX(), box.getPos2().getX()), y,
                Math.min(box.getPos1().getZ(), box.getPos2().getZ()));
        this.placedSpot = pos;
        this.placedWas = mc.level.getBlockState(pos);
        mc.level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        net.minecraft.world.item.ItemStack stone = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
        var result = ((net.minecraft.world.item.BlockItem) net.minecraft.world.item.Items.STONE).place(new net.minecraft.world.item.context.BlockPlaceContext(
                mc.level, mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, stone, hit));
        this.k.check(T + L + "слой стройки: блок поставлен (" + result + ")", result.consumesAction());
        this.k.check(T + L + "слой стройки: запомнена клетка", pos, com.aell.layerlist.schematics.materials.BuildSpot.last());
        return true;
    }

    private boolean unplace(net.minecraft.client.Minecraft mc)
    {
        if (this.placedSpot != null && this.placedWas != null && mc.level != null)
        {
            mc.level.setBlock(this.placedSpot, this.placedWas, 3);
        }

        return true;
    }

    /** В открытом контекстном меню есть активный пункт с такой подписью. */
    private boolean menuHas(String label)
    {
        Layers layers = this.layers();

        if (layers == null || ! (layers.top() instanceof com.aell.layerlist.ui.kit.ContextMenu menu))
        {
            return false;
        }

        List<com.aell.layerlist.ui.kit.ContextMenu.Item> items = KProbe.fieldOfType(menu, List.class);
        boolean found = items.stream().anyMatch(i -> ! i.isSeparator() && i.enabled() && label.equals(i.label()));
        this.k.check(T + L + "в меню есть «" + label + "»", found);
        return true;
    }

    private fi.dy.masa.malilib.util.@Nullable LayerMode savedMode;
    private int savedSingle;
    private net.minecraft.core.Direction.@Nullable Axis savedAxis;

    /** Включить Litematica «один слой» по Y на выбранном слое схемы (или вернуть прежний режим). */
    private boolean singleLayerHere(boolean on)
    {
        fi.dy.masa.malilib.util.position.LayerRange range = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();

        if (! on)
        {
            if (this.savedMode != null && this.savedAxis != null)
            {
                range.setAxis(this.savedAxis);
                range.setLayerSingle(this.savedSingle);
                range.setLayerMode(this.savedMode);
            }

            return true;
        }

        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.layerIndex < 0)
        {
            this.k.fail(T + L + "текущий слой: нет схемы или слоя");
            return true;
        }

        var placements = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllPlacementsOfSchematic(schematic);

        if (placements.isEmpty())
        {
            this.k.fail(T + L + "текущий слой: схема не размещена");
            return true;
        }

        this.savedMode = range.getLayerMode();
        this.savedSingle = range.getLayerSingle();
        this.savedAxis = range.getAxis();
        int y = placements.get(0).getOrigin().getY() + BlockReplacements.layerBase(schematic) + this.layerIndex;
        range.setAxis(net.minecraft.core.Direction.Axis.Y);
        range.setLayerMode(fi.dy.masa.malilib.util.LayerMode.SINGLE_LAYER);
        range.setLayerSingle(y);
        return true;
    }

    /** Строка таблицы, у блока которой есть слой с частью его клеток (на других слоях он тоже есть). */
    private boolean pickLayer()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.busy())
        {
            return schematic == null;
        }

        int count = BlockReplacements.layerCount(schematic);
        this.k.check(T + L + "слоёв в схеме = высоте схемы", Math.abs(schematic.getTotalSize().getY()), count);

        for (MaterialLine line : this.lines())
        {
            if (line.disabled() || ! (line.stack().getItem() instanceof BlockItem))
            {
                continue;
            }

            // Слой повыше нижнего: отступ региона по высоте тоже проверяется
            for (int layer = count - 1; layer >= 0; --layer)
            {
                long cells = BlockReplacements.countOnLayer(schematic, line.stack().getItem(), layer);

                if (cells > 0L && cells < line.need())
                {
                    this.layerItem = line.stack().getItem();
                    this.layerKey = line.key();
                    this.layerIndex = layer;
                    this.layerCells = cells;
                    this.layerNeedBefore = line.need();
                    this.layerWorldBefore = this.worldCount(((BlockItem) this.layerItem).getBlock());
                    this.k.ok(T + L + line.name() + ", слой " + (layer + 1) + " из " + count + ": " + cells + " из " + line.need()
                            + ", в голограмме " + this.layerWorldBefore);
                    return true;
                }
            }
        }

        this.k.fail(T + L + "в k-generated нет блока, что стоит на нескольких слоях");
        return true;
    }

    private boolean rightClickRow(String key)
    {
        ReplaceCell cell = this.cell();
        int[] box = cell == null ? null : cell.cellBox(key);
        com.aell.layerlist.schematics.gui.GuiSchematics gui = this.k.gui();

        if (box == null || gui == null)
        {
            return false;
        }

        net.minecraft.client.input.MouseButtonEvent click = new net.minecraft.client.input.MouseButtonEvent(box[0] + box[2] / 3.0,
                box[1] + box[3] / 2.0, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
        gui.onMouseClicked(click, false);
        gui.onMouseReleased(click);
        return true;
    }

    /** Пункт открытого меню — с клавиатуры: ↓ до него, Enter. */
    private boolean chooseMenu(String label)
    {
        Layers layers = this.layers();
        com.aell.layerlist.schematics.gui.GuiSchematics gui = this.k.gui();

        if (layers == null || gui == null || ! (layers.top() instanceof com.aell.layerlist.ui.kit.ContextMenu menu))
        {
            return false;
        }

        List<com.aell.layerlist.ui.kit.ContextMenu.Item> items = KProbe.fieldOfType(menu, List.class);
        int steps = 0;
        boolean found = false;

        for (com.aell.layerlist.ui.kit.ContextMenu.Item item : items)
        {
            if (item.isSeparator() || ! item.enabled())
            {
                continue;
            }

            ++steps;

            if (item.label().equals(label))
            {
                found = true;
                break;
            }
        }

        this.k.check(T + L + "в меню строки есть «" + label + "»", found);

        for (int i = 0; found && i < steps; ++i)
        {
            KProbe.press(gui, InputConstants.KEY_DOWN);
        }

        KProbe.press(gui, found ? InputConstants.KEY_RETURN : InputConstants.KEY_ESCAPE);
        return true;
    }

    private @Nullable com.aell.layerlist.ui.kit.Modal layerModal()
    {
        Layers layers = this.layers();
        return layers != null && layers.top() instanceof com.aell.layerlist.ui.kit.Modal modal ? modal : null;
    }

    /** Поле и ползунок окна: {поле, ползунок, строка счёта}. */
    private @Nullable Object[] layerPicker()
    {
        com.aell.layerlist.ui.kit.Modal modal = this.layerModal();

        for (com.aell.layerlist.ui.kit.FormRow row : KProbe.fieldsOfType(modal, List.class).stream()
                .flatMap(list -> ((List<?>) list).stream())
                .map(item -> KProbe.recordComponent(item, com.aell.layerlist.ui.kit.Widget.class))
                .filter(widget -> widget instanceof com.aell.layerlist.ui.kit.FormRow)
                .map(widget -> (com.aell.layerlist.ui.kit.FormRow) widget).toList())
        {
            Object control = row.control();
            TextInput field = KProbe.fieldOfType(control, TextInput.class);
            com.aell.layerlist.ui.kit.Slider slider = KProbe.fieldOfType(control, com.aell.layerlist.ui.kit.Slider.class);

            if (field != null && slider != null)
            {
                Object info = KProbe.fieldNamedType(control, "IntConsumer");
                return new Object[] { field, slider, info };
            }
        }

        return null;
    }

    private boolean dialogOpened()
    {
        return this.dialogOpened(1, "нижнего слоя (Litematica — все слои, ничего не строили)");
    }

    private boolean dialogOpened(int start, String what)
    {
        com.aell.layerlist.ui.kit.Modal modal = this.layerModal();
        Object[] picker = this.layerPicker();

        if (modal == null || picker == null || this.schematic == null)
        {
            return false;
        }

        MaterialLine line = this.lines().stream().filter(l -> l.key().equals(this.layerKey)).findFirst().orElse(null);
        String title = KProbe.fieldOfType(modal, String.class);
        this.k.check(T + L + "заголовок окна", I18n.get("layerlist.schematics.materials.layer.title", line == null ? "?" : line.name()), title);
        com.aell.layerlist.ui.kit.Slider slider = (com.aell.layerlist.ui.kit.Slider) picker[1];
        int count = BlockReplacements.layerCount(this.schematic);
        this.k.check(T + L + "ползунок от 1", 1, slider.min());
        this.k.check(T + L + "ползунок до числа слоёв схемы", count, slider.max());
        this.k.check(T + L + "начинает с " + what, start, slider.value());
        this.k.check(T + L + "в поле — тот же номер", String.valueOf(start), ((TextInput) picker[0]).text());
        return true;
    }

    /** Клик в поле, стереть, напечатать номер. */
    private boolean typeLayer(int layer)
    {
        Object[] picker = this.layerPicker();

        if (picker == null)
        {
            return false;
        }

        TextInput field = (TextInput) picker[0];
        KInline.realClick(field.x() + field.w() / 2.0, field.y() + field.h() / 2.0);

        for (int i = 0; i < 6; ++i)
        {
            press(InputConstants.KEY_BACKSPACE);
        }

        type(String.valueOf(layer));
        return true;
    }

    /** Поле и ползунок показывают {@code layer}; кнопка «Отключить» активна, если на слое есть блок. */
    private boolean pickerAt(int layer, boolean enabled)
    {
        Object[] picker = this.layerPicker();

        if (picker == null)
        {
            return false;
        }

        this.k.check(T + L + "ползунок на слое " + layer, layer, ((com.aell.layerlist.ui.kit.Slider) picker[1]).value());
        this.k.check(T + L + "в поле " + layer, String.valueOf(layer), ((TextInput) picker[0]).text());
        Button disable = this.dialogButton("layerlist.schematics.materials.layer.disable");
        this.k.check(T + L + "«Отключить» " + (enabled ? "активна" : "неактивна"), enabled, disable != null && disable.enabled());
        String info = this.infoText();
        this.k.check(T + L + "строка счёта: «" + info + "»", info.contains(Tr("materials.layer.count", layer, com.aell.layerlist.ui.kit.Kit.group(this.layerCells, I18n.get("layerlist.schematics.num_group")))));
        return true;
    }

    private static String Tr(String suffix, Object... args)
    {
        return I18n.get("layerlist.schematics." + suffix, args);
    }

    private String infoText()
    {
        com.aell.layerlist.ui.kit.Modal modal = this.layerModal();

        for (Object list : KProbe.fieldsOfType(modal, List.class))
        {
            for (Object row : (List<?>) list)
            {
                Object widget = KProbe.recordComponent(row, com.aell.layerlist.ui.kit.Widget.class);

                if (widget != null && widget.getClass().getSimpleName().equals("Info"))
                {
                    String text = KProbe.fieldOfType(widget, String.class);
                    return text == null ? "" : text;
                }
            }
        }

        return "";
    }

    private @Nullable Button dialogButton(String langKey)
    {
        String label = I18n.get(langKey);

        for (Object list : KProbe.fieldsOfType(this.layerModal(), List.class))
        {
            for (Object item : (List<?>) list)
            {
                if (item instanceof Button button && label.equals(KProbe.fieldOfType(button, String.class)))
                {
                    return button;
                }
            }
        }

        return null;
    }

    private boolean clickDialogButton(String langKey)
    {
        Button button = this.dialogButton(langKey);

        if (button == null)
        {
            this.k.fail(T + L + "нет кнопки «" + I18n.get(langKey) + "»");
            return true;
        }

        KInline.realClick(button.x() + button.w() / 2.0, button.y() + button.h() / 2.0);
        return true;
    }

    /** Колесо над полем: на слой выше и обратно. */
    private boolean wheel()
    {
        Object[] picker = this.layerPicker();
        com.aell.layerlist.schematics.gui.GuiSchematics gui = this.k.gui();

        if (picker == null || gui == null)
        {
            return false;
        }

        TextInput field = (TextInput) picker[0];
        com.aell.layerlist.ui.kit.Slider slider = (com.aell.layerlist.ui.kit.Slider) picker[1];
        int was = slider.value();
        double x = field.x() + field.w() / 2.0;
        double y = field.y() + field.h() / 2.0;
        gui.mouseScrolled(x, y, 0.0, was < slider.max() ? 1.0 : -1.0);
        int moved = slider.value();
        this.k.check(T + L + "колесо сдвинуло слой на один", 1, Math.abs(moved - was));
        this.k.check(T + L + "поле за колесом", String.valueOf(moved), field.text());
        gui.mouseScrolled(x, y, 0.0, moved > was ? -1.0 : 1.0);
        this.k.check(T + L + "колесо обратно", was, slider.value());
        return true;
    }

    /** Число больше числа слоёв: ползунок встаёт на верхний слой. */
    private boolean beyond()
    {
        Object[] picker = this.layerPicker();

        if (picker == null || this.schematic == null)
        {
            return false;
        }

        int count = BlockReplacements.layerCount(this.schematic);
        this.typeLayer(count + 50);
        this.k.check(T + L + "за краем — верхний слой", count, ((com.aell.layerlist.ui.kit.Slider) picker[1]).value());
        return true;
    }

    /** Нижняя клетка региона по высоте, в координатах схемы. */
    private static int regionMinY(LitematicaSchematic schematic, String name)
    {
        BlockPos pos = schematic.getSubRegionPosition(name);
        BlockPos size = schematic.getAreaSize(name);
        int end = pos.getY() + size.getY() + (size.getY() < 0 ? 1 : -1);
        return Math.min(pos.getY(), end);
    }

    /**
     * Клетки схемы против исходных ({@code cellsBefore}): на слоях {@code off} клетки блока строки — воздух,
     * все остальные — как в файле.
     */
    private boolean layerCells(Map<Integer, Boolean> off)
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.layerItem == null)
        {
            this.k.fail(T + L + "нет схемы или строки");
            return true;
        }

        int base = BlockReplacements.layerBase(schematic);
        long air = 0L;
        long wrong = 0L;

        for (String name : this.cellsBefore.keySet())
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            BlockState[] was = this.cellsBefore.get(name);
            Vec3i size = container.getSize();
            int regionY = regionMinY(schematic, name);
            int i = 0;

            for (int y = 0; y < size.getY(); ++y)
            {
                boolean layerOff = off.containsKey(regionY + y - base);

                for (int z = 0; z < size.getZ(); ++z)
                {
                    for (int x = 0; x < size.getX(); ++x, ++i)
                    {
                        BlockState now = container.get(x, y, z);
                        boolean hit = layerOff && was[i].getBlock().asItem() == this.layerItem;
                        BlockState expected = hit ? Blocks.AIR.defaultBlockState() : was[i];

                        if (now != expected && ! (hit && now.isAir()))
                        {
                            ++wrong;
                        }

                        air += hit && now.isAir() ? 1 : 0;
                    }
                }
            }
        }

        this.k.check(T + L + "клеток стало воздухом", this.layerCells * off.size() >= air && air > 0L, air > 0L);
        this.k.check(T + L + "лишних изменений в клетках нет", 0L, wrong);
        return true;
    }

    /** Строка блока: отключённые слои, «Всего» меньше на {@code minus}, изменённая, с золотой кнопкой. */
    private boolean layerRow(List<Integer> layers, long minus)
    {
        if (this.busy())
        {
            return false;
        }

        MaterialLine line = this.lines().stream().filter(l -> l.key().equals(this.layerKey) && ! l.disabled()).findFirst().orElse(null);

        if (line == null || ! line.layersOff().equals(layers))
        {
            return false;
        }

        this.k.check(T + L + "у строки слои " + layers, layers, line.layersOff());
        this.k.check(T + L + "«Всего» строки", this.layerNeedBefore - minus, line.need());
        this.k.check(T + L + "строка " + (layers.isEmpty() ? "не " : "") + "в «Изменённых»", ! layers.isEmpty(), line.changed());
        ReplaceCell cell = this.cell();
        this.revealKey(this.layerKey);
        this.k.check(T + L + "золотая кнопка " + (layers.isEmpty() ? "убрана" : "у строки"), ! layers.isEmpty(),
                cell != null && cell.revertBox(this.layerKey) != null);
        return true;
    }

    private long worldCount(Block block)
    {
        SchematicPlacement p = this.placement;
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        KPlace.Bounds bounds = p == null ? null : KPlace.bounds(p);

        if (world == null || bounds == null)
        {
            return -1L;
        }

        long found = 0L;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = bounds.minY(); y <= bounds.maxY(); ++y)
        {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); ++z)
            {
                for (int x = bounds.minX(); x <= bounds.maxX(); ++x)
                {
                    found += world.getBlockState(pos.set(x, y, z)).is(block) ? 1 : 0;
                }
            }
        }

        return found;
    }

    private boolean layerWorld(long expected)
    {
        if (this.layerItem == null)
        {
            return true;
        }

        long found = this.worldCount(((BlockItem) this.layerItem).getBlock());

        if (found != expected && Util.getMillis() - this.k.startedAt() < REBUILD_MS - 1_000L)
        {
            return false;
        }

        this.k.check(T + L + "в голограмме блоков строки", expected, found);
        return true;
    }

    private boolean layerStored(List<Integer> layers)
    {
        Path file = this.file;

        if (file == null || this.layerItem == null)
        {
            this.k.fail(T + L + "у схемы нет файла");
            return true;
        }

        Map<String, List<Integer>> stored = BlockReplacements.TestHooks.storedLayers(file);
        String id = ReplacementStates.id(((BlockItem) this.layerItem).getBlock()).toString();
        this.k.check(T + L + "в файле замен слои " + id, layers, stored == null ? null : stored.get(id));
        return true;
    }

    private boolean hoverRevertKey(String key)
    {
        ReplaceCell cell = this.cell();
        int[] box = cell == null ? null : cell.revertBox(key);

        if (box == null)
        {
            return false;
        }

        move(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        return true;
    }

    /** Та же схема, прочитанная с диска заново: отключение на слое наложено при чтении. */
    private boolean layerReread()
    {
        Path file = this.file;

        if (file == null || this.layerItem == null)
        {
            return true;
        }

        LitematicaSchematic fresh = LitematicaSchematic.createFromFile(file.getParent(), file.getFileName().toString());

        if (fresh == null)
        {
            this.k.fail(T + L + "схема не прочиталась с диска");
            return true;
        }

        BlockReplacements.ensure(fresh);
        this.fresh.add(fresh);
        this.k.check(T + L + "у перечитанной схемы слой отключён", Map.of(((BlockItem) this.layerItem).getBlock(), java.util.Set.of(this.layerIndex)),
                BlockReplacements.layersOff(fresh));
        this.k.check(T + L + "у перечитанной схемы на слое блока нет", 0L,
                BlockReplacements.countOnLayer(fresh, this.layerItem, this.layerIndex));
        return true;
    }

    /** Второй слой ядром: подпись строки — диапазон, если слои рядом. */
    private boolean secondLayer()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.layerItem == null || this.busy())
        {
            return schematic == null;
        }

        int count = BlockReplacements.layerCount(schematic);
        int other = -1;

        for (int layer = 0; layer < count; ++layer)
        {
            if (layer != this.layerIndex && BlockReplacements.countOnLayer(schematic, this.layerItem, layer) > 0L)
            {
                other = layer;
                break;
            }
        }

        if (other < 0)
        {
            this.k.info(T + L + "второго слоя с этим блоком нет — пропуск");
            return true;
        }

        this.layerOther = other;
        this.layerOtherCells = BlockReplacements.countOnLayer(schematic, this.layerItem, other);
        BlockReplacements.Outcome outcome = BlockReplacements.disableLayer(schematic, this.layerItem, other);
        this.k.check(T + L + "второй слой ядром — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.k.info(T + L + "второй слой " + (other + 1) + ": " + this.layerOtherCells + " клеток, всего у строки останется "
                + (this.layerNeedBefore - this.layerCells - this.layerOtherCells));
        BlockReplacements.Outcome again = BlockReplacements.disableLayer(schematic, this.layerItem, other);
        this.k.check(T + L + "тот же слой второй раз — отказ «блока нет»", ! again.ok());
        this.refreshPane();

        try
        {
            Class<?> pane = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane");
            java.lang.reflect.Method text = pane.getDeclaredMethod("layersText", List.class);
            text.setAccessible(true);
            this.k.check(T + L + "подпись слоёв диапазоном", "3–5, 8", text.invoke(null, List.of(3, 4, 5, 8)));
            this.k.check(T + L + "подпись одного слоя", "7", text.invoke(null, List.of(7)));
        }
        catch (ReflectiveOperationException e)
        {
            this.k.fail(T + L + "layersText: " + e);
        }

        return true;
    }

    /** Отключение на слое и замена того же блока: замена не возвращает клетки слоя; «Сброс» снимает всё. */
    private boolean layerAndReplace()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null || this.layerItem == null || this.busy())
        {
            return schematic == null;
        }

        BlockReplacements.Outcome off = BlockReplacements.disableLayer(schematic, this.layerItem, this.layerIndex);
        Block target = ((BlockItem) this.layerItem).getBlock() == Blocks.STONE ? Blocks.GRANITE : Blocks.STONE;
        BlockReplacements.Outcome swap = BlockReplacements.replace(schematic, this.layerItem, target);
        this.k.check(T + L + "слой и замена ядром — " + off.ok() + "/" + swap.ok(), off.ok() && swap.ok());
        Item targetItem = target.asItem();
        this.k.check(T + L + "после замены на слое блока нет и новым", 0L, BlockReplacements.countOnLayer(schematic, targetItem, this.layerIndex)
                - this.countOriginalOnLayerAs(schematic, targetItem));
        this.refreshPane();
        return true;
    }

    /** Сколько клеток слоя исходно были блоком {@code item} (не строки) — их замена не трогает. */
    private long countOriginalOnLayerAs(LitematicaSchematic schematic, Item item)
    {
        int base = BlockReplacements.layerBase(schematic);
        long count = 0L;

        for (String name : this.cellsBefore.keySet())
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(name);
            BlockState[] was = this.cellsBefore.get(name);
            Vec3i size = container.getSize();
            int localY = base + this.layerIndex - regionMinY(schematic, name);

            if (localY < 0 || localY >= size.getY())
            {
                continue;
            }

            int from = localY * size.getX() * size.getZ();

            for (int i = from; i < from + size.getX() * size.getZ(); ++i)
            {
                count += was[i].getBlock().asItem() == item ? 1 : 0;
            }
        }

        return count;
    }

    // ===== Строки таблицы =====

    /**
     * Пересчитанные строки: у строки {@code item} пометка «← исходные» ровно {@code from}; строки
     * {@code gone} нет. Ждёт, пока пересчёт закончится.
     */
    private boolean replacedRow(String p, Item item, List<Block> from, @Nullable Item gone)
    {
        return this.replacedRow(p, item, from, gone, true);
    }

    /** {@code selectedNew} — после замены таблица выбрала строку нового блока (а не после смены источника). */
    private boolean replacedRow(String p, Item item, List<Block> from, @Nullable Item gone, boolean selectedNew)
    {
        if (this.busy())
        {
            return false;
        }

        MaterialLine line = this.line(item);

        if (line == null || (gone != null && this.line(gone) != null))
        {
            return false;
        }

        List<String> names = new ArrayList<>();
        from.forEach(block -> names.add(ReplacementStates.name(block)));
        this.k.check(p + T + "строка «" + line.name() + "»: пометка замены", names, line.replacedFrom());

        Table<MaterialLine> table = this.table();
        MaterialLine selected = table == null ? null : table.selectedItem();

        if (! from.isEmpty() && selectedNew)
        {
            this.k.check(p + T + "выбрана строка нового блока", line.key(), selected == null ? null : selected.key());
        }

        return true;
    }

    private boolean need(Item item, long expected)
    {
        MaterialLine line = this.line(item);
        this.k.check(T + "«" + this.lineName(item) + "»: всего", expected, line == null ? -1L : line.need());
        return true;
    }

    // ===== Голограмма =====

    /** В мире схемы Litematica в рамке размещения: {@code a} — {@code countA} шт, {@code b} — {@code countB}. */
    private boolean world(Block a, long countA, Block b, long countB)
    {
        SchematicPlacement p = this.placement;
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        KPlace.Bounds bounds = p == null ? null : KPlace.bounds(p);

        if (p == null || world == null || bounds == null)
        {
            this.k.fail(T + "голограмма: нет размещения или мира схемы");
            return true;
        }

        long foundA = 0L;
        long foundB = 0L;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = bounds.minY(); y <= bounds.maxY(); ++y)
        {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); ++z)
            {
                for (int x = bounds.minX(); x <= bounds.maxX(); ++x)
                {
                    BlockState state = world.getBlockState(pos.set(x, y, z));
                    foundA += state.is(a) ? 1 : 0;
                    foundB += state.is(b) ? 1 : 0;
                }
            }
        }

        if ((foundA != countA || foundB != countB) && Util.getMillis() - this.k.startedAt() < REBUILD_MS - 1_000L)
        {
            return false;
        }

        this.k.check(T + "голограмма: " + ReplacementStates.shortId(a), countA, foundA);
        this.k.check(T + "голограмма: " + ReplacementStates.shortId(b), countB, foundB);
        return true;
    }

    private boolean stored(@Nullable Map<String, String> expected)
    {
        Path file = this.file;

        if (file == null)
        {
            this.k.fail(T + "файл замен: у схемы нет файла");
            return true;
        }

        Map<String, String> stored = BlockReplacements.TestHooks.stored(file);
        this.k.check(T + "файл замен: запись k-generated", expected == null ? "нет" : expected, stored == null ? "нет" : stored);

        if (expected != null)
        {
            try
            {
                String text = Files.readString(BlockReplacements.TestHooks.storeFile());
                this.k.check(T + "файл замен на диске называет файл схемы", text.contains(file.getFileName().toString()));
            }
            catch (IOException e)
            {
                this.k.fail(T + "файл замен не читается: " + e);
            }
        }

        return true;
    }

    // ===== Перезагрузка =====

    private boolean coreReplace()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            this.k.fail(T + "перезагрузка: нет схемы");
            return true;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.replace(schematic, Items.OAK_PLANKS, Blocks.SPRUCE_PLANKS);
        this.k.check(T + "перезагрузка: замена ядром — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        return true;
    }

    private boolean unload()
    {
        LitematicaSchematic schematic = this.schematic;

        if (this.placement != null && PlacementOps.all().contains(this.placement))
        {
            PlacementOps.remove(this.placement);
        }

        this.placement = null;
        removeFromHolder(this.file);
        this.k.check(T + "перезагрузка: схемы файла нет в «Загруженных схемах»", ! inHolder(this.file));
        this.k.check(T + "перезагрузка: старый объект ещё с заменой", schematic != null
                && ! BlockReplacements.active(schematic).isEmpty());
        return true;
    }

    private boolean reloadCheck(boolean restart)
    {
        Path file = this.file;

        if (file == null)
        {
            this.k.fail(T + "перезагрузка: нет файла");
            return true;
        }

        if (restart)
        {
            removeFromHolder(file);
            BlockReplacements.TestHooks.reloadStore();
        }

        LitematicaSchematic loaded = SchematicHolder.getInstance().getOrLoad(file);
        String how = restart ? "после «перезапуска»" : "Litematica сама";

        if (! this.k.check(T + "перезагрузка (" + how + "): схема загружена новым объектом", loaded != null && loaded != this.schematic))
        {
            return true;
        }

        this.fresh.add(loaded);
        this.reloaded = loaded;
        this.k.check(T + "перезагрузка (" + how + "): миксин чтения увидел схему", BlockReplacements.TestHooks.known(loaded));
        this.expectOn(loaded, "перезагрузка (" + how + ")", Map.of(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS), true);
        return true;
    }

    private boolean reloadRevert()
    {
        LitematicaSchematic schematic = this.schematic;

        if (schematic == null)
        {
            return true;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.revert(schematic, Items.SPRUCE_PLANKS);
        this.k.check(T + "перезагрузка: откат ядром — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.expect("откат (свой объект)", Map.of(), false);
        this.expectOn(this.reloaded, "откат (перечитанный объект)", Map.of(), false);
        this.stored(null);
        removeFromHolder(this.file);
        return true;
    }

    private static boolean inHolder(@Nullable Path file)
    {
        for (LitematicaSchematic schematic : SchematicHolder.getInstance().getAllSchematics())
        {
            if (file != null && schematic.getFile() != null && file.equals(schematic.getFile().toAbsolutePath().normalize()))
            {
                return true;
            }
        }

        return false;
    }

    private static void removeFromHolder(@Nullable Path file)
    {
        for (LitematicaSchematic schematic : List.copyOf(SchematicHolder.getInstance().getAllSchematics()))
        {
            if (file != null && schematic.getFile() != null && file.equals(schematic.getFile().toAbsolutePath().normalize()))
            {
                SchematicHolder.getInstance().removeSchematic(schematic);
            }
        }
    }

    // ===== Файл сменился =====

    private boolean copyLoaded()
    {
        if (this.copyLoading == null)
        {
            SchematicEntry generated = this.k.generated(".litematic");

            try
            {
                Path folder = this.k.outDir().resolve("replace-src");
                Files.createDirectories(folder);
                this.copy = folder.resolve("k-replace-copy.litematic").toAbsolutePath().normalize();
                Files.copy(generated.path(), this.copy, StandardCopyOption.REPLACE_EXISTING);
                SchematicEntry entry = SchematicLibrary.get().entry(this.copy);
                this.copyLoading = SchematicLibrary.get().load(entry);
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail(T + "файл: копия — " + e);
                return true;
            }
        }

        if (! this.copyLoading.isDone())
        {
            return false;
        }

        try
        {
            this.copySchematic = this.copyLoading.join().litematica();
            this.k.ok(T + "файл: копия загружена нашим загрузчиком");
        }
        catch (RuntimeException e)
        {
            this.k.fail(T + "файл: загрузка копии — " + e);
        }

        return true;
    }

    private boolean copyReplace()
    {
        if (this.copySchematic == null || this.copy == null)
        {
            return true;
        }

        BlockReplacements.Outcome outcome = BlockReplacements.replace(this.copySchematic, Items.OAK_PLANKS, Blocks.BIRCH_PLANKS);
        this.k.check(T + "файл: замена в копии — " + (outcome.ok() ? "ок" : outcome.error()), outcome.ok());
        this.k.check(T + "файл: запись копии в файле замен", BlockReplacements.TestHooks.stored(this.copy) != null);
        return true;
    }

    private boolean copyTouched()
    {
        Path copy = this.copy;

        if (copy == null)
        {
            return true;
        }

        try
        {
            Files.setLastModifiedTime(copy, FileTime.fromMillis(Files.getLastModifiedTime(copy).toMillis() + 60_000L));
        }
        catch (IOException e)
        {
            this.k.fail(T + "файл: время не меняется — " + e);
            return true;
        }

        removeFromHolder(copy);
        LitematicaSchematic loaded = SchematicHolder.getInstance().getOrLoad(copy);

        if (this.k.check(T + "файл («потрогали»): Litematica загрузила копию", loaded != null))
        {
            this.fresh.add(loaded);
            this.k.check(T + "файл («потрогали»): замена на месте",
                    Map.of(Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS).toString(), BlockReplacements.active(loaded).toString());
        }

        removeFromHolder(copy);
        return true;
    }

    private boolean copyChanged()
    {
        Path copy = this.copy;
        Path other = this.otherLitematic();

        if (copy == null)
        {
            return true;
        }

        if (other == null)
        {
            this.k.info(T + "файл: другой .litematic для подмены не нашёлся — шаг пропущен");
            return true;
        }

        try
        {
            Files.copy(other, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            this.k.fail(T + "файл: подмена — " + e);
            return true;
        }

        LitematicaSchematic loaded = SchematicHolder.getInstance().getOrLoad(copy);

        if (this.k.check(T + "файл (другой): Litematica загрузила подменённый файл", loaded != null))
        {
            this.fresh.add(loaded);
            this.k.check(T + "файл (другой): замен не наложено", BlockReplacements.active(loaded).isEmpty());
        }

        this.k.check(T + "файл (другой): запись замен сброшена", BlockReplacements.TestHooks.stored(copy) == null);
        removeFromHolder(copy);
        return true;
    }

    /** Другой .litematic из папки прогона — другого размера, чем k-generated. */
    private @Nullable Path otherLitematic()
    {
        SchematicEntry generated = this.k.generated(".litematic");

        try (Stream<Path> files = Files.list(this.k.kDir()))
        {
            return files.filter(path -> path.getFileName().toString().endsWith(".litematic"))
                    .filter(path -> generated == null || KData.size(path) != KData.size(generated.path()))
                    .filter(path -> KData.size(path) < 2_000_000L)
                    .sorted(Comparator.comparing(Path::toString))
                    .findFirst().orElse(null);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private boolean tidy()
    {
        if (this.placement != null && PlacementOps.all().contains(this.placement))
        {
            PlacementOps.remove(this.placement);
        }

        this.placement = null;

        for (LitematicaSchematic schematic : this.fresh)
        {
            SchematicHolder.getInstance().removeSchematic(schematic);
        }

        this.fresh.clear();

        // То же с отключёнными сущностями: упавший шаг не должен оставить стойку или рамки выключенными
        if (this.schematic != null && ! BlockReplacements.disabledEntityItems(this.schematic).isEmpty())
        {
            BlockReplacements.enable(this.schematic, List.of(), List.copyOf(BlockReplacements.disabledEntityItems(this.schematic)));
        }

        // Замены, оставшиеся от упавшего шага, не должны пережить прогон
        if (this.schematic != null && ! BlockReplacements.active(this.schematic).isEmpty())
        {
            for (Block target : List.copyOf(BlockReplacements.active(this.schematic).values()))
            {
                BlockReplacements.revert(this.schematic, target.asItem());
            }
        }

        return true;
    }

    // ===== Большая схема =====

    private boolean loadBig()
    {
        if (! this.bigSearched)
        {
            this.bigSearched = true;
            Path source = biggest();

            if (source == null)
            {
                this.k.info(T + "большой схемы игрока не нашлось — шаги большой схемы без неё");
                return true;
            }

            try
            {
                Path folder = this.k.outDir().resolve("replace-big");
                Files.createDirectories(folder);
                Path copy = folder.resolve(source.getFileName().toString());
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
                this.bigEntry = SchematicLibrary.get().entry(copy);
                this.k.info(T + "большая схема: " + source + " (" + KData.size(copy) + " Б)");
                this.bigLoading = this.bigEntry == null ? null : SchematicLibrary.get().load(this.bigEntry);
            }
            catch (IOException | RuntimeException e)
            {
                this.k.fail(T + "большая: копия — " + e);
                return true;
            }
        }

        if (this.bigLoading == null)
        {
            return true;
        }

        if (! this.bigLoading.isDone())
        {
            return false;
        }

        try
        {
            this.big = this.bigLoading.join().litematica();
            this.k.ok(T + "большая загружена: блоков " + this.big.getMetadata().getTotalBlocks());
        }
        catch (RuntimeException e)
        {
            this.k.fail(T + "большая: загрузка — " + e);
        }

        this.bigLoading = null;
        return true;
    }

    private boolean openBig()
    {
        if (this.big == null)
        {
            return true;
        }

        this.k.setTab("MATERIALS");

        try
        {
            KProbe.call(this.pane(), "open", this.bigEntry, null);
            this.bigCountFrom = Util.getMillis();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            this.k.fail(T + "большая: открыть в «Материалах» — " + e);
            this.big = null;
        }

        return true;
    }

    private boolean bigRows()
    {
        if (this.big == null)
        {
            return true;
        }

        Object pane = this.pane();

        if (this.busy() || this.lines().isEmpty() || KProbe.fieldOfType(pane, LitematicaSchematic.class) != this.big)
        {
            return false;
        }

        this.k.info(T + "большая: строк " + this.lines().size() + ", счёт " + (Util.getMillis() - this.bigCountFrom) + " мс");

        // Самая частая строка-блок, которую можно заменить; цель — губка (её полный id ни в чьём другом не
        // встречается, поиск по нему однозначен), а если это губка — светокамень
        List<MaterialRow> rows = new ArrayList<>();
        this.lines().forEach(line -> rows.add(line.row()));
        Map<String, BlockReplacements.RowState> states = BlockReplacements.rows(this.big, rows, false);
        MaterialLine best = null;

        for (MaterialLine line : this.lines())
        {
            BlockReplacements.RowState state = states.get(line.key());

            if (state != null && state.replaceable() && line.key().equals(DumpSchematics.id(line.stack().getItem()))
                    && (best == null || line.need() > best.need()))
            {
                best = line;
            }
        }

        if (! this.k.check(T + "большая: есть строка-блок для замены", best != null))
        {
            this.big = null;
            return true;
        }

        this.bigItem = best.stack().getItem();
        Block from = ((BlockItem) this.bigItem).getBlock();
        this.bigTarget = from == Blocks.SPONGE ? Blocks.GLOWSTONE : Blocks.SPONGE;
        this.k.info(T + "большая: заменяем «" + best.name() + "» (" + best.needText() + ") → " + ReplacementStates.shortId(this.bigTarget));
        return true;
    }

    private boolean bigReveal()
    {
        return this.bigItem == null || this.reveal(this.bigItem);
    }

    private boolean bigEnter()
    {
        if (this.bigItem == null)
        {
            return true;
        }

        long from = Util.getMillis();
        press(InputConstants.KEY_RETURN);
        long took = Util.getMillis() - from;

        this.k.check(T + "большая: замена (Enter → готово) за " + took + " мс, предел " + BIG_REPLACE_MAX_MS, took <= BIG_REPLACE_MAX_MS);
        this.k.check(T + "большая: замена в схеме", ! BlockReplacements.active(this.big).isEmpty());
        this.k.resetStepMaxGap();
        this.bigCountFrom = Util.getMillis();
        return true;
    }

    private boolean bigPauses(String after)
    {
        if (this.bigItem != null)
        {
            this.k.info(T + "большая: самая долгая пауза кадра за 5 с после " + after + " — " + this.k.stepMaxGap() + " мс");
        }

        return true;
    }

    private boolean bigRecounted()
    {
        if (this.bigItem == null || this.bigTarget == null)
        {
            return true;
        }

        MaterialLine line = this.line(this.bigTarget.asItem());

        if (this.busy() || line == null || line.replacedFrom().isEmpty())
        {
            return false;
        }

        this.k.info(T + "большая: пересчёт после замены — " + (Util.getMillis() - this.bigCountFrom) + " мс");
        this.k.check(T + "большая: строка «" + line.name() + "» помечена заменой", ! line.replacedFrom().isEmpty());
        return true;
    }

    private boolean bigRevert()
    {
        if (this.bigItem == null || this.bigTarget == null)
        {
            return true;
        }

        ReplaceCell cell = this.cell();
        MaterialLine line = this.line(this.bigTarget.asItem());
        int[] box = cell == null || line == null ? null : cell.revertBox(line.key());

        if (box == null)
        {
            Table<MaterialLine> table = this.table();

            if (table != null && line != null)
            {
                table.setSelected(line);
            }

            return false;
        }

        long from = Util.getMillis();
        KInline.realClick(box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
        long took = Util.getMillis() - from;

        this.k.check(T + "большая: откат за " + took + " мс, предел " + BIG_REPLACE_MAX_MS, took <= BIG_REPLACE_MAX_MS);
        this.k.check(T + "большая: замен не осталось", BlockReplacements.active(this.big).isEmpty());
        this.k.resetStepMaxGap();
        return true;
    }

    private boolean unloadBig()
    {
        if (this.big != null)
        {
            if (! BlockReplacements.active(this.big).isEmpty())
            {
                for (Block target : List.copyOf(BlockReplacements.active(this.big).values()))
                {
                    BlockReplacements.revert(this.big, target.asItem());
                }
            }

            SchematicHolder.getInstance().removeSchematic(this.big);

            if (this.bigEntry != null)
            {
                this.k.check(T + "большая: записи в файле замен нет", BlockReplacements.TestHooks.stored(this.bigEntry.path()) == null);
            }

            this.big = null;
        }

        // Таблица — снова k-generated, чтобы следующие шаги не считали большую
        SchematicEntry generated = this.k.generated(".litematic");

        if (generated != null && this.k.gui() != null)
        {
            try
            {
                KProbe.call(this.pane(), "open", generated, null);
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                // Таблица осталась на большой — следующие шаги выберут свою схему сами
            }
        }

        return true;
    }

    /** Самый большой файл схемы в папках игрока (как у постера и редактора); {@code LL_K_POSTER_BIG} — свой путь. */
    private static @Nullable Path biggest()
    {
        String override = System.getenv("LL_K_POSTER_BIG");

        if (override != null && ! override.isBlank())
        {
            Path path = Path.of(override.trim());
            return Files.isRegularFile(path) ? path : null;
        }

        Path best = null;
        long bestSize = -1L;

        for (Path folder : KData.profileFolders())
        {
            try (Stream<Path> files = Files.list(folder))
            {
                for (Path file : files.filter(p -> SchematicFormat.of(p) != null && Files.isRegularFile(p))
                        .sorted(Comparator.comparing(Path::toString)).toList())
                {
                    long size = KData.size(file);

                    if (size > bestSize)
                    {
                        best = file;
                        bestSize = size;
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                // Папка недоступна — смотрим остальные
            }
        }

        return best;
    }

    // ===== Настоящий ввод =====

    private static long handle()
    {
        return Minecraft.getInstance().getWindow().handle();
    }

    private static void move(double guiX, double guiY)
    {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double x = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
        double y = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
        mc.mouseHandler.onMove(handle(), x, y, 0.0, 0.0);
    }

    private static void press(int scancode)
    {
        int keycode = switch (scancode)
        {
            case InputConstants.KEY_RETURN -> '\r';
            case InputConstants.KEY_ESCAPE -> 0x1B;
            case InputConstants.KEY_BACKSPACE -> '\b';
            default -> scancode | SCANCODE_MASK;
        };
        KeyEvent event = new KeyEvent(scancode, keycode, 0);
        Minecraft.getInstance().keyboardHandler.keyPress(handle(), InputConstants.PRESS, event);
        Minecraft.getInstance().keyboardHandler.keyPress(handle(), InputConstants.RELEASE, event);
    }

    /** Набор строки, как его даёт SDL: нажатие клавиши, событие текста, отпускание; кириллица — на клавишах ЙЦУКЕН. */
    private static void type(String text)
    {
        text.codePoints().forEach(c ->
        {
            String piece = new String(Character.toChars(c));
            int[] key = physicalKey(c);

            if (key == null)
            {
                Minecraft.getInstance().keyboardHandler.textInput(handle(), piece);
                return;
            }

            KeyEvent event = new KeyEvent(key[0], key[1], key[2]);
            Minecraft.getInstance().keyboardHandler.keyPress(handle(), InputConstants.PRESS, event);
            Minecraft.getInstance().keyboardHandler.textInput(handle(), piece);
            Minecraft.getInstance().keyboardHandler.keyPress(handle(), InputConstants.RELEASE, event);
        });
    }

    private static int @Nullable [] physicalKey(int c)
    {
        int lower = Character.toLowerCase(c);
        int shift = lower != c ? InputConstants.MOD_SHIFT : 0;
        int ru = RU.indexOf(lower);

        if (ru >= 0)
        {
            lower = EN.charAt(ru);
        }

        if (lower >= 'a' && lower <= 'z')
        {
            return new int[] { InputConstants.KEY_A + lower - 'a', lower, shift };
        }

        return switch (lower)
        {
            case ' ' -> new int[] { InputConstants.KEY_SPACE, ' ', 0 };
            case '_' -> new int[] { InputConstants.KEY_MINUS, '-', InputConstants.MOD_SHIFT };
            default -> null;
        };
    }

    /** Кто держит ввод текста SDL ({@code TextInputManager.owner}, закрытое поле — только харнесс). */
    private static @Nullable Object owner()
    {
        Object manager = Minecraft.getInstance().textInputManager();

        for (Class<?> c = manager.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            try
            {
                Field enabled = c.getDeclaredField("textInputEnabled");
                Field owner = c.getDeclaredField("owner");
                enabled.setAccessible(true);
                owner.setAccessible(true);
                return Boolean.TRUE.equals(enabled.get(manager)) ? owner.get(manager) : null;
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
}
