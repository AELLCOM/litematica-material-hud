package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * «Склад только рядом» (заявка #4 на GitHub, 15.11.2026: «строю — значит беру из сундуков вокруг, а не из далёких; и на
 * сервере в счёт идут чужие сундуки, которые я когда-то открыл»). С опцией склад («В сундуках», «С учётом склада» в HUD
 * и таблице) — только хранилища не дальше {@link LayerListConfigs#STOCK_RADIUS} блоков от игрока по горизонтали, как
 * радиус поиска. Эндер-сундук не ограничивается: его содержимое с игроком везде.
 *
 * <p>Игрок ходит — круг идёт с ним. Раз в секунду {@link #onClientTick} смотрит, какие известные хранилища сейчас в
 * круге, и если набор сменился, поднимает ревизию индекса ({@link StorageIndex#touch}): HUD и таблица пересчитают склад.
 */
@Environment(EnvType.CLIENT)
public final class StockArea
{
    private static final int CHECK_EVERY_TICKS = 20;

    private static int ticks;
    /** Отпечаток набора хранилищ в круге при прошлой проверке. */
    private static long lastPrint;

    private StockArea() {}

    public static boolean active()
    {
        return LayerListConfigs.STOCK_NEAR_ONLY.getBooleanValue();
    }

    public static int radius()
    {
        return LayerListConfigs.STOCK_RADIUS.getIntegerValue();
    }

    /** Хранилище в счёте склада по расстоянию: опция выключена — любое. */
    public static boolean inRange(Minecraft mc, BlockPos pos)
    {
        if (! active() || mc.player == null)
        {
            return true;
        }

        double dx = pos.getX() + 0.5D - mc.player.getX();
        double dz = pos.getZ() + 0.5D - mc.player.getZ();
        double r = radius();
        return dx * dx + dz * dz <= r * r;
    }

    public static void onClientTick(Minecraft mc)
    {
        if (++ticks < CHECK_EVERY_TICKS)
        {
            return;
        }

        ticks = 0;

        if (! active() || mc.level == null || mc.player == null)
        {
            lastPrint = 0L;
            return;
        }

        String scope = StorageIndex.scope(mc);
        List<StorageIndex.Rec> records = StorageIndex.getInstance().recordsInScope(scope);
        long print = 1L;

        for (StorageIndex.Rec rec : records)
        {
            if (inRange(mc, rec.pos()))
            {
                print = print * 31L + rec.pos().asLong();
            }
        }

        if (print != lastPrint)
        {
            boolean first = lastPrint == 0L;
            lastPrint = print;

            if (! first)
            {
                StorageIndex.getInstance().touch();
            }
        }
    }

    /** Сменились опция или радиус — пересчитать склад сразу, не дожидаясь шага. */
    public static void changed()
    {
        lastPrint = 0L;
        StorageIndex.getInstance().touch();
    }
}
