package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.schematics.materials.Availability;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.gui.materials.StorageFind;
import com.aell.layerlist.storage.ChestPicker;
import com.aell.layerlist.storage.ChosenStorages;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;

/**
 * Режим {@code chosen}: «Только выбранные сундуки», клавиша «Выбрать сундуки» и эндер-сундук в счёт (29.10.2026).
 *
 * <p>Губки (их нет в других стендах) в пяти хранилищах: A — свой сундук (5), B — «сундук друга» (7), C — бочка, которую
 * не открывали (3), D — двойной сундук (11, кликаем по второй половине), E — эндер-сундук (13 + 4 в шалкере). Проверки:
 * эндер-сундук пишется одной записью на игрока с содержимым шалкера; клавиша (настоящим событием SDL) включает режим;
 * настоящий ПКМ мышью по сундуку отмечает его и не открывает, не ставит блок из руки и сам включает «Только выбранные»;
 * двойной сундук отмечается по канонической половине; счёт «В сундуках» — только выбранные плюс эндер-сундук; ПКМ по
 * эндер-сундуку выключает и включает его счёт; зажатая ПКМ переключает один раз; выбор переживает перечитывание файла;
 * вне режима сундук открывается как обычно; сломанный выбранный сундук сам уходит из счёта. Снимки: мир в режиме
 * выбора и плитка в настройках.
 */
@Environment(EnvType.CLIENT)
public final class ChosenChestsTest implements CovJob
{
    private static final String TAG = "[chosen] ";
    private static final String SPONGE = "minecraft:sponge";
    private static final BlockPos A = new BlockPos(420, 100, 2);
    private static final BlockPos B = new BlockPos(418, 100, 2);
    private static final BlockPos C = new BlockPos(422, 100, 2);
    /** Двойной сундук: 424 — «правая» половина (каноническая, меньший x), 425 — «левая». */
    private static final BlockPos D = new BlockPos(424, 100, 2);
    private static final BlockPos D2 = new BlockPos(425, 100, 2);
    private static final BlockPos E = new BlockPos(416, 100, 2);
    /** Одинарный S, к которому потом приставят S2 (меньший x): каноническая половина сменится с S на S2. */
    private static final BlockPos S = new BlockPos(428, 100, 2);
    private static final BlockPos S2 = new BlockPos(427, 100, 2);
    /** Клавиша K: в стенде больше ни на что не назначена. */
    private static final int SCANCODE_K = 14;
    private static final int SCANCODE_MASK = 1 << 30;

    private int stage;
    private long nextAt;
    private boolean failed;
    private long baseline;
    private String scope = "";
    private boolean followsPage = true;
    private boolean easyPlaceWas;
    private boolean rewriteWas;

    @Override
    public String name()
    {
        return "CHOSEN (только выбранные сундуки, эндер-сундук)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        ChestPicker.stop();
        this.easyPlaceWas = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue();
        this.rewriteWas = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue();
        fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);
        LayerListConfigs.PICK_CHESTS.getKeybind().setValueFromString("K");
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();

        server.execute(() ->
        {
            command(server, "forceload add 400 -10 440 20");
            command(server, "fill 410 99 -4 430 99 12 minecraft:stone");
            command(server, "fill 410 100 -4 430 106 12 minecraft:air");
            command(server, "gamemode creative @a");
            command(server, "tp @a 420.5 100 4.5 180 35");
            command(server, chest(A, "chest[facing=south]", 5));
            command(server, chest(B, "chest[facing=south]", 7));
            command(server, chest(C, "barrel[facing=up]", 3));
            command(server, "setblock " + xyz(D) + " minecraft:chest[facing=south,type=right]");
            command(server, chest(D2, "chest[facing=south,type=left]", 11));
            command(server, "setblock " + xyz(E) + " minecraft:ender_chest[facing=south]");
            command(server, "item replace entity @a enderchest.0 with " + SPONGE + " 13");
            command(server, "item replace entity @a enderchest.1 with minecraft:shulker_box[container=[{slot:0,item:{id:\""
                    + SPONGE + "\",count:4}}]]");
            command(server, "item replace entity @a weapon.mainhand with minecraft:cobblestone 64");
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        if (Util.getMillis() < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        this.nextAt = Util.getMillis() + 1200L;
        MinecraftServer server = mc.getSingleplayerServer();
        ChosenStorages chosen = ChosenStorages.getInstance();

        switch (this.stage++)
        {
            case 0 ->
            {
                this.scope = StorageIndex.scope(mc);

                // Хвосты прошлых прогонов: записи этих мест, выбор области и эндер-сундук — с чистого листа
                for (BlockPos pos : new ArrayList<>(chosen.in(this.scope)))
                {
                    chosen.remove(this.scope, pos);
                }

                StorageIndex.getInstance().removeNonCanonical(this.scope, BlockPos.ZERO, List.of(A, B, C, D, D2, E, S, S2));
                StorageIndex.getInstance().removeNonCanonical(StorageIndex.enderScope(mc), new BlockPos(0, -999, 0),
                        List.of(StorageIndex.ENDER_POS));
                LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
                this.baseline = sponges();
                LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);
                check("до открытия эндер-сундука записи нет", Availability.enderRecord(mc) == null, "");
                CovOut.log(TAG + "губок в старых записях области: " + this.baseline);
                click(mc, A);
            }
            case 1 -> { mc.player.closeContainer(); click(mc, B); }
            case 2 -> { mc.player.closeContainer(); click(mc, D2); }
            case 3 -> { mc.player.closeContainer(); click(mc, E); }
            case 4 ->
            {
                check("эндер-сундук открылся", mc.gui.screen() instanceof AbstractContainerScreen<?>, "");
            }
            case 5 ->
            {
                mc.player.closeContainer();
                StorageIndex.Rec ender = Availability.enderRecord(mc);
                check("эндер-сундук записан одной записью на игрока", ender != null, "");
                check("в эндер-сундуке 13 губок и 4 в шалкере",
                        ender != null && Integer.valueOf(17).equals(ender.counts.get(SPONGE)), ender == null ? "" : ender.counts.toString());
                check("эндер-сундук не лёг записью на свой блок", StorageIndex.getInstance().get(this.scope, E) == null, "");
                check("двойной сундук записан по канонической половине", count(D) == 11 && StorageIndex.getInstance().get(this.scope, D2) == null,
                        "D " + count(D));
                check("бочку C не открывали — записи нет", StorageIndex.getInstance().get(this.scope, C) == null, "");
                check("все открытые + эндер: " + (this.baseline + 40), sponges() == this.baseline + 40, "есть " + sponges());
                LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
                check("без эндер-сундука: " + (this.baseline + 23), sponges() == this.baseline + 23, "есть " + sponges());
                LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);
                // Клавиша «Выбрать сундуки» — настоящим событием SDL, отпускание — следующим шагом
                pushKey(SCANCODE_K, true);
                this.nextAt = Util.getMillis() + 150L;
            }
            case 6 ->
            {
                pushKey(SCANCODE_K, false);
                this.nextAt = Util.getMillis() + 500L;
            }
            case 7 ->
            {
                check("клавиша включила режим выбора", ChestPicker.isActive(), "");
                check("«Только выбранные» ещё выключено", ! LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue(), "");
                // Настоящий ПКМ мышью: игрок смотрит на сундук A, в руке булыжник
                rightButton(mc, true);
                this.nextAt = Util.getMillis() + 300L;
            }
            case 8 ->
            {
                rightButton(mc, false);
                this.nextAt = Util.getMillis() + 600L;
            }
            case 9 ->
            {
                CovOut.log(TAG + "прицел: " + mc.hitResult);
                check("ПКМ мышью отметил сундук A", chosen.contains(this.scope, A), chosen.in(this.scope).toString());
                check("сундук не открылся", mc.gui.screen() == null, String.valueOf(mc.gui.screen()));
                check("булыжник из руки не поставлен", mc.level.getBlockState(A.south()).isAir(),
                        mc.level.getBlockState(A.south()).toString());
                check("первая отметка включила «Только выбранные»", LayerListConfigs.COUNT_CHOSEN_CHESTS.getBooleanValue(), "");
                click(mc, D2);
            }
            case 10 ->
            {
                check("двойной сундук отмечен канонической половиной", chosen.contains(this.scope, D) && ! chosen.contains(this.scope, D2),
                        chosen.in(this.scope).toString());
                click(mc, C);
            }
            case 11 ->
            {
                check("неоткрытая бочка C отмечена", chosen.contains(this.scope, C), "");
                check("только выбранные + эндер: 5 + 11 + 0 + 17 = 33", sponges() == 33, "есть " + sponges());
                check("подсказка колонки говорит про выбранные",
                        Availability.chestsHint().contains("3"), Availability.chestsHint());
                command(server, "tp @a 420.5 102.5 9.5 180 22");
                this.nextAt = Util.getMillis() + 2500L;
            }
            case 12 ->
            {
                shot(mc, "chosen-pick-world");
                click(mc, E);
            }
            case 13 ->
            {
                check("ПКМ по эндер-сундуку выключил его счёт", ! LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue(), "");
                check("без эндер-сундука: 16", sponges() == 16, "есть " + sponges());
                click(mc, E);
            }
            case 14 ->
            {
                check("второй ПКМ включил счёт эндер-сундука", LayerListConfigs.COUNT_ENDER_CHEST.getBooleanValue(), "");
                // Зажатая ПКМ: повторы клика в том же нажатии не переключают
                mc.options.keyUse.setDown(true);
                click(mc, B);
                click(mc, B);
                click(mc, B);
                mc.options.keyUse.setDown(false);
                check("зажатая ПКМ переключила B один раз", chosen.contains(this.scope, B), "");
                check("с B: 40", sponges() == 40, "есть " + sponges());
            }
            case 15 ->
            {
                click(mc, B);
                check("новое нажатие сняло B", ! chosen.contains(this.scope, B), "");
                Set<BlockPos> before = Set.copyOf(chosen.in(this.scope));
                chosen.load();
                check("выбор пережил перечитывание файла", before.equals(Set.copyOf(chosen.in(this.scope))),
                        before + " / " + chosen.in(this.scope));
                // Выход из режима — тем же обработчиком, каким malilib зовёт клавишу
                ((fi.dy.masa.malilib.hotkeys.KeybindMulti) LayerListConfigs.PICK_CHESTS.getKeybind()).getCallback()
                        .onKeyAction(fi.dy.masa.malilib.hotkeys.KeyAction.PRESS, LayerListConfigs.PICK_CHESTS.getKeybind());
                check("клавиша выключила режим", ! ChestPicker.isActive(), "");
                command(server, "tp @a 420.5 100 4.5 180 35");
            }
            case 16 -> click(mc, B);
            case 17 ->
            {
                check("вне режима сундук открывается", mc.gui.screen() instanceof AbstractContainerScreen<?>, String.valueOf(mc.gui.screen()));
                mc.player.closeContainer();
                check("невыбранный B в счёт не вошёл: 33", sponges() == 33, "есть " + sponges());
                checkSearchHighlight(mc, chosen);
                // Снимок подсветки: губки ищутся, «Свой склад» включён — светятся A, D и эндер-сундук E, B нет. Схемы нет,
                // страница HUD пуста — «Следовать за страницей» сняло бы отметки на следующем пересчёте
                this.followsPage = LayerListConfigs.SEARCH_FOLLOWS_PAGE.getBooleanValue();
                LayerListConfigs.SEARCH_FOLLOWS_PAGE.setBooleanValue(false);
                StorageSearch.getInstance().togglePage(
                        List.of(new MaterialListEntry(new ItemStack(Items.SPONGE), 64, 64, 0, 0)));
                command(server, "tp @a 421.5 101.5 9.5 180 25");
                this.nextAt = Util.getMillis() + 1500L;
            }
            case 18 ->
            {
                check("подсветка поиска перед снимком: A, D и эндер-сундук E",
                        StorageSearch.getInstance().getMatches().keySet().equals(Set.of(A, D, E)),
                        StorageSearch.getInstance().getMatches().keySet().toString());
                shot(mc, "chosen-search");
                command(server, "setblock " + xyz(A) + " minecraft:air");
                // Блока эндер-сундука рядом больше нет — «Найти в сундуках» должно сказать «лежит в эндер-сундуке»
                command(server, "setblock " + xyz(E) + " minecraft:air");
                this.nextAt = Util.getMillis() + 3500L;
            }
            case 19 ->
            {
                StorageSearch.getInstance().clear();
                StorageFind.Outcome farEnder = StorageFind.toggle(new MaterialRow(new ItemStack(Items.SHULKER_BOX),
                        "minecraft:shulker_box", "Shulker Box", 1, 1, 0, 0, 1, false)).outcome();
                check("эндер-сундука рядом нет — IN_ENDER, а не «не встречался»", farEnder == StorageFind.Outcome.IN_ENDER,
                        String.valueOf(farEnder));
                StorageSearch.getInstance().clear();
                LayerListConfigs.SEARCH_FOLLOWS_PAGE.setBooleanValue(this.followsPage);
                check("сломанный сундук A ушёл из выбранных", ! chosen.contains(this.scope, A), chosen.in(this.scope).toString());
                check("без A: 28", sponges() == 28, "есть " + sponges());
                // Easy Place Litematica включён, старый путь (по умолчанию у Litematica): настоящий ПКМ по B в режиме выбора
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
                pressPickKey();
                command(server, "tp @a 418.5 100 4.5 180 35");
                this.nextAt = Util.getMillis() + 1500L;
            }
            case 20 -> { rightButton(mc, true); this.nextAt = Util.getMillis() + 300L; }
            case 21 ->
            {
                rightButton(mc, false);
                this.nextAt = Util.getMillis() + 600L;
            }
            case 22 ->
            {
                check("Easy Place (старый путь): ПКМ мышью отметил B", chosen.contains(this.scope, B), chosen.in(this.scope).toString());
                check("Easy Place (старый путь): сундук не открылся", mc.gui.screen() == null, String.valueOf(mc.gui.screen()));
                check("Easy Place (старый путь): булыжник не поставлен", mc.level.getBlockState(B.south()).isAir(), "");
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(true);
                rightButton(mc, true);
                this.nextAt = Util.getMillis() + 300L;
            }
            case 23 ->
            {
                rightButton(mc, false);
                this.nextAt = Util.getMillis() + 600L;
            }
            case 24 ->
            {
                check("Easy Place (новый путь): ПКМ мышью снял B", ! chosen.contains(this.scope, B), chosen.in(this.scope).toString());
                check("Easy Place (новый путь): сундук не открылся", mc.gui.screen() == null, String.valueOf(mc.gui.screen()));
                check("Easy Place (новый путь): булыжник не поставлен", mc.level.getBlockState(B.south()).isAir(), "");
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
                // Одинарный сундук S выбран, потом к нему приставили второй с меньшим x — канон уезжает на S2
                command(server, chest(S, "chest[facing=south]", 9));
                // Рядом с S: дальше досягаемости сервер сундук не откроет
                command(server, "tp @a 427.5 100 4.5 180 35");
                this.nextAt = Util.getMillis() + 1200L;
            }
            case 25 -> click(mc, S);
            case 26 ->
            {
                check("одинарный S выбран", chosen.contains(this.scope, S), "");
                pressPickKey();
                click(mc, S);
            }
            case 27 ->
            {
                check("S открыт вне режима", mc.gui.screen() instanceof AbstractContainerScreen<?>, String.valueOf(mc.gui.screen()));
                mc.player.closeContainer();
                check("с S: 28 + 9 = 37", sponges() == 37, "есть " + sponges());
                command(server, chest(S2, "chest[facing=south,type=right]", 2));
                command(server, chest(S, "chest[facing=south,type=left]", 9));
                this.nextAt = Util.getMillis() + 1000L;
            }
            case 28 -> click(mc, S);
            case 29 ->
            {
                mc.player.closeContainer();
                check("двойной: запись на новой канонической половине", count(S2) == 11 && StorageIndex.getInstance().get(this.scope, S) == null,
                        "S2 " + count(S2));
                check("отметка переехала на S2", chosen.contains(this.scope, S2) && ! chosen.contains(this.scope, S), chosen.in(this.scope).toString());
                check("двойной в счёте: 28 + 11 = 39", sponges() == 39, "есть " + sponges());
                // Клавишу сняли, пока режим включён: режим сам выходит, иначе выйти нечем
                pressPickKey();
                check("режим снова включён", ChestPicker.isActive(), "");
                LayerListConfigs.PICK_CHESTS.getKeybind().setValueFromString("");
            }
            case 30 ->
            {
                check("клавишу сняли — режим выключился сам", ! ChestPicker.isActive(), "");
                LayerListConfigs.PICK_CHESTS.getKeybind().setValueFromString("K");
                fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();
                var screen = com.aell.layerlist.config.GuiConfigs.create(null, null);
                mc.gui.setScreen(screen);
                SupportTest.moveMouse(mc, 4.0D, mc.getWindow().getGuiScaledHeight() - 4.0D);
                this.nextAt = Util.getMillis() + 2000L;
            }
            case 31 -> shot(mc, "chosen-settings");
            default ->
            {
                mc.gui.setScreen(null);
                CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        return false;
    }

    /**
     * Подсветка поиска со «Своим складом» (жалоба 29.10.2026: открытые раньше чужие сундуки светились и с включённой
     * опцией). Выбраны A, D и неоткрытая C, губки лежат в A, B и D: отмечены должны быть A и D, без опции — и B. И
     * «Найти в сундуках» из таблицы: губки только в невыбранных — так и сказать, а не «не рядом».
     */
    private void checkSearchHighlight(Minecraft mc, ChosenStorages chosen)
    {
        StorageSearch search = StorageSearch.getInstance();
        List<MaterialListEntry> sponge = List.of(new MaterialListEntry(new ItemStack(Items.SPONGE), 64, 64, 0, 0));

        search.clear();
        search.togglePage(sponge);
        Set<BlockPos> marked = Set.copyOf(search.getMatches().keySet());
        check("поиск со «Своим складом» светит A и D, а не B", marked.contains(A) && marked.contains(D) && ! marked.contains(B),
                marked.toString());
        // Эндер-сундук: губки есть в снимке — светится блок E (жалоба 29.10.2026: «там же лежат нужные мне еловые люки»)
        check("эндер-сундук E светится: губки есть в его снимке", marked.contains(E), marked.toString());

        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
        search.clear();
        search.togglePage(sponge);
        marked = Set.copyOf(search.getMatches().keySet());
        check("эндер-сундук не в счёте — E не светится", ! marked.contains(E) && marked.contains(A), marked.toString());
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);

        // Шалкер лежит только в эндер-сундуке: «Найти в сундуках» светит один E
        search.clear();
        StorageFind.Result shulker = StorageFind.toggle(new MaterialRow(new ItemStack(Items.SHULKER_BOX), "minecraft:shulker_box",
                "Shulker Box", 1, 1, 0, 0, 1, false));
        marked = Set.copyOf(search.getMatches().keySet());
        check("«Найти в сундуках»: шалкер только в эндер-сундуке — светится E", shulker.outcome() == StorageFind.Outcome.MARKED
                && marked.equals(Set.of(E)), shulker.outcome() + " " + marked);

        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        search.clear();
        search.togglePage(sponge);
        marked = Set.copyOf(search.getMatches().keySet());
        check("без «Своего склада» поиск светит и B", marked.contains(A) && marked.contains(B) && marked.contains(D),
                marked.toString());

        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(true);
        search.clear();
        search.togglePage(sponge);
        marked = Set.copyOf(search.getMatches().keySet());
        check("опцию включили обратно — B погас", ! marked.contains(B) && marked.contains(A), marked.toString());

        // Пока A и D сняты, губки есть только в невыбранных (эндер-сундук на это время не в счёте — в нём тоже губки)
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(false);
        chosen.toggle(this.scope, A);
        chosen.toggle(this.scope, D);
        search.clear();
        MaterialRow row = new MaterialRow(new ItemStack(Items.SPONGE), SPONGE, "Sponge", 64, 64, 0, 0, 64, false);
        StorageFind.Outcome outcome = StorageFind.toggle(row).outcome();
        check("«Найти в сундуках»: губки только в невыбранных — NOT_CHOSEN", outcome == StorageFind.Outcome.NOT_CHOSEN,
                String.valueOf(outcome));
        check("и ничего не подсвечено", search.getMatches().isEmpty(), search.getMatches().keySet().toString());
        search.clear();
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);
        chosen.toggle(this.scope, A);
        chosen.toggle(this.scope, D);
        check("A и D снова выбраны", chosen.contains(this.scope, A) && chosen.contains(this.scope, D),
                chosen.in(this.scope).toString());
    }

    /** Клавиша выбора — тем же обработчиком, каким malilib зовёт её (повторные события SDL под xvfb не доходят). */
    private static void pressPickKey()
    {
        ((fi.dy.masa.malilib.hotkeys.KeybindMulti) LayerListConfigs.PICK_CHESTS.getKeybind()).getCallback()
                .onKeyAction(fi.dy.masa.malilib.hotkeys.KeyAction.PRESS, LayerListConfigs.PICK_CHESTS.getKeybind());
    }

    private long sponges()
    {
        return Availability.capture().inChests(new ItemStack(Items.SPONGE));
    }

    private int count(BlockPos pos)
    {
        StorageIndex.Rec rec = StorageIndex.getInstance().get(this.scope, pos);
        return rec == null ? -1 : rec.counts.getOrDefault(SPONGE, 0);
    }

    private static void rightButton(Minecraft mc, boolean down)
    {
        mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0),
                down ? InputConstants.PRESS : InputConstants.RELEASE);
    }

    private static void pushKey(int scancode, boolean down)
    {
        long window = Minecraft.getInstance().getWindow().handle();
        int windowId = SDLVideo.SDL_GetWindowID(window);
        SDL_Event event = SDL_Event.calloc();

        try
        {
            event.key(key -> key.type(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP)
                    .windowID(windowId).scancode(scancode).key(scancode | SCANCODE_MASK).down(down).repeat(false));
            SDLEvents.SDL_PushEvent(event);
        }
        finally
        {
            event.free();
        }
    }

    private static String xyz(BlockPos pos)
    {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String chest(BlockPos pos, String block, int sponges)
    {
        return "setblock " + xyz(pos) + " minecraft:" + block + "{Items:[{Slot:0b,id:\"" + SPONGE + "\",count:" + sponges + "}]}";
    }

    private static void click(Minecraft mc, BlockPos pos)
    {
        Vec3 face = Vec3.atCenterOf(pos).add(0, 0, 0.5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.SOUTH, pos, false));
    }

    private static void command(MinecraftServer server, String command)
    {
        server.execute(() ->
        {
            try
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
            }
            catch (RuntimeException e)
            {
                CovOut.log(TAG + "команда не прошла (" + command + "): " + e);
            }
        });
    }

    private static void shot(Minecraft mc, String name)
    {
        java.nio.file.Path path = FabricLoader.getInstance().getGameDir().resolve("screenshots").resolve(name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image ->
        {
            try (image)
            {
                java.nio.file.Files.createDirectories(path.getParent());
                image.writeToFile(path);
                CovOut.log(TAG + "снимок " + path);
            }
            catch (java.io.IOException | RuntimeException e)
            {
                CovOut.log(TAG + "снимок не записан: " + e);
            }
        });
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
    }

    @Override
    public void close()
    {
        fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(this.easyPlaceWas);
        fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(this.rewriteWas);
        ChestPicker.stop();
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setBooleanValue(false);
        LayerListConfigs.COUNT_ENDER_CHEST.setBooleanValue(true);
        LayerListConfigs.PICK_CHESTS.getKeybind().setValueFromString("");
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().updateUsedKeys();
    }
}
