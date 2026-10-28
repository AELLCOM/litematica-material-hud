package com.aell.layerlist.dev;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Тестовые схемы режима «k»: копии настоящих файлов игрока из профилей Modrinth и образец,
 * построенный в мире стенда, — он же сохраняется в трёх форматах (.litematic сохраняет сама
 * Litematica, .nbt — ванильный {@link StructureTemplate}, .schem — свой минимальный писатель
 * Sponge v3 ниже; только для харнесса, в мод не входит).
 */
@Environment(EnvType.CLIENT)
final class KData
{
    /** Пол образца (мин. угол); верх пола — уровень площадки. */
    static final BlockPos SAMPLE = new BlockPos(500, 99, 0);
    static final Vec3i SAMPLE_SIZE = new Vec3i(7, 5, 7);
    /** Базовое имя сгенерированных файлов (без расширения). */
    static final String GENERATED = "k-generated";

    private static final long SMALL_MIN = 2_000L;
    private static final long SMALL_MAX = 16_000L;
    private static final long MEDIUM_MAX = 256_000L;
    private static final long MEDIUM_TARGET = 64_000L;
    private static final int ENTITY_SCAN_MAX = 40;
    private static final int FILL_LIMIT = 32_768;
    /** Сколько воздуха над площадкой: выше верха схемы с запасом на PgUp. */
    private static final int CLEAR_HEIGHT = 48;

    private KData() {}

    /** Что взяли из папок игрока и почему. */
    record Picked(Path source, String why) {}

    // ===== Образец в мире =====

    /** Строит образец командами сервера (парсер команд сам разбирает состояния и данные блоков). Серверный поток. */
    static void buildSample(MinecraftServer server)
    {
        int x = SAMPLE.getX();
        int y = SAMPLE.getY();
        int z = SAMPLE.getZ();

        // Лиана над воздухом прорастает вниз от случайного такта — успей он до сохранения схемы, в ней
        // выйдет 86 блоков и три лианы вместо двух (так упал прогон 07.10, RU). Образец не должен расти
        server.overworld().getGameRules().set(GameRules.SPREAD_VINES, false, server);

        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 4, y, z - 4, x + 10, y + 12, z + 10));
        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x, y, z, x + 6, y, z + 6));

        for (int dy = 1; dy <= 3; ++dy)
        {
            set(server, 0, dy, 0, "oak_planks");
            set(server, 6, dy, 0, "oak_planks");
        }

        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", x + 1, y + 1, z, x + 5, y + 2, z));
        set(server, 0, 4, 0, "smooth_stone_slab[type=top]");
        set(server, 6, 4, 0, "smooth_stone_slab[type=bottom]");

        set(server, 1, 1, 1, "chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3},"
                + "{Slot:1b,id:\"minecraft:oak_log\",count:16}]}");
        // Лиана на двух гранях: опора с севера — столб, с востока — доска над сундуком
        set(server, 1, 2, 1, "oak_planks");
        set(server, 0, 2, 1, "vine[north=true,east=true]");

        set(server, 1, 1, 3, "smooth_stone_slab[type=bottom]");
        set(server, 2, 1, 3, "smooth_stone_slab[type=top]");
        set(server, 3, 1, 3, "smooth_stone_slab[type=double]");

        // Источник воды в стеклянной чаше — не растечётся
        set(server, 4, 1, 2, "glass");
        set(server, 6, 1, 2, "glass");
        set(server, 5, 1, 1, "glass");
        set(server, 5, 1, 3, "glass");
        set(server, 5, 1, 2, "water");
        // Плита «в воде» над источником, в такой же стеклянной чаше: ведро воды в таблице считает и её
        set(server, 4, 2, 2, "glass");
        set(server, 6, 2, 2, "glass");
        set(server, 5, 2, 1, "glass");
        set(server, 5, 2, 3, "glass");
        set(server, 5, 2, 2, "oak_slab[type=bottom,waterlogged=true]");

        set(server, 4, 1, 4, "potted_poppy");
        // Флаг с узором и такой же без: у нас — две строки, у Litematica — одна
        set(server, 3, 1, 5, "white_banner[rotation=8]{patterns:[{pattern:\"minecraft:stripe_top\",color:\"red\"},"
                + "{pattern:\"minecraft:border\",color:\"blue\"}]}");
        set(server, 2, 1, 5, "white_banner[rotation=8]");

        set(server, 1, 1, 6, "oak_stairs[facing=north]");
        set(server, 2, 1, 6, "oak_stairs[facing=north]");
        set(server, 4, 1, 6, "stone_brick_stairs[facing=south,half=top]");
        set(server, 5, 1, 6, "stone_brick_stairs[facing=south,half=top]");

        command(server, String.format(Locale.ROOT, "kill @e[type=minecraft:armor_stand,x=%d,y=%d,z=%d,dx=7,dy=5,dz=7]", x, y, z));
        command(server, String.format(Locale.ROOT, "summon minecraft:armor_stand %.1f %d %.1f {Rotation:[90f,0f]}",
                x + 5.5, y + 1, z + 4.5));

        // Две рамки лежат на каменных кирпичах пола (Facing:1b — вверх): с алмазом и пустая светящаяся.
        // block_pos — клетка самой рамки, позиция — её центр; без block_pos 26.3 рамку не читает
        command(server, String.format(Locale.ROOT, "kill @e[type=minecraft:item_frame,x=%d,y=%d,z=%d,dx=7,dy=5,dz=7]", x, y, z));
        command(server, String.format(Locale.ROOT, "kill @e[type=minecraft:glow_item_frame,x=%d,y=%d,z=%d,dx=7,dy=5,dz=7]", x, y, z));
        command(server, String.format(Locale.ROOT, "summon minecraft:item_frame %.1f %.1f %.1f {Facing:1b,block_pos:[I;%d,%d,%d],"
                + "Item:{id:\"minecraft:diamond\",count:1}}", x + 2.5, y + 1.5, z + 1.5, x + 2, y + 1, z + 1));
        command(server, String.format(Locale.ROOT, "summon minecraft:glow_item_frame %.1f %.1f %.1f {Facing:1b,block_pos:[I;%d,%d,%d]}",
                x + 3.5, y + 1.5, z + 2.5, x + 3, y + 1, z + 2));
    }

    /** Площадка под размещения: камень на уровне пола образца, воздух над ним (рельеф стенда не мешает «К земле»). */
    static void buildPlatform(MinecraftServer server, int minX, int minZ, int maxX, int maxZ, int floorY)
    {
        // /fill меняет не больше 32 768 блоков за раз — воздух над площадкой слоями такой толщины
        int area = (maxX - minX + 1) * (maxZ - minZ + 1);
        int band = Math.max(1, FILL_LIMIT / area);

        for (int y0 = floorY + 1; y0 <= floorY + CLEAR_HEIGHT; y0 += band)
        {
            int y1 = Math.min(y0 + band - 1, floorY + CLEAR_HEIGHT);
            command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", minX, y0, minZ, maxX, y1, maxZ));
        }

        command(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", minX, floorY, minZ, maxX, floorY, maxZ));
    }

    /** Непустых клеток в образце — столько должна показать карточка любого из трёх форматов. Серверный поток. */
    static long countSample(ServerLevel level)
    {
        long count = 0;

        for (BlockPos pos : BlockPos.betweenClosed(SAMPLE, SAMPLE.offset(SAMPLE_SIZE).offset(-1, -1, -1)))
        {
            if (! level.getBlockState(pos).isAir())
            {
                ++count;
            }
        }

        return count;
    }

    /** Ванильная структура (.nbt) с сущностями. Серверный поток. */
    static void writeStructure(ServerLevel level, Path file) throws IOException
    {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, SAMPLE, SAMPLE_SIZE, true, List.of(Blocks.STRUCTURE_VOID));
        template.setAuthor("Material HUD harness");
        CompoundTag tag = template.save(new CompoundTag());

        if (! tag.contains("DataVersion"))
        {
            tag.putInt("DataVersion", dataVersion());
        }

        writeAtomically(tag, file);
    }

    /**
     * Sponge Schematic v3 по открытой спецификации формата: корень {@code Schematic} с размерами,
     * палитрой «состояние → номер», номерами клеток varint-ами в порядке x, затем z, затем y и
     * данными блоков (позиция, id, данные без координат). Сущности не пишутся. Серверный поток.
     */
    static void writeSponge(ServerLevel level, Path file) throws IOException
    {
        int w = SAMPLE_SIZE.getX();
        int h = SAMPLE_SIZE.getY();
        int l = SAMPLE_SIZE.getZ();
        Map<String, Integer> ids = new LinkedHashMap<>();
        ByteArrayOutputStream cells = new ByteArrayOutputStream();
        ListTag blockEntities = new ListTag();

        for (int y = 0; y < h; ++y)
        {
            for (int z = 0; z < l; ++z)
            {
                for (int x = 0; x < w; ++x)
                {
                    BlockPos pos = SAMPLE.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    String key = BlockStateParser.serialize(state);
                    Integer id = ids.get(key);

                    if (id == null)
                    {
                        id = ids.size();
                        ids.put(key, id);
                    }

                    varInt(cells, id);
                    BlockEntity blockEntity = level.getBlockEntity(pos);

                    if (blockEntity != null)
                    {
                        CompoundTag entry = new CompoundTag();
                        entry.putIntArray("Pos", new int[]{x, y, z});
                        entry.putString("Id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString());
                        entry.put("Data", blockEntity.saveWithoutMetadata(level.registryAccess()));
                        blockEntities.add(entry);
                    }
                }
            }
        }

        CompoundTag palette = new CompoundTag();
        ids.forEach(palette::putInt);

        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", cells.toByteArray());
        blocks.put("BlockEntities", blockEntities);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", GENERATED);
        metadata.putString("Author", "Material HUD harness");
        metadata.putLong("Date", Util.getEpochMillis());

        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", dataVersion());
        schematic.put("Metadata", metadata);
        schematic.putShort("Width", (short) w);
        schematic.putShort("Height", (short) h);
        schematic.putShort("Length", (short) l);
        schematic.putIntArray("Offset", new int[]{0, 0, 0});
        schematic.put("Blocks", blocks);

        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);
        writeAtomically(root, file);
    }

    private static void varInt(ByteArrayOutputStream out, int value)
    {
        int v = value;

        while ((v & ~0x7F) != 0)
        {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }

        out.write(v);
    }

    static int dataVersion()
    {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static void writeAtomically(CompoundTag tag, Path file) throws IOException
    {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void set(MinecraftServer server, int dx, int dy, int dz, String block)
    {
        command(server, String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", SAMPLE.getX() + dx, SAMPLE.getY() + dy,
                SAMPLE.getZ() + dz, block));
    }

    static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[k] команда не прошла (" + command + "): " + e);
        }
    }

    // ===== Папка _k и файлы игрока =====

    /** Пересоздать папку: старые копии и выгрузки прошлого прогона не должны путать список. */
    static void recreate(Path dir) throws IOException
    {
        if (Files.exists(dir))
        {
            try (Stream<Path> walk = Files.walk(dir))
            {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                {
                    Files.deleteIfExists(path);
                }
            }
        }

        Files.createDirectories(dir);
    }

    /**
     * Папки схем профилей Modrinth: {@code LL_K_SOURCES} (через «;»), иначе {@code %APPDATA%} в
     * Windows и {@code /mnt/c/Users/*} в WSL. Недоступно — пустой список, харнесс обойдётся
     * сгенерированными файлами.
     */
    static List<Path> profileFolders()
    {
        List<Path> roots = new ArrayList<>();
        String override = System.getenv("LL_K_SOURCES");

        if (override != null && ! override.isBlank())
        {
            for (String part : override.split(";"))
            {
                if (! part.isBlank())
                {
                    roots.add(Path.of(part.trim()));
                }
            }

            return roots;
        }

        List<Path> profileRoots = new ArrayList<>();
        String appData = System.getenv("APPDATA");

        if (appData != null && ! appData.isBlank())
        {
            profileRoots.add(Path.of(appData, "ModrinthApp", "profiles"));
        }

        Path users = Path.of("/mnt/c/Users");

        if (Files.isDirectory(users))
        {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(users))
            {
                for (Path user : stream)
                {
                    profileRoots.add(user.resolve("AppData").resolve("Roaming").resolve("ModrinthApp").resolve("profiles"));
                }
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log("[k] не прочитать " + users + ": " + e);
            }
        }

        for (Path profiles : profileRoots)
        {
            if (! Files.isDirectory(profiles))
            {
                continue;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(profiles))
            {
                for (Path profile : stream)
                {
                    Path schematics = profile.resolve("schematics");

                    if (Files.isDirectory(schematics))
                    {
                        roots.add(schematics);
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log("[k] не прочитать " + profiles + ": " + e);
            }
        }

        return roots;
    }

    /**
     * До четырёх настоящих схем: маленькая и средняя .litematic, .litematic с сущностями, один
     * Sponge .schem; плюс старый .schematic, если он вообще есть. Одинаковые имена из разных
     * профилей — один файл (берётся первый, у новых профилей — 26.3 — приоритет по порядку).
     */
    static List<Picked> pickReal(List<Path> folders)
    {
        Map<String, Path> byName = new LinkedHashMap<>();
        List<Path> ordered = new ArrayList<>(folders);
        // Профиль под текущую версию игры — первым: его схемы ближе к тому, что игрок строит сейчас
        ordered.sort(Comparator.comparing((Path p) -> ! p.toString().contains("26.3")));

        for (Path folder : ordered)
        {
            try (Stream<Path> walk = Files.walk(folder, 2))
            {
                for (Path path : walk.filter(Files::isRegularFile).toList())
                {
                    String name = path.getFileName().toString();
                    String lower = name.toLowerCase(Locale.ROOT);

                    if (! name.startsWith(".") && (lower.endsWith(".litematic") || lower.endsWith(".schem")
                            || lower.endsWith(".schematic")))
                    {
                        byName.putIfAbsent(name, path);
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                CovOut.log("[k] не прочитать " + folder + ": " + e);
            }
        }

        List<Path> litematics = new ArrayList<>();
        List<Path> sponge = new ArrayList<>();
        List<Path> legacy = new ArrayList<>();

        for (Path path : byName.values())
        {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            (lower.endsWith(".litematic") ? litematics : lower.endsWith(".schem") ? sponge : legacy).add(path);
        }

        Comparator<Path> bySize = Comparator.comparingLong(KData::size);
        litematics.sort(bySize);
        sponge.sort(bySize);
        legacy.sort(bySize);

        List<Picked> out = new ArrayList<>();
        Set<Path> taken = new TreeSet<>();

        Path small = first(litematics, p -> size(p) >= SMALL_MIN && size(p) <= SMALL_MAX);
        add(out, taken, small != null ? small : litematics.isEmpty() ? null : litematics.getFirst(), "маленькая .litematic");

        Path medium = null;

        for (Path p : litematics)
        {
            if (! taken.contains(p) && size(p) > SMALL_MAX && size(p) <= MEDIUM_MAX
                    && (medium == null || Math.abs(size(p) - MEDIUM_TARGET) < Math.abs(size(medium) - MEDIUM_TARGET)))
            {
                medium = p;
            }
        }

        add(out, taken, medium, "средняя .litematic");

        int scanned = 0;

        for (Path p : litematics)
        {
            if (taken.contains(p) || size(p) > MEDIUM_MAX || scanned++ >= ENTITY_SCAN_MAX)
            {
                continue;
            }

            int entities = litematicEntities(p);

            if (entities > 0)
            {
                add(out, taken, p, ".litematic с сущностями (" + entities + ")");
                break;
            }
        }

        add(out, taken, first(sponge, p -> size(p) >= SMALL_MIN), "Sponge .schem");

        if (! legacy.isEmpty())
        {
            add(out, taken, legacy.getFirst(), "старый .schematic");
        }

        return out;
    }

    private static void add(List<Picked> out, Set<Path> taken, @Nullable Path path, String why)
    {
        if (path != null && taken.add(path))
        {
            out.add(new Picked(path, why));
        }
    }

    private static @Nullable Path first(List<Path> paths, java.util.function.Predicate<Path> test)
    {
        for (Path p : paths)
        {
            if (test.test(p))
            {
                return p;
            }
        }

        return null;
    }

    static long size(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException | RuntimeException e)
        {
            return Long.MAX_VALUE;
        }
    }

    /** Сущностей во всех регионах .litematic (ванильный читатель NBT); −1 — не читается. */
    static int litematicEntities(Path path)
    {
        try
        {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            int total = 0;

            for (String name : regions.keySet())
            {
                total += regions.getCompoundOrEmpty(name).getListOrEmpty("Entities").size();
            }

            return total;
        }
        catch (IOException | RuntimeException e)
        {
            return -1;
        }
    }
}
