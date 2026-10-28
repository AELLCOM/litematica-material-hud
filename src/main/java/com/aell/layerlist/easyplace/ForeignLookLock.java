package com.aell.layerlist.easyplace;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

/**
 * Блокировка look-пакетов стороннего EasyPlaceFix: пока она взведена, его миксин в
 * {@code ServerboundMovePlayerPacket} переписывает yaw/pitch всех исходящих пакетов поворота,
 * и наш {@code LookSpoof} до сервера не доходит. Состояние читается рефлексией и только на
 * чтение; если в будущей версии мода не окажется ни метода, ни поля, {@link #armed()} тихо
 * отвечает {@code false} — тогда достаточно минимальной выдержки в {@code DeferredPlace}
 * (блокировка живёт один тик).
 *
 * <p>С 0.6.5 у блокировки есть срок годности: {@code isLookLocked()} гасит её через 1,5 с,
 * если задача снятия потерялась. Читаем именно его, а не сырой флаг {@code notChangPlayerLook},
 * который в таком случае остаётся взведённым навсегда — иначе отложенные установки ждали бы
 * зависшую блокировку до истечения собственного лимита и молча пропадали.
 */
@Environment(EnvType.CLIENT)
public final class ForeignLookLock
{
    private static final String MOD_ID = "easyplacefix";
    private static final String OWNER = "com.tick_ins.tick.TickThread";
    private static final @Nullable Method LOCKED = resolveMethod();
    private static final @Nullable Field FLAG = LOCKED == null ? resolveField() : null;

    private ForeignLookLock() {}

    private static boolean present()
    {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    private static @Nullable Method resolveMethod()
    {
        if (! present())
        {
            return null;
        }

        try
        {
            return Class.forName(OWNER).getMethod("isLookLocked");
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            return null;
        }
    }

    private static @Nullable Field resolveField()
    {
        if (! present())
        {
            return null;
        }

        try
        {
            return Class.forName(OWNER).getField("notChangPlayerLook");
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            return null;
        }
    }

    public static boolean armed()
    {
        try
        {
            if (LOCKED != null)
            {
                return Boolean.TRUE.equals(LOCKED.invoke(null));
            }

            return FLAG != null && FLAG.getBoolean(null);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return false;
        }
    }
}
