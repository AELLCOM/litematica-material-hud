package com.aell.layerlist.schematics.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.FieldSelector;
import org.jetbrains.annotations.Nullable;

/**
 * Сведения о файле схемы для карточки и строки списка — своим лёгким чтением NBT. Методы
 * Litematica для метаданных здесь не годятся: они разбирают блоки целиком и показывают сообщения
 * в интерфейсе, а мы читаем в фоновом потоке и ошибку отдаём в карточку текстом.
 *
 * <p>Никогда не бросает: всё, что пошло не так, — {@link SchematicInfo#failed}.
 */
@Environment(EnvType.CLIENT)
final class MetadataReader
{
    private static final String[] AIR_NAMES = {"air", "cave_air", "void_air"};

    private MetadataReader() {}

    static SchematicInfo read(SchematicEntry entry)
    {
        String name = entry.name();

        if (entry.directory() || entry.format() == null)
        {
            return SchematicInfo.failed(name, new SchematicReadException("layerlist.schematics.error.not_schematic").getMessage());
        }

        try
        {
            return switch (entry.format())
            {
                case LITEMATIC -> litematic(entry);
                case SPONGE -> sponge(entry);
                case VANILLA -> structure(entry);
                case SCHEMATICA -> schematica(entry);
            };
        }
        catch (SchematicReadException error)
        {
            return SchematicInfo.failed(name, error.getMessage());
        }
        catch (IOException | RuntimeException error)
        {
            return SchematicInfo.failed(name, new SchematicReadException(error, "layerlist.schematics.error.read",
                    SchematicReadException.reason(error)).getMessage());
        }
    }

    // ===== .litematic: всё нужное лежит в «Metadata» до блоков =====

    private static SchematicInfo litematic(SchematicEntry entry) throws IOException, SchematicReadException
    {
        CompoundTag root = NbtFiles.readFields(entry.path(),
                new FieldSelector(IntTag.TYPE, "MinecraftDataVersion"),
                new FieldSelector(CompoundTag.TYPE, "Metadata"));

        CompoundTag meta = root == null ? null : root.getCompound("Metadata").orElse(null);

        if (meta == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "Metadata");
        }

        CompoundTag size = meta.getCompoundOrEmpty("EnclosingSize");
        int sx = Math.abs(size.getIntOr("x", 0));
        int sy = Math.abs(size.getIntOr("y", 0));
        int sz = Math.abs(size.getIntOr("z", 0));
        long volume = meta.getIntOr("TotalVolume", -1);

        if (volume < 0)
        {
            volume = (long) sx * sy * sz;
        }

        int[] preview = meta.getIntArray("PreviewImageData").orElse(null);
        int side = preview == null ? 0 : squareSide(preview.length);

        return new SchematicInfo(
                orFileName(meta.getStringOr("Name", ""), entry),
                meta.getStringOr("Author", ""),
                meta.getStringOr("Description", ""),
                sx, sy, sz, volume,
                meta.getIntOr("TotalBlocks", -1),
                Math.max(1, meta.getIntOr("RegionCount", 1)),
                meta.getLongOr("TimeCreated", 0L),
                meta.getLongOr("TimeModified", entry.modified()),
                root.getIntOr("MinecraftDataVersion", 0),
                side > 0 ? preview : null, side, null);
    }

    // ===== Sponge .schem: блоки считаем сами по палитре =====

    private static SchematicInfo sponge(SchematicEntry entry) throws IOException, SchematicReadException
    {
        CompoundTag root = NbtFiles.read(entry.path());
        CompoundTag body = root.getCompound("Schematic").orElse(root);
        int width = SchematicDecoder.unsignedShort(body, "Width");
        int height = SchematicDecoder.unsignedShort(body, "Height");
        int length = SchematicDecoder.unsignedShort(body, "Length");

        CompoundTag palette;
        byte[] data;
        Tag blocksTag = body.get("Blocks");

        if (blocksTag instanceof CompoundTag blocks)
        {
            palette = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
        }
        else
        {
            palette = body.getCompoundOrEmpty("Palette");
            data = body.getByteArray("BlockData").orElse(new byte[0]);
        }

        int top = -1;

        for (String key : palette.keySet())
        {
            top = Math.max(top, palette.getIntOr(key, -1));
        }

        boolean[] air = new boolean[Math.max(0, top + 1)];

        for (String key : palette.keySet())
        {
            int index = palette.getIntOr(key, -1);

            if (index >= 0)
            {
                air[index] = isAirName(key);
            }
        }

        long volume = (long) width * height * length;
        long blocks = 0L;
        int at = 0;

        for (long i = 0; i < volume && at < data.length; ++i)
        {
            int index = 0;
            int shift = 0;
            byte b;

            do
            {
                b = data[at++];
                index |= (b & 0x7F) << shift;
                shift += 7;
            }
            while ((b & 0x80) != 0 && at < data.length && shift <= 28);

            if (index >= 0 && index < air.length && ! air[index])
            {
                ++blocks;
            }
        }

        // У Sponge метаданные необязательны: v2 кладёт Name/Author/Date в «Metadata», v3 — так же
        CompoundTag meta = body.getCompoundOrEmpty("Metadata");
        long created = meta.getLongOr("Date", 0L);

        return new SchematicInfo(
                orFileName(meta.getStringOr("Name", ""), entry),
                meta.getStringOr("Author", ""),
                "",
                width, height, length, volume, blocks, 1,
                created > 0L ? created : creationTime(entry.path()),
                entry.modified(),
                body.getIntOr("DataVersion", 0),
                null, 0, null);
    }

    // ===== Ванильная структура .nbt =====

    private static SchematicInfo structure(SchematicEntry entry) throws IOException, SchematicReadException
    {
        CompoundTag root = NbtFiles.read(entry.path());
        ListTag size = root.getListOrEmpty("size");
        int sx = size.getIntOr(0, 0);
        int sy = size.getIntOr(1, 0);
        int sz = size.getIntOr(2, 0);
        ListTag palette = SchematicDecoder.structurePalette(root);

        if (palette == null)
        {
            throw new SchematicReadException("layerlist.schematics.error.corrupt", "palette");
        }

        boolean[] air = new boolean[palette.size()];

        for (int i = 0; i < air.length; ++i)
        {
            CompoundTag state = palette.getCompoundOrEmpty(i);
            air[i] = isAirName(state.getString("id").orElse(state.getStringOr("Name", "")));
        }

        ListTag list = root.getListOrEmpty("blocks");
        long blocks = 0L;

        for (int i = 0; i < list.size(); ++i)
        {
            int state = list.getCompoundOrEmpty(i).getIntOr("state", -1);

            if (state >= 0 && state < air.length && ! air[state])
            {
                ++blocks;
            }
        }

        return new SchematicInfo(
                entry.name(),
                root.getStringOr("author", ""),
                "",
                sx, sy, sz, (long) sx * sy * sz, blocks, 1,
                creationTime(entry.path()), entry.modified(),
                root.getIntOr("DataVersion", 0),
                null, 0, null);
    }

    // ===== Schematica .schematic: числовые id, блоки — всё, что не 0 =====

    private static SchematicInfo schematica(SchematicEntry entry) throws IOException
    {
        CompoundTag root = NbtFiles.read(entry.path());
        int width = SchematicDecoder.unsignedShort(root, "Width");
        int height = SchematicDecoder.unsignedShort(root, "Height");
        int length = SchematicDecoder.unsignedShort(root, "Length");
        byte[] ids = root.getByteArray("Blocks").orElse(new byte[0]);
        // Старшие 4 бита id для номеров больше 255 — по полбайта на блок (Schematica «AddBlocks»)
        byte[] add = root.getByteArray("AddBlocks").orElse(root.getByteArray("Add").orElse(new byte[0]));
        long blocks = 0L;

        for (int i = 0; i < ids.length; ++i)
        {
            int high = 0;

            if ((i >> 1) < add.length)
            {
                high = (i & 1) == 0 ? (add[i >> 1] >> 4) & 0x0F : add[i >> 1] & 0x0F;
            }

            if ((ids[i] & 0xFF) != 0 || high != 0)
            {
                ++blocks;
            }
        }

        return new SchematicInfo(
                entry.name(), "", "",
                width, height, length, (long) width * height * length, blocks, 1,
                creationTime(entry.path()), entry.modified(), 0,
                null, 0, null);
    }

    // ===== Общее =====

    private static String orFileName(String name, SchematicEntry entry)
    {
        return name.isBlank() ? entry.name() : name;
    }

    /** Имя воздуха без регистра и пространства имён: в метаданных реестр не нужен. */
    static boolean isAirName(String state)
    {
        String name = state.trim();
        int props = name.indexOf('[');

        if (props >= 0)
        {
            name = name.substring(0, props);
        }

        if (name.startsWith("minecraft:"))
        {
            name = name.substring("minecraft:".length());
        }

        for (String air : AIR_NAMES)
        {
            if (air.equals(name))
            {
                return true;
            }
        }

        return false;
    }

    /** Сторона квадратного превью; 0 — длина не квадрат. */
    static int squareSide(int length)
    {
        int side = (int) Math.round(Math.sqrt(length));

        return side > 0 && side * side == length ? side : 0;
    }

    private static long creationTime(@Nullable Path file)
    {
        if (file == null)
        {
            return 0L;
        }

        try
        {
            return Files.readAttributes(file, BasicFileAttributes.class).creationTime().toMillis();
        }
        catch (IOException | RuntimeException error)
        {
            return 0L;
        }
    }
}
