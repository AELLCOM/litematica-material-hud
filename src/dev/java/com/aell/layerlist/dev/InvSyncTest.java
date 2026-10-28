package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.InventorySync;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.util.LayerMode;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code sync}: «иногда ставится не тот блок, если быстро строить» (жалоба 09.10.2026, 10 блоков/с на
 * сервере). Схема 5×5 вокруг игрока — 24 клетки, пары блоков «A, B, A, B» (12 разных блоков); все блоки в рюкзаке,
 * хотбар забит яблоками, так что Litematica перед каждой установкой меняет предмет в руке кликом по
 * инвентарю. Ставим по клетке на два тика (10 блоков/с) и сверяем мир сервера со схемой.
 * <ul>
 *   <li>задержка ответов сервера 150 мс (пинг), защиты нет — гонка воспроизводится: на сервере встают
 *       не те блоки (это не проверка, а подтверждение причины — пишется в отчёт);</li>
 *   <li>та же задержка, защита вкл — всё встало как в схеме; обмен ждал ответа, своя установка после
 *       своего обмена — нет;</li>
 *   <li>одиночная игра без задержки — ожидание 0, ничего не ждёт, всё встаёт;</li>
 *   <li>чужой клик по инвентарю (автопополнение) — установка ждёт, потом снова можно.</li>
 * </ul>
 * С Easy Place Fix+ в {@code run/mods} работает его копия {@code InventorySync} — тест настраивает обе.
 */
@Environment(EnvType.CLIENT)
public final class InvSyncTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(720, 100, 0);
    private static final BlockPos TARGET = new BlockPos(720, 100, 20);
    /** Пинг, скорость и число типов — из окружения ({@code LL_SYNC_LAG}, {@code LL_SYNC_RATE}, {@code LL_SYNC_TYPES}). */
    private static final long LAG_MS = env("LL_SYNC_LAG", 150);
    private static final int RATE = (int) env("LL_SYNC_RATE", 10);
    private static final int TYPES = (int) Math.max(2, Math.min(12, env("LL_SYNC_TYPES", 12)));
    private static final long RUN_MS = 20000L;
    private static final Block[] MATS = {
            Blocks.STONE, Blocks.DIRT, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
            Blocks.SANDSTONE, Blocks.BRICKS, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.MOSSY_COBBLESTONE};
    private static final String EPF_SYNC = "com.aell.easyplacefixplus.core.InventorySync";

    private final List<BlockPos> cells = new ArrayList<>();
    private final List<Block> wanted = new ArrayList<>();
    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private int waits;
    private Lag lag;
    private int index;
    private long runDeadline;
    private long runStarted;
    private long runTook;
    private long lastTick = Long.MIN_VALUE;
    private long resumeTick = Long.MIN_VALUE;
    private int attempts;

    @Override
    public String name()
    {
        return "SYNC (не тот блок на скорости: обмен в руку и ответ сервера)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        LayerListConfigs.GHOST_INVENTORY_ONLY.setBooleanValue(false);
        CovOut.log("[sync] Easy Place ведёт " + (FabricLoader.getInstance().isModLoaded("easyplacefixplus")
                ? "Easy Place Fix+" : "Material HUD") + "; пинг " + LAG_MS + " мс, " + RATE + " блоков/с, типов " + TYPES);
        setRate(RATE);

        for (int x = 0; x < 5; ++x)
        {
            for (int z = 0; z < 5; ++z)
            {
                if (x != 2 || z != 2)
                {
                    this.cells.add(new BlockPos(x, 0, z));
                    // Пары «A, B, A, B»: устаревший ответ сервера про A приходит, когда в руке уже B, —
                    // клиент думает, что держит A, и ставит без обмена; сервер ставит B
                    int i = this.cells.size() - 1;
                    this.wanted.add(MATS[(i / 4 * 2 + i % 2) % TYPES]);
                }
            }
        }

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, "forceload add 710 -10 735 35");

            for (BlockPos o : new BlockPos[]{REF, TARGET})
            {
                command(server, fill(o, -2, -1, -2, 6, -1, 6, "minecraft:stone"));
                command(server, fill(o, -2, 0, -2, 6, 4, 6, "minecraft:air"));
            }

            for (int i = 0; i < this.cells.size(); ++i)
            {
                command(server, set(REF, this.cells.get(i), BuiltInRegistries.BLOCK.getKey(this.wanted.get(i)).toString()));
            }

            // Центр площадки — там пол есть и схема пуста: игрок не падает и не теряет вещи
            command(server, "tp @a " + (REF.getX() + 2.5D) + " " + REF.getY() + " " + (REF.getZ() + 2.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null || mc.level == null)
        {
            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        long wait = 2000L;

        switch (this.stage)
        {
            case 0 ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-sync");
                area.addSubRegionBox(new Box(REF, REF.offset(4, 0, 4), "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);
                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-sync", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(this.placement);
                this.resetArena(server);
                LayerListConfigs.EP_ENABLED.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                Configs.Generic.EASY_PLACE_SP_HANDLING.setBooleanValue(false);
                Configs.Generic.EASY_PLACE_PROTOCOL.setOptionListValue(EasyPlaceProtocol.NONE);
                wait = 3000L;
            }
            case 1 ->
            {
                var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

                if ((world == null || world.getBlockState(TARGET.offset(this.cells.get(0))).isAir()) && ++this.waits < 40)
                {
                    this.nextAt = now + 250L;
                    return false;
                }

                this.lag = Lag.install(mc);
                this.lag.delayMs = LAG_MS;
                setForced(0L);
                this.startRun(now);
                CovOut.log("[sync] задержка ответов сервера " + LAG_MS + " мс, защиты нет (ожидание 0)");
                wait = 0L;
            }
            case 2, 5, 8 ->
            {
                if (this.runStep(mc, now))
                {
                    return false;
                }

                wait = 2500L;
            }
            case 3 ->
            {
                int[] r = this.count(server, "без защиты");
                CovOut.log("[sync] ИНФО без защиты при пинге " + LAG_MS + " мс: не тех блоков " + r[0] + ", не встало " + r[1]
                        + " из " + this.cells.size() + (r[0] > 0 ? " — гонка воспроизведена" : " — гонка в этот раз не проявилась"));
                this.resetArena(server);
                wait = 3000L;
            }
            case 4 ->
            {
                setForced(2L * LAG_MS + 60L);
                this.startRun(now);
                CovOut.log("[sync] та же задержка, защита вкл (ожидание " + (2L * LAG_MS + 60L) + " мс)");
                wait = 0L;
            }
            case 6 ->
            {
                int[] r = this.count(server, "с защитой");
                int[] w = waitCounts();
                this.check("с защитой при пинге " + LAG_MS + " мс: не тех блоков нет", r[0] == 0, "не тех " + r[0]);
                this.check("с защитой: все блоки встали", r[1] == 0, "не встало " + r[1] + " за " + this.runTook + " мс");
                this.check("с защитой: обмен в руку ждал ответа сервера", w[0] > 0, "ожиданий обмена " + w[0]);
                this.check("своя установка сразу после своего обмена не ждёт", w[1] == 0, "ожиданий установки " + w[1]);
                CovOut.log("[sync] с защитой: 24 блока за " + this.runTook + " мс (" + String.format(java.util.Locale.ROOT, "%.1f", 24000.0 / Math.max(1, this.runTook)) + " блоков/с)");
                this.lag.delayMs = 0L;
                setForced(-1L);
                this.resetArena(server);
                wait = 3000L;
            }
            case 7 ->
            {
                this.check("одиночная игра: ожидание 0", settleMs() == 0L, "ожидание " + settleMs());
                this.startRun(now);
                wait = 0L;
            }
            case 9 ->
            {
                int[] r = this.count(server, "одиночная");
                int[] w = waitCounts();
                this.check("одиночная игра: всё встало как в схеме", r[0] == 0 && r[1] == 0, "не тех " + r[0] + ", не встало " + r[1]);
                this.check("одиночная игра: ничего не ждёт", w[0] == 0 && w[1] == 0, "обмен " + w[0] + ", установка " + w[1]);
                CovOut.log("[sync] одиночная: 24 блока за " + this.runTook + " мс (" + String.format(java.util.Locale.ROOT, "%.1f", 24000.0 / Math.max(1, this.runTook)) + " блоков/с)");
                // Чужой клик по инвентарю (как автопополнение): обмен рюкзак → хотбар 1
                setForced(300L);
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, 9, 1, ContainerInput.SWAP, mc.player);
                this.check("чужой клик по инвентарю: установка ждёт", placeTooSoon(), "");
                wait = 450L;
            }
            case 10 ->
            {
                this.check("чужой клик по инвентарю: через ожидание установка снова идёт", ! placeTooSoon(), "");
                setForced(-1L);
                wait = 300L;
            }
            default ->
            {
                CovOut.log("[sync] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        this.nextAt = now + wait;
        ++this.stage;
        return false;
    }

    private void startRun(long now)
    {
        this.index = 0;
        this.runStarted = now;
        this.runDeadline = now + RUN_MS;
        this.resumeTick = Long.MIN_VALUE;
        this.attempts = 0;
        resetCounts();
    }

    /** Шаг стройки: по клетке на два тика. Истина — ещё строим. */
    private boolean runStep(Minecraft mc, long now)
    {
        long tick = mc.level.getGameTime();

        if (this.index >= this.cells.size() || now > this.runDeadline)
        {
            this.runTook = now - this.runStarted;
            return false;
        }

        if (tick == this.lastTick || tick < this.resumeTick)
        {
            return true;
        }

        this.lastTick = tick;
        BlockPos cell = TARGET.offset(this.cells.get(this.index));

        if (! mc.level.getBlockState(cell).isAir())
        {
            this.attempts = 0;
            ++this.index;

            // 10 блоков/с — следующая клетка через тик; 20 — в этом же тике
            if (RATE < 20 || this.index >= this.cells.size())
            {
                this.resumeTick = tick + 1;
                return true;
            }

            cell = TARGET.offset(this.cells.get(this.index));
        }

        if (TRACE && this.stage == 5)
        {
            trace(mc, tick, cell);
        }

        this.easyPlace(mc, cell);

        if (++this.attempts == 10)
        {
            var sw = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            CovOut.log("[sync] клетка " + this.cells.get(this.index).toShortString() + " не ставится: в руке "
                    + mc.player.getMainHandItem() + ", слот " + mc.player.getInventory().getSelectedSlot()
                    + ", схема " + (sw == null ? "нет" : sw.getBlockState(cell)) + ", игрок " + mc.player.blockPosition().toShortString()
                    + ", взгляд " + mc.hitResult + ", режим " + mc.gameMode.getPlayerMode());
        }

        return true;
    }

    /** Взгляд на середину верхней грани клетки и шаг Easy Place. */
    private void easyPlace(Minecraft mc, BlockPos cell)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        Vec3 aim = Vec3.atLowerCornerOf(cell).add(0.5D, 0.98D, 0.5D);
        double dx = aim.x - eyes.x;
        double dy = aim.y - eyes.y;
        double dz = aim.z - eyes.z;
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        WorldUtils.handleEasyPlace(mc);
    }

    /** {не тех блоков, не встало} в мире сервера. */
    private int[] count(MinecraftServer server, String what)
    {
        int[] r = server.submit(() -> {
            int wrong = 0;
            int missing = 0;
            StringBuilder detail = new StringBuilder();

            for (int i = 0; i < this.cells.size(); ++i)
            {
                var state = server.overworld().getBlockState(TARGET.offset(this.cells.get(i)));

                if (state.isAir())
                {
                    ++missing;
                }
                else if (! state.is(this.wanted.get(i)))
                {
                    ++wrong;
                    detail.append(' ').append(this.cells.get(i).toShortString()).append(": ждали ")
                            .append(BuiltInRegistries.BLOCK.getKey(this.wanted.get(i)).getPath()).append(", встал ")
                            .append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()).append(';');
                }
            }

            if (! detail.isEmpty())
            {
                CovOut.log("[sync] " + what + ":" + detail);
            }

            return new int[]{wrong, missing};
        }).join();

        return r;
    }

    /** Площадка пустая, у игрока по 4 штуки каждого блока в рюкзаке, хотбар забит яблоками. */
    private void resetArena(MinecraftServer server)
    {
        server.execute(() -> {
            command(server, fill(TARGET, -2, 0, -2, 6, 4, 6, "minecraft:air"));
            command(server, "tp @a " + (TARGET.getX() + 2.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 2.5D) + " 0 60");
            command(server, "gamemode survival @a");
            command(server, "clear @a");
            command(server, "effect give @a minecraft:saturation 600 1 true");

            // LL_SYNC_HOTBAR=1: первые девять типов — в хотбаре (обычная стройка), остальные — в рюкзаке;
            // иначе хотбар забит яблоками и каждый блок идёт обменом из рюкзака (худший случай)
            boolean hotbar = "1".equals(System.getenv("LL_SYNC_HOTBAR"));

            for (int i = 0; i < 9; ++i)
            {
                command(server, "item replace entity @a hotbar." + i + " with "
                        + (hotbar && i < TYPES ? BuiltInRegistries.BLOCK.getKey(MATS[i]) + " 6" : "minecraft:apple"));
            }

            for (int i = hotbar ? 9 : 0; i < TYPES; ++i)
            {
                command(server, "item replace entity @a inventory." + i + " with "
                        + BuiltInRegistries.BLOCK.getKey(MATS[i]) + " 6");
            }

            command(server, "tp @a " + (TARGET.getX() + 2.5D) + " " + TARGET.getY() + " " + (TARGET.getZ() + 2.5D) + " 0 60");
        });
    }

    private static final boolean TRACE = "1".equals(System.getenv("LL_SYNC_TRACE"));

    /** Тик защищённого прохода: клетка, рука, ожидания InventorySync (рефлексией — у того, кто ведёт). */
    private void trace(Minecraft mc, long tick, BlockPos cell)
    {
        StringBuilder out = new StringBuilder("[sync] тик ").append(tick).append(" клетка ").append(this.index)
                .append(" рука ").append(mc.player.getMainHandItem().getItem()).append(" слот ")
                .append(mc.player.getInventory().getSelectedSlot());

        try
        {
            Class<?> sync = epf() ? Class.forName(EPF_SYNC) : InventorySync.class;
            out.append(" обменРано=").append(sync.getMethod("swapTooSoon").invoke(null));

            for (String name : new String[]{"placeAcked", "placeEvent", "slotEvent", "placeAt", "placeAckAt"})
            {
                Field f = sync.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(null);

                if (v instanceof Long l && (name.endsWith("At")))
                {
                    v = l < 0 ? "—" : (net.minecraft.util.Util.getMillis() - l) + "мс назад";
                }

                out.append(' ').append(name).append('=').append(v);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            out.append(" ?").append(e);
        }

        CovOut.log(out.toString());
    }

    private static long env(String name, long fallback)
    {
        String value = System.getenv(name);

        try
        {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    /** «Блоков в секунду» там, где ведётся Easy Place: Material HUD и, если стоит, Easy Place Fix+. */
    private static void setRate(int rate)
    {
        LayerListConfigs.EP_PLACE_RATE.setIntegerValue(rate);

        if (epf())
        {
            try
            {
                Object config = Class.forName("com.aell.easyplacefixplus.config.FixConfigs").getField("EP_PLACE_RATE").get(null);
                config.getClass().getMethod("setIntegerValue", int.class).invoke(config, rate);
            }
            catch (ReflectiveOperationException | LinkageError ignored)
            {
            }
        }
    }

    // Обе копии InventorySync: Material HUD и, если стоит, Easy Place Fix+

    private static boolean epf()
    {
        return FabricLoader.getInstance().isModLoaded("easyplacefixplus");
    }

    private static void setForced(long ms)
    {
        InventorySync.forcedSettleMs = ms;

        if (epf())
        {
            try
            {
                Class.forName(EPF_SYNC).getField("forcedSettleMs").setLong(null, ms);
            }
            catch (ReflectiveOperationException | LinkageError e)
            {
                CovOut.log("[sync] Easy Place Fix+ без InventorySync: " + e);
            }
        }
    }

    private static void resetCounts()
    {
        InventorySync.swapWaits = 0;
        InventorySync.placeWaits = 0;

        if (epf())
        {
            try
            {
                Class.forName(EPF_SYNC).getField("swapWaits").setInt(null, 0);
                Class.forName(EPF_SYNC).getField("placeWaits").setInt(null, 0);
            }
            catch (ReflectiveOperationException | LinkageError ignored)
            {
            }
        }
    }

    /** {ожиданий обмена, ожиданий установки} у того, кто ведёт Easy Place. */
    private static int[] waitCounts()
    {
        if (epf())
        {
            try
            {
                return new int[]{Class.forName(EPF_SYNC).getField("swapWaits").getInt(null),
                        Class.forName(EPF_SYNC).getField("placeWaits").getInt(null)};
            }
            catch (ReflectiveOperationException | LinkageError e)
            {
                return new int[]{-1, -1};
            }
        }

        return new int[]{InventorySync.swapWaits, InventorySync.placeWaits};
    }

    private static long settleMs()
    {
        return epf() ? (long) callEpf("settleMs") : InventorySync.settleMs();
    }

    private static boolean placeTooSoon()
    {
        return epf() ? (boolean) callEpf("placeTooSoon") : InventorySync.placeTooSoon();
    }

    private static Object callEpf(String method)
    {
        try
        {
            return Class.forName(EPF_SYNC).getMethod(method).invoke(null);
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Задержка входящих пакетов на клиенте — как пинг до сервера. Очередь держит порядок: пакет уходит дальше
     * не раньше своего срока и не раньше тех, кто пришёл до него (смена задержки ничего не переставит).
     */
    static final class Lag extends ChannelInboundHandlerAdapter
    {
        volatile long delayMs;
        private final ArrayDeque<Object[]> queue = new ArrayDeque<>();

        static Lag install(Minecraft mc)
        {
            try
            {
                Connection connection = mc.getConnection().getConnection();
                Channel channel = null;

                for (Field field : Connection.class.getDeclaredFields())
                {
                    if (field.getType() == Channel.class)
                    {
                        field.setAccessible(true);
                        channel = (Channel) field.get(connection);
                    }
                }

                Lag lag = new Lag();

                if (channel.pipeline().get("dev_lag") != null)
                {
                    lag = (Lag) channel.pipeline().get("dev_lag");
                }
                else
                {
                    channel.pipeline().addBefore("packet_handler", "dev_lag", lag);
                }

                return lag;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                CovOut.log("[sync] задержку не поставить: " + e);
                return new Lag();
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
        {
            long delay = this.delayMs;

            if (delay <= 0L && this.queue.isEmpty())
            {
                ctx.fireChannelRead(msg);
                return;
            }

            this.queue.add(new Object[]{msg, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, delay))});
            ctx.executor().schedule(() -> this.drain(ctx), Math.max(0L, delay), TimeUnit.MILLISECONDS);
        }

        private void drain(ChannelHandlerContext ctx)
        {
            long now = System.nanoTime();

            while (! this.queue.isEmpty() && (long) this.queue.peek()[1] <= now)
            {
                ctx.fireChannelRead(this.queue.poll()[0]);
            }
        }
    }

    private static String set(BlockPos origin, BlockPos c, String block)
    {
        BlockPos pos = origin.offset(c);
        return String.format(Locale.ROOT, "setblock %d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), block);
    }

    private static String fill(BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2, String block)
    {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", o.getX() + x1, o.getY() + y1, o.getZ() + z1,
                o.getX() + x2, o.getY() + y2, o.getZ() + z2, block);
    }

    private void check(String what, boolean ok, String detail)
    {
        CovOut.log("[sync] " + (ok ? "OK   " : "FAIL ") + what + (detail.isEmpty() ? "" : " — " + detail));
        this.failed |= ! ok;
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[sync] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        if (this.lag != null)
        {
            this.lag.delayMs = 0L;
        }

        setForced(-1L);
        setRate(10);

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
    }
}
