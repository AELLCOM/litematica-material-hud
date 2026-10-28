package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.SchematicEntities;
import com.aell.layerlist.util.Chat;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.PaintingVariantTags;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Easy Place для сущностей схемы: стойки для брони, подушки (26.3), рамки (с предметом и
 * его поворотом), картины, кристаллы края, вагонетки.
 *
 * <p>Сама Litematica сущности не ставит вовсе: её трассировка видит только блоки схемы.
 * Здесь луч взгляда проверяется по голограммам из мира схемы; если голограмма ближе клетки
 * схемы и настоящего блока, а в мире на её месте ничего подходящего нет, сущность
 * ставится тем же кликом, каким её поставил бы игрок: предмет в руку, клик по опорной
 * грани, поворот головы — как у голограммы. Темп, подтверждение сервером и поворот —
 * общие с установкой блоков ({@link Pacing}, {@link PoseAhead}).
 */
@Environment(EnvType.CLIENT)
public final class EntityPlace
{
    /** Сколько ждать, пока поставленная сущность придёт с сервера, прежде чем кликать снова. */
    private static final long SPAWN_WAIT_MS = 750L;
    /** Предмет в рамку и её поворот приходят обновлением данных сущности — быстрее. */
    private static final long INTERACT_WAIT_MS = 350L;
    /** Столько раз подряд сервер может не принять клик, после чего голограмма отдыхает. */
    private static final int MAX_TRIES = 3;
    private static final long REST_MS = 10_000L;

    /** {@code resting} — после отказов сервера: клики тогда отдаём Litematica, а не съедаем. */
    private record Attempt(long until, int tries, String state, boolean resting) {}

    /** Ключ — id голограммы в мире схемы: у двух размещений одной схемы UUID совпадают. */
    private static final Map<Integer, Attempt> ATTEMPTS = new HashMap<>();
    private static final Set<String> TOLD = new HashSet<>();

    private record Target(Entity ghost, SchematicEntities.Kind kind, SchematicEntities.Match match, double distance) {}

    /**
     * Что сделать. {@code hit} — клик по блоку (поставить), {@code entity} — клик по
     * сущности (предмет в рамку или её поворот). {@code item == null} — рука не важна.
     */
    private record Action(String what, @Nullable Predicate<ItemStack> item, ItemStack sample, @Nullable Float yaw,
                          @Nullable BlockHitResult hit, @Nullable Entity entity, boolean sneak, long waitMs) {}

    /** Ставить нельзя, и человеку стоит сказать почему. */
    private record Skip(String reasonKey) {}

    private EntityPlace() {}

    public static void clear()
    {
        ATTEMPTS.clear();
        TOLD.clear();
    }

    /** Вызывается в начале обработчика Easy Place. true — клик наш, Litematica не трогаем. */
    public static boolean run()
    {
        if (! EasyPlaceModule.isActive() || ! LayerListConfigs.EP_ENTITIES.getBooleanValue())
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        WorldSchematic schematic = schematicWorld();

        if (player == null || level == null || schematic == null || mc.gameMode == null)
        {
            return false;
        }

        Target target = aim(mc, player, level, schematic);

        if (target == null)
        {
            return false;
        }

        Entity ghost = target.ghost();
        BlockPos at = BlockPos.containing(ghost.position());
        String state = stateOf(target.match());
        // Монотонные часы: настенные в WSL и на ноутбуках прыгают на секунды при синхронизации
        long now = Util.getMillis();
        Attempt attempt = ATTEMPTS.get(ghost.getId());

        if (attempt != null && now < attempt.until())
        {
            // Клик ушёл, ждём сущность с сервера: второй клик сейчас поставил бы вторую стойку
            // или ушёл бы в блок за голограммой. Отдыхающая после отказов голограмма не мешает блокам.
            return ! attempt.resting();
        }

        Object plan = plan(level, player, target);

        if (plan instanceof Skip skip)
        {
            tell(ghost, at, skip.reasonKey());
            return false;
        }

        if (! (plan instanceof Action action))
        {
            EpDebug.say("сущность " + ghost.getType() + " " + EpDebug.at(at) + ": нечего делать, в мире "
                    + (target.match().real() == null ? "пусто" : target.match().statuses()));
            return false;
        }

        int tries = attempt != null && attempt.state().equals(state) && now < attempt.until() + REST_MS ? attempt.tries() : 0;

        if (tries >= MAX_TRIES)
        {
            ATTEMPTS.put(ghost.getId(), new Attempt(now + REST_MS, 0, state, true));
            tell(ghost, at, "gave_up");
            return false;
        }

        if (AutoPlaceState.isConfirmationPending(at, level.getBlockState(at), LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            EpDebug.say("сущность " + ghost.getType() + " " + EpDebug.at(at) + ": клетка ждёт подтверждения");
            return true;
        }

        if (Pacing.tooSoon())
        {
            return true;
        }

        InteractionHand hand = action.item() == null ? InteractionHand.MAIN_HAND : take(mc, player, action.item(), action.sample());

        if (hand == null)
        {
            tellMissing(ghost, at, action.sample());
            return true;
        }

        float yaw = action.yaw() != null ? action.yaw() : player.getYRot();
        LookSpoof.Pose pose = Pacing.pose(player, new LookSpoof.Pose(yaw, player.getXRot(), action.sneak()));

        if (action.yaw() != null && ! facesLike(target.kind(), pose.yaw(), action.yaw()))
        {
            // «Не поворачивать игрока»: с нынешним взглядом сущность встанет не тем боком
            tell(ghost, at, "turn");
            return true;
        }

        if (PoseAhead.deferring(player, pose))
        {
            EpDebug.say("сущность " + ghost.getType() + " " + EpDebug.at(at) + ": угол уйдёт пакетом движения");
            return true;
        }

        EpDebug.say("сущность " + action.what() + " " + EpDebug.at(at));

        boolean done = PoseAhead.applied(player, pose, () -> AutoPlaceState.ownClick(() -> {
            if (action.entity() != null)
            {
                Entity entity = action.entity();
                EntityHitResult hit = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
                return mc.gameMode.interact(player, entity, hit, hand).consumesAction();
            }

            return mc.gameMode.useItemOn(player, hand, action.hit()).consumesAction();
        }));

        Pacing.mark();
        ATTEMPTS.put(ghost.getId(), new Attempt(now + action.waitMs(), tries + 1, state, false));

        if (ATTEMPTS.size() > 512)
        {
            ATTEMPTS.entrySet().removeIf(entry -> entry.getValue().until() + REST_MS < now);
        }

        if (done && Configs.Generic.EASY_PLACE_SWING_HAND.getBooleanValue())
        {
            player.swing(hand);
        }

        return true;
    }

    // ===== Прицел =====

    private static @Nullable Target aim(Minecraft mc, LocalPlayer player, ClientLevel level, WorldSchematic schematic)
    {
        double range = EasyPlaceUtils.getValidBlockRange(mc);
        Vec3 eyes = player.getEyePosition(1.0F);
        Vec3 end = eyes.add(player.getViewVector(1.0F).scale(range));
        Target best = null;
        int candidates = 0;
        int hits = 0;
        int satisfied = 0;

        for (Entity ghost : schematic.getEntities((Entity) null, new AABB(eyes, end).inflate(1.0D),
                entity -> SchematicEntities.kind(entity) != null))
        {
            ++candidates;

            if (! DataManager.getRenderLayerRange().isPositionWithinRange(BlockPos.containing(ghost.position())))
            {
                continue;
            }

            // Рамка и картина толщиной в шестнадцатую — чуть раздуваем, чтобы попасть было легко
            Optional<Vec3> hit = ghost.getBoundingBox().inflate(0.0625D).clip(eyes, end);

            if (hit.isEmpty())
            {
                continue;
            }

            ++hits;
            double distance = hit.get().distanceTo(eyes);

            if (best != null && distance >= best.distance())
            {
                continue;
            }

            SchematicEntities.Match match = SchematicEntities.match(level, ghost);

            if (! match.satisfied())
            {
                best = new Target(ghost, SchematicEntities.kind(ghost), match, distance);
            }
            else
            {
                ++satisfied;
            }
        }

        if (best == null)
        {
            if (hits > 0)
            {
                EpDebug.say("сущность: под прицелом голограмм " + hits + " из " + candidates + ", все на месте");
            }

            return null;
        }

        // Настоящий блок между глазом и голограммой — это взгляд сквозь стену, не к сущности
        BlockHitResult wall = level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        if (wall.getType() == HitResult.Type.BLOCK && wall.getLocation().distanceTo(eyes) < best.distance() - 1.0E-3D)
        {
            EpDebug.say("сущность " + best.ghost().getType() + ": заслоняет блок " + EpDebug.at(wall.getBlockPos()));
            return null;
        }

        // Клетка схемы ближе голограммы — сначала её ставит Litematica
        BlockHitResult cell = StateRepair.target(mc);

        if (cell != null && cell.getLocation().distanceTo(eyes) < best.distance() - 1.0E-3D)
        {
            EpDebug.say("сущность " + best.ghost().getType() + ": ближе клетка схемы " + EpDebug.at(cell.getBlockPos()));
            return null;
        }

        return best;
    }

    // ===== Планы =====

    /** {@link Action}, {@link Skip} или null — ставить нечего, пусть работает Litematica. */
    private static @Nullable Object plan(ClientLevel level, LocalPlayer player, Target target)
    {
        Entity ghost = target.ghost();
        Entity real = target.match().real();

        if (target.kind() == SchematicEntities.Kind.ITEM_FRAME)
        {
            ItemFrame want = (ItemFrame) ghost;

            if (real == null)
            {
                return hanging(level, want, SchematicEntities.frameItem(want), "рамка");
            }

            ItemFrame have = (ItemFrame) real;
            ItemStack content = want.getItem();

            if (content.isEmpty())
            {
                return null;
            }

            if (have.getItem().isEmpty())
            {
                return new Action("предмет в рамку", stack -> ItemData.same(stack, content), content.copyWithCount(1),
                        null, null, have, false, INTERACT_WAIT_MS);
            }

            if (ItemData.same(have.getItem(), content) && have.getRotation() != want.getRotation())
            {
                // Каждый клик по рамке с предметом поворачивает его на 45°
                return new Action("поворот предмета в рамке", null, ItemStack.EMPTY, null, null, have, false,
                        INTERACT_WAIT_MS);
            }

            return null;
        }

        if (real != null)
        {
            // Стоит, но не та (другого цвета, повёрнута, другой сюжет) — кликом не исправить
            return null;
        }

        return switch (target.kind())
        {
            case ARMOR_STAND -> stand(level, ghost);
            case CUSHION -> null; // 26.2: подушек в игре нет
            case PAINTING -> painting(level, player, (Painting) ghost);
            case END_CRYSTAL -> crystal(level, ghost);
            case MINECART -> minecart(level, ghost);
            case BOAT -> new Skip("boat");
            default -> null;
        };
    }

    /**
     * Стойка встаёт у низа клетки, куда кликнули, и падает: на плите она окажется на
     * половине блока. Поэтому клетка спавна — та, чей низ на высоте стойки или чуть выше.
     */
    private static @Nullable Action stand(ClientLevel level, Entity ghost)
    {
        Vec3 p = ghost.position();
        BlockPos at = BlockPos.containing(p.x, Math.ceil(p.y - 1.0E-4D), p.z);
        AABB box = EntityTypes.ARMOR_STAND.getDimensions().makeBoundingBox(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);

        if (! level.noCollision(null, box) || ! level.getEntities((Entity) null, box).isEmpty())
        {
            return null;
        }

        BlockHitResult hit = support(level, at);

        if (hit == null)
        {
            return null;
        }

        // Поворот стойки = взгляд игрока − 180°, с шагом 45°
        return new Action("стойка", stack -> stack.is(Items.ARMOR_STAND), new ItemStack(Items.ARMOR_STAND),
                ghost.getYRot() + 180.0F, hit, null, needsSneak(level, hit), SPAWN_WAIT_MS);
    }

    private static @Nullable Action hanging(ClientLevel level, HangingEntity ghost, ItemStack item, String what)
    {
        BlockPos pos = ghost.getPos();
        Direction face = ghost.getDirection();
        HangingEntity probe = ghost instanceof GlowItemFrame ? new GlowItemFrame(level, pos, face) : new ItemFrame(level, pos, face);

        if (! probe.survives())
        {
            return null;
        }

        BlockHitResult hit = faceHit(level, pos.relative(face.getOpposite()), face);

        return new Action(what, stack -> stack.is(item.getItem()), item, null, hit, null, needsSneak(level, hit),
                SPAWN_WAIT_MS);
    }

    /**
     * Картина. Сюжет выбирает игра — случайно среди самых больших, что помещаются на месте.
     * Ставим, только если выйдет картина того же размера, что в схеме, или в инвентаре есть
     * картина с этим самым сюжетом (из творческого режима): иначе на месте маленькой
     * встала бы большая и заняла соседние.
     */
    private static Object painting(ClientLevel level, LocalPlayer player, Painting ghost)
    {
        Direction face = ghost.getDirection();
        Holder<PaintingVariant> variant = ghost.getVariant();
        BlockPos anchor = paintingAnchor(level, ghost, face, variant);

        if (anchor == null || ! new Painting(level, anchor, face, variant).survives())
        {
            return null;
        }

        int wanted = area(variant);
        int largest = 0;

        for (Holder<PaintingVariant> candidate : level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                .getTagOrEmpty(PaintingVariantTags.PLACEABLE))
        {
            if (new Painting(level, anchor, face, candidate).survives())
            {
                largest = Math.max(largest, area(candidate));
            }
        }

        Predicate<ItemStack> exact = stack -> stack.is(Items.PAINTING) && variant.equals(stack.get(DataComponents.PAINTING_VARIANT));
        Predicate<ItemStack> item = exact;

        if (! Hands.has(player, exact) && ! player.hasInfiniteMaterials())
        {
            if (largest != wanted)
            {
                return new Skip("painting_size");
            }

            item = stack -> stack.is(Items.PAINTING) && stack.get(DataComponents.PAINTING_VARIANT) == null;
        }

        ItemStack sample = new ItemStack(Items.PAINTING);

        if (player.hasInfiniteMaterials())
        {
            sample.set(DataComponents.PAINTING_VARIANT, variant);
        }

        BlockHitResult hit = faceHit(level, anchor.relative(face.getOpposite()), face);

        return new Action("картина", item, sample, null, hit, null, needsSneak(level, hit), SPAWN_WAIT_MS);
    }

    /**
     * Клетка, от которой игра строит картину: у чётных по ширине и высоте она не в центре.
     * Ищем ту, где картина того же сюжета займёт ровно место голограммы.
     */
    private static @Nullable BlockPos paintingAnchor(ClientLevel level, Painting ghost, Direction face, Holder<PaintingVariant> variant)
    {
        Vec3 centre = ghost.getBoundingBox().getCenter();
        Direction left = face.getCounterClockWise();

        for (int up = 0; up >= -1; --up)
        {
            for (int side = 0; side <= 1; ++side)
            {
                for (int sign = 1; sign >= -1; sign -= 2)
                {
                    BlockPos anchor = ghost.getPos().relative(left, side * sign).above(up);
                    Painting probe = new Painting(level, anchor, face, variant);

                    if (probe.getBoundingBox().getCenter().distanceToSqr(centre) < 1.0E-4D)
                    {
                        return anchor;
                    }
                }
            }
        }

        return null;
    }

    private static int area(Holder<PaintingVariant> variant)
    {
        return variant.value().width() * variant.value().height();
    }

    private static @Nullable Action crystal(ClientLevel level, Entity ghost)
    {
        BlockPos at = BlockPos.containing(ghost.position());
        BlockPos base = at.below();
        BlockState state = level.getBlockState(base);

        if ((! state.is(Blocks.OBSIDIAN) && ! state.is(Blocks.BEDROCK)) || ! level.isEmptyBlock(at)
                || ! level.getEntities((Entity) null, new AABB(at.getX(), at.getY(), at.getZ(), at.getX() + 1.0D,
                        at.getY() + 2.0D, at.getZ() + 1.0D)).isEmpty())
        {
            return null;
        }

        BlockHitResult hit = faceHit(level, base, Direction.UP);

        return new Action("кристалл края", stack -> stack.is(Items.END_CRYSTAL), new ItemStack(Items.END_CRYSTAL), null, hit,
                null, needsSneak(level, hit), SPAWN_WAIT_MS);
    }

    /** Вагонетка встаёт по центру рельса, по которому кликнули. */
    private static @Nullable Action minecart(ClientLevel level, Entity ghost)
    {
        BlockPos rail = BlockPos.containing(ghost.position());

        if (! level.getBlockState(rail).is(BlockTags.RAILS))
        {
            rail = rail.below();

            if (! level.getBlockState(rail).is(BlockTags.RAILS))
            {
                return null;
            }
        }

        ItemStack item = ghost.getPickResult();

        if (item == null || item.isEmpty())
        {
            return null;
        }

        BlockHitResult hit = faceHit(level, rail, Direction.UP);
        ItemStack sample = item.copyWithCount(1);

        return new Action("вагонетка", stack -> stack.is(sample.getItem()), sample, null, hit, null, false, SPAWN_WAIT_MS);
    }

    // ===== Опора и клик =====

    /** Грань, клик по которой поставит сущность в клетку {@code at}: верх блока под ней или бок соседа. */
    private static @Nullable BlockHitResult support(ClientLevel level, BlockPos at)
    {
        BlockPos below = at.below();

        if (solid(level, below))
        {
            return faceHit(level, below, Direction.UP);
        }

        for (Direction side : Direction.Plane.HORIZONTAL)
        {
            BlockPos neighbour = at.relative(side);

            if (solid(level, neighbour))
            {
                return faceHit(level, neighbour, side.getOpposite());
            }
        }

        return null;
    }

    private static boolean solid(ClientLevel level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        // Снег от двух слоёв заменяет только сам снег: стойку он держит сверху, как любой блок
        boolean replaceable = state.canBeReplaced()
                && ! (state.getBlock() instanceof SnowLayerBlock && state.getValue(SnowLayerBlock.LAYERS) > 1);

        return ! state.isAir() && ! replaceable && ! state.getShape(level, pos).isEmpty();
    }

    /** Точка на грани настоящей формы блока: висящую в воздухе точку античит отменяет. */
    private static BlockHitResult faceHit(ClientLevel level, BlockPos pos, Direction face)
    {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        double x = (box.minX + box.maxX) / 2.0D;
        double y = (box.minY + box.maxY) / 2.0D;
        double z = (box.minZ + box.maxZ) / 2.0D;

        switch (face)
        {
            case UP -> y = box.maxY;
            case DOWN -> y = box.minY;
            case NORTH -> z = box.minZ;
            case SOUTH -> z = box.maxZ;
            case WEST -> x = box.minX;
            case EAST -> x = box.maxX;
        }

        return new BlockHitResult(new Vec3(x, y, z), face, pos, false);
    }

    /** Опора открывается или ест клик (сундук, дверь) — кликаем с приседом, как сделал бы игрок. */
    private static boolean needsSneak(ClientLevel level, BlockHitResult hit)
    {
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);

        return Interactive.opensScreen(level, pos, state) || Interactive.eatsRightClick(level, pos, state);
    }

    /** С нынешним взглядом сущность встанет как надо: стойка — шагом 45°, подушка — стороной света. */
    private static boolean facesLike(SchematicEntities.Kind kind, float have, float wanted)
    {
        if (kind == SchematicEntities.Kind.CUSHION)
        {
            return Direction.fromYRot(have) == Direction.fromYRot(wanted);
        }

        return SchematicEntities.standYaw(have) == SchematicEntities.standYaw(wanted);
    }

    /** Нужный предмет в руку; в творческом режиме, если его нет, — берём из ничего, как pick-block. */
    private static @Nullable InteractionHand take(Minecraft mc, LocalPlayer player, Predicate<ItemStack> item, ItemStack sample)
    {
        InteractionHand hand = Hands.with(player, item);

        if (hand != null || ! player.hasInfiniteMaterials() || sample.isEmpty() || mc.gameMode == null)
        {
            return hand;
        }

        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = sample.copyWithCount(1);
        player.getInventory().setItem(slot, stack);
        mc.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);

        return InteractionHand.MAIN_HAND;
    }

    private static String stateOf(SchematicEntities.Match match)
    {
        Entity real = match.real();

        if (real instanceof ItemFrame frame)
        {
            return "frame:" + frame.getItem().getItem() + ":" + frame.getRotation();
        }

        return real == null ? "none" : "present:" + match.statuses();
    }

    // ===== Сообщения =====

    private static void tell(Entity ghost, BlockPos at, String reasonKey)
    {
        EpDebug.say("сущность " + ghost.getType() + " " + EpDebug.at(at) + ": " + reasonKey);

        if (remember(ghost, at, reasonKey))
        {
            Chat.send(Chat.tr("layerlist.message.ep_entity_skip", Chat.WARN, ghost.getType().getDescription(),
                    at.getX(), at.getY(), at.getZ(), Component.translatable("layerlist.message.ep_entity_reason." + reasonKey)));
        }
    }

    private static void tellMissing(Entity ghost, BlockPos at, ItemStack item)
    {
        EpDebug.say("сущность " + ghost.getType() + " " + EpDebug.at(at) + ": нет предмета " + item.getItem());

        if (remember(ghost, at, "item"))
        {
            Chat.send(Chat.tr("layerlist.message.ep_entity_missing_item", Chat.WARN, ghost.getType().getDescription(),
                    at.getX(), at.getY(), at.getZ(), item.getHoverName()));
        }
    }

    private static boolean remember(Entity ghost, BlockPos at, String what)
    {
        if (TOLD.size() > 256)
        {
            TOLD.clear();
        }

        return TOLD.add(ghost.getType() + "@" + at.asLong() + ":" + what);
    }

    private static @Nullable WorldSchematic schematicWorld()
    {
        try
        {
            return SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
