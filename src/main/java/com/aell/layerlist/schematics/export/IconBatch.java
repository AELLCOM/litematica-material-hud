package com.aell.layerlist.schematics.export;

import com.aell.layerlist.schematics.materials.ItemIcons;
import com.aell.layerlist.schematics.materials.MaterialRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Иконки строк выгрузки, заказанные заранее. {@link ItemIcons} рисует их игрой в кадре GUI
 * (порциями по бюджету кадра), а пиксели приходят с видеокарты через кадр-два — поэтому окно
 * выгрузки зовёт {@link #step} раз в кадр, пока он не скажет «готово», и тогда отдаёт набор в
 * {@code Exporters.xlsx/google/png/sendToTeamSheet(…, IconBatch)}, которые {@code ItemIcons} уже не
 * трогают и потому могут писать файл в фоне.
 *
 * <p>{@link #step} — только клиентский поток; готовый набор после этого только читается (передача
 * в фоновую задачу через исполнитель даёт нужный порядок памяти).
 */
@Environment(EnvType.CLIENT)
public final class IconBatch
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    private final List<MaterialRow> rows;
    private final List<CompletableFuture<byte[]>> pictures = new ArrayList<>();
    private int done;
    private boolean warned;

    private IconBatch(List<MaterialRow> rows)
    {
        this.rows = rows;
    }

    /** Пустой набор: выгрузка без иконок (или флажок «Иконки предметов» выключен). */
    public static IconBatch none()
    {
        return new IconBatch(List.of());
    }

    /**
     * Иконки всех строк, которые попадут в выгрузку (после «только недостающее»), по одной на
     * ключ строки — порядок как в таблице.
     */
    public static IconBatch of(ExportRequest request)
    {
        Objects.requireNonNull(request, "request");
        Map<String, MaterialRow> unique = new LinkedHashMap<>();

        for (ExportData.Line line : ExportData.of(request, LocalDateTime.now()).lines())
        {
            unique.putIfAbsent(line.row().key(), line.row());
        }

        return new IconBatch(List.copyOf(unique.values()));
    }

    public int total()
    {
        return this.rows.size();
    }

    /** Сколько иконок уже пришло. */
    public int done()
    {
        return this.done;
    }

    public boolean finished()
    {
        return this.done >= this.rows.size();
    }

    /**
     * Кадр окна: при первом вызове — заказать иконки всех строк (из кеша — сразу), дальше —
     * попросить кадр GUI рисовать очередь, отдавая ей до {@code budgetMs} миллисекунд, и пересчитать
     * пришедшие. Только клиентский поток.
     *
     * @return {@code true} — все иконки готовы
     */
    public boolean step(long budgetMs)
    {
        if (this.finished())
        {
            return true;
        }

        // Заявки и кеш ItemIcons живут на клиентском потоке
        if (! Minecraft.getInstance().isSameThread())
        {
            throw new IllegalStateException("IconBatch.step must run on the client thread");
        }

        if (this.pictures.isEmpty())
        {
            for (MaterialRow row : this.rows)
            {
                this.pictures.add(this.order(row));
            }
        }

        ItemIcons.pump(budgetMs);

        int ready = 0;

        for (CompletableFuture<byte[]> picture : this.pictures)
        {
            if (picture.isDone())
            {
                ++ready;
            }
        }

        this.done = ready;

        return this.finished();
    }

    private CompletableFuture<byte[]> order(MaterialRow row)
    {
        try
        {
            return ItemIcons.request(row.stack(), Exporters.ICON_SIZE);
        }
        catch (RuntimeException | LinkageError e)
        {
            if (! this.warned)
            {
                this.warned = true;
                LOGGER.warn("Export: no icon for {}, the row goes without it; further icon failures are not logged ({})",
                        row.key(), e.toString());
            }

            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    /** Готовое — для выгрузки (в том числе в фоне); не пришедшие иконки — строки без картинки. */
    ExportIcons icons()
    {
        Map<String, byte[]> ready = new HashMap<>();

        for (int i = 0; i < this.pictures.size(); ++i)
        {
            byte[] png = this.pictures.get(i).getNow(null);
            ready.put(this.rows.get(i).key(), png != null && png.length > 8 ? png : new byte[0]);
        }

        return ExportIcons.prepared(Exporters.ICON_SIZE, Map.copyOf(ready));
    }
}
