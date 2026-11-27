package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.party.Parties;
import com.aell.layerlist.party.Party;
import com.aell.layerlist.party.PartyChat;
import com.aell.layerlist.party.PartyCode;
import com.aell.layerlist.schematics.export.ExportRequest;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.export.OnlinePlayers;
import com.aell.layerlist.schematics.gui.export.PlayerPicker;
import com.aell.layerlist.schematics.gui.materials.ExportFormat;
import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.party.PartyWindow;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.ui.kit.Button;
import com.aell.layerlist.ui.kit.Layers;
import com.aell.layerlist.ui.kit.Modal;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.util.LayerMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Режим {@code party} (24.11.2026): Party — делёж списка схемы между игроками и приглашение через чат.
 * <ol>
 *   <li>Кнопка Party во вкладке «Материалы» → окно: состав (свой ник, «+ игроки онлайн»), доли, «Создать Party».</li>
 *   <li>Таблица показывает долю; строки долей в окне переключают, чью (своя, чужая, весь список).</li>
 *   <li>«Поделиться в чат»: в чате вместо кода — приглашение словами.</li>
 *   <li>Список HUD — тоже долей.</li>
 *   <li>Чужое приглашение: ссылка «Принять приглашение» фирменного цвета, настоящий клик по ней — игрок в Party
 *   под своим номером, таблица и HUD показывают его долю.</li>
 *   <li>Приглашение без своего ника и от более новой версии — без ссылки; приглашение с другим списком схемы —
 *   привязка вручную и предупреждение.</li>
 * </ol>
 * Клики по кнопкам, чипам, строкам долей и ссылке чата — настоящие ({@link SupportTest#click}).
 */
@Environment(EnvType.CLIENT)
public final class PartyTest implements CovJob
{
    private static final String TAG = "[party] ";
    private static final BlockPos REF = new BlockPos(1900, 100, 0);
    private static final BlockPos PLACE = new BlockPos(1900, 100, 24);
    private static final String STONE = "minecraft:stone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String GLASS = "minecraft:glass";
    private static final String GLOW = "minecraft:glowstone";
    private static final String BEACON = "minecraft:beacon";
    private static final String NAME = "Замок";
    private static final String FRIEND = "Tester_42";
    private static final String HOST = "Host_Player";
    private static final String KEY = "layerlist.party.";

    private int stage;
    private long nextAt;
    private boolean failed;
    /** Приглашение, как оно ушло в чат (24.11.2026: кнопка «Скопировать приглашение» и история чата). */
    private String sentInvite;
    private int arrowStep;
    private boolean stockWas;
    private boolean cutoffWas;
    private @Nullable SchematicPlacement placement;
    private @Nullable LitematicaSchematic schematic;
    private String fingerprint = "";
    private int chatBefore;

    @Override
    public String name()
    {
        return "party (делёж списка между игроками и приглашение через чат)";
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
        Parties.TestHooks.clear();
        this.stockWas = LayerListConfigs.USE_STOCK.getBooleanValue();
        this.cutoffWas = LayerListConfigs.MATERIAL_CUTOFF.getBooleanValue();
        LayerListConfigs.USE_STOCK.setBooleanValue(false);
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(false);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(true);

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 1890 -10 1930 50");
            command(server, fill(REF, -4, -1, -4, 16, -1, 40, "minecraft:smooth_stone"));
            command(server, fill(REF, -4, 0, -4, 16, 10, 40, "minecraft:air"));
            // Камень 200 (3 стака и 8), доски 130 (2 стака и 2), стекло 64, светокамень 5, маяк 1
            command(server, fill(REF, 0, 0, 0, 9, 1, 9, STONE));
            command(server, fill(REF, 0, 2, 0, 9, 2, 9, PLANKS));
            command(server, fill(REF, 0, 3, 0, 9, 3, 2, PLANKS));
            command(server, fill(REF, 0, 4, 0, 7, 4, 7, GLASS));
            command(server, fill(REF, 0, 5, 0, 4, 5, 0, GLOW));
            command(server, fill(REF, 0, 6, 0, 0, 6, 0, BEACON));
            command(server, "tp @a " + (REF.getX() + 5.5D) + " " + (REF.getY() + 8) + " " + (REF.getZ() + 14.5D));
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
        String self = Parties.self();
        long wait = 1200L;

        switch (this.stage)
        {
            case 0 ->
            {
                this.schematic = schematic(mc, REF, REF.offset(9, 6, 9), "dev-party-castle");
                this.placement = place(this.schematic, PLACE, NAME);
                LayerListConfigs.HUD_ENABLED = true;
                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");
                    // Пол стенда кончается на z = 40 от REF: встать на него, лицом к постройке
                    command(server, "tp @a " + (PLACE.getX() + 5.5D) + " " + PLACE.getY() + " " + (PLACE.getZ() + 14.5D) + " 180 10");
                });
                GuiSchematics.openPlacements(null);
                wait = 2500L;
            }
            case 1 ->
            {
                this.invoke(this.gui(mc), "showMaterials", null, this.placement);
                wait = 3500L;
            }
            // ===== Создание Party =====
            case 2 ->
            {
                Object pane = this.materials(mc);
                this.check("экран «Схемы» открыт", this.gui(mc) != null, String.valueOf(mc.gui.screen()));
                this.totals(pane, "таблица до Party — весь список", 200, 130, 64, 5, 1);
                Button party = KTrash.field(pane, "partyButton");
                this.check("кнопка Party есть и включена", party != null && party.enabled() && party.w() > 0, "");
                this.check("подпись кнопки — «Party»", party != null && party.label().equals("Party"),
                        party == null ? "" : party.label());
                click(mc, party);
            }
            case 3 ->
            {
                PartyWindow window = this.window(mc);
                this.check("окно Party открылось", window != null, "");

                if (window != null)
                {
                    this.check("Party ещё нет — окно её собирает", ! PartyWindow.TestHooks.exists(window), "");
                    this.check("в составе сразу свой ник", this.members(window).equals(List.of(self)),
                            String.valueOf(this.members(window)));
                    OnlinePlayers.TestHooks.fakeOnline(List.of(FRIEND, "Bob_Builder", "Alice_Mine"));
                    click(mc, KTrash.<Button>field(PartyWindow.TestHooks.roster(window), "online"));
                }

                wait = 900L;
            }
            case 4 ->
            {
                PlayerPicker picker = this.picker(mc);
                this.check("«+ игроки онлайн» открыл выбор игроков", picker != null, "");

                if (picker != null)
                {
                    this.check("в выборе — игроки онлайн", PlayerPicker.TestHooks.shown(picker).contains(FRIEND),
                            String.valueOf(PlayerPicker.TestHooks.shown(picker)));
                    double[] chip = PlayerPicker.TestHooks.chip(picker, FRIEND);
                    this.check("чип " + FRIEND + " виден", chip != null, "");

                    if (chip != null)
                    {
                        SupportTest.click(mc, chip[0], chip[1]);
                        SupportTest.moveMouse(mc, 3.0D, 3.0D);
                    }
                }

                wait = 700L;
            }
            case 5 ->
            {
                PlayerPicker picker = this.picker(mc);
                shot(mc, "party-1-игроки-онлайн");

                if (picker != null)
                {
                    click(mc, PlayerPicker.TestHooks.done(picker));
                }

                wait = 700L;
            }
            case 6 ->
            {
                PartyWindow window = this.window(mc);

                if (window != null)
                {
                    this.check("состав: свой ник и " + FRIEND, this.members(window).equals(List.of(self, FRIEND)),
                            String.valueOf(this.members(window)));
                    List<String> shares = PartyWindow.TestHooks.shareLabels(window);
                    CovOut.log(TAG + "доли до создания: " + shares);
                    this.check("доли посчитаны: #1 — 5 яч., 199 шт., 3 вида; #2 — 5 яч., 201 шт., 4 вида",
                            shares.size() == 2 && shares.get(0).startsWith(self + " #1") && numbers(shares.get(0)).equals("5 199 3")
                                    && shares.get(1).startsWith(FRIEND + " #2") && numbers(shares.get(1)).equals("5 201 4"),
                            String.valueOf(shares));
                    shot(mc, "party-2-состав-и-доли");
                    click(mc, PartyWindow.TestHooks.create(window));
                }
                else
                {
                    this.check("окно Party на месте", false, "");
                }

                wait = 1500L;
            }
            case 7 ->
            {
                PartyWindow window = this.window(mc);
                Object pane = this.materials(mc);
                this.check("Party создана", window != null && PartyWindow.TestHooks.exists(window), "");
                this.check("Party записана у игрока", Parties.all().size() == 1 && Parties.all().getFirst().own(),
                        String.valueOf(Parties.all()));
                this.check("файл config/layerlist_party.json записан",
                        Files.isRegularFile(FabricLoader.getInstance().getConfigDir().resolve("layerlist_party.json")), "");

                if (window != null)
                {
                    this.check("окно сказало «Party создана»", said(window).equals(t("said.created")), said(window));
                    this.check("до первой рассылки окно не просит «поделиться ещё раз»", note(window).isEmpty(), note(window));
                    this.check("выбрана своя доля", PartyWindow.TestHooks.shownRow(window) == 0,
                            "" + PartyWindow.TestHooks.shownRow(window));
                    this.check("у созданной Party есть строка «Весь список»",
                            PartyWindow.TestHooks.shareLabels(window).size() == 3, "");
                }

                this.totals(pane, "таблица — своя доля #1", 128, 66, 0, 5, 0);
                Button party = KTrash.field(pane, "partyButton");
                this.check("подпись кнопки — «Party #1»", party != null && party.label().equals("Party #1"),
                        party == null ? "" : party.label());
                shot(mc, "party-3-создана");
                this.chatBefore = this.chat(mc).size();

                if (window != null)
                {
                    click(mc, PartyWindow.TestHooks.share(window));
                }

                wait = 1800L;
            }
            case 8 ->
            {
                PartyWindow window = this.window(mc);
                this.check("окно сказало «отправлено в чат»", window != null && said(window).contains(t("said.shared")),
                        window == null ? "" : said(window));
                // Свежей строкой чата бывает «Снимок экрана сохранён» — приглашение ищем по названию Party
                Component invite = this.line(mc, "«" + NAME + "»");
                String line = invite == null ? "" : invite.getString();
                CovOut.log(TAG + "чат после рассылки: " + line);
                this.check("в чат пришло приглашение", this.chat(mc).size() > this.chatBefore && invite != null, "");
                this.check("в чате приглашение словами, без кода", line.contains("«" + NAME + "»") && ! line.contains("mhud-party:"),
                        line);
                this.check("кода нет ни в одной строке чата", this.line(mc, "mhud-party:") == null, "");
                this.check("себе ссылку не предлагают: «" + tc("joined") + "»", line.endsWith(tc("joined"))
                        && this.link(invite) == null, line);
                // Имя отправителя — один раз: сообщение приходит уже оформленным, второй раз его не оформляем
                this.check("строка у ведущего — «<ник> приглашение», имя один раз",
                        line.equals("<" + self + "> " + I18nText.get(KEY + "chat.invite.mine", NAME, 2, 1) + " " + tc("joined")), line);

                // Отправленное приглашение — в истории чата: стрелка вверх его вернёт
                Party shared = Parties.all().stream().filter(Party::own).findFirst().orElse(null);
                this.sentInvite = shared == null ? null : PartyChat.message(shared);
                var recent = mc.gui.hud.getChat().getRecentChat();
                String last = recent.isEmpty() ? "" : recent.getLast();
                this.check("отправленное приглашение — последнее в истории чата", this.sentInvite != null
                        && this.sentInvite.equals(last), last);

                if (window != null)
                {
                    this.check("у созданной Party есть кнопка «" + t("copy") + "»", PartyWindow.TestHooks.copy(window) != null, "");
                    click(mc, PartyWindow.TestHooks.copy(window));
                    this.check("окно сказало «приглашение скопировано»", said(window).equals(t("said.copied")), said(window));
                    this.check("в буфер обмена ушла та же строка, что в чат", this.sentInvite != null
                            && this.sentInvite.equals(PartyChat.TestHooks.lastCopied()), String.valueOf(PartyChat.TestHooks.lastCopied()));
                    String clipboard = mc.keyboardHandler.getClipboard();
                    CovOut.log(TAG + "буфер обмена игры: " + clipboard);
                    // Без окна буфер обмена бывает недоступен — тогда он пуст; если отдаёт, то именно приглашение
                    this.check("буфер обмена игры отдаёт приглашение (или недоступен)", clipboard == null || clipboard.isEmpty()
                            || clipboard.equals(this.sentInvite), clipboard);
                    int copies = 0;

                    for (String sent : mc.gui.hud.getChat().getRecentChat())
                    {
                        copies += sent.equals(this.sentInvite) ? 1 : 0;
                    }

                    this.check("в истории чата приглашение одно, без повтора", copies == 1, "строк " + copies);
                    shot(mc, "party-3б-скопировать-приглашение");
                }

                if (window != null)
                {
                    this.clickRow(mc, window, 1);
                }

                wait = 900L;
            }
            case 9 ->
            {
                PartyWindow window = this.window(mc);
                this.totals(this.materials(mc), "щёлкнули долю #2 — таблица показывает её", 72, 64, 64, 0, 1);

                if (window != null)
                {
                    this.check("в окне выбрана доля #2", PartyWindow.TestHooks.shownRow(window) == 1, "");
                    this.clickRow(mc, window, 2);
                }

                wait = 900L;
            }
            case 10 ->
            {
                PartyWindow window = this.window(mc);
                Object pane = this.materials(mc);
                this.totals(pane, "щёлкнули «Весь список» — таблица целиком", 200, 130, 64, 5, 1);
                Button party = KTrash.field(pane, "partyButton");
                this.check("подпись кнопки снова «Party»", party != null && party.label().equals("Party"),
                        party == null ? "" : party.label());

                if (window != null)
                {
                    this.clickRow(mc, window, 0);
                }

                wait = 900L;
            }
            case 11 ->
            {
                PartyWindow window = this.window(mc);
                Object pane = this.materials(mc);
                this.totals(pane, "щёлкнули свою долю — снова #1", 128, 66, 0, 5, 0);

                if (window != null)
                {
                    window.cancel();
                }

                String footer = this.text(pane, "footerStatus");
                this.check("подвал говорит, чья доля", footer.contains(self + " #1"), footer);
                wait = 600L;
            }
            case 12 ->
            {
                shot(mc, "party-4-таблица-доля");
                mc.gui.setScreen(null);
                LayerCountManager.getInstance().markDirty();
                wait = 3500L;
            }
            // ===== Список HUD =====
            case 13 ->
            {
                this.hud("список HUD — своя доля #1", 128, 66, 0, 5, 0);
                Parties.HudShare share = LayerCountManager.getInstance().getPartyShare();
                this.check("HUD знает, что показана доля #1", share != null && share.applied() != null
                        && share.applied().number() == 1, String.valueOf(share));
                shot(mc, "party-5-hud-доля");

                Parties.Base base = Parties.base(this.schematic);
                this.fingerprint = base == null ? "" : base.fingerprint();
                this.check("отпечаток списка схемы посчитан", this.fingerprint.length() == 8, this.fingerprint);
                // Дальше — глазами приглашённого: своей Party нет
                Parties.TestHooks.clear();
                wait = 3500L;
            }
            // ===== Чужое приглашение =====
            case 14 ->
            {
                this.hud("Party нет — список HUD целиком", 200, 130, 64, 5, 1);
                this.check("HUD без пометки Party", LayerCountManager.getInstance().getPartyShare() == null, "");
                String message = PartyCode.message(new PartyCode.Invite("host01", this.fingerprint, List.of(HOST, self), NAME),
                        I18nText.get(KEY + "chat.plain"));
                CovOut.log(TAG + "сообщение ведущего как оно идёт по сети: " + message);
                this.check("сообщение влезает в чат", message != null && message.length() <= PartyCode.CHAT_MAX, "");
                mc.gui.chatListener().handleSystemMessage(Component.literal("[G] " + HOST + ": " + message), false);
                wait = 700L;
            }
            case 15 ->
            {
                Component message = this.line(mc, tc("join"));
                String line = message == null ? "" : message.getString();
                String want = "[G] " + HOST + ": " + I18nText.get(KEY + "chat.invite.mine", NAME, 2, 2) + " " + tc("join");
                CovOut.log(TAG + "чат у приглашённого: " + line);
                this.check("приглашение словами со ссылкой «" + tc("join") + "»", line.equals(want), line);
                Style link = this.link(message);
                this.check("ссылка есть", link != null, "");

                if (link != null)
                {
                    this.check("цвет ссылки #CCFF33", link.getColor() != null && link.getColor().getValue() == PartyChat.LINK_COLOR,
                            String.valueOf(link.getColor()));
                    this.check("тень ссылки #679B0E", link.getShadowColor() != null && link.getShadowColor() == PartyChat.LINK_SHADOW,
                            link.getShadowColor() == null ? "нет" : Integer.toHexString(link.getShadowColor()));
                    this.check("ссылка — клиентская команда вступления", link.getClickEvent() instanceof ClickEvent.RunCommand run
                            && run.command().startsWith("/mhudparty join mhud-party:1:host01:"), String.valueOf(link.getClickEvent()));
                }

                mc.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
                wait = 900L;
            }
            case 16 ->
            {
                // Сначала — стрелка вверх в окне чата: в поле ввода встаёт приглашение. Три захода, потом прежняя проверка
                if (this.arrowStep == 0)
                {
                    arrow(mc, com.mojang.blaze3d.platform.InputConstants.KEY_UP, SDLK_UP);

                    this.arrowStep = 1;
                    this.nextAt = now + 900L;
                    return false;
                }

                if (this.arrowStep == 1)
                {
                    net.minecraft.client.gui.components.EditBox input = mc.gui.screen() == null ? null
                            : KTrash.field(mc.gui.screen(), "input");
                    String typed = input == null ? "" : input.getValue();
                    this.check("стрелка вверх в чате возвращает приглашение", typed.equals(this.sentInvite), typed);
                    shot(mc, "party-6а-стрелка-вверх");
                    this.arrowStep = 2;
                    this.nextAt = now + 2500L;
                    return false;
                }

                if (this.arrowStep == 2)
                {
                    arrow(mc, com.mojang.blaze3d.platform.InputConstants.KEY_DOWN, SDLK_DOWN);

                    this.arrowStep = 3;
                    this.nextAt = now + 900L;
                    return false;
                }

                shot(mc, "party-6-чат-приглашение");
                double[] at = this.linkOnScreen(mc);
                this.check("ссылка найдена на экране чата", at != null, "");

                if (at != null)
                {
                    CovOut.log(TAG + "щёлкаю по ссылке в " + at[0] + ", " + at[1]);
                    SupportTest.click(mc, at[0], at[1]);
                }

                wait = 1500L;
            }
            case 17 ->
            {
                Party joined = Parties.byId("host01");
                this.check("настоящий клик по ссылке — игрок в Party", joined != null, "");

                if (joined == null)
                {
                    // Клик мимо — остальное проверяем через тот же путь, каким ссылку нажимает игра
                    Style link = this.link(this.findInvite(mc));

                    if (link != null && link.getClickEvent() instanceof ClickEvent.RunCommand run && mc.getConnection() != null)
                    {
                        mc.getConnection().sendUnattendedCommand(run.command().substring(1), mc.gui.screen());
                    }

                    joined = Parties.byId("host01");
                }

                this.check("Party принята: чужая, свой номер — #2", joined != null && ! joined.own() && joined.indexOf(self) == 1,
                        String.valueOf(joined));
                String answer = I18nText.get(KEY + "chat.answer.new", NAME, 2, 2);
                this.check("в чате ответ «" + answer + "»", this.line(mc, answer) != null, this.newest(mc));

                if (mc.gui.screen() instanceof ChatScreen)
                {
                    mc.gui.setScreen(null);
                }

                wait = 3500L;
            }
            case 18 ->
            {
                this.hud("список HUD у приглашённого — доля #2", 72, 64, 64, 0, 1);
                shot(mc, "party-7-hud-после-вступления");
                GuiSchematics.openPlacements(null);
                wait = 2000L;
            }
            case 19 ->
            {
                this.invoke(this.gui(mc), "showMaterials", null, this.placement);
                wait = 3500L;
            }
            case 20 ->
            {
                Object pane = this.materials(mc);
                this.totals(pane, "таблица у приглашённого — доля #2", 72, 64, 64, 0, 1);
                Button party = KTrash.field(pane, "partyButton");
                this.check("подпись кнопки — «Party #2»", party != null && party.label().equals("Party #2"),
                        party == null ? "" : party.label());
                click(mc, party);
                // Видимость «+ игроки онлайн» окно выставляет при отрисовке: дать новому окну нарисоваться (24.11.2026 —
                // под программным рендером кадр за 1,2 с успевал не всегда)
                wait = 3500L;
            }
            case 21 ->
            {
                PartyWindow window = this.window(mc);
                this.check("окно принятой Party открылось", window != null && PartyWindow.TestHooks.exists(window), "");

                if (window != null)
                {
                    Button leave = PartyWindow.TestHooks.leave(window);
                    this.check("у чужой Party кнопка «" + t("leave") + "»", leave != null && leave.label().equals(t("leave")),
                            leave == null ? "" : leave.label());
                    Button online = KTrash.field(PartyWindow.TestHooks.roster(window), "online");
                    this.check("состав чужой Party не правится («+ игроки онлайн» скрыта)", online != null && ! online.visible(), "");
                    this.check("состав — как в приглашении", this.members(window).equals(List.of(HOST, self)),
                            String.valueOf(this.members(window)));
                    shot(mc, "party-8-окно-принятой");
                    window.cancel();
                }

                // Выгрузка: шапка говорит, чья доля
                this.invoke(this.materials(mc), "export", ExportFormat.COPY);
            }
            case 22 ->
            {
                Modal export = this.exportWindow(mc);
                ExportRequest request = null;

                try
                {
                    request = export == null ? null : (ExportRequest) KProbe.call(export, "request");
                }
                catch (ReflectiveOperationException e)
                {
                    CovOut.log(TAG + "request: " + e);
                }

                this.check("выгрузка: в подписи источника — доля", request != null && request.source().contains(self + " #2"),
                        request == null ? "" : request.source());
                long stone = 0L;

                if (request != null)
                {
                    for (var row : request.rows())
                    {
                        stone += StorageIndex.itemId(row.stack()).equals(STONE) ? row.total() : 0L;
                    }
                }

                this.check("выгрузка: камня — доля (72)", stone == 72L, "" + stone);

                if (export != null)
                {
                    export.cancel();
                }

                // Приглашение без своего ника и от более новой версии мода
                String foreign = PartyCode.message(new PartyCode.Invite("other1", this.fingerprint, List.of(HOST, FRIEND), NAME),
                        I18nText.get(KEY + "chat.plain"));
                mc.gui.chatListener().handleSystemMessage(Component.literal("<" + HOST + "> " + foreign), false);
                wait = 600L;
            }
            case 23 ->
            {
                Component message = this.line(mc, tc("not_listed"));
                String line = message == null ? "" : message.getString();
                this.check("чужое приглашение без своего ника: «" + tc("not_listed") + "», ссылки нет",
                        line.contains(tc("not_listed")) && this.link(message) == null && ! line.contains("mhud-party:"), line);
                mc.gui.chatListener().handleSystemMessage(Component.literal("<" + HOST + "> [Material HUD] hi mhud-party:7:zz"),
                        false);
                wait = 600L;
            }
            case 24 ->
            {
                Component message = this.line(mc, tc("newer"));
                String line = message == null ? "" : message.getString();
                this.check("приглашение от новой версии: просьба обновить мод, ссылки нет",
                        line.equals("<" + HOST + "> " + tc("newer")) && this.link(message) == null, line);
                mc.gui.chatListener().handleSystemMessage(Component.literal("<" + HOST + "> просто mhud-party:1:сломан"), false);
                wait = 600L;
            }
            // ===== Другой список схемы =====
            case 25 ->
            {
                Component broken = this.line(mc, "сломан");
                this.check("битый код остаётся как пришёл", broken != null
                        && broken.getString().equals("<" + HOST + "> просто mhud-party:1:сломан"), broken == null ? "" : broken.getString());
                Parties.TestHooks.clear();
                String code = PartyCode.code(new PartyCode.Invite("host02", "0badf00d", List.of(HOST, self), NAME));

                if (mc.getConnection() != null)
                {
                    mc.getConnection().sendCommand("mhudparty join " + code);
                }

                wait = 3500L;
            }
            case 26 ->
            {
                this.check("Party с другим списком принята", Parties.byId("host02") != null, "");
                boolean told = false;

                for (GuiMessage message : this.chat(mc))
                {
                    told |= message.content().getString().equals(I18nText.get(KEY + "chat.no_schematic", NAME));
                }

                this.check("чат сказал, что схемы ведущего среди загруженных нет", told, this.newest(mc));
                Object pane = this.materials(mc);
                this.totals(pane, "чужой список не делит таблицу", 200, 130, 64, 5, 1);
                click(mc, KTrash.<Button>field(pane, "partyButton"));
            }
            case 27 ->
            {
                PartyWindow window = this.window(mc);
                this.check("окно предлагает привязать принятую Party", window != null && ! PartyWindow.TestHooks.exists(window)
                        && note(window).contains(NAME), window == null ? "" : note(window));
                int[] box = window == null ? null : PartyWindow.TestHooks.noteAction(window, t("note.waiting.bind"));
                this.check("кнопка «" + t("note.waiting.bind") + "» есть", box != null, "");

                if (box != null)
                {
                    SupportTest.click(mc, box[0] + box[2] / 2.0D, box[1] + box[3] / 2.0D);
                    SupportTest.moveMouse(mc, 3.0D, 3.0D);
                }

                wait = 1500L;
            }
            case 28 ->
            {
                PartyWindow window = this.window(mc);
                this.check("Party привязана к схеме", window != null && PartyWindow.TestHooks.exists(window), "");
                this.check("окно предупреждает: список не такой, как у ведущего",
                        window != null && note(window).equals(t("note.mismatch")), window == null ? "" : note(window));
                this.totals(this.materials(mc), "привязанная Party делит таблицу: доля #2", 72, 64, 64, 0, 1);
                shot(mc, "party-9-другой-список");

                if (window != null)
                {
                    click(mc, PartyWindow.TestHooks.leave(window));
                }

                wait = 900L;
            }
            case 29 ->
            {
                Layers layers = this.layers(mc);
                Modal confirm = layers != null && layers.top() instanceof Modal modal ? modal : null;
                Button yes = KTrash.modalButton(confirm, t("leave"));
                this.check("«Выйти» спрашивает подтверждение", yes != null, KTrash.modalTitle(confirm));
                click(mc, yes);
                wait = 1500L;
            }
            case 30 ->
            {
                this.check("после выхода Party нет", Parties.all().isEmpty(), String.valueOf(Parties.all()));
                this.totals(this.materials(mc), "после выхода таблица снова целиком", 200, 130, 64, 5, 1);
                PartyWindow window = this.window(mc);

                if (window != null)
                {
                    window.cancel();
                }

                wait = 500L;
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

    // ===== Окно Party =====

    private @Nullable Layers layers(Minecraft mc)
    {
        return KProbe.fieldOfType(this.gui(mc), Layers.class);
    }

    private @Nullable PlayerPicker picker(Minecraft mc)
    {
        Layers layers = this.layers(mc);

        return layers == null ? null : PlayerPicker.TestHooks.current(layers);
    }

    private @Nullable PartyWindow window(Minecraft mc)
    {
        Layers layers = this.layers(mc);

        return layers == null ? null : PartyWindow.TestHooks.current(layers);
    }

    private @Nullable Modal exportWindow(Minecraft mc)
    {
        Layers layers = this.layers(mc);

        return layers != null && layers.top() instanceof Modal modal && modal.getClass().getSimpleName().equals("ExportWindow")
                ? modal : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> members(PartyWindow window)
    {
        Object store = KTrash.field(PartyWindow.TestHooks.roster(window), "store");

        try
        {
            return store == null ? List.of() : List.copyOf((List<String>) KProbe.call(store, "list"));
        }
        catch (ReflectiveOperationException e)
        {
            return List.of(e.toString());
        }
    }

    private void clickRow(Minecraft mc, PartyWindow window, int index)
    {
        int[] box = PartyWindow.TestHooks.shareRow(window, index);
        this.check("строка доли " + (index + 1) + " есть", box != null, "");

        if (box != null)
        {
            SupportTest.click(mc, box[0] + 20.0D, box[1] + box[3] / 2.0D);
            SupportTest.moveMouse(mc, 3.0D, 3.0D);
        }
    }

    private static String said(PartyWindow window)
    {
        String said = PartyWindow.TestHooks.said(window);

        return said == null ? "" : said;
    }

    private static String note(PartyWindow window)
    {
        String note = PartyWindow.TestHooks.note(window);

        return note == null ? "" : note;
    }

    /** Числа из подписи доли по порядку: ячейки, штуки, виды. */
    private static String numbers(String label)
    {
        int bar = label.indexOf('|');
        String stats = bar < 0 ? label : label.substring(bar + 1);
        List<String> out = new ArrayList<>();
        StringBuilder digits = new StringBuilder();

        for (char c : (stats + " ").toCharArray())
        {
            if (Character.isDigit(c))
            {
                digits.append(c);
            }
            else if (digits.length() > 0)
            {
                out.add(digits.toString());
                digits.setLength(0);
            }
        }

        return String.join(" ", out);
    }

    private static String t(String suffix)
    {
        return I18nText.get(KEY + suffix);
    }

    private static String tc(String suffix)
    {
        return I18nText.get(KEY + "chat." + suffix);
    }

    private static void click(Minecraft mc, @Nullable Widget widget)
    {
        if (widget == null)
        {
            CovOut.log(TAG + "щёлкать не по чему");
            return;
        }

        SupportTest.click(mc, widget.x() + Math.min(8.0D, widget.w() / 2.0D), widget.y() + widget.h() / 2.0D);
        // Курсор — в сторону: подсказка под ним закрывала бы окно на снимке
        SupportTest.moveMouse(mc, 3.0D, 3.0D);
    }

    // ===== Чат =====

    private List<GuiMessage> chat(Minecraft mc)
    {
        List<GuiMessage> all = KTrash.field(mc.gui.hud.getChat(), "allMessages");

        return all == null ? List.of() : all;
    }

    private @Nullable Component newestMessage(Minecraft mc)
    {
        List<GuiMessage> all = this.chat(mc);

        return all.isEmpty() ? null : all.getFirst().content();
    }

    private String newest(Minecraft mc)
    {
        Component message = this.newestMessage(mc);

        return message == null ? "" : message.getString();
    }

    /** Самое свежее сообщение чата, в тексте которого есть {@code part}; {@code null} — такого нет. */
    private @Nullable Component line(Minecraft mc, String part)
    {
        for (GuiMessage message : this.chat(mc))
        {
            if (message.content().getString().contains(part))
            {
                return message.content();
            }
        }

        return null;
    }

    /** Самое свежее сообщение чата со ссылкой вступления. */
    private @Nullable Component findInvite(Minecraft mc)
    {
        for (GuiMessage message : this.chat(mc))
        {
            if (this.link(message.content()) != null)
            {
                return message.content();
            }
        }

        return null;
    }

    /**
     * Стиль ссылки вступления в сообщении; {@code null} — такой нет. Имя игрока в чате тоже кликабельно
     * (подставляет «/tell ник») — это не она.
     */
    private @Nullable Style link(@Nullable Component message)
    {
        if (message == null)
        {
            return null;
        }

        return message.visit((style, text) -> joins(style) ? Optional.of(style) : Optional.<Style>empty(), Style.EMPTY)
                .orElse(null);
    }

    private static boolean joins(Style style)
    {
        return style.getClickEvent() instanceof ClickEvent.RunCommand run && run.command().startsWith("/mhudparty join ");
    }

    /**
     * Где на экране чата ссылка: строки чата идут снизу вверх от отступа 40, слева отступ 4; сдвиг ссылки в строке —
     * ширина текста перед ней.
     */
    private double @Nullable [] linkOnScreen(Minecraft mc)
    {
        List<GuiMessage.Line> lines = KTrash.field(mc.gui.hud.getChat(), "trimmedMessages");

        if (lines == null)
        {
            return null;
        }

        int height = mc.getWindow().getGuiScaledHeight();

        for (int i = 0; i < lines.size() && i < 20; ++i)
        {
            StringBuilder before = new StringBuilder();
            StringBuilder linkText = new StringBuilder();

            lines.get(i).content().accept((position, style, codePoint) ->
            {
                (joins(style) ? linkText : linkText.isEmpty() ? before : new StringBuilder()).appendCodePoint(codePoint);
                return true;
            });

            if (! linkText.isEmpty())
            {
                double x = 4.0D + mc.font.width(before.toString()) + mc.font.width(linkText.toString()) / 2.0D;
                double y = height - 40.0D - i * 9.0D - 4.5D;
                CovOut.log(TAG + "ссылка в строке чата " + i + " снизу: «" + linkText + "»");

                return new double[] { x, y };
            }
        }

        return null;
    }

    // ===== Экран «Схемы» =====

    private @Nullable GuiSchematics gui(Minecraft mc)
    {
        return mc.gui.screen() instanceof GuiSchematics gui ? gui : null;
    }

    private @Nullable Object materials(Minecraft mc)
    {
        return KProbe.fieldNamedType(this.gui(mc), "MaterialsPane");
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

    private String text(@Nullable Object target, String method)
    {
        try
        {
            return target == null ? "" : String.valueOf(KProbe.call(target, method));
        }
        catch (ReflectiveOperationException e)
        {
            return e.toString();
        }
    }

    /** «Всего» строки предмета в таблице «Материалов»; 0 — строки нет. */
    private long total(@Nullable Object pane, String itemId)
    {
        List<?> lines = KTrash.field(pane, "lines");

        if (lines != null)
        {
            for (Object line : lines)
            {
                MaterialLine l = (MaterialLine) line;

                if (StorageIndex.itemId(l.stack()).equals(itemId))
                {
                    return l.need();
                }
            }
        }

        return 0L;
    }

    private void totals(@Nullable Object pane, String what, long stone, long planks, long glass, long glow, long beacon)
    {
        String got = this.total(pane, STONE) + " / " + this.total(pane, PLANKS) + " / " + this.total(pane, GLASS) + " / "
                + this.total(pane, GLOW) + " / " + this.total(pane, BEACON);
        String want = stone + " / " + planks + " / " + glass + " / " + glow + " / " + beacon;
        this.check(what + " (камень / доски / стекло / светокамень / маяк = " + want + ")", got.equals(want), got);
    }

    /** То же по списку HUD: схема не построена, «не хватает» = «всего». */
    private void hud(String what, long stone, long planks, long glass, long glow, long beacon)
    {
        long[] got = new long[5];
        String[] ids = { STONE, PLANKS, GLASS, GLOW, BEACON };

        for (MaterialListEntry entry : LayerCountManager.getInstance().getAllEntries())
        {
            for (int i = 0; i < ids.length; ++i)
            {
                if (StorageIndex.itemId(entry.getStack()).equals(ids[i]))
                {
                    got[i] += entry.getCountMissing();
                }
            }
        }

        String shown = got[0] + " / " + got[1] + " / " + got[2] + " / " + got[3] + " / " + got[4];
        String want = stone + " / " + planks + " / " + glass + " / " + glow + " / " + beacon;
        this.check(what + " (камень / доски / стекло / светокамень / маяк = " + want + ")", shown.equals(want), shown);
    }

    // ===== Схема и мир =====

    private static LitematicaSchematic schematic(Minecraft mc, BlockPos from, BlockPos to, String name)
    {
        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(from, to, "main"), false);
        area.setExplicitOrigin(from);
        return LitematicaSchematic.createFromWorld(mc.level, area, new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                "dev", CovOut::log);
    }

    private static SchematicPlacement place(LitematicaSchematic schematic, BlockPos at, String name)
    {
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, at, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        return placement;
    }

    /** Коды стрелок, по которым их узнаёт окно чата 26.3 ({@code KeyEvent.shortcutKey}). */
    private static final int SDLK_UP = 1073741906;
    private static final int SDLK_DOWN = 1073741905;

    /** Стрелка через KeyboardHandler — тот же путь, что у настоящей клавиши. */
    private static void arrow(Minecraft mc, int key, int code)
    {
        net.minecraft.client.input.KeyEvent event = new net.minecraft.client.input.KeyEvent(key, code, 0);
        DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.PRESS, event);
        DevInput.keyPress(mc.keyboardHandler, mc.getWindow().handle(), com.mojang.blaze3d.platform.InputConstants.RELEASE, event);
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
        LayerListConfigs.MATERIAL_CUTOFF.setBooleanValue(this.cutoffWas);
        OnlinePlayers.TestHooks.fakeOnline(null);
        Parties.TestHooks.clear();

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }
    }

    /** Перевод без подстановок и с ними — тем же путём, что в игре. */
    private static final class I18nText
    {
        static String get(String key, Object... args)
        {
            return net.minecraft.client.resources.language.I18n.get(key, args);
        }
    }
}
