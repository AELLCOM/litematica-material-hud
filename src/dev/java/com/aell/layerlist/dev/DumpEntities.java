package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.SchematicEntities;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Дамп F — сущности: строит образец со стойками, подушками, рамками, картиной,
 * кристаллом, вагонеткой и лодкой, снимает с него схему, кладёт её рядом на такие же
 * опоры и проверяет: (1) список материалов до установки, (2) установку каждой сущности
 * через Easy Place в выживании, (3) совпадение поставленного с образцом, (4) список
 * после, (5) что у повёрнутого размещения позиции, по которым считает список, совпадают
 * с голограммами Litematica. Результат — {@code entities.csv}.
 */
@Environment(EnvType.CLIENT)
public final class DumpEntities implements CovJob
{
    private static final String HEADER = "subject,expected,actual,ok";
    private static final int Y = 100;
    private static final BlockPos REF = new BlockPos(200, Y, 0);
    private static final BlockPos TARGET = new BlockPos(200, Y, 24);
    private static final BlockPos ROTATED = new BlockPos(200, Y, 48);
    private static final int ENTITIES = 14;
    private static final long STABLE_MS = 3000L;
    private static final long PER_GHOST_MS = 7000L;
    /** Свободное место для вставки схемы в мир: дальше размещений REF/TARGET/ROTATED. */
    private static final BlockPos PASTE_POS = new BlockPos(200, Y, 72);

    private enum Phase { BUILD, SNAPSHOT, GHOSTS, LIST_BEFORE, SURVIVAL, PLACE, VERIFY, LIST_AFTER, ROTATE, ROTATE_CHECK, TRANSFER, LIST, DISABLE, PASTE, DONE }

    private CovOut out;
    /** Когда сделано отключение / включение (для вывода при сбое). */
    private long editAt;
    private Phase phase = Phase.BUILD;
    private long until;
    private int ok;
    private int failed;
    private LitematicaSchematic schematic;
    private SchematicPlacement placement;
    private SchematicPlacement rotated;
    private List<Entity> ghosts;
    private int ghostIndex;
    private long ghostDeadline;
    private boolean ghostPositioned;
    private long lastTick = Long.MIN_VALUE;
    private String listSignature = "";
    private long listStableAt;

    @Override
    public String name()
    {
        return "F (сущности)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        try
        {
            // CovOut пропускает уже записанные ключи — этот дамп каждый раз снимается заново
            java.nio.file.Files.deleteIfExists(CovOut.dir().resolve("entities.csv"));
            this.out = CovOut.open("entities.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("entities.csv не открылся: " + e);
            return false;
        }

        LayerListConfigs.HUD_ENABLED = true;
        LayerListConfigs.COUNT_ENTITIES.setBooleanValue(true);
        LayerListConfigs.EP_ENTITIES.setBooleanValue(true);
        LayerListConfigs.EP_ENABLED.setBooleanValue(true);
        LayerListConfigs.ONLY_MISSING.setBooleanValue(false);
        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
        Configs.Generic.EASY_PLACE_POST_REWRITE.setBooleanValue(false);
        LayerListConfigs.EP_DEBUG.setBooleanValue(true);

        // Размещения от прошлых прогонов (дамп D кладёт «магазин») сохраняются в мире стенда
        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            CovOut.log("[entities] убираю чужое размещение «" + old.getName() + "»");
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        server.execute(() -> {
            command(server, "forceload add 192 -16 224 64");
            ServerLevel level = server.overworld();
            build(level, REF);
            build(level, TARGET);
            build(level, ROTATED, true);
            summonReference(server);
            command(server, "tp @a " + (REF.getX() + 4.5D) + " " + Y + " " + (REF.getZ() - 3.5D) + " 0 30");
        });

        this.until = net.minecraft.util.Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = net.minecraft.util.Util.getMillis();

        if (now < this.until || mc.level == null || mc.player == null)
        {
            return false;
        }

        switch (this.phase)
        {
            case BUILD -> this.snapshot(mc);
            case SNAPSHOT, GHOSTS -> this.waitGhosts(mc, now);
            case LIST_BEFORE -> this.listCheck(mc, now, false);
            case SURVIVAL -> this.survival(mc);
            case PLACE -> this.place(mc, now);
            case VERIFY -> this.verify(mc);
            case LIST_AFTER -> this.listCheck(mc, now, true);
            case ROTATE -> this.rotate(mc);
            case ROTATE_CHECK -> this.rotateCheck(mc, now);
            case TRANSFER -> this.transfer(mc, now);
            case LIST -> this.listPhase(mc, now);
            case DISABLE -> this.disablePhase(mc, now);
            case PASTE -> this.pastePhase(mc, now);
            case DONE -> {
                return true;
            }
        }

        return this.phase == Phase.DONE;
    }

    @Override
    public void close()
    {
        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        if (this.rotated != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.rotated);
        }

        if (this.pasted != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.pasted);
        }

        CovOut.log("[entities] итог: OK " + this.ok + ", FAIL " + this.failed);

        if (this.out != null)
        {
            this.out.close();
        }
    }

    private static int countInfos(LitematicaSchematic schematic)
    {
        int count = 0;

        for (String region : schematic.getAreas().keySet())
        {
            List<LitematicaSchematic.EntityInfo> list = schematic.getEntityListForRegion(region);
            count += list == null ? 0 : list.size();
        }

        return count;
    }

    // ===== Фазы =====

    private void snapshot(Minecraft mc)
    {
        int seen = mc.level.getEntities((Entity) null, area(REF), e -> SchematicEntities.kind(e) != null).size();
        this.record("build", "сущностей в образце (клиент)", ENTITIES, seen);
        // Миксин MixinSchematicWorldEntities (require = 0) встал: без него голограмма сущности у двух размещений одной
        // схемы иногда пропадала (гонка потоков перестройки Litematica)
        this.record("build", "мир схемы: изменения сущностей под замком (миксин)", true, java.util.Arrays.stream(
                WorldSchematic.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("layerlist$addFreshSafe")));

        AreaSelection area = new AreaSelection();
        area.setName("dev-entities");
        BlockPos min = REF.below();
        area.addSubRegionBox(new Box(min, REF.offset(8, 3, 8), "main"), false);
        area.setExplicitOrigin(min);

        this.schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

        if (this.schematic == null)
        {
            this.record("build", "схема снята", 1, 0);
            this.phase = Phase.DONE;
            return;
        }

        // Через файл, как у игрока: схема с мира сохраняется и читается обратно — сущности должны пережить и это,
        // а отключение (DISABLE, PASTE) запоминается по файлу схемы
        java.nio.file.Path dir = com.aell.layerlist.schematics.library.SchematicLibrary.get().refreshRoot().resolve("_f")
                .toAbsolutePath().normalize();
        int fromWorld = countInfos(this.schematic);

        try
        {
            java.nio.file.Files.createDirectories(dir);
        }
        catch (java.io.IOException e)
        {
            CovOut.log("[entities] папка для схемы: " + e);
        }

        boolean written = this.schematic.writeToFile(dir, "dev-entities", true);
        LitematicaSchematic read = written ? LitematicaSchematic.createFromFile(dir, "dev-entities.litematic") : null;
        this.record("build", "схема записана в файл и прочитана", true, read != null);

        if (read != null)
        {
            this.record("build", "сущностей в схеме: с мира = из файла", fromWorld, countInfos(read));
            this.schematic = read;
        }

        this.placement = SchematicPlacement.createFor(this.schematic, TARGET.below(), "dev-entities", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
        CovOut.log("[entities] схема снята, размещение в " + TARGET.below());

        this.phase = Phase.GHOSTS;
        this.until = net.minecraft.util.Util.getMillis() + 1000L;
        this.ghostWait = 0L;
        this.ghosts = null;
        this.listStableAt = 0L;
    }

    /** Мир схемы собирается в фоне — ждём всех голограмм по часам, не дольше 20 с. */
    private void waitGhosts(Minecraft mc, long now)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        List<Entity> found = world == null ? List.of() : ghostsIn(world, TARGET);

        if (this.ghostWait == 0L)
        {
            this.ghostWait = now + 20_000L;
        }

        if (found.size() < ENTITIES && now < this.ghostWait)
        {
            this.until = now + 250L;
            return;
        }

        this.record("ghosts", "голограмм на размещении", ENTITIES, found.size());
        this.ghosts = new ArrayList<>(found);
        this.ghostWait = 0L;
        LayerCountManager.getInstance().markDirty();
        this.phase = Phase.LIST_BEFORE;
        this.listSignature = "";
        this.until = now + 500L;
    }

    private long ghostWait;
    private boolean paintingMotiveDiffers;

    /** Ждём, пока список не меняется {@link #STABLE_MS}, и сверяем строки сущностей. */
    private void listCheck(Minecraft mc, long now, boolean after)
    {
        Map<String, int[]> rows = entityRows();
        String signature = rows.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1] + "/" + e.getValue()[2])
                .sorted().reduce("", (a, b) -> a + ";" + b);

        if (! signature.equals(this.listSignature))
        {
            this.listSignature = signature;
            this.listStableAt = now + STABLE_MS;
            this.until = now + 250L;
            return;
        }

        if (now < this.listStableAt)
        {
            this.until = now + 250L;
            return;
        }

        String phase = after ? "list_after" : "list_before";
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(id(Items.ARMOR_STAND), 3);
        expected.put(id(Items.CUSHION.pick(DyeColor.RED)), 1);
        expected.put(id(Items.CUSHION.pick(DyeColor.BLUE)), 1);
        expected.put(id(Items.CUSHION.pick(DyeColor.GREEN)), 1);
        expected.put(id(Items.ITEM_FRAME), 3);
        expected.put(id(Items.GLOW_ITEM_FRAME), 1);
        expected.put(id(Items.DIAMOND), 1);
        expected.put(id(Items.STICK), 1);
        expected.put(id(Items.APPLE), 1);
        expected.put(id(Items.PAINTING), 1);
        expected.put(id(Items.END_CRYSTAL), 1);
        expected.put(id(Items.MINECART), 1);
        expected.put(id(Items.OAK_BOAT), 1);

        for (Map.Entry<String, Integer> entry : expected.entrySet())
        {
            int[] row = rows.getOrDefault(entry.getKey(), new int[]{0, 0, 0});
            boolean boat = entry.getKey().equals(id(Items.OAK_BOAT));
            // Сюжет картины случаен: после установки она честно «не то», если выпал другой
            boolean painting = after && entry.getKey().equals(id(Items.PAINTING)) && this.paintingMotiveDiffers;
            // «Не то» входит в «не хватает», только если не включено «Нехватка — только пустые»
            boolean mismatchIsMissing = ! LayerListConfigs.SHORTFALL_ONLY_MISSING.getBooleanValue();
            int wantMissing = after ? (boat || (painting && mismatchIsMissing) ? 1 : 0) : entry.getValue();
            this.record(phase, entry.getKey() + " всего", entry.getValue(), row[0]);
            this.record(phase, entry.getKey() + " не хватает", wantMissing, row[1]);
            this.record(phase, entry.getKey() + " не то", painting ? 1 : 0, row[2]);
        }

        this.phase = after ? Phase.ROTATE : Phase.SURVIVAL;
        this.until = now + 200L;
    }

    private void survival(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        server.execute(() -> {
            command(server, "gamemode survival @a");
            command(server, "clear @a");

            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                give(player, Items.ARMOR_STAND, 3);
                give(player, Items.CUSHION.pick(DyeColor.RED), 1);
                give(player, Items.CUSHION.pick(DyeColor.BLUE), 1);
                give(player, Items.CUSHION.pick(DyeColor.GREEN), 1);
                give(player, Items.ITEM_FRAME, 3);
                give(player, Items.GLOW_ITEM_FRAME, 1);
                give(player, Items.DIAMOND, 1);
                give(player, Items.STICK, 1);
                give(player, Items.APPLE, 1);
                give(player, Items.PAINTING, 1);
                give(player, Items.END_CRYSTAL, 1);
                give(player, Items.MINECART, 1);
                give(player, Items.OAK_BOAT, 1);
            }
        });

        this.ghosts.sort(Comparator.comparingDouble(e -> e.position().x * 31 + e.position().z));
        this.ghostIndex = 0;
        this.ghostPositioned = false;
        this.phase = Phase.PLACE;
        this.until = net.minecraft.util.Util.getMillis() + 1500L;
    }

    /** По одной голограмме: встать рядом, навести взгляд, жать Easy Place раз в тик, пока не встанет. */
    private void place(Minecraft mc, long now)
    {
        if (this.ghostIndex >= this.ghosts.size())
        {
            this.phase = Phase.VERIFY;
            this.until = now + 1500L;
            return;
        }

        Entity ghost = this.ghosts.get(this.ghostIndex);
        String subject = describe(ghost);

        if (! this.ghostPositioned)
        {
            Vec3 spot = standingSpot(ghost);
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.2f %d %.2f", spot.x, Y, spot.z)));
            this.ghostPositioned = true;
            this.ghostDeadline = now + PER_GHOST_MS;
            this.until = now + 400L;
            return;
        }

        boolean boat = SchematicEntities.kind(ghost) == SchematicEntities.Kind.BOAT;
        SchematicEntities.Match placed = SchematicEntities.match(mc.level, ghost);
        // Картина встала, если на месте картина того же размера: сюжет выбирает игра
        boolean satisfied = placed.satisfied() || (ghost instanceof Painting && placed.real() != null);

        if (satisfied || now > this.ghostDeadline || (boat && now > this.ghostDeadline - PER_GHOST_MS + 2000L))
        {
            if (! satisfied && ! boat)
            {
                SchematicEntities.Match match = SchematicEntities.match(mc.level, ghost);
                CovOut.log("[entities] не встала: " + subject + " — в мире "
                        + (match.real() == null ? "ничего" : describe(match.real())) + ", статусы " + match.statuses());
                WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
                Vec3 eyes = mc.player.getEyePosition(1.0F);
                Vec3 end = eyes.add(mc.player.getViewVector(1.0F).scale(6.0D));

                if (world != null)
                {
                    for (Entity near : world.getEntities((Entity) null, ghost.getBoundingBox().inflate(1.5D), e -> true))
                    {
                        CovOut.log("[entities]   голограмма рядом: " + describe(near) + " removed=" + near.isRemoved()
                                + " box=" + near.getBoundingBox() + " луч=" + near.getBoundingBox().inflate(0.0625D).clip(eyes, end).isPresent());
                    }
                }

                CovOut.log("[entities]   сохранённая голограмма removed=" + ghost.isRemoved() + " box=" + ghost.getBoundingBox()
                        + " луч=" + ghost.getBoundingBox().inflate(0.0625D).clip(eyes, end).isPresent());
            }

            this.record("place", subject, boat ? 0 : 1, satisfied ? 1 : 0);
            ++this.ghostIndex;
            this.ghostPositioned = false;
            this.until = now + 100L;
            return;
        }

        long tick = mc.level.getGameTime();

        if (tick == this.lastTick)
        {
            return;
        }

        this.lastTick = tick;
        look(mc, ghost.getBoundingBox().getCenter());
        WorldUtils.handleEasyPlace(mc);
    }

    /** Поставленное у цели против образца — по клиентскому миру, независимо от сверки мода. */
    private void verify(Minecraft mc)
    {
        BlockPos shift = TARGET.subtract(REF);

        for (Entity want : mc.level.getEntities((Entity) null, area(REF), e -> SchematicEntities.kind(e) != null))
        {
            SchematicEntities.Kind kind = SchematicEntities.kind(want);
            Vec3 at = want.position().add(shift.getX(), shift.getY(), shift.getZ());
            Entity have = null;

            for (Entity entity : mc.level.getEntities((Entity) null, new AABB(at, at).inflate(0.8D), e -> e.getType() == want.getType()))
            {
                if (have == null || entity.position().distanceToSqr(at) < have.position().distanceToSqr(at))
                {
                    have = entity;
                }
            }

            String subject = describe(want);

            if (kind == SchematicEntities.Kind.BOAT)
            {
                this.record("verify", subject + " (лодку не ставим)", "нет", have == null ? "нет" : "есть");
                continue;
            }

            if (want instanceof Painting w && have instanceof Painting h && ! w.getVariant().equals(h.getVariant()))
            {
                this.paintingMotiveDiffers = true;
                CovOut.log("[entities] сюжет картины выпал " + h.getVariant().getRegisteredName() + " (в образце "
                        + w.getVariant().getRegisteredName() + ") — игра выбирает его случайно");
            }

            this.record("verify", subject, "как в образце", have == null ? "нет" : compare(want, have, at));
        }

        this.phase = Phase.LIST_AFTER;
        this.listSignature = "";
        LayerCountManager.getInstance().markDirty();
        this.until = net.minecraft.util.Util.getMillis() + 500L;
    }

    private void rotate(Minecraft mc)
    {
        this.rotated = SchematicPlacement.createFor(this.schematic, ROTATED.below(), "dev-entities-rot", true, true);
        this.rotated.setRotation(Rotation.CLOCKWISE_90, NO_MESSAGES);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.rotated, false);
        this.phase = Phase.ROTATE_CHECK;
        this.ghostWait = net.minecraft.util.Util.getMillis() + 15_000L;
        this.until = net.minecraft.util.Util.getMillis() + 1000L;
    }

    /** Позиции, по которым считает список (данные схемы + поворот), против голограмм Litematica. */
    private void rotateCheck(Minecraft mc, long now)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        AABB box = new AABB(ROTATED.getX() - 10, Y - 2, ROTATED.getZ() - 2, ROTATED.getX() + 10, Y + 6, ROTATED.getZ() + 12);
        int ghostsThere = world == null ? 0 : ghostsIn(world, box).size();

        if (ghostsThere < ENTITIES && now < this.ghostWait)
        {
            this.until = now + 250L;
            return;
        }

        int wanted = 0;
        int matched = 0;

        for (SchematicEntities.Wanted entity : SchematicEntities.collect(mc.level))
        {
            if (! box.contains(entity.pos()))
            {
                continue;
            }

            ++wanted;

            if (SchematicEntities.ghost(world, entity) != null)
            {
                ++matched;
            }
            else
            {
                CovOut.log("[entities] поворот: нет голограммы для " + entity.type() + " у " + entity.pos());
            }
        }

        this.record("rotated", "голограмм у повёрнутого размещения", ENTITIES, ghostsThere);
        this.record("rotated", "сущностей по данным схемы", ENTITIES, wanted);
        this.record("rotated", "из них нашли голограмму", wanted, matched);
        this.phase = Phase.TRANSFER;
        this.until = now + 500L;
    }

    // ===== Перенос, список, «Отключить», вставка =====

    private int sub;
    private long deadline;
    private long stableAt;
    private SchematicPlacement pasted;
    private Map<String, Integer> sampleById = new TreeMap<>();
    private final Map<String, List<LitematicaSchematic.EntityInfo>> listsBefore = new LinkedHashMap<>();
    private CompletableFuture<List<MaterialRow>> future;
    private int standsBefore;
    private int ghostsBefore;

    /** Схема, снятая с мира, держит ровно сущности образца: по числу, по типам и по видам мода. */
    private void transfer(Minecraft mc, long now)
    {
        Map<String, Integer> byId = new TreeMap<>();
        Map<SchematicEntities.Kind, Integer> byKind = new EnumMap<>(SchematicEntities.Kind.class);
        int total = 0;

        for (String region : this.schematic.getAreaSizes().keySet())
        {
            List<LitematicaSchematic.EntityInfo> infos = this.schematic.getEntityListForRegion(region);

            if (infos == null)
            {
                continue;
            }

            for (LitematicaSchematic.EntityInfo info : infos)
            {
                ++total;
                byId.merge(info.nbt().getString("id").replace("minecraft:", ""), 1, Integer::sum);
            }

            for (SchematicEntities.Spec spec : SchematicEntities.specs(infos, mc.level))
            {
                byKind.merge(spec.kind(), 1, Integer::sum);
            }
        }

        // Образец — то, что BUILD реально призвал: считаем по клиентскому миру
        Map<String, Integer> built = new TreeMap<>();
        Map<SchematicEntities.Kind, Integer> builtKind = new EnumMap<>(SchematicEntities.Kind.class);

        for (Entity e : mc.level.getEntities((Entity) null, area(REF), x -> SchematicEntities.kind(x) != null))
        {
            built.merge(typeId(e), 1, Integer::sum);
            builtKind.merge(SchematicEntities.kind(e), 1, Integer::sum);
        }

        this.sampleById = built;
        this.record("transfer", "сущностей в схеме (все регионы)", ENTITIES, total);

        for (Map.Entry<String, Integer> entry : built.entrySet())
        {
            this.record("transfer", "в схеме " + entry.getKey(), entry.getValue(), byId.getOrDefault(entry.getKey(), 0));
        }

        TreeSet<String> extra = new TreeSet<>(byId.keySet());
        extra.removeAll(built.keySet());
        this.record("transfer", "лишних типов в схеме", "[]", extra.toString());
        this.record("transfer", "виды мода по схеме", builtKind.toString(), byKind.toString());

        this.phase = Phase.LIST;
        this.sub = 0;
        this.until = now + 200L;
    }

    /** Строки таблицы материалов по схеме: сущности и содержимое рамок на месте, помечены как сущности. */
    private void listPhase(Minecraft mc, long now)
    {
        if (this.sub == 0)
        {
            this.future = SchematicMaterials.ofLitematica(this.schematic);
            this.deadline = now + 20_000L;
            this.sub = 1;
            this.until = now + 100L;
            return;
        }

        if (! this.future.isDone())
        {
            if (now > this.deadline)
            {
                this.record("list", "таблица материалов посчиталась за 20 с", "да", "нет");
                this.phase = Phase.DISABLE;
                this.sub = 0;
                return;
            }

            this.until = now + 100L;
            return;
        }

        List<MaterialRow> rows;

        try
        {
            rows = this.future.join();
        }
        catch (RuntimeException e)
        {
            this.record("list", "таблица материалов", "строки", "ошибка " + e);
            this.phase = Phase.DISABLE;
            this.sub = 0;
            return;
        }

        Map<String, Long> total = new TreeMap<>();
        Map<String, Boolean> entityOnly = new TreeMap<>();

        for (MaterialRow row : rows)
        {
            String key = id(row.stack().getItem());
            total.merge(key, row.total(), Long::sum);
            entityOnly.merge(key, row.entity(), Boolean::logicalAnd);
        }

        Map<String, Integer> expected = expectedItems();
        long own = 0L;
        long all = 0L;
        Map<String, Integer> contents = Map.of(id(Items.DIAMOND), 1, id(Items.STICK), 1, id(Items.APPLE), 1);

        for (Map.Entry<String, Integer> entry : expected.entrySet())
        {
            long have = total.getOrDefault(entry.getKey(), 0L);
            all += have;

            if (! contents.containsKey(entry.getKey()))
            {
                own += have;
            }

            this.record("list", entry.getKey() + " всего в таблице", entry.getValue(), have);
            this.record("list", entry.getKey() + " строка — сущность", true, entityOnly.getOrDefault(entry.getKey(), false));
        }

        this.record("list", "сущностей без содержимого рамок (сумма строк)", ENTITIES, own);
        this.record("list", "сущностей вместе с содержимым рамок (сумма строк)", ENTITIES + contents.size(), all);

        TreeSet<String> stray = new TreeSet<>();

        for (MaterialRow row : rows)
        {
            if (row.entity() && ! expected.containsKey(id(row.stack().getItem())))
            {
                stray.add(id(row.stack().getItem()));
            }
        }

        this.record("list", "лишних строк сущностей", "[]", stray.toString());
        this.compareWithLitematica(total);
        this.phase = Phase.DISABLE;
        this.sub = 0;
        this.until = now + 200L;
    }

    /** Что должно быть строками: предмет → число. */
    private static Map<String, Integer> expectedItems()
    {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(id(Items.ARMOR_STAND), 3);
        expected.put(id(Items.CUSHION.pick(DyeColor.RED)), 1);
        expected.put(id(Items.CUSHION.pick(DyeColor.BLUE)), 1);
        expected.put(id(Items.CUSHION.pick(DyeColor.GREEN)), 1);
        expected.put(id(Items.ITEM_FRAME), 3);
        expected.put(id(Items.GLOW_ITEM_FRAME), 1);
        expected.put(id(Items.DIAMOND), 1);
        expected.put(id(Items.STICK), 1);
        expected.put(id(Items.APPLE), 1);
        expected.put(id(Items.PAINTING), 1);
        expected.put(id(Items.END_CRYSTAL), 1);
        expected.put(id(Items.MINECART), 1);
        expected.put(id(Items.OAK_BOAT), 1);
        return expected;
    }

    /** Свой счёт Litematica по тем же предметам: расхождения — справка (она считает сущности по-своему), не провал. */
    private void compareWithLitematica(Map<String, Long> ours)
    {
        Map<String, Long> theirs = new TreeMap<>();

        try
        {
            for (MaterialListEntry entry : MaterialListUtils.createMaterialListFor(this.schematic))
            {
                if (! entry.getStack().isEmpty())
                {
                    theirs.merge(id(entry.getStack().getItem()), (long) entry.getCountTotal(), Long::sum);
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log("[entities] INFO Litematica createMaterialListFor не отработал: " + e);
            return;
        }

        for (String key : expectedItems().keySet())
        {
            long a = ours.getOrDefault(key, 0L);
            long b = theirs.getOrDefault(key, 0L);

            CovOut.log("[entities] INFO " + (a == b ? "совпало" : "расхождение с Litematica") + " " + key + ": у мода " + a
                    + ", у Litematica " + b + (a == b ? "" : " (Litematica 0.29.1 считает сущности своим способом: стойка,"
                    + " рамка, картина, вагонетка — по типу, подушки и содержимое рамок может не учитывать)"));
        }
    }

    /** «Отключить» стойки: голограммы уходят, строка тухнет, список HUD пустеет по стойкам, потом всё возвращается. */
    private void disablePhase(Minecraft mc, long now)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();

        switch (this.sub)
        {
            case 0 ->
            {
                this.listsBefore.clear();

                for (String region : this.schematic.getAreaSizes().keySet())
                {
                    List<LitematicaSchematic.EntityInfo> infos = this.schematic.getEntityListForRegion(region);

                    if (infos != null)
                    {
                        this.listsBefore.put(region, infos);
                    }
                }

                this.ghostsBefore = world == null ? 0 : ghostsIn(world, TARGET).size();
                this.standsBefore = rowTotal("armor_stand");
                // Схема размещена дважды — TARGET и повёрнутая ROTATED: HUD считает стойки обоих
                this.record("disable", "стоек в списке HUD до отключения (2 размещения × 3)", 6, this.standsBefore);
                this.record("disable", "голограмм до отключения", ENTITIES, this.ghostsBefore);

                this.editAt = net.minecraft.util.Util.getMillis();
                BlockReplacements.Outcome outcome = BlockReplacements.disable(this.schematic, Items.ARMOR_STAND);
                this.record("disable", "отключение стоек прошло", "ok", outcome.ok() ? "ok" : "ошибка " + outcome.error());
                this.record("disable", "отключены предметы", "[armor_stand]",
                        BlockReplacements.disabledEntityItems(this.schematic).stream().map(DumpEntities::id).toList().toString());
                this.deadline = now + 10_000L;
                this.sub = 1;
                this.until = now + 100L;
            }
            case 1 ->
            {
                List<Entity> ghosts = world == null ? List.of() : ghostsIn(world, TARGET);
                int stands = countStands(ghosts);
                boolean done = stands == 0 && ghosts.size() == this.ghostsBefore - 3;

                if (! done && now < this.deadline)
                {
                    this.until = now + 100L;
                    return;
                }

                if (! done)
                {
                    this.dumpGhosts("disable");
                }

                this.record("disable", "стоек среди голограмм", 0, stands);
                this.record("disable", "остальных голограмм осталось", this.ghostsBefore - 3, ghosts.size() - stands);
                this.checkDisabledRow();
                LayerCountManager.getInstance().markDirty();
                this.deadline = now + 10_000L;
                this.sub = 2;
                this.until = now + 300L;
            }
            case 2 ->
            {
                int have = rowTotal("armor_stand");

                if (have != 0 && now < this.deadline)
                {
                    LayerCountManager.getInstance().markDirty();
                    this.until = now + 300L;
                    return;
                }

                this.record("disable", "стоек в списке HUD после отключения", 0, have);
                this.editAt = net.minecraft.util.Util.getMillis();
                BlockReplacements.Outcome outcome = BlockReplacements.enable(this.schematic, List.of(), List.of(Items.ARMOR_STAND));
                this.record("disable", "включение стоек прошло", "ok", outcome.ok() ? "ok" : "ошибка " + outcome.error());
                this.deadline = now + 10_000L;
                this.sub = 3;
                this.until = now + 100L;
            }
            case 3 ->
            {
                List<Entity> ghosts = world == null ? List.of() : ghostsIn(world, TARGET);

                if (ghosts.size() != this.ghostsBefore && now < this.deadline)
                {
                    this.until = now + 100L;
                    return;
                }

                if (ghosts.size() != this.ghostsBefore)
                {
                    this.dumpGhosts("enable");
                }

                this.record("enable", "голограмм после включения", this.ghostsBefore, ghosts.size());
                this.record("enable", "из них стоек", 3, countStands(ghosts));

                boolean same = true;

                for (Map.Entry<String, List<LitematicaSchematic.EntityInfo>> before : this.listsBefore.entrySet())
                {
                    // По ссылкам: CompoundData.equals malilib смотрит только ключи левой стороны
                    List<LitematicaSchematic.EntityInfo> now2 = this.schematic.getEntityListForRegion(before.getKey());
                    List<LitematicaSchematic.EntityInfo> was = before.getValue();
                    boolean one = now2 != null && now2.size() == was.size();

                    for (int i = 0; one && i < was.size(); ++i)
                    {
                        one = was.get(i) == now2.get(i);
                    }

                    same &= one;
                }

                this.record("enable", "списки сущностей схемы те же, что были (по ссылкам)", true, same);
                this.record("enable", "отключённых предметов", "[]", BlockReplacements.disabledEntityItems(this.schematic).toString());
                this.record("enable", "потухших строк", 0, SchematicMaterials.disabledRows(this.schematic).size());
                LayerCountManager.getInstance().markDirty();
                this.deadline = now + 10_000L;
                this.sub = 4;
                this.until = now + 300L;
            }
            default ->
            {
                int have = rowTotal("armor_stand");

                if (have != this.standsBefore && now < this.deadline)
                {
                    LayerCountManager.getInstance().markDirty();
                    this.until = now + 300L;
                    return;
                }

                this.record("enable", "стоек в списке HUD после включения", this.standsBefore, have);
                this.phase = Phase.PASTE;
                this.sub = 0;
                this.until = now + 200L;
            }
        }
    }

    /** Потухшая строка стоек: предмет в списке включаемых и то же число, что было до отключения. */
    private void checkDisabledRow()
    {
        SchematicMaterials.DisabledRow found = null;

        for (SchematicMaterials.DisabledRow row : SchematicMaterials.disabledRows(this.schematic))
        {
            if (row.row().stack().is(Items.ARMOR_STAND))
            {
                found = row;
            }
        }

        this.record("disable", "потухшая строка armor_stand есть", true, found != null);

        if (found != null)
        {
            this.record("disable", "строка включает armor_stand", true, found.entities().contains(Items.ARMOR_STAND));
            // Потухшая строка — счёт самой схемы (3 стойки), а не размещений
            this.record("disable", "число в потухшей строке (стоек в схеме)", 3L, found.row().total());
        }
    }

    /** Вставка схемы в мир с отключёнными стойками: сущности встали, стоек нет, опоры пришли с блоками. */
    private void pastePhase(Minecraft mc, long now)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos support = PASTE_POS.offset(8, 1, 3);
        BlockPos obsidian = PASTE_POS.offset(6, -1, 6);

        switch (this.sub)
        {
            case 0 ->
            {
                server.execute(() -> {
                    command(server, "forceload add 192 64 224 96");
                    command(server, "gamemode creative @a");
                    clearPaste(server.overworld());
                    command(server, "tp @a " + (PASTE_POS.getX() + 4.5D) + " " + Y + " " + (PASTE_POS.getZ() - 3.5D) + " 0 30");
                });
                this.sub = 1;
                this.until = now + 2000L;
            }
            case 1 ->
            {
                this.pasted = SchematicPlacement.createFor(this.schematic, PASTE_POS.below(), "dev-entities-paste", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.pasted, false);
                this.editAt = net.minecraft.util.Util.getMillis();
                BlockReplacements.Outcome outcome = BlockReplacements.disable(this.schematic, Items.ARMOR_STAND);
                this.record("paste", "стойки отключены перед вставкой", "ok", outcome.ok() ? "ok" : "ошибка " + outcome.error());
                this.sub = 2;
                this.until = now + 1000L;
            }
            case 2 ->
            {
                this.record("paste", "до вставки в боксе пусто", "{}", String.valueOf(serverEntities(server, PASTE_POS)));
                DataManager.getSchematicPlacementManager().pastePlacementToWorld(this.pasted, mc);
                this.deadline = now + 20_000L;
                this.stableAt = 0L;
                this.sub = 3;
                this.until = now + 1000L;
            }
            case 3 ->
            {
                Map<String, Integer> have = serverEntities(server, PASTE_POS);
                Map<String, Integer> want = new TreeMap<>(this.sampleById);
                want.remove("armor_stand");
                boolean match = want.equals(have);

                if (match && this.stableAt == 0L)
                {
                    // Рамки без опоры слетают через тик-другой — держим совпадение 2 с
                    this.stableAt = now + 2000L;
                }
                else if (! match)
                {
                    this.stableAt = 0L;
                }

                if (! (match && now >= this.stableAt) && now < this.deadline)
                {
                    this.until = now + 400L;
                    return;
                }

                for (Map.Entry<String, Integer> entry : this.sampleById.entrySet())
                {
                    int wanted = entry.getKey().equals("armor_stand") ? 0 : entry.getValue();
                    this.record("paste", "в мире " + entry.getKey(), wanted, have == null ? -1 : have.getOrDefault(entry.getKey(), 0));
                }

                this.record("paste", "всего сущностей в боксе", ENTITIES - 3, have == null ? -1 : have.values().stream().mapToInt(Integer::intValue).sum());
                this.record("paste", "опора стены вставилась", "stone", blockAt(server, support));
                this.record("paste", "обсидиан вставился", "obsidian", blockAt(server, obsidian));
                this.sub = 4;
                this.until = now + 100L;
            }
            case 4 ->
            {
                this.editAt = net.minecraft.util.Util.getMillis();
                BlockReplacements.Outcome outcome = BlockReplacements.enable(this.schematic, List.of(), List.of(Items.ARMOR_STAND));
                this.record("paste", "стойки включены обратно", "ok", outcome.ok() ? "ok" : "ошибка " + outcome.error());
                server.execute(() -> {
                    ServerLevel level = server.overworld();

                    for (Entity e : level.getEntities((Entity) null, area(PASTE_POS).inflate(2.0D), x -> ! (x instanceof Player)))
                    {
                        e.discard();
                    }

                    clearPaste(level);
                });
                DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.pasted);
                this.pasted = null;
                this.sub = 5;
                this.until = now + 1500L;
            }
            default ->
            {
                this.record("paste", "после уборки сущностей нет", "{}", String.valueOf(serverEntities(server, PASTE_POS)));
                this.record("paste", "после уборки опора убрана", "air", blockAt(server, support));
                this.record("paste", "отключённых предметов в конце", "[]", BlockReplacements.disabledEntityItems(this.schematic).toString());
                server.execute(() -> command(server, "gamemode survival @a"));
                this.phase = Phase.DONE;
            }
        }
    }

    /** Бокс вставки в воздух, включая пол и обсидиан под ним. */
    private static void clearPaste(ServerLevel level)
    {
        build(level, PASTE_POS, true);

        for (int x = 0; x <= 8; ++x)
        {
            for (int z = 0; z <= 8; ++z)
            {
                level.setBlockAndUpdate(PASTE_POS.offset(x, -1, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Сущности учитываемых видов в боксе по серверному миру: тип → число; {@code null}, если сервер не ответил. */
    private static Map<String, Integer> serverEntities(MinecraftServer server, BlockPos origin)
    {
        try
        {
            return server.submit(() -> {
                Map<String, Integer> out = new TreeMap<>();

                for (Entity e : server.overworld().getEntities((Entity) null, area(origin), x -> SchematicEntities.kind(x) != null))
                {
                    out.merge(typeId(e), 1, Integer::sum);
                }

                return out;
            }).get(3, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            CovOut.log("[entities] серверный мир не ответил: " + e);
            return null;
        }
    }

    private static String blockAt(MinecraftServer server, BlockPos pos)
    {
        try
        {
            return server.submit(() -> BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(pos).getBlock()).getPath())
                    .get(3, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            return "нет ответа";
        }
    }

    private static int countStands(List<Entity> ghosts)
    {
        return (int) ghosts.stream().filter(e -> e instanceof ArmorStand).count();
    }

    /** Общее число предмета в списке HUD (0, если строки нет). */
    private static int rowTotal(String item)
    {
        int[] row = entityRows().get(item);
        return row == null ? 0 : row[0];
    }

    private static String typeId(Entity entity)
    {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    // ===== Мир =====

    private static void build(ServerLevel level, BlockPos origin)
    {
        build(level, origin, false);
    }

    /** Пол 9×9, стена с нишей, плита, ковёр, обсидиан, рельс; {@code rotated} — опоры повёрнуты на 90°. */
    private static void build(ServerLevel level, BlockPos origin, boolean rotated)
    {
        for (int x = -10; x <= 10; ++x)
        {
            for (int z = -2; z <= 12; ++z)
            {
                for (int y = 0; y <= 5; ++y)
                {
                    level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        if (rotated)
        {
            return;
        }

        for (int x = 0; x <= 8; ++x)
        {
            for (int z = 0; z <= 8; ++z)
            {
                level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }

        for (int z = 0; z <= 5; ++z)
        {
            for (int y = 0; y <= 2; ++y)
            {
                level.setBlockAndUpdate(origin.offset(8, y, z), Blocks.STONE.defaultBlockState());
            }
        }

        level.setBlockAndUpdate(origin.offset(8, 0, 7), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(4, 0, 2), Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        level.setBlockAndUpdate(origin.offset(6, 0, 2), Blocks.CARPET.pick(DyeColor.GREEN).defaultBlockState());
        level.setBlockAndUpdate(origin.offset(6, -1, 6), Blocks.OBSIDIAN.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 0, 6), Blocks.RAIL.defaultBlockState());
    }

    private static void summonReference(MinecraftServer server)
    {
        int x = REF.getX();
        int z = REF.getZ();
        summon(server, "armor_stand", x + 0.5, Y, z + 0.5, "{Rotation:[45f,0f]}");
        summon(server, "armor_stand", x + 2.5, Y, z + 0.5, "{Rotation:[180f,0f]}");
        summon(server, "armor_stand", x + 4.5, Y + 0.5, z + 2.5, "{Rotation:[90f,0f]}");
        summon(server, "cushion", x + 0.5, Y, z + 2.5, "{color:\"red\",Rotation:[270f,0f],block_pos:[I;" + x + "," + Y + "," + (z + 2) + "]}");
        summon(server, "cushion", x + 2.5, Y, z + 2.5, "{color:\"blue\",Rotation:[0f,0f],block_pos:[I;" + (x + 2) + "," + Y + "," + (z + 2) + "]}");
        summon(server, "cushion", x + 6.5, Y + 0.0625, z + 2.5, "{color:\"green\",Rotation:[90f,0f],block_pos:[I;" + (x + 6) + "," + Y + "," + (z + 2) + "]}");
        summon(server, "item_frame", x + 7.5, Y + 1.5, z + 1.5, "{Facing:4b,block_pos:[I;" + (x + 7) + "," + (Y + 1) + "," + (z + 1) + "]}");
        summon(server, "item_frame", x + 7.5, Y + 1.5, z + 3.5, "{Facing:4b,block_pos:[I;" + (x + 7) + "," + (Y + 1) + "," + (z + 3)
                + "],Item:{id:\"minecraft:diamond\",count:1},ItemRotation:3b}");
        summon(server, "glow_item_frame", x + 7.5, Y + 2.5, z + 5.5, "{Facing:4b,block_pos:[I;" + (x + 7) + "," + (Y + 2) + "," + (z + 5)
                + "],Item:{id:\"minecraft:stick\",count:1}}");
        summon(server, "item_frame", x + 1.5, Y + 0.5, z + 6.5, "{Facing:1b,block_pos:[I;" + (x + 1) + "," + Y + "," + (z + 6)
                + "],Item:{id:\"minecraft:apple\",count:1},ItemRotation:2b}");
        summon(server, "painting", x + 7.5, Y + 0.5, z + 7.5, "{facing:1b,variant:\"minecraft:kebab\",block_pos:[I;" + (x + 7) + "," + Y + "," + (z + 7) + "]}");
        summon(server, "end_crystal", x + 6.5, Y, z + 6.5, "{ShowBottom:0b}");
        summon(server, "minecart", x + 2.5, Y + 0.0625, z + 6.5, "{}");
        summon(server, "oak_boat", x + 4.5, Y, z + 6.5, "{}");
    }

    private static void summon(MinecraftServer server, String id, double x, double y, double z, String nbt)
    {
        command(server, String.format(Locale.ROOT, "summon minecraft:%s %.4f %.4f %.4f %s", id, x, y, z, nbt));
    }

    private static void give(ServerPlayer player, Item item, int count)
    {
        player.getInventory().add(new ItemStack(item, count));
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("команда не прошла (" + command + "): " + e);
        }
    }

    // ===== Помощники =====

    private static AABB area(BlockPos origin)
    {
        return new AABB(origin.getX() - 0.5D, origin.getY() - 1.5D, origin.getZ() - 0.5D,
                origin.getX() + 9.5D, origin.getY() + 4.5D, origin.getZ() + 9.5D);
    }

    /** При сбое — всё о голограммах обоих размещений и списках схемы (так ловилась гонка потоков Litematica 21.10.2026). */
    private void dumpGhosts(String when)
    {
        WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
        StringBuilder d = new StringBuilder("[entities] DEBUG " + when + " через " + (net.minecraft.util.Util.getMillis() - this.editAt) + " мс;");

        for (BlockPos origin : new BlockPos[] { TARGET, ROTATED })
        {
            d.append(" | ").append(origin == TARGET ? "TARGET" : "ROTATED").append(':');

            if (world != null)
            {
                for (Entity e : world.getEntities((Entity) null, area(origin), x -> true))
                {
                    d.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath())
                            .append('#').append(e.getUUID().toString(), 0, 8)
                            .append('@').append(String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f", e.getX(), e.getY(), e.getZ()))
                            .append(e.isRemoved() ? "(removed)" : "");
                }
            }
        }

        d.append(" | схема:");

        for (String region : this.schematic.getAreas().keySet())
        {
            List<LitematicaSchematic.EntityInfo> list = this.schematic.getEntityListForRegion(region);
            d.append(' ').append(region).append('=').append(list == null ? 0 : list.size()).append(" list@")
                    .append(System.identityHashCode(list));

            if (list != null)
            {
                for (LitematicaSchematic.EntityInfo info : list)
                {
                    d.append(' ').append(info.nbt().getString("id").replace("minecraft:", ""));

                    if (info.nbt().containsLenient("UUID"))
                    {
                        d.append("#uuid");
                    }
                }
            }
        }

        d.append(" | всего сущностей в мире схемы: ").append(world == null ? -1 : countAll(world));
        CovOut.log(d.toString());
    }

    private static String countAll(WorldSchematic world)
    {
        return world.getRegularEntityCount() + " (" + world.getEntityDebug() + ")";
    }

    private static List<Entity> ghostsIn(WorldSchematic world, BlockPos origin)
    {
        return ghostsIn(world, area(origin));
    }

    private static List<Entity> ghostsIn(WorldSchematic world, AABB box)
    {
        List<Entity> out = new ArrayList<>();

        for (Entity entity : world.getEntities((Entity) null, box, e -> SchematicEntities.kind(e) != null))
        {
            // Litematica без deduplicateSchematicEntities держит по две голограммы на сущность
            boolean twin = out.stream().anyMatch(o -> o.getType() == entity.getType()
                    && o.position().distanceToSqr(entity.position()) < 1.0E-4D);

            if (box.contains(entity.position()) && ! twin)
            {
                out.add(entity);
            }
        }

        return out;
    }

    /** Клетка пола рядом с голограммой, где можно встать: не занята сущностями и опорами. */
    private Vec3 standingSpot(Entity ghost)
    {
        Vec3 p = ghost.position();
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;

        for (int x = 0; x <= 7; ++x)
        {
            for (int z = 0; z <= 8; ++z)
            {
                Vec3 cell = new Vec3(TARGET.getX() + x + 0.5D, Y, TARGET.getZ() + z + 0.5D);
                boolean blocked = (x == 4 && z == 2) || (x == 6 && z == 2) || (x == 6 && z == 6) || (x == 2 && z == 6)
                        || (x == 7 && z == 7);

                for (Entity other : this.ghosts)
                {
                    BlockPos at = BlockPos.containing(other.position());

                    if (at.getX() - TARGET.getX() == x && at.getZ() - TARGET.getZ() == z)
                    {
                        blocked = true;
                    }

                    if (other.getBoundingBox().inflate(0.4D).contains(cell.add(0, 0.5, 0)))
                    {
                        blocked = true;
                    }
                }

                if (blocked)
                {
                    continue;
                }

                double dx = cell.x - p.x;
                double dz = cell.z - p.z;
                double distance = Math.sqrt(dx * dx + dz * dz);
                double score = Math.abs(distance - 2.2D);

                if (score < bestScore)
                {
                    best = cell;
                    bestScore = score;
                }
            }
        }

        return best != null ? best : new Vec3(TARGET.getX() + 4.5D, Y, TARGET.getZ() + 4.5D);
    }

    private static void look(Minecraft mc, Vec3 point)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
    }

    private static String compare(Entity want, Entity have, Vec3 at)
    {
        List<String> diff = new ArrayList<>();

        if (have.position().distanceTo(at) > 0.1D)
        {
            diff.add(String.format(Locale.ROOT, "позиция %.2f", have.position().distanceTo(at)));
        }

        if (want instanceof ArmorStand && SchematicEntities.standYaw(want.getYRot()) != SchematicEntities.standYaw(have.getYRot()))
        {
            diff.add("поворот " + want.getYRot() + "→" + have.getYRot());
        }

        if (want instanceof Cushion w && have instanceof Cushion h)
        {
            if (w.getColor() != h.getColor())
            {
                diff.add("цвет " + h.getColor());
            }

            if (Direction.fromYRot(w.getYRot()) != Direction.fromYRot(h.getYRot()))
            {
                diff.add("сторона " + Direction.fromYRot(h.getYRot()));
            }
        }

        if (want instanceof HangingEntity w && have instanceof HangingEntity h && w.getDirection() != h.getDirection())
        {
            diff.add("грань " + h.getDirection());
        }

        if (want instanceof ItemFrame w && have instanceof ItemFrame h)
        {
            if (! ItemStack.isSameItem(w.getItem(), h.getItem()))
            {
                diff.add("в рамке " + h.getItem());
            }

            if (w.getRotation() != h.getRotation())
            {
                diff.add("поворот в рамке " + h.getRotation());
            }
        }

        // Сюжет в выживании выбирает игра — случайно среди одинаковых по размеру; проверяем размер
        if (want instanceof Painting w && have instanceof Painting h
                && (w.getVariant().value().width() != h.getVariant().value().width()
                        || w.getVariant().value().height() != h.getVariant().value().height()))
        {
            diff.add("размер " + h.getVariant().getRegisteredName());
        }

        return diff.isEmpty() ? "как в образце" : String.join("; ", diff);
    }

    private static String describe(Entity entity)
    {
        String extra = "";

        if (entity instanceof Cushion cushion)
        {
            extra = " " + cushion.getColor().getName();
        }
        else if (entity instanceof ItemFrame frame && ! frame.getItem().isEmpty())
        {
            extra = " с " + id(frame.getItem().getItem()) + " r" + frame.getRotation();
        }

        Vec3 p = entity.position();

        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + extra
                + String.format(Locale.ROOT, " @%.1f/%.1f/%.1f", p.x, p.y, p.z);
    }

    /** Строки списка по предмету: всего, не хватает, не то. */
    private static Map<String, int[]> entityRows()
    {
        Map<String, int[]> rows = new LinkedHashMap<>();

        for (MaterialListEntry entry : LayerCountManager.getInstance().getEntries())
        {
            rows.put(id(entry.getStack().getItem()),
                    new int[]{entry.getCountTotal(), entry.getCountMissing(), entry.getCountMismatched()});
        }

        return rows;
    }

    private static String id(Item item)
    {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    private void record(String phase, String subject, Object expected, Object actual)
    {
        boolean pass = String.valueOf(expected).equals(String.valueOf(actual));

        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        CovOut.log("[entities] " + (pass ? "OK  " : "FAIL") + " " + phase + ": " + subject + " — ждали " + expected + ", вышло " + actual);
        this.out.row(List.of(phase + ":" + subject, String.valueOf(expected), String.valueOf(actual), pass ? "1" : "0"));
    }

    private static final IMessageConsumer NO_MESSAGES = new IMessageConsumer()
    {
        @Override
        public void addMessage(Message.MessageType type, String key, Object... args)
        {
        }

        @Override
        public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args)
        {
        }
    };
}
