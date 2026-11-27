package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.BannerCard;
import com.aell.layerlist.hud.BannerPin;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.kit.ContextMenu;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.ScrollList;
import com.aell.layerlist.ui.kit.Table;
import com.aell.layerlist.ui.kit.Toasts;
import com.mojang.blaze3d.platform.InputConstants;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code bannerpin} (24.11.2026): рецепт флага во вкладке «Материалы» и закрепление у ткацкого станка. Просьба
 * владельца: «чтобы крафт флагов так же открывался в списке материалов, при наведении на флаг, и чтобы его можно было
 * закрепить, например, в ткацком станке через контекстное меню списка материалов».
 *
 * <p>Схема: два флага с тремя узорами (крипер, кайма, поток), флаг с полосой и камень. Настоящие движения мыши и
 * щелчки: над столбцом счёта карточки нет, над названием флага она открывается, остаётся под курсором, листается
 * колёсиком и стрелкой, щелчок по узору ведёт на «Как получить» и не достаётся строке; под открытым меню её нет.
 * Пункт меню закрепляет рецепт — у окна ткацкого станка стоит карточка, она следит за флагом в ячейке станка,
 * переживает «перезапуск» (файл настроек) и снимается крестиком.
 */
@Environment(EnvType.CLIENT)
public final class BannerPinTest implements CovJob
{
    private static final String TAG = "[bannerpin] ";
    private static final BlockPos REF = new BlockPos(1260, 100, 0);
    private static final BlockPos TARGET = new BlockPos(1260, 100, 14);
    private static final BlockPos LOOM = TARGET.offset(1, 0, 8);
    private static final String CREEPER = "{pattern:\"minecraft:creeper\",color:\"red\"}";
    private static final String THREE = "[" + CREEPER + ",{pattern:\"minecraft:border\",color:\"black\"},"
            + "{pattern:\"minecraft:flow\",color:\"blue\"}]";
    private static final String STRIPE = "[{pattern:\"minecraft:stripe_top\",color:\"red\"}]";
    private static final long SHOT_HOLD_MS = 3500L;

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private boolean hudWas;
    private boolean cardWas;
    private Runnable after;
    private long afterWait;
    /** Строка флага с тремя узорами: середина названия и середина столбца счёта. */
    private double nameX;
    private double countX;
    private double rowY;
    private String rowKey = "";
    private ItemStack banner = ItemStack.EMPTY;

    @Override
    public String name()
    {
        return "bannerpin (рецепт флага в «Материалах» и у ткацкого станка)";
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
        this.cardWas = LayerListConfigs.BANNER_CARD.getBooleanValue();
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.BANNER_CARD.setBooleanValue(true);
        BannerPin.unpin();

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1250 -10 1280 40");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -3, -1, -3, 9, -1, 11, "minecraft:stone"));
                command(server, fill(o, -3, 0, -3, 9, 5, 11, "minecraft:air"));
            }

            command(server, set(REF, 0, "minecraft:white_banner[rotation=0]{patterns:" + THREE + "}"));
            command(server, set(REF, 1, "minecraft:white_banner[rotation=0]{patterns:" + THREE + "}"));
            command(server, set(REF, 2, "minecraft:white_banner[rotation=0]{patterns:" + STRIPE + "}"));
            command(server, set(REF, 3, "minecraft:cobblestone"));
            command(server, set(REF, 4, "minecraft:oak_planks"));
            command(server, set(REF, 5, "minecraft:glass"));
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 5.5D));
        });

        this.nextAt = Util.getMillis() + 3500L;
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

        if (this.after != null)
        {
            Runnable action = this.after;
            this.after = null;
            action.run();
            this.nextAt = now + this.afterWait;
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        long wait = 900L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-bannerpin");
                area.addSubRegionBox(new Box(REF, REF.offset(5, 0, 0), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-bannerpin", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> {
                    // В творческом окно инвентаря другое — дальше стенд идёт в выживании
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:loom", LOOM.getX(), LOOM.getY(), LOOM.getZ()));
                    command(server, "item replace entity @a hotbar.0 with minecraft:white_banner 1");
                    command(server, "item replace entity @a hotbar.1 with minecraft:white_banner[banner_patterns=[" + CREEPER + "]] 1");
                    command(server, "item replace entity @a hotbar.2 with minecraft:white_banner[banner_patterns=" + THREE + "] 1");
                    command(server, "item replace entity @a hotbar.3 with minecraft:red_banner 1");
                    command(server, "tp @a " + (LOOM.getX() + 0.5D) + " " + LOOM.getY() + " " + (LOOM.getZ() + 2.5D) + " 180 30");
                });
                wait = 3500L;
            }
            case 1 ->
            {
                lastTab("MATERIALS");
                GuiSchematics.open(null);
                SupportTest.moveMouse(mc, 4.0D, 4.0D);
                wait = 5000L;
            }
            case 2 ->
            {
                boolean found = this.findRow(mc);
                this.check("во вкладке «Материалы» есть строка флага с тремя узорами", found, this.rowsText(mc));

                if (! found)
                {
                    this.stage = 1000;
                    return false;
                }

                // Сначала — столбец счёта той же строки: там своя подсказка, карточки быть не должно
                SupportTest.moveMouse(mc, this.countX, this.rowY);
            }
            case 3 ->
            {
                this.check("курсор на столбце счёта строки флага — карточки нет", this.cardBounds(mc) == null, this.cardState(mc));
                SupportTest.moveMouse(mc, this.nameX, this.rowY);
            }
            case 4 ->
            {
                // Основа, три красителя, два предмета-узора, станок = 7 строк → 2 страницы + 3 шага
                this.check("курсор на названии флага — карточка открыта на «Что нужно»", "страница 1/5".equals(this.cardState(mc)),
                        this.cardState(mc));
                this.check("карточка открыта у этой строки", this.rowKey.equals(String.valueOf(this.card(mc, "openKey"))),
                        String.valueOf(this.card(mc, "openKey")));
                int[] box = this.cardBounds(mc);
                Table<?> table = this.table(mc);
                this.check("карточка целиком в области таблицы, правее курсора и под строкой флага (строка видна)",
                        box != null && table != null
                        && box[0] >= table.x() && box[1] >= table.y() && box[0] + box[2] <= table.x() + table.w()
                        && box[1] + box[3] <= table.y() + table.h() && box[0] > this.nameX && box[1] > this.rowY,
                        box == null ? "нет" : box[0] + "," + box[1] + " " + box[2] + "×" + box[3] + ", курсор "
                                + this.nameX + "," + this.rowY);
                this.shot(mc, "1 материалы — карточка у строки флага");
                // Курсор вбок, на саму карточку: она должна остаться
                this.then(() ->
                {
                    int[] at = this.cardBounds(mc);

                    if (at != null)
                    {
                        SupportTest.moveMouse(mc, at[0] + at[2] / 2.0D, at[1] + at[3] - 30.0D);
                    }
                }, 900L);
            }
            case 5 ->
            {
                this.check("курсор перешёл на карточку — она осталась", "страница 1/5".equals(this.cardState(mc)), this.cardState(mc));
                int[] box = this.cardBounds(mc);
                Table<?> table = this.table(mc);
                int scrollBefore = table == null ? -1 : table.body().scroll();

                if (box != null && mc.screen != null)
                {
                    // Колёсико над карточкой листает её, а не таблицу
                    mc.screen.mouseScrolled(box[0] + box[2] / 2.0D, box[1] + box[3] - 30.0D, 0.0D, -1.0D);
                }

                this.check("колёсико над карточкой — вторая страница, таблица на месте", "страница 2/5".equals(this.cardState(mc))
                        && table != null && table.body().scroll() == scrollBefore, this.cardState(mc));

                if (mc.screen instanceof GuiSchematics gui)
                {
                    KProbe.press(gui, InputConstants.KEY_RIGHT);
                }
            }
            case 6 ->
            {
                this.check("стрелка → листает до первого шага", "страница 3/5".equals(this.cardState(mc)), this.cardState(mc));
                double[] spot = this.cardSpot(mc);
                this.check("узор на странице шага щёлкается", spot != null, "");

                if (spot != null)
                {
                    SupportTest.click(mc, spot[0], spot[1]);
                }
            }
            case 7 ->
            {
                this.check("щелчок по узору — «Как получить» с рецептом верстака", this.cardState(mc).contains("как получить: CRAFT"),
                        this.cardState(mc));
                Layers layers = this.layers(mc);
                this.check("щелчок достался карточке: окно замены блока не открылось", layers != null && ! layers.any(), "");
                this.shot(mc, "2 материалы — как получить узор");
                // Курсор прочь — на пустое место под строками
                this.then(() -> SupportTest.moveMouse(mc, 4.0D, 4.0D), 900L);
            }
            case 8 ->
            {
                this.check("курсор ушёл со строки и с карточки — карточка закрыта", this.cardBounds(mc) == null, this.cardState(mc));
                SupportTest.moveMouse(mc, this.nameX, this.rowY);
            }
            case 9 ->
            {
                this.check("снова на названии флага — карточка открылась заново", "страница 1/5".equals(this.cardState(mc)),
                        this.cardState(mc));
                this.rightClick(mc);
            }
            case 10 ->
            {
                ContextMenu menu = this.menu(mc);
                String pin = I18n.get("layerlist.schematics.materials.menu.banner_pin");
                this.check("ПКМ по строке флага — в меню есть «" + pin + "»", menu != null && this.menuSpot(menu, pin) != null,
                        this.menuLabels(menu));
                this.check("под открытым меню карточки нет", this.cardBounds(mc) == null, this.cardState(mc));
                this.shot(mc, "3 материалы — меню строки флага");
                this.then(() ->
                {
                    ContextMenu open = this.menu(mc);
                    double[] spot = open == null ? null : this.menuSpot(open, pin);

                    if (spot != null)
                    {
                        SupportTest.click(mc, spot[0], spot[1]);
                    }
                }, 900L);
            }
            case 11 ->
            {
                ItemStack pinned = BannerPin.pinned();
                this.check("пункт меню закрепил этот флаг", pinned != null && ItemStack.isSameItemSameComponents(pinned, this.banner),
                        String.valueOf(pinned));
                this.check("закрепление записано в файл настроек", Files.isRegularFile(pinFile()), pinFile().toString());
                Toasts toasts = mc.screen instanceof GuiSchematics gui ? KProbe.fieldOfType(gui, Toasts.class) : null;
                String said = I18n.get("layerlist.schematics.materials.toast.banner_pinned", this.banner.getHoverName().getString());
                this.check("всплывающая строка говорит, что рецепт закреплён", toasts != null
                        && Toasts.TestHooks.texts(toasts).stream().anyMatch(line -> line.contains(said)),
                        toasts == null ? "нет" : Toasts.TestHooks.texts(toasts).toString());
                this.shot(mc, "4 материалы — рецепт закреплён");
                // Всплывающая строка живёт 5 секунд и забирает щелчки по себе — ждём, пока уйдёт
                wait = 6500L;
            }
            case 12 -> this.rightClick(mc);
            case 13 ->
            {
                ContextMenu menu = this.menu(mc);
                String unpin = I18n.get("layerlist.schematics.materials.menu.banner_unpin");
                this.check("у закреплённого флага в меню — «" + unpin + "»", menu != null && this.menuSpot(menu, unpin) != null,
                        this.menuLabels(menu));
                mc.setScreen(null);
                this.then(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(LOOM).add(0, 0, 0.5), Direction.SOUTH, LOOM, false)), 2500L);
            }
            case 14 ->
            {
                this.check("открыт ткацкий станок", mc.screen instanceof LoomScreen, String.valueOf(mc.screen));
                int[] box = BannerPin.TestHooks.bounds();
                this.check("у станка стоит закреплённая карточка, за флагом ещё не следит",
                        box != null && BannerPin.TestHooks.state().equals("страница 1/5, сделано шагов -1"), BannerPin.TestHooks.state());
                this.check("карточка рядом с окном станка, не поверх него и целиком на экране", box != null
                        && this.besideWindow(mc, box), box == null ? "нет" : box[0] + "," + box[1] + " " + box[2] + "×" + box[3]);
                this.shot(mc, "5 станок — закреплённая карточка");
                // Чистый белый флаг — в ячейку станка
                this.then(() -> this.quickMove(mc, 0), 1500L);
            }
            case 15 ->
            {
                this.check("чистый флаг в станке — карточка сама открыла первый шаг",
                        BannerPin.TestHooks.state().equals("страница 3/5, сделано шагов 0"), BannerPin.TestHooks.state());
                this.shot(mc, "6 станок — флаг в ячейке, шаг 1");
                // Флаг обратно, вместо него — флаг с уже нанесённым первым узором
                this.then(() ->
                {
                    if (mc.screen instanceof LoomScreen loom)
                    {
                        mc.gameMode.handleInventoryMouseClick(loom.getMenu().containerId, loom.getMenu().getBannerSlot().index, 0,
                                ClickType.QUICK_MOVE, mc.player);
                    }

                    this.quickMove(mc, 1);
                }, 1500L);
            }
            case 16 ->
            {
                this.check("флаг с первым узором в станке — открыт второй шаг, первый отмечен",
                        BannerPin.TestHooks.state().equals("страница 4/5, сделано шагов 1"), BannerPin.TestHooks.state());
                this.shot(mc, "7 станок — первый узор сделан, шаг 2");
                // Тот же флаг, что закреплён, под курсором с Shift: второй карточки не нужно
                this.then(() ->
                {
                    BannerCard.TestHooks.shift(true);
                    double[] at = this.inventorySlot(mc, 2);

                    if (at != null)
                    {
                        SupportTest.moveMouse(mc, at[0], at[1]);
                    }
                }, 900L);
            }
            case 17 ->
            {
                this.check("Shift и курсор на закреплённом флаге — второй карточки нет", BannerCard.bounds() == null, BannerCard.state());
                double[] at = this.loomBannerSlot(mc);

                if (at != null)
                {
                    SupportTest.moveMouse(mc, at[0], at[1]);
                }
            }
            case 18 ->
            {
                // В ячейке станка — другой флаг (один узор: основа, краситель, предмет-узор, станок = 1 страница + 1 шаг):
                // у него карточка под курсором своя, поверх закреплённой
                this.check("Shift и курсор на другом узорном флаге — его карточка открылась", "страница 1/2".equals(BannerCard.state()),
                        BannerCard.state());
                double[] at = this.loomBannerSlot(mc);

                if (at != null && mc.screen != null)
                {
                    // На страницу шага: теперь полотна флагов рисуют обе карточки сразу
                    mc.screen.mouseScrolled(at[0], at[1], 0.0D, -1.0D);
                }

                this.check("колёсико над флагом листает карточку под курсором, закреплённая на своей странице",
                        "страница 2/2".equals(BannerCard.state())
                        && BannerPin.TestHooks.state().equals("страница 4/5, сделано шагов 1"),
                        BannerCard.state() + " / " + BannerPin.TestHooks.state());
                wait = 1200L;
            }
            case 19 ->
            {
                this.shot(mc, "8 станок — две карточки, у каждой свои полотна");
                this.then(() ->
                {
                    BannerCard.TestHooks.shift(null);
                    SupportTest.moveMouse(mc, 4.0D, 4.0D);
                    // «Перезапуск игры»: забыть собранный предмет и перечитать файл
                    BannerPin.TestHooks.reload();
                }, 1500L);
            }
            case 20 ->
            {
                ItemStack pinned = BannerPin.pinned();
                this.check("после перечитывания файла закреплён тот же флаг", pinned != null
                        && ItemStack.isSameItemSameComponents(pinned, this.banner), String.valueOf(pinned));
                this.check("карточка у станка на месте", BannerPin.TestHooks.bounds() != null, BannerPin.TestHooks.state());
                double[] cross = BannerPin.TestHooks.unpinSpot();
                this.check("у закреплённой карточки есть крестик", cross != null, "");

                if (cross != null)
                {
                    SupportTest.click(mc, cross[0], cross[1]);
                }
            }
            case 21 ->
            {
                this.check("крестик открепил рецепт: карточки нет, файл убран", BannerPin.pinned() == null
                        && BannerPin.TestHooks.bounds() == null && ! Files.exists(pinFile()), BannerPin.TestHooks.state());
                this.check("щелчок по крестику достался карточке: предмет на курсор не взят",
                        mc.screen instanceof LoomScreen loom && loom.getMenu().getCarried().isEmpty(), "");
                this.shot(mc, "9 станок — рецепт откреплён");
            }
            default ->
            {
                if (mc.screen != null)
                {
                    mc.player.closeContainer();
                    mc.setScreen(null);
                }

                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + (this.after != null ? SHOT_HOLD_MS : wait);
        ++this.stage;
        return false;
    }

    // ===== Вкладка «Материалы» =====

    private Object pane(Minecraft mc)
    {
        return mc.screen instanceof GuiSchematics gui ? KProbe.fieldNamedType(gui, "MaterialsPane") : null;
    }

    private Table<?> table(Minecraft mc)
    {
        return field(this.pane(mc), "table") instanceof Table<?> table ? table : null;
    }

    private Layers layers(Minecraft mc)
    {
        return mc.screen instanceof GuiSchematics gui ? KProbe.fieldOfType(gui, Layers.class) : null;
    }

    private ContextMenu menu(Minecraft mc)
    {
        Layers layers = this.layers(mc);
        return layers != null && layers.top() instanceof ContextMenu menu ? menu : null;
    }

    /** Строка флага с тремя узорами: где на экране её название и столбец счёта. */
    private boolean findRow(Minecraft mc)
    {
        Table<?> table = this.table(mc);

        if (table == null)
        {
            return false;
        }

        ScrollList<?> body = table.body();
        double name = body.x() + 70.0D;

        for (int y = body.y(); y < body.y() + body.h(); ++y)
        {
            int index = body.indexAt(name, y);

            if (index < 0 || ! (body.items().get(index) instanceof MaterialLine line))
            {
                continue;
            }

            BannerPatternLayers layers = line.stack().getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

            if (line.stack().getItem() instanceof BannerItem && layers.layers().size() == 3)
            {
                this.nameX = name;
                this.countX = body.x() + body.rowWidth() - 14.0D;
                // Середина строки: первая её точка плюс половина высоты
                this.rowY = y + Ui.ROW_H / 2.0D;
                this.rowKey = line.key();
                this.banner = line.stack().copyWithCount(1);
                return true;
            }
        }

        return false;
    }

    private String rowsText(Minecraft mc)
    {
        Table<?> table = this.table(mc);

        if (table == null)
        {
            return "таблицы нет";
        }

        StringBuilder text = new StringBuilder();

        for (Object item : table.body().items())
        {
            text.append(item instanceof MaterialLine line ? line.name() : String.valueOf(item)).append("; ");
        }

        return text.isEmpty() ? "строк нет" : text.toString();
    }

    private Object card(Minecraft mc, String method, Object... args)
    {
        Object card = field(this.pane(mc), "bannerCard");

        if (card == null)
        {
            return null;
        }

        try
        {
            return KProbe.call(card, method, args);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log(TAG + "карточка строки: не вызвать " + method + ": " + e);
            return null;
        }
    }

    private String cardState(Minecraft mc)
    {
        return String.valueOf(this.card(mc, "state"));
    }

    private int[] cardBounds(Minecraft mc)
    {
        return this.card(mc, "bounds") instanceof int[] box ? box : null;
    }

    private double[] cardSpot(Minecraft mc)
    {
        return this.card(mc, "infoSpot", 0) instanceof double[] spot ? spot : null;
    }

    private void rightClick(Minecraft mc)
    {
        if (! SupportTest.moveMouse(mc, this.nameX, this.rowY))
        {
            return;
        }

        var window = mc.getWindow();
        DevInput.onButton(mc.mouseHandler, window.handle(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0), InputConstants.PRESS);
        DevInput.onButton(mc.mouseHandler, window.handle(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0), InputConstants.RELEASE);
    }

    /** Середина пункта меню с такой подписью. */
    private double[] menuSpot(ContextMenu menu, String label)
    {
        List<ContextMenu.Item> items = KProbe.fieldOfType(menu, List.class);
        Object x = field(menu, "x");
        Object y = field(menu, "y");
        Object w = field(menu, "w");

        if (items == null || ! (x instanceof Integer mx) || ! (y instanceof Integer my) || ! (w instanceof Integer mw))
        {
            return null;
        }

        int rowY = my + Ui.PAD_MICRO;

        for (ContextMenu.Item item : items)
        {
            if (item.isSeparator())
            {
                rowY += Ui.PAD_REL + 1;
                continue;
            }

            if (item.label().equals(label) && item.enabled())
            {
                return new double[]{mx + mw / 2.0D, rowY + Ui.HEAD_BTN_H / 2.0D};
            }

            rowY += Ui.HEAD_BTN_H;
        }

        return null;
    }

    private String menuLabels(ContextMenu menu)
    {
        if (menu == null)
        {
            return "меню нет";
        }

        List<ContextMenu.Item> items = KProbe.fieldOfType(menu, List.class);
        StringBuilder text = new StringBuilder();

        for (ContextMenu.Item item : items == null ? List.<ContextMenu.Item>of() : items)
        {
            text.append(item.isSeparator() ? "—" : item.label()).append("; ");
        }

        return text.toString();
    }

    // ===== Ткацкий станок =====

    private static Path pinFile()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("layerlist_banner_pin.json");
    }

    /** Карточка целиком на экране и не пересекается с окном станка. */
    private boolean besideWindow(Minecraft mc, int[] box)
    {
        if (! (mc.screen instanceof LoomScreen loom))
        {
            return false;
        }

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) loom;
        int left = geometry.layerlist_getLeftPos();
        int width = geometry.layerlist_getImageWidth();
        boolean apart = box[0] + box[2] <= left || box[0] >= left + width;
        return apart && box[0] >= 0 && box[1] >= 0 && box[0] + box[2] <= mc.getWindow().getGuiScaledWidth()
                && box[1] + box[3] <= mc.getWindow().getGuiScaledHeight();
    }

    /** Shift-щелчок по ячейке панели быстрого доступа: флаг уходит в ячейку станка. */
    private void quickMove(Minecraft mc, int hotbar)
    {
        if (! (mc.screen instanceof LoomScreen loom))
        {
            return;
        }

        for (Slot slot : loom.getMenu().slots)
        {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == hotbar)
            {
                mc.gameMode.handleInventoryMouseClick(loom.getMenu().containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
                return;
            }
        }
    }

    private double[] inventorySlot(Minecraft mc, int inventorySlot)
    {
        if (! (mc.screen instanceof LoomScreen loom))
        {
            return null;
        }

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) loom;

        for (Slot slot : loom.getMenu().slots)
        {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventorySlot)
            {
                return new double[]{geometry.layerlist_getLeftPos() + slot.x + 8.0D, geometry.layerlist_getTopPos() + slot.y + 8.0D};
            }
        }

        return null;
    }

    private double[] loomBannerSlot(Minecraft mc)
    {
        if (! (mc.screen instanceof LoomScreen loom))
        {
            return null;
        }

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) loom;
        Slot slot = loom.getMenu().getBannerSlot();
        return new double[]{geometry.layerlist_getLeftPos() + slot.x + 8.0D, geometry.layerlist_getTopPos() + slot.y + 8.0D};
    }

    // ===== Общее =====

    private void then(Runnable action, long wait)
    {
        this.after = action;
        this.afterWait = wait;
    }

    private void shot(Minecraft mc, String name)
    {
        CovOut.log(TAG + "снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
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
            CovOut.log(TAG + "нет поля " + name + ": " + e);
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
            CovOut.log(TAG + "вкладку не выбрать: " + e);
        }
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
        BannerCard.TestHooks.shift(null);
        BannerPin.unpin();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.HUD_ENABLED = this.hudWas;
        LayerListConfigs.BANNER_CARD.setBooleanValue(this.cardWas);
    }
}
