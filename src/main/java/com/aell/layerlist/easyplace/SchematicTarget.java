package com.aell.layerlist.easyplace;

import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Есть ли Easy Place работа в этой клетке.
 *
 * Повторяет условия, при которых {@code EasyPlaceUtils.handleEasyPlace} выходит с FAIL,
 * ничего не сделав: клетка пуста в схеме, уже совпадает со схемой или требуемого предмета
 * для неё не существует (верхняя половина двери, изголовье кровати, верх высокой травы).
 */
@Environment(EnvType.CLIENT)
public final class SchematicTarget
{
    private SchematicTarget() {}

    /**
     * Клетке ещё нужен клик, и мы не ждём подтверждения сервером уже отправленного.
     * Снимает кеш Litematica «сюда уже кликали» с клеток, чей клик погасил темп
     * («Блоков в секунду»): иначе отложенная клетка считалась бы сделанной ~2 секунды.
     */
    public static boolean needsClick(BlockPos pos)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || ! actionable(pos))
        {
            return false;
        }

        return ! AutoPlaceState.isConfirmationPending(pos, mc.level.getBlockState(pos),
                com.aell.layerlist.config.LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue());
    }

    public static boolean actionable(BlockPos pos)
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        WorldSchematic schematic = schematicWorld();

        if (level == null || schematic == null)
        {
            return true;
        }

        BlockState wanted = schematic.getBlockState(pos);
        BlockState world = level.getBlockState(pos);

        if (wanted.isAir() || wanted == world || Stairs.sameShape(wanted, world)
                || GrowingPlants.satisfies(wanted, world)
                // Клетка лозы, у которой опора ещё не построена: клик сюда ждал бы её вечно
                // (PlacementGuard.decidePlant). Для прицела — тупик: луч идёт дальше, к клетке
                // той же колонны с опорой. Глядя на лозу снизу, взгляд сначала проходит её
                // нижние пустые клетки — раньше Easy Place на них и застревал (0.0.23)
                || GrowingPlants.awaitingSupport(level, pos, wanted, world)
                || DoubleChest.awaitingPartner(wanted, world)
                // Стоящий рельс не той формы: работы здесь нет — форму задают соседи,
                // а прицел, залипший на такой клетке, не давал бы дойти до следующей
                || Rails.onlyShapeDiffers(wanted, world)
                // То же у забора, ограды, стены, панели, решётки и провода: пока сосед
                // по линии не построен, стоящий блок отличается от схемы соединениями.
                // Клик сюда ничего не исправит (соединения задаёт окружение), а попадёт
                // мимо: клетка занята, и установка съедет на клетку за гранью клика —
                // над забором вырастет лишний
                || Connections.onlyConnectionsDiffer(wanted, world)
                // Общий случай тех же двух: блок стоит, а вся разница со схемой лежит вне
                // того, что задаёт установка. Так выглядят ступени с недосчитанной формой,
                // трава со snowy и листва с distance — ровно то, на чём мод снимал
                // блокировку Litematica и ронял лишний блок на клетку выше
                // Земля вместо дёрна по тумблеру «Ставить землю, если схема просит дёрн»
                || GrassSwap.satisfies(wanted, world)
                || Controllable.placementCannotChange(wanted, world)
                // Тот же блок, но повёрнут не так: кликом не повернуть, а сломать за игрока
                // мы не вправе. Конвейер такую клетку пропускает (PlacementGuard
                // .skipWrongRotation) — прицел обязан согласиться, иначе он залипнет на ней
                // и стройка встанет на первом же неверно повёрнутом блоке.
                || (com.aell.layerlist.config.LayerListConfigs.EP_SKIP_WRONG.getBooleanValue() && ! world.canBeReplaced()
                        && Controllable.placementOnlyDiffers(wanted, world)))
        {
            return false;
        }

        return hasItem(schematic, pos, wanted);
    }

    /**
     * Клетка внутри области какого-нибудь включённого размещения схемы.
     * Тем же способом, что и Litematica: части размещений, чьи боксы содержат позицию.
     */
    public static boolean insideSchematic(BlockPos pos)
    {
        try
        {
            for (fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart part
                    : fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager()
                            .getAllPlacementsTouchingChunk(pos))
            {
                if (part.getBox().contains(pos))
                {
                    return true;
                }
            }

            return false;
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    public static boolean hasItem(WorldSchematic schematic, BlockPos pos, BlockState wanted)
    {
        try
        {
            ItemStack stack = MaterialCache.getInstance()
                    .getRequiredBuildItemForState(wanted, schematic, pos);

            return stack != null && ! stack.isEmpty();
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
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
