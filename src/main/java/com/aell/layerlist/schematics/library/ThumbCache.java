package com.aell.layerlist.schematics.library;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Миниатюры: кеш PNG на диске ({@code <sha1 пути>-<время>-<размер>.png} — изменился файл,
 * изменилось и имя) и текстуры в памяти только для строк, которые список сейчас показывает.
 *
 * <p>Список зовёт {@link #get} для видимых строк каждый кадр. Запрос, который давно не повторялся
 * (строку прокрутили), забывается; из ждущих первым идёт самый свежий — поэтому при прокрутке
 * миниатюры появляются там, куда смотрят. Текстуры создаются на потоке рендера понемногу за кадр
 * и все освобождаются в {@link #release()} при закрытии экрана.
 */
@Environment(EnvType.CLIENT)
final class ThumbCache
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    private static final String NAMESPACE = "layerlist";
    private static final long FORGET_MS = 1500L;
    private static final int UPLOADS_PER_FRAME = 4;
    private static final int MAX_JOBS = 2;
    private static final int MAX_TEXTURES = 384;

    private record Ready(SchematicEntry entry, int @Nullable [] pixels, int generation) {}

    private final Path dir;
    private final Function<SchematicEntry, CompletableFuture<int @Nullable []>> generator;
    private final Executor io;

    // Всё ниже — только клиентский поток; из фона приходит лишь очередь ready
    private final Map<SchematicEntry, Identifier> textures = new LinkedHashMap<>(64, 0.75F, true);
    private final Map<SchematicEntry, Long> wanted = new HashMap<>();
    private final Set<SchematicEntry> running = new HashSet<>();
    private final Set<SchematicEntry> failed = new HashSet<>();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private int generation;
    private int counter;
    private long uploadWindow = Long.MIN_VALUE;
    private int uploadBudget;

    /**
     * @param generator рисует миниатюру, если на диске её нет; {@code null} в результате — миниатюры не будет
     * @param io        потоки для чтения и записи PNG
     */
    ThumbCache(Path dir, Function<SchematicEntry, CompletableFuture<int @Nullable []>> generator, Executor io)
    {
        this.dir = dir;
        this.generator = generator;
        this.io = io;
    }

    /** Текстура миниатюры или {@code null} (ещё нет — запрос поставлен; или миниатюры не будет). */
    @Nullable Identifier get(SchematicEntry entry)
    {
        long now = Util.getMillis();
        this.uploadReady(now);

        Identifier id = this.textures.get(entry);

        if (id != null || this.failed.contains(entry))
        {
            return id;
        }

        if (! this.running.contains(entry))
        {
            this.wanted.put(entry, now);
        }

        this.pump(now);

        return null;
    }

    /** Освободить все текстуры и забыть очередь; поздние результаты фона будут выброшены. */
    void release()
    {
        Minecraft mc = Minecraft.getInstance();

        for (Identifier id : this.textures.values())
        {
            mc.getTextureManager().release(id);
        }

        this.textures.clear();
        this.wanted.clear();
        this.running.clear();
        this.failed.clear();
        this.ready.clear();
        ++this.generation;
    }

    /** Файл ушёл (в корзину): отдать текстуры его записей и забыть запросы; поздний результат фона ляжет зря, но безвредно. */
    void forget(Path path)
    {
        Iterator<Map.Entry<SchematicEntry, Identifier>> it = this.textures.entrySet().iterator();

        while (it.hasNext())
        {
            Map.Entry<SchematicEntry, Identifier> known = it.next();

            if (known.getKey().path().toAbsolutePath().normalize().equals(path))
            {
                Minecraft.getInstance().getTextureManager().release(known.getValue());
                it.remove();
            }
        }

        this.wanted.keySet().removeIf(e -> e.path().toAbsolutePath().normalize().equals(path));
        this.failed.removeIf(e -> e.path().toAbsolutePath().normalize().equals(path));
    }

    /** Удалено насовсем: стереть PNG его миниатюр (все версии времени/размера) в фоне. */
    void deleteFiles(Path path)
    {
        String pattern = key(path) + "-*.png";

        this.io.execute(() ->
        {
            if (! Files.isDirectory(this.dir))
            {
                return;
            }

            try (DirectoryStream<Path> stale = Files.newDirectoryStream(this.dir, pattern))
            {
                for (Path old : stale)
                {
                    Files.deleteIfExists(old);
                }
            }
            catch (IOException | RuntimeException error)
            {
                LOGGER.debug("Could not delete thumbnails of {}", path, error);
            }
        });
    }

    /** Файл переименовали: PNG его миниатюр — под новое имя (время и размер у файла те же), в фоне. */
    void moveFiles(Path from, Path to)
    {
        String oldKey = key(from);
        String newKey = key(to);

        this.io.execute(() ->
        {
            if (! Files.isDirectory(this.dir))
            {
                return;
            }

            try (DirectoryStream<Path> pngs = Files.newDirectoryStream(this.dir, oldKey + "-*.png"))
            {
                for (Path png : pngs)
                {
                    String tail = png.getFileName().toString().substring(oldKey.length());
                    Files.move(png, this.dir.resolve(newKey + tail), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            catch (IOException | RuntimeException error)
            {
                // Не перенеслась — миниатюра нарисуется заново
                LOGGER.debug("Could not move thumbnails of {} to {}", from, to, error);
            }
        });
    }

    private void pump(long now)
    {
        while (this.running.size() < MAX_JOBS)
        {
            SchematicEntry next = null;
            long newest = Long.MIN_VALUE;
            Iterator<Map.Entry<SchematicEntry, Long>> it = this.wanted.entrySet().iterator();

            while (it.hasNext())
            {
                Map.Entry<SchematicEntry, Long> request = it.next();

                // Строку давно не показывали — прокрутили мимо; появится снова — попросит снова
                if (now - request.getValue() > FORGET_MS)
                {
                    it.remove();
                }
                else if (request.getValue() > newest)
                {
                    newest = request.getValue();
                    next = request.getKey();
                }
            }

            if (next == null)
            {
                return;
            }

            this.wanted.remove(next);
            this.running.add(next);
            this.start(next, this.generation);
        }
    }

    private void start(SchematicEntry entry, int generation)
    {
        Path file = this.file(entry);

        CompletableFuture.supplyAsync(() -> readPng(file), this.io)
                .thenCompose(cached -> cached != null
                        ? CompletableFuture.completedFuture(cached)
                        : this.generator.apply(entry).thenApplyAsync(pixels ->
                        {
                            if (pixels != null)
                            {
                                this.writePng(file, entry, pixels);
                            }

                            return pixels;
                        }, this.io))
                .whenComplete((pixels, error) ->
                {
                    if (error != null)
                    {
                        LOGGER.debug("No thumbnail for {}", entry.path(), error);
                    }

                    this.ready.add(new Ready(entry, error == null ? pixels : null, generation));
                });
    }

    /** Не больше {@link #UPLOADS_PER_FRAME} текстур за кадр — сколько бы строк ни спросило в этом кадре. */
    private void uploadReady(long now)
    {
        // Кадр короче миллисекунды не бывает: «новое значение часов» = новый кадр
        if (now != this.uploadWindow)
        {
            this.uploadWindow = now;
            this.uploadBudget = UPLOADS_PER_FRAME;
        }

        while (this.uploadBudget > 0)
        {
            Ready next = this.ready.poll();

            if (next == null)
            {
                return;
            }

            if (next.generation() != this.generation)
            {
                continue;
            }

            this.running.remove(next.entry());

            int[] pixels = next.pixels();

            if (pixels == null || pixels.length != ThumbnailRenderer.SIZE * ThumbnailRenderer.SIZE)
            {
                this.failed.add(next.entry());
                continue;
            }

            this.textures.put(next.entry(), this.upload(pixels));
            --this.uploadBudget;
            this.trim();
        }
    }

    private Identifier upload(int[] pixels)
    {
        int size = ThumbnailRenderer.SIZE;
        NativeImage image = new NativeImage(size, size, true);

        for (int y = 0; y < size; ++y)
        {
            for (int x = 0; x < size; ++x)
            {
                image.setPixel(x, y, pixels[y * size + x]);
            }
        }

        Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "schematic_thumb/" + this.counter++);
        Minecraft.getInstance().getTextureManager().register(id, new ThumbTexture(image));

        return id;
    }

    /** Список длинный, а экран открыт долго: самые давно не нужные текстуры отдаём. */
    private void trim()
    {
        Iterator<Map.Entry<SchematicEntry, Identifier>> it = this.textures.entrySet().iterator();

        while (this.textures.size() > MAX_TEXTURES && it.hasNext())
        {
            Minecraft.getInstance().getTextureManager().release(it.next().getValue());
            it.remove();
        }
    }

    // ===== Диск =====

    private Path file(SchematicEntry entry)
    {
        return this.dir.resolve(key(entry.path()) + "-" + entry.modified() + "-" + entry.size() + ".png");
    }

    static String key(Path path)
    {
        try
        {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException error)
        {
            // SHA-1 есть в любой JVM; на всякий случай — хеш строки, имя всё равно уникально «почти всегда»
            return Integer.toHexString(path.toAbsolutePath().normalize().toString().hashCode());
        }
    }

    private static int @Nullable [] readPng(Path file)
    {
        if (! Files.isRegularFile(file))
        {
            return null;
        }

        try
        {
            BufferedImage image = ImageIO.read(file.toFile());
            int size = ThumbnailRenderer.SIZE;

            if (image == null || image.getWidth() != size || image.getHeight() != size)
            {
                return null;
            }

            return image.getRGB(0, 0, size, size, null, 0, size);
        }
        catch (IOException | RuntimeException error)
        {
            return null;
        }
    }

    private void writePng(Path file, SchematicEntry entry, int[] pixels)
    {
        int size = ThumbnailRenderer.SIZE;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

        try
        {
            Files.createDirectories(this.dir);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, size, size, pixels, 0, size);

            if (! ImageIO.write(image, "png", tmp.toFile()))
            {
                return;
            }

            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException error)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }

            // Старые миниатюры того же файла (другое время/размер) больше не понадобятся
            try (DirectoryStream<Path> stale = Files.newDirectoryStream(this.dir, key(entry.path()) + "-*.png"))
            {
                for (Path old : stale)
                {
                    if (! old.equals(file))
                    {
                        Files.deleteIfExists(old);
                    }
                }
            }
        }
        catch (IOException | RuntimeException error)
        {
            LOGGER.debug("Could not cache thumbnail {}", file, error);

            try
            {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored)
            {
            }
        }
    }

    /**
     * Текстура миниатюры с линейной фильтрацией: в списке 64×64 рисуется в 32×32, и с ближайшим
     * соседом половина пикселей просто выпадала бы.
     */
    private static final class ThumbTexture extends DynamicTexture
    {
        ThumbTexture(NativeImage image)
        {
            super(() -> "layerlist schematic thumbnail", image);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
