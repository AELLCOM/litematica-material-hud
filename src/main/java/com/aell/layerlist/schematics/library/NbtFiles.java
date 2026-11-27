package com.aell.layerlist.schematics.library;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.nbt.visitors.FieldSelector;
import org.jetbrains.annotations.Nullable;

/**
 * Чтение файлов схем ванильным {@link NbtIo}. Все четыре формата — именованный корневой
 * compound; сжатие (gzip) узнаём по первым двум байтам, а не по расширению: встречаются и
 * несжатые {@code .nbt}, и сжатые «как попало».
 */
@Environment(EnvType.CLIENT)
final class NbtFiles
{
    /**
     * Потолок памяти на разбор одного файла. Столько же разрешает себе Litematica: файл больше
     * она всё равно не загрузит, а нам он грозил бы нехваткой памяти посреди игры.
     */
    static final long QUOTA = 512L * 1024L * 1024L;

    private static final int GZIP_MAGIC_0 = 0x1F;
    private static final int GZIP_MAGIC_1 = 0x8B;
    private static final int BUFFER = 1 << 16;

    private NbtFiles() {}

    /** Весь файл целиком. */
    static CompoundTag read(Path file) throws IOException
    {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER))
        {
            NbtAccounter accounter = NbtAccounter.create(QUOTA);

            return gzip(in) ? NbtIo.readCompressed(in, accounter) : NbtIo.read(new DataInputStream(in), accounter);
        }
    }

    /**
     * Только выбранные поля. Разбор потоковый и обрывается, как только всё нужное собрано, — так
     * метаданные {@code .litematic} читаются с начала файла, не распаковывая мегабайты блоков после
     * них (если, конечно, writer положил метаданные раньше блоков; старые версии Litematica клали
     * «Regions» первым — тогда файл просто пролистывается до конца).
     *
     * @return собранные поля; {@code null} — корень не compound
     */
    static @Nullable CompoundTag readFields(Path file, FieldSelector... fields) throws IOException
    {
        CollectFields collector = new UntilCollected(fields);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER))
        {
            NbtAccounter accounter = NbtAccounter.create(QUOTA);

            if (gzip(in))
            {
                NbtIo.parseCompressed(in, collector, accounter);
            }
            else
            {
                NbtIo.parse(new DataInputStream(in), collector, accounter);
            }
        }

        Tag result = collector.getResult();

        return result instanceof CompoundTag compound ? compound : null;
    }

    /**
     * Положения регионов {@code .litematic} без их блоков (1.0.40): корень с одним «Regions», в каждом регионе —
     * только «Position» и «Size». Файл пролистывается целиком, но палитры, блоки и сущности пропускаются, не занимая
     * память, — так точку привязки можно проверить у файла любого размера.
     *
     * @return собранный корень; {@code null} — корень не compound
     */
    static @Nullable CompoundTag readRegionHeads(Path file) throws IOException
    {
        RegionHeads collector = new RegionHeads();

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER))
        {
            NbtAccounter accounter = NbtAccounter.create(QUOTA);

            if (gzip(in))
            {
                NbtIo.parseCompressed(in, collector, accounter);
            }
            else
            {
                NbtIo.parse(new DataInputStream(in), collector, accounter);
            }
        }

        Tag result = collector.getResult();

        return result instanceof CompoundTag compound ? compound : null;
    }

    /**
     * Сборщик «только положения регионов». Глубина — сколько контейнеров открыто: 1 — поля корня (нужен один
     * «Regions»), 2 — сами регионы (все), 3 — поля региона (нужны «Position» и «Size»), глубже — их x, y, z.
     */
    private static final class RegionHeads extends CollectToTag
    {
        @Override
        public StreamTagVisitor.EntryResult visitEntry(TagType<?> type, String name)
        {
            int depth = this.depth();

            if (depth == 1 && ! "Regions".equals(name))
            {
                return StreamTagVisitor.EntryResult.SKIP;
            }

            if (depth == 3 && ! "Position".equals(name) && ! "Size".equals(name))
            {
                return StreamTagVisitor.EntryResult.SKIP;
            }

            return super.visitEntry(type, name);
        }
    }

    /**
     * Ванильный сборщик полей, собрав всё, отвечает BREAK — и разбор компаунда всё равно
     * пролистывает остаток файла. Мы в этот момент закрываем открытые контейнеры (чтобы корень
     * попал в результат) и отвечаем HALT — разбор останавливается сразу.
     */
    private static final class UntilCollected extends CollectFields
    {
        UntilCollected(FieldSelector... fields)
        {
            super(fields);
        }

        @Override
        public StreamTagVisitor.EntryResult visitEntry(TagType<?> type)
        {
            StreamTagVisitor.EntryResult result = super.visitEntry(type);

            if (result == StreamTagVisitor.EntryResult.BREAK && this.getMissingFieldCount() <= 0)
            {
                while (this.depth() > 0)
                {
                    this.visitContainerEnd();
                }

                return StreamTagVisitor.EntryResult.HALT;
            }

            return result;
        }
    }

    private static boolean gzip(InputStream in) throws IOException
    {
        in.mark(2);
        int first = in.read();
        int second = in.read();
        in.reset();

        return first == GZIP_MAGIC_0 && second == GZIP_MAGIC_1;
    }
}
