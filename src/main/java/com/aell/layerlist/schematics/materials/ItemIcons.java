package com.aell.layerlist.schematics.materials;

import com.aell.layerlist.storage.ItemColors;
import com.aell.layerlist.util.Chat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PNG-иконки предметов для выгрузок (таблица, Google, картинка-карточка) — такие же, как в меню
 * креатива: предмет рисует сама игра (модель предмета в положении GUI, особые отрисовщики сундуков,
 * голов, флагов, щитов, ваз…, свет инвентаря) в свою текстуру вне экрана, пиксели читаются с
 * видеокарты. Как устроен кадр — {@link IconRenderer}.
 *
 * <p>Рисовать можно только внутри кадра GUI (там игра подставляет «световую карту» интерфейса), а
 * пиксели приходят с видеокарты через кадр-два. Поэтому иконка — заявка: {@link #request} отдаёт
 * будущее, которое завершается на клиентском потоке; ждать его {@code join()} на клиентском потоке
 * нельзя — кадры встанут. Готовые PNG кешируются по предмету со всеми его компонентами (узор флага,
 * черепки вазы, профиль головы, зелье, краска, блеск…) и размеру: два стака с одинаковыми
 * компонентами выглядят одинаково, а ключ строки {@link ItemKeys} различает не всё.
 *
 * <p>Если игра иконку не нарисовала (сбой отрисовки, кадры GUI не идут дольше {@link #TIMEOUT_MS}),
 * будущее завершается плашкой среднего цвета предмета; в лог — один раз.
 */
@Environment(EnvType.CLIENT)
public final class ItemIcons
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist/schematics");

    public static final int MIN_SIZE = 8;
    public static final int MAX_SIZE = 256;
    private static final int MAX_CACHED = 1024;

    /** Заявка без ответа столько — плашка (кадры GUI не идут или чтение с видеокарты потерялось). */
    static final long TIMEOUT_MS = 20_000L;
    /** Без заявок столько — текстуры рендерера отдаём видеокарте обратно. */
    private static final long IDLE_RELEASE_MS = 10_000L;
    /** Сколько миллисекунд кадра GUI рендерер тратит на иконки, если окно не сказало иного. */
    private static final long DEFAULT_FRAME_BUDGET_MS = 6L;

    /** Обводка плашки темнее заливки — плашка читается и на тёмном, и на светлом листе. */
    private static final float TILE_EDGE = 0.7F;
    /** Плашка, когда цвета предмета не узнать: приглушённый тон мода. */
    private static final int UNKNOWN_TILE = 0xFF000000 | Chat.DIM;

    private static final Map<IconKey, byte[]> CACHE = new LinkedHashMap<>(64, 0.75F, true);
    private static final Map<IconKey, Job> PENDING = new HashMap<>();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final Identifier HUD_HOOK = Identifier.fromNamespaceAndPath("layerlist", "item_icons");

    private static @Nullable IconRenderer renderer;
    private static boolean registered;
    private static long lastActivity;
    private static long frameBudgetMs = DEFAULT_FRAME_BUDGET_MS;
    private static int generation;
    private static boolean warnedFailure;
    private static boolean warnedTimeout;

    private ItemIcons() {}

    /**
     * Один раз из {@code SchematicsModule.init()} (то есть из {@code onInitializeClient}): рендерер
     * в реестре «картинки в картинке» Fabric (принимает только до создания {@code GameRenderer}),
     * две точки сборки кадра GUI, где очередь просит кадр (HUD в мире; любой открытый экран), и такт
     * клиента — снимать просроченные заявки и отдавать текстуры после простоя.
     *
     * <p>Просить кадр можно только во время сборки GUI: {@code Gui.extractRenderState} начинает её со
     * сброса состояния, и заявка, положенная раньше (например, в такте), пропала бы.
     */
    public static void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        SpecialGuiElementRegistry.register(context ->
        {
            IconRenderer created = new IconRenderer(context.vertexConsumers());
            renderer = created;

            return created;
        });

        // В мире без экрана (кроме скрытого F1 интерфейса); элемент ничего не рисует
        HudElementRegistry.addLast(HUD_HOOK, (graphics, deltaTracker) -> requestFrame());
        // Любой экран — и в мире, и в меню (у экрана свои события, подписываемся при каждой сборке)
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) -> requestFrame()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /**
     * Заявка на иконку {@code size}×{@code size} (размер ограничен 8…256); готовая из кеша — сразу.
     * Будущее завершается на клиентском потоке байтами PNG (при сбое — PNG плашки) и исключением не
     * завершается. Из другого потока заявка сначала переходит на клиентский.
     */
    public static CompletableFuture<byte[]> request(ItemStack stack, int size)
    {
        Objects.requireNonNull(stack, "stack");
        Minecraft mc = Minecraft.getInstance();

        if (! mc.isSameThread())
        {
            return CompletableFuture.supplyAsync(() -> request(stack, size), mc).thenCompose(Function.identity());
        }

        int side = clampSize(size);
        IconKey key = IconKey.of(stack, side);
        byte[] ready = CACHE.get(key);

        if (ready != null)
        {
            return CompletableFuture.completedFuture(ready.clone());
        }

        Job job = PENDING.get(key);

        if (job == null)
        {
            job = new Job(key, ItemKeys.of(stack) + "@" + side, stack.copyWithCount(1), side, Util.getMillis(), generation);
            PENDING.put(key, job);
            QUEUE.addLast(job);
        }

        return job.future.thenApply(byte[]::clone);
    }

    /** Готовая иконка из кеша или {@code null} — ещё не рисовали. Клиентский поток. */
    public static byte @Nullable [] cached(ItemStack stack, int size)
    {
        byte[] ready = CACHE.get(IconKey.of(Objects.requireNonNull(stack, "stack"), clampSize(size)));

        return ready == null ? null : ready.clone();
    }

    /**
     * Сразу и без ожидания кадра: готовая иконка из кеша, иначе — плашка среднего цвета (настоящая
     * иконка при этом заказывается на потом). Выгрузки готовят иконки заранее ({@code IconBatch}),
     * так что сюда они приходят уже из кеша. Из другого потока ждёт ближайшего такта клиента; из
     * потока, которого ждёт клиент, звать нельзя.
     */
    public static byte[] png(ItemStack stack, int size)
    {
        Objects.requireNonNull(stack, "stack");
        Minecraft mc = Minecraft.getInstance();

        if (! mc.isSameThread())
        {
            return mc.submit((Supplier<byte[]>) () -> png(stack, size)).join();
        }

        byte[] ready = cached(stack, size);

        if (ready != null)
        {
            return ready;
        }

        request(stack, size);
        int side = clampSize(size);

        return encode(tile(average(stack), side), side);
    }

    /** Ещё ждут кадра или чтения с видеокарты. Клиентский поток. */
    public static int pending()
    {
        return PENDING.size();
    }

    /**
     * Окно, которое ждёт иконки, зовёт раз в кадр из своей отрисовки: {@code budgetMs} — сколько
     * миллисекунд кадра GUI отдать иконкам, и просьба нарисовать очередь в этом кадре (её же делают
     * и точки из {@link #register}; повтор в одном кадре ничего не добавляет). Клиентский поток.
     */
    public static void pump(long budgetMs)
    {
        frameBudgetMs = Math.max(1L, budgetMs);
        requestFrame();
    }

    /** Забыть готовые иконки (сменился набор ресурсов); рисующиеся сейчас в кеш уже не лягут. Клиентский поток. */
    public static void clearCache()
    {
        CACHE.clear();
        ++generation;
    }

    // ===== Для IconRenderer (клиентский поток) =====

    /** Кадр GUI позвал рендерер. */
    static void prepared()
    {
        lastActivity = Util.getMillis();
    }

    static long frameBudgetMs()
    {
        return frameBudgetMs;
    }

    /** Следующая заявка; {@code size > 0} — только такого размера (в одной текстуре клетки одного размера). */
    static @Nullable Job poll(int size)
    {
        Job head = QUEUE.peekFirst();

        if (head == null || (size > 0 && head.size != size))
        {
            return null;
        }

        QUEUE.pollFirst();
        head.drawnAt = Util.getMillis();

        return head;
    }

    /** Пиксели нарисованной иконки (ARGB, альфа не домножена) — в PNG в фоне, потом в кеш. */
    static void drawn(Job job, int[] argb)
    {
        Minecraft mc = Minecraft.getInstance();

        CompletableFuture.supplyAsync(() -> encode(argb, job.size), Util.backgroundExecutor())
                .whenCompleteAsync((png, error) ->
                {
                    if (png != null)
                    {
                        finish(job, png, true);
                    }
                    else
                    {
                        failed(job, error, false);
                    }
                }, mc);
    }

    /**
     * Иконку не получили — плашка; в лог только первый случай. {@code remember}: плашку кешировать
     * (игра этот предмет не рисует — нечего пробовать на каждой выгрузке) или нет (сбой разовый).
     */
    static void failed(Job job, @Nullable Throwable error, boolean remember)
    {
        if (! warnedFailure)
        {
            warnedFailure = true;
            LOGGER.warn("Item icons: no picture for {}, using a colour tile; further failures are not logged", job.label, error);
        }

        int color;

        try
        {
            color = average(job.stack);
        }
        catch (RuntimeException | LinkageError e)
        {
            color = UNKNOWN_TILE;
        }

        finish(job, encode(tile(color, job.size), job.size), remember);
    }

    // ===== Внутреннее =====

    /**
     * Сборка кадра GUI: есть очередь — положить в состояние кадра невидимую «картинку в картинке»
     * 1×1, тогда рендер GUI этого кадра позовёт {@link IconRenderer#prepare} (на экран ничего не
     * ляжет). Одна заявка на кадр: уже лежащую не дублируем.
     */
    private static void requestFrame()
    {
        if (QUEUE.isEmpty())
        {
            return;
        }

        GuiRenderState frame = ((com.aell.layerlist.mixin.schematics.IMixinGameRendererGui) Minecraft.getInstance().gameRenderer).layerlist$guiRenderState();
        boolean[] present = {false};
        frame.forEachPictureInPicture(state -> present[0] |= state == IconRenderer.Pass.INSTANCE);

        if (! present[0])
        {
            frame.submitPicturesInPictureState(IconRenderer.Pass.INSTANCE);
        }
    }

    private static void finish(Job job, byte[] png, boolean cache)
    {
        if (PENDING.get(job.key) == job)
        {
            PENDING.remove(job.key);
        }

        if (cache && job.generation == generation)
        {
            CACHE.put(job.key, png);

            if (CACHE.size() > MAX_CACHED)
            {
                Iterator<IconKey> eldest = CACHE.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }

        job.future.complete(png);
    }

    private static void tick()
    {
        long now = Util.getMillis();

        if (PENDING.isEmpty())
        {
            IconRenderer active = renderer;

            if (active != null && now - lastActivity > IDLE_RELEASE_MS)
            {
                active.releaseTextures();
            }

            return;
        }

        lastActivity = now;

        List<Job> expired = new ArrayList<>();

        for (Job job : PENDING.values())
        {
            long since = job.drawnAt >= 0L ? job.drawnAt : job.queuedAt;

            if (now - since > TIMEOUT_MS)
            {
                expired.add(job);
            }
        }

        for (Job job : expired)
        {
            QUEUE.remove(job);

            if (! warnedTimeout)
            {
                warnedTimeout = true;
                LOGGER.warn("Item icons: no picture for {} after {} ms ({}), using colour tiles; further timeouts are not logged",
                        job.label, TIMEOUT_MS, job.drawnAt >= 0L ? "the GPU read-back never arrived" : "the GUI renderer never ran");
            }

            // Плашку по таймауту не кешируем: в следующий раз кадры, может быть, пойдут
            finish(job, encode(tile(average(job.stack), job.size), job.size), false);
        }
    }

    private static int clampSize(int size)
    {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    private static int average(ItemStack stack)
    {
        int color = ItemColors.forStack(stack);

        return color != 0 ? 0xFF000000 | color : UNKNOWN_TILE;
    }

    /** Запасная плашка: скруглённый квадрат цвета предмета с тёмной каймой. */
    private static int[] tile(int color, int size)
    {
        int[] out = new int[size * size];
        int inset = Math.max(1, size / 8);
        int last = size - inset - 1;
        int edge = darker(color);

        for (int y = inset; y <= last; ++y)
        {
            for (int x = inset; x <= last; ++x)
            {
                boolean cornerX = x == inset || x == last;
                boolean cornerY = y == inset || y == last;

                if (cornerX && cornerY)
                {
                    continue;
                }

                out[y * size + x] = cornerX || cornerY ? edge : color;
            }
        }

        return out;
    }

    private static int darker(int argb)
    {
        int r = Math.round(((argb >> 16) & 0xFF) * TILE_EDGE);
        int g = Math.round(((argb >> 8) & 0xFF) * TILE_EDGE);
        int b = Math.round((argb & 0xFF) * TILE_EDGE);

        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static byte[] encode(int[] argb, int size)
    {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, size, size, argb, 0, size);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);

        // Поток в памяти: без него ImageIO пишет промежуточный файл во временную папку
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes))
        {
            if (! ImageIO.write(image, "png", stream))
            {
                throw new IllegalStateException("no PNG writer");
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }

        return bytes.toByteArray();
    }

    /** Ключ кеша: предмет, все его компоненты (сравнение по значению) и размер. */
    private record IconKey(Item item, DataComponentPatch components, int size)
    {
        static IconKey of(ItemStack stack, int size)
        {
            return new IconKey(stack.getItem(), stack.getComponentsPatch(), size);
        }
    }

    /** Одна заявка: предмет (копия, 1 шт.), размер, будущее; когда встала в очередь и когда нарисована. */
    static final class Job
    {
        final IconKey key;
        /** Для лога: ключ строки и размер. */
        final String label;
        final ItemStack stack;
        final int size;
        final long queuedAt;
        final int generation;
        final CompletableFuture<byte[]> future = new CompletableFuture<>();
        long drawnAt = -1L;

        Job(IconKey key, String label, ItemStack stack, int size, long queuedAt, int generation)
        {
            this.key = key;
            this.label = label;
            this.stack = stack;
            this.size = size;
            this.queuedAt = queuedAt;
            this.generation = generation;
        }
    }
}
