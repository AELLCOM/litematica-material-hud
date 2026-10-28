package com.aell.layerlist.dev;

import com.aell.layerlist.storage.StorageIndex;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code f}: фризы при открытии сундука (жалоба 08.10.2026). Замеряет запись индекса хранилищ
 * ({@code StorageIndex.save}) на том индексе, что лежит в {@code run/config}, и самый долгий клиентский тик
 * при настоящем открытии и закрытии сундука (между START и END клиентского тика — там и стоит запись).
 */
@Environment(EnvType.CLIENT)
public final class FreezeTest implements CovJob
{
    private static final BlockPos CHEST = new BlockPos(380, 100, 0);

    private static long tickStart;
    private static long worstTick;
    private static boolean hooked;

    private int stage;
    private long nextAt;

    @Override
    public String name()
    {
        return "F (фризы у сундука)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        if (! hooked)
        {
            hooked = true;
            ClientTickEvents.START_CLIENT_TICK.register(c -> tickStart = Util.getNanos());
            ClientTickEvents.END_CLIENT_TICK.register(c -> worstTick = Math.max(worstTick, Util.getNanos() - tickStart));
        }

        server.execute(() -> {
            command(server, "forceload add 370 -10 390 10");
            command(server, "fill 376 99 -4 384 99 6 minecraft:stone");
            command(server, "fill 376 100 -4 384 103 6 minecraft:air");
            command(server, "setblock 380 100 0 minecraft:chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:stone\",count:5}]}");
            command(server, "gamemode creative @a");
            command(server, "clear @a");
            command(server, "tp @a 380.5 100 2.5 180 20");
        });

        // Схема рядом, как у игрока на стройке: клик по сундуку будит пересчёт списка материалов
        var path = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("schematics").resolve("Портал.litematic");
        var schematic = fi.dy.masa.litematica.data.SchematicHolder.getInstance().getOrLoad(path);

        if (schematic != null)
        {
            var placement = fi.dy.masa.litematica.schematic.placement.SchematicPlacement.createFor(schematic,
                    new BlockPos(370, 100, 8), "freeze", true, true);
            fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
            fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
            com.aell.layerlist.config.LayerListConfigs.HUD_ENABLED = true;
        }

        this.nextAt = Util.getMillis() + 8000L;
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        long now = Util.getMillis();

        if (now < this.nextAt || mc.player == null)
        {
            return false;
        }

        this.nextAt = now + 1500L;

        switch (this.stage++)
        {
            case 0 ->
            {
                int records = StorageIndex.getInstance().recordsInScope(StorageIndex.scope(mc)).size();
                long worst = 0L;
                long sum = 0L;

                long background = 0L;

                for (int i = 0; i < 5; ++i)
                {
                    dirty();
                    long t = Util.getNanos();
                    StorageIndex.getInstance().save();
                    long d = Util.getNanos() - t;
                    worst = Math.max(worst, d);
                    sum += d;
                    long f = Util.getNanos();
                    StorageIndex.getInstance().flush();
                    background = Math.max(background, Util.getNanos() - f);
                }

                CovOut.log(String.format("[freeze] фоновая запись файла: до %.1f мс (в своём потоке)", background / 1e6));

                CovOut.log(String.format("[freeze] save() в игровом потоке: среднее %.1f мс, худшее %.1f мс (записей в этом мире %d, файл %d КБ)",
                        sum / 5 / 1e6, worst / 1e6, records,
                        net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("layerlist_storage.json").toFile().length() / 1024));
            }
            case 1 -> worstTick = 0L;
            case 2 -> CovOut.log(String.format("[freeze] фон: худший тик %.1f мс", worstTick / 1e6));
            case 3 ->
            {
                worstTick = 0L;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CHEST).add(0, 0, 0.5), Direction.SOUTH, CHEST, false));
            }
            case 4 ->
            {
                CovOut.log(String.format("[freeze] открытие сундука: худший тик %.1f мс (экран %s)", worstTick / 1e6,
                        fi.dy.masa.malilib.util.GuiUtils.getCurrentScreen()));
                worstTick = 0L;
                mc.player.closeContainer();
            }
            case 5 -> CovOut.log(String.format("[freeze] закрытие сундука: худший тик %.1f мс", worstTick / 1e6));
            default ->
            {
                CovOut.log("[freeze] итог: готово");
                return true;
            }
        }

        return false;
    }

    /** Флаг «есть что писать» — иначе save() без изменений ничего не делает. */
    private static void dirty()
    {
        try
        {
            var field = StorageIndex.class.getDeclaredField("dirty");
            field.setAccessible(true);
            field.setBoolean(StorageIndex.getInstance(), true);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[freeze] поле dirty не найдено: " + e);
        }
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[freeze] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close() {}
}
