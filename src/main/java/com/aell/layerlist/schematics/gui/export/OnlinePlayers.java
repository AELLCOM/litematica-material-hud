package com.aell.layerlist.schematics.gui.export;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.jetbrains.annotations.Nullable;

/**
 * Ники из списка игроков сервера (то, что видно по Tab) для выбора в «Кто собирает»: свой ник
 * первым, остальные по алфавиту без учёта регистра.
 *
 * <p>Ники берутся как есть (у игроков Bedrock бывают префиксы «.» или «*»), пропускаются только явно
 * не ники — пустые, с пробелами и цветами (служебные строки плагинов Tab).
 */
@Environment(EnvType.CLIENT)
public final class OnlinePlayers
{
    private static final Pattern LISTED = Pattern.compile("[.*]?[A-Za-z0-9_]{1," + RosterField.NICK_MAX + "}");

    /** Ники харнесса вместо пустого сервера одиночной игры ({@link TestHooks}); в игре всегда {@code null}. */
    private static @Nullable List<String> fake;

    private OnlinePlayers() {}

    /** {@code null} — подключения нет (главное меню). В одиночной игре — сам игрок. */
    static @Nullable List<String> names()
    {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();

        if (connection == null)
        {
            return null;
        }

        List<String> listed = new ArrayList<>();

        for (PlayerInfo info : connection.getListedOnlinePlayers())
        {
            listed.add(info.getProfile().name());
        }

        if (fake != null)
        {
            listed.addAll(fake);
        }

        String self = mc.getUser().getName();
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String name : listed)
        {
            if (name != null && LISTED.matcher(name).matches() && seen.add(name.toLowerCase(Locale.ROOT)))
            {
                names.add(name);
            }
        }

        // Свой ник — первым: чаще всего себя и вписывают первым
        names.sort((a, b) ->
        {
            boolean aSelf = a.equalsIgnoreCase(self);
            boolean bSelf = b.equalsIgnoreCase(self);

            return aSelf != bSelf ? (aSelf ? -1 : 1) : String.CASE_INSENSITIVE_ORDER.compare(a, b);
        });

        return names;
    }

    /** Свой ник (сравнение без учёта регистра). */
    static boolean self(String nick)
    {
        return nick.equalsIgnoreCase(Minecraft.getInstance().getUser().getName());
    }

    /**
     * Узкий вход для харнесса покрытия (режим {@code k}): в одиночной игре список игроков — один сам
     * игрок, а выбор надо снять с длинным списком. В игре его никто не зовёт.
     */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Добавить к списку игроков выдуманные ники; {@code null} — убрать. */
        public static void fakeOnline(@Nullable List<String> names)
        {
            fake = names == null ? null : List.copyOf(names);
        }
    }
}
