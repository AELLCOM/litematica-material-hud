package com.aell.layerlist.ghost;

import com.aell.layerlist.easyplace.Hands;
import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.easyplace.StateFix;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Голограмма клетки, которую фильтры мода не рисуют: «только блоки из инвентаря» ({@link InventoryGhosts}) и
 * «скрывать блоки без опоры» ({@link GhostSupport}). Те же правила, что у сборки чанка, — для прицела: луч
 * Litematica и доводка прицела проходят такую клетку насквозь, как пустую, иначе невидимая голограмма
 * перехватывала бы клик у видимой за ней. Только недостроенные клетки (в мире не тот блок, что в схеме):
 * готовая клетка и так ничего не рисует, а прицел у неё — как у Litematica. Клиентский поток.
 */
@Environment(EnvType.CLIENT)
public final class HiddenGhosts
{
    /** Срез мира игрока у чанка, который сейчас собирается в этом потоке (для отсечения граней). */
    private static final ThreadLocal<BlockGetter> BUILDING = new ThreadLocal<>();

    private HiddenGhosts() {}

    public static void building(@org.jetbrains.annotations.Nullable BlockGetter clientView)
    {
        if (clientView == null)
        {
            BUILDING.remove();
        }
        else
        {
            BUILDING.set(clientView);
        }
    }

    /**
     * Сборка чанка: соседнюю клетку фильтр не рисует, значит, грань к ней видна — не отсекать её о блок
     * схемы, которого на экране нет (иначе в непрозрачной голограмме дыра). Рабочий поток сборки.
     */
    public static boolean hiddenNeighbor(BlockGetter schematicView, BlockPos pos)
    {
        BlockGetter client = BUILDING.get();

        if (client == null)
        {
            return false;
        }

        BlockState schematic = schematicView.getBlockState(pos);
        BlockState have = client.getBlockState(pos);

        if (schematic.isAir() || schematic == have)
        {
            return false;
        }

        if (InventoryGhosts.active()
                && InventoryGhosts.hides(Potting.needsPlant(schematic, have) ? Potting.plantState(schematic) : schematic)
                && ! finishableInBuild(schematic, have, pos))
        {
            return true;
        }

        return GhostSupport.hideUnsupported() && GhostSupport.unsupported(schematic, have, pos, client, schematicView);
    }

    /** Хоть один фильтр включён — иначе проверять нечего. */
    public static boolean any()
    {
        return InventoryGhosts.active() || GhostSupport.hideUnsupported();
    }

    public static boolean hidden(BlockPos pos)
    {
        Minecraft mc = Minecraft.getInstance();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

        if (! any() || mc.level == null || schematicWorld == null)
        {
            return false;
        }

        return hidden(schematicWorld.getBlockState(pos), mc.level.getBlockState(pos), pos, mc.level, schematicWorld);
    }

    public static boolean hidden(BlockState schematic, BlockState client, BlockPos pos, BlockGetter level,
            WorldSchematic schematicWorld)
    {
        if (schematic.isAir() || schematic == client)
        {
            return false;
        }

        if (InventoryGhosts.active())
        {
            // Пустой горшок уже стоит — нужен только цветок, по нему и решаем (как при сборке чанка)
            BlockState needed = Potting.needsPlant(schematic, client) ? Potting.plantState(schematic) : schematic;

            if (! InventoryGhosts.available(needed) && ! finishable(schematic, client, pos, level, schematicWorld))
            {
                return true;
            }
        }

        return GhostSupport.hideUnsupported() && GhostSupport.unsupported(schematic, client, pos, level, schematicWorld);
    }

    /**
     * Доводка на месте: в клетке уже стоит блок, у плана доводки есть ход (костёр потушить лопатой, лозу
     * подстричь, тыкву вырезать ножницами, медь соскоблить), и инструмент у игрока есть. Такую клетку
     * фильтры голограмм не прячут — доводка работает при любом режиме. Без инструмента клетка прячется,
     * как раньше: невидимая голограмма не должна перехватывать прицел у видимой за ней. Клиентский поток.
     */
    public static boolean finishable(BlockState schematic, BlockState client, BlockPos pos, BlockGetter level,
            @org.jetbrains.annotations.Nullable Level schematicWorld)
    {
        Minecraft mc = Minecraft.getInstance();

        if (client.isAir() || schematic == client || mc.player == null)
        {
            return false;
        }

        StateFix.Fix fix;

        try
        {
            fix = level instanceof Level world ? StateFix.plan(world, schematicWorld, pos.immutable(), schematic, client)
                    : StateFix.plan(schematic, client);
        }
        catch (RuntimeException e)
        {
            return false;
        }

        return fix != null && (fix.tool() == null || Hands.has(mc.player, fix.tool()));
    }

    /**
     * То же для сборки чанка (рабочий поток): план — по паре состояний, инструмент — по снимку инвентаря.
     * Клетка, видимость которой зависит от инструмента, запоминается: появился или пропал инструмент —
     * её чанк пересоберётся.
     */
    public static boolean finishableInBuild(BlockState schematic, BlockState client, BlockPos pos)
    {
        if (client.isAir() || schematic == client)
        {
            return false;
        }

        StateFix.Fix fix;

        try
        {
            fix = StateFix.plan(schematic, client);
        }
        catch (RuntimeException e)
        {
            return false;
        }

        if (fix == null)
        {
            return false;
        }

        if (fix.tool() == null)
        {
            return true;
        }

        InventoryGhosts.noteToolCell(pos);
        return InventoryGhosts.holds(fix.tool());
    }
}
