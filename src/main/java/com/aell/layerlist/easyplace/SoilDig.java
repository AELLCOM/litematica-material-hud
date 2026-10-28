package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * «Заменять почву». Земля, дёрн, каменистая земля, тропинка, корневая земля, подзол и
 * мицелий — дешёвые блоки без ценного дропа. Если в клетке стоит один из них, а схема просит
 * другой, обычный Easy Place упирается в «клетка занята». Мод выкапывает лишний блок теми же
 * вызовами, что и удерж левой кнопки, а следующий проход Easy Place ставит нужный.
 *
 * <p>Где блок доводится на месте инструментом (мотыга — грядка, лопата — тропинка), копать
 * не надо: это делает {@link Conversions} через починку состояний, и она идёт первой.
 * Раскопки ведутся по одной клетке за раз и обрываются, если сервер не отдал блок за
 * {@link #MAX_TICKS} тиков — вне досягаемости, защищённая территория, рассинхрон.
 */
@Environment(EnvType.CLIENT)
public final class SoilDig
{
    private static final Set<Block> SOIL = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
            Blocks.DIRT_PATH, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM);

    private static final int MAX_TICKS = 80;

    private static @Nullable BlockPos pos;
    private static Direction face = Direction.UP;
    private static int ticks;

    private SoilDig() {}

    /**
     * Вызывается в начале обработчика Easy Place, после починки состояний. true — клетка под
     * прицелом взята в раскопки (или уже копается), и Litematica кликать не нужно.
     */
    public static boolean run()
    {
        if (! LayerListConfigs.EP_REPLACE_SOIL.getBooleanValue() || ! EasyPlaceModule.isActive())
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        WorldSchematic schematic = schematicWorld();

        if (mc.player == null || level == null || mc.gameMode == null || schematic == null)
        {
            return false;
        }

        BlockHitResult trace = StateRepair.target(mc);

        if (trace == null)
        {
            return false;
        }

        BlockPos target = trace.getBlockPos();

        if (pos != null)
        {
            return pos.equals(target);
        }

        BlockState wanted = schematic.getBlockState(target);
        BlockState world = level.getBlockState(target);

        if (! eligible(wanted, world) || Conversions.fix(wanted, world) != null)
        {
            return false;
        }

        pos = target.immutable();
        face = trace.getDirection();
        ticks = 0;

        EpDebug.say("почва " + EpDebug.at(pos) + ": мир " + EpDebug.name(world) + " → схема "
                + EpDebug.name(wanted) + ", выкапываю");

        mc.gameMode.startDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

        return true;
    }

    public static void onClientTick(Minecraft mc)
    {
        if (pos == null)
        {
            return;
        }

        if (mc.level == null || mc.player == null || mc.gameMode == null || ! EasyPlaceModule.isActive()
                || ! LayerListConfigs.EP_REPLACE_SOIL.getBooleanValue())
        {
            clear();

            return;
        }

        if (! SOIL.contains(mc.level.getBlockState(pos).getBlock()))
        {
            // Выкопано — следующий проход Easy Place поставит то, что просит схема
            EpDebug.say("почва " + EpDebug.at(pos) + ": клетка свободна");
            clear();

            return;
        }

        if (++ticks > MAX_TICKS)
        {
            mc.gameMode.stopDestroyBlock();
            EpDebug.say("почва " + EpDebug.at(pos) + ": не выкопалось за " + MAX_TICKS + " тиков, бросаю");
            clear();

            return;
        }

        mc.gameMode.continueDestroyBlock(pos, face);
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
    }

    /** Эту клетку сейчас копаем мы. */
    public static boolean digging(BlockPos other)
    {
        return pos != null && pos.equals(other);
    }

    /** Смена мира или выключение модуля: раскопки не переносятся. */
    public static void clear()
    {
        pos = null;
        ticks = 0;
    }

    static boolean eligible(BlockState wanted, BlockState world)
    {
        return SOIL.contains(wanted.getBlock()) && SOIL.contains(world.getBlock())
                && ! world.is(wanted.getBlock());
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
