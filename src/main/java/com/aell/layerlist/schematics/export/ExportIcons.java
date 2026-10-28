package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.materials.ItemIcons;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Иконки строк для одной выгрузки: PNG из {@link ItemIcons}, по разу на ключ строки. Если
 * иконку получить не удалось, строка выгружается без картинки — из-за иконки выгрузка не падает.
 *
 * <p>Без набора ({@link #ExportIcons(int)}) берётся {@link ItemIcons#png}: готовая иконка из кеша,
 * а не нарисованная ещё — плашкой цвета (игра рисует иконки в кадре GUI, сразу их не получить).
 * Заготовленный набор ({@link #prepared}, из {@link IconBatch}) только отдаёт готовое и
 * {@link ItemIcons} не зовёт — с ним выгрузку можно вести не на клиентском потоке.
 */
@Environment(EnvType.CLIENT)
final class ExportIcons
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    private final int size;
    private final Map<String, byte[]> cache = new HashMap<>();
    private final boolean prepared;
    private boolean warned;

    ExportIcons(int size)
    {
        this(size, Map.of(), false);
    }

    private ExportIcons(int size, Map<String, byte[]> ready, boolean prepared)
    {
        this.size = size;
        this.cache.putAll(ready);
        this.prepared = prepared;
    }

    /** Только готовые PNG по ключу строки; чего нет — строка без картинки. */
    static ExportIcons prepared(int size, Map<String, byte[]> ready)
    {
        return new ExportIcons(size, ready, true);
    }

    @Nullable
    byte[] png(MaterialRow row)
    {
        byte[] known = this.cache.get(row.key());

        if (known == null && this.prepared)
        {
            return null;
        }

        if (known != null)
        {
            return known.length == 0 ? null : known;
        }

        byte[] bytes;

        try
        {
            bytes = ItemIcons.png(row.stack(), this.size);
        }
        catch (RuntimeException | LinkageError e)
        {
            if (! this.warned)
            {
                this.warned = true;
                LOGGER.warn("Export: no icon for {}, the row goes without it; further icon failures are not logged ({})",
                        row.key(), e.toString());
            }

            bytes = null;
        }

        boolean usable = bytes != null && bytes.length > 8;
        this.cache.put(row.key(), usable ? bytes : new byte[0]);

        return usable ? bytes : null;
    }
}
