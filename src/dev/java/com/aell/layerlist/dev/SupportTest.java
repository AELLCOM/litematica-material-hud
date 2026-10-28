package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.ghost.GhostSupport;
import com.aell.layerlist.hud.TransferButton;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code u}: голограммы и опора, кнопка «Перенести всё необходимое» (запрос 08.10.2026).
 *
 * <p>Эталон снимается с мира: под четырьмя блоками потолка висят подвесная табличка, светящиеся ягоды
 * (два звена), бледный свисающий мох и лиана; рядом — камень и факел на полу как контроль. В целевом месте
 * пол есть, потолка нет.
 * <ol>
 *   <li>«Скрывать блоки без опоры»: выкл — ничего не убрано; вкл — убраны ровно пять висящих клеток, камень и
 *       факел на полу остаются; потолок поставлен — висящие (и нижнее звено ягод через верхнее) снова видны.</li>
 *   <li>«Опора с соседнего слоя»: один слой (уровень висящих), потолок снова снят — четыре клетки потолка
 *       становятся опорами, считаются «внутри слоя», дорисовываются; Easy Place в выживании ставит одну из
 *       них, и она пропадает из опор.</li>
 *   <li>Кнопка в настоящем сундуке: стоит над панелью вровень с верхом окна, щелчок забирает камень, таблички и
 *       ягоды, землю (её в схеме нет) не трогает.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class SupportTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(520, 100, 0);
    private static final BlockPos TARGET = new BlockPos(520, 100, 14);
    private static final BlockPos CHEST = TARGET.offset(6, 0, 5);
    /** Второй сундук: повторный перенос, «Auto», сверка с сервером, опоры в переносе (запрос 09.10.2026). */
    private static final BlockPos CHEST_B = TARGET.offset(3, 0, 5);
    private static final String CHEST_B_ITEMS = "{Items:[{Slot:0b,id:\"minecraft:stone\",count:64},"
            + "{Slot:1b,id:\"minecraft:oak_hanging_sign\",count:3},{Slot:2b,id:\"minecraft:glow_berries\",count:5}]}";
    private static final int[][] CEILING = {{1, 3, 1}, {3, 3, 1}, {5, 3, 1}, {7, 3, 1}};
    private static final int[][] HANGING = {{1, 2, 1}, {3, 2, 1}, {3, 1, 1}, {5, 2, 1}, {7, 2, 1}};
    private static final int[][] GROUNDED = {{9, 0, 1}, {11, 0, 1}};
    private static final long PLACE_MS = 8000L;
    /** Откуда снимать: сзади-снизу, весь ряд висящих и потолок в кадре. */
    private static final String VIEW = "tp @a " + (TARGET.getX() + 6.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 8.5D)
            + " 180 -10";

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private long placeDeadline;
    private long lastTick = Long.MIN_VALUE;

    @Override
    public String name()
    {
        return "U (голограммы и опора, кнопка переноса)";
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

        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 510 -10 540 30");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 14, -1, 8, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 14, 6, 8, "minecraft:air"));
            }

            for (int[] c : CEILING)
            {
                command(server, set(REF, c, "minecraft:stone"));
            }

            command(server, set(REF, HANGING[0], "minecraft:oak_hanging_sign[rotation=0,attached=false]"));
            command(server, set(REF, HANGING[1], "minecraft:cave_vines_plant[berries=true]"));
            command(server, set(REF, HANGING[2], "minecraft:cave_vines[berries=true,age=0]"));
            command(server, set(REF, HANGING[3], "minecraft:pale_hanging_moss[tip=true]"));
            command(server, set(REF, HANGING[4], "minecraft:vine[up=true]"));
            command(server, set(REF, GROUNDED[0], "minecraft:stone"));
            command(server, set(REF, GROUNDED[1], "minecraft:torch"));
            command(server, "tp @a " + (REF.getX() + 6.5D) + " " + REF.getY() + " " + (REF.getZ() + 6.5D));
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
        long wait = 4000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-support");
                area.addSubRegionBox(new Box(REF, REF.offset(12, 3, 2), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-support", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                server.execute(() -> command(server, VIEW));
                wait = 5000L;
            }
            case 1 ->
            {
                GhostSupport.takeStats();
                SchematicWorldRefresher.INSTANCE.updateAll();
                LayerVerifier.getInstance().toggle();
            }
            case 2 ->
            {
                int[] stats = GhostSupport.takeStats();
                this.check("тумблер выключен — ничего не убрано", stats[0] == 0 && stats[1] == 0,
                        "убрано " + stats[0] + ", опор " + stats[1]);
                this.checkCells(mc, "без потолка", true);
                this.missingOff = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                this.markedOff = new java.util.HashSet<>(LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.MISSING));
                this.check("Ctrl+B без тумблера — висящие клетки в «пропущено»", this.hangingMarked() == HANGING.length,
                        "рамок у висящих " + this.hangingMarked() + ", пропущено " + this.missingOff);
                LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(true);
            }
            case 3 ->
            {
                int[] stats = GhostSupport.takeStats();
                this.check("тумблер включён — сборка убрала висящие клетки", stats[0] >= HANGING.length,
                        "убрано " + stats[0]);
                int missing = LayerVerifier.getInstance().getCount(LayerVerifier.Category.MISSING);
                java.util.Set<BlockPos> gone = new java.util.HashSet<>(this.markedOff);
                gone.removeAll(LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.MISSING));
                java.util.Set<BlockPos> hanging = new java.util.HashSet<>();

                for (int[] c : HANGING)
                {
                    hanging.add(at(TARGET, c));
                }

                this.check("Ctrl+B с тумблером — висящих без опоры не ищет, остальное на месте",
                        this.hangingMarked() == 0 && gone.equals(hanging),
                        "пропущено " + missing + " (было " + this.missingOff + "), ушли рамки " + gone);
                shot(mc, "без опоры скрыто");
                wait = 1500L;
            }
            case 4 ->
            {
                server.execute(() -> {
                    for (int[] c : CEILING)
                    {
                        command(server, set(TARGET, c, "minecraft:stone"));
                    }
                });
                wait = 3000L;
            }
            case 5 ->
            {
                this.checkCells(mc, "потолок поставлен", false);
                GhostSupport.takeStats();
                SchematicWorldRefresher.INSTANCE.updateAll();
            }
            case 6 ->
            {
                int[] stats = GhostSupport.takeStats();
                this.check("потолок поставлен — сборка ничего не убрала", stats[0] == 0, "убрано " + stats[0]);
                this.check("Ctrl+B: потолок поставлен — висящие снова в «пропущено»", this.hangingMarked() == HANGING.length,
                        "рамок у висящих " + this.hangingMarked());
                LayerVerifier.getInstance().toggle();
                shot(mc, "опора есть — видно");
                wait = 1500L;
            }
            case 7 ->
            {
                // Опора с соседнего слоя: потолок снова снят, видимый слой — уровень висящих
                LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
                server.execute(() -> {
                    for (int[] c : CEILING)
                    {
                        command(server, set(TARGET, c, "minecraft:air"));
                    }
                });
                LayerRange range = DataManager.getRenderLayerRange();
                range.setLayerMode(LayerMode.SINGLE_LAYER);
                range.setAxis(Direction.Axis.Y);
                range.setLayerSingle(TARGET.getY() + 2);
                GhostSupport.takeStats();
                LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(true);
            }
            case 8 ->
            {
                LayerRange range = DataManager.getRenderLayerRange();
                int count = GhostSupport.supportCount();
                this.check("опор вне слоя — ровно четыре клетки потолка", count == CEILING.length, "опор " + count);
                boolean all = true;

                for (int[] c : CEILING)
                {
                    BlockPos pos = at(TARGET, c);
                    all &= GhostSupport.isSupportCell(pos.getX(), pos.getY(), pos.getZ()) && range.isPositionWithinRange(pos);
                }

                this.check("клетки потолка — «внутри слоя» для прицела", all, "");
                this.check("пустая клетка над слоем — вне слоя",
                        ! range.isPositionWithinRange(TARGET.offset(9, 3, 1)) && ! range.isPositionWithinRange(TARGET.offset(1, 1, 1)), "");
                int[] stats = GhostSupport.takeStats();
                this.check("опоры дорисованы сборкой чанка", stats[1] >= CEILING.length, "дорисовано " + stats[1]);
                this.check("опоры в списке материалов слоя — камень ×4", this.stoneMissing() == CEILING.length,
                        "камень в списке " + this.stoneMissing());
                shot(mc, "опора с соседнего слоя");
                wait = 1500L;
            }
            case 9 ->
            {
                // Easy Place в выживании ставит опору над табличкой
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, "effect give @a minecraft:saturation 600 1 true");
                    command(server, "item replace entity @a hotbar.0 with minecraft:stone 16");
                    command(server, "tp @a " + (TARGET.getX() + 1.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 4.5D) + " 180 -30");
                });
                wait = 2000L;
                this.placeDeadline = now + wait + PLACE_MS;
            }
            case 10 ->
            {
                BlockPos cell = at(TARGET, CEILING[0]);
                boolean placed = mc.level.getBlockState(cell).is(Blocks.STONE);

                if (! placed && now < this.placeDeadline)
                {
                    this.aimAndPlace(mc, Vec3.atCenterOf(cell).add(0.0D, 0.0D, 0.5D));
                    return false;
                }

                this.check("Easy Place поставил опору вне слоя", placed, "в мире " + mc.level.getBlockState(cell)
                        + ", в руке " + mc.player.getMainHandItem());
                wait = 2500L;
            }
            case 11 ->
            {
                int count = GhostSupport.supportCount();
                this.check("поставленная опора ушла из списка", count == CEILING.length - 1, "опор " + count);
                this.check("в списке материалов опор стало меньше — камень ×3", this.stoneMissing() == CEILING.length - 1,
                        "камень в списке " + this.stoneMissing());
                LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                wait = 3000L;
            }
            case 12 ->
            {
                // С 13.10.2026 опоры в списке — по схеме и без опции: опция только про голограммы и Easy Place
                this.check("опция выключена — опоры в списке остаются, камень ×3", this.stoneMissing() == CEILING.length - 1,
                        "камень в списке " + this.stoneMissing());
                DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

                // Кнопка переноса: настоящий сундук, инвентарь пуст
                LayerListConfigs.HUD_ENABLED = true;
                LayerListConfigs.ONLY_MISSING.setBooleanValue(true);
                LayerListConfigs.HUD_BESIDE_CONTAINER.setBooleanValue(true);
                LayerListConfigs.HUD_TRANSFER_BUTTON.setBooleanValue(true);
                server.execute(() -> {
                    // Выживание: в творческом Axiom и подобные перехватывают правый клик
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]{Items:["
                                    + "{Slot:0b,id:\"minecraft:stone\",count:64},{Slot:1b,id:\"minecraft:oak_hanging_sign\",count:3},"
                                    + "{Slot:2b,id:\"minecraft:dirt\",count:10},{Slot:3b,id:\"minecraft:glow_berries\",count:5}]}",
                            CHEST.getX(), CHEST.getY(), CHEST.getZ()));
                    command(server, "tp @a " + (CHEST.getX() + 0.5D) + " " + CHEST.getY() + " " + (CHEST.getZ() + 2.5D) + " 180 30");
                });
                wait = 3000L;
            }
            case 13 ->
            {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
                wait = 2500L;
            }
            case 14 ->
            {
                Screen screen = GuiUtils.getCurrentScreen();
                int[] bounds = TransferButton.bounds();
                this.check("сундук открыт", screen instanceof AbstractContainerScreen<?>, String.valueOf(screen));

                if (screen instanceof AbstractContainerScreen<?> container)
                {
                    int top = ((IMixinAbstractContainerScreen) container).layerlist_getTopPos();
                    this.check("кнопка нарисована", bounds[2] > 0, java.util.Arrays.toString(bounds));
                    this.check("кнопка вровень с верхом окна, не выше", bounds[1] == Math.max(0, top),
                            "кнопка y=" + bounds[1] + ", окно y=" + top);
                    var dock = com.aell.layerlist.hud.LayerHudRenderer.dockBesideContainer(screen);
                    this.check("панель под кнопкой", dock != null && dock.hasButton()
                                    && dock.y() >= bounds[1] + TransferButton.HEIGHT,
                            dock == null ? "нет стыковки" : "панель y=" + dock.y());
                }

                shot(mc, "кнопка над панелью");

                if (bounds[2] > 0 && screen != null)
                {
                    // Настоящим путём мыши (MouseHandler.onButton), а не screen.mouseClicked: моды вроде
                    // Inventory Profiles Next перехватывают щелчок раньше экрана
                    click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + 7.0D);
                }

                wait = 2000L;
            }
            case 15 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int signs = count(inventory, Items.OAK_HANGING_SIGN);
                int berries = count(inventory, Items.GLOW_BERRIES);
                int dirt = count(inventory, Items.DIRT);
                // Нехватка: камень 4 (три потолка и контрольный), табличка 1, ягоды 2 — ровно столько, не стопками
                this.check("щелчок забрал ровно нехватку и не тронул лишнее", stone == 4 && signs == 1 && berries == 2 && dirt == 0,
                        "камень " + stone + ", таблички " + signs + ", ягоды " + berries + ", земля " + dirt);
                shot(mc, "после переноса");
                mc.player.closeContainer();
                server.execute(() -> {
                    command(server, "effect clear @a");
                    server.getPlayerList().getPlayers().forEach(p -> p.getInventory().setItem(17,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHISELED_BOOKSHELF, 13)));
                });
                wait = 1000L;
            }
            case 16 ->
            {
                // Инвентарь игрока (экран с книгой рецептов): подсказка предмета поверх панели
                mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                wait = 1500L;
            }
            case 17 ->
            {
                if (GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?> inventory)
                {
                    IMixinAbstractContainerScreen access = (IMixinAbstractContainerScreen) inventory;
                    net.minecraft.world.inventory.Slot slot = inventory.getMenu().getSlot(17);
                    moveMouse(mc, access.layerlist_getLeftPos() + slot.x + 8.0D, access.layerlist_getTopPos() + slot.y + 8.0D);
                    CovOut.log("[support] под курсором слот 17: " + slot.getItem());
                }

                com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                wait = 1000L;
            }
            case 18 ->
            {
                int[] draws = com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                this.check("инвентарь игрока: панель рисует экран, под подсказкой", draws[1] > 0 && draws[1] == draws[0],
                        "кадров " + draws[0] + ", из экрана " + draws[1]);
                shot(mc, "подсказка поверх панели в инвентаре");
                wait = 800L;
            }
            case 19 ->
            {
                mc.gui.setScreen(com.aell.layerlist.config.GuiConfigs.create(null, null));
                com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                wait = 1500L;
            }
            case 20 ->
            {
                int[] draws = com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                this.check("меню мода: панель скрыта", draws[0] == 0, "кадров с панелью " + draws[0]);
                shot(mc, "меню мода без панели");
                mc.gui.setScreen(null);
                com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                wait = 1500L;
            }
            case 21 ->
            {
                int[] draws = com.aell.layerlist.hud.LayerHudRenderer.takeDrawStats();
                this.check("вышли из меню — панель снова видна", draws[0] > 0, "кадров с панелью " + draws[0]);
                // Второй сундук: инвентарь пуст, строка панели успевает это увидеть
                this.freshChestB(server, true);
                wait = 3000L;
            }
            case 22 ->
            {
                // Строка панели «застывает» с «есть с собой 0», а камень уже в инвентаре — как сразу после
                // первого сундука. Перенос должен считать по инвентарю сейчас и камень не брать
                LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(200);
                server.execute(() -> command(server, "give @a minecraft:stone 4"));
                wait = 700L;
            }
            case 23 ->
            {
                openChest(mc, CHEST_B);
                wait = 1500L;
            }
            case 24 ->
            {
                int[] bounds = TransferButton.bounds();
                int[] auto = TransferButton.autoBounds();
                this.check("рядом с переносом кнопка «Auto», справа и вровень", auto[2] > 0 && auto[1] == bounds[1]
                                && auto[0] >= bounds[0] + bounds[2],
                        "перенос " + java.util.Arrays.toString(bounds) + ", Auto " + java.util.Arrays.toString(auto));
                shot(mc, "кнопка Auto выключена");
                click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + 7.0D);
                wait = 2500L;
            }
            case 25 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int signs = count(inventory, Items.OAK_HANGING_SIGN);
                int berries = count(inventory, Items.GLOW_BERRIES);
                this.check("второй сундук подряд: камень не взят повторно (счёт по инвентарю сейчас)",
                        stone == 4 && signs == 1 && berries == 2,
                        "камень " + stone + ", таблички " + signs + ", ягоды " + berries);
                mc.player.closeContainer();
                LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(20);
                this.freshChestB(server, true);
                wait = 3000L;
            }
            case 26 ->
            {
                // «Auto» щелчком по самой кнопке: включаем в открытом сундуке — забирает сразу
                openChest(mc, CHEST_B);
                wait = 1500L;
            }
            case 27 ->
            {
                int[] auto = TransferButton.autoBounds();
                click(mc, auto[0] + auto[2] / 2.0D, auto[1] + 7.0D);
                this.check("щелчок по «Auto» включил автоперенос", LayerListConfigs.HUD_TRANSFER_AUTO.getBooleanValue(), "");
                wait = 2500L;
            }
            case 28 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int signs = count(inventory, Items.OAK_HANGING_SIGN);
                int berries = count(inventory, Items.GLOW_BERRIES);
                this.check("«Auto» включили в открытом сундуке — забрал ровно нехватку",
                        stone == 4 && signs == 1 && berries == 2,
                        "камень " + stone + ", таблички " + signs + ", ягоды " + berries);
                shot(mc, "кнопка Auto включена");
                mc.player.closeContainer();
                this.freshChestB(server, true);
                wait = 3000L;
            }
            case 29 ->
            {
                // Открыли хранилище при включённом «Auto» — без единого щелчка
                openChest(mc, CHEST_B);
                wait = 2500L;
            }
            case 30 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int signs = count(inventory, Items.OAK_HANGING_SIGN);
                int berries = count(inventory, Items.GLOW_BERRIES);
                this.check("«Auto»: при открытии забрал сам, ровно нехватку", stone == 4 && signs == 1 && berries == 2,
                        "камень " + stone + ", таблички " + signs + ", ягоды " + berries);
                mc.player.closeContainer();
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(false);
                this.freshChestB(server, true);
                wait = 3000L;
            }
            case 31 ->
            {
                openChest(mc, CHEST_B);
                wait = 1500L;
            }
            case 32 ->
            {
                // Расхождение с сервером: пока идут щелчки, сервер кладёт игроку ещё 3 камня — как будто
                // щелчок по части стопки разошёлся с предсказанием. Сверка должна вернуть лишнее в сундук
                int[] bounds = TransferButton.bounds();
                click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + 7.0D);
                server.execute(() -> server.getPlayerList().getPlayers().forEach(p -> {
                    p.getInventory().add(new net.minecraft.world.item.ItemStack(Items.STONE, 3));
                    p.containerMenu.broadcastChanges();
                }));
                wait = 3500L;
            }
            case 33 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                this.check("сверка с сервером: лишний камень вернулся в сундук, у игрока ровно нехватка", stone == 4,
                        "камень " + stone);
                mc.player.closeContainer();
                // Опоры в переносе: слой висящих, «опора с соседнего слоя» — камень потолка нужен как опора
                LayerRange range = DataManager.getRenderLayerRange();
                range.setLayerMode(LayerMode.SINGLE_LAYER);
                range.setAxis(Direction.Axis.Y);
                range.setLayerSingle(TARGET.getY() + 2);
                LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(true);
                this.freshChestB(server, true);
                wait = 4000L;
            }
            case 34 ->
            {
                this.supportStone = this.stoneMissing();
                this.check("опоры слоя в списке материалов (камень потолка)", this.supportStone > 0,
                        "камень в списке " + this.supportStone + ", опор " + GhostSupport.supportCount());
                openChest(mc, CHEST_B);
                wait = 1500L;
            }
            case 35 ->
            {
                int[] bounds = TransferButton.bounds();
                click(mc, bounds[0] + bounds[2] / 2.0D, bounds[1] + 7.0D);
                wait = 2500L;
            }
            case 36 ->
            {
                int stone = count(mc.player.getInventory(), Items.STONE);
                this.check("перенос забрал и опорные блоки", stone == this.supportStone && stone > 0,
                        "камень " + stone + ", опор в списке " + this.supportStone);
                mc.player.closeContainer();
                LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
                DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
                // Панель материалов скрыта (Y), «Auto» включён — автоперенос всё равно работает
                LayerListConfigs.HUD_ENABLED = false;
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(true);
                this.freshChestB(server, true);
                wait = 3500L;
            }
            case 37 ->
            {
                openChest(mc, CHEST_B);
                wait = 3000L;
            }
            case 38 ->
            {
                Inventory inventory = mc.player.getInventory();
                int stone = count(inventory, Items.STONE);
                int signs = count(inventory, Items.OAK_HANGING_SIGN);
                int berries = count(inventory, Items.GLOW_BERRIES);
                this.check("панель скрыта (Y) — «Auto» всё равно забрал нехватку", stone == 4 && signs == 1 && berries == 2,
                        "камень " + stone + ", таблички " + signs + ", ягоды " + berries);
                mc.player.closeContainer();
                LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(false);
                LayerListConfigs.HUD_ENABLED = true;
                mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                wait = 1500L;
            }
            case 39 ->
            {
                // Клавиша панели в открытом инвентаре — настоящим путём клавиатуры (KeyboardHandler)
                int key = LayerListConfigs.TOGGLE_HUD.getKeybind().getKeys().get(0);
                var event = new net.minecraft.client.input.KeyEvent(key, 0, 0);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.PRESS, event);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.RELEASE, event);
                boolean hidden = ! LayerListConfigs.HUD_ENABLED;
                boolean stillOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen;
                this.check("Y в инвентаре спрятала панель, инвентарь открыт", hidden && stillOpen,
                        "HUD " + LayerListConfigs.HUD_ENABLED + ", экран " + mc.gui.screen());
                shot(mc, "инвентарь — панель скрыта клавишей");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.PRESS, event);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.RELEASE, event);
                this.check("второе нажатие Y вернуло панель", LayerListConfigs.HUD_ENABLED, "");
                wait = 1500L;
            }
            case 40 ->
            {
                shot(mc, "инвентарь — панель снова видна");
                mc.gui.setScreen(null);
                wait = 500L;
            }
            default ->
            {
                CovOut.log("[support] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private int missingOff;
    private int supportStone;

    /** Второй сундук с полным набором заново; {@code clear} — инвентарь игрока пуст. */
    private void freshChestB(MinecraftServer server, boolean clear)
    {
        server.execute(() -> {
            if (clear)
            {
                command(server, "clear @a");
            }

            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", CHEST_B.getX(), CHEST_B.getY(), CHEST_B.getZ()));
            command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=north]%s",
                    CHEST_B.getX(), CHEST_B.getY(), CHEST_B.getZ(), CHEST_B_ITEMS));
            command(server, "tp @a " + (CHEST_B.getX() + 0.5D) + " " + CHEST_B.getY() + " " + (CHEST_B.getZ() + 2.5D) + " 180 30");
        });
    }

    private static void openChest(Minecraft mc, BlockPos pos)
    {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false));
    }

    /** Сколько камня не хватает по списку материалов панели (0 — строки нет). */
    private int stoneMissing()
    {
        for (var entry : com.aell.layerlist.core.LayerCountManager.getInstance().getPageEntries())
        {
            if (entry.getStack().is(Items.STONE))
            {
                return entry.getCountMissing();
            }
        }

        return 0;
    }
    private java.util.Set<BlockPos> markedOff = java.util.Set.of();

    /** Сколько висящих клеток в рамках «пропущено» у Ctrl+B. */
    private int hangingMarked()
    {
        int count = 0;

        for (int[] c : HANGING)
        {
            count += LayerVerifier.getInstance().getVisible().get(LayerVerifier.Category.MISSING).contains(at(TARGET, c)) ? 1 : 0;
        }

        return count;
    }

    /** Висящие клетки — «без опоры» ({@code hangingUnsupported}), стоящие на полу и потолок — никогда. */
    private void checkCells(Minecraft mc, String when, boolean hangingUnsupported)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        List<String> wrong = new ArrayList<>();

        for (int[][] group : new int[][][]{HANGING, GROUNDED, CEILING})
        {
            boolean expected = group == HANGING && hangingUnsupported;

            for (int[] c : group)
            {
                BlockPos pos = at(TARGET, c);
                var wanted = world.getBlockState(pos);
                boolean unsupported = GhostSupport.unsupported(wanted, mc.level.getBlockState(pos), pos, mc.level, world);

                if (unsupported != expected && ! (group == CEILING && wanted == mc.level.getBlockState(pos)))
                {
                    wrong.add(wanted.getBlock().getName().getString() + " " + pos.toShortString() + " → " + unsupported);
                }
            }
        }

        this.check(when + ": «без опоры» — ровно висящие клетки" + (hangingUnsupported ? "" : " (никто)"),
                wrong.isEmpty(), wrong.toString());
    }

    private void aimAndPlace(Minecraft mc, Vec3 aim)
    {
        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
    }

    /** Щелчок левой кнопкой в точке экрана (координаты GUI) — через MouseHandler, как от GLFW. */
    static void click(Minecraft mc, double guiX, double guiY)
    {
        if (! moveMouse(mc, guiX, guiY))
        {
            return;
        }

        var window = mc.getWindow();
        int left = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
        mc.mouseHandler.onButton(window.handle(), new MouseButtonInfo(left, 0), com.mojang.blaze3d.platform.InputConstants.PRESS);
        mc.mouseHandler.onButton(window.handle(), new MouseButtonInfo(left, 0), com.mojang.blaze3d.platform.InputConstants.RELEASE);
    }

    /** Курсор в точку экрана (координаты GUI). */
    static boolean moveMouse(Minecraft mc, double guiX, double guiY)
    {
        var window = mc.getWindow();

        try
        {
            var x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
            var y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
            x.setAccessible(true);
            y.setAccessible(true);
            x.setDouble(mc.mouseHandler, guiX * window.getScreenWidth() / window.getGuiScaledWidth());
            y.setDouble(mc.mouseHandler, guiY * window.getScreenHeight() / window.getGuiScaledHeight());
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[support] курсор не поставить: " + e);
            return false;
        }

        return true;
    }

    private static int count(Inventory inventory, Item item)
    {
        int count = 0;

        for (int i = 0; i < inventory.getContainerSize(); ++i)
        {
            if (inventory.getItem(i).is(item))
            {
                count += inventory.getItem(i).getCount();
            }
        }

        return count;
    }

    private static BlockPos at(BlockPos origin, int[] c)
    {
        return origin.offset(c[0], c[1], c[2]);
    }

    private static String set(BlockPos origin, int[] c, String block)
    {
        BlockPos pos = at(origin, c);
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private static void shot(Minecraft mc, String name)
    {
        CovOut.log("[support] снимок " + name);
        Screenshot.grab(mc, false);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[support] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[support] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setBooleanValue(false);
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setBooleanValue(false);
        LayerListConfigs.HUD_TRANSFER_AUTO.setBooleanValue(false);
        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.REFRESH_INTERVAL.setIntegerValue(20);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
    }
}
