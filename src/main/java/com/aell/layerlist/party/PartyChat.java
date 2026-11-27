package com.aell.layerlist.party;

import com.mojang.brigadier.arguments.StringArgumentType;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Party в чате: рассылка приглашения и ссылка «Принять приглашение».
 *
 * <p>Приглашение — обычное сообщение с кодом ({@link PartyCode}). Мод находит код во входящих сообщениях и
 * показывает вместо него приглашение словами со ссылкой фирменного цвета; ссылка — клиентская команда
 * {@code /mhudparty join <код>}, на сервер она не уходит. Игрок без мода видит слова ведущего и сам код.
 */
@Environment(EnvType.CLIENT)
public final class PartyChat
{
    private static final String COMMAND = "mhudparty";
    private static final String KEY = "layerlist.party.chat.";
    /** Цвет ссылки «Принять приглашение» — фирменный лайм, и его тёмная пара — тенью текста. */
    public static final int LINK_COLOR = 0xCCFF33;
    public static final int LINK_SHADOW = 0xFF679B0E;
    /** Сколько ждём счёт основ загруженных схем, прежде чем сказать, что подходящей схемы нет. */
    private static final long CHECK_MAX_MS = 30_000L;

    /** Party, принятая только что: когда основы схем досчитаются — сказать, если её схемы нет. */
    /** Что последним положили в буфер обмена (для харнесса: буфер без окна бывает недоступен). */
    private static @Nullable String lastCopied;
    private static @Nullable String checkId;
    private static long checkSince;

    private PartyChat() {}

    /** Чем кончилась рассылка. */
    public enum Sent
    {
        OK,
        /** Нет подключения (главное меню). */
        OFFLINE,
        /** Состав не влезает в строку чата. */
        TOO_LONG
    }

    public static void init()
    {
        // Чат игроков: сообщение прячем и показываем своё. Сюда оно приходит уже оформленным («<Ник> текст») —
        // начало до приглашения остаётся как было
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) ->
        {
            Component shown = replaced(message);

            if (shown == null)
            {
                return true;
            }

            Minecraft.getInstance().gui.getChat().addMessage(shown);
            return false;
        });

        // Чат, который сервер шлёт системным сообщением (плагины чата)
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
        {
            Component shown = overlay ? null : replaced(message);

            return shown == null ? message : shown;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommandManager.literal(COMMAND).then(ClientCommandManager.literal("join")
                        .then(ClientCommandManager.argument("code", StringArgumentType.greedyString()).executes(command ->
                        {
                            Component answer = join(StringArgumentType.getString(command, "code"));
                            command.getSource().sendFeedback(answer);
                            return 1;
                        })))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // ===== Рассылка =====

    /** Отправить приглашение в общий чат. */
    public static Sent share(Party party)
    {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();

        if (connection == null)
        {
            return Sent.OFFLINE;
        }

        String message = message(party);

        if (message == null)
        {
            return Sent.TOO_LONG;
        }

        connection.sendChat(message);
        remember(message);
        return Sent.OK;
    }

    /**
     * Положить приглашение в буфер обмена (просьба владельца 24.11.2026): та же строка, что уходит в чат, — её можно
     * вставить в личное сообщение или в чат клана. Заодно она ложится в историю чата: стрелка вверх её вернёт.
     */
    public static Sent copy(Party party)
    {
        String message = message(party);

        if (message == null)
        {
            return Sent.TOO_LONG;
        }

        Minecraft.getInstance().keyboardHandler.setClipboard(message);
        lastCopied = message;
        remember(message);
        return Sent.OK;
    }

    /** Строка приглашения, как она уходит в чат; {@code null} — состав не влезает в сообщение чата. */
    public static @Nullable String message(Party party)
    {
        return PartyCode.message(invite(party), I18n.get(KEY + "plain"));
    }

    /**
     * В историю отправленных сообщений чата — как набранное руками: игра сама кладёт туда только то, что отправлено из
     * окна чата. Стрелка вверх в чате вернёт приглашение, чтобы разослать его ещё раз. Повтор подряд игра не добавляет.
     */
    private static void remember(String message)
    {
        Minecraft.getInstance().gui.getChat().addRecentChat(message);
    }

    public static PartyCode.Invite invite(Party party)
    {
        return new PartyCode.Invite(party.id(), party.fingerprint(), party.roster(), party.name());
    }

    // ===== Входящее сообщение =====

    /** Сообщение с приглашением вместо кода; {@code null} — кода в сообщении нет, показывать как есть. */
    static @Nullable Component replaced(Component message)
    {
        PartyCode.Found found = PartyCode.find(message.getString());

        if (found == null)
        {
            return null;
        }

        MutableComponent out = head(message, found.start());

        if (found.invite() == null)
        {
            // Битый код оставляем как пришёл; код новее сборки — просим обновить мод
            return found.newer() ? out.append(text("newer", ChatFormatting.YELLOW)) : null;
        }

        return out.append(line(found.invite()));
    }

    /** Приглашение словами: название, сколько участников, свой номер — и ссылка фирменного цвета. */
    private static Component line(PartyCode.Invite invite)
    {
        int count = invite.roster().size();
        String name = invite.name().isBlank() ? I18n.get(KEY + "unnamed") : invite.name();
        int mine = - 1;

        for (int i = 0; i < count; ++i)
        {
            if (invite.roster().get(i).equalsIgnoreCase(Parties.self()))
            {
                mine = i;
            }
        }

        if (mine < 0)
        {
            return Component.literal(I18n.get(KEY + "invite", name, count) + " ")
                    .append(text("not_listed", ChatFormatting.GRAY));
        }

        MutableComponent out = Component.literal(I18n.get(KEY + "invite.mine", name, count, mine + 1) + " ");

        if (Parties.joined(invite))
        {
            return out.append(text("joined", ChatFormatting.GRAY));
        }

        // Без подчёркивания: с цветной тенью оно двоится, ссылку выделяет цвет
        Style link = Style.EMPTY.withColor(LINK_COLOR).withShadowColor(LINK_SHADOW)
                .withClickEvent(new ClickEvent.RunCommand("/" + COMMAND + " join " + PartyCode.code(invite)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(I18n.get(KEY + "join.tip", mine + 1, count))));

        return out.append(Component.literal(I18n.get(KEY + "join")).withStyle(link));
    }

    private static MutableComponent text(String suffix, ChatFormatting color)
    {
        return Component.literal(I18n.get(KEY + suffix)).withStyle(color);
    }

    /** Начало сообщения до знака {@code end} с его оформлением (имя игрока, префиксы сервера). */
    private static MutableComponent head(Component message, int end)
    {
        MutableComponent out = Component.empty();
        int[] left = { end };

        message.visit((style, part) ->
        {
            if (left[0] <= 0)
            {
                return Optional.of(Unit.INSTANCE);
            }

            String kept = part.length() <= left[0] ? part : part.substring(0, left[0]);
            left[0] -= kept.length();
            out.append(Component.literal(kept).withStyle(style));

            return Optional.empty();
        }, Style.EMPTY);

        return out;
    }

    // ===== Вступление =====

    /** Принять приглашение по коду из ссылки; ответ — строкой в чат. */
    static Component join(String code)
    {
        PartyCode.Found found = PartyCode.find(code);

        if (found == null || found.invite() == null)
        {
            return text(found != null && found.newer() ? "newer" : "broken", ChatFormatting.RED);
        }

        PartyCode.Invite invite = found.invite();
        String name = invite.name().isBlank() ? I18n.get(KEY + "unnamed") : invite.name();
        Parties.Joined joined = Parties.join(invite);

        if (joined == Parties.Joined.NOT_LISTED)
        {
            return Component.literal(I18n.get(KEY + "answer.not_listed", name)).withStyle(ChatFormatting.RED);
        }

        Party party = Parties.byId(invite.id());
        int number = party == null ? 0 : party.indexOf(Parties.self()) + 1;
        checkId = invite.id();
        checkSince = Util.getMillis();

        return Component.literal(I18n.get(KEY + (joined == Parties.Joined.NEW ? "answer.new" : "answer.updated"), name, number,
                invite.roster().size())).withStyle(ChatFormatting.GREEN);
    }

    /**
     * После вступления: основы загруженных схем досчитались, а схемы Party среди них нет — сказать об этом
     * один раз (саму схему через чат не передать).
     */
    private static void tick()
    {
        String id = checkId;

        if (id == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Party party = Parties.byId(id);

        if (party == null || mc.level == null || mc.player == null)
        {
            checkId = null;
            return;
        }

        boolean pending = false;
        boolean found = false;

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                LitematicaSchematic schematic = placement.getSchematic();
                Parties.Base base = Parties.base(schematic);

                if (base != null && base.fingerprint().equals(party.fingerprint()))
                {
                    found = true;
                }
                else if (base == null && Parties.basePending(schematic))
                {
                    pending = true;
                }
            }
        }
        catch (RuntimeException e)
        {
            checkId = null;
            return;
        }

        if (found)
        {
            checkId = null;
            return;
        }

        if (pending && Util.getMillis() - checkSince < CHECK_MAX_MS)
        {
            return;
        }

        checkId = null;
        String name = party.name().isBlank() ? I18n.get(KEY + "unnamed") : party.name();
        mc.gui.getChat().addMessage(
                Component.literal(I18n.get(KEY + "no_schematic", name)).withStyle(ChatFormatting.YELLOW));
    }

    /** Только для харнесса. В игре никто не зовёт. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Приглашение, которое последним ушло в буфер обмена. */
        public static @Nullable String lastCopied()
        {
            return lastCopied;
        }
    }
}
