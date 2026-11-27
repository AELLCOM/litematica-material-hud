package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.hud.BannerCard;
import com.aell.layerlist.hud.BannerSources;
import com.aell.layerlist.mixin.IMixinAbstractContainerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Режим {@code bannercard} (24.11.2026): «Рецепт флага при наведении». Настоящие движения мыши и щелчки в окне
 * инвентаря: без Shift карточки нет (подсказка обычная), с зажатым Shift она открывается на узорном флаге (Shift
 * стенду подставляет {@code BannerCard.TestHooks}: игра читает клавишу из состояния клавиатуры SDL), щелчок с Shift
 * мимо карточки прячет её, пока Shift не отпустят. Карточка листается колёсиком и стрелкой, остаётся, когда курсор переходит
 * на неё, щелчок по узору открывает «Как получить» (рецепт верстака и место, где искать), «Назад» возвращает;
 * у простого флага и при выключенной настройке карточки нет. Отдельно — сверка таблицы {@link BannerSources} с игрой:
 * у каждого предмета-узора из реестра есть запись, а рецепты совпадают с рецептами сервера.
 */
@Environment(EnvType.CLIENT)
public final class BannerCardTest implements CovJob
{
    private static final String THREE = "[{pattern:\"minecraft:creeper\",color:\"red\"},{pattern:\"minecraft:border\",color:\"black\"},"
            + "{pattern:\"minecraft:flow\",color:\"blue\"}]";
    private static final String EIGHT = "[{pattern:\"minecraft:globe\",color:\"light_blue\"},{pattern:\"minecraft:piglin\",color:\"orange\"},"
            + "{pattern:\"minecraft:stripe_top\",color:\"yellow\"},{pattern:\"minecraft:bricks\",color:\"gray\"},"
            + "{pattern:\"minecraft:skull\",color:\"white\"},{pattern:\"minecraft:mojang\",color:\"red\"},"
            + "{pattern:\"minecraft:flower\",color:\"pink\"},{pattern:\"minecraft:curly_border\",color:\"lime\"}]";
    private static final long SHOT_HOLD_MS = 3500L;

    private net.minecraft.core.BlockPos chest;
    private int stage;
    private long nextAt;
    private boolean failed;
    private boolean cardWas;
    private Runnable after;
    private long afterWait;

    @Override
    public String name()
    {
        return "bannercard (рецепт флага при наведении)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        this.cardWas = LayerListConfigs.BANNER_CARD.getBooleanValue();
        LayerListConfigs.BANNER_CARD.setBooleanValue(true);
        this.checkSources(server);

        // Сундук с узорным флагом — окно без книги рецептов, оно рисуется другим путём, чем инвентарь
        net.minecraft.core.BlockPos feet = mc.player.blockPosition();
        this.chest = feet.offset(0, 0, -2);
        net.minecraft.core.BlockPos chestAt = this.chest;

        server.execute(() ->
        {
            command(server, String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", feet.getX() - 1, feet.getY(),
                    feet.getZ() - 3, feet.getX() + 1, feet.getY() + 2, feet.getZ() + 1));
            command(server, String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:stone", feet.getX(), feet.getY() - 1,
                    feet.getZ()));
            command(server, String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=south]{Items:["
                    + "{Slot:13b,id:\"minecraft:white_banner\",count:1,components:{\"minecraft:banner_patterns\":%s}},"
                    // Стопки под карточкой: их число не должно проступать сквозь неё
                    + "{Slot:5b,id:\"minecraft:stone\",count:64},{Slot:14b,id:\"minecraft:stone\",count:64},"
                    + "{Slot:15b,id:\"minecraft:oak_planks\",count:37},{Slot:23b,id:\"minecraft:glass\",count:12},"
                    + "{Slot:24b,id:\"minecraft:torch\",count:48}]}",
                    chestAt.getX(), chestAt.getY(), chestAt.getZ(), THREE));
            command(server, String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 180 30", feet.getX() + 0.5D, feet.getY(),
                    feet.getZ() + 0.5D));
            // В творческом окно инвентаря подменяется творческим — стенд идёт в выживании
            command(server, "gamemode survival @a");
            command(server, "clear @a");
            command(server, "item replace entity @a hotbar.0 with minecraft:white_banner[banner_patterns=" + THREE + "] 1");
            command(server, "item replace entity @a hotbar.1 with minecraft:stone 3");
            command(server, "item replace entity @a hotbar.2 with minecraft:red_banner 1");
            // inventory.0 — первая ячейка рюкзака, девятая по счёту инвентаря
            command(server, "item replace entity @a inventory.0 with minecraft:black_banner[banner_patterns=" + EIGHT + "] 1");
        });

        this.nextAt = Util.getMillis() + 3000L;
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

        if (mc.player == null || now < this.nextAt)
        {
            return false;
        }

        long wait = 900L;

        if (this.after != null)
        {
            Runnable action = this.after;
            this.after = null;
            action.run();
            this.nextAt = now + this.afterWait;
            return false;
        }

        switch (this.stage)
        {
            case 0 ->
            {
                mc.setScreen(new InventoryScreen(mc.player));
                SupportTest.moveMouse(mc, 4.0D, 4.0D);
            }
            case 1 ->
            {
                this.check("окно инвентаря открыто, карточки нет", mc.screen instanceof InventoryScreen
                        && BannerCard.bounds() == null, BannerCard.state());
                this.hover(mc, 0);
            }
            case 2 ->
            {
                // Слово владельца 24.11: в инвентаре карточка только с зажатым Shift
                this.check("курсор на узорном флаге без Shift — карточки нет, подсказка обычная", BannerCard.bounds() == null
                        && ! BannerCard.hidesTooltip(mc.screen), BannerCard.state());
                this.shot(mc, "0 без Shift — обычная подсказка");
                this.then(() -> BannerCard.TestHooks.shift(true), 900L);
            }
            case 3 ->
            {
                // Флаг с тремя узорами: основа, три красителя, два предмета-узора, станок = 7 строк → 2 страницы + 3 шага
                this.check("курсор на узорном флаге — карточка открыта на «Что нужно»", "страница 1/5".equals(BannerCard.state()),
                        BannerCard.state());
                this.check("подсказку предмета карточка заменяет", BannerCard.hidesTooltip(mc.screen), "");
                int[] box = BannerCard.bounds();
                double[] slot = this.slotCenter(mc, 0);
                this.check("карточка вплотную к ячейке и целиком на экране", box != null && slot != null
                        && (box[0] == (int) slot[0] + 9 || box[0] + box[2] == (int) slot[0] - 9) && box[0] >= 2 && box[1] >= 2
                        && box[0] + box[2] <= mc.getWindow().getGuiScaledWidth() - 2
                        && box[1] + box[3] <= mc.getWindow().getGuiScaledHeight() - 2,
                        box == null ? "нет" : box[0] + "," + box[1] + " " + box[2] + "×" + box[3]);
                this.shot(mc, "1 что нужно");
                this.then(() -> this.scroll(mc, 0, -1.0D), 900L);
            }
            case 4 ->
            {
                this.check("колёсико вниз — вторая страница «Что нужно»", "страница 2/5".equals(BannerCard.state()),
                        BannerCard.state());
                this.scroll(mc, 0, -1.0D);
            }
            case 5 ->
            {
                this.check("ещё колёсико — первый шаг", "страница 3/5".equals(BannerCard.state()), BannerCard.state());
                this.shot(mc, "2 шаг 1 — узор с предметом");
                // Курсор с ячейки на карточку, на сам узор: карточка не должна уйти
                this.then(() ->
                {
                    double[] spot = BannerCard.firstInfoSpot(0);

                    if (spot != null)
                    {
                        SupportTest.moveMouse(mc, spot[0], spot[1]);
                    }
                }, 900L);
            }
            case 6 ->
            {
                this.check("курсор перешёл на карточку — она осталась на той же странице", "страница 3/5".equals(BannerCard.state()),
                        BannerCard.state());
                double[] spot = BannerCard.firstInfoSpot(0);
                this.check("узор на странице шага щёлкается", spot != null, "");

                if (spot != null)
                {
                    SupportTest.click(mc, spot[0], spot[1]);
                }
            }
            case 7 ->
            {
                this.check("щелчок по узору — «Как получить» с рецептом верстака",
                        BannerCard.state().contains("как получить: CRAFT"), BannerCard.state());
                this.check("щелчок достался карточке: предмет на курсор не взят",
                        mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu().getCarried().isEmpty(), "");
                this.shot(mc, "3 как получить — рецепт");
                this.then(() ->
                {
                    double[] back = BannerCard.firstInfoSpot(0);

                    if (back != null)
                    {
                        SupportTest.click(mc, back[0], back[1]);
                    }
                }, 900L);
            }
            case 8 ->
            {
                this.check("«Назад» возвращает на страницу шага", "страница 3/5".equals(BannerCard.state()), BannerCard.state());
                // Клавиша → дважды: шаг 3 — «Поток», его только находят
                this.key(mc, InputConstants.KEY_RIGHT);
                this.key(mc, InputConstants.KEY_RIGHT);
            }
            case 9 ->
            {
                this.check("стрелка → листает до последнего шага", "страница 5/5".equals(BannerCard.state()), BannerCard.state());
                double[] spot = BannerCard.firstInfoSpot(0);

                if (spot != null)
                {
                    SupportTest.click(mc, spot[0], spot[1]);
                }
            }
            case 10 ->
            {
                this.check("узор «Поток» — «Как получить» с местом, где искать", BannerCard.state().contains("как получить: FOUND"),
                        BannerCard.state());
                this.shot(mc, "4 как получить — где искать");
                // Курсор прочь: карточка закрывается сама
                this.then(() -> SupportTest.moveMouse(mc, 4.0D, 4.0D), 900L);
            }
            case 11 ->
            {
                this.check("курсор ушёл с карточки и с флага — карточка закрыта", BannerCard.bounds() == null, BannerCard.state());
                this.hover(mc, 9);
            }
            case 12 ->
            {
                // Восемь узоров: основа, восемь красителей, семь предметов-узоров, станок = 17 строк → 4 страницы + 8 шагов
                this.check("флаг с восемью узорами — 12 страниц", "страница 1/12".equals(BannerCard.state()), BannerCard.state());
                this.shot(mc, "5 флаг с восемью узорами");
                this.then(() ->
                {
                    for (int i = 0; i < 11; ++i)
                    {
                        this.scroll(mc, 9, -1.0D);
                    }
                }, 900L);
            }
            case 13 ->
            {
                this.check("долистали до восьмого шага", "страница 12/12".equals(BannerCard.state()), BannerCard.state());
                this.shot(mc, "6 шаг сверх шести узоров станка");
                this.then(() ->
                {
                    for (int i = 0; i < 9; ++i)
                    {
                        this.scroll(mc, 9, 1.0D);
                    }
                }, 900L);
            }
            case 14 ->
            {
                this.check("колёсико вверх листает назад", "страница 3/12".equals(BannerCard.state()), BannerCard.state());
                double[] row = BannerCard.firstInfoSpot(0);

                if (row != null)
                {
                    SupportTest.moveMouse(mc, row[0], row[1]);
                }
            }
            case 15 ->
            {
                double[] row = BannerCard.firstInfoSpot(0);

                if (row != null)
                {
                    SupportTest.click(mc, row[0], row[1]);
                }
            }
            case 16 ->
            {
                this.check("строка предмета-узора в «Что нужно» открывает «Как получить»",
                        BannerCard.state().contains("как получить:"), BannerCard.state());
                this.shot(mc, "7 как получить из списка");
                this.then(() -> SupportTest.moveMouse(mc, 4.0D, 4.0D), 900L);
            }
            case 17 -> this.hover(mc, 2);
            case 18 ->
            {
                this.check("простой флаг без узоров — карточки нет, подсказка обычная", BannerCard.bounds() == null
                        && ! BannerCard.hidesTooltip(mc.screen), BannerCard.state());
                this.shot(mc, "8 простой флаг — обычная подсказка");
                this.then(() ->
                {
                    LayerListConfigs.BANNER_CARD.setBooleanValue(false);
                    SupportTest.moveMouse(mc, 4.0D, 4.0D);
                }, 600L);
            }
            case 19 -> this.hover(mc, 0);
            case 20 ->
            {
                this.check("настройка выключена — карточки нет и у узорного флага", BannerCard.bounds() == null, BannerCard.state());
                LayerListConfigs.BANNER_CARD.setBooleanValue(true);
                // Сундук: закрываем инвентарь и открываем его настоящим нажатием на блок
                mc.setScreen(null);
                this.then(() -> mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(this.chest).add(0, 0, 0.5),
                                net.minecraft.core.Direction.SOUTH, this.chest, false)), 2500L);
            }
            case 21 ->
            {
                this.check("окно сундука открыто", mc.screen instanceof AbstractContainerScreen<?>
                        && ! (mc.screen instanceof InventoryScreen), String.valueOf(mc.screen));
                double[] at = this.chestSlotCenter(mc, 13);
                this.check("ячейка сундука с флагом найдена", at != null, "");

                if (at != null)
                {
                    SupportTest.moveMouse(mc, at[0], at[1]);
                }
            }
            case 22 ->
            {
                this.check("в сундуке карточка открывается так же", "страница 1/5".equals(BannerCard.state()), BannerCard.state());
                this.check("в сундуке подсказку предмета карточка заменяет", BannerCard.hidesTooltip(mc.screen), "");
                double[] at = this.chestSlotCenter(mc, 13);

                if (at != null && mc.screen != null)
                {
                    mc.screen.mouseScrolled(at[0], at[1], 0.0D, -1.0D);
                    mc.screen.mouseScrolled(at[0], at[1], 0.0D, -1.0D);
                    mc.screen.mouseScrolled(at[0], at[1], 0.0D, -1.0D);
                }
            }
            case 23 ->
            {
                this.check("в сундуке колёсико листает карточку", "страница 4/5".equals(BannerCard.state()), BannerCard.state());
                this.shot(mc, "9 сундук — шаг 2");
                // Shift и щелчок мимо карточки (пустая ячейка сундука): игрок перекладывает предметы — карточка уходит
                this.then(() ->
                {
                    double[] empty = this.chestSlotCenter(mc, 0);

                    if (empty != null)
                    {
                        SupportTest.click(mc, empty[0], empty[1]);
                    }
                }, 900L);
            }
            case 24 ->
            {
                this.check("Shift и щелчок мимо карточки — карточка спрятана", BannerCard.bounds() == null
                        && BannerCard.TestHooks.quiet(), BannerCard.state());
                double[] at = this.chestSlotCenter(mc, 13);

                if (at != null)
                {
                    SupportTest.moveMouse(mc, at[0], at[1]);
                }
            }
            case 25 ->
            {
                this.check("пока Shift не отпущен, карточка не возвращается и у флага", BannerCard.bounds() == null
                        && ! BannerCard.hidesTooltip(mc.screen), BannerCard.state());
                BannerCard.TestHooks.shift(false);
            }
            case 26 ->
            {
                this.check("Shift отпущен — запрет снят, карточки по-прежнему нет", BannerCard.bounds() == null
                        && ! BannerCard.TestHooks.quiet(), BannerCard.state());
                BannerCard.TestHooks.shift(true);
            }
            case 27 ->
            {
                this.check("Shift зажат снова — карточка открылась", "страница 1/5".equals(BannerCard.state()), BannerCard.state());
                BannerCard.TestHooks.shift(false);
            }
            case 28 -> this.check("Shift отпущен — карточка закрыта сразу", BannerCard.bounds() == null
                    && ! BannerCard.hidesTooltip(mc.screen), BannerCard.state());
            default ->
            {
                mc.setScreen(null);
                CovOut.log("[bannercard] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + (this.after != null ? SHOT_HOLD_MS : wait);
        ++this.stage;
        return false;
    }

    /** Таблица «откуда узор» против игры: полнота по реестру и рецепты против рецептов сервера. */
    private void checkSources(MinecraftServer server)
    {
        List<Item> patternItems = new ArrayList<>();

        for (Item item : BuiltInRegistries.ITEM)
        {
            if (new ItemStack(item).get(DataComponents.PROVIDES_BANNER_PATTERNS) != null)
            {
                patternItems.add(item);
            }
        }

        List<String> unknown = new ArrayList<>();

        for (Item item : patternItems)
        {
            if (! BannerSources.known(item))
            {
                unknown.add(BuiltInRegistries.ITEM.getKey(item).getPath());
            }
        }

        this.check("у каждого предмета-узора реестра есть запись (предметов " + patternItems.size() + ")",
                ! patternItems.isEmpty() && unknown.isEmpty(), "нет записи: " + unknown);

        Map<Item, Set<Item>> recipes = new HashMap<>();
        AtomicBoolean ready = new AtomicBoolean();

        server.execute(() ->
        {
            try
            {
                var context = SlotDisplayContext.fromLevel(server.overworld());

                for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes())
                {
                    for (RecipeDisplay display : holder.value().display())
                    {
                        if (! (display instanceof ShapelessCraftingRecipeDisplay shapeless))
                        {
                            continue;
                        }

                        for (ItemStack result : display.result().resolveForStacks(context))
                        {
                            if (! patternItems.contains(result.getItem()))
                            {
                                continue;
                            }

                            Set<Item> inputs = recipes.computeIfAbsent(result.getItem(), key -> new HashSet<>());

                            for (SlotDisplay ingredient : shapeless.ingredients())
                            {
                                for (ItemStack stack : ingredient.resolveForStacks(context))
                                {
                                    inputs.add(stack.getItem());
                                }
                            }
                        }
                    }
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                CovOut.log("[bannercard] рецепты сервера не собрались: " + e);
            }
            finally
            {
                ready.set(true);
            }
        });

        for (int i = 0; i < 200 && ! ready.get(); ++i)
        {
            try
            {
                Thread.sleep(50L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        List<String> wrong = new ArrayList<>();

        for (Item item : patternItems)
        {
            String second = BannerSources.craftIngredient(item);
            Set<Item> real = recipes.get(item);
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();

            if (second == null)
            {
                if (real != null)
                {
                    wrong.add(path + ": в игре есть рецепт, в таблице — «только найти»");
                }

                continue;
            }

            Item other = BannerSources.stack(second, 1).getItem();

            if (real == null || ! real.equals(Set.of(Items.PAPER, other)))
            {
                wrong.add(path + ": в таблице бумага + " + second + ", в игре " + real);
            }
        }

        this.check("рецепты предметов-узоров совпадают с рецептами сервера (с рецептом " + recipes.size() + ")", wrong.isEmpty(),
                wrong.toString());
    }

    /** Середина ячейки инвентаря игрока в координатах GUI. */
    private double[] slotCenter(Minecraft mc, int inventorySlot)
    {
        if (! (mc.screen instanceof AbstractContainerScreen<?> screen))
        {
            return null;
        }

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) screen;

        for (Slot slot : screen.getMenu().slots)
        {
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == inventorySlot)
            {
                return new double[]{geometry.layerlist_getLeftPos() + slot.x + 8.0D, geometry.layerlist_getTopPos() + slot.y + 8.0D};
            }
        }

        return null;
    }

    /** Середина ячейки открытого сундука (не инвентаря игрока) в координатах GUI. */
    private double[] chestSlotCenter(Minecraft mc, int containerSlot)
    {
        if (! (mc.screen instanceof AbstractContainerScreen<?> screen))
        {
            return null;
        }

        IMixinAbstractContainerScreen geometry = (IMixinAbstractContainerScreen) screen;

        for (Slot slot : screen.getMenu().slots)
        {
            if (slot.container != mc.player.getInventory() && slot.getContainerSlot() == containerSlot)
            {
                return new double[]{geometry.layerlist_getLeftPos() + slot.x + 8.0D, geometry.layerlist_getTopPos() + slot.y + 8.0D};
            }
        }

        return null;
    }

    private void hover(Minecraft mc, int inventorySlot)
    {
        double[] at = this.slotCenter(mc, inventorySlot);
        this.check("ячейка " + inventorySlot + " найдена на экране", at != null, "");

        if (at != null)
        {
            SupportTest.moveMouse(mc, at[0], at[1]);
        }
    }

    private void scroll(Minecraft mc, int inventorySlot, double amount)
    {
        double[] at = this.slotCenter(mc, inventorySlot);

        if (at != null && mc.screen != null)
        {
            mc.screen.mouseScrolled(at[0], at[1], 0.0D, amount);
        }
    }

    private void key(Minecraft mc, int key)
    {
        if (mc.screen != null)
        {
            mc.screen.keyPressed(new KeyEvent(key, 0, 0));
        }
    }

    private void then(Runnable action, long wait)
    {
        this.after = action;
        this.afterWait = wait;
    }

    private void shot(Minecraft mc, String name)
    {
        CovOut.log("[bannercard] снимок " + name);
        Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[bannercard] " + (ok ? "OK   " : "FAIL ") + what + (ok || detail.isEmpty() ? "" : " — " + detail));
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
            CovOut.log("[bannercard] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        BannerCard.TestHooks.shift(null);
        LayerListConfigs.BANNER_CARD.setBooleanValue(this.cardWas);
    }
}
