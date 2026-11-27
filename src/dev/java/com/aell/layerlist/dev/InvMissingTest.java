package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.library.SchematicEntry;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Toggle;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code invmissing} (24.11.2026): «Только недостающее» у схемы, не размещённой в мире, сверяет её с инвентарём —
 * как «Скрыть доступные» Litematica.
 * <ol>
 *   <li>Схема из файла без размещения: тумблер доступен, без предметов в инвентаре остаются все строки.</li>
 *   <li>Взяли 2 камня из 3 — строка камня осталась с «Осталось» 1; числа обновились сами, без «Обновить».</li>
 *   <li>Шалкер с 5 досками в инвентаре — строка досок ушла (содержимое шалкера считается).</li>
 *   <li>Ещё камень — таблица пуста с надписью «всё нужное уже в инвентаре»; выключили тумблер — снова вся схема.</li>
 *   <li>Сундук с досками не вычитается (только «С учётом склада»).</li>
 *   <li>Схему разместили поверх готовой постройки — сверка уходит на мир: инвентарь не важен, недостающего нет.</li>
 * </ol>
 * Тумблер — настоящим щелчком ({@link SupportTest#click}).
 */
@Environment(EnvType.CLIENT)
public final class InvMissingTest implements CovJob
{
    private static final String TAG = "[invmissing] ";
    private static final BlockPos REF = new BlockPos(1800, 100, 0);
    private static final BlockPos CHEST = new BlockPos(1803, 100, 3);
    private static final String STONE = "minecraft:stone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String NAME = "dev-invmissing";

    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean stockWas;
    private boolean onlyMissingWas;
    private @Nullable LitematicaSchematic schematic;
    private @Nullable SchematicEntry entry;
    private @Nullable SchematicPlacement placement;

    @Override
    public String name()
    {
        return "invmissing («Только недостающее» без размещения — по инвентарю)";
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
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.onlyMissingWas = onlyMissing();
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(false);
        setOnlyMissing(false);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1790 -10 1830 30");
            command(server, fill(REF, -4, -1, -4, 12, -1, 12, "minecraft:smooth_stone"));
            command(server, fill(REF, -4, 0, -4, 12, 8, 12, "minecraft:air"));
            // Башня: три камня и две доски сверху
            command(server, fill(REF, 0, 0, 0, 0, 2, 0, STONE));
            command(server, fill(REF, 0, 3, 0, 0, 4, 0, PLANKS));
            command(server, "tp @a " + (REF.getX() + 3.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D) + " 160 10");
        });

        this.nextAt = Util.getMillis() + 3500L;
        return true;
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
                this.schematic = schematic(mc);
                this.entry = this.write(this.schematic);
                this.check("схема записана в файл", this.entry != null, "");
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                });
                GuiSchematics.openPlacements(null);
                wait = 1500L;
            }
            case 1 ->
            {
                this.invoke(this.gui(mc), "showMaterials", this.entry, null);
                wait = 3500L;
            }
            case 2 ->
            {
                Object pane = this.materials(mc);
                this.check("источник — файл, схема не размещена", Boolean.TRUE.equals(KTrash.field(pane, "unplaced")),
                        this.label(pane));
                Toggle toggle = this.toggle(pane);
                this.check("тумблер «Только недостающее» доступен", toggle != null && toggle.enabled(), "");
                this.check("пустой инвентарь, тумблер выкл.: камень 3, доски 2",
                        this.shownNeed(pane, STONE) == 3L && this.shownNeed(pane, PLANKS) == 2L, this.dump(pane));
                SupportTest.moveMouse(mc, toggle == null ? 3.0D : toggle.x() + 8.0D, toggle == null ? 3.0D : toggle.y() + toggle.h() / 2.0D);
                wait = 1200L;
            }
            case 3 ->
            {
                shot(mc, "invmissing-0-tooltip");
                this.clickToggle(mc);
                wait = 900L;
            }
            case 4 ->
            {
                Object pane = this.materials(mc);
                this.check("тумблер включился", onlyMissing(), "");
                this.check("инвентарь пуст: остались обе строки, «Осталось» 3 и 2",
                        this.shownLeft(pane, STONE) == 3L && this.shownLeft(pane, PLANKS) == 2L, this.dump(pane));
                this.check("заголовок колонки — «Осталось»", Boolean.TRUE.equals(this.call(pane, "showLeft")), "");
                server.execute(() -> command(server, "give @a " + STONE + " 2"));
                wait = 1500L;
            }
            case 5 ->
            {
                Object pane = this.materials(mc);
                this.check("2 камня в инвентаре: камень остался с «Осталось» 1 (сам, без «Обновить»)",
                        this.shownLeft(pane, STONE) == 1L, this.dump(pane));
                this.check("доски на месте (2)", this.shownLeft(pane, PLANKS) == 2L, this.dump(pane));
                shot(mc, "invmissing-1-stone");
                server.execute(() -> command(server, "give @a minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:\""
                        + PLANKS + "\",count:5}}]] 1"));
                wait = 1500L;
            }
            case 6 ->
            {
                Object pane = this.materials(mc);
                this.check("шалкер с 5 досками в инвентаре: строки досок нет", this.shownLeft(pane, PLANKS) == -1L, this.dump(pane));
                this.check("камень остался (1)", this.shownLeft(pane, STONE) == 1L, this.dump(pane));
                server.execute(() -> command(server, "give @a " + STONE + " 1"));
                wait = 1500L;
            }
            case 7 ->
            {
                Object pane = this.materials(mc);
                List<?> shown = KTrash.field(pane, "shown");
                String empty = String.valueOf(this.call(pane, "emptyText"));
                this.check("всё в инвентаре: таблица пуста", shown != null && shown.isEmpty(), this.dump(pane));
                this.check("надпись — «всё нужное уже в инвентаре»",
                        empty.equals(net.minecraft.client.resources.language.I18n.get("layerlist.schematics.materials.empty.inventory")), empty);
                shot(mc, "invmissing-2-empty");
                this.clickToggle(mc);
                wait = 900L;
            }
            case 8 ->
            {
                Object pane = this.materials(mc);
                this.check("тумблер выкл.: снова вся схема, камень 3, доски 2",
                        this.shownNeed(pane, STONE) == 3L && this.shownNeed(pane, PLANKS) == 2L, this.dump(pane));
                this.clickToggle(mc);
                // Доски — из инвентаря в сундук: сундук «Только недостающее» не вычитает
                server.execute(() -> {
                    command(server, "clear @a minecraft:shulker_box");
                    command(server, "setblock " + CHEST.getX() + " " + CHEST.getY() + " " + CHEST.getZ()
                            + " minecraft:chest{Items:[{Slot:0b,id:\"" + PLANKS + "\",count:10}]}");
                });
                wait = 1500L;
            }
            case 9 ->
            {
                mc.gui.setScreen(null);
                // Открыть сундук, чтобы он попал в индекс складов
                if (mc.gameMode != null && mc.player != null)
                {
                    mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(CHEST),
                                    net.minecraft.core.Direction.UP, CHEST, false));
                }

                wait = 1500L;
            }
            case 10 ->
            {
                if (mc.player != null)
                {
                    mc.player.closeContainer();
                }

                GuiSchematics.openPlacements(null);
                wait = 1000L;
            }
            case 11 ->
            {
                this.invoke(this.gui(mc), "showMaterials", this.entry, null);
                wait = 3500L;
            }
            case 12 ->
            {
                Object pane = this.materials(mc);
                MaterialLine planks = this.line(pane, PLANKS);
                this.check("сундук с досками открыт и в счёте «В сундуках»", planks != null && planks.chests() >= 10L,
                        planks == null ? "строки нет" : "в сундуках " + planks.chests());
                this.check("«Только недостающее» сундук не вычитает: доски остались, «Осталось» 2",
                        this.shownLeft(pane, PLANKS) == 2L, this.dump(pane));
                this.check("камня хватает в инвентаре — строки нет", this.shownLeft(pane, STONE) == -1L, this.dump(pane));
                shot(mc, "invmissing-3-chest");
                // Разместить схему поверх готовой башни: сверка уходит на мир
                server.execute(() -> command(server, "clear @a"));
                this.placement = this.place();
                wait = 5000L;
            }
            case 13 ->
            {
                Object pane = this.materials(mc);
                this.check("размещена: «не размещена» снято", ! Boolean.TRUE.equals(KTrash.field(pane, "unplaced")), "");
                this.check("строки сверены с миром", Boolean.TRUE.equals(KTrash.field(pane, "verifiedRows")), "");
                List<?> shown = KTrash.field(pane, "shown");
                this.check("инвентарь пуст, но всё уже стоит — недостающего нет", shown != null && shown.isEmpty(), this.dump(pane));
                String empty = String.valueOf(this.call(pane, "emptyText"));
                this.check("надпись — «всё уже стоит на месте постройки»",
                        empty.equals(net.minecraft.client.resources.language.I18n.get("layerlist.schematics.materials.empty.placed")), empty);
                shot(mc, "invmissing-4-placed");
                wait = 600L;
            }
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
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
        return KTrash.field(pane, "missingOnly");
    }

    private void clickToggle(Minecraft mc)
    {
        Toggle toggle = this.toggle(this.materials(mc));

        if (toggle == null)
        {
            this.check("тумблер найден", false, "");
            return;
        }

        SupportTest.click(mc, toggle.x() + 8.0D, toggle.y() + toggle.h() / 2.0D);
        SupportTest.moveMouse(mc, 3.0D, 3.0D);
    }

    private static Field onlyMissingField() throws ReflectiveOperationException
    {
        Field field = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane").getDeclaredField("onlyMissing");
        field.setAccessible(true);
        return field;
    }

    private static boolean onlyMissing()
    {
        try
        {
            return onlyMissingField().getBoolean(null);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "onlyMissing: " + e);
            return false;
        }
    }

    private static void setOnlyMissing(boolean value)
    {
        try
        {
            onlyMissingField().setBoolean(null, value);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "onlyMissing: " + e);
        }
    }

    private @Nullable MaterialLine line(@Nullable Object pane, String itemId)
    {
        List<?> lines = KTrash.field(pane, "lines");

        if (lines != null)
        {
            for (Object line : lines)
            {
                MaterialLine l = (MaterialLine) line;

                if (! l.disabled() && StorageIndex.itemId(l.stack()).equals(itemId))
                {
                    return l;
                }
            }
        }

        return null;
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

    /** «Всего» видимой строки; -1 — строки на экране нет. */
    private long shownNeed(@Nullable Object pane, String itemId)
    {
        List<?> shown = KTrash.field(pane, "shown");

        if (shown != null)
        {
            for (Object line : shown)
            {
                MaterialLine l = (MaterialLine) line;

                if (StorageIndex.itemId(l.stack()).equals(itemId))
                {
                    return l.need();
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
                    .append(l.lack()).append(" инв=").append(l.inventory()).append(" сунд=").append(l.chests()).append(';');
        }

        return out.toString();
    }

    private @Nullable Object call(@Nullable Object target, String method)
    {
        try
        {
            return target == null ? null : KProbe.call(target, method);
        }
        catch (ReflectiveOperationException e)
        {
            return e.toString();
        }
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

    private String label(@Nullable Object pane)
    {
        Object label = this.call(pane, "sourceLabel");
        return label == null ? "" : label.toString();
    }

    // ===== Схема и мир =====

    private static LitematicaSchematic schematic(Minecraft mc)
    {
        AreaSelection area = new AreaSelection();
        area.setName(NAME);
        area.addSubRegionBox(new Box(REF, REF.offset(0, 4, 0), "main"), false);
        area.setExplicitOrigin(REF);
        return LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                "dev", CovOut::log);
    }

    private Path dir()
    {
        return DataManager.getSchematicsBaseDirectory().resolve("dev-invmissing");
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

        SchematicPlacement placement = SchematicPlacement.createFor(read, REF, NAME, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
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

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
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
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);
        setOnlyMissing(this.onlyMissingWas);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
