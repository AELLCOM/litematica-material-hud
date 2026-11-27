package com.aell.layerlist.schematics.library;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;

/**
 * Точка привязки схемы {@code .litematic} относительно её блоков (1.0.40).
 *
 * <p>Litematica записывает положение каждого региона как «угол 1 минус точка привязки выделения», а при загрузке ставит
 * точку привязки в игрока. Своя точка привязки, оставшаяся от прежнего выделения в другом месте, даёт файл, блоки
 * которого лежат в сотнях и тысячах блоков от точки привязки: в Litematica такая схема встаёт на этом расстоянии от
 * игрока — «схемы нигде нет». Наше «Разместить» ставит схему по габариту блоков и разницы не замечает, поэтому автор
 * узнаёт о беде только от тех, кому отправил файл.
 *
 * <p>Здесь — проверка файла (положения регионов читаются без разбора блоков, {@link NbtFiles#readRegionHeads}) и запись
 * исправленной копии: то же содержимое NBT, только «Position» каждого региона сдвинута так, что наименьший угол общего
 * габарита стал нулём — ровно то, что Litematica записала бы без своей точки привязки. Палитры, блоки, сущности,
 * блочные сущности и отложенные тики хранятся относительно региона и не меняются. Исходный файл не трогаем: его
 * размещения в мирах держатся на прежних положениях регионов.
 *
 * <p>Классов Litematica здесь нет — только NBT.
 */
@Environment(EnvType.CLIENT)
public final class SchematicOrigin
{
    /**
     * Точка привязки дальше стольких блоков от блоков схемы — потерянная: в Litematica схема встанет в стороне от
     * игрока, за этим расстоянием. Ближе — обычный случай: точку привязки ставят рядом с постройкой нарочно.
     */
    public static final int FAR = 64;

    private static final String[] AXES = { "x", "y", "z" };

    private SchematicOrigin() {}

    /** Габарит блоков относительно точки привязки (она — ноль), обе границы включительно. */
    public record Extent(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        /** На сколько блоков точка привязки отстоит от габарита (до ближайшего его блока); 0 — она внутри. */
        public int gap()
        {
            return distance(0, 0, 0, this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }

        public boolean far()
        {
            return this.gap() > FAR;
        }
    }

    /**
     * Расстояние от точки до габарита в блоках, округлённое; 0 — точка внутри или на границе. Меряем до ближайшего
     * блока габарита: на столько схема и окажется в стороне от места, куда её ставят.
     */
    public static int distance(int x, int y, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        double dx = Math.max(0L, Math.max((long) minX - x, (long) x - maxX));
        double dy = Math.max(0L, Math.max((long) minY - y, (long) y - maxY));
        double dz = Math.max(0L, Math.max((long) minZ - z, (long) z - maxZ));

        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    /**
     * Габарит блоков файла относительно его точки привязки.
     *
     * @return {@code null} — в файле нет регионов с положением и размером
     */
    static @Nullable Extent read(Path file) throws IOException
    {
        CompoundTag root = NbtFiles.readRegionHeads(file);

        return root == null ? null : extent(root.getCompoundOrEmpty("Regions"));
    }

    /**
     * Общий габарит регионов. Размер региона у Litematica бывает отрицательным (угол 1 — не наименьший): дальний угол
     * тогда — «положение + размер + 1», при положительном — «положение + размер − 1».
     */
    static @Nullable Extent extent(CompoundTag regions)
    {
        int[] min = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };
        int[] max = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        boolean any = false;

        for (String name : regions.keySet())
        {
            CompoundTag region = regions.getCompoundOrEmpty(name);
            CompoundTag position = region.getCompound("Position").orElse(null);
            CompoundTag size = region.getCompound("Size").orElse(null);

            if (position == null || size == null)
            {
                continue;
            }

            for (int i = 0; i < 3; ++i)
            {
                int from = position.getIntOr(AXES[i], 0);
                int length = size.getIntOr(AXES[i], 0);
                int to = length > 0 ? from + length - 1 : length < 0 ? from + length + 1 : from;

                min[i] = Math.min(min[i], Math.min(from, to));
                max[i] = Math.max(max[i], Math.max(from, to));
            }

            any = true;
        }

        return any ? new Extent(min[0], min[1], min[2], max[0], max[1], max[2]) : null;
    }

    /**
     * Копия {@code source} в {@code target} с точкой привязки в наименьшем углу габарита. {@code target} не должен
     * существовать; пишем во временный файл рядом и переносим — недописанной копии в папке не остаётся.
     *
     * @return габарит исходного файла: его наименьший угол — сдвиг, на который переехали регионы
     */
    static Extent writeFixedCopy(Path source, Path target) throws IOException, SchematicReadException
    {
        CompoundTag root = NbtFiles.read(source);
        CompoundTag regions = root.getCompound("Regions").orElse(null);
        Extent extent = regions == null ? null : extent(regions);

        if (extent == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "Regions");
        }

        int[] shift = { extent.minX(), extent.minY(), extent.minZ() };

        for (String name : regions.keySet())
        {
            CompoundTag position = regions.getCompoundOrEmpty(name).getCompound("Position").orElse(null);

            if (position == null)
            {
                continue;
            }

            for (int i = 0; i < 3; ++i)
            {
                position.putInt(AXES[i], position.getIntOr(AXES[i], 0) - shift[i]);
            }
        }

        root.getCompound("Metadata").ifPresent(meta -> meta.putLong("TimeModified", System.currentTimeMillis()));

        if (Files.exists(target))
        {
            throw new IOException("target exists: " + target.getFileName());
        }

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");

        try
        {
            NbtIo.writeCompressed(root, tmp);

            try
            {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, target);
            }
        }
        finally
        {
            Files.deleteIfExists(tmp);
        }

        return extent;
    }
}
