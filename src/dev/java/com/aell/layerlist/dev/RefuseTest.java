package com.aell.layerlist.dev;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.util.LayerMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Режим {@code refuse}: тумблер «Бросать клетку после отказов сервера» (жалоба 10.10.2026: игрок перебирает торги
 * жителя кафедрой, после «сервер слишком много раз отказал по клетке» кафедра на то же место больше не ставится).
 *
 * <p>Схема — одна кафедра. Пока «сервер против», серверный {@link UseBlockCallback} отвечает FAIL на клик рядом с её
 * клеткой: клиент предсказал установку, сервер её откатил — ровно то, что мод считает отказом. (Житель в клетке отказа
 * не даёт: клиент видит его сам, и Easy Place туда не кликает.)
 * <ol>
 *   <li>Тумблер вкл: Easy Place жмёт 4 с — в чате ровно одно «сервер слишком много раз отказал», кафедры нет.
 *       Сервер больше не против — Easy Place клетку уже не трогает (брошена), а ручной клик без Easy Place ставит.</li>
 *   <li>Тумблер выкл: сервер снова против, Easy Place жмёт 4 с — в чате ничего нового. Сервер не против —
 *       Easy Place ставит кафедру сам.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class RefuseTest implements CovJob
{
    private static final BlockPos REF = new BlockPos(1000, 100, 160);
    private static final BlockPos TARGET = new BlockPos(1000, 100, 180);
    private static final long PRESS_MS = 4000L;

    private static volatile boolean serverAgainst;
    private static boolean hooked;

    private enum Stage { SNAPSHOT, ON_BLOCKED, ON_FREE, ON_HAND, OFF_SETUP, OFF_BLOCKED, OFF_FREE, DONE }

    private Stage stage = Stage.SNAPSHOT;
    private long nextAt;
    private long until;
    private long lastTick = Long.MIN_VALUE;
    private SchematicPlacement placement;
    private ConfigBoolean toggle;
    private boolean toggleWas;
    private final List<ConfigBoolean> chat = new ArrayList<>();
    private final List<Boolean> chatWas = new ArrayList<>();
    private boolean settled;
    private int messagesBefore;
    private int ok;
    private int failed;

    @Override
    public String name()
    {
        return "REFUSE (тумблер «Бросать клетку после отказов сервера»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        this.toggle = findToggle();

        if (this.toggle == null)
        {
            CovOut.log("[refuse] тумблер easyPlaceGiveUpRefused не найден");
            return false;
        }

        if (! hooked)
        {
            hooked = true;
            UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                    serverAgainst && ! level.isClientSide()
                            && (hit.getBlockPos().equals(TARGET) || hit.getBlockPos().relative(hit.getDirection()).equals(TARGET))
                            ? InteractionResult.FAIL : InteractionResult.PASS);
        }

        serverAgainst = false;
        this.toggleWas = this.toggle.getBooleanValue();
        this.toggle.setBooleanValue(true);

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        BuildTest.applyProfile("user");

        // У игрока «Сообщения в чат» выключены — а проверяем как раз сообщение; отладка Easy Place включена
        // и за секунды выталкивает его из ста строк истории чата
        for (String name : List.of("com.aell.easyplacefixplus.config.FixConfigs", "com.aell.layerlist.config.LayerListConfigs"))
        {
            for (String field : List.of("CHAT_MESSAGES", "EP_DEBUG"))
            {
                try
                {
                    ConfigBoolean option = (ConfigBoolean) Class.forName(name).getField(field).get(null);
                    this.chat.add(option);
                    this.chatWas.add(option.getBooleanValue());
                    option.setBooleanValue(field.equals("CHAT_MESSAGES"));
                }
                catch (ReflectiveOperationException ignored)
                {
                }
            }
        }
        CovOut.log("[refuse] Easy Place ведёт "
                + (FabricLoader.getInstance().isModLoaded("easyplacefixplus") ? "Easy Place Fix+" : "Material HUD"));

        server.execute(() -> {
            command(server, "gamemode creative @a");
            command(server, String.format(Locale.ROOT, "forceload add %d %d %d %d", REF.getX() - 4, REF.getZ() - 4,
                    TARGET.getX() + 4, TARGET.getZ() + 4));
            command(server, "gamerule advance_time false");
            command(server, "time set noon");
            ServerLevel level = server.overworld();

            for (BlockPos o : List.of(REF, TARGET))
            {
                for (BlockPos p : BlockPos.betweenClosed(o.offset(-3, -1, -3), o.offset(3, 4, 3)))
                {
                    level.setBlock(p, p.getY() < o.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }

            }

            level.setBlock(REF, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH), 2);
            command(server, String.format(Locale.ROOT, "tp @a %.1f %d %.1f", TARGET.getX() + 0.5D, TARGET.getY() + 2,
                    TARGET.getZ() - 2.5D));
        });

        this.nextAt = Util.getMillis() + 3000L;
        return true;
    }

    @Override
    public boolean waiting()
    {
        return Util.getMillis() < this.nextAt;
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
        hold(mc);

        switch (this.stage)
        {
            case SNAPSHOT ->
            {
                AreaSelection area = new AreaSelection();
                area.setName("dev-refuse");
                area.addSubRegionBox(new Box(REF, REF, "main"), false);
                area.setExplicitOrigin(REF);
                LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, false, false), "dev", CovOut::log);

                if (schematic == null)
                {
                    CovOut.log("[refuse] схема не снялась");
                    return true;
                }

                this.placement = SchematicPlacement.createFor(schematic, TARGET, "dev-refuse", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);

                server.execute(() -> {
                    command(server, "gamemode survival @a");
                    command(server, "clear @a");

                    for (ServerPlayer player : server.getPlayerList().getPlayers())
                    {
                        // Клиент списывает предмет за каждый предсказанный клик, а отказ сервера инвентарь не
                        // пересылает: с запасом, чтобы рука не «опустела» у клиента раньше времени
                        player.getInventory().add(new ItemStack(Items.LECTERN, 64));
                        player.getAbilities().mayfly = true;
                        player.getAbilities().flying = true;
                        player.getAbilities().invulnerable = true;
                        player.onUpdateAbilities();
                    }

                });

                serverAgainst = true;
                this.messagesBefore = refusals(mc);
                this.go(Stage.ON_BLOCKED, now, 2000L);
            }
            case ON_BLOCKED ->
            {
                if (this.press(mc, now))
                {
                    return false;
                }

                this.record("вкл, сервер против: сообщений об отказе", 1, refusals(mc) - this.messagesBefore);
                this.record("вкл, сервер против: кафедра", "нет", lectern(mc));
                serverAgainst = false;
                this.go(Stage.ON_FREE, now, 1500L);
            }
            case ON_FREE ->
            {
                if (this.press(mc, now))
                {
                    return false;
                }

                this.record("вкл, сервер не против: Easy Place клетку бросил", "нет", lectern(mc));
                this.go(Stage.ON_HAND, now, 300L);
            }
            case ON_HAND ->
            {
                // Ручной клик: Easy Place выключен, клавиша активации не зажата — бросать клетку не наше дело
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
                look(mc, Vec3.atCenterOf(TARGET.below()).add(0.0D, 0.5D, 0.0D));
                useItem(mc);
                Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
                this.go(Stage.OFF_SETUP, now, 1500L);
            }
            case OFF_SETUP ->
            {
                this.record("вкл, ручной клик ставит", "есть", lectern(mc));
                this.toggle.setBooleanValue(false);
                server.execute(() -> {
                    server.overworld().setBlock(TARGET, Blocks.AIR.defaultBlockState(), 2);

                    for (ServerPlayer player : server.getPlayerList().getPlayers())
                    {
                        player.getInventory().clearContent();
                        player.getInventory().add(new ItemStack(Items.LECTERN, 64));
                        player.inventoryMenu.sendAllDataToRemote();
                    }

                });
                serverAgainst = true;
                this.messagesBefore = refusals(mc);
                this.go(Stage.OFF_BLOCKED, now, 2000L);
            }
            case OFF_BLOCKED ->
            {
                if (this.press(mc, now))
                {
                    return false;
                }

                this.record("выкл, сервер против: сообщений об отказе", 0, refusals(mc) - this.messagesBefore);
                this.record("выкл, сервер против: кафедра", "нет", lectern(mc));
                serverAgainst = false;
                this.go(Stage.OFF_FREE, now, 1500L);
            }
            case OFF_FREE ->
            {
                if (this.press(mc, now) && lectern(mc).equals("нет"))
                {
                    return false;
                }

                this.record("выкл, сервер не против: Easy Place ставит", "есть", lectern(mc));
                this.stage = Stage.DONE;
            }
            case DONE ->
            {
                return true;
            }
        }

        return this.stage == Stage.DONE;
    }

    private void go(Stage next, long now, long delay)
    {
        this.stage = next;
        this.nextAt = now + delay;
        this.until = this.nextAt + PRESS_MS;
    }

    /**
     * Easy Place раз в тик, пока не вышло время; true — ещё жмём. После — пауза, чтобы откат сервера дошёл:
     * предсказанная клиентом кафедра видна до ответа, и снимок посреди потока кликов ловил бы её.
     */
    private boolean press(Minecraft mc, long now)
    {
        if (now > this.until)
        {
            if (! this.settled)
            {
                this.settled = true;
                this.nextAt = now + 700L;
                return true;
            }

            this.settled = false;
            return false;
        }

        long tick = mc.level.getGameTime();

        if (tick != this.lastTick)
        {
            this.lastTick = tick;
            look(mc, Vec3.atCenterOf(TARGET));
            WorldUtils.handleEasyPlace(mc);
        }

        return true;
    }

    private static String lectern(Minecraft mc)
    {
        return mc.level.getBlockState(TARGET).is(Blocks.LECTERN) ? "есть" : "нет";
    }

    /** Сколько в чате строк «сервер слишком много раз отказал». */
    private static int refusals(Minecraft mc)
    {
        String needle = Component.translatable(FabricLoader.getInstance().isModLoaded("easyplacefixplus")
                ? "easyplacefixplus.message.ep_refused" : "layerlist.message.ep_refused", "x", "y", "z").getString();
        needle = needle.substring(0, Math.min(24, needle.length()));
        int count = 0;

        try
        {
            ChatComponent chat = mc.gui.getChat();

            for (Field field : ChatComponent.class.getDeclaredFields())
            {
                if (! List.class.isAssignableFrom(field.getType()))
                {
                    continue;
                }

                field.setAccessible(true);
                List<?> list = (List<?>) field.get(chat);
                int here = 0;

                for (Object line : list)
                {
                    Method content = findMethod(line.getClass(), "content");

                    if (content != null && content.invoke(line) instanceof Component text && text.getString().contains(needle))
                    {
                        ++here;
                    }
                }

                count = Math.max(count, here);
            }
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[refuse] чат не прочитался: " + e);
        }

        return count;
    }

    private static Method findMethod(Class<?> type, String name)
    {
        try
        {
            Method method = type.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
    }

    /** Тумблер того мода, что ведёт Easy Place: Easy Place Fix+, если стоит, иначе наш. */
    private static ConfigBoolean findToggle()
    {
        for (String name : List.of("com.aell.easyplacefixplus.config.FixConfigs", "com.aell.layerlist.config.LayerListConfigs"))
        {
            try
            {
                return (ConfigBoolean) Class.forName(name).getField("EP_GIVE_UP_REFUSED").get(null);
            }
            catch (ReflectiveOperationException ignored)
            {
            }
        }

        return null;
    }

    /** Обычный правый клик: ваниль сама берёт цель под прицелом. */
    private static void useItem(Minecraft mc)
    {
        try
        {
            mc.hitResult = mc.player.pick(5.0D, 1.0F, false);
            Method start = Minecraft.class.getDeclaredMethod("startUseItem");
            start.setAccessible(true);
            start.invoke(mc);
        }
        catch (ReflectiveOperationException e)
        {
            CovOut.log("[refuse] ручной клик не прошёл: " + e);
        }
    }

    private static void hold(Minecraft mc)
    {
        if (! mc.player.getAbilities().flying)
        {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }
    }

    private static void look(Minecraft mc, Vec3 point)
    {
        Vec3 eyes = mc.player.getEyePosition(1.0F);
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
    }

    private void record(String subject, Object expected, Object actual)
    {
        boolean pass = String.valueOf(expected).equals(String.valueOf(actual));

        if (pass)
        {
            ++this.ok;
        }
        else
        {
            ++this.failed;
        }

        CovOut.log("[refuse] " + (pass ? "OK  " : "FAIL") + " " + subject + " — ждали " + expected + ", вышло " + actual);
    }

    private static void command(MinecraftServer server, String command)
    {
        try
        {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
        catch (RuntimeException e)
        {
            CovOut.log("[refuse] команда не прошла (" + command + "): " + e);
        }
    }

    @Override
    public void close()
    {
        serverAgainst = false;
        BuildTest.resetProfile();

        for (int i = 0; i < this.chat.size(); ++i)
        {
            this.chat.get(i).setBooleanValue(this.chatWas.get(i));
        }

        if (this.toggle != null)
        {
            this.toggle.setBooleanValue(this.toggleWas);
        }

        if (this.placement != null)
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        CovOut.log("[refuse] итог: OK " + this.ok + ", FAIL " + this.failed);
    }
}
