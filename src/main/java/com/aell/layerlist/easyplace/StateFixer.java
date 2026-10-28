package com.aell.layerlist.easyplace;

import java.util.function.Predicate;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class StateFixer
{
    private StateFixer() {}

    public static boolean run(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState schematic,
            BlockState world, Direction preferredFace)
    {
        StateFix.Fix fix = StateFix.plan(mc.level, schematicWorld(), pos, schematic, world);

        if (fix == null)
        {
            return false;
        }

        return switch (fix.path())
        {
            case TOOL -> withTool(mc, player, pos, world, fix, preferredFace);
            case BLOCK_ACTION -> withBlockAction(mc, player, pos, world, fix, preferredFace);
            case EMPTY_HAND -> withEmptyHand(mc, player, pos, world, fix, preferredFace);
            case BUCKET -> withBucket(mc, player, pos, world, fix);
            case SHELF_SWAP -> ShelfSwap.run(mc, player, pos, world, fix, preferredFace);
            case SHELF_SINGLE -> ShelfSwap.runSingle(mc, player, pos, world, fix, preferredFace);
        };
    }

    private static boolean withTool(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world,
            StateFix.Fix fix, Direction preferredFace)
    {
        InteractionHand hand = handWith(player, fix.tool());

        if (hand == null)
        {
            EpDebug.say("починка '" + fix.id() + "' " + EpDebug.at(pos)
                    + ": нужного предмета нет в инвентаре");
            return false;
        }

        return click(mc, player, pos, world, fix, preferredFace, hand, fix.sneak());
    }

    private static boolean withBlockAction(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world,
            StateFix.Fix fix, Direction preferredFace)
    {
        return click(mc, player, pos, world, fix, preferredFace, InteractionHand.MAIN_HAND, false);
    }

    private static boolean withEmptyHand(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world,
            StateFix.Fix fix, Direction preferredFace)
    {
        InteractionHand hand = player.getOffhandItem().isEmpty()
                ? InteractionHand.OFF_HAND
                : player.getMainHandItem().isEmpty() ? InteractionHand.MAIN_HAND : null;

        if (hand == null)
        {
            EpDebug.say("починка '" + fix.id() + "' " + EpDebug.at(pos) + ": нужна пустая рука");
            return false;
        }

        return click(mc, player, pos, world, fix, preferredFace, hand, false);
    }

    private static boolean withBucket(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world,
            StateFix.Fix fix)
    {
        if (! (mc.hitResult instanceof BlockHitResult aimed)
                || mc.hitResult.getType() != HitResult.Type.BLOCK
                || ! aimed.getBlockPos().equals(pos))
        {
            return false;
        }

        InteractionHand hand = handWith(player, fix.tool());

        if (hand == null)
        {
            return false;
        }

        AutoPlaceState.markClicked(pos, world);

        return LookSpoof.announced(player,
                new LookSpoof.Pose(player.getYRot(), player.getXRot(), false),
                () -> AutoPlaceState.ownClick(() -> mc.gameMode.useItem(player, hand).consumesAction()));
    }

    static boolean click(Minecraft mc, LocalPlayer player, BlockPos pos, BlockState world,
            StateFix.Fix fix, Direction preferredFace, InteractionHand hand, boolean sneak)
    {
        float yaw = fix.yawFacing() == null || ! Pacing.mayTurn()
                ? player.getYRot()
                : fix.yawFacing().toYRot();

        // Точка попадания — пересечение луча взгляда с РЕАЛЬНОЙ формой блока, как у
        // ванильного клика. Грань куба клетки не годится: у горшка «верх» — это y+1,
        // а сам он высотой 6/16 — точка висела бы в воздухе над блоком, и античит
        // такой клик молча отменяет. Если луч в форму не попал (взгляд в стороне),
        // остаётся расчётная точка на грани клетки.
        BlockHitResult aimed = fix.upperHalf() || fix.point() != null || fix.clickFace() != null ? null
                : clipShape(mc, player, pos);
        Direction face;
        Vec3 point;

        if (fix.point() != null)
        {
            // Слот полки: точка задана планом — сервер по ней вычислит нужный слот
            face = fix.clickFace();
            point = fix.point();
        }
        else if (fix.clickFace() != null)
        {
            // Грань задана планом (тыква режется гранью, куда смотрит лицо) — точка на ней по взгляду
            face = fix.clickFace();
            point = PlainHits.spot(player.getEyePosition(1.0F), yaw, player.getXRot(), pos, face);
        }
        else if (aimed != null && ! (fix.avoidDownFace() && aimed.getDirection() == Direction.DOWN))
        {
            face = aimed.getDirection();
            point = aimed.getLocation();
        }
        else
        {
            face = StateFix.face(fix, preferredFace);
            point = fix.upperHalf()
                    ? spot(pos, face, true)
                    : PlainHits.spot(player.getEyePosition(1.0F), yaw, player.getXRot(), pos, face);
        }

        BlockHitResult hit = new BlockHitResult(point, face, pos, false);

        AutoPlaceState.markClicked(pos, world);
        EpDebug.say("починка '" + fix.id() + "' " + EpDebug.at(pos) + ": клик гранью " + face
                + (aimed != null ? " (по форме)" : " (расчётная точка)"));

        return LookSpoof.announced(player, new LookSpoof.Pose(yaw, player.getXRot(), sneak),
                () -> AutoPlaceState.ownClick(() -> {
                    var result = mc.gameMode.useItemOn(player, hand, hit);
                    EpDebug.say("починка '" + fix.id() + "' → " + result);
                    return result.consumesAction();
                }));
    }

    private static net.minecraft.world.level.Level schematicWorld()
    {
        try
        {
            return fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Пересечение луча взгляда с настоящей формой блока — ванильная точка попадания. */
    private static @Nullable BlockHitResult clipShape(Minecraft mc, LocalPlayer player, BlockPos pos)
    {
        if (mc.level == null)
        {
            return null;
        }

        Vec3 eyes = player.getEyePosition(1.0F);
        Vec3 end = eyes.add(player.getViewVector(1.0F).scale(player.blockInteractionRange() + 1.0D));

        try
        {
            return mc.level.getBlockState(pos).getShape(mc.level, pos).clip(eyes, end, pos);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Vec3 spot(BlockPos pos, Direction face, boolean upperHalf)
    {
        double y = upperHalf
                ? pos.getY() + 0.9D
                : pos.getY() + 0.5D + face.getStepY() * 0.5D;

        return new Vec3(pos.getX() + 0.5D + face.getStepX() * 0.5D, y,
                pos.getZ() + 0.5D + face.getStepZ() * 0.5D);
    }

    private static @Nullable InteractionHand handWith(LocalPlayer player,
            @Nullable Predicate<ItemStack> tool)
    {
        return Hands.with(player, tool);
    }
}
