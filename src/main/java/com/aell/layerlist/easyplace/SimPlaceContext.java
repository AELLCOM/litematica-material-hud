package com.aell.layerlist.easyplace;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class SimPlaceContext extends BlockPlaceContext
{
    private static @Nullable LookSpoof.Pose incoming;
    private static float incomingHead;

    private final @Nullable LookSpoof.Pose pose;
    private final float headYaw;

    private SimPlaceContext(Level level, Player player, InteractionHand hand, ItemStack stack,
            BlockHitResult hit, LookSpoof.Pose pose, float headYaw)
    {
        super(level, player, hand, stack, hit);

        this.pose = pose;
        this.headYaw = headYaw;
    }

    public static SimPlaceContext of(Level level, Player player, InteractionHand hand, ItemStack stack,
            BlockHitResult hit, LookSpoof.Pose pose)
    {
        return of(level, player, hand, stack, hit, pose, pose.yaw());
    }

    /**
     * Клик, каким его разберёт сервер, у которого голова игрока ещё повёрнута на
     * {@code headYaw}, а тело — уже на угол позы. Так и бывает: пакет поворота сервер
     * применяет к {@code yRot} сразу, а {@code yHeadRot} догоняет его только в тике игрока
     * ({@code Player.aiStep}). Ближайшее направление взгляда — то, по которому встают
     * наблюдатель, поршень, раздатчик, бочка, — ваниль берёт из головы.
     */
    public static SimPlaceContext of(Level level, Player player, InteractionHand hand, ItemStack stack,
            BlockHitResult hit, LookSpoof.Pose pose, float headYaw)
    {
        incoming = pose;
        incomingHead = headYaw;

        try
        {
            return new SimPlaceContext(level, player, hand, stack, hit, pose, headYaw);
        }
        finally
        {
            incoming = null;
        }
    }

    private LookSpoof.Pose pose()
    {
        return this.pose != null ? this.pose : incoming;
    }

    private float headYaw()
    {
        return this.pose != null ? this.headYaw : incomingHead;
    }

    @Override
    public boolean isSecondaryUseActive()
    {
        return this.pose().sneak();
    }

    @Override
    public float getRotation()
    {
        return this.pose().yaw();
    }

    @Override
    public Direction getHorizontalDirection()
    {
        return Direction.fromYRot(this.pose().yaw());
    }

    @Override
    public Direction getNearestLookingDirection()
    {
        return this.ordered()[0];
    }

    @Override
    public Direction getNearestLookingVerticalDirection()
    {
        return this.pose().pitch() < 0.0F ? Direction.UP : Direction.DOWN;
    }

    @Override
    public Direction[] getNearestLookingDirections()
    {
        Direction[] directions = this.ordered();

        if (this.replacingClickedOnBlock())
        {
            return directions;
        }

        Direction back = this.getClickedFace().getOpposite();
        int index = 0;

        while (index < directions.length && directions[index] != back)
        {
            ++index;
        }

        if (index > 0 && index < directions.length)
        {
            System.arraycopy(directions, 0, directions, 1, index);
            directions[0] = back;
        }

        return directions;
    }

    /**
     * Шесть направлений от самого близкого к взгляду к самому далёкому — так, как их перебирает ваниль
     * для «ближайшего направления взгляда». Наклон — из позы (тело, {@code getViewXRot}), поворот — из
     * головы ({@code getViewYRot} у живых сущностей отдаёт голову).
     *
     * <p>Собирается вставкой: из двух горизонтальных осей вперёд идёт та, к которой поворот ближе (при
     * равенстве — север–юг); вертикаль встаёт перед ней, если наклон круче её доли, после второй
     * горизонтали — если положе той, иначе между ними. Вторая половина — противоположные стороны в
     * обратном порядке. Доли считаются через {@code Mth.sin/cos} теми же шагами float, что и в ванили,
     * иначе на равенствах (ровно 45°, ровно вверх) порядок расходился бы.
     */
    private Direction[] ordered()
    {
        // Вертикаль: куда смотрит и какая доля взгляда приходится на неё
        float pitch = this.pose().pitch() * Mth.DEG_TO_RAD;
        float rise = Mth.sin(pitch);
        Direction vertical = rise < 0.0F ? Direction.UP : Direction.DOWN;
        float verticalShare = Math.abs(rise);
        float level = Mth.cos(pitch);

        // Горизонталь: доли по осям x и z — от поворота головы, ужатые наклоном
        float yaw = -this.headYaw() * Mth.DEG_TO_RAD;
        float toEast = Mth.sin(yaw);
        Direction alongX = toEast > 0.0F ? Direction.EAST : Direction.WEST;
        float toSouth = Mth.cos(yaw);
        Direction alongZ = toSouth > 0.0F ? Direction.SOUTH : Direction.NORTH;
        boolean xFirst = Math.abs(toEast) > Math.abs(toSouth);
        float xShare = Math.abs(toEast) * level;
        float zShare = Math.abs(toSouth) * level;

        Direction lead = xFirst ? alongX : alongZ;
        Direction trail = xFirst ? alongZ : alongX;
        float leadShare = xFirst ? xShare : zShare;
        float trailShare = xFirst ? zShare : xShare;
        int verticalAt = verticalShare > leadShare ? 0 : trailShare > verticalShare ? 2 : 1;

        Direction[] near = new Direction[3];
        near[verticalAt] = vertical;
        near[verticalAt == 0 ? 1 : 0] = lead;
        near[verticalAt == 2 ? 1 : 2] = trail;

        Direction[] all = new Direction[6];

        for (int i = 0; i < 3; ++i)
        {
            all[i] = near[i];
            all[5 - i] = near[i].getOpposite();
        }

        return all;
    }

    public @Nullable BlockState placementState()
    {
        if (! this.canPlace())
        {
            return null;
        }

        BlockPlaceContext updated = this.getItemInHand().getItem() instanceof net.minecraft.world.item.BlockItem item
                ? item.updatePlacementContext(this)
                : this;

        return updated == null ? null : BlockItemAccess.placementState(this.getItemInHand(), updated);
    }
}
