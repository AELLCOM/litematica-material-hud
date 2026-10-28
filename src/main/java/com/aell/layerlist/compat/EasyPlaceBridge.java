package com.aell.layerlist.compat;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.malilib.config.IConfigBase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Мост для отдельного мода Litematica EasyPlaceFixPlus: у него те же опции Easy Place под теми
 * же именами, и настройки у нас общие. Имя класса и подписи методов — контракт между модами:
 * сосед находит их рефлексией (см. {@code com.aell.ui.SharedOptions}), поэтому переименовывать
 * их нельзя, иначе у людей с обоими модами настройки разойдутся на два набора.
 *
 * <p>Отдаём только {@link LayerListConfigs#EP_OPTIONS} — общие опции Easy Place. Всё остальное
 * (HUD слоёв, поиск по хранилищам, сообщения в чат) сосед не увидит и не перепишет.
 */
@Environment(EnvType.CLIENT)
public final class EasyPlaceBridge
{
    private EasyPlaceBridge() {}

    public static @Nullable IConfigBase option(String name)
    {
        for (IConfigBase config : LayerListConfigs.EP_OPTIONS)
        {
            if (config.getName().equals(name))
            {
                return config;
            }
        }

        return null;
    }

    /**
     * Голограмму клетки Material HUD сейчас не рисует («только блоки из инвентаря», «скрывать блоки без
     * опоры») — доводка прицела соседа её пропускает, как пустую. Клиентский поток.
     */
    public static boolean ghostHidden(net.minecraft.core.BlockPos pos)
    {
        return com.aell.layerlist.ghost.HiddenGhosts.hidden(pos);
    }

    /** Только запись своего файла: ничего у соседа не зовём, чтобы сохранение не зацикливалось. */
    public static void saveOwn()
    {
        new LayerListConfigs().save();
    }
}
