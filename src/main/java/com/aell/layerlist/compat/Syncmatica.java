package com.aell.layerlist.compat;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Syncmatica — общие для сервера схемы — во вкладках «Библиотека» и «Размещения». Без её типов: всё с ними живёт в
 * {@link SyncmaticaImpl}, который грузится, только если мод стоит. Нет Syncmatica или не подключились к серверу с
 * ней — {@link #active()} ложь, кнопки скрыты. Сбой вызова не роняет экран — пишется в лог один раз.
 */
@Environment(EnvType.CLIENT)
public final class Syncmatica
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("syncmatica");
    private static boolean warned;

    /**
     * Схема на сервере.
     *
     * @param handle      сам {@code ServerPlacement} — передавать обратно в методы
     * @param rendered    загружена у себя (есть размещение Litematica)
     * @param fileReady   файл уже скачан
     * @param downloading качается сейчас
     */
    public record Remote(Object handle, String name, String file, String owner, BlockPos origin, String dimension,
            boolean rendered, boolean fileReady, boolean downloading) {}

    private Syncmatica() {}

    /** Мод стоит. */
    public static boolean installed()
    {
        return INSTALLED;
    }

    /** Мод стоит и связь с сервером Syncmatica поднята: можно делиться и смотреть схемы сервера. */
    public static boolean active()
    {
        return INSTALLED && guard(SyncmaticaImpl::active, false);
    }

    /** Размещение — общее (пришло с сервера или им поделились). */
    public static boolean shared(@Nullable SchematicPlacement placement)
    {
        return placement != null && INSTALLED && guard(() -> SyncmaticaImpl.shared(placement), false);
    }

    /**
     * Поделиться размещением на сервере. {@code null} — запрос ушёл; иначе ключ причины отказа
     * ({@code layerlist.schematics.sync.error.*}).
     */
    public static @Nullable String share(SchematicPlacement placement)
    {
        return INSTALLED ? guard(() -> SyncmaticaImpl.share(placement), "layerlist.schematics.sync.error.failed")
                : "layerlist.schematics.sync.error.inactive";
    }

    /** Убрать общее размещение у себя (на сервере остаётся). */
    public static void hide(SchematicPlacement placement)
    {
        run(() -> SyncmaticaImpl.hide(placement));
    }

    /** Убрать общее размещение с сервера — у всех. */
    public static void removeFromServer(SchematicPlacement placement)
    {
        run(() -> SyncmaticaImpl.removeFromServer(placement));
    }

    /** Схемы сервера, по имени. */
    public static List<Remote> remote()
    {
        return INSTALLED ? guard(SyncmaticaImpl::remote, List.of()) : List.of();
    }

    /** Общее размещение под это размещение Litematica или {@code null}. */
    public static @Nullable Remote remoteOf(SchematicPlacement placement)
    {
        return INSTALLED ? guard(() -> SyncmaticaImpl.remoteOf(placement), null) : null;
    }

    public static void load(Remote remote)
    {
        run(() -> SyncmaticaImpl.load(remote.handle()));
    }

    public static void unload(Remote remote)
    {
        run(() -> SyncmaticaImpl.unload(remote.handle()));
    }

    public static void download(Remote remote)
    {
        run(() -> SyncmaticaImpl.download(remote.handle()));
    }

    public static void remove(Remote remote)
    {
        run(() -> SyncmaticaImpl.remove(remote.handle()));
    }

    /**
     * Общее размещение заперто, пока сервер не разрешит правку: запросить. {@code null} — запрос ушёл, ответ
     * отопрёт размещение ({@link #editable}); иначе ключ причины отказа.
     */
    public static @Nullable String requestEdit(SchematicPlacement placement)
    {
        return INSTALLED ? guard(() -> SyncmaticaImpl.requestEdit(placement), "layerlist.schematics.sync.error.failed")
                : "layerlist.schematics.sync.error.inactive";
    }

    /** Сервер разрешил правку: размещение отперто и правка ещё не отправлена. */
    public static boolean editable(SchematicPlacement placement)
    {
        return INSTALLED && guard(() -> SyncmaticaImpl.editable(placement), false);
    }

    /** Отправить правку на сервер (размещение снова запирается). */
    public static void finishEdit(SchematicPlacement placement)
    {
        run(() -> SyncmaticaImpl.finishEdit(placement));
    }

    private interface Call<T>
    {
        T get() throws Exception;
    }

    private static <T> T guard(Call<T> call, T fallback)
    {
        try
        {
            return call.get();
        }
        catch (Exception | LinkageError e)
        {
            if (! warned)
            {
                warned = true;
                LOGGER.warn("[layerlist] Syncmatica: вызов не прошёл", e);
            }

            return fallback;
        }
    }

    private static void run(Runnable action)
    {
        if (INSTALLED)
        {
            guard(() -> { action.run(); return null; }, null);
        }
    }
}
