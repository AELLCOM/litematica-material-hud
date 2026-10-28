package com.aell.layerlist.core;

import com.aell.layerlist.easyplace.ItemData;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartSpawner;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Сущности схемы, которые ставятся предметом: стойки для брони, подушки (26.3), рамки,
 * картины, кристаллы края, вагонетки и лодки.
 *
 * <p>Litematica 0.29.1 считает сущности в своём списке материалов, но только в
 * загруженных чанках мира схемы (итог плавает, пока ходишь), сверяет их с миром лишь
 * по типу и точной позиции — подушка другого цвета или рамка с чужим предметом у неё
 * «на месте», — и мобов считает яйцами призыва. Здесь итог берётся прямо из данных
 * схемы, как у блоков, а сверка — с голограммой из мира схемы, где у сущности уже
 * точные поворот, цвет и содержимое.
 */
@Environment(EnvType.CLIENT)
public final class SchematicEntities
{
    private SchematicEntities() {}

    /** Какие сущности учитываем. Мобы, узлы поводка и прочее предметом не ставятся. */
    public enum Kind
    {
        ARMOR_STAND, CUSHION, ITEM_FRAME, PAINTING, END_CRYSTAL, MINECART, BOAT
    }

    /** Что за сущность нужно принести: по предмету на строку списка. У рамки — сама рамка и её содержимое. */
    public enum Status
    {
        OK, MISSING, MISMATCH
    }

    /** Сущность схемы в мировых координатах. */
    public record Wanted(Kind kind, EntityType<?> type, Vec3 pos, List<ItemStack> items)
    {
        public BlockPos blockPos()
        {
            return BlockPos.containing(this.pos);
        }
    }

    /** Итог сверки одной сущности схемы с миром: найденная сущность и статус каждого предмета. */
    public record Match(@Nullable Entity real, List<Status> statuses)
    {
        public boolean satisfied()
        {
            return this.statuses.stream().allMatch(status -> status == Status.OK);
        }
    }

    /**
     * Разобранная сущность схемы в координатах самой схемы — чтобы не создавать её на каждом пересчёте.
     * Открыта для таблицы материалов файла схемы (вкладка «Схемы»): там размещения нет, а правила те же.
     */
    public record Spec(Kind kind, EntityType<?> type, Vec3 posVec, List<ItemStack> items) {}

    /** Ключ — список сущностей региона в схеме: он живёт, пока схема загружена. */
    private static final Map<List<LitematicaSchematic.EntityInfo>, List<Spec>> SPECS = new IdentityHashMap<>();

    public static @Nullable Kind kind(Entity entity)
    {
        if (entity instanceof ArmorStand)
        {
            return Kind.ARMOR_STAND;
        }

        if (entity instanceof Cushion)
        {
            return Kind.CUSHION;
        }

        if (entity instanceof ItemFrame)
        {
            return Kind.ITEM_FRAME;
        }

        if (entity instanceof Painting)
        {
            return Kind.PAINTING;
        }

        if (entity instanceof EndCrystal)
        {
            return Kind.END_CRYSTAL;
        }

        if (entity instanceof AbstractMinecart minecart)
        {
            // Командный и спавнерный вагоны в выживании не достать
            return minecart instanceof MinecartCommandBlock || minecart instanceof MinecartSpawner ? null : Kind.MINECART;
        }

        if (entity instanceof AbstractBoat)
        {
            return Kind.BOAT;
        }

        return null;
    }

    /** Предмет самой рамки — его возвращает и pick-block пустой рамки. */
    public static ItemStack frameItem(ItemFrame frame)
    {
        return new ItemStack(frame instanceof GlowItemFrame ? Items.GLOW_ITEM_FRAME : Items.ITEM_FRAME);
    }

    /**
     * Что принести за сущность. Картина — обычная, без варианта: в выживании другой не
     * бывает, сюжет выбирает игра, и строка «картина с таким-то сюжетом» никогда бы не
     * нашлась в инвентаре.
     */
    public static List<ItemStack> items(Entity entity, Kind kind)
    {
        switch (kind)
        {
            case ITEM_FRAME ->
            {
                ItemFrame frame = (ItemFrame) entity;
                ItemStack content = frame.getItem();

                return content.isEmpty()
                        ? List.of(frameItem(frame))
                        : List.of(frameItem(frame), content.copyWithCount(1));
            }
            case PAINTING ->
            {
                return List.of(new ItemStack(Items.PAINTING));
            }
            default ->
            {
                ItemStack pick = entity.getPickResult();

                return pick == null || pick.isEmpty() ? List.of() : List.of(pick.copyWithCount(1));
            }
        }
    }

    /**
     * Все учитываемые сущности включённых размещений. Позиция переводится в мир так же,
     * как Litematica ставит сущности при вставке и в мир схемы
     * ({@code SchematicPlacingUtils.placeEntitiesToWorldWithinChunk}).
     */
    public static List<Wanted> collect(Level level)
    {
        return collect(level, null);
    }

    /** То же только для размещений {@code only} ({@code null} — всех включённых): счёт вкладки «Материалы». */
    public static List<Wanted> collect(Level level, java.util.function.@Nullable Predicate<SchematicPlacement> only)
    {
        List<Wanted> out = new ArrayList<>();

        if (SPECS.size() > 512)
        {
            SPECS.clear();
        }

        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
        {
            // Свой набор размещений ({@code only}) — и выключенные: их голограммы скрыты, но сверить с миром можно
            if ((only == null && ! placement.isEnabled()) || placement.ignoreEntities() || (only != null && ! only.test(placement)))
            {
                continue;
            }

            LitematicaSchematic schematic = placement.getSchematic();
            BlockPos origin = placement.getOrigin();
            Mirror mainMirror = placement.getMirror();
            Rotation mainRotation = placement.getRotation();

            SubRegionPlacement.RequiredEnabled required = only == null
                    ? SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED : SubRegionPlacement.RequiredEnabled.ANY;

            for (String regionName : placement.getSubRegionBoxes(required).keySet())
            {
                SubRegionPlacement sub = placement.getRelativeSubRegionPlacement(regionName);
                List<LitematicaSchematic.EntityInfo> infos = schematic.getEntityListForRegion(regionName);

                if (sub == null || ! sub.isEnabled() || sub.ignoreEntities() || infos == null || infos.isEmpty())
                {
                    continue;
                }

                BlockPos regionOffset = PositionUtils.getTransformedBlockPos(sub.getPos(), mainMirror, mainRotation);
                double offX = regionOffset.getX() + origin.getX();
                double offY = regionOffset.getY() + origin.getY();
                double offZ = regionOffset.getZ() + origin.getZ();

                for (Spec spec : specs(infos, level))
                {
                    Vec3 pos = PositionUtils.getTransformedPosition(spec.posVec(), mainMirror, mainRotation);
                    pos = PositionUtils.getTransformedPosition(pos, sub.getMirror(), sub.getRotation());

                    out.add(new Wanted(spec.kind(), spec.type(), pos.add(offX, offY, offZ), spec.items()));
                }
            }
        }

        return out;
    }

    /**
     * Учитываемые сущности региона схемы с предметами, которыми они ставятся. Размещение не нужно —
     * хватает любого мира, чтобы создать сущность из данных. Только клиентский поток; результат
     * кешируется по экземпляру списка, его не менять.
     */
    public static List<Spec> specs(List<LitematicaSchematic.EntityInfo> infos, Level level)
    {
        List<Spec> cached = SPECS.get(infos);

        if (cached != null && cached.size() <= infos.size())
        {
            return cached;
        }

        List<Spec> specs = new ArrayList<>();

        for (LitematicaSchematic.EntityInfo info : infos)
        {
            Entity entity;

            try
            {
                // Создаём по исходным данным схемы: нужен только предмет, а позиция и
                // block_pos в исходных данных согласованы — без жалоб «invalid position».
                entity = EntityUtils.createEntityAndPassengersFromData(info.nbt(), level);
            }
            catch (RuntimeException | LinkageError e)
            {
                entity = null;
            }

            Kind kind = entity == null ? null : kind(entity);

            if (kind == null)
            {
                continue;
            }

            List<ItemStack> items = items(entity, kind);

            if (! items.isEmpty())
            {
                specs.add(new Spec(kind, entity.getType(), info.posVec(), items));
            }
        }

        SPECS.put(infos, specs);

        return specs;
    }

    /** Голограмма этой сущности в мире схемы — с точными поворотом, цветом и содержимым. */
    public static @Nullable Entity ghost(@Nullable WorldSchematic world, Wanted wanted)
    {
        if (world == null)
        {
            return null;
        }

        Entity best = null;
        double bestDistance = 1.0D;

        for (Entity entity : world.getEntities((Entity) null, new AABB(wanted.pos(), wanted.pos()).inflate(1.0D),
                entity -> entity.getType() == wanted.type()))
        {
            double distance = entity.position().distanceToSqr(wanted.pos());

            if (distance < bestDistance)
            {
                best = entity;
                bestDistance = distance;
            }
        }

        return best;
    }

    /**
     * Сверка без голограммы — чанк мира схемы ещё не собран: есть ли рядом сущность того же
     * типа. Цвет, содержимое и поворот без голограммы не сравнить, поэтому найденная — «на месте».
     */
    public static Match matchApprox(Level world, Wanted wanted)
    {
        Vec3 at = wanted.pos();

        for (Entity entity : world.getEntities((Entity) null, new AABB(at, at).inflate(0.75D),
                entity -> entity.getType() == wanted.type() && ! entity.isRemoved()))
        {
            Vec3 pos = entity.position();
            double dx = pos.x - at.x;
            double dz = pos.z - at.z;

            if (dx * dx + dz * dz <= 0.6D * 0.6D && Math.abs(pos.y - at.y) <= 0.75D)
            {
                return new Match(entity, all(wanted.items().size(), Status.OK));
            }
        }

        return new Match(null, all(wanted.items().size(), Status.MISSING));
    }

    /** Сверка с миром по голограмме из мира схемы: тип, место, поворот, цвет, содержимое. */
    public static Match match(Level world, Entity reference)
    {
        Kind kind = kind(reference);

        if (kind == null)
        {
            return new Match(null, List.of());
        }

        List<ItemStack> wanted = items(reference, kind);
        Entity real = findReal(world, reference, kind);

        if (real == null)
        {
            return new Match(null, all(wanted.size(), Status.MISSING));
        }

        List<Status> statuses = new ArrayList<>(all(wanted.size(), Status.OK));

        switch (kind)
        {
            case ARMOR_STAND ->
            {
                if (standYaw(real.getYRot()) != standYaw(reference.getYRot()))
                {
                    statuses.set(0, Status.MISMATCH);
                }
            }
            case CUSHION ->
            {
                Cushion want = (Cushion) reference;
                Cushion have = (Cushion) real;

                if (want.getColor() != have.getColor()
                        || Direction.fromYRot(want.getYRot()) != Direction.fromYRot(have.getYRot()))
                {
                    statuses.set(0, Status.MISMATCH);
                }
            }
            case ITEM_FRAME ->
            {
                ItemFrame want = (ItemFrame) reference;
                ItemFrame have = (ItemFrame) real;

                if (wanted.size() > 1)
                {
                    if (have.getItem().isEmpty())
                    {
                        statuses.set(1, Status.MISSING);
                    }
                    else if (! ItemData.same(have.getItem(), want.getItem())
                            || have.getRotation() != want.getRotation())
                    {
                        statuses.set(1, Status.MISMATCH);
                    }
                }
                else if (! have.getItem().isEmpty())
                {
                    // Рамка по схеме пустая, а в мире в ней что-то лежит
                    statuses.set(0, Status.MISMATCH);
                }
            }
            case PAINTING ->
            {
                if (! ((Painting) real).getVariant().equals(((Painting) reference).getVariant()))
                {
                    statuses.set(0, Status.MISMATCH);
                }
            }
            default ->
            {
            }
        }

        return new Match(real, statuses);
    }

    /** Поворот стойки для брони, как его даёт предмет: шаг 45°. */
    public static int standYaw(float yaw)
    {
        return Math.floorMod(Mth.floor((Mth.wrapDegrees(yaw) + 22.5F) / 45.0F), 8);
    }

    /**
     * Сущность того же типа в мире на месте голограммы. Прикреплённые к блоку (рамка,
     * картина) — на той же клетке той же гранью; подушка — ровно на месте: она не падает
     * и не сдвигается, и на краю котла вместо его дна это уже не она; остальные — рядом:
     * стойка падает на плиту, вагонетка садится на рельс, точного совпадения ждать нельзя.
     */
    private static @Nullable Entity findReal(Level world, Entity reference, Kind kind)
    {
        Vec3 at = reference.position();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        double across = kind == Kind.CUSHION ? 0.05D : 0.35D;
        double up = kind == Kind.CUSHION ? 0.05D : 0.75D;

        for (Entity entity : world.getEntities((Entity) null, reference.getBoundingBox().inflate(0.75D),
                entity -> entity.getType() == reference.getType() && ! entity.isRemoved()))
        {
            if (kind == Kind.ITEM_FRAME || kind == Kind.PAINTING)
            {
                var want = (net.minecraft.world.entity.decoration.HangingEntity) reference;
                var have = (net.minecraft.world.entity.decoration.HangingEntity) entity;

                if (have.getDirection() != want.getDirection()
                        || have.getBoundingBox().getCenter().distanceToSqr(want.getBoundingBox().getCenter()) > 0.01D)
                {
                    continue;
                }

                return entity;
            }

            Vec3 pos = entity.position();
            double dx = pos.x - at.x;
            double dz = pos.z - at.z;
            double dy = Math.abs(pos.y - at.y);

            if (dx * dx + dz * dz > across * across || dy > up)
            {
                continue;
            }

            double distance = pos.distanceToSqr(at);

            if (distance < bestDistance)
            {
                best = entity;
                bestDistance = distance;
            }
        }

        return best;
    }

    private static List<Status> all(int size, Status status)
    {
        List<Status> out = new ArrayList<>(size);

        for (int i = 0; i < size; ++i)
        {
            out.add(status);
        }

        return out;
    }
}
