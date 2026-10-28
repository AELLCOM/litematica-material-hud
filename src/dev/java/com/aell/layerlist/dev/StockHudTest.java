package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code stockhud}: «С учётом склада» в HUD и в таблице «Материалы» (просьба игрока 23.10.2026: «если для схемы
 * нужно 144 земли и они лежат в сундуке — не показывать в списке»). Схема: камень ×3, доски ×2, стекло ×1, булыжник ×2.
 * Сундук A — камень ×3 (покрывает целиком), бочка B — доска ×1 (частично), сундук C — шалкер с булыжником ×1 и ещё
 * булыжник ×1 в инвентаре (покрывает вместе), сундук E — стекло ×5, но до шага 6 его не открывали (мод о нём не знает).
 */
@Environment(EnvType.CLIENT)
public final class StockHudTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(720, 100, 0);
    private static final BlockPos TARGET = new BlockPos(720, 100, 14);
    private static final BlockPos A = TARGET.offset(0, 0, 8);
    private static final BlockPos B = TARGET.offset(2, 0, 8);
    private static final BlockPos C = TARGET.offset(4, 0, 8);
    private static final BlockPos E = TARGET.offset(6, 0, 8);
    private static final BlockPos[] OPEN_FIRST = {A, B, C};

    private static final String STONE = "minecraft:stone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String GLASS = "minecraft:glass";
    private static final String COBBLE = "minecraft:cobblestone";

    private int stage;
    private int opened;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;

    @Override
    public String name()
    {
        return "stockhud («С учётом склада» в HUD и таблице)";
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
        forgetChests(mc);
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 710 -10 740 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 9, -1, 12, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 9, 5, 12, "minecraft:air"));
            }

            command(server, fill(REF, 0, 0, 0, 2, 0, 0, STONE));
            command(server, fill(REF, 0, 0, 1, 1, 0, 1, PLANKS));
            command(server, fill(REF, 0, 0, 2, 0, 0, 2, GLASS));
            command(server, fill(REF, 0, 0, 3, 1, 0, 3, COBBLE));
            command(server, "tp @a " + (REF.getX() + 1.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
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
        long wait = 1500L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-stock");
                area.addSubRegionBox(new Box(REF, REF.offset(2, 0, 3), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-stock", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "give @a " + COBBLE + " 1");
                    command(server, chest(A, "chest", "{Slot:0b,id:\"" + STONE + "\",count:3}"));
                    command(server, chest(B, "barrel", "{Slot:0b,id:\"" + PLANKS + "\",count:1}"));
                    command(server, chest(C, "chest", "{Slot:3b,id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":"
                            + "[{slot:0,item:{id:\"" + COBBLE + "\",count:1}}]}}"));
                    command(server, chest(E, "chest", "{Slot:0b,id:\"" + GLASS + "\",count:5}"));
                    command(server, "tp @a " + (TARGET.getX() + 3.5D) + " " + TARGET.getY() + " " + (A.getZ() + 2.5D) + " 180 30");
                });
                wait = 3500L;
            }
            case 1 ->
            {
                // A, B, C открыть и закрыть — так их запоминает индекс; E не трогаем
                if (mc.gui.screen() != null)
                {
                    mc.player.closeContainer();
                    ++this.opened;
                    this.nextAt = now + 700L;
                    return false;
                }

                if (this.opened < OPEN_FIRST.length)
                {
                    this.open(mc, OPEN_FIRST[this.opened]);
                    this.nextAt = now + 1500L;
                    return false;
                }

                hud.markDirty();
                wait = 3000L;
            }
            case 2 ->
            {
                TreeSet<String> rows = ids(hud.getEntries());
                CovOut.log("[stock] настройка выкл., строки HUD: " + rows);
                this.check("по умолчанию выключено", ! LayerListConfigs.USE_STOCK.getBooleanValue(), "");
                this.check("выкл.: в HUD все четыре строки", rows.equals(set(STONE, PLANKS, GLASS, COBBLE)), rows.toString());
                this.check("выкл.: ничего не спрятано", hud.getStockHidden() == 0, "" + hud.getStockHidden());
                shot(mc, "stock-1-off");
                LayerListConfigs.USE_STOCK.setBooleanValue(true);
                wait = 1000L;
            }
            case 3 ->
            {
                TreeSet<String> rows = ids(hud.getEntries());
                CovOut.log("[stock] настройка вкл., строки HUD: " + rows + ", скрыто " + hud.getStockHidden());
                this.check("вкл.: камень (весь в сундуке A) спрятан", ! rows.contains(STONE), "");
                this.check("вкл.: булыжник (инвентарь + шалкер в C) спрятан", ! rows.contains(COBBLE), "");
                this.check("вкл.: доски (в бочке только 1 из 2) остались", rows.contains(PLANKS), "");
                this.check("вкл.: стекло (сундук E не открывали) осталось", rows.contains(GLASS), "");
                this.check("вкл.: подвал «скрыто 2»", hud.getStockHidden() == 2, "" + hud.getStockHidden());
                MaterialListEntry planks = find(hud.getEntries(), PLANKS);
                this.check("вкл.: нехватка досок 1 (2 − 1 в бочке)", planks != null && hud.getShortfall(planks) == 1,
                        planks == null ? "нет строки" : "" + hud.getShortfall(planks));
                shot(mc, "stock-2-on");
                this.open(mc, A);
                wait = 1500L;
            }
            case 4 ->
            {
                TreeSet<String> rows = ids(hud.getEntries());
                CovOut.log("[stock] открыт сундук A (" + screenName(mc) + "), строки HUD: " + rows);
                this.check("открыт сундук: экран хранилища", mc.gui.screen() != null, "");
                this.check("открыт сундук: список полный (для переноса)", rows.equals(set(STONE, PLANKS, GLASS, COBBLE)), rows.toString());
                this.check("открыт сундук: страница для «Перенести» с камнем",
                        ids(hud.getPageEntries()).contains(STONE), "");
                shot(mc, "stock-3-chest-open");
                mc.player.closeContainer();
                wait = 1000L;
            }
            case 5 ->
            {
                TreeSet<String> rows = ids(hud.getEntries());
                CovOut.log("[stock] сундук закрыт, строки HUD: " + rows);
                this.check("сундук закрыт: снова спрятано 2", hud.getStockHidden() == 2 && ! rows.contains(STONE), rows.toString());
                this.open(mc, E);
                wait = 1500L;
            }
            case 6 ->
            {
                mc.player.closeContainer();
                wait = 1500L;
            }
            case 7 ->
            {
                TreeSet<String> rows = ids(hud.getEntries());
                CovOut.log("[stock] открыли сундук E со стеклом, строки HUD: " + rows + ", скрыто " + hud.getStockHidden());
                this.check("после открытия E стекло спрятано", ! rows.contains(GLASS), rows.toString());
                this.check("осталось только доски", rows.equals(set(PLANKS)), rows.toString());
                shot(mc, "stock-4-after-e");
                lastTab("MATERIALS");
                GuiSchematics.open(null);
                wait = 2500L;
            }
            case 8 ->
            {
                Object pane = pane(mc);
                Widget hudButton = (Widget) field(pane, "hudButton");

                if (hudButton != null)
                {
                    SupportTest.click(mc, hudButton.x() + hudButton.w() / 2.0, hudButton.y() + hudButton.h() / 2.0);
                }

                wait = 2500L;
            }
            case 9 ->
            {
                Object pane = pane(mc);
                Widget toggle = (Widget) field(pane, "stockAware");
                TreeSet<String> shown = shownIds(pane);
                CovOut.log("[stock] таблица «Список HUD», тумблер склада вкл., строки: " + shown);
                this.check("таблица: тумблер «С учётом склада» виден", toggle != null && toggle.visible(), "");
                this.check("таблица (склад вкл.): только доски", shown.equals(set(PLANKS)), shown.toString());
                shot(mc, "stock-5-table-on");

                if (toggle != null)
                {
                    SupportTest.click(mc, toggle.x() + toggle.w() / 2.0, toggle.y() + toggle.h() / 2.0);
                }

                wait = 1500L;
            }
            case 10 ->
            {
                Object pane = pane(mc);
                TreeSet<String> shown = shownIds(pane);
                CovOut.log("[stock] клик по тумблеру: настройка " + LayerListConfigs.USE_STOCK.getBooleanValue() + ", строки " + shown);
                this.check("клик по тумблеру выключил общую настройку", ! LayerListConfigs.USE_STOCK.getBooleanValue(), "");
                this.check("таблица (склад выкл.): все четыре", shown.equals(set(STONE, PLANKS, GLASS, COBBLE)), shown.toString());
                this.check("HUD тоже вернул все четыре", ids(hud.getEntries()).size() == 4 && hud.getStockHidden() == 0,
                        ids(hud.getEntries()).toString());
                shot(mc, "stock-6-table-off");
                mc.gui.setScreen(null);
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[stock] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    /** Сундуки прошлых прогонов этого мира (доски, стекло…) тоже в счёте склада — для чистого опыта их забыть. */
    @SuppressWarnings("unchecked")
    private static void forgetChests(Minecraft mc)
    {
        String scope = StorageIndex.scope(mc);
        java.util.Map<String, StorageIndex.Rec> records =
                (java.util.Map<String, StorageIndex.Rec>) field(StorageIndex.getInstance(), "records");

        if (records != null)
        {
            int before = records.size();
            records.values().removeIf(rec -> scope.equals(rec.scope));
            CovOut.log("[stock] индекс: забыто записей этого мира " + (before - records.size()));
        }
    }

    private void open(Minecraft mc, BlockPos pos)
    {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false));
    }

    private static String screenName(Minecraft mc)
    {
        return mc.gui.screen() == null ? "нет экрана" : mc.gui.screen().getClass().getSimpleName();
    }

    private static TreeSet<String> ids(List<MaterialListEntry> entries)
    {
        TreeSet<String> ids = new TreeSet<>();

        for (MaterialListEntry entry : entries)
        {
            ids.add(StorageIndex.itemId(entry.getStack()));
        }

        return ids;
    }

    private static MaterialListEntry find(List<MaterialListEntry> entries, String id)
    {
        for (MaterialListEntry entry : entries)
        {
            if (id.equals(StorageIndex.itemId(entry.getStack())))
            {
                return entry;
            }
        }

        return null;
    }

    private static TreeSet<String> set(String... ids)
    {
        return new TreeSet<>(List.of(ids));
    }

    @SuppressWarnings("unchecked")
    private static TreeSet<String> shownIds(Object pane)
    {
        TreeSet<String> ids = new TreeSet<>();
        Object shown = field(pane, "shown");

        if (shown instanceof List<?> list)
        {
            for (Object line : list)
            {
                ids.add(((MaterialLine) line).row().key());
            }
        }

        return ids;
    }

    private static Object pane(Minecraft mc)
    {
        return mc.gui.screen() instanceof GuiSchematics gui ? KProbe.fieldNamedType(gui, "MaterialsPane") : null;
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
            CovOut.log("[stock] нет поля " + name + ": " + e);
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
            CovOut.log("[stock] вкладку не выбрать: " + e);
        }
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[stock] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private static String chest(BlockPos pos, String block, String items)
    {
        return String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s[facing=south]{Items:[%s]}", pos.getX(), pos.getY(),
                pos.getZ(), block, items);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[stock] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[stock] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        LayerListConfigs.USE_STOCK.setBooleanValue(false);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }
}
