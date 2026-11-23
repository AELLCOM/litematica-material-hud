package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.DataItems;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.materials.Availability;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.TextInput;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code bannerstock} (19.11.2026): «С учётом склада» и флаги с узорами. Жалоба владельца: во вкладке
 * «Материалы» при «Только недостающее» + «С учётом склада» флаги не показываются вовсе, хотя их не хватает.
 * Причина была в складе: сундуки помнили предметы только по виду, без узора, и один и тот же запас засчитывался
 * каждой строке флага.
 *
 * <p>Схема: три белых флага без узора, два с полосой и один с градиентом. В сундуке А — пять белых без узора, в
 * сундуке Б — один с полосой; градиента нет нигде. Ждём: обычные закрыты складом; с полосой не хватает одного; с
 * градиентом — одного. И в панели HUD, и во вкладке «Материалы»; поиск «флаг» их находит; а когда хватает всего —
 * пустая таблица говорит про склад, а не «ничего не найдено».
 */
@Environment(EnvType.CLIENT)
public final class BannerStockTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(1220, 100, 0);
    private static final BlockPos TARGET = new BlockPos(1220, 100, 14);
    private static final BlockPos CHEST_A = TARGET.offset(0, 0, 8);
    private static final BlockPos CHEST_B = TARGET.offset(2, 0, 8);
    private static final String STRIPE = "[{pattern:\"minecraft:stripe_top\",color:\"red\"}]";
    private static final String GRADIENT = "[{pattern:\"minecraft:gradient\",color:\"blue\"}]";

    private int stage;
    private int opened;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private boolean hudWas;
    private boolean stockWas;
    private boolean onlyMissingWas;
    private boolean chosenWas;
    private boolean enderWas;

    @Override
    public String name()
    {
        return "bannerstock (склад и флаги с узорами)";
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
        this.hudWas = LayerListConfigs.HUD_ENABLED;
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.onlyMissingWas = LayerListConfigs.ONLY_MISSING.getBooleanValue();
        this.chosenWas = LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue();
        this.enderWas = LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue();
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(true);
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
        forgetChests(mc);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1210 -10 1240 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -3, 9, -1, 11, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 9, 5, 11, "minecraft:air"));
            }

            for (int x = 0; x < 3; ++x)
            {
                command(server, set(REF, x, "minecraft:white_banner[rotation=0]"));
            }

            command(server, set(REF, 3, "minecraft:white_banner[rotation=0]{patterns:" + STRIPE + "}"));
            command(server, set(REF, 4, "minecraft:white_banner[rotation=0]{patterns:" + STRIPE + "}"));
            command(server, set(REF, 5, "minecraft:white_banner[rotation=0]{patterns:" + GRADIENT + "}"));
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
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
        LayerCountManager hud = LayerCountManager.getInstance();
        long wait = 2000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-bannerstock");
                area.addSubRegionBox(new Box(REF, REF.offset(5, 0, 0), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-bannerstock", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, chest(CHEST_A, "{Slot:0b,id:\"minecraft:white_banner\",count:5}"));
                    command(server, chest(CHEST_B, "{Slot:0b,id:\"minecraft:white_banner\",count:1,components:"
                            + "{\"minecraft:banner_patterns\":" + STRIPE + "}}"));
                    command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (CHEST_A.getZ() + 2.5D) + " 180 30");
                });
                wait = 3500L;
            }
            case 1 ->
            {
                // Оба сундука открыть и закрыть — так их запоминает индекс складов
                if (mc.screen != null)
                {
                    mc.player.closeContainer();
                    ++this.opened;
                    this.nextAt = now + 800L;
                    return false;
                }

                if (this.opened < 2)
                {
                    BlockPos chest = this.opened == 0 ? CHEST_A : CHEST_B;
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0, 0.5), Direction.SOUTH, chest, false));
                    this.nextAt = now + 1500L;
                    return false;
                }

                hud.markDirty();
                wait = 3500L;
            }
            case 2 ->
            {
                Map<String, MaterialListEntry> rows = rows(hud.getAllEntries());
                this.check("в списке три строки флагов: без узора, с полосой, с градиентом", rows.size() == 3
                        && rows.containsKey("без узора") && rows.containsKey("stripe_top") && rows.containsKey("gradient"), rows.keySet().toString());
                Availability have = Availability.capture();
                long plain = rows.containsKey("без узора") ? have.inChests(rows.get("без узора").getStack()) : -1L;
                long stripe = rows.containsKey("stripe_top") ? have.inChests(rows.get("stripe_top").getStack()) : -1L;
                long gradient = rows.containsKey("gradient") ? have.inChests(rows.get("gradient").getStack()) : -1L;
                this.check("в сундуках: без узора 5, с полосой 1, с градиентом 0 (у каждой строки — свой запас)",
                        plain == 5L && stripe == 1L && gradient == 0L, "без узора " + plain + ", с полосой " + stripe + ", с градиентом " + gradient);
                LayerListConfigs.USE_STOCK.setBooleanValue(true);
                wait = 2000L;
            }
            case 3 ->
            {
                Map<String, MaterialListEntry> rows = rows(hud.getEntries());
                this.check("панель, «С учётом склада»: флаги без узора закрыты складом", ! rows.containsKey("без узора"), rows.keySet().toString());
                this.check("панель: с полосой не хватает 1 (нужно 2, в сундуке 1)", rows.containsKey("stripe_top")
                        && hud.getShortfall(rows.get("stripe_top")) == 1, rows.containsKey("stripe_top")
                        ? "нехватка " + hud.getShortfall(rows.get("stripe_top")) : "строки нет");
                this.check("панель: с градиентом не хватает 1 (нет нигде)", rows.containsKey("gradient")
                        && hud.getShortfall(rows.get("gradient")) == 1, rows.containsKey("gradient")
                        ? "нехватка " + hud.getShortfall(rows.get("gradient")) : "строки нет");
                shot(mc, "панель: флаги и склад");
                setStatic("onlyMissing", true);
                lastTab("MATERIALS");
                GuiSchematics.open(null);
                wait = 4500L;
            }
            case 4 ->
            {
                Object pane = pane(mc);
                Map<String, MaterialLine> shown = lines(field(pane, "shown"));
                this.check("вкладка «Материалы», оба тумблера: строки флагов с полосой и с градиентом на месте",
                        shown.size() == 2 && shown.containsKey("stripe_top") && shown.containsKey("gradient"), describe(shown));
                MaterialLine stripe = shown.get("stripe_top");
                MaterialLine gradient = shown.get("gradient");
                this.check("вкладка: с полосой — осталось 2, в сундуках 1, не хватает 1", stripe != null && stripe.lack() == 2L
                        && stripe.chests() == 1L && stripe.shortage() == 1L, describe(shown));
                this.check("вкладка: с градиентом — осталось 1, в сундуках 0, не хватает 1", gradient != null && gradient.lack() == 1L
                        && gradient.chests() == 0L && gradient.shortage() == 1L, describe(shown));
                shot(mc, "вкладка: только недостающее + с учётом склада");
                search(pane, I18nFlag.WORD);
                wait = 2500L;
            }
            case 5 ->
            {
                Object pane = pane(mc);
                Map<String, MaterialLine> shown = lines(field(pane, "shown"));
                this.check("вкладка: поиск «флаг» находит обе недостающие строки", shown.size() == 2, describe(shown));
                shot(mc, "вкладка: поиск флаг");
                mc.setScreen(null);
                // Недостающее — в инвентарь: теперь хватает всего, и таблица должна сказать об этом правду
                server.execute(() -> {
                    command(server, "item replace entity @a hotbar.0 with minecraft:white_banner[banner_patterns=" + STRIPE + "] 1");
                    command(server, "item replace entity @a hotbar.1 with minecraft:white_banner[banner_patterns=" + GRADIENT + "] 1");
                });
                wait = 2500L;
            }
            case 6 ->
            {
                GuiSchematics.open(null);
                wait = 4500L;
            }
            case 7 ->
            {
                search(pane(mc), I18nFlag.WORD);
                wait = 2500L;
            }
            case 8 ->
            {
                Object pane = pane(mc);
                Map<String, MaterialLine> shown = lines(field(pane, "shown"));
                String empty = String.valueOf(invoke(pane, "emptyText"));
                this.check("всего хватает: таблица пуста", shown.isEmpty(), describe(shown));
                this.check("пустая таблица говорит про склад, а не «ничего не найдено»",
                        empty.equals(net.minecraft.client.resources.language.I18n.get("layerlist.schematics.materials.empty.search.covered", I18nFlag.WORD)),
                        empty);
                shot(mc, "вкладка: всего хватает");
                mc.setScreen(null);
            }
            default ->
            {
                CovOut.log("[bannerstock] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Слово для поиска — как его напишет игрок на своём языке. */
    private static final class I18nFlag
    {
        static final String WORD = Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru") ? "флаг" : "banner";
    }

    /** Строки флагов панели по узору: «без узора» или имя первого слоя узора. */
    private static Map<String, MaterialListEntry> rows(List<MaterialListEntry> entries)
    {
        Map<String, MaterialListEntry> rows = new TreeMap<>();

        for (MaterialListEntry entry : entries)
        {
            if (StorageIndex.itemId(entry.getStack()).equals("minecraft:white_banner"))
            {
                rows.put(pattern(entry.getStack()), entry);
            }
        }

        return rows;
    }

    private static Map<String, MaterialLine> lines(Object shown)
    {
        Map<String, MaterialLine> lines = new TreeMap<>();

        if (shown instanceof List<?> list)
        {
            for (Object item : list)
            {
                MaterialLine line = (MaterialLine) item;

                if (StorageIndex.itemId(line.stack()).equals("minecraft:white_banner"))
                {
                    lines.put(pattern(line.stack()), line);
                }
            }
        }

        return lines;
    }

    private static String describe(Map<String, MaterialLine> lines)
    {
        StringBuilder text = new StringBuilder();

        for (Map.Entry<String, MaterialLine> line : lines.entrySet())
        {
            text.append(line.getKey()).append(": осталось ").append(line.getValue().lack()).append(", в инвентаре ")
                    .append(line.getValue().inventory()).append(", в сундуках ").append(line.getValue().chests())
                    .append(", не хватает ").append(line.getValue().shortage()).append("; ");
        }

        return text.isEmpty() ? "строк нет" : text.toString();
    }

    private static String pattern(ItemStack stack)
    {
        BannerPatternLayers layers = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

        if (layers.layers().isEmpty())
        {
            return DataItems.hasData(stack) ? "данные" : "без узора";
        }

        String name = layers.layers().get(0).pattern().getRegisteredName();
        return name.substring(name.indexOf(':') + 1);
    }

    private static void search(Object pane, String query)
    {
        if (field(pane, "search") instanceof TextInput input)
        {
            input.setText(query);
        }

        invoke(pane, "applyFilter", true);
    }

    private static Object invoke(Object owner, String name, Object... args)
    {
        if (owner == null)
        {
            return null;
        }

        try
        {
            for (Method method : owner.getClass().getDeclaredMethods())
            {
                if (method.getName().equals(name) && method.getParameterCount() == args.length)
                {
                    method.setAccessible(true);
                    return method.invoke(owner, args);
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[bannerstock] не вызвать " + name + ": " + e);
        }

        return null;
    }

    private static void setStatic(String name, boolean value)
    {
        try
        {
            Field field = Class.forName("com.aell.layerlist.schematics.gui.MaterialsPane").getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, value);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[bannerstock] нет поля " + name + ": " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void forgetChests(Minecraft mc)
    {
        String scope = StorageIndex.scope(mc);
        Map<String, StorageIndex.Rec> records = (Map<String, StorageIndex.Rec>) field(StorageIndex.getInstance(), "records");

        if (records != null)
        {
            records.values().removeIf(rec -> scope.equals(rec.scope));
        }
    }

    private static Object pane(Minecraft mc)
    {
        return mc.screen instanceof GuiSchematics gui ? KProbe.fieldNamedType(gui, "MaterialsPane") : null;
    }

    private static Object field(Object owner, String name)
    {
        if (owner == null)
        {
            return null;
        }

        try
        {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[bannerstock] нет поля " + name + ": " + e);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void lastTab(String tab)
    {
        try
        {
            Class<? extends Enum> tabs = (Class<? extends Enum>) Class.forName(GuiSchematics.class.getName() + "$Tab");
            Field field = GuiSchematics.class.getDeclaredField("lastTab");
            field.setAccessible(true);
            field.set(null, Enum.valueOf(tabs, tab));
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[bannerstock] вкладку не выбрать: " + e);
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[bannerstock] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private static String chest(BlockPos pos, String items)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=south]{Items:[%s]}", pos.getX(), pos.getY(),
                pos.getZ(), items);
    }

    private static String set(BlockPos o, int x, String block)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", o.getX() + x, o.getY(), o.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[bannerstock] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[bannerstock] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        setStatic("onlyMissing", false);
        LayerListConfigs.HUD_ENABLED = this.hudWas;
        LayerListConfigs.USE_STOCK.setBooleanValue(this.stockWas);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(this.onlyMissingWas);
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(this.chosenWas);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(this.enderWas);
    }
}
