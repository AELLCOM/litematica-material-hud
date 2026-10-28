package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class StateRepair
{
    private StateRepair() {}

    public static boolean run()
    {
        if (! EasyPlaceModule.isActive())
        {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        WorldSchematic schematic = schematicWorld();

        if (player == null || level == null || schematic == null)
        {
            return false;
        }

        BlockHitResult trace = target(mc);

        if (trace == null)
        {
            return false;
        }

        BlockPos pos = trace.getBlockPos();
        BlockState wanted = schematic.getBlockState(pos);
        BlockState world = level.getBlockState(pos);

        // Растение в уже стоящем пустом горшке, необтёсанное бревно под топор и остальные
        // ванильные пары «блок A + инструмент → блок B» (медь, грядка, тропинка, свеча на
        // торт) — случаи, когда блок в мире и блок в схеме разные, а клик всё равно доводит
        // клетку до схемы. Совпадающие состояния НЕ отсекаем: у полки с предметами вся
        // разница живёт в block entity — про неё знает только план.
        if (wanted.isAir()
                || (! world.is(wanted.getBlock()) && ! Potting.needsPlant(wanted, world)
                        && ! Stripping.needsStrip(wanted, world)
                        && Conversions.fix(wanted, world) == null
                        && ! StateFix.fillsCauldron(wanted, world)))
        {
            return false;
        }

        if (countedFirst(wanted, world))
        {
            return false;
        }

        StateFix.Fix plan = StateFix.plan(level, schematic, pos, wanted, world);

        if (plan == null)
        {
            return false;
        }

        if (AutoPlaceState.isConfirmationPending(pos, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            EpDebug.say("починка '" + plan.id() + "' " + EpDebug.at(pos)
                    + ": жду подтверждения сервером");
            return true;
        }

        // Темп. Этот путь стоит в самом начале обработчика Easy Place, до всего остального,
        // и раньше шёл мимо «Блоков в секунду» целиком: в тике, где уже прошла установка,
        // он добавлял второе взаимодействие и ещё пару пакетов поворота. Бюджет у починки и
        // у установки теперь общий, то есть за интервал уходит ровно одно действие.
        if (Pacing.tooSoon())
        {
            EpDebug.say("починка '" + plan.id() + "' " + EpDebug.at(pos)
                    + ": темп («Блоков в секунду»)");
            return false;
        }

        EpDebug.say("починка '" + plan.id() + "' " + EpDebug.at(pos) + ": мир "
                + EpDebug.name(world) + " → схема " + EpDebug.name(wanted));

        boolean fixed = StateFixer.run(mc, player, pos, wanted, world, trace.getDirection());

        if (fixed)
        {
            Pacing.mark();
        }

        return fixed;
    }

    /**
     * Клетке ещё нужен клик-починка. Litematica держит кеш «сюда уже кликали» и второй
     * раз по той же позиции не бьёт — из-за него дверь оставалась закрытой, а бревно
     * необтёсанным до следующего прохода прицелом.
     */
    public static boolean needsFix(BlockPos pos)
    {
        if (! EasyPlaceModule.isActive())
        {
            return false;
        }

        try
        {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            WorldSchematic schematic = schematicWorld();

            if (level == null || schematic == null)
            {
                return false;
            }

            BlockState wanted = schematic.getBlockState(pos);
            BlockState world = level.getBlockState(pos);

            if (wanted.isAir()
                    || StateFix.plan(level, schematic, pos, wanted, world) == null)
            {
                return false;
            }

            return ! AutoPlaceState.isConfirmationPending(pos, world,
                    LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue());
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static boolean countedFirst(BlockState wanted, BlockState world)
    {
        var property = BlockFamilies.countProperty(wanted);

        if (property != null)
        {
            return BlockFamilies.count(world, property) < BlockFamilies.count(wanted, property);
        }

        return BlockFamilies.isMultiface(wanted);
    }

    /** Клетка схемы под прицелом Easy Place — та же трассировка, что у Litematica. */
    static @Nullable BlockHitResult target(Minecraft mc)
    {
        try
        {
            double range = EasyPlaceUtils.getValidBlockRange(mc);
            RayTraceUtils.RayTraceWrapper wrapper =
                    Configs.Generic.EASY_PLACE_FIRST.getBooleanValue()
                            ? RayTraceUtils.getGenericTrace(mc.level, mc.player, range)
                            : RayTraceUtils.getFurthestSchematicWorldTraceBeforeVanilla(mc.level,
                                    mc.player, range);

            if (wrapper == null
                    || wrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
            {
                return null;
            }

            return wrapper.getBlockHitResult();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
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
