package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Кувшинка и икра лягушки: блоки, которые ставятся на воду, а не по клику в клетку.
 *
 * У их предмета ({@link PlaceOnWaterBlockItem}) {@code useOn} возвращает PASS — то есть
 * любой клик по блоку, и ванильный, и присланный Easy Place, для них пустой. Работает
 * только {@code use}: сервер сам ведёт луч из глаз игрока с {@code Fluid.SOURCE_ONLY},
 * берёт клетку воды, в которую попал, и ставит блок над ней. Угол взгляда сервер берёт
 * прямо из пакета {@code ServerboundUseItemPacket}, поэтому целиться можно {@link LookSpoof}.
 *
 * Отсюда две половины работы. Первая — вместо бесполезного клика подобрать взгляд ровно
 * в ту клетку воды, что под клеткой схемы, и отправить {@code use} самим. Вторая —
 * погасить ванильный {@code use}, который иначе прилетает тем же правым кликом уже с
 * настоящим взглядом игрока и сеет кувшинки мимо схемы.
 */
@Environment(EnvType.CLIENT)
public final class WaterPlace
{
    /** Доли клетки воды по горизонтали, в которые пробуем целиться. */
    private static final double[][] SPOTS = {
            { 0.5D, 0.5D }, { 0.3D, 0.5D }, { 0.7D, 0.5D }, { 0.5D, 0.3D }, { 0.5D, 0.7D }
    };

    private WaterPlace() {}

    public static boolean is(ItemStack stack)
    {
        return stack.getItem() instanceof PlaceOnWaterBlockItem;
    }

    /** Предмет ставит именно тот блок, который просит схема. */
    public static boolean matches(ItemStack stack, BlockState schematic)
    {
        return stack.getItem() instanceof BlockItem item && item.getBlock() == schematic.getBlock();
    }

    /** Вода под клеткой уже налита — иначе ставить не на что, ждём. */
    public static boolean supported(ClientLevel level, BlockPos target, BlockState schematic)
    {
        try
        {
            return schematic.canSurvive(level, target);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /**
     * Поза, при которой серверный луч {@code SOURCE_ONLY} попадёт ровно в клетку воды
     * под целью. Каждая проба проверяется тем же самым лучом, что ведёт сервер, поэтому
     * промах по соседней колонне воды отсеивается здесь, а не всплывает кувшинкой не там.
     */
    public static @Nullable LookSpoof.Pose aim(ClientLevel level, LocalPlayer player, BlockPos target)
    {
        BlockPos water = target.below();
        boolean sneak = player.isShiftKeyDown();

        if (hits(level, player, player.getYRot(), player.getXRot(), water))
        {
            return new LookSpoof.Pose(player.getYRot(), player.getXRot(), sneak);
        }

        if (! Pacing.mayTurn())
        {
            return null;
        }

        Vec3 eyes = player.getEyePosition();

        for (Vec3 spot : spots(level, water))
        {
            Vec3 line = spot.subtract(eyes);
            double flat = Math.sqrt(line.x * line.x + line.z * line.z);

            if (line.lengthSqr() < 1.0E-6D)
            {
                continue;
            }

            float yaw = (float) Mth.wrapDegrees(- Math.toDegrees(Math.atan2(line.x, line.z)));
            float pitch = (float) (- Math.toDegrees(Math.atan2(line.y, flat)));

            if (hits(level, player, yaw, pitch, water))
            {
                return new LookSpoof.Pose(yaw, pitch, sneak);
            }
        }

        return null;
    }

    public static boolean place(Minecraft mc, LocalPlayer player, InteractionHand hand, BlockPos target,
            LookSpoof.Pose pose, BlockState world)
    {
        AutoPlaceState.markClicked(target, world);

        return LookSpoof.announced(player, pose, () -> AutoPlaceState.ownClick(() ->
        {
            InteractionResult result = mc.gameMode.useItem(player, hand);
            EpDebug.say("вода " + EpDebug.at(target) + ": use() → " + result);

            return result.consumesAction();
        }));
    }

    /**
     * Ванильный {@code use} тем же правым кликом: {@code Minecraft.startUseItem} зовёт его
     * следом за кликом по блоку, а при промахе — вместо него. Взгляд там настоящий, игрока,
     * поэтому кувшинка легла бы в первую попавшуюся клетку воды. Пропускаем такой клик,
     * когда он и сам попадает ровно в незаполненную клетку схемы — и всегда, когда он
     * ложится вне области размещения: за границей схемы кувшинка — ручная работа игрока.
     */
    public static boolean strayUse(Player player, InteractionHand hand)
    {
        if (! EasyPlaceModule.isActive() || AutoPlaceState.isOwnClick()
                || ! (player instanceof LocalPlayer local))
        {
            return false;
        }

        ItemStack stack = player.getItemInHand(hand);
        ClientLevel level = Minecraft.getInstance().level;

        if (! is(stack) || level == null || ! AutoPlaceState.isAutomated(
                LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue()))
        {
            return false;
        }

        WorldSchematic schematicWorld = schematicWorld();
        BlockPos cell = schematicWorld == null ? null : predict(level, local);

        if (cell == null)
        {
            return false;
        }

        BlockState wanted = schematicWorld.getBlockState(cell);
        boolean onPlan = ! wanted.isAir() && wanted.getBlock().asItem() == stack.getItem();
        // «Только по схеме» и здесь режет только внутри области размещения — как в
        // PlacementGuard. Раньше проверки границы не было, и при активации на ПКМ
        // (клик «автоматизирован» всегда) кувшинку нельзя было поставить нигде в мире.
        boolean stray = onPlan
                ? ! level.getBlockState(cell).canBeReplaced()
                : LayerListConfigs.EP_ONLY_SCHEMATIC.getBooleanValue()
                        && SchematicTarget.insideSchematic(cell);

        if (stray)
        {
            EpDebug.say("вода: ванильный use() лёг бы в " + EpDebug.at(cell) + " — отменён");
        }

        return stray;
    }

    /** Клетка, которую занял бы ванильный {@code use} при нынешнем взгляде. */
    private static @Nullable BlockPos predict(ClientLevel level, LocalPlayer player)
    {
        BlockHitResult hit = pov(level, player, player.getYRot(), player.getXRot());

        if (hit == null)
        {
            return null;
        }

        BlockPos cell = hit.getBlockPos().above();

        return level.getBlockState(cell).canBeReplaced() ? cell : cell.relative(hit.getDirection());
    }

    private static boolean hits(ClientLevel level, LocalPlayer player, float yaw, float pitch,
            BlockPos water)
    {
        BlockHitResult hit = pov(level, player, yaw, pitch);

        return hit != null && hit.getBlockPos().equals(water);
    }

    /** Тот же луч, что ведёт {@code Item.getPlayerPOVHitResult} на сервере. */
    private static @Nullable BlockHitResult pov(ClientLevel level, LocalPlayer player, float yaw,
            float pitch)
    {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.calculateViewVector(pitch, yaw).scale(player.blockInteractionRange()));

        HitResult trace = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY, player));

        return trace instanceof BlockHitResult hit && trace.getType() == HitResult.Type.BLOCK
                ? hit
                : null;
    }

    /**
     * Точки прицеливания внутри клетки воды: сперва по её поверхности, потом вполглубины.
     * Поверхность у источника — 8/9 клетки, а не её верхняя грань, поэтому высоту берём
     * из формы жидкости: точка на грани прошла бы над водой и никуда не попала.
     */
    private static List<Vec3> spots(ClientLevel level, BlockPos water)
    {
        double top = surface(level, water);
        List<Vec3> out = new ArrayList<>();

        for (double height : new double[] { top, top * 0.5D })
        {
            for (double[] spot : SPOTS)
            {
                out.add(new Vec3(water.getX() + spot[0], water.getY() + height,
                        water.getZ() + spot[1]));
            }
        }

        return out;
    }

    private static double surface(ClientLevel level, BlockPos water)
    {
        try
        {
            VoxelShape shape = level.getFluidState(water).getShape(level, water);

            return shape.isEmpty() ? 1.0D : Math.min(1.0D, shape.max(Direction.Axis.Y));
        }
        catch (RuntimeException | LinkageError e)
        {
            return 8.0D / 9.0D;
        }
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
