package com.aell.layerlist.core;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Знает ли клиент, что стоит в этой точке из сущностей. Чанк может быть загружен, а сущностей в нём у клиента нет:
 * сервер присылает их только в своём радиусе вокруг игрока, и он меньше дальности чанков (жалоба 16.11.2026:
 * поставленные подушки издалека светились «пропущено»). Дальше радиуса сверять не с чем — ответ «неизвестно».
 *
 * <p>В одиночной игре радиус известен точно: {@code clientTrackingRange} типа × настройка «Дальность сущностей»,
 * не дальше прорисовки. На сервере его задаёт сервер (у Spigot/Paper/Purpur — {@code entity-tracking-range},
 * обычно 32–64 блока), и клиенту он не сообщается. Там радиус — самое дальнее расстояние, на котором сейчас видна
 * сущность этого типа, но не меньше {@link #REMOTE_FLOOR}: дальше него клиент ничего не утверждает.
 */
@Environment(EnvType.CLIENT)
public final class EntityReach
{
    /** На сервере ближе этого сущности есть у клиента почти всегда: меньше радиус не ставят. */
    private static final double REMOTE_FLOOR = 16.0D;
    /** Запас на движение: у самой границы сущность появляется и пропадает с опозданием в несколько тиков. */
    private static final double MARGIN = 4.0D;
    private static final int SAMPLE_TICKS = 20;

    /** Тип → самое дальнее расстояние по горизонтали до видимой сущности: нынешний замер и прошлый. */
    private static final Map<EntityType<?>, Double> NOW = new HashMap<>();
    private static final Map<EntityType<?>, Double> BEFORE = new HashMap<>();
    private static Object level;
    private static long sampledAt = Long.MIN_VALUE;

    /** Для стенда: считать радиус, как на сервере, и в одиночной игре. */
    public static boolean forceRemote;

    private EntityReach() {}

    /** Сущность такого типа в этой точке, будь она в мире, была бы сейчас у клиента. */
    public static boolean known(Minecraft mc, EntityType<?> type, Vec3 pos)
    {
        if (mc.player == null || mc.level == null)
        {
            return false;
        }

        double dx = pos.x - mc.player.getX();
        double dz = pos.z - mc.player.getZ();
        double reach = range(mc, type) - MARGIN;

        return reach > 0.0D && dx * dx + dz * dz <= reach * reach;
    }

    /** Радиус (по горизонтали, блоки), в котором сервер держит у клиента сущности этого типа. */
    public static double range(Minecraft mc, EntityType<?> type)
    {
        double vanilla = type.clientTrackingRange() * 16.0D;
        double view = mc.options.getEffectiveRenderDistance() * 16.0D;

        if (mc.hasSingleplayerServer() && ! forceRemote)
        {
            return Math.min((int) (vanilla * mc.options.entityDistanceScaling().get()), view);
        }

        sample(mc);
        // Два замера подряд: сразу после телепорта прежние сущности ещё не убраны и «видны» за сотни блоков
        double seen = Math.min(NOW.getOrDefault(type, 0.0D), BEFORE.getOrDefault(type, 0.0D));

        return Math.min(Math.min(vanilla, view), Math.max(REMOTE_FLOOR, seen));
    }

    private static void sample(Minecraft mc)
    {
        long tick = mc.level.getGameTime();

        if (mc.level != level)
        {
            level = mc.level;
            NOW.clear();
            BEFORE.clear();
        }
        else if (Math.abs(tick - sampledAt) < SAMPLE_TICKS)
        {
            return;
        }

        sampledAt = tick;
        BEFORE.clear();
        BEFORE.putAll(NOW);
        NOW.clear();

        for (Entity entity : mc.level.entitiesForRendering())
        {
            if (entity == mc.player || entity.isRemoved())
            {
                continue;
            }

            double dx = entity.getX() - mc.player.getX();
            double dz = entity.getZ() - mc.player.getZ();
            NOW.merge(entity.getType(), Math.sqrt(dx * dx + dz * dz), Math::max);
        }
    }
}
