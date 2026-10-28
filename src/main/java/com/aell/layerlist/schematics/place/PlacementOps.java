package com.aell.layerlist.schematics.place;

import com.aell.layerlist.mixin.schematics.IMixinLitematicaSchematicFile;
import com.aell.layerlist.mixin.schematics.IMixinSchematicPlacementFile;
import com.aell.layerlist.schematics.library.LoadedSchematic;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import fi.dy.masa.malilib.interfaces.IStringConsumer;
import fi.dy.masa.malilib.util.StringUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * Операции с размещениями Litematica (M24): создать перед игроком, перечислить, выбрать,
 * удалить, а для редактора — сдвиг, поворот и отражение с сохранением центра, «к земле».
 * Всё — на клиентском потоке.
 *
 * <p>Порядок «сначала добавить в менеджер, потом менять» обязателен: {@code setOrigin} и
 * соседи сообщают о перестройке через менеджер размещения, и голограмма не добавленного
 * размещения осталась бы висеть в чанках без хозяина.
 */
@Environment(EnvType.CLIENT)
public final class PlacementOps
{
    /** «Разместить здесь»: ближняя грань габарита — на столько блоков перед игроком. */
    static final int FRONT_GAP = 2;

    /** Сколько колонок под габаритом смотрим в «К земле»; больше — с шагом, чтобы не подвиснуть. */
    private static final int GROUND_COLUMNS_MAX = 16_384;

    private PlacementOps() {}

    /** Новое размещение перед игроком (см. {@link #frontCorner}); уже добавлено и выбрано. */
    public static SchematicPlacement placeInFront(LoadedSchematic s)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null)
        {
            throw new IllegalStateException("placeInFront: no world");
        }

        Bounds local = localBounds(s.litematica());
        BlockPos corner = frontCorner(player, local.sizeX(), local.sizeZ());

        return placeAt(s, corner.subtract(new Vec3i(local.minX(), local.minY(), local.minZ())));
    }

    /**
     * Новое размещение с готовым origin (без поворота и отражения); уже добавлено и выбрано.
     * Для «Разместить…» — у курсора или на месте прошлого размещения этой схемы.
     */
    public static SchematicPlacement placeAt(LoadedSchematic s, BlockPos origin)
    {
        LitematicaSchematic schematic = s.litematica();

        // Схема из нашего браузера должна быть видна и в «Загруженных схемах» Litematica, как после
        // её собственной загрузки; дубль того же файла холдер сам не примет
        SchematicHolder.getInstance().addSchematic(schematic, false);

        SchematicPlacement p = SchematicPlacement.createFor(schematic, origin.immutable(), placementName(s), true, true);
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();

        manager.addSchematicPlacement(p, false);
        manager.setSelectedSchematicPlacement(p);

        return p;
    }

    /** Все размещения текущего мира (снимок списка). */
    public static List<SchematicPlacement> all()
    {
        return List.copyOf(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements());
    }

    /** Размещения схемы из этого файла. */
    public static List<SchematicPlacement> of(Path file)
    {
        Path wanted = normalize(file);
        List<SchematicPlacement> out = new ArrayList<>();

        for (SchematicPlacement p : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            Path own = p.getSchematicFile();

            if (own != null && normalize(own).equals(wanted))
            {
                out.add(p);
            }
        }

        return out;
    }

    public static void remove(SchematicPlacement p)
    {
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
    }

    /** Сделать выбранным в Litematica (она при этом сбрасывает свой открытый список материалов). */
    public static void select(SchematicPlacement p)
    {
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(p);
    }

    /**
     * Снять выбор в Litematica: ни одно размещение не выбрано. Её {@code setSelectedSchematicPlacement(null)}
     * это допускает (проверка «есть в списке» — только для не-null) и делает то же, что при выборе:
     * событие выбора слушателям, кеш рамок, {@code DataManager.setMaterialList(null)} — открытый список
     * материалов Litematica закрывается.
     */
    public static void deselect()
    {
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(null);
    }

    /** Выбранное в Litematica размещение; {@code null} — ничего не выбрано. */
    public static @Nullable SchematicPlacement selected()
    {
        return DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
    }

    /** Убранные вместе с файлом размещения и выгруженные схемы — чтобы «Отменить» вернула их как были. */
    public record Detached(List<SchematicPlacement> placements, List<LitematicaSchematic> schematics)
    {
        public boolean isEmpty()
        {
            return this.placements.isEmpty() && this.schematics.isEmpty();
        }
    }

    /**
     * Убрать все размещения схемы из этого файла и выгрузить саму схему из «Загруженных схем»
     * Litematica (как её кнопка «Выгрузить»: {@code SchematicHolder.removeSchematic} убирает и
     * размещения этой схемы). Схема, которую держит размещение другого файла, остаётся.
     */
    public static Detached detach(Path file)
    {
        Path wanted = normalize(file);
        List<SchematicPlacement> placements = of(file);
        List<LitematicaSchematic> schematics = new ArrayList<>();

        for (SchematicPlacement p : placements)
        {
            if (! containsSame(schematics, p.getSchematic()))
            {
                schematics.add(p.getSchematic());
            }
        }

        for (LitematicaSchematic s : List.copyOf(SchematicHolder.getInstance().getAllSchematics()))
        {
            if (s.getFile() != null && normalize(s.getFile()).equals(wanted) && ! containsSame(schematics, s))
            {
                schematics.add(s);
            }
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();

        for (SchematicPlacement p : placements)
        {
            manager.removeSchematicPlacement(p);
        }

        List<LitematicaSchematic> unloaded = new ArrayList<>();

        for (LitematicaSchematic s : schematics)
        {
            if (! usedByAny(manager, s) && SchematicHolder.getInstance().removeSchematic(s))
            {
                unloaded.add(s);
            }
        }

        return new Detached(List.copyOf(placements), List.copyOf(unloaded));
    }

    /** Вернуть убранное {@link #detach}: схемы — в «Загруженные», размещения — в менеджер (без сообщений). */
    public static void reattach(Detached detached)
    {
        for (LitematicaSchematic s : detached.schematics())
        {
            SchematicHolder.getInstance().addSchematic(s, false);
        }

        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();

        for (SchematicPlacement p : detached.placements())
        {
            if (! manager.getAllSchematicsPlacements().contains(p))
            {
                manager.addSchematicPlacement(p, false);
            }
        }
    }

    /**
     * Файл схемы переименовали: размещения этого файла и загруженные из него схемы теперь указывают на новое имя.
     * Те же объекты, ничего не перестраивается; Litematica запишет размещения в мир уже с новым путём, и при
     * следующем входе её {@code SchematicHolder.getOrLoad} найдёт файл. Возвращает, сколько размещений переведено.
     */
    public static int repoint(Path from, Path to)
    {
        Path wanted = normalize(from);
        Path target = normalize(to);
        List<SchematicPlacement> placements = of(from);
        List<LitematicaSchematic> schematics = new ArrayList<>(SchematicHolder.getInstance().getAllSchematics());

        for (SchematicPlacement p : placements)
        {
            ((IMixinSchematicPlacementFile) p).layerlist$setSchematicFile(target);
            schematics.add(p.getSchematic());
        }

        for (LitematicaSchematic s : schematics)
        {
            if (s != null && s.getFile() != null && normalize(s.getFile()).equals(wanted))
            {
                ((IMixinLitematicaSchematicFile) s).layerlist$setSchematicFile(target);
            }
        }

        return placements.size();
    }

    private static boolean containsSame(List<LitematicaSchematic> list, LitematicaSchematic s)
    {
        for (LitematicaSchematic known : list)
        {
            if (known == s)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean usedByAny(SchematicPlacementManager manager, LitematicaSchematic s)
    {
        for (SchematicPlacement p : manager.getAllSchematicsPlacements())
        {
            if (p.getSchematic() == s)
            {
                return true;
            }
        }

        return false;
    }

    static boolean exists(SchematicPlacement p)
    {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(p);
    }

    /**
     * Габарит в мире по всем подобластям, включая выключенные. {@code getEclosingBox} Litematica
     * не годится: он обновляется, только пока у размещения включена её рамка.
     */
    static @Nullable Bounds bounds(SchematicPlacement p)
    {
        return Bounds.enclosing(p.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values());
    }

    /** Не ноль блоков: у Sponge Litematica пишет 0, у старых файлов -1 — тогда считаем объём. */
    static long blockCount(SchematicPlacement p)
    {
        SchematicMetadata meta = p.getSchematic().getMetadata();
        long blocks = meta.getTotalBlocks();

        return blocks > 0 ? blocks : Math.max(0, meta.getTotalVolume());
    }

    /**
     * Мин. угол габарита размером {@code sizeX × sizeZ} перед игроком: ближняя грань — в
     * {@link #FRONT_GAP} блоках по направлению взгляда (по стороне света), поперёк — по центру
     * игрока, низ — на уровне ног. Так габарит целиком лежит впереди при любом взгляде.
     */
    static BlockPos frontCorner(LocalPlayer player, int sizeX, int sizeZ)
    {
        BlockPos feet = player.blockPosition();
        Direction facing = player.getDirection();
        int x = feet.getX();
        int z = feet.getZ();

        return switch (facing)
        {
            case NORTH -> new BlockPos(x - sizeX / 2, feet.getY(), z - FRONT_GAP - (sizeZ - 1));
            case SOUTH -> new BlockPos(x - sizeX / 2, feet.getY(), z + FRONT_GAP);
            case WEST -> new BlockPos(x - FRONT_GAP - (sizeX - 1), feet.getY(), z - sizeZ / 2);
            default -> new BlockPos(x + FRONT_GAP, feet.getY(), z - sizeZ / 2);
        };
    }

    /** Origin, при котором текущий габарит размещения окажется перед игроком. */
    static @Nullable BlockPos originInFront(SchematicPlacement p)
    {
        LocalPlayer player = Minecraft.getInstance().player;
        Bounds b = bounds(p);

        if (player == null || b == null)
        {
            return null;
        }

        BlockPos corner = frontCorner(player, b.sizeX(), b.sizeZ());

        return p.getOrigin().offset(corner.getX() - b.minX(), corner.getY() - b.minY(), corner.getZ() - b.minZ());
    }

    /** Закреплённое размещение не трогаем вовсе: Litematica отказала бы сама, но молча для нас. */
    static boolean refuseLocked(SchematicPlacement p, Feedback feedback)
    {
        if (p.isLocked())
        {
            feedback.error("layerlist.schematics.place.msg.locked");

            return true;
        }

        return false;
    }

    static void moveTo(SchematicPlacement p, BlockPos origin, Feedback feedback)
    {
        if (! refuseLocked(p, feedback))
        {
            p.setOrigin(origin, feedback);
        }
    }

    /** Состояние целиком: поворот и отражение раньше origin — origin считается уже от них. */
    static void apply(SchematicPlacement p, EditHistory.State state, Feedback feedback)
    {
        if (refuseLocked(p, feedback))
        {
            return;
        }

        p.setRotation(state.rotation(), feedback);
        p.setMirror(state.mirror(), feedback);
        p.setOrigin(state.origin(), feedback);
    }

    /**
     * Поворот и/или отражение. С опорой {@code pivot2} (удвоенный центр по X и Z) origin
     * пересчитывается так, чтобы новый габарит встал центром как можно ближе к опоре; без опоры
     * — как у самой Litematica, вокруг origin (угла схемы).
     */
    static void transform(SchematicPlacement p, Rotation rotation, Mirror mirror, @Nullable Pivot pivot,
            Feedback feedback)
    {
        if (refuseLocked(p, feedback))
        {
            return;
        }

        p.setRotation(rotation, feedback);
        p.setMirror(mirror, feedback);

        Bounds after = bounds(p);

        if (pivot == null || after == null)
        {
            return;
        }

        // Удвоенные координаты: центр габарита с чётной стороной лежит между блоками, а целые
        // удвоенные числа дают одну и ту же опору на все четыре поворота — круг R×4 возвращает
        // схему ровно на место
        int dx = Math.floorDiv(pivot.x2() - (after.minX() + after.maxX()), 2);
        int dz = Math.floorDiv(pivot.z2() - (after.minZ() + after.maxZ()), 2);

        if (dx != 0 || dz != 0)
        {
            p.setOrigin(p.getOrigin().offset(dx, 0, dz), feedback);
        }
    }

    /** Следующее отражение по кругу «нет → фронт-тыл → лево-право». */
    static Mirror nextMirror(Mirror mirror)
    {
        return switch (mirror)
        {
            case NONE -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.LEFT_RIGHT;
            default -> Mirror.NONE;
        };
    }

    /**
     * Высота земли под габаритом: верхний твёрдый блок каждой загруженной колонки, затем самый
     * высокий ({@code highest}) или самый низкий из них. Возвращает Y, на который встаёт низ
     * габарита, или {@code null}, если под ним нет ни одной загруженной колонки.
     *
     * <p>Без потолка у измерения старт колонки — карта высот клиента (без листвы), дальше вниз
     * сквозь воду и траву до твёрдого: «к земле» значит «на поверхность», даже если габарит сейчас
     * в толще холма. С потолком (Незер) карта высот упирается в бедрок, поэтому ищем первую
     * твёрдую поверхность вниз от верха габарита.
     */
    static @Nullable Integer groundY(Bounds b, boolean highest)
    {
        ClientLevel level = Minecraft.getInstance().level;

        if (level == null)
        {
            return null;
        }

        long columns = (long) b.sizeX() * b.sizeZ();
        int stride = 1;

        while (columns / ((long) stride * stride) > GROUND_COLUMNS_MAX)
        {
            ++stride;
        }

        boolean ceiling = level.dimensionType().hasCeiling();
        int minY = level.getMinY();
        int top = Math.min(level.getMaxY(), b.maxY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Integer result = null;

        for (int x = b.minX(); x <= b.maxX(); x += stride)
        {
            for (int z = b.minZ(); z <= b.maxZ(); z += stride)
            {
                LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);

                if (chunk == null)
                {
                    continue;
                }

                int start = ceiling ? top : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int surface = surfaceBelow(chunk, pos, x, z, start, minY);

                if (surface < minY)
                {
                    continue;
                }

                int bottom = surface + 1;

                if (result == null || (highest ? bottom > result : bottom < result))
                {
                    result = bottom;
                }
            }
        }

        return result;
    }

    /**
     * Первый твёрдый блок, над которым не твёрдый, вниз от {@code start}; ниже мира —
     * {@code minY - 1}. Если старт внутри породы, сначала проходим её насквозь: поверхность — это
     * твёрдое под воздухом, а не любой твёрдый блок.
     */
    private static int surfaceBelow(LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int z, int start, int minY)
    {
        boolean aboveSolid = solid(chunk, pos.set(x, start + 1, z));

        for (int y = start; y >= minY; --y)
        {
            boolean solid = solid(chunk, pos.set(x, y, z));

            if (solid && ! aboveSolid)
            {
                return y;
            }

            aboveSolid = solid;
        }

        return minY - 1;
    }

    private static boolean solid(LevelChunk chunk, BlockPos pos)
    {
        BlockState state = chunk.getBlockState(pos);

        return ! state.isAir()
                && ! state.is(BlockTags.LEAVES)
                && ! state.getCollisionShape(chunk, pos).isEmpty();
    }

    private static Bounds localBounds(LitematicaSchematic schematic)
    {
        Bounds b = Bounds.enclosing(schematic.getAreas().values());

        if (b != null)
        {
            return b;
        }

        Vec3i size = schematic.getTotalSize();

        return new Bounds(0, 0, 0, Math.max(1, size.getX()) - 1, Math.max(1, size.getY()) - 1,
                Math.max(1, size.getZ()) - 1);
    }

    private static String placementName(LoadedSchematic s)
    {
        String name = s.litematica().getMetadata().getName();

        return name == null || name.isBlank() ? s.entry().name() : name;
    }

    private static Path normalize(Path path)
    {
        return path.toAbsolutePath().normalize();
    }

    /** Габарит по блокам, обе границы включительно. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        static @Nullable Bounds enclosing(Collection<Box> boxes)
        {
            Pair<BlockPos, BlockPos> corners = PositionUtils.getEnclosingAreaCorners(boxes);

            if (corners == null)
            {
                return null;
            }

            BlockPos min = corners.getLeft();
            BlockPos max = corners.getRight();

            return new Bounds(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        }

        public int sizeX()
        {
            return this.maxX - this.minX + 1;
        }

        public int sizeY()
        {
            return this.maxY - this.minY + 1;
        }

        public int sizeZ()
        {
            return this.maxZ - this.minZ + 1;
        }

        public Bounds offset(int dx, int dy, int dz)
        {
            return new Bounds(this.minX + dx, this.minY + dy, this.minZ + dz,
                    this.maxX + dx, this.maxY + dy, this.maxZ + dz);
        }

        Pivot pivot()
        {
            return new Pivot(this.minX + this.maxX, this.minZ + this.maxZ);
        }

        /** Углов у габарита — ручек подъёма в редакторе. */
        public static final int CORNERS = 8;

        /**
         * Угол габарита как точка мира (вершина, а не блок: правая граница — {@code max + 1}); биты
         * индекса — «дальний» по X (1), по Y (2), по Z (4).
         */
        public Vec3 corner(int index)
        {
            return new Vec3((index & 1) == 0 ? this.minX : this.maxX + 1.0, (index & 2) == 0 ? this.minY : this.maxY + 1.0,
                    (index & 4) == 0 ? this.minZ : this.maxZ + 1.0);
        }

        /** Центр габарита (точка мира). */
        public Vec3 center()
        {
            return new Vec3((this.minX + this.maxX + 1) * 0.5, (this.minY + this.maxY + 1) * 0.5,
                    (this.minZ + this.maxZ + 1) * 0.5);
        }
    }

    /** Опора поворота: удвоенный центр габарита по X и Z. */
    record Pivot(int x2, int z2) {}

    /**
     * Свой приёмник сообщений Litematica для {@code setOrigin}/{@code setRotation}/{@code setMirror}:
     * у {@code IMessageConsumer} два абстрактных метода, а {@code null} Litematica разыменует на
     * закреплённом размещении. Хранит последнее сообщение — редактор показывает его в панели.
     */
    public static final class Feedback implements IStringConsumer, IMessageConsumer
    {
        private @Nullable String text;
        private boolean error;
        private long at;

        @Override
        public void setString(String key)
        {
            this.put(StringUtils.translate(key), false);
        }

        @Override
        public void addMessage(Message.MessageType type, String key, Object... args)
        {
            this.put(StringUtils.translate(key, args), type == Message.MessageType.ERROR
                    || type == Message.MessageType.WARNING);
        }

        @Override
        public void addMessage(Message.MessageType type, int lifeTime, String key, Object... args)
        {
            this.addMessage(type, key, args);
        }

        void error(String key, Object... args)
        {
            this.put(StringUtils.translate(key, args), true);
        }

        void info(String key, Object... args)
        {
            this.put(StringUtils.translate(key, args), false);
        }

        private void put(String message, boolean isError)
        {
            this.text = message;
            this.error = isError;
            this.at = Util.getMillis();
        }

        /** Сообщение, если оно моложе {@code maxAgeMs}. */
        @Nullable String recent(long maxAgeMs)
        {
            return this.text != null && Util.getMillis() - this.at <= maxAgeMs ? this.text : null;
        }

        boolean isError()
        {
            return this.error;
        }
    }
}
