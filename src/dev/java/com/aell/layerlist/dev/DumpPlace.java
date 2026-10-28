package com.aell.layerlist.dev;

import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.PlaceSolver;
import com.aell.layerlist.easyplace.WaterPlace;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Дамп C: фактическая установка. Клик уходит на встроенный сервер ровно так же, как его
 * шлёт модуль — поза объявляется пакетом поворота, точка попадания берётся из плана
 * решателя, — а потом читается состояние, которое сервер действительно поставил. Это
 * единственный дамп, который меняет мир: перед каждой пробой станция возвращается в
 * исходное состояние, и только после этого считается план.
 *
 * <p>Ширина важнее глубины: на блок берётся до {@value #PER_BLOCK} состояний — значение по
 * умолчанию плюс равномерная выборка из остальных.
 */
@Environment(EnvType.CLIENT)
public final class DumpPlace implements CovJob
{
    private static final String HEADER = String.join(",",
            "key", "id", "state", "station", "solver", "expected", "actual", "result", "pose",
            "hand", "use_result", "server_hand", "server_pos", "note");

    private static final int PER_BLOCK = 4;
    private static final int SETTLE = 3;

    private enum Step
    {
        SETUP,
        WAIT,
        CLICK,
        READ
    }

    private CovOut out;
    private final List<BlockState> queue = new ArrayList<>();
    private int index;
    private Step step = Step.SETUP;
    private long waitUntil;
    private CovArena.Station station = CovArena.Station.FLOOR;
    private BlockState wanted;
    private BlockState expected;
    private LookSpoof.Pose pose;
    private BlockHitResult hit;
    private String solver = "";
    private String useResult = "";
    private volatile BlockState actual;
    private volatile String serverHand = "";
    private volatile String serverPos = "";
    private volatile boolean read;

    @Override
    public String name()
    {
        return "C/place.csv";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            this.out = CovOut.open("place.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("не открыть place.csv: " + e);
            return false;
        }

        for (Block block : BuiltInRegistries.BLOCK)
        {
            if (! (block.asItem() instanceof BlockItem) || block.asItem() == Items.AIR)
            {
                continue;
            }

            List<BlockState> all = DumpSolver.candidates(block);
            BlockState def = block.defaultBlockState();

            this.queue.add(def);

            int stride = Math.max(1, all.size() / PER_BLOCK);
            int taken = 1;

            for (int i = 1; i < all.size() && taken < PER_BLOCK; i += stride)
            {
                if (! all.get(i).equals(def))
                {
                    this.queue.add(all.get(i));
                    ++taken;
                }
            }
        }

        String limit = System.getenv("LL_COVERAGE_LIMIT");

        if (limit != null && ! limit.isBlank())
        {
            int max = Integer.parseInt(limit.trim());

            while (this.queue.size() > max)
            {
                this.queue.remove(this.queue.size() - 1);
            }
        }

        CovOut.log("C: состояний к установке " + this.queue.size());

        return mc.level != null && mc.player != null && mc.getSingleplayerServer() != null;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (server == null || level == null || player == null)
        {
            return true;
        }

        return switch (this.step)
        {
            case SETUP -> this.setup(server);
            case WAIT -> this.countdown(level, Step.CLICK);
            case CLICK -> this.click(mc, level, player);
            case READ -> this.record();
        };
    }

    /** Чистим все станции разом: план считается уже по чистому миру. */
    private boolean setup(MinecraftServer server)
    {
        while (this.index < this.queue.size()
                && this.out.has(DumpSolver.key(this.queue.get(this.index))))
        {
            ++this.index;
        }

        if (this.index >= this.queue.size())
        {
            return true;
        }

        this.wanted = this.queue.get(this.index);

        if ((this.index & 63) == 0)
        {
            CovOut.log("C: " + this.index + "/" + this.queue.size());
        }

        ItemStack stack = new ItemStack(this.wanted.getBlock().asItem());

        server.execute(() ->
        {
            for (CovArena.Station station : CovArena.Station.values())
            {
                CovArena.reset(server.overworld(), station);
            }

            // предмет кладём прямо в серверный инвентарь: пакет креативного слота
            // доходит с задержкой в несколько тиков, и клик уходил бы с пустой рукой
            var players = server.getPlayerList().getPlayers();

            if (! players.isEmpty())
            {
                players.get(0).getInventory().setItem(0, stack.copy());
                players.get(0).getInventory().setSelectedSlot(0);
                players.get(0).inventoryMenu.broadcastChanges();
            }
        });

        this.waitUntil = server.overworld().getGameTime() + SETTLE;
        this.step = Step.WAIT;

        return false;
    }

    /**
     * Ждём именно тики, а не вызовы: клетка чистится на сервере, а план считается по
     * клиентскому миру, и без настоящей паузы план опирался бы на вчерашнюю картинку.
     */
    private boolean countdown(ClientLevel level, Step next)
    {
        if (level.getGameTime() >= this.waitUntil)
        {
            this.step = next;
        }

        return false;
    }

    private boolean click(Minecraft mc, ClientLevel level, LocalPlayer player)
    {
        Item item = this.wanted.getBlock().asItem();
        ItemStack stack = new ItemStack(item);

        this.solver = "";
        this.expected = null;
        this.pose = null;
        this.hit = null;
        this.useResult = "";

        int bestMiss = Integer.MAX_VALUE;

        // как и в дампе B: станция выбирается та, где состояние выходит точно, а не
        // первая, где блок вообще встал
        for (CovArena.Station candidate : order(stack))
        {
            BlockPos target = CovArena.target(candidate);
            PlaceSolver.Plan plan = PlaceSolver.solve(level, player, InteractionHand.MAIN_HAND, stack,
                    target, this.wanted, null);

            BlockState got = plan == null ? null : plan.expected();
            LookSpoof.Pose gotPose = plan == null ? null : plan.pose();
            BlockHitResult gotHit = plan == null ? null : plan.hit();
            String tag = plan == null ? null : (plan.perfect() ? "PERFECT" : "PARTIAL");

            if (plan == null || ! plan.perfect())
            {
                CovSim.Result sim = CovSim.sweep(level, player, InteractionHand.MAIN_HAND, stack,
                        target, this.wanted, 4096);

                if (sim.expected() != null
                        && (got == null || sim.matched() > CovSim.matched(got, this.wanted)))
                {
                    got = sim.expected();
                    gotPose = sim.pose();
                    gotHit = sim.hit();
                    tag = plan == null ? "SWEEP" : "SOLVER_MISS";
                }
            }

            if (got == null || gotHit == null || gotPose == null)
            {
                continue;
            }

            int miss = CovSim.diff(got, this.wanted).size();

            if (miss < bestMiss)
            {
                bestMiss = miss;
                this.station = candidate;
                this.expected = got;
                this.pose = gotPose;
                this.hit = gotHit;
                this.solver = tag == null ? "" : tag;
            }

            if (miss == 0)
            {
                break;
            }
        }

        if (this.expected == null || this.hit == null || this.pose == null)
        {
            this.out.row(List.of(DumpSolver.key(this.wanted), DumpSolver.id(this.wanted),
                    DumpSolver.props(this.wanted), "", "NONE", "", "", "NO_PLAN", "", "", "", "",
                    "", ""));
            ++this.index;
            this.step = Step.SETUP;

            return false;
        }

        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null)
        {
            return true;
        }

        BlockPos target = CovArena.target(this.station);
        BlockHitResult hit = this.hit;
        LookSpoof.Pose pose = this.pose;

        this.read = false;
        this.actual = null;

        // Клик исполняет сама серверная сторона: ровно тот код, который вызывает приход
        // ServerboundUseItemOnPacket, но без сетевого пути — поза, предмет и точка
        // попадания те же, что ушли бы в пакете.
        server.execute(() ->
        {
            var players = server.getPlayerList().getPlayers();

            if (players.isEmpty())
            {
                this.read = true;
                return;
            }

            var sp = players.get(0);
            ItemStack inHand = new ItemStack(item);

            // LivingEntity.getViewYRot отдаёт yHeadRot, а не yRot: без поворота головы
            // всё, что смотрит на getNearestLookingDirection (раздатчик, наблюдатель,
            // поршень, воронка), встало бы по старому взгляду
            sp.setYRot(pose.yaw());
            sp.setXRot(pose.pitch());
            sp.setYHeadRot(pose.yaw());
            sp.yHeadRotO = pose.yaw();
            sp.yRotO = pose.yaw();
            sp.xRotO = pose.pitch();
            sp.setShiftKeyDown(pose.sneak());
            sp.getInventory().setSelectedSlot(0);
            sp.getInventory().setItem(0, inHand);

            try
            {
                InteractionResult result = sp.gameMode.useItemOn(sp, sp.level(), inHand,
                        InteractionHand.MAIN_HAND, hit);

                this.useResult = result == null ? "null" : result.getClass().getSimpleName();
            }
            catch (RuntimeException | LinkageError e)
            {
                this.useResult = "EX:" + e;
            }

            this.actual = sp.level().getBlockState(target);
            this.serverHand = BuiltInRegistries.ITEM.getKey(sp.getMainHandItem().getItem()).toString();
            this.serverPos = String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f", sp.getX(),
                    sp.getY(), sp.getZ());
            this.read = true;
        });

        this.step = Step.READ;

        return false;
    }

    private boolean record()
    {
        if (! this.read)
        {
            return false;
        }

        BlockState got = this.actual;
        String result;
        String note = "";

        if (got == null || ! got.is(this.wanted.getBlock()))
        {
            result = "NOT_PLACED";
            note = got == null ? "" : DumpSolver.id(got);
        }
        else if (CovSim.diff(got, this.wanted).isEmpty())
        {
            result = "MATCH";
        }
        else
        {
            result = "MISMATCH";

            List<String> names = new ArrayList<>();

            CovSim.diff(got, this.wanted).forEach(property -> names.add(property.getName()));
            note = String.join("|", names);
        }

        if (got != null && this.expected != null && got.is(this.expected.getBlock())
                && ! CovSim.diff(got, this.expected).isEmpty())
        {
            note = note.isEmpty() ? "prediction_off" : note + ";prediction_off";
        }

        this.out.row(List.of(DumpSolver.key(this.wanted), DumpSolver.id(this.wanted),
                DumpSolver.props(this.wanted), this.station.name(), this.solver,
                this.expected == null ? "" : DumpSolver.props(this.expected),
                got == null ? "" : DumpSolver.props(got), result,
                this.pose == null ? "" : this.pose.yaw() + "/" + this.pose.pitch(), "",
                this.useResult, this.serverHand, this.serverPos, note));

        ++this.index;
        this.step = Step.SETUP;

        return false;
    }

    private static List<CovArena.Station> order(ItemStack stack)
    {
        if (WaterPlace.is(stack))
        {
            return List.of(CovArena.Station.ONWATER, CovArena.Station.WATER, CovArena.Station.FLOOR,
                    CovArena.Station.WALL, CovArena.Station.CEIL, CovArena.Station.BOX);
        }

        return List.of(CovArena.Station.FLOOR, CovArena.Station.WALL, CovArena.Station.CEIL,
                CovArena.Station.SOIL, CovArena.Station.FARM, CovArena.Station.SAND,
                CovArena.Station.ONWATER, CovArena.Station.WATER, CovArena.Station.DARK,
                CovArena.Station.BOX);
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            CovOut.log("place.csv: строк " + this.out.doneCount());
            this.out.close();
        }
    }
}
