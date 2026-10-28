package com.aell.layerlist.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Харнесс покрытия Easy Place. Живёт в наборе исходников {@code src/dev/java}: в jar не
 * попадает, цепляется отражением из {@code LayerListClient} и только при заданной
 * переменной окружения {@code LL_COVERAGE}.
 *
 * <p>{@code LL_COVERAGE=a,b,c} — какие дампы снять (или {@code all}). Клиент сам заходит
 * в плоский креативный мир {@code ep_coverage}, готовит площадку, пишет дампы в
 * {@code docs/easyplace/coverage} и выходит. {@code LL_COVERAGE_KEEP=1} оставляет клиент
 * запущенным, чтобы можно было продолжить руками через {@code /ephud coverage <режим>}.
 */
@Environment(EnvType.CLIENT)
public final class Coverage
{
    private static final String WORLD = "ep_coverage";
    private static final long SLICE_NANOS = 25_000_000L;
    private static final int BOOT_TICKS = 60;
    private static final int SETTLE_TICKS = 40;

    private enum Stage
    {
        BOOT,
        WAIT_WORLD,
        PREPARE,
        RUN,
        DONE,
        IDLE
    }

    private static Stage stage = Stage.BOOT;
    private static int ticks;
    private static int stageTicks;
    private static boolean keepAlive;
    private static final Deque<CovJob> JOBS = new ArrayDeque<>();
    private static CovJob current;

    private Coverage() {}

    private static int styleOf(String env)
    {
        return "2".equals(env) ? 2 : "1".equals(env) ? 1 : 0;
    }

    public static void register()
    {
        keepAlive = "1".equals(System.getenv("LL_COVERAGE_KEEP"));

        String modes = System.getenv("LL_COVERAGE");

        // LL_UI_VANILLA=0|1|2 — оформление на весь прогон (Aell UI / Vanilla / Vanilla+), для туров по окнам
        String vanilla = System.getenv("LL_UI_VANILLA");

        if (vanilla != null)
        {
            com.aell.layerlist.config.LayerListConfigs.UI_STYLE.setIntegerValue(styleOf(vanilla));
        }

        CovOut.log("харнесс включён, режимы: " + modes + ", каталог: " + CovOut.dir());
        queue(modes);

        ClientTickEvents.END_CLIENT_TICK.register(Coverage::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("ephud")
                        .then(ClientCommands.literal("coverage")
                                .then(ClientCommands.argument("mode", StringArgumentType.word())
                                        .executes(context ->
                                        {
                                            String mode = StringArgumentType.getString(context, "mode");

                                            context.getSource().sendFeedback(
                                                    Component.literal("[COVERAGE] запуск: " + mode));
                                            queue(mode);
                                            stage = Stage.PREPARE;
                                            stageTicks = 0;

                                            return 1;
                                        })))));
    }

    private static void queue(String modes)
    {
        if (modes == null)
        {
            return;
        }

        String lower = modes.toLowerCase(Locale.ROOT);
        List<String> wanted = new ArrayList<>();

        if (lower.contains("all"))
        {
            wanted.add("a");
            wanted.add("b");
            wanted.add("c");
        }
        else
        {
            for (String part : lower.split("[,;+ ]+"))
            {
                if (! part.isBlank())
                {
                    wanted.add(part.trim());
                }
            }
        }

        for (String mode : wanted)
        {
            switch (mode)
            {
                case "a", "registry" -> JOBS.add(new DumpRegistry());
                case "b", "solver" -> JOBS.add(new DumpSolver());
                case "c", "place" -> JOBS.add(new DumpPlace());
                case "d", "data" -> JOBS.add(new DumpData());
                case "e", "list" -> JOBS.add(new DumpListItems());
                case "f", "entities" -> JOBS.add(new DumpEntities());
                case "g", "contracts" -> JOBS.add(new ContractCheck());
                case "h", "plants" -> JOBS.add(new DumpPlants());
                case "i", "tour" -> JOBS.add(new TourShots());
                case "j", "look" -> JOBS.add(new DumpLook());
                case "k", "schematics" -> JOBS.add(new DumpSchematics());
                case "x", "banner" -> JOBS.add(new BannerTest());
                case "z", "freeze" -> JOBS.add(new FreezeTest());
                case "p", "palette" -> JOBS.add(new PaletteTest());
                case "r", "redstone" -> JOBS.add(new DumpRedstone());
                case "q", "storagepot" -> JOBS.add(new StoragePotTest());
                case "o", "ghostinv" -> JOBS.add(new GhostInvTest());
                case "u", "support" -> JOBS.add(new SupportTest());
                case "transferclose" -> JOBS.add(new TransferCloseTest());
                case "nestedsearch" -> JOBS.add(new NestedSearchTest());
                case "stockhud" -> JOBS.add(new StockHudTest());
                case "countformat" -> JOBS.add(new CountFormatTest());
                case "escguard" -> JOBS.add(new EscGuardTest());
                case "transferbox" -> JOBS.add(new TransferBoxTest());
                case "selworld" -> JOBS.add(new SelWorldTest());
                case "selperf" -> JOBS.add(new SelPerfTest());
                case "selslice" -> JOBS.add(new SelSliceTest());
                case "endghost" -> JOBS.add(new EndPortalGhostTest());
                case "fixes" -> JOBS.add(new FixesTest());
                case "verifyfix" -> JOBS.add(new VerifyFixTest());
                case "listsupport" -> JOBS.add(new ListSupportTest());
                case "listcache" -> JOBS.add(new ListCacheTest());
                case "listbobby" -> JOBS.add(new ListBobbyTest());
                case "listperf" -> JOBS.add(new ListPerfTest());
                case "v", "invaim" -> JOBS.add(new InvAimTest());
                case "finish" -> JOBS.add(new FinishTest());
                case "zfverify" -> JOBS.add(new ZfVerifyTest());
                case "zonelib" -> JOBS.add(new ZoneLibTest());
                case "zoneplace" -> JOBS.add(new ZonePlaceTest());
                case "chestsweep" -> JOBS.add(new SearchAllTest());
                case "syncmatica" -> JOBS.add(new SyncTest());
                case "nozf" -> JOBS.add(new ZoneNoZfTest());
                case "zonecard" -> JOBS.add(new ZoneCardShot());
                case "strict" -> JOBS.add(new StrictTest());
                case "sync" -> JOBS.add(new InvSyncTest());
                case "build" -> JOBS.add(new BuildTest());
                case "frames" -> JOBS.add(new SupportFrameTest());
                case "entghost" -> JOBS.add(new EntityGhostShot());
                case "refuse" -> JOBS.add(new RefuseTest());
                case "toggles" -> JOBS.add(new ToggleKeysTest());
                case "longlines" -> JOBS.add(new LongLinesTest());
                case "light" -> JOBS.add(new LightTest());
                case "glow" -> JOBS.add(new GlowShot());
                case "dig" -> JOBS.add(new DigGuardTest());
                case "zfviews" -> JOBS.add(new ZfViewsTest());
                case "zftab" -> JOBS.add(new ZfTabShot());
                case "s", "epfsupport" -> JOBS.add(new EpfSupportTest());
                case "m", "settingsshot" -> JOBS.add(new SettingsShot());
                case "l", "zonesshot" -> JOBS.add(new ZonesShot());
                case "n", "icons" -> JOBS.add(new DumpIcons());
                case "t", "legacy" -> JOBS.add(new DumpLegacy());
                default -> CovOut.log("неизвестный режим: " + mode);
            }
        }
    }

    private static void tick(Minecraft mc)
    {
        // Стиль из LL_UI_VANILLA держится весь прогон: malilib перечитывает настройки при входе в мир
        String uiVanilla = System.getenv("LL_UI_VANILLA");

        if (uiVanilla != null && com.aell.layerlist.config.LayerListConfigs.UI_STYLE.getIntegerValue() != styleOf(uiVanilla))
        {
            com.aell.layerlist.config.LayerListConfigs.UI_STYLE.setIntegerValue(styleOf(uiVanilla));
        }

        ++ticks;
        ++stageTicks;

        try
        {
            switch (stage)
            {
                case BOOT -> boot(mc);
                case WAIT_WORLD -> waitWorld(mc);
                case PREPARE -> prepare(mc);
                case RUN -> run(mc);
                case DONE -> finish(mc);
                case IDLE ->
                {
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            CovOut.log("сбой на стадии " + stage + ": " + e);
            e.printStackTrace(System.out);
            stage = Stage.IDLE;
        }
    }

    private static void boot(Minecraft mc)
    {
        if (JOBS.isEmpty())
        {
            stage = Stage.IDLE;
            return;
        }

        if (mc.level != null && mc.player != null)
        {
            enter(Stage.WAIT_WORLD);
            return;
        }

        if (ticks < BOOT_TICKS)
        {
            return;
        }

        // LL_SERVER=адрес:порт — к настоящему серверу (пинг, порядок пакетов), а не в одиночный мир
        String remote = System.getenv("LL_SERVER");

        if (remote != null && ! remote.isBlank())
        {
            CovOut.log("подключаюсь к серверу " + remote);
            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), mc,
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(remote),
                    new net.minecraft.client.multiplayer.ServerData("dev", remote, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
                    false, null);
            enter(Stage.WAIT_WORLD);
            return;
        }

        CovOut.log("открываю мир " + WORLD);
        mc.gui.setScreen(null);

        // Открытие существующего мира упирается в экраны подтверждения (бэкап,
        // экспериментальные настройки), а нажать в них некому: мир всегда создаём заново.
        if (mc.getLevelSource().levelExists(WORLD))
        {
            drop(mc);
        }

        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                WorldDataConfiguration.DEFAULT);

        // LL_COVERAGE_TERRAIN=1 — обычный рельеф с постоянным сидом (замеры превью области на настоящей местности)
        if ("1".equals(System.getenv("LL_COVERAGE_TERRAIN")))
        {
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261019L, false, false),
                    WorldPresets::createNormalWorldDimensions, null);
        }
        else
        {
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings,
                    WorldOptions.testWorldWithRandomSeed(), WorldPresets::createTestWorldDimensions,
                    null);
        }

        enter(Stage.WAIT_WORLD);
    }

    private static void drop(Minecraft mc)
    {
        java.nio.file.Path save = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("saves").resolve(WORLD);

        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(save))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path ->
            {
                try
                {
                    java.nio.file.Files.deleteIfExists(path);
                }
                catch (java.io.IOException e)
                {
                    CovOut.log("не удалить " + path + ": " + e);
                }
            });

            CovOut.log("старый мир удалён");
        }
        catch (java.io.IOException e)
        {
            CovOut.log("старый мир не удалить: " + e);
        }
    }

    private static void waitWorld(Minecraft mc)
    {
        if (stageTicks > 20 * 120)
        {
            CovOut.log("мир так и не загрузился за две минуты");
            stage = Stage.IDLE;
            return;
        }

        MinecraftServer server = mc.getSingleplayerServer();
        boolean remote = System.getenv("LL_SERVER") != null;

        if (mc.level == null || mc.player == null || (! remote && (server == null || ! server.isReady())))
        {
            return;
        }

        // Пока клиент не доложил серверу «мир загружен», сервер молча выбрасывает
        // ServerboundUseItemOnPacket: клики уходят в никуда, а предупреждений нет.
        if (mc.player.connection == null || ! mc.player.connection.hasClientLoaded())
        {
            if (stageTicks % 40 == 0)
            {
                CovOut.log("жду доклада «мир загружен» от клиента");
            }

            return;
        }

        mc.gui.setScreen(null);
        // Площадку CovArena готовит встроенный сервер — на настоящем её нет, режимы готовят своё сами
        enter(remote ? Stage.RUN : Stage.PREPARE);
    }

    private static void prepare(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null || mc.level == null)
        {
            return;
        }

        if (stageTicks == 1)
        {
            CovArena.prepare(server);
            CovOut.log("готовлю площадку");
            // Litematica в одиночной игре снимает с сервера проверку точки клика (не дальше блока от середины
            // кликнутого блока) — настоящий сервер такие клики молча выбрасывает. Стенд по умолчанию строгий, как
            // сервер (10.10.2026: подушки на снегу вставали на стенде и не вставали на сервере); LL_SP_BYPASS=1 — как было
            boolean bypass = "1".equals(System.getenv("LL_SP_BYPASS"));
            fi.dy.masa.litematica.config.Configs.Generic.ITEM_USE_PACKET_CHECK_BYPASS.setBooleanValue(bypass);
            CovOut.log("проверка точки клика на сервере: " + (bypass ? "снята (как в одиночной игре)" : "как на настоящем сервере"));
        }

        if (stageTicks < SETTLE_TICKS)
        {
            return;
        }

        ServerLevel level = server.overworld();

        if (! CovArena.ready(mc, level))
        {
            if (stageTicks % 20 == 0)
            {
                CovOut.log("жду синхронизации площадки");
            }

            if (stageTicks > 20 * 60)
            {
                CovOut.log("площадка не синхронизировалась — работаю как есть");
                enter(Stage.RUN);
            }

            return;
        }

        CovOut.log("площадка готова");
        enter(Stage.RUN);
    }

    private static void run(Minecraft mc)
    {
        if (current == null)
        {
            current = JOBS.poll();

            if (current == null)
            {
                enter(Stage.DONE);
                return;
            }

            CovOut.log("=== дамп " + current.name() + " ===");

            if (! current.begin(mc))
            {
                CovOut.log("дамп " + current.name() + " не запустился");
                current = null;
                return;
            }
        }

        long deadline = System.nanoTime() + SLICE_NANOS;

        while (System.nanoTime() < deadline)
        {
            if (current.step(mc))
            {
                CovOut.log("=== дамп " + current.name() + " готов ===");
                current.close();
                current = null;
                return;
            }

            if (current.waiting())
            {
                return;
            }
        }
    }

    private static void finish(Minecraft mc)
    {
        CovOut.log("все дампы сняты, каталог " + CovOut.dir());

        if (keepAlive)
        {
            CovOut.log("LL_COVERAGE_KEEP=1 — клиент остаётся запущенным");
            stage = Stage.IDLE;
            return;
        }

        stage = Stage.IDLE;
        mc.stop();
    }

    private static void enter(Stage next)
    {
        stage = next;
        stageTicks = 0;
    }
}
