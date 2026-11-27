package com.aell.layerlist.party;

import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.ItemKeys;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Все Party игрока и их связь со схемами: хранение ({@code config/layerlist_party.json}), основа делёжа каждой
 * схемы и то, чью долю показывать в таблице «Материалы» и в списке HUD.
 *
 * <p>Сервера нет: каждый участник считает доли сам, из своей копии той же схемы. Основа делёжа — вся схема без
 * «Считать до Y» и с сущностями ({@link SchematicMaterials#ofLitematicaWhole}): от настроек счёта она не зависит,
 * поэтому у всех одна. Party находит свою схему по отпечатку основы; не сошёлся (другая версия файла, свои замены
 * блоков) — схему можно привязать вручную, а окно Party предупредит, что доли могут разойтись.
 *
 * <p>Только клиентский поток. Пока Party нет ни одной, ничего не считается.
 */
@Environment(EnvType.CLIENT)
public final class Parties
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/party");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "layerlist_party.json";
    /** Больше стольких Party не храним: самые старые уходят. */
    private static final int KEEP = 24;
    private static final String ID_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<Party> PARTIES = new ArrayList<>();
    private static boolean loaded;
    /** Растёт при любой перемене, от которой зависят доли: состав, вид, готовая основа схемы. */
    private static long revision;
    /** Основы делёжа по схемам (слабо: выгрузили схему — ушла и основа). */
    private static final Map<LitematicaSchematic, Slot> BASES = new WeakHashMap<>();
    /**
     * Какая Party у схемы в этой игре (слабо): раз найденная связь держится и после замены блоков, когда список
     * схемы — а с ним и отпечаток — уже другой. Между запусками её заменяют файл и отпечаток привязки.
     */
    private static final Map<LitematicaSchematic, String> SEEN = new WeakHashMap<>();
    /** Свой ник харнесса вместо ника сессии ({@link TestHooks}); в игре всегда {@code null}. */
    private static @Nullable String fakeSelf;

    private Parties() {}

    /**
     * Основа делёжа схемы: её полный список и отпечаток.
     */
    public static final class Base
    {
        private final List<PartySplit.Item> items;
        private final String fingerprint;
        private final Map<Integer, PartySplit> splits = new HashMap<>();

        private Base(List<PartySplit.Item> items)
        {
            this.items = List.copyOf(items);
            this.fingerprint = PartySplit.fingerprint(items);
        }

        public String fingerprint()
        {
            return this.fingerprint;
        }

        /** Позиций в списке схемы. */
        public int positions()
        {
            return this.items.size();
        }

        /** Делёж на {@code members} участников (считается один раз на число). */
        public PartySplit split(int members)
        {
            return this.splits.computeIfAbsent(Math.max(1, members), n -> PartySplit.of(this.items, n));
        }
    }

    /** Основа схемы и её счёт: готовая остаётся, пока считается новая (после замены блоков). */
    private static final class Slot
    {
        private @Nullable Base ready;
        private int generation = Integer.MIN_VALUE;
        private boolean pending;
        private boolean failed;
    }

    /**
     * Party схемы — как есть, не глядя на то, чью долю показывать (окну Party).
     *
     * @param mismatch список этой схемы не совпал со списком ведущего: Party привязана к файлу вручную
     */
    public record Bound(Party party, Base base, boolean mismatch)
    {
        public PartySplit split()
        {
            return this.base.split(this.party.roster().size());
        }
    }

    /**
     * Доля, которую сейчас надо показывать вместо полного списка.
     *
     * @param member чья доля, номером с нуля
     */
    public record Applied(Party party, PartySplit split, int member, boolean mismatch)
    {
        /** Строки таблицы — долей: «Всего» и «не хватает» участника; чужие предметы уходят. */
        public List<MaterialRow> rows(List<MaterialRow> rows)
        {
            List<MaterialRow> out = new ArrayList<>(rows.size());

            for (MaterialRow row : rows)
            {
                if (! this.split.knows(row.key()))
                {
                    out.add(row);
                    continue;
                }

                long total = this.split.part(row.key(), row.total(), this.member);

                if (total <= 0L)
                {
                    continue;
                }

                out.add(new MaterialRow(row.stack(), row.key(), row.name(), total,
                        this.split.part(row.key(), row.missing(), this.member), row.inInventory(), row.inChests(),
                        row.maxStack(), row.entity()));
            }

            return out;
        }

        /** То же для строк списка HUD. Новый изменяемый список. */
        public List<MaterialListEntry> entries(List<MaterialListEntry> entries)
        {
            List<MaterialListEntry> out = new ArrayList<>(entries.size());

            for (MaterialListEntry entry : entries)
            {
                String key = ItemKeys.of(entry.getStack());

                if (! this.split.knows(key))
                {
                    out.add(entry);
                    continue;
                }

                long total = this.split.part(key, entry.getCountTotal(), this.member);

                if (total <= 0L)
                {
                    continue;
                }

                out.add(new MaterialListEntry(entry.getStack(), (int) Math.min(Integer.MAX_VALUE, total),
                        (int) Math.min(Integer.MAX_VALUE, this.split.part(key, entry.getCountMissing(), this.member)),
                        (int) Math.min(Integer.MAX_VALUE, this.split.part(key, entry.getCountMismatched(), this.member)),
                        entry.getCountAvailable()));
            }

            return out;
        }

        /** Номер показанной доли для людей: с единицы. */
        public int number()
        {
            return this.member + 1;
        }

        /** Ник показанной доли. */
        public String nick()
        {
            return this.party.roster().get(this.member);
        }
    }

    // ===== Party =====

    /** Все Party, свежие первыми. Копия. */
    public static List<Party> all()
    {
        load();

        return List.copyOf(PARTIES);
    }

    public static @Nullable Party byId(String id)
    {
        load();

        for (Party party : PARTIES)
        {
            if (party.id().equals(id))
            {
                return party;
            }
        }

        return null;
    }

    /**
     * Записать Party: новая встаёт первой, с тем же {@code id} — заменяет прежнюю. У одной схемы — одна Party:
     * прежние с тем же отпечатком или тем же файлом уходят.
     */
    public static void put(Party party)
    {
        load();
        PARTIES.removeIf(other -> other.id().equals(party.id())
                || (! party.fingerprint().isEmpty() && other.fingerprint().equals(party.fingerprint()))
                || (! party.local().isEmpty() && (other.local().equals(party.local()) || other.fingerprint().equals(party.local())))
                || (! party.file().isEmpty() && other.file().equals(party.file())));
        PARTIES.addFirst(party);

        while (PARTIES.size() > KEEP)
        {
            PARTIES.removeLast();
        }

        save();
        changed();
    }

    /** Сменить название: доли от него не зависят — список HUD не пересчитывается. */
    public static void rename(String id, String name)
    {
        load();

        for (int i = 0; i < PARTIES.size(); ++i)
        {
            Party party = PARTIES.get(i);

            if (party.id().equals(id) && ! party.name().equals(name))
            {
                PARTIES.set(i, party.withName(name));
                save();
                return;
            }
        }
    }

    public static void remove(String id)
    {
        load();

        if (PARTIES.removeIf(party -> party.id().equals(id)))
        {
            save();
            changed();
        }
    }

    /** Новый идентификатор Party: шесть знаков, которых хватает, чтобы не совпасть у одной компании. */
    public static String newId()
    {
        StringBuilder id = new StringBuilder(6);

        for (int i = 0; i < 6; ++i)
        {
            id.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }

        return id.toString();
    }

    /** Свой ник — по нему находится своя доля. */
    public static String self()
    {
        return fakeSelf != null ? fakeSelf : Minecraft.getInstance().getUser().getName();
    }

    /** Счётчик перемен: сменился — доли в таблице и HUD надо показать заново. */
    public static long revision()
    {
        return revision;
    }

    private static void changed()
    {
        ++revision;
        // Список HUD публикуется уже долей — пересчитать его с новым составом
        LayerCountManager.getInstance().markDirty();
    }

    // ===== Основа схемы =====

    /**
     * Основа делёжа схемы; {@code null} — ещё считается (или не посчиталась: нет мира). Первый вызов запускает
     * счёт, готовность видна по {@link #revision()}.
     */
    public static @Nullable Base base(@Nullable LitematicaSchematic schematic)
    {
        if (schematic == null)
        {
            return null;
        }

        Slot slot = BASES.computeIfAbsent(schematic, key -> new Slot());
        int generation = BlockReplacements.generation(schematic);

        if (! slot.pending && (slot.generation != generation || (slot.ready == null && ! slot.failed)))
        {
            slot.pending = true;
            slot.failed = false;
            slot.generation = generation;

            SchematicMaterials.ofLitematicaWhole(schematic).whenComplete((rows, error) ->
                    Minecraft.getInstance().execute(() -> finish(slot, rows, error)));
        }

        return slot.ready;
    }

    private static void finish(Slot slot, @Nullable List<MaterialRow> rows, @Nullable Throwable error)
    {
        slot.pending = false;

        if (error != null || rows == null)
        {
            // Нет мира или Litematica не отдала схему: попробуем при следующей смене схемы, а не каждый кадр
            slot.failed = true;
            return;
        }

        List<PartySplit.Item> items = new ArrayList<>(rows.size());

        for (MaterialRow row : rows)
        {
            items.add(new PartySplit.Item(row.key(), row.total(), row.maxStack()));
        }

        slot.ready = new Base(items);
        changed();
    }

    /** Основа схемы не посчиталась (нет мира) — окну Party есть что сказать вместо вечного «считаю». */
    public static boolean baseFailed(@Nullable LitematicaSchematic schematic)
    {
        Slot slot = schematic == null ? null : BASES.get(schematic);

        return slot != null && slot.failed && slot.ready == null;
    }

    /** Основа схемы сейчас считается. */
    public static boolean basePending(@Nullable LitematicaSchematic schematic)
    {
        Slot slot = schematic == null ? null : BASES.get(schematic);

        return slot != null && slot.pending;
    }

    /** Забыть неудачу счёта основы: мир появился — пробуем снова. */
    public static void retry(@Nullable LitematicaSchematic schematic)
    {
        Slot slot = schematic == null ? null : BASES.get(schematic);

        if (slot != null && slot.failed)
        {
            slot.failed = false;
        }
    }

    // ===== Связь со схемой =====

    /**
     * Party этой схемы. Сначала — по отпечатку списка (он же у ведущего); не сошёлся — та, что уже была у этой схемы
     * в этой игре, потом привязанная вручную (по отпечатку своего списка, по файлу): список у ведущего другой,
     * {@link Bound#mismatch}. {@code null} — Party нет или основа ещё считается.
     */
    public static @Nullable Bound bound(@Nullable LitematicaSchematic schematic)
    {
        load();

        if (schematic == null || PARTIES.isEmpty())
        {
            return null;
        }

        Base base = base(schematic);

        if (base == null)
        {
            return null;
        }

        for (Party party : PARTIES)
        {
            if (party.fingerprint().equals(base.fingerprint()))
            {
                SEEN.put(schematic, party.id());
                return new Bound(party, base, false);
            }
        }

        String seen = SEEN.get(schematic);
        String file = fileOf(schematic);

        for (Party party : PARTIES)
        {
            if (party.id().equals(seen) || party.local().equals(base.fingerprint())
                    || (! file.isEmpty() && party.file().equals(file)))
            {
                SEEN.put(schematic, party.id());
                return new Bound(party, base, true);
            }
        }

        SEEN.remove(schematic);

        return null;
    }

    /** Привязать Party к этой схеме вручную: список у ведущего другой, но игрок говорит, что схема та самая. */
    public static void bind(Party party, LitematicaSchematic schematic)
    {
        Base base = base(schematic);
        SEEN.put(schematic, party.id());
        put(party.withBinding(fileOf(schematic), base == null ? "" : base.fingerprint()));
    }

    /** Доля, которую надо показать вместо списка этой схемы; {@code null} — показывать весь список. */
    public static @Nullable Applied applied(@Nullable LitematicaSchematic schematic)
    {
        Bound bound = bound(schematic);

        if (bound == null)
        {
            return null;
        }

        int member = bound.party().shownMember(self());

        return member < 0 ? null : new Applied(bound.party(), bound.split(), member, bound.mismatch());
    }

    /**
     * Доля для списка HUD: среди включённых размещений ровно одна Party — её. Две разные — список общий
     * (строки размещений в HUD слиты, по схемам их не разнять).
     */
    public static HudShare hud()
    {
        load();

        if (PARTIES.isEmpty())
        {
            return HudShare.NONE;
        }

        Map<LitematicaSchematic, Boolean> seen = new IdentityHashMap<>();
        Applied found = null;

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                LitematicaSchematic schematic = placement.getSchematic();

                if (! placement.isEnabled() || schematic == null || seen.put(schematic, Boolean.TRUE) != null)
                {
                    continue;
                }

                Applied applied = applied(schematic);

                if (applied == null)
                {
                    continue;
                }

                if (found != null && ! found.party().id().equals(applied.party().id()))
                {
                    return new HudShare(null, true);
                }

                found = applied;
            }
        }
        catch (RuntimeException e)
        {
            // Размещения не прочитались — список HUD остаётся общим
            return HudShare.NONE;
        }

        return found == null ? HudShare.NONE : new HudShare(found, false);
    }

    /**
     * Что делать со списком HUD.
     *
     * @param applied чья доля; {@code null} — список общий
     * @param several включено несколько схем с разными Party — поэтому список общий
     */
    public record HudShare(@Nullable Applied applied, boolean several)
    {
        static final HudShare NONE = new HudShare(null, false);
    }

    /** Файл схемы строкой для привязки; схема из памяти — пусто. */
    public static String fileOf(@Nullable LitematicaSchematic schematic)
    {
        Path file = schematic == null ? null : schematic.getFile();

        return file == null ? "" : file.toAbsolutePath().normalize().toString();
    }

    /** Party, принятые по приглашению, которые ещё не нашли свою схему: их можно привязать вручную. */
    public static List<Party> unbound()
    {
        load();
        List<String> matched = new ArrayList<>();
        List<String> files = new ArrayList<>();

        try
        {
            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                LitematicaSchematic schematic = placement.getSchematic();
                Slot slot = schematic == null ? null : BASES.get(schematic);

                if (slot != null && slot.ready != null)
                {
                    matched.add(slot.ready.fingerprint());
                }

                files.add(fileOf(schematic));
            }
        }
        catch (RuntimeException e)
        {
            // Размещения не прочитались — считаем, что схем нет
        }

        List<Party> out = new ArrayList<>();

        for (Party party : PARTIES)
        {
            if (! matched.contains(party.fingerprint()) && (party.local().isEmpty() || ! matched.contains(party.local()))
                    && (party.file().isEmpty() || ! files.contains(party.file())))
            {
                out.add(party);
            }
        }

        out.sort(Comparator.comparingLong(Party::time).reversed());

        return out;
    }

    // ===== Приглашение =====

    /** Чем кончилось принятие приглашения. */
    public enum Joined
    {
        /** Вступил: Party новая. */
        NEW,
        /** Уже участвовал: состав обновлён. */
        UPDATED,
        /** Своего ника в составе нет. */
        NOT_LISTED
    }

    /** Принять приглашение из чата: Party появляется у игрока, доля — по его нику. */
    public static Joined join(PartyCode.Invite invite)
    {
        load();
        Party draft = new Party(invite.id(), invite.name(), invite.roster(), invite.fingerprint(), false, Party.VIEW_MINE, "",
                "", Util.getMillis());

        if (draft.indexOf(self()) < 0)
        {
            return Joined.NOT_LISTED;
        }

        Party before = byId(invite.id());

        if (before == null)
        {
            put(draft);
            return Joined.NEW;
        }

        // Своя Party, пришедшая обратно из чата, остаётся своей; вид и привязка к файлу — как были
        put(new Party(before.id(), invite.name(), invite.roster(), invite.fingerprint(), before.own(),
                before.view() >= invite.roster().size() ? Party.VIEW_MINE : before.view(), before.file(), before.local(),
                before.time()));

        return Joined.UPDATED;
    }

    /** Приглашение уже принято в точности таким: тот же состав, отпечаток и название. */
    public static boolean joined(PartyCode.Invite invite)
    {
        Party party = byId(invite.id());

        return party != null && party.roster().equals(invite.roster()) && party.fingerprint().equals(invite.fingerprint());
    }

    // ===== Файл =====

    private static Path path()
    {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static void load()
    {
        if (loaded)
        {
            return;
        }

        loaded = true;
        PARTIES.clear();
        Path path = path();

        if (! Files.isRegularFile(path))
        {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path))
        {
            JsonElement root = JsonParser.parseReader(reader);

            if (! (root instanceof JsonObject object) || ! (object.get("parties") instanceof JsonArray array))
            {
                return;
            }

            for (JsonElement element : array)
            {
                Party party = element instanceof JsonObject one ? read(one) : null;

                if (party != null && PARTIES.size() < KEEP)
                {
                    PARTIES.add(party);
                }
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not read party list {}", path, e);
        }
    }

    // Битая запись не мешает остальным: пропускается
    private static @Nullable Party read(JsonObject object)
    {
        String id = string(object, "id");
        List<String> roster = new ArrayList<>();

        if (object.get("roster") instanceof JsonArray array)
        {
            for (JsonElement element : array)
            {
                if (element instanceof JsonPrimitive primitive && primitive.isString() && ! primitive.getAsString().isBlank()
                        && roster.size() < PartySplit.MAX_MEMBERS)
                {
                    roster.add(primitive.getAsString());
                }
            }
        }

        if (id.isEmpty())
        {
            return null;
        }

        int view = object.get("view") instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsInt()
                : Party.VIEW_MINE;
        long time = object.get("time") instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsLong() : 0L;
        boolean own = object.get("own") instanceof JsonPrimitive primitive && primitive.isBoolean() && primitive.getAsBoolean();

        return new Party(id, string(object, "name"), roster, string(object, "fingerprint"), own,
                view < Party.VIEW_ALL || view >= roster.size() ? Party.VIEW_MINE : view, string(object, "file"),
                string(object, "local"), time);
    }

    private static String string(JsonObject object, String key)
    {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : "";
    }

    private static void save()
    {
        JsonArray array = new JsonArray();

        for (Party party : PARTIES)
        {
            JsonObject one = new JsonObject();
            one.addProperty("id", party.id());
            one.addProperty("name", party.name());
            JsonArray roster = new JsonArray();
            party.roster().forEach(roster::add);
            one.add("roster", roster);
            one.addProperty("fingerprint", party.fingerprint());
            one.addProperty("own", party.own());
            one.addProperty("view", party.view());
            one.addProperty("file", party.file());
            one.addProperty("local", party.local());
            one.addProperty("time", party.time());
            array.add(one);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("parties", array);

        Path path = path();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(path.getParent());

            try (BufferedWriter writer = Files.newBufferedWriter(tmp))
            {
                GSON.toJson(root, writer);
            }

            try
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("Could not save party list {}", path, e);

            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (Exception ignored)
            {
                // Остаток .tmp перезапишется при следующем сохранении
            }
        }
    }

    /** Только для харнесса: чужой ник вместо своего и чистый список Party. В игре никто не зовёт. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** Считать своим ником {@code nick}; {@code null} — снова ник сессии. */
        public static void self(@Nullable String nick)
        {
            fakeSelf = nick;
            changed();
        }

        /** Убрать все Party (и из файла). */
        public static void clear()
        {
            load();
            PARTIES.clear();
            SEEN.clear();
            save();
            changed();
        }
    }
}
